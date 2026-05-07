package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RemoteDumpService {

    private static final Logger logger = LoggerFactory.getLogger(RemoteDumpService.class);
    private static final int SSH_TIMEOUT_SEC = 30;
    private static final int SCP_TIMEOUT_SEC = 600; // 10분

    private final TargetServerRepository serverRepository;
    private final DumpTransferLogRepository transferLogRepository;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final HeapDumpConfig config;

    // 동적 스캔 주기 (초) — Settings에서 런타임 변경 가능
    private volatile int scanIntervalSec;
    private final AtomicLong lastScanTime = new AtomicLong(0);

    // SSH/SCP 로컬 실행 계정 — Settings에서 런타임 변경 가능
    private volatile String sshLocalUser;

    // SCP 임시 저장 경로 — Settings에서 런타임 변경 가능
    private volatile String scpTempDir;

    // 마지막 자동 스캔 에러 기록 (서버 ID → 에러 메시지)
    private final Map<Long, String> lastAutoScanErrors = new java.util.concurrent.ConcurrentHashMap<>();

    public RemoteDumpService(TargetServerRepository serverRepository,
                             DumpTransferLogRepository transferLogRepository,
                             AnalysisHistoryRepository analysisHistoryRepository,
                             HeapDumpConfig config) {
        this.serverRepository = serverRepository;
        this.transferLogRepository = transferLogRepository;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.config = config;
        this.scanIntervalSec = config.getRemoteScanIntervalSec();
        this.sshLocalUser = config.getSshLocalUser();
        this.scpTempDir = config.getScpTempDir();
    }

    /** 부팅 시 1회 실행 — 레거시 transfer 로그(remote_filename NULL)를 filename으로 보정. */
    @PostConstruct
    public void backfillRemoteFilenames() {
        try {
            int updated = transferLogRepository.backfillRemoteFilenameFromLocal();
            if (updated > 0) {
                logger.info("[RemoteDump] Backfilled remote_filename for {} legacy transfer log row(s)", updated);
            }
        } catch (Exception e) {
            logger.warn("[RemoteDump] remote_filename backfill 실패 (무시 가능): {}", e.getMessage());
        }
    }

    // ── 스캔 주기 getter/setter ───────────────────────────────

    public int getScanIntervalSec() { return scanIntervalSec; }

    public void setScanIntervalSec(int sec) {
        this.scanIntervalSec = Math.max(10, sec);
        logger.info("[RemoteDump] Scan interval changed to {}s", this.scanIntervalSec);
    }

    public String getSshLocalUser() { return sshLocalUser; }

    public void setSshLocalUser(String user) {
        this.sshLocalUser = (user != null) ? user.trim() : "";
        logger.info("[RemoteDump] SSH local user changed to '{}'", this.sshLocalUser);
    }

    public String getScpTempDir() { return scpTempDir; }

    public void setScpTempDir(String dir) {
        this.scpTempDir = (dir != null && !dir.trim().isEmpty()) ? dir.trim() : "/tmp";
        logger.info("[RemoteDump] SCP temp dir changed to '{}'", this.scpTempDir);
    }

    public Map<Long, String> getLastAutoScanErrors() {
        return Collections.unmodifiableMap(lastAutoScanErrors);
    }

    /**
     * SSH 연결 테스트 — 성공/실패 시 서버 상태 DB 업데이트
     */
    public Map<String, Object> testConnection(TargetServer server) {
        Map<String, Object> result = new HashMap<>();
        try {
            String[] cmd = buildSshCommand(server, "echo OK && hostname");
            ProcessResult pr = executeCommand(cmd, SSH_TIMEOUT_SEC);
            if (pr.exitCode == 0 && pr.stdout.contains("OK")) {
                result.put("success", true);
                result.put("hostname", pr.stdout.replace("OK", "").trim());
                result.put("message", "연결 성공");
                updateServerStatus(server, "OK", null);
            } else {
                String errMsg = "SSH 연결 실패: " + cleanSshError(pr.stderr);
                result.put("success", false);
                result.put("message", errMsg);
                updateServerStatus(server, "FAIL", errMsg);
            }
        } catch (Exception e) {
            String errMsg = "SSH 연결 오류: " + e.getMessage();
            result.put("success", false);
            result.put("message", errMsg);
            updateServerStatus(server, "FAIL", errMsg);
        }
        return result;
    }

    /**
     * 원격 서버에서 힙 덤프 파일 목록 스캔 — 다중 경로(최대 5개) 모두 순회.
     * - 모든 경로가 같은 종류(NOT_FOUND / NOT_READABLE / SSH_ERROR)로 실패하면 그 errorCode 반환
     * - 일부만 실패하면 partial 성공: 성공한 경로의 파일은 반환, 실패 경로는 pathErrors에 기록
     * - 한 경로라도 성공하면 서버 상태는 OK
     */
    public Map<String, Object> scanRemoteDumpsWithStatus(TargetServer server) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> pathErrors = new ArrayList<>();

        List<String> paths = server.getDumpPaths();
        if (paths.isEmpty()) {
            String errorMsg = "덤프 경로가 설정되지 않았습니다.";
            result.put("errorCode", "NO_DUMP_PATH");
            result.put("error", errorMsg);
            result.put("files", files);
            result.put("count", 0);
            updateServerStatus(server, "FAIL", errorMsg);
            return result;
        }

        // 같은 파일 path 중복 제거 (다중 경로가 겹치는 경우)
        Set<String> seenPaths = new HashSet<>();
        int successCount = 0;
        String firstFatalError = null;
        String firstFatalCode = null;

        for (String dumpPath : paths) {
            try {
                Map<String, Object> single = scanSinglePath(server, dumpPath);
                if (single.containsKey("error")) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("dumpPath", dumpPath);
                    err.put("errorCode", single.get("errorCode"));
                    err.put("error", single.get("error"));
                    pathErrors.add(err);
                    if (firstFatalError == null) {
                        firstFatalError = (String) single.get("error");
                        firstFatalCode = (String) single.get("errorCode");
                    }
                } else {
                    successCount++;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> pathFiles = (List<Map<String, Object>>) single.get("files");
                    for (Map<String, Object> f : pathFiles) {
                        String p = (String) f.get("path");
                        if (p != null && seenPaths.add(p)) {
                            files.add(f);
                        }
                    }
                }
            } catch (Exception e) {
                Map<String, Object> err = new HashMap<>();
                err.put("dumpPath", dumpPath);
                err.put("errorCode", "SCAN_EXCEPTION");
                err.put("error", "스캔 실패: " + e.getMessage());
                pathErrors.add(err);
                if (firstFatalError == null) {
                    firstFatalError = "스캔 실패: " + e.getMessage();
                    firstFatalCode = "SCAN_EXCEPTION";
                }
                logger.error("[RemoteDump] Scan failed for {} path={}: {}", server.getName(), dumpPath, e.getMessage());
            }
        }

        result.put("files", files);
        result.put("count", files.size());
        if (!pathErrors.isEmpty()) result.put("pathErrors", pathErrors);

        if (successCount == 0) {
            // 모든 경로 실패
            result.put("errorCode", firstFatalCode != null ? firstFatalCode : "SCAN_EXCEPTION");
            result.put("error", firstFatalError != null ? firstFatalError : "모든 경로 스캔 실패");
            updateServerStatus(server, "FAIL", firstFatalError);
        } else {
            updateServerStatus(server, "OK", null);
        }
        logger.info("[RemoteDump] Scanned {} files across {}/{} path(s) on server {}",
                files.size(), successCount, paths.size(), server.getName());
        return result;
    }

    /** 단일 경로 스캔 — error/errorCode/files 키 반환. 상태 DB 업데이트는 호출자에서 집계. */
    private Map<String, Object> scanSinglePath(TargetServer server, String dumpPath) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();

        // 경로 존재/권한 체크를 find 앞에 두어 exit 2/3으로 명확히 구분
        String safePath = dumpPath.replace("'", "'\\''");
        String findCmd = String.format(
            "[ -d '%s' ] || { echo HEAPDUMP_PATH_NOT_FOUND >&2; exit 2; }; "
            + "[ -r '%s' ] || { echo HEAPDUMP_PATH_NOT_READABLE >&2; exit 3; }; "
            + "find '%s' -maxdepth 2 -type f \\( -name '*.hprof' -o -name '*.hprof.gz' -o -name '*.bin' -o -name '*.dump' \\) -exec ls -la {} \\; 2>/dev/null",
            safePath, safePath, safePath);
        String[] cmd = buildSshCommand(server, findCmd);
        ProcessResult pr = executeCommand(cmd, SSH_TIMEOUT_SEC);

        if (pr.exitCode == 2) {
            result.put("errorCode", "DUMP_PATH_NOT_FOUND");
            result.put("error", "원격 덤프 경로가 존재하지 않습니다: " + dumpPath);
            logger.warn("[RemoteDump] Dump path not found on {}: {}", server.getName(), dumpPath);
        } else if (pr.exitCode == 3) {
            result.put("errorCode", "DUMP_PATH_NOT_READABLE");
            result.put("error", "원격 덤프 경로 읽기 권한이 없습니다: " + dumpPath);
            logger.warn("[RemoteDump] Dump path not readable on {} (user={}): {}",
                    server.getName(), server.getSshUser(), dumpPath);
        } else if (pr.exitCode != 0 && pr.stdout.trim().isEmpty()) {
            String errorMsg = cleanSshError(pr.stderr);
            if (errorMsg.isEmpty()) errorMsg = "원격 명령 실행 실패 (exit " + pr.exitCode + ")";
            result.put("errorCode", "SSH_ERROR");
            result.put("error", "SSH 오류: " + errorMsg);
            logger.warn("[RemoteDump] SSH error on {} path={}: exit={}, stderr={}",
                    server.getName(), dumpPath, pr.exitCode, errorMsg);
        } else if (!pr.stdout.trim().isEmpty()) {
            for (String line : pr.stdout.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> fileInfo = parseLsLine(line);
                if (fileInfo != null) {
                    String filename = (String) fileInfo.get("filename");
                    Long size = (Long) fileInfo.get("size");
                    // 전송됨 판정: SUCCESS 로그 + 로컬 파일 실존(.gz 포함) 모두 충족해야 함.
                    // 같은 원격 파일이 여러 번 전송된 경우(_2/_3 suffix 등) 로컬 파일이 남아있는 row를 우선 채택.
                    List<DumpTransferLog> succLogs = transferLogRepository
                            .findByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(
                                    server.getId(), filename, size, "SUCCESS");
                    File localDir = new File(config.getDumpFilesDirectory());
                    String matchedLocalFilename = null;
                    for (DumpTransferLog sl : succLogs) {
                        String local = sl.getFilename();
                        if (local == null) continue;
                        File f = new File(localDir, local);
                        File gz = new File(localDir, local + ".gz");
                        if (f.exists() || gz.exists()) {
                            matchedLocalFilename = local;
                            break;
                        }
                    }
                    boolean transferred = matchedLocalFilename != null;
                    boolean analyzed = transferred
                            && analysisHistoryRepository.existsByFilename(matchedLocalFilename);
                    fileInfo.put("transferred", transferred);
                    fileInfo.put("analyzed", analyzed);
                    fileInfo.put("sourceDumpPath", dumpPath);
                    files.add(fileInfo);
                }
            }
        }
        result.put("files", files);
        return result;
    }

    /** 하위 호환용 */
    public List<Map<String, Object>> scanRemoteDumps(TargetServer server) {
        Map<String, Object> result = scanRemoteDumpsWithStatus(server);
        return (List<Map<String, Object>>) result.getOrDefault("files", Collections.emptyList());
    }

    /** 전송 진행률 콜백 — bytesTransferred / totalBytes (-1이면 unknown). 예외는 호출자에서 swallow. */
    public interface TransferProgressListener {
        void onProgress(long bytes, long total);
    }

    /**
     * 원격 파일을 SCP로 로컬에 전송 (2단계: 임시경로 → 최종경로)
     * Phase 1: SCP를 sscuser로 실행 → 임시 디렉토리에 저장
     * Phase 2: Files.move()로 앱 계정 권한으로 최종 경로에 이동
     */
    public DumpTransferLog transferFile(TargetServer server, String remoteFilePath) {
        return transferFile(server, remoteFilePath, null);
    }

    /**
     * 진행률 콜백 버전. listener가 null이 아닐 때만 SCP 진행 중 임시 파일 크기를 폴링하여 보고.
     */
    public DumpTransferLog transferFile(TargetServer server, String remoteFilePath,
                                        TransferProgressListener listener) {
        // 원격 원본명 — rename 후에도 변하지 않는 식별자 (scan transferred 판정 키)
        final String remoteFilename = new File(remoteFilePath).getName();
        String filename = remoteFilename;
        DumpTransferLog log = new DumpTransferLog();
        log.setServerId(server.getId());
        log.setFilename(filename);
        log.setRemoteFilename(remoteFilename);
        log.setRemotePath(remoteFilePath);
        log.setTransferStatus("IN_PROGRESS");
        log.setStartedAt(LocalDateTime.now());
        transferLogRepository.save(log);

        File tempFile = null;

        try {
            File localDir = new File(config.getDumpFilesDirectory());
            if (!localDir.exists()) localDir.mkdirs();
            File localFile = new File(localDir, filename);

            if (localFile.exists()) {
                String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
                String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";
                int count = 2;
                while (localFile.exists()) {
                    localFile = new File(localDir, base + "_" + count + ext);
                    count++;
                }
                filename = localFile.getName();
                log.setFilename(filename);
            }

            // 원격 총 크기 사전 조회 (실패해도 전송은 진행 — 진행률은 미정)
            long totalBytes = -1L;
            if (listener != null) {
                totalBytes = fetchRemoteFileSize(server, remoteFilePath);
                safeProgress(listener, 0L, totalBytes);
            }

            // Phase 1: SCP → 임시 경로 (sscuser 쓰기 가능)
            String tempFileName = java.util.UUID.randomUUID().toString().substring(0, 8) + "_" + filename;
            tempFile = new File(scpTempDir, "heapdump_transfer_" + tempFileName);

            String[] cmd = buildScpCommand(server, remoteFilePath, tempFile.getAbsolutePath());
            ProcessResult pr = (listener != null)
                    ? executeCommandWithProgress(cmd, SCP_TIMEOUT_SEC, tempFile, totalBytes, listener)
                    : executeCommand(cmd, SCP_TIMEOUT_SEC);

            if (pr.exitCode == 0 && tempFile.exists() && tempFile.length() > 0) {
                long finalSize = tempFile.length();
                if (listener != null) safeProgress(listener, finalSize, totalBytes > 0 ? totalBytes : finalSize);

                // Phase 2: 임시 파일 → 최종 경로 (앱 계정 권한으로 이동)
                Files.move(tempFile.toPath(), localFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                log.setTransferStatus("SUCCESS");
                log.setFileSize(localFile.length());
                log.setCompletedAt(LocalDateTime.now());
                logger.info("[RemoteDump] Transfer success: {} from {} ({}bytes)",
                        filename, server.getName(), localFile.length());
            } else {
                cleanupTempFile(tempFile);
                String errorMsg = cleanSshError(pr.stderr);
                if (errorMsg.isEmpty()) errorMsg = "파일 전송 실패 (exit " + pr.exitCode + ")";
                log.setTransferStatus("FAILED");
                log.setErrorMessage(errorMsg);
                log.setCompletedAt(LocalDateTime.now());
                logger.error("[RemoteDump] SCP failed: {} from {} — {}",
                        filename, server.getName(), errorMsg);
            }
        } catch (Exception e) {
            cleanupTempFile(tempFile);
            log.setTransferStatus("FAILED");
            log.setErrorMessage(e.getMessage());
            log.setCompletedAt(LocalDateTime.now());
            logger.error("[RemoteDump] Transfer error: {}", e.getMessage());
        }

        transferLogRepository.save(log);
        return log;
    }

    /** SSH `stat -c %s` 로 원격 파일 크기 조회. 실패 시 -1. */
    private long fetchRemoteFileSize(TargetServer server, String remoteFilePath) {
        try {
            String safePath = remoteFilePath.replace("'", "'\\''");
            String[] statCmd = buildSshCommand(server,
                    "stat -c %s '" + safePath + "' 2>/dev/null || wc -c < '" + safePath + "'");
            ProcessResult pr = executeCommand(statCmd, SSH_TIMEOUT_SEC);
            if (pr.exitCode == 0) {
                String s = pr.stdout.trim();
                if (!s.isEmpty()) return Long.parseLong(s);
            }
        } catch (Exception e) {
            logger.debug("[RemoteDump] Failed to fetch remote size for {}: {}", remoteFilePath, e.getMessage());
        }
        return -1L;
    }

    private void safeProgress(TransferProgressListener listener, long bytes, long total) {
        try { listener.onProgress(bytes, total); } catch (Exception ignored) {}
    }

    /**
     * SCP 프로세스 실행 + 500ms마다 tempFile.length()로 진행률 보고.
     * executeCommand와 동일한 stdout/stderr 캡처 + waitFor + timeout.
     */
    private ProcessResult executeCommandWithProgress(String[] cmd, int timeoutSec,
                                                     File tempFile, long totalBytes,
                                                     TransferProgressListener listener) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread outReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {}
        });
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (IOException ignored) {}
        });
        outReader.setDaemon(true);
        errReader.setDaemon(true);
        outReader.start();
        errReader.start();

        Thread monitor = new Thread(() -> {
            while (process.isAlive()) {
                long b = tempFile.exists() ? tempFile.length() : 0L;
                safeProgress(listener, b, totalBytes);
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            monitor.interrupt();
            throw new RuntimeException("Command timed out after " + timeoutSec + " seconds");
        }
        monitor.interrupt();

        outReader.join(5000);
        errReader.join(5000);
        return new ProcessResult(process.exitValue(), stdout.toString(), stderr.toString());
    }

    private void cleanupTempFile(File tempFile) {
        try {
            if (tempFile != null && tempFile.exists()) {
                if (tempFile.delete()) {
                    logger.debug("[RemoteDump] Cleaned up temp file: {}", tempFile.getAbsolutePath());
                } else {
                    logger.warn("[RemoteDump] Failed to delete temp file: {}", tempFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            logger.warn("[RemoteDump] Error cleaning up temp file: {}", e.getMessage());
        }
    }

    /**
     * 자동 탐지 — 10초마다 체크, 설정된 주기가 경과했으면 스캔 실행
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 30000)
    public void autoDetectAndTransfer() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastScanTime.get();
        if (elapsed < (long) scanIntervalSec * 1000) return;
        lastScanTime.set(now);

        List<TargetServer> servers = serverRepository.findByAutoDetectTrueAndEnabledTrue();
        for (TargetServer server : servers) {
            try {
                Map<String, Object> scanResult = scanRemoteDumpsWithStatus(server);

                // 에러 기록
                if (scanResult.containsKey("error")) {
                    lastAutoScanErrors.put(server.getId(), (String) scanResult.get("error"));
                } else {
                    lastAutoScanErrors.remove(server.getId());
                }

                List<Map<String, Object>> remoteDumps =
                        (List<Map<String, Object>>) scanResult.getOrDefault("files", Collections.emptyList());
                for (Map<String, Object> dump : remoteDumps) {
                    boolean transferred = (Boolean) dump.getOrDefault("transferred", false);
                    if (!transferred) {
                        String remotePath = (String) dump.get("path");
                        logger.info("[AutoDetect] New dump found on {}: {}", server.getName(), remotePath);
                        transferFile(server, remotePath);
                    }
                }
            } catch (Exception e) {
                lastAutoScanErrors.put(server.getId(), "자동 탐지 오류: " + e.getMessage());
                updateServerStatus(server, "FAIL", "자동 탐지 오류: " + e.getMessage());
                logger.warn("[AutoDetect] Error scanning server {}: {}", server.getName(), e.getMessage());
            }
        }
    }

    /**
     * 전송 이력 조회
     */
    public List<DumpTransferLog> getTransferLogs(Long serverId) {
        return transferLogRepository.findByServerIdOrderByStartedAtDesc(serverId);
    }

    // ── 서버 상태 업데이트 ─────────────────────────────────────

    private void updateServerStatus(TargetServer server, String status, String errorMsg) {
        try {
            server.setConnStatus(status);
            server.setLastError("OK".equals(status) ? null : errorMsg);
            server.setLastCheckedAt(LocalDateTime.now());
            serverRepository.save(server);
        } catch (Exception e) {
            logger.warn("[RemoteDump] Failed to update server status for {}: {}", server.getName(), e.getMessage());
        }
    }

    // ── Private helpers ──────────────────────────────────────

    private String[] buildSshCommand(TargetServer server, String remoteCommand) {
        // SSH 원격 명령을 큰따옴표로 감싸서 로컬 셸 해석 방지
        String sshCmd = "ssh"
                + " -o StrictHostKeyChecking=no"
                + " -o ConnectTimeout=10"
                + " -o BatchMode=yes"
                + " -p " + server.getPort()
                + " " + server.getSshUser() + "@" + server.getHost()
                + " \"" + remoteCommand.replace("\"", "\\\"") + "\"";
        return wrapWithLocalUser(sshCmd);
    }

    private String[] buildScpCommand(TargetServer server, String remotePath, String localPath) {
        String scpCmd = "scp"
                + " -o StrictHostKeyChecking=no"
                + " -o ConnectTimeout=10"
                + " -o BatchMode=yes"
                + " -P " + server.getPort()
                + " " + server.getSshUser() + "@" + server.getHost() + ":\"" + remotePath + "\""
                + " " + localPath;
        return wrapWithLocalUser(scpCmd);
    }

    /**
     * 로컬 실행 계정이 설정되어 있고 현재 프로세스 계정과 다르면 runuser 로 전환.
     * runuser는 비밀번호 없이 root → 다른 계정으로 전환 가능 (su와 달리 PAM 세션 불필요).
     */
    private String[] wrapWithLocalUser(String command) {
        String localUser = this.sshLocalUser;
        if (localUser != null && !localUser.isEmpty()) {
            String currentUser = System.getProperty("user.name", "");
            if (!localUser.equals(currentUser)) {
                return new String[]{"runuser", "-l", localUser, "-c", command};
            }
        }
        return new String[]{"bash", "-c", command};
    }

    private ProcessResult executeCommand(String[] cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread outReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {}
        });
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (IOException ignored) {}
        });

        outReader.start();
        errReader.start();

        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out after " + timeoutSec + " seconds");
        }

        outReader.join(5000);
        errReader.join(5000);

        return new ProcessResult(process.exitValue(), stdout.toString(), stderr.toString());
    }

    /**
     * SSH/SCP stderr에서 배너 등 불필요한 텍스트 제거, 핵심 에러만 추출
     */
    private String cleanSshError(String stderr) {
        if (stderr == null || stderr.trim().isEmpty()) return "";
        // 배너나 MOTD 라인 제거, 에러 관련 라인만 추출
        StringBuilder cleaned = new StringBuilder();
        for (String line : stderr.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // 배너/MOTD 패턴 건너뛰기
            if (t.startsWith("*") || t.startsWith("NOTICE") || t.startsWith("SSH Connect")
                || t.startsWith("****")) continue;
            cleaned.append(t).append(" ");
        }
        String result = cleaned.toString().trim();
        // 너무 길면 잘라내기
        if (result.length() > 300) result = result.substring(0, 300) + "...";
        return result;
    }

    private Map<String, Object> parseLsLine(String line) {
        String[] parts = line.split("\\s+", 9);
        if (parts.length < 9) return null;
        try {
            Map<String, Object> info = new HashMap<>();
            long size = Long.parseLong(parts[4]);
            String path = parts[8];
            String filename = new File(path).getName();
            info.put("filename", filename);
            info.put("path", path);
            info.put("size", size);
            info.put("formattedSize", formatBytes(size));
            info.put("date", parts[5] + " " + parts[6] + " " + parts[7]);
            return info;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static class ProcessResult {
        final int exitCode;
        final String stdout;
        final String stderr;
        ProcessResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}

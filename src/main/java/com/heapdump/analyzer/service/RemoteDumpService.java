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

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
        String trimmed = (user != null) ? user.trim() : "";
        boolean autoFilled = false;
        if (trimmed.isEmpty()) {
            // 빈 칸이면 현재 프로세스를 기동 중인 OS 계정으로 자동 채움
            String currentUser = System.getProperty("user.name", "");
            if (currentUser != null && !currentUser.trim().isEmpty()) {
                trimmed = currentUser.trim();
                autoFilled = true;
            }
        }
        this.sshLocalUser = trimmed;
        if (autoFilled) {
            logger.info("[RemoteDump] SSH local user empty → auto-filled with current process user '{}'", this.sshLocalUser);
        } else {
            logger.info("[RemoteDump] SSH local user changed to '{}'", this.sshLocalUser);
        }
    }

    public String getScpTempDir() { return scpTempDir; }

    public void setScpTempDir(String dir) {
        this.scpTempDir = (dir != null && !dir.trim().isEmpty()) ? dir.trim() : "/tmp";
        logger.info("[RemoteDump] SCP temp dir changed to '{}'", this.scpTempDir);
    }

    public Map<Long, String> getLastAutoScanErrors() {
        return Collections.unmodifiableMap(lastAutoScanErrors);
    }

    // ── 런타임 설정 영속화 연동 (LlmConfigService/RagConfigService 와 동일 패턴) ──
    // HeapDumpAnalyzerService.persistSettings()/syncApplicationProperties()/loadSettings() 가 위임 호출.

    /** settings.json 에 저장할 원격(SSH/SCP) 설정 수집. */
    public void collectSettings(Map<String, Object> settings) {
        settings.put("sshLocalUser", sshLocalUser);
        settings.put("scpTempDir", scpTempDir);
        settings.put("scanIntervalSec", scanIntervalSec);
    }

    /** application.properties 동기화용 키-값 수집(줄 단위 치환). */
    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("remote.ssh.local-user", sshLocalUser != null ? sshLocalUser : "");
        updates.put("remote.scp.temp-dir", scpTempDir != null ? scpTempDir : "/tmp");
        updates.put("remote.scan.interval-sec", String.valueOf(scanIntervalSec));
    }

    /** settings.json 복원 — 저장된 값으로 런타임 필드 덮어씀(application.properties 기본값 우선). */
    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("sshLocalUser")) {
            Object v = saved.get("sshLocalUser");
            if (v != null) {
                this.sshLocalUser = String.valueOf(v).trim();
                logger.info("[Settings] Restored sshLocalUser='{}'", this.sshLocalUser);
            }
        }
        if (saved.containsKey("scpTempDir")) {
            Object v = saved.get("scpTempDir");
            if (v != null && !String.valueOf(v).trim().isEmpty()) {
                this.scpTempDir = String.valueOf(v).trim();
                logger.info("[Settings] Restored scpTempDir='{}'", this.scpTempDir);
            }
        }
        if (saved.containsKey("scanIntervalSec")) {
            Object v = saved.get("scanIntervalSec");
            int sec = (v instanceof Number) ? ((Number) v).intValue() : -1;
            if (sec <= 0 && v != null) {
                try { sec = Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
            }
            if (sec > 0) {
                this.scanIntervalSec = sec;
                logger.info("[Settings] Restored scanIntervalSec={}", this.scanIntervalSec);
            }
        }
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
     * 원격 서버에서 힙 덤프 + 코어파일 목록 스캔 — 다중 경로(최대 5개) 모두 순회.
     * - 모든 경로가 같은 종류(NOT_FOUND / NOT_READABLE / SSH_ERROR)로 실패하면 그 errorCode 반환
     * - 일부만 실패하면 partial 성공: 성공한 경로의 파일은 반환, 실패 경로는 pathErrors에 기록
     * - 한 경로라도 성공하면 서버 상태는 OK
     */
    public Map<String, Object> scanRemoteDumpsWithStatus(TargetServer server) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> pathErrors = new ArrayList<>();

        List<String> heapPaths = server.isScanHeap() ? server.getDumpPaths() : Collections.emptyList();
        List<String> corePaths = server.isScanCore() ? server.getCoreDumpPaths() : Collections.emptyList();

        if (heapPaths.isEmpty() && corePaths.isEmpty()) {
            String errorMsg = server.isScanHeap() || server.isScanCore()
                    ? "탐지 경로가 설정되지 않았습니다."
                    : "힙덤프 또는 코어파일 탐지 경로가 설정되지 않았습니다.";
            result.put("errorCode", "NO_DUMP_PATH");
            result.put("error", errorMsg);
            result.put("files", files);
            result.put("count", 0);
            updateServerStatus(server, "FAIL", errorMsg);
            return result;
        }

        Set<String> seenPaths = new HashSet<>();
        int successCount = 0;
        String firstFatalError = null;
        String firstFatalCode = null;

        // 힙덤프 경로 스캔
        for (String dumpPath : heapPaths) {
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

        // 코어파일 경로 스캔
        for (String corePath : corePaths) {
            try {
                Map<String, Object> single = scanCorePath(server, corePath);
                if (single.containsKey("error")) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("dumpPath", corePath);
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
                err.put("dumpPath", corePath);
                err.put("errorCode", "SCAN_EXCEPTION");
                err.put("error", "코어파일 스캔 실패: " + e.getMessage());
                pathErrors.add(err);
                if (firstFatalError == null) {
                    firstFatalError = "코어파일 스캔 실패: " + e.getMessage();
                    firstFatalCode = "SCAN_EXCEPTION";
                }
                logger.error("[RemoteDump] Core scan failed for {} path={}: {}", server.getName(), corePath, e.getMessage());
            }
        }

        // 탐지 파일 — 수정시각(mtime) 기준 내림차순 정렬 (최신 파일 우선)
        files.sort((a, b) -> {
            double ma = a.get("mtime") instanceof Number ? ((Number) a.get("mtime")).doubleValue() : 0;
            double mb = b.get("mtime") instanceof Number ? ((Number) b.get("mtime")).doubleValue() : 0;
            return Double.compare(mb, ma);
        });

        result.put("files", files);
        result.put("count", files.size());
        if (!pathErrors.isEmpty()) result.put("pathErrors", pathErrors);

        if (successCount == 0) {
            result.put("errorCode", firstFatalCode != null ? firstFatalCode : "SCAN_EXCEPTION");
            result.put("error", firstFatalError != null ? firstFatalError : "모든 경로 스캔 실패");
            updateServerStatus(server, "FAIL", firstFatalError);
        } else {
            updateServerStatus(server, "OK", null);
        }
        logger.info("[RemoteDump] Scanned {} files ({} heap-paths, {} core-paths) on server {}",
                files.size(), heapPaths.size(), corePaths.size(), server.getName());
        return result;
    }

    /** 힙덤프 단일 경로 스캔 — error/errorCode/files 키 반환. 상태 DB 업데이트는 호출자에서 집계. */
    private Map<String, Object> scanSinglePath(TargetServer server, String dumpPath) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();

        // 경로 존재/권한 체크를 find 앞에 두어 exit 2/3으로 명확히 구분.
        // find 끝의 `|| true` : 하위 디렉토리 권한 부족 등으로 find 가 exit 1 로 끝나도
        //   매칭된 파일이 stdout 에 있으면 성공으로 처리. find stderr 는 그대로 SSH stderr 로
        //   올라와 디버깅 단서로 로그에 남김 (2>/dev/null 로 묻히지 않음).
        String safePath = dumpPath.replace("'", "'\\''");
        // -printf 로 epoch mtime(%T@) + 정렬가능 날짜 + 크기 + 경로를 '|' 구분 출력.
        //   기존 `-exec ls -la` 의 파일별 fork 제거 + 정렬 키(mtime) 확보. (String.format 미사용:
        //   printf 포맷의 % 가 format specifier 로 오인되는 것 방지 — 경로만 직접 치환.)
        String findCmd =
            "[ -d '" + safePath + "' ] || { echo HEAPDUMP_PATH_NOT_FOUND >&2; exit 2; }; "
            + "[ -r '" + safePath + "' ] || { echo HEAPDUMP_PATH_NOT_READABLE >&2; exit 3; }; "
            + "find '" + safePath + "' -maxdepth 2 -type f \\( -name '*.hprof' -o -name '*.hprof.gz' -o -name '*.bin' -o -name '*.dump' \\) "
            + "-printf '%T@|%TY-%Tm-%Td %TH:%TM|%s|%p\\n' || true";
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
        } else if (pr.exitCode != 0) {
            String errorMsg = cleanSshError(pr.stderr);
            if (errorMsg.isEmpty()) errorMsg = "원격 명령 실행 실패 (exit " + pr.exitCode + ")";
            result.put("errorCode", "SSH_ERROR");
            result.put("error", "SSH 오류: " + errorMsg);
            logger.warn("[RemoteDump] SSH error on {} path={}: exit={}, stderr={}",
                    server.getName(), dumpPath, pr.exitCode, errorMsg);
        } else {
            if (pr.stderr != null && !pr.stderr.trim().isEmpty()) {
                logger.info("[RemoteDump] find non-fatal stderr on {} path={}: {}",
                        server.getName(), dumpPath, summarizeStderr(pr.stderr, 5));
            }
            if (!pr.stdout.trim().isEmpty()) {
                for (String line : pr.stdout.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    Map<String, Object> fileInfo = parsePrintfLine(line);
                    if (fileInfo != null) {
                        String filename = (String) fileInfo.get("filename");
                        Long size = (Long) fileInfo.get("size");
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
                        fileInfo.put("fileType", "heap");
                        files.add(fileInfo);
                    }
                }
            }
        }
        result.put("files", files);
        return result;
    }

    /**
     * 코어파일 단일 경로 스캔 — Linux core 파일(core, core.*, *.core) 탐지.
     * 파일 권한(600/400)으로 read 불가한 경우에도 find+ls로 파일 존재는 확인.
     * scanExecutable=true면 각 파일에 대해 file 명령으로 실행파일 경로 추출 시도.
     */
    private Map<String, Object> scanCorePath(TargetServer server, String corePath) throws Exception {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();

        String safePath = corePath.replace("'", "'\\''");
        // 2>/dev/null || true : 하위 파일 권한 부족으로 Permission denied 발생해도 통과.
        // 코어파일은 root:root 600/400이 일반적이라 sshUser가 속성은 볼 수 있어도
        // 파일 내용을 읽을 수 없는 경우가 많음. find -printf 는 디렉토리 execute 권한만 있으면 동작.
        // -printf: epoch mtime(%T@) + 정렬가능 날짜 + 크기 + 경로 ('|' 구분). 파일별 ls fork 제거.
        String findCmd =
            "[ -d '" + safePath + "' ] || { echo HEAPDUMP_PATH_NOT_FOUND >&2; exit 2; }; "
            + "[ -r '" + safePath + "' ] || { echo HEAPDUMP_PATH_NOT_READABLE >&2; exit 3; }; "
            + "find '" + safePath + "' -maxdepth 3 -type f \\( -name 'core' -o -name 'core.[0-9]*' -o -name '*.core' \\) "
            + "-printf '%T@|%TY-%Tm-%Td %TH:%TM|%s|%p\\n' 2>/dev/null || true";
        String[] cmd = buildSshCommand(server, findCmd);
        ProcessResult pr = executeCommand(cmd, SSH_TIMEOUT_SEC);

        if (pr.exitCode == 2) {
            result.put("errorCode", "DUMP_PATH_NOT_FOUND");
            result.put("error", "코어파일 경로가 존재하지 않습니다: " + corePath);
            logger.warn("[RemoteDump] Core path not found on {}: {}", server.getName(), corePath);
        } else if (pr.exitCode == 3) {
            result.put("errorCode", "DUMP_PATH_NOT_READABLE");
            result.put("error", "코어파일 경로 읽기 권한이 없습니다: " + corePath);
            logger.warn("[RemoteDump] Core path not readable on {} (user={}): {}",
                    server.getName(), server.getSshUser(), corePath);
        } else if (pr.exitCode != 0) {
            String errorMsg = cleanSshError(pr.stderr);
            if (errorMsg.isEmpty()) errorMsg = "원격 명령 실행 실패 (exit " + pr.exitCode + ")";
            result.put("errorCode", "SSH_ERROR");
            result.put("error", "SSH 오류: " + errorMsg);
            logger.warn("[RemoteDump] SSH core scan error on {} path={}: exit={}, stderr={}",
                    server.getName(), corePath, pr.exitCode, errorMsg);
        } else {
            if (pr.stderr != null && !pr.stderr.trim().isEmpty()) {
                logger.info("[RemoteDump] core scan non-fatal stderr on {} path={}: {}",
                        server.getName(), corePath, summarizeStderr(pr.stderr, 5));
            }
            if (!pr.stdout.trim().isEmpty()) {
                // 1) 코어파일 메타 파싱 + 전송 여부 판정
                File localCoreDir = new File(config.getCoreDumpDirectory(), "dumpfiles");
                File localHeapDir = new File(config.getDumpFilesDirectory());
                for (String line : pr.stdout.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    Map<String, Object> fileInfo = parsePrintfLine(line);
                    if (fileInfo != null) {
                        Long size = (Long) fileInfo.get("size");
                        String filename = (String) fileInfo.get("filename");
                        List<DumpTransferLog> succLogs = transferLogRepository
                                .findByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(
                                        server.getId(), filename, size, "SUCCESS");
                        boolean transferred = false;
                        for (DumpTransferLog sl : succLogs) {
                            String local = sl.getFilename();
                            if (local == null) continue;
                            if (new File(localCoreDir, local).exists()
                                    || new File(localHeapDir, local).exists()
                                    || new File(localHeapDir, local + ".gz").exists()) {
                                transferred = true;
                                break;
                            }
                        }
                        fileInfo.put("transferred", transferred);
                        fileInfo.put("analyzed", false);
                        fileInfo.put("sourceDumpPath", corePath);
                        fileInfo.put("fileType", "core");
                        files.add(fileInfo);
                    }
                }

                // 2) 실행파일 탐지 — 파일별 SSH 대신 단일 배치 `file` 호출(성능 핵심).
                if (server.isScanExecutable() && !files.isEmpty()) {
                    List<String> corePaths = new ArrayList<>();
                    for (Map<String, Object> fi : files) {
                        String p = (String) fi.get("path");
                        if (p != null) corePaths.add(p);
                    }
                    Map<String, String[]> execMap = detectExecutablesBatch(server, corePaths);
                    for (Map<String, Object> fi : files) {
                        String[] info = execMap.get(fi.get("path"));
                        if (info == null) continue;
                        if (info[0] != null) fi.put("executablePath", info[0]);     // execfn (실행파일)
                        if (info[1] != null) fi.put("executableCommand", info[1]);  // from (실행명령, 참고용)
                        if (info[2] != null) fi.put("executableWarning", info[2]);
                    }
                }
            }
        }
        result.put("files", files);
        return result;
    }

    private static final int FILE_BATCH_SIZE = 150;       // 단일 `file` 호출당 코어파일 수 상한
    private static final int FILE_BATCH_TIMEOUT_SEC = 90;  // 배치 file 명령 타임아웃
    private static final java.util.regex.Pattern EXECFN_PAT =
            java.util.regex.Pattern.compile("execfn:\\s*'([^']+)'");
    private static final java.util.regex.Pattern FROM_PAT =
            java.util.regex.Pattern.compile("from\\s+'([^']+)'");

    /**
     * 여러 코어파일의 실행파일 정보를 단일 SSH `file` 호출(들)로 일괄 추출 — 파일별 SSH 왕복 제거(성능 핵심).
     * 반환: path → [executablePath(execfn), executableCommand(from), warning]. 각 요소 미상 시 null.
     * - executablePath 는 **execfn 만** 사용(실제 실행 바이너리 경로). from 은 실행명령(참고용)이라 path 로 쓰지 않음.
     */
    private Map<String, String[]> detectExecutablesBatch(TargetServer server, List<String> paths) {
        Map<String, String[]> out = new HashMap<>();
        if (paths == null || paths.isEmpty()) return out;
        for (int start = 0; start < paths.size(); start += FILE_BATCH_SIZE) {
            List<String> chunk = paths.subList(start, Math.min(start + FILE_BATCH_SIZE, paths.size()));
            try {
                StringBuilder sb = new StringBuilder("file");
                for (String p : chunk) sb.append(" '").append(p.replace("'", "'\\''")).append("'");
                sb.append(" 2>&1 || true");
                String[] cmd = buildSshCommand(server, sb.toString());
                ProcessResult pr = executeCommand(cmd, FILE_BATCH_TIMEOUT_SEC);
                for (String line : pr.stdout.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    // `file` 출력은 "경로: 설명" — 경로엔 ": " 가 없으므로 첫 ": " 로 분리
                    int idx = line.indexOf(": ");
                    if (idx < 0) continue;
                    String path = line.substring(0, idx);
                    String desc = line.substring(idx + 2);
                    out.put(path, parseFileDesc(desc));
                }
            } catch (Exception e) {
                logger.debug("[RemoteDump] detectExecutablesBatch chunk failed (start={}): {}", start, e.getMessage());
            }
        }
        return out;
    }

    /**
     * `file` 명령 설명부 파싱 → [execfn 실행파일 경로, from 실행명령, warning].
     * execfn 이 실제 실행 바이너리 경로(전송 대상), from 은 실행 시 명령행(참고용 표시).
     */
    private String[] parseFileDesc(String desc) {
        if (desc == null) desc = "";
        if (desc.contains("Permission denied") || desc.contains("cannot open")) {
            return new String[]{null, null, "파일 읽기 권한 부족 (chmod 또는 sudo 필요)"};
        }
        String execfn = null, fromCmd = null;
        java.util.regex.Matcher m1 = EXECFN_PAT.matcher(desc);
        if (m1.find()) execfn = m1.group(1);
        java.util.regex.Matcher m2 = FROM_PAT.matcher(desc);
        if (m2.find()) fromCmd = m2.group(1);

        if (execfn == null && fromCmd == null) {
            if (desc.contains("core file")) {
                return new String[]{null, null, "실행파일 정보(execfn)를 추출할 수 없습니다"};
            }
            return new String[]{null, null, "코어파일 형식이 아닙니다: "
                    + desc.substring(0, Math.min(80, desc.length()))};
        }
        // execfn 없이 from 만 있는 경우: 실행명령은 표시하되 전송 가능한 실행파일 경로는 없음
        String warn = (execfn == null) ? "실행파일 경로(execfn) 없음 — 실행명령만 확인됨" : null;
        return new String[]{execfn, fromCmd, warn};
    }

    /** stderr 첫 N 줄만 발췌해 로깅용 한 줄로 묶음. */
    private String summarizeStderr(String stderr, int maxLines) {
        if (stderr == null) return "";
        String[] lines = stderr.split("\n");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            if (shown > 0) sb.append(" | ");
            sb.append(t);
            shown++;
            if (shown >= maxLines) break;
        }
        int total = 0;
        for (String l : lines) if (!l.trim().isEmpty()) total++;
        if (total > shown) sb.append(" (외 ").append(total - shown).append("줄)");
        return sb.toString();
    }

    /** 하위 호환용 */
    public List<Map<String, Object>> scanRemoteDumps(TargetServer server) {
        Map<String, Object> result = scanRemoteDumpsWithStatus(server);
        return (List<Map<String, Object>>) result.getOrDefault("files", Collections.emptyList());
    }

    /**
     * 원격 코어 원본명으로 이미 전송된 로컬 코어파일명을 조회 (코어덤프 dumpfiles 디렉터리에 실존하는 SUCCESS 로그).
     * 실행파일을 "{coreLocal}.exec" 로 저장해 자동 페어링하기 위한 lookup. 없으면 null.
     */
    public String findTransferredCoreLocalName(TargetServer server, String coreRemoteName) {
        if (coreRemoteName == null) return null;
        File coreDir = new File(config.getCoreDumpDirectory(), "dumpfiles");
        List<DumpTransferLog> logs = transferLogRepository
                .findByServerIdAndRemoteFilenameAndTransferStatusOrderByCompletedAtDesc(
                        server.getId(), coreRemoteName, "SUCCESS");
        for (DumpTransferLog l : logs) {
            String local = l.getFilename();
            if (local != null && new File(coreDir, local).exists()) return local;
        }
        return null;
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
        return transferFile(server, remoteFilePath, "heap", null);
    }

    public DumpTransferLog transferFile(TargetServer server, String remoteFilePath,
                                        TransferProgressListener listener) {
        return transferFile(server, remoteFilePath, "heap", listener);
    }

    public DumpTransferLog transferFile(TargetServer server, String remoteFilePath, String fileType) {
        return transferFile(server, remoteFilePath, fileType, null);
    }

    public DumpTransferLog transferFile(TargetServer server, String remoteFilePath,
                                        String fileType,
                                        TransferProgressListener listener) {
        return transferFile(server, remoteFilePath, fileType, null, listener);
    }

    /**
     * 진행률 콜백 버전. listener가 null이 아닐 때만 SCP 진행 중 임시 파일 크기를 폴링하여 보고.
     * fileType: "core"/"coreexec" → 코어덤프 디렉터리, 나머지 → 힙덤프 디렉터리.
     * targetFilename: 지정 시 로컬 저장명으로 사용(예: 코어 실행파일을 "{coreLocal}.exec" 로 저장해 자동 페어링).
     *                 null 이면 원격 원본명 사용.
     */
    public DumpTransferLog transferFile(TargetServer server, String remoteFilePath,
                                        String fileType, String targetFilename,
                                        TransferProgressListener listener) {
        // 원격 원본명 — rename 후에도 변하지 않는 식별자 (scan transferred 판정 키)
        final String remoteFilename = new File(remoteFilePath).getName();
        String filename = (targetFilename != null && !targetFilename.isBlank())
                ? targetFilename : remoteFilename;
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
            // core(코어덤프) / coreexec(코어의 실행 바이너리) 모두 코어덤프 dumpfiles 디렉터리로 전송
            boolean toCoreDir = "core".equals(fileType) || "coreexec".equals(fileType);
            File localDir = toCoreDir
                    ? new File(config.getCoreDumpDirectory(), "dumpfiles")
                    : new File(config.getDumpFilesDirectory());
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
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {}
        });
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
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
     * 자동 탐지 — 10초마다 체크, 설정된 주기가 경과했으면 스캔 실행.
     * scanHeap=true면 힙덤프, scanCore=true면 코어파일도 자동 전송.
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

                if (scanResult.containsKey("error")) {
                    lastAutoScanErrors.put(server.getId(), (String) scanResult.get("error"));
                } else {
                    lastAutoScanErrors.remove(server.getId());
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> remoteDumps =
                        (List<Map<String, Object>>) scanResult.getOrDefault("files", Collections.emptyList());
                for (Map<String, Object> dump : remoteDumps) {
                    boolean transferred = (Boolean) dump.getOrDefault("transferred", false);
                    if (!transferred) {
                        String remotePath = (String) dump.get("path");
                        String fileType = (String) dump.getOrDefault("fileType", "heap");
                        logger.info("[AutoDetect] New {} file found on {}: {}", fileType, server.getName(), remotePath);
                        transferFile(server, remotePath, fileType);
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
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {}
        });
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
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

    /**
     * find -printf '%T@|%TY-%Tm-%Td %TH:%TM|%s|%p\n' 출력 1줄 파싱.
     * 반환 맵: filename/path/size/formattedSize/date(정렬가능 "YYYY-MM-DD HH:MM") + mtime(epoch double, 정렬 키).
     */
    private Map<String, Object> parsePrintfLine(String line) {
        String[] parts = line.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            double mtime = Double.parseDouble(parts[0].trim());
            String date = parts[1];
            long size = Long.parseLong(parts[2].trim());
            String path = parts[3];
            Map<String, Object> info = new HashMap<>();
            info.put("filename", new File(path).getName());
            info.put("path", path);
            info.put("size", size);
            info.put("formattedSize", formatBytes(size));
            info.put("date", date);
            info.put("mtime", mtime);
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

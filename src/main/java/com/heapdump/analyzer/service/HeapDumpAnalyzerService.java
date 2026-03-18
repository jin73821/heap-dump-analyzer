package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.parser.MatReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Heap Dump 분석 서비스 (MAT CLI + SSE + 디스크 영속화)
 *
 * 신규 기능:
 *   - setKeepUnreachableObjects(): 런타임 설정 변경
 *   - isKeepUnreachableObjects():  현재 설정 조회
 *   - getCachedResultCount():       캐시된 결과 수
 *   - getHeapDumpDirectory():       디렉토리 경로
 *   - getMatCliPath():              MAT CLI 경로
 */
@Service
public class HeapDumpAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpAnalyzerService.class);
    private static final int    MAT_TIMEOUT_MINUTES = 30;
    private static final String RESULT_JSON  = "result.json";
    private static final String MAT_LOG_FILE = "mat.log";

    private final HeapDumpConfig  config;
    private final MatReportParser parser;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    // 메모리 1차 캐시
    private final ConcurrentHashMap<String, HeapAnalysisResult> memCache = new ConcurrentHashMap<>();

    // 비동기 실행 스레드 풀
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 런타임 설정 (application.properties 초기값, API로 변경 가능)
    private volatile boolean keepUnreachableObjects;

    public HeapDumpAnalyzerService(HeapDumpConfig config, MatReportParser parser) {
        this.config  = config;
        this.parser  = parser;
        this.keepUnreachableObjects = config.isKeepUnreachableObjects();
    }

    // ── 시작 시 디스크 결과 복원 ──────────────────────────────────

    @PostConstruct
    public void restoreResultsFromDisk() {
        File baseDir = new File(config.getHeapDumpDirectory());
        if (!baseDir.exists()) return;
        File[] subDirs = baseDir.listFiles(File::isDirectory);
        if (subDirs == null) return;

        int loaded = 0;
        for (File dir : subDirs) {
            File resultFile = new File(dir, RESULT_JSON);
            if (!resultFile.exists()) continue;
            try {
                HeapAnalysisResult r = objectMapper.readValue(resultFile, HeapAnalysisResult.class);
                if (r == null || r.getFilename() == null) continue;
                if (r.getAnalysisStatus() != HeapAnalysisResult.AnalysisStatus.SUCCESS) continue;
                File logFile = new File(dir, MAT_LOG_FILE);
                if (logFile.exists()) {
                    r.setMatLog(new String(Files.readAllBytes(logFile.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
                }
                memCache.put(r.getFilename(), r);
                loaded++;
            } catch (Exception e) {
                logger.warn("Failed to restore {}: {}", resultFile, e.getMessage());
            }
        }
        logger.info("Restored {} cached results from disk", loaded);
    }

    // ── 설정 Getter/Setter ───────────────────────────────────────

    public boolean isKeepUnreachableObjects()      { return keepUnreachableObjects; }
    public void    setKeepUnreachableObjects(boolean v) {
        this.keepUnreachableObjects = v;
        logger.info("keep_unreachable_objects set to {}", v);
    }
    public String  getHeapDumpDirectory()          { return config.getHeapDumpDirectory(); }
    public String  getMatCliPath()                 { return config.getMatCliPath(); }
    public int     getCachedResultCount()          { return memCache.size(); }

    // ── 파일 관리 ────────────────────────────────────────────────

    public String uploadFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        String filename = Optional.ofNullable(file.getOriginalFilename())
                .map(n -> new File(n).getName()).filter(n -> !n.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("Invalid filename"));
        if (!isValidHeapDumpFile(filename))
            throw new IllegalArgumentException(
                    "Invalid file type. Only .hprof, .bin, .dump files are allowed");
        Path target = Paths.get(config.getHeapDumpDirectory(), filename);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Uploaded: {}", filename);
        return filename;
    }

    public List<HeapDumpFile> listFiles() {
        File dir = new File(config.getHeapDumpDirectory());
        File[] files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
        if (files == null) return Collections.emptyList();
        return Arrays.stream(files)
                .map(f -> new HeapDumpFile(f.getName(), f.getAbsolutePath(),
                        f.length(), f.lastModified()))
                .sorted(Comparator.comparingLong(HeapDumpFile::getLastModified).reversed())
                .collect(Collectors.toList());
    }

    public File getFile(String filename) throws IOException {
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);
        if (!file.exists() || !file.isFile())
            throw new FileNotFoundException("File not found: " + filename);
        return file;
    }

    public void deleteFile(String filename) throws IOException {
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);
        if (!file.exists()) throw new FileNotFoundException("File not found: " + filename);
        if (!file.delete()) throw new IOException("Failed to delete: " + filename);
        clearCache(filename);
    }

    // ── 캐시 조회 / 삭제 ─────────────────────────────────────────

    public HeapAnalysisResult getCachedResult(String filename) {
        String safe = new File(filename).getName();
        HeapAnalysisResult cached = memCache.get(safe);
        if (cached != null) return cached;

        File resultFile = resultJsonFile(safe);
        if (resultFile.exists()) {
            try {
                HeapAnalysisResult r = objectMapper.readValue(resultFile, HeapAnalysisResult.class);
                if (r != null && r.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS) {
                    File logFile = new File(resultDirectory(safe), MAT_LOG_FILE);
                    if (logFile.exists())
                        r.setMatLog(new String(Files.readAllBytes(logFile.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8));
                    memCache.put(safe, r);
                    return r;
                }
            } catch (Exception e) {
                logger.warn("Failed to read disk cache {}: {}", safe, e.getMessage());
            }
        }
        return null;
    }

    public void clearCache(String filename) {
        String safe = new File(filename).getName();
        memCache.remove(safe);
        File resultFile = resultJsonFile(safe);
        if (resultFile.exists()) {
            resultFile.delete();
            logger.info("Disk cache deleted: {}", resultFile.getAbsolutePath());
        }
        logger.info("Cache cleared: {}", safe);
    }

    // ── SSE 기반 비동기 분석 ─────────────────────────────────────

    public void analyzeWithProgress(String filename, SseEmitter emitter) {
        final String safe = new File(filename).getName();
        executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            try {
                sendProgress(emitter, AnalysisProgress.step(safe, 5, "힙 덤프 파일 확인 중..."));
                File dumpFile = new File(config.getHeapDumpDirectory(), safe);
                if (!dumpFile.exists()) {
                    sendProgress(emitter, AnalysisProgress.error(safe, "파일을 찾을 수 없습니다: " + safe));
                    emitter.complete(); return;
                }

                File resultDir = resultDirectory(safe);
                Files.createDirectories(resultDir.toPath());

                sendProgress(emitter, AnalysisProgress.step(safe, 10,
                        "MAT CLI 초기화 중... (" + formatSize(dumpFile.length()) + ")"));

                String matLog = runMatCliWithProgress(dumpFile.getAbsolutePath(), safe, resultDir, emitter);

                sendProgress(emitter, AnalysisProgress.parsing(safe, 85, "분석 리포트 파싱 중..."));
                Thread.sleep(300);

                String base = stripExtension(safe);
                moveZipsToResultDir(base, resultDir);

                sendProgress(emitter, AnalysisProgress.parsing(safe, 88, "Overview 파싱 중..."));
                MatParseResult parsed = parser.parse(resultDir.getAbsolutePath(), base);
                if (!parsed.hasData()) {
                    logger.warn("ZIP not in resultDir, fallback to heapDumpDir");
                    parsed = parser.parse(config.getHeapDumpDirectory(), base);
                }

                sendProgress(emitter, AnalysisProgress.parsing(safe, 93, "Top Components 분석 중..."));
                Thread.sleep(200);
                sendProgress(emitter, AnalysisProgress.parsing(safe, 96, "Leak Suspects 분석 중..."));
                Thread.sleep(200);
                sendProgress(emitter, AnalysisProgress.parsing(safe, 99, "결과 조립 중..."));

                HeapAnalysisResult result = buildResult(safe, dumpFile, parsed, matLog);
                result.setAnalysisTime(System.currentTimeMillis() - startTime);
                result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);

                memCache.put(safe, result);
                saveResultToDisk(result, resultDir);

                sendProgress(emitter, AnalysisProgress.completed(safe, "/analyze/result/" + safe));
                logger.info("Analysis done: {} in {}ms", safe, result.getAnalysisTime());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendProgress(emitter, AnalysisProgress.error(safe, "분석이 중단되었습니다."));
            } catch (Exception e) {
                logger.error("Analysis failed for {}", safe, e);
                sendProgress(emitter, AnalysisProgress.error(safe, e.getMessage()));
            } finally {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });
    }

    // ── MAT CLI 실행 ─────────────────────────────────────────────

    private String runMatCliWithProgress(String dumpPath, String filename,
                                          File resultDir, SseEmitter emitter)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>();
        cmd.add("sh");
        cmd.add(config.getMatCliPath());
        cmd.add(dumpPath);

        if (keepUnreachableObjects) {
            cmd.add("-keep_unreachable_objects");
            logger.info("MAT option: -keep_unreachable_objects ENABLED");
        }

        cmd.add("org.eclipse.mat.api:suspects");
        cmd.add("org.eclipse.mat.api:overview");
        cmd.add("org.eclipse.mat.api:top_components");

        logger.info("Running MAT CLI: {}", String.join(" ", cmd));
        sendProgress(emitter, AnalysisProgress.step(filename, 15, "MAT CLI 실행 중..."));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MALLOC_ARENA_MAX", "2");
        pb.directory(resultDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        final int[] pct = {15};
        StringBuilder output = new StringBuilder();

        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    if (pct[0] < 80) pct[0] = Math.min(80, pct[0] + 1);
                    sendProgress(emitter, AnalysisProgress.log(filename, pct[0], line));
                }
            } catch (IOException e) {
                logger.warn("MAT output read error: {}", e.getMessage());
            }
        }, executor);

        boolean finished = process.waitFor(MAT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        reader.join();

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("MAT CLI timed out after " + MAT_TIMEOUT_MINUTES + " min");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) logger.warn("MAT CLI exit={}", exitCode);

        sendProgress(emitter, AnalysisProgress.step(filename, 82,
                "MAT CLI 완료 (exit=" + exitCode + ")"));
        return output.toString();
    }

    // ── 디스크 저장 ──────────────────────────────────────────────

    private void saveResultToDisk(HeapAnalysisResult result, File dir) {
        try {
            if (result.getMatLog() != null && !result.getMatLog().isEmpty()) {
                Files.write(Paths.get(dir.getAbsolutePath(), MAT_LOG_FILE),
                        result.getMatLog().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            HeapAnalysisResult slim = cloneWithoutLog(result);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(dir, RESULT_JSON), slim);
            logger.info("Result saved: {}", dir.getAbsolutePath());
        } catch (Exception e) {
            logger.warn("Failed to save result: {}", e.getMessage());
        }
    }

    private HeapAnalysisResult cloneWithoutLog(HeapAnalysisResult r) {
        HeapAnalysisResult c = new HeapAnalysisResult();
        c.setFilename(r.getFilename());           c.setFileSize(r.getFileSize());
        c.setLastModified(r.getLastModified());   c.setFormat(r.getFormat());
        c.setTotalHeapSize(r.getTotalHeapSize()); c.setUsedHeapSize(r.getUsedHeapSize());
        c.setFreeHeapSize(r.getFreeHeapSize());   c.setHeapUsagePercent(r.getHeapUsagePercent());
        c.setTopMemoryObjects(r.getTopMemoryObjects());
        c.setLeakSuspects(r.getLeakSuspects());
        c.setTotalClasses(r.getTotalClasses());   c.setTotalObjects(r.getTotalObjects());
        c.setAnalysisTime(r.getAnalysisTime());   c.setAnalysisStatus(r.getAnalysisStatus());
        c.setOverviewHtml(r.getOverviewHtml());   c.setTopComponentsHtml(r.getTopComponentsHtml());
        c.setSuspectsHtml(r.getSuspectsHtml());   c.setMatLog(null);
        return c;
    }

    // ── 결과 조립 ────────────────────────────────────────────────

    private HeapAnalysisResult buildResult(String filename, File dumpFile,
                                            MatParseResult parsed, String matLog) {
        HeapAnalysisResult r = new HeapAnalysisResult();
        r.setFilename(filename);        r.setFileSize(dumpFile.length());
        r.setLastModified(dumpFile.lastModified());
        r.setFormat(getExtension(filename).toUpperCase());
        r.setMatLog(matLog);
        r.setTotalHeapSize(parsed.getTotalHeapSize());
        r.setUsedHeapSize(parsed.getUsedHeapSize());
        r.setFreeHeapSize(parsed.getFreeHeapSize());
        if (parsed.getTotalHeapSize() > 0)
            r.setHeapUsagePercent(parsed.getUsedHeapSize() * 100.0 / parsed.getTotalHeapSize());
        r.setTopMemoryObjects(parsed.getTopMemoryObjects());
        r.setLeakSuspects(parsed.getLeakSuspects());
        r.setTotalClasses(parsed.getTotalClasses());
        r.setTotalObjects(parsed.getTotalObjects());
        r.setOverviewHtml(parsed.getOverviewHtml());
        r.setTopComponentsHtml(parsed.getTopComponentsHtml());
        r.setSuspectsHtml(parsed.getSuspectsHtml());
        return r;
    }

    // ── 경로 / 유틸리티 ──────────────────────────────────────────

    private File resultDirectory(String filename) {
        return new File(config.getHeapDumpDirectory(), stripExtension(filename));
    }
    private File resultJsonFile(String filename) {
        return new File(resultDirectory(filename), RESULT_JSON);
    }

    private void sendProgress(SseEmitter emitter, AnalysisProgress progress) {
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(objectMapper.writeValueAsString(progress)));
        } catch (Exception e) {
            logger.debug("SSE send failed: {}", e.getMessage());
        }
    }

    private boolean isValidHeapDumpFile(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.endsWith(".hprof") || l.endsWith(".bin") || l.endsWith(".dump");
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    private void moveZipsToResultDir(String base, File resultDir) {
        File heapDir = new File(config.getHeapDumpDirectory());
        File[] zips = heapDir.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".zip") && lower.contains(base.toLowerCase());
        });
        if (zips == null || zips.length == 0) {
            logger.warn("[ZIP Move] No ZIPs found for base='{}'", base);
            return;
        }
        for (File zip : zips) {
            File dest = new File(resultDir, zip.getName());
            try {
                Files.move(zip.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("[ZIP Move] Moved: {} → {}", zip.getName(), resultDir.getName());
            } catch (IOException e) {
                try {
                    Files.copy(zip.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    logger.error("[ZIP Move] Failed: {}", ex.getMessage());
                }
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1048576)         return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824L)     return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

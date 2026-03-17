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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Heap Dump 분석 서비스 (MAT CLI + SSE 진행상황 실시간 전송)
 */
@Service
public class HeapDumpAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpAnalyzerService.class);
    private static final int MAT_TIMEOUT_MINUTES = 30;

    private final HeapDumpConfig config;
    private final MatReportParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // filename → 분석 결과 캐시 (완료 후 결과 조회용)
    private final ConcurrentHashMap<String, HeapAnalysisResult> resultCache = new ConcurrentHashMap<>();

    // 비동기 MAT 실행용 스레드 풀
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public HeapDumpAnalyzerService(HeapDumpConfig config, MatReportParser parser) {
        this.config = config;
        this.parser  = parser;
    }

    // ═══════════════════════════════════════════════════════════
    //  파일 관리
    // ═══════════════════════════════════════════════════════════

    public String uploadFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String filename = Optional.ofNullable(file.getOriginalFilename())
                .map(n -> new File(n).getName())
                .filter(n -> !n.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("Invalid filename"));

        if (!isValidHeapDumpFile(filename))
            throw new IllegalArgumentException("Invalid file format. Only .hprof, .bin, .dump files are allowed");

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
                .map(f -> new HeapDumpFile(f.getName(), f.getAbsolutePath(), f.length(), f.lastModified()))
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
        if (!file.delete()) throw new IOException("Failed to delete file: " + filename);
        resultCache.remove(filename);
    }

    // ═══════════════════════════════════════════════════════════
    //  결과 캐시 조회 (analyze 페이지에서 사용)
    // ═══════════════════════════════════════════════════════════

    public HeapAnalysisResult getCachedResult(String filename) {
        return resultCache.get(new File(filename).getName());
    }

    // ═══════════════════════════════════════════════════════════
    //  SSE 기반 비동기 분석 (핵심)
    // ═══════════════════════════════════════════════════════════

    /**
     * MAT CLI를 비동기로 실행하고 SSE로 진행 상황을 실시간 전송합니다.
     *
     * @param filename 덤프 파일명
     * @param emitter  SSE 이미터
     */
    public void analyzeWithProgress(String filename, SseEmitter emitter) {
        final String safeFilename = new File(filename).getName();

        executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            try {
                // ── STEP 1: 파일 확인 ────────────────────────────
                sendProgress(emitter, AnalysisProgress.step(safeFilename, 5, "힙 덤프 파일 확인 중..."));

                File dumpFile = new File(config.getHeapDumpDirectory(), safeFilename);
                if (!dumpFile.exists()) {
                    sendProgress(emitter, AnalysisProgress.error(safeFilename, "파일을 찾을 수 없습니다: " + safeFilename));
                    emitter.complete();
                    return;
                }

                // ── STEP 2: MAT CLI 실행 ─────────────────────────
                sendProgress(emitter, AnalysisProgress.step(safeFilename, 10,
                        "MAT CLI 초기화 중... (" + formatSize(dumpFile.length()) + ")"));

                String matLog = runMatCliWithProgress(dumpFile.getAbsolutePath(), safeFilename, emitter);

                // ── STEP 3: ZIP 파싱 ─────────────────────────────
                sendProgress(emitter, AnalysisProgress.parsing(safeFilename, 85, "분석 리포트 파싱 중..."));
                Thread.sleep(300);

                sendProgress(emitter, AnalysisProgress.parsing(safeFilename, 88, "Overview 리포트 파싱 중..."));
                String dumpBaseName = stripExtension(safeFilename);
                MatParseResult parsed = parser.parse(config.getHeapDumpDirectory(), dumpBaseName);

                sendProgress(emitter, AnalysisProgress.parsing(safeFilename, 93, "Top Components 분석 중..."));
                Thread.sleep(200);

                sendProgress(emitter, AnalysisProgress.parsing(safeFilename, 96, "Leak Suspects 분석 중..."));
                Thread.sleep(200);

                // ── STEP 4: 결과 조립 ────────────────────────────
                sendProgress(emitter, AnalysisProgress.parsing(safeFilename, 99, "결과 데이터 조립 중..."));

                HeapAnalysisResult result = buildResult(safeFilename, dumpFile, parsed, matLog);
                result.setAnalysisTime(System.currentTimeMillis() - startTime);
                result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);

                // 캐시에 저장
                resultCache.put(safeFilename, result);

                // ── STEP 5: 완료 ─────────────────────────────────
                String resultUrl = "/analyze/result/" + safeFilename;
                sendProgress(emitter, AnalysisProgress.completed(safeFilename, resultUrl));
                logger.info("Analysis done: {} in {}ms", safeFilename, result.getAnalysisTime());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendProgress(emitter, AnalysisProgress.error(safeFilename, "분석이 중단되었습니다."));
            } catch (Exception e) {
                logger.error("Analysis failed for {}", safeFilename, e);
                sendProgress(emitter, AnalysisProgress.error(safeFilename, e.getMessage()));
            } finally {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });
    }

    // ─── MAT CLI 실행 + 실시간 로그 전송 ─────────────────────

    private String runMatCliWithProgress(String dumpPath, String filename, SseEmitter emitter)
            throws IOException, InterruptedException {

        String matScript = config.getMatCliPath();
        List<String> cmd = List.of(
                "sh", matScript, dumpPath,
                "org.eclipse.mat.api:suspects",
                "org.eclipse.mat.api:overview",
                "org.eclipse.mat.api:top_components"
        );

        logger.info("Running MAT CLI: {}", String.join(" ", cmd));
        sendProgress(emitter, AnalysisProgress.step(filename, 15, "MAT CLI 실행 중..."));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MALLOC_ARENA_MAX", "2");
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // 진행률 단계: 15 → 80 사이를 로그 라인 수로 나눠 채움
        final int[] progressHolder = {15};
        StringBuilder output = new StringBuilder();

        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    logger.debug("[MAT] {}", line);

                    // 진행률 점진 증가 (최대 80까지)
                    if (progressHolder[0] < 80) {
                        progressHolder[0] = Math.min(80, progressHolder[0] + 1);
                    }

                    // 로그 라인을 SSE로 전송
                    sendProgress(emitter, AnalysisProgress.log(filename, progressHolder[0], line));
                }
            } catch (IOException e) {
                logger.warn("MAT output read error: {}", e.getMessage());
            }
        }, executor);

        boolean finished = process.waitFor(MAT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        reader.join();

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("MAT CLI timed out after " + MAT_TIMEOUT_MINUTES + " minutes");
        }

        int exitCode = process.exitValue();
        String log = output.toString();

        if (exitCode != 0) {
            logger.warn("MAT CLI exited with code {}: {}", exitCode, log);
        }

        sendProgress(emitter, AnalysisProgress.step(filename, 82, "MAT CLI 완료 (exit=" + exitCode + ")"));
        logger.info("MAT CLI finished (exit={}), output {} chars", exitCode, log.length());
        return log;
    }

    // ─── SSE 이벤트 전송 헬퍼 ────────────────────────────────

    private void sendProgress(SseEmitter emitter, AnalysisProgress progress) {
        try {
            String json = objectMapper.writeValueAsString(progress);
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(json));
        } catch (Exception e) {
            logger.debug("SSE send failed (client disconnected?): {}", e.getMessage());
        }
    }

    // ─── 결과 조립 ────────────────────────────────────────────

    private HeapAnalysisResult buildResult(String filename, File dumpFile,
                                           MatParseResult parsed, String matLog) {
        HeapAnalysisResult r = new HeapAnalysisResult();
        r.setFilename(filename);
        r.setFileSize(dumpFile.length());
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

    // ─── 유틸리티 ─────────────────────────────────────────────

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

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

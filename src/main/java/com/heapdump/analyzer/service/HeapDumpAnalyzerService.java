package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.parser.MatReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Heap Dump 분석 서비스 (MAT CLI 연동)
 *
 * <p>핵심 흐름:
 * <ol>
 *   <li>파일 업로드 / 목록 / 다운로드 / 삭제</li>
 *   <li>MAT CLI(ParseHeapDump.sh) 실행</li>
 *   <li>생성된 ZIP 리포트를 {@link MatReportParser}로 파싱</li>
 *   <li>{@link HeapAnalysisResult} 조립 후 반환</li>
 * </ol>
 * </p>
 */
@Service
public class HeapDumpAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpAnalyzerService.class);

    /** MAT CLI 실행 타임아웃 (분) — application.properties로 재정의 가능 */
    private static final int MAT_TIMEOUT_MINUTES = 30;

    private final HeapDumpConfig config;
    private final MatReportParser parser;

    public HeapDumpAnalyzerService(HeapDumpConfig config, MatReportParser parser) {
        this.config = config;
        this.parser  = parser;
    }

    // ═══════════════════════════════════════════════════════════
    //  파일 관리
    // ═══════════════════════════════════════════════════════════

    /** 파일 업로드 */
    public String uploadFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");

        String filename = Optional.ofNullable(file.getOriginalFilename())
                .map(n -> new File(n).getName())
                .filter(n -> !n.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("Invalid filename"));

        if (!isValidHeapDumpFile(filename)) {
            throw new IllegalArgumentException(
                    "Invalid file format. Only .hprof, .bin, .dump files are allowed");
        }

        Path target = Paths.get(config.getHeapDumpDirectory(), filename);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Uploaded: {}", filename);
        return filename;
    }

    /** 파일 목록 조회 */
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

    /** 파일 경로 반환 (다운로드용) */
    public File getFile(String filename) throws IOException {
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);
        if (!file.exists() || !file.isFile())
            throw new FileNotFoundException("File not found: " + filename);
        return file;
    }

    /** 파일 삭제 */
    public void deleteFile(String filename) throws IOException {
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);
        if (!file.exists()) throw new FileNotFoundException("File not found: " + filename);
        if (!file.delete()) throw new IOException("Failed to delete file: " + filename);
    }

    // ═══════════════════════════════════════════════════════════
    //  MAT CLI 분석 (핵심)
    // ═══════════════════════════════════════════════════════════

    /**
     * 힙 덤프 파일을 MAT CLI로 분석합니다.
     *
     * @param filename 덤프 파일명 (경로 없이 파일명만)
     * @return 분석 결과
     */
    public HeapAnalysisResult analyzeHeapDump(String filename) {
        long startTime = System.currentTimeMillis();
        filename = new File(filename).getName();   // 경로 탐색 방지

        File dumpFile = new File(config.getHeapDumpDirectory(), filename);
        if (!dumpFile.exists() || !dumpFile.isFile()) {
            return errorResult(filename, "File not found: " + filename, startTime);
        }

        try {
            // 1) MAT CLI 실행
            String matLog = runMatCli(dumpFile.getAbsolutePath());

            // 2) 생성된 ZIP 리포트 파싱
            String dumpBaseName = stripExtension(filename);
            MatParseResult parsed = parser.parse(config.getHeapDumpDirectory(), dumpBaseName);

            // 3) 결과 조립
            HeapAnalysisResult result = buildResult(filename, dumpFile, parsed, matLog);
            result.setAnalysisTime(System.currentTimeMillis() - startTime);
            result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);

            logger.info("Analysis done: {} in {}ms", filename, result.getAnalysisTime());
            return result;

        } catch (Exception e) {
            logger.error("Analysis failed for {}", filename, e);
            return errorResult(filename, e.getMessage(), startTime);
        }
    }

    // ─── MAT CLI 실행 ─────────────────────────────────────────

    /**
     * sh /opt/mat/ParseHeapDump.sh {dumpPath} \
     *     org.eclipse.mat.api:suspects \
     *     org.eclipse.mat.api:overview \
     *     org.eclipse.mat.api:top_components
     */
    private String runMatCli(String dumpPath) throws IOException, InterruptedException {
        String matScript = config.getMatCliPath();   // /opt/mat/ParseHeapDump.sh

        List<String> cmd = List.of(
                "sh", matScript, dumpPath,
                "org.eclipse.mat.api:suspects",
                "org.eclipse.mat.api:overview",
                "org.eclipse.mat.api:top_components"
        );

        logger.info("Running MAT CLI: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MALLOC_ARENA_MAX", "2");   // MAT 메모리 최적화
        pb.redirectErrorStream(true);                     // stderr → stdout 합침

        Process process = pb.start();

        // 비동기로 출력 수집 (버퍼 꽉 참 방지)
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    logger.debug("[MAT] {}", line);
                }
            } catch (IOException e) {
                logger.warn("MAT output read error: {}", e.getMessage());
            }
        });

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
            // exit code != 0 이어도 ZIP이 생성되면 계속 진행
        }

        logger.info("MAT CLI finished (exit={}), output {} chars", exitCode, log.length());
        return log;
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

        // 힙 통계
        r.setTotalHeapSize(parsed.getTotalHeapSize());
        r.setUsedHeapSize(parsed.getUsedHeapSize());
        r.setFreeHeapSize(parsed.getFreeHeapSize());
        if (parsed.getTotalHeapSize() > 0) {
            r.setHeapUsagePercent(
                    parsed.getUsedHeapSize() * 100.0 / parsed.getTotalHeapSize());
        }

        // Top Objects
        r.setTopMemoryObjects(parsed.getTopMemoryObjects());

        // Leak Suspects
        r.setLeakSuspects(parsed.getLeakSuspects());

        // 통계
        r.setTotalClasses(parsed.getTotalClasses());
        r.setTotalObjects(parsed.getTotalObjects());

        // 원본 HTML
        r.setOverviewHtml(parsed.getOverviewHtml());
        r.setTopComponentsHtml(parsed.getTopComponentsHtml());
        r.setSuspectsHtml(parsed.getSuspectsHtml());

        return r;
    }

    // ─── 에러 결과 ────────────────────────────────────────────

    private HeapAnalysisResult errorResult(String filename, String msg, long startTime) {
        HeapAnalysisResult r = new HeapAnalysisResult();
        r.setFilename(filename);
        r.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.ERROR);
        r.setErrorMessage(msg);
        r.setAnalysisTime(System.currentTimeMillis() - startTime);
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
}

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
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
    private static final String TMP_DIR_NAME = "tmp";

    private final HeapDumpConfig  config;
    private final MatReportParser parser;
    private final ObjectMapper    objectMapper = new ObjectMapper();

    // 메모리 1차 캐시
    private final ConcurrentHashMap<String, HeapAnalysisResult> memCache = new ConcurrentHashMap<>();

    // 비동기 실행 스레드 풀
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 분석 동시 실행 제한: 한 번에 1개만 실행, 나머지는 큐 대기
    private final java.util.concurrent.Semaphore analysisSemaphore = new java.util.concurrent.Semaphore(1);
    private final java.util.concurrent.atomic.AtomicInteger queueSize = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile String currentAnalysisFilename = null;

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
        File dataDir = new File(config.getDataDirectory());

        // 기존 결과 디렉토리를 data/로 마이그레이션
        migrateOldResultDirs(baseDir, dataDir);

        // data 디렉토리에서 결과 복원
        int loaded = 0;
        if (dataDir.exists()) {
            File[] subDirs = dataDir.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (File dir : subDirs) {
                    if (loadResultFromDir(dir)) loaded++;
                }
            }
        }
        logger.info("Restored {} cached results from disk (data directory)", loaded);

        // 상위 디렉토리에 남은 .index/.threads 파일을 결과 디렉토리로 이동
        migrateStrayArtifacts(baseDir);

        // 원본과 .gz가 동시에 존재하는 중복 파일 정리
        File[] dumpFiles = baseDir.listFiles((d, n) -> isValidHeapDumpFile(n));
        if (dumpFiles != null && dumpFiles.length > 0) {
            cleanupDuplicateGzFiles(dumpFiles);
        }

        // 이전 실행에서 남은 tmp 파일 정리
        cleanupTmpDir();
    }

    /**
     * 개별 결과 디렉토리에서 result.json을 로드하여 memCache에 적재.
     */
    private boolean loadResultFromDir(File dir) {
        File resultFile = new File(dir, RESULT_JSON);
        if (!resultFile.exists()) return false;
        try {
            HeapAnalysisResult r = objectMapper.readValue(resultFile, HeapAnalysisResult.class);
            if (r == null || r.getFilename() == null) return false;
            if (r.getAnalysisStatus() != HeapAnalysisResult.AnalysisStatus.SUCCESS
                    && r.getAnalysisStatus() != HeapAnalysisResult.AnalysisStatus.ERROR) return false;
            File logFile = new File(dir, MAT_LOG_FILE);
            if (logFile.exists()) {
                r.setMatLog(new String(Files.readAllBytes(logFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            // Heap 데이터 없는 SUCCESS → ERROR로 보정
            if (r.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS
                    && r.getTotalHeapSize() <= 0 && r.getUsedHeapSize() <= 0) {
                r.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.ERROR);
                if (r.getErrorMessage() == null || r.getErrorMessage().isEmpty()) {
                    r.setErrorMessage("Heap data not available — MAT ZIP 파싱 결과에 힙 데이터가 없습니다.");
                }
                logger.info("Corrected status to ERROR for {} (no heap data)", r.getFilename());
                try {
                    objectMapper.writerWithDefaultPrettyPrinter()
                            .writeValue(resultFile, r);
                } catch (Exception ex) {
                    logger.warn("Failed to update result.json for {}", r.getFilename());
                }
            }
            // 기존 캐시의 MAT HTML 재정제 (body 추출 등)
            if (r.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS) {
                sanitizeCachedHtml(r);
            }
            memCache.put(r.getFilename(), r);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to restore {}: {}", resultFile, e.getMessage());
            return false;
        }
    }

    /**
     * 기존 결과 디렉토리(baseDir/{basename}/)를 data 디렉토리로 마이그레이션.
     */
    private void migrateOldResultDirs(File baseDir, File dataDir) {
        File[] subDirs = baseDir.listFiles(File::isDirectory);
        if (subDirs == null) return;
        int migrated = 0;
        for (File dir : subDirs) {
            String name = dir.getName();
            // tmp, data 디렉토리는 스킵
            if (name.equals(TMP_DIR_NAME) || name.equals("data")) continue;
            File resultFile = new File(dir, RESULT_JSON);
            if (!resultFile.exists()) continue;

            File target = new File(dataDir, name);
            if (target.exists()) {
                logger.debug("[Migrate] Target already exists, skipping: {}", target);
                continue;
            }
            try {
                Files.move(dir.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                migrated++;
                logger.info("[Migrate] Moved result directory: {} → {}", dir.getAbsolutePath(), target.getAbsolutePath());
            } catch (IOException e) {
                // 다른 파일시스템이면 atomic move 실패 → copy + delete
                try {
                    copyDirectoryRecursively(dir.toPath(), target.toPath());
                    deleteDirectoryRecursively(dir);
                    migrated++;
                    logger.info("[Migrate] Copied result directory: {} → {}", dir.getAbsolutePath(), target.getAbsolutePath());
                } catch (Exception ex) {
                    logger.error("[Migrate] Failed to migrate {}: {}", name, ex.getMessage());
                }
            }
        }
        if (migrated > 0) {
            logger.info("[Migrate] Moved {} result directories to data/", migrated);
        }
    }

    private void copyDirectoryRecursively(Path source, Path target) throws IOException {
        Files.walk(source).forEach(s -> {
            try {
                Path t = target.resolve(source.relativize(s));
                if (Files.isDirectory(s)) {
                    if (!Files.exists(t)) Files.createDirectories(t);
                } else {
                    Files.copy(s, t, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * 상위 디렉토리에 남아있는 .index/.threads 파일을 결과 디렉토리로 이동 (마이그레이션).
     */
    private void migrateStrayArtifacts(File baseDir) {
        File[] stray = baseDir.listFiles((d, n) ->
                n.endsWith(".index") || n.endsWith(".threads"));
        if (stray == null || stray.length == 0) return;
        int moved = 0;
        for (File f : stray) {
            // 파일명에서 base 추출: "tomcat_heapdump.a2s.index" → "tomcat_heapdump"
            String name = f.getName();
            int firstDot = name.indexOf('.');
            if (firstDot <= 0) continue;
            String base = name.substring(0, firstDot);
            File resultDir = new File(config.getDataDirectory(), base);
            if (!resultDir.exists() || !resultDir.isDirectory()) continue;
            try {
                Files.move(f.toPath(), new File(resultDir, name).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                moved++;
            } catch (IOException e) {
                logger.warn("[Migrate] Failed to move {}: {}", name, e.getMessage());
            }
        }
        if (moved > 0) {
            logger.info("[Migrate] Moved {} stray index/threads files to result directories", moved);
        }
    }

    private File tmpDirectory() {
        return new File(config.getHeapDumpDirectory(), TMP_DIR_NAME);
    }

    /**
     * 기존 캐시에 저장된 MAT HTML에서 &lt;body&gt; 내부만 추출하여 재정제.
     * 이전 버전에서 전체 HTML 문서가 저장된 경우를 처리.
     */
    private void sanitizeCachedHtml(HeapAnalysisResult r) {
        r.setOverviewHtml(extractBodyContent(r.getOverviewHtml()));
        r.setTopComponentsHtml(extractBodyContent(r.getTopComponentsHtml()));
        r.setSuspectsHtml(extractBodyContent(r.getSuspectsHtml()));
        if (r.getComponentDetailHtmlMap() != null && !r.getComponentDetailHtmlMap().isEmpty()) {
            r.getComponentDetailHtmlMap().replaceAll((k, v) -> extractBodyContent(v));
        }
        r.setHistogramHtml(extractBodyContent(r.getHistogramHtml()));
        r.setThreadOverviewHtml(extractBodyContent(r.getThreadOverviewHtml()));

        // componentDetailHtmlMap이 비어있으면 ZIP에서 재파싱 시도
        if (r.getComponentDetailHtmlMap() == null || r.getComponentDetailHtmlMap().isEmpty()) {
            reparsComponentDetails(r);
        }

        // histogramHtml이 없으면 ZIP에서 재추출 시도
        if (r.getHistogramHtml() == null || r.getHistogramHtml().isEmpty()) {
            reparseActions(r);
        }

        // .threads 파일 로드
        loadThreadStacksText(r);
    }

    /**
     * 기존 캐시에 componentDetailHtmlMap이 없을 때 ZIP에서 재추출.
     */
    private void reparsComponentDetails(HeapAnalysisResult r) {
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            tmp.setTotalHeapSize(r.getTotalHeapSize());
            tmp.setTopMemoryObjects(r.getTopMemoryObjects());
            // parser.parse는 전체 파싱이므로 ZIP만 직접 처리
            parser.reparseComponentDetails(resultDir.getAbsolutePath(), baseName, tmp);
            if (!tmp.getComponentDetailHtmlMap().isEmpty()) {
                r.setComponentDetailHtmlMap(tmp.getComponentDetailHtmlMap());
                logger.info("Re-extracted {} component detail pages for {}",
                        tmp.getComponentDetailHtmlMap().size(), r.getFilename());
            }
        } catch (Exception e) {
            logger.debug("Could not re-extract component details for {}: {}", r.getFilename(), e.getMessage());
        }
    }

    /**
     * 기존 캐시에 histogramHtml/threadOverviewHtml이 없을 때 ZIP에서 재추출.
     */
    private void reparseActions(HeapAnalysisResult r) {
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            parser.reparseActions(resultDir.getAbsolutePath(), baseName, tmp);
            if (tmp.getHistogramHtml() != null && !tmp.getHistogramHtml().isEmpty()) {
                r.setHistogramHtml(tmp.getHistogramHtml());
                r.setHistogramEntries(tmp.getHistogramEntries());
                r.setTotalHistogramClasses(tmp.getTotalHistogramClasses());
            }
            if (tmp.getThreadOverviewHtml() != null && !tmp.getThreadOverviewHtml().isEmpty()) {
                r.setThreadOverviewHtml(tmp.getThreadOverviewHtml());
                r.setThreadInfos(tmp.getThreadInfos());
            }
        } catch (Exception e) {
            logger.debug("Could not re-extract actions for {}: {}", r.getFilename(), e.getMessage());
        }
    }

    /**
     * .threads 파일을 찾아 threadStacksText에 로드.
     */
    private void loadThreadStacksText(HeapAnalysisResult r) {
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        // 결과 디렉토리 우선, 없으면 상위 디렉토리에서 탐색
        File threadsFile = new File(resultDirectory(r.getFilename()), baseName + ".threads");
        if (!threadsFile.exists()) {
            threadsFile = new File(config.getHeapDumpDirectory(), baseName + ".threads");
        }
        if (threadsFile.exists() && threadsFile.isFile()) {
            try {
                String content = new String(Files.readAllBytes(threadsFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                r.setThreadStacksText(content);
                matchThreadStackTraces(r, content);
                logger.debug("Loaded .threads file for {}: {} chars", r.getFilename(), content.length());
            } catch (IOException e) {
                logger.debug("Failed to read .threads file for {}: {}", r.getFilename(), e.getMessage());
            }
        }
    }

    /**
     * .threads 파일 내용을 파싱하여 각 ThreadInfo에 스택트레이스를 매칭합니다.
     * 형식: "Thread 0xADDRESS\n  at method...\n  ...\n\n  locals:\n  ..."
     */
    private void matchThreadStackTraces(HeapAnalysisResult r, String threadsText) {
        if (r.getThreadInfos() == null || r.getThreadInfos().isEmpty()) return;

        // 주소 → ThreadInfo 매핑
        Map<String, com.heapdump.analyzer.model.ThreadInfo> addrMap = new java.util.HashMap<>();
        for (com.heapdump.analyzer.model.ThreadInfo ti : r.getThreadInfos()) {
            if (ti.getAddress() != null && !ti.getAddress().isEmpty()) {
                addrMap.put(ti.getAddress().toLowerCase(), ti);
            }
        }

        // .threads 파일을 "Thread 0x..." 단위로 분할
        String[] blocks = threadsText.split("(?=Thread 0x)");
        int matched = 0;
        for (String block : blocks) {
            block = block.trim();
            if (!block.startsWith("Thread 0x")) continue;

            // 주소 추출
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "Thread (0x[0-9a-fA-F]+)").matcher(block);
            if (!m.find()) continue;
            String addr = m.group(1).toLowerCase();

            com.heapdump.analyzer.model.ThreadInfo ti = addrMap.get(addr);
            if (ti == null) continue;

            // 스택트레이스만 추출 (locals: 이전까지)
            String stackPart = block;
            int localsIdx = stackPart.indexOf("locals:");
            if (localsIdx > 0) {
                stackPart = stackPart.substring(0, localsIdx).trim();
            }

            // 첫 줄(Thread 0x...) 제거하고 "at ..." 부분만
            int firstNewline = stackPart.indexOf('\n');
            if (firstNewline > 0) {
                stackPart = stackPart.substring(firstNewline + 1).trim();
            }

            if (!stackPart.isEmpty()) {
                ti.setStackTrace(stackPart);
                matched++;
            }
        }
        logger.debug("Matched {} thread stack traces out of {} threads", matched, r.getThreadInfos().size());
    }

    private String extractBodyContent(String html) {
        if (html == null || html.isEmpty()) return html;
        // 이미 body만 추출된 경우 (<!DOCTYPE 없음) 그대로 반환
        if (!html.contains("<!DOCTYPE") && !html.contains("<html")) return html;

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<body[^>]*>(.*)</body>",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(html);
        if (m.find()) {
            html = m.group(1);
        }
        // script 태그 제거
        html = html.replaceAll("(?i)<script[^>]*>.*?</script>", "");
        // 외부 CSS link 제거
        html = html.replaceAll("(?i)<link[^>]*>", "");
        // 이미지 제거
        html = html.replaceAll("(?i)<img[^>]+src\\s*=\\s*['\"][^'\"]*['\"][^>]*>", "");
        // hidden input 제거
        html = html.replaceAll("(?i)<input[^>]+type\\s*=\\s*['\"]hidden['\"][^>]*>", "");
        // 이벤트 핸들러 제거 (중첩 따옴표 처리)
        html = html.replaceAll("(?i)\\s+on\\w+\\s*=\\s*\"[^\"]*\"", "");
        html = html.replaceAll("(?i)\\s+on\\w+\\s*=\\s*'[^']*'", "");
        // mat:// 프로토콜 href 제거
        html = html.replaceAll("(?i)href\\s*=\\s*\"mat://[^\"]*\"", "href=\"javascript:void(0)\"");
        // 깨진 href 정리
        html = html.replaceAll("(?i)href\\s*=\\s*\"(?!https?://|javascript:|#\")[^\"]*\"", "href=\"javascript:void(0)\"");
        html = html.replaceAll("(?i)href\\s*=\\s*'(?!https?://|javascript:|#')[^']*'", "href=\"javascript:void(0)\"");
        // href="#" → javascript:void(0)
        html = html.replaceAll("(?i)href\\s*=\\s*['\"]#['\"]", "href=\"javascript:void(0)\"");
        return html.trim();
    }

    private void cleanupTmpDir() {
        File tmpDir = tmpDirectory();
        if (!tmpDir.exists()) return;
        File[] files = tmpDir.listFiles();
        if (files == null || files.length == 0) return;
        int cleaned = 0;
        for (File f : files) {
            if (f.isFile() && f.delete()) cleaned++;
        }
        if (cleaned > 0) {
            logger.info("[Cleanup] Removed {} leftover tmp files", cleaned);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("[Shutdown] HeapDumpAnalyzerService shutting down — cached results: {}, terminating thread pool...",
                memCache.size());
        executor.shutdownNow();
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.info("[Shutdown] Thread pool terminated gracefully");
            } else {
                logger.warn("[Shutdown] Thread pool did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            logger.warn("[Shutdown] Thread pool termination interrupted");
            Thread.currentThread().interrupt();
        }
        cleanupTmpDir();
        logger.info("[Shutdown] HeapDumpAnalyzerService shutdown complete");
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
    public Collection<HeapAnalysisResult> getAllCachedResults() { return Collections.unmodifiableCollection(memCache.values()); }
    public Set<String> getCacheKeys()               { return Collections.unmodifiableSet(memCache.keySet()); }
    public boolean isMatCliReady()                 { return config.isMatCliReady(); }
    public String  getMatCliStatusMessage()        { return config.getMatCliStatusMessage(); }

    // ── 파일 관리 ────────────────────────────────────────────────

    public String uploadFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            logger.warn("[Upload] Rejected: empty file");
            throw new IllegalArgumentException("File is empty");
        }

        String originalName = file.getOriginalFilename();
        String filename = Optional.ofNullable(originalName)
                .map(n -> new File(n).getName()).filter(n -> !n.isEmpty())
                .orElseThrow(() -> {
                    logger.warn("[Upload] Rejected: invalid or missing filename");
                    return new IllegalArgumentException("Invalid filename");
                });

        logger.info("[Upload] Started: filename={}, size={}, contentType={}",
                filename, formatBytes(file.getSize()), file.getContentType());

        if (!isValidHeapDumpFile(filename)) {
            String ext = getExtension(filename);
            logger.warn("[Upload] Rejected: invalid extension '{}' for file '{}'. Allowed: .hprof, .bin, .dump",
                    ext, filename);
            throw new IllegalArgumentException(
                    "'" + ext + "' is not a supported file type. Only .hprof, .bin, .dump files are allowed.");
        }

        File tmpDir = tmpDirectory();
        Files.createDirectories(tmpDir.toPath());
        Path target = tmpDir.toPath().resolve(filename);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("[Upload] Failed to write file '{}' to tmp: {}", filename, e.getMessage(), e);
            throw e;
        }

        long writtenSize = Files.size(target);
        logger.info("[Upload] Completed: filename={}, writtenSize={}, path={} (tmp)",
                filename, formatBytes(writtenSize), target.toAbsolutePath());
        return filename;
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0)                      return "0 B";
        if (bytes < 1024)                    return bytes + " B";
        if (bytes < 1024 * 1024)             return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)     return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public List<HeapDumpFile> listFiles() {
        List<HeapDumpFile> result = new ArrayList<>();

        // 기존 heapdump 디렉토리 파일
        File dir = new File(config.getHeapDumpDirectory());
        File[] files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
        Set<String> existing = new HashSet<>();
        if (files != null) {
            // 원본과 .gz가 동시에 존재하면 .gz 삭제 (원본 우선)
            cleanupDuplicateGzFiles(files);

            // 정리 후 다시 조회
            files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
            if (files != null) {
                for (File f : files) {
                    // .gz 파일은 원본 이름으로 표시
                    String displayName = f.getName();
                    if (displayName.toLowerCase().endsWith(".gz")) {
                        displayName = displayName.substring(0, displayName.length() - 3);
                    }
                    if (!existing.contains(displayName)) {
                        result.add(new HeapDumpFile(displayName, f.getAbsolutePath(),
                                f.length(), f.lastModified()));
                        existing.add(displayName);
                    }
                }
            }
        }

        // tmp 디렉토리 파일 (분석 대기 중)
        File tmpDir = tmpDirectory();
        File[] tmpFiles = tmpDir.exists() ? tmpDir.listFiles((d, n) -> isValidHeapDumpFile(n)) : null;
        if (tmpFiles != null) {
            for (File f : tmpFiles) {
                if (!existing.contains(f.getName())) {
                    result.add(new HeapDumpFile(f.getName(), f.getAbsolutePath(),
                            f.length(), f.lastModified()));
                }
            }
        }

        result.sort(Comparator.comparingLong(HeapDumpFile::getLastModified).reversed());
        return result;
    }

    /**
     * 원본 덤프 파일과 .gz 파일이 동시에 존재하면 .gz 파일을 삭제한다.
     * 원본이 있으면 .gz는 중복이므로 제거하여 디스크 공간을 절약한다.
     */
    private void cleanupDuplicateGzFiles(File[] files) {
        Set<String> originals = new HashSet<>();
        List<File> gzFiles = new ArrayList<>();

        for (File f : files) {
            String name = f.getName();
            if (name.toLowerCase().endsWith(".gz")) {
                gzFiles.add(f);
            } else {
                originals.add(name);
            }
        }

        for (File gz : gzFiles) {
            // example.hprof.gz → example.hprof
            String originalName = gz.getName().substring(0, gz.getName().length() - 3);
            if (originals.contains(originalName)) {
                long gzSize = gz.length();
                if (gz.delete()) {
                    logger.info("[Cleanup] 중복 .gz 파일 삭제: {} ({})", gz.getName(), formatBytes(gzSize));
                } else {
                    logger.warn("[Cleanup] 중복 .gz 파일 삭제 실패: {}", gz.getName());
                }
            }
        }
    }

    public File getFile(String filename) throws IOException {
        filename = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), filename);
        if (file.exists() && file.isFile()) return file;
        // .gz 압축 파일도 탐색
        File gzFile = new File(config.getHeapDumpDirectory(), filename + ".gz");
        if (gzFile.exists() && gzFile.isFile()) return gzFile;
        // tmp에서도 탐색
        File tmpFile = new File(tmpDirectory(), filename);
        if (tmpFile.exists() && tmpFile.isFile()) return tmpFile;
        throw new FileNotFoundException("File not found: " + filename);
    }

    public void deleteFile(String filename) throws IOException {
        String safe = new File(filename).getName();
        File file = new File(config.getHeapDumpDirectory(), safe);
        File tmpFile = new File(tmpDirectory(), safe);

        logger.info("[Delete] Started: filename={}", safe);

        // tmp에 있으면 tmp에서 삭제
        if (tmpFile.exists()) {
            long tmpSize = tmpFile.length();
            if (tmpFile.delete()) {
                logger.info("[Delete] Tmp file deleted: filename={}, size={}", safe, formatBytes(tmpSize));
            } else {
                logger.warn("[Delete] Failed to delete tmp file: {}", safe);
            }
        }

        // .gz 압축 파일도 확인
        File gzFile = new File(config.getHeapDumpDirectory(), safe + ".gz");

        if (!file.exists() && !tmpFile.exists() && !gzFile.exists()) {
            logger.warn("[Delete] Heap dump file not found: {}", safe);
            throw new FileNotFoundException("File not found: " + safe);
        }

        if (file.exists()) {
            long fileSize = file.length();
            if (!file.delete()) {
                logger.error("[Delete] Failed to delete heap dump file: {}", safe);
                throw new IOException("Failed to delete: " + safe);
            }
            logger.info("[Delete] Heap dump file deleted: filename={}, size={}", safe, formatBytes(fileSize));
        }

        // .gz 압축 파일 삭제
        if (gzFile.exists()) {
            long gzSize = gzFile.length();
            if (gzFile.delete()) {
                logger.info("[Delete] Compressed file deleted: filename={}, size={}", gzFile.getName(), formatBytes(gzSize));
            } else {
                logger.warn("[Delete] Failed to delete compressed file: {}", gzFile.getName());
            }
        }

        // 상위 디렉토리의 MAT 인덱스 파일 삭제 (예: heapdump.a2s.index, heapdump.threads 등)
        String baseName = stripExtension(safe);
        File parentDir = new File(config.getHeapDumpDirectory());
        File[] relatedFiles = parentDir.listFiles((dir, name) ->
                name.startsWith(baseName + ".") && !name.equals(safe));
        int relatedCount = 0;
        if (relatedFiles != null) {
            for (File related : relatedFiles) {
                if (related.isFile()) {
                    if (related.delete()) {
                        relatedCount++;
                        logger.debug("[Delete] Related file deleted: {}", related.getName());
                    } else {
                        logger.warn("[Delete] Failed to delete related file: {}", related.getName());
                    }
                }
            }
        }
        if (relatedCount > 0) {
            logger.info("[Delete] {} related index files deleted for '{}'", relatedCount, safe);
        }

        // 분석 결과(data/ 디렉토리)와 메모리 캐시는 보존 — 이력 유지
        logger.info("[Delete] Completed: heap dump file deleted for '{}', analysis data preserved in data/", safe);
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
                if (r != null && (r.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS
                        || r.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR)) {
                    File logFile = new File(resultDirectory(safe), MAT_LOG_FILE);
                    if (logFile.exists())
                        r.setMatLog(new String(Files.readAllBytes(logFile.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8));
                    if (r.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS) {
                        sanitizeCachedHtml(r);
                    }
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
        File resultDir = resultDirectory(safe);
        if (resultDir.exists() && resultDir.isDirectory()) {
            deleteDirectoryRecursively(resultDir);
            logger.info("Result directory deleted: {}", resultDir.getAbsolutePath());
        }
        logger.info("Cache cleared: {}", safe);
    }

    private void deleteDirectoryRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectoryRecursively(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    // ── SSE 기반 비동기 분석 ─────────────────────────────────────

    /** 현재 큐 대기 수 (API 노출용) */
    public int getQueueSize() { return queueSize.get(); }

    /** 현재 분석 중인 파일명 (API 노출용) */
    public String getCurrentAnalysisFilename() { return currentAnalysisFilename; }

    public Future<?> analyzeWithProgress(String filename, SseEmitter emitter) {
        final String safe = new File(filename).getName();
        queueSize.incrementAndGet();
        return executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            File tmpFile = new File(tmpDirectory(), safe);
            boolean analysisSuccess = false;
            boolean semaphoreAcquired = false;
            try {
                // ── 큐 대기: 세마포어를 즉시 획득할 수 없으면 대기 상태를 SSE로 전송 ──
                if (!analysisSemaphore.tryAcquire()) {
                    logger.info("[Analysis] Queued: {} (queue size: {}, running: {})",
                            safe, queueSize.get(), currentAnalysisFilename);
                    // 대기 중 SSE 업데이트를 주기적으로 전송
                    while (!analysisSemaphore.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        int pos = queueSize.get() - 1; // 현재 실행 중인 것 제외
                        String current = currentAnalysisFilename;
                        sendProgress(emitter, AnalysisProgress.queued(safe, pos, current));
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Cancelled while queued");
                        }
                    }
                    logger.info("[Analysis] Dequeued, starting: {} (waited {}ms)",
                            safe, System.currentTimeMillis() - startTime);
                }
                semaphoreAcquired = true;
                currentAnalysisFilename = safe;

                sendProgress(emitter, AnalysisProgress.step(safe, 3, "힙 덤프 파일 확인 중..."));

                // tmp에 파일이 있으면 tmp에서, 아니면 heapdump 디렉토리에서 탐색
                File dumpFile;
                if (tmpFile.exists()) {
                    dumpFile = tmpFile;
                    logger.info("[Analysis] File found in tmp: {}", safe);
                } else {
                    dumpFile = new File(config.getHeapDumpDirectory(), safe);
                    File gzFile = new File(config.getHeapDumpDirectory(), safe + ".gz");

                    if (dumpFile.exists() && gzFile.exists()) {
                        // 원본과 .gz가 동시에 존재 → .gz 삭제 (원본 우선)
                        long gzSize = gzFile.length();
                        if (gzFile.delete()) {
                            logger.info("[Analysis] 중복 .gz 파일 삭제: {} ({})", gzFile.getName(), formatBytes(gzSize));
                        }
                    } else if (!dumpFile.exists() && gzFile.exists()) {
                        // 원본 없고 .gz만 존재 → 압축 해제 후 분석
                        sendProgress(emitter, AnalysisProgress.step(safe, 4, "압축 해제 중..."));
                        decompressDumpFile(gzFile, dumpFile);
                        logger.info("[Analysis] Decompressed .gz file for re-analysis: {}", safe);
                    } else if (!dumpFile.exists()) {
                        sendProgress(emitter, AnalysisProgress.error(safe, "파일을 찾을 수 없습니다: " + safe));
                        emitter.complete(); return;
                    }
                    logger.info("[Analysis] File found in heapdump dir (re-analysis): {}", safe);
                }

                sendProgress(emitter, AnalysisProgress.step(safe, 5, "파일 확인 완료"));

                // MAT CLI 사전 검증
                if (!config.isMatCliReady()) {
                    String matErr = config.getMatCliStatusMessage();
                    logger.error("[Analysis] MAT CLI is not ready: {}", matErr);
                    sendProgress(emitter, AnalysisProgress.error(safe,
                            "MAT CLI를 사용할 수 없습니다: " + matErr
                            + " — 관리자에게 MAT CLI 설치 상태를 확인해 주세요."));
                    emitter.complete();
                    return;
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
                moveArtifactsToResultDir(base, safe, resultDir);

                sendProgress(emitter, AnalysisProgress.parsing(safe, 88, "Overview 파싱 중..."));
                MatParseResult parsed = parser.parse(resultDir.getAbsolutePath(), base);
                if (!parsed.hasData()) {
                    // heapDumpDir fallback은 정확히 base 이름이 일치하는 ZIP만 사용
                    // (다른 분석 결과의 ZIP을 잘못 매칭하는 것을 방지)
                    logger.warn("ZIP not in resultDir, fallback to heapDumpDir (strict match for base='{}')", base);
                    parsed = parser.parse(config.getHeapDumpDirectory(), base);
                    if (!parsed.hasData()) {
                        logger.warn("[Analysis] No matching ZIPs found for '{}' in heapDumpDir either", base);
                    }
                }

                sendProgress(emitter, AnalysisProgress.parsing(safe, 93, "Top Components 분석 중..."));
                Thread.sleep(200);
                sendProgress(emitter, AnalysisProgress.parsing(safe, 96, "Leak Suspects 분석 중..."));
                Thread.sleep(200);
                sendProgress(emitter, AnalysisProgress.parsing(safe, 99, "결과 조립 중..."));

                // tmp 파일이면 최종 위치로 이동
                if (tmpFile.exists()) {
                    Path finalPath = Paths.get(config.getHeapDumpDirectory(), safe);
                    Files.move(tmpFile.toPath(), finalPath, StandardCopyOption.REPLACE_EXISTING);
                    dumpFile = finalPath.toFile();
                    logger.info("[Analysis] Moved tmp → final: {}", finalPath);
                }

                HeapAnalysisResult result = buildResult(safe, dumpFile, parsed, matLog);
                result.setAnalysisTime(System.currentTimeMillis() - startTime);

                // Heap 데이터가 없으면 분석 실패로 처리
                boolean hasHeapData = result.getTotalHeapSize() > 0 || result.getUsedHeapSize() > 0;
                if (!hasHeapData) {
                    result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.ERROR);
                    result.setErrorMessage("Heap data not available — MAT ZIP 파싱 결과에 힙 데이터가 없습니다.");
                    memCache.put(safe, result);
                    saveResultToDisk(result, resultDir);
                    analysisSuccess = true; // tmp 파일 삭제 방지 (결과는 저장됨)
                    sendProgress(emitter, AnalysisProgress.error(safe, "Heap data not available"));
                    logger.warn("[Analysis] No heap data for {}, marked as ERROR", safe);
                } else {
                    result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);
                    memCache.put(safe, result);
                    saveResultToDisk(result, resultDir);
                    analysisSuccess = true;

                    // 분석 완료 후 덤프 파일 gzip 압축
                    compressDumpFile(dumpFile);

                    sendProgress(emitter, AnalysisProgress.completed(safe, "/analyze/result/" + safe));
                    logger.info("[Analysis] Done: {} in {}ms", safe, result.getAnalysisTime());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("[Analysis] Interrupted (client disconnect or shutdown): {}", safe);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("[Analysis] Interrupted during processing: {}", safe);
                } else {
                    logger.error("[Analysis] Failed for {}", safe, e);
                    sendProgress(emitter, AnalysisProgress.error(safe, e.getMessage()));

                    // 분석 실패 결과를 memCache + 디스크에 저장 (파일 삭제 전까지 유지)
                    try {
                        // tmp 파일이면 최종 위치로 이동하여 보존
                        File finalFile;
                        if (tmpFile.exists()) {
                            Path finalPath = Paths.get(config.getHeapDumpDirectory(), safe);
                            Files.move(tmpFile.toPath(), finalPath, StandardCopyOption.REPLACE_EXISTING);
                            finalFile = finalPath.toFile();
                            logger.info("[Analysis] Moved failed tmp → final: {}", finalPath);
                        } else {
                            finalFile = new File(config.getHeapDumpDirectory(), safe);
                        }

                        HeapAnalysisResult errorResult = new HeapAnalysisResult();
                        errorResult.setFilename(safe);
                        errorResult.setFileSize(finalFile.exists() ? finalFile.length() : 0);
                        errorResult.setLastModified(finalFile.exists() ? finalFile.lastModified() : System.currentTimeMillis());
                        errorResult.setFormat(getExtension(safe).toUpperCase());
                        errorResult.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.ERROR);
                        errorResult.setErrorMessage(e.getMessage());
                        errorResult.setAnalysisTime(System.currentTimeMillis() - startTime);

                        // MAT CLI 로그가 있으면 에러 결과에도 포함
                        File errorResultDir = resultDirectory(safe);
                        File matLogFile = new File(errorResultDir, MAT_LOG_FILE);
                        if (matLogFile.exists()) {
                            try {
                                errorResult.setMatLog(new String(Files.readAllBytes(matLogFile.toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8));
                            } catch (Exception logEx) {
                                logger.warn("[Analysis] Failed to read mat.log for error result: {}", logEx.getMessage());
                            }
                        }
                        Files.createDirectories(errorResultDir.toPath());
                        memCache.put(safe, errorResult);
                        saveResultToDisk(errorResult, errorResultDir);
                        analysisSuccess = true; // tmp 파일 삭제 방지 (이미 이동 완료)
                        logger.info("[Analysis] Error result saved for: {}", safe);
                    } catch (Exception saveEx) {
                        logger.warn("[Analysis] Failed to save error result for {}: {}", safe, saveEx.getMessage());
                    }
                }
            } finally {
                // 세마포어 해제 및 큐 카운터 감소
                if (semaphoreAcquired) {
                    currentAnalysisFilename = null;
                    analysisSemaphore.release();
                }
                queueSize.decrementAndGet();

                // 분석 실패/중단 시 tmp 파일 삭제
                if (!analysisSuccess && tmpFile.exists()) {
                    if (tmpFile.delete()) {
                        logger.info("[Analysis] Tmp file cleaned up: {}", safe);
                    } else {
                        logger.warn("[Analysis] Failed to clean up tmp file: {}", safe);
                    }
                }
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

        cmd.add("org.eclipse.mat.api:overview");
        cmd.add("org.eclipse.mat.api:top_components");
        cmd.add("org.eclipse.mat.api:suspects");

        logger.info("Running MAT CLI: {}", String.join(" ", cmd));
        sendProgress(emitter, AnalysisProgress.step(filename, 15, "MAT CLI 실행 중..."));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MALLOC_ARENA_MAX", "2");
        pb.directory(resultDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        final int[] pct = {15};
        final int[] lineCount = {0};
        final String[] phase = {"init"};  // init → overview → top_components → suspects
        StringBuilder output = new StringBuilder();

        final Thread callerThread = Thread.currentThread();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    lineCount[0]++;
                    if (callerThread.isInterrupted()) break;

                    // 리포트 단계 감지 (MAT CLI Subtask 출력 패턴 기반)
                    if (line.startsWith("Subtask: System Overview") && !"overview".equals(phase[0])) {
                        phase[0] = "overview";
                        pct[0] = 40;
                        logger.info("[MAT CLI] Report phase: overview (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "overview", "Overview 리포트 생성 중..."));
                        continue;
                    } else if ((line.startsWith("Subtask: Top Component"))
                               && !"top_components".equals(phase[0])) {
                        phase[0] = "top_components";
                        pct[0] = 55;
                        logger.info("[MAT CLI] Report phase: top_components (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "top_components", "Top Components 리포트 생성 중..."));
                        continue;
                    } else if (line.startsWith("Subtask: Leak Suspects") && !"suspects".equals(phase[0])) {
                        phase[0] = "suspects";
                        pct[0] = 68;
                        logger.info("[MAT CLI] Report phase: suspects (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "suspects", "Leak Suspects 리포트 생성 중..."));
                        continue;
                    }

                    // 현재 단계별 최대 진행률 제한
                    int maxPct;
                    switch (phase[0]) {
                        case "overview":       maxPct = 55; break;
                        case "top_components": maxPct = 68; break;
                        case "suspects":       maxPct = 80; break;
                        default:               maxPct = 40; break; // init
                    }
                    int prevPct = pct[0];
                    if (pct[0] < maxPct) pct[0] = Math.min(maxPct, pct[0] + 1);
                    // 진행률이 변경되었거나 50줄마다 한 번씩 전송 (로그 라인 누적용)
                    if (pct[0] != prevPct || lineCount[0] % 50 == 0) {
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line, phase[0], null));
                    }
                }
            } catch (IOException e) {
                logger.warn("MAT output read error: {}", e.getMessage());
            }
        }, executor);

        boolean finished = process.waitFor(MAT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        reader.join();

        if (!finished) {
            process.destroyForcibly();
            logger.error("[MAT CLI] Process timed out after {} minutes for file: {}", MAT_TIMEOUT_MINUTES, filename);
            throw new RuntimeException("MAT CLI가 " + MAT_TIMEOUT_MINUTES + "분 제한 시간을 초과했습니다. "
                    + "힙 덤프 파일이 너무 크거나 시스템 메모리가 부족할 수 있습니다.");
        }

        int exitCode = process.exitValue();
        String matOutput = output.toString();

        if (exitCode != 0) {
            logger.error("[MAT CLI] Exited with code {} for file: {}", exitCode, filename);
            // 출력에서 핵심 에러 메시지 추출
            String errorHint = extractMatErrorHint(matOutput);
            if (!errorHint.isEmpty()) {
                logger.error("[MAT CLI] Error detail: {}", errorHint);
            }
            // MAT CLI 실패 시 즉시 예외 발생 — 잘못된 파일의 분석 성공 방지
            String errorMsg = !errorHint.isEmpty() ? errorHint
                    : "MAT CLI가 오류 코드 " + exitCode + "로 종료되었습니다. 유효한 힙 덤프 파일인지 확인하세요.";
            throw new RuntimeException(errorMsg);
        } else {
            logger.info("[MAT CLI] Completed successfully for file: {} (exit=0)", filename);
        }

        sendProgress(emitter, AnalysisProgress.step(filename, 82,
                "MAT CLI 완료 (exit=" + exitCode + ")"));
        return matOutput;
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
        c.setErrorMessage(r.getErrorMessage());
        c.setOverviewHtml(r.getOverviewHtml());   c.setTopComponentsHtml(r.getTopComponentsHtml());
        c.setSuspectsHtml(r.getSuspectsHtml());   c.setMatLog(null);
        c.setHistogramHtml(r.getHistogramHtml());
        c.setThreadOverviewHtml(r.getThreadOverviewHtml());
        c.setHistogramEntries(r.getHistogramEntries());
        c.setThreadInfos(r.getThreadInfos());
        c.setTotalHistogramClasses(r.getTotalHistogramClasses());
        // threadStacksText는 @JsonIgnore이므로 저장하지 않음
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

        // Top Objects의 retained heap 합산으로 실제 사용량 계산
        long topObjTotal = 0;
        if (parsed.getTopMemoryObjects() != null) {
            for (com.heapdump.analyzer.model.MemoryObject obj : parsed.getTopMemoryObjects()) {
                topObjTotal += obj.getTotalSize();
            }
        }

        if (parsed.getTotalHeapSize() > 0 && topObjTotal > 0) {
            // Top Objects 합산이 total보다 작으면 나머지가 Others (free 아님, 미분류 used)
            // MAT GUI 방식: total = "Used heap dump", Top Objects는 그 중 주요 소비자
            r.setUsedHeapSize(topObjTotal);
            r.setFreeHeapSize(parsed.getTotalHeapSize() - topObjTotal);
            r.setHeapUsagePercent(topObjTotal * 100.0 / parsed.getTotalHeapSize());
        } else {
            r.setUsedHeapSize(parsed.getUsedHeapSize());
            r.setFreeHeapSize(parsed.getFreeHeapSize());
            if (parsed.getTotalHeapSize() > 0)
                r.setHeapUsagePercent(parsed.getUsedHeapSize() * 100.0 / parsed.getTotalHeapSize());
        }
        r.setTopMemoryObjects(parsed.getTopMemoryObjects());
        r.setLeakSuspects(parsed.getLeakSuspects());
        r.setTotalClasses(parsed.getTotalClasses());
        r.setTotalObjects(parsed.getTotalObjects());
        r.setOverviewHtml(parsed.getOverviewHtml());
        r.setTopComponentsHtml(parsed.getTopComponentsHtml());
        r.setSuspectsHtml(parsed.getSuspectsHtml());
        r.setComponentDetailHtmlMap(parsed.getComponentDetailHtmlMap());
        r.setHistogramHtml(parsed.getHistogramHtml());
        r.setThreadOverviewHtml(parsed.getThreadOverviewHtml());
        r.setHistogramEntries(parsed.getHistogramEntries());
        r.setThreadInfos(parsed.getThreadInfos());
        r.setTotalHistogramClasses(parsed.getTotalHistogramClasses());

        // .threads 파일 로드
        loadThreadStacksText(r);

        return r;
    }

    // ── 경로 / 유틸리티 ──────────────────────────────────────────

    private File resultDirectory(String filename) {
        return new File(config.getDataDirectory(), stripExtension(filename));
    }
    private File resultJsonFile(String filename) {
        return new File(resultDirectory(filename), RESULT_JSON);
    }

    /**
     * SSE 진행 전송. emitter가 이미 완료된 경우 조용히 스킵.
     * 클라이언트 disconnect 감지 시 분석 스레드를 인터럽트한다.
     */
    private void sendProgress(SseEmitter emitter, AnalysisProgress progress) {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(objectMapper.writeValueAsString(progress)));
        } catch (IllegalStateException e) {
            // ResponseBodyEmitter has already completed — 클라이언트 disconnect
            // 현재 스레드 인터럽트하여 분석 중단 유도
            logger.info("[SSE] Client disconnected during analysis, interrupting thread");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.debug("[SSE] Send failed: {}", e.getMessage());
        }
    }

    /**
     * 분석 완료된 덤프 파일을 gzip으로 압축한다.
     * 압축 전 디스크 여유 공간이 원본 파일 크기 이상인지 점검한다.
     */
    private void compressDumpFile(File dumpFile) {
        if (dumpFile == null || !dumpFile.exists() || !dumpFile.isFile()) {
            return;
        }

        // 이미 .gz 파일이면 스킵
        if (dumpFile.getName().toLowerCase().endsWith(".gz")) {
            return;
        }

        long fileSize = dumpFile.length();
        long usableSpace = dumpFile.getParentFile().getUsableSpace();

        if (usableSpace < fileSize) {
            logger.warn("[Compress] 디스크 여유 공간 부족으로 압축 건너뜀: 필요={}, 여유={}, 파일={}",
                    formatBytes(fileSize), formatBytes(usableSpace), dumpFile.getName());
            return;
        }

        File gzFile = new File(dumpFile.getAbsolutePath() + ".gz");

        // 이미 .gz 파일이 존재하면 삭제 후 재압축
        if (gzFile.exists()) {
            logger.info("[Compress] 기존 .gz 파일 삭제 후 재압축: {}", gzFile.getName());
            gzFile.delete();
        }

        logger.info("[Compress] 덤프 파일 gzip 압축 시작: {} ({})", dumpFile.getName(), formatBytes(fileSize));

        try (FileInputStream fis = new FileInputStream(dumpFile);
             FileOutputStream fos = new FileOutputStream(gzFile);
             GZIPOutputStream gzos = new GZIPOutputStream(fos, 8192)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, len);
            }
            gzos.finish();
        } catch (IOException e) {
            logger.error("[Compress] gzip 압축 실패: {}", dumpFile.getName(), e);
            // 실패 시 불완전한 .gz 파일 삭제
            if (gzFile.exists()) {
                gzFile.delete();
            }
            return;
        }

        // 원본 파일 삭제
        if (dumpFile.delete()) {
            logger.info("[Compress] 압축 완료: {} → {} ({}→{})",
                    dumpFile.getName(), gzFile.getName(),
                    formatBytes(fileSize), formatBytes(gzFile.length()));
        } else {
            logger.warn("[Compress] 원본 파일 삭제 실패: {}", dumpFile.getName());
        }
    }

    /**
     * gzip 압축된 덤프 파일을 원본으로 복원한다.
     * 복원 전 디스크 여유 공간을 점검한다 (압축 파일 크기의 3배 이상 필요).
     */
    private void decompressDumpFile(File gzFile, File destFile) throws IOException {
        long gzSize = gzFile.length();
        long usableSpace = gzFile.getParentFile().getUsableSpace();

        // 압축 해제 시 원본은 압축 파일보다 클 수 있으므로 여유 있게 점검
        if (usableSpace < gzSize * 3) {
            throw new IOException("디스크 여유 공간 부족으로 압축 해제 불가: 여유=" +
                    formatBytes(usableSpace) + ", 압축파일=" + formatBytes(gzSize));
        }

        logger.info("[Decompress] gzip 압축 해제 시작: {} ({})", gzFile.getName(), formatBytes(gzSize));

        try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(gzFile), 8192);
             FileOutputStream fos = new FileOutputStream(destFile)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            // 실패 시 불완전한 파일 삭제
            if (destFile.exists()) {
                destFile.delete();
            }
            throw e;
        }

        // 압축 파일 삭제
        if (gzFile.delete()) {
            logger.info("[Decompress] 압축 해제 완료: {} → {} ({}→{})",
                    gzFile.getName(), destFile.getName(),
                    formatBytes(gzSize), formatBytes(destFile.length()));
        } else {
            logger.warn("[Decompress] 압축 파일 삭제 실패: {}", gzFile.getName());
        }
    }

    private boolean isValidHeapDumpFile(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.endsWith(".hprof") || l.endsWith(".bin") || l.endsWith(".dump")
                || l.endsWith(".hprof.gz") || l.endsWith(".bin.gz") || l.endsWith(".dump.gz");
    }

    private String stripExtension(String name) {
        // .hprof.gz → base name (strip .gz first, then .hprof)
        String l = name.toLowerCase();
        if (l.endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
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

    /**
     * MAT 인덱스 파일(.index)과 .threads 파일을 결과 디렉토리로 이동.
     * 분석 완료 후 상위 디렉토리를 깨끗하게 유지한다.
     */
    private void moveArtifactsToResultDir(String base, String safe, File resultDir) {
        File heapDir = new File(config.getHeapDumpDirectory());
        File[] artifacts = heapDir.listFiles((d, n) ->
                n.startsWith(base + ".") && !n.equals(safe)
                && (n.endsWith(".index") || n.endsWith(".threads")));
        if (artifacts == null || artifacts.length == 0) return;
        int moved = 0;
        for (File f : artifacts) {
            File dest = new File(resultDir, f.getName());
            try {
                Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved++;
            } catch (IOException e) {
                logger.warn("[Artifact Move] Failed to move {}: {}", f.getName(), e.getMessage());
            }
        }
        if (moved > 0) {
            logger.info("[Artifact Move] Moved {} index/threads files → {}", moved, resultDir.getName());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1048576)         return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824L)     return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * MAT CLI 출력에서 핵심 에러 힌트를 추출합니다.
     * OutOfMemoryError, SnapshotException, 권한 오류 등 주요 패턴을 감지합니다.
     */
    private String extractMatErrorHint(String matOutput) {
        if (matOutput == null || matOutput.isEmpty()) return "";

        String lower = matOutput.toLowerCase();

        if (lower.contains("outofmemoryerror") || lower.contains("java.lang.outofmemory")) {
            return "Java OutOfMemoryError — MAT 실행에 더 많은 힙 메모리가 필요합니다. "
                    + "MemoryAnalyzer.ini의 -Xmx 값을 늘려주세요.";
        }
        if (lower.contains("snapshotexception") || lower.contains("error opening heap dump")) {
            return "힙 덤프 파일이 손상되었거나 지원하지 않는 형식입니다. "
                    + "유효한 HPROF/PHD 형식인지 확인하세요.";
        }
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return "파일 또는 디렉토리 접근 권한이 부족합니다. 파일 권한을 확인하세요.";
        }
        if (lower.contains("no such file") || lower.contains("file not found")
                || lower.contains("cannot find")) {
            return "파일을 찾을 수 없습니다. 경로가 올바른지 확인하세요.";
        }
        if (lower.contains("disk full") || lower.contains("no space left")) {
            return "디스크 공간이 부족합니다. 불필요한 파일을 정리한 후 다시 시도하세요.";
        }
        if (lower.contains("exception") || lower.contains("error")) {
            // 마지막 Exception/Error 라인 추출
            String[] lines = matOutput.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.toLowerCase().contains("exception") || line.toLowerCase().contains("error")) {
                    if (line.length() > 200) line = line.substring(0, 200) + "...";
                    return line;
                }
            }
        }
        return "";
    }
}

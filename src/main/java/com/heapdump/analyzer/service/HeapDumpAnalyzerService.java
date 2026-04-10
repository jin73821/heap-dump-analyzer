package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.parser.MatReportParser;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    // MAT_TIMEOUT_MINUTES → config.getMatTimeoutMinutes()로 이동
    private static final String RESULT_JSON      = "result.json";
    private static final String AI_INSIGHT_FILE  = "ai_insight.json";
    private static final String MAT_LOG_FILE     = "mat.log";
    private static final String TMP_DIR_NAME     = "tmp";

    private final HeapDumpConfig  config;
    private final MatReportParser parser;

    public MatReportParser getParser() { return parser; }
    private final ObjectMapper    objectMapper = new ObjectMapper();

    // 메모리 1차 캐시
    private final ConcurrentHashMap<String, HeapAnalysisResult> memCache = new ConcurrentHashMap<>();

    // 비동기 실행 스레드 풀 (application.properties에서 설정, @PostConstruct에서 초기화)
    private ExecutorService executor;

    // 분석 동시 실행 제한: 한 번에 1개만 실행, 나머지는 큐 대기
    private final java.util.concurrent.Semaphore analysisSemaphore = new java.util.concurrent.Semaphore(1);
    private final java.util.concurrent.atomic.AtomicInteger queueSize = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile String currentAnalysisFilename = null;

    // 활성 분석 태스크 추적 (명시적 취소 API용)
    private final ConcurrentHashMap<String, java.util.concurrent.Future<?>> activeTasks = new ConcurrentHashMap<>();

    // 런타임 설정 (application.properties 초기값 → settings.json으로 영속화)
    private static final String SETTINGS_FILE = "settings.json";
    private volatile boolean keepUnreachableObjects;
    private volatile boolean compressAfterAnalysis;

    // LLM 런타임 설정
    private volatile boolean llmEnabled;
    private volatile String  llmProvider;
    private volatile String  llmApiUrl;
    private volatile String  llmModel;
    private volatile String  llmApiKey;
    private volatile int     llmMaxInputTokens;
    private volatile int     llmMaxOutputTokens;
    private volatile int     llmTimeoutConnectSeconds;
    private volatile int     llmTimeoutReadSeconds;

    public HeapDumpAnalyzerService(HeapDumpConfig config, MatReportParser parser) {
        this.config  = config;
        this.parser  = parser;
        this.keepUnreachableObjects = config.isKeepUnreachableObjects();
        this.compressAfterAnalysis = config.isCompressAfterAnalysis();
        // LLM 초기화
        this.llmEnabled = config.isLlmEnabled();
        this.llmProvider = config.getLlmProvider();
        this.llmApiUrl = config.getLlmApiUrl();
        this.llmModel = config.getLlmModel();
        this.llmApiKey = config.getLlmApiKey();
        this.llmMaxInputTokens = config.getLlmMaxInputTokens();
        this.llmMaxOutputTokens = config.getLlmMaxOutputTokens();
        this.llmTimeoutConnectSeconds = config.getLlmTimeoutConnectSeconds();
        this.llmTimeoutReadSeconds = config.getLlmTimeoutReadSeconds();
        // 환경변수 우선
        String envKey = System.getenv("LLM_API_KEY");
        if (envKey != null && !envKey.isEmpty()) {
            this.llmApiKey = envKey;
            logger.info("[LLM] API key loaded from environment variable LLM_API_KEY");
        }
    }

    // ── 스레드 풀 초기화 ───────────────────────────────────────────

    private void initExecutor() {
        int core = config.getThreadPoolCoreSize();
        int max  = config.getThreadPoolMaxSize();
        int queue = config.getThreadPoolQueueCapacity();
        logger.info("[ThreadPool] 분석 스레드 풀 초기화: core={}, max={}, queue={}", core, max, queue);

        executor = new java.util.concurrent.ThreadPoolExecutor(
                core, max, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(queue),
                (r, ex) -> {
                    logger.error("[ThreadPool] ★ 스레드 풀 고갈! 태스크가 거부되었습니다. "
                            + "active={}, poolSize={}, queueSize={}, completedTasks={}",
                            ex.getActiveCount(), ex.getPoolSize(),
                            ex.getQueue().size(), ex.getCompletedTaskCount());
                    logger.error("[ThreadPool] → application.properties의 analysis.thread-pool 설정을 늘려주세요.");
                    // CallerRunsPolicy 대체: 호출 스레드(Tomcat)에서 직접 실행하여 태스크 유실 방지
                    if (!ex.isShutdown()) {
                        r.run();
                    }
                });
    }

    // ── 시작 시 디스크 결과 복원 ──────────────────────────────────

    @PostConstruct
    public void restoreResultsFromDisk() {
        // ── 스레드 풀 초기화 ──
        initExecutor();

        // 영속 설정 복원 (application.properties 기본값을 settings.json으로 덮어씀)
        loadPersistedSettings();

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
        logger.info("Restored {} saved results from disk (data directory)", loaded);

        // 상위 디렉토리에 남은 .index/.threads 파일을 결과 디렉토리로 이동
        migrateStrayArtifacts(baseDir);

        // 기존 루트 디렉토리의 덤프 파일을 dumpfiles/로 마이그레이션
        migrateDumpFilesToNewDir();

        // 원본과 .gz가 동시에 존재하는 중복 파일 정리 (dumpfiles/ 디렉토리)
        File dumpDir = dumpFilesDirectory();
        File[] dumpDirFiles = dumpDir.listFiles((d, n) -> isValidHeapDumpFile(n));
        if (dumpDirFiles != null && dumpDirFiles.length > 0) {
            cleanupDuplicateGzFiles(dumpDirFiles);
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

    private void migrateDumpFilesToNewDir() {
        File baseDir = new File(config.getHeapDumpDirectory());
        File dumpDir = dumpFilesDirectory();
        File[] oldFiles = baseDir.listFiles((d, n) -> isValidHeapDumpFile(n));
        if (oldFiles == null || oldFiles.length == 0) return;
        int moved = 0;
        for (File f : oldFiles) {
            try {
                Path dest = dumpDir.toPath().resolve(f.getName());
                if (!Files.exists(dest)) {
                    Files.move(f.toPath(), dest);
                    moved++;
                }
            } catch (IOException e) {
                logger.warn("[Migration] Failed to move {} to dumpfiles: {}", f.getName(), e.getMessage());
            }
        }
        if (moved > 0) {
            logger.info("[Migration] Moved {} dump files from root to dumpfiles/", moved);
        }
    }

    private File tmpDirectory() {
        return new File(config.getHeapDumpDirectory(), TMP_DIR_NAME);
    }

    private File dumpFilesDirectory() {
        return new File(config.getDumpFilesDirectory());
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

        // componentDetailParsedMap이 비어있으면 기존 HTML에서 구조화 파싱
        if ((r.getComponentDetailParsedMap() == null || r.getComponentDetailParsedMap().isEmpty())
                && r.getComponentDetailHtmlMap() != null && !r.getComponentDetailHtmlMap().isEmpty()) {
            r.setComponentDetailParsedMap(new java.util.LinkedHashMap<>());
            for (java.util.Map.Entry<String, String> entry : r.getComponentDetailHtmlMap().entrySet()) {
                String key = entry.getKey();
                String className = key.contains("#") ? key.substring(0, key.lastIndexOf('#')) : key;
                com.heapdump.analyzer.model.ComponentDetailParsed parsed =
                        parser.parseComponentDetail(entry.getValue(), className);
                if (parsed.isParsedSuccessfully()) {
                    r.getComponentDetailParsedMap().put(key, parsed);
                }
            }
            if (!r.getComponentDetailParsedMap().isEmpty()) {
                logger.info("Lazy-parsed {} component details for {}", r.getComponentDetailParsedMap().size(), r.getFilename());
            }
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
                if (!tmp.getComponentDetailParsedMap().isEmpty()) {
                    r.setComponentDetailParsedMap(tmp.getComponentDetailParsedMap());
                }
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

    /**
     * MAT HTML 새니타이즈 — OWASP whitelist 기반.
     * 이전 버전에서 전체 HTML 문서가 저장된 경우 body 추출 후 정제.
     */
    private String extractBodyContent(String html) {
        if (html == null || html.isEmpty()) return html;
        return com.heapdump.analyzer.util.HtmlSanitizer.sanitize(html);
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
        logger.info("[Shutdown] HeapDumpAnalyzerService shutting down — saved results: {}, terminating thread pool...",
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
        persistSettings();
    }

    public boolean isCompressAfterAnalysis()      { return compressAfterAnalysis; }
    public void    setCompressAfterAnalysis(boolean v) {
        this.compressAfterAnalysis = v;
        logger.info("compress_after_analysis set to {}", v);
        persistSettings();
    }

    // ── 런타임 설정 영속화 (settings.json) ─────────────────────────

    private File getSettingsFile() {
        return new File(config.getDataDirectory(), SETTINGS_FILE);
    }

    @SuppressWarnings("unchecked")
    private void loadPersistedSettings() {
        File file = getSettingsFile();

        // 1) 파일이 없으면 기본값으로 새로 생성
        if (!file.exists()) {
            logger.info("[Settings] No persisted settings file found — creating with application.properties defaults");
            persistSettings();
            return;
        }

        // 2) 빈 파일(0 bytes) → 기본값으로 재생성
        if (file.length() == 0) {
            logger.warn("[Settings] settings.json is empty (0 bytes) — recreating with defaults");
            persistSettings();
            return;
        }

        // 3) JSON 파싱
        try {
            Map<String, Object> saved = objectMapper.readValue(file, Map.class);

            // 4) null 또는 빈 맵 → 기본값으로 재생성
            if (saved == null || saved.isEmpty()) {
                logger.warn("[Settings] settings.json contains no settings — recreating with defaults");
                persistSettings();
                return;
            }

            // 5) 개별 설정 복원 (타입 안전 처리)
            if (saved.containsKey("keepUnreachableObjects")) {
                Object val = saved.get("keepUnreachableObjects");
                if (val instanceof Boolean) {
                    this.keepUnreachableObjects = (Boolean) val;
                } else {
                    // 문자열 "true"/"false" 등 비정상 타입 대응
                    this.keepUnreachableObjects = Boolean.parseBoolean(String.valueOf(val));
                    logger.warn("[Settings] keepUnreachableObjects had unexpected type '{}', parsed as {}",
                            val.getClass().getSimpleName(), keepUnreachableObjects);
                }
                logger.info("[Settings] Restored keepUnreachableObjects={}", keepUnreachableObjects);
            }

            if (saved.containsKey("compressAfterAnalysis")) {
                Object val = saved.get("compressAfterAnalysis");
                if (val instanceof Boolean) {
                    this.compressAfterAnalysis = (Boolean) val;
                } else {
                    this.compressAfterAnalysis = Boolean.parseBoolean(String.valueOf(val));
                }
                logger.info("[Settings] Restored compressAfterAnalysis={}", compressAfterAnalysis);
            }

            // LLM 설정 복원
            if (saved.containsKey("llmEnabled")) {
                this.llmEnabled = Boolean.parseBoolean(String.valueOf(saved.get("llmEnabled")));
            }
            if (saved.containsKey("llmProvider")) {
                this.llmProvider = String.valueOf(saved.get("llmProvider"));
            }
            if (saved.containsKey("llmApiUrl")) {
                this.llmApiUrl = String.valueOf(saved.get("llmApiUrl"));
            }
            if (saved.containsKey("llmModel")) {
                this.llmModel = String.valueOf(saved.get("llmModel"));
            }
            if (saved.containsKey("llmApiKey")) {
                this.llmApiKey = String.valueOf(saved.get("llmApiKey"));
            }
            if (saved.containsKey("llmMaxInputTokens")) {
                this.llmMaxInputTokens = Integer.parseInt(String.valueOf(saved.get("llmMaxInputTokens")));
            }
            if (saved.containsKey("llmMaxOutputTokens")) {
                this.llmMaxOutputTokens = Integer.parseInt(String.valueOf(saved.get("llmMaxOutputTokens")));
            }
            if (saved.containsKey("llmTimeoutConnectSeconds")) {
                this.llmTimeoutConnectSeconds = Integer.parseInt(String.valueOf(saved.get("llmTimeoutConnectSeconds")));
            }
            if (saved.containsKey("llmTimeoutReadSeconds")) {
                this.llmTimeoutReadSeconds = Integer.parseInt(String.valueOf(saved.get("llmTimeoutReadSeconds")));
            }
            // 환경변수 LLM_API_KEY 우선
            String envKey = System.getenv("LLM_API_KEY");
            if (envKey != null && !envKey.isEmpty()) {
                this.llmApiKey = envKey;
            }
            if (llmEnabled) {
                logger.info("[Settings] LLM enabled: provider={}, model={}", llmProvider, llmModel);
            }

            logger.info("[Settings] Persisted settings loaded from {}", file.getAbsolutePath());

            // application.properties도 동기화 (settings.json 값 반영)
            syncApplicationProperties();
        } catch (Exception e) {
            // 6) 파싱 실패 (깨진 JSON 등) → 백업 후 기본값으로 재생성
            logger.error("[Settings] Failed to parse settings.json: {} — recreating with defaults", e.getMessage());
            File backup = new File(file.getParent(), SETTINGS_FILE + ".corrupted");
            if (file.renameTo(backup)) {
                logger.info("[Settings] Corrupted file backed up to {}", backup.getName());
            }
            persistSettings();
        }
    }

    private void persistSettings() {
        File file = getSettingsFile();
        try {
            // data 디렉토리가 없으면 생성
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (parentDir.mkdirs()) {
                    logger.info("[Settings] Created data directory: {}", parentDir.getAbsolutePath());
                } else {
                    logger.error("[Settings] Failed to create data directory: {}", parentDir.getAbsolutePath());
                    return;
                }
            }

            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("keepUnreachableObjects", keepUnreachableObjects);
            settings.put("compressAfterAnalysis", compressAfterAnalysis);
            // LLM 설정
            settings.put("llmEnabled", llmEnabled);
            settings.put("llmProvider", llmProvider);
            settings.put("llmApiUrl", llmApiUrl);
            settings.put("llmModel", llmModel);
            settings.put("llmApiKey", llmApiKey);
            settings.put("llmMaxInputTokens", llmMaxInputTokens);
            settings.put("llmMaxOutputTokens", llmMaxOutputTokens);
            settings.put("llmTimeoutConnectSeconds", llmTimeoutConnectSeconds);
            settings.put("llmTimeoutReadSeconds", llmTimeoutReadSeconds);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, settings);
            logger.info("[Settings] Persisted settings to {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("[Settings] Failed to persist settings: {}", e.getMessage());
        }

        // application.properties도 동기화
        syncApplicationProperties();
    }

    /**
     * application.properties 파일의 런타임 변경 가능 설정값을 현재 값으로 동기화.
     * 줄 단위 치환으로 주석/포맷을 보존한다.
     */
    private void syncApplicationProperties() {
        File propsFile = findExternalPropertiesFile();
        if (propsFile == null) {
            logger.debug("[Settings] External application.properties not found, skip sync");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(propsFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put("mat.keep.unreachable.objects", String.valueOf(keepUnreachableObjects));
            updates.put("analysis.compress-after-analysis", String.valueOf(compressAfterAnalysis));
            updates.put("llm.enabled", String.valueOf(llmEnabled));
            updates.put("llm.provider", llmProvider != null ? llmProvider : "claude");
            updates.put("llm.api.url", llmApiUrl != null ? llmApiUrl : "");
            updates.put("llm.model", llmModel != null ? llmModel : "");
            updates.put("llm.api.key", llmApiKey != null ? llmApiKey : "");
            updates.put("llm.max-input-tokens", String.valueOf(llmMaxInputTokens));
            updates.put("llm.max-output-tokens", String.valueOf(llmMaxOutputTokens));
            updates.put("llm.timeout.connect-seconds", String.valueOf(llmTimeoutConnectSeconds));
            updates.put("llm.timeout.read-seconds", String.valueOf(llmTimeoutReadSeconds));

            List<String> newLines = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                boolean replaced = false;
                for (Map.Entry<String, String> entry : updates.entrySet()) {
                    if (trimmed.startsWith(entry.getKey() + "=")) {
                        newLines.add(entry.getKey() + "=" + entry.getValue());
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    newLines.add(line);
                }
            }
            Files.write(propsFile.toPath(), newLines, java.nio.charset.StandardCharsets.UTF_8);
            logger.info("[Settings] application.properties 동기화 완료: {}", propsFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("[Settings] application.properties 동기화 실패: {}", e.getMessage());
        }
    }

    private File findExternalPropertiesFile() {
        // 1) JAR과 같은 디렉토리
        try {
            File jarDir = new File(getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParentFile();
            File f = new File(jarDir, "application.properties");
            if (f.exists()) return f;
        } catch (Exception ignored) {}

        // 2) 프로젝트 소스 디렉토리 (개발 환경)
        File srcProps = new File("src/main/resources/application.properties");
        if (srcProps.exists()) return srcProps;

        return null;
    }
    public String  getHeapDumpDirectory()          { return config.getHeapDumpDirectory(); }
    public String  getMatCliPath()                 { return config.getMatCliPath(); }
    public int     getCachedResultCount()          { return memCache.size(); }
    public Collection<HeapAnalysisResult> getAllCachedResults() { return Collections.unmodifiableCollection(memCache.values()); }
    public Set<String> getCacheKeys()               { return Collections.unmodifiableSet(memCache.keySet()); }
    public boolean isMatCliReady()                 { return config.isMatCliReady(); }
    public String  getMatCliStatusMessage()        { return config.getMatCliStatusMessage(); }

    // ── LLM 설정 getter/setter ────────────────────────────────────
    public boolean isLlmEnabled()              { return llmEnabled; }
    public String  getLlmProvider()             { return llmProvider; }
    public String  getLlmApiUrl()               { return llmApiUrl; }
    public String  getLlmModel()                { return llmModel; }
    public String  getLlmApiKey()               { return llmApiKey; }
    public int     getLlmMaxInputTokens()       { return llmMaxInputTokens; }
    public int     getLlmMaxOutputTokens()      { return llmMaxOutputTokens; }
    public int     getLlmTimeoutConnectSeconds() { return llmTimeoutConnectSeconds; }
    public int     getLlmTimeoutReadSeconds()    { return llmTimeoutReadSeconds; }

    public void setLlmEnabled(boolean enabled) {
        this.llmEnabled = enabled;
        persistSettings();
        logger.info("[LLM] enabled={}", enabled);
    }

    public void setLlmConfig(String provider, String apiUrl, String model,
                             int maxInputTokens, int maxOutputTokens) {
        this.llmProvider = provider;
        this.llmApiUrl = apiUrl;
        this.llmModel = model;
        this.llmMaxInputTokens = maxInputTokens;
        this.llmMaxOutputTokens = maxOutputTokens;
        persistSettings();
        logger.info("[LLM] config updated: provider={}, model={}", provider, model);
    }

    public void setLlmApiKey(String apiKey) {
        this.llmApiKey = apiKey;
        persistSettings();
        logger.info("[LLM] API key updated (length={})", apiKey != null ? apiKey.length() : 0);
    }

    public String getLlmApiKeyMasked() {
        if (llmApiKey == null || llmApiKey.length() < 8) return "****";
        return llmApiKey.substring(0, 7) + "..." + llmApiKey.substring(llmApiKey.length() - 4);
    }

    public boolean isLlmApiKeySet() {
        return llmApiKey != null && !llmApiKey.trim().isEmpty();
    }

    public String getDefaultApiUrl(String provider) {
        switch (provider) {
            case "claude":   return "https://api.anthropic.com/v1/messages";
            case "gpt":      return "https://api.openai.com/v1/chat/completions";
            case "genspark": return "https://www.genspark.ai/api/llm_proxy/v1/chat/completions";
            case "custom":   return "";
            default:         return "";
        }
    }

    /** Genspark 허용 모델 목록 */
    public static final List<String> GENSPARK_MODELS = java.util.Arrays.asList(
        "gpt-5", "gpt-5-mini", "gpt-5-nano",
        "gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano",
        "gpt-5-codex", "gpt-5.2-codex", "gpt-5.3-codex",
        "claude-sonnet-4-5", "claude-sonnet-4-6", "claude-sonnet-4-6-1m",
        "claude-haiku-4-5",
        "claude-opus-4-5", "claude-opus-4-6", "claude-opus-4-6-1m",
        "kimi-k2p5", "minimax-m2p5", "minimax-m2p7"
    );

    /**
     * LLM 연결 테스트 — 프로바이더별 분기
     */
    public Map<String, Object> testLlmConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", llmProvider);

        if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "API 키가 설정되지 않았습니다");
            return result;
        }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "API URL이 설정되지 않았습니다");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            java.net.URL url = new java.net.URL(llmApiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            String body;
            if ("claude".equals(llmProvider)) {
                conn.setRequestProperty("x-api-key", llmApiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = "{\"model\":\"" + llmModel + "\",\"max_tokens\":10,"
                     + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
                body = "{\"model\":\"" + llmModel + "\",\"max_tokens\":10,"
                     + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            long latency = System.currentTimeMillis() - start;

            if (code >= 200 && code < 300) {
                // 성공 응답 파싱
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                result.put("success", true);
                result.put("latencyMs", latency);
                result.put("model", llmModel);
                // 응답에서 실제 모델명 추출 시도
                try {
                    Map<String, Object> resp = objectMapper.readValue(sb.toString(), Map.class);
                    if (resp.containsKey("model")) {
                        result.put("model", resp.get("model"));
                    }
                } catch (Exception ignored) {}
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) errSb.append(line);
                    }
                }
                result.put("success", false);
                result.put("error", "HTTP " + code + ": " + errSb.toString());
                result.put("latencyMs", latency);
            }
            conn.disconnect();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("latencyMs", latency);
            logger.warn("[LLM] Connection test failed: {}", e.getMessage());
        }
        return result;
    }

    // ── AI Insight 저장 / 불러오기 ──────────────────────────────────

    /**
     * AI 인사이트 결과를 /opt/heapdumps/data/{baseName}/ai_insight.json 에 영속화
     */
    public void saveAiInsight(String filename, Map<String, Object> insightData) {
        try {
            File dir = resultDirectory(filename);
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, AI_INSIGHT_FILE);
            // analysedAt 타임스탬프 추가
            Map<String, Object> toSave = new LinkedHashMap<>(insightData);
            toSave.put("analysedAt", System.currentTimeMillis());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, toSave);
            logger.info("[AI-Insight] Saved ai_insight.json for '{}' → {}", filename, target.getAbsolutePath());
        } catch (Exception e) {
            logger.error("[AI-Insight] Failed to save ai_insight.json for '{}': {}", filename, e.getMessage());
        }
    }

    /**
     * 저장된 AI 인사이트 결과를 불러옴. 없으면 null 반환.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadAiInsight(String filename) {
        try {
            File target = new File(resultDirectory(filename), AI_INSIGHT_FILE);
            if (!target.exists()) {
                logger.debug("[AI-Insight] No saved ai_insight.json for '{}'", filename);
                return null;
            }
            Map<String, Object> data = objectMapper.readValue(target, Map.class);
            logger.info("[AI-Insight] Loaded ai_insight.json for '{}' (analysedAt={})", filename, data.get("analysedAt"));
            return data;
        } catch (Exception e) {
            logger.warn("[AI-Insight] Failed to load ai_insight.json for '{}': {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * 저장된 AI 인사이트 결과 삭제
     */
    public boolean deleteAiInsight(String filename) {
        try {
            File target = new File(resultDirectory(filename), AI_INSIGHT_FILE);
            if (target.exists() && target.delete()) {
                logger.info("[AI-Insight] Deleted ai_insight.json for '{}'", filename);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.warn("[AI-Insight] Failed to delete ai_insight.json for '{}': {}", filename, e.getMessage());
            return false;
        }
    }

    // ── LLM 분석 호출 ────────────────────────────────────────────────

    /**
     * LLM API를 호출하여 힙 분석 결과를 AI가 해석하게 함 (로깅·오류 분류 강화)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callLlmAnalysis(String prompt) {
        Map<String, Object> result = new LinkedHashMap<>();

        // ── 사전 검증 ─────────────────────────────────────────────
        if (!llmEnabled) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — LLM 비활성화 상태 (Settings에서 AI Analysis를 ON으로 설정하세요)");
            result.put("success", false);
            result.put("errorCode", "LLM_DISABLED");
            result.put("error", "AI 분석 기능이 비활성화 상태입니다. Settings → AI/LLM Configuration에서 활성화하세요.");
            return result;
        }
        if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — API 키 미설정 (provider={})", llmProvider);
            result.put("success", false);
            result.put("errorCode", "NO_API_KEY");
            result.put("error", "API 키가 설정되지 않았습니다. Settings → AI/LLM Configuration에서 API 키를 저장하세요.");
            return result;
        }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — API URL 미설정 (provider={})", llmProvider);
            result.put("success", false);
            result.put("errorCode", "NO_API_URL");
            result.put("error", "API URL이 설정되지 않았습니다. Settings → AI/LLM Configuration에서 API URL을 확인하세요.");
            return result;
        }

        long startTime = System.currentTimeMillis();
        logger.info("[AI-Insight][STEP 1/4] LLM 분석 시작 — provider={}, model={}, url={}, maxOutput={}",
            llmProvider, llmModel, llmApiUrl, llmMaxOutputTokens);

        try {
            // ── STEP 2: 프롬프트 준비 ──────────────────────────────
            int maxChars = llmMaxInputTokens * 4;
            boolean truncated = prompt.length() > maxChars;
            if (truncated) {
                prompt = prompt.substring(0, maxChars) + "\n...(truncated)";
                logger.warn("[AI-Insight][STEP 2/4] 프롬프트가 maxInputTokens({}) 초과 — 잘림 처리", llmMaxInputTokens);
            }
            boolean isReasoningModel = llmModel != null && (
                llmModel.startsWith("gpt-5") || llmModel.startsWith("o1") || llmModel.startsWith("o3")
            );
            int effectiveMaxOutputTokens = isReasoningModel
                ? Math.max(llmMaxOutputTokens, 8000)
                : Math.max(llmMaxOutputTokens, 2000);
            logger.info("[AI-Insight][STEP 2/4] 프롬프트 준비 완료 — 길이={} chars, reasoningModel={}, effectiveMaxTokens={}",
                prompt.length(), isReasoningModel, effectiveMaxOutputTokens);

            // ── STEP 3: HTTP 요청 전송 ─────────────────────────────
            java.net.URL url = new java.net.URL(llmApiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            String systemPrompt = "당신은 Java 힙 덤프 분석 전문가입니다. "
                + "Eclipse MAT 분석 결과를 해석하여 메모리 누수의 근본 원인을 진단하고 "
                + "실행 가능한 조치를 한국어로 제안합니다. "
                + "응답은 반드시 마크다운 없이 순수 JSON 형태로만 반환하세요. "
                + "코드블록(```)을 절대 사용하지 마세요.";

            String body;
            if ("claude".equals(llmProvider)) {
                conn.setRequestProperty("x-api-key", llmApiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = objectMapper.writeValueAsString(Map.of(
                    "model", llmModel,
                    "max_tokens", effectiveMaxOutputTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
                ));
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
                body = objectMapper.writeValueAsString(Map.of(
                    "model", llmModel,
                    "max_tokens", effectiveMaxOutputTokens,
                    "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", prompt)
                    )
                ));
            }

            logger.info("[AI-Insight][STEP 3/4] HTTP POST 전송 중 — timeout={}s/{}s",
                llmTimeoutConnectSeconds, llmTimeoutReadSeconds);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            // ── STEP 4: 응답 처리 ─────────────────────────────────
            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("[AI-Insight][STEP 4/4] HTTP 응답 수신 — status={}, elapsed={}ms", code, elapsed);

            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }

                Map<String, Object> resp = objectMapper.readValue(sb.toString(), Map.class);
                String text = extractLlmText(resp);
                result.put("model", llmModel);
                result.put("latencyMs", elapsed);

                if (text == null || text.trim().isEmpty()) {
                    logger.warn("[AI-Insight] LLM이 빈 content를 반환 — model={}, isReasoning={}, effectiveMaxTokens={}",
                        llmModel, isReasoningModel, effectiveMaxOutputTokens);
                    result.put("success", false);
                    result.put("errorCode", "EMPTY_RESPONSE");
                    result.put("error", "LLM이 빈 응답을 반환했습니다."
                        + (isReasoningModel ? " GPT-5 계열 reasoning 모델은 max_tokens를 8,000 이상으로 설정하거나, claude-sonnet-4-5 모델을 사용해 주세요." : " max_tokens를 늘리거나 다른 모델을 선택하세요."));
                    return result;
                }

                try {
                    String cleaned = text.trim();
                    cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```\\s*$", "").trim();
                    int jsonStart = cleaned.indexOf('{');
                    int jsonEnd   = cleaned.lastIndexOf('}');
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
                    }
                    Map<String, Object> aiData = objectMapper.readValue(cleaned, Map.class);
                    result.put("success", true);
                    result.put("data", aiData);
                    logger.info("[AI-Insight] 분석 완료 — model={}, latency={}ms, severity={}",
                        llmModel, elapsed, aiData.get("severity"));
                } catch (Exception parseErr) {
                    logger.warn("[AI-Insight] JSON 파싱 실패 — textLen={}, parseError={}",
                        text.length(), parseErr.getMessage());
                    result.put("success", true);
                    result.put("errorCode", "JSON_PARSE_WARN");
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("summary", text.length() > 1000 ? text.substring(0, 1000) + "..." : text);
                    fallback.put("rootCause", "AI 응답을 JSON으로 파싱하지 못했습니다. 위 요약에서 원문을 확인하세요.");
                    fallback.put("recommendations", "-");
                    fallback.put("severity", "Unknown");
                    fallback.put("severityDesc", "파싱 오류: " + parseErr.getMessage());
                    result.put("data", fallback);
                }
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) errSb.append(line);
                    }
                }
                String errBody = errSb.toString();
                String errorCode = classifyHttpError(code, errBody);
                String friendlyMsg = buildHttpErrorMessage(code, errBody);
                logger.error("[AI-Insight] HTTP 오류 — status={}, errorCode={}, body={}", code, errorCode, errBody);
                result.put("success", false);
                result.put("errorCode", errorCode);
                result.put("error", friendlyMsg);
                result.put("httpStatus", code);
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Insight] 연결/읽기 타임아웃 — elapsed={}ms, msg={}", elapsed, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "TIMEOUT");
            result.put("error", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초). "
                + "Settings에서 타임아웃을 늘리거나, 더 빠른 모델(claude-sonnet-4-5, gpt-5-mini)을 선택하세요.");
        } catch (java.net.ConnectException e) {
            logger.error("[AI-Insight] 서버 연결 실패 — url={}, msg={}", llmApiUrl, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "CONNECT_FAILED");
            result.put("error", "LLM 서버에 연결할 수 없습니다. API URL(" + llmApiUrl + ")을 확인하세요: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            logger.error("[AI-Insight] 알 수 없는 호스트 — url={}, msg={}", llmApiUrl, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "UNKNOWN_HOST");
            result.put("error", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage() + ". URL(" + llmApiUrl + ")을 확인하세요.");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Insight] 예외 발생 — type={}, msg={}, elapsed={}ms",
                e.getClass().getSimpleName(), e.getMessage(), elapsed, e);
            result.put("success", false);
            result.put("errorCode", "INTERNAL_ERROR");
            result.put("error", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
        }
        return result;
    }

    /** HTTP 오류 코드를 errorCode 문자열로 분류 */
    private String classifyHttpError(int code, String body) {
        if (code == 401 || code == 403) return "AUTH_ERROR";
        if (code == 404) return "NOT_FOUND";
        if (code == 429) return "RATE_LIMIT";
        if (code == 400) return "BAD_REQUEST";
        if (code >= 500) return "SERVER_ERROR";
        return "HTTP_" + code;
    }

    /** HTTP 오류 코드에 따른 사용자 친화적 메시지 생성 */
    private String buildHttpErrorMessage(int code, String body) {
        String base;
        switch (code) {
            case 401: base = "API 키 인증 실패(401). API 키가 올바른지 확인하세요."; break;
            case 403: base = "API 키 권한 없음(403). 해당 모델 접근 권한이 있는지 확인하세요."; break;
            case 404: base = "API 엔드포인트를 찾을 수 없습니다(404). Settings에서 API URL 끝에 /chat/completions 포함 여부를 확인하세요."; break;
            case 429: base = "API 요청 횟수 초과(429 Too Many Requests). 잠시 후 다시 시도하세요."; break;
            case 400: base = "잘못된 요청(400 Bad Request). 모델명이 허용 목록에 있는지 확인하세요."; break;
            case 500: case 502: case 503:
                base = "LLM 서버 내부 오류(" + code + "). 잠시 후 다시 시도하세요."; break;
            default:  base = "HTTP " + code + " 오류가 발생했습니다.";
        }
        if (body != null && !body.isEmpty() && body.length() < 300) {
            base += " 상세: " + body;
        }
        return base;
    }

    @SuppressWarnings("unchecked")
    private String extractLlmText(Map<String, Object> resp) {
        // Claude 응답 형식
        if (resp.containsKey("content")) {
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            if (content != null && !content.isEmpty()) {
                return String.valueOf(content.get(0).get("text"));
            }
        }
        // OpenAI 호환 응답 형식
        if (resp.containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                if (msg != null) return String.valueOf(msg.get("content"));
            }
        }
        return resp.toString();
    }

    // ── MAT JVM 힙 메모리 설정 ───────────────────────────────────

    /**
     * MemoryAnalyzer.ini에서 현재 -Xmx 값을 읽어서 바이트 단위로 반환
     */
    public long getMatHeapSize() {
        return readIniJvmArg("-Xmx");
    }

    /**
     * MemoryAnalyzer.ini에서 현재 -Xmx 문자열 반환 (예: "2048m", "8g")
     */
    public String getMatHeapSizeString() {
        return readIniJvmArgString("-Xmx");
    }

    /**
     * MemoryAnalyzer.ini에서 현재 -Xms 값을 바이트 단위로 반환
     */
    public long getMatInitialHeapSize() {
        return readIniJvmArg("-Xms");
    }

    /**
     * MemoryAnalyzer.ini에서 현재 -Xms 문자열 반환
     */
    public String getMatInitialHeapSizeString() {
        return readIniJvmArgString("-Xms");
    }

    /**
     * MemoryAnalyzer.ini의 -Xmx 값을 변경
     */
    public void setMatHeapSize(String newXmx) throws IOException {
        writeIniJvmArg("-Xmx", newXmx);
    }

    /**
     * MemoryAnalyzer.ini의 -Xms 값을 변경
     */
    public void setMatInitialHeapSize(String newXms) throws IOException {
        writeIniJvmArg("-Xms", newXms);
    }

    private long readIniJvmArg(String prefix) {
        File iniFile = getMatIniFile();
        if (iniFile == null || !iniFile.exists()) return -1;
        try {
            for (String line : Files.readAllLines(iniFile.toPath())) {
                String trimmed = line.trim();
                if (trimmed.startsWith(prefix) && !trimmed.startsWith(prefix + "x") && !trimmed.startsWith(prefix + "s")) {
                    // -Xmx → prefix="-Xmx", 뒤에 값만 추출
                    return parseXmxValue(trimmed.substring(prefix.length()));
                }
            }
        } catch (IOException e) {
            logger.warn("[MAT] Failed to read MemoryAnalyzer.ini: {}", e.getMessage());
        }
        return -1;
    }

    private String readIniJvmArgString(String prefix) {
        File iniFile = getMatIniFile();
        if (iniFile == null || !iniFile.exists()) return null;
        try {
            for (String line : Files.readAllLines(iniFile.toPath())) {
                String trimmed = line.trim();
                if (trimmed.startsWith(prefix) && !trimmed.startsWith(prefix + "x") && !trimmed.startsWith(prefix + "s")) {
                    return trimmed.substring(prefix.length());
                }
            }
        } catch (IOException e) {
            logger.warn("[MAT] Failed to read MemoryAnalyzer.ini: {}", e.getMessage());
        }
        return null;
    }

    private void writeIniJvmArg(String prefix, String newVal) throws IOException {
        File iniFile = getMatIniFile();
        if (iniFile == null || !iniFile.exists()) {
            throw new FileNotFoundException("MemoryAnalyzer.ini not found");
        }
        List<String> lines = Files.readAllLines(iniFile.toPath());
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith(prefix) && !trimmed.startsWith(prefix + "x") && !trimmed.startsWith(prefix + "s")) {
                String oldVal = trimmed;
                lines.set(i, prefix + newVal);
                found = true;
                logger.info("[MAT] Changed: {} → {}{}", oldVal, prefix, newVal);
                break;
            }
        }
        if (!found) {
            lines.add(prefix + newVal);
            logger.info("[MAT] Added: {}{}", prefix, newVal);
        }
        Files.write(iniFile.toPath(), lines);
    }

    private File getMatIniFile() {
        String cliPath = config.getMatCliPath();
        if (cliPath == null) return null;
        File matDir = new File(cliPath).getParentFile();
        return new File(matDir, "MemoryAnalyzer.ini");
    }

    private long parseXmxValue(String val) {
        val = val.trim().toLowerCase();
        try {
            if (val.endsWith("g")) {
                return Long.parseLong(val.substring(0, val.length() - 1)) * 1024L * 1024L * 1024L;
            } else if (val.endsWith("m")) {
                return Long.parseLong(val.substring(0, val.length() - 1)) * 1024L * 1024L;
            } else if (val.endsWith("k")) {
                return Long.parseLong(val.substring(0, val.length() - 1)) * 1024L;
            } else {
                return Long.parseLong(val);
            }
        } catch (NumberFormatException e) {
            return -1;
        }
    }

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
            logger.warn("[Upload] Rejected: invalid extension '{}' for file '{}'. Allowed: .hprof, .bin, .dump (+ .gz)",
                    ext, filename);
            throw new IllegalArgumentException(
                    "'" + ext + "' is not a supported file type. Only .hprof, .bin, .dump (+ .gz) files are allowed.");
        }

        File dumpDir = dumpFilesDirectory();
        Files.createDirectories(dumpDir.toPath());
        Path target = dumpDir.toPath().resolve(filename);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("[Upload] Failed to write file '{}' to dumpfiles: {}", filename, e.getMessage(), e);
            throw e;
        }

        long writtenSize = Files.size(target);
        logger.info("[Upload] Completed: filename={}, writtenSize={}, path={} (dumpfiles)",
                filename, formatBytes(writtenSize), target.toAbsolutePath());
        return filename;
    }

    // ── 업로드 중복 검사 ─────────────────────────────────────────

    public Map<String, String> checkDuplicate(String filename, long fileSize, String partialHash) {
        Map<String, String> result = new LinkedHashMap<>();
        File dir = dumpFilesDirectory();
        File[] files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
        if (files == null) {
            result.put("status", "OK");
            return result;
        }

        boolean nameMatch = false;
        for (File f : files) {
            // 기존 파일의 실제 크기 결정 (gz인 경우 originalSize 사용)
            String fName = f.getName();
            long existingSize;
            boolean isGz = fName.toLowerCase().endsWith(".gz");
            if (isGz) {
                String displayName = fName.substring(0, fName.length() - 3);
                HeapAnalysisResult cached = memCache.get(displayName);
                existingSize = (cached != null && cached.getOriginalFileSize() > 0)
                        ? cached.getOriginalFileSize() : -1;
            } else {
                existingSize = f.length();
            }

            // 이름 일치 확인 (gz 확장자 제거 후 비교)
            String existingDisplayName = isGz ? fName.substring(0, fName.length() - 3) : fName;
            if (existingDisplayName.equals(filename)) {
                nameMatch = true;
            }

            // 크기 일치 시 해시 비교
            if (existingSize == fileSize) {
                try {
                    String existingHash = computePartialHash(f, 65536);
                    if (existingHash.equals(partialHash)) {
                        result.put("status", "DUPLICATE_CONTENT");
                        result.put("existingFilename", existingDisplayName);
                        logger.info("[Upload Check] Duplicate content: '{}' matches '{}'", filename, existingDisplayName);
                        return result;
                    }
                } catch (Exception e) {
                    logger.debug("[Upload Check] Hash computation failed for {}: {}", fName, e.getMessage());
                }
            }
        }

        if (nameMatch) {
            result.put("status", "DUPLICATE_NAME");
            result.put("existingFilename", filename);
            result.put("suggestedName", generateUniqueName(filename, dir));
            logger.info("[Upload Check] Name conflict: '{}', suggested: '{}'", filename, result.get("suggestedName"));
            return result;
        }

        result.put("status", "OK");
        return result;
    }

    private String computePartialHash(File file, int bytes) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = file.getName().toLowerCase().endsWith(".gz")
                ? new GZIPInputStream(new FileInputStream(file))
                : new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int totalRead = 0;
            while (totalRead < bytes) {
                int read = is.read(buf, 0, Math.min(buf.length, bytes - totalRead));
                if (read < 0) break;
                digest.update(buf, 0, read);
                totalRead += read;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String generateUniqueName(String filename, File directory) {
        String base = stripExtension(filename);
        String ext = getExtension(filename);
        int counter = 2;
        String candidate;
        do {
            candidate = base + "_" + counter + "." + ext;
            counter++;
        } while (new File(directory, candidate).exists()
                || new File(directory, candidate + ".gz").exists());
        return candidate;
    }

    private String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }

    public List<HeapDumpFile> listFiles() {
        List<HeapDumpFile> result = new ArrayList<>();

        // dumpfiles 디렉토리에서 파일 목록 조회
        File dir = dumpFilesDirectory();
        File[] files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
        Set<String> existing = new HashSet<>();
        if (files != null) {
            for (File f : files) {
                // .gz 파일은 원본 이름으로 표시
                String displayName = f.getName();
                boolean compressed = displayName.toLowerCase().endsWith(".gz");
                if (compressed) {
                    displayName = displayName.substring(0, displayName.length() - 3);
                }
                if (!existing.contains(displayName)) {
                    HeapDumpFile hdf = new HeapDumpFile();
                    hdf.setName(displayName);
                    hdf.setPath(f.getAbsolutePath());
                    hdf.setSize(f.length());
                    hdf.setLastModified(f.lastModified());
                    if (compressed) {
                        hdf.setCompressed(true);
                        hdf.setCompressedSize(f.length());
                        // memCache에서 원본 크기 조회
                        HeapAnalysisResult cached = memCache.get(displayName);
                        if (cached != null && cached.getOriginalFileSize() > 0) {
                            hdf.setOriginalSize(cached.getOriginalFileSize());
                            hdf.setSize(cached.getOriginalFileSize());
                        } else {
                            hdf.setOriginalSize(f.length());
                        }
                    }
                    result.add(hdf);
                    existing.add(displayName);
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
        File file = new File(config.getDumpFilesDirectory(), filename);
        if (!file.exists()) {
            // .gz in dumpfiles
            File gzFile = new File(config.getDumpFilesDirectory(), filename + ".gz");
            if (gzFile.exists()) return gzFile;
            // fallback to legacy root
            file = new File(config.getHeapDumpDirectory(), filename);
        }
        if (!file.exists()) {
            // .gz in legacy root
            File gzFile = new File(config.getHeapDumpDirectory(), filename + ".gz");
            if (gzFile.exists()) return gzFile;
        }
        // tmp fallback
        if (!file.exists()) {
            File tmpFile = new File(tmpDirectory(), filename);
            if (tmpFile.exists()) return tmpFile;
        }
        if (file.exists() && file.isFile()) return file;
        throw new FileNotFoundException("File not found: " + filename);
    }

    public void deleteFile(String filename) throws IOException {
        String safe = new File(filename).getName();
        File file = new File(config.getDumpFilesDirectory(), safe);
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
        File gzFile = new File(config.getDumpFilesDirectory(), safe + ".gz");

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

        // dumpfiles 디렉토리의 MAT 인덱스 파일 삭제 (예: heapdump.a2s.index, heapdump.threads 등)
        String baseName = stripExtension(safe);
        File parentDir = dumpFilesDirectory();
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

    /**
     * 히스토리 삭제: 분석 결과 디렉토리 + 인덱스 파일 + 메모리 캐시 삭제
     * @param deleteHeapDump true이면 힙덤프 파일도 함께 삭제
     */
    public void deleteHistory(String filename, boolean deleteHeapDump) throws IOException {
        String safe = new File(filename).getName();
        logger.info("[DeleteHistory] Started: filename={}, deleteHeapDump={}", safe, deleteHeapDump);

        if (deleteHeapDump) {
            // 1) 힙덤프 파일 삭제 (존재하면)
            File file = new File(config.getDumpFilesDirectory(), safe);
            if (file.exists() && file.isFile()) {
                long fileSize = file.length();
                if (file.delete()) {
                    logger.info("[DeleteHistory] Heap dump deleted: {}, size={}", safe, formatBytes(fileSize));
                }
            }

            // tmp 파일 삭제
            File tmpFile = new File(tmpDirectory(), safe);
            if (tmpFile.exists()) {
                tmpFile.delete();
            }

            // .gz 압축 파일 삭제
            File gzFile = new File(config.getDumpFilesDirectory(), safe + ".gz");
            if (gzFile.exists()) {
                gzFile.delete();
            }
        }

        // 2) MAT 인덱스 파일 삭제 (baseName.*.index, baseName.threads 등)
        //    힙덤프 파일 자체(.hprof, .bin, .dump 및 .gz)는 제외
        String baseName = stripExtension(safe);
        File parentDir = dumpFilesDirectory();
        File[] relatedFiles = parentDir.listFiles((dir, name) -> {
            if (!name.startsWith(baseName + ".") || name.equals(safe)) return false;
            if (!deleteHeapDump && isValidHeapDumpFile(name)) return false;
            return true;
        });
        if (relatedFiles != null) {
            for (File related : relatedFiles) {
                if (related.isFile()) {
                    related.delete();
                }
            }
        }

        // 3) 분석 결과 디렉토리 삭제 (result.json, mat.log, ZIPs 등)
        File resultDir = resultDirectory(safe);
        if (resultDir.exists() && resultDir.isDirectory()) {
            deleteDirectoryRecursively(resultDir);
            logger.info("[DeleteHistory] Result directory deleted: {}", resultDir.getAbsolutePath());
        }

        // 4) 메모리 캐시 제거
        memCache.remove(safe);

        logger.info("[DeleteHistory] Completed: filename='{}', heapDumpDeleted={}", safe, deleteHeapDump);
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
                logger.warn("Failed to read saved result {}: {}", safe, e.getMessage());
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

    /** 명시적 분석 취소 (API 호출용) */
    public boolean cancelAnalysis(String filename) {
        String safe = new File(filename).getName();
        java.util.concurrent.Future<?> task = activeTasks.remove(safe);
        if (task != null && !task.isDone()) {
            logger.info("[Analysis] Cancel requested via API: {}", safe);
            return task.cancel(true);
        }
        logger.info("[Analysis] Cancel requested but no active task found: {}", safe);
        return false;
    }

    public Future<?> analyzeWithProgress(String filename, SseEmitter emitter) {
        final String safe = new File(filename).getName();
        queueSize.incrementAndGet();
        Future<?> future = executor.submit(() -> {
            long startTime = System.currentTimeMillis();
            File tmpFile = new File(tmpDirectory(), safe);
            boolean analysisSuccess = false;
            boolean semaphoreAcquired = false;
            try {
                // ── 큐 대기: 세마포어를 즉시 획득할 수 없으면 대기 상태를 SSE로 전송 ──
                if (!analysisSemaphore.tryAcquire()) {
                    logger.info("[Analysis] Queued: {} (queue size: {}, running: {})",
                            safe, queueSize.get(), currentAnalysisFilename);
                    // 즉시 첫 QUEUED 상태 전송 (3초 대기 없이)
                    sendProgress(emitter, AnalysisProgress.queued(safe,
                            queueSize.get() - 1, currentAnalysisFilename));
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

                // dumpfiles에서 원본 파일 탐색
                File sourceFile = new File(config.getDumpFilesDirectory(), safe);
                File gzFile = new File(config.getDumpFilesDirectory(), safe + ".gz");

                if (sourceFile.exists() && gzFile.exists()) {
                    long gzSize = gzFile.length();
                    if (gzFile.delete()) {
                        logger.info("[Analysis] 중복 .gz 파일 삭제: {} ({})", gzFile.getName(), formatBytes(gzSize));
                    }
                } else if (!sourceFile.exists() && gzFile.exists()) {
                    sendProgress(emitter, AnalysisProgress.step(safe, 4, "압축 해제 중..."));
                    decompressDumpFile(gzFile, sourceFile);
                    logger.info("[Analysis] Decompressed .gz file for re-analysis: {}", safe);
                } else if (!sourceFile.exists()) {
                    // fallback: 기존 heapdumps 루트 디렉토리 탐색 (마이그레이션 호환)
                    sourceFile = new File(config.getHeapDumpDirectory(), safe);
                    if (!sourceFile.exists()) {
                        sendProgress(emitter, AnalysisProgress.error(safe, "파일을 찾을 수 없습니다: " + safe));
                        emitter.complete();
                        return;
                    }
                    logger.info("[Analysis] File found in legacy root dir: {}", safe);
                }

                // 디스크 여유 공간 체크 후 tmp로 copy
                long freeSpace = tmpDirectory().getUsableSpace();
                long sourceSize = sourceFile.length();
                if (freeSpace < sourceSize * 2) {  // 압축 여유분 포함
                    String msg = String.format("디스크 여유 공간 부족: 필요 %s, 여유 %s",
                            formatBytes(sourceSize * 2), formatBytes(freeSpace));
                    sendProgress(emitter, AnalysisProgress.error(safe, msg));
                    emitter.complete();
                    return;
                }

                sendProgress(emitter, AnalysisProgress.step(safe, 4, "분석용 임시 파일 복사 중..."));
                Files.copy(sourceFile.toPath(), tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("[Analysis] Copied to tmp: {} ({}) → tmp/", safe, formatBytes(sourceSize));

                File dumpFile = tmpFile;  // MAT CLI는 tmp 파일로 실행

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

                // dumpfiles의 원본 파일 참조 (크기 정보 등에 사용)
                File originalFile = new File(config.getDumpFilesDirectory(), safe);
                if (!originalFile.exists()) {
                    originalFile = new File(config.getHeapDumpDirectory(), safe);  // fallback
                }

                HeapAnalysisResult result = buildResult(safe, originalFile, parsed, matLog);
                result.setOriginalFileSize(originalFile.length());
                result.setAnalysisTime(System.currentTimeMillis() - startTime);

                // Heap 데이터가 없으면 분석 실패로 처리
                boolean hasHeapData = result.getTotalHeapSize() > 0 || result.getUsedHeapSize() > 0;
                if (!hasHeapData) {
                    result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.ERROR);
                    result.setErrorMessage("Heap data not available — MAT ZIP 파싱 결과에 힙 데이터가 없습니다.");
                    memCache.put(safe, result);
                    saveResultToDisk(result, resultDir);
                    analysisSuccess = true;
                    sendProgress(emitter, AnalysisProgress.error(safe, "Heap data not available"));
                    logger.warn("[Analysis] No heap data for {}, marked as ERROR", safe);
                } else {
                    result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);
                    memCache.put(safe, result);
                    saveResultToDisk(result, resultDir);
                    analysisSuccess = true;

                    // 분석 완료 후 dumpfiles 원본 gzip 압축
                    if (compressAfterAnalysis) {
                        File dumpOriginal = new File(config.getDumpFilesDirectory(), safe);
                        if (!dumpOriginal.exists()) {
                            dumpOriginal = new File(config.getHeapDumpDirectory(), safe);
                        }
                        compressDumpFile(dumpOriginal);
                    }

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
                        File origFile = new File(config.getDumpFilesDirectory(), safe);
                        if (!origFile.exists()) origFile = new File(config.getHeapDumpDirectory(), safe);
                        File finalFile = origFile;

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
                activeTasks.remove(safe);

                // tmp 파일 항상 정리 (원본은 dumpfiles에 안전하게 보존)
                if (tmpFile.exists()) {
                    if (tmpFile.delete()) {
                        logger.info("[Analysis] Tmp file cleaned up: {}", safe);
                    } else {
                        logger.warn("[Analysis] Failed to clean up tmp file: {}", safe);
                    }
                }
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        });
        activeTasks.put(safe, future);
        return future;
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
        // MAT 출력 리더를 전용 데몬 스레드로 실행 (분석 executor 스레드 고갈 방지)
        Thread readerThread = new Thread(() -> {
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
                    if (pct[0] != prevPct || lineCount[0] % config.getProgressLogUpdateLines() == 0) {
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line, phase[0], null));
                    }
                }
            } catch (IOException e) {
                logger.warn("MAT output read error: {}", e.getMessage());
            }
        }, "mat-output-reader-" + filename);
        readerThread.setDaemon(true);
        readerThread.start();

        int matTimeout = config.getMatTimeoutMinutes();
        boolean finished = process.waitFor(matTimeout, TimeUnit.MINUTES);
        readerThread.join();

        if (!finished) {
            process.destroyForcibly();
            logger.error("[MAT CLI] Process timed out after {} minutes for file: {}", matTimeout, filename);
            throw new RuntimeException("MAT CLI가 " + matTimeout + "분 제한 시간을 초과했습니다. "
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
        c.setOriginalFileSize(r.getOriginalFileSize());
        c.setComponentDetailParsedMap(r.getComponentDetailParsedMap());
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
        r.setComponentDetailParsedMap(parsed.getComponentDetailParsedMap());
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
     * 지정된 reportType에 해당하는 MAT 리포트 ZIP 파일을 찾아 반환합니다.
     * @param reportType "overview" | "top_components" | "suspects"
     */
    public File findReportZip(String filename, String reportType) {
        String safe = new File(filename).getName();
        File resultDir = resultDirectory(safe);
        if (!resultDir.exists()) return null;
        return parser.findReportZip(resultDir.getAbsolutePath(), stripExtension(safe), reportType);
    }

    public boolean hasReportZip(String filename, String reportType) {
        return findReportZip(filename, reportType) != null;
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
        } catch (Exception e) {
            // SSE 전송 실패 = 클라이언트 disconnect (SSE 연결은 복구 불가)
            // 현재 스레드 인터럽트하여 분석 중단 유도
            logger.info("[SSE] Client disconnected ({}), interrupting thread", e.getClass().getSimpleName());
            Thread.currentThread().interrupt();
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

        // .gz 파일 검증
        if (!gzFile.exists() || gzFile.length() == 0) {
            logger.error("[Compress] .gz 파일 검증 실패: 파일 없거나 0바이트. 원본 보존: {}", dumpFile.getName());
            if (gzFile.exists()) gzFile.delete();
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
        // MAT CLI가 tmp에서 실행되므로 tmp + 루트 모두 탐색
        java.util.function.BiPredicate<File, String> zipFilter = (d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".zip") && lower.contains(base.toLowerCase());
        };
        List<File> allZips = new ArrayList<>();
        for (File searchDir : new File[]{ tmpDirectory(), new File(config.getHeapDumpDirectory()) }) {
            File[] found = searchDir.listFiles((d, n) -> zipFilter.test(d, n));
            if (found != null) Collections.addAll(allZips, found);
        }
        if (allZips.isEmpty()) {
            logger.warn("[ZIP Move] No ZIPs found for base='{}'", base);
            return;
        }
        for (File zip : allZips) {
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
        // MAT CLI가 tmp에서 실행되므로 tmp + 루트 모두 탐색
        List<File> allArtifacts = new ArrayList<>();
        for (File searchDir : new File[]{ tmpDirectory(), new File(config.getHeapDumpDirectory()) }) {
            File[] found = searchDir.listFiles((d, n) ->
                    n.startsWith(base + ".") && !n.equals(safe)
                    && (n.endsWith(".index") || n.endsWith(".threads")));
            if (found != null) Collections.addAll(allArtifacts, found);
        }
        if (allArtifacts.isEmpty()) return;
        int moved = 0;
        for (File f : allArtifacts) {
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

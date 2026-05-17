package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.model.entity.AiInsightEntity;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.parser.MatReportParser;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AiInsightRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    // MAT_TIMEOUT_MINUTES → config.getMatTimeoutMinutes()로 이동
    private static final String RESULT_JSON      = "result.json";
    // AI_INSIGHT_FILE 상수는 AiInsightManager 내부로 이동 (Phase 7-5)
    private static final String MAT_LOG_FILE     = "mat.log";
    private static final String TMP_DIR_NAME     = "tmp";

    private final HeapDumpConfig  config;
    private final MatReportParser parser;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final AiInsightRepository aiInsightRepository;
    private final DumpTransferLogRepository transferLogRepository;
    private final TargetServerRepository targetServerRepository;
    private final HeapAnalysisResultCache resultCache;
    private final FileManagementService fileMgmt;
    private final LlmConfigService llmConfig;
    private final RagConfigService ragConfig;
    private final AiInsightManager aiInsight;

    public MatReportParser getParser() { return parser; }
    private final ObjectMapper    objectMapper = new ObjectMapper();

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

    // LLM 런타임 설정은 LlmConfigService 로 이동 (Phase 7-2)
    // RAG 런타임 설정은 RagConfigService 로 이동 (Phase 7-3)
    // DEFAULT_CHAT_SYSTEM_PROMPT 는 LlmConfigService 내부 상수 (Phase 7-2)

    public HeapDumpAnalyzerService(HeapDumpConfig config, MatReportParser parser,
                                   AnalysisHistoryRepository analysisHistoryRepository,
                                   AiInsightRepository aiInsightRepository,
                                   DumpTransferLogRepository transferLogRepository,
                                   TargetServerRepository targetServerRepository,
                                   HeapAnalysisResultCache resultCache,
                                   FileManagementService fileMgmt,
                                   LlmConfigService llmConfig,
                                   RagConfigService ragConfig,
                                   AiInsightManager aiInsight) {
        this.config  = config;
        this.parser  = parser;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.aiInsightRepository = aiInsightRepository;
        this.transferLogRepository = transferLogRepository;
        this.targetServerRepository = targetServerRepository;
        this.resultCache = resultCache;
        this.fileMgmt = fileMgmt;
        this.llmConfig = llmConfig;
        this.ragConfig = ragConfig;
        this.aiInsight = aiInsight;
        this.keepUnreachableObjects = config.isKeepUnreachableObjects();
        this.compressAfterAnalysis = config.isCompressAfterAnalysis();
        // LLM/RAG 초기화는 각각 LlmConfigService/RagConfigService @PostConstruct 에서 수행
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

        // 기존 결과를 DB로 마이그레이션 (한 번만 실행)
        migrateExistingResultsToDb();

        // 기존 history 레코드 중 서버 정보가 누락된 항목 보정
        fixMissingServerInfoInHistory();
    }

    private void migrateExistingResultsToDb() {
        int migrated = 0;
        for (Map.Entry<String, HeapAnalysisResult> entry : resultCache.entries()) {
            String filename = entry.getKey();
            if (!analysisHistoryRepository.existsByFilename(filename)) {
                HeapAnalysisResult result = entry.getValue();
                saveAnalysisToDb(result);
                migrated++;
            }
        }
        if (migrated > 0) {
            logger.info("[DB Migration] {} existing results migrated to database", migrated);
        }
        // AI 인사이트 파일 → DB 마이그레이션
        migrateAiInsightsToDb();
    }

    private void fixMissingServerInfoInHistory() {
        try {
            List<AnalysisHistoryEntity> allHistory = analysisHistoryRepository.findAll();
            int fixed = 0;
            for (AnalysisHistoryEntity entity : allHistory) {
                if (entity.getServerName() != null && !entity.getServerName().isEmpty()) continue;
                // 전송 로그에서 서버 정보 조회
                List<DumpTransferLog> logs = transferLogRepository
                        .findByFilenameAndTransferStatusOrderByCompletedAtDesc(entity.getFilename(), "SUCCESS");
                if (!logs.isEmpty()) {
                    DumpTransferLog log = logs.get(0);
                    entity.setServerId(log.getServerId());
                    Optional<TargetServer> serverOpt = targetServerRepository.findById(log.getServerId());
                    if (serverOpt.isPresent()) {
                        entity.setServerName(serverOpt.get().getName());
                        analysisHistoryRepository.save(entity);
                        fixed++;
                    }
                }
            }
            if (fixed > 0) {
                logger.info("[DB Fix] Updated server info for {} history records from transfer logs", fixed);
            }
        } catch (Exception e) {
            logger.warn("[DB Fix] Failed to fix missing server info: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void migrateAiInsightsToDb() {
        aiInsight.migrateAiInsightsToDb();
    }

    /**
     * 개별 결과 디렉토리에서 result.json을 로드하여 resultCache에 적재.
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
            resultCache.put(r.getFilename(), r);
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
        return fileMgmt.tmpDirectory();
    }

    private File dumpFilesDirectory() {
        return fileMgmt.dumpFilesDirectory();
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
                resultCache.size());
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

            // LLM/RAG 설정 복원 — 각 ConfigService 에 위임
            llmConfig.applyFromSettings(saved);
            ragConfig.applyFromSettings(saved);
            if (ragConfig.isRagEnabled()) {
                logger.info("[Settings] RAG enabled: url={}, index={}, mode={}",
                        ragConfig.getRagElasticsearchUrl(), ragConfig.getRagIndex(), ragConfig.getRagSearchMode());
            }
            // 환경변수 LLM_API_KEY 우선 + 로깅은 LlmConfigService 에서 처리
            llmConfig.applyEnvOverride();
            if (llmConfig.isLlmEnabled()) {
                logger.info("[Settings] LLM enabled: provider={}, model={}",
                        llmConfig.getLlmProvider(), llmConfig.getLlmModel());
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
            // LLM/RAG 설정 — 각 ConfigService 에 위임
            llmConfig.collectSettings(settings);
            ragConfig.collectSettings(settings);
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
            // LLM/RAG 설정 — 각 ConfigService 에 위임
            llmConfig.collectApplicationProperties(updates);
            ragConfig.collectApplicationProperties(updates);
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
    public File findExternalPropertiesFilePublic() { return findExternalPropertiesFile(); }
    public String  getHeapDumpDirectory()          { return config.getHeapDumpDirectory(); }
    public String  getMatCliPath()                 { return config.getMatCliPath(); }
    public int     getCachedResultCount()          { return resultCache.size(); }
    public Collection<HeapAnalysisResult> getAllCachedResults() { return resultCache.values(); }
    public Set<String> getCacheKeys()               { return resultCache.keys(); }
    public boolean isMatCliReady()                 { return config.isMatCliReady(); }
    public String  getMatCliStatusMessage()        { return config.getMatCliStatusMessage(); }

    // ── LLM 설정 getter/setter — LlmConfigService 위임 ─────────────
    public boolean isLlmEnabled()              { return llmConfig.isLlmEnabled(); }
    public String  getLlmProvider()             { return llmConfig.getLlmProvider(); }
    public String  getLlmApiUrl()               { return llmConfig.getLlmApiUrl(); }
    public String  getLlmModel()                { return llmConfig.getLlmModel(); }
    public String  getLlmApiKey()               { return llmConfig.getLlmApiKey(); }
    public int     getLlmMaxInputTokens()       { return llmConfig.getLlmMaxInputTokens(); }
    public int     getLlmMaxOutputTokens()      { return llmConfig.getLlmMaxOutputTokens(); }
    public int     getLlmTimeoutConnectSeconds() { return llmConfig.getLlmTimeoutConnectSeconds(); }
    public int     getLlmTimeoutReadSeconds()    { return llmConfig.getLlmTimeoutReadSeconds(); }

    public void setLlmEnabled(boolean enabled) {
        llmConfig.setLlmEnabled(enabled);
        persistSettings();
    }

    public void setLlmConfig(String provider, String apiUrl, String model,
                             int maxInputTokens, int maxOutputTokens) {
        llmConfig.setLlmConfig(provider, apiUrl, model, maxInputTokens, maxOutputTokens);
        persistSettings();
    }

    public void setLlmApiKey(String apiKey) {
        llmConfig.setLlmApiKey(apiKey);
        persistSettings();
    }

    public String getLlmApiKeyMasked() { return llmConfig.getLlmApiKeyMasked(); }
    public boolean isLlmApiKeySet()    { return llmConfig.isLlmApiKeySet(); }

    public String getLlmChatSystemPrompt() { return llmConfig.getLlmChatSystemPrompt(); }

    public void setLlmChatSystemPrompt(String prompt) {
        llmConfig.setLlmChatSystemPrompt(prompt);
        persistSettings();
    }

    public boolean isLlmChatRestoreIncludeHistory() { return llmConfig.isLlmChatRestoreIncludeHistory(); }

    public void setLlmChatRestoreIncludeHistory(boolean v) {
        llmConfig.setLlmChatRestoreIncludeHistory(v);
        persistSettings();
    }

    public boolean isLlmSslVerify() { return llmConfig.isLlmSslVerify(); }

    public void setLlmSslVerify(boolean sslVerify) {
        llmConfig.setLlmSslVerify(sslVerify);
        persistSettings();
    }

    // ── RAG 설정 facade — RagConfigService 위임 (Phase 7-3) ────────
    public boolean isRagEnabled()           { return ragConfig.isRagEnabled(); }
    public String  getRagElasticsearchUrl() { return ragConfig.getRagElasticsearchUrl(); }
    public String  getRagAuthType()         { return ragConfig.getRagAuthType(); }
    public String  getRagUsername()         { return ragConfig.getRagUsername(); }
    public String  getRagPassword()         { return ragConfig.getRagPassword(); }
    public String  getRagApiKey()           { return ragConfig.getRagApiKey(); }
    public String  getRagIndex()            { return ragConfig.getRagIndex(); }
    public boolean isRagSslVerify()         { return ragConfig.isRagSslVerify(); }
    public String  getRagSearchMode()       { return ragConfig.getRagSearchMode(); }
    public String  getRagTextField()        { return ragConfig.getRagTextField(); }
    public int     getRagTopK()             { return ragConfig.getRagTopK(); }
    public double  getRagMinScore()         { return ragConfig.getRagMinScore(); }
    public int     getRagTimeoutSeconds()   { return ragConfig.getRagTimeoutSeconds(); }
    public boolean isRagChunkingEnabled()         { return ragConfig.isRagChunkingEnabled(); }
    public String  getRagChunkingStrategy()       { return ragConfig.getRagChunkingStrategy(); }
    public int     getRagChunkingSize()           { return ragConfig.getRagChunkingSize(); }
    public int     getRagChunkingOverlap()        { return ragConfig.getRagChunkingOverlap(); }
    public int     getRagChunkingMaxChunksPerDoc(){ return ragConfig.getRagChunkingMaxChunksPerDoc(); }
    public int     getRagChunkingMaxTotalChars()  { return ragConfig.getRagChunkingMaxTotalChars(); }
    public String  getRagSemanticQueryType()    { return ragConfig.getRagSemanticQueryType(); }
    public String  getRagSemanticModelId()      { return ragConfig.getRagSemanticModelId(); }
    public String  getRagSemanticTokensField()  { return ragConfig.getRagSemanticTokensField(); }
    public String  getRagSemanticField()        { return ragConfig.getRagSemanticField(); }
    public String  getRagEmbeddingProvider()    { return ragConfig.getRagEmbeddingProvider(); }
    public String  getRagEmbeddingApiUrl()      { return ragConfig.getRagEmbeddingApiUrl(); }
    public String  getRagEmbeddingApiKey()      { return ragConfig.getRagEmbeddingApiKey(); }
    public String  getRagEmbeddingModel()       { return ragConfig.getRagEmbeddingModel(); }
    public int     getRagEmbeddingDimension()   { return ragConfig.getRagEmbeddingDimension(); }
    public int     getRagEmbeddingTimeoutSeconds() { return ragConfig.getRagEmbeddingTimeoutSeconds(); }
    public String  getRagKnnVectorField()       { return ragConfig.getRagKnnVectorField(); }
    public int     getRagKnnNumCandidates()     { return ragConfig.getRagKnnNumCandidates(); }

    public boolean isRagPasswordSet()         { return ragConfig.isRagPasswordSet(); }
    public boolean isRagApiKeySet()           { return ragConfig.isRagApiKeySet(); }
    public boolean isRagEmbeddingApiKeySet()  { return ragConfig.isRagEmbeddingApiKeySet(); }
    public String  getRagEmbeddingApiKeyMasked() { return ragConfig.getRagEmbeddingApiKeyMasked(); }
    public String  getRagPasswordMasked()      { return ragConfig.getRagPasswordMasked(); }
    public String  getRagApiKeyMasked()        { return ragConfig.getRagApiKeyMasked(); }

    public void setRagEnabled(boolean enabled) {
        ragConfig.setRagEnabled(enabled);
        persistSettings();
    }

    public void setRagConfig(String url, String authType, String username,
                             String password, String apiKey, String index, boolean sslVerify,
                             String searchMode, String textField, int topK, double minScore,
                             int timeoutSeconds) {
        ragConfig.setRagConfig(url, authType, username, password, apiKey, index, sslVerify,
                searchMode, textField, topK, minScore, timeoutSeconds);
        persistSettings();
    }

    public void setRagSemanticConfig(String queryType, String modelId, String tokensField, String semanticField) {
        ragConfig.setRagSemanticConfig(queryType, modelId, tokensField, semanticField);
        persistSettings();
    }

    public void setRagEmbeddingConfig(String provider, String apiUrl, String apiKey, String model,
                                      int dimension, int timeoutSeconds, String vectorField, int numCandidates) {
        ragConfig.setRagEmbeddingConfig(provider, apiUrl, apiKey, model, dimension, timeoutSeconds, vectorField, numCandidates);
        persistSettings();
    }

    public void setRagChunkingConfig(boolean enabled, String strategy, int size, int overlap,
                                     int maxChunksPerDoc, int maxTotalChars) {
        ragConfig.setRagChunkingConfig(enabled, strategy, size, overlap, maxChunksPerDoc, maxTotalChars);
        persistSettings();
    }

    // ── AI Insight facade — AiInsightManager 위임 (Phase 7-5) ────

    public void saveAiInsight(String filename, Map<String, Object> insightData) {
        aiInsight.saveAiInsight(filename, insightData);
    }

    public Map<String, Object> loadAiInsight(String filename) {
        return aiInsight.loadAiInsight(filename);
    }

    public boolean deleteAiInsight(String filename) {
        return aiInsight.deleteAiInsight(filename);
    }

    // ── LLM 호출 facade — LlmConfigService 위임 (Phase 7-2) ───────

    public String getDefaultApiUrl(String provider) {
        return llmConfig.getDefaultApiUrl(provider);
    }

    /** @deprecated LlmConfigService.GENSPARK_MODELS 직접 참조 권장 */
    public static final List<String> GENSPARK_MODELS = LlmConfigService.GENSPARK_MODELS;

    public Map<String, Object> testLlmConnection() {
        return llmConfig.testLlmConnection();
    }

    public Map<String, Object> callLlmAnalysis(String prompt) {
        return llmConfig.callLlmAnalysis(prompt);
    }

    public Map<String, Object> callLlmChat(List<Map<String, String>> messages, String systemPrompt) {
        return llmConfig.callLlmChat(messages, systemPrompt);
    }

    public void callLlmChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        llmConfig.callLlmChatStream(messages, systemPrompt, onChunk, onDone, onError);
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
        return fileMgmt.uploadFile(file);
    }

    // ── 업로드 중복 검사 ─────────────────────────────────────────

    public Map<String, String> checkDuplicate(String filename, long fileSize, String partialHash) {
        return fileMgmt.checkDuplicate(filename, fileSize, partialHash);
    }

    private String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }

    public List<HeapDumpFile> listFiles() {
        return fileMgmt.listFiles();
    }

    private void cleanupDuplicateGzFiles(File[] files) {
        fileMgmt.cleanupDuplicateGzFiles(files);
    }

    public File getFile(String filename) throws IOException {
        return fileMgmt.getFile(filename);
    }

    public void deleteFile(String filename) throws IOException {
        fileMgmt.deleteFile(filename);
    }

    /**
     * 히스토리 삭제: 분석 결과 디렉토리 + 인덱스 파일 + 메모리 캐시 삭제
     * @param deleteHeapDump true이면 힙덤프 파일도 함께 삭제
     */
    @Transactional
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
        resultCache.remove(safe);

        // 5) DB 레코드 삭제 (analysis_history + ai_insights)
        try {
            analysisHistoryRepository.deleteByFilename(safe);
            logger.info("[DeleteHistory] DB analysis_history record deleted: {}", safe);
        } catch (Exception e) {
            logger.warn("[DeleteHistory] Failed to delete DB analysis_history for '{}': {}", safe, e.getMessage());
        }
        try {
            aiInsightRepository.deleteByFilename(safe);
            logger.info("[DeleteHistory] DB ai_insights record deleted: {}", safe);
        } catch (Exception e) {
            logger.warn("[DeleteHistory] Failed to delete DB ai_insights for '{}': {}", safe, e.getMessage());
        }

        logger.info("[DeleteHistory] Completed: filename='{}', heapDumpDeleted={}", safe, deleteHeapDump);
    }

    // ── 캐시 조회 / 삭제 ─────────────────────────────────────────

    public HeapAnalysisResult getCachedResult(String filename) {
        String safe = new File(filename).getName();
        HeapAnalysisResult cached = resultCache.get(safe);
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
                    resultCache.put(safe, r);
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
        resultCache.remove(safe);
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
                    resultCache.put(safe, result);
                    saveResultToDisk(result, resultDir);
                    saveAnalysisToDb(result);
                    analysisSuccess = true;
                    sendProgress(emitter, AnalysisProgress.error(safe, "Heap data not available"));
                    logger.warn("[Analysis] No heap data for {}, marked as ERROR", safe);
                } else {
                    result.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);
                    resultCache.put(safe, result);
                    saveResultToDisk(result, resultDir);
                    saveAnalysisToDb(result);
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

                    // 분석 실패 결과를 resultCache + 디스크에 저장 (파일 삭제 전까지 유지)
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
                        resultCache.put(safe, errorResult);
                        saveResultToDisk(errorResult, errorResultDir);
                        saveAnalysisToDb(errorResult);
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
                    // 단방향 진행만 허용 — 이미 더 뒤 단계라면 같은/이전 단계 Subtask 라인이 재등장해도 무시.
                    // (MAT 출력은 suspects 진행 중에도 "Subtask: Top Component..." 같은 라인을 다시 내보낼 수 있어
                    //  단순 phase 불일치 가드로는 pct 가 역행할 수 있음.)
                    int curRank = phaseRank(phase[0]);
                    if (line.startsWith("Subtask: System Overview") && phaseRank("overview") > curRank) {
                        phase[0] = "overview";
                        pct[0] = Math.max(pct[0], 40);
                        logger.info("[MAT CLI] Report phase: overview (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "overview", "Overview 리포트 생성 중..."));
                        continue;
                    } else if (line.startsWith("Subtask: Top Component") && phaseRank("top_components") > curRank) {
                        phase[0] = "top_components";
                        pct[0] = Math.max(pct[0], 55);
                        logger.info("[MAT CLI] Report phase: top_components (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "top_components", "Top Components 리포트 생성 중..."));
                        continue;
                    } else if (line.startsWith("Subtask: Leak Suspects") && phaseRank("suspects") > curRank) {
                        phase[0] = "suspects";
                        pct[0] = Math.max(pct[0], 68);
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

    // ── DB 저장 ───────────────────────────────────────────────────

    public void saveAnalysisToDb(HeapAnalysisResult result) {
        // 전송 로그에서 서버 정보를 자동 조회
        Long serverId = null;
        String serverName = null;
        try {
            List<DumpTransferLog> logs = transferLogRepository
                    .findByFilenameAndTransferStatusOrderByCompletedAtDesc(result.getFilename(), "SUCCESS");
            if (!logs.isEmpty()) {
                DumpTransferLog log = logs.get(0);
                serverId = log.getServerId();
                Optional<TargetServer> serverOpt = targetServerRepository.findById(serverId);
                if (serverOpt.isPresent()) {
                    serverName = serverOpt.get().getName();
                }
            }
        } catch (Exception e) {
            logger.debug("[DB] Failed to lookup transfer log for {}: {}", result.getFilename(), e.getMessage());
        }
        saveAnalysisToDb(result, serverId, serverName, null);
    }

    public void saveAnalysisToDb(HeapAnalysisResult result, Long serverId, String serverName, String uploadedBy) {
        try {
            AnalysisHistoryEntity entity = analysisHistoryRepository.findByFilename(result.getFilename())
                    .orElse(new AnalysisHistoryEntity());
            entity.setFilename(result.getFilename());
            entity.setStatus(result.getAnalysisStatus() != null ? result.getAnalysisStatus().name() : "ERROR");
            entity.setFileSize(result.getFileSize());
            entity.setOriginalFileSize(result.getOriginalFileSize());
            entity.setTotalHeapSize(result.getTotalHeapSize());
            entity.setUsedHeapSize(result.getUsedHeapSize());
            entity.setHeapUsagePercent(result.getHeapUsagePercent());
            entity.setSuspectCount(result.getLeakSuspects() != null ? result.getLeakSuspects().size() : 0);
            entity.setTotalClasses(result.getTotalClasses());
            entity.setTotalObjects(result.getTotalObjects());
            entity.setAnalysisTimeMs(result.getAnalysisTime());
            entity.setCompressed(false);
            entity.setFileDeleted(false);
            entity.setErrorMessage(result.getErrorMessage());
            if (serverId != null) entity.setServerId(serverId);
            if (serverName != null) entity.setServerName(serverName);
            if (uploadedBy != null) entity.setUploadedBy(uploadedBy);
            entity.setAnalyzedAt(java.time.LocalDateTime.now());
            analysisHistoryRepository.save(entity);
            logger.info("[DB] Analysis history saved for: {}", result.getFilename());
        } catch (Exception e) {
            logger.warn("[DB] Failed to save analysis history for {}: {}", result.getFilename(), e.getMessage());
        }
    }

    public AnalysisHistoryRepository getAnalysisHistoryRepository() {
        return analysisHistoryRepository;
    }

    public AiInsightRepository getAiInsightRepository() {
        return aiInsightRepository;
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
        return fileMgmt.resultDirectory(filename);
    }
    private File resultJsonFile(String filename) {
        return fileMgmt.resultJsonFile(filename);
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

    /** MAT 리포트 단계의 순서. 진행률 역행 방지용 — 같은 또는 더 뒤 단계 전환 시도는 무시한다. */
    private static int phaseRank(String phase) {
        if (phase == null) return -1;
        switch (phase) {
            case "init":           return 0;
            case "overview":       return 1;
            case "top_components": return 2;
            case "suspects":       return 3;
            default:               return -1;
        }
    }

    private void compressDumpFile(File dumpFile) {
        fileMgmt.compressDumpFile(dumpFile);
    }

    private void decompressDumpFile(File gzFile, File destFile) throws IOException {
        fileMgmt.decompressDumpFile(gzFile, destFile);
    }

    private boolean isValidHeapDumpFile(String name) {
        return fileMgmt.isValidHeapDumpFile(name);
    }

    private String stripExtension(String name) {
        return fileMgmt.stripExtension(name);
    }

    /** 컨트롤러 등 외부에서 파일명 확장자 제거에 사용 */
    public String stripExtensionPublic(String name) {
        return fileMgmt.stripExtension(name);
    }

    private String getExtension(String name) {
        return fileMgmt.getExtension(name);
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

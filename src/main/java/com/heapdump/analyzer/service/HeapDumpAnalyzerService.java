package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.model.entity.AiInsightEntity;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.parser.MatReportParser;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AiChatMessageRepository;
import com.heapdump.analyzer.repository.AiChatSessionRepository;
import com.heapdump.analyzer.repository.AiInsightRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final AiChatSessionRepository aiChatSessionRepository;
    private final AiChatMessageRepository aiChatMessageRepository;
    private final DumpTransferLogRepository transferLogRepository;
    private final TargetServerRepository targetServerRepository;
    private final HeapAnalysisResultCache resultCache;
    private final FileManagementService fileMgmt;
    private final LlmConfigService llmConfig;
    private final RagConfigService ragConfig;
    private final RemoteDumpService remoteDumpService;
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

    // 클라이언트 disconnect 로 전송 불가가 된 SSE emitter 집합.
    // 여기 담긴 emitter 로는 sendProgress 가 전송을 건너뛰되(로그 스팸 방지) 분석은 백그라운드로 계속한다.
    private final java.util.Set<SseEmitter> deadEmitters =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Dominator Refs 사전계산 전용 직렬 executor (분석 풀과 분리, 글로벌 동시 1개)
    private final ExecutorService domRefPrecomputeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "dom-ref-precompute");
                th.setDaemon(true);
                return th;
            });
    // 사이드카(dominator-refs.json) 파싱 결과 캐시: filename → (address → {incoming,outgoing})
    private final ConcurrentHashMap<String, Map<String, Map<String, List<com.heapdump.analyzer.model.DominatorRefEntry>>>> domRefSidecarCache
            = new ConcurrentHashMap<>();

    // ── MAT 프로세스 동시성 게이트 (메모리 기반 동적) ────────────────────────────
    // MAT 자식 프로세스(MemoryAnalyzer)는 각 -Xmx(MemoryAnalyzer.ini) 만큼 힙을 점유한다.
    // 호스트 RAM / 앱 -Xmx / MAT -Xmx 로 동시 실행 가능 개수를 산정해 모든 MAT spawn(분석·precompute·lazy)
    // 을 게이트한다. 저용량(예: 4GB)=1 → 직렬화(양보). 고용량(예: 32GB)=N → 동시 실행 허용.
    // MAT -Xmx 를 설정 UI 로 바꾸면 setMatHeapSize → recompute 로 즉시 재산정(재기동 불필요).
    private final ResizableSemaphore matSlots = new ResizableSemaphore(1);
    private volatile int matMaxConcurrent = 1;

    /** 공정(FIFO) + 런타임 permit 변경 가능한 Semaphore. */
    static final class ResizableSemaphore extends java.util.concurrent.Semaphore {
        private int maxPermits;
        ResizableSemaphore(int permits) { super(Math.max(1, permits), true); this.maxPermits = Math.max(1, permits); }
        synchronized void setMaxPermits(int newMax) {
            if (newMax < 1) newMax = 1;
            int delta = newMax - maxPermits;
            if (delta > 0) release(delta);
            else if (delta < 0) reducePermits(-delta);
            maxPermits = newMax;
        }
        int getMaxPermits() { return maxPermits; }
    }

    // ── Observer 모드: 진행 상황 스냅샷 캐시 (파일명 → 최신 AnalysisProgress) ──
    private final ConcurrentHashMap<String, AnalysisProgress> lastProgressCache = new ConcurrentHashMap<>();
    // ── Observer 모드: MAT 로그 캐시 (파일명 → 최근 500줄, ConcurrentLinkedDeque는 스레드 안전) ──
    private final ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<String>> logCache
            = new ConcurrentHashMap<>();
    private static final int LOG_CACHE_MAX = 500;

    // 런타임 설정 (application.properties 초기값 → settings.json으로 영속화)
    private static final String SETTINGS_FILE = "settings.json";
    private volatile boolean keepUnreachableObjects;
    private volatile boolean compressAfterAnalysis;
    private volatile boolean dominatorRefsEnabled;

    // 업로드 최대 파일 크기 (bytes). 기본 5 GB. 최대 50 GB.
    public  static final long MAX_UPLOAD_LIMIT_BYTES = 50L * 1024 * 1024 * 1024;
    private static final long DEFAULT_UPLOAD_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    private volatile long maxUploadSizeBytes = DEFAULT_UPLOAD_SIZE_BYTES;

    // 확장자 화이트리스트(.hprof/.bin/.dump + .gz) 우회 — 기본 false (검증 유지)
    private volatile boolean allowAllExtensions = false;

    // 세션 타임아웃 (시간 단위, 1~6h). 기본 1h (60m → application.properties 초기값)
    private volatile int sessionTimeoutHours = 1;

    // 대시보드 Detections 표시 기간 (일 단위). 기본 14일
    private volatile int dashboardDetectDays = 14;

    // LLM 런타임 설정은 LlmConfigService 로 이동 (Phase 7-2)
    // RAG 런타임 설정은 RagConfigService 로 이동 (Phase 7-3)
    // DEFAULT_CHAT_SYSTEM_PROMPT 는 LlmConfigService 내부 상수 (Phase 7-2)

    public HeapDumpAnalyzerService(HeapDumpConfig config, MatReportParser parser,
                                   AnalysisHistoryRepository analysisHistoryRepository,
                                   AiInsightRepository aiInsightRepository,
                                   AiChatSessionRepository aiChatSessionRepository,
                                   AiChatMessageRepository aiChatMessageRepository,
                                   DumpTransferLogRepository transferLogRepository,
                                   TargetServerRepository targetServerRepository,
                                   HeapAnalysisResultCache resultCache,
                                   FileManagementService fileMgmt,
                                   LlmConfigService llmConfig,
                                   RagConfigService ragConfig,
                                   RemoteDumpService remoteDumpService,
                                   AiInsightManager aiInsight,
                                   MultipartProperties multipartProperties) {
        this.config  = config;
        this.parser  = parser;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.aiInsightRepository = aiInsightRepository;
        this.aiChatSessionRepository = aiChatSessionRepository;
        this.aiChatMessageRepository = aiChatMessageRepository;
        this.transferLogRepository = transferLogRepository;
        this.targetServerRepository = targetServerRepository;
        this.resultCache = resultCache;
        this.fileMgmt = fileMgmt;
        this.llmConfig = llmConfig;
        this.ragConfig = ragConfig;
        this.remoteDumpService = remoteDumpService;
        this.aiInsight = aiInsight;
        this.keepUnreachableObjects = config.isKeepUnreachableObjects();
        this.compressAfterAnalysis = config.isCompressAfterAnalysis();
        this.dominatorRefsEnabled = config.isDominatorRefsEnabled();
        // application.properties 의 spring.servlet.multipart.max-file-size 초기값 채택
        // (settings.json 로 덮어쓸 수 있음)
        try {
            if (multipartProperties != null && multipartProperties.getMaxFileSize() != null) {
                long initBytes = multipartProperties.getMaxFileSize().toBytes();
                if (initBytes > 0 && initBytes <= MAX_UPLOAD_LIMIT_BYTES) {
                    this.maxUploadSizeBytes = initBytes;
                }
            }
        } catch (Exception ignored) { /* DEFAULT_UPLOAD_SIZE_BYTES 유지 */ }
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

        // MAT 동시 실행 한도 산정 (호스트 RAM / 앱·MAT -Xmx 기반)
        recomputeMatConcurrency();

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

        // dump_creation_time이 DB에 없는 기존 레코드를 캐시에서 백필
        backfillDumpCreationTimeToDb();

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

    private void backfillDumpCreationTimeToDb() {
        int filled = 0;
        try {
            List<AnalysisHistoryEntity> allHistory = analysisHistoryRepository.findAll();
            for (AnalysisHistoryEntity entity : allHistory) {
                if (entity.getDumpCreationTime() != null) continue;
                if (!"SUCCESS".equals(entity.getStatus())) continue;
                HeapAnalysisResult cached = resultCache.get(entity.getFilename());
                if (cached == null || cached.getDumpCreationTime() == null) continue;
                entity.setDumpCreationTime(cached.getDumpCreationTime());
                analysisHistoryRepository.save(entity);
                filled++;
            }
        } catch (Exception e) {
            logger.warn("[DB Backfill] dump_creation_time 백필 중 오류: {}", e.getMessage());
        }
        if (filled > 0) {
            logger.info("[DB Backfill] dump_creation_time {} 건 백필 완료", filled);
        }
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

        // suspects stacktrace 페이지 경로 재파싱 (기존 result.json에 없는 경우)
        reparseSuspectsStacktrace(r);

        // keyword 기능 도입 이전 result.json 은 keywords 가 null → ZIP 에서 재추출 + 잘린 description 복구
        reparseSuspectsMeta(r);

        // classLoaderCount/gcRootCount가 0이거나 dumpCreationTime이 없으면 Overview ZIP에서 재파싱
        if (r.getClassLoaderCount() == 0 && r.getGcRootCount() == 0 || r.getDumpCreationTime() == null) {
            reparseOverviewMeta(r);
        }

        // dominatorTreeEntries가 없거나, 구버전 파서가 남긴 배열 내용 미리보기(점 나열)가
        // className에 섞여 있으면 Query ZIP에서 재파싱 (현재 파서가 점 아티팩트 제거)
        if (r.getDominatorTreeEntries() == null || r.getDominatorTreeEntries().isEmpty()
                || hasDominatorNamePreviewArtifact(r.getDominatorTreeEntries())) {
            reparseDominatorTree(r);
        }

        // .threads 파일 로드
        loadThreadStacksText(r);
    }

    /** 구버전 파서가 className에 남긴 MAT 배열 내용 미리보기(연속 점) 잔존 여부 검사 */
    private boolean hasDominatorNamePreviewArtifact(
            java.util.List<com.heapdump.analyzer.model.DominatorTreeEntry> entries) {
        for (com.heapdump.analyzer.model.DominatorTreeEntry e : entries) {
            String name = e.getClassName();
            if (name != null && name.contains("..")) return true;
        }
        return false;
    }

    private void reparseDominatorTree(HeapAnalysisResult r) {
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            parser.reparseDominatorTree(resultDir.getAbsolutePath(), baseName, tmp);
            if (!tmp.getDominatorTreeEntries().isEmpty()) {
                r.setDominatorTreeEntries(tmp.getDominatorTreeEntries());
                logger.info("Re-extracted {} dominator tree entries for {}",
                        tmp.getDominatorTreeEntries().size(), r.getFilename());
            }
        } catch (Exception e) {
            logger.debug("Could not re-extract dominator tree for {}: {}", r.getFilename(), e.getMessage());
        }
    }

    private void reparseDominatorReferences(HeapAnalysisResult r) {
        if (r.getFilename() == null) return;
        if (r.getDominatorTreeEntries() == null || r.getDominatorTreeEntries().isEmpty()) return;
        // 이미 ref 가 채워져 있으면 스킵
        boolean alreadyPopulated = r.getDominatorTreeEntries().stream()
                .limit(50)
                .anyMatch(d -> (d.getIncomingRefs() != null && !d.getIncomingRefs().isEmpty())
                            || (d.getOutgoingRefs() != null && !d.getOutgoingRefs().isEmpty()));
        if (alreadyPopulated) return;

        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            tmp.setDominatorTreeEntries(r.getDominatorTreeEntries());
            parser.reparseDominatorReferences(resultDir.getAbsolutePath(), baseName, tmp, 50, 50);
        } catch (Exception e) {
            logger.debug("Could not re-extract dominator refs for {}: {}", r.getFilename(), e.getMessage());
        }
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

    private void reparseSuspectsStacktrace(HeapAnalysisResult r) {
        if (r.getLeakSuspects() == null || r.getLeakSuspects().isEmpty()) return;
        boolean anyMissing = r.getLeakSuspects().stream()
                .allMatch(s -> s.getStacktracePage() == null && s.getStacktraceLocalVarsPage() == null);
        if (!anyMissing) return;
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            parser.reparseSuspects(resultDir.getAbsolutePath(), baseName, tmp);
            if (tmp.getLeakSuspects() != null && tmp.getLeakSuspects().size() == r.getLeakSuspects().size()) {
                for (int i = 0; i < r.getLeakSuspects().size(); i++) {
                    LeakSuspect orig = r.getLeakSuspects().get(i);
                    LeakSuspect fresh = tmp.getLeakSuspects().get(i);
                    if (orig.getStacktracePage() == null && fresh.getStacktracePage() != null) {
                        orig.setStacktracePage(fresh.getStacktracePage());
                    }
                    if (orig.getStacktraceLocalVarsPage() == null && fresh.getStacktraceLocalVarsPage() != null) {
                        orig.setStacktraceLocalVarsPage(fresh.getStacktraceLocalVarsPage());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not re-extract suspects stacktrace for {}: {}", r.getFilename(), e.getMessage());
        }
    }

    /**
     * keyword 추출 기능 도입(2026-05) 이전에 생성된 result.json 은 leakSuspects[].keywords 가 null 이고
     * description 이 구버전 500자 캡으로 잘려 있다. 해당 결과를 캐시 복원 시 _Leak_Suspects.zip 에서 재추출하여
     * keywords 백필 + (재추출 description 이 더 길면) description 교체. 인메모리 캐시만 갱신(reparse* 패턴 동일).
     */
    private void reparseSuspectsMeta(HeapAnalysisResult r) {
        if (r.getLeakSuspects() == null || r.getLeakSuspects().isEmpty()) return;
        boolean anyMissingKeywords = r.getLeakSuspects().stream()
                .anyMatch(s -> s.getKeywords() == null || s.getKeywords().isEmpty());
        if (!anyMissingKeywords) return;
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            parser.reparseSuspects(resultDir.getAbsolutePath(), baseName, tmp);
            if (tmp.getLeakSuspects() != null && tmp.getLeakSuspects().size() == r.getLeakSuspects().size()) {
                int backfilled = 0;
                for (int i = 0; i < r.getLeakSuspects().size(); i++) {
                    LeakSuspect orig = r.getLeakSuspects().get(i);
                    LeakSuspect fresh = tmp.getLeakSuspects().get(i);
                    if ((orig.getKeywords() == null || orig.getKeywords().isEmpty())
                            && fresh.getKeywords() != null && !fresh.getKeywords().isEmpty()) {
                        orig.setKeywords(fresh.getKeywords());
                        backfilled++;
                    }
                    // 구버전 description(500자 잘림 또는 푸터 혼입)을 현재 파서의 정규 재추출 본문으로 교체.
                    // 백필 자체가 keyword 누락(구버전) 조건에서만 동작하므로 fresh 가 권위 있는 값.
                    if (fresh.getDescription() != null && !fresh.getDescription().isEmpty()) {
                        orig.setDescription(fresh.getDescription());
                    }
                }
                if (backfilled > 0) {
                    logger.info("Backfilled keywords for {} suspect(s) of {}", backfilled, r.getFilename());
                }
            }
        } catch (Exception e) {
            logger.debug("Could not backfill suspects meta for {}: {}", r.getFilename(), e.getMessage());
        }
    }

    private void reparseOverviewMeta(HeapAnalysisResult r) {
        if (r.getFilename() == null) return;
        String baseName = stripExtension(r.getFilename());
        File resultDir = resultDirectory(r.getFilename());
        if (!resultDir.exists()) return;
        try {
            MatParseResult tmp = new MatParseResult();
            parser.reparseOverviewMeta(resultDir.getAbsolutePath(), baseName, tmp);
            if (tmp.getClassLoaderCount() > 0) r.setClassLoaderCount(tmp.getClassLoaderCount());
            if (tmp.getGcRootCount() > 0) r.setGcRootCount(tmp.getGcRootCount());
            if (r.getDumpCreationTime() == null && (tmp.getDumpDate() != null || tmp.getDumpTime() != null)) {
                r.setDumpCreationTime(parseDumpCreationTime(tmp.getDumpDate(), tmp.getDumpTime()));
            }
        } catch (Exception e) {
            logger.debug("Could not re-extract overview meta for {}: {}", r.getFilename(), e.getMessage());
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
        detectOomInThreads(r);
    }

    /**
     * 각 ThreadInfo 의 stackTrace 에서 OutOfMemoryError 를 감지하여 oom=true 로 표시하고,
     * OOM 종류를 oomType 에 저장한다. 매번 idempotent. 감지/분류는 {@link com.heapdump.analyzer.util.OomDetector} 에 위임.
     *
     * <p>우선순위: 힙에서 추출한 정확한 메시지({@code result.oomDetailMessage}, preallocated 배제됨)가 있으면
     * 그 값을 OOM 스레드에 적용(정확), 없으면 스택 시그니처 기반 "(추정)" 값을 사용한다.
     */
    private void detectOomInThreads(HeapAnalysisResult result) {
        if (result == null) return;
        java.util.List<com.heapdump.analyzer.model.ThreadInfo> threads = result.getThreadInfos();
        if (threads == null || threads.isEmpty()) return;
        String exact = result.getOomDetailMessage();
        boolean hasExact = exact != null && !exact.trim().isEmpty();
        int oomCount = 0, inferredCount = 0;
        for (com.heapdump.analyzer.model.ThreadInfo ti : threads) {
            com.heapdump.analyzer.util.OomDetector.Result r =
                    com.heapdump.analyzer.util.OomDetector.detect(ti.getStackTrace());
            if (r.oom) {
                ti.setOom(true);
                if (hasExact) {
                    ti.setOomType(exact);            // 힙에서 추출한 정확한 종류
                } else {
                    ti.setOomType(r.displayType);    // 스택 시그니처 기반 추정 (또는 명시 메시지)
                    if (r.inferred) inferredCount++;
                }
                oomCount++;
            } else if (ti.isOom()) {
                // 스택에 OutOfMemoryError.<init> 프레임은 없지만 enrich/영속화로 이미 OOM 으로 식별된 스레드
                // (예: 네이티브 스레드 생성 실패 — Thread.start0 만 보이고 <init> 없음). 표시 유지.
                String cur = ti.getOomType();
                if (cur == null || cur.isEmpty()) ti.setOomType(hasExact ? exact : null);
                oomCount++;
            } else {
                ti.setOom(false);
                ti.setOomType(null);
            }
        }
        if (oomCount > 0) {
            logger.info("[OOM] Detected OutOfMemoryError in {} thread(s) — {}",
                    oomCount, hasExact ? ("exact type from heap: " + exact)
                                       : (inferredCount + " subtype inferred from stack"));
        }
    }

    /**
     * 실제 throw 된 OOM 의 정확한 종류를 힙에서 추출한다.
     *
     * <p>JVM 은 OOM 발생 시 추가 할당을 피하려 OutOfMemoryError 인스턴스들("Java heap space" /
     * "GC overhead limit exceeded" / "Metaspace" / "Requested array size exceeds VM limit" 등)을
     * <b>미리 만들어둔다(preallocated)</b>. 따라서 단순히 모든 OOM 인스턴스의 메시지를 읽으면 실제
     * 발생한 OOM 을 알 수 없다(모든 종류가 항상 존재). 실제 throw 된 OOM 은 스레드 스택의 local 변수로
     * 참조되므로, OQL 로 얻은 (OOM 인스턴스 주소 → detailMessage) 맵과 .threads 의 local objectId
     * 집합의 <b>교집합</b>을 구해 정확한 메시지를 추출한다.
     *
     * <p>또한 스택에 {@code OutOfMemoryError.<init>} 프레임이 없어 {@link #detectOomInThreads} 가
     * 놓치는 OOM 종류 — 대표적으로 <b>"unable to create new native thread"</b>(네이티브 메서드
     * {@code Thread.start0} 에서 VM 이 직접 throw) — 도, OOM 인스턴스가 해당 스레드의 local 로
     * 참조되므로 OOM-prone 프레임을 가진 스레드를 직접 oom=true 로 표시한다. ("Direct buffer memory" 는
     * {@code Bits.reserveMemory} 의 Java {@code new OutOfMemoryError} 라 스택에 {@code <init>} 가
     * 있어 이미 감지되지만, 동일 경로로 함께 보강한다.)
     *
     * @param result   OOM 가능성이 있는 결과 (threadStacksText 로드 완료 상태)
     * @param dumpFile tmp 의 힙 덤프 파일
     * @param resultDir .index/.threads 가 위치한 결과 디렉토리
     */
    /** 스레드 중 OOM-prone 프레임(네이티브 스레드 생성/다이렉트 버퍼)을 가진 것이 있는지 — enrich 실행 트리거. */
    private boolean hasOomProneThread(HeapAnalysisResult result) {
        if (result == null || result.getThreadInfos() == null) return false;
        for (com.heapdump.analyzer.model.ThreadInfo ti : result.getThreadInfos()) {
            if (com.heapdump.analyzer.util.OomDetector.hasOomProneFrames(ti.getStackTrace())) return true;
        }
        return false;
    }

    private void enrichThrownOomMessage(HeapAnalysisResult result, File dumpFile, File resultDir) {
        try {
            if (result == null) return;
            String threadsText = result.getThreadStacksText();
            if (threadsText == null || threadsText.isEmpty()) return;

            // 1) 스레드별 local 주소 집합 (Thread 0x... 블록 단위) + 전역 합집합
            java.util.Map<String, java.util.Set<Long>> threadLocals = parseThreadLocalsByThread(threadsText);
            java.util.Set<Long> allLocals = new java.util.HashSet<>();
            for (java.util.Set<Long> s : threadLocals.values()) allLocals.addAll(s);
            if (allLocals.isEmpty()) return;

            // 2) OQL 실행 — 격리된 임시 디렉토리(덤프 심볼릭 링크 + 인덱스 복사)에서 쿼리
            File queryZip = runOomDetailQuery(dumpFile, resultDir);
            if (queryZip == null || !queryZip.exists()) return;

            // 3) (OOM 인스턴스 주소 → 메시지) 파싱 후 임시 디렉토리 정리
            java.util.Map<Long, String> oomInstances = parseOomQueryZip(queryZip);
            try { deleteDirectoryRecursively(queryZip.getParentFile()); } catch (Exception ignore) {}
            if (oomInstances.isEmpty()) return;

            // 4) 메시지 추출 — 스레드가 실제 참조하는(=throw 된) OOM 인스턴스만 카운트
            //    (uncaughtException 핸들러가 보유한 것도 메시지 산정에는 포함)
            java.util.Map<String, Integer> freq = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<Long, String> e : oomInstances.entrySet()) {
                if (!allLocals.contains(e.getKey())) continue;
                String msg = e.getValue();
                if (msg == null || msg.isEmpty() || "null".equalsIgnoreCase(msg)) continue;
                freq.merge(msg, 1, Integer::sum);
            }
            if (freq.isEmpty()) {
                logger.info("[OOM] No thread-referenced OOM instance for {} — keeping inferred type", result.getFilename());
                return;
            }

            // 5) 가장 빈번한 메시지를 대표값으로
            String primary = null; int best = -1;
            for (java.util.Map.Entry<String, Integer> e : freq.entrySet()) {
                if (e.getValue() > best) { best = e.getValue(); primary = e.getKey(); }
            }
            result.setOomDetailMessage(primary);
            logger.info("[OOM] Exact OOM type from heap for {}: '{}' (thrown candidates: {})",
                    result.getFilename(), primary, freq.keySet());

            // 6) 스택에 OutOfMemoryError.<init> 프레임이 없어 detect() 가 놓친 OOM 스레드 직접 표시:
            //    OOM-prone 프레임(Thread.start0 / Bits.reserveMemory 등)을 가지고 + OOM 인스턴스를
            //    local 로 참조하는 스레드만 oom=true (핸들러 스레드 과다표시 방지).
            int flagged = 0;
            if (result.getThreadInfos() != null) {
                for (com.heapdump.analyzer.model.ThreadInfo ti : result.getThreadInfos()) {
                    if (ti.isOom()) continue;  // detect() 가 이미 표시
                    if (!com.heapdump.analyzer.util.OomDetector.hasOomProneFrames(ti.getStackTrace())) continue;
                    String addr = ti.getAddress() != null ? ti.getAddress().toLowerCase() : null;
                    java.util.Set<Long> locals = addr != null ? threadLocals.get(addr) : null;
                    if (locals == null) continue;
                    String refMsg = null;
                    for (Long la : locals) {
                        String m = oomInstances.get(la);
                        if (m != null && !m.isEmpty() && !"null".equalsIgnoreCase(m)) { refMsg = m; break; }
                    }
                    if (refMsg != null) {
                        ti.setOom(true);
                        ti.setOomType(refMsg);
                        flagged++;
                    }
                }
            }
            if (flagged > 0) {
                logger.info("[OOM] Flagged {} additional OOM thread(s) via local-ref (no <init> frame, e.g. native thread)", flagged);
            }

            // 7) 스레드 oomType 재적용 (정확 메시지 반영, 6 에서 표시한 스레드는 유지)
            detectOomInThreads(result);
        } catch (Exception e) {
            logger.warn("[OOM] Exact OOM message extraction failed for {}: {}",
                    result != null ? result.getFilename() : "?", e.getMessage());
        }
    }

    /**
     * .threads 를 "Thread 0x..." 블록 단위로 나눠 각 스레드 주소(소문자 hex)별 local objectId 집합을 만든다.
     * 반환 키는 ThreadInfo.getAddress() 와 매칭되는 "0x..." 형식.
     */
    private java.util.Map<String, java.util.Set<Long>> parseThreadLocalsByThread(String threadsText) {
        java.util.Map<String, java.util.Set<Long>> map = new java.util.HashMap<>();
        java.util.regex.Pattern localP = java.util.regex.Pattern.compile("objectId=0x([0-9a-fA-F]+)");
        for (String block : threadsText.split("(?=Thread 0x)")) {
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("Thread (0x[0-9a-fA-F]+)").matcher(block);
            if (!hm.find()) continue;
            String addr = hm.group(1).toLowerCase();
            java.util.Set<Long> locals = new java.util.HashSet<>();
            java.util.regex.Matcher lm = localP.matcher(block);
            while (lm.find()) {
                try { locals.add(Long.parseUnsignedLong(lm.group(1), 16)); } catch (NumberFormatException ignore) {}
            }
            if (!locals.isEmpty()) map.put(addr, locals);
        }
        return map;
    }

    /**
     * OOM detailMessage 추출용 OQL 을 격리된 임시 디렉토리에서 실행하고 결과 `_Query.zip` 을 반환.
     * 인덱스는 복사(작음), 덤프는 심볼릭 링크(큼)하여 원본 아티팩트를 건드리지 않는다.
     */
    private File runOomDetailQuery(File dumpFile, File resultDir) {
        File qdir = null;
        try {
            String dumpName = dumpFile.getName();
            String base = stripExtension(dumpName);
            qdir = new File(tmpDirectory(), "oomq-" + base + "-" + System.nanoTime());
            Files.createDirectories(qdir.toPath());

            // 덤프: 심볼릭 링크 (104MB 복사 회피)
            java.nio.file.Path linkDump = new File(qdir, dumpName).toPath();
            Files.createSymbolicLink(linkDump, dumpFile.getAbsoluteFile().toPath());

            // 인덱스/threads: 복사 (MAT 가 재기록해도 원본 보호)
            File[] idx = resultDir.listFiles((d, n) ->
                    n.startsWith(base + ".") && (n.endsWith(".index") || n.endsWith(".threads")));
            if (idx == null || idx.length == 0) {
                logger.warn("[OOM] No index files in {} for OQL — skipping exact extraction", resultDir.getName());
                deleteDirectoryRecursively(qdir);
                return null;
            }
            for (File f : idx) {
                Files.copy(f.toPath(), new File(qdir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            String oql = "oql \"SELECT o.@objectAddress, toString(o.detailMessage) FROM java.lang.OutOfMemoryError o\"";
            runMatSingleQuery(linkDump.toString(), qdir, oql, 120);

            File zip = new File(qdir, base + "_Query.zip");
            return zip.exists() ? zip : null;
        } catch (Exception e) {
            logger.warn("[OOM] OQL query run failed: {}", e.getMessage());
            if (qdir != null) { try { deleteDirectoryRecursively(qdir); } catch (Exception ignore) {} }
            return null;
        }
    }

    /** OQL `_Query.zip` 의 index.html 표에서 (주소 → detailMessage) 맵을 파싱. */
    private java.util.Map<Long, String> parseOomQueryZip(File zip) {
        java.util.Map<Long, String> map = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.zip.ZipEntry idx = null;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().endsWith("index.html")) { idx = e; break; }
            }
            if (idx == null) return map;
            String html;
            try (java.io.InputStream is = zf.getInputStream(idx)) {
                html = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            java.util.regex.Matcher rows = java.util.regex.Pattern
                    .compile("<tr[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL).matcher(html);
            java.util.regex.Pattern cellP = java.util.regex.Pattern
                    .compile("<t[dh][^>]*>(.*?)</t[dh]>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Pattern addrP = java.util.regex.Pattern.compile("^([0-9,]+)");
            while (rows.find()) {
                java.util.List<String> cells = new java.util.ArrayList<>();
                java.util.regex.Matcher cm = cellP.matcher(rows.group(1));
                while (cm.find()) {
                    cells.add(cm.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                }
                if (cells.size() < 2) continue;
                java.util.regex.Matcher am = addrP.matcher(cells.get(0));
                if (!am.find()) continue;             // 헤더("o.@objectAddress")/푸터("Total:") 행 제외
                String digits = am.group(1).replace(",", "");
                if (digits.isEmpty()) continue;
                long addr;
                try { addr = Long.parseLong(digits); }
                catch (NumberFormatException ex) {
                    try { addr = Long.parseUnsignedLong(digits); } catch (Exception ex2) { continue; }
                }
                map.put(addr, cells.get(1));
            }
        } catch (Exception e) {
            logger.warn("[OOM] Failed to parse OQL query zip: {}", e.getMessage());
        }
        return map;
    }

    /**
     * System Properties 추출. 두 단계로 시도한다.
     *
     * <p>1차(주): System_Overview.zip 의 {@code System_Properties*.html} 파싱.
     *    Overview 리포트는 메인 MAT 분석에서 항상 생성되며 WebLogic·JEUS·Tomcat 등
     *    모든 벤더에서 안정적으로 동작한다.
     *
     * <p>2차(폴백): MAT {@code system_properties} 단독 쿼리. Overview 파싱이 실패하거나
     *    System_Properties 섹션이 없을 때만 실행한다.
     *
     * <p>실패해도 분석 전체에는 영향 없음 (빈 맵 유지).
     */
    private void enrichSystemProperties(HeapAnalysisResult result, File dumpFile, File resultDir) {
        String base = stripExtension(dumpFile.getName());

        // ── 1차: System_Overview.zip 내 System_Properties 페이지 파싱 ────────────────
        try {
            java.util.Map<String, String> props = parser.parseSystemProperties(
                    resultDir.getAbsolutePath(), base);
            if (!props.isEmpty()) {
                result.setSystemProperties(props);
                logger.info("[SysProp] Extracted {} system properties from overview ZIP for {}",
                        props.size(), result.getFilename());
                return;
            }
            logger.debug("[SysProp] Overview ZIP has no system_properties page for {}", result.getFilename());
        } catch (Exception e) {
            logger.debug("[SysProp] Overview parsing failed for {}: {}", result.getFilename(), e.getMessage());
        }

        // ── 2차 폴백: MAT system_properties 단독 쿼리 ────────────────────────────────
        File qdir = null;
        try {
            String dumpName = dumpFile.getName();
            qdir = new File(tmpDirectory(), "sysprop-" + base + "-" + System.nanoTime());
            Files.createDirectories(qdir.toPath());

            java.nio.file.Path linkDump = new File(qdir, dumpName).toPath();
            Files.createSymbolicLink(linkDump, dumpFile.getAbsoluteFile().toPath());

            File[] idx = resultDir.listFiles((d, n) ->
                    n.startsWith(base + ".") && (n.endsWith(".index") || n.endsWith(".threads")));
            if (idx == null || idx.length == 0) {
                logger.warn("[SysProp] No index files in {} — skipping MAT query fallback", resultDir.getName());
                deleteDirectoryRecursively(qdir);
                return;
            }
            for (File f : idx) {
                Files.copy(f.toPath(), new File(qdir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            String matOut = runMatSingleQuery(linkDump.toString(), qdir, "system_properties", 120);

            // zip 이름: 정확한 이름 → qdir 내 *_Query.zip 폴백
            File zip = new File(qdir, base + "_Query.zip");
            if (!zip.exists()) {
                File[] candidates = qdir.listFiles((d, n) -> n.endsWith("_Query.zip"));
                if (candidates != null && candidates.length > 0) {
                    zip = candidates[0];
                    logger.debug("[SysProp] Zip found via fallback search: {}", zip.getName());
                } else {
                    logger.warn("[SysProp] Query zip not found for {} — MAT output: {}",
                            result.getFilename(),
                            matOut.substring(0, Math.min(500, matOut.length())).trim());
                }
            }
            if (zip.exists()) {
                java.util.Map<String, String> props = parseSystemPropertiesZip(zip);
                if (!props.isEmpty()) {
                    result.setSystemProperties(props);
                    logger.info("[SysProp] Extracted {} system properties via MAT query for {}",
                            props.size(), result.getFilename());
                } else {
                    logger.warn("[SysProp] MAT query zip parsed but empty for {} — MAT output: {}",
                            result.getFilename(),
                            matOut.substring(0, Math.min(500, matOut.length())).trim());
                }
            }
        } catch (Exception e) {
            logger.warn("[SysProp] System properties extraction failed for {}: {}",
                    result != null ? result.getFilename() : "?", e.getMessage());
        } finally {
            if (qdir != null) { try { deleteDirectoryRecursively(qdir); } catch (Exception ignore) {} }
        }
    }

    /** system_properties `_Query.zip` 의 index.html 2열 표를 (key → value) 맵으로 파싱. 입력 순서 보존. */
    private java.util.Map<String, String> parseSystemPropertiesZip(File zip) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            java.util.zip.ZipEntry idx = null;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e.getName().endsWith("index.html")) { idx = e; break; }
            }
            if (idx == null) return map;
            String html;
            try (java.io.InputStream is = zf.getInputStream(idx)) {
                html = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            java.util.regex.Matcher rows = java.util.regex.Pattern
                    .compile("<tr[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL).matcher(html);
            java.util.regex.Pattern cellP = java.util.regex.Pattern
                    .compile("<t[dh][^>]*>(.*?)</t[dh]>", java.util.regex.Pattern.DOTALL);
            while (rows.find()) {
                java.util.List<String> cells = new java.util.ArrayList<>();
                java.util.regex.Matcher cm = cellP.matcher(rows.group(1));
                while (cm.find()) {
                    cells.add(cm.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                }
                // MAT system_properties 출력: 3열 [Collection | Key | Value] 플랫 테이블.
                // 헤더행: [Collection, Key, Value], 데이터행: [java.util.Properties@0x..., key, value],
                // 푸터행: [Total: N entries, "", ""]
                // 2열 폴백도 허용 (MAT 버전에 따라 가능).
                String key, value;
                if (cells.size() >= 3) { key = cells.get(1); value = cells.get(2); }
                else if (cells.size() == 2) { key = cells.get(0); value = cells.get(1); }
                else continue;
                // 헤더("Key"/"Collection")·푸터("Total:")·빈 키 제외
                if (key.isEmpty() || key.equalsIgnoreCase("Key") || key.equalsIgnoreCase("Name")
                        || key.equalsIgnoreCase("Collection")
                        || key.toLowerCase().startsWith("total")) continue;
                map.put(key, value);
            }
        } catch (Exception e) {
            logger.warn("[SysProp] Failed to parse system properties zip: {}", e.getMessage());
        }
        return map;
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

    public boolean isDominatorRefsEnabled()       { return dominatorRefsEnabled; }
    public void    setDominatorRefsEnabled(boolean v) {
        this.dominatorRefsEnabled = v;
        logger.info("mat.dominator-refs.enabled set to {}", v);
        persistSettings();
    }

    public boolean isAllowAllExtensions()         { return allowAllExtensions; }
    public void    setAllowAllExtensions(boolean v) {
        this.allowAllExtensions = v;
        com.heapdump.analyzer.util.FilenameValidator.setAllowAllExtensions(v);
        logger.info("allow_all_extensions set to {}", v);
        persistSettings();
    }

    public int  getSessionTimeoutHours()          { return sessionTimeoutHours; }
    public void setSessionTimeoutHours(int hours) {
        if (hours < 1 || hours > 6) throw new IllegalArgumentException("세션 타임아웃은 1~6시간 사이여야 합니다.");
        this.sessionTimeoutHours = hours;
        logger.info("session_timeout_hours set to {}", hours);
        persistSettings();
    }

    public int  getDashboardDetectDays()          { return dashboardDetectDays; }
    public void setDashboardDetectDays(int days) {
        if (days < 7 || days > 90) throw new IllegalArgumentException("대시보드 탐지 기간은 7~90일 사이여야 합니다.");
        this.dashboardDetectDays = days;
        logger.info("dashboard_detect_days set to {}", days);
        persistSettings();
    }

    public long getMaxUploadSizeBytes()           { return maxUploadSizeBytes; }
    public void setMaxUploadSizeBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("업로드 크기는 0보다 커야 합니다.");
        }
        if (bytes > MAX_UPLOAD_LIMIT_BYTES) {
            throw new IllegalArgumentException("업로드 크기는 최대 50 GB를 초과할 수 없습니다.");
        }
        this.maxUploadSizeBytes = bytes;
        logger.info("max_upload_size_bytes set to {} ({} GB)", bytes, bytes / (1024.0 * 1024 * 1024));
        persistSettings();
    }

    // ── 런타임 설정 영속화 (settings.json) ─────────────────────────

    /**
     * 외부 서비스(예: RemoteDumpService 의 SSH local user/SCP temp dir/scan interval 변경)가
     * 런타임 설정을 바꾼 뒤 settings.json + application.properties 동기화를 트리거하기 위한 공개 진입점.
     */
    public void persistRuntimeSettings() {
        persistSettings();
    }

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

            if (saved.containsKey("dominatorRefsEnabled")) {
                Object val = saved.get("dominatorRefsEnabled");
                if (val instanceof Boolean) {
                    this.dominatorRefsEnabled = (Boolean) val;
                } else {
                    this.dominatorRefsEnabled = Boolean.parseBoolean(String.valueOf(val));
                }
                logger.info("[Settings] Restored dominatorRefsEnabled={}", dominatorRefsEnabled);
            }

            if (saved.containsKey("allowAllExtensions")) {
                Object val = saved.get("allowAllExtensions");
                if (val instanceof Boolean) {
                    this.allowAllExtensions = (Boolean) val;
                } else {
                    this.allowAllExtensions = Boolean.parseBoolean(String.valueOf(val));
                }
                logger.info("[Settings] Restored allowAllExtensions={}", allowAllExtensions);
            }
            // FilenameValidator 정적 플래그 동기화 — 컨트롤러 입구 검증이 토글을 인식하도록.
            com.heapdump.analyzer.util.FilenameValidator.setAllowAllExtensions(this.allowAllExtensions);

            if (saved.containsKey("maxUploadSizeBytes")) {
                Object val = saved.get("maxUploadSizeBytes");
                long bytes = 0;
                if (val instanceof Number) {
                    bytes = ((Number) val).longValue();
                } else if (val != null) {
                    try { bytes = Long.parseLong(String.valueOf(val)); } catch (NumberFormatException ignored) {}
                }
                if (bytes > 0 && bytes <= MAX_UPLOAD_LIMIT_BYTES) {
                    this.maxUploadSizeBytes = bytes;
                    logger.info("[Settings] Restored maxUploadSizeBytes={} ({} GB)",
                            bytes, bytes / (1024.0 * 1024 * 1024));
                } else {
                    logger.warn("[Settings] Invalid maxUploadSizeBytes={}, using default {} GB",
                            val, DEFAULT_UPLOAD_SIZE_BYTES / (1024 * 1024 * 1024));
                }
            }

            if (saved.containsKey("sessionTimeoutHours")) {
                Object val = saved.get("sessionTimeoutHours");
                int h = (val instanceof Number) ? ((Number) val).intValue() : 1;
                if (h >= 1 && h <= 6) {
                    this.sessionTimeoutHours = h;
                    logger.info("[Settings] Restored sessionTimeoutHours={}", h);
                }
            }

            if (saved.containsKey("dashboardDetectDays")) {
                Object val = saved.get("dashboardDetectDays");
                int d = (val instanceof Number) ? ((Number) val).intValue() : 14;
                int[] allowed = {7, 14, 30, 60, 90};
                boolean valid = false;
                for (int a : allowed) if (a == d) { valid = true; break; }
                if (valid) {
                    this.dashboardDetectDays = d;
                    logger.info("[Settings] Restored dashboardDetectDays={}", d);
                }
            }

            // LLM/RAG/원격 설정 복원 — 각 서비스에 위임
            llmConfig.applyFromSettings(saved);
            ragConfig.applyFromSettings(saved);
            remoteDumpService.applyFromSettings(saved);
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
            settings.put("dominatorRefsEnabled", dominatorRefsEnabled);
            settings.put("maxUploadSizeBytes", maxUploadSizeBytes);
            settings.put("allowAllExtensions", allowAllExtensions);
            settings.put("sessionTimeoutHours", sessionTimeoutHours);
            settings.put("dashboardDetectDays", dashboardDetectDays);
            // LLM/RAG/원격 설정 — 각 서비스에 위임
            llmConfig.collectSettings(settings);
            ragConfig.collectSettings(settings);
            remoteDumpService.collectSettings(settings);
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
            updates.put("mat.dominator-refs.enabled", String.valueOf(dominatorRefsEnabled));
            String multipartSize = formatBytesAsSpringSize(maxUploadSizeBytes);
            updates.put("spring.servlet.multipart.max-file-size", multipartSize);
            updates.put("spring.servlet.multipart.max-request-size", multipartSize);
            updates.put("server.servlet.session.timeout", sessionTimeoutHours + "h");
            // LLM/RAG/원격 설정 — 각 서비스에 위임
            llmConfig.collectApplicationProperties(updates);
            ragConfig.collectApplicationProperties(updates);
            remoteDumpService.collectApplicationProperties(updates);
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

    /**
     * bytes 를 Spring DataSize 표기 (예: 5GB / 512MB / 1024KB) 로 변환.
     * 정확히 GB/MB/KB 단위로 떨어지면 해당 단위, 아니면 byte 값을 그대로 반환.
     */
    private static String formatBytesAsSpringSize(long bytes) {
        long gb = 1024L * 1024 * 1024;
        long mb = 1024L * 1024;
        long kb = 1024L;
        if (bytes % gb == 0) return (bytes / gb) + "GB";
        if (bytes % mb == 0) return (bytes / mb) + "MB";
        if (bytes % kb == 0) return (bytes / kb) + "KB";
        return String.valueOf(bytes) + "B";
    }

    private File findExternalPropertiesFile() {
        // 1) JAR과 같은 디렉토리
        // Spring Boot 3.x 팻 JAR은 getCodeSource().getLocation()이 nested:/...!/... 또는
        // jar:file:/...!/... 형태라 toURI()->new File() 변환이 실패함.
        // URL 문자열에서 .jar 경로를 직접 추출하여 처리.
        try {
            String loc = getClass().getProtectionDomain().getCodeSource().getLocation().toString();
            // "nested:/path/to/app.jar/!BOOT-INF/..." 또는 "jar:file:/path/to/app.jar!/..."
            String jarPath = loc;
            if (jarPath.contains(".jar!") || jarPath.contains(".jar/!")) {
                int idx = jarPath.contains(".jar!") ? jarPath.indexOf(".jar!") : jarPath.indexOf(".jar/!");
                jarPath = jarPath.substring(0, idx + 4); // ".jar" 까지만
            }
            // 스킴 제거 (nested:, jar:file:, file: 등)
            jarPath = jarPath.replaceFirst("^(?:nested:|jar:file:|file:)", "");
            File jarDir = new File(jarPath).getParentFile();
            if (jarDir != null) {
                File f = new File(jarDir, "application.properties");
                if (f.exists()) return f;
            }
        } catch (Exception ignored) {}

        // 2) 현재 작업 디렉토리
        try {
            File f = new File(System.getProperty("user.dir", "."), "application.properties");
            if (f.exists()) return f;
        } catch (Exception ignored) {}

        // 3) 프로젝트 소스 디렉토리 (개발 환경)
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

    public void callLlmChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   List<Map<String, Object>> attachments,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        llmConfig.callLlmChatStream(messages, systemPrompt, attachments, onChunk, onDone, onError);
    }

    public boolean isLlmFileAttachEnabled() { return llmConfig.isLlmFileAttachEnabled(); }

    public void setLlmFileAttachEnabled(boolean v) {
        llmConfig.setLlmFileAttachEnabled(v);
        persistSettings();
    }

    public boolean isFileAttachCapable() { return llmConfig.isFileAttachCapable(); }

    /**
     * LLM 호출 시 prompt 에 합칠 OOM 컨텍스트 블록을 반환.
     * cached HeapAnalysisResult 의 ThreadInfo 목록에서 oom=true 인 스레드를 집계한다.
     * - OOM 미감지 / filename 누락 / result 미존재 시 빈 문자열 반환 → 호출자가 합치지 않음.
     */
    public String buildOomPromptSection(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        try {
            HeapAnalysisResult r = getCachedResult(filename);
            return r != null ? r.getOomContextSummary() : "";
        } catch (Exception e) {
            logger.debug("[OOM] buildOomPromptSection failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    // ── MAT 프로세스 동시성 산정 (메모리 기반 동적) ───────────────────────────

    /**
     * 동시 실행 가능한 MAT 프로세스 수를 (재)산정해 게이트에 반영.
     * 시작 시 + MAT -Xmx 변경 시 호출. config override(>0) 가 있으면 그 값을 사용.
     */
    public synchronized void recomputeMatConcurrency() {
        int computed = computeMaxConcurrentMat();
        matMaxConcurrent = computed;
        matSlots.setMaxPermits(computed);
        logger.info("[MAT Concurrency] 동시 MAT 프로세스 한도 = {} (matXmx={}MB, appXmx={}MB, hostRAM={}MB, cpus={}, override={})",
                computed, getMatHeapSize() / 1048576L, Runtime.getRuntime().maxMemory() / 1048576L,
                hostPhysicalRamBytes() / 1048576L, Runtime.getRuntime().availableProcessors(),
                config.getMatMaxConcurrentProcesses());
    }

    private int computeMaxConcurrentMat() {
        int override = config.getMatMaxConcurrentProcesses();
        if (override > 0) return override; // 운영자 명시 고정값
        long matXmx = getMatHeapSize();
        if (matXmx <= 0) matXmx = 2048L * 1048576L; // ini 읽기 실패 시 보수적 기본 2GB
        long appXmx = Runtime.getRuntime().maxMemory();
        long hostRam = hostPhysicalRamBytes();
        if (hostRam <= 0) return 1; // RAM 미상 → 보수적 1
        // OS/버퍼 여유 20% 확보 후, 앱 힙 제외한 가용분을 MAT -Xmx 로 나눔. CPU 수로도 상한.
        long usable = (long) (hostRam * 0.80) - appXmx;
        int byMem = (int) Math.max(1L, usable / Math.max(matXmx, 256L * 1048576L));
        int byCpu = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(byMem, byCpu));
    }

    /** 호스트 물리 RAM(bytes). Linux /proc/meminfo MemTotal. 실패 시 -1. */
    private long hostPhysicalRamBytes() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2) return Long.parseLong(p[1]) * 1024L; // kB → bytes
                }
            }
        } catch (Exception ignore) {}
        return -1;
    }

    /** 호스트 가용 메모리(MB). Linux /proc/meminfo MemAvailable. 실패 시 -1. 진단 로깅용. */
    private long hostAvailableRamMb() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 2) return Long.parseLong(p[1]) / 1024L; // kB → MB
                }
            }
        } catch (Exception ignore) {}
        return -1;
    }

    /** 현재 MAT 동시 실행 한도에 도달해 추가 MAT 가 대기해야 하는 상태인지. lazy 대기 안내용. */
    public boolean isMatThrottled() { return matSlots.availablePermits() <= 0; }

    /** 현재 설정된 동시 MAT 한도. */
    public int getMatMaxConcurrent() { return matMaxConcurrent; }

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
        // MAT -Xmx 변경 → 동시 실행 한도 재산정(재기동 불필요)
        recomputeMatConcurrency();
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
        return fileMgmt.uploadFile(file, allowAllExtensions);
    }

    // ── 업로드 중복 검사 ─────────────────────────────────────────

    public Map<String, String> checkDuplicate(String filename, long fileSize, String partialHash) {
        return fileMgmt.checkDuplicate(filename, fileSize, partialHash, allowAllExtensions);
    }

    private String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }

    public List<HeapDumpFile> listFiles() {
        return fileMgmt.listFiles(allowAllExtensions);
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

    public Map<String, String> loadFileClassifications() {
        return fileMgmt.loadFileClassifications();
    }

    public void saveFileClassification(String filename, String fileType) throws IOException {
        fileMgmt.saveFileClassification(filename, fileType);
    }

    public Map<String, String> loadCoreExecPairings() {
        return fileMgmt.loadCoreExecPairings();
    }

    public void saveCoreExecPairing(String coreFilename, String execFilename) throws IOException {
        fileMgmt.saveCoreExecPairing(coreFilename, execFilename);
    }

    public void removeCoreExecPairing(String coreFilename) throws IOException {
        fileMgmt.removeCoreExecPairing(coreFilename);
    }

    /**
     * 히스토리 삭제: 분석 결과 디렉토리 + 인덱스 파일 + 메모리 캐시 삭제
     * @param deleteHeapDump true이면 힙덤프 파일도 함께 삭제
     */
    @Transactional
    public void deleteHistory(String filename, boolean deleteHeapDump, boolean deleteAiChat) throws IOException {
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
        if (deleteAiChat) {
            try {
                aiChatMessageRepository.deleteByFilename(safe);
                aiChatSessionRepository.deleteByFilename(safe);
                logger.info("[DeleteHistory] DB ai_chat_messages/sessions deleted: {}", safe);
            } catch (Exception e) {
                logger.warn("[DeleteHistory] Failed to delete AI chat data for '{}': {}", safe, e.getMessage());
            }
        }

        logger.info("[DeleteHistory] Completed: filename='{}', heapDumpDeleted={}", safe, deleteHeapDump);
    }

    // ── 캐시 조회 / 삭제 ─────────────────────────────────────────

    public HeapAnalysisResult getCachedResult(String filename) {
        String safe = new File(filename).getName();
        HeapAnalysisResult cached = resultCache.get(safe);
        if (cached != null) {
            syncGzFileSize(cached, safe);
            return cached;
        }

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
                    syncGzFileSize(r, safe);
                    resultCache.put(safe, r);
                    return r;
                }
            } catch (Exception e) {
                logger.warn("Failed to read saved result {}: {}", safe, e.getMessage());
            }
        }
        return null;
    }

    /** gz 파일이 있고 아직 fileSize에 반영되지 않은 경우 originalFileSize/fileSize를 동기화. */
    private void syncGzFileSize(HeapAnalysisResult r, String safe) {
        if (r.getFileSize() <= 0) return;
        File gzFile = new File(config.getDumpFilesDirectory(), safe + ".gz");
        if (gzFile.exists() && r.getOriginalFileSize() <= r.getFileSize()) {
            r.setOriginalFileSize(r.getFileSize());
            r.setFileSize(gzFile.length());
        }
    }

    public void clearCache(String filename) {
        String safe = new File(filename).getName();
        resultCache.remove(safe);
        domRefSidecarCache.remove(safe);
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

    /** 진행 중(실행+큐 대기)인 모든 파일명 목록 (목록 페이지의 '분석 중' 애니메이션 버튼용) */
    public java.util.List<String> getInProgressFilenames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        activeTasks.forEach((name, task) -> {
            if (task != null && !task.isDone()) names.add(name);
        });
        return names;
    }

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

    public boolean isInProgress(String filename) {
        String safe = new File(filename).getName();
        Future<?> task = activeTasks.get(safe);
        return task != null && !task.isDone();
    }

    public AnalysisProgress getLastProgress(String filename) {
        return lastProgressCache.get(new File(filename).getName());
    }

    public java.util.List<String> getRecentLogs(String filename) {
        java.util.concurrent.ConcurrentLinkedDeque<String> deque =
                logCache.get(new File(filename).getName());
        return deque == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(deque);
    }

    public Future<?> analyzeWithProgress(String filename, SseEmitter emitter) {
        final String safe = new File(filename).getName();

        // 동일 파일 중복 분석 방지: 이미 실행 중인 태스크가 있으면 즉시 ALREADY_ANALYZING 전송 후 종료
        Future<?> existingTask = activeTasks.get(safe);
        if (existingTask != null && !existingTask.isDone()) {
            logger.info("[Analysis] Duplicate blocked: {} is already in progress", safe);
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(objectMapper.writeValueAsString(AnalysisProgress.alreadyAnalyzing(safe))));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
            return null;  // cancelTask 클로저의 null 체크로 기존 태스크 취소 방지
        }

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
                // Observer 로그 캐시 리셋 (새 분석 시작 시 이전 로그 제거)
                logCache.put(safe, new java.util.concurrent.ConcurrentLinkedDeque<>());

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

                // OOM 감지 시: 힙의 OutOfMemoryError 인스턴스에서 실제 throw 된 정확한 종류 추출
                // (preallocated 템플릿 배제 — 스레드 local 참조와 교집합). dumpFile(tmp) 은 finally 전까지 존재.
                // <init> 프레임이 없는 종류(네이티브 스레드 생성 실패 / 다이렉트 버퍼)도 잡기 위해
                // OOM-prone 프레임이 있는 스레드가 있으면 함께 실행.
                if (result.getOomThreadCount() > 0 || hasOomProneThread(result)) {
                    sendProgress(emitter, AnalysisProgress.parsing(safe, 99, "OOM 종류 정밀 분석 중..."));
                    enrichThrownOomMessage(result, dumpFile, resultDir);
                }

                // System Properties 추출 (JDK/OS/WAS 식별·버전). dumpFile(tmp)·인덱스 존재 구간.
                sendProgress(emitter, AnalysisProgress.parsing(safe, 99, "System Properties 추출 중..."));
                enrichSystemProperties(result, dumpFile, resultDir);

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

                    // Dominator Refs 사전계산 (백그라운드 직렬). 압축 후 호출 → .gz 해제 경로 결정적.
                    precomputeDominatorRefsAsync(safe);

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
                    // e.getMessage()가 null인 예외(NPE, StackOverflowError 등)는 클래스명으로 대체
                    String errMsg = (e.getMessage() != null && !e.getMessage().isEmpty())
                            ? e.getMessage()
                            : e.getClass().getSimpleName() + " (no message)";
                    sendProgress(emitter, AnalysisProgress.error(safe, errMsg));

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
                        errorResult.setErrorMessage(errMsg);
                        errorResult.setAnalysisTime(System.currentTimeMillis() - startTime);

                        // MAT CLI 로그가 있으면 에러 결과에도 포함
                        File errorResultDir = resultDirectory(safe);
                        File matLogFile = new File(errorResultDir, MAT_LOG_FILE);
                        if (matLogFile.exists()) {
                            try {
                                errorResult.setMatLog(new String(Files.readAllBytes(matLogFile.toPath()),
                                        java.nio.charset.StandardCharsets.UTF_8));
                            } catch (Exception logEx) {
                                logger.warn("[Analysis] Failed to read mat.log for error result: {}",
                                        logEx.getMessage() != null ? logEx.getMessage() : logEx.getClass().getSimpleName());
                            }
                        }
                        Files.createDirectories(errorResultDir.toPath());
                        resultCache.put(safe, errorResult);
                        saveResultToDisk(errorResult, errorResultDir);
                        saveAnalysisToDb(errorResult);
                        analysisSuccess = true; // tmp 파일 삭제 방지 (이미 이동 완료)
                        logger.info("[Analysis] Error result saved for: {}", safe);
                    } catch (Exception saveEx) {
                        String saveErrMsg = saveEx.getMessage() != null ? saveEx.getMessage() : saveEx.getClass().getSimpleName();
                        logger.warn("[Analysis] Failed to save error result for {}: {}", safe, saveErrMsg);
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
                deadEmitters.remove(emitter);  // dead-emitter 집합 누수 방지
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
        cmd.add("-command=dominator_tree -groupBy NONE");
        cmd.add("org.eclipse.mat.api:query");

        logger.info("Running MAT CLI: {}", String.join(" ", cmd));
        sendProgress(emitter, AnalysisProgress.step(filename, 15, "MAT CLI 실행 중..."));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MALLOC_ARENA_MAX", "2");
        pb.directory(resultDir);
        pb.redirectErrorStream(true);

        // 메모리 보호: 분석 MAT 도 동시 MAT 게이트를 점유(precompute/lazy 와 공용 한도)
        // 진단(B-3): 슬롯 대기 시간과 MAT 실제 파싱(서브프로세스) wall time 을 분리 측정해
        //          "파싱이 느림" 회귀의 원인(슬롯 대기 vs 연산/메모리 thrashing)을 구분 가능하게 한다.
        logger.info("[MAT Concurrency] 분석 '{}' MAT spawn 직전 — 한도={}, 가용슬롯={}, 가용메모리={}MB",
                filename, matMaxConcurrent, matSlots.availablePermits(), hostAvailableRamMb());
        if (matSlots.availablePermits() <= 0) {
            logger.info("[MAT Concurrency] 분석 '{}' 슬롯 대기 — 동시 MAT 한도({}) 도달", filename, matMaxConcurrent);
        }
        long slotWaitStart = System.currentTimeMillis();
        matSlots.acquire();
        long slotWaitMs = System.currentTimeMillis() - slotWaitStart;
        long matSpawnStart = System.currentTimeMillis();
        try {
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
                        pct[0] = Math.max(pct[0], 62);
                        logger.info("[MAT CLI] Report phase: suspects (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "suspects", "Leak Suspects 리포트 생성 중..."));
                        continue;
                    } else if (line.startsWith("Subtask: Single Query") && phaseRank("dominator") > curRank) {
                        phase[0] = "dominator";
                        pct[0] = Math.max(pct[0], 72);
                        logger.info("[MAT CLI] Report phase: dominator (line {})", lineCount[0]);
                        sendProgress(emitter, AnalysisProgress.reportLog(filename, pct[0], line,
                                "dominator", "Dominator Tree 리포트 생성 중..."));
                        continue;
                    }

                    // 현재 단계별 최대 진행률 제한
                    int maxPct;
                    switch (phase[0]) {
                        case "overview":       maxPct = 50; break;
                        case "top_components": maxPct = 62; break;
                        case "suspects":       maxPct = 72; break;
                        case "dominator":      maxPct = 80; break;
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

        // 진단(B-3): 슬롯 대기 vs MAT 서브프로세스 실제 파싱 시간 분리. 가용메모리도 함께 기록.
        long matWallMs = System.currentTimeMillis() - matSpawnStart;
        logger.info("[MAT CLI] 파싱 분리 측정 — 슬롯 대기={}ms, MAT 서브프로세스={}ms, 종료후 가용메모리={}MB, 파일={}",
                slotWaitMs, matWallMs, hostAvailableRamMb(), filename);

        sendProgress(emitter, AnalysisProgress.step(filename, 82,
                "MAT CLI 완료 (exit=" + exitCode + ")"));
        return matOutput;
        } finally {
            matSlots.release();
        }
    }

    /**
     * 단일 MAT 쿼리를 실행해 `<base>_Query.zip` 을 생성한다 (lazy on-demand 용).
     *
     * MAT batch CLI 가 단일 invocation 내 다중 `org.eclipse.mat.api:query` 보고서를
     * 동일 파일명 (`<base>_Query.zip`) 으로 덮어쓰는 제약 때문에, top 50 사전 추출은
     * 비현실적 → 클릭 시점 endpoint 가 1 회 호출 당 1 쿼리만 실행.
     *
     * 인덱스(.index) 파일이 dataDir 에 이미 있고 hprof 도 같이 있으면 MAT 는
     * 재파싱 없이 곧바로 쿼리 실행 → 보통 2~5s.
     */
    public String runMatSingleQuery(String dumpPath, File workDir, String queryWithArgs, long timeoutSeconds)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("sh");
        cmd.add(config.getMatCliPath());
        cmd.add(dumpPath);
        if (keepUnreachableObjects) cmd.add("-keep_unreachable_objects");
        cmd.add("-command=" + queryWithArgs);
        cmd.add("org.eclipse.mat.api:query");
        logger.info("[MAT Lazy] Query: {} (workDir={})", queryWithArgs, workDir.getName());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MALLOC_ARENA_MAX", "2");
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        // 메모리 보호: 동시 MAT 한도 게이트(분석/precompute/lazy 공용). 한도 도달 시 슬롯이 빌 때까지 대기.
        if (matSlots.availablePermits() <= 0) {
            logger.info("[MAT Concurrency] '{}' 슬롯 대기 — 동시 MAT 한도({}) 도달", queryWithArgs, matMaxConcurrent);
        }
        matSlots.acquire();
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (IOException ignored) {}
            }, "mat-lazy-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            reader.join(1000);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("MAT 쿼리 타임아웃 (" + timeoutSeconds + "s): " + queryWithArgs);
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("[MAT Lazy] Query exited with code {} for '{}'", exitCode, queryWithArgs);
            }
            return output.toString();
        } finally {
            matSlots.release();
        }
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
            AnalysisHistoryEntity existing = analysisHistoryRepository.findByFilename(result.getFilename()).orElse(null);
            AnalysisHistoryEntity entity = existing != null ? existing : new AnalysisHistoryEntity();
            // 재분석 식별: 기존 SUCCESS 레코드가 있고 analyzed_at 이 이미 채워져 있으면 보존
            boolean preserveAnalyzedAt = existing != null
                    && "SUCCESS".equals(existing.getStatus())
                    && existing.getAnalyzedAt() != null;
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
            if (result.getDumpCreationTime() != null) entity.setDumpCreationTime(result.getDumpCreationTime());
            if (serverId != null) entity.setServerId(serverId);
            if (serverName != null) entity.setServerName(serverName);
            if (uploadedBy != null) entity.setUploadedBy(uploadedBy);
            if (!preserveAnalyzedAt) {
                entity.setAnalyzedAt(java.time.LocalDateTime.now());
            }
            analysisHistoryRepository.save(entity);
            logger.info("[DB] Analysis history saved for: {}", result.getFilename());
        } catch (Exception e) {
            logger.warn("[DB] Failed to save analysis history for {}: {}", result.getFilename(), e.getMessage());
        }
    }

    /**
     * 업로드 완료 직후 analysis_history 에 NOT_ANALYZED 레코드를 삽입해 ID(순번)를 채번한다.
     * 이후 분석이 실행되면 saveAnalysisToDb() 가 동일 레코드를 findByFilename 으로 찾아 update 한다.
     * 이미 레코드가 존재하면(재업로드 방지 로직 우회 등) 삽입하지 않고 기존 ID 를 유지한다.
     */
    public void saveUploadRecord(String filename, long fileSize, String uploadedBy) {
        try {
            if (analysisHistoryRepository.findByFilename(filename).isPresent()) return;
            AnalysisHistoryEntity entity = new AnalysisHistoryEntity();
            entity.setFilename(filename);
            entity.setStatus("NOT_ANALYZED");
            entity.setFileSize(fileSize);
            entity.setFileDeleted(false);
            if (uploadedBy != null) entity.setUploadedBy(uploadedBy);
            analysisHistoryRepository.save(entity);
            logger.info("[DB] Upload record saved (NOT_ANALYZED) for: {}", filename);
        } catch (Exception e) {
            logger.warn("[DB] Failed to save upload record for {}: {}", filename, e.getMessage());
        }
    }

    /**
     * analysis_history 의 server_name(= 덤프 출처 호스트명)을 조회한다.
     * SSH 원격 전송 시 자동 기록되며, 수동 업로드는 null. 미식별/레코드 없으면 빈 문자열.
     */
    public String getAnalysisServerName(String filename) {
        try {
            return analysisHistoryRepository.findByFilename(filename)
                    .map(AnalysisHistoryEntity::getServerName)
                    .filter(s -> s != null && !s.isEmpty())
                    .orElse("");
        } catch (Exception e) {
            logger.debug("[DB] getAnalysisServerName failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    /**
     * 호스트명(server_name) 수동 편집 — 주로 수동 업로드 덤프에 사용.
     * 빈 값/null 이면 미지정(null)으로 초기화. server_name 컬럼 길이(100) 초과 시 절단.
     * @return 저장 후 정규화된 호스트명(미지정이면 ""). 레코드 없으면 null.
     */
    @org.springframework.transaction.annotation.Transactional
    public String updateAnalysisServerName(String filename, String hostname) {
        AnalysisHistoryEntity e = analysisHistoryRepository.findByFilename(filename).orElse(null);
        if (e == null) return null;
        String normalized = (hostname == null) ? "" : hostname.trim();
        if (normalized.length() > 100) normalized = normalized.substring(0, 100);
        e.setServerName(normalized.isEmpty() ? null : normalized);
        analysisHistoryRepository.save(e);
        logger.info("[DB] Hostname updated for {}: '{}'", filename, normalized);
        return normalized;
    }

    /**
     * JEUS 인스턴스(jeus.server.name) 수동 편집값 조회. 미설정/레코드 없으면 빈 문자열.
     * 자동 식별값(System Properties)으로의 폴백은 호출자(컨트롤러)가 처리한다.
     */
    public String getAnalysisJeusInstance(String filename) {
        try {
            return analysisHistoryRepository.findByFilename(filename)
                    .map(AnalysisHistoryEntity::getJeusInstance)
                    .filter(s -> s != null && !s.isEmpty())
                    .orElse("");
        } catch (Exception e) {
            logger.debug("[DB] getAnalysisJeusInstance failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    /** JEUS 도메인(jeus.domain.name) 수동 편집값 조회. 미설정/레코드 없으면 빈 문자열. */
    public String getAnalysisJeusDomain(String filename) {
        try {
            return analysisHistoryRepository.findByFilename(filename)
                    .map(AnalysisHistoryEntity::getJeusDomain)
                    .filter(s -> s != null && !s.isEmpty())
                    .orElse("");
        } catch (Exception e) {
            logger.debug("[DB] getAnalysisJeusDomain failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }

    /**
     * JEUS 인스턴스/도메인 수동 편집 — null 인 필드는 기존값 유지, 빈 문자열이면 초기화(자동값 폴백).
     * 컬럼 길이(100) 초과 시 절단. @return 저장 후 수동값 맵(instance/domain, 미설정이면 ""). 레코드 없으면 null.
     */
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, String> updateAnalysisJeus(String filename, String instance, String domain) {
        AnalysisHistoryEntity e = analysisHistoryRepository.findByFilename(filename).orElse(null);
        if (e == null) return null;
        if (instance != null) {
            String n = instance.trim();
            if (n.length() > 100) n = n.substring(0, 100);
            e.setJeusInstance(n.isEmpty() ? null : n);
        }
        if (domain != null) {
            String n = domain.trim();
            if (n.length() > 100) n = n.substring(0, 100);
            e.setJeusDomain(n.isEmpty() ? null : n);
        }
        analysisHistoryRepository.save(e);
        java.util.Map<String, String> r = new java.util.LinkedHashMap<>();
        r.put("instance", e.getJeusInstance() == null ? "" : e.getJeusInstance());
        r.put("domain", e.getJeusDomain() == null ? "" : e.getJeusDomain());
        logger.info("[DB] JEUS meta updated for {}: instance='{}' domain='{}'",
                filename, r.get("instance"), r.get("domain"));
        return r;
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
        c.setClassLoaderCount(r.getClassLoaderCount()); c.setGcRootCount(r.getGcRootCount());
        c.setAnalysisTime(r.getAnalysisTime());   c.setAnalysisStatus(r.getAnalysisStatus());
        c.setErrorMessage(r.getErrorMessage());
        c.setOverviewHtml(r.getOverviewHtml());   c.setTopComponentsHtml(r.getTopComponentsHtml());
        c.setSuspectsHtml(r.getSuspectsHtml());   c.setMatLog(null);
        c.setHistogramHtml(r.getHistogramHtml());
        c.setThreadOverviewHtml(r.getThreadOverviewHtml());
        c.setHistogramEntries(r.getHistogramEntries());
        c.setThreadInfos(r.getThreadInfos());
        c.setTotalHistogramClasses(r.getTotalHistogramClasses());
        c.setSystemProperties(r.getSystemProperties());
        c.setOomDetailMessage(r.getOomDetailMessage());
        c.setOriginalFileSize(r.getOriginalFileSize());
        c.setComponentDetailParsedMap(r.getComponentDetailParsedMap());
        c.setDominatorTreeEntries(r.getDominatorTreeEntries());
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
        r.setClassLoaderCount(parsed.getClassLoaderCount());
        r.setGcRootCount(parsed.getGcRootCount());
        r.setDumpCreationTime(parseDumpCreationTime(parsed.getDumpDate(), parsed.getDumpTime()));
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
        r.setDominatorTreeEntries(parsed.getDominatorTreeEntries());

        // .threads 파일 로드
        loadThreadStacksText(r);

        return r;
    }

    // ── 경로 / 유틸리티 ──────────────────────────────────────────

    /**
     * MAT System Overview 의 Date("2026. 5. 29.")와 Time("오후 6시 18분 53초 GMT+9")을
     * "2026-05-29 18:18:53" 형태로 조합한다. 파싱 실패 시 원본 문자열을 그대로 이어 붙인다.
     */
    private static String parseDumpCreationTime(String date, String time) {
        if (date == null && time == null) return null;

        String parsedDate = null;
        if (date != null) {
            try {
                // "2026. 5. 29." → ["2026", "5", "29"]
                String[] parts = date.replaceAll("\\.$", "").split("\\.\\s*");
                if (parts.length == 3) {
                    int y = Integer.parseInt(parts[0].trim());
                    int m = Integer.parseInt(parts[1].trim());
                    int d = Integer.parseInt(parts[2].trim());
                    parsedDate = String.format("%04d-%02d-%02d", y, m, d);
                }
            } catch (Exception e) { /* fall through */ }
            if (parsedDate == null) parsedDate = date.trim();
        }

        String parsedTime = null;
        if (time != null) {
            try {
                boolean pm = time.contains("오후");
                Matcher m = Pattern.compile("(\\d+)시\\s*(\\d+)분\\s*(\\d+)초").matcher(time);
                if (m.find()) {
                    int h   = Integer.parseInt(m.group(1));
                    int min = Integer.parseInt(m.group(2));
                    int sec = Integer.parseInt(m.group(3));
                    if (pm && h != 12) h += 12;
                    else if (!pm && h == 12) h = 0;
                    parsedTime = String.format("%02d:%02d:%02d", h, min, sec);
                }
            } catch (Exception e) { /* fall through */ }
            if (parsedTime == null) parsedTime = time.trim();
        }

        if (parsedDate != null && parsedTime != null) return parsedDate + " " + parsedTime;
        return parsedDate != null ? parsedDate : parsedTime;
    }

    public File resultDirectoryPublic(String filename) {
        return fileMgmt.resultDirectory(filename);
    }

    /** 컨트롤러에서 임시 디렉토리 정리 시 사용 (내부 deleteDirectoryRecursively 위임). */
    public void deleteDirectoryPublic(File dir) {
        deleteDirectoryRecursively(dir);
    }

    // ── Dominator Refs lazy/precompute 공용 헬퍼 ────────────────────────────────

    /** 덤프 파일명에서 확장자(.hprof/.bin/.dump[.gz])를 제거한 base 이름. */
    public String dumpBaseName(String safe) {
        return safe.replaceAll("\\.(hprof|bin|dump)(\\.gz)?$", "");
    }

    /**
     * MAT lazy 쿼리용 원본 hprof 확보: dumpfiles > tmp > .gz(1회 해제) 순.
     * .gz 만 있으면 tmp/{base}.hprof 로 해제하고 그 경로를 반환(이후 lazy 클릭이 재사용).
     * 못 찾으면 null.
     */
    public File resolveSourceHprof(String safe) throws IOException {
        String base = dumpBaseName(safe);
        String dumpfiles = config.getHeapDumpDirectory() + File.separator + "dumpfiles";
        File original = new File(dumpfiles, safe);
        if (original.exists()) return original;
        File tmpHprof = new File(config.getHeapDumpDirectory() + File.separator + TMP_DIR_NAME, base + ".hprof");
        if (tmpHprof.exists() && tmpHprof.length() > 0) return tmpHprof;
        File gz = new File(dumpfiles, safe + ".gz");
        if (gz.exists()) {
            logger.info("[MAT Lazy] Decompressing {} → {} (1-time)", gz.getName(), tmpHprof.getName());
            decompressGzTo(gz, tmpHprof);
            return tmpHprof;
        }
        return null;
    }

    /**
     * precompute 전용 hprof 확보. 분석의 공유 작업본(tmp/{base}.hprof)은 분석 finally 가 곧 삭제하므로
     * precompute 가 그것을 symlink 하면 도중에 dangling 되어 MAT 가 exit 13 으로 실패한다(레이스).
     * 따라서 압축 원본이 있으면 그대로(안정), .gz 만 있으면 **precompute 전용 경로**로 해제해 격리한다.
     * 반환된 전용 해제본은 호출자가 사용 후 삭제한다(원본/공유 tmp 는 삭제 금지).
     */
    public File resolveSourceHprofIsolated(String safe) throws IOException {
        String dumpfiles = config.getHeapDumpDirectory() + File.separator + "dumpfiles";
        File original = new File(dumpfiles, safe);
        if (original.exists()) return original; // 압축 비활성 — 원본 안정적, 삭제 안 함
        String base = dumpBaseName(safe);
        File gz = new File(dumpfiles, safe + ".gz");
        if (gz.exists()) {
            // 공유 tmp/{base}.hprof 와 분리된 전용 경로(분석 cleanup·lazy 와 충돌 없음)
            File priv = new File(config.getHeapDumpDirectory() + File.separator + TMP_DIR_NAME,
                                 base + ".precompute.hprof");
            logger.info("[DomRefs] precompute 전용 해제 {} → {}", gz.getName(), priv.getName());
            decompressGzTo(gz, priv);
            return priv;
        }
        return null;
    }

    private void decompressGzTo(File gz, File dest) throws IOException {
        if (dest.getParentFile() != null && !dest.getParentFile().exists()) dest.getParentFile().mkdirs();
        try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(gz.toPath()));
             OutputStream os = Files.newOutputStream(dest.toPath())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = gis.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    /**
     * lazy 워킹 디렉토리에 data/{base}/ 의 index/threads + 원본 hprof 를 연결한다.
     * index/threads 는 symlink(플래그 OFF 또는 실패 시 copy 폴백), hprof 는 항상 symlink 우선.
     * 매 클릭 수 GB copy 를 제거하는 핵심 최적화. 반환값은 워킹 디렉토리의 {base}.hprof.
     */
    public File linkMatInputs(File workDir, File resultDir, File sourceHprof, String base) throws IOException {
        if (!workDir.exists()) workDir.mkdirs();
        boolean symlinkIndex = config.isDominatorRefsSymlinkIndex();
        File[] idxFiles = resultDir.listFiles((d, n) ->
                n.startsWith(base + ".") && (n.endsWith(".index") || n.endsWith(".threads")));
        long oldestIndexMtime = Long.MAX_VALUE;
        if (idxFiles != null) {
            for (File f : idxFiles) {
                linkOrCopy(f, new File(workDir, f.getName()), symlinkIndex);
                if (f.getName().endsWith(".index")) {
                    oldestIndexMtime = Math.min(oldestIndexMtime, f.lastModified());
                }
            }
        }
        File workHprof = new File(workDir, base + ".hprof");
        linkOrCopy(sourceHprof, workHprof, true);
        alignHprofMtimeToIndex(sourceHprof, oldestIndexMtime);
        return workHprof;
    }

    /**
     * MAT reparse 방지: MAT 는 hprof 의 mtime 이 index 파일보다 최신이면 "newer than index" 로 판단해
     * 전체 힙(수십만~백만 객체)을 재파싱한다. .gz 1회 해제본은 해제 시각(=현재)이 mtime 이라 항상
     * index 보다 최신 → lazy/precompute 매 호출이 30~60초 reparse(또는 symlink 인덱스 쓰기 충돌로 exit 13).
     * hprof mtime 을 index 보다 오래되게 맞추면 MAT 가 기존 인덱스를 재사용(reopen, ~4초)한다.
     * 이미 index 보다 오래된 원본(dumpfiles)은 조건 미충족 → 손대지 않음.
     */
    private void alignHprofMtimeToIndex(File hprof, long oldestIndexMtime) {
        if (oldestIndexMtime == Long.MAX_VALUE || hprof == null || !hprof.exists()) return;
        if (hprof.lastModified() <= oldestIndexMtime) return; // 이미 인덱스보다 오래됨 — reparse 안 됨
        long target = Math.max(1000L, oldestIndexMtime - 60_000L);
        if (hprof.setLastModified(target)) {
            logger.info("[MAT Lazy] hprof mtime 을 index 이전으로 조정 — reparse 방지: {}", hprof.getName());
        } else {
            logger.warn("[MAT Lazy] hprof mtime 조정 실패(권한?) — reparse 발생 가능: {}", hprof.getName());
        }
    }

    private void linkOrCopy(File src, File dest, boolean preferSymlink) throws IOException {
        if (dest.exists()) return;
        if (preferSymlink) {
            try {
                Files.createSymbolicLink(dest.toPath(), src.toPath());
                return;
            } catch (IOException | UnsupportedOperationException e) {
                logger.warn("[MAT Lazy] symlink 실패, copy 폴백: {} ({})", dest.getName(), e.getMessage());
            }
        }
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ── Dominator Refs 사전계산 + 사이드카 영속화 ───────────────────────────────

    /** 분석 완료 직후 호출. 백그라운드(직렬)로 Top-N refs 를 사이드카에 사전계산. */
    public void precomputeDominatorRefsAsync(String safe) {
        if (!config.isDominatorRefsPrecompute()) return;
        try {
            domRefPrecomputeExecutor.submit(() -> {
                try {
                    doPrecomputeDominatorRefs(safe);
                } catch (Exception e) {
                    logger.warn("[DomRefs] precompute failed for {}: {}", safe,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            logger.debug("[DomRefs] precompute submit rejected for {}", safe);
        }
    }

    private void doPrecomputeDominatorRefs(String safe) throws Exception {
        long start    = System.currentTimeMillis();
        long budgetMs = Math.max(10L, config.getDominatorRefsPrecomputeBudgetSeconds()) * 1000L;
        int  topN     = Math.max(1, config.getDominatorRefsPrecomputeTopN());
        int  cap      = Math.max(1, config.getDominatorRefsPrecomputeCap());

        HeapAnalysisResult r = getCachedResult(safe);
        if (r == null || r.getDominatorTreeEntries() == null || r.getDominatorTreeEntries().isEmpty()) return;

        File resultDir = resultDirectory(safe);
        if (!resultDir.exists()) return;
        File sidecar = new File(resultDir, "dominator-refs.json");
        if (sidecar.exists()) {
            logger.info("[DomRefs] sidecar already present for {}, skip precompute", safe);
            return;
        }

        // 분석 finally 가 삭제하는 공유 tmp 대신 격리된 전용 hprof 사용(레이스→exit 13 방지)
        File sourceHprof = resolveSourceHprofIsolated(safe);
        if (sourceHprof == null) {
            logger.warn("[DomRefs] precompute skipped — hprof not found for {}", safe);
            return;
        }
        // 전용 해제본(.precompute.hprof)만 사후 삭제 대상. 압축 원본(dumpfiles)은 보존.
        File privateHprof = sourceHprof.getName().endsWith(".precompute.hprof") ? sourceHprof : null;
        String base = dumpBaseName(safe);
        File workDir = new File(resultDir, ".precompute_" + System.currentTimeMillis());
        File workHprof;
        try {
            workHprof = linkMatInputs(workDir, resultDir, sourceHprof, base);
        } catch (Exception e) {
            logger.warn("[DomRefs] precompute work dir setup failed for {}: {}", safe, e.getMessage());
            deleteDirectoryRecursively(workDir);
            if (privateHprof != null) privateHprof.delete();
            return;
        }

        Map<String, Object> refsMap = new LinkedHashMap<>();
        int done = 0;
        try {
            List<com.heapdump.analyzer.model.DominatorTreeEntry> entries = r.getDominatorTreeEntries();
            int limit = Math.min(topN, entries.size());
            File zip = new File(workDir, base + "_Query.zip");
            for (int i = 0; i < limit; i++) {
                if (System.currentTimeMillis() - start > budgetMs) {
                    logger.info("[DomRefs] precompute budget({}s) exceeded for {} after {} entries",
                            config.getDominatorRefsPrecomputeBudgetSeconds(), safe, done);
                    break;
                }
                com.heapdump.analyzer.model.DominatorTreeEntry e = entries.get(i);
                String addr = e.getObjectAddress();
                if (addr == null || !addr.matches("0x[0-9a-fA-F]+")) continue;

                // 동시성/메모리 보호는 runMatSingleQuery 내부의 동적 matSlots 게이트가 담당한다.
                // 저용량 호스트(슬롯 1)에서는 분석 MAT 가 슬롯을 점유하는 동안 아래 쿼리가 대기(양보)하고,
                // 고용량 호스트(슬롯 N)에서는 MAT -Xmx 한도 내에서 분석과 동시 실행된다.
                List<com.heapdump.analyzer.model.DominatorRefEntry> incoming = Collections.emptyList();
                List<com.heapdump.analyzer.model.DominatorRefEntry> outgoing = Collections.emptyList();
                try {
                    runMatSingleQuery(workHprof.getAbsolutePath(), workDir, "path2gc " + addr, 90L);
                    if (zip.exists()) { incoming = parser.parseRefZipPath2gc(zip, cap); zip.delete(); }
                } catch (Exception qe) {
                    logger.debug("[DomRefs] path2gc failed for {} addr {}: {}", safe, addr, qe.getMessage());
                }
                try {
                    runMatSingleQuery(workHprof.getAbsolutePath(), workDir, "show_retained_set " + addr, 90L);
                    if (zip.exists()) { outgoing = parser.parseRefZipRetained(zip, cap); zip.delete(); }
                } catch (Exception qe) {
                    logger.debug("[DomRefs] show_retained_set failed for {} addr {}: {}", safe, addr, qe.getMessage());
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("incoming", incoming);
                one.put("outgoing", outgoing);
                refsMap.put(addr, one);
                done++;
            }
        } finally {
            deleteDirectoryRecursively(workDir);
            if (privateHprof != null && privateHprof.exists() && privateHprof.delete()) {
                logger.debug("[DomRefs] precompute 전용 해제본 삭제: {}", privateHprof.getName());
            }
        }

        // 안전장치: 모든 항목이 incoming/outgoing 둘 다 빈 목록이면 MAT 쿼리 전반 실패(예: reparse/exit 13)
        // 신호 → 사이드카를 쓰지 않는다. 빈 사이드카는 reconnect 시 정상 동작하는 lazy 경로를 가려서
        // "참조 없음" 으로 잘못 표시되는 회귀를 유발하므로, 차라리 lazy 폴백을 유지한다.
        boolean anyData = false;
        for (Object v : refsMap.values()) {
            Map<?, ?> m = (Map<?, ?>) v;
            List<?> in  = (List<?>) m.get("incoming");
            List<?> out = (List<?>) m.get("outgoing");
            if ((in != null && !in.isEmpty()) || (out != null && !out.isEmpty())) { anyData = true; break; }
        }
        if (!refsMap.isEmpty() && !anyData) {
            logger.warn("[DomRefs] precompute produced only empty refs for {} ({} entries) — skip sidecar (lazy 폴백 유지)",
                    safe, done);
            return;
        }

        // 사이드카 atomic write (temp → move)
        Map<String, Object> sidecarData = new LinkedHashMap<>();
        sidecarData.put("version", 1);
        sidecarData.put("generatedAt", java.time.LocalDateTime.now().toString());
        sidecarData.put("topN", topN);
        sidecarData.put("capPerList", cap);
        sidecarData.put("refs", refsMap);
        File tmp = new File(resultDir, "dominator-refs.json.tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, sidecarData);
        try {
            Files.move(tmp.toPath(), sidecar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.move(tmp.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        domRefSidecarCache.remove(safe); // 다음 조회 시 새로 로드
        logger.info("[DomRefs] precompute done for {}: {} entries in {}ms",
                safe, done, System.currentTimeMillis() - start);
    }

    /**
     * 사이드카에서 특정 주소의 incoming/outgoing 반환 (사전계산 HIT 시 MAT 호출 0회).
     * 없으면 null → 호출자가 lazy 경로로 폴백. 파일별 파싱 결과는 메모리 캐시.
     */
    public Map<String, List<com.heapdump.analyzer.model.DominatorRefEntry>> getPrecomputedRefs(String safe, String address) {
        Map<String, Map<String, List<com.heapdump.analyzer.model.DominatorRefEntry>>> all =
                domRefSidecarCache.computeIfAbsent(safe, this::loadDominatorRefsSidecar);
        if (all == null || all.isEmpty()) return null;
        return all.get(address);
    }

    private Map<String, Map<String, List<com.heapdump.analyzer.model.DominatorRefEntry>>> loadDominatorRefsSidecar(String safe) {
        File sidecar = new File(resultDirectory(safe), "dominator-refs.json");
        if (!sidecar.exists()) return null; // computeIfAbsent: null → 미저장(다음 호출 재시도)
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(sidecar);
            com.fasterxml.jackson.databind.JsonNode refs = root.get("refs");
            if (refs == null || !refs.isObject()) return Collections.emptyMap();
            com.fasterxml.jackson.core.type.TypeReference<List<com.heapdump.analyzer.model.DominatorRefEntry>> listType =
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.heapdump.analyzer.model.DominatorRefEntry>>() {};
            Map<String, Map<String, List<com.heapdump.analyzer.model.DominatorRefEntry>>> out = new HashMap<>();
            Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = refs.fields();
            boolean anyData = false;
            while (it.hasNext()) {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> en = it.next();
                com.fasterxml.jackson.databind.JsonNode node = en.getValue();
                List<com.heapdump.analyzer.model.DominatorRefEntry> in =
                        node.has("incoming") ? objectMapper.convertValue(node.get("incoming"), listType) : null;
                List<com.heapdump.analyzer.model.DominatorRefEntry> outv =
                        node.has("outgoing") ? objectMapper.convertValue(node.get("outgoing"), listType) : null;
                if ((in != null && !in.isEmpty()) || (outv != null && !outv.isEmpty())) anyData = true;
                Map<String, List<com.heapdump.analyzer.model.DominatorRefEntry>> m = new HashMap<>();
                m.put("incoming", in != null ? in : Collections.emptyList());
                m.put("outgoing", outv != null ? outv : Collections.emptyList());
                out.put(en.getKey(), m);
            }
            // 자가 치유: 구버전이 남긴 전부-빈 사이드카(예: reparse 실패로 생성)는 무효 취급 → lazy 폴백.
            // 빈 사이드카가 정상 lazy 데이터를 가리는 회귀를 런타임에서 차단(수동 삭제 불필요).
            if (!out.isEmpty() && !anyData) {
                logger.warn("[DomRefs] sidecar for {} is all-empty — 무효 처리, lazy 폴백", safe);
                return Collections.emptyMap();
            }
            logger.info("[DomRefs] sidecar loaded for {}: {} addresses", safe, out.size());
            return out;
        } catch (Exception e) {
            logger.warn("[DomRefs] sidecar load failed for {}: {}", safe, e.getMessage());
            return Collections.emptyMap();
        }
    }

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
     * SSE 진행 전송. 진행 상황은 항상 Observer 캐시(lastProgressCache/logCache)에 먼저 기록되므로
     * emitter 가 죽어도(페이지 이탈) 재진입/관찰 경로에서 진행 상황을 계속 볼 수 있다.
     * 클라이언트 disconnect 로 전송이 실패하면 해당 emitter 를 deadEmitters 에 담아 이후 전송만 스킵하고
     * 분석 스레드는 인터럽트하지 않는다 → 백그라운드로 계속 진행. (명시적 취소는 cancelAnalysis 로만.)
     */
    private void sendProgress(SseEmitter emitter, AnalysisProgress progress) {
        // 명시적 취소(cancelAnalysis → cancel(true)) 시에만 인터럽트되므로 조기 종료.
        if (Thread.currentThread().isInterrupted()) return;

        // ── Observer 캐시 업데이트 (ALREADY_ANALYZING은 실제 진행 상황이 아니므로 제외) ──
        if (progress.getStatus() != AnalysisProgress.Status.ALREADY_ANALYZING
                && progress.getFilename() != null) {
            lastProgressCache.put(progress.getFilename(), progress);
            if (progress.getLogLine() != null) {
                java.util.concurrent.ConcurrentLinkedDeque<String> deque =
                        logCache.computeIfAbsent(progress.getFilename(),
                                k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
                deque.addLast(progress.getLogLine());
                while (deque.size() > LOG_CACHE_MAX) deque.pollFirst();
            }
        }

        // 이미 죽은 emitter 로는 전송 시도하지 않음 (캐시는 위에서 이미 갱신됨)
        if (emitter == null || deadEmitters.contains(emitter)) return;

        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(objectMapper.writeValueAsString(progress)));
        } catch (Exception e) {
            // SSE 전송 실패 = 클라이언트 disconnect. 분석은 백그라운드로 계속 진행, 전송만 중단.
            deadEmitters.add(emitter);
            logger.info("[SSE] Client disconnected ({}), analysis continues in background", e.getClass().getSimpleName());
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
            case "dominator":      return 4;
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

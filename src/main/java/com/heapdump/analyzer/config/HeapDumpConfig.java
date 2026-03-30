package com.heapdump.analyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/**
 * Heap Dump Analyzer 설정
 *
 * application.properties 또는 환경 변수로 재정의 가능:
 *   heapdump.directory=/opt/heapdumps
 *   mat.cli.path=/opt/mat/ParseHeapDump.sh
 *   mat.keep.unreachable.objects=true
 */
@Configuration
public class HeapDumpConfig {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpConfig.class);

    /** 힙 덤프 파일 저장 디렉토리 */
    @Value("${heapdump.directory:/opt/heapdumps}")
    private String heapDumpDirectory;

    /** MAT CLI 스크립트 경로 */
    @Value("${mat.cli.path:/opt/mat/ParseHeapDump.sh}")
    private String matCliPath;

    /**
     * MAT CLI -keep_unreachable_objects 옵션
     * true 설정 시 GC Root에서 도달 불가능한 객체도 분석에 포함합니다.
     * 힙 덤프 파일 크기와 MAT 표시 힙 크기가 크게 차이날 때 사용합니다.
     */
    @Value("${mat.keep.unreachable.objects:false}")
    private boolean keepUnreachableObjects;

    /** MAT CLI 실행 타임아웃 (분) */
    @Value("${mat.timeout.minutes:30}")
    private int matTimeoutMinutes;

    /** SSE Emitter 타임아웃 (분) — MAT 타임아웃보다 길어야 함 */
    @Value("${sse.emitter.timeout.minutes:35}")
    private int sseEmitterTimeoutMinutes;

    /** 대시보드 히스토리 최대 표시 수 */
    @Value("${dashboard.history.max-display:5}")
    private int dashboardHistoryMaxDisplay;

    /** 디스크 경고 사용률 임계값 (%) */
    @Value("${disk.warning.usage-percent:90}")
    private int diskWarningUsagePercent;

    /** 디스크 최소 여유 공간 경고 임계값 (MB) */
    @Value("${disk.warning.free-space-mb:500}")
    private long diskWarningFreeSpaceMb;

    /** 분석 결과 페이지 Top Memory Objects 최대 표시 수 */
    @Value("${analysis.top-objects.max-display:10}")
    private int topObjectsMaxDisplay;

    /** MAT 로그 에러 시 최대 표시 길이 (문자 수) */
    @Value("${analysis.mat-log.max-display-chars:5000}")
    private int matLogMaxDisplayChars;

    /** MAT 진행률 로그 업데이트 빈도 (줄 단위) */
    @Value("${analysis.progress.log-update-lines:50}")
    private int progressLogUpdateLines;

    /** 분석 스레드 풀 core 크기 (최소 3 권장: 분석1 + MAT리더1 + 대기1) */
    @Value("${analysis.thread-pool.core-size:3}")
    private int threadPoolCoreSize;

    /** 분석 스레드 풀 max 크기 */
    @Value("${analysis.thread-pool.max-size:5}")
    private int threadPoolMaxSize;

    /** 분석 스레드 풀 큐 용량 */
    @Value("${analysis.thread-pool.queue-capacity:12}")
    private int threadPoolQueueCapacity;

    /** 분석 완료 후 원본 gzip 압축 여부 */
    @Value("${analysis.compress-after-analysis:true}")
    private boolean compressAfterAnalysis;

    /** MAT CLI 유효성 상태 (init 후 설정) */
    private boolean matCliReady;
    private String  matCliStatusMessage;

    @PostConstruct
    public void init() {
        logger.info("========================================");
        logger.info(" Heap Dump Analyzer — Configuration Init");
        logger.info("========================================");

        // ── 1. 힙 덤프 디렉토리 검증 ──────────────────────────
        initHeapDumpDirectory();

        // ── 2. MAT CLI 검증 ───────────────────────────────────
        validateMatCli();

        // ── 3. 스레드 풀 설정 검증 ─────────────────────────────
        validateThreadPoolConfig();

        // ── 4. 설정 값 로깅 ───────────────────────────────────
        logger.info("[Config] keep_unreachable_objects: {}", keepUnreachableObjects);
        logger.info("========================================");
    }

    private void initHeapDumpDirectory() {
        logger.info("[Config] Heap dump directory: {}", heapDumpDirectory);
        try {
            Path path = Paths.get(heapDumpDirectory);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                logger.info("[Config] Created heap dump directory: {}", heapDumpDirectory);
            }

            // 쓰기 권한 확인
            File dir = path.toFile();
            if (!dir.canWrite()) {
                logger.error("[Config] Heap dump directory is NOT writable: {}", heapDumpDirectory);
                logger.error("[Config] → 업로드 및 분석 결과 저장이 불가합니다. 디렉토리 권한을 확인하세요.");
            } else {
                // 디스크 여유 공간 확인
                long freeSpace = dir.getUsableSpace();
                long freeSpaceMB = freeSpace / (1024 * 1024);
                if (freeSpaceMB < diskWarningFreeSpaceMb) {
                    logger.warn("[Config] Heap dump directory free space is low: {} MB — 최소 {} MB 이상 권장", freeSpaceMB, diskWarningFreeSpaceMb);
                } else {
                    logger.info("[Config] Heap dump directory OK (writable, free: {} MB)", freeSpaceMB);
                }
            }
            // data 디렉토리 생성 (분석 결과 영속 저장)
            Path dataPath = Paths.get(heapDumpDirectory, "data");
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
                logger.info("[Config] Created data directory: {}", dataPath);
            } else {
                logger.info("[Config] Data directory: {}", dataPath);
            }
            // dumpfiles 디렉토리 생성 (원본 힙덤프 저장)
            Path dumpFilesPath = Paths.get(heapDumpDirectory, "dumpfiles");
            if (!Files.exists(dumpFilesPath)) {
                Files.createDirectories(dumpFilesPath);
                logger.info("[Config] Created dumpfiles directory: {}", dumpFilesPath);
            } else {
                logger.info("[Config] Dumpfiles directory: {}", dumpFilesPath);
            }
        } catch (IOException e) {
            logger.error("[Config] Failed to create heap dump directory: {} — {}", heapDumpDirectory, e.getMessage());
            throw new RuntimeException("Failed to initialize heap dump directory: " + heapDumpDirectory, e);
        }
    }

    private void validateMatCli() {
        logger.info("[Config] MAT CLI path: {}", matCliPath);
        File matFile = new File(matCliPath);

        // 1) 파일 존재 여부
        if (!matFile.exists()) {
            matCliReady = false;
            matCliStatusMessage = "MAT CLI script not found at: " + matCliPath;
            logger.error("┌─────────────────────────────────────────────────┐");
            logger.error("│  MAT CLI STATUS: NOT FOUND                      │");
            logger.error("├─────────────────────────────────────────────────┤");
            logger.error("│  Path: {}", matCliPath);
            logger.error("│                                                 │");
            logger.error("│  해결 방법:                                      │");
            logger.error("│  1. Eclipse MAT를 설치하세요                     │");
            logger.error("│     https://eclipse.dev/mat/downloads.php       │");
            logger.error("│  2. ParseHeapDump.sh 경로를 확인하세요            │");
            logger.error("│  3. application.properties에서 mat.cli.path를    │");
            logger.error("│     올바른 경로로 설정하세요                       │");
            logger.error("│                                                 │");
            logger.error("│  힙 덤프 분석 기능이 동작하지 않습니다.            │");
            logger.error("└─────────────────────────────────────────────────┘");
            return;
        }

        // 2) 일반 파일인지 확인 (디렉토리가 아닌지)
        if (!matFile.isFile()) {
            matCliReady = false;
            matCliStatusMessage = "MAT CLI path is not a regular file: " + matCliPath;
            logger.error("[Config] MAT CLI STATUS: INVALID — path exists but is not a regular file (directory?): {}", matCliPath);
            return;
        }

        // 3) 읽기 권한 확인
        if (!matFile.canRead()) {
            matCliReady = false;
            matCliStatusMessage = "MAT CLI script is not readable: " + matCliPath;
            logger.error("[Config] MAT CLI STATUS: NOT READABLE — 파일 읽기 권한이 없습니다: {}", matCliPath);
            logger.error("[Config] → 해결: chmod +r {}", matCliPath);
            return;
        }

        // 4) 실행 권한 확인 및 자동 부여
        if (!matFile.canExecute()) {
            logger.warn("[Config] MAT CLI is not executable, attempting to set execute permission: {}", matCliPath);
            boolean set = matFile.setExecutable(true);
            if (!set || !matFile.canExecute()) {
                matCliReady = false;
                matCliStatusMessage = "MAT CLI script is not executable and permission could not be set: " + matCliPath;
                logger.error("[Config] MAT CLI STATUS: NOT EXECUTABLE — 실행 권한을 부여할 수 없습니다: {}", matCliPath);
                logger.error("[Config] → 해결: chmod +x {}", matCliPath);
                return;
            }
            logger.info("[Config] MAT CLI execute permission set successfully");
        }

        // 5) 파일 크기 확인 (비어있는 파일이 아닌지)
        if (matFile.length() == 0) {
            matCliReady = false;
            matCliStatusMessage = "MAT CLI script is empty (0 bytes): " + matCliPath;
            logger.error("[Config] MAT CLI STATUS: EMPTY FILE — 파일 크기가 0입니다: {}", matCliPath);
            return;
        }

        // 모든 검증 통과
        matCliReady = true;
        matCliStatusMessage = "Ready";
        logger.info("┌─────────────────────────────────────────────────┐");
        logger.info("│  MAT CLI STATUS: READY                          │");
        logger.info("├─────────────────────────────────────────────────┤");
        logger.info("│  Path: {}", matCliPath);
        logger.info("│  Size: {} bytes", matFile.length());
        logger.info("│  Executable: true                               │");
        logger.info("│  Readable: true                                 │");
        logger.info("└─────────────────────────────────────────────────┘");
    }

    private void validateThreadPoolConfig() {
        if (threadPoolCoreSize < 3) {
            logger.warn("[Config] analysis.thread-pool.core-size={} — 3 미만이면 대기열 알림이 지연될 수 있습니다 (권장: 3 이상)", threadPoolCoreSize);
        }
        if (threadPoolMaxSize < threadPoolCoreSize) {
            logger.warn("[Config] analysis.thread-pool.max-size({})가 core-size({})보다 작아 core-size로 보정합니다", threadPoolMaxSize, threadPoolCoreSize);
            threadPoolMaxSize = threadPoolCoreSize;
        }
        if (threadPoolQueueCapacity < 1) {
            logger.warn("[Config] analysis.thread-pool.queue-capacity={} — 최소 1 이상이어야 합니다. 기본값 12로 보정합니다", threadPoolQueueCapacity);
            threadPoolQueueCapacity = 12;
        }
        logger.info("[Config] 분석 스레드 풀: core={}, max={}, queue={}", threadPoolCoreSize, threadPoolMaxSize, threadPoolQueueCapacity);
    }

    public String  getHeapDumpDirectory()        { return heapDumpDirectory; }
    public String  getDataDirectory()            { return heapDumpDirectory + File.separator + "data"; }
    public String  getDumpFilesDirectory()       { return heapDumpDirectory + File.separator + "dumpfiles"; }
    public String  getMatCliPath()               { return matCliPath; }
    public boolean isKeepUnreachableObjects()    { return keepUnreachableObjects; }
    public boolean isMatCliReady()               { return matCliReady; }
    public String  getMatCliStatusMessage()      { return matCliStatusMessage; }
    public int     getMatTimeoutMinutes()        { return matTimeoutMinutes; }
    public int     getSseEmitterTimeoutMinutes() { return sseEmitterTimeoutMinutes; }
    public int     getDashboardHistoryMaxDisplay(){ return dashboardHistoryMaxDisplay; }
    public int     getDiskWarningUsagePercent()  { return diskWarningUsagePercent; }
    public long    getDiskWarningFreeSpaceMb()   { return diskWarningFreeSpaceMb; }
    public int     getTopObjectsMaxDisplay()     { return topObjectsMaxDisplay; }
    public int     getMatLogMaxDisplayChars()    { return matLogMaxDisplayChars; }
    public int     getProgressLogUpdateLines()   { return progressLogUpdateLines; }
    public int     getThreadPoolCoreSize()      { return threadPoolCoreSize; }
    public int     getThreadPoolMaxSize()        { return threadPoolMaxSize; }
    public int     getThreadPoolQueueCapacity()  { return threadPoolQueueCapacity; }
    public boolean isCompressAfterAnalysis()    { return compressAfterAnalysis; }
}

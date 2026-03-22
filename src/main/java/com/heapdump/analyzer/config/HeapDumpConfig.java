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

        // ── 3. 설정 값 로깅 ───────────────────────────────────
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
                if (freeSpaceMB < 500) {
                    logger.warn("[Config] Heap dump directory free space is low: {} MB — 최소 500 MB 이상 권장", freeSpaceMB);
                } else {
                    logger.info("[Config] Heap dump directory OK (writable, free: {} MB)", freeSpaceMB);
                }
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

    public String  getHeapDumpDirectory()      { return heapDumpDirectory; }
    public String  getMatCliPath()             { return matCliPath; }
    public boolean isKeepUnreachableObjects()  { return keepUnreachableObjects; }
    public boolean isMatCliReady()             { return matCliReady; }
    public String  getMatCliStatusMessage()    { return matCliStatusMessage; }
}

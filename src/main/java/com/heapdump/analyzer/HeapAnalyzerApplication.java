package com.heapdump.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Heap Dump Analyzer Application (MAT CLI Edition)
 *
 * 종료 추적 (kill -9 / OOM Killer 같은 SIGKILL 류는 OS 가 즉시 죽이므로 JVM 어떤 코드도 실행 불가):
 *  - 신호별 핸들러: SIGTERM/SIGINT/SIGHUP 수신 시 신호 이름/번호 로깅 후 정상 셧다운 트리거
 *  - shutdown marker 파일: 정상 셧다운 시 삭제. 다음 기동에 파일이 남아있으면 직전 종료가
 *    비정상(SIGKILL/OOM/crash/전원 차단)이었다고 추정하여 WARN 로그.
 */
@SpringBootApplication
@EnableScheduling
public class HeapAnalyzerApplication {

    private static final Logger logger = LoggerFactory.getLogger(HeapAnalyzerApplication.class);

    /**
     * 셧다운 정상성 추적용 마커 파일.
     * - app.home 시스템 프로퍼티가 있으면 사용, 없으면 user.dir 기준.
     * - 정상 종료(shutdown hook 진입) 시 삭제 → 다음 기동 시 부재 = 정상 종료였음.
     * - 강제 종료 시 마커가 남음 → 다음 기동 시 존재 = 비정상 종료였음.
     */
    private static final Path SHUTDOWN_MARKER = Paths.get(
        System.getProperty("app.home", System.getProperty("user.dir")),
        "logs", ".shutdown-marker");

    public static void main(String[] args) {
        // 1) 직전 비정상 종료 감지 (SpringApplication.run 전에 수행 — 부팅 로그 상단에 노출)
        checkPreviousShutdown();

        // 2) 신호별 핸들러 등록 (SIGTERM/SIGINT/SIGHUP)
        installSignalHandlers();

        // 3) 통합 shutdown hook — 신호 출처와 무관하게 종료 직전 항상 실행
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[Shutdown] Application is shutting down");
            try {
                if (Files.deleteIfExists(SHUTDOWN_MARKER)) {
                    logger.info("[Shutdown] Shutdown marker cleared — next start will see this as a clean exit");
                }
            } catch (IOException e) {
                logger.warn("[Shutdown] Failed to delete shutdown marker ({}): {}",
                    SHUTDOWN_MARKER, e.getMessage());
            }
        }, "shutdown-logger"));

        // 4) 새 마커 작성 (PID + 시작 시각). 이후 SIGKILL/OOM 으로 죽으면 이 파일이 남음
        writeShutdownMarker();

        SpringApplication.run(HeapAnalyzerApplication.class, args);
    }

    /**
     * 직전 셧다운 마커가 남아있는지 검사한다.
     * 존재 = 직전 프로세스가 shutdown hook 까지 도달하지 못하고 죽었음을 의미.
     * (SIGKILL / Linux OOM Killer / JVM crash / 전원 차단 / 컨테이너 강제 종료 등)
     */
    private static void checkPreviousShutdown() {
        if (!Files.exists(SHUTDOWN_MARKER)) {
            return;  // 정상 종료였거나 최초 기동
        }
        String content;
        try {
            content = new String(Files.readAllBytes(SHUTDOWN_MARKER), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            content = "(마커 읽기 실패: " + e.getMessage() + ")";
        }
        logger.warn("[Startup] ⚠ 직전 종료가 정상적으로 완료되지 않았습니다. " +
            "JVM 이 shutdown hook 을 실행하지 못하고 종료되었습니다. " +
            "원인 후보: kill -9 (SIGKILL), Linux OOM Killer, JVM crash, 전원 차단, 컨테이너 강제 종료.");
        logger.warn("[Startup] 직전 마커 정보 — {}", content);
        logger.warn("[Startup] OS 레벨 확인 명령:");
        logger.warn("[Startup]   1) OOM Killer:  dmesg | grep -i 'killed process'");
        logger.warn("[Startup]   2) journal:     journalctl -k --since '1 hour ago' | grep -iE 'oom|kill'");
        logger.warn("[Startup]   3) JVM crash:   ls -lt {} 2>/dev/null | head", SHUTDOWN_MARKER.getParent());
    }

    /**
     * 새 마커 작성 — 현재 프로세스가 시작했음을 표시.
     * 정상 셧다운 hook 이 실행되면 삭제됨. 강제 종료 시 남음.
     */
    private static void writeShutdownMarker() {
        try {
            Path parent = SHUTDOWN_MARKER.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String pidName = ManagementFactory.getRuntimeMXBean().getName();  // <pid>@<hostname>
            String content = "pidName=" + pidName
                + ", startedAt=" + Instant.now()
                + ", javaVersion=" + System.getProperty("java.version")
                + System.lineSeparator();
            Files.write(SHUTDOWN_MARKER, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("[Startup] 셧다운 마커 작성 실패 (이번 사이클의 비정상 종료 감지가 동작하지 않습니다): {}",
                e.getMessage());
        }
    }

    /**
     * SIGTERM/SIGINT/SIGHUP 별 전용 핸들러 등록.
     * - sun.misc.Signal 은 jdk.unsupported 모듈에 남아있어 Java 11/17 모두 사용 가능.
     * - 핸들러는 신호 이름/번호를 로깅한 뒤 System.exit(0) 호출 → JVM 셧다운 hook 정상 트리거.
     * - 핸들러 등록 실패(SecurityManager 거부 등) 는 warn 으로만 남기고 계속 진행 (기본 JVM 동작은 보존).
     */
    @SuppressWarnings({"sunapi", "deprecation"})
    private static void installSignalHandlers() {
        String[] signals = {"TERM", "INT", "HUP"};
        for (String sig : signals) {
            try {
                sun.misc.Signal.handle(new sun.misc.Signal(sig), signal ->
                    handleSignal(signal.getName(), signal.getNumber())
                );
            } catch (IllegalArgumentException | SecurityException e) {
                logger.warn("[Signal] SIG{} 핸들러 등록 실패 (기본 JVM 동작 유지): {}", sig, e.getMessage());
            }
        }
        logger.info("[Signal] 신호 핸들러 등록 완료 — SIGTERM/SIGINT/SIGHUP 수신 시 종류별 로깅 활성화 " +
            "(SIGKILL 은 OS 가 직접 죽이므로 catch 불가)");
    }

    private static void handleSignal(String name, int number) {
        logger.info("[Signal] Received SIG{} ({}) — initiating graceful shutdown", name, number);
        // System.exit(0) 호출이 모든 shutdown hook (위에서 등록한 것 + Spring 컨텍스트) 을 트리거
        System.exit(0);
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원격 전송의 로컬 이름 충돌 방어 (2026-09-14).
 *
 * <p>① 파일 없이 남은 GC 로그 기록의 이름은 쓰지 않는다 ② 같은 이름을 동시에 받는 전송이 서로 덮어쓰지 않는다(예약 + 덮어쓰기 없는 이동)
 * ③ 스캔 '전송됨' 판정은 원격 파일명이 아니라 원격 전체 경로로 한다. 덤으로 힙 {@code X.hprof} ↔ {@code X.hprof.gz} 짝 충돌
 * (기동 시 cleanupDuplicateGzFiles 가 .gz 를 지운다)도 회피명으로 피한다.
 */
class RemoteDumpTransferNameGuardTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 14, 15, 30, 45);

    @TempDir Path tmp;
    private File dir;
    private DumpTransferLogRepository transferRepo;
    private GcLogAnalysisRepository gcRepo;
    private RemoteDumpService svc;

    @BeforeEach
    void setUp() {
        dir = tmp.toFile();
        transferRepo = Mockito.mock(DumpTransferLogRepository.class);
        gcRepo = Mockito.mock(GcLogAnalysisRepository.class);
        svc = new RemoteDumpService(Mockito.mock(TargetServerRepository.class), transferRepo,
                Mockito.mock(AnalysisHistoryRepository.class), Mockito.mock(HeapDumpConfig.class));
        ReflectionTestUtils.setField(svc, "gcLogAnalysisRepository", gcRepo);
    }

    private void touch(String name, String text) throws Exception {
        Files.writeString(new File(dir, name).toPath(), text, StandardCharsets.UTF_8);
    }

    // ── ① 파일 없이 남은 기록 ─────────────────────────────────────

    @Test
    @DisplayName("① gclog: 디스크에 파일이 없어도 gc_log_analysis 기록이 있는 이름은 회피명으로 받는다")
    void gcLogRecordNameIsTaken() {
        Mockito.when(gcRepo.existsByFilename("gc.log")).thenReturn(true);
        String name = svc.reserveLocalName(dir, "gc.log", svc.nameTaken("gclog", dir), T);
        assertEquals("gc_202609141530.log", name, "옛 기록 이름을 쓰면 registerTransferred 가 옛 결과를 이어 쓴다");

        Mockito.when(gcRepo.existsByFilename("gc.log.1")).thenReturn(false);
        assertEquals("gc.log.1", svc.reserveLocalName(dir, "gc.log.1", svc.nameTaken("gclog", dir), T), "기록이 없는 이름은 그대로");
    }

    @Test
    @DisplayName("heap: X.hprof ↔ X.hprof.gz 짝이 있으면 회피명 — 둘이 함께 있으면 기동 시 .gz 가 지워진다 / core 는 파일 존재만")
    void heapGzSiblingIsTaken() throws Exception {
        touch("app.hprof.gz", "old");
        assertEquals("app_202609141530.hprof", svc.reserveLocalName(dir, "app.hprof", svc.nameTaken("heap", dir), T));
        touch("web.hprof", "old");
        assertEquals("web.hprof_202609141530.gz", svc.reserveLocalName(dir, "web.hprof.gz", svc.nameTaken("heap", dir), T));
        assertFalse(svc.nameTaken("core", dir).test("app.hprof"), "코어 저장소는 .gz 짝 규칙과 무관");
        assertFalse(svc.nameTaken("gclog", dir).test("gc.log"), "기록 없음");
    }

    // ── ② 동시 전송 ─────────────────────────────────────────────

    @Test
    @DisplayName("② 예약 중인 이름은 다른 전송이 고르지 못하고, 풀면 다시 쓸 수 있다")
    void reservationBlocksConcurrentSameName() {
        Predicate<String> none = n -> false;
        String a = svc.reserveLocalName(dir, "gc.log", none, T);
        String b = svc.reserveLocalName(dir, "gc.log", none, T);
        assertEquals("gc.log", a);
        assertNotEquals(a, b, "파일이 아직 없어도(SCP 중) 같은 이름을 두 전송에 주면 마지막 이동에서 덮어쓴다");
        assertTrue(svc.isReserved(dir, a) && svc.isReserved(dir, b));
        svc.releaseLocalName(dir, a);
        assertFalse(svc.isReserved(dir, a));
        assertEquals("gc.log", svc.reserveLocalName(dir, "gc.log", none, T), "끝난 전송의 이름은 다시 쓸 수 있다");
        assertFalse(svc.reserveLocalName(new File(dir, "other"), "gc.log", none, T).contains("_"), "예약은 디렉토리별이다");
    }

    @Test
    @DisplayName("② 32 스레드가 같은 이름을 동시에 예약해도 전부 서로 다른 이름")
    void parallelReservationsAreDistinct() throws Exception {
        int n = 32;
        Set<String> names = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> { start.await(); names.add(svc.reserveLocalName(dir, "gc.log", x -> false, T)); return null; });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(n, names.size());
    }

    @Test
    @DisplayName("② 이동은 덮어쓰지 않는다 — 예약 뒤 다른 경로가 같은 이름을 만들면 새 이름으로 옮기고 기존 파일은 그대로")
    void moveNeverReplaces() throws Exception {
        String reserved = svc.reserveLocalName(dir, "gc.log", x -> false, T);
        touch("gc.log", "EXISTING");                      // 예약 밖(업로드·수동 복사)에서 먼저 생김
        Path temp = Files.writeString(tmp.resolve("incoming.tmp"), "NEW", StandardCharsets.UTF_8);

        File placed = svc.moveIntoPlace(temp.toFile(), dir, reserved, "gc.log", x -> false);

        assertEquals("EXISTING", Files.readString(new File(dir, "gc.log").toPath()), "먼저 있던 파일이 덮이면 안 된다");
        assertNotEquals("gc.log", placed.getName());
        assertEquals("NEW", Files.readString(placed.toPath()));
        assertFalse(temp.toFile().exists());
        assertTrue(svc.isReserved(dir, placed.getName()), "예약은 최종 이름으로 넘어간다");
        assertFalse(svc.isReserved(dir, "gc.log"), "옛 예약은 풀린다");
    }

    @Test
    @DisplayName("② transferFile 은 REPLACE_EXISTING 으로 옮기지 않고, 예약을 finally 에서 푼다")
    void transferFileSourceContract() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java"), StandardCharsets.UTF_8);
        int from = src.indexOf("public DumpTransferLog transferFile(TargetServer server, String remoteFilePath,\n                                        String fileType, String targetFilename,");
        int to = src.indexOf("스캔 '전송됨' 판정 (2026-09-14)", from);   // 바로 뒤 헬퍼들의 주석(REPLACE_EXISTING 금지 설명)은 범위 밖
        String body = src.substring(from, to);
        assertFalse(body.contains("REPLACE_EXISTING"), "덮어쓰기 이동이 되살아났다");
        assertTrue(body.contains("reserveLocalName(localDir, requestedName, taken, LocalDateTime.now())"));
        assertTrue(body.contains("moveIntoPlace(tempFile, localDir, reservedName, requestedName, taken)"));
        assertTrue(body.contains("} finally {\n            releaseLocalName(localDir, reservedName);"));
    }

    // ── ③ 스캔 전송됨 판정 ──────────────────────────────────────

    private DumpTransferLog logOf(String local, String remotePath) {
        DumpTransferLog l = new DumpTransferLog();
        l.setFilename(local);
        l.setRemotePath(remotePath);
        return l;
    }

    @Test
    @DisplayName("③ 같은 서버·같은 이름·같은 크기라도 다른 디렉토리의 파일은 전송됨이 아니다")
    void transferredMatchesByRemotePath() {
        TargetServer server = new TargetServer();
        server.setId(3L);
        Mockito.when(transferRepo.findByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(3L, "gc.log", 500L, "SUCCESS"))
                .thenReturn(List.of(logOf("gc.log", "/logs/was1/gc.log")));
        Predicate<String> exists = local -> true;

        assertNull(svc.findTransferredLocal(server, "gc.log", "/logs/was2/gc.log", 500L, exists),
                "was2 의 gc.log 가 was1 전송 기록으로 '전송됨' 이 되면 자동 전송에서 조용히 빠진다");
        assertEquals("gc.log", svc.findTransferredLocal(server, "gc.log", "/logs/was1/gc.log", 500L, exists));
        assertEquals("gc.log", svc.findTransferredLocal(server, "gc.log", "/logs//was1/./gc.log", 500L, exists), "슬래시·./ 표기 차이는 같은 경로");
        assertNull(svc.findTransferredLocal(server, "gc.log", "/logs/was1/gc.log", 500L, local -> false), "로컬 파일이 없으면 전송됨이 아니다");
    }

    @Test
    @DisplayName("③ remote_path 가 없는 옛 기록은 종전처럼 이름·크기로 인정한다(재전송 폭주 방지) — 경로가 맞는 기록을 먼저 찾는다")
    void legacyLogsWithoutPathStillCount() {
        TargetServer server = new TargetServer();
        server.setId(3L);
        Mockito.when(transferRepo.findByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(3L, "app.hprof", 9L, "SUCCESS"))
                .thenReturn(List.of(logOf("app_other.hprof", "/dumps/b/app.hprof"), logOf("app.hprof", null)));
        assertEquals("app.hprof", svc.findTransferredLocal(server, "app.hprof", "/dumps/a/app.hprof", 9L, l -> true));
        assertTrue(RemoteDumpService.sameRemotePath("/a//b/./c", "/a/b/c"));
        assertFalse(RemoteDumpService.sameRemotePath("/a/b/c", "/a/B/c"), "대소문자는 구분한다");
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.repository.GcLogResultDetailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파일 분류로 GC 로그 저장소를 드나드는 이동 의미론 (2026-09-14).
 *
 * <p>분류 라벨({@code file-classifications.properties})은 이름표일 뿐이지만 GC 로그 분석기는 <b>GC 로그 저장소의 파일만</b>
 * 읽는다. 그래서 'GC로그' 로 분류하면 파일을 옮기고({@code adoptFile}), 다른 유형으로 되돌리면 힙덤프 저장소로 내보낸다
 * ({@code releaseFile}). 여기서 고정하는 것: 형식이 아니면 <b>파일이 제자리에 남는다</b>, 이름 충돌은 어느 쪽도 덮지 않는다,
 * 내보내면 결과·AI 해석·엔티티가 함께 사라진다(되살아나는 유령 결과 금지).
 */
class GcLogClassifyMoveTest {

    @TempDir Path root;
    private Path gcDir;
    private Path heapDir;

    private GcLogAnalysisRepository repo;
    private GcLogResultDetailRepository detailRepo;
    private AiInsightManager insight;
    private DumpTransferLogRepository transferRepo;
    private GcLogMatchService match;
    private GcLogAnalyzerService service;
    private final AtomicReference<GcLogAnalysisEntity> stored = new AtomicReference<>();

    private static final String GC_TEXT =
            "[2026-09-14T00:52:33.485+0900][0.003s][info][gc] Using G1\n"
          + "[2026-09-14T00:52:34.485+0900][1.003s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 60M->10M(258M) 3.000ms\n";

    @BeforeEach
    void setUp() throws Exception {
        gcDir = Files.createDirectories(root.resolve("gclogs/dumpfiles"));
        heapDir = Files.createDirectories(root.resolve("heapdumps/dumpfiles"));
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getGcLogDumpFilesDirectory()).thenReturn(gcDir.toString());
        Mockito.when(config.getGcLogMaxFileBytes()).thenReturn(2L * 1024 * 1024 * 1024);
        Mockito.when(config.getGcLogMaxLineChars()).thenReturn(4096);
        Mockito.when(config.getGcLogAnalysisTimeoutMinutes()).thenReturn(10);

        repo = Mockito.mock(GcLogAnalysisRepository.class);
        Mockito.when(repo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        Mockito.when(repo.save(Mockito.any(GcLogAnalysisEntity.class))).thenAnswer(inv -> { stored.set(inv.getArgument(0)); return inv.getArgument(0); });
        detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        insight = Mockito.mock(AiInsightManager.class);
        transferRepo = Mockito.mock(DumpTransferLogRepository.class);
        match = Mockito.mock(GcLogMatchService.class);
        service = new GcLogAnalyzerService(config, repo, detailRepo, Mockito.mock(AnalysisHistoryRepository.class), match,
                Mockito.mock(LlmConfigService.class), insight, transferRepo);
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    private File heapFile(String name, String text) throws Exception {
        Path p = heapDir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p.toFile();
    }

    @Test
    @DisplayName("adopt: 힙덤프 저장소의 GC 로그를 옮기고 NOT_ANALYZED 로 새로 시작 — 옛 결과·매칭은 비우고 원격 출처는 전송 기록에서 채운다")
    void adoptMovesAndResets() throws Exception {
        File src = heapFile("verbosegc.txt", GC_TEXT);
        GcLogAnalysisEntity old = new GcLogAnalysisEntity();    // 예전에 GC 로그였다가 내보냈던 같은 이름의 기록
        old.setFilename("verbosegc.txt");
        old.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        old.setSeverity("High");
        old.setMatchedDumpFilename("app.hprof");
        old.setMatchSource(GcLogAnalysisEntity.MATCH_MANUAL);
        stored.set(old);
        DumpTransferLog t = new DumpTransferLog();
        t.setServerId(7L);
        t.setRemotePath("/app/logs/verbosegc.txt");
        Mockito.when(transferRepo.findByFilenameAndTransferStatusOrderByCompletedAtDesc("verbosegc.txt", "SUCCESS")).thenReturn(List.of(t));

        GcLogAnalysisEntity e = service.adoptFile(src, "admin", "was01");

        assertFalse(src.exists(), "원래 자리에 남으면 두 저장소에 같은 파일이 생긴다");
        assertTrue(gcDir.resolve("verbosegc.txt").toFile().isFile(), "GC 로그 저장소로 이동");
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, e.getStatus());
        assertNull(e.getSeverity());
        assertNull(e.getMatchedDumpFilename(), "옛 수동 매칭이 새 파일에 붙어 있으면 안 된다");
        assertEquals(GcLogAnalysisEntity.MATCH_NONE, e.getMatchSource());
        assertEquals(7L, e.getServerId());
        assertEquals("/app/logs/verbosegc.txt", e.getRemotePath());
        assertEquals("was01", e.getServerName());
        assertEquals((long) GC_TEXT.getBytes(StandardCharsets.UTF_8).length, e.getFileSize());
        Mockito.verify(detailRepo).deleteByFilename("verbosegc.txt");
        Mockito.verify(insight).deleteAiInsight("__gclog__:verbosegc.txt");
        Mockito.verify(match).tryAutoMatch(e);
    }

    @Test
    @DisplayName("adopt 거부: GC 로그 형식이 아니면 400 계열 예외 + 파일은 제자리")
    void adoptRefusesNonGcLog() throws Exception {
        File src = heapFile("app.log", "2026-09-14 00:00:00 INFO application started\n[main] hello\n");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.adoptFile(src, "admin", null));
        assertTrue(ex.getMessage().contains("GC 로그 형식을 인식하지 못했습니다"), ex.getMessage());
        assertTrue(src.isFile(), "거부된 파일이 사라지면 안 된다");
        assertFalse(gcDir.resolve("app.log").toFile().exists());
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("adopt 거부: GC 로그 저장소에 같은 이름이 있으면 409 계열 예외 + 양쪽 파일 모두 원본 유지")
    void adoptRefusesNameConflict() throws Exception {
        File src = heapFile("gc.log", GC_TEXT);
        Files.writeString(gcDir.resolve("gc.log"), "existing", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> service.adoptFile(src, "admin", null));
        assertTrue(src.isFile());
        assertEquals("existing", Files.readString(gcDir.resolve("gc.log")), "기존 GC 로그를 덮으면 안 된다");
    }

    @Test
    @DisplayName("release: 힙덤프 저장소로 옮기고 결과·AI 해석·엔티티를 함께 지운다 — 같은 이름의 형제 파일(gc.log.1)은 건드리지 않는다")
    void releaseMovesAndForgets() throws Exception {
        Files.writeString(gcDir.resolve("gc.log"), GC_TEXT, StandardCharsets.UTF_8);
        Files.writeString(gcDir.resolve("gc.log.1"), GC_TEXT, StandardCharsets.UTF_8);
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc.log");
        e.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        stored.set(e);

        service.releaseFile("gc.log", heapDir.toFile(), "admin");

        assertTrue(heapDir.resolve("gc.log").toFile().isFile(), "힙덤프 저장소로 이동");
        assertFalse(gcDir.resolve("gc.log").toFile().exists());
        assertTrue(gcDir.resolve("gc.log.1").toFile().isFile(), "회전 형제 파일은 그대로");
        Mockito.verify(detailRepo).deleteByFilename("gc.log");
        Mockito.verify(insight).deleteAiInsight("__gclog__:gc.log");
        Mockito.verify(repo).delete(e);
        assertTrue(service.loadResult("gc.log").isEmpty(), "캐시에서 옛 결과가 되살아나면 안 된다");
    }

    @Test
    @DisplayName("release 거부: 대상에 같은 이름이 있으면 409 계열 · 파일이 없으면 400 계열 — 어느 경우도 기록을 지우지 않는다")
    void releaseRefusals() throws Exception {
        Files.writeString(gcDir.resolve("gc.log"), GC_TEXT, StandardCharsets.UTF_8);
        Files.writeString(heapDir.resolve("gc.log"), "heap-side", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> service.releaseFile("gc.log", heapDir.toFile(), "admin"));
        assertTrue(gcDir.resolve("gc.log").toFile().isFile());
        assertEquals("heap-side", Files.readString(heapDir.resolve("gc.log")));

        assertThrows(IllegalArgumentException.class, () -> service.releaseFile("missing.log", heapDir.toFile(), "admin"));
        Mockito.verify(repo, Mockito.never()).delete(Mockito.any());
        Mockito.verify(detailRepo, Mockito.never()).deleteByFilename(Mockito.anyString());
    }
}

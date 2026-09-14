package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.model.entity.TargetServer;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 같은 이름의 GC 로그 기록이 남아 있을 때 새 파일이 들어오는 경로 (2026-09-14).
 *
 * <p>결함: 파일을 디스크에서 지워도 {@code gc_log_analysis} 기록(결과·AI·매칭)은 남는다. 그 이름으로 새 파일이 전송·업로드되거나
 * 디스크에 다시 나타나면 기록을 이어 써서 <b>새 파일에 옛 분석 결과(SUCCESS)가 붙었다</b> — 예외도 경고도 없이.
 * 고정하는 것: 전송·업로드는 늘 새 파일로 취급하고, 디스크 재등장은 크기가 다를 때만 새 파일로 본다(같은 파일을 되돌려 놓은 경우 결과 보존).
 */
class GcLogReusedNameTest {

    @TempDir Path root;
    private Path gcDir;
    private GcLogAnalysisRepository repo;
    private GcLogResultDetailRepository detailRepo;
    private AiInsightManager insight;
    private GcLogAnalyzerService service;
    private final AtomicReference<GcLogAnalysisEntity> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        gcDir = Files.createDirectories(root.resolve("gclogs/dumpfiles"));
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getGcLogDumpFilesDirectory()).thenReturn(gcDir.toString());
        repo = Mockito.mock(GcLogAnalysisRepository.class);
        Mockito.when(repo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        Mockito.when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(inv -> stored.get() == null ? List.of() : List.of(stored.get()));
        Mockito.when(repo.save(Mockito.any(GcLogAnalysisEntity.class))).thenAnswer(inv -> { stored.set(inv.getArgument(0)); return inv.getArgument(0); });
        detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        insight = Mockito.mock(AiInsightManager.class);
        service = new GcLogAnalyzerService(config, repo, detailRepo, Mockito.mock(AnalysisHistoryRepository.class),
                Mockito.mock(GcLogMatchService.class), Mockito.mock(LlmConfigService.class), insight, Mockito.mock(DumpTransferLogRepository.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    /** 디스크에서 지워진 파일의 기록 — 분석 완료·수동 매칭·인스턴스명·출처까지 채워 둔다. */
    private GcLogAnalysisEntity ghost(long size) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setId(11L);
        e.setFilename("gc.log");
        e.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        e.setFileDeleted(true);
        e.setFileSize(size);
        e.setSeverity("High");
        e.setCollector("G1");
        e.setEventCount(45);
        e.setAnalyzedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        e.setMatchedDumpFilename("old.hprof");
        e.setMatchSource(GcLogAnalysisEntity.MATCH_MANUAL);
        e.setInstanceName("old-instance");
        e.setServerId(1L);
        e.setServerName("old-server");
        e.setRemotePath("/old/gc.log");
        e.setUploadedBy("someone");
        stored.set(e);
        return e;
    }

    private void assertForgotten(GcLogAnalysisEntity e) {
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, e.getStatus(), "옛 SUCCESS 가 이어지면 새 파일을 열 때 옛 결과가 보인다");
        assertNull(e.getSeverity());
        assertNull(e.getCollector());
        assertNull(e.getEventCount());
        assertNull(e.getAnalyzedAt());
        assertNull(e.getMatchedDumpFilename(), "옛 수동 매칭");
        assertEquals(GcLogAnalysisEntity.MATCH_NONE, e.getMatchSource(), "manual 이 남으면 자동 매칭이 영영 돌지 않는다");
        assertNull(e.getInstanceName(), "옛 인스턴스명");
        assertFalse(e.isFileDeleted());
        Mockito.verify(detailRepo).deleteByFilename("gc.log");
        Mockito.verify(insight).deleteAiInsight("__gclog__:gc.log");
    }

    @Test
    @DisplayName("전송: 같은 이름 기록이 있어도 옛 결과·매칭·인스턴스를 비우고 새 출처로 등록")
    void transferOntoOldRecordForgets() {
        ghost(100);
        TargetServer server = new TargetServer();
        server.setId(7L);
        server.setName("was07");
        DumpTransferLog log = new DumpTransferLog();
        log.setFilename("gc.log");
        log.setFileSize(5_000L);
        log.setRemotePath("/logs/was07/gc.log");

        GcLogAnalysisEntity e = service.registerTransferred(log, server);

        assertForgotten(e);
        assertEquals(7L, e.getServerId());
        assertEquals("was07", e.getServerName());
        assertEquals("/logs/was07/gc.log", e.getRemotePath());
        assertEquals(5_000L, e.getFileSize());
        assertNull(e.getUploadedBy(), "옛 등록자가 남으면 안 된다");
    }

    @Test
    @DisplayName("업로드: 파일 없이 남은 기록 이름으로 올려도 새 파일로 시작")
    void uploadOntoOldRecordForgets() {
        ghost(100);
        GcLogAnalysisEntity e = service.registerUploaded("gc.log", 5_000L, "admin", null);
        assertForgotten(e);
        assertEquals("admin", e.getUploadedBy());
        assertNull(e.getServerName(), "옛 서버명이 남으면 매칭이 엉뚱한 서버의 덤프를 고른다");
        assertNull(e.getRemotePath());
    }

    @Test
    @DisplayName("디스크 재등장: 크기가 다르면 새 파일로 취급, 같으면(되돌려 놓은 같은 파일) 결과 보존")
    void reappearedFileForgetsOnlyWhenSizeDiffers() throws Exception {
        Files.writeString(gcDir.resolve("gc.log"), "x".repeat(300));
        ghost(100);
        service.listExistingFiles();
        assertForgotten(stored.get());
        assertEquals(300L, stored.get().getFileSize());

        Mockito.clearInvocations(detailRepo, insight);
        ghost(300);
        service.listExistingFiles();
        GcLogAnalysisEntity kept = stored.get();
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, kept.getStatus(), "같은 크기면 같은 파일 — 결과를 지우지 않는다");
        assertEquals("old.hprof", kept.getMatchedDumpFilename());
        assertFalse(kept.isFileDeleted());
        Mockito.verify(detailRepo, Mockito.never()).deleteByFilename(Mockito.anyString());
    }

    @Test
    @DisplayName("처음 보는 이름은 지울 것이 없다 — 결과 삭제를 부르지 않는다")
    void freshNameDoesNotTouchResults() {
        GcLogAnalysisEntity e = service.registerUploaded("new.log", 10L, "admin", "was01");
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, e.getStatus());
        assertEquals("was01", e.getServerName());
        Mockito.verify(detailRepo, Mockito.never()).deleteByFilename(Mockito.anyString());
        Mockito.verify(insight, Mockito.never()).deleteAiInsight(Mockito.anyString());
    }
}

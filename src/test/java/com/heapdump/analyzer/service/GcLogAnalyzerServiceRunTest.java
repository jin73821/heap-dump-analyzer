package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.model.entity.GcLogResultDetailEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC 로그 분석 실행 경로(서비스 → 실행기 → 엔진 → 저장) 회귀 (2026-09-14).
 *
 * <p>계기: 업로드한 GC 로그가 전부 {@code NullPointerException: Cannot invoke "java.lang.Long.longValue()"} 로 실패했다.
 * 폴백 시각을 {@code cond ? long : (cond ? long : null)} 삼항으로 계산해 결과 타입이 원시 long 이 됐고, 원격 mtime 도
 * 서버 ID 도 없는 <b>업로드 파일</b>에서 안쪽이 null 을 내는 순간 언박싱됐다. 파서·집계기 테스트는 엔진에 null 을 직접
 * 넘겨서 이 서비스 경로를 지나지 않았다 — 그래서 여기서는 {@code submitAnalysis} 로 실제 실행기를 태운다(리포지토리만 mock).
 */
class GcLogAnalyzerServiceRunTest {

    private static final String TS = "2026-09-14T00:52:33.485+0900";

    @TempDir Path dir;

    private HeapDumpConfig config;
    private GcLogAnalysisRepository repo;
    private GcLogResultDetailRepository detailRepo;
    private GcLogMatchService match;
    private GcLogAnalyzerService service;
    private final AtomicReference<GcLogAnalysisEntity> stored = new AtomicReference<>();
    private final AtomicReference<GcLogResultDetailEntity> storedDetail = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getGcLogDumpFilesDirectory()).thenReturn(dir.toString());
        Mockito.when(config.getGcLogMaxFileBytes()).thenReturn(2L * 1024 * 1024 * 1024);
        Mockito.when(config.getGcLogMaxLineChars()).thenReturn(4096);
        Mockito.when(config.getGcLogAnalysisTimeoutMinutes()).thenReturn(10);

        repo = Mockito.mock(GcLogAnalysisRepository.class);
        Mockito.when(repo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        Mockito.when(repo.save(Mockito.any(GcLogAnalysisEntity.class))).thenAnswer(inv -> { stored.set(inv.getArgument(0)); return inv.getArgument(0); });

        detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        Mockito.when(detailRepo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(storedDetail.get()));
        Mockito.when(detailRepo.save(Mockito.any(GcLogResultDetailEntity.class))).thenAnswer(inv -> { storedDetail.set(inv.getArgument(0)); return inv.getArgument(0); });

        match = Mockito.mock(GcLogMatchService.class);
        service = new GcLogAnalyzerService(config, repo, detailRepo, Mockito.mock(AnalysisHistoryRepository.class), match,
                Mockito.mock(LlmConfigService.class), Mockito.mock(AiInsightManager.class),
                Mockito.mock(com.heapdump.analyzer.repository.DumpTransferLogRepository.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    private static String unified(boolean withTime) {
        StringBuilder sb = new StringBuilder();
        String deco = withTime ? "[" + TS + "]" : "";
        sb.append(deco).append("[0.003s][info][gc] Using G1\n");
        for (int i = 0; i < 5; i++) {
            double up = 1.0 + i * 10;
            String u = String.format(java.util.Locale.ROOT, "[%.3fs]", up);
            sb.append(deco).append(u).append("[info][gc,start] GC(").append(i).append(") Pause Young (Normal) (G1 Evacuation Pause)\n");
            sb.append(deco).append(u).append("[info][gc] GC(").append(i).append(") Pause Young (Normal) (G1 Evacuation Pause) 60M->10M(258M) 3.000ms\n");
        }
        return sb.toString();
    }

    private File write(String name, String text) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p.toFile();
    }

    private GcLogAnalysisEntity runToEnd(GcLogAnalysisEntity e) throws Exception {
        stored.set(e);
        GcLogAnalyzerService.JobProgress p = service.submitAnalysis(e.getFilename(), "tester");
        p.future.get(20, TimeUnit.SECONDS);
        return stored.get();
    }

    @Test
    @DisplayName("업로드 파일(원격 mtime·서버 ID 없음)도 분석이 끝까지 성공한다 — 종전 NPE 경로")
    void uploadedLogWithoutOriginAnalyzes() throws Exception {
        write("gc.log", unified(true));
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc.log");
        e.setServerName("guacmg1t");   // 업로드 화면에서 서버명만 입력 — serverId 는 없다
        GcLogAnalysisEntity done = runToEnd(e);
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, done.getStatus(), "오류: " + done.getErrorMessage());
        assertNull(done.getErrorMessage());
        assertEquals("G1", done.getCollector());
        assertEquals(5, done.getEventCount());
        assertEquals("absolute", done.getTimeSource());
        assertNotNull(done.getLogStart());
        assertNotNull(storedDetail.get(), "상세 JSON 저장");
        assertTrue(storedDetail.get().getResultJson().contains("\"collector\":\"G1\""));
        Mockito.verify(match).tryAutoMatch(Mockito.any(GcLogAnalysisEntity.class));
        assertTrue(service.loadResult("gc.log").isPresent());
    }

    @Test
    @DisplayName("업로드 + uptime 만 있는 로그 → 시간 출처 none (업로드 mtime 은 신뢰하지 않는다)")
    void uploadedUptimeOnlyLogHasNoTimeSource() throws Exception {
        write("gc-uptime.log", unified(false));
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc-uptime.log");
        GcLogAnalysisEntity done = runToEnd(e);
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, done.getStatus(), "오류: " + done.getErrorMessage());
        assertEquals("none", done.getTimeSource());
        assertNull(done.getLogStart());
    }

    @Test
    @DisplayName("전송 파일: 원격 mtime 이 있으면 그것, 없으면 로컬 파일 mtime 으로 기간을 추정한다")
    void transferredLogUsesRemoteThenLocalMtime() throws Exception {
        File f = write("gc-remote.log", unified(false));
        GcLogAnalysisEntity withRemote = new GcLogAnalysisEntity();
        withRemote.setFilename("gc-remote.log");
        withRemote.setServerId(7L);
        LocalDateTime remote = LocalDateTime.of(2026, 9, 14, 9, 0, 0);
        withRemote.setRemoteMtime(remote);
        GcLogAnalysisEntity done = runToEnd(withRemote);
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, done.getStatus(), "오류: " + done.getErrorMessage());
        assertEquals("mtime", done.getTimeSource());
        assertEquals(remote, done.getLogEnd(), "로그 끝 = 원격 stat mtime");

        assertEquals(remote.atZone(ZoneId.systemDefault()).toEpochSecond(), GcLogAnalyzerService.fallbackEndEpochSec(withRemote, f));
        GcLogAnalysisEntity localOnly = new GcLogAnalysisEntity();
        localOnly.setServerId(7L);
        assertEquals(f.lastModified() / 1000L, GcLogAnalyzerService.fallbackEndEpochSec(localOnly, f), "전송 파일은 로컬 mtime 폴백");
        assertNull(GcLogAnalyzerService.fallbackEndEpochSec(new GcLogAnalysisEntity(), f), "업로드 파일은 null — 예외가 아니라");
    }

    @Test
    @DisplayName("GC 로그가 아닌 파일·예상 밖 예외는 ERROR + 사람이 읽을 문구로 끝난다(실행기 스레드가 죽지 않는다)")
    void failuresEndAsErrorWithMessage() throws Exception {
        write("not-gc.log", "2026-09-14 00:00:00 INFO application started\nnothing else\n");
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("not-gc.log");
        GcLogAnalysisEntity done = runToEnd(e);
        assertEquals(GcLogAnalysisEntity.STATUS_ERROR, done.getStatus());
        assertTrue(done.getErrorMessage().startsWith("NOT_GC_LOG:"), done.getErrorMessage());

        write("boom.log", unified(true));
        Mockito.when(detailRepo.save(Mockito.any(GcLogResultDetailEntity.class))).thenThrow(new IllegalStateException("db down"));
        stored.set(null);
        storedDetail.set(null);
        GcLogAnalysisEntity b = new GcLogAnalysisEntity();
        b.setFilename("boom.log");
        GcLogAnalysisEntity failed = runToEnd(b);
        assertEquals(GcLogAnalysisEntity.STATUS_ERROR, failed.getStatus());
        assertTrue(failed.getErrorMessage().startsWith("내부 오류로 분석하지 못했습니다 (IllegalStateException: db down)"), failed.getErrorMessage());
    }
}

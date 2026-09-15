package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.GcLogResult;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.model.entity.GcLogResultDetailEntity;
import com.heapdump.analyzer.parser.gclog.GcLogResultCodec;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.repository.GcLogResultDetailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC 로그 AI 프롬프트(2026-09-16) — {@code == Full GC 분류 ==} 섹션·압박 요약 노트·힙 프롬프트용 GC 섹션·시스템 프롬프트 문구.
 * 옛 결과(블록 없음)는 섹션이 없다.
 */
class GcLogAiPromptTest {

    private GcLogAnalysisRepository repo;
    private GcLogResultDetailRepository detailRepo;
    private GcLogAnalyzerService service;

    @BeforeEach
    void setUp() {
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getGcLogDumpFilesDirectory()).thenReturn(System.getProperty("java.io.tmpdir"));
        repo = Mockito.mock(GcLogAnalysisRepository.class);
        detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        AnalysisHistoryRepository history = Mockito.mock(AnalysisHistoryRepository.class);
        Mockito.when(history.findByFilename(Mockito.anyString())).thenReturn(Optional.empty());
        service = new GcLogAnalyzerService(config, repo, detailRepo, history, Mockito.mock(GcLogMatchService.class),
                Mockito.mock(LlmConfigService.class), Mockito.mock(AiInsightManager.class),
                Mockito.mock(com.heapdump.analyzer.repository.DumpTransferLogRepository.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    private static GcLogResult result(boolean withSummary) {
        GcLogResult r = new GcLogResult();
        r.getMeta().setFormat("JDK8"); r.getMeta().setCollector("Parallel"); r.getMeta().setJdkVersion("1.8.0_392"); r.getMeta().setDurationSec(7200.0); r.getMeta().setTimeSource("absolute");
        r.getMeta().setHeapMaxBytes(1L << 30);
        r.getKpi().setEventCount(370); r.getKpi().setFullCount(13); r.getKpi().setFullGcPerHour(6.5); r.getKpi().setExplicitFullCount(2); r.getKpi().setSystemGcCount(2);
        r.getKpi().setPressureFullGcPerHour(5.5); r.getKpi().setThroughputPct(98.5); r.getKpi().setMaxHeapTotalBytes(1L << 30); r.getKpi().setLastHeapAfterBytes(964_000L << 10);
        r.getPauseStats().setCount(370); r.getPauseStats().setTotalMs(12000); r.getPauseStats().setP99Ms(900.0); r.getPauseStats().setMaxMs(2500.0);
        r.getTrend().setBasis("full");
        if (withSummary) {
            GcLogResult.FullGcSummary s = new GcLogResult.FullGcSummary();
            s.setTotal(13); s.setHeapPressureCount(9); s.setHeapPressurePerHour(4.5); s.setMaxConsecutivePressure(3);
            s.getByKind().put("EXPLICIT", 2); s.getByKind().put("METASPACE", 2); s.getByKind().put("GC_LOCKER", 0); s.getByKind().put("HEAP_PRESSURE", 9); s.getByKind().put("OTHER", 0);
            s.getPressureByCause().put("Ergonomics", 8); s.getPressureByCause().put("Allocation Failure", 1);
            s.setReclaimSamples(9); s.setReclaimPctMedian(14.0); s.setReclaimPctMin(3.6); s.setLowReclaimCount(6);
            s.setIntervalMedianFirstHalfSec(1500.0); s.setIntervalMedianSecondHalfSec(300.0); s.setIntervalShrinking(Boolean.TRUE); s.setIntervalCount(8);
            s.setLastAtSec(6900.0); s.setLastLine(377); s.setLastBeforeBytes(1_000_000L << 10); s.setLastAfterBytes(964_000L << 10); s.setLastTotalBytes(1_048_576L << 10);
            s.setLastAfterRatio(0.919); s.setLastAfterCapRatio(0.919);
            for (int i = 0; i < 4; i++) {
                GcLogResult.FullGcSample w = new GcLogResult.FullGcSample();
                w.setLine(377 - i * 10); w.setUptimeSec(6900.0 - i * 300); w.setCause(i == 0 ? "Allocation Failure" : "Ergonomics");
                w.setHeapBefore(980_000L << 10); w.setHeapAfter((900_000L + i * 10_000) << 10); w.setHeapTotal(1_048_576L << 10); w.setReclaimPct(3.6 + i * 2);
                s.getWorstReclaim().add(w);
            }
            r.setFullGcSummary(s);
        }
        return r;
    }

    private static GcLogAnalysisEntity entity() {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc-pressure.log"); e.setServerName("was01"); e.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS); e.setMatchSource("auto");
        return e;
    }

    @Test
    @DisplayName("결과 프롬프트에 '== Full GC 분류 ==' 섹션 — 분류·원인·회수율·간격·마지막 압박 Full·최저 3건, 12,000자 이내")
    void promptHasClassificationSection() {
        String p = service.buildGcPrompt(result(true), entity(), null);
        int at = p.indexOf("== Full GC 분류 ==");
        assertTrue(at > 0 && at < p.indexOf("== 이상 징후"), "Full GC·힙 추세 뒤, 이상 징후 앞");
        assertTrue(p.contains("전체 13회 = 메모리 압박 9회 · Metaspace 2회 · GCLocker 0회 · 명시적 2회 · 기타 0회"), p);
        assertTrue(p.contains("압박 Full GC 원인: Ergonomics 8, Allocation Failure 1 — 시간당 4.50회, 연속 최대 3회"), p);
        assertTrue(p.contains("압박 Full GC 회수율: 중앙값 14% · 최저 4% · 20% 미만 6회 (표본 9회)"), p);
        assertTrue(p.contains("압박 Full GC 간격: 앞 절반 중앙값 25m 00s → 뒤 절반 5m 00s (짧아짐)"), p);
        assertTrue(p.contains("마지막 압박 Full GC: uptime 1h 55m, 줄 377,") && p.contains("(92%), 최대 힙 대비 92%"), p);
        assertTrue(p.contains("회수율 최저 3건: 줄 377") && !p.contains("줄 347 "), "최저 3건만");
        assertTrue(p.contains("Full GC: 13회 (시간당 6.50회, 연속 최대 0회) — 그중 명시적 호출(System.gc() 등) Full 2회, 메모리 압박 Full GC 시간당 5.50회 (명시적 Full 은 Old 부족 신호가 아님)"
                + " · 메모리 압박 Full GC 9회(회수율 중앙값 14%, 마지막 직후 최대 힙 대비 92%)"), p);
        assertTrue(p.length() <= 12_000);
        assertTrue(GcLogAnalyzerService.GC_SYSTEM_PROMPT.contains("명시적 호출(System.gc()·힙 덤프)의 Full GC 는 메모리 압박 신호가 아니며")
                && GcLogAnalyzerService.GC_SYSTEM_PROMPT.contains("Critical|High|Medium|Low"), GcLogAnalyzerService.GC_SYSTEM_PROMPT);
    }

    @Test
    @DisplayName("옛 결과(분류 블록 없음)는 섹션도 압박 노트도 없다")
    void oldResultHasNoSection() {
        String p = service.buildGcPrompt(result(false), entity(), null);
        assertFalse(p.contains("== Full GC 분류 =="));
        assertFalse(p.contains("메모리 압박 Full GC 9회"), "압박 노트 없음(종전 explicitGcNote 의 시간당 문구는 그대로)");
        assertTrue(p.contains("메모리 압박 Full GC 시간당 5.50회"), "종전 explicitGcNote 는 유지");
        assertEquals("", GcLogAnalyzerService.fullGcClassificationSection(result(false)));
        assertEquals("", GcLogAnalyzerService.pressureNote(null));
        GcLogResult.FullGcSummary zero = new GcLogResult.FullGcSummary();
        zero.setTotal(72); zero.getByKind().put("EXPLICIT", 72);
        assertEquals("", GcLogAnalyzerService.pressureNote(zero), "압박 0회면 노트 없음");
        assertTrue(GcLogAnalyzerService.fullGcClassificationSection(withSummary(zero)).contains("압박 Full GC 없음 — 힙이 차서 일어난 Full GC 는 없습니다"));
    }

    private static GcLogResult withSummary(GcLogResult.FullGcSummary s) { GcLogResult r = result(false); r.setFullGcSummary(s); return r; }

    @Test
    @DisplayName("힙 덤프 프롬프트의 GC 섹션에도 압박 요약이 실린다(≤1500자)")
    void dumpSectionCarriesPressureNote() {
        GcLogAnalysisEntity e = entity();
        e.setMatchedDumpFilename("app.hprof");
        Mockito.when(repo.findByMatchedDumpFilename("app.hprof")).thenReturn(List.of(e));
        GcLogResultDetailEntity d = new GcLogResultDetailEntity();
        d.setFilename("gc-pressure.log"); d.setResultJson(GcLogResultCodec.toJson(result(true)));
        Mockito.when(detailRepo.findByFilename("gc-pressure.log")).thenReturn(Optional.of(d));
        String s = service.buildGcPromptSectionForDump("app.hprof");
        assertTrue(s.startsWith("== GC 로그 요약 =="), s);
        assertTrue(s.contains("메모리 압박 Full GC 9회(회수율 중앙값 14%, 마지막 직후 최대 힙 대비 92%)"), s);
        assertTrue(s.contains("압박 Full GC 회수율 중앙값 14% · 20% 미만 6회 · 간격 짧아지는 중"), s);
        assertTrue(s.length() <= 1500);
        Map<String, Object> summary = service.summaryForDump("gc-pressure.log", null);
        assertTrue(summary.get("fullGcSummary") instanceof GcLogResult.FullGcSummary, "힙 패널 축약에도 분류 블록");
    }
}

package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 결과 JSON 왕복 + 4MB 상한 축소(요약·소견 보존, 시계열·표만 감소). */
class GcLogResultCodecTest {

    @Test
    void roundTripPreservesSummaryAndToleratesUnknownFields() {
        GcLogResult r = new GcLogResult();
        r.getMeta().setCollector("G1");
        r.getKpi().setEventCount(3);
        r.getKpi().setThroughputPct(99.5);
        GcLogResult.Finding f = new GcLogResult.Finding();
        f.setCode("X"); f.setSeverity("High"); f.setTitle("t");
        r.getFindings().add(f);
        r.getTrend().getAfterPoints().add(new double[]{1, 2});
        String json = GcLogResultCodec.toJson(r);
        GcLogResult back = GcLogResultCodec.fromJson(json);
        assertNotNull(back);
        assertEquals("G1", back.getMeta().getCollector());
        assertEquals(99.5, back.getKpi().getThroughputPct());
        assertEquals("X", back.getFindings().get(0).getCode());
        assertEquals(2.0, back.getTrend().getAfterPoints().get(0)[1]);
        assertNotNull(GcLogResultCodec.fromJson(json.replaceFirst("\\{", "{\"futureField\":1,")));
        assertNull(GcLogResultCodec.fromJson("{not json"));
        assertNull(GcLogResultCodec.fromJson(""));
    }

    @Test
    void fullGcSummaryRoundTripsAndOldJsonLeavesItNull() {
        GcLogResult r = new GcLogResult();
        GcLogResult.FullGcSummary s = new GcLogResult.FullGcSummary();
        s.setTotal(3); s.setHeapPressureCount(2); s.getByKind().put("HEAP_PRESSURE", 2); s.getByKind().put("EXPLICIT", 1);
        s.getPressureByCause().put("Ergonomics", 2); s.setReclaimPctMedian(12.0); s.setIntervalShrinking(Boolean.TRUE);
        s.getPoints().add(new double[]{100, 2048, 4096});
        GcLogResult.FullGcSample w = new GcLogResult.FullGcSample(); w.setLine(7); w.setReclaimPct(3.5); s.getWorstReclaim().add(w);
        r.setFullGcSummary(s);
        GcLogResult.EventRow e = new GcLogResult.EventRow(); e.setSeq(0); e.setLine(7); e.setType("FULL"); e.setFullKind("HEAP_PRESSURE");
        r.getEvents().add(e);
        String json = GcLogResultCodec.toJson(r);
        GcLogResult back = GcLogResultCodec.fromJson(json);
        assertEquals(2, back.getFullGcSummary().getHeapPressureCount());
        assertEquals(2, back.getFullGcSummary().getPressureByCause().get("Ergonomics"));
        assertEquals(Boolean.TRUE, back.getFullGcSummary().getIntervalShrinking());
        assertEquals(4096.0, back.getFullGcSummary().getPoints().get(0)[2]);
        assertEquals(7, back.getFullGcSummary().getWorstReclaim().get(0).getLine());
        assertEquals("HEAP_PRESSURE", back.getEvents().get(0).getFullKind());

        // 분류 이전 옛 JSON — 블록은 null 이어야 화면이 '재분석 안내' 와 '압박 0회' 를 가른다(함정 58). 행의 fullKind 도 null
        String old = json.replaceFirst(",\"fullGcSummary\":\\{.*?\\}\\]\\}", "").replace(",\"fullKind\":\"HEAP_PRESSURE\"", "");
        assertTrue(!old.contains("fullGcSummary") && !old.contains("fullKind"), old);
        GcLogResult legacy = GcLogResultCodec.fromJson(old);
        assertNotNull(legacy);
        assertNull(legacy.getFullGcSummary());
        assertNull(legacy.getEvents().get(0).getFullKind());
        assertNull(new GcLogResult().getFullGcSummary(), "= new 로 초기화하면 옛 JSON 이 빈 블록이 된다");
    }

    @Test
    void oversizedResultIsTrimmedUnderCapWithFindingAndSummaryIntact() {
        GcLogResult r = new GcLogResult();
        r.getKpi().setEventCount(123456);
        GcLogResult.Series s = r.getSeries();
        List<GcLogResult.EventRow> events = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            s.getUptimeSec().add(i * 1.0); s.getHeapBefore().add(1L << 30); s.getHeapAfter().add(1L << 29);
            s.getHeapTotal().add(2L << 30); s.getPauseMs().add(12.345); s.getType().add("YOUNG");
            GcLogResult.EventRow e = new GcLogResult.EventRow();
            e.setSeq(i); e.setLine(i); e.setType(i % 1000 == 0 ? "FULL" : "YOUNG"); e.setCause("G1 Evacuation Pause");
            if (i % 1000 == 0) e.setFullKind("HEAP_PRESSURE");
            e.setPauseMs(12.3); e.setHeapBefore(1L << 30); e.setHeapAfter(1L << 29); e.setHeapTotal(2L << 30);
            e.setUserSec(0.1); e.setSysSec(0.0); e.setRealSec(0.1);
            events.add(e);
        }
        r.setEvents(events);
        for (int i = 0; i < 100; i++) { r.getRawSample().getHead().add("x".repeat(200)); r.getRawSample().getTail().add("y".repeat(200)); }
        assertTrue(GcLogResultCodec.toJson(r).getBytes(StandardCharsets.UTF_8).length > GcLogResultCodec.MAX_JSON_BYTES, "픽스처가 상한을 넘어야 의미 있다");

        String json = GcLogResultCodec.toJsonCapped(r);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= GcLogResultCodec.MAX_JSON_BYTES);
        GcLogResult back = GcLogResultCodec.fromJson(json);
        assertEquals(123456, back.getKpi().getEventCount(), "요약은 그대로");
        assertTrue(back.getSeries().getUptimeSec().size() < 60_000);
        assertTrue(back.getSeries().getMergeFactor() > 1);
        assertTrue(back.getEvents().size() < 60_000);
        assertTrue(back.getEvents().stream().filter(e -> "FULL".equals(e.getType())).count() >= 60, "Full 행은 우선 보존");
        assertTrue(back.getEvents().stream().filter(e -> "FULL".equals(e.getType())).allMatch(e -> "HEAP_PRESSURE".equals(e.getFullKind())), "축소 후에도 Full 행 분류 유지");
        assertTrue(back.getFindings().stream().anyMatch(f -> "RESULT_TRIMMED".equals(f.getCode())));
    }
}

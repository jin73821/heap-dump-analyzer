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
        assertTrue(back.getFindings().stream().anyMatch(f -> "RESULT_TRIMMED".equals(f.getCode())));
    }
}

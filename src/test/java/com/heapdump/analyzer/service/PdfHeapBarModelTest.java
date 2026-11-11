package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PDF 힙 사용률 바 — 분모가 -Xmx 인지(2026-11-11 버그: Xmx 2g·Total 300MB 가 99% 로 표시). */
class PdfHeapBarModelTest {

    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * MB;

    @Test
    @DisplayName("Xmx 2GB · Total 300MB → 15% (99% 아님)")
    void usesXmxAsDenominator() {
        Map<String, Object> m = PdfReportService.heapBarModel(300 * MB, 2 * GB);
        assertEquals(true, m.get("heapBarAvailable"));
        assertEquals(15L, m.get("usedBarPct"));
        assertEquals(85L, m.get("freeBarPct"));
        assertEquals("15%", m.get("heapUsedPctText"));
        assertEquals("Used 15%", m.get("usedSegText"));
        assertEquals("Free 85%", m.get("freeSegText"));
        assertEquals("2 GB", m.get("heapXmxText"));
        assertEquals(false, m.get("heapOverXmx"));
    }

    @Test
    @DisplayName("Xmx 미지정 → 바를 그리지 않는다")
    void noXmxHidesBar() {
        assertEquals(false, PdfReportService.heapBarModel(300 * MB, null).get("heapBarAvailable"));
        assertEquals(false, PdfReportService.heapBarModel(0, 2 * GB).get("heapBarAvailable"));
    }

    @Test
    @DisplayName("좁은 칸은 글자를 비우고, 0% 반올림이어도 1% 폭은 남긴다")
    void tinyUsage() {
        Map<String, Object> m = PdfReportService.heapBarModel(10 * MB, 8 * GB);
        assertEquals(1L, m.get("usedBarPct"));
        assertEquals("", m.get("usedSegText"));
        assertEquals("0%", m.get("heapUsedPctText"));
    }

    @Test
    @DisplayName("Total 이 Xmx 초과 → 바는 100% 로 자르고 실제 비율·경고 표시")
    void overXmx() {
        Map<String, Object> m = PdfReportService.heapBarModel(3 * GB, 2 * GB);
        assertEquals(100L, m.get("usedBarPct"));
        assertEquals(0L, m.get("freeBarPct"));
        assertEquals("150%", m.get("heapUsedPctText"));
        assertEquals(true, m.get("heapOverXmx"));
        assertEquals("0 B", m.get("heapFreeText"));
    }
}

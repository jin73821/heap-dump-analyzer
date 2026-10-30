package com.heapdump.analyzer.model.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 대시보드 탐지 현황 요약 칩·툴팁 파생값 (2026-10-31). */
class DetectionSummaryItemTest {

    private static DetectionSummaryItem item(int critical, int high, int medium, int low) {
        DetectionSummaryItem i = new DetectionSummaryItem();
        i.setCriticalCount(critical);
        i.setHighCount(high);
        i.setMediumCount(medium);
        i.setLowCount(low);
        return i;
    }

    @Test
    @DisplayName("요약 칩은 가장 높은 심각도와 그 건수 + 나머지 건수다")
    void chipShowsTopSeverityAndRest() {
        assertEquals("critical x3 +9", item(3, 5, 2, 2).getSeverityChip());
        assertEquals("high x2 +1", item(0, 2, 1, 0).getSeverityChip());
        assertEquals("low x2", item(0, 0, 0, 2).getSeverityChip());
        assertEquals("critical", item(3, 5, 2, 2).getTopSeverity());
        assertEquals("medium", item(0, 0, 1, 9).getTopSeverity());
    }

    @Test
    @DisplayName("툴팁은 건수가 있는 심각도만 높은 순으로 한 줄씩 보인다")
    void tipListsOnlyPresentSeverities() {
        assertEquals("critical 3건\nhigh 5건\nmedium 2건\nlow 2건", item(3, 5, 2, 2).getSeverityTip());
        assertEquals("high 2건\nlow 1건", item(0, 2, 0, 1).getSeverityTip());
    }

    @Test
    @DisplayName("집계가 전부 0 이면 칩이 없다")
    void noSeverityMeansNoChip() {
        DetectionSummaryItem none = item(0, 0, 0, 0);
        assertNull(none.getTopSeverity());
        assertNull(none.getSeverityChip());
        assertEquals("", none.getSeverityTip());
    }

    @Test
    @DisplayName("파생값은 화면 전용이다 — /api/history/detections JSON 에 싣지 않는다")
    void derivedValuesStayOutOfApiJson() {
        String json = JsonMapper.builder().build().writeValueAsString(item(3, 5, 2, 2));
        assertTrue(json.contains("\"criticalCount\":3"), "기존 필드는 그대로여야 한다");
        assertFalse(json.contains("topSeverity") || json.contains("severityChip") || json.contains("severityTip"), json);
    }
}

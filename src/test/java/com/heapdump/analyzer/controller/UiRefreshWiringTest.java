package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 화면 자동 갱신 주기 — 모델 전달(UiRefreshModelAdvice) + 대시보드 차트 데이터 섬 이스케이프 (2026-09-26). */
class UiRefreshWiringTest {

    @Test
    @DisplayName("ModelAdvice 는 설정값을 그대로 내리고, 0 이하면 60 으로 막는다")
    void adviceExposesConfiguredInterval() {
        HeapDumpConfig config = new HeapDumpConfig();
        UiRefreshModelAdvice advice = new UiRefreshModelAdvice(config);
        config.setUiRefreshIntervalSec(30);
        assertEquals(30, advice.uiRefreshSeconds());
        config.setUiRefreshIntervalSec(10);
        assertEquals(10, advice.uiRefreshSeconds());
        config.setUiRefreshIntervalSec(0);   // 설정 손상 — 클라이언트가 0ms 폴링을 하지 않게
        assertEquals(60, advice.uiRefreshSeconds());
    }

    @Test
    @DisplayName("차트 데이터 섬은 '</' 를 이스케이프해 <script> 블록을 조기 종료시키지 않는다 (함정 51)")
    void dataIslandEscapesScriptTerminator() {
        String json = HeapDumpViewController.toDataIsland(
                java.util.List.of(java.util.Map.of("date", "2026-09-26", "note", "</script><b>x</b>")),
                java.util.List.of());
        assertFalse(json.contains("</"), "'</' 가 남아 있다: " + json);
        assertTrue(json.contains("<\\/script>"), "이스케이프 형태가 다르다: " + json);
        assertTrue(json.startsWith("{\"daily\":[") && json.contains("\"series\":[]"), json);
        assertEquals("{\"daily\":[],\"series\":[]}", HeapDumpViewController.toDataIsland(null, null), "null 은 빈 배열");
    }
}

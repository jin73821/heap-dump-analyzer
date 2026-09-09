package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** source_type 집계는 순수 함수라 HTTP 없이 픽스처로 검증한다. */
class ChromaSourceStatsTest {

    private static Map<String, Object> resp(List<String> ids, List<Map<String, Object>> metas) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ids", ids);
        m.put("metadatas", metas);
        return m;
    }

    private static Map<String, Object> meta(String sourceType, Boolean synthetic) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_type", sourceType);
        if (synthetic != null) m.put("synthetic", synthetic);
        return m;
    }

    @Test
    @DisplayName("청크 수는 세고 #n 접미사를 접어 문서 수를 낸다")
    void countsChunksAndDocs() {
        Map<String, Object> r = resp(
                List.of("csv:a#0", "csv:a#1", "csv:b", "leak_lib:1#0"),
                List.of(meta("csv", false), meta("csv", false), meta("csv", true), meta("leak_lib", null)));

        Map<String, Object> agg = ChromaSearchService.aggregateSourceTypes(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) agg.get("sources");

        Map<String, Object> csv = sources.stream()
                .filter(s -> "csv".equals(s.get("sourceType"))).findFirst().orElseThrow();
        assertEquals(3, csv.get("chunks"));
        assertEquals(2, csv.get("docs"), "csv:a 의 두 청크는 한 문서다");
        assertEquals(1, csv.get("syntheticChunks"));
        assertEquals(4, agg.get("scanned"));
        assertEquals(1, agg.get("syntheticTotal"));
    }

    @Test
    @DisplayName("검색 제외 소스를 표시한다 — 화면이 '색인은 됐지만 안 쓰인다'를 알려야 한다")
    void marksExcluded() {
        Map<String, Object> agg = ChromaSearchService.aggregateSourceTypes(
                resp(List.of("ai_chat:1#0", "csv:a"), List.of(meta("ai_chat", null), meta("csv", null))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) agg.get("sources");
        for (Map<String, Object> s : sources) {
            if ("ai_chat".equals(s.get("sourceType"))) assertTrue((Boolean) s.get("excluded"));
            if ("csv".equals(s.get("sourceType"))) assertFalse((Boolean) s.get("excluded"));
        }
    }

    @Test
    @DisplayName("청크 많은 순으로 정렬한다 (--stats 와 같은 순서)")
    void sortedByChunksDesc() {
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> metas = new ArrayList<>();
        for (int i = 0; i < 5; i++) { ids.add("big:" + i); metas.add(meta("big", null)); }
        for (int i = 0; i < 2; i++) { ids.add("small:" + i); metas.add(meta("small", null)); }

        Map<String, Object> agg = ChromaSearchService.aggregateSourceTypes(resp(ids, metas));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) agg.get("sources");
        assertEquals("big", sources.get(0).get("sourceType"));
        assertEquals("small", sources.get(1).get("sourceType"));
    }

    @Test
    @DisplayName("메타가 비어도 예외 없이 빈 집계를 낸다")
    void emptySafe() {
        Map<String, Object> agg = ChromaSearchService.aggregateSourceTypes(new LinkedHashMap<>());
        assertEquals(0, agg.get("scanned"));
        assertTrue(((List<?>) agg.get("sources")).isEmpty());
    }
}

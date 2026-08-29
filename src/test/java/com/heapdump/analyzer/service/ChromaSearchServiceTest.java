package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chroma 응답 정규화와 스코어 변환.
 *
 * <p>방어 대상은 두 가지다.
 * <ol>
 *   <li><b>스코어 방향 역전</b> — ES 는 score 가 클수록, Chroma 는 distance 가 작을수록
 *       좋다. 부호를 잘못 잡으면 <b>최악 문서가 최상위</b>로 올라오는데, 에러도 로그도
 *       없이 "그럴듯한 오답"만 나와 눈으로는 거의 못 잡는다. 단조성 단언이 이걸 잡는다.</li>
 *   <li><b>null 필드</b> — {@code include} 에서 빠진 필드는 빈 배열이 아니라 <b>null</b>
 *       로 온다(실측 확인). 언랩 코드가 NPE 를 내면 검색 전체가 실패한다.</li>
 * </ol>
 */
class ChromaSearchServiceTest {

    /** Chroma 응답 모양 — 질의당 중첩 배열(depth 2). */
    private static Map<String, Object> resp(List<Object> ids, List<Object> dists,
                                            List<Object> docs, List<Object> metas) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ids", ids == null ? null : Collections.singletonList(ids));
        m.put("distances", dists == null ? null : Collections.singletonList(dists));
        m.put("documents", docs == null ? null : Collections.singletonList(docs));
        m.put("metadatas", metas == null ? null : Collections.singletonList(metas));
        m.put("embeddings", null);   // include 하지 않으면 null 로 온다
        m.put("uris", null);
        return m;
    }

    @Test
    @DisplayName("cosine: score = 1 - distance, 실측값(1.0/0.8/0.0)과 일치")
    void cosineConversionMatchesMeasuredValues() {
        assertEquals(1.0, ChromaSearchService.toScore(0.0, "cosine"), 1e-9);
        assertEquals(0.8, ChromaSearchService.toScore(0.2, "cosine"), 1e-9);
        assertEquals(0.0, ChromaSearchService.toScore(1.0, "cosine"), 1e-9);
    }

    @Test
    @DisplayName("l2 / ip 변환식")
    void otherSpaces() {
        assertEquals(0.5, ChromaSearchService.toScore(1.0, "l2"), 1e-9);
        assertEquals(0.9, ChromaSearchService.toScore(-0.9, "ip"), 1e-9);
    }

    @Test
    @DisplayName("space 가 null/미지값이면 cosine 으로 폴백한다")
    void unknownSpaceFallsBackToCosine() {
        assertEquals(0.7, ChromaSearchService.toScore(0.3, null), 1e-9);
        assertEquals(0.7, ChromaSearchService.toScore(0.3, "euclidean-오타"), 1e-9);
    }

    @Test
    @DisplayName("distance 오름차순이 score 내림차순으로 보존된다 (부호 오류 탐지)")
    void scoreIsMonotonicallyNonIncreasing() {
        Map<String, Object> json = resp(
                Arrays.asList("a", "b", "c"),
                Arrays.asList(0.0, 0.2, 1.0),
                Arrays.asList("문서 A", "문서 B", "문서 C"),
                null);
        List<Map<String, Object>> hits = ChromaSearchService.normalizeHits(json, "cosine", 0);
        assertEquals(3, hits.size());
        double prev = Double.MAX_VALUE;
        for (Map<String, Object> h : hits) {
            double s = (Double) h.get("score");
            assertTrue(s <= prev + 1e-9, "score 가 증가했다 — 부호가 뒤집혔을 수 있다: " + hits);
            prev = s;
        }
        assertEquals("a", hits.get(0).get("id"));
    }

    @Test
    @DisplayName("minScore 는 변환된 score 기준으로 적용된다")
    void minScoreFiltersOnConvertedScore() {
        Map<String, Object> json = resp(
                Arrays.asList("a", "b", "c"),
                Arrays.asList(0.1, 0.5, 0.9),     // score 0.9 / 0.5 / 0.1
                Arrays.asList("A", "B", "C"), null);
        List<Map<String, Object>> hits = ChromaSearchService.normalizeHits(json, "cosine", 0.45);
        assertEquals(2, hits.size(), "0.45 미만인 c 만 걸러져야 한다");
        assertEquals("a", hits.get(0).get("id"));
        assertEquals("b", hits.get(1).get("id"));
    }

    @Test
    @DisplayName("documents 가 null 이면 metadata 로 폴백하고 NPE 를 내지 않는다")
    void nullDocumentsFallsBackToMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source_type", "leak_lib");
        Map<String, Object> json = resp(
                Collections.singletonList("x"),
                Collections.singletonList(0.2),
                null,                                    // include 에서 빠짐
                Collections.singletonList(meta));
        List<Map<String, Object>> hits = ChromaSearchService.normalizeHits(json, "cosine", 0);
        assertEquals(1, hits.size());
        assertTrue(String.valueOf(hits.get(0).get("content")).contains("leak_lib"));
    }

    @Test
    @DisplayName("빈 결과·필드 누락에도 예외 없이 빈 목록을 돌려준다")
    void emptyAndMissingFieldsAreSafe() {
        assertTrue(ChromaSearchService.normalizeHits(
                resp(new ArrayList<>(), null, null, null), "cosine", 0).isEmpty());
        assertTrue(ChromaSearchService.normalizeHits(
                new LinkedHashMap<>(), "cosine", 0).isEmpty());

        // distances 만 누락 — score 0 으로 처리되고 크래시하지 않는다
        Map<String, Object> json = resp(Collections.singletonList("a"), null,
                Collections.singletonList("본문"), null);
        List<Map<String, Object>> hits = ChromaSearchService.normalizeHits(json, "cosine", 0);
        assertEquals(1, hits.size());
        assertEquals(1.0, (Double) hits.get(0).get("score"), 1e-9);   // 1 - 0.0
    }

    @Test
    @DisplayName("정규화 결과는 ES 경로와 동일한 계약(id/score/content)을 지킨다")
    void keepsElasticsearchContract() {
        Map<String, Object> json = resp(
                Collections.singletonList("csv:oom-2026-001"),
                Collections.singletonList(0.355),
                Collections.singletonList("Old Generation OOM"), null);
        Map<String, Object> hit = ChromaSearchService.normalizeHits(json, "cosine", 0).get(0);
        assertTrue(hit.containsKey("id"));
        assertTrue(hit.containsKey("score"));
        assertTrue(hit.containsKey("content"));
        assertEquals("csv:oom-2026-001", hit.get("id"));
    }
}

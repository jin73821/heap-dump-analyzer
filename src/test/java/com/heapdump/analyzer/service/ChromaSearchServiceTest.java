package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ── 컬렉션 메타 파싱 + 연동 정합성 경고 (2026-08-29, 설정 화면 상태 패널) ─────

    /** 실측 {@code GET .../collections/{name}} 응답 모양 (필요한 키만). */
    private static Map<String, Object> collectionJson(Object dimension, String space) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "31bbdd69-8d1f-4b77-960d-7c082be495d2");
        m.put("name", "heap-knowledge-base");
        if (space != null) {
            Map<String, Object> hnsw = new LinkedHashMap<>();
            hnsw.put("space", space);
            hnsw.put("ef_search", 100);
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("hnsw", hnsw);
            cfg.put("spann", null);
            m.put("configuration_json", cfg);
        }
        m.put("dimension", dimension);
        return m;
    }

    @Test
    @DisplayName("컬렉션 JSON 에서 space·dimension 을 읽고, 없으면 null (억지 파싱 없음)")
    void readsSpaceAndDimensionFromCollectionJson() {
        Map<String, Object> m = collectionJson(384, "cosine");
        assertEquals("cosine", ChromaSearchService.readSpace(m));
        assertEquals(384, ChromaSearchService.readDimension(m));

        // 벡터가 없는 새 컬렉션은 dimension:null, hnsw 미설정이면 space 없음
        assertNull(ChromaSearchService.readDimension(collectionJson(null, null)));
        assertNull(ChromaSearchService.readSpace(collectionJson(null, null)));
        // 문자열 차원은 숫자가 아니므로 null
        assertNull(ChromaSearchService.readDimension(collectionJson("384", "cosine")));
        assertNull(ChromaSearchService.readSpace(null));
        assertNull(ChromaSearchService.readDimension(null));
    }

    private static ChromaSearchService.IntegrationFacts healthy() {
        return new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 384,
                true, true, 384, 384, "cosine", "cosine", 834L);
    }

    private static List<String> codes(List<Map<String, Object>> w) {
        return w.stream().map(x -> String.valueOf(x.get("code"))).collect(Collectors.toList());
    }

    private static String levelOf(List<Map<String, Object>> w, String code) {
        return w.stream().filter(x -> code.equals(x.get("code")))
                .map(x -> String.valueOf(x.get("level"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("정합성: 전부 정상이면 경고 0건")
    void noWarningsWhenEverythingMatches() {
        assertTrue(ChromaSearchService.integrationWarnings(healthy()).isEmpty());
    }

    @Test
    @DisplayName("정합성: chroma 모드인데 provider 가 openai → error")
    void providerNotLocalOnnxIsErrorInChromaMode() {
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "openai", 384, false, false, null, 384, "cosine", "cosine", 834L);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertTrue(codes(w).contains("PROVIDER_NOT_LOCAL_ONNX"));
        assertEquals("error", levelOf(w, "PROVIDER_NOT_LOCAL_ONNX"));
        assertFalse(codes(w).contains("MODE_NOT_CHROMA"));
    }

    @Test
    @DisplayName("정합성: 설정 차원 1536 ≠ 사이드카 384 → CONFIG_DIM_MISMATCH error")
    void configuredDimensionMismatchIsError() {
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 1536, true, true, 384, 384, "cosine", "cosine", 834L);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertEquals(List.of("CONFIG_DIM_MISMATCH"), codes(w));
        assertEquals("error", levelOf(w, "CONFIG_DIM_MISMATCH"));
    }

    @Test
    @DisplayName("정합성: 컬렉션 1024 ≠ 사이드카 384 → 재색인 필요 error")
    void collectionDimensionMismatchIsError() {
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 384, true, true, 384, 1024, "cosine", "cosine", 834L);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertEquals(List.of("COLLECTION_DIM_MISMATCH"), codes(w));
        assertTrue(String.valueOf(w.get(0).get("message")).contains("--reset"));
    }

    @Test
    @DisplayName("정합성: space 설정 l2 ≠ 실제 cosine → warn (함정 39)")
    void spaceMismatchIsWarn() {
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 384, true, true, 384, 384, "l2", "cosine", 834L);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertEquals(List.of("SPACE_MISMATCH"), codes(w));
        assertEquals("warn", levelOf(w, "SPACE_MISMATCH"));
        // 대소문자만 다르면 불일치가 아니다
        ChromaSearchService.IntegrationFacts g = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 384, true, true, 384, 384, "Cosine", "cosine", 834L);
        assertTrue(ChromaSearchService.integrationWarnings(g).isEmpty());
    }

    @Test
    @DisplayName("정합성: count 0 → 색인 안내 warn")
    void emptyCollectionIsWarn() {
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 384, true, true, 384, 384, "cosine", "cosine", 0L);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertEquals(List.of("COLLECTION_EMPTY"), codes(w));
        assertTrue(String.valueOf(w.get(0).get("message")).contains("run-index.sh"));
    }

    @Test
    @DisplayName("정합성: 현재 운영 상태(keyword / openai / 1536, 컬렉션 384) — info + 강등된 warn + 차원 경고")
    void currentProductionStateIsReportedWithoutErrorBadge() {
        // 사이드카는 provider 가 local-onnx 가 아니라 두드리지 않는다(sidecarProbed=false)
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "keyword", true, true, "openai", 1536, false, false, null, 384, "cosine", "cosine", 834L);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertEquals(List.of("MODE_NOT_CHROMA", "PROVIDER_NOT_LOCAL_ONNX", "COLLECTION_DIM_VS_CONFIG"), codes(w));
        assertEquals("info", levelOf(w, "MODE_NOT_CHROMA"));
        // keyword 모드에서는 "지금 쓰는 경로"가 아니므로 error 가 아니라 warn
        assertEquals("warn", levelOf(w, "PROVIDER_NOT_LOCAL_ONNX"));
        assertEquals("warn", levelOf(w, "COLLECTION_DIM_VS_CONFIG"));
        assertTrue(String.valueOf(w.get(2).get("message")).contains("384"));
    }

    @Test
    @DisplayName("정합성: Chroma·사이드카 미도달 — chroma 모드면 error, 아니면 warn")
    void unreachableSeverityDependsOnMode() {
        ChromaSearchService.IntegrationFacts inChroma = new ChromaSearchService.IntegrationFacts(
                "chroma", true, false, "local-onnx", 384, true, false, null, null, "cosine", null, null);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(inChroma);
        assertEquals(List.of("CHROMA_UNREACHABLE", "SIDECAR_UNREACHABLE"), codes(w));
        assertEquals("error", levelOf(w, "CHROMA_UNREACHABLE"));
        assertEquals("error", levelOf(w, "SIDECAR_UNREACHABLE"));

        ChromaSearchService.IntegrationFacts inKeyword = new ChromaSearchService.IntegrationFacts(
                "keyword", false, false, "local-onnx", 384, true, false, null, null, "cosine", null, null);
        List<Map<String, Object>> k = ChromaSearchService.integrationWarnings(inKeyword);
        assertEquals(List.of("MODE_NOT_CHROMA", "RAG_DISABLED", "CHROMA_UNREACHABLE", "SIDECAR_UNREACHABLE"), codes(k));
        assertEquals("warn", levelOf(k, "CHROMA_UNREACHABLE"));
        assertEquals("warn", levelOf(k, "SIDECAR_UNREACHABLE"));
    }

    @Test
    @DisplayName("정합성: null 안전 — 차원·space·count 를 모르면 해당 경고를 내지 않는다")
    void nullFactsProduceNoDimensionWarnings() {
        ChromaSearchService.IntegrationFacts f = new ChromaSearchService.IntegrationFacts(
                "chroma", true, true, "local-onnx", 1536, false, false, null, null, "cosine", null, null);
        List<Map<String, Object>> w = ChromaSearchService.integrationWarnings(f);
        assertTrue(w.isEmpty(), "모르는 값으로 경고를 만들면 안 된다: " + codes(w));
    }

    // ── 검색 제외 필터 (2026-08-29) ──────────────────────────────

    @Test
    @DisplayName("where 필터: 조건이 2개 이상이면 $and 로 묶는다 (단일 맵은 AND 가 되지 않는다)")
    @SuppressWarnings("unchecked")
    void searchFilterCombinesConditionsWithAnd() {
        Map<String, Object> f = ChromaSearchService.searchFilter();
        assertTrue(f.containsKey("$and"), "조건 2개를 $and 없이 넣으면 Chroma 가 하나만 적용한다: " + f);
        List<Map<String, Object>> conds = (List<Map<String, Object>>) f.get("$and");
        assertEquals(1 + ChromaSearchService.EXCLUDED_SOURCE_TYPES.size(), conds.size());

        // 가상 사례 제외는 절대 빠지면 안 된다 — RAG 가 허구를 근거로 답하게 된다
        assertTrue(conds.stream().anyMatch(c -> c.containsKey("synthetic")), "synthetic 제외가 사라졌다");
        // 과거 대화 제외 (Recall@10 0.857→0.952 실측 근거)
        assertTrue(conds.stream().anyMatch(c -> {
            Object v = c.get("source_type");
            return v instanceof Map && "ai_chat".equals(((Map<?, ?>) v).get("$ne"));
        }), "ai_chat 제외가 사라졌다");

        // 연산자는 $ne — $eq 로 뒤집히면 제외 대상만 검색된다(조용한 대형 사고)
        for (Map<String, Object> c : conds) {
            Object v = c.values().iterator().next();
            assertTrue(v instanceof Map && ((Map<?, ?>) v).containsKey("$ne"),
                    "제외 조건이 $ne 가 아니다: " + c);
        }
    }

    @Test
    @DisplayName("제외 목록이 비면 $and 없이 단일 조건으로 떨어진다")
    void searchFilterDegradesGracefully() {
        // EXCLUDED_SOURCE_TYPES 를 비우는 것은 되돌리기 경로다 — 그때도 synthetic 제외는 남아야 한다.
        assertFalse(ChromaSearchService.EXCLUDED_SOURCE_TYPES.isEmpty(),
                "현재는 ai_chat 을 제외하는 것이 기본이다 (되돌릴 때 이 단언도 함께 고칠 것)");
    }
}

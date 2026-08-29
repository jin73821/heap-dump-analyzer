package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 살아 있는 Chroma·임베딩 사이드카에 붙는 통합 확인. <b>기본 비활성</b>이다.
 *
 * <pre>mvn test -Dtest=ChromaLiveIntegrationTest -Dchroma.live=true</pre>
 *
 * <p>일반 {@code mvn test} 에서 돌리지 않는 이유는 명확하다 — 외부 프로세스
 * 두 개(chroma:8000, chroma-embed:8001)와 색인된 데이터에 의존하므로,
 * 빌드 서버나 개발자 PC 에서는 존재하지 않는 이유로 실패한다. 단위 테스트가
 * 검증하는 것은 {@link ChromaSearchServiceTest} 쪽이고, 이 파일은
 * 배포 후 손으로 한 번 돌려 실제 배선을 확인하는 용도다.
 */
@EnabledIfSystemProperty(named = "chroma.live", matches = "true")
class ChromaLiveIntegrationTest {

    private static ChromaSearchService wire() {
        RagConfigService cfg = new RagConfigService(new HeapDumpConfig());
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("ragEnabled", "true");
        saved.put("ragSearchMode", "chroma");
        saved.put("ragTopK", "3");
        saved.put("ragMinScore", "0");
        saved.put("ragChromaUrl", "http://127.0.0.1:8000");
        saved.put("ragChromaApiPath", "/api/v2");
        saved.put("ragChromaTenant", "default_tenant");
        saved.put("ragChromaDatabase", "default_database");
        saved.put("ragChromaCollection", "heap-knowledge-base");
        saved.put("ragChromaSpace", "cosine");
        saved.put("ragChromaAuthType", "none");
        saved.put("ragChromaTimeoutSeconds", "15");
        saved.put("ragChromaSslVerify", "false");
        // 질의 임베딩은 사이드카가 담당한다 (Chroma 서버는 임베딩을 하지 않는다)
        saved.put("ragEmbeddingProvider", "local-onnx");
        saved.put("ragEmbeddingApiUrl", "http://127.0.0.1:8001/embed");
        saved.put("ragEmbeddingDimension", "384");
        saved.put("ragEmbeddingTimeoutSeconds", "15");
        cfg.applyFromSettings(saved);
        return new ChromaSearchService(cfg, new EmbeddingService(cfg));
    }

    @Test
    @DisplayName("연결 테스트 — API 버전·컬렉션·문서 수·space 를 읽어온다")
    void connectionTest() {
        Map<String, Object> r = wire().testConnection(new LinkedHashMap<>());
        System.out.println("[live] testConnection = " + r);
        assertTrue((Boolean) r.get("success"), "연결 실패: " + r.get("error"));
        assertNotNull(r.get("apiVersion"));
        assertNotNull(r.get("collectionId"));
        assertEquals("cosine", r.get("space"));
        assertFalse("0".equals(r.get("count")), "컬렉션이 비어 있다 — run-index.sh 를 먼저 실행하라");
    }

    @Test
    @DisplayName("검색 — 도메인 질의가 관련 문서를 최상위로 돌려준다")
    @SuppressWarnings("unchecked")
    void searchReturnsRelevantHits() {
        Map<String, Object> r = wire().search("Tibero JDBC 커서가 닫히지 않아 누수가 생깁니다", null);
        assertTrue((Boolean) r.get("success"), "검색 실패: " + r.get("error"));

        List<Map<String, Object>> hits = (List<Map<String, Object>>) r.get("hits");
        assertFalse(hits.isEmpty(), "결과가 0건이다");
        for (Map<String, Object> h : hits) {
            System.out.printf("[live] %.3f  %s%n", (Double) h.get("score"),
                    String.valueOf(h.get("content")).split("\n")[0]);
        }
        // ES 경로와 동일한 계약
        assertTrue(hits.get(0).containsKey("id"));
        assertTrue(hits.get(0).containsKey("score"));
        assertTrue(hits.get(0).containsKey("content"));
        assertTrue(String.valueOf(hits.get(0).get("content")).contains("Tibero"),
                "가장 관련 있는 문서가 1위가 아니다: " + hits.get(0).get("content"));

        // score 는 클수록 좋아야 한다 (ES 규약과 동일 방향)
        double prev = Double.MAX_VALUE;
        for (Map<String, Object> h : hits) {
            double s = (Double) h.get("score");
            assertTrue(s <= prev + 1e-9, "score 방향이 뒤집혔다");
            prev = s;
        }
    }

    @Test
    @DisplayName("가상 사례(synthetic)는 검색 결과에서 제외된다")
    @SuppressWarnings("unchecked")
    void syntheticDocsAreExcluded() {
        Map<String, Object> r = wire().search("사내 트러블슈팅 사례를 알려줘", null);
        assertTrue((Boolean) r.get("success"));
        for (Map<String, Object> h : (List<Map<String, Object>>) r.get("hits")) {
            Object meta = h.get("metadata");
            if (meta instanceof Map) {
                assertFalse(Boolean.TRUE.equals(((Map<String, Object>) meta).get("synthetic")),
                        "가상 사례가 결과에 섞였다: " + h.get("id"));
            }
        }
    }
}

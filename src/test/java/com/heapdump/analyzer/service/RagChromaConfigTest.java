package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chroma 설정의 영속화 왕복과 화이트리스트.
 *
 * <p><b>방어 대상</b> — RAG 설정은 다섯 곳을 동시에 고쳐야 산다:
 * {@code HeapDumpConfig} 의 {@code @Value} → {@code init()} →
 * {@code applyFromSettings} → {@code collectSettings} →
 * {@code collectApplicationProperties}. 하나라도 빠지면 <b>예외 없이 값만 조용히
 * 유실</b>되고, 보통 다음 재기동 때야 드러난다. 아래 스윕 테스트가 필드 추가를
 * 강제로 감시한다(기존 필드까지 소급 보호된다).
 */
class RagChromaConfigTest {

    private RagConfigService rag;

    @BeforeEach
    void setUp() {
        rag = new RagConfigService(new HeapDumpConfig());
    }

    // ── 동기화 스윕 ──────────────────────────────────────────────

    @Test
    @DisplayName("모든 volatile 필드가 collectSettings 와 collectApplicationProperties 에 존재한다")
    void everyMutableFieldIsPersisted() {
        Map<String, Object> settings = new LinkedHashMap<>();
        rag.collectSettings(settings);
        Map<String, String> props = new LinkedHashMap<>();
        rag.collectApplicationProperties(props);

        // settings.json 키는 필드명 그대로라 정확 비교가 가능하다.
        List<String> missingSettings = new ArrayList<>();
        int volatileCount = 0;
        for (Field f : RagConfigService.class.getDeclaredFields()) {
            if (!Modifier.isVolatile(f.getModifiers())) continue;   // 시크릿(final SecretValue)은 아래 카운트로
            volatileCount++;
            if (!settings.containsKey(f.getName())) missingSettings.add(f.getName());
        }
        assertTrue(missingSettings.isEmpty(),
                "collectSettings 누락 — 재기동 시 조용히 유실된다: " + missingSettings);

        // application.properties 키는 케밥-닷 표기라(rag.elasticsearch.auth-type ↔ ragAuthType)
        // 이름으로는 대조할 수 없다. 대신 개수 정합성으로 누락을 잡는다 —
        // 두 훅은 같은 필드 집합을 열거하므로 개수가 어긋나면 한쪽이 빠진 것이다.
        assertEquals(settings.size(), props.size(),
                "collectSettings(" + settings.size() + ")와 collectApplicationProperties("
                        + props.size() + ") 의 항목 수가 다르다 — 한쪽에 필드가 누락됐다");
        assertEquals(volatileCount + 4, settings.size(),
                "volatile 필드 " + volatileCount + " + 시크릿 4 와 settings 키 수가 맞지 않는다");
    }

    @Test
    @DisplayName("시크릿 4종이 모두 settings 에 기록된다 (chroma 토큰 포함)")
    void allFourSecretsArePersisted() {
        rag.setRagConfig("http://es", "basic", "u", "pw", "ak", "idx", true,
                "keyword", "content", 5, 0.5, 10);
        rag.setRagEmbeddingConfig("openai", "http://emb", "ek", "m", 1536, 15, "embedding", 50);
        rag.setRagChromaConfig("http://127.0.0.1:8000", "/api/v2", "t", "d", "col",
                "token", "chroma-tok", "cosine", 15, true);

        Map<String, Object> s = new LinkedHashMap<>();
        rag.collectSettings(s);
        for (String key : new String[]{"ragPassword", "ragApiKey", "ragEmbeddingApiKey", "ragChromaToken"}) {
            assertTrue(s.containsKey(key), key + " 가 settings 에 없다");
            assertTrue(String.valueOf(s.get(key)).startsWith("ENC("), key + " 가 평문으로 저장됐다");
        }
    }

    // ── 왕복 ────────────────────────────────────────────────────

    @Test
    @DisplayName("setter → collectSettings → 새 인스턴스 applyFromSettings 왕복에서 값이 보존된다")
    void chromaConfigSurvivesRoundTrip() {
        rag.setRagChromaConfig("http://vec.local:9000", "/api/v3", "tn", "db", "kb",
                "token", "secret-token", "l2", 42, false);

        Map<String, Object> saved = new LinkedHashMap<>();
        rag.collectSettings(saved);

        RagConfigService restored = new RagConfigService(new HeapDumpConfig());
        restored.applyFromSettings(saved);

        assertEquals("http://vec.local:9000", restored.getRagChromaUrl());
        assertEquals("/api/v3", restored.getRagChromaApiPath());
        assertEquals("tn", restored.getRagChromaTenant());
        assertEquals("db", restored.getRagChromaDatabase());
        assertEquals("kb", restored.getRagChromaCollection());
        assertEquals("token", restored.getRagChromaAuthType());
        assertEquals("secret-token", restored.getRagChromaToken());
        assertEquals("l2", restored.getRagChromaSpace());
        assertEquals(42, restored.getRagChromaTimeoutSeconds());
        assertFalse(restored.isRagChromaSslVerify());
    }

    @Test
    @DisplayName("토큰 3상태 — null=유지 / \"\"=삭제 / 값=교체")
    void tokenThreeStateSemantics() {
        rag.setRagChromaConfig(null, null, null, null, null, null, "tok-1", null, -1, true);
        assertEquals("tok-1", rag.getRagChromaToken());

        rag.setRagChromaConfig(null, null, null, null, null, null, null, null, -1, true);
        assertEquals("tok-1", rag.getRagChromaToken(), "null 은 기존 값을 유지해야 한다");

        rag.setRagChromaConfig(null, null, null, null, null, null, "", null, -1, true);
        assertEquals("", rag.getRagChromaToken(), "빈 문자열은 삭제여야 한다");
    }

    // ── 화이트리스트 ─────────────────────────────────────────────

    @Test
    @DisplayName("space 오타는 cosine 으로 되돌린다 (스코어 방향 역전 방지)")
    void unknownSpaceIsRejected() {
        rag.setRagChromaConfig(null, null, null, null, null, null, null, "cosin-오타", -1, true);
        assertEquals("cosine", rag.getRagChromaSpace());
        rag.setRagChromaConfig(null, null, null, null, null, null, null, "L2", -1, true);
        assertEquals("l2", rag.getRagChromaSpace(), "대소문자는 정규화되어야 한다");
    }

    @Test
    @DisplayName("auth 오타는 none 으로 되돌린다")
    void unknownAuthIsRejected() {
        rag.setRagChromaConfig(null, null, null, null, null, "bearer", null, null, -1, true);
        assertEquals("none", rag.getRagChromaAuthType());
    }

    @Test
    @DisplayName("searchMode 화이트리스트 — 오타가 조용히 BM25 로 떨어지지 않게 한다")
    void searchModeIsWhitelisted() {
        for (String m : RagConfigService.AVAILABLE_MODES) {
            rag.setRagConfig("http://es", "none", "", null, null, "i", true,
                    m, "content", 5, 0, 10);
            assertEquals(m, rag.getRagSearchMode(), m + " 이 그대로 수용돼야 한다");
        }
        rag.setRagConfig("http://es", "none", "", null, null, "i", true,
                "chrome", "content", 5, 0, 10);   // 오타
        assertEquals("keyword", rag.getRagSearchMode(), "미지값은 keyword 로 되돌려야 한다");
    }

    @Test
    @DisplayName("AVAILABLE_MODES 에 chroma 가 포함된다 (컨트롤러 목록의 단일 출처)")
    void availableModesContainsChroma() {
        assertTrue(RagConfigService.AVAILABLE_MODES.contains("chroma"));
        assertEquals(4, RagConfigService.AVAILABLE_MODES.size());
    }

    @Test
    @DisplayName("손상된 settings.json 의 searchMode 도 복원 시 정규화된다")
    void applyFromSettingsNormalizesMode() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("ragSearchMode", "존재하지않는모드");
        rag.applyFromSettings(saved);
        assertEquals("keyword", rag.getRagSearchMode());
    }
}

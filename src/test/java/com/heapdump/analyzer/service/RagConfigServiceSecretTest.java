package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.util.AesEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 시크릿의 로드 → 사용 → 저장 왕복 테스트.
 *
 * 방어 대상 회귀 2건:
 * <ol>
 *   <li><b>세탁 루프</b> — 손상된 평문이 재암호화되어 되저장되면 원본 암호문이 영구히 사라진다.
 *       RAG 와 무관한 설정 변경 하나로도 이 경로가 돌았다.</li>
 *   <li><b>기동 churn</b> — 값이 안 바뀌어도 랜덤 IV 때문에 매번 다른 암호문이 기록됐다.</li>
 * </ol>
 *
 * Spring 컨텍스트 없이 {@code applyFromSettings} 를 직접 호출한다
 * ({@code @PostConstruct init()} 은 부르지 않는다).
 */
class RagConfigServiceSecretTest {

    private RagConfigService rag;

    @BeforeEach
    void setUp() {
        rag = new RagConfigService(new HeapDumpConfig());
    }

    /** 실측 손상 사례와 동일한 형태 — 앞에 U+FFFD 쓰레기가 붙은 평문. */
    private static String corruptedPlain() {
        return "�}'�f�c�]X��test-pw-123";
    }

    private static Map<String, Object> settingsWith(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static String collectedPassword(RagConfigService rag) {
        Map<String, Object> out = new LinkedHashMap<>();
        rag.collectSettings(out);
        return (String) out.get("ragPassword");
    }

    // ── 손상 감지 ────────────────────────────────────────

    @Test
    @DisplayName("손상값 로드 — 손상으로 판정되고 런타임 사용은 차단된다")
    void corruptedSecretIsDetectedAndBlocked() {
        String stored = "ENC(" + AesEncryptor.encrypt(corruptedPlain()) + ")";
        rag.applyFromSettings(settingsWith("ragPassword", stored));

        assertFalse(rag.isRagPasswordHealthy());
        assertFalse(rag.getRagPasswordIssue().isEmpty());
        assertEquals("", rag.getRagPassword(), "손상값은 Basic 인증 헤더로 절대 나가면 안 된다");
    }

    @Test
    @DisplayName("손상값 마스킹은 '손상됨' — 정상처럼 보이면 사용자가 문제를 인지할 수 없다")
    void corruptedSecretIsNotMaskedAsHealthy() {
        String stored = "ENC(" + AesEncryptor.encrypt(corruptedPlain()) + ")";
        rag.applyFromSettings(settingsWith("ragPassword", stored));

        String masked = rag.getRagPasswordMasked();
        assertEquals("손상됨", masked);
        assertFalse(masked.contains("23"), "손상값의 꼬리를 정상 마스킹처럼 노출하면 안 된다");
    }

    // ── 세탁 루프 차단 ───────────────────────────────────

    @Test
    @DisplayName("세탁 루프 차단 — 손상값을 로드해도 저장 시 원본 암호문이 그대로 유지된다")
    void corruptedSecretIsNeverReEncrypted() {
        String stored = "ENC(" + AesEncryptor.encrypt(corruptedPlain()) + ")";
        rag.applyFromSettings(settingsWith("ragPassword", stored));

        assertEquals(stored, collectedPassword(rag),
            "재암호화하면 원본 암호문이 사라져 나중에 다른 키로도 복구할 수 없다");
    }

    // ── churn 제거 ───────────────────────────────────────

    @Test
    @DisplayName("churn 제거 — 값을 안 바꾸면 저장 결과가 매번 동일하다")
    void unchangedSecretProducesIdenticalCiphertext() {
        String stored = "ENC(" + AesEncryptor.encrypt("real-password") + ")";
        rag.applyFromSettings(settingsWith("ragPassword", stored));

        String first = collectedPassword(rag);
        String second = collectedPassword(rag);

        assertEquals(stored, first, "로드 당시 암호문을 그대로 돌려줘야 한다");
        assertEquals(first, second);
    }

    @Test
    @DisplayName("랜덤 IV 라 새 암호화는 매번 달라진다 — churn 이 실재했음을 고정")
    void freshEncryptionDiffersEveryTime() {
        assertNotEquals(AesEncryptor.encrypt("real-password"), AesEncryptor.encrypt("real-password"));
    }

    // ── 모호 형식 1회 마이그레이션 ────────────────────────

    @Test
    @DisplayName("마커 없는 64 HEX 정상값 — 로드 시 복구되고 저장 시 v2 로 1회 마이그레이션")
    void ambiguousFormatIsMigratedToV2Once() {
        String unmarked = AesEncryptor.encrypt("short-pw").substring(2);   // v2 마커 제거
        assertEquals(64, unmarked.length(), "모호 구간(64 HEX)이어야 의미 있는 테스트");

        rag.applyFromSettings(settingsWith("ragPassword", "ENC(" + unmarked + ")"));
        assertTrue(rag.isRagPasswordHealthy());
        assertEquals("short-pw", rag.getRagPassword());

        String saved = collectedPassword(rag);
        assertTrue(saved.startsWith("ENC(v2"), "함정 구간을 영구 제거하려면 v2 로 재봉인해야 한다: " + saved);
        assertEquals("short-pw", AesEncryptor.decryptIfEncrypted(saved));
    }

    // ── 신규 저장 / 삭제 ─────────────────────────────────

    @Test
    @DisplayName("사용자가 새 값을 입력하면 v2 로 재암호화된다")
    void userSuppliedSecretIsEncryptedFresh() {
        rag.applyFromSettings(settingsWith("ragPassword", "ENC(" + AesEncryptor.encrypt("old-pw") + ")"));
        rag.setRagConfig("http://es:9200", "basic", "elastic", "new-pw", null,
                "idx", true, "keyword", "content", 5, 0.5, 10);

        String saved = collectedPassword(rag);
        assertTrue(saved.startsWith("ENC(v2"));
        assertEquals("new-pw", AesEncryptor.decryptIfEncrypted(saved));
        assertEquals("new-pw", rag.getRagPassword());
    }

    @Test
    @DisplayName("빈 문자열을 넘기면 삭제된다 (UI 의 '지우기' 경로)")
    void emptyStringClearsSecret() {
        rag.applyFromSettings(settingsWith("ragPassword", "ENC(" + AesEncryptor.encrypt("old-pw") + ")"));
        rag.setRagConfig("http://es:9200", "basic", "elastic", "", null,
                "idx", true, "keyword", "content", 5, 0.5, 10);

        assertEquals("", collectedPassword(rag));
        assertFalse(rag.isRagPasswordSet());
        assertTrue(rag.isRagPasswordHealthy(), "미설정은 손상이 아니다");
    }

    @Test
    @DisplayName("null 을 넘기면 기존 값이 유지된다")
    void nullKeepsExistingSecret() {
        String stored = "ENC(" + AesEncryptor.encrypt("keep-me") + ")";
        rag.applyFromSettings(settingsWith("ragPassword", stored));
        rag.setRagConfig("http://es:9200", "basic", "elastic", null, null,
                "idx", true, "keyword", "content", 5, 0.5, 10);

        assertEquals("keep-me", rag.getRagPassword());
        assertEquals(stored, collectedPassword(rag));
    }

    @Test
    @DisplayName("application.properties 동기화 맵도 같은 값을 쓴다")
    void applicationPropertiesUseSameCiphertext() {
        String stored = "ENC(" + AesEncryptor.encrypt("real-password") + ")";
        rag.applyFromSettings(settingsWith("ragPassword", stored));

        Map<String, String> props = new LinkedHashMap<>();
        rag.collectApplicationProperties(props);
        assertEquals(stored, props.get("rag.elasticsearch.password"));
    }

    @Test
    @DisplayName("서로 다른 시크릿 3종이 독립적으로 관리된다")
    void secretsAreIndependent() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("ragPassword", "ENC(" + AesEncryptor.encrypt(corruptedPlain()) + ")");
        saved.put("ragApiKey", "ENC(" + AesEncryptor.encrypt("clean-api-key-value") + ")");
        rag.applyFromSettings(saved);

        assertFalse(rag.isRagPasswordHealthy());
        assertTrue(rag.isRagApiKeyHealthy());
        assertEquals("clean-api-key-value", rag.getRagApiKey());
        assertSame(true, rag.isRagEmbeddingApiKeyHealthy());
    }
}

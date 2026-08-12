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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM API 키의 암호화 저장 왕복 테스트 (2026-08-12).
 *
 * <p>키는 그동안 settings.json / application.properties 에 <b>평문</b>으로 있었다.
 * RAG 시크릿 3종과 동일한 {@code SecretValue} 규약으로 옮기면서 방어해야 할 것:
 * <ol>
 *   <li>저장본은 항상 {@code ENC(...)}, 런타임 소비값은 평문일 것</li>
 *   <li>값이 안 바뀌면 <b>재암호화하지 않을 것</b> (랜덤 IV churn 방지)</li>
 *   <li>손상된 키는 헤더로 나가지 않고 마스킹도 "손상됨" 으로 표기될 것</li>
 *   <li>레거시 평문을 감지해 1회 재봉인 대상으로 표시할 것</li>
 * </ol>
 *
 * <p>Spring 컨텍스트 없이 {@code applyFromSettings} 를 직접 호출한다
 * ({@code @PostConstruct init()} 은 부르지 않는다).
 */
class LlmApiKeySecretTest {

    private LlmConfigService llm;

    @BeforeEach
    void setUp() {
        llm = new LlmConfigService(new HeapDumpConfig(), new LlmRateLimitService(new HeapDumpConfig()));
    }

    private static Map<String, Object> settingsWith(String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("llmApiKey", value);
        return m;
    }

    private static String collectedKey(LlmConfigService llm) {
        Map<String, Object> out = new LinkedHashMap<>();
        llm.collectSettings(out);
        return (String) out.get("llmApiKey");
    }

    /** 실측 손상 사례와 동일한 형태 — 앞에 U+FFFD 쓰레기가 붙은 평문. */
    private static String corruptedPlain() {
        return "�}'�f�c�]X��sk-ant-test";
    }

    // ── 왕복 ─────────────────────────────────────────────

    @Test
    @DisplayName("새 키 저장 — settings.json 에는 ENC(...), 런타임에는 평문")
    void newKeyIsSealedOnStorage() {
        llm.setLlmApiKey("sk-ant-api03-secret-value");

        String stored = collectedKey(llm);
        assertTrue(stored.startsWith("ENC(") && stored.endsWith(")"),
                "저장본이 평문이면 안 된다: " + stored);
        assertFalse(stored.contains("sk-ant-api03-secret-value"), "평문이 저장본에 노출됐다");
        assertEquals("sk-ant-api03-secret-value", llm.getLlmApiKey(), "런타임 소비값은 평문이어야 한다");
        assertTrue(llm.isLlmApiKeySet());
        assertTrue(llm.isLlmApiKeyHealthy());
    }

    @Test
    @DisplayName("ENC 저장값 로드 → 복호화된 평문을 API 호출에 쓸 수 있다")
    void encryptedValueIsLoaded() {
        String stored = "ENC(" + AesEncryptor.encrypt("sk-loaded-key") + ")";
        llm.applyFromSettings(settingsWith(stored));

        assertEquals("sk-loaded-key", llm.getLlmApiKey());
        assertTrue(llm.isLlmApiKeySet());
        assertFalse(llm.isLlmApiKeyUnsealed(), "ENC 로 저장돼 있으면 재봉인 대상이 아니다");
    }

    @Test
    @DisplayName("application.properties 에도 ENC 로 나간다")
    void applicationPropertiesGetsSealedValue() {
        llm.setLlmApiKey("sk-props-key");
        Map<String, String> updates = new LinkedHashMap<>();
        llm.collectApplicationProperties(updates);

        String v = updates.get("llm.api.key");
        assertTrue(v.startsWith("ENC("), "properties 저장본이 평문이다: " + v);
        assertFalse(v.contains("sk-props-key"));
    }

    // ── churn 방지 ───────────────────────────────────────

    @Test
    @DisplayName("값이 안 바뀌면 재암호화하지 않는다 (기동 churn 0)")
    void unchangedKeyIsNotReEncrypted() {
        String stored = "ENC(" + AesEncryptor.encrypt("sk-stable-key") + ")";
        llm.applyFromSettings(settingsWith(stored));

        assertEquals(stored, collectedKey(llm), "로드 당시 암호문을 그대로 되돌려줘야 한다");
        assertEquals(stored, collectedKey(llm), "여러 번 저장해도 동일해야 한다");
    }

    @Test
    @DisplayName("값을 바꾸면 새 암호문이 생성된다")
    void changedKeyIsReEncrypted() {
        String stored = "ENC(" + AesEncryptor.encrypt("sk-old") + ")";
        llm.applyFromSettings(settingsWith(stored));
        llm.setLlmApiKey("sk-new");

        String after = collectedKey(llm);
        assertNotEquals(stored, after);
        assertEquals("sk-new", llm.getLlmApiKey());
    }

    // ── 레거시 평문 ──────────────────────────────────────

    @Test
    @DisplayName("레거시 평문 키는 재봉인 대상으로 표시되고, 저장 시 ENC 로 승격된다")
    void legacyPlaintextIsFlaggedAndSealed() {
        llm.applyFromSettings(settingsWith("sk-plain-legacy-key"));

        assertTrue(llm.isLlmApiKeyUnsealed(), "평문 저장본은 재봉인 대상으로 잡혀야 한다");
        assertEquals("sk-plain-legacy-key", llm.getLlmApiKey(), "평문도 그대로 사용은 가능해야 한다");

        String stored = collectedKey(llm);
        assertTrue(stored.startsWith("ENC("), "저장 시 봉인돼야 한다: " + stored);
        assertFalse(llm.isLlmApiKeyUnsealed(), "봉인 후에는 경고 플래그가 내려가야 한다");
    }

    @Test
    @DisplayName("미설정 상태는 재봉인 대상이 아니다")
    void emptyKeyIsNotFlagged() {
        llm.applyFromSettings(settingsWith(""));

        assertFalse(llm.isLlmApiKeySet());
        assertFalse(llm.isLlmApiKeyUnsealed());
        assertEquals("", collectedKey(llm), "명시적으로 비운 값은 빈 문자열로 저장된다");
    }

    // ── 손상 처리 ────────────────────────────────────────

    @Test
    @DisplayName("손상된 키는 런타임 사용이 차단되고 마스킹도 손상 표기가 된다")
    void corruptedKeyIsBlocked() {
        String stored = "ENC(" + AesEncryptor.encrypt(corruptedPlain()) + ")";
        llm.applyFromSettings(settingsWith(stored));

        assertFalse(llm.isLlmApiKeyHealthy());
        assertFalse(llm.getLlmApiKeyIssue().isEmpty());
        assertEquals("", llm.getLlmApiKey(), "손상값은 Authorization 헤더로 절대 나가면 안 된다");
        assertEquals("손상됨", llm.getLlmApiKeyMasked(),
                "정상처럼 마스킹되면 사용자가 문제를 인지할 수 없다");
    }

    @Test
    @DisplayName("손상된 키는 재암호화되지 않는다 (세탁 루프 차단)")
    void corruptedKeyIsNotLaundered() {
        String stored = "ENC(" + AesEncryptor.encrypt(corruptedPlain()) + ")";
        llm.applyFromSettings(settingsWith(stored));

        assertEquals(stored, collectedKey(llm),
                "손상 평문을 재봉인하면 원본 암호문이 영구히 사라진다");
    }

    // ── 마스킹 ───────────────────────────────────────────

    @Test
    @DisplayName("정상 키 마스킹은 앞 7자 + 뒤 4자만 노출한다")
    void maskingShowsOnlyEdges() {
        llm.setLlmApiKey("sk-ant-api03-abcdefghijklmnop-WXYZ");
        String masked = llm.getLlmApiKeyMasked();

        assertTrue(masked.startsWith("sk-ant-"));
        assertTrue(masked.endsWith("WXYZ"));
        assertTrue(masked.contains("..."));
        assertFalse(masked.contains("abcdefghijklmnop"), "본문이 노출되면 안 된다");
    }

    @Test
    @DisplayName("짧은 키는 전량 마스킹된다")
    void shortKeyFullyMasked() {
        llm.setLlmApiKey("abc");
        assertEquals("****", llm.getLlmApiKeyMasked());
    }
}

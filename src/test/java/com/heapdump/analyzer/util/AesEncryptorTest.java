package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AesEncryptor 왕복/형식 호환 테스트.
 *
 * 회귀 대상: 랜덤 IV 형식은 평문 15바이트 이하일 때 정확히 64 HEX 문자가 되어
 * 레거시(고정 IV) 형식과 길이가 겹쳤고, 길이 기반 판별이 이를 레거시로 오판했다.
 * CBC 특성상 두 번째 블록은 정상 복원 + 패딩도 유효해 **예외 없이** 앞에 쓰레기
 * 16바이트가 붙은 문자열이 반환됐다. "v2" 마커 도입으로 형식이 명확해졌다.
 */
class AesEncryptorTest {

    // AesEncryptor 와 동일한 기본 키 시드 (테스트 환경엔 HEAP_ANALYZER_ENCRYPTION_KEY 미설정)
    private static final String DEFAULT_KEY_SEED = "HeapDumpAnalyzer2026!@#SecretKey";

    @ParameterizedTest(name = "평문 {0}자 왕복")
    @ValueSource(ints = {1, 8, 10, 15, 16, 17, 31, 32, 33, 64, 200})
    @DisplayName("모든 평문 길이에서 encrypt → decrypt 왕복이 원문과 정확히 일치")
    void roundTripAtEveryLength(int length) {
        String plain = "a".repeat(length);
        assertEquals(plain, AesEncryptor.decrypt(AesEncryptor.encrypt(plain)));
    }

    @Test
    @DisplayName("15자 이하 평문 왕복 — 64 HEX 경계 회귀 (쓰레기 프리픽스가 붙지 않아야 함)")
    void roundTripShortPlaintextNoGarbagePrefix() {
        String plain = "pw@short-15chr";   // 14자: 암호문 1블록 → 마커 없으면 64 HEX
        String encrypted = AesEncryptor.encrypt(plain);
        String decrypted = AesEncryptor.decrypt(encrypted);

        assertEquals(plain, decrypted, "복호화 결과에 IV 블록 쓰레기가 섞이면 안 됨");
        assertEquals(plain.length(), decrypted.length(), "길이가 늘면 앞에 쓰레기 블록이 붙은 것");
    }

    @Test
    @DisplayName("암호문에 v2 마커가 붙고, 마커 제거 시 정확히 64 HEX (겹침 구간임을 고정)")
    void encryptEmitsVersionMarker() {
        String encrypted = AesEncryptor.encrypt("shortpw");
        assertTrue(encrypted.startsWith("v2"), "신규 암호문에는 v2 마커가 있어야 함: " + encrypted);
        assertEquals(64, encrypted.substring(2).length(),
            "짧은 평문은 IV(32) + 1블록(32) = 64 HEX — 레거시와 길이가 겹치는 구간");
    }

    @Test
    @DisplayName("같은 평문도 매번 다른 암호문 (랜덤 IV)")
    void randomIvProducesDifferentCiphertexts() {
        String plain = "shinhan-sample";
        assertNotEquals(AesEncryptor.encrypt(plain), AesEncryptor.encrypt(plain));
    }

    @Test
    @DisplayName("레거시 고정 IV 암호문(32 HEX) 하위 호환")
    void decryptsLegacyFixedIvShort() throws Exception {
        String plain = "legacy-pw";                       // 9자 → 1블록 → 32 HEX
        String legacyHex = encryptLegacyFixedIv(plain);
        assertEquals(32, legacyHex.length());
        assertEquals(plain, AesEncryptor.decrypt(legacyHex));
    }

    @Test
    @DisplayName("레거시 고정 IV 암호문(64 HEX, 평문 16~31자) 하위 호환 — 마커 없는 값의 판별 규칙 유지")
    void decryptsLegacyFixedIvTwoBlocks() throws Exception {
        String plain = "legacy-20-characters";            // 20자 → 2블록 → 64 HEX
        String legacyHex = encryptLegacyFixedIv(plain);
        assertEquals(64, legacyHex.length());
        assertEquals(plain, AesEncryptor.decrypt(legacyHex));
    }

    @Test
    @DisplayName("마커 이전 랜덤 IV 암호문(64 HEX 초과) 하위 호환")
    void decryptsUnmarkedRandomIv() {
        String plain = "unmarked-random-iv-value-32chars";  // 32자 → 마커 제거 시 128 HEX
        String unmarked = stripV2(AesEncryptor.encrypt(plain));
        assertTrue(unmarked.length() > 64);
        assertEquals(plain, AesEncryptor.decrypt(unmarked));
    }

    @Test
    @DisplayName("decryptIfEncrypted — ENC(...) 만 복호화, 평문/null 은 그대로 통과")
    void decryptIfEncryptedHandlesWrapperOnly() {
        String plain = "wrapped-pw";
        String wrapped = "ENC(" + AesEncryptor.encrypt(plain) + ")";

        assertEquals(plain, AesEncryptor.decryptIfEncrypted(wrapped));
        assertEquals("plain-value", AesEncryptor.decryptIfEncrypted("plain-value"));
        assertNull(AesEncryptor.decryptIfEncrypted(null));
    }

    @Test
    @DisplayName("UTF-8 멀티바이트 평문 왕복")
    void roundTripMultibyte() {
        String plain = "비밀번호!@#한글";
        assertEquals(plain, AesEncryptor.decrypt(AesEncryptor.encrypt(plain)));
    }

    // ── 마커 없는 64 HEX 자동 복구 (2026-07-31 회귀) ─────────

    @Test
    @DisplayName("v2 이전 랜덤 IV + 짧은 평문 — 쓰레기 프리픽스 없이 원문 그대로 복구")
    void unmarkedRandomIvShortPlaintextIsRecovered() throws Exception {
        String plain = "test-pw-123";                    // 실제 손상 사례와 동일한 11바이트
        String unmarked = encryptPreV2RandomIv(plain);

        assertEquals(64, unmarked.length(), "IV(32) + 1블록(32) = 64 HEX — 레거시와 겹치는 구간");
        assertEquals(plain, AesEncryptor.decrypt(unmarked),
            "레거시 고정 IV 로 오복호화하면 예외 없이 '쓰레기 16바이트 + 원문' 이 나온다");
    }

    @RepeatedTest(200)
    @DisplayName("위 복구가 랜덤 IV 값과 무관하게 성립 (미검출 확률 약 1e-7)")
    void unmarkedRandomIvRecoveryIsStable() throws Exception {
        String plain = "pw-9chars";
        assertEquals(plain, AesEncryptor.decrypt(encryptPreV2RandomIv(plain)));
    }

    @ParameterizedTest(name = "v2 이전 랜덤 IV, 평문 {0}자")
    @ValueSource(ints = {1, 5, 10, 15, 16, 17, 31, 32})
    @DisplayName("64 HEX 경계 전후 평문 길이 전수 복구")
    void unmarkedRandomIvRecoveredAtEveryBoundaryLength(int length) throws Exception {
        String plain = "x".repeat(length);
        assertEquals(plain, AesEncryptor.decrypt(encryptPreV2RandomIv(plain)));
    }

    @Test
    @DisplayName("진짜 레거시 64 HEX 는 여전히 레거시로 채택 — 동점 규칙 카나리아")
    void legacy64HexStillWinsWhenBothDecode() throws Exception {
        String plain = "legacy-20-characters";           // 20자 → 2블록 → 64 HEX
        String legacyHex = encryptLegacyFixedIv(plain);
        assertEquals(64, legacyHex.length());
        assertEquals(plain, AesEncryptor.decrypt(legacyHex));
    }

    // ── decryptIfEncryptedChecked ────────────────────────

    @Test
    @DisplayName("손상된 평문을 담은 암호문 — 예외 없이 healthy=false + 사유 보고")
    void checkedReportsCorruptionWithoutThrowing() {
        String corrupted = "\uFFFD}'\uFFFDf\u001Atest-pw-123";   // 실측 손상값과 같은 형태
        String stored = "ENC(" + AesEncryptor.encrypt(corrupted) + ")";

        AesEncryptor.Decrypted d = assertDoesNotThrow(() -> AesEncryptor.decryptIfEncryptedChecked(stored));
        assertFalse(d.healthy());
        assertNotNull(d.issue());
        assertFalse(d.isFailed(), "복호화 자체는 성공한다 — 평문이 손상된 것");
    }

    @ParameterizedTest(name = "쓰레기 입력 {0}")
    @ValueSource(strings = {"ENC(zzzz)", "ENC(ab)", "ENC(v2)", "ENC(v2zz)"})
    @DisplayName("복호화 불가 입력에도 예외를 던지지 않고 FAILED 로 보고")
    void checkedNeverThrowsOnGarbage(String stored) {
        AesEncryptor.Decrypted d = assertDoesNotThrow(() -> AesEncryptor.decryptIfEncryptedChecked(stored));
        assertTrue(d.isFailed(), "FAILED 여야 함: " + stored);
        assertFalse(d.healthy());
    }

    @Test
    @DisplayName("ENC() 빈 본문은 미설정과 동일하게 빈 값 + 정상으로 해석")
    void checkedTreatsEmptyBodyAsUnset() {
        AesEncryptor.Decrypted d = assertDoesNotThrow(() -> AesEncryptor.decryptIfEncryptedChecked("ENC()"));
        assertEquals("", d.value());
        assertTrue(d.healthy());
        assertFalse(d.isFailed());
    }

    @Test
    @DisplayName("비-ENC 값과 null 은 PLAIN 으로 통과")
    void checkedPassesThroughPlainValues() {
        AesEncryptor.Decrypted plain = AesEncryptor.decryptIfEncryptedChecked("plain-value");
        assertEquals("plain-value", plain.value());
        assertTrue(plain.healthy());
        assertEquals(AesEncryptor.Decrypted.Format.PLAIN, plain.format());

        AesEncryptor.Decrypted none = AesEncryptor.decryptIfEncryptedChecked(null);
        assertNull(none.value());
        assertTrue(none.healthy());
    }

    @Test
    @DisplayName("자동 복구 시 recovered 플래그가 켜진다")
    void checkedFlagsRecovery() throws Exception {
        String stored = "ENC(" + encryptPreV2RandomIv("short-pw") + ")";
        AesEncryptor.Decrypted d = AesEncryptor.decryptIfEncryptedChecked(stored);
        assertEquals("short-pw", d.value());
        assertTrue(d.recovered(), "모호 구간에서 랜덤 IV 해석으로 복구됐음을 표시해야 함");
        assertEquals(AesEncryptor.Decrypted.Format.RANDOM_IV, d.format());
    }

    // ── 헬퍼 ────────────────────────────────────────────

    /** AesEncryptor 의 레거시 경로와 동일하게 고정 IV 로 암호화 (하위 호환 검증용). */
    private static String encryptLegacyFixedIv(String plain) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        SecretKeySpec key = new SecretKeySpec(
            sha.digest(DEFAULT_KEY_SEED.getBytes(StandardCharsets.UTF_8)), "AES");
        byte[] ivBytes = MessageDigest.getInstance("SHA-256")
            .digest((DEFAULT_KEY_SEED + "IV").getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(Arrays.copyOf(ivBytes, 16)));
        return toHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    /** v2 마커 도입 이전 방식(랜덤 IV, 마커 없는 HEX)으로 암호화 — 손상 시나리오 재현용. */
    private static String encryptPreV2RandomIv(String plain) throws Exception {
        return stripV2(AesEncryptor.encrypt(plain));
    }

    private static String stripV2(String encrypted) {
        return encrypted.startsWith("v2") ? encrypted.substring(2) : encrypted;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}

package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 위생 검사 규칙 테스트.
 *
 * 핵심 요구: 오복호화된 쓰레기는 반드시 잡되(검출력), 사람이 쓰는 정상 시크릿은
 * 하나도 손상으로 오판하지 않아야 한다(오탐 0).
 */
class SecretSanityTest {

    // ── 거부돼야 하는 값 ────────────────────────────────

    @Test
    @DisplayName("U+FFFD 포함 — 비가역 UTF-8 손실의 확정 증거")
    void rejectsReplacementChar() {
        assertFalse(SecretSanity.isClean("a�b"));
        assertNotNull(SecretSanity.describe("a�b"));
    }

    @ParameterizedTest(name = "제어문자 0x{0}")
    @ValueSource(ints = {0x00, 0x01, 0x1A, 0x1F, 0x7F, 0x80, 0x90, 0x9F})
    @DisplayName("C0/DEL/C1 제어문자 거부 (소스에 보이지 않는 문자를 넣지 않도록 코드포인트로 표기)")
    void rejectsControlChars(int codePoint) {
        String secret = "pw" + (char) codePoint + "x";
        assertFalse(SecretSanity.isClean(secret), "코드포인트 0x" + Integer.toHexString(codePoint));
        assertNotNull(SecretSanity.describe(secret));
    }

    @Test
    @DisplayName("실측 손상 바이트열 — 오복호화 결과를 그대로 재현한 값")
    void rejectsRealWorldCorruption() {
        // /opt/heapdumps/data/settings.json 의 ragPassword 를 복호화했을 때 나온 바이트열
        byte[] observed = {
            (byte) 0xEF, (byte) 0xBF, (byte) 0xBD, '}', '\'',
            (byte) 0xEF, (byte) 0xBF, (byte) 0xBD, 'f', 0x1A,
            (byte) 0xEF, (byte) 0xBF, (byte) 0xBD, 'c',
            (byte) 0xEF, (byte) 0xBF, (byte) 0xBD, ']', 'X',
            't', 'e', 's', 't', '-', 'p', 'w', '-', '1', '2', '3'
        };
        String corrupted = new String(observed, StandardCharsets.UTF_8);

        assertFalse(SecretSanity.isClean(corrupted));
        String issue = SecretSanity.describe(corrupted);
        assertNotNull(issue);
        assertFalse(issue.contains("test-pw-123"), "사유 문자열에 시크릿 원문이 새면 안 된다");
    }

    @Test
    @DisplayName("손상 사유는 원문을 노출하지 않고 개수·위치만 담는다")
    void describeDoesNotLeakSecret() {
        String issue = SecretSanity.describe("��super-secret-value");
        assertNotNull(issue);
        assertFalse(issue.contains("super-secret-value"));
        assertTrue(issue.contains("2"), "U+FFFD 개수를 담아야 함: " + issue);
    }

    // ── 통과해야 하는 값 (오탐 0) ───────────────────────

    @ParameterizedTest(name = "정상 시크릿 [{0}]")
    @ValueSource(strings = {
        "test-pw-123",
        "P@ssw0rd!#$%^&*()_+-=",
        "한글비밀번호",
        "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",   // Base32 OTP seed 32자
        "sk-ant-api03-abcdefghijklmnop",
        "YWJjZGVmZ2hpams+Lz09",               // Base64 (+ / =)
        "pass with spaces",
        "  leading-and-trailing  ",
        "1",
        "비밀번호🔐emoji"
    })
    void acceptsRealSecrets(String secret) {
        assertTrue(SecretSanity.isClean(secret), "정상 시크릿을 손상으로 오판: " + secret);
        assertNull(SecretSanity.describe(secret));
    }

    @Test
    @DisplayName("미설정(null/빈 문자열)은 손상이 아니다")
    void treatsUnsetAsClean() {
        assertTrue(SecretSanity.isClean(null));
        assertTrue(SecretSanity.isClean(""));
        assertNull(SecretSanity.describe(null));
        assertNull(SecretSanity.describe(""));
    }
}

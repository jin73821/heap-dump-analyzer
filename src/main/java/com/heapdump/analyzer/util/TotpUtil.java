package com.heapdump.analyzer.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP (Time-based One-Time Password) 유틸리티.
 * Google Authenticator / MS Authenticator 호환 규격:
 *   - HMAC-SHA1, 6자리, 30초 주기
 *   - Seed 는 RFC 4648 Base32 인코딩 (패딩 없음)
 *
 * 외부 라이브러리 없이 JDK crypto 만 사용. 검증은 상수 시간 비교(MessageDigest.isEqual).
 */
public final class TotpUtil {

    /** TOTP 시간 스텝 (초) */
    public static final long PERIOD_SECONDS = 30L;
    /** OTP 자릿수 */
    public static final int DIGITS = 6;
    /** Seed 바이트 수 (160bit — RFC 4226 권장) */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtil() {
    }

    /** SecureRandom 160bit seed 를 Base32(패딩 없음)로 생성 */
    public static String generateSecretBase32() {
        byte[] raw = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(raw);
        return base32Encode(raw);
    }

    /**
     * TOTP 코드 검증.
     *
     * @param secretBase32     Base32 seed
     * @param code             사용자 입력 6자리 코드
     * @param window           허용 드리프트 스텝 (1 = 현재±1 스텝, 총 90초 창)
     * @param minStepExclusive 이 timestep "이하" 코드는 거부 (재사용 방지). 제한 없으면 null
     * @return 매칭된 timestep, 불일치 시 -1
     */
    public static long verify(String secretBase32, String code, int window, Long minStepExclusive) {
        if (secretBase32 == null || secretBase32.isEmpty() || code == null) {
            return -1;
        }
        String trimmed = code.trim();
        if (!trimmed.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key;
        try {
            key = base32Decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        long currentStep = System.currentTimeMillis() / 1000L / PERIOD_SECONDS;
        byte[] input = trimmed.getBytes(StandardCharsets.UTF_8);
        long matched = -1;
        // 전 후보를 항상 순회 (타이밍 균일화). 재사용 방지 조건은 후보 채택 시에만 적용.
        for (long step = currentStep - window; step <= currentStep + window; step++) {
            byte[] expected = generateCode(key, step).getBytes(StandardCharsets.UTF_8);
            boolean match = MessageDigest.isEqual(expected, input);
            boolean reusable = minStepExclusive == null || step > minStepExclusive;
            if (match && reusable && matched < 0) {
                matched = step;
            }
        }
        return matched;
    }

    /** 특정 timestep 의 6자리 코드 생성 (RFC 4226 dynamic truncation) */
    static String generateCode(byte[] key, long step) {
        try {
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (step & 0xFF);
                step >>>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 코드 생성 실패", e);
        }
    }

    /** otpauth:// URI 생성 (QR 인코딩 대상) */
    public static String buildOtpAuthUri(String issuer, String account, String secretBase32) {
        String encIssuer = urlEncode(issuer);
        String encAccount = urlEncode(account);
        return "otpauth://totp/" + encIssuer + ":" + encAccount
                + "?secret=" + secretBase32
                + "&issuer=" + encIssuer
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** 수동 입력용 4자 그룹핑 (예: ABCD EFGH ...) */
    public static String groupForDisplay(String secretBase32) {
        if (secretBase32 == null) return "";
        return secretBase32.replaceAll("(.{4})(?=.)", "$1 ");
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ── RFC 4648 Base32 (패딩 없음) ──

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32_ALPHABET.charAt((buffer >>> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String encoded) {
        String normalized = encoded.trim().replace(" ", "").replace("=", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[normalized.length() * 5 / 8];
        int idx = 0;
        for (char c : normalized.toCharArray()) {
            int v = BASE32_ALPHABET.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Base32 문자가 아님: " + c);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[idx++] = (byte) ((buffer >>> bits) & 0xFF);
            }
        }
        return out;
    }
}

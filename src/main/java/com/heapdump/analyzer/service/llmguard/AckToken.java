package com.heapdump.analyzer.service.llmguard;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * '확인 후 전송' 토큰 — {@code issuedAt.HMAC-SHA256(부팅 키, user|sha256(점검 텍스트)|issuedAt)} (LLM Guard, 2026-11-26).
 *
 * <p>서버에 상태를 남기지 않는다. 유효시간 {@value #TTL_MILLIS}ms. 토큰은 <b>문구에 묶여 있어</b> 문구를 바꾸거나
 * 재기동(키 재생성)하면 다시 확인을 받는다. 다른 사용자의 토큰은 쓸 수 없다.
 */
public final class AckToken {

    public static final long TTL_MILLIS = 10 * 60 * 1000L;
    private static final long CLOCK_SKEW_MILLIS = 60 * 1000L;

    private final byte[] key;

    public AckToken() {
        this.key = new byte[32];
        new SecureRandom().nextBytes(this.key);
    }

    /** 테스트용 — 고정 키. */
    AckToken(byte[] key) {
        this.key = key.clone();
    }

    public String issue(String user, String text) {
        return issue(user, text, System.currentTimeMillis());
    }

    String issue(String user, String text, long issuedAt) {
        return issuedAt + "." + mac(user, sha256(text), issuedAt);
    }

    public boolean verify(String token, String user, String text) {
        return verify(token, user, text, System.currentTimeMillis());
    }

    boolean verify(String token, String user, String text, long now) {
        if (token == null || token.isEmpty() || token.length() > 200) return false;
        int dot = token.indexOf('.');
        if (dot <= 0) return false;
        long issuedAt;
        try {
            issuedAt = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (now - issuedAt > TTL_MILLIS || issuedAt - now > CLOCK_SKEW_MILLIS) return false;
        String expected = mac(user, sha256(text), issuedAt);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII));
    }

    private String mac(String user, String textHash, long issuedAt) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = m.doFinal((String.valueOf(user) + "|" + textHash + "|" + issuedAt)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 사용 불가", e);
        }
    }

    static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 사용 불가", e);
        }
    }
}

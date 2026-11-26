package com.heapdump.analyzer.service.llmguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** '확인 후 전송' 토큰 — 사용자·문구·시간에 묶인다. */
class AckTokenTest {

    private final AckToken ack = new AckToken(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
    private static final long T0 = 1_800_000_000_000L;

    @Test
    @DisplayName("같은 사용자·같은 문구면 유효")
    void sameUserSameText() {
        String t = ack.issue("alice", "010-1234-5678", T0);
        assertTrue(ack.verify(t, "alice", "010-1234-5678", T0 + 60_000));
    }

    @Test
    @DisplayName("문구·사용자가 바뀌면 무효")
    void boundToTextAndUser() {
        String t = ack.issue("alice", "010-1234-5678", T0);
        assertFalse(ack.verify(t, "alice", "010-1234-5679", T0));
        assertFalse(ack.verify(t, "bob", "010-1234-5678", T0));
    }

    @Test
    @DisplayName("10분이 지나면 무효, 미래 시각 위조도 무효")
    void expires() {
        String t = ack.issue("alice", "x", T0);
        assertTrue(ack.verify(t, "alice", "x", T0 + AckToken.TTL_MILLIS));
        assertFalse(ack.verify(t, "alice", "x", T0 + AckToken.TTL_MILLIS + 1));
        String future = ack.issue("alice", "x", T0 + 10 * 60_000L);
        assertFalse(ack.verify(future, "alice", "x", T0));
    }

    @Test
    @DisplayName("다른 키(재기동)·변조·형식 오류는 무효")
    void tamperAndRestart() {
        String t = ack.issue("alice", "x", T0);
        assertFalse(new AckToken(new byte[]{9}).verify(t, "alice", "x", T0), "재기동하면 다시 확인을 받는다");
        String tampered = (T0 + 1) + t.substring(t.indexOf('.'));
        assertFalse(ack.verify(tampered, "alice", "x", T0 + 1));
        assertFalse(ack.verify("garbage", "alice", "x", T0));
        assertFalse(ack.verify(null, "alice", "x", T0));
        assertFalse(ack.verify("", "alice", "x", T0));
    }
}

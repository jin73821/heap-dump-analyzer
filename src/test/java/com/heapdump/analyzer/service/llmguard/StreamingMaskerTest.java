package com.heapdump.analyzer.service.llmguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스트림 마스커 불변식 — 어디서 쪼개 흘려도 원문 조각이 나가지 않고, 이어 붙인 결과가 한 번에 가린 결과와 같다.
 */
class StreamingMaskerTest {

    private static final String FILLER = "힙 덤프 분석 결과 요약입니다. 메모리 사용량이 높습니다. ";

    /** 조각 목록을 흘려보내고 내보낸 조각들을 돌려준다(마지막은 flush). */
    private static List<String> stream(LlmGuardService s, List<String> chunks) {
        StreamingMasker m = s.newStreamMasker();
        List<String> out = new ArrayList<>();
        for (String c : chunks) {
            String o = m.push(c);
            if (!o.isEmpty()) out.add(o);
        }
        String tail = m.flush();
        if (!tail.isEmpty()) out.add(tail);
        assertEquals(String.join("", out), m.maskedText(), "maskedText 는 내보낸 조각의 합이어야 한다");
        return out;
    }

    private static void assertEverySplit(LlmGuardService s, String full, String secret) {
        String expected = s.maskText(full).text();
        assertFalse(expected.contains(secret), "전문 가림 결과에 원문이 남았다");
        for (int i = 0; i <= full.length(); i++) {
            List<String> out = stream(s, List.of(full.substring(0, i), full.substring(i)));
            String joined = String.join("", out);
            assertEquals(expected, joined, "분할 위치 " + i + " 에서 결과가 다르다");
            for (String o : out) assertFalse(o.contains(secret), "분할 위치 " + i + " 에서 원문이 나갔다");
        }
    }

    @Test
    @DisplayName("주민번호·카드·이메일 — 모든 분할 위치에서 원문 미노출 + 전문 가림과 동일")
    void everySplitPosition() {
        LlmGuardService s = GuardTestSupport.defaults();
        String pre = FILLER.repeat(3);
        assertEverySplit(s, pre + "주민번호는 900101-1234567 입니다. " + FILLER, "900101-1234567");
        assertEverySplit(s, pre + "카드 4111-1111-1111-1111 결제 " + FILLER, "4111-1111-1111-1111");
        assertEverySplit(s, pre + "담당 abcde@corp.com 회신 " + FILLER, "abcde@corp.com");
        // 짧은 응답(홀드백보다 짧음)도 flush 로 전부 나온다
        assertEverySplit(s, "메일 abcde@corp.com", "abcde@corp.com");
    }

    @Test
    @DisplayName("한 글자씩 흘려도 결과가 같다")
    void charByChar() {
        LlmGuardService s = GuardTestSupport.defaults();
        String full = FILLER.repeat(4) + "연락처 010-1234-5678, 계좌 110-123-456789, password=hunter2 " + FILLER.repeat(4);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < full.length(); i++) chunks.add(String.valueOf(full.charAt(i)));
        List<String> out = stream(s, chunks);
        assertEquals(s.maskText(full).text(), String.join("", out));
        assertTrue(out.size() > 2, "중간 조각이 나오지 않았다 — 홀드백만큼만 붙잡아야 한다");
    }

    @Test
    @DisplayName("PEM 개인키 — 7글자씩 흘려도 블록이 한 글자도 새지 않는다")
    void pemBlockNeverLeaks() {
        LlmGuardService s = GuardTestSupport.defaults();
        String pem = LlmGuardDetectorsTest.pem();
        String full = FILLER.repeat(3) + "\n" + pem + "\n" + FILLER.repeat(3);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < full.length(); i += 7) chunks.add(full.substring(i, Math.min(full.length(), i + 7)));
        List<String> out = stream(s, chunks);
        String joined = String.join("", out);
        assertEquals(s.maskText(full).text(), joined);
        String body = pem.split("\n")[3];
        for (String o : out) {
            assertFalse(o.contains("PRIVATE KEY-----"), "PEM 표지가 나갔다");
            assertFalse(o.contains(body.substring(0, 12)), "PEM 본문이 나갔다");
        }
        assertTrue(joined.contains(BuiltinDetectors.PEM_MASK));
    }

    @Test
    @DisplayName("PEM 끝 표지 없이 스트림이 끝나면 시작 표지부터 끝까지 가린다")
    void pemWithoutEndMasksToEnd() {
        LlmGuardService s = GuardTestSupport.defaults();
        String pem = LlmGuardDetectorsTest.pem();
        String cutPem = pem.substring(0, pem.indexOf("-----END"));
        String full = FILLER.repeat(3) + cutPem;
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < full.length(); i += 50) chunks.add(full.substring(i, Math.min(full.length(), i + 50)));
        String joined = String.join("", stream(s, chunks));
        assertEquals(FILLER.repeat(3) + BuiltinDetectors.PEM_MASK, joined);
        assertEquals(joined, s.maskText(full).text());
    }

    @Test
    @DisplayName("flush 는 붙잡아 둔 꼬리를 돌려준다 — 호출자가 onChunk 로 보내야 끝부분이 빠지지 않는다")
    void flushReturnsHeldTail() {
        LlmGuardService s = GuardTestSupport.defaults();
        StreamingMasker m = s.newStreamMasker();
        String text = FILLER.repeat(6);
        String first = m.push(text);
        assertEquals(text.length() - StreamingMasker.HOLD, first.length(), "홀드백 128자를 뺀 앞부분만 나온다");
        String tail = m.flush();
        assertEquals(StreamingMasker.HOLD, tail.length());
        assertEquals(text, first + tail);
    }

    @Test
    @DisplayName("건수 — 내보낸 일치만 규칙별로 센다")
    void countsPerRule() {
        LlmGuardService s = GuardTestSupport.defaults();
        StreamingMasker m = s.newStreamMasker();
        m.push(FILLER + "abcde@corp.com 그리고 fghij@corp.com, 010-1234-5678 " + FILLER);
        m.flush();
        assertEquals(3, m.result().total());
        assertEquals("EMAIL×2,MOBILE×1", m.result().rulesSummary());
        assertNotNull(m.result().toOutputGuard());
    }

    @Test
    @DisplayName("출력 가림 OFF 면 그대로 통과")
    void disabledPassesThrough() {
        LlmGuardService s = GuardTestSupport.defaults();
        s.setSettings(true, false, true);
        StreamingMasker m = s.newStreamMasker();
        assertEquals("900101-1234567", m.push("900101-1234567"));
        assertEquals("", m.flush());
        assertEquals(0, m.result().total());
    }
}

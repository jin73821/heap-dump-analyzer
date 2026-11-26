package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 사용자 정의 정규식의 실행 시간 제한(규칙당 200ms).
 *
 * <p>⚠ JDK 9+ 는 {@code (a+)+$} 같은 고전 사례를 메모이제이션으로 즉시 끝낸다(JDK 21 실측 1ms). 역참조가 들어가면
 * 그 최적화가 꺼져 {@code (a+)+\1!} 이 "a"×30 에서 42초가 걸린다(실측) — 그래서 이 패턴으로 시험한다.
 */
class RegexDeadlineTest {

    private static final String EVIL = "(a+)+\\1!";
    private static final String INPUT = "a".repeat(30);

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("입력: 시간 초과 규칙은 확인 후 전송으로 취급(fail-safe) — BLOCK 규칙이어도 우회되지 않게 CONFIRM 이상")
    void inputTimeoutIsConfirm() {
        LlmGuardService s = GuardTestSupport.withRules(null,
                GuardTestSupport.regex("느린 규칙", EVIL, LlmGuardRule.ACTION_BLOCK, true));
        InputVerdict v = s.checkInput("u", List.of(Map.of("role", "user", "content", INPUT)), null, null);
        assertEquals(InputVerdict.Kind.CONFIRM, v.kind());
        assertTrue(v.findings().stream().anyMatch(InputVerdict.Finding::timeout), "시간 초과 표시가 없다");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("출력: 시간 초과 규칙은 건너뛰고 나머지 규칙은 가린다")
    void outputTimeoutIsSkipped() {
        LlmGuardService s = GuardTestSupport.withRules(null,
                GuardTestSupport.regex("느린 규칙", EVIL, LlmGuardRule.ACTION_CONFIRM, true));
        MaskResult r = s.maskText(INPUT + " abcde@corp.com");
        assertEquals(INPUT + " ab***@corp.com", r.text());
        assertEquals(1, r.total());
        assertEquals(1, r.timedOut().size());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("스트림: 시간 초과 규칙은 그 스트림에서 빠지고 응답은 계속 나온다")
    void streamTimeoutIsSkipped() {
        LlmGuardService s = GuardTestSupport.withRules(null,
                GuardTestSupport.regex("느린 규칙", EVIL, LlmGuardRule.ACTION_CONFIRM, true));
        StreamingMasker m = s.newStreamMasker();
        StringBuilder out = new StringBuilder();
        out.append(m.push(INPUT));
        out.append(m.push(" 연락 010-1234-5678"));
        out.append(m.flush());
        assertEquals(INPUT + " 연락 010-****-5678", out.toString());
        assertFalse(m.failed());
    }

    @Test
    @DisplayName("저장 검증 — 잘못된 정규식·500자 초과는 거부")
    void compileValidation() {
        LlmGuardRule bad = GuardTestSupport.regex("x", "(abc", LlmGuardRule.ACTION_CONFIRM, true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CompiledRule.compile(bad));
        assertTrue(e.getMessage().startsWith("정규식 오류"));
        LlmGuardRule tooLong = GuardTestSupport.regex("x", "a".repeat(501), LlmGuardRule.ACTION_CONFIRM, true);
        assertThrows(IllegalArgumentException.class, () -> CompiledRule.compile(tooLong));
        LlmGuardRule emptyKw = GuardTestSupport.keyword("x", "\n  \n", LlmGuardRule.ACTION_CONFIRM, true);
        assertThrows(IllegalArgumentException.class, () -> CompiledRule.compile(emptyKw));
    }
}

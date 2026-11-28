package com.heapdump.analyzer.service.llmguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.heapdump.analyzer.service.llmguard.GuardTestSupport.builtinHits;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 내장 규칙 고도화 (2026-11-28) — 주민·외국인등록번호 · 신용카드번호 · 계좌번호 · 카드 보안코드·유효기간.
 *
 * <p>사용자 결정: 붙여 쓴 13자리는 검증번호 또는 앞 단어가 있을 때만, 하이픈 없는 계좌는 앞 단어가 있을 때만,
 * 카드 보안코드·유효기간 값은 새 내장 규칙(입력 BLOCK, 출력은 값만 가림).
 */
class LlmGuardDetectorsAdvancedTest {

    private static final String RRN = BuiltinDetectors.RRN;
    private static final String CARD = BuiltinDetectors.CARD;
    private static final String ACC = BuiltinDetectors.ACCOUNT;
    private static final String CVC = BuiltinDetectors.CARD_CVC;

    // ── 주민·외국인등록번호 ───────────────────────────────────────

    @Test
    @DisplayName("주민번호 — 대시·전각 하이픈·공백 낀 하이픈도 하이픈 형태로 잡고 구분자를 보존해 가린다")
    void rrnSeparators() {
        assertEquals(List.of("900101 – 1234567=>900101 – 1******"), builtinHits(RRN, "번호 900101 – 1234567"));
        assertEquals(List.of("900101－1234567=>900101－1******"), builtinHits(RRN, "900101－1234567"));
        assertEquals(List.of("900101 - 1234567=>900101 - 1******"), builtinHits(RRN, "900101 - 1234567"));
    }

    @Test
    @DisplayName("주민번호 — 성별자리로 정한 출생 연대에서 윤년·미래 출생까지 검증")
    void rrnBirthValidity() {
        assertEquals(1, builtinHits(RRN, "000229-3234567").size(), "2000-02-29 는 윤일");
        assertTrue(builtinHits(RRN, "010229-3234567").isEmpty(), "2001년에는 2월 29일이 없다");
        assertTrue(builtinHits(RRN, "000229-1234567").isEmpty(), "1900년은 윤년이 아니다");
        assertTrue(builtinHits(RRN, "990101-3234567").isEmpty(), "2099년 출생(미래)은 제외");
        assertEquals(1, builtinHits(RRN, "990101-1234567").size(), "1999년 출생은 통과");
    }

    @Test
    @DisplayName("붙여 쓴 13자리 — 검증번호가 맞거나 앞 단어가 있어야 잡는다 (epoch 밀리초 오탐 방지)")
    void rrnCompact() {
        assertTrue(builtinHits(RRN, "ts=1704067200000").isEmpty(), "2024-01-01 epoch 밀리초는 주민번호가 아니다");
        assertTrue(builtinHits(RRN, "9001011234567").isEmpty(), "검증번호가 틀리고 앞 단어도 없으면 제외");
        assertEquals(List.of("9001011234567=>9001011******"), builtinHits(RRN, "주민번호 9001011234567"),
                "앞 단어가 있으면 검증번호 없이도 잡는다(2020-10 이후 발급 번호)");
        assertEquals(List.of("9001011234567=>9001011******"), builtinHits(RRN, "RRN: 9001011234567"));
        assertEquals(List.of("9001011234568=>9001011******"), builtinHits(RRN, "값 9001011234568"), "주민 공식 검증번호");
        assertEquals(List.of("900101 1234568=>900101 1******"), builtinHits(RRN, "900101 1234568"), "공백 하나는 붙임과 같게 본다");
        assertTrue(builtinHits(RRN, "900101 1234567").isEmpty());
    }

    @Test
    @DisplayName("외국인등록번호 — 성별자리 5~8, 붙여 쓰면 외국인 공식 검증번호도 받는다")
    void foreignerNumber() {
        assertEquals(List.of("900101-5234567=>900101-5******"), builtinHits(RRN, "외국인 900101-5234567"));
        assertEquals(List.of("9001015234561=>9001015******"), builtinHits(RRN, "9001015234561"), "외국인 공식 (13 − s%11) % 10");
        assertEquals(List.of("9001015234569=>9001015******"), builtinHits(RRN, "9001015234569"), "주민 공식으로 발급된 외국인 번호");
        assertTrue(builtinHits(RRN, "9001015234567").isEmpty(), "두 공식 모두 틀리면 제외");
        assertEquals(1, builtinHits(RRN, "외국인등록번호는 9001015234567 입니다").size(), "앞 단어가 있으면 통과");
    }

    @Test
    @DisplayName("붙여 쓴 주민번호 — 입력은 앞 단어 유무로 BLOCK/ALLOW 가 갈린다")
    void rrnCompactVerdict() {
        LlmGuardService s = GuardTestSupport.defaults();
        assertEquals(InputVerdict.Kind.ALLOW, s.checkText("u", "GC at 1704067200000 ms", null).kind());
        assertEquals(InputVerdict.Kind.BLOCK, s.checkText("u", "주민번호 9001011234567 확인", null).kind());
        assertEquals("주민번호 9001011****** 확인", s.maskText("주민번호 9001011234567 확인").text(), "단어는 남기고 숫자만 가린다");
    }

    // ── 신용카드번호 ─────────────────────────────────────────────

    @Test
    @DisplayName("카드번호 — Diners 14·UnionPay 19자리·점 구분, 끝 4자리 보존")
    void cardLengths() {
        assertEquals(List.of("3056-930902-5904=>****-******-5904"), builtinHits(CARD, "카드 3056-930902-5904"));
        assertEquals(List.of("30569309025904=>**********5904"), builtinHits(CARD, "30569309025904"));
        assertEquals(List.of("6212345678901234569=>***************4569"), builtinHits(CARD, "6212345678901234569"));
        assertEquals(List.of("6212 3456 7890 1234 569=>**** **** **** ***4 569"), builtinHits(CARD, "6212 3456 7890 1234 569"));
        assertEquals(List.of("4111.1111.1111.1111=>****.****.****.1111"), builtinHits(CARD, "4111.1111.1111.1111"));
        assertTrue(builtinHits(CARD, "1234567890123456789").isEmpty(), "19자리는 4·5·6 번호대만");
        assertTrue(builtinHits(CARD, "4111-1111 1111.1111").isEmpty(), "구분자는 한 가지만");
    }

    @Test
    @DisplayName("카드번호 — 영문·밑줄에 붙은 숫자열(해시·hex·ID)은 제외, 16자리를 품은 19자리는 1건")
    void cardBoundaries() {
        assertTrue(builtinHits(CARD, "abc4111111111111111").isEmpty());
        assertTrue(builtinHits(CARD, "0x4111111111111111").isEmpty());
        assertTrue(builtinHits(CARD, "id_4111111111111111").isEmpty());
        assertTrue(builtinHits(CARD, "4111111111111111ab").isEmpty());
        assertEquals(1, builtinHits(CARD, "카드번호:4111111111111111 입니다").size(), "한글에 붙은 건 잡는다");
        // 4-4-4-4-3 의 앞 16자리도 Luhn 이 맞는 값 — 같은 값을 두 번 세지 않는다
        String nineteen = nineteenWithValidPrefix16();
        assertEquals(1, builtinHits(CARD, nineteen).size(), "19자리 안의 16자리는 따로 세지 않는다: " + nineteen);
    }

    /** 앞 16자리(4111 1111 1111 1111)와 19자리 전체가 모두 Luhn 을 통과하는 공백 구분 값. */
    private static String nineteenWithValidPrefix16() {
        for (int a = 0; a < 1000; a++) {
            String tail = String.format("%03d", a);
            if (BuiltinDetectors.luhn("4111111111111111" + tail)) return "4111 1111 1111 1111 " + tail;
        }
        throw new AssertionError("없음");
    }

    // ── 계좌번호 ─────────────────────────────────────────────────

    @Test
    @DisplayName("계좌 — 앞 단어가 있으면 붙임·공백 구분도 잡는다, 단어는 남기고 숫자만 가린다")
    void accountContext() {
        assertEquals(List.of("110123456789=>*********789"), builtinHits(ACC, "계좌번호: 110123456789"));
        assertEquals(List.of("123456 78 901234=>123456 ** ***234"), builtinHits(ACC, "국민은행 123456 78 901234"));
        assertEquals(List.of("1234567890=>*******890"), builtinHits(ACC, "account no. 1234567890"));
        assertEquals(List.of("3333011234567=>**********567"), builtinHits(ACC, "카카오뱅크로 3333011234567 보내 주세요"));
        assertEquals("입금 계좌 *********789 로", GuardTestSupport.defaults().maskText("입금 계좌 110123456789 로").text());
        assertTrue(builtinHits(ACC, "110123456789").isEmpty(), "단어가 없으면 붙인 숫자열은 잡지 않는다(현행 유지)");
    }

    @Test
    @DisplayName("계좌 — 금액·건수 단위, 전화번호, 날짜로 시작하는 숫자열, 더 긴 숫자열은 제외")
    void accountContextExclusions() {
        assertTrue(builtinHits(ACC, "계좌 잔액 1234567890원").isEmpty());
        assertTrue(builtinHits(ACC, "이체 1234567890 건").isEmpty());
        assertTrue(builtinHits(ACC, "account size 1234567890 bytes").isEmpty());
        assertTrue(builtinHits(ACC, "은행 대표번호 01012345678").isEmpty(), "휴대전화 모양");
        assertTrue(builtinHits(ACC, "이체 일시 20261128132240").isEmpty(), "yyyyMMdd 로 시작");
        assertTrue(builtinHits(ACC, "계좌 12345678901234567").isEmpty(), "15자리 이상은 계좌로 보지 않는다");
        assertTrue(builtinHits(ACC, "계좌\n110123456789").isEmpty(), "줄을 넘어가면 문맥으로 보지 않는다");
    }

    @Test
    @DisplayName("계좌 — 국제전화 +82·82- 제외, 단어+하이픈 형태는 1건으로 센다")
    void accountHyphenAdvanced() {
        assertTrue(builtinHits(ACC, "+82-10-1234-5678").isEmpty());
        assertTrue(builtinHits(ACC, "82-10-1234-5678").isEmpty());
        assertTrue(builtinHits(ACC, "82-2-1234-5678").isEmpty());
        assertEquals(List.of("3333-01-1234567=>3333-**-****567"), builtinHits(ACC, "카카오뱅크 3333-01-1234567"));
        LlmGuardService s = GuardTestSupport.defaults();
        InputVerdict v = s.checkText("u", "카카오뱅크 3333-01-1234567", null);
        InputVerdict.Finding f = v.findings().stream()
                .filter(x -> ACC.equals(x.rule().builtinKey)).findFirst().orElseThrow();
        assertEquals(1, f.count(), "하이픈 패턴과 문맥 패턴이 같은 값을 잡아도 1건");
        assertEquals("하나은행 123-******-**234 입니다", s.maskText("하나은행 123-456789-01234 입니다").text(),
                "단어가 붙어도 하이픈 형태와 같은 모양으로 가린다");
    }

    // ── 카드 보안코드·유효기간 ─────────────────────────────────────

    @Test
    @DisplayName("보안코드·유효기간 — 단어 뒤 값만 가린다")
    void cvcAndExpiry() {
        assertEquals(List.of("123=>***"), builtinHits(CVC, "CVC 123"));
        assertEquals(List.of("1234=>****"), builtinHits(CVC, "cvv2: 1234"));
        assertEquals(List.of("456=>***"), builtinHits(CVC, "카드 보안코드는 456"));
        assertEquals(List.of("12/27=>**/**"), builtinHits(CVC, "유효기간 12/27"));
        assertEquals(List.of("09/2028=>**/****"), builtinHits(CVC, "EXP 09/2028"));
        assertEquals(List.of("01 / 30=>** / **"), builtinHits(CVC, "valid thru 01 / 30"));
        LlmGuardService s = GuardTestSupport.defaults();
        assertEquals("카드 CVC *** 유효기간 **/**", s.maskText("카드 CVC 123 유효기간 12/27").text());
    }

    @Test
    @DisplayName("보안코드·유효기간 — 다른 숫자·날짜 모양과 단어 일부는 제외")
    void cvcExclusions() {
        assertTrue(builtinHits(CVC, "CVC는 1234567").isEmpty(), "3~4자리가 아니면 제외");
        assertTrue(builtinHits(CVC, "{\"exp\": 1732600000}").isEmpty(), "JWT exp");
        assertTrue(builtinHits(CVC, "인증서 유효기간 2027-12").isEmpty(), "MM/YY 형태만");
        assertTrue(builtinHits(CVC, "expected 10/12 tests").isEmpty(), "exp 는 단어 경계");
        assertTrue(builtinHits(CVC, "유효기간 13/27").isEmpty(), "13월 없음");
        assertTrue(builtinHits(CVC, "보안코드 123456").isEmpty(), "OTP 6자리");
    }

    @Test
    @DisplayName("보안코드 — 입력은 BLOCK, 이미 가린 값은 다시 세지 않는다")
    void cvcVerdict() {
        LlmGuardService s = GuardTestSupport.defaults();
        assertEquals(InputVerdict.Kind.BLOCK, s.checkText("u", "CVC 123 입니다", null).kind());
        assertEquals(0, s.maskText("CVC *** 유효기간 **/**").total(), "가린 값은 건수 0");
    }

    // ── 정의·표시문 ──────────────────────────────────────────────

    @Test
    @DisplayName("기본 정의 — 12종, 키 중복 없음, 설명 255자 이하, 판정기 등록")
    void defaults() {
        assertEquals(12, BuiltinDetectors.DEFAULTS.size());
        List<String> keys = new ArrayList<>();
        for (BuiltinDetectors.Def d : BuiltinDetectors.DEFAULTS) {
            assertFalse(keys.contains(d.key), "키 중복: " + d.key);
            keys.add(d.key);
            if (d.description != null) assertTrue(d.description.length() <= 255, d.key + " 설명이 255자 초과");
            if (!"KEYWORD".equals(d.matchType)) assertNotNull(BuiltinDetectors.detectorFor(d.key), d.key + " 판정기 없음");
        }
        assertTrue(keys.contains(CVC));
    }
}

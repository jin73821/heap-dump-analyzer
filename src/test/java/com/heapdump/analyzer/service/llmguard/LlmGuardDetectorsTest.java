package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.heapdump.analyzer.service.llmguard.GuardTestSupport.builtinHits;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 내장 탐지 규칙의 양성/음성 판정과 판정 규칙(BLOCK 우선·부분 가림·재집계 0) (LLM Guard, 2026-11-26).
 */
class LlmGuardDetectorsTest {

    private static List<Map<String, String>> msg(String text) {
        return List.of(Map.of("role", "user", "content", text));
    }

    @Test
    @DisplayName("주민번호 — 유효 날짜만, 하이픈 선택, 뒤 6자리 가림")
    void rrn() {
        assertEquals(List.of("900101-1234567=>900101-1******"), builtinHits(BuiltinDetectors.RRN, "번호 900101-1234567 입니다"));
        assertEquals(List.of("9001015234567=>9001015******"), builtinHits(BuiltinDetectors.RRN, "9001015234567"));
        assertTrue(builtinHits(BuiltinDetectors.RRN, "901301-1234567").isEmpty(), "13월은 주민번호가 아니다");
        assertTrue(builtinHits(BuiltinDetectors.RRN, "900230-1234567").isEmpty(), "2월 30일은 주민번호가 아니다");
        assertTrue(builtinHits(BuiltinDetectors.RRN, "900101-9234567").isEmpty(), "성별 자리 9 는 제외");
        assertTrue(builtinHits(BuiltinDetectors.RRN, "12900101-1234567").isEmpty(), "더 긴 숫자열의 일부는 제외");
    }

    @Test
    @DisplayName("카드번호 — Luhn 통과만, 끝 4자리만 남김, Amex 15자리")
    void card() {
        assertEquals(List.of("4111-1111-1111-1111=>****-****-****-1111"),
                builtinHits(BuiltinDetectors.CARD, "카드 4111-1111-1111-1111"));
        assertEquals(List.of("4111111111111111=>************1111"), builtinHits(BuiltinDetectors.CARD, "4111111111111111"));
        assertTrue(builtinHits(BuiltinDetectors.CARD, "4111-1111-1111-1112").isEmpty(), "Luhn 실패는 통과");
        assertTrue(builtinHits(BuiltinDetectors.CARD, "0000-0000-0000-0000").isEmpty(), "전부 같은 숫자는 제외");
        assertTrue(builtinHits(BuiltinDetectors.CARD, "1234-5678-9012-3452").isEmpty(), "카드사 번호대가 아니면 제외");
        assertEquals(List.of("3782-822463-10005=>****-******-*0005"), builtinHits(BuiltinDetectors.CARD, "3782-822463-10005"));
    }

    @Test
    @DisplayName("계좌번호 — 하이픈 구분 10~14자리, 전화번호·날짜 모양 제외")
    void account() {
        assertEquals(List.of("110-123-456789=>110-***-***789"), builtinHits(BuiltinDetectors.ACCOUNT, "계좌 110-123-456789"));
        assertEquals(List.of("352-1234-5678-13=>352-****-***8-13"), builtinHits(BuiltinDetectors.ACCOUNT, "352-1234-5678-13"));
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "2026-11-26").isEmpty(), "날짜는 계좌가 아니다");
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "2026-11-26-1234").isEmpty(), "날짜로 시작하면 제외");
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "010-1234-5678").isEmpty(), "휴대전화 모양은 계좌가 아니다");
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "02-1234-5678").isEmpty(), "지역번호 모양은 계좌가 아니다");
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "900101-1234567").isEmpty(), "주민번호 모양은 주민번호 규칙 몫");
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "20261126-123456").isEmpty(), "yyyyMMdd- 로 시작하면 제외");
        assertTrue(builtinHits(BuiltinDetectors.ACCOUNT, "110123456789").isEmpty(), "하이픈 없는 숫자열은 잡지 않는다");
    }

    @Test
    @DisplayName("휴대전화·이메일·여권·운전면허")
    void contactAndIds() {
        assertEquals(List.of("010-1234-5678=>010-****-5678"), builtinHits(BuiltinDetectors.MOBILE, "연락처 010-1234-5678"));
        assertEquals(List.of("01012345678=>010****5678"), builtinHits(BuiltinDetectors.MOBILE, "01012345678"));
        assertEquals(List.of("abcde@corp.com=>ab***@corp.com"), builtinHits(BuiltinDetectors.EMAIL, "메일 abcde@corp.com 로"));
        assertTrue(builtinHits(BuiltinDetectors.EMAIL, "user@example.com").isEmpty(), "예시 도메인 제외");
        assertTrue(builtinHits(BuiltinDetectors.EMAIL, "git@github.com").isEmpty(), "서비스 계정 제외");
        assertEquals(List.of("M12345678=>M1*******"), builtinHits(BuiltinDetectors.PASSPORT, "여권 M12345678"));
        assertEquals(List.of("M123A4567=>M1*******"), builtinHits(BuiltinDetectors.PASSPORT, "M123A4567"));
        assertEquals(List.of("11-22-123456-78=>11-**-******-**"), builtinHits(BuiltinDetectors.DRIVER, "면허 11-22-123456-78"));
        assertTrue(builtinHits(BuiltinDetectors.DRIVER, "112212345678 bytes").isEmpty(), "12자리 바이트 수는 운전면허가 아니다");
    }

    @Test
    @DisplayName("비밀번호·키 값 — 값만 가림, ${…}·ENC(…)·****·빈 값 제외")
    void secretKv() {
        assertEquals(List.of("hunter2=>****"), builtinHits(BuiltinDetectors.SECRET_KV, "password=hunter2"));
        assertEquals(List.of("\"s3cr3t\"=>\"****\""), builtinHits(BuiltinDetectors.SECRET_KV, "{\"apiKey\": \"s3cr3t\"}"));
        assertEquals(List.of("abc=>****"), builtinHits(BuiltinDetectors.SECRET_KV, "DB_PASSWORD=abc"));
        assertTrue(builtinHits(BuiltinDetectors.SECRET_KV, "password=${DB_PASS}").isEmpty());
        assertTrue(builtinHits(BuiltinDetectors.SECRET_KV, "spring.datasource.password=ENC(Zm9vYmFy)").isEmpty());
        assertTrue(builtinHits(BuiltinDetectors.SECRET_KV, "password=****").isEmpty());
        assertTrue(builtinHits(BuiltinDetectors.SECRET_KV, "password=").isEmpty());
        assertTrue(builtinHits(BuiltinDetectors.SECRET_KV, "useSecret=true").isEmpty(), "불리언 플래그 제외");
    }

    static String pem() {
        byte[] body = new byte[1200];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31 + 7);
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(body);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + b64 + "\n-----END RSA PRIVATE KEY-----";
    }

    @Test
    @DisplayName("PEM 개인키 — 블록 전체를 가림, 입력은 BLOCK")
    void pemKey() {
        LlmGuardService s = GuardTestSupport.defaults();
        String text = "키는 아래와 같습니다\n" + pem() + "\n끝";
        MaskResult r = s.maskText(text);
        assertEquals("키는 아래와 같습니다\n" + BuiltinDetectors.PEM_MASK + "\n끝", r.text());
        assertEquals(InputVerdict.Kind.BLOCK, s.checkText("u", text, null).kind());
    }

    @Test
    @DisplayName("사용자 정규식 — (?<v>) 그룹만 부분 가림")
    void namedGroupPartialMask() {
        LlmGuardService s = GuardTestSupport.withRules(null,
                GuardTestSupport.regex("사번", "사번[:=]\\s*(?<v>[0-9]{6})", LlmGuardRule.ACTION_CONFIRM, true));
        assertEquals("담당 사번: 1***** 입니다", s.maskText("담당 사번: 123456 입니다").text());
    }

    @Test
    @DisplayName("키워드 — 대소문자 무시, 영문은 단어 경계")
    void keywordCaseInsensitive() {
        LlmGuardService s = GuardTestSupport.defaults();
        assertEquals(InputVerdict.Kind.CONFIRM, s.checkText("u", "this is confidential data", null).kind());
        assertEquals(InputVerdict.Kind.CONFIRM, s.checkText("u", "대외비 문서입니다", null).kind());
        assertEquals(InputVerdict.Kind.ALLOW, s.checkText("u", "nonconfidentiality", null).kind(), "식별자 안 일치는 제외");
        assertEquals("고객 홍길동 님", s.maskText("고객 홍길동 님").text(), "내장 키워드는 출력 가림 대상이 아니다");

        LlmGuardService s2 = GuardTestSupport.withRules(null,
                GuardTestSupport.keyword("임원 실명", "홍길동\nKim Chulsoo", LlmGuardRule.ACTION_CONFIRM, true));
        assertEquals("고객 홍** 님, k** *******", s2.maskText("고객 홍길동 님, kim chulsoo").text());
    }

    @Test
    @DisplayName("비활성 규칙은 판정하지 않는다")
    void disabledRule() {
        LlmGuardRule off = GuardTestSupport.regex("끈 규칙", "XYZ-[0-9]+", LlmGuardRule.ACTION_BLOCK, true);
        off.setEnabled(false);
        LlmGuardService s = GuardTestSupport.withRules(null, off);
        assertEquals(InputVerdict.Kind.ALLOW, s.checkText("u", "XYZ-1234", null).kind());
        assertEquals("XYZ-1234", s.maskText("XYZ-1234").text());
    }

    @Test
    @DisplayName("BLOCK 이 CONFIRM 보다 우선, 토큰이 있어도 차단")
    void blockWinsOverConfirm() {
        LlmGuardService s = GuardTestSupport.defaults();
        String text = "메일 abcde@corp.com 주민 900101-1234567";
        InputVerdict v = s.checkText("u", text, null);
        assertEquals(InputVerdict.Kind.BLOCK, v.kind());
        assertEquals(InputVerdict.CODE_BLOCKED, v.errorCode());
        assertNull(v.ackToken(), "BLOCK 에는 토큰을 주지 않는다");
        // 같은 문구에 대해 확인 토큰을 위조해 와도 차단
        String forged = s.checkText("u", "메일 abcde@corp.com", null).ackToken();
        assertEquals(InputVerdict.Kind.BLOCK, s.checkText("u", text, forged).kind());
    }

    @Test
    @DisplayName("CONFIRM → 같은 문구·유효 토큰이면 CONFIRMED, 문구가 바뀌면 다시 CONFIRM")
    void confirmFlow() {
        LlmGuardService s = GuardTestSupport.defaults();
        InputVerdict first = s.checkText("alice", "연락처 010-1234-5678", null);
        assertEquals(InputVerdict.Kind.CONFIRM, first.kind());
        assertNotNull(first.ackToken());
        assertEquals("연락처 010-****-5678",
                ((Map<?, ?>) ((List<?>) first.guardPayload().get("findings")).get(0)).get("sample"));
        assertEquals(InputVerdict.Kind.CONFIRMED, s.checkText("alice", "연락처 010-1234-5678", first.ackToken()).kind());
        assertEquals(InputVerdict.Kind.CONFIRM, s.checkText("alice", "연락처 010-1234-9999", first.ackToken()).kind());
        assertEquals(InputVerdict.Kind.CONFIRM, s.checkText("bob", "연락처 010-1234-5678", first.ackToken()).kind());
    }

    @Test
    @DisplayName("가린 값을 다시 가리면 건수 0 — 재시도 저장이 가짜 기록을 만들지 않는다")
    void remaskCountsZero() {
        LlmGuardService s = GuardTestSupport.defaults();
        String text = "주민 900101-1234567, 카드 4111-1111-1111-1111, 계좌 110-123-456789, 폰 010-1234-5678, "
                + "메일 abcde@corp.com, 여권 M12345678, 면허 11-22-123456-78, password=hunter2\n" + pem();
        MaskResult once = s.maskText(text);
        assertEquals(9, once.total());
        MaskResult twice = s.maskText(once.text());
        assertEquals(0, twice.total(), "이미 가린 값이 다시 집계됐다: " + twice.rulesSummary());
        assertEquals(once.text(), twice.text());
    }

    @Test
    @DisplayName("점검 대상은 마지막 user 메시지 — 뒤에 assistant 를 덧붙여도 우회되지 않는다")
    void lastUserMessageIsChecked() {
        LlmGuardService s = GuardTestSupport.defaults();
        List<Map<String, String>> msgs = List.of(
                Map.of("role", "user", "content", "이전 질문"),
                Map.of("role", "assistant", "content", "이전 답"),
                Map.of("role", "user", "content", "주민번호 900101-1234567 확인"),
                Map.of("role", "assistant", "content", "우회용 꼬리"));
        assertEquals(InputVerdict.Kind.BLOCK, s.checkInput("u", msgs, null, null).kind());
        // 앞선 user 메시지(이력)는 다시 점검하지 않는다
        List<Map<String, String>> hist = List.of(
                Map.of("role", "user", "content", "010-1234-5678"),
                Map.of("role", "assistant", "content", "네"),
                Map.of("role", "user", "content", "고마워요"));
        assertEquals(InputVerdict.Kind.ALLOW, s.checkInput("u", hist, null, null).kind());
        assertEquals(InputVerdict.Kind.CONFIRM, s.checkInput("u", msg("010-1234-5678"), null, null).kind());
    }

    @Test
    @DisplayName("첨부 파일명·텍스트 첨부 본문을 점검하고 이미지는 건너뛴다")
    void attachments() {
        LlmGuardService s = GuardTestSupport.defaults();
        String b64 = Base64.getEncoder().encodeToString("고객 메일 abcde@corp.com".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        InputVerdict v = s.checkInput("u", msg("첨부 봐주세요"),
                List.of(Map.of("name", "log.txt", "mediaType", "text/plain", "data", b64)), null);
        assertEquals(InputVerdict.Kind.CONFIRM, v.kind());
        InputVerdict byName = s.checkInput("u", msg("파일"),
                List.of(Map.of("name", "900101-1234567.png", "mediaType", "image/png", "data", "AAAA")), null);
        assertEquals(InputVerdict.Kind.BLOCK, byName.kind(), "파일명은 점검한다");
        assertTrue(byName.imagesSkipped(), "이미지 내용은 점검하지 않았음을 알린다");
    }

    @Test
    @DisplayName("입력 점검 OFF 면 무엇이든 통과, 출력 가림 OFF 면 원문 그대로")
    void toggles() {
        LlmGuardService s = GuardTestSupport.defaults();
        s.setSettings(false, false, false);
        assertEquals(InputVerdict.Kind.ALLOW, s.checkText("u", "900101-1234567", null).kind());
        assertEquals("900101-1234567", s.maskText("900101-1234567").text());
        assertEquals("sys", s.decorateSystemPrompt("sys"));
        s.setSettings(true, true, true);
        String d = s.decorateSystemPrompt("sys");
        assertTrue(d.startsWith("sys") && d.contains(LlmGuardService.INSTRUCTION_MARK));
        assertEquals(d, s.decorateSystemPrompt(d), "지시문은 한 번만 붙는다");
    }

    @Test
    @DisplayName("결과 맵 재귀 가림 — 식별자 키는 건너뛰고 outputGuard 문구를 만든다")
    void maskInPlace() {
        LlmGuardService s = GuardTestSupport.defaults();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("summary", "담당자 abcde@corp.com 에게 연락");
        data.put("recommendations", new java.util.ArrayList<>(List.of("010-1234-5678 로 전화", "그대로")));
        data.put("filename", "abcde@corp.com.hprof");
        MaskResult r = s.maskInPlace(data);
        assertEquals("담당자 ab***@corp.com 에게 연락", data.get("summary"));
        assertEquals(List.of("010-****-5678 로 전화", "그대로"), data.get("recommendations"));
        assertEquals("abcde@corp.com.hprof", data.get("filename"));
        assertEquals(2, r.total());
        assertEquals("개인정보로 보이는 2건을 가렸습니다(이메일 1·휴대전화번호 1)", r.message());
    }
}

package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비밀번호 초기화·수정 화면 (2026-11-27).
 * ① 강제 변경 모달(login-password-expired)을 실제 엔진으로 사유별 렌더 — 초기화는 새 + 확인만, 만료는 현재 + 새 + 확인.
 * ② 계정 관리 '비밀번호' 버튼의 말풍선 메뉴(초기화/수정)와 접속 중 변경 모달(pw-change-gate.js) 배선.
 */
class PasswordResetUiTest {

    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static String render(String reason, String error) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("username", "alice");
        m.put("expiryDays", 90);
        m.put("reason", reason);
        if (error != null) m.put("errorMessage", error);
        m.forEach(ctx::setVariable);
        return engine.process("login-password-expired", ctx);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = PasswordResetUiTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "리소스 없음: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("강제 변경 모달 — 초기화: 새 비밀번호 + 확인만, 닫을 수 없는 열린 모달")
    void resetModal() {
        String html = render("reset", null);
        assertTrue(html.contains("id=\"pwChangeModal\"") && html.contains("class=\"modal-ov open\""), "처음부터 열린 모달");
        assertTrue(html.contains("data-reason=\"reset\""));
        assertTrue(html.contains("새 비밀번호 설정") && html.contains("관리자가 이 계정의 비밀번호를 초기화했습니다"));
        assertFalse(html.contains("name=\"currentPassword\""), "초기화 뒤 첫 로그인은 현재 비밀번호를 다시 묻지 않는다");
        assertTrue(html.contains("name=\"newPassword\"") && html.contains("name=\"confirmPassword\""));
        assertTrue(html.contains("name=\"_csrf\""), "th:action 이 CSRF hidden 을 넣는다");
        assertFalse(html.contains("onclick=\"if(event.target===this)"), "바깥 클릭으로 닫히면 안 된다");
    }

    @Test
    @DisplayName("강제 변경 모달 — 만료: 현재 + 새 + 확인, 오류 문구 재렌더")
    void expiredModal() {
        String html = render("expired", "현재 비밀번호가 일치하지 않습니다.");
        assertTrue(html.contains("data-reason=\"expired\"") && html.contains("비밀번호 변경 필요"));
        assertTrue(html.contains("비밀번호 유효기간이 만료되었습니다") && html.contains(">90<"));
        assertTrue(html.contains("name=\"currentPassword\""));
        assertTrue(html.contains("현재 비밀번호가 일치하지 않습니다."));
    }

    @Test
    @DisplayName("계정 관리 — '비밀번호' 버튼은 말풍선 메뉴(초기화/수정)를 연다")
    void adminPasswordMenu() throws IOException {
        String u = resource("/templates/admin/users.html");
        assertTrue(u.contains("data-act=\"pwmenu\"") && u.contains("aria-haspopup=\"menu\""), "버튼이 메뉴를 연다");
        assertTrue(u.contains("else if (act === 'pwmenu') openUserMenu(btn, u, 'password');"));
        assertTrue(u.contains("menu.innerHTML = kind === 'password' ? passwordMenuHtml(u) :"));
        assertTrue(u.contains("data-act=\"pw-require\"") && u.contains("'비밀번호 초기화'"));
        assertTrue(u.contains("data-act=\"password\"") && u.contains("'비밀번호 수정</button>'"));
        assertTrue(u.contains("'/api/admin/users/' + id + '/require-password-change'"));
        assertTrue(u.contains("id=\"requirePwModal\"") && u.contains("<div class=\"modal-ttl\">비밀번호 수정</div>"));
        assertTrue(u.contains("u.passwordChangeRequired") && u.contains(">변경 대기</span>"), "목록 상태 배지");
    }

    @Test
    @DisplayName("접속 중 변경 모달 — 배너가 새 상태 응답에서만 켜고, 현재 + 새 + 확인을 /api/account/password 로 보낸다")
    void liveGateWiring() throws IOException {
        String banner = resource("/templates/fragments/banner.html");
        assertTrue(banner.contains("<script src=\"/js/pw-change-gate.js?v="));
        int fetchFn = banner.indexOf("function fetchBannerStatus(cb)");
        int apply = banner.indexOf("function applyBannerStatus(d)");
        int call = banner.indexOf("window.PwChangeGate.update(d.passwordChangeRequired === true)");
        assertTrue(fetchFn > 0 && call > fetchFn && call < banner.indexOf("function setDbDown"), "fetchBannerStatus 안에서만");
        assertFalse(banner.substring(apply).contains("PwChangeGate"), "캐시로 그리는 applyBannerStatus 에서 켜면 바꾼 뒤에도 다시 뜬다");
        assertTrue(banner.contains(".gb-pw-ov {"), "common.css 없는 화면용 자립형 스타일");

        String js = resource("/static/js/pw-change-gate.js");
        assertTrue(js.contains("'/api/account/password'") && js.contains("currentPassword: cur, newPassword: np, confirmPassword: cf"));
        assertTrue(js.contains("if (e.key === 'Escape') { e.preventDefault();"), "Esc 로 닫히지 않는다");
        assertTrue(js.contains("global.PwChangeGate = { update: update };"));

        String status = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/controller/HeapSystemApiController.java"));
        assertTrue(status.contains("resp.put(\"passwordChangeRequired\", pwChange);"));
        assertTrue(status.contains("if (db.up() && userService != null && auth != null)"), "DB 장애 때는 묻지 않는다");
    }

    @Test
    @DisplayName("관리자 API — 초기화·수정 모두 감사 로그(by=)를 남긴다")
    void adminAudit() throws IOException {
        String c = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/controller/AdminController.java"));
        assertTrue(c.contains("@PostMapping(\"/api/admin/users/{id}/require-password-change\")"));
        assertTrue(c.contains("[User] action=password-reset-required target={} by={}"));
        assertTrue(c.contains("[User] action=password-set target={} by={}"));
    }
}

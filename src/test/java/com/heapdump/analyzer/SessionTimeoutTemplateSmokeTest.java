package com.heapdump.analyzer;

import com.heapdump.analyzer.controller.SessionModelAdvice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세션 유휴 만료 타이머 배선 회귀 방어 (2026-08-23).
 *
 * <p>이 기능의 근본 원인은 "배너가 60초마다 인증 API 를 쳐서 서버 세션이 영원히 만료되지 않는다" 였다.
 * 여기서 지키려는 것은 두 가지다.
 * <ul>
 *   <li><b>모듈이 실제로 로드되는가</b> — 배너 fragment 1곳 + 배너가 없는 {@code /account/memo} 1곳.
 *       한 곳만 빠져도 그 페이지에서는 세션이 영원히 안 끊긴다.</li>
 *   <li><b>만료 시간이 서버에서 내려오는가</b> — 하드코딩하면 관리자가 타임아웃을 바꿔도 반영되지 않는다.</li>
 * </ul>
 *
 * <p>정적 픽스처가 아니라 실제 {@link SpringTemplateEngine} 으로 렌더하는 이유는 함정 23 때문이다 —
 * 템플릿 파싱이 깨지면 응답이 chunked 전송 도중 끊겨 브라우저에 빈 화면만 뜨는데, 픽스처로는 안 잡힌다.
 */
class SessionTimeoutTemplateSmokeTest {

    /** {@code ${_csrf.token}} / {@code ${_csrf.headerName}} 해석용 최소 스텁. */
    public static final class CsrfStub {
        public String getToken()      { return "test-token"; }
        public String getHeaderName() { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static String render(String template, Map<String, Object> model) {
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
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    private static Map<String, Object> baseModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("sessionTimeoutSeconds", 3600);
        return m;
    }

    @Test
    @DisplayName("배너에 세션 타이머 모듈과 서버 유래 만료 시간이 실린다")
    void bannerLoadsSessionTimeout() {
        String html = render("fragments/banner", baseModel());

        assertTrue(html.contains("/js/session-timeout.js"), "세션 타이머 모듈이 배너에서 로드되지 않는다");
        assertTrue(html.contains("window.SESSION_TIMEOUT_SECONDS = 3600"),
                "만료 시간이 서버 모델에서 내려오지 않는다 (하드코딩하면 관리자 설정 변경이 반영되지 않는다)");
        // common.js 가 먼저여야 한다 — 모듈이 Common.csrfToken() 으로 로그아웃 POST 토큰을 얻는다
        assertTrue(html.indexOf("/js/common.js") < html.indexOf("/js/session-timeout.js"),
                "session-timeout.js 가 common.js 보다 먼저 로드된다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
    }

    @Test
    @DisplayName("배너 폴링이 유휴 시 스스로 멈추고, 401 을 만료로 처리한다")
    void bannerPollIsGatedAndDetects401() {
        String html = render("fragments/banner", baseModel());

        // ⚠ 이 폴링이 무동작 만료를 무력화한 장본인 — 무조건 setInterval 로 되돌리면 버그가 재발한다
        assertTrue(html.contains("SessionTimeout.managedInterval(fetchBannerStatus"),
                "배너 폴링이 managedInterval 로 게이트되지 않는다 — 유휴 세션이 영원히 만료되지 않는다");
        // 종전 죽은 코드: /api/** 는 302 가 아니라 401 JSON 을 준다
        assertTrue(html.contains("r.status === 401"), "배너 폴링이 401 을 감지하지 않는다");
        assertFalse(html.contains("location.href = '/login?expired=true'"),
                "도달 불가능했던 r.redirected 기반 리다이렉트가 남아 있다");
        assertTrue(html.contains("typeof d.matCliReady === 'undefined'"),
                "응답 형태 검사가 없다 — 401 본문이 배너 상태로 렌더되고 캐시까지 오염된다");
        assertTrue(html.contains("function disableUnloadGuards()"),
                "만료 시 스피너 가드를 해제할 수단이 없다");
    }

    @Test
    @DisplayName("/account/memo 는 배너가 없으므로 모듈과 만료 시간을 직접 싣는다")
    void accountMemoIsSelfSufficient() {
        Map<String, Object> m = memoModel();
        String html = render("account-memo", m);

        // 함정 26 — 자립형 새창은 opener 것을 빌릴 수 없다
        assertTrue(html.contains("/js/session-timeout.js"), "새창 페이지에 세션 타이머 모듈이 없다");
        assertTrue(html.contains("window.SESSION_TIMEOUT_SECONDS = 3600"), "새창 페이지에 만료 시간이 없다");
        assertTrue(html.contains("registerNavigationBlock"), "작성 중 내용 보호(이동 차단) 배선이 없다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
    }

    @Test
    @DisplayName("로그인 계열 페이지에는 세션 타이머가 실리지 않는다")
    void loginPagesAreExcluded() {
        Map<String, Object> m = baseModel();
        m.put("errorMessage", null);
        m.put("logoutMessage", null);
        m.put("expiredMessage", null);
        m.put("disabledMessage", null);
        m.put("ssoEnabled", false);
        m.put("ssoNotice", null);

        String html = render("login", m);
        // /login 은 메모장이 재로그인 팝업으로 여는 창이다 — 여기서 자기를 로그아웃시키면 안 된다
        assertFalse(html.contains("/js/session-timeout.js"), "로그인 페이지에 세션 타이머가 실렸다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다");
    }

    @Test
    @DisplayName("만료 시간은 설정값이 아니라 이 세션의 실제 값에서 온다")
    void adviceReadsLiveSession() {
        SessionModelAdvice advice = new SessionModelAdvice();

        MockHttpServletRequest noSession = new MockHttpServletRequest();
        assertEquals(3600, advice.sessionTimeoutSeconds(noSession), "세션이 없으면 기본값이어야 한다");

        // 관리자가 타임아웃을 바꿔도 기존 세션은 옛 간격을 유지한다(setDefaultMaxInactiveInterval 은
        // 신규 세션에만 적용). 설정값을 내려보내면 클라이언트가 경고도 못 띄운 채 401 을 맞는다.
        MockHttpServletRequest withSession = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(7200);
        withSession.setSession(session);
        assertEquals(7200, advice.sessionTimeoutSeconds(withSession), "세션의 실제 만료 간격을 읽어야 한다");

        // 0 이하(만료 없음)는 클라이언트가 즉시 만료시키지 않도록 기본값으로 대체
        MockHttpServletRequest never = new MockHttpServletRequest();
        MockHttpSession neverSession = new MockHttpSession();
        neverSession.setMaxInactiveInterval(0);
        never.setSession(neverSession);
        assertEquals(3600, advice.sessionTimeoutSeconds(never));
    }

    /** account-memo 렌더에 필요한 최소 모델. */
    private static Map<String, Object> memoModel() {
        com.heapdump.analyzer.model.entity.User user = new com.heapdump.analyzer.model.entity.User();
        user.setUsername("tester");
        user.setDisplayName("테스터");
        user.setRole(com.heapdump.analyzer.model.entity.User.Role.USER);
        user.setEnabled(true);
        user.setMemo("메모 본문");
        user.setMemoFont("d2coding");
        user.setMemoUpdatedAt(java.time.LocalDateTime.of(2026, 8, 6, 14, 15));

        Map<String, Object> m = baseModel();
        m.put("user", user);
        m.put("memoAutosave", true);
        return m;
    }
}

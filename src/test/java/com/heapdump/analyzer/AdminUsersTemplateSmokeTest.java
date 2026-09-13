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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * admin/users.html 렌더 스모크 — 설정 탭의 Account Lockout Policy 카드 (2026-09-13).
 *
 * <p>방어 대상 ①: 함정 23 — {@code th:inline} 없는 {@code <script>} 안의 {@code [[} 도
 * Thymeleaf 3 는 인라인 표현식으로 해석해 파싱이 깨지고, 응답이 이미 chunked 로 나가던 중이면
 * 브라우저에는 빈 화면({@code ERR_INCOMPLETE_CHUNKED_ENCODING})만 뜬다.
 *
 * <p>방어 대상 ②: 잠금 정책의 초기값은 컨트롤러 모델을 <b>서버 렌더</b>한 {@code data-*} 속성에서
 * 읽는다 (JS 가 별도 GET 을 하지 않는다). 이 배선이 끊기면 화면은 멀쩡한데 토글이 늘 기본값으로
 * 보이고, 관리자가 그 상태로 저장하면 정책이 조용히 되돌아간다.
 */
class AdminUsersTemplateSmokeTest {

    /** {@code ${_csrf.token}} / {@code ${_csrf.headerName}} 해석용 최소 스텁. */
    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static String render(Map<String, Object> model) {
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
        return engine.process("admin/users", ctx);
    }

    /** AdminController.usersPage() 가 넣는 모델과 같은 키 구성 */
    private static Map<String, Object> model(boolean lockEnabled, int threshold, boolean adminExempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("twoFactorMode", "off");
        m.put("twoFactorAdminPolicy", "enforce_no_lock");
        m.put("ssoEndpointUrl", "");
        m.put("ssoClientId", "");
        m.put("ssoRedirectUri", "");
        m.put("ssoClientSecretSet", false);
        m.put("passwordExpiryDays", 0);
        m.put("passwordExpiryAdminExempt", true);
        m.put("accountLockoutEnabled", lockEnabled);
        m.put("accountLockoutThreshold", threshold);
        m.put("accountLockoutAdminExempt", adminExempt);
        return m;
    }

    @Test
    @DisplayName("admin/users.html 이 끝까지 파싱/렌더되고 Account Lockout Policy 카드가 존재한다")
    void adminUsersRenders() {
        String html = render(model(false, 10, true));

        assertTrue(html.contains("Account Lockout Policy"), "잠금 정책 카드가 렌더되지 않았다");
        assertTrue(html.contains("id=\"lockEnabled\""), "기능 사용 토글이 없다");
        assertTrue(html.contains("id=\"lockThreshold\""), "임계 횟수 입력이 없다");
        assertTrue(html.contains("id=\"lockAdminExempt\""), "관리자 예외 토글이 없다");
        assertTrue(html.contains("/api/settings/account-lockout"), "저장 엔드포인트 호출부가 없다");
        // 파싱이 중간에 끊기면 여기까지 오지 못한다 (함정 23).
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:replace"), "미처리 th:replace 가 남았다");
        assertFalse(html.contains("[["), "인라인 표현식으로 해석될 '[[' 가 남아 있다");
    }

    @Test
    @DisplayName("잠금 정책 초기값은 서버 렌더 data-* 로 내려간다 (JS 가 GET 으로 다시 묻지 않는다)")
    void lockPolicyInitialStateIsServerRendered() {
        String on = render(model(true, 5, false));
        assertTrue(on.contains("data-enabled=\"true\""), "활성 상태가 서버 렌더되지 않았다");
        assertTrue(on.contains("data-threshold=\"5\""), "임계 횟수가 서버 렌더되지 않았다");
        assertTrue(on.contains("data-exempt=\"false\""), "관리자 예외가 서버 렌더되지 않았다");

        String off = render(model(false, 10, true));
        assertTrue(off.contains("data-enabled=\"false\""));
        assertTrue(off.contains("data-threshold=\"10\""));
        assertTrue(off.contains("data-exempt=\"true\""));
    }

    @Test
    @DisplayName("사용자 목록: 최근 접속 열 + 조밀 표·작업 버튼 스타일이 사용자 탭에만 갇혀 있다 (2026-09-14)")
    void userListDensityAndActions() {
        String html = render(model(false, 10, true));

        assertTrue(html.contains(">최근 접속</th>"), "최근 접속 열 헤더가 없다");
        assertTrue(html.contains("<th class=\"u-act-h\">작업</th>"), "작업 열이 오른쪽 정렬 헤더가 아니다");
        assertTrue(html.contains("<tbody id=\"userBody\">"), "이벤트 위임 대상 tbody id 가 없다");
        assertTrue(html.contains("<td colspan=\"8\" class=\"lh-empty\">"), "열이 8개인데 빈 행 colspan 이 맞지 않다");
        assertFalse(html.contains("colspan=\"7\" class=\"lh-empty\">'"), "옛 7열 colspan 이 JS 에 남아 있다");

        // 조밀 표 규칙은 #panel-users 로 가둔다 — 다른 탭(현재 접속/접속 이력/계정 신청) 표를 건드리면 안 된다
        assertTrue(html.contains("#panel-users .utable td { padding: 5px 10px;"), "조밀 표 규칙이 사용자 탭 범위가 아니다");
        // 버튼 폰트 상속 누락이 종전 결함(라벨 Arial) — 되돌아가면 안 된다
        int ubtn = html.indexOf(".ubtn { display: inline-flex;");
        assertTrue(ubtn > 0 && html.indexOf("font-family: inherit;", ubtn) - ubtn < 400, "작업 버튼이 폰트를 상속하지 않는다");
        // 메뉴는 표 스크롤 컨테이너에 잘리지 않도록 fixed
        assertTrue(html.contains(".u-menu { position: fixed;"), "더보기 메뉴가 fixed 가 아니다 (overflow-x 컨테이너에 잘린다)");
        // 위험·드문 동작은 메뉴로, 기본 관리자 삭제는 메뉴에서 비활성
        assertTrue(html.contains("data-act=\"delete\"") && html.contains("보호 계정"), "삭제 메뉴 항목/보호 계정 안내가 없다");
        assertTrue(html.contains("aria-haspopup=\"menu\""), "더보기 버튼 접근성 속성이 없다");
        assertTrue(html.contains("function renderLastLogin(u)") && html.contains("기록 없음"), "최근 접속 렌더러가 없다");
    }

    @Test
    @DisplayName("잠금 해제 모달은 사유(OTP/비밀번호)를 표시할 자리를 가진다")
    void unlockModalCarriesReasonSlot() {
        String html = render(model(true, 10, true));
        assertTrue(html.contains("id=\"ulReason\""), "잠금 사유 표시 자리가 없다");
        assertFalse(html.contains("잠금(OTP 반복 실패)을 해제"),
                "사유가 OTP 로 고정된 옛 문구가 남아 있다 (비밀번호 잠금에 거짓 안내)");
    }
}

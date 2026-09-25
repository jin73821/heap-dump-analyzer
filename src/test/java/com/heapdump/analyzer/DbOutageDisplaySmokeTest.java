package com.heapdump.analyzer;

import com.heapdump.analyzer.controller.AuthController;
import com.heapdump.analyzer.service.TwoFactorConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAS 정상·DB 비정상 표시 (2026-09-26, docs/DB_OUTAGE_DISPLAY_PLAN.md) — 템플릿 렌더 + 배선 소스 계약.
 *
 * <p>핵심 전제 두 가지를 고정한다. ① 503 페이지는 <b>자립형</b>이어야 한다 — ERROR 디스패치는 세션 필터를 타지 않고
 * 세션 저장소가 DB 라 배너·{@code _csrf} 를 참조하면 렌더 중 다시 죽는다. ② 보안 설정이 ERROR 디스패치를 허용·CSRF 면제해야
 * 오류 페이지가 로그인 리다이렉트/401(세션 만료 오인)로 바뀌지 않는다.
 */
class DbOutageDisplaySmokeTest {

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

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("503 페이지는 세션·CSRF·배너 없이 렌더되고 경로·재시도 초를 data-* 로 싣는다")
    void dbUnavailablePageIsSelfContained() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "데이터베이스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        m.put("path", "/alerts");
        m.put("occurredAt", "2026-09-26 10:00:00");
        m.put("retryAfter", 30);
        m.put("detail", "SQLNonTransientConnectionException: Connection refused");
        String html = render("error/db-unavailable", m);   // 모델에 _csrf 가 없어도 렌더돼야 한다
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다");
        assertTrue(html.contains("데이터베이스에 연결할 수 없습니다") && html.contains("2026-09-26 10:00:00") && html.contains("Connection refused"));
        assertTrue(html.contains("data-path=\"/alerts\"") && html.contains("data-retry=\"30\""), "재시도 배선");
        assertTrue(html.contains("HTTP 503"));
        String tpl = read("src/main/resources/templates/error/db-unavailable.html");
        assertFalse(tpl.contains("_csrf") || tpl.contains("fragments/banner") || tpl.contains("th:replace"), "자립형이어야 한다(세션 참조 금지)");
        assertFalse(tpl.contains("<script src=") || tpl.contains("<link "), "외부 리소스 의존 금지");
        assertFalse(tpl.contains("[["), "함정 23");
        assertFalse(tpl.contains("border-left"), "좌측 색 띠 금지");
        assertFalse(html.contains("th:text") || html.contains("th:if") || html.contains("th:data-"), "미처리 th: 속성");
        // detail 이 없으면 그 블록이 빠진다
        m.put("detail", "");
        assertFalse(render("error/db-unavailable", m).contains("class=\"detail\""));
    }

    @Test
    @DisplayName("로그인 — 사용자 조회 실패(DB)는 자격 증명 오류와 다른 문구로")
    void loginShowsUnavailableSeparately() {
        AuthController c = new AuthController(Mockito.mock(TwoFactorConfigService.class));
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("login", c.loginPage("unavailable", null, null, null, model));
        assertTrue(String.valueOf(model.get("unavailableMessage")).contains("데이터베이스에 연결할 수 없어"));
        assertNull(model.get("errorMessage"), "DB 장애가 '아이디 또는 비밀번호 오류' 로 보이면 안 된다");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new SessionTimeoutTemplateSmokeTest.CsrfStub());
        m.put("sessionTimeoutSeconds", 3600);
        m.put("unavailableMessage", "데이터베이스에 연결할 수 없어 로그인할 수 없습니다.\n잠시 후 다시 시도해 주세요.");
        String html = render("login", m);
        assertTrue(html.contains("데이터베이스에 연결할 수 없어 로그인할 수 없습니다"), "로그인 화면에 문구가 없다");
        assertTrue(html.trim().endsWith("</html>"));
    }

    @Test
    @DisplayName("Hikari 타임아웃은 properties 키가 없어도 코드 기본값 5초·2초 — application.properties 는 .gitignore 대상이다")
    void hikariDefaultsLiveInCode() {
        org.springframework.boot.jdbc.autoconfigure.DataSourceProperties p = new org.springframework.boot.jdbc.autoconfigure.DataSourceProperties();
        p.setUrl("jdbc:mariadb://127.0.0.1:1/none");   // 연결은 하지 않는다(Hikari 는 첫 getConnection 에서 연결)
        p.setDriverClassName("org.mariadb.jdbc.Driver");
        p.setUsername("u");
        p.setPassword("p");
        try (com.zaxxer.hikari.HikariDataSource ds = new com.heapdump.analyzer.config.DataSourceConfig().dataSource(p)) {
            assertEquals(5_000L, ds.getConnectionTimeout(), "코드 기본 connection-timeout");
            assertEquals(2_000L, ds.getValidationTimeout(), "코드 기본 validation-timeout");
        }
    }

    @Test
    @DisplayName("배선 — 보안 설정(ERROR 디스패치 허용·CSRF 면제·실패 분기), 배너 DB 행·스트립, fetchJSON 503 승격, Hikari 타임아웃")
    void wiringContracts() throws IOException {
        String sec = read("src/main/java/com/heapdump/analyzer/config/SecurityConfig.java");
        assertTrue(sec.contains(".dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()"), "ERROR 디스패치가 허용되지 않으면 503 이 로그인 리다이렉트/401 로 바뀐다");
        assertTrue(sec.indexOf(".dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()") < sec.indexOf(".anyRequest()"), "permitAll 은 anyRequest 앞");
        assertTrue(sec.contains(".ignoringRequestMatchers(new DispatcherTypeRequestMatcher(DispatcherType.ERROR))"), "ERROR 디스패치 CSRF 면제");
        assertTrue(sec.contains("ex instanceof InternalAuthenticationServiceException") && sec.contains("/login?error=unavailable"), "로그인 실패 분기");

        String banner = read("src/main/resources/templates/fragments/banner.html");
        assertTrue(banner.contains("id=\"gbDbDot\"") && banner.contains("id=\"gbDbVal\""), "배너 Database 행");
        assertTrue(banner.contains("Common.isDbUnavailable(503, t)") && banner.contains("r.status === 503"), "상태 폴링이 503 DB_UNAVAILABLE 을 읽지 않는다");
        assertTrue(banner.contains("addEventListener('db:unavailable'") && banner.contains("addEventListener('db:available'"), "이벤트 배선");
        assertTrue(banner.contains("d.dbUp === false"), "dbUp 필드 반영");
        int css = banner.indexOf("#gbDbStrip {");
        assertTrue(css > 0 && !banner.substring(css, banner.indexOf("@media", css)).contains("border-left"), "스트립에 좌측 색 띠");
        assertTrue(banner.contains("/js/common.js?v=2026-09-26"), "common.js 캐시 키 갱신");

        String common = read("src/main/resources/static/js/common.js");
        assertTrue(common.contains("err.dbUnavailable = true") && common.contains("Common.isDbUnavailable = function") && common.contains("'db:unavailable'"), "fetchJSON 503 승격");
        assertTrue(common.contains("b.code === 'DB_UNAVAILABLE'"), "코드 판정");

        String st = read("src/main/resources/static/js/session-timeout.js");
        int ping = st.indexOf("function ping(");
        String pingBody = st.substring(ping, st.indexOf("/* ── 경고 모달", ping));
        assertTrue(pingBody.contains("r.status === 401 || r.status === 403") && !pingBody.contains("503"), "keepalive 는 503 을 만료로 보면 안 된다");

        String props = read("src/main/resources/application.properties");
        assertTrue(props.contains("\nspring.datasource.hikari.connection-timeout=5000") && props.contains("\nspring.datasource.hikari.validation-timeout=2000"), "Hikari 타임아웃(장애 시 30초 대기 방지)");
        String dsc = read("src/main/java/com/heapdump/analyzer/config/DataSourceConfig.java");
        assertTrue(dsc.contains("@ConfigurationProperties(\"spring.datasource.hikari\")") && dsc.contains("public HikariDataSource dataSource("),
                "직접 만든 DataSource 빈에는 hikari.* 가 바인딩되지 않는다 — 프리픽스 + HikariDataSource 반환형이 필요(실측: 없으면 30초 대기)");
        Path k8s = Path.of("/opt/genspark/heapApp_k8s/config/application.properties");
        if (Files.exists(k8s)) assertTrue(read(k8s.toString()).contains("\nspring.datasource.hikari.connection-timeout=5000"), "k8s 설정에도 Hikari 타임아웃");

        String bell = read("src/main/resources/static/js/alert-bell.js");
        assertTrue(bell.contains("e.dbUnavailable"), "알림 벨 DB 문구");
        String live = read("src/main/resources/static/js/live-refresh.js");
        assertTrue(live.contains("isDbUnavailable(r.status, t)") && live.contains("갱신 실패 · DB 연결 불가"), "부분 갱신 DB 문구");
    }
}

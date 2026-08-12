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
 * llm-settings.html 템플릿 렌더 스모크 (2026-08-12).
 *
 * <p>방어 대상: Thymeleaf 3 는 <b>{@code th:inline} 이 없는 평범한 {@code <script>} 안에서도</b>
 * 여는 대괄호 2연속({@code [[})을 인라인 표현식 시작으로 해석한다. 파싱이 실패하면 응답이 이미
 * chunked 로 나가던 중이라 종료 마커가 없어 브라우저에는 {@code ERR_INCOMPLETE_CHUNKED_ENCODING}
 * 빈 화면만 뜬다 — 정적 픽스처 검증으로는 절대 못 잡는 부류다.
 *
 * <p>여기서는 SpringTemplateEngine 없이 순수 Thymeleaf 엔진으로 템플릿을 직접 렌더해
 * <b>파싱 단계</b>만 확인한다. 배너 fragment 의 {@code sec:} 속성은 미등록 dialect 라 그대로
 * 통과하므로 별도 스텁이 필요 없다.
 */
class LlmSettingsTemplateSmokeTest {

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

        // 순수 TemplateEngine 은 OGNL 을 요구한다 — Boot 와 동일하게 SpEL 을 쓰는
        // SpringTemplateEngine 을 사용하고, SpEL 평가 컨텍스트를 직접 넣어준다.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        // 배너 fragment 가 @{/logout} 같은 컨텍스트 상대 링크를 쓰므로 IWebContext 가 필요하다.
        // (없으면 배너에서 렌더가 중단돼 정작 검사하려는 본문까지 도달하지 못한다)
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());

        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    @Test
    @DisplayName("llm-settings.html 이 파싱/렌더된다 (Rate Limit 카드 포함)")
    void llmSettingsRenders() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("llm-settings", model);

        assertTrue(html.contains("Rate Limit"), "Rate Limit 카드가 렌더되지 않았다");
        assertTrue(html.contains("id=\"rlPerSecond\""));
        assertTrue(html.contains("id=\"rlPerMinute\""));
        assertTrue(html.contains("id=\"rlPerDay\""));
        assertTrue(html.contains("id=\"rlConcurrent\""));
        assertTrue(html.contains("/api/llm/ratelimit"), "저장 엔드포인트 호출부가 없다");
        // 파싱이 중간에 끊기면 여기까지 오지 못한다 — 운영에서는 ERR_INCOMPLETE_CHUNKED_ENCODING 로 나타난다.
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:replace"), "미처리 th:replace 가 남았다");
        assertTrue(html.contains("test-token"), "CSRF meta 가 렌더되지 않았다");
    }

    @Test
    @DisplayName("비-ADMIN 렌더도 파싱된다 (읽기 전용 배너 경로)")
    void llmSettingsRendersForNonAdmin() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", false);

        String html = render("llm-settings", model);

        assertTrue(html.contains("읽기 전용"), "비-ADMIN 안내 배너가 없다");
        assertTrue(html.contains("Rate Limit"));
    }
}

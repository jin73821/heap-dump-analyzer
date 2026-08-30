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
 * rag-settings.html 템플릿 렌더 스모크 (2026-08-29, Chroma 모드 추가).
 *
 * <p>방어 대상: Thymeleaf 3 는 <b>{@code th:inline} 이 없는 평범한 {@code <script>} 안에서도</b>
 * 여는 대괄호 2연속({@code [[})을 인라인 표현식 시작으로 해석한다. 파싱이 실패하면 응답이 이미
 * chunked 로 나가던 중이라 종료 마커가 없어 브라우저에는 {@code ERR_INCOMPLETE_CHUNKED_ENCODING}
 * 빈 화면만 뜬다 — 정적 픽스처 검증으로는 절대 못 잡는 부류다.
 *
 * <p>707줄짜리 이 템플릿에는 그동안 스모크 테스트가 없었다. Chroma 카드를 추가하면서
 * 정확히 이 지뢰를 밟을 수 있는 편집을 했으므로 함께 도입한다.
 *
 * <p>여기서는 SpringTemplateEngine 없이 순수 Thymeleaf 엔진으로 템플릿을 직접 렌더해
 * <b>파싱 단계</b>만 확인한다. 배너 fragment 의 {@code sec:} 속성은 미등록 dialect 라 그대로
 * 통과하므로 별도 스텁이 필요 없다.
 */
class RagSettingsTemplateSmokeTest {

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
    @DisplayName("rag-settings.html 이 파싱/렌더된다 (Chroma 카드 포함)")
    void ragSettingsRenders() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 신규 Chroma 카드
        assertTrue(html.contains("id=\"cardChroma\""), "Chroma 카드가 렌더되지 않았다");
        assertTrue(html.contains("value=\"chroma\""), "Search Mode 에 chroma 옵션이 없다");
        assertTrue(html.contains("id=\"chromaUrl\""));
        assertTrue(html.contains("id=\"chromaCollection\""));
        assertTrue(html.contains("id=\"chromaSpace\""));
        assertTrue(html.contains("id=\"chromaToken\""));
        assertTrue(html.contains("/api/settings/rag/chroma/test"), "Chroma 테스트 엔드포인트 호출부가 없다");
        assertTrue(html.contains("local-onnx"), "임베딩 provider 에 local-onnx 가 없다");
        assertTrue(html.contains("id=\"minScoreHint\""), "모드별 Min Score 힌트 요소가 없다");

        // 2026-08-29 — Chroma 연동 항목 상시 표시 + 연동 상태 패널
        assertTrue(html.contains("id=\"cardChromaStatus\""), "Chroma 연동 상태 카드가 없다");
        assertTrue(html.contains("/api/settings/rag/chroma/status"), "상태 엔드포인트 호출부가 없다");
        assertTrue(html.matches("(?s).*<button[^>]*id=\"chromaStatusRefresh\"[^>]*refresh-btn.*")
                || html.matches("(?s).*<button[^>]*refresh-btn[^>]*id=\"chromaStatusRefresh\".*"),
                "새로고침 버튼에 refresh-btn 이 없다 — 비-ADMIN IIFE 가 비활성화한다");
        assertTrue(html.contains("id=\"chromaModeBadge\"") && html.contains("id=\"embModeBadge\""), "모드 배지가 없다");
        // 2026-08-29(2차) — 모드 중심 재구성: 검색 모드 카드 + ES/Chroma 상호 배제 + 2열 래퍼
        assertTrue(html.contains("id=\"cardSearchMode\""), "검색 모드 카드가 없다");
        assertTrue(html.contains("id=\"cardEsConnection\"") && html.contains("id=\"cardEsAuth\""),
                "ES 카드가 모드로 제어되는 id 를 갖고 있지 않다");
        assertTrue(html.contains("class=\"rag-cols\"") && html.contains("/.rag-cols"), "2열 래퍼가 없다");
        assertTrue(html.contains("class=\"mode-grid\"") && html.contains("onModeRadioChange"), "모드 라디오가 없다");
        // 라디오 4개 + 값 보관용 select 는 함께 있어야 한다(기존 JS 가 ragMode.value 를 읽는다)
        for (String m : new String[]{"keyword", "semantic-server", "semantic-client", "chroma"}) {
            assertTrue(html.contains("name=\"ragModeRadio\" id=\"mode") && html.contains("value=\"" + m + "\""),
                    "모드 " + m + " 선택지가 없다");
        }
        assertTrue(html.contains("id=\"ragMode\""), "값 보관용 select 가 사라졌다 — buildConnPayload 가 깨진다");
        // ES 계열 모드에서 Chroma 준비 상태를 알리는 요약 줄 + 미리보기 토글
        assertTrue(html.contains("id=\"backendSummary\"") && html.contains("id=\"chromaPeekBtn\""), "백엔드 요약 줄이 없다");
        assertTrue(html.contains("function toggleChromaPeek"), "Chroma 미리보기 토글이 없다");
        // 카드 가시성은 _show() 한 곳에서만 — 개별 style.display 직접 조작이 흩어지면 모드 배제가 샌다
        assertTrue(html.contains("_show('cardEsConnection'") && html.contains("_show('cardChroma'"),
                "모드별 카드 표시가 _show() 를 경유하지 않는다");

        // 결함 ①: label.tog 는 common.css .tog{display:none} 에 걸려 컨트롤 전체가 사라졌다
        assertTrue(html.contains("<input type=\"checkbox\" class=\"tog\" id=\"chromaSslVerify\""), "TLS 토글 input 마크업이 다르다");
        assertTrue(html.contains("<label class=\"tog-track\" for=\"chromaSslVerify\">"), "TLS 토글 track 이 없다");
        assertFalse(html.contains("<label class=\"tog\">"), "보이지 않는 label.tog 패턴이 남아 있다");
        // 결함 ②: 미정의 클래스
        assertFalse(html.contains("btn-test"), "미정의 btn-test 클래스가 남아 있다");
        // 결함 ③: chroma 모드 Test Connection 라우팅
        assertTrue(html.contains("id=\"ragTestBtn\"") && html.contains("function testChromaStack"), "chroma 모드 테스트 라우팅이 없다");
        // 결함 ④: local-onnx 안내
        assertTrue(html.contains("onEmbProviderChange"), "provider 변경 핸들러가 없다");
        assertTrue(html.contains("id=\"embSidecarFillBtn\"") && html.contains("id=\"ragEmbApiKeyRow\"")
                && html.contains("id=\"ragEmbModelRow\"") && html.contains("id=\"embKnnBox\""), "local-onnx 안내 요소가 없다");
        // 문구
        assertFalse(html.contains("세 가지 검색 모드"), "안내문이 아직 3개 모드다");
        assertFalse(html.contains("RAG (Elasticsearch) Configuration"), "제목이 아직 Elasticsearch 전용이다");
        // 저장 피드백은 토스트 1회 — 완료 메시지와 경고를 따로 띄우면 같은 좌표에 포개진다
        assertFalse(html.contains("주의: chroma 모드인데"), "저장 경고를 별도 토스트로 띄우고 있다");
        assertTrue(html.contains("RAG 설정 저장 완료 — 단,"), "경고가 완료 메시지에 합쳐지지 않았다");

        // 폴링 금지(함정 38): 이 페이지의 인라인 스크립트에 setInterval 이 있으면 안 된다
        // (배너 fragment 의 시스템 상태 폴러는 managedInterval 경유라 검사 범위에서 뺀다)
        int inlineStart = html.indexOf("var toast = Common.toast");
        assertTrue(inlineStart > 0, "인라인 스크립트 시작점을 찾지 못했다");
        assertFalse(html.substring(inlineStart).contains("setInterval"),
                "배경 폴링이 추가됐다 — SessionTimeout.managedInterval 없이는 금지");

        // 기존 모드가 그대로 남아 있어야 한다 (롤백 경로)
        assertTrue(html.contains("value=\"keyword\""));
        assertTrue(html.contains("value=\"semantic-server\""));
        assertTrue(html.contains("value=\"semantic-client\""));

        // 파싱이 중간에 끊기면 여기까지 오지 못한다 — 운영에서는 ERR_INCOMPLETE_CHUNKED_ENCODING.
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:replace"), "미처리 th:replace 가 남았다");
        assertTrue(html.contains("test-token"), "CSRF meta 가 렌더되지 않았다");
    }

    @Test
    @DisplayName("Save All / Test Connection / Reload 은 같은 높이로 렌더된다")
    void actionButtonsShareOneSizeRule() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", true);

        String html = render("rag-settings", model);

        // 2026-08-30 제보: 세 버튼 높이가 31 / 35 / 33 px 로 제각각이었다. 원인은 두 개다.
        //  ① border 가 .btn-secondary 에만 있어 Reload 만 2px 크다.
        //  ② line-height 미지정 → 한글이 섞인 라벨("Test Connection (Chroma + 임베딩)")이
        //     fallback 폰트의 큰 행상자를 그대로 높이로 가져가 4px 더 크다.
        // 둘 다 "예외 없이 조용히 어긋나는" 부류라 앵커를 박아 둔다.
        assertTrue(html.contains(".btn-primary, .btn-secondary, .btn-success {"),
                "3종 버튼의 치수 규칙이 한 곳에 모여 있지 않다 — 다시 제각각으로 갈라진다");
        assertTrue(html.contains("border: 1px solid transparent"),
                "채움 버튼에 1px transparent border 가 없다 — .btn-secondary 만 2px 커진다");
        assertTrue(html.matches("(?s).*\\.btn-primary, \\.btn-secondary, \\.btn-success \\{[^}]*line-height: 18px.*"),
                "공통 규칙에 고정 line-height 가 없다 — 한글 라벨이 라틴 라벨보다 높아진다");
        assertTrue(html.matches("(?s).*\\.btn-primary, \\.btn-secondary, \\.btn-success \\{[^}]*font-family: inherit.*"),
                "button 은 폰트를 상속하지 않는다 — inherit 이 없으면 본문과 다른 폰트로 렌더된다");

        // 액션 바 3개는 인라인 치수가 아니라 .btn-act 하나를 공유해야 한다.
        for (String label : new String[]{"Save All", "Reload"}) {
            assertTrue(html.matches("(?s).*<button[^>]*btn-act[^>]*>" + label + "</button>.*"),
                    label + " 버튼이 .btn-act 를 쓰지 않는다");
        }
        assertTrue(html.matches("(?s).*<button[^>]*id=\"ragTestBtn\"[^>]*btn-act.*"),
                "Test Connection 버튼이 .btn-act 를 쓰지 않는다");
        assertFalse(html.contains("style=\"padding:8px 20px;font-size:13px\""),
                "인라인 치수가 남아 있다 — 규칙이 두 곳으로 갈라지면 다시 어긋난다");
    }

    @Test
    @DisplayName("비-ADMIN 렌더도 파싱된다 (읽기 전용 경로)")
    void ragSettingsRendersForNonAdmin() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_csrf", new CsrfStub());
        model.put("isAdmin", false);

        String html = render("rag-settings", model);
        assertTrue(html.contains("id=\"cardChroma\""));
        assertTrue(html.contains("id=\"cardChromaStatus\""), "비-ADMIN 도 연동 상태를 읽을 수 있어야 한다");
        assertTrue(html.trim().endsWith("</html>"));
    }
}

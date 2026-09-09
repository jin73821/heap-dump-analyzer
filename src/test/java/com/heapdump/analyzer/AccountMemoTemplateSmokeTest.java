package com.heapdump.analyzer;

import com.heapdump.analyzer.model.entity.User;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 개인 메모장 두 페이지(/account · /account/memo) 렌더 스모크 (2026-08-12).
 *
 * <p>미저장 백업 뷰어를 붙이며 두 템플릿의 마크업을 건드렸다. Thymeleaf 3 는
 * {@code th:inline} 이 없는 평범한 {@code <script>} 안의 {@code [[} 도 인라인 표현식으로
 * 해석하는데, 파싱이 깨지면 응답이 chunked 전송 도중 끊겨 브라우저에는 빈 화면만 뜬다
 * (함정 23). 정적 픽스처 검증으로는 잡히지 않으므로 실제 엔진으로 렌더해 확인한다.
 */
class AccountMemoTemplateSmokeTest {

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

        // 배너 fragment 의 @{/logout} 같은 컨텍스트 상대 링크는 IWebContext 를 요구한다.
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());

        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    /** {@code <html ...>} 여는 태그만 추출 — 문서 전체에서 클래스명을 grep 하면 CSS 규칙에 걸린다. */
    private static String htmlTag(String html) {
        int s = html.indexOf("<html");
        return html.substring(s, html.indexOf('>', s) + 1);
    }

    /** 컨트롤러가 넘기는 모델을 그대로 흉내낸다 (role 은 enum 이라 Map 스텁으로는 안 된다). */
    private static Map<String, Object> memoModel() {
        User user = new User();
        user.setUsername("tester");
        user.setDisplayName("테스터");
        user.setRole(User.Role.USER);
        user.setEnabled(true);
        user.setMemo("메모 본문\n둘째 줄");
        user.setMemoFont("d2coding");
        user.setMemoUpdatedAt(LocalDateTime.of(2026, 8, 6, 14, 15));
        user.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        user.setUpdatedAt(LocalDateTime.of(2026, 8, 6, 14, 15));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("user", user);
        m.put("memoAutosave", true);
        m.put("accountLayout", "stack");
        m.put("isAdmin", false);
        // 비밀번호 만료 정책 비활성 상태
        m.put("pwExpiryEnabled", false);
        m.put("pwExpired", false);
        m.put("pwWarnSoon", false);
        m.put("pwDaysLeft", 0);
        m.put("pwExpiryText", "");
        return m;
    }

    @Test
    @DisplayName("/account 가 렌더된다 (백업 조회·되돌리기 배너 포함)")
    void accountRenders() {
        String html = render("account", memoModel());

        assertTrue(html.contains("저장되지 못한 메모가 남아 있습니다"), "백업 안내 배너가 없다");
        assertTrue(html.contains("내용 보기"), "백업 조회 버튼이 없다");
        assertTrue(html.contains("id=\"undoAlert\""), "되돌리기 배너가 없다");
        assertTrue(html.contains("복구 이전으로 되돌리기"));
        assertTrue(html.contains("id=\"restoreSize\""), "백업 크기 표기 자리가 없다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:replace"), "미처리 th:replace 가 남았다");
    }

    @Test
    @DisplayName("레이아웃은 서버가 <html> 클래스로 렌더한다 (FOUC 방지 경로)")
    void accountLayoutIsServerRendered() {
        // ⚠ head 전체를 보면 안 된다 — <style> 안에 `html.acct-split ...` 규칙이 있어 항상 매칭된다.
        //    반드시 <html> 여는 태그만 떼어내 검사할 것.
        String stack = htmlTag(render("account", memoModel()));
        assertFalse(stack.contains("acct-split"), "stack 인데 split 클래스가 붙었다: " + stack);

        // 저장된 값이 split 이면 <html> 에 클래스가 실려 나온다
        Map<String, Object> model = memoModel();
        model.put("accountLayout", "split");
        String rendered = render("account", model);
        assertTrue(htmlTag(rendered).contains("acct-split"),
                "서버가 클래스를 렌더하지 않으면 새로고침마다 기본 배치가 한 번 그려졌다 튄다: " + htmlTag(rendered));
        String split = rendered;
        // 두 경우 모두 세그먼트 버튼은 항상 존재
        assertTrue(split.contains("data-layout=\"split\"") && split.contains("data-layout=\"stack\""));
    }

    @Test
    @DisplayName("이탈 경고 가드가 배너 스피너에 등록된다 (팝업 '취소' 시 무한 스피너 방지)")
    void unloadGuardIsWiredToBannerSpinner() {
        String html = render("account", memoModel());   // 배너 fragment 가 인라인된 최종 문서

        // 배너 쪽 — 가드 등록 API + 스피너 예약 게이트가 살아 있어야 한다
        assertTrue(html.contains("function registerUnloadGuard"), "배너에 가드 등록 API 가 없다");
        assertTrue(html.contains("if (willPromptUnload()) return;"),
                "스피너를 예약할 때 가드를 확인하지 않는다 — 경고 팝업 취소 시 무한 회전한다");

        // 페이지 쪽 — beforeunload 경고 조건을 같은 함수로 등록해야 둘이 어긋나지 않는다
        assertTrue(html.contains("function memoWillWarnOnLeave"), "경고 조건 함수가 없다");
        assertTrue(html.contains("registerUnloadGuard(memoWillWarnOnLeave)"),
                "경고 조건을 배너에 등록하지 않았다");
    }

    @Test
    @DisplayName("메모장 툴 버튼은 바가 좁아지면 아이콘만 남는다 (라벨 개행 방지)")
    void memoToolLabelsCollapseIntoIconsOnNarrowBar() {
        String html = render("account", memoModel());

        // 라벨은 감출 수 있도록 span 으로 분리돼 있어야 한다 (텍스트 노드는 CSS 로 못 감춘다)
        assertTrue(html.contains("<span class=\"memo-tool-txt\">복사</span>"), "복사 라벨이 span 이 아니다");
        assertTrue(html.contains("<span class=\"memo-tool-txt\">새창에서 열기</span>"), "새창 라벨이 span 이 아니다");
        // 아이콘만 남았을 때 접근성 이름이 사라지지 않도록 aria-label 이 있어야 한다
        assertTrue(html.contains("aria-label=\"복사\""), "복사 버튼에 aria-label 이 없다");
        assertTrue(html.contains("aria-label=\"새창에서 열기\""), "새창 버튼에 aria-label 이 없다");

        // 판정 기준은 뷰포트가 아니라 바(카드) 폭이다 — 좌우 분할에서는 넓은 창에서도 카드가 좁다.
        // 미디어 쿼리로 되돌리면 split 레이아웃(뷰포트 1101~1300px)에서 다시 개행된다.
        assertTrue(html.contains("container-type: inline-size") && html.contains("container-name: memobar"),
                "폰트 바가 컨테이너로 선언되지 않았다 — @container 가 아무 폭에서도 적용되지 않는다");
        int mediaAt = html.indexOf("@media (min-width: 641px)");
        int containerAt = html.indexOf("@container memobar (max-width: 580px)");
        assertTrue(containerAt > 0, "라벨 숨김 컨테이너 쿼리가 없다");
        assertTrue(html.indexOf(".memo-tool-txt { display: none; }", containerAt) > containerAt,
                "컨테이너 쿼리 안에서 라벨을 감추지 않는다");
        // ≤640px 모바일 분기는 버튼이 전폭 50:50 이라 라벨이 그대로 들어간다 — 숨김 규칙이 거기까지 내려가면 안 된다
        assertTrue(mediaAt > 0 && mediaAt < containerAt,
                "라벨 숨김이 min-width:641px 밖에 있다 — 모바일 전폭 버튼까지 아이콘만 남는다");
    }

    @Test
    @DisplayName("폰트 미리보기(한글 ABC 123)는 마크업·CSS·JS 어디에도 남지 않는다")
    void fontPreviewIsFullyRemoved() {
        String html = render("account", memoModel());

        assertFalse(html.contains("한글 ABC 123"), "미리보기 문구가 남아 있다");
        assertFalse(html.contains("memoFontPreview"), "미리보기 엘리먼트/JS 참조가 남아 있다");
        assertFalse(html.contains("memo-font-preview"), "미리보기 CSS 클래스가 남아 있다");
        // 폰트 선택 자체는 그대로 동작해야 한다 (미리보기만 제거)
        assertTrue(html.contains("id=\"memoFontSelect\"") && html.contains("applyMemoFont"),
                "폰트 선택 기능까지 지워졌다");
    }

    @Test
    @DisplayName("/account/memo 새창 페이지가 렌더된다 (자체 CSRF meta 포함)")
    void accountMemoRenders() {
        String html = render("account-memo", memoModel());

        assertTrue(html.contains("저장되지 못한 메모가 남아 있습니다"));
        assertTrue(html.contains("내용 보기"));
        assertTrue(html.contains("id=\"undoAlert\""));
        // 팝업은 opener 의 meta 를 빌릴 수 없다 — 자체적으로 있어야 저장이 403 나지 않는다(함정 26)
        assertTrue(html.contains("test-token"), "자체 CSRF meta 가 렌더되지 않았다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
    }
}

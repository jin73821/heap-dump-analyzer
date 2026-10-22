package com.heapdump.analyzer;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC 로그 목록의 <b>서랍</b>(대표 행 + 접힌 구성 파일) 렌더 계약 (2026-09-21).
 *
 * <p>화면을 봐야만 드러나는 결함을 막는다 — ① 구성 파일 행의 칸 수가 헤더와 다르면 표가 통째로 어긋난다
 * ② 구성 파일에 {@code .row-check} 가 있으면 대표와 함께 선택돼 이어붙이기가 거부된다 ③ {@code select-mode.js}
 * 의 고정 id 가 하나라도 빠지면 선택 모드가 첫 클릭에서 죽는다(함정 65) ④ 구성 파일 행이 대표 바로 뒤에
 * 오지 않으면 서랍 토글이 엉뚱한 행을 여닫는다.
 *
 * <p>⚠ 값을 채운 모델로 렌더해 숫자를 단언한다 — 빈 모델 스모크만으로는 {@code th:if} 안 표현식이 조용히
 * null 이 되는 부류를 못 잡는다(함정 59).
 */
class GcLogDrawerTemplateSmokeTest {

    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
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
        ctx.setVariable("_csrf", new CsrfStub());
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    private static GcLogAnalysisEntity row(String fn, String status) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename(fn);
        e.setStatus(status);
        e.setServerName("was01");
        e.setInstanceName("c1");
        e.setFileSize(4096L);
        e.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 5));
        e.setMatchSource(GcLogAnalysisEntity.MATCH_NONE);
        return e;
    }

    /** 대표 1 + 구성 2 짜리 묶음이 든 목록. */
    private static Map<String, Object> groupedModel() {
        GcLogAnalysisEntity rep = row("gc.log", GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        rep.setMemberCount(3);
        rep.setMembersBytes(12288L);
        rep.setAnalysisName("was01_c1_20260919");
        GcLogAnalysisEntity m1 = row("gc.log.0", GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        m1.setPartOf("gc.log");
        GcLogAnalysisEntity m2 = row("gc.log.1", GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        m2.setPartOf("gc.log");
        GcLogAnalysisEntity solo = row("other.log", GcLogAnalysisEntity.STATUS_SUCCESS);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("gcLogFiles", List.of());
        model.put("history", List.of(rep, m1, m2, solo));
        model.put("memberRows", Map.of("gc.log", List.of(m1, m2)));
        model.put("isAdmin", true);
        return model;
    }

    private static String tbody(String html) {
        int a = html.indexOf("<tbody id=\"hiTbody\">");
        int b = html.indexOf("</tbody>", a);
        assertTrue(a > 0 && b > a, "이력 표 tbody 를 찾지 못했다");
        return html.substring(a, b);
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("구성 파일은 대표 행 바로 뒤에 접힌 채로 렌더된다")
    void membersFollowRepresentativeAndStartCollapsed() {
        String body = tbody(render("gc-log/index", groupedModel()));

        int rep = body.indexOf("data-filename=\"gc.log\"");
        int m1 = body.indexOf("data-filename=\"gc.log.0\"");
        int m2 = body.indexOf("data-filename=\"gc.log.1\"");
        int solo = body.indexOf("data-filename=\"other.log\"");
        assertTrue(rep > 0 && m1 > rep && m2 > m1 && solo > m2,
                "구성 파일이 대표 바로 뒤에 오지 않았다 — 서랍 토글이 엉뚱한 행을 여닫는다");

        assertEquals(2, count(body, "class=\"hi-subrow\""), "구성 파일 행 수가 다르다");
        assertEquals(2, count(body, "style=\"display:none\""), "구성 파일이 펼쳐진 채로 렌더됐다 — 기본은 접힘이다");

        // 대표가 중복 렌더되지 않는다(구성 목록에 자기 자신이 들어 있어도)
        assertEquals(1, count(body, "data-filename=\"gc.log\" "), "대표 행이 두 번 렌더됐다");
    }

    @Test
    @DisplayName("구성이 있는 대표에만 ▸ 토글이 붙고 기본은 접힘 상태다")
    void toggleOnlyOnCombinedRows() {
        String body = tbody(render("gc-log/index", groupedModel()));

        assertEquals(1, count(body, "class=\"hi-group-toggle\""), "토글이 구성 없는 행에도 붙었다");
        assertTrue(body.contains("aria-expanded=\"false\""), "aria-expanded 초기값이 없다");
        assertTrue(body.contains("glToggleGroup(event, this)"), "토글 핸들러 배선이 없다");
        assertTrue(body.contains("data-members=\"3\""), "토글이 구성 개수를 들고 있지 않다(접근성 라벨용)");
        // ⚠ 장식용 chevron 은 코어덤프 목록과 CSS 를 공유한다 — 서랍 토글로 개조하면 안 된다
        assertFalse(body.contains("hi-row-chevron\" aria-expanded"), ".hi-row-chevron 을 토글로 개조했다");
    }

    @Test
    @DisplayName("구성 파일 행의 칸 수가 헤더와 같다 — 하나만 어긋나도 표가 통째로 밀린다")
    void memberRowHasSameColumnCount() {
        String html = render("gc-log/index", groupedModel());
        // ⚠ "<th" 로 세면 <thead> 자신이 함께 잡힌다 — 여는 태그 두 형태만 센다
        String head = html.substring(html.indexOf("<thead>"), html.indexOf("</thead>"));
        int th = count(head, "<th>") + count(head, "<th ");

        String body = tbody(html);
        int start = body.indexOf("class=\"hi-subrow\"");
        int end = body.indexOf("</tr>", start);
        String memberRow = body.substring(start, end);
        assertEquals(th, count(memberRow, "<td"), "구성 파일 행의 칸 수가 헤더(" + th + ")와 다르다");
    }

    @Test
    @DisplayName("구성 파일에는 선택 체크박스를 두지 않는다 — 대표와 함께 선택되면 이어붙이기가 거부된다")
    void memberRowsHaveNoRowCheck() {
        String body = tbody(render("gc-log/index", groupedModel()));
        int start = body.indexOf("class=\"hi-subrow\"");
        int end = body.indexOf("</tr>", start);
        assertFalse(body.substring(start, end).contains("row-check"),
                "구성 파일 행에 .row-check 가 있다 — select-mode 가 대표와 함께 집어 간다");
        // 대표 행에는 있어야 한다
        assertTrue(body.substring(0, start).contains("class=\"row-check\""), "대표 행의 체크박스가 사라졌다");
    }

    @Test
    @DisplayName("select-mode.js 고정 id 6종이 모두 있다 (함정 65 — 하나만 빠져도 첫 클릭에서 죽는다)")
    void selectModeContractIdsExist() {
        String html = render("gc-log/index", groupedModel());
        for (String id : List.of("selectModeBtn", "selectModeBtnLabel", "selectActionBar", "saCount", "saDelBtn", "allCheck")) {
            assertTrue(html.contains("id=\"" + id + "\""), "select-mode.js 계약 id 누락: " + id);
        }
    }

    @Test
    @DisplayName("묶음 해제·그룹에서 빼기 버튼이 배선돼 있다")
    void ungroupAndDetachAreWired() {
        String body = tbody(render("gc-log/index", groupedModel()));
        assertTrue(body.contains("glUngroup(this.dataset.filename)"), "대표 행에 묶음 해제가 없다");
        assertTrue(body.contains("glDetachMember(this.dataset.rep, this.dataset.filename)"), "구성 파일에 빼기가 없다");
        assertTrue(body.contains("data-rep=\"gc.log\""), "빼기 버튼이 대표를 모른다");
    }

    @Test
    @DisplayName("업로드 후 흐름은 자동 묶기로 간다 — 옛 '이어 붙일까요' 제안은 없다")
    void uploadFinishGoesToAutoGroup() throws IOException {
        String js = Files.readString(Path.of("src/main/resources/static/js/gc-log-index.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("/api/gc-log/group/auto"), "업로드 뒤 자동 판정 호출이 없다");
        assertFalse(js.contains("offerCombine("), "옛 제안 모달 경로가 남아 있다");
        assertTrue(js.contains("combine/link"), "묶기 전용 엔드포인트 호출이 없다");

        String html = render("gc-log/index", groupedModel());
        assertTrue(html.contains("id=\"groupResultModal\""), "자동 묶기 결과 모달이 없다");
        // 모달 골격 4종은 페이지가 갖고 있어야 한다(함정 17)
        assertTrue(html.contains("id=\"groupResultBody\"") && html.contains("modal-btns"), "모달 골격이 빠졌다");
    }

    // ── 2026-10-22: 좌측 파일 패널 서랍 · 액션 '⋯' 말풍선 · 상태 배지 ─────────────

    /** 사이드 패널까지 채운 모델 — 컨트롤러가 현존 파일 기준으로 sideMembers/sideClaimed 를 준다. */
    private static Map<String, Object> sideModel() {
        Map<String, Object> model = groupedModel();
        @SuppressWarnings("unchecked")
        List<GcLogAnalysisEntity> history = (List<GcLogAnalysisEntity>) model.get("history");
        model.put("gcLogFiles", history);
        model.put("sideMembers", Map.of("gc.log", List.of(history.get(1), history.get(2))));
        model.put("sideClaimed", java.util.Set.of("gc.log.0", "gc.log.1"));
        return model;
    }

    private static String fileList(String html) {
        int a = html.indexOf("id=\"glFileList\"");
        int b = html.indexOf("id=\"glFileNoResult\"", a);
        assertTrue(a > 0 && b > a, "좌측 파일 목록을 찾지 못했다");
        return html.substring(a, b);
    }

    @Test
    @DisplayName("좌측 패널: 구성 파일은 대표 카드 아래 접힌 서랍에만 있고, 건수는 최상위 단위로 센다")
    void sidebarGroupsMembersIntoDrawer() {
        String html = render("gc-log/index", sideModel());
        String list = fileList(html);

        assertEquals(2, count(list, "class=\"gl-unit"), "최상위 단위는 묶음 1 + 단독 1 이어야 한다");
        assertEquals(1, count(list, "class=\"gl-unit gl-group\""), "묶음 단위 표시가 없다");
        assertEquals(2, count(list, "class=\"cd-file-item\""), "구성 파일이 최상위 카드로도 렌더됐다");
        assertEquals(2, count(list, "class=\"gl-member\""), "서랍 안 구성 파일 수가 다르다");
        assertTrue(list.contains("<div class=\"gl-members\" hidden>"), "서랍이 펼쳐진 채로 렌더됐다 — 기본은 접힘이다");
        assertTrue(list.contains("glToggleSideGroup(this)") && list.contains("aria-expanded=\"false\""), "서랍 손잡이 배선");
        // 손잡이는 카드(role=button) 밖 형제여야 한다 — 버튼 안의 버튼은 키보드·낭독기에서 깨진다
        int card = list.indexOf("class=\"cd-file-item\"");
        int cardEnd = list.indexOf("<button type=\"button\" class=\"gl-group-toggle\"", card);
        assertTrue(cardEnd > card, "서랍 손잡이를 찾지 못했다");
        assertFalse(list.substring(card, cardEnd).contains("<button"), "손잡이가 카드 안에 들어갔다");
        assertTrue(html.contains("id=\"glFileCount\">2<"), "건수가 서랍 안 구성 파일까지 셌다");
    }

    @Test
    @DisplayName("좌측 패널: sideMembers 가 없는 모델(옛 컨트롤러·빈 묶음)은 모든 파일을 평범한 카드로 그린다")
    void sidebarWithoutDrawerModelStaysFlat() {
        Map<String, Object> model = groupedModel();
        model.put("gcLogFiles", model.get("history"));
        String list = fileList(render("gc-log/index", model));
        assertEquals(4, count(list, "class=\"cd-file-item\""));
        assertEquals(0, count(list, "gl-group-toggle"));
    }

    @Test
    @DisplayName("액션 열: 결과 보기 외 동작은 행마다 ⋯ 하나 뒤의 <template> 메뉴에만 있다")
    void actionsCollapseIntoMoreMenu() {
        Map<String, Object> model = groupedModel();
        model.put("claimed", java.util.Set.of("gc.log.0", "gc.log.1"));   // 컨트롤러처럼 구성 파일은 서랍에만
        String body = tbody(render("gc-log/index", model));
        String outside = body.replaceAll("(?s)<template class=\"hi-more-tpl\">.*?</template>", "");

        // 대표 2(gc.log·other.log) + 구성 파일 2 = 4행, 행마다 ⋯ 하나 · 메뉴 템플릿 하나
        assertEquals(4, count(body, "hi-more-btn\""), "행마다 ⋯ 버튼이 하나여야 한다");
        assertEquals(4, count(body, "<template class=\"hi-more-tpl\">"), "행마다 메뉴 템플릿이 하나여야 한다");
        assertTrue(outside.contains("glOpenRowMenu(event, this)") && outside.contains("aria-haspopup=\"menu\""), "⋯ 배선");

        for (String act : List.of("deleteGcLog(", "glUngroup(", "glDetachMember(", "AnalyzeConfirm.fromLink(", "/api/gc-log/download/")) {
            assertFalse(outside.contains(act), "메뉴 밖에 버튼이 남았다: " + act);
            assertTrue(body.contains(act), "메뉴에서 동작이 빠졌다: " + act);
        }
        // 결과 보기(SUCCESS 단독 행)는 메뉴 밖에 그대로 — 결과 보기만 남기는 것이 요구다
        assertEquals(1, count(outside, ">결과 보기</a>"), "결과 보기가 메뉴 안으로 들어갔거나 사라졌다");
    }

    @Test
    @DisplayName("CSS·JS 계약 — 화면을 봐야만 드러나는 부류(배지 구분·서랍 납작해짐·hidden 무력화·말풍선 잘림)")
    void drawerMenuAndBadgeStylesAreWired() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/gc-log.css"), StandardCharsets.UTF_8);
        // 미분석은 속이 빈 외곽선 배지 — 완료(녹색 단색)와 명도부터 다르다
        assertTrue(css.contains(".status-badge.status-not-analyzed { background: #fff;"), "미분석 배지가 외곽선형이 아니다");
        assertTrue(css.contains(".status-badge.status-success::before"), "완료 배지 체크 표시가 없다");
        // overflow:hidden 인 묶음은 높이 제한 flex 열에서 납작해진다(모바일 380px 패널 실측)
        assertTrue(css.contains(".gl-unit { flex: none; }"), "묶음 단위가 flex 열에서 줄어들 수 있다");
        // display 를 주는 규칙이 hidden 속성을 덮으면 서랍이 늘 펼쳐진다
        assertTrue(css.contains(".gl-members[hidden] { display: none; }"), "서랍 hidden 이 CSS 에 덮인다");
        // 말풍선은 body 에 붙는 fixed — 표(overflow-x:auto) 안에 두면 잘린다
        assertTrue(css.contains(".hi-menu { position: fixed;"), "말풍선이 fixed 가 아니다");

        String js = Files.readString(Path.of("src/main/resources/static/js/gc-log-index.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("tpl.content.cloneNode(true)") && js.contains("document.body.appendChild(el)"), "메뉴가 body 에 복제되지 않는다");
        assertTrue(js.contains("closeRowMenu(false);   // 행이 옮겨지거나"), "표를 다시 그릴 때 말풍선을 닫지 않는다");
        assertTrue(js.contains("'gcLogSideOpenGroups'"), "좌측 서랍 펼침 상태 키가 없다");
        assertTrue(js.contains("window.glOpenRowMenu = glOpenRowMenu") && js.contains("window.glToggleSideGroup = glToggleSideGroup"),
                "인라인 핸들러용 전역 노출이 빠졌다");
    }

    @Test
    @DisplayName("'모든 그룹 펼치기' 토글은 id·localStorage 키를 그대로 쓴다")
    void showMembersToggleKeepsItsContract() throws IOException {
        String html = render("gc-log/index", groupedModel());
        assertTrue(html.contains("id=\"hiShowMembers\"") && html.contains("onShowMembersChange()"));
        assertTrue(html.contains("모든 그룹 펼치기"), "토글 라벨이 옛 의미('구성 파일 포함')로 남아 있다");

        String js = Files.readString(Path.of("src/main/resources/static/js/gc-log-index.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("'gcLogShowMembers'"), "기존 저장 키가 바뀌었다");
        assertTrue(js.contains("'gcLogOpenGroups'"), "개별 펼침 상태 키가 없다");
    }
}

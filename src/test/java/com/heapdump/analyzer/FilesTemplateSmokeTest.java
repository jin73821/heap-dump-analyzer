package com.heapdump.analyzer;

import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files(Analysis Files) 페이지 렌더 스모크 (2026-09-17) — 목록 로딩 스피너 배선을 고정한다.
 *
 * <p>행은 서버 렌더지만 정렬·필터·페이지네이션은 {@code TableGrid} 가 하므로, 그 전까지
 * {@code #fileTableBody.tb-loading tr { display:none }} 가 행을 전부 감춘다. 종전에는 그 구간이
 * 빈 표로 보였다 — 스피너가 그 자리를 채운다.
 *
 * <p>고정하는 계약 2가지:
 * <ul>
 *   <li>스피너 행은 {@code #fileTableBody} 와 <b>별도 tbody</b> 에 있어야 한다. 같은 tbody 에 넣으면
 *       {@code initRows()} 의 {@code querySelectorAll('#fileTableBody tr')} 가 이 행을 데이터 행으로
 *       주워 담아 순번·페이지 건수가 어긋난다.</li>
 *   <li>스피너를 내리는 지점은 행을 드러내는 지점({@code tb-loading} 해제)과 <b>같은 콜백</b>이어야 한다.
 *       갈라 두면 렌더가 끝난 뒤에도 스피너가 남아 "아직 로딩 중"으로 읽힌다(rag-settings 색인
 *       스피너와 같은 계열의 결함).</li>
 * </ul>
 */
class FilesTemplateSmokeTest {

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

    private static Map<String, Object> emptyModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysisHistory", Collections.emptyList());
        m.put("execFilenames", Collections.emptyList());
        m.put("coreExecSubrowMap", Collections.emptyMap());
        m.put("serverNames", Collections.emptyList());
        m.put("isAdmin", true);
        m.put("fileCount", 0);
        m.put("coreDumpCount", 0L);
        m.put("analyzedCount", 0L);
        m.put("totalSize", "0 B");
        m.put("diskSize", "0 B");
        m.put("hasCompressed", false);
        m.put("maxUploadSizeBytes", 5368709120L);
        m.put("maxUploadSizeGb", 5L);
        m.put("allowAllExtensions", false);
        return m;
    }

    /** GC 로그 묶음(대표 1 + 구성 2)이 든 목록 — 서랍 렌더 계약 확인용. */
    private static Map<String, Object> gcGroupModel() {
        AnalysisHistoryItem rep = gc("gc.log.0", null, 5);
        AnalysisHistoryItem m1 = gc("gc.log.3", "gc.log.0", null);
        AnalysisHistoryItem m2 = gc("gc.log.4", "gc.log.0", null);
        AnalysisHistoryItem solo = gc("other.log", null, null);
        Map<String, Object> m = emptyModel();
        m.put("analysisHistory", List.of(rep, m1, m2, solo));
        return m;
    }

    private static AnalysisHistoryItem gc(String fn, String partOf, Integer members) {
        AnalysisHistoryItem i = new AnalysisHistoryItem();
        i.setFileType("gclog");
        i.setFilename(fn);
        i.setStatus("NOT_ANALYZED");
        i.setFormattedSize("48 KB");
        i.setFormattedDate("2026-09-21 16:45");
        i.setLastModified(1_758_000_000_000L);
        i.setServerName("guacmg1t");
        i.setPartOf(partOf);
        i.setMemberCount(members);
        return i;
    }

    @Test
    @DisplayName("★ GC 로그 묶음은 Files 목록에서도 서랍으로 보인다 — 구성 파일은 사라지지 않는다")
    void gcLogGroupRendersAsDrawer() {
        String h = render("files", gcGroupModel());

        // 구성 파일은 목록에서 빠지지 않는다(파일 관리 화면이므로 어떤 파일도 사라지면 안 된다)
        assertTrue(h.contains("data-name=\"gc.log.3\""), "구성 파일 행이 통째로 빠졌다");
        assertTrue(h.contains("data-name=\"gc.log.4\""), "구성 파일 행이 통째로 빠졌다");
        // 부모를 가리키는 표시가 있어야 JS 가 서랍으로 접는다
        assertTrue(h.contains("data-parent-group=\"gc.log.0\""), "구성 파일이 부모를 모른다 — 서랍이 만들어지지 않는다");
        assertTrue(h.contains("gclog-subrow"), "구성 파일 행에 서브행 클래스가 없다");
        // 대표에는 토글과 묶음 배지
        assertTrue(h.contains("filesToggleGroup(event, this)"), "대표 행에 펼침 토글이 없다");
        assertTrue(h.contains("aria-expanded=\"false\""), "토글 초기 상태(접힘)가 없다");
        assertTrue(h.contains("5개 이어붙임"), "묶음 배지가 없다");
        assertTrue(h.contains("&rarr; gc.log.0") || h.contains("→ gc.log.0"), "구성 파일에 대표 표시가 없다");
        // 묶음이 아닌 GC 로그에는 토글이 붙지 않는다
        assertEquals(1, h.split("onclick=\"filesToggleGroup", -1).length - 1, "토글이 묶음 아닌 행에도 붙었다");
    }

    @Test
    @DisplayName("서랍 배선: 부모당 하위 행 배열 · 고아 행 복구 · 접을 때 선택 해제")
    void drawerWiringContracts() {
        String h = render("files", gcGroupModel());
        assertTrue(h.contains("function parentOfRow"), "부모 판정이 한 곳에 모이지 않았다");
        assertTrue(h.contains("_subrowMap[parent] = _subrowMap[parent] || []"), "하위 행이 부모당 1개로 덮어써진다");
        // ⚠ 부모가 목록에 없는 하위 행을 최상위로 되돌리지 않으면 파일이 조용히 사라진다
        assertTrue(h.contains("부모가 목록에 없는 하위 행은 최상위로 되돌린다"), "고아 행 복구가 없다");
        assertTrue(h.contains("filesOpenGroups"), "펼침 상태 저장 키가 없다");
        assertTrue(h.contains("if (_sel && _sel.active) _sel.updateCount();"), "접을 때 선택 카운트를 갱신하지 않는다");
        // gc-log.css 는 이 페이지에 없으므로 토글 CSS 를 페이지가 갖고 있어야 한다(함정 17 계열)
        assertTrue(h.contains(".hi-group-toggle[aria-expanded=\"true\"]"), "토글 회전 CSS 가 없다 — 민짜 버튼이 된다");
        assertTrue(h.contains(".gclog-subrow {"), "서브행 CSS 가 없다");
    }

    @Test
    @DisplayName("files: 끝까지 렌더되고 목록 로딩 스피너가 별도 tbody 로 들어간다")
    void filesRendersWithLoadingSpinner() {
        String h = render("files", emptyModel());

        assertTrue(h.trim().endsWith("</html>"), "렌더 미완 — 템플릿 파싱 실패 가능(함정 23)");
        assertFalse(h.contains("[["), "인라인 표현식으로 해석될 '[[' 가 남아 있다");

        // 스피너 자체
        assertTrue(h.contains("id=\"fileLoadingBody\""), "로딩 tbody 부재");
        assertTrue(h.contains("class=\"tb-spinner\""), "스피너 엘리먼트 부재");
        assertTrue(h.contains("파일 목록을 불러오는 중"), "로딩 문구 부재");
        assertTrue(h.contains("@keyframes tb-spin"), "스피너 회전 애니메이션 부재 — common.css 에는 없다");

        // ⚠ 별도 tbody 계약: 로딩 행이 #fileTableBody 앞에서 닫혀야 한다.
        int loadOpen  = h.indexOf("id=\"fileLoadingBody\"");
        int loadClose = h.indexOf("</tbody>", loadOpen);
        int gridOpen  = h.indexOf("id=\"fileTableBody\"");
        assertTrue(loadOpen > 0 && gridOpen > 0, "두 tbody 가 모두 있어야 한다");
        assertTrue(loadClose > 0 && loadClose < gridOpen,
                "로딩 행이 #fileTableBody 안에 있다 — initRows 가 데이터 행으로 주워 담는다");
    }

    @Test
    @DisplayName("files: 스피너 해제가 행을 드러내는 콜백(onRenderStart)과 같은 지점에 배선돼 있다")
    void spinnerIsClearedBySameSignalThatRevealsRows() {
        String h = render("files", emptyModel());

        int cb = h.indexOf("onRenderStart: function()");
        assertTrue(cb > 0, "TableGrid onRenderStart 콜백 부재");
        String body = h.substring(cb, Math.min(h.length(), cb + 400));

        assertTrue(body.contains("classList.remove('tb-loading')"), "행 표시 해제가 사라졌다");
        assertTrue(body.contains("fileLoadingBody"),
                "스피너 해제가 onRenderStart 밖으로 나갔다 — 두 신호가 갈리면 스피너가 영원히 남는다");

        // 행을 숨기는 규칙은 그대로 살아 있어야 한다(초기 flash 방지).
        assertTrue(h.contains("#fileTableBody.tb-loading tr { display: none; }"), "초기 행 숨김 규칙 부재");
    }
}

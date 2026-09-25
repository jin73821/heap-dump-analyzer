package com.heapdump.analyzer;

import com.heapdump.analyzer.model.dto.AlertBoardRow;
import com.heapdump.analyzer.model.dto.AlertDomainKpi;
import com.heapdump.analyzer.model.dto.AlertItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 현황판(/alerts) 렌더 스모크 + 알림 벨 배선.
 * 템플릿 파싱 오류는 chunked 응답이 중간에 끊겨 빈 화면이 되므로(함정 23) 실제 엔진으로 렌더해 본다.
 */
class AlertBoardTemplateSmokeTest {

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

    private static String resource(String path) throws IOException {
        try (InputStream in = AlertBoardTemplateSmokeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " 를 클래스패스에서 찾을 수 없다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** prefix 로 시작하는 여는 태그 중 attr 을 가진 첫 태그 전체. */
    private static String tagWith(String html, String prefix, String attr) {
        for (int i = html.indexOf(prefix); i >= 0; i = html.indexOf(prefix, i + 1)) {
            String tag = html.substring(i, html.indexOf('>', i) + 1);
            if (tag.contains(attr)) return tag;
        }
        throw new AssertionError(prefix + " … " + attr + " 태그가 없다");
    }

    private static Map<String, Object> baseModel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("isAdmin", true);
        return m;
    }

    private static AlertItem alert(String key, String domain, boolean acked, boolean deleted) {
        AlertItem a = new AlertItem();
        a.setKey(key); a.setDomain(domain); a.setFilename(key.split(":")[1]); a.setSeverity("Critical");
        a.setTitle("OOM 탐지 — 힙 공간 부족 (스레드 2개)"); a.setDetail("상세"); a.setOccurredAtLabel("2026-09-19 10:00");
        a.setFileDeleted(deleted); a.setLink(deleted ? null : "/analyze/result/" + a.getFilename());
        a.setAcked(acked);
        if (acked) { a.setAckedBy("admin"); a.setAckedAtLabel("2026-09-19 11:00"); }
        return a;
    }

    @Test
    @DisplayName("/alerts — 모델이 비어 있어도, 채워져 있어도 끝까지 렌더된다")
    void boardRenders() {
        String empty = render("alerts", baseModel());
        assertTrue(empty.trim().endsWith("</html>"), "빈 모델에서 렌더가 끊겼다");
        assertTrue(empty.contains("분석 기록이 없습니다."));

        Map<String, Object> m = baseModel();
        AlertDomainKpi k = new AlertDomainKpi();
        k.setTotal(5); k.setSuccess(3); k.setError(1); k.setNotAnalyzed(1); k.setCritical(2);
        m.put("kpi", Map.of("heap", k, "core", new AlertDomainKpi(), "gclog", new AlertDomainKpi()));
        AlertBoardRow row = new AlertBoardRow();
        row.setDomain("heap"); row.setFilename("a.hprof"); row.setStatus("SUCCESS"); row.setSeverity("Critical");
        row.setKeySignal("OOM · 힙 공간 부족"); row.setAnalyzedAtLabel("2026-09-19 10:00"); row.setLink("/analyze/result/a.hprof");
        AlertBoardRow pending = new AlertBoardRow();
        pending.setDomain("heap"); pending.setFilename("b.hprof"); pending.setStatus("NOT_ANALYZED"); pending.setAnalyzedAtLabel("—");
        m.put("rows", Map.of("heap", List.of(row, pending), "core", List.of(), "gclog", List.of()));
        m.put("alerts", List.of(alert("heap:a.hprof:1", "heap", false, false), alert("core:core.9:2", "core", true, true)));
        m.put("unackedCount", 1);

        String html = render("alerts", m);
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:each") || html.contains("th:text"), "미처리 th: 속성이 남았다");
        assertTrue(html.contains("data-key=\"heap:a.hprof:1\"") && html.contains("data-acked=\"false\""), "알람 행의 data-* 계약");
        assertTrue(html.contains("ACK: admin · 2026-09-19 11:00"), "확인자·시각 표기가 없다");
        assertTrue(html.contains(">확인 취소<") && html.contains(">확인<"), "ACK 버튼 라벨");
        assertTrue(html.contains("미확인 1"), "미확인 건수");
        assertTrue(html.contains("href=\"/analyze/result/a.hprof\""), "결과 링크");
        assertTrue(html.contains("sev-Critical") && html.contains("st-NOT_ANALYZED") && html.contains("sev-none"));
        assertTrue(html.contains("<b>5</b>"), "KPI 총계");
        // 삭제된 파일의 알람은 링크가 없다
        String deleted = html.substring(html.indexOf("data-key=\"core:core.9:2\""));
        deleted = deleted.substring(0, deleted.indexOf("al-side"));
        assertFalse(deleted.contains("<a "), "삭제된 파일 알람에 링크가 있다");
        assertTrue(deleted.contains(">deleted<"));
        // 배너에 현황판 메뉴 (펼침·접힘 둘 다)
        assertTrue(html.contains("href=\"/alerts\" class=\"gb-nav-link\" id=\"gbNavAlerts\""), "배너 메뉴가 없다");
        assertTrue(html.contains("href=\"/alerts\" class=\"gb-ci\""), "배너 접힘 아이콘이 없다");

        // 부분 갱신 조각(2026-09-26) — id = th:fragment. 토글·모달은 조각 밖에 있어야 상태가 보존된다
        assertTrue(html.contains("id=\"alertKpi\""), "KPI 조각 id");
        assertTrue(html.contains("id=\"alertList\""), "알람 목록 조각 id");
        assertTrue(html.contains("data-unacked=\"1\"") && html.contains("data-deleted-count=\"0\""), "조각 밖 카운터를 맞출 data-* 가 없다");
        assertTrue(html.contains("id=\"alertRows\""), "최근 분석 조각 id");
        assertTrue(html.indexOf("id=\"rowTabs\"") < html.indexOf("id=\"alertRows\""), "탭이 조각 안에 들어갔다(SegThumb 상태 소실)");
        assertTrue(html.contains("/js/live-refresh.js"), "부분 갱신 모듈이 실리지 않는다");
        assertTrue(html.contains("url: '/api/alerts/fragments'"), "조각 엔드포인트는 /api/ 접두여야 한다(만료 시 401 JSON)");
        assertFalse(html.contains("<a href=\"/alerts\" class=\"topbar-btn\">Refresh</a>"), "Refresh 가 전체 새로고침 링크로 남아 있다");
    }

    @Test
    @DisplayName("삭제된 파일 표시 토글은 관리자에게만, 삭제된 행·알람은 서버 렌더부터 숨긴 채 나간다")
    void deletedToggleIsAdminOnly() {
        Map<String, Object> m = baseModel();
        AlertBoardRow live = new AlertBoardRow();
        live.setDomain("heap"); live.setFilename("live.hprof"); live.setStatus("SUCCESS"); live.setAnalyzedAtLabel("—");
        AlertBoardRow gone = new AlertBoardRow();
        gone.setDomain("heap"); gone.setFilename("heapApp_20260622.tar.gz"); gone.setStatus("ERROR");
        gone.setFileDeleted(true); gone.setAnalyzedAtLabel("2026-06-25 23:26");
        m.put("rows", Map.of("heap", List.of(live, gone), "core", List.of(), "gclog", List.of()));
        m.put("alerts", List.of(alert("heap:old.hprof:4", "heap", false, true)));
        m.put("deletedCount", 2);

        String admin = render("alerts", m);
        assertTrue(admin.contains("id=\"showDeletedChk\""), "관리자에게 토글이 없다");
        assertTrue(admin.contains("삭제된 파일 표시") && admin.contains("(2)"), "토글 라벨·건수");
        // 속성 표기·순서(hidden / hidden="hidden")에 기대지 않고 태그 단위로 본다
        assertTrue(tagWith(admin, "<tr", "data-deleted=\"true\"").contains("hidden"), "삭제된 파일 행이 처음부터 숨겨져 있지 않다(JS 전에 번쩍인다)");
        assertFalse(tagWith(admin, "<tr", "data-deleted=\"false\"").contains("hidden"), "현존 파일 행이 숨겨졌다");
        assertTrue(tagWith(admin, "<div class=\"al-item\"", "data-key=\"heap:old.hprof:4\"").contains("hidden"), "삭제된 파일 알람도 기본 숨김");
        assertTrue(admin.contains("data-rows-empty"), "삭제분만 남은 표의 빈 상태 문구");

        m.put("isAdmin", false);
        String user = render("alerts", m);
        assertFalse(user.contains("id=\"showDeletedChk\""), "비관리자에게 토글이 보인다");
        assertFalse(user.contains("삭제된 파일 표시</span>"), "비관리자에게 토글 라벨이 보인다");
    }

    @Test
    @DisplayName("현황판·벨 어디에도 Thymeleaf 인라인 시퀀스·좌측 색 띠가 없다")
    void noInlineSequenceNoLeftAccent() throws IOException {
        String tpl = resource("/templates/alerts.html");
        String js = resource("/static/js/alert-bell.js");
        assertFalse(tpl.contains("[["), "alerts.html 에 [[ 가 있다 (함정 23)");
        assertFalse(tpl.contains("border-left"), "현황판에 좌측 색 띠");
        assertFalse(js.contains("border-left"), "알림 말풍선에 좌측 색 띠");
        assertFalse(tpl.contains("inset 3px") || js.contains("inset 3px"), "inset 레일");
    }

    @Test
    @DisplayName("알림 벨 배선 — 관리형 폴링·success 확인·capture 단계 바깥 클릭·Esc·현황판 버튼")
    void bellWiring() throws IOException {
        String js = resource("/static/js/alert-bell.js");
        assertTrue(js.contains("SessionTimeout.managedInterval("), "배지 갱신이 managedInterval 을 거치지 않는다 (함정 38)");
        assertFalse(js.contains("setInterval("), "배경 타이머를 직접 만든다 — 유휴 세션 만료를 무력화한다");
        assertTrue(js.contains("d.success !== true"), "응답 본문을 믿기 전에 success 를 확인하지 않는다 (함정 27)");
        assertTrue(js.contains("document.addEventListener('mousedown', pop.onDown, true)"), "바깥 클릭이 capture 단계가 아니다");
        assertTrue(js.contains("ev.key === 'Escape'"), "Esc 닫기");
        assertTrue(js.contains("'/api/alerts/top?limit='") && js.contains("'/api/alerts/ack'"), "API 경로");
        assertTrue(js.contains("현황판 보기"), "현황판 이동 버튼");
        assertTrue(js.contains("aria-expanded") && js.contains("aria-haspopup"), "접근성 속성");
        assertTrue(js.contains(".ab-badge[hidden]{display:none}"), "배지 [hidden] 이 display 에 진다");
    }

    @Test
    @DisplayName("확인(ACK)은 확인 모달을 거친다 — 현황판 개별·전체 확인, 대시보드 말풍선 확인. 확인 취소는 바로")
    void ackRequiresConfirm() throws IOException {
        String tpl = resource("/templates/alerts.html");
        assertTrue(tpl.contains("id=\"ackConfirmModal\""), "현황판 확인 모달이 없다");
        // ack API 는 확인 모달의 run 안에서만 부른다 — 목록 클릭에서 바로 부르면 한 번 누름으로 전 사용자 알림이 사라진다
        assertEquals(1, count(tpl, "'/api/alerts/ack',"), "개별 ack 호출은 한 곳뿐이어야 한다");
        int ackCall = tpl.indexOf("'/api/alerts/ack',");
        int openCf = tpl.lastIndexOf("openAckConfirm({", ackCall);
        assertTrue(openCf > 0 && tpl.indexOf("run: function ()", openCf) < ackCall, "개별 확인이 모달을 거치지 않는다");
        int allCall = tpl.indexOf("'/api/alerts/ack-all'");
        assertTrue(tpl.lastIndexOf("openAckConfirm({", allCall) > tpl.indexOf("window.openAckAll = function"), "전체 확인이 모달을 거치지 않는다");
        assertTrue(tpl.contains("cfCancel.focus();"), "기본 포커스가 취소가 아니다(Enter 한 번으로 처리된다)");
        assertTrue(tpl.contains("'/api/alerts/unack'"), "확인 취소 경로");

        String js = resource("/static/js/alert-bell.js");
        assertTrue(js.contains("askAck(b.closest('.ab-item').getAttribute('data-key'), b);"), "말풍선 확인이 확인 창을 거치지 않는다");
        assertEquals(1, count(js, "'/api/alerts/ack'"), "말풍선 ack 호출은 한 곳뿐");
        assertTrue(js.indexOf("'/api/alerts/ack'") > js.indexOf("function ack(key)"), "ack 호출이 확인 뒤 실행 함수 밖에 있다");
        assertTrue(js.contains("role=\"dialog\" aria-modal=\"true\"") && js.contains("cancelBtn.focus();"), "확인 창 접근성·기본 포커스");
        // 확인 창이 떠 있는 동안 Esc·바깥 클릭이 말풍선까지 닫으면 무엇이 취소됐는지 헷갈린다
        assertTrue(js.contains("onKey: function (ev) { if (cf) return;") && js.contains("onDown: function (ev) { if (cf) return;"),
                "확인 창이 떠 있을 때 말풍선이 닫힌다");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    @DisplayName("현황판 확인·확인 취소 연출 — 빠지는 행은 접히고, 되살아난 행은 강조되며, 화면은 서버가 준 확인자·시각을 그린다")
    void boardAckAnimations() throws IOException {
        String tpl = resource("/templates/alerts.html");

        // 확인 직후 페이지를 통째로 새로고침하면 연출이 들어설 자리가 없다(행이 빠지는 모습도 안 보인다).
        // 대신 서버가 돌려준 확인자·시각(d.ack / d.acks)으로 그 행만 고쳐 그린다.
        assertFalse(tpl.contains("location.reload()"), "확인 직후 페이지를 새로고침한다 — 연출이 통째로 잘린다");
        assertTrue(tpl.contains("setCount(d.unackedCount || 0);\n                        applyAck(row, d.ack, applyFilter);"),
                "개별 확인이 응답의 확인 정보로 행을 그리지 않는다");
        assertTrue(tpl.contains("var acks = d.acks || {};"), "전체 확인이 행마다의 확인 정보를 쓰지 않는다");

        // 목록에서 빠질 행만 접는다 — '전체' 보기에서는 자리에 남아 배경·테두리가 옮겨 간다
        assertTrue(tpl.contains("if (filter.ack === 'unacked') {   // 곧 목록에서 빠진다"), "필터에 따른 연출 분기가 없다");
        assertTrue(tpl.contains(".al-item.leaving {") && tpl.contains("height: 0 !important;"),
                "빠지는 행이 접히지 않는다(높이 인라인 값을 이기려면 !important 가 필요하다)");
        assertTrue(tpl.contains("Common.afterMotion(row, 'transitionend', 320,") && tpl.contains("'height');"),
                "가장 오래 걸리는 속성(height)에서 끝내지 않는다 — 접힘이 잘린다");
        assertTrue(tpl.contains(".al-item.returning") && tpl.contains("markReturned(row);"),
                "확인 취소한 행을 강조하지 않는다 — 행이 사라지지 않아 무엇이 바뀌었는지 안 보인다");
        assertTrue(tpl.contains(".al-item { transition: background-color"), "확인 상태 전환이 즉시 바뀐다");

        // 모달: 닫을 때는 연출이 끝난 뒤에 .open 을 뗀다(먼저 떼면 display:none 이라 잘린다)
        assertTrue(tpl.contains(".modal-ov.closing { animation: ovFadeOut"), "확인 모달 닫기 연출이 없다");
        assertTrue(tpl.contains("cfModal.classList.add('closing');"), "확인 모달이 닫기 연출 없이 사라진다");
        assertTrue(tpl.contains("if (seq !== cfSeq) return;"), "닫히는 사이 다시 열면 뒤늦은 감추기가 새 모달을 지운다");
        assertTrue(tpl.contains("prefers-reduced-motion: reduce"), "동작 줄이기에서 연출을 끄지 않는다");

        // 말풍선의 확인 창도 같은 언어 — 열기만 연출하면 취소가 화면을 툭 끊는다
        String js = resource("/static/js/alert-bell.js");
        assertTrue(js.contains(".ab-cf-ov.ab-cf-out{animation:abCfFadeOut"), "말풍선 확인 창 닫기 연출이 없다");
        assertTrue(js.contains("afterMotion(ov, 'animationend', 220, detach);"), "확인 창을 연출이 끝난 뒤 떼지 않는다");
    }

    @Test
    @DisplayName("알림 말풍선 애니메이션 — 꼬리 기준으로 열리고, 닫을 때 사라진 뒤 떼며, 확인 항목은 접혀 빠진다. 동작 줄이기면 즉시")
    void bellAnimations() throws IOException {
        String js = resource("/static/js/alert-bell.js");
        assertTrue(js.contains("@keyframes abPopIn") && js.contains("@keyframes abPopOut"), "열기·닫기 애니메이션이 없다");
        // 위/아래가 열리는 도중 뒤집혀도 애니메이션이 처음부터 다시 돌지 않게 — 이름이 아니라 변수로 방향을 바꾼다
        assertTrue(js.contains(".ab-pop.up{--ab-dy:6px;transform-origin:calc(var(--ab-arrow-x,24px) + 7px) 100%}"),
                "위로 열릴 때 기준점·방향이 없다");
        assertFalse(js.contains("abPopInUp"), "방향별 애니메이션 이름 — 뒤집힐 때 다시 재생된다");
        // 닫을 때는 애니메이션이 끝난 뒤 뗀다. 끝 이벤트가 안 와도(숨은 탭 등) 시간 상한 뒤엔 반드시 뗀다
        assertTrue(js.contains("el.classList.add('ab-out');") && js.contains("afterMotion(el, 'animationend', 220, detach)"),
                "닫기 애니메이션 후 제거 배선");
        assertTrue(js.contains(".ab-pop.ab-out{animation:abPopOut .13s ease-in both;pointer-events:none}"),
                "사라지는 말풍선이 클릭을 받는다");
        // 확인 항목은 높이 트랜지션이 끝난 뒤 목록을 다시 그린다 — 가장 빠른 속성(투명도)에서 그리면 접힘이 잘린다
        assertTrue(js.contains("afterMotion(row, 'transitionend', 260, done, 'height')"), "확인 항목 접힘 대기 속성");
        // 동작 줄이기: CSS 로 끄고, JS 도 끝 이벤트를 기다리지 않는다(안 그러면 닫힌 말풍선이 잠시 남는다)
        assertTrue(js.contains("prefers-reduced-motion:reduce") && js.contains("if (motionOff()) { detach(); return; }"),
                "동작 줄이기에서 즉시 닫지 않는다");
        assertFalse(js.contains("setInterval("), "배경 타이머 직접 사용");
    }
}

package com.heapdump.analyzer;

import com.heapdump.analyzer.model.dto.DetectionSummaryItem;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대시보드 탐지 현황 KPI 카드(Critical/High/Medium/Low) 렌더 회귀 (2026-08-30).
 *
 * <p>라벨 `CRITICAL`/`MEDIUM` 은 공백이 없어 줄바꿈이 되지 않는다. 카드가 좁아지면 그대로
 * 카드 밖으로 삐져나가 옆 카드를 침범했는데(실측: 카드 50px 일 때 27px 초과), 판정 기준이
 * 뷰포트가 아니라 <b>카드 폭</b>이라 미디어 쿼리로는 잡히지 않는다 — 배너를 접으면(220→44px)
 * 같은 뷰포트에서 카드가 22px 넓어져 임계 뷰포트가 ~1300px 에서 ~1105px 로 밀린다.
 * 그래서 카드를 컨테이너로 선언하고 {@code @container} 로 라벨을 감춘다.
 */
class DashboardDetectSummaryTest {

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

    /** 탐지 현황 패널이 실제로 렌더되는 모델 (파일 1건 + 심각도 카운트). */
    private static Map<String, Object> dashboardModel() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", "dump.hprof");
        file.put("extension", "HPROF");
        file.put("compressed", false);
        file.put("formattedSize", "157 MB");
        file.put("formattedCompressedSize", "45 MB");
        file.put("formattedOriginalSize", "157 MB");
        file.put("formattedDate", "2026-08-30 12:00");

        DetectionSummaryItem item = new DetectionSummaryItem();
        item.setFilename("dump.hprof");
        item.setSuspectCount(12);
        item.setCriticalCount(3);
        item.setHighCount(5);
        item.setMediumCount(2);
        item.setLowCount(2);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("files", List.of(file));
        m.put("recentFiles", List.of(new com.heapdump.analyzer.model.dto.RecentFileItem(
                new com.heapdump.analyzer.model.HeapDumpFile("dump.hprof", "/x/dump.hprof", 157L << 20, 0L, false, 0L, 0L), "heap", "SUCCESS", false)));
        m.put("recentCount", 1);
        m.put("fileCount", 1);
        m.put("totalSize", "157 MB");
        m.put("analyzedCount", 1L);
        m.put("totalSuspects", 12L);
        m.put("analyzedFiles", Set.of("dump.hprof"));
        m.put("errorFiles", Collections.emptySet());
        m.put("othersFiles", Collections.emptySet());
        m.put("allowAllExtensions", false);
        m.put("maxUploadSizeGb", 5);
        m.put("maxUploadSizeBytes", 5L * 1024 * 1024 * 1024);
        m.put("diskUsedPercent", 42);
        // 탐지 현황
        m.put("hasDetections", true);
        m.put("criticalCount", 3);
        m.put("highCount", 5);
        m.put("mediumCount", 2);
        m.put("lowCount", 2);
        m.put("detectionItems", List.of(item));
        m.put("dailyDetections", Collections.emptyList());
        m.put("serverSeries", Collections.emptyList());
        m.put("dashboardDetectDays", 14);
        m.put("kpiTotal14d", 12);
        m.put("kpiLast7d", 7);
        m.put("kpiPrev7d", 5);
        m.put("kpiDelta7d", 40);
        m.put("kpiPeakDay", "08-29");
        m.put("kpiPeakCount", 4);
        m.put("dailyDetectJson", "{\"daily\":[{\"date\":\"2026-08-29\"}],\"series\":[]}");   // 차트 데이터 섬(2026-09-26)
        return m;
    }

    /** 페이지가 아니라 th:fragment 조각 하나만 렌더 — /api/dashboard/fragments 가 하는 일과 같다. */
    private static String renderFragment(String template, String fragment, Map<String, Object> model) {
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
        return engine.process(new org.thymeleaf.TemplateSpec(template, Set.of(fragment), TemplateMode.HTML, null), ctx);
    }

    private static final List<String> DASH_FRAGMENTS = List.of(
            "dashRecentFiles", "dashStats", "dashProgress", "dashDetections", "dashDetectTotals", "dashDetectKinds");

    @Test
    @DisplayName("부분 갱신 조각 6개는 페이지와 같은 마크업이고, 파일이 없으면 페이지에 없는 조각은 빈 출력이다 (2026-09-26)")
    void fragmentsShareMarkupAndFollowPageConditions() {
        Map<String, Object> full = dashboardModel();
        String page = render("index", full);
        for (String f : DASH_FRAGMENTS) {
            String frag = renderFragment("index", f, full);
            assertTrue(frag.contains("id=\"" + f + "\""), f + " 조각이 자기 id 를 갖지 않는다");
            assertTrue(page.contains("id=\"" + f + "\""), f + " 조각 id 가 페이지에 없다");
            assertFalse(frag.contains("<html"), f + " 조각이 문서 전체를 낸다");
        }
        // 차트 데이터 섬 — 조각 안에 있고 페이지에도 있다
        String det = renderFragment("index", "dashDetections", full);
        assertTrue(det.contains("<script type=\"application/json\" id=\"dashDetectData\">{\"daily\":[{\"date\":\"2026-08-29\"}],\"series\":[]}</script>"),
                "데이터 섬이 th:utext 로 실리지 않는다");
        // 토글·큐 패널은 조각 밖이어야 한다(SegThumb 상태·JS 렌더 유지)
        assertFalse(renderFragment("index", "dashDetectTotals", full).contains("detectKindToggle"), "토글이 조각 안에 들어갔다");
        assertFalse(renderFragment("index", "dashDetectKinds", full).contains("detectKindToggle"), "토글이 조각 안에 들어갔다");
        for (String f : DASH_FRAGMENTS) assertFalse(renderFragment("index", f, full).contains("queuePanel"), "Analysis Queue 가 조각에 포함됐다: " + f);

        // 파일 0건 — 페이지의 조상 th:if 가 조각 루트에도 복제돼 있어야 '한쪽만 있음' 이 생기지 않는다
        Map<String, Object> none = dashboardModel();
        none.put("files", Collections.emptyList());
        none.put("fileCount", 0);
        none.put("recentFiles", Collections.emptyList());
        none.put("recentCount", 0);
        String emptyPage = render("index", none);
        for (String f : List.of("dashStats", "dashProgress", "dashDetections", "dashDetectTotals", "dashDetectKinds")) {
            assertFalse(emptyPage.contains("id=\"" + f + "\""), "파일 0건 페이지에 " + f + " 가 있다");
            assertTrue(renderFragment("index", f, none).trim().isEmpty(),
                    "파일 0건인데 " + f + " 조각이 렌더된다 — 페이지와 어긋나 부분 갱신이 멈춘다(조각 루트에 th:if 복제 필요)");
        }
        assertTrue(renderFragment("index", "dashRecentFiles", none).contains("No files yet"), "Recent Files 조각은 파일 0건에도 있어야 한다");
    }

    @Test
    @DisplayName("탐지 현황 종류 토글 — 기본은 힙덤프, 코어덤프·GC로그 블록은 숨긴 채 서버 렌더. 알림 벨은 Refresh 왼쪽")
    void detectKindToggleAndBell() {
        // detectKinds 가 없어도(서비스 미주입·조회 실패) 대시보드는 렌더된다
        String bare = render("index", dashboardModel());
        assertTrue(bare.contains("심각도가 판정된 코어덤프 분석 결과가 없습니다."), "detectKinds 없는 렌더의 빈 상태");

        Map<String, Object> model = dashboardModel();
        com.heapdump.analyzer.model.dto.DetectKindSummary core = new com.heapdump.analyzer.model.dto.DetectKindSummary();
        core.setCritical(1); core.setHigh(1);
        com.heapdump.analyzer.model.dto.AlertBoardRow row = new com.heapdump.analyzer.model.dto.AlertBoardRow();
        row.setDomain("core"); row.setFilename("core.1234"); row.setSeverity("Critical"); row.setKeySignal("SIGSEGV");
        row.setStatus("SUCCESS"); row.setLink("/core-dump/analyze/core.1234");
        core.getItems().add(row);
        model.put("detectKinds", Map.of("core", core, "gclog", new com.heapdump.analyzer.model.dto.DetectKindSummary()));
        String html = render("index", model);

        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다");
        assertTrue(html.contains("<div data-detect-kind=\"heap\">"), "힙 블록은 hidden 없이 기본 표시");
        // th:attr 로 붙는 data-* 는 정적 속성 뒤에 온다 — 순서가 아니라 같은 태그 안에 hidden 이 있는지를 본다
        assertTrue(html.contains("<div hidden data-detect-kind=\"core\">") && html.contains("<div hidden data-detect-kind=\"gclog\">"),
                "코어덤프·GC로그 블록이 기본 숨김이 아니다");
        assertEquals(3, html.split("class=\"detect-kind-btn\"", -1).length - 1, "토글 버튼 3개");
        assertTrue(html.contains("data-kind=\"heap\" aria-pressed=\"true\""), "기본 선택은 힙덤프");
        assertTrue(html.contains("총 2건"), "코어덤프는 파일 단위 건수");
        assertTrue(html.contains("href=\"/core-dump/analyze/core.1234\"") && html.contains(">SIGSEGV<"), "코어 항목");
        assertTrue(html.contains("심각도가 판정된 GC 로그 분석 결과가 없습니다."), "GC 빈 상태");
        assertTrue(html.contains("[data-detect-kind][hidden]"), "[hidden] 이 display 에 지지 않게 하는 규칙");

        int bell = html.indexOf("id=\"alertBellBtn\"");
        int refresh = html.indexOf(">Refresh</a>");
        assertTrue(bell > 0 && bell < refresh, "알림 벨이 Refresh 왼쪽(앞)에 없다");
        assertTrue(html.contains("/js/alert-bell.js?v="), "alert-bell.js 로드");
        assertTrue(html.contains("AlertBell.mount("), "벨 mount 호출");
    }

    @Test
    @DisplayName("탐지 현황 종류 전환 애니메이션 — 공용 seg-thumb.js 로 선택 표시가 미끄러지고 새 내용이 이동 방향에서 들어온다")
    void detectKindSwitchAnimates() {
        String html = render("index", dashboardModel());
        // 토글 스크립트가 본문 중간에서 돌므로 모듈은 그보다 먼저(head) 실려야 한다
        int mod = html.indexOf("/js/seg-thumb.js?v=");
        int toggleScript = html.indexOf("SegThumb.attach(box,");
        assertTrue(mod > 0 && mod < html.indexOf("</head>"), "seg-thumb.js 가 head 에 없다");
        assertTrue(toggleScript > mod, "선택 표시 배선이 없다");
        assertTrue(html.contains("SegThumb.attach(box, { active: '[aria-pressed=\"true\"]' })"), "선택 기준이 aria-pressed 가 아니다");
        assertTrue(html.contains("if (kind !== prev) enter(kind,") && html.contains("SegThumb.slideIn("),
                "전환 시 내용 슬라이드 호출이 없다 / 같은 탭을 다시 눌러도 돈다");
        // 종전 인라인 구현이 남으면 선택 표시가 둘이 된다
        assertFalse(html.contains("detect-kind-thumb") || html.contains("@keyframes detKindIn"), "인라인 선택 표시 구현이 남았다");
    }

    @Test
    @DisplayName("탐지 현황 KPI 카드 4종이 렌더된다 (대시보드 렌더 스모크)")
    void detectionKpiCardsRender() {
        String html = render("index", dashboardModel());

        assertTrue(html.contains("<div class=\"detect-card-lbl\">Critical</div>"), "Critical 카드가 없다");
        assertTrue(html.contains("<div class=\"detect-card-lbl\">High</div>"), "High 카드가 없다");
        assertTrue(html.contains("<div class=\"detect-card-lbl\">Medium</div>"), "Medium 카드가 없다");
        assertTrue(html.contains("<div class=\"detect-card-lbl\">Low</div>"), "Low 카드가 없다");
        // 라벨을 감추면 마우스 사용자에게 남는 유일한 이름이다
        assertTrue(html.contains("class=\"detect-card dc-critical\" title=\"Critical\""),
                "카드에 title 이 없다 — 라벨이 숨겨지면 마우스로 확인할 방법이 사라진다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("th:each"), "미처리 th: 속성이 남았다");
    }

    @Test
    @DisplayName("카드가 좁아지면 심각도 라벨은 컨테이너 쿼리로 감춘다 (미디어 쿼리 아님)")
    void severityLabelsHideByContainerQuery() {
        String html = render("index", dashboardModel());

        // 카드 자체가 컨테이너여야 한다 — .detect-summary 를 컨테이너로 잡으면 ≤480px 2열 배치에서
        // 카드가 99px 로 넉넉한데도 요약 폭이 작다는 이유로 라벨이 사라진다.
        assertTrue(html.contains("container-type: inline-size") && html.contains("container-name: detcard"),
                "카드가 컨테이너로 선언되지 않았다 — @container 가 어떤 폭에서도 적용되지 않는다");
        int q = html.indexOf("@container detcard (max-width: 64px)");
        assertTrue(q > 0, "라벨 숨김 컨테이너 쿼리가 없다 (임계값 변경 시 CHANGELOG 실측표도 함께 갱신할 것)");

        // display:none 이면 스크린리더에서도 사라져 숫자만 남는다 — 시각적 숨김이어야 한다
        String block = html.substring(q, q + 400);
        assertTrue(block.contains("clip-path: inset(50%)"), "시각적 숨김이 아니다");
        assertFalse(block.contains("display: none"), "display:none 은 스크린리더에서도 라벨을 없앤다");

        // 컨테이너 쿼리 미지원 브라우저용 2차 방어 — 최소한 말줄임으로 끝나야 한다(넘쳐서 옆 카드를 침범 금지)
        assertTrue(html.contains("white-space: nowrap; overflow: hidden; text-overflow: ellipsis;"),
                "말줄임 폴백이 없다 — 컨테이너 쿼리 미지원 시 라벨이 카드 밖으로 삐져나간다");
    }

    @Test
    @DisplayName("Recent Files 툴팁은 사이드바 밖(body 직속)에 떠서 잘리지 않고, 긴 파일명은 줄바꿈으로 끝까지 보인다 (2026-10-31)")
    void recentFileTooltipEscapesSidebar() {
        String html = render("index", dashboardModel());

        // 사이드바가 overflow-y:auto 라 그 안에서 hover 로 켜는 absolute 툴팁은 경계에서 잘린다(실측 657px 툴팁이 520px 에서 잘림)
        assertFalse(html.contains(".file-item:hover .fname-tooltip"), "항목 안의 툴팁을 직접 켜면 사이드바에 잘린다");
        assertTrue(html.contains("<div class=\"fname-tooltip\">"), "항목별 툴팁 내용은 서버 렌더로 남아야 부분 갱신을 따라간다");
        assertTrue(html.contains("tip.innerHTML = item.querySelector('.fname-tooltip').innerHTML;"), "항목의 내용을 복사해 띄우지 않는다");
        assertTrue(html.contains("document.body.appendChild(tip);"), "툴팁이 body 직속이 아니다 — sticky 사이드바의 쌓임 맥락에 갇힌다");
        assertTrue(html.contains("#fnameFloatTip.show { display: block; }"), "표시 스위치 규칙이 없다");
        assertTrue(html.contains("z-index: 190;"), "툴팁은 상단바(100) 위·모달(200) 아래여야 한다");

        // 아래가 모자라면 항목 위로 뒤집는다 — 목록 끝 항목의 툴팁이 화면 밖으로 나갔다(실측 931px / 뷰포트 800px)
        assertTrue(html.contains("top = a.top - GAP - tip.offsetHeight;"), "아래 공간 부족 시 뒤집기가 없다");

        // 툴팁은 목록에서 잘린 이름을 끝까지 보는 곳이다 — 말줄임으로 되돌리면 잘림을 고쳐도 이름을 볼 수 없다
        int val = html.indexOf(".fname-tooltip .tt-val {");
        assertTrue(val > 0, "파일명 값 규칙이 없다");
        String rule = html.substring(val, html.indexOf('}', val));
        assertTrue(rule.contains("overflow-wrap: anywhere"), "긴 파일명이 줄바꿈되지 않는다");
        assertFalse(rule.contains("text-overflow"), "툴팁 파일명에 말줄임이 남았다");

        // 조각 교체로 기준 항목이 사라지면 mouseout 이 오지 않아 옛 툴팁이 남는다
        int swapped = html.indexOf("onSwapped: function (ids)");
        assertTrue(swapped > 0 && html.indexOf("_fileTip.hide();", swapped) > swapped, "부분 갱신 때 툴팁을 닫지 않는다");
    }

    @Test
    @DisplayName("탐지 현황 행은 마우스를 올리면 배경으로 구분된다 — 조건부 미디어 쿼리에 가두지 않는다 (2026-10-31)")
    void detectRowsHighlightOnHover() {
        String html = render("index", dashboardModel());

        assertTrue(html.contains(".detect-item:hover { background: #EFF6FF;"), "탐지 현황 행 호버 배경이 없다");
        // 배경이 점·배지에 붙지 않게 하는 좌우 여백
        assertTrue(html.contains(".detect-item { display: flex; align-items: center; gap: 10px; padding: 9px 8px;"), "행 좌우 패딩이 없다");
        // (hover: hover) 는 마우스를 장치로 인식하지 못하는 환경에서 거짓이라 기능이 조용히 꺼진다(헤드리스 실측)
        assertFalse(html.contains("@media (hover: hover)"), "호버 강조가 장치 조건에 갇혔다");
    }

    @Test
    @DisplayName("탐지 현황 힙 행은 심각도 칩이 하나이고 내역은 툴팁이다 — 종류별 칩 4개가 파일명 폭을 0 으로 만들었다 (2026-10-31)")
    void heapDetectRowUsesSingleChipWithTooltip() throws java.io.IOException {
        String html = render("index", dashboardModel());   // 모델: critical 3 · high 5 · medium 2 · low 2

        int heap = html.indexOf("<div data-detect-kind=\"heap\">");
        String block = html.substring(heap, html.indexOf("data-detect-kind=\"core\"", heap));
        assertEquals(1, block.split("detect-sev detect-sev-sum", -1).length - 1, "힙 행의 심각도 칩은 하나여야 한다");
        assertTrue(block.contains("class=\"detect-sev detect-sev-sum ds-critical\""), "칩 색은 가장 높은 심각도");
        assertFalse(block.contains("ds-high") || block.contains("ds-medium") || block.contains("ds-low"), "종류별 칩이 남았다");
        assertTrue(block.contains(">critical x3 +9<"), "칩 문구");
        assertTrue(block.contains("data-tip=\"critical 3건\nhigh 5건\nmedium 2건\nlow 2건\""), "툴팁 내역(줄바꿈은 krds 의 pre-wrap 이 살린다)");
        assertTrue(block.contains("tabindex=\"0\""), "키보드로 닿지 않는다");

        // hover·포커스·터치 탭은 krds-tooltip.js 가 담당한다. 두 툴팁 모듈은 한 페이지에 섞지 않는다
        assertTrue(html.contains("/js/krds-tooltip.js?v="), "모듈이 없으면 툴팁이 조용히 안 뜬다");
        assertFalse(html.contains("/js/float-tooltip.js"), "툴팁 모듈 두 종이 섞였다");

        // 툴팁을 연 채 칩이 교체되면 mouseout 이 오지 않는다 — 터치는 다음 탭까지 옛 숫자가 남는다
        assertTrue(html.contains("if (ids.indexOf('dashDetectKinds') >= 0 && window.KrdsTooltip) KrdsTooltip.hide();"), "부분 갱신 때 툴팁을 닫지 않는다");
        try (java.io.InputStream in = getClass().getResourceAsStream("/static/js/krds-tooltip.js")) {
            String module = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(module.contains("window.KrdsTooltip = { hide: hideTip };"), "모듈의 닫기 함수가 공개돼 있지 않다");
        }
    }

    @Test
    @DisplayName("행이 좁으면 파일명이 첫 줄로 내려간다 — 판정은 뷰포트가 아니라 패널 폭이다 (2026-10-31)")
    void narrowDetectRowsKeepFilename() {
        String html = render("index", dashboardModel());

        // .detect-fname 은 flex:1 이라 공간이 모자라면 파일명만 폭 0 이 된다(실측 1024px 폭 0)
        assertTrue(html.contains("#dashDetectKinds { container-type: inline-size; container-name: detlist; }"), "목록이 컨테이너가 아니다");
        int q = html.indexOf("@container detlist (max-width: 300px)");
        assertTrue(q > 0, "좁은 행 컨테이너 쿼리가 없다");
        String block = html.substring(q, q + 200);
        assertTrue(block.contains("flex-wrap: wrap") && block.contains("flex-basis: calc(100% - 18px)"), "파일명이 한 줄을 차지하지 않는다");
        // 100% 면 점만 한 줄을 차지해 행이 3줄이 된다
        assertFalse(html.contains(".detect-fname { flex-basis: 100%; }"), "모바일 규칙이 좁은 행 배치와 다르다");
    }

    @Test
    @DisplayName("본문이 좁으면 오른쪽 카드가 왼쪽 열 아래로 내려간다 — 판정은 뷰포트가 아니라 본문 폭이다 (2026-10-31)")
    void narrowMainStacksRightCards() {
        String html = render("index", dashboardModel());

        // 배너를 접으면 같은 뷰포트에서 본문이 176px 달라져 미디어 쿼리로는 맞출 수 없다(실측: 증상은 본문 폭으로만 갈린다)
        assertTrue(html.contains(".main-content { padding: 20px; overflow-y: auto; container-type: inline-size; container-name: dashmain; }"),
                "본문이 컨테이너가 아니다");
        int q = html.indexOf("@container dashmain (max-width: 700px)");
        assertTrue(q > 0, "좁은 본문 컨테이너 쿼리가 없다 (임계값 변경 시 주석의 브라우저 폭 환산도 함께 갱신할 것)");
        assertTrue(html.substring(q, q + 120).contains(".dashboard-top, .grid2 { grid-template-columns: minmax(0, 1fr); }"), "2단 영역이 1단이 되지 않는다");

        // 같은 명시도라 뒤에 와야 이긴다
        assertTrue(q > html.indexOf(".dashboard-top { display: grid;") && q > html.indexOf(".grid2 { display: grid;"), "쿼리가 기본 규칙보다 앞에 있다");

        // 진행률·Queue 는 한 열 안에서 함께 내려가도록 같은 래퍼에 있어야 한다
        int right = html.indexOf("class=\"dashboard-right\"");
        assertTrue(right > 0 && right < html.indexOf("id=\"dashProgress\"") && right < html.indexOf("id=\"queuePanel\""), "오른쪽 카드 구조가 바뀌었다");

        // 본문이 컨테이너라 그 안의 position:fixed 는 기준이 바뀔 수 있다 — 모달이 본문 밖이어야 뷰포트를 덮는다
        int mainEnd = html.indexOf("</main>");
        assertTrue(html.indexOf("id=\"downloadModal\"") > mainEnd && html.indexOf("id=\"deleteModal\"") > mainEnd, "모달이 본문 안으로 들어갔다");
    }

    @Test
    @DisplayName("Recent Files: GC 로그 항목은 GC 경로·배지·확인 모달 kind 로, 힙 항목은 힙 경로로 렌더된다 (2026-09-14)")
    void recentFilesRenderGcLogWithGcRoutes() {
        Map<String, Object> m = dashboardModel();
        com.heapdump.analyzer.model.HeapDumpFile gcDone = new com.heapdump.analyzer.model.HeapDumpFile("gc.log", "/g/gc.log", 53_251L, 2L, false, 53_251L, 0L);
        com.heapdump.analyzer.model.HeapDumpFile gcNew = new com.heapdump.analyzer.model.HeapDumpFile("gc.log.1", "/g/gc.log.1", 10L, 1L, false, 10L, 0L);
        com.heapdump.analyzer.model.HeapDumpFile gcRun = new com.heapdump.analyzer.model.HeapDumpFile("gc.log.2", "/g/gc.log.2", 10L, 1L, false, 10L, 0L);
        com.heapdump.analyzer.model.HeapDumpFile heapNew = new com.heapdump.analyzer.model.HeapDumpFile("app.hprof", "/h/app.hprof", 10L, 0L, false, 10L, 0L);
        m.put("recentFiles", List.of(
                new com.heapdump.analyzer.model.dto.RecentFileItem(gcDone, "gclog", "SUCCESS", false),
                new com.heapdump.analyzer.model.dto.RecentFileItem(gcNew, "gclog", "NOT_ANALYZED", false),
                new com.heapdump.analyzer.model.dto.RecentFileItem(gcRun, "gclog", "ANALYZING", false),
                new com.heapdump.analyzer.model.dto.RecentFileItem(heapNew, "heap", "NOT_ANALYZED", false)));
        m.put("recentCount", 7);
        String raw = render("index", m);
        assertTrue(raw.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다");
        String html = raw.replaceAll("\\s+", " ");   // 속성 사이 개행·들여쓰기 무시
        assertTrue(html.contains("(4 / 7)"), "헤더 건수는 병합 목록 기준");

        assertTrue(html.contains("data-name=\"gc.log\" data-kind=\"gclog\""), "GC 로그 항목 kind");
        assertTrue(html.contains("<div class=\"ext-badge\">GC</div>") && html.contains("file-gclog"), "GC 배지·클래스");
        assertTrue(html.contains("href=\"/gc-log/analyze/gc.log\" class=\"fb v\""), "분석 완료 GC 로그는 GC 결과 페이지로 — 힙 /analyze/result 로 가면 안 된다");
        assertFalse(html.contains("/analyze/result/gc.log"), "GC 로그에 힙 결과 경로");
        assertTrue(html.contains("href=\"/gc-log/analyze/gc.log.1?start=1\" class=\"fb p\""), "미분석 GC 로그의 분석 폴백 href 는 start=1");
        assertTrue(html.contains("data-filename=\"gc.log.1\" data-kind=\"gclog\""), "분석 확인 모달 kind gclog");
        assertTrue(html.contains("href=\"/gc-log/analyze/gc.log.2\" class=\"fb analyzing\""), "GC 분석 중 버튼");
        assertTrue(html.contains("href=\"/analyze/app.hprof\" class=\"fb p\"") && html.contains("data-filename=\"app.hprof\" data-kind=\"heap\""), "힙 항목은 종전 경로");
        assertTrue(html.contains("data-url=\"/delete/app.hprof\" data-kind=\"heap\""), "힙 삭제는 종전 form 경로");
        assertTrue(html.contains("title=\"Delete\" data-name=\"gc.log\" data-kind=\"gclog\" onclick=\"showDeleteModal(this.dataset.name,this.dataset.url,this.dataset.kind)"),
                "GC 삭제는 힙 /delete URL 없이(빈 값은 속성째 빠진다) kind 로 분기");
        assertTrue(html.contains("title=\"Download\" data-name=\"gc.log\" data-size=\"52.00 KB\" data-kind=\"gclog\" onclick=\"showDownloadModal("), "GC 다운로드 버튼도 kind 를 넘긴다");

        // JS 분기 — 다운로드·삭제·분석 중 폴링
        assertTrue(html.contains("(_dlKind === 'gclog' ? '/api/gc-log/download/' : '/download/')"), "GC 다운로드 경로");
        assertTrue(html.contains("Common.fetchJSON('/api/gc-log/' + encodeURIComponent(filename) + '?deleteFile=true', { method: 'DELETE' })"), "GC 삭제 API");
        assertTrue(html.contains("if (!d || d.success !== true)"), "삭제 응답 success 확인(함정 27)");
        assertTrue(html.contains("if (item.getAttribute('data-kind') === 'gclog') return;"), "힙 MAT 큐 폴링이 GC 항목을 건드리지 않는다");
    }
}

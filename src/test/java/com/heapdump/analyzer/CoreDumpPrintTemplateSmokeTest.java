package com.heapdump.analyzer;

import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.dto.CoreDumpRevision;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.CoreDumpPdfReportService;
import com.heapdump.analyzer.service.CoreDumpPdfReportServiceTest;
import com.heapdump.analyzer.service.PdfReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코어덤프 인쇄 템플릿 + 결과 화면 탭 개편 렌더 스모크 (2026-08-23).
 *
 * <p>탭 구조 개편(Summary/리포트 탭)과 신규 인쇄 템플릿(core-dump/analyze-print)은 마크업
 * 대수술이라 함정 23(평범한 {@code <script>} 안 {@code [[} 도 Thymeleaf 인라인 표현식으로
 * 파싱 → chunked 응답 중단 → 빈 화면)을 실제 엔진 렌더로 방어한다. 인쇄 템플릿은 PDF 바이트
 * 렌더(%PDF 헤더)까지 확인해 OpenHTMLtoPDF XHTML 정합성·폰트 로드도 함께 커버한다
 * (AccountMemoTemplateSmokeTest 패턴).
 */
class CoreDumpPrintTemplateSmokeTest {

    /** {@code ${_csrf.token}} / {@code ${_csrf.headerName}} 해석용 최소 스텁. */
    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static String render(String template, Map<String, Object> model) {
        // 배너 fragment 의 @{/logout} 같은 컨텍스트 상대 링크는 IWebContext 를 요구한다.
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        model.forEach(ctx::setVariable);
        return engine().process(template, ctx);
    }

    /** 인쇄 모델은 실제 빌더로 생성 — 모델↔템플릿 계약이 어긋나면 여기서 잡힌다. */
    private static Map<String, Object> printModel(String revLabel, CoreDumpAnalysisResult result) {
        CoreDumpAnalyzerService analyzer = Mockito.mock(CoreDumpAnalyzerService.class);
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("severity", "High");
        insight.put("summary", "요약 텍스트");
        insight.put("rootCause", "원인 텍스트");
        insight.put("recommendations", "1. 첫째\n2. 둘째");
        insight.put("model", "claude-fable-5");
        Mockito.when(analyzer.loadAiInsight(Mockito.anyString())).thenReturn(insight);
        return new CoreDumpPdfReportService(null, analyzer)
                .buildCorePrintModel(result.getFilename(), revLabel, result);
    }

    private static Map<String, Object> analyzeModel(CoreDumpAnalysisResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("filename", result != null ? result.getFilename() : "core.12345");
        m.put("result", result);
        CoreDumpAnalysisEntity entity = null;
        if (result != null) {
            entity = new CoreDumpAnalysisEntity();
            entity.setFilename(result.getFilename());
            entity.setStatus("SUCCESS");
        }
        m.put("entity", entity);
        CoreDumpRevision rev = new CoreDumpRevision();
        rev.setId("20260801-120000");
        rev.setLabel("2026-08-01 12:00 · exec 없음");
        m.put("revisions", result != null ? List.of(rev) : List.of());
        m.put("currentRevision", null);
        return m;
    }

    // ── 인쇄 템플릿 (core-dump/analyze-print) ────────────────────

    @Test
    @DisplayName("인쇄 템플릿 — 풀 모델 렌더 (헤더/KPI/콜 체인/결함 모듈/AI 요약)")
    void printTemplateRendersFull() {
        String html = render("core-dump/analyze-print",
                printModel(null, CoreDumpPdfReportServiceTest.fullResult()));

        assertTrue(html.contains("Core Dump Crash Report"));
        assertTrue(html.contains("SIGSEGV"));
        assertTrue(html.contains("크래시 콜 체인"), "콜 체인 섹션이 없다");
        assertTrue(html.contains("crash_here"), "FRAME #0 함수명이 표에 없다");
        assertTrue(html.contains("libfoo.so"), "결함 모듈 박스가 없다");
        assertTrue(html.contains("HIGH"), "AI severity 배지가 대문자로 렌더되지 않았다");
        assertTrue(html.contains("요약 텍스트"));
        assertFalse(html.contains("보존 리비전"), "현재 결과인데 리비전 배지가 떴다");

        // 크래시 요약은 화면과 동일하게 2열(원인·위치) — 조치 칸 제거 (2026-08-23).
        // ⚠ 화면 쪽 단언은 "조치 · FIX"(U+00B7) 를 찾지만 인쇄본 표기는 "조치 &#183; Fix" 라
        //   렌더 결과 문자열이 달라 서로를 못 잡는다 — 인쇄용 단언을 따로 둔다.
        // 정적 텍스트의 &#183; 는 Thymeleaf 가 디코드하지 않고 그대로 흘린다
        assertTrue(html.contains("원인 &#183; Why") && html.contains("위치 &#183; Where"), "원인/위치 칸이 사라졌다");
        // ⚠ "조치" 단독 검사 금지 — AI 요약의 "권장 조치"(정상)까지 잡는다. FIX 셀 라벨만 본다.
        assertFalse(html.contains("조치 &#183; Fix"), "인쇄 리포트에 조치 칸이 남아 있다");
        assertTrue(html.contains("권장 조치"), "AI 권장 조치까지 지워졌다");
        assertFalse(html.contains("sum-cell fix"), "조치 셀 마크업이 남아 있다");
        // ⚠ "33.33%" 단독 검사 금지 — KPI 3열(.kpi)이 정당하게 쓴다. 요약 셀 규칙만 본다.
        assertTrue(html.contains(".sum-cell { display: table-cell; width: 50%;"),
                "요약 셀이 2열 균등(50%)이 아니다");
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
    }

    @Test
    @DisplayName("인쇄 템플릿 — 최소 모델(빈 결과·AI 없음) + 리비전 배지")
    void printTemplateRendersMinimalAndRevision() {
        CoreDumpAnalyzerService analyzer = Mockito.mock(CoreDumpAnalyzerService.class);
        Mockito.when(analyzer.loadAiInsight(Mockito.anyString())).thenReturn(null);
        CoreDumpAnalysisResult empty = new CoreDumpAnalysisResult();
        empty.setFilename("core.empty");
        Map<String, Object> model = new CoreDumpPdfReportService(null, analyzer)
                .buildCorePrintModel("core.empty", "20260801-120000", empty);

        String html = render("core-dump/analyze-print", model);
        assertTrue(html.contains("보존 리비전"), "rev 리포트 배지가 없다");
        assertTrue(html.contains("AI 크래시 분석 미실행"), "AI 미분석 빈 박스가 없다");
        assertTrue(html.contains("식별 가능한 프레임 없음"), "빈 콜 체인 행이 없다");
        assertTrue(html.trim().endsWith("</html>"));
    }

    @Test
    @DisplayName("인쇄 템플릿 → PDF 바이트 (%PDF 헤더) — XHTML 정합성 + Pretendard 폰트 로드까지 커버")
    void printTemplateRendersToPdfBytes() throws Exception {
        byte[] pdf = new PdfReportService(engine(), null, null)
                .renderPdf("core-dump/analyze-print",
                        printModel(null, CoreDumpPdfReportServiceTest.fullResult()));
        assertTrue(pdf.length > 1000, "PDF 바이트가 비정상적으로 작다: " + pdf.length);
        String head = new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("%PDF-", head, "PDF 시그니처가 아니다");
    }

    // ── 결과 화면 (core-dump/analyze) — 탭 개편 ──────────────────

    @Test
    @DisplayName("결과 화면 — 탭 7개(요약~리포트), 기본 활성은 요약 패널, 요약 2열 래퍼 구조")
    void analyzeRendersWithSummaryTab() {
        // 경고 동반 성공(시그널 있음 + errorMessage 있음) — 경고 배너 배치까지 함께 검증
        CoreDumpAnalysisResult withWarning = CoreDumpPdfReportServiceTest.fullResult();
        withWarning.setErrorMessage("일부 스레드 스택을 읽지 못했습니다");
        String html = render("core-dump/analyze", analyzeModel(withWarning));

        // 배너 fragment 에도 data-tab(모바일 탭)이 있으므로 코어 탭 버튼 클래스만 센다
        Matcher tabBtn = Pattern.compile("class=\"cd-tab-btn").matcher(html);
        int count = 0;
        while (tabBtn.find()) count++;
        assertEquals(7, count, "탭 버튼은 summary/bt/threads/registers/libs/raw/report 7개");

        assertTrue(html.contains("id=\"tab-summary\""), "요약 패널이 없다");
        assertTrue(html.contains("class=\"cd-tab-panel active\" id=\"tab-summary\""),
                "기본 활성 탭이 요약이 아니다");
        assertFalse(html.contains("class=\"cd-tab-panel active\" id=\"tab-bt\""),
                "스택 트레이스 탭이 여전히 기본 활성이다");
        assertTrue(html.contains("id=\"tab-report\""), "리포트 탭 패널이 없다");
        assertTrue(html.contains("id=\"cdPdfIframe\""), "리포트 iframe 이 없다");
        assertTrue(html.contains("/print-pdf?"), "PDF 다운로드 앵커가 없다");
        assertTrue(html.contains("crash-hero"), "크래시 히어로가 사라졌다");
        // 탭 버튼은 data-tab 위임으로 전환 — onclick 문자열 매칭 버튼이 남아 있으면 안 된다
        // (히어로 팩트 바의 switchTab('bt') 링크는 의도적으로 유지되므로 버튼 마크업만 검사)
        assertFalse(html.contains("cd-tab-btn\" role=\"tab\" aria-selected=\"false\" onclick"),
                "onclick 방식 탭 버튼이 남아 있다");
        assertTrue(html.contains("data-tab=\"summary\"") && html.contains("data-tab=\"report\""),
                "탭 버튼에 data-tab 속성이 없다");
        assertTrue(html.contains("CORE_CURRENT_REV"), "리비전 글로벌 주입이 없다");

        // 넓은 화면 2열 배치 — AI 패널과 콜 체인이 같은 열 래퍼 안에 있어야 flex 2열이 성립한다
        assertTrue(html.contains("class=\"cd-analyze\""), "body 스코프 클래스가 없다 (컨테이너 확장 규칙 미적용)");
        int colsOpen = html.indexOf("class=\"cd-sum-cols\"");
        int colsClose = html.indexOf("<!-- /cd-sum-cols -->");
        assertTrue(colsOpen > 0 && colsClose > colsOpen, "2열 래퍼(cd-sum-cols)가 없다");
        int aiAt = html.indexOf("id=\"cdaPanel\"");
        int chainAt = html.indexOf("callchain-card");
        assertTrue(aiAt > colsOpen && aiAt < colsClose, "AI 패널이 2열 래퍼 밖에 있다");
        assertTrue(chainAt > colsOpen && chainAt < colsClose, "콜 체인이 2열 래퍼 밖에 있다");
        // 분석 경고 배너는 래퍼 앞(전폭) — 열 안에 들어가면 한쪽 열에 갇힌다
        int warnAt = html.indexOf("분석 경고:");
        assertTrue(warnAt > 0 && warnAt < colsOpen, "분석 경고 배너가 2열 래퍼 안에 있다");

        // 히어로 = 좌(정체성) / 우(팩트 레일) 2열 + 하단 전폭(신뢰도) — 2026-08-23 재설계
        int heroAt = html.indexOf("card--hero crash-hero");
        int heroEnd = html.indexOf("<!-- 시그널은 있으나 스택 없음");
        int railAt = html.indexOf("class=\"hero-rail\"");
        int mainAt = html.indexOf("class=\"hero-main\"");
        int confAt = html.indexOf("class=\"hero-confidence");
        assertTrue(heroAt > 0 && heroEnd > heroAt, "크래시 히어로 경계를 못 찾았다");
        assertTrue(mainAt > heroAt && mainAt < heroEnd, "좌측 정체성 열(hero-main)이 히어로 밖에 있다");
        assertTrue(railAt > mainAt && railAt < heroEnd, "팩트 레일이 히어로 안 hero-main 뒤에 있어야 한다");
        assertTrue(confAt > railAt && confAt < heroEnd, "신뢰도 디스클로저가 레일 뒤 전폭 자리에 없다");
        assertTrue(html.contains("원인") && html.contains("위치"), "원인/위치 항목이 사라졌다");
        assertFalse(html.contains("crash-summary"), "구 요약 스트립(.crash-summary)이 남아 있다");
        assertFalse(html.contains("조치 · FIX"), "조치 카드가 남아 있다");
        // 구 3개 컴포넌트(메타칩·팩트바·힌트)는 레일 하나로 통합 — 되살아나면 여백이 다시 늘어난다
        assertFalse(html.contains("hero-metachip"), "메타칩이 레일과 별도로 남아 있다");
        assertFalse(html.contains("crash-hero-hints"), "해석 힌트 박스가 레일과 별도로 남아 있다");
        assertFalse(html.contains("hero-facts"), "구 팩트 바(2열 카드)가 남아 있다");
        // JS 계약: core-dump-analyze.js 가 getElementById 로 찾아 코드 웰을 주입한다
        assertTrue(html.contains("id=\"heroSource\""), "히어로 소스 컨테이너 id 가 사라졌다");

        // 덤프 메타 정보는 요약 탭 안에 상시 노출 — 탭 밖 <details> 로 되돌아가면 안 된다
        int metaAt = html.indexOf("덤프 메타 정보");
        int summaryEnd = html.indexOf("<!-- /tab-summary -->");
        assertTrue(metaAt > 0, "덤프 메타 정보 카드가 없다");
        assertTrue(metaAt < summaryEnd, "덤프 메타 정보가 요약 탭 밖에 있다");
        assertFalse(html.contains("<details class=\"content-card\""), "메타 정보가 접힌 details 로 남아 있다");
        assertTrue(html.contains("meta-dl"), "메타 dl 그리드가 없다");
        assertTrue(html.contains("GDB 버전") && html.contains("분석 완료"), "메타 항목이 누락됐다");
        // 메타 카드는 구 요약 스트립 자리 — 히어로 바로 아래, 2열 래퍼보다 앞(전폭)
        assertTrue(metaAt > heroEnd, "덤프 메타 카드가 히어로보다 앞에 있다");
        assertTrue(metaAt < colsOpen, "덤프 메타 카드가 2열 래퍼 안/뒤에 있다 (구 요약 스트립 자리로 못 옮겨졌다)");

        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
    }

    @Test
    @DisplayName("결과 화면 — 오류 모델(result 없음)에서는 탭바가 렌더되지 않는다")
    void analyzeErrorModelHidesTabs() {
        Map<String, Object> m = analyzeModel(null);
        m.put("error", "분석 결과를 찾을 수 없습니다: core.12345");
        String html = render("core-dump/analyze", m);

        assertTrue(html.contains("분석 결과를 찾을 수 없습니다"));
        assertFalse(html.contains("cd-tabs-bar"), "오류 화면에 탭바가 렌더됐다");
        assertFalse(html.contains("id=\"tab-summary\""));
        assertTrue(html.trim().endsWith("</html>"));
    }
}

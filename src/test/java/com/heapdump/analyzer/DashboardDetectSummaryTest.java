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
        return m;
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

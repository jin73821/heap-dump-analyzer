package com.heapdump.analyzer;

import com.heapdump.analyzer.model.HeapAnalysisResult;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 힙덤프 인쇄 템플릿(analyze-print) — JVM Heap 환경 셀 렌더 스모크 (2026-09-11).
 *
 * <p>환경 스트립이 4셀→5셀로 바뀌었고 값은 Java 에서 완성한 문자열({@code jvmHeapLabel})만 받는다.
 * 라벨 유/무 두 경우가 각각 값·"미지정"으로 렌더되는지, 그리고 문서 끝까지 파싱되는지(함정 23)를 고정한다.
 */
class HeapPrintTemplateJvmSmokeTest {

    private static String render(Map<String, Object> model) {
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
        return engine.process("analyze-print", ctx);
    }

    /** PdfReportService.buildPrintModel 이 넣는 키를 최소값으로 흉내낸다(AI 없음·suspect 없음). */
    private static Map<String, Object> model(String jvmHeapLabel) {
        HeapAnalysisResult r = new HeapAnalysisResult();
        r.setFilename("java_pid1.hprof");
        r.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filename", r.getFilename());
        m.put("result", r);
        m.put("topMem", List.of());
        m.put("topSuspects", List.of());
        m.put("ai", null);
        m.put("hostname", "was01");
        m.put("middlewareVendor", "JEUS");
        m.put("jeusInstance", "srv1");
        m.put("jeusDomain", "dom1");
        m.put("jvmHeapLabel", jvmHeapLabel);
        m.put("usedBarPct", 100L);
        m.put("freeBarPct", 0L);
        m.put("analysisTimeSec", "1.0");
        m.put("formattedDate", "2026-09-11 10:00");
        m.put("suspectCount", 0);
        return m;
    }

    @Test
    @DisplayName("JVM Heap 셀 — 라벨이 있으면 그대로, 없으면 미지정")
    void jvmHeapCellRendersLabelOrPlaceholder() {
        String with = render(model("Xms 2g / Xmx 8g (자동 수집 · 추정)"));
        assertTrue(with.contains("JVM Heap"), "환경 셀 라벨이 없다");
        assertTrue(with.contains("Xms 2g / Xmx 8g (자동 수집 · 추정)"), "JVM 값이 렌더되지 않았다");
        assertTrue(with.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(with.contains("th:text") || with.contains("th:if"), "미처리 th: 속성이 남았다");

        String without = render(model("미지정"));
        int cell = without.indexOf("JVM Heap");
        assertTrue(cell > 0);
        assertTrue(without.substring(cell, Math.min(without.length(), cell + 400)).contains("미지정"), "미지정 폴백이 없다");
        assertTrue(without.contains("width: 20%"), "5셀 폭(20%)으로 바뀌지 않았다");
    }
}

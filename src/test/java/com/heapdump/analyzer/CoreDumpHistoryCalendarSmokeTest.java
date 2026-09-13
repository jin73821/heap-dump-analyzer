package com.heapdump.analyzer;

import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코어 덤프 분석 이력 — 업로드일 기간 캘린더 배선 (2026-09-14).
 *
 * <p>Files 와 같은 KRDS 캘린더 위젯({@code calendar.js} + common.css)을 쓴다. 고정하는 계약:
 * <ul>
 *   <li>입력 2개·팝업 영역 id 가 {@code core-dump-index.js} 의 {@code Calendar.attach} 인자와 일치</li>
 *   <li>행의 {@code data-uploaded} 가 ISO 날짜로 렌더된다 — 필터가 앞 10자('yyyy-MM-dd')를 문자열 비교한다</li>
 *   <li>{@code calendar.js} 는 페이지당 인스턴스 1개 — 이 페이지의 attach 는 이력 필터 한 곳뿐</li>
 * </ul>
 * JS 동작(기간 적용·지우기·복원·건수)은 이 렌더 결과를 헤드리스로 띄워 확인했다.
 * {@code -Dcoredump.render.dump=<경로>} 를 주면 렌더 결과를 파일로 남긴다(헤드리스 검증용).
 */
class CoreDumpHistoryCalendarSmokeTest {

    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static CoreDumpAnalysisEntity item(String name, String status, LocalDateTime created, LocalDateTime analyzed) {
        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
        e.setFilename(name);
        e.setStatus(status);
        e.setCreatedAt(created);
        e.setAnalyzedAt(analyzed);
        if ("SUCCESS".equals(status)) e.setCrashSignal("SIGSEGV");
        return e;
    }

    private static String render() {
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

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("coreDumpFiles", Collections.emptyList());
        m.put("history", List.of(
                item("core.1001", "SUCCESS", LocalDateTime.of(2026, 9, 1, 9, 30), LocalDateTime.of(2026, 9, 1, 9, 40)),
                item("core.1002", "ERROR", LocalDateTime.of(2026, 9, 10, 23, 59, 59), LocalDateTime.of(2026, 9, 11, 0, 5)),
                item("core.1003", "NOT_ANALYZED", LocalDateTime.of(2026, 9, 13, 0, 0), null)));
        m.forEach(ctx::setVariable);
        return engine.process("core-dump/index", ctx);
    }

    @Test
    @DisplayName("분석 이력 검색줄에 Files 와 같은 기간 캘린더가 렌더되고 JS 배선 id 가 일치한다")
    void historyCalendarRenders() throws IOException {
        String html = render();
        String dump = System.getProperty("coredump.render.dump");
        if (dump != null && !dump.isBlank()) Files.writeString(Path.of(dump), html, StandardCharsets.UTF_8);

        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        int controls = html.indexOf("<div class=\"hi-controls\">");
        int table = html.indexOf("id=\"hiTable\"");
        assertTrue(controls > 0 && table > controls, "이력 검색줄이 없다");
        String bar = html.substring(controls, table);
        assertTrue(bar.contains("id=\"hiSearch\""), "파일명 검색");
        assertTrue(bar.contains("<span class=\"hi-date-lbl\" id=\"hiDateLbl\">업로드일</span>"), "기준(업로드일) 라벨");
        assertTrue(bar.contains("class=\"calendar-range\""), "Files 와 같은 캘린더 박스(common.css)가 아니다");
        assertTrue(bar.contains("id=\"hiDateStart\"") && bar.contains("id=\"hiDateEnd\"") && bar.contains("id=\"hiCalArea\""),
                "캘린더 입력/팝업 id");
        assertTrue(bar.contains("onclick=\"Calendar.open()\"") && bar.contains("onclick=\"Calendar.clear()\""), "달력 열기·지우기");

        // 필터는 data-uploaded 앞 10자를 'yyyy-MM-dd' 로 비교한다
        assertTrue(html.contains("data-uploaded=\"2026-09-10T23:59:59\""), "data-uploaded 가 ISO 로 렌더되지 않았다");

        String js = Files.readString(Path.of("src/main/resources/static/js/core-dump-index.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("startInputId: 'hiDateStart', endInputId: 'hiDateEnd'") && js.contains("areaId: 'hiCalArea'"),
                "JS 의 Calendar.attach 인자가 템플릿 id 와 다르다");
        assertEquals(1, js.split("Calendar\\.attach\\(", -1).length - 1,
                "calendar.js 는 페이지당 인스턴스 1개 — attach 가 두 곳이면 나중 것이 앞의 입력을 가로챈다");
        assertTrue(html.contains("/js/calendar.js"), "calendar.js 가 로드되지 않는다(배너 전역 로드 경로)");
        assertFalse(html.contains("[["), "인라인 표현식으로 해석될 '[[' 가 남아 있다");
    }
}

package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 힙 analyze 페이지의 GC 로그 칩·사이드바·패널 배선 (2026-09-14).
 *
 * <p>칩 조각을 잘라 세 모델(연결됨 / 미연결+후보 / 미연결)로 실제 엔진 렌더 — {@code gcLog.matched[0].filename} 같은 식은
 * 미연결일 때 평가되면 안 된다(빈 리스트 인덱스). 사이드바 배지는 배너 탭 클론 때문에 ID 없이 class 로만 갱신(함정 8)하고,
 * AI 프롬프트 스키마에는 GC 로그가 연결됐을 때만 {@code gcAdvice} 키가 붙는다.
 */
class GcLogChipTemplateSmokeTest {

    private static final Path ANALYZE = Path.of("src/main/resources/templates/analyze.html");
    private static final Pattern UNPROCESSED_TH = Pattern.compile("\\sth:[a-z-]+=");

    private static String block(String html, String startMarker) {
        int start = html.indexOf(startMarker);
        assertTrue(start >= 0, "마커를 찾을 수 없다: " + startMarker);
        Matcher m = Pattern.compile("<div\\b|</div>|<button\\b|</button>").matcher(html);
        int depth = 0;
        m.region(start, html.length());
        while (m.find()) {
            if (m.group().startsWith("</")) { depth--; if (depth == 0) return html.substring(start, m.end()); }
            else depth++;
        }
        throw new AssertionError("태그 균형이 맞지 않는다: " + startMarker);
    }

    private static String render(String fragment, Map<String, Object> model) {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        Context ctx = new Context();
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        model.forEach(ctx::setVariable);
        return engine.process(fragment, ctx);
    }

    private static Map<String, Object> view(int matched, int candidates, String source) {
        Map<String, Object> v = new LinkedHashMap<>();
        List<Map<String, Object>> m = new java.util.ArrayList<>();
        for (int i = 0; i < matched; i++) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("filename", "gc.log." + i); x.put("source", source); x.put("reason", "server+time"); x.put("status", "SUCCESS");
            m.add(x);
        }
        List<Map<String, Object>> c = new java.util.ArrayList<>();
        for (int i = 0; i < candidates; i++) { Map<String, Object> x = new LinkedHashMap<>(); x.put("filename", "cand" + i); x.put("score", 1.0); c.add(x); }
        v.put("matched", m); v.put("candidates", c); v.put("count", matched); v.put("candidateCount", candidates); v.put("hasMatch", matched > 0);
        return v;
    }

    @Test
    @DisplayName("칩: 연결됨(자동/외 N) · 미연결+후보 · 미연결 세 모델이 모두 렌더된다")
    void chipRendersAllStates() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        String chip = block(html, "<div class=\"host-chip gclog-chip\" id=\"gcLogChip\"");

        String a = render(chip, Map.of("gcLog", view(2, 0, "auto")));
        assertTrue(a.contains("gc.log.0 외 1"), "연결 파일명 + 외 N: " + a);
        assertTrue(a.contains(">자동</span>"), "자동 배지");
        assertFalse(a.contains("host-chip-empty"));
        assertTrue(a.contains("id=\"gcLogChipOpen\" onclick=\"openGcLogPage()\" title=\"GC 로그 분석 결과 열기\">") && !a.contains("id=\"gcLogChipOpen\" onclick=\"openGcLogPage()\" title=\"GC 로그 분석 결과 열기\" style=\"display:none\""), "열기 버튼 보임");
        assertFalse(UNPROCESSED_TH.matcher(a).find(), "미처리 th: 속성");

        String b = render(chip, Map.of("gcLog", view(0, 3, "none")));
        assertTrue(b.contains(">미연결<") && b.contains("host-chip-empty"), "미연결");
        assertTrue(b.contains("후보 3"), "후보 배지");
        assertTrue(b.contains("style=\"display:none\""), "열기·해제 버튼 숨김");

        String c = render(chip, Map.of("gcLog", view(0, 0, "none")));
        assertTrue(c.contains(">미연결<") && !c.contains("후보 "));
        assertTrue(c.contains("onclick=\"openGcLogPickModal()\""), "선택 버튼은 항상");
    }

    @Test
    @DisplayName("사이드바 nav-item 은 class 배지(ID 없음), 패널·선택 모달·AI GC 카드가 있고 JS 가 그 계약을 쓴다")
    void sidebarPanelAndJsWiring() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        String nav = block(html, "<button type=\"button\" class=\"nav-item\" data-panel=\"gc-log\"");
        assertTrue(nav.contains("class=\"nav-badge gclog-nav-badge\""), "배지는 class 로만");
        assertFalse(nav.contains("id=\""), "사이드바 배지에 id 를 두면 배너 클론과 충돌한다(함정 8)");
        String navRendered = render(nav, Map.of("gcLog", view(1, 0, "auto")));
        assertTrue(navRendered.contains(">1</span>"), "배지 수");
        String navNone = render(nav, Map.of("gcLog", view(0, 0, "none")));
        assertFalse(navNone.contains("gclog-nav-badge"), "0건이면 배지 없음");

        assertTrue(html.contains("<div id=\"panel-gc-log\" class=\"panel\""), "패널");
        assertTrue(html.contains("id=\"gcLogPickModal\"") && html.contains("id=\"gcLogPickList\""), "선택 모달");
        assertTrue(html.contains("id=\"aiGcAdviceCard\""), "AI GC 카드");
        assertTrue(html.contains("var GC_LOG_MATCHED     = /*[[${gcLog.hasMatch}]]*/ false;"), "인라인 변수");

        String js = Files.readString(Path.of("src/main/resources/static/js/analyze.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("if (name === 'gc-log' && !_gcLogPanelLoaded)"), "showPanel 훅");
        assertTrue(js.contains("function renderGcLogChip(view)") && js.contains("function loadGcLogPanel()") && js.contains("function openGcLogPickModal()"), "칩·패널·모달 함수");
        assertTrue(js.contains("document.querySelectorAll('.gclog-nav-badge')"), "배지는 querySelectorAll 로 원본+클론 동시 갱신");
        assertFalse(js.contains("getElementById('gclog-nav-badge')"), "배지를 id 로 찾으면 클론이 갱신되지 않는다");
        assertTrue(js.contains("\"gcAdvice\""), "AI 스키마 gcAdvice");
        assertTrue(js.contains("'/api/history/' + encodeURIComponent(FILENAME) + '/gc-log'"), "역방향 API 경로");
        assertTrue(js.contains("/summary?dump="), "요약 조회에 덤프를 넘겨 시점 위치를 받는다");
    }
}

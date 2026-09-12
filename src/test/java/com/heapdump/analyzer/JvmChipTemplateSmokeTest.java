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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * analyze.html 의 JVM Heap 칩·KPI 서브라벨·JS 인라인 변수 렌더 스모크 (2026-09-11).
 *
 * <p>analyze.html 전체는 모델이 13종이라 통째로 렌더하지 않고, 이번에 추가한 조각 3개를 템플릿에서 잘라내
 * 실제 엔진으로 렌더한다 — 엘비스({@code ?:})·{@code != null} 비교·문자열 결합이 섞인 식이라 문법 오류가 나면
 * 페이지 전체가 빈 화면이 된다(함정 23). 값 있음/없음 두 모델을 모두 통과해야 한다.
 */
class JvmChipTemplateSmokeTest {

    private static final Path ANALYZE = Path.of("src/main/resources/templates/analyze.html");

    private static String snippet(String html, String startMarker) {
        int s = html.indexOf(startMarker);
        assertTrue(s >= 0, "마커를 찾을 수 없다: " + startMarker);
        int start = html.lastIndexOf('<', s);
        int end = html.indexOf("</div>", s);
        return html.substring(start, end + "</div>".length());
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

    private static Map<String, Object> view(boolean hasValue) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("hasValue", hasValue);
        v.put("xms", hasValue ? "2g" : null);
        v.put("xmx", hasValue ? "8g" : null);
        v.put("source", hasValue ? "auto" : null);
        v.put("sourceLabel", hasValue ? "자동 수집" : null);
        List<Map<String, String>> fl = new ArrayList<>();
        if (hasValue) fl.add(Map.of("flag", "restarted", "label", "재기동 후", "hint", "덤프 이후 기동"));
        v.put("flagLabels", fl);
        v.put("ambiguous", !hasValue);
        v.put("candidateCount", hasValue ? 1 : 3);
        v.put("recollectable", hasValue);
        v.put("options", hasValue ? "-Xms2g -Xmx8g -XX:+UseG1GC" : null);
        v.put("usedPctOfXmx", hasValue ? 74 : null);
        v.put("pid", hasValue ? 1234 : null);
        v.put("capturedAtShort", hasValue ? "09-10 03:12" : null);
        v.put("capturedAt", hasValue ? "2026-09-10 03:12:45" : null);
        // 서버가 만든 ⓘ 툴팁 문구 (JvmHeapInfoService.tipText) — 실제 조립 규칙은 JvmHeapInfoServiceTest 가 고정한다
        v.put("tipText", hasValue
                ? "JVM 힙 설정 — Xms 2g / Xmx 8g\n출처: 자동 수집\n수집 시각: 2026-09-10 03:12:45 (pid 1234)\n\n"
                        + "재기동 후 — 덤프 이후 기동\n\nJVM 옵션: -Xms2g -Xmx8g -XX:+UseG1GC"
                : "JVM 힙 설정 — 미지정\n\njava 프로세스가 3개라 자동 확정하지 못했습니다 — 목록(☰)에서 고르세요.");
        return v;
    }

    @Test
    @DisplayName("칩·KPI 서브라벨·JS 변수 — 값 있음/없음 모델 모두 렌더")
    void chipKpiAndInlineVarRender() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        String chip = snippet(html, "id=\"jvmChip\"");
        String kpi = snippet(html, "id=\"kpiXmxSub\"");
        Matcher m = Pattern.compile("var JVM_HEAP[^\\n]*").matcher(html);
        assertTrue(m.find(), "JVM_HEAP 인라인 변수가 없다");
        String js = "<script th:inline=\"javascript\">" + m.group() + "</script>";

        Map<String, Object> with = Map.of("jvmHeap", view(true));
        String c1 = render(chip, with);
        assertTrue(c1.contains("Xms 2g / Xmx 8g"), c1);
        // 출처·신뢰도·수집 시각은 인라인 배지가 아니라 ⓘ 툴팁 안에 있어야 한다 (2026-09-12)
        assertTrue(c1.contains("class=\"info-icon\""), "ⓘ 트리거가 없다: " + c1);
        assertTrue(c1.contains("tabindex=\"0\"") && c1.contains("aria-label="),
                "키보드 포커스로 열려야 하고 아이콘만 남으므로 접근성 이름이 필요하다: " + c1);
        assertTrue(c1.contains("출처: 자동 수집"), c1);
        assertTrue(c1.contains("재기동 후"), c1);
        assertTrue(c1.contains("수집 시각: 2026-09-10 03:12:45 (pid 1234)"), c1);
        assertFalse(c1.contains("jvm-flag-src"), "출처는 더 이상 인라인 배지가 아니다");
        assertFalse(c1.contains("jvm-flag-warn"), "확정 상태에서 후보 배지가 보이면 안 된다");
        assertTrue(c1.contains("JVM 옵션: -Xms2g"), "옵션은 툴팁 마지막 단락");
        assertTrue(c1.startsWith("<div class=\"host-chip jvm-chip\" id=\"jvmChip\">"),
                "칩 자체에 네이티브 title 이 남으면 ⓘ 위에서 팝오버와 겹친다: " + c1.substring(0, Math.min(200, c1.length())));
        assertFalse(c1.contains("th:"), "미처리 th: 속성");
        String k1 = render(kpi, with);
        assertTrue(k1.contains("/ Xmx 8g (74%)"), k1);
        String j1 = render(js, with);
        assertTrue(j1.contains("\"xmx\":\"8g\"") && j1.contains("\"flagLabels\""), j1);

        Map<String, Object> without = Map.of("jvmHeap", view(false));
        String c0 = render(chip, without);
        assertTrue(c0.contains("미지정") && c0.contains("host-chip-empty"), c0);
        assertTrue(c0.contains("후보 3"), "미확정이면 후보 배지");
        assertTrue(c0.contains("목록(☰)"), "후보 배지도 native title 이 아니라 data-tip 으로 설명한다: " + c0);
        assertTrue(c0.contains("display:none"), "재수집 버튼은 출처 없으면 숨김");
        String k0 = render(kpi, without);
        assertTrue(k0.contains("display:none"), k0);
        String j0 = render(js, without);
        assertTrue(j0.contains("\"xmx\":null"), j0);
    }

    @Test
    @DisplayName("ⓘ 툴팁 모듈 — krds 한 종만 싣는다 (두 툴팁 모듈 혼재 금지)")
    void krdsTooltipModuleIsLoadedAndNotMixed() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        assertTrue(html.contains("/js/krds-tooltip.js"), "[data-tip] 팝오버 모듈이 없으면 툴팁이 조용히 안 뜬다");
        assertFalse(html.contains("/js/float-tooltip.js"),
                "float-tooltip([data-tooltip])과 함께 실으면 동작은 하지만 디자인이 섞인다");
    }
}

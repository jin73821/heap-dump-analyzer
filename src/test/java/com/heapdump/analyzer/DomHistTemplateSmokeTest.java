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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * analyze.html 의 Dominator Tree / Class Histogram 패널 렌더 스모크 (2026-09-13 조밀 표 + 페이지네이션 재설계).
 *
 * <p>두 패널을 템플릿에서 균형 잡힌 {@code <div>} 단위로 잘라 실제 SpringTemplateEngine 으로 렌더한다 —
 * {@code #lists.size}·{@code #numbers.formatInteger/formatDecimal}·엘비스가 섞인 식이라 문법 오류 하나가
 * 페이지 전체를 빈 화면으로 만든다(함정 23). 고정하는 계약:
 * <ul>
 *   <li>{@code table.data-table.dense} + table-grid.js 가 읽는 id(tbody·행표시 셀렉트·페이지네이션 바)</li>
 *   <li>정렬 헤더 {@code data-sort-key/type} 와 행의 {@code data-*} 정렬값(pct·idx 포함)</li>
 *   <li>{@code idx < 50} 참조 펼치기 규칙 — chevron 이 정확히 50개</li>
 *   <li>Histogram 열 순서(rank/name/objects/shallow/retained) — analyze.js findClassInHistogram 의 셀 인덱스 계약</li>
 * </ul>
 */
class DomHistTemplateSmokeTest {

    private static final Path ANALYZE = Path.of("src/main/resources/templates/analyze.html");
    /** 속성 접두(공백 + th:xxx=)만 잡는다 — "width:28px" 같은 CSS 값의 "th:" 는 미처리 속성이 아니다 */
    private static final Pattern UNPROCESSED_TH = Pattern.compile("\\sth:[a-z-]+=");

    /** {@code <div id="…"} 에서 시작해 여닫는 div 개수가 맞는 지점까지 자른다. */
    private static String panel(String html, String id) {
        int start = html.indexOf("<div id=\"" + id + "\"");
        assertTrue(start >= 0, "패널을 찾을 수 없다: " + id);
        Matcher m = Pattern.compile("<div\\b|</div>").matcher(html);
        int depth = 0;
        m.region(start, html.length());
        while (m.find()) {
            if (m.group().startsWith("</")) {
                depth--;
                if (depth == 0) return html.substring(start, m.end());
            } else {
                depth++;
            }
        }
        throw new AssertionError("div 균형이 맞지 않는다: " + id);
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

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static List<Map<String, Object>> dominatorRows(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("className", i == 0 ? "org.apache.catalina.loader.ParallelWebappClassLoader" : "com.example.pkg" + i + ".VeryLongClassName" + i);
            d.put("objectAddress", "0x7f00" + Integer.toHexString(0x1000 + i));
            d.put("shallowHeap", 64L + i);
            d.put("retainedHeap", 100_000_000L - i * 1000L);
            d.put("percentOfHeap", 12.3456 - i * 0.01);
            d.put("classLoader", i == 0);
            d.put("shallowHeapHuman", (64 + i) + " B");
            d.put("retainedHeapHuman", "95.4 MB");
            rows.add(d);
        }
        return rows;
    }

    @Test
    @DisplayName("Dominator Tree 패널 — dense 표·정렬 헤더·페이지네이션 골격·idx<50 chevron 규칙")
    void dominatorPanelRenders() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        String tpl = panel(html, "panel-dominator-tree");

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("hasDominatorTree", true);
        model.put("result", Map.of("dominatorTreeEntries", dominatorRows(52)));
        String out = render(tpl, model);

        assertFalse(UNPROCESSED_TH.matcher(out).find(), "미처리 th: 속성");
        assertFalse(out.contains("[["), "Thymeleaf 인라인 시작 시퀀스가 남으면 안 된다(함정 23)");
        assertTrue(out.contains("class=\"data-table dense\" id=\"domTreeTable\""), "dense 변형 표");
        assertTrue(out.contains("<tbody id=\"domTreeBody\">"), "table-grid.js tbodyId");
        for (String id : new String[]{"domPageSize", "domPaginationBar", "domPgInfo", "domPgList", "domNoMatch", "domTreeSearch"}) {
            assertTrue(out.contains("id=\"" + id + "\""), "그리드 배선 id 누락: " + id);
        }
        assertTrue(out.contains("<option value=\"50\" selected>50</option>"), "기본 행표시 50");
        for (String key : new String[]{"idx", "class", "shallow", "retained", "pct"}) {
            assertTrue(out.contains("data-sort-key=\"" + key + "\""), "정렬 헤더 누락: " + key);
        }
        assertEquals(5, count(out, "class=\"sort-arrow\""), "정렬 가능한 헤더 5개(chevron 열 제외)");
        assertTrue(out.contains("onclick=\"onDomHeaderSort(this)\""));

        assertEquals(52, count(out, "class=\"dom-row\""), "행 52건");
        assertEquals(50, count(out, "class=\"dom-chevron\""), "참조 펼치기 chevron 은 idx<50 인 50행에만");
        assertTrue(out.contains("data-idx=\"51\""), "data-idx 는 0 기반 원래 순위");
        assertTrue(out.contains("data-pct=\"12.3456\""), "% 정렬값은 원시 double");
        assertTrue(out.contains("data-retained=\"100000000\""));
        assertTrue(out.contains("class=\"cl-badge\""), "ClassLoader 행 배지");
        assertTrue(out.contains("title=\"org.apache.catalina.loader.ParallelWebappClassLoader @ 0x7f001000\""),
                "말줄임된 이름은 td title 로 전체를 보인다");
        assertTrue(out.contains("Top <strong>52</strong>"), "툴바 건수는 목록 크기");
        assertTrue(out.contains("12.35%"), "formatDecimal 2자리");
        assertTrue(out.contains("class=\"dom-lgd dom-lgd-shallow\"") && out.contains("class=\"dom-lgd dom-lgd-retained\""),
                "막대 범례 스와치는 유지");
        assertFalse(out.contains("dom-tab"), "탭 구조는 2단 드로어로 대체됐다");
    }

    @Test
    @DisplayName("Class Histogram 패널 — dense 표·정렬 헤더·페이지네이션 골격·셀 인덱스 계약")
    void histogramPanelRenders() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        String tpl = panel(html, "panel-histogram");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("className", i == 0 ? "byte[]" : "java.util.HashMap$Node" + i);
            h.put("objectCount", 8481L + i);
            h.put("shallowHeap", 100_761_152L - i);
            h.put("retainedHeap", 100_761_152L - i);
            h.put("retainedHeapDisplay", ">= 100,761,152");
            h.put("shallowHeapHuman", "96.1 MB");
            h.put("retainedHeapHuman", "≥96.1 MB");
            rows.add(h);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("hasHistogram", true);
        model.put("histogramEntries", rows);
        model.put("totalHistogramClasses", 25086);
        String out = render(tpl, model);

        assertFalse(UNPROCESSED_TH.matcher(out).find(), "미처리 th: 속성");
        assertFalse(out.contains("[["), "함정 23");
        assertTrue(out.contains("class=\"data-table dense\" id=\"histogramTable\""));
        assertTrue(out.contains("<tbody id=\"histogramBody\">"));
        for (String id : new String[]{"histPageSize", "histPaginationBar", "histPgInfo", "histPgList", "histNoMatch", "histSearch"}) {
            assertTrue(out.contains("id=\"" + id + "\""), "그리드 배선 id 누락: " + id);
        }
        for (String key : new String[]{"idx", "class", "objects", "shallow", "retained"}) {
            assertTrue(out.contains("data-sort-key=\"" + key + "\""), "정렬 헤더 누락: " + key);
        }
        assertTrue(out.contains("onclick=\"onHistHeaderSort(this)\""));
        assertTrue(out.contains("data-idx=\"2\"") && out.contains("data-objects=\"8481\""), "행 정렬값");
        assertTrue(out.contains("title=\"byte[]\""), "이름 td title");
        assertTrue(out.contains("(out of <strong>25,086</strong> total classes)"), "총계 콤마 포맷");
        assertTrue(out.contains("Top <span>3</span> classes"), "툴바 건수는 목록 크기(기존 25행 분석 건은 25 로 보인다)");

        // findClassInHistogram 셀 인덱스 계약: 행마다 td 5개, 순서 rank / name / objects / shallow / retained
        Matcher row = Pattern.compile("<tr data-class=\"byte\\[\\]\"[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(out);
        assertTrue(row.find(), "byte[] 행");
        String cells = row.group(1);
        assertEquals(5, count(cells, "<td"), "td 5개");
        int rank = cells.indexOf("rank-num"), name = cells.indexOf("class-name-cell"),
            objects = cells.indexOf("8,481"), shallow = cells.indexOf("96.1 MB"), retained = cells.indexOf("≥96.1 MB");
        assertTrue(rank < name && name < objects && objects < shallow && shallow < retained, "열 순서: " + cells);
    }

    @Test
    @DisplayName("스크립트 배선 — table-grid.js 가 analyze.js 앞에 실리고 캐시 키가 갱신됐다")
    void gridScriptIsLoadedBeforeAnalyzeJs() throws IOException {
        String html = Files.readString(ANALYZE, StandardCharsets.UTF_8);
        int grid = html.indexOf("/js/table-grid.js?v=");
        int analyze = html.indexOf("/js/analyze.js?v=");
        assertTrue(grid >= 0, "table-grid.js 를 싣지 않으면 initDomGrid/initHistGrid 가 no-op 이라 500행이 통째로 그려진다");
        assertTrue(grid < analyze, "analyze.js 가 초기화 시점에 TableGrid 를 참조하므로 순서가 중요하다");
        assertFalse(html.contains("/js/analyze.js?v=2026-09-12"), "캐시 키 갱신");
        assertFalse(html.contains("/css/analyze.css?v=2026-09-11"), "캐시 키 갱신");
    }
}

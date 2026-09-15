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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * analyze.html Instance 칩 렌더 스모크 (2026-09-15) — 툴팁이 자동 식별 출처 키(jeus.server.name / weblogic.Name)를
 * 말하는지, 미식별일 때 null 키로도 렌더가 깨지지 않는지 고정한다. 식이 틀리면 페이지 전체가 빈 화면이 된다(함정 23).
 */
class InstanceChipTemplateSmokeTest {

    private static final Path ANALYZE = Path.of("src/main/resources/templates/analyze.html");

    private static String chip(String html) {
        int s = html.indexOf("id=\"jeusChip-instance\"");
        assertTrue(s >= 0, "Instance 칩이 없다");
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

    @Test
    @DisplayName("WebLogic 자동 식별: 이름 + 툴팁 출처 weblogic.Name / 미식별: 미지정 + 두 키 안내")
    void titleNamesSourceKey() throws IOException {
        String fragment = chip(Files.readString(ANALYZE, StandardCharsets.UTF_8));

        String wl = render(fragment, Map.of("jeusInstance", "AdminServer", "jeusInstanceAutoKey", "weblogic.Name"));
        assertTrue(wl.contains(">AdminServer<"), wl);
        assertTrue(wl.contains("System Properties(weblogic.Name) 자동 식별"), wl);
        assertFalse(wl.contains("th:"), "미처리 th: 속성: " + wl);

        Map<String, Object> none = new HashMap<>();
        none.put("jeusInstance", "");
        none.put("jeusInstanceAutoKey", null);
        String n = render(fragment, none);
        assertTrue(n.contains("미지정") && n.contains("host-chip-empty"), n);
        assertTrue(n.contains("jeus.server.name / weblogic.Name) 미식별"), n);
    }
}

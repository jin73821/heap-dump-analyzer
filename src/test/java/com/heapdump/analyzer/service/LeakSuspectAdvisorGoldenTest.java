package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.entity.LeakFallbackRule;
import com.heapdump.analyzer.model.entity.LeakLibraryRule;
import com.heapdump.analyzer.util.LeakSuspectAdvisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * LeakSuspectAdvisor.analyze() 골든 테스트 — DB 룰 경로의 결과 불변성 검증.
 *
 * JSON 시드(leak-rules/*.json)를 LeakRuleSeeder 와 동일한 키 매핑으로 파싱해
 * LeakRuleService mock 에 주입한다(우선순위 오름차순 — 운영 쿼리
 * findByEnabledTrueOrderByPriorityAscIdAsc 와 동일한 순서).
 * 하드코딩 룰 배열(KNOWN_LIBRARIES/FALLBACK_RULES) 제거 전후로 결과가 같아야 한다.
 */
class LeakSuspectAdvisorGoldenTest {

    private static List<LeakLibraryRule> LIBS;
    private static List<LeakRuleService.RuntimeFallback> FALLBACKS;

    @BeforeAll
    static void loadSeedJson() throws Exception {
        ObjectMapper om = new ObjectMapper();

        List<LeakLibraryRule> libs = new ArrayList<>();
        try (InputStream in = new ClassPathResource("leak-rules/library-rules.json").getInputStream()) {
            for (JsonNode n : om.readTree(in)) {
                LeakLibraryRule r = new LeakLibraryRule();
                r.setPrefix(n.path("prefix").asText());
                r.setLibraryName(n.path("libraryName").asText());
                r.setCategory(n.path("category").asText());
                r.setSeverityHint(n.path("severityHint").isNull() ? null : n.path("severityHint").asText(null));
                r.setExplanationTpl(n.path("explanationTpl").asText());
                r.setAdviceTpl(n.path("adviceTpl").asText());
                r.setEnabled(n.path("enabled").asBoolean(true));
                r.setPriority(n.path("priority").asInt(1000));
                if (r.isEnabled()) libs.add(r);
            }
        }
        libs.sort(Comparator.comparingInt(LeakLibraryRule::getPriority)); // stable → JSON 순서가 id 순서 역할
        LIBS = libs;

        List<LeakRuleService.RuntimeFallback> fbs = new ArrayList<>();
        try (InputStream in = new ClassPathResource("leak-rules/fallback-rules.json").getInputStream()) {
            List<LeakFallbackRule> rows = new ArrayList<>();
            for (JsonNode n : om.readTree(in)) {
                LeakFallbackRule r = new LeakFallbackRule();
                r.setName(n.path("name").asText());
                r.setCategory(n.path("category").asText());
                r.setPatternRegex(n.path("patternRegex").asText());
                r.setExplanationTpl(n.path("explanationTpl").asText());
                r.setAdviceTpl(n.path("adviceTpl").asText());
                r.setSeverityHint(n.path("severityHint").isNull() ? null : n.path("severityHint").asText(null));
                r.setEnabled(n.path("enabled").asBoolean(true));
                r.setPriority(n.path("priority").asInt(5000));
                if (r.isEnabled()) rows.add(r);
            }
            rows.sort(Comparator.comparingInt(LeakFallbackRule::getPriority));
            for (LeakFallbackRule r : rows) {
                fbs.add(new LeakRuleService.RuntimeFallback(
                        r, Pattern.compile(r.getPatternRegex(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL)));
            }
        }
        FALLBACKS = fbs;
    }

    @AfterEach
    void unbind() {
        LeakSuspectAdvisor.bindRuleService(null); // static 상태 누수로 타 테스트 오염 방지
    }

    private void bindSeedRules() {
        LeakRuleService mock = Mockito.mock(LeakRuleService.class);
        when(mock.libraryRules()).thenReturn(LIBS);
        when(mock.fallbackRules()).thenReturn(FALLBACKS);
        LeakSuspectAdvisor.bindRuleService(mock);
    }

    private LeakSuspect analyzed(String fullText) {
        LeakSuspect s = new LeakSuspect("Suspect #1", "desc");
        LeakSuspectAdvisor.analyze(s, fullText);
        return s;
    }

    // ─── DB 룰 경로 골든 (삭제 전후 결과 불변) ───────────────────────────

    @Test
    void newRelicWithAccumulator() {
        bindSeedRules();
        LeakSuspect s = analyzed(
                "1,234 instances of com.newrelic.agent.deps.SomeCache, "
                + "loaded by com.newrelic.bootstrap.BootstrapAgent$JVMAgentClassLoader @ 0x7f0 "
                + "occupy 489,131,008 (45.6%) bytes. "
                + "The memory is accumulated in one instance of com.newrelic.agent.util.CacheTable "
                + "which occupies 450,000,000 (42.0%) bytes.");
        assertEquals("APM Agent 메모리 점유", s.getCategory());
        assertEquals("high", s.getSeverity());
        assertNotNull(s.getExplanation());
        assertTrue(s.getExplanation().contains("New Relic"), s.getExplanation());
        assertTrue(s.getExplanation().contains("45.6"), s.getExplanation());
        assertNotNull(s.getAdvice());
        assertTrue(s.getAdvice().contains("newrelic.yml"), s.getAdvice());
    }

    @Test
    void jeusServletInputStream() {
        bindSeedRules();
        LeakSuspect s = analyzed(
                "3,000 instances of jeus.servlet.engine.io.BufferedServletInputStream, "
                + "loaded by jeus.loader.WebappClassLoader @ 0x1a2 "
                + "occupy 209,715,200 (25.0%) bytes.");
        assertEquals("WAS 세션/요청 누적", s.getCategory());
        assertEquals("high", s.getSeverity());
        assertTrue(s.getExplanation().contains("BufferedServletInputStream"), s.getExplanation());
        assertNotNull(s.getAdvice());
    }

    @Test
    void catalinaSessionManager() {
        bindSeedRules();
        LeakSuspect s = analyzed(
                "10 instances of org.apache.catalina.session.StandardManager, "
                + "loaded by org.apache.catalina.loader.ParallelWebappClassLoader @ 0x9b "
                + "occupy 128,974,848 (12.3%) bytes.");
        assertEquals("WAS 세션 누적", s.getCategory());
        assertEquals("medium", s.getSeverity());
        assertNotNull(s.getExplanation());
        assertNotNull(s.getAdvice());
    }

    @Test
    void hibernateSessionFactory() {
        bindSeedRules();
        LeakSuspect s = analyzed(
                "One instance of org.hibernate.internal.SessionFactoryImpl, "
                + "loaded by sun.misc.Launcher$AppClassLoader @ 0x1 "
                + "occupies 52,428,800 (8.2%) bytes.");
        assertEquals("ORM 캐시/세션 누적", s.getCategory());
        assertEquals("low", s.getSeverity());
        assertNotNull(s.getExplanation());
        assertNotNull(s.getAdvice());
    }

    @Test
    void javaLangClassOneInstance() {
        bindSeedRules();
        LeakSuspect s = analyzed(
                "One instance of java.lang.Class, loaded by <system class loader> "
                + "occupies 590,558,003 (55.0%) bytes.");
        assertEquals("클래스 메타데이터 누적", s.getCategory());
        assertEquals("critical", s.getSeverity());
        assertNotNull(s.getExplanation());
        assertNotNull(s.getAdvice());
    }

    @Test
    void regexFallbackCollectionGrowth() {
        bindSeedRules();
        // 라이브러리 prefix 미매칭 → fallback 정규식(hashmap 계열) 매칭 + enrichExplanation 동적 보강
        LeakSuspect s = analyzed(
                "50,000 instances of com.example.cache.EntryHolder, "
                + "loaded by com.example.AppLoader @ 0x1 occupy 104,857,600 (15.0%) bytes. "
                + "The memory is accumulated in one instance of java.util.HashMap.");
        assertEquals("컬렉션 증가", s.getCategory());
        assertEquals("medium", s.getSeverity());
        // enrichExplanation 이 동적 컨텍스트를 prepend
        assertTrue(s.getExplanation().contains("EntryHolder"), s.getExplanation());
        assertTrue(s.getExplanation().contains("15.0"), s.getExplanation());
        assertNotNull(s.getAdvice());
    }

    @Test
    void unmatchedTextHitsCatchAllRule() {
        bindSeedRules();
        // 시드에 catch-all(`.*`) fallback 룰이 있어 어떤 텍스트든 최소 generic 카테고리를 받는다
        LeakSuspect s = analyzed("완전히 무관한 텍스트입니다. 어떤 룰과도 매칭되지 않습니다.");
        assertEquals("메모리 누수 의심", s.getCategory());
        assertEquals("medium", s.getSeverity()); // 비율 미추출 기본값
        assertNotNull(s.getExplanation());
        assertNotNull(s.getAdvice());
    }

    // ─── 하드코딩 룰 배열 제거 후 no-op 계약 ─────────────────────────────

    @Test
    void emptyRuleListsAreNoOp() {
        LeakRuleService mock = Mockito.mock(LeakRuleService.class);
        when(mock.libraryRules()).thenReturn(List.of());
        when(mock.fallbackRules()).thenReturn(List.of());
        LeakSuspectAdvisor.bindRuleService(mock);

        LeakSuspect s = analyzed(
                "1,234 instances of com.newrelic.agent.deps.SomeCache occupy 489,131,008 (45.6%) bytes.");
        assertNull(s.getCategory());
        assertNull(s.getExplanation());
        assertNull(s.getAdvice());
        assertNull(s.getSeverity());
    }

    @Test
    void unboundRuleServiceIsNoOp() {
        LeakSuspectAdvisor.bindRuleService(null);
        LeakSuspect s = analyzed(
                "1,234 instances of com.newrelic.agent.deps.SomeCache occupy 489,131,008 (45.6%) bytes.");
        assertNull(s.getCategory());
        assertNull(s.getExplanation());
        assertNull(s.getAdvice());
        assertNull(s.getSeverity());
    }

    @Test
    void nullAndEmptyInputAreNoOp() {
        bindSeedRules();
        LeakSuspect s = new LeakSuspect("Suspect #1", "desc");
        LeakSuspectAdvisor.analyze(s, null);
        LeakSuspectAdvisor.analyze(s, "");
        LeakSuspectAdvisor.analyze(null, "text");
        assertNull(s.getCategory());
    }
}

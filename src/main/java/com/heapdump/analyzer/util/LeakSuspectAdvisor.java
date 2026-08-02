package com.heapdump.analyzer.util;

import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.entity.LeakFallbackRule;
import com.heapdump.analyzer.model.entity.LeakLibraryRule;
import com.heapdump.analyzer.service.LeakRuleService;
import com.heapdump.analyzer.service.LeakRuleService.RuntimeFallback;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leak Suspect 텍스트를 구조적으로 분석하여 카테고리, 설명, 조언, 심각도를 생성한다.
 *
 * 분석 흐름 (DB 단일 경로, 2026-08-02 하드코딩 룰 배열 제거):
 * 1. parseContext() — MAT 텍스트에서 클래스명, 인스턴스 수, 메모리 크기/비율, 축적 대상 등 추출
 * 2. DB 룰셋(LeakLibraryRule prefix → LeakFallbackRule regex) 순차 매칭 — LeakRuleSeeder 가
 *    시드한 leak-rules/*.json 이 데이터 원본, /admin/leak-rules 에서 운영자가 CRUD.
 *
 * 룰 미매칭/서비스 미주입/룰 전체 비활성화 시 no-op (suspect 필드 null 유지).
 * 회귀 방어: LeakSuspectAdvisorGoldenTest.
 */
public final class LeakSuspectAdvisor {

    private LeakSuspectAdvisor() {}

    /** Spring 빈 LeakRuleService 정적 참조 (LeakSuspectAdvisorBootstrap에서 주입). null 가능 — 미주입 시 analyze() 는 no-op. */
    private static volatile LeakRuleService ruleService;

    public static void bindRuleService(LeakRuleService svc) {
        ruleService = svc;
    }

    // ─── 텍스트 파싱용 정규식 ──────────────────────────────────────────────

    private static final Pattern INSTANCE_CLASS_PATTERN = Pattern.compile(
            "(\\d[\\d,]*|One|one)\\s+instances?\\s+of\\s+([\\w.$\\[\\]<>]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern LOADER_PATTERN = Pattern.compile(
            "loaded by\\s+(.*?)(?:\\s*@\\s*0x[\\da-fA-F]+)?\\s+occup", Pattern.CASE_INSENSITIVE);

    private static final Pattern MEMORY_PATTERN = Pattern.compile(
            "occup(?:y|ies)\\s+([\\d,]+)\\s+\\(([\\d.]+)%\\)\\s+bytes", Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCUMULATOR_PATTERN = Pattern.compile(
            "accumulated in.*?instance of\\s+([\\w.$]+).*?occup(?:y|ies)\\s+([\\d,]+)\\s+\\(([\\d.]+)%\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern REFERENCED_FROM_PATTERN = Pattern.compile(
            "referenced from.*?instance of\\s+([\\w.$\\[\\]]+)", Pattern.CASE_INSENSITIVE);

    // ─── 메인 분석 메서드 ────────────────────────────────────────────────

    /**
     * Suspect의 전체 텍스트를 구조적으로 분석하여 category, explanation, advice, severity를 설정한다.
     *
     * DB 룰셋(LeakLibraryRule/LeakFallbackRule) 단일 경로. 룰 미매칭 또는 서비스 미주입
     * (bindRuleService 미호출/룰 전체 비활성화) 시에는 no-op — suspect 필드가 null 로 남고,
     * UI(analyze.html)는 null 가드로 원문+키워드만 표시한다.
     */
    public static void analyze(LeakSuspect suspect, String fullText) {
        if (suspect == null || fullText == null || fullText.isEmpty()) return;

        SuspectContext ctx = parseContext(fullText);
        tryDbRules(suspect, fullText, ctx);
    }

    /** DB 룰셋을 우선 시도. 매칭되면 suspect 채우고 true. service 미주입/룰 없음/미매칭이면 false. */
    private static boolean tryDbRules(LeakSuspect suspect, String fullText, SuspectContext ctx) {
        LeakRuleService svc = ruleService;
        if (svc == null) return false;

        // 0a. DB library rules (prefix)
        for (LeakLibraryRule lib : svc.libraryRules()) {
            if (matchesDbLibrary(ctx, lib.getPrefix())) {
                suspect.setCategory(lib.getCategory());
                suspect.setExplanation(LeakRuleTemplate.render(lib.getExplanationTpl(), toTplCtx(ctx)));
                suspect.setAdvice(LeakRuleTemplate.render(lib.getAdviceTpl(), toTplCtx(ctx)));
                suspect.setSeverity(pickSeverity(lib.getSeverityHint(), ctx.severity));
                return true;
            }
        }

        // 0b. DB fallback rules (regex)
        for (RuntimeFallback rf : svc.fallbackRules()) {
            if (rf.pattern.matcher(fullText).find()) {
                suspect.setCategory(rf.rule.getCategory());
                String tplOut = LeakRuleTemplate.render(rf.rule.getExplanationTpl(), toTplCtx(ctx));
                suspect.setExplanation(enrichExplanation(tplOut, ctx));
                suspect.setAdvice(LeakRuleTemplate.render(rf.rule.getAdviceTpl(), toTplCtx(ctx)));
                suspect.setSeverity(pickSeverity(rf.rule.getSeverityHint(), ctx.severity));
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDbLibrary(SuspectContext ctx, String prefix) {
        if (prefix == null || prefix.isEmpty()) return false;
        if (ctx.className != null && ctx.className.startsWith(prefix)) return true;
        if (ctx.accumulatorClass != null && ctx.accumulatorClass.startsWith(prefix)) return true;
        if (ctx.classLoader != null && ctx.classLoader.startsWith(prefix)) return true;
        return false;
    }

    private static String pickSeverity(String hint, String fromPercentage) {
        return (hint != null && !hint.isEmpty()) ? hint : fromPercentage;
    }

    /** 내부 SuspectContext → 템플릿 엔진용 com.heapdump.analyzer.util.LeakRuleContext 변환. */
    private static com.heapdump.analyzer.util.LeakRuleContext toTplCtx(SuspectContext src) {
        com.heapdump.analyzer.util.LeakRuleContext t = new com.heapdump.analyzer.util.LeakRuleContext();
        t.instanceCount = src.instanceCount;
        t.className = src.className;
        t.simpleClassName = src.simpleClassName;
        t.classLoader = src.classLoader;
        t.bytes = src.bytes;
        t.percentage = src.percentage;
        t.accumulatorClass = src.accumulatorClass;
        t.accumulatorSimple = src.accumulatorSimple;
        t.accumulatorBytes = src.accumulatorBytes;
        t.accumulatorPercentage = src.accumulatorPercentage;
        t.referencedFromClass = src.referencedFromClass;
        t.severity = src.severity;
        t.hasAccumulator = t.accumulatorClass != null;
        t.hasReferencedFrom = t.referencedFromClass != null;
        t.hasInstanceCount = t.instanceCount > 0;
        t.highPercentage = t.percentage >= 30.0;
        t.veryHighPercentage = t.percentage >= 50.0;
        String lcn = t.simpleClassName == null ? "" : t.simpleClassName.toLowerCase();
        t.streamClass = lcn.contains("inputstream") || lcn.contains("outputstream") || lcn.contains("reader") || lcn.contains("writer");
        t.sessionClass = lcn.contains("session");
        t.threadClass = lcn.contains("thread");
        t.classLoaderClass = lcn.contains("classloader");
        t.cacheClass = lcn.contains("cache");
        t.mapClass = lcn.contains("map") || lcn.contains("hashtable") || lcn.contains("dictionary");
        return t;
    }

    // ─── 텍스트 파싱 ────────────────────────────────────────────────────

    private static SuspectContext parseContext(String text) {
        SuspectContext ctx = new SuspectContext();

        // 인스턴스 수 + 클래스명
        Matcher m = INSTANCE_CLASS_PATTERN.matcher(text);
        if (m.find()) {
            String countStr = m.group(1).replace(",", "");
            ctx.instanceCount = countStr.equalsIgnoreCase("one") ? 1 : parseIntSafe(countStr);
            ctx.className = m.group(2);
            ctx.simpleClassName = simpleName(ctx.className);
        }

        // ClassLoader
        m = LOADER_PATTERN.matcher(text);
        if (m.find()) {
            ctx.classLoader = m.group(1).trim();
        }

        // 메모리
        m = MEMORY_PATTERN.matcher(text);
        if (m.find()) {
            ctx.bytes = parseLongSafe(m.group(1).replace(",", ""));
            ctx.percentage = parseDoubleSafe(m.group(2));
        }

        // 축적 대상
        m = ACCUMULATOR_PATTERN.matcher(text);
        if (m.find()) {
            ctx.accumulatorClass = m.group(1);
            ctx.accumulatorSimple = simpleName(m.group(1));
            ctx.accumulatorBytes = parseLongSafe(m.group(2).replace(",", ""));
            ctx.accumulatorPercentage = parseDoubleSafe(m.group(3));
        }

        // 참조 출처
        m = REFERENCED_FROM_PATTERN.matcher(text);
        if (m.find()) {
            ctx.referencedFromClass = simpleName(m.group(1));
        }

        // 심각도
        ctx.severity = severityFrom(ctx.percentage);

        return ctx;
    }

    // ─── fallback 설명 보강 ──────────────────────────────────────────────

    private static String enrichExplanation(String baseExplanation, SuspectContext ctx) {
        if (ctx.className == null && ctx.percentage <= 0) return baseExplanation;

        StringBuilder sb = new StringBuilder();

        // 동적 컨텍스트 요약 추가
        if (ctx.className != null) {
            sb.append(ctx.simpleClassName);
            if (ctx.instanceCount > 0) {
                sb.append(" ").append(formatInstanceCount(ctx.instanceCount));
            }
            if (ctx.percentage > 0) {
                sb.append("가 힙의 ").append(ctx.percentage).append("% (").append(formatBytes(ctx.bytes)).append(")를 점유하고 있습니다. ");
            } else {
                sb.append("가 메모리를 점유하고 있습니다. ");
            }
        }

        if (ctx.accumulatorClass != null) {
            sb.append("메모리는 주로 ").append(ctx.accumulatorSimple).append("에 축적되어 있습니다. ");
        }

        sb.append(baseExplanation);
        return sb.toString();
    }

    // ─── 유틸리티 메서드 ─────────────────────────────────────────────────

    static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("약 %.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("약 %.1fMB", bytes / (1024.0 * 1024));
        return String.format("약 %.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String severityFrom(double percentage) {
        if (percentage >= 50) return "critical";
        if (percentage >= 25) return "high";
        if (percentage >= 10) return "medium";
        if (percentage > 0) return "low";
        return "medium"; // 비율을 추출하지 못한 경우 기본값
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) return null;
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static String formatInstanceCount(int count) {
        if (count <= 0) return "";
        if (count == 1) return "1개 인스턴스";
        return String.format("%,d개 인스턴스", count);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    // ─── 내부 데이터 클래스 ──────────────────────────────────────────────

    private static class SuspectContext {
        int instanceCount;
        String className;
        String simpleClassName;
        String classLoader;
        long bytes;
        double percentage;
        String accumulatorClass;
        String accumulatorSimple;
        long accumulatorBytes;
        double accumulatorPercentage;
        String referencedFromClass;
        String severity;
    }
}

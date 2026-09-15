package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GC 로그 AI 응답 정규화(2026-09-16) — severity 4값·recommendations 리스트·길이 상한·통과 키·null 안전. */
class GcAiResponseNormalizerTest {

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> recs(Map<String, Object> d) { return (List<String>) d.get("recommendations"); }

    @SuppressWarnings("unchecked")
    private static List<String> warnings(Map<String, Object> d) { return (List<String>) d.get("warnings"); }

    @Test
    @DisplayName("severity: 대소문자·앞머리만 맞아도 정규형, 모르는 값은 Unknown + 원문 + 경고, 'Unknown' 은 경고 없이 유지")
    void severityIsCanonicalized() {
        assertEquals("High", GcAiResponseNormalizer.normalize(m("severity", "high")).get("severity"));
        assertEquals("Critical", GcAiResponseNormalizer.normalize(m("severity", " CRITICAL ")).get("severity"));
        Map<String, Object> head = GcAiResponseNormalizer.normalize(m("severity", "Medium (Full GC 빈도 기준)"));
        assertEquals("Medium", head.get("severity"));
        assertTrue(warnings(head).get(0).contains("Medium 로 읽음"), String.valueOf(warnings(head)));
        Map<String, Object> bad = GcAiResponseNormalizer.normalize(m("severity", "심각"));
        assertEquals("Unknown", bad.get("severity"));
        assertEquals("심각", bad.get("severityRaw"));
        assertTrue(warnings(bad).get(0).contains("인식하지 못해"));
        Map<String, Object> unknown = GcAiResponseNormalizer.normalize(m("severity", "Unknown"));
        assertEquals("Unknown", unknown.get("severity"));
        assertNull(unknown.get("warnings"), "JSON_PARSE_WARN 폴백은 정상 경로");
    }

    @Test
    @DisplayName("recommendations: 번호 매긴 한 줄·줄바꿈 문자열·리스트 모두 정리된 List<String>")
    void recommendationsBecomeList() {
        assertEquals(List.of("힙을 늘리세요", "누수를 잡으세요"), recs(GcAiResponseNormalizer.normalize(m("recommendations", "1. 힙을 늘리세요 2. 누수를 잡으세요"))));
        assertEquals(List.of("A", "B", "C"), recs(GcAiResponseNormalizer.normalize(m("recommendations", "- A\n• B\n\n3) C\n"))));
        assertEquals(List.of("x", "y"), recs(GcAiResponseNormalizer.normalize(m("recommendations", new ArrayList<>(Arrays.asList(" 1. x", null, "", "y "))))));
        assertEquals(List.of(), recs(GcAiResponseNormalizer.normalize(m("recommendations", "-"))), "JSON_PARSE_WARN 폴백 '-' 는 빈 목록");
        assertEquals(List.of(), recs(GcAiResponseNormalizer.normalize(m("recommendations", "  "))));
    }

    @Test
    @DisplayName("recommendations 상한 5 — 초과분은 버리고 경고")
    void recommendationsAreCapped() {
        Map<String, Object> d = GcAiResponseNormalizer.normalize(m("recommendations", List.of("1", "2", "3", "4", "5", "6", "7")));
        assertEquals(5, recs(d).size());
        assertEquals("5", recs(d).get(4));
        assertTrue(warnings(d).get(0).contains("7건 중 5건만"), String.valueOf(warnings(d)));
    }

    @Test
    @DisplayName("본문 길이 상한 — summary 2000 · gcTuningAdvice 3000, 넘으면 '…' + 경고, 비문자열은 문자열화")
    void longTextIsClipped() {
        String longText = "가".repeat(2500);
        Map<String, Object> d = GcAiResponseNormalizer.normalize(m("summary", longText, "gcTuningAdvice", "나".repeat(3500), "rootCause", 42));
        assertEquals(2001, ((String) d.get("summary")).length());
        assertTrue(((String) d.get("summary")).endsWith("…"));
        assertEquals(3001, ((String) d.get("gcTuningAdvice")).length());
        assertEquals("42", d.get("rootCause"));
        assertEquals(2, warnings(d).size());
        Map<String, Object> ok = GcAiResponseNormalizer.normalize(m("summary", "짧다"));
        assertNull(ok.get("warnings"));
    }

    @Test
    @DisplayName("모르는 키는 그대로 통과하고 입력 맵은 바뀌지 않는다")
    void unknownKeysPassThroughAndInputIsUntouched() {
        Map<String, Object> in = m("severity", "low", "model", "claude", "extra", List.of(1, 2), "recommendations", "1. a");
        Map<String, Object> d = GcAiResponseNormalizer.normalize(in);
        assertEquals("claude", d.get("model"));
        assertEquals(List.of(1, 2), d.get("extra"));
        assertEquals("low", in.get("severity"), "입력 불변");
        assertEquals("1. a", in.get("recommendations"));
        assertEquals("Low", d.get("severity"));
    }

    @Test
    @DisplayName("null · 빈 맵 · 필드 없음은 예외 없이 지나간다")
    void nullSafe() {
        assertTrue(GcAiResponseNormalizer.normalize(null).isEmpty());
        assertTrue(GcAiResponseNormalizer.normalize(new LinkedHashMap<>()).isEmpty());
        Map<String, Object> d = GcAiResponseNormalizer.normalize(m("summary", null, "severity", null, "recommendations", null));
        assertNull(d.get("warnings"));
        assertNull(d.get("recommendations"));
    }

    @Test
    @DisplayName("warnings 는 고친 것이 있을 때만 붙고 순서는 severity → recommendations → 본문")
    void warningsOnlyWhenChanged() {
        Map<String, Object> d = GcAiResponseNormalizer.normalize(m("severity", "높음", "recommendations", List.of("1", "2", "3", "4", "5", "6"), "summary", "가".repeat(2001)));
        List<String> w = warnings(d);
        assertEquals(3, w.size());
        assertTrue(w.get(0).startsWith("severity") && w.get(1).startsWith("권고") && w.get(2).startsWith("summary"), String.valueOf(w));
        assertFalse(GcAiResponseNormalizer.normalize(m("severity", "High", "recommendations", List.of("a"), "summary", "s")).containsKey("warnings"));
    }
}

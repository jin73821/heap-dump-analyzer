package com.heapdump.analyzer.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GC 로그 AI 응답 정규화(2026-09-16) — LLM 이 돌려준 JSON 은 모양이 흔들린다: {@code severity} 가 소문자·문장, {@code recommendations} 가
 * 문자열(번호 매긴 한 줄)이거나 리스트, 본문이 수천 자. 저장 전에 한 모양으로 맞추고 무엇을 고쳤는지 {@code warnings} 에 남긴다.
 * 순수 함수 — 새 맵을 돌려주고 절대 던지지 않는다. 모르는 키는 그대로 통과.
 */
public final class GcAiResponseNormalizer {

    private GcAiResponseNormalizer() {}

    static final int MAX_RECOMMENDATIONS = 5;
    static final int SUMMARY_MAX = 2000, ROOT_CAUSE_MAX = 2000, SEVERITY_DESC_MAX = 1000, TUNING_MAX = 3000;
    private static final String[] SEVERITIES = {"Critical", "High", "Medium", "Low"};
    private static final Pattern LEAD_MARK = Pattern.compile("^\\s*(?:\\d+[.)]|[-•*·])\\s*");
    private static final Pattern INLINE_NUMBERED = Pattern.compile("\\s+(?=\\d+[.)]\\s)");

    public static Map<String, Object> normalize(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        List<String> warnings = new ArrayList<>();
        try {
            for (Map.Entry<String, Object> e : in.entrySet()) out.put(e.getKey(), e.getValue());
            normalizeSeverity(out, warnings);
            normalizeRecommendations(out, warnings);
            clip(out, "summary", SUMMARY_MAX, warnings);
            clip(out, "rootCause", ROOT_CAUSE_MAX, warnings);
            clip(out, "severityDesc", SEVERITY_DESC_MAX, warnings);
            clip(out, "gcTuningAdvice", TUNING_MAX, warnings);
        } catch (RuntimeException ex) {
            warnings.add("응답 정규화 중 오류: " + ex.getClass().getSimpleName());
        }
        if (!warnings.isEmpty()) out.put("warnings", warnings);
        return out;
    }

    private static void normalizeSeverity(Map<String, Object> out, List<String> warnings) {
        Object v = out.get("severity");
        if (v == null) return;
        String s = String.valueOf(v).trim();
        if (s.equalsIgnoreCase("Unknown")) { out.put("severity", "Unknown"); return; }   // JSON_PARSE_WARN 폴백 — 경고 없이 유지
        for (String c : SEVERITIES) if (c.equalsIgnoreCase(s)) { out.put("severity", c); return; }
        // "High (근거…)" 처럼 앞머리만 맞는 경우
        String head = s.split("[\\s(:,;/-]", 2)[0];
        for (String c : SEVERITIES) if (c.equalsIgnoreCase(head)) { out.put("severity", c); warnings.add("severity '" + trunc(s, 40) + "' 를 " + c + " 로 읽음"); return; }
        out.put("severity", "Unknown");
        out.put("severityRaw", trunc(s, 200));
        warnings.add("severity '" + trunc(s, 40) + "' 를 인식하지 못해 Unknown 으로 표시");
    }

    private static void normalizeRecommendations(Map<String, Object> out, List<String> warnings) {
        Object v = out.get("recommendations");
        if (v == null) return;
        List<String> items = new ArrayList<>();
        if (v instanceof Collection<?> col) {
            for (Object o : col) if (o != null) items.add(String.valueOf(o));
        } else {
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !s.equals("-")) {
                String[] lines = s.split("\\r?\\n+");
                if (lines.length == 1) lines = INLINE_NUMBERED.split(s);
                for (String l : lines) items.add(l);
            }
        }
        List<String> cleaned = new ArrayList<>();
        for (String it : items) {
            String c = LEAD_MARK.matcher(it.trim()).replaceFirst("").trim();
            if (!c.isEmpty()) cleaned.add(c);
        }
        if (cleaned.size() > MAX_RECOMMENDATIONS) {
            warnings.add("권고 " + cleaned.size() + "건 중 " + MAX_RECOMMENDATIONS + "건만 표시");
            cleaned = new ArrayList<>(cleaned.subList(0, MAX_RECOMMENDATIONS));
        }
        out.put("recommendations", cleaned);
    }

    private static void clip(Map<String, Object> out, String key, int max, List<String> warnings) {
        Object v = out.get(key);
        if (v == null) return;
        String s = (v instanceof String str ? str : String.valueOf(v)).trim();
        if (s.length() > max) {
            s = s.substring(0, max) + "…";
            warnings.add(key + " 가 " + max + "자를 넘어 잘림");
        }
        out.put(key, s);
    }

    private static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
}

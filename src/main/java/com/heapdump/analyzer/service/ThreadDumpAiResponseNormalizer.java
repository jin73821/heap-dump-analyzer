package com.heapdump.analyzer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스레드 덤프 AI 응답 정규화 — 공통 모양(severity 4값·recommendations 리스트·길이 상한)은 {@link GcAiResponseNormalizer} 에 맡기고
 * 스레드 덤프 전용 키 {@code codeAdvice} 의 길이만 더 본다. 순수 함수 — 절대 던지지 않는다.
 */
public final class ThreadDumpAiResponseNormalizer {

    private ThreadDumpAiResponseNormalizer() {}

    static final int CODE_ADVICE_MAX = 3000;

    public static Map<String, Object> normalize(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(GcAiResponseNormalizer.normalize(in));
        try {
            Object v = out.get("codeAdvice");
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (s.length() > CODE_ADVICE_MAX) {
                    s = s.substring(0, CODE_ADVICE_MAX) + "…";
                    List<String> warnings = new ArrayList<>();
                    if (out.get("warnings") instanceof List<?> w) for (Object o : w) warnings.add(String.valueOf(o));
                    warnings.add("codeAdvice 가 " + CODE_ADVICE_MAX + "자를 넘어 잘림");
                    out.put("warnings", warnings);
                }
                out.put("codeAdvice", s);
            }
        } catch (RuntimeException ex) {
            out.put("warnings", List.of("응답 정규화 중 오류: " + ex.getClass().getSimpleName()));
        }
        return out;
    }
}

package com.heapdump.analyzer.service.llmguard;

/**
 * 규칙 일치 1건. 좌표는 검사한 텍스트 기준.
 *
 * <p>{@code start..end} 는 일치 전체 구간(스트림 자르기·겹침 판정용),
 * {@code replStart..replEnd} 는 그중 실제로 바꿔 쓰는 구간(named group {@code v} 가 있으면 그 그룹)이다.
 */
public final class Hit {
    final CompiledRule rule;
    final int start;
    final int end;
    final int replStart;
    final int replEnd;
    final String replacement;

    Hit(CompiledRule rule, int start, int end, int replStart, int replEnd, String replacement) {
        this.rule = rule;
        this.start = start;
        this.end = end;
        this.replStart = replStart;
        this.replEnd = replEnd;
        this.replacement = replacement;
    }

    /** 치환해도 글자가 그대로면(이미 가린 값) 세지도 바꾸지도 않는다. */
    boolean changes(CharSequence text) {
        if (replacement == null) return false;
        int len = replEnd - replStart;
        if (len != replacement.length()) return true;
        for (int i = 0; i < len; i++) {
            if (text.charAt(replStart + i) != replacement.charAt(i)) return true;
        }
        return false;
    }
}

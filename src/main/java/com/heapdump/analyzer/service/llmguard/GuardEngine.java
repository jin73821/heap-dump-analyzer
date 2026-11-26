package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.util.DeadlineCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 규칙 집합으로 텍스트를 훑고, 겹침을 정리하고, 가린 문자열을 만드는 순수 함수 모음.
 *
 * <p>겹침 정리는 <b>먼저 시작한 일치 → 긴 일치 → 규칙 순서</b>로 탐욕 선택한다. 뒤에서 시작한 일치가 앞의 일치를
 * 밀어내는 일이 없으므로, 스트림에서 자르는 지점 앞부분의 판정이 뒤에 올 텍스트에 의존하지 않는다.
 */
final class GuardEngine {

    private GuardEngine() {}

    /** 규칙 적용 범위. SAMPLE 은 기록용 발췌를 한 번 더 가릴 때 — 내장 키워드(단어 자체는 민감하지 않음)만 뺀다. */
    enum Mode { INPUT, OUTPUT, SAMPLE }

    static boolean applies(CompiledRule r, Mode mode) {
        if (!r.enabled) return false;
        switch (mode) {
            case INPUT:  return r.checkInput;
            case OUTPUT: return r.checkOutput;
            default:     return !r.isBuiltinKeyword();
        }
    }

    static final class Scan {
        final List<Hit> raw = new ArrayList<>();
        int incompleteAt = -1;
        final Set<CompiledRule> timedOut = new LinkedHashSet<>();
    }

    /**
     * 텍스트 {@code [from, length)} 를 모든 적용 규칙으로 훑는다. 시간을 넘긴 사용자 정규식은 그 규칙의 일치를
     * 통째로 버리고 {@code timedOut} 에 담는다(입력은 호출자가 CONFIRM 취급, 출력은 건너뜀).
     */
    static Scan scan(List<CompiledRule> rules, Mode mode, CharSequence text, int from, boolean atEnd,
                     Set<CompiledRule> skip) {
        Scan s = new Scan();
        int to = text.length();
        for (CompiledRule r : rules) {
            if (!applies(r, mode) || (skip != null && skip.contains(r))) continue;
            List<Hit> tmp = new ArrayList<>();
            try {
                int inc = r.scan(text, from, to, atEnd, tmp);
                if (inc >= 0 && (s.incompleteAt < 0 || inc < s.incompleteAt)) s.incompleteAt = inc;
                s.raw.addAll(tmp);
            } catch (DeadlineCharSequence.Timeout t) {
                s.timedOut.add(r);
            }
        }
        return s;
    }

    private static final Comparator<Hit> ORDER = Comparator
            .comparingInt((Hit h) -> h.start)
            .thenComparing(Comparator.comparingInt((Hit h) -> h.end).reversed())
            .thenComparingInt(h -> h.rule.sortOrder);

    /** 실제로 글자를 바꾸는 일치 중 {@code end <= limitEnd} 인 것을 겹치지 않게 고른다. */
    static List<Hit> resolve(List<Hit> raw, CharSequence text, int limitEnd) {
        List<Hit> cand = new ArrayList<>();
        for (Hit h : raw) {
            if (h.end <= limitEnd && h.changes(text)) cand.add(h);
        }
        cand.sort(ORDER);
        List<Hit> kept = new ArrayList<>();
        int lastEnd = Integer.MIN_VALUE;
        for (Hit h : cand) {
            if (h.start >= lastEnd) {
                kept.add(h);
                lastEnd = h.end;
            }
        }
        return kept;
    }

    /** 가린 결과 + 원문 좌표 → 가린 좌표 대응. */
    static final class Masked {
        final String text;
        final List<Hit> kept;
        private final int base;
        private final int[] mReplStart;
        private final int[] mReplEnd;

        Masked(String text, List<Hit> kept, int base, int[] mReplStart, int[] mReplEnd) {
            this.text = text;
            this.kept = kept;
            this.base = base;
            this.mReplStart = mReplStart;
            this.mReplEnd = mReplEnd;
        }

        /** 원문 좌표 p → 가린 문자열 좌표. 치환 구간 안이면 {@code atEnd} 에 따라 치환문 시작/끝. */
        int map(int p, boolean atEnd) {
            int delta = -base;
            for (int i = 0; i < kept.size(); i++) {
                Hit k = kept.get(i);
                if (p <= k.replStart) return p + delta;
                if (p < k.replEnd) return atEnd ? mReplEnd[i] : mReplStart[i];
                delta = mReplEnd[i] - k.replEnd;
            }
            return p + delta;
        }
    }

    /** {@code [from, to)} 를 kept 로 치환한 문자열. kept 는 정렬·비겹침·구간 안이어야 한다. */
    static Masked apply(CharSequence text, int from, int to, List<Hit> kept) {
        StringBuilder sb = new StringBuilder(to - from + 16);
        int[] ms = new int[kept.size()];
        int[] me = new int[kept.size()];
        int pos = from;
        for (int i = 0; i < kept.size(); i++) {
            Hit h = kept.get(i);
            sb.append(text, pos, h.replStart);
            ms[i] = sb.length();
            sb.append(h.replacement);
            me[i] = sb.length();
            pos = h.replEnd;
        }
        sb.append(text, pos, to);
        return new Masked(sb.toString(), kept, from, ms, me);
    }

    /** 한 번에 가리기(기록 없음). 시간 초과 규칙은 건너뛴다. */
    static Masked maskAll(List<CompiledRule> rules, Mode mode, CharSequence text, Set<CompiledRule> timedOutSink) {
        Scan s = scan(rules, mode, text, 0, true, null);
        if (timedOutSink != null) timedOutSink.addAll(s.timedOut);
        return apply(text, 0, text.length(), resolve(s.raw, text, text.length()));
    }

    static final int EXCERPT_SIDE = 20;
    static final int EXCERPT_MAX = 500;

    /**
     * 기록용 발췌 — 가린 문자열의 {@code [ms, me)} 앞뒤 20자. 창 가장자리에서 잘린 값이 그대로 남지 않게
     * SAMPLE 규칙으로 한 번 더 가리고, 줄바꿈은 공백으로 접는다. 원문은 여기로 들어오지 않는다.
     */
    static String excerpt(List<CompiledRule> rules, String masked, int ms, int me) {
        int a = Math.max(0, Math.min(ms, masked.length()) - EXCERPT_SIDE);
        int b = Math.min(masked.length(), Math.max(me, ms) + EXCERPT_SIDE);
        if (b < a) b = a;
        String w = masked.substring(a, b);
        w = maskAll(rules, Mode.SAMPLE, w, null).text;
        w = w.replaceAll("[\\r\\n\\t]+", " ");
        if (a > 0) w = "…" + w;
        if (b < masked.length()) w = w + "…";
        if (w.length() > EXCERPT_MAX) w = w.substring(0, EXCERPT_MAX - 1) + "…";
        return w;
    }
}

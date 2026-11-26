package com.heapdump.analyzer.service.llmguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 출력 가림 결과 — 규칙별 건수와 첫 발췌(가린 값).
 * 건수는 치환으로 글자가 <b>실제로 바뀐</b> 일치만 센다(이미 가린 값의 재시도 저장이 가짜 기록을 만들지 않게).
 */
public final class MaskResult {

    /** 문자열 가림 결과(문자열 1개를 가렸을 때만 채운다). */
    private String text;
    private final Map<CompiledRule, Integer> counts = new LinkedHashMap<>();
    private final Map<CompiledRule, String> samples = new LinkedHashMap<>();
    private final List<CompiledRule> timedOut = new ArrayList<>();

    public String text() { return text; }

    void setText(String t) { this.text = t; }

    void add(CompiledRule r, int n, String sample) {
        if (n <= 0) return;
        counts.merge(r, n, Integer::sum);
        if (sample != null) samples.putIfAbsent(r, sample);
    }

    void addTimedOut(CompiledRule r) {
        if (!timedOut.contains(r)) timedOut.add(r);
    }

    void merge(MaskResult o) {
        if (o == null) return;
        o.counts.forEach((r, n) -> add(r, n, o.samples.get(r)));
        o.timedOut.forEach(this::addTimedOut);
    }

    public int total() {
        int t = 0;
        for (int n : counts.values()) t += n;
        return t;
    }

    public boolean isEmpty() { return counts.isEmpty(); }

    Map<CompiledRule, Integer> counts()  { return counts; }
    Map<CompiledRule, String> samples()  { return samples; }
    List<CompiledRule> timedOut()        { return timedOut; }

    /** 앱 로그용 — {@code RRN×1,EMAIL×2}. */
    public String rulesSummary() {
        StringBuilder sb = new StringBuilder();
        counts.forEach((r, n) -> {
            if (sb.length() > 0) sb.append(',');
            sb.append(r.code()).append('×').append(n);
        });
        return sb.toString();
    }

    /** 화면 안내 — "개인정보로 보이는 3건을 가렸습니다(이메일 1·휴대전화 2)". */
    public String message() {
        StringBuilder sb = new StringBuilder();
        counts.forEach((r, n) -> {
            if (sb.length() > 0) sb.append('·');
            sb.append(r.name).append(' ').append(n);
        });
        return "개인정보로 보이는 " + total() + "건을 가렸습니다(" + sb + ")";
    }

    /** 클라이언트 응답용 {@code outputGuard} — 비어 있으면 null. 규칙명은 관리자 입력이라 Jackson 으로만 직렬화할 것. */
    public Map<String, Object> toOutputGuard() {
        if (counts.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", total());
        List<Map<String, Object>> items = new ArrayList<>();
        counts.forEach((r, n) -> {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("rule", r.name);
            it.put("category", r.category);
            it.put("count", n);
            items.add(it);
        });
        m.put("items", items);
        m.put("message", message());
        return m;
    }
}

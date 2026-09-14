package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link GcLogResult} ↔ JSON (Jackson 3). {@code gc_log_result_detail.result_json} 상한(4MB)을 넘으면 시계열·이벤트 표를
 * 절반씩 줄여 다시 직렬화하고 {@code RESULT_TRIMMED} 소견을 붙인다 — 요약 수치·소견은 절대 줄이지 않는다.
 */
public final class GcLogResultCodec {

    private GcLogResultCodec() {}

    public static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static String toJson(GcLogResult r) {
        return MAPPER.writeValueAsString(r);
    }

    /** 상한 안에 들어오도록 줄여 직렬화한다(원본 객체도 함께 줄어든다 — 화면과 저장본이 같아야 하므로 의도된 동작). */
    public static String toJsonCapped(GcLogResult r) {
        String s = toJson(r);
        int rounds = 0;
        boolean trimmed = false;
        while (s.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES && rounds++ < 8) {
            halveSeries(r);
            halveEvents(r);
            trimRaw(r);
            trimmed = true;
            s = toJson(r);
        }
        if (trimmed && r.getFindings().stream().noneMatch(f -> "RESULT_TRIMMED".equals(f.getCode()))) {
            GcLogResult.Finding f = new GcLogResult.Finding();
            f.setCode("RESULT_TRIMMED");
            f.setSeverity("Info");
            f.setTitle("저장 크기 상한으로 시계열·이벤트 표를 줄였습니다");
            f.setDetail("요약 수치와 소견은 전량 기준이며 그래프·표만 간략화됐습니다.");
            f.setAdvice("");
            r.getFindings().add(f);
            s = toJson(r);
        }
        return s;
    }

    public static GcLogResult fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, GcLogResult.class);
        } catch (RuntimeException e) { // JacksonException 은 unchecked
            return null;
        }
    }

    private static void halveSeries(GcLogResult r) {
        GcLogResult.Series s = r.getSeries();
        s.setUptimeSec(everyOther(s.getUptimeSec()));
        s.setHeapBefore(everyOther(s.getHeapBefore()));
        s.setHeapAfter(everyOther(s.getHeapAfter()));
        s.setHeapTotal(everyOther(s.getHeapTotal()));
        s.setPauseMs(everyOther(s.getPauseMs()));
        s.setType(everyOther(s.getType()));
        s.setMergeFactor(s.getMergeFactor() * 2);
    }

    private static void halveEvents(GcLogResult r) {
        List<GcLogResult.EventRow> ev = r.getEvents();
        if (ev.size() <= 200) return;
        List<GcLogResult.EventRow> keep = new ArrayList<>(ev.size() / 2 + 1);
        for (int i = 0; i < ev.size(); i++) {
            GcLogResult.EventRow e = ev.get(i);
            if ("FULL".equals(e.getType()) || GcFlag.anyAbnormal(e.getFlags()) || i % 2 == 0) keep.add(e);
        }
        if (keep.size() >= ev.size()) { keep = new ArrayList<>(); for (int i = 0; i < ev.size(); i += 2) keep.add(ev.get(i)); }
        r.setEvents(keep);
    }

    private static void trimRaw(GcLogResult r) {
        GcLogResult.RawSample raw = r.getRawSample();
        raw.setHead(firstN(raw.getHead(), 40));
        raw.setTail(firstN(raw.getTail(), 40));
    }

    private static <T> List<T> everyOther(List<T> in) {
        List<T> out = new ArrayList<>(in.size() / 2 + 1);
        for (int i = 0; i < in.size(); i += 2) out.add(in.get(i));
        return out;
    }

    private static <T> List<T> firstN(List<T> in, int n) {
        return in.size() <= n ? in : new ArrayList<>(in.subList(0, n));
    }
}

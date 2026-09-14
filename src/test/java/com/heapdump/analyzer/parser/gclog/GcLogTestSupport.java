package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** GC 로그 테스트 공용 — 이벤트를 모으는 sink, 파서 실행, 엔진 실행, 픽스처 조립. */
final class GcLogTestSupport {

    private GcLogTestSupport() {}

    static final class Recorder implements GcEventSink {
        final List<GcEvent> events = new ArrayList<>();
        final Map<String, String> meta = new LinkedHashMap<>();
        @Override public void onEvent(GcEvent event) { events.add(event); }
        @Override public void onMeta(String key, String value) { meta.put(key, value); }
    }

    static Recorder runUnified(String... lines) {
        Recorder rec = new Recorder();
        UnifiedGcLogParser p = new UnifiedGcLogParser(rec, 1_000_000);
        feed(p, lines);
        return rec;
    }

    static Recorder runJdk8(String... lines) {
        Recorder rec = new Recorder();
        Jdk8GcLogParser p = new Jdk8GcLogParser(rec, 1_000_000);
        feed(p, lines);
        return rec;
    }

    static void feed(GcLogParser p, String... lines) {
        int n = 0;
        for (String l : lines) for (String part : l.split("\n", -1)) p.feedLine(++n, part);
        p.finish();
    }

    static GcLogResult engine(String text) throws IOException {
        return GcLogEngine.analyze(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), ParseLimits.DEFAULT, null, null);
    }

    static GcLogResult engine(String text, ParseLimits limits, Long mtime) throws IOException {
        return GcLogEngine.analyze(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), limits, mtime, null);
    }

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(text.getBytes(StandardCharsets.UTF_8)); }
        return bos.toByteArray();
    }

    // ── 통합 로깅 픽스처 ─────────────────────────────────────────

    static String u(String ts, String up, String tags, String msg) {
        return "[" + ts + "][" + up + "][info][" + pad(tags) + "] " + msg;
    }

    private static String pad(String tags) {
        StringBuilder sb = new StringBuilder(tags);
        while (sb.length() < 12) sb.append(' ');
        return sb.toString();
    }

    /** G1 young pause 한 세트(start → heap → metaspace → 완료 → cpu). */
    static String g1Young(int id, String ts, double up, String kind, String cause, String sizes, String ms,
                          int edenB, int edenA, int oldB, int oldA, int humB, int humA) {
        String u = String.format(java.util.Locale.ROOT, "%.3fs", up);
        String u2 = String.format(java.util.Locale.ROOT, "%.3fs", up + Double.parseDouble(ms) / 1000.0);
        String head = "Pause Young (" + kind + ") (" + cause + ")";
        return String.join("\n",
                u(ts, u, "gc,start", "GC(" + id + ") " + head),
                u(ts, u2, "gc,heap", "GC(" + id + ") Eden regions: " + edenB + "->" + edenA + "(25)"),
                u(ts, u2, "gc,heap", "GC(" + id + ") Survivor regions: 0->2(2)"),
                u(ts, u2, "gc,heap", "GC(" + id + ") Old regions: " + oldB + "->" + oldA),
                u(ts, u2, "gc,heap", "GC(" + id + ") Humongous regions: " + humB + "->" + humA),
                u(ts, u2, "gc,metaspace", "GC(" + id + ") Metaspace: 1708K(1984K)->1708K(1984K) NonClass: 1530K(1664K)->1530K(1664K) Class: 178K(320K)->178K(320K)"),
                u(ts, u2, "gc", "GC(" + id + ") " + head + " " + sizes + " " + ms + "ms"),
                u(ts, u2, "gc,cpu", "GC(" + id + ") User=0.01s Sys=0.00s Real=0.01s"));
    }

    static final String TS = "2026-09-14T00:52:33.485+0900";
}

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

    // ── 합성 압박 픽스처 (2026-09-16) ──────────────────────────
    // src/test/resources/gclog/synthetic-jdk8-parallel-pressure.log 의 단일 출처. 파일을 다시 만들려면
    //   mvn test -Dtest=GcLogPressureFixtureTest -Dgclog.fixture.write=true
    // GcLogPressureFixtureTest.fixtureMatchesGenerator 가 파일과 이 생성기의 동일성을 고정한다.

    private static final long PRESSURE_DAY0 = GcLogSupport.parseIsoEpochMs("2026-09-16T09:00:00.000+0900");

    static String pressureTs(double uptimeSec) {
        long ms = PRESSURE_DAY0 + (long) (uptimeSec * 1000);
        return java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.ofHours(9))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.ROOT));
    }

    private static String pYoung(double up, String cause, long beforeK, long afterK, long totalK, double secs) {
        return pressureTs(up) + ": " + String.format(java.util.Locale.ROOT, "%.3f: [GC (%s) [PSYoungGen: %dK->%dK(%dK)] %dK->%dK(%dK), %.4f secs] [Times: user=%.2f sys=0.00, real=%.2f secs]",
                up, cause, beforeK / 2, afterK / 4, totalK / 3, beforeK, afterK, totalK, secs, secs * 2, secs);
    }

    private static String pFull(double up, String cause, long beforeK, long afterK, long totalK, double secs) {
        return pressureTs(up) + ": " + String.format(java.util.Locale.ROOT, "%.3f: [Full GC (%s) [PSYoungGen: 1000K->0K(30000K)] [ParOldGen: %dK->%dK(%dK)] %dK->%dK(%dK), [Metaspace: 61000K->61000K(1099776K)], %.4f secs] [Times: user=%.2f sys=0.00, real=%.2f secs]",
                up, cause, beforeK - 1000, afterK, totalK - 30000, beforeK, afterK, totalK, secs, secs * 2, secs);
    }

    /**
     * JDK 8 Parallel · 1GB 힙 · 2시간. Metaspace 임계치 Full 2(3s·8s) · System.gc() Young+Full 짝 2(3000s·7100s) ·
     * Ergonomics Full 8(간격 1500,1500,1500,600,300,300,300 — 회수율 25%→8%) · 마지막 Allocation Failure Full 1(6900s, 직후 92%).
     * 압박 Full 9 / Metaspace 2 / 명시적 2. 기대: FULL_GC_LOW_RECLAIM Critical · FULL_GC_INTERVAL_SHRINKING High · 종합 Critical.
     */
    static String pressureParallelLog() {
        long total = 1_048_576;
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("OpenJDK 64-Bit Server VM (25.392-b08) for linux-amd64 JRE (1.8.0_392-b08), built on Oct 17 2023 12:00:00 by \"mockbuild\" with gcc 8.5.0\n");
        sb.append("Memory: 4k page, physical 16000000k(8000000k free), swap 0k(0k free)\n");
        sb.append("CommandLine flags: -XX:InitialHeapSize=1073741824 -XX:MaxHeapSize=1073741824 -XX:+PrintGC -XX:+PrintGCDateStamps -XX:+PrintGCDetails -XX:+PrintGCTimeStamps -XX:+UseParallelGC\n");
        java.util.List<double[]> events = new java.util.ArrayList<>();   // {up, kind, i} — kind 0 young / 1 meta full / 2 sys pair / 3 ergonomics / 4 alloc-failure full
        events.add(new double[]{3.0, 1, 0});
        events.add(new double[]{8.0, 1, 1});
        for (int i = 0; i < 359; i++) events.add(new double[]{20.0 + i * 20.0, 0, i});
        events.add(new double[]{3000.0, 2, 0});
        events.add(new double[]{7100.0, 2, 1});
        double[] ergUp = {600, 2100, 3600, 5100, 5700, 6000, 6300, 6600};
        for (int i = 0; i < ergUp.length; i++) events.add(new double[]{ergUp[i], 3, i});
        events.add(new double[]{6900.0, 4, 0});
        events.sort((a, b) -> Double.compare(a[0], b[0]));
        double[] reclaimPct = {25, 22, 20, 17, 14, 12, 10, 8};
        for (double[] e : events) {
            double up = e[0]; int kind = (int) e[1]; int i = (int) e[2];
            switch (kind) {
                case 0: { long base = 100_000 + i * 1_700L; sb.append(pYoung(up, "Allocation Failure", base + 200_000, base, total, 0.012)).append('\n'); break; }
                case 1: sb.append(pFull(up, "Metadata GC Threshold", 50_000, 30_000, total, 0.080)).append('\n'); break;
                case 2: sb.append(pYoung(up, "System.gc()", 800_000, 700_000, total, 0.020)).append('\n');
                        sb.append(pFull(up + 0.05, "System.gc()", 700_000, 680_000, total, 0.400)).append('\n'); break;
                case 3: { long before = 980_000; long after = Math.round(before * (1 - reclaimPct[i] / 100.0)); sb.append(pFull(up, "Ergonomics", before, after, total, 0.9 + i * 0.1)).append('\n'); break; }
                default: sb.append(pFull(up, "Allocation Failure", 1_000_000, 964_000, total, 2.5)).append('\n'); break;
            }
        }
        return sb.toString();
    }
}

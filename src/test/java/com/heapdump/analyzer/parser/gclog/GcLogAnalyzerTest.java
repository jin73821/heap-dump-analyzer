package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.heapdump.analyzer.parser.gclog.GcLogTestSupport.engine;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 집계기·엔진 테스트 — 처리량·백분위·누수 회귀·할당률·소견 트리거·다운샘플·시간 출처·gz·상한.
 * 픽스처는 JDK 8 Parallel 한 줄 형식(합성이 쉽다)으로 만든다.
 */
class GcLogAnalyzerTest {

    private static final long DAY0 = GcLogSupport.parseIsoEpochMs("2026-09-14T00:00:00.000+0900");

    private static String ts(double uptimeSec) {
        long ms = DAY0 + (long) (uptimeSec * 1000);
        return java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.ofHours(9))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT));
    }

    private static String young(double up, long beforeK, long afterK, long totalK, double secs, boolean dated) {
        return (dated ? ts(up) + ": " : "") + String.format(Locale.ROOT, "%.3f: [GC (Allocation Failure) [PSYoungGen: %dK->%dK(%dK)] %dK->%dK(%dK), %.4f secs] [Times: user=%.2f sys=0.00, real=%.2f secs]",
                up, beforeK / 2, afterK / 4, totalK / 3, beforeK, afterK, totalK, secs, secs * 2, secs);
    }

    private static String full(double up, long beforeK, long afterK, long totalK, double secs, boolean dated, String cause) {
        return (dated ? ts(up) + ": " : "") + String.format(Locale.ROOT, "%.3f: [Full GC (%s) [PSYoungGen: 1000K->0K(30000K)] [ParOldGen: %dK->%dK(%dK)] %dK->%dK(%dK), [Metaspace: 3000K->3000K(1056768K)], %.4f secs] [Times: user=%.2f sys=0.00, real=%.2f secs]",
                up, cause, beforeK - 1000, afterK, totalK - 30000, beforeK, afterK, totalK, secs, secs * 2, secs);
    }

    /** 1시간 동안 10초마다 young(10ms) + 10분마다 Full — after-Full 이 leak 이면 선형 증가. */
    private static String hourLog(boolean leak, boolean dated) {
        StringBuilder sb = new StringBuilder();
        long total = 1_000_000;
        for (int i = 0; i < 360; i++) {
            double up = 10.0 + i * 10.0;
            long base = leak ? 100_000 + i * 1_000 : 100_000;
            sb.append(young(up, base + 200_000, base, total, 0.010, dated)).append('\n');
            if (i % 60 == 59) sb.append(full(up + 5, base + 200_000, base, total, 0.500, dated, "Ergonomics")).append('\n');
        }
        return sb.toString();
    }

    private static Optional<GcLogResult.Finding> finding(GcLogResult r, String code) {
        return r.getFindings().stream().filter(f -> code.equals(f.getCode())).findFirst();
    }

    @Test
    void throughputPausePercentilesAndFullGcRateAreComputed() throws Exception {
        GcLogResult r = engine(hourLog(false, true));
        GcLogResult.Kpi k = r.getKpi();
        assertEquals(366, k.getEventCount());
        assertEquals(360, k.getYoungCount());
        assertEquals(6, k.getFullCount());
        assertEquals("Parallel", r.getMeta().getCollector());
        assertEquals("absolute", r.getMeta().getTimeSource());
        assertEquals("JDK8", r.getMeta().getFormat());
        // 기간 = 마지막 uptime(3605) − 첫(10) = 3595s, pause 합 = 360×10ms + 6×500ms = 6600ms
        assertEquals(3595.0, r.getMeta().getDurationSec(), 1e-6);
        assertEquals(100.0 * (1 - 6.6 / 3595.0), k.getThroughputPct(), 1e-6);
        assertEquals(6 / (3595.0 / 3600.0), k.getFullGcPerHour(), 1e-6);
        GcLogResult.PauseStats ps = r.getPauseStats();
        assertEquals(366, ps.getCount());
        assertEquals(500.0, ps.getMaxMs(), 1e-9);
        assertEquals(10.0, ps.getP50Ms(), 1e-9);
        assertEquals(10.0, ps.getP95Ms(), 1e-9, "Full 6건은 상위 1.6% — p95 는 young");
        assertEquals(500.0, ps.getP99Ms(), 1e-9);
        assertFalse(ps.isApproximate());
        assertEquals(500.0, r.getFullPauseStats().getMaxMs(), 1e-9);
        assertEquals(6, r.getFullPauseStats().getCount());
        assertEquals(1_000_000L << 10, k.getMaxHeapTotalBytes());
        assertEquals(DAY0 + 10_000L, r.getMeta().getLogStartEpochMs());
        assertNotNull(k.getAllocationRateMbPerSec());
        assertTrue(k.getAllocationRateMbPerSec() > 0);
        assertEquals("full", r.getTrend().getBasis());
        assertEquals(6, r.getTrend().getAfterPoints().size());
        assertTrue(Math.abs(r.getTrend().getSlopeMbPerHour()) < 1e-6, "누수 없음 → 기울기 0");
        assertTrue(finding(r, "LEAK_TREND").isEmpty());
        assertTrue(finding(r, "FULL_GC_FREQUENT").isPresent(), "시간당 6.008회 → Critical (>6)");
        assertEquals("Critical", finding(r, "FULL_GC_FREQUENT").get().getSeverity());
        assertEquals("Critical", k.getSeverity());
    }

    @Test
    void leakTrendIsDetectedFromAfterFullGcRegression() throws Exception {
        GcLogResult r = engine(hourLog(true, true));
        GcLogResult.Trend t = r.getTrend();
        assertNotNull(t.getSlopeMbPerHour());
        // after-Full: 100000K + i*1000K, i=59,119,…,359 → 1000K/10s… 즉 (1000KB × 6/min) = 360000KB/h ≈ 351.6 MB/h
        assertEquals(360_000.0 / 1024.0, t.getSlopeMbPerHour(), 1.0);
        assertEquals(1.0, t.getR2(), 1e-6);
        Optional<GcLogResult.Finding> leak = finding(r, "LEAK_TREND");
        assertTrue(leak.isPresent());
        assertEquals("Medium", leak.get().getSeverity(), "마지막 after-Full 459MB / 1000MB = 46% → Medium");
        assertTrue(leak.get().getDetail().contains("MB/h"));
    }

    @Test
    void uptimeOnlyLogUsesMtimeForTimeRangeAndFlagsIt() throws Exception {
        long mtime = 1_800_000_000L;
        GcLogResult r = engine(hourLog(false, false), ParseLimits.DEFAULT, mtime);
        assertEquals("mtime", r.getMeta().getTimeSource());
        assertEquals(mtime * 1000L, r.getMeta().getLogEndEpochMs());
        assertEquals(mtime * 1000L - 3_595_000L, r.getMeta().getLogStartEpochMs());
        assertTrue(finding(r, "TIME_FROM_MTIME").isPresent());

        GcLogResult none = engine(hourLog(false, false), ParseLimits.DEFAULT, null);
        assertEquals("none", none.getMeta().getTimeSource());
        assertNull(none.getMeta().getLogStartEpochMs());
        assertTrue(finding(none, "NO_ABSOLUTE_TIME").isPresent());
    }

    @Test
    void seriesIsDownsampledToAtMost2000PointsAndEventsAreCapped() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9000; i++) sb.append(young(1.0 + i, 300_000, 100_000, 1_000_000, 0.010, false)).append('\n');
        GcLogResult r = engine(sb.toString());
        assertEquals(9000, r.getKpi().getEventCount());
        int n = r.getSeries().getUptimeSec().size();
        assertTrue(n <= GcLogAnalyzer.SERIES_CAP && n >= 1000, "n=" + n);
        assertEquals(4, r.getSeries().getMergeFactor(), "9000 → 4500 → 2250 → (mergeFactor 4)");
        assertEquals(GcLogAnalyzer.HEAD_CAP + GcLogAnalyzer.TAIL_CAP, r.getEvents().size());
        assertEquals(0, r.getEvents().get(0).getSeq());
        assertEquals(8999, r.getEvents().get(r.getEvents().size() - 1).getSeq());
        assertEquals(GcLogEngine.HEAD_LINES, r.getRawSample().getHead().size());
        assertEquals(GcLogEngine.TAIL_LINES, r.getRawSample().getTail().size());
    }

    @Test
    void flagFindingsFireFromUnifiedG1Events() throws Exception {
        String ts = "2026-09-14T00:52:33.485+0900";
        List<String> lines = new ArrayList<>();
        lines.add(GcLogTestSupport.u(ts, "0.006s", "gc,init", "Heap Region Size: 1M"));
        lines.add(GcLogTestSupport.u(ts, "0.003s", "gc", "Using G1"));
        for (int i = 0; i < 6; i++) {
            double up = 10 + i * 10;
            lines.add(GcLogTestSupport.u(ts, up + "s", "gc,start", "GC(" + i + ") Pause Young (Normal) (G1 Humongous Allocation)"));
            lines.add(GcLogTestSupport.u(ts, up + "s", "gc", "GC(" + i + ") To-space exhausted"));
            lines.add(GcLogTestSupport.u(ts, up + "s", "gc,heap", "GC(" + i + ") Humongous regions: 40->40"));
            lines.add(GcLogTestSupport.u(ts, (up + 2) + "s", "gc", "GC(" + i + ") Pause Young (Normal) (G1 Humongous Allocation) 250M->240M(258M) 2000.000ms"));
        }
        lines.add(GcLogTestSupport.u(ts, "100s", "gc,start", "GC(9) Pause Full (System.gc())"));
        lines.add(GcLogTestSupport.u(ts, "101s", "gc", "GC(9) Pause Full (System.gc()) 240M->230M(258M) 1000.000ms"));
        GcLogResult r = engine(String.join("\n", lines));
        assertEquals("G1", r.getMeta().getCollector());
        assertEquals("Critical", finding(r, "TO_SPACE_EXHAUSTED").get().getSeverity());
        assertTrue(finding(r, "HUMONGOUS_HEAVY").isPresent(), "40 리전 × 1M = 40MB ≥ 258M 의 10%");
        assertTrue(finding(r, "SYSTEM_GC").isPresent());
        assertTrue(finding(r, "LONG_PAUSE").isPresent());
        assertTrue(finding(r, "HEAP_NEAR_MAX").isPresent(), "마지막 GC 후 230/258 = 89%");
        assertEquals("Critical", r.getKpi().getSeverity());
        assertEquals(6, r.getCounts().getByFlag().get("TO_SPACE_EXHAUSTED"));
        assertEquals("Critical", r.getFindings().get(0).getSeverity(), "심각도 내림차순 정렬");
        assertEquals(GcLogEngine.AROUND_LINES * 2 + 1 >= r.getRawSample().getAroundMaxPause().size(), true);
        assertNotNull(r.getRawSample().getAroundMaxPauseFirstLine());
    }

    @Test
    void gzipInputIsTransparentAndLimitsAreEnforced() throws Exception {
        String text = hourLog(false, true);
        GcLogResult r = GcLogEngine.analyze(new ByteArrayInputStream(GcLogTestSupport.gzip(text)), ParseLimits.DEFAULT, null, null);
        assertEquals(366, r.getKpi().getEventCount());
        assertTrue(r.getMeta().getBytes() < text.length(), "진행 바이트는 압축 원본 기준");

        GcLogEngine.GcLogException tooLarge = assertThrows(GcLogEngine.GcLogException.class,
                () -> GcLogEngine.analyze(new ByteArrayInputStream(GcLogTestSupport.gzip(text)), new ParseLimits(10_000, 4096, 1000), null, null));
        assertEquals("TOO_LARGE", tooLarge.code(), "gz 해제 바이트 기준 상한");

        GcLogEngine.GcLogException notGc = assertThrows(GcLogEngine.GcLogException.class,
                () -> engine("2026-09-14 00:52:33 INFO application log\nnothing here\n"));
        assertEquals("NOT_GC_LOG", notGc.code());

        GcLogResult limited = engine(text, new ParseLimits(Long.MAX_VALUE, 4096, 100), null);
        assertEquals(100, limited.getKpi().getEventCount());
        assertTrue(limited.getMeta().isTruncated());
        assertTrue(finding(limited, "LOG_TRUNCATED_LIMIT").isPresent());

        GcLogResult cut = engine(text, new ParseLimits(Long.MAX_VALUE, 60, 100000), null);
        assertTrue(cut.getMeta().getTruncatedLines() > 0);
    }

    @Test
    void startupWarmupDoesNotBecomeLeakTrend() throws Exception {
        // 2026-09-14 실제 오탐 모양: 기동 2~16초에 Full GC 직후 힙이 16→152MB 로 자라고(클래스 로딩), 이후 36분 조용하다.
        // 종전에는 14초 폭 기울기를 시간 단위로 환산해 '+40,390 MB/h 누수 의심' 을 냈다.
        StringBuilder sb = new StringBuilder();
        long[] afterMb = {16, 24, 36, 103, 107, 151, 152};
        double[] up = {2.3, 4.8, 7.5, 11.4, 12.6, 13.8, 16.2};
        for (int i = 0; i < up.length; i++) sb.append(full(up[i], afterMb[i] * 1024 + 50_000, afterMb[i] * 1024, 262_144, 0.010, true, "Metadata GC Threshold")).append('\n');
        for (int i = 0; i < 20; i++) sb.append(young(30 + i * 100, 200_000, 160_000, 262_144, 0.005, true)).append('\n');
        GcLogResult r = engine(sb.toString());
        GcLogResult.Trend t = r.getTrend();
        assertEquals("full", t.getBasis());
        assertEquals(7, t.getAfterPoints().size(), "차트에는 전체 점을 그대로 둔다");
        assertEquals(7, t.getExcludedWarmup());
        assertEquals(0, t.getPointsUsed());
        assertNull(t.getSlopeMbPerHour(), "기동 직후 점만으로는 추세를 내지 않는다");
        assertNotNull(t.getNote());
        assertTrue(t.getNote().contains("기동 직후"), t.getNote());
        assertTrue(finding(r, "LEAK_TREND").isEmpty(), "워밍업은 누수가 아니다");
    }

    @Test
    void warmupPointsAreExcludedButLaterLeakIsStillDetected() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(full(20, 300_000, 200_000, 1_000_000, 0.2, true, "Metadata GC Threshold")).append('\n');   // 워밍업 — 제외
        for (int i = 0; i < 6; i++) {
            double upSec = 600 + i * 600;          // 10분 간격 6점, 폭 50분
            long after = 100_000 + i * 50_000;     // 10분마다 +50MB ≈ +293 MB/h
            sb.append(full(upSec, after + 100_000, after, 1_000_000, 0.3, true, "Ergonomics")).append('\n');
        }
        GcLogResult r = engine(sb.toString());
        GcLogResult.Trend t = r.getTrend();
        assertEquals(1, t.getExcludedWarmup());
        assertEquals(6, t.getPointsUsed());
        assertEquals(3000.0, t.getSpanSec(), 1e-6);
        assertEquals(50_000.0 * 6 / 1024.0, t.getSlopeMbPerHour(), 0.5, "워밍업 점(200MB)을 넣었으면 기울기가 왜곡된다");
        assertEquals(1.0, t.getR2(), 1e-6);
        assertNull(t.getNote());
        assertEquals((double) (100_000L << 10), t.getFirstAfterBytes(), 1, "시작점도 워밍업 이후 첫 점");
        assertTrue(finding(r, "LEAK_TREND").isPresent());
        assertTrue(finding(r, "LEAK_TREND").get().getDetail().contains("기동 직후 1점은 제외"));
    }

    @Test
    void pointsClusteredInShortSpanDoNotProduceSlope() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(full(1000 + i * 60, 400_000 + i * 50_000, 300_000 + i * 50_000, 1_000_000, 0.2, true, "Ergonomics")).append('\n');
        sb.append(young(5000, 500_000, 400_000, 1_000_000, 0.01, true)).append('\n');
        GcLogResult r = engine(sb.toString());
        GcLogResult.Trend t = r.getTrend();
        assertEquals(5, t.getPointsUsed());
        assertEquals(240.0, t.getSpanSec(), 1e-6);
        assertNull(t.getSlopeMbPerHour(), "4분 폭 — 시간당 기울기로 환산하면 허수가 된다");
        assertTrue(t.getNote().contains("구간에 몰려"), t.getNote());
        assertTrue(finding(r, "LEAK_TREND").isEmpty());
    }

    @Test
    void regressionHelperIsExact() {
        List<double[]> pts = List.of(new double[]{0, 0}, new double[]{3600, 1048576}, new double[]{7200, 2097152});
        double[] reg = GcLogAnalyzer.regression(pts);
        assertEquals(1.0, reg[0], 1e-9, "1 MB/h");
        assertEquals(1.0, reg[1], 1e-9);
        assertEquals(0.0, GcLogAnalyzer.regression(List.of(new double[]{1, 1}, new double[]{1, 2}))[0]);
    }

    // ── JDK 6/7 CMS (JEUS 8 운영 로그 형식) ───────────────────────────────────

    /** 6초 주기 CMS 사이클 n회 — Initial Mark 시점 Old 점유량 oldK / 1572864K. 사이클 5번째 뒤에 [GC [ParNew][CMS] 전체 수집. */
    private static String legacyCmsLog(int cycles, long oldK, boolean withFull) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cycles; i++) {
            double up = 117326046.772 + i * 6.0;
            sb.append(ts(up)).append(String.format(Locale.ROOT, ": %.3f: [GC [1 CMS-initial-mark: %dK(1572864K)] %dK(2044736K), 0.3811460 secs] [Times: user=0.35 sys=0.00, real=0.38 secs]\n", up, oldK, oldK + 467666));
            sb.append(ts(up + 0.4)).append(String.format(Locale.ROOT, ": %.3f: [CMS-concurrent-mark-start]\n", up + 0.4));
            sb.append(ts(up + 2.0)).append(String.format(Locale.ROOT, ": %.3f: [CMS-concurrent-mark: 1.607/1.607 secs] [Times: user=1.55 sys=0.02, real=1.61 secs]\n", up + 2.0));
            sb.append(ts(up + 2.1)).append(String.format(Locale.ROOT, ": %.3f: [GC[YG occupancy: 467770 K (471872 K)]%.3f: [Rescan (parallel) , 0.3254290 secs]%.3f: [weak refs processing, 0.0000160 secs] [1 CMS-remark: %dK(1572864K)] %dK(2044736K), 0.3698110 secs] [Times: user=0.81 sys=0.02, real=0.37 secs]\n", up + 2.1, up + 2.1, up + 2.4, oldK, oldK + 467770));
            sb.append(ts(up + 3.5)).append(String.format(Locale.ROOT, ": %.3f: [CMS-concurrent-sweep: 1.182/1.182 secs] [Times: user=1.10 sys=0.01, real=1.19 secs]\n", up + 3.5));
            if (withFull && i == 4) {
                sb.append(ts(up + 4.5)).append(String.format(Locale.ROOT, ": %.3f: [GC %.3f: [ParNew: 471872K->471872K(471872K), 0.0000280 secs]%.3f: [CMS: 1572858K->1572858K(1572864K), 4.1819420 secs] 2044730K->2032970K(2044736K), [CMS Perm : 120715K->120714K(1048576K)], 4.1823090 secs] [Times: user=3.87 sys=0.05, real=4.19 secs]\n", up + 4.5, up + 4.5, up + 4.5));
            }
        }
        return sb.toString();
    }

    @Test
    void legacyCmsOldSaturationIsCriticalAndFullCollectionIsCounted() throws Exception {
        GcLogResult r = engine(legacyCmsLog(8, 1572858, true));
        assertEquals("JDK8", r.getMeta().getFormat());
        assertEquals("CMS", r.getMeta().getCollector());
        assertEquals(Boolean.TRUE, r.getMeta().getPermGen());
        assertEquals("1.7 이하(PermGen)", r.getMeta().getJdkVersion(), "헤더 없는 회전 로그 — PermGen 라벨로 세대를 알린다");
        assertEquals(1, r.getKpi().getFullCount());
        assertEquals(0, r.getKpi().getYoungCount(), "Initial Mark·Remark 가 Young 으로 섞이면 Young 일시정지 통계가 수백 ms 로 부푼다");
        assertEquals(8, r.getCounts().getByType().get("CMS_INITIAL_MARK"));
        assertEquals(8, r.getCounts().getByType().get("CMS_FINAL_REMARK"));
        assertEquals(2044736L << 10, r.getKpi().getMaxHeapTotalBytes());
        assertEquals(120714L << 10, r.getKpi().getMetaspaceLastBytes());

        GcLogResult.Finding sat = finding(r, "CMS_OLD_SATURATED").orElseThrow();
        assertEquals("Critical", sat.getSeverity());
        assertEquals(8, sat.getEvidence().get("maxConsecutive"));
        assertEquals(6.0, (Double) sat.getEvidence().get("avgIntervalSec"), 1e-6);
        assertTrue(sat.getDetail().contains("8회 중 8회"), sat.getDetail());
        assertEquals("Critical", r.getKpi().getSeverity());
        assertTrue(finding(r, "LONG_PAUSE").isPresent());
        assertEquals("High", finding(r, "HEAP_NEAR_MAX").orElseThrow().getSeverity(), "Full 직후 99%");
    }

    @Test
    void healthyCmsCyclesDoNotRaiseSaturation() throws Exception {
        GcLogResult r = engine(legacyCmsLog(10, 1_100_000, false));   // 70%
        assertTrue(finding(r, "CMS_OLD_SATURATED").isEmpty());
        GcLogResult few = engine(legacyCmsLog(2, 1572858, false));
        assertTrue(finding(few, "CMS_OLD_SATURATED").isEmpty(), "연속 3회 미만은 판단하지 않는다");
        GcLogResult three = engine(legacyCmsLog(3, 1572858, false));
        assertEquals("High", finding(three, "CMS_OLD_SATURATED").orElseThrow().getSeverity());
    }
}

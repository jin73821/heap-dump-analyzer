package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static com.heapdump.analyzer.parser.gclog.GcLogMultiSourceEngineTest.full;
import static com.heapdump.analyzer.parser.gclog.GcLogMultiSourceEngineTest.header;
import static com.heapdump.analyzer.parser.gclog.GcLogMultiSourceEngineTest.young;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이어 붙인 로그의 시간축·실행 구분·구간 지표 (2026-09-20).
 *
 * <p>이 서버 실측처럼 회전본이 곧 별개 JVM 실행이면 uptime 이 0 으로 되감긴다. 그대로 x 축에 쓰면 10분 창이
 * 비지 않고 할당률 구간이 누락되며 차트가 되돌아 그어진다(함정 61). 더 위험한 것은 <b>누수 오탐</b>이다 —
 * x 기준이 절대 시각으로 바뀌면 {@code WARMUP_SEC} 필터가 통째로 꺼져 실행마다의 기동 램프가 전부 회귀에
 * 들어간다(2026-09-14 의 {@code +40,390 MB/h} 오탐, 함정 57 이 구간 수만큼 증폭된 꼴).
 */
class GcLogSegmentAnalyzerTest {

    private static final long T0 = GcLogSupport.parseIsoEpochMs("2026-09-19T22:00:00.000+0900");
    private static final long HOUR = 3_600_000L;

    /** 한 실행의 Full GC 한 줄 — 실행 시작 시각 + uptime 으로 절대 시각을 만든다. */
    private static String fullAt(long runStartMs, double up, long afterMb) {
        return full(runStartMs + (long) (up * 1000), up, afterMb * 1024 + 2048, afterMb * 1024);
    }

    private static String youngAt(long runStartMs, double up, long beforeMb, long afterMb) {
        return young(runStartMs + (long) (up * 1000), up, beforeMb * 1024, afterMb * 1024);
    }

    /** 절대 시각 없이 uptime 만 있는 JDK8 줄(-XX:+PrintGCDateStamps 미지정). */
    private static String youngUptimeOnly(double up) {
        return String.format(Locale.ROOT,
                "%.3f: [GC (Allocation Failure) [PSYoungGen: 524800K->39141K(611840K)] 500000K->40000K(2010112K), "
                        + "0.0500000 secs] [Times: user=0.10 sys=0.01, real=0.05 secs]", up);
    }

    private static GcLogResult run(String... members) throws IOException {
        List<GcLogSource> src = new java.util.ArrayList<>();
        for (int i = 0; i < members.length; i++) src.add(GcLogTestSupport.src("gc.log." + i, members[i]));
        return GcLogTestSupport.engineMulti(src);
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("재기동이 섞이면 실행을 나누고, x 축은 절대 시각 기준이라 되돌아가지 않는다")
    void restartMakesNewRunAndXStaysMonotonic() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 10; i++) a.append(youngAt(T0, 10 + i * 60.0, 500, 40)).append('\n');
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 10; i++) b.append(youngAt(T0 + HOUR, 10 + i * 60.0, 500, 40)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        assertEquals("absolute", r.getMeta().getXBasis());
        assertEquals(2, r.getMeta().getRunCount(), "uptime 이 되돌아갔는데 같은 실행으로 봤다");
        assertEquals(0, r.getSegments().get(0).getRunIndex());
        assertEquals(1, r.getSegments().get(1).getRunIndex());

        List<Double> xs = r.getSeries().getUptimeSec();
        for (int i = 1; i < xs.size(); i++) {
            assertTrue(xs.get(i) >= xs.get(i - 1),
                    "x 가 되돌아갔다 (" + xs.get(i - 1) + " → " + xs.get(i) + ") — 차트 선이 거꾸로 그어진다");
        }
        // 두 번째 실행의 x 는 첫 실행 시작으로부터의 경과초여야 한다(1시간 = 3600초 근방)
        assertTrue(xs.get(xs.size() - 1) > 3600, "두 번째 실행이 첫 실행 위에 겹쳐 그려졌다: " + xs.get(xs.size() - 1));
    }

    @Test
    @DisplayName("기간은 조각이 실제로 덮은 시간의 합이고, 공백은 따로 보고한다")
    void durationIsSumOfCoveredSpans() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i <= 10; i++) a.append(youngAt(T0, 10 + i * 10.0, 500, 40)).append('\n');           // 100초
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i <= 10; i++) b.append(youngAt(T0 + HOUR, 10 + i * 10.0, 500, 40)).append('\n');    // 100초

        GcLogResult r = run(a.toString(), b.toString());
        GcLogResult.Meta m = r.getMeta();

        assertEquals(200.0, m.getDurationSec(), 1.0, "공백(JVM 정지)이 기간에 들어갔다");
        assertEquals(200.0, m.getCoveredSec(), 1.0);
        assertEquals(3700.0, m.getSpanSec(), 5.0, "전체 범위(첫 시각~끝 시각)가 맞지 않는다");
        assertEquals(3500.0, m.getGapSec(), 5.0, "공백을 보고하지 않는다");
        assertTrue(r.getFindings().stream().anyMatch(f -> "SEGMENT_GAP".equals(f.getCode())), "공백 안내 소견이 없다");
    }

    @Test
    @DisplayName("처리량·Full GC 빈도의 분모는 공백을 뺀 실제 기록 시간이다")
    void ratesUseCoveredDurationNotSpan() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 6; i++) a.append(fullAt(T0, 400 + i * 100.0, 900)).append('\n');           // 500초, Full 6회
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 6; i++) b.append(fullAt(T0 + HOUR, 400 + i * 100.0, 900)).append('\n');    // 500초, Full 6회

        GcLogResult r = run(a.toString(), b.toString());

        double covered = r.getMeta().getDurationSec();
        assertEquals(1000.0, covered, 5.0);
        // 12회 / (1000초=0.2778시간) ≈ 43.2/h — span(4100초) 로 나누면 10.5/h 로 실제보다 낙관적이 된다
        assertEquals(12 / (covered / 3600.0), r.getKpi().getFullGcPerHour(), 0.5,
                "Full GC 빈도가 공백을 포함한 범위로 계산됐다");
    }

    @Test
    @DisplayName("누수 추세는 가장 긴 실행 안에서만, 실행마다의 기동 램프는 빼고 잰다")
    void trendUsesLongestRunAndExcludesPerRunWarmup() throws IOException {
        // 두 실행 모두 기동 직후 100→900MB 로 치솟는 램프(정상적인 클래스 로딩·캐시 워밍업)를 갖는다.
        // 그 뒤는 평탄하다 — 누수가 아니다. 램프를 빼지 않으면 '누수 의심' 오탐이 난다.
        StringBuilder a = new StringBuilder(header(2147483648L));
        long[] ramp = {100, 300, 500, 700, 900};
        for (int i = 0; i < ramp.length; i++) a.append(fullAt(T0, 10 + i * 60.0, ramp[i])).append('\n');
        for (int i = 0; i < 3; i++) a.append(fullAt(T0, 400 + i * 300.0, 900)).append('\n');            // 짧은 실행(~1000초)

        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < ramp.length; i++) b.append(fullAt(T0 + HOUR, 10 + i * 60.0, ramp[i])).append('\n');
        for (int i = 0; i < 8; i++) b.append(fullAt(T0 + HOUR, 400 + i * 500.0, 900)).append('\n');     // 긴 실행(~3900초)

        GcLogResult r = run(a.toString(), b.toString());
        GcLogResult.Trend t = r.getTrend();

        assertEquals(2, r.getMeta().getRunCount());
        assertEquals(10, t.getExcludedWarmup(), "기동 램프 점을 실행별로 빼지 않았다 — 누수 오탐의 원인");
        assertEquals(8, t.getPointsUsed(), "가장 긴 실행 밖의 점까지 회귀에 넣었다");
        assertNotNull(t.getNote());
        assertTrue(t.getNote().contains("가장 긴 구간"), "어느 구간으로 쟀는지 밝히지 않는다: " + t.getNote());
        assertFalse(r.getFindings().stream().anyMatch(f -> "LEAK_TREND".equals(f.getCode())),
                "평탄한 로그에서 누수 의심이 났다(기동 램프가 회귀에 들어간 것)");
    }

    @Test
    @DisplayName("한 실행 안의 진짜 증가는 그대로 잡는다 — 워밍업 제외가 누수를 가리지 않는다")
    void realLeakInsideOneRunIsStillDetected() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 4; i++) a.append(fullAt(T0, 10 + i * 60.0, 100 + i * 50L)).append('\n');   // 워밍업(제외)
        for (int i = 0; i < 12; i++) a.append(fullAt(T0, 400 + i * 600.0, 300 + i * 120L)).append('\n'); // 꾸준한 증가
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 3; i++) b.append(fullAt(T0 + 4 * HOUR, 10 + i * 60.0, 100)).append('\n');   // 짧은 재기동

        GcLogResult r = run(a.toString(), b.toString());

        assertTrue(r.getTrend().getSlopeMbPerHour() != null && r.getTrend().getSlopeMbPerHour() > 0,
                "증가 추세를 계산하지 않았다");
        assertTrue(r.getFindings().stream().anyMatch(f -> "LEAK_TREND".equals(f.getCode())),
                "실제 증가(1.4GB 까지 상승)를 누수로 잡지 못했다");
    }

    @Test
    @DisplayName("재기동을 지나도 10분 창 GC 비중과 할당률이 오염되지 않는다")
    void windowAndAllocationSurviveRestart() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 20; i++) a.append(youngAt(T0, 10 + i * 30.0, 500, 40)).append('\n');
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 20; i++) b.append(youngAt(T0 + HOUR, 10 + i * 30.0, 500, 40)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        Double overhead = r.getKpi().getMaxOverheadPct10m();
        assertTrue(overhead == null || overhead <= 100.0, "10분 창이 비지 않아 GC 비중이 100% 를 넘었다: " + overhead);
        Double alloc = r.getKpi().getAllocationRateMbPerSec();
        // 한 실행 안의 할당률은 (500-40)MB / 30초 ≈ 15MB/s. 재기동 경계를 끼워 넣으면 남의 힙과 빼 셈이 되어 튄다.
        assertTrue(alloc == null || alloc < 30.0, "재기동 경계가 할당률에 들어갔다: " + alloc);
    }

    @Test
    @DisplayName("기간이 겹치는 조각은 이중 계수 경고를 낸다")
    void overlappingMembersRaiseFinding() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 6; i++) a.append(youngAt(T0, 10 + i * 60.0, 500, 40)).append('\n');
        // 같은 실행의 같은 구간을 다시 담은 조각(사본을 함께 고른 경우)
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(youngAt(T0, 100 + i * 60.0, 500, 40)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        assertTrue(r.getFindings().stream().anyMatch(f -> "SEGMENT_OVERLAP".equals(f.getCode())),
                "겹치는 구간을 경고하지 않는다 — 횟수·처리량이 조용히 거짓이 된다");
    }

    @Test
    @DisplayName("수집기·힙 크기가 다르면 다른 JVM 의 로그가 섞였다고 경고한다")
    void mixedSourcesRaiseFinding() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 4; i++) a.append(youngAt(T0, 10 + i * 60.0, 500, 40)).append('\n');
        StringBuilder b = new StringBuilder(header(8589934592L));   // 힙 최대 크기가 다르다
        for (int i = 0; i < 4; i++) b.append(youngAt(T0 + HOUR, 10 + i * 60.0, 500, 40)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        assertTrue(r.getFindings().stream().anyMatch(f -> "MIXED_SOURCES".equals(f.getCode())),
                "출처가 다른 로그를 섞었는데 경고가 없다");
    }

    @Test
    @DisplayName("절대 시각이 없는 조각은 순서대로 이어 붙이고 공백을 모른다고 밝힌다")
    void uptimeOnlyMembersAreStackedAndFlagged() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 6; i++) a.append(youngUptimeOnly(10 + i * 10.0)).append('\n');
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 6; i++) b.append(youngUptimeOnly(10 + i * 10.0)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        assertEquals("stacked", r.getMeta().getXBasis());
        assertEquals(Boolean.TRUE, r.getMeta().getGapUnknown(), "공백을 모른다는 표시가 없다");
        List<Double> xs = r.getSeries().getUptimeSec();
        for (int i = 1; i < xs.size(); i++) assertTrue(xs.get(i) >= xs.get(i - 1), "이어 붙인 x 가 되돌아갔다");
    }

    @Test
    @DisplayName("이상 이벤트 표는 조각별 몫으로 나눈다 — 앞 조각이 상한을 독식하지 않는다")
    void flaggedRowsAreQuotaedPerSegment() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 2100; i++) a.append(fullAt(T0, 400 + i * 2.0, 900)).append('\n');   // 상한(2000) 초과
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 5; i++) b.append(fullAt(T0 + 2 * HOUR, 400 + i * 100.0, 900)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        long fromLast = r.getEvents().stream().filter(e -> e.getSeg() != null && e.getSeg() == 1).count();
        assertEquals(5, fromLast,
                "마지막 조각의 Full GC 가 표에서 밀려났다 — '범위를 넓혔는데 최근 구간이 안 보이는' 역효과");
        long fromFirst = r.getEvents().stream().filter(e -> e.getSeg() != null && e.getSeg() == 0).count();
        assertTrue(fromFirst > 0 && fromFirst <= 2000, "첫 조각 몫이 이상하다: " + fromFirst);
    }

    @Test
    @DisplayName("구성 안내 소견에 조각 수와 실행 수가 담긴다")
    void multiRunFindingDescribesComposition() throws IOException {
        StringBuilder a = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 4; i++) a.append(youngAt(T0, 10 + i * 60.0, 500, 40)).append('\n');
        StringBuilder b = new StringBuilder(header(2147483648L));
        for (int i = 0; i < 4; i++) b.append(youngAt(T0 + HOUR, 10 + i * 60.0, 500, 40)).append('\n');

        GcLogResult r = run(a.toString(), b.toString());

        GcLogResult.Finding f = r.getFindings().stream().filter(x -> "MULTI_RUN_SEGMENTS".equals(x.getCode()))
                .findFirst().orElse(null);
        assertNotNull(f, "이어 붙인 구성을 설명하는 소견이 없다");
        assertEquals("Info", f.getSeverity(), "회전본이 곧 별개 실행인 것은 흔한 일이라 경고가 아니라 안내여야 한다");
        assertTrue(f.getTitle().contains("2개"), f.getTitle());
        assertTrue(f.getDetail().contains("gc.log.0"), f.getDetail());
    }
}

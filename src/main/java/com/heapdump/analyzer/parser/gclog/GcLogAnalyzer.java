package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import com.heapdump.analyzer.model.GcLogResult.EventRow;
import com.heapdump.analyzer.model.GcLogResult.Finding;
import com.heapdump.analyzer.model.GcLogResult.PauseStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 단일 패스·상수 메모리 집계기. 파서가 흘려보내는 이벤트를 받아 통계·시계열(다운샘플)·이벤트 표(상한)·소견을 만든다.
 *
 * <p>백분위는 이벤트 20만 건까지 정확값, 그 이상은 로그 스케일 256 버킷 근사({@code approximate=true}).
 * 시계열은 버퍼가 4000 점을 넘을 때마다 인접 쌍을 병합해 2000 점으로 줄인다(병합 계수 {@code mergeFactor} 를 결과에 남긴다).
 * "Full GC 직후 힙" 추세는 누수 신호의 핵심이라 별도 점 목록으로 두고 선형회귀(slope MB/h, R²)를 낸다 —
 * G1 처럼 Full 이 없는 로그는 Remark 직후, 그것도 없으면 Mixed 직후 값으로 대체하고 {@code basis} 에 밝힌다.
 */
public final class GcLogAnalyzer implements GcEventSink {

    static final int EXACT_PAUSE_CAP = 200_000;
    static final int SERIES_CAP = 4000;
    static final int SERIES_TARGET = 2000;
    static final int FLAGGED_CAP = 2000;
    static final int HEAD_CAP = 2500;
    static final int TAIL_CAP = 2500;
    static final int TREND_CAP = 4000;
    static final double OVERHEAD_WINDOW_SEC = 600.0;
    static final int CAUSE_CAP = 60;
    /** 기동 워밍업 — 클래스 로딩·캐시 적재로 라이브 셋이 정상적으로 자라는 구간. 누수 회귀에서 뺀다(uptime 이 있을 때만). */
    static final double WARMUP_SEC = 300.0;
    /** 회귀 점들의 최소 시간 폭 — 몇 초 구간의 증가를 시간 단위로 환산하면 수만 MB/h 같은 허수가 나온다. */
    static final double MIN_TREND_SPAN_SEC = 600.0;
    /** CMS 사이클 시작(Initial Mark) 시점 Old 점유율이 이 이상이면 직전 사이클이 회수하지 못한 것으로 본다(기본 트리거 ~92%). */
    static final double CMS_SATURATED_RATIO = 0.98;
    /** Parallel 의 명시적 호출은 Young 수집 → Full GC 로 연달아 기록된다(ScavengeBeforeFullGC). 이 간격 안이면 같은 호출. */
    static final double EXPLICIT_PAIR_SEC = 2.0;
    static final int CALL_INTERVAL_CAP = 5000;
    /** 누수 추세의 실질성 — 회귀 구간 증가량 하한(바이트)·최대 힙 대비 하한·최대 힙 90% 도달 예상 상한(일). */
    static final long LEAK_MIN_GROWTH_BYTES = 32L << 20;
    static final double LEAK_MIN_GROWTH_RATIO = 0.02;
    static final double LEAK_MAX_DAYS_TO_FILL = 30.0;
    /** 메모리 압박 Full GC(2026-09-16) — 회수율 하한(%)·직후 점유율 상한·간격 추세 판단 최소 간격 수·단축 비율·표본 상한. */
    static final double LOW_RECLAIM_PCT = 20.0;
    static final double HIGH_OCCUPANCY_RATIO = 0.85;
    static final int INTERVAL_MIN_COUNT = 6;
    static final double INTERVAL_SHRINK_RATIO = 0.5;
    static final double INTERVAL_STRONG_SHRINK_RATIO = 0.25;
    static final int WORST_CAP = 5;
    static final int PRESSURE_POINTS_CAP = 500;

    // ── 메타 ────────────────────────────────────────────────────
    private String collector, jdkVersion, decorations, jvmOptionsLine;
    private Long regionSize, heapMax;
    private boolean permGen;

    // ── 시간 ────────────────────────────────────────────────────
    private Long firstTs, lastTs;
    private Double firstUptime, lastUptime;
    private boolean xIsUptime = true;

    // ── 카운트 ──────────────────────────────────────────────────
    private final EnumMap<GcEventType, Integer> byType = new EnumMap<>(GcEventType.class);
    private final Map<String, Integer> byCause = new HashMap<>();
    private final EnumMap<GcFlag, Integer> byFlag = new EnumMap<>(GcFlag.class);
    private int eventCount, pauseCount, concurrentCount;

    // ── 일시정지 ────────────────────────────────────────────────
    private final PauseAcc all = new PauseAcc(), young = new PauseAcc(), full = new PauseAcc();

    // ── 힙 ──────────────────────────────────────────────────────
    private Long maxHeapTotal, maxHeapAfter, lastHeapAfter;
    private Long prevAfter; private Double prevX;
    private double allocBytes; private double allocSpanSec;
    private double promotedBytes;
    private final List<double[]> fullAfter = new ArrayList<>(), remarkAfter = new ArrayList<>(), mixedAfter = new ArrayList<>();
    private int consecutiveFull, maxConsecutiveFull;
    private Double firstFullX, lastFullX;

    // ── 10분 창 오버헤드 ────────────────────────────────────────
    private final ArrayDeque<double[]> window = new ArrayDeque<>();
    private double windowSum, maxOverhead = 0, maxOverheadStart = 0;

    // ── 메타스페이스·humongous·CPU ──────────────────────────────
    private Long metaFirst, metaLast, metaMaxTotal;
    private Integer maxHumongous;
    private int sysHigh, starvation, systemGc;

    // ── 명시적 GC(System.gc() 등) ───────────────────────────────
    private int explicitFull, pairedExplicit;
    private double explicitFullTotalMs, explicitFullMaxMs;
    private String pendingExplicitCause; private Double pendingExplicitX; private Long pendingExplicitBefore;
    private Double firstSysGcX, lastSysGcX;
    private final List<Double> sysGcIntervals = new ArrayList<>();
    private Double trendGrowthBytes, trendDaysToFill;

    // ── Full GC 분류·메모리 압박 ────────────────────────────────
    private final EnumMap<FullGcKind, Integer> fullByKind = new EnumMap<>(FullGcKind.class);
    private final Map<String, Integer> pressureByCause = new HashMap<>();
    private int consecutivePressure, maxConsecutivePressure;
    private Double firstPressureX, lastPressureX;
    private Integer lastPressureLine;
    private Long lastPressureBefore, lastPressureAfter, lastPressureTotal;
    private double maxPressureAfterRatio;
    /** 회수율 1% 버킷 히스토그램 — 상수 메모리로 중앙값을 낸다. */
    private final int[] reclaimHist = new int[101];
    private int reclaimSamples, lowReclaimCount, highOccupancyCount;
    private Double reclaimMin, reclaimLast;
    private final List<Double> pressureIntervals = new ArrayList<>();
    private final List<double[]> pressurePoints = new ArrayList<>();       // x, after, before
    private final List<GcLogResult.FullGcSample> worstReclaim = new ArrayList<>();

    // ── CMS Old 포화 ────────────────────────────────────────────
    private int cmsMarks, cmsSaturated, cmsSaturatedRun, cmsMaxSaturatedRun;
    private Long cmsLastOld, cmsLastOldTotal;
    private Double cmsFirstMarkX, cmsLastMarkX;

    // ── 시계열 ──────────────────────────────────────────────────
    private final List<double[]> series = new ArrayList<>(); // x, before, after, total, pause, typeOrdinal
    private int mergeFactor = 1;
    private double[] mergeAcc; private int mergeCount;

    // ── 이벤트 표 ────────────────────────────────────────────────
    private final List<EventRow> flagged = new ArrayList<>();
    private final List<EventRow> head = new ArrayList<>();
    private final ArrayDeque<EventRow> tail = new ArrayDeque<>();

    // ── 파서 상태 전달 ───────────────────────────────────────────
    private int maxPauseLine = -1;

    /** 최장 일시정지 이벤트의 줄 번호(원문 샘플 캡처용, 없으면 -1). */
    public int maxPauseLine() { return maxPauseLine; }

    /** 진행 표시용 — 지금까지 받은 이벤트 수. */
    public int eventCountSoFar() { return eventCount; }

    // ═══════════════════════════════════════════════════════════
    @Override
    public void onMeta(String key, String value) {
        switch (key) {
            case "collector": if (collector == null) collector = value; break;
            case "jdkVersion": if (jdkVersion == null) jdkVersion = value; break;
            case "decorations": decorations = value; break;
            case "jvmOptions": if (jvmOptionsLine == null) jvmOptionsLine = value; break;
            case "regionSize": regionSize = parseLong(value); break;
            case "heapMax": heapMax = parseLong(value); break;
            case "permGen": permGen = "true".equals(value); break;
            default: break;
        }
    }

    @Override
    public void onEvent(GcEvent ev) {
        eventCount++;
        if (ev.tsEpochMs != null) { if (firstTs == null) firstTs = ev.tsEpochMs; lastTs = ev.tsEpochMs; }
        if (ev.uptimeSec != null) { if (firstUptime == null) firstUptime = ev.uptimeSec; lastUptime = ev.uptimeSec; }
        Double x = effectiveX(ev);

        byType.merge(ev.type, 1, Integer::sum);
        if (ev.cause != null && (byCause.size() < CAUSE_CAP || byCause.containsKey(ev.cause))) byCause.merge(ev.cause, 1, Integer::sum);
        for (GcFlag f : ev.flags) byFlag.merge(f, 1, Integer::sum);
        Long callHeapBefore = onExplicit(ev, x);

        if (ev.concurrent) { concurrentCount++; }
        else if (ev.isPause()) {
            pauseCount++;
            double p = ev.pauseMs;
            all.add(p);
            if (ev.type == GcEventType.YOUNG || ev.type == GcEventType.MIXED) young.add(p);
            if (ev.type == GcEventType.FULL) full.add(p);
            if (p > all.maxHolderMs) { all.maxHolderMs = p; all.maxAt = ev; maxPauseLine = ev.line; }
            if (x != null) slideWindow(x, p);
            if (ev.userSec != null && ev.sysSec != null && ev.realSec != null) {
                if (ev.realSec >= 0.1 && ev.sysSec > 0 && ev.sysSec >= ev.userSec * 0.3) sysHigh++;
                double cpu = ev.userSec + ev.sysSec;
                if (ev.realSec >= 0.05 && ev.realSec > 2.0 * Math.max(cpu, 0.001) && cpu > 0) starvation++;
            }
        }

        FullGcKind kind = null;
        if (ev.type == GcEventType.FULL) {
            consecutiveFull++;
            maxConsecutiveFull = Math.max(maxConsecutiveFull, consecutiveFull);
            if (x != null) { if (firstFullX == null) firstFullX = x; lastFullX = x; }
            kind = FullGcKind.of(ev);
            fullByKind.merge(kind, 1, Integer::sum);
            if (kind == FullGcKind.HEAP_PRESSURE) {
                onPressureFull(ev, x);
                maxConsecutivePressure = Math.max(maxConsecutivePressure, ++consecutivePressure);
            } else {
                consecutivePressure = 0;
            }
        } else if (!ev.concurrent) {
            consecutiveFull = 0;
            consecutivePressure = 0;
        }

        if (ev.heapTotal != null && (maxHeapTotal == null || ev.heapTotal > maxHeapTotal)) maxHeapTotal = ev.heapTotal;
        if (ev.heapAfter != null) {
            if (maxHeapAfter == null || ev.heapAfter > maxHeapAfter) maxHeapAfter = ev.heapAfter;
            lastHeapAfter = ev.heapAfter;
            if (x != null && !ev.concurrent) {
                if (ev.type == GcEventType.FULL) addTrend(fullAfter, x, ev.heapAfter);
                else if (ev.type == GcEventType.REMARK || ev.type == GcEventType.CMS_FINAL_REMARK) addTrend(remarkAfter, x, ev.heapAfter);
                else if (ev.type == GcEventType.MIXED) addTrend(mixedAfter, x, ev.heapAfter);
            }
        }
        if (ev.heapBefore != null && ev.heapAfter != null && !ev.concurrent) {
            if (prevAfter != null && prevX != null && x != null && x > prevX) {
                long delta = ev.heapBefore - prevAfter;
                if (delta > 0) { allocBytes += delta; allocSpanSec += (x - prevX); }
            }
            prevAfter = ev.heapAfter;
            prevX = x;
            addSeriesPoint(x == null ? eventCount : x, ev);
        }
        if (ev.oldBefore != null && ev.oldAfter != null && (ev.type == GcEventType.YOUNG || ev.type == GcEventType.MIXED)) {
            long d = ev.oldAfter - ev.oldBefore;
            if (d > 0) promotedBytes += d;
        }
        if (ev.metaAfter != null) { if (metaFirst == null) metaFirst = ev.metaAfter; metaLast = ev.metaAfter; }
        if (ev.metaTotal != null && (metaMaxTotal == null || ev.metaTotal > metaMaxTotal)) metaMaxTotal = ev.metaTotal;
        if (ev.humongousRegions != null && (maxHumongous == null || ev.humongousRegions > maxHumongous)) maxHumongous = ev.humongousRegions;
        if (ev.type == GcEventType.CMS_INITIAL_MARK && ev.oldBefore != null && ev.oldTotal != null && ev.oldTotal > 0) {
            cmsMarks++;
            if ((double) ev.oldBefore / ev.oldTotal >= CMS_SATURATED_RATIO) {
                cmsSaturated++;
                cmsMaxSaturatedRun = Math.max(cmsMaxSaturatedRun, ++cmsSaturatedRun);
            } else {
                cmsSaturatedRun = 0;
            }
            cmsLastOld = ev.oldBefore;
            cmsLastOldTotal = ev.oldTotal;
            if (x != null) { if (cmsFirstMarkX == null) cmsFirstMarkX = x; cmsLastMarkX = x; }
        }

        EventRow row = toRow(ev);
        row.setCallHeapBefore(callHeapBefore);
        if (kind != null) row.setFullKind(kind.name());
        boolean abnormal = false;
        for (GcFlag f : ev.flags) if (f.isAbnormal()) { abnormal = true; break; }
        if (ev.type == GcEventType.FULL || abnormal || ev.type == GcEventType.CMS_FINAL_REMARK) {
            if (flagged.size() < FLAGGED_CAP) flagged.add(row);
        } else if (head.size() < HEAD_CAP) {
            head.add(row);
        } else {
            if (tail.size() >= TAIL_CAP) tail.pollFirst();
            tail.addLast(row);
        }
    }

    /**
     * 명시적 GC 집계. Parallel 은 호출 1회를 {@code [GC (System.gc())]} Young 수집 + {@code [Full GC (System.gc())]} 로 남기므로
     * 같은 원인의 Young 직후 {@link #EXPLICIT_PAIR_SEC} 이내 Full 은 같은 호출로 짝짓는다 — 짝지은 Full 에는 호출 전체의
     * 시작 힙(Young 수집 전)을 돌려준다. 그 사이에 다른 일시정지가 끼면 짝을 끊는다(동시 사이클은 무관).
     */
    private Long onExplicit(GcEvent ev, Double x) {
        boolean explicit = ev.flags.contains(GcFlag.SYSTEM_GC) || GcLogSupport.isExplicitCause(ev.cause);
        if (!explicit) {
            if (!ev.concurrent) { pendingExplicitCause = null; pendingExplicitX = null; pendingExplicitBefore = null; }
            return null;
        }
        boolean sysGc = ev.flags.contains(GcFlag.SYSTEM_GC);
        Long callBefore = null;
        boolean paired = ev.type == GcEventType.FULL && pendingExplicitCause != null && pendingExplicitCause.equals(ev.cause)
                && (x == null || pendingExplicitX == null || (x >= pendingExplicitX && x - pendingExplicitX <= EXPLICIT_PAIR_SEC));
        if (paired) {
            pairedExplicit++;
            callBefore = pendingExplicitBefore;
        } else if (sysGc) {
            systemGc++;
            if (x != null) {
                if (firstSysGcX == null) firstSysGcX = x;
                if (lastSysGcX != null && x > lastSysGcX) {
                    if (sysGcIntervals.size() >= CALL_INTERVAL_CAP) {   // 절반 솎기 — 중앙값 근사용이라 순서만 유지하면 된다
                        List<Double> thin = new ArrayList<>(sysGcIntervals.size() / 2 + 1);
                        for (int i = 0; i < sysGcIntervals.size(); i += 2) thin.add(sysGcIntervals.get(i));
                        sysGcIntervals.clear();
                        sysGcIntervals.addAll(thin);
                    }
                    sysGcIntervals.add(x - lastSysGcX);
                }
                lastSysGcX = x;
            }
        }
        if (ev.type == GcEventType.FULL) {
            explicitFull++;
            if (ev.pauseMs != null) { explicitFullTotalMs += ev.pauseMs; explicitFullMaxMs = Math.max(explicitFullMaxMs, ev.pauseMs); }
        }
        if (!ev.concurrent && ev.type != GcEventType.FULL) {
            pendingExplicitCause = ev.cause; pendingExplicitX = x; pendingExplicitBefore = ev.heapBefore;
        } else if (!ev.concurrent) {
            pendingExplicitCause = null; pendingExplicitX = null; pendingExplicitBefore = null;
        }
        return callBefore;
    }

    /**
     * 메모리 압박 Full GC 집계(2026-09-16) — 원인 분포·회수율(before→after)·직후 점유율(after/total)·간격·차트 점·최저 회수 표본.
     * 명시적 Full 은 여기 오지 않는다(호출자가 {@link FullGcKind#HEAP_PRESSURE} 만 넘긴다).
     */
    private void onPressureFull(GcEvent ev, Double x) {
        String cause = ev.cause == null ? "(원인 미기록)" : ev.cause;
        if (pressureByCause.size() < CAUSE_CAP || pressureByCause.containsKey(cause)) pressureByCause.merge(cause, 1, Integer::sum);
        if (x != null) {
            if (firstPressureX == null) firstPressureX = x;
            if (lastPressureX != null && x > lastPressureX) {
                if (pressureIntervals.size() >= CALL_INTERVAL_CAP) {
                    List<Double> thin = new ArrayList<>(pressureIntervals.size() / 2 + 1);
                    for (int i = 0; i < pressureIntervals.size(); i += 2) thin.add(pressureIntervals.get(i));
                    pressureIntervals.clear();
                    pressureIntervals.addAll(thin);
                }
                pressureIntervals.add(x - lastPressureX);
            }
            lastPressureX = x;
        }
        lastPressureLine = ev.line;
        lastPressureBefore = ev.heapBefore;
        lastPressureAfter = ev.heapAfter;
        lastPressureTotal = ev.heapTotal;
        Double reclaimPct = null;
        if (ev.heapBefore != null && ev.heapAfter != null && ev.heapBefore > 0) {
            reclaimPct = clamp(100.0 * (ev.heapBefore - ev.heapAfter) / ev.heapBefore, 0, 100);
            reclaimHist[(int) Math.round(reclaimPct)]++;
            reclaimSamples++;
            reclaimLast = reclaimPct;
            if (reclaimMin == null || reclaimPct < reclaimMin) reclaimMin = reclaimPct;
            if (reclaimPct < LOW_RECLAIM_PCT) lowReclaimCount++;
            // 회수율 최저 표본 — 삽입 정렬, 상한을 넘으면 가장 좋은 것을 버린다
            GcLogResult.FullGcSample s = new GcLogResult.FullGcSample();
            s.setLine(ev.line); s.setUptimeSec(ev.uptimeSec); s.setCause(ev.cause);
            s.setHeapBefore(ev.heapBefore); s.setHeapAfter(ev.heapAfter); s.setHeapTotal(ev.heapTotal); s.setReclaimPct(round3(reclaimPct));
            int pos = 0;
            while (pos < worstReclaim.size() && worstReclaim.get(pos).getReclaimPct() <= reclaimPct) pos++;
            if (pos < WORST_CAP) {
                worstReclaim.add(pos, s);
                if (worstReclaim.size() > WORST_CAP) worstReclaim.remove(worstReclaim.size() - 1);
            }
        }
        if (ev.heapAfter != null && ev.heapTotal != null && ev.heapTotal > 0) {
            double ratio = (double) ev.heapAfter / ev.heapTotal;
            if (ratio > maxPressureAfterRatio) maxPressureAfterRatio = ratio;
            if (ratio >= HIGH_OCCUPANCY_RATIO) highOccupancyCount++;
        }
        if (ev.heapAfter != null) {
            double px = x == null ? eventCount : x;
            if (pressurePoints.size() >= TREND_CAP) {
                List<double[]> thin = new ArrayList<>(pressurePoints.size() / 2 + 1);
                for (int i = 0; i < pressurePoints.size(); i += 2) thin.add(pressurePoints.get(i));
                pressurePoints.clear();
                pressurePoints.addAll(thin);
            }
            pressurePoints.add(new double[]{px, ev.heapAfter, ev.heapBefore == null ? -1 : ev.heapBefore});
        }
    }

    /** {@link GcLogResult.FullGcSummary} 조립 — 파싱만 되면 늘 만든다(Full 0회여도 total=0). 옛 JSON(null)과 구분하기 위해서다. */
    private GcLogResult.FullGcSummary buildFullGcSummary(double durationSec, long capBytes) {
        GcLogResult.FullGcSummary s = new GcLogResult.FullGcSummary();
        int total = 0;
        for (FullGcKind k : FullGcKind.values()) {
            int n = fullByKind.getOrDefault(k, 0);
            s.getByKind().put(k.name(), n);
            total += n;
        }
        s.setTotal(total);
        int pressure = fullByKind.getOrDefault(FullGcKind.HEAP_PRESSURE, 0);
        s.setHeapPressureCount(pressure);
        if (durationSec > 0) s.setHeapPressurePerHour(round3(pressure / (durationSec / 3600.0)));
        pressureByCause.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                .forEach(e -> s.getPressureByCause().put(e.getKey(), e.getValue()));
        s.setMaxConsecutivePressure(maxConsecutivePressure);
        s.setFirstAtSec(firstPressureX == null ? null : round3(firstPressureX));
        s.setLastAtSec(lastPressureX == null ? null : round3(lastPressureX));
        s.setLastLine(lastPressureLine);
        s.setLastBeforeBytes(lastPressureBefore);
        s.setLastAfterBytes(lastPressureAfter);
        s.setLastTotalBytes(lastPressureTotal);
        if (lastPressureAfter != null && lastPressureTotal != null && lastPressureTotal > 0) s.setLastAfterRatio(round3((double) lastPressureAfter / lastPressureTotal));
        if (lastPressureAfter != null && capBytes > 0) s.setLastAfterCapRatio(round3((double) lastPressureAfter / capBytes));
        if (maxPressureAfterRatio > 0) s.setMaxAfterRatio(round3(maxPressureAfterRatio));
        s.setReclaimSamples(reclaimSamples);
        if (reclaimSamples > 0) {
            s.setReclaimPctMin(round3(reclaimMin));
            s.setReclaimPctLast(round3(reclaimLast));
            int target = (reclaimSamples + 1) / 2, cum = 0;
            for (int b = 0; b <= 100; b++) { cum += reclaimHist[b]; if (cum >= target) { s.setReclaimPctMedian((double) b); break; } }
        }
        s.setLowReclaimCount(lowReclaimCount);
        s.setHighOccupancyCount(highOccupancyCount);
        s.setIntervalCount(pressureIntervals.size());
        if (pressureIntervals.size() >= 2) {
            int half = pressureIntervals.size() / 2;
            double first = median(pressureIntervals.subList(0, half));
            double second = median(pressureIntervals.subList(half, pressureIntervals.size()));
            s.setIntervalMedianFirstHalfSec(round3(first));
            s.setIntervalMedianSecondHalfSec(round3(second));
            if (pressureIntervals.size() >= INTERVAL_MIN_COUNT) s.setIntervalShrinking(first > 0 && second <= first * INTERVAL_SHRINK_RATIO);
        }
        s.setPoints(pressurePoints.size() > PRESSURE_POINTS_CAP ? thin(pressurePoints, PRESSURE_POINTS_CAP) : new ArrayList<>(pressurePoints));
        s.setWorstReclaim(new ArrayList<>(worstReclaim));
        if (pressure > 0 && reclaimSamples == 0) s.setNote("힙 크기 전후 값이 없어 회수율을 계산하지 않았습니다.");
        else if (pressure > 0 && lastPressureTotal == null) s.setNote("힙 총량이 없어 직후 점유율을 계산하지 않았습니다.");
        return s;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return 0;
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a[a.length / 2];
    }

    /** uptime 이 없으면 절대 시각으로, 그것도 없으면 null. */
    private Double effectiveX(GcEvent ev) {
        if (ev.uptimeSec != null) return ev.uptimeSec;
        if (ev.tsEpochMs != null && firstTs != null) {
            xIsUptime = false;
            return (ev.tsEpochMs - firstTs) / 1000.0 + (firstUptime == null ? 0 : firstUptime);
        }
        return null;
    }

    private void slideWindow(double x, double pauseMs) {
        window.addLast(new double[]{x, pauseMs});
        windowSum += pauseMs;
        while (!window.isEmpty() && x - window.peekFirst()[0] > OVERHEAD_WINDOW_SEC) windowSum -= window.pollFirst()[1];
        // 분모는 항상 고정 창(600s) — 가까운 두 pause 만으로 비율이 튀지 않게 한다
        double pct = windowSum / (OVERHEAD_WINDOW_SEC * 1000.0) * 100.0;
        if (window.size() >= 2 && pct > maxOverhead) { maxOverhead = pct; maxOverheadStart = window.peekFirst()[0]; }
    }

    private static void addTrend(List<double[]> list, double x, long after) {
        if (list.size() >= TREND_CAP) {   // 절반 솎기 — 오래된 점을 하나 걸러 버린다
            List<double[]> thin = new ArrayList<>(list.size() / 2 + 1);
            for (int i = 0; i < list.size(); i += 2) thin.add(list.get(i));
            list.clear();
            list.addAll(thin);
        }
        list.add(new double[]{x, after});
    }

    private void addSeriesPoint(double x, GcEvent ev) {
        double[] p = {x, ev.heapBefore, ev.heapAfter, ev.heapTotal == null ? -1 : ev.heapTotal,
                ev.pauseMs == null ? 0 : ev.pauseMs, ev.type.ordinal()};
        if (mergeFactor == 1) { series.add(p); }
        else {
            if (mergeAcc == null) { mergeAcc = p.clone(); mergeCount = 1; }
            else { mergeInto(mergeAcc, p); mergeCount++; }
            if (mergeCount >= mergeFactor) { series.add(mergeAcc); mergeAcc = null; mergeCount = 0; }
        }
        if (series.size() >= SERIES_CAP) {
            List<double[]> merged = new ArrayList<>(SERIES_TARGET + 1);
            for (int i = 0; i + 1 < series.size(); i += 2) {
                double[] a = series.get(i).clone();
                mergeInto(a, series.get(i + 1));
                merged.add(a);
            }
            if (series.size() % 2 == 1) merged.add(series.get(series.size() - 1));
            series.clear();
            series.addAll(merged);
            mergeFactor *= 2;
        }
    }

    /** 병합 규칙: x=마지막, before=최대, after=마지막, total=최대, pause=최대, type=더 무거운 쪽. */
    private static void mergeInto(double[] acc, double[] p) {
        acc[0] = p[0];
        acc[1] = Math.max(acc[1], p[1]);
        acc[2] = p[2];
        acc[3] = Math.max(acc[3], p[3]);
        acc[4] = Math.max(acc[4], p[4]);
        if (p[5] == GcEventType.FULL.ordinal() || acc[5] == GcEventType.FULL.ordinal()) acc[5] = GcEventType.FULL.ordinal();
        else if (p[5] == GcEventType.MIXED.ordinal()) acc[5] = p[5];
    }

    private static EventRow toRow(GcEvent ev) {
        EventRow r = new EventRow();
        r.setSeq(ev.seq); r.setLine(ev.line); r.setTsEpochMs(ev.tsEpochMs); r.setUptimeSec(ev.uptimeSec);
        r.setType(ev.type.name()); r.setCause(ev.cause); r.setPauseMs(ev.pauseMs); r.setConcurrent(ev.concurrent);
        r.setHeapBefore(ev.heapBefore); r.setHeapAfter(ev.heapAfter); r.setHeapTotal(ev.heapTotal);
        r.setOldAfter(ev.oldAfter); r.setMetaAfter(ev.metaAfter);
        for (GcFlag f : ev.flags) r.getFlags().add(f.name());
        r.setUserSec(ev.userSec); r.setSysSec(ev.sysSec); r.setRealSec(ev.realSec);
        return r;
    }

    // ═══════════════════════════════════════════════════════════
    /**
     * 집계 마감. {@code fallbackEndEpochSec} 는 절대 시각이 없을 때 로그 끝 시각으로 쓸 파일 mtime(없으면 null).
     */
    public GcLogResult finish(GcLogFormat format, Long fallbackEndEpochSec, boolean limitHit, int droppedIncomplete) {
        if (mergeAcc != null) { series.add(mergeAcc); mergeAcc = null; }
        GcLogResult r = new GcLogResult();
        GcLogResult.Meta meta = r.getMeta();
        meta.setFormat(format.name());
        meta.setCollector(collector == null ? "unknown" : collector);
        meta.setJdkVersion(jdkVersion);
        meta.setDecorations(decorations);
        meta.setRegionSizeBytes(regionSize);
        if (heapMax == null) heapMax = heapMaxFromOptions(jvmOptionsLine);   // JDK 8 CommandLine flags 의 -XX:MaxHeapSize / -Xmx
        meta.setHeapMaxBytes(heapMax);
        if (permGen) {
            meta.setPermGen(Boolean.TRUE);
            if (jdkVersion == null) meta.setJdkVersion("1.7 이하(PermGen)");   // 헤더 없는 회전 로그 — PermGen 은 JDK 8 에서 제거됐다
        }
        meta.setFirstUptimeSec(firstUptime);
        meta.setLastUptimeSec(lastUptime);
        meta.setDroppedIncomplete(droppedIncomplete);
        meta.setTruncated(limitHit || droppedIncomplete > 0);

        double durationSec = 0;
        if (firstUptime != null && lastUptime != null && lastUptime > firstUptime) durationSec = lastUptime - firstUptime;
        else if (firstTs != null && lastTs != null && lastTs > firstTs) durationSec = (lastTs - firstTs) / 1000.0;
        meta.setDurationSec(durationSec);

        if (firstTs != null && lastTs != null) {
            meta.setTimeSource("absolute");
            meta.setLogStartEpochMs(firstTs);
            meta.setLogEndEpochMs(lastTs);
        } else if (fallbackEndEpochSec != null) {
            meta.setTimeSource("mtime");
            meta.setLogEndEpochMs(fallbackEndEpochSec * 1000L);
            meta.setLogStartEpochMs(fallbackEndEpochSec * 1000L - (long) (durationSec * 1000.0));
        } else {
            meta.setTimeSource("none");
        }

        GcLogResult.Kpi k = r.getKpi();
        k.setEventCount(eventCount);
        k.setPauseCount(pauseCount);
        k.setYoungCount(byType.getOrDefault(GcEventType.YOUNG, 0));
        k.setMixedCount(byType.getOrDefault(GcEventType.MIXED, 0));
        k.setFullCount(byType.getOrDefault(GcEventType.FULL, 0));
        k.setConcurrentCount(concurrentCount);
        if (durationSec > 0) {
            k.setThroughputPct(clamp(100.0 * (1.0 - all.total / (durationSec * 1000.0)), 0, 100));
            k.setFullGcPerHour(k.getFullCount() / (durationSec / 3600.0));
        }
        if (allocSpanSec > 0) k.setAllocationRateMbPerSec(allocBytes / 1048576.0 / allocSpanSec);
        if (durationSec > 0 && promotedBytes > 0) k.setPromotionRateMbPerSec(promotedBytes / 1048576.0 / durationSec);
        k.setMaxConsecutiveFull(maxConsecutiveFull);
        k.setMaxHeapTotalBytes(maxHeapTotal);
        k.setMaxHeapAfterBytes(maxHeapAfter);
        k.setLastHeapAfterBytes(lastHeapAfter);
        if (maxOverhead > 0) { k.setMaxOverheadPct10m(maxOverhead); k.setMaxOverheadWindowStartSec(maxOverheadStart); }
        k.setMetaspaceFirstBytes(metaFirst);
        k.setMetaspaceLastBytes(metaLast);
        k.setMetaspaceMaxTotalBytes(metaMaxTotal);
        k.setMaxHumongousRegions(maxHumongous);
        k.setSysTimeHighCount(sysHigh);
        k.setCpuStarvationCount(starvation);
        k.setSystemGcCount(systemGc);
        k.setExplicitFullCount(explicitFull);
        if (durationSec > 0) k.setPressureFullGcPerHour((k.getFullCount() - explicitFull) / (durationSec / 3600.0));
        k.setSystemGcIntervalSec(regularInterval(sysGcIntervals));

        r.setPauseStats(all.stats());
        r.setYoungPauseStats(young.stats());
        r.setFullPauseStats(full.stats());

        GcLogResult.Counts c = r.getCounts();
        for (Map.Entry<GcEventType, Integer> e : byType.entrySet()) c.getByType().put(e.getKey().name(), e.getValue());
        byCause.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                .forEach(e -> c.getByCause().put(e.getKey(), e.getValue()));
        for (Map.Entry<GcFlag, Integer> e : byFlag.entrySet()) c.getByFlag().put(e.getKey().name(), e.getValue());

        GcLogResult.Trend t = r.getTrend();
        List<double[]> basisList; String basis;
        if (fullAfter.size() >= 2) { basisList = fullAfter; basis = "full"; }
        else if (remarkAfter.size() >= 2) { basisList = remarkAfter; basis = "remark"; }
        else if (mixedAfter.size() >= 2) { basisList = mixedAfter; basis = "mixed"; }
        else { basisList = fullAfter.isEmpty() ? (remarkAfter.isEmpty() ? mixedAfter : remarkAfter) : fullAfter; basis = basisList.isEmpty() ? "none" : (basisList == fullAfter ? "full" : basisList == remarkAfter ? "remark" : "mixed"); }
        t.setBasis(basis);
        t.setAfterPoints(basisList.size() > 500 ? thin(basisList, 500) : new ArrayList<>(basisList));
        // 회귀 대상 — 기동 워밍업 점 제외(x 가 uptime 일 때만 판별 가능). 2026-09-14: 기동 2~16초의 Remark 7점으로
        // +40,390 MB/h '누수 의심' 오탐이 났다(정상 Spring 기동의 클래스 로딩 증가).
        List<double[]> fit = basisList;
        if (xIsUptime && firstUptime != null) {
            fit = new ArrayList<>(basisList.size());
            for (double[] p : basisList) if (p[0] >= WARMUP_SEC) fit.add(p);
            t.setExcludedWarmup(basisList.size() - fit.size());
        }
        List<double[]> firstLast = fit.isEmpty() ? basisList : fit;
        if (!firstLast.isEmpty()) {
            t.setFirstAfterBytes(firstLast.get(0)[1]);
            t.setLastAfterBytes(basisList.get(basisList.size() - 1)[1]);   // '마지막' 은 제외와 무관하게 가장 최근 점
        }
        double span = fit.size() >= 2 ? fit.get(fit.size() - 1)[0] - fit.get(0)[0] : 0;
        t.setPointsUsed(fit.size());
        t.setSpanSec(round3(span));
        if (fit.size() >= 3 && span >= MIN_TREND_SPAN_SEC) {
            double[] reg = regression(fit);
            t.setSlopeMbPerHour(reg[0]);
            t.setR2(reg[1]);
            judgeTrendSignificance(t, span, heapMax != null && heapMax > 0 ? heapMax : (maxHeapTotal == null ? 0 : maxHeapTotal));
        } else if (!basisList.isEmpty()) {
            if (fit.size() < 3 && t.getExcludedWarmup() > 0) {
                t.setNote(String.format(Locale.ROOT, "기동 직후(%.0f분) 점 %d개를 빼고 나면 %d점뿐이라 추세를 계산하지 않았습니다.", WARMUP_SEC / 60, t.getExcludedWarmup(), fit.size()));
            } else if (fit.size() < 3) {
                t.setNote("점이 " + fit.size() + "개뿐이라 추세를 계산하지 않았습니다.");
            } else {
                t.setNote(String.format(Locale.ROOT, "점 %d개가 %s 구간에 몰려 있어(최소 %.0f분) 추세를 계산하지 않았습니다.", fit.size(), fmtDur(span), MIN_TREND_SPAN_SEC / 60));
            }
        }

        GcLogResult.Series s = r.getSeries();
        s.setMergeFactor(mergeFactor);
        for (double[] p : series) {
            s.getUptimeSec().add(round3(p[0]));
            s.getHeapBefore().add((long) p[1]);
            s.getHeapAfter().add((long) p[2]);
            s.getHeapTotal().add(p[3] < 0 ? null : (long) p[3]);
            s.getPauseMs().add(round3(p[4]));
            s.getType().add(GcEventType.values()[(int) p[5]].name());
        }

        List<EventRow> events = new ArrayList<>(flagged.size() + head.size() + tail.size());
        events.addAll(flagged);
        events.addAll(head);
        events.addAll(tail);
        // 파서는 완료 순(동시 사이클 완료가 pending pause 보다 먼저 나올 수 있음)이라 줄 번호로 정렬한다
        events.sort((a, b) -> a.getLine() != b.getLine() ? Integer.compare(a.getLine(), b.getLine()) : Integer.compare(a.getSeq(), b.getSeq()));
        r.setEvents(events);

        if (jvmOptionsLine != null) {
            for (String tok : jvmOptionsLine.trim().split("\\s+")) {
                if (tok.startsWith("-") && r.getJvmOptions().size() < 40) r.getJvmOptions().add(tok.length() > 200 ? tok.substring(0, 200) : tok);
            }
        }

        r.setFullGcSummary(buildFullGcSummary(durationSec, heapMax != null && heapMax > 0 ? heapMax : (maxHeapTotal == null ? 0 : maxHeapTotal)));
        r.setFindings(buildFindings(r, limitHit));
        k.setSeverity(maxSeverity(r.getFindings()));
        return r;
    }

    /**
     * 누수 추세의 실질성 판정(2026-09-15). 기울기가 양수·R²&gt;0.5 여도 증가가 미미하면 누수 신호가 아니다 — 71시간 동안 +2MB
     * (0.03 MB/h, 2GB 힙 90% 도달까지 6년)가 "누수 의심" 으로 나온 실측 오탐. 증가량과 도달 예상을 <b>둘 다</b> 넘겨야 significant.
     */
    private void judgeTrendSignificance(GcLogResult.Trend t, double spanSec, long capBytes) {
        double slope = t.getSlopeMbPerHour();
        if (!(slope > 0) || t.getR2() == null || t.getR2() <= 0.5 || t.getPointsUsed() == null || t.getPointsUsed() < 3 || t.getLastAfterBytes() == null) {
            t.setSignificant(false);
            return;
        }
        double growthBytes = slope * (spanSec / 3600.0) * 1048576.0;
        double minGrowth = Math.max(LEAK_MIN_GROWTH_BYTES, capBytes * LEAK_MIN_GROWTH_RATIO);
        Double days = null;
        if (capBytes > 0) {
            double remainMb = (capBytes * 0.9 - t.getLastAfterBytes()) / 1048576.0;
            days = Math.max(0, remainMb / slope) / 24.0;
        }
        trendGrowthBytes = growthBytes;
        trendDaysToFill = days;
        boolean sig = growthBytes >= minGrowth && (days == null || days <= LEAK_MAX_DAYS_TO_FILL);
        t.setSignificant(sig);
        if (!sig) {
            StringBuilder sb = new StringBuilder("완만한 증가(회귀 구간 +").append(fmtBytes(growthBytes));
            if (capBytes > 0) sb.append(String.format(Locale.ROOT, ", 최대 힙의 %.1f%%", 100.0 * growthBytes / capBytes));
            if (days != null) sb.append(", 최대 힙 90% 도달까지 ").append(fmtDays(days));
            sb.append(") — 누수 신호로 보지 않았습니다.");
            t.setNote(sb.toString());
        }
    }

    /** 간격이 규칙적이면(3개 이상·80% 가 중앙값 ±5% 이내·중앙값 1분 이상) 중앙값, 아니면 null. */
    static Double regularInterval(List<Double> intervals) {
        if (intervals.size() < 3) return null;
        double[] a = intervals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double med = a[a.length / 2];
        if (med < 60) return null;
        int near = 0;
        for (double v : a) if (Math.abs(v - med) <= med * 0.05) near++;
        return near >= a.length * 0.8 ? round3(med) : null;
    }

    private static final java.util.regex.Pattern MAX_HEAP_FLAG = java.util.regex.Pattern.compile("(?:^|\\s)-XX:MaxHeapSize=(\\d+)(?=\\s|$)");
    private static final java.util.regex.Pattern XMX_FLAG = java.util.regex.Pattern.compile("(?:^|\\s)-Xmx(\\d+[kKmMgGtT]?)(?=\\s|$)");

    /** JVM 옵션 줄의 최대 힙 — 뒤에 나온 값이 이긴다(JVM 규칙). 없으면 null. */
    static Long heapMaxFromOptions(String line) {
        if (line == null) return null;
        Long v = null;
        java.util.regex.Matcher m = MAX_HEAP_FLAG.matcher(line);
        while (m.find()) v = parseLong(m.group(1));
        m = XMX_FLAG.matcher(line);
        int lastXmx = -1; Long xmx = null;
        while (m.find()) { lastXmx = m.start(); xmx = GcLogSupport.parseSizeToken(m.group(1).toUpperCase(Locale.ROOT)); }
        if (xmx != null) {
            java.util.regex.Matcher mh = MAX_HEAP_FLAG.matcher(line);
            int lastMh = -1; while (mh.find()) lastMh = mh.start();
            if (v == null || lastXmx > lastMh) v = xmx;
        }
        return v != null && v > 0 ? v : null;
    }

    static String fmtDays(double days) {
        if (days < 1) return String.format(Locale.ROOT, "약 %.0f시간", days * 24);
        if (days < 365) return String.format(Locale.ROOT, "약 %.0f일", days);
        return String.format(Locale.ROOT, "약 %.1f년", days / 365);
    }

    static String fmtInterval(double sec) {
        if (sec < 120) return String.format(Locale.ROOT, "%.0f초", sec);
        if (sec < 7200) return String.format(Locale.ROOT, "%.0f분", sec / 60);
        return String.format(Locale.ROOT, "%.1f시간", sec / 3600);
    }

    static String fmtSlope(double mbPerHour) {
        return Math.abs(mbPerHour) < 1 ? String.format(Locale.ROOT, "%+.2f", mbPerHour) : String.format(Locale.ROOT, "%+.1f", mbPerHour);
    }

    // ── 소견 규칙 ───────────────────────────────────────────────

    private List<Finding> buildFindings(GcLogResult r, boolean limitHit) {
        List<Finding> out = new ArrayList<>();
        GcLogResult.Kpi k = r.getKpi();
        GcLogResult.Trend t = r.getTrend();
        double durSec = r.getMeta().getDurationSec() == null ? 0 : r.getMeta().getDurationSec();
        long maxTotal = k.getMaxHeapTotalBytes() == null ? 0 : k.getMaxHeapTotalBytes();

        // Full GC 빈도 — 명시적 호출(System.gc()·힙 덤프 등)은 Old 가 찼다는 신호가 아니라 뺀다(2026-09-15 실측: 1시간 주기
        // RMI System.gc() 72회가 "Old 영역이 반복해서 차고 있습니다" High 로 나왔다. Old 점유 5%)
        int pressureFull = k.getFullCount() - explicitFull;
        GcLogResult.FullGcSummary fs = r.getFullGcSummary();
        int heapPressure = fs == null ? pressureFull : fs.getHeapPressureCount();
        int metaFull = fs == null ? 0 : fs.getByKind().getOrDefault(FullGcKind.METASPACE.name(), 0);
        int lockerFull = fs == null ? 0 : fs.getByKind().getOrDefault(FullGcKind.GC_LOCKER.name(), 0);
        if (pressureFull >= 2 && k.getPressureFullGcPerHour() != null) {
            double ph = k.getPressureFullGcPerHour();
            String sev = ph > 6 ? "Critical" : ph > 1 ? "High" : (durSec >= 600 && ph > 0.25) ? "Medium" : null;
            if (sev == null && durSec < 600 && pressureFull >= 3) sev = "Medium";
            if (sev != null) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("fullCount", pressureFull); ev.put("perHour", round3(ph)); ev.put("maxConsecutive", k.getMaxConsecutiveFull()); ev.put("explicitFullCount", explicitFull);
                StringBuilder breakdown = new StringBuilder();
                if (fs != null) {
                    ev.put("heapPressureCount", heapPressure); ev.put("metaspaceCount", metaFull); ev.put("gcLockerCount", lockerFull);
                    ev.put("pressureByCause", new LinkedHashMap<>(fs.getPressureByCause()));
                    breakdown.append(String.format(Locale.ROOT, " 분류: 힙 압박 %d회", heapPressure));
                    if (!fs.getPressureByCause().isEmpty()) {
                        StringBuilder c = new StringBuilder();
                        fs.getPressureByCause().forEach((cause, n) -> c.append(c.length() > 0 ? " · " : "").append(cause).append(' ').append(n));
                        breakdown.append("(원인: ").append(c).append(')');
                    }
                    breakdown.append(String.format(Locale.ROOT, " · Metaspace %d회 · GCLocker %d회.", metaFull, lockerFull));
                }
                out.add(f("FULL_GC_FREQUENT", sev, "Full GC 가 잦습니다",
                        String.format(Locale.ROOT, "로그 구간 %s 동안 Full GC %d회 (시간당 %.1f회, 연속 최대 %d회).", fmtDur(durSec), pressureFull, ph, k.getMaxConsecutiveFull())
                                + (explicitFull > 0 ? String.format(Locale.ROOT, " 명시적 호출(System.gc() 등) Full %d회는 제외한 수치입니다(전체 %d회).", explicitFull, k.getFullCount()) : "")
                                + breakdown,
                        "Old 영역이 반복해서 차고 있습니다. Full GC 직후 힙 추세(누수 신호)를 먼저 확인하고, 추세가 평평하면 -Xmx 확대·Young 비율 조정·Humongous/승격 원인을 점검하세요.",
                        ev));
            }
        }
        // 메모리 압박 Full GC 가 회수하지 못함 — Full GC 뒤에도 살아 있는 객체가 힙 대부분(2026-09-16). 빈도와 별개의 신호다:
        // 시간당 0.5회여도 매번 10% 만 회수하고 직후 90% 라면 다음 Full 은 OutOfMemoryError 다.
        if (fs != null && fs.getHeapPressureCount() >= 2 && fs.getLowReclaimCount() >= 2) {
            double capRatio = fs.getLastAfterCapRatio() == null ? 0 : fs.getLastAfterCapRatio();
            String sev = capRatio >= 0.85 ? "Critical" : capRatio >= 0.6 ? "High" : "Medium";
            String col = collector == null ? "" : collector;
            StringBuilder advice = new StringBuilder("Full GC 뒤에도 살아 있는 객체가 힙 대부분을 차지합니다 — 누수 또는 힙 부족입니다. "
                    + "연결된 힙 덤프의 Leak Suspects·Dominator Tree 로 누적 주체를 확인하고, 누수가 아니면 -Xmx 를 늘리세요.");
            if (col.equals("CMS")) advice.append(" CMS 는 이 상태에서 concurrent mode failure 로 이어집니다.");
            else if (col.equals("G1")) advice.append(" G1 은 Full GC 전 Mixed GC 가 Old 를 못 비운 것이니 -XX:G1MixedGCLiveThresholdPercent·IHOP(-XX:InitiatingHeapOccupancyPercent)도 함께 보세요.");
            else if (col.equals("Parallel") || col.equals("Serial")) advice.append(" ").append(col).append(" 은 Full GC 만이 Old 를 회수하므로 회수율이 낮으면 곧 OutOfMemoryError 입니다.");
            List<Integer> worstLines = new ArrayList<>();
            for (GcLogResult.FullGcSample w : fs.getWorstReclaim()) worstLines.add(w.getLine());
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("heapPressureCount", fs.getHeapPressureCount()); ev.put("lowReclaimCount", fs.getLowReclaimCount());
            ev.put("reclaimMedianPct", fs.getReclaimPctMedian()); ev.put("reclaimMinPct", fs.getReclaimPctMin());
            ev.put("lastAfterBytes", fs.getLastAfterBytes()); ev.put("capBytes", r.getMeta().getHeapMaxBytes() != null && r.getMeta().getHeapMaxBytes() > 0 ? r.getMeta().getHeapMaxBytes() : maxTotal);
            ev.put("lastAfterCapRatio", fs.getLastAfterCapRatio()); ev.put("lastLine", fs.getLastLine()); ev.put("worstLines", worstLines);
            out.add(f("FULL_GC_LOW_RECLAIM", sev, "Full GC 가 메모리를 거의 회수하지 못합니다",
                    String.format(Locale.ROOT, "메모리 압박 Full GC %d회 중 %d회에서 회수율이 %.0f%% 미만이었습니다(중앙값 %.0f%%, 최저 %.0f%%).",
                            fs.getHeapPressureCount(), fs.getLowReclaimCount(), LOW_RECLAIM_PCT, nz(fs.getReclaimPctMedian()), nz(fs.getReclaimPctMin()))
                            + (fs.getLastAfterBytes() != null ? String.format(Locale.ROOT, " 마지막 압박 Full GC 직후 %s / %s (%.0f%%), uptime %s, 줄 %d.",
                                    fmtBytes(fs.getLastAfterBytes()), fmtBytes(fs.getLastTotalBytes()), nz(fs.getLastAfterRatio()) * 100, fmtDur(fs.getLastAtSec()), fs.getLastLine() == null ? 0 : fs.getLastLine()) : ""),
                    advice.toString(), ev));
        }
        // 압박 Full GC 간격 단축 — 라이브 셋이 늘거나 할당 부하가 커지는 신호
        if (fs != null && Boolean.TRUE.equals(fs.getIntervalShrinking())) {
            double first = fs.getIntervalMedianFirstHalfSec(), second = fs.getIntervalMedianSecondHalfSec();
            String sev = (second <= first * INTERVAL_STRONG_SHRINK_RATIO || second < 300) ? "High" : "Medium";
            out.add(f("FULL_GC_INTERVAL_SHRINKING", sev, "메모리 압박 Full GC 간격이 짧아지고 있습니다",
                    String.format(Locale.ROOT, "압박 Full GC %d회 — 간격 중앙값 앞 절반 %s → 뒤 절반 %s.", fs.getHeapPressureCount(), fmtInterval(first), fmtInterval(second)),
                    "라이브 셋이 늘거나 할당 부하가 커지는 신호입니다. Full GC 직후 힙 추세(누수)와 할당률 소견을 함께 보고, 힙 덤프로 증가 주체를 확인하세요.",
                    Map.of("intervalCount", fs.getIntervalCount(), "firstHalfMedianSec", round3(first), "secondHalfMedianSec", round3(second), "ratio", first > 0 ? round3(second / first) : 0)));
        }
        // 누수 추세
        if (Boolean.TRUE.equals(t.getSignificant())) {
            long cap = r.getMeta().getHeapMaxBytes() != null && r.getMeta().getHeapMaxBytes() > 0 ? r.getMeta().getHeapMaxBytes() : maxTotal;
            double ratio = cap > 0 ? t.getLastAfterBytes() / cap : 0;
            String sev = ratio >= 0.85 ? "Critical" : ratio >= 0.6 ? "High" : "Medium";
            String basisLabel = "full".equals(t.getBasis()) ? "Full GC" : "remark".equals(t.getBasis()) ? "Remark" : "Mixed GC";
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("slopeMbPerHour", round3(t.getSlopeMbPerHour()));
            ev.put("r2", round3(t.getR2()));
            ev.put("lastAfterBytes", t.getLastAfterBytes().longValue());
            ev.put("basis", t.getBasis());
            if (trendGrowthBytes != null) ev.put("growthBytes", Math.round(trendGrowthBytes));
            if (trendDaysToFill != null) ev.put("daysToFill", round3(trendDaysToFill));
            out.add(f("LEAK_TREND", sev, basisLabel + " 직후 힙 사용량이 계속 증가합니다 (메모리 누수 의심)",
                    String.format(Locale.ROOT, "%s 직후 살아남는 객체가 %s → %s 로 늘었고 회귀 기울기 %s MB/h (R²=%.2f, %d점). 최대 힙 대비 %.0f%%.",
                            basisLabel, fmtBytes(t.getFirstAfterBytes()), fmtBytes(t.getLastAfterBytes()), fmtSlope(t.getSlopeMbPerHour()), t.getR2(), t.getPointsUsed(), ratio * 100)
                            + (trendDaysToFill != null ? " 이 속도면 최대 힙 90% 도달까지 " + fmtDays(trendDaysToFill) + "." : "")
                            + (t.getExcludedWarmup() > 0 ? String.format(Locale.ROOT, " 기동 직후 %d점은 제외.", t.getExcludedWarmup()) : ""),
                    "GC 로 회수되지 않는 객체가 누적되는 전형적 패턴입니다. 연결된 힙 덤프의 Leak Suspects·Dominator Tree 로 누적 주체를 확인하세요.",
                    ev));
        }
        // 힙 만수
        if (maxTotal > 0 && k.getLastHeapAfterBytes() != null) {
            double ratio = (double) k.getLastHeapAfterBytes() / maxTotal;
            Long lastFull = t.getLastAfterBytes() == null ? null : t.getLastAfterBytes().longValue();
            double fullRatio = lastFull == null ? 0 : (double) lastFull / maxTotal;
            if (("full".equals(t.getBasis()) && fullRatio >= 0.85) || ratio >= 0.9) {
                out.add(f("HEAP_NEAR_MAX", fullRatio >= 0.85 ? "High" : "Medium", "GC 후에도 힙이 거의 가득 차 있습니다",
                        String.format(Locale.ROOT, "마지막 GC 직후 사용량 %s / 최대 %s (%.0f%%)%s.", fmtBytes(k.getLastHeapAfterBytes()), fmtBytes(maxTotal), ratio * 100,
                                lastFull != null ? String.format(Locale.ROOT, ", 마지막 Full GC 직후 %.0f%%", fullRatio * 100) : ""),
                        "라이브 셋이 힙 용량에 근접해 OutOfMemoryError 직전 상태입니다. 누수가 아니면 -Xmx 를 늘리고, 누수면 힙 덤프로 원인을 잡아야 합니다.",
                        Map.of("lastAfterBytes", k.getLastHeapAfterBytes(), "maxTotalBytes", maxTotal)));
            }
        }
        // CMS 사이클이 Old 를 회수하지 못함 — 사이클 시작 시점에 이미 Old 가 가득 차 있다
        if (cmsMaxSaturatedRun >= 3) {
            Double interval = (cmsMarks >= 2 && cmsFirstMarkX != null && cmsLastMarkX > cmsFirstMarkX) ? (cmsLastMarkX - cmsFirstMarkX) / (cmsMarks - 1) : null;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("saturatedMarks", cmsSaturated);
            ev.put("initialMarks", cmsMarks);
            ev.put("maxConsecutive", cmsMaxSaturatedRun);
            ev.put("lastOldBytes", cmsLastOld);
            ev.put("lastOldTotalBytes", cmsLastOldTotal);
            if (interval != null) ev.put("avgIntervalSec", round3(interval));
            out.add(f("CMS_OLD_SATURATED", cmsMaxSaturatedRun >= 5 ? "Critical" : "High", "CMS 사이클이 Old 영역을 회수하지 못하고 있습니다",
                    String.format(Locale.ROOT, "CMS 사이클 시작(Initial Mark) %d회 중 %d회(연속 최대 %d회)에서 Old 점유율이 %.0f%% 이상이었습니다. 마지막 %s / %s%s.",
                            cmsMarks, cmsSaturated, cmsMaxSaturatedRun, CMS_SATURATED_RATIO * 100, fmtBytes(cmsLastOld), fmtBytes(cmsLastOldTotal),
                            interval != null ? String.format(Locale.ROOT, ", 사이클 평균 %s 간격", fmtDur(interval)) : ""),
                    "동시 수집이 끝나도 Old 가 비지 않아 곧바로 다음 사이클이 시작되는 상태로, 살아 있는 객체가 Old 용량을 넘었습니다(누수 또는 힙 부족). "
                            + "CMSInitiatingOccupancyFraction 조정으로는 풀리지 않습니다 — 연결된 힙 덤프로 누적 주체를 확인하고, 누수가 아니면 -Xmx(Old 영역)를 늘리세요. 방치하면 STW Full GC 반복과 OutOfMemoryError 로 이어집니다.",
                    ev));
        }
        // 처리량
        if (k.getThroughputPct() != null && durSec >= 60) {
            double tp = k.getThroughputPct();
            String sev = tp < 80 ? "Critical" : tp < 90 ? "High" : tp < 95 ? "Medium" : null;
            if (sev != null) out.add(f("GC_OVERHEAD", sev, "GC 에 쓰는 시간 비중이 큽니다",
                    String.format(Locale.ROOT, "애플리케이션 처리량 %.1f%% — 구간 %s 중 %s 를 GC 일시정지에 썼습니다 (%d회).", tp, fmtDur(durSec), fmtDur(all.total / 1000.0), pauseCount),
                    "힙이 부족하거나 할당률이 과도합니다. Full GC 빈도·누수 추세·할당률 소견을 함께 보고 힙 크기 또는 수집기 설정을 조정하세요.",
                    Map.of("throughputPct", round3(tp), "pauseTotalMs", round3(all.total))));
        }
        if (k.getMaxOverheadPct10m() != null && k.getMaxOverheadPct10m() >= 25 && durSec >= 600) {
            double o = k.getMaxOverheadPct10m();
            out.add(f("GC_OVERHEAD_BURST", o >= 50 ? "High" : "Medium", "특정 10분 구간에 GC 가 집중됐습니다",
                    String.format(Locale.ROOT, "uptime %s 부근 10분 창에서 시간의 %.0f%% 가 GC 일시정지였습니다.", fmtDur(k.getMaxOverheadWindowStartSec()), o),
                    "그 시각의 애플리케이션 부하·배치·대량 할당 코드를 확인하세요. 연결된 힙 덤프가 이 구간에 있으면 원인 후보입니다.",
                    Map.of("windowStartSec", round3(k.getMaxOverheadWindowStartSec()), "overheadPct", round3(o))));
        }
        // 긴 일시정지
        if (all.maxAt != null && all.maxHolderMs > 1000) {
            out.add(f("LONG_PAUSE", all.maxHolderMs > 5000 ? "High" : "Medium", "1초를 넘는 일시정지가 있습니다",
                    String.format(Locale.ROOT, "최장 %.0fms — %s (줄 %d).", all.maxHolderMs, all.maxAt.describe(), all.maxAt.line),
                    "응답 지연·타임아웃의 직접 원인입니다. Full GC 라면 힙 크기/누수를, Young 이라면 Survivor 넘침·승격 실패·CPU 경합(user+sys 대비 real)을 확인하세요.",
                    Map.of("maxPauseMs", round3(all.maxHolderMs), "line", all.maxAt.line, "type", all.maxAt.type.name())));
        }
        // 플래그 기반
        int tse = byFlag.getOrDefault(GcFlag.TO_SPACE_EXHAUSTED, 0);
        if (tse > 0) out.add(f("TO_SPACE_EXHAUSTED", tse >= 5 ? "Critical" : "High", "G1 대피 실패(To-space exhausted)",
                String.format(Locale.ROOT, "%d회. 살아남은 객체를 옮길 빈 리전이 없어 대피가 실패했고, 이어서 Full GC 로 이어지기 쉽습니다.", tse),
                "힙 여유(-Xmx) 또는 -XX:G1ReservePercent 를 늘리고, Humongous 할당·Old 점유가 높은지 확인하세요.",
                Map.of("count", tse)));
        int cmf = byFlag.getOrDefault(GcFlag.CONCURRENT_MODE_FAILURE, 0);
        if (cmf > 0) out.add(f("CONCURRENT_MODE_FAILURE", cmf >= 3 ? "Critical" : "High", "CMS concurrent mode failure",
                String.format(Locale.ROOT, "%d회. CMS 가 회수를 끝내기 전에 Old 가 가득 차 STW Full GC 로 떨어졌습니다.", cmf),
                "-XX:CMSInitiatingOccupancyFraction 을 낮추고(+UseCMSInitiatingOccupancyOnly), Old 크기를 늘리거나 G1 전환을 검토하세요.",
                Map.of("count", cmf)));
        int pf = byFlag.getOrDefault(GcFlag.PROMOTION_FAILED, 0);
        if (pf > 0) out.add(f("PROMOTION_FAILED", "High", "승격 실패(promotion failed)",
                String.format(Locale.ROOT, "%d회. Young 에서 살아남은 객체를 Old 로 옮길 공간이 없었습니다(단편화 또는 Old 부족).", pf),
                "Old 영역 크기·단편화를 점검하고, CMS 라면 Full GC 압축 주기나 G1 전환을 검토하세요.",
                Map.of("count", pf)));
        int hum = byFlag.getOrDefault(GcFlag.HUMONGOUS_ALLOC, 0);
        Long humBytes = (maxHumongous != null && regionSize != null) ? maxHumongous * regionSize : null;
        if ((humBytes != null && maxTotal > 0 && humBytes >= maxTotal * 0.10) || hum >= 20) {
            out.add(f("HUMONGOUS_HEAVY", "Medium", "Humongous(거대) 객체 할당이 많습니다",
                    (humBytes != null ? String.format(Locale.ROOT, "Humongous 리전 최대 %d개(%s, 힙의 %.0f%%). ", maxHumongous, fmtBytes(humBytes), maxTotal > 0 ? 100.0 * humBytes / maxTotal : 0) : "")
                            + String.format(Locale.ROOT, "Humongous 원인 GC %d회.", hum),
                    "리전 크기의 절반을 넘는 배열(대형 byte[]/String/컬렉션)이 Old 에 직접 들어가 단편화를 만듭니다. -XX:G1HeapRegionSize 확대 또는 대형 버퍼 재사용을 검토하세요.",
                    Map.of("maxHumongousRegions", maxHumongous == null ? 0 : maxHumongous, "humongousGcCount", hum)));
        }
        int meta = byFlag.getOrDefault(GcFlag.METADATA_THRESHOLD, 0);
        if (meta >= 3) out.add(f("METADATA_THRESHOLD", "Medium", "Metaspace 임계치로 인한 GC 가 반복됩니다",
                String.format(Locale.ROOT, "Metadata GC Threshold 원인 GC %d회. Metaspace %s → %s (예약 최대 %s).", meta, fmtBytes(metaFirst), fmtBytes(metaLast), fmtBytes(metaMaxTotal))
                        + (metaFull > 0 ? String.format(Locale.ROOT, " 그중 Full GC %d회(힙 압박이 아니라 Metaspace 부족).", metaFull) : ""),
                "클래스 로딩이 계속 늘고 있습니다(동적 프록시·스크립트·클래스로더 누수 의심). -XX:MetaspaceSize 를 올려 초기 GC 를 줄이고 클래스 누수를 점검하세요.",
                Map.of("count", meta, "fullCount", metaFull)));
        if (systemGc > 0) out.add(systemGcFinding(k, durSec));
        if (k.getAllocationRateMbPerSec() != null && k.getAllocationRateMbPerSec() > 500) {
            double ar = k.getAllocationRateMbPerSec();
            out.add(f("ALLOC_RATE_HIGH", ar > 1500 ? "High" : "Medium", "객체 할당률이 높습니다",
                    String.format(Locale.ROOT, "평균 %.0f MB/s. Young GC %d회.", ar, k.getYoungCount() + k.getMixedCount()),
                    "GC 빈도의 근본 원인입니다. 프로파일러로 할당 핫스팟(임시 객체·문자열 결합·박싱)을 찾고, Young 영역을 키워 GC 횟수를 줄이세요.",
                    Map.of("allocationRateMbPerSec", round3(ar))));
        }
        if (pauseCount >= 10 && sysHigh >= 3 && sysHigh * 10 >= pauseCount) out.add(f("SYS_TIME_HIGH", "Medium", "GC 중 시스템(sys) 시간 비중이 큽니다",
                String.format(Locale.ROOT, "일시정지 %d회 중 %d회에서 sys ≥ user×0.3.", pauseCount, sysHigh),
                "커널 시간이 크면 페이지 폴트·스왑·Transparent Huge Pages·메모리 압축이 의심됩니다. 스왑 사용량과 THP 설정, -XX:+AlwaysPreTouch 를 확인하세요.",
                Map.of("count", sysHigh)));
        if (pauseCount >= 10 && starvation >= 3 && starvation * 10 >= pauseCount) out.add(f("CPU_STARVATION", "Medium", "GC 스레드가 CPU 를 충분히 받지 못했습니다",
                String.format(Locale.ROOT, "일시정지 %d회 중 %d회에서 real 이 user+sys 의 2배를 넘었습니다.", pauseCount, starvation),
                "다른 프로세스와의 CPU 경합, 컨테이너 CPU 제한, 과도한 ParallelGCThreads 가 원인일 수 있습니다.",
                Map.of("count", starvation)));
        if ("ZGC".equals(collector) || "Shenandoah".equals(collector) || "Epsilon".equals(collector)) out.add(f("PARTIAL_SUPPORT", "Info", collector + " 로그는 일시정지·사이클 집계만 지원합니다",
                "세대별 크기·승격률·Full GC 추세는 이 수집기의 로그 형식에서 산출하지 않습니다.", "", Map.of("collector", collector)));
        if (limitHit) out.add(f("LOG_TRUNCATED_LIMIT", "Low", "이벤트 상한에 도달해 뒷부분을 읽지 않았습니다",
                String.format(Locale.ROOT, "%,d건에서 중단. 통계는 앞부분 기준입니다.", eventCount), "로그를 회전 단위로 나눠 올리세요.", Map.of("eventCount", eventCount)));
        if ("mtime".equals(r.getMeta().getTimeSource())) out.add(f("TIME_FROM_MTIME", "Info", "로그에 절대 시각이 없어 파일 수정 시각으로 기간을 추정했습니다",
                "-Xlog 데코레이션에 time 이 없거나(JDK 8 은 -XX:+PrintGCDateStamps 미지정) uptime 만 있습니다. 덤프 매칭 시간 신호는 약한 근거로만 씁니다.",
                "다음부터는 -Xlog:gc*:file=…:time,uptime,level,tags (JDK 8: -XX:+PrintGCDateStamps) 로 남기세요.", Map.of()));
        else if ("none".equals(r.getMeta().getTimeSource())) out.add(f("NO_ABSOLUTE_TIME", "Info", "로그에 절대 시각이 없습니다",
                "uptime 만으로는 덤프 생성 시각과 대조할 수 없어 시간 기반 자동 매칭에서 제외됩니다.", "수동 매칭을 이용하거나 time 데코레이션을 켜서 다시 수집하세요.", Map.of()));
        if (droppedNote > 0) out.add(f("INCOMPLETE_EVENTS", "Info", "미종결 이벤트를 버렸습니다",
                String.format(Locale.ROOT, "%d건 — 기록 중인 파일이거나 회전 경계에서 잘린 이벤트입니다.", droppedNote), "", Map.of("count", droppedNote)));

        out.sort((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())));
        return out;
    }

    /**
     * System.gc() 소견(2026-09-15 재작성) — 호출 단위 횟수 · 규칙적 주기와 원인 추정(1시간 = RMI 분산 GC) · 기동 직후 첫 호출 ·
     * 영향 기준 심각도(명시적 Full 최장 ≥1초 또는 시간당 6회 초과면 Medium) · 수집기에 맞는 권고.
     */
    private Finding systemGcFinding(GcLogResult.Kpi k, double durSec) {
        Double interval = k.getSystemGcIntervalSec();
        boolean rmiLike = interval != null && interval >= 3300 && interval <= 3900;
        double perHour = durSec > 0 ? systemGc / (durSec / 3600.0) : 0;
        String sev = (explicitFullMaxMs >= 1000 || perHour > 6) ? "Medium" : "Low";
        String col = collector == null ? "" : collector;
        boolean stwCollector = col.equals("Parallel") || col.equals("Serial");

        StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "System.gc() 호출 %d회", systemGc));
        if (durSec > 0) d.append(String.format(Locale.ROOT, "(시간당 %.2f회)", perHour));
        if (explicitFull > 0) {
            d.append(String.format(Locale.ROOT, " — 명시적 Full GC %d회, 1회 평균 %.0fms · 최장 %.0fms", explicitFull, explicitFullTotalMs / explicitFull, explicitFullMaxMs));
        }
        d.append('.');
        if (pairedExplicit > 0) {
            d.append(String.format(Locale.ROOT, " %s 수집기는 호출마다 Young 수집 → Full GC 두 단계를 기록하므로 이벤트로는 %d건입니다(짝지어 한 번으로 셌습니다).",
                    col.isEmpty() ? "이" : col, systemGc + pairedExplicit));
        }
        if (interval != null) {
            d.append(" 호출이 약 ").append(fmtInterval(interval)).append(" 간격으로 규칙적입니다 — 부하와 무관한 타이머성 호출(JVM 내부 데몬 또는 스케줄러)로 보입니다.");
        }
        if (rmiLike) {
            d.append(" 1시간 주기는 RMI 분산 GC(sun.rmi.dgc.client.gcInterval / sun.rmi.dgc.server.gcInterval, 기본 3600000ms)의 전형입니다.");
        }
        if (firstSysGcX != null && xIsUptime && firstSysGcX < WARMUP_SEC) {
            d.append(" 첫 호출은 기동 ").append(firstSysGcX < 60 ? String.format(Locale.ROOT, "%.1f초", firstSysGcX) : fmtDur(firstSysGcX))
                    .append(" 시점으로, WAS·프레임워크 기동 코드의 명시적 호출로 보입니다.");
        }
        if (explicitFull > 0) {
            d.append(" 명시적 Full GC 는 메모리 압박 신호가 아니므로 Full GC 빈도 판정에서 제외했습니다.");
        }

        StringBuilder a = new StringBuilder();
        if ("Low".equals(sev)) a.append("1회 일시정지가 짧아 서비스 영향은 작습니다 — 조치가 꼭 필요하지는 않습니다. ");
        if (rmiLike) {
            a.append("주기를 늘리려면 -Dsun.rmi.dgc.client.gcInterval=86400000 -Dsun.rmi.dgc.server.gcInterval=86400000(24시간) 처럼 지정하세요. ");
        }
        if (stwCollector) {
            a.append("-XX:+ExplicitGCInvokesConcurrent 는 ").append(col).append(" 수집기에서는 효과가 없습니다(G1·CMS 전용). ");
        } else if (col.equals("G1") || col.equals("CMS")) {
            a.append("-XX:+ExplicitGCInvokesConcurrent 로 명시적 호출을 동시 사이클로 바꾸면 STW Full GC 를 피할 수 있습니다. ");
        }
        a.append("-XX:+DisableExplicitGC 는 NIO Direct 버퍼 회수가 System.gc() 에 기대는 경우 Direct 메모리 OutOfMemoryError 를 부를 수 있어 신중히 쓰세요.");

        String title = String.format(Locale.ROOT, "System.gc() 명시적 호출 %d회", systemGc) + (interval != null ? " (약 " + fmtInterval(interval) + " 간격)" : "");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("count", systemGc);
        ev.put("explicitFullCount", explicitFull);
        ev.put("pairedEvents", pairedExplicit);
        if (explicitFull > 0) ev.put("explicitFullMaxMs", round3(explicitFullMaxMs));
        if (interval != null) ev.put("intervalSec", interval);
        ev.put("rmiLike", rmiLike);
        return f("SYSTEM_GC", sev, title, d.toString(), a.toString().trim(), ev);
    }

    private int droppedNote;
    /** finish 전에 호출 — 미종결 폐기 건수를 소견에 싣는다. */
    public void setDroppedIncomplete(int n) { this.droppedNote = n; }

    private static Finding f(String code, String sev, String title, String detail, String advice, Map<String, Object> ev) {
        Finding x = new Finding();
        x.setCode(code); x.setSeverity(sev); x.setTitle(title); x.setDetail(detail); x.setAdvice(advice);
        x.setEvidence(new LinkedHashMap<>(ev));
        return x;
    }

    static int sevRank(String s) {
        if (s == null) return 0;
        switch (s) { case "Critical": return 5; case "High": return 4; case "Medium": return 3; case "Low": return 2; case "Info": return 1; default: return 0; }
    }

    private static String maxSeverity(List<Finding> fs) {
        String best = null; int rank = 0;
        for (Finding f : fs) {
            int r = sevRank(f.getSeverity());
            if (r > rank && !"Info".equals(f.getSeverity())) { rank = r; best = f.getSeverity(); }
        }
        return best == null ? "Low" : best;
    }

    // ── 수치 헬퍼 ───────────────────────────────────────────────

    /** 최소제곱 회귀 — x 초, y 바이트 → {slope MB/h, R²}. */
    static double[] regression(List<double[]> pts) {
        int n = pts.size();
        double sx = 0, sy = 0;
        for (double[] p : pts) { sx += p[0] / 3600.0; sy += p[1] / 1048576.0; }
        double mx = sx / n, my = sy / n;
        double sxx = 0, sxy = 0, syy = 0;
        for (double[] p : pts) {
            double dx = p[0] / 3600.0 - mx, dy = p[1] / 1048576.0 - my;
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy;
        }
        if (sxx == 0) return new double[]{0, 0};
        double slope = sxy / sxx;
        double r2 = syy == 0 ? 0 : (sxy * sxy) / (sxx * syy);
        return new double[]{slope, r2};
    }

    private static List<double[]> thin(List<double[]> list, int target) {
        List<double[]> out = new ArrayList<>(target);
        double step = (double) list.size() / target;
        for (int i = 0; i < target; i++) out.add(list.get((int) Math.min(list.size() - 1, Math.floor(i * step))));
        return out;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double nz(Double v) { return v == null ? 0 : v; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static Long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return null; } }

    static String fmtBytes(Number b) {
        if (b == null) return "?";
        double v = b.doubleValue();
        if (v >= 1L << 30) return String.format(Locale.ROOT, "%.2f GB", v / (1L << 30));
        if (v >= 1L << 20) return String.format(Locale.ROOT, "%.1f MB", v / (1L << 20));
        if (v >= 1L << 10) return String.format(Locale.ROOT, "%.0f KB", v / (1L << 10));
        return String.format(Locale.ROOT, "%.0f B", v);
    }

    static String fmtDur(Double sec) {
        if (sec == null) return "?";
        long s = Math.round(sec);
        if (s >= 3600) return String.format(Locale.ROOT, "%dh %02dm", s / 3600, (s % 3600) / 60);
        if (s >= 60) return String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60);
        return String.format(Locale.ROOT, "%.1fs", sec);
    }

    // ── 일시정지 누적기 ──────────────────────────────────────────

    private static final class PauseAcc {
        int count; double total, max; double maxHolderMs = -1; GcEvent maxAt;
        double[] exact = new double[256]; int n;
        final int[] hist = new int[256];

        void add(double ms) {
            count++; total += ms; if (ms > max) max = ms;
            if (n < EXACT_PAUSE_CAP) {
                if (n == exact.length) exact = Arrays.copyOf(exact, Math.min(EXACT_PAUSE_CAP, exact.length * 2));
                exact[n++] = ms;
            }
            hist[bucket(ms)]++;
        }

        static int bucket(double ms) {
            if (ms <= 0.01) return 0;
            int b = (int) Math.floor(Math.log10(ms / 0.01) * 32.0);
            return Math.max(0, Math.min(255, b));
        }

        static double bucketLow(int b) { return 0.01 * Math.pow(10, b / 32.0); }

        PauseStats stats() {
            PauseStats s = new PauseStats();
            s.setCount(count);
            s.setTotalMs(round3(total));
            if (count == 0) return s;
            s.setAvgMs(round3(total / count));
            s.setMaxMs(round3(max));
            if (n == count) {
                double[] a = Arrays.copyOf(exact, n);
                Arrays.sort(a);
                s.setP50Ms(round3(a[idx(n, 0.50)]));
                s.setP95Ms(round3(a[idx(n, 0.95)]));
                s.setP99Ms(round3(a[idx(n, 0.99)]));
            } else {
                s.setApproximate(true);
                s.setP50Ms(round3(pct(0.50)));
                s.setP95Ms(round3(pct(0.95)));
                s.setP99Ms(round3(pct(0.99)));
            }
            if (maxAt != null) {
                s.setMaxAtUptimeSec(maxAt.uptimeSec);
                s.setMaxAtEpochMs(maxAt.tsEpochMs);
                s.setMaxAtLine(maxAt.line);
                s.setMaxDescription(maxAt.describe());
            }
            return s;
        }

        private static int idx(int n, double p) { return Math.max(0, Math.min(n - 1, (int) Math.ceil(p * n) - 1)); }

        private double pct(double p) {
            long target = Math.max(1, Math.round(p * count));
            long cum = 0;
            for (int b = 0; b < hist.length; b++) { cum += hist[b]; if (cum >= target) return bucketLow(b); }
            return max;
        }
    }
}

package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.parser.gclog.GcLogTestSupport.Recorder;
import org.junit.jupiter.api.Test;

import static com.heapdump.analyzer.parser.gclog.GcLogTestSupport.runJdk8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDK 8 {@code -XX:+PrintGCDetails} 파서 골든 테스트 — Parallel / CMS / Serial / G1, 날짜 스탬프 유무, 다중 행 이벤트. */
class Jdk8GcLogParserTest {

    private static final String D1 = "2026-09-14T00:52:33.485+0900";
    private static final String D2 = "2026-09-14T00:52:40.000+0900";

    @Test
    void parallelYoungAndFullWithDateStamps() {
        Recorder r = runJdk8(
                "Java HotSpot(TM) 64-Bit Server VM (25.202-b08) for linux-amd64 JRE (1.8.0_202-b08), built on Dec 15 2018 12:34:56 by \"java_re\" with gcc 7.3.0",
                "Memory: 4k page, physical 16000000k(8000000k free), swap 0k(0k free)",
                "CommandLine flags: -XX:InitialHeapSize=268435456 -XX:MaxHeapSize=4294967296 -XX:+PrintGC -XX:+PrintGCDateStamps -XX:+PrintGCDetails -XX:+UseParallelGC",
                D1 + ": 0.325: [GC (Allocation Failure) [PSYoungGen: 33280K->5100K(38400K)] 33280K->5108K(125952K), 0.0067 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                D2 + ": 6.840: [Full GC (Ergonomics) [PSYoungGen: 5088K->0K(38400K)] [ParOldGen: 87000K->60000K(87552K)] 92088K->60000K(125952K), [Metaspace: 3000K->3000K(1056768K)], 0.1234 secs] [Times: user=0.40 sys=0.00, real=0.12 secs]");
        assertEquals("1.8.0_202-b08", r.meta.get("jdkVersion"));
        assertEquals("Parallel", r.meta.get("collector"));
        assertTrue(r.meta.get("jvmOptions").contains("-XX:+UseParallelGC"));
        assertEquals(2, r.events.size());
        GcEvent y = r.events.get(0);
        assertEquals(GcEventType.YOUNG, y.type);
        assertEquals("Allocation Failure", y.cause);
        assertEquals(6.7, y.pauseMs, 1e-9);
        assertEquals(33280L << 10, y.youngBefore);
        assertEquals(5100L << 10, y.youngAfter);
        assertEquals(33280L << 10, y.heapBefore);
        assertEquals(5108L << 10, y.heapAfter);
        assertEquals(125952L << 10, y.heapTotal);
        assertEquals(0.325, y.uptimeSec, 1e-9);
        assertEquals(GcLogSupport.parseIsoEpochMs(D1), y.tsEpochMs);
        assertEquals(0.01, y.realSec, 1e-9);
        GcEvent f = r.events.get(1);
        assertEquals(GcEventType.FULL, f.type);
        assertEquals("Ergonomics", f.cause);
        assertTrue(f.flags.contains(GcFlag.ERGONOMICS));
        assertEquals(123.4, f.pauseMs, 1e-9);
        assertEquals(87000L << 10, f.oldBefore);
        assertEquals(60000L << 10, f.oldAfter);
        assertEquals(3000L << 10, f.metaAfter);
        assertEquals(1056768L << 10, f.metaTotal);
        assertEquals(60000L << 10, f.heapAfter);
        assertEquals(0.40, f.userSec, 1e-9);
    }

    @Test
    void uptimeOnlyPrefixLeavesTimestampNull() {
        Recorder r = runJdk8("0.325: [GC (Allocation Failure) [PSYoungGen: 33280K->5100K(38400K)] 33280K->5108K(125952K), 0.0067 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]");
        assertEquals(1, r.events.size());
        assertNull(r.events.get(0).tsEpochMs);
        assertEquals(0.325, r.events.get(0).uptimeSec, 1e-9);
    }

    @Test
    void tenuringDistributionSplitsEventAcrossLines() {
        Recorder r = runJdk8(
                D1 + ": 0.325: [GC (Allocation Failure) ",
                "Desired survivor size 5242880 bytes, new threshold 7 (max 15)",
                "- age   1:    1234567 bytes,    1234567 total",
                "[PSYoungGen: 33280K->5100K(38400K)] 33280K->5108K(125952K), 0.0067 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                D2 + ": 1.000: [GC (Allocation Failure) [PSYoungGen: 40000K->5000K(38400K)] 45000K->10000K(125952K), 0.0050 secs] [Times: user=0.01 sys=0.00, real=0.00 secs]");
        assertEquals(2, r.events.size());
        assertEquals(6.7, r.events.get(0).pauseMs, 1e-9);
        assertEquals(33280L << 10, r.events.get(0).heapBefore);
        assertEquals(1, r.events.get(0).line);
        assertEquals(5.0, r.events.get(1).pauseMs, 1e-9);
    }

    @Test
    void cmsCycleWithInitialMarkConcurrentPhasesFinalRemarkAndFailures() {
        Recorder r = runJdk8(
                D1 + ": 1.000: [GC (Allocation Failure) " + D1 + ": 1.000: [ParNew: 100000K->10000K(200000K), 0.0100 secs] 100000K->50000K(1000000K), 0.0120 secs] [Times: user=0.02 sys=0.00, real=0.01 secs]",
                D1 + ": 2.000: [GC (CMS Initial Mark) [1 CMS-initial-mark: 500000K(800000K)] 600000K(1000000K), 0.0050 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                D1 + ": 2.100: [CMS-concurrent-mark-start]",
                D1 + ": 2.200: [CMS-concurrent-mark: 0.100/0.150 secs] [Times: user=0.30 sys=0.00, real=0.15 secs]",
                D1 + ": 2.300: [CMS-concurrent-preclean-start]",
                D1 + ": 2.400: [CMS-concurrent-preclean: 0.010/0.010 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                D1 + ": 2.500: [GC (CMS Final Remark) [YG occupancy: 100000 K (200000 K)]" + D1 + ": 2.500: [Rescan (parallel) , 0.0010 secs]" + D1 + ": 2.501: [weak refs processing, 0.0001 secs]" + D1 + ": 2.501: [class unloading, 0.0020 secs] [1 CMS-remark: 500000K(800000K)] 600000K(1000000K), 0.0100 secs] [Times: user=0.03 sys=0.00, real=0.01 secs]",
                D1 + ": 2.600: [CMS-concurrent-sweep-start]",
                D1 + ": 2.700: [CMS-concurrent-sweep: 0.100/0.100 secs] [Times: user=0.10 sys=0.00, real=0.10 secs]",
                D2 + ": 3.000: [GC (Allocation Failure) " + D2 + ": 3.000: [ParNew (promotion failed): 100000K->100000K(200000K), 0.0100 secs]" + D2 + ": 3.010: [CMS (concurrent mode failure): 700000K->300000K(800000K), 0.5000 secs] 800000K->300000K(1000000K), [Metaspace: 3000K->3000K(1056768K)], 0.5200 secs] [Times: user=0.60 sys=0.01, real=0.52 secs]",
                D2 + ": 4.000: [Full GC (Allocation Failure) " + D2 + ": 4.000: [CMS: 700000K->300000K(800000K), 0.0500 secs] 800000K->300000K(1000000K), [Metaspace: 3000K->3000K(1056768K)], 0.0510 secs] [Times: user=0.05 sys=0.00, real=0.05 secs]");
        assertEquals("CMS", r.meta.get("collector"));
        assertEquals(8, r.events.size(), "young, initial-mark, conc-mark, conc-preclean, final-remark, conc-sweep, promotion-failed full, full — -start 줄은 제외");
        GcEvent young = r.events.get(0);
        assertEquals(GcEventType.YOUNG, young.type);
        assertEquals(12.0, young.pauseMs, 1e-9, "안쪽 ParNew 0.0100 이 아니라 바깥 0.0120");
        assertEquals(100000L << 10, young.youngBefore);
        assertEquals(50000L << 10, young.heapAfter);
        assertEquals(GcEventType.CMS_INITIAL_MARK, r.events.get(1).type);
        assertEquals(5.0, r.events.get(1).pauseMs, 1e-9);
        assertEquals(500000L << 10, r.events.get(1).oldBefore, "Initial Mark 의 순간 Old 점유량");
        assertEquals(800000L << 10, r.events.get(1).oldTotal);
        assertEquals(1000000L << 10, r.events.get(1).heapTotal);
        assertNull(r.events.get(1).heapAfter, "점유량은 수집 전후가 아니다 — 추세·할당률에 넣지 않는다");
        GcEvent mark = r.events.get(2);
        assertEquals(GcEventType.CONCURRENT_CYCLE, mark.type);
        assertTrue(mark.concurrent);
        assertEquals(150.0, mark.pauseMs, 1e-9, "wall 시간(뒤 숫자)");
        assertEquals(GcEventType.CMS_FINAL_REMARK, r.events.get(4).type);
        assertEquals(10.0, r.events.get(4).pauseMs, 1e-9, "여러 개의 안쪽 secs 를 지나 바깥 0.0100");
        assertTrue(r.events.get(5).concurrent, "sweep");
        GcEvent pf = r.events.get(6);
        assertEquals(GcEventType.FULL, pf.type, "[GC 안의 [CMS (concurrent mode failure): …] 는 Old 까지 STW 로 치운 전체 수집");
        assertTrue(pf.flags.contains(GcFlag.PROMOTION_FAILED));
        assertTrue(pf.flags.contains(GcFlag.CONCURRENT_MODE_FAILURE));
        assertEquals(520.0, pf.pauseMs, 1e-9);
        assertEquals(700000L << 10, pf.oldBefore);
        assertEquals(300000L << 10, pf.oldAfter);
        assertEquals(300000L << 10, pf.heapAfter);
        assertEquals(3000L << 10, pf.metaAfter);
        assertEquals(GcEventType.FULL, r.events.get(7).type);
        assertEquals(51.0, r.events.get(7).pauseMs, 1e-9);
    }

    @Test
    void cmsFullGcAfterSweepIsFull() {
        Recorder r = runJdk8(
                D2 + ": 4.000: [Full GC (Allocation Failure) " + D2 + ": 4.000: [CMS: 700000K->300000K(800000K), 0.0500 secs] 800000K->300000K(1000000K), [Metaspace: 3000K->3000K(1056768K)], 0.0510 secs] [Times: user=0.05 sys=0.00, real=0.05 secs]");
        assertEquals(1, r.events.size());
        assertEquals(GcEventType.FULL, r.events.get(0).type);
        assertEquals(51.0, r.events.get(0).pauseMs, 1e-9);
        assertEquals(800000L << 10, r.events.get(0).heapBefore);
    }

    @Test
    void serialCollectorIsDetectedFromDefNewTenured() {
        Recorder r = runJdk8(
                "0.500: [GC (Allocation Failure) 0.500: [DefNew: 100K->10K(200K), 0.0010 secs] 100K->50K(1000K), 0.0012 secs] [Times: user=0.00 sys=0.00, real=0.00 secs]",
                "1.000: [Full GC (System.gc()) 1.000: [Tenured: 800K->300K(800K), 0.0500 secs] 900K->300K(1000K), [Metaspace: 3000K->3000K(1056768K)], 0.0510 secs] [Times: user=0.05 sys=0.00, real=0.05 secs]");
        assertEquals("Serial", r.meta.get("collector"));
        assertEquals(2, r.events.size());
        assertTrue(r.events.get(1).flags.contains(GcFlag.SYSTEM_GC));
        assertEquals(800L << 10, r.events.get(1).oldBefore);
    }

    @Test
    void g1Jdk8MultiLinePauseWithTrailerAndConcurrentPhases() {
        Recorder r = runJdk8(
                D1 + ": 0.300: [GC pause (G1 Evacuation Pause) (young), 0.0034 secs]",
                "   [Parallel Time: 3.0 ms, GC Workers: 4]",
                "      [GC Worker Start (ms): Min: 300.4, Avg: 300.5, Max: 300.6, Diff: 0.2]",
                "   [Code Root Fixup: 0.0 ms]",
                "   [Eden: 24.0M(24.0M)->0.0B(20.0M) Survivors: 0.0B->4096.0K Heap: 24.0M(256.0M)->10.2M(256.0M)]",
                " [Times: user=0.01 sys=0.00, real=0.00 secs] ",
                D1 + ": 6.000: [GC pause (G1 Humongous Allocation) (young) (initial-mark), 0.0100 secs]",
                "   [Eden: 20.0M(20.0M)->0.0B(20.0M) Survivors: 4096.0K->4096.0K Heap: 100.0M(256.0M)->90.0M(256.0M)]",
                " [Times: user=0.02 sys=0.00, real=0.01 secs] ",
                D1 + ": 6.100: [GC concurrent-root-region-scan-start]",
                D1 + ": 6.200: [GC concurrent-root-region-scan-end, 0.0100 secs]",
                D1 + ": 6.300: [GC concurrent-mark-start]",
                D1 + ": 7.000: [GC concurrent-mark-end, 0.7000 secs]",
                D1 + ": 7.100: [GC remark " + D1 + ": 7.100: [Finalize Marking, 0.0010 secs] " + D1 + ": 7.101: [GC ref-proc, 0.0010 secs] " + D1 + ": 7.102: [Unloading, 0.0050 secs], 0.0100 secs]",
                " [Times: user=0.03 sys=0.00, real=0.01 secs] ",
                D1 + ": 7.200: [GC cleanup 100M->90M(256M), 0.0010 secs]",
                " [Times: user=0.00 sys=0.00, real=0.00 secs] ",
                D2 + ": 8.000: [GC pause (G1 Evacuation Pause) (mixed), 0.0200 secs]",
                "   [Eden: 20.0M(20.0M)->0.0B(20.0M) Survivors: 4096.0K->4096.0K Heap: 120.0M(256.0M)->80.0M(256.0M)]",
                " [Times: user=0.04 sys=0.00, real=0.02 secs] ",
                D2 + ": 9.000: [GC pause (G1 Evacuation Pause) (young) (to-space exhausted), 0.1000 secs]",
                "   [Eden: 20.0M(20.0M)->0.0B(20.0M) Survivors: 4096.0K->4096.0K Heap: 250.0M(256.0M)->250.0M(256.0M)]",
                " [Times: user=0.10 sys=0.00, real=0.10 secs] ",
                D2 + ": 9.200: [Full GC (Allocation Failure)  250M->100M(256M), 0.5000 secs]",
                "   [Eden: 0.0B(20.0M)->0.0B(20.0M) Survivors: 0.0B->0.0B Heap: 250.0M(256.0M)->100.0M(256.0M)], [Metaspace: 3000K->3000K(1056768K)]",
                " [Times: user=0.50 sys=0.00, real=0.50 secs] ");
        assertEquals("G1", r.meta.get("collector"));
        assertEquals(9, r.events.size(), "young, initial-mark young, scan-end, mark-end, remark, cleanup, mixed, to-space young, full");
        GcEvent y = r.events.get(0);
        assertEquals(GcEventType.YOUNG, y.type);
        assertEquals(3.4, y.pauseMs, 1e-9);
        assertEquals((long) (24.0 * (1 << 20)), y.heapBefore, "트레일러 [Eden: … Heap: …] 에서 힙 총합");
        assertEquals((long) (10.2 * (1 << 20)), y.heapAfter, 1);
        assertEquals(256L << 20, y.heapTotal);
        assertEquals(24L << 20, y.youngBefore);
        assertEquals(0.01, y.userSec, 1e-9, "들여쓴 [Times:] 트레일러");
        GcEvent im = r.events.get(1);
        assertTrue(im.flags.contains(GcFlag.INITIAL_MARK));
        assertTrue(im.flags.contains(GcFlag.HUMONGOUS_ALLOC));
        assertTrue(r.events.get(2).concurrent);
        assertEquals(700.0, r.events.get(3).pauseMs, 1e-9);
        assertEquals(GcEventType.REMARK, r.events.get(4).type);
        assertEquals(10.0, r.events.get(4).pauseMs, 1e-9);
        assertEquals(GcEventType.CLEANUP, r.events.get(5).type);
        assertEquals(100L << 20, r.events.get(5).heapBefore);
        assertEquals(GcEventType.MIXED, r.events.get(6).type);
        assertTrue(r.events.get(7).flags.contains(GcFlag.TO_SPACE_EXHAUSTED));
        GcEvent full = r.events.get(8);
        assertEquals(GcEventType.FULL, full.type);
        assertEquals(500.0, full.pauseMs, 1e-9);
        assertEquals(100L << 20, full.heapAfter);
        assertEquals(3000L << 10, full.metaAfter);
    }

    @Test
    void unterminatedLastEventIsDroppedAndCounted() {
        Recorder rec = new Recorder();
        Jdk8GcLogParser p = new Jdk8GcLogParser(rec, 1000);
        p.feedLine(1, D1 + ": 0.325: [GC (Allocation Failure) [PSYoungGen: 33280K->5100K(38400K)] 33280K->5108K(125952K), 0.0067 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]");
        p.feedLine(2, D2 + ": 1.000: [GC (Allocation Failure) [PSYoungGen: 40000K->");
        p.finish();
        assertEquals(1, rec.events.size());
        assertEquals(1, p.droppedIncomplete());
    }

    @Test
    void applicationStoppedTimeLinesFlushPendingWithoutBreakingNextEvent() {
        Recorder r = runJdk8(
                D1 + ": 0.325: [GC (Allocation Failure) [PSYoungGen: 33280K->5100K(38400K)] 33280K->5108K(125952K), 0.0067 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                D1 + ": 0.340: Total time for which application threads were stopped: 0.0068 secs, Stopping threads took: 0.0001 secs",
                D1 + ": 0.400: Application time: 0.0600 seconds",
                D2 + ": 1.000: [GC (Allocation Failure) [PSYoungGen: 40000K->5000K(38400K)] 45000K->10000K(125952K), 0.0050 secs] [Times: user=0.01 sys=0.00, real=0.00 secs]");
        assertEquals(2, r.events.size());
        assertEquals(5.0, r.events.get(1).pauseMs, 1e-9);
    }

    // ── JDK 6/7 형식 (JEUS 8 운영 로그, 2026-09-14) ─────────────────────────────

    private static final String J1 = "2026-09-14T15:53:17.480+0900";

    /** 원인 괄호가 없고 PermGen 이 있는 JDK 7 이하 CMS 로그 — 운영 로그 원문 그대로. */
    @Test
    void legacyCmsWithoutCauseParensIsClassifiedByContent() {
        Recorder r = runJdk8(
                "2026-09-14T15:53:15.855+0900: 117326041.546: [CMS-concurrent-mark-start]",
                "2026-09-14T15:53:17.472+0900: 117326043.163: [CMS-concurrent-mark: 1.617/1.617 secs] [Times: user=1.58 sys=0.01, real=1.61 secs]",
                J1 + ": 117326043.171: [GC[YG occupancy: 467357 K (471872 K)]117326043.171: [Rescan (parallel) , 0.3658740 secs]117326043.537: [weak refs processing, 0.0000250 secs]117326043.537: [class unloading, 0.0185140 secs]117326043.555: [scrub symbol & string tables, 0.0148480 secs] [1 CMS-remark: 1572858K(1572864K)] 2040215K(2044736K), 0.4037040 secs] [Times: user=0.91 sys=0.02, real=0.41 secs]",
                "2026-09-14T15:53:19.079+0900: 117326044.770: [CMS-concurrent-reset: 0.007/0.007 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                "2026-09-14T15:53:21.081+0900: 117326046.772: [GC [1 CMS-initial-mark: 1572858K(1572864K)] 2040524K(2044736K), 0.3811460 secs] [Times: user=0.35 sys=0.00, real=0.38 secs]",
                "{Heap before GC invocations=880400 (full 13726962):",
                " par new generation   total 471872K, used 471872K [0x0000000740000000, 0x0000000760000000, 0x0000000760000000)",
                "  eden space 419456K, 100% used [0x0000000740000000, 0x00000007599a0000, 0x00000007599a0000)",
                " concurrent mark-sweep generation total 1572864K, used 1572858K [0x0000000760000000, 0x00000007c0000000, 0x00000007c0000000)",
                " concurrent-mark-sweep perm gen total 1048576K, used 120715K [0x00000007c0000000, 0x0000000800000000, 0x0000000800000000)",
                "2026-09-14T15:53:54.081+0900: 117326079.772: [GC 117326079.772: [ParNew: 471872K->471872K(471872K), 0.0000280 secs]117326079.772: [CMS: 1572858K->1572858K(1572864K), 4.1819420 secs] 2044730K->2032970K(2044736K), [CMS Perm : 120715K->120714K(1048576K)], 4.1823090 secs] [Times: user=3.87 sys=0.05, real=4.19 secs]",
                "Heap after GC invocations=880401 (full 13726963):",
                " par new generation   total 471872K, used 460112K [0x0000000740000000, 0x0000000760000000, 0x0000000760000000)",
                "}",
                "2026-09-14T15:54:02.235+0900: 117326087.926: [GC[YG occupancy: 460354 K (471872 K)]117326087.926: [Rescan (parallel) , 0.2684320 secs]117326088.194: [weak refs processing, 0.0000160 secs]117326088.194: [class unloading, 0.0205480 secs]117326088.215: [scrub symbol & string tables, 0.0153420 secs] [1 CMS-remark: 1572858K(1572864K)] 2033212K(2044736K), 0.3088270 secs]");
        assertEquals("CMS", r.meta.get("collector"));
        assertEquals("true", r.meta.get("permGen"), "CMS Perm 라벨 = JDK 7 이하");
        assertEquals(6, r.events.size(), "mark, remark, reset, initial-mark, full, 마지막 remark — PrintHeapAtGC 블록은 이벤트가 아니다");

        GcEvent remark = r.events.get(1);
        assertEquals(GcEventType.CMS_FINAL_REMARK, remark.type, "[GC[YG occupancy: … [1 CMS-remark: …] — 원인 괄호가 없어도 Young 이 아니다");
        assertEquals(403.704, remark.pauseMs, 1e-6, "안쪽 Rescan/weak refs 가 아니라 바깥 secs");
        assertEquals(1572858L << 10, remark.oldBefore);
        assertEquals(1572864L << 10, remark.oldTotal);
        assertEquals(2044736L << 10, remark.heapTotal);
        assertEquals(0.41, remark.realSec, 1e-9);

        GcEvent im = r.events.get(3);
        assertEquals(GcEventType.CMS_INITIAL_MARK, im.type, "[GC [1 CMS-initial-mark: …]");
        assertEquals(381.146, im.pauseMs, 1e-6);
        assertEquals(1572858L << 10, im.oldBefore);

        GcEvent full = r.events.get(4);
        assertEquals(GcEventType.FULL, full.type, "[GC [ParNew: …][CMS: …] 는 Old 까지 치운 전체 수집(PrintHeapAtGC 의 full 카운트도 1 증가)");
        assertNull(full.cause);
        assertEquals(4182.309, full.pauseMs, 1e-6);
        assertEquals(471872L << 10, full.youngBefore);
        assertEquals(1572858L << 10, full.oldAfter);
        assertEquals(2044730L << 10, full.heapBefore);
        assertEquals(2032970L << 10, full.heapAfter);
        assertEquals(2044736L << 10, full.heapTotal);
        assertEquals(120714L << 10, full.metaAfter, "CMS Perm");
        assertEquals(3.87, full.userSec, 1e-9);

        GcEvent last = r.events.get(5);
        assertEquals(GcEventType.CMS_FINAL_REMARK, last.type, "[Times:] 없이 파일이 끝나도 finish 에서 내보낸다");
        assertEquals(308.827, last.pauseMs, 1e-6);
        assertEquals(15, last.line);
    }

    @Test
    void concurrentPhaseInterleavedIntoFullCollectionIsSplitOut() {
        Recorder r = runJdk8(
                "2026-09-14T15:53:54.081+0900: 117326079.772: [GC 117326079.772: [ParNew: 471872K->471872K(471872K), 0.0000280 secs]117326079.772: [CMS2026-09-14T15:53:55.100+0900: 117326080.791: [CMS-concurrent-mark: 1.617/1.620 secs] [Times: user=1.58 sys=0.01, real=1.61 secs] ",
                " (concurrent mode failure): 1572858K->1400000K(1572864K), 4.1819420 secs] 2044730K->1860000K(2044736K), [CMS Perm : 120715K->120714K(1048576K)], 4.1823090 secs] [Times: user=3.87 sys=0.05, real=4.19 secs]",
                D2 + ": 9.000: [GC (Allocation Failure) " + D2 + ": 9.000: [ParNew (promotion failed): 100000K->100000K(200000K), 0.0100 secs]" + D2 + ": 9.010: [CMS" + D2 + ": 9.020: [CMS-concurrent-abortable-preclean-start]",
                " (concurrent mode failure): 700000K->300000K(800000K), 0.5000 secs] 800000K->300000K(1000000K), [Metaspace: 3000K->3000K(1056768K)], 0.5200 secs] [Times: user=0.60 sys=0.01, real=0.52 secs]");
        assertEquals(3, r.events.size(), "끼어든 mark 는 별도 동시 이벤트, -start 는 지우기만");
        GcEvent conc = r.events.get(0);
        assertTrue(conc.concurrent);
        assertEquals("CMS-concurrent-mark", conc.cause);
        assertEquals(1620.0, conc.pauseMs, 1e-9);
        assertEquals(117326080.791, conc.uptimeSec, 1e-6, "끼어든 단계 자신의 시각");

        GcEvent cmf = r.events.get(1);
        assertEquals(GcEventType.FULL, cmf.type);
        assertTrue(cmf.flags.contains(GcFlag.CONCURRENT_MODE_FAILURE));
        assertEquals(1572858L << 10, cmf.oldBefore, "라벨 [CMS 와 크기 사이의 동시 단계를 걷어내야 Old 로 잡힌다");
        assertEquals(1400000L << 10, cmf.oldAfter);
        assertEquals(2044730L << 10, cmf.heapBefore, "Old 트리플이 힙 총합으로 오인되지 않는다");
        assertEquals(1860000L << 10, cmf.heapAfter);
        assertEquals(3.87, cmf.userSec, 1e-9, "안쪽 동시 단계의 [Times:] 가 아니라 바깥 것");
        assertEquals(4182.309, cmf.pauseMs, 1e-6);

        GcEvent jdk8 = r.events.get(2);
        assertEquals(GcEventType.FULL, jdk8.type);
        assertTrue(jdk8.flags.contains(GcFlag.PROMOTION_FAILED));
        assertEquals(700000L << 10, jdk8.oldBefore);
        assertEquals(300000L << 10, jdk8.heapAfter);
    }

    @Test
    void parNewTenuringDistributionKeepsGenerationLabel() {
        Recorder r = runJdk8(
                "2026-09-14T15:50:00.000+0900: 117325800.000: [GC 117325800.000: [ParNew",
                "Desired survivor size 26836992 bytes, new threshold 1 (max 6)",
                "- age   1:   53671088 bytes,   53671088 total",
                ": 471872K->52416K(471872K), 0.0512340 secs] 1800000K->1420000K(2044736K), 0.0514560 secs] [Times: user=0.18 sys=0.00, real=0.05 secs]");
        assertEquals(1, r.events.size());
        GcEvent y = r.events.get(0);
        assertEquals(GcEventType.YOUNG, y.type);
        assertEquals("CMS", r.meta.get("collector"), "ParNew 라벨을 잃으면 수집기도 모른다");
        assertEquals(471872L << 10, y.youngBefore);
        assertEquals(52416L << 10, y.youngAfter);
        assertEquals(1800000L << 10, y.heapBefore);
        assertEquals(51.456, y.pauseMs, 1e-6);
    }

    @Test
    void legacyParallelAndSerialLabelsAndSystemCause() {
        Recorder r = runJdk8(
                "10.000: [GC-- [PSYoungGen: 100K->100K(200K)] 900K->950K(1000K), 0.0100 secs] [Times: user=0.01 sys=0.00, real=0.01 secs]",
                "11.000: [Full GC (System) [PSYoungGen: 10K->0K(200K)] [PSOldGen: 800K->300K(800K)] 810K->300K(1000K) [PSPermGen: 3000K->3000K(21248K)], 0.0500 secs] [Times: user=0.05 sys=0.00, real=0.05 secs]",
                "12.000: [GC 12.000: [DefNew: 100K->10K(200K), 0.0010 secs] 400K->310K(1000K), 0.0012 secs] [Times: user=0.00 sys=0.00, real=0.00 secs]");
        assertEquals("Parallel", r.meta.get("collector"));
        assertEquals("true", r.meta.get("permGen"));
        assertEquals(3, r.events.size());
        assertEquals(GcEventType.YOUNG, r.events.get(0).type);
        assertTrue(r.events.get(0).flags.contains(GcFlag.PROMOTION_FAILED), "[GC-- = JDK 6/7 Parallel 승격 실패");
        GcEvent full = r.events.get(1);
        assertEquals(GcEventType.FULL, full.type);
        assertTrue(full.flags.contains(GcFlag.SYSTEM_GC), "JDK 6/7 은 (System.gc()) 가 아니라 (System)");
        assertEquals(800L << 10, full.oldBefore, "PSOldGen");
        assertEquals(3000L << 10, full.metaAfter);
        assertEquals(GcEventType.YOUNG, r.events.get(2).type, "Old 라벨이 없는 [GC 는 그대로 Young");
    }
}

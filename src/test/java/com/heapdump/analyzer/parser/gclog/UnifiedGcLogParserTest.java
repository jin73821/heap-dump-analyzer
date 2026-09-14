package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.parser.gclog.GcLogTestSupport.Recorder;
import org.junit.jupiter.api.Test;

import static com.heapdump.analyzer.parser.gclog.GcLogTestSupport.TS;
import static com.heapdump.analyzer.parser.gclog.GcLogTestSupport.g1Young;
import static com.heapdump.analyzer.parser.gclog.GcLogTestSupport.runUnified;
import static com.heapdump.analyzer.parser.gclog.GcLogTestSupport.u;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDK 9+ 통합 로깅 파서 골든 테스트. 픽스처는 앱 자체 {@code logs/gc.log}(JDK 21 G1) 실측 줄 형식을 코드로 합성한다 —
 * 런타임 산출물인 logs/ 는 읽지 않는다.
 */
class UnifiedGcLogParserTest {

    @Test
    void g1YoungPauseIsAssembledFromStartHeapMetaspaceCompletionAndCpuLines() {
        Recorder r = runUnified(
                u(TS, "0.006s", "gc,init", "Version: 21+35-2513 (release)"),
                u(TS, "0.006s", "gc,init", "Heap Region Size: 1M"),
                u(TS, "0.003s", "gc", "Using G1"),
                g1Young(0, TS, 0.325, "Normal", "G1 Evacuation Pause", "14M->2M(258M)", "3.363", 13, 0, 2, 2, 0, 0),
                g1Young(1, TS, 0.723, "Normal", "G1 Evacuation Pause", "27M->3M(258M)", "4.770", 25, 0, 2, 3, 0, 0));
        assertEquals("G1", r.meta.get("collector"));
        assertEquals("21+35-2513", r.meta.get("jdkVersion"));
        assertEquals("1048576", r.meta.get("regionSize"));
        assertEquals(2, r.events.size(), "완료 이벤트 2건");
        GcEvent e = r.events.get(0);
        assertEquals(GcEventType.YOUNG, e.type);
        assertEquals("G1 Evacuation Pause", e.cause);
        assertEquals(3.363, e.pauseMs, 1e-9);
        assertEquals(14L << 20, e.heapBefore);
        assertEquals(2L << 20, e.heapAfter);
        assertEquals(258L << 20, e.heapTotal);
        assertEquals(0.325, e.uptimeSec, 1e-9, "시작 줄의 uptime 을 쓴다");
        assertEquals(13L << 20, e.youngBefore, "Eden 13 리전 × 1M");
        assertEquals(2L << 20, e.oldAfter);
        assertEquals(1708L << 10, e.metaAfter);
        assertEquals(0.01, e.userSec, 1e-9, "완료 줄 뒤에 오는 cpu 줄이 붙는다");
        assertEquals(0.01, e.realSec, 1e-9);
        assertNotNull(e.tsEpochMs);
        assertEquals(4, e.line, "메타 3줄 뒤 gc,start 줄");
        assertTrue(r.meta.get("decorations").contains("time"));
    }

    @Test
    void mixedPrepareMixedAndConcurrentStartMarkersAreClassified() {
        Recorder r = runUnified(
                u(TS, "0.006s", "gc,init", "Heap Region Size: 1M"),
                g1Young(3, TS, 2.413, "Concurrent Start", "Metadata GC Threshold", "96M->11M(258M)", "9.501", 88, 0, 2, 2, 0, 0),
                g1Young(5, TS, 3.000, "Prepare Mixed", "G1 Evacuation Pause", "96M->11M(258M)", "5.0", 88, 0, 2, 2, 0, 0),
                g1Young(6, TS, 4.000, "Mixed", "G1 Evacuation Pause", "96M->11M(258M)", "5.0", 88, 0, 2, 2, 0, 0));
        assertEquals(3, r.events.size());
        assertEquals(GcEventType.YOUNG, r.events.get(0).type);
        assertTrue(r.events.get(0).flags.contains(GcFlag.INITIAL_MARK));
        assertTrue(r.events.get(0).flags.contains(GcFlag.METADATA_THRESHOLD));
        assertEquals("Metadata GC Threshold", r.events.get(0).cause);
        assertEquals(GcEventType.YOUNG, r.events.get(1).type, "Prepare Mixed 는 young");
        assertEquals(GcEventType.MIXED, r.events.get(2).type);
    }

    @Test
    void concurrentCycleInterleavesWithPausesAndSharesIdSpaceWithRemarkCleanup() {
        // JDK 21 실측 순서: GC(3) young 완료 → GC(4) Concurrent Mark Cycle 시작 → GC(3) cpu → … → GC(4) Pause Remark → GC(4) Pause Cleanup → GC(4) Concurrent Mark Cycle 완료
        Recorder r = runUnified(
                u(TS, "2.413s", "gc,start", "GC(3) Pause Young (Concurrent Start) (Metadata GC Threshold)"),
                u(TS, "2.422s", "gc", "GC(3) Pause Young (Concurrent Start) (Metadata GC Threshold) 96M->11M(258M) 9.501ms"),
                u(TS, "2.422s", "gc", "GC(4) Concurrent Mark Cycle"),
                u(TS, "2.422s", "gc,marking", "GC(4) Concurrent Scan Root Regions"),
                u(TS, "2.422s", "gc,cpu", "GC(3) User=0.02s Sys=0.00s Real=0.01s"),
                u(TS, "2.431s", "gc,marking", "GC(4) Concurrent Scan Root Regions 8.652ms"),
                u(TS, "2.500s", "gc,start", "GC(4) Pause Remark"),
                u(TS, "2.510s", "gc", "GC(4) Pause Remark 20M->20M(258M) 10.000ms"),
                u(TS, "2.510s", "gc,cpu", "GC(4) User=0.03s Sys=0.00s Real=0.01s"),
                u(TS, "2.600s", "gc,start", "GC(4) Pause Cleanup"),
                u(TS, "2.601s", "gc", "GC(4) Pause Cleanup 20M->20M(258M) 0.500ms"),
                u(TS, "2.601s", "gc,cpu", "GC(4) User=0.00s Sys=0.00s Real=0.00s"),
                u(TS, "2.700s", "gc", "GC(4) Concurrent Mark Cycle 278.000ms"));
        assertEquals(4, r.events.size());
        GcEvent young = byType(r, GcEventType.YOUNG);
        assertEquals(0.02, young.userSec, 1e-9, "동시 사이클 시작 줄이 끼어도 cpu 줄이 young 에 붙는다");
        GcEvent remark = byType(r, GcEventType.REMARK);
        assertEquals(0.03, remark.userSec, 1e-9);
        assertEquals(2.500, remark.uptimeSec, 1e-9);
        assertNotNull(byType(r, GcEventType.CLEANUP));
        GcEvent conc = byType(r, GcEventType.CONCURRENT_CYCLE);
        assertEquals(GcEventType.CONCURRENT_CYCLE, conc.type);
        assertTrue(conc.concurrent);
        assertFalse(conc.isPause());
        assertEquals(278.0, conc.pauseMs, 1e-9);
        assertEquals(2.422, conc.uptimeSec, 1e-9, "사이클 시작 줄의 uptime");
    }

    @Test
    void decorationSubsetWithoutTimeAndParallelFullGc() {
        Recorder r = runUnified(
                "[0.003s][info][gc] Using Parallel",
                "[0.325s][info][gc,start] GC(0) Pause Young (Allocation Failure)",
                "[0.328s][info][gc,heap] GC(0) PSYoungGen: 32768K(38400K)->5088K(38400K) Eden: 32768K(32768K)->0K(32768K) From: 0K(2560K)->5088K(2560K)",
                "[0.328s][info][gc,heap] GC(0) ParOldGen: 0K(87552K)->8K(87552K)",
                "[0.328s][info][gc] GC(0) Pause Young (Allocation Failure) 32M->4M(123M) 3.100ms",
                "[0.328s][info][gc,cpu] GC(0) User=0.01s Sys=0.00s Real=0.00s",
                "[6.840s][info][gc,start] GC(1) Pause Full (Ergonomics)",
                "[6.963s][info][gc] GC(1) Pause Full (Ergonomics) 89M->58M(123M) 123.400ms",
                "[6.963s][info][gc,cpu] GC(1) User=0.40s Sys=0.00s Real=0.12s");
        assertEquals("Parallel", r.meta.get("collector"));
        assertEquals(2, r.events.size());
        GcEvent y = r.events.get(0);
        assertNull(y.tsEpochMs, "time 데코레이션 없음");
        assertEquals(0.325, y.uptimeSec, 1e-9);
        assertEquals(32768L << 10, y.youngBefore);
        assertEquals(8L << 10, y.oldAfter);
        assertTrue(y.flags.contains(GcFlag.ALLOCATION_FAILURE));
        GcEvent f = r.events.get(1);
        assertEquals(GcEventType.FULL, f.type);
        assertTrue(f.flags.contains(GcFlag.ERGONOMICS));
        assertEquals(123.4, f.pauseMs, 1e-9);
        assertEquals(0.40, f.userSec, 1e-9);
        assertEquals("uptime,level", r.meta.get("decorations"));
    }

    @Test
    void utctimeAndUptimeMillisDecorationsParse() {
        Recorder r = runUnified(
                "[2026-09-13T15:52:33.485+0000][325ms][info][gc,start] GC(0) Pause Young (Normal) (G1 Evacuation Pause)",
                "[2026-09-13T15:52:33.489+0000][328ms][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(258M) 3.363ms");
        assertEquals(1, r.events.size());
        GcEvent e = r.events.get(0);
        assertEquals(0.325, e.uptimeSec, 1e-9);
        // 2026-09-13T15:52:33.485Z == 2026-09-14T00:52:33.485+0900
        assertEquals(GcLogSupport.parseIsoEpochMs("2026-09-14T00:52:33.485+0900"), e.tsEpochMs);
    }

    @Test
    void toSpaceExhaustedAndHumongousCauseSetFlags() {
        Recorder r = runUnified(
                u(TS, "0.006s", "gc,init", "Heap Region Size: 1M"),
                u(TS, "10.000s", "gc,start", "GC(7) Pause Young (Normal) (G1 Humongous Allocation)"),
                u(TS, "10.100s", "gc", "GC(7) To-space exhausted"),
                u(TS, "10.100s", "gc,heap", "GC(7) Humongous regions: 40->40"),
                u(TS, "10.200s", "gc", "GC(7) Pause Young (Normal) (G1 Humongous Allocation) 250M->240M(258M) 200.000ms"),
                u(TS, "10.300s", "gc,start", "GC(8) Pause Full (G1 Compaction Pause)"),
                u(TS, "11.300s", "gc", "GC(8) Pause Full (G1 Compaction Pause) 240M->100M(258M) 1000.000ms"));
        assertEquals(2, r.events.size());
        GcEvent y = r.events.get(0);
        assertTrue(y.flags.contains(GcFlag.TO_SPACE_EXHAUSTED));
        assertTrue(y.flags.contains(GcFlag.HUMONGOUS_ALLOC));
        assertEquals(40, y.humongousRegions);
        assertEquals(GcEventType.FULL, r.events.get(1).type);
        assertEquals("G1 Compaction Pause", r.events.get(1).cause);
    }

    @Test
    void completionWithoutStartLineApproximatesStartTime() {
        Recorder r = runUnified(u(TS, "5.000s", "gc", "GC(0) Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(258M) 1000.000ms"));
        assertEquals(1, r.events.size());
        assertEquals(4.0, r.events.get(0).uptimeSec, 1e-9, "완료 uptime − 소요");
    }

    @Test
    void zgcAndUnrelatedLinesAreHarmless() {
        Recorder r = runUnified(
                "[0.003s][info][gc] Using The Z Garbage Collector",
                "[1.000s][info][gc] GC(0) Garbage Collection (Allocation Rate)",
                "[1.001s][info][gc,start] GC(0) Pause Mark Start",
                "[1.002s][info][gc] GC(0) Pause Mark Start 0.010ms",
                "[1.500s][info][gc] GC(0) Garbage Collection (Allocation Rate) 1024M(50%)->512M(25%)",
                "[1.600s][info][gc,heap] GC(0)  Mark Start          Mark End        Relocate Start      Relocate End           High               Low",
                "some garbage line without brackets",
                "[not a gc line]");
        assertEquals("ZGC", r.meta.get("collector"));
        assertEquals(2, r.events.size());
        GcEvent mark = byType(r, GcEventType.OTHER);
        assertEquals(0.010, mark.pauseMs, 1e-9);
        GcEvent cyc = byType(r, GcEventType.CONCURRENT_CYCLE);
        assertTrue(cyc.concurrent);
        assertEquals(1024L << 20, cyc.heapBefore);
        assertNull(cyc.heapTotal);
    }

    @Test
    void openEventsWithoutCompletionAreDroppedAtFinishAndEventLimitStopsParsing() {
        Recorder r = new Recorder();
        UnifiedGcLogParser open = new UnifiedGcLogParser(r, 1000);
        open.feedLine(1, u(TS, "1.000s", "gc,start", "GC(0) Pause Young (Normal) (G1 Evacuation Pause)"));
        open.feedLine(2, u(TS, "1.100s", "gc,start", "GC(1) Pause Young (Normal) (G1 Evacuation Pause)"));
        open.finish();
        assertEquals(0, r.events.size());
        assertEquals(2, open.droppedIncomplete(), "미종결 2건 폐기");

        Recorder rec = new Recorder();
        UnifiedGcLogParser p = new UnifiedGcLogParser(rec, 2);
        for (int i = 0; i < 5; i++) {
            p.feedLine(i * 2 + 1, u(TS, i + ".000s", "gc,start", "GC(" + i + ") Pause Young (Normal) (G1 Evacuation Pause)"));
            p.feedLine(i * 2 + 2, u(TS, i + ".010s", "gc", "GC(" + i + ") Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(258M) 3.0ms"));
        }
        p.finish();
        assertTrue(p.limitReached());
        assertEquals(2, rec.events.size());
    }

    private static GcEvent byType(Recorder r, GcEventType t) {
        return r.events.stream().filter(e -> e.type == t).findFirst().orElseThrow();
    }
}

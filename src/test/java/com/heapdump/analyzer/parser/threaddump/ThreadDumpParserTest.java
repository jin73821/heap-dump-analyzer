package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 문법 — JDK 별 헤더 변형, 락 줄, 스냅샷 경계, 해석 못 한 줄 분류. */
class ThreadDumpParserTest {

    private static List<ThreadDumpParser.ParsedSnapshot> parse(String text, ThreadDumpResult.Diagnostics diag) {
        ThreadDumpParser p = new ThreadDumpParser(ThreadDumpLimits.DEFAULT, new StackTable(), diag);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) p.feedLine(i + 1, lines[i]);
        p.finish(lines.length);
        return p.snapshots();
    }

    private static ThreadDumpParser.ParsedThread one(String headerLine) {
        var snaps = parse(VM8 + "\n\n" + headerLine + "\n   java.lang.Thread.State: RUNNABLE\n\nJNI global references: 1\n", new ThreadDumpResult.Diagnostics());
        assertEquals(1, snaps.size());
        assertEquals(1, snaps.get(0).threads.size(), headerLine);
        return snaps.get(0).threads.get(0);
    }

    @Test
    void 헤더_JDK7_번호와_os_prio_없음() {
        var t = one("\"http-8080-1\" daemon prio=10 tid=0x00007f1c2c001000 nid=0x1f03 runnable [0x00007f1c35d5c000]");
        assertEquals("http-8080-1", t.name);
        assertEquals(-1, t.num);
        assertTrue(t.daemon);
        assertEquals("0x00007f1c2c001000", t.tid);
        assertEquals("0x1f03", t.nid);
        assertEquals("runnable", t.nativeStatus);
    }

    @Test
    void 헤더_JDK8() {
        var t = one("\"Attach Listener\" #30 daemon prio=9 os_prio=0 tid=0x00007f880c001000 nid=0x12daf8 waiting on condition [0x0000000000000000]");
        assertEquals(30, t.num);
        assertTrue(t.daemon);
        assertEquals("waiting on condition", t.nativeStatus);
        assertTrue(Double.isNaN(t.cpuMs));
    }

    @Test
    void 헤더_JDK11_17_cpu_elapsed() {
        var t = one("\"main\" #1 prio=5 os_prio=0 cpu=54.53ms elapsed=3.21s tid=0x00007f6ef0012800 nid=0x1a03 waiting on condition  [0x00007f6ef7a3c000]");
        assertEquals(1, t.num);
        assertFalse(t.daemon);
        assertEquals(54.53, t.cpuMs, 0.001);
        assertEquals(3.21, t.elapsedSec, 0.001);
    }

    @Test
    void 헤더_JDK21_osTid_괄호와_10진_nid() {
        var t = one("\"main\" #1 [1235804] prio=5 os_prio=0 cpu=45.36ms elapsed=1.35s tid=0x00007f90ec02adc0 nid=1235804 waiting on condition  [0x00007f90f6111000]");
        assertEquals(1, t.num);
        assertEquals("1235804", t.nid);
        assertEquals(45.36, t.cpuMs, 0.001);
    }

    @Test
    void 헤더_이름에_따옴표와_대괄호_WebLogic() {
        var t = one("\"[ACTIVE] ExecuteThread: '12' for queue: 'weblogic.kernel.Default (self-tuning)'\" #45 daemon prio=5 os_prio=0 tid=0x00007f0000045000 nid=0x2d runnable [0x00007f0000000000]");
        assertEquals("[ACTIVE] ExecuteThread: '12' for queue: 'weblogic.kernel.Default (self-tuning)'", t.name);
        assertEquals(45, t.num);
        var q = one("\"worker \"quoted\" name\" #7 prio=5 os_prio=0 tid=0x00007f0000007000 nid=0x7 runnable [0x0]");
        assertEquals("worker \"quoted\" name", q.name);
    }

    @Test
    void JVM_내부_스레드는_상태줄이_없다() {
        var snaps = parse(VM8 + "\n\n\"VM Thread\" os_prio=0 tid=0x00007f88540d6000 nid=0x12dac8 runnable \n\n"
                + "\"GC task thread#0 (ParallelGC)\" os_prio=0 tid=0x00007f885405f000 nid=0x12dac4 runnable \n\nJNI global references: 5\n", new ThreadDumpResult.Diagnostics());
        var th = snaps.get(0).threads;
        assertEquals(2, th.size());
        assertFalse(th.get(0).javaThread());
        assertEquals(ThreadDumpResult.ST_VM, th.get(0).state);
        assertEquals("GC task thread#0 (ParallelGC)", th.get(1).name);
        assertEquals(-1, th.get(1).num, "이름 안의 #0 은 스레드 번호가 아니다");
    }

    @Test
    void 락_줄과_소유_동기화기() {
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(new Dump(null, VM8)
                .thread("t", "BLOCKED (on object monitor)",
                        at("a.B.c(B.java:1)"),
                        waitingToLock("0x00000000feb66428", "java.lang.Object"),
                        locked("0x00000000feb66418", "java.lang.Class for a.B"),
                        at("a.B.d(B.java:2)"),
                        "\t- waiting on <no object reference available>",
                        "\t- eliminated <owner is scalar replaced> (a java.lang.Object)",
                        "\t- frobnicating <0x1> (a X)",
                        "",
                        "   Locked ownable synchronizers:",
                        "\t- <0x00000000ffd15858> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)",
                        "\t- None")
                .end(), diag);
        var t = snaps.get(0).threads.get(0);
        assertEquals(ThreadDumpResult.ST_BLOCKED, t.state);
        assertEquals(5, t.ops.size());
        assertEquals("waiting to lock", t.ops.get(0).verb());
        assertEquals(1, t.ops.get(0).at(), "첫 프레임 뒤에 놓인다");
        assertEquals("java.lang.Class for a.B", t.ops.get(1).cls());
        assertEquals("<no object reference available>".substring(1, 30), t.ops.get(2).addr());
        assertEquals("owns", t.ops.get(4).verb());
        assertEquals("0x00000000ffd15858", t.ops.get(4).addr());
        assertEquals(1, diag.getUnknownLockLines(), "모르는 동사는 세기만 한다");
        assertEquals(0, diag.getUnparsedLines());
    }

    @Test
    void 프레임의_모듈_버전은_떼고_스택은_프레임만으로_같다고_본다() {
        var table = new StackTable();
        var p = new ThreadDumpParser(ThreadDumpLimits.DEFAULT, table, new ThreadDumpResult.Diagnostics());
        String text = new Dump(null, VM17)
                .thread("a", "RUNNABLE", at("java.lang.Thread.sleep(java.base@17.0.8/Thread.java:10)"), locked("0x01", "java.lang.Object"), at("x.Y.z(Y.java:3)"))
                .thread("b", "RUNNABLE", at("java.lang.Thread.sleep(java.base@17.0.8/Thread.java:10)"), locked("0x02", "java.lang.Object"), at("x.Y.z(Y.java:3)"))
                .end();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) p.feedLine(i + 1, lines[i]);
        p.finish(lines.length);
        var th = p.snapshots().get(0).threads;
        assertEquals(th.get(0).stackId, th.get(1).stackId, "락 주소가 달라도 프레임이 같으면 같은 스택이다");
        assertEquals("java.lang.Thread.sleep(Thread.java:10)", table.top(th.get(0).stackId));
    }

    @Test
    void 타임스탬프_형식() {
        assertNotNull(ThreadDumpParser.parseTimestamp("2026-11-26 10:52:55"));
        assertNotNull(ThreadDumpParser.parseTimestamp("2026-11-26T10:52:55.123"));
        assertEquals(1795657975000L, ThreadDumpParser.parseTimestamp("2026-11-26T01:52:55Z"));
        assertEquals(1795657975123L, ThreadDumpParser.parseTimestamp("2026-11-26 10:52:55,123+0900"));
        assertEquals(1795657975000L, ThreadDumpParser.parseTimestamp("2026-11-26 10:52:55+09:00"));
        assertNull(ThreadDumpParser.parseTimestamp("2026-13-40 10:52:55"), "모양만 시각인 줄");
        assertNull(ThreadDumpParser.parseTimestamp("10:52:55"));
    }

    @Test
    void 연속_스냅샷과_시각줄() {
        String text = new Dump("2026-11-26 10:00:00", VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).end()
                + new Dump("2026-11-26 10:00:05", VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(text, diag);
        assertEquals(2, snaps.size());
        assertEquals(5000, snaps.get(1).ts - snaps.get(0).ts);
        assertTrue(snaps.get(0).complete);
        assertEquals(0, diag.getMissingTimestamps());
        assertEquals(0, diag.getNoiseLines(), "시각 줄은 잡음이 아니다");
    }

    @Test
    void 트레일러_없이_다음_덤프가_오면_앞_스냅샷은_미완() {
        String text = new Dump(null, VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).endNoTrailer()
                + new Dump(null, VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(text, diag);
        assertEquals(2, snaps.size());
        assertFalse(snaps.get(0).complete);
        assertEquals("NEXT_DUMP", snaps.get(0).incompleteReason);
        assertEquals(1, snaps.get(0).threads.size(), "끊기기 전에 읽힌 스레드는 살린다");
        assertTrue(snaps.get(1).complete);
        assertEquals(1, diag.getIncompleteSnapshots());
        assertEquals(2, diag.getMissingTimestamps());
    }

    @Test
    void 덤프_안에_끼어든_로그_줄은_해석_못_한_줄로_센다() {
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(new Dump(null, VM8)
                .thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)"))
                .raw("2026-11-26 10:00:01 WARN  app.Foo - interleaved log line")
                .raw("\tat broken frame without parens")
                .thread("b", "RUNNABLE", at("x.Y.z(Y.java:1)"))
                .end(), diag);
        assertEquals(2, snaps.get(0).threads.size(), "끼어든 줄 뒤의 스레드도 읽는다");
        assertEquals(2, diag.getUnparsedLines());
        assertEquals(2, diag.getUnparsedSamples().size());
        assertTrue(diag.getUnparsedSamples().get(0).getText().contains("interleaved"));
        assertTrue(diag.getUnparsedSamples().get(0).getLine() > 0);
        assertTrue(snaps.get(0).complete);
    }

    @Test
    void 로그가_길게_이어지면_스냅샷을_닫고_잡음으로_되돌린다() {
        StringBuilder sb = new StringBuilder(new Dump(null, VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).endNoTrailer());
        for (int i = 0; i < 40; i++) sb.append("2026-11-26 10:00:01 INFO  app.Foo - line ").append(i).append('\n');
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(sb.toString(), diag);
        assertEquals(1, snaps.size());
        assertEquals("INTERRUPTED_BY_LOG", snaps.get(0).incompleteReason);
        assertEquals(0, diag.getUnparsedLines(), "로그였던 줄은 해석 못 한 줄에서 뺀다");
        assertTrue(diag.getUnparsedSamples().isEmpty());
        assertTrue(diag.getNoiseLines() >= 40);
    }

    @Test
    void 헤더_없는_덤프는_스레드_3개_이상일_때만_받는다() {
        String three = "\"a\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]\n   java.lang.Thread.State: RUNNABLE\n\tat x.Y.z(Y.java:1)\n\n"
                + "\"b\" #2 prio=5 os_prio=0 tid=0x00007f0000002000 nid=0x2 runnable [0x0]\n   java.lang.Thread.State: RUNNABLE\n\n"
                + "\"c\" #3 prio=5 os_prio=0 tid=0x00007f0000003000 nid=0x3 runnable [0x0]\n   java.lang.Thread.State: RUNNABLE\n\n";
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(three, diag);
        assertEquals(1, snaps.size());
        assertTrue(snaps.get(0).headerless);
        assertEquals(3, snaps.get(0).threads.size());

        var diag2 = new ThreadDumpResult.Diagnostics();
        var none = parse("log line\n\"a\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]\nmore log\n", diag2);
        assertTrue(none.isEmpty(), "로그에 인용된 스레드 줄 하나는 덤프가 아니다");
        assertEquals(1, diag2.getDiscardedFragments());
    }

    @Test
    void JVM_데드락_섹션_사슬() {
        String text = new Dump(null, VM8)
                .thread("A", "BLOCKED (on object monitor)", at("x.Y.a(Y.java:1)"), waitingToLock("0x02", "java.lang.Object"), locked("0x01", "java.lang.Object"))
                .thread("B", "BLOCKED (on object monitor)", at("x.Y.b(Y.java:2)"), waitingToLock("0x01", "java.lang.Object"), locked("0x02", "java.lang.Object"))
                .end()
                + "\nFound one Java-level deadlock:\n=============================\n\"B\":\n"
                + "  waiting to lock monitor 0x00007f87ec002418 (object 0x0000000000000001, a java.lang.Object),\n  which is held by \"A\"\n\"A\":\n"
                + "  waiting for ownable synchronizer 0x0000000000000002, (a java.util.concurrent.locks.ReentrantLock$NonfairSync),\n  which is held by \"B\"\n\n"
                + "Java stack information for the threads listed above:\n===================================================\n\"B\":\n\tat x.Y.b(Y.java:2)\n\"A\":\n\tat x.Y.a(Y.java:1)\n\nFound 1 deadlock.\n\n";
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(text, diag);
        assertEquals(1, snaps.get(0).jvmDeadlocks.size());
        var chain = snaps.get(0).jvmDeadlocks.get(0).chain;
        assertEquals(2, chain.size(), "뒤쪽 스택 구간의 이름 줄은 사슬에 넣지 않는다");
        assertArrayEquals(new String[]{"B", "0x0000000000000001", "java.lang.Object", "A"}, chain.get(0));
        assertEquals("java.util.concurrent.locks.ReentrantLock$NonfairSync", chain.get(1)[2]);
        assertEquals(0, diag.getUnparsedLines());
        assertTrue(snaps.get(0).complete);
    }

    @Test
    void 컨테이너_로그_접두를_뗀다() {
        String dump = new Dump("2026-11-26 10:00:00", VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
        StringBuilder sb = new StringBuilder();
        for (String l : dump.split("\n", -1)) sb.append("2026-11-26T01:00:00.123456789Z stdout F ").append(l).append('\n');
        var diag = new ThreadDumpResult.Diagnostics();
        var snaps = parse(sb.toString(), diag);
        assertEquals(1, snaps.size());
        assertEquals(1, snaps.get(0).threads.size());
        assertEquals("a", snaps.get(0).threads.get(0).name);
        assertNotNull(diag.getDecoratedPrefix());
        assertNotNull(snaps.get(0).ts, "접두 뒤의 시각 줄도 읽는다");
        assertEquals(0, diag.getUnparsedLines());
    }

    @Test
    void 스냅샷당_스레드_상한() {
        Dump d = new Dump(null, VM8);
        for (int i = 0; i < 10; i++) d.thread("t-" + i, "RUNNABLE", at("x.Y.z(Y.java:1)"));
        var diag = new ThreadDumpResult.Diagnostics();
        ThreadDumpParser p = new ThreadDumpParser(new ThreadDumpLimits(1 << 30, 4096, 4, 30), new StackTable(), diag);
        String[] lines = d.end().split("\n", -1);
        for (int i = 0; i < lines.length; i++) p.feedLine(i + 1, lines[i]);
        p.finish(lines.length);
        assertEquals(4, p.snapshots().get(0).threads.size());
        assertEquals(6, p.snapshots().get(0).skippedThreads);
        assertTrue(diag.getLimitHits().contains("THREADS_PER_SNAPSHOT"));
        assertEquals(0, diag.getUnparsedLines(), "건너뛴 스레드의 줄은 해석 못 한 줄이 아니다");
    }

    @Test
    void 스냅샷_상한은_마지막_N개를_남긴다() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) sb.append(new Dump("2026-11-26 10:00:0" + i, VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:" + i + ")")).end());
        var diag = new ThreadDumpResult.Diagnostics();
        ThreadDumpParser p = new ThreadDumpParser(new ThreadDumpLimits(1 << 30, 4096, 100, 3), new StackTable(), diag);
        String[] lines = sb.toString().split("\n", -1);
        for (int i = 0; i < lines.length; i++) p.feedLine(i + 1, lines[i]);
        p.finish(lines.length);
        assertEquals(7, p.snapshotsSeen());
        assertEquals(3, p.snapshots().size());
        assertEquals(4, p.snapshots().get(0).seq, "앞쪽 4개를 버리고 5~7번째를 남긴다");
        assertTrue(diag.getLimitHits().contains("SNAPSHOTS"));
    }
}

package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 이 호스트의 JDK 8·21 로 실제로 받은 덤프(데모 프로그램: 모니터 교착 2 + synchronized 경합 6 + ReentrantLock 경합 3 +
 * 풀 유휴 4·활성 2 + busy loop + Object.wait) — 형식이 바뀌면 여기서 먼저 깨진다.
 */
class ThreadDumpRealFixtureTest {

    @Test
    void jdk8_jstack_3연속_스냅샷() {
        ThreadDumpResult r = fixture("jdk8-deadlock-3snap.txt");
        System.out.println("[jdk8] " + codes(r) + " kpi=" + r.getKpi() + " diag=" + r.getDiagnostics());
        assertEquals(3, r.getSnapshots().size());
        assertEquals(8, r.getMeta().getJdkMajor());
        assertEquals("absolute", r.getMeta().getTimeSource());
        assertEquals("timestamp", r.getMeta().getIntervalSource());
        assertEquals(1, r.getMeta().getRuns());
        for (ThreadDumpResult.Snapshot s : r.getSnapshots()) {
            assertTrue(s.isComplete(), "트레일러까지 읽혔다");
            assertEquals(36, s.getThreads());
            assertEquals(30, s.getJavaThreads());
            assertEquals(8, s.getBlocked());       // deadlock 2 + hot-lock-waiter 6
            assertEquals(1, s.getDeadlocks());
            assertEquals(5, s.getJniRefs());
        }
        assertEquals(0, r.getDiagnostics().getUnparsedLines(), () -> "해석 못 한 줄: " + r.getDiagnostics().getUnparsedSamples());
        assertEquals(0, r.getDiagnostics().getUnknownLockLines());

        // 교착 — 그래프와 JVM 보고가 같은 것을 가리켜 1건, 출처는 jvm
        assertEquals(1, r.getDeadlocks().size());
        ThreadDumpResult.Deadlock d = r.getDeadlocks().get(0);
        assertEquals("jvm", d.getSource());
        assertEquals(2, d.getTracks().size());
        assertEquals(List.of("deadlock-A", "deadlock-B"), d.getTracks().stream().map(t -> name(r, t)).sorted().toList());
        ThreadDumpResult.Finding dl = finding(r, "DEADLOCK");
        assertNotNull(dl);
        assertEquals("Critical", dl.getSeverity());
        assertEquals("Critical", r.getKpi().getSeverity());

        // 락 경합 — HOT 모니터 대기 6, 소유자는 sleep 중인 hot-lock-owner
        ThreadDumpResult.Finding lc = finding(r, "LOCK_CONTENTION");
        assertNotNull(lc, () -> "소견: " + codes(r));
        assertEquals(6, lc.getEvidence().get("waiters"));
        assertEquals("hot-lock-owner", lc.getEvidence().get("owner"));
        assertEquals("High", lc.getSeverity(), "3개 스냅샷에 걸쳐 이어져 한 단계 올라간다");
        assertTrue(has(r, "LOCK_OWNER_STUCK"));

        // ReentrantLock — jstack -l 이라 소유자를 안다
        ThreadDumpResult.LockInfo rl = r.getLocks().stream().filter(l -> "juc".equals(l.getKind())).findFirst().orElseThrow();
        assertEquals(3, rl.getWaiterCount());
        assertEquals("rl-owner", name(r, rl.getOwner()));
        assertFalse(has(r, "NO_LOCK_OWNER_INFO"));

        // Object.wait() 중인 스레드는 자기가 locked 로 찍은 모니터의 소유자가 아니다
        assertTrue(r.getLocks().stream().noneMatch(l -> l.getOwner() >= 0 && name(r, l.getOwner()).equals("object-waiter")));

        // 풀 — 유휴 4, 활성 2
        ThreadDumpResult.Pool pool = r.getPools().stream().filter(p -> p.getStem().equals("http-nio-#-exec-#")).findFirst().orElseThrow();
        assertEquals("worker", pool.getKind());
        assertEquals(List.of(6, 6, 6), pool.getSizes());
        assertEquals(List.of(4, 4, 4), pool.getIdle());

        // 정체 — 교착 2개는 따로 세고, busy-spinner·경합 대기자·활성 워커가 잡힌다
        assertTrue(has(r, "STUCK_THREADS"));
        List<String> stuckNames = r.getStuck().stream().map(s -> name(r, s.getT())).toList();
        assertTrue(stuckNames.contains("busy-spinner"), stuckNames.toString());
        assertTrue(stuckNames.contains("hot-lock-waiter-0"), stuckNames.toString());
        assertFalse(stuckNames.contains("main"), "풀 밖 스레드의 sleep 은 정체가 아니다");
        assertFalse(stuckNames.contains("http-nio-8080-exec-6"), "유휴 스레드는 정체가 아니다");

        // JDK 8 은 cpu= 가 없다
        assertFalse(has(r, "HOT_THREAD_CPU"));
        assertTrue(r.getCpuTop().isEmpty());
    }

    @Test
    void jdk21_jstack_cpu_와_10진_nid() {
        ThreadDumpResult r = fixture("jdk21-3snap.txt");
        System.out.println("[jdk21] " + codes(r) + " kpi=" + r.getKpi() + " diag=" + r.getDiagnostics());
        assertEquals(3, r.getSnapshots().size());
        assertEquals(21, r.getMeta().getJdkMajor());
        assertEquals(0, r.getDiagnostics().getUnparsedLines(), () -> "해석 못 한 줄: " + r.getDiagnostics().getUnparsedSamples());
        assertTrue(r.getSnapshots().get(0).isComplete());
        assertEquals(1, r.getDeadlocks().size());

        ThreadDumpResult.Track main = track(r, "main");
        assertNotNull(main);
        assertEquals(1L, main.getNum());
        assertTrue(main.getNid().matches("\\d+"), "JDK 21 은 nid 가 10진수다");
        assertEquals("TTT", main.getS());
        // 모듈 버전 접두는 떼고 저장한다
        assertEquals("java.lang.Thread.sleep0(Native Method)", frames(r, main.getK().get(0)).get(0));

        ThreadDumpResult.Finding hot = finding(r, "HOT_THREAD_CPU");
        assertNotNull(hot, () -> "소견: " + codes(r));
        assertEquals("busy-spinner", name(r, hot.getTracks().get(0)));
        assertEquals("busy-spinner", name(r, r.getCpuTop().get(0).getT()));
        assertTrue(r.getCpuTop().get(0).getPct() > 80);
    }

    @Test
    void kill3_로그에_섞인_덤프_gz() {
        ThreadDumpResult r = fixture("jdk8-kill3-embedded.txt.gz");
        System.out.println("[kill3] " + codes(r) + " diag=" + r.getDiagnostics());
        assertEquals(2, r.getSnapshots().size());
        assertTrue(r.getMeta().isEmbedded());
        assertTrue(r.getSnapshots().get(0).isComplete());
        assertTrue(r.getSnapshots().get(1).isComplete());
        assertEquals(0, r.getDiagnostics().getUnparsedLines(), () -> "Heap 섹션이 해석 못 한 줄로 세어지면 안 된다: " + r.getDiagnostics().getUnparsedSamples());
        assertTrue(r.getDiagnostics().getNoiseLines() >= 3);
        assertEquals(1, r.getDeadlocks().size());
        // 스냅샷이 2개(1초 간격)뿐이라 정체 판정은 하지 않는다
        assertFalse(has(r, "STUCK_THREADS"));
    }

    @Test
    void jdk21_kill3_Heap_섹션() {
        ThreadDumpResult r = fixture("jdk21-kill3-embedded.txt");
        assertEquals(2, r.getSnapshots().size());
        assertEquals(0, r.getDiagnostics().getUnparsedLines(), () -> "" + r.getDiagnostics().getUnparsedSamples());
        assertEquals(5, r.getSnapshots().get(0).getJniRefs());
    }

    @Test
    void 도중에_잘린_덤프는_읽힌_만큼_분석한다() {
        ThreadDumpResult r = fixture("jdk21-truncated.txt");
        System.out.println("[trunc] " + codes(r) + " diag=" + r.getDiagnostics());
        assertEquals(1, r.getSnapshots().size());
        ThreadDumpResult.Snapshot s = r.getSnapshots().get(0);
        assertFalse(s.isComplete());
        assertEquals("EOF", s.getIncompleteReason());
        assertTrue(s.getThreads() >= 15);
        assertEquals(1, r.getDiagnostics().getIncompleteThreads(), "헤더만 읽힌 마지막 스레드는 버린다");
        assertEquals(1, r.getDiagnostics().getIncompleteSnapshots());
        assertTrue(has(r, "SNAPSHOT_TRUNCATED"));
        assertTrue(has(r, "SINGLE_SNAPSHOT"));
        // 잘리기 전에 읽힌 교착은 그래프에서 찾는다(JVM 보고 섹션은 잘려 나갔다)
        assertEquals(1, r.getDeadlocks().size());
        assertEquals("graph", r.getDeadlocks().get(0).getSource());
    }
}

package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 규칙별 양성·음성 — 오탐을 막는 게이트가 실제로 막는지 본다. */
class ThreadDumpAnalyzerTest {

    private static final String OBJ = "java.lang.Object";
    private static final String SYNC = "java.util.concurrent.locks.ReentrantLock$NonfairSync";
    private static final String COND = "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject";

    private static String ts(int sec) { return String.format("2026-11-26 10:%02d:%02d", sec / 60, sec % 60); }

    @Test
    void wait_중인_스레드는_locked_로_찍혀도_소유자가_아니다() {
        String d = new Dump(ts(0), VM8)
                .thread("waiter", "WAITING (on object monitor)",
                        at("java.lang.Object.wait(Native Method)"), "\t- waiting on <0x0a> (a " + OBJ + ")",
                        at("x.Q.take(Q.java:5)"), locked("0x0a", OBJ))
                .thread("blocked-1", "BLOCKED (on object monitor)", at("x.Q.put(Q.java:9)"), waitingToLock("0x0a", OBJ))
                .end();
        ThreadDumpResult r = text(d);
        ThreadDumpResult.LockInfo l = r.getLocks().get(0);
        assertEquals("0x0a", l.getAddr());
        assertEquals(-1, l.getOwner(), "wait() 중인 스레드를 소유자로 지목하면 엉뚱한 스레드를 범인으로 만든다");
        assertEquals(1, l.getWaiterCount());
        assertTrue(r.getDeadlocks().isEmpty());
    }

    @Test
    void 그래프_교착_JVM_보고_없이도_찾는다() {
        String d = new Dump(ts(0), VM8)
                .thread("A", "BLOCKED (on object monitor)", at("x.Y.a(Y.java:1)"), waitingToLock("0x02", OBJ), locked("0x01", OBJ))
                .thread("B", "BLOCKED (on object monitor)", at("x.Y.b(Y.java:2)"), waitingToLock("0x03", OBJ), locked("0x02", OBJ))
                .thread("C", "BLOCKED (on object monitor)", at("x.Y.c(Y.java:3)"), waitingToLock("0x01", OBJ), locked("0x03", OBJ))
                .thread("victim", "BLOCKED (on object monitor)", at("x.Y.v(Y.java:4)"), waitingToLock("0x01", OBJ))
                .end();
        ThreadDumpResult r = text(d);
        assertEquals(1, r.getDeadlocks().size());
        ThreadDumpResult.Deadlock dl = r.getDeadlocks().get(0);
        assertEquals("graph", dl.getSource());
        assertEquals(3, dl.getTracks().size(), "교착 고리 밖에서 기다리는 victim 은 고리에 넣지 않는다");
        assertEquals(3, dl.getChain().size());
        assertEquals("Critical", finding(r, "DEADLOCK").getSeverity());
        assertEquals(1, r.getKpi().getDeadlocks());
        assertEquals(1, r.getSnapshots().get(0).getDeadlocks());
        // victim 의 사슬 끝은 고리 안의 스레드다 — 무한 루프 없이 끝난다
        ThreadDumpResult.LockInfo l1 = r.getLocks().stream().filter(l -> l.getAddr().equals("0x01")).findFirst().orElseThrow();
        assertTrue(l1.getRootBlocker() >= 0);
    }

    @Test
    void 같은_교착이_여러_스냅샷에_있어도_1건이다() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(new Dump(ts(i * 5), VM8)
                    .thread("A", "BLOCKED (on object monitor)", at("x.Y.a(Y.java:1)"), waitingToLock("0x02", OBJ), locked("0x01", OBJ))
                    .thread("B", "BLOCKED (on object monitor)", at("x.Y.b(Y.java:2)"), waitingToLock("0x01", OBJ), locked("0x02", OBJ))
                    .end());
        }
        ThreadDumpResult r = text(sb.toString());
        assertEquals(1, r.getDeadlocks().size());
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> ev = (List<java.util.Map<String, Object>>) finding(r, "DEADLOCK").getEvidence().get("deadlocks");
        assertEquals(3, ev.get(0).get("seenInSnapshots"));
        // 교착 스레드는 STUCK_THREADS 로 한 번 더 세지 않는다
        assertFalse(has(r, "STUCK_THREADS"));
        assertEquals("deadlock", r.getStuck().get(0).getCategory());
    }

    @Test
    void ReentrantLock_대기는_경합_Condition_대기는_아니다() {
        Dump d = new Dump(ts(0), VM8)
                .thread("owner", "TIMED_WAITING (sleeping)", at("java.lang.Thread.sleep(Native Method)"), at("x.Y.hold(Y.java:1)"),
                        "", "   Locked ownable synchronizers:", "\t- <0x0b> (a " + SYNC + ")");
        for (int i = 0; i < 6; i++) d.thread("w-" + i, "WAITING (parking)", at("sun.misc.Unsafe.park(Native Method)"), parking("0x0b", SYNC), at("x.Y.work(Y.java:2)"));
        for (int i = 0; i < 6; i++) d.thread("c-" + i, "WAITING (parking)", at("sun.misc.Unsafe.park(Native Method)"), parking("0x0c", COND), at("x.Y.await(Y.java:3)"));
        ThreadDumpResult r = text(d.end());
        assertEquals(1, r.getLocks().size(), "Condition 대기는 락 표에 넣지 않는다");
        assertEquals("juc", r.getLocks().get(0).getKind());
        assertEquals(6, r.getLocks().get(0).getWaiterCount());
        assertEquals("owner", name(r, r.getLocks().get(0).getOwner()));
        assertEquals("Medium", finding(r, "LOCK_CONTENTION").getSeverity());
    }

    @Test
    void jstack_l_없이_받은_덤프는_소유자를_모른다고_알린다() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 3; i++) d.thread("w-" + i, "WAITING (parking)", at("sun.misc.Unsafe.park(Native Method)"), parking("0x0b", SYNC), at("x.Y.work(Y.java:2)"));
        ThreadDumpResult r = text(d.end());
        assertEquals(-1, r.getLocks().get(0).getOwner());
        assertTrue(has(r, "NO_LOCK_OWNER_INFO"));
        assertFalse(has(r, "LOCK_CONTENTION"), "대기자 3명은 임계(5) 미만이다");
    }

    @Test
    void BLOCKED_비율_락_하나가_설명하면_중복_소견을_내지_않는다() {
        Dump one = new Dump(ts(0), VM8).thread("owner", "RUNNABLE", at("x.Y.hold(Y.java:1)"), locked("0x01", OBJ));
        for (int i = 0; i < 20; i++) one.thread("b-" + i, "BLOCKED (on object monitor)", at("x.Y.in(Y.java:2)"), waitingToLock("0x01", OBJ));
        ThreadDumpResult r1 = text(one.end());
        assertTrue(has(r1, "LOCK_CONTENTION"));
        assertFalse(has(r1, "BLOCKED_RATIO_HIGH"));

        // 락 12개에 2명씩 — 어느 락도 임계를 못 넘지만 전체 비율이 높다
        Dump many = new Dump(ts(0), VM8);
        for (int k = 0; k < 12; k++) {
            many.thread("o-" + k, "RUNNABLE", at("x.Y.hold(Y.java:1)"), locked("0x1" + Integer.toHexString(k), OBJ));
            for (int i = 0; i < 2; i++) many.thread("b-" + k + "-" + i, "BLOCKED (on object monitor)", at("x.Y.in(Y.java:2)"), waitingToLock("0x1" + Integer.toHexString(k), OBJ));
        }
        ThreadDumpResult r2 = text(many.end());
        assertFalse(has(r2, "LOCK_CONTENTION"));
        ThreadDumpResult.Finding br = finding(r2, "BLOCKED_RATIO_HIGH");
        assertNotNull(br, () -> "" + codes(r2));
        assertEquals("High", br.getSeverity());     // 24/36 = 67%
    }

    @Test
    void 정체_판정_게이트() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(new Dump(ts(i * 5), VM8)
                    .thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)"), at("x.Y.run(Y.java:1)"))
                    .thread("acceptor", "RUNNABLE", at("java.net.PlainSocketImpl.socketAccept(Native Method)"), at("java.net.ServerSocket.accept(ServerSocket.java:1)"))
                    .thread("selector", "RUNNABLE", at("sun.nio.ch.EPollArrayWrapper.epollWait(Native Method)"), at("sun.nio.ch.SelectorImpl.select(SelectorImpl.java:1)"))
                    .thread("db-reader", "RUNNABLE", at("java.net.SocketInputStream.socketRead0(Native Method)"), at("oracle.jdbc.driver.T4CMAREngine.unmarshalUB1(T4C.java:1)"))
                    .thread("scheduler", "TIMED_WAITING (sleeping)", at("java.lang.Thread.sleep(Native Method)"), at("x.Cron.loop(Cron.java:1)"))
                    .thread("pool-1-thread-1", "WAITING (parking)", IDLE_BODY)
                    .thread("pool-1-thread-2", "WAITING (parking)", IDLE_BODY)
                    .thread("mover", "RUNNABLE", at("x.Y.step" + i + "(Y.java:" + i + ")"))
                    .end());
        }
        ThreadDumpResult r = text(sb.toString());
        List<String> names = r.getStuck().stream().map(s -> name(r, s.getT())).toList();
        assertEquals(List.of("looper", "db-reader"), names, "수신 대기·유휴·풀 밖 sleep·스택이 바뀌는 스레드는 정체가 아니다");
        assertEquals("running", r.getStuck().get(0).getCategory());
        assertEquals("io", r.getStuck().get(1).getCategory());
        assertEquals(10.0, r.getStuck().get(0).getDurationSec());
        assertEquals("Medium", finding(r, "STUCK_THREADS").getSeverity());
        assertEquals(2, r.getKpi().getStuck());
    }

    @Test
    void 스냅샷_2개는_30초_이상_벌어져야_정체다() {
        String near = new Dump(ts(0), VM8).thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)")).end()
                + new Dump(ts(5), VM8).thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)")).end();
        assertTrue(text(near).getStuck().isEmpty());
        String far = new Dump(ts(0), VM8).thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)")).end()
                + new Dump(ts(40), VM8).thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)")).end();
        assertEquals(1, text(far).getStuck().size());
    }

    @Test
    void CPU_델타_JDK11_이상만() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(new Dump(null, VM11, i * 5.0)
                    .thread("hot", "RUNNABLE", 100 + i * 4900, at("x.Y.spin(Y.java:7)"))
                    .thread("cool", "RUNNABLE", 100 + i * 50, at("x.Y.work(Y.java:" + i + ")"))
                    .end());
        }
        ThreadDumpResult r = text(sb.toString());
        assertEquals("none", r.getMeta().getTimeSource());
        assertEquals("elapsed", r.getMeta().getIntervalSource(), "시각 줄이 없으면 elapsed 차이로 간격을 추정한다");
        assertEquals(10.0, r.getMeta().getSpanSec());
        ThreadDumpResult.Finding f = finding(r, "HOT_THREAD_CPU");
        assertNotNull(f, () -> "" + codes(r));
        assertEquals("High", f.getSeverity(), "두 구간 연속으로 높다");
        assertEquals(1, f.getTracks().size());
        assertEquals("hot", name(r, f.getTracks().get(0)));
        assertEquals(98.0, r.getCpuTop().get(0).getPct());
        assertTrue(has(r, "NO_TIMESTAMP"));
        assertEquals(List.of(100.0, 5000.0, 9900.0), track(r, "hot").getCpu());
    }

    @Test
    void 요청_풀_유휴_없음_인식된_풀만() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 2; s++) {
            Dump d = new Dump(ts(s * 5), VM8);
            for (int i = 0; i < 12; i++) d.thread("http-nio-8080-exec-" + i, "RUNNABLE", at("x.Svc.call" + (i % 3) + "(Svc.java:" + s + ")"));
            for (int i = 0; i < 12; i++) d.thread("my-batch-" + i, "RUNNABLE", at("x.Batch.run" + (i % 3) + "(Batch.java:" + s + ")"));
            for (int i = 0; i < 12; i++) d.thread("svc-worker-" + i, "RUNNABLE", at("x.Biz.go(Biz.java:" + s + ")"),
                    at("javax.servlet.http.HttpServlet.service(HttpServlet.java:742)"));
            sb.append(d.end());
        }
        ThreadDumpResult r = text(sb.toString());
        List<ThreadDumpResult.Finding> fs = r.getFindings().stream().filter(f -> f.getCode().equals("POOL_NO_IDLE")).toList();
        List<Object> poolNames = fs.stream().map(f -> f.getEvidence().get("pool")).toList();
        assertTrue(poolNames.contains("http-nio-#-exec-#"), poolNames.toString());
        assertTrue(poolNames.contains("svc-worker-#"), "서블릿을 실행 중이면 이름과 무관하게 요청 풀이다: " + poolNames);
        assertFalse(poolNames.contains("my-batch-#"), "용도를 모르는 풀은 '유휴 없음'으로 경고하지 않는다");
        assertEquals("High", fs.get(0).getSeverity(), "2개 스냅샷 연속");
    }

    @Test
    void 커넥션_풀_대기와_DB_응답_대기() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 4; i++) d.thread("http-nio-8080-exec-" + i, "TIMED_WAITING (parking)",
                at("sun.misc.Unsafe.park(Native Method)"), at("java.util.concurrent.SynchronousQueue$TransferQueue.awaitFulfill(SynchronousQueue.java:1)"),
                at("com.zaxxer.hikari.util.ConcurrentBag.borrow(ConcurrentBag.java:1)"), at("com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:1)"),
                at("x.Dao.find(Dao.java:1)"));
        for (int i = 4; i < 12; i++) d.thread("http-nio-8080-exec-" + i, "RUNNABLE",
                at("java.net.SocketInputStream.socketRead0(Native Method)"), at("java.net.SocketInputStream.read(SocketInputStream.java:1)"),
                at("oracle.jdbc.driver.T4CSocketInputStreamWrapper.read(T4C.java:1)"), at("oracle.jdbc.driver.OraclePreparedStatement.executeQuery(Ops.java:1)"),
                at("x.Dao.find(Dao.java:2)"));
        ThreadDumpResult r = text(d.end());
        ThreadDumpResult.Finding cp = finding(r, "CONN_POOL_WAIT");
        assertNotNull(cp, () -> "" + codes(r));
        assertEquals(4, cp.getEvidence().get("waiting"));
        assertEquals("High", cp.getSeverity(), "워커 12개 중 4개(33%)");
        ThreadDumpResult.Finding db = finding(r, "DB_WAIT_CLUSTER");
        assertNotNull(db);
        assertEquals(8, db.getEvidence().get("waiting"));
        assertEquals("High", db.getSeverity(), "작업 중 12개 중 8개(67%)");
        assertFalse(has(r, "REMOTE_CALL_WAIT_CLUSTER"));
        // 커넥션 풀 대기 스레드는 SynchronousQueue 프레임이 있어도 유휴가 아니라 대기다 — 풀에 유휴가 없다고 본다
        assertNotNull(track(r, "http-nio-8080-exec-0"));
    }

    @Test
    void 스레드_수_과다와_증가_추세() {
        StringBuilder sb = new StringBuilder();
        int[] sizes = {100, 180, 320};
        for (int s = 0; s < 3; s++) {
            Dump d = new Dump(ts(s * 10), VM8);
            for (int i = 0; i < sizes[s]; i++) d.thread("leak-" + i, "WAITING (parking)", IDLE_BODY);
            sb.append(d.end());
        }
        ThreadDumpResult r = text(sb.toString());
        ThreadDumpResult.Finding g = finding(r, "THREAD_COUNT_GROWTH");
        assertNotNull(g, () -> "" + codes(r));
        assertEquals("High", g.getSeverity());      // 100 → 320 (×3.2, +220)
        assertEquals(100, g.getEvidence().get("from"));
        assertEquals(320, g.getEvidence().get("to"));
        assertFalse(has(r, "THREAD_COUNT_HIGH"));
        assertEquals(320, r.getKpi().getThreadsPeak());
        assertEquals(1, r.getStacks().size(), "같은 스택은 한 번만 저장된다");
    }

    @Test
    void 기본_이름_Executor_가_많으면_반복_생성_의심() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 1; i <= 60; i++) d.thread("pool-" + i + "-thread-1", "WAITING (parking)", IDLE_BODY);
        ThreadDumpResult r = text(d.end());
        ThreadDumpResult.Finding f = finding(r, "EXECUTOR_LEAK_SUSPECT");
        assertNotNull(f);
        assertEquals(60, f.getEvidence().get("pools"));
        assertEquals("Medium", f.getSeverity());
    }

    @Test
    void Finalizer_BLOCKED_와_WebLogic_STUCK() {
        String d = new Dump(ts(0), VM8)
                .thread("Finalizer", "BLOCKED (on object monitor)", at("x.Res.finalize(Res.java:9)"), waitingToLock("0x01", OBJ))
                .thread("[STUCK] ExecuteThread: '3' for queue: 'weblogic.kernel.Default (self-tuning)'", "RUNNABLE", at("x.Y.z(Y.java:1)"))
                .thread("[ACTIVE] ExecuteThread: '4' for queue: 'weblogic.kernel.Default (self-tuning)'", "RUNNABLE", at("x.Y.z(Y.java:2)"))
                .end();
        ThreadDumpResult r = text(d);
        assertTrue(has(r, "FINALIZER_BLOCKED"));
        ThreadDumpResult.Finding w = finding(r, "WLS_STUCK_THREADS");
        assertNotNull(w);
        assertEquals(1, w.getEvidence().get("count"));
        // 상태 접두가 달라도 같은 풀로 묶인다
        assertEquals(1, r.getPools().size());
        assertEquals("ExecuteThread: '#' for queue: 'weblogic.kernel.Default (self-tuning)'", r.getPools().get(0).getStem());
        assertEquals("worker", r.getPools().get(0).getKind());
    }

    @Test
    void 덤프_밖_로그의_OOM() {
        String d = "2026-11-26 09:59:58 ERROR app.Svc - failed\njava.lang.OutOfMemoryError: unable to create new native thread\n\tat java.lang.Thread.start0(Native Method)\n"
                + new Dump(ts(0), VM8).thread("main", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
        ThreadDumpResult r = text(d);
        ThreadDumpResult.Finding f = finding(r, "OOM_IN_LOG");
        assertNotNull(f);
        assertEquals("High", f.getSeverity());
        assertTrue(f.getEvidence().get("kinds").toString().contains("NATIVE_THREAD"));
        assertTrue(r.getMeta().isEmbedded());
    }

    @Test
    void 재기동을_넘는_스냅샷은_비교하지_않는다() {
        String run1 = new Dump(ts(0), VM8).thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)")).end()
                + new Dump(ts(5), VM8).thread("looper", "RUNNABLE", at("x.Y.spin(Y.java:7)")).end();
        // 재기동 — tid 가 전부 바뀐다(같은 #1, 같은 이름, 같은 스택)
        String run2 = new Dump(ts(60), VM8).numStart(1).raw("")
                .raw("\"looper\" #1 prio=5 os_prio=0 tid=0x00007f99aaaa0000 nid=0x777 runnable [0x0]")
                .raw("   java.lang.Thread.State: RUNNABLE").raw(at("x.Y.spin(Y.java:7)")).raw("").end();
        ThreadDumpResult r = text(run1 + run2);
        assertEquals(2, r.getMeta().getRuns());
        assertEquals(List.of(0, 0, 1), r.getSnapshots().stream().map(ThreadDumpResult.Snapshot::getRun).toList());
        assertTrue(r.getStuck().isEmpty(), "서로 다른 실행의 같은 이름 스레드를 이어 붙이면 안 된다");
        assertEquals(2, r.getTracks().size());
        assertTrue(has(r, "RESTART_BETWEEN_SNAPSHOTS"));
    }

    @Test
    void 규칙_하나가_터져도_나머지_분석은_끝난다() {
        // 교착 규칙이 raw 를 읽다 NPE 가 나도록 깨진 입력을 직접 만든다
        ThreadDumpResult.Diagnostics diag = new ThreadDumpResult.Diagnostics();
        StackTable table = new StackTable();
        ThreadDumpParser p = new ThreadDumpParser(ThreadDumpLimits.DEFAULT, table, diag);
        String[] lines = new Dump(ts(0), VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).end().split("\n", -1);
        for (int i = 0; i < lines.length; i++) p.feedLine(i + 1, lines[i]);
        p.finish(lines.length);
        var snaps = p.snapshots();
        ThreadDumpParser.JvmDeadlock broken = new ThreadDumpParser.JvmDeadlock();
        broken.chain.add(new String[]{null, null, null, null});   // 이름이 null — nameKey 의 TreeSet 이 NPE
        snaps.get(0).jvmDeadlocks.add(broken);

        ThreadDumpResult r = new ThreadDumpAnalyzer(snaps, 1, table, diag, null).analyze();
        assertEquals(1, diag.getRuleFailures().size());
        assertEquals("deadlock", diag.getRuleFailures().get(0).getRule());
        ThreadDumpResult.Finding f = finding(r, "RULE_FAILED");
        assertNotNull(f);
        assertEquals("Info", f.getSeverity());
        assertEquals(1, r.getSnapshots().size(), "나머지 결과는 정상적으로 만들어진다");
        assertEquals(1, r.getTracks().size());
        assertTrue(has(r, "SINGLE_SNAPSHOT"));
    }

    @Test
    void 시간초과_취소는_격리하지_않고_올린다() {
        ThreadDumpResult.Diagnostics diag = new ThreadDumpResult.Diagnostics();
        StackTable table = new StackTable();
        ThreadDumpParser p = new ThreadDumpParser(ThreadDumpLimits.DEFAULT, table, diag);
        String[] lines = new Dump(ts(0), VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)")).end().split("\n", -1);
        for (int i = 0; i < lines.length; i++) p.feedLine(i + 1, lines[i]);
        p.finish(lines.length);
        ThreadDumpException e = assertThrows(ThreadDumpException.class,
                () -> new ThreadDumpAnalyzer(p.snapshots(), 1, table, diag, () -> { throw new ThreadDumpException("TIMEOUT", "시간 초과"); }).analyze());
        assertEquals("TIMEOUT", e.code());
    }

    @Test
    void JDK_버전_판독() {
        assertEquals(8, ThreadDumpAnalyzer.jdkMajor(VM8));
        assertEquals(11, ThreadDumpAnalyzer.jdkMajor(VM11));
        assertEquals(17, ThreadDumpAnalyzer.jdkMajor(VM17));
        assertEquals(7, ThreadDumpAnalyzer.jdkMajor("Full thread dump Java HotSpot(TM) 64-Bit Server VM (24.80-b11 mixed mode):"));
        assertEquals(21, ThreadDumpAnalyzer.jdkMajor("Full thread dump OpenJDK 64-Bit Server VM (21.0.12.1+1-LTS mixed mode, sharing):"));
        assertNull(ThreadDumpAnalyzer.jdkMajor(null));
    }

    @Test
    void 소견은_심각도_내림차순이고_종합은_Info_를_뺀_최댓값() {
        String d = new Dump(null, VM8).thread("main", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
        ThreadDumpResult r = text(d);
        assertEquals("Info", r.getKpi().getSeverity(), "Info 소견만 있으면 종합도 Info");
        assertEquals(0, r.getKpi().getFindingCount());
        assertTrue(has(r, "SINGLE_SNAPSHOT"));
        assertTrue(has(r, "NO_TIMESTAMP"));
    }
}

package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.ThreadDumpResult.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2.7.4 소견 점검·고도화 — 고친 오탐·미탐, 근거 줄, 원인 연결, 새 소견 3종.
 *
 * <p>연결·DNS·콘솔 락·JUL 락·RMI 대기 프레임은 이 호스트의 JDK 8·21 데모로 받은 덤프에서 옮긴 것이다.
 * WebLogic Muxer·연결당 스레드 방식의 요청 수신 대기는 실측 덤프가 없어 합성 텍스트로만 검증한다(THREAD_DUMP.md 함정 91).
 */
class ThreadDumpFindingInsightTest {

    private static final String OBJ = "java.lang.Object";
    private static final String SYNC = "java.util.concurrent.locks.ReentrantLock$NonfairSync";
    private static final String SERVLET = "javax.servlet.http.HttpServlet.service(HttpServlet.java:742)";
    private static final String SOCKET_READ = "java.net.SocketInputStream.socketRead0(Native Method)";

    private static final String[] HIKARI_WAIT = {
            at("sun.misc.Unsafe.park(Native Method)"),
            at("java.util.concurrent.SynchronousQueue$TransferQueue.awaitFulfill(SynchronousQueue.java:1)"),
            at("com.zaxxer.hikari.util.ConcurrentBag.borrow(ConcurrentBag.java:1)"),
            at("com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:1)"),
            at("x.Dao.find(Dao.java:1)"),
    };

    private static String ts(int sec) { return String.format("2026-11-27 10:%02d:%02d", sec / 60, sec % 60); }

    private static List<Finding> all(ThreadDumpResult r, String code) {
        return r.getFindings().stream().filter(f -> code.equals(f.getCode())).toList();
    }

    private static boolean basisHas(Finding f, String part) {
        return f.getBasis() != null && f.getBasis().stream().anyMatch(b -> b.contains(part));
    }

    private static boolean related(Finding f, String code) {
        return f.getRelated() != null && f.getRelated().contains(code);
    }

    // ── A8·A1: 커넥션 풀 ────────────────────────────────────────

    @Test
    void 커넥션_풀_대기는_유휴가_아니다_풀이_꽉_찬_것이다() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 12; i++) d.thread("http-nio-8080-exec-" + i, "TIMED_WAITING (parking)", HIKARI_WAIT);
        ThreadDumpResult r = text(d.end());
        assertEquals(2, r.getMeta().getRuleset());
        ThreadDumpResult.Pool pool = r.getPools().get(0);
        assertEquals(List.of(0), pool.getIdle(), "Hikari 대기는 SynchronousQueue 프레임이 있어도 유휴로 세지 않는다");
        assertFalse(track(r, "http-nio-8080-exec-0").isIdle());

        Finding pf = finding(r, "POOL_NO_IDLE");
        assertNotNull(pf, () -> "풀이 커넥션 대기로 꽉 찼는데 소견이 없다: " + codes(r));
        assertTrue(pf.getBasis().get(0).contains("커넥션 풀 대기 12"), pf.getBasis().toString());
        assertTrue(related(pf, "CONN_POOL_WAIT"));

        Finding cp = finding(r, "CONN_POOL_WAIT");
        assertEquals("db", cp.getEvidence().get("poolKind"));
        assertEquals(0, cp.getEvidence().get("jdbcUsers"));
        assertTrue(basisHas(cp, "반납 누락"), "커넥션을 쓰는 스레드가 하나도 없으면 누수를 의심한다: " + cp.getBasis());
        assertTrue(basisHas(cp, "호출 지점 x.Dao.find"));

        assertNotNull(r.getVerdict());
        assertEquals("CONN_POOL_WAIT", r.getVerdict().getCodes().get(0), "머리는 가장 높은 등급(High)의 소견이다");
        assertTrue(r.getVerdict().getCodes().contains("POOL_NO_IDLE"));
        assertEquals("CONN_POOL_WAIT", r.getFindings().get(0).getCode(), "소견 카드도 종합 진단의 머리부터 나온다");
    }

    @Test
    void 커넥션_풀_종류를_가른다_HTTP_와_객체_풀을_DB_문구로_안내하지_않는다() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 5; i++) d.thread("api-" + i, "TIMED_WAITING (parking)", at("sun.misc.Unsafe.park(Native Method)"),
                at("org.apache.http.pool.AbstractConnPool.getPoolEntryBlocking(AbstractConnPool.java:1)"), at("x.Api.call(Api.java:1)"));
        for (int i = 0; i < 4; i++) d.thread("cache-" + i, "TIMED_WAITING (parking)", at("sun.misc.Unsafe.park(Native Method)"),
                at("org.apache.commons.pool2.impl.GenericObjectPool.borrowObject(GenericObjectPool.java:1)"),
                at("redis.clients.jedis.util.Pool.getResource(Pool.java:1)"), at("x.Cache.get(Cache.java:1)"));
        for (int i = 0; i < 3; i++) d.thread("dbcp-" + i, "TIMED_WAITING (parking)", at("sun.misc.Unsafe.park(Native Method)"),
                at("org.apache.commons.pool2.impl.GenericObjectPool.borrowObject(GenericObjectPool.java:1)"),
                at("org.apache.commons.dbcp2.PoolingDataSource.getConnection(PoolingDataSource.java:1)"), at("x.Dao.find(Dao.java:1)"));
        ThreadDumpResult r = text(d.end());
        List<Finding> fs = all(r, "CONN_POOL_WAIT");
        assertEquals(3, fs.size(), () -> "" + codes(r));
        Finding http = fs.stream().filter(f -> "http".equals(f.getEvidence().get("poolKind"))).findFirst().orElseThrow();
        assertEquals(5, http.getEvidence().get("waiting"));
        assertTrue(http.getTitle().contains("HTTP"));
        assertFalse(http.getDetail().contains("DB") || http.getAdvice().contains("DB"), "HTTP 커넥션 풀 대기에 DB 문구가 나가면 안 된다");
        Finding obj = fs.stream().filter(f -> "object".equals(f.getEvidence().get("poolKind"))).findFirst().orElseThrow();
        assertEquals(4, obj.getEvidence().get("waiting"));
        assertTrue(basisHas(obj, "redis.clients.jedis"));
        Finding db = fs.stream().filter(f -> "db".equals(f.getEvidence().get("poolKind"))).findFirst().orElseThrow();
        assertEquals(3, db.getEvidence().get("waiting"), "commons-pool 을 DBCP 가 불렀으면 DB 커넥션 풀이다");
    }

    // ── B1: 락 경합 ─────────────────────────────────────────────

    @Test
    void 락_경합은_스레드가_많은_JVM_에서도_절대_상한으로_잡는다() {
        Dump d = new Dump(ts(0), VM8).thread("owner", "RUNNABLE", at("x.Y.hold(Y.java:1)"), locked("0x01", OBJ));
        for (int i = 0; i < 400; i++) d.thread("bg-" + i, "WAITING (parking)", IDLE_BODY);
        for (int i = 0; i < 35; i++) d.thread("b-" + i, "BLOCKED (on object monitor)", at("x.Y.in(Y.java:2)"), waitingToLock("0x01", OBJ));
        ThreadDumpResult r = text(d.end());
        Finding f = finding(r, "LOCK_CONTENTION");
        assertNotNull(f, "436개 중 35개(8%)는 비율 기준(10%)만으로는 놓친다 — 절대 상한 30 으로 잡는다");
        assertEquals("Medium", f.getSeverity());
        assertTrue(basisHas(f, "대기 35개 / Java 스레드 436개"));
        assertTrue(basisHas(f, "소유 스레드 \"owner\" — 실행 중"));

        Dump big = new Dump(ts(0), VM8).thread("owner", "RUNNABLE", at("x.Y.hold(Y.java:1)"), locked("0x01", OBJ));
        for (int i = 0; i < 1000; i++) big.thread("bg-" + i, "WAITING (parking)", IDLE_BODY);
        for (int i = 0; i < 100; i++) big.thread("b-" + i, "BLOCKED (on object monitor)", at("x.Y.in(Y.java:2)"), waitingToLock("0x01", OBJ));
        assertEquals("High", finding(text(big.end()), "LOCK_CONTENTION").getSeverity(), "대기 100개는 스레드 수와 무관하게 High");
    }

    @Test
    void 락_종류는_대기_지점_프레임으로_가른다() {
        // JDK 21 — System.out 은 모니터가 아니라 ReentrantLock(InternalLock)이다. 락 클래스로는 알 수 없다
        Dump console = new Dump(ts(0), VM17).thread("writer", "RUNNABLE", body(
                at("java.io.FileOutputStream.writeBytes(Native Method)"), at("java.io.PrintStream.implWrite(PrintStream.java:645)"),
                at("java.io.PrintStream.println(PrintStream.java:1168)"), at("x.Svc.log(Svc.java:1)"), owns("0x0b", SYNC)));
        for (int i = 0; i < 6; i++) console.thread("w-" + i, "WAITING (parking)",
                at("jdk.internal.misc.Unsafe.park(Native Method)"), parking("0x0b", SYNC),
                at("java.util.concurrent.locks.LockSupport.park(LockSupport.java:221)"),
                at("java.util.concurrent.locks.ReentrantLock.lock(ReentrantLock.java:322)"),
                at("jdk.internal.misc.InternalLock.lock(InternalLock.java:74)"),
                at("java.io.PrintStream.writeln(PrintStream.java:824)"), at("java.io.PrintStream.println(PrintStream.java:1168)"),
                at("x.Svc.log(Svc.java:1)"));
        Finding c = finding(text(console.end()), "LOCK_CONTENTION");
        assertNotNull(c);
        assertEquals("console", c.getEvidence().get("lockKind"));
        assertTrue(basisHas(c, "대기 지점 java.io.PrintStream.writeln"), c.getBasis().toString());
        assertTrue(c.getAdvice().contains("System.out"));

        // JDK 8 — JUL 핸들러는 모니터다
        Dump jul = new Dump(ts(0), VM8).thread("writer", "RUNNABLE", at("java.io.FileOutputStream.writeBytes(Native Method)"),
                at("java.util.logging.StreamHandler.publish(StreamHandler.java:211)"), locked("0x0c", "java.util.logging.ConsoleHandler"));
        for (int i = 0; i < 6; i++) jul.thread("w-" + i, "BLOCKED (on object monitor)",
                at("java.util.logging.StreamHandler.publish(StreamHandler.java:206)"), waitingToLock("0x0c", "java.util.logging.ConsoleHandler"),
                at("java.util.logging.Logger.log(Logger.java:738)"), at("x.Svc.log(Svc.java:1)"));
        assertEquals("logging", finding(text(jul.end()), "LOCK_CONTENTION").getEvidence().get("lockKind"));

        Dump cl = new Dump(ts(0), VM8).thread("loader", "RUNNABLE", at("java.util.zip.ZipFile.read(Native Method)"),
                at("java.lang.ClassLoader.loadClass(ClassLoader.java:411)"), locked("0x0d", OBJ));
        for (int i = 0; i < 6; i++) cl.thread("w-" + i, "BLOCKED (on object monitor)",
                at("java.lang.ClassLoader.loadClass(ClassLoader.java:404)"), waitingToLock("0x0d", OBJ), at("x.Plugin.find(Plugin.java:1)"));
        Finding k = finding(text(cl.end()), "LOCK_CONTENTION");
        assertEquals("classload", k.getEvidence().get("lockKind"));
        assertTrue(k.getAdvice().contains("클래스 로딩"));

        assertEquals("classload", ThreadDumpRules.lockKind("org.apache.catalina.loader.WebappClassLoaderBase.loadClass(WebappClassLoaderBase.java:1)", OBJ));
        assertEquals("jdbc", ThreadDumpRules.lockKind("x.Dao.q(Dao.java:1)", "oracle.jdbc.driver.T4CConnection"));
        assertEquals("dns", ThreadDumpRules.lockKind("java.net.InetAddress$NameServiceAddresses.get(InetAddress.java:815)", "java.net.InetAddress$NameServiceAddresses"));
        assertNull(ThreadDumpRules.lockKind("x.Y.z(Y.java:1)", OBJ), "덤프 글자로 확정할 수 없으면 종류를 말하지 않는다");
    }

    // ── A2·B7·B8: 정체·무한 루프 ────────────────────────────────

    @Test
    void 정체_관측_구간이_짧으면_High_로_올리지_않는다() {
        StringBuilder near = new StringBuilder(), far = new StringBuilder();
        for (int s = 0; s < 3; s++) {
            Dump a = new Dump(ts(s), VM8), b = new Dump(ts(s * 10), VM8);
            for (int i = 0; i < 6; i++) {
                a.thread("job-" + i, "RUNNABLE", at("x.Y.spin(Y.java:7)"));
                b.thread("job-" + i, "RUNNABLE", at("x.Y.spin(Y.java:7)"));
            }
            near.append(a.end());
            far.append(b.end());
        }
        Finding n = finding(text(near.toString()), "STUCK_THREADS");
        assertNotNull(n);
        assertEquals("Medium", n.getSeverity(), "1초 간격 3회(2초)는 지나가는 작업일 수 있다");
        assertTrue(basisHas(n, "관측 구간이 짧습니다"));
        Finding f = finding(text(far.toString()), "STUCK_THREADS");
        assertEquals("High", f.getSeverity(), "20초 동안 6개가 같은 스택이면 High");
        assertFalse(basisHas(f, "관측 구간이 짧습니다"));
        assertTrue(basisHas(f, "분류: 실행 중 6"));
        assertEquals(2, f.getSnapshotIdx(), "스레드 링크가 열 스냅샷을 준다");
    }

    @Test
    void 같은_스택에서_CPU_를_쓰면_무한_루프_의심_CPU_를_안_쓰면_네이티브_대기() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(new Dump(null, VM11, i * 5.0)
                    .thread("spinner", "RUNNABLE", 100 + i * 4900, at("java.util.HashMap.getEntry(HashMap.java:465)"), at("x.Y.lookup(Y.java:7)"))
                    .thread("native-waiter", "RUNNABLE", 100 + i, at("x.Jni.call(Native Method)"), at("x.Y.run(Y.java:1)"))
                    .end());
        }
        ThreadDumpResult r = text(sb.toString());
        Finding busy = finding(r, "BUSY_LOOP_SUSPECT");
        assertNotNull(busy, () -> "" + codes(r));
        assertEquals("High", busy.getSeverity());
        assertEquals(1, busy.getTracks().size());
        assertEquals("spinner", name(r, busy.getTracks().get(0)));
        assertEquals(98.0, busy.getEvidence().get("topPct"));
        assertTrue(basisHas(busy, "HashMap"), "최상단이 HashMap 내부면 동시 수정 가능성을 알린다");
        assertTrue(related(busy, "STUCK_THREADS") && related(busy, "HOT_THREAD_CPU"));

        Finding stuck = finding(r, "STUCK_THREADS");
        assertEquals(1, stuck.getEvidence().get("runningWithoutCpu"), "RUNNABLE·같은 스택이어도 CPU 를 안 썼으면 루프가 아니다");
        // 무한 루프 소견이 따로 나와도 CPU 소견의 스레드는 그대로다
        assertEquals(1, finding(r, "HOT_THREAD_CPU").getTracks().size());
    }

    @Test
    void 락_소유자_정체는_지속_시간을_말한다() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 2; s++) {
            Dump d = new Dump(ts(s * 3), VM8).thread("owner", "TIMED_WAITING (sleeping)", at("java.lang.Thread.sleep(Native Method)"),
                    at("x.Y.hold(Y.java:1)"), locked("0x01", OBJ));
            for (int i = 0; i < 6; i++) d.thread("w-" + i, "BLOCKED (on object monitor)", at("x.Y.in(Y.java:2)"), waitingToLock("0x01", OBJ));
            sb.append(d.end());
        }
        ThreadDumpResult r = text(sb.toString());
        Finding f = finding(r, "LOCK_OWNER_STUCK");
        assertNotNull(f);
        assertEquals(3.0, f.getEvidence().get("durationSec"));
        assertTrue(basisHas(f, "2개 스냅샷 동안 같은 스택(약 3.0초)"));
        assertTrue(basisHas(f, "관측 구간이 10초 미만"));
        assertTrue(basisHas(f, "소유 스레드가 하고 있는 일: sleep"));
        assertTrue(related(f, "LOCK_CONTENTION"));
    }

    // ── B2·B3: CPU ──────────────────────────────────────────────

    @Test
    void 스냅샷이_1개여도_생성_이후_평균_CPU_는_본다() {
        String d = new Dump(ts(0), VM17, 100)
                .threadAt("burner", "RUNNABLE", 58_000, 60, at("x.Y.spin(Y.java:7)"))
                .threadAt("young", "RUNNABLE", 9_900, 10, at("x.Y.spin(Y.java:8)"))
                .threadAt("calm", "RUNNABLE", 500, 600, at("x.Y.work(Y.java:1)"))
                .vmThread("GC Thread#0", 1, 100_000)
                .end();
        ThreadDumpResult r = text(d);
        Finding hot = finding(r, "HOT_THREAD_CPU");
        assertNotNull(hot, () -> "" + codes(r));
        assertEquals("Medium", hot.getSeverity(), "평균값이라 지금을 말하지 못한다 — Medium 을 넘기지 않는다");
        assertEquals("lifetime", hot.getEvidence().get("basis"));
        assertTrue(hot.getTitle().contains("생성 이후 평균"));
        assertEquals(1, hot.getTracks().size(), "갓 만들어진 스레드(10초)의 평균은 보지 않는다");
        assertEquals("burner", name(r, hot.getTracks().get(0)));
        Finding vm = finding(r, "VM_THREAD_CPU_HIGH");
        assertNotNull(vm);
        assertEquals(1, vm.getEvidence().get("gc"));
        assertTrue(r.getCpuTop().isEmpty(), "구간 CPU 표는 스냅샷이 2개 이상일 때만 채운다");
    }

    @Test
    void JVM_스레드의_CPU_는_컴파일러도_GC_도_잡는다() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(new Dump(null, VM17, i * 5.0)
                    .thread("C2 CompilerThread0", "RUNNABLE", 100 + i * 4900)
                    .thread("app", "RUNNABLE", 100 + i * 10, at("x.Y.work(Y.java:" + i + ")"))
                    .vmThread("GC Thread#0", 1, 100 + i * 4900)
                    .end());
        }
        ThreadDumpResult r = text(sb.toString());
        Finding vm = finding(r, "VM_THREAD_CPU_HIGH");
        assertNotNull(vm, () -> "" + codes(r));
        assertEquals(1, vm.getEvidence().get("gc"));
        assertEquals(1, vm.getEvidence().get("compiler"), "상태 줄이 있는 컴파일러 스레드는 예전에 어느 소견에도 들지 않았다");
        assertEquals("High", vm.getSeverity(), "GC 스레드가 두 구간 연속이면 High");
        assertTrue(vm.getAdvice().contains("GC 로그"));
        assertFalse(has(r, "HOT_THREAD_CPU"), "JVM 스레드는 애플리케이션 CPU 소견에 넣지 않는다");
    }

    // ── A4·B4 + 원인 연결 ───────────────────────────────────────

    @Test
    void 풀_유휴_거의_없음_과_원인_연결() {
        Dump d = new Dump(ts(0), VM8)
                .thread("holder", "RUNNABLE", at(SOCKET_READ), at("oracle.jdbc.driver.T4CMAREngine.unmarshalUB1(T4C.java:1)"),
                        at("x.Cache.load(Cache.java:9)"), locked("0x01", OBJ))
                .thread("http-nio-8080-exec-0", "WAITING (parking)", IDLE_BODY);
        for (int i = 1; i <= 30; i++) d.thread("http-nio-8080-exec-" + i, "BLOCKED (on object monitor)",
                at("x.Cache.get(Cache.java:3)"), waitingToLock("0x01", OBJ), at(SERVLET));
        for (int i = 31; i < 40; i++) d.thread("http-nio-8080-exec-" + i, "RUNNABLE", at("x.Svc.work(Svc.java:" + i + ")"), at(SERVLET));
        ThreadDumpResult r = text(d.end());

        Finding pf = finding(r, "POOL_NO_IDLE");
        assertNotNull(pf, () -> "40개 중 유휴 1개(2.5%)는 예전에 놓쳤다: " + codes(r));
        assertEquals("Medium", pf.getSeverity());
        assertTrue(pf.getTitle().contains("거의 없음"));
        assertEquals(1, pf.getEvidence().get("idle"));
        assertTrue(pf.getDetail().contains("최대 크기는 덤프에 없습니다"), "풀이 한계에 닿았다고 단정하지 않는다");
        assertTrue(pf.getBasis().get(0).contains("작업 중 39개") && pf.getBasis().get(0).contains("락 대기 30"), pf.getBasis().toString());
        assertTrue(basisHas(pf, "그중 30개가 락 0x01"));
        assertTrue(related(pf, "LOCK_CONTENTION"));

        Finding lc = finding(r, "LOCK_CONTENTION");
        assertEquals("High", lc.getSeverity());
        assertEquals("DB_WAIT", lc.getEvidence().get("ownerActivity"));
        assertTrue(basisHas(lc, "소유 스레드 \"holder\" — DB 응답 대기"), lc.getBasis().toString());

        ThreadDumpResult.Verdict v = r.getVerdict();
        assertNotNull(v);
        assertEquals(List.of("LOCK_CONTENTION", "POOL_NO_IDLE"), v.getCodes());
        assertEquals(List.of("LOCK_CONTENTION", "POOL_NO_IDLE", "SINGLE_SNAPSHOT"), codes(r), "등급 내림차순, 같은 등급은 종합 진단의 흐름 순");
        assertTrue(v.getHeadline().startsWith("락 경합"));
        assertEquals(1, v.getPoints().size());
    }

    @Test
    void 종합_진단의_머리는_최고_등급_소견이다() {
        // 교착(Critical)은 이어진 소견이 없고 락 경합(High) 쪽에만 연결이 있다 — High 를 머리에 올려 교착을 가리면 안 된다
        ThreadDumpResult r = fixture("jdk8-deadlock-3snap.txt");
        assertEquals("Critical", r.getKpi().getSeverity());
        assertTrue(related(finding(r, "LOCK_CONTENTION"), "LOCK_OWNER_STUCK"));
        assertNull(r.getVerdict());
        assertEquals("DEADLOCK", r.getFindings().get(0).getCode(), "화면 배너는 최상위 소견으로 대신한다");
    }

    @Test
    void 교착_뒤에_막힌_스레드를_센다() {
        Dump d = new Dump(ts(0), VM8)
                .thread("A", "BLOCKED (on object monitor)", at("x.Y.a(Y.java:1)"), waitingToLock("0x02", OBJ), locked("0x01", OBJ))
                .thread("B", "BLOCKED (on object monitor)", at("x.Y.b(Y.java:2)"), waitingToLock("0x01", OBJ), locked("0x02", OBJ));
        for (int i = 0; i < 6; i++) d.thread("victim-" + i, "BLOCKED (on object monitor)", at("x.Y.v(Y.java:4)"), waitingToLock("0x01", OBJ));
        ThreadDumpResult r = text(d.end());
        Finding dl = finding(r, "DEADLOCK");
        assertEquals(6, dl.getEvidence().get("blockedBehind"));
        assertTrue(basisHas(dl, "기다리는 스레드가 6개 더"));
        assertTrue(basisHas(dl, "락 대기 그래프의 순환"));
        Finding lc = finding(r, "LOCK_CONTENTION");
        assertNotNull(lc);
        assertTrue(basisHas(lc, "교착에 묶여"));
        assertTrue(related(lc, "DEADLOCK") && related(dl, "LOCK_CONTENTION"));
        assertEquals("DEADLOCK", r.getVerdict().getCodes().get(0));
    }

    // ── B5: 소켓 대기 · 요청 수신 대기 ──────────────────────────

    @Test
    void 대상을_모르는_소켓_응답_대기_집중() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 10; i++) d.thread("http-nio-8080-exec-" + i, "RUNNABLE", at(SOCKET_READ),
                at("redis.clients.jedis.Connection.readProtocolWithCheckingBroken(Connection.java:1)"), at("x.Cache.get(Cache.java:1)"), at(SERVLET));
        ThreadDumpResult r = text(d.end());
        Finding f = finding(r, "SOCKET_WAIT_CLUSTER");
        assertNotNull(f, () -> "" + codes(r));
        assertEquals(10, f.getEvidence().get("waiting"));
        assertEquals("High", f.getSeverity());
        assertTrue(basisHas(f, "호출 지점 redis.clients.jedis.Connection.readProtocolWithCheckingBroken"));
        assertFalse(has(r, "DB_WAIT_CLUSTER") || has(r, "REMOTE_CALL_WAIT_CLUSTER"));
        Finding pf = finding(r, "POOL_NO_IDLE");
        assertTrue(related(pf, "SOCKET_WAIT_CLUSTER"), "풀이 꽉 찬 원인으로 이어진다");
    }

    @Test
    void 요청을_기다리며_소켓을_읽는_풀_스레드는_유휴다() {
        // 연결당 스레드 방식 — 서블릿·필터에 들어가기 전의 소켓 읽기는 다음 요청을 기다리는 것이다
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 3; s++) {
            Dump d = new Dump(ts(s * 10), VM8);
            for (int i = 0; i < 10; i++) d.thread("http-bio-8080-exec-" + i, "RUNNABLE", at(SOCKET_READ),
                    at("x.was.Connector.readRequestLine(Connector.java:1)"), at("org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61)"));
            sb.append(d.end());
        }
        ThreadDumpResult r = text(sb.toString());
        assertEquals(List.of(10, 10, 10), r.getPools().get(0).getIdle());
        assertTrue(track(r, "http-bio-8080-exec-0").isIdle());
        assertTrue(r.getStuck().isEmpty(), "평생 같은 스택이어도 정체가 아니다");
        assertFalse(has(r, "POOL_NO_IDLE") || has(r, "SOCKET_WAIT_CLUSTER") || has(r, "STUCK_THREADS"), () -> "" + codes(r));

        // 필터 체인 안에서의 소켓 읽기는 요청을 처리하는 중이다 — 유휴로 빼지 않는다
        Dump busy = new Dump(ts(0), VM8);
        for (int i = 0; i < 10; i++) busy.thread("http-bio-8080-exec-" + i, "RUNNABLE", at(SOCKET_READ),
                at("x.Auth.lookup(Auth.java:1)"), at("x.AuthFilter.doFilter(AuthFilter.java:1)"),
                at("org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61)"));
        ThreadDumpResult rb = text(busy.end());
        assertEquals(List.of(0), rb.getPools().get(0).getIdle());
        assertTrue(has(rb, "SOCKET_WAIT_CLUSTER"), () -> "" + codes(rb));
    }

    @Test
    void 평생_소켓을_읽는_백그라운드_스레드와_RMI_연결_스레드는_집중으로_세지_않는다() {
        Dump d = new Dump(ts(0), VM8)
                .thread("collector-a", "RUNNABLE", at(SOCKET_READ), at("x.CollectA.loop(CollectA.java:1)"))
                .thread("collector-b", "RUNNABLE", at(SOCKET_READ), at("x.CollectB.loop(CollectB.java:1)"))
                .thread("collector-c", "RUNNABLE", at(SOCKET_READ), at("x.CollectC.loop(CollectC.java:1)"));
        // 이 호스트 JDK 8 의 JMX 연결 스레드 스택 그대로
        for (int i = 1; i <= 6; i++) d.thread("RMI TCP Connection(" + i + ")-10.0.0.1", "RUNNABLE", at(SOCKET_READ),
                at("java.net.SocketInputStream.socketRead(SocketInputStream.java:116)"), at("java.net.SocketInputStream.read(SocketInputStream.java:171)"),
                at("java.net.SocketInputStream.read(SocketInputStream.java:141)"), at("java.io.BufferedInputStream.fill(BufferedInputStream.java:246)"),
                at("java.io.BufferedInputStream.read(BufferedInputStream.java:265)"), at("java.io.FilterInputStream.read(FilterInputStream.java:83)"),
                at("sun.rmi.transport.tcp.TCPTransport.handleMessages(TCPTransport.java:555)"),
                at("sun.rmi.transport.tcp.TCPTransport$ConnectionHandler.run0(TCPTransport.java:834)"));
        ThreadDumpResult quiet = text(d.end());
        assertFalse(has(quiet, "SOCKET_WAIT_CLUSTER"), () -> "스택이 제각각인 3개와 RMI 대기 6개는 집중이 아니다: " + codes(quiet));

        // 같은 스택이 5개 이상 묶이면 풀 밖이어도 센다
        for (int i = 0; i < 5; i++) d.thread("mq-listener-" + i, "RUNNABLE", at(SOCKET_READ), at("x.mq.Channel.receive(Channel.java:1)"), at("x.mq.Listener.run(Listener.java:1)"));
        ThreadDumpResult r = text(d.end());
        Finding f = finding(r, "SOCKET_WAIT_CLUSTER");
        assertNotNull(f, () -> "" + codes(r));
        assertEquals(5, f.getEvidence().get("waiting"), "묶음에 든 5개만 센다");
        assertEquals(8, f.getEvidence().get("active"), "RMI 대기는 작업 중으로 세지 않는다");
        assertEquals(5, f.getTracks().size(), "관련 스레드도 세는 기준과 같다");
    }

    @Test
    void 외부_호출_응답_대기_집중() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 6; i++) d.thread("http-nio-8080-exec-" + i, "RUNNABLE", at(SOCKET_READ),
                at("org.apache.http.impl.io.SessionInputBufferImpl.fillBuffer(SessionInputBufferImpl.java:1)"), at("x.Api.call(Api.java:1)"), at(SERVLET));
        for (int i = 6; i < 10; i++) d.thread("http-nio-8080-exec-" + i, "RUNNABLE", at("x.Svc.work(Svc.java:" + i + ")"), at(SERVLET));
        ThreadDumpResult r = text(d.end());
        Finding f = finding(r, "REMOTE_CALL_WAIT_CLUSTER");
        assertNotNull(f, () -> "" + codes(r));
        assertEquals(6, f.getEvidence().get("waiting"));
        assertEquals(10, f.getEvidence().get("active"));
        assertEquals("High", f.getSeverity());      // 60%
        assertTrue(basisHas(f, "호출 지점 x.Api.call(Api.java:1) ×6"));
        assertTrue(basisHas(f, "스택이 완전히 같은 스레드가 최대 6개"));
        assertEquals(6, f.getTracks().size());
    }

    // ── B6: 연결 수립 대기 ──────────────────────────────────────

    @Test
    void 연결_수립과_DNS_에서_멈춘_스레드() {
        // 프레임은 이 호스트 JDK 21(시한 연결)·JDK 8(socketConnect)·DNS 조회 덤프 그대로
        String[] timed = {at("sun.nio.ch.Net.poll(Native Method)"), at("sun.nio.ch.NioSocketImpl.park(NioSocketImpl.java:191)"),
                at("sun.nio.ch.NioSocketImpl.timedFinishConnect(NioSocketImpl.java:548)"), at("sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:592)"),
                at("java.net.Socket.connect(Socket.java:751)"), at("oracle.net.nt.TcpNTAdapter.connect(TcpNTAdapter.java:1)"),
                at("oracle.jdbc.driver.T4CConnection.connect(T4CConnection.java:1)"), at("x.Dao.open(Dao.java:1)")};
        String[] plain = {at("java.net.PlainSocketImpl.socketConnect(Native Method)"), at("java.net.AbstractPlainSocketImpl.doConnect(AbstractPlainSocketImpl.java:350)"),
                at("java.net.Socket.connect(Socket.java:607)"), at("x.Legacy.open(Legacy.java:1)")};
        String[] dns = {at("java.net.Inet6AddressImpl.lookupAllHostAddr(Native Method)"), at("java.net.InetAddress.getAllByName(InetAddress.java:1688)"), at("x.Client.call(Client.java:1)")};
        StringBuilder two = new StringBuilder();
        String one = null;
        for (int s = 0; s < 2; s++) {
            String d = new Dump(ts(s * 5), VM17, s * 5.0)
                    .thread("db-1", "RUNNABLE", timed).thread("db-2", "RUNNABLE", timed)
                    .thread("legacy", "RUNNABLE", plain).thread("resolver", "RUNNABLE", dns)
                    .thread("reader", "RUNNABLE", at("sun.nio.ch.Net.poll(Native Method)"), at("sun.nio.ch.NioSocketImpl.park(NioSocketImpl.java:191)"),
                            at("sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:280)"), at("sun.nio.ch.NioSocketImpl.implRead(NioSocketImpl.java:304)"), at("x.R.read(R.java:1)"))
                    .end();
            if (s == 0) one = d;
            two.append(d);
        }
        ThreadDumpResult r1 = text(one);
        Finding f = finding(r1, "CONNECT_WAIT");
        assertNotNull(f, () -> "" + codes(r1));
        assertEquals(4, f.getEvidence().get("waiting"), "꼭대기가 같은 Net.poll 이어도 시한 읽기(reader)는 연결 대기가 아니다");
        assertEquals(1, f.getEvidence().get("dns"));
        assertEquals(2, f.getEvidence().get("viaJdbc"));
        assertEquals("Medium", f.getSeverity());
        assertTrue(f.getDetail().contains("DNS 조회"));
        assertFalse(has(r1, "DB_WAIT_CLUSTER"));

        Finding f2 = finding(text(two.toString()), "CONNECT_WAIT");
        assertEquals("High", f2.getSeverity(), "인접 스냅샷에서도 같은 스택이면 연결이 계속 맺어지지 않는 것이다");
        assertTrue(basisHas(f2, "인접 스냅샷에서도 같은 스택"));
    }

    // ── A6·A3·A7 ────────────────────────────────────────────────

    @Test
    void WebLogic_Muxer_는_요청_풀도_정체도_아니다() {
        StringBuilder sb = new StringBuilder();
        String muxLock = "weblogic.socket.EPollSocketMuxer$1";
        for (int s = 0; s < 3; s++) {
            Dump d = new Dump(ts(s * 10), VM8)
                    .thread("ExecuteThread: '0' for queue: 'weblogic.socket.Muxer'", "RUNNABLE", at("weblogic.socket.EPollSocketMuxer.epollWait(Native Method)"),
                            at("weblogic.socket.EPollSocketMuxer.processSockets(EPollSocketMuxer.java:153)"), locked("0x0e", muxLock),
                            at("weblogic.work.ExecuteThread.run(ExecuteThread.java:178)"));
            for (int i = 1; i < 4; i++) d.thread("ExecuteThread: '" + i + "' for queue: 'weblogic.socket.Muxer'", "BLOCKED (on object monitor)",
                    at("weblogic.socket.EPollSocketMuxer.processSockets(EPollSocketMuxer.java:153)"), waitingToLock("0x0e", muxLock),
                    at("weblogic.work.ExecuteThread.run(ExecuteThread.java:178)"));
            for (int i = 0; i < 12; i++) d.thread("[ACTIVE] ExecuteThread: '" + i + "' for queue: 'weblogic.kernel.Default (self-tuning)'", "WAITING (on object monitor)",
                    at("java.lang.Object.wait(Native Method)"), at("weblogic.work.ExecuteThread.waitForRequest(ExecuteThread.java:157)"),
                    at("weblogic.work.ExecuteThread.run(ExecuteThread.java:178)"));
            sb.append(d.end());
        }
        ThreadDumpResult r = text(sb.toString());
        ThreadDumpResult.Pool mux = r.getPools().stream().filter(p -> p.getStem().contains("weblogic.socket.Muxer")).findFirst().orElseThrow();
        assertEquals("other", mux.getKind(), "이름이 ExecuteThread 꼴이고 실행기 프레임이 있어도 요청 처리 풀이 아니다");
        assertTrue(r.getStuck().isEmpty(), "Muxer 는 평생 같은 스택이고 서로의 락을 기다리는 모습도 정상이다: " + r.getStuck());
        assertFalse(has(r, "STUCK_THREADS") || has(r, "POOL_NO_IDLE") || has(r, "LOCK_CONTENTION"), () -> "" + codes(r));
        assertEquals("Info", r.getKpi().getSeverity());
    }

    @Test
    void Finalizer_같은_스택은_정체와_같은_기준으로_본다() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 3; s++) {
            sb.append(new Dump(ts(s * 5), VM8).thread("Finalizer", "RUNNABLE", at("x.Res.close(Res.java:9)"), at("x.Res.finalize(Res.java:3)"))
                    .thread("main", "RUNNABLE", at("x.Y.z(Y.java:" + s + ")")).end());
            if (s == 1) assertFalse(has(text(sb.toString()), "FINALIZER_BLOCKED"), "5초 간격 2회로는 지나가는 finalize 일 수 있다");
        }
        Finding f = finding(text(sb.toString()), "FINALIZER_BLOCKED");
        assertNotNull(f);
        assertTrue(f.getDetail().contains("3개 스냅샷 동안 같은 스택"));
        assertTrue(basisHas(f, "3개 스냅샷 동안 같은 스택(약 10.0초)"));
    }

    @Test
    void 스레드_수_과다는_Java_스레드_기준임을_밝힌다() {
        Dump d = new Dump(ts(0), VM8).vmThread("VM Thread");
        for (int i = 0; i < 2000; i++) d.thread("w-" + i, "WAITING (parking)", IDLE_BODY);
        ThreadDumpResult r = text(d.end());
        Finding f = finding(r, "THREAD_COUNT_HIGH");
        assertNotNull(f);
        assertEquals("Medium", f.getSeverity());
        assertEquals(2000, f.getEvidence().get("threads"));
        assertEquals(2001, r.getKpi().getThreadsPeak(), "화면 KPI 는 전체 스레드 수다");
        assertTrue(f.getDetail().contains("JVM 내부 스레드 제외"));
        assertTrue(basisHas(f, "전체 2001개"), "소견의 수와 화면의 수가 다른 이유를 근거로 남긴다");
    }

    @Test
    void 스레드_증가는_늘어난_이름_줄기를_말한다() {
        StringBuilder sb = new StringBuilder();
        int[] leak = {10, 90, 230};
        for (int s = 0; s < 3; s++) {
            Dump d = new Dump(ts(s * 10), VM8);
            for (int i = 0; i < 100; i++) d.thread("steady-" + i, "WAITING (parking)", IDLE_BODY);
            for (int i = 0; i < leak[s]; i++) d.thread("leak-" + i, "WAITING (parking)", IDLE_BODY);
            sb.append(d.end());
        }
        Finding g = finding(text(sb.toString()), "THREAD_COUNT_GROWTH");
        assertNotNull(g);
        assertEquals(List.of("leak-# +220 (10 → 230)"), g.getEvidence().get("grownStems"));
        assertTrue(basisHas(g, "늘어난 이름 줄기: leak-# +220"));
    }

    @Test
    void OOM_은_종류에_맞는_권고를_내고_스레드_수_소견과_잇는다() {
        Dump d = new Dump(ts(0), VM8);
        for (int i = 1; i <= 60; i++) d.thread("pool-" + i + "-thread-1", "WAITING (parking)", IDLE_BODY);
        String text = "java.lang.OutOfMemoryError: unable to create new native thread\n\tat java.lang.Thread.start0(Native Method)\n" + d.end();
        ThreadDumpResult r = text(text);
        Finding f = finding(r, "OOM_IN_LOG");
        assertEquals("NATIVE_THREAD", f.getEvidence().get("mainKind"));
        assertTrue(f.getAdvice().contains("ulimit"), f.getAdvice());
        assertTrue(basisHas(f, "네이티브 스레드 생성 실패 ×1"));
        assertTrue(related(f, "EXECUTOR_LEAK_SUSPECT"));
        assertEquals(List.of("OOM_IN_LOG", "EXECUTOR_LEAK_SUSPECT"), r.getVerdict().getCodes());

        String heap = "java.lang.OutOfMemoryError: Java heap space\n" + new Dump(ts(0), VM8).thread("main", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
        assertTrue(finding(text(heap), "OOM_IN_LOG").getAdvice().contains("힙 덤프"));
    }

    // ── 진단류 양성(지금까지 테스트가 없던 것) ──────────────────

    @Test
    void 해석_못_한_줄과_스레드_상한_소견() {
        String d = new Dump(ts(0), VM8).thread("a", "RUNNABLE", at("x.Y.z(Y.java:1)"))
                .raw("이 줄은 덤프 문법이 아니다 ###")
                .thread("b", "RUNNABLE", at("x.Y.z(Y.java:2)")).end();
        ThreadDumpResult r = text(d);
        assertTrue(r.getDiagnostics().getUnparsedLines() > 0);
        Finding u = finding(r, "LINES_UNPARSED");
        assertNotNull(u, () -> "" + codes(r));
        assertNull(u.getBasis(), "근거 줄이 없는 소견은 basis 를 만들지 않는다(빈 배열이 아니라 키가 없다)");

        Dump many = new Dump(ts(0), VM8);
        for (int i = 0; i < 5; i++) many.thread("t-" + i, "RUNNABLE", at("x.Y.z(Y.java:" + i + ")"));
        ThreadDumpResult lim = text(many.end(), new ThreadDumpLimits(ThreadDumpLimits.DEFAULT.maxBytes(), 4096, 3, 30));
        Finding t = finding(lim, "THREAD_LIMIT_HIT");
        assertNotNull(t, () -> "" + codes(lim));
        assertEquals("Low", t.getSeverity());
        assertTrue(t.getDetail().contains("2개를 건너뛰었습니다"));
    }

    // ── 저장 형식 ───────────────────────────────────────────────

    @Test
    void 옛_결과에는_새_필드가_없고_새_결과는_왕복한다() {
        ThreadDumpResult old = ThreadDumpResultCodec.fromJson(
                "{\"schema\":1,\"meta\":{\"format\":\"HOTSPOT\"},\"findings\":[{\"code\":\"LOCK_CONTENTION\",\"severity\":\"High\",\"title\":\"t\",\"detail\":\"d\",\"advice\":\"a\",\"evidence\":{},\"tracks\":[0]}]}");
        assertNotNull(old);
        assertNull(old.getMeta().getRuleset(), "ruleset 이 없으면 옛 규칙의 결과다 — 화면이 재분석을 안내한다");
        assertNull(old.getVerdict());
        assertNull(old.getFindings().get(0).getBasis());
        assertNull(old.getFindings().get(0).getRelated());

        Dump d = new Dump(ts(0), VM8);
        for (int i = 0; i < 12; i++) d.thread("http-nio-8080-exec-" + i, "TIMED_WAITING (parking)", HIKARI_WAIT);
        ThreadDumpResult r = text(d.end());
        String json = ThreadDumpResultCodec.toJson(r);
        assertTrue(json.contains("\"verdict\"") && json.contains("\"basis\"") && json.contains("\"related\"") && json.contains("\"ruleset\":2"));
        ThreadDumpResult back = ThreadDumpResultCodec.fromJson(json);
        assertEquals(json, ThreadDumpResultCodec.toJson(back));
        assertEquals(r.getVerdict().getHeadline(), back.getVerdict().getHeadline());
        // 근거 줄이 없는 소견(안내류)은 키 자체를 싣지 않는다
        String single = ThreadDumpResultCodec.toJson(text(new Dump(ts(0), VM8).thread("main", "RUNNABLE", at("x.Y.z(Y.java:1)")).end()));
        assertFalse(single.contains("\"basis\"") || single.contains("\"verdict\""), single);
    }
}

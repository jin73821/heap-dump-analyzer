package com.heapdump.analyzer.parser.threaddump;

import java.util.regex.Pattern;

/**
 * 스택·이름 시그니처 — 분석기가 스레드를 "유휴/수신 대기/소켓 읽기/커넥션 풀 대기/DB/외부 호출" 로 가르는 근거.
 *
 * <p>스레드 덤프 글자만으로 확실한 것만 둔다. 스택 하나의 판정은 {@link #traitsOf}가 한 번 계산하고
 * 분석기가 스택 id 로 캐시한다(같은 스택을 가진 스레드 수천 개를 다시 훑지 않는다).
 */
final class ThreadDumpRules {

    private ThreadDumpRules() {}

    /** 유휴 판정에 보는 상위 프레임 수. 풀 대기는 스택 꼭대기 근처에만 있다. */
    static final int IDLE_DEPTH = 8;
    static final int WAIT_DEPTH = 12;

    /** 일거리를 기다리는 중(큐에서 take/poll) — 상태가 WAITING/TIMED_WAITING 일 때만 유휴로 친다. */
    private static final String[] IDLE = {
            "ThreadPoolExecutor.getTask(",
            "ScheduledThreadPoolExecutor$DelayedWorkQueue.take(",
            "ScheduledThreadPoolExecutor$DelayedWorkQueue.poll(",
            "org.apache.tomcat.util.threads.TaskQueue.take(",
            "org.apache.tomcat.util.threads.TaskQueue.poll(",
            "java.util.concurrent.LinkedBlockingQueue.take(",
            "java.util.concurrent.LinkedBlockingQueue.poll(",
            "java.util.concurrent.LinkedBlockingDeque.take",
            "java.util.concurrent.LinkedBlockingDeque.poll",
            "java.util.concurrent.ArrayBlockingQueue.take(",
            "java.util.concurrent.ArrayBlockingQueue.poll(",
            "java.util.concurrent.SynchronousQueue",
            "java.util.concurrent.DelayQueue.take(",
            "java.util.concurrent.DelayQueue.poll(",
            "java.util.concurrent.LinkedTransferQueue",
            "java.util.concurrent.ForkJoinPool.awaitWork(",
            "weblogic.work.ExecuteThread.waitForRequest(",
            "org.eclipse.jetty.util.thread.QueuedThreadPool.idleJobPoll(",
            "org.eclipse.jetty.util.thread.QueuedThreadPool$Runner.idleJobPoll(",
            "java.lang.ref.ReferenceQueue.remove",
            "java.lang.ref.NativeReferenceQueue.",
            "java.lang.ref.Reference.waitForReferencePendingList(",
            "java.lang.ref.Reference$ReferenceHandler.run(",
            "java.util.TimerThread.mainLoop(",
            "jdk.internal.ref.CleanerImpl.run(",
    };

    /** 연결·이벤트를 기다리는 수신 스레드 — RUNNABLE 로 찍혀도 CPU 를 쓰지 않고, 같은 스택에 머무는 것이 정상이다. */
    private static final String[] LISTEN = {
            "sun.nio.ch.SelectorImpl.select(",
            "sun.nio.ch.SelectorImpl.lockAndDoSelect(",
            "sun.nio.ch.EPollSelectorImpl.doSelect(",
            "sun.nio.ch.EPollArrayWrapper.epollWait(",
            "sun.nio.ch.EPollArrayWrapper.poll(",
            "sun.nio.ch.EPoll.wait(",
            "sun.nio.ch.KQueue.poll(",
            "sun.nio.ch.PollSelectorImpl.doSelect(",
            "sun.nio.ch.ServerSocketChannelImpl.accept",
            "sun.nio.ch.Net.accept(",
            "java.net.PlainSocketImpl.socketAccept(",
            "java.net.DualStackPlainSocketImpl.accept0(",
            "java.net.ServerSocket.accept(",
            "java.net.ServerSocket.implAccept(",
            "sun.nio.ch.NioSocketImpl.accept(",
            "io.netty.channel.epoll.Native.epollWait",
            "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll",
            "sun.nio.ch.UnixDomainSockets.accept",
    };

    /** 소켓에서 응답을 읽는 중 — RUNNABLE 로 찍히지만 실제로는 상대(DB·외부 시스템)를 기다린다. */
    private static final String[] SOCKET_READ = {
            "java.net.SocketInputStream.socketRead0(",
            "java.net.SocketInputStream.socketRead(",
            "java.net.SocketInputStream.read(",
            "sun.nio.ch.NioSocketImpl.implRead(",
            "sun.nio.ch.NioSocketImpl.read(",
            "sun.nio.ch.SocketDispatcher.read0(",
            "sun.nio.ch.SocketDispatcher.read(",
            "sun.nio.ch.SocketChannelImpl.read(",
            "sun.nio.ch.FileDispatcherImpl.read0(",
            "sun.security.ssl.SSLSocketInputRecord.read",
            "java.net.SocketInputStream.implRead(",
    };

    /**
     * 커넥션 풀에서 반납을 기다리는 중 — 종류별(2.7.4). 종류를 섞으면 HTTP 커넥션 풀·Redis 객체 풀 대기를 DB 문구로 안내하게 된다.
     * commons-pool 은 무엇의 풀인지 클래스 이름만으로는 알 수 없어 스택에 JDBC 프레임이 있을 때만 DB 로 본다.
     */
    private static final String[] CONN_POOL_DB = {
            "com.zaxxer.hikari.pool.HikariPool.getConnection(",
            "com.zaxxer.hikari.util.ConcurrentBag.borrow(",
            "org.apache.tomcat.dbcp.pool2.impl.GenericObjectPool.borrowObject(",
            "org.apache.tomcat.jdbc.pool.ConnectionPool.borrowConnection(",
            "oracle.ucp.common.UniversalConnectionPoolImpl.borrowConnection",
            "weblogic.common.resourcepool.ResourcePoolImpl.reserveResource",
            "com.mchange.v2.resourcepool.BasicResourcePool.awaitAvailable(",
            "jeus.jdbc.connectionpool.ConnectionPoolImpl.getConnection(",
    };
    private static final String[] CONN_POOL_HTTP = {
            "org.apache.http.pool.AbstractConnPool.getPoolEntryBlocking(",
    };
    private static final String[] CONN_POOL_OBJECT = {
            "org.apache.commons.pool2.impl.GenericObjectPool.borrowObject(",
            "org.apache.commons.pool.impl.GenericObjectPool.borrowObject(",
    };
    static final String POOL_DB = "db", POOL_HTTP = "http", POOL_OBJECT = "object";

    /**
     * 연결을 맺는 중(2.7.4) — 이 호스트의 JDK 8·21 덤프로 확인한 프레임만 둔다. JDK 21 의 시한 연결은 꼭대기가 시한 읽기와 같은
     * {@code sun.nio.ch.Net.poll} 이라 그 프레임은 어느 목록에도 넣지 않는다(읽기와 연결을 가르지 못한다).
     */
    private static final String[] CONNECT = {
            "java.net.PlainSocketImpl.socketConnect(",
            "sun.nio.ch.Net.connect0(",
            "sun.nio.ch.NioSocketImpl.timedFinishConnect(",
    };
    private static final String[] DNS = {
            "java.net.Inet6AddressImpl.lookupAllHostAddr(",
            "java.net.Inet4AddressImpl.lookupAllHostAddr(",
    };

    private static final String[] JDBC = {
            "oracle.jdbc.", "com.mysql.", "org.mariadb.jdbc.", "org.postgresql.", "com.microsoft.sqlserver.jdbc.",
            "com.ibm.db2.jcc.", "com.tmax.tibero.jdbc.", "com.sybase.jdbc", "net.sourceforge.jtds.", "cubrid.jdbc.",
            "Altibase.jdbc.", "org.h2.jdbc", "com.edb.", "oracle.net.",
    };

    private static final String[] HTTP_CLIENT = {
            "org.apache.http.", "org.apache.hc.", "okhttp3.", "com.squareup.okhttp.",
            "sun.net.www.protocol.http.HttpURLConnection", "sun.net.www.http.HttpClient",
            "java.net.http.", "jdk.internal.net.http.", "org.springframework.web.client.RestTemplate",
            "reactor.netty.http.client.", "feign.", "org.apache.commons.httpclient.",
    };

    /**
     * 요청 처리 풀의 실행기 프레임(2.7.1) — 유휴 스레드도 스택 바닥에 이 프레임이 있다. 이름 규칙에 없는 WAS(JEUS {@code http-NN} 등)의
     * 웹 스레드 풀을 "요청 처리 풀" 로 알아보는 근거다. 이름만 보면 JEUS 웹 스레드 100개가 모두 바빠도 '유휴 없음' 경고가 나지 않았다.
     */
    private static final String[] WEB_POOL = {
            "jeus.servlet.engine.WebThreadPoolExecutor", "org.apache.tomcat.util.threads.ThreadPoolExecutor", "org.apache.tomcat.util.threads.TaskThread",
            "org.eclipse.jetty.util.thread.QueuedThreadPool", "weblogic.work.ExecuteThread", "io.undertow.server.", "com.ibm.ws.util.ThreadPool$Worker",
    };

    private static final String[] SERVLET = {
            "javax.servlet.http.HttpServlet.service(", "jakarta.servlet.http.HttpServlet.service(",
    };

    /** 요청 처리 풀로 확실히 아는 이름 줄기(숫자는 {@code #} 로 치환된 상태). Acceptor·Poller·Selector 는 넣지 않는다. */
    private static final Pattern WORKER_STEM = Pattern.compile(
            "^(?:http|https|ajp)-[A-Za-z0-9]*-?.*-exec-#$"
                    + "|^catalina-exec-#$"
                    + "|^qtp#-#$"
                    + "|^default task-#$"
                    + "|^XNIO-# task-#$"
                    + "|ExecuteThread: '#' for queue: "
                    + "|^WebContainer : #$"
                    + "|^http-listener-.*#$");

    private static final Pattern WLS_STATE_PREFIX = Pattern.compile("^\\[(?:ACTIVE|STANDBY|STUCK|HOGGING)\\] ");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    static final Pattern EXECUTOR_DEFAULT_NAME = Pattern.compile("^pool-(\\d+)-thread-\\d+$");
    /** {@code ReentrantLock}·{@code ReentrantReadWriteLock} 의 Sync 만 경합이다. Condition·Latch·Queue 대기는 락 경합이 아니다. */
    private static final Pattern JUC_LOCK_CLASS = Pattern.compile(
            "java\\.util\\.concurrent\\.locks\\.(?:ReentrantLock|ReentrantReadWriteLock)\\$(?:Nonfair|Fair)?Sync$");

    private static final String[] SYSTEM_NAMES = {
            "Reference Handler", "Finalizer", "Signal Dispatcher", "Service Thread", "Attach Listener", "Common-Cleaner",
            "Monitor Deflation Thread", "Notification Thread", "Sweeper thread", "DestroyJavaVM", "Surrogate Locker Thread",
            "JFR ", "Cleaner-", "process reaper", "RMI TCP Accept", "RMI Scheduler", "RMI Reaper", "JMX server connection timeout",
    };
    private static final String[] SYSTEM_PREFIXES = {
            "C1 CompilerThread", "C2 CompilerThread", "JVMCI", "VM ", "GC ", "G1 ", "Gang worker", "Concurrent Mark-Sweep",
            "ZGC", "ZWorker", "ZDirector", "ZDriver", "ZStat", "ZUnmapper", "ZUncommitter", "Shenandoah", "StringDedup",
            "XGC", "XWorker", "Java2D Disposer",
    };

    static final class Traits {
        boolean idle;
        boolean listen;
        boolean socketRead;
        boolean connPoolWait;
        boolean jdbc;
        boolean httpClient;
        boolean servlet;
        boolean webPool;
        boolean sleep;
        /** 연결 수립(TCP connect) 중 / DNS 조회 중. */
        boolean connect;
        boolean dns;
        /** 서블릿 필터 체인 안 — 서블릿 진입 전이라도 요청을 처리하는 중이다. */
        boolean filterChain;
        /** RMI 연결 스레드가 다음 호출을 기다리며 소켓을 읽는 중(정상). */
        boolean rmiIdle;
        /** 커넥션 풀 대기일 때 풀 종류({@link #POOL_DB} 등). 아니면 null. */
        String poolKind;
    }

    static Traits traitsOf(StackTable table, int stackId) {
        Traits t = new Traits();
        int[] f = table.framesOf(stackId);
        boolean dbcp = false;
        for (int i = 0; i < f.length; i++) {
            String fr = table.frameText(f[i]);
            if (!dbcp && fr.startsWith("org.apache.commons.dbcp")) dbcp = true;
            if (i == 0 && (fr.startsWith("java.lang.Thread.sleep(") || fr.startsWith("java.lang.Thread.sleep0(")
                    || fr.startsWith("java.lang.Thread.sleepNanos"))) t.sleep = true;
            if (i < 3 && fr.startsWith("java.lang.Thread.sleep")) t.sleep = true;
            if (i < IDLE_DEPTH && !t.idle && any(fr, IDLE)) t.idle = true;
            if (i < IDLE_DEPTH && !t.listen && any(fr, LISTEN)) t.listen = true;
            if (i < IDLE_DEPTH && !t.socketRead && any(fr, SOCKET_READ)) t.socketRead = true;
            if (i < WAIT_DEPTH && t.poolKind == null) {
                if (any(fr, CONN_POOL_DB)) t.poolKind = POOL_DB;
                else if (any(fr, CONN_POOL_HTTP)) t.poolKind = POOL_HTTP;
                else if (any(fr, CONN_POOL_OBJECT)) t.poolKind = POOL_OBJECT;
            }
            if (i < IDLE_DEPTH && !t.connect && any(fr, CONNECT)) t.connect = true;
            if (i < IDLE_DEPTH && !t.dns && any(fr, DNS)) t.dns = true;
            if (i < WAIT_DEPTH && !t.rmiIdle && fr.contains(RMI_HANDLE_MESSAGES)) t.rmiIdle = true;
            if (!t.jdbc && startsAny(fr, JDBC)) t.jdbc = true;
            if (!t.httpClient && startsAny(fr, HTTP_CLIENT)) t.httpClient = true;
            if (!t.servlet && any(fr, SERVLET)) t.servlet = true;
            if (!t.filterChain && fr.contains(".doFilter(")) t.filterChain = true;
            if (!t.webPool && any(fr, WEB_POOL)) t.webPool = true;
        }
        if (t.poolKind != null) {
            t.connPoolWait = true;
            // commons-pool 을 DBCP 가 불렀거나 스택에 JDBC 드라이버가 있으면 DB 커넥션 풀이다
            if (POOL_OBJECT.equals(t.poolKind) && (t.jdbc || dbcp)) t.poolKind = POOL_DB;
            // 커넥션 풀 대기는 유휴가 아니다 — Hikari 는 SynchronousQueue 로 기다려 유휴 시그니처에도 맞는다(함정 90)
            t.idle = false;
        }
        return t;
    }

    static boolean isSystemThread(String name, boolean javaThread) {
        if (!javaThread) return true;                     // 상태 줄이 없는 JVM 내부 스레드(GC·VM)
        if (name == null) return false;
        for (String s : SYSTEM_NAMES) if (name.startsWith(s)) return true;
        for (String s : SYSTEM_PREFIXES) if (name.startsWith(s)) return true;
        return false;
    }

    /** 풀 이름 줄기 — 숫자 열을 {@code #} 로 바꾸고 WebLogic 상태 접두를 뗀다. */
    static String stem(String name) {
        if (name == null) return "";
        String s = WLS_STATE_PREFIX.matcher(name).replaceFirst("");
        return DIGITS.matcher(s).replaceAll("#");
    }

    static boolean isWorkerStem(String stem) { return WORKER_STEM.matcher(stem).find() && !stem.contains(WLS_MUXER_QUEUE); }

    /**
     * WebLogic 소켓 Muxer — 이름은 {@code ExecuteThread … for queue: 'weblogic.socket.Muxer'} 로 실행 스레드와 같은 꼴이지만
     * 소켓 이벤트를 기다리는 수신 스레드다. 평생 같은 스택이고 서로의 락을 기다리는 모습도 정상이라 정체·요청 풀에서 뺀다.
     * 프레임 이름은 버전마다 달라 이름으로만 가른다(실측 WebLogic 덤프로는 확인하지 못했다).
     */
    static boolean isMuxerName(String name) { return name != null && name.contains(WLS_MUXER_QUEUE); }

    private static final String WLS_MUXER_QUEUE = "for queue: 'weblogic.socket.Muxer'";
    private static final String RMI_HANDLE_MESSAGES = "sun.rmi.transport.tcp.TCPTransport.handleMessages(";

    // ── JVM 내부 스레드 종류(CPU 소견 문구용) ───────────────────
    static final String VM_GC = "gc", VM_COMPILER = "compiler", VM_OTHER = "other";
    private static final String[] GC_PREFIXES = {
            "GC ", "G1 ", "Gang worker", "Concurrent Mark-Sweep", "ZGC", "ZWorker", "ZDirector", "ZDriver", "XGC", "XWorker",
            "Shenandoah",
    };
    private static final String[] COMPILER_PREFIXES = { "C1 CompilerThread", "C2 CompilerThread", "JVMCI" };

    static String vmKind(String name) {
        if (name == null) return VM_OTHER;
        for (String s : GC_PREFIXES) if (name.startsWith(s)) return VM_GC;
        for (String s : COMPILER_PREFIXES) if (name.startsWith(s)) return VM_COMPILER;
        return VM_OTHER;
    }

    // ── 락 종류 — 대기 지점 프레임과 락 클래스로만 가른다 ────────
    static final String LOCK_CONSOLE = "console", LOCK_LOGGING = "logging", LOCK_CLASSLOAD = "classload",
            LOCK_DNS = "dns", LOCK_JDBC = "jdbc";
    private static final String[] LOGGING = {
            "java.util.logging.", "ch.qos.logback.", "org.apache.log4j.", "org.apache.logging.log4j.", "org.jboss.logmanager.",
    };
    /** 락을 얻으려는 코드가 아니라 락 구현 자체인 프레임 — 대기 지점을 찾을 때 건너뛴다. */
    private static final String[] LOCK_IMPL = {
            "java.util.concurrent.", "jdk.internal.misc.", "sun.misc.Unsafe.", "java.lang.Object.wait",
    };

    /**
     * 대기 지점 — 락을 얻으려던 코드의 프레임(락 구현 프레임을 건너뛴 첫 프레임). 없으면 빈 문자열.
     * JDK 21 의 {@code System.out}·JUL 은 모니터가 아니라 ReentrantLock 이라 락 클래스로는 종류를 알 수 없다 — 이 프레임으로 가른다.
     */
    static String waitSite(StackTable table, int stackId) {
        int[] f = table.framesOf(stackId);
        for (int i = 0; i < f.length && i < WAIT_DEPTH; i++) {
            String fr = table.frameText(f[i]);
            if (!startsAny(fr, LOCK_IMPL)) return fr;
        }
        return "";
    }

    /** @return 락 종류. 덤프 글자로 확정할 수 없으면 null. */
    static String lockKind(String waitSite, String lockCls) {
        String w = waitSite == null ? "" : waitSite;
        String c = lockCls == null ? "" : lockCls;
        if (w.startsWith("java.io.PrintStream.") || c.equals("java.io.PrintStream")) return LOCK_CONSOLE;
        if (startsAny(w, LOGGING) || startsAny(c, LOGGING)) return LOCK_LOGGING;
        if (w.startsWith("java.lang.ClassLoader.") || (w.contains("ClassLoader") && w.contains(".loadClass("))) return LOCK_CLASSLOAD;
        if (w.startsWith("java.net.InetAddress") || c.startsWith("java.net.InetAddress")) return LOCK_DNS;
        if (startsAny(c, JDBC)) return LOCK_JDBC;
        return null;
    }

    // ── 호출 지점 — JDK·드라이버·풀 라이브러리 프레임 밖 첫 프레임 ─
    private static final String[] JDK_PREFIXES = { "java.", "javax.", "jakarta.", "sun.", "jdk.", "com.sun." };
    private static final String[] POOL_LIBS = {
            "com.zaxxer.hikari.", "org.apache.commons.pool", "org.apache.commons.dbcp", "org.apache.tomcat.dbcp.", "org.apache.tomcat.jdbc.",
            "oracle.ucp.", "com.mchange.", "weblogic.common.resourcepool.", "weblogic.jdbc.", "jeus.jdbc.",
    };

    /**
     * 드라이버·클라이언트를 부른 지점 — 스택 위에서부터 JDK·JDBC 드라이버·HTTP 클라이언트·풀 라이브러리 프레임을 건너뛴 첫 프레임.
     * 스택에 적힌 사실이지 추정이 아니다(프레임워크 프레임일 수도 있다 — 그대로 보인다). 없으면 null.
     */
    static String callSite(StackTable table, int stackId) {
        int[] f = table.framesOf(stackId);
        for (int id : f) {
            String fr = table.frameText(id);
            if (startsAny(fr, JDK_PREFIXES) || startsAny(fr, JDBC) || startsAny(fr, HTTP_CLIENT) || startsAny(fr, POOL_LIBS)) continue;
            return fr;
        }
        return null;
    }

    static boolean isJucLockClass(String cls) { return cls != null && JUC_LOCK_CLASS.matcher(cls).find(); }

    private static boolean any(String frame, String[] sigs) {
        for (String s : sigs) if (frame.contains(s)) return true;
        return false;
    }

    private static boolean startsAny(String frame, String[] prefixes) {
        for (String s : prefixes) if (frame.startsWith(s)) return true;
        return false;
    }
}

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

    /** 커넥션 풀에서 커넥션이 돌아오기를 기다리는 중. */
    private static final String[] CONN_POOL_WAIT = {
            "com.zaxxer.hikari.pool.HikariPool.getConnection(",
            "com.zaxxer.hikari.util.ConcurrentBag.borrow(",
            "org.apache.commons.pool2.impl.GenericObjectPool.borrowObject(",
            "org.apache.commons.pool.impl.GenericObjectPool.borrowObject(",
            "org.apache.tomcat.dbcp.pool2.impl.GenericObjectPool.borrowObject(",
            "org.apache.tomcat.jdbc.pool.ConnectionPool.borrowConnection(",
            "oracle.ucp.common.UniversalConnectionPoolImpl.borrowConnection",
            "weblogic.common.resourcepool.ResourcePoolImpl.reserveResource",
            "com.mchange.v2.resourcepool.BasicResourcePool.awaitAvailable(",
            "org.apache.http.pool.AbstractConnPool.getPoolEntryBlocking(",
            "jeus.jdbc.connectionpool.ConnectionPoolImpl.getConnection(",
    };

    private static final String[] JDBC = {
            "oracle.jdbc.", "com.mysql.", "org.mariadb.jdbc.", "org.postgresql.", "com.microsoft.sqlserver.jdbc.",
            "com.ibm.db2.jcc.", "com.tmax.tibero.jdbc.", "com.sybase.jdbc", "net.sourceforge.jtds.", "cubrid.jdbc.",
            "Altibase.jdbc.", "org.h2.jdbc", "com.edb.", "oracle.net.ns.",
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
    }

    static Traits traitsOf(StackTable table, int stackId) {
        Traits t = new Traits();
        int[] f = table.framesOf(stackId);
        for (int i = 0; i < f.length; i++) {
            String fr = table.frameText(f[i]);
            if (i == 0 && (fr.startsWith("java.lang.Thread.sleep(") || fr.startsWith("java.lang.Thread.sleep0(")
                    || fr.startsWith("java.lang.Thread.sleepNanos"))) t.sleep = true;
            if (i < 3 && fr.startsWith("java.lang.Thread.sleep")) t.sleep = true;
            if (i < IDLE_DEPTH && !t.idle && any(fr, IDLE)) t.idle = true;
            if (i < IDLE_DEPTH && !t.listen && any(fr, LISTEN)) t.listen = true;
            if (i < IDLE_DEPTH && !t.socketRead && any(fr, SOCKET_READ)) t.socketRead = true;
            if (i < WAIT_DEPTH && !t.connPoolWait && any(fr, CONN_POOL_WAIT)) t.connPoolWait = true;
            if (!t.jdbc && startsAny(fr, JDBC)) t.jdbc = true;
            if (!t.httpClient && startsAny(fr, HTTP_CLIENT)) t.httpClient = true;
            if (!t.servlet && any(fr, SERVLET)) t.servlet = true;
            if (!t.webPool && any(fr, WEB_POOL)) t.webPool = true;
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

    static boolean isWorkerStem(String stem) { return WORKER_STEM.matcher(stem).find(); }

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

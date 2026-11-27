package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.ThreadDumpResult.Finding;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpParser.LockOp;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpParser.ParsedSnapshot;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpParser.ParsedThread;
import com.heapdump.analyzer.util.OomDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파싱된 스냅샷 묶음 → {@link ThreadDumpResult}. 소견은 스레드 덤프 글자만으로 확실한 것만 낸다.
 *
 * <h3>지켜야 하는 불변식</h3>
 * <ul>
 *   <li>{@code Object.wait()} 중인 스레드도 {@code - locked <X>} 를 찍는다 — 같은 스택에 {@code waiting on <X>} 가 있으면
 *       <b>소유자가 아니다</b>(모니터를 놓고 기다리는 중).</li>
 *   <li>{@code RUNNABLE} 이라도 꼭대기가 소켓 읽기·accept·select 면 CPU 를 쓰는 것이 아니다.</li>
 *   <li>스레드 동일성은 {@code #n}+{@code tid} 다. 이름은 키가 아니다(WebLogic 은 상태에 따라 이름을 바꾼다).</li>
 *   <li>스냅샷 사이에 JVM 이 재기동됐으면 비교하지 않는다 — 비교 규칙은 같은 실행(run) 안에서만 돈다.</li>
 *   <li>규칙 하나의 예외가 분석 전체를 죽이지 않는다 — {@link #runRule} 이 격리하고 {@code RULE_FAILED} 로 남긴다.</li>
 * </ul>
 */
final class ThreadDumpAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDumpAnalyzer.class);

    static final int STACK_DEPTH = 64;
    static final int TRACKS_CAP = 20_000;
    static final int FINDING_TRACKS = 20;
    static final int LOCKS_CAP = 200;
    static final int WAITERS_CAP = 50;
    static final int STUCK_CAP = 500;

    // ── 규칙 임계값(2.7.4 — 한 곳에 모은다. 런타임 설정은 없다: threaddump.* 는 @Value 전용) ──
    /** 소견 규칙 세대 — {@code meta.ruleset}. 근거 줄·원인 연결이 생긴 2.7.4 가 2 다. */
    static final int RULESET = 2;
    /** "같은 스택" 을 정체로 보는 최소 구간 — 스냅샷 3개 이상이거나, 2개라도 30초 이상. */
    private static final int STUCK_MIN_SNAPSHOTS = 3;
    private static final double STUCK_MIN_SEC = 30;
    /** 이보다 짧게 관측된 "같은 스택" 은 지나가는 작업일 수 있다 — 정체 소견을 High 로 올리지 않는다. */
    private static final double SHORT_OBSERVATION_SEC = 10;
    private static final double HOT_CPU_RATIO = 0.8;
    private static final double HOT_CPU_MIN_GAP_SEC = 2;
    /** 스냅샷 1개일 때 생성 이후 평균 CPU 를 볼 최소 경과 — 갓 만들어진 스레드의 평균은 뜻이 없다. */
    private static final double LIFETIME_CPU_MIN_ELAPSED_SEC = 30;
    /** 락 경합의 절대 상한 — 스레드가 아주 많은 JVM 에서 비율 기준만으로 놓치지 않게. */
    private static final int LOCK_HIGH_ABS = 100, LOCK_MEDIUM_ABS = 30;
    private static final int POOL_MIN_SIZE = 10, POOL_NEAR_FULL_MIN_SIZE = 20;
    private static final double POOL_NEAR_FULL_IDLE_RATIO = 0.05;
    private static final int IO_CLUSTER_MIN = 5;
    private static final double IO_CLUSTER_RATIO = 0.20;
    /** 대상을 모르는 소켓 대기는 같은 스택이 이만큼 묶여야 "집중" 으로 센다(요청 처리 풀 스레드는 무조건 센다). */
    private static final int SOCKET_GROUP_MIN = 5;
    private static final int CONNECT_WAIT_MIN = 3, CONNECT_WAIT_HIGH = 10;
    private static final int BASIS_CAP = 8, FRAME_CLIP = 140, VERDICT_POINTS = 4;

    private static final Pattern OOM_MSG = Pattern.compile("java\\.lang\\.OutOfMemoryError(?::\\s*(.*))?");
    private static final Pattern VM_VERSION = Pattern.compile("VM \\((\\d+)[.+\\-]");
    private static final Pattern LEGACY_VERSION = Pattern.compile("VM \\(\\d+\\.\\d+-b\\d+");

    private static final String[] SEVERITY = {"Info", "Low", "Medium", "High", "Critical"};

    // ── 입력 ────────────────────────────────────────────────────
    private final List<ParsedSnapshot> snaps;
    private final StackTable table;
    private final ThreadDumpResult.Diagnostics diag;
    private final Runnable checkpoint;
    private final int S;
    private final int snapshotsSeen;
    private final boolean heapDerived;

    // ── 중간 상태 ───────────────────────────────────────────────
    private final ThreadDumpResult r = new ThreadDumpResult();
    private int[] run;
    /** 실행(run) 시작 기준 초. 모르면 NaN. */
    private double[] relSec;
    private final List<TrackB> tracks = new ArrayList<>();
    /** ParsedThread → 트랙. 동일성 비교라 IdentityHashMap 대신 스냅샷별 배열을 쓴다. */
    private TrackB[][] trackOf;
    private ThreadDumpRules.Traits[] traits;
    private List<List<LockB>> locks;
    private final List<DeadlockB> deadlocks = new ArrayList<>();
    private final Set<TrackB> deadlocked = new HashSet<>();
    private final List<StuckB> stuck = new ArrayList<>();
    private final Map<String, PoolB> pools = new LinkedHashMap<>();
    private int focus;
    private boolean anyOwnsInfo;
    private boolean anyJucWaiter;

    private static final class TrackB {
        int idx;
        int runIdx;
        ParsedThread[] at;
        int first = -1, last = -1;
        boolean sys;
        PoolB pool;
        ParsedThread any() { return at[first]; }
    }

    private static final class LockB {
        String addr;
        String cls;
        boolean juc;
        TrackB owner;
        final List<TrackB> waiters = new ArrayList<>();
        TrackB rootBlocker;
    }

    private static final class DeadlockB {
        int snap;
        String source = "graph";
        List<TrackB> cycle = new ArrayList<>();
        List<String[]> chain = new ArrayList<>();   // {name, addr, cls, heldByName}
        List<String> raw = new ArrayList<>();
        int seenIn = 1;
        int lastSeen;
        String key;
    }

    private static final class StuckB {
        TrackB t;
        int stackId;
        int from, to;
        Double durationSec;
        String category;
        Double cpuDeltaMs;
    }

    private static final class PoolB {
        String stem;
        boolean worker;
        int[] size, idle;
        int idx = -1;
        int max() { int m = 0; for (int v : size) m = Math.max(m, v); return m; }
    }

    ThreadDumpAnalyzer(List<ParsedSnapshot> snaps, int snapshotsSeen, StackTable table, ThreadDumpResult.Diagnostics diag, Runnable checkpoint) {
        this(snaps, snapshotsSeen, table, diag, checkpoint, false);
    }

    /** @param heapDerived 힙 덤프의 MAT 산출물에서 만든 입력 — 한 시점·락 줄 없음을 전제로 진단 문구를 바꾼다 */
    ThreadDumpAnalyzer(List<ParsedSnapshot> snaps, int snapshotsSeen, StackTable table, ThreadDumpResult.Diagnostics diag, Runnable checkpoint,
                       boolean heapDerived) {
        this.heapDerived = heapDerived;
        this.snapshotsSeen = snapshotsSeen;
        this.snaps = snaps;
        this.table = table;
        this.diag = diag;
        this.checkpoint = checkpoint == null ? () -> { } : checkpoint;
        this.S = snaps.size();
    }

    ThreadDumpResult analyze() {
        r.setDiagnostics(diag);
        traits = new ThreadDumpRules.Traits[table.stackCount()];
        // 골격 — 여기가 실패하면 결과를 만들 수 없으므로 격리하지 않는다
        assignRuns();
        buildTracks();
        estimateTimes();
        focus = 0;
        for (int i = 0; i < S; i++) if (!snaps.get(i).threads.isEmpty()) focus = i;
        ThreadDumpResult.Meta meta = r.getMeta();
        meta.setSnapshotsSeen(snapshotsSeen);
        meta.setSnapshotsParsed(S);
        meta.setRuns(S == 0 ? 1 : run[S - 1] + 1);
        meta.setFocusSnapshot(focus);
        meta.setRuleset(RULESET);
        for (ParsedSnapshot s : snaps) if (s.vmLine != null) { meta.setJvmVersionLine(s.vmLine); break; }
        meta.setJdkMajor(jdkMajor(meta.getJvmVersionLine()));
        buildSnapshotStats();
        buildPools();
        buildLocks();

        runRule("deadlock", this::ruleDeadlock);
        runRule("stuck", this::computeStuck);
        runRule("lock-contention", this::ruleLockContention);
        runRule("blocked-ratio", this::ruleBlockedRatio);
        runRule("stuck-threads", this::ruleStuckThreads);
        runRule("cpu", this::ruleCpu);
        runRule("pool-no-idle", this::rulePoolNoIdle);
        runRule("conn-pool-wait", this::ruleConnPoolWait);
        runRule("io-wait", this::ruleIoWait);
        runRule("connect-wait", this::ruleConnectWait);
        runRule("thread-count", this::ruleThreadCount);
        runRule("executor-leak", this::ruleExecutorLeak);
        runRule("finalizer", this::ruleFinalizer);
        runRule("weblogic-stuck", this::ruleWeblogicStuck);
        runRule("oom-in-log", this::ruleOomInLog);
        // 원인 연결은 문제 규칙이 모두 끝난 뒤, 진단류(파싱·상한 안내)가 붙기 전에 돈다 — 종합 진단에 진단류가 섞이지 않게
        runRule("correlate", this::correlate);
        // 미들웨어(WAS)는 판정하지 않는다(2.7.1) — 스레드 덤프에는 System Properties 가 없어 스택 패키지 이름으로
        // 짐작할 수밖에 없고, 확정되지 않은 값을 화면·PDF·AI 에 싣지 않는다. meta.middleware 는 옛 결과 호환용으로만 남는다.
        runRule("diagnostics", this::ruleDiagnostics);

        buildResultBody();
        runRule("views", this::buildViews);
        finishKpi();
        return r;
    }

    /**
     * 규칙 하나를 격리해 돌린다. 시간 초과·취소({@link ThreadDumpException})는 그대로 올리고,
     * 그 밖의 예외는 {@code RULE_FAILED} 진단으로 남긴 뒤 다음 규칙으로 넘어간다.
     */
    private void runRule(String name, Runnable body) {
        checkpoint.run();
        try {
            body.run();
        } catch (ThreadDumpException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            diag.getRuleFailures().add(new ThreadDumpResult.RuleFailure(name, e.getClass().getSimpleName() + ": " + e.getMessage()));
            logger.warn("[ThreadDump] action=rule-failed rule={} — {}", name, e.toString(), e);
        }
    }

    // ══ 골격 ════════════════════════════════════════════════════

    private static String identity(ParsedThread t) {
        return (t.num >= 0 ? Long.toString(t.num) : "") + "@" + t.tid;
    }

    /** VM 버전 줄이 바뀌거나 앞 스냅샷과 겹치는 스레드가 하나도 없으면 JVM 이 재기동된 것으로 본다. */
    private void assignRuns() {
        run = new int[S];
        Set<String> prev = null;
        String prevVm = null;
        for (int i = 0; i < S; i++) {
            ParsedSnapshot s = snaps.get(i);
            Set<String> ids = new HashSet<>(s.threads.size() * 2);
            for (ParsedThread t : s.threads) ids.add(identity(t));
            boolean same = true;
            if (i > 0) {
                if (prevVm != null && s.vmLine != null && !prevVm.equals(s.vmLine)) same = false;
                else if (!prev.isEmpty() && !ids.isEmpty()) {
                    boolean overlap = false;
                    for (String id : ids) if (prev.contains(id)) { overlap = true; break; }
                    same = overlap;
                }
            }
            run[i] = i == 0 ? 0 : (same ? run[i - 1] : run[i - 1] + 1);
            if (!ids.isEmpty()) prev = ids;
            else if (prev == null) prev = ids;
            if (s.vmLine != null) prevVm = s.vmLine;
        }
    }

    private void buildTracks() {
        Map<String, TrackB> byKey = new HashMap<>();
        trackOf = new TrackB[S][];
        for (int i = 0; i < S; i++) {
            List<ParsedThread> th = snaps.get(i).threads;
            trackOf[i] = new TrackB[th.size()];
            for (int j = 0; j < th.size(); j++) {
                ParsedThread t = th.get(j);
                String key = run[i] + "|" + identity(t);
                TrackB tb = byKey.get(key);
                if (tb == null || tb.at[i] != null) {
                    // 같은 스냅샷에 같은 식별자가 두 번 나오면(손상된 덤프) 별개 트랙으로 둔다
                    tb = new TrackB();
                    tb.idx = tracks.size();
                    tb.runIdx = run[i];
                    tb.at = new ParsedThread[S];
                    tb.first = i;
                    tracks.add(tb);
                    byKey.put(key, tb);
                }
                tb.at[i] = t;
                tb.last = i;
                trackOf[i][j] = tb;
            }
        }
        for (TrackB tb : tracks) {
            ParsedThread any = tb.any();
            boolean java = false;
            for (ParsedThread p : tb.at) if (p != null && p.javaThread()) { java = true; break; }
            tb.sys = ThreadDumpRules.isSystemThread(any.name, java);
        }
    }

    /** 스냅샷 간격 — 타임스탬프가 있으면 그것, 없으면 JDK 11+ {@code elapsed=} 차이의 중앙값. 파일 mtime 은 쓰지 않는다. */
    private void estimateTimes() {
        relSec = new double[S];
        Arrays.fill(relSec, Double.NaN);
        boolean usedTs = false, usedElapsed = false;
        for (int i = 0; i < S; i++) {
            if (i == 0 || run[i] != run[i - 1]) { relSec[i] = 0; continue; }
            ParsedSnapshot a = snaps.get(i - 1), b = snaps.get(i);
            Double gap = null;
            if (a.ts != null && b.ts != null && b.ts >= a.ts) { gap = (b.ts - a.ts) / 1000.0; usedTs = true; }
            else {
                List<Double> diffs = new ArrayList<>();
                for (int j = 0; j < trackOf[i].length && diffs.size() < 200; j++) {
                    TrackB tb = trackOf[i][j];
                    ParsedThread pa = tb.at[i - 1], pb = tb.at[i];
                    if (pa != null && pb != null && !Double.isNaN(pa.elapsedSec) && !Double.isNaN(pb.elapsedSec)
                            && pb.elapsedSec >= pa.elapsedSec) diffs.add(pb.elapsedSec - pa.elapsedSec);
                }
                if (!diffs.isEmpty()) {
                    diffs.sort(Comparator.naturalOrder());
                    gap = diffs.get(diffs.size() / 2);
                    usedElapsed = true;
                }
            }
            relSec[i] = gap == null || Double.isNaN(relSec[i - 1]) ? Double.NaN : relSec[i - 1] + gap;
        }
        ThreadDumpResult.Meta m = r.getMeta();
        m.setIntervalSource(usedTs ? "timestamp" : usedElapsed ? "elapsed" : "none");
        Long first = null, last = null;
        for (ParsedSnapshot s : snaps) if (s.ts != null) { if (first == null) first = s.ts; last = s.ts; }
        m.setTimeSource(first != null ? "absolute" : "none");
        m.setFirstTs(first);
        m.setLastTs(last);
        if (S > 0 && run[S - 1] == run[0] && !Double.isNaN(relSec[S - 1]) && S > 1) m.setSpanSec(round1(relSec[S - 1]));
        else if (first != null && last != null && S > 1) m.setSpanSec(round1((last - first) / 1000.0));
    }

    /** 두 스냅샷 사이 초. 다른 실행이거나 모르면 null. */
    private Double gapSec(int i, int j) {
        if (run[i] != run[j] || Double.isNaN(relSec[i]) || Double.isNaN(relSec[j])) return null;
        return relSec[j] - relSec[i];
    }

    private ThreadDumpRules.Traits tr(ParsedThread t) {
        if (t == null || t.stackId < 0) return NO_TRAITS;
        ThreadDumpRules.Traits x = traits[t.stackId];
        if (x == null) traits[t.stackId] = x = ThreadDumpRules.traitsOf(table, t.stackId);
        return x;
    }

    private static final ThreadDumpRules.Traits NO_TRAITS = new ThreadDumpRules.Traits();

    private boolean isIdle(ParsedThread t) {
        if (t == null) return false;
        if (t.state != ThreadDumpResult.ST_WAITING && t.state != ThreadDumpResult.ST_TIMED) return false;
        return tr(t).idle;
    }

    private void buildSnapshotStats() {
        for (int i = 0; i < S; i++) {
            ParsedSnapshot ps = snaps.get(i);
            ThreadDumpResult.Snapshot s = new ThreadDumpResult.Snapshot();
            s.setIdx(i);
            s.setRun(run[i]);
            s.setTs(ps.ts);
            s.setTsText(ps.tsText);
            s.setFirstLine(ps.firstLine);
            s.setLastLine(ps.lastLine);
            s.setJniRefs(ps.jniRefs);
            s.setComplete(ps.complete);
            s.setIncompleteReason(ps.incompleteReason);
            s.setHeaderless(ps.headerless);
            s.setSkippedThreads(ps.skippedThreads);
            s.setFile(ps.file);
            Map<String, Integer> st = s.getStates();
            for (String k : new String[]{"RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "NEW", "TERMINATED", "VM", "UNKNOWN"}) st.put(k, 0);
            int java = 0, daemon = 0;
            for (ParsedThread t : ps.threads) {
                st.merge(stateName(t.state), 1, Integer::sum);
                if (t.javaThread()) java++;
                if (t.daemon) daemon++;
            }
            st.values().removeIf(v -> v == 0);
            s.setThreads(ps.threads.size());
            s.setJavaThreads(java);
            s.setDaemon(daemon);
            s.setBlocked(st.getOrDefault("BLOCKED", 0));
            r.getSnapshots().add(s);
        }
    }

    private void buildPools() {
        for (TrackB tb : tracks) {
            if (tb.sys) continue;
            String stem = ThreadDumpRules.stem(tb.any().name);
            if (stem.indexOf('#') < 0) continue;       // 번호가 없는 이름은 풀이 아니다
            PoolB p = pools.get(stem);
            if (p == null) {
                p = new PoolB();
                p.stem = stem;
                p.size = new int[S];
                p.idle = new int[S];
                p.worker = ThreadDumpRules.isWorkerStem(stem);
                pools.put(stem, p);
            }
            tb.pool = p;
            for (int i = tb.first; i <= tb.last; i++) {
                ParsedThread t = tb.at[i];
                if (t == null) continue;
                p.size[i]++;
                if (isIdle(t)) p.idle[i]++;
                // 서블릿을 실행 중이거나 WAS 의 요청 실행기 위에서 도는 스레드면 이름과 무관하게 요청 처리 풀이다(유휴 스레드도 실행기 프레임은 있다)
                // WebLogic Muxer 는 바닥에 실행기 프레임(ExecuteThread.run)이 있어도 수신 스레드다 — 요청 풀로 올리지 않는다
                if (!p.worker && !ThreadDumpRules.isMuxerName(t.name) && (tr(t).servlet || tr(t).webPool)) p.worker = true;
            }
        }
        pools.values().removeIf(p -> p.max() < 2);
        for (TrackB tb : tracks) if (tb.pool != null && !pools.containsKey(tb.pool.stem)) tb.pool = null;
        // 다음 요청을 기다리며 소켓을 읽는 요청 풀 스레드도 유휴로 센다(연결당 스레드 방식의 유휴 — 2.7.4).
        // 요청 풀 여부가 위에서 확정된 뒤라야 판정할 수 있어 두 번째 순회로 둔다.
        for (TrackB tb : tracks) {
            if (tb.pool == null || !tb.pool.worker) continue;
            for (int i = tb.first; i <= tb.last; i++) {
                ParsedThread t = tb.at[i];
                if (t != null && !isIdle(t) && activityOf(tb, t) == Activity.REQUEST_READ) tb.pool.idle[i]++;
            }
        }
    }

    // ══ 락 그래프 ═══════════════════════════════════════════════

    private void buildLocks() {
        locks = new ArrayList<>(S);
        for (int i = 0; i < S; i++) {
            Map<String, LockB> byAddr = new LinkedHashMap<>();
            List<ParsedThread> th = snaps.get(i).threads;
            for (int j = 0; j < th.size(); j++) {
                ParsedThread t = th.get(j);
                if (t.ops == null) continue;
                TrackB tb = trackOf[i][j];
                Set<String> waitingOn = null;
                for (LockOp op : t.ops) {
                    if (op.verb().equals("waiting on")) { if (waitingOn == null) waitingOn = new HashSet<>(); waitingOn.add(op.addr()); }
                }
                boolean waiterTaken = false;
                for (LockOp op : t.ops) {
                    if (!op.addr().startsWith("0x")) continue;     // <no object reference available> 등
                    switch (op.verb()) {
                        case "locked" -> {
                            if (waitingOn != null && waitingOn.contains(op.addr())) break;   // wait() 중 — 소유가 아니다
                            LockB l = lock(byAddr, op, false);
                            if (l.owner == null) l.owner = tb;
                        }
                        case "owns" -> {
                            anyOwnsInfo = true;
                            LockB l = lock(byAddr, op, true);
                            if (l.owner == null) l.owner = tb;
                        }
                        case "waiting to lock", "waiting to re-lock in wait()" -> {
                            if (waiterTaken) break;
                            lock(byAddr, op, false).waiters.add(tb);
                            waiterTaken = true;
                        }
                        case "parking to wait for" -> {
                            if (waiterTaken || !ThreadDumpRules.isJucLockClass(op.cls())) break;
                            anyJucWaiter = true;
                            lock(byAddr, op, true).waiters.add(tb);
                            waiterTaken = true;
                        }
                        default -> { /* waiting on · eliminated — 그래프에 넣지 않는다 */ }
                    }
                }
            }
            List<LockB> list = new ArrayList<>(byAddr.values());
            // 대기 관계: 스레드 → 그 스레드가 기다리는 락
            Map<TrackB, LockB> waitFor = new HashMap<>();
            for (LockB l : list) for (TrackB w : l.waiters) waitFor.putIfAbsent(w, l);
            for (LockB l : list) {
                if (l.waiters.isEmpty() || l.owner == null) continue;
                // 소유자 사슬을 따라가 끝(더는 아무것도 기다리지 않는 스레드)을 찾는다 — 반복형, 순환이면 멈춘다
                TrackB cur = l.owner;
                Set<TrackB> seen = new HashSet<>();
                while (seen.add(cur)) {
                    LockB next = waitFor.get(cur);
                    if (next == null || next.owner == null) break;
                    cur = next.owner;
                }
                l.rootBlocker = cur;
            }
            locks.add(list);
        }
    }

    private static LockB lock(Map<String, LockB> byAddr, LockOp op, boolean juc) {
        LockB l = byAddr.get(op.addr());
        if (l == null) {
            l = new LockB();
            l.addr = op.addr();
            l.cls = op.cls();
            l.juc = juc;
            byAddr.put(op.addr(), l);
        } else if (l.cls == null) l.cls = op.cls();
        return l;
    }

    // ══ 활동 분류 ═══════════════════════════════════════════════

    /**
     * 스레드가 그 순간 하고 있는 일(2.7.4) — 정체 분류·풀 분해·원인 연결이 <b>같은 분류</b>를 쓴다.
     * 판정 순서가 곧 규칙이다: 더 특정적인 것(연결·DB·외부 호출)을 먼저 보고, 그다음에야 "요청 수신 대기" 로 정상 처리한다.
     */
    enum Activity {
        LOCK_WAIT("락 대기"), CONN_POOL_WAIT("커넥션 풀 대기"), DB_WAIT("DB 응답 대기"), REMOTE_WAIT("외부 호출 응답 대기"),
        SOCKET_WAIT("소켓 응답 대기"), CONNECT_WAIT("연결 수립 대기"), REQUEST_READ("요청 수신 대기"), RUNNING("실행 중"),
        SLEEP("sleep"), WAIT_OTHER("대기"), IDLE("유휴"), LISTEN("수신 대기"), NONE("");

        final String ko;
        Activity(String ko) { this.ko = ko; }
    }

    /** {@link #buildPools} 뒤에만 부를 것 — "요청 수신 대기" 는 요청 처리 풀 여부({@code pool.worker})를 본다. */
    private Activity activityOf(TrackB tb, ParsedThread t) {
        if (t == null || !t.javaThread()) return Activity.NONE;
        if (ThreadDumpRules.isMuxerName(t.name)) return Activity.LISTEN;
        ThreadDumpRules.Traits x = tr(t);
        switch (t.state) {
            case ThreadDumpResult.ST_BLOCKED:
                // 풀의 synchronized borrow 에 들어가려고 막힌 스레드도 커넥션을 기다리는 것이다
                return x.connPoolWait ? Activity.CONN_POOL_WAIT : Activity.LOCK_WAIT;
            case ThreadDumpResult.ST_RUNNABLE:
                if (x.listen) return Activity.LISTEN;
                if (x.connect || x.dns) return Activity.CONNECT_WAIT;
                if (x.socketRead) {
                    if (x.jdbc) return Activity.DB_WAIT;
                    if (x.httpClient) return Activity.REMOTE_WAIT;
                    if (x.rmiIdle) return Activity.REQUEST_READ;
                    boolean worker = tb != null && tb.pool != null && tb.pool.worker;
                    // 요청 처리 풀 스레드가 서블릿·필터에 들어가기 전에 소켓을 읽는다 = 다음 요청을 기다린다(keep-alive·연결당 스레드 방식)
                    if (worker && !x.servlet && !x.filterChain) return Activity.REQUEST_READ;
                    return Activity.SOCKET_WAIT;
                }
                return Activity.RUNNING;
            case ThreadDumpResult.ST_WAITING:
            case ThreadDumpResult.ST_TIMED:
                if (x.connPoolWait) return Activity.CONN_POOL_WAIT;
                if (x.idle) return Activity.IDLE;
                if (t.ops != null) {
                    for (LockOp op : t.ops) {
                        if (op.verb().equals("parking to wait for") && ThreadDumpRules.isJucLockClass(op.cls())) return Activity.LOCK_WAIT;
                    }
                }
                return x.sleep ? Activity.SLEEP : Activity.WAIT_OTHER;
            default:
                return Activity.NONE;
        }
    }

    /** 일거리를 기다리는 중 — 유휴 시그니처이거나 다음 요청을 기다리며 소켓을 읽는 중. */
    private boolean isFree(TrackB tb, ParsedThread t) {
        return isIdle(t) || activityOf(tb, t) == Activity.REQUEST_READ;
    }

    /** "작업 중" 으로 세는 활동 — 유휴·수신 대기·요청 수신 대기는 뺀다. */
    private static boolean working(Activity a) {
        return a != Activity.NONE && a != Activity.IDLE && a != Activity.LISTEN && a != Activity.REQUEST_READ;
    }

    /**
     * 같은 스택에 머무는 것이 "정체"로 볼 만한가. 수신 대기·유휴·요청 수신 대기·풀 밖 스레드의 평범한 대기는 null(정상).
     * 반환 문자열은 결과 JSON 의 {@code Stuck.category} 다 — 화면·PDF 가 이 값으로 표시명을 고르므로 <b>바꾸지 말 것</b>.
     */
    private String stuckCategory(TrackB tb, ParsedThread t) {
        boolean worker = tb.pool != null && tb.pool.worker;
        return switch (activityOf(tb, t)) {
            case LOCK_WAIT -> t.state == ThreadDumpResult.ST_BLOCKED ? "blocked" : "lock-wait";
            case CONN_POOL_WAIT -> "pool-wait";
            case DB_WAIT, REMOTE_WAIT, SOCKET_WAIT, CONNECT_WAIT -> "io";
            case RUNNING -> "running";
            case SLEEP -> worker ? "sleeping" : null;        // 풀 밖 스레드의 대기·sleep 은 흔한 정상 동작이다
            case WAIT_OTHER -> worker ? "waiting" : null;
            default -> null;
        };
    }

    private static String categoryKo(String c) {
        return switch (c) {
            case "blocked" -> "락 대기(BLOCKED)";
            case "lock-wait" -> "락 대기(ReentrantLock)";
            case "pool-wait" -> "커넥션 풀 대기";
            case "io" -> "I/O 응답 대기";
            case "running" -> "실행 중";
            case "sleeping" -> "sleep";
            case "waiting" -> "대기";
            default -> c;
        };
    }

    // ══ 규칙 ════════════════════════════════════════════════════

    /** 교착 — JVM 이 보고한 섹션 + 락 대기 그래프의 순환. 같은 스레드 집합이면 하나로 친다. */
    private void ruleDeadlock() {
        Map<String, DeadlockB> unique = new LinkedHashMap<>();
        for (int i = 0; i < S; i++) {
            Set<String> inSnap = new HashSet<>();
            Map<TrackB, LockB> waitFor = new HashMap<>();
            for (LockB l : locks.get(i)) for (TrackB w : l.waiters) waitFor.putIfAbsent(w, l);
            // 각 스레드의 나가는 간선은 많아야 하나(기다리는 락의 소유자) — 포인터를 따라가며 순환을 찾는다
            Map<TrackB, Integer> color = new HashMap<>();
            for (TrackB start : waitFor.keySet()) {
                if (color.containsKey(start)) continue;
                List<TrackB> path = new ArrayList<>();
                TrackB cur = start;
                while (cur != null && !color.containsKey(cur)) {
                    color.put(cur, 1);
                    path.add(cur);
                    LockB l = waitFor.get(cur);
                    cur = l == null ? null : l.owner;
                }
                if (cur != null && color.get(cur) == 1) {
                    int at = path.indexOf(cur);
                    if (at >= 0) {
                        DeadlockB d = new DeadlockB();
                        d.snap = i;
                        d.cycle = new ArrayList<>(path.subList(at, path.size()));
                        for (TrackB t : d.cycle) {
                            LockB l = waitFor.get(t);
                            d.chain.add(new String[]{t.at[i].name, l.addr, l.cls, l.owner.at[i].name});
                        }
                        d.key = cycleKey(d.cycle);
                        noteDeadlock(unique, d, inSnap);
                    }
                }
                for (TrackB t : path) color.put(t, 2);
            }
            // JVM 보고 — 그래프에서 못 찾은 것만 추가하고, 찾은 것은 출처를 jvm 으로 올린다
            for (ThreadDumpParser.JvmDeadlock jd : snaps.get(i).jvmDeadlocks) {
                List<TrackB> cyc = new ArrayList<>();
                for (String[] c : jd.chain) {
                    TrackB t = findByName(i, c[0]);
                    if (t != null && !cyc.contains(t)) cyc.add(t);
                }
                if (cyc.isEmpty() && jd.chain.isEmpty()) continue;
                String key = cyc.size() == jd.chain.size() && !cyc.isEmpty() ? cycleKey(cyc) : "jvm:" + run[i] + ":" + nameKey(jd.chain);
                DeadlockB known = unique.get(key);
                if (known != null) {
                    known.source = "jvm";
                    if (known.raw.isEmpty()) known.raw = jd.raw;
                    if (inSnap.add(key) && known.lastSeen != i) { known.seenIn++; known.lastSeen = i; }
                    continue;
                }
                DeadlockB d = new DeadlockB();
                d.snap = i;
                d.source = "jvm";
                d.cycle = cyc;
                d.chain = jd.chain;
                d.raw = jd.raw;
                d.key = key;
                noteDeadlock(unique, d, inSnap);
            }
            r.getSnapshots().get(i).setDeadlocks(inSnap.size());
        }
        deadlocks.addAll(unique.values());
        for (DeadlockB d : deadlocks) deadlocked.addAll(d.cycle);
        if (deadlocks.isEmpty()) return;

        Finding f = finding("DEADLOCK", "Critical", "교착 상태(Deadlock) " + deadlocks.size() + "건");
        DeadlockB first = deadlocks.get(0);
        StringBuilder sb = new StringBuilder();
        for (String[] c : first.chain) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append('"').append(c[0]).append("\" 가 \"").append(c[3]).append("\" 가 쥔 락을 대기");
        }
        f.setDetail("스레드들이 서로가 쥔 락을 기다려 영원히 진행하지 못합니다. " + sb
                + (deadlocks.size() > 1 ? " 외 " + (deadlocks.size() - 1) + "건." : "."));
        f.setAdvice("교착은 스스로 풀리지 않습니다. 해당 JVM 을 재기동해 복구하고, 원인 코드는 락 획득 순서를 통일하거나 tryLock(타임아웃)으로 바꾸세요.");
        f.setSnapshotIdx(first.snap);
        List<Map<String, Object>> ev = new ArrayList<>();
        int jvm = 0;
        for (DeadlockB d : deadlocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            for (String[] c : d.chain) names.add(c[0]);
            m.put("threads", names);
            m.put("source", d.source);
            m.put("seenInSnapshots", d.seenIn);
            ev.add(m);
            if ("jvm".equals(d.source)) jvm++;
            for (TrackB t : d.cycle) addTrack(f, t);
        }
        f.getEvidence().put("deadlocks", ev);

        basis(f, jvm == deadlocks.size() ? "JVM 이 직접 보고한 교착입니다(Found one Java-level deadlock)"
                : jvm > 0 ? "JVM 보고 " + jvm + "건 + 락 대기 그래프의 순환으로 찾은 " + (deadlocks.size() - jvm) + "건"
                : "JVM 보고 섹션은 없고 락 대기 그래프의 순환으로 확인했습니다");
        if (S > 1) {
            Double dur = gapSec(first.snap, first.lastSeen);
            basis(f, "스냅샷 " + S + "개 중 " + first.seenIn + "개에서 같은 교착이 보입니다"
                    + (dur != null && dur > 0 ? "(약 " + round1(dur) + "초 이상 지속)" : ""));
        }
        // 교착 스레드가 쥔 락 뒤에 막힌 스레드 — 교착이 풀리지 않는 한 함께 멈춘다
        int behind = 0;
        int at = Math.min(first.lastSeen, S - 1);
        for (LockB l : locks.get(at)) {
            if (l.rootBlocker == null || !deadlocked.contains(l.rootBlocker)) continue;
            for (TrackB w : l.waiters) if (!deadlocked.contains(w)) behind++;
        }
        f.getEvidence().put("blockedBehind", behind);
        if (behind > 0) basis(f, "교착 스레드가 쥔 락을 기다리는 스레드가 " + behind + "개 더 있습니다(교착이 풀리지 않는 한 함께 멈춥니다)");
    }

    private static void noteDeadlock(Map<String, DeadlockB> unique, DeadlockB d, Set<String> inSnap) {
        inSnap.add(d.key);
        DeadlockB known = unique.get(d.key);
        if (known == null) { d.lastSeen = d.snap; unique.put(d.key, d); }
        else if (known.lastSeen != d.snap) { known.seenIn++; known.lastSeen = d.snap; }
    }

    private static String cycleKey(List<TrackB> cycle) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (TrackB t : cycle) ids.add(t.idx);
        return ids.toString();
    }

    private static String nameKey(List<String[]> chain) {
        TreeSet<String> names = new TreeSet<>();
        for (String[] c : chain) names.add(c[0]);
        return names.toString();
    }

    private TrackB findByName(int snap, String name) {
        List<ParsedThread> th = snaps.get(snap).threads;
        for (int j = 0; j < th.size(); j++) if (th.get(j).name.equals(name)) return trackOf[snap][j];
        return null;
    }

    /** 같은 실행의 연속 스냅샷에서 같은 스택에 머문 구간(스레드별 최장)을 구한다. */
    private void computeStuck() {
        if (S < 2) return;
        for (TrackB tb : tracks) {
            if (tb.sys) continue;
            int bestFrom = -1, bestTo = -1;
            int from = -1;
            for (int i = tb.first; i <= tb.last + 1; i++) {
                ParsedThread t = i <= tb.last ? tb.at[i] : null;
                boolean cont = from >= 0 && t != null && t.stackId >= 0 && t.stackId == tb.at[from].stackId
                        && t.state == tb.at[from].state && stuckCategory(tb, t) != null;
                if (cont) continue;
                if (from >= 0 && i - 1 > from && (bestFrom < 0 || (i - 1 - from) > (bestTo - bestFrom))) { bestFrom = from; bestTo = i - 1; }
                from = (t != null && t.stackId >= 0 && stuckCategory(tb, t) != null) ? i : -1;
            }
            if (bestFrom < 0) continue;
            int span = bestTo - bestFrom + 1;
            Double dur = gapSec(bestFrom, bestTo);
            if (span < STUCK_MIN_SNAPSHOTS && !(dur != null && dur >= STUCK_MIN_SEC)) continue;
            StuckB s = new StuckB();
            s.t = tb;
            s.stackId = tb.at[bestFrom].stackId;
            s.from = bestFrom;
            s.to = bestTo;
            s.durationSec = dur == null ? null : round1(dur);
            s.category = stuckCategory(tb, tb.at[bestFrom]);
            double c0 = tb.at[bestFrom].cpuMs, c1 = tb.at[bestTo].cpuMs;
            if (!Double.isNaN(c0) && !Double.isNaN(c1) && c1 >= c0) s.cpuDeltaMs = round1(c1 - c0);
            stuck.add(s);
        }
        stuck.sort(Comparator.comparingInt((StuckB s) -> -(s.to - s.from)).thenComparingInt(s -> s.t.idx));
    }

    private void ruleLockContention() {
        // 락 주소별로 대기자가 가장 많았던 스냅샷을 대표로 삼는다
        Map<String, int[]> best = new LinkedHashMap<>();      // addr → {snap, waiters, persistSnaps}
        Map<String, LockB> bestLock = new HashMap<>();
        for (int i = 0; i < S; i++) {
            for (LockB l : locks.get(i)) {
                if (l.waiters.isEmpty()) continue;
                String key = run[i] + "|" + l.addr;
                int[] b = best.get(key);
                if (b == null) { best.put(key, new int[]{i, l.waiters.size(), 1}); bestLock.put(key, l); }
                else {
                    b[2]++;
                    if (l.waiters.size() > b[1]) { b[0] = i; b[1] = l.waiters.size(); bestLock.put(key, l); }
                }
            }
        }
        List<Map.Entry<String, int[]>> order = new ArrayList<>(best.entrySet());
        order.sort((a, b) -> b.getValue()[1] - a.getValue()[1]);
        int reported = 0;
        for (Map.Entry<String, int[]> e : order) {
            int snap = e.getValue()[0], waiters = e.getValue()[1], seen = e.getValue()[2];
            LockB l = bestLock.get(e.getKey());
            int java = Math.max(1, r.getSnapshots().get(snap).getJavaThreads());
            // 비율 기준에 절대 상한을 둔다 — 스레드 3,000개 JVM 에서 한 락에 100개가 막혀도 "10% 미만" 이라 지나치던 것(2.7.4)
            int level;
            if (waiters >= Math.min(Math.max(20, java * 0.25), LOCK_HIGH_ABS)) level = 3;
            else if (waiters >= Math.min(Math.max(5, java * 0.10), LOCK_MEDIUM_ABS)) level = 2;
            else continue;
            if (seen >= 2) level = Math.min(3, level + 1);
            if (reported++ >= 3) break;
            Finding f = finding("LOCK_CONTENTION", SEVERITY[level], "락 경합 — " + waiters + "개 스레드가 같은 락 대기");
            String ownerName = l.owner == null ? null : l.owner.at[snap].name;
            String rootName = l.rootBlocker == null ? null : l.rootBlocker.at[snap].name;
            f.setDetail("락 " + l.addr + " (" + shortClass(l.cls) + ") 을 " + waiters + "개 스레드가 기다립니다."
                    + (ownerName != null ? " 소유 스레드: \"" + ownerName + "\"." : " 소유 스레드는 덤프에서 확인되지 않습니다.")
                    + (rootName != null && !rootName.equals(ownerName) ? " 사슬의 끝은 \"" + rootName + "\" 입니다." : "")
                    + (seen >= 2 ? " " + seen + "개 스냅샷에 걸쳐 이어졌습니다." : ""));
            f.setAdvice("소유 스레드의 스택에서 락을 쥔 채 오래 걸리는 구간(I/O·원격 호출·큰 반복)을 찾아 임계 구역 밖으로 빼거나 락 범위를 좁히세요.");
            f.setSnapshotIdx(snap);
            f.getEvidence().put("lock", l.addr);
            f.getEvidence().put("lockClass", l.cls);
            f.getEvidence().put("waiters", waiters);
            f.getEvidence().put("owner", ownerName);
            f.getEvidence().put("rootBlocker", rootName);
            f.getEvidence().put("seenInSnapshots", seen);
            if (l.owner != null) {
                f.getEvidence().put("ownerTopFrame", table.top(l.owner.at[snap].stackId));
                addTrack(f, l.owner);
            }
            for (TrackB w : l.waiters) addTrack(f, w);

            // 근거 — 대기 지점·락 종류·소유자가 하는 일
            basis(f, "대기 " + waiters + "개 / Java 스레드 " + java + "개(" + pct((double) waiters / java) + ")");
            Map<String, Integer> sites = new LinkedHashMap<>();
            for (TrackB w : l.waiters) {
                ParsedThread wt = w.at[snap];
                if (wt != null && wt.stackId >= 0) sites.merge(ThreadDumpRules.waitSite(table, wt.stackId), 1, Integer::sum);
            }
            Map.Entry<String, Integer> site = top(sites);
            String kind = ThreadDumpRules.lockKind(site == null ? null : site.getKey(), l.cls);
            if (site != null && !site.getKey().isEmpty()) {
                basis(f, "대기 지점 " + clip(site.getKey(), FRAME_CLIP) + " ×" + site.getValue());
                f.getEvidence().put("waitSite", site.getKey());
            }
            if (kind != null) {
                basis(f, "락 종류: " + lockKindKo(kind));
                f.getEvidence().put("lockKind", kind);
                f.setAdvice(lockAdvice(kind));
            }
            if (l.owner != null) {
                Activity oa = activityOf(l.owner, l.owner.at[snap]);
                f.getEvidence().put("ownerActivity", oa.name());
                basis(f, "소유 스레드 \"" + ownerName + "\"" + (oa.ko.isEmpty() ? "" : " — " + oa.ko)
                        + " (" + clip(table.top(l.owner.at[snap].stackId), FRAME_CLIP) + ")");
            }
            if (l.rootBlocker != null && l.rootBlocker != l.owner) {
                Activity ra = activityOf(l.rootBlocker, l.rootBlocker.at[snap]);
                basis(f, "사슬 끝 \"" + rootName + "\"" + (ra.ko.isEmpty() ? "" : " — " + ra.ko));
            }
            lockHits.add(new LockHit(f, l, snap));

            // 소유자가 락을 쥔 채 같은 스택에 머무는가
            if (l.owner != null && waiters >= 3) {
                int same = 0, firstSame = -1, lastSame = -1;
                TrackB o = l.owner;
                int sid = o.at[snap].stackId;
                for (int i = 0; i < S; i++) {
                    if (run[i] != run[snap] || o.at[i] == null || o.at[i].stackId != sid || sid < 0) continue;
                    for (LockB x : locks.get(i)) {
                        if (x.addr.equals(l.addr) && x.owner == o && !x.waiters.isEmpty()) {
                            same++;
                            if (firstSame < 0) firstSame = i;
                            lastSame = i;
                            break;
                        }
                    }
                }
                if (same >= 2) {
                    Finding g = finding("LOCK_OWNER_STUCK", "High", "락을 쥔 스레드가 진행하지 못함");
                    g.setDetail("\"" + ownerName + "\" 가 락 " + l.addr + " 을 쥔 채 " + same + "개 스냅샷 동안 같은 스택("
                            + table.top(sid) + ")에 머물렀고, 그동안 " + waiters + "개 스레드가 대기했습니다.");
                    g.setAdvice("이 스레드가 기다리는 대상(외부 응답·sleep·다른 락)을 먼저 해소해야 대기 스레드가 풀립니다.");
                    g.setSnapshotIdx(snap);
                    g.getEvidence().put("owner", ownerName);
                    g.getEvidence().put("lock", l.addr);
                    g.getEvidence().put("snapshots", same);
                    g.getEvidence().put("waiters", waiters);
                    addTrack(g, o);
                    Double dur = gapSec(firstSame, lastSame);
                    if (dur != null) g.getEvidence().put("durationSec", round1(dur));
                    basis(g, same + "개 스냅샷 동안 같은 스택" + (dur != null ? "(약 " + round1(dur) + "초)" : "(간격은 알 수 없음)"));
                    Activity oa = activityOf(o, o.at[snap]);
                    if (!oa.ko.isEmpty()) basis(g, "소유 스레드가 하고 있는 일: " + oa.ko);
                    if (dur != null && dur < SHORT_OBSERVATION_SEC) basis(g, "관측 구간이 10초 미만입니다 — 일시적으로 오래 걸린 작업일 수 있으니 간격을 넓혀 다시 받아 확인하세요");
                    relate(f, g);
                }
            }
        }
    }

    private void ruleBlockedRatio() {
        int bestSnap = -1, bestBlocked = 0;
        double bestRatio = 0;
        for (int i = 0; i < S; i++) {
            ThreadDumpResult.Snapshot s = r.getSnapshots().get(i);
            if (s.getJavaThreads() == 0) continue;
            double ratio = (double) s.getBlocked() / s.getJavaThreads();
            if (s.getBlocked() >= 10 && ratio > bestRatio) { bestRatio = ratio; bestSnap = i; bestBlocked = s.getBlocked(); }
        }
        if (bestSnap < 0 || bestRatio < 0.20) return;
        int topWaiters = 0, lockCount = 0;
        for (LockB l : locks.get(bestSnap)) {
            topWaiters = Math.max(topWaiters, l.waiters.size());
            if (!l.waiters.isEmpty()) lockCount++;
        }
        boolean contentionReported = r.getFindings().stream().anyMatch(f -> "LOCK_CONTENTION".equals(f.getCode()));
        if (contentionReported && topWaiters >= bestBlocked * 0.7) return;   // 락 하나가 설명한다 — 중복 소견을 내지 않는다
        Finding f = finding("BLOCKED_RATIO_HIGH", bestRatio >= 0.5 ? "High" : "Medium", "BLOCKED 스레드 비율 높음");
        f.setDetail("Java 스레드 " + r.getSnapshots().get(bestSnap).getJavaThreads() + "개 중 " + bestBlocked + "개("
                + pct(bestRatio) + ")가 모니터 진입을 기다립니다. 여러 락에 대기가 흩어져 있습니다.");
        f.setAdvice("락 경합 표에서 대기자가 많은 락부터 소유 스레드의 스택을 확인하세요.");
        f.setSnapshotIdx(bestSnap);
        f.getEvidence().put("blocked", bestBlocked);
        f.getEvidence().put("ratioPct", round1(bestRatio * 100));
        basis(f, "대기자가 있는 락 " + lockCount + "개, 가장 많은 락의 대기자 " + topWaiters + "개");
    }

    private void ruleStuckThreads() {
        List<StuckB> list = new ArrayList<>();
        for (StuckB s : stuck) if (!deadlocked.contains(s.t)) list.add(s);
        if (list.isEmpty()) return;
        boolean high = list.size() >= 5;
        Map<PoolB, Integer> perPool = new HashMap<>();
        for (StuckB s : list) if (s.t.pool != null) perPool.merge(s.t.pool, 1, Integer::sum);
        List<String> poolLines = new ArrayList<>();
        for (Map.Entry<PoolB, Integer> e : perPool.entrySet()) {
            if (e.getKey().max() >= 10 && e.getValue() >= e.getKey().max() * 0.10) {
                high = true;
                poolLines.add("풀 \"" + e.getKey().stem + "\" 의 " + e.getKey().max() + "개 중 " + e.getValue() + "개가 정체");
            }
        }
        // 관측 구간이 짧으면(간격을 아는데 최장이 10초 미만) 지나가는 작업일 수 있다 — High 로 올리지 않는다
        Double longest = null;
        for (StuckB s : list) if (s.durationSec != null && (longest == null || s.durationSec > longest)) longest = s.durationSec;
        boolean shortObs = longest != null && longest < SHORT_OBSERVATION_SEC;
        if (shortObs) high = false;

        Map<String, Integer> byCat = new LinkedHashMap<>();
        for (StuckB s : list) byCat.merge(s.category, 1, Integer::sum);
        Finding f = finding("STUCK_THREADS", high ? "High" : "Medium", "같은 스택에 머무는 스레드 " + list.size() + "개");
        StuckB top = list.get(0);
        f.setDetail("여러 스냅샷에 걸쳐 스택이 바뀌지 않은 스레드가 " + list.size() + "개 있습니다. 가장 오래 머문 스레드는 \""
                + top.t.any().name + "\" (" + (top.to - top.from + 1) + "개 스냅샷"
                + (top.durationSec != null ? ", 약 " + top.durationSec + "초" : "") + ", " + table.top(top.stackId) + ") 입니다.");
        f.setAdvice("정체 스레드 표에서 분류(락 대기·I/O·실행 중)별로 스택을 확인하세요. 실행 중인데 스택이 같으면 무한 루프, I/O 면 상대 시스템 지연을 의심합니다.");
        f.setSnapshotIdx(top.to);
        f.getEvidence().put("count", list.size());
        f.getEvidence().put("byCategory", byCat);
        for (StuckB s : list) addTrack(f, s.t);

        List<Map.Entry<String, Integer>> cats = new ArrayList<>(byCat.entrySet());
        cats.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder cb = new StringBuilder();
        for (Map.Entry<String, Integer> e : cats) {
            if (cb.length() > 0) cb.append(" · ");
            cb.append(categoryKo(e.getKey())).append(' ').append(e.getValue());
        }
        basis(f, "분류: " + cb);
        for (String pl : poolLines) basis(f, pl);
        if (shortObs) basis(f, "관측 구간이 짧습니다(최장 약 " + longest + "초) — 지나가는 작업일 수 있어 등급을 올리지 않았습니다. 간격을 넓혀 다시 받으면 확실해집니다");

        // '실행 중' 정체를 CPU 로 가른다(JDK 11+) — 실제로 돌고 있으면 무한 루프 의심, 거의 안 썼으면 네이티브·파일 I/O 안에서 기다리는 것
        List<StuckB> busy = new ArrayList<>();
        Map<StuckB, Double> busyPct = new HashMap<>();
        int quiet = 0;
        for (StuckB s : list) {
            if (!"running".equals(s.category) || s.cpuDeltaMs == null) continue;
            Double gap = cpuGap(s.t, s.from, s.to);
            if (gap == null || gap < HOT_CPU_MIN_GAP_SEC) continue;
            double ratio = s.cpuDeltaMs / (gap * 1000);
            if (ratio >= HOT_CPU_RATIO) { busy.add(s); busyPct.put(s, ratio); }
            else if (ratio < 0.05) quiet++;
        }
        if (quiet > 0) {
            f.getEvidence().put("runningWithoutCpu", quiet);
            basis(f, "'실행 중' 으로 찍힌 정체 스레드 중 " + quiet + "개는 그 구간에 CPU 를 거의 쓰지 않았습니다 — 루프가 아니라 네이티브 호출·파일 I/O 안에서 기다리는 것으로 보입니다");
        }
        if (busy.isEmpty()) return;
        busy.sort((a, b) -> Double.compare(busyPct.get(b), busyPct.get(a)));
        StuckB b0 = busy.get(0);
        String topFrame = table.top(b0.stackId);
        Finding g = finding("BUSY_LOOP_SUSPECT", "High", "무한 루프 의심 — 같은 스택에서 CPU 를 쓰는 스레드 " + busy.size() + "개");
        g.setDetail("\"" + b0.t.any().name + "\" 가 " + (b0.to - b0.from + 1) + "개 스냅샷"
                + (b0.durationSec != null ? "(약 " + b0.durationSec + "초)" : "") + " 동안 같은 스택(" + topFrame + ")에 머물면서 CPU 를 한 코어의 "
                + pct(busyPct.get(b0)) + " 썼습니다." + (busy.size() > 1 ? " 같은 모습의 스레드가 " + (busy.size() - 1) + "개 더 있습니다." : ""));
        g.setAdvice("스택 최상단 메서드의 반복 조건(끝나지 않는 while·재시도 루프, 종료 조건이 없는 순회)을 확인하세요. 같은 스택이 이어지는 한 그 작업은 스스로 끝나지 않고 코어 하나를 계속 차지합니다.");
        g.setSnapshotIdx(b0.to);
        g.getEvidence().put("count", busy.size());
        g.getEvidence().put("topPct", round1(busyPct.get(b0) * 100));
        g.getEvidence().put("topFrame", topFrame);
        for (StuckB s : busy) addTrack(g, s.t);
        basis(g, "스택이 바뀌지 않음 + 그 구간 CPU " + pct(busyPct.get(b0)) + " — 대기가 아니라 같은 자리에서 계속 실행 중");
        if (topFrame.startsWith("java.util.HashMap.") || topFrame.startsWith("java.util.HashMap$")) {
            basis(g, "최상단이 HashMap 내부입니다 — 동기화 없이 여러 스레드가 함께 고친 HashMap 에서 나타나는 전형적인 모습입니다");
        } else if (topFrame.startsWith("java.util.regex.")) {
            basis(g, "최상단이 정규식 매칭 내부입니다 — 입력에 따라 역추적이 폭증하는 패턴인지 확인하세요");
        }
        relate(f, g);
    }

    /** CPU — JDK 11+ 의 {@code cpu=} 누적값 차이. JDK 8 이하는 값이 없어 이 규칙이 돌지 않는다. */
    private void ruleCpu() {
        if (S < 2) { ruleCpuLifetime(); return; }
        record Hot(TrackB t, double deltaMs, double pct, int from, int to, int hotIntervals) {}
        List<Hot> all = new ArrayList<>();
        for (TrackB tb : tracks) {
            int a = -1, b = -1, hotIntervals = 0, prev = -1;
            for (int i = tb.first; i <= tb.last; i++) {
                ParsedThread t = tb.at[i];
                if (t == null || Double.isNaN(t.cpuMs)) continue;
                if (a < 0) a = i;
                b = i;
                if (prev >= 0) {
                    Double gap = cpuGap(tb, prev, i);
                    if (gap != null && gap >= HOT_CPU_MIN_GAP_SEC && (t.cpuMs - tb.at[prev].cpuMs) / (gap * 1000) >= HOT_CPU_RATIO) hotIntervals++;
                }
                prev = i;
            }
            if (a < 0 || b <= a) continue;
            Double gap = cpuGap(tb, a, b);
            double delta = tb.at[b].cpuMs - tb.at[a].cpuMs;
            if (gap == null || gap <= 0 || delta < 0) continue;
            all.add(new Hot(tb, delta, delta / (gap * 1000), a, b, hotIntervals));
        }
        all.sort((x, y) -> Double.compare(y.deltaMs, x.deltaMs));
        for (int i = 0; i < Math.min(30, all.size()); i++) {
            Hot h = all.get(i);
            if (h.deltaMs < 10) break;      // 몇 ms 짜리는 '상위' 라고 보여 줄 값이 아니다
            ThreadDumpResult.CpuTop c = new ThreadDumpResult.CpuTop();
            c.setT(h.t.idx);
            c.setDeltaMs(round1(h.deltaMs));
            c.setPct(round1(h.pct * 100));
            c.setFrom(h.from);
            c.setTo(h.to);
            r.getCpuTop().add(c);
        }
        List<Hot> hotJava = new ArrayList<>(), hotVm = new ArrayList<>();
        for (Hot h : all) {
            Double gap = cpuGap(h.t, h.from, h.to);
            if (gap == null || gap < HOT_CPU_MIN_GAP_SEC || h.pct < HOT_CPU_RATIO) continue;
            // 상태 줄이 있는 JVM 스레드(C1/C2 CompilerThread 등)도 JVM 쪽으로 — 예전에는 어느 소견에도 들지 않았다(2.7.4)
            if (h.t.sys) hotVm.add(h); else hotJava.add(h);
        }
        if (!hotJava.isEmpty()) {
            boolean sustained = hotJava.stream().anyMatch(h -> h.hotIntervals >= 2);
            Map<Integer, Integer> sameStack = new HashMap<>();
            for (Hot h : hotJava) sameStack.merge(h.t.at[h.to].stackId, 1, Integer::sum);
            int groupMax = 0;
            for (int v : sameStack.values()) groupMax = Math.max(groupMax, v);
            boolean group = groupMax >= 2;
            Hot top = hotJava.get(0);
            Finding f = finding("HOT_THREAD_CPU", sustained || group ? "High" : "Medium", "CPU 점유 스레드 " + hotJava.size() + "개");
            f.setDetail("스냅샷 사이에 CPU 를 한 코어의 80% 이상 쓴 스레드가 " + hotJava.size() + "개 있습니다. 최상위는 \""
                    + top.t.any().name + "\" (" + pct(top.pct) + ", " + table.top(top.t.at[top.to].stackId) + ") 입니다.");
            f.setAdvice("해당 스레드의 스택이 스냅샷마다 같으면 무한 루프·과도한 반복을, 매번 다르면 처리량 자체가 많은 것을 의심하세요.");
            f.setSnapshotIdx(top.to);
            f.getEvidence().put("count", hotJava.size());
            f.getEvidence().put("topPct", round1(top.pct * 100));
            for (Hot h : hotJava) addTrack(f, h.t);
            double sum = 0;
            for (Hot h : hotJava) sum += h.pct;
            basis(f, "해당 스레드들의 CPU 합계 ≈ 코어 " + round1(sum) + "개분");
            if (sustained) basis(f, "두 구간 이상 연속으로 80% 를 넘은 스레드가 있습니다");
            if (group) basis(f, "같은 스택에서 CPU 를 쓰는 스레드가 " + groupMax + "개 — 같은 코드 경로가 몰려 돕니다");
        }
        if (!hotVm.isEmpty()) {
            Hot top = hotVm.get(0);
            int gc = 0, comp = 0, other = 0;
            boolean gcSustained = false;
            double gcSum = 0;
            for (Hot h : hotVm) {
                String k = ThreadDumpRules.vmKind(h.t.any().name);
                if (ThreadDumpRules.VM_GC.equals(k)) { gc++; gcSum += h.pct; if (h.hotIntervals >= 2) gcSustained = true; }
                else if (ThreadDumpRules.VM_COMPILER.equals(k)) comp++;
                else other++;
            }
            Finding f = finding("VM_THREAD_CPU_HIGH", gcSustained ? "High" : "Medium", "GC/VM 스레드 CPU 사용 높음");
            f.setDetail("JVM 내부 스레드 " + hotVm.size() + "개가 스냅샷 사이에 CPU 를 한 코어의 80% 이상 썼습니다. 최상위는 \""
                    + top.t.any().name + "\" (" + pct(top.pct) + ") 입니다.");
            f.setAdvice(vmCpuAdvice(gc, comp));
            f.setSnapshotIdx(top.to);
            f.getEvidence().put("count", hotVm.size());
            f.getEvidence().put("gc", gc);
            f.getEvidence().put("compiler", comp);
            f.getEvidence().put("other", other);
            for (Hot h : hotVm) addTrack(f, h.t);
            basis(f, "구성: GC " + gc + " · JIT 컴파일러 " + comp + " · 기타 " + other);
            if (gc > 0) basis(f, "GC 스레드 CPU 합계 ≈ 코어 " + round1(gcSum) + "개분" + (gcSustained ? " — 두 구간 이상 이어졌습니다" : ""));
        }
    }

    private static String vmCpuAdvice(int gc, int comp) {
        if (gc > 0) return "GC 스레드가 쉬지 않고 돕니다 — 힙이 부족해 GC 가 연달아 도는 상황일 수 있습니다. 같은 시각의 GC 로그(Full GC 빈도·회수량)를 함께 확인하세요.";
        if (comp > 0) return "JIT 컴파일러 스레드입니다. 기동·배포 직후라면 정상이고, 한참 뒤에도 이어지면 코드 캐시 부족(ReservedCodeCacheSize)이나 반복되는 역최적화를 의심하세요.";
        return "JVM 내부 스레드입니다. 스레드 이름으로 역할을 확인하고 같은 시각의 GC 로그·JVM 로그를 함께 보세요.";
    }

    /**
     * 스냅샷이 1개일 때의 CPU — 구간 값은 없지만 JDK 11+ 는 {@code cpu=}(누적)와 {@code elapsed=}(생성 후 경과)를 찍으므로
     * <b>생성 이후 평균</b>은 낼 수 있다. 평균이라 "지금" 을 말하지는 못한다 — 문구에 그렇게 적고 Medium 을 넘기지 않는다.
     */
    private void ruleCpuLifetime() {
        if (S != 1) return;
        record Avg(TrackB t, double pct, double elapsed) {}
        List<Avg> java = new ArrayList<>(), vm = new ArrayList<>();
        List<ParsedThread> th = snaps.get(0).threads;
        for (int j = 0; j < th.size(); j++) {
            ParsedThread t = th.get(j);
            if (Double.isNaN(t.cpuMs) || Double.isNaN(t.elapsedSec) || t.elapsedSec < LIFETIME_CPU_MIN_ELAPSED_SEC) continue;
            double p = t.cpuMs / (t.elapsedSec * 1000);
            if (p < HOT_CPU_RATIO) continue;
            TrackB tb = trackOf[0][j];
            (tb.sys ? vm : java).add(new Avg(tb, p, t.elapsedSec));
        }
        java.sort((a, b) -> Double.compare(b.pct, a.pct));
        vm.sort((a, b) -> Double.compare(b.pct, a.pct));
        if (!java.isEmpty()) {
            Avg top = java.get(0);
            Finding f = finding("HOT_THREAD_CPU", "Medium", "CPU 점유 스레드 " + java.size() + "개(생성 이후 평균)");
            f.setDetail("스냅샷이 1개라 구간 CPU 는 알 수 없지만, 생성된 뒤 지금까지 평균으로 한 코어의 80% 이상을 쓴 스레드가 " + java.size()
                    + "개 있습니다. 최상위는 \"" + top.t.any().name + "\" (" + pct(top.pct) + ", 생성 후 약 " + round1(top.elapsed) + "초, "
                    + table.top(top.t.at[0].stackId) + ") 입니다.");
            f.setAdvice("살아 있는 내내 CPU 를 쓴 스레드입니다. 5~10초 간격으로 2~3회 더 받아 스택이 같은 자리에 머무는지(무한 루프) 매번 달라지는지(처리량) 확인하세요.");
            f.setSnapshotIdx(0);
            f.getEvidence().put("count", java.size());
            f.getEvidence().put("topPct", round1(top.pct * 100));
            f.getEvidence().put("basis", "lifetime");
            for (Avg a : java) addTrack(f, a.t);
            basis(f, "판정 근거: cpu= ÷ elapsed= (생성 이후 평균) — 지금 이 순간의 사용률이 아닙니다");
        }
        if (!vm.isEmpty()) {
            Avg top = vm.get(0);
            int gc = 0, comp = 0;
            for (Avg a : vm) {
                String k = ThreadDumpRules.vmKind(a.t.any().name);
                if (ThreadDumpRules.VM_GC.equals(k)) gc++; else if (ThreadDumpRules.VM_COMPILER.equals(k)) comp++;
            }
            Finding f = finding("VM_THREAD_CPU_HIGH", "Medium", "GC/VM 스레드 CPU 사용 높음(기동 이후 평균)");
            f.setDetail("JVM 내부 스레드 " + vm.size() + "개가 기동 이후 평균으로 CPU 를 한 코어의 80% 이상 썼습니다. 최상위는 \""
                    + top.t.any().name + "\" (" + pct(top.pct) + ") 입니다.");
            f.setAdvice(vmCpuAdvice(gc, comp));
            f.setSnapshotIdx(0);
            f.getEvidence().put("count", vm.size());
            f.getEvidence().put("gc", gc);
            f.getEvidence().put("compiler", comp);
            f.getEvidence().put("basis", "lifetime");
            for (Avg a : vm) addTrack(f, a.t);
            basis(f, "판정 근거: cpu= ÷ elapsed= (기동 이후 평균)");
        }
    }

    /** CPU 비율의 분모 — 그 스레드의 {@code elapsed=} 차이를 우선하고 없으면 스냅샷 간격. */
    private Double cpuGap(TrackB tb, int i, int j) {
        double a = tb.at[i].elapsedSec, b = tb.at[j].elapsedSec;
        if (!Double.isNaN(a) && !Double.isNaN(b) && b > a) return b - a;
        return gapSec(i, j);
    }

    private void rulePoolNoIdle() {
        for (PoolB p : pools.values()) {
            if (!p.worker) continue;
            int streak = 0, best = 0, bestSnap = -1;
            for (int i = 0; i < S; i++) {
                boolean none = p.size[i] >= POOL_MIN_SIZE && p.idle[i] == 0;
                streak = none && i > 0 && run[i] == run[i - 1] ? streak + 1 : (none ? 1 : 0);
                if (streak > best) { best = streak; bestSnap = i; }
            }
            if (best > 0) {
                Finding f = finding("POOL_NO_IDLE", best >= 2 ? "High" : "Medium", "요청 처리 풀에 유휴 스레드 없음");
                f.setDetail("풀 \"" + p.stem + "\" 의 스레드 " + p.size[bestSnap] + "개가 모두 작업 중입니다"
                        + (best >= 2 ? "(" + best + "개 스냅샷 연속)." : ".") + " " + POOL_MAX_NOTE);
                f.setAdvice("풀 스레드들이 무엇을 기다리는지는 근거의 활동 분해에 있습니다. 그 원인부터 해소하세요 — 풀 크기를 늘리는 것은 그 뒤의 조치입니다.");
                f.setSnapshotIdx(bestSnap);
                f.getEvidence().put("pool", p.stem);
                f.getEvidence().put("size", p.size[bestSnap]);
                f.getEvidence().put("idle", 0);
                f.getEvidence().put("consecutiveSnapshots", best);
                poolHits.add(new PoolHit(f, p, bestSnap));
                continue;
            }
            // 유휴가 거의 없는 풀 — 유휴 0 의 연속 계산과는 따로 본다(유휴 1~2개가 섞여도 연속을 늘리지 않는다)
            int nearSnap = -1;
            double nearRatio = 1;
            for (int i = 0; i < S; i++) {
                if (p.size[i] < POOL_NEAR_FULL_MIN_SIZE || p.idle[i] == 0) continue;
                double ratio = (double) p.idle[i] / p.size[i];
                if (ratio <= POOL_NEAR_FULL_IDLE_RATIO && ratio < nearRatio) { nearRatio = ratio; nearSnap = i; }
            }
            if (nearSnap < 0) continue;
            Finding f = finding("POOL_NO_IDLE", "Medium", "요청 처리 풀에 유휴 스레드 거의 없음");
            f.setDetail("풀 \"" + p.stem + "\" 의 스레드 " + p.size[nearSnap] + "개 중 유휴가 " + p.idle[nearSnap] + "개뿐입니다. " + POOL_MAX_NOTE);
            f.setAdvice("풀 스레드들이 무엇을 기다리는지는 근거의 활동 분해에 있습니다. 그 원인부터 해소하세요 — 풀 크기를 늘리는 것은 그 뒤의 조치입니다.");
            f.setSnapshotIdx(nearSnap);
            f.getEvidence().put("pool", p.stem);
            f.getEvidence().put("size", p.size[nearSnap]);
            f.getEvidence().put("idle", p.idle[nearSnap]);
            f.getEvidence().put("consecutiveSnapshots", 0);
            poolHits.add(new PoolHit(f, p, nearSnap));
        }
    }

    private static final String POOL_MAX_NOTE = "풀이 설정된 최대 크기에 이미 도달했다면 새 요청은 큐에서 기다리거나 거부됩니다(최대 크기는 덤프에 없습니다).";

    /** 커넥션 풀 대기자 — 세는 기준과 관련 스레드로 보이는 기준을 한 곳에서 정한다. */
    private boolean connPoolWaiter(ParsedThread t, String kind) {
        if (t.state != ThreadDumpResult.ST_WAITING && t.state != ThreadDumpResult.ST_TIMED && t.state != ThreadDumpResult.ST_BLOCKED) return false;
        ThreadDumpRules.Traits x = tr(t);
        return x.connPoolWait && (kind == null || kind.equals(x.poolKind));
    }

    private void ruleConnPoolWait() {
        for (String kind : new String[]{ThreadDumpRules.POOL_DB, ThreadDumpRules.POOL_HTTP, ThreadDumpRules.POOL_OBJECT}) {
            int bestSnap = -1, best = 0;
            for (int i = 0; i < S; i++) {
                int n = 0;
                for (ParsedThread t : snaps.get(i).threads) if (connPoolWaiter(t, kind)) n++;
                if (n > best) { best = n; bestSnap = i; }
            }
            if (best < 3) continue;
            int workers = 0;
            for (PoolB p : pools.values()) if (p.worker) workers += p.size[bestSnap];
            boolean high = best >= 10 || (workers > 0 && best >= workers * 0.25);
            Finding f;
            if (ThreadDumpRules.POOL_DB.equals(kind)) {
                f = finding("CONN_POOL_WAIT", high ? "High" : "Medium", "DB 커넥션 풀 대기 " + best + "개 스레드");
                f.setDetail(best + "개 스레드가 DB 커넥션 풀에서 커넥션이 반납되기를 기다립니다. 풀이 고갈됐거나 커넥션을 쥔 스레드가 오래 걸리고 있습니다.");
                f.setAdvice("커넥션을 쥐고 있는 스레드(DB 응답 대기·긴 트랜잭션)를 먼저 찾으세요. 반납 누락(close 미호출)도 같은 모양으로 나타납니다.");
            } else if (ThreadDumpRules.POOL_HTTP.equals(kind)) {
                f = finding("CONN_POOL_WAIT", high ? "High" : "Medium", "HTTP 커넥션 풀 대기 " + best + "개 스레드");
                f.setDetail(best + "개 스레드가 HTTP 클라이언트의 커넥션 풀에서 연결이 반납되기를 기다립니다. 대상별 최대 연결 수에 도달했거나 연결을 쥔 호출이 오래 걸리고 있습니다.");
                f.setAdvice("같은 대상을 호출하고 응답을 기다리는 스레드를 먼저 확인하세요. 응답 본문을 닫지 않아(close·consume 누락) 연결이 반납되지 않는 경우도 같은 모양입니다. 풀 크기(maxTotal·maxPerRoute)와 타임아웃도 함께 보세요.");
            } else {
                f = finding("CONN_POOL_WAIT", high ? "High" : "Medium", "객체 풀 대기 " + best + "개 스레드");
                f.setDetail(best + "개 스레드가 commons-pool 객체 풀에서 객체가 반납되기를 기다립니다. 무엇의 풀인지는 스택의 호출 지점으로 확인합니다(캐시·메시지 클라이언트 등).");
                f.setAdvice("풀에서 꺼낸 객체를 쥔 채 오래 걸리는 스레드와 반납 누락을 확인하세요. 풀 최대 크기(maxTotal)와 대기 타임아웃(maxWait) 설정도 함께 보세요.");
            }
            f.setSnapshotIdx(bestSnap);
            f.getEvidence().put("waiting", best);
            f.getEvidence().put("poolKind", kind);
            List<ParsedThread> th = snaps.get(bestSnap).threads;
            List<ParsedThread> members = new ArrayList<>();
            for (int j = 0; j < th.size(); j++) {
                if (!connPoolWaiter(th.get(j), kind)) continue;
                members.add(th.get(j));
                addTrack(f, trackOf[bestSnap][j]);
            }
            if (workers > 0) basis(f, "대기 " + best + "개 / 요청 처리 풀 스레드 " + workers + "개(" + pct((double) best / workers) + ")");
            basis(f, callSiteLine(members));
        }
    }

    private void ruleIoWait() {
        ioCluster(Activity.DB_WAIT);
        ioCluster(Activity.REMOTE_WAIT);
        ioCluster(Activity.SOCKET_WAIT);
    }

    /**
     * 스냅샷 i 에서 kind 로 응답을 기다리는 스레드(스냅샷 안의 위치 j). 세기·관련 스레드·지속 판정이 모두 이 목록을 쓴다.
     * 대상을 모르는 소켓 대기({@code SOCKET_WAIT})는 <b>요청 처리 풀 스레드이거나 같은 스택이 5개 이상 묶인 것</b>만 센다 —
     * 평생 소켓 하나를 읽는 백그라운드 스레드(수집기·구독자)를 "집중" 으로 세지 않기 위해서다.
     */
    private List<Integer> ioMembers(int i, Activity kind) {
        List<ParsedThread> th = snaps.get(i).threads;
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j < th.size(); j++) {
            ParsedThread t = th.get(j);
            TrackB tb = trackOf[i][j];
            if (!t.javaThread() || tb.sys) continue;
            if (activityOf(tb, t) == kind) out.add(j);
        }
        if (kind != Activity.SOCKET_WAIT || out.isEmpty()) return out;
        Map<Integer, Integer> byStack = new HashMap<>();
        for (int j : out) byStack.merge(th.get(j).stackId, 1, Integer::sum);
        List<Integer> kept = new ArrayList<>();
        for (int j : out) {
            TrackB tb = trackOf[i][j];
            boolean worker = tb.pool != null && tb.pool.worker;
            if (worker || byStack.get(th.get(j).stackId) >= SOCKET_GROUP_MIN) kept.add(j);
        }
        return kept;
    }

    private int workingCount(int i) {
        int active = 0;
        List<ParsedThread> th = snaps.get(i).threads;
        for (int j = 0; j < th.size(); j++) {
            ParsedThread t = th.get(j);
            TrackB tb = trackOf[i][j];
            if (!t.javaThread() || tb.sys) continue;
            if (working(activityOf(tb, t))) active++;
        }
        return active;
    }

    private void ioCluster(Activity kind) {
        int bestSnap = -1, best = 0;
        for (int i = 0; i < S; i++) {
            int n = ioMembers(i, kind).size();
            if (n > best) { best = n; bestSnap = i; }
        }
        if (best < IO_CLUSTER_MIN) return;
        int bestActive = workingCount(bestSnap);
        if (bestActive == 0) return;
        double ratio = (double) best / bestActive;
        if (ratio < IO_CLUSTER_RATIO) return;
        List<Integer> members = ioMembers(bestSnap, kind);
        List<ParsedThread> th = snaps.get(bestSnap).threads;
        // 같은 스레드가 인접 스냅샷에도 같은 스택이면 응답이 실제로 늦는 것이다
        int persistedCount = 0;
        for (int j : members) {
            ParsedThread t = th.get(j);
            TrackB tb = trackOf[bestSnap][j];
            for (int k : new int[]{bestSnap - 1, bestSnap + 1}) {
                if (k >= 0 && k < S && run[k] == run[bestSnap] && tb.at[k] != null && tb.at[k].stackId == t.stackId) { persistedCount++; break; }
            }
        }
        boolean persisted = persistedCount > 0;
        String sev = ratio >= 0.5 || persisted ? "High" : (S == 1 ? "Low" : "Medium");
        Finding f;
        if (kind == Activity.DB_WAIT) {
            f = finding("DB_WAIT_CLUSTER", sev, "DB 응답 대기 집중 — " + best + "개 스레드");
            f.setDetail("작업 중인 스레드 " + bestActive + "개 중 " + best + "개(" + pct(ratio) + ")가 JDBC 드라이버 안에서 DB 응답을 기다립니다."
                    + (persisted ? " 같은 스레드가 인접 스냅샷에서도 같은 스택이었습니다." : ""));
            f.setAdvice("DB 쪽의 느린 쿼리·락 대기를 확인하세요. 스택의 호출 지점으로 어떤 쿼리인지 좁힐 수 있습니다.");
        } else if (kind == Activity.REMOTE_WAIT) {
            f = finding("REMOTE_CALL_WAIT_CLUSTER", sev, "외부 호출 응답 대기 집중 — " + best + "개 스레드");
            f.setDetail("작업 중인 스레드 " + bestActive + "개 중 " + best + "개(" + pct(ratio) + ")가 HTTP 클라이언트 안에서 상대 시스템의 응답을 기다립니다."
                    + (persisted ? " 같은 스레드가 인접 스냅샷에서도 같은 스택이었습니다." : ""));
            f.setAdvice("호출 대상 시스템의 응답 지연과 클라이언트 타임아웃 설정(연결·읽기)을 확인하세요.");
        } else {
            f = finding("SOCKET_WAIT_CLUSTER", sev, "소켓 응답 대기 집중 — " + best + "개 스레드");
            f.setDetail("작업 중인 스레드 " + bestActive + "개 중 " + best + "개(" + pct(ratio) + ")가 소켓에서 상대의 응답을 기다립니다. "
                    + "JDBC·HTTP 클라이언트가 아닌 연결이라 대상은 스택의 호출 지점으로 확인합니다(캐시·메시지 큐·LDAP·자체 프로토콜 등)."
                    + (persisted ? " 같은 스레드가 인접 스냅샷에서도 같은 스택이었습니다." : ""));
            f.setAdvice("호출 지점이 가리키는 대상 시스템의 응답 지연과 읽기 타임아웃 설정을 확인하세요. 타임아웃이 없으면 상대가 응답하지 않는 한 스레드가 풀리지 않습니다.");
        }
        f.setSnapshotIdx(bestSnap);
        f.getEvidence().put("waiting", best);
        f.getEvidence().put("active", bestActive);
        f.getEvidence().put("ratioPct", round1(ratio * 100));
        Map<Integer, Integer> byStack = new HashMap<>();
        List<ParsedThread> list = new ArrayList<>();
        for (int j : members) {
            addTrack(f, trackOf[bestSnap][j]);
            list.add(th.get(j));
            byStack.merge(th.get(j).stackId, 1, Integer::sum);
        }
        int groupMax = 0;
        for (int v : byStack.values()) groupMax = Math.max(groupMax, v);
        f.getEvidence().put("sameStackMax", groupMax);
        basis(f, "작업 중 " + bestActive + "개 중 " + best + "개(" + pct(ratio) + ")");
        basis(f, callSiteLine(list));
        if (groupMax >= 2) basis(f, "스택이 완전히 같은 스레드가 최대 " + groupMax + "개 — 같은 호출 경로에 몰려 있습니다");
        if (persisted) basis(f, persistedCount + "개는 인접 스냅샷에서도 같은 스택 — 지나가는 호출이 아니라 응답이 실제로 늦습니다");
        else if (S == 1) basis(f, "스냅샷이 1개라 지속 여부는 알 수 없습니다");
    }

    /** 연결 수립(TCP connect)·DNS 조회에서 멈춘 스레드 — 응답이 늦는 것이 아니라 연결이 맺어지지 않는 상태다(2.7.4). */
    private void ruleConnectWait() {
        int bestSnap = -1, best = 0;
        for (int i = 0; i < S; i++) {
            int n = ioMembers(i, Activity.CONNECT_WAIT).size();
            if (n > best) { best = n; bestSnap = i; }
        }
        if (best < CONNECT_WAIT_MIN) return;
        List<Integer> members = ioMembers(bestSnap, Activity.CONNECT_WAIT);
        List<ParsedThread> th = snaps.get(bestSnap).threads;
        int dns = 0, viaJdbc = 0, viaHttp = 0, persistedCount = 0;
        List<ParsedThread> list = new ArrayList<>();
        for (int j : members) {
            ParsedThread t = th.get(j);
            ThreadDumpRules.Traits x = tr(t);
            if (x.dns) dns++;
            if (x.jdbc) viaJdbc++; else if (x.httpClient) viaHttp++;
            list.add(t);
            TrackB tb = trackOf[bestSnap][j];
            for (int k : new int[]{bestSnap - 1, bestSnap + 1}) {
                if (k >= 0 && k < S && run[k] == run[bestSnap] && tb.at[k] != null && tb.at[k].stackId == t.stackId) { persistedCount++; break; }
            }
        }
        boolean high = persistedCount > 0 || best >= CONNECT_WAIT_HIGH;
        Finding f = finding("CONNECT_WAIT", high ? "High" : "Medium", "연결 수립 대기 " + best + "개 스레드");
        f.setDetail(best + "개 스레드가 상대 시스템에 연결을 맺는 단계(TCP connect" + (dns > 0 ? "·DNS 조회" : "") + ")에서 멈춰 있습니다. "
                + "응답이 늦는 것과 달리 연결 자체가 맺어지지 않는 상태입니다." + (dns > 0 ? " 그중 " + dns + "개는 DNS 조회 중입니다." : ""));
        f.setAdvice("대상 서버 다운·방화벽 차단(패킷 무응답)·대상의 접속 대기열 포화를 확인하세요. 연결 타임아웃이 없으면 OS 기본값(수 분)까지 기다립니다."
                + (dns > 0 ? " DNS 조회가 멈췄다면 네임 서버 응답과 resolv.conf 설정을 확인하세요." : ""));
        f.setSnapshotIdx(bestSnap);
        f.getEvidence().put("waiting", best);
        f.getEvidence().put("dns", dns);
        f.getEvidence().put("viaJdbc", viaJdbc);
        f.getEvidence().put("viaHttpClient", viaHttp);
        for (int j : members) addTrack(f, trackOf[bestSnap][j]);
        if (viaJdbc > 0 || viaHttp > 0) basis(f, "경유: JDBC 드라이버 " + viaJdbc + "개 · HTTP 클라이언트 " + viaHttp + "개 · 그 밖 " + (best - viaJdbc - viaHttp) + "개");
        basis(f, callSiteLine(list));
        if (persistedCount > 0) basis(f, persistedCount + "개는 인접 스냅샷에서도 같은 스택 — 연결이 계속 맺어지지 않습니다");
        else if (S == 1) basis(f, "스냅샷이 1개라 지속 여부는 알 수 없습니다");
    }

    private void ruleThreadCount() {
        int peak = 0, peakSnap = 0;
        for (int i = 0; i < S; i++) {
            int n = r.getSnapshots().get(i).getJavaThreads();
            if (n > peak) { peak = n; peakSnap = i; }
        }
        if (peak >= 2000) {
            Finding f = finding("THREAD_COUNT_HIGH", peak >= 5000 ? "High" : "Medium", "스레드 수 과다 — " + peak + "개");
            List<PoolB> top = new ArrayList<>(pools.values());
            final int ps = peakSnap;
            top.sort((a, b) -> b.size[ps] - a.size[ps]);
            List<String> stems = new ArrayList<>();
            for (int i = 0; i < Math.min(5, top.size()); i++) stems.add(top.get(i).stem + " ×" + top.get(i).size[ps]);
            f.setDetail("Java 스레드가 " + peak + "개입니다(JVM 내부 스레드 제외). 스레드마다 스택 메모리(기본 1MB)를 쓰고 문맥 전환 비용이 커집니다. 많은 이름: " + String.join(", ", stems));
            f.setAdvice("이름 줄기별 개수에서 예상보다 많은 풀을 찾아 최대 크기 설정과 생성 지점을 확인하세요.");
            f.setSnapshotIdx(peakSnap);
            f.getEvidence().put("threads", peak);
            f.getEvidence().put("topStems", stems);
            int all = r.getSnapshots().get(peakSnap).getThreads();
            if (all != peak) basis(f, "Java 스레드 " + peak + "개 + JVM 내부 스레드 " + (all - peak) + "개 = 전체 " + all + "개");
            for (String s : stems) basis(f, s);
        }
        // 증가 추세 — 같은 실행 안에서 3회 이상, 줄어든 적 없이
        int i = 0;
        while (i < S) {
            int j = i;
            while (j + 1 < S && run[j + 1] == run[i]) j++;
            if (j - i + 1 >= 3) {
                boolean mono = true;
                for (int k = i + 1; k <= j; k++) if (r.getSnapshots().get(k).getJavaThreads() < r.getSnapshots().get(k - 1).getJavaThreads()) { mono = false; break; }
                int a = r.getSnapshots().get(i).getJavaThreads(), b = r.getSnapshots().get(j).getJavaThreads();
                if (mono && a > 0 && b >= a * 1.2 && b - a >= 50) {
                    boolean high = b >= a * 2 && b - a >= 200;
                    Finding f = finding("THREAD_COUNT_GROWTH", high ? "High" : "Medium", "스레드 수 증가 추세");
                    Double gap = gapSec(i, j);
                    f.setDetail("스냅샷 " + (j - i + 1) + "개에 걸쳐 Java 스레드가 " + a + " → " + b + "개로 늘었습니다"
                            + (gap != null ? "(약 " + round1(gap) + "초)." : "."));
                    f.setAdvice("늘어난 이름 줄기를 확인하세요. 요청마다 스레드나 ExecutorService 를 새로 만들고 종료하지 않는 코드가 흔한 원인입니다.");
                    f.setSnapshotIdx(j);
                    f.getEvidence().put("from", a);
                    f.getEvidence().put("to", b);
                    // 어느 이름 줄기가 늘었는가
                    final int fi = i, fj = j;
                    List<PoolB> grown = new ArrayList<>();
                    for (PoolB p : pools.values()) if (p.size[fj] > p.size[fi]) grown.add(p);
                    grown.sort((x, y) -> (y.size[fj] - y.size[fi]) - (x.size[fj] - x.size[fi]));
                    List<String> gs = new ArrayList<>();
                    for (int k = 0; k < Math.min(3, grown.size()); k++) {
                        PoolB p = grown.get(k);
                        gs.add(p.stem + " +" + (p.size[fj] - p.size[fi]) + " (" + p.size[fi] + " → " + p.size[fj] + ")");
                    }
                    f.getEvidence().put("grownStems", gs);
                    if (!gs.isEmpty()) basis(f, "늘어난 이름 줄기: " + String.join(" · ", gs));
                    else basis(f, "번호가 붙은 이름 줄기에서는 증가가 보이지 않습니다 — 이름이 제각각인 스레드가 늘었습니다");
                }
            }
            i = j + 1;
        }
    }

    private void ruleExecutorLeak() {
        int best = 0, bestSnap = -1;
        for (int i = 0; i < S; i++) {
            Set<String> ids = new HashSet<>();
            for (ParsedThread t : snaps.get(i).threads) {
                Matcher m = ThreadDumpRules.EXECUTOR_DEFAULT_NAME.matcher(t.name);
                if (m.matches()) ids.add(m.group(1));
            }
            if (ids.size() > best) { best = ids.size(); bestSnap = i; }
        }
        if (best < 50) return;
        Finding f = finding("EXECUTOR_LEAK_SUSPECT", best >= 200 ? "High" : "Medium", "ExecutorService 반복 생성 의심");
        f.setDetail("기본 이름(pool-N-thread-M)의 스레드 풀이 " + best + "개 살아 있습니다. 풀을 만들 때마다 N 이 하나씩 늘어나므로, 풀을 재사용하지 않고 계속 새로 만드는 코드가 있을 가능성이 큽니다.");
        f.setAdvice("Executors.newXxx 호출 지점을 찾아 풀을 한 번만 만들어 재사용하고, 쓰고 버리는 풀은 shutdown() 하세요.");
        f.setSnapshotIdx(bestSnap);
        f.getEvidence().put("pools", best);
    }

    private void ruleFinalizer() {
        for (TrackB tb : tracks) {
            if (!"Finalizer".equals(tb.any().name)) continue;
            boolean blocked = false;
            int blockedSnap = -1;
            // 같은 스택에 머문 최장 구간 — 정체와 같은 기준(스냅샷 3개 이상 또는 30초 이상)을 쓴다. 예전에는 인접 2개면 냈다(2.7.4)
            int from = -1, bestFrom = -1, bestTo = -1;
            for (int i = tb.first; i <= tb.last + 1; i++) {
                ParsedThread t = i <= tb.last ? tb.at[i] : null;
                if (t != null && t.state == ThreadDumpResult.ST_BLOCKED) { blocked = true; blockedSnap = i; }
                boolean busy = t != null && t.stackId >= 0 && !isIdle(t) && !tr(t).idle;
                boolean cont = from >= 0 && busy && t.stackId == tb.at[from].stackId;
                if (cont) continue;
                if (from >= 0 && i - 1 > from && (bestFrom < 0 || (i - 1 - from) > (bestTo - bestFrom))) { bestFrom = from; bestTo = i - 1; }
                from = busy ? i : -1;
            }
            boolean held = false;
            Double dur = null;
            if (bestFrom >= 0) {
                dur = gapSec(bestFrom, bestTo);
                held = (bestTo - bestFrom + 1) >= STUCK_MIN_SNAPSHOTS || (dur != null && dur >= STUCK_MIN_SEC);
            }
            if (!blocked && !held) continue;
            int at = blocked ? blockedSnap : bestTo;
            ParsedThread t = tb.at[at];
            Finding f = finding("FINALIZER_BLOCKED", "Medium", "Finalizer 스레드 정체");
            f.setDetail("Finalizer 스레드가 " + (blocked ? "BLOCKED 상태입니다" : (bestTo - bestFrom + 1) + "개 스냅샷 동안 같은 스택에 머물렀습니다")
                    + " (" + table.top(t.stackId) + "). 그동안 finalize 대상 객체가 회수되지 않고 쌓입니다.");
            f.setAdvice("스택의 finalize() 구현이 락이나 I/O 를 기다리지 않게 고치세요. 힙 사용량이 계속 늘면 이것이 원인일 수 있습니다.");
            f.setSnapshotIdx(at);
            addTrack(f, tb);
            if (held) basis(f, (bestTo - bestFrom + 1) + "개 스냅샷 동안 같은 스택" + (dur != null ? "(약 " + round1(dur) + "초)" : ""));
            if (blocked) basis(f, "BLOCKED — 다른 스레드가 쥔 모니터를 기다립니다");
        }
    }

    private void ruleWeblogicStuck() {
        int best = 0, bestSnap = -1;
        for (int i = 0; i < S; i++) {
            int n = 0;
            for (ParsedThread t : snaps.get(i).threads) if (t.name.startsWith("[STUCK] ExecuteThread")) n++;
            if (n > best) { best = n; bestSnap = i; }
        }
        if (best == 0) return;
        Finding f = finding("WLS_STUCK_THREADS", "High", "WebLogic STUCK 스레드 " + best + "개");
        f.setDetail("WebLogic 이 StuckThreadMaxTime 을 넘긴 것으로 표시한 실행 스레드가 " + best + "개 있습니다.");
        f.setAdvice("STUCK 스레드의 스택에서 기다리는 대상(DB·외부 호출·락)을 확인하세요.");
        f.setSnapshotIdx(bestSnap);
        f.getEvidence().put("count", best);
        List<ParsedThread> th = snaps.get(bestSnap).threads;
        Map<Activity, Integer> act = new EnumMap<>(Activity.class);
        for (int j = 0; j < th.size(); j++) {
            if (!th.get(j).name.startsWith("[STUCK] ExecuteThread")) continue;
            addTrack(f, trackOf[bestSnap][j]);
            act.merge(activityOf(trackOf[bestSnap][j], th.get(j)), 1, Integer::sum);
        }
        basis(f, "STUCK 스레드가 하고 있는 일: " + activityLine(act));
    }

    /** 덤프 밖 로그 줄의 OOM — jstack 스택에는 예외가 찍히지 않으므로 로그 줄의 메시지로만 종류를 가른다. */
    private void ruleOomInLog() {
        if (diag.getOomLines().isEmpty()) return;
        Map<String, Integer> kinds = new LinkedHashMap<>();
        Map<OomDetector.OomKind, Integer> byKind = new LinkedHashMap<>();
        for (String line : diag.getOomLines()) {
            Matcher m = OOM_MSG.matcher(line);
            String msg = m.find() && m.group(1) != null ? m.group(1).trim() : "";
            OomDetector.OomKind kind = OomDetector.classifyMessage(msg);
            kinds.merge(kind == null ? "UNKNOWN" : kind.name(), 1, Integer::sum);
            byKind.merge(kind == null ? OomDetector.OomKind.UNKNOWN : kind, 1, Integer::sum);
        }
        Finding f = finding("OOM_IN_LOG", "High", "로그에 OutOfMemoryError 기록");
        f.setDetail("스레드 덤프와 같은 파일의 로그 줄에 OutOfMemoryError 가 있습니다: " + diag.getOomLines().get(0));
        f.getEvidence().put("kinds", kinds);
        f.getEvidence().put("firstLine", diag.getOomLines().get(0));
        // 종류별 권고 — 가장 많이 나온 종류 기준
        OomDetector.OomKind main = OomDetector.OomKind.UNKNOWN;
        int mainN = 0;
        StringBuilder kb = new StringBuilder();
        for (Map.Entry<OomDetector.OomKind, Integer> e : byKind.entrySet()) {
            if (e.getValue() > mainN) { mainN = e.getValue(); main = e.getKey(); }
            if (kb.length() > 0) kb.append(" · ");
            kb.append(e.getKey().koLabel()).append(" ×").append(e.getValue());
        }
        basis(f, "종류: " + kb);
        f.setAdvice(switch (main) {
            case NATIVE_THREAD -> "스레드를 더 만들지 못한 상황입니다. 이 덤프의 스레드 수와 이름 줄기별 개수에서 비정상적으로 많은 풀을 찾고, "
                    + "OS 한도(ulimit -u·kernel.pid_max·컨테이너 pids 제한)와 스레드 스택 크기(-Xss)를 확인하세요.";
            case HEAP_SPACE, GC_OVERHEAD -> "힙이 부족합니다. 같은 시각의 힙 덤프·GC 로그를 함께 분석하세요. "
                    + "이 덤프에서는 큰 결과를 읽거나 만드는 중인 스레드(대량 조회·직렬화·컬렉션 증가)의 스택이 단서입니다.";
            case METASPACE, COMPRESSED_CLASS_SPACE, PERMGEN -> "클래스 메타데이터 영역이 부족합니다. 클래스로더 누수(재배포 반복)·동적 클래스 생성을 확인하고 관련 크기 설정을 점검하세요.";
            case DIRECT_BUFFER, NATIVE_MEMORY, OUT_OF_SWAP, KILL_PROCESS -> "힙 밖 메모리가 부족합니다. 다이렉트 버퍼·JNI 사용량과 호스트·컨테이너 메모리 한도를 확인하세요.";
            default -> "OOM 종류에 맞춰 힙 덤프(Java heap space)·스레드 수(unable to create native thread)·메타스페이스 설정을 확인하세요. 힙 덤프가 있으면 함께 분석하세요.";
        });
        f.getEvidence().put("mainKind", main.name());
    }

    // ══ 원인 연결 ═══════════════════════════════════════════════

    /**
     * 소견끼리 원인·결과로 잇는다(2.7.4). <b>겹침을 세기만 한다</b> — "풀의 작업 중 스레드 몇 개가 어느 락을 기다리는가" 같은 사실만 근거 줄로 남기고,
     * 세어서 나온 추정에는 "의심" 을 붙인다. 규칙이 남긴 {@link #poolHits}·{@link #lockHits} 로 대상을 찾는다(같은 코드의 소견이 여러 건일 수 있다).
     */
    private void correlate() {
        // 1) 요청 처리 풀 → 그 스레드들이 하고 있는 일
        for (PoolHit h : poolHits) {
            List<ParsedThread> th = snaps.get(h.snap).threads;
            Map<TrackB, LockB> waitFor = new HashMap<>();
            for (LockB l : locks.get(h.snap)) for (TrackB w : l.waiters) waitFor.putIfAbsent(w, l);
            Map<Activity, Integer> act = new EnumMap<>(Activity.class);
            Map<LockB, Integer> lockIn = new HashMap<>();
            int busy = 0;
            for (int j = 0; j < th.size(); j++) {
                TrackB tb = trackOf[h.snap][j];
                if (tb.pool != h.pool) continue;
                Activity a = activityOf(tb, th.get(j));
                if (!working(a)) continue;
                busy++;
                act.merge(a, 1, Integer::sum);
                LockB l = waitFor.get(tb);
                if (l != null) lockIn.merge(l, 1, Integer::sum);
            }
            if (busy == 0) continue;
            Map<String, Integer> ev = new LinkedHashMap<>();
            for (Map.Entry<Activity, Integer> e : act.entrySet()) ev.put(e.getKey().name(), e.getValue());
            h.f.getEvidence().put("activity", ev);
            basisFirst(h.f, "풀 \"" + h.pool.stem + "\" 작업 중 " + busy + "개: " + activityLine(act));
            Map.Entry<Activity, Integer> top = null;
            for (Map.Entry<Activity, Integer> e : act.entrySet()) if (top == null || e.getValue() > top.getValue()) top = e;
            if (top == null || top.getValue() * 2 < busy) continue;      // 한 가지 활동이 절반을 넘을 때만 원인으로 잇는다
            switch (top.getKey()) {
                case LOCK_WAIT -> {
                    Map.Entry<LockB, Integer> tl = null;
                    for (Map.Entry<LockB, Integer> e : lockIn.entrySet()) if (tl == null || e.getValue() > tl.getValue()) tl = e;
                    Finding lf = null;
                    if (tl != null) {
                        basis(h.f, "그중 " + tl.getValue() + "개가 락 " + tl.getKey().addr + " (" + shortClass(tl.getKey().cls) + ") 대기");
                        for (LockHit lh : lockHits) if (lh.lock.addr.equals(tl.getKey().addr) && run[lh.snap] == run[h.snap]) { lf = lh.f; break; }
                    }
                    if (lf == null) lf = first("LOCK_CONTENTION");
                    if (lf == null) lf = first("BLOCKED_RATIO_HIGH");
                    relate(h.f, lf);
                }
                case CONN_POOL_WAIT -> relate(h.f, first("CONN_POOL_WAIT"));
                case DB_WAIT -> relate(h.f, first("DB_WAIT_CLUSTER"));
                case REMOTE_WAIT -> relate(h.f, first("REMOTE_CALL_WAIT_CLUSTER"));
                case SOCKET_WAIT -> relate(h.f, first("SOCKET_WAIT_CLUSTER"));
                case CONNECT_WAIT -> relate(h.f, first("CONNECT_WAIT"));
                case RUNNING -> {
                    basis(h.f, "대부분 실행 중(RUNNABLE)입니다 — 무언가를 기다리는 것이 아니라 처리 자체가 몰린 모습입니다");
                    relate(h.f, first("HOT_THREAD_CPU"));
                }
                default -> { /* sleep·그 밖의 대기는 이을 소견이 없다 */ }
            }
        }

        // 2) 락 → 소유자가 하고 있는 일
        Finding deadlock = first("DEADLOCK");
        for (LockHit lh : lockHits) {
            TrackB o = lh.lock.owner, root = lh.lock.rootBlocker;
            if ((o != null && deadlocked.contains(o)) || (root != null && deadlocked.contains(root))) {
                basis(lh.f, "이 락의 소유 사슬이 교착에 묶여 있습니다 — 교착이 풀리지 않는 한 이 대기도 풀리지 않습니다");
                relate(lh.f, deadlock);
                continue;
            }
            TrackB end = root != null ? root : o;
            if (end == null || end.at[lh.snap] == null) continue;
            switch (activityOf(end, end.at[lh.snap])) {
                case DB_WAIT -> relate(lh.f, first("DB_WAIT_CLUSTER"));
                case REMOTE_WAIT -> relate(lh.f, first("REMOTE_CALL_WAIT_CLUSTER"));
                case SOCKET_WAIT -> relate(lh.f, first("SOCKET_WAIT_CLUSTER"));
                case CONNECT_WAIT -> relate(lh.f, first("CONNECT_WAIT"));
                case CONN_POOL_WAIT -> relate(lh.f, first("CONN_POOL_WAIT"));
                default -> { /* 실행 중·sleep — 소유자 줄이 이미 근거에 있다 */ }
            }
        }

        // 3) DB 커넥션 풀 대기 → 커넥션을 쓰는 스레드가 있는가
        for (Finding f : r.getFindings()) {
            if (!"CONN_POOL_WAIT".equals(f.getCode()) || !ThreadDumpRules.POOL_DB.equals(f.getEvidence().get("poolKind"))) continue;
            int snap = f.getSnapshotIdx() == null ? focus : f.getSnapshotIdx();
            List<ParsedThread> th = snaps.get(snap).threads;
            Map<Activity, Integer> act = new EnumMap<>(Activity.class);
            int users = 0;
            for (int j = 0; j < th.size(); j++) {
                ParsedThread t = th.get(j);
                TrackB tb = trackOf[snap][j];
                if (!t.javaThread() || tb.sys || !tr(t).jdbc || connPoolWaiter(t, null)) continue;
                Activity a = activityOf(tb, t);
                if (!working(a)) continue;
                users++;
                act.merge(a, 1, Integer::sum);
            }
            f.getEvidence().put("jdbcUsers", users);
            if (users == 0) {
                basis(f, "같은 스냅샷에 JDBC 를 쓰는 중인 스레드가 없습니다 — 커넥션을 쥔 채 다른 일을 하는 스레드(트랜잭션 안의 외부 호출·긴 연산)나 반납 누락(close 미호출)이 의심됩니다");
            } else {
                basis(f, "JDBC 를 쓰는 중인 스레드 " + users + "개: " + activityLine(act));
                if (act.getOrDefault(Activity.DB_WAIT, 0) * 2 >= users) relate(f, first("DB_WAIT_CLUSTER"));
            }
        }

        // 4) 정체 ↔ 분류별 원인, 무한 루프 ↔ CPU, OOM(native thread) ↔ 스레드 수
        Finding stuckF = first("STUCK_THREADS");
        if (stuckF != null && stuckF.getEvidence().get("byCategory") instanceof Map<?, ?> byCat) {
            if (byCat.containsKey("blocked") || byCat.containsKey("lock-wait")) relate(stuckF, first("LOCK_CONTENTION"));
            if (byCat.containsKey("pool-wait")) relate(stuckF, first("CONN_POOL_WAIT"));
            if (byCat.containsKey("io")) {
                relate(stuckF, first("DB_WAIT_CLUSTER"));
                relate(stuckF, first("REMOTE_CALL_WAIT_CLUSTER"));
                relate(stuckF, first("SOCKET_WAIT_CLUSTER"));
                relate(stuckF, first("CONNECT_WAIT"));
            }
        }
        relate(first("BUSY_LOOP_SUSPECT"), first("HOT_THREAD_CPU"));
        Finding oom = first("OOM_IN_LOG");
        if (oom != null && "NATIVE_THREAD".equals(oom.getEvidence().get("mainKind"))) {
            relate(oom, first("THREAD_COUNT_HIGH"));
            relate(oom, first("THREAD_COUNT_GROWTH"));
            relate(oom, first("EXECUTOR_LEAK_SUSPECT"));
        }

        buildVerdict();
    }

    /** 같은 등급 안에서 종합 진단의 머리로 삼을 순서 — 증상(풀·교착) 쪽을 앞에 두고 원인 쪽으로 따라 내려간다. */
    private static final List<String> VERDICT_ORDER = List.of(
            "DEADLOCK", "POOL_NO_IDLE", "WLS_STUCK_THREADS", "CONN_POOL_WAIT", "LOCK_OWNER_STUCK", "LOCK_CONTENTION",
            "DB_WAIT_CLUSTER", "REMOTE_CALL_WAIT_CLUSTER", "SOCKET_WAIT_CLUSTER", "CONNECT_WAIT", "BUSY_LOOP_SUSPECT",
            "HOT_THREAD_CPU", "STUCK_THREADS", "OOM_IN_LOG");

    /**
     * 종합 진단 — <b>가장 높은 등급의 소견</b>에서 {@code related} 를 따라간다. 머리는 반드시 최고 등급이어야 한다:
     * 교착(Critical)이 있는데 이어진 소견이 있다는 이유로 High 소견을 머리에 올리면 화면 배너가 가장 심각한 문제를 가린다.
     * 최고 등급 소견 중 이어진 것이 없으면 만들지 않는다(화면·PDF 가 최상위 소견으로 대신한다).
     */
    private void buildVerdict() {
        int max = 0;
        for (Finding f : r.getFindings()) max = Math.max(max, severityLevel(f.getSeverity()));
        if (max < 2) return;
        Finding head = null;
        for (Finding f : r.getFindings()) {
            if (severityLevel(f.getSeverity()) != max || f.getRelated() == null || f.getRelated().isEmpty()) continue;
            if (head == null || verdictRank(f) < verdictRank(head)) head = f;
        }
        if (head == null) return;
        ThreadDumpResult.Verdict v = new ThreadDumpResult.Verdict();
        v.setHeadline(clip(head.getTitle() + lead(head), 240));
        v.getCodes().add(head.getCode());
        List<Finding> queue = new ArrayList<>();
        queue.add(head);
        for (int qi = 0; qi < queue.size() && v.getPoints().size() < VERDICT_POINTS; qi++) {
            Finding cur = queue.get(qi);
            if (cur.getRelated() == null) continue;
            for (String code : cur.getRelated()) {
                if (v.getCodes().contains(code) || v.getPoints().size() >= VERDICT_POINTS) continue;
                Finding n = first(code);
                if (n == null) continue;
                v.getCodes().add(code);
                v.getPoints().add(clip(n.getTitle() + lead(n), 240));
                queue.add(n);
            }
        }
        if (v.getPoints().isEmpty()) return;
        r.setVerdict(v);
    }

    private static int verdictRank(Finding f) {
        int i = VERDICT_ORDER.indexOf(f.getCode());
        return i < 0 ? VERDICT_ORDER.size() : i;
    }

    private static String lead(Finding f) {
        return f.getBasis() == null || f.getBasis().isEmpty() ? "" : " — " + f.getBasis().get(0);
    }

    // ── 근거·연결 헬퍼 ──────────────────────────────────────────

    private record PoolHit(Finding f, PoolB pool, int snap) {}
    private record LockHit(Finding f, LockB lock, int snap) {}
    private final List<PoolHit> poolHits = new ArrayList<>();
    private final List<LockHit> lockHits = new ArrayList<>();

    private static void basis(Finding f, String line) {
        if (f == null || line == null || line.isEmpty()) return;
        if (f.getBasis() == null) f.setBasis(new ArrayList<>());
        if (f.getBasis().size() < BASIS_CAP && !f.getBasis().contains(line)) f.getBasis().add(line);
    }

    private static void basisFirst(Finding f, String line) {
        if (f == null || line == null || line.isEmpty()) return;
        if (f.getBasis() == null) f.setBasis(new ArrayList<>());
        f.getBasis().remove(line);
        f.getBasis().add(0, line);
        while (f.getBasis().size() > BASIS_CAP) f.getBasis().remove(f.getBasis().size() - 1);
    }

    /** 두 소견을 서로의 연관 소견으로 잇는다. 한쪽이 없으면 아무것도 하지 않는다. */
    private static void relate(Finding a, Finding b) {
        if (a == null || b == null || a == b || a.getCode().equals(b.getCode())) return;
        addRelated(a, b.getCode());
        addRelated(b, a.getCode());
    }

    private static void addRelated(Finding f, String code) {
        if (f.getRelated() == null) f.setRelated(new ArrayList<>());
        if (!f.getRelated().contains(code)) f.getRelated().add(code);
    }

    private Finding first(String code) {
        for (Finding f : r.getFindings()) if (code.equals(f.getCode())) return f;
        return null;
    }

    private static <K> Map.Entry<K, Integer> top(Map<K, Integer> m) {
        Map.Entry<K, Integer> best = null;
        for (Map.Entry<K, Integer> e : m.entrySet()) if (best == null || e.getValue() > best.getValue()) best = e;
        return best;
    }

    /** "DB 응답 대기 12 · 락 대기 3 …" — 많은 순, 최대 5가지. */
    private static String activityLine(Map<Activity, Integer> act) {
        List<Map.Entry<Activity, Integer>> es = new ArrayList<>(act.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, es.size()); i++) {
            if (es.get(i).getKey().ko.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(es.get(i).getKey().ko).append(' ').append(es.get(i).getValue());
        }
        return sb.toString();
    }

    /** 스레드들의 호출 지점 중 가장 많은 것 — "호출 지점 x.Dao.find(Dao.java:1) ×9". 찾지 못하면 null. */
    private String callSiteLine(List<ParsedThread> list) {
        Map<String, Integer> sites = new LinkedHashMap<>();
        for (ParsedThread t : list) {
            if (t.stackId < 0) continue;
            String s = callSites.computeIfAbsent(t.stackId, id -> { String c = ThreadDumpRules.callSite(table, id); return c == null ? "" : c; });
            if (!s.isEmpty()) sites.merge(s, 1, Integer::sum);
        }
        Map.Entry<String, Integer> best = top(sites);
        if (best == null) return null;
        return "호출 지점 " + clip(best.getKey(), FRAME_CLIP) + " ×" + best.getValue()
                + (sites.size() > 1 ? " (그 밖에 " + (sites.size() - 1) + "곳)" : "");
    }

    private final Map<Integer, String> callSites = new HashMap<>();

    private static String lockKindKo(String kind) {
        return switch (kind) {
            case ThreadDumpRules.LOCK_CONSOLE -> "콘솔 출력(System.out·System.err)";
            case ThreadDumpRules.LOCK_LOGGING -> "로그 출력(appender·handler)";
            case ThreadDumpRules.LOCK_CLASSLOAD -> "클래스 로딩";
            case ThreadDumpRules.LOCK_DNS -> "DNS 조회(같은 호스트 이름)";
            case ThreadDumpRules.LOCK_JDBC -> "JDBC 드라이버 객체(커넥션·스테이트먼트 공유)";
            default -> kind;
        };
    }

    private static String lockAdvice(String kind) {
        return switch (kind) {
            case ThreadDumpRules.LOCK_CONSOLE -> "System.out·System.err 는 스트림 하나를 잠그고 씁니다. 요청 경로의 println·printStackTrace 를 로거로 바꾸고, 콘솔이 향하는 곳(디스크·파이프)의 쓰기가 느린지 확인하세요.";
            case ThreadDumpRules.LOCK_LOGGING -> "로그 출력이 한 락에 몰렸습니다. 로그량(레벨)을 줄이거나 비동기 appender 를 쓰고, 로그 파일이 놓인 디스크의 쓰기 지연을 확인하세요.";
            case ThreadDumpRules.LOCK_CLASSLOAD -> "클래스 로딩 락입니다. 요청마다 클래스를 다시 찾는 코드(Class.forName·리플렉션·스크립트 컴파일)와 느린 클래스패스(네트워크 파일시스템의 JAR)를 확인하세요.";
            case ThreadDumpRules.LOCK_DNS -> "같은 호스트 이름의 DNS 조회를 기다립니다. 네임 서버 응답 지연과 조회 캐시 설정(networkaddress.cache.ttl)을 확인하세요.";
            case ThreadDumpRules.LOCK_JDBC -> "JDBC 드라이버 객체 하나를 여러 스레드가 함께 씁니다. 커넥션·스테이트먼트를 스레드끼리 공유하지 말고 요청마다 풀에서 받아 쓰세요.";
            default -> "소유 스레드의 스택에서 락을 쥔 채 오래 걸리는 구간을 찾아 임계 구역 밖으로 빼세요.";
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void ruleDiagnostics() {
        if (diag.getIncompleteSnapshots() > 0) {
            Finding f = finding("SNAPSHOT_TRUNCATED", "Low", "끝까지 읽히지 않은 스냅샷 " + diag.getIncompleteSnapshots() + "개");
            f.setDetail("덤프가 도중에 끊겼습니다(파일이 잘렸거나 로그 줄이 끼어들었습니다). 읽힌 스레드까지만 분석했으므로 스레드 수·교착 판정이 실제보다 적을 수 있습니다.");
            f.setAdvice("가능하면 덤프를 다시 받아 주세요. jstack -l <pid> > 파일 로 받으면 잘리지 않습니다.");
        }
        if (diag.getLimitHits().contains("BYTES") || diag.getLimitHits().contains("GZIP_TRUNCATED")) {
            Finding f = finding("FILE_READ_INCOMPLETE", "Low", "파일을 끝까지 읽지 못했습니다");
            f.setDetail(diag.getLimitHits().contains("BYTES")
                    ? "압축 해제 기준 크기 상한에 도달해 그 뒤는 읽지 않았습니다. 뒤쪽 스냅샷이 빠졌을 수 있습니다."
                    : "gzip 스트림이 도중에 끊겨 그 앞까지만 읽었습니다(전송 중 잘린 파일).");
            f.setAdvice("상한은 threaddump.max-file-bytes 설정입니다. 필요한 구간만 잘라 다시 올리세요.");
        }
        if (!diag.getMembersWithoutDump().isEmpty()) {
            Finding f = finding("MEMBER_NO_DUMP", "Low", "스레드 덤프가 없는 구성 파일 " + diag.getMembersWithoutDump().size() + "개");
            f.setDetail("이어 붙인 파일 중 다음 파일에서는 스레드 덤프를 찾지 못해 빼고 분석했습니다: " + String.join(", ", diag.getMembersWithoutDump()));
            f.setAdvice("구성을 바꾸려면 결과 화면의 '묶음 해제' 후 다시 이어 붙이세요.");
            f.getEvidence().put("files", new ArrayList<>(diag.getMembersWithoutDump()));
        }
        if (diag.getLimitHits().contains("SNAPSHOTS")) {
            Finding f = finding("SNAPSHOT_LIMIT_HIT", "Low", "스냅샷 상한 도달");
            f.setDetail("파일에 스냅샷이 " + r.getMeta().getSnapshotsSeen() + "개 있어 마지막 " + S + "개만 분석했습니다.");
            f.setAdvice("앞쪽 스냅샷이 필요하면 파일을 나눠 올리세요.");
        }
        if (diag.getLimitHits().contains("THREADS_PER_SNAPSHOT")) {
            int skipped = 0;
            for (ParsedSnapshot s : snaps) skipped += s.skippedThreads;
            Finding f = finding("THREAD_LIMIT_HIT", "Low", "스냅샷당 스레드 상한 도달");
            f.setDetail("스레드가 상한을 넘어 " + skipped + "개를 건너뛰었습니다. 건너뛴 스레드는 집계와 락 그래프에 들어가지 않았습니다.");
            f.setAdvice("상한은 threaddump.max-threads-per-snapshot 설정입니다.");
        }
        if (diag.getUnparsedLines() > 0) {
            long dump = Math.max(1, diag.getDumpLines());
            boolean many = diag.getUnparsedLines() > dump * 0.05;
            Finding f = finding("LINES_UNPARSED", many ? "Low" : "Info", "해석하지 못한 줄 " + diag.getUnparsedLines() + "개");
            f.setDetail("덤프 구간 안에서 문법에 맞지 않는 줄을 건너뛰었습니다(로그 줄이 끼어들었거나 줄 앞에 접두가 붙은 경우). 파싱 진단에서 원문을 볼 수 있습니다.");
            f.setAdvice("");
        }
        if (heapDerived) {
            Finding f = finding("HEAP_DERIVED", "Info", "힙 덤프에서 추출한 스레드 정보입니다");
            StringBuilder sb = new StringBuilder("힙 덤프를 만든 한 시점의 스레드 상태·스택만 있습니다. 스냅샷 간 비교(정체·CPU·증가 추세), "
                    + "synchronized 락의 소유·대기 관계, JVM 의 교착 보고, JVM 내부 스레드(GC·컴파일러)는 힙 덤프에 남지 않아 판정하지 않았습니다.");
            if (diag.getHeapNotAliveThreads() > 0) sb.append(" 살아 있지 않은(NEW·TERMINATED) Thread 객체 ").append(diag.getHeapNotAliveThreads()).append("개는 뺐습니다.");
            if (diag.getHeapThreadsWithoutState() > 0) sb.append(" 이 MAT 결과에는 상태 열이 없어 ").append(diag.getHeapThreadsWithoutState()).append("개 스레드의 상태를 알 수 없습니다.");
            f.setDetail(sb.toString());
            f.setAdvice("멈춘 스레드·락 경합까지 보려면 같은 JVM 에서 jstack -l 로 5~10초 간격 3회 받은 스레드 덤프를 올려 이 힙 덤프와 연결하세요.");
        }
        if (!heapDerived && S > 0 && "none".equals(r.getMeta().getTimeSource())) {
            Finding f = finding("NO_TIMESTAMP", "Info", "덤프에 시각 정보 없음");
            f.setDetail("스냅샷 앞에 타임스탬프 줄이 없습니다." + ("elapsed".equals(r.getMeta().getIntervalSource())
                    ? " 스냅샷 간격은 스레드의 elapsed 값 차이로 추정했습니다." : " 스냅샷 간격을 알 수 없어 지속 시간은 스냅샷 개수로만 표시합니다."));
            f.setAdvice("");
        }
        if (S == 1 && !heapDerived) {
            Finding f = finding("SINGLE_SNAPSHOT", "Info", "스냅샷이 1개뿐입니다");
            f.setDetail("한 시점만으로는 스레드가 멈춰 있는지 지나가는 중인지 구분할 수 없습니다. 정체·증가 추세 판정은 건너뛰었고, CPU 는 구간 값 대신 생성 이후 평균으로만 봤습니다(JDK 11 이상).");
            f.setAdvice("5~10초 간격으로 3회 이상 받은 덤프를 한 파일에 이어 붙여 올리면 스냅샷 간 비교가 됩니다.");
        }
        if (anyJucWaiter && !anyOwnsInfo) {
            Finding f = finding("NO_LOCK_OWNER_INFO", "Info", "ReentrantLock 소유자 정보 없음");
            f.setDetail("java.util.concurrent 락을 기다리는 스레드가 있지만 덤프에 소유 정보(Locked ownable synchronizers)가 없어 누가 쥐고 있는지 알 수 없습니다.");
            f.setAdvice("jstack -l <pid> 또는 jcmd <pid> Thread.print -l 로 받으면 소유 정보가 포함됩니다.");
        }
        if (r.getMeta().getRuns() > 1) {
            Finding f = finding("RESTART_BETWEEN_SNAPSHOTS", "Info", "스냅샷 사이에 JVM 재기동");
            f.setDetail("파일 안의 스냅샷이 " + r.getMeta().getRuns() + "개의 서로 다른 JVM 실행에 걸쳐 있습니다. 스냅샷 간 비교는 같은 실행 안에서만 했습니다.");
            f.setAdvice("");
        }
        for (ThreadDumpResult.RuleFailure rf : diag.getRuleFailures()) {
            Finding f = finding("RULE_FAILED", "Info", "분석 규칙 일부 실패 — " + rf.getRule());
            f.setDetail("규칙 \"" + rf.getRule() + "\" 가 오류로 건너뛰어졌습니다(" + rf.getError() + "). 나머지 분석은 정상입니다.");
            f.setAdvice("같은 파일로 반복되면 관리자에게 알려 주세요. 서버 로그에 상세 내역이 남습니다.");
        }
    }

    // ══ 결과 조립 ═══════════════════════════════════════════════

    private void buildResultBody() {
        // 사전 재색인 — 결과에 담기는 스택만, 깊이는 STACK_DEPTH 까지
        Map<String, Integer> nameIdx = new HashMap<>();
        int[] stackMap = new int[table.stackCount()];
        Arrays.fill(stackMap, -1);
        Map<Integer, Integer> frameMap = new HashMap<>();
        int pi = 0;
        for (PoolB p : pools.values()) p.idx = pi++;

        for (TrackB tb : tracks) {
            ThreadDumpResult.Track t = new ThreadDumpResult.Track();
            ParsedThread any = tb.any();
            Integer ni = nameIdx.get(any.name);
            if (ni == null) { ni = r.getNames().size(); r.getNames().add(any.name); nameIdx.put(any.name, ni); }
            t.setN(ni);
            t.setNum(any.num >= 0 ? any.num : null);
            t.setTid(any.tid);
            t.setNid(any.nid);
            t.setD(any.daemon);
            t.setSys(tb.sys);
            t.setP(tb.pool == null ? -1 : tb.pool.idx);
            t.setFirst(tb.first);
            StringBuilder s = new StringBuilder(tb.last - tb.first + 1);
            List<Integer> k = new ArrayList<>(tb.last - tb.first + 1);
            List<Double> cpu = null;
            for (int i = tb.first; i <= tb.last; i++) {
                ParsedThread p = tb.at[i];
                if (p == null) { s.append(ThreadDumpResult.ST_ABSENT); k.add(-1); if (cpu != null) cpu.add(null); continue; }
                s.append(p.state);
                k.add(mapStack(p.stackId, stackMap, frameMap));
                if (!Double.isNaN(p.cpuMs)) {
                    if (cpu == null) { cpu = new ArrayList<>(); for (int z = tb.first; z < i; z++) cpu.add(null); }
                    cpu.add(p.cpuMs);
                } else if (cpu != null) cpu.add(null);
            }
            t.setS(s.toString());
            t.setK(k);
            t.setCpu(cpu);
            ParsedThread fp = focus < S ? tb.at[focus] : null;
            if (fp != null) {
                t.setIdle(isFree(tb, fp));
                t.setNat(fp.nativeStatus);
                if (fp.ops != null) {
                    List<ThreadDumpResult.Op> ops = new ArrayList<>(fp.ops.size());
                    for (LockOp op : fp.ops) {
                        if (op.at() > STACK_DEPTH) continue;
                        ThreadDumpResult.Op o = new ThreadDumpResult.Op();
                        o.setAt(op.at());
                        o.setV(op.verb());
                        o.setA(op.addr());
                        o.setC(op.cls());
                        ops.add(o);
                    }
                    if (!ops.isEmpty()) t.setOps(ops);
                }
            }
            r.getTracks().add(t);
        }

        for (PoolB p : pools.values()) {
            ThreadDumpResult.Pool o = new ThreadDumpResult.Pool();
            o.setStem(p.stem);
            o.setKind(p.worker ? "worker" : "other");
            for (int i = 0; i < S; i++) { o.getSizes().add(p.size[i]); o.getIdle().add(p.idle[i]); o.getActive().add(p.size[i] - p.idle[i]); }
            r.getPools().add(o);
        }

        if (focus < S) {
            List<LockB> fl = new ArrayList<>();
            for (LockB l : locks.get(focus)) if (!l.waiters.isEmpty()) fl.add(l);
            fl.sort((a, b) -> b.waiters.size() - a.waiters.size());
            for (LockB l : fl) {
                if (r.getLocks().size() >= LOCKS_CAP) break;
                ThreadDumpResult.LockInfo o = new ThreadDumpResult.LockInfo();
                o.setSnap(focus);
                o.setAddr(l.addr);
                o.setCls(l.cls);
                o.setKind(l.juc ? "juc" : "monitor");
                o.setOwner(l.owner == null ? -1 : l.owner.idx);
                o.setWaiterCount(l.waiters.size());
                for (TrackB w : l.waiters) { if (o.getWaiters().size() >= WAITERS_CAP) break; o.getWaiters().add(w.idx); }
                o.setRootBlocker(l.rootBlocker == null ? -1 : l.rootBlocker.idx);
                r.getLocks().add(o);
            }
        }

        for (DeadlockB d : deadlocks) {
            ThreadDumpResult.Deadlock o = new ThreadDumpResult.Deadlock();
            o.setSnap(d.snap);
            o.setSource(d.source);
            for (TrackB t : d.cycle) o.getTracks().add(t.idx);
            for (String[] c : d.chain) {
                ThreadDumpResult.Chain ch = new ThreadDumpResult.Chain();
                ch.setName(c[0]);
                ch.setAddr(c[1]);
                ch.setCls(c[2]);
                ch.setHeldByName(c[3]);
                TrackB a = findByName(d.snap, c[0]), b = c[3] == null ? null : findByName(d.snap, c[3]);
                ch.setT(a == null ? -1 : a.idx);
                ch.setHeldBy(b == null ? -1 : b.idx);
                o.getChain().add(ch);
            }
            o.setRaw(d.raw.size() > 40 ? new ArrayList<>(d.raw.subList(0, 40)) : d.raw);
            r.getDeadlocks().add(o);
        }

        for (StuckB sb : stuck) {
            if (r.getStuck().size() >= STUCK_CAP) break;
            ThreadDumpResult.Stuck o = new ThreadDumpResult.Stuck();
            o.setT(sb.t.idx);
            o.setStackId(mapStack(sb.stackId, stackMap, frameMap));
            o.setFrom(sb.from);
            o.setTo(sb.to);
            o.setDurationSec(sb.durationSec);
            o.setCategory(deadlocked.contains(sb.t) ? "deadlock" : sb.category);
            o.setCpuDeltaMs(sb.cpuDeltaMs);
            r.getStuck().add(o);
        }
        this.stackMap = stackMap;
        this.frameMap = frameMap;
    }

    private int[] stackMap;
    private Map<Integer, Integer> frameMap;

    private int mapStack(int sid, int[] stackMap, Map<Integer, Integer> frameMap) {
        if (sid < 0) return -1;
        if (stackMap[sid] >= 0) return stackMap[sid];
        int[] f = table.framesOf(sid);
        ThreadDumpResult.Stack st = new ThreadDumpResult.Stack();
        int keep = Math.min(f.length, STACK_DEPTH);
        for (int i = 0; i < keep; i++) {
            Integer fi = frameMap.get(f[i]);
            if (fi == null) { fi = r.getFrames().size(); r.getFrames().add(table.frameText(f[i])); frameMap.put(f[i], fi); }
            st.getF().add(fi);
        }
        st.setCut(f.length - keep);
        int id = r.getStacks().size();
        r.getStacks().add(st);
        stackMap[sid] = id;
        return id;
    }

    /** focus 스냅샷의 스택 그룹·상위 프레임. */
    private void buildViews() {
        if (focus >= S) return;
        List<ParsedThread> th = snaps.get(focus).threads;
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        Map<String, Integer> topFrames = new HashMap<>();
        for (int j = 0; j < th.size(); j++) {
            ParsedThread t = th.get(j);
            if (!t.javaThread() || t.stackId < 0) continue;
            groups.computeIfAbsent(t.stackId, x -> new ArrayList<>()).add(j);
            if (t.state == ThreadDumpResult.ST_RUNNABLE && !trackOf[focus][j].sys) {
                ThreadDumpRules.Traits x = tr(t);
                if (!x.listen && !x.socketRead) topFrames.merge(table.top(t.stackId), 1, Integer::sum);
            }
        }
        List<Map.Entry<Integer, List<Integer>>> gl = new ArrayList<>(groups.entrySet());
        gl.sort((a, b) -> b.getValue().size() - a.getValue().size());
        for (Map.Entry<Integer, List<Integer>> e : gl) {
            if (r.getStackGroups().size() >= 50) break;
            ThreadDumpResult.StackGroup g = new ThreadDumpResult.StackGroup();
            g.setStackId(mapStack(e.getKey(), stackMap, frameMap));
            g.setCount(e.getValue().size());
            g.setState(stateName(th.get(e.getValue().get(0)).state));
            for (int j : e.getValue()) { if (g.getSample().size() >= 5) break; g.getSample().add(trackOf[focus][j].idx); }
            r.getStackGroups().add(g);
        }
        List<Map.Entry<String, Integer>> tf = new ArrayList<>(topFrames.entrySet());
        tf.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(30, tf.size()); i++) {
            ThreadDumpResult.TopFrame o = new ThreadDumpResult.TopFrame();
            o.setFrame(tf.get(i).getKey());
            o.setCount(tf.get(i).getValue());
            r.getTopFrames().add(o);
        }
    }

    private void finishKpi() {
        ThreadDumpResult.Kpi k = r.getKpi();
        int peak = 0, blockedMax = 0;
        for (ThreadDumpResult.Snapshot s : r.getSnapshots()) { peak = Math.max(peak, s.getThreads()); blockedMax = Math.max(blockedMax, s.getBlocked()); }
        k.setThreadsPeak(peak);
        k.setThreadsLast(S == 0 ? 0 : r.getSnapshots().get(focus).getThreads());
        k.setBlockedMax(blockedMax);
        k.setDeadlocks(deadlocks.size());
        int stuckCount = 0;
        for (StuckB s : stuck) if (!deadlocked.contains(s.t)) stuckCount++;
        k.setStuck(stuckCount);
        int topWaiters = 0;
        if (locks != null) for (List<LockB> ls : locks) for (LockB l : ls) topWaiters = Math.max(topWaiters, l.waiters.size());
        k.setTopLockWaiters(topWaiters);
        int max = 0, count = 0;
        for (Finding f : r.getFindings()) {
            int lv = severityLevel(f.getSeverity());
            if (lv > 0) count++;
            max = Math.max(max, lv);
        }
        k.setFindingCount(count);
        k.setSeverity(SEVERITY[max]);
        // 등급 내림차순. 같은 등급 안에서는 종합 진단이 이은 순서(증상 → 원인)를 앞에 둔다 — 카드를 위에서부터 읽으면 진단의 흐름이 된다
        List<String> chain = r.getVerdict() == null ? List.of() : r.getVerdict().getCodes();
        r.getFindings().sort((a, b) -> {
            int d = severityLevel(b.getSeverity()) - severityLevel(a.getSeverity());
            if (d != 0) return d;
            int ia = chain.indexOf(a.getCode()), ib = chain.indexOf(b.getCode());
            return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);   // 나머지는 규칙 실행 순서 그대로(안정 정렬)
        });
    }

    // ══ 헬퍼 ════════════════════════════════════════════════════

    private Finding finding(String code, String severity, String title) {
        Finding f = new Finding();
        f.setCode(code);
        f.setSeverity(severity);
        f.setTitle(title);
        f.setDetail("");
        f.setAdvice("");
        r.getFindings().add(f);
        return f;
    }

    private static void addTrack(Finding f, TrackB t) {
        if (f.getTracks().size() < FINDING_TRACKS && !f.getTracks().contains(t.idx)) f.getTracks().add(t.idx);
    }

    static int severityLevel(String s) {
        for (int i = 0; i < SEVERITY.length; i++) if (SEVERITY[i].equals(s)) return i;
        return 0;
    }

    static String stateName(char c) {
        return switch (c) {
            case ThreadDumpResult.ST_RUNNABLE -> "RUNNABLE";
            case ThreadDumpResult.ST_BLOCKED -> "BLOCKED";
            case ThreadDumpResult.ST_WAITING -> "WAITING";
            case ThreadDumpResult.ST_TIMED -> "TIMED_WAITING";
            case ThreadDumpResult.ST_NEW -> "NEW";
            case ThreadDumpResult.ST_TERMINATED -> "TERMINATED";
            case ThreadDumpResult.ST_VM -> "VM";
            default -> "UNKNOWN";
        };
    }

    /** {@code VM (25.422-b05 …)} → 8, {@code VM (21.0.12.1+1-LTS …)} → 21. */
    static Integer jdkMajor(String vmLine) {
        if (vmLine == null) return null;
        Matcher m = VM_VERSION.matcher(vmLine);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group(1));
        if (LEGACY_VERSION.matcher(vmLine).find()) return n >= 20 ? n - 17 : null;   // HotSpot 25 = JDK 8, 24 = JDK 7
        return n;
    }

    private static String shortClass(String cls) {
        if (cls == null) return "알 수 없는 클래스";
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    private static String pct(double ratio) { return round1(ratio * 100) + "%"; }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}

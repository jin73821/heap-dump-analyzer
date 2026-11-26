package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HotSpot 스레드 덤프(jstack · kill -3 · jcmd Thread.print) 줄 단위 상태 기계.
 *
 * <p>서버 stdout 에 섞인 덤프도 읽는다 — 스냅샷은 {@code Full thread dump} 줄에서 시작해 {@code JNI global ref…} 트레일러
 * (+ JVM 데드락 섹션, kill -3 의 {@code Heap} 섹션)에서 끝난다. 헤더 없이 스레드 줄만 있는 조각은 스레드가 3개 이상일 때만
 * 스냅샷으로 받는다(로그에 인용된 한두 줄을 덤프로 오인하지 않게).
 *
 * <p><b>실패시키지 않는다.</b> 해석 못 한 줄·잘린 스레드·미지의 상태는 {@link ThreadDumpResult.Diagnostics} 에 세기만 하고
 * 읽힌 만큼으로 결과를 만든다. 줄은 보관하지 않는다(스택은 {@link StackTable} 에 사전화).
 */
final class ThreadDumpParser {

    static final int MAX_FRAMES = 512;
    static final int UNPARSED_SAMPLES = 20;
    static final int UNPARSED_SAMPLE_CHARS = 200;
    /** 스레드 구간에서 문법에 없는 줄이 이만큼 이어지면 덤프가 로그에 끊긴 것으로 보고 스냅샷을 닫는다. */
    static final int INTERRUPT_AFTER = 30;
    static final int IMPLICIT_MIN_THREADS = 3;
    static final int OOM_LINE_SAMPLES = 20;

    // ── 문법 ────────────────────────────────────────────────────
    private static final String HEADER_MARK = "Full thread dump ";
    private static final Pattern THREAD_HEADER = Pattern.compile("^\"(.*)\"((?: [^\"]*?)?) tid=(0x[0-9a-fA-F]+) nid=(\\S+)(.*)$");
    private static final Pattern A_NUM = Pattern.compile("(?:^| )#(\\d+)");
    private static final Pattern A_PRIO = Pattern.compile("(?:^| )prio=(-?\\d+)");
    private static final Pattern A_CPU = Pattern.compile("(?:^| )cpu=([\\d.]+)ms");
    private static final Pattern A_ELAPSED = Pattern.compile("(?:^| )elapsed=([\\d.]+)s");
    private static final Pattern STATE = Pattern.compile("^java\\.lang\\.Thread\\.State: ([A-Z_]+)(?: \\(([^)]*)\\))?");
    private static final Pattern FRAME = Pattern.compile("^at ([^\\s(]+)\\(([^)]*)\\)\\s*$");
    private static final Pattern MODULE_VERSION = Pattern.compile("^[A-Za-z0-9_.]+@[^/]+/");
    private static final Pattern LOCK = Pattern.compile(
            "^- (locked|waiting to lock|waiting on|parking to wait for|waiting to re-lock in wait\\(\\)|eliminated)\\s+<([^>]+)>(?: \\(a (.+)\\))?\\s*$");
    private static final Pattern OWNED_SYNC = Pattern.compile("^- <(0x[0-9a-fA-F]+)> \\(a (.+)\\)\\s*$");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern JNI = Pattern.compile("^JNI global ref(?:erences|s): (\\d+)");
    private static final Pattern DEADLOCK_START = Pattern.compile("^Found (?:one|\\d+) Java-level deadlocks?:");
    private static final Pattern DEADLOCK_END = Pattern.compile("^Found \\d+ deadlocks?\\.");
    private static final Pattern DL_NAME = Pattern.compile("^\"(.*)\":\\s*$");
    private static final Pattern DL_WAIT_MONITOR = Pattern.compile("waiting to lock monitor \\S+ \\(object (0x[0-9a-fA-F]+), an? (.+)\\),?\\s*$");
    private static final Pattern DL_WAIT_SYNC = Pattern.compile("waiting for ownable synchronizer (0x[0-9a-fA-F]+),? \\(an? (.+)\\),?\\s*$");
    private static final Pattern DL_HELD = Pattern.compile("which is held by \"(.*)\"\\s*$");
    /** 컨테이너·journald 가 줄 앞에 붙이는 접두 — 이 두 형태만 떼어 준다(그 밖의 접두는 해석 못 한 줄로 드러낸다). */
    private static final Pattern PREFIX_CRI = Pattern.compile("^\\S+ (?:stdout|stderr) [FP] ");
    private static final Pattern PREFIX_JOURNALD = Pattern.compile("^\\w{3} +\\d+ [\\d:]+ \\S+ \\S+: ");

    private enum Mode { OUTSIDE, THREADS, SMR, AFTER_TRAILER, DEADLOCK, HEAP }

    // ── 파싱 결과(메모리 안 표현) ───────────────────────────────
    record LockOp(int at, String verb, String addr, String cls) {}

    static final class ParsedThread {
        String name;
        long num = -1;
        String tid;
        String nid;
        boolean daemon;
        double cpuMs = Double.NaN;
        double elapsedSec = Double.NaN;
        String nativeStatus;
        char state = ThreadDumpResult.ST_VM;
        boolean hasState;
        int stackId = -1;
        List<LockOp> ops;
        int line;

        boolean javaThread() { return hasState; }
    }

    static final class JvmDeadlock {
        /** {스레드 이름, 락 주소, 락 클래스, 소유 스레드 이름}. */
        final List<String[]> chain = new ArrayList<>();
        final List<String> raw = new ArrayList<>();
    }

    static final class ParsedSnapshot {
        int seq;
        /** 이어 붙인 분석에서 이 스냅샷이 나온 파일(파일 하나짜리 분석이면 null). */
        String file;
        Long ts;
        String tsText;
        String vmLine;
        int firstLine;
        int lastLine;
        final List<ParsedThread> threads = new ArrayList<>();
        Integer jniRefs;
        boolean complete;
        String incompleteReason;
        boolean headerless;
        int skippedThreads;
        final List<JvmDeadlock> jvmDeadlocks = new ArrayList<>();
    }

    // ── 상태 ────────────────────────────────────────────────────
    private final ThreadDumpLimits limits;
    private final StackTable table;
    /** 이어 붙인 분석에서 지금 읽는 파일 이름 — 스냅샷·해석 못 한 줄에 붙인다. 단일 파일이면 null. */
    private String fileName;
    private final ThreadDumpResult.Diagnostics diag;
    private final ArrayDeque<ParsedSnapshot> snapshots = new ArrayDeque<>();
    private final Map<String, String> interned = new HashMap<>();
    private final int[] frameBuf = new int[MAX_FRAMES];

    private Mode mode = Mode.OUTSIDE;
    private ParsedSnapshot snap;
    private ParsedThread cur;
    private int frameLen;
    private boolean skipping;
    private boolean inOwnedSync;
    private int strangeRun;
    private int strangeRunFirstLine;
    private String prevNonBlank;
    private Pattern prefix;
    private int snapshotsSeen;
    private int firstDumpLine = -1;
    private JvmDeadlock curDeadlock;
    private String[] curChain;
    private long outsideNonBlank;
    private long oomSeen;
    private long dumpLines;

    ThreadDumpParser(ThreadDumpLimits limits, StackTable table, ThreadDumpResult.Diagnostics diag) {
        this.limits = limits;
        this.table = table;
        this.diag = diag;
    }

    List<ParsedSnapshot> snapshots() { return new ArrayList<>(snapshots); }
    void setFileName(String fileName) { this.fileName = fileName; }
    int snapshotsSeen() { return snapshotsSeen; }
    int firstDumpLine() { return firstDumpLine; }
    long outsideNonBlank() { return outsideNonBlank; }
    long oomSeen() { return oomSeen; }
    int threadsSoFar() {
        int n = snap == null ? 0 : snap.threads.size();
        for (ParsedSnapshot s : snapshots) n += s.threads.size();
        return n;
    }

    // ── 줄 공급 ─────────────────────────────────────────────────
    void feedLine(int lineNo, String rawLine) {
        String line = rawLine;
        if (prefix != null) {
            Matcher pm = prefix.matcher(line);
            if (pm.lookingAt()) line = line.substring(pm.end());
        }
        int mark = line.indexOf(HEADER_MARK);
        if (mark >= 0 && line.indexOf(" VM (", mark) > 0) {
            String before = line.substring(0, mark);
            if (!before.isBlank() && prefix == null) {
                if (PREFIX_CRI.matcher(before).matches()) prefix = PREFIX_CRI;
                else if (PREFIX_JOURNALD.matcher(before).matches()) prefix = PREFIX_JOURNALD;
                diag.setDecoratedPrefix(clip(before.strip(), 80));
            }
            startSnapshot(lineNo, line.substring(mark), false);
            return;
        }
        boolean blank = line.isBlank();
        switch (mode) {
            case OUTSIDE -> outside(lineNo, line, blank);
            case THREADS -> threads(lineNo, line, blank);
            case SMR -> { dumpLines++; if (blank) mode = Mode.THREADS; }
            case AFTER_TRAILER -> afterTrailer(lineNo, line, blank);
            case DEADLOCK -> deadlock(lineNo, line, blank);
            case HEAP -> {
                if (blank || !Character.isWhitespace(line.charAt(0))) {
                    closeSnapshot(lineNo - 1);
                    if (!blank) outside(lineNo, line, false);
                } else {
                    dumpLines++;
                    snap.lastLine = lineNo;
                }
            }
        }
        if (!blank) prevNonBlank = line;
    }

    void finish(int lastLineNo) {
        if (snap == null) return;
        if (mode == Mode.THREADS || mode == Mode.SMR) {
            // 파일이 덤프 도중에 끝났다 — 헤더만 읽힌 마지막 Java 스레드는 버린다
            if (cur != null && !cur.hasState && cur.num >= 0 && frameLen == 0) {
                cur = null;
                diag.setIncompleteThreads(diag.getIncompleteThreads() + 1);
            }
            snap.incompleteReason = "EOF";
        } else if (mode == Mode.DEADLOCK) {
            endDeadlockGroup();
            snap.incompleteReason = snap.complete ? null : "EOF";
        }
        closeSnapshot(lastLineNo);
    }

    // ── 스냅샷 밖 ───────────────────────────────────────────────
    private void outside(int lineNo, String line, boolean blank) {
        if (blank) return;
        if (line.charAt(0) == '"') {
            Matcher m = THREAD_HEADER.matcher(line);
            if (m.matches()) {
                // 헤더 없는 덤프 — 일단 받아 두고 닫을 때 스레드 수로 판정한다
                startSnapshot(lineNo, null, true);
                beginThread(lineNo, m);
                return;
            }
        }
        if (TIMESTAMP.matcher(line).matches()) return;   // 다음 덤프의 시각 줄일 수 있다 — prevNonBlank 로 남는다
        outsideNonBlank++;
        diag.setNoiseLines(diag.getNoiseLines() + 1);
        if (line.contains("java.lang.OutOfMemoryError")) {
            oomSeen++;
            if (diag.getOomLines().size() < OOM_LINE_SAMPLES) diag.getOomLines().add(clip(line.strip(), 300));
        }
    }

    private void startSnapshot(int lineNo, String vmLine, boolean headerless) {
        if (snap != null) {
            if (mode == Mode.THREADS || mode == Mode.SMR) snap.incompleteReason = "NEXT_DUMP";
            if (mode == Mode.DEADLOCK) endDeadlockGroup();
            closeSnapshot(lineNo - 1);
        }
        snap = new ParsedSnapshot();
        snap.seq = snapshotsSeen;          // 버려질 수도 있으므로 닫을 때 확정한다
        snap.file = fileName;
        snap.firstLine = lineNo;
        snap.lastLine = lineNo;
        snap.headerless = headerless;
        snap.vmLine = vmLine == null ? null : clip(vmLine.strip(), 200);
        if (!headerless && prevNonBlank != null) {
            String p = prevNonBlank;
            if (prefix != null) { Matcher pm = prefix.matcher(p); if (pm.lookingAt()) p = p.substring(pm.end()); }
            Long ts = parseTimestamp(p.strip());
            if (ts != null) { snap.ts = ts; snap.tsText = p.strip(); }
        }
        if (firstDumpLine < 0) firstDumpLine = lineNo;
        mode = Mode.THREADS;
        cur = null;
        frameLen = 0;
        skipping = false;
        inOwnedSync = false;
        strangeRun = 0;
        prevNonBlank = null;
        dumpLines++;
    }

    // ── 스레드 구간 ─────────────────────────────────────────────
    private void threads(int lineNo, String line, boolean blank) {
        if (blank) { dumpLines++; return; }
        char c0 = line.charAt(0);
        if (c0 == '"') {
            Matcher m = THREAD_HEADER.matcher(line);
            if (m.matches()) { grammar(lineNo); beginThread(lineNo, m); return; }
            strange(lineNo, line);
            return;
        }
        if (Character.isWhitespace(c0)) {
            String t = line.strip();
            if (t.startsWith("at ")) {
                Matcher m = FRAME.matcher(t);
                if (!m.matches()) { strange(lineNo, line); return; }
                grammar(lineNo);
                if (cur != null && !skipping && frameLen < MAX_FRAMES) {
                    String loc = MODULE_VERSION.matcher(m.group(2)).replaceFirst("");
                    frameBuf[frameLen++] = table.frame(intern(m.group(1) + "(" + loc + ")"));
                }
                return;
            }
            if (t.startsWith("- ")) {
                grammar(lineNo);
                if (cur == null || skipping) return;
                if (inOwnedSync) {
                    Matcher m = OWNED_SYNC.matcher(t);
                    if (m.matches()) addOp(new LockOp(frameLen, "owns", m.group(1), intern(m.group(2))));
                    else if (!t.equals("- None")) diag.setUnknownLockLines(diag.getUnknownLockLines() + 1);
                    return;
                }
                Matcher m = LOCK.matcher(t);
                if (m.matches()) addOp(new LockOp(frameLen, m.group(1), m.group(2), m.group(3) == null ? null : intern(m.group(3))));
                else diag.setUnknownLockLines(diag.getUnknownLockLines() + 1);
                return;
            }
            if (t.startsWith("java.lang.Thread.State:")) {
                grammar(lineNo);
                if (cur != null && !skipping) {
                    Matcher m = STATE.matcher(t);
                    cur.hasState = true;
                    cur.state = m.find() ? stateCode(m.group(1)) : ThreadDumpResult.ST_UNKNOWN;
                    if (cur.state == ThreadDumpResult.ST_UNKNOWN) diag.setUnknownStates(diag.getUnknownStates() + 1);
                }
                return;
            }
            if (t.startsWith("Locked ownable synchronizers:")) { grammar(lineNo); inOwnedSync = true; return; }
            if (t.startsWith("Carrying virtual thread") || t.startsWith("No compile task") || t.startsWith("Compiling:")
                    || t.startsWith("(in native)")) { grammar(lineNo); return; }
            strange(lineNo, line);
            return;
        }
        if (line.startsWith("Threads class SMR info:")) { grammar(lineNo); endThread(); mode = Mode.SMR; return; }
        Matcher jni = JNI.matcher(line);
        if (jni.find()) {
            grammar(lineNo);
            endThread();
            snap.jniRefs = parseIntSafe(jni.group(1));
            snap.complete = true;
            mode = Mode.AFTER_TRAILER;
            return;
        }
        if (DEADLOCK_START.matcher(line).find()) { grammar(lineNo); endThread(); beginDeadlock(line); return; }
        if (line.equals("Heap")) { grammar(lineNo); endThread(); snap.complete = true; mode = Mode.HEAP; return; }
        strange(lineNo, line);
    }

    private void grammar(int lineNo) {
        strangeRun = 0;
        snap.lastLine = lineNo;
        dumpLines++;
    }

    /** 스냅샷 안인데 문법에 없는 줄. 길게 이어지면 덤프가 끊긴 것으로 본다. */
    private void strange(int lineNo, String line) {
        if (strangeRun == 0) strangeRunFirstLine = lineNo;
        strangeRun++;
        diag.setUnparsedLines(diag.getUnparsedLines() + 1);
        if (diag.getUnparsedSamples().size() < UNPARSED_SAMPLES) {
            diag.getUnparsedSamples().add(new ThreadDumpResult.LineSample(lineNo, clip(line, UNPARSED_SAMPLE_CHARS), fileName));
        }
        if (line.contains("java.lang.OutOfMemoryError")) {
            oomSeen++;
            if (diag.getOomLines().size() < OOM_LINE_SAMPLES) diag.getOomLines().add(clip(line.strip(), 300));
        }
        if (strangeRun >= INTERRUPT_AFTER) {
            // 방금 센 줄들은 덤프가 아니라 로그였다 — 잡음으로 되돌린다
            final int first = strangeRunFirstLine;
            final String fn = fileName;
            diag.setUnparsedLines(diag.getUnparsedLines() - strangeRun);
            diag.setNoiseLines(diag.getNoiseLines() + strangeRun);
            outsideNonBlank += strangeRun;
            diag.getUnparsedSamples().removeIf(s -> s.getLine() >= first && java.util.Objects.equals(s.getFile(), fn));
            snap.incompleteReason = "INTERRUPTED_BY_LOG";
            closeSnapshot(first - 1);
        }
    }

    private void beginThread(int lineNo, Matcher m) {
        endThread();
        inOwnedSync = false;
        if (snap.threads.size() >= limits.maxThreadsPerSnapshot()) {
            skipping = true;
            cur = null;
            snap.skippedThreads++;
            if (!diag.getLimitHits().contains("THREADS_PER_SNAPSHOT")) diag.getLimitHits().add("THREADS_PER_SNAPSHOT");
            return;
        }
        skipping = false;
        ParsedThread t = new ParsedThread();
        t.line = lineNo;
        t.name = intern(clip(m.group(1), 300));
        String attrs = m.group(2) == null ? "" : m.group(2);
        Matcher a = A_NUM.matcher(attrs);
        if (a.find()) t.num = parseLongSafe(a.group(1));
        t.daemon = attrs.contains(" daemon");
        a = A_CPU.matcher(attrs);
        if (a.find()) t.cpuMs = parseDoubleSafe(a.group(1));
        a = A_ELAPSED.matcher(attrs);
        if (a.find()) t.elapsedSec = parseDoubleSafe(a.group(1));
        t.tid = m.group(3);
        t.nid = m.group(4);
        String tail = m.group(5) == null ? "" : m.group(5).strip();
        int br = tail.lastIndexOf('[');
        if (br >= 0 && tail.endsWith("]")) tail = tail.substring(0, br).strip();
        t.nativeStatus = tail.isEmpty() ? null : intern(clip(tail, 60));
        cur = t;
        frameLen = 0;
    }

    private void endThread() {
        if (cur != null) {
            cur.stackId = table.stack(frameBuf, frameLen);
            snap.threads.add(cur);
        }
        cur = null;
        frameLen = 0;
        skipping = false;
        inOwnedSync = false;
    }

    private void addOp(LockOp op) {
        if (cur.ops == null) cur.ops = new ArrayList<>(2);
        if (cur.ops.size() < 64) cur.ops.add(op);
    }

    // ── 트레일러 뒤 ─────────────────────────────────────────────
    private void afterTrailer(int lineNo, String line, boolean blank) {
        if (blank) { dumpLines++; return; }
        if (DEADLOCK_START.matcher(line).find()) { grammar(lineNo); beginDeadlock(line); return; }
        if (line.equals("Heap")) { grammar(lineNo); mode = Mode.HEAP; return; }
        closeSnapshot(snap.lastLine);
        outside(lineNo, line, false);
    }

    // ── JVM 이 보고한 데드락 섹션 ───────────────────────────────
    private void beginDeadlock(String line) {
        endDeadlockGroup();
        mode = Mode.DEADLOCK;
        curDeadlock = new JvmDeadlock();
        curDeadlock.raw.add(clip(line, 300));
        snap.jvmDeadlocks.add(curDeadlock);
        curChain = null;
    }

    private void deadlock(int lineNo, String line, boolean blank) {
        snap.lastLine = lineNo;
        dumpLines++;
        if (DEADLOCK_END.matcher(line).find()) { endDeadlockGroup(); snap.complete = true; mode = Mode.AFTER_TRAILER; return; }
        if (DEADLOCK_START.matcher(line).find()) { beginDeadlock(line); return; }
        if (curDeadlock == null) return;
        if (!blank && curDeadlock.raw.size() < 120) curDeadlock.raw.add(clip(line, 300));
        if (blank) return;
        if (line.startsWith("Java stack information")) { curChain = null; return; }
        Matcher m = DL_NAME.matcher(line);
        if (m.matches()) {
            // 사슬 구간의 이름 줄만 받는다 — 뒤쪽 "Java stack information" 구간에도 같은 모양의 줄이 있다
            boolean inStacks = curDeadlock.raw.stream().anyMatch(r -> r.startsWith("Java stack information"));
            curChain = inStacks ? null : new String[]{intern(m.group(1)), null, null, null};
            return;
        }
        if (curChain == null) return;
        m = DL_WAIT_MONITOR.matcher(line);
        if (!m.find()) m = DL_WAIT_SYNC.matcher(line);
        if (m.find(0)) { curChain[1] = m.group(1); curChain[2] = intern(m.group(2)); return; }
        m = DL_HELD.matcher(line);
        if (m.find()) {
            curChain[3] = intern(m.group(1));
            curDeadlock.chain.add(curChain);
            curChain = null;
        }
    }

    private void endDeadlockGroup() {
        curDeadlock = null;
        curChain = null;
    }

    // ── 스냅샷 닫기 ─────────────────────────────────────────────
    private void closeSnapshot(int lastLine) {
        if (snap == null) return;
        endThread();
        ParsedSnapshot s = snap;
        snap = null;
        mode = Mode.OUTSIDE;
        strangeRun = 0;
        s.lastLine = Math.max(s.firstLine, Math.min(s.lastLine, lastLine));
        if (s.headerless && s.threads.size() < IMPLICIT_MIN_THREADS) {
            // 로그에 인용된 스레드 줄 몇 개 — 덤프로 치지 않는다
            diag.setDiscardedFragments(diag.getDiscardedFragments() + 1);
            diag.setNoiseLines(diag.getNoiseLines() + (s.lastLine - s.firstLine + 1));
            outsideNonBlank += s.threads.size();
            return;
        }
        if (s.complete) s.incompleteReason = null;
        else {
            if (s.incompleteReason == null) s.incompleteReason = "NO_TRAILER";
            diag.setIncompleteSnapshots(diag.getIncompleteSnapshots() + 1);
        }
        if (s.ts == null && !s.headerless) diag.setMissingTimestamps(diag.getMissingTimestamps() + 1);
        s.seq = snapshotsSeen++;
        snapshots.addLast(s);
        if (snapshots.size() > limits.maxSnapshots()) {
            snapshots.pollFirst();
            if (!diag.getLimitHits().contains("SNAPSHOTS")) diag.getLimitHits().add("SNAPSHOTS");
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────
    static char stateCode(String s) {
        return switch (s) {
            case "RUNNABLE" -> ThreadDumpResult.ST_RUNNABLE;
            case "BLOCKED" -> ThreadDumpResult.ST_BLOCKED;
            case "WAITING" -> ThreadDumpResult.ST_WAITING;
            case "TIMED_WAITING" -> ThreadDumpResult.ST_TIMED;
            case "NEW" -> ThreadDumpResult.ST_NEW;
            case "TERMINATED" -> ThreadDumpResult.ST_TERMINATED;
            default -> ThreadDumpResult.ST_UNKNOWN;
        };
    }

    /** HotSpot 기본 시각 줄은 구역이 없는 로컬 시각이다 — 서버와 같은 구역이라 가정한다(다르면 간격만 맞고 절대 시각은 어긋난다). */
    static Long parseTimestamp(String line) {
        Matcher m = TIMESTAMP.matcher(line);
        if (!m.matches()) return null;
        try {
            String frac = m.group(3) == null ? "" : "." + m.group(3);
            String iso = m.group(1) + "T" + m.group(2) + frac;
            String zone = m.group(4);
            if (zone == null) return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (!zone.equals("Z") && zone.indexOf(':') < 0) zone = zone.substring(0, 3) + ":" + zone.substring(3);
            return OffsetDateTime.parse(iso + zone, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return null;   // 모양은 시각인데 값이 틀린 줄(13월 등) — 시각 없음으로 둔다
        }
    }

    private String intern(String s) {
        String old = interned.putIfAbsent(s, s);
        return old == null ? s : old;
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return Double.NaN; }
    }
}

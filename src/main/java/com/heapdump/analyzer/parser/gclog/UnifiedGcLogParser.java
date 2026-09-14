package com.heapdump.analyzer.parser.gclog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 9+ 통합 로깅({@code -Xlog:gc*}) 파서.
 *
 * <p>줄 = 데코레이션 그룹 {@code [..][..]…} + 메시지. 데코레이션은 {@code time,utctime,uptime,timemillis,uptimemillis,
 * timenanos,uptimenanos,hostname,pid,tid,level,tags} 의 <b>부분집합</b>이고 순서만 고정이라, 그룹을 개수·위치가 아니라
 * 내용으로 분류한다(마지막 그룹이 태그). 메시지는 {@code GC(n)} id 로 묶이며 실측 순서(JDK 21 G1)는
 * {@code gc,start → gc,heap → gc,metaspace → gc(완료, 소요 ms) → gc,cpu} 이고 <b>cpu 줄이 완료 줄 뒤에 온다</b>.
 * 그래서 완료 이벤트를 곧바로 내보내지 않고 {@code pending} 에 잠시 들고 있다가 다음 pause 의 start/완료에서 내보낸다.
 * Concurrent 사이클은 pause 와 id 공간을 공유하므로(JDK 17: 같은 GC(n) 아래 Pause Remark/Cleanup) pause 와 별도 맵에 둔다.
 */
public final class UnifiedGcLogParser implements GcLogParser {

    private static final int MAX_OPEN = 64;

    /** 괄호 내용 — {@code (System.gc())} 처럼 한 단계 중첩을 허용한다. */
    private static final String P = "(?:[^()]|\\([^()]*\\))*";
    private static final Pattern GC_ID = Pattern.compile("^GC\\((\\d+)\\) (.*)$");
    private static final Pattern UPTIME_S = Pattern.compile("^(\\d+(?:\\.\\d+)?)s$");
    private static final Pattern MILLIS = Pattern.compile("^(\\d+)ms$");
    private static final Pattern NANOS = Pattern.compile("^(\\d+)ns$");
    private static final Pattern LEVEL = Pattern.compile("^(trace|debug|info|warning|error)$");
    private static final Pattern TAGS = Pattern.compile("^[a-z][a-z,+]*$");

    /**
     * pause/concurrent 메시지: 이름 + 괄호들 + (옵션) 크기 + (옵션) 소요.
     * 예: {@code Pause Young (Normal) (G1 Evacuation Pause) 14M->2M(258M) 3.363ms},
     * {@code Pause Full (Ergonomics) 100M->50M(258M) 120.5ms}, {@code Concurrent Mark Cycle 45.1ms},
     * ZGC {@code Garbage Collection (Allocation Rate) 1024M(50%)->512M(25%)}.
     */
    private static final Pattern EVENT_MSG = Pattern.compile(
            "^(Pause [A-Za-z][A-Za-z ]*?|Concurrent [A-Za-z][A-Za-z ]*?|Garbage Collection|Major Collection|Minor Collection)"
            + "((?: \\(" + P + "\\))*)"
            + "(?:\\s+(\\d+(?:\\.\\d+)?)([KMGTB])(?:\\(\\d+%\\))?->(\\d+(?:\\.\\d+)?)([KMGTB])(?:\\((\\d+(?:\\.\\d+)?)([KMGTB])\\))?(?:\\(\\d+%\\))?)?"
            + "\\s*(?:(\\d+(?:\\.\\d+)?)ms)?\\s*$");
    private static final Pattern PAREN = Pattern.compile("\\((" + P + ")\\)");
    private static final Pattern REGIONS = Pattern.compile("^(Eden|Survivor|Old|Humongous|Archive) regions: (\\d+)->(\\d+)(?:\\((\\d+)\\))?");
    private static final Pattern GEN = Pattern.compile(
            "^(PSYoungGen|ParOldGen|ParNew|CMS|Tenured|DefNew|Metaspace|PSPermGen): (\\d+(?:\\.\\d+)?)([KMGTB])(?:\\((\\d+(?:\\.\\d+)?)([KMGTB])\\))?->(\\d+(?:\\.\\d+)?)([KMGTB])\\((\\d+(?:\\.\\d+)?)([KMGTB])\\)");
    private static final Pattern CPU = Pattern.compile("User=(\\d+(?:\\.\\d+)?)s Sys=(\\d+(?:\\.\\d+)?)s Real=(\\d+(?:\\.\\d+)?)s");
    private static final Pattern USING = Pattern.compile("^Using (.+)$");
    private static final Pattern VERSION = Pattern.compile("^Version: (\\S+)");
    private static final Pattern REGION_SIZE = Pattern.compile("^Heap [Rr]egion [Ss]ize: (\\S+)");
    private static final Pattern HEAP_MAX = Pattern.compile("^Heap Max Capacity: (\\S+)");

    private final GcEventSink sink;
    private final int maxEvents;
    private final Map<Integer, GcEvent> openPauses = new LinkedHashMap<>();
    private final Map<Integer, GcEvent> openConcurrent = new LinkedHashMap<>();
    private GcEvent pending;
    private int pendingId = -1;
    private int seq;
    private int dropped;
    private boolean limit;
    private Long regionSize;
    private boolean collectorReported;
    private final List<String> decorationsSeen = new ArrayList<>();

    public UnifiedGcLogParser(GcEventSink sink, int maxEvents) {
        this.sink = sink;
        this.maxEvents = maxEvents;
    }

    @Override public GcLogFormat format() { return GcLogFormat.UNIFIED; }
    @Override public int droppedIncomplete() { return dropped; }
    @Override public boolean limitReached() { return limit; }

    // ── 줄 처리 ─────────────────────────────────────────────────

    @Override
    public void feedLine(int lineNo, String line) {
        if (limit || line == null || line.isEmpty() || line.charAt(0) != '[') return;
        Deco d = decorate(line);
        if (d == null || d.tags == null) return;
        String msg = d.message;
        if (msg.isEmpty()) return;

        Matcher idm = GC_ID.matcher(msg);
        if (idm.matches()) {
            int id = Integer.parseInt(idm.group(1));
            String rest = idm.group(2);
            switch (d.tags) {
                case "gc,start": onStart(id, rest, d, lineNo, msg); break;
                case "gc": onGc(id, rest, d, lineNo, msg); break;
                case "gc,heap": onHeap(id, rest); break;
                case "gc,metaspace": onMetaspace(id, rest); break;
                case "gc,cpu": onCpu(id, rest); break;
                default: break; // gc,phases / gc,task / gc,marking / gc,ergo … 무시
            }
            return;
        }
        onMetaLine(d.tags, msg);
    }

    @Override
    public void finish() {
        flushPending();
        dropped += openPauses.size() + openConcurrent.size();
        openPauses.clear();
        openConcurrent.clear();
    }

    // ── 데코레이션 ──────────────────────────────────────────────

    private static final class Deco {
        Long tsEpochMs; Double uptimeSec; String tags; String message;
    }

    private Deco decorate(String line) {
        Deco d = new Deco();
        int pos = 0;
        String last = null;
        int groups = 0;
        while (pos < line.length() && line.charAt(pos) == '[') {
            int end = line.indexOf(']', pos);
            if (end < 0) return null;
            String g = line.substring(pos + 1, end).trim();
            pos = end + 1;
            groups++;
            last = g;
            classify(g, d);
        }
        if (groups == 0 || last == null) return null;
        // 마지막 그룹은 태그(gc / gc,heap …). 태그 아닌 무언가로 끝나면 GC 줄이 아니다.
        d.tags = TAGS.matcher(last).matches() ? last : null;
        d.message = line.substring(pos).trim();
        return d;
    }

    private void classify(String g, Deco d) {
        if (g.isEmpty()) return;
        Matcher m;
        if (GcLogSupport.ISO_TS.matcher(g).matches()) {
            Long ts = GcLogSupport.parseIsoEpochMs(g);
            if (ts != null) { d.tsEpochMs = ts; noteDecoration("time"); }
            return;
        }
        if ((m = UPTIME_S.matcher(g)).matches()) {
            d.uptimeSec = Double.parseDouble(m.group(1));
            noteDecoration("uptime");
            return;
        }
        if ((m = MILLIS.matcher(g)).matches()) {
            long v = Long.parseLong(m.group(1));
            if (v > 100_000_000_000L) { if (d.tsEpochMs == null) d.tsEpochMs = v; noteDecoration("timemillis"); }
            else if (d.uptimeSec == null) { d.uptimeSec = v / 1000.0; noteDecoration("uptimemillis"); }
            return;
        }
        if ((m = NANOS.matcher(g)).matches()) {
            long v = Long.parseLong(m.group(1));
            if (v > 100_000_000_000_000_000L) { if (d.tsEpochMs == null) d.tsEpochMs = v / 1_000_000L; noteDecoration("timenanos"); }
            else if (d.uptimeSec == null) { d.uptimeSec = v / 1e9; noteDecoration("uptimenanos"); }
            return;
        }
        if (LEVEL.matcher(g).matches()) { noteDecoration("level"); return; }
        // hostname / pid / tid / tags 는 여기서 구분하지 않는다 — 태그는 마지막 그룹으로 확정
    }

    private void noteDecoration(String name) {
        if (decorationsSeen.size() < 12 && !decorationsSeen.contains(name)) {
            decorationsSeen.add(name);
            sink.onMeta("decorations", String.join(",", decorationsSeen));
        }
    }

    // ── 이벤트 조립 ─────────────────────────────────────────────

    private void onStart(int id, String rest, Deco d, int lineNo, String msg) {
        flushPending();
        Matcher m = EVENT_MSG.matcher(rest);
        if (!m.matches()) return;
        GcEvent ev = newEvent(lineNo, d, msg);
        applyName(ev, m.group(1), m.group(2));
        if (ev.concurrent) { putOpen(openConcurrent, id, ev); return; }
        putOpen(openPauses, id, ev);
    }

    private void onGc(int id, String rest, Deco d, int lineNo, String msg) {
        if (rest.startsWith("To-space exhausted")) {
            GcEvent target = openPauses.get(id);
            if (target == null && pendingId == id) target = pending;
            if (target != null) target.flags.add(GcFlag.TO_SPACE_EXHAUSTED);
            return;
        }
        Matcher m = EVENT_MSG.matcher(rest);
        if (!m.matches()) return;
        String duration = m.group(9);
        boolean concurrentName = isConcurrentName(m.group(1));

        if (duration == null && m.group(3) == null) {
            // 소요 시간도 크기도 없다 → 시작 줄(concurrent 사이클은 gc,start 없이 gc 태그로 시작한다)
            GcEvent ev = newEvent(lineNo, d, msg);
            applyName(ev, m.group(1), m.group(2));
            if (ev.concurrent) putOpen(openConcurrent, id, ev);
            else if (!openPauses.containsKey(id)) putOpen(openPauses, id, ev);
            return;
        }

        if (concurrentName) {
            GcEvent ev = openConcurrent.remove(id);
            if (ev == null) ev = newEvent(lineNo, d, msg);
            applyName(ev, m.group(1), m.group(2));
            applySizes(ev, m);
            ev.pauseMs = GcLogSupport.parseDoubleOrNull(duration);
            ev.concurrent = true;
            ev.text = GcLogSupport.trunc(msg, 200);
            emit(ev);
            return;
        }

        flushPending();
        GcEvent ev = openPauses.remove(id);
        if (ev == null) {
            ev = newEvent(lineNo, d, msg);
            Double p = GcLogSupport.parseDoubleOrNull(duration);
            // 시작 줄이 없으면 완료 시각에서 소요를 빼 시작 시각을 근사한다
            if (p != null && ev.tsEpochMs != null) ev.tsEpochMs = ev.tsEpochMs - (long) Math.floor(p);
            if (p != null && ev.uptimeSec != null) ev.uptimeSec = Math.max(0, ev.uptimeSec - p / 1000.0);
        }
        applyName(ev, m.group(1), m.group(2));
        applySizes(ev, m);
        ev.pauseMs = GcLogSupport.parseDoubleOrNull(duration);
        ev.text = GcLogSupport.trunc(msg, 200);
        pending = ev;
        pendingId = id;
    }

    private void onHeap(int id, String rest) {
        GcEvent ev = target(id);
        if (ev == null) return;
        Matcher m = REGIONS.matcher(rest);
        if (m.find()) {
            int before = Integer.parseInt(m.group(2)), after = Integer.parseInt(m.group(3));
            Integer target = m.group(4) == null ? null : Integer.parseInt(m.group(4));
            switch (m.group(1)) {
                case "Eden":
                case "Survivor":
                    if (regionSize != null) {
                        ev.youngBefore = (ev.youngBefore == null ? 0 : ev.youngBefore) + before * regionSize;
                        ev.youngAfter = (ev.youngAfter == null ? 0 : ev.youngAfter) + after * regionSize;
                        if (target != null) ev.youngTotal = (ev.youngTotal == null ? 0 : ev.youngTotal) + target * regionSize;
                    }
                    break;
                case "Old":
                    if (regionSize != null) { ev.oldBefore = before * regionSize; ev.oldAfter = after * regionSize; }
                    break;
                case "Humongous":
                    ev.humongousRegions = after; // 플래그는 원인(G1 Humongous Allocation)에서만 — 존재만으로 잡으면 매 GC 가 잡힌다
                    break;
                default: break;
            }
            return;
        }
        m = GEN.matcher(rest);
        if (m.find()) {
            Long b = GcLogSupport.parseSize(m.group(2), m.group(3));
            Long a = GcLogSupport.parseSize(m.group(6), m.group(7));
            Long t = GcLogSupport.parseSize(m.group(8), m.group(9));
            switch (m.group(1)) {
                case "PSYoungGen": case "ParNew": case "DefNew": ev.youngBefore = b; ev.youngAfter = a; ev.youngTotal = t; break;
                case "ParOldGen": case "CMS": case "Tenured": ev.oldBefore = b; ev.oldAfter = a; ev.oldTotal = t; break;
                case "Metaspace": case "PSPermGen": ev.metaBefore = b; ev.metaAfter = a; ev.metaTotal = t; break;
                default: break;
            }
        }
    }

    private void onMetaspace(int id, String rest) {
        GcEvent ev = target(id);
        if (ev == null) return;
        Matcher m = GEN.matcher(rest);
        if (m.find() && m.group(1).equals("Metaspace")) {
            ev.metaBefore = GcLogSupport.parseSize(m.group(2), m.group(3));
            ev.metaAfter = GcLogSupport.parseSize(m.group(6), m.group(7));
            ev.metaTotal = GcLogSupport.parseSize(m.group(8), m.group(9));
        }
    }

    private void onCpu(int id, String rest) {
        GcEvent ev = pendingId == id ? pending : openPauses.get(id);
        if (ev == null) return;
        Matcher m = CPU.matcher(rest);
        if (m.find()) {
            ev.userSec = Double.parseDouble(m.group(1));
            ev.sysSec = Double.parseDouble(m.group(2));
            ev.realSec = Double.parseDouble(m.group(3));
        }
    }

    private void onMetaLine(String tags, String msg) {
        Matcher m;
        if (tags.equals("gc") && (m = USING.matcher(msg)).matches()) {
            reportCollector(m.group(1));
            return;
        }
        if (tags.equals("gc,init") && (m = VERSION.matcher(msg)).find()) { sink.onMeta("jdkVersion", m.group(1)); return; }
        if ((tags.equals("gc,init") || tags.equals("gc,heap")) && (m = REGION_SIZE.matcher(msg)).find()) {
            Long rs = GcLogSupport.parseSizeToken(m.group(1));
            if (rs != null && rs > 0) { regionSize = rs; sink.onMeta("regionSize", Long.toString(rs)); }
            return;
        }
        if (tags.equals("gc,init") && (m = HEAP_MAX.matcher(msg)).find()) {
            Long v = GcLogSupport.parseSizeToken(m.group(1));
            if (v != null) sink.onMeta("heapMax", Long.toString(v));
        }
    }

    private void reportCollector(String using) {
        if (collectorReported) return;
        collectorReported = true;
        String u = using.toLowerCase(Locale.ROOT);
        String c;
        if (u.startsWith("g1")) c = "G1";
        else if (u.startsWith("parallel")) c = "Parallel";
        else if (u.startsWith("serial")) c = "Serial";
        else if (u.contains("z garbage")) c = "ZGC";
        else if (u.startsWith("shenandoah")) c = "Shenandoah";
        else if (u.contains("concurrent mark sweep")) c = "CMS";
        else if (u.startsWith("epsilon")) c = "Epsilon";
        else c = using;
        sink.onMeta("collector", c);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private GcEvent target(int id) {
        GcEvent ev = openPauses.get(id);
        if (ev == null && pendingId == id) ev = pending;
        return ev;
    }

    private GcEvent newEvent(int lineNo, Deco d, String msg) {
        GcEvent ev = new GcEvent();
        ev.line = lineNo;
        ev.tsEpochMs = d.tsEpochMs;
        ev.uptimeSec = d.uptimeSec;
        ev.text = GcLogSupport.trunc(msg, 200);
        return ev;
    }

    private static boolean isConcurrentName(String name) {
        return name.startsWith("Concurrent") || name.equals("Garbage Collection")
                || name.equals("Major Collection") || name.equals("Minor Collection");
    }

    private void applyName(GcEvent ev, String name, String parens) {
        List<String> ps = new ArrayList<>();
        Matcher pm = PAREN.matcher(parens == null ? "" : parens);
        while (pm.find()) ps.add(pm.group(1));

        if (isConcurrentName(name)) {
            ev.type = GcEventType.CONCURRENT_CYCLE;
            ev.concurrent = true;
            if (!ps.isEmpty()) ev.cause = ps.get(ps.size() - 1);
            return;
        }
        String n = name.trim();
        if (n.equals("Pause Young")) {
            ev.type = GcEventType.YOUNG;
            for (Iterator<String> it = ps.iterator(); it.hasNext(); ) {
                String p = it.next();
                switch (p) {
                    case "Normal": it.remove(); break;
                    case "Mixed": ev.type = GcEventType.MIXED; it.remove(); break;
                    case "Prepare Mixed": it.remove(); break;
                    case "Concurrent Start": ev.flags.add(GcFlag.INITIAL_MARK); it.remove(); break;
                    default: break;
                }
            }
        } else if (n.equals("Pause Mixed")) {
            ev.type = GcEventType.MIXED;
        } else if (n.equals("Pause Initial Mark")) {
            ev.type = GcEventType.YOUNG;
            ev.flags.add(GcFlag.INITIAL_MARK);
        } else if (n.equals("Pause Full")) {
            ev.type = GcEventType.FULL;
        } else if (n.equals("Pause Remark") || n.equals("Pause Final Mark")) {
            ev.type = GcEventType.REMARK;
        } else if (n.equals("Pause Cleanup")) {
            ev.type = GcEventType.CLEANUP;
        } else if (n.equals("Pause Degenerated GC")) {
            ev.type = GcEventType.FULL;
        } else {
            ev.type = GcEventType.OTHER;
        }
        if (!ps.isEmpty()) ev.cause = ps.get(ps.size() - 1);
        GcLogSupport.flagsFromCause(ev.cause, ev);
    }

    private static void applySizes(GcEvent ev, Matcher m) {
        if (m.group(3) == null) return;
        ev.heapBefore = GcLogSupport.parseSize(m.group(3), m.group(4));
        ev.heapAfter = GcLogSupport.parseSize(m.group(5), m.group(6));
        if (m.group(7) != null) ev.heapTotal = GcLogSupport.parseSize(m.group(7), m.group(8));
    }

    private void putOpen(Map<Integer, GcEvent> map, int id, GcEvent ev) {
        if (map.size() >= MAX_OPEN) {
            Integer oldest = map.keySet().iterator().next();
            map.remove(oldest);
            dropped++;
        }
        map.put(id, ev);
    }

    private void flushPending() {
        if (pending == null) return;
        GcEvent ev = pending;
        pending = null;
        pendingId = -1;
        emit(ev);
    }

    private void emit(GcEvent ev) {
        if (limit) return;
        if (seq >= maxEvents) { limit = true; return; }
        ev.seq = seq++;
        sink.onEvent(ev.freeze());
    }
}

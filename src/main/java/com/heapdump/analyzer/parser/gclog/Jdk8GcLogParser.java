package com.heapdump.analyzer.parser.gclog;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 8 이하 {@code -XX:+PrintGCDetails} 파서 (Parallel / CMS / Serial / G1, {@code PrintGCDateStamps} 유무 무관).
 * JDK 6/7 형식(원인 괄호 없음, PermGen)도 같은 파서가 읽는다 — 그래서 유형은 {@code [GC (CMS Initial Mark)} 같은 원인 괄호가 아니라
 * 본문 마커({@code CMS-initial-mark:}·{@code CMS-remark:}·세대 라벨)로 판정한다.
 *
 * <p>줄 = {@code [ISO시각: ][uptime: ]본문}. 이벤트는 {@code [GC …} / {@code [Full GC …} / {@code [CMS-concurrent-…: …]} 로
 * 열리고 <b>대괄호 깊이가 0 으로 돌아오는 줄</b>에서 닫힌다 — {@code PrintTenuringDistribution} 이 이벤트 중간에
 * {@code Desired survivor size …} 줄을 끼워 넣고, CMS Final Remark 는 한 줄에 시각이 여러 번 박히며, G1 은 닫힌 뒤에도
 * 들여쓴 {@code [Eden: … Heap: …]}·{@code [Times: …]} 트레일러 줄이 따라오므로 단일 정규식으로는 안 된다.
 * 닫힌 이벤트는 {@code pending} 으로 두고 트레일러가 아닌 줄이 오면 내보낸다.
 *
 * <p>크기 트리플 {@code aK->bK(cK)} 는 앞의 라벨({@code [PSYoungGen: } 등)로 세대를 구분하고, 라벨이 없거나
 * {@code Heap:} 이면 힙 총합이다. 세대 라벨은 수집기 판별에도 쓴다(PSYoungGen→Parallel, ParNew/CMS→CMS, DefNew/Tenured→Serial).
 */
public final class Jdk8GcLogParser implements GcLogParser {

    private static final int MAX_EVENT_CHARS = 32 * 1024;
    private static final int MAX_EVENT_LINES = 400;

    /** 괄호 내용 — {@code (System.gc())} 처럼 한 단계 중첩을 허용한다. */
    private static final String P = "(?:[^()]|\\([^()]*\\))*";
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}): )?(?:(\\d+\\.\\d+): )?(.*)$");
    private static final Pattern JRE_HEADER = Pattern.compile("^(?:Java HotSpot\\(TM\\)|OpenJDK) .*JRE \\(([^)]+)\\)");
    private static final Pattern CMDLINE = Pattern.compile("^CommandLine flags: (.*)$");
    private static final Pattern TIMES = Pattern.compile("\\[Times: user=(\\d+(?:\\.\\d+)?) sys=(\\d+(?:\\.\\d+)?), real=(\\d+(?:\\.\\d+)?) secs\\]");
    private static final Pattern OUTER_SECS = Pattern.compile(", (\\d+(?:\\.\\d+)?) secs\\]$");
    private static final Pattern CONC_END = Pattern.compile("^\\[GC concurrent-([\\w-]+?)-end, (\\d+(?:\\.\\d+)?) secs\\]");
    private static final Pattern CMS_CONC = Pattern.compile("^\\[CMS-concurrent-([\\w-]+?): (\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?) secs\\]");
    private static final Pattern G1_PAUSE = Pattern.compile("^\\[GC pause(?: \\((" + P + ")\\))?((?: \\(" + P + "\\))*)");
    private static final Pattern FULL = Pattern.compile("^\\[Full GC(?: \\((" + P + ")\\))?");
    private static final Pattern GC = Pattern.compile("^\\[GC(?:--)?(?: \\((" + P + ")\\))?");
    private static final Pattern PAREN = Pattern.compile("\\((" + P + ")\\)");
    /** {@code 33280K->5100K(38400K)} · {@code 24.0M(24.0M)->10.2M(256.0M)} · {@code 0.0B->4096.0K(…)} */
    private static final Pattern TRIPLE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)([KMGTB]?)(?:\\((\\d+(?:\\.\\d+)?)([KMGTB]?)\\))?->(\\d+(?:\\.\\d+)?)([KMGTB]?)\\((\\d+(?:\\.\\d+)?)([KMGTB]?)\\)");
    private static final Pattern LABEL_BEFORE = Pattern.compile("(?<![A-Za-z0-9.])([A-Za-z][A-Za-z ]*?)(?: \\([^)]*\\))?: $");
    private static final String SIZE = "(\\d+(?:\\.\\d+)?)([KMGTB]?)";
    /** CMS 시작·재마킹 시점의 점유량 {@code [1 CMS-initial-mark: old(oldCap)] heap(heapCap)} — before→after 가 아니라 순간값이다. */
    private static final Pattern CMS_MARK_OCC = Pattern.compile("\\[1 CMS-(?:initial-mark|remark): " + SIZE + "\\(" + SIZE + "\\)\\] " + SIZE + "\\(" + SIZE + "\\)");
    /**
     * GC 이벤트 안에 끼어든 CMS 동시 단계. Old 수집이 동시 사이클 도중에 시작되면 JVM 이 두 출력을 섞어
     * {@code [CMS2026-…: 12.3: [CMS-concurrent-mark: 1.6/1.6 secs] [Times: …]} 뒤 다음 줄에 {@code  (concurrent mode failure): …} 를 쓴다.
     */
    private static final Pattern NESTED_CMS_CONC = Pattern.compile(
            "(?:(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}): )?(?:(\\d+\\.\\d+): )?"
                    + "\\[CMS-concurrent-([\\w-]+?)(?:: (\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?) secs)?\\](?: ?\\[Times: [^\\]]*\\])?");
    /** {@code PrintTenuringDistribution} 출력 — CMS 는 {@code [ParNew} 와 {@code : 크기} 사이에 끼어 세대 라벨을 끊는다. */
    private static final Pattern TENURING = Pattern.compile(
            "Desired survivor size \\d+ bytes, new threshold \\d+ \\(max \\d+\\)|- age +\\d+: +\\d+ bytes, +\\d+ total");

    private final GcEventSink sink;
    private final int maxEvents;

    private final StringBuilder buf = new StringBuilder();
    private int bufLines;
    private int depth;
    private boolean inEvent;
    private Long startTs;
    private Double startUptime;
    private int startLine;

    private GcEvent pending;
    private int seq;
    private int dropped;
    private boolean limit;
    private boolean collectorReported;
    private boolean permGenReported;

    public Jdk8GcLogParser(GcEventSink sink, int maxEvents) {
        this.sink = sink;
        this.maxEvents = maxEvents;
    }

    @Override public GcLogFormat format() { return GcLogFormat.JDK8; }
    @Override public int droppedIncomplete() { return dropped; }
    @Override public boolean limitReached() { return limit; }

    @Override
    public void feedLine(int lineNo, String line) {
        if (limit || line == null) return;
        if (inEvent) {
            append(line);
            if (depth <= 0) closeEvent();
            else if (bufLines > MAX_EVENT_LINES || buf.length() > MAX_EVENT_CHARS) { dropped++; resetBuf(); }
            return;
        }
        Matcher pm = PREFIX.matcher(line);
        if (!pm.matches()) return;
        String body = pm.group(3);
        if (body.isEmpty()) return;

        if (isEventStart(body)) {
            flushPending();
            startTs = GcLogSupport.parseIsoEpochMs(pm.group(1));
            startUptime = GcLogSupport.parseDoubleOrNull(pm.group(2));
            startLine = lineNo;
            inEvent = true;
            append(body);
            if (depth <= 0) closeEvent();
            return;
        }
        if (pending != null && absorbTrailer(body)) return;
        flushPending();
        onOtherLine(body);
    }

    @Override
    public void finish() {
        if (inEvent) { dropped++; resetBuf(); }
        flushPending();
    }

    // ── 이벤트 경계 ─────────────────────────────────────────────

    private static boolean isEventStart(String body) {
        return body.startsWith("[GC") || body.startsWith("[Full GC") || body.startsWith("[CMS-concurrent-");
    }

    private void append(String text) {
        if (buf.length() > 0) buf.append(' ');
        buf.append(text);
        bufLines++;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
        }
    }

    private void resetBuf() {
        buf.setLength(0);
        bufLines = 0;
        depth = 0;
        inEvent = false;
    }

    private void closeEvent() {
        String text = buf.toString();
        Long ts = startTs;
        Double up = startUptime;
        int line = startLine;
        resetBuf();
        if (!text.startsWith("[CMS-concurrent-") && text.indexOf("[CMS-concurrent-") > 0) text = extractNestedConcurrent(text, ts, up, line);
        if (text.indexOf("Desired survivor") >= 0) text = TENURING.matcher(text).replaceAll("");
        GcEvent ev = parseEventText(text, ts, up, line);
        if (ev == null) return;
        if (ev.concurrent) { emit(ev); return; }   // 동시 단계는 트레일러가 없다
        pending = ev;
    }

    /** 끼어든 동시 단계를 별도 이벤트로 내보내고 본문에서 지운다 — 남겨 두면 세대 라벨({@code [CMS})과 크기가 끊기고 안쪽 {@code [Times:} 가 먼저 잡힌다. */
    private String extractNestedConcurrent(String text, Long ts, Double up, int line) {
        Matcher m = NESTED_CMS_CONC.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            last = m.end();
            if (m.group(4) == null) continue;   // -start 는 이벤트가 아니다
            GcEvent c = new GcEvent();
            c.line = line;
            Long cts = GcLogSupport.parseIsoEpochMs(m.group(1));
            Double cup = GcLogSupport.parseDoubleOrNull(m.group(2));
            c.tsEpochMs = cts != null ? cts : ts;
            c.uptimeSec = cup != null ? cup : up;
            c.type = GcEventType.CONCURRENT_CYCLE;
            c.concurrent = true;
            c.cause = "CMS-concurrent-" + m.group(3);
            c.pauseMs = Double.parseDouble(m.group(5)) * 1000.0;
            c.text = GcLogSupport.trunc(m.group(), 200);
            reportCollector("CMS");
            emit(c);
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** 닫힌 이벤트 뒤의 G1 트레일러({@code [Eden: … Heap: …]}, {@code [Times: …]}, 들여쓴 세부 줄)를 pending 에 흡수. */
    private boolean absorbTrailer(String body) {
        String t = body.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("[Times:")) { applyTimes(pending, t); return true; }
        if (t.startsWith("[Eden:") || t.contains("Heap: ")) { applySizes(pending, t); return true; }
        if (t.startsWith("[Metaspace:")) { applySizes(pending, t); return true; }
        if (t.startsWith("Desired survivor") || t.startsWith("- age")) return true;
        return body.startsWith(" ") && t.startsWith("[");
    }

    private void onOtherLine(String body) {
        Matcher m = JRE_HEADER.matcher(body);
        if (m.find()) { sink.onMeta("jdkVersion", m.group(1)); return; }
        m = CMDLINE.matcher(body);
        if (m.matches()) sink.onMeta("jvmOptions", GcLogSupport.trunc(m.group(1), 4000));
    }

    // ── 본문 해석 ───────────────────────────────────────────────

    GcEvent parseEventText(String text, Long ts, Double uptime, int line) {
        GcEvent ev = new GcEvent();
        ev.line = line;
        ev.tsEpochMs = ts;
        ev.uptimeSec = uptime;
        ev.text = GcLogSupport.trunc(text, 200);
        Matcher m;

        if ((m = CMS_CONC.matcher(text)).find()) {
            ev.type = GcEventType.CONCURRENT_CYCLE;
            ev.concurrent = true;
            ev.cause = "CMS-concurrent-" + m.group(1);
            ev.pauseMs = Double.parseDouble(m.group(3)) * 1000.0;
            reportCollector("CMS");
            return ev;
        }
        if (text.startsWith("[CMS-concurrent-")) return null; // -start 줄
        if ((m = CONC_END.matcher(text)).find()) {
            ev.type = GcEventType.CONCURRENT_CYCLE;
            ev.concurrent = true;
            ev.cause = "concurrent-" + m.group(1);
            ev.pauseMs = Double.parseDouble(m.group(2)) * 1000.0;
            reportCollector("G1");
            return ev;
        }
        if (text.startsWith("[GC concurrent-")) return null; // -start 줄

        if ((m = FULL.matcher(text)).find()) {
            ev.type = GcEventType.FULL;
            ev.cause = m.group(1);
        } else if (text.contains("CMS-initial-mark:")) {
            // JDK 8 [GC (CMS Initial Mark) [1 CMS-initial-mark: …] / JDK 6·7 [GC [1 CMS-initial-mark: …]
            ev.type = GcEventType.CMS_INITIAL_MARK;
            ev.cause = "CMS Initial Mark";
            reportCollector("CMS");
        } else if (text.contains("CMS-remark:")) {
            // JDK 8 [GC (CMS Final Remark) [YG occupancy… / JDK 6·7 [GC[YG occupancy: …] … [1 CMS-remark: …]
            ev.type = GcEventType.CMS_FINAL_REMARK;
            ev.cause = "CMS Final Remark";
            reportCollector("CMS");
        } else if ((m = G1_PAUSE.matcher(text)).find()) {
            ev.type = GcEventType.YOUNG;
            ev.cause = m.group(1);
            Matcher pm = PAREN.matcher(m.group(2) == null ? "" : m.group(2));
            while (pm.find()) {
                String p = pm.group(1).toLowerCase(Locale.ROOT);
                if (p.equals("mixed")) ev.type = GcEventType.MIXED;
                else if (p.equals("initial-mark")) ev.flags.add(GcFlag.INITIAL_MARK);
                else if (p.equals("to-space exhausted") || p.equals("to-space overflow")) ev.flags.add(GcFlag.TO_SPACE_EXHAUSTED);
            }
            reportCollector("G1");
        } else if (text.startsWith("[GC remark")) {
            ev.type = GcEventType.REMARK;
            reportCollector("G1");
        } else if (text.startsWith("[GC cleanup")) {
            ev.type = GcEventType.CLEANUP;
            reportCollector("G1");
        } else if ((m = GC.matcher(text)).find()) {
            ev.type = GcEventType.YOUNG;
            ev.cause = m.group(1);
            if (text.startsWith("[GC--")) ev.flags.add(GcFlag.PROMOTION_FAILED);   // JDK 6/7 Parallel 승격 실패 표기
        } else {
            return null;
        }

        GcLogSupport.flagsFromCause(ev.cause, ev);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("promotion failed")) ev.flags.add(GcFlag.PROMOTION_FAILED);
        if (lower.contains("concurrent mode failure") || lower.contains("concurrent mode interrupted")) ev.flags.add(GcFlag.CONCURRENT_MODE_FAILURE);
        if (lower.contains("humongous")) ev.flags.add(GcFlag.HUMONGOUS_ALLOC);

        ev.pauseMs = outerPauseMs(text);
        applySizes(ev, text);
        applyTimes(ev, text);
        if (ev.type == GcEventType.CMS_INITIAL_MARK || ev.type == GcEventType.CMS_FINAL_REMARK) applyCmsOccupancy(ev, text);
        // [GC 안에 Old 세대 수집([CMS: …]·[Tenured: …])이 있으면 Young 을 포기하고 Old 까지 STW 로 치운 전체 수집이다.
        // JDK 6/7 은 [Full GC 가 아니라 [GC [ParNew: …][CMS: …] 로 찍고, JDK 8 의 promotion failed·concurrent mode failure 도 같은 모양이다.
        if (ev.type == GcEventType.YOUNG && ev.oldBefore != null) ev.type = GcEventType.FULL;
        return ev;
    }

    /** 바깥 대괄호가 닫히는 지점 바로 앞의 {@code , X secs]} — 안쪽 {@code [ParNew: …, 0.01 secs]} 를 잡지 않게 깊이로 찾는다. */
    private static Double outerPauseMs(String text) {
        int d = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') d++;
            else if (c == ']') {
                d--;
                if (d == 0) {
                    Matcher m = OUTER_SECS.matcher(text.substring(0, i + 1));
                    if (m.find()) return Double.parseDouble(m.group(1)) * 1000.0;
                    return null;
                }
            }
        }
        return null;
    }

    private void applySizes(GcEvent ev, String text) {
        Matcher m = TRIPLE.matcher(text);
        while (m.find()) {
            int start = m.start();
            String before = text.substring(Math.max(0, start - 40), start);
            Matcher lm = LABEL_BEFORE.matcher(before);
            String label = lm.find() ? lm.group(1).trim() : null;
            Long b = GcLogSupport.parseSize(m.group(1), m.group(2));
            Long a = GcLogSupport.parseSize(m.group(5), m.group(6));
            Long t = GcLogSupport.parseSize(m.group(7), m.group(8));
            if (label == null || label.equals("Heap")) {
                if (ev.heapBefore == null || label != null) { ev.heapBefore = b; ev.heapAfter = a; ev.heapTotal = t; }
                continue;
            }
            switch (label) {
                case "PSYoungGen": reportCollector("Parallel"); ev.youngBefore = b; ev.youngAfter = a; ev.youngTotal = t; break;
                case "ParNew": reportCollector("CMS"); ev.youngBefore = b; ev.youngAfter = a; ev.youngTotal = t; break;
                case "DefNew": reportCollector("Serial"); ev.youngBefore = b; ev.youngAfter = a; ev.youngTotal = t; break;
                case "Eden": ev.youngBefore = b; ev.youngAfter = a; ev.youngTotal = t; break;
                case "ParOldGen": case "PSOldGen": reportCollector("Parallel"); ev.oldBefore = b; ev.oldAfter = a; ev.oldTotal = t; break;   // PSOldGen = JDK 6/7 -XX:-UseParallelOldGC
                case "CMS": reportCollector("CMS"); ev.oldBefore = b; ev.oldAfter = a; ev.oldTotal = t; break;
                case "Tenured": reportCollector("Serial"); ev.oldBefore = b; ev.oldAfter = a; ev.oldTotal = t; break;
                case "PSPermGen": case "CMS Perm": case "Perm":
                    reportPermGen();
                    ev.metaBefore = b; ev.metaAfter = a; ev.metaTotal = t; break;
                case "Metaspace":
                    ev.metaBefore = b; ev.metaAfter = a; ev.metaTotal = t; break;
                default: break; // Survivors 등
            }
        }
    }

    /** CMS 시작·재마킹의 순간 점유량 — Old 는 before=after 로 두고(수집이 아니다), 힙은 용량만 쓴다(추세·할당률 오염 방지). */
    private static void applyCmsOccupancy(GcEvent ev, String text) {
        Matcher m = CMS_MARK_OCC.matcher(text);
        if (!m.find()) return;
        ev.oldBefore = GcLogSupport.parseSize(m.group(1), m.group(2));
        ev.oldAfter = ev.oldBefore;
        ev.oldTotal = GcLogSupport.parseSize(m.group(3), m.group(4));
        if (ev.heapTotal == null) ev.heapTotal = GcLogSupport.parseSize(m.group(7), m.group(8));
    }

    private static void applyTimes(GcEvent ev, String text) {
        Matcher m = TIMES.matcher(text);
        if (m.find()) {
            ev.userSec = Double.parseDouble(m.group(1));
            ev.sysSec = Double.parseDouble(m.group(2));
            ev.realSec = Double.parseDouble(m.group(3));
        }
    }

    private void reportCollector(String c) {
        if (collectorReported) return;
        collectorReported = true;
        sink.onMeta("collector", c);
    }

    /** PermGen 라벨은 JDK 7 이하에만 있다(JDK 8 은 Metaspace) — 헤더 없는 회전 로그에서 JDK 세대를 알려 준다. */
    private void reportPermGen() {
        if (permGenReported) return;
        permGenReported = true;
        sink.onMeta("permGen", "true");
    }

    private void flushPending() {
        if (pending == null) return;
        GcEvent ev = pending;
        pending = null;
        emit(ev);
    }

    private void emit(GcEvent ev) {
        if (limit) return;
        if (seq >= maxEvents) { limit = true; return; }
        ev.seq = seq++;
        sink.onEvent(ev.freeze());
    }
}

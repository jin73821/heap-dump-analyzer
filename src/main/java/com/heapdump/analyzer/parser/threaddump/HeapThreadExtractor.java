package com.heapdump.analyzer.parser.threaddump;

import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 힙 덤프의 MAT 산출물(Thread Overview 표 + Thread Stacks {@code .threads}) → HotSpot 스레드 덤프 형식 텍스트(2.7.1).
 *
 * <p>만든 텍스트를 {@link ThreadDumpEngine} 에 그대로 넣어 스레드 덤프 분석기를 재사용한다 — 규칙을 두 벌 두지 않는다.
 * 두 산출물은 스레드 객체 주소({@code 0xc1290440})로 잇는다. 주소는 텍스트의 {@code tid=} 자리에 넣어 분석 결과의
 * {@code Track.tid} 로 다시 찾을 수 있게 한다(스레드별 힙 점유를 붙일 때).
 *
 * <h3>힙 덤프에 없는 것</h3>
 * MAT {@code .threads} 에는 스레드 이름·상태·락 줄이 없다(이름·상태는 Overview 표에서 온다). {@code synchronized} 락의
 * 소유·대기 관계와 CPU 시간은 힙 덤프에 아예 남지 않는다. 그래서 만든 텍스트에는 락 줄이 없고 스냅샷은 1개다.
 *
 * <p>상태는 Overview 의 {@code State value}(JVMTI 스레드 상태 비트)로 정한다 — 문자열 열은 MAT 버전마다 표기가 달라 쓰지 않는다.
 * 살아 있지 않은(NEW·TERMINATED) Thread 객체는 jstack 에도 나오지 않으므로 뺀다. 순수 함수 — 파일·스프링 의존 없음.
 */
public final class HeapThreadExtractor {

    private HeapThreadExtractor() {}

    /** 만든 텍스트의 VM 줄 — 분석 결과 화면이 이 문구로 "힙 덤프에서 추출" 임을 알아볼 필요는 없다(meta.source 가 따로 있다). */
    public static final String VM_LINE = "Full thread dump Java VM (heap dump derived):";

    private static final Pattern TH = Pattern.compile("<th[^>]*>(.*?)</th>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TR = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TD = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ADDR = Pattern.compile("@ (0x[0-9a-fA-F]+)");
    private static final Pattern OBJ_TYPE = Pattern.compile("^\\s*(\\S+) @ 0x");
    private static final Pattern THREAD_LINE = Pattern.compile("^Thread (0x[0-9a-fA-F]+)");
    private static final Pattern TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");

    /** 스레드 하나 — 주소가 키다. 수치가 없는 칸은 null. */
    public record HeapThread(String address, String name, String objectType, boolean daemon, Integer priority,
                             Integer stateValue, Long retained, Long localsRetained, List<String> frames) {}

    /**
     * @param dumpText          분석기에 넣을 스레드 덤프 텍스트
     * @param threads           덤프에 넣은 스레드(살아 있는 것만)
     * @param notAlive          뺀 NEW·TERMINATED 스레드 수
     * @param withoutStack      Overview 에는 있는데 .threads 에 스택이 없던 스레드 수
     * @param withoutState      상태를 몰라 UNKNOWN 으로 넣은 스레드 수(MAT 표에 상태 열이 없는 옛 결과)
     * @param stackOnly         .threads 에만 있고 Overview 에 없던 스레드 수(이름을 모른다)
     */
    public record Extract(String dumpText, List<HeapThread> threads, int notAlive, int withoutStack, int withoutState, int stackOnly) {
        public Map<String, HeapThread> byAddress() {
            Map<String, HeapThread> m = new HashMap<>();
            for (HeapThread t : threads) m.put(t.address(), t);
            return m;
        }
    }

    /**
     * @param overviewHtml MAT Thread Overview 페이지 HTML(없으면 null — 이름·상태를 모른 채 스택만 쓴다)
     * @param threadsText  MAT {@code .threads} 내용(없으면 null — 스택 없이 이름·상태만 쓴다)
     * @param dumpTime     힙 덤프 생성 시각 {@code yyyy-MM-dd HH:mm:ss}(없으면 null — 시각 줄을 넣지 않는다)
     * @throws ThreadDumpException {@code HEAP_THREADS_UNAVAILABLE} — 둘 다 없거나 스레드가 하나도 없을 때
     */
    public static Extract extract(String overviewHtml, String threadsText, String dumpTime) {
        Map<String, Row> rows = overviewHtml == null ? Map.of() : parseOverview(overviewHtml);
        Map<String, List<String>> stacks = threadsText == null ? Map.of() : parseThreads(threadsText);
        if (rows.isEmpty() && stacks.isEmpty()) {
            throw new ThreadDumpException("HEAP_THREADS_UNAVAILABLE",
                    "이 힙 덤프 분석 결과에는 스레드 정보(MAT Thread Overview·Thread Stacks)가 없습니다. 힙 덤프를 다시 분석하면 생성됩니다.");
        }
        List<String> order = new ArrayList<>(rows.keySet());
        for (String a : stacks.keySet()) if (!rows.containsKey(a)) order.add(a);

        StringBuilder sb = new StringBuilder(Math.max(4096, (threadsText == null ? 0 : threadsText.length())));
        if (dumpTime != null && TIMESTAMP.matcher(dumpTime.trim()).matches()) sb.append(dumpTime.trim()).append('\n');
        sb.append(VM_LINE).append("\n\n");
        List<HeapThread> kept = new ArrayList<>();
        int notAlive = 0, withoutStack = 0, withoutState = 0, stackOnly = 0;
        for (String addr : order) {
            Row r = rows.get(addr);
            List<String> frames = stacks.getOrDefault(addr, List.of());
            if (r != null && r.stateValue != null && !alive(r.stateValue)) { notAlive++; continue; }
            if (r == null) stackOnly++;
            if (r != null && !stacks.isEmpty() && !stacks.containsKey(addr)) withoutStack++;
            String name = r == null || r.name.isBlank() ? "thread@" + addr : r.name;
            Integer sv = r == null ? null : r.stateValue;
            if (sv == null) withoutState++;
            sb.append('"').append(name.replace('\n', ' ').replace('\r', ' ')).append('"');
            if (r != null && r.daemon) sb.append(" daemon");
            sb.append(" prio=").append(r == null || r.priority == null ? 5 : r.priority);
            sb.append(" tid=").append(addr).append(" nid=0x0 ").append(nativeStatus(sv)).append(" [0x0]\n");
            sb.append("   java.lang.Thread.State: ").append(stateLine(sv)).append('\n');
            for (String f : frames) sb.append("\tat ").append(f).append('\n');
            sb.append('\n');
            kept.add(new HeapThread(addr, name, r == null ? null : r.objectType, r != null && r.daemon, r == null ? null : r.priority,
                    sv, r == null ? null : r.retained, r == null ? null : r.localsRetained, frames));
        }
        sb.append("JNI global references: 0\n");
        if (kept.isEmpty()) {
            throw new ThreadDumpException("HEAP_THREADS_UNAVAILABLE",
                    "이 힙 덤프에는 살아 있는 스레드가 없습니다(Thread 객체 " + notAlive + "개가 모두 NEW·TERMINATED 상태).");
        }
        return new Extract(sb.toString(), kept, notAlive, withoutStack, withoutState, stackOnly);
    }

    /**
     * 변환 결과를 스레드 덤프 분석기로 분석하고, 스레드마다 힙 점유(Retained·지역 변수 최대 Retained)와 객체 클래스를 붙인다.
     * 힙 쪽 집계(뺀 스레드·스택 없음·상태 모름)는 진단에 담겨 {@code HEAP_DERIVED} 소견 문구가 된다.
     */
    public static com.heapdump.analyzer.model.ThreadDumpResult analyze(Extract x, ThreadDumpLimits limits, String heapFilename)
            throws java.io.IOException {
        com.heapdump.analyzer.model.ThreadDumpResult.Diagnostics seed = new com.heapdump.analyzer.model.ThreadDumpResult.Diagnostics();
        seed.setHeapNotAliveThreads(x.notAlive());
        seed.setHeapThreadsWithoutStack(x.withoutStack());
        seed.setHeapThreadsWithoutState(x.withoutState());
        byte[] b = x.dumpText().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        com.heapdump.analyzer.model.ThreadDumpResult r = ThreadDumpEngine.analyze(
                List.of(new ThreadDumpEngine.Source(null, () -> new java.io.ByteArrayInputStream(b))), limits, null, true, seed);
        r.getMeta().setHeapFilename(heapFilename);
        Map<String, HeapThread> by = x.byAddress();
        for (com.heapdump.analyzer.model.ThreadDumpResult.Track t : r.getTracks()) {
            HeapThread h = t.getTid() == null ? null : by.get(t.getTid().toLowerCase(Locale.ROOT));
            if (h == null) continue;
            t.setHr(h.retained());
            t.setHl(h.localsRetained());
            t.setHc(h.objectType());
        }
        return r;
    }

    // ── JVMTI 스레드 상태 비트 ──────────────────────────────────
    static final int ALIVE = 0x1, TERMINATED = 0x2, RUNNABLE = 0x4, WAITING_INDEFINITELY = 0x10, WAITING_WITH_TIMEOUT = 0x20,
            SLEEPING = 0x40, IN_OBJECT_WAIT = 0x100, PARKED = 0x200, BLOCKED_ON_MONITOR_ENTER = 0x400;

    static boolean alive(int sv) { return (sv & ALIVE) != 0 && (sv & TERMINATED) == 0; }

    /** 화면·연결 뷰용 상태 문구(상태 열이 없으면 UNKNOWN). */
    public static String stateLineOf(Integer sv) { return stateLine(sv); }

    /** {@code java.lang.Thread.State:} 줄 내용 — jstack 이 찍는 것과 같은 모양. */
    static String stateLine(Integer sv) {
        if (sv == null) return "UNKNOWN";
        if ((sv & BLOCKED_ON_MONITOR_ENTER) != 0) return "BLOCKED (on object monitor)";
        String detail = (sv & PARKED) != 0 ? " (parking)" : (sv & IN_OBJECT_WAIT) != 0 ? " (on object monitor)" : (sv & SLEEPING) != 0 ? " (sleeping)" : "";
        if ((sv & WAITING_INDEFINITELY) != 0) return "WAITING" + detail;
        if ((sv & WAITING_WITH_TIMEOUT) != 0) return "TIMED_WAITING" + detail;
        if ((sv & RUNNABLE) != 0) return "RUNNABLE";
        return "RUNNABLE";      // ALIVE 만 켜진 값 — HotSpot 은 실행 중 네이티브를 이렇게도 찍는다
    }

    private static String nativeStatus(Integer sv) {
        if (sv == null) return "unknown";
        if ((sv & BLOCKED_ON_MONITOR_ENTER) != 0) return "waiting for monitor entry";
        if ((sv & IN_OBJECT_WAIT) != 0) return "in Object.wait()";
        if ((sv & (WAITING_INDEFINITELY | WAITING_WITH_TIMEOUT)) != 0) return "waiting on condition";
        return "runnable";
    }

    // ── Overview 표 ─────────────────────────────────────────────
    private static final class Row {
        String name = "", objectType;
        boolean daemon;
        Integer priority, stateValue;
        Long retained, localsRetained;
    }

    static Map<String, Row> parseOverview(String html) {
        List<String> heads = new ArrayList<>();
        Matcher th = TH.matcher(html);
        while (th.find()) heads.add(cell(th.group(1)).toLowerCase(Locale.ROOT));
        int cName = find(heads, "name"), cRet = find(heads, "retained heap"), cLoc = find(heads, "max. locals"),
                cDaemon = find(heads, "is daemon"), cPrio = find(heads, "priority"), cSv = find(heads, "state value");
        Map<String, Row> out = new LinkedHashMap<>();
        Matcher tr = TR.matcher(html);
        while (tr.find()) {
            if (tr.group(1).contains("totals")) continue;
            List<String> tds = new ArrayList<>();
            Matcher td = TD.matcher(tr.group(2));
            while (td.find()) tds.add(cell(td.group(1)));
            if (tds.size() < 2) continue;
            Matcher a = ADDR.matcher(tds.get(0));
            if (!a.find()) continue;                    // 합계 행·스택 프레임 행
            Row r = new Row();
            Matcher ot = OBJ_TYPE.matcher(tds.get(0));
            if (ot.find()) r.objectType = ot.group(1);
            r.name = at(tds, cName);
            r.retained = parseLong(at(tds, cRet));
            r.localsRetained = parseLong(at(tds, cLoc));
            r.daemon = "true".equalsIgnoreCase(at(tds, cDaemon));
            Long prio = parseLong(at(tds, cPrio));
            r.priority = prio == null ? null : prio.intValue();
            String sv = at(tds, cSv);
            if (sv.startsWith("0x")) {
                try { r.stateValue = Integer.parseInt(sv.substring(2), 16); } catch (NumberFormatException e) { r.stateValue = null; }
            }
            out.putIfAbsent(a.group(1).toLowerCase(Locale.ROOT), r);
        }
        return out;
    }

    private static int find(List<String> heads, String prefix) {
        for (int i = 0; i < heads.size(); i++) if (heads.get(i).startsWith(prefix)) return i;
        return -1;
    }

    private static String at(List<String> tds, int i) { return i < 0 || i >= tds.size() ? "" : tds.get(i); }

    private static String cell(String raw) {
        return HtmlUtils.htmlUnescape(TAG.matcher(raw).replaceAll("")).replace(' ', ' ').trim();
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return null;
        try { return Long.parseLong(d); } catch (NumberFormatException e) { return null; }
    }

    // ── .threads ────────────────────────────────────────────────

    /** 주소 → jstack 모양 프레임 목록({@code pkg.Class.method(File.java:12)}). {@code locals:} 아래는 버린다. */
    static Map<String, List<String>> parseThreads(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> cur = null;
        for (String line : text.split("\r?\n")) {
            Matcher m = THREAD_LINE.matcher(line);
            if (m.find()) { cur = new ArrayList<>(); out.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), cur); continue; }
            String t = line.trim();
            if (t.startsWith("locals:")) { cur = null; continue; }
            if (cur == null || !t.startsWith("at ")) continue;
            String f = frame(t.substring(3));
            if (f != null) cur.add(f);
        }
        return out;
    }

    /**
     * MAT 프레임 {@code java.util.concurrent.locks.LockSupport.park(Ljava/lang/Object;)V (LockSupport.java:175)}
     * → {@code java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)}. JVM 시그니처에는 공백이 없으므로
     * 메서드명 뒤 첫 공백 이후가 위치다. 네이티브는 {@code (Unsafe.java(Native Method))} 처럼 괄호가 겹친다.
     */
    static String frame(String s) {
        int paren = s.indexOf('(');
        if (paren <= 0) return null;
        String method = s.substring(0, paren);
        int sp = s.indexOf(' ', paren);
        String loc = sp < 0 ? "" : s.substring(sp + 1).trim();
        if (loc.startsWith("(") && loc.endsWith(")")) loc = loc.substring(1, loc.length() - 1);
        if (loc.endsWith("(Native Method)") || loc.equals("Native Method")) loc = "Native Method";
        if (loc.isEmpty()) loc = "Unknown Source";
        return method + "(" + loc + ")";
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.parser.gclog.GcLogFormat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 업로드한 GC 로그들이 <b>한 인스턴스의 연속된 회전본</b>인지 판정한다(2026-09-21).
 *
 * <p>세 축을 모두 본다 — ① 로그 내용의 시각 연속성 ② 호스트네임·인스턴스명 ③ JVM 마커(수집기·최대 힙).
 * 판정은 순수 함수이고 DB·파일·스프링에 닿지 않는다. 입력 {@link Probe} 를 만드는 쪽(파일을 읽는 쪽)은
 * {@link GcLogAnalyzerService#probe} 다.
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li><b>정렬을 새로 짜지 않는다</b> — 순서의 유일한 출처는 {@link GcLogAnalyzerService#orderMembers} 다.
 *       회전 번호·파일명 순은 시간 순이 아니다(함정 60).</li>
 *   <li><b>마커가 없는 것은 불일치가 아니다</b> — JDK 9+ 의 {@code filecount} 회전은 {@code gc,init} 을 첫 파일에만
 *       쓰므로 헤더 없는 회전본이 정상이다. 축 판정을 3값({@link Signal})으로 두고 <b>양쪽 모두 값이 있을 때만</b>
 *       비교한다.</li>
 *   <li><b>겹침이 유일한 강한 부정 신호다</b> — 두 노드의 로그를 함께 올리면 시작 시각만으로는 완벽히 "연속"으로
 *       보인다. 그래서 끝 시각을 읽어 겹침을 본다. 끝 시각을 모르면 묶지 않는다(안전측).</li>
 *   <li><b>시간은 사슬, 정체성은 앵커</b> — 시각 연속성은 직전 조각과, 출처·마커는 그룹의 첫 조각과 비교한다.
 *       사슬로만 비교하면 A≈B, B≈C 인데 A 와 C 가 다른 "표류"가 생긴다.</li>
 * </ul>
 */
public final class GcLogGroupPlanner {

    private GcLogGroupPlanner() { }

    /** 축 판정 3값. {@code UNKNOWN} 은 "다르다"가 아니라 "근거가 없다"는 뜻이다. */
    public enum Signal { MATCH, MISMATCH, UNKNOWN }

    /**
     * 그룹 판정용 지문 — {@link GcLogAnalyzerService.Peek}(머리)에 끝 시각과 JVM 마커, 출처를 덧댄 것.
     *
     * <p>⚠ {@code Peek} 에 필드를 추가하지 않고 합성한다. {@code Peek} 은 "정렬에 필요한 것만" 담는다는 계약이
     * {@code orderMembers} 의 순수성을 지키고, 이어붙이기 미리보기({@code planCombine})는 체크박스 토글마다
     * 호출되므로 꼬리 읽기·마커 파싱 비용을 얹으면 안 된다.
     *
     * @param lastTsEpochMs 조각의 마지막 절대 시각. 모르면 null → 겹침을 검사할 수 없다
     * @param endSource     끝 시각의 근거 — {@code logEnd}(분석된 행의 컬럼) · {@code content}(파일 꼬리) · {@code none}
     */
    public record Probe(GcLogAnalyzerService.Peek peek, Long lastTsEpochMs, String endSource,
                        String serverName, String instanceName,
                        String collector, String jdkVersion, Long heapMaxBytes) {

        public String filename()      { return peek.filename(); }
        public Long   firstTs()       { return peek.firstTsEpochMs(); }
        public GcLogFormat format()   { return peek.format(); }
        public long   size()          { return peek.size(); }
    }

    /** 판정 임계값. 설정 키 {@code gclog.group.*} 에서 온다. */
    public record Rules(long maxOverlapSec, long maxGapSec, int maxMembers) { }

    /** 그룹이 어떤 근거로 묶였는지 — 화면이 "왜 묶였나/왜 안 묶였나"를 설명하는 데 쓴다. */
    public record Axes(Signal time, Signal origin, Signal jvm) { }

    /**
     * 묶인 한 덩어리.
     *
     * @param members        <b>병합 순서의 전체 목록</b>(대표 포함) — {@code member_files} 에 그대로 들어간다
     * @param representative 대표 = 가장 최근 조각(목록의 마지막). 기존 화면 관례와 같다
     */
    public record Group(String representative, List<String> members, Axes axes,
                        List<String> warnings, long totalBytes, String orderBasis) { }

    /**
     * 판정 결과.
     *
     * @param singles 어디에도 묶이지 않은 파일 — 이유는 {@code singleReasons} 에 있다
     */
    public record Plan(List<Group> groups, List<String> singles, Map<String, String> singleReasons) { }

    // ── 판정 ────────────────────────────────────────────────────

    /**
     * 시간순으로 훑으며 연속인 동안 한 그룹으로 모은다.
     *
     * <p>⚠ 여기서 파일명으로 정렬하지 않는다 — 순서는 {@link #order} 가 {@code orderMembers} 에 위임한다(함정 60).
     */
    public static Plan plan(List<Probe> probes, Rules rules) {
        List<Group> groups = new ArrayList<>();
        List<String> singles = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        if (probes == null || probes.isEmpty()) return new Plan(groups, singles, reasons);

        List<String> orderWarnings = new ArrayList<>();
        List<Probe> ordered = order(probes, orderWarnings);
        String basis = GcLogAnalyzerService.orderBasisOf(ordered.stream().map(Probe::peek).toList());

        List<List<Probe>> chunks = new ArrayList<>();
        List<List<String>> chunkNotes = new ArrayList<>();
        List<Axes> chunkAxes = new ArrayList<>();

        List<Probe> cur = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Axes axes = null;
        String breakReason = null;

        for (Probe p : ordered) {
            if (cur.isEmpty()) {
                cur.add(p);
                continue;
            }
            Probe anchor = cur.get(0), prev = cur.get(cur.size() - 1);
            List<String> pairNotes = new ArrayList<>();
            Axes pair = evaluate(anchor, prev, p, rules, pairNotes);
            boolean roomLeft = cur.size() < rules.maxMembers();
            if (roomLeft && continuous(pair)) {
                cur.add(p);
                notes.addAll(pairNotes);
                axes = axes == null ? pair : weaker(axes, pair);
            } else {
                String why = roomLeft ? explain(pair) : "한 묶음의 상한(" + rules.maxMembers() + "개)에 닿았습니다";
                close(chunks, chunkNotes, chunkAxes, cur, notes, axes, singles, reasons, breakReason);
                breakReason = why;
                cur = new ArrayList<>();
                cur.add(p);
                notes = new ArrayList<>();
                axes = null;
            }
        }
        close(chunks, chunkNotes, chunkAxes, cur, notes, axes, singles, reasons, breakReason);

        for (int i = 0; i < chunks.size(); i++) {
            List<Probe> chunk = chunks.get(i);
            List<String> members = new ArrayList<>(chunk.size());
            long bytes = 0;
            for (Probe p : chunk) { members.add(p.filename()); bytes += p.size(); }
            List<String> w = new ArrayList<>(orderWarnings);
            for (String n : chunkNotes.get(i)) if (!w.contains(n)) w.add(n);
            groups.add(new Group(members.get(members.size() - 1), members, chunkAxes.get(i), w, bytes, basis));
        }
        return new Plan(groups, singles, reasons);
    }

    /** 덩어리를 마감한다 — 2개 이상이면 그룹, 하나면 단독(이유와 함께). */
    private static void close(List<List<Probe>> chunks, List<List<String>> chunkNotes, List<Axes> chunkAxes,
                              List<Probe> cur, List<String> notes, Axes axes,
                              List<String> singles, Map<String, String> reasons, String breakReason) {
        if (cur.isEmpty()) return;
        if (cur.size() >= 2) {
            chunks.add(new ArrayList<>(cur));
            chunkNotes.add(new ArrayList<>(notes));
            chunkAxes.add(axes == null ? new Axes(Signal.UNKNOWN, Signal.UNKNOWN, Signal.UNKNOWN) : axes);
            return;
        }
        String fn = cur.get(0).filename();
        singles.add(fn);
        reasons.put(fn, breakReason == null ? "이어질 로그를 찾지 못했습니다" : breakReason);
    }

    /** 정렬 — 순서의 유일한 출처인 {@code orderMembers} 에 위임하고 Probe 로 되돌린다. */
    static List<Probe> order(List<Probe> probes, List<String> warnings) {
        List<GcLogAnalyzerService.Peek> peeks = new ArrayList<>(probes.size());
        Map<String, Probe> by = new LinkedHashMap<>();
        for (Probe p : probes) {
            peeks.add(p.peek());
            by.putIfAbsent(p.filename(), p);
        }
        List<Probe> out = new ArrayList<>(probes.size());
        for (GcLogAnalyzerService.Peek pk : GcLogAnalyzerService.orderMembers(peeks, warnings)) {
            Probe p = by.get(pk.filename());
            if (p != null) out.add(p);
        }
        return out;
    }

    /** 세 축을 한 번에 잰다. {@code notes} 에는 묶더라도 알려야 할 사실이 쌓인다. */
    static Axes evaluate(Probe anchor, Probe prev, Probe next, Rules rules, List<String> notes) {
        return new Axes(timeSignal(prev, next, rules, notes),
                        originSignal(anchor, next),
                        jvmSignal(anchor, next, notes));
    }

    /** ① 시간축은 필수, ②③ 불일치는 즉시 차단. 둘 다 근거가 없으면 묶지 않는다. */
    static boolean continuous(Axes a) {
        if (a.time() != Signal.MATCH) return false;
        if (a.origin() == Signal.MISMATCH || a.jvm() == Signal.MISMATCH) return false;
        return a.origin() == Signal.MATCH || a.jvm() == Signal.MATCH;
    }

    /** 왜 끊겼는지 한국어 한 줄 — 화면이 "따로 둔 이유"로 보여 준다. */
    static String explain(Axes a) {
        if (a.time() == Signal.MISMATCH) return "앞 로그와 시간이 겹치거나 너무 멀리 떨어져 있습니다";
        if (a.time() == Signal.UNKNOWN)  return "로그의 시각을 확인하지 못해 연속인지 판단할 수 없습니다";
        if (a.origin() == Signal.MISMATCH) return "호스트네임 또는 인스턴스명이 다릅니다";
        if (a.jvm() == Signal.MISMATCH)    return "JVM 설정(수집기·최대 힙·로그 형식)이 다릅니다";
        return "같은 인스턴스라는 근거(출처·JVM 설정)가 없습니다";
    }

    /**
     * ① 시각 연속성 — 직전 조각의 끝과 다음 조각의 시작을 본다.
     *
     * <p>끝 시각을 모르면 {@code UNKNOWN} 이다. 겹침을 못 보는 채로 묶으면 다른 노드의 로그가 섞인다.
     */
    static Signal timeSignal(Probe prev, Probe next, Rules r, List<String> notes) {
        if (prev.firstTs() == null || next.firstTs() == null) {
            note(notes, "로그에 절대 시각이 없어 시간 연속성을 확인하지 못했습니다.");
            return Signal.UNKNOWN;
        }
        if (prev.lastTsEpochMs() == null) {
            note(notes, prev.filename() + " 의 끝 시각을 확인하지 못해 겹침을 검사하지 못했습니다.");
            return Signal.UNKNOWN;
        }
        long overlapSec = (prev.lastTsEpochMs() - next.firstTs()) / 1000L;
        if (overlapSec > r.maxOverlapSec()) return Signal.MISMATCH;
        long gapSec = (next.firstTs() - prev.lastTsEpochMs()) / 1000L;
        if (gapSec > r.maxGapSec()) return Signal.MISMATCH;
        if (gapSec > 0) {
            note(notes, prev.filename() + " 과(와) " + next.filename() + " 사이에 "
                    + humanGap(gapSec) + " 공백이 있습니다(그 사이 JVM 이 꺼져 있었을 수 있습니다).");
        }
        return Signal.MATCH;
    }

    /** ② 출처 — 양쪽 값이 다 있을 때만 판정한다. 호스트네임이 없는 옛 기록은 근거 없음이다. */
    static Signal originSignal(Probe anchor, Probe next) {
        String as = norm(anchor.serverName()), bs = norm(next.serverName());
        String ai = norm(anchor.instanceName()), bi = norm(next.instanceName());
        if (as != null && bs != null && !as.equals(bs)) return Signal.MISMATCH;
        if (ai != null && bi != null && !ai.equals(bi)) return Signal.MISMATCH;
        return (as != null && bs != null) ? Signal.MATCH : Signal.UNKNOWN;
    }

    /**
     * ③ JVM 마커.
     *
     * <p>차단 마커는 <b>형식·수집기·최대 힙</b>이고, 형식이 같은 것은 긍정 근거로 세지 않는다 — 형식은 JVM 세대일
     * 뿐 인스턴스의 정체성이 아니다. {@code jdkVersion} 은 참고 마커다: 빌드 문자열이 패치 하나로 바뀌는데
     * 회전 = 재기동인 환경에서는 같은 인스턴스의 연속 로그가 다른 빌드를 담는 게 정상이다(경고만 남긴다).
     */
    static Signal jvmSignal(Probe anchor, Probe next, List<String> notes) {
        GcLogFormat af = anchor.format(), bf = next.format();
        if (af != null && bf != null && af != GcLogFormat.UNKNOWN && bf != GcLogFormat.UNKNOWN && af != bf) {
            return Signal.MISMATCH;
        }
        boolean compared = false;
        String ac = norm(anchor.collector()), bc = norm(next.collector());
        if (ac != null && bc != null) {
            if (!ac.equals(bc)) return Signal.MISMATCH;
            compared = true;
        }
        Long ah = anchor.heapMaxBytes(), bh = next.heapMaxBytes();
        if (ah != null && bh != null) {
            if (!ah.equals(bh)) return Signal.MISMATCH;
            compared = true;
        }
        String aj = norm(anchor.jdkVersion()), bj = norm(next.jdkVersion());
        if (aj != null && bj != null && !aj.equals(bj)) {
            note(notes, "JDK 버전 표기가 다릅니다(" + anchor.jdkVersion() + " / " + next.jdkVersion()
                    + ") — 재기동 사이에 JVM 이 바뀌었을 수 있습니다.");
        }
        return compared ? Signal.MATCH : Signal.UNKNOWN;
    }

    // ── 보조 ────────────────────────────────────────────────────

    /** 두 판정 중 덜 확신하는 쪽을 남긴다(MISMATCH &lt; UNKNOWN &lt; MATCH 순으로 약함). */
    private static Axes weaker(Axes a, Axes b) {
        return new Axes(weaker(a.time(), b.time()), weaker(a.origin(), b.origin()), weaker(a.jvm(), b.jvm()));
    }

    private static Signal weaker(Signal a, Signal b) {
        if (a == Signal.MISMATCH || b == Signal.MISMATCH) return Signal.MISMATCH;
        if (a == Signal.UNKNOWN || b == Signal.UNKNOWN) return Signal.UNKNOWN;
        return Signal.MATCH;
    }

    private static void note(List<String> notes, String msg) {
        if (notes != null && !notes.contains(msg)) notes.add(msg);
    }

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }

    static String humanGap(long sec) {
        if (sec < 90) return sec + "초";
        if (sec < 5400) return (sec / 60) + "분";
        return String.format(Locale.ROOT, "%.1f시간", sec / 3600.0);
    }
}

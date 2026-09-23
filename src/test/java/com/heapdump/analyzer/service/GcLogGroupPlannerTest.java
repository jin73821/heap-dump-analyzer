package com.heapdump.analyzer.service;

import com.heapdump.analyzer.parser.gclog.GcLogFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 연속 회전본 자동 판정 — 순수 함수 계약 (2026-09-21).
 *
 * <p>고정하는 것은 네 가지다. ① 순서는 회전 번호가 아니라 내용의 시각이다(함정 60) ② <b>겹침</b>은 다른 JVM 의
 * 로그가 섞였다는 강한 부정 신호다 ③ <b>마커가 없는 것은 불일치가 아니다</b> — 헤더 없는 회전본이 정상이므로
 * 양쪽에 값이 있을 때만 비교한다 ④ 근거가 하나도 없으면 묶지 않는다(조용히 잘못 묶는 것보다 낫다).
 */
class GcLogGroupPlannerTest {

    /** 2026-09-19 22:18 — 이 서버 실측 회전본의 첫 조각 시각. */
    private static final long T0 = Instant.parse("2026-09-19T13:18:00Z").toEpochMilli();
    private static final long MIN = 60_000L;

    private static final GcLogGroupPlanner.Rules RULES =
            new GcLogGroupPlanner.Rules(60, 720 * 60, GcLogAnalyzerService.MAX_MEMBERS);

    // ── 픽스처 ──────────────────────────────────────────────────

    /** 조각 하나 — 시각은 T0 기준 분, 마커는 기본(G1 / 2GiB). */
    private static GcLogGroupPlanner.Probe p(String name, long startMin, Long endMin) {
        return probe(name, startMin, endMin, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED);
    }

    private static GcLogGroupPlanner.Probe probe(String name, long startMin, Long endMin, String host, String inst,
                                                 String collector, Long heap, String jdk, GcLogFormat fmt) {
        GcLogAnalyzerService.Peek peek =
                new GcLogAnalyzerService.Peek(name, fmt, T0 + startMin * MIN, 0.003, 0L, 1024L);
        Long end = endMin == null ? null : T0 + endMin * MIN;
        return new GcLogGroupPlanner.Probe(peek, end, end == null ? "none" : "content",
                host, inst, collector, jdk, heap);
    }

    private static List<String> names(GcLogGroupPlanner.Group g) { return g.members(); }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("한 인스턴스의 회전본 6개는 한 묶음이 되고 순서는 회전 번호가 아니라 시각을 따른다")
    void rotationsFromOneInstanceFormOneGroup() {
        // 실측 배열: gc.log.0(22:18) → .3(22:57) → .4(23:09) → .1(23:23) → .2.gz(다음날 10:56) → gc.log(12:49)
        List<GcLogGroupPlanner.Probe> in = List.of(
                p("gc.log", 871, 880L),
                p("gc.log.0", 0, 37L),
                p("gc.log.1", 65, 70L),
                p("gc.log.2.gz", 758, 860L),
                p("gc.log.3", 39, 49L),
                p("gc.log.4", 51, 63L));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertEquals(1, plan.groups().size(), "연속된 회전본이 한 묶음으로 모이지 않았다");
        assertEquals(List.of("gc.log.0", "gc.log.3", "gc.log.4", "gc.log.1", "gc.log.2.gz", "gc.log"),
                names(plan.groups().get(0)),
                "이름·회전 번호 순으로 묶었다 — JDK 는 아카이브 슬롯을 돌려 쓴다(함정 60)");
        assertEquals("gc.log", plan.groups().get(0).representative(), "대표는 가장 최근 조각이어야 한다");
        assertTrue(plan.singles().isEmpty());
        assertEquals("absolute", plan.groups().get(0).orderBasis());
    }

    @Test
    @DisplayName("구간이 겹치면 다른 JVM 의 로그다 — 묶지 않는다")
    void overlappingWindowsSplit() {
        // 두 노드의 로그를 함께 올린 모양: 시작 시각만 보면 완벽히 '연속' 이다.
        List<GcLogGroupPlanner.Probe> in = List.of(
                p("a.log", 0, 120L),
                p("b.log", 60, 180L));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertTrue(plan.groups().isEmpty(), "겹치는 구간을 한 묶음으로 봤다 — 같은 이벤트가 두 번 집계된다");
        assertEquals(List.of("a.log", "b.log"), plan.singles());
        assertTrue(plan.singleReasons().get("b.log").contains("겹치"), plan.singleReasons().toString());
    }

    @Test
    @DisplayName("1분 이하로 스치는 것은 회전 순간의 시계 흔들림이라 묶는다")
    void tinyOverlapIsToleratedAsRotationJitter() {
        GcLogGroupPlanner.Probe a = p("a.log", 0, 60L);
        // b 가 a 의 끝보다 30초 앞서 시작한다(= 겹침 30초)
        GcLogAnalyzerService.Peek shifted = new GcLogAnalyzerService.Peek(
                "b.log", GcLogFormat.UNIFIED, T0 + 60 * MIN - 30_000, 0.003, 0L, 1024L);
        GcLogGroupPlanner.Probe b = new GcLogGroupPlanner.Probe(
                shifted, T0 + 120 * MIN, "content", "was01", "c1", "G1", null, 2L << 30);

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(List.of(a, b), RULES);

        assertEquals(1, plan.groups().size(), "30초 겹침(회전 jitter)에 묶음이 깨졌다");
    }

    @Test
    @DisplayName("공백이 상한을 넘으면 다른 사건이다 — 새 묶음을 연다")
    void hugeGapStartsNewGroup() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                p("a.log", 0, 60L),
                p("b.log", 61, 120L),
                p("c.log", 60 * 24 * 3, 60 * 24 * 3 + 30L));   // 3일 뒤

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertEquals(1, plan.groups().size());
        assertEquals(List.of("a.log", "b.log"), names(plan.groups().get(0)));
        assertEquals(List.of("c.log"), plan.singles());
    }

    @Test
    @DisplayName("회전 = 재기동이라 몇 시간 공백은 정상이다 — 끊지 않고 경고만 남긴다")
    void restartGapWithinLimitStaysTogether() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                p("a.log", 0, 60L),
                p("b.log", 60 + 400, 60 + 460L));   // 6시간 40분 공백

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertEquals(1, plan.groups().size(), "12시간 이내 공백에 묶음이 깨졌다 — 회전 세트가 조각마다 따로 논다");
        assertTrue(plan.groups().get(0).warnings().stream().anyMatch(w -> w.contains("공백")),
                "공백을 조용히 넘겼다 — 사용자에게 알려야 한다: " + plan.groups().get(0).warnings());
    }

    @Test
    @DisplayName("호스트네임이 다르면 묶지 않는다")
    void differentHostnameSplits() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                probe("a.log", 0, 60L, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, "was02", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertTrue(plan.groups().isEmpty());
        assertTrue(plan.singleReasons().get("b.log").contains("호스트네임"), plan.singleReasons().toString());
    }

    @Test
    @DisplayName("인스턴스명이 다르면 묶지 않는다 — 한 서버의 여러 WAS 인스턴스")
    void differentInstanceSplits() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                probe("a.log", 0, 60L, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, "was01", "c2", "G1", 2L << 30, null, GcLogFormat.UNIFIED));

        assertTrue(GcLogGroupPlanner.plan(in, RULES).groups().isEmpty());
    }

    @Test
    @DisplayName("최대 힙·수집기·로그 형식이 다르면 묶지 않는다")
    void differentJvmMarkersSplit() {
        List<GcLogGroupPlanner.Probe> heap = List.of(
                probe("a.log", 0, 60L, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, "was01", "c1", "G1", 8L << 30, null, GcLogFormat.UNIFIED));
        assertTrue(GcLogGroupPlanner.plan(heap, RULES).groups().isEmpty(), "최대 힙이 다른데 묶었다");

        List<GcLogGroupPlanner.Probe> collector = List.of(
                probe("a.log", 0, 60L, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, "was01", "c1", "Parallel", 2L << 30, null, GcLogFormat.UNIFIED));
        assertTrue(GcLogGroupPlanner.plan(collector, RULES).groups().isEmpty(), "수집기가 다른데 묶었다");

        List<GcLogGroupPlanner.Probe> fmt = List.of(
                probe("a.log", 0, 60L, "was01", "c1", null, null, null, GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, "was01", "c1", null, null, null, GcLogFormat.JDK8));
        assertTrue(GcLogGroupPlanner.plan(fmt, RULES).groups().isEmpty(), "로그 형식이 다른데 묶었다");
    }

    @Test
    @DisplayName("★ 마커가 없는 회전본은 불일치가 아니다 — 헤더 없는 조각이 정상이다")
    void missingMarkersAreNeutralNotMismatch() {
        // JDK 9+ filecount 회전은 gc,init 을 첫 파일에만 쓴다. 뒤 조각은 수집기·힙 크기를 알 수 없다.
        List<GcLogGroupPlanner.Probe> in = List.of(
                probe("gc.log.0", 0, 60L, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED),
                probe("gc.log", 61, 120L, "was01", "c1", null, null, null, GcLogFormat.UNIFIED));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertEquals(1, plan.groups().size(), "마커가 없다고 끊었다 — 헤더 없는 회전본이 정상이다");
        assertEquals(List.of("gc.log.0", "gc.log"), names(plan.groups().get(0)));
    }

    @Test
    @DisplayName("JDK 빌드 표기 차이는 경고일 뿐 묶음을 깨지 않는다")
    void jdkBuildDifferenceWarnsButDoesNotSplit() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                probe("a.log", 0, 60L, "was01", "c1", "G1", 2L << 30, "21+35-2513", GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, "was01", "c1", "G1", 2L << 30, "21.0.1+12", GcLogFormat.UNIFIED));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertEquals(1, plan.groups().size(), "JDK 패치 차이로 끊었다 — 회전 = 재기동 환경에서는 정상이다");
        assertTrue(plan.groups().get(0).warnings().stream().anyMatch(w -> w.contains("JDK")),
                "조용히 넘겼다: " + plan.groups().get(0).warnings());
    }

    @Test
    @DisplayName("출처도 마커도 근거가 없으면 묶지 않는다")
    void bothOriginAndMarkersUnknownStaysSingle() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                probe("a.log", 0, 60L, null, null, null, null, null, GcLogFormat.UNIFIED),
                probe("b.log", 61, 120L, null, null, null, null, null, GcLogFormat.UNIFIED));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertTrue(plan.groups().isEmpty(), "근거 없이 묶었다 — 조용히 잘못 묶는 것이 가장 나쁘다");
        assertTrue(plan.singleReasons().get("b.log").contains("근거"), plan.singleReasons().toString());
    }

    @Test
    @DisplayName("끝 시각을 모르면 겹침을 볼 수 없으므로 거기서 사슬을 끊는다")
    void unknownEndTimeStopsTheChain() {
        List<GcLogGroupPlanner.Probe> in = List.of(
                p("a.log", 0, null),          // 대용량 gz 라 꼬리를 못 읽었다
                p("b.log", 61, 120L));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, RULES);

        assertTrue(plan.groups().isEmpty());
        assertTrue(plan.singleReasons().get("b.log").contains("시각"), plan.singleReasons().toString());
    }

    @Test
    @DisplayName("절대 시각이 없는 로그는 자동으로 묶지 않는다(수동 이어붙이기는 가능)")
    void logsWithoutAbsoluteTimeStaySingle() {
        GcLogAnalyzerService.Peek a = new GcLogAnalyzerService.Peek("a.log", GcLogFormat.UNIFIED, null, 0.003, 100L, 10L);
        GcLogAnalyzerService.Peek b = new GcLogAnalyzerService.Peek("b.log", GcLogFormat.UNIFIED, null, 0.003, 200L, 10L);
        List<GcLogGroupPlanner.Probe> in = List.of(
                new GcLogGroupPlanner.Probe(a, null, "none", "was01", "c1", "G1", null, null),
                new GcLogGroupPlanner.Probe(b, null, "none", "was01", "c1", "G1", null, null));

        assertTrue(GcLogGroupPlanner.plan(in, RULES).groups().isEmpty());
    }

    @Test
    @DisplayName("한 묶음은 상한을 넘지 않는다 — 넘치면 다음 묶음으로 넘어간다")
    void groupNeverExceedsMaxMembers() {
        GcLogGroupPlanner.Rules small = new GcLogGroupPlanner.Rules(60, 720 * 60, 3);
        List<GcLogGroupPlanner.Probe> in = new ArrayList<>();
        for (int i = 0; i < 5; i++) in.add(p("gc.log." + i, i * 10L, i * 10L + 9));

        GcLogGroupPlanner.Plan plan = GcLogGroupPlanner.plan(in, small);

        assertEquals(2, plan.groups().size(), "상한에 닿았는데 나머지를 버렸다");
        assertEquals(3, plan.groups().get(0).members().size(), "상한을 넘겨 묶었다");
        assertEquals(List.of("gc.log.0", "gc.log.1", "gc.log.2"), names(plan.groups().get(0)));
        assertEquals(List.of("gc.log.3", "gc.log.4"), names(plan.groups().get(1)));
        assertTrue(plan.singles().isEmpty());
    }

    @Test
    @DisplayName("입력이 비었거나 하나면 그룹은 없다")
    void emptyOrSingleInput() {
        assertTrue(GcLogGroupPlanner.plan(List.of(), RULES).groups().isEmpty());
        assertTrue(GcLogGroupPlanner.plan(null, RULES).groups().isEmpty());
        GcLogGroupPlanner.Plan one = GcLogGroupPlanner.plan(List.of(p("gc.log", 0, 10L)), RULES);
        assertTrue(one.groups().isEmpty());
        assertEquals(List.of("gc.log"), one.singles());
    }

    @Test
    @DisplayName("축 판정은 3값이다 — 한쪽이 비면 비교하지 않는다")
    void signalsAreThreeValued() {
        GcLogGroupPlanner.Probe known = probe("a.log", 0, 60L, "was01", "c1", "G1", 2L << 30, null, GcLogFormat.UNIFIED);
        GcLogGroupPlanner.Probe blank = probe("b.log", 61, 120L, null, null, null, null, null, GcLogFormat.UNKNOWN);

        assertEquals(GcLogGroupPlanner.Signal.UNKNOWN, GcLogGroupPlanner.originSignal(known, blank));
        assertEquals(GcLogGroupPlanner.Signal.UNKNOWN, GcLogGroupPlanner.jvmSignal(known, blank, new ArrayList<>()));
        assertEquals(GcLogGroupPlanner.Signal.MATCH, GcLogGroupPlanner.originSignal(known, known));
        assertEquals(GcLogGroupPlanner.Signal.MATCH, GcLogGroupPlanner.jvmSignal(known, known, new ArrayList<>()));
        assertFalse(GcLogGroupPlanner.continuous(new GcLogGroupPlanner.Axes(
                GcLogGroupPlanner.Signal.MATCH, GcLogGroupPlanner.Signal.UNKNOWN, GcLogGroupPlanner.Signal.UNKNOWN)));
    }
}

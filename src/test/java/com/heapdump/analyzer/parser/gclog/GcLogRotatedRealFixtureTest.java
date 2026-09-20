package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실측 회전 로그 — 이 서버가 스스로 남긴 {@code logs/gc.log.*} 6개를 앞 150줄씩 잘라 고정한 픽스처 (2026-09-20).
 *
 * <p>이 파일들이 증명하는 것은 하나다: <b>회전 번호는 시간순이 아니다</b>(함정 60).
 * 실제 시각 순서는 {@code .0(22:18) → .3(22:57) → .4(23:09) → .1(23:23) → .2(10:56) → gc.log(12:49)} 로,
 * 이름순으로 이으면 하루가 뒤죽박죽이 된다. JDK 9+ 통합 로깅은 아카이브 슬롯을 돌려 쓰기 때문이다.
 *
 * <p>덤으로 이 로그들은 전부 {@code gc,init} 로 시작해 {@code gc,heap,exit} 로 끝난다 — 이 환경에서는
 * <b>회전 = JVM 재기동</b>이라 여러 실행이 섞인 입력이 예외가 아니라 기본이다. 누수 추세는 그 사실을 알고
 * 가장 긴 실행 안에서만 계산해야 한다(함정 61).
 */
class GcLogRotatedRealFixtureTest {

    /** 이름순(사전식)이 아니라 <b>시각순</b>이 정답인 순서. */
    private static final List<String> CHRONOLOGICAL =
            List.of("gc.log.0", "gc.log.3", "gc.log.4", "gc.log.1", "gc.log.2.gz", "gc.log");

    /** 파일명 사전식 정렬(= 잘못된 순서). */
    private static final List<String> BY_NAME =
            List.of("gc.log", "gc.log.0", "gc.log.1", "gc.log.2.gz", "gc.log.3", "gc.log.4");

    private static GcLogResult result;

    private static byte[] read(String name) throws IOException {
        try (InputStream in = GcLogRotatedRealFixtureTest.class.getResourceAsStream("/gclog/rotated/" + name)) {
            assertNotNull(in, "/gclog/rotated/" + name + " 픽스처가 없다");
            return in.readAllBytes();
        }
    }

    private static GcLogSource source(String name) throws IOException {
        byte[] bytes = read(name);
        return new GcLogSource(name, () -> new java.io.ByteArrayInputStream(bytes), bytes.length, null);
    }

    private static List<GcLogSource> sources(List<String> names) throws IOException {
        List<GcLogSource> out = new ArrayList<>();
        for (String n : names) out.add(source(n));
        return out;
    }

    @BeforeAll
    static void analyzeOnce() throws IOException {
        result = GcLogEngine.analyze(sources(CHRONOLOGICAL), ParseLimits.DEFAULT, null, null);
    }

    @Test
    @DisplayName("회전본 6개를 이어 붙이면 조각 6개·실행 6개로 나뉘고, 조각 순서가 그대로 결과에 남는다")
    void segmentsAndRuns() {
        List<GcLogResult.Segment> segs = result.getSegments();
        assertNotNull(segs, "이어 붙였는데 segments 가 없다");
        assertEquals(6, segs.size());
        assertEquals(CHRONOLOGICAL, segs.stream().map(GcLogResult.Segment::getFilename).toList());
        assertEquals(6, result.getMeta().getRunCount(),
                "이 환경의 회전본은 전부 별개 JVM 실행이다(각 파일이 gc,init 로 시작한다)");
        assertEquals("absolute", result.getMeta().getXBasis());
        assertEquals("UNIFIED", result.getMeta().getFormat());
        // gz 조각도 그대로 읽힌다(조각마다 따로 판별한다)
        assertTrue(segs.get(4).getEventCount() > 0, "gz 조각의 이벤트를 읽지 못했다");
        for (int i = 1; i < segs.size(); i++) {
            assertTrue(segs.get(i).getFirstLine() > segs.get(i - 1).getLastLine(),
                    "줄 번호가 조각마다 1 부터 다시 시작한다 — 전역이어야 원문을 되찾을 수 있다");
        }
    }

    @Test
    @DisplayName("x 축은 되돌아가지 않고, 기간은 조각이 실제로 덮은 시간의 합이다(JVM 이 꺼져 있던 공백 제외)")
    void timelineIsMonotonicAndGapAware() {
        List<Double> xs = result.getSeries().getUptimeSec();
        for (int i = 1; i < xs.size(); i++) {
            assertTrue(xs.get(i) >= xs.get(i - 1), "x 가 되돌아갔다 — uptime 을 그대로 쓴 것(함정 61)");
        }
        GcLogResult.Meta m = result.getMeta();
        assertNotNull(m.getSpanSec());
        assertNotNull(m.getGapSec());
        assertTrue(m.getSpanSec() > 50_000, "전체 범위가 14시간(9/19 22:18 ~ 9/20 12:49) 안팎이어야 한다: " + m.getSpanSec());
        assertTrue(m.getDurationSec() < m.getSpanSec(),
                "재기동 사이 공백이 기간에 그대로 들어갔다 — 처리량·Full GC/h 가 낙관적으로 왜곡된다");
        assertTrue(m.getGapSec() > 0);
    }

    @Test
    @DisplayName("실행이 6개라도 기동 램프로 누수 오탐을 내지 않는다 — 구성은 안내(Info)로만 알린다")
    void noFalseLeakAcrossRestarts() {
        assertFalse(result.getFindings().stream().anyMatch(f -> "LEAK_TREND".equals(f.getCode())),
                "재기동이 섞인 입력에서 누수 의심이 났다(2026-09-14 오탐이 구간 수만큼 증폭된 꼴 — 함정 57/61)");
        GcLogResult.Finding multi = result.getFindings().stream()
                .filter(f -> "MULTI_RUN_SEGMENTS".equals(f.getCode())).findFirst().orElse(null);
        assertNotNull(multi, "이어 붙인 구성을 설명하는 소견이 없다");
        assertEquals("Info", multi.getSeverity(), "회전본이 곧 별개 실행인 것은 흔한 일이라 경고가 아니라 안내여야 한다");
        assertTrue(multi.getTitle().contains("6개"), multi.getTitle());
    }

    @Test
    @DisplayName("이름순으로 이으면 시간이 뒤섞인다 — 정렬 근거가 파일명이 아님을 실측으로 고정")
    void nameOrderIsNotTimeOrder() throws IOException {
        GcLogResult wrong = GcLogEngine.analyze(sources(BY_NAME), ParseLimits.DEFAULT, null, null);
        List<GcLogResult.Segment> segs = wrong.getSegments();
        boolean backwards = false;
        for (int i = 1; i < segs.size(); i++) {
            Long prev = segs.get(i - 1).getFirstTsEpochMs(), cur = segs.get(i).getFirstTsEpochMs();
            if (prev != null && cur != null && cur < prev) { backwards = true; break; }
        }
        assertTrue(backwards, "이 픽스처는 이름순 ≠ 시각순이어야 의미가 있다(회전 슬롯 재사용 실측)");
        assertFalse(CHRONOLOGICAL.equals(BY_NAME), "정렬 기대값이 이름순과 같아졌다 — 픽스처를 다시 만들 것");
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.service.GcLogMatchService.Decision;
import com.heapdump.analyzer.service.GcLogMatchService.DumpFacts;
import com.heapdump.analyzer.service.GcLogMatchService.LogFacts;
import com.heapdump.analyzer.util.JvmHeapCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC 로그 ↔ 힙 덤프 자동 매칭 판정 (2026-09-14). 순수 함수 {@code decide} 만 본다 — DB 없이.
 *
 * <p>고정하는 계약: ① 서버+시간 2점이면 자동 확정 ② 강한 후보가 둘이면 미확정(후보만) ③ mtime 기반 시간은 0.5점(약함)이라
 * 서버와 합쳐도 1.5 → 미확정 ④ 경로 일치는 회전 접미사·%t 를 무시 ⑤ manual(연결·해제 모두)은 자동이 덮지 않는다.
 */
class GcLogMatchServiceTest {

    private static final long T0 = 1_800_000_000L;

    private static LogFacts log(Long serverId, String serverName, String path, Long start, Long end, String src) {
        return new LogFacts(serverId, serverName, path, start, end, src, null);
    }

    private static DumpFacts dump(String fn, Long serverId, String serverName, Long ts, String gcPath) {
        return new DumpFacts(fn, serverId, serverName, ts, gcPath, "/opt/app", null);
    }

    @Test
    @DisplayName("서버 일치 + 덤프 시각이 로그 범위 안 → 유일 강한 후보 → auto 확정")
    void serverAndTimeConfirms() {
        LogFacts lf = log(1L, "was01", "/var/log/gc.log.0", T0, T0 + 3600, "absolute");
        Decision d = GcLogMatchService.decide(lf, List.of(
                dump("a.hprof", 1L, "was01", T0 + 1800, null),
                dump("b.hprof", 2L, "was02", T0 + 1800, null),        // 다른 서버 — 시간만 1점
                dump("c.hprof", 1L, "was01", T0 - 86400, null)),      // 같은 서버 — 하루 전
                10);
        assertNotNull(d.confirmed());
        assertEquals("a.hprof", d.confirmed().dumpFilename());
        assertEquals("server+time", d.reason());
        assertEquals(3, d.candidates().size(), "약한 후보도 목록에는 남는다");
        assertEquals(2.0, d.candidates().get(0).score());
    }

    @Test
    @DisplayName("강한 후보가 둘이면 확정하지 않고 후보만 남긴다 (오답보다 미확정)")
    void twoStrongCandidatesStayUnconfirmed() {
        LogFacts lf = log(1L, "was01", "/var/log/gc.log", T0, T0 + 7200, "absolute");
        Decision d = GcLogMatchService.decide(lf, List.of(
                dump("a.hprof", 1L, "was01", T0 + 100, null),
                dump("b.hprof", 1L, "was01", T0 + 5000, null)), 10);
        assertNull(d.confirmed());
        assertTrue(d.reason().startsWith("ambiguous"));
        assertEquals(2, d.candidates().size());
    }

    @Test
    @DisplayName("허용 오차: 범위 밖 tolerance 안이면 시간 점수, 밖이면 0 — mtime 출처는 tol×6 이지만 0.5점")
    void toleranceAndWeakTime() {
        LogFacts abs = log(1L, "was01", null, T0, T0 + 3600, "absolute");
        assertNotNull(GcLogMatchService.decide(abs, List.of(dump("a.hprof", 1L, "was01", T0 + 3600 + 9 * 60, null)), 10).confirmed(), "9분 뒤 — 허용");
        assertNull(GcLogMatchService.decide(abs, List.of(dump("a.hprof", 1L, "was01", T0 + 3600 + 11 * 60, null)), 10).confirmed(), "11분 뒤 — 시간 0점 → 서버만 1점");

        LogFacts mtime = log(1L, "was01", null, T0, T0 + 3600, "mtime");
        Decision d = GcLogMatchService.decide(mtime, List.of(dump("a.hprof", 1L, "was01", T0 + 3600 + 50 * 60, null)), 10);
        assertNull(d.confirmed(), "mtime 기반은 0.5점 — 서버 1 + 0.5 = 1.5 < 2");
        assertEquals(1.5, d.candidates().get(0).score());
        assertTrue(d.candidates().get(0).reasons().contains("time-weak"));

        LogFacts none = log(1L, "was01", null, T0, T0 + 3600, "none");
        assertEquals(1.0, GcLogMatchService.decide(none, List.of(dump("a.hprof", 1L, "was01", T0 + 10, null)), 10).candidates().get(0).score(), "시간 출처 없음 → 시간 0점");
    }

    @Test
    @DisplayName("경로 신호: 덤프 JVM 의 -Xloggc 경로가 로그 원격 경로와 회전 접미사·%t 무시하고 같으면 1점")
    void pathSignal() {
        LogFacts lf = log(null, "was01", "/var/log/jvm/gc.log.3", null, null, "none");
        Decision d = GcLogMatchService.decide(lf, List.of(dump("a.hprof", null, "was01", null, "/var/log/jvm/gc.log")), 10);
        assertNotNull(d.confirmed(), "서버(이름) + 경로 = 2점");
        assertEquals("server+path", d.reason());

        assertTrue(JvmHeapCapture.sameGcLogPath("/var/log/gc.log", "/var/log/gc.log.1.current", null));
        assertTrue(JvmHeapCapture.sameGcLogPath("/var/log/gc.log", "/var/log/gc.log.2.gz", null));
        assertTrue(JvmHeapCapture.sameGcLogPath("/var/log/gc-%t.log", "/var/log/gc-2026-09-14_00-52-33.log.0", null));
        assertTrue(JvmHeapCapture.sameGcLogPath("/var/log/gc_%p.log", "/var/log/gc_pid12345.log", null));
        assertTrue(JvmHeapCapture.sameGcLogPath("logs/gc.log", "/opt/app/logs/gc.log.0", "/opt/app"), "상대경로는 cwd 기준");
        assertFalse(JvmHeapCapture.sameGcLogPath("/var/log/gc.log", "/var/log/other/gc.log", null));
        assertFalse(JvmHeapCapture.sameGcLogPath(null, "/var/log/gc.log", null));
        assertEquals("/var/log/gc.log", JvmHeapCapture.stripRotation("/var/log/gc.log.3.current"));
        assertEquals("/var/log/gc.log", JvmHeapCapture.stripRotation("/var/log/gc.log.1.gz"));
        assertEquals("/var/log/gc-2026.log", JvmHeapCapture.stripRotation("/var/log/gc-2026.log"));
    }

    @Test
    @DisplayName("서버 판정: id 가 둘 다 있으면 id, 아니면 이름(대소문자 무시)")
    void sameServer() {
        assertTrue(GcLogMatchService.sameServer(1L, "x", 1L, "y"));
        assertFalse(GcLogMatchService.sameServer(1L, "x", 2L, "x"), "id 가 있으면 id 가 우선");
        assertTrue(GcLogMatchService.sameServer(null, "WAS01", 3L, "was01 "));
        assertFalse(GcLogMatchService.sameServer(null, null, 3L, "was01"));
    }

    @Test
    @DisplayName("manual 은 연결이든 해제든 자동이 덮지 않는다")
    void manualIsSticky() {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setMatchSource(GcLogAnalysisEntity.MATCH_NONE);
        assertTrue(GcLogMatchService.mayOverwrite(e));
        e.setMatchSource(GcLogAnalysisEntity.MATCH_AUTO);
        assertTrue(GcLogMatchService.mayOverwrite(e));
        e.setMatchSource(GcLogAnalysisEntity.MATCH_MANUAL);
        e.setMatchedDumpFilename("a.hprof");
        assertFalse(GcLogMatchService.mayOverwrite(e));
        e.setMatchedDumpFilename(null);   // 수동 해제
        assertFalse(GcLogMatchService.mayOverwrite(e), "수동 해제도 manual — 자동 재연결 금지");
    }

    @Test
    @DisplayName("후보가 없으면 no-candidates, 상위 10건만 남긴다")
    void noCandidatesAndCap() {
        LogFacts lf = log(1L, "was01", null, T0, T0 + 10, "absolute");
        assertEquals("no-candidates", GcLogMatchService.decide(lf, List.of(dump("z.hprof", 9L, "other", null, null)), 10).reason());
        java.util.List<DumpFacts> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(dump("d" + i + ".hprof", 1L, "was01", null, null));   // 서버만 1점 × 30
        Decision d = GcLogMatchService.decide(lf, many, 10);
        assertNull(d.confirmed());
        assertEquals(10, d.candidates().size());
        assertEquals("weak", d.reason());
    }
}

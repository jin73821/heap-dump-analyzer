package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import com.heapdump.analyzer.util.JvmHeapCapture;
import com.heapdump.analyzer.util.JvmHeapCapture.Candidate;
import com.heapdump.analyzer.util.JvmHeapCapture.Capture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM 힙 설정 "현재 진실" 관리 테스트 (2026-09-11).
 *
 * <p>급소는 <b>덮어쓰기 규칙</b>이다 — 운영자가 손으로 넣거나 후보에서 고른 값을 재분석이 조용히 되돌리면
 * 사용자는 알 길이 없다. 분석 시 재매칭이 sysProps 로 후보를 확정하는 경로와, 재수집이 출처 서버를 못 찾을 때
 * 정직하게 NO_ORIGIN 을 돌려주는 것도 함께 고정한다. Spring 컨텍스트 없이 Mockito 스텁으로 검증한다.
 */
class JvmHeapInfoServiceTest {

    private static final long GB = 1L << 30;
    private static final long NOW = 1_800_000_000L;

    private AnalysisHistoryRepository historyRepo;
    private DumpTransferLogRepository logRepo;
    private TargetServerRepository serverRepo;
    private RemoteDumpService remote;
    private JvmHeapInfoService svc;

    @BeforeEach
    void setUp() {
        historyRepo = Mockito.mock(AnalysisHistoryRepository.class);
        logRepo = Mockito.mock(DumpTransferLogRepository.class);
        serverRepo = Mockito.mock(TargetServerRepository.class);
        remote = Mockito.mock(RemoteDumpService.class);
        svc = new JvmHeapInfoService(historyRepo, logRepo, serverRepo, remote);
        Mockito.when(historyRepo.save(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Candidate cand(int pid, Long xmx, Map<String, String> markers) {
        return new Candidate(pid, "jeus", NOW - 10_000, "jeus.server.Bootstrapper", "/opt/jeus", "/opt/jdk/bin/java",
                GB, xmx, "cmdline", null, List.of("-Xmx" + JvmHeapCapture.formatSize(xmx), "-XX:+UseG1GC"), 0,
                markers, true, false, false, false);
    }

    private static String twoCandidatesJson() {
        Capture cap = new Capture(1, NOW, NOW, 16 * GB, 8 * GB, 300, false, null,
                List.of(cand(100, 2 * GB, Map.of("jeus.server.name", "srv1")),
                        cand(200, 4 * GB, Map.of("jeus.server.name", "srv2"))),
                null, "ambiguous", List.of(), null);
        return JvmHeapCapture.toJson(cap);
    }

    private static DumpTransferLog logWith(String json) {
        DumpTransferLog l = new DumpTransferLog();
        l.setFilename("java_pid999.hprof");
        l.setRemoteFilename("java_pid999.hprof");
        l.setRemotePath("/dumps/java_pid999.hprof");
        l.setJvmInfo(json);
        return l;
    }

    private static HeapAnalysisResult resultWith(String serverName) {
        HeapAnalysisResult r = new HeapAnalysisResult();
        r.setFilename("java_pid999.hprof");
        r.setSystemProperties(Map.of("jeus.server.name", serverName));
        r.setDumpCreationTime("2026-09-10 03:12:00");
        return r;
    }

    @Test
    void mayOverwriteProtectsManualAndSelected() {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        assertTrue(svc.mayOverwrite(null));
        assertTrue(svc.mayOverwrite(e));
        e.setJvmHeapSource(JvmHeapInfoService.SOURCE_AUTO);
        assertTrue(svc.mayOverwrite(e));
        e.setJvmHeapSource(JvmHeapInfoService.SOURCE_MANUAL);
        assertFalse(svc.mayOverwrite(e));
        e.setJvmHeapSource(JvmHeapInfoService.SOURCE_SELECTED);
        assertFalse(svc.mayOverwrite(e));
    }

    @Test
    void reconcileResolvesAmbiguousCaptureWithSystemProperties() {
        JvmHeapInfoService.Resolution r = svc.reconcile(logWith(twoCandidatesJson()), resultWith("srv2"));
        assertNotNull(r);
        assertTrue(r.matched());
        assertEquals(4 * GB, r.xmx());
        assertEquals(200, r.pid());
        assertEquals("sysprop:jeus.server.name", r.reason());
        assertTrue(r.options().contains("-XX:+UseG1GC"));
        assertNotNull(r.infoJson());

        JvmHeapInfoService.Resolution still = svc.reconcile(logWith(twoCandidatesJson()), resultWith("srv9"));
        assertNotNull(still);
        assertFalse(still.matched(), "sysProps 로도 못 고르면 미확정이어야 한다 — 그럴듯한 오답 금지");
        assertNotNull(still.infoJson(), "후보는 남겨 화면에서 고를 수 있어야 한다");

        assertNull(svc.reconcile(logWith(null), resultWith("srv1")), "캡처가 없으면 null");
        assertNull(svc.reconcile(null, resultWith("srv1")));
    }

    @Test
    void applyRespectsOverwriteRuleAndAmbiguityOnlyUpdatesCandidates() {
        JvmHeapInfoService.Resolution matched = svc.reconcile(logWith(twoCandidatesJson()), resultWith("srv2"));
        JvmHeapInfoService.Resolution ambiguous = svc.reconcile(logWith(twoCandidatesJson()), resultWith("srv9"));

        AnalysisHistoryEntity manual = new AnalysisHistoryEntity();
        manual.setJvmHeapSource(JvmHeapInfoService.SOURCE_MANUAL);
        manual.setJvmXmxBytes(7 * GB);
        svc.apply(manual, matched);
        assertEquals(7 * GB, manual.getJvmXmxBytes(), "수동값은 재분석이 덮지 않는다");
        assertNull(manual.getJvmInfo(), "선택 인덱스가 참조하는 jvm_info 도 건드리지 않는다");

        AnalysisHistoryEntity auto = new AnalysisHistoryEntity();
        svc.apply(auto, matched);
        assertEquals(4 * GB, auto.getJvmXmxBytes());
        assertEquals(JvmHeapInfoService.SOURCE_AUTO, auto.getJvmHeapSource());
        assertEquals(200, auto.getJvmPid());
        assertNotNull(auto.getJvmCapturedAt());

        AnalysisHistoryEntity old = new AnalysisHistoryEntity();
        old.setJvmHeapSource(JvmHeapInfoService.SOURCE_AUTO);
        old.setJvmXmxBytes(2 * GB);
        svc.apply(old, ambiguous);
        assertEquals(2 * GB, old.getJvmXmxBytes(), "미확정이면 기존 자동값은 유지");
        assertNotNull(old.getJvmInfo(), "후보 목록은 최신으로");
    }

    @Test
    void updateManualParsesClearsAndValidates() {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        e.setFilename("x.hprof");
        e.setJvmHeapFlags("estimated");
        Mockito.when(historyRepo.findByFilename("x.hprof")).thenReturn(Optional.of(e));

        Map<String, Object> v = svc.updateManual("x.hprof", "4g", "8192m", 6 * GB);
        assertEquals(4 * GB, e.getJvmXmsBytes());
        assertEquals(8 * GB, e.getJvmXmxBytes());
        assertEquals(JvmHeapInfoService.SOURCE_MANUAL, e.getJvmHeapSource());
        assertNull(e.getJvmHeapFlags(), "수동 입력은 추정 플래그를 지운다");
        assertEquals("8g", v.get("xmx"));
        assertEquals(75, v.get("usedPctOfXmx"));
        assertEquals("수동 입력", v.get("sourceLabel"));

        svc.updateManual("x.hprof", "", " ", null);
        assertNull(e.getJvmXmxBytes());
        assertNull(e.getJvmHeapSource(), "둘 다 비면 미지정으로 초기화");

        assertThrows(IllegalArgumentException.class, () -> svc.updateManual("x.hprof", "abc", "", null));
        assertThrows(IllegalArgumentException.class, () -> svc.updateManual("x.hprof", "8g", "4g", null), "Xms > Xmx");
        assertNull(svc.updateManual("missing.hprof", "1g", "2g", null), "레코드 없으면 null(→404)");
    }

    @Test
    void selectCandidateCopiesValuesAndRejectsBadIndex() {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        e.setFilename("x.hprof");
        e.setJvmInfo(twoCandidatesJson());
        Mockito.when(historyRepo.findByFilename("x.hprof")).thenReturn(Optional.of(e));

        Map<String, Object> v = svc.selectCandidate("x.hprof", 1, null);
        assertEquals(4 * GB, e.getJvmXmxBytes());
        assertEquals(200, e.getJvmPid());
        assertEquals(JvmHeapInfoService.SOURCE_SELECTED, e.getJvmHeapSource());
        assertEquals("후보 선택", v.get("sourceLabel"));
        assertEquals(2, v.get("candidateCount"));
        assertEquals(false, v.get("ambiguous"), "값이 정해지면 더는 미확정이 아니다");

        assertThrows(IllegalArgumentException.class, () -> svc.selectCandidate("x.hprof", 2, null));
        assertThrows(IllegalArgumentException.class, () -> svc.selectCandidate("x.hprof", -1, null));
        e.setJvmInfo(null);
        assertThrows(IllegalArgumentException.class, () -> svc.selectCandidate("x.hprof", 0, null));
    }

    @Test
    void recollectReportsNoOriginAndOverridesWhenMatched() {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        e.setFilename("x.hprof");
        e.setJvmHeapSource(JvmHeapInfoService.SOURCE_MANUAL);
        e.setJvmXmxBytes(1 * GB);
        Mockito.when(historyRepo.findByFilename("x.hprof")).thenReturn(Optional.of(e));

        JvmHeapInfoService.RecollectResult none = svc.recollect("x.hprof", null, null, null);
        assertEquals("NO_ORIGIN", none.code(), "출처 서버를 모르면 정직하게 실패");
        assertEquals(1 * GB, e.getJvmXmxBytes());

        // server_name 만 있어도(수동 업로드 덤프) 등록 서버명과 같으면 재수집 가능
        TargetServer srv = new TargetServer();
        srv.setId(7L);
        srv.setName("WAS01");
        e.setServerName("was01");
        Mockito.when(serverRepo.findAll()).thenReturn(List.of(srv));
        Capture single = new Capture(1, NOW, NOW, 16 * GB, 8 * GB, 300, false, null,
                List.of(cand(300, 6 * GB, Map.of())), null, null, List.of(), null);
        Mockito.when(remote.captureJvmInfo(Mockito.eq(srv), Mockito.any())).thenReturn(JvmHeapCapture.toJson(single));

        JvmHeapInfoService.RecollectResult ok = svc.recollect("x.hprof", null, null, 3 * GB);
        assertNull(ok.code());
        assertFalse(ok.needsSelection());
        assertEquals(6 * GB, e.getJvmXmxBytes(), "재수집은 사용자가 누른 것이라 수동값도 덮는다");
        assertEquals(JvmHeapInfoService.SOURCE_AUTO, e.getJvmHeapSource());
        assertEquals(50, ok.view().get("usedPctOfXmx"));
        Mockito.verify(remote).clearJvmCaptureCache();

        // 후보 다수 → 값은 두고 후보만 갱신 + needsSelection
        Mockito.when(remote.captureJvmInfo(Mockito.eq(srv), Mockito.any())).thenReturn(twoCandidatesJson());
        JvmHeapInfoService.RecollectResult multi = svc.recollect("x.hprof", null, null, null);
        assertNull(multi.code());
        assertTrue(multi.needsSelection());
        assertEquals(2, multi.candidates().size());
        assertEquals(6 * GB, e.getJvmXmxBytes());

        // 수집 자체가 실패(끊김·후보 0) → SSH_FAIL
        Capture failed = new Capture(1, NOW, null, 0, 0, -1, true, null, List.of(), null, null, List.of(), "수집 실패: timeout");
        Mockito.when(remote.captureJvmInfo(Mockito.eq(srv), Mockito.any())).thenReturn(JvmHeapCapture.toJson(failed));
        assertEquals("SSH_FAIL", svc.recollect("x.hprof", null, null, null).code());

        assertEquals("NOT_FOUND", svc.recollect("missing.hprof", null, null, null).code());
    }

    /**
     * ⓘ 툴팁 문구는 서버가 유일한 출처다 — 칩은 편집·후보 선택·재수집 때마다 JS 가 다시 그리므로, 문구를
     * 양쪽에서 조립하면 예외 없이 갈라진다. 수집 시각은 이 툴팁이 화면에 처음 노출하는 값이라 함께 고정한다.
     */
    @Test
    void tipTextCarriesSourceFlagsAndCapturedAt() {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        e.setFilename("x.hprof");
        e.setJvmXmsBytes(GB / 2);
        e.setJvmXmxBytes(GB);
        e.setJvmHeapSource(JvmHeapInfoService.SOURCE_AUTO);
        e.setJvmHeapFlags(JvmHeapCapture.FLAG_RESTARTED);
        e.setJvmOptions("-Xms512m -Xmx1024m");
        e.setJvmPid(710585);
        e.setJvmCapturedAt(LocalDateTime.of(2026, 9, 12, 21, 23, 49));
        Mockito.when(historyRepo.findByFilename("x.hprof")).thenReturn(Optional.of(e));

        String tip = (String) svc.view("x.hprof", null).get("tipText");
        assertTrue(tip.contains("Xms 512m / Xmx 1g"), tip);
        assertTrue(tip.contains("출처: 자동 수집"), tip);
        assertTrue(tip.contains("수집 시각: 2026-09-12 21:23:49 (pid 710585)"), tip);
        assertTrue(tip.contains("재기동 후 — 덤프 생성 이후에 기동된 프로세스의 설정"),
                "플래그는 라벨만 두지 말고 뜻을 함께 — 배지 시절엔 hover 로만 보였다: " + tip);
        assertTrue(tip.contains("JVM 옵션: -Xms512m -Xmx1024m"), tip);
        assertTrue(tip.contains("\n"), "여러 줄 — krds 팝오버가 pre-wrap 으로 표시한다");

        // 수동 입력은 수집 시각이 없다(updateManual 이 지운다) — 없는 줄을 만들지 않아야 한다
        svc.updateManual("x.hprof", "1g", "2g", null);
        String manual = (String) svc.view("x.hprof", null).get("tipText");
        assertTrue(manual.contains("출처: 수동 입력"), manual);
        assertFalse(manual.contains("수집 시각"), "수동 입력에 수집 시각이 남으면 거짓 정보다: " + manual);

        // 미지정: 화면에는 '미지정' 한 단어뿐이라 무엇을 해야 하는지 툴팁이 설명해야 한다
        String none = (String) svc.view("nope.hprof", null).get("tipText");
        assertTrue(none.contains("미지정"), none);
        assertTrue(none.contains("자동 수집") && none.contains("직접 입력"), none);
    }

    @Test
    void viewIsSafeWithoutEntityAndSummaryReflectsStatus() {
        Map<String, Object> empty = svc.view("nope.hprof", null);
        assertEquals(false, empty.get("hasValue"));
        assertEquals("미지정", empty.get("summary"));
        assertEquals(false, empty.get("recollectable"));
        assertEquals("미지정", svc.label("nope.hprof"));

        assertEquals("none", JvmHeapInfoService.summaryForClient(null).get("status"));
        assertEquals("ambiguous", JvmHeapInfoService.summaryForClient(twoCandidatesJson()).get("status"));
        Capture one = new Capture(1, NOW, NOW, 16 * GB, 8 * GB, 300, false, null,
                List.of(cand(300, 6 * GB, Map.of())), 0, "single", List.of(JvmHeapCapture.FLAG_RESTARTED), null);
        Map<String, Object> s = JvmHeapInfoService.summaryForClient(JvmHeapCapture.toJson(one));
        assertEquals("collected", s.get("status"));
        assertEquals("6g", s.get("xmx"));
        assertTrue(((List<?>) s.get("flagLabels")).contains("재기동 후"));

        assertEquals(1_800_000_000L - 1_800_000_000L + JvmHeapInfoService.toEpoch("2026-09-10 03:12:00"),
                JvmHeapInfoService.toEpoch("2026-09-10 03:12:00"));
        assertNull(JvmHeapInfoService.toEpoch("2026. 9. 10."));
        assertNull(JvmHeapInfoService.toEpoch(null));
    }
}

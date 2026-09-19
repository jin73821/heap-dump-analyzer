package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.ThreadInfo;
import com.heapdump.analyzer.model.dto.AlertItem;
import com.heapdump.analyzer.model.dto.DetectKindSummary;
import com.heapdump.analyzer.model.entity.AlertAck;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AiInsightRepository;
import com.heapdump.analyzer.repository.AlertAckRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 알림 현황판 — 심각도 판정·알람 생성(순수 함수) + 수집·ACK(목 리포지토리).
 * 조용히 틀리는 부류를 고정한다: OOM 만 있고 suspect 0건인 덤프 누락, 세션·어휘 혼재, 삭제 파일 노출, 임의 key ACK.
 */
class AlertBoardServiceTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 19, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 19, 12, 0);

    // ── 순수 함수 ─────────────────────────────────────────────

    @Test
    @DisplayName("심각도 어휘 정규화 — 대소문자·공백·레거시(warning/info), 모르는 값은 null")
    void normalizeSeverity() {
        assertEquals("Critical", AlertBoardService.normalizeSeverity("critical"));
        assertEquals("Critical", AlertBoardService.normalizeSeverity(" CRITICAL "));
        assertEquals("High", AlertBoardService.normalizeSeverity("warning"));
        assertEquals("Low", AlertBoardService.normalizeSeverity("info"));
        assertEquals("Medium", AlertBoardService.normalizeSeverity("Medium"));
        assertNull(AlertBoardService.normalizeSeverity(null));
        assertNull(AlertBoardService.normalizeSeverity(""));
        assertNull(AlertBoardService.normalizeSeverity("심각"));
    }

    @Test
    @DisplayName("코어 심각도 — AI 우선, AI 가 없거나 모르는 값이면 시그널 폴백")
    void coreSeverity() {
        assertEquals("Critical", AlertBoardService.coreSignalSeverity("SIGSEGV"));
        assertEquals("Critical", AlertBoardService.coreSignalSeverity("sigbus (7)"));
        assertEquals("Critical", AlertBoardService.coreSignalSeverity("SIGILL"));
        assertEquals("High", AlertBoardService.coreSignalSeverity("SIGABRT"));
        assertEquals("High", AlertBoardService.coreSignalSeverity("SIGFPE"));
        assertEquals("Medium", AlertBoardService.coreSignalSeverity("SIGKILL"));
        assertEquals("Medium", AlertBoardService.coreSignalSeverity(null));

        assertEquals("Low", AlertBoardService.coreSeverity("Low", "SIGSEGV"), "AI 판정이 시그널보다 우선한다");
        assertEquals("Critical", AlertBoardService.coreSeverity("critical", "SIGABRT"));
        assertEquals("Critical", AlertBoardService.coreSeverity(null, "SIGSEGV"));
        assertEquals("High", AlertBoardService.coreSeverity("알수없음", "SIGABRT"), "모르는 AI 값은 폴백");
    }

    @Test
    @DisplayName("key 도메인 검증 — 임의 문자열은 거부")
    void parseKeyDomain() {
        assertEquals("heap", AlertBoardService.parseKeyDomain("heap:a.hprof:1"));
        assertEquals("gclog", AlertBoardService.parseKeyDomain("gclog:gc.log:0"));
        assertThrows(IllegalArgumentException.class, () -> AlertBoardService.parseKeyDomain("evil:x:1"));
        assertThrows(IllegalArgumentException.class, () -> AlertBoardService.parseKeyDomain("noprefix"));
        assertThrows(IllegalArgumentException.class, () -> AlertBoardService.parseKeyDomain(" "));
        assertThrows(IllegalArgumentException.class, () -> AlertBoardService.parseKeyDomain(null));
        assertThrows(IllegalArgumentException.class, () -> AlertBoardService.parseKeyDomain("heap:" + "x".repeat(600)));
    }

    @Test
    @DisplayName("힙 알람 — suspect 0건이어도 OOM 이면 알람, 제목은 OOM 우선, 실패·무해 덤프는 알람 없음")
    void heapAlert() {
        AlertItem oomOnly = AlertBoardService.buildHeapAlert(heap("a.hprof", "SUCCESS", T1), result(1, "Java heap space"), false);
        assertNotNull(oomOnly, "OOM 만 있는 덤프를 놓쳤다 — aggregateDetections 의 suspectCount==0 skip 과 같은 결함");
        assertEquals("HEAP_OOM", oomOnly.getReasonCode());
        assertTrue(oomOnly.getTitle().startsWith("OOM 탐지"), oomOnly.getTitle());
        assertTrue(oomOnly.getTitle().contains("스레드 1개"));
        assertEquals("/analyze/result/a.hprof", oomOnly.getLink());
        assertEquals("heap:a.hprof:" + AlertBoardService.epochOf(T1), oomOnly.getKey());

        AlertItem both = AlertBoardService.buildHeapAlert(heap("a.hprof", "SUCCESS", T1), result(2, null, "critical", "low"), false);
        assertEquals("HEAP_OOM", both.getReasonCode());
        assertTrue(both.getDetail().contains("Critical 누수 의심 1건"));

        AlertItem crit = AlertBoardService.buildHeapAlert(heap("a.hprof", "SUCCESS", T1), result(0, null, "CRITICAL", "critical"), true);
        assertEquals("HEAP_CRITICAL_SUSPECT", crit.getReasonCode());
        assertEquals("Critical 누수 의심 2건", crit.getTitle());
        assertNull(crit.getLink(), "삭제된 파일은 링크가 없다");

        assertNull(AlertBoardService.buildHeapAlert(heap("a.hprof", "SUCCESS", T1), result(0, null, "high"), false));
        assertNull(AlertBoardService.buildHeapAlert(heap("a.hprof", "ERROR", T1), result(1, "x"), false));
        assertNull(AlertBoardService.buildHeapAlert(heap("a.hprof", "SUCCESS", T1), null, false));

        assertEquals("Critical", AlertBoardService.heapFileSeverity(result(1, null)));
        assertEquals("High", AlertBoardService.heapFileSeverity(result(0, null, "low", "high")));
        assertNull(AlertBoardService.heapFileSeverity(result(0, null)));
    }

    @Test
    @DisplayName("코어·GC 알람 — AI 가 낮추면 SIGSEGV 도 알람 없음, 발생 시각은 분석·AI 중 늦은 쪽, 압박 Full GC 문구")
    void coreAndGcAlert() {
        assertNull(AlertBoardService.buildCoreAlert(core("c1", "SUCCESS", "SIGSEGV", T1, false), "Low", T2));
        assertNull(AlertBoardService.buildCoreAlert(core("c1", "SUCCESS", "SIGABRT", T1, false), null, null));
        assertNull(AlertBoardService.buildCoreAlert(core("c1", "ERROR", "SIGSEGV", T1, false), null, null));

        AlertItem bySignal = AlertBoardService.buildCoreAlert(core("c 1", "SUCCESS", "SIGSEGV", T1, false), null, null);
        assertEquals("CORE_FATAL_SIGNAL", bySignal.getReasonCode());
        assertEquals("/core-dump/analyze/c%201", bySignal.getLink(), "파일명은 경로 세그먼트로 인코딩");

        AlertItem byAi = AlertBoardService.buildCoreAlert(core("c1", "SUCCESS", "SIGABRT", T1, false), "critical", T2);
        assertEquals("CORE_AI_CRITICAL", byAi.getReasonCode());
        assertEquals(AlertBoardService.epochOf(T2), byAi.getOccurredAtEpoch(), "AI 가 나중에 Critical 을 주면 그 시각으로 최신에 뜬다");
        assertEquals("core:c1:" + AlertBoardService.epochOf(T1), byAi.getKey(), "key 는 분석 시각 기준 — AI 재실행에 흔들리지 않는다");

        AlertItem gc = AlertBoardService.buildGcAlert(gc("gc.log", "SUCCESS", "Critical", 3, T1, false));
        assertTrue(gc.getTitle().contains("메모리 압박 Full GC 3회"), gc.getTitle());
        assertEquals("GC 로그 Critical", AlertBoardService.buildGcAlert(gc("gc.log", "SUCCESS", "Critical", 0, T1, false)).getTitle());
        assertNull(AlertBoardService.buildGcAlert(gc("gc.log", "SUCCESS", "High", 9, T1, false)));
        assertNull(AlertBoardService.buildGcAlert(gc("gc.log", "ERROR", "Critical", 9, T1, false)));
    }

    @Test
    @DisplayName("정렬은 최신순, 동률이면 key 순(결정적)")
    void sortNewest() {
        List<AlertItem> l = new ArrayList<>();
        l.add(item("heap:b:1", 100)); l.add(item("heap:a:1", 100)); l.add(item("core:z:1", 200));
        AlertBoardService.sortNewest(l);
        assertEquals(List.of("core:z:1", "heap:a:1", "heap:b:1"), l.stream().map(AlertItem::getKey).toList());
    }

    // ── 수집·ACK ──────────────────────────────────────────────

    private HeapDumpAnalyzerService analyzer;
    private AnalysisHistoryRepository heapRepo;
    private CoreDumpAnalysisRepository coreRepo;
    private GcLogAnalyzerService gcService;
    private AiInsightRepository insightRepo;
    private AlertAckRepository ackRepo;
    private AlertBoardService service;

    @BeforeEach
    void setUp() {
        analyzer = Mockito.mock(HeapDumpAnalyzerService.class);
        heapRepo = Mockito.mock(AnalysisHistoryRepository.class);
        coreRepo = Mockito.mock(CoreDumpAnalysisRepository.class);
        gcService = Mockito.mock(GcLogAnalyzerService.class);
        insightRepo = Mockito.mock(AiInsightRepository.class);
        ackRepo = Mockito.mock(AlertAckRepository.class);
        service = new AlertBoardService(analyzer, heapRepo, coreRepo, gcService, insightRepo, ackRepo);

        // 힙: live.hprof(OOM, 디스크에 있음) · gone.hprof(critical suspect, 파일 없음) · new.hprof(기록 없는 디스크 파일)
        Mockito.when(analyzer.listFiles()).thenReturn(List.of(file("live.hprof"), file("new.hprof")));
        Mockito.when(heapRepo.findAllByOrderByAnalyzedAtDesc())
                .thenReturn(List.of(heap("live.hprof", "SUCCESS", T2), heap("gone.hprof", "SUCCESS", T1)));
        Mockito.when(analyzer.getCachedResult("live.hprof")).thenReturn(result(1, "Java heap space"));
        Mockito.when(analyzer.getCachedResult("gone.hprof")).thenReturn(result(0, null, "critical"));
        Mockito.when(coreRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(core("core.1", "SUCCESS", "SIGSEGV", T1, false), core("core.2", "SUCCESS", "SIGABRT", T1, false)));
        Mockito.when(insightRepo.findByFilenameStartingWith("__core__:")).thenReturn(List.of());
        Mockito.when(gcService.history())
                .thenReturn(List.of(gc("gc.log", "SUCCESS", "Critical", 2, T1, false), gc("ok.log", "SUCCESS", "Low", 0, T1, false)));
        Mockito.when(ackRepo.findAll()).thenReturn(List.of());
    }

    @Test
    @DisplayName("삭제된 파일 — 비관리자·말풍선에는 없고 관리자 현황판에만 보인다. 기록 없는 디스크 파일은 미분석")
    void deletedVisibility() {
        assertEquals(List.of("heap:live.hprof", "core:core.1", "gclog:gc.log"),
                service.alerts(false).stream().map(a -> a.getDomain() + ":" + a.getFilename()).toList());
        assertEquals(4, service.alerts(true).size());
        assertEquals(3, ((List<?>) service.top(10).get("alerts")).size(), "말풍선은 관리자에게도 삭제 파일을 싣지 않는다");

        AlertBoardService.Board user = service.board(false);
        assertEquals(2, user.kpi.get("heap").getTotal());
        assertEquals(1, user.kpi.get("heap").getNotAnalyzed());
        assertEquals(1, user.kpi.get("heap").getCritical());
        AlertBoardService.Board admin = service.board(true);
        assertEquals(2, admin.kpi.get("heap").getTotal(), "KPI 는 관리자에게도 현존 파일만 — 삭제된 파일은 '현황' 이 아니다");
        assertTrue(admin.rows.get("heap").stream().anyMatch(r -> r.isFileDeleted() && "gone.hprof".equals(r.getFilename())),
                "관리자에게는 삭제된 파일 행을 보낸다(화면이 기본으로 숨긴다)");
        assertFalse(user.rows.get("heap").stream().anyMatch(r -> r.isFileDeleted()), "비관리자에게는 보내지 않는다");
        assertEquals(2, admin.deletedCount, "삭제된 행 1 + 삭제된 파일 알람 1");
        assertEquals(0, user.deletedCount);
        assertEquals(3, admin.unackedCount, "미확인 건수는 삭제 파일을 세지 않는다(말풍선 배지와 같은 수)");

        // AI 인사이트는 접두 조회 한 번 — 행마다 findByFilename 을 부르면 N+1
        Mockito.verify(insightRepo, Mockito.never()).findByFilename(anyString());
    }

    @Test
    @DisplayName("대시보드 요약 — 파일 단위 건수, Critical 우선")
    void dashboardKinds() {
        Map<String, DetectKindSummary> m = service.dashboardKinds();
        DetectKindSummary core = m.get("core");
        assertEquals(1, core.getCritical());
        assertEquals(1, core.getHigh());
        assertEquals("core.1", core.getItems().get(0).getFilename());
        assertEquals("SIGSEGV", core.getItems().get(0).getKeySignal());
        assertEquals(2, m.get("gclog").getTotal());
        assertEquals("압박 Full GC 2회", m.get("gclog").getItems().get(0).getKeySignal());
    }

    @Test
    @DisplayName("ACK — 확인하면 말풍선에서 빠지고 확인자가 실린다. 없는 key 는 400, 이미 확인·동시 확인은 흡수")
    void ack() {
        String key = "heap:live.hprof:" + AlertBoardService.epochOf(T2);
        assertTrue(service.ack(key, "alice"));
        Mockito.verify(ackRepo).save(any(AlertAck.class));

        AlertAck saved = new AlertAck();
        saved.setAlertKey(key); saved.setAckedBy("alice"); saved.setAckedAt(T2);
        Mockito.when(ackRepo.findAll()).thenReturn(List.of(saved));
        Mockito.when(ackRepo.findByAlertKey(key)).thenReturn(Optional.of(saved));

        assertEquals(2, service.unacked().size());
        AlertItem acked = service.alerts(false).stream().filter(a -> a.getKey().equals(key)).findFirst().orElseThrow();
        assertTrue(acked.isAcked());
        assertEquals("alice", acked.getAckedBy());
        assertEquals("2026-09-19 12:00", acked.getAckedAtLabel());
        assertFalse(service.ack(key, "bob"), "이미 확인된 알람 — 기존 확인자를 덮지 않는다");

        assertThrows(IllegalArgumentException.class, () -> service.ack("heap:nope.hprof:1", "alice"));
        assertThrows(IllegalArgumentException.class, () -> service.ack("evil", "alice"));

        assertEquals("alice", service.unack(key));
        Mockito.verify(ackRepo).deleteByAlertKey(key);
        assertNull(service.unack("core:none:1"));

        Mockito.when(ackRepo.save(any(AlertAck.class))).thenThrow(new DataIntegrityViolationException("dup"));
        assertFalse(service.ack("gclog:gc.log:" + AlertBoardService.epochOf(T1), "bob"), "동시 확인은 예외가 아니라 '이미 확인됨'");
    }

    @Test
    @DisplayName("최근 분석 현황·KPI 는 Files 페이지 Others 탭 파일을 싣지 않는다 — 알람은 분석 결과 기준이라 유지")
    void othersExcludedFromTables() {
        Mockito.when(analyzer.isAllowAllExtensions()).thenReturn(true);
        Mockito.when(analyzer.loadFileClassifications()).thenReturn(Map.of(
                "tool.jar", "others", "setup.rpm", "heapdump", "crash.core", "core", "junk.txt", "others"));
        // 힙 저장소: 라벨 없는 png·gc.log(=others), others 라벨 jar, 힙 덤프 라벨 rpm, core 라벨, 정상 hprof
        Mockito.when(analyzer.listFiles()).thenReturn(List.of(file("live.hprof"), file("front.png"), file("gc.log"),
                file("tool.jar"), file("setup.rpm"), file("crash.core"), file("noext_oom")));
        // 확장자 없는 파일이 MAT 분석에서 OOM 이 나온 경우 — 이름은 others 지만 알람은 남겨야 한다
        // .tar.gz 기록(업로드 유령·분석 실패)은 단독 .gz 라 힙 덤프가 아니다 — 라벨이 없어도 others
        Mockito.when(heapRepo.findAllByOrderByAnalyzedAtDesc())
                .thenReturn(List.of(heap("live.hprof", "SUCCESS", T2), heap("tool.jar", "ERROR", T1), heap("noext_oom", "SUCCESS", T1),
                        heap("heapApp_20260622.tar.gz", "ERROR", T1), heap("bundle.tar.gz", "NOT_ANALYZED", T1)));
        Mockito.when(analyzer.getCachedResult("noext_oom")).thenReturn(result(1, "Java heap space"));
        Mockito.when(coreRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(core("core.1", "SUCCESS", "SIGSEGV", T1, false), core("junk.txt", "ERROR", null, T1, false)));

        AlertBoardService.Board b = service.board(true);
        assertEquals(List.of("live.hprof", "setup.rpm"),
                b.rows.get("heap").stream().map(r -> r.getFilename()).sorted().toList(),
                "others·core 라벨 파일이 힙 덤프 표에 남았다");
        assertEquals(2, b.kpi.get("heap").getTotal(), "KPI 도 표와 같은 기준");
        assertEquals(1, b.kpi.get("heap").getNotAnalyzed());
        assertEquals(0, b.kpi.get("heap").getError(), "others 파일의 분석 실패가 힙 덤프 실패로 세였다");
        assertEquals(List.of("core.1"), b.rows.get("core").stream().map(r -> r.getFilename()).toList());
        assertTrue(b.alerts.stream().anyMatch(a -> "noext_oom".equals(a.getFilename())), "분석 결과가 OOM 인 알람은 이름과 무관하게 유지");
    }

    @Test
    @DisplayName("한 도메인의 조회 실패가 나머지 현황을 막지 않는다")
    void domainFailureIsIsolated() {
        Mockito.when(coreRepo.findAllByOrderByCreatedAtDesc()).thenThrow(new RuntimeException("DB down"));
        AlertBoardService.Snapshot s = service.collect();
        assertTrue(s.partial);
        assertEquals(2, s.alerts.stream().filter(a -> !a.isFileDeleted()).count());
        assertTrue(s.rows.get("core").isEmpty());
        assertFalse(s.rows.get("gclog").isEmpty());
    }

    // ── 픽스처 ────────────────────────────────────────────────

    private static AnalysisHistoryEntity heap(String fn, String status, LocalDateTime at) {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        e.setFilename(fn); e.setStatus(status); e.setAnalyzedAt(at); e.setServerName("was1"); e.setSuspectCount(1);
        return e;
    }

    private static HeapAnalysisResult result(int oomThreads, String oomType, String... suspectSeverities) {
        HeapAnalysisResult r = new HeapAnalysisResult();
        List<ThreadInfo> threads = new ArrayList<>();
        for (int i = 0; i < oomThreads; i++) {
            ThreadInfo t = new ThreadInfo();
            t.setOom(true); t.setOomType(oomType);
            threads.add(t);
        }
        r.setThreadInfos(threads);
        List<LeakSuspect> suspects = new ArrayList<>();
        for (String sev : suspectSeverities) {
            LeakSuspect s = new LeakSuspect();
            s.setSeverity(sev);
            suspects.add(s);
        }
        r.setLeakSuspects(suspects);
        return r;
    }

    private static CoreDumpAnalysisEntity core(String fn, String status, String signal, LocalDateTime at, boolean deleted) {
        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
        e.setFilename(fn); e.setStatus(status); e.setCrashSignal(signal); e.setAnalyzedAt(at); e.setFileDeleted(deleted);
        return e;
    }

    private static GcLogAnalysisEntity gc(String fn, String status, String sev, int pressure, LocalDateTime at, boolean deleted) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename(fn); e.setStatus(status); e.setSeverity(sev); e.setPressureFullGcCount(pressure);
        e.setFindingsCount(4); e.setAnalyzedAt(at); e.setFileDeleted(deleted);
        return e;
    }

    private static HeapDumpFile file(String name) {
        HeapDumpFile f = new HeapDumpFile();
        f.setName(name);
        return f;
    }

    private static AlertItem item(String key, long epoch) {
        AlertItem a = new AlertItem();
        a.setKey(key); a.setOccurredAtEpoch(epoch);
        return a;
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.repository.GcLogResultDetailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 묶기(link)와 업로드 직후 자동 그룹화 — 서비스 계약 (2026-09-21).
 *
 * <p>이 테스트의 존재 이유는 한 줄이다: <b>묶기는 분석이 아니다.</b> 자동 경로가 분석을 제출하는 순간
 * "묶기만 자동, 분석은 사용자" 라는 요구가 깨진다. 함께 고정하는 것 — {@code file_size} 는 대표 것(함정 63),
 * 링크는 {@code forgetAnalysis} 를 쓰지 않아 서버명·인스턴스명이 살아남는다, 같은 이름 재업로드는 {@code part_of} 까지 끊는다.
 */
class GcLogGroupLinkServiceTest {

    @TempDir Path root;
    private Path gcDir;
    private GcLogAnalysisRepository repo;
    private GcLogResultDetailRepository detailRepo;
    private AiInsightManager insight;
    private HeapDumpConfig config;
    private GcLogAnalyzerService service;
    private final Map<String, GcLogAnalysisEntity> store = new LinkedHashMap<>();

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneId.systemDefault());
    private static final long T0 = Instant.parse("2026-09-19T13:18:00Z").toEpochMilli();
    private static final long MIN = 60_000L;

    @BeforeEach
    void setUp() throws Exception {
        gcDir = Files.createDirectories(root.resolve("gclogs/dumpfiles"));
        config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getGcLogDumpFilesDirectory()).thenReturn(gcDir.toString());
        Mockito.when(config.getGcLogMaxFileBytes()).thenReturn(2L * 1024 * 1024 * 1024);
        Mockito.when(config.getGcLogMaxLineChars()).thenReturn(4096);
        Mockito.when(config.getGcLogAnalysisTimeoutMinutes()).thenReturn(10);
        Mockito.when(config.isGcLogGroupEnabled()).thenReturn(true);
        Mockito.when(config.getGcLogGroupMaxOverlapSeconds()).thenReturn(60L);
        Mockito.when(config.getGcLogGroupMaxGapMinutes()).thenReturn(720L);
        Mockito.when(config.getGcLogGroupGzTailMaxBytes()).thenReturn(64L * 1024 * 1024);

        repo = Mockito.mock(GcLogAnalysisRepository.class);
        Mockito.when(repo.findByFilename(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        Mockito.when(repo.save(Mockito.any(GcLogAnalysisEntity.class))).thenAnswer(inv -> {
            GcLogAnalysisEntity e = inv.getArgument(0);
            store.put(e.getFilename(), e);
            return e;
        });
        Mockito.when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(inv -> new ArrayList<>(store.values()));
        Mockito.when(repo.findByPartOf(Mockito.anyString())).thenAnswer(inv -> store.values().stream()
                .filter(e -> inv.getArgument(0).equals(e.getPartOf())).toList());
        Mockito.when(repo.findByAnalysisName(Mockito.anyString())).thenAnswer(inv -> store.values().stream()
                .filter(e -> inv.getArgument(0).equals(e.getAnalysisName())).toList());

        detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        Mockito.when(detailRepo.findByFilename(Mockito.anyString())).thenReturn(Optional.empty());
        insight = Mockito.mock(AiInsightManager.class);
        service = new GcLogAnalyzerService(config, repo, detailRepo, Mockito.mock(AnalysisHistoryRepository.class),
                Mockito.mock(GcLogMatchService.class), Mockito.mock(LlmConfigService.class), insight,
                Mockito.mock(DumpTransferLogRepository.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    // ── 픽스처 ──────────────────────────────────────────────────

    /** JDK 8 Parallel 헤더 — 수집기·최대 힙 마커가 여기서 나온다. */
    private static String header() {
        return "OpenJDK 64-Bit Server VM (25.422-b05) for linux-amd64 JRE (1.8.0_422-b05), built on Jul 11 2024\n"
                + "Memory: 4k page, physical 131479652k(65699592k free)\n"
                + "CommandLine flags: -XX:MaxHeapSize=2147483648 -XX:+UseParallelGC\n\n";
    }

    private static String logText(long startMs, int events) {
        StringBuilder sb = new StringBuilder(header());
        for (int i = 0; i < events; i++) {
            sb.append(ISO.format(Instant.ofEpochMilli(startMs + i * MIN)))
              .append(String.format(Locale.ROOT, ": %.3f: ", 3.0 + i * 60))
              .append("[GC (Allocation Failure) [PSYoungGen: 524800K->39141K(611840K)] 500000K->40000K(2010112K), "
                      + "0.0500000 secs] [Times: user=0.10 sys=0.01, real=0.05 secs]\n");
        }
        return sb.toString();
    }

    private GcLogAnalysisEntity put(String name, long startMinute, int events, String server, String instance)
            throws Exception {
        Files.writeString(gcDir.resolve(name), logText(T0 + startMinute * MIN, events), StandardCharsets.UTF_8);
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setId((long) (store.size() + 1));
        e.setFilename(name);
        e.setFileSize(gcDir.resolve(name).toFile().length());
        e.setServerName(server);
        e.setInstanceName(instance);
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setAnalysisDate(LocalDate.of(2026, 9, 19));
        store.put(name, e);
        return e;
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 묶기는 분석을 시작하지 않는다 — 대표는 미분석으로 남는다")
    void linkDoesNotStartAnalysis() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");

        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");

        GcLogAnalysisEntity rep = store.get("gc.log");
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, rep.getStatus(), "묶기가 분석을 시작했다");
        assertFalse(service.isAnalyzing("gc.log"));
        assertEquals(2, rep.getMemberCount());
        assertEquals("gc.log", store.get("gc.log.0").getPartOf(), "구성원 역참조가 없다");
        assertNull(rep.getPartOf(), "대표에 part_of 가 붙었다");
    }

    @Test
    @DisplayName("묶어도 file_size 는 대표 자신의 크기 — 합계는 members_bytes (함정 63)")
    void linkKeepsRepresentativeFileSize() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");
        long own = gcDir.resolve("gc.log").toFile().length();
        long sum = own + gcDir.resolve("gc.log.0").toFile().length();

        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");

        GcLogAnalysisEntity rep = store.get("gc.log");
        assertEquals(own, rep.getFileSize(), "file_size 에 합계를 넣었다 — listExistingFiles 가 결과를 지운다");
        assertEquals(sum, rep.getMembersBytes());
    }

    @Test
    @DisplayName("묶기는 출처를 지우지 않는다 — forgetAnalysis 를 쓰면 서버명·인스턴스명이 날아간다")
    void linkKeepsOriginAndDropsOnlyResult() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        GcLogAnalysisEntity rep = put("gc.log", 30, 5, "was01", "c1");
        rep.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        rep.setSeverity("High");
        rep.setEventCount(1234);
        rep.setMatchedDumpFilename("app.hprof");

        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");

        GcLogAnalysisEntity after = store.get("gc.log");
        assertEquals("was01", after.getServerName(), "묶기가 호스트네임을 날렸다");
        assertEquals("c1", after.getInstanceName(), "묶기가 인스턴스명을 날렸다");
        assertEquals("app.hprof", after.getMatchedDumpFilename(), "연결된 덤프까지 끊었다 — 연결은 여전히 유효하다");
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, after.getStatus());
        assertNull(after.getSeverity(), "옛 요약이 남았다");
        assertNull(after.getEventCount());
        Mockito.verify(detailRepo).deleteByFilename("gc.log");
        Mockito.verify(insight).deleteAiInsight(Mockito.contains("gc.log"));
    }

    @Test
    @DisplayName("빈 목록은 구성 해제 — 분석도 시작하지 않는다")
    void linkWithEmptyListUngroupsWithoutAnalyzing() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");
        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");

        service.linkCombined("gc.log", List.of(), "tester", "manual");

        assertNull(store.get("gc.log").getMemberFiles());
        assertNull(store.get("gc.log").getMemberCount());
        assertNull(store.get("gc.log.0").getPartOf(), "구성원 역참조가 남았다");
        assertFalse(service.isAnalyzing("gc.log"));
    }

    @Test
    @DisplayName("묶기는 호스트네임을 요구하지 않는다 — 분석 초크포인트는 하나뿐이다(함정 66)")
    void linkDoesNotRequireHostname() throws Exception {
        put("gc.log.0", 0, 5, null, null);
        put("gc.log", 30, 5, null, null);

        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");
        assertEquals(2, store.get("gc.log").getMemberCount());

        // 분석은 여전히 막힌다
        assertThrows(GcLogAnalyzerService.MissingHostnameException.class, () -> service.submitAnalysis("gc.log", "tester"));
    }

    @Test
    @DisplayName("묶어 둔 뒤 분석하면 저장된 구성 그대로 이어 붙인다")
    void analyzeAfterLinkUsesStoredComposition() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");
        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "auto");

        GcLogAnalyzerService.JobProgress p = service.submitAnalysis("gc.log", "tester");
        p.future.get(30, TimeUnit.SECONDS);

        GcLogAnalysisEntity rep = store.get("gc.log");
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, rep.getStatus(), rep.getErrorMessage());
        assertEquals(2, rep.getMemberCount(), "분석이 구성을 지웠다");
        assertEquals(10, rep.getEventCount(), "두 조각의 이벤트가 모두 집계되지 않았다");
    }

    @Test
    @DisplayName("★ 자동 그룹화는 묶기만 한다 — 분석 제출 없음")
    void autoGroupLinksButNeverAnalyzes() throws Exception {
        for (int i = 0; i < 4; i++) put("gc.log." + i, i * 30L, 5, "was01", "c1");
        put("gc.log", 120, 5, "was01", "c1");

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(
                List.of("gc.log", "gc.log.0", "gc.log.1", "gc.log.2", "gc.log.3"), "tester", true);

        assertEquals(1, r.groups().size(), "연속 업로드가 한 묶음이 되지 않았다: " + r.singleReasons());
        assertTrue(r.groups().get(0).linked());
        assertEquals("gc.log", r.groups().get(0).group().representative());
        assertEquals(5, store.get("gc.log").getMemberCount());
        for (String n : List.of("gc.log.0", "gc.log.1", "gc.log.2", "gc.log.3")) {
            assertEquals("gc.log", store.get(n).getPartOf(), n + " 의 역참조가 없다");
        }
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, store.get("gc.log").getStatus(),
                "자동 그룹화가 분석을 시작했다 — '묶기만 자동' 계약이 깨졌다");
        assertFalse(service.isAnalyzing("gc.log"));
    }

    @Test
    @DisplayName("미리보기(apply=false)는 아무것도 쓰지 않는다")
    void previewDoesNotWrite() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(List.of("gc.log", "gc.log.0"), "tester", false);

        assertEquals(1, r.groups().size());
        assertFalse(r.groups().get(0).linked());
        assertNull(store.get("gc.log").getMemberCount(), "미리보기가 DB 를 바꿨다");
        assertNull(store.get("gc.log.0").getPartOf());
    }

    @Test
    @DisplayName("이미 분석했거나 다른 구성에 속한 파일은 자동 판정에서 뺀다")
    void autoGroupSkipsAnalyzedAndCombined() throws Exception {
        GcLogAnalysisEntity done = put("gc.log.0", 0, 5, "was01", "c1");
        done.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        GcLogAnalysisEntity member = put("gc.log.1", 30, 5, "was01", "c1");
        member.setPartOf("other.log");
        put("gc.log", 60, 5, "was01", "c1");

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(
                List.of("gc.log.0", "gc.log.1", "gc.log", "ghost.log"), "tester", true);

        assertTrue(r.groups().isEmpty(), "남은 파일이 하나뿐인데 묶었다");
        List<String> reasons = r.skipped().stream().map(m -> m.get("filename") + ":" + m.get("reason")).toList();
        assertTrue(reasons.contains("gc.log.0:already-analyzed"), reasons.toString());
        assertTrue(reasons.contains("gc.log.1:already-member"), reasons.toString());
        assertTrue(reasons.contains("ghost.log:not-registered"), reasons.toString());
    }

    @Test
    @DisplayName("킬 스위치를 끄면 판정도 묶기도 하지 않는다")
    void killSwitchLeavesEverythingSingle() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");
        Mockito.when(config.isGcLogGroupEnabled()).thenReturn(false);

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(List.of("gc.log", "gc.log.0"), "tester", true);

        assertTrue(r.groups().isEmpty());
        assertEquals(2, r.singles().size());
        assertNull(store.get("gc.log").getMemberCount());
    }

    @Test
    @DisplayName("같은 이름 재업로드는 part_of 까지 끊는다 — 새 내용이 옛 그룹에 매달리면 안 된다")
    void forgetAnalysisClearsPartOf() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");
        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");
        assertEquals("gc.log", store.get("gc.log.0").getPartOf());

        // 구성원을 지우고 같은 이름으로 다른 파일을 올린다
        Files.writeString(gcDir.resolve("gc.log.0"), logText(T0 + 5000 * MIN, 9), StandardCharsets.UTF_8);
        service.registerUploaded("gc.log.0", gcDir.resolve("gc.log.0").toFile().length(), "tester", "was09", null);

        assertNull(store.get("gc.log.0").getPartOf(),
                "새 파일이 옛 묶음에 매달린 채 남았다 — 엉뚱한 로그가 이어 붙여 분석된다");
    }

    @Test
    @DisplayName("구성 파일이 같은 이름의 다른 파일로 바뀌면 분석을 거부한다")
    void changedMemberIsRejectedByName() throws Exception {
        put("gc.log.0", 0, 5, "was01", "c1");
        put("gc.log", 30, 5, "was01", "c1");
        service.linkCombined("gc.log", List.of("gc.log.0"), "tester", "manual");

        // 구성에 적어 둔 크기와 달라지게 덮어쓴다(기록은 그대로)
        Files.writeString(gcDir.resolve("gc.log.0"), logText(T0, 40), StandardCharsets.UTF_8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submitAnalysis("gc.log", "tester"));
        assertTrue(ex.getMessage().contains("gc.log.0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("바뀌"), ex.getMessage());
    }
}

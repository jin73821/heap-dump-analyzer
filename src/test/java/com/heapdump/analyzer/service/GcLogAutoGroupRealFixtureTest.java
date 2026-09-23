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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 업로드 직후 자동 그룹화 — <b>이 서버 실측 회전본</b>으로 끝까지 돌린다 (2026-09-21).
 *
 * <p>합성 픽스처로는 못 잡는 것을 본다: 실제 꼬리 읽기(평문·gz), 실제 {@code gc,init} 헤더에서 뽑은 JVM 마커,
 * 그리고 <b>이름순이 시간순이 아닌</b> 실측 배열(함정 60). 순서가 어긋나면 이어 붙인 분석의 추세·간격이 전부 거짓이 된다.
 *
 * <p>실측 배열: {@code gc.log.0}(9/19 22:18) → {@code .3}(22:57) → {@code .4}(23:09) → {@code .1}(23:23)
 * → {@code .2.gz}(9/20 10:56) → {@code gc.log}(12:49). 사전식으로 이으면 하루가 뒤죽박죽이 된다.
 */
class GcLogAutoGroupRealFixtureTest {

    /** 이름순(사전식)이 아니라 시각순이 정답인 순서 — {@code GcLogRotatedRealFixtureTest} 와 같은 상수. */
    private static final List<String> CHRONOLOGICAL =
            List.of("gc.log.0", "gc.log.3", "gc.log.4", "gc.log.1", "gc.log.2.gz", "gc.log");
    private static final Path FIXTURES = Path.of("src/test/resources/gclog/rotated");

    @TempDir Path root;
    private Path gcDir;
    private GcLogAnalyzerService service;
    private HeapDumpConfig config;
    private final Map<String, GcLogAnalysisEntity> store = new LinkedHashMap<>();

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

        GcLogAnalysisRepository repo = Mockito.mock(GcLogAnalysisRepository.class);
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

        GcLogResultDetailRepository detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        Mockito.when(detailRepo.findByFilename(Mockito.anyString())).thenReturn(Optional.empty());
        service = new GcLogAnalyzerService(config, repo, detailRepo, Mockito.mock(AnalysisHistoryRepository.class),
                Mockito.mock(GcLogMatchService.class), Mockito.mock(LlmConfigService.class),
                Mockito.mock(AiInsightManager.class), Mockito.mock(DumpTransferLogRepository.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    /** 실측 회전본을 업로드본처럼 올려 둔다(호스트네임은 업로드 화면에서 받은 값). */
    private void stage(String server, String instance) throws IOException {
        for (String name : CHRONOLOGICAL) {
            Files.copy(FIXTURES.resolve(name), gcDir.resolve(name));
            GcLogAnalysisEntity e = new GcLogAnalysisEntity();
            e.setId((long) (store.size() + 1));
            e.setFilename(name);
            e.setFileSize(gcDir.resolve(name).toFile().length());
            e.setServerName(server);
            e.setInstanceName(instance);
            e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
            store.put(name, e);
        }
    }

    /** 사전식(= 사용자가 파일 선택 창에서 고르는 순서)으로 넘긴다 — 판정이 이름에 휘둘리면 안 된다. */
    private static List<String> byName() {
        List<String> byName = new ArrayList<>(CHRONOLOGICAL);
        byName.sort(String::compareTo);
        return byName;
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 실측 회전본 6개를 한 묶음으로 묶고 순서는 시각순이다 — 분석은 시작하지 않는다")
    void sixRotationsBecomeOneGroupInTimeOrder() throws Exception {
        stage("was01", "c1");

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(byName(), "tester", true);

        assertEquals(1, r.groups().size(), "한 묶음이 되지 않았다: " + r.singleReasons());
        GcLogAnalyzerService.GroupOutcome g = r.groups().get(0);
        assertTrue(g.linked(), g.error());
        assertEquals(CHRONOLOGICAL, g.group().members(),
                "이름순으로 이어 붙였다 — JDK 는 아카이브 슬롯을 돌려 쓴다(함정 60)");
        assertEquals("gc.log", g.group().representative(), "대표는 가장 최근 조각이어야 한다");
        assertEquals("absolute", g.group().orderBasis());

        GcLogAnalysisEntity rep = store.get("gc.log");
        assertEquals(6, rep.getMemberCount());
        assertEquals(GcLogAnalysisEntity.STATUS_NOT_ANALYZED, rep.getStatus(),
                "자동 묶기가 분석을 시작했다 — '묶기만 자동' 계약이 깨졌다");
        assertEquals(gcDir.resolve("gc.log").toFile().length(), rep.getFileSize(),
                "file_size 에 합계가 들어갔다(함정 63)");
        assertTrue(rep.getMembersBytes() > rep.getFileSize(), "합계가 members_bytes 에 없다");
        for (String n : CHRONOLOGICAL) {
            if (n.equals("gc.log")) continue;
            assertEquals("gc.log", store.get(n).getPartOf(), n + " 의 역참조가 없다");
        }
    }

    @Test
    @DisplayName("헤더의 JVM 마커를 실제로 읽는다 — G1 · 최대 힙")
    void markersComeFromRealHeaders() throws Exception {
        stage("was01", "c1");

        GcLogGroupPlanner.Probe p = service.probe(store.get("gc.log.0"));

        assertEquals("G1", p.collector(), "gc,init 헤더에서 수집기를 못 읽었다");
        assertNotNull(p.jdkVersion(), "JDK 버전을 못 읽었다");
        assertNotNull(p.heapMaxBytes(), "최대 힙을 못 읽었다");
        assertNotNull(p.firstTs(), "첫 절대 시각을 못 읽었다");
        assertNotNull(p.lastTsEpochMs(), "꼬리에서 끝 시각을 못 읽었다");
        assertEquals("content", p.endSource());
        assertTrue(p.lastTsEpochMs() >= p.firstTs(), "끝 시각이 시작보다 앞선다");
    }

    @Test
    @DisplayName("gz 조각도 꼬리를 읽는다 — 해제 상한 안에서")
    void gzMemberAlsoYieldsEndTime() throws Exception {
        stage("was01", "c1");

        GcLogGroupPlanner.Probe p = service.probe(store.get("gc.log.2.gz"));

        assertNotNull(p.lastTsEpochMs(), "gz 꼬리 시각을 못 읽었다 — 그 경계에서 묶음이 끊긴다");
        assertEquals("content", p.endSource());
    }

    @Test
    @DisplayName("호스트네임이 다르면 같은 회전 계열이어도 묶지 않는다")
    void differentHostKeepsThemApart() throws Exception {
        stage("was01", "c1");
        store.get("gc.log.1").setServerName("was02");

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(byName(), "tester", true);

        boolean split = r.groups().size() > 1 || !r.singles().isEmpty();
        assertTrue(split, "다른 서버의 로그를 한 묶음에 넣었다");
        assertTrue(r.groups().stream().noneMatch(g -> g.group().members().contains("gc.log.1")
                        && g.group().members().contains("gc.log.0")),
                "호스트가 다른 조각이 같은 묶음에 들어갔다");
    }

    @Test
    @DisplayName("호스트네임이 없는 옛 기록도 JVM 마커가 같으면 묶인다(수동 확인 여지는 남긴다)")
    void legacyRowsWithoutHostStillGroupOnMarkers() throws Exception {
        stage(null, null);

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(byName(), "tester", true);

        assertEquals(1, r.groups().size(), "마커가 같은데 묶지 않았다: " + r.singleReasons());
        assertEquals(GcLogGroupPlanner.Signal.UNKNOWN, r.groups().get(0).group().axes().origin(),
                "출처 근거가 없는데 MATCH 로 표시했다");
        assertEquals(GcLogGroupPlanner.Signal.MATCH, r.groups().get(0).group().axes().jvm());
    }

    @Test
    @DisplayName("묶은 뒤 분석하면 6조각이 한 결과로 이어진다")
    void analyzeAfterAutoGroupMergesAllSegments() throws Exception {
        stage("was01", "c1");
        service.autoGroup(byName(), "tester", true);

        GcLogAnalyzerService.JobProgress p = service.submitAnalysis("gc.log", "tester");
        p.future.get(60, java.util.concurrent.TimeUnit.SECONDS);

        GcLogAnalysisEntity rep = store.get("gc.log");
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, rep.getStatus(), rep.getErrorMessage());
        assertEquals(6, rep.getMemberCount(), "분석이 구성을 지웠다");
        assertNotNull(rep.getLogStart());
        assertNotNull(rep.getLogEnd());
        assertTrue(rep.getLogEnd().isAfter(rep.getLogStart().plusHours(10)),
                "이어 붙인 범위가 한 조각 수준이다 — 순서·병합이 어긋났다");
        // 매칭 시간창은 대표 조각 구간이어야 한다(함정 64)
        assertNotNull(rep.getMatchWindowStart());
        assertTrue(rep.getMatchWindowStart().isAfter(rep.getLogStart()),
                "매칭 시간창에 묶음 전체 범위가 들어갔다 — 자동 연결이 ambiguous 로 풀린다");
    }

    @Test
    @DisplayName("킬 스위치를 끄면 전부 단독으로 남는다")
    void killSwitchKeepsEverythingSingle() throws Exception {
        stage("was01", "c1");
        Mockito.when(config.isGcLogGroupEnabled()).thenReturn(false);

        GcLogAnalyzerService.AutoGroupResult r = service.autoGroup(byName(), "tester", true);

        assertTrue(r.groups().isEmpty());
        assertEquals(6, r.singles().size());
        assertNull(store.get("gc.log").getMemberCount());
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.parser.gclog.GcLogFormat;
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

import java.io.File;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 회전 GC 로그 이어 붙이기 — 서비스 계약 (2026-09-20).
 *
 * <p>가장 위험한 두 가지를 고정한다. ① {@code file_size} 는 <b>대표 파일 자신의 크기</b>여야 한다 — 합계를 넣으면
 * {@code listExistingFiles()} 가 "다른 파일"로 보고 {@code forgetAnalysis} 를 돌려 결과·AI·매칭이 조용히 사라진다(함정 63).
 * ② 구성 참조는 <b>양방향</b>으로 정리해야 한다 — 한쪽만 지우면 유령 링크가 남아 재분석이 없는 파일을 찾는다.
 */
class GcLogCombineServiceTest {

    @TempDir Path root;
    private Path gcDir;
    private GcLogAnalysisRepository repo;
    private GcLogResultDetailRepository detailRepo;
    private AiInsightManager insight;
    private GcLogAnalyzerService service;
    private final Map<String, GcLogAnalysisEntity> store = new LinkedHashMap<>();

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneId.systemDefault());
    private static final long T0 = Instant.parse("2026-09-19T13:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;

    @BeforeEach
    void setUp() throws Exception {
        gcDir = Files.createDirectories(root.resolve("gclogs/dumpfiles"));
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getGcLogDumpFilesDirectory()).thenReturn(gcDir.toString());
        Mockito.when(config.getGcLogMaxFileBytes()).thenReturn(2L * 1024 * 1024 * 1024);
        Mockito.when(config.getGcLogMaxLineChars()).thenReturn(4096);
        Mockito.when(config.getGcLogAnalysisTimeoutMinutes()).thenReturn(10);

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

    private static String header() {
        return "OpenJDK 64-Bit Server VM (25.422-b05) for linux-amd64 JRE (1.8.0_422-b05), built on Jul 11 2024\n"
                + "Memory: 4k page, physical 131479652k(65699592k free)\n"
                + "CommandLine flags: -XX:MaxHeapSize=2147483648 -XX:+UseParallelGC\n\n";
    }

    private static String logText(long startMs, double startUptime, int events) {
        StringBuilder sb = new StringBuilder(header());
        for (int i = 0; i < events; i++) {
            long ts = startMs + (long) (i * 60_000L);
            sb.append(ISO.format(Instant.ofEpochMilli(ts)))
              .append(String.format(Locale.ROOT, ": %.3f: ", startUptime + i * 60))
              .append("[GC (Allocation Failure) [PSYoungGen: 524800K->39141K(611840K)] 500000K->40000K(2010112K), "
                      + "0.0500000 secs] [Times: user=0.10 sys=0.01, real=0.05 secs]\n");
        }
        return sb.toString();
    }

    private GcLogAnalysisEntity put(String name, String text, String server) throws Exception {
        Files.writeString(gcDir.resolve(name), text, StandardCharsets.UTF_8);
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setId((long) (store.size() + 1));   // 저장된 행은 id 가 있다 — 등록 경로가 '새 행' 으로 오판하지 않게
        e.setFilename(name);
        e.setFileSize(gcDir.resolve(name).toFile().length());
        e.setServerName(server);
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setAnalysisDate(LocalDate.of(2026, 9, 19));
        store.put(name, e);
        return e;
    }

    private GcLogAnalysisEntity await(GcLogAnalyzerService.JobProgress p, String rep) throws Exception {
        p.future.get(30, TimeUnit.SECONDS);
        return store.get(rep);
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("이어 붙여도 file_size 는 대표 파일 자신의 크기 — 합계는 members_bytes 에 따로")
    void combineKeepsRepresentativeFileSize() throws Exception {
        put("gc.log.1", logText(T0, 3, 5), "was1");
        GcLogAnalysisEntity rep = put("gc.log", logText(T0 + HOUR, 3, 5), "was1");
        long ownSize = gcDir.resolve("gc.log").toFile().length();

        GcLogAnalysisEntity after = await(service.submitCombined("gc.log", List.of("gc.log.1"), "tester"), "gc.log");

        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, after.getStatus(), after.getErrorMessage());
        assertEquals(ownSize, after.getFileSize(),
                "file_size 에 합계가 들어갔다 — 다음 목록 조회가 '다른 파일'로 보고 결과를 지운다(함정 63)");
        assertEquals(2, after.getMemberCount());
        assertNotNull(after.getMembersBytes());
        assertTrue(after.getMembersBytes() > ownSize, "members_bytes 가 합계가 아니다");
        assertEquals("gc.log", store.get("gc.log.1").getPartOf(), "구성 파일에 역참조가 없다 — 목록에 미분석 행으로 남는다");
        assertNull(rep.getPartOf(), "대표에 역참조가 붙었다");
        // 이어 붙인 로그는 오래된 것이 먼저다(gc.log.1 이 1시간 앞선다)
        List<GcLogAnalyzerService.MemberRef> refs = service.parseMembers(after.getMemberFiles());
        assertEquals(List.of("gc.log.1", "gc.log"), refs.stream().map(GcLogAnalyzerService.MemberRef::filename).toList());
    }

    @Test
    @DisplayName("구성 해제는 대표 표시와 구성 파일 역참조를 함께 푼다")
    void ungroupClearsBothSides() throws Exception {
        put("gc.log.1", logText(T0, 3, 5), "was1");
        put("gc.log", logText(T0 + HOUR, 3, 5), "was1");
        await(service.submitCombined("gc.log", List.of("gc.log.1"), "tester"), "gc.log");

        GcLogAnalysisEntity after = await(service.submitCombined("gc.log", List.of(), "tester"), "gc.log");

        assertNull(after.getMemberFiles());
        assertNull(after.getMemberCount());
        assertNull(store.get("gc.log.1").getPartOf(), "구성 파일에 유령 역참조가 남았다");
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, after.getStatus());
    }

    @Test
    @DisplayName("같은 이름으로 새 파일이 들어오면 구성도 함께 잊는다 — 옛 구성으로 엉뚱한 파일을 합치지 않게")
    void forgetAnalysisClearsCombination() throws Exception {
        put("gc.log.1", logText(T0, 3, 5), "was1");
        put("gc.log", logText(T0 + HOUR, 3, 5), "was1");
        await(service.submitCombined("gc.log", List.of("gc.log.1"), "tester"), "gc.log");

        service.registerUploaded("gc.log", 123L, "someone", "was9", "inst9");

        GcLogAnalysisEntity after = store.get("gc.log");
        assertNull(after.getMemberFiles(), "옛 구성이 남았다 — 재분석이 엉뚱한 파일을 합친다");
        assertNull(after.getMemberCount());
        assertNull(store.get("gc.log.1").getPartOf(), "구성 파일의 역참조가 남았다");
    }

    @Test
    @DisplayName("구성 파일이 사라지면 이름을 밝히고 분석을 거부한다")
    void missingMemberFailsWithNamedKoreanMessage() throws Exception {
        put("gc.log.1", logText(T0, 3, 5), "was1");
        put("gc.log", logText(T0 + HOUR, 3, 5), "was1");
        await(service.submitCombined("gc.log", List.of("gc.log.1"), "tester"), "gc.log");

        Files.delete(gcDir.resolve("gc.log.1"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.submitAnalysis("gc.log", "tester"));
        assertTrue(e.getMessage().contains("gc.log.1"), "어느 파일이 없는지 밝히지 않는다: " + e.getMessage());
    }

    @Test
    @DisplayName("한도·형식·중복을 검사한다")
    void planRejectsBadCombinations() throws Exception {
        put("gc.log", logText(T0, 3, 3), "was1");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < GcLogAnalyzerService.MAX_MEMBERS + 1; i++) {
            String n = "gc.log." + i;
            put(n, logText(T0 + i * HOUR, 3, 2), "was1");
            many.add(n);
        }
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                () -> service.planCombine("gc.log", many));
        assertTrue(tooMany.getMessage().contains(String.valueOf(GcLogAnalyzerService.MAX_MEMBERS)));

        // 같은 파일을 두 번 골라도 한 번만 들어간다
        GcLogAnalyzerService.CombinePlan plan = service.planCombine("gc.log", List.of("gc.log.0", "gc.log.0"));
        assertEquals(2, plan.ordered().size());

        Files.writeString(gcDir.resolve("other.log"), "hello world\nnot a gc log\n");
        assertThrows(IllegalArgumentException.class, () -> service.planCombine("gc.log", List.of("other.log")));
    }

    @Test
    @DisplayName("순서는 회전 번호가 아니라 내용의 첫 시각으로 정한다 (이 서버 실측 배열)")
    void ordersByFirstTimestampNotRotationNumber() {
        // 실측: gc.log.2(22:07) → .0(22:18) → .3(22:57) → .4(23:09) → .1(23:23) → gc.log(현재)
        long base = T0;
        List<GcLogAnalyzerService.Peek> peeks = List.of(
                new GcLogAnalyzerService.Peek("gc.log.0", GcLogFormat.UNIFIED, base + 11 * 60_000, 0.003, 0, 10),
                new GcLogAnalyzerService.Peek("gc.log.1", GcLogFormat.UNIFIED, base + 83 * 60_000, 0.004, 0, 10),
                new GcLogAnalyzerService.Peek("gc.log.2", GcLogFormat.UNIFIED, base, 0.003, 0, 10),
                new GcLogAnalyzerService.Peek("gc.log.3", GcLogFormat.UNIFIED, base + 50 * 60_000, 0.003, 0, 10),
                new GcLogAnalyzerService.Peek("gc.log.4", GcLogFormat.UNIFIED, base + 62 * 60_000, 0.004, 0, 10),
                new GcLogAnalyzerService.Peek("gc.log", GcLogFormat.UNIFIED, base + 100 * 60_000, 0.004, 0, 10));

        List<String> order = GcLogAnalyzerService.orderMembers(peeks, new ArrayList<>()).stream()
                .map(GcLogAnalyzerService.Peek::filename).toList();

        assertEquals(List.of("gc.log.2", "gc.log.0", "gc.log.3", "gc.log.4", "gc.log.1", "gc.log"), order,
                "이름·회전 번호 순으로 이어 붙였다 — JDK 는 아카이브 슬롯을 돌려 쓴다(함정 60)");
    }

    @Test
    @DisplayName("절대 시각이 없으면 파일 시각 → 첫 uptime 순으로 잇고 그 사실을 경고한다")
    void fallsBackToMtimeThenUptime() {
        List<GcLogAnalyzerService.Peek> peeks = List.of(
                new GcLogAnalyzerService.Peek("b.log", GcLogFormat.JDK8, null, 700.0, 200, 10),
                new GcLogAnalyzerService.Peek("a.log", GcLogFormat.JDK8, null, 3.0, 100, 10),
                new GcLogAnalyzerService.Peek("c.log", GcLogFormat.JDK8, null, 1.0, 200, 10));
        List<String> warn = new ArrayList<>();

        List<String> order = GcLogAnalyzerService.orderMembers(peeks, warn).stream()
                .map(GcLogAnalyzerService.Peek::filename).toList();

        assertEquals(List.of("a.log", "c.log", "b.log"), order);
        assertTrue(warn.stream().anyMatch(w -> w.contains("절대 시각")), "추측으로 이었다는 사실을 알리지 않는다");
    }

    @Test
    @DisplayName("분석명은 호스트_인스턴스_일자이고, 로그 시작일로 확정된다")
    void analysisNameFollowsHostInstanceLogDate() throws Exception {
        GcLogAnalysisEntity e = put("gc.log", logText(T0, 3, 5), "was1");
        e.setInstanceName("container1");

        GcLogAnalysisEntity after = await(service.submitAnalysis("gc.log", "tester"), "gc.log");

        LocalDate logDay = Instant.ofEpochMilli(T0).atZone(ZoneId.systemDefault()).toLocalDate();
        assertEquals("was1_container1_" + logDay.format(DateTimeFormatter.ofPattern("yyyyMMdd")), after.getAnalysisName());
        assertEquals(logDay, after.getAnalysisDate(), "일자가 로그 시작일이 아니다");
    }

    @Test
    @DisplayName("호스트네임이 없으면 분석을 시작하지 않는다 — 프런트만 막으면 새는 경로가 생긴다")
    void hostnameIsRequiredToAnalyze() throws Exception {
        put("gc.log", logText(T0, 3, 5), null);

        GcLogAnalyzerService.MissingHostnameException e = assertThrows(
                GcLogAnalyzerService.MissingHostnameException.class, () -> service.submitAnalysis("gc.log", "tester"));
        assertTrue(e.getMessage().contains("호스트네임"));

        store.get("gc.log").setServerName("was1");
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS,
                await(service.submitAnalysis("gc.log", "tester"), "gc.log").getStatus());
    }

    @Test
    @DisplayName("전송본은 호스트네임과 인스턴스명이 자동으로 채워진다 (WasInstanceName 규칙 재사용)")
    void transferredLogFillsHostAndInstance() {
        String jvmInfo = "{\"schema\":2,\"capturedAtEpoch\":1,\"memTotal\":1,\"memAvailable\":1,\"procVisible\":1,"
                + "\"truncated\":false,\"dumpMtimeEpoch\":1758240000,\"matchedIndex\":0,\"candidates\":[{"
                + "\"pid\":1,\"user\":\"was\",\"markers\":{\"jeus.server.name\":\"spserver1\"}}]}";
        DumpTransferLog log = new DumpTransferLog();
        log.setFilename("gc.log");
        log.setFileSize(100L);
        log.setRemotePath("/logs/gc.log");
        log.setJvmInfo(jvmInfo);
        TargetServer server = new TargetServer();
        server.setId(7L);
        server.setName("spdomain1");

        GcLogAnalysisEntity e = service.registerTransferred(log, server);

        assertEquals("spdomain1", e.getServerName(), "전송본인데 호스트네임이 비었다");
        assertEquals("spserver1", e.getInstanceName(), "JVM 마커에서 인스턴스명을 읽지 못했다");
        assertNotNull(e.getAnalysisName());
        assertTrue(e.getAnalysisName().startsWith("spdomain1_spserver1_"), e.getAnalysisName());
    }

    @Test
    @DisplayName("이어 붙인 분석의 매칭 시간창은 대표 파일 자신의 구간이다 — 넓은 범위가 자동 매칭을 지우지 않게")
    void matchWindowStaysOnRepresentativeSegment() throws Exception {
        put("gc.log.1", logText(T0, 3, 5), "was1");
        put("gc.log", logText(T0 + 5 * HOUR, 3, 5), "was1");

        GcLogAnalysisEntity after = await(service.submitCombined("gc.log", List.of("gc.log.1"), "tester"), "gc.log");

        assertNotNull(after.getMatchWindowStart());
        assertNotNull(after.getLogStart());
        assertTrue(after.getMatchWindowStart().isAfter(after.getLogStart()),
                "매칭 시간창이 전체 범위로 넓어졌다 — 그 안의 덤프가 전부 후보가 되어 자동 연결이 ambiguous 로 풀린다(함정 64)");
        File own = gcDir.resolve("gc.log").toFile();
        assertTrue(own.isFile());
    }

    @Test
    @DisplayName("이어붙이기 모달의 현재 로그 행은 대표 자신의 이름·크기·구간이다 — 후보 목록에는 없다")
    void combineCurrentShowsRepresentativeOwnSegment() throws Exception {
        put("gc.log.1", logText(T0, 3, 5), "was1");
        put("gc.log", logText(T0 + 5 * HOUR, 3, 5), "was1");
        long ownSize = gcDir.resolve("gc.log").toFile().length();

        GcLogAnalysisEntity after = await(service.submitCombined("gc.log", List.of("gc.log.1"), "tester"), "gc.log");
        Map<String, Object> cur = service.combineCurrent(after);

        assertEquals("gc.log", cur.get("filename"));
        assertEquals(ownSize, cur.get("size"), "현재 로그 행에 합계 크기가 보인다");
        assertEquals(after.getMatchWindowStart().toString(), cur.get("logStart"),
                "현재 로그 행의 시작이 묶음 전체 범위다 — 대표 파일 자신의 구간이어야 한다");
        assertTrue(service.combineOptions(after, null, 200).stream().noneMatch(o -> "gc.log".equals(o.get("filename"))),
                "대표가 후보 목록에도 나온다 — 체크를 풀 수 있는 것처럼 보인다");
    }
}

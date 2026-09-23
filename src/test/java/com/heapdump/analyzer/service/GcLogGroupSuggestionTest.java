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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 분석 시작 전 연속 로그 제안 — {@link GcLogAnalyzerService#suggestGroup} (2026-09-23).
 *
 * <p>계기: Target Servers 에서 aibank01d 의 회전본 6개를 전송했는데 각각 단독 분석만 됐다. 원격 전송은 파일마다 따로
 * 도착해 업로드 큐의 자동 묶기(autoGroup)가 돌 자리가 없다. 그래서 <b>아무 파일에서나</b> '분석 시작'을 누르면
 * 그 파일이 속한 연속 회전본 전체가 제안돼야 한다.
 *
 * <p>픽스처 {@code gclog/rotated-transfer/*.gz} 는 그때 받은 실제 6개 파일이다(이 앱 자신의 G1 로그, 경계 공백 ~1초).
 * 로컬 이름은 전송 회피명({@code gc.log_202609231516.N})이라 파일명만으로는 계열을 알 수 없고 원격 경로가 근거다.
 * 시간순은 {@code .3 → .4 → .1 → .2 → .0 → gc.log} — 회전 번호와 무관하다(함정 60).
 */
class GcLogGroupSuggestionTest {

    private static final Path FIXTURES = Path.of("src/test/resources/gclog/rotated-transfer");
    private static final String SERVER = "aibank01d";
    /** 원격 이름 → 전송 시 로컬 회피명(운영 실측 그대로). */
    private static final Map<String, String> LOCAL = new LinkedHashMap<>();
    static {
        for (int i = 0; i <= 4; i++) LOCAL.put("gc.log." + i, "gc.log_202609231516." + i);
        LOCAL.put("gc.log", "gc_202609231516.log");
    }
    /** 로그 내용의 첫 시각 순서(정답). */
    private static final List<String> CHRONO = List.of(
            "gc.log_202609231516.3", "gc.log_202609231516.4", "gc.log_202609231516.1",
            "gc.log_202609231516.2", "gc.log_202609231516.0", "gc_202609231516.log");
    private static final String REP = "gc_202609231516.log";

    @TempDir Path root;
    private Path gcDir;
    private GcLogAnalysisRepository repo;
    private HeapDumpConfig config;
    private GcLogAnalyzerService service;
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

        GcLogResultDetailRepository detailRepo = Mockito.mock(GcLogResultDetailRepository.class);
        Mockito.when(detailRepo.findByFilename(Mockito.anyString())).thenReturn(Optional.empty());
        service = new GcLogAnalyzerService(config, repo, detailRepo, Mockito.mock(AnalysisHistoryRepository.class),
                Mockito.mock(GcLogMatchService.class), Mockito.mock(LlmConfigService.class),
                Mockito.mock(AiInsightManager.class), Mockito.mock(DumpTransferLogRepository.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    // ── 픽스처 ──────────────────────────────────────────────────

    /** 실측 6개를 원격 전송본처럼 둔다 — 평문으로 풀고(전송본은 평문이다) 원격 경로·서버명을 채운다. */
    private void stageTransferred() throws IOException {
        for (Map.Entry<String, String> en : LOCAL.entrySet()) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(FIXTURES.resolve(en.getKey() + ".gz")))) {
                Files.copy(in, gcDir.resolve(en.getValue()));
            }
            register(en.getValue(), "/tmp/" + en.getKey(), SERVER, null);
        }
    }

    private GcLogAnalysisEntity register(String local, String remotePath, String server, String instance) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setId((long) (store.size() + 1));
        e.setFilename(local);
        e.setFileSize(gcDir.resolve(local).toFile().length());
        e.setRemotePath(remotePath);
        e.setServerName(server);
        e.setInstanceName(instance);
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        store.put(local, e);
        return e;
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneId.systemDefault());
    private static final long T0 = Instant.parse("2026-09-19T01:00:00Z").toEpochMilli();
    private static final long MIN = 60_000L;

    /** 합성 JDK 8 Parallel 로그 — 헤더에 수집기·최대 힙 마커. {@code startMinute} 부터 1분 간격 이벤트. */
    private GcLogAnalysisEntity synth(String local, String remotePath, String server, long startMinute, int events)
            throws IOException {
        StringBuilder sb = new StringBuilder()
                .append("OpenJDK 64-Bit Server VM (25.422-b05) for linux-amd64 JRE (1.8.0_422-b05), built on Jul 11 2024\n")
                .append("Memory: 4k page, physical 131479652k(65699592k free)\n")
                .append("CommandLine flags: -XX:MaxHeapSize=2147483648 -XX:+UseParallelGC\n\n");
        for (int i = 0; i < events; i++) {
            sb.append(ISO.format(Instant.ofEpochMilli(T0 + (startMinute + i) * MIN)))
              .append(String.format(Locale.ROOT, ": %.3f: ", 3.0 + i * 60))
              .append("[GC (Allocation Failure) [PSYoungGen: 524800K->39141K(611840K)] 500000K->40000K(2010112K), "
                      + "0.0500000 secs] [Times: user=0.10 sys=0.01, real=0.05 secs]\n");
        }
        Files.writeString(gcDir.resolve(local), sb.toString(), StandardCharsets.UTF_8);
        return register(local, remotePath, server, null);
    }

    private static List<String> names(GcLogAnalyzerService.GroupSuggestion s) {
        return s.members().stream().map(GcLogAnalyzerService.SuggestedMember::filename).toList();
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ 전송받은 회전본 6개 — 어느 파일에서 열어도 같은 6개가 시간순으로 제안되고, 아무것도 쓰지 않는다")
    void anyTransferredFileSuggestsWholeChain() throws Exception {
        stageTransferred();

        for (String clicked : LOCAL.values()) {
            GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup(clicked);
            assertEquals(GcLogAnalyzerService.SUGGEST_GROUP, s.mode(), clicked + " 에서 묶음을 찾지 못했다: " + s.reason());
            assertEquals(CHRONO, names(s), clicked + " — 회전 번호순으로 이었다(함정 60)");
            assertEquals(REP, s.representative(), "대표는 가장 최근 조각 — 업로드 자동 묶기와 같은 규칙");
            assertEquals(clicked, s.filename());
            assertEquals(6, s.candidates());
            assertEquals(GcLogGroupPlanner.Signal.MATCH, s.axes().time());
            assertEquals(GcLogGroupPlanner.Signal.MATCH, s.axes().origin(), "같은 서버가 근거로 잡히지 않았다");
            assertEquals("/tmp/gc.log", s.series(), "계열은 로컬 회피명이 아니라 원격 경로에서 나온다");
            assertEquals(SERVER, s.serverName());
        }
        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup(CHRONO.get(0));
        assertEquals("2026-09-20 12:59:43", s.members().get(0).start(), "첫 조각의 시작 시각(서버 시간대 문자열)");
        assertEquals("2026-09-23 15:12:22", s.members().get(5).end(), "마지막 조각의 끝 시각");

        // 제안은 읽기 전용이다 — 묶기는 사용자가 수락했을 때 /combine 이 한다
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
        for (GcLogAnalysisEntity e : store.values()) {
            assertNull(e.getPartOf(), "제안이 역참조를 걸었다");
            assertNull(e.getMemberFiles(), "제안이 구성을 저장했다");
        }
    }

    @Test
    @DisplayName("★ 이미 단독 분석된 회전본도 사슬에 남고, 끝 시각은 마지막 GC 이벤트가 아니라 파일 끝이다")
    void analyzedMemberStaysAndUsesFileEnd() throws Exception {
        stageTransferred();
        // 운영 실측: .4 를 먼저 단독 분석했다. log_end 는 마지막 GC 이벤트(13:18:02) — 파일은 13:22:36(gc,heap,exit)에 끝난다
        GcLogAnalysisEntity four = store.get("gc.log_202609231516.4");
        four.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        four.setLogStart(LocalDateTime.of(2026, 9, 20, 13, 16, 35));
        four.setLogEnd(LocalDateTime.of(2026, 9, 20, 13, 18, 2, 516_000_000));

        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup("gc.log_202609231516.2");

        assertEquals(CHRONO, names(s), "분석된 파일을 빼면 사슬 가운데가 빈다");
        GcLogAnalyzerService.SuggestedMember m = s.members().get(1);
        assertEquals("gc.log_202609231516.4", m.filename());
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, m.status());
        assertEquals("2026-09-20 13:22:36", m.end(), "log_end(마지막 이벤트)를 파일 끝으로 썼다");
        assertTrue(s.warnings().stream().noneMatch(w -> w.contains("공백")),
                "경계 공백은 ~1초인데 없는 공백을 알린다: " + s.warnings());
    }

    @Test
    @DisplayName("이어 붙인 대표의 log_end 는 묶음 전체의 끝 — 그 파일 자신의 끝으로 쓰지 않는다")
    void combinedRepresentativeEndIsItsOwnFile() throws Exception {
        stageTransferred();
        // 결과 화면의 '로그 이어붙이기' 는 연 파일이 대표다 — 시간상 첫 조각(.3)이 대표인 구성
        String rep = "gc.log_202609231516.3";
        service.linkCombined(rep, CHRONO, "tester", "manual");
        GcLogAnalysisEntity e = store.get(rep);
        e.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        e.setLogStart(LocalDateTime.of(2026, 9, 20, 12, 59, 43));
        e.setLogEnd(LocalDateTime.of(2026, 9, 23, 15, 12, 22));   // 묶음 전체의 끝

        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup(rep);

        assertEquals(GcLogAnalyzerService.SUGGEST_COMBINED, s.mode());
        GcLogAnalyzerService.SuggestedMember own = s.members().get(0);
        assertEquals(rep, own.filename());
        assertEquals("2026-09-20 13:16:34", own.end(), "대표 파일의 끝에 묶음 전체의 끝(09-23)을 썼다");
    }

    @Test
    @DisplayName("구성 파일에서 열면 member(대표 안내), 대표에서 열면 combined — 저장된 구성 순서 그대로")
    void existingCompositionIsReportedNotRecomputed() throws Exception {
        stageTransferred();
        service.linkCombined(REP, CHRONO, "tester", "manual");
        store.get(REP).setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);

        GcLogAnalyzerService.GroupSuggestion member = service.suggestGroup("gc.log_202609231516.1");
        assertEquals(GcLogAnalyzerService.SUGGEST_MEMBER, member.mode());
        assertEquals(REP, member.representative());
        assertEquals(CHRONO, names(member));
        assertEquals(GcLogAnalysisEntity.STATUS_SUCCESS, member.members().get(5).status(), "대표 상태가 있어야 '묶음 결과 보기' 로 간다");

        GcLogAnalyzerService.GroupSuggestion combined = service.suggestGroup(REP);
        assertEquals(GcLogAnalyzerService.SUGGEST_COMBINED, combined.mode());
        assertEquals(CHRONO, names(combined));
    }

    @Test
    @DisplayName("낡은 part_of(대표 구성에 없음)는 무시하고 새로 판정한다 — 다른 파일에서 볼 때는 제외 대상이다")
    void stalePartOfIsIgnored() throws Exception {
        stageTransferred();
        store.get("gc.log_202609231516.0").setPartOf("gone.log");

        GcLogAnalyzerService.GroupSuggestion self = service.suggestGroup("gc.log_202609231516.0");
        assertEquals(GcLogAnalyzerService.SUGGEST_GROUP, self.mode(), "사라진 대표를 가리키는 파일이 제안을 받지 못했다");
        assertEquals(CHRONO, names(self));

        // 다른 파일에서 열면 .0 은 '다른 묶음의 구성 파일' 로 보여 빠진다 — 그 사실은 제외 수로 알린다
        GcLogAnalyzerService.GroupSuggestion other = service.suggestGroup("gc.log_202609231516.3");
        assertEquals(1, other.excludedGrouped());
        assertFalse(names(other).contains("gc.log_202609231516.0"));
    }

    @Test
    @DisplayName("다른 서버·다른 경로·다른 묶음의 로그는 후보가 아니다 — 제외 수는 알린다")
    void candidatesAreSameServerSameSeriesUngrouped() throws Exception {
        synth("gc.log.1", "/logs/gc.log.1", "was01", 0, 10);
        synth("gc.log", "/logs/gc.log", "was01", 10, 10);
        // 같은 경로·같은 시각이지만 다른 서버
        synth("gc.log_202609230000.1", "/logs/gc.log.1", "was02", 20, 10);
        // 같은 서버지만 다른 경로(인스턴스별 디렉토리)
        synth("gc_was2.log", "/logs/was2/gc.log", "was01", 20, 10);
        // 같은 서버·같은 경로지만 이미 다른 묶음의 구성 파일
        synth("gc.log_202609230000.2", "/logs/gc.log.2", "was01", -20, 10).setPartOf("elsewhere.log");

        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup("gc.log.1");

        assertEquals(GcLogAnalyzerService.SUGGEST_GROUP, s.mode(), s.reason());
        assertEquals(List.of("gc.log.1", "gc.log"), names(s));
        assertEquals(2, s.candidates());
        assertEquals(1, s.excludedGrouped());
        assertEquals("Parallel", s.collector(), "JVM 마커(수집기)가 실리지 않았다");
        assertEquals(2L * 1024 * 1024 * 1024, s.heapMaxBytes());
    }

    @Test
    @DisplayName("같은 계열이어도 시간이 겹치면(다른 JVM) 제안하지 않고 이유를 준다")
    void overlappingLogsAreNotSuggested() throws Exception {
        // %p 파일명 — 두 인스턴스가 같은 디렉토리에 pid 별로 쓴다. 계열 키는 같지만 동시에 기록됐다
        synth("gc-12345.log", "/logs/gc-12345.log", "was01", 0, 30);
        synth("gc-67890.log", "/logs/gc-67890.log", "was01", 5, 30);

        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup("gc-12345.log");

        assertEquals(GcLogAnalyzerService.SUGGEST_SINGLE, s.mode());
        assertEquals(2, s.candidates(), "같은 계열 후보로는 잡혀야 한다(그래야 '왜 안 묶였나' 를 말할 수 있다)");
        assertTrue(s.reason() != null && s.reason().contains("gc-67890.log") && s.reason().contains("겹칩니다"),
                "이웃 로그와 겹친다는 이유가 없다: " + s.reason());
    }

    @Test
    @DisplayName("같은 계열 로그가 없으면 single · 후보 1 · 이유 없음(화면은 블록을 숨긴다)")
    void aloneWhenNothingElse() throws Exception {
        synth("gc.log", "/logs/gc.log", "was01", 0, 5);

        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup("gc.log");

        assertEquals(GcLogAnalyzerService.SUGGEST_SINGLE, s.mode());
        assertEquals(1, s.candidates());
        assertNull(s.reason());
        assertTrue(s.members().isEmpty());
    }

    @Test
    @DisplayName("자동 묶기가 꺼져 있으면 제안도 하지 않는다(gclog.group.enabled)")
    void disabledByConfig() throws Exception {
        stageTransferred();
        Mockito.when(config.isGcLogGroupEnabled()).thenReturn(false);

        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup(REP);

        assertEquals(GcLogAnalyzerService.SUGGEST_SINGLE, s.mode());
        assertTrue(s.reason().contains("gclog.group.enabled"));
    }

    @Test
    @DisplayName("계열 키 — 회전 접미사·%t·긴 숫자만 가리고 디렉토리·짧은 숫자(인스턴스 번호)는 가리지 않는다")
    void seriesKeyRules() {
        assertEquals("/tmp/gc.log", GcLogAnalyzerService.seriesKey("/tmp/gc.log.3", "gc.log_202609231516.3"));
        assertEquals("/tmp/gc.log", GcLogAnalyzerService.seriesKey("/tmp/gc.log", "gc_202609231516.log"));
        assertEquals("/logs/gc.log", GcLogAnalyzerService.seriesKey("/logs/gc.log.0.current", "x"));
        assertEquals(GcLogAnalyzerService.seriesKey("/logs/gc-2026-09-14_00-52-33.log", "a"),
                     GcLogAnalyzerService.seriesKey("/logs/gc-2026-09-15_08-01-02.log", "b"), "%t 재기동 파일은 같은 계열");
        assertFalse(GcLogAnalyzerService.seriesKey("/logs/was1/gc.log", "a")
                .equals(GcLogAnalyzerService.seriesKey("/logs/was2/gc.log", "b")), "인스턴스별 디렉토리가 한 계열이 됐다");
        assertFalse(GcLogAnalyzerService.seriesKey(null, "gc_container1.log")
                .equals(GcLogAnalyzerService.seriesKey(null, "gc_container2.log")), "인스턴스 번호를 가렸다");
        assertEquals("gc.log", GcLogAnalyzerService.seriesKey(" ", "gc.log.4"), "원격 경로가 없으면 파일명");
    }

    @Test
    @DisplayName("스캔 '분석 완료' — 자기 SUCCESS 는 자기 이름, 분석된 묶음의 구성 파일은 대표 이름, 아니면 null")
    void scanAnalyzedViaGroupRepresentative() throws Exception {
        stageTransferred();
        String member = "gc.log_202609231516.1";
        assertNull(RemoteDumpService.gcLogAnalyzedVia(repo, member), "미분석인데 분석 완료로 봤다");

        service.linkCombined(REP, CHRONO, "tester", "manual");
        assertNull(RemoteDumpService.gcLogAnalyzedVia(repo, member), "묶기만 했는데(대표 미분석) 분석 완료로 봤다");

        store.get(REP).setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
        assertEquals(REP, RemoteDumpService.gcLogAnalyzedVia(repo, member),
                "묶음이 분석됐는데 구성 파일에 '분석 시작' 이 계속 붙는다");
        assertEquals(REP, RemoteDumpService.gcLogAnalyzedVia(repo, REP));
        assertNull(RemoteDumpService.gcLogAnalyzedVia(repo, "unknown.log"));
        assertNull(RemoteDumpService.gcLogAnalyzedVia(null, member));
    }
}

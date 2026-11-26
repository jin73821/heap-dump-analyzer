package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.model.entity.ThreadDumpResultDetailEntity;
import com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor;
import com.heapdump.analyzer.repository.ThreadDumpAnalysisRepository;
import com.heapdump.analyzer.repository.ThreadDumpResultDetailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2.7.1 — 이어 붙이기(묶기·풀기·삭제 시 정리·업로드 직후 제안)와 힙 덤프 수동 연결(후보·연결·이음).
 * 리포지토리만 메모리 맵으로 흉내 내고 실제 실행기로 분석한다.
 */
class ThreadDumpCombineLinkServiceTest {

    @TempDir Path root;

    private HeapDumpConfig config;
    private ThreadDumpAnalysisRepository repo;
    private ThreadDumpAnalyzerService service;
    private final Map<String, ThreadDumpAnalysisEntity> rows = new LinkedHashMap<>();
    private final Map<String, ThreadDumpResultDetailEntity> details = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("dumpfiles"));
        config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getThreadDumpDumpFilesDirectory()).thenReturn(root.resolve("dumpfiles").toString());
        Mockito.when(config.getThreadDumpTmpDirectory()).thenReturn(root.resolve("tmp").toString());
        Mockito.when(config.getThreadDumpMaxFileBytes()).thenReturn(8L * 1024 * 1024);
        Mockito.when(config.getThreadDumpMaxLineChars()).thenReturn(4096);
        Mockito.when(config.getThreadDumpMaxThreadsPerSnapshot()).thenReturn(20000);
        Mockito.when(config.getThreadDumpMaxSnapshots()).thenReturn(30);
        Mockito.when(config.getThreadDumpAnalysisTimeoutMinutes()).thenReturn(10);
        Mockito.when(config.getThreadDumpMatchToleranceMin()).thenReturn(10);

        repo = Mockito.mock(ThreadDumpAnalysisRepository.class);
        Mockito.when(repo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0))));
        Mockito.when(repo.save(Mockito.any(ThreadDumpAnalysisEntity.class))).thenAnswer(inv -> {
            ThreadDumpAnalysisEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId((long) (rows.size() + 1));
            synchronized (rows) { rows.put(e.getFilename(), e); }
            return e;
        });
        Mockito.when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(inv -> new ArrayList<>(rows.values()));
        Mockito.when(repo.findByPartOf(Mockito.anyString())).thenAnswer(inv ->
                rows.values().stream().filter(e -> inv.getArgument(0).equals(e.getPartOf())).toList());
        Mockito.when(repo.findByLinkedHeapFilename(Mockito.anyString())).thenAnswer(inv ->
                rows.values().stream().filter(e -> inv.getArgument(0).equals(e.getLinkedHeapFilename())).toList());
        Mockito.doAnswer(inv -> { rows.remove(inv.<ThreadDumpAnalysisEntity>getArgument(0).getFilename()); return null; })
                .when(repo).delete(Mockito.any(ThreadDumpAnalysisEntity.class));

        ThreadDumpResultDetailRepository detailRepo = Mockito.mock(ThreadDumpResultDetailRepository.class);
        Mockito.when(detailRepo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(details.get(inv.<String>getArgument(0))));
        Mockito.when(detailRepo.save(Mockito.any(ThreadDumpResultDetailEntity.class))).thenAnswer(inv -> {
            ThreadDumpResultDetailEntity d = inv.getArgument(0);
            details.put(d.getFilename(), d);
            return d;
        });
        Mockito.doAnswer(inv -> { details.remove(inv.<String>getArgument(0)); return null; }).when(detailRepo).deleteByFilename(Mockito.anyString());

        service = new ThreadDumpAnalyzerService(config, repo, detailRepo, Mockito.mock(LlmConfigService.class), Mockito.mock(AiInsightManager.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    private static String fixture(String name) throws IOException {
        try (InputStream in = ThreadDumpCombineLinkServiceTest.class.getResourceAsStream("/threaddump/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 실측 JDK 21 3회 덤프를 스냅샷마다 파일 하나로 나눠 저장소에 놓는다(이름은 시각과 반대 순서). */
    private List<String> placeParts() throws IOException {
        String all = fixture("jdk21-3snap.txt");
        List<String> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int next = all.indexOf("\nFull thread dump", all.indexOf("Full thread dump", from) + 10);
            if (next < 0) { parts.add(all.substring(from)); break; }
            int cut = all.lastIndexOf('\n', next - 1) + 1;
            parts.add(all.substring(from, cut));
            from = cut;
        }
        List<String> names = List.of("c-part.txt", "b-part.txt", "a-part.txt");   // c = 가장 이른 시각
        for (int i = 0; i < 3; i++) Files.writeString(root.resolve("dumpfiles").resolve(names.get(i)), parts.get(i));
        return names;
    }

    private void await(String name) throws Exception {
        for (int i = 0; i < 400 && (service.isAnalyzing(name) || (rows.get(name) != null && ThreadDumpAnalysisEntity.STATUS_ANALYZING.equals(rows.get(name).getStatus()))); i++) {
            Thread.sleep(25);
        }
    }

    // ── 이어 붙이기 ──────────────────────────────────────────────

    @Test
    void 미리보기는_시각순으로_정렬하고_대표를_정한다() throws Exception {
        placeParts();
        Map<String, Object> p = service.combinePreview(List.of("a-part.txt", "b-part.txt", "c-part.txt"));
        assertEquals("timestamp", p.get("order"));
        assertEquals("c-part.txt", p.get("representative"));
        assertEquals(3, p.get("snapshots"));
        assertEquals(false, p.get("overLimit"));
        assertTrue(rows.isEmpty(), "미리보기는 기록을 만들지 않는다");
    }

    @Test
    void 이어_붙이면_대표에_결과가_붙고_구성_파일은_대표를_가리킨다() throws Exception {
        placeParts();
        Map<String, Object> out = service.combine(List.of("a-part.txt", "b-part.txt", "c-part.txt"), "tester");
        assertEquals("c-part.txt", out.get("representative"));
        await("c-part.txt");
        ThreadDumpAnalysisEntity rep = rows.get("c-part.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, rep.getStatus(), rep.getErrorMessage());
        assertEquals(3, rep.getMemberCount());
        assertEquals(List.of("c-part.txt", "b-part.txt", "a-part.txt"), ThreadDumpAnalyzerService.parseMembers(rep.getMemberFiles()));
        assertEquals(3, rep.getSnapshotCount());
        assertEquals("c-part.txt", rows.get("a-part.txt").getPartOf());
        assertEquals("c-part.txt", rows.get("b-part.txt").getPartOf());
        ThreadDumpResult r = service.loadResult("c-part.txt").orElseThrow();
        assertEquals(List.of("c-part.txt", "b-part.txt", "a-part.txt"), r.getMeta().getFiles());
        assertEquals("a-part.txt", r.getSnapshots().get(2).getFile());
        assertTrue(r.getFindings().stream().anyMatch(f -> "DEADLOCK".equals(f.getCode())));

        // 재분석도 저장된 구성 그대로
        service.submitAnalysis("c-part.txt", "tester");
        await("c-part.txt");
        assertEquals(3, rows.get("c-part.txt").getSnapshotCount());
    }

    @Test
    void 묶음을_풀면_구성_파일은_단독으로_돌아가고_묶음_결과는_지워진다() throws Exception {
        placeParts();
        service.combine(List.of("a-part.txt", "b-part.txt", "c-part.txt"), "tester");
        await("c-part.txt");
        service.ungroup("c-part.txt", "tester");
        assertNull(rows.get("a-part.txt").getPartOf());
        assertNull(rows.get("b-part.txt").getPartOf());
        ThreadDumpAnalysisEntity rep = rows.get("c-part.txt");
        assertFalse(rep.isCombined());
        assertEquals(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED, rep.getStatus());
        assertTrue(service.loadResult("c-part.txt").isEmpty());
        assertTrue(service.fileOf("a-part.txt").isFile(), "구성 파일 자체는 남는다");
        assertThrows(IllegalArgumentException.class, () -> service.ungroup("c-part.txt", "tester"));
    }

    @Test
    void 구성_파일을_지우면_대표의_구성에서만_빠진다() throws Exception {
        placeParts();
        service.combine(List.of("a-part.txt", "b-part.txt", "c-part.txt"), "tester");
        await("c-part.txt");
        service.delete("a-part.txt", true, "tester");
        ThreadDumpAnalysisEntity rep = rows.get("c-part.txt");
        assertEquals(List.of("c-part.txt", "b-part.txt"), ThreadDumpAnalyzerService.parseMembers(rep.getMemberFiles()));
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, rep.getStatus(), "이미 만든 결과는 그대로 둔다");

        // 대표를 지우면 남은 구성 파일은 단독으로 돌아간다
        service.delete("c-part.txt", true, "tester");
        assertNull(rows.get("b-part.txt").getPartOf());
        assertTrue(service.fileOf("b-part.txt").isFile());
    }

    @Test
    void 구성_파일이_사라진_묶음을_다시_분석하면_FILE_MISSING_으로_이름을_알린다() throws Exception {
        placeParts();
        service.combine(List.of("a-part.txt", "b-part.txt", "c-part.txt"), "tester");
        await("c-part.txt");
        Files.delete(root.resolve("dumpfiles").resolve("b-part.txt"));       // 기록 정리 없이 디스크에서만 사라짐
        service.submitAnalysis("c-part.txt", "tester");
        await("c-part.txt");
        ThreadDumpAnalysisEntity rep = rows.get("c-part.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_ERROR, rep.getStatus());
        assertEquals("FILE_MISSING", rep.getErrorCode());
        assertTrue(rep.getErrorMessage().contains("b-part.txt"), rep.getErrorMessage());
    }

    @Test
    void 이어_붙이기_입력_검증() throws Exception {
        placeParts();
        assertThrows(IllegalArgumentException.class, () -> service.combine(List.of("a-part.txt"), "t"));
        assertThrows(IllegalArgumentException.class, () -> service.combine(List.of("a-part.txt", "a-part.txt"), "t"));
        assertThrows(IllegalArgumentException.class, () -> service.combine(List.of("a-part.txt", "nope.txt"), "t"));
        assertThrows(IllegalArgumentException.class, () -> service.combine(List.of("a-part.txt", "../etc/passwd"), "t"));
        assertThrows(IllegalArgumentException.class, () -> service.combine(null, "t"));
        assertTrue(rows.isEmpty());
    }

    @Test
    void 다른_묶음의_대표를_새_묶음에_넣으면_옛_묶음은_풀린다() throws Exception {
        placeParts();
        service.combine(List.of("a-part.txt", "b-part.txt"), "t");      // 대표 b
        await("b-part.txt");
        assertEquals("b-part.txt", rows.get("a-part.txt").getPartOf());
        service.combine(List.of("c-part.txt", "b-part.txt"), "t");      // 대표 c — b 가 이끌던 묶음은 풀린다
        await("c-part.txt");
        assertNull(rows.get("a-part.txt").getPartOf());
        assertEquals("c-part.txt", rows.get("b-part.txt").getPartOf());
        assertFalse(rows.get("b-part.txt").isCombined());
        assertEquals(2, rows.get("c-part.txt").getMemberCount());
    }

    @Test
    void 업로드_직후_제안은_같은_JVM_의_파일만_묶는다() throws Exception {
        placeParts();
        Files.writeString(root.resolve("dumpfiles").resolve("other.txt"), fixture("jdk21-truncated.txt"));
        try (InputStream in = getClass().getResourceAsStream("/threaddump/jdk8-deadlock-3snap.txt")) {
            Files.write(root.resolve("dumpfiles").resolve("jdk8.txt"), in.readAllBytes());
        }
        List<Map<String, Object>> groups = service.groupSuggestion(List.of("a-part.txt", "jdk8.txt", "b-part.txt", "c-part.txt", "missing.txt"));
        assertEquals(1, groups.size(), groups.toString());
        assertEquals(List.of("c-part.txt", "b-part.txt", "a-part.txt"), groups.get(0).get("files"), "시각순");
        assertEquals(3, groups.get(0).get("snapshots"));
        assertTrue(rows.isEmpty(), "제안은 기록을 만들지 않는다");
    }

    // ── 힙 덤프 연결 ─────────────────────────────────────────────

    private HeapThreadService heapMock(String heapName, String status, String dumpTime, String server) {
        HeapThreadService h = Mockito.mock(HeapThreadService.class);
        AnalysisHistoryEntity he = new AnalysisHistoryEntity();
        he.setFilename(heapName);
        he.setStatus(status);
        he.setDumpCreationTime(dumpTime);
        he.setServerName(server);
        he.setUsedHeapSize(512L * 1024 * 1024);
        Mockito.when(h.validateFilename(Mockito.anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(h.entity(heapName)).thenReturn(Optional.of(he));
        Mockito.when(h.entity(Mockito.argThat(s -> !heapName.equals(s)))).thenReturn(Optional.empty());
        Mockito.when(h.analyzedHeaps()).thenReturn("SUCCESS".equals(status) ? List.of(he) : List.of());
        Map<String, HeapThreadExtractor.HeapThread> byName = new LinkedHashMap<>();
        byName.put("deadlock-A", new HeapThreadExtractor.HeapThread("0x1", "deadlock-A", "java.lang.Thread", false, 5, 0x401, 4_000_000L, 3_000_000L, List.of()));
        byName.put("hot-lock-owner", new HeapThreadExtractor.HeapThread("0x2", "hot-lock-owner", "java.lang.Thread", false, 5, 0x5, 9_000_000L, null, List.of()));
        HeapThreadService.Loaded loaded = new HeapThreadService.Loaded(heapName, emptyHeapResult(), "{}", he, byName, 0, "sig");
        Mockito.when(h.load(heapName)).thenReturn(loaded);
        return h;
    }

    private static ThreadDumpResult emptyHeapResult() {
        ThreadDumpResult r = new ThreadDumpResult();
        ThreadDumpResult.Snapshot s = new ThreadDumpResult.Snapshot();
        s.setThreads(2);
        r.getSnapshots().add(s);
        return r;
    }

    @Test
    void 힙_덤프_연결과_스레드_이름_이음() throws Exception {
        placeParts();
        service.submitAnalysis("c-part.txt", "t");
        await("c-part.txt");
        service.heapThreads = heapMock("app.hprof", "SUCCESS", "2026-11-26 10:53:30", "was01");

        Map<String, Object> before = service.heapLinkView("c-part.txt");
        assertEquals(false, before.get("linked"));

        service.linkHeap("c-part.txt", "app.hprof", "tester");
        ThreadDumpAnalysisEntity e = rows.get("c-part.txt");
        assertEquals("app.hprof", e.getLinkedHeapFilename());
        assertEquals("tester", e.getLinkedBy());
        assertNotNull(e.getLinkedAt());

        Map<String, Object> v = service.heapLinkView("c-part.txt");
        assertEquals(true, v.get("linked"));
        assertEquals(2, v.get("matchedThreads"));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> mem = (Map<String, Map<String, Object>>) v.get("memory");
        assertEquals(4_000_000L, mem.get("deadlock-A").get("r"));
        assertEquals("BLOCKED (on object monitor)", mem.get("deadlock-A").get("s"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) v.get("topThreads");
        assertEquals("hot-lock-owner", top.get(0).get("name"), "힙 점유 큰 순");
        assertEquals(21L, v.get("gapSec"), "스냅샷(10:53:09)과 덤프 시각 차");

        assertEquals(1, service.linkedToHeap("app.hprof").size());
        assertTrue(service.promptSectionForHeap("app.hprof").contains("c-part.txt"));
        String section = service.heapLinkPromptSection(e, service.loadResult("c-part.txt").orElseThrow());
        assertTrue(section.contains("== 연결된 힙 덤프 ==") && section.contains("hot-lock-owner"), section);

        service.linkHeap("c-part.txt", "", "tester");
        assertNull(rows.get("c-part.txt").getLinkedHeapFilename());
        assertTrue(service.linkedToHeap("app.hprof").isEmpty());
    }

    @Test
    void 분석이_끝나지_않은_힙_덤프는_연결하지_않는다() throws Exception {
        placeParts();
        service.submitAnalysis("c-part.txt", "t");
        await("c-part.txt");
        service.heapThreads = heapMock("app.hprof", "ANALYZING", null, null);
        assertThrows(IllegalArgumentException.class, () -> service.linkHeap("c-part.txt", "app.hprof", "t"));
        assertThrows(IllegalArgumentException.class, () -> service.linkHeap("c-part.txt", "other.hprof", "t"));
        assertNull(rows.get("c-part.txt").getLinkedHeapFilename());
    }

    @Test
    void 후보는_점수만_매기고_자동으로_연결하지_않는다() throws Exception {
        placeParts();
        service.submitAnalysis("c-part.txt", "t");
        await("c-part.txt");
        rows.get("c-part.txt").setServerName("was01");
        service.heapThreads = heapMock("app.hprof", "SUCCESS", "2026-11-26 10:55:00", " was01 ");
        List<Map<String, Object>> c = service.heapCandidates("c-part.txt");
        assertEquals(1, c.size());
        @SuppressWarnings("unchecked") List<String> reasons = (List<String>) c.get(0).get("reasons");
        assertTrue(reasons.contains("같은 서버"), reasons.toString());
        assertTrue(reasons.stream().anyMatch(s -> s.startsWith("덤프 시각이 스냅샷과")), reasons.toString());
        // 힙 쪽 이름 2개만 겹친다 — 절반에 못 미쳐 근거로 치지 않고 비율만 알린다
        assertFalse(reasons.stream().anyMatch(s -> s.startsWith("스레드 이름")), reasons.toString());
        assertTrue((int) c.get(0).get("nameMatchPct") < 50);
        assertEquals(2, c.get(0).get("score"));
        assertNull(rows.get("c-part.txt").getLinkedHeapFilename(), "후보 조회는 연결하지 않는다");
    }

    @Test
    void 같은_이름의_새_파일은_옛_연결과_묶음을_이어받지_않는다() throws Exception {
        placeParts();
        service.combine(List.of("a-part.txt", "c-part.txt"), "t");
        await("c-part.txt");
        ThreadDumpAnalysisEntity rep = rows.get("c-part.txt");
        rep.setLinkedHeapFilename("app.hprof");
        service.forgetAnalysis(rep, "reupload");
        assertNull(rep.getLinkedHeapFilename());
        assertNull(rep.getMemberFiles());
        assertNull(rows.get("a-part.txt").getPartOf());
    }

    // ── 힙 덤프 → 스레드 덤프 파일 내보내기 ─────────────────────

    private HeapThreadService exportMock() throws IOException {
        String overview = fixture("heap/synthetic-overview.html");
        String threads = fixture("heap/synthetic.threads");
        HeapThreadExtractor.Extract x = HeapThreadExtractor.extract(overview, threads, "2026-11-26 10:00:00");
        HeapThreadService h = Mockito.mock(HeapThreadService.class);
        Mockito.when(h.exportSource("app.hprof"))
                .thenReturn(new HeapThreadService.ExportSource("app.hprof", "app", x.dumpText(), x.threads().size(), "was01"));
        return h;
    }

    @Test
    void 힙_덤프_스레드를_파일로_내보내면_분석까지_마친_채_연결되어_목록에_오른다() throws Exception {
        service.heapThreads = exportMock();
        ThreadDumpAnalyzerService.HeapExport x = service.exportFromHeap("app.hprof", false, "tester");
        assertEquals("app_threads.txt", x.filename());
        Path f = root.resolve("dumpfiles").resolve("app_threads.txt");
        assertTrue(Files.isRegularFile(f));
        assertEquals(Files.size(f), x.size());
        assertTrue(Files.readString(f).contains(HeapThreadExtractor.VM_LINE));
        ThreadDumpAnalysisEntity e = rows.get("app_threads.txt");
        // 힙 화면에서 이미 분석을 본 상태 — 목록에 '미분석' 으로 올려 같은 분석을 다시 시작하게 하지 않는다
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, x.status(), x.error());
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, e.getStatus(), e.getErrorMessage());
        assertNull(x.error());
        assertEquals("app.hprof", e.getLinkedHeapFilename(), "같은 덤프에서 나온 파일이라 연결된 채로 등록하고, 분석 뒤에도 유지한다");
        assertEquals("tester", e.getLinkedBy());
        assertEquals("tester", e.getUploadedBy());
        assertEquals("was01", e.getServerName());
        assertTrue(service.history().stream().anyMatch(r -> "app_threads.txt".equals(r.getFilename())), "스레드 덤프 목록에 보여야 한다");
        try (var tmp = Files.list(root.resolve("tmp"))) { assertEquals(0, tmp.count(), "임시 파일이 남았다"); }

        assertEquals(x.threads(), e.getThreadCount());
        ThreadDumpResult r = service.loadResult("app_threads.txt").orElseThrow();
        assertTrue(r.getFindings().stream().anyMatch(fd -> "HEAP_DERIVED".equals(fd.getCode())), "힙에서 내보낸 파일이라는 안내가 없다");
        assertTrue(r.getFindings().stream().noneMatch(fd -> "SINGLE_SNAPSHOT".equals(fd.getCode())), "한 시점이 전제인데 스냅샷 1개 소견이 나왔다");
        assertNotEquals("heap", r.getMeta().getSource(), "파일로 올린 분석은 스레드 덤프 결과다");
    }

    @Test
    void 이미_내보낸_파일이_있으면_덮어쓰지_않고_새_이름은_번호를_붙인다() throws Exception {
        service.heapThreads = exportMock();
        service.exportFromHeap("app.hprof", false, "tester");
        Path f = root.resolve("dumpfiles").resolve("app_threads.txt");
        Files.writeString(f, Files.readString(f) + "\n# 사용자가 고친 내용\n");
        long before = Files.size(f);

        ThreadDumpAnalyzerService.ExportExistsException ex = assertThrows(ThreadDumpAnalyzerService.ExportExistsException.class,
                () -> service.exportFromHeap("app.hprof", false, "tester"));
        assertEquals("app_threads.txt", ex.filename());
        assertEquals(before, Files.size(f), "기존 파일을 건드리지 않는다");

        assertEquals("app_threads-2.txt", service.exportFromHeap("app.hprof", true, "tester").filename());
        assertEquals("app_threads-3.txt", service.exportFromHeap("app.hprof", true, "tester").filename());
        try (var tmp = Files.list(root.resolve("tmp"))) { assertEquals(0, tmp.count()); }
    }

    @Test
    void 힙_연계가_없으면_내보낼_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> service.exportFromHeap("app.hprof", false, "tester"));
    }

    @Test
    void 시각_차이_계산() {
        assertEquals(0L, ThreadDumpAnalyzerService.gapMs(1500L, 1000L, 2000L));
        assertEquals(500L, ThreadDumpAnalyzerService.gapMs(500L, 1000L, 2000L));
        assertEquals(1000L, ThreadDumpAnalyzerService.gapMs(3000L, 1000L, 2000L));
        assertEquals(100L, ThreadDumpAnalyzerService.gapMs(1100L, 1000L, null));
        assertNull(ThreadDumpAnalyzerService.gapMs(null, 1000L, 2000L));
        assertNull(ThreadDumpAnalyzerService.gapMs(1000L, null, null));
        assertEquals("1시간 1분", ThreadDumpAnalyzerService.fmtGap(3_660_000L));
    }
}

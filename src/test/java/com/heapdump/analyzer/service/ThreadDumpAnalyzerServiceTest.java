package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.model.entity.ThreadDumpResultDetailEntity;
import com.heapdump.analyzer.repository.ThreadDumpAnalysisRepository;
import com.heapdump.analyzer.repository.ThreadDumpResultDetailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스레드 덤프 서비스 — 실제 실행기를 태워 업로드 → 분석 → 저장 경로와, 행이 ANALYZING 에 남지 않게 하는 네 겹을 본다.
 * 리포지토리만 메모리 맵으로 흉내 낸다.
 */
class ThreadDumpAnalyzerServiceTest {

    @TempDir Path root;

    private HeapDumpConfig config;
    private ThreadDumpAnalysisRepository repo;
    private ThreadDumpResultDetailRepository detailRepo;
    private AiInsightManager ai;
    private LlmConfigService llm;
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

        repo = Mockito.mock(ThreadDumpAnalysisRepository.class);
        Mockito.when(repo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0))));
        Mockito.when(repo.save(Mockito.any(ThreadDumpAnalysisEntity.class))).thenAnswer(inv -> {
            ThreadDumpAnalysisEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId((long) (rows.size() + 1));
            synchronized (rows) { rows.put(e.getFilename(), e); }
            return e;
        });
        Mockito.when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(inv -> new ArrayList<>(rows.values()));
        Mockito.when(repo.findByStatus(Mockito.anyString())).thenAnswer(inv ->
                rows.values().stream().filter(e -> inv.getArgument(0).equals(e.getStatus())).toList());
        Mockito.doAnswer(inv -> { rows.remove(inv.<ThreadDumpAnalysisEntity>getArgument(0).getFilename()); return null; })
                .when(repo).delete(Mockito.any(ThreadDumpAnalysisEntity.class));

        detailRepo = Mockito.mock(ThreadDumpResultDetailRepository.class);
        Mockito.when(detailRepo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(details.get(inv.<String>getArgument(0))));
        Mockito.when(detailRepo.save(Mockito.any(ThreadDumpResultDetailEntity.class))).thenAnswer(inv -> {
            ThreadDumpResultDetailEntity d = inv.getArgument(0);
            details.put(d.getFilename(), d);
            return d;
        });
        Mockito.doAnswer(inv -> { details.remove(inv.<String>getArgument(0)); return null; }).when(detailRepo).deleteByFilename(Mockito.anyString());

        ai = Mockito.mock(AiInsightManager.class);
        llm = Mockito.mock(LlmConfigService.class);
        service = new ThreadDumpAnalyzerService(config, repo, detailRepo, llm, ai);
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = ThreadDumpAnalyzerServiceTest.class.getResourceAsStream("/threaddump/" + name)) {
            return in.readAllBytes();
        }
    }

    private ThreadDumpAnalysisEntity upload(String name, byte[] body) throws IOException {
        return service.storeUpload(name, body.length, dest -> Files.write(dest.toPath(), body), "tester", " was-01 ");
    }

    private void await(String name) throws Exception {
        for (int i = 0; i < 400 && (service.isAnalyzing(name) || ThreadDumpAnalysisEntity.STATUS_ANALYZING.equals(rows.get(name).getStatus())); i++) {
            Thread.sleep(25);
        }
    }

    private String[] tmpFiles() {
        String[] l = root.resolve("tmp").toFile().list();
        return l == null ? new String[0] : l;
    }

    // ── 업로드 ──────────────────────────────────────────────────

    @Test
    void 업로드는_형식을_확인한_뒤에만_저장소에_들어간다() throws Exception {
        ThreadDumpAnalysisEntity e = upload("jstack-app.txt", fixture("jdk8-deadlock-3snap.txt"));
        assertEquals(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED, e.getStatus());
        assertEquals("tester", e.getUploadedBy());
        assertEquals("was-01", e.getServerName());
        assertTrue(service.fileOf("jstack-app.txt").isFile());
        assertEquals(0, tmpFiles().length, "임시 파일이 남지 않는다");
    }

    @Test
    void 스레드_덤프가_아니면_거부하고_흔적을_남기지_않는다() {
        var ex = assertThrows(ThreadDumpAnalyzerService.UploadRejectedException.class,
                () -> upload("notes.txt", "hello\nworld\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("NOT_THREAD_DUMP", ex.code());
        assertEquals(400, ex.httpStatus());
        assertFalse(service.fileOf("notes.txt").exists());
        assertTrue(rows.isEmpty(), "거부된 파일의 기록이 생기면 안 된다");
        assertEquals(0, tmpFiles().length);

        var j9 = assertThrows(ThreadDumpAnalyzerService.UploadRejectedException.class,
                () -> upload("javacore.txt", "0SECTION       TITLE\n1TISIGINFO     Dump Event\n3XMTHREADINFO  \"main\"\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("UNSUPPORTED_FORMAT", j9.code());
        assertTrue(j9.getMessage().contains("javacore"));
    }

    @Test
    void 이름_중복과_크기_초과_빈_파일() throws Exception {
        upload("a.txt", fixture("jdk21-truncated.txt"));
        var dup = assertThrows(ThreadDumpAnalyzerService.UploadRejectedException.class, () -> upload("a.txt", fixture("jdk21-truncated.txt")));
        assertEquals("DUPLICATE_NAME", dup.code());
        assertEquals(409, dup.httpStatus());

        var big = assertThrows(ThreadDumpAnalyzerService.UploadRejectedException.class,
                () -> service.storeUpload("big.txt", 9L * 1024 * 1024, dest -> fail("상한을 넘으면 받지도 않는다"), "tester", null));
        assertEquals("TOO_LARGE", big.code());
        assertEquals(413, big.httpStatus());

        var empty = assertThrows(ThreadDumpAnalyzerService.UploadRejectedException.class, () -> upload("empty.txt", new byte[0]));
        assertEquals("EMPTY", empty.code());
        assertEquals(0, tmpFiles().length);

        // 경로 요소는 떼어 내고 저장소 안에만 쓴다
        ThreadDumpAnalysisEntity esc = upload("../../evil.txt", fixture("jdk21-truncated.txt"));
        assertEquals("evil.txt", esc.getFilename());
        assertTrue(service.fileOf("evil.txt").isFile());
        assertFalse(root.resolve("evil.txt").toFile().exists());
        assertThrows(IllegalArgumentException.class, () -> upload("..", fixture("jdk21-truncated.txt")));
        assertThrows(IllegalArgumentException.class, () -> upload("x".repeat(481) + ".txt", fixture("jdk21-truncated.txt")), "이름 길이 상한");
    }

    // ── 분석 ────────────────────────────────────────────────────

    @Test
    void 분석_성공_요약과_결과_JSON_저장() throws Exception {
        upload("jstack-app.txt", fixture("jdk8-deadlock-3snap.txt"));
        service.submitAnalysis("jstack-app.txt", "tester");
        await("jstack-app.txt");
        ThreadDumpAnalysisEntity e = rows.get("jstack-app.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, e.getStatus(), () -> "" + e.getErrorMessage());
        assertEquals(3, e.getSnapshotCount());
        assertEquals(36, e.getThreadCount());
        assertEquals(1, e.getDeadlockCount());
        assertEquals("Critical", e.getSeverity());
        assertEquals(8, e.getJdkMajor());
        assertNotNull(e.getFirstSnapshotAt());
        assertNotNull(e.getAnalyzedAt());
        assertNull(e.getErrorCode());

        String json = service.loadResultJson("jstack-app.txt").orElseThrow();
        assertTrue(json.contains("\"DEADLOCK\""));
        ThreadDumpResult r = service.loadResult("jstack-app.txt").orElseThrow();
        assertEquals(1, r.getDeadlocks().size());
        assertEquals("SUCCESS", service.getStatus("jstack-app.txt").get("status"));
        assertFalse(service.isAnalyzing("jstack-app.txt"));
        Mockito.verify(ai, Mockito.atLeastOnce()).deleteAiInsight("__threaddump__:jstack-app.txt");
    }

    @Test
    void 저장소에_직접_놓인_잘못된_파일은_ERROR_와_코드로_끝난다() throws Exception {
        Files.writeString(service.fileOf("plain.txt").toPath(), "not a dump\n");
        service.submitAnalysis("plain.txt", "tester");
        await("plain.txt");
        ThreadDumpAnalysisEntity e = rows.get("plain.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_ERROR, e.getStatus());
        assertEquals("NOT_THREAD_DUMP", e.getErrorCode());
        assertTrue(e.getErrorMessage().startsWith("NOT_THREAD_DUMP: "));
        Map<String, Object> st = service.getStatus("plain.txt");
        assertEquals("ERROR", st.get("status"));
        assertEquals("NOT_THREAD_DUMP", st.get("errorCode"));
        assertTrue(service.loadResultJson("plain.txt").isEmpty());
    }

    @Test
    void 파일이_없으면_제출을_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> service.submitAnalysis("ghost.txt", "tester"));
        assertTrue(rows.isEmpty());
    }

    @Test
    void OutOfMemoryError_도_ERROR_로_기록된다() throws Exception {
        upload("oom.txt", fixture("jdk21-truncated.txt"));
        Mockito.when(detailRepo.save(Mockito.any(ThreadDumpResultDetailEntity.class))).thenThrow(new OutOfMemoryError("Java heap space"));
        service.submitAnalysis("oom.txt", "tester");
        await("oom.txt");
        ThreadDumpAnalysisEntity e = rows.get("oom.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_ERROR, e.getStatus(), "Exception 만 잡으면 이 행은 영원히 ANALYZING 이다");
        assertEquals("OUT_OF_MEMORY", e.getErrorCode());
        assertFalse(service.isAnalyzing("oom.txt"));
    }

    @Test
    void 예상_못_한_예외는_INTERNAL() throws Exception {
        upload("npe.txt", fixture("jdk21-truncated.txt"));
        Mockito.when(detailRepo.save(Mockito.any(ThreadDumpResultDetailEntity.class))).thenThrow(new IllegalStateException("boom"));
        service.submitAnalysis("npe.txt", "tester");
        await("npe.txt");
        assertEquals("INTERNAL", rows.get("npe.txt").getErrorCode());
        assertTrue(rows.get("npe.txt").getErrorMessage().contains("IllegalStateException"));
    }

    @Test
    void 실패_상태_저장이_실패해도_실행기는_살아_있다() throws Exception {
        upload("a.txt", fixture("jdk21-truncated.txt"));
        Files.writeString(service.fileOf("bad.txt").toPath(), "not a dump\n");
        // bad.txt 의 실패 기록 저장(ERROR)만 터뜨린다
        // (메모리 맵은 같은 객체를 들고 있으므로, 저장되지 않은 것을 흉내 내려 상태를 되돌린 뒤 던진다)
        Mockito.when(repo.save(Mockito.argThat((ThreadDumpAnalysisEntity x) -> x != null && "bad.txt".equals(x.getFilename())
                && ThreadDumpAnalysisEntity.STATUS_ERROR.equals(x.getStatus())))).thenAnswer(inv -> {
            ThreadDumpAnalysisEntity x = inv.getArgument(0);
            x.setStatus(ThreadDumpAnalysisEntity.STATUS_ANALYZING);
            x.setErrorCode(null);
            throw new RuntimeException("db down");
        });
        service.submitAnalysis("bad.txt", "tester");
        for (int i = 0; i < 200 && service.isAnalyzing("bad.txt"); i++) Thread.sleep(25);
        // 다음 작업이 정상적으로 돈다
        service.submitAnalysis("a.txt", "tester");
        await("a.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, rows.get("a.txt").getStatus());
        // 남은 ANALYZING 행은 상태 조회가 고친다
        assertEquals(ThreadDumpAnalysisEntity.STATUS_ANALYZING, rows.get("bad.txt").getStatus());
        Mockito.reset(repo);
        Mockito.when(repo.findByFilename(Mockito.anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0))));
        Mockito.when(repo.save(Mockito.any(ThreadDumpAnalysisEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> st = service.getStatus("bad.txt");
        assertEquals("ERROR", st.get("status"));
        assertEquals("INTERRUPTED", st.get("errorCode"));
    }

    @Test
    void 대기_중인_작업을_취소하면_직접_CANCELLED_를_쓴다() throws Exception {
        upload("first.txt", fixture("jdk8-deadlock-3snap.txt"));
        upload("second.txt", fixture("jdk21-truncated.txt"));
        // 실행기(1스레드)를 첫 작업에 붙잡아 둔다
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Mockito.when(detailRepo.save(Mockito.any(ThreadDumpResultDetailEntity.class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            ThreadDumpResultDetailEntity d = inv.getArgument(0);
            details.put(d.getFilename(), d);
            return d;
        });
        service.submitAnalysis("first.txt", "tester");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.submitAnalysis("second.txt", "tester");
        assertEquals("queued", service.getStatus("second.txt").get("phase"));

        assertTrue(service.cancel("second.txt", "admin"));
        ThreadDumpAnalysisEntity e = rows.get("second.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_ERROR, e.getStatus(), "runAnalysis 가 돌지 않으므로 cancel 이 직접 써야 한다");
        assertEquals("CANCELLED", e.getErrorCode());
        assertFalse(service.isAnalyzing("second.txt"));
        assertFalse(service.cancel("second.txt", "admin"), "이미 끝난 작업");

        release.countDown();
        await("first.txt");
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, rows.get("first.txt").getStatus());
    }

    @Test
    void 재기동으로_끊긴_분석은_기동_직후_INTERRUPTED_로_돌린다() {
        ThreadDumpAnalysisEntity stale = new ThreadDumpAnalysisEntity();
        stale.setFilename("stale.txt");
        stale.setStatus(ThreadDumpAnalysisEntity.STATUS_ANALYZING);
        rows.put("stale.txt", stale);
        ThreadDumpAnalysisEntity ok = new ThreadDumpAnalysisEntity();
        ok.setFilename("ok.txt");
        ok.setStatus(ThreadDumpAnalysisEntity.STATUS_SUCCESS);
        rows.put("ok.txt", ok);

        service.recoverInterrupted();
        assertEquals(ThreadDumpAnalysisEntity.STATUS_ERROR, stale.getStatus());
        assertEquals("INTERRUPTED", stale.getErrorCode());
        assertTrue(stale.getErrorMessage().contains("다시 분석"));
        assertEquals(ThreadDumpAnalysisEntity.STATUS_SUCCESS, ok.getStatus());
    }

    @Test
    void 복구_중_DB_오류는_기동을_막지_않는다() {
        Mockito.when(repo.findByStatus(Mockito.anyString())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.recoverInterrupted());
    }

    // ── 분류 이동 ───────────────────────────────────────────────

    @Test
    void 편입은_형식_검사가_이동보다_먼저다() throws Exception {
        Path other = Files.createDirectories(root.resolve("heap"));
        Path good = other.resolve("catalina.out");
        Files.write(good, fixture("jdk21-kill3-embedded.txt"));
        ThreadDumpAnalysisEntity e = service.adoptFile(good.toFile(), "admin", "was-02");
        assertFalse(Files.exists(good));
        assertTrue(service.fileOf("catalina.out").isFile());
        assertEquals("was-02", e.getServerName());
        assertEquals(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED, e.getStatus());

        Path bad = other.resolve("plain.txt");
        Files.writeString(bad, "nope\n");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.adoptFile(bad.toFile(), "admin", null));
        assertTrue(ex.getMessage().contains("분류할 수 없습니다"));
        assertTrue(Files.exists(bad), "거부되면 파일은 원래 자리에 그대로 있다");
        assertFalse(rows.containsKey("plain.txt"));

        Path dup = other.resolve("catalina.out");
        Files.write(dup, fixture("jdk21-kill3-embedded.txt"));
        assertThrows(IllegalStateException.class, () -> service.adoptFile(dup.toFile(), "admin", null));
        assertTrue(Files.exists(dup));
    }

    @Test
    void 내보내면_결과와_기록을_함께_지운다() throws Exception {
        upload("a.txt", fixture("jdk21-truncated.txt"));
        service.submitAnalysis("a.txt", "tester");
        await("a.txt");
        assertTrue(details.containsKey("a.txt"));
        File heap = Files.createDirectories(root.resolve("heap")).toFile();
        service.releaseFile("a.txt", heap, "admin");
        assertTrue(new File(heap, "a.txt").isFile());
        assertFalse(service.fileOf("a.txt").exists());
        assertFalse(rows.containsKey("a.txt"));
        assertFalse(details.containsKey("a.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.releaseFile("a.txt", heap, "admin"));
    }

    @Test
    void 같은_이름으로_다시_올리면_옛_결과가_붙지_않는다() throws Exception {
        upload("a.txt", fixture("jdk8-deadlock-3snap.txt"));
        service.submitAnalysis("a.txt", "tester");
        await("a.txt");
        assertEquals(1, rows.get("a.txt").getDeadlockCount());
        assertTrue(service.fileOf("a.txt").delete());          // 디스크에서만 지웠다 — 기록은 남는다
        ThreadDumpAnalysisEntity e = upload("a.txt", fixture("jdk21-truncated.txt"));
        assertEquals(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED, e.getStatus());
        assertNull(e.getDeadlockCount());
        assertNull(e.getSeverity());
        assertFalse(details.containsKey("a.txt"));
        assertTrue(service.loadResult("a.txt").isEmpty(), "캐시에도 옛 결과가 없어야 한다");
    }

    // ── 디스크 동기화·삭제 ──────────────────────────────────────

    @Test
    void 디스크_동기화_등록_사라짐_다시_나타남() throws Exception {
        Files.write(service.fileOf("disk.txt").toPath(), fixture("jdk21-truncated.txt"));
        Files.writeString(service.fileOf(".hidden").toPath(), "x");
        List<ThreadDumpAnalysisEntity> list = service.syncWithDisk();
        assertEquals(1, list.size());
        assertEquals("disk.txt", list.get(0).getFilename());

        rows.get("disk.txt").setSeverity("High");
        assertTrue(service.fileOf("disk.txt").delete());
        assertTrue(service.syncWithDisk().isEmpty());
        assertTrue(rows.get("disk.txt").isFileDeleted());

        // 같은 크기로 돌아오면 결과를 살리고, 다른 크기면 다른 파일이다
        Files.write(service.fileOf("disk.txt").toPath(), fixture("jdk21-truncated.txt"));
        service.syncWithDisk();
        assertFalse(rows.get("disk.txt").isFileDeleted());
        assertEquals("High", rows.get("disk.txt").getSeverity());

        assertTrue(service.fileOf("disk.txt").delete());
        service.syncWithDisk();
        Files.write(service.fileOf("disk.txt").toPath(), fixture("jdk8-deadlock-3snap.txt"));
        service.syncWithDisk();
        assertNull(rows.get("disk.txt").getSeverity(), "크기가 다르면 옛 결과를 지운다");
    }

    @Test
    void 파일_하나의_등록_실패가_목록_전체를_비우지_않는다() throws Exception {
        Files.write(service.fileOf("a.txt").toPath(), fixture("jdk21-truncated.txt"));
        Files.write(service.fileOf("b.txt").toPath(), fixture("jdk21-truncated.txt"));
        Mockito.when(repo.save(Mockito.argThat((ThreadDumpAnalysisEntity x) -> x != null && "a.txt".equals(x.getFilename()))))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));
        List<ThreadDumpAnalysisEntity> list = assertDoesNotThrow(() -> service.syncWithDisk());
        assertEquals(1, list.size());
        assertEquals("b.txt", list.get(0).getFilename());
    }

    @Test
    void 삭제_파일까지_또는_결과만() throws Exception {
        upload("a.txt", fixture("jdk21-truncated.txt"));
        service.submitAnalysis("a.txt", "tester");
        await("a.txt");
        service.delete("a.txt", false, "admin");
        assertTrue(service.fileOf("a.txt").isFile());
        assertEquals(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED, rows.get("a.txt").getStatus());
        assertNull(rows.get("a.txt").getSnapshotCount());
        assertFalse(details.containsKey("a.txt"));

        service.delete("a.txt", true, "admin");
        assertFalse(service.fileOf("a.txt").exists());
        assertFalse(rows.containsKey("a.txt"));
    }

    @Test
    void 손상된_결과_JSON_은_없는_것으로_본다() {
        ThreadDumpResultDetailEntity d = new ThreadDumpResultDetailEntity();
        d.setFilename("x.txt");
        d.setResultJson("{\"schema\":1,\"tracks\":[{");
        details.put("x.txt", d);
        assertTrue(service.loadResult("x.txt").isEmpty());
        assertTrue(service.loadResultJson("x.txt").isPresent(), "원문은 그대로 돌려준다 — 화면이 파싱 실패를 보고 재분석을 안내한다");
    }

    // ── AI ──────────────────────────────────────────────────────

    @Test
    void AI_분석_결과가_없으면_호출하지_않는다() {
        Map<String, Object> res = service.analyzeWithAi("none.txt");
        assertEquals(false, res.get("success"));
        assertEquals("NO_RESULT", res.get("errorCode"));
        Mockito.verifyNoInteractions(llm);
    }

    @Test
    void AI_분석_프롬프트와_응답_정규화() throws Exception {
        upload("a.txt", fixture("jdk8-deadlock-3snap.txt"));
        service.submitAnalysis("a.txt", "tester");
        await("a.txt");
        Map<String, Object> llmRes = new LinkedHashMap<>();
        llmRes.put("success", true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("severity", "critical");
        data.put("recommendations", "1. 락 순서 통일 2. tryLock 사용");
        data.put("codeAdvice", "x".repeat(5000));
        llmRes.put("data", data);
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.when(llm.callLlmAnalysis(prompt.capture(), Mockito.eq(ThreadDumpAnalyzerService.TD_SYSTEM_PROMPT))).thenReturn(llmRes);

        Map<String, Object> res = service.analyzeWithAi("a.txt");
        @SuppressWarnings("unchecked") Map<String, Object> out = (Map<String, Object>) res.get("data");
        assertEquals("Critical", out.get("severity"));
        assertEquals(List.of("락 순서 통일", "tryLock 사용"), out.get("recommendations"));
        assertTrue(String.valueOf(out.get("codeAdvice")).length() <= ThreadDumpAiResponseNormalizer.CODE_ADVICE_MAX + 1);

        String p = prompt.getValue();
        assertTrue(p.length() <= ThreadDumpPromptBuilder.MAX_CHARS);
        assertTrue(p.contains("== 교착 사슬 =="));
        assertTrue(p.contains("\"deadlock-A\""));
        assertTrue(p.contains("DeadlockDemo.lockAB"), "교착 스레드의 스택이 들어간다");
        assertTrue(p.contains("hot-lock-owner"));
        assertTrue(p.contains("서버: was-01"));
        assertTrue(p.trim().endsWith("}"), "형식 지시가 프롬프트 끝에 있다");
        assertFalse(p.contains("Full thread dump OpenJDK 64-Bit Server VM (25.422-b05 mixed mode):\n\n\""), "원문 덤프는 넣지 않는다");
    }

    @Test
    void 프롬프트가_길어도_형식_지시는_잘리지_않는다() {
        ThreadDumpResult r = new ThreadDumpResult();
        for (int i = 0; i < 400; i++) {
            ThreadDumpResult.Finding f = new ThreadDumpResult.Finding();
            f.setCode("X" + i);
            f.setSeverity("High");
            f.setTitle("제목 " + i);
            f.setDetail("상세 ".repeat(20));
            r.getFindings().add(f);
        }
        ThreadDumpAnalysisEntity e = new ThreadDumpAnalysisEntity();
        e.setFilename("big.txt");
        String p = ThreadDumpPromptBuilder.build(r, e);
        assertTrue(p.length() <= ThreadDumpPromptBuilder.MAX_CHARS, "" + p.length());
        assertTrue(p.contains("...(truncated)"));
        assertTrue(p.trim().endsWith("\"codeAdvice\": \"스택에서 보이는 클래스·메서드 기준의 수정 지점\"}"));
    }
}

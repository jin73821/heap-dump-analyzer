package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpException;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2.7.1 — 힙 덤프의 MAT 산출물(.threads + Overview ZIP 의 Thread Overview)에서 스레드 분석을 만든다.
 * 합성 픽스처(threaddump/heap/)만 쓴다 — 운영 덤프는 넣지 않는다.
 */
class HeapThreadServiceTest {

    @TempDir Path root;

    private HeapDumpAnalyzerService heap;
    private HeapThreadService svc;
    private AnalysisHistoryEntity entity;
    private File resultDir;

    @BeforeEach
    void setUp() throws IOException {
        resultDir = root.resolve("data/app.hprof").toFile();
        assertTrue(resultDir.mkdirs());
        heap = Mockito.mock(HeapDumpAnalyzerService.class);
        entity = new AnalysisHistoryEntity();
        entity.setFilename("app.hprof");
        entity.setStatus("SUCCESS");
        entity.setDumpCreationTime("2026-11-26 10:00:00");
        entity.setUsedHeapSize(300L * 1024 * 1024);
        entity.setSuspectCount(1);
        Mockito.when(heap.findHistoryEntity("app.hprof")).thenReturn(Optional.of(entity));
        Mockito.when(heap.findHistoryEntity(Mockito.argThat(s -> !"app.hprof".equals(s)))).thenReturn(Optional.empty());
        Mockito.when(heap.resultDirectoryPublic("app.hprof")).thenReturn(resultDir);
        Mockito.when(heap.stripExtensionPublic("app.hprof")).thenReturn("app");
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getHeapDumpDirectory()).thenReturn(root.resolve("dumpfiles").toString());
        Mockito.when(config.getThreadDumpMaxFileBytes()).thenReturn(8L * 1024 * 1024);
        Mockito.when(config.getThreadDumpMaxLineChars()).thenReturn(4096);
        Mockito.when(config.getThreadDumpMaxThreadsPerSnapshot()).thenReturn(20000);
        svc = new HeapThreadService(heap, Mockito.mock(AnalysisHistoryRepository.class), config,
                Mockito.mock(LlmConfigService.class), Mockito.mock(AiInsightManager.class));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = HeapThreadServiceTest.class.getResourceAsStream("/threaddump/heap/" + name)) {
            return in.readAllBytes();
        }
    }

    private File writeZip(String zipName, String entryName, byte[] page) throws IOException {
        File z = new File(resultDir, zipName);
        try (OutputStream os = Files.newOutputStream(z.toPath()); ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry("index.html"));
            zos.write("<html>index</html>".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(page);
            zos.closeEntry();
        }
        return z;
    }

    private void placeAll() throws IOException {
        Files.write(new File(resultDir, "app.threads").toPath(), fixture("synthetic.threads"));
        File z = writeZip("app_System_Overview.zip", "pages/Thread_Overview2.html", fixture("synthetic-overview.html"));
        Mockito.when(heap.findReportZip("app.hprof", "overview")).thenReturn(z);
    }

    @Test
    void 산출물에서_스레드_분석을_만들고_캐시한다() throws Exception {
        placeAll();
        HeapThreadService.Loaded l = svc.load("app.hprof");
        ThreadDumpResult r = l.result();
        assertEquals("heap", r.getMeta().getSource());
        assertEquals("app.hprof", r.getMeta().getHeapFilename());
        assertTrue(r.getFindings().stream().anyMatch(f -> "HEAP_DERIVED".equals(f.getCode())));
        assertTrue(l.byName().containsKey("main"));
        assertTrue(l.byName().containsKey("batch & <report> worker"), "HTML 이스케이프를 푼 이름");
        assertTrue(l.json().contains("\"source\":\"heap\""));
        assertSame(l, svc.load("app.hprof"), "산출물이 그대로면 캐시");

        // 산출물이 바뀌면 다시 만든다
        File threads = new File(resultDir, "app.threads");
        Files.write(threads.toPath(), fixture("synthetic.threads"));
        assertTrue(threads.setLastModified(threads.lastModified() + 5000));
        assertNotSame(l, svc.load("app.hprof"));
    }

    @Test
    void 요약은_힙_점유_상위와_풀을_담는다() throws Exception {
        placeAll();
        Map<String, Object> m = svc.summary("app.hprof");
        assertEquals(true, m.get("available"), String.valueOf(m.get("error")));
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) m.get("topMemory");
        assertFalse(top.isEmpty());
        assertEquals(9_876_543L, top.get(0).get("retained"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> pools = (List<Map<String, Object>>) m.get("pools");
        assertTrue(pools.stream().anyMatch(p -> "worker".equals(p.get("kind"))), pools.toString());
        String section = svc.promptSectionForHeapAi("app.hprof");
        assertTrue(section.startsWith("== 스레드 상태 (힙 덤프 시점) =="), section);
        assertTrue(section.length() <= 902);
        String ctx = HeapThreadService.heapContextSection(svc.load("app.hprof"));
        assertTrue(ctx.contains("사용 힙: ") && ctx.contains("Leak Suspects: 1건"), ctx);
    }

    @Test
    void Overview_가_없으면_Leak_Suspects_ZIP_에서_찾는다() throws Exception {
        Files.write(new File(resultDir, "app.threads").toPath(), fixture("synthetic.threads"));
        File noThreads = writeZip("app_System_Overview.zip", "pages/Top_Consumers.html", "<html/>".getBytes());
        File suspects = writeZip("app_Leak_Suspects.zip", "pages/Thread_Overview5.html", fixture("synthetic-overview.html"));
        Mockito.when(heap.findReportZip("app.hprof", "overview")).thenReturn(noThreads);
        Mockito.when(heap.findReportZip("app.hprof", "suspects")).thenReturn(suspects);
        assertEquals(suspects, svc.overviewZip("app.hprof"));
        assertTrue(svc.load("app.hprof").byName().containsKey("main"));
    }

    @Test
    void 실패는_코드로_알린다() throws Exception {
        ThreadDumpException none = assertThrows(ThreadDumpException.class, () -> svc.load("app.hprof"));
        assertEquals("HEAP_THREADS_UNAVAILABLE", none.code());
        Map<String, Object> m = svc.summary("app.hprof");
        assertEquals(false, m.get("available"));
        assertEquals("HEAP_THREADS_UNAVAILABLE", m.get("code"));
        assertEquals("", svc.promptSectionForHeapAi("app.hprof"), "힙 AI 를 막지 않는다");

        assertEquals("HEAP_NOT_FOUND", assertThrows(ThreadDumpException.class, () -> svc.load("other.hprof")).code());
        entity.setStatus("ANALYZING");
        assertEquals("HEAP_NOT_ANALYZED", assertThrows(ThreadDumpException.class, () -> svc.load("app.hprof")).code());
        // 경로는 떼고 파일명만 본다(FilenameValidator.validateSafe) — 다른 디렉토리로 나가지 않는다
        assertEquals("HEAP_NOT_FOUND", assertThrows(ThreadDumpException.class, () -> svc.load("../x.hprof")).code());
        assertThrows(IllegalArgumentException.class, () -> svc.load(""));
        assertEquals("BAD_REQUEST", svc.summary("").get("code"));
    }

    @Test
    void 내보내기_원본은_화면_분석과_같은_변환이다() throws Exception {
        placeAll();
        HeapThreadService.ExportSource src = svc.exportSource("app.hprof");
        assertEquals("app.hprof", src.heapFilename());
        assertEquals("app", src.baseName());
        assertTrue(src.text().contains(com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor.VM_LINE));
        assertTrue(src.text().startsWith("2026-11-26 10:00:00\n"), "덤프 시각 줄이 맨 앞에 있어야 스레드 덤프 분석이 시각을 안다");
        assertEquals(svc.load("app.hprof").result().getSnapshots().get(0).getThreads(), src.threads());
        entity.setStatus("ANALYZING");
        assertEquals("HEAP_NOT_ANALYZED", assertThrows(ThreadDumpException.class, () -> svc.exportSource("app.hprof")).code());
    }

    @Test
    void 스택만_있어도_분석한다() throws Exception {
        Files.write(new File(resultDir, "app.threads").toPath(), fixture("synthetic.threads"));
        HeapThreadService.Loaded l = svc.load("app.hprof");
        assertTrue(l.result().getSnapshots().get(0).getThreads() > 0);
    }
}

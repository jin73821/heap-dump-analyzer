package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.service.ComparisonHistoryService;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.CoreDumpPdfReportService;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.HeapHistoryAggregator;
import com.heapdump.analyzer.service.JvmHeapInfoService;
import com.heapdump.analyzer.service.LlmConfigService;
import com.heapdump.analyzer.service.PdfReportService;
import com.heapdump.analyzer.service.RagConfigService;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 힙·코어·GC 로그 원본 다운로드와 결과 화면 리다이렉트의 헤더 계약 (2026-10-29, 함정 71, standalone MockMvc).
 *
 * <p>고정하는 계약: ① 세 저장소의 다운로드가 한글 파일명에서도 <b>ISO-8859-1 로 쓸 수 있는</b>
 * Content-Disposition 을 주고 filename* 가 원래 이름으로 돌아온다(사내망 제보 파일명 그대로)
 * ② {@code Range: bytes=0-0} 에 206·1바이트·같은 Content-Disposition — 화면의 {@code Common.downloadFile} 이
 * 이 응답 하나로 세션·삭제·장애를 판정하므로 깨지면 모든 다운로드 버튼이 멈춘다
 * ③ 한글 이름의 결과 화면 리다이렉트(코어·힙)가 인코딩된 경로다(인코딩하지 않으면 Location 없는 302).
 */
class DownloadHeaderEndpointTest {

    static final String XLSX = "nodejs 사용여부 전수조사_v1.0.xlsx";
    static final String CORE = "코어 덤프_운영1.core";
    static final String GCLOG = "운영서버_gc.log.1";
    static final byte[] BODY = "0123456789".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;
    private boolean allowAllBefore;

    @BeforeEach
    void rememberToggle() { allowAllBefore = FilenameValidator.isAllowAllExtensions(); }

    @AfterEach
    void restoreToggle() { FilenameValidator.setAllowAllExtensions(allowAllBefore); }

    private File file(String name) throws Exception {
        return Files.write(dir.resolve(name), BODY).toFile();
    }

    /** 다운로드 응답 공통 단언 — 전체 GET 과 Range 사전 확인 둘 다 */
    private static void assertDownloadContract(MockMvc mvc, String url, String name) throws Exception {
        MvcResult full = mvc.perform(get(url, name)).andExpect(status().isOk()).andReturn();
        String cd = full.getResponse().getHeader("Content-Disposition");
        assertTrue(cd != null && StandardCharsets.ISO_8859_1.newEncoder().canEncode(cd), "Tomcat 이 쓸 수 있어야 한다: " + cd);
        assertEquals(HttpHeaderValues.attachment(name), cd);
        assertEquals(BODY.length, full.getResponse().getContentAsByteArray().length);

        MvcResult probe = mvc.perform(get(url, name).header("Range", "bytes=0-0"))
                .andExpect(status().isPartialContent()).andReturn();
        assertEquals(cd, probe.getResponse().getHeader("Content-Disposition"), "사전 확인도 같은 파일명");
        assertEquals(1, probe.getResponse().getContentAsByteArray().length, "사전 확인은 1바이트만");
    }

    @Test
    @DisplayName("힙 저장소 /download — 제보 파일명(.xlsx, 모든 확장자 허용 운영 설정)")
    void heapDownload() throws Exception {
        FilenameValidator.setAllowAllExtensions(true);
        HeapDumpAnalyzerService heap = Mockito.mock(HeapDumpAnalyzerService.class);
        File f = file(XLSX);
        Mockito.when(heap.getFile(XLSX)).thenReturn(f);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HeapFileApiController(heap, null, null)).build();
        assertDownloadContract(mvc, "/download/{fn}", XLSX);
    }

    @Test
    @DisplayName("코어 덤프 저장소 /api/core-dump/download")
    void coreDownload() throws Exception {
        CoreDumpAnalyzerService core = Mockito.mock(CoreDumpAnalyzerService.class);
        Mockito.when(core.validateCoreDumpFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(core.dumpFilesDir()).thenReturn(dir.toFile());
        file(CORE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CoreDumpApiController(core, null, null, null)).build();
        assertDownloadContract(mvc, "/api/core-dump/download/{fn}", CORE);
    }

    @Test
    @DisplayName("GC 로그 저장소 /api/gc-log/download")
    void gcLogDownload() throws Exception {
        GcLogAnalyzerService gc = Mockito.mock(GcLogAnalyzerService.class);
        Mockito.when(gc.validateGcLogFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        File f = file(GCLOG);
        Mockito.when(gc.fileOf(GCLOG)).thenReturn(f);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new GcLogApiController(gc, null, null, null, null)).build();
        assertDownloadContract(mvc, "/api/gc-log/download/{fn}", GCLOG);
    }

    @Test
    @DisplayName("코어 결과 리다이렉트 — 이미 분석된 코어의 진행 화면 → 결과 화면, 경로가 인코딩된다")
    void coreRedirectIsEncoded() throws Exception {
        CoreDumpAnalyzerService core = Mockito.mock(CoreDumpAnalyzerService.class);
        Mockito.when(core.validateCoreDumpFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        CoreDumpAnalysisResult done = new CoreDumpAnalysisResult();
        done.setFilename(CORE);
        done.setCrashSignal("SIGSEGV");
        Mockito.when(core.loadResult(CORE)).thenReturn(Optional.of(done));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new CoreDumpViewController(core, Mockito.mock(CoreDumpPdfReportService.class))).build();

        String loc = mvc.perform(get("/core-dump/progress/{fn}", CORE))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertRedirect("/core-dump/analyze/", CORE, loc);
    }

    @Test
    @DisplayName("힙 결과 리다이렉트 — 캐시 적중한 진행 화면 → 결과 화면, 경로가 인코딩된다(# 도 잘리지 않는다)")
    void heapRedirectIsEncoded() throws Exception {
        String name = "운영서버 덤프#1.hprof";
        HeapDumpAnalyzerService heap = Mockito.mock(HeapDumpAnalyzerService.class);
        HeapAnalysisResult ok = new HeapAnalysisResult();
        ok.setAnalysisStatus(HeapAnalysisResult.AnalysisStatus.SUCCESS);
        Mockito.when(heap.getCachedResult(name)).thenReturn(ok);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HeapDumpViewController(heap,
                Mockito.mock(LlmConfigService.class), Mockito.mock(HeapDumpConfig.class),
                Mockito.mock(PdfReportService.class), Mockito.mock(HeapHistoryAggregator.class),
                Mockito.mock(ComparisonHistoryService.class), Mockito.mock(CoreDumpAnalyzerService.class),
                Mockito.mock(RagConfigService.class), Mockito.mock(JvmHeapInfoService.class))).build();

        String loc = mvc.perform(get("/analyze/{fn}", name))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertRedirect("/analyze/result/", name, loc);
    }

    private static void assertRedirect(String prefix, String name, String loc) {
        assertTrue(loc != null && StandardCharsets.US_ASCII.newEncoder().canEncode(loc), "Location 은 ASCII: " + loc);
        assertEquals(prefix + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8), loc);
        assertEquals(name, UriUtils.decode(loc.substring(prefix.length()), StandardCharsets.UTF_8));
    }
}

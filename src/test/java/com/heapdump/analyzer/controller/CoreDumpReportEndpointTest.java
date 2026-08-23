package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.CoreDumpPdfReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 코어덤프 PDF 리포트 엔드포인트 라우팅/헤더 검증 (2026-08-23, standalone MockMvc — Spring 컨텍스트 없음).
 *
 * <p>{@code /core-dump/analyze/{filename:.+}} (결과 페이지)와
 * {@code /core-dump/analyze/{filename:.+}/print-pdf|print-html} 가 공존하는 경로 매핑,
 * mode/rev 파라미터 분기, Content-Disposition 파일명 규칙(.core 접미사만 제거 + -crash-report.pdf),
 * GDB 인식 실패 404/리다이렉트를 확인한다. PDF 실바이트는 CoreDumpPrintTemplateSmokeTest 가 커버.
 */
class CoreDumpReportEndpointTest {

    private CoreDumpAnalyzerService analyzer;
    private CoreDumpPdfReportService pdf;
    private MockMvc apiMvc;
    private MockMvc viewMvc;

    private static final byte[] FAKE_PDF = "%PDF-fake".getBytes();

    @BeforeEach
    void setUp() throws Exception {
        analyzer = Mockito.mock(CoreDumpAnalyzerService.class);
        pdf = Mockito.mock(CoreDumpPdfReportService.class);
        // validate 는 입력 그대로 통과 (검증 로직 자체는 CoreDumpAnalyzerService 테스트 소관)
        Mockito.when(analyzer.validateCoreDumpFilename(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(pdf.renderCorePdf(anyString(), any(), any())).thenReturn(FAKE_PDF);
        Mockito.when(pdf.buildCorePrintModel(anyString(), any(), any()))
                .thenReturn(new LinkedHashMap<>(Map.of("filename", "core.12345")));

        apiMvc = MockMvcBuilders.standaloneSetup(
                new CoreDumpApiController(analyzer, null, pdf, null)).build();
        viewMvc = MockMvcBuilders.standaloneSetup(
                new CoreDumpViewController(analyzer, pdf)).build();
    }

    private static CoreDumpAnalysisResult okResult() {
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setFilename("core.12345");
        r.setCrashSignal("SIGSEGV");
        return r;
    }

    private static CoreDumpAnalysisResult gdbFailResult() {
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setFilename("bad.dmp");
        r.setErrorMessage("파일 형식을 인식할 수 없습니다.");
        return r;
    }

    @Test
    @DisplayName("print-pdf 기본(mode=download) — 200 PDF + attachment + .core 접미사 제거 파일명")
    void printPdfDownload() throws Exception {
        Mockito.when(analyzer.loadResult("demo.core")).thenReturn(Optional.of(okResult()));

        apiMvc.perform(get("/core-dump/analyze/demo.core/print-pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"demo-crash-report.pdf\"")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(FAKE_PDF));
    }

    @Test
    @DisplayName("print-pdf mode=inline — inline disposition (리포트 탭 iframe 미리보기)")
    void printPdfInline() throws Exception {
        Mockito.when(analyzer.loadResult("core.12345")).thenReturn(Optional.of(okResult()));

        apiMvc.perform(get("/core-dump/analyze/core.12345/print-pdf").param("mode", "inline"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("inline; filename=\"core.12345-crash-report.pdf\"")));
    }

    @Test
    @DisplayName("print-pdf ?rev= — 리비전 로드 경로 + 파일명에 -{rev} 삽입, blank rev 는 현재 결과")
    void printPdfRevision() throws Exception {
        Mockito.when(analyzer.loadRevisionResult("core.12345", "20260801-120000"))
                .thenReturn(Optional.of(okResult()));

        apiMvc.perform(get("/core-dump/analyze/core.12345/print-pdf").param("rev", "20260801-120000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("core.12345-20260801-120000-crash-report.pdf")));
        Mockito.verify(pdf).renderCorePdf(eq("core.12345"), eq("20260801-120000"), any());

        // blank rev 는 현재 결과 (다운로드 앵커가 rev= 빈 값으로 직렬화하는 경우)
        Mockito.when(analyzer.loadResult("core.12345")).thenReturn(Optional.of(okResult()));
        apiMvc.perform(get("/core-dump/analyze/core.12345/print-pdf").param("rev", ""))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("\"core.12345-crash-report.pdf\"")));
    }

    @Test
    @DisplayName("print-pdf — 결과 없음/GDB 인식 실패(시그널 없음+오류)는 404")
    void printPdfNotReportable() throws Exception {
        Mockito.when(analyzer.loadResult("none.core")).thenReturn(Optional.empty());
        apiMvc.perform(get("/core-dump/analyze/none.core/print-pdf"))
                .andExpect(status().isNotFound());

        Mockito.when(analyzer.loadResult("bad.dmp")).thenReturn(Optional.of(gdbFailResult()));
        apiMvc.perform(get("/core-dump/analyze/bad.dmp/print-pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("print-pdf — 파일명 검증 실패는 400")
    void printPdfInvalidFilename() throws Exception {
        Mockito.when(analyzer.validateCoreDumpFilename("evil"))
                .thenThrow(new IllegalArgumentException("잘못된 파일명"));
        apiMvc.perform(get("/core-dump/analyze/evil/print-pdf"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("print-html — 같은 모델로 core-dump/analyze-print 뷰 렌더, 리포트 불가면 결과 페이지 리다이렉트")
    void printHtml() throws Exception {
        Mockito.when(analyzer.loadResult("demo.core")).thenReturn(Optional.of(okResult()));
        viewMvc.perform(get("/core-dump/analyze/demo.core/print-html"))
                .andExpect(status().isOk())
                .andExpect(view().name("core-dump/analyze-print"))
                .andExpect(model().attribute("filename", "core.12345"));

        Mockito.when(analyzer.loadResult("bad.dmp")).thenReturn(Optional.of(gdbFailResult()));
        viewMvc.perform(get("/core-dump/analyze/bad.dmp/print-html"))
                .andExpect(redirectedUrl("/core-dump/analyze/bad.dmp"));
    }

    @Test
    @DisplayName("경로 공존 — /analyze/{fn} 은 결과 페이지, /analyze/{fn}/print-html 은 인쇄 뷰로 각각 매핑")
    void analyzeAndPrintCoexist() throws Exception {
        Mockito.when(analyzer.loadResult("demo.core")).thenReturn(Optional.of(okResult()));
        Mockito.when(analyzer.getEntity("demo.core")).thenReturn(Optional.empty());
        Mockito.when(analyzer.listRevisions("demo.core")).thenReturn(java.util.List.of());

        viewMvc.perform(get("/core-dump/analyze/demo.core"))
                .andExpect(status().isOk())
                .andExpect(view().name("core-dump/analyze"));
        viewMvc.perform(get("/core-dump/analyze/demo.core/print-html"))
                .andExpect(view().name("core-dump/analyze-print"));
    }
}

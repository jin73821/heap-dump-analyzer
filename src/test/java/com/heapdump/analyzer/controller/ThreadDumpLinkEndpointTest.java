package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpException;
import com.heapdump.analyzer.service.HeapThreadService;
import com.heapdump.analyzer.service.ThreadDumpAnalyzerService;
import com.heapdump.analyzer.service.ThreadDumpPdfReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2.7.1 API 계약 — 이어 붙이기·묶음 해제·제안, 힙 덤프 수동 연결, 힙 덤프의 스레드 분석(결과·요약·PDF).
 * 서비스는 mock 이다(실행 경로는 ThreadDumpCombineLinkServiceTest · HeapThreadServiceTest).
 */
class ThreadDumpLinkEndpointTest {

    private ThreadDumpAnalyzerService service;
    private ThreadDumpPdfReportService pdf;
    private HeapThreadService heap;
    private ThreadDumpApiController controller;
    private MockMvc mvc;
    private final Principal user = () -> "kim";

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ThreadDumpAnalyzerService.class);
        pdf = Mockito.mock(ThreadDumpPdfReportService.class);
        heap = Mockito.mock(HeapThreadService.class);
        Mockito.when(service.validateFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(heap.validateFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        controller = new ThreadDumpApiController(service, pdf);
        controller.heapThreads = heap;
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── 이어 붙이기 ──────────────────────────────────────────────

    @Test
    @DisplayName("이어 붙이기 — 이름 목록과 수행자를 서비스에 넘기고 대표를 돌려준다")
    void combineOk() throws Exception {
        Mockito.when(service.combine(eq(List.of("b.txt", "a.txt")), eq("kim")))
                .thenReturn(new LinkedHashMap<>(Map.of("representative", "a.txt", "members", List.of("a.txt", "b.txt"), "order", "timestamp")));
        mvc.perform(post("/api/thread-dump/combine").principal(user).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"b.txt\",\"a.txt\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.representative").value("a.txt"));
    }

    @Test
    @DisplayName("이어 붙이기 — 검증 실패 400, 분석 중 409, filenames 없음 400")
    void combineErrors() throws Exception {
        Mockito.when(service.combine(eq(List.of("a.txt")), anyString())).thenThrow(new IllegalArgumentException("2개 이상 고르세요"));
        mvc.perform(post("/api/thread-dump/combine").principal(user).contentType(MediaType.APPLICATION_JSON).content("{\"filenames\":[\"a.txt\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        Mockito.when(service.combine(eq(List.of("a.txt", "b.txt")), anyString())).thenThrow(new ThreadDumpAnalyzerService.BusyException("분석이 진행 중"));
        mvc.perform(post("/api/thread-dump/combine").principal(user).contentType(MediaType.APPLICATION_JSON).content("{\"filenames\":[\"a.txt\",\"b.txt\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(post("/api/thread-dump/combine").principal(user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("미리보기·제안·묶음 해제")
    void previewSuggestUngroup() throws Exception {
        Mockito.when(service.combinePreview(any())).thenReturn(Map.of("order", "given", "representative", "a.txt"));
        mvc.perform(post("/api/thread-dump/combine/preview").principal(user).contentType(MediaType.APPLICATION_JSON).content("{\"filenames\":[\"a.txt\",\"b.txt\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.order").value("given"));
        Mockito.when(service.groupSuggestion(any())).thenReturn(List.of(Map.of("files", List.of("a.txt", "b.txt"))));
        mvc.perform(post("/api/thread-dump/group-suggestion").principal(user).contentType(MediaType.APPLICATION_JSON).content("{\"filenames\":[\"a.txt\",\"b.txt\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups[0].files[1]").value("b.txt"));
        mvc.perform(post("/api/thread-dump/a.txt/ungroup").principal(user)).andExpect(status().isOk());
        Mockito.verify(service).ungroup("a.txt", "kim");
    }

    // ── 힙 덤프 연결 ─────────────────────────────────────────────

    @Test
    @DisplayName("힙 덤프 연결·해제 — 수행자를 넘기고 연결 뷰를 돌려준다")
    void heapLink() throws Exception {
        Mockito.when(service.heapLinkView("t.txt")).thenReturn(new LinkedHashMap<>(Map.of("linked", true, "heapFilename", "app.hprof")));
        mvc.perform(post("/api/thread-dump/t.txt/heap-link").principal(user).contentType(MediaType.APPLICATION_JSON).content("{\"heapFilename\":\"app.hprof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heapFilename").value("app.hprof"));
        Mockito.verify(service).linkHeap("t.txt", "app.hprof", "kim");
        mvc.perform(post("/api/thread-dump/t.txt/heap-link").principal(user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        Mockito.verify(service).linkHeap(eq("t.txt"), isNull(), eq("kim"));
        Mockito.when(service.heapCandidates("t.txt")).thenReturn(List.of(Map.of("filename", "app.hprof", "score", 2)));
        Mockito.when(service.heapLinkAvailable()).thenReturn(true);
        mvc.perform(get("/api/thread-dump/t.txt/heap-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].score").value(2));
    }

    // ── 힙 덤프의 스레드 분석 ────────────────────────────────────

    private HeapThreadService.Loaded loaded() {
        AnalysisHistoryEntity he = new AnalysisHistoryEntity();
        he.setFilename("app.hprof");
        ThreadDumpResult r = new ThreadDumpResult();
        return new HeapThreadService.Loaded("app.hprof", r, "{\"schema\":1,\"meta\":{\"source\":\"heap\"}}", he, Map.of(), 0, "s");
    }

    @Test
    @DisplayName("힙 추출 결과 JSON · 요약(연결된 스레드 덤프 포함)")
    void heapResultSummary() throws Exception {
        Mockito.when(heap.load("app.hprof")).thenReturn(loaded());
        mvc.perform(get("/api/thread-dump/heap/app.hprof/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.source").value("heap"));
        Mockito.when(heap.summary("app.hprof")).thenReturn(new LinkedHashMap<>(Map.of("available", true, "threads", 12)));
        Mockito.when(service.linkedToHeap("app.hprof")).thenReturn(List.of(Map.of("filename", "t.txt")));
        mvc.perform(get("/api/thread-dump/heap/app.hprof/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threads").value(12))
                .andExpect(jsonPath("$.linkedThreadDumps[0].filename").value("t.txt"));
    }

    @Test
    @DisplayName("힙 추출 실패 — 코드별 HTTP 상태와 {success:false, code, error}")
    void heapResultErrors() throws Exception {
        Mockito.when(heap.load("none.hprof")).thenThrow(new ThreadDumpException("HEAP_THREADS_UNAVAILABLE", "스레드 정보가 없습니다"));
        mvc.perform(get("/api/thread-dump/heap/none.hprof/result"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("HEAP_THREADS_UNAVAILABLE"));
        Mockito.when(heap.load("run.hprof")).thenThrow(new ThreadDumpException("HEAP_NOT_ANALYZED", "분석 중"));
        mvc.perform(get("/api/thread-dump/heap/run.hprof/result")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("힙 추출 PDF — application/pdf · 파일명은 HttpHeaderValues · 수행자 로그 경로")
    void heapPdf() throws Exception {
        Mockito.when(heap.load("app.hprof")).thenReturn(loaded());
        Mockito.when(pdf.renderPdf(eq("app.hprof"), isNull(), any(), any(), isNull())).thenReturn("%PDF-1.4 x".getBytes());
        mvc.perform(get("/thread-dump/heap/app.hprof/print-pdf").principal(user))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("application/pdf")))
                .andExpect(header().string("Content-Disposition", containsString("app-thread-report.pdf")));
        Mockito.when(heap.load("none.hprof")).thenThrow(new ThreadDumpException("HEAP_THREADS_UNAVAILABLE", "x"));
        mvc.perform(get("/thread-dump/heap/none.hprof/print-pdf").principal(user)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("힙 추출 AI — LLM 꺼져 있으면 LLM_DISABLED, 성공하면 __heapthreads__ 키로 저장")
    void heapAi() throws Exception {
        Mockito.when(heap.isLlmEnabled()).thenReturn(false);
        mvc.perform(post("/api/thread-dump/heap/app.hprof/ai-analyze").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value("LLM_DISABLED"));
        Mockito.when(heap.isLlmEnabled()).thenReturn(true);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("model", "m");
        ok.put("data", new LinkedHashMap<>(Map.of("summary", "s")));
        Mockito.when(heap.analyzeWithAi("app.hprof")).thenReturn(ok);
        mvc.perform(post("/api/thread-dump/heap/app.hprof/ai-analyze").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
        Mockito.verify(heap).saveAiInsight(eq("__heapthreads__:app.hprof"), any());
    }

    @Test
    @DisplayName("스레드 덤프 파일로 내보내기 — 성공은 파일명·목록 링크, 이미 있으면 409 EXPORT_EXISTS + 이름, newName 전달, 실패 코드 유지")
    void heapExport() throws Exception {
        Mockito.when(service.exportFromHeap("app 1.hprof", false, "kim"))
                .thenReturn(new ThreadDumpAnalyzerService.HeapExport("app 1_threads.txt", "app 1.hprof", 160, 52_000L, "SUCCESS", null));
        mvc.perform(post("/api/thread-dump/heap/app 1.hprof/export").principal(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.filename").value("app 1_threads.txt"))
                .andExpect(jsonPath("$.threads").value(160))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.analysisError").doesNotExist())
                .andExpect(jsonPath("$.listUrl").value("/thread-dump?file=app%201_threads.txt"))
                .andExpect(jsonPath("$.analyzeUrl").value("/thread-dump/analyze/app%201_threads.txt"));

        Mockito.when(service.exportFromHeap("b.hprof", false, "kim")).thenThrow(new ThreadDumpAnalyzerService.ExportExistsException("b_threads.txt"));
        mvc.perform(post("/api/thread-dump/heap/b.hprof/export").principal(user))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("EXPORT_EXISTS"))
                .andExpect(jsonPath("$.filename").value("b_threads.txt"));

        Mockito.when(service.exportFromHeap("b.hprof", true, "kim"))
                .thenReturn(new ThreadDumpAnalyzerService.HeapExport("b_threads-2.txt", "b.hprof", 3, 100L, "ERROR", "NOT_THREAD_DUMP: x"));
        mvc.perform(post("/api/thread-dump/heap/b.hprof/export").param("newName", "true").principal(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("b_threads-2.txt"))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.analysisError").value("NOT_THREAD_DUMP: x"));

        Mockito.when(service.exportFromHeap("c.hprof", false, "kim")).thenThrow(new ThreadDumpException("HEAP_NOT_ANALYZED", "분석이 끝나지 않았습니다"));
        mvc.perform(post("/api/thread-dump/heap/c.hprof/export").principal(user))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEAP_NOT_ANALYZED"));
        Mockito.when(service.exportFromHeap("d.hprof", false, "kim")).thenThrow(new ThreadDumpException("HEAP_THREADS_UNAVAILABLE", "산출물 없음"));
        mvc.perform(post("/api/thread-dump/heap/d.hprof/export").principal(user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HEAP_THREADS_UNAVAILABLE"));
    }

    @Test
    @DisplayName("힙 스레드 서비스가 없으면 힙 경로는 404 코드로 답한다")
    void heapUnavailable() throws Exception {
        controller.heapThreads = null;
        mvc.perform(get("/api/thread-dump/heap/app.hprof/result"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("HEAP_THREADS_UNAVAILABLE")));
    }
}

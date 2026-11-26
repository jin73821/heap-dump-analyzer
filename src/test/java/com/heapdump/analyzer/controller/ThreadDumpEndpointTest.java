package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.service.PdfReportService;
import com.heapdump.analyzer.service.ThreadDumpAnalyzerService;
import com.heapdump.analyzer.service.ThreadDumpPdfReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스레드 덤프 API 계약(2026-11-26) — 오류 본문 {@code {success:false, code, error}}, 상태 코드, 다운로드·PDF 헤더, 수행자 전달.
 * 서비스는 mock 이다(실행 경로는 {@code ThreadDumpAnalyzerServiceTest}).
 */
class ThreadDumpEndpointTest {

    @TempDir Path dir;
    private ThreadDumpAnalyzerService service;
    private ThreadDumpPdfReportService pdf;
    private MockMvc mvc;
    private final Principal user = () -> "kim";

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ThreadDumpAnalyzerService.class);
        pdf = Mockito.mock(ThreadDumpPdfReportService.class);
        Mockito.when(service.validateFilename(anyString())).thenAnswer(inv -> {
            String n = inv.getArgument(0);
            if (n.contains("..")) throw new IllegalArgumentException("Invalid filename: path traversal detected");
            return n;
        });
        Mockito.when(service.fileOf(anyString())).thenAnswer(inv -> new File(dir.toFile(), inv.getArgument(0)));
        mvc = MockMvcBuilders.standaloneSetup(new ThreadDumpApiController(service, pdf)).build();
    }

    private static ThreadDumpAnalysisEntity entity(String name, String status) {
        ThreadDumpAnalysisEntity e = new ThreadDumpAnalysisEntity();
        e.setFilename(name);
        e.setStatus(status);
        e.setFileSize(10L);
        return e;
    }

    // ── 업로드 ──────────────────────────────────────────────────

    @Test
    @DisplayName("업로드 성공 — 파일명·크기와 수행자를 서비스에 넘긴다")
    void uploadOk() throws Exception {
        Mockito.when(service.storeUpload(eq("jstack.txt"), anyLong(), any(), eq("kim"), eq("was-01"))).thenReturn(entity("jstack.txt", "NOT_ANALYZED"));
        mvc.perform(multipart("/api/thread-dump/upload").file(new MockMultipartFile("threadDumpFile", "jstack.txt", "text/plain", "x".getBytes()))
                        .param("serverName", "was-01").principal(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.filename").value("jstack.txt"));
    }

    @Test
    @DisplayName("업로드 거부 — 서비스가 정한 코드와 HTTP 상태를 그대로 내린다")
    void uploadRejected() throws Exception {
        Mockito.when(service.storeUpload(anyString(), anyLong(), any(), anyString(), any()))
                .thenThrow(new ThreadDumpAnalyzerService.UploadRejectedException("NOT_THREAD_DUMP", 400, "스레드 덤프 형식을 인식하지 못했습니다."));
        mvc.perform(multipart("/api/thread-dump/upload").file(new MockMultipartFile("threadDumpFile", "a.txt", "text/plain", "x".getBytes())).principal(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_THREAD_DUMP"))
                .andExpect(jsonPath("$.error").value(containsString("인식하지 못했습니다")));

        // 이미 던지도록 설정된 mock 은 when(...) 안에서 호출하면 그 예외가 난다 — doThrow 로 다시 건다
        Mockito.doThrow(new ThreadDumpAnalyzerService.UploadRejectedException("DUPLICATE_NAME", 409, "같은 이름"))
                .when(service).storeUpload(anyString(), anyLong(), any(), anyString(), any());
        mvc.perform(multipart("/api/thread-dump/upload").file(new MockMultipartFile("threadDumpFile", "a.txt", "text/plain", "x".getBytes())).principal(user))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_NAME"));

        Mockito.doThrow(new ThreadDumpAnalyzerService.UploadRejectedException("TOO_LARGE", 413, "상한 초과"))
                .when(service).storeUpload(anyString(), anyLong(), any(), anyString(), any());
        mvc.perform(multipart("/api/thread-dump/upload").file(new MockMultipartFile("threadDumpFile", "a.txt", "text/plain", "x".getBytes())).principal(user))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("TOO_LARGE"));
    }

    @Test
    @DisplayName("업로드 — 빈 파일 400 EMPTY, 파일 파라미터 누락은 400(500 이 아니다), 디스크 오류는 500 IO_ERROR")
    void uploadEdgeCases() throws Exception {
        mvc.perform(multipart("/api/thread-dump/upload").file(new MockMultipartFile("threadDumpFile", "a.txt", "text/plain", new byte[0])).principal(user))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY"));
        mvc.perform(multipart("/api/thread-dump/upload").principal(user))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        Mockito.when(service.storeUpload(anyString(), anyLong(), any(), anyString(), any())).thenThrow(new IOException("No space left on device"));
        mvc.perform(multipart("/api/thread-dump/upload").file(new MockMultipartFile("threadDumpFile", "a.txt", "text/plain", "x".getBytes())).principal(user))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("IO_ERROR"))
                .andExpect(jsonPath("$.error").value(containsString("No space left")));
    }

    // ── 분석 ────────────────────────────────────────────────────

    @Test
    @DisplayName("분석 시작 — 파일 없음 404, 이미 완료면 다시 돌리지 않음, 아니면 제출(수행자 전달)")
    void analyze() throws Exception {
        mvc.perform(post("/api/thread-dump/analyze/none.txt").principal(user))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Files.writeString(dir.resolve("a.txt"), "x");
        Mockito.when(service.find("a.txt")).thenReturn(Optional.of(entity("a.txt", "SUCCESS")));
        mvc.perform(post("/api/thread-dump/analyze/a.txt").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        Mockito.verify(service, Mockito.never()).submitAnalysis(anyString(), anyString());

        Mockito.when(service.find("a.txt")).thenReturn(Optional.of(entity("a.txt", "ERROR")));
        mvc.perform(post("/api/thread-dump/analyze/a.txt").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYZING"));
        Mockito.verify(service).submitAnalysis("a.txt", "kim");
    }

    @Test
    @DisplayName("재분석 — 진행 중이면 409 ANALYZING, 취소는 결과를 그대로 알린다")
    void reanalyzeAndCancel() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "x");
        Mockito.when(service.isAnalyzing("a.txt")).thenReturn(true);
        mvc.perform(post("/api/thread-dump/reanalyze/a.txt").principal(user))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANALYZING"));
        Mockito.when(service.isAnalyzing("a.txt")).thenReturn(false);
        mvc.perform(post("/api/thread-dump/reanalyze/a.txt").principal(user)).andExpect(status().isOk());
        Mockito.verify(service).submitAnalysis("a.txt", "kim");

        Mockito.when(service.cancel("a.txt", "kim")).thenReturn(true);
        mvc.perform(post("/api/thread-dump/cancel/a.txt").principal(user)).andExpect(jsonPath("$.cancelled").value(true));
    }

    @Test
    @DisplayName("상태·결과 — 결과 JSON 은 저장본 그대로, 없으면 404 NO_RESULT")
    void statusAndResult() throws Exception {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("status", "ERROR");
        st.put("errorCode", "TIMEOUT");
        st.put("error", "TIMEOUT: 분석 시간 상한(10분)을 넘었습니다.");
        Mockito.when(service.getStatus("a.txt")).thenReturn(st);
        mvc.perform(get("/api/thread-dump/a.txt/status"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.errorCode").value("TIMEOUT"));

        Mockito.when(service.loadResultJson("a.txt")).thenReturn(Optional.of("{\"schema\":1,\"meta\":{\"lines\":3}}"));
        mvc.perform(get("/api/thread-dump/a.txt/result"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"schema\":1,\"meta\":{\"lines\":3}}"));
        Mockito.when(service.loadResultJson("b.txt")).thenReturn(Optional.empty());
        mvc.perform(get("/api/thread-dump/b.txt/result")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_RESULT"));
    }

    @Test
    @DisplayName("잘못된 파일명은 400 BAD_REQUEST, 예상 못 한 예외는 500 INTERNAL(내부 메시지를 흘리지 않는다)")
    void errorContract() throws Exception {
        mvc.perform(get("/api/thread-dump/a..b/status")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        Mockito.when(service.getStatus("boom.txt")).thenThrow(new IllegalStateException("secret internal detail"));
        mvc.perform(get("/api/thread-dump/boom.txt/status")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret internal detail"))));
    }

    @Test
    @DisplayName("DB 장애는 전역 계약(503 + Retry-After + DB_UNAVAILABLE)을 지킨다")
    void dbOutage() throws Exception {
        Mockito.when(service.getStatus("a.txt")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("pool",
                new java.sql.SQLTransientConnectionException("Connection is not available, request timed out after 5000ms", "08001")));
        mvc.perform(get("/api/thread-dump/a.txt/status")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }

    // ── 파일 ────────────────────────────────────────────────────

    @Test
    @DisplayName("다운로드 — 한글 파일명 헤더(함정 71), 없으면 404")
    void download() throws Exception {
        Files.writeString(dir.resolve("스레드 덤프.txt"), "hello");
        mvc.perform(get("/api/thread-dump/download/스레드 덤프.txt").principal(user))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"")))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''%EC%8A%A4%EB%A0%88%EB%93%9C%20%EB%8D%A4%ED%94%84.txt")))
                .andExpect(content().string("hello"));
        mvc.perform(get("/api/thread-dump/download/none.txt").principal(user)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("삭제·서버 이름 — 수행자를 넘기고, 기록이 없으면 404")
    void deleteAndServerName() throws Exception {
        mvc.perform(delete("/api/thread-dump/a.txt").param("deleteFile", "false").principal(user)).andExpect(jsonPath("$.success").value(true));
        Mockito.verify(service).delete("a.txt", false, "kim");
        mvc.perform(delete("/api/thread-dump/a.txt").principal(user)).andExpect(status().isOk());
        Mockito.verify(service).delete("a.txt", true, "kim");

        Mockito.when(service.find("a.txt")).thenReturn(Optional.empty());
        mvc.perform(post("/api/thread-dump/a.txt/server-name").contentType(MediaType.APPLICATION_JSON).content("{\"serverName\":\"w1\"}").principal(user))
                .andExpect(status().isNotFound());
        ThreadDumpAnalysisEntity e = entity("a.txt", "SUCCESS");
        Mockito.when(service.find("a.txt")).thenReturn(Optional.of(e));
        Mockito.when(service.updateServerName(e, "w1")).thenAnswer(inv -> { e.setServerName("w1"); return e; });
        mvc.perform(post("/api/thread-dump/a.txt/server-name").contentType(MediaType.APPLICATION_JSON).content("{\"serverName\":\"w1\"}").principal(user))
                .andExpect(jsonPath("$.serverName").value("w1"));
    }

    // ── AI ──────────────────────────────────────────────────────

    @Test
    @DisplayName("AI — 꺼져 있으면 LLM_DISABLED, 성공하면 저장, 저장 실패해도 결과는 돌려준다, 호출량 제한은 429")
    void ai() throws Exception {
        Mockito.when(service.isLlmEnabled()).thenReturn(false);
        mvc.perform(post("/api/thread-dump/a.txt/ai-analyze").principal(user)).andExpect(jsonPath("$.errorCode").value("LLM_DISABLED"));
        Mockito.verify(service, Mockito.never()).analyzeWithAi(anyString());

        Mockito.when(service.isLlmEnabled()).thenReturn(true);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("model", "m1");
        ok.put("data", new LinkedHashMap<>(Map.of("severity", "High", "summary", "요약")));
        Mockito.when(service.analyzeWithAi("a.txt")).thenReturn(ok);
        mvc.perform(post("/api/thread-dump/a.txt/ai-analyze").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.data.severity").value("High"));
        Mockito.verify(service).saveAiInsight(eq("__threaddump__:a.txt"), any());

        Map<String, Object> ok2 = new LinkedHashMap<>(ok);
        Mockito.when(service.analyzeWithAi("b.txt")).thenReturn(ok2);
        Mockito.doThrow(new RuntimeException("db down")).when(service).saveAiInsight(eq("__threaddump__:b.txt"), any());
        mvc.perform(post("/api/thread-dump/b.txt/ai-analyze").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.saved").value(false)).andExpect(jsonPath("$.saveErrorCode").value("SAVE_FAILED"))
                .andExpect(jsonPath("$.retryPayload.summary").value("요약"));

        Mockito.when(service.analyzeWithAi("c.txt")).thenThrow(new IllegalStateException("llm client broke"));
        mvc.perform(post("/api/thread-dump/c.txt/ai-analyze").principal(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.errorCode").value("AI_ERROR"));

        Mockito.when(service.loadAiInsight("__threaddump__:a.txt")).thenReturn(null);
        mvc.perform(get("/api/thread-dump/a.txt/ai-insight")).andExpect(jsonPath("$.found").value(false));
        Mockito.when(service.deleteAiInsight("__threaddump__:a.txt")).thenReturn(true);
        mvc.perform(delete("/api/thread-dump/a.txt/ai-insight").principal(user)).andExpect(jsonPath("$.deleted").value(true));
    }

    // ── PDF ─────────────────────────────────────────────────────

    @Test
    @DisplayName("PDF — 결과 없음 404, 잘못된 이름 400, 렌더 오류 500, 성공 시 한글 파일명·no-store·inline/attachment")
    void printPdf() throws Exception {
        Mockito.when(service.loadResult("none.txt")).thenReturn(Optional.empty());
        mvc.perform(get("/thread-dump/analyze/none.txt/print-pdf").principal(user)).andExpect(status().isNotFound());
        mvc.perform(get("/thread-dump/analyze/a..b/print-pdf").principal(user)).andExpect(status().isBadRequest());

        ThreadDumpResult r = new ThreadDumpResult();
        Mockito.when(service.loadResult(anyString())).thenReturn(Optional.of(r));
        Mockito.when(service.loadAiInsight(anyString())).thenThrow(new RuntimeException("ai table down"));   // AI 조회 실패가 리포트를 막지 않는다
        Mockito.when(pdf.renderPdf(anyString(), any(), eq(r), any(), any())).thenReturn("%PDF-1.7 test".getBytes());
        mvc.perform(get("/thread-dump/analyze/스레드.txt.gz/print-pdf").principal(user))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", containsString("%EC%8A%A4%EB%A0%88%EB%93%9C-thread-dump-report.pdf")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/thread-dump/analyze/a.txt/print-pdf").param("mode", "inline").principal(user))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")));

        Mockito.when(pdf.renderPdf(eq("bad.txt"), any(), any(), any(), any())).thenThrow(new IOException("PDF 생성 실패: xml"));
        mvc.perform(get("/thread-dump/analyze/bad.txt/print-pdf").principal(user)).andExpect(status().isInternalServerError());
    }
}

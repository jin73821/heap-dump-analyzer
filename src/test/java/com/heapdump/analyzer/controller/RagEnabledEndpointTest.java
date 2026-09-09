package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.service.AiInsightManager;
import com.heapdump.analyzer.service.ChromaSearchService;
import com.heapdump.analyzer.service.EmbeddingService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.LlmConfigService;
import com.heapdump.analyzer.service.LlmRateLimitService;
import com.heapdump.analyzer.service.RagConfigService;
import com.heapdump.analyzer.service.RagKnowledgeExportService;
import com.heapdump.analyzer.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/settings/rag/enabled} 계약 (standalone MockMvc — Spring 컨텍스트/DB 없음).
 *
 * <p>2026-09-02 점검에서 드러난 세 가지를 고정한다:
 * <ol>
 *   <li>{@code enabled} 누락/형식 오류가 <b>조용히 false(=비활성화)</b> 로 처리되던 문제 → 400</li>
 *   <li>settings.json 기록 실패가 {@code success:true} 로 보고되던 문제 → {@code persisted:false} + 경고</li>
 *   <li>예외가 Spring 기본 500(영문)으로 새던 문제 → 한국어 {@code error} 3필드</li>
 * </ol>
 */
class RagEnabledEndpointTest {

    private HeapDumpAnalyzerService analyzerService;
    private RagConfigService ragConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        analyzerService = Mockito.mock(HeapDumpAnalyzerService.class);
        ragConfig = Mockito.mock(RagConfigService.class);

        HeapAiApiController controller = new HeapAiApiController(
                analyzerService,
                Mockito.mock(LlmConfigService.class),
                Mockito.mock(LlmRateLimitService.class),
                ragConfig,
                Mockito.mock(AiInsightManager.class),
                Mockito.mock(HeapDumpConfig.class),
                Mockito.mock(RagService.class),
                Mockito.mock(EmbeddingService.class),
                Mockito.mock(ChromaSearchService.class),
                Mockito.mock(RagKnowledgeExportService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private org.springframework.test.web.servlet.ResultActions toggle(String json) throws Exception {
        return mvc.perform(post("/api/settings/rag/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    @DisplayName("정상 토글 — 서버 실측값과 persisted 를 함께 돌려준다")
    void togglesAndReportsPersisted() throws Exception {
        Mockito.when(analyzerService.setRagEnabled(true)).thenReturn(true);
        Mockito.when(ragConfig.isRagEnabled()).thenReturn(false, true);

        toggle("{\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.persisted").value(true))
                .andExpect(jsonPath("$.warning").doesNotExist());

        Mockito.verify(analyzerService).setRagEnabled(true);
    }

    @Test
    @DisplayName("저장 실패는 success:true + persisted:false + 경고 — 적용은 됐으므로 토글을 되돌리면 안 된다")
    void persistFailureIsReportedWithoutRevert() throws Exception {
        Mockito.when(analyzerService.setRagEnabled(false)).thenReturn(false);
        Mockito.when(ragConfig.isRagEnabled()).thenReturn(true, false);

        toggle("{\"enabled\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))   // 메모리에는 반영됐다
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.warning").exists());
    }

    @Test
    @DisplayName("enabled 누락은 400 — 조용히 비활성화하지 않는다")
    void missingEnabledIsRejected() throws Exception {
        toggle("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error").exists());

        Mockito.verify(analyzerService, Mockito.never()).setRagEnabled(anyBoolean());
    }

    @Test
    @DisplayName("enabled 가 boolean 이 아니면 400 — 문자열 \"false\" 도 거부")
    void nonBooleanEnabledIsRejected() throws Exception {
        toggle("{\"enabled\":\"false\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Mockito.verify(analyzerService, Mockito.never()).setRagEnabled(anyBoolean());
    }

    @Test
    @DisplayName("설정 변경 중 예외는 한국어 메시지를 담은 500 으로 나간다")
    void runtimeFailureIsReportedInKorean() throws Exception {
        Mockito.when(ragConfig.isRagEnabled()).thenReturn(false);
        Mockito.when(analyzerService.setRagEnabled(true))
                .thenThrow(new IllegalStateException("settings 디렉토리 없음"));

        toggle("{\"enabled\":true}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RAG_TOGGLE_FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("변경하지 못했습니다")));
    }
}

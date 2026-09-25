package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.GcLogInstanceService;
import com.heapdump.analyzer.service.GcLogMatchService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회전 로그 이어 붙이기 API (2026-09-20, standalone MockMvc).
 *
 * <p>고정하는 계약: ① 오류는 언제나 {@code {success:false, code, error}} ② 호스트네임 없이 분석을 시작하면 전용 코드
 * {@code NO_HOSTNAME} 으로 400(화면이 입력 칸을 펼칠 수 있어야 한다) ③ 미리보기는 분석을 시작하지 않는다
 * ④ 빈 목록은 구성 해제 ⑤ 후보 목록은 같은 계열·서버·일자를 {@code suggested} 로 표시한다.
 */
class GcLogCombineEndpointTest {

    private GcLogAnalyzerService gc;
    private GcLogAnalysisEntity entity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gc = Mockito.mock(GcLogAnalyzerService.class);
        GcLogMatchService match = Mockito.mock(GcLogMatchService.class);
        GcLogAnalysisRepository repo = Mockito.mock(GcLogAnalysisRepository.class);
        HeapDumpAnalyzerService heap = Mockito.mock(HeapDumpAnalyzerService.class);
        entity = new GcLogAnalysisEntity();
        entity.setFilename("gc.log");
        entity.setServerName("was1");
        entity.setStatus(GcLogAnalysisEntity.STATUS_ANALYZING);
        entity.setMemberCount(3);
        entity.setAnalysisName("was1_c1_20260919");
        Mockito.when(gc.validateGcLogFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(gc.find("gc.log")).thenReturn(Optional.of(entity));
        Mockito.when(gc.find("missing.log")).thenReturn(Optional.empty());
        Mockito.when(gc.parseMembers(any())).thenReturn(List.of());
        GcLogInstanceService instance = new GcLogInstanceService(repo, heap);
        mvc = MockMvcBuilders.standaloneSetup(new GcLogApiController(gc, match, instance, null, null)).build();
    }

    @Test
    @DisplayName("combine — 구성 파일 수·분석명을 돌려주고, 알 수 없는 로그는 404")
    void combineReturnsCompositionAndHandlesUnknown() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log/combine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log.1\",\"gc.log.2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.analysisName").value("was1_c1_20260919"));
        Mockito.verify(gc).submitCombined(eq("gc.log"), eq(List.of("gc.log.1", "gc.log.2")), anyString(), eq("manual"));

        mvc.perform(post("/api/gc-log/missing.log/combine").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("호스트네임이 없으면 NO_HOSTNAME 400 — 화면이 입력 칸을 펼칠 수 있게 전용 코드로")
    void missingHostnameHasItsOwnCode() throws Exception {
        Mockito.doThrow(new GcLogAnalyzerService.MissingHostnameException("GC 로그를 수집한 서버(호스트네임)를 입력해야 분석할 수 있습니다."))
                .when(gc).submitCombined(anyString(), any(), anyString(), anyString());

        mvc.perform(post("/api/gc-log/gc.log/combine").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NO_HOSTNAME"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("호스트네임")));
    }

    @Test
    @DisplayName("잘못된 요청은 {success,code,error} 400 — filenames 가 배열이 아니면 거부")
    void badRequestKeepsJsonContract() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log/combine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":\"gc.log.1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        Mockito.doThrow(new IllegalArgumentException("GC 로그 파일이 없습니다: gc.log.9"))
                .when(gc).submitCombined(anyString(), any(), anyString(), anyString());
        mvc.perform(post("/api/gc-log/gc.log/combine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log.9\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("gc.log.9")));
    }

    @Test
    @DisplayName("미리보기는 순서·경고만 돌려주고 분석을 시작하지 않는다")
    void previewDoesNotStartAnalysis() throws Exception {
        Mockito.when(gc.planCombine(eq("gc.log"), any())).thenReturn(new GcLogAnalyzerService.CombinePlan(
                List.of(new GcLogAnalyzerService.MemberRef("gc.log.1", 100, 1L),
                        new GcLogAnalyzerService.MemberRef("gc.log", 200, 2L)),
                List.of("일자가 다른 로그를 함께 묶었습니다 — 분석명이 기간 표기로 바뀝니다."), 300, "absolute"));

        mvc.perform(post("/api/gc-log/gc.log/combine/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log.1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0].filename").value("gc.log.1"))
                .andExpect(jsonPath("$.order[1].filename").value("gc.log"))
                .andExpect(jsonPath("$.totalBytes").value(300))
                .andExpect(jsonPath("$.orderBasis").value("absolute"))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("일자")));

        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString());
        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString(), anyString());
        Mockito.verify(gc, Mockito.never()).submitAnalysis(anyString(), anyString());
    }

    @Test
    @DisplayName("후보 목록은 같은 계열·서버·일자를 suggested 로 표시하고, 현재 로그는 current 로 따로 준다")
    void combineOptionsMarkSuggested() throws Exception {
        Map<String, Object> sibling = new LinkedHashMap<>();
        sibling.put("filename", "gc.log.1");
        sibling.put("suggested", true);
        sibling.put("sameSeries", true);
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("filename", "other-gc.log");
        other.put("suggested", false);
        Mockito.when(gc.combineOptions(eq(entity), any(), anyInt())).thenReturn(List.of(sibling, other));
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("filename", "gc.log");
        current.put("analysisName", "was1_c1_20260919");
        Mockito.when(gc.combineCurrent(entity)).thenReturn(current);

        mvc.perform(get("/api/gc-log/gc.log/combine-options").param("q", "gc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.options[0].filename").value("gc.log.1"))
                .andExpect(jsonPath("$.options[0].suggested").value(true))
                .andExpect(jsonPath("$.options[1].suggested").value(false))
                // 현재 로그는 후보가 아니라 모달 맨 위 회색 고정 행으로 따로 온다(2026-09-25)
                .andExpect(jsonPath("$.current.filename").value("gc.log"))
                .andExpect(jsonPath("$.current.analysisName").value("was1_c1_20260919"))
                .andExpect(jsonPath("$.maxMembers").value(GcLogAnalyzerService.MAX_MEMBERS));
    }
}

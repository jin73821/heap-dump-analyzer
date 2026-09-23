package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.GcLogGroupPlanner;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 연속 회전본 자동 그룹화 API (2026-09-21, standalone MockMvc).
 *
 * <p>고정하는 계약: ① 자동 경로는 <b>절대 분석을 제출하지 않는다</b> ② 미리보기는 쓰지 않는다
 * ③ 알 수 없는 파일은 404 가 아니라 {@code skipped}(배치 의미) ④ 오류는 {@code {success,code,error}}
 * ⑤ 그룹 경로에서 {@code NO_HOSTNAME} 이 나오면 안 된다 — 묶기는 분석이 아니다.
 *
 * <p>⚠ 컨트롤러 생성자에 빈을 추가하지 않는다 — 이 테스트와 {@code GcLogCombineEndpointTest} 가 생성자를 직접 부른다.
 */
class GcLogGroupEndpointTest {

    private GcLogAnalyzerService gc;
    private MockMvc mvc;


    @BeforeEach
    void setUp() {
        gc = Mockito.mock(GcLogAnalyzerService.class);
        GcLogMatchService match = Mockito.mock(GcLogMatchService.class);
        GcLogAnalysisRepository repo = Mockito.mock(GcLogAnalysisRepository.class);
        HeapDumpAnalyzerService heap = Mockito.mock(HeapDumpAnalyzerService.class);
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc.log");
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setMemberCount(3);
        e.setAnalysisName("was01_c1_20260919");
        Mockito.when(gc.validateGcLogFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(gc.find("gc.log")).thenReturn(Optional.of(e));
        Mockito.when(gc.find("missing.log")).thenReturn(Optional.empty());
        Mockito.when(gc.parseMembers(any())).thenReturn(List.of());
        GcLogInstanceService instance = new GcLogInstanceService(repo, heap);
        mvc = MockMvcBuilders.standaloneSetup(new GcLogApiController(gc, match, instance, null, null)).build();
    }

    private static GcLogAnalyzerService.AutoGroupResult oneGroup(boolean linked) {
        GcLogGroupPlanner.Group g = new GcLogGroupPlanner.Group(
                "gc.log", List.of("gc.log.0", "gc.log"),
                new GcLogGroupPlanner.Axes(GcLogGroupPlanner.Signal.MATCH,
                        GcLogGroupPlanner.Signal.MATCH, GcLogGroupPlanner.Signal.UNKNOWN),
                List.of("공백이 있습니다."), 2048L, "absolute");
        return new GcLogAnalyzerService.AutoGroupResult(
                List.of(new GcLogAnalyzerService.GroupOutcome(g, linked, null, "was01_c1_20260919")),
                List.of("other.log"), Map.of("other.log", "이어질 로그를 찾지 못했습니다"),
                List.of(Map.of("filename", "missing.log", "reason", "not-registered")));
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ group/auto 는 묶기만 한다 — 분석을 제출하지 않는다")
    void autoGroupLinksButNeverAnalyzes() throws Exception {
        Mockito.when(gc.autoGroup(any(), anyString(), eq(true))).thenReturn(oneGroup(true));

        mvc.perform(post("/api/gc-log/group/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log\",\"gc.log.0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.groups[0].representative").value("gc.log"))
                .andExpect(jsonPath("$.groups[0].memberCount").value(2))
                .andExpect(jsonPath("$.groups[0].linked").value(true))
                .andExpect(jsonPath("$.groups[0].analysisName").value("was01_c1_20260919"))
                .andExpect(jsonPath("$.groups[0].axes.time").value("MATCH"))
                .andExpect(jsonPath("$.groups[0].axes.jvm").value("UNKNOWN"))
                .andExpect(jsonPath("$.singles[0]").value("other.log"))
                .andExpect(jsonPath("$.skipped[0].reason").value("not-registered"));

        Mockito.verify(gc, Mockito.never()).submitAnalysis(anyString(), anyString());
        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString());
        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("group/preview 는 쓰지 않는다 — apply=false 로만 부른다")
    void previewDoesNotWrite() throws Exception {
        Mockito.when(gc.autoGroup(any(), anyString(), eq(false))).thenReturn(oneGroup(false));

        mvc.perform(post("/api/gc-log/group/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log\",\"gc.log.0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].linked").value(false));

        Mockito.verify(gc).autoGroup(any(), anyString(), eq(false));
        Mockito.verify(gc, Mockito.never()).autoGroup(any(), anyString(), eq(true));
        Mockito.verify(gc, Mockito.never()).linkCombined(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("그룹이 하나도 없어도 200 — 정상 상황이다")
    void emptyPlanIsStillOk() throws Exception {
        Mockito.when(gc.autoGroup(any(), anyString(), anyBoolean())).thenReturn(
                new GcLogAnalyzerService.AutoGroupResult(List.of(), List.of("a.log"),
                        Map.of("a.log", "이어질 로그를 찾지 못했습니다"), List.of()));

        mvc.perform(post("/api/gc-log/group/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"a.log\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.groups").isEmpty())
                .andExpect(jsonPath("$.singleReasons['a.log']").exists());
    }

    @Test
    @DisplayName("filenames 가 배열이 아니면 400 + JSON 계약 유지")
    void badRequestKeepsJsonContract() throws Exception {
        mvc.perform(post("/api/gc-log/group/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":\"gc.log\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("combine/link 는 묶기만 한다 — 분석 제출 없음, 호스트네임도 묻지 않는다")
    void linkEndpointDoesNotAnalyze() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log/combine/link").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log.0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(GcLogAnalysisEntity.STATUS_NOT_ANALYZED))
                .andExpect(jsonPath("$.memberCount").value(3));

        Mockito.verify(gc).linkCombined(eq("gc.log"), eq(List.of("gc.log.0")), anyString(), eq("manual"));
        Mockito.verify(gc, Mockito.never()).submitAnalysis(anyString(), anyString());
        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString());
        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("combine/link — 빈 배열은 해제, 없는 로그는 404")
    void linkUngroupsAndHandlesUnknown() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log/combine/link").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        Mockito.verify(gc).linkCombined(eq("gc.log"), eq(List.of()), anyString(), eq("manual"));

        mvc.perform(post("/api/gc-log/missing.log/combine/link").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("묶기는 분석이 아니므로 그룹 경로에서 NO_HOSTNAME 이 나오지 않는다")
    void groupPathsNeverDemandHostname() throws Exception {
        Mockito.when(gc.autoGroup(any(), anyString(), anyBoolean())).thenReturn(oneGroup(true));

        mvc.perform(post("/api/gc-log/group/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist());

        // 서비스가 호스트네임을 요구하지 않는 것은 서비스 테스트가 고정한다(여기서는 계약만 확인)
        Mockito.verify(gc, Mockito.never()).submitAnalysis(anyString(), anyString());
    }
}

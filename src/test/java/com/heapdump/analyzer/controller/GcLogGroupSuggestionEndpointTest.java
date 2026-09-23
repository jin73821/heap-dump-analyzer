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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 시작 전 연속 로그 제안 API (2026-09-23, standalone MockMvc).
 *
 * <p>고정하는 계약: ① {@code GET …/group-suggestion} 은 제안만 한다 — 묶기·분석을 하지 않는다
 * ② 응답 모양(mode·대표·구성 순서·시각·축·제외 수)은 확인 창(analyze-confirm.js)이 그대로 읽는다
 * ③ 알 수 없는 로그는 404 ④ 수락 경로 {@code /combine} 의 {@code via} 는 {@code suggest} 만 인정하고 나머지는 manual.
 */
class GcLogGroupSuggestionEndpointTest {

    private GcLogAnalyzerService gc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gc = Mockito.mock(GcLogAnalyzerService.class);
        GcLogAnalysisRepository repo = Mockito.mock(GcLogAnalysisRepository.class);
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc.log.1");
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        Mockito.when(gc.validateGcLogFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(gc.find("gc.log.1")).thenReturn(Optional.of(e));
        Mockito.when(gc.find("gc.log")).thenReturn(Optional.of(e));
        Mockito.when(gc.find("missing.log")).thenReturn(Optional.empty());
        GcLogInstanceService instance = new GcLogInstanceService(repo, Mockito.mock(HeapDumpAnalyzerService.class));
        mvc = MockMvcBuilders.standaloneSetup(
                new GcLogApiController(gc, Mockito.mock(GcLogMatchService.class), instance, null, null)).build();
    }

    @Test
    @DisplayName("★ 제안 응답 — 대표·시간순 구성·시각·축·제외 수를 싣고, 묶기·분석은 하지 않는다")
    void suggestionShapeAndNoWrites() throws Exception {
        Mockito.when(gc.suggestGroup("gc.log.1")).thenReturn(new GcLogAnalyzerService.GroupSuggestion(
                GcLogAnalyzerService.SUGGEST_GROUP, "gc.log.1", "gc.log", "/tmp/gc.log",
                List.of(new GcLogAnalyzerService.SuggestedMember("gc.log.1", 100, "2026-09-20 12:59:43", "2026-09-20 13:16:34",
                                GcLogAnalysisEntity.STATUS_SUCCESS, "aibank01d_20260920", false),
                        new GcLogAnalyzerService.SuggestedMember("gc.log", 200, "2026-09-20 13:16:35", "2026-09-23 15:12:22",
                                GcLogAnalysisEntity.STATUS_NOT_ANALYZED, null, false)),
                new GcLogGroupPlanner.Axes(GcLogGroupPlanner.Signal.MATCH, GcLogGroupPlanner.Signal.MATCH,
                        GcLogGroupPlanner.Signal.UNKNOWN),
                List.of("공백이 있습니다."), null, 3, 1, 300L, "absolute", "aibank01d", "G1", 512L * 1024 * 1024));

        mvc.perform(get("/api/gc-log/gc.log.1/group-suggestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.mode").value("group"))
                .andExpect(jsonPath("$.filename").value("gc.log.1"))
                .andExpect(jsonPath("$.representative").value("gc.log"))
                .andExpect(jsonPath("$.series").value("/tmp/gc.log"))
                .andExpect(jsonPath("$.members[0].filename").value("gc.log.1"))
                .andExpect(jsonPath("$.members[0].start").value("2026-09-20 12:59:43"))
                .andExpect(jsonPath("$.members[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.members[1].end").value("2026-09-23 15:12:22"))
                .andExpect(jsonPath("$.members[1].missing").value(false))
                .andExpect(jsonPath("$.axes.time").value("MATCH"))
                .andExpect(jsonPath("$.axes.jvm").value("UNKNOWN"))
                .andExpect(jsonPath("$.warnings[0]").value("공백이 있습니다."))
                .andExpect(jsonPath("$.candidates").value(3))
                .andExpect(jsonPath("$.excludedGrouped").value(1))
                .andExpect(jsonPath("$.serverName").value("aibank01d"))
                .andExpect(jsonPath("$.collector").value("G1"))
                .andExpect(jsonPath("$.heapMaxBytes").value(512L * 1024 * 1024))
                .andExpect(jsonPath("$.maxMembers").value(GcLogAnalyzerService.MAX_MEMBERS));

        Mockito.verify(gc, Mockito.never()).linkCombined(anyString(), any(), anyString(), anyString());
        Mockito.verify(gc, Mockito.never()).submitCombined(anyString(), any(), anyString(), anyString());
        Mockito.verify(gc, Mockito.never()).submitAnalysis(anyString(), anyString());
    }

    @Test
    @DisplayName("단독 제안은 axes 가 null 이고 이유를 싣는다 · 알 수 없는 로그는 404")
    void singleAndUnknown() throws Exception {
        Mockito.when(gc.suggestGroup("gc.log.1")).thenReturn(new GcLogAnalyzerService.GroupSuggestion(
                GcLogAnalyzerService.SUGGEST_SINGLE, "gc.log.1", "gc.log.1", "/tmp/gc.log", List.of(), null, List.of(),
                "gc.log 과(와) 기록 시간이 겹칩니다", 2, 0, 0L, null, "was01", null, null));

        mvc.perform(get("/api/gc-log/gc.log.1/group-suggestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("single"))
                .andExpect(jsonPath("$.axes").doesNotExist())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("겹칩니다")));

        mvc.perform(get("/api/gc-log/missing.log/group-suggestion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("수락 — /combine 의 via 는 suggest 만 그대로 넘기고, 그 밖의 값은 manual 로 둔다(감사 로그에 임의 문자열 금지)")
    void combineViaWhitelist() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log/combine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log.1\",\"gc.log\"],\"via\":\"suggest\"}"))
                .andExpect(status().isOk());
        Mockito.verify(gc).submitCombined(eq("gc.log"), eq(List.of("gc.log.1", "gc.log")), anyString(), eq("suggest"));

        mvc.perform(post("/api/gc-log/gc.log/combine").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filenames\":[\"gc.log.1\"],\"via\":\"x\\ninjected\"}"))
                .andExpect(status().isOk());
        Mockito.verify(gc).submitCombined(eq("gc.log"), eq(List.of("gc.log.1")), anyString(), eq("manual"));
    }
}

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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GC 로그 인스턴스명 API (2026-09-14, standalone MockMvc).
 *
 * <p>고정하는 계약: ① {@code POST …/instance} 는 수동값을 저장하고 카드가 그대로 그릴 뷰를 돌려준다 ② 빈 값은 수동값을 지워
 * 연결된 덤프 값으로 되돌린다 ③ 매칭 계열 응답({@code /match})에도 {@code instance} 가 실린다 — 연결을 바꾸면 카드가 따라 바뀐다
 * ④ {@code instance} 키가 없는 요청은 400(조용히 지우지 않는다).
 */
class GcLogInstanceEndpointTest {

    private GcLogAnalyzerService gc;
    private GcLogMatchService match;
    private GcLogAnalysisRepository repo;
    private HeapDumpAnalyzerService heap;
    private GcLogAnalysisEntity entity;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gc = Mockito.mock(GcLogAnalyzerService.class);
        match = Mockito.mock(GcLogMatchService.class);
        repo = Mockito.mock(GcLogAnalysisRepository.class);
        heap = Mockito.mock(HeapDumpAnalyzerService.class);
        entity = new GcLogAnalysisEntity();
        entity.setFilename("gc.log.0");
        entity.setMatchedDumpFilename("app.hprof");
        entity.setMatchSource("auto");
        Mockito.when(gc.validateGcLogFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(gc.find("gc.log.0")).thenReturn(Optional.of(entity));
        Mockito.when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(heap.getEffectiveJeusInstance("app.hprof")).thenReturn("server1");
        GcLogInstanceService instance = new GcLogInstanceService(repo, heap);
        mvc = MockMvcBuilders.standaloneSetup(new GcLogApiController(gc, match, instance, null, null)).build();
    }

    @Test
    @DisplayName("수동 저장 → source=manual, 덤프 값은 함께 / 빈 값 → 수동값 삭제 후 덤프 값 폴백")
    void saveAndClear() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log.0/instance").contentType(MediaType.APPLICATION_JSON).content("{\"instance\":\"  was_a \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.instance.name").value("was_a"))
                .andExpect(jsonPath("$.instance.source").value("manual"))
                .andExpect(jsonPath("$.instance.dump").value("server1"));
        assertEquals("was_a", entity.getInstanceName());

        mvc.perform(post("/api/gc-log/gc.log.0/instance").contentType(MediaType.APPLICATION_JSON).content("{\"instance\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.name").value("server1"))
                .andExpect(jsonPath("$.instance.source").value("dump"))
                .andExpect(jsonPath("$.instance.dumpFilename").value("app.hprof"));
        assertNull(entity.getInstanceName());
        Mockito.verify(repo, Mockito.times(2)).save(entity);
    }

    @Test
    @DisplayName("instance 키 없음 → 400, 이력 없음 → 404 (둘 다 저장하지 않는다)")
    void rejects() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log.0/instance").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/gc-log/none.log/instance").contentType(MediaType.APPLICATION_JSON).content("{\"instance\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        Mockito.verify(repo, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("매칭 응답에 instance 뷰가 실린다 — 연결 해제 후에는 비어 있다")
    void matchViewCarriesInstance() throws Exception {
        mvc.perform(get("/api/gc-log/gc.log.0/match"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.name").value("server1"))
                .andExpect(jsonPath("$.instance.source").value("dump"));

        GcLogAnalysisEntity unlinked = new GcLogAnalysisEntity();
        unlinked.setFilename("gc.log.0");
        unlinked.setMatchSource("manual");
        Mockito.when(match.setManual(eq(entity), eq(null))).thenReturn(unlinked);
        mvc.perform(post("/api/gc-log/gc.log.0/match").contentType(MediaType.APPLICATION_JSON).content("{\"dumpFilename\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").isEmpty())
                .andExpect(jsonPath("$.instance.name").value(""))
                .andExpect(jsonPath("$.instance.source").value("none"));
    }
}

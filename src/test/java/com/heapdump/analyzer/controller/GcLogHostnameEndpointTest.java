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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GC 로그 출처 서버명 API (2026-09-15, standalone MockMvc).
 *
 * <p>고정하는 계약: ① 결과 페이지 '서버' 알약이 나중에 입력한 값을 저장하고, 응답이 매칭 뷰({@code matched}·{@code candidates}·
 * {@code instance}) + {@code hostname} 이다 — 서버명이 매칭 신호라 저장 뒤 칩·인스턴스 카드도 같은 응답으로 다시 그린다
 * ② 빈 값은 서버명을 지운다 ③ {@code hostname} 키가 없으면 400, 이력이 없으면 404 — 둘 다 저장하지 않는다.
 */
class GcLogHostnameEndpointTest {

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
        entity.setFilename("gc.log.0");
        entity.setMatchSource("none");
        Mockito.when(gc.validateGcLogFilename(anyString())).thenAnswer(inv -> inv.getArgument(0));
        Mockito.when(gc.find("gc.log.0")).thenReturn(Optional.of(entity));
        // 실제 서비스처럼 정규화 후 자동 매칭을 돌린다 — 서버명이 채워지면 덤프가 확정되는 상황을 흉내 낸다
        Mockito.when(gc.updateServerName(any(), any())).thenAnswer(inv -> {
            GcLogAnalysisEntity e = inv.getArgument(0);
            String v = inv.getArgument(1);
            v = v == null || v.isBlank() ? null : v.trim();
            e.setServerName(v);
            e.setMatchedDumpFilename(v == null ? null : "app.hprof");
            e.setMatchSource(v == null ? "none" : "auto");
            return e;
        });
        Mockito.when(heap.getEffectiveJeusInstance("app.hprof")).thenReturn("server1");
        GcLogInstanceService instance = new GcLogInstanceService(repo, heap);
        mvc = MockMvcBuilders.standaloneSetup(new GcLogApiController(gc, match, instance, null, null)).build();
    }

    @Test
    @DisplayName("나중에 입력 → hostname + 매칭 뷰(자동 연결·인스턴스) / 빈 값 → 지우고 연결도 풀린 뷰")
    void saveAndClear() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log.0/hostname").contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\" guacmg1t \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.hostname").value("guacmg1t"))
                .andExpect(jsonPath("$.serverName").value("guacmg1t"))
                .andExpect(jsonPath("$.matched.dumpFilename").value("app.hprof"))
                .andExpect(jsonPath("$.matched.source").value("auto"))
                .andExpect(jsonPath("$.instance.name").value("server1"));

        mvc.perform(post("/api/gc-log/gc.log.0/hostname").contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").isEmpty())
                .andExpect(jsonPath("$.matched").isEmpty())
                .andExpect(jsonPath("$.instance.source").value("none"));
        Mockito.verify(gc, Mockito.times(2)).updateServerName(any(), any());
    }

    @Test
    @DisplayName("hostname 키 없음 → 400, 이력 없음 → 404 (둘 다 저장하지 않는다)")
    void rejects() throws Exception {
        mvc.perform(post("/api/gc-log/gc.log.0/hostname").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/api/gc-log/none.log/hostname").contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        Mockito.verify(gc, Mockito.never()).updateServerName(any(), any());
    }
}

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.dto.AlertItem;
import com.heapdump.analyzer.service.AlertBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * /api/alerts/** 응답 계약 — alert-bell.js·alerts.html 이 {@code success===true}·{@code unackedCount} 를 쓴다.
 * 판정·ACK 로직은 {@code AlertBoardServiceTest} 소관.
 */
class AlertEndpointTest {

    private AlertBoardService service;
    private MockMvc mvc;

    private static Authentication auth(String name, String role) {
        return new UsernamePasswordAuthenticationToken(name, "n/a", List.of(new SimpleGrantedAuthority(role)));
    }

    @BeforeEach
    void setUp() {
        service = Mockito.mock(AlertBoardService.class);
        Mockito.when(service.unacked()).thenReturn(List.of(new AlertItem(), new AlertItem()));
        mvc = MockMvcBuilders.standaloneSetup(new AlertBoardController(service))
                .setSingleView((m, req, res) -> { }).build();
    }

    @Test
    @DisplayName("top — success·unackedCount·alerts, limit 은 1~50 으로 자른다")
    void top() throws Exception {
        AlertItem a = new AlertItem();
        a.setKey("heap:a.hprof:1"); a.setTitle("OOM 탐지");
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("unackedCount", 7);
        top.put("alerts", List.of(a));
        Mockito.when(service.top(anyInt())).thenReturn(top);

        mvc.perform(get("/api/alerts/top").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.unackedCount").value(7))
                .andExpect(jsonPath("$.alerts[0].key").value("heap:a.hprof:1"));
        Mockito.verify(service).top(50);
    }

    @Test
    @DisplayName("ack·unack·ack-all — 로그인 사용자로 기록하고 남은 미확인 건수를 돌려준다")
    void ackContract() throws Exception {
        Mockito.when(service.ack("heap:a.hprof:1", "alice")).thenReturn(true);
        mvc.perform(post("/api/alerts/ack").principal(auth("alice", "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"heap:a.hprof:1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.already").value(false))
                .andExpect(jsonPath("$.unackedCount").value(2));

        Mockito.when(service.unack("heap:a.hprof:1")).thenReturn("alice");
        mvc.perform(post("/api/alerts/unack").principal(auth("bob", "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"heap:a.hprof:1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));

        Mockito.when(service.ackAll("alice")).thenReturn(2);
        mvc.perform(post("/api/alerts/ack-all").principal(auth("alice", "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acked").value(2));
    }

    @Test
    @DisplayName("ack·ack-all 은 확인자·시각을 함께 돌려준다 — 화면이 그 행만 고쳐 그린다(새로고침 없이)")
    void ackReturnsAckInfo() throws Exception {
        // 이미 bob 이 확인해 둔 알람을 alice 가 눌렀다 — 화면에 찍혀야 하는 이름은 alice 가 아니라 bob 이다
        Mockito.when(service.ack("heap:a.hprof:1", "alice")).thenReturn(false);
        Mockito.when(service.ackInfo("heap:a.hprof:1"))
                .thenReturn(Map.of("by", "bob", "atLabel", "2026-09-20 10:00"));
        mvc.perform(post("/api/alerts/ack").principal(auth("alice", "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"heap:a.hprof:1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.already").value(true))
                .andExpect(jsonPath("$.ack.by").value("bob"))
                .andExpect(jsonPath("$.ack.atLabel").value("2026-09-20 10:00"));

        // 전체 확인은 행마다 확인자가 다를 수 있다 — key → 확인 정보 맵을 그대로 싣는다
        Mockito.when(service.ackAll("alice")).thenReturn(1);
        Mockito.when(service.ackInfoAll()).thenReturn(Map.of(
                "heap:a.hprof:1", Map.of("by", "bob", "atLabel", "2026-09-20 10:00"),
                "gclog:gc.log:2", Map.of("by", "alice", "atLabel", "2026-09-20 11:00")));
        mvc.perform(post("/api/alerts/ack-all").principal(auth("alice", "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acks['heap:a.hprof:1'].by").value("bob"))
                .andExpect(jsonPath("$.acks['gclog:gc.log:2'].by").value("alice"));
    }

    @Test
    @DisplayName("key 누락·잘못된 key 는 400 JSON {success,code,error}")
    void badRequest() throws Exception {
        mvc.perform(post("/api/alerts/ack").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ALERT_BAD_REQUEST"));
        mvc.perform(post("/api/alerts/ack"))
                .andExpect(status().isBadRequest());

        Mockito.when(service.ack(Mockito.eq("evil"), Mockito.any())).thenThrow(new IllegalArgumentException("알람 key 가 올바르지 않습니다."));
        mvc.perform(post("/api/alerts/ack").contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"evil\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("알람 key 가 올바르지 않습니다."));
    }

    @Test
    @DisplayName("/alerts — 관리자 여부를 서비스에 넘긴다 (비관리자는 삭제 파일 제외)")
    void pagePassesRole() throws Exception {
        Mockito.when(service.board(Mockito.anyBoolean())).thenReturn(new AlertBoardService.Board());

        mvc.perform(get("/alerts").principal(auth("u", "ROLE_USER")))
                .andExpect(status().isOk()).andExpect(view().name("alerts"))
                .andExpect(model().attribute("isAdmin", false));
        Mockito.verify(service).board(false);

        mvc.perform(get("/alerts").principal(auth("admin", "ROLE_ADMIN")))
                .andExpect(model().attribute("isAdmin", true));
        Mockito.verify(service).board(true);
    }
}

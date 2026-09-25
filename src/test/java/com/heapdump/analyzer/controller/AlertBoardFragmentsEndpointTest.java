package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.dto.AlertBoardRow;
import com.heapdump.analyzer.model.dto.AlertDomainKpi;
import com.heapdump.analyzer.model.dto.AlertItem;
import com.heapdump.analyzer.service.AlertBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 현황판 부분 갱신 조각 {@code GET /api/alerts/fragments} (2026-09-26, standalone MockMvc + 실제 Thymeleaf 뷰 리졸버).
 *
 * <p>고정하는 계약: ① Spring {@code FragmentsRendering} 이 {@code alerts :: alertKpi} 같은 Thymeleaf 조각 뷰명을
 * 실제로 해석해 한 응답에 3조각을 잇는다 ② Content-Type 은 {@code text/html;charset=UTF-8}(함정 35 — charset 이
 * 빠지면 프록시가 라벨을 채워 한글이 깨진다) ③ 조각에는 문서 골격·토글·모달이 없다(페이지 JS 가 캡처한 요소가
 * 살아남아야 한다) ④ 페이지 {@code /alerts} 와 조각은 같은 모델 채우기를 쓴다.
 */
class AlertBoardFragmentsEndpointTest {

    private AlertBoardService svc;
    private AlertBoardController controller;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock(AlertBoardService.class);
        AlertBoardService.Board b = new AlertBoardService.Board();
        AlertDomainKpi k = new AlertDomainKpi();
        k.setTotal(5); k.setSuccess(3); k.setError(1); k.setNotAnalyzed(1); k.setCritical(2);
        b.kpi.put("heap", k); b.kpi.put("core", new AlertDomainKpi()); b.kpi.put("gclog", new AlertDomainKpi());
        AlertBoardRow row = new AlertBoardRow();
        row.setDomain("heap"); row.setFilename("a.hprof"); row.setStatus("SUCCESS"); row.setSeverity("Critical");
        row.setKeySignal("OOM"); row.setAnalyzedAtLabel("2026-09-19 10:00"); row.setLink("/analyze/result/a.hprof");
        b.rows.put("heap", List.of(row)); b.rows.put("core", List.of()); b.rows.put("gclog", List.of());
        AlertItem a = new AlertItem();
        a.setKey("heap:a.hprof:1"); a.setDomain("heap"); a.setFilename("a.hprof"); a.setSeverity("Critical");
        a.setTitle("OOM 탐지"); a.setOccurredAtLabel("2026-09-19 10:00"); a.setLink("/analyze/result/a.hprof");
        b.alerts = List.of(a);
        b.unackedCount = 1;
        b.deletedCount = 0;
        Mockito.when(svc.board(Mockito.anyBoolean())).thenReturn(b);
        controller = new AlertBoardController(svc);
    }

    private static ThymeleafViewResolver thymeleaf() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        ThymeleafViewResolver vr = new ThymeleafViewResolver();
        vr.setTemplateEngine(engine);
        vr.setCharacterEncoding("UTF-8");
        vr.setContentType("text/html;charset=UTF-8");   // Boot 자동설정(spring.thymeleaf.servlet.content-type + encoding)과 동일
        return vr;
    }

    @Test
    @DisplayName("한 응답에 3조각(KPI·알람 목록·최근 분석 표)만 — 문서 골격·토글·모달은 없고 charset 이 명시된다")
    void fragmentsRenderThreeRegionsOnly() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(thymeleaf()).build();
        MvcResult r = mvc.perform(get("/api/alerts/fragments"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        String ct = r.getResponse().getContentType();
        assertTrue(ct != null && ct.startsWith("text/html") && ct.toLowerCase().contains("charset=utf-8"), "Content-Type: " + ct);
        String body = r.getResponse().getContentAsString();
        assertTrue(body.contains("id=\"alertKpi\""), "KPI 조각");
        assertTrue(body.contains("id=\"alertList\"") && body.contains("data-unacked=\"1\"") && body.contains("data-deleted-count=\"0\""), "알람 목록 조각 + 카운터 data-*");
        assertTrue(body.contains("data-key=\"heap:a.hprof:1\"") && body.contains("<b>5</b>"), "모델이 조각에 반영되지 않았다");
        assertTrue(body.contains("id=\"alertRows\"") && body.contains("href=\"/analyze/result/a.hprof\""), "최근 분석 조각");
        assertTrue(body.indexOf("id=\"alertKpi\"") < body.indexOf("id=\"alertList\"")
                && body.indexOf("id=\"alertList\"") < body.indexOf("id=\"alertRows\""), "조각 순서");
        assertFalse(body.contains("<html") || body.contains("<head") || body.contains("<body"), "문서 골격이 섞였다");
        assertFalse(body.contains("id=\"rowTabs\"") || body.contains("id=\"ackFilter\"") || body.contains("id=\"ackConfirmModal\""),
                "토글·모달이 조각에 들어갔다 — 페이지 JS 가 캡처한 요소가 죽는다");
        assertFalse(body.contains("th:fragment") || body.contains("th:each"), "미처리 th: 속성");
    }

    @Test
    @DisplayName("페이지 /alerts 는 같은 모델 채우기를 거쳐 뷰 alerts 를 낸다")
    void pageStillRendersSameModel() throws Exception {
        // 뷰는 forward(렌더하지 않음) — 페이지 렌더 자체는 AlertBoardTemplateSmokeTest 가 본다
        MockMvc plain = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(new org.springframework.web.servlet.view.InternalResourceViewResolver("/WEB-INF/", ".html")).build();
        plain.perform(get("/alerts"))
                .andExpect(status().isOk())
                .andExpect(view().name("alerts"))
                .andExpect(model().attribute("unackedCount", 1))
                .andExpect(model().attributeExists("kpi", "rows", "alerts", "deletedCount", "isAdmin"));
    }
}

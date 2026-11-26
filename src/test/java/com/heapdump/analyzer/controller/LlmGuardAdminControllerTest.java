package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.llmguard.LlmGuardLogService;
import com.heapdump.analyzer.service.llmguard.LlmGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 민감정보 탐지 관리 API — 검증·내장 규칙 보호·경로 계약 (LLM Guard, 2026-11-26). */
class LlmGuardAdminControllerTest {

    private LlmGuardRuleRepository repo;
    private HeapDumpAnalyzerService analyzer;
    private MockMvc mvc;
    private LlmGuardRule builtin;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(LlmGuardRuleRepository.class);
        analyzer = Mockito.mock(HeapDumpAnalyzerService.class);
        Mockito.when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(Collections.emptyList());
        Mockito.when(repo.save(any(LlmGuardRule.class))).thenAnswer(inv -> inv.getArgument(0));
        LlmGuardService guard = new LlmGuardService(null, repo, Mockito.mock(LlmGuardLogService.class));
        guard.init();

        builtin = new LlmGuardRule();
        builtin.setId(1L);
        builtin.setName("주민·외국인등록번호");
        builtin.setCategory("개인식별");
        builtin.setMatchType(LlmGuardRule.TYPE_BUILTIN);
        builtin.setBuiltinKey("RRN");
        builtin.setPattern("YYMMDD-[1-8]NNNNNN");
        builtin.setInputAction(LlmGuardRule.ACTION_BLOCK);
        Mockito.when(repo.findById(1L)).thenReturn(Optional.of(builtin));

        mvc = MockMvcBuilders.standaloneSetup(new LlmGuardAdminController(guard,
                Mockito.mock(LlmGuardLogService.class), repo, analyzer)).build();
    }

    @Test
    @DisplayName("잘못된 정규식은 400")
    void invalidRegex() throws Exception {
        mvc.perform(post("/api/admin/llm-guard/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"category\":\"기타\",\"matchType\":\"REGEX\",\"pattern\":\"(abc\",\"inputAction\":\"CONFIRM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_RULE"));
        Mockito.verify(repo, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("정상 규칙은 저장되고 키워드는 줄 단위로 정규화된다")
    void createKeyword() throws Exception {
        mvc.perform(post("/api/admin/llm-guard/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"임원\",\"category\":\"개인식별\",\"matchType\":\"KEYWORD\","
                        + "\"pattern\":\" 홍길동 \\n\\n홍길동\\n김철수\",\"inputAction\":\"BLOCK\",\"checkOutput\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.pattern").value("홍길동\n김철수"))
                .andExpect(jsonPath("$.rule.inputAction").value("BLOCK"));
    }

    @Test
    @DisplayName("내장 규칙 삭제는 400")
    void builtinDelete() throws Exception {
        mvc.perform(delete("/api/admin/llm-guard/rules/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUILTIN_READONLY"));
        Mockito.verify(repo, Mockito.never()).delete(any());
    }

    @Test
    @DisplayName("내장 규칙의 패턴 변경은 400, 조치·사용 여부 변경은 허용")
    void builtinPatternReadonly() throws Exception {
        mvc.perform(put("/api/admin/llm-guard/rules/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pattern\":\"\\\\d+\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUILTIN_READONLY"));
        mvc.perform(put("/api/admin/llm-guard/rules/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputAction\":\"CONFIRM\",\"enabled\":false,\"pattern\":\"YYMMDD-[1-8]NNNNNN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule.inputAction").value("CONFIRM"))
                .andExpect(jsonPath("$.rule.enabled").value(false));
    }

    @Test
    @DisplayName("설정 저장 — 불리언이 아니면 400, 정상이면 facade 경유 영속화")
    void settings() throws Exception {
        mvc.perform(post("/api/admin/llm-guard/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputCheckEnabled\":\"yes\",\"outputMaskEnabled\":true,\"outputInstructionEnabled\":true}"))
                .andExpect(status().isBadRequest());
        Mockito.when(analyzer.setLlmGuardSettings(false, true, true)).thenReturn(true);
        mvc.perform(post("/api/admin/llm-guard/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputCheckEnabled\":false,\"outputMaskEnabled\":true,\"outputInstructionEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true));
        Mockito.verify(analyzer).setLlmGuardSettings(false, true, true);
    }

    @Test
    @DisplayName("초안 규칙 시험 — 저장 없이 판정·가림 미리보기")
    void testDraft() throws Exception {
        mvc.perform(post("/api/admin/llm-guard/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"사번: 123456\",\"rule\":{\"matchType\":\"REGEX\",\"pattern\":\"사번: (?<v>[0-9]{6})\","
                        + "\"inputAction\":\"BLOCK\",\"checkOutput\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputVerdict").value("BLOCK"))
                .andExpect(jsonPath("$.maskedText").value("사번: 1*****"))
                .andExpect(jsonPath("$.draft").value(true));
        Mockito.verify(repo, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("경로는 /api/admin/ 아래(SecurityConfig ADMIN·CSRF 매처) + 클래스 @PreAuthorize, 이력 삭제 API 없음")
    void mappingContract() {
        RequestMapping rm = LlmGuardAdminController.class.getAnnotation(RequestMapping.class);
        assertTrue(rm.value()[0].startsWith("/api/admin/"));
        PreAuthorize pa = LlmGuardAdminController.class.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", pa.value());
        for (Method m : LlmGuardAdminController.class.getDeclaredMethods()) {
            DeleteMapping d = m.getAnnotation(DeleteMapping.class);
            if (d != null) {
                for (String p : d.value()) assertFalse(p.startsWith("/logs"), "탐지 이력 삭제 API 가 생겼다: " + p);
            }
        }
    }
}

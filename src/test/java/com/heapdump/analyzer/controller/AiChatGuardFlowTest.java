package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.AiChatMessage;
import com.heapdump.analyzer.model.entity.AiChatSession;
import com.heapdump.analyzer.model.entity.LlmGuardLog;
import com.heapdump.analyzer.repository.AiChatMessageRepository;
import com.heapdump.analyzer.repository.AiChatSessionRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.LlmConfigService;
import com.heapdump.analyzer.service.RagService;
import com.heapdump.analyzer.service.llmguard.LlmGuardLogService;
import com.heapdump.analyzer.service.llmguard.LlmGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅 입력 점검 흐름 (LLM Guard, 2026-11-26) — 거부되면 저장·RAG·LLM 호출이 일어나지 않아야 한다.
 */
class AiChatGuardFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AiChatSessionRepository sessionRepo;
    private AiChatMessageRepository messageRepo;
    private RagService ragService;
    private LlmConfigService llmConfig;
    private LlmGuardLogService logService;
    private MockMvc mvc;
    private final UsernamePasswordAuthenticationToken alice = new UsernamePasswordAuthenticationToken(
            "alice", "x", List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @BeforeEach
    void setUp() {
        sessionRepo = Mockito.mock(AiChatSessionRepository.class);
        messageRepo = Mockito.mock(AiChatMessageRepository.class);
        ragService = Mockito.mock(RagService.class);
        llmConfig = Mockito.mock(LlmConfigService.class);
        logService = Mockito.mock(LlmGuardLogService.class);
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getSseEmitterTimeoutMinutes()).thenReturn(1);
        Mockito.when(ragService.fetchContextForLlm(anyString())).thenReturn("");
        Mockito.when(messageRepo.save(any(AiChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        AiChatSession session = new AiChatSession();
        session.setId(7L);
        session.setUsername("alice");
        session.setTitle("새 채팅");
        session.setMessageCount(0);
        Mockito.when(sessionRepo.findById(7L)).thenReturn(Optional.of(session));

        LlmGuardRuleRepository ruleRepo = Mockito.mock(LlmGuardRuleRepository.class);
        Mockito.when(ruleRepo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(Collections.emptyList());
        LlmGuardService guard = new LlmGuardService(null, ruleRepo, logService);
        guard.init();
        guard.setSettings(true, true, true);

        AiChatController controller = new AiChatController(sessionRepo, messageRepo,
                Mockito.mock(AnalysisHistoryRepository.class), Mockito.mock(HeapDumpAnalyzerService.class),
                llmConfig, config, ragService, guard);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String stream(String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/ai-chat/sessions/7/stream").principal(alice)
                .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(r));
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String body(String text, String ack, boolean assistantTail) throws Exception {
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        java.util.List<java.util.Map<String, String>> msgs = new java.util.ArrayList<>();
        msgs.add(java.util.Map.of("role", "user", "content", text));
        if (assistantTail) msgs.add(java.util.Map.of("role", "assistant", "content", "꼬리"));
        b.put("messages", msgs);
        if (ack != null) b.put("sensitiveAck", ack);
        return JSON.writeValueAsString(b);
    }

    private static JsonNode errorPayload(String sse) {
        Matcher m = Pattern.compile("event:error\\s*\\ndata:(\\{.*})").matcher(sse);
        assertTrue(m.find(), "error 이벤트가 없다: " + sse);
        return JSON.readTree(m.group(1));
    }

    private void assertNothingHappened() {
        Mockito.verify(messageRepo, Mockito.never()).save(any());
        Mockito.verify(ragService, Mockito.never()).fetchContextForLlm(anyString());
        Mockito.verify(llmConfig, Mockito.never()).callLlmChatStream(anyList(), any(), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("CONFIRM — error 이벤트만 나가고 메시지 저장·RAG·LLM 호출이 없다")
    void confirmSendsOnlyError() throws Exception {
        JsonNode e = errorPayload(stream(body("연락처 010-1234-5678 로 연락", null, false)));
        assertEquals("SENSITIVE_INPUT_CONFIRM", e.get("errorCode").asText());
        assertEquals("CONFIRM", e.get("guard").get("verdict").asText());
        assertFalse(e.get("guard").get("ackToken").asText().isEmpty());
        // 독립 MockMvc 의 String 변환기는 ISO-8859-1 이라 SSE 본문의 한글이 '?' 가 된다(운영은 Boot 가 UTF-8 로 설정).
        // 그래서 발췌는 ASCII 부분만 본다 — 가린 값이 들어 있고 원문은 없어야 한다.
        String sample = e.get("guard").get("findings").get(0).get("sample").asText();
        assertTrue(sample.contains("010-****-5678") && !sample.contains("1234"), sample);
        assertNothingHappened();
    }

    @Test
    @DisplayName("유효 토큰으로 재전송하면 통과하고 CONFIRMED 가 기록된다")
    @SuppressWarnings("unchecked")
    void validTokenPasses() throws Exception {
        String text = "연락처 010-1234-5678 로 연락";
        String ack = errorPayload(stream(body(text, null, false))).get("guard").get("ackToken").asText();
        Mockito.clearInvocations(logService);

        mvc.perform(post("/api/ai-chat/sessions/7/stream").principal(alice)
                .contentType(MediaType.APPLICATION_JSON).content(body(text, ack, false)))
                .andExpect(request().asyncStarted());

        Mockito.verify(messageRepo).save(any(AiChatMessage.class));   // user 메시지 저장까지 진행
        ArgumentCaptor<List<LlmGuardLog>> cap = ArgumentCaptor.forClass(List.class);
        Mockito.verify(logService).write(cap.capture());
        assertEquals(LlmGuardLog.ACT_CONFIRMED, cap.getValue().get(0).getAction());
    }

    @Test
    @DisplayName("BLOCK 은 토큰이 있어도 차단된다")
    void blockIgnoresToken() throws Exception {
        String ack = errorPayload(stream(body("010-1234-5678", null, false))).get("guard").get("ackToken").asText();
        JsonNode e = errorPayload(stream(body("주민 900101-1234567", ack, false)));
        assertEquals("SENSITIVE_INPUT_BLOCKED", e.get("errorCode").asText());
        assertTrue(e.get("guard").get("ackToken") == null || e.get("guard").get("ackToken").isNull());
        assertNothingHappened();
    }

    @Test
    @DisplayName("끝에 assistant 메시지를 덧붙여도 우회되지 않는다")
    void assistantTailDoesNotBypass() throws Exception {
        JsonNode e = errorPayload(stream(body("주민 900101-1234567", null, true)));
        assertEquals("SENSITIVE_INPUT_BLOCKED", e.get("errorCode").asText());
        assertNothingHappened();
    }

    @Test
    @DisplayName("saveMessage role=user 도 점검된다 — 400 + guard, 저장 안 함")
    void saveMessageUserIsChecked() throws Exception {
        mvc.perform(post("/api/ai-chat/sessions/7/messages").principal(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"user\",\"content\":\"주민 900101-1234567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SENSITIVE_INPUT_BLOCKED"))
                .andExpect(jsonPath("$.guard.verdict").value("BLOCK"));
        Mockito.verify(messageRepo, Mockito.never()).save(any());
    }

    @Test
    @DisplayName("saveMessage role=assistant 는 다시 가려서 저장한다")
    void saveMessageAssistantIsMasked() throws Exception {
        mvc.perform(post("/api/ai-chat/sessions/7/messages").principal(alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"assistant\",\"content\":\"메일 abcde@corp.com 로\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<AiChatMessage> cap = ArgumentCaptor.forClass(AiChatMessage.class);
        Mockito.verify(messageRepo).save(cap.capture());
        assertEquals("메일 ab***@corp.com 로", cap.getValue().getContent());
    }

    @Test
    @DisplayName("이력 조회 — 배포 이전 assistant 메시지는 읽을 때 가린다(DB 값은 그대로)")
    void readTimeMasking() throws Exception {
        AiChatMessage u = new AiChatMessage();
        u.setRole("user");
        u.setContent("내 메일 abcde@corp.com");
        AiChatMessage a = new AiChatMessage();
        a.setRole("assistant");
        a.setContent("답: abcde@corp.com");
        Mockito.when(messageRepo.findBySessionIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(u, a));
        mvc.perform(get("/api/ai-chat/sessions/7/messages").principal(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("내 메일 abcde@corp.com"))
                .andExpect(jsonPath("$[1].content").value("답: ab***@corp.com"));
        assertEquals("답: abcde@corp.com", a.getContent(), "엔티티 값은 바꾸지 않는다");
    }
}

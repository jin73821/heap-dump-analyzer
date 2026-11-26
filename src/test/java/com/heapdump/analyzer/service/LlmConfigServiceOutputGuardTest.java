package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.LlmGuardLog;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.service.llmguard.LlmGuardLogService;
import com.heapdump.analyzer.service.llmguard.LlmGuardService;
import com.heapdump.analyzer.util.LlmGuardContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 출력 가림이 게이트 지점에서 <b>정확히 1회</b> 걸리는지 — 가짜 LLM(JDK HttpServer)으로 5경로를 실제 HTTP 로 돌린다.
 *
 * <p>방어 대상: ① 첨부 오버로드의 위임 분기를 또 감싸면 마스커가 겹쳐 가짜 건수·지시문 중복이 생긴다
 * ② 스트림 끝에서 붙잡아 둔 꼬리를 onChunk 로 먼저 내보내지 않으면 응답 끝부분이 화면·DB 양쪽에서 빠진다.
 */
class LlmConfigServiceOutputGuardTest {

    private static final String SENSITIVE_TEXT =
            "분석 결과 담당자 abcde@corp.com 에게 연락하세요. 비상 연락처는 010-1234-5678 입니다. "
            + "추가로 메모리 누수가 의심되는 캐시를 점검하세요. 이 문장은 홀드백보다 길게 만들기 위한 채움 문장입니다. "
            + "마지막으로 주민번호 900101-1234567 같은 값은 쓰면 안 됩니다. 끝.";

    private HttpServer server;
    private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
    private LlmConfigService llm;
    private LlmGuardLogService logService;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            boolean stream = body.contains("\"stream\":true");
            boolean analyze = body.contains("JSON");
            byte[] out;
            if (stream) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < SENSITIVE_TEXT.length(); i += 5) {
                    String piece = SENSITIVE_TEXT.substring(i, Math.min(SENSITIVE_TEXT.length(), i + 5));
                    sb.append("data: {\"choices\":[{\"delta\":{\"content\":\"").append(piece).append("\"}}]}\n\n");
                }
                sb.append("data: [DONE]\n\n");
                out = sb.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            } else {
                String content = analyze
                        ? "{\\\"summary\\\":\\\"담당 abcde@corp.com\\\",\\\"severity\\\":\\\"High\\\","
                          + "\\\"recommendations\\\":[\\\"010-1234-5678 로 연락\\\"]}"
                        : SENSITIVE_TEXT;
                out = ("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
            }
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();

        HeapDumpConfig config = new HeapDumpConfig();
        ReflectionTestUtils.setField(config, "llmTimeoutConnectSeconds", 5);
        ReflectionTestUtils.setField(config, "llmTimeoutReadSeconds", 10);
        ReflectionTestUtils.setField(config, "llmMaxInputTokens", 8000);
        ReflectionTestUtils.setField(config, "llmMaxOutputTokens", 2000);
        ReflectionTestUtils.setField(config, "llmSslVerify", true);

        LlmRateLimitService rl = new LlmRateLimitService(config);
        rl.setLlmRateLimit(false, 0, 0, 0, 0);

        logService = Mockito.mock(LlmGuardLogService.class);
        LlmGuardRuleRepository repo = Mockito.mock(LlmGuardRuleRepository.class);
        Mockito.when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(Collections.emptyList());
        LlmGuardService guard = new LlmGuardService(config, repo, logService);
        guard.init();
        guard.setSettings(true, true, true);

        llm = new LlmConfigService(config, rl, guard);
        llm.init();
        llm.setLlmEnabled(true);
        llm.setLlmConfig("gpt", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-model", 8000, 2000);
        llm.setLlmApiKey("test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        LlmGuardContext.clear();
    }

    @SuppressWarnings("unchecked")
    private List<LlmGuardLog> maskedRowsWrittenOnce() {
        ArgumentCaptor<List<LlmGuardLog>> cap = ArgumentCaptor.forClass(List.class);
        Mockito.verify(logService, Mockito.times(1)).write(cap.capture());
        List<LlmGuardLog> rows = cap.getValue();
        assertFalse(rows.isEmpty());
        for (LlmGuardLog r : rows) {
            assertEquals(LlmGuardLog.ACT_MASKED, r.getAction());
            assertEquals(LlmGuardLog.DIR_OUTPUT, r.getDirection());
            assertNotNull(r.getSample());
            assertFalse(r.getSample().contains("abcde@corp.com") || r.getSample().contains("010-1234-5678")
                    || r.getSample().contains("1234567"), "기록 발췌에 원문이 들어갔다: " + r.getSample());
        }
        return rows;
    }

    private void assertInstructionOnce() {
        assertEquals(1, requestBodies.size(), "업스트림 호출은 1회여야 한다");
        String body = requestBodies.get(0);
        int n = body.split("개인정보 보호 지침", -1).length - 1;
        assertEquals(1, n, "지시문이 " + n + "회 붙었다");
    }

    private static void assertMasked(String s) {
        assertFalse(s.contains("abcde@corp.com"), s);
        assertFalse(s.contains("010-1234-5678"), s);
        assertFalse(s.contains("900101-1234567"), s);
        assertTrue(s.contains("ab***@corp.com") && s.contains("010-****-5678") && s.contains("900101-1******"), s);
    }

    @Test
    @DisplayName("analyze — data 를 재귀로 가리고 outputGuard·MASKED 기록 1회·지시문 1회")
    @SuppressWarnings("unchecked")
    void analyze() {
        Map<String, Object> r = llm.callLlmAnalysis("== 테스트 ==\n분석해 주세요");
        assertEquals(Boolean.TRUE, r.get("success"), String.valueOf(r));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("담당 ab***@corp.com", data.get("summary"));
        assertEquals(List.of("010-****-5678 로 연락"), data.get("recommendations"));
        Map<String, Object> og = (Map<String, Object>) r.get("outputGuard");
        assertEquals(2, og.get("count"));
        assertEquals(2, maskedRowsWrittenOnce().size());
        assertInstructionOnce();
    }

    @Test
    @DisplayName("chat — text 를 가린다")
    void chat() {
        Map<String, Object> r = llm.callLlmChat(List.of(Map.of("role", "user", "content", "질문")), "sys");
        assertEquals(Boolean.TRUE, r.get("success"), String.valueOf(r));
        assertMasked((String) r.get("text"));
        assertNotNull(r.get("outputGuard"));
        maskedRowsWrittenOnce();
        assertInstructionOnce();
    }

    /** 스트림 실행 — onChunk 조각, onDone 전문, onDone 시점의 outputGuard 를 돌려준다. */
    private Object[] runStream(List<Map<String, Object>> attachments) {
        List<String> chunks = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> done = new AtomicReference<>();
        AtomicReference<Map<String, Object>> og = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        List<Map<String, String>> msgs = List.of(Map.of("role", "user", "content", "질문"));
        if (attachments == null) {
            llm.callLlmChatStream(msgs, "sys", chunks::add,
                    (ft, ms) -> { done.set(ft); og.set(LlmGuardContext.takeLastOutputGuard()); },
                    (c, m) -> error.set(c + ":" + m));
        } else {
            llm.callLlmChatStream(msgs, "sys", attachments, chunks::add,
                    (ft, ms) -> { done.set(ft); og.set(LlmGuardContext.takeLastOutputGuard()); },
                    (c, m) -> error.set(c + ":" + m));
        }
        assertNull(error.get(), error.get());
        return new Object[]{String.join("", chunks), done.get(), og.get()};
    }

    private void assertStreamGuarded(Object[] res) {
        String joined = (String) res[0];
        assertMasked(joined);
        assertTrue(joined.endsWith("끝."), "끝부분(홀드백)이 onChunk 로 나가지 않았다: " + joined);
        assertEquals(joined, res[1], "onDone 은 가린 전문을 받아야 한다(원문 아님)");
        assertNotNull(res[2], "onDone 시점에 outputGuard 가 없다");
        assertEquals(3, ((Map<?, ?>) res[2]).get("count"));
        assertEquals(3, maskedRowsWrittenOnce().size());
        assertInstructionOnce();
    }

    @Test
    @DisplayName("stream base — 조각·전문 모두 가림, 꼬리 전송, 기록·지시문 각 1회")
    void streamBase() {
        assertStreamGuarded(runStream(null));
    }

    @Test
    @DisplayName("stream 텍스트 첨부 위임 — base 가 한 번만 감싼다(이중 마스킹 없음)")
    void streamTextAttachmentDelegation() {
        String b64 = Base64.getEncoder().encodeToString("로그 내용".getBytes(StandardCharsets.UTF_8));
        assertStreamGuarded(runStream(List.of(Map.of("name", "a.log", "mediaType", "text/plain", "data", b64))));
    }

    @Test
    @DisplayName("stream 첨부 없음 위임 — 빈 첨부 목록도 base 한 번")
    void streamEmptyAttachmentDelegation() {
        assertStreamGuarded(runStream(Collections.emptyList()));
    }

    @Test
    @DisplayName("stream vision — 이미지 경로도 한 번 감싼다")
    void streamVision() {
        llm.setLlmFileAttachEnabled(true);
        assertStreamGuarded(runStream(List.of(Map.of("name", "a.png", "mediaType", "image/png", "data", "iVBORw0KGgo="))));
        assertTrue(requestBodies.get(0).contains("image_url"), "vision 경로를 타지 않았다");
    }
}

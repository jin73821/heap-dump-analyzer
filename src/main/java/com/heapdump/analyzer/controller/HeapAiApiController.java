package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.service.EmbeddingService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.RagService;
import com.heapdump.analyzer.util.FilenameValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM (AI) + RAG (Elasticsearch) API (Phase 4B-2).
 */
@Controller
public class HeapAiApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapAiApiController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final HeapDumpConfig config;
    private final RagService ragService;
    private final EmbeddingService embeddingService;

    public HeapAiApiController(HeapDumpAnalyzerService analyzerService,
                               HeapDumpConfig config,
                               RagService ragService,
                               EmbeddingService embeddingService) {
        this.analyzerService = analyzerService;
        this.config = config;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
    }

    // ── LLM 기본 설정/연결 ────────────────────────────────────────

    @PostMapping("/api/llm/enabled")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmEnabled(@RequestParam boolean enabled) {
        analyzerService.setLlmEnabled(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("enabled", enabled);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/config")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmConfig(@RequestBody Map<String, Object> body) {
        String provider = (String) body.getOrDefault("provider", analyzerService.getLlmProvider());
        String apiUrl = (String) body.get("apiUrl");
        String model = (String) body.get("model");
        int maxIn = body.containsKey("maxInputTokens")
                ? Integer.parseInt(String.valueOf(body.get("maxInputTokens")))
                : analyzerService.getLlmMaxInputTokens();
        int maxOut = body.containsKey("maxOutputTokens")
                ? Integer.parseInt(String.valueOf(body.get("maxOutputTokens")))
                : analyzerService.getLlmMaxOutputTokens();

        if (apiUrl == null || apiUrl.isEmpty()) {
            apiUrl = analyzerService.getDefaultApiUrl(provider);
        }
        if (model == null) model = analyzerService.getLlmModel();

        analyzerService.setLlmConfig(provider, apiUrl, model, maxIn, maxOut);

        if (body.containsKey("sslVerify")) {
            boolean sslVerify = Boolean.parseBoolean(String.valueOf(body.get("sslVerify")));
            analyzerService.setLlmSslVerify(sslVerify);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("provider", provider);
        resp.put("apiUrl", apiUrl);
        resp.put("model", model);
        resp.put("sslVerify", analyzerService.isLlmSslVerify());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/apikey")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmApiKey(@RequestBody Map<String, String> body) {
        String key = body.get("apiKey");
        if (key == null) key = "";
        analyzerService.setLlmApiKey(key.trim());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("apiKeySet", analyzerService.isLlmApiKeySet());
        resp.put("apiKeyMasked", analyzerService.getLlmApiKeyMasked());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/test-connection")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testLlmConnection() {
        Map<String, Object> result = analyzerService.testLlmConnection();
        return ResponseEntity.ok(result);
    }

    // ── AI 분석/인사이트 ──────────────────────────────────────────

    @PostMapping("/api/llm/analyze")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> analyzeLlm(@RequestBody Map<String, Object> body) {
        String prompt = (String) body.get("prompt");
        String filename = (String) body.get("filename");
        Boolean save = body.get("save") instanceof Boolean ? (Boolean) body.get("save") : true;

        if (prompt == null || prompt.trim().isEmpty()) {
            logger.warn("[AI-Insight] 분석 요청 거부 — 프롬프트 비어있음 (file={})", filename);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "EMPTY_PROMPT");
            err.put("error", "분석 프롬프트가 비어있습니다. 페이지에서 힙 분석 결과 데이터를 찾을 수 없습니다. 덤프 분석이 완료된 후 AI 분석을 실행하세요.");
            return ResponseEntity.badRequest().body(err);
        }
        if (!prompt.contains("==") || prompt.trim().length() < 50) {
            logger.warn("[AI-Insight] 분석 요청 경고 — 프롬프트 데이터 부족 (file={}, len={})", filename, prompt.length());
        }

        // OOM 감지 시 prompt 헤더 직후, 첫 '== ... ==' 섹션 직전에 OOM 블록 splice (마지막 JSON 스키마 지시문은 그대로 prompt 끝에 유지)
        String oomSection = analyzerService.buildOomPromptSection(filename);
        if (!oomSection.isEmpty()) {
            int firstSection = prompt.indexOf("\n== ");
            if (firstSection > 0) {
                prompt = prompt.substring(0, firstSection) + "\n\n" + oomSection + prompt.substring(firstSection);
            } else {
                prompt = prompt + "\n\n" + oomSection;
            }
            logger.info("[AI-Insight] OOM context injected: {} char(s)", oomSection.length());
        }

        logger.info("[AI-Insight][REQ] 분석 요청 수신 — file='{}', promptLen={} chars, save={}, provider={}",
            filename, prompt.length(), save, analyzerService.getLlmProvider());

        long reqStart = System.currentTimeMillis();
        Map<String, Object> result = analyzerService.callLlmAnalysis(prompt);
        long totalElapsed = System.currentTimeMillis() - reqStart;

        boolean success = Boolean.TRUE.equals(result.get("success"));
        String errorCode = (String) result.get("errorCode");
        Object dataObj = result.get("data");
        String severity = null;
        if (dataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dm = (Map<String, Object>) dataObj;
            severity = (String) dm.get("severity");
        }
        if (success) {
            logger.info("[AI-Insight][RESULT] 분석 성공 — file='{}', severity={}, totalElapsed={}ms, model={}",
                filename, severity, totalElapsed, result.get("model"));
        } else {
            logger.warn("[AI-Insight][RESULT] 분석 실패 — file='{}', errorCode={}, totalElapsed={}ms, error={}",
                filename, errorCode, totalElapsed, result.get("error"));
        }

        if (success && filename != null && !filename.isEmpty() && Boolean.TRUE.equals(save)) {
            Map<String, Object> toStore = new LinkedHashMap<>();
            toStore.put("model", result.get("model"));
            toStore.put("latencyMs", result.get("latencyMs"));
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                toStore.putAll(dataMap);
            }
            try {
                analyzerService.saveAiInsight(filename, toStore);
                result.put("saved", true);
                result.put("savedTo", "database");
                logger.info("[AI-Insight][SAVE] 저장 완료 — file='{}', severity={}", filename, severity);
            } catch (Exception saveEx) {
                logger.error("[AI-Insight][SAVE] 저장 실패 — file='{}', type={}, msg={}",
                    filename, saveEx.getClass().getSimpleName(), saveEx.getMessage());
                result.put("saved", false);
                result.put("saveError", saveEx.getMessage());
                result.put("saveErrorCode", "SAVE_FAILED");
                result.put("retryPayload", toStore);
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * AI 인사이트 수동 저장 (자동 저장 실패 시 재시도용).
     */
    @PostMapping("/api/llm/insight/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveAiInsightManual(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String filename = (String) body.get("filename");
        Object dataObj = body.get("insightData");

        if (filename == null || filename.trim().isEmpty()) {
            logger.warn("[AI-Insight][SAVE-RETRY] 거부 — filename 누락");
            resp.put("success", false);
            resp.put("errorCode", "MISSING_FILENAME");
            resp.put("error", "filename 이 비어있습니다.");
            return ResponseEntity.badRequest().body(resp);
        }
        if (!(dataObj instanceof Map)) {
            logger.warn("[AI-Insight][SAVE-RETRY] 거부 — insightData 누락 또는 형식 오류 (file='{}')", filename);
            resp.put("success", false);
            resp.put("errorCode", "MISSING_PAYLOAD");
            resp.put("error", "insightData 가 비어있거나 형식이 올바르지 않습니다.");
            return ResponseEntity.badRequest().body(resp);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> insightData = (Map<String, Object>) dataObj;

        logger.info("[AI-Insight][SAVE-RETRY] 수동 저장 요청 — file='{}', severity={}, model={}",
            filename, insightData.get("severity"), insightData.get("model"));

        try {
            analyzerService.saveAiInsight(filename, insightData);
            logger.info("[AI-Insight][SAVE-RETRY] 수동 저장 성공 — file='{}'", filename);
            resp.put("success", true);
            resp.put("savedTo", "database");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            logger.error("[AI-Insight][SAVE-RETRY] 수동 저장 실패 — file='{}', type={}, msg={}",
                filename, e.getClass().getSimpleName(), e.getMessage());
            resp.put("success", false);
            resp.put("errorCode", "SAVE_FAILED");
            resp.put("error", e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @GetMapping("/api/llm/insight/{filename}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAiInsight(@PathVariable String filename) {
        logger.debug("[AI-Insight][LOAD] 저장된 인사이트 조회 시작 — file='{}'", filename);
        Map<String, Object> insight = analyzerService.loadAiInsight(filename);
        if (insight == null) {
            logger.debug("[AI-Insight][LOAD] 저장된 인사이트 없음 — file='{}'", filename);
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("found", false);
            return ResponseEntity.ok(notFound);
        }
        logger.info("[AI-Insight][LOAD] 인사이트 로드 성공 — file='{}', severity={}, analysedAt={}",
            filename, insight.get("severity"), insight.get("analysedAt"));
        insight.put("found", true);
        insight.put("savedTo", "database");
        return ResponseEntity.ok(insight);
    }

    @DeleteMapping("/api/llm/insight/{filename}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteAiInsight(@PathVariable String filename) {
        logger.info("[AI-Insight][DELETE] 인사이트 삭제 요청 — file='{}'", filename);
        boolean deleted = analyzerService.deleteAiInsight(filename);
        if (deleted) {
            logger.info("[AI-Insight][DELETE] 삭제 완료 — file='{}'", filename);
        } else {
            logger.warn("[AI-Insight][DELETE] 삭제 대상 없음 또는 실패 — file='{}'", filename);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", deleted);
        return ResponseEntity.ok(res);
    }

    // ── Compare AI ────────────────────────────────────────────────

    /**
     * Compare 전용 AI 분석. AiInsightManager 를 합성 키로 재사용.
     * compareKey = "__compare__:" + sha256(base + "|" + target).substring(0, 40)
     */
    @PostMapping("/api/llm/compare/analyze")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> analyzeCompareLlm(@RequestBody Map<String, Object> body) {
        String base   = (String) body.get("base");
        String target = (String) body.get("target");
        String prompt = (String) body.get("prompt");
        Boolean save  = body.get("save") instanceof Boolean ? (Boolean) body.get("save") : true;

        if (base == null || target == null || base.isEmpty() || target.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "MISSING_PARAMS");
            err.put("error", "base, target 두 파라미터가 모두 필요합니다.");
            return ResponseEntity.badRequest().body(err);
        }
        base   = FilenameValidator.validate(base);
        target = FilenameValidator.validate(target);

        if (prompt == null || prompt.trim().isEmpty()) {
            logger.warn("[AI-Compare] 분석 요청 거부 — 프롬프트 비어있음 (base='{}', target='{}')", base, target);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "EMPTY_PROMPT");
            err.put("error", "분석 프롬프트가 비어있습니다.");
            return ResponseEntity.badRequest().body(err);
        }
        if (!prompt.contains("==") || prompt.trim().length() < 50) {
            logger.warn("[AI-Compare] 프롬프트 데이터 부족 (base='{}', target='{}', len={})", base, target, prompt.length());
        }

        String key = compareKey(base, target);
        logger.info("[AI-Compare][REQ] base='{}', target='{}', key='{}', promptLen={}, save={}",
            base, target, key, prompt.length(), save);

        long reqStart = System.currentTimeMillis();
        Map<String, Object> result = analyzerService.callLlmAnalysis(prompt);
        long totalElapsed = System.currentTimeMillis() - reqStart;

        boolean success = Boolean.TRUE.equals(result.get("success"));
        Object dataObj  = result.get("data");
        String severity = null;
        if (dataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dm = (Map<String, Object>) dataObj;
            severity = (String) dm.get("severity");
        }
        if (success) {
            logger.info("[AI-Compare][RESULT] 성공 — key='{}', severity={}, elapsed={}ms, model={}",
                key, severity, totalElapsed, result.get("model"));
        } else {
            logger.warn("[AI-Compare][RESULT] 실패 — key='{}', errorCode={}, elapsed={}ms, error={}",
                key, result.get("errorCode"), totalElapsed, result.get("error"));
        }

        if (success && Boolean.TRUE.equals(save)) {
            Map<String, Object> toStore = new LinkedHashMap<>();
            toStore.put("model",     result.get("model"));
            toStore.put("latencyMs", result.get("latencyMs"));
            toStore.put("compareBase",   base);
            toStore.put("compareTarget", target);
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                toStore.putAll(dataMap);
            }
            try {
                analyzerService.saveAiInsight(key, toStore);
                result.put("saved", true);
                result.put("savedTo", "database");
                result.put("analysedAt", System.currentTimeMillis());
            } catch (Exception saveEx) {
                logger.error("[AI-Compare][SAVE] 저장 실패 — key='{}', msg={}", key, saveEx.getMessage());
                result.put("saved", false);
                result.put("saveError", saveEx.getMessage());
                result.put("saveErrorCode", "SAVE_FAILED");
                result.put("retryPayload", toStore);
            }
        }
        result.put("base", base);
        result.put("target", target);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/llm/compare/insight")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCompareInsight(
            @RequestParam String base,
            @RequestParam String target) {
        base   = FilenameValidator.validate(base);
        target = FilenameValidator.validate(target);
        String key = compareKey(base, target);
        Map<String, Object> insight = analyzerService.loadAiInsight(key);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (insight == null) {
            resp.put("found", false);
            resp.put("base", base);
            resp.put("target", target);
            return ResponseEntity.ok(resp);
        }
        insight.put("found", true);
        insight.put("savedTo", "database");
        insight.put("base", base);
        insight.put("target", target);
        return ResponseEntity.ok(insight);
    }

    @DeleteMapping("/api/llm/compare/insight")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteCompareInsight(
            @RequestParam String base,
            @RequestParam String target) {
        base   = FilenameValidator.validate(base);
        target = FilenameValidator.validate(target);
        String key = compareKey(base, target);
        logger.info("[AI-Compare][DELETE] key='{}' (base='{}', target='{}')", key, base, target);
        boolean deleted = analyzerService.deleteAiInsight(key);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", deleted);
        res.put("base", base);
        res.put("target", target);
        return ResponseEntity.ok(res);
    }

    /** "__compare__:" + sha256(base+"|"+target).substring(0,40). 순서 바뀌면 다른 키. */
    private static String compareKey(String base, String target) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((base + "|" + target).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return "__compare__:" + sb.substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            return "__compare__:" + Math.abs((base + "|" + target).hashCode());
        }
    }

    // ── AI Chat ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @PostMapping("/api/llm/chat")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> aiChat(@RequestBody Map<String, Object> body) {
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        String context = body.get("context") != null ? String.valueOf(body.get("context")) : "";
        String filename = body.get("filename") != null ? String.valueOf(body.get("filename")) : "";

        if (messages == null || messages.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "EMPTY_MESSAGES");
            err.put("error", "메시지가 비어있습니다.");
            return ResponseEntity.badRequest().body(err);
        }

        logger.info("[AI-Chat][REQ] 채팅 요청 — file='{}', messageCount={}, contextLen={}",
            filename, messages.size(), context.length());

        String systemPrompt = analyzerService.getLlmChatSystemPrompt();
        if (!context.trim().isEmpty()) {
            systemPrompt += "\n\n아래는 사용자가 현재 보고 있는 힙 덤프 분석 결과입니다. "
                + "이 데이터를 참고하여 질문에 답하세요:\n\n" + context;
        }
        Map<String, String> lastMsg = messages.get(messages.size() - 1);
        if (lastMsg != null && "user".equals(lastMsg.get("role"))) {
            String ragContext = ragService.fetchContextForLlm(lastMsg.get("content"));
            if (!ragContext.isEmpty()) systemPrompt += ragContext;
        }
        String oomChatSection = analyzerService.buildOomPromptSection(filename);
        if (!oomChatSection.isEmpty()) {
            systemPrompt += "\n\n" + oomChatSection;
            logger.info("[AI-Chat] OOM context injected: {} char(s)", oomChatSection.length());
        }

        Map<String, Object> result = analyzerService.callLlmChat(messages, systemPrompt);

        if (Boolean.TRUE.equals(result.get("success"))) {
            logger.info("[AI-Chat][RESULT] 응답 완료 — model={}, latency={}ms",
                result.get("model"), result.get("latencyMs"));
        } else {
            logger.warn("[AI-Chat][RESULT] 실패 — errorCode={}, error={}",
                result.get("errorCode"), result.get("error"));
        }

        return ResponseEntity.ok(result);
    }

    @SuppressWarnings("unchecked")
    @PostMapping(value = "/api/llm/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter aiChatStream(@RequestBody Map<String, Object> body) {
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        String context = body.get("context") != null ? String.valueOf(body.get("context")) : "";
        String filename = body.get("filename") != null ? String.valueOf(body.get("filename")) : "";

        SseEmitter emitter = new SseEmitter(config.getSseEmitterTimeoutMinutes() * 60L * 1000);

        if (messages == null || messages.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().name("error")
                    .data("{\"errorCode\":\"EMPTY_MESSAGES\",\"error\":\"메시지가 비어있습니다.\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        logger.info("[AI-Chat-Stream][REQ] 스트리밍 채팅 요청 — file='{}', messageCount={}", filename, messages.size());

        String systemPrompt = analyzerService.getLlmChatSystemPrompt();
        if (!context.trim().isEmpty()) {
            systemPrompt += "\n\n아래는 사용자가 현재 보고 있는 힙 덤프 분석 결과입니다. "
                + "이 데이터를 참고하여 질문에 답하세요:\n\n" + context;
        }
        Map<String, String> lastStreamMsg = messages.get(messages.size() - 1);
        if (lastStreamMsg != null && "user".equals(lastStreamMsg.get("role"))) {
            String ragContext = ragService.fetchContextForLlm(lastStreamMsg.get("content"));
            if (!ragContext.isEmpty()) systemPrompt += ragContext;
        }
        String oomStreamSection = analyzerService.buildOomPromptSection(filename);
        if (!oomStreamSection.isEmpty()) {
            systemPrompt += "\n\n" + oomStreamSection;
            logger.info("[AI-Chat-Stream] OOM context injected: {} char(s)", oomStreamSection.length());
        }

        final String finalSystemPrompt = systemPrompt;
        final String model = analyzerService.getLlmModel();

        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                    .data("{\"model\":\"" + (model != null ? model : "") + "\"}"));

                analyzerService.callLlmChatStream(messages, finalSystemPrompt,
                    chunk -> {
                        try {
                            String escaped = chunk.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t");
                            emitter.send(SseEmitter.event().name("chunk")
                                .data("{\"text\":\"" + escaped + "\"}"));
                        } catch (Exception e) {
                            // 클라이언트 disconnect
                        }
                    },
                    (fullText, latencyMs) -> {
                        try {
                            emitter.send(SseEmitter.event().name("done")
                                .data("{\"latencyMs\":" + latencyMs + "}"));
                            emitter.complete();
                        } catch (Exception ignored) {}
                    },
                    (errorCode, errorMsg) -> {
                        try {
                            String escapedMsg = errorMsg.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n");
                            emitter.send(SseEmitter.event().name("error")
                                .data("{\"errorCode\":\"" + errorCode + "\",\"error\":\"" + escapedMsg + "\"}"));
                            emitter.complete();
                        } catch (Exception ignored) {}
                    }
                );
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data("{\"errorCode\":\"INTERNAL_ERROR\",\"error\":\"" + e.getMessage() + "\"}"));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        }, "ai-chat-stream-" + System.currentTimeMillis()).start();

        emitter.onTimeout(() -> {
            logger.warn("[AI-Chat-Stream] 타임아웃 — file='{}'", filename);
            emitter.complete();
        });

        return emitter;
    }

    @PostMapping("/api/llm/chat-prompt")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveChatPrompt(@RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        analyzerService.setLlmChatSystemPrompt(prompt);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("prompt", analyzerService.getLlmChatSystemPrompt());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/llm/chat-restore-mode")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setChatRestoreMode(@RequestBody Map<String, Object> body) {
        boolean include = Boolean.TRUE.equals(body.get("includeHistory"));
        analyzerService.setLlmChatRestoreIncludeHistory(include);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("includeHistory", analyzerService.isLlmChatRestoreIncludeHistory());
        return ResponseEntity.ok(res);
    }

    // ── RAG (Elasticsearch) ───────────────────────────────────────

    @GetMapping("/api/settings/rag")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRagSettings() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", analyzerService.isRagEnabled());
        res.put("url", analyzerService.getRagElasticsearchUrl());
        res.put("authType", analyzerService.getRagAuthType());
        res.put("username", analyzerService.getRagUsername());
        res.put("passwordSet", analyzerService.isRagPasswordSet());
        res.put("passwordMasked", analyzerService.getRagPasswordMasked());
        res.put("apiKeySet", analyzerService.isRagApiKeySet());
        res.put("apiKeyMasked", analyzerService.getRagApiKeyMasked());
        res.put("index", analyzerService.getRagIndex());
        res.put("sslVerify", analyzerService.isRagSslVerify());
        res.put("searchMode", analyzerService.getRagSearchMode());
        res.put("textField", analyzerService.getRagTextField());
        res.put("topK", analyzerService.getRagTopK());
        res.put("minScore", analyzerService.getRagMinScore());
        res.put("timeoutSeconds", analyzerService.getRagTimeoutSeconds());
        Map<String, Object> chunking = new LinkedHashMap<>();
        chunking.put("enabled", analyzerService.isRagChunkingEnabled());
        chunking.put("strategy", analyzerService.getRagChunkingStrategy());
        chunking.put("size", analyzerService.getRagChunkingSize());
        chunking.put("overlap", analyzerService.getRagChunkingOverlap());
        chunking.put("maxChunksPerDoc", analyzerService.getRagChunkingMaxChunksPerDoc());
        chunking.put("maxTotalChars", analyzerService.getRagChunkingMaxTotalChars());
        res.put("chunking", chunking);

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("queryType", analyzerService.getRagSemanticQueryType());
        semantic.put("modelId", analyzerService.getRagSemanticModelId());
        semantic.put("tokensField", analyzerService.getRagSemanticTokensField());
        semantic.put("semanticField", analyzerService.getRagSemanticField());
        res.put("semantic", semantic);

        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", analyzerService.getRagEmbeddingProvider());
        embedding.put("apiUrl", analyzerService.getRagEmbeddingApiUrl());
        embedding.put("apiKeySet", analyzerService.isRagEmbeddingApiKeySet());
        embedding.put("apiKeyMasked", analyzerService.getRagEmbeddingApiKeyMasked());
        embedding.put("model", analyzerService.getRagEmbeddingModel());
        embedding.put("dimension", analyzerService.getRagEmbeddingDimension());
        embedding.put("timeoutSeconds", analyzerService.getRagEmbeddingTimeoutSeconds());
        embedding.put("vectorField", analyzerService.getRagKnnVectorField());
        embedding.put("numCandidates", analyzerService.getRagKnnNumCandidates());
        res.put("embedding", embedding);

        res.put("availableModes", Arrays.asList("keyword", "semantic-server", "semantic-client"));
        res.put("availableAuthTypes", Arrays.asList("none", "basic", "api-key"));
        res.put("availableChunkingStrategies", Arrays.asList("fixed", "paragraph", "sentence"));
        res.put("availableSemanticQueryTypes", Arrays.asList("text_expansion", "semantic"));
        res.put("availableEmbeddingProviders", Arrays.asList("openai", "cohere", "custom"));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/settings/rag/chunking")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setRagChunking(@RequestBody Map<String, Object> body) {
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        String strategy = (String) body.getOrDefault("strategy", "fixed");
        int size      = parseInt(body.get("size"), 800);
        int overlap   = parseInt(body.get("overlap"), 120);
        int maxPerDoc = parseInt(body.get("maxChunksPerDoc"), 3);
        int maxTotal  = parseInt(body.get("maxTotalChars"), 6000);
        analyzerService.setRagChunkingConfig(enabled, strategy, size, overlap, maxPerDoc, maxTotal);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("enabled", analyzerService.isRagChunkingEnabled());
        res.put("strategy", analyzerService.getRagChunkingStrategy());
        res.put("size", analyzerService.getRagChunkingSize());
        res.put("overlap", analyzerService.getRagChunkingOverlap());
        res.put("maxChunksPerDoc", analyzerService.getRagChunkingMaxChunksPerDoc());
        res.put("maxTotalChars", analyzerService.getRagChunkingMaxTotalChars());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/settings/rag/enabled")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setRagEnabled(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        analyzerService.setRagEnabled(enabled);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("enabled", analyzerService.isRagEnabled());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/settings/rag")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setRagConfig(@RequestBody Map<String, Object> body) {
        String url        = (String) body.getOrDefault("url", "");
        String authType   = (String) body.getOrDefault("authType", "none");
        String username   = (String) body.getOrDefault("username", "");
        String index      = (String) body.getOrDefault("index", "");
        boolean sslVerify = !Boolean.FALSE.equals(body.get("sslVerify"));
        String searchMode = (String) body.getOrDefault("searchMode", "keyword");
        String textField  = (String) body.getOrDefault("textField", "content");
        int topK          = parseInt(body.get("topK"), 3);
        double minScore   = parseDouble(body.get("minScore"), 0.0);
        int timeoutSec    = parseInt(body.get("timeoutSeconds"), 10);

        // password/apiKey: 키 자체가 없거나 null이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 갱신
        String password = body.containsKey("password") ? (String) body.get("password") : null;
        String apiKey   = body.containsKey("apiKey")   ? (String) body.get("apiKey")   : null;

        analyzerService.setRagConfig(url, authType, username, password, apiKey, index, sslVerify,
                searchMode, textField, topK, minScore, timeoutSec);

        String semQueryType    = body.containsKey("semanticQueryType")    ? (String) body.get("semanticQueryType")    : null;
        String semModelId      = body.containsKey("semanticModelId")      ? (String) body.get("semanticModelId")      : null;
        String semTokensField  = body.containsKey("semanticTokensField")  ? (String) body.get("semanticTokensField")  : null;
        String semField        = body.containsKey("semanticField")        ? (String) body.get("semanticField")        : null;
        if (semQueryType != null || semModelId != null || semTokensField != null || semField != null) {
            analyzerService.setRagSemanticConfig(semQueryType, semModelId, semTokensField, semField);
        }

        String embProvider    = body.containsKey("embeddingProvider") ? (String) body.get("embeddingProvider") : null;
        String embApiUrl      = body.containsKey("embeddingApiUrl")   ? (String) body.get("embeddingApiUrl")   : null;
        String embApiKey      = body.containsKey("embeddingApiKey")   ? (String) body.get("embeddingApiKey")   : null;
        String embModel       = body.containsKey("embeddingModel")    ? (String) body.get("embeddingModel")    : null;
        int    embDim         = body.containsKey("embeddingDimension")    ? parseInt(body.get("embeddingDimension"), -1)    : -1;
        int    embTimeout     = body.containsKey("embeddingTimeoutSeconds") ? parseInt(body.get("embeddingTimeoutSeconds"), -1) : -1;
        String knnVectorField = body.containsKey("knnVectorField")    ? (String) body.get("knnVectorField")    : null;
        int    knnCandidates  = body.containsKey("knnNumCandidates")  ? parseInt(body.get("knnNumCandidates"), -1)  : -1;
        if (embProvider != null || embApiUrl != null || embApiKey != null || embModel != null
                || embDim > 0 || embTimeout > 0 || knnVectorField != null || knnCandidates > 0) {
            analyzerService.setRagEmbeddingConfig(embProvider, embApiUrl, embApiKey, embModel,
                    embDim, embTimeout, knnVectorField, knnCandidates);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("url", analyzerService.getRagElasticsearchUrl());
        res.put("index", analyzerService.getRagIndex());
        res.put("searchMode", analyzerService.getRagSearchMode());
        res.put("passwordSet", analyzerService.isRagPasswordSet());
        res.put("apiKeySet", analyzerService.isRagApiKeySet());
        res.put("embeddingApiKeySet", analyzerService.isRagEmbeddingApiKeySet());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/settings/rag/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testRagConnection(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = ragService.testConnection(body);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/settings/rag/search")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> ragSearchProbe(@RequestBody Map<String, Object> body) {
        String query = (String) body.getOrDefault("query", "");
        if (query.trim().isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "query 파라미터가 비어있습니다.");
            return ResponseEntity.badRequest().body(err);
        }
        Map<String, Object> result = ragService.search(query, null);
        return ResponseEntity.ok(result);
    }

    /**
     * Phase 2 — 임베딩 API 연결 테스트.
     * 페이로드의 apiKey가 비어 있거나 누락되면 저장된 키 사용.
     */
    @PostMapping("/api/settings/rag/embedding/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testEmbedding(@RequestBody(required = false) Map<String, Object> body) {
        if (body != null && body.containsKey("apiKey")) {
            Object v = body.get("apiKey");
            if (v == null || String.valueOf(v).trim().isEmpty()) body.remove("apiKey");
        }
        Map<String, Object> result = embeddingService.testConnection(body);
        return ResponseEntity.ok(result);
    }

    private static int parseInt(Object v, int fallback) {
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return fallback; }
    }
    private static double parseDouble(Object v, double fallback) {
        if (v == null) return fallback;
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return fallback; }
    }
}

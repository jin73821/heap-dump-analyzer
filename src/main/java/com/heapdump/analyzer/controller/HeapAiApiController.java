package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.service.AiInsightManager;
import com.heapdump.analyzer.service.ChromaSearchService;
import com.heapdump.analyzer.service.RagKnowledgeExportService;
import com.heapdump.analyzer.service.EmbeddingService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.LlmConfigService;
import com.heapdump.analyzer.service.LlmRateLimitService;
import com.heapdump.analyzer.service.RagConfigService;
import com.heapdump.analyzer.service.RagService;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.SseJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

    /** GC 로그 요약 주입(2026-09-14) — 선택 주입. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.heapdump.analyzer.service.GcLogAnalyzerService gcLogAnalyzerService;


    private static final Logger logger = LoggerFactory.getLogger(HeapAiApiController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final LlmConfigService llmConfig;
    private final LlmRateLimitService rateLimitService;
    private final RagConfigService ragConfig;
    private final AiInsightManager aiInsight;
    private final HeapDumpConfig config;
    private final RagService ragService;
    private final EmbeddingService embeddingService;
    private final ChromaSearchService chromaSearchService;
    private final RagKnowledgeExportService knowledgeExport;

    public HeapAiApiController(HeapDumpAnalyzerService analyzerService,
                               LlmConfigService llmConfig,
                               LlmRateLimitService rateLimitService,
                               RagConfigService ragConfig,
                               AiInsightManager aiInsight,
                               HeapDumpConfig config,
                               RagService ragService,
                               EmbeddingService embeddingService,
                               ChromaSearchService chromaSearchService,
                               RagKnowledgeExportService knowledgeExport) {
        this.analyzerService = analyzerService;
        this.llmConfig = llmConfig;
        this.rateLimitService = rateLimitService;
        this.ragConfig = ragConfig;
        this.aiInsight = aiInsight;
        this.config = config;
        this.ragService = ragService;
        this.embeddingService = embeddingService;
        this.chromaSearchService = chromaSearchService;
        this.knowledgeExport = knowledgeExport;
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
        String provider = (String) body.getOrDefault("provider", llmConfig.getLlmProvider());
        String apiUrl = (String) body.get("apiUrl");
        String model = (String) body.get("model");
        int maxIn = body.containsKey("maxInputTokens")
                ? Integer.parseInt(String.valueOf(body.get("maxInputTokens")))
                : llmConfig.getLlmMaxInputTokens();
        int maxOut = body.containsKey("maxOutputTokens")
                ? Integer.parseInt(String.valueOf(body.get("maxOutputTokens")))
                : llmConfig.getLlmMaxOutputTokens();

        if (apiUrl == null || apiUrl.isEmpty()) {
            apiUrl = llmConfig.getDefaultApiUrl(provider);
        }
        if (model == null) model = llmConfig.getLlmModel();

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
        resp.put("sslVerify", llmConfig.isLlmSslVerify());
        resp.put("fileAttachEnabled", llmConfig.isLlmFileAttachEnabled());
        resp.put("fileAttachCapable", llmConfig.isFileAttachCapable());
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
        resp.put("apiKeySet", llmConfig.isLlmApiKeySet());
        resp.put("apiKeyMasked", llmConfig.getLlmApiKeyMasked());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/test-connection")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testLlmConnection() {
        Map<String, Object> result = llmConfig.testLlmConnection();
        return LlmRateLimitService.toResponse(result);
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
        // 매칭된 GC 로그 요약(2026-09-14) — OOM 블록과 같은 자리(첫 '== ' 섹션 앞)에 끼운다. 미연결이면 빈 문자열.
        String gcSection = gcLogAnalyzerService == null ? "" : gcLogAnalyzerService.buildGcPromptSectionForDump(filename);
        if (!gcSection.isEmpty()) {
            int firstSection = prompt.indexOf("\n== ");
            if (firstSection > 0) {
                prompt = prompt.substring(0, firstSection) + "\n\n" + gcSection + prompt.substring(firstSection);
            } else {
                prompt = prompt + "\n\n" + gcSection;
            }
            logger.info("[AI-Insight] GC context injected: {} char(s)", gcSection.length());
        }

        logger.info("[AI-Insight][REQ] 분석 요청 수신 — file='{}', promptLen={} chars, save={}, provider={}",
            filename, prompt.length(), save, llmConfig.getLlmProvider());

        long reqStart = System.currentTimeMillis();
        Map<String, Object> result = llmConfig.callLlmAnalysis(prompt);
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
                aiInsight.saveAiInsight(filename, toStore);
                result.put("saved", true);
                result.put("savedTo", "database");
                // saveAiInsight 가 toStore 에 스탬프한 분석 시각을 응답에 실어 신규 완료 즉시 표시
                if (toStore.get("analysedAt") != null) result.put("analysedAt", toStore.get("analysedAt"));
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
        return LlmRateLimitService.toResponse(result);
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
            aiInsight.saveAiInsight(filename, insightData);
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
        Map<String, Object> insight = aiInsight.loadAiInsight(filename);
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
        boolean deleted = aiInsight.deleteAiInsight(filename);
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
        Map<String, Object> result = llmConfig.callLlmAnalysis(prompt);
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
                aiInsight.saveAiInsight(key, toStore);
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
        return LlmRateLimitService.toResponse(result);
    }

    @GetMapping("/api/llm/compare/insight")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCompareInsight(
            @RequestParam String base,
            @RequestParam String target) {
        base   = FilenameValidator.validate(base);
        target = FilenameValidator.validate(target);
        String key = compareKey(base, target);
        Map<String, Object> insight = aiInsight.loadAiInsight(key);
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
        boolean deleted = aiInsight.deleteAiInsight(key);
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

        String systemPrompt = llmConfig.getLlmChatSystemPrompt();
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

        Map<String, Object> result = llmConfig.callLlmChat(messages, systemPrompt);

        if (Boolean.TRUE.equals(result.get("success"))) {
            logger.info("[AI-Chat][RESULT] 응답 완료 — model={}, latency={}ms",
                result.get("model"), result.get("latencyMs"));
        } else {
            logger.warn("[AI-Chat][RESULT] 실패 — errorCode={}, error={}",
                result.get("errorCode"), result.get("error"));
        }

        return LlmRateLimitService.toResponse(result);
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
            SseJson.sendError(emitter, "EMPTY_MESSAGES", "메시지가 비어있습니다.");
            return emitter;
        }

        logger.info("[AI-Chat-Stream][REQ] 스트리밍 채팅 요청 — file='{}', messageCount={}", filename, messages.size());

        String systemPrompt = llmConfig.getLlmChatSystemPrompt();
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
        final String model = llmConfig.getLlmModel();

        // ⚠ DelegatingSecurityContextRunnable 필수 — 호출량 게이트가 SecurityContextHolder 로
        //   사용자를 식별하는데, 맨 Runnable 로 스레드를 띄우면 컨텍스트가 전파되지 않아
        //   모든 사용자가 "system" 버킷을 공유하게 된다.
        new Thread(new org.springframework.security.concurrent.DelegatingSecurityContextRunnable(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                    .data("{\"model\":\"" + (model != null ? model : "") + "\"}"));

                llmConfig.callLlmChatStream(messages, finalSystemPrompt,
                    chunk -> {
                        try {
                            emitter.send(SseEmitter.event().name("chunk").data(SseJson.chunk(chunk)));
                        } catch (Exception e) {
                            logger.debug("[AI-Chat SSE] chunk 전송 실패(클라이언트 disconnect 추정): {}", e.toString());
                        }
                    },
                    (fullText, latencyMs) -> {
                        try {
                            emitter.send(SseEmitter.event().name("done")
                                .data("{\"latencyMs\":" + latencyMs + "}"));
                            emitter.complete();
                        } catch (Exception e) {
                            logger.debug("[AI-Chat SSE] done 전송 실패(클라이언트 disconnect 추정): {}", e.toString());
                        }
                    },
                    (errorCode, errorMsg) -> SseJson.sendError(emitter, errorCode, errorMsg)
                );
            } catch (Exception e) {
                SseJson.sendError(emitter, "INTERNAL_ERROR", e.getMessage());
            }
        }), "ai-chat-stream-" + System.currentTimeMillis()).start();

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
        res.put("prompt", llmConfig.getLlmChatSystemPrompt());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/llm/chat-restore-mode")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setChatRestoreMode(@RequestBody Map<String, Object> body) {
        boolean include = Boolean.TRUE.equals(body.get("includeHistory"));
        analyzerService.setLlmChatRestoreIncludeHistory(include);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("includeHistory", llmConfig.isLlmChatRestoreIncludeHistory());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/llm/file-attach")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setFileAttachEnabled(@RequestParam boolean enabled) {
        analyzerService.setLlmFileAttachEnabled(enabled);
        logger.info("[LLM-Config] fileAttachEnabled={}, provider={}", enabled, llmConfig.getLlmProvider());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("fileAttachEnabled", llmConfig.isLlmFileAttachEnabled());
        res.put("fileAttachCapable", llmConfig.isFileAttachCapable());
        return ResponseEntity.ok(res);
    }

    /**
     * LLM 호출량 제한 설정 (ADMIN + CSRF).
     * 각 값 0 = 무제한. SecurityConfig 의 authorize/csrf 두 매처에 1:1 등록돼 있다.
     */
    @PostMapping("/api/llm/ratelimit")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmRateLimit(
            @RequestBody Map<String, Object> body,
            org.springframework.security.core.Authentication authentication) {
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        int perSecond  = intOrDefault(body.get("perSecond"),  rateLimitService.getLlmRateLimitPerSecond());
        int perMinute  = intOrDefault(body.get("perMinute"),  rateLimitService.getLlmRateLimitPerMinute());
        int perDay     = intOrDefault(body.get("perDay"),     rateLimitService.getLlmRateLimitPerDay());
        int concurrent = intOrDefault(body.get("concurrent"), rateLimitService.getLlmRateLimitConcurrent());

        analyzerService.setLlmRateLimit(enabled, perSecond, perMinute, perDay, concurrent);
        logger.info("[LLM-Config] action=update-ratelimit enabled={} perSecond={} perMinute={} perDay={} concurrent={} by={}",
                rateLimitService.isLlmRateLimitEnabled(), rateLimitService.getLlmRateLimitPerSecond(),
                rateLimitService.getLlmRateLimitPerMinute(), rateLimitService.getLlmRateLimitPerDay(),
                rateLimitService.getLlmRateLimitConcurrent(),
                authentication != null ? authentication.getName() : "unknown");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.putAll(rateLimitView());
        return ResponseEntity.ok(res);
    }

    /** 현재 호출량 제한 설정 — 저장 응답과 /api/settings 가 같은 형태를 쓰도록 한 곳에서 만든다. */
    private Map<String, Object> rateLimitView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", rateLimitService.isLlmRateLimitEnabled());
        m.put("perSecond", rateLimitService.getLlmRateLimitPerSecond());
        m.put("perMinute", rateLimitService.getLlmRateLimitPerMinute());
        m.put("perDay", rateLimitService.getLlmRateLimitPerDay());
        m.put("concurrent", rateLimitService.getLlmRateLimitConcurrent());
        return m;
    }

    /** 숫자 파싱 실패/누락 시 현재값 유지 — 부분 저장이 다른 값을 0(무제한)으로 밀지 않도록. */
    private static int intOrDefault(Object v, int fallback) {
        if (v == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ── RAG (Elasticsearch) ───────────────────────────────────────

    @GetMapping("/api/settings/rag")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRagSettings() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", ragConfig.isRagEnabled());
        res.put("url", ragConfig.getRagElasticsearchUrl());
        res.put("authType", ragConfig.getRagAuthType());
        res.put("username", ragConfig.getRagUsername());
        res.put("passwordSet", ragConfig.isRagPasswordSet());
        res.put("passwordMasked", ragConfig.getRagPasswordMasked());
        res.put("passwordHealthy", ragConfig.isRagPasswordHealthy());
        res.put("passwordIssue", ragConfig.getRagPasswordIssue());
        res.put("apiKeySet", ragConfig.isRagApiKeySet());
        res.put("apiKeyMasked", ragConfig.getRagApiKeyMasked());
        res.put("apiKeyHealthy", ragConfig.isRagApiKeyHealthy());
        res.put("apiKeyIssue", ragConfig.getRagApiKeyIssue());
        res.put("index", ragConfig.getRagIndex());
        res.put("sslVerify", ragConfig.isRagSslVerify());
        res.put("searchMode", ragConfig.getRagSearchMode());
        res.put("textField", ragConfig.getRagTextField());
        res.put("topK", ragConfig.getRagTopK());
        res.put("minScore", ragConfig.getRagMinScore());
        res.put("timeoutSeconds", ragConfig.getRagTimeoutSeconds());
        Map<String, Object> chunking = new LinkedHashMap<>();
        chunking.put("enabled", ragConfig.isRagChunkingEnabled());
        chunking.put("strategy", ragConfig.getRagChunkingStrategy());
        chunking.put("size", ragConfig.getRagChunkingSize());
        chunking.put("overlap", ragConfig.getRagChunkingOverlap());
        chunking.put("maxChunksPerDoc", ragConfig.getRagChunkingMaxChunksPerDoc());
        chunking.put("maxTotalChars", ragConfig.getRagChunkingMaxTotalChars());
        res.put("chunking", chunking);

        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("queryType", ragConfig.getRagSemanticQueryType());
        semantic.put("modelId", ragConfig.getRagSemanticModelId());
        semantic.put("tokensField", ragConfig.getRagSemanticTokensField());
        semantic.put("semanticField", ragConfig.getRagSemanticField());
        res.put("semantic", semantic);

        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("provider", ragConfig.getRagEmbeddingProvider());
        embedding.put("apiUrl", ragConfig.getRagEmbeddingApiUrl());
        embedding.put("apiKeySet", ragConfig.isRagEmbeddingApiKeySet());
        embedding.put("apiKeyMasked", ragConfig.getRagEmbeddingApiKeyMasked());
        embedding.put("apiKeyHealthy", ragConfig.isRagEmbeddingApiKeyHealthy());
        embedding.put("apiKeyIssue", ragConfig.getRagEmbeddingApiKeyIssue());
        embedding.put("model", ragConfig.getRagEmbeddingModel());
        embedding.put("dimension", ragConfig.getRagEmbeddingDimension());
        embedding.put("timeoutSeconds", ragConfig.getRagEmbeddingTimeoutSeconds());
        embedding.put("vectorField", ragConfig.getRagKnnVectorField());
        embedding.put("numCandidates", ragConfig.getRagKnnNumCandidates());
        // 사이드카 기본값의 단일 출처 — 설정 화면 "사이드카 기본값 채우기" 가 이 값을 쓴다(JS 리터럴 금지).
        embedding.put("localOnnxDefaultUrl", EmbeddingService.LOCAL_ONNX_DEFAULT_URL);
        embedding.put("localOnnxDimension", EmbeddingService.LOCAL_ONNX_DIMENSION);
        embedding.put("localOnnxModel", EmbeddingService.LOCAL_ONNX_MODEL);
        res.put("embedding", embedding);

        Map<String, Object> chroma = new LinkedHashMap<>();
        chroma.put("url", ragConfig.getRagChromaUrl());
        chroma.put("apiPath", ragConfig.getRagChromaApiPath());
        chroma.put("tenant", ragConfig.getRagChromaTenant());
        chroma.put("database", ragConfig.getRagChromaDatabase());
        chroma.put("collection", ragConfig.getRagChromaCollection());
        chroma.put("authType", ragConfig.getRagChromaAuthType());
        chroma.put("tokenSet", ragConfig.isRagChromaTokenSet());
        chroma.put("tokenMasked", ragConfig.getRagChromaTokenMasked());
        chroma.put("tokenHealthy", ragConfig.isRagChromaTokenHealthy());
        chroma.put("tokenIssue", ragConfig.getRagChromaTokenIssue());
        chroma.put("space", ragConfig.getRagChromaSpace());
        chroma.put("timeoutSeconds", ragConfig.getRagChromaTimeoutSeconds());
        chroma.put("sslVerify", ragConfig.isRagChromaSslVerify());
        res.put("chroma", chroma);

        // UI 배지용 단일 플래그 — 시크릿 4종 중 하나라도 손상이면 false
        // ⚠ 시크릿을 추가하면 여기도 반드시 늘릴 것 — 빠뜨리면 손상 토큰이 배지에 안 잡힌다.
        res.put("secretsHealthy", ragConfig.isRagPasswordHealthy()
                && ragConfig.isRagApiKeyHealthy()
                && ragConfig.isRagEmbeddingApiKeyHealthy()
                && ragConfig.isRagChromaTokenHealthy());

        // 모드 목록의 단일 출처는 RagConfigService.AVAILABLE_MODES 다 —
        // 여기에 리터럴을 다시 적으면 setter 화이트리스트와 조용히 갈라진다.
        res.put("availableModes", RagConfigService.AVAILABLE_MODES);
        res.put("availableAuthTypes", Arrays.asList("none", "basic", "api-key"));
        res.put("availableChunkingStrategies", Arrays.asList("fixed", "paragraph", "sentence"));
        res.put("availableSemanticQueryTypes", Arrays.asList("text_expansion", "semantic"));
        res.put("availableEmbeddingProviders", Arrays.asList("openai", "cohere", "custom", "local-onnx"));
        res.put("availableChromaAuthTypes", Arrays.asList("none", "token", "basic"));
        res.put("availableChromaSpaces", Arrays.asList("cosine", "l2", "ip"));
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
        res.put("enabled", ragConfig.isRagChunkingEnabled());
        res.put("strategy", ragConfig.getRagChunkingStrategy());
        res.put("size", ragConfig.getRagChunkingSize());
        res.put("overlap", ragConfig.getRagChunkingOverlap());
        res.put("maxChunksPerDoc", ragConfig.getRagChunkingMaxChunksPerDoc());
        res.put("maxTotalChars", ragConfig.getRagChunkingMaxTotalChars());
        return ResponseEntity.ok(res);
    }

    /**
     * RAG 활성/비활성 토글. <b>실패를 실패라고 말하는 것</b>이 이 엔드포인트의 계약이다.
     *
     * <ul>
     *   <li>{@code enabled} 누락/형식 오류 → <b>400</b>. 종전에는 {@code Boolean.TRUE.equals(null)}
     *       이 false 라 <b>잘못된 요청 하나가 조용히 RAG 를 껐다.</b></li>
     *   <li>설정 파일 기록 실패 → 200 이되 {@code persisted=false} + 경고 문구. 메모리에는 이미
     *       반영됐으므로 {@code success=false} 로 돌리면 화면이 토글을 되돌려 <b>서버 상태와 어긋난다</b>.</li>
     *   <li>감사 로그는 {@code by=} 를 반드시 남긴다 — "누가 RAG 를 껐나"를 추적할 수 있어야 한다.</li>
     * </ul>
     */
    @PostMapping("/api/settings/rag/enabled")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setRagEnabled(@RequestBody(required = false) Map<String, Object> body,
                                                             Authentication authentication) {
        Object raw = (body == null) ? null : body.get("enabled");
        if (!(raw instanceof Boolean)) {
            logger.warn("[RAG] action=toggle rejected reason=invalid-enabled raw={} by={}", raw, who(authentication));
            return ResponseEntity.badRequest()
                    .body(ragError("INVALID_REQUEST", "enabled 값(true/false)이 필요합니다."));
        }
        boolean requested = (Boolean) raw;
        boolean before = ragConfig.isRagEnabled();

        boolean persisted;
        try {
            persisted = analyzerService.setRagEnabled(requested);
        } catch (RuntimeException e) {
            logger.error("[RAG] action=toggle failed enabled={}->{} by={}",
                    before, requested, who(authentication), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ragError("RAG_TOGGLE_FAILED", "RAG 설정을 변경하지 못했습니다: " + e.getMessage()));
        }

        boolean after = ragConfig.isRagEnabled();
        logger.info("[RAG] action=toggle enabled={}->{} persisted={} by={}",
                before, after, persisted, who(authentication));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("enabled", after);
        res.put("persisted", persisted);
        if (!persisted) {
            // 로그에만 남기면 사용자는 저장된 줄 안다 — 재기동 때 되돌아간다는 사실을 화면에 알린다.
            logger.error("[RAG] action=toggle 저장 실패 — 메모리에는 적용됨(enabled={}) / settings.json 기록 실패", after);
            res.put("warning", "변경은 즉시 적용됐지만 설정 파일에 저장하지 못했습니다 — 재기동하면 이전 값으로 돌아갑니다.");
        }
        return ResponseEntity.ok(res);
    }

    /** 감사 로그의 행위자. 컨벤션상 모든 mutation 로그에 {@code by=} 로 붙는다. */
    private static String who(Authentication auth) { return auth != null ? auth.getName() : "unknown"; }

    /** 오류 응답 3필드({@code success}/{@code code}/{@code error}) — SecurityConfig 의 API 오류와 같은 모양. */
    private static Map<String, Object> ragError(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("code", code);
        err.put("error", message);
        return err;
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

        String chUrl        = body.containsKey("chromaUrl")        ? (String) body.get("chromaUrl")        : null;
        String chApiPath    = body.containsKey("chromaApiPath")    ? (String) body.get("chromaApiPath")    : null;
        String chTenant     = body.containsKey("chromaTenant")     ? (String) body.get("chromaTenant")     : null;
        String chDatabase   = body.containsKey("chromaDatabase")   ? (String) body.get("chromaDatabase")   : null;
        String chCollection = body.containsKey("chromaCollection") ? (String) body.get("chromaCollection") : null;
        String chAuthType   = body.containsKey("chromaAuthType")   ? (String) body.get("chromaAuthType")   : null;
        // password/apiKey 와 동일한 3상태 — 키 없음=유지 / ""=삭제 / 값=교체
        String chToken      = body.containsKey("chromaToken")      ? (String) body.get("chromaToken")      : null;
        String chSpace      = body.containsKey("chromaSpace")      ? (String) body.get("chromaSpace")      : null;
        int    chTimeout    = body.containsKey("chromaTimeoutSeconds") ? parseInt(body.get("chromaTimeoutSeconds"), -1) : -1;
        if (chUrl != null || chApiPath != null || chTenant != null || chDatabase != null
                || chCollection != null || chAuthType != null || chToken != null
                || chSpace != null || chTimeout > 0 || body.containsKey("chromaSslVerify")) {
            boolean chSsl = !Boolean.FALSE.equals(body.get("chromaSslVerify"));
            analyzerService.setRagChromaConfig(chUrl, chApiPath, chTenant, chDatabase, chCollection,
                    chAuthType, chToken, chSpace, chTimeout, chSsl);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("url", ragConfig.getRagElasticsearchUrl());
        res.put("index", ragConfig.getRagIndex());
        res.put("searchMode", ragConfig.getRagSearchMode());
        res.put("passwordSet", ragConfig.isRagPasswordSet());
        res.put("apiKeySet", ragConfig.isRagApiKeySet());
        res.put("embeddingApiKeySet", ragConfig.isRagEmbeddingApiKeySet());
        res.put("chromaTokenSet", ragConfig.isRagChromaTokenSet());
        return ResponseEntity.ok(res);
    }

    /**
     * 앱 코드 안에만 있는 진단 지식을 RAG 색인용 문서로 내보낸다.
     * 색인기(`/opt/chroma/app/run-index.sh --sources app`)가 이 응답을 그대로 upsert 한다.
     *
     * <p>⚠ 경로가 {@code /api/admin/**} 아래인 것이 인가의 전부다 — SecurityConfig 의
     * {@code .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")} 이 덮는다.
     * {@code /api/rag/...} 같은 곳으로 옮기면 {@code anyRequest()} 가 USER 까지 허용하므로
     * 일반 사용자에게 열린다. GET 이라 CSRF 는 대상이 아니다.
     */
    @GetMapping("/api/admin/rag/knowledge-export")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> exportKnowledge() {
        List<Map<String, Object>> docs = knowledgeExport.export();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("count", docs.size());
        res.put("docs", docs);
        return ResponseEntity.ok(res);
    }

    /**
     * Chroma 연동 상태 (읽기 전용). 설정 화면의 "Chroma 연동 상태" 패널이 로드 시 1회 + 새로고침 시 부른다.
     *
     * <p>GET 이라 CSRF 대상이 아니고 USER 도 읽는다 — 페이지 자체가 USER 열람 가능하기 때문.
     * 그래서 {@link ChromaSearchService#integrationStatus()} 는 토큰·시크릿을 싣지 않는다.
     * 저장된 설정만 본다(폼의 미저장 값은 아래 연결 테스트가 담당). 프로브 타임아웃은
     * min(설정, 5초) — 서비스가 죽어 있어도 페이지 로드가 15초씩 멈추지 않도록.
     * 주기 폴링은 없다(함정 38 — 배경 폴러가 세션 만료를 무력화한다).
     */
    @GetMapping("/api/settings/rag/chroma/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> chromaIntegrationStatus() {
        return ResponseEntity.ok(chromaSearchService.integrationStatus());
    }

    /**
     * Chroma 연결 테스트. 기존 /api/settings/rag/test 와 합치지 않는 이유는
     * 그쪽이 ES {@code _cluster/health} + 인덱스 HEAD 전용이고, ES 와 Chroma 를
     * 병행 운용하는 것이 요구사항이기 때문이다(합치면 ES 테스트가 망가진다).
     *
     * <p>인가는 SecurityConfig 의 {@code POST /api/settings/**} → ADMIN 패턴이
     * 이미 덮는다. 경로를 옮기면 인가가 조용히 풀리므로 주의.
     */
    @PostMapping("/api/settings/rag/chroma/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testChromaConnection(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> overrides = (body == null) ? new LinkedHashMap<>() : body;
        // 토큰이 빈 문자열이면 저장된 값으로 폴백 — 임베딩 테스트와 동일 규약
        Object t = overrides.get("chromaToken");
        if (t == null || String.valueOf(t).trim().isEmpty()) overrides.remove("chromaToken");
        return ResponseEntity.ok(chromaSearchService.testConnection(overrides));
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

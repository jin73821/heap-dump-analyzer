package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 설정 및 호출 서비스 (Phase 4A-2).
 *
 * 책임:
 *   - LLM 12 개 런타임 필드 보유 (provider/apiUrl/apiKey/model/tokens/timeout 등)
 *   - getter/setter
 *   - 4 개 호출 메서드: testLlmConnection / callLlmAnalysis / callLlmChat / callLlmChatStream
 *   - SSL 검증 토글 (사내 사설 CA 환경)
 *   - settings.json / application.properties 영속화는 호출자(HeapDumpAnalyzerService)에서 트리거 —
 *     본 클래스는 collectSettings/applyFromSettings/collectApplicationProperties 로 raw map 만 노출.
 */
@Component
public class LlmConfigService {

    private static final Logger logger = LoggerFactory.getLogger(LlmConfigService.class);

    static final String DEFAULT_CHAT_SYSTEM_PROMPT =
        "당신은 Java 힙 덤프 분석 전문가입니다. 사용자가 제공한 힙 덤프 분석 결과에 대해 "
        + "대화형으로 질문에 답합니다. 메모리 누수 원인, JVM 튜닝, 코드 수정 방안 등을 "
        + "한국어로 상세히 설명합니다. 마크다운 형식으로 응답하되, 간결하고 실행 가능한 조언을 제공하세요.";

    /** Genspark 허용 모델 목록 */
    public static final List<String> GENSPARK_MODELS = Arrays.asList(
        "gpt-5", "gpt-5-mini", "gpt-5-nano",
        "gpt-5.1", "gpt-5.2", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano",
        "gpt-5-codex", "gpt-5.2-codex", "gpt-5.3-codex",
        "claude-sonnet-4-5", "claude-sonnet-4-6", "claude-sonnet-4-6-1m",
        "claude-haiku-4-5",
        "claude-opus-4-5", "claude-opus-4-6", "claude-opus-4-6-1m",
        "kimi-k2p5", "minimax-m2p5", "minimax-m2p7"
    );

    private final HeapDumpConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 런타임 설정 (12 개 필드) ─────────────────────────────────
    private volatile boolean llmEnabled;
    private volatile String  llmProvider;
    private volatile String  llmApiUrl;
    private volatile String  llmModel;
    private volatile String  llmApiKey;
    private volatile int     llmMaxInputTokens;
    private volatile int     llmMaxOutputTokens;
    private volatile int     llmTimeoutConnectSeconds;
    private volatile int     llmTimeoutReadSeconds;
    private volatile String  llmChatSystemPrompt;
    private volatile boolean llmChatRestoreIncludeHistory = true;
    private volatile boolean llmSslVerify = true;

    public LlmConfigService(HeapDumpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.llmEnabled = config.isLlmEnabled();
        this.llmProvider = config.getLlmProvider();
        this.llmApiUrl = config.getLlmApiUrl();
        this.llmModel = config.getLlmModel();
        this.llmApiKey = config.getLlmApiKey();
        this.llmMaxInputTokens = config.getLlmMaxInputTokens();
        this.llmMaxOutputTokens = config.getLlmMaxOutputTokens();
        this.llmTimeoutConnectSeconds = config.getLlmTimeoutConnectSeconds();
        this.llmTimeoutReadSeconds = config.getLlmTimeoutReadSeconds();
        this.llmSslVerify = config.isLlmSslVerify();
        this.llmChatSystemPrompt = DEFAULT_CHAT_SYSTEM_PROMPT;
        applyEnvOverride();
    }

    /** `LLM_API_KEY` 환경변수가 설정되어 있으면 우선 적용. */
    public void applyEnvOverride() {
        String envKey = System.getenv("LLM_API_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            this.llmApiKey = envKey;
        }
    }

    // ── Getter/Setter ──────────────────────────────────────────────

    public boolean isLlmEnabled()              { return llmEnabled; }
    public String  getLlmProvider()             { return llmProvider; }
    public String  getLlmApiUrl()               { return llmApiUrl; }
    public String  getLlmModel()                { return llmModel; }
    public String  getLlmApiKey()               { return llmApiKey; }
    public int     getLlmMaxInputTokens()       { return llmMaxInputTokens; }
    public int     getLlmMaxOutputTokens()      { return llmMaxOutputTokens; }
    public int     getLlmTimeoutConnectSeconds() { return llmTimeoutConnectSeconds; }
    public int     getLlmTimeoutReadSeconds()    { return llmTimeoutReadSeconds; }

    public void setLlmEnabled(boolean enabled) {
        this.llmEnabled = enabled;
        logger.info("[LLM] enabled={}", enabled);
    }

    public void setLlmConfig(String provider, String apiUrl, String model,
                             int maxInputTokens, int maxOutputTokens) {
        this.llmProvider = provider;
        this.llmApiUrl = apiUrl;
        this.llmModel = model;
        this.llmMaxInputTokens = maxInputTokens;
        this.llmMaxOutputTokens = maxOutputTokens;
        logger.info("[LLM] config updated: provider={}, model={}", provider, model);
    }

    public void setLlmApiKey(String apiKey) {
        this.llmApiKey = apiKey;
        logger.info("[LLM] API key updated (length={})", apiKey != null ? apiKey.length() : 0);
    }

    public String getLlmApiKeyMasked() {
        if (llmApiKey == null || llmApiKey.length() < 8) return "****";
        return llmApiKey.substring(0, 7) + "..." + llmApiKey.substring(llmApiKey.length() - 4);
    }

    public boolean isLlmApiKeySet() {
        return llmApiKey != null && !llmApiKey.trim().isEmpty();
    }

    public String getLlmChatSystemPrompt() { return llmChatSystemPrompt; }

    public void setLlmChatSystemPrompt(String prompt) {
        this.llmChatSystemPrompt = (prompt != null && !prompt.trim().isEmpty()) ? prompt.trim() : DEFAULT_CHAT_SYSTEM_PROMPT;
        logger.info("[LLM] Chat system prompt updated (length={})", this.llmChatSystemPrompt.length());
    }

    public boolean isLlmChatRestoreIncludeHistory() { return llmChatRestoreIncludeHistory; }

    public void setLlmChatRestoreIncludeHistory(boolean v) {
        this.llmChatRestoreIncludeHistory = v;
        logger.info("[LLM] Chat restore include-history: {}", v);
    }

    public boolean isLlmSslVerify() { return llmSslVerify; }

    public void setLlmSslVerify(boolean sslVerify) {
        this.llmSslVerify = sslVerify;
        logger.info("[LLM] SSL verify: {}", sslVerify);
    }

    public String getDefaultApiUrl(String provider) {
        switch (provider) {
            case "claude":   return "https://api.anthropic.com/v1/messages";
            case "gpt":      return "https://api.openai.com/v1/chat/completions";
            case "genspark": return "https://www.genspark.ai/api/llm_proxy/v1/chat/completions";
            case "custom":   return "";
            default:         return "";
        }
    }

    // ── Settings 영속화 hook ─────────────────────────────────────

    /** settings.json 로드 후 LLM 키들을 적용. */
    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("llmEnabled")) {
            this.llmEnabled = Boolean.parseBoolean(String.valueOf(saved.get("llmEnabled")));
        }
        if (saved.containsKey("llmProvider")) {
            this.llmProvider = String.valueOf(saved.get("llmProvider"));
        }
        if (saved.containsKey("llmApiUrl")) {
            this.llmApiUrl = String.valueOf(saved.get("llmApiUrl"));
        }
        if (saved.containsKey("llmModel")) {
            this.llmModel = String.valueOf(saved.get("llmModel"));
        }
        if (saved.containsKey("llmApiKey")) {
            this.llmApiKey = String.valueOf(saved.get("llmApiKey"));
        }
        if (saved.containsKey("llmMaxInputTokens")) {
            this.llmMaxInputTokens = Integer.parseInt(String.valueOf(saved.get("llmMaxInputTokens")));
        }
        if (saved.containsKey("llmMaxOutputTokens")) {
            this.llmMaxOutputTokens = Integer.parseInt(String.valueOf(saved.get("llmMaxOutputTokens")));
        }
        if (saved.containsKey("llmTimeoutConnectSeconds")) {
            this.llmTimeoutConnectSeconds = Integer.parseInt(String.valueOf(saved.get("llmTimeoutConnectSeconds")));
        }
        if (saved.containsKey("llmTimeoutReadSeconds")) {
            this.llmTimeoutReadSeconds = Integer.parseInt(String.valueOf(saved.get("llmTimeoutReadSeconds")));
        }
        if (saved.containsKey("llmChatSystemPrompt")) {
            String prompt = String.valueOf(saved.get("llmChatSystemPrompt"));
            if (prompt != null && !prompt.trim().isEmpty()) {
                this.llmChatSystemPrompt = prompt;
            }
        }
        if (saved.containsKey("llmChatRestoreIncludeHistory")) {
            this.llmChatRestoreIncludeHistory = Boolean.TRUE.equals(saved.get("llmChatRestoreIncludeHistory"));
        }
        if (saved.containsKey("llmSslVerify")) {
            this.llmSslVerify = Boolean.parseBoolean(String.valueOf(saved.get("llmSslVerify")));
        }
    }

    /** settings.json 저장 시 LLM 키들을 map 에 채워준다. */
    public void collectSettings(Map<String, Object> settings) {
        settings.put("llmEnabled", llmEnabled);
        settings.put("llmProvider", llmProvider);
        settings.put("llmApiUrl", llmApiUrl);
        settings.put("llmModel", llmModel);
        settings.put("llmApiKey", llmApiKey);
        settings.put("llmMaxInputTokens", llmMaxInputTokens);
        settings.put("llmMaxOutputTokens", llmMaxOutputTokens);
        settings.put("llmTimeoutConnectSeconds", llmTimeoutConnectSeconds);
        settings.put("llmTimeoutReadSeconds", llmTimeoutReadSeconds);
        settings.put("llmChatSystemPrompt", llmChatSystemPrompt);
        settings.put("llmChatRestoreIncludeHistory", llmChatRestoreIncludeHistory);
        settings.put("llmSslVerify", llmSslVerify);
    }

    /** application.properties 동기화 시 LLM 키들을 map 에 채워준다. */
    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("llm.enabled", String.valueOf(llmEnabled));
        updates.put("llm.provider", llmProvider != null ? llmProvider : "claude");
        updates.put("llm.api.url", llmApiUrl != null ? llmApiUrl : "");
        updates.put("llm.model", llmModel != null ? llmModel : "");
        updates.put("llm.api.key", llmApiKey != null ? llmApiKey : "");
        updates.put("llm.max-input-tokens", String.valueOf(llmMaxInputTokens));
        updates.put("llm.max-output-tokens", String.valueOf(llmMaxOutputTokens));
        updates.put("llm.timeout.connect-seconds", String.valueOf(llmTimeoutConnectSeconds));
        updates.put("llm.timeout.read-seconds", String.valueOf(llmTimeoutReadSeconds));
        updates.put("llm.chat.restore-include-history", String.valueOf(llmChatRestoreIncludeHistory));
        updates.put("llm.ssl.verify", String.valueOf(llmSslVerify));
    }

    // ── SSL 토글 헬퍼 ─────────────────────────────────────────────

    /**
     * 자체 서명 인증서 환경용 — HttpsURLConnection 의 SSL 검증을 비활성화.
     * 운영에서는 사내 CA 를 JVM truststore 에 등록하고 llm.ssl.verify=true 유지가 권장.
     */
    private void disableSslVerification(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{ new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String s) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String s) {}
            }};
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            logger.warn("[LLM] SSL verification disable failed: {}", e.getMessage());
        }
    }

    // ── 호출 메서드 ──────────────────────────────────────────────

    /**
     * LLM 연결 테스트 — 프로바이더별 분기
     */
    public Map<String, Object> testLlmConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", llmProvider);

        if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "API 키가 설정되지 않았습니다");
            return result;
        }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "API URL이 설정되지 않았습니다");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            java.net.URL url = new java.net.URL(llmApiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (!llmSslVerify && conn instanceof javax.net.ssl.HttpsURLConnection) {
                disableSslVerification((javax.net.ssl.HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            String body;
            if ("claude".equals(llmProvider)) {
                conn.setRequestProperty("x-api-key", llmApiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = "{\"model\":\"" + llmModel + "\",\"max_tokens\":10,"
                     + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
                body = "{\"model\":\"" + llmModel + "\",\"max_tokens\":10,"
                     + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            long latency = System.currentTimeMillis() - start;

            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                result.put("success", true);
                result.put("latencyMs", latency);
                result.put("model", llmModel);
                try {
                    Map<String, Object> resp = objectMapper.readValue(sb.toString(), Map.class);
                    if (resp.containsKey("model")) {
                        result.put("model", resp.get("model"));
                    }
                } catch (Exception ignored) {}
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) errSb.append(line);
                    }
                }
                result.put("success", false);
                result.put("error", "HTTP " + code + ": " + errSb.toString());
                result.put("latencyMs", latency);
            }
            conn.disconnect();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("latencyMs", latency);
            logger.warn("[LLM] Connection test failed: {}", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> callLlmAnalysis(String prompt) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!llmEnabled) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — LLM 비활성화 상태 (Settings에서 AI Analysis를 ON으로 설정하세요)");
            result.put("success", false);
            result.put("errorCode", "LLM_DISABLED");
            result.put("error", "AI 분석 기능이 비활성화 상태입니다. Settings → AI/LLM Configuration에서 활성화하세요.");
            return result;
        }
        if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — API 키 미설정 (provider={})", llmProvider);
            result.put("success", false);
            result.put("errorCode", "NO_API_KEY");
            result.put("error", "API 키가 설정되지 않았습니다. Settings → AI/LLM Configuration에서 API 키를 저장하세요.");
            return result;
        }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — API URL 미설정 (provider={})", llmProvider);
            result.put("success", false);
            result.put("errorCode", "NO_API_URL");
            result.put("error", "API URL이 설정되지 않았습니다. Settings → AI/LLM Configuration에서 API URL을 확인하세요.");
            return result;
        }

        long startTime = System.currentTimeMillis();
        logger.info("[AI-Insight][STEP 1/4] LLM 분석 시작 — provider={}, model={}, url={}, maxOutput={}",
            llmProvider, llmModel, llmApiUrl, llmMaxOutputTokens);

        try {
            int maxChars = llmMaxInputTokens * 4;
            boolean truncated = prompt.length() > maxChars;
            if (truncated) {
                prompt = prompt.substring(0, maxChars) + "\n...(truncated)";
                logger.warn("[AI-Insight][STEP 2/4] 프롬프트가 maxInputTokens({}) 초과 — 잘림 처리", llmMaxInputTokens);
            }
            boolean isReasoningModel = llmModel != null && (
                llmModel.startsWith("gpt-5") || llmModel.startsWith("o1") || llmModel.startsWith("o3")
            );
            int effectiveMaxOutputTokens = isReasoningModel
                ? Math.max(llmMaxOutputTokens, 8000)
                : Math.max(llmMaxOutputTokens, 2000);
            logger.info("[AI-Insight][STEP 2/4] 프롬프트 준비 완료 — 길이={} chars, reasoningModel={}, effectiveMaxTokens={}",
                prompt.length(), isReasoningModel, effectiveMaxOutputTokens);

            java.net.URL url = new java.net.URL(llmApiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (!llmSslVerify && conn instanceof javax.net.ssl.HttpsURLConnection) {
                disableSslVerification((javax.net.ssl.HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            String systemPrompt = "당신은 Java 힙 덤프 분석 전문가입니다. "
                + "Eclipse MAT 분석 결과를 해석하여 메모리 누수의 근본 원인을 진단하고 "
                + "실행 가능한 조치를 한국어로 제안합니다. "
                + "응답은 반드시 마크다운 없이 순수 JSON 형태로만 반환하세요. "
                + "코드블록(```)을 절대 사용하지 마세요.";

            String body;
            if ("claude".equals(llmProvider)) {
                conn.setRequestProperty("x-api-key", llmApiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = objectMapper.writeValueAsString(Map.of(
                    "model", llmModel,
                    "max_tokens", effectiveMaxOutputTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
                ));
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
                body = objectMapper.writeValueAsString(Map.of(
                    "model", llmModel,
                    "max_tokens", effectiveMaxOutputTokens,
                    "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", prompt)
                    )
                ));
            }

            logger.info("[AI-Insight][STEP 3/4] HTTP POST 전송 중 — timeout={}s/{}s",
                llmTimeoutConnectSeconds, llmTimeoutReadSeconds);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - startTime;
            logger.info("[AI-Insight][STEP 4/4] HTTP 응답 수신 — status={}, elapsed={}ms", code, elapsed);

            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }

                Map<String, Object> resp = objectMapper.readValue(sb.toString(), Map.class);
                String text = extractLlmText(resp);
                result.put("model", llmModel);
                result.put("latencyMs", elapsed);

                if (text == null || text.trim().isEmpty()) {
                    logger.warn("[AI-Insight] LLM이 빈 content를 반환 — model={}, isReasoning={}, effectiveMaxTokens={}",
                        llmModel, isReasoningModel, effectiveMaxOutputTokens);
                    result.put("success", false);
                    result.put("errorCode", "EMPTY_RESPONSE");
                    result.put("error", "LLM이 빈 응답을 반환했습니다."
                        + (isReasoningModel ? " GPT-5 계열 reasoning 모델은 max_tokens를 8,000 이상으로 설정하거나, claude-sonnet-4-5 모델을 사용해 주세요." : " max_tokens를 늘리거나 다른 모델을 선택하세요."));
                    return result;
                }

                try {
                    String cleaned = text.trim();
                    cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)\\s*```\\s*$", "").trim();
                    int jsonStart = cleaned.indexOf('{');
                    int jsonEnd   = cleaned.lastIndexOf('}');
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
                    }
                    Map<String, Object> aiData = objectMapper.readValue(cleaned, Map.class);
                    result.put("success", true);
                    result.put("data", aiData);
                    logger.info("[AI-Insight] 분석 완료 — model={}, latency={}ms, severity={}",
                        llmModel, elapsed, aiData.get("severity"));
                } catch (Exception parseErr) {
                    logger.warn("[AI-Insight] JSON 파싱 실패 — textLen={}, parseError={}",
                        text.length(), parseErr.getMessage());
                    result.put("success", true);
                    result.put("errorCode", "JSON_PARSE_WARN");
                    Map<String, Object> fallback = new LinkedHashMap<>();
                    fallback.put("summary", text.length() > 1000 ? text.substring(0, 1000) + "..." : text);
                    fallback.put("rootCause", "AI 응답을 JSON으로 파싱하지 못했습니다. 위 요약에서 원문을 확인하세요.");
                    fallback.put("recommendations", "-");
                    fallback.put("severity", "Unknown");
                    fallback.put("severityDesc", "파싱 오류: " + parseErr.getMessage());
                    result.put("data", fallback);
                }
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) errSb.append(line);
                    }
                }
                String errBody = errSb.toString();
                String errorCode = classifyHttpError(code, errBody);
                String friendlyMsg = buildHttpErrorMessage(code, errBody);
                logger.error("[AI-Insight] HTTP 오류 — status={}, errorCode={}, body={}", code, errorCode, errBody);
                result.put("success", false);
                result.put("errorCode", errorCode);
                result.put("error", friendlyMsg);
                result.put("httpStatus", code);
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Insight] 연결/읽기 타임아웃 — elapsed={}ms, msg={}", elapsed, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "TIMEOUT");
            result.put("error", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초). "
                + "Settings에서 타임아웃을 늘리거나, 더 빠른 모델(claude-sonnet-4-5, gpt-5-mini)을 선택하세요.");
        } catch (java.net.ConnectException e) {
            logger.error("[AI-Insight] 서버 연결 실패 — url={}, msg={}", llmApiUrl, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "CONNECT_FAILED");
            result.put("error", "LLM 서버에 연결할 수 없습니다. API URL(" + llmApiUrl + ")을 확인하세요: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            logger.error("[AI-Insight] 알 수 없는 호스트 — url={}, msg={}", llmApiUrl, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "UNKNOWN_HOST");
            result.put("error", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage() + ". URL(" + llmApiUrl + ")을 확인하세요.");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Insight] 예외 발생 — type={}, msg={}, elapsed={}ms",
                e.getClass().getSimpleName(), e.getMessage(), elapsed, e);
            result.put("success", false);
            result.put("errorCode", "INTERNAL_ERROR");
            result.put("error", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> callLlmChat(List<Map<String, String>> messages, String systemPrompt) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!llmEnabled) {
            result.put("success", false);
            result.put("errorCode", "LLM_DISABLED");
            result.put("error", "AI 분석 기능이 비활성화 상태입니다.");
            return result;
        }
        if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
            result.put("success", false);
            result.put("errorCode", "NO_API_KEY");
            result.put("error", "API 키가 설정되지 않았습니다.");
            return result;
        }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) {
            result.put("success", false);
            result.put("errorCode", "NO_API_URL");
            result.put("error", "API URL이 설정되지 않았습니다.");
            return result;
        }

        long startTime = System.currentTimeMillis();
        logger.info("[AI-Chat] 채팅 요청 시작 — provider={}, model={}, messageCount={}",
            llmProvider, llmModel, messages.size());

        try {
            int maxChars = llmMaxInputTokens * 4;
            int totalChars = messages.stream().mapToInt(m -> {
                String c = m.get("content");
                return c != null ? c.length() : 0;
            }).sum();
            List<Map<String, String>> effectiveMessages = new ArrayList<>(messages);
            if (totalChars > maxChars && effectiveMessages.size() > 1) {
                while (totalChars > maxChars && effectiveMessages.size() > 1) {
                    Map<String, String> removed = effectiveMessages.remove(0);
                    String rc = removed.get("content");
                    totalChars -= (rc != null ? rc.length() : 0);
                }
                logger.warn("[AI-Chat] 메시지 truncation — 남은 메시지 수={}", effectiveMessages.size());
            }

            boolean isReasoningModel = llmModel != null && (
                llmModel.startsWith("gpt-5") || llmModel.startsWith("o1") || llmModel.startsWith("o3")
            );
            int effectiveMaxOutputTokens = isReasoningModel
                ? Math.max(llmMaxOutputTokens, 8000)
                : Math.max(llmMaxOutputTokens, 2000);

            java.net.URL url = new java.net.URL(llmApiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (!llmSslVerify && conn instanceof javax.net.ssl.HttpsURLConnection) {
                disableSslVerification((javax.net.ssl.HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            List<Map<String, Object>> msgList = new ArrayList<>();
            for (Map<String, String> m : effectiveMessages) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", m.get("role"));
                msg.put("content", m.get("content"));
                msgList.add(msg);
            }

            String body;
            if ("claude".equals(llmProvider)) {
                conn.setRequestProperty("x-api-key", llmApiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("system", systemPrompt);
                reqBody.put("messages", msgList);
                body = objectMapper.writeValueAsString(reqBody);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
                List<Map<String, Object>> allMessages = new ArrayList<>();
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                allMessages.add(sysMsg);
                allMessages.addAll(msgList);
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("messages", allMessages);
                body = objectMapper.writeValueAsString(reqBody);
            }

            logger.info("[AI-Chat] HTTP POST 전송 — timeout={}s/{}s", llmTimeoutConnectSeconds, llmTimeoutReadSeconds);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - startTime;

            if (code >= 200 && code < 300) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }
                Map<String, Object> resp = objectMapper.readValue(sb.toString(), Map.class);
                String text = extractLlmText(resp);
                result.put("model", llmModel);
                result.put("latencyMs", elapsed);

                if (text == null || text.trim().isEmpty()) {
                    result.put("success", false);
                    result.put("errorCode", "EMPTY_RESPONSE");
                    result.put("error", "LLM이 빈 응답을 반환했습니다.");
                } else {
                    result.put("success", true);
                    result.put("text", text.trim());
                    logger.info("[AI-Chat] 응답 수신 — model={}, latency={}ms, textLen={}",
                        llmModel, elapsed, text.length());
                }
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) errSb.append(line);
                    }
                }
                String errBody = errSb.toString();
                result.put("success", false);
                result.put("errorCode", classifyHttpError(code, errBody));
                result.put("error", buildHttpErrorMessage(code, errBody));
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Chat] 타임아웃 — elapsed={}ms", elapsed);
            result.put("success", false);
            result.put("errorCode", "TIMEOUT");
            result.put("error", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초).");
        } catch (java.net.ConnectException e) {
            result.put("success", false);
            result.put("errorCode", "CONNECT_FAILED");
            result.put("error", "LLM 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            result.put("success", false);
            result.put("errorCode", "UNKNOWN_HOST");
            result.put("error", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Chat] 예외 — type={}, msg={}, elapsed={}ms",
                e.getClass().getSimpleName(), e.getMessage(), elapsed, e);
            result.put("success", false);
            result.put("errorCode", "INTERNAL_ERROR");
            result.put("error", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public void callLlmChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        if (!llmEnabled) { onError.accept("LLM_DISABLED", "AI 분석 기능이 비활성화 상태입니다."); return; }
        if (llmApiKey == null || llmApiKey.trim().isEmpty()) { onError.accept("NO_API_KEY", "API 키가 설정되지 않았습니다."); return; }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) { onError.accept("NO_API_URL", "API URL이 설정되지 않았습니다."); return; }

        long startTime = System.currentTimeMillis();
        logger.info("[AI-Chat-Stream] 스트리밍 요청 시작 — provider={}, model={}, messageCount={}",
            llmProvider, llmModel, messages.size());

        try {
            int maxChars = llmMaxInputTokens * 4;
            int totalChars = messages.stream().mapToInt(m -> {
                String c = m.get("content");
                return c != null ? c.length() : 0;
            }).sum();
            List<Map<String, String>> effectiveMessages = new ArrayList<>(messages);
            if (totalChars > maxChars && effectiveMessages.size() > 1) {
                while (totalChars > maxChars && effectiveMessages.size() > 1) {
                    Map<String, String> removed = effectiveMessages.remove(0);
                    String rc = removed.get("content");
                    totalChars -= (rc != null ? rc.length() : 0);
                }
            }

            boolean isReasoningModel = llmModel != null && (
                llmModel.startsWith("gpt-5") || llmModel.startsWith("o1") || llmModel.startsWith("o3")
            );
            int effectiveMaxOutputTokens = isReasoningModel
                ? Math.max(llmMaxOutputTokens, 8000)
                : Math.max(llmMaxOutputTokens, 2000);

            java.net.URL url = new java.net.URL(llmApiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (!llmSslVerify && conn instanceof javax.net.ssl.HttpsURLConnection) {
                disableSslVerification((javax.net.ssl.HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            List<Map<String, Object>> msgList = new ArrayList<>();
            for (Map<String, String> m : effectiveMessages) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", m.get("role"));
                msg.put("content", m.get("content"));
                msgList.add(msg);
            }

            String body;
            boolean isClaude = "claude".equals(llmProvider);
            if (isClaude) {
                conn.setRequestProperty("x-api-key", llmApiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("stream", true);
                reqBody.put("system", systemPrompt);
                reqBody.put("messages", msgList);
                body = objectMapper.writeValueAsString(reqBody);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + llmApiKey);
                List<Map<String, Object>> allMessages = new ArrayList<>();
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                allMessages.add(sysMsg);
                allMessages.addAll(msgList);
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("stream", true);
                reqBody.put("messages", allMessages);
                body = objectMapper.writeValueAsString(reqBody);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                StringBuilder fullText = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;

                        try {
                            Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                            String text = null;

                            if (isClaude) {
                                String type = String.valueOf(chunk.get("type"));
                                if ("content_block_delta".equals(type)) {
                                    Map<String, Object> delta = (Map<String, Object>) chunk.get("delta");
                                    if (delta != null) text = (String) delta.get("text");
                                }
                            } else {
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                    if (delta != null) text = (String) delta.get("content");
                                }
                            }

                            if (text != null && !text.isEmpty()) {
                                fullText.append(text);
                                onChunk.accept(text);
                            }
                        } catch (Exception parseErr) {
                            // 개별 청크 파싱 오류는 무시
                        }
                    }
                }
                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("[AI-Chat-Stream] 스트리밍 완료 — model={}, latency={}ms, textLen={}",
                    llmModel, elapsed, fullText.length());
                onDone.accept(fullText.toString(), elapsed);
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String l; while ((l = br.readLine()) != null) errSb.append(l);
                    }
                }
                String errBody = errSb.toString();
                onError.accept(classifyHttpError(code, errBody), buildHttpErrorMessage(code, errBody));
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            onError.accept("TIMEOUT", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초).");
        } catch (java.net.ConnectException e) {
            onError.accept("CONNECT_FAILED", "LLM 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            onError.accept("UNKNOWN_HOST", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage());
        } catch (Exception e) {
            logger.error("[AI-Chat-Stream] 예외 — type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            onError.accept("INTERNAL_ERROR", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
        }
    }

    // ── HTTP 오류 처리 헬퍼 ───────────────────────────────────────

    private String classifyHttpError(int code, String body) {
        if (code == 401 || code == 403) return "AUTH_ERROR";
        if (code == 404) return "NOT_FOUND";
        if (code == 429) return "RATE_LIMIT";
        if (code == 400) return "BAD_REQUEST";
        if (code >= 500) return "SERVER_ERROR";
        return "HTTP_" + code;
    }

    private String buildHttpErrorMessage(int code, String body) {
        String base;
        switch (code) {
            case 401: base = "API 키 인증 실패(401). API 키가 올바른지 확인하세요."; break;
            case 403: base = "API 키 권한 없음(403). 해당 모델 접근 권한이 있는지 확인하세요."; break;
            case 404: base = "API 엔드포인트를 찾을 수 없습니다(404). Settings에서 API URL 끝에 /chat/completions 포함 여부를 확인하세요."; break;
            case 429: base = "API 요청 횟수 초과(429 Too Many Requests). 잠시 후 다시 시도하세요."; break;
            case 400: base = "잘못된 요청(400 Bad Request). 모델명이 허용 목록에 있는지 확인하세요."; break;
            case 500: case 502: case 503:
                base = "LLM 서버 내부 오류(" + code + "). 잠시 후 다시 시도하세요."; break;
            default:  base = "HTTP " + code + " 오류가 발생했습니다.";
        }
        if (body != null && !body.isEmpty() && body.length() < 300) {
            base += " 상세: " + body;
        }
        return base;
    }

    @SuppressWarnings("unchecked")
    private String extractLlmText(Map<String, Object> resp) {
        // Claude 응답 형식
        if (resp.containsKey("content")) {
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            if (content != null && !content.isEmpty()) {
                return String.valueOf(content.get(0).get("text"));
            }
        }
        // OpenAI 호환 응답 형식
        if (resp.containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                if (msg != null) return String.valueOf(msg.get("content"));
            }
        }
        return resp.toString();
    }
}

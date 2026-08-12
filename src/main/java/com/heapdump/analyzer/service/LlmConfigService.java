package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.util.SecretValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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
 *
 * <p><b>호출량 제한의 단일 초크포인트</b> (2026-08-12) — 실제로 업스트림 API 를 때리는 메서드는
 * 전부 여기 5 곳(위 4 개 + Vision 스트리밍 overload)뿐이므로 게이트를 컨트롤러가 아니라 이 층에 둔다.
 * 컨트롤러마다 거는 방식은 새 엔드포인트가 추가될 때 누락되기 쉽다.
 * <b>새 LLM 호출 메서드를 추가하면 반드시 {@code acquireGate(scope)} 로 감쌀 것.</b>
 *
 * <p>⚠ 게이트는 {@link SecurityContextHolder} 로 호출자를 식별한다. SSE 처럼 별도 스레드에서
 * 호출하는 경로는 스레드 spawn 시 {@code DelegatingSecurityContextRunnable} 로 감싸야 사용자별
 * 버킷이 제대로 잡힌다 (안 감싸면 전원이 "system" 버킷을 공유한다).
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

    /** 손상된 시크릿의 마스킹 표기 — 정상처럼 보이면 사용자가 문제를 인지할 수 없다. */
    private static final String CORRUPTED_MASK = "손상됨";

    private final HeapDumpConfig config;
    private final LlmRateLimitService rateLimit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 런타임 설정 (12 개 필드) ─────────────────────────────────
    private volatile boolean llmEnabled;
    private volatile String  llmProvider;
    private volatile String  llmApiUrl;
    private volatile String  llmModel;
    /** settings.json / application.properties 에는 ENC(...) 로 저장 (2026-08-12). */
    private final SecretValue llmApiKey = SecretValue.empty();
    /** 저장된 키가 아직 평문이면 true — 기동 시 1회 재봉인 트리거용. */
    private volatile boolean llmApiKeyUnsealed;
    private volatile int     llmMaxInputTokens;
    private volatile int     llmMaxOutputTokens;
    private volatile int     llmTimeoutConnectSeconds;
    private volatile int     llmTimeoutReadSeconds;
    private volatile String  llmChatSystemPrompt;
    private volatile boolean llmChatRestoreIncludeHistory = true;
    private volatile boolean llmSslVerify = true;
    private volatile boolean llmFileAttachEnabled = false;

    /** Vision API를 기본 지원하는 provider 목록 */
    public static final List<String> VISION_SUPPORTED_PROVIDERS = Arrays.asList("claude", "gpt");

    public LlmConfigService(HeapDumpConfig config, LlmRateLimitService rateLimit) {
        this.config = config;
        this.rateLimit = rateLimit;
    }

    @PostConstruct
    public void init() {
        this.llmEnabled = config.isLlmEnabled();
        this.llmProvider = config.getLlmProvider();
        this.llmApiUrl = config.getLlmApiUrl();
        this.llmModel = config.getLlmModel();
        adopt(config.getLlmApiKey());
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
            this.llmApiKey.set(envKey);
            this.llmApiKeyUnsealed = false;   // set() 한 값은 다음 저장 때 ENC 로 봉인된다
            logger.info("[LLM] 환경변수 LLM_API_KEY 적용 (length={})", envKey.trim().length());
        }
    }

    /**
     * 저장값을 SecretValue 에 로드하고 손상 시 경고. 예외를 던지지 않는다.
     * (RagConfigService.adopt 와 동일 패턴 — 복호화 실패로 기동이 막히면 안 된다.)
     */
    private void adopt(String stored) {
        SecretValue loaded = SecretValue.load(stored);
        this.llmApiKey.adoptFrom(loaded);
        // 값은 있는데 ENC(...) 형식이 아니면 = 레거시 평문. 다음 persist 에서 봉인 대상.
        this.llmApiKeyUnsealed = stored != null && !stored.trim().isEmpty()
                && !"null".equals(stored) && !(stored.startsWith("ENC(") && stored.endsWith(")"));
        if (!loaded.isHealthy()) {
            logger.warn("[LLM] 저장된 API 키를 사용할 수 없습니다 — {} (/settings/llm 에서 재입력 필요)",
                    loaded.issue());
        } else if (this.llmApiKeyUnsealed) {
            logger.warn("[LLM] API 키가 평문으로 저장돼 있습니다 — 다음 설정 저장 시 ENC(...) 로 자동 봉인됩니다");
        }
    }

    // ── Getter/Setter ──────────────────────────────────────────────

    public boolean isLlmEnabled()              { return llmEnabled; }
    public String  getLlmProvider()             { return llmProvider; }
    public String  getLlmApiUrl()               { return llmApiUrl; }
    public String  getLlmModel()                { return llmModel; }
    /** 런타임 소비용 — 손상값은 절대 내보내지 않는다(빈 문자열). */
    public String  getLlmApiKey()               { return llmApiKey.usable(); }
    public boolean isLlmApiKeyHealthy()         { return llmApiKey.isHealthy(); }
    public String  getLlmApiKeyIssue()          { return llmApiKey.issue() != null ? llmApiKey.issue() : ""; }
    /** 저장본이 아직 평문인지 — 기동 시 1회 재봉인 판단용. */
    public boolean isLlmApiKeyUnsealed()        { return llmApiKeyUnsealed && llmApiKey.isSet(); }
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
        this.llmApiKey.set(apiKey);
        this.llmApiKeyUnsealed = false;   // 새 값은 저장 시 ENC 로 봉인된다
        logger.info("[LLM] API key updated (length={})", apiKey != null ? apiKey.length() : 0);
    }

    /** 손상값은 마스킹 대신 손상 표기 — 정상처럼 보이면 사용자가 문제를 인지할 수 없다. */
    public String getLlmApiKeyMasked() {
        if (!llmApiKey.isHealthy()) return CORRUPTED_MASK;
        String v = llmApiKey.raw();
        if (v.length() < 8) return "****";
        return v.substring(0, 7) + "..." + v.substring(v.length() - 4);
    }

    public boolean isLlmApiKeySet() {
        return llmApiKey.isSet();
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

    public boolean isLlmFileAttachEnabled() { return llmFileAttachEnabled; }

    public void setLlmFileAttachEnabled(boolean v) {
        this.llmFileAttachEnabled = v;
        logger.info("[LLM] File attach enabled: {}", v);
    }

    public boolean isFileAttachCapable() {
        if (!llmFileAttachEnabled) return false;
        return VISION_SUPPORTED_PROVIDERS.contains(llmProvider)
            || "genspark".equals(llmProvider) || "custom".equals(llmProvider);
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
            adopt(String.valueOf(saved.get("llmApiKey")));
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
        if (saved.containsKey("llmFileAttachEnabled")) {
            this.llmFileAttachEnabled = Boolean.TRUE.equals(saved.get("llmFileAttachEnabled"));
        }
    }

    /** settings.json 저장 시 LLM 키들을 map 에 채워준다. */
    public void collectSettings(Map<String, Object> settings) {
        settings.put("llmEnabled", llmEnabled);
        settings.put("llmProvider", llmProvider);
        settings.put("llmApiUrl", llmApiUrl);
        settings.put("llmModel", llmModel);
        if (putSecret(settings, "llmApiKey", llmApiKey)) {
            llmApiKeyUnsealed = false;   // 봉인된 값이 기록됐다 — 평문 경고 해제
        }
        settings.put("llmMaxInputTokens", llmMaxInputTokens);
        settings.put("llmMaxOutputTokens", llmMaxOutputTokens);
        settings.put("llmTimeoutConnectSeconds", llmTimeoutConnectSeconds);
        settings.put("llmTimeoutReadSeconds", llmTimeoutReadSeconds);
        settings.put("llmChatSystemPrompt", llmChatSystemPrompt);
        settings.put("llmChatRestoreIncludeHistory", llmChatRestoreIncludeHistory);
        settings.put("llmSslVerify", llmSslVerify);
        settings.put("llmFileAttachEnabled", llmFileAttachEnabled);
    }

    /** application.properties 동기화 시 LLM 키들을 map 에 채워준다. */
    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("llm.enabled", String.valueOf(llmEnabled));
        updates.put("llm.provider", llmProvider != null ? llmProvider : "claude");
        updates.put("llm.api.url", llmApiUrl != null ? llmApiUrl : "");
        updates.put("llm.model", llmModel != null ? llmModel : "");
        putSecret(updates, "llm.api.key", llmApiKey);
        updates.put("llm.max-input-tokens", String.valueOf(llmMaxInputTokens));
        updates.put("llm.max-output-tokens", String.valueOf(llmMaxOutputTokens));
        updates.put("llm.timeout.connect-seconds", String.valueOf(llmTimeoutConnectSeconds));
        updates.put("llm.timeout.read-seconds", String.valueOf(llmTimeoutReadSeconds));
        updates.put("llm.chat.restore-include-history", String.valueOf(llmChatRestoreIncludeHistory));
        updates.put("llm.ssl.verify", String.valueOf(llmSslVerify));
        updates.put("llm.file-attach.enabled", String.valueOf(llmFileAttachEnabled));
    }

    /**
     * 시크릿을 저장 맵에 기록. 암호화 실패(forStorage()==null)면 <b>키 자체를 생략</b>해
     * 기존 저장값을 보존한다 — 빈 문자열로 덮으면 시크릿이 무경고로 삭제된다.
     * (RagConfigService.putSecret 과 동일 규약. 기록했으면 true.)
     */
    private static <T> boolean putSecret(Map<String, T> target, String key, SecretValue secret) {
        String stored = secret.forStorage();
        if (stored == null) {
            logger.error("[Settings] '{}' AES 암호화 실패 — 키를 기록하지 않고 기존 저장값을 유지합니다", key);
            return false;
        }
        @SuppressWarnings("unchecked")
        T value = (T) stored;
        target.put(key, value);
        return true;
    }

    // ── 호출량 게이트 ─────────────────────────────────────────────

    /**
     * 호출 권한 획득. 실제 업스트림 호출 직전에 호출하고, 반환값은 <b>반드시 close</b> 해야 한다
     * (동시 호출 슬롯이 거기서만 반납된다).
     */
    private LlmRateLimitService.Lease acquireGate(String scope) {
        return rateLimit.acquire(currentUser(), scope);
    }

    /**
     * 호출자 식별 — 사용자별 버킷 키.
     * SecurityContext 가 없는 백그라운드 스레드는 "system" 으로 묶인다.
     */
    private static String currentUser() {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.getName() != null && !"anonymousUser".equals(a.getName())) {
                return a.getName();
            }
        } catch (Exception ignored) {
            // SecurityContext 미가용 — system 버킷으로 폴백
        }
        return LlmRateLimitService.SYSTEM_USER;
    }

    /** 사용 가능한 API 키, 미설정·손상이면 null. 손상값은 SecretValue 가 이미 차단한다. */
    private String usableApiKey() {
        String k = llmApiKey.usable();
        return (k == null || k.trim().isEmpty()) ? null : k;
    }

    /**
     * API 키를 쓸 수 없는 이유. 저장은 돼 있는데 복호화 결과가 손상된 경우와
     * 애초에 미설정인 경우를 구분한다 — "미설정" 으로 뭉뚱그리면 원인을 못 찾는다.
     */
    private String apiKeyErrorMessage() {
        if (llmApiKey.isSet() && !llmApiKey.isHealthy()) {
            return "저장된 API 키를 복호화할 수 없습니다 (" + getLlmApiKeyIssue()
                 + "). Settings → AI/LLM Configuration 에서 API 키를 다시 저장하세요.";
        }
        return "API 키가 설정되지 않았습니다";
    }

    /** 거부 Lease → 기존 호출 메서드들과 동일한 결과 맵 형태로 변환. */
    private static Map<String, Object> rateLimitResult(LlmRateLimitService.Lease lease) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("errorCode", lease.code());
        result.put("error", lease.message());
        result.put("retryAfterSeconds", lease.retryAfterSeconds());
        return result;
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

    // ── URL 정규화 헬퍼 ─────────────────────────────────────────────

    /**
     * 비-Claude(OpenAI 호환) provider 의 API URL 이 /chat/completions 로 끝나지 않으면
     * 자동으로 접미를 부착한다. OpenAI 파이썬/JS SDK 가 base_url 뒤에 자동 부착하는 동작과 동일.
     *
     * 사내 게이트웨이 사례:
     *   - 매뉴얼 base_url:  https://gw/openapi/model/<UUID>
     *   - 실제 호출 URL:    https://gw/openapi/model/<UUID>/chat/completions
     *
     * Claude 는 /v1/messages 가 정식 경로이므로 보정 대상에서 제외.
     */
    private String normalizeChatCompletionsUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if ("claude".equals(llmProvider)) return url;
        String trimmed = url.trim();
        // 쿼리스트링/프래그먼트 분리해서 path 부분만 검사
        int qIdx = trimmed.indexOf('?');
        String path = (qIdx >= 0) ? trimmed.substring(0, qIdx) : trimmed;
        String tail = (qIdx >= 0) ? trimmed.substring(qIdx) : "";
        // /chat/completions 또는 /completions(레거시) 로 끝나면 그대로 유지
        if (path.endsWith("/chat/completions") || path.endsWith("/completions")) return trimmed;
        // /messages 로 끝나면 Claude 직접 호출 경로 — 보정 안 함
        if (path.endsWith("/messages")) return trimmed;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String normalized = path + "/chat/completions" + tail;
        logger.warn("[LLM] API URL 자동 보정 — '/chat/completions' 접미 부착: {} → {}", trimmed, normalized);
        return normalized;
    }

    // ── 호출 메서드 ──────────────────────────────────────────────

    /**
     * LLM 연결 테스트 — 프로바이더별 분기
     */
    public Map<String, Object> testLlmConnection() {
        LlmRateLimitService.Lease lease = acquireGate("test-connection");
        if (!lease.allowed()) return rateLimitResult(lease);
        try {
            return doTestLlmConnection();
        } finally {
            lease.close();
        }
    }

    private Map<String, Object> doTestLlmConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", llmProvider);

        final String apiKey = usableApiKey();
        if (apiKey == null) {
            logger.warn("[LLM-Test] 연결 테스트 거부 — API 키 사용 불가 (provider={}, healthy={})",
                    llmProvider, llmApiKey.isHealthy());
            result.put("success", false);
            result.put("error", apiKeyErrorMessage());
            return result;
        }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) {
            logger.warn("[LLM-Test] 연결 테스트 거부 — API URL 미설정 (provider={})", llmProvider);
            result.put("success", false);
            result.put("error", "API URL이 설정되지 않았습니다");
            return result;
        }

        String effectiveUrl = normalizeChatCompletionsUrl(llmApiUrl);
        logger.info("[LLM-Test] 연결 테스트 시작 — provider={}, model={}, url={}, sslVerify={}",
            llmProvider, llmModel, effectiveUrl, llmSslVerify);
        long start = System.currentTimeMillis();
        try {
            java.net.URL url = new java.net.URL(effectiveUrl);
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
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = "{\"model\":\"" + llmModel + "\",\"max_tokens\":10,"
                     + "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                logger.info("[LLM-Test] 연결 테스트 성공 — provider={}, model={}, status={}, latency={}ms",
                    llmProvider, result.get("model"), code, latency);
            } else {
                StringBuilder errSb = new StringBuilder();
                InputStream errStream = conn.getErrorStream();
                if (errStream != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(errStream, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) errSb.append(line);
                    }
                }
                String errBody = errSb.toString();
                String errorCode = classifyHttpError(code, errBody);
                result.put("success", false);
                result.put("errorCode", errorCode);
                result.put("error", "HTTP " + code + ": " + errBody);
                result.put("latencyMs", latency);
                logger.error("[LLM-Test] 연결 테스트 실패 — provider={}, url={}, status={}, errorCode={}, latency={}ms, body={}",
                    llmProvider, llmApiUrl, code, errorCode, latency, errBody);
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("errorCode", "TIMEOUT");
            result.put("error", "연결/응답 타임아웃 (" + latency + "ms): " + e.getMessage());
            result.put("latencyMs", latency);
            logger.error("[LLM-Test] 타임아웃 — url={}, connectTimeout={}s, readTimeout={}s, elapsed={}ms",
                llmApiUrl, llmTimeoutConnectSeconds, llmTimeoutReadSeconds, latency);
        } catch (java.net.ConnectException e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("errorCode", "CONNECT_FAILED");
            result.put("error", "서버 연결 실패: " + e.getMessage());
            result.put("latencyMs", latency);
            logger.error("[LLM-Test] 서버 연결 실패 — url={}, msg={}", llmApiUrl, e.getMessage());
        } catch (java.net.UnknownHostException e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("errorCode", "UNKNOWN_HOST");
            result.put("error", "호스트를 찾을 수 없습니다: " + e.getMessage());
            result.put("latencyMs", latency);
            logger.error("[LLM-Test] 알 수 없는 호스트 — url={}, msg={}", llmApiUrl, e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("errorCode", "SSL_ERROR");
            result.put("error", "SSL 핸드셰이크 실패: " + e.getMessage()
                + " (사내 사설 CA 인증서라면 /settings/llm 의 'SSL 검증' 토글을 OFF 하거나 truststore를 설정하세요.)");
            result.put("latencyMs", latency);
            logger.error("[LLM-Test] SSL 오류 — url={}, sslVerify={}, type={}, msg={}",
                llmApiUrl, llmSslVerify, e.getClass().getSimpleName(), e.getMessage(), e);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("errorCode", "INTERNAL_ERROR");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("latencyMs", latency);
            logger.error("[LLM-Test] 예외 — url={}, type={}, msg={}, elapsed={}ms",
                llmApiUrl, e.getClass().getSimpleName(), e.getMessage(), latency, e);
        }
        return result;
    }

    public Map<String, Object> callLlmAnalysis(String prompt) {
        LlmRateLimitService.Lease lease = acquireGate("analyze");
        if (!lease.allowed()) return rateLimitResult(lease);
        try {
            return doCallLlmAnalysis(prompt);
        } finally {
            lease.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doCallLlmAnalysis(String prompt) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!llmEnabled) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — LLM 비활성화 상태 (Settings에서 AI Analysis를 ON으로 설정하세요)");
            result.put("success", false);
            result.put("errorCode", "LLM_DISABLED");
            result.put("error", "AI 분석 기능이 비활성화 상태입니다. Settings → AI/LLM Configuration에서 활성화하세요.");
            return result;
        }
        final String apiKey = usableApiKey();
        if (apiKey == null) {
            logger.warn("[AI-Insight][STEP] 분석 요청 거부 — API 키 사용 불가 (provider={}, healthy={})",
                    llmProvider, llmApiKey.isHealthy());
            result.put("success", false);
            result.put("errorCode", "NO_API_KEY");
            result.put("error", apiKeyErrorMessage());
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
        String effectiveUrl = normalizeChatCompletionsUrl(llmApiUrl);
        logger.info("[AI-Insight][STEP 1/4] LLM 분석 시작 — provider={}, model={}, url={}, maxOutput={}",
            llmProvider, llmModel, effectiveUrl, llmMaxOutputTokens);

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

            java.net.URL url = new java.net.URL(effectiveUrl);
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
                + "JVM 힙 설정(-Xms, -Xmx)이 제공되면 실제 힙 사용량과 비교하여 "
                + "힙 여유 공간 비율, 메모리 압박 수준, Xmx 증설 또는 축소 필요 여부를 반드시 판단하세요. "
                + "권장 조치(recommendations)는 효과/시급성 기준으로 가장 우선순위가 높은 최우선 조치 3개만 "
                + "1. 2. 3. 순서로 제시하세요 (4개 이상 금지). OutOfMemoryError 가 감지된 경우 "
                + "해당 OOM 종류에 직결되는 조치를 1순위로 두세요. "
                + "응답은 반드시 마크다운 없이 순수 JSON 형태로만 반환하세요. "
                + "코드블록(```)을 절대 사용하지 마세요.";

            String body;
            if ("claude".equals(llmProvider)) {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body = objectMapper.writeValueAsString(Map.of(
                    "model", llmModel,
                    "max_tokens", effectiveMaxOutputTokens,
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", prompt))
                ));
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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

    public Map<String, Object> callLlmChat(List<Map<String, String>> messages, String systemPrompt) {
        LlmRateLimitService.Lease lease = acquireGate("chat");
        if (!lease.allowed()) return rateLimitResult(lease);
        try {
            return doCallLlmChat(messages, systemPrompt);
        } finally {
            lease.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doCallLlmChat(List<Map<String, String>> messages, String systemPrompt) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!llmEnabled) {
            result.put("success", false);
            result.put("errorCode", "LLM_DISABLED");
            result.put("error", "AI 분석 기능이 비활성화 상태입니다.");
            return result;
        }
        final String apiKey = usableApiKey();
        if (apiKey == null) {
            result.put("success", false);
            result.put("errorCode", "NO_API_KEY");
            result.put("error", apiKeyErrorMessage());
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

            java.net.URL url = new java.net.URL(normalizeChatCompletionsUrl(llmApiUrl));
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
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("system", systemPrompt);
                reqBody.put("messages", msgList);
                body = objectMapper.writeValueAsString(reqBody);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                String errorCode = classifyHttpError(code, errBody);
                result.put("success", false);
                result.put("errorCode", errorCode);
                result.put("error", buildHttpErrorMessage(code, errBody));
                logger.error("[AI-Chat] HTTP 오류 — provider={}, url={}, status={}, errorCode={}, elapsed={}ms, body={}",
                    llmProvider, llmApiUrl, code, errorCode, elapsed, errBody);
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Chat] 타임아웃 — url={}, connectTimeout={}s, readTimeout={}s, elapsed={}ms",
                llmApiUrl, llmTimeoutConnectSeconds, llmTimeoutReadSeconds, elapsed);
            result.put("success", false);
            result.put("errorCode", "TIMEOUT");
            result.put("error", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초).");
        } catch (java.net.ConnectException e) {
            logger.error("[AI-Chat] 서버 연결 실패 — url={}, msg={}", llmApiUrl, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "CONNECT_FAILED");
            result.put("error", "LLM 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            logger.error("[AI-Chat] 알 수 없는 호스트 — url={}, msg={}", llmApiUrl, e.getMessage());
            result.put("success", false);
            result.put("errorCode", "UNKNOWN_HOST");
            result.put("error", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            logger.error("[AI-Chat] SSL 오류 — url={}, sslVerify={}, type={}, msg={}",
                llmApiUrl, llmSslVerify, e.getClass().getSimpleName(), e.getMessage(), e);
            result.put("success", false);
            result.put("errorCode", "SSL_ERROR");
            result.put("error", "SSL 핸드셰이크 실패: " + e.getMessage()
                + " (사내 사설 CA 라면 'SSL 검증' OFF 또는 truststore 설정 필요)");
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

    public void callLlmChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        LlmRateLimitService.Lease lease = acquireGate("chat-stream");
        if (!lease.allowed()) { onError.accept(lease.code(), lease.message()); return; }
        try {
            doCallLlmChatStream(messages, systemPrompt, onChunk, onDone, onError);
        } finally {
            lease.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void doCallLlmChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        if (!llmEnabled) { onError.accept("LLM_DISABLED", "AI 분석 기능이 비활성화 상태입니다."); return; }
        final String apiKey = usableApiKey();
        if (apiKey == null) { onError.accept("NO_API_KEY", apiKeyErrorMessage()); return; }
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

            java.net.URL url = new java.net.URL(normalizeChatCompletionsUrl(llmApiUrl));
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
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("stream", true);
                reqBody.put("system", systemPrompt);
                reqBody.put("messages", msgList);
                body = objectMapper.writeValueAsString(reqBody);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                long elapsed = System.currentTimeMillis() - startTime;
                String errorCode = classifyHttpError(code, errBody);
                logger.error("[AI-Chat-Stream] HTTP 오류 — provider={}, url={}, status={}, errorCode={}, elapsed={}ms, body={}",
                    llmProvider, llmApiUrl, code, errorCode, elapsed, errBody);
                onError.accept(errorCode, buildHttpErrorMessage(code, errBody));
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            logger.error("[AI-Chat-Stream] 타임아웃 — url={}, connectTimeout={}s, readTimeout={}s, elapsed={}ms",
                llmApiUrl, llmTimeoutConnectSeconds, llmTimeoutReadSeconds, elapsed);
            onError.accept("TIMEOUT", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초).");
        } catch (java.net.ConnectException e) {
            logger.error("[AI-Chat-Stream] 서버 연결 실패 — url={}, msg={}", llmApiUrl, e.getMessage());
            onError.accept("CONNECT_FAILED", "LLM 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            logger.error("[AI-Chat-Stream] 알 수 없는 호스트 — url={}, msg={}", llmApiUrl, e.getMessage());
            onError.accept("UNKNOWN_HOST", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            logger.error("[AI-Chat-Stream] SSL 오류 — url={}, sslVerify={}, type={}, msg={}",
                llmApiUrl, llmSslVerify, e.getClass().getSimpleName(), e.getMessage(), e);
            onError.accept("SSL_ERROR", "SSL 핸드셰이크 실패: " + e.getMessage()
                + " (사내 사설 CA 라면 'SSL 검증' OFF 또는 truststore 설정 필요)");
        } catch (Exception e) {
            logger.error("[AI-Chat-Stream] 예외 — url={}, type={}, msg={}",
                llmApiUrl, e.getClass().getSimpleName(), e.getMessage(), e);
            onError.accept("INTERNAL_ERROR", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
        }
    }

    // ── Vision(파일 첨부) overload ────────────────────────────────

    /**
     * attachments 포함 스트리밍 채팅.
     * attachments가 비어 있으면 기존 overload로 위임한다.
     */
    public void callLlmChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   List<Map<String, Object>> attachments,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        if (attachments == null || attachments.isEmpty()) {
            callLlmChatStream(messages, systemPrompt, onChunk, onDone, onError);
            return;
        }

        // 텍스트/로그 첨부는 Vision API 로 보낼 수 없으므로 디코드해 마지막 user 메시지 본문에 접어 넣고,
        // 이미지 첨부만 provider 별 Vision 경로로 전송한다. (텍스트 전용이면 Vision 불필요 → 모든 provider 지원)
        List<Map<String, Object>> imageAtts = new ArrayList<>();
        StringBuilder textBlocks = new StringBuilder();
        for (Map<String, Object> att : attachments) {
            String mt = (String) att.get("mediaType");
            if (mt != null && mt.startsWith("image/")) {
                imageAtts.add(att);
            } else {
                String name = (String) att.get("name");
                String decoded = decodeAttachmentText((String) att.get("data"));
                if (decoded != null) {
                    textBlocks.append("\n\n[첨부 파일: ").append(name != null ? name : "file")
                              .append("]\n```\n").append(decoded).append("\n```");
                }
            }
        }
        if (textBlocks.length() > 0) {
            messages = appendTextToLastUserMessage(messages, textBlocks.toString());
        }
        if (imageAtts.isEmpty()) {
            // 텍스트/로그 전용 — 본문 주입 후 일반 스트리밍 경로 위임 (Vision 미지원 provider 도 동작)
            logger.info("[AI-Chat-Stream] 텍스트 첨부 {}개 본문 주입 — provider={}, 이미지 없음",
                attachments.size(), llmProvider);
            callLlmChatStream(messages, systemPrompt, onChunk, onDone, onError);
            return;
        }
        // 이미지 Vision 경로 — 여기서부터 실제 업스트림 호출이므로 게이트를 건다.
        // (위 두 위임 분기는 base overload 가 이미 게이트를 걸었다 — 중복 차감 방지)
        LlmRateLimitService.Lease lease = acquireGate("chat-stream-vision");
        if (!lease.allowed()) { onError.accept(lease.code(), lease.message()); return; }
        try {
            doVisionChatStream(messages, systemPrompt, imageAtts, onChunk, onDone, onError);
        } finally {
            lease.close();
        }
    }

    /** Vision(이미지 첨부) 스트리밍 실제 구현 — 게이트는 위 overload 가 이미 획득한 상태. */
    private void doVisionChatStream(List<Map<String, String>> messages, String systemPrompt,
                                   List<Map<String, Object>> attachments,
                                   java.util.function.Consumer<String> onChunk,
                                   java.util.function.BiConsumer<String, Long> onDone,
                                   java.util.function.BiConsumer<String, String> onError) {
        if (!llmEnabled) { onError.accept("LLM_DISABLED", "AI 분석 기능이 비활성화 상태입니다."); return; }
        final String apiKey = usableApiKey();
        if (apiKey == null) { onError.accept("NO_API_KEY", apiKeyErrorMessage()); return; }
        if (llmApiUrl == null || llmApiUrl.trim().isEmpty()) { onError.accept("NO_API_URL", "API URL이 설정되지 않았습니다."); return; }

        long startTime = System.currentTimeMillis();
        int approxAttachBytes = attachments.stream()
            .mapToInt(a -> { String d = (String) a.get("data"); return d != null ? d.length() : 0; }).sum();
        logger.info("[AI-Chat-Stream] 스트리밍 요청 시작(Vision) — provider={}, model={}, messageCount={}, attachmentCount={}, approxAttachBytes={}",
            llmProvider, llmModel, messages.size(), attachments.size(), approxAttachBytes);

        try {
            boolean isReasoningModel = llmModel != null && (
                llmModel.startsWith("gpt-5") || llmModel.startsWith("o1") || llmModel.startsWith("o3")
            );
            int effectiveMaxOutputTokens = isReasoningModel
                ? Math.max(llmMaxOutputTokens, 8000)
                : Math.max(llmMaxOutputTokens, 2000);

            java.net.URL url = new java.net.URL(normalizeChatCompletionsUrl(llmApiUrl));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (!llmSslVerify && conn instanceof javax.net.ssl.HttpsURLConnection) {
                disableSslVerification((javax.net.ssl.HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(llmTimeoutConnectSeconds * 1000);
            conn.setReadTimeout(llmTimeoutReadSeconds * 1000);
            conn.setRequestProperty("Content-Type", "application/json");

            List<Map<String, Object>> msgList = buildMsgListWithAttachments(messages, attachments);

            String body;
            boolean isClaude = "claude".equals(llmProvider);
            if (isClaude) {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("model", llmModel);
                reqBody.put("max_tokens", effectiveMaxOutputTokens);
                reqBody.put("stream", true);
                reqBody.put("system", systemPrompt);
                reqBody.put("messages", msgList);
                body = objectMapper.writeValueAsString(reqBody);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                            @SuppressWarnings("unchecked")
                            Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                            String text = null;
                            if (isClaude) {
                                String type = String.valueOf(chunk.get("type"));
                                if ("content_block_delta".equals(type)) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> delta = (Map<String, Object>) chunk.get("delta");
                                    if (delta != null) text = (String) delta.get("text");
                                }
                            } else {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                    if (delta != null) text = (String) delta.get("content");
                                }
                            }
                            if (text != null && !text.isEmpty()) {
                                fullText.append(text);
                                onChunk.accept(text);
                            }
                        } catch (Exception parseErr) { /* 청크 파싱 오류 무시 */ }
                    }
                }
                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("[AI-Chat-Stream] 스트리밍 완료(Vision) — model={}, latency={}ms, textLen={}",
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
                long elapsed = System.currentTimeMillis() - startTime;
                String errorCode = classifyHttpError(code, errBody);
                logger.error("[AI-Chat-Stream] HTTP 오류(Vision) — provider={}, status={}, errorCode={}, elapsed={}ms, body={}",
                    llmProvider, code, errorCode, elapsed, errBody);
                onError.accept(errorCode, buildHttpErrorMessage(code, errBody));
            }
            conn.disconnect();
        } catch (java.net.SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            onError.accept("TIMEOUT", "LLM 응답 대기 시간이 초과되었습니다 (" + elapsed / 1000 + "초).");
        } catch (java.net.ConnectException e) {
            onError.accept("CONNECT_FAILED", "LLM 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            onError.accept("UNKNOWN_HOST", "API URL의 호스트를 찾을 수 없습니다: " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            onError.accept("SSL_ERROR", "SSL 핸드셰이크 실패: " + e.getMessage()
                + " (사내 사설 CA 라면 'SSL 검증' OFF 또는 truststore 설정 필요)");
        } catch (Exception e) {
            logger.error("[AI-Chat-Stream] 예외(Vision) — type={}, msg={}", e.getClass().getSimpleName(), e.getMessage(), e);
            onError.accept("INTERNAL_ERROR", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
        }
    }

    /**
     * 마지막 user 메시지에 attachments를 provider별 Vision 형식으로 주입한다.
     * 나머지 메시지는 기존 {role, content:String} 형식 유지.
     */
    /** 텍스트/로그 첨부 본문 주입 시 컨텍스트 보호용 문자 상한(약 50K 토큰). 초과분은 잘라낸다. */
    private static final int MAX_ATTACH_TEXT_CHARS = 200_000;

    /** 첨부 텍스트(base64) 를 UTF-8 로 디코드. 과대 입력은 잘라 컨텍스트 보호. 실패 시 null. */
    private String decodeAttachmentText(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(base64);
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (s.length() > MAX_ATTACH_TEXT_CHARS) {
                s = s.substring(0, MAX_ATTACH_TEXT_CHARS) + "\n…(이하 생략 — 파일이 커서 일부만 첨부됨)";
            }
            return s;
        } catch (Exception e) {
            logger.warn("[AI-Chat-Attach] 텍스트 첨부 디코드 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 마지막 user 메시지(없으면 마지막 메시지) content 뒤에 텍스트를 덧붙인 새 메시지 리스트 반환. */
    private List<Map<String, String>> appendTextToLastUserMessage(List<Map<String, String>> messages, String extra) {
        int target = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).get("role"))) target = i;
        }
        if (target < 0) target = messages.size() - 1;
        List<Map<String, String>> copy = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> m = new LinkedHashMap<>(messages.get(i));
            if (i == target) {
                String c = m.get("content") != null ? m.get("content") : "";
                m.put("content", c + extra);
            }
            copy.add(m);
        }
        return copy;
    }

    private List<Map<String, Object>> buildMsgListWithAttachments(
            List<Map<String, String>> messages, List<Map<String, Object>> attachments) {
        boolean isClaude = "claude".equals(llmProvider);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> m = messages.get(i);
            boolean isLastUser = i == messages.size() - 1 && "user".equals(m.get("role"));
            if (isLastUser && !attachments.isEmpty()) {
                // Vision content array
                List<Map<String, Object>> contentParts = new ArrayList<>();
                // text part
                Map<String, Object> textPart = new LinkedHashMap<>();
                textPart.put("type", "text");
                textPart.put("text", m.get("content") != null ? m.get("content") : "");
                contentParts.add(textPart);
                // image parts
                for (Map<String, Object> att : attachments) {
                    String mediaType = (String) att.get("mediaType");
                    String data = (String) att.get("data");
                    if (mediaType == null || data == null) continue;
                    if (isClaude) {
                        Map<String, Object> imgPart = new LinkedHashMap<>();
                        imgPart.put("type", "image");
                        Map<String, Object> source = new LinkedHashMap<>();
                        source.put("type", "base64");
                        source.put("media_type", mediaType);
                        source.put("data", data);
                        imgPart.put("source", source);
                        contentParts.add(imgPart);
                    } else {
                        Map<String, Object> imgPart = new LinkedHashMap<>();
                        imgPart.put("type", "image_url");
                        Map<String, Object> imageUrl = new LinkedHashMap<>();
                        imageUrl.put("url", "data:" + mediaType + ";base64," + data);
                        imgPart.put("image_url", imageUrl);
                        contentParts.add(imgPart);
                    }
                }
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "user");
                msg.put("content", contentParts);
                result.add(msg);
            } else {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", m.get("role"));
                msg.put("content", m.get("content"));
                result.add(msg);
            }
        }
        return result;
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
            case 404: base = "API 엔드포인트/모델을 찾을 수 없습니다(404). 확인 항목: "
                + "(1) API URL 경로(예: 끝에 /chat/completions 포함 여부, 사내 게이트웨이의 경우 모델 UUID 정확성), "
                + "(2) 모델명이 게이트웨이에 등록·활성화 되어 있는지, "
                + "(3) 호출 키에 해당 모델 호출 권한이 있는지(사내 게이트웨이는 권한 없음을 404로 회신하기도 함)."; break;
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

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.util.SecretValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 설정 서비스 (Phase 4A-3, Phase 7-3).
 *
 * 책임:
 *   - Elasticsearch 연결 + 검색 + 청킹 + Embedding 26 개 런타임 필드
 *   - password / apiKey / embeddingApiKey 의 AES 암호화 처리
 *   - settings.json / application.properties 영속화 hook
 *   - getter / 그룹 setter (setRagConfig 등 5 개)
 *
 * 영속화 트리거(persistSettings 호출)는 호출자(HeapDumpAnalyzerService/Controller)가 담당.
 */
@Component
public class RagConfigService {

    private static final Logger logger = LoggerFactory.getLogger(RagConfigService.class);

    /** 손상된 시크릿의 마스킹 표기 — 정상처럼 보이면 사용자가 문제를 인지할 수 없다. */
    private static final String CORRUPTED_MASK = "손상됨";

    private final HeapDumpConfig config;

    // ── 기본 (7) ──────────────────────────────────────────────────
    private volatile boolean ragEnabled;
    private volatile String  ragElasticsearchUrl;
    private volatile String  ragAuthType;          // none | basic | api-key
    private volatile String  ragUsername;
    private final SecretValue ragPassword = SecretValue.empty();  // settings.json 에는 ENC
    private final SecretValue ragApiKey   = SecretValue.empty();  // settings.json 에는 ENC
    private volatile String  ragIndex;

    // ── 검색 (6) ──────────────────────────────────────────────────
    private volatile boolean ragSslVerify;
    private volatile String  ragSearchMode;        // keyword | semantic-server | semantic-client
    private volatile String  ragTextField;
    private volatile int     ragTopK;
    private volatile double  ragMinScore;
    private volatile int     ragTimeoutSeconds;

    // ── 청킹 (6) ──────────────────────────────────────────────────
    private volatile boolean ragChunkingEnabled;
    private volatile String  ragChunkingStrategy;       // fixed | paragraph | sentence
    private volatile int     ragChunkingSize;
    private volatile int     ragChunkingOverlap;
    private volatile int     ragChunkingMaxChunksPerDoc;
    private volatile int     ragChunkingMaxTotalChars;

    // ── Semantic-server (4) ──────────────────────────────────────
    private volatile String  ragSemanticQueryType;     // text_expansion | semantic
    private volatile String  ragSemanticModelId;       // ELSER 모델 ID
    private volatile String  ragSemanticTokensField;   // ELSER 토큰 필드
    private volatile String  ragSemanticField;         // semantic_text 필드

    // ── Embedding/kNN (8) ────────────────────────────────────────
    private volatile String  ragEmbeddingProvider;     // openai | cohere | custom
    private volatile String  ragEmbeddingApiUrl;
    private final SecretValue ragEmbeddingApiKey = SecretValue.empty();  // settings.json 에는 ENC
    private volatile String  ragEmbeddingModel;
    private volatile int     ragEmbeddingDimension;
    private volatile int     ragEmbeddingTimeoutSeconds;
    private volatile String  ragKnnVectorField;
    private volatile int     ragKnnNumCandidates;

    public RagConfigService(HeapDumpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.ragEnabled = config.isRagEnabled();
        this.ragElasticsearchUrl = config.getRagElasticsearchUrl();
        this.ragAuthType = config.getRagAuthType();
        this.ragUsername = config.getRagUsername();
        adopt(ragPassword, config.getRagPassword(), "password");
        adopt(ragApiKey, config.getRagApiKey(), "apiKey");
        this.ragIndex = config.getRagIndex();
        this.ragSslVerify = config.isRagSslVerify();
        this.ragSearchMode = config.getRagSearchMode();
        this.ragTextField = config.getRagTextField();
        this.ragTopK = config.getRagTopK();
        this.ragMinScore = config.getRagMinScore();
        this.ragTimeoutSeconds = config.getRagTimeoutSeconds();
        this.ragChunkingEnabled = config.isRagChunkingEnabled();
        this.ragChunkingStrategy = config.getRagChunkingStrategy();
        this.ragChunkingSize = config.getRagChunkingSize();
        this.ragChunkingOverlap = config.getRagChunkingOverlap();
        this.ragChunkingMaxChunksPerDoc = config.getRagChunkingMaxChunksPerDoc();
        this.ragChunkingMaxTotalChars = config.getRagChunkingMaxTotalChars();
        this.ragSemanticQueryType   = config.getRagSemanticQueryType();
        this.ragSemanticModelId     = config.getRagSemanticModelId();
        this.ragSemanticTokensField = config.getRagSemanticTokensField();
        this.ragSemanticField       = config.getRagSemanticField();
        this.ragEmbeddingProvider = config.getRagEmbeddingProvider();
        this.ragEmbeddingApiUrl   = config.getRagEmbeddingApiUrl();
        adopt(ragEmbeddingApiKey, config.getRagEmbeddingApiKey(), "embeddingApiKey");
        this.ragEmbeddingModel    = config.getRagEmbeddingModel();
        this.ragEmbeddingDimension = config.getRagEmbeddingDimension();
        this.ragEmbeddingTimeoutSeconds = config.getRagEmbeddingTimeoutSeconds();
        this.ragKnnVectorField    = config.getRagKnnVectorField();
        this.ragKnnNumCandidates  = config.getRagKnnNumCandidates();
    }

    // ── Getter ────────────────────────────────────────────────────

    public boolean isRagEnabled()           { return ragEnabled; }
    public String  getRagElasticsearchUrl() { return ragElasticsearchUrl; }
    public String  getRagAuthType()         { return ragAuthType; }
    public String  getRagUsername()         { return ragUsername; }
    public String  getRagPassword()         { return ragPassword.usable(); }
    public String  getRagApiKey()           { return ragApiKey.usable(); }
    public String  getRagIndex()            { return ragIndex; }
    public boolean isRagSslVerify()         { return ragSslVerify; }
    public String  getRagSearchMode()       { return ragSearchMode; }
    public String  getRagTextField()        { return ragTextField; }
    public int     getRagTopK()             { return ragTopK; }
    public double  getRagMinScore()         { return ragMinScore; }
    public int     getRagTimeoutSeconds()   { return ragTimeoutSeconds; }
    public boolean isRagChunkingEnabled()         { return ragChunkingEnabled; }
    public String  getRagChunkingStrategy()       { return ragChunkingStrategy; }
    public int     getRagChunkingSize()           { return ragChunkingSize; }
    public int     getRagChunkingOverlap()        { return ragChunkingOverlap; }
    public int     getRagChunkingMaxChunksPerDoc(){ return ragChunkingMaxChunksPerDoc; }
    public int     getRagChunkingMaxTotalChars()  { return ragChunkingMaxTotalChars; }
    public String  getRagSemanticQueryType()    { return ragSemanticQueryType; }
    public String  getRagSemanticModelId()      { return ragSemanticModelId; }
    public String  getRagSemanticTokensField()  { return ragSemanticTokensField; }
    public String  getRagSemanticField()        { return ragSemanticField; }
    public String  getRagEmbeddingProvider()    { return ragEmbeddingProvider; }
    public String  getRagEmbeddingApiUrl()      { return ragEmbeddingApiUrl; }
    public String  getRagEmbeddingApiKey()      { return ragEmbeddingApiKey.usable(); }
    public String  getRagEmbeddingModel()       { return ragEmbeddingModel; }
    public int     getRagEmbeddingDimension()   { return ragEmbeddingDimension; }
    public int     getRagEmbeddingTimeoutSeconds() { return ragEmbeddingTimeoutSeconds; }
    public String  getRagKnnVectorField()       { return ragKnnVectorField; }
    public int     getRagKnnNumCandidates()     { return ragKnnNumCandidates; }

    public boolean isRagPasswordSet()         { return ragPassword.isSet(); }
    public boolean isRagApiKeySet()           { return ragApiKey.isSet(); }
    public boolean isRagEmbeddingApiKeySet()  { return ragEmbeddingApiKey.isSet(); }

    // 손상 상태 — 저장은 돼 있으나 복호화 결과가 훼손돼 사용할 수 없는 경우를 UI/API 에 노출한다.
    public boolean isRagPasswordHealthy()         { return ragPassword.isHealthy(); }
    public boolean isRagApiKeyHealthy()           { return ragApiKey.isHealthy(); }
    public boolean isRagEmbeddingApiKeyHealthy()  { return ragEmbeddingApiKey.isHealthy(); }
    public String  getRagPasswordIssue()          { return nullToEmpty(ragPassword.issue()); }
    public String  getRagApiKeyIssue()            { return nullToEmpty(ragApiKey.issue()); }
    public String  getRagEmbeddingApiKeyIssue()   { return nullToEmpty(ragEmbeddingApiKey.issue()); }

    public String getRagEmbeddingApiKeyMasked() {
        return maskKey(ragEmbeddingApiKey);
    }

    public String getRagPasswordMasked() {
        if (!ragPassword.isHealthy()) return CORRUPTED_MASK;
        String v = ragPassword.raw();
        if (v.isEmpty()) return "";
        if (v.length() < 4) return "****";
        return "****" + v.substring(v.length() - 2);
    }

    public String getRagApiKeyMasked() {
        return maskKey(ragApiKey);
    }

    /** 손상값은 마스킹 대신 손상 표기 — 정상처럼 보이면 사용자가 문제를 인지할 수 없다. */
    private static String maskKey(SecretValue secret) {
        if (!secret.isHealthy()) return CORRUPTED_MASK;
        String v = secret.raw();
        if (v.isEmpty()) return "";
        if (v.length() < 8) return "****";
        return v.substring(0, 4) + "..." + v.substring(v.length() - 4);
    }

    private static String nullToEmpty(String s) { return s != null ? s : ""; }

    // ── Setter (그룹) ─────────────────────────────────────────────

    public void setRagEnabled(boolean enabled) {
        this.ragEnabled = enabled;
        logger.info("[RAG] enabled={}", enabled);
    }

    /**
     * RAG 설정 일괄 업데이트.
     * password/apiKey 파라미터는 null이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 새 값으로 교체.
     */
    public void setRagConfig(String url, String authType, String username,
                             String password, String apiKey, String index, boolean sslVerify,
                             String searchMode, String textField, int topK, double minScore,
                             int timeoutSeconds) {
        this.ragElasticsearchUrl = trimOrEmpty(url);
        this.ragAuthType = (authType == null || authType.isEmpty()) ? "none" : authType;
        this.ragUsername = trimOrEmpty(username);
        if (password != null) this.ragPassword.set(password);
        if (apiKey != null)   this.ragApiKey.set(apiKey);
        this.ragIndex = trimOrEmpty(index);
        this.ragSslVerify = sslVerify;
        this.ragSearchMode = (searchMode == null || searchMode.isEmpty()) ? "keyword" : searchMode;
        this.ragTextField = (textField == null || textField.isEmpty()) ? "content" : textField;
        this.ragTopK = Math.max(1, Math.min(20, topK));
        this.ragMinScore = Math.max(0.0, minScore);
        this.ragTimeoutSeconds = Math.max(1, Math.min(60, timeoutSeconds));
        logger.info("[RAG] config updated: url={}, index={}, mode={}, topK={}",
                ragElasticsearchUrl, ragIndex, ragSearchMode, ragTopK);
    }

    public void setRagSemanticConfig(String queryType, String modelId, String tokensField, String semanticField) {
        if (queryType != null) {
            String q = queryType.trim();
            if (!q.equals("text_expansion") && !q.equals("semantic")) q = "text_expansion";
            this.ragSemanticQueryType = q;
        }
        if (modelId != null)      this.ragSemanticModelId = modelId.trim();
        if (tokensField != null)  this.ragSemanticTokensField = tokensField.trim().isEmpty() ? "ml.tokens" : tokensField.trim();
        if (semanticField != null) this.ragSemanticField = semanticField.trim();
        logger.info("[RAG] semantic-server config updated: queryType={}, modelId={}, tokensField={}, semanticField={}",
                ragSemanticQueryType, ragSemanticModelId, ragSemanticTokensField, ragSemanticField);
    }

    public void setRagEmbeddingConfig(String provider, String apiUrl, String apiKey, String model,
                                      int dimension, int timeoutSeconds, String vectorField, int numCandidates) {
        if (provider != null) {
            String p = provider.trim().toLowerCase();
            if (!p.equals("openai") && !p.equals("cohere") && !p.equals("custom")) p = "openai";
            this.ragEmbeddingProvider = p;
        }
        if (apiUrl != null)      this.ragEmbeddingApiUrl = apiUrl.trim();
        if (apiKey != null)      this.ragEmbeddingApiKey.set(apiKey);
        if (model != null)       this.ragEmbeddingModel = model.trim();
        if (dimension > 0)       this.ragEmbeddingDimension = Math.min(8192, dimension);
        if (timeoutSeconds > 0)  this.ragEmbeddingTimeoutSeconds = Math.max(1, Math.min(120, timeoutSeconds));
        if (vectorField != null) this.ragKnnVectorField = vectorField.trim().isEmpty() ? "embedding" : vectorField.trim();
        if (numCandidates > 0)   this.ragKnnNumCandidates = Math.max(1, Math.min(10000, numCandidates));
        logger.info("[RAG] semantic-client config updated: provider={}, model={}, dim={}, vectorField={}, numCandidates={}",
                ragEmbeddingProvider, ragEmbeddingModel, ragEmbeddingDimension, ragKnnVectorField, ragKnnNumCandidates);
    }

    public void setRagChunkingConfig(boolean enabled, String strategy, int size, int overlap,
                                     int maxChunksPerDoc, int maxTotalChars) {
        this.ragChunkingEnabled = enabled;
        String s = (strategy == null) ? "fixed" : strategy.toLowerCase();
        if (!s.equals("fixed") && !s.equals("paragraph") && !s.equals("sentence")) s = "fixed";
        this.ragChunkingStrategy = s;
        this.ragChunkingSize = Math.max(100, Math.min(8000, size));
        this.ragChunkingOverlap = Math.max(0, Math.min(this.ragChunkingSize - 1, overlap));
        this.ragChunkingMaxChunksPerDoc = Math.max(1, Math.min(20, maxChunksPerDoc));
        this.ragChunkingMaxTotalChars = Math.max(500, Math.min(50000, maxTotalChars));
        logger.info("[RAG] chunking updated: enabled={}, strategy={}, size={}, overlap={}, maxPerDoc={}, maxTotal={}",
                ragChunkingEnabled, ragChunkingStrategy, ragChunkingSize, ragChunkingOverlap,
                ragChunkingMaxChunksPerDoc, ragChunkingMaxTotalChars);
    }

    private static String trimOrEmpty(String s) { return s == null ? "" : s.trim(); }

    // ── Settings 영속화 hook ─────────────────────────────────────

    public void applyFromSettings(Map<String, Object> saved) {
        str(saved, "ragElasticsearchUrl",     v -> this.ragElasticsearchUrl = v);
        str(saved, "ragAuthType",             v -> this.ragAuthType = v);
        str(saved, "ragUsername",             v -> this.ragUsername = v);
        str(saved, "ragPassword",             v -> adopt(ragPassword, v, "password"));
        str(saved, "ragApiKey",               v -> adopt(ragApiKey, v, "apiKey"));
        str(saved, "ragIndex",                v -> this.ragIndex = v);
        str(saved, "ragSearchMode",           v -> this.ragSearchMode = v);
        str(saved, "ragTextField",            v -> this.ragTextField = v);
        str(saved, "ragChunkingStrategy",     v -> this.ragChunkingStrategy = v);
        str(saved, "ragSemanticQueryType",    v -> this.ragSemanticQueryType = v);
        str(saved, "ragSemanticModelId",      v -> this.ragSemanticModelId = v);
        str(saved, "ragSemanticTokensField",  v -> this.ragSemanticTokensField = v);
        str(saved, "ragSemanticField",        v -> this.ragSemanticField = v);
        str(saved, "ragEmbeddingProvider",    v -> this.ragEmbeddingProvider = v);
        str(saved, "ragEmbeddingApiUrl",      v -> this.ragEmbeddingApiUrl = v);
        str(saved, "ragEmbeddingApiKey",      v -> adopt(ragEmbeddingApiKey, v, "embeddingApiKey"));
        str(saved, "ragEmbeddingModel",       v -> this.ragEmbeddingModel = v);
        str(saved, "ragKnnVectorField",       v -> this.ragKnnVectorField = v);
        str(saved, "ragEnabled",              v -> this.ragEnabled = Boolean.parseBoolean(v));
        str(saved, "ragSslVerify",            v -> this.ragSslVerify = Boolean.parseBoolean(v));
        str(saved, "ragChunkingEnabled",      v -> this.ragChunkingEnabled = Boolean.parseBoolean(v));
        str(saved, "ragTopK",                 v -> this.ragTopK = Integer.parseInt(v));
        str(saved, "ragMinScore",             v -> this.ragMinScore = Double.parseDouble(v));
        str(saved, "ragTimeoutSeconds",       v -> this.ragTimeoutSeconds = Integer.parseInt(v));
        str(saved, "ragChunkingSize",         v -> this.ragChunkingSize = Integer.parseInt(v));
        str(saved, "ragChunkingOverlap",      v -> this.ragChunkingOverlap = Integer.parseInt(v));
        str(saved, "ragChunkingMaxChunksPerDoc", v -> this.ragChunkingMaxChunksPerDoc = Integer.parseInt(v));
        str(saved, "ragChunkingMaxTotalChars",   v -> this.ragChunkingMaxTotalChars = Integer.parseInt(v));
        str(saved, "ragEmbeddingDimension",   v -> this.ragEmbeddingDimension = Integer.parseInt(v));
        str(saved, "ragEmbeddingTimeoutSeconds", v -> this.ragEmbeddingTimeoutSeconds = Integer.parseInt(v));
        str(saved, "ragKnnNumCandidates",     v -> this.ragKnnNumCandidates = Integer.parseInt(v));
    }

    /** saved 에 key 가 있으면 String.valueOf 값으로 apply 실행 (기존 if-블록 31개와 동일 시맨틱). */
    private static void str(Map<String, Object> saved, String key, java.util.function.Consumer<String> apply) {
        if (saved.containsKey(key)) apply.accept(String.valueOf(saved.get(key)));
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("ragEnabled", ragEnabled);
        settings.put("ragElasticsearchUrl", ragElasticsearchUrl);
        settings.put("ragAuthType", ragAuthType);
        settings.put("ragUsername", ragUsername);
        putSecret(settings, "ragPassword", ragPassword);
        putSecret(settings, "ragApiKey", ragApiKey);
        settings.put("ragIndex", ragIndex);
        settings.put("ragSslVerify", ragSslVerify);
        settings.put("ragSearchMode", ragSearchMode);
        settings.put("ragTextField", ragTextField);
        settings.put("ragTopK", ragTopK);
        settings.put("ragMinScore", ragMinScore);
        settings.put("ragTimeoutSeconds", ragTimeoutSeconds);
        settings.put("ragChunkingEnabled", ragChunkingEnabled);
        settings.put("ragChunkingStrategy", ragChunkingStrategy);
        settings.put("ragChunkingSize", ragChunkingSize);
        settings.put("ragChunkingOverlap", ragChunkingOverlap);
        settings.put("ragChunkingMaxChunksPerDoc", ragChunkingMaxChunksPerDoc);
        settings.put("ragChunkingMaxTotalChars", ragChunkingMaxTotalChars);
        settings.put("ragSemanticQueryType", ragSemanticQueryType);
        settings.put("ragSemanticModelId", ragSemanticModelId);
        settings.put("ragSemanticTokensField", ragSemanticTokensField);
        settings.put("ragSemanticField", ragSemanticField);
        settings.put("ragEmbeddingProvider", ragEmbeddingProvider);
        settings.put("ragEmbeddingApiUrl", ragEmbeddingApiUrl);
        putSecret(settings, "ragEmbeddingApiKey", ragEmbeddingApiKey);
        settings.put("ragEmbeddingModel", ragEmbeddingModel);
        settings.put("ragEmbeddingDimension", ragEmbeddingDimension);
        settings.put("ragEmbeddingTimeoutSeconds", ragEmbeddingTimeoutSeconds);
        settings.put("ragKnnVectorField", ragKnnVectorField);
        settings.put("ragKnnNumCandidates", ragKnnNumCandidates);
    }

    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("rag.enabled", String.valueOf(ragEnabled));
        updates.put("rag.elasticsearch.url", ragElasticsearchUrl != null ? ragElasticsearchUrl : "");
        updates.put("rag.elasticsearch.auth-type", ragAuthType != null ? ragAuthType : "none");
        updates.put("rag.elasticsearch.username", ragUsername != null ? ragUsername : "");
        putSecret(updates, "rag.elasticsearch.password", ragPassword);
        putSecret(updates, "rag.elasticsearch.api-key", ragApiKey);
        updates.put("rag.elasticsearch.index", ragIndex != null ? ragIndex : "");
        updates.put("rag.elasticsearch.ssl-verify", String.valueOf(ragSslVerify));
        updates.put("rag.search.mode", ragSearchMode != null ? ragSearchMode : "keyword");
        updates.put("rag.search.text-field", ragTextField != null ? ragTextField : "content");
        updates.put("rag.search.top-k", String.valueOf(ragTopK));
        updates.put("rag.search.min-score", String.valueOf(ragMinScore));
        updates.put("rag.search.timeout-seconds", String.valueOf(ragTimeoutSeconds));
        updates.put("rag.chunking.enabled", String.valueOf(ragChunkingEnabled));
        updates.put("rag.chunking.strategy", ragChunkingStrategy != null ? ragChunkingStrategy : "fixed");
        updates.put("rag.chunking.size", String.valueOf(ragChunkingSize));
        updates.put("rag.chunking.overlap", String.valueOf(ragChunkingOverlap));
        updates.put("rag.chunking.max-chunks-per-doc", String.valueOf(ragChunkingMaxChunksPerDoc));
        updates.put("rag.chunking.max-total-chars", String.valueOf(ragChunkingMaxTotalChars));
        updates.put("rag.search.semantic.query-type", ragSemanticQueryType != null ? ragSemanticQueryType : "text_expansion");
        updates.put("rag.search.semantic.model-id", ragSemanticModelId != null ? ragSemanticModelId : "");
        updates.put("rag.search.semantic.tokens-field", ragSemanticTokensField != null ? ragSemanticTokensField : "ml.tokens");
        updates.put("rag.search.semantic.semantic-field", ragSemanticField != null ? ragSemanticField : "");
        updates.put("rag.embedding.provider", ragEmbeddingProvider != null ? ragEmbeddingProvider : "openai");
        updates.put("rag.embedding.api.url", ragEmbeddingApiUrl != null ? ragEmbeddingApiUrl : "");
        putSecret(updates, "rag.embedding.api.key", ragEmbeddingApiKey);
        updates.put("rag.embedding.model", ragEmbeddingModel != null ? ragEmbeddingModel : "");
        updates.put("rag.embedding.dimension", String.valueOf(ragEmbeddingDimension));
        updates.put("rag.embedding.timeout-seconds", String.valueOf(ragEmbeddingTimeoutSeconds));
        updates.put("rag.search.knn.vector-field", ragKnnVectorField != null ? ragKnnVectorField : "embedding");
        updates.put("rag.search.knn.num-candidates", String.valueOf(ragKnnNumCandidates));
    }

    /**
     * 시크릿을 저장 맵에 기록. 암호화 실패(forStorage()==null)면 <b>키 자체를 생략</b>해
     * 기존 저장값을 보존한다 — 빈 문자열로 덮으면 시크릿이 무경고로 삭제된다.
     */
    private static <T> void putSecret(Map<String, T> target, String key, SecretValue secret) {
        String stored = secret.forStorage();
        if (stored == null) {
            logger.error("[Settings] '{}' AES 암호화 실패 — 키를 기록하지 않고 기존 저장값을 유지합니다", key);
            return;
        }
        @SuppressWarnings("unchecked")
        T value = (T) stored;
        target.put(key, value);
    }

    /** 저장값을 SecretValue 에 로드하고 손상 시 경고. 예외를 던지지 않는다. */
    private static void adopt(SecretValue target, String stored, String label) {
        SecretValue loaded = SecretValue.load(stored);
        target.adoptFrom(loaded);
        if (!loaded.isHealthy()) {
            logger.warn("[RAG] 저장된 {} 를 사용할 수 없습니다 — {} (/settings/rag 에서 재입력 필요)",
                    label, loaded.issue());
        }
    }
}

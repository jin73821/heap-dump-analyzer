package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.util.AesEncryptor;
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

    private final HeapDumpConfig config;

    // ── 기본 (7) ──────────────────────────────────────────────────
    private volatile boolean ragEnabled;
    private volatile String  ragElasticsearchUrl;
    private volatile String  ragAuthType;          // none | basic | api-key
    private volatile String  ragUsername;
    private volatile String  ragPassword;          // 평문 보관 (settings.json 에는 ENC)
    private volatile String  ragApiKey;            // 평문 보관 (settings.json 에는 ENC)
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
    private volatile String  ragEmbeddingApiKey;       // 평문 보관 (settings.json 에는 ENC)
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
        this.ragPassword = AesEncryptor.decryptIfEncrypted(config.getRagPassword());
        this.ragApiKey = AesEncryptor.decryptIfEncrypted(config.getRagApiKey());
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
        this.ragEmbeddingApiKey   = AesEncryptor.decryptIfEncrypted(config.getRagEmbeddingApiKey());
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
    public String  getRagPassword()         { return ragPassword; }
    public String  getRagApiKey()           { return ragApiKey; }
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
    public String  getRagEmbeddingApiKey()      { return ragEmbeddingApiKey; }
    public String  getRagEmbeddingModel()       { return ragEmbeddingModel; }
    public int     getRagEmbeddingDimension()   { return ragEmbeddingDimension; }
    public int     getRagEmbeddingTimeoutSeconds() { return ragEmbeddingTimeoutSeconds; }
    public String  getRagKnnVectorField()       { return ragKnnVectorField; }
    public int     getRagKnnNumCandidates()     { return ragKnnNumCandidates; }

    public boolean isRagPasswordSet()         { return ragPassword != null && !ragPassword.trim().isEmpty(); }
    public boolean isRagApiKeySet()           { return ragApiKey != null && !ragApiKey.trim().isEmpty(); }
    public boolean isRagEmbeddingApiKeySet()  { return ragEmbeddingApiKey != null && !ragEmbeddingApiKey.trim().isEmpty(); }

    public String getRagEmbeddingApiKeyMasked() {
        if (ragEmbeddingApiKey == null || ragEmbeddingApiKey.isEmpty()) return "";
        if (ragEmbeddingApiKey.length() < 8) return "****";
        return ragEmbeddingApiKey.substring(0, 4) + "..." + ragEmbeddingApiKey.substring(ragEmbeddingApiKey.length() - 4);
    }

    public String getRagPasswordMasked() {
        if (ragPassword == null || ragPassword.isEmpty()) return "";
        if (ragPassword.length() < 4) return "****";
        return "****" + ragPassword.substring(ragPassword.length() - 2);
    }

    public String getRagApiKeyMasked() {
        if (ragApiKey == null || ragApiKey.isEmpty()) return "";
        if (ragApiKey.length() < 8) return "****";
        return ragApiKey.substring(0, 4) + "..." + ragApiKey.substring(ragApiKey.length() - 4);
    }

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
        if (password != null) this.ragPassword = password;
        if (apiKey != null)   this.ragApiKey = apiKey;
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
        if (apiKey != null)      this.ragEmbeddingApiKey = apiKey;
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
        if (saved.containsKey("ragEnabled")) {
            this.ragEnabled = Boolean.parseBoolean(String.valueOf(saved.get("ragEnabled")));
        }
        if (saved.containsKey("ragElasticsearchUrl")) {
            this.ragElasticsearchUrl = String.valueOf(saved.get("ragElasticsearchUrl"));
        }
        if (saved.containsKey("ragAuthType")) {
            this.ragAuthType = String.valueOf(saved.get("ragAuthType"));
        }
        if (saved.containsKey("ragUsername")) {
            this.ragUsername = String.valueOf(saved.get("ragUsername"));
        }
        if (saved.containsKey("ragPassword")) {
            this.ragPassword = AesEncryptor.decryptIfEncrypted(String.valueOf(saved.get("ragPassword")));
        }
        if (saved.containsKey("ragApiKey")) {
            this.ragApiKey = AesEncryptor.decryptIfEncrypted(String.valueOf(saved.get("ragApiKey")));
        }
        if (saved.containsKey("ragIndex")) {
            this.ragIndex = String.valueOf(saved.get("ragIndex"));
        }
        if (saved.containsKey("ragSslVerify")) {
            this.ragSslVerify = Boolean.parseBoolean(String.valueOf(saved.get("ragSslVerify")));
        }
        if (saved.containsKey("ragSearchMode")) {
            this.ragSearchMode = String.valueOf(saved.get("ragSearchMode"));
        }
        if (saved.containsKey("ragTextField")) {
            this.ragTextField = String.valueOf(saved.get("ragTextField"));
        }
        if (saved.containsKey("ragTopK")) {
            this.ragTopK = Integer.parseInt(String.valueOf(saved.get("ragTopK")));
        }
        if (saved.containsKey("ragMinScore")) {
            this.ragMinScore = Double.parseDouble(String.valueOf(saved.get("ragMinScore")));
        }
        if (saved.containsKey("ragTimeoutSeconds")) {
            this.ragTimeoutSeconds = Integer.parseInt(String.valueOf(saved.get("ragTimeoutSeconds")));
        }
        if (saved.containsKey("ragChunkingEnabled")) {
            this.ragChunkingEnabled = Boolean.parseBoolean(String.valueOf(saved.get("ragChunkingEnabled")));
        }
        if (saved.containsKey("ragChunkingStrategy")) {
            this.ragChunkingStrategy = String.valueOf(saved.get("ragChunkingStrategy"));
        }
        if (saved.containsKey("ragChunkingSize")) {
            this.ragChunkingSize = Integer.parseInt(String.valueOf(saved.get("ragChunkingSize")));
        }
        if (saved.containsKey("ragChunkingOverlap")) {
            this.ragChunkingOverlap = Integer.parseInt(String.valueOf(saved.get("ragChunkingOverlap")));
        }
        if (saved.containsKey("ragChunkingMaxChunksPerDoc")) {
            this.ragChunkingMaxChunksPerDoc = Integer.parseInt(String.valueOf(saved.get("ragChunkingMaxChunksPerDoc")));
        }
        if (saved.containsKey("ragChunkingMaxTotalChars")) {
            this.ragChunkingMaxTotalChars = Integer.parseInt(String.valueOf(saved.get("ragChunkingMaxTotalChars")));
        }
        if (saved.containsKey("ragSemanticQueryType")) {
            this.ragSemanticQueryType = String.valueOf(saved.get("ragSemanticQueryType"));
        }
        if (saved.containsKey("ragSemanticModelId")) {
            this.ragSemanticModelId = String.valueOf(saved.get("ragSemanticModelId"));
        }
        if (saved.containsKey("ragSemanticTokensField")) {
            this.ragSemanticTokensField = String.valueOf(saved.get("ragSemanticTokensField"));
        }
        if (saved.containsKey("ragSemanticField")) {
            this.ragSemanticField = String.valueOf(saved.get("ragSemanticField"));
        }
        if (saved.containsKey("ragEmbeddingProvider")) {
            this.ragEmbeddingProvider = String.valueOf(saved.get("ragEmbeddingProvider"));
        }
        if (saved.containsKey("ragEmbeddingApiUrl")) {
            this.ragEmbeddingApiUrl = String.valueOf(saved.get("ragEmbeddingApiUrl"));
        }
        if (saved.containsKey("ragEmbeddingApiKey")) {
            this.ragEmbeddingApiKey = AesEncryptor.decryptIfEncrypted(String.valueOf(saved.get("ragEmbeddingApiKey")));
        }
        if (saved.containsKey("ragEmbeddingModel")) {
            this.ragEmbeddingModel = String.valueOf(saved.get("ragEmbeddingModel"));
        }
        if (saved.containsKey("ragEmbeddingDimension")) {
            this.ragEmbeddingDimension = Integer.parseInt(String.valueOf(saved.get("ragEmbeddingDimension")));
        }
        if (saved.containsKey("ragEmbeddingTimeoutSeconds")) {
            this.ragEmbeddingTimeoutSeconds = Integer.parseInt(String.valueOf(saved.get("ragEmbeddingTimeoutSeconds")));
        }
        if (saved.containsKey("ragKnnVectorField")) {
            this.ragKnnVectorField = String.valueOf(saved.get("ragKnnVectorField"));
        }
        if (saved.containsKey("ragKnnNumCandidates")) {
            this.ragKnnNumCandidates = Integer.parseInt(String.valueOf(saved.get("ragKnnNumCandidates")));
        }
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("ragEnabled", ragEnabled);
        settings.put("ragElasticsearchUrl", ragElasticsearchUrl);
        settings.put("ragAuthType", ragAuthType);
        settings.put("ragUsername", ragUsername);
        settings.put("ragPassword", encryptForStorage(ragPassword));
        settings.put("ragApiKey", encryptForStorage(ragApiKey));
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
        settings.put("ragEmbeddingApiKey", encryptForStorage(ragEmbeddingApiKey));
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
        updates.put("rag.elasticsearch.password", encryptForStorage(ragPassword));
        updates.put("rag.elasticsearch.api-key", encryptForStorage(ragApiKey));
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
        updates.put("rag.embedding.api.key", encryptForStorage(ragEmbeddingApiKey));
        updates.put("rag.embedding.model", ragEmbeddingModel != null ? ragEmbeddingModel : "");
        updates.put("rag.embedding.dimension", String.valueOf(ragEmbeddingDimension));
        updates.put("rag.embedding.timeout-seconds", String.valueOf(ragEmbeddingTimeoutSeconds));
        updates.put("rag.search.knn.vector-field", ragKnnVectorField != null ? ragKnnVectorField : "embedding");
        updates.put("rag.search.knn.num-candidates", String.valueOf(ragKnnNumCandidates));
    }

    /** 평문을 ENC(...) 형식 암호문으로 변환. 빈 값은 그대로 빈 문자열. */
    private static String encryptForStorage(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            return "ENC(" + AesEncryptor.encrypt(plain) + ")";
        } catch (Exception e) {
            logger.warn("[Settings] AES 암호화 실패, 평문 저장 회피: {}", e.getMessage());
            return "";
        }
    }
}

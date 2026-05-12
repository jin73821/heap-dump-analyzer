package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Elasticsearch 기반 RAG 검색 서비스.
 *
 * Phase 2: keyword(BM25) / semantic-server(ES inference) / semantic-client(앱측 임베딩 + kNN) 지원.
 */
@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RagConfigService ragConfig;
    private final EmbeddingService embeddingService;

    public RagService(RagConfigService ragConfig, EmbeddingService embeddingService) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
    }

    /**
     * 활성화된 RAG로 질의를 검색하여 LLM에 주입할 컨텍스트 문자열을 생성.
     * 비활성화/미설정/검색 실패 시 빈 문자열 반환 — 호출자는 항상 안전하게 사용 가능.
     *
     * 청킹 활성화 시: 각 검색 결과의 본문을 작은 청크로 분할하여 전체 글자수 한도 내에서 주입.
     */
    public String fetchContextForLlm(String query) {
        if (!ragConfig.isRagEnabled()) return "";
        if (query == null || query.trim().isEmpty()) return "";

        Map<String, Object> result = search(query, null);
        if (!Boolean.TRUE.equals(result.get("success"))) return "";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        if (hits == null || hits.isEmpty()) return "";

        boolean chunking = ragConfig.isRagChunkingEnabled();
        int maxTotalChars = ragConfig.getRagChunkingMaxTotalChars();
        int maxPerDoc = ragConfig.getRagChunkingMaxChunksPerDoc();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[참고 자료 (RAG)]\n");
        sb.append("아래는 사내 지식베이스에서 검색된 관련 자료입니다. 답변 시 참고하되, 자료에 없는 내용은 추측하지 마세요.\n\n");

        int totalChars = 0;
        int docIdx = 1;
        outer:
        for (Map<String, Object> hit : hits) {
            Object content = hit.get("content");
            if (content == null) continue;
            String text = String.valueOf(content).trim();
            if (text.isEmpty()) continue;

            List<String> chunks;
            if (chunking) {
                chunks = chunkText(text,
                        ragConfig.getRagChunkingStrategy(),
                        ragConfig.getRagChunkingSize(),
                        ragConfig.getRagChunkingOverlap(),
                        maxPerDoc);
            } else {
                chunks = Collections.singletonList(text);
            }

            int chunkIdx = 1;
            for (String chunk : chunks) {
                String header = "--- 자료 " + docIdx + (chunks.size() > 1 ? "." + chunkIdx : "") + " ---\n";
                int needed = header.length() + chunk.length() + 2;
                if (chunking && totalChars + needed > maxTotalChars) {
                    if (totalChars == 0) {
                        // 첫 청크가 한도 초과 — 잘라서라도 주입
                        int budget = Math.max(0, maxTotalChars - header.length() - 4);
                        sb.append(header).append(chunk, 0, Math.min(chunk.length(), budget)).append("...\n\n");
                        totalChars = sb.length();
                    }
                    break outer;
                }
                sb.append(header).append(chunk).append("\n\n");
                totalChars += needed;
                chunkIdx++;
            }
            docIdx++;
        }
        return sb.toString();
    }

    // ── 청킹 ───────────────────────────────────────────────────

    /**
     * 텍스트를 청크 리스트로 분할.
     * @param strategy fixed | paragraph | sentence
     */
    static List<String> chunkText(String text, String strategy, int size, int overlap, int maxChunks) {
        if (text == null) return Collections.emptyList();
        text = text.trim();
        if (text.isEmpty()) return Collections.emptyList();
        if (strategy == null) strategy = "fixed";

        List<String> chunks;
        switch (strategy.toLowerCase()) {
            case "paragraph":
                chunks = chunkByParagraph(text, size);
                break;
            case "sentence":
                chunks = chunkBySentence(text, size, overlap);
                break;
            case "fixed":
            default:
                chunks = chunkFixed(text, size, overlap);
                break;
        }
        if (chunks.size() > maxChunks) {
            chunks = chunks.subList(0, maxChunks);
        }
        return chunks;
    }

    private static List<String> chunkFixed(String text, int size, int overlap) {
        if (size <= 0) size = 800;
        if (overlap < 0) overlap = 0;
        if (overlap >= size) overlap = size / 2;
        List<String> out = new ArrayList<>();
        int len = text.length();
        if (len <= size) { out.add(text); return out; }
        int step = size - overlap;
        for (int start = 0; start < len; start += step) {
            int end = Math.min(start + size, len);
            out.add(text.substring(start, end));
            if (end >= len) break;
        }
        return out;
    }

    private static List<String> chunkByParagraph(String text, int size) {
        // 빈 줄(연속 \n) 단위로 자른 뒤, size 한도 내에서 머지
        String[] paragraphs = text.split("\\n{2,}");
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            String para = p.trim();
            if (para.isEmpty()) continue;
            if (buf.length() == 0) {
                buf.append(para);
            } else if (buf.length() + 2 + para.length() <= size) {
                buf.append("\n\n").append(para);
            } else {
                out.add(buf.toString());
                buf.setLength(0);
                buf.append(para);
            }
            // 단일 문단이 size를 넘으면 fixed로 분할
            if (buf.length() > size) {
                out.addAll(chunkFixed(buf.toString(), size, 0));
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    private static List<String> chunkBySentence(String text, int size, int overlap) {
        // 한국어/영문 문장 종결: . ? ! 。 ？ ! 다음 공백
        String[] sentences = text.split("(?<=[\\.!?。？！])\\s+");
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String tail = "";
        for (String s : sentences) {
            String sent = s.trim();
            if (sent.isEmpty()) continue;
            if (buf.length() == 0) {
                if (!tail.isEmpty()) buf.append(tail).append(' ');
                buf.append(sent);
            } else if (buf.length() + 1 + sent.length() <= size) {
                buf.append(' ').append(sent);
            } else {
                String chunk = buf.toString();
                out.add(chunk);
                tail = overlap > 0 && chunk.length() > overlap
                        ? chunk.substring(chunk.length() - overlap)
                        : "";
                buf.setLength(0);
                if (!tail.isEmpty()) buf.append(tail).append(' ');
                buf.append(sent);
            }
            // 한 문장이 size를 넘으면 fixed로 분할
            if (buf.length() > size * 1.5) {
                out.addAll(chunkFixed(buf.toString(), size, overlap));
                buf.setLength(0);
                tail = "";
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /**
     * Elasticsearch 검색 호출.
     * @param query 검색어
     * @param overrides null이면 현재 저장된 설정 사용. 테스트 연결 시 일시 설정 전달용.
     * @return success/hits/error 등 키를 포함한 결과 맵
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(String query, Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();

        String url    = pickStr(overrides, "url",    ragConfig.getRagElasticsearchUrl());
        String index  = pickStr(overrides, "index",  ragConfig.getRagIndex());
        String auth   = pickStr(overrides, "authType", ragConfig.getRagAuthType());
        String user   = pickStr(overrides, "username", ragConfig.getRagUsername());
        String pass   = pickStr(overrides, "password", ragConfig.getRagPassword());
        String apiKey = pickStr(overrides, "apiKey",   ragConfig.getRagApiKey());
        boolean sslVerify = pickBool(overrides, "sslVerify", ragConfig.isRagSslVerify());
        String mode   = pickStr(overrides, "searchMode", ragConfig.getRagSearchMode());
        String field  = pickStr(overrides, "textField",  ragConfig.getRagTextField());
        int topK      = pickInt(overrides, "topK",     ragConfig.getRagTopK());
        double minScore = pickDouble(overrides, "minScore", ragConfig.getRagMinScore());
        int timeoutSec = pickInt(overrides, "timeoutSeconds", ragConfig.getRagTimeoutSeconds());

        if (url == null || url.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "Elasticsearch URL이 설정되지 않았습니다.");
            return result;
        }
        if (index == null || index.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "검색 대상 인덱스가 설정되지 않았습니다.");
            return result;
        }

        try {
            String searchUrl = stripTrailingSlash(url) + "/" + index + "/_search";
            String body;
            try {
                body = buildQueryBody(mode, field, query, topK, minScore, overrides);
            } catch (Exception qbe) {
                logger.warn("[RAG] 쿼리 본문 생성 실패 — mode={}: {}", mode, qbe.getMessage());
                result.put("success", false);
                result.put("error", "쿼리 생성 실패: " + qbe.getMessage());
                return result;
            }

            HttpURLConnection conn = openConnection(searchUrl, sslVerify);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestProperty("Content-Type", "application/json");
            applyAuth(conn, auth, user, pass, apiKey);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseBody = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());

            if (code < 200 || code >= 300) {
                logger.warn("[RAG] 검색 실패 — status={}, body={}", code, truncate(responseBody, 500));
                result.put("success", false);
                result.put("httpStatus", code);
                result.put("error", "Elasticsearch 응답 오류 (HTTP " + code + "): " + truncate(responseBody, 200));
                return result;
            }

            Map<String, Object> json = objectMapper.readValue(responseBody, Map.class);
            Map<String, Object> hitsWrap = (Map<String, Object>) json.get("hits");
            List<Map<String, Object>> rawHits = hitsWrap != null
                    ? (List<Map<String, Object>>) hitsWrap.get("hits")
                    : Collections.emptyList();

            List<Map<String, Object>> hits = new ArrayList<>();
            for (Map<String, Object> h : rawHits) {
                double score = h.get("_score") != null ? ((Number) h.get("_score")).doubleValue() : 0.0;
                if (minScore > 0 && score < minScore) continue;
                Map<String, Object> source = (Map<String, Object>) h.get("_source");
                String content = extractContent(source, field);
                Map<String, Object> simplified = new LinkedHashMap<>();
                simplified.put("id", h.get("_id"));
                simplified.put("score", score);
                simplified.put("content", content);
                hits.add(simplified);
            }

            result.put("success", true);
            result.put("total", hitsWrap != null ? hitsWrap.get("total") : 0);
            result.put("hits", hits);
            return result;
        } catch (Exception e) {
            logger.error("[RAG] 검색 예외 — {}: {}", e.getClass().getSimpleName(), e.getMessage());
            result.put("success", false);
            result.put("error", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
            return result;
        }
    }

    /**
     * 연결 테스트 — cluster health + 인덱스 존재 확인.
     * overrides 맵으로 저장 전 설정값 검증 가능.
     */
    public Map<String, Object> testConnection(Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();

        String url    = pickStr(overrides, "url",    ragConfig.getRagElasticsearchUrl());
        String index  = pickStr(overrides, "index",  ragConfig.getRagIndex());
        String auth   = pickStr(overrides, "authType", ragConfig.getRagAuthType());
        String user   = pickStr(overrides, "username", ragConfig.getRagUsername());
        String pass   = pickStr(overrides, "password", ragConfig.getRagPassword());
        String apiKey = pickStr(overrides, "apiKey",   ragConfig.getRagApiKey());
        boolean sslVerify = pickBool(overrides, "sslVerify", ragConfig.isRagSslVerify());
        int timeoutSec = pickInt(overrides, "timeoutSeconds", ragConfig.getRagTimeoutSeconds());

        if (url == null || url.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "Elasticsearch URL이 설정되지 않았습니다.");
            return result;
        }

        try {
            // 1) cluster health
            String healthUrl = stripTrailingSlash(url) + "/_cluster/health";
            HttpURLConnection conn = openConnection(healthUrl, sslVerify);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutSec * 1000);
            conn.setReadTimeout(timeoutSec * 1000);
            applyAuth(conn, auth, user, pass, apiKey);
            int code = conn.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                result.put("success", false);
                result.put("httpStatus", code);
                result.put("error", "Cluster health 호출 실패 (HTTP " + code + "): " + truncate(body, 200));
                return result;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> health = objectMapper.readValue(body, Map.class);
            result.put("clusterName", health.get("cluster_name"));
            result.put("status", health.get("status"));

            // 2) 인덱스 존재 확인 (선택)
            if (index != null && !index.trim().isEmpty()) {
                String existsUrl = stripTrailingSlash(url) + "/" + index;
                HttpURLConnection c2 = openConnection(existsUrl, sslVerify);
                c2.setRequestMethod("HEAD");
                c2.setConnectTimeout(timeoutSec * 1000);
                c2.setReadTimeout(timeoutSec * 1000);
                applyAuth(c2, auth, user, pass, apiKey);
                int existsCode = c2.getResponseCode();
                result.put("indexExists", existsCode == 200);
                result.put("indexHttpStatus", existsCode);
                if (existsCode == 404) {
                    result.put("warning", "인덱스 '" + index + "'가 존재하지 않습니다.");
                }
            }

            result.put("success", true);
            return result;
        } catch (Exception e) {
            logger.error("[RAG] 연결 테스트 예외 — {}: {}", e.getClass().getSimpleName(), e.getMessage());
            result.put("success", false);
            result.put("error", "[" + e.getClass().getSimpleName() + "] " + e.getMessage());
            return result;
        }
    }

    // ── 내부 유틸 ────────────────────────────────────────────────

    private String buildQueryBody(String mode, String field, String query, int topK, double minScore,
                                  Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", topK);
        if (minScore > 0) body.put("min_score", minScore);

        String m = mode == null ? "keyword" : mode.toLowerCase();
        switch (m) {
            case "semantic-server":
                buildSemanticServerQuery(body, query, overrides);
                break;
            case "semantic-client":
                buildSemanticClientQuery(body, query, topK, overrides);
                break;
            case "keyword":
            default: {
                Map<String, Object> match = new LinkedHashMap<>();
                match.put(field, query);
                Map<String, Object> matchWrap = new LinkedHashMap<>();
                matchWrap.put("match", match);
                body.put("query", matchWrap);
                break;
            }
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * Phase 2 — ES 서버측 임베딩 (text_expansion / semantic).
     */
    private void buildSemanticServerQuery(Map<String, Object> body, String query, Map<String, Object> overrides) {
        String queryType    = pickStr(overrides, "semanticQueryType", ragConfig.getRagSemanticQueryType());
        String modelId      = pickStr(overrides, "semanticModelId", ragConfig.getRagSemanticModelId());
        String tokensField  = pickStr(overrides, "semanticTokensField", ragConfig.getRagSemanticTokensField());
        String semanticField = pickStr(overrides, "semanticField", ragConfig.getRagSemanticField());

        if (queryType == null || queryType.trim().isEmpty()) queryType = "text_expansion";

        if ("semantic".equalsIgnoreCase(queryType)) {
            // ES 8.11+ semantic_text 쿼리: { semantic: { field: "...", query: "..." } }
            if (semanticField == null || semanticField.trim().isEmpty()) {
                throw new IllegalStateException("semantic 쿼리에는 semantic-field 설정이 필요합니다");
            }
            Map<String, Object> semantic = new LinkedHashMap<>();
            semantic.put("field", semanticField);
            semantic.put("query", query);
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("semantic", semantic);
            body.put("query", wrap);
        } else {
            // text_expansion (ELSER): { text_expansion: { <tokensField>: { model_id, model_text } } }
            if (modelId == null || modelId.trim().isEmpty()) {
                throw new IllegalStateException("text_expansion 쿼리에는 model-id 설정이 필요합니다");
            }
            if (tokensField == null || tokensField.trim().isEmpty()) tokensField = "ml.tokens";
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("model_id", modelId);
            inner.put("model_text", query);
            Map<String, Object> textExpansion = new LinkedHashMap<>();
            textExpansion.put(tokensField, inner);
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("text_expansion", textExpansion);
            body.put("query", wrap);
        }
    }

    /**
     * Phase 2 — 앱측 임베딩 + kNN.
     */
    private void buildSemanticClientQuery(Map<String, Object> body, String query, int topK, Map<String, Object> overrides) {
        String vectorField   = pickStr(overrides, "knnVectorField", ragConfig.getRagKnnVectorField());
        int numCandidates    = pickInt(overrides, "knnNumCandidates", ragConfig.getRagKnnNumCandidates());
        if (vectorField == null || vectorField.trim().isEmpty()) {
            throw new IllegalStateException("kNN 쿼리에는 vector-field 설정이 필요합니다");
        }
        if (numCandidates <= 0) numCandidates = Math.max(10, topK * 10);

        // 1) 앱에서 임베딩 생성 (오버라이드는 임베딩 설정에는 그대로 전달)
        float[] vector = embeddingService.embed(query, overrides);

        // 2) kNN 쿼리 본문 구성
        Map<String, Object> knn = new LinkedHashMap<>();
        knn.put("field", vectorField);
        List<Float> vec = new ArrayList<>(vector.length);
        for (float v : vector) vec.add(v);
        knn.put("query_vector", vec);
        knn.put("k", topK);
        knn.put("num_candidates", numCandidates);
        body.put("knn", knn);
    }

    private String extractContent(Map<String, Object> source, String preferredField) {
        if (source == null) return "";
        if (preferredField != null && source.get(preferredField) != null) {
            return String.valueOf(source.get(preferredField));
        }
        // 폴백: content/body/text 순서
        for (String f : new String[]{"content", "body", "text", "message"}) {
            if (source.get(f) != null) return String.valueOf(source.get(f));
        }
        // 최후: 전체 source 직렬화
        try {
            return objectMapper.writeValueAsString(source);
        } catch (Exception e) {
            return source.toString();
        }
    }

    private HttpURLConnection openConnection(String urlStr, boolean sslVerify) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (!sslVerify && conn instanceof HttpsURLConnection) {
            disableSslVerification((HttpsURLConnection) conn);
        }
        return conn;
    }

    private void disableSslVerification(HttpsURLConnection conn) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String s) {}
            public void checkServerTrusted(X509Certificate[] c, String s) {}
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        conn.setSSLSocketFactory(sc.getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> true);
    }

    private void applyAuth(HttpURLConnection conn, String authType, String user, String pass, String apiKey) {
        if (authType == null) authType = "none";
        switch (authType.toLowerCase()) {
            case "basic": {
                if (user != null && !user.isEmpty()) {
                    String creds = user + ":" + (pass != null ? pass : "");
                    String encoded = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
                    conn.setRequestProperty("Authorization", "Basic " + encoded);
                }
                break;
            }
            case "api-key":
            case "apikey": {
                if (apiKey != null && !apiKey.isEmpty()) {
                    conn.setRequestProperty("Authorization", "ApiKey " + apiKey);
                }
                break;
            }
            default:
                break; // none
        }
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String pickStr(Map<String, Object> overrides, String key, String fallback) {
        if (overrides == null) return fallback;
        Object v = overrides.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static int pickInt(Map<String, Object> overrides, String key, int fallback) {
        if (overrides == null || overrides.get(key) == null) return fallback;
        try { return Integer.parseInt(String.valueOf(overrides.get(key))); }
        catch (Exception e) { return fallback; }
    }

    private static double pickDouble(Map<String, Object> overrides, String key, double fallback) {
        if (overrides == null || overrides.get(key) == null) return fallback;
        try { return Double.parseDouble(String.valueOf(overrides.get(key))); }
        catch (Exception e) { return fallback; }
    }

    private static boolean pickBool(Map<String, Object> overrides, String key, boolean fallback) {
        if (overrides == null || overrides.get(key) == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(overrides.get(key)));
    }
}

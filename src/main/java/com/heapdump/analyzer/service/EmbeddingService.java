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
 * 텍스트 임베딩 생성 서비스 (semantic-client 모드 전용).
 *
 * Provider 분기:
 * - openai : POST {url} {model, input}            → data[0].embedding[]
 * - cohere : POST {url} {model, texts:[]}         → embeddings[0][]
 * - custom : OpenAI 호환 스펙 가정
 *
 * API Key는 HeapDumpAnalyzerService에 평문 보관, settings.json에는 ENC(...) AES 암호화.
 */
@Service
public class EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RagConfigService ragConfig;

    public EmbeddingService(RagConfigService ragConfig) {
        this.ragConfig = ragConfig;
    }

    /**
     * 현재 저장된 설정으로 텍스트 임베딩 생성.
     * @return float 벡터 (성공 시), 실패 시 RuntimeException
     */
    public float[] embed(String text) {
        return embed(text, null);
    }

    /**
     * @param overrides 테스트용 임시 설정 오버라이드. null이면 저장된 설정 사용.
     */
    public float[] embed(String text, Map<String, Object> overrides) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Embedding 입력 텍스트가 비어 있습니다");
        }

        String provider = pickStr(overrides, "provider", ragConfig.getRagEmbeddingProvider());
        String url      = pickStr(overrides, "apiUrl",   ragConfig.getRagEmbeddingApiUrl());
        String apiKey   = pickStr(overrides, "apiKey",   ragConfig.getRagEmbeddingApiKey());
        String model    = pickStr(overrides, "model",    ragConfig.getRagEmbeddingModel());
        int timeout     = pickInt(overrides, "timeoutSeconds", ragConfig.getRagEmbeddingTimeoutSeconds());

        if (url == null || url.trim().isEmpty()) {
            throw new IllegalStateException("Embedding API URL이 설정되지 않았습니다");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Embedding API Key가 설정되지 않았습니다");
        }
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalStateException("Embedding 모델이 설정되지 않았습니다");
        }

        try {
            String body = buildRequestBody(provider, model, text);
            HttpURLConnection conn = openConnection(url, true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeout * 1000);
            conn.setReadTimeout(timeout * 1000);
            conn.setRequestProperty("Content-Type", "application/json");
            applyAuth(conn, provider, apiKey);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String response = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());

            if (code < 200 || code >= 300) {
                logger.warn("[Embedding] HTTP {} — body={}", code, truncate(response, 300));
                throw new RuntimeException("Embedding API 호출 실패 (HTTP " + code + "): " + truncate(response, 200));
            }

            return parseEmbedding(provider, response);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Embedding] 호출 예외 — {}: {}", e.getClass().getSimpleName(), e.getMessage());
            throw new RuntimeException("[" + e.getClass().getSimpleName() + "] " + e.getMessage(), e);
        }
    }

    /**
     * 임베딩 API 연결 테스트.
     * @return success/dimension/error 등을 포함한 결과 맵
     */
    public Map<String, Object> testConnection(Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            float[] vec = embed("test", overrides);
            result.put("success", true);
            result.put("dimension", vec != null ? vec.length : 0);
            result.put("provider", pickStr(overrides, "provider", ragConfig.getRagEmbeddingProvider()));
            result.put("model", pickStr(overrides, "model", ragConfig.getRagEmbeddingModel()));
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return result;
        }
    }

    // ── 내부 ──────────────────────────────────────────────

    private String buildRequestBody(String provider, String model, String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        if ("cohere".equalsIgnoreCase(provider)) {
            body.put("texts", Collections.singletonList(text));
            body.put("input_type", "search_query");
        } else {
            // openai / custom
            body.put("input", text);
        }
        return objectMapper.writeValueAsString(body);
    }

    @SuppressWarnings("unchecked")
    private float[] parseEmbedding(String provider, String responseBody) throws Exception {
        Map<String, Object> json = objectMapper.readValue(responseBody, Map.class);
        List<? extends Number> vec = null;

        if ("cohere".equalsIgnoreCase(provider)) {
            // {embeddings: [[...]]}
            Object emb = json.get("embeddings");
            if (emb instanceof List && !((List<?>) emb).isEmpty()) {
                Object first = ((List<?>) emb).get(0);
                if (first instanceof List) vec = (List<Number>) first;
            }
        } else {
            // openai / custom : {data: [{embedding: [...]}]}
            Object data = json.get("data");
            if (data instanceof List && !((List<?>) data).isEmpty()) {
                Object first = ((List<?>) data).get(0);
                if (first instanceof Map) {
                    Object emb = ((Map<String, Object>) first).get("embedding");
                    if (emb instanceof List) vec = (List<Number>) emb;
                }
            }
        }

        if (vec == null || vec.isEmpty()) {
            throw new RuntimeException("Embedding 응답에서 벡터를 찾을 수 없습니다: " + truncate(responseBody, 200));
        }

        float[] out = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) out[i] = vec.get(i).floatValue();
        return out;
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

    private void applyAuth(HttpURLConnection conn, String provider, String apiKey) {
        if ("cohere".equalsIgnoreCase(provider)) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        } else {
            // openai / custom
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String pickStr(Map<String, Object> overrides, String key, String fallback) {
        if (overrides == null) return fallback;
        Object v = overrides.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v);
        return s.isEmpty() ? fallback : s;
    }

    private static int pickInt(Map<String, Object> overrides, String key, int fallback) {
        if (overrides == null || overrides.get(key) == null) return fallback;
        try { return Integer.parseInt(String.valueOf(overrides.get(key))); }
        catch (Exception e) { return fallback; }
    }
}

package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chroma 벡터 DB 검색 (rag.search.mode=chroma).
 *
 * <p>ES 경로({@link RagService#search})와 응답 계약은 같지만 내부는 전혀 다르다 —
 * URL 형태, 인증 헤더, 응답 구조, 스코어 방향이 모두 달라 {@code buildQueryBody}
 * switch 확장으로는 처리할 수 없다.
 *
 * <p><b>Chroma 서버는 임베딩을 하지 않는다.</b> REST {@code /query} 는
 * {@code query_embeddings} 만 받고 서버 프로세스에 모델이 없다
 * ({@code embedding_function} 이 항상 null). 질의 임베딩은
 * {@link EmbeddingService}(provider=local-onnx)가 사이드카에서 받아온다.
 *
 * <p><b>⚠ 스코어 방향</b> — ES 는 score 가 클수록, Chroma 는 distance 가 작을수록
 * 좋다. {@link #toScore}가 space 별로 "클수록 좋음"으로 변환해 기존 minScore
 * 규약을 유지한다. space 를 잘못 잡으면 <b>최악 문서가 최상위로 올라온다</b>.
 *
 * <p><b>⚠ minScore 임계값은 임베딩 모델에 종속된다.</b> BM25 는 5~30, 패러프레이즈
 * 모델은 0.0~0.6, e5 계열은 0.80~0.94 대에 값이 몰린다. 모드나 모델을 바꾸면
 * 같은 숫자의 의미가 달라져 <b>조용히 무필터가 되거나 전부 걸린다</b>.
 * 현재 사이드카(e5-small) 기준 권장 하한은 0.86 이다(도메인 밖 질의 0.81~0.85 실측).
 */
@Service
public class ChromaSearchService {

    private static final Logger logger = LoggerFactory.getLogger(ChromaSearchService.class);

    private final RagConfigService ragConfig;
    private final EmbeddingService embeddingService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 이름→UUID 해석 결과. Chroma 는 GET .../collections/{name} 은 이름으로 받지만
     * add/upsert/query 는 UUID 를 요구한다.
     * ⚠ 캐시 키에 url+tenant+database+collection 을 모두 넣는다 — 컬렉션만 키로 쓰면
     *   UI 에서 대상을 바꿔도 재기동 전까지 옛 UUID 를 계속 조회한다.
     */
    private final Map<String, String> idCache = new ConcurrentHashMap<>();
    /** 컬렉션이 실제로 쓰는 hnsw:space. 설정값과 다르면 이쪽을 채택한다. */
    private final Map<String, String> spaceCache = new ConcurrentHashMap<>();

    public ChromaSearchService(RagConfigService ragConfig, EmbeddingService embeddingService) {
        this.ragConfig = ragConfig;
        this.embeddingService = embeddingService;
    }

    // ── 검색 ────────────────────────────────────────────────────────

    public Map<String, Object> search(String query, Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();
        String url        = RagService.pickStr(overrides, "chromaUrl", ragConfig.getRagChromaUrl());
        String apiPath    = RagService.pickStr(overrides, "chromaApiPath", ragConfig.getRagChromaApiPath());
        String tenant     = RagService.pickStr(overrides, "chromaTenant", ragConfig.getRagChromaTenant());
        String database   = RagService.pickStr(overrides, "chromaDatabase", ragConfig.getRagChromaDatabase());
        String collection = RagService.pickStr(overrides, "chromaCollection", ragConfig.getRagChromaCollection());
        String authType   = RagService.pickStr(overrides, "chromaAuthType", ragConfig.getRagChromaAuthType());
        String token      = RagService.pickStr(overrides, "chromaToken", ragConfig.getRagChromaToken());
        String space      = RagService.pickStr(overrides, "chromaSpace", ragConfig.getRagChromaSpace());
        boolean sslVerify = RagService.pickBool(overrides, "chromaSslVerify", ragConfig.isRagChromaSslVerify());
        int timeoutSec    = RagService.pickInt(overrides, "chromaTimeoutSeconds", ragConfig.getRagChromaTimeoutSeconds());
        int topK          = RagService.pickInt(overrides, "topK", ragConfig.getRagTopK());
        double minScore   = RagService.pickDouble(overrides, "minScore", ragConfig.getRagMinScore());

        if (isBlank(url))        return fail(result, "Chroma URL이 설정되지 않았습니다.");
        if (isBlank(collection)) return fail(result, "Chroma 컬렉션 이름이 설정되지 않았습니다.");
        if (!ragConfig.isRagChromaTokenHealthy()) {
            return fail(result, "Chroma 토큰이 손상되어 사용할 수 없습니다. /settings/rag 에서 다시 저장하세요.");
        }

        try {
            String base = base(url, apiPath, tenant, database);
            String key = base + "|" + collection;
            String id = resolveCollectionId(base, collection, key, authType, token, sslVerify, timeoutSec);

            // 컬렉션이 실제로 쓰는 space 가 설정과 다르면 실제값이 이긴다.
            String actual = spaceCache.get(key);
            if (actual != null && !actual.equalsIgnoreCase(space)) {
                logger.warn("[Chroma] space 불일치 — 설정={} 컬렉션실제={}. 실제값을 사용합니다 "
                        + "(설정을 맞추지 않으면 스코어 방향이 뒤집힐 수 있습니다)", space, actual);
                space = actual;
            }

            float[] vec = embeddingService.embed(query, overrides);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query_embeddings", new float[][]{vec});
            body.put("n_results", Math.max(1, topK));
            body.put("include", List.of("documents", "distances", "metadatas"));
            // 가상 사례(synthetic=true)는 기본 제외 — 색인은 하되 답변 근거로는 쓰지 않는다.
            body.put("where", Map.of("synthetic", Map.of("$ne", true)));

            String raw = post(base + "/collections/" + enc(id) + "/query",
                    mapper.writeValueAsString(body), authType, token, sslVerify, timeoutSec);

            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(raw, Map.class);
            List<Map<String, Object>> hits = normalizeHits(json, space, minScore);

            result.put("success", true);
            result.put("total", hits.size());
            result.put("hits", hits);
            return result;
        } catch (Exception e) {
            logger.warn("[Chroma] 검색 실패: {}", e.toString());
            return fail(result, "Chroma 검색 실패: " + e.getMessage());
        }
    }

    // ── 응답 정규화 (순수 함수 — 테스트 대상) ───────────────────────

    /**
     * Chroma query 응답 → ES 경로와 동일한 {@code {id,score,content}} 목록.
     *
     * <p>⚠ 응답은 질의당 중첩 배열이고, {@code include} 에서 빠진 필드는
     * 빈 배열이 아니라 <b>null</b> 로 온다. 언랩과 null 처리를 모두 견뎌야 한다.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> normalizeHits(Map<String, Object> json, String space, double minScore) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> ids = unwrap(json.get("ids"));
        if (ids == null || ids.isEmpty()) return out;
        List<Object> dists = unwrap(json.get("distances"));
        List<Object> docs = unwrap(json.get("documents"));
        List<Object> metas = unwrap(json.get("metadatas"));

        for (int i = 0; i < ids.size(); i++) {
            double dist = at(dists, i) instanceof Number ? ((Number) at(dists, i)).doubleValue() : 0.0;
            double score = toScore(dist, space);
            if (minScore > 0 && score < minScore) continue;

            Object doc = at(docs, i);
            String content = doc instanceof String ? (String) doc : null;
            Object meta = at(metas, i);
            if (content == null || content.isEmpty()) {
                // documents 를 include 하지 않았거나 비었으면 메타로 폴백 — 절대 예외를 던지지 않는다.
                content = meta == null ? "" : String.valueOf(meta);
            }

            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", String.valueOf(ids.get(i)));
            hit.put("score", score);
            hit.put("content", content);
            hit.put("distance", dist);
            if (meta instanceof Map) hit.put("metadata", meta);
            out.add(hit);
        }
        return out;
    }

    /**
     * distance → "클수록 좋음" score. ES 규약과 맞추기 위한 변환이다.
     * 임베딩이 L2 정규화돼 있으므로 cosine distance 는 [0,2] 범위다.
     */
    static double toScore(double distance, String space) {
        String sp = space == null ? "cosine" : space.trim().toLowerCase();
        switch (sp) {
            case "l2":  return 1.0 / (1.0 + distance);
            case "ip":  return -distance;
            case "cosine":
            default:    return 1.0 - distance;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> unwrap(Object v) {
        if (!(v instanceof List)) return null;                 // null 로 오는 경우 포함
        List<Object> l = (List<Object>) v;
        if (l.isEmpty()) return null;
        Object first = l.get(0);
        return first instanceof List ? (List<Object>) first : l;
    }

    private static Object at(List<Object> l, int i) {
        return (l == null || i >= l.size()) ? null : l.get(i);
    }

    // ── 연결 테스트 ──────────────────────────────────────────────────

    public Map<String, Object> testConnection(Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>();
        String url        = RagService.pickStr(overrides, "chromaUrl", ragConfig.getRagChromaUrl());
        String apiPath    = RagService.pickStr(overrides, "chromaApiPath", ragConfig.getRagChromaApiPath());
        String tenant     = RagService.pickStr(overrides, "chromaTenant", ragConfig.getRagChromaTenant());
        String database   = RagService.pickStr(overrides, "chromaDatabase", ragConfig.getRagChromaDatabase());
        String collection = RagService.pickStr(overrides, "chromaCollection", ragConfig.getRagChromaCollection());
        String authType   = RagService.pickStr(overrides, "chromaAuthType", ragConfig.getRagChromaAuthType());
        String token      = RagService.pickStr(overrides, "chromaToken", ragConfig.getRagChromaToken());
        boolean sslVerify = RagService.pickBool(overrides, "chromaSslVerify", ragConfig.isRagChromaSslVerify());
        int timeoutSec    = RagService.pickInt(overrides, "chromaTimeoutSeconds", ragConfig.getRagChromaTimeoutSeconds());

        if (isBlank(url)) return fail(result, "Chroma URL이 설정되지 않았습니다.");
        try {
            String root = RagService.stripTrailingSlash(url) + normalizePath(apiPath);
            // ⚠ /version 은 패키지 버전이 아니라 API 버전("1.0.0")을 돌려준다.
            String version = get(root + "/version", authType, token, sslVerify, timeoutSec).replace("\"", "");
            result.put("success", true);
            result.put("apiVersion", version);

            if (!isBlank(collection)) {
                String base = base(url, apiPath, tenant, database);
                String key = base + "|" + collection;
                idCache.remove(key);       // 테스트는 항상 실측한다
                spaceCache.remove(key);
                String id = resolveCollectionId(base, collection, key, authType, token, sslVerify, timeoutSec);
                result.put("collectionId", id);
                result.put("collectionName", collection);
                result.put("space", spaceCache.get(key));
                String cnt = get(base + "/collections/" + enc(id) + "/count",
                        authType, token, sslVerify, timeoutSec);
                result.put("count", cnt.trim());
                if ("0".equals(cnt.trim())) {
                    result.put("warning", "컬렉션이 비어 있습니다. 색인을 먼저 실행하세요 "
                            + "(/opt/chroma/app/run-index.sh --sources all).");
                }
            }
            return result;
        } catch (Exception e) {
            logger.warn("[Chroma] 연결 테스트 실패: {}", e.toString());
            return fail(result, "Chroma 연결 실패: " + e.getMessage());
        }
    }

    // ── 내부 ────────────────────────────────────────────────────────

    private String resolveCollectionId(String base, String collection, String cacheKey,
                                       String authType, String token, boolean sslVerify, int timeoutSec)
            throws Exception {
        String cached = idCache.get(cacheKey);
        if (cached != null) return cached;

        String raw = get(base + "/collections/" + enc(collection), authType, token, sslVerify, timeoutSec);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.readValue(raw, Map.class);
        Object id = m.get("id");
        if (id == null) throw new IllegalStateException("컬렉션 '" + collection + "' 을 찾을 수 없습니다");

        Object cfg = m.get("configuration_json");
        if (cfg instanceof Map) {
            Object hnsw = ((Map<?, ?>) cfg).get("hnsw");
            if (hnsw instanceof Map) {
                Object sp = ((Map<?, ?>) hnsw).get("space");
                if (sp != null) spaceCache.put(cacheKey, String.valueOf(sp));
            }
        }
        idCache.put(cacheKey, String.valueOf(id));
        return String.valueOf(id);
    }

    private static String base(String url, String apiPath, String tenant, String database) {
        return RagService.stripTrailingSlash(url) + normalizePath(apiPath)
                + "/tenants/" + enc(blankTo(tenant, "default_tenant"))
                + "/databases/" + enc(blankTo(database, "default_database"));
    }

    private static String normalizePath(String p) {
        String s = isBlank(p) ? "/api/v2" : p.trim();
        if (!s.startsWith("/")) s = "/" + s;
        return RagService.stripTrailingSlash(s);
    }

    private String get(String url, String authType, String token, boolean sslVerify, int timeoutSec)
            throws Exception {
        return exchange(url, "GET", null, authType, token, sslVerify, timeoutSec);
    }

    private String post(String url, String body, String authType, String token,
                        boolean sslVerify, int timeoutSec) throws Exception {
        return exchange(url, "POST", body, authType, token, sslVerify, timeoutSec);
    }

    private String exchange(String url, String method, String body, String authType,
                            String token, boolean sslVerify, int timeoutSec) throws Exception {
        HttpURLConnection conn = RagService.openConnection(url, sslVerify);
        conn.setRequestMethod(method);
        int ms = Math.max(1, timeoutSec) * 1000;
        conn.setConnectTimeout(ms);
        conn.setReadTimeout(ms);
        conn.setRequestProperty("Accept", "application/json");
        applyAuth(conn, authType, token);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String err = RagService.readStream(conn.getErrorStream());
            throw new IllegalStateException("HTTP " + status + (err.isEmpty() ? "" : " — " + trunc(err)));
        }
        return RagService.readStream(conn.getInputStream());
    }

    private static void applyAuth(HttpURLConnection conn, String authType, String token) {
        if (token == null || token.isEmpty()) return;
        String t = authType == null ? "none" : authType.trim().toLowerCase();
        if ("token".equals(t)) {
            conn.setRequestProperty("x-chroma-token", token);
        } else if ("basic".equals(t)) {
            conn.setRequestProperty("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String blankTo(String s, String dflt) { return isBlank(s) ? dflt : s.trim(); }

    private static String trunc(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static Map<String, Object> fail(Map<String, Object> result, String msg) {
        result.put("success", false);
        result.put("error", msg);
        return result;
    }
}

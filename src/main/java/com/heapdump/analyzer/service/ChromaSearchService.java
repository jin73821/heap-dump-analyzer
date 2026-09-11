package com.heapdump.analyzer.service;

import tools.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
    /** 컬렉션의 벡터 차원(실측). 사이드카·설정 차원과의 정합성 경고에 쓴다. */
    private final Map<String, Integer> dimensionCache = new ConcurrentHashMap<>();

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
            body.put("where", searchFilter());

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

    /**
     * 검색에서 제외할 소스. <b>색인은 하되 답변 근거로는 쓰지 않는다</b> — 데이터는 남기고
     * 검색에서만 뺀다(되돌리려면 이 목록에서 지우면 되고 재색인이 필요 없다).
     *
     * <ul>
     *   <li>{@code synthetic=true} — 가상 사례. RAG 가 허구를 근거로 답하면 안 된다.</li>
     *   <li>{@code source_type=ai_chat} — 과거 대화 로그. 2026-08-29 평가셋 42건 실측에서
     *       <b>검색 슬롯의 39.5% 를 차지하면서 정답을 밀어냈다</b>: 제외 시 Recall@10 0.857→0.952,
     *       Recall@5 0.786→0.905. 게다가 과거 대화에는 <b>다른 분석 건의 구체적 수치</b>가 그대로 들어 있어
     *       모델이 현재 덤프 수치와 섞는 사고가 실제로 발생했다(함정 42의 연장).</li>
     * </ul>
     */
    static final List<String> EXCLUDED_SOURCE_TYPES = List.of("ai_chat");

    /** Chroma {@code where} 절. 조건이 2개 이상이면 {@code $and} 로 묶어야 한다(단일 맵은 AND 가 안 된다). */
    static Map<String, Object> searchFilter() {
        List<Map<String, Object>> conds = new ArrayList<>();
        conds.add(Map.of("synthetic", Map.of("$ne", true)));
        for (String t : EXCLUDED_SOURCE_TYPES) {
            conds.add(Map.of("source_type", Map.of("$ne", t)));
        }
        return conds.size() == 1 ? conds.get(0) : Map.of("$and", conds);
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
                dimensionCache.remove(key);
                String id = resolveCollectionId(base, collection, key, authType, token, sslVerify, timeoutSec);
                result.put("collectionId", id);
                result.put("collectionName", collection);
                result.put("space", spaceCache.get(key));
                result.put("dimension", dimensionCache.get(key));
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

    // ── 연동 상태 (설정 화면 상태 패널) ───────────────────────────────

    /** 상태 프로브 타임아웃 상한(초). 서비스가 죽어 있어도 페이지 로드가 설정값(기본 15초)만큼 멈추지 않게. */
    static final int STATUS_PROBE_MAX_SECONDS = 5;

    /**
     * 저장된 설정 기준으로 Chroma 서버·컬렉션·임베딩 사이드카를 한 번에 점검한다.
     * {@code GET /api/settings/rag/chroma/status} 가 그대로 반환한다.
     *
     * <p>규약: <b>항상 최상위 {@code success:true}</b> 이고 컴포넌트별로 {@code success:false} + {@code error}.
     * 여기서 예외가 새면 상태 패널 하나 때문에 설정 화면 전체가 에러 토스트를 띄운다.
     *
     * <p>⚠ 토큰·authType 값은 싣지 않는다 — 이 응답은 USER 도 읽는다(페이지 자체가 USER 열람 가능).
     * ⚠ 폼의 미저장 값은 반영하지 않는다 — 그건 "연결 테스트" 버튼(overrides)의 몫이다.
     */
    public Map<String, Object> integrationStatus() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("checkedAt", java.time.LocalDateTime.now().withNano(0).toString());
        try {
            String mode = ragConfig.getRagSearchMode();
            boolean enabled = ragConfig.isRagEnabled();
            boolean chromaMode = "chroma".equalsIgnoreCase(mode);
            res.put("searchMode", mode);
            res.put("ragEnabled", enabled);
            res.put("chromaActive", enabled && chromaMode);

            // ── Chroma 서버 + 컬렉션
            int probe = Math.min(Math.max(1, ragConfig.getRagChromaTimeoutSeconds()), STATUS_PROBE_MAX_SECONDS);
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("chromaTimeoutSeconds", probe);
            Map<String, Object> t = testConnection(overrides);
            Map<String, Object> chroma = new LinkedHashMap<>();
            boolean chromaOk = Boolean.TRUE.equals(t.get("success"));
            chroma.put("success", chromaOk);
            chroma.put("url", ragConfig.getRagChromaUrl());
            chroma.put("apiVersion", t.get("apiVersion"));
            chroma.put("collectionName", t.get("collectionName"));
            // 컬렉션 조회에 실패하면 collectionName 이 비므로 화면이 "무엇을 못 찾았는지" 말할 수 있게 설정값을 따로 싣는다.
            chroma.put("configuredCollection", ragConfig.getRagChromaCollection());
            chroma.put("collectionId", t.get("collectionId"));
            Long count = parseLong(t.get("count"));
            chroma.put("count", count);
            chroma.put("space", t.get("space"));
            chroma.put("configuredSpace", ragConfig.getRagChromaSpace());
            Integer collectionDim = t.get("dimension") instanceof Number ? ((Number) t.get("dimension")).intValue() : null;
            chroma.put("dimension", collectionDim);
            chroma.put("error", chromaOk ? null : t.get("error"));
            res.put("chroma", chroma);

            // ── 임베딩 (사이드카는 local-onnx 일 때만 두드린다)
            String provider = ragConfig.getRagEmbeddingProvider();
            String apiUrl = ragConfig.getRagEmbeddingApiUrl();
            boolean localOnnx = "local-onnx".equalsIgnoreCase(provider);
            Map<String, Object> emb = new LinkedHashMap<>();
            emb.put("provider", provider);
            emb.put("apiUrl", apiUrl);
            emb.put("model", ragConfig.getRagEmbeddingModel());
            emb.put("configuredDimension", ragConfig.getRagEmbeddingDimension());
            emb.put("isLocalOnnx", localOnnx);
            Map<String, Object> sidecar = null;
            if (localOnnx) {
                int embProbe = Math.min(Math.max(1, ragConfig.getRagEmbeddingTimeoutSeconds()), STATUS_PROBE_MAX_SECONDS);
                sidecar = embeddingService.sidecarHealth(apiUrl, embProbe);
            }
            emb.put("sidecar", sidecar);
            res.put("embedding", emb);

            boolean sidecarOk = sidecar != null && Boolean.TRUE.equals(sidecar.get("success"));
            Integer sidecarDim = sidecarOk && sidecar.get("dimension") instanceof Number
                    ? ((Number) sidecar.get("dimension")).intValue() : null;
            IntegrationFacts facts = new IntegrationFacts(mode, enabled, chromaOk, provider,
                    ragConfig.getRagEmbeddingDimension(), localOnnx, sidecarOk, sidecarDim,
                    collectionDim, ragConfig.getRagChromaSpace(), (String) t.get("space"), count);
            res.put("warnings", integrationWarnings(facts));
        } catch (Exception e) {
            // 어떤 경로로 새더라도 상태 패널은 "점검 실패"만 보여야 한다.
            logger.warn("[Chroma] 연동 상태 집계 실패: {}", e.toString());
            res.put("error", "상태 집계 실패: " + e.getMessage());
            res.putIfAbsent("warnings", List.of());
        }
        return res;
    }

    /** 정합성 판정에 필요한 사실만 모은 값 객체 — HTTP 없이 {@link #integrationWarnings} 를 테스트하기 위한 경계. */
    record IntegrationFacts(String searchMode, boolean ragEnabled, boolean chromaReachable,
                            String embeddingProvider, int configuredDimension,
                            boolean sidecarProbed, boolean sidecarReachable, Integer sidecarDimension,
                            Integer collectionDimension, String configuredSpace, String actualSpace,
                            Long count) {}

    /**
     * 연동 정합성 경고. 순수 함수.
     *
     * <p>chroma 모드가 아니면 대부분 {@code warn} 으로 강등한다 — 지금은 쓰지 않는 경로의 문제를
     * {@code error} 로 띄우면 keyword 모드 운영자가 매번 빨간 배지를 본다. 단 차원 불일치는
     * 모드와 무관하게 {@code error} 다: 그 상태로 chroma 로 전환하는 순간 반드시 실패하기 때문이다.
     */
    static List<Map<String, Object>> integrationWarnings(IntegrationFacts f) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean chromaMode = "chroma".equalsIgnoreCase(f.searchMode());
        String hard = chromaMode ? "error" : "warn";

        if (!chromaMode) {
            out.add(warning("MODE_NOT_CHROMA", "info",
                    "현재 미사용 — Search Mode 가 " + (f.searchMode() == null ? "(미설정)" : f.searchMode())
                    + " 입니다. chroma 로 바꿔 저장하면 아래 설정이 검색에 쓰입니다."));
        }
        if (!f.ragEnabled()) {
            out.add(warning("RAG_DISABLED", "info", "RAG 가 비활성화되어 있습니다 — 어떤 모드든 검색이 주입되지 않습니다."));
        }
        if (!f.chromaReachable()) {
            out.add(warning("CHROMA_UNREACHABLE", hard, "Chroma 서버에 연결할 수 없거나 컬렉션을 찾지 못했습니다."));
        }
        boolean localOnnx = "local-onnx".equalsIgnoreCase(f.embeddingProvider());
        if (!localOnnx) {
            out.add(warning("PROVIDER_NOT_LOCAL_ONNX", hard,
                    "질의 임베딩 불가 — Embedding provider 가 " + f.embeddingProvider()
                    + " 입니다. Chroma 컬렉션은 로컬 사이드카(local-onnx, e5-small 384차원)로 색인됐으므로 "
                    + "다른 provider 의 벡터와는 차원·의미가 맞지 않습니다."));
        }
        if (f.sidecarProbed() && !f.sidecarReachable()) {
            out.add(warning("SIDECAR_UNREACHABLE", hard,
                    "임베딩 사이드카(:8001)에 연결할 수 없습니다 — systemctl status chroma-embed 를 확인하세요."));
        }
        if (f.sidecarDimension() != null && f.configuredDimension() != f.sidecarDimension()) {
            out.add(warning("CONFIG_DIM_MISMATCH", "error",
                    "Embedding Dimension 설정 " + f.configuredDimension() + " ≠ 사이드카 실제 "
                    + f.sidecarDimension() + " — 임베딩 호출이 '차원 불일치'로 실패합니다."));
        }
        if (f.collectionDimension() != null && f.sidecarDimension() != null
                && !f.collectionDimension().equals(f.sidecarDimension())) {
            out.add(warning("COLLECTION_DIM_MISMATCH", "error",
                    "컬렉션 차원 " + f.collectionDimension() + " ≠ 사이드카 " + f.sidecarDimension()
                    + " — 색인에 쓴 모델과 질의 모델이 다릅니다. 재색인(run-index.sh --reset)이 필요합니다."));
        }
        if (f.sidecarDimension() == null && f.collectionDimension() != null
                && f.configuredDimension() != f.collectionDimension()) {
            out.add(warning("COLLECTION_DIM_VS_CONFIG", "warn",
                    "컬렉션 차원 " + f.collectionDimension() + " ≠ Embedding Dimension 설정 "
                    + f.configuredDimension() + " — chroma 모드로 전환하려면 설정을 "
                    + f.collectionDimension() + " 로 맞추세요."));
        }
        if (f.configuredSpace() != null && f.actualSpace() != null
                && !f.configuredSpace().equalsIgnoreCase(f.actualSpace())) {
            out.add(warning("SPACE_MISMATCH", "warn",
                    "Distance Space 설정 " + f.configuredSpace() + " ≠ 컬렉션 실제 " + f.actualSpace()
                    + " — 검색은 실제값을 채택하지만 설정을 맞춰 두세요(스코어 방향 판단 기준)."));
        }
        if (f.count() != null && f.count() == 0L) {
            out.add(warning("COLLECTION_EMPTY", "warn",
                    "컬렉션이 비어 있습니다 — /opt/chroma/app/run-index.sh --sources all 로 색인하세요."));
        }
        return out;
    }

    private static Map<String, Object> warning(String code, String level, String message) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", code);
        w.put("level", level);
        w.put("message", message);
        return w;
    }

    // ── 색인 현황 (source_type 별 집계) ────────────────────────────

    /** 한 번에 끌어올 메타데이터 상한. 실측 834청크 기준 217KB / 50~140ms 라 1회 호출로 충분하다. */
    static final int STATS_MAX_ITEMS = 20_000;

    /**
     * 컬렉션 전체 메타를 1회 조회해 {@code source_type} 별로 집계한다.
     * {@code indexer.py --stats} 와 같은 값을 화면에서 보기 위한 것이다.
     *
     * <p>실패해도 예외를 던지지 않는다 — 상태 패널과 같은 계약으로 본문에 {@code error} 를 담는다.
     */
    public Map<String, Object> sourceTypeStats() {
        Map<String, Object> res = new LinkedHashMap<>();
        try {
            String url = ragConfig.getRagChromaUrl();
            String collection = ragConfig.getRagChromaCollection();
            if (url == null || url.isBlank() || collection == null || collection.isBlank()) {
                res.put("success", false);
                res.put("error", "Chroma URL 또는 컬렉션이 설정되지 않았습니다");
                return res;
            }
            int timeoutSec = Math.min(Math.max(1, ragConfig.getRagChromaTimeoutSeconds()), STATUS_PROBE_MAX_SECONDS);
            String authType = ragConfig.getRagChromaAuthType();
            String token = ragConfig.getRagChromaToken();
            boolean sslVerify = ragConfig.isRagChromaSslVerify();
            String base = base(url, ragConfig.getRagChromaApiPath(),
                    ragConfig.getRagChromaTenant(), ragConfig.getRagChromaDatabase());
            String key = base + "|" + collection;
            String id = resolveCollectionId(base, collection, key, authType, token, sslVerify, timeoutSec);

            // ⚠ count 를 먼저 읽는다 — 상한을 넘으면 집계가 조용히 과소보고되므로 partial 로 알린다.
            long total = 0L;
            try {
                total = Long.parseLong(get(base + "/collections/" + enc(id) + "/count",
                        authType, token, sslVerify, timeoutSec).trim());
            } catch (Exception ignore) { /* count 실패는 치명적이지 않다 */ }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("include", List.of("metadatas"));
            body.put("limit", STATS_MAX_ITEMS);
            String raw = post(base + "/collections/" + enc(id) + "/get",
                    mapper.writeValueAsString(body), authType, token, sslVerify, timeoutSec);
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(raw, Map.class);

            Map<String, Object> agg = aggregateSourceTypes(json);
            res.putAll(agg);
            res.put("success", true);
            res.put("collection", collection);
            res.put("totalChunks", total > 0 ? total : agg.get("scanned"));
            res.put("partial", total > STATS_MAX_ITEMS);
        } catch (Exception e) {
            logger.warn("[Chroma] 색인 현황 집계 실패: {}", e.toString());
            res.put("success", false);
            res.put("error", "색인 현황 조회 실패: " + e.getMessage());
        }
        return res;
    }

    /**
     * {@code /get} 응답 → source_type 별 집계. <b>순수 함수</b>(테스트 경계).
     *
     * <p>{@code docs} 는 청크 id 의 {@code #n} 접미사를 떼고 센 <b>문서 수</b>다 — 청크 수만
     * 보여주면 "834건" 이 문서 수로 오해된다(실제 문서는 365건).
     */
    static Map<String, Object> aggregateSourceTypes(Map<String, Object> getResponse) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<?> ids = getResponse.get("ids") instanceof List ? (List<?>) getResponse.get("ids") : List.of();
        List<?> metas = getResponse.get("metadatas") instanceof List ? (List<?>) getResponse.get("metadatas") : List.of();

        Map<String, Integer> chunks = new LinkedHashMap<>();
        Map<String, Integer> synthetic = new LinkedHashMap<>();
        Map<String, Set<String>> docIds = new LinkedHashMap<>();

        for (int i = 0; i < metas.size(); i++) {
            Object mo = metas.get(i);
            if (!(mo instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) mo;
            String st = m.get("source_type") == null ? "(미상)" : String.valueOf(m.get("source_type"));
            chunks.merge(st, 1, Integer::sum);
            if (Boolean.TRUE.equals(m.get("synthetic"))) synthetic.merge(st, 1, Integer::sum);

            String rawId = i < ids.size() && ids.get(i) != null ? String.valueOf(ids.get(i)) : null;
            String origin = rawId == null ? String.valueOf(m.get("origin_id"))
                    : (rawId.contains("#") ? rawId.substring(0, rawId.lastIndexOf('#')) : rawId);
            docIds.computeIfAbsent(st, k -> new LinkedHashSet<>()).add(origin);
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        chunks.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sourceType", e.getKey());
                    row.put("chunks", e.getValue());
                    row.put("docs", docIds.getOrDefault(e.getKey(), Set.of()).size());
                    row.put("syntheticChunks", synthetic.getOrDefault(e.getKey(), 0));
                    row.put("excluded", EXCLUDED_SOURCE_TYPES.contains(e.getKey()));
                    sources.add(row);
                });
        out.put("sources", sources);
        out.put("scanned", metas.size());
        out.put("syntheticTotal", synthetic.values().stream().mapToInt(Integer::intValue).sum());
        return out;
    }

    private static Long parseLong(Object v) {
        if (v == null) return null;
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
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

        String sp = readSpace(m);
        if (sp != null) spaceCache.put(cacheKey, sp);
        Integer dim = readDimension(m);
        if (dim != null) dimensionCache.put(cacheKey, dim);
        idCache.put(cacheKey, String.valueOf(id));
        return String.valueOf(id);
    }

    /** {@code configuration_json.hnsw.space} — 없으면 null (컬렉션 생성 설정에 따라 비어 있을 수 있다). */
    static String readSpace(Map<String, Object> collectionJson) {
        if (collectionJson == null) return null;
        Object cfg = collectionJson.get("configuration_json");
        if (!(cfg instanceof Map)) return null;
        Object hnsw = ((Map<?, ?>) cfg).get("hnsw");
        if (!(hnsw instanceof Map)) return null;
        Object sp = ((Map<?, ?>) hnsw).get("space");
        return sp == null ? null : String.valueOf(sp);
    }

    /**
     * 최상위 {@code dimension} — 벡터가 하나도 없는 새 컬렉션은 null 로 온다.
     * 숫자가 아니면(문자열 등) null — 억지로 파싱하지 않는다.
     */
    static Integer readDimension(Map<String, Object> collectionJson) {
        if (collectionJson == null) return null;
        Object d = collectionJson.get("dimension");
        return d instanceof Number ? ((Number) d).intValue() : null;
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

# RAG Phase 2 구현 계획 (Elasticsearch Semantic Search)

## 배경

Phase 1(2026-04-26 완료)에서 Keyword(BM25) 모드로 RAG 연동의 골격을 구축했습니다. Settings UI / API / 영속화 / LLM 컨텍스트 주입 흐름은 모두 완성되어 있고, 사내 운영팀에 ES 매핑/inference endpoint를 확인한 뒤 Semantic 모드만 활성화하면 됩니다.

Phase 1 변경 내역은 `CHANGELOG.md` 의 `[2026-04-26] RAG (Elasticsearch) 연동 — Phase 1` 섹션을 참고하세요.

---

## Phase 2 진입 전 사내 운영팀 확인 사항

배포 환경 ES 운영팀에 아래 3개 항목을 질의하면 어느 모드로 진행할지 결정 가능합니다.

1. **인덱스 매핑에 `dense_vector` 또는 `sparse_vector` 필드가 있는가?**
   - 있으면 (a) server-side 또는 (b) client-side 둘 다 가능
   - 없으면 Phase 1 keyword 모드 유지 또는 색인 재구성 필요

2. **`_inference` endpoint(ELSER/E5 등) 또는 ingest pipeline에 inference processor가 등록되어 있는가?**
   - 있으면 → **모드 (a) `semantic-server`** (앱은 텍스트만 보내고 ES가 임베딩/확장)
   - 없으면 → **모드 (b) `semantic-client`** (앱이 임베딩 API 호출 후 벡터를 `knn` 쿼리로 전송)

3. **권장 검색 쿼리 예시 (`curl` 한 줄)**
   - 인덱스명, 벡터 필드명(예: `embedding`, `content_embedding`), 모델 ID, 분석기 설정 등 실제 동작하는 쿼리 본문 확보
   - 이 응답이 그대로 `RagService.buildQueryBody()` 분기 구현 스펙이 됨

---

## 모드별 구현 계획

### 모드 (a) `semantic-server` — ES 서버 측 임베딩 (앱은 텍스트만 송신)

**전제:** 사내 ES에 `_inference` endpoint 또는 `text_expansion` / `semantic` 쿼리가 동작하도록 인덱싱되어 있음.

**예상 ES 쿼리 형태 (운영팀 답변에 따라 교체):**

ELSER 사용 시 (`text_expansion`):
```json
{
  "size": 3,
  "query": {
    "text_expansion": {
      "ml.tokens": {
        "model_id": ".elser_model_2",
        "model_text": "<query>"
      }
    }
  }
}
```

ES 8.11+ semantic_text 필드 사용 시:
```json
{
  "size": 3,
  "query": {
    "semantic": {
      "field": "<semantic_field>",
      "query": "<query>"
    }
  }
}
```

**필요한 추가 설정 (Settings 모달):**
- `rag.search.semantic.model-id` (선택, ELSER 등 model_id 명시 필요한 경우)
- `rag.search.semantic.tokens-field` (ELSER 토큰 필드명, 기본 `ml.tokens`)
- `rag.search.semantic.semantic-field` (semantic_text 필드명)

**구현 작업:**
- `HeapDumpConfig`, `HeapDumpAnalyzerService`에 위 필드 3개 추가 (Phase 1과 동일 패턴)
- `application.properties`에 키 추가
- `RagService.buildQueryBody()`에서 `mode == "semantic-server"` 분기 추가 — 운영팀이 준 쿼리 본문을 그대로 만들기
- `settings.html` 모달에서 `searchMode == "semantic-server"` 선택 시 Phase 2 필드 박스 노출 (`onRagModeChange()` 헬퍼)
- 인덱스 매핑 검증 로직 추가 (선택): `testConnection()`에서 `GET /{index}/_mapping` 호출해 필요한 필드 존재 여부 표시

**예상 작업량:** 0.5일 (운영팀 쿼리 예시 받은 직후 기준)

---

### 모드 (b) `semantic-client` — 앱이 임베딩 API 호출 후 kNN 쿼리

**전제:** 사내 ES 인덱스에 `dense_vector` 필드가 있고, 앱에서 사용할 임베딩 API(별도 모델 서비스, 또는 OpenAI/Cohere 등)가 별도로 제공됨.

**검색 흐름:**
1. 사용자 query → 임베딩 API 호출 → `float[]` 벡터 획득
2. `POST {url}/{index}/_search`에 `knn` 쿼리로 벡터 전송

```json
{
  "knn": {
    "field": "<vector_field>",
    "query_vector": [0.123, -0.456, ...],
    "k": 3,
    "num_candidates": 30
  },
  "_source": ["<text_field>"]
}
```

**필요한 추가 설정 (Settings 모달):**
- `rag.embedding.api.url` — 임베딩 API URL (예: `https://api.openai.com/v1/embeddings`, 또는 사내 모델 서버)
- `rag.embedding.api.key` — API Key (ENC(...) AES 암호화 저장 — Phase 1 패턴 재사용)
- `rag.embedding.model` — 임베딩 모델명 (예: `text-embedding-3-small`)
- `rag.embedding.provider` — `openai` | `cohere` | `custom` (요청/응답 형식 분기용)
- `rag.search.knn.vector-field` — `dense_vector` 필드명 (예: `embedding`)
- `rag.search.knn.num-candidates` — kNN 후보 수 (기본 `topK * 10`)

**구현 작업:**
- 위 6개 설정 필드 추가 (LLM 설정과 동일한 멀티 프로바이더 패턴)
- `RagService` 또는 별도 `EmbeddingService`에 `embed(query)` 메서드 추가:
  - OpenAI: `POST /v1/embeddings` `{model, input}` → `data[0].embedding`
  - Cohere: `POST /v1/embed` `{model, texts}` → `embeddings[0]`
  - Custom: OpenAI 호환 가정 (LLM 설정의 custom과 동일)
- `RagService.buildQueryBody()`에서 `mode == "semantic-client"` 분기 추가 — `embed(query)` 호출 후 `knn` JSON 빌드
- 임베딩 API 타임아웃/재시도/에러 처리 (LLM `callLlm*` 패턴 참고)
- 임베딩 캐싱 검토 (선택): 동일 query 반복 호출 방지용 LRU 인메모리 캐시 (TTL 5분, 최대 100건). 사내 환경 부하/비용 따라 결정
- `settings.html` 모달에 "Embedding API" 섹션 추가 (`searchMode == "semantic-client"` 선택 시 노출). LLM 설정 페이지의 Provider 셀렉트 패턴 재사용

**예상 작업량:** 1~1.5일

---

## 모드 (a) vs (b) 비교

| 항목 | (a) semantic-server | (b) semantic-client |
|------|---------------------|---------------------|
| 앱 구현 복잡도 | 낮음 (쿼리 JSON 빌더만) | 중간 (임베딩 API 호출 + 키 관리 추가) |
| 추가 외부 의존성 | 없음 (ES만) | 임베딩 API 서비스 필요 |
| 임베딩 모델 일관성 | 색인 = 검색 동일 모델 자동 | 색인 모델과 검색 모델 일치 보장 필요 |
| 비용 | ES 라이선스에 포함 | API 호출당 과금 (provider에 따라) |
| 지연시간 | ES 1회 호출 | 임베딩 + ES 2회 호출 |
| 권장 | **사내 ES가 ELSER/inference 지원 시 우선** | **임베딩 API 운영 중이고 ES는 단순 벡터 저장만 할 때** |

대부분의 사내 환경은 (a)가 운영 부담이 적어 더 일반적입니다. 운영팀 답변이 (a)/(b) 모두 가능이면 (a) 우선 채택을 추천합니다.

---

## Phase 2 작업 체크리스트

운영팀 답변 받으면 아래 체크리스트로 진행:

### 공통
- [ ] 운영팀에서 받은 ES 쿼리 예시 본문을 `RagService.buildQueryBody()`에 그대로 반영
- [ ] `application.properties`에 모드별 신규 키 추가
- [ ] `HeapDumpConfig` / `HeapDumpAnalyzerService` 영속화 필드 추가 (Phase 1 패턴 재사용)
- [ ] `settings.html` 모달에 모드별 동적 노출 영역 추가 (`onRagModeChange()`)

### 모드 (a) 추가
- [ ] `text_expansion` 또는 `semantic` 쿼리 빌더 분기
- [ ] `model-id` / `tokens-field` / `semantic-field` 설정 노출

### 모드 (b) 추가
- [ ] `EmbeddingService` 신규 (또는 `RagService` 내부 메서드)
- [ ] OpenAI / Cohere / Custom provider 분기
- [ ] 임베딩 API Key ENC 암호화 저장 (Phase 1 password 패턴 재사용)
- [ ] `knn` 쿼리 빌더 분기
- [ ] (선택) 임베딩 인메모리 LRU 캐시
- [ ] `vector-field` / `num-candidates` 설정 노출

### 검증
- [ ] 사내 환경에서 실제 검색 결과로 LLM 응답 품질 비교 (RAG ON vs OFF)
- [ ] 응답 latency 측정 (P50/P95). 검색 + LLM 합계가 사용자 체감 가능한 지연(>3초)이면 캐시/타임아웃 튜닝
- [ ] 잘못된 인덱스/필드명에 대한 에러 메시지 명확성 확인

---

## 관련 파일 위치 (Phase 1 기준 — Phase 2도 동일 위치 수정)

| 영역 | 파일 |
|------|------|
| 프로퍼티 | `src/main/resources/application.properties` |
| 설정 클래스 | `src/main/java/com/heapdump/analyzer/config/HeapDumpConfig.java` |
| 런타임 서비스 + 영속화 | `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java` |
| RAG 검색 로직 | `src/main/java/com/heapdump/analyzer/service/RagService.java` |
| API 엔드포인트 | `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java` |
| LLM 흐름 통합 | `src/main/java/com/heapdump/analyzer/controller/AiChatController.java`, `HeapDumpController.java` |
| Settings UI | `src/main/resources/templates/settings.html` |
| 암호화 유틸 | `src/main/java/com/heapdump/analyzer/util/AesEncryptor.java` (재사용, 변경 없음) |

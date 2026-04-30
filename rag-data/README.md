# RAG 학습 데이터 CSV

사내 RAG(Elasticsearch) 인덱스에 투입할 학습 데이터. **keyword(BM25) 모드** 기준으로 작성.

## 파일 목록

| 파일 | 설명 |
|---|---|
| `rag-knowledge-20260430.csv` | 총 84행. 카테고리별: heap_analysis 15 / java_spring 15 / mat_tip 13 / troubleshooting 11 / was_jeus 10 / was_webtob 10 / was_weblogic 10 |

## CSV 컬럼 스펙

| 컬럼 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `id` | string | ✅ | 고유 식별자. ES `_id`로 사용 권장(멱등 재인덱싱). 비우지 말 것 |
| `category` | enum | ✅ | `heap_analysis` / `java_spring` / `mat_tip` / `troubleshooting` / `was_jeus` / `was_webtob` / `was_weblogic` 중 1개 |
| `title` | string | ✅ | 60자 이내 한 줄 제목 |
| `content` | text | ✅ | **BM25 검색 본문.** 800~1500자 권장(=청크 1개). 메타 사항(버전·태그)은 본문에 자연어로 녹임 |
| `tags` | string | - | 콤마 구분 키워드(공백 없이). 예: `OOM,GC,G1GC` |
| `source` | string | - | 출처 URL 또는 문서명 |
| `severity` | enum | - | `critical` / `high` / `medium` / `low` / `info`. 비우면 `info` |
| `created_at` | ISO date | - | `YYYY-MM-DD` 형식 |

> `content` 한 필드만 LLM 컨텍스트에 들어감(`RagService.fetchContextForLlm()`). title/source 등 메타정보를 LLM이 보게 하려면 content 본문 안에 포함시켜 작성.

## CSV 형식 규칙 (RFC 4180)

- **인코딩:** UTF-8 (BOM 없음). 사내 등록 도구가 BOM 요구 시 추가
- **구분자:** 콤마 `,`
- **인용:** 값에 `,` `"` 줄바꿈 포함 시 `"..."`로 감쌈. 본문 내 `"`는 `""`로 이스케이프
- **빈 값:** 그냥 비워둠. `null`, `-` 같은 문자열 쓰지 말 것
- **헤더 행:** 첫 줄에 컬럼명

## 카테고리별 작성 가이드

### `heap_analysis` — Heap dump 분석 노하우
- id: `oom-YYYY-NNN`, `leak-YYYY-NNN`, `gc-YYYY-NNN`
- content 구조: 증상 → 의심 패턴 → 분석 절차 → 해결 방향
- severity: critical/high

### `java_spring` — Java/Spring 운영 가이드 / FAQ
- id: `java-{영역}-NNN` (예: `java-spring-014`, `java-jvm-003`)
- 버전 의존성 본문에 명시 (`Spring Boot 2.7+` 등)

### `mat_tip` — Eclipse MAT 팁
- id: `mat-tip-NNN`, `mat-oql-NNN`
- MAT 버전이 다르면 별도 row

### `troubleshooting` — 사내 트러블슈팅 사례
- id: `ts-YYYY-NNN`
- content 구조: 일자 → 증상 → 원인 → 해결 → 재발방지
- 민감정보(IP·내부 시스템명) 마스킹 필수
- severity 필수
- **현재 초안의 11건은 모두 가상 예시.** `source`에 `(예시 — 실제 사례로 교체 필요)` 표시. 사내 인시던트로 교체 후 등록 권장

### `was_jeus` / `was_webtob` / `was_weblogic` — WAS/WebServer 제품별
- id: `jeus-NNN`, `webtob-NNN`, `weblogic-NNN`
- 제품 공식 문서 + 운영 노하우 혼합
- heap dump 캡처 / 메모리 영역 / 세션 / Connection Pool / Thread Pool / 클러스터 / 버전별 차이 등 운영 핵심 토픽 위주
- 사내 환경 설정값(포트·도메인·계정명)은 본문에 포함하지 말 것 — 일반화된 가이드 형태 유지

## 청킹 가이드

- 한 row = 한 청크 (인덱싱 시점에 자동 청킹 안 됨)
- 권장 길이: content 800~1500자. 2000자 초과 시 분할
- 분할 id 규칙: `xxx-p1`, `xxx-p2` 접미사. title도 `(1/2)` 표기
- 100자 미만 row 피할 것 (BM25 점수 노이즈)

## ES 인덱스 매핑 (운영팀 전달용)

```json
{
  "mappings": {
    "properties": {
      "id":         { "type": "keyword" },
      "category":   { "type": "keyword" },
      "title":      { "type": "text", "analyzer": "nori" },
      "content":    { "type": "text", "analyzer": "nori" },
      "tags":       { "type": "keyword" },
      "source":     { "type": "keyword" },
      "severity":   { "type": "keyword" },
      "created_at": { "type": "date", "format": "yyyy-MM-dd||strict_date_optional_time" }
    }
  }
}
```

- 한국어 분석기는 사내 표준에 맞게 (nori / standard / 사내 커스텀)
- 향후 semantic 모드 확장 시: `ml.tokens`(ELSER) 또는 `embedding`(dense_vector, dims=1536) 필드 추가

## 등록 후 검증 (앱에서)

1. **`/settings/rag`** → URL/index/auth 입력 → Search Mode `keyword`, Text Field `content` → Save All → Test Connection
2. **검색 프로브** (rag-settings 페이지 검색 박스): `OOM G1GC`, `Spring Session 만료`, `MAT leak suspect` → CSV 본문이 결과에 포함되는지 확인
3. **`/ai-chat`** → RAG 토글 ON → `Old Generation OOM이 G1GC에서 반복되는데 뭐부터 봐야 해?` 같은 질문 → 답변에 CSV `source`/근거 포함되면 성공
4. **품질 비교**: 동일 질문을 RAG OFF로도 던져 차이 비교

## 주의사항

- **id 비우지 말 것** — ES `_id`로 활용. 비우면 random ID로 중복 누적
- **content에 비밀정보 금지** — 인덱싱 후 ES 권한자 모두 검색 가능
- **Excel 자동변환 주의** — `2026-04-30`이 날짜 셀로 변환되거나 큰 숫자가 지수표기로. VS Code/LibreOffice 또는 텍스트 모드 저장 권장
- **`category` 값 오타 주의** — keyword 타입이라 `heap_analysis` ≠ `Heap_Analysis`

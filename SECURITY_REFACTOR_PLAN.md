# Security & Refactoring Plan

## Phase 1: 보안 취약점 수정 (Critical)

- [x] **1A. XSS — HTML 새니타이징 라이브러리 도입** (2026-03-29 완료)
  - OWASP Java HTML Sanitizer 20220608.1 도입
  - util/HtmlSanitizer.java 신규 생성 (whitelist PolicyFactory)
  - MatReportParser.sanitizeHtml() + HeapDumpAnalyzerService.extractBodyContent() 중복 제거
- [x] **1B. Path Traversal — Controller 입구에서 파일명 검증** (2026-03-29 완료)
  - util/FilenameValidator.java 신규 생성 (null/empty, .., /, \, null byte 차단, 확장자 whitelist)
  - Controller 16개 엔드포인트 + compare 2개 파라미터에 검증 적용
  - @ExceptionHandler → 400 응답
- [x] **1C. 정보 노출 — /api/settings 응답 축소** (2026-03-29 완료)
  - 절대 경로 마스킹: heapDumpDirectory, mat.path → `***/filename` 형태로 변환
  - 서버 상세 정보 제거: javaVendor, osName, osArch 필드 삭제
  - MAT 파일 권한 상세 제거: exists, executable, readable 필드 삭제 (ready/statusMessage 유지)
  - matCliPath 최상위 필드 제거 (mat.path로 통합)
  - settings.html에서 Java Vendor, OS 행 제거 및 JS 정리
- [x] **1D. 파괴적 GET → POST 전환** (2026-03-29 완료)
  - Controller: `/delete/{filename}`, `/history/delete/{filename}`, `/analyze/rerun/{filename}` → @PostMapping 전환
  - 프론트엔드: 모든 호출을 동적 form POST 제출로 변경 (index, files, history, analyze, progress)

## Phase 2: 런타임 안전성 (Quick Wins)

- [x] **2A. 스레드 풀 바운딩** (2026-03-29 완료)
  - `Executors.newCachedThreadPool()` → `ThreadPoolExecutor(core=2, max=4, queue=8, CallerRunsPolicy)`
  - 무제한 스레드 생성 방지, 큐 포화 시 CallerRunsPolicy로 백프레셔 적용
- [x] **2B. Regex 패턴 사전 컴파일** (2026-03-29 완료)
  - 12개 `static final Pattern` 상수 추가 (NON_DIGIT, WHITESPACE, HEX_ADDR 등)
  - 로컬 `Pattern.compile` 2개 → 사전 컴파일 상수로 전환
  - 인라인 `String.replaceAll` → `Pattern.matcher().replaceAll()` 전환
  - `stripTags()`, `digitsOnly()` 헬퍼 메서드 추가

## Phase 3: 코드 중복 제거 및 설정 추출

- [x] **3A. formatBytes() 통합** (2026-03-29 완료)
  - `util/FormatUtils.java` 신규 생성 — `public static formatBytes(long)`
  - Controller, Service, Model 3곳의 `private formatBytes()` → `FormatUtils.formatBytes()` 위임
- [x] **3B. 하드코딩 값 설정 추출** (2026-03-29 완료)
  - `HeapDumpConfig`에 8개 `@Value` 프로퍼티 추가 (mat.timeout.minutes, sse.emitter.timeout.minutes 등)
  - Service: MAT_TIMEOUT_MINUTES 상수, 진행률 업데이트 빈도 → Config 참조
  - Controller: maxDisplay, SSE 타임아웃, 디스크 경고 임계값, 로그 표시 길이, Top Objects 제한 → Config 참조

## Phase 4: 구조 분리 (대형 리팩토링)

- [~] **4A. 서비스 분리** — *부분 완료 (2026-05-12)*
  - [x] **4A-1. HeapAnalysisResultCache 분리** (2026-05-12 완료, Phase 6-1)
    - `memCache` `ConcurrentHashMap<String, HeapAnalysisResult>` 와 순수 map 연산 7 개를 별도 `@Component` 로 추출
    - 외부 API (`getCachedResult`/`clearCache`/`getCachedResultCount`/`getAllCachedResults`/`getCacheKeys`) 시그니처 무변경 — facade 패턴
  - [x] **4A-2. LlmConfigService 분리** (2026-05-12 완료, Phase 7-2)
    - 12 LLM 필드 + 17 getter/setter + 4 호출 메서드(`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`/`testLlmConnection`) + `disableSslVerification` + `GENSPARK_MODELS` 이전
    - settings.json/application.properties 영속화는 `applyFromSettings`/`collectSettings`/`collectApplicationProperties` hook 으로 분리
    - 외부 API facade 유지 — Controller 수정 없음
    - `HeapDumpAnalyzerService` 3,445 → 2,644 (-801 라인)
  - [x] **4A-3. RagConfigService 분리** (2026-05-12 완료, Phase 7-3)
    - 26 RAG 필드 + 32 getter + 5 그룹 setter + AES 암호화 + settings 영속화 hook 이전
    - `RagService`/`EmbeddingService` 생성자 재배선 — `HeapDumpAnalyzerService` 대신 `RagConfigService` 직접 주입
    - `HeapDumpAnalyzerService` 의 RAG 메서드는 facade 유지 (Controller 무변경)
    - `HeapDumpAnalyzerService` 2,644 → 2,327 (-317 라인)
  - [x] **4A-4. FileManagementService 분리** (2026-05-12 완료)
    - [x] **4A-4 Phase 1** (Phase 7-1): 유틸/조회 8 메서드 — `listFiles`/`checkDuplicate`/`computePartialHash`/`generateUniqueName`/`isValidHeapDumpFile`/`stripExtension`/`getExtension`/`dumpFilesDirectory`. -136 라인
    - [x] **4A-4 Phase 2** (Phase 7-4): I/O 메서드 — `uploadFile`/`getFile`/`deleteFile`/`compressDumpFile`/`decompressDumpFile`/`cleanupDuplicateGzFiles` + 디렉토리 helper (`tmpDirectory`/`resultDirectory`/`resultJsonFile`). -263 라인
    - 분석 파이프라인 결합 메서드 (`cleanupTmpDir`/`moveZipsToResultDir`/`deleteHistory(DB)`/마이그레이션) 은 HeapDumpAnalyzerService 잔존.
  - [x] **4A-5. AiInsightManager 분리** (2026-05-12 완료, Phase 7-5)
    - `saveAiInsight`/`loadAiInsight`/`deleteAiInsight`/`migrateAiInsightsToDb` 4 메서드 + 상수(`AI_INSIGHT_FILE`/`RESULT_JSON`) 이전
    - 의존성: `AiInsightRepository` + `FileManagementService` + `HeapDumpConfig`
    - -99 라인

**Phase 4A 종합**: `HeapDumpAnalyzerService` 3,581 → **1,965** (-1,616 라인, **-45%**). 추출된 컴포넌트: `HeapAnalysisResultCache` / `FileManagementService` / `LlmConfigService` / `RagConfigService` / `AiInsightManager`.
- [x] **4B. Controller 분리** (View / API) — *부분 완료 (2026-05-12)*
  - [x] **4B-1. 단순 2 분할 (View + API)** (2026-05-12 완료)
    - `HeapDumpViewController` 신규 (516 라인) — Thymeleaf 페이지 9 + form-redirect POST 4 + 분석 결과/미리보기 페이지 4 = 총 15 엔드포인트
    - `HeapDumpController` 잔존 (2,365 라인) — REST API + SSE + 바이너리(PDF/Resource) + iframe HTML 50 엔드포인트
    - 공유 헬퍼 6 개 (`isAdmin`/`buildHistory`/`aggregateDetections`/`buildClassSizeMap`/`formatBytes`/`truncateLog`) `private` → `public` 가시성 승격
    - inner public static DTO 8 개는 `HeapDumpController` 잔존, view 측에서 `HeapDumpController.AnalysisHistoryItem` 으로 참조 (facade 패턴)
    - URL/외부 시그니처 100% 무변경 — 회귀 위험 최소
  - [x] **4B-2. API 도메인별 6 분할** (2026-05-17 완료)
    - 6 신규 컨트롤러: `HeapAnalysisApiController` (80, SSE/큐/취소) / `HeapReportApiController` (350, MAT HTML·iframe·PDF·log·thread-stacks) / `HeapFileApiController` (141, upload/download/bulk-delete) / `HeapHistoryApiController` (283, history·detections·compare data·cache clear) / `HeapSystemApiController` (432, settings·DB·MAT·disk·system) / `HeapAiApiController` (754, LLM + RAG)
    - DTO 11 개 → `model/dto/` 패키지로 추출 (`AnalysisHistoryItem`/`DailyDetection`/`ServerSeries`/`DetectionSummaryItem`/`DetectionAggregate`/`DetectionDayFile`/`DetectionRecentItem`/`ClassDiff`/`HistogramDiff`/`SuspectDiff`/`KpiDiff`)
    - 공유 헬퍼 → `HeapHistoryAggregator` @Component 신규 (585 라인): `buildHistory`/`buildAnalysisName`/`buildClassSizeMap`/`buildClassDiffs`/`buildHistogramDiffs`/`buildSuspectDiffs`/`buildKpiDiff`/`aggregateDetections`/`truncateLog`/`formatDuration` + 내부 helpers
    - `AuthUtil.isAdmin(Authentication)` static util 추출 → View/Comparison 컨트롤러 공용
    - `HeapDumpController.java` (2,898 라인) 완전 삭제. `HeapDumpViewController`/`ComparisonHistoryController`/`ComparisonHistoryService` import 갱신
    - URL/외부 시그니처 100% 무변경. 인증 세션으로 6 도메인 모든 대표 API 200 OK 회귀 검증
    - 빌드 SUCCESS / 기동 10.55s 정상

## Phase 5: 프론트엔드 정리

- [x] **5C. analyze.html 인라인 JS 외부화** (2026-05-17 완료)
  - `static/js/analyze.js` (3,125 라인, 149KB) 신규 — 라인 1011-4137 의 순수 JS (Thymeleaf 표현식 0건) 추출
  - `analyze.html` 4,183 → 1,057 라인 (**-3,126 라인**, -75%)
  - 인라인 `<script>` 블록 → `<script src="/js/analyze.js?v=2026-05-17"></script>` 1 라인 치환
  - Thymeleaf 인라인 변수 블록 (line 993-1008, `USED_BYTES`/`FILENAME`/`OBJ_NAMES` 등 13 vars) 은 인라인 유지 — external JS 가 같은 글로벌 vars 를 참조 (script 순서 보장)
  - 빌드 SUCCESS / 기동 정상 / `/js/analyze.js` 200 + `/analyze/result/{filename}` 200 + script src 참조 확인

- [~] **5A. 공통 CSS 추출** — *부분 완료 (2026-05-12)*
  - [x] **5A-1. common.css 인프라 + 안전 패턴 추출** (2026-05-12 완료, Phase 6-3)
    - `static/css/common.css` (60 라인): reset, `body` base, `.topbar*` (표준 패턴)
    - 12 개 페이지 (`index`/`files`/`history`/`settings`/`servers`/`admin/users`/`ai-chat`/`llm-settings`/`rag-settings`/`server-detail`/`server-logs`/`login`) 에 적용 — 인라인 165 라인 삭제
    - anti-wrap topbar 확장 (servers/server-logs/admin/users) 은 의도된 변형, 인라인 유지
  - [x] **5A-2. analyze.html 인라인 CSS 분리** (2026-05-12 완료, Phase 6-5)
    - `static/css/analyze.css` (738 라인) — 페이지 고유 CSS 변수/레이아웃 외부화
    - `analyze.html` 4866 → 4127 라인 (-739)
  - [x] **5A-3. modal/btn/grid 공통 패턴 확장** — *2026-05-17 완료 (modal + btn 색상, grid 는 토큰 표준화 사이클 이연)*
    - **Round 1**: `common.css` 에 `.modal-ov` base + `.modal-ov.open { display: flex }` + `@keyframes modalIn` 추가. history / comparison-history / login / admin-users 4 페이지에서 중복 제거. files (`.35` + fadeIn) / leak-rules (common.css 미적용) 보존.
    - **Round 2**: `common.css` 에 `.modal-box` base 4 속성 (background / border-radius:12px / padding:24px / box-shadow) 추가. 5 페이지 인라인 정리:
      - history.html / comparison-history.html: 4 속성 제거, `width:90%; max-width:420px; animation:modalIn`만 잔존 (1라인)
      - admin/users.html: 4 속성 제거, `width:90%; max-width:440px; animation:modalIn`만 잔존
      - login.html: 4 속성 중 background/padding/box-shadow 제거, `border-radius:14px` 변형 override 유지 + width/max-height/overflow-y/animation 인라인
      - settings.html: 4 속성 중 background/border-radius/box-shadow 제거, `padding:28px 30px` 변형 override 유지 + 이번 라운드에 `.modal-ov` / `@keyframes modalIn` 잔존 제거
    - 변형 보존: `files.html` (box-shadow 다름 `.15`, modalIn 미사용) / `leak-rules.html` (common.css 미적용 + padding 변형) 변경 없음
    - **Round 3**: 페이지별 명명 분기(`.mbtn-cancel` / `.btn-cancel` / `.sa-btn-cancel` 등)를 multi-selector 로 묶어 색상만 common.css 에 통합. 박스 속성(padding/font-size/border-radius/font-weight)은 페이지별 인라인 유지.
      - cancel(`#F3F4F6/#374151` → hover `#E5E7EB`): `.mbtn-cancel, .btn-cancel, .sa-btn-cancel`
      - danger(`#EF4444/#fff` → hover `#DC2626`): `.mbtn-del, .mbtn-danger, .btn-delete, .sa-btn-del`
      - primary(`#2563EB/#fff` → hover `#1D4ED8`): `.mbtn-save, .mbtn-primary, .mbtn-confirm, .btn-download`
      - 8 페이지 인라인 색상 정의 ~40 라인 제거: comparison-history / login / servers / compare / settings / files / history / admin-users
      - `:disabled` variant (login `mbtn-save`, files/history/comparison-history `sa-btn-del`) 는 페이지별 색상 차이가 있어 인라인 유지 (cascade 가 base 위에 override)
      - `leak-rules.html` 은 common.css 미적용이라 제외
    - **Round 4 (grid CSS)**: 4 테이블 family (htable/ftable/stable/utable) multi-selector base 통합. width/thead bg/th 색상속성·typography/td 테두리·hover/last-child border 통합. `.sortable` cursor+hover+sort-active 색상도 통합 (sort-arrow 자체는 font-size 9/10px 차이로 인라인 유지). `padding`/`font-size`/`min-width` 변형은 페이지별 인라인. ltable (server-logs.html) 은 hover #FAFAFA + th 직접 background 구조라 제외. 5 페이지 (comparison-history / history / files / servers / admin/users) 정리, ~50 라인 추가 제거.
    - 13 페이지 `common.css?v=` 캐시 무효화 (2026-05-12 → 2026-05-17c). 빌드 SUCCESS / 기동 정상 / `/css/common.css` 본문에 modal-ov/modalIn/modal-box/btn 색상 utility 모두 확인.
- [~] **5B. JS 네임스페이스화** — *인프라만 도입 (2026-05-12)*
  - [x] **5B-1. common.js (window.Common) 인프라** (2026-05-12 완료, Phase 6-4)
    - `static/js/common.js` (97 라인): `Common.escHtml`/`csrfToken`/`csrfHeaderName`/`fetchJSON`/`appendCsrfToForm`/`formatBytes`
    - `fragments/banner.html` 에서 1 회 로드 → 14 개 페이지 자동 가용
  - [x] **5B-2. 페이지 인라인 `escHtml`/CSRF 호출의 `Common.*` 마이그레이션** (2026-05-17 완료, A+B+C, D 의도적 보류)
    - **A 그룹 (escHtml/escapeHtml alias)**: 9 파일 12 정의 → `var escHtml = Common.escHtml;` (또는 escapeHtml) 별칭으로 치환. 호출처 무변경. textContent 기반 3 문자 버전(`<>&` 만) → Common 의 5 문자(`&<>"'`) escape 로 XSS 방어 강화.
      - 대상: ai-chat / compare / index (2) / leak-rules / analyze (3: 2311/2579/3463) / admin-users / server-logs / history / servers
    - **B 그룹 (CSRF appendCsrfToForm)**: 6 곳의 3 라인 패턴(`var ci = createElement; ci.type/name; ci.value = querySelector(meta); appendChild`) → `Common.appendCsrfToForm(f)` 1 라인 치환.
      - 대상: index / history / progress / analyze / files (2)
    - **C 그룹 (CSRF meta 직접 읽기)** — 7 페이지 14 라인 → 4 라인 `var csrfMeta/csrfHeaderMeta` 패턴은 2 라인 `var csrfToken = Common.csrfToken(); if (csrfToken) headers[Common.csrfHeaderName()] = csrfToken;` 으로, 모듈 레벨 `var _csrf = ...content` 변수는 RHS만 `Common.csrfToken()` / `Common.csrfHeaderName()` 호출로 치환 (변수명·호출처 무변경).
      - 대상: comparison-history (2 occurrences) / history / files / rag-settings / admin/users / server-detail / leak-rules
    - **D1 (안전 fetchJSON)** — 2026-05-17 추가: `if (!r.ok) throw + return r.json()` 패턴 18 곳을 `Common.fetchJSON` 으로 치환. Common.fetchJSON 의 시맨틱(non-2xx throw + 자동 JSON 파싱)이 100% 일치하는 안전 후보만 선별. POST 의 수동 `headers: {Content-Type, X-CSRF-TOKEN}` 구성 코드도 함께 제거 (fetchJSON 이 자동 부착).
      - 대상: llm-settings (7) / rag-settings (1) / server-logs (2) / settings (2) / history (2) / admin-users (4)
      - 보류: compare.html (1 곳 — 에러 메시지 포맷 `compare data 로드 실패 (404)` 가 `HTTP 404: <body>` 로 변경되어 시각적 회귀)
    - **D2 (side-effect only)** — 2026-05-17 추가: settings.html 3 곳 (`saveSetting` / `saveCompressSetting` / `doDisableCompress`) — `.then(r => { if (!r.ok) throw; toast(...) })` 패턴 → `Common.fetchJSON(...).then(function(){ toast(...) })` 로 치환. 파싱된 JSON 을 discard 해 동작 동일.
    - **D3 (r.text)** — 2026-05-17 추가: analyze.html 2 곳 (`loadFullErrorLog` line 109 — `.catch err 미사용` / 컴포넌트 parsed 폴백 line 1321 — `.catch()` 인자 없음). Common.fetchJSON 이 non-JSON Content-Type 시 text 반환하므로 양쪽 안전. 엔드포인트 `/analyze/log/*` text/plain, `/report/.../component-detail-parsed` JSON ✓.
    - **D 최종 보류 (2건)**: analyze.html line 1352 (raw-detail) — catch err 가 사용자 가시 fallback 텍스트에 interpolate / compare.html line 1543 — 커스텀 에러 메시지가 .catch 에서 사용자 표시 → 메시지 회귀 회피.
    - 검증: 빌드 SUCCESS / 기동 정상 / 7 페이지 200 OK + Common.csrf* 참조 확인 (`/files` 2, `/history` 2, `/comparison-history` 4, `/admin/users` 2, `/settings/rag` 2, `/admin/leak-rules` 2).

## Phase 6: 추가 보안/구조 개선 (2026-05-12 신규)

- [x] **6-2. GlobalExceptionHandler 중앙화** (2026-05-12 완료)
  - `@ControllerAdvice GlobalExceptionHandler` 신규
  - `IllegalArgumentException` → JSON 400 (`/api/`/AJAX) 또는 HTML 302 redirect (`?error=invalidFilename`)
  - `HeapDumpController.handleBadFilename()` 제거 — 향후 다른 컨트롤러에 동일 검증 도입 시 자동 적용
  - 위 항목 외 (4A-1, 5A-1, 5A-2, 5B-1) 은 위 Phase 4·5 의 부분 완료에 통합 기록

## 보류 항목 (대형 리팩토링 — 별도 사이클)

> **2026-05-17 전체 완료** — 모든 보류 항목 해소.

| 완료 항목 | 완료 라운드 |
|---|---|
| 4A-2 LlmConfigService 분리 | 2026-05-12 (Phase 7-2) |
| 4A-3 RagConfigService 분리 | 2026-05-12 (Phase 7-3) |
| 4A-4 FileManagementService 분리 | 2026-05-12 |
| 4B-2 API 도메인별 6 분할 | 2026-05-17 |
| 5A-3 modal/btn/grid 공통 패턴 (Round 1~4) | 2026-05-17 |
| 5B-2 Common.* 마이그레이션 (A+B+C+D1+D2/D3) | 2026-05-17 |
| 5C analyze.html JS 외부화 | 2026-05-17 |

> 향후 작업은 새로 식별되는 항목 기준으로 본 문서를 갱신할 것.

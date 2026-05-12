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
  - [ ] **4B-2. API 도메인별 6 분할** (보류) — 추가 분리 권장안 (Analysis/Report/File/History/System/Ai), 의존성·DTO 이전 영향 큼. 별도 사이클.

## Phase 5: 프론트엔드 정리

- [~] **5A. 공통 CSS 추출** — *부분 완료 (2026-05-12)*
  - [x] **5A-1. common.css 인프라 + 안전 패턴 추출** (2026-05-12 완료, Phase 6-3)
    - `static/css/common.css` (60 라인): reset, `body` base, `.topbar*` (표준 패턴)
    - 12 개 페이지 (`index`/`files`/`history`/`settings`/`servers`/`admin/users`/`ai-chat`/`llm-settings`/`rag-settings`/`server-detail`/`server-logs`/`login`) 에 적용 — 인라인 165 라인 삭제
    - anti-wrap topbar 확장 (servers/server-logs/admin/users) 은 의도된 변형, 인라인 유지
  - [x] **5A-2. analyze.html 인라인 CSS 분리** (2026-05-12 완료, Phase 6-5)
    - `static/css/analyze.css` (738 라인) — 페이지 고유 CSS 변수/레이아웃 외부화
    - `analyze.html` 4866 → 4127 라인 (-739)
  - [ ] **5A-3. modal/btn/grid 공통 패턴 확장** (보류) — `modalIn` 키프레임 미세 차이, btn 색상 변형 등 면밀 검토 필요
- [~] **5B. JS 네임스페이스화** — *인프라만 도입 (2026-05-12)*
  - [x] **5B-1. common.js (window.Common) 인프라** (2026-05-12 완료, Phase 6-4)
    - `static/js/common.js` (97 라인): `Common.escHtml`/`csrfToken`/`csrfHeaderName`/`fetchJSON`/`appendCsrfToForm`/`formatBytes`
    - `fragments/banner.html` 에서 1 회 로드 → 14 개 페이지 자동 가용
  - [ ] **5B-2. 페이지 인라인 `escHtml`/CSRF 호출의 `Common.*` 마이그레이션** (보류) — 회귀 위험으로 점진적 진행 권장

## Phase 6: 추가 보안/구조 개선 (2026-05-12 신규)

- [x] **6-2. GlobalExceptionHandler 중앙화** (2026-05-12 완료)
  - `@ControllerAdvice GlobalExceptionHandler` 신규
  - `IllegalArgumentException` → JSON 400 (`/api/`/AJAX) 또는 HTML 302 redirect (`?error=invalidFilename`)
  - `HeapDumpController.handleBadFilename()` 제거 — 향후 다른 컨트롤러에 동일 검증 도입 시 자동 적용
  - 위 항목 외 (4A-1, 5A-1, 5A-2, 5B-1) 은 위 Phase 4·5 의 부분 완료에 통합 기록

## 보류 항목 (대형 리팩토링 — 별도 사이클)

| 항목 | 이연 사유 | 권장 시점 |
|---|---|---|
| 4A-2 LlmConfigService | LLM 21 메서드 + RAG SSL 미러링, 단독 PR 권장 | Phase 7 |
| 4A-3 RagConfigService | RAG 26 필드 + getter/setter 40+, 단독 PR | Phase 7 |
| 4A-4 FileManagementService | 디스크/gzip/마이그레이션 결합도 큼 | Phase 7+ |
| 4B-2 API 도메인별 6 분할 | 4B-1 (View+API) 완료. Analysis/Report/File/History/System/Ai 추가 분리 — DTO/헬퍼 이전 영향 큼 | 별도 사이클 |
| 5A-3 modal/btn/grid CSS | 페이지별 미세 변형 검토 필요 | 시각 회귀 테스트 인프라 후 |
| 5B-2 Common.* 마이그레이션 | 회귀 위험, 점진적 PR | 단계적 |
| `analyze.html` HTML/JS 분할 (4127 라인) | SPA 화 결정 후 | 별도 사이클 |

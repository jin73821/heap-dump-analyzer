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

- [ ] **4A. 파일 관리 서비스 분리** (HeapDumpAnalyzerService → FileManagementService)
- [ ] **4B. Controller 분리** (View / API)

## Phase 5: 프론트엔드 정리

- [ ] **5A. 공통 CSS 추출** (7개 템플릿 → common.css)
- [ ] **5B. JS 전역 변수 네임스페이스화**

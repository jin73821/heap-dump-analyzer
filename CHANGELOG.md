# Heap Dump Analyzer — 변경 이력 (CHANGELOG)

## [2026-06-24] History 페이지 — 분석결과 목록 파일명 폰트를 Files 페이지와 통일

**대상:**
- `src/main/resources/templates/history.html`

### 변경 내용
- `.hi-name` 클래스에서 `font-family: 'JetBrains Mono', Consolas, monospace; font-size: 13px;` 제거
- Files 페이지의 `.fname` 클래스(font-family 미지정, 기본 sans-serif 상속)와 동일하게 변경

---

## [2026-06-23] Files 페이지 — 코어 덤프 다운로드·삭제 버튼 추가

**대상:**
- `src/main/java/com/heapdump/analyzer/controller/CoreDumpApiController.java`
- `src/main/resources/templates/files.html`

### 변경 내용

**백엔드 (`CoreDumpApiController.java`):**
- `GET /api/core-dump/download/{filename}` 엔드포인트 추가
  - `/opt/coredumps/dumpfiles/` 에서 파일 서빙 (`Content-Disposition: attachment`)
  - 파일 미존재 시 404

**프론트엔드 (`files.html`):**
- 코어 덤프 행에 다운로드 버튼 추가 (`!fileDeleted` 조건)
  - `showDownloadModal(filename, size, '', '', true)` 호출 → `_dlIsCore=true`
  - `btnConfirmDl` 핸들러: `_dlIsCore` 시 `/api/core-dump/download/{filename}` blob 다운로드
- 코어 덤프 행에 삭제 버튼 추가 (`!fileDeleted` 조건)
  - `confirmCoreDumpDelete(filename)` 호출 → `_delIsCore=true`
  - 삭제 모달 문구 동적 전환: "코어 덤프 파일과 분석 결과가 함께 삭제됩니다."
  - `btnConfirmDel` 핸들러: `_delIsCore` 시 `DELETE /api/core-dump/{filename}` 호출 후 페이지 새로고침

---

## [2026-06-23] 코어 덤프 업로드 후 코어 덤프 페이지 업로드 카드 사전 등록

**대상:**
- `src/main/resources/static/js/upload-queue.js`
- `src/main/resources/templates/files.html`
- `src/main/resources/templates/index.html`
- `src/main/resources/templates/core-dump/index.html`

### 변경 내용

- `upload-queue.js` `getLastQueueTypes()`: `filename` 필드 추가
- `files.html` / `index.html` `onUploadQueueDone`: 단일 코어 덤프 업로드 성공 시 `/core-dump?file={filename}` 으로 이동
- `core-dump/index.html`:
  - DOMContentLoaded 시 `?file` URL 파라미터 감지
  - 파라미터 있으면 업로드 카드에 파일명 표시 + `has-file` 스타일 적용 + 버튼 활성화 + 안내 문구 표시
  - `URL history.replaceState`로 파라미터 제거 (새로고침 재진입 방지)
  - `startUpload()`: `_preloadedFilename` 있으면 재업로드 없이 바로 `/core-dump/progress/{filename}` 이동
  - 새 파일 직접 선택 시 `_preloadedFilename` 초기화 → 기존 업로드 흐름 유지

---

## [2026-06-23] 코어 덤프 업로드 후 자동으로 코어 덤프 페이지 이동

**대상:**
- `src/main/resources/static/js/upload-queue.js`
- `src/main/resources/templates/files.html`
- `src/main/resources/templates/index.html`

### 변경 내용

- `upload-queue.js`: `UploadQueue.getLastQueueTypes()` 공개 — 마지막 업로드 배치의 파일 타입·상태 배열 반환
- `files.html` / `index.html`: `window.onUploadQueueDone` 콜백 등록
  - 업로드 완료 모달 "확인" 클릭 시:
    - 성공한 파일이 **코어 덤프만** 있는 경우 → `/core-dump` 페이지로 이동
    - 힙덤프가 1개라도 포함된 경우 (또는 혼합) → 현재 페이지 새로고침 (기존 동작 유지)

---

## [2026-06-23] 업로드 자동 분류 — 코어 덤프 패턴 감지 및 탭별 강제 분류

**대상:**
- `src/main/resources/static/js/upload-queue.js`
- `src/main/resources/templates/files.html`

### 변경 내용

**`upload-queue.js`:**
- `_uploadMode` 변수 추가 (`'auto'` | `'heapdump'` | `'coredump'`)
- `isCoreDumpFilename(name)` — `core.*`, `*.core`, `core` 정확히 일치 패턴 감지
- `resolveFileType(name)` — 모드와 파일명으로 `'heapdump'` | `'coredump'` 결정
- `enqueueFiles()` 확장자 검사 개선: `coredump` 타입은 확장자 제한 없음
- 큐 아이템에 `fileType` 필드 추가
- `startDuplicateChecks()`: 코어 덤프는 중복 검사 건너뜀 (저장 경로 다름)
- `processNextInQueue()`: `fileType === 'coredump'` 시 `/api/core-dump/upload` + `coreFile` 파라미터로 라우팅
- `UploadQueue.setUploadMode(mode)` 공개 API 추가

**`files.html`:**
- `onTabClick()`: 탭 변경 시 `UploadQueue.setUploadMode()` 호출
  - ALL 탭 → `'auto'` (파일명 패턴으로 자동 판별)
  - Heapdump 탭 → `'heapdump'` (항상 힙덤프)
  - Coredump 탭 → `'coredump'` (항상 코어 덤프)
- 탭 변경 시 `<input accept>` 속성도 동기화 (Coredump 탭: 제한 없음)

---

## [2026-06-23] Target Servers 스캔 결과에 덤프파일 날짜/시간 표시

**대상:**
- `src/main/resources/templates/servers.html`

### 변경 내용

**프론트엔드 (`servers.html`):**
- `renderScanResultsHtml()` 함수에서 파일명 아래에 날짜/시간 표시 추가
- `f.date` 필드(예: `"Jun 15 14:23"`, `"Jun 15 2024"`) 를 파일명 아래 회색 소자로 렌더링
- 날짜가 없는 경우(구버전 파서 호환) 미표시 처리 (`f.date` falsy 시 빈 문자열)
- `parseLsLine()`이 이미 `ls -la` 출력의 5·6·7번째 필드를 `date`로 반환하고 있으므로 백엔드 변경 불필요

---

## [2026-06-23] Analysis Files — ALL / Heapdump / Coredump 탭 추가

**대상:**
- `src/main/java/com/heapdump/analyzer/model/dto/AnalysisHistoryItem.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- `src/main/resources/templates/files.html`

### 변경 내용

**백엔드:**
- `AnalysisHistoryItem`에 `fileType` 필드 추가 (기본값 `"heapdump"`, 코어 덤프 항목은 `"coredump"`)
- `HeapDumpViewController`에 `CoreDumpAnalyzerService` 주입 → `buildCoreDumpHistory()` 메서드 추가
  - `/opt/coredumps/dumpfiles/` 디렉토리 파일 + `core_dump_analysis_history` DB 레코드 통합
  - 날짜 역순으로 힙덤프 목록과 병합하여 `analysisHistory`로 전달
  - 관리자: deleted 항목 포함 / 일반 사용자: fileDeleted=false만 표시

**프론트엔드 (`files.html`):**
- 테이블 상단에 **ALL / Heapdump / Coredump** 탭 추가 (`type-tabs`)
- 탭 클릭 시 `data-file-type` 속성으로 행 필터링 (JS `onTabClick()` → `applyFilter()`)
- 탭 카운트 배지: 나머지 필터(검색·서버·기간·상태) 적용 후 타입별 카운트 실시간 표시
- 코어 덤프 행 파일명 옆 `CORE` 황색 배지 표시
- 코어 덤프 액션 버튼 분리:
  - SUCCESS: `/core-dump/analyze/{filename}` View 버튼
  - ERROR/NOT_ANALYZED: `/core-dump` 이동 버튼
  - 힙덤프 전용 Download·Delete 버튼은 코어 덤프 행에 미표시

---

## [2026-06-23] 빌드 오류 수정 + Files 다운로드 로그인 이탈 버그 수정

**대상:**
- `src/main/java/com/heapdump/analyzer/service/FileManagementService.java`
- `src/main/resources/templates/files.html`

### 변경 내용

**빌드 오류 수정:**
- `FileManagementService.listFiles()` 내 `FilenameValidator.isAllowAllExtensions()` 호출 시 import 누락 → `import com.heapdump.analyzer.util.FilenameValidator` 추가

**Files 다운로드 로그인 이탈 버그 수정:**
- 기존: `window.location.href` 방식으로 `/download/{filename}` 직접 페이지 이동 → 세션 만료 또는 브라우저 종류에 따라 Spring Security가 `/login`으로 302 리다이렉트하면 사용자가 로그인 페이지로 이탈
- 수정: `fetch(..., {credentials:'same-origin'}) → r.blob() → URL.createObjectURL → a.click()` blob 다운로드 방식으로 전환 (CLAUDE.md 권장 패턴)
  - 페이지 이동 없이 다운로드 처리 (현재 페이지 유지)
  - 세션 만료 시 `Content-Disposition: attachment` 부재 감지 → `window.location.href = '/login'` 으로 명시적 안내
  - 다운로드 실패 시 `alert()` 오류 메시지 표시

## [2026-06-19] Core Dump — 코드 리팩토링 (uploadedBy 설정·감사로깅·CSS 중복 제거)

**대상:**
- `src/main/java/com/heapdump/analyzer/controller/CoreDumpApiController.java`
- `src/main/java/com/heapdump/analyzer/service/CoreDumpAnalyzerService.java`
- `src/main/resources/static/css/core-dump.css`
- `src/main/resources/templates/core-dump/index.html`
- `src/main/resources/templates/core-dump/progress.html`
- `src/main/resources/templates/core-dump/analyze.html`

### 변경 내용

**백엔드 (CoreDumpApiController, CoreDumpAnalyzerService):**
- `streamProgress` 엔드포인트에 `Principal` 추가 → `analyzeWithProgress(filename, emitter, uploadedBy)` 시그니처 변경
- `CoreDumpAnalysisEntity.uploadedBy` 최초 생성 시 설정 (기존 미설정 버그 수정)
- `deleteDump` API에 `Principal` 추가 + `[CoreDump] action=delete, filename=*, by=*` 감사 로그
- `reanalyze` API에 `Principal` 추가 + `[CoreDump] action=reanalyze, filename=*, by=*` 감사 로그
- `deleteDump()` 서비스 내부 중복 파일명 재검증 제거 (호출자에서 이미 검증됨)
- `deleteQuietly` / `deleteDirectoryQuietly` 삭제 실패를 조용히 무시하던 방식 → `logger.warn` 로그 출력으로 변경
- `ExecutorService`: `newCachedThreadPool` → `newFixedThreadPool(4)` 변경 (스레드 무한 생성 위험 방지)

**프론트엔드 CSS 리팩토링:**
- `core-dump.css`에 3개 페이지 공통 스타일 추가: `.cd-topbar`, `.topbar-back/brand/title/right`, `.cd-page`, `@media (max-width:900px)`, `.content-card`, `.content-card-title`, `.cd-tabs`, `.cd-tab-panel`, `.status-badge` 계열, `.signal-chip` 계열, `.action-btn` 계열
- `index.html`, `progress.html`, `analyze.html` 인라인 `<style>` 블록에서 공통 스타일 제거 (각 파일 약 130~170줄 감소)
- `progress.html`: `.cd-topbar { justify-content: space-between; }` 페이지 전용 override 유지
- `index.html`: `.content-card`/`.content-card-title` 인덱스 전용 크기 override 유지
- 3개 HTML 파일에 `common.css` 로드 추가 (기존 누락)

---

## [2026-06-19] Core Dump — 최초 분석 로깅/에러 표시 보강

**대상:**
- `src/main/java/com/heapdump/analyzer/controller/CoreDumpApiController.java`
- `src/main/java/com/heapdump/analyzer/service/CoreDumpAnalyzerService.java`
- `src/main/resources/templates/core-dump/index.html`
- `src/main/resources/templates/core-dump/progress.html`

### 변경 내용

**업로드 컨트롤러 로깅 보강 (CoreDumpApiController):**
- 파일명 검증 실패 시 WARN 로그 추가 (originalName, reason, 요청자)
- 업로드 성공 로그에 파일 크기 + 요청자(`principal.getName()`) 포함
- 실행 파일 업로드 성공 로그에 파일 크기 + 요청자 포함
- IOException과 일반 Exception 분리 처리 + 상세 에러 메시지

**파싱/분석 서비스 로깅 보강 (CoreDumpAnalyzerService):**
- GDB 출력 비어있음 → WARN 로그 + 에러 메시지에 조치 안내 추가
- GDB 파일 인식 실패(is not a core dump 등) → WARN 로그 (filename, reason)
- `parseGdbOutput` 완료 후 결과 요약 INFO 로그 (signal, frames, threads, warn 여부)
- 분석 완료 로그에 signal/frames/threads/경과시간 포함

**index.html 업로드 에러 표시 개선:**
- 기존: alert() 또는 uploadStatus 텍스트 변경(시인성 낮음)
- 변경: `.upload-error-box` 에러 배너 추가 — 빨간 테두리, 에러 제목 + 상세 메시지 구분 표시
- 재시도 가능: 에러 후 파일 선택 유지 + 버튼 재활성화
- 새 업로드 시도 시 이전 에러 박스 자동 초기화
- CSRF 토큰 취득 방식을 안전한 방식으로 변경 (`?.content` → null 체크)

**progress.html SSE 연결 오류 처리 개선:**
- `evtSource.onerror` 발생 시 elapsed timer 정리
- HEAD 요청으로 결과 확인 후 3가지 케이스 분기:
  1. 결과 있음 → 완료 처리 (기존 동일)
  2. 서버 응답 있지만 결과 없음 → 에러 배너 + 힌트 표시
  3. 서버 응답 없음 → 에러 배너 + 서버 재기동 안내 힌트
- `showSseError(msg, hint)` 헬퍼 함수 추출 (에러 배너 + 힌트 + active 스텝 error 처리)

## [2026-06-19] Core Dump — 재분석 버튼 구현 + 로깅/에러 표시 보강

**대상:**
- `src/main/java/com/heapdump/analyzer/service/CoreDumpAnalyzerService.java`
- `src/main/resources/templates/core-dump/progress.html`
- `src/main/resources/templates/core-dump/index.html`
- `src/main/resources/templates/core-dump/analyze.html`

### 변경 내용

**재분석 버튼 동작 개선 (index.html, analyze.html):**
- 기존: `<a href="/core-dump/progress/...">` 단순 링크 → result.json 미삭제 상태로 이동
- 변경: `POST /api/core-dump/reanalyze/{filename}` 호출(result.json 삭제) 후 progress 페이지 이동
- 버튼 클릭 중 disabled + 텍스트 변경으로 중복 클릭 방지
- CSRF 토큰 자동 포함

**progress.html 에러 배너 보강:**
- 에러 발생 시 "재분석" 버튼 추가 → `POST /api/core-dump/reanalyze/` 호출 후 페이지 리로드
- 에러 원인별 힌트 메시지 자동 표시 (파일 없음 / 시간 초과 / GDB 출력 없음 / 취소)
- `.error-hint` 영역 추가, 재분석 버튼 스타일 개선 (보라 계열, disabled 상태 처리)

**서비스 로깅 보강 (CoreDumpAnalyzerService):**
- 분석 시작: 파일명 + 실행 파일 유무 INFO 로그
- GDB 종료: exitCode ≠ 0 시 WARN (종료코드/파일명/출력길이), 출력 10자 미만 ERROR
- 분석 취소: 경과 시간 포함 WARN 로그
- Exception catch: 에러 유형 분류 (타임아웃 / IOException / 일반 예외) + 경과 시간 + stacktrace 포함

## [2026-06-19] Core Dump — 스택 프레임 소스 코드 뷰어 추가

**대상:**
- `src/main/java/com/heapdump/analyzer/service/CoreDumpAnalyzerService.java`
- `src/main/java/com/heapdump/analyzer/controller/CoreDumpApiController.java`
- `src/main/resources/templates/core-dump/analyze.html`
- `src/main/resources/static/css/core-dump.css`

### 변경 내용

**백엔드:**
- `CoreDumpAnalyzerService.readSourceContext(locationStr, contextLines)` 추가
  - `location` 필드(`/path/file.c:42` 형식)에서 경로·라인 파싱
  - `Path.toRealPath()`로 경로 정규화 (null byte / 경로 순회 차단)
  - 타겟 라인 전후 N줄(기본 8줄) 반환
- `CoreDumpAnalyzerService.existsAnalysis(filename)` 추가 — repository 위임
- `GET /api/core-dump/{filename}/source?location=...&context=N` 신규 API 추가 (`CoreDumpApiController`)

**프론트엔드 (analyze.html):**
- 크래시 히어로 카드에 `#heroSource` div 추가 → Frame #0 `location` 있을 때 페이지 로드 시 소스 자동 표시
- 스택 트레이스 탭·스레드 탭 프레임 카드에 "📄 소스" 버튼 추가 (`location != null`인 경우)
  - 클릭 시 API 호출하여 소스 뷰어 인라인 펼침/닫기 토글
  - 이미 로드된 경우 재요청 없이 캐시 사용 (`dataset.loaded`)
- 소스 뷰어: 라인 번호 + 코드 표시, 크래시 라인 빨간 강조(user 프레임은 초록)
- `CORE_FILENAME` Thymeleaf 인라인 변수로 API URL 동적 구성
- `renderSourceView()` / `loadFrameSource()` / `autoLoadHeroSource()` / `escSrc()` JS 함수 추가

**CSS (core-dump.css):**
- `.frame-src-btn` — 소스 보기 버튼 (라이트 아웃라인, 호버 시 다크)
- `.frame-source` — 소스 뷰어 영역 (다크 배경, 기본 숨김, `.visible`로 표시)
- `.src-line`, `.src-line-target`, `.src-linenum`, `.src-code` — 소스 라인 스타일
- `.hero-source` — 히어로 카드 내부 소스 뷰어

## [2026-06-19] Core Dump — 분석 전 화면 디자인 개편 + 프로그램명 절대경로 표시

**대상:** `src/main/resources/templates/core-dump/index.html`, `src/main/resources/templates/core-dump/analyze.html`

### 변경 내용

**index.html (분석 전 화면) 전면 재작성 — analyze.html 디자인 통일:**
- `section-card` → `content-card` (analyze.html과 동일 스타일: border-radius 14px, shadow)
- 업로드 존 UI 개선: 플렉스 레이아웃 + 아이콘 + 서브텍스트, `has-file` 시 solid 초록 테두리
- 이력 테이블 → 카드형 플렉스 목록 (`.hi-item`)으로 전환
  - 상태별 배경/테두리 색상 구분: SUCCESS(초록) / ERROR(빨강) / ANALYZING(파란 pulse)
  - 파일명 monospace 강조, 시그널 칩 3색 구분(fatal/warn/neutral), 업로드 시각 인라인 표시
  - 분석 시각을 상태 배지 아래 작게 표시
  - ERROR/NOT_ANALYZED 항목에 "재분석" 버튼 추가 (보라 계열)
- 모든 버튼/배지 스타일 analyze.html과 완전 통일
- CSS 버전 `?v=2026-06-18` → `?v=2026-06-19` 갱신

**analyze.html (분석 결과 화면) 프로그램명 카드 개선:**
- 기존: 전체 명령행 한 줄 표시
- 변경: `th:with`로 basename / execPath 분리
  - 첫 줄: basename (파일명만, 굵게)
  - 둘째 줄: 절대경로 (`/` 포함 시에만, 회색 monospace 11px)
  - 인자 포함 명령행(`/path/prog arg1`) 처리 — 첫 공백 이전을 execPath로 파싱

## [2026-06-19] Core Dump — 분석 결과 페이지 고도화

**대상:** `src/main/resources/templates/core-dump/analyze.html`, `src/main/resources/static/css/core-dump.css`

### 변경 내용

- **크래시 히어로 카드 신설** (기존 단순 시그널 배너 대체)
  - 최상단에서 즉시 "어느 코드에서 크래시가 났는지" 파악 가능
  - `mainBacktrace[0]`의 함수명(22px bold monospace) + 파일:라인(초록 강조) + 시그널 칩 통합 표시
  - 시그널별 3가지 색상 배경 (SIGSEGV/SIGBUS/SIGILL → 빨강, SIGABRT/SIGFPE → 노랑, 기타 → 회색)
  - gradient 배경 + border + shadow + 배경 장식 원
  - 모바일(≤600px): flex-direction column 전환
- **콜 체인 타임라인 신설** (히어로 카드 아래)
  - 상위 최대 8프레임을 세로 타임라인으로 시각화
  - 사용자 소스 코드 (초록 도트 + 초록 배경) vs 라이브러리 (보라 도트 + 희미 처리) 색상 구분
  - Frame #0 빨간 도트 + CRASH 배지
  - 8개 초과 시 "+ N개 더" 안내
- **스택 트레이스 탭 카드화** (단순 텍스트 라인 → 독립 카드)
  - 프레임별 `frame-crash/frame-user/frame-lib` 색상 구분 카드
  - `locals` (지역변수) 있는 프레임은 클릭 시 다크 배경으로 확장 (▼ 아이콘)
  - Frame #0 자동 확장 (locals 있을 경우)
- **스레드 탭 프레임도 동일 카드화** 적용
- **메타 그리드 간소화**: 6개 → 4개 (실행파일/프로그램명/GDB버전/분석시각+소요시간)
- **CSS 정리**: 시그널 배너 등 인라인 중복 제거, `core-dump.css` 신규 컴포넌트 블록 추가 (버전 `?v=2026-06-19`)

## [2026-06-19] Core Dump — 분석 이력 상태 배지·액션 버튼 디자인 개선

**대상:** `src/main/resources/templates/core-dump/index.html`

### 변경 내용

- **상태 배지 재디자인:** `inline-flex` + 왼쪽 컬러 도트 + 아웃라인 테두리 적용
  - 완료: 에메랄드 (#ECFDF5 배경 / #047857 텍스트 / #6EE7B7 테두리)
  - 실패: 로즈 (#FFF1F2 / #BE123C / #FECDD3)
  - 분석 중: 블루 pulse-ring 애니메이션 + 다이아몬드 스피너
  - 미분석: 슬레이트 (#F8FAFC / #64748B / #CBD5E1)
- **액션 버튼 재디자인:** 아웃라인 스타일 → 호버 시 배경 솔리드 전환 + 그림자 + translateY(-1px) 상승 효과
  - "결과 보기" (`action-view`): 블루 계열
  - "진행 확인" (`action-progress`): 그린 계열 (기존 `action-view`에서 분리)
  - "삭제" (`action-delete`): 로즈 계열

## [2026-06-19] 버전 2.1.1 → 2.1.2

**대상:** `pom.xml`, `restart.sh`, `run.sh`, `stop.sh`, `fragments/banner.html`, `index.html`, `progress.html`

### 변경 내용

- 버전 문자열 `2.1.1` → `2.1.2` 일괄 변경 (JAR 파일명 포함 7개 파일)

## [2026-06-19] Core Dump — 파일 형식 인식 실패 시 오류 카드 표시

**대상:** `src/main/java/com/heapdump/analyzer/service/CoreDumpAnalyzerService.java`, `src/main/resources/templates/core-dump/analyze.html`

### 변경 내용

- `parseGdbOutput()`: GDB 파싱 전 `"is not a core dump"` / `"file format not recognized"` / `"not a core file"` / `"No such file or directory"` 패턴 조기 감지 → `result.errorMessage` 설정 후 즉시 반환
- `analyze.html`: `result.crashSignal == null and result.errorMessage != null` 조건일 때 탭·메타 정보 영역 전체를 숨기고 전용 오류 카드(빨간 배경, 안내 문구, 목록으로/재분석 버튼) 표시. 시그널이 있는 경우의 기존 경고 배너는 유지.

## [2026-06-19] Core Dump 분석 결과 페이지 ERR_INCOMPLETE_CHUNKED_ENCODING 수정

**대상:** `src/main/resources/templates/core-dump/analyze.html`

### 변경 내용

- line 292: `th:text="${thread.backtrace != null} ? (#lists.size(thread.backtrace) + ' frames') : '0 frames'"` → Thymeleaf 3.1에서 `${condition} ? (expr)` 형식이 파싱 오류(`TemplateProcessingException`) 발생. 응답이 chunked로 이미 전송 중에 예외가 나면 종료 마커가 미전송되어 브라우저에서 `ERR_INCOMPLETE_CHUNKED_ENCODING`으로 표출됨.
- 수정: 조건·분기 전체를 단일 `${}` 안으로 이동 → `th:text="${thread.backtrace != null ? #lists.size(thread.backtrace) + ' frames' : '0 frames'}"`

## [2026-06-19] Core Dump GDB 파서 버그 수정 — 섹션 파싱 불가 + 공유 라이브러리 경로 오류

**대상:** `src/main/java/com/heapdump/analyzer/service/CoreDumpAnalyzerService.java`

### 변경 내용

1. **`(gdb)` 프롬프트 기반 섹션 감지 제거** — GDB `--batch` 모드는 `(gdb)` 프롬프트를 출력하지 않아 스택트레이스·레지스터·스레드·공유라이브러리가 모두 빈 배열로 파싱됨. `-ex "echo ===SECTION:xxx===\n"` 마커를 각 GDB 명령 앞에 삽입하고, 파서가 해당 마커로 섹션 전환을 감지하도록 변경.
2. **`set print limit 0` 제거** — 유효하지 않은 GDB 명령어(`Undefined set print command` 경고 발생). 제거.
3. **`SHAREDLIB_PATTERN` 정규식 대안 순서 수정** — `(Yes|No|Yes \(\*\))` 에서 `Yes` 가 `Yes (*)` 보다 먼저 매칭되어 `(*)` 가 경로(path) 필드로 잘못 파싱됨. → `(Yes(?:\s+\(\*\))?|No)` 로 변경, `symsRead` 에 `Yes (*)` 전체가 캡처되고 경로에 실제 라이브러리 경로 정상 표시.

## [2026-06-19] GDB 기반 Core Dump 분석 기능 추가 (PR #6)

**대상:** 신규 15개 파일

### 변경 내용

- `CoreDumpAnalyzerService` — GDB `--batch` 실행·파싱·결과 저장 (678줄)
- `CoreDumpApiController` — 업로드·SSE 분석 스트림·이력·삭제·재분석 REST API
- `CoreDumpViewController` — `/core-dump`, `/core-dump/progress/{f}`, `/core-dump/analyze/{f}` Thymeleaf 뷰
- `CoreDumpAnalysisEntity` + `CoreDumpAnalysisRepository` — `core_dump_analysis_history` DB 이력
- `HeapDumpConfig` 확장 — `coredump.directory` / `gdb.cli.path` / `coredump.timeout.minutes` 설정 + 기동 시 GDB PATH 검증
- `core-dump/index.html` — 코어파일+실행파일 드래그앤드롭 업로드, 분석 이력 테이블
- `core-dump/progress.html` — SSE 기반 GDB 실행 실시간 진행률
- `core-dump/analyze.html` — 시그널 배너·스택·스레드·레지스터·공유라이브러리·GDB raw 6탭
- `banner.html` — 사이드바 "코어 덤프" 메뉴 항목 추가

## [2026-06-16] Dominator Tree (Raw) 패널 표출 안되는 버그 수정

**대상:** `src/main/resources/static/js/analyze.js`

### 변경 내용

- `showPanel()`의 iframe lazy-load `iframeMap`에 `mat-domtree` 항목 누락 → "Dominator Tree (Raw)" 패널(`panel-mat-domtree`, iframe id=`matDomTreeIframe`) 클릭 시 `src` 속성이 끝까지 설정되지 않아 항상 빈 화면으로 표출됨
- `iframeMap`에 `'mat-domtree': 'matDomTreeIframe'` 추가 → 클릭 시 `data-src`(`/report/{filename}/mat-page/dominator_tree/index.html`)가 정상 적용되어 MAT Single Query 리포트 렌더링 확인

## [2026-06-16] Dominator Tree 로드 클래스 목록 — 25개 초기 표시 + 추가 조회 버튼

**대상:** `src/main/resources/static/js/analyze.js`, `src/main/resources/static/css/analyze.css`

### 변경 내용

- 초기 렌더 시 상위 **25개만 표시** (Retained 내림차순 기준)
- 25개 초과 시 **"추가 조회 (N개 더)"** 버튼 표시 → 클릭마다 25개씩 추가 (`clShowMore`)
- 정렬 헤더 클릭 시 전체 데이터 재정렬 후 첫 25개로 리셋, "추가 조회" 버튼 카운트 자동 갱신
- `_buildClRows(sorted, start, end)` / `_clSortArray(classes, col, dir)` 헬퍼 분리
- `_renderClassLoaderTable`에 `addr` 인자 추가 → 테이블 `data-addr` 속성 및 버튼에 전달
- `analyze.css`: `.cl-more-wrap` / `.cl-more-btn` 스타일 추가

---

## [2026-06-16] Dominator Tree 로드 클래스 목록 — Retained 기본 정렬 + 헤더 클릭 정렬

**대상:** `src/main/resources/static/js/analyze.js`, `src/main/resources/static/css/analyze.css`

### 변경 내용

- `_renderClassLoaderTable()`: 클래스 목록을 **Retained 내림차순**으로 기본 정렬하여 렌더
- 각 클래스 행에 `data-shallow` / `data-retained` 속성 추가 (raw 바이트 수치)
- Shallow / Retained 헤더 클릭 시 오름차순 ↔ 내림차순 토글 (`sortClTable()` 신규 추가)
  - 정렬 방향 아이콘(▼/▲) 헤더에 표시, 활성 헤더 보라색 강조
  - 정렬 전 열려 있는 인스턴스 서브행 자동 닫힘 처리
- `analyze.css`: `.cl-sort-th`, `.cl-sort-active`, `.cl-sort-icon` 스타일 추가

---

## [2026-06-15] Dominator Tree domOut.index 바이너리 파싱 연구 완료 + 구현 검증

**대상:** 코드 변경 없음 (연구/검증 완료)

### 연구 결론

`DOMTREE_CHILDREN_RESEARCH.md`에 기술된 구현 과제에 대한 Python 바이너리 검증 완료:

**1. classLoaderId=330648 정확성 확인 (이진 검색)**
- `wgdist_1_heapdump_20260326.idx.index` (LongIndexReader)를 Python으로 직접 파싱.
- `objectId=330648 → address=0x8216b620` (ContextLoader 정확히 일치).
- `objectId=311451 → address=0x81184668` (별개 객체 — RESEARCH.md의 "311451이 올바른 값" 주장은 오류).
- 결론: 앱이 사용 중인 classLoaderId=330648이 **정확한 값**이며 수정 불필요.

**2. domOut.index 파싱 알고리즘 검증 (oom-test 교차검증)**
- `oom-test.hprof`의 domOut.index 파싱 → `objectId=1635(0xfd780098)`: count=6.
- 동일 객체가 `oql "SELECT * FROM ... dominator_tree"` Query ZIP HTML 최상단에 표시됨 확인.
- ArrayIntCompressed bit-packing 알고리즘(varyingBits/trailingClearBits) 정확성 입증.

**3. ContextLoader의 Dominator Tree children = 0 (leaf 노드)**
- `objectId=330648`(ContextLoader @ 0x8216b620): domOut.index에서 count=0 → 직접 dominate하는 자식 없음.
- MAT GUI의 "Σ Total: 25 of 27,044 entries"는 Dominator Tree children이 아니라 `classloaderexplorerquery` 결과(로드된 클래스 + 인스턴스).
- 결론: 현재 OQL 기반 구현(classloaderexplorerquery → OQL `@classLoaderId = N`)이 MAT GUI 동작과 **동일한 뷰**를 제공하는 올바른 접근법.

**4. wgdist domOut 이상 (기존 덤프 파일 corruption)**
- `objectId=311451` domOut: count=1, children=[311451] (자기 자신).
- domIn 교차검증: domIn[292746]=311451 이나 domOut[311451] child=311451 → 논리 불일치.
- 원인: wgdist_1_heapdump_20260326.domOut.index 파일 내 pre-existing corruption. 코드 버그 아님.

---

## [2026-06-15] ClassLoader classLoaderId 파싱 버그 완전 수정 (extractClassLoaderIdNearAddress)

**대상:** `controller/HeapReportApiController.java`, `parser/MatReportParser.java`, `static/js/analyze.js`

### 문제 (2차 수정)
- Phase 1 OQL `SELECT s.@objectId FROM INSTANCEOF java.lang.ClassLoader s WHERE s.@objectAddress = {addrLong}` 방식이 MAT 1.16.1에서 작동하지 않음 (`InstanceImpl has no property dominatorId` 유사 에러 — 실제로는 결과 0건).
- classLoaderId 추출 실패 → `classes=0` 반환, 화면에 클래스가 조회되지 않음.

### 수정 (2차)
**`MatReportParser.java` 신규 메서드 2개 추가:**
- `extractClassLoaderIdNearAddress(File zip, String addrHex)`: `classloaderexplorerquery` HTML을 TR 단위로 파싱, `addrHex`가 포함된 TR에서만 `CL_ID_FROM_EXPLORER_PATTERN`으로 classLoaderId 추출 → 부모 ClassLoader ID 오추출 버그 완전 해소
- `extractTotalEntryCount(File zip)`: HTML의 `"Total: N of M entries"` 패턴에서 M(전체 수) 추출 → `DOM_TREE_TOTAL_PATTERN` 재사용

**`HeapReportApiController.java` `classLoaderClassesSse` 수정:**
- Step 1: `classloaderexplorerquery {address}` 실행 → `extractClassLoaderIdNearAddress(zip, addrHex)` 호출
- Step 2: `@classLoaderId = N` OQL로 Java 클래스 목록 조회 (cap 500)
- SSE 이벤트 payload: `{ classes: [...], total: N }` 구조로 변경

**`analyze.js` 수정:**
- `handleClSseEvent('classes', ...)`: `parsed.classes` / `parsed.total` 분리 처리
- `_clCache[addr]`: `{ classes, total }` 구조로 저장
- `_renderClassLoaderTable(classes, total)`: total 있으면 "N개 클래스 (전체 M개 중)" 표시

### 검증 결과 (Python 시뮬레이션)
- `oom-test.hprof` / `classloaderexplorerquery 0xffceaf10` 실행 → TR 내 `ffceaf10` 탐지 → `classLoaderId = 17496` 정확 추출 확인

## [2026-06-15] ClassLoader 클래스 OQL `SELECT *` 수정 (classes=0 버그 해소)

**대상:** `controller/HeapReportApiController.java`

### 문제
- `classLoaderId` 추출에 성공했음에도 `classes=0` 반환.
- 원인: Step 2 OQL이 `SELECT c FROM java.lang.Class c WHERE ...`였는데, MAT가 `c` 컬럼 **1개짜리** HTML 테이블을 생성 → `parseClassLoaderClassesZip`이 `cells.size() < needed(3)` 조건 실패로 모든 행 스킵.

### 수정
- `SELECT c FROM` → `SELECT * FROM` 변경.
- `SELECT *` 사용 시 MAT가 `Class Name / Shallow Heap / Retained Heap` 3컬럼 표준 테이블 생성 → 파싱 정상 동작.

---

## [2026-06-15] ClassLoader 로드 클래스 수 불일치 수정 (MAT GUI 기준으로 정확히 조회)

**대상:** `controller/HeapReportApiController.java`, `parser/MatReportParser.java`

### 문제
- `jeus.servlet.loader.ContextLoader`에서 MAT GUI는 25개 클래스를 보여주지만 앱은 500개를 반환.
- 원인: `classloaderexplorerquery` HTML 결과에는 **부모 ClassLoader 계층 전체**의 classLoaderId 링크가 포함되며, 부모의 classLoaderId가 HTML에서 먼저 등장해 `m.find()`가 잘못된 ID(330648)를 추출.
  - 330648 = 부모 ClassLoader의 objectId → OQL이 부모의 클래스(500개)를 반환

### 수정
**Step 1 교체** (`HeapReportApiController.java`):
- 기존: `classloaderexplorerquery` 실행 → HTML에서 첫 번째 classLoaderId 추출 (부모 ID 오추출)
- 신규: OQL `SELECT s.@objectId FROM INSTANCEOF java.lang.ClassLoader s WHERE s.@objectAddress = {addrLong}` 실행
  - 주소(`0x8216b620`)를 `Long.parseUnsignedLong(addrHex, 16)`으로 decimal long 변환
  - `INSTANCEOF java.lang.ClassLoader`로 스캔 범위 최소화 (빠름)
  - ClassLoader 자신의 MAT objectId를 직접 획득 → 부모 혼동 없음

**신규 파서 메서드** (`MatReportParser.java`):
- `extractObjectIdFromOqlZip(File zip)`: TR_PATTERN + TD_PATTERN으로 첫 번째 데이터 셀의 정수를 long으로 파싱
- 기존 `extractClassLoaderIdFromExplorerZip` + `CL_ID_FROM_EXPLORER_PATTERN`은 `@Deprecated` 보존

### 검증
- 앱 재기동 후 ContextLoader "목록 조회" → 로그에서 `classLoaderId={올바른ID} classes=25` 확인 예정
- 핵심 개념: `IClass.getClassLoaderId()` == 정의 ClassLoader의 `IObject.getObjectId()` 이므로 objectId로 조회하면 MAT GUI와 완전 동일한 결과

---

## [2026-06-15] ClassLoader 로드 클래스 → 인스턴스 조회 (Class 조회 기능 추가)

**대상:** `controller/HeapReportApiController.java`, `static/js/analyze.js`, `static/css/analyze.css`

### 추가 기능
- ClassLoader "목록 조회" 후 각 클래스 행을 클릭하면 해당 클래스의 힙 인스턴스 목록 인라인 조회 가능.
- 클릭 → `oql "SELECT * FROM {className}"` 실행(MAT CLI, 최대 90초) → 인스턴스 주소·Shallow/Retained Heap 서브 테이블 렌더.
- 클릭된 행에 `▶`/`▼` chevron + hover 하이라이트로 상태 시각화.
- 재클릭 시 서브 행 접힘(토글), 동일 클래스 재조회 시 클라이언트·서버 2계층 캐시 활용(즉시 반환).

### 신규 엔드포인트
- `GET /api/class-instances/{filename}?className=...` (SSE): `DOM_REF_CACHE(":inst:")` + `DOM_SEMAPHORES(2)` 재사용. `LoadedClassEntry` 최대 200건 반환.

### JS
- `_renderClassLoaderTable`: 각 행에 `cl-class-row` 클래스 + `onclick="toggleClassInstances(this)"` + `data-classname` 추가.
- 신규 `toggleClassInstances(row)`: 열기/닫기 토글 + SSE 스트리밍 처리.
- 신규 `_renderInstanceTable(instances)`: 인스턴스 서브 테이블 렌더러.
- 전역 `_classInstCache` / `_classInstAbortCtrls` 추가.

### CSS
- `.cl-class-row`, `.cl-chev`, `.cl-inst-row`, `.cl-inst-tbl`, `.cl-inst-addr`, `.cl-inst-count` 스타일 추가.

---

## [2026-06-15] ClassLoader 로드 클래스 목록 조회 버그 수정 (0건 → 정상 반환)

**대상:** `controller/HeapReportApiController.java`, `parser/MatReportParser.java`

### 문제
- MAT GUI에서는 `jeus.server.classloader.RootClassLoader`에 25건 로드된 클래스가 조회되나, 애플리케이션에서는 0건 반환.
- 원인 1: MAT OQL 문법 오류 — `WHERE c.@classLoaderAddress = 0x8002dc78` 에서 HEX 리터럴(`0x...`)이 OQL grammar에서 token으로 정의되어 있으나 expression으로 사용 불가 (정수 리터럴만 허용).
- 원인 2: `FROM java.lang.Class c`는 `InstanceImpl`(힙 Class 미러 객체)을 반환하는데 `@classLoaderAddress`는 `ClassImpl`(클래스 정의) 전용 속성 → `Type InstanceImpl has no property classLoaderAddress`.

### 수정 — 2단계 접근으로 교체
**Step 1** (`HeapReportApiController.java`): `org.eclipse.mat.inspections.classloaderexplorerquery 0x<addr>` 실행 → `MatReportParser.extractClassLoaderIdFromExplorerZip()` 로 MAT 내부 정수 classLoaderId 추출.

**Step 2** (`HeapReportApiController.java`): `oql "SELECT * FROM java.lang.Class c WHERE c implements org.eclipse.mat.snapshot.model.IClass and c.@classLoaderId = <id>"` 실행 → 기존 `parseClassLoaderClassesZip()` 파싱. cap 200 → 500 상향.

**신규 메서드** (`MatReportParser.java`): `extractClassLoaderIdFromExplorerZip(File zip)` — `CL_ID_FROM_EXPLORER_PATTERN`(`classLoaderId(?:\+%3D\+|\s*=\s*)(\d+)`)으로 URL-encoded / plain 두 형식 모두 파싱. 실패 시 -1 반환.

### 검증
- 실제 덤프 `wgdist_1_heapdump_20260326.hprof` / `RootClassLoader @ 0x8002dc78` → classLoaderId = 2636 → OQL 결과 6,905건 확인.

---

## [2026-06-15] Dominator Tree ClassLoader 로드 클래스 목록 조회 기능

**대상:** `model/DominatorTreeEntry.java`, `model/LoadedClassEntry.java`(신규), `parser/MatReportParser.java`, `controller/HeapReportApiController.java`, `templates/analyze.html`, `static/js/analyze.js`, `static/css/analyze.css`

### 핵심 변경
- **ClassLoader 자동 감지**: `MatReportParser.isClassLoaderClass(className)` — `classloader`/`contextloader` 포함 or 알려진 WAS 로더 prefix(jeus/weblogic/catalina/spring-boot)에 해당하면 `DominatorTreeEntry.classLoader = true` 설정. Dominator Tree 행에 보라색 `CL` 배지 표시.
- **신규 SSE 엔드포인트** `GET /api/classloader-classes/{filename}?address={0xADDR}`: MAT OQL `SELECT c FROM java.lang.Class c WHERE c.@classLoaderAddress = {address}` 실행 → `classes` SSE 이벤트로 `List<LoadedClassEntry>` 반환. DOM_REF_CACHE(키 suffix `:cl:`) + DOM_SEMAPHORES 재사용.
- **신규 모델 클래스** `LoadedClassEntry`: `className`, `objectAddress`, `shallowHeap`, `retainedHeap` + human-readable 포맷 메서드.
- **`parseClassLoaderClassesZip(File, int)`**: OQL 결과 ZIP 파싱. 헤더 행 동적 컬럼 감지 + `extractCleanClassName` 재사용. cap 200.

### Frontend 변경
- `analyze.html`: `th:data-is-loader="${d.classLoader}"` + `<span class="cl-badge">CL</span>` 추가.
- `analyze.js`: `_clCache`/`_clAbortCtrl` 추가, `_buildDomDetailLoadingHtml(isLoader)` 파라미터화, `_buildDomDetailHtml` ClassLoader 섹션 추가, `loadClassLoaderClasses(btn)` + `_renderClassLoaderTable(classes)` 신규 함수.
- `analyze.css`: `.cl-badge`, `.dom-cl-section`, `.dom-cl-load-btn` 스타일 추가.

---

## [2026-06-15] Dominator Tree 인바운드/아웃바운드 참조 SSE 스트리밍 + LRU 캐시 개편

**대상:** `controller/HeapReportApiController.java`, `static/js/analyze.js`

### 핵심 변경
- **SSE 스트리밍 전환** (`GET /api/dominator-refs/{filename}`): 기존 단일 JSON 블로킹 응답(`ResponseEntity<Map>`) → `SseEmitter` (TEXT_EVENT_STREAM). MAT `path2gc` 완료 즉시 `incoming` 이벤트 전송, `show_retained_set` 완료 후 `outgoing` 이벤트 전송 → 브라우저가 약 2–3초 간격으로 단계적으로 렌더링.
- **서버 측 LRU 캐시** (`DOM_REF_CACHE`, max 200): 동일 주소 재클릭 시 MAT 재실행 없이 캐시 결과를 즉시 두 이벤트로 반환.
- **동시 요청 제한** (`DOM_SEMAPHORES`, `Semaphore(2)` per filename): 같은 덤프 파일에 동시 MAT 쿼리를 최대 2개로 제한. 10초 내 획득 실패 시 `refs-error` 이벤트.
- **클린업 보장** (`AtomicBoolean cleaned`): 클라이언트 disconnect (`onTimeout`/`onError`) 와 daemon thread `finally` 블록 중 정확히 1회만 Semaphore 해제 + workDir 삭제.

### Frontend 변경 (analyze.js)
- **`_domAbortCtrl`** (AbortController): 다른 행 클릭 시 진행 중인 fetch 즉시 abort → 이전 요청 결과가 현재 열린 행에 표시되는 현상 차단.
- **`_closeDomDetail()`**: abort 로직 추가.
- **`_buildDomDetailLoadingHtml()`** 신규: incoming/outgoing 섹션을 `.dom-incoming-body` / `.dom-outgoing-body` 로 독립 구분 → SSE 이벤트별 개별 업데이트.
- **`toggleDomDetail()`**: `fetch` → `fetch + ReadableStream SSE 파서`로 교체. `incoming` 이벤트 도착 시 incoming 테이블 즉시 렌더, `outgoing` 이벤트 도착 시 outgoing 테이블 렌더. `AbortError` 는 정상 취소로 처리.
- **에러 이벤트**: 서버 측 오류는 `refs-error` 이름 SSE 이벤트로 전달 (`error` 네이밍은 EventSource 내장 오류와 충돌 방지).

---

## [2026-06-14] 지역변수 포함 스택트레이스 모달 파서 & 렌더링 고도화

**대상:** `static/js/analyze.js`, `static/css/analyze.css`, `templates/analyze.html`

### 핵심 변경
- **MAT 포맷 자동 감지**: `_parseMatStacktraceHtml()` 이 `<table class="result">` 여부로 두 포맷을 자동 분기.
  - `<pre>` 포맷 (`pages/25.html` — 일반 스택트레이스): `_parsePreStacktraceHtml()` — `<pre>` 요소 직접 파싱, 첫 줄에서 스레드명 추출.
  - `<table>` 포맷 (`pages/26.html` — 지역변수 포함): `_parseLocalVarsTableHtml()` — thead로 컬럼 인덱스 자동 감지, 스레드 헤더/스택 프레임/지역변수 객체 3종 행 구분.
- **`<Java Local>` 감지 로직**: `firstLink.textContent` 에서는 접두사가 소실되므로 `td.textContent`(entity 디코딩 포함) 의 `fullTdText` 로 감지 + `fullTdText.replace(/^[|+\s]+/)` 로 MAT 트리 접두사 제거.
- **신규 헬퍼 함수**: `_buildThreadHeader()`, `_fmtHeapBytes()`.

### 렌더링 개선
- **스레드 헤더 패널** (`.stm-thread-header`): 스레드명·State·Is Daemon·Context ClassLoader 표시. 좌측 `#818cf8` 보더로 구분.
- **지역변수 보유량 배지** (`.stm-local-heap`): 각 프레임 우측에 `_fmtHeapBytes()` 포맷 배지 표시 (`localHeap > 0` 인 경우만).
- **중요 프레임 하이라이트** (`.stm-significant`): MAT `class="selected"` 프레임을 주황색(`#f59e0b`) 좌측 보더 + 배경으로 강조. 사용자 코드는 `#fcd34d` 굵은 글씨.
- **중요 지역변수** (`.stm-local-sig`): MAT `class="selected"` 지역변수 행을 `#fbbf24` 굵은 글씨로 구분.
- **상태바 신규 항목** (`#stmLocalVarCount`): 지역변수 보유 프레임 수 표시 (주황색, 없으면 숨김).
- **복사 개선**: `copyStacktrace()` 가 스레드명을 첫 줄에 포함.
- **CSS 추가**: `stm-thread-header` / `stm-thread-name` / `stm-thread-meta` / `stm-thread-state` / `stm-thread-loader` / `stm-significant` / `stm-local-sig` / `stm-local-heap` / `stm-local-heap-sig` 9개 클래스.

---

## [2026-06-14] 스택트레이스 모달 범례 클릭 타입 필터 기능 추가

**대상:** `templates/analyze.html`, `static/js/analyze.js`, `static/css/analyze.css`

- 범례 항목(사용자 코드/프레임워크/JDK 내부/OOM/예외/지역변수) 클릭 시 해당 타입 프레임만 강조하고 나머지를 흐리게(opacity 0.18) 처리.
- 같은 항목 재클릭 시 필터 해제.
- 활성 범례 항목에 배경색 + 현재 색상 테두리 표시.
- 모달 열기/닫기 시 필터 자동 초기화.
- `_stmLegendActive` 변수, `stmFilterByType()`, `_stmClearLegendFilter()` 함수 추가.

---

## [2026-06-14] 스택트레이스 모달 범례 색상 가독성 개선

**대상:** `templates/analyze.html`

- `#stmLegend` 범례 5개 항목의 텍스트 색상을 어두운 배경 전용 파스텔 → 밝은 배경에서도 명확히 보이는 진한 색상으로 교체.
  - 사용자 코드: `#a7f3d0` → `#047857` (진한 에메랄드)
  - 프레임워크: `#93c5fd` → `#1d4ed8` (진한 블루)
  - JDK 내부: `#6b7280` → `#374151` (진한 그레이)
  - OOM/예외: `#fca5a5` → `#dc2626` (진한 레드)
  - 지역변수: `#fde68a` → `#b45309` (진한 앰버)
- `font-weight:600` 추가로 가독성 향상.

---

## [2026-06-14] 버전 2.1.0 → 2.1.1 업데이트

**대상:** `pom.xml`, `restart.sh`, `run.sh`, `stop.sh`, `templates/fragments/banner.html`, `templates/index.html`, `templates/progress.html`

- 전체 7개 파일의 버전 표기를 `2.1.0` → `2.1.1`로 일괄 변경.

---

## [2026-06-14] AI Chat 입력 영역 레이아웃 수정

**대상:** `templates/ai-chat.html`

1. **chat-input-area**: `align-items: flex-end` → `align-items: center` 변경 — 단일 줄 입력 시 첨부 버튼·textarea·전송 버튼 수직 중앙 정렬.
2. **chat-attach-btn**: `width/height 36px` → `40px` — 전송 버튼(40px)과 크기 통일.
3. **chat-textarea**: `padding: 12px 16px` → `9px 16px` — 자연 높이를 버튼 높이(40px)에 근접하게 조정.

---

## [2026-06-14] AI Chat 파일 첨부(Vision) 기능 추가

**대상:** `service/LlmConfigService.java`, `service/HeapDumpAnalyzerService.java`, `config/SecurityConfig.java`, `controller/HeapAiApiController.java`, `controller/HeapSystemApiController.java`, `controller/AiChatController.java`, `templates/llm-settings.html`, `templates/ai-chat.html`, `templates/analyze.html`, `static/js/analyze.js`, `static/css/analyze.css`, `resources/application.properties`

1. **LlmConfigService**: `llmFileAttachEnabled` volatile 필드 추가. `VISION_SUPPORTED_PROVIDERS` 상수(claude/gpt). `isFileAttachCapable()` — 토글 ON + (지원 provider 또는 genspark/custom) 조건. `callLlmChatStream(messages, systemPrompt, attachments, ...)` overload 추가. `buildMsgListWithAttachments()` — Claude는 `image.source.base64`, OpenAI 호환은 `image_url.url=data:...;base64,...` 형식 변환. 영속화(`applyFromSettings`/`collectSettings`/`collectApplicationProperties`) 추가.
2. **HeapDumpAnalyzerService**: `isLlmFileAttachEnabled()` / `setLlmFileAttachEnabled()` / `isFileAttachCapable()` / `callLlmChatStream(with attachments)` facade 추가.
3. **SecurityConfig**: `/api/llm/file-attach` 를 `authorizeHttpRequests` ADMIN POST 목록 + CSRF 보호 목록 양쪽에 추가.
4. **HeapAiApiController**: `POST /api/llm/file-attach?enabled=` 신규 엔드포인트 추가. `/api/llm/config` 응답에 `fileAttachEnabled`/`fileAttachCapable` 추가.
5. **HeapSystemApiController**: `GET /api/settings` 응답 `llm` 맵에 `fileAttachEnabled`/`fileAttachCapable` 추가.
6. **AiChatController.streamChat()**: body에서 `attachments` 파싱 → `FILE_ATTACH_DISABLED` / `FILE_ATTACH_UNSUPPORTED` / `FILE_TYPE_INVALID` / `FILE_TOO_LARGE` SSE error 검증. DB 저장 content에 `[이미지 첨부: name.jpg]` 텍스트만 기록(base64 미저장). `callLlmChatStream` attachments overload 호출.
7. **llm-settings.html**: "File Attachment (Vision)" 설정 카드 추가. provider 지원 배지(Claude/GPT 녹색, Genspark/Custom 노란색). `toggleFileAttach()` JS 함수 추가. `loadLlmSettings()`에서 `togFileAttach` 초기화.
8. **ai-chat.html**: 📎 첨부 버튼, 미리보기 바(`attach-preview-bar`) 추가. `_attachedFiles` 전역 변수. `onChatFileSelect()` / `renderAttachBar()` / `removeAttachment()` 함수. `sendMessage()` 에서 첨부 파일 복사 후 초기화 → LLM 전송. user 메시지 버블에 이미지 썸네일 렌더링. 에러코드별 한국어 메시지 처리.
9. **analyze.html + analyze.js**: 플로팅 FAB 채팅 패널에 동일 기능 추가. `_aiChatAttachments` 전역 변수. `onAnalyzeChatFileSelect()` / `renderAnalyzeAttachBar()` / `removeAiAttachment()` / `renderChatMessage(with attachments)`. `doStreamRequest(typing, attachments)` 파라미터 추가.
10. **application.properties**: `llm.file-attach.enabled=false` 추가(기본 비활성).
11. **analyze.css**: `.chat-attach-btn` / `.attach-preview-item` / `.attach-preview-remove` / `.chat-msg-image` CSS 추가.

---

## [2026-06-14] Target Servers 모바일 UX 추가 수정 2건

**대상:** `templates/servers.html`

1. **덤프 경로 우측 정렬 재수정**: `td[data-label="덤프 경로"] > span` 에 `display:flex !important` 추가 — 기존에 `justify-content:flex-end`만 있었으나 `inline-flex`라 너비를 채우지 못해 값이 가운데로 보이는 버그 → `display:flex`로 교체하여 flex 아이템 전체 너비 확보 후 우측 정렬
2. **팝오버 뷰포트 이탈 수정**: 터치 툴팁 rAF 내에서 `max-width`를 `window.innerWidth - 16` 으로 재설정 후 `offsetWidth` 측정 → x 확정 후에도 우측이 넘치는 경우 `maxWidth = winW - x - 8` 로 재클램핑 → 팝오버가 모바일 화면 왼쪽으로 삐져나가는 버그 수정

---

## [2026-06-14] Target Servers 모바일 UX 3건 수정

**대상:** `templates/servers.html`

1. **퍼블릭키 버튼 높이 통일**: `@media (max-width: 640px)` 에 `.btn-pubkey { padding: 7px 14px; font-size: 12px }` 추가 → "서버 추가" 버튼과 높이 일치
2. **덤프 경로 우측 정렬**: 640px 미디어쿼리에서 `td[data-label="덤프 경로"] > span` 에 `flex:1; min-width:0; max-width:none; justify-content:flex-end` 적용 → 가운데 정렬 버그 수정
3. **상태 배너 팝오버 외부 터치 즉시 닫기**: 툴팁 IIFE 리팩터
   - `_activeTipEl` 변수 추가 — 현재 팝오버 기준 요소 추적
   - `touchstart` 핸들러: 같은 요소 재터치 시 토글 닫기, 외부 터치 시 즉시 닫기
   - `touchend` 1800ms 자동 닫힘 제거 (기존 동작: 딜레이 중 외부 터치해도 안 닫힘)
   - `touchmove` 핸들러 신규 추가 — 스크롤 시 팝오버 즉시 닫기

---

## [2026-06-14] Target Servers — KRDS 툴팁 패턴 적용

**대상:** `templates/servers.html` / `templates/server-detail.html`

KRDS(Korea Design System) 툴팁 컨벤션으로 통일:
- `data-tip` attribute → `data-tooltip` (KRDS 표준)
- 툴팁 대상 요소에 `krds-tooltip tooltip-vertical` 클래스 추가
- 적용 대상: ⚠ 실패 배지, 덤프 경로 td (servers.html) / ⚠ 실패 배지 (server-detail.html)
- `updateStatusBadge()` JS도 동일하게 변경 — 동적 생성 배지도 KRDS 패턴 유지
- 툴팁 IIFE: `[data-tip]` → `[data-tooltip]` 셀렉터 업데이트 (mouseover/mouseout/touchstart 모두)
- 내부 구현(position:fixed 플로팅)은 부모 overflow 영향을 받지 않아 유지

---

## [2026-06-14] Target Servers 모바일 UX 버그 수정

**대상:** `templates/servers.html` / `templates/server-detail.html`

1. **모달 배경 스크롤 방지**: 모달 open 시 `document.body.style.overflow = 'hidden'`, close 시 복구
   - servers.html: `openCreateModal` / `openEditModal` / `openDeleteModal` / `openPubkeyModal` / `closeModal`
   - server-detail.html: `openEditModal` / `closeEditModal`

2. **덤프 경로 우측 정렬 수정**: td 내 두 `<span>` 요소를 wrapper `<span>`으로 묶어 단일 flex 아이템으로 처리 → `justify-content:space-between`이 올바르게 좌(라벨):우(값) 정렬

3. **덤프 경로 터치 툴팁**: 덤프 경로 td에 `data-tip` attribute 추가 (기존 `th:title`과 동일 값) — 모바일 터치 시 전체 경로 팝업

4. **실패 배너 툴팁 화면 초과 수정**: touchstart 핸들러에서 `tip.style.maxWidth = min(360, innerWidth-16)px` 동적 설정 + x 좌표 클램핑 개선 (우측/좌측 경계 각각 보정)

5. **스캔 결과 카드 하단 표시**: `scanServer()` 함수에서 `window.innerWidth <= 640` 시 `#scanPanel` DOM을 해당 서버 tr 다음 위치로 이동 (`insertBefore`) → 스캔 결과가 해당 카드 바로 아래 표시. 데스크탑은 기존 컨테이너 하단 위치 유지.

---

## [2026-06-14] Target Servers — 모바일 전면 개편

**대상:** `templates/servers.html` / `templates/server-detail.html`

### servers.html
- `@media (max-width:640px)` 블록 전면 교체 — 테이블 → 카드 변환
  - `.stable thead` 숨김, `tr` → 독립 카드 (border-radius:10px, box-shadow)
  - `td` → `flex` 라벨:값 레이아웃 (::before에 `data-label` 렌더)
  - `.col-name` (이름 컬럼): 카드 헤더 역할, font-weight:700, 하단 구분선
  - `.col-hide-sm` (SSH계정/덤프경로/자동탐지): 카드 모드에서 모두 표시
  - `.actions-cell`: 4버튼 1열 grid (1fr × 4)
  - FAIL 카드: `border-left:3px solid #DC2626` + 배경 `#FFF5F5`
- Toolbar: `@media (max-width:640px)`에서 라벨 숨김 + select 중앙 정렬
- 모달 → 바텀시트: `.modal-ov { align-items:flex-end }` + `.modal-box { border-radius:14px 14px 0 0, max-height:92vh }` + 버튼 풀폭
- `.modal-box.modal-wide { max-width:560px }` 클래스 추가 (pubkeyModal)
- Scan Panel: `flex-wrap:wrap` + `transfer-progress` overflow 수정
- 툴팁 IIFE: `touchstart` 이벤트 추가 (1.8초 후 자동 숨김) — 모바일 FAIL 에러 접근 지원
- Toast `top: 60px` → `56px`

### server-detail.html
- `@media (max-width:640px)` 블록 전면 교체 — 분석이력/전송이력 테이블 → 카드 변환
  - `.dtable thead` 숨김, `tr` → 독립 카드
  - `td` → `flex` 라벨:값 (::before에 `data-label` 렌더)
  - `.col-card-hdr` (상태 컬럼): 카드 헤더, 구분선 하단
  - `.col-hide-sm` 카드 모드에서 표시
  - `.td-err { max-width:none; white-space:normal }` — 에러 전문 노출
- `info-actions` 버튼: `flex-wrap:wrap` + 2열 배치 (`flex:1 1 calc(50% - 4px)`)
- Edit 모달 → 바텀시트: `align-items:flex-end !important` + inner div `border-radius:14px 14px 0 0 !important`
- `.topbar-status { flex-shrink:0 }` — 긴 서버명에도 배지 항상 표시
- `.topbar-title { white-space:nowrap; overflow:hidden; text-overflow:ellipsis }` — 긴 이름 말줄임
- 툴팁 IIFE: `touchstart` 이벤트 추가
- Toast `top: 64px` → `56px`

---

## [2026-06-14] Target Servers — 실패 상태 툴팁 개선

**대상:** `templates/servers.html` / `templates/server-detail.html`

- 펄스 애니메이션(`failPulse`) 제거
- `.fail-err` 인라인 에러 텍스트 제거
- `title` attribute 대신 `data-tip` attribute + 플로팅 JS 툴팁으로 변경
  - 마우스 위치 따라다니는 플로팅 div (position:fixed, z-index:9999)
  - 화면 경계 자동 보정 (우측/하단 넘침 방지)
  - 에러 전문 표시 (max-width:360px, word-break:break-word)
- `updateStatusBadge()` JS: `title` → `data-tip` 업데이트 → 테스트/스캔 시 툴팁 내용 즉시 반영

---

## [2026-06-14] Target Servers — 실패 상태 시각적 강조

**대상:** `templates/servers.html` / `templates/server-detail.html`

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| `.badge-fail` 배경 | 연한 빨간 `#FEE2E2` | 진한 빨간 `#DC2626` (흰 텍스트) |
| 배지 텍스트 | `실패` | `⚠ 실패` |
| 배지 효과 | 없음 | 펄스 애니메이션 (`failPulse` 2.5s) |
| 행 배경 (목록) | 기본 흰색 | `tr[data-status="FAIL"]` → 연한 빨간 `#FFF5F5` |
| 행 좌측 테두리 (목록) | 없음 | 3px solid `#DC2626` |
| 에러 메시지 표시 | tooltip(hover)만 | 배지 아래 `.fail-err` 텍스트로 최대 50자 인라인 표시 |
| `updateStatusBadge()` JS | 기존 패턴 유지 | ⚠ 아이콘 + 에러 메시지 인라인으로 동기화 |

---

## [2026-06-14] Comparison 파일 피커 — 서버명 검색 지원

**대상:** `templates/compare.html`

- 검색 입력창 placeholder `"파일명 검색…"` → `"파일명 또는 서버명 검색…"` 변경
- `applyPickerFilters()`: 파일명 불일치 시 서버명(`f.serverName`)도 함께 검사 (OR 조건)

---

## [2026-06-14] Comparison 피커 — /api/history 응답에 dumpCreationTime 누락 수정

**대상:** `HeapHistoryApiController.java` `getHistory()`

`getHistory()`가 `AnalysisHistoryItem`을 `Map`으로 수동 변환 시 `dumpCreationTime` 필드를 포함하지 않아 비교 페이지 피커에서 덤프일이 `—`로 표시되는 문제.

- `item.put("dumpCreationTime", h.getDumpCreationTime())` 한 줄 추가

---

## [2026-06-14] Comparison 피커 — 기존 분석 기록 dump_creation_time 백필

**대상:** `HeapDumpAnalyzerService.java`

기존 분석 기록들이 `dump_creation_time`이 DB에 NULL로 남아 비교 페이지 피커에서 덤프일이 표시되지 않는 문제 해결.

- `backfillDumpCreationTimeToDb()` 메서드 추가
- `@PostConstruct restoreResultsFromDisk()` 마지막 단계로 실행
- `sanitizeCachedHtml()` → `reparseOverviewMeta()` 경로로 캐시에 이미 채워진 `dumpCreationTime`을 DB에 UPDATE
- DB `dump_creation_time IS NULL AND status = 'SUCCESS'` 레코드 대상으로 한 번만 실행 (이후 신규 분석은 `saveAnalysisToDb()`에서 자동 저장)
- 앱 기동 시 자동 실행, 로그: `[DB Backfill] dump_creation_time N 건 백필 완료`

---

## [2026-06-14] Comparison 피커 — 힙덤프 생성 시각(Heap Date) 표시 + 날짜 역전 경고

**대상:** `AnalysisHistoryEntity.java` / `HeapDumpAnalyzerService.java` / `AnalysisHistoryItem.java` / `HeapHistoryAggregator.java` / `compare.html` + DB DDL

**변경 내용:**

1. **DB**: `analysis_history` 테이블에 `dump_creation_time VARCHAR(50) NULL` 컬럼 추가
2. **`AnalysisHistoryEntity`**: `dumpCreationTime` 필드 + `@Column(name="dump_creation_time", length=50)` 추가
3. **`HeapDumpAnalyzerService.saveAnalysisToDb()`**: `result.getDumpCreationTime() != null` 조건으로 엔티티에 저장
4. **`AnalysisHistoryItem`**: `dumpCreationTime` 필드 + getter/setter 추가
5. **`HeapHistoryAggregator.buildHistory()`**: `item.setDumpCreationTime(e.getDumpCreationTime())` 매핑 추가
6. **`compare.html`**:
   - 피커 카드 메타 행에 `덤프일` 행 추가 (ID: `cmpBaseMeta-heapdate`, `cmpTargetMeta-heapdate`)
   - 기존 `DATE` → `분석일` (분석 완료 시각), 신규 `덤프일` = 힙덤프 생성 시각
   - `pickFile()`: `dumpCreationTime` 파라미터 전달
   - `applyCardSelection()`: `heapDate` 파라미터 수신 + `_baseHeapDate`/`_targetHeapDate` 전역 변수 갱신
   - `parseDumpDate()` 헬퍼: `"YYYY-MM-DD HH:mm:ss"` → `Date` 변환
   - `updateDateWarnBox()`: Before 덤프일 > After 덤프일 시 피커 아래 노란 경고 박스 표시
   - `runCompare()`: 날짜 역전 감지 시 확인 모달 표시, `doRunCompare()`로 실행 분리
   - 날짜 역전 경고 모달(`cmpDateOrderModal`): 취소 / 그래도 실행 버튼

---

## [2026-06-14] 로컬 퍼블릭키 조회 예외 처리 및 로깅 보강

**대상:** `ServerController.java` `getLocalPubkey()`

| # | 문제 | 수정 |
|---|------|------|
| 1 | `catch` 블록에 로그 없음 + `e.getMessage()` null 가능 | `logger.warn("[LocalPubkey] …", localUser, errMsg, e)` + null guard |
| 2 | `ProcessBuilder.waitFor` 타임아웃 시 프로세스 누수 | 반환값 확인 → `false` 이면 `p.destroy()` + warn 로그 후 즉시 반환 |
| 3 | 유효하지 않은 `user.name` 분기에 로그 없음 | `logger.warn` 추가 |
| 4 | 홈 디렉토리 확인 실패 분기에 로그 없음 | `logger.warn` (실제 output 값 포함) 추가 |
| 5 | 키 파일 미발견 분기에 로그 없음 | `logger.warn` (탐색한 sshDir 포함) 추가 |
| 6 | 성공 분기에 로그 없음 | `logger.debug` (user + keyFile 경로) 추가 |

모든 로그 prefix: `[LocalPubkey]`

---

## [2026-06-14] Analysis Queue 예외 표시 및 로깅 보강

**검토에서 발견된 3개 문제 수정:**

**[높음] `index.html` — Queue 패널 fetch 오류 무음 처리 수정**
- `_queueFetchErrors` 카운터 + `_QUEUE_MAX_ERRORS = 3` 추가
- `refreshQueueStatus` / `startQueuePolling` 내 `.catch(function(){})` → `!r.ok` 포함 오류 감지로 교체
- 연속 3회 실패 시: `renderQueueFetchError()` 호출 → 패널에 빨간 ⚠ + "큐 상태를 확인할 수 없습니다" 표시 + 폴링 중지
- `console.warn('[Queue] …')` 로 브라우저 콘솔 기록
- 수동 새로고침 버튼 클릭 시 카운터 리셋 (재시도 기회)

**[중간] `HeapDumpAnalyzerService.java` — `e.getMessage()` null 전파 방지**
- `catch (Exception e)` 블록에서 null 메시지를 `e.getClass().getSimpleName() + " (no message)"` 로 대체
- `sendProgress(AnalysisProgress.error(…))` 및 `errorResult.setErrorMessage(…)` 양쪽에 적용
- `logEx.getMessage()` / `saveEx.getMessage()` null guard 동일 패턴 적용

**[중간] `progress.html` — SSE 연결 끊김 후 HEAD 요청 실패 시 사용자 안내 추가**
- `evtSource.onerror` 내 HEAD `.catch(() => {})` → 에러 배너 표시 + `console.warn` 로 교체
- 메시지: "SSE 연결이 끊겼습니다. 분석이 서버에서 계속 실행 중일 수 있습니다. 잠시 후 새로고침하거나 재분석 버튼을 눌러주세요."

---

## [2026-06-14] 서버 등록 화면 — 로컬 퍼블릭키 조회 버튼 추가

**목적:** SSH 연결 설정 시, 원격 서버 `authorized_keys`에 등록해야 할 로컬 계정 퍼블릭키를 UI에서 바로 확인·복사.

**구현:**
- `ServerController.java` — `GET /api/servers/local-pubkey` 추가
  - **앱 기동 OS 계정** 기준(`System.getProperty("user.name")`) — root 환경은 root, gbhda 환경은 gbhda 자동 반영
  - `bash -c "echo ~{user}"` 로 홈 디렉토리 확인 (shell injection 방지: `^[a-zA-Z0-9_-]+$` 검증)
  - 탐색 순서: `id_ed25519.pub` → `id_rsa.pub` → `id_ecdsa.pub` → `id_dsa.pub`
  - 응답: `{found, localUser, pubkey, keyFile}` or `{found:false, message}`
- `templates/servers.html`
  - 서버 추가/수정 모달 내부 퍼블릭키 UI 제거
  - page-hdr "서버 추가" 버튼 왼쪽에 "🔑 로컬 퍼블릭키" 버튼 추가
  - 클릭 시 전용 모달 오픈 → 실행 계정명·파일 경로·퍼블릭키 텍스트 + "복사" 버튼 표시
  - "복사": `navigator.clipboard` 우선, 폴백 `execCommand('copy')`

---

## [2026-06-14] 버전 2.0.8 → 2.1.0 업데이트

- `pom.xml` — `<version>2.1.0</version>`
- `restart.sh` / `run.sh` / `stop.sh` — JAR 경로 `heap-analyzer-2.1.0.jar`
- `fragments/banner.html` — `v2.1.0 · MAT CLI`
- `templates/index.html` — `v2.1.0 · MAT CLI Edition`
- `templates/progress.html` — `v2.1.0`

---

## [2026-06-14] 심각도 색상 최종 조정 (High→노랑, Medium→초록)

**최종 확정 팔레트:**
| 단계 | 색상 |
|------|------|
| Critical | 빨강 `#DC2626` |
| High     | 노랑 `#CA8A04` |
| Medium   | 초록 `#16A34A` |
| Low      | 파랑 `#2563EB` |

**적용 파일:** `templates/history.html`, `static/js/analyze.js`, `templates/analyze-print.html`

---

## [2026-06-13] 심각도 색상 CVD(적녹색약) 접근성 개선

**목적:** Low를 녹색에서 파란색으로 교체. High→노랑, Medium→초록은 위 후속 조정에서 확정.

---

## [2026-06-13] UI 개선 4건

**1. Dump Time 글자 크기 조정**
- `static/css/analyze.css` — `.stat-dumptime`의 `font-size:11px !important` 제거. 부모 `.stat-val`의 `12px`를 그대로 상속.

**2. Compare 모바일 돌아가기 버튼 크기 축소**
- `templates/compare.html` — `@media (max-width: 600px)` 내 `.back-btn { min-height:44px; display:inline-flex; align-items:center }` → `{ font-size:12px; padding:5px 10px }` 로 교체. 모바일에서 버튼이 과도하게 커지던 현상 해소.

**3. 대시보드 다운로드 모달 한글화**
- `templates/index.html` — 제목 "Download File" → "파일 다운로드", 설명/레이블(Filename, File Size, Cancel, Download) 전부 한글로 변환.

**4. 다운로드 모달 — 원본/GZ 용량 동시 표시 (Dashboard · Files · Analysis)**
- 압축 파일 다운로드 시 "원본 크기"와 "GZ 크기" 두 행 표시. 비압축 파일은 기존대로 "파일 크기" 한 행만 표시.
- `model/HeapDumpFile.java` — `getFormattedOriginalSize()` / `getFormattedCompressedSize()` 메서드 이미 존재, 활용.
- `model/HeapAnalysisResult.java` — `getFormattedOriginalFileSize()` 메서드 추가.
- `templates/index.html` — 다운로드 버튼에 `data-orig`, `data-gz` 속성 추가. `showDownloadModal(filename, size, origSize, gzSize)` 함수 확장. 모달 HTML에 원본/GZ 행(기본 hidden) 추가.
- `templates/files.html` — 동일 패턴 적용 (버튼 속성 + JS + 모달 HTML).
- `templates/analyze.html` — `result.originalFileSize > result.fileSize`(압축) 조건으로 "원본 크기" / "GZ 크기" 분기 표시.

---

## [2026-06-13] Analysis 페이지 Heap Statistics — Dump Time 표시

**목적:** Analysis 페이지 Heap Statistics 섹션에 힙 덤프가 JVM에서 최초 생성된 시각(분석/업로드 시각 아님)을 표시.

**파싱 소스:** MAT System Overview ZIP (`index.html`)의 `<td>Date</td>` + `<td>Time</td>` TD 쌍.  
예) `"2026. 5. 29."` + `"오후 6시 18분 53초 GMT+9"` → `"2026-05-29 18:18:53"` (오전/오후 24h 변환).

**백엔드:**
- **`model/MatParseResult.java`**: `dumpDate` / `dumpTime` String 필드 추가.
- **`parser/MatReportParser.java`**: `parseOverviewZip()` KV 루프에 `date`/`time` 키 처리 추가. `reparseOverviewMeta()` 에도 동일 파싱 추가 (기존 result.json 소급 적용용).
- **`model/HeapAnalysisResult.java`**: `dumpCreationTime` String 필드 추가 (result.json 영속, 없으면 null).
- **`service/HeapDumpAnalyzerService.java`**: `buildAnalysisResult()`에서 `parseDumpCreationTime(date, time)` static 헬퍼 호출하여 매핑. 한국어 로케일 `오전/오후` 파싱 → 24h `HH:mm:ss` + `yyyy-MM-dd` 조합. 기존 result.json 로드 시 `dumpCreationTime == null` 조건으로 `reparseOverviewMeta()` 소급 실행.

**프런트엔드:**
- **`templates/analyze.html`**: Heap Statistics 최상단에 `Dump Time` 행 추가 (`th:if="${result.dumpCreationTime != null}"`).
- **`static/css/analyze.css`**: `.stat-dumptime` — `font-size:11px`, `color:var(--text-secondary)`. 기존 stat 항목과 시각적 구분.

---

## [2026-06-13] 비활성화 계정 로그인 처리 개선

**목적:** 비활성화된 계정 접속 시도 시 웹 페이지에 전용 안내 메시지 표시 + 접속 이력에 "비활성화된 계정" 사유 기록.

**백엔드:**
- **`service/CustomUserDetailsService.java`**: `!user.isEnabled()` 시 직접 throw하던 로직 제거. `UserDetails` 생성 시 `enabled` 필드를 그대로 반환 → Spring Security 내장 `preAuthenticationChecks`가 `DisabledException`을 정상 발생시켜 `AbstractAuthenticationFailureEvent`로 전파.
- **`config/SecurityConfig.java`**: `.failureUrl("/login?error=true")` 대신 커스텀 `failureHandler` 사용. `DisabledException` → `/login?error=disabled`, 그 외 → `/login?error=true` 분기 리다이렉트.
- **`controller/AuthController.java`**: `error=disabled` 파라미터 처리 추가 → `disabledMessage` 모델 어트리뷰트 설정.
- **`listener/AuthEventListener.java`**: `onFailure()`에서 예외가 `DisabledException`이면 `failure_reason`을 `"비활성화된 계정"`으로 기록.

**프런트엔드 (`templates/login.html`):**
- `.msg-disabled` CSS 스타일 추가 (주황 계열: `#FFF7ED` / `#FED7AA` / `#C2410C`). 일반 오류(빨강)·로그아웃(파랑)과 시각적으로 구분.
- `th:if="${disabledMessage}"` div 추가 → "비활성화된 계정입니다. 관리자에게 문의하세요." 표시.
- `history.replaceState` 스크립트는 기존 `indexOf('error') >= 0` 조건으로 `?error=disabled`도 자동 처리 (F5 시 메시지 잔존 방지).

---

## [2026-06-13] Settings Max upload size 소수점 제거

**대상 파일:**
- **`util/FormatUtils.java`**: GB 포맷에서 후행 소수점·0 제거. `String.format("%.2f", gb).replaceAll("\\.?0+$", "")` 적용 — "10.00 GB" → "10 GB", "2.50 GB" → "2.5 GB".
- **`templates/settings.html`** (`formatBytesJS`): JS GB 포맷 동일 처리. `.toFixed(2).replace(/\.?0+$/, '')` 적용. Settings 페이지에서 10 GB 이상 선택 시 "10.00GB" 개행 현상 해소.

---

## [2026-06-13] Comparison 에러 처리 보강

**백엔드:**
- **`HeapDumpViewController.compareDumps()`**: diff 빌더 4종(buildClassDiffs/buildHistogramDiffs/buildSuspectDiffs/buildKpiDiff) 호출부에 try-catch 추가. 예외 발생 시 `logger.error()` 기록 + `model.addAttribute("error", ...)` → compare.html 에러 박스 표시. 기존에는 예외 시 HTTP 500이 반환되고 사용자에게 피드백 없었음.
- **`HeapHistoryApiController.compareData()`**: 동일 4종 빌더 호출부에 try-catch 추가. 예외 발생 시 `logger.error()` 기록 + `{ success: false, error: "..." }` JSON으로 HTTP 500 응답. 프런트엔드 `.catch()` 경로가 명확한 에러 메시지를 받아 모달에 표시 가능.

**프런트엔드 (`compare.html`):**
- **LLM 분석 fetch**: `/api/llm/compare/analyze` 응답에 HTTP 상태 체크 추가 (`if (!r.ok) throw new Error('HTTP ' + r.status)`). 기존에는 서버 500 응답을 `.json()` 파싱 시도 후 `.catch()`로만 처리.
- **AI 인사이트 조회 fetch** (`cmpAiLoadSaved`): `/api/llm/compare/insight` 응답에 HTTP 상태 체크 추가.
- **AI 인사이트 삭제**: `alert()` 대신 `cmpAiSummarySet('error', { message: ... })`로 인라인 표시. 기존 `window.alert()` 제거.

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapHistoryApiController.java`
- `src/main/resources/templates/compare.html`

---

## [2026-06-13] Comparison 파일 선택 UI 고도화 (모달 기반 파일 피커)

**배경:** `/compare` 페이지에서 분석 완료 파일이 많아지면 `<select>` 드롭다운으로 파일을 찾기 불편. 서버명 표시 누락(API에 `serverName` 미포함) 문제도 함께 수정.

**개선 내용:**
- **모달 기반 파일 선택기**: Before/After 카드의 `<select>` 드롭다운 → "📁 파일을 선택하세요…" 버튼으로 교체. 클릭 시 Before/After 공용 파일 선택 모달이 열림.
- **모달 기능**: 파일명 실시간 검색, 서버 필터 드롭다운(serverName별), 정렬(최신순/오래된순/Heap 큰순/파일명순), 파일 목록 테이블(파일명/서버/날짜/Heap/의심건수).
- **반대쪽 선택 표시**: After에서 이미 선택한 파일은 Before 모달에서 회색 배지 "[After에서 선택]"으로 표시 + 클릭 불가.
- **메타 정보 추가**: 카드에 HEAP/DATE/SERVER 외 **SUSPECT(의심건수)** 항목 추가. 선택 즉시 갱신.
- **"변경" 버튼**: 파일 선택 완료 후 카드 헤드에 변경 버튼 표시.
- **API 수정**: `/api/history` 응답에 `serverName` 및 `heapUsedBytes`(Heap 크기순 정렬용) 필드 추가. 기존 `serverName`이 응답에 누락되어 SERVER 필드가 항상 `—`였던 버그 수정.

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/controller/HeapHistoryApiController.java` — `/api/history` 응답 필드 추가
- `src/main/resources/templates/compare.html` — Picker CSS/HTML/JS 전체 교체 + 모달 추가

---

## [2026-06-13] 세션 타임아웃 설정 + 대시보드 Detections 기간 설정 추가

**기능 1 — Session Timeout 설정 (1~6시간):**
- **점검**: `server.servlet.session.timeout=60m` + `spring.session.jdbc.cleanup-cron=0 */10 * * * *` 기존 설정은 올바르게 구성됨. 다만 런타임 변경 시 `JdbcIndexedSessionRepository.setDefaultMaxInactiveInterval()` 병행 호출 필요 — 이번에 추가.
- **`HeapDumpAnalyzerService`**: `volatile int sessionTimeoutHours = 1` 필드 + getter/setter. `loadPersistedSettings()` / `persistSettings()` / `syncApplicationProperties()` 에 `sessionTimeoutHours` / `server.servlet.session.timeout` 동기화 추가.
- **`HeapSystemApiController`**: `JdbcIndexedSessionRepository` 생성자 주입. `POST /api/settings/session-timeout?hours=N` — 서비스 setter + 세션 저장소 즉시 갱신.
- **`settings.html`**: "Session timeout" 드롭다운 (1~6시간) 추가. 선택 즉시 저장.

**기능 2 — 대시보드 Detections 기간 설정 (7/14/30/60/90일):**
- **`HeapDumpAnalyzerService`**: `volatile int dashboardDetectDays = 14` 필드 + getter/setter. `persistSettings()` / `loadPersistedSettings()` 에 `dashboardDetectDays` 키 추가.
- **`HeapSystemApiController`**: `POST /api/settings/dashboard-detect-days?days=N` 엔드포인트 추가.
- **`HeapDumpViewController`**: `aggregateDetections(history, 14, 12)` → `aggregateDetections(history, dashDays, 12)`. `dashboardDetectDays` 모델 전달.
- **`index.html`**: 패널 제목 / KPI 라벨 / 점유율 문구 / 빈 차트 문구 동적 기간 표시. `DASHBOARD_DETECT_DAYS` JS 변수 주입.
- **`settings.html`**: "Dashboard Detections 기간" 드롭다운 (7/14/30/60/90일) 추가. 선택 즉시 저장.

**영속화:** `settings.json` + `application.properties` 모두 동기화. 재시작 후 복원.

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapSystemApiController.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- `src/main/resources/templates/settings.html`
- `src/main/resources/templates/index.html`

---

## [2026-06-13] 최근 탐지 결과 / TOP SERVERS 중복 표시 버그 수정

**버그:** `/history` 페이지에서 Suspect가 2개인 파일 2개를 분석하면, "최근 탐지 결과"에 파일당 2줄씩 4건이 표시되고 "TOP SERVERS" 카운트도 suspects 수(4)로 집계되는 문제.

**원인:** `HeapHistoryAggregator.aggregateDetections()`에서 `DetectionRecentItem`을 `LeakSuspect` 반복문 **안에서** 생성해 파일 1개당 suspects 수만큼 행을 만들었음. 서버/일별 카운터도 suspects 합계로 누적.

**수정:**
- **`HeapHistoryAggregator.aggregateDetections()`**: `DetectionRecentItem` 생성을 LeakSuspect 루프 밖으로 이동 — 파일 1개당 1건 생성, 최대 severity suspect 대표 표시. 카운터(`dailyTotals`/`dailyServerBuckets`/`serverTotals`) 모두 `+1` 파일 단위로 변경. `dailySeverityBuckets`도 파일 최대 severity 기준으로 변경.
- **`DetectionRecentItem`**: `suspectCount` 필드 추가 — 파일 내 총 suspects 수를 배지로 표시.
- **`history.html`**: "의심 누적 건수" → "탐지 파일 건수". suspects > 1인 경우 "의심 N건" 배지 표시(Thymeleaf + JS).
- **`index.html`**: 대시보드 14d Total KPI 라벨 동일하게 변경.

**결과:**
- "최근 탐지 결과": 파일 1건당 1행 (suspects 2개 이상이면 "의심 N건" 배지)
- "TOP SERVERS": 파일 수 기준 집계 (suspects 수 아님)
- 차트 클릭 드릴다운: 기존 동작 유지 (파일 단위, 변경 없음)
- 대시보드 "총 N suspects": 유지 (변경 없음)

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/service/HeapHistoryAggregator.java`
- `src/main/java/com/heapdump/analyzer/model/dto/DetectionRecentItem.java`
- `src/main/resources/templates/history.html`
- `src/main/resources/templates/index.html`

---

## [2026-06-13] Observer 모드 — "이미 분석 중" 화면 실시간 진행 상황 표시

**기능:** `ALREADY_ANALYZING` 상태 수신 시 진행 카드(타임라인·진행바)와 MAT CLI 로그 카드를 실시간으로 채워 Observer UX 개선.

**구현:**
- **`HeapDumpAnalyzerService`**: `lastProgressCache`(ConcurrentHashMap<파일명, AnalysisProgress>) + `logCache`(ConcurrentHashMap<파일명, ConcurrentLinkedDeque<String>>, 최대 500줄) 추가. `sendProgress()` 내 캐시 업데이트 — ALREADY_ANALYZING 제외. 세마포어 획득 직후 `logCache` 리셋(새 분석 시작). `getLastProgress()` / `getRecentLogs()` 공개 getter 추가.
- **`HeapAnalysisApiController`**: `GET /api/analyze/live-snapshot/{filename}` — `{ inProgress, progress, logLines }` 반환.
- **`progress.html`**:
  - `.observer-badge` ("👁 관찰 중") + `.log-live-chip` ("LIVE") CSS/HTML 추가.
  - `_applyObserverSteps()` 헬퍼 함수 (SSE 핸들러의 percent/reportPhase 기반 setStep 로직 추출).
  - `startAlreadyPolling()` 교체: 2초 주기 `/api/analyze/live-snapshot` 폴링 → `setProgress()` + `_applyObserverSteps()` + `appendLog()`로 실시간 갱신. 로그 커서(`_observerLogCursor`)로 중복 방지. 완료 시 `showComplete()` 호출.

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapAnalysisApiController.java`
- `src/main/resources/templates/progress.html`

---

## [2026-06-13] 동일 파일 중복 분석 방지 (ALREADY_ANALYZING)

**기능:** 파일 A가 이미 분석 중일 때 다른 사용자가 동일한 파일 A 분석을 요청하면, 대기열에 넣지 않고 즉시 "이미 분석 중" 상태를 반환. 분석 완료 시 자동으로 결과 페이지로 이동.

**구현:**
- **`AnalysisProgress.Status`**: `ALREADY_ANALYZING` enum 값 추가 + `alreadyAnalyzing(filename)` 팩토리 메서드.
- **`HeapDumpAnalyzerService.analyzeWithProgress()`**: `activeTasks.get(safe)` 가 not-done Future 이면 `queueSize` 증가 없이 ALREADY_ANALYZING SSE 이벤트를 즉시 전송하고 `null` 반환 (기존 태스크 취소 방지).
- **`HeapDumpAnalyzerService.isInProgress()`**: public 메서드 추가 — 폴링 엔드포인트용.
- **`HeapAnalysisApiController`**: `GET /api/analyze/in-progress/{filename}` 추가 — `{ inProgress: bool, currentAnalysis: string }` 반환.
- **`progress.html`**:
  - `.already-banner` CSS + HTML 추가 (teal 그라디언트, queue-banner 동일 스타일).
  - SSE 핸들러에서 `ALREADY_ANALYZING` 수신 시 evtSource 즉시 닫기, 배너 표시, 취소 버튼 숨김, 페이지 타이틀 변경.
  - `startAlreadyPolling()`: 4초마다 `/api/analyze/in-progress/{filename}` 폴링 → `inProgress: false` 수신 시 `showComplete()` 호출.

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/model/AnalysisProgress.java`
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapAnalysisApiController.java`
- `src/main/resources/templates/progress.html`

---

## [2026-06-13] AI Chat 스트리밍 렌더링 성능 개선 (rAF 스로틀 + 스마트 스크롤)

**문제:** AI 응답이 길어질수록 스트리밍 중 렉이 심화됨. SSE `chunk` 이벤트가 초당 수십~수백 회 발생할 때마다 `renderChatMarkdown(fullText)` (누적 전체 텍스트 정규식 처리) → `bubble.innerHTML` 전체 교체 → `scrollTop = scrollHeight` 강제 레이아웃 재계산이 반복되어 응답 후반부로 갈수록 지수적으로 무거워졌음.

**개선:**
- **`requestAnimationFrame` 스로틀**: 청크가 아무리 빠르게 도착해도 프레임당 최대 1회만 DOM 갱신. 새 청크가 올 때 이미 rAF가 예약돼 있으면 텍스트만 덮어쓰고 DOM 조작은 건너뜀.
- **스마트 자동 스크롤** (`_smartScrollChat` / `_chatSmartScroll`): `scrollHeight − scrollTop − clientHeight < 120px` 조건이 참일 때만 스크롤. 사용자가 위로 스크롤해 이전 내용을 읽는 중이면 강제 이동하지 않음.
- **`finishStream` / `finish` 최종 렌더**: 스트림 종료 시 미처리 rAF를 `cancelAnimationFrame`으로 취소하고 커서 없는 최종 마크다운 렌더를 직접 수행 (중복 갱신 방지).
- 같은 패턴을 **`ai-chat.html`** 인라인 스트리밍 코드에도 동일하게 적용.

**대상 파일:**
- `src/main/resources/static/js/analyze.js`
- `src/main/resources/templates/ai-chat.html`

---

## [2026-06-13] 분석 페이지 섹션별 로딩 스피너 추가

**개선:** 페이지 로딩 완료 후 트리맵·차트·AI 인사이트 요약 카드 등 세부 섹션이 렌더링/데이터 로딩 중일 때 스피너를 표시.
- **트리맵·Stacked Bar**: HTML에 `sec-loader-center` 스피너 삽입 → `buildTreemap()` / `buildStackedBar()` 가 `container.innerHTML = ''` 시 자동 제거.
- **파이 차트·바 차트**: `chart-loader-overlay` 오버레이를 canvas 위에 배치 → `new Chart()` 직후 JS에서 `display:none`.
- **`initCharts()` IIFE**: `requestAnimationFrame`으로 감싸 브라우저가 스피너를 먼저 페인트한 뒤 차트 초기화.
- **AI 인사이트 요약 카드** (`#ovAiInsightCard`): `ovAiInsightLoading` 스피너를 기본 표시, Empty 상태는 숨김. `initAiPanel()` fetch 완료 후 로딩 스피너를 숨기고 실제 상태로 전환.

**대상 파일:**
- `src/main/resources/static/css/analyze.css`
- `src/main/resources/templates/analyze.html`
- `src/main/resources/static/js/analyze.js`

---

## [2026-06-12] AI Chat — 분석 이력 삭제 모달에 "AI Chat 기록도 함께 삭제" 옵션 추가

**내용:** 분석 이력(History 페이지) 단건/일괄 삭제 모달에 "AI Chat 기록도 함께 삭제" 체크박스를 추가. 체크 시 해당 파일에 연결된 `ai_chat_sessions` 및 `ai_chat_messages` 행을 함께 삭제. 미체크 시 AI 채팅 데이터는 보존 (기존 동작 유지).

- **`history.html`** — 단건 삭제 모달(`#deleteModal`)과 일괄 삭제 모달(`#bulkDeleteModal`) 양쪽에 `#deleteAiChatChk` / `#bulkDelAiChatChk` 체크박스 추가. `openDeleteModal()` 호출 시 체크박스 초기화. `submitDelete()`·`submitBulkDelete()`에서 체크 상태를 요청 파라미터에 반영.
- **`HeapDumpViewController.deleteHistory()`** — `deleteAiChat` `@RequestParam` 추가 (기본값 false).
- **`HeapHistoryApiController.bulkDeleteHistory()`** — JSON body에서 `deleteAiChat` 파싱, 서비스에 전달.
- **`HeapDumpAnalyzerService.deleteHistory()`** — 시그니처에 `boolean deleteAiChat` 추가, `if (deleteAiChat)` 조건부로 AI 채팅 데이터 삭제.

**대상 파일:**
- `src/main/resources/templates/history.html`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapHistoryApiController.java`
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`

---

## [2026-06-12] AI Chat — 분석 이력 삭제 시 ai_chat_messages/sessions 연동 삭제

**문제:** 분석 이력(History/Files 페이지 삭제)을 삭제할 때 `deleteHistory()`가 `analysis_history`·`ai_insights`는 지우지만 `ai_chat_sessions`·`ai_chat_messages`는 삭제하지 않아 고아(orphan) 데이터가 잔존할 수 있었음.

- **`AiChatMessageRepository`** — `deleteBySessionId`에 `@Transactional` 명시, filename 기준 JPQL DELETE 메서드 `deleteByFilename` 추가 (서브쿼리로 session → message 연쇄 삭제).
- **`AiChatSessionRepository`** — JPQL `@Modifying` DELETE 메서드 `deleteByFilename` 추가.
- **`HeapDumpAnalyzerService.deleteHistory()`** — `aiChatMessageRepository.deleteByFilename(safe)` → `aiChatSessionRepository.deleteByFilename(safe)` 순 정리 로직 추가 (메시지 먼저, 세션 나중). 두 리포지토리를 생성자 주입에 추가.

**대상 파일:**
- `src/main/java/com/heapdump/analyzer/repository/AiChatMessageRepository.java`
- `src/main/java/com/heapdump/analyzer/repository/AiChatSessionRepository.java`
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`

---

## [2026-06-12] System Properties — WebLogic 포함 전 벤더 추출 안정화

**문제:** WebLogic 덤프 분석 시 System Properties 탭이 표시되지 않음. MAT `system_properties` 단독 쿼리가 WebLogic JDK 환경(Oracle JDK)에서 `java.lang.System.props` 필드를 못 찾거나 결과를 반환하지 않는 케이스 존재.

**원인 분석:** 기존 구현은 격리된 임시 디렉터리에서 MAT `system_properties` 쿼리를 실행해 결과 zip을 파싱하는 단일 경로였음. MAT 1.16의 `SystemPropertiesQuery`는 `java.lang.System.props` → `systemProperties` 필드를 순서대로 탐색하며 둘 다 null이면 결과를 반환하지 않음. System_Overview.zip에는 이미 `System_Properties*.html`(2열: Key/Value)이 항상 포함되어 있었으나 활용하지 않고 있었음.

- **`MatReportParser.parseSystemProperties(heapDumpDir, base)` 신규 추가:** System_Overview.zip 내 `System_Properties*.html`(패턴 `system_prop`) 페이지를 파싱해 (key→value) LinkedHashMap 반환. 메인 MAT 분석 시 Overview는 항상 생성되므로 WebLogic·JEUS·Tomcat·WildFly 등 모든 벤더에 안정적.
- **`HeapDumpAnalyzerService.enrichSystemProperties()` 재설계:** 1차(주) — `parser.parseSystemProperties()`로 Overview zip 파싱. 성공 시 즉시 반환. 2차(폴백) — 기존 MAT `system_properties` 단독 쿼리(Overview에 프로퍼티 섹션이 없는 엣지 케이스 대비).
- **MAT query 폴백 강화:** zip 파일명 정확 매칭 실패 시 qdir 내 `*_Query.zip` 재탐색 + zip 미생성·빈 결과 시 MAT 출력 500자 WARN 로그 (사내 서버 진단 용이).
- **`parseSystemPropertiesZip` 주석·필터 개선:** 실제 포맷 반영(트리 아닌 플랫 3열), `"Collection"` 헤더 필터 추가.

## [2026-06-06] PDF Report — "HTML로 보기" 모드 확대/축소 컨트롤 추가

**요청:** HTML 미리보기 모드에서 확대·축소 버튼 제공.

- **`analyze.js`(`?v=2026-06-06b`):** `_pdfHtmlZoom`(50~200%, 10% 스텝) + `adjustPdfHtmlZoom(delta)`/`resetPdfHtmlZoom()`/`applyPdfHtmlZoom()` 추가. same-origin iframe 내부 `.report` 시트에 **CSS `zoom`** 적용 — `transform:scale` 과 달리 문서 흐름이 리플로우되어 중앙 정렬·스크롤 높이 유지. `setPdfPreviewMode()` 가 HTML 모드에서만 `#pdfZoomCtrl` 표시(PDF 모드는 뷰어 자체 줌 사용), iframe `load` 시 현재 배율 재적용 → PDF↔HTML 재토글에도 배율 유지.
- **`analyze.html`:** 패널 헤더에 줌 컨트롤(`#pdfZoomCtrl`: − / 배율 표시(클릭 시 100% 재설정) / +) 추가 — 색상·형태 인라인 제공(함정 #17). `analyze.js ?v=2026-06-06b` 캐시 무효화.
- **검증(헤드리스 Chrome):** PDF 모드 컨트롤 숨김 / HTML 100%→130%(종이 794→1032px 정비례)→하한 50% 클램프→리셋 100% / 70% 상태로 PDF→HTML 재토글 시 배율 유지 — 전부 실측 통과, 130%·50% 스크린샷 중앙 정렬 유지 확인.

## [2026-06-06] print-html 데스크톱 디자인 개편 — A4 종이 시트 중앙 정렬 + 문서 느낌

**요청:** "HTML로 보기" 미리보기에서 리포트를 레이아웃 가운데 정렬 + 한 번 더 감싸는 레이아웃으로 문서 느낌 부여. 기존 데스크톱 print-html 은 screen 규칙이 모바일(≤900px)뿐이라 흰 배경 좌측 정렬로 밋밋하게 렌더.

- **`analyze-print.html`:** `@media screen and (min-width: 901px)` 블록 신설 — 라이트 그레이(#F3F4F6) 뷰포트 배경 위에 **A4 실물 크기(210×297mm) 흰 종이 시트**를 `margin:0 auto` 가운데 정렬 + 그림자/테두리. `padding:12mm` 으로 PDF `@page` 마진 재현(border-box 라 콘텐츠 폭 186mm 무변경), `min-height:297mm; height:auto; overflow:visible` 로 화면 렌더 시 내용 잘림 방지. `.ftr` 는 base `width:100%`(right 무시) + `display:table`(width:auto 시 shrink-to-fit) 함정 둘 다 회피하려 `left:12mm; width:calc(100% - 24mm)` 로 내부 여백 안쪽 고정. OpenHTMLtoPDF 는 print 미디어만 사용 → **PDF 산출물 무영향** (pdftotext diff 로 생성 시각 1줄 외 동일 실측). HTML 구조 무변경, 모바일 블록 무변경.
- **검증(헤드리스 Chrome 1920×1080):** 종이 794×1123px(=210×297mm 정확) + 좌우 여백 563px 균등(중앙 정렬) + footer 내부 여백 46px(≈12mm) 3변 균등 + 그림자 렌더. 모바일 390px 은 기존 흰 배경/풀폭/그림자 없음 그대로. analyze 페이지 iframe 경유 "HTML로 보기" 토글 경로 스크린샷 확인.

## [2026-06-06] PDF Report — 브라우저 PDF 뷰어 비활성 시 HTML 미리보기 자동 폴백 + 수동 토글

**요청:** PDF Report 패널에서 브라우저가 설정(Chrome "PDF를 열지 않고 다운로드", 기업 정책, 뷰어 미내장 등)에 의해 PDF를 로드할 수 없을 때의 대안 제공. 기존 폴백은 4초 후 "다운로드 버튼 사용" 텍스트 안내뿐.

- **`analyze.js`(`?v=2026-06-06a`):** `loadPdfReportPanel()` 재작성 — `setPdfPreviewMode(mode, isAutoFallback)` + `togglePdfPreview()` 도입. ① **사전 감지**: `navigator.pdfViewerEnabled === false`(Chrome 94+/FF 99+/Safari 16.4+ 표준, Chrome "다운로드 대신 열기" 설정 시 false) 또는 구형 브라우저 `mimeTypes['application/pdf']` 부재 → PDF 시도 자체를 생략하고 모바일용으로 기존재하는 `/print-html`(동일 내용 HTML 렌더, 백엔드 무변경)을 즉시 로드 + 안내 배너 — 의도치 않은 자동 다운로드까지 방지 ② **사후 감지**: PDF 모드 4초 내 `load` 미발생 시 텍스트 안내 대신 **HTML 모드 자동 전환** + 배너 (재시도 시 타임아웃 재무장) ③ HTML 모드조차 load 실패하는 극단 케이스는 기존 `#pdfReportFallback` 오버레이 최후 폴백 유지 ④ 모바일(≤900px)은 현행(HTML, 배너 없음) 유지.
- **`analyze.html`:** PDF Report 패널 헤더에 **PDF↔HTML 수동 전환 토글 버튼**(`#pdfPreviewToggleBtn`, 라벨 'HTML로 보기'/'PDF로 보기' 자동 갱신, secondary 톤 — 정상 환경에서도 사용 가능) + 닫기 가능한 호박색 안내 배너(`#pdfReportNotice`) 추가. `analyze.js ?v=2026-06-06a` 캐시 무효화.
- **검증(헤드리스 Chrome):** ① 정상 데스크톱 — `print-pdf?mode=inline` 로드 + 토글 양방향 전환 정상 ② `pdfViewerEnabled=false` 주입 — PDF 시도 없이 즉시 `print-html` + 배너 표시 ③ 모바일 390px — 현행 HTML + 배너 미표시 ④ 데스크톱 폭 print-html 레이아웃 스크린샷 깨짐 없음.

## [2026-06-06] 버전 2.0.7 → 2.0.8 업데이트

- **`pom.xml`:** `<version>` 2.0.7 → 2.0.8.
- **`restart.sh` / `run.sh` / `stop.sh`:** JAR 경로 + 프로세스 grep 패턴 `heap-analyzer-2.0.8.jar` 갱신.
- **`fragments/banner.html` / `index.html` / `progress.html`:** UI 버전 표기 v2.0.8 갱신.
- **배포:** 구버전(2.0.7) 프로세스 수동 kill 후 재기동 — 신규 grep 패턴이 구 JAR 미감지하는 포트 충돌 함정 회피. 기동 검증 OK (13.6s).

## [2026-06-06] 좌측 Navigation 배너 — 창 축소 시 스크롤바 미표시 수정 (전 페이지 공통)

**보고:** 1920×1080 에서 분석결과 화면 진입 후 브라우저 크기를 줄이면 좌측 Navigation 배너에 스크롤이 생기지 않아 하단 메뉴(Settings/Logout)에 접근 불가. 헤드리스 Chrome 실측으로 원인 확정: `.g-banner` 의 `overflow-y:auto` 는 동작하나 ① Win11 Chrome/Edge 의 overlay(fluent) 스크롤바가 상호작용 전까지 렌더되지 않아 "스크롤 불가"로 인지되고 ② flex column 압축으로 내부 `overflow:hidden` 요소(Disk/JVM 진행바 등)가 스크롤 전에 먼저 찌그러짐(실측 743px→717px) ③ 배너 스크롤이 바닥에 닿으면 뒷페이지로 휠 체이닝(실측 main 720px 이동).

- **`fragments/banner.html`(전 페이지 공통 수혜):** ① `.g-banner`/`.gb-tab-content` 에 커스텀 스크롤바 명시(`scrollbar-width:thin; scrollbar-color` + `::-webkit-scrollbar` 6px — CSS 스크롤바 스타일 지정 시 Chromium 이 overlay 모드를 해제하고 overflow 시 항상 표시) ② `.gb-header/.gb-body/.gb-collapsed-icons/.gb-toggle { flex-shrink:0 }` — 내용 찌그러짐 방지, overflow 를 배너 스크롤로 일원화(모바일은 후행 `.gb-tab-content { flex:1 }` shorthand 가 shrink 복원 → 드로어 내부 스크롤 현행 유지) ③ `.g-banner { overscroll-behavior:contain }` — 페이지 휠 체이닝 차단.
- **`analyze.css`(`?v=2026-06-06`):** analyze 자체 `.sidebar`(섹션 네비게이션)에도 동일 패턴(thin 스크롤바 + `overscroll-behavior:contain`) 적용 — 같은 증상 동반 해소.
- **검증(헤드리스 Chrome, 1920×1080 → 1100×480/800×420 축소):** 배너 스크롤바 표시(scrollbar 폭 0→10px) + 휠 스크롤 정상 + 체이닝 차단(main 720→0) + 모바일 드로어 스크롤 정상 + 1920×1080 레이아웃 무변화(overflow 없을 땐 스크롤바 미표시). ※ 검증 시 puppeteer 기본 `--hide-scrollbars` 플래그가 스크롤바를 숨기므로 `ignoreDefaultArgs` 필요.

## [2026-06-05] Leak Rules — Import 고도화: 중복 검사/처리 (skip/overwrite/append)

**요청:** import 시 새 데이터와 기존 데이터를 비교해 중복 검사 후 가져오기. 중복 자연 키 = 라이브러리 `prefix` / Fallback `patternRegex` (trim, case-sensitive). 처리 방식은 모달에서 선택.

- **`LeakRuleAdminController`:** `ImportRequest.onDuplicate`("skip"|"overwrite"|"append", null=append 하위호환, replace 모드에선 무시) + `validateImportRequest` 형식 검증. 제네릭 `applyImport(mode, dup, rules, repo, keyFn, copyFn, idNuller, kind)` 로 library/fallback 공통화 — ① skip/overwrite 시 파일 내부 중복 last-wins 정리(`intraDup` 로깅) ② `findAll()` 키 그룹맵으로 DB 현재 상태 기준 권위적 판정: 미존재 insert / skip 건너뜀 / overwrite 첫 매칭 행만 `copy*BusinessFields`(id/createdAt 유지, @PreUpdate 가 updatedAt 갱신), 기존 데이터 자체가 키 중복이면 첫 행만 갱신 + warn 로그. 전건 validation 현행 유지. 응답 `importSuccess` 확장(`onDuplicate/updated/skipped` 추가, `deleted/inserted` 하위호환 유지). 감사 로깅 `action=import ... onDuplicate=... inserted=... updated=... skipped=... intraDup=...`. 신규 엔드포인트 없음 → SecurityConfig 무변경.
- **`leak-rules.html`:** 파일 선택 시 즉시 클라이언트 미리보기 — "✅ N건 인식 — 신규 X건 · 중복 Y건"(중복 시 호박색) + 중복 키 목록 `<details>` 토글(`#impDupBox`, monospace 스크롤 박스). append 모드 + 중복>0 일 때만 중복 처리 radio(`#impDupModeRow`: 건너뛰기 기본/덮어쓰기/모두 추가) 노출, hint 에 "서버가 DB 현재 상태로 최종 판정" 명시. `submitImport` 가 `onDuplicate` 전송 + 완료 alert 에 추가/갱신/건너뜀 건수 표시. `_impKeyOf`/`_impExistingKeys` 헬퍼 (`_libRules`/`_fbRules` 재사용).
- **검증:** `zz.test.dup.` 테스트 룰로 skip(inserted=0/skipped=1, DB 무변화) → overwrite(updated=1, id/createdAt 유지) → append(중복 2행) → 2행 상태 overwrite 시 "matched 2 rows; only first updated" warn → intra-file 중복 last-wins(intraDup=1) 모두 API 검증 후 테스트 행 전부 삭제(운영 룰 무변경). onDuplicate 형식 오류 400.

## [2026-06-05] Leak Rules — 모달 열림 중 배경 스크롤 잠금

**보고:** 룰 추가 모달에서 스크롤 시 뒷배경 페이지가 함께 움직임 (`.modal-box` 내부 스크롤이 경계에서 body 로 전파).

- **`leak-rules.html`:** `ovOpen(id)/ovClose(id)` 헬퍼 추가 — 첫 모달 open 시 body 를 `position:fixed; top:-scrollY` 로 고정(iOS 포함 `overflow:hidden` 보다 확실), 마지막 close 시 해제 + `scrollTo` 로 위치 복원. 모달 중첩(설정→경고) 지원. 7개 모달 open/close 14개 호출 지점 일괄 치환. CSS `.modal-ov`/`.modal-box` 에 `overscroll-behavior:contain` 보강(내부 스크롤 경계 전파 차단).

## [2026-06-05] Leak Rules — 룰 일괄 설정 모달 (전체 사용설정/사용해제/삭제)

**요청:** 룰 관리 페이지에 설정 버튼 + 모달. 전체 사용해제/사용설정(경고 표시), 전체 삭제(경고 표시). 적용 범위는 모달에서 선택(라이브러리/Fallback/전체), 전체 삭제는 확인 문구("전체 삭제") 입력 방식.

- **`LeakRuleAdminController`:** `POST /api/admin/leak-rules/bulk-enabled` (`{target, enabled}` — 대상 룰 enabled 일괄 변경, 변경 건수 반환) + `POST /api/admin/leak-rules/bulk-delete` (`{target}` — `deleteAllInBatch`, 삭제 전 건수 캡처) 추가. target=library/fallback/all 검증(`normalizeTarget`), `@Transactional`, `ruleService.invalidate()`, 감사 로깅 `[LeakRule] action=bulk-enabled|bulk-delete target=... by=...` 컨벤션 준수. `/api/admin/**` 패턴이라 SecurityConfig 변경 불필요(ADMIN+CSRF 자동 적용).
- **`leak-rules.html`:** page-hdr 우측에 `⚙ 설정` 버튼(`.page-hdr-right` 래퍼). 모달 3개 추가 — ① `#bulkModal` 설정(대상 라디오: 라이브러리 N건/Fallback N건/전체 N건 + 액션 3버튼 `.bulk-btn`, 삭제는 danger 톤) ② `#bulkConfirmModal` 사용설정/해제 경고(대상·건수 + 영향 경고문) ③ `#bulkDeleteModal` 삭제 경고(복구 불가 + Export 백업 안내 + **"전체 삭제" 문구 입력 시에만 삭제 버튼 활성화**). 완료 시 양 탭 리로드 + 변경/삭제 건수 alert. 기존 fetch+authHeaders/모달 패턴 동일 적용.

## [2026-06-05] Leak Rules — topbar Dashboard 버튼 추가

- **`leak-rules.html`:** 비어있던 `.topbar-right` 에 `← Dashboard` 링크 추가 (admin/users.html 과 동일 패턴). leak-rules 는 common.css 미로드 페이지라 `.topbar-btn` 인라인 스타일 + ≤640px 컴팩트 패딩(5px 8px) 동반 추가.

## [2026-06-05] KRDS 캘린더 — 모바일 토요일 열 우측 넘침 수정 (calendar-wrap 260px 축소 규칙 제거)

**보고:** (뷰포트 클램프 적용 후에도) 시작일/종료일 달력 모두 토요일 우측 여백이 좁고, 토요일 날짜 선택 시 파란 박스가 달력 레이아웃을 살짝 벗어남. 헤드리스 Chrome 실측으로 원인 확정: `common.css` 모바일(≤640px) 블록의 `.calendar-wrap { width:260px }` 가 테이블 최소 폭(버튼 32px×7 + td padding 4px×7 = 252px + wrap padding 24 + border 2 = **278px**)보다 작아 테이블이 우측으로 **18px 오버플로**.

- **`common.css`(`?v=2026-06-05`, 14개 템플릿 일괄 갱신):** 모바일 `.calendar-wrap { width:260px }` 제거 → 기본 280px 사용(토요일 우측 여백 12px 복원, 32px 터치 타깃 유지). 재발 방지 주석 추가 — 280px 미만 축소 금지, 위치는 `calendar.js clampToViewport` 가 보정(≥320px 뷰포트 안전).

## [2026-06-05] KRDS 캘린더 — 모바일 뷰포트 우측 이탈 보정

**보고:** 모바일 Files 기간선택에서 시작일 달력은 토요일 열 우측 여백이 너무 좁고(뷰포트 우측 클리핑), 종료일 달력은 입력칸 위치가 오른쪽이라 팝업(`position:absolute; left:0`, 폭 ~306px)이 화면 오른쪽을 벗어남. `openCalendar()` 가 `.open` 토글만 하고 위치 보정이 없던 것이 원인.

- **`calendar.js`(`?v=2026-06-05`, banner.html 전역 로드라 캘린더 쓰는 모든 페이지 공통 수혜):** `clampToViewport(area)` 헬퍼 추가 — open 직후 `getBoundingClientRect()` 로 팝업 우측이 `clientWidth - 8px` 를 넘으면 초과분만큼 `style.left` 음수 이동(좌측은 최소 4px 까지만 — 초소형 화면 양쪽 잘림 최소화). `closeCalendar()` 에서 `style.left` 초기화(데스크톱 기본 위치 복원). 320px 뷰포트까지 팝업(306px) 무잘림.
- **`fragments/banner.html`:** `calendar.js?v=2026-05-17` → `?v=2026-06-05`.

## [2026-06-05] Files — 모바일 툴바 레이아웃 재정비 (검색칸 과대 + 토글류 불균형)

**보고:** Analysis Files 모바일 화면에서 검색 칸이 다른 컨트롤 대비 과하게 큼(padding 11px/font 14px) + 선택/deleted 표시/행갯수 버튼이 `flex:auto` 라 폭 불균형.

- **`files.html`(≤640px 인라인 미디어쿼리, 인라인 CSS 라 캐시 키 불필요):** 전 컨트롤 높이 통일(padding 9px) — 검색은 풀폭 유지하되 컴팩트(9px 12px/13px), 서버·기간·상태 select 3등분(기존 유지), 선택·deleted 표시·행표시를 동일 패턴 `flex:1 1 calc(33.333% - 6px)` 3등분 균등 배치(비관리자는 deleted 토글 부재 → 선택/행표시 50:50 자동 분배). `page-size-select` 는 wrap 폭 100% + 가운데 정렬(`text-align-last`).

## [2026-06-05] analyze Overview — 좌우 컬럼 높이 균형 + AI 인사이트 요약 카드 보강

**보고:** Overview 상단 진단 카드(OOM 감지 / Leak Suspects / AI 인사이트 요약)가 내용 양에 따라 좌측 컬럼이 비어 보임 — `wgdist_1_heapdump_20260326.hprof`(OOM 미감지 + Suspect 1건 + 짧은 AI 요약 → 좌측 하단 ~200px 공백) vs `jeus_server1.hprof`(Suspect 2건 → 균형). 원인: `.overview-top { align-items:start }` 라 좌측 카드가 내용 높이만큼만 차지, 우측(KPI+차트 ~650px)과 불균형.

- **`analyze.css`(`?v=2026-06-05b`):** `.overview-top` align-items start→**stretch**. 좌측 진단 카드 flex-grow 차등(`diag-ok` 1 / `diag-danger` 2 / leak·AI 카드 3)으로 우측 높이에 비례 확장 — 정상 카드는 기존 가로 flex+`align-items:center` 라 확장분이 세로 중앙 패딩으로 분산. leak/AI 카드는 세로 flex 전환 + "전체 보기/자세히 보기" 링크 `margin-top:auto` 하단 고정(`#ovAiInsightBody`/`#ovAiInsightEmpty` 래퍼도 세로 flex). 역전(좌>우) 케이스 대칭 처리: 우측 `.chart-box` flex-grow:1 + canvas 세로 중앙, chart-box 부재 시 `.card:last-child` 폴백. AI 카드용 보라톤 `.ov-ai-row/.ov-ai-row-key/.ov-ai-row-val`(line-clamp 2줄) 추가.
- **`analyze.html`:** AI 인사이트 요약 카드 `#ovAiInsightBody` 에 원인(`#ovAiRootCauseRow`)·권장(`#ovAiRecoRow`) 행 추가. `analyze.js?v=2026-06-05b`.
- **`analyze.js`:** `renderOverviewInsight()` 가 `rootCause` + 첫 번째 `recommendations` 항목을 채움(없으면 행 숨김, 미생성 복귀 시에도 숨김). `firstRecommendation()` 헬퍼 신설 — `setNumberedList` 와 동일한 번호 정규식(`/(\d+)\s*[.)]\s*/g`)으로 "1. xxx 2. yyy" 문자열에서 첫 항목 추출, 번호 없으면 첫 줄 폴백. `textContent` 사용(XSS 안전).
- **검증:** wgdist(비어 보이던 케이스)·jeus_server1(꽉 찬 케이스) 두 페이지에서 좌우 컬럼 높이 균형 + AI 카드 원인/권장 노출 확인. ≤900px 1열 전환 영향 없음(grow 는 단일 컬럼에서 무해, 해당 카드 대상 `!important` 미디어쿼리 없음). 빌드+기동 정상.

## [2026-06-05] Leak Suspects — Suspect 설명 잘림 + Keyword 미표시 수정

**보고:** `jeus_server1.hprof` 분석에서 Suspect #1 설명이 잘려 표시되고 Keyword 가 보이지 않음. 실제 `result.json` + MAT ZIP 검증으로 두 근본 원인 확정: (1) description 500자 하드캡, (2) `result.json` 이 keyword 추출 기능 도입(2026-05) 이전 산출물이라 `keywords: null`.

- **`MatReportParser`:** `parseSuspectsZip()` description 캡 500→**2000**자 상향(fallback 경로 1000→2000). MAT 본문 끝에 평탄화되어 섞이는 꼬리(`Keywords` FQCN 목록 — chip 으로 별도 표시되므로 본문 중복 제거 / `Details »`(`&raquo;`/`&#187;` 엔티티 포함) / `Table Of Contents` / `Created by Eclipse`)를 잘라내는 `SUSPECT_FOOTER_PATTERN` + `trimSuspectFooter()` 헬퍼 추가, substring 이전 적용. (`Keywords` 가 산문에 등장하지 않음을 실데이터로 확인.)
- **`HeapDumpAnalyzerService`:** `sanitizeCachedHtml()` 에 `reparseSuspectsMeta()` 추가(기존 `reparseSuspectsStacktrace()` 패턴). 구버전 result.json(= suspect 하나라도 `keywords` 비어있음) 캐시 복원 시 `_Leak_Suspects.zip` 에서 keyword 백필 + (현재 파서 기준) description 교체. **재분석 없이** 다음 로드에 자동 복구(인메모리 캐시만 갱신). 기동 시 jeus_server1 등 구버전 결과 다수 백필 로그 확인.
- **`analyze.html` (상세 패널):** `suspect-keywords` 블록이 `th:if="${s.explanation != null}"` 컨테이너 안에 중첩되어 룰 미매칭(explanation=null) suspect 의 키워드가 안 보이던 잠재 버그 수정 — 컨테이너 밖 형제 요소로 이동. `analyze.css` `.suspect-keywords` 를 독립 박스 스타일로(`?v=2026-06-05a`).
- **검증:** `/analyze/result/jeus_server1.hprof` 렌더 확인 — Suspect #1 설명 1186자(푸터/키워드 덤프 제거, 잘림 없음)·#2 97자 깔끔, Keywords 요약 카드 9칩 + 상세 패널 2블록 7라인 정상. 빌드+기동 정상(12.8s).

## [2026-06-01] analyze Overview — JEUS Instance/Domain 식별 칩 (자동 식별 + 수동 입력)

**요청:** System Properties 에 `jeus.server.name`/`jeus.domain.name` 이 식별되면 Overview 상단 Middleware 배지 오른쪽에 각각 **Instance**/**Domain** 으로 표기. 식별되지 않으면 수동 입력 가능하게.

- **`AnalysisHistoryEntity`:** `jeus_instance`/`jeus_domain` 컬럼 추가(varchar 100, ddl-auto=update 로 운영 DB 자동 생성 확인). 수동 편집값 영속화용 — 비면 자동 식별값으로 폴백.
- **`HeapDumpAnalyzerService`:** `getAnalysisJeusInstance/Domain(filename)` 조회 + `updateAnalysisJeus(filename, instance, domain)` 추가(null=유지, ""=초기화, 100자 절단). `updateAnalysisServerName`(호스트명) 패턴 재사용.
- **`HeapDumpViewController.analyzeResult`:** `jeus.server.name`/`jeus.domain.name` System Properties 에서 자동 식별값 추출 → 수동 편집값 우선, 없으면 자동값으로 폴백한 `jeusInstance`/`jeusDomain` + JS 폴백용 `jeusInstanceAuto`/`jeusDomainAuto` 모델 주입.
- **`HeapHistoryApiController`:** `POST /api/history/{filename}/jeus` 추가(instance/domain 부분 갱신, 호스트명 엔드포인트와 동일 패턴). `[JeusMeta]` 감사 로깅.
- **`analyze.html`:** Middleware 배지 오른쪽에 Host 칩과 동일 스타일의 **Instance**(🧩)/**Domain**(🌐) 칩 2개 추가(연필 클릭 인라인 편집). 항상 표시 — 식별 시 자동값, 미식별 시 "미지정" + 수동 입력. `JEUS_*_AUTO` 글로벌 추가, `analyze.js?v=2026-06-01c`.
- **`analyze.js`:** `startJeusEdit(field)`/`saveJeusEdit`/`applyJeusChip` 추가(호스트명 칩 인라인 편집 일반화). 저장 후 수동값 없으면 자동 식별값으로 폴백 표시.
- **검증:** JEUS 덤프(`heapdump_gmadomain_2.hprof`)에서 Instance=`gmadm_1`/Domain=`gmadmdomain` 자동 식별·렌더 확인. 수동 override 저장(부분 갱신 null-means-keep)·빈값 초기화→자동 폴백 동작 + DB 반영 확인 후 테스트값 정리. 빌드+기동 정상(13.5s).

## [2026-06-01] Target Servers CRUD 감사 로깅 보강

**점검 결과:** `ServerController` 의 서버 추가/수정/삭제(`POST/PUT/DELETE /api/servers`)에 **감사 로깅이 전혀 없었음**(save/deleteById 후 바로 응답). LeakRule 룰관리는 `[LeakRule] action=... by={user}` 패턴으로 감사 로깅이 되어 있어, 동일 패턴으로 보강.

- **`ServerController`:** slf4j `logger` + `who(Authentication)` 헬퍼 추가. 세 엔드포인트에 `Authentication auth` 파라미터 추가.
  - **create:** `[Server] action=create id=.. name=.. host=.. port=.. sshUser=.. autoDetect=.. by=..`
  - **update:** 변경 전 값 캡처 → `action=update id=.. name='old'->'new' host/port/autoDetect/enabled before->after fields=[변경키] by=..`
  - **delete:** 삭제 전 name/host 캡처(삭제 후 조회 불가) → `action=delete id=.. name=.. host=.. existed=.. by=..`
  - 각 catch 에 `action=... FAILED ... error=..` warn 로깅 추가.
- **검증:** 테스트 서버 생성→수정→삭제 후 로그에 create/update(diff)/delete 3건 + `by=admin` 기록 확인, 테스트 행 DB 정리 완료. 빌드+기동 정상(13.2s).

## [2026-06-01] Transfer Logs Export 모달 — 취소/다운로드 버튼 스타일 깨짐 수정

- **`server-logs.html`:** Export 확인 모달의 `.mbtn-cancel`/`.mbtn-confirm` 버튼이 브라우저 기본 버튼처럼 렌더되던 문제. common.css 의 `.mbtn-*` 는 **색상(background/color)만** 제공하고 padding/border/radius/font 등 형태는 페이지가 지정해야 하는데(cascade override 패턴), 직전 추가한 모달엔 형태 규칙이 없었음. `#exportModal .modal-box button` 에 `padding/border:none/border-radius/font-size/font-weight/cursor` 기본 형태 추가(leak-rules.html `.modal-btns button` 과 동일 패턴).
- **검증:** 페이지에 규칙 반영 확인. 빌드+기동 정상(13.3s).

## [2026-06-01] 모바일 — AI 결과 폰트 축소 + Transfer Logs KPI 한 줄 + Export 모달/스피너 수정

- **AI 인사이트 결과 폰트 (`analyze.css?v=2026-06-01e`):** `≤768px` 에서 저장배너(`#aiSavedPathBanner` 14→13px) + 요약/근본원인/권장조치/위험요소/JVM 본문(`#aiSummary` 외 4개 15→13px, line-height 1.85→1.7) 약간 축소. 인라인 스타일이라 `!important` 로 오버라이드.
- **Transfer Logs KPI 한 줄 (`server-logs.html`):** `≤640px` 에서 `.kpi-grid` 가 `1fr`(세로 3줄)이던 것 → `1fr 1fr 1fr`(한 줄 3등분) + 콤팩트 세로 카드(아이콘 26px·value 17px·label 10px).
- **Transfer Logs Export 모달 + 무한 스피너 수정 (`server-logs.html`):** CSV/JSON 클릭 시 즉시 `<a href=url>` 네비게이션으로 다운로드 → 응답 `Content-Disposition: form-data`(non-attachment)라 브라우저 탭 스피너가 무한 회전하던 문제. (1) 클릭 시 확인 모달(common.css `.modal-ov`/`.modal-box` 재사용) 표시, (2) `confirmExport()` 가 `fetch`+`blob`+`URL.createObjectURL` 로 **페이지 네비게이션 없이** 저장(Content-Disposition 파일명 파싱, 버튼 '다운로드 중…' 상태). 스피너 무한 회전 원천 제거.
- **검증:** 배포 CSS/페이지에 폰트·KPI·모달/JS 반영, export 엔드포인트 GET 200 + CSV + 파일명(`transfer-logs-…csv`) 확인. 빌드+기동 정상(13.4s).

## [2026-06-01] 모바일 — Leak Rules 모달 화면초과 수정 + Target Servers 필터 한 줄 배치

- **Leak Rules 모달 (`leak-rules.html`):** 새 라이브러리 룰/편집 모달(긴 폼)이 모바일 bottom-sheet 에서 화면을 넘고 하단 저장/취소 버튼 도달이 어렵던 문제. `≤640px` 모달을 flex 컬럼으로 전환 — `.modal-ttl` sticky top / `.modal-btns` sticky bottom(둘 다 `margin:0 -16px` 로 풀폭 + 자체 배경/구분선) / 본문만 스크롤. `max-height` 를 `92vh` → `92dvh`(모바일 주소창 영역 제외)로 변경해 화면 초과 방지.
- **Target Servers 필터 (`servers.html`):** `≤900px` 에서 툴바가 `flex-direction:column` 이라 검색·상태·자동탐지·행표시가 4줄로 적층되던 것 → 검색은 `flex:1 1 100%`(한 줄 전체), 상태/자동탐지/행표시 `.page-size-wrap{flex:1 1 0;min-width:0}` 로 **한 줄 3등분**. 좁은 폭 대응 라벨 11px·select padding/화살표 위치 축소.
- **검증:** 두 페이지 렌더에 sticky 모달(`92dvh`)·필터 3등분 규칙 반영 확인. 빌드+기동 정상(13.2s).

## [2026-06-01] 모바일 AI 채팅 — 오픈 시 가상 키보드 자동 표시 방지

- **`analyze.js?v=2026-06-01b`:** `toggleAiChat()` 오픈 시 200ms 후 `aiChatInput.focus()` 호출이 모바일에서 가상 키보드를 즉시 띄우던 문제. `!window.matchMedia('(max-width: 900px)').matches` 가드 추가 → 모바일은 자동 포커스 생략(사용자가 입력창 탭하면 그때 키보드), 데스크톱은 기존대로 즉시 포커스. (전송 완료 후 재포커스(3252)·호스트명 칩 편집 포커스(3509)는 사용자 능동 동작이라 유지.)
- **검증:** 배포 JS 에 900px 가드 반영 확인. 빌드+기동 정상.

## [2026-06-01] AI 인사이트 결과 모바일 — 위험도 2줄 적층 + 메타 글자 축소

- **위험도 2줄 (`analyze.html` `#aiSevGroup`, `analyze.css?v=2026-06-01d`):** 위험도 그룹 div 에 id 부여 후 `@media (max-width:768px)` 에서 `flex-direction:column` 으로 "위험도"(11px) / "Critical" 2줄 적층. 가로 공간 확보.
- **메타 글자 축소:** 우측 시간/모델/응답이 좁은 화면에서 레이아웃을 벗어나던 문제 → `≤768px` 10px, `≤480px` 9px(+아이콘 26·위험도 19px)로 단계 축소. 중복됐던 `≤560px` 메타 규칙 제거.
- **검증:** 배포 CSS 에 `#aiSevGroup` column 적층·메타 10px/9px 반영, 페이지에 `aiSevGroup` id 확인. 빌드+기동 정상(13.1s).

## [2026-06-01] AI 인사이트 결과 — 모바일 메타 우측 3줄 배치 + 신규 완료 시 분석시각 즉시 표시

- **모바일 배너 레이아웃 (`analyze.css?v=2026-06-01c`, `analyze.html`):** 위험도 배너의 시간/모델/응답 메타를 위험도 **우측에 세로 3줄**로 표시. 근본 원인은 기존 `@media (max-width:768px)` 의 `#aiSeverityBanner { flex-direction:column !important }` + `> div:last-child { text-align:left !important }` 가 배너를 세로 적층하고 메타를 위험도 하단·좌측에 두던 것 — 이 강제 규칙 제거하고 가로 유지(`gap`/`padding` 만 축소) + `#aiSevMeta { text-align:right }` 로 변경. 배너 HTML 도 메타를 가로 wrap span → 세로 stack `<div>`(id `aiSevMeta`)로 되돌리고 `margin-left:auto` 우측 고정, `flex-wrap` 제거. 좁은 폭 대응으로 `≤768px` 아이콘 28·위험도 20px(기존 480 블록), `≤560px` 메타 10.5px 축소.
- **신규 완료 시 분석시각 미표시 버그 (`AiInsightManager` / `HeapAiApiController` / `analyze.js?v=2026-06-01a`):** `/api/llm/analyze` 응답에 `analysedAt` 이 없어 분석 직후엔 "… 분석" 시각이 빈 값이고 새로고침(DB 로드) 후에만 표시되던 문제. `saveAiInsight` 가 분석시각을 복사본이 아닌 **입력 맵에 스탬프**하도록 변경 → 컨트롤러가 `result.put("analysedAt", …)` 로 응답에 포함. 추가로 `showAiResult` 에 `|| Date.now()` 폴백(저장 실패 등 누락 시 빈 값 방지).
- **검증:** 배포 CSS 에 column 강제 제거·메타 우측정렬 반영, JS 폴백 반영 확인. 빌드+기동 정상(13.1s). (LLM 실호출 검증은 비용·데이터 보호 위해 생략, 서버 응답 경로는 코드 리뷰로 확인.)

## [2026-06-01] AI 인사이트 분석중 화면 — 모바일 단계 인디케이터 오버플로 수정

- **단계 인디케이터 (`analyze.css?v=2026-06-01a`):** '데이터 수집-프롬프트 구성-LLM 분석-결과 저장' 스텝퍼가 고정 `min-width:76px`×4 + 라인 `min-width:28px`×3 ≈ 388px 라 좁은 모바일에서 가로 오버플로(레이아웃 이탈). `@media (max-width:560px)` 추가: `.ai-step{min-width:0;flex:1 1 0}`(가용 폭 균등 축소) + 라벨 `white-space:normal;word-break:keep-all`(줄바꿈 허용) + circle 36→30px + line `flex:0 0 10px`. 어떤 모바일 폭에서도 컨테이너를 넘지 않음.
- **검증:** 배포 CSS(캐시키 a)에 `flex:1 1 0`/라벨 줄바꿈 규칙 반영 확인. 빌드+기동 정상(13.1s).

## [2026-06-01] AI 인사이트 패널 — 모바일 '완료' 뱃지 개행 수정 + 위험도 배너 메타 가로 배치

- **'완료' 뱃지 개행 (`analyze.html`):** `#aiPanelBadge` 에 `white-space:nowrap;flex-shrink:0` 추가. 좁은 모바일 폭에서 패널 헤더 좌측 flex 그룹(아이콘+제목+뱃지)이 압축되며 "완/료" 가 글자 단위로 줄바꿈되던 문제 해결.
- **위험도 배너 메타 (`analyze.html` `#aiSeverityBanner`):** 시간/모델/응답을 세로 누적 `<div>` 3개 → 위험도 우측 가로 배치 `<span>` flex 그룹(`margin-left:auto`, `flex-wrap:wrap`)으로 변경. 배너 자체도 `flex-wrap:wrap` 추가. 모바일에서 메타가 위험도 하단에 3줄로 쌓여 세로 공간을 크게 차지하던 문제를 1행(좁으면 1줄 줄바꿈)으로 축소. padding 22→18px, icon 38→34px 소폭 조정. (ID 불변 → analyze.js textContent 세팅 그대로 동작.)
- **검증:** 렌더 페이지(oom-test.hprof)에서 뱃지 nowrap·메타 span/`margin-left:auto` 반영, 옛 세로 div 블록 제거 확인. 빌드+기동 정상(13.4s).

## [2026-05-31] 분석화면 — AI 미분석 로고 제거 + Overview 컬럼 레이아웃 자연 정렬

- **AI 인사이트 패널 (`analyze.html`):** `#aiStateNotAnalyzed` 본문 "AI 분석이 아직 수행되지 않았습니다" 상단의 🤖 로고 박스(64px gradient) 제거.
- **Overview 레이아웃 (`analyze.css?v=2026-05-31i`):** `.overview-top` 의 `align-items:stretch` → `align-items:start`. 기존엔 두 컬럼을 강제로 같은 높이로 늘여(좌: OOM/Leak/AI요약, 우: KPI/Heap/Biggest Objects 파이), 한쪽이 길면 짧은 쪽 마지막 카드(예: Biggest Objects) 하단에 빈 공간이 생겼다. 특히 Leak Suspects 2건 + AI 요약 카드로 좌측이 길어진 oom-test.hprof 에서 우측 파이 카드 아래 공백 발생. `.chart-box canvas{max-height:260px}` + 파이 `maintainAspectRatio:true` 라 카드를 늘여도 내부 공백만 커지므로, stretch 자체를 제거해 각 컬럼·카드가 내용 높이에 맞춰 자연 정렬되도록 함. (불필요해진 `.ov-diag > .card:last-child{flex:1 1 auto}` 규칙 제거.)
- **검증:** 로고 박스 잔존 0, 배포 CSS 에 `align-items:start` 반영 확인. 빌드+기동 정상(13.3s).

## [2026-05-31] 분석화면 Overview — Leak Suspects 하위에 AI 인사이트 요약 카드

**배경:** AI 인사이트는 별도 탭에만 있어 Overview 한눈 진단에서 누락. Overview 좌측 진단 컬럼(`ov-diag`)의 Leak Suspects 카드 하위에 AI 인사이트 **요약(summary)** + 위험도 칩을 노출.

- **UI (`analyze.html`):** `ov-diag` 컬럼 끝에 `#ovAiInsightCard` 카드 추가. 두 상태: 미생성(`#ovAiInsightEmpty` — "AI 분석 하러가기 →") / 요약 본문(`#ovAiInsightBody` — `#ovAiSummary` + 위험도 칩 `#ovAiSeverity` + "자세히 보기 →"). 기본 미생성 상태, JS가 토글.
- **CSS (`analyze.css?v=2026-05-31h`):** `.ai-insight-summary-card`(보라 테마) + `.ov-ai-summary`/`.ov-ai-empty-text`. 기존 `.diag-card`/`.leak-view-all` 재사용.
- **JS (`analyze.js?v=2026-05-31c`):** `goToAiInsight()`(패널 전환) + `renderOverviewInsight(data)`(summary 채움, 위험도 칩 `_SEV_CONFIG` 색상 적용, summary 없으면 미생성 상태로 복귀). `showAiResult()` 끝 + 저장 인사이트 auto-load 경로에서 자동 호출, `deleteAiInsight()` 성공 시 `renderOverviewInsight(null)` 로 초기화.
- **검증:** 실제 인사이트(oom-test.hprof) GET `found:true`/severity `Critical`/summary 반환 확인, analyze 페이지에 카드 요소(`ovAiInsightCard`/`ovAiSummary`/`ovAiSeverity`)·신규 JS 함수 포함 확인. 빌드+기동 정상(13.4s).
- **⚠️ 데이터 손실 (검증 중 사고):** 테스트 INSERT 의 `ON DUPLICATE KEY UPDATE` 가 기존 `ai_insights` 의 `tomcat_heapdump.hprof` 인사이트(id 7)를 깨진 JSON 으로 덮어씀(문자열 내 미이스케이프 개행). 파일 백업 없어 복구 불가 → 깨진 행 삭제(미수행 상태로 복귀). 해당 덤프는 "AI 분석 시작" 으로 재생성 필요. **교훈: 운영 DB 의 UNIQUE 키 대상 테스트 INSERT 에 `ON DUPLICATE KEY UPDATE` 금지.**

## [2026-05-31] 분석화면 Overview — 덤프 출처 호스트명 칩 + 수동 편집

**배경:** SSH 원격 전송 덤프는 `analysis_history.server_name`(출처 서버)이 자동 기록되지만, 수동 업로드 덤프는 비어 있음. 분석화면 Overview에 호스트명 칩을 노출하고, 연필 아이콘으로 운영자가 직접 입력/수정할 수 있게 함.

- **서비스 (`HeapDumpAnalyzerService`):** `getAnalysisServerName(filename)`(조회, 미식별 시 "") + `updateAnalysisServerName(filename, hostname)`(@Transactional, 빈 값→null 초기화, 100자 절단, 레코드 없으면 null) 추가.
- **API (`HeapHistoryApiController`):** `POST /api/history/{filename}/hostname` — `{hostname}` 입력, `{success, hostname}` 반환. 레코드 없으면 404. `/api/**` 공통 정책(인증 필요 + CSRF 면제)이라 SecurityConfig 변경 불필요.
- **ViewController (`HeapDumpViewController.analyzeResult`):** 모델에 `hostname` 추가.
- **UI (`analyze.html` + `analyze.js?v=2026-05-31b`):** 미들웨어 배지 좌측에 `🌐 Host: {호스트명}` 칩(`.host-chip`) + 연필 버튼. 미지정 시 이탤릭 "미지정". 연필 클릭 → 인라인 input + 저장/취소(Enter 저장·Esc 취소) → `Common.fetchJSON` POST → 칩 즉시 갱신. 배지 2개를 `.overview-badge-row` flex로 묶음.
- **검증:** 로그인 세션으로 설정(`guacmg1t`)→DB 반영 확인→빈값 초기화→없는 파일 404, analyze 결과 페이지 칩 렌더(`guacmg1t`) 확인. (`/api/history` 응답은 원래부터 serverName 미포함 — 본 변경 무관, 칩은 DB 직접 조회.) 빌드+기동 정상(13.5s). 테스트 데이터 원복.

## [2026-05-31] 어플리케이션 버전 2.0.6 → 2.0.7

- `pom.xml` `<version>` 2.0.6 → 2.0.7 (산출물 `heap-analyzer-2.0.7.jar`).
- 기동 스크립트 JAR 파일명 참조 일괄 갱신: `restart.sh` / `run.sh` / `stop.sh`.
- UI 버전 표기: `fragments/banner.html`(헤더), `index.html`(모바일 사이드바), `progress.html`(푸터). `CLAUDE.md` 빌드 예시도 갱신.
- **주의:** 스크립트가 새 JAR명(2.0.7)으로 grep 하므로 첫 재기동 시 구버전(2.0.6) 프로세스를 자동 종료하지 못함 → 구 프로세스 수동 `kill` 후 재기동(포트 18080 점유 충돌 해소). 이후 재기동은 정상.
- **검증:** 빌드 OK, `heap-analyzer-2.0.7.jar` 기동 정상(13.1s).

## [2026-05-31] 미들웨어 배지 — "N회 매칭" 표기 제거

**배경:** 분석화면 미들웨어 배지의 `WAS · 1447회 매칭` 에서 매칭 횟수는 `MiddlewareDetector` 의 내부 휴리스틱 점수(`matchCount`, 벤더 시그니처 누적 매칭수로 대표 벤더 1개 선택용)일 뿐 사용자에게는 의미 없는 디버깅 지표 → 혼란만 유발하여 노출 제거.

- `templates/analyze.html` — 배지 meta span 을 `category + 'N회 매칭'` → `category` 단독으로 축소(WAS/DB 카테고리만 유지).
- `controller/HeapDumpViewController.java` — `model.addAttribute("middlewareMatchCount", ...)` 제거. `MiddlewareDetector.Result.matchCount` 필드는 내부 선택 로직에 그대로 사용(모델 노출만 제거).
- **검증:** 빌드+재기동 정상(14.0s).

## [2026-05-31] System Properties 추출 + 미들웨어 버전/JDK 버전 확정

**배경:** 미들웨어 벤더 배지(아래 항목)의 버전이 클래스/스택 이름만으로는 추출 불가했음. MAT `system_properties` 쿼리로 JVM `System.getProperties()` 를 가져와 JDK/WAS 버전을 확정하고 전용 탭으로 노출.

**MAT 쿼리 (`service/HeapDumpAnalyzerService.java`):**
- `enrichSystemProperties(result, dumpFile, resultDir)` — `runOomDetailQuery` 패턴 미러링(인덱스 복사 + 덤프 심볼릭 링크 격리 실행) + `runMatSingleQuery(..., "system_properties", 120)`. 실패해도 분석 전체 무영향(빈 맵). 분석 흐름의 OOM enrich 직후(dumpFile·인덱스 존재 구간)에서 호출.
- `parseSystemPropertiesZip(zip)` — `_Query.zip`/index.html 파싱. **MAT 출력이 3열 `[Collection | Key | Value]` 트리 구조**임을 확인 → key=2번째·value=3번째 셀(2열 폴백 허용), 헤더/컬렉션 부모행/Total 제외. `LinkedHashMap` 순서 보존.
- **신규 분석만** 적용(기존 result.json 은 빈 맵 — 재분석 시 채워짐).

**모델 (`model/HeapAnalysisResult.java`):** `Map<String,String> systemProperties` 필드 + `hasSystemProperties()` + `getJdkVersion()`(java.runtime.version→java.version→java.vm.version). `cloneWithoutLog` 에 persist 1줄 추가.

**감지기 (`util/MiddlewareDetector.java`):** `detect(entries, threads, sysProps)` 오버로드 추가. (1)벤더 고유 sysprop 마커(`catalina.home`/`weblogic.Name`/`jeus.home` 등) 존재 시 가중치 100으로 **권위적 확정**. (2)버전 해석: 직접 버전 프로퍼티 → **제품 토큰 anchor 경로 추출**(`tomcat[^0-9]{0,12}(\d+\.\d+...)` → `apache-tomcat-9.0.87` 에서 `9.0.87`) → 이름 best-effort 순.

**UI (`templates/analyze.html`, `controller/HeapDumpViewController.java`):** 배지에 JDK 버전 칩(초록) 추가, 미식별이어도 JDK 만 있으면 배지 표시. Analysis 네비에 **System Properties 탭** + 검색 가능한 key/value 표 패널(`filterSysProps()` 인라인) 추가.

- **검증:** tomcat_heapdump.hprof 재분석 → `[SysProp] Extracted 71` 로그, 배지 `Apache Tomcat · 9.0.87 · JDK 21+35-2513 · WAS · 364회 매칭`(버전 java.class.path 추출/ JDK java.runtime.version), System Properties 탭 71행 렌더 확인. 빌드+재기동 정상(13.5s).

## [2026-05-31] 분석화면 Overview — 미들웨어(WAS) 벤더 자동 감지 배지

**배경:** 덤프가 어떤 WAS(Tomcat/WebLogic/JEUS 등)에서 떴는지 한눈에 파악할 수 있도록 Analysis 페이지에 표시 요청. 신규 분석 없이 기존 결과(result.json)에도 소급 적용.

**신규 (`util/MiddlewareDetector.java`):** `OomDetector` 스타일의 전용 유틸. `detect(List<HistogramEntry>, List<ThreadInfo>)` → 패키지 prefix 기반 8개 벤더(WebLogic `weblogic.` / JEUS `jeus.` / Tomcat `org.apache.catalina|coyote|tomcat.` / WebSphere `com.ibm.ws|websphere.` / Jetty `org.eclipse.jetty.` / JBoss·WildFly / Undertow / Resin) 매칭. 여러 벤더 동시 감지 시 **매칭 횟수 최다 1개**를 대표로 반환. 오탐 방지 `MIN_MATCH=3`.
- **신호 출처(중요):** MAT 히스토그램 리포트는 retained 상위 ~25개만 담아 WAS 내부 클래스가 거의 안 잡힘. 따라서 **주 신호는 스레드 스택 트레이스 + 스레드 객체타입 + 컨텍스트 클래스로더**(WAS 요청 스레드는 프레임워크 프레임을 반드시 거침), 히스토그램은 보조 합산. (`LeakSuspectAdvisor` prefix 와 중복되나 그쪽은 advice 람다 강결합이라 재사용 부적합 → 전용 유틸 분리.)
- **버전:** 모든 신호가 클래스/패키지 이름이라 버전 문자열 없음(Tomcat `ServerInfo`·WebLogic `weblogic.version` 등 객체 필드 값). `detectVersion()` 은 이름에 버전 토큰 박힌 드문 경우만 best-effort, 그 외 `null`. 신뢰도 높은 버전은 MAT system_properties 추출(후속) 필요.

**수정 (`controller/HeapDumpViewController.java` `analyzeResult`):** 렌더 시점에 `MiddlewareDetector.detect(histogramEntries, threadInfos)` 호출 → 감지 시 `middlewareVendor`/`middlewareCategory`/`middlewareMatchCount`/`middlewareVersion` 모델 속성 추가. result 모델·result.json 영속화 변경 없음.

**수정 (`templates/analyze.html`):** Overview 패널 상단에 `.mw-badge` 칩 추가 — `th:if="${middlewareVendor != null}"`. 벤더명 + (버전 있으면)버전 pill + `카테고리 · N회 매칭` 메타. KPI 6-카드 그리드 그대로 유지(배지를 위에 배치). 스코프 `<style>` 인라인.

- **검증:** 빌드+재기동 정상(13.3s). 로그인 후 `/analyze/result/tomcat_heapdump` 200 → 배지 `Apache Tomcat / WAS · 164회 매칭` 렌더 확인(스택의 `org.apache.tomcat.` 130 + `org.apache.catalina.` 등 합산). 버전 null(미표시) 확인.

## [2026-05-31] Leak Rule 관리 — 감사 로깅 보강 + Import/Export 화살표 교정

**배경:** Leak Suspect 룰 관리(`/admin/leak-rules`)에서 토글/편집/삭제/Export 동작이 로그에 전혀 남지 않음(Import만 로깅). 또 Export/Import 버튼 화살표가 서로 반대.

**수정 (`controller/LeakRuleAdminController.java`):** 모든 mutation 에 `Authentication auth` 주입 + INFO 감사 로그 추가. 통일 포맷 `[LeakRule] action=<action> kind=<library|fallback> ... by=<user>`.
- **toggle vs update 구분:** PUT update 엔드포인트가 토글(enabled만 변경)과 편집(그 외 필드 변경)을 모두 처리하므로, 기존값(`cur`)과 요청값(`body`)을 비교해 enabled만 바뀌면 `action=toggle enabled=old->new`, 그 외엔 `action=update priority=.. enabled=..` 로 로깅.
- **create:** `action=create ... id=.. prefix/name=.. priority=.. enabled=..`. **delete:** 삭제 전 엔티티 조회해 `action=delete id=.. prefix/name=..`(식별자 보존). **export:** `action=export count=..`. **import:** 기존 `[LeakRuleImport]` → 통일 포맷 `action=import mode=.. deleted=.. inserted=..`.
- 헬퍼 `who(auth)`/`eq(a,b)`(null-safe) 추가.

**수정 (`templates/leak-rules.html`):** Export 버튼 화살표 `⬇`→`⬆`(위), Import 버튼 `⬆`→`⬇`(아래). 라이브러리·Fallback 양쪽 4개 버튼 모두 교정. (Export 모달 내 "다운로드" 버튼은 다운로드 의미라 `⬇` 유지.)

- **검증:** 빌드+재기동 정상(15.9s). 로그인 후 API 로 toggle/update/create/delete/export/import 전 동작 수행 → 로그에 `[LeakRule] action=toggle|update|create|delete|export|import ... by=admin` 9줄 정상 출력 확인(toggle↔update 구분 정확). 페이지 렌더 결과 Export=`⬆`/Import=`⬇` 확인. 테스트 룰 정리 + DB 상태 원복(lib 98/fb 66) 확인.

## [2026-05-31] 분석화면 OOM 강조 위치 변경 — Thread Overview 라인 롤백 → Heap Statistics OOM 행으로 이동

**수정 (`templates/analyze.html` + `static/css/analyze.css`):**
- **롤백:** 직전 작업(좌측 네비 Thread Overview 라인 `nav-item-oom` 붉은 배경)을 되돌림. `analyze.html` 의 `th:classappend="... nav-item-oom ..."` 제거, `analyze.css` 의 `.nav-item.nav-item-oom`(+hover/active) 3개 규칙 삭제.
- **신규:** OOM 감지(`oomThreadCount > 0`) 시 **Heap Statistics 섹션의 OOM 행** 배경을 붉은색으로 강조. `sidebar-stat` 행에 `th:classappend="${oomThreadCount > 0 ? 'sidebar-stat-oom' : ''}"` 추가 + `.sidebar-stat.sidebar-stat-oom { background:var(--danger-light); border:1px solid #fecaca; border-radius:6px; padding:6px 8px; margin:2px -8px }` (stat-key 다크레드). 기존 `stat-oom-yes`(값 텍스트 red)와 결합.
- 클래스 기반이라 모바일 배너 사이드바 클론에도 자동 적용.
- 캐시 키 `?v=2026-05-31f`→`2026-05-31g`(css).
- **검증:** 빌드+재기동 정상(13.2s). `/analyze/result/oom-test.hprof` 200 — Threads 버튼 `class="nav-item"`(nav-item-oom 0건), OOM 행 `class="sidebar-stat sidebar-stat-oom"` 렌더, 서빙 CSS 에서 `nav-item-oom` 0건·`sidebar-stat-oom` 규칙 포함 확인.

## [2026-05-31] 분석화면 좌측 네비 — OOM 감지 시 Thread Overview 라인 배경 붉은색 강조

**수정 (`templates/analyze.html` + `static/css/analyze.css`):** OOM 감지 시 좌측 사이드바의 OOM 라인(Thread Overview nav-item, 기존 `⚠ OOM` 배지가 붙는 항목)을 한눈에 띄게 배경 강조.
- `analyze.html`: Thread Overview 버튼에 `th:classappend="${oomThreadCount > 0 ? 'nav-item-oom' : ''}"` 추가(기존 `nav-badge-oom` 배지 조건과 동일).
- `analyze.css`: `.nav-item.nav-item-oom { background:var(--danger-light); color:#991b1b; box-shadow:inset 3px 0 0 var(--danger) }` + hover(`#fee2e2`)/active(`#fecaca`) 변형. 좌측 3px 붉은 액센트 바 동반.
- 클래스 기반이라 모바일 배너 클론(`registerBannerAnalysisTab`, pitfall #8)에도 JS 없이 자동 적용.
- 캐시 키 `?v=2026-05-31e`→`2026-05-31f`(css).
- **검증:** 빌드+재기동 정상(15.4s). 로그인 후 `/analyze/result/oom-test.hprof` 200 — Thread Overview 버튼이 `class="nav-item nav-item-oom"` 렌더, `nav-badge-oom` 배지 동반, 서빙 CSS(`?v=...f`)에 `.nav-item-oom` 규칙 포함 확인.

## [2026-05-31] Leak Suspect 룰 고도화 — Oracle/Tibero DB 드라이버 룰 보강 (library 91→98, fallback 58→66)

**배경:** Oracle/Tibero JDBC 드라이버 측 누수(미닫힌 커서, 임시 LOB 미해제, 커넥션 풀 미반환) 진단 정밀화 요청. 기존엔 generic `oracle.jdbc.`(pri 1042) / `com.tmax.tibero.`(1044) catch-all 만 존재, Oracle/Tibero 전용 fallback 0건.

**추가 (`resources/leak-rules/library-rules.json` + `fallback-rules.json` + 운영 DB INSERT):**
- **Library(prefix) +7:** Oracle 5 — `oracle.jdbc.driver.`(커서/Statement) · `oracle.jdbc.pool.`(커넥션 풀) · `oracle.ucp.`(Universal Connection Pool) · `oracle.sql.`(Temporary LOB) · `oracle.net.`(SQL*Net 버퍼), pri 930~934. Tibero 2 — `com.tmax.tibero.jdbc.driver.`(커서/Statement) · `com.tmax.tibero.jdbc.`(드라이버 리소스), pri 935~936. 모두 generic(`oracle.jdbc.` 1042 / `com.tmax.tibero.` 1044)보다 낮은 priority → 구체 prefix 우선 매칭.
- **Fallback(regex) +8:** Oracle 5 — 커서/Statement(ORA-01000 포함) · Temporary LOB · UCP 풀 · 물리 커넥션(T4CConnection) · SQL*Net 버퍼, pri 4930~4934. Tibero 3 — 커서/Statement(TbStatement) · LOB(TbClob/TbBlob) · 커넥션 풀(TbConnection), pri 4935~4937. 기존 generic Statement 룰(5018)보다 먼저 평가되어 DB 특화 설명/조치 제공. WAS 룰(4900~4929)과 키워드 상호배타.
- 비고: Tibero 는 JDBC 패키지 구조가 단순해 library 2건으로 두고 세부 유형(LOB/커서/풀)은 fallback regex(클래스명 키워드 기반)로 보강.

**적용 방식:** JSON 추가 + 운영 DB(`192.168.56.9/HEAPDB`)에 `INSERT ... WHERE NOT EXISTS`(prefix/name idempotent) 직접 보강.
- **검증:** 빌드+재기동 정상(14.0s), seeder 98/66 "already present" 스킵. DB 정규식 단일 백슬래시 검증(`'oracle.sql.CLOB' REGEXP=1`, `'oracleXsqlXCLOB'=0`, `'...TbStatement'=1`). 라이브 `/api/admin/leak-rules/{library,fallback}` 가 98/66 반환, Oracle 5·Tibero 2(library)·Oracle 5·Tibero 3(fallback) priority·우선순위 노출 확인(`oracle.jdbc.driver.`930<`oracle.jdbc.`1042).

## [2026-05-31] Leak Suspect 룰 고도화 — JEUS 룰 보강 (library 81→91, fallback 48→58)

**배경:** 운영 환경 주력 WAS 인 JEUS(TmaxSoft) 누수 진단 정밀화 요청. 기존엔 generic `jeus.`(pri 1003, 서블릿 스트림 중심) 1건 + `com.tmax.`(1045)만 존재, JEUS fallback 룰 0건.

**추가 (`resources/leak-rules/library-rules.json` + `fallback-rules.json` + 운영 DB INSERT):**
- **Library(prefix) +10 (pri 920~929):** `jeus.servlet.`(세션/요청) · `jeus.ejb.` · `jeus.jdbc.`(커넥션 풀) · `jeus.transaction.`(JTA) · `jeus.jms.` · `jeus.connector.`(JCA) · `jeus.deploy.`(ClassLoader 재배포) · `jeus.security.` · `jeus.server.`(워커 스레드) · `jeus.management.`(JMX). 모두 generic `jeus.`(1003)보다 낮은 priority → 구체 prefix 우선 매칭.
- **Fallback(regex) +10 (pri 4920~4929):** 웹 세션 · 요청/응답 스트림 · EJB · JDBC 풀 · JTA · JMS · JCA 커넥터 · 클래스로더(재배포) · 보안 컨텍스트 · 워커 스레드. 기존 generic fallback(≥5001) 및 WebLogic/Tomcat(4900~4914)과 키워드 상호배타, JEUS 텍스트에 특화 설명/조치 제공.
- explanationTpl 은 `{#if streamClass}`/`{#if hasAccumulator}` 등 컨텍스트 플래그 활용. severityHint=null(점유율 기반 자동).

**적용 방식:** 기존 시드(91/58 도달 전 81/48) 상태라 JSON 추가 + 운영 DB(`192.168.56.9/HEAPDB`)에 `INSERT ... WHERE NOT EXISTS`(prefix/name idempotent) 직접 보강.
- **검증:** 빌드+재기동 정상(14.2s), seeder 91/58 "already present" 스킵. DB 정규식 단일 백슬래시 검증(`'jeus.jdbc.Pool' REGEXP=1`, `'jeusXjdbc'=0`). 로그인 후 `/api/admin/leak-rules/{library,fallback}` 가 91/58 반환, JEUS 신규 10/10 priority·category 노출 확인.

## [2026-05-31] Leak Suspect 룰 고도화 — WebLogic/Tomcat 룰 보강 (library 66→81, fallback 33→48)

**배경:** WAS(WebLogic/Tomcat) 환경 누수 진단 정밀도 향상 요청. 기존엔 generic `weblogic.`(pri 1023) / `org.apache.catalina.`(1004) / `org.apache.tomcat.`(1005) prefix 룰만 존재.

**추가 (`resources/leak-rules/library-rules.json` + `fallback-rules.json` + 운영 DB INSERT):**
- **Library(prefix) +15:** WebLogic 10 (`weblogic.jdbc.`/`servlet.`/`ejb.`/`jms.`/`work.`/`kernel.`/`rjvm.`/`cluster.`/`security.`/`transaction.`, pri 901~910), Tomcat 5 (`org.apache.catalina.session.`/`loader.`/`core.`/`org.apache.coyote.`/`org.apache.tomcat.util.net.`, pri 911~915). 모두 generic 룰(≥1004)보다 낮은 priority → 구체 prefix가 먼저 매칭.
- **Fallback(regex) +15:** WebLogic 10 (JDBC풀/세션/EJB/JMS/Work Manager/Stuck Thread/RJVM/세션복제/보안/WLDF, pri 4900~4909), Tomcat 5 (세션매니저/WebappClassLoader/Coyote/NIO Poller/JDBC Pool, pri 4910~4914). 기존 generic fallback(≥5001)보다 먼저 평가되어 WAS 특화 설명·조치 제공.
- 각 룰 explanationTpl/adviceTpl 는 템플릿 엔진 placeholder(`{simpleClassName}`/`{instanceCount|instances}`/`{percentage}`/`{bytes|bytes}`/`{#if hasAccumulator}`) 활용, severityHint=null(점유율 기반 자동).

**적용 방식:** seeder 는 테이블 비었을 때만 시드하므로(이미 66/33 시드됨) JSON 추가 + 운영 DB(`192.168.56.9/HEAPDB`)에 `INSERT ... WHERE NOT EXISTS`(prefix/name 기준 idempotent)로 직접 보강. 신규 환경(빈 DB)은 JSON 시드로 동일 결과.
- **검증:** 빌드+재기동 정상(13.5s), seeder 81/48 "already present" 스킵 확인. DB 정규식 단일 백슬래시 저장 검증(`'weblogic.jdbc.Foo' REGEXP=1`, `'weblogicXjdbc'=0`). 로그인 후 `/api/admin/leak-rules/{library,fallback}` API 가 81/48 반환, 신규 WebLogic 10·Tomcat 5 각각 노출 확인.

## [2026-05-31] PDF Report Preview — AI 인사이트 요약 잘림 개선

**배경:** `oom-test.hprof` PDF Report Preview 에서 AI 인사이트 요약이 끝부분이 잘려 표시됨. 실제 요약은 245자인데 `SUMMARY_MAX_CHARS=220` 데이터 클립에 걸려 219자 + "…" 로 절단되던 것이 원인(시각적 `ai-box max-height:68mm` 가 아님 — 245자는 box 안에 충분히 들어감).
**수정 (`service/PdfReportService.java`):** `SUMMARY_MAX_CHARS` 220→600, `RECOMMEND_MAX_CHARS` 560→700 으로 상향. 일반적 AI 요약/권장조치 전문을 수용하면서도 본문 분량(요약 600 + 권장 700 ≈ 최악 45mm < 68mm)이 단일 A4 페이지 `ai-box` 안에 들어가 페이지 오버플로 없음.
- **검증:** 빌드+재기동 정상(13.1s). 로그인 후 `/analyze/oom-test.hprof/print-pdf` 생성 → `pdftotext` 추출 결과 요약 마지막 문장("…메모리를 소진시킨 것이 명확합니다.")까지 완전 표시, 권장 조치 1~3번 전문 표시, 푸터 "Page 1 / 1" 유지(단일 페이지 보존).

## [2026-05-31] 분석화면 Overview — Leak Suspects 요약 설명 3줄 클램프 제거

**배경:** `oom-test.hprof` Overview 에서 Suspect #2 분석 설명이 3줄까지만 표시되고 나머지가 잘리는 문제.
**수정 (`static/css/analyze.css`):** `.leak-summary-desc` 의 `display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden` 제거 → 전체 텍스트 표시. `word-break:break-word; overflow-wrap:anywhere` 추가로 긴 클래스명/토큰 줄바꿈 보장. 카드는 `flex:1` 로 우측 컬럼 높이에 맞춰 확장되므로 길이 증가 수용.
- 캐시 키 `?v=2026-05-31d`→`2026-05-31e`(css).
- **검증:** `mvn clean package` + 재기동 정상(12.9s).

## [2026-05-31] 분석화면 Overview — OOM/Leak Suspects 진단 카드 글자 크기 확대

**수정 (`static/css/analyze.css`):** Overview 좌측 진단 컬럼(OOM·Leak Suspects)의 가독성 향상 위해 본문 폰트를 약 1~1.5px씩 상향.
- **OOM 배너/링크:** `.oom-banner` 13→14px, `.oom-banner-type` 12→13px, `.oom-banner-more`/`.oom-link` 11.5→12.5px.
- **진단 카드 공통:** `.diag-head-title` 14→15px, `.diag-head-icon` 16→17px, `.diag-kind-label` 15→16px, `.diag-kind-raw` 11→12px, `.diag-row` 12.5→13.5px, `.diag-row-key` 11→12px.
- **Leak Suspects 요약:** `.leak-summary-title` 13→14px, `.leak-summary-desc` 12→13px, `.leak-kw-chip`/`.leak-kw-more` 11→12px, `.leak-view-all` 12.5→13.5px.
- 캐시 키 `?v=2026-05-31c`→`2026-05-31d`(css).
- **검증:** `mvn clean package` + 재기동 정상(13.3s).

## [2026-05-31] 분석화면 Overview — 하단 배치 재조정 + Leak 요약 분석설명 추가

**수정 (`templates/analyze.html` Overview 패널 + `static/css/analyze.css`):**
- **하단 행 = [Memory Treemap(½)] [Top Memory Consumers(½)]:** `chart-row` 우측 셀을 Biggest Objects(Pie)→Top Memory Consumers(Bar)로 교체. 기존 전폭 Bar 블록 제거.
- **Biggest Objects(Pie) 우측 컬럼 이동:** `ov-metrics`(KPI + Heap Composition) 하단에 Pie 차트박스 추가 → 우측 컬럼이 길어짐. canvas ID(`pieChart`) 불변이라 `initCharts()` 그대로 동작.
- **Leak Suspects 세로 확장:** 우측 컬럼이 길어진 만큼, `align-items:stretch` + `.ov-diag > .card:last-child { flex:1 }` 로 좌측 Leak 카드가 하단 Treemap 행 직전까지 확장.
- **Leak 요약에 분석 설명 추가:** `.leak-summary-desc`(`s.explanationBody`, 없으면 `s.description`) 3줄 클램프로 상위 2건 각각에 표시.
- 캐시 키 `?v=2026-05-31b`→`2026-05-31c`(css).
- **검증:** 재기동 정상. `jeus_admin_20260517.hprof` 200 — pieChart 가 `chart-row` 보다 앞(우측 컬럼 내부), 하단행 treemap→barChart 순, `leak-summary-desc` 실제 텍스트("byte[] 25,826개 인스턴스가 힙의 51.17%…") 렌더, 서빙 CSS 규칙 확인. Thymeleaf 오류 0.

## [2026-05-31] 분석화면 Overview — 레이아웃 미세 조정 (좌우 컬럼 교체 · 차트 재배치 · Leak 톤 변경)

**배경:** 상단 2단 재배치 후속 조정 요청. 진단(OOM/Leak)을 좌측으로, 지표(KPI/Heap)를 우측으로 옮기고, 하단 시각화 배치 변경 및 Leak 카드 색조·높이 조정.

**수정 (`templates/analyze.html` Overview 패널 + `static/css/analyze.css`):**
- **좌우 컬럼 교체:** `.overview-top` 첫 컬럼 = `ov-col ov-diag`(OOM + Leak 진단), 둘째 컬럼 = `ov-col ov-metrics`(KPI 2×3 + Heap Composition). 기존 `.ov-left/.ov-right` → 공용 `.ov-col` 로 통합.
- **하단 시각화 재배치:** `chart-row` = [Memory Treemap(가로 절반)] + [Biggest Objects(Pie)]. **Top Memory Consumers(Bar)** 는 기존 Treemap 자리(전폭)로 이동. Treemap JS(`buildTreemap`)는 `container.offsetWidth` 동적 측정이라 절반 폭 자동 적응.
- **Leak Suspects 푸른 파스텔톤:** `.leak-summary-card` 배경 `linear-gradient(#eff6ff→#fff)` + border `#bfdbfe`, 헤더/카운트/키워드 chip/전체보기 링크 모두 blue 계열(#1e40af/#dbeafe/#1d4ed8)로 통일(기존 amber 대체).
- **Leak 카드 세로 확장:** `.overview-top { align-items:stretch }` + `.ov-diag > .card:last-child { flex:1 1 auto }` → 진단 컬럼의 마지막 카드(Leak 요약/정상)가 우측 컬럼 높이에 맞춰 빈 공간을 채움.
- 캐시 키 `?v=2026-05-31a`→`2026-05-31b`(css).
- **검증:** 재기동 정상. `oom-test.hprof`/`jeus_admin_20260517.hprof` 200 — `ov-diag` 가 `ov-metrics` 보다 먼저 렌더(진단 좌측), 하단 Treemap→Biggest Objects→Top Memory Consumers 순, 서빙 CSS 에 파란 파스텔·flex 확장 규칙 확인. Thymeleaf 오류 없음.

## [2026-05-31] 분석화면 Overview — 상단 2단 재배치 + OOM/Leak 진단 카드

**배경:** Overview 패널이 KPI → Heap Composition → Charts → Treemap 단일 세로 흐름이었고, OOM 은 한 줄 배너로 타입 문자열만, Leak Suspects 는 별도 패널에만 노출되어 "왜 OOM 이 났는지 / 어떤 누수가 의심되는지" 진단 정보가 한눈에 안 들어옴. 상단을 2단으로 재배치해 좌측에 지표, 우측에 진단을 모음.

**수정:**
- **`util/OomDetector.java`**: `OomKind` enum 각 상수에 `cause`(원인 1줄)·`recommendation`(권장조치 1줄) 한국어 필드 + `cause()`/`recommendation()` getter 추가(11종 + UNKNOWN). 생성자 4-인자로 확장.
- **`controller/HeapDumpViewController.java`** (`analyzeResult`): 기존 `oomFirstType` 산출 뒤 `OomDetector.classifyMessage(oomFirstType)` 로 `OomKind` 역매핑 → 모델 속성 `oomKindLabel`/`oomCause`/`oomRecommendation` 추가(oomCount>0 시).
- **`templates/analyze.html`** (Overview 패널): 기존 OOM 한 줄 배너 제거. `.overview-top` 2단 그리드 신설 — **좌(`.ov-left`)** = KPI 바(2×3) + Heap Composition 카드, **우(`.ov-right`)** = OOM 진단 카드(종류 라벨·원문·원인·권장조치·스레드 chip, `goToOomThread` 재사용) + Leak Suspects 요약 카드(상위 2건 심각도 배지 + 키워드 chip 상위 6개 + "전체 보기 →" `goToSuspects`). OOM/Leak 미감지 시 각각 초록 정상 상태 카드. Charts/Treemap 은 2단 아래 전폭 유지. 캐시 키 `?v=2026-05-30d`→`2026-05-31a`(css), `?v=2026-05-30a`→`2026-05-31a`(js).
- **`static/css/analyze.css`**: `.overview-top`(grid 1fr 1fr) / `.ov-left`·`.ov-right`(flex column) / 좌측 `.kpi-bar` 2열 그리드화 + 경계선 보정 / `.diag-card`·`.diag-danger`·`.diag-head*`·`.diag-kind*`·`.diag-row*`·`.diag-chips*` / `.leak-summary-card`·`.leak-summary-item*`·`.leak-kw-chip`·`.leak-view-all` / `.diag-ok*`(정상 상태) 신설. `@media(max-width:900px)` 1단 세로 전환(KPI 2열 유지).
- **`static/js/analyze.js`**: `goToSuspects()` 추가 — Leak 요약 "전체 보기" → `showPanel('suspects', navBtn)`.
- **검증:** 빌드(jar) + 재기동 12.6s 정상. 로그인 후 `oom-test.hprof`(OOM=Java heap space) 페이지 200 — OOM 진단 카드(diag-danger·종류 라벨·원문·원인·권장 2 row·스레드 chip) 정상. `jeus_admin_20260517.hprof`(OOM 없음·Leak 있음) — OOM 정상 상태 카드 + Leak 요약 카드 정상. Thymeleaf/SpEL 오류 없음.

## [2026-05-30] Dominator Tree — 클래스명에 섞인 MAT 배열 내용 미리보기(점 나열) 제거

**배경:** Dominator Tree 에서 `byte[262144] ........................` 처럼 클래스/객체명 뒤에 점이 수백 개 표시되는 현상 신고. 원인 점검 결과 MAT(Eclipse MAT) 원본 HTML(`*_Query.zip` 의 `index.html`)이 배열 객체를 `byte[262144] @ 0x... <내용 미리보기>` 형태로 렌더링하며, 출력 불가 바이트(256KB 배열을 채운 0x00 등)를 `.` 로 표기한 것. 파서가 `@ 0x...` 주소만 제거하고 그 뒤 내용 미리보기는 그대로 `className` 에 저장 → `result.json` 에 점 포함 문자열 영속화. 데이터 정합성(수치/주소)은 정상, 표시만 비정상.

**수정:**
- **`parser/MatReportParser.java`**: `ARRAY_CONTENT_PREVIEW_PATTERN`(`\s*\.{2,}.*$`, DOTALL) 신설 + `extractCleanClassName()` 에 적용. 클래스/객체명은 연속 점(`..`)을 포함하지 않으므로(패키지 구분자는 단일 점) 2개 이상 점부터 절단 → `byte[262144] ....` → `byte[262144]`.
- **`service/HeapDumpAnalyzerService.java`**: `hasDominatorNamePreviewArtifact()` 추가 + restore 경로의 dominator 재파싱 조건 보강. 기존 `result.json` 의 className 에 `..` 잔존 시(구버전 파서 산출물) Query ZIP 에서 자동 재파싱 → 기존 분석도 재실행 없이 자가 치유.

## [2026-05-30] Dominator Tree — Shallow/Retained Heap 인라인 바 차트

**배경:** Eclipse MAT 의 Dominator Tree 뷰처럼 메모리 사용량을 막대로 시각화하는 요청. 숫자만으로는 상위 객체 간 비중 차이가 직관적으로 보이지 않음.

**수정:**
- **`templates/analyze.html`** (Dominator Tree 패널 테이블): Shallow Heap / Retained Heap `<td>` 를 `dom-bar-cell` 구조로 변경. 셀 내부에 `dom-bar-fill`(막대, `data-val` 에 raw 바이트) + `dom-bar-val`(숫자 텍스트, 우측 정렬) 중첩. 막대는 텍스트 뒤(z-index 0)에 배치.
- **`static/css/analyze.css`**: `.dom-bar-cell`(position:relative) / `.dom-bar-fill`(absolute, width transition) / `.dom-bar-shallow`(연한 블루 그라데이션) / `.dom-bar-retained`(진한 블루 그라데이션) / `.dom-bar-val`(z-index 1) 신설. hover 시 텍스트 primary 색. 캐시 키 `?v=2026-05-30c` → `?v=2026-05-30d`.
- **`static/js/analyze.js`**: `renderDomBars()` 추가 — shallow/retained 각 컬럼의 최댓값을 100%로 잡아 막대 너비(%) 산정(0이 아닌 값은 최소 2% 가시성 보장). `showPanel('dominator-tree')` 진입 시 호출(idempotent) → width transition 애니메이션 트리거. 캐시 키 `?v=2026-05-29d` → `?v=2026-05-30a`.

## [2026-05-30] 분석 사이드바 — Dominator Tree 뱃지 제거 + Thread Overview OOM 뱃지 추가

**수정 (`templates/analyze.html` 좌측 Actions 네비게이션):**
- **Dominator Tree** nav-item 의 항목 수 뱃지(`nav-badge`, `dominatorTreeEntries` 크기) 제거.
- **Thread Overview** nav-item 에 `oomThreadCount > 0` 일 때 `⚠ OOM` 뱃지(`nav-badge-oom`) 추가 — `title` 에 OOM 타입(`oomFirstType`) 노출. (Overview 패널 KPI 영역 OOM 배지/배너와 동일 데이터.)
- **`static/css/analyze.css`**: `.nav-item .nav-badge-oom` 스타일 신설(danger 배경, nowrap). 캐시 키 `?v=2026-05-30b` → `?v=2026-05-30c`.

## [2026-05-30] OOM 감지 보강 — Direct buffer memory / unable to create new native thread

**배경:** 두 종류는 동작이 다름:
- **Direct buffer memory** — `java.nio.Bits.reserveMemory()` 에서 Java `new OutOfMemoryError("Direct buffer memory")` 로 throw → 스택에 `OutOfMemoryError.<init>` 프레임 존재 → 기존 `detect()` 가 이미 감지(+OQL 정확값).
- **unable to create new native thread** — `java.lang.Thread.start0()` (네이티브 메서드)에서 **VM 이 직접 throw** → 스택에 `OutOfMemoryError.<init>` 프레임이 **없음** → 기존 `detect()` 가 **놓침**. 또한 이 두 메시지는 preallocated 가 아니라 throw 시점에 동적 생성되어, 힙에 존재하면 곧 실제 발생을 의미.

**수정:**
- **`util/OomDetector.java`**: `hasOomProneFrames(stack)` 추가 — 네이티브 스레드 생성(`Thread.start0`/`Thread.start(`/`ThreadPoolExecutor.addWorker`) 또는 다이렉트 버퍼(`Bits.reserveMemory`/`DirectByteBuffer.<init>`/`getTemporaryDirectBuffer`) 프레임 존재 여부. (`<init>` 없는 OOM 의 사전 필터.)
- **`service/HeapDumpAnalyzerService.java`**:
  - 분석 플로우 트리거 확장: `oomThreadCount > 0` **또는** `hasOomProneThread(result)` 일 때 `enrichThrownOomMessage` 실행 → `<init>` 프레임 없는 종류도 OQL 검증 대상.
  - `enrichThrownOomMessage`: 스레드별 local 파싱(`parseThreadLocalsByThread`)으로 변경. 메시지 추출은 전역 합집합(uncaughtException 핸들러 보유분 포함), **스레드 플래깅은 OOM-prone 프레임 + OOM 인스턴스 local 참조** 동시 충족 시에만 oom=true 설정 → native thread 스레드를 잡으면서 핸들러 스레드 과다표시 방지.
  - `detectOomInThreads`: 스택에 `<init>` 가 없어도 이미 oom=true 인 스레드(enrich/영속화 출처)는 표시 유지(leave-as-is) → 재실행/디스크 복원 시 native thread OOM 표시 보존.
- **검증:** OomDetector 단위테스트(native-thread: detect=false·prone=true / direct-buffer: detect=true·prone=true / 분류 정확). oom-test(heap space) 재분석 회귀 없음 — OOM 스레드 1개 유지, 핸들러 과다표시 없음, `oomDetailMessage="Java heap space"` 영속.

## [2026-05-30] OOM 종류 정밀 추출 — 힙의 OutOfMemoryError detailMessage 기반 (GC overhead 등 정확 식별)

**배경:** 직전 작업(스택 시그니처 기반 "(추정)")으로는 `GC overhead limit exceeded` vs `Java heap space` 를 구분 불가 (둘 다 힙 고갈, throw 시점 스택 동일). 정확히 알 수 있는 유일한 출처는 힙 안의 OutOfMemoryError 객체의 `detailMessage` 필드. 사용자 요청으로 MAT OQL 추출 방식 도입.

**핵심 난제 — preallocated 인스턴스:** JVM 은 OOM 발생 시 추가 할당을 피하려 OutOfMemoryError 인스턴스들을 **미리 만들어둔다**. 실제 힙 덤프를 OQL 로 조회하면 `Java heap space` / `GC overhead limit exceeded` / `Metaspace` / `Compressed class space` / `Requested array size exceeds VM limit` / `C heap space` 등 **12종이 항상 존재** (실제 발생 여부 무관). 따라서 단순 인스턴스 메시지 읽기는 무의미.

**해결 — 스레드 local 참조 교집합:** 실제 throw 된 OOM 은 스레드 스택의 local 변수로 참조됨(특히 `ThreadGroup.uncaughtException` 처리 스레드). OQL 로 얻은 `(OOM 인스턴스 주소 → detailMessage)` 맵과 `.threads` 의 `objectId=0x...` local 주소 집합의 **교집합**을 구해 preallocated 템플릿을 배제하고 실제 메시지만 추출. (oom-test 검증: 12개 중 thread-referenced 2개 → 둘 다 `Java heap space` → GC overhead 오탐 방지 확인.)

**구현:**
- **`model/HeapAnalysisResult.java`**: `oomDetailMessage` 필드 신설 (result.json 영속). null 이면 미추출 → `(추정)` 폴백.
- **`service/HeapDumpAnalyzerService.java`**:
  - `detectOomInThreads(result)` 로 시그니처 변경 — `oomDetailMessage` 존재 시 OOM 스레드 `oomType` 에 정확값(추정 마커 없음) 적용, 없으면 `OomDetector` 추정값.
  - `enrichThrownOomMessage()` 신설 — OOM 감지 시에만 실행. 격리된 임시 디렉토리에 덤프 심볼릭 링크 + 인덱스 복사 → `runMatSingleQuery` 로 OQL(`SELECT o.@objectAddress, toString(o.detailMessage) FROM java.lang.OutOfMemoryError o`) 실행 → `_Query.zip` 의 HTML 표 파싱 → 스레드 local 주소와 교집합 → 최빈 메시지를 `oomDetailMessage` 에 저장 후 `detectOomInThreads` 재적용. (기존 `_Query.zip`/인덱스 비파괴.)
  - 헬퍼 `parseThreadLocalAddresses` / `runOomDetailQuery` / `parseOomQueryZip` 추가.
  - 분석 플로우(buildResult 직후)에 `oomThreadCount > 0` 조건부 호출 + "OOM 종류 정밀 분석 중..." SSE 진행 단계.
  - `cloneWithoutLog` 에 `oomDetailMessage` 복사 추가 (영속화 누락 수정).
- 인덱스 재사용으로 추가 MAT 호출 ~2-5초, **OOM 감지 시에만** 발생. 임시 디렉토리는 추출 후 자동 삭제.
- **검증:** oom-test 실제 재분석 → 로그 `Exact OOM type from heap: 'Java heap space'` + result.json `oomDetailMessage: "Java heap space"` 영속 + 스레드 `oomType` 정확값(추정 마커 제거) 확인.

## [2026-05-30] OOM 감지 로직 고도화 — 종류 추정(subtype inference) + 전용 유틸 분리

**배경:** 기존 OOM 감지는 `HeapDumpAnalyzerService.detectOomInThreads` 의 단일 정규식(`java.lang.OutOfMemoryError(?::\s*(메시지))?`)으로 스레드 스택에서 OOM 등장 여부만 판정. **핵심 한계:** 실제 MAT 스레드 덤프는 OOM 을 `at java.lang.OutOfMemoryError.<init>()` **프레임 형태로만** 표기하고 "Java heap space" 같은 메시지를 포함하지 않아, 대부분의 실제 케이스에서 `oomType` 이 null → OOM 종류(힙/메타스페이스/네이티브 스레드/다이렉트 버퍼 등)를 구분 못 함.

**신규 `util/OomDetector.java`:**
- `OomKind` enum — JVM 표준 메시지 + 한국어 라벨 12종 (HEAP_SPACE / GC_OVERHEAD / METASPACE / COMPRESSED_CLASS_SPACE / PERMGEN / DIRECT_BUFFER / NATIVE_THREAD / ARRAY_SIZE / OUT_OF_SWAP / NATIVE_MEMORY / KILL_PROCESS / UNKNOWN).
- `detect(stack)` 3단계 판정:
  1. **명시적 메시지** 존재 시 원문 신뢰 (`inferred=false`). `classifyMessage()` 로 종류 분류 — GC overhead / Metaspace / Compressed class / PermGen / Direct buffer / native thread / array size / swap / kill process / native memory / heap space 부분일치.
  2. 메시지 부재 시 **스택 프레임 시그니처로 추정** (`inferred=true`):
     - `Thread.start0`/`Thread.start(`/`ThreadPoolExecutor.addWorker` → 네이티브 스레드 생성 실패
     - `Bits.reserveMemory`/`DirectByteBuffer.<init>`/`getTemporaryDirectBuffer` → 다이렉트 버퍼 메모리 부족
     - `ClassLoader.defineClass`/`defineClass1·2`/`Proxy`/`instrument`/`reflect` → 메타스페이스 부족
  3. 특이 시그니처 없으면 실무상 절대다수인 **`Java heap space` 로 추정** — 모든 경우 `(추정)` 마커로 불확실성 명시.
- `HeapDumpAnalyzerService.detectOomInThreads` 는 이 유틸에 위임하도록 리팩토링 (정규식/분기 제거). 추정 건수도 로그에 기록.

**적용 범위:** `loadThreadStacksText → matchThreadStackTraces → detectOomInThreads` 는 result.json 디스크 복원 경로(라인 2137)에서도 호출되므로 **기존 분석 결과도 재분석 없이 다음 조회 시 새 종류 라벨 자동 적용** (앱 재시작으로 인메모리 캐시 초기화됨).

**효과:** Heap Statistics OOM 행 / Thread Overview OOM 배지·배너 / LLM 컨텍스트(`getOomContextSummary`) 모두 `oomType` 을 공유하므로, 종래 "감지됨(종류 불명)" 이던 케이스가 "Java heap space (추정)" / "네이티브 스레드 생성 실패 (추정)" 등 구체적 종류로 표시됨.

## [2026-05-30] Heap Statistics Objects 중복 제거 + OOM 상태/배지 표기

**배경:** 분석 페이지 좌측 사이드바 Heap Statistics 에 `Objects` 항목이 두 번 중복 표시됨. 또한 OOM 감지 여부를 사이드바와 Thread Overview 제목에서 한눈에 알기 어려웠음.

**수정:**
- **`templates/analyze.html`** Heap Statistics (라인 148-151): 중복된 두 번째 `Objects` 행 제거 → 그 자리에 `OOM` 상태 행 추가. `oomThreadCount > 0` 이면 OOM 타입(`oomFirstType`, 없으면 "감지됨")을 빨강으로, 0 이면 "없음"을 초록으로 표시. `title` 에 감지 스레드 수 노출. (모델 속성 `oomThreadCount`/`oomFirstType` 는 `HeapDumpViewController` 가 기존에 노출하던 값 재사용.)
- **`templates/analyze.html`** Thread Overview 카드 제목 (라인 857): `oomThreadCount > 0` 일 때 `⚠ OOM` 배지(`oom-title-badge`) 추가 — 기존 OOM 배너/스레드 행 하이라이트와 별개로 제목에서 즉시 식별. `title` 에 OOM 타입 노출.
- **`static/css/analyze.css`**: `.stat-oom-yes`(danger)/`.stat-oom-no`(green) + `.oom-title-badge`(danger-light 배경 칩) 스타일 신설.
- `analyze.css` 캐시 키 `?v=2026-05-30a` → `?v=2026-05-30b`.

## [2026-05-30] Leak Suspects 관련 키워드 — 원본 MAT 리포트의 Keywords 표기로 변경

**배경:** 분석 페이지 Leak Suspects 의 키워드가 "관련 키워드" 라벨 + 파란색 칩(chip) 가로 나열 형식으로 표시되어 원본 MAT Leak Suspects Report 의 `Keywords` 섹션(제목 + 한 줄에 하나씩 세로 목록)과 형식이 달랐음.

**수정:**
- **`templates/analyze.html`** (라인 708-711): 라벨 텍스트 `관련 키워드` → `Keywords`, 키워드를 칩 `<span class="suspect-kw-chip">` 가로 나열 → `<div class="suspect-kw-line">` 세로 목록(한 줄에 하나)으로 변경.
- **`static/css/analyze.css`** `.suspect-keywords` 의 flex/wrap/chip 스타일 제거 → 블록 레이아웃. `.suspect-keywords-label` 은 일반 제목(13px), `.suspect-kw-chip` → `.suspect-kw-line` (monospace, 칩 배경 제거, 한 줄씩) 으로 교체.
- `analyze.css` 캐시 키 `?v=2026-05-29c` → `?v=2026-05-30a`.

## [2026-05-30] 분석 중 화면 이탈 경고 팝업 취소 시 로딩 스피너 무한 회전 버그 수정

**증상:** 분석 진행 중 화면(`/progress/{filename}`)에서 배너 등 다른 버튼/링크로 이탈을 시도하면 `beforeunload` 이탈 경고 팝업이 표시됨. 이 팝업을 **취소(머무르기)** 로 닫으면 페이지가 그대로 남는데, 화면 중앙 "페이지 로딩 중..." 스피너가 사라지지 않고 무한히 회전.

**원인 (이벤트 순서):**
1. 배너의 전역 네비게이션 핸들러 `handlePageNavClick`(`fragments/banner.html`)가 링크 클릭 시 `schedulePageLoading()` 으로 100ms 후 스피너 표시 타이머 예약.
2. 브라우저가 네비게이션 개시 → `beforeunload` 발화 → `progress.html` 핸들러가 `preventDefault()` → 경고 팝업 표시.
3. 사용자가 **취소** → 네비게이션 중단, 페이지 잔류.
4. 예약된 100ms 타이머가 발화 → 스피너 표시. 페이지가 언로드되지 않았으므로 **스피너를 숨기는 코드가 영영 실행되지 않음** → 무한 회전.

**수정 (`fragments/banner.html` — 공통 배너 init IIFE):**
- `beforeunload` 팝업을 취소하면 창이 포커스를 되찾으므로(=같은 페이지 잔류) 이를 스피너 제거 신호로 사용:
  ```javascript
  window.addEventListener('focus', hidePageLoading);
  window.addEventListener('visibilitychange', function() {
      if (!document.hidden) hidePageLoading();
  });
  ```
- 공통 배너 fragment 에 적용되어 동일한 이탈 경고를 가진 `compare.html`(AI 분석 진행 중 이탈 방어) 및 향후 추가될 모든 페이지에 자동 적용.
- 실제 네비게이션이 일어나는 정상 케이스에서는 페이지가 교체되며 새 배너가 로드되므로 영향 없음.

## [2026-05-29] AI 인사이트 권장 조치 — 최우선 3개로 정책 통일

**배경:** 분석 페이지 AI 인사이트의 "권장 조치" 영역이 LLM 응답을 자유 형식으로 받아 표시 (`recommendations` 필드, 단순 텍스트 `"1. ... 2. ... 3. ... 4. ..."`). LLM 이 4-6개 항목을 반환하는 경우가 잦고 우선순위가 모호해 사용자가 무엇부터 시도해야 할지 판단하기 어려움. **항목 수를 3개로 제한 + 최우선 조치 우선순위 강제** 정책 도입.

**3중 가드 구현 (서버 + 클라이언트):**
- **`service/LlmConfigService.java`** 분석용 system prompt (라인 491-497) 변경:
  추가 문구 — `"권장 조치(recommendations)는 효과/시급성 기준으로 가장 우선순위가 높은 최우선 조치 3개만 1. 2. 3. 순서로 제시하세요 (4개 이상 금지). OutOfMemoryError 가 감지된 경우 해당 OOM 종류에 직결되는 조치를 1순위로 두세요."`
  → LLM 호출 단계에서 1차 제어. OOM 우선순위 가이드 추가 (직전 OOM-LLM 컨텍스트 주입 작업과 연계).
- **`static/js/analyze.js`** `buildAnalysisPrompt` 의 JSON schema (라인 2395) 변경:
  기존: `"recommendations":"권장 조치(1. 2. 3. 번호 매기기)"` →
  변경: `"recommendations":"최우선 조치 3개만 우선순위 순서로 (정확히 1. 2. 3. 형식, 4개 이상 금지)"`
  → user prompt 의 응답 schema 명세에서 2차 제어.
- **`static/js/analyze.js`** `setNumberedList` (라인 2735 부근) 클라이언트 안전망:
  ```
  if (numbered.length > 3) numbered = numbered.slice(0, 3);
  ```
  → LLM 이 지시를 어기고 4개+ 반환했거나, 기존 저장된 인사이트(`AiInsightManager` 가 DB 에 영속화한 JSON) 가 이미 4-6개 항목을 가지고 있는 경우에도 UI 는 항상 첫 3개만 표시. **기존 인사이트도 일관 적용** — 별도 DB 마이그레이션 불필요.

**캐시 키:** JS 변경이므로 `analyze.js?v=2026-05-29c` → `?v=2026-05-29d`. CSS 미변경.

**부수 효과 — 비교 분석 (`/api/llm/compare/analyze`):** `LlmConfigService.callLlmAnalysis(prompt)` 공유 system prompt 사용 → 동일 정책 자동 적용 (호출자가 동일 schema directive 를 prompt 에 포함하는 경우). 별도 변경 없음.

**검증:**
- 컴파일 산출물: `javap -v LlmConfigService.class` constant pool 에 신규 system prompt 텍스트 (`"최우선 조치 3개만"`, `"4개 이상 금지"`, `"OutOfMemoryError 가 감지된 경우 해당 OOM 종류에 직결되는 조치를 1순위로"`) 정확히 포함 확인.
- 클라이언트 fetch: `curl http://localhost:18080/js/analyze.js?v=2026-05-29d` → 신규 schema 문자열 + truncate 코드 (`slice(0, 3)`) 모두 포함 확인 (grep count 2 = 주석 + 코드 라인).
- 페이지 렌더: `analyze.html` cache key `?v=2026-05-29d` 정상 적용. 200 OK.

**미스코프 (의도):** 저장된 인사이트의 DB 영속 데이터 자체는 변경 안 함 (클라이언트 truncate 로 일관 표시 충족). 비교 분석의 user prompt schema 도 별도 변경 안 함 (system prompt 으로 충분).

**변경 파일:** `service/LlmConfigService.java`, `static/js/analyze.js`, `templates/analyze.html` (JS 캐시 키)

---

## [2026-05-29] Overview 패널에 OOM 감지 배너 추가 + Thread 패널 점프

**배경:** 직전 작업으로 Thread Overview 탭 (panel-threads) 에 OOM 배너/행 강조가 추가되었지만 분석 페이지 첫 진입 시 default 활성 패널은 `panel-overview` (KPI Bar + 차트). 사용자가 OOM 여부를 확인하려면 좌측 사이드바의 Thread Overview nav-item 을 직접 클릭해야 했음. 분석 진입 즉시 OOM 인지를 위해 Overview 패널에도 표시.

**구현 (UI 만 — 백엔드/모델/컨트롤러 무변경):**
- `templates/analyze.html` `panel-overview` (라인 513) KPI Bar 직전에 신규 배너 블록 삽입. Thread Overview 배너 (라인 841-852) 의 마크업을 그대로 복제하되 wrapper 에 `oom-banner-overview` 추가 클래스, chip `<a>` 의 `onclick` 만 `scrollToOomThread` → `goToOomThread` 로 차별화. `th:if="${oomThreadCount > 0}"` 가드로 OOM 미감지 시 DOM 자체 미생성.
- `static/js/analyze.js` 신규 헬퍼 `goToOomThread(idx)` (라인 ~349 — `scrollToOomThread` 직후). 내부: `showPanel('threads', navBtn)` 으로 패널 전환 + 사이드바 Threads 활성화 → `requestAnimationFrame` 후 `scrollToOomThread(idx)` 호출 (panel 활성화 직후 layout 완료 보장 — 즉시 scrollIntoView 시 비활성 패널 좌표로 계산되어 오작동 위험).
- `static/css/analyze.css` 1줄: `.oom-banner-overview { margin-bottom:20px; }` (기존 `.oom-banner` 블록 직후, 라인 ~802). KPI Bar 와의 간격 확보. base 색상/아이콘/내부 spacing 은 `.oom-banner` 그대로 cascade.
- CSS/JS 캐시 키 `?v=2026-05-29c` 일괄 갱신 (CSS+JS 모두 변경).

**미채택 옵션 (의도):**
- KPI 카드 7번째 추가 — KPI 격자에 갇혀 OOM 치명성 약화 + 7칸 격자가 768px 미만에서 줄바꿈 균형 깨짐 + 카운트만 노출 (이름/타입 전달 불가).
- 사이드바 nav badge — Threads nav-item 이 `th:if="${hasThreadOverview}"` 가드라 OOM dump 외 의미 없음 + 상단 배너가 이미 카운트/이름/타입 모두 노출 (정보 중복) + CLAUDE.md 함정 #8 (사이드바 DOM 복제) 회피.

**검증 (`oom-test.hprof`, oomThreadCount=1, sample=oom-worker-2):**
- Overview 페이지: `oom-banner-overview` 1건, `goToOomThread` onclick 1건, `scrollToOomThread` onclick 1건 (Thread Overview), `thread-row oom` 1건, `oom-badge` 1건, Thymeleaf literal `${oom` 누수 0건.
- Overview 배너 텍스트: `⚠ OutOfMemoryError 감지: 1 개 스레드 · oom-worker-2`.
- OOM 미감지 dump (`gbmbap1t_jeus_license.bin`/`wgdist_1_heapdump_20260326.hprof.gz`/`heap-analyzer_20260521.hprof`) 페이지 200 OK + `oom-banner`/`oom-banner-overview` 0건 — 빈 OOM 가드 정상.
- 캐시 키 적용 확인: CSS `oom-banner-overview` 1건, JS `function goToOomThread` 1건.
- 직전 작업 회귀 없음: Thread Overview 배너/행 강조/LLM 주입 모두 정상 유지.

**클릭 동선:**
- Overview 진입 → 즉시 빨간 배너 표시 → chip 클릭 → Thread Overview 패널 전환 + 사이드바 active 토글 + 해당 행으로 부드러운 스크롤 + 1.2s flash pulse.
- Thread Overview 패널 안의 동일 chip 도 정상 동작 (이미 활성 패널이므로 `scrollToOomThread` 직접 호출).

**변경 파일:** `templates/analyze.html`, `static/js/analyze.js`, `static/css/analyze.css`

---

## [2026-05-29] OOM 감지 → AI 인사이트/채팅 LLM 컨텍스트 자동 주입

**배경:** 직전 변경으로 `ThreadInfo.oom`/`oomType` 로 OOM 스레드를 감지·표시했지만 LLM 호출 (분석 인사이트, 분석 페이지 채팅) prompt 에는 OOM 정보가 들어가지 않아 진단 품질에 한계. heap vs metaspace OOM 처방이 다르고 OOM 을 던진 스레드 컨텍스트가 누수 원인 추론의 핵심 단서이므로 자동 주입 필요.

**구현:**
- `model/HeapAnalysisResult.java` — `@JsonIgnore` transient getter 4개 추가: `getOomThreadCount()`, `getOomFirstType()`, `getOomThreadNames(int limit)`, `getOomContextSummary()`. 모두 `threadInfos` 1회 stream 집계. `getOomContextSummary()` 가 LLM prompt 용 텍스트 블록 (`== OutOfMemoryError 감지 ==\n감지된 스레드: N개 (Java heap space)\n- name1\n- name2\n...(외 M개)\n`) 을 반환하고 count==0 이면 `""` 반환 (단일 소스 진실).
- `service/HeapDumpAnalyzerService.java` — `public String buildOomPromptSection(String filename)` 헬퍼 추가. `getCachedResult(filename)` (cache + disk 복원 자동) 활용해 `r.getOomContextSummary()` 반환. filename 누락 / result 미존재 / OOM 0 케이스 모두 `""` 반환.
- `controller/HeapAiApiController.java` 3 곳 보강 (각 1줄):
  - `analyzeLlm` (라인 121-188) — `callLlmAnalysis()` 호출 직전 prompt 의 첫 `\n== ` 위치 직전에 OOM 섹션 splice. 폴백: 첫 `==` 없으면 끝에 append. 마지막 JSON 스키마 지시문이 prompt 끝에 그대로 남아 "JSON 응답" framing 보존.
  - `aiChat` (라인 414-454) — RAG 주입 직후 `systemPrompt += "\n\n" + oomChatSection` (RAG 와 같은 자리에 한 줄).
  - `aiChatStream` (라인 457-543) — 동일 패턴.
- `controller/AiChatController.java` `streamChat` (라인 305-467) — RAG 주입 직후 `opt.get().getFilename()` 으로 세션에 바인딩된 분석 파일명을 가져와 동일 패턴으로 systemPrompt 에 append.
- 모든 호출 지점에 `isEmpty()` 가드 → OOM 0 케이스에선 systemPrompt 변경 없음. **"감지된 스레드: 0개" 같은 잘못된 단정 정보가 LLM 에 전달되지 않음**.

**검증 (jeus_admin.hprof.gz 의 .threads 임시 변조 + 즉시 원복):**
- 첫 2개 스레드 블록에 `at java.lang.OutOfMemoryError: Java heap space` / `GC overhead limit exceeded` 합성.
- 앱 재기동 → 페이지 fetch → ThreadInfo.oom=true 2건 채워짐 확인 (`oom-banner=4` markup, `thread-row oom=2`).
- 3 경로 LLM 호출 모두 OOM injection 정확:
  - `POST /api/llm/analyze` → `[AI-Insight] OOM context injected: 104 char(s)` (`promptLen` 218 = 원본 114 + OOM 104).
  - `POST /api/llm/chat/stream` → `[AI-Chat-Stream] OOM context injected: 104 char(s)`.
  - `POST /api/ai-chat/sessions/17/stream` (세션 17 = filename binding=jeus_admin.hprof.gz) → `[AI-Chat-Stream] OOM context injected for session 17 (jeus_admin.hprof.gz): 104 char(s)`.
- .threads 원본 강제 복원 (`\cp -f`) → 앱 재기동 → 페이지 fetch → `oom-banner=0`/`thread-row oom=0`/`oom-badge=0` (UI 회귀 없음). 새 LLM stream 호출 → `OOM context injected` 로그 **0건** (빈 OOM 가드 정상 동작).

**역호환:** 신규 4개 getter 모두 `@JsonIgnore` transient → result.json 직렬화/역직렬화 0 영향. 기존 분석 페이지 200 OK (`jeus_admin.hprof.gz`, `heap-analyzer_20260521.hprof`, `wgdist_1_heapdump_20260326.hprof.gz`). OOM 미감지 케이스는 LLM 입력 변화 없음.

**클라이언트 무변경:** `analyze.js` `buildAnalysisPrompt()` (라인 2352-2392), `collectAnalysisData()`, 채팅 흐름 변경 없음. analyze.html 인라인 변수 추가 없음. CSS/JS 캐시 키 갱신 불필요. CLAUDE.md 함정 #4 (`th:onclick` 차단) 무관.

**미스코프 (의도):** PDF 리포트 OOM 별도 섹션 / 비교 분석(`/api/llm/compare/analyze`) OOM 주입 / OOM 별 권장 처방 데이터셋 — 별도 분리 작업.

**변경 파일:** `model/HeapAnalysisResult.java`, `service/HeapDumpAnalyzerService.java`, `controller/HeapAiApiController.java`, `controller/AiChatController.java`

---

## [2026-05-29] Thread Overview OOM 감지 — 배너 + 행 강조

**배경:** Thread Overview 탭은 스레드 이름/타입/heap/loader 만 평면 테이블로 나열했음. heap dump 가 OOM 으로 인해 생성된 경우 어떤 스레드가 `java.lang.OutOfMemoryError` 를 던졌는지(=trigger 스택) 가 진단 핵심이지만 사용자는 60+ 행을 직접 펼쳐야 확인 가능했음.

**구현:**
- `model/ThreadInfo.java` — `boolean oom` + `String oomType` 필드 추가. Lombok `@Data` 자동 getter/setter. `@JsonIgnoreProperties(ignoreUnknown=true)` 가 이미 있어 기존 result.json 역호환 안전.
- `service/HeapDumpAnalyzerService.java` — `static final Pattern OOM_PATTERN = "java\\.lang\\.OutOfMemoryError(?::\\s*([^\\r\\n]+))?"` (FQCN 강제로 본문 오탐 차단, 그룹1 로 메시지 추출). `private detectOomInThreads(List<ThreadInfo>)` 가 각 스레드의 stackTrace 를 1회 검사해 `oom=true` 와 `oomType` 설정 (idempotent). `matchThreadStackTraces()` 종료 직전 1줄 호출 — **신규 분석 + 캐시 복원(`loadThreadStacksText` 경로)** 양쪽 모두 자동 커버. 별도 lazy 보강 메서드 불필요.
- `controller/HeapDumpViewController.java` `analyzeResult()` (라인 414 `threadInfos` 주입 직후) — model attribute 4개 신규: `oomThreadCount` (int), `oomThreadSamples` (List<String>, 최대 3), `oomThreadIndices` (List<Integer>, 클릭 스크롤용), `oomFirstType` (String|null, 배너 부제목).
- `templates/analyze.html` panel-threads 상단 — `<div th:if="${oomThreadCount > 0}" class="oom-banner">` 신규. 내용: 경고 아이콘 + 카운트 + OOM 타입 + 샘플 chip(`onclick="scrollToOomThread(this.dataset.idx)"`, CLAUDE.md 함정 #4 준수) + `+N more`. 행 렌더에 `th:classappend="${t.oom} ? 'oom'"` 추가, Thread Name 셀 머리에 `<span th:if="${t.oom}" class="oom-badge" th:title="...">⚠</span>`. CSS/JS 캐시 키 `?v=2026-05-29b`.
- `static/js/analyze.js` — `scrollToOomThread(idx)` 신규: `querySelector('tr.thread-row[data-idx=...]')` + `scrollIntoView({block:'center', behavior:'smooth'})` + `.oom-flash` 클래스 1.2s 토글.
- `static/css/analyze.css` — `.oom-banner`/`.oom-banner-icon`/`.oom-banner-type`/`.oom-banner-sep`/`.oom-banner-more`/`.oom-link`(hover 포함)/`.thread-row.oom`(`box-shadow: inset 3px 0 0 var(--danger)` 로 컬럼 width 흔들림 회피)/`.thread-row.oom:hover`/`.oom-badge`/`.oom-flash` + `@keyframes oomFlash` (0/100% → danger-light, 50% → #fecaca, 3회 반복).

**검증 (jeus_admin.hprof.gz 의 .threads 임시 변조 + 즉시 원복 — 사용자 동의):**
- 첫 2개 스레드 블록에 `at java.lang.OutOfMemoryError: Java heap space` / `... GC overhead limit exceeded` 합성.
- 앱 재기동 (cache clear) → `/analyze/jeus_admin.hprof.gz` 페이지 fetch.
- 결과: `oom-banner` 1개 / `thread-row oom` 2개 / `oom-badge` 2개 / `oom-link` 2개 / Thymeleaf literal `${oom` 누수 0 / 로그 `[OOM] Detected OutOfMemoryError in 2 thread(s)`.
- 배너 텍스트: `⚠ OutOfMemoryError 감지: 2개 스레드 (Java heap space) · http1-5  selector.thread for listener 'BASE'`.
- .threads 원본 복원 → 앱 재기동 → 동일 페이지 fetch → OOM 표시 0건 확인. **사용자 데이터 무결성 보장**.

**역호환:** OOM 미포함 dump (`heap-analyzer_20260521.hprof`, `wgdist_1_heapdump_20260326.hprof.gz`, `gbmbap1t_jeus_license.bin`) 페이지 200 OK + OOM 요소 0건. 기존 result.json 의 ThreadInfo 가 신규 필드 미보유여도 캐시 복원 시 `loadThreadStacksText` → `matchThreadStackTraces` → `detectOomInThreads` 자동 재계산.

**미스코프 (의도):** PDF 리포트 OOM 별도 섹션 / LLM 컨텍스트 자동 주입 / 51 번째 이상 OOM 스레드 chip 표시 / 비-FQCN `OutOfMemoryError` 단독 키워드(오탐 위험).

**변경 파일:** `model/ThreadInfo.java`, `service/HeapDumpAnalyzerService.java`, `controller/HeapDumpViewController.java`, `templates/analyze.html`, `static/js/analyze.js`, `static/css/analyze.css`

---

## [2026-05-29] Leak Suspects 분석 설명 — 키워드 chip 분리 출력 (UI 정돈)

**배경:** 직전 변경(같은 날짜)에서 `LeakSuspectAdvisor` 의 explanation 본문 끝에 `"\n\n관련 키워드: A, B, C"` 라인을 합쳐 표시했으나, 본문 텍스트와 키워드 라인이 같은 단락/같은 스타일로 흐려져 시각적 정돈이 부족.

**개선:**
- `model/LeakSuspect.java` — UI 전용 `@JsonIgnore public String getExplanationBody()` 추가. `explanation` 문자열에서 `"\n\n관련 키워드:"` 또는 `"관련 키워드:"` 등장 지점 이전을 trim 해 반환. Jackson 무시이므로 result.json 직렬화/역직렬화에 영향 없음 (외부 텍스트 사용처 — PDF/LLM 컨텍스트 — 는 기존 `explanation` 그대로 사용해 정보 누락 없음).
- `templates/analyze.html` — 분석 설명 박스 내부 구조 분리: 본문 `<p class="suspect-explanation-body" th:text="${s.explanationBody}">` + `s.keywords` 비어있지 않을 때 별도 `<div class="suspect-keywords">` (라벨 1 + `<span class="suspect-kw-chip">` 반복). 본문에서 키워드 라인 제거되어 중복 표시 사라짐.
- `static/css/analyze.css` — `.suspect-explanation` 의 `white-space: pre-wrap` 제거 (키워드 라인이 본문에서 빠져 줄바꿈 의존 사라짐). 신규 `.suspect-explanation-body` (단락 margin 제거) / `.suspect-keywords` (점선 상단 구분선 + flex-wrap chip 정렬) / `.suspect-keywords-label` (uppercase 작은 라벨) / `.suspect-kw-chip` (mono 폰트 pill, `word-break:break-all` 로 긴 FQCN 줄바꿈) 추가. 캐시 키 `?v=2026-05-29a`.

**역호환:** 기존 result.json 에 `keywords` 필드 없거나 빈 리스트인 경우 `th:if="${!#lists.isEmpty(s.keywords)}"` 가 false → chip 영역 미출력 + 본문만 정상. 검증: `wgdist_1_heapdump_20260326.hprof.gz` (keywords 미보유) chip 0 / body 1 / 200 OK.

**검증 (신규 분석 케이스):**
- `heap-analyzer_20260521.hprof` Suspect#1: chip 3개 (`com.heapdump.analyzer.service.HeapAnalysisResultCache` / `org.springframework.boot.loader.launch.LaunchedClassLoader` / `java.util.concurrent.ConcurrentHashMap$Node[]`). Suspect#2: chip 1개 (`int[]`). 본문 영역의 `"관련 키워드:"` 텍스트 누수 0.

**변경 파일:** `model/LeakSuspect.java`, `templates/analyze.html`, `static/css/analyze.css`

---

## [2026-05-29] Leak Suspects MAT Keywords 추출 — 분석 설명에 자동 포함

**배경:** MAT Leak Suspects 리포트의 각 Problem Suspect 섹션에는 `<p><strong>Keywords</strong></p><ul><li>FQCN</li>...</ul>` 형태로 의심 클래스/클래스로더/배열의 **정확한 FQCN 목록**이 포함되어 있음. 기존 파서는 이를 별도 추출하지 않고 `stripTags(rawSection)` 시 전체 텍스트에 묻혀 들어가, LLM 컨텍스트나 사용자 시각으로 핵심 식별자가 잘 보이지 않았음.

**구현:**
- `model/LeakSuspect.java` — `List<String> keywords` 필드 추가 (Lombok `@Data` 자동 getter/setter, 기본값 빈 리스트). `@JsonIgnoreProperties(ignoreUnknown=true)` 가 이미 있어 keywords 미보유 result.json 역호환 OK.
- `parser/MatReportParser.java` — `KEYWORDS_BLOCK_PATTERN` (`<strong>Keywords</strong></p><ul>...</ul>` 추출) + `KEYWORD_LI_PATTERN` (`<li>...</li>` 추출) 추가. `parseSuspectsZip()` 의 problem 섹션 루프와 fallback 단일-suspect 경로 모두에서 `extractKeywords(rawHtml)` → `suspect.setKeywords(...)` → `LeakSuspectAdvisor.analyze(...)` → `appendKeywordsToExplanation(suspect)` 순서로 호출. Advisor 가 explanation 을 채운 뒤 그 끝에 `"\n\n관련 키워드: A, B, C"` 라인 한 줄 자연스럽게 합쳐짐 (이미 키워드 라인 있으면 중복 추가 안 함).
- `static/css/analyze.css` — `.suspect-explanation` 에 `white-space: pre-wrap` 추가. 분석 설명 본문은 한 단락이지만 keywords 라인이 빈 줄을 사이에 두고 별도 단락으로 보이도록 줄바꿈 보존. 캐시 키 `?v=2026-05-29`.

**미스코프 (의도적):**
- 기존 result.json (이번 변경 이전 분석) 자동 보강은 미적용. `reparseSuspectsStacktrace()` 의 조건(스택트레이스 페이지 누락)에 keywords 누락도 OR 로 묶을 수 있으나 explanation 자체는 이미 디스크에 저장되어 있어 lazy 보강만으로는 일관성이 깨짐 (keywords 는 채워지는데 explanation 끝에는 안 들어감). 일관성 유지 위해 **기존 분석은 재분석해야 keywords 노출**.

**검증:**
- `LeakSuspect.class` 컴파일 산출물에 `keywords` 필드 + getter/setter 존재 확인.
- `MatReportParser.class` 에 `KEYWORDS_BLOCK_PATTERN`, `KEYWORD_LI_PATTERN`, `extractKeywords`, `appendKeywordsToExplanation` 존재 확인.
- 재분석: `jeus_admin.hprof.gz` (Suspect#1 keywords=`["byte[]"]`, Suspect#2=`["int[]"]`) / `heap-analyzer_20260521.hprof` (Suspect#1 keywords=클래스+클래스로더+컬렉션 노드 배열 3개, Suspect#2=`["int[]"]`).
- explanation tail 에 `\n\n관련 키워드: ...` 정상 합쳐짐.
- `/analyze/{filename}` 렌더 HTML 에 `"관련 키워드: com.heapdump.analyzer.service.HeapAnalysisResultCache, org.springframework.boot.loader.launch.LaunchedClassLoader, java.util.concurrent.ConcurrentHashMap$Node[]"` 그대로 출력 + CSS pre-wrap 으로 분석 설명 본문과 빈 줄로 시각 분리.

**변경 파일:** `model/LeakSuspect.java`, `parser/MatReportParser.java`, `static/css/analyze.css`, `templates/analyze.html` (CSS 캐시 키)

---

## [2026-05-29] Dominator Tree Top 50 disclosure 클릭 무동작 버그 수정

**증상:** 2026-05-28 도입한 Dominator Tree 행 chevron 클릭 시 Top 50 행의 inbound/outbound 참조 disclosure 가 펼쳐지지 않음. 51 번째 이후 행의 Component Detail 모달도 열리지 않음 (양쪽 모두 클릭 무반응).

**원인:** `analyze.html` 의 `<tr class="dom-row" th:onclick="${s.index < 50} ? 'toggleDomDetail(this)' : 'showComponentDetail(...)'">` 가 Thymeleaf 3.1 restricted expression policy 에 의해 **OGNL 표현식이 평가되지 않고 리터럴 문자열 그대로 `onclick` 속성에 출력**됨 (CLAUDE.md 함정 #4). 브라우저는 `${s.index < 50} ? ...` 를 JS 로 실행하다 silent syntax error 로 실패 → 클릭 핸들러 전부 무동작. (`th:if` / `th:title` 등 비-이벤트 속성은 정상 평가되어 chevron 50 개 표시까지는 정상이라 증상이 발견 어려움.)

**수정:** suspects/threads 행이 이미 채택한 plain `onclick="fn(this.dataset.x)"` 패턴으로 통일.
- `analyze.html` — `th:onclick` 제거, `onclick="domRowClick(this)"` 고정
- `analyze.js` — 신규 `domRowClick(row)` 디스패처 추가: `parseInt(row.dataset.idx) < 50` 이면 `toggleDomDetail(row)`, 아니면 `showComponentDetail(row.dataset['class'])` 로 분기
- `th:title` 의 ternary 는 정상 평가되므로 유지 (Top 50 행은 "inbound/outbound 참조 펼치기", 그 외는 "클릭하여 상세 보기")

**검증:** `/analyze/heap-analyzer_20260521.hprof` 재렌더 — dom-row 500 / dom-chevron 50 / domRowClick 500 / 리터럴 `${s.index` 누수 0.

**변경 파일:** `templates/analyze.html`, `static/js/analyze.js`

---

## [2026-05-28] Dominator Tree disclosure — Top 50 incoming/outgoing 참조 lazy 조회

**핵심 UX:** Dominator Tree 탭의 Top 50 객체 행에 `▶` chevron 추가. 클릭 시 행 하단에 **Incoming references (Path to GC Roots)** + **Outgoing references (Retained Set)** 2분할 disclosure 펼침. 평면 테이블만이던 기존 UX → 객체별 inbound/outbound 참조 그래프 즉시 확인 가능. (51 번째 행 이후는 chevron 없이 기존 동작 유지 — 컴포넌트 모달.)

**MAT 쿼리 전략 — lazy on-demand REST endpoint:**
- 신규 `GET /api/dominator-refs/{filename}?address=0xADDR` 엔드포인트
- 클릭 시점 2 회 MAT CLI 호출: `path2gc 0xADDR` (incoming = GC root 까지의 경로) + `show_retained_set 0xADDR` (outgoing = 보유 객체 retained set)
- MAT 인덱스 (`.index`) 재사용 → hprof 재파싱 없이 보통 5~7초/회
- 압축된 `.hprof.gz` 만 있을 경우 `tmp/` 에 1회 압축 해제 후 캐시 (첫 클릭 ~15s, 이후 ~7s)

**2-pass MAT batch 사전 추출 시도 → 폐기 (설계 노트):** 처음에는 분석 시점에 Top 50 inbound/outbound 를 사전 추출하려 했으나 MAT batch CLI 가 동일 invocation 내 다중 `org.eclipse.mat.api:query` report 를 동일 파일명 (`<base>_Query.zip`) 으로 덮어쓰는 제약 발견. 50 객체 × 2 방향 = 100 회 독립 MAT 호출이 필요 (각 5초 → 8 분 추가) → 비현실적. lazy on-demand 로 전환. 사용자는 실제로 펼친 행만 비용 부담 (~7s/회) → 더 나은 trade-off.

**구현 파일:**
- 신규 `model/DominatorRefEntry.java` — 단순 POJO (className, objectAddress, shallow, retained + Human getter)
- `model/DominatorTreeEntry.java` — `incomingRefs`/`outgoingRefs` 필드 추가 (`@JsonInclude(NON_EMPTY)`, 추후 사전 추출 옵션을 위한 placeholder — 현재 lazy 모드에서는 미사용)
- `parser/MatReportParser.java` — `parseRefZipPublic()` (path2gc / show_retained_set HTML 테이블 파싱), `extractFirstHtmlFromZip()` (페이지명 무관 첫 HTML 추출)
- `service/HeapDumpAnalyzerService.java` — `runMatSingleQuery()` (단일 쿼리 헬퍼, ProcessBuilder + 데몬 reader thread), `resultDirectoryPublic()` (외부 노출), `getParser()` 활용
- `controller/HeapReportApiController.java` — `/api/dominator-refs/{filename}` endpoint. hprof 위치 자동 해소 (dumpfiles → tmp 압축 해제 → symlink 우선, copy 폴백), 호출 전후 dominator_tree Query.zip 백업/복원 (다른 페이지 영향 없음)
- `templates/analyze.html` — chevron 컬럼 추가, `s.index < 50` 조건부 렌더, 클래스명 셀 클릭은 기존 Component Detail 모달 유지
- `static/js/analyze.js` — `toggleDomDetail` AJAX 버전 (단일 open, addr 별 클라이언트 캐시, loading spinner, 에러 상태)
- `static/css/analyze.css` — `.dom-chevron` 회전 애니메이션, `.dom-refs-wrap` 2-column grid, `.dom-spinner` keyframe
- `config/HeapDumpConfig.java` — `mat.dominator-refs.enabled` 플래그 추가 (현재 미사용, lazy 모드에서는 항상 endpoint 가용)
- `application.properties` — 위 플래그 추가

**미스코프 (의도적):**
- Sort UI (MAT 가 retained 기준 사전 정렬)
- 재귀적 다단계 펼침 (참조의 참조)
- 51~500 entry chevron (운영 비용/UX 균형)
- 클래스 단위 histogram (객체별 raw 주소 결정)

## [2026-05-28] 재분석시 Detection 날짜 이동 방지 + 비밀번호 모달 외부 클릭 닫기 제거

**Detection 날짜 보존:** 기존 SUCCESS 분석 기록을 재분석하면 `analysis_history.analyzed_at`이 `now()`로 덮어써져 Dashboard/History의 14일 Detection chart가 오늘 버킷으로 이동하는 문제 해결. `HeapDumpAnalyzerService.saveAnalysisToDb()` 에서 기존 엔티티가 SUCCESS 이고 `analyzedAt != null` 이면 최초 분석일 보존. 다른 필드(suspect_count, heap 사용량 등)는 최신 결과로 계속 갱신. ERROR → SUCCESS, NOT_ANALYZED → SUCCESS 전이는 기존대로 `now()` 갱신.

**비밀번호 변경 모달 UX:** My Account 페이지(`account.html`)의 비밀번호 변경 모달에서 overlay 외부 클릭시 닫히던 동작 제거. 입력 중 실수로 모달이 닫혀 재입력을 강요당하는 문제 방지. 취소 버튼만으로 명시적 닫기. (`pwModal` overlay onclick 제거)

**변경 파일:** `HeapDumpAnalyzerService.java` (saveAnalysisToDb 분기), `account.html` (pwModal onclick 제거)

## [2026-05-27] 로그인 페이지 문구 변경 및 계정신청 모달 UX 개선

로그인 폼·계정신청 모달의 "사용자명" 레이블을 "사용자명(사번)"으로 변경. 힌트·유효성 메시지·체크박스 문구도 일괄 반영. 모달 외부(overlay) 클릭 시 모달이 닫히지 않도록 변경. 모달 내 텍스트 드래그 시 mouseup이 overlay에서 발생하여 모달이 닫히던 문제도 동시 해결. Escape 키 닫기는 유지.

**변경 파일:** `login.html` (레이블·힌트·메시지 문구 4곳 변경, overlay onclick 제거)

## [2026-05-25] Dominator Tree 탭 신설

분석 페이지에 Dominator Tree 탭 추가. MAT CLI에 `org.eclipse.mat.api:query` (`-command=dominator_tree -groupBy NONE`) 4번째 리포트를 추가하여 최상위 500개 dominator 객체를 파싱, 네이티브 테이블로 렌더링. 검색 필터, 행 클릭 시 Component Detail 모달 연동. Raw Data 섹션에 MAT HTML iframe(Dominator Tree Raw) 패널도 추가.

기존 분석 결과는 앱 기동 시 Query ZIP에서 자동 재파싱하여 탭 표시.

**변경 파일:** `DominatorTreeEntry.java` (신규 모델), `MatParseResult.java`·`HeapAnalysisResult.java` (필드 추가), `MatReportParser.java` (parseDominatorTreeZip + reparseDominatorTree), `HeapDumpAnalyzerService.java` (MAT CLI 4번째 리포트 + 진행률 단계 + buildResult + 캐시 복원), `HeapDumpViewController.java` (모델 속성), `HeapReportApiController.java` (report type), `analyze.html` (사이드바 + 패널 + iframe), `analyze.js` (filterDomTree)

## [2026-05-24] 분석 페이지 KPI 핵심 지표 교체

기존 KPI 6칸 중 "Top Consumers / Others / Usage" 3칸이 MAT Top Components의 class loader 기준 dominator 합산 특성상 거의 항상 100%/0%/100%로 무의미하던 문제 개선. Objects, Class Loaders, GC Roots로 교체.

- `Before`: Total Heap | Top Consumers | Others | Usage % | Classes | Suspects
- `After`:  Total Heap | Objects | Class Loaders | GC Roots | Classes | Suspects

MAT Overview에서 "Number of class loaders" / "Number of GC roots" 신규 파싱. 기존 result.json(신규 필드 없음)에서는 0으로 표시되며 재분석 시 정상 반영. 스택 바 차트(Top Consumers 비율)는 그대로 유지.

**변경 파일:** `MatParseResult.java`, `HeapAnalysisResult.java` (필드+포맷 헬퍼), `MatReportParser.java` (파싱), `HeapDumpAnalyzerService.java` (빌드+클론), `analyze.html` (KPI+사이드바), `analyze-print.html` (PDF KPI)

## [2026-05-24] Leak Suspects — stacktrace 모달 뷰어

MAT Leak Suspects에서 "See stacktrace" / "See stacktrace with involved local variables" 링크가 있는 경우, suspect 아이템에 버튼을 표시하고 클릭 시 모달로 stacktrace 내용 로드. 기존에는 HtmlSanitizer가 상대 경로 href를 `javascript:void(0)`로 치환하여 링크가 동작하지 않았음.

파서에서 각 suspect 섹션의 원본 HTML에서 stacktrace 페이지 경로를 추출하여 `LeakSuspect.stacktracePage` / `stacktraceLocalVarsPage` 필드에 저장. 기존 result.json(새 필드 없음)은 앱 기동 시 Suspects ZIP에서 자동 재파싱.

**변경 파일:** `LeakSuspect.java` (필드 추가), `MatReportParser.java` (stacktrace 링크 추출 + reparseSuspects/reparseOverviewMeta 공개 메서드), `HeapDumpAnalyzerService.java` (캐시 복원 시 재파싱), `analyze.html` (버튼 + 모달), `analyze.js` (openStacktraceModal/closeStacktraceModal)

## [2026-05-24] AI 인사이트 — Custom Provider + SSL 미검증 시 API 비용 경고 숨김

LLM Provider가 "custom"이고 SSL Verify가 해제된 경우(사내 자체 LLM 서버) AI 분석 확인 모달에서 "API 비용이 발생할 수 있습니다" 경고 문구를 자동으로 숨김.

**변경 파일:** `analyze.html` (경고 `<li>`에 id 부여), `analyze.js` (모달 열기 시 `/api/settings` 조회 후 조건부 숨김)

## [2026-05-24] AI 인사이트 — JVM Xms/Xmx 입력 지원

AI 분석 확인 모달에 대상 애플리케이션의 JVM 힙 설정(-Xms, -Xmx) 입력 필드 추가. 입력 시 LLM 프롬프트에 JVM 설정 섹션이 포함되어 힙 여유 공간 비율, 메모리 압박 수준, Xmx 증설/축소 권고를 포함한 정밀 분석 제공. 결과에 "JVM 힙 설정 분석" 전용 카드로 표시.

**변경 파일:** `analyze.html` (모달 입력 필드 + 결과 카드), `analyze.js` (데이터 수집 + 프롬프트 생성 + 결과 렌더링), `LlmConfigService.java` (시스템 프롬프트 JVM 분석 지시 추가)

## [2026-05-24] GC 로깅 JVM 옵션 추가

`restart.sh` 및 `run.sh`에 `-Xlog:gc*` JVM 옵션 추가. GC 이벤트를 `logs/gc.log`에 기록하며 로테이션(20MB × 5파일) 적용.

**변경 파일:** `restart.sh`, `run.sh`

## [2026-05-24] SpringSessionTableChecker 실행 시점 수정

`@EventListener(ApplicationReadyEvent.class)` → `InitializingBean.afterPropertiesSet()` 변경.
기존에는 앱 기동 완료 후 세션 테이블을 점검했으나, Spring Session JDBC가 기동 중에 이미 테이블에 접근하므로 테이블 누락 시 자동 생성 기회를 놓치는 문제가 있었음. `InitializingBean`으로 변경해 빈 초기화 시점에 점검·생성이 이루어지도록 수정.

**변경 파일:** `src/main/java/.../config/SpringSessionTableChecker.java`

## [2026-05-24] 보안 개선: /login 계정 신청 endpoint 강화

**Issue 1 — 사용자명 열거 방지 (Critical):**
`POST /api/account-requests`에서 사용자명 충돌 시 구분 가능한 에러 메시지를 반환하던 취약점 수정.
- `AccountRequestService.submit()` — 기존 사용자명/대기 신청 충돌 시 `IllegalArgumentException` 대신 `null` 반환 (서버 WARN 로그만 기록)
- `AccountRequestController.submit()` — `null` 응답을 `success:true`로 통일, 응답에서 `id` 필드 제거
- 타이밍 공격 방지: BCrypt 해싱을 충돌 검사 전에 항상 수행

**Issue 2 — 공개 endpoint 요청 제한 (Medium):**
- `RateLimiter.java` 신규 — `ConcurrentHashMap` 기반 IP별 슬라이딩 윈도우 카운터 (외부 의존성 없음)
- `AccountRequestController` — IP당 60초 5회 제한, 초과 시 HTTP 429
- `@Scheduled` 5분 주기 만료 엔트리 정리

**Issue 3 — 프론트엔드 주석 정리 (Low):**
- `login.html` — HTML/JS 주석 4건 제거 (HTML 주석·JS 설명 주석)
- `common.css` — 내부 아키텍처 상세 설명 주석 축소 (섹션 헤더 1줄만 유지)
- 캐시 키 `?v=2026-05-24a`로 14개 템플릿 일괄 갱신

**변경 파일:**
- `src/main/java/.../service/AccountRequestService.java`
- `src/main/java/.../controller/AccountRequestController.java`
- `src/main/java/.../util/RateLimiter.java` (신규)
- `src/main/resources/templates/login.html`
- `src/main/resources/static/css/common.css`
- 14개 템플릿 `?v=` 캐시 키 갱신

## [2026-05-23] 모바일 Topbar 버튼 개행 방지 — common.css 전역 수정

**현상:** Comparison History 등 모바일 화면에서 topbar 의 `+새비교` / `Dashboard` 버튼이 우측 폭 압력으로 제목 아래로 개행됨.

**원인:** `common.css` 의 `.topbar-brand` / `.topbar-title` / `.topbar-right` 에 CLAUDE.md 규약(줄바꿈 방지 패턴)이 미적용.

**변경 (`src/main/resources/static/css/common.css`):**
- `.topbar-brand` — `flex: 1 1 auto; min-width: 0` 추가 (브랜드 영역이 남은 공간만 차지, 수축 허용)
- `.topbar-title` — `white-space: nowrap; overflow: hidden; text-overflow: ellipsis` 추가 (타이틀 단일 라인 유지)
- `.topbar-right` — `flex-shrink: 0` 추가 (버튼 영역 절대 축소 금지)
- 캐시 키 `?v=2026-05-23b` 로 14개 템플릿 일괄 갱신

## [2026-05-23] 업로드 시 Analysis Files 순번(ID) 즉시 채번

**배경:** 덤프 파일이 아닌 파일을 포함해 파일 업로드 후 분석을 실행하지 않으면 `analysis_history` DB 레코드가 없어 순번(ID)이 채번되지 않고 Files 페이지에 `-` 로 표시됨.

**변경:**
- `HeapDumpAnalyzerService.saveUploadRecord(filename, fileSize, uploadedBy)` 신규 추가 — 업로드 성공 직후 `analysis_history` 에 `status=NOT_ANALYZED` 레코드를 INSERT 해 ID 를 채번. 동명 파일 레코드가 이미 존재하면 스킵(중복 방지).
- `HeapFileApiController.uploadFileApi()` — `Authentication` 파라미터 추가, 업로드 성공 후 `saveUploadRecord()` 호출.
- `HeapDumpViewController.uploadFile()` — 동일하게 `saveUploadRecord()` 호출 추가.
- 이후 분석 실행 시 `saveAnalysisToDb()` 가 기존 `findByFilename().orElse(new)` 패턴으로 동일 레코드를 찾아 update → 순번 유지.

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapFileApiController.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`

## [2026-05-23] 동일 파일명 업로드 차단 모달 너비 확대

**변경:** `src/main/resources/static/js/upload-queue.js` 251번 라인 — 모달 `max-width` 420px → **520px**.

## [2026-05-23] SpringSessionTableChecker — 수동 안내 → 자동 부트스트랩

**배경:** 신규 DB 배포 시 운영자가 `schema-mysql.sql` 을 수동 실행해야 하는 부담. 사용자가 자동 부트스트랩 로직 도입을 제안.

**대안 분석:** `initialize-schema=always` 로 되돌리는 단순한 방법은 직전에 제거한 1050/1061 WARN 회귀(Spring Session 의 schema-mysql.sql 이 `IF NOT EXISTS` 미사용) 를 일으킴. 따라서 `never` 유지 + 우리 checker 가 직접 부트스트랩하는 방식 채택.

**변경 (`src/main/java/com/heapdump/analyzer/config/SpringSessionTableChecker.java`):**
- 누락 시 ERROR 안내 → **`ScriptUtils.executeSqlScript()` 로 자동 실행** 으로 교체.
- 스크립트 소스: `classpath:org/springframework/session/jdbc/schema-mysql.sql` (Spring Session JDBC JAR 내장, `spring-jdbc:6.2.18` 의 ScriptUtils 사용 — 별도 의존성 추가 없음).
- `continueOnError=true` — 한쪽만 누락된 비정상 상태에서 기존 테이블의 1050 오류 흘려보냄, 누락 테이블 CREATE 만 성공시킴.
- 부트스트랩 실패 시 ERROR + stack trace + 수동 안내로 폴백.
- 기존 보강(LIKE wildcard escape / catalog 명시 / 단일 connection / SQLException ERROR)은 그대로 유지.
- `application.properties` 의 `initialize-schema=never` 설정은 변경하지 않음.

**검증:**
- 정상 케이스 (테이블 둘 다 존재) — 재기동 후 INFO `테이블 확인 완료 (catalog=HEAPDB).` 출력, 부트스트랩 미실행 확인.
- 누락 케이스 — 운영 DB DROP 검증은 활성 세션 영향으로 미실시. 코드 경로는 `ScriptUtils.executeSqlScript()` 표준 동작 + `continueOnError=true` 의 검증된 시맨틱에 의존.

## [2026-05-23] SpringSessionTableChecker 정합성 보완 (4건)

**배경:** 신규 추가한 `SpringSessionTableChecker` 코드 리뷰에서 잠재적 결함 2건 + 개선 사항 2건 식별.

**변경 (`src/main/java/com/heapdump/analyzer/config/SpringSessionTableChecker.java`):**
1. **JDBC LIKE 패턴 wildcard escape** — `getTables()` 의 tableNamePattern 인자는 SQL LIKE 패턴이라 `_` 가 wildcard 로 해석됨. `meta.getSearchStringEscape()` 로 escape 문자(`\`)를 얻어 `_`/`%`/escape 자체 3종을 모두 escape. `SPRING_SESSION` 이 `SPRINGXSESSION` 같은 false positive 에 잡히지 않도록 보강.
2. **catalog 명시** — `conn.getCatalog()` 로 현재 DB(`HEAPDB`)를 얻어 첫 인자로 전달. 동일 사용자가 다른 DB 권한을 가졌을 때 그쪽 동명 테이블이 매치되는 것 방지.
3. **Connection 재사용** — 두 테이블 검사를 단일 connection 으로 통합. HikariCP 풀에서 2회 빌리던 것을 1회로 단축.
4. **SQLException 시 ERROR 격상** — 메타데이터 조회 자체 실패는 앱 동작 불가 상태이므로 WARN → ERROR. 누락 오탐 방지 로직(누락 경고 미출력)은 유지.

**검증:** 재기동 로그에서 `[SpringSession] SPRING_SESSION / SPRING_SESSION_ATTRIBUTES 테이블 확인 완료 (catalog=HEAPDB).` 출력 확인.

## [2026-05-23] 기동 시 SPRING_SESSION 테이블 누락 감지 로깅 추가

**배경:** `initialize-schema=never` 환경에서 신규 DB에 배포 시 `SPRING_SESSION` 테이블이 없어도 앱이 정상 기동되다가 첫 로그인 시점에 오류가 발생. 기동 시점에 조기 경고가 없었음.

**변경:** `src/main/java/com/heapdump/analyzer/config/SpringSessionTableChecker.java` 신규 추가.
- `@EventListener(ApplicationReadyEvent.class)` — 기동 완료 시점에 `DatabaseMetaData.getTables()`로 `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` 테이블 존재 여부 확인.
- 테이블 누락 시 `ERROR` 레벨 경고 블록 출력 (누락 테이블 명시 + 수동 생성 안내).
- 테이블 정상 존재 시 `INFO` 레벨 확인 로그 출력.
- SQL 확인 중 예외 발생 시 `WARN` 출력 후 과도한 경고 방지를 위해 존재로 간주.

**검증:** 재기동 후 `[SpringSession] SPRING_SESSION / SPRING_SESSION_ATTRIBUTES 테이블 확인 완료.` INFO 로그 출력 확인.

## [2026-05-23] 기동 시 SPRING_SESSION WARN(1050/1061) 제거

**원인:** `spring.session.jdbc.initialize-schema=always` 설정으로 매 기동마다 `CREATE TABLE SPRING_SESSION` / `CREATE INDEX` DDL이 실행되었으나 테이블·인덱스가 이미 존재해 MariaDB가 1050/1061 에러를 반환, 드라이버가 WARN으로 기록.

**변경:** `src/main/resources/application.properties` — `initialize-schema` 값을 `always` → **`never`** 로 변경. 테이블은 최초 기동 시(`always`) 이미 생성되어 있으므로 이후 기동에서는 DDL 실행 불필요.

**검증:** 변경 후 재기동 로그에서 `1050-42S01` / `1061-42000` WARN 미발생 확인.

## [2026-05-23] My Account — 비밀번호 변경을 계정 정보 카드 우측 상단 버튼으로 이동

**변경 내용:**
- "비밀번호 변경" 별도 카드 제거.
- "계정 정보" 카드 타이틀(`.card-title`) 우측에 작은 `비밀번호 변경` 버튼 추가 (`card-title-btn` 스타일).
- 버튼 클릭 시 모달(`#pwModal`, `.pw-modal-box`)이 열려 3개 input 표시. 성공 시 모달 자동 닫힘 + toast 표시.
- JS: `openPwModal()` / `closePwModal()` 추가. `changePassword()` 성공 콜백에서 `closePwModal()` 호출로 변경.

**변경 파일:** `src/main/resources/templates/account.html`

## [2026-05-23] My Account 메모 초기화 모달 디자인 보정

**현상:** 메모 초기화 확인 모달이 max-width 없이 화면 가득 펼쳐지고 h3·p·버튼 모두 기본 폰트로 렌더링되어 디자인 깨짐.

**원인 (CLAUDE.md 함정 13 — CSS cascade):** `common.css` 의 `.modal-ov`/`.modal-box` 는 background/radius/padding/box-shadow 의 **공통 base 만** 제공하고 `max-width`/`width`/`text-align`/`h3`/`p`/`.modal-btns` 변형은 페이지별 인라인 정의 패턴이다. account.html 에서 이 인라인 변형을 누락.

**변경:** `src/main/resources/templates/account.html` `<style>` 에 files.html 패턴 동일하게 5 룰 추가 — `.modal-box { max-width:380px; width:90%; text-align:center }` / `.modal-box h3 {font-size:15px;font-weight:600;margin-bottom:8px}` / `.modal-box p {font-size:13px;color:#6B7280;margin-bottom:16px;line-height:1.55}` / `.modal-btns {display:flex;gap:8px;justify-content:center}` / `.modal-btns button {padding:7px 20px;...}`. 색상 utility (`.btn-cancel`/`.btn-delete`)는 common.css 그대로 사용.

**검증:** `/account` 응답 본문에 `.modal-box { max-width: 380px; ... }` 룰 포함 확인. 브라우저에서 [초기화] 클릭 시 380px 정사각 카드 + 가운데 정렬 텍스트 + 작은 버튼 두 개 정상 렌더.

## [2026-05-23] My Account 메모 한도 64KB → 10MB 확장

**요청 배경:** 64KB(=65,535 byte) 한도가 짧다는 사용자 피드백. UI 카운터의 단위가 모호("13,809 / 64KB" — 단위 미명시 byte) 했던 점도 함께 개선.

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/model/entity/User.java` — `memo` 컬럼 `columnDefinition`: `TEXT` (64KB) → **`MEDIUMTEXT`** (16MB).
- 수정: `src/main/java/com/heapdump/analyzer/service/UserService.java` — `MEMO_MAX_BYTES` 상수 `65_535` → **`10 * 1024 * 1024` (10 MB UTF-8 byte)** + 한글 에러 메시지 갱신.
- 수정: `src/main/resources/templates/account.html` —
  - JS `MEMO_MAX` 상수 동일 갱신.
  - 카운터 표시를 raw byte 숫자 → 사람이 읽기 좋은 단위(`fmtBytes()` — B/KB/MB 자동 전환)로 변경. 표시 형식 예: `5.00 MB / 10 MB (UTF-8 byte)` — 단위 모호성 해소.
- 수동 마이그레이션: `ALTER TABLE users MODIFY COLUMN memo MEDIUMTEXT;` 적용 (Hibernate `ddl-auto=update` 가 컬럼 타입 변경은 자동 처리하지 않음).
- 수정: `CHANGELOG.md`.

**검증:**
1. `mvn clean package` BUILD SUCCESS, 재기동 정상.
2. `SHOW COLUMNS FROM users WHERE Field='memo'` → `mediumtext`.
3. **5MB 메모 저장** (`'A' * 5242880`) → HTTP 200 + DB `OCTET_LENGTH(memo) = 5,242,880`.
4. **11MB 시도** → HTTP 400 + `메모는 최대 10MB까지 저장할 수 있습니다.`
5. 페이지 카운터 표시 `0 B / 10 MB (UTF-8 byte)` 노출 확인.

**참고 — 단위 해설:** account.html 의 `utf8ByteLen(str) = new Blob([str]).size` 는 UTF-8 인코딩 후 바이트 수를 측정. 한글 1글자는 UTF-8 로 3 byte 차지 → 글자수보다 항상 더 큰 값이 표시됨 (서버 측 cap 도 `String.getBytes(StandardCharsets.UTF_8).length` 라 1:1 일치).

## [2026-05-23] My Account 페이지 신설 (계정 정보 + 비밀번호 변경 + 개인 메모장)

**요청 배경:** 좌측 배너 하단 계정명이 plain `<span>` 이어서 클릭 동작이 없었고, 일반 사용자가 본인 비밀번호를 자체 변경할 통로(`UserService.resetPassword()` 는 ADMIN 전용)도 자기서비스 페이지도 부재. 사용자가 본인 정보를 보고 비밀번호를 바꾸며 본인만의 메모를 저장할 수 있는 페이지 신설.

**진입 UX:** 배너 좌하단 **계정명 row 전체** (아이콘 + username) 가 `/account` 링크. 기존 Logout 버튼은 분리 유지.

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/model/entity/User.java` — `memo` (TEXT, 명시 columnDefinition 으로 Hibernate 6 의 `tinytext(255)` default 회피) + `memo_updated_at` (LocalDateTime) 2개 컬럼 추가. Lombok `@Data` 라 getter/setter 자동. `ddl-auto=update` 로 재기동 시 `users` 테이블 자동 ALTER.
- 수정: `src/main/java/com/heapdump/analyzer/service/UserService.java` — 자기서비스 메서드 4종 추가:
  - `changeOwnPassword(username, currentPw, newPw)` — `passwordEncoder.matches()` 로 현재 PW 검증 + 기존 `validatePassword()` 재사용 + 동일 PW 재변경 차단 + BCrypt 인코딩 후 save.
  - `getOwnMemo(username)` / `saveMemo(username, text)` (UTF-8 65,535 byte cap) / `clearMemo(username)`.
- 신규: `src/main/java/com/heapdump/analyzer/controller/AccountController.java` (~95 라인) — `GET /account` 페이지 렌더 + 4 API endpoint. 모든 mutation 이 `Principal.getName()` 으로 본인 username 확정, 외부 path/query 파라미터로 username 받지 않음 → 타인 데이터 접근 경로 차단. `IllegalArgumentException` 은 `GlobalExceptionHandler` 가 JSON 400 으로 자동 변환.
- 수정: `src/main/java/com/heapdump/analyzer/config/SecurityConfig.java` —
  - `requestMatchers("/account", "/api/account/**").authenticated()` 명시 매처 추가.
  - CSRF ignore 람다에 `if (uri.startsWith("/api/account/")) return false;` 추가 → 비밀번호/메모 변경 mutation 은 **CSRF 보호 유지** (계정 보안 변경에 해당). `Common.fetchJSON` 이 자동으로 CSRF 헤더 부착.
- 수정: `src/main/resources/templates/fragments/banner.html` (L428~433) — 계정명 wrapper `<div>` → `<a href="/account" class="gb-nav-link" id="gbNavAccount">` 변경. 기존 `highlightActiveNav()` 가 자동으로 `.active` 클래스 부착 → /account 진입 시 시각 피드백.
- 신규: `src/main/resources/templates/account.html` (~250 라인) — 3 카드 패널:
  - 계정 정보 (read-only): username · displayName · role(badge) · enabled · createdAt · updatedAt — `<dl class="info-dl">` grid.
  - 비밀번호 변경: 3 input + 클라이언트 사전검증 (length/match) + `Common.fetchJSON` POST + 한글 에러 표시.
  - 메모장: textarea + UTF-8 바이트 카운터 (64KB 초과 시 빨강) + 저장/초기화 버튼. 초기화는 `.modal-ov` confirm 모달.
- 수정: `CHANGELOG.md`.

**SecurityConfig 1:1 미러링 규약 (CLAUDE.md 보안 섹션) 준수:**
- ADMIN matcher 와 ADMIN CSRF ignore 의 1:1 미러링은 그대로 유지.
- `/api/account/**` 는 **ADMIN 영역이 아니므로** authorize 측 `hasRole("ADMIN")` 매처에는 추가되지 않음. 그러나 CSRF 보호는 유지가 필요하므로 `ignoringRequestMatchers` 람다에서 `/api/account/` 를 명시적으로 `return false` 처리. (그렇지 않으면 마지막 `return uri.startsWith("/api/")` 가 면제해 버림.)

**검증 (mvn build + restart 후):**
1. `GET /account` 200 — admin/displayName/role(ADMIN badge)/createdAt 표시.
2. `POST /api/account/password` CSRF 토큰 없이 → 403. 토큰 포함 + 정확한 currentPw → `{success:true}`. 잘못된 currentPw → 400 + `현재 비밀번호가 일치하지 않습니다.` 동일 PW 재시도 → 400 + 한글 메시지.
3. `POST /api/account/memo` 정상 텍스트 → 200 + memoUpdatedAt 반환. 64KB+ 초과 → 400. `DELETE /api/account/memo` → 200 + DB 컬럼 NULL.
4. `users` 테이블 `DESC` 로 `memo TEXT` / `memo_updated_at DATETIME(6)` 컬럼 자동 추가 확인.
5. 미인증 `GET /account` → 302 → `/login`. 미인증 mutation → 302/401.
6. 회귀: `/admin/users` ADMIN reset-password 기능 정상.

**연관 함정 (CLAUDE.md §⚠️):**
- 함정 16 (`@Lob String` → Hibernate 6 default tinytext): memo 컬럼은 `@Column(columnDefinition="TEXT")` 명시.
- 함정 14 (`Common.fetchJSON` non-2xx throw): account.html JS 의 `extractErrorMessage(e)` 가 `e.body` JSON parse 후 `error`/`message` 필드 추출. 한글 에러 메시지 보존.
- 함정 13 (CSS cascade): `common.css` 의 `.modal-ov` / `.modal-box` / `.btn-cancel` / `.btn-delete` base 재사용, account.html 인라인 `<style>` 은 페이지 고유 변형만 정의.

## [2026-05-23] Files 페이지에 업로드 영역 추가 + 업로드 큐 JS 공유 모듈화

**요청:** Analysis Files(`/files`) 페이지의 기간선택 영역을 가로 절반으로 줄이고, 그 빈 자리에 업로드 레이아웃 추가. 동작은 대시보드와 동일하게 드래그&드롭 + 다중 큐 + 부분해시 중복검사 + 진행률 모달 풀세트.

**변경 파일:**
- 신규: `src/main/resources/static/js/upload-queue.js` (~520 라인) — `index.html` 인라인에 있던 멀티파일 업로드 큐 시스템 일체를 추출한 자기충족 모듈. `enqueueFiles` / `bindZone` / `fmtB` / `fmtSpeed` / `toast` / `escapeHtml` 을 `window.UploadQueue` 네임스페이스 + 호환용 글로벌로 노출. 페이지 글로벌 `MAX_UPLOAD_BYTES` / `MAX_UPLOAD_GB` / `ALLOW_ALL_EXT` 를 의존성으로 사용. DOMContentLoaded 시 `#uploadZone` / `#fileInput` 자동 바인딩. 진행률 모달의 "확인" 클릭 시 `window.onUploadQueueDone` 콜백이 있으면 호출하고 없으면 `location.reload()`.
- 수정: `src/main/resources/templates/files.html` —
  - 기존 `.date-filter-row` 단독 행을 `.top-row > .top-col × 2` 좌우 2분할 컨테이너로 감싸고, 좌측 `.top-col` 에 업로드 카드 추가. 모바일(`max-width:900px`) 에서는 세로 stack 으로 자동 분기.
  - 인라인 CSS 에 `.upload-card` / `.upload-card.upload-zone` / `.upload-icon` / `.upload-zone-btn` (`.uploading` spinner 포함) / `.uq-item` 외 큐 모달용 base style 추가. `upload-zone` 은 hover/drag-over 시 색상 변경, 가로형 (icon · text · button) flex 레이아웃.
  - `<head>` 의 `common.css` 캐시 키 `?v=2026-05-17e` → `?v=2026-05-23a` 갱신.
  - 페이지 하단 인라인 script 직전에 `<script th:inline="javascript">` 블록 추가 — `MAX_UPLOAD_BYTES` / `MAX_UPLOAD_GB` / `ALLOW_ALL_EXT` 글로벌 정의. 직후 `<script src="/js/upload-queue.js?v=2026-05-23a">` 로드.
- 수정: `src/main/resources/templates/index.html` —
  - L1099~L1587 의 업로드 인라인 JS 블록 (`validateFileExt` / `showExtWarning` / `escapeHtml` / `enqueueFiles` / `continueEnqueue` / `startDuplicateChecks` / 두 dup 모달 / `showUploadProgressModal` / `renderUploadModalList` / `cancelUploadQueue` / `doCancelUpload` / `startQueueUploads` / `processNextInQueue` / `setUploadZoneDisabled` × 2 / `computePartialHash` / `simpleHash` / `showDiskFullModal` / `fileInput` & `zone` DOM 바인딩) 통째 제거 — 488 라인 감소.
  - `fmtB` / `fmtSpeed` / `toast` 인라인 함수 정의 제거 (모듈이 글로벌로 노출).
  - 글로벌 변수 정의 `<script th:inline>` 직후에 `<script src="/js/upload-queue.js?v=2026-05-23a">` 로드 추가. 사이드바 → 배너 Upload 탭 클론 IIFE 는 모듈 노출 글로벌(`enqueueFiles`)을 그대로 사용하므로 유지.
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java` —
  - `filesPage()` 에 `maxUploadSizeBytes` / `maxUploadSizeGb` / `allowAllExtensions` 3개 model attribute 추가 (대시보드와 동일 소스). 업로드 카드의 "Max N GB" 표시 및 클라이언트 사이드 사전 검증용.

**검증:**
1. `mvn clean package -DskipTests` → BUILD SUCCESS (10.7s). 기동 12.9초.
2. admin 로그인 후 `GET /` / `GET /files` / `GET /js/upload-queue.js` 모두 **200** (upload-queue.js 36,579 bytes).
3. `/files` 응답 본문에 `id="uploadZone"` × 1, `top-row`/`top-col` 매치 9건, `/js/upload-queue.js` 스크립트 태그, `MAX_UPLOAD_BYTES = 5368709120` / `ALLOW_ALL_EXT = true` 글로벌 모두 포함.
4. `/` 응답 본문에 `Multi-file upload queue` / `function fmtB` / `function toast` 인라인 잔존 0건 — 정확히 모듈로 이전됨. 배너 클론 IIFE 의 `enqueueFiles(files)` 호출 2건은 정상 유지.

**연관 함정 (CLAUDE.md §⚠️):** 새 화면에 업로드 도입 시 (1) 컨트롤러에 3개 model attribute 추가, (2) `<head>` 에 `<meta name="_csrf">` (이미 있음), (3) `MAX_UPLOAD_BYTES`/`MAX_UPLOAD_GB`/`ALLOW_ALL_EXT` 글로벌 사전정의 후 `upload-queue.js` 로드, (4) DOM 에 `#uploadZone`/`#fileInput`/`#uploadBtn` ID 사용 → 모듈이 자동 바인딩.

## [2026-05-22] Allow all extensions 토글 ON 시 비표준 확장자 파일 분석 진입 실패 수정

**현상:** 토글 ON 상태로 `foo.xyz` 같은 비표준 확장자 파일을 업로드한 뒤(이전 수정으로 /files 목록에는 정상 노출), Files 행의 "Analyze" 버튼을 클릭하면 `/?error=invalidFilename` 로 홈 리다이렉트되며 분석 진입 불가.

**원인:** `FilenameValidator.validate()` 가 컨트롤러 입구(`/analyze/{filename}` 등 33개 호출 지점)에서 `.hprof/.bin/.dump (+.gz)` 화이트리스트를 강제하고, 비매칭 시 `IllegalArgumentException` → `GlobalExceptionHandler` 가 HTML 요청을 `/?error=invalidFilename` 로 redirect. 토글은 인지하지 못하던 비대칭 — Listing/Upload 경로는 토글 반영, Validator 만 미반영.

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/util/FilenameValidator.java` — 정적 `volatile boolean allowAllExtensions` 필드 + `setAllowAllExtensions(v)` / `isAllowAllExtensions()` 공개 API 추가. `validate()` 의 확장자 검사 조건을 `!allowAllExtensions && !hasAllowedExtension(safe)` 로 변경 — 경로 traversal/null byte/빈 파일명 검증은 모든 모드에서 그대로 유지.
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java` —
  - `setAllowAllExtensions(boolean v)` 에서 `FilenameValidator.setAllowAllExtensions(v)` 동시 호출 (런타임 토글 즉시 반영).
  - `loadSettings()` 의 allowAllExtensions 복원 블록 직후에도 동기화 호출 추가 (재기동 시 settings.json 상태 반영).

**검증:**
1. 토글 ON + `/analyze/foo.xyz` → HTTP **200** + `<title>Analyzing... - Heap Dump Analyzer</title>` (progress 페이지 진입).
2. 토글 OFF + `/analyze/foo.hprof` → HTTP **200** (기존 동작 유지).
3. 토글 OFF + `/analyze/foo.xyz` → HTTP **302** → `/?error=invalidFilename` (회귀 검증 — 정상 거부).
4. 부팅 로그에 `[Settings] Restored allowAllExtensions=true` 확인 — validator 정적 플래그가 부팅 시점에 동기화됨.

## [2026-05-22] 업로드 진행 모달 폰트 추가 확대 (2차)

**요청 배경:** 1차 확대(13/12px 단계) 후에도 모달 내 글자가 살짝 작아 보임. 한 단계 더 키움.

**변경 파일:**
- 수정: `src/main/resources/templates/index.html`
  - `.uq-item` 13→15px / gap 10→11 / padding 7→8, `.uq-icon` 18→20px(svg 동일), `.uq-status` 12→14px, 파일 사이즈 인라인 12→13px.
  - 모달 박스: max-width 560→600px / padding 26→28 / max-height 82→84vh, 헤더 아이콘 44→46px(SVG 22→24), 제목 17→19px, 부제 13→14px, 취소·확인 버튼 14→15px, 버튼 padding 9·22 → 10·24 (취소) / 11·26 → 12·28 (확인).

**검증:** 빌드 후 `curl /` HTML 응답에서 신 사이즈(`.uq-item ... font-size:15px`, `.uq-status font-size:14px`, `max-width:600px`, `font-size:19px ... 파일 업로드`) 노출 확인.

## [2026-05-22] 업로드 진행 모달 확대 + 전송 속도 표시 추가

**요청 배경:** Dashboard "Choose files" 클릭 후 표시되는 업로드 진행 모달이 너무 작고(폰트 10~13px, max-width 480px) 가독성이 떨어진다. 또한 진행률(%) 과 크기(loaded/total) 만 보여 실제 전송 속도(MB/s) 확인 불가.

**변경 파일:**
- 수정: `src/main/resources/templates/index.html`
  - `.uq-*` CSS: `.uq-item` font-size 11→13px / padding 5→7px / gap 8→10px, `.uq-icon` 16→18px (svg 강제 동일 크기), `.uq-status` 10→12px, `.uq-bar-bg` height 4→5px (radius 2→3px), 파일 사이즈 인라인 10→12px.
  - `showUploadProgressModal()` 모달 박스 인라인 스타일: max-width 480→560px, width 90→92%, max-height 80→82vh, padding 24→26px, 헤더 gap 12→14, 아이콘 원 40→44px, 헤더 제목 16→17px, 부제 12→13px, 취소 버튼 padding 8→9 / font 13→14px, 확인 버튼 padding 10→11·24→26 / font 13→14px.
  - `processNextInQueue()` xhr.upload.progress 핸들러에 전송 속도 계산 추가 — `item._lastTs` / `item._lastLoaded` 누적 + EMA(α=0.3) 평활 (`item._speed = item._speed * 0.7 + instSpeed * 0.3`). dt < 0.25s 인 짧은 progress event 는 무시(노이즈 억제). 라벨에 `42% (12.3/29.4 MB · 4.1 MB/s)` 형식 인라인 노출.
  - `renderUploadModalList()` uploading 상태 라벨도 `_speed` 존재 시 `· 4.1 MB/s` 부착 — 중도 재렌더 시에도 속도 유지.
  - 신규 헬퍼 `fmtSpeed(bps)` — B/s · KB/s · MB/s · GB/s 자동 단위 선택 (`fmtB` 와 동일 패턴).

**검증:**
1. `mvn clean package -DskipTests` 빌드 성공, `bash restart.sh` 기동 11.9s.
2. `curl -s http://localhost:18080/` HTML 응답에 새 CSS(`.uq-item { ... font-size:13px ... }`) + `function fmtSpeed` + `fmtSpeed(item._speed)` + `max-width:560px` 모두 포함 확인.
3. 브라우저에서 실제 파일 업로드 시 전송 속도 표시는 사용자 측 확인 필요(curl 로는 progress event 재현 불가).

## [2026-05-22] Allow all extensions 토글 ON 시 Files 목록 누락 버그 수정

**현상:** `/settings` 의 "Allow all file extensions" 토글을 ON 으로 설정한 뒤 `.hprof/.bin/.dump` 외 확장자(예: `.xyz`) 파일을 업로드하면 — 업로드 자체는 200 으로 성공하고 `dumpfiles/` 에도 정상 저장되지만 — `/files` 페이지 목록과 `/api/history` 등 모든 listing 결과에서 누락되어 사용자가 확인/삭제/분석할 수 없는 상태.

**원인:** `FileManagementService.listFiles()` 와 `checkDuplicate()` 가 디렉토리 스캔 시 `isValidHeapDumpFile()` 화이트리스트 필터를 강제 — 토글 값을 무시하고 항상 `.hprof/.bin/.dump (+.gz)` 만 통과시킴. 업로드 경로(`uploadFile`) 는 토글을 인식했지만 조회·중복검사 경로는 동기화되지 않은 비대칭.

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/FileManagementService.java` — `listFiles(boolean allowAllExtensions)` 오버로드 추가 + 기존 무인자 시그니처는 `listFiles(false)` 위임. `checkDuplicate(filename, fileSize, hash, boolean allowAllExtensions)` 4-arg 오버로드 추가 + 기존 3-arg 시그니처 보존. 통합 필터 헬퍼 `isListableDumpFile(dir, name, allowAllExtensions)` — 디렉토리/숨김 파일(`.`로 시작)은 항상 제외하되 토글 ON 시 모든 일반 파일 허용.
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java` — `listFiles()` / `checkDuplicate()` facade 메서드가 자신의 `allowAllExtensions` 필드를 새 오버로드에 전달하도록 변경.

**검증:**
1. `POST /api/settings/allow-all-extensions?enabled=true` → 200, `/api/settings` 응답에서 `allowAllExtensions=true` 확인.
2. `POST /api/upload` 로 `test_upload_dummy.xyz` 업로드 → `{"status":"ok"}` 200.
3. `dumpfiles/test_upload_dummy.xyz` 실 파일 존재 확인.
4. `GET /files` HTML 응답에 `test_upload_dummy.xyz` 노출(과거에는 누락).
5. 테스트 아티팩트 삭제 후 종료.

**영향 범위:** 7 컨트롤러(HeapDumpView/HeapHistoryApi/HeapSystemApi 등) 가 모두 `analyzerService.listFiles()` 를 거치므로 한 곳 수정으로 일관 반영. 토글 OFF 일 때의 기존 동작은 변경 없음(기본 인자 `false` 로 위임).

## [2026-05-22] application.properties 주석 구조 정리 — 옵션별 인라인 → 그룹 상단 통합

**요청 배경:** 각 옵션 바로 위에 설명 주석이 인터리브된 구조는 옵션을 수정/주석 처리할 때 인접 옵션과 시각적으로 분리되어 가독성이 떨어진다. 섹션 헤더 아래에 설명 주석을 모두 모으고 그 다음에 옵션 값을 나열하는 구조로 통일.

**변경 파일:**
- 수정: `src/main/resources/application.properties` — 섹션 헤더(`# ── 섹션명 ─`) 직하에 인라인되어 있던 옵션별 설명 주석을 모두 헤더 바로 아래로 끌어올리고, 옵션 키-값 라인은 주석 블록 다음에 연속 배치. 적용 섹션: 세션 관리 / MAT CLI 실행 설정 / 분석 스레드 풀 / 원격 서버 SSH·SCP / LLM 분석 / RAG (Elasticsearch) / Phase 2 semantic-server / Phase 2 semantic-client / RAG 청킹 / 분석 결과 표시 / MariaDB (예시 주석 위치 조정). 옵션이 단 1개거나 설명 주석이 없는 섹션(서버 설정, 힙 덤프 디렉토리, MAT CLI 경로, MAT CLI 옵션, gzip 압축, 디스크 모니터링, 파일 업로드 제한, Thymeleaf, 로그 레벨, 로그 파일 출력, Tomcat Access Log)은 변경 없음.

**키 보존 검증:** 변환 전후 키 개수 동일(97개). `syncApplicationProperties()` 는 `line.startsWith(key + "=")` 매칭이라 주석 위치와 무관 — 런타임 설정 영속화 동작에 영향 없음.

## [2026-05-22] 앱 JVM 힙 옵션 고정 — Xms 256m / Xmx 1g

**요청 배경:** 기존 기동 명령에 JVM 힙 옵션이 없어 JDK 기본값(시스템 메모리 기반 자동 산정) 적용. 운영 환경에서 메모리 사용량을 예측 가능하게 고정 필요.

**변경 파일:**
- 수정: `restart.sh` / `run.sh` — `java -jar ...` 명령 직전에 `JVM_HEAP_OPTS="-Xms256m -Xmx1g"` 변수 정의 후 `java $JVM_HEAP_OPTS ...` 형태로 부착. (restart.sh 는 기존 `-Dfile.encoding=UTF-8 $TRUST_OPTS` 다음에, run.sh 는 동일 위치에 추가).

### 검증
- `bash restart.sh` 후 `ps -ef` 출력 확인 — `java -Xms256m -Xmx1g -Dfile.encoding=UTF-8 -jar ...heap-analyzer-2.0.6.jar` 정상 부착.
- `/api/system/status` JSON 응답: `jvmMaxMb: 1024` (= -Xmx1g), `jvmUsedMb: 125` — 최대 힙 1 GB 적용 확인.

## [2026-05-22] 버전 2.0.3 → 2.0.6

**변경 파일:**
- 수정: `pom.xml` — `<version>2.0.3</version>` → `<version>2.0.6</version>`. mvn 빌드 산출물 파일명이 `heap-analyzer-2.0.6.jar` 로 변경됨.
- 수정: `stop.sh` / `run.sh` / `restart.sh` — `heap-analyzer-2.0.3.jar` → `heap-analyzer-2.0.6.jar` (각 파일 안 grep / JAR 경로 패턴 일괄 치환).
- 수정: `src/main/resources/templates/progress.html` 푸터 — `Heap Dump Analyzer v2.0.3` → `v2.0.6`.
- 수정: `src/main/resources/templates/fragments/banner.html` 좌측 배너 헤더 — `v2.0 · MAT CLI` → `v2.0.6 · MAT CLI` (분석 페이지 포함 모든 페이지에 노출).
- 수정: `src/main/resources/templates/index.html` 모바일 사이드바 헤더 — `v2.0 · MAT CLI Edition` → `v2.0.6 · MAT CLI Edition`.
- 수정: `CLAUDE.md` — `java -jar target/heap-analyzer-2.0.3.jar` → `2.0.6` (개발자 문서 정합).

**전환 절차:**
1. 기존 2.0.3 프로세스 SIGTERM (`kill -15 <PID>`) — 새 restart.sh 가 2.0.6 패턴으로 grep 하므로 직접 종료 필요.
2. `mvn clean package -DskipTests` 으로 새 JAR 생성: `target/heap-analyzer-2.0.6.jar` (`target/heap-analyzer-2.0.3.jar` 는 `mvn clean` 으로 제거).
3. `bash restart.sh` → 새 JAR 로 정상 기동 (13.2s).

### 검증
- 빌드 OK, 새 JAR `heap-analyzer-2.0.6.jar` 생성 (~76.5 MB).
- 프로세스 확인: `java ... heap-analyzer-2.0.6.jar --server.port=18080` 가동 중.
- 페이지 응답 grep:
  - 배너 헤더: `<div class="gb-header-sub">v2.0.6 · MAT CLI</div>`
  - 인덱스 모바일 헤더: `<div class="ms-sub">v2.0.6 · MAT CLI Edition</div>`
  - progress 푸터: `Heap Dump Analyzer v2.0.6`

## [2026-05-22] MAT 리포트 번역 보완 — "Component does not keep <Type> References alive" / "Possible Memory Leak" / 단수형 "One object"

**누락 사례:**
1. "Component does not keep Weak References alive." — 기존엔 Finalizer 변형(`with Finalizer methods`)만 처리.
2. "Possible Memory Leak One object totalling 32 B are weakly referenced and also strongly retained (kept alive) via weak references." — 두 문제 결합:
   - "Possible Memory Leak" 라벨 누락
   - "One object totalling ..." (단수형 `One`) 패턴 누락 — 기존엔 `(\d[\d,]*) objects totalling ...` 복수형만 처리

**변경 파일:**
- 수정: `src/main/resources/static/js/analyze.js` — `MAT_SENTENCE_PATTERNS` 에 4 패턴 추가:
  - `One object totalling ([\d,.]+ \w+) (?:is|are) (softly|weakly) referenced and also strongly retained \(kept alive\) via (soft|weak|phantom) references\.` → "{r} 참조되면서 {t} 참조를 통해 강하게 유지(alive)되는 객체가 1개 있습니다 (총 X 단위)." (is/are 양쪽 허용)
  - `One object totalling ([\d,.]+ \w+) (?:is|are) retained \(kept alive\) only via (soft|weak|phantom) references\.` → "{t} 참조만으로 유지(alive)되는 객체가 1개 있습니다 (총 X 단위)." (only via 단수형 변형)
  - `Possible Memory Leak` → "잠재적 메모리 누수" (global, 단독/인라인 모두 매칭)
  - `Component does not keep (Soft|Weak|Phantom) References alive\.` → "이 컴포넌트는 {t} 참조를 유지(alive)하지 않습니다." (3 가지 참조 타입 일괄)

### 검증
- 빌드 OK, 재기동 정상.
- Node 로 7 케이스 sanity test:
  - Component does not keep (Weak/Soft/Phantom) References alive → 한글 변환 OK
  - "Possible Memory Leak One object totalling 32 B are weakly referenced and also strongly retained..." → "잠재적 메모리 누수 약한 참조되면서 약한 참조를 통해 강하게 유지(alive)되는 객체가 1개 있습니다 (총 32 B)." 정상
  - `is` 변형 동일 처리 OK
  - "One object totalling 232 B is retained (kept alive) only via weak references." → "약한 참조만으로 유지(alive)되는 객체가 1개 있습니다 (총 232 B)." 정상

## [2026-05-21] MAT 리포트 footer 노이즈 제거 — "Table Of Contents / Created by Eclipse Memory Analyzer" inline 출현 제거

**요청 배경:** "맵 충돌 비율" 같은 섹션 본문 끝에 "목차 — Eclipse Memory Analyzer에 의해 생성됨" 이 붙어 시각적 노이즈만 유발. 섹션 표시에 가치 없음.

**변경 파일:**
- 수정: `src/main/resources/static/js/analyze.js`
  - 기존 inline 형태 3 패턴이 한글로 번역만 했었음 → 모두 빈 문자열 반환으로 변경:
    - `Table Of Contents\s+Created by Eclipse Memory Analyzer` (combined) → `''`
    - `Created by Eclipse Memory Analyzer` (standalone) → `''`
    - `Table Of Contents` (standalone) → `''`
  - 세 패턴 모두 `global` 플래그(`/ig`) 추가 — 한 문장 안에 2 번 이상 등장 시 모두 제거.
  - 단독 라인 형태 (`^Table Of Contents$` / `^Created by Eclipse Memory Analyzer$`) 는 `MAT_LINE_PATTERNS` 에서 별도 처리 중이므로 그대로 한글 번역 유지 (실제 TOC 헤딩 라인의 번역).
  - `trText()` 끝부분에 whitespace 정돈 추가 — `replace(/[ \t]{2,}/g, ' ')` (다중 공백 → 단일) + `replace(/[ \t]+(\n|$)/g, '$1')` (라인 끝 공백 제거) — 블랭크된 footer 위치에 남는 공백 정돈.

### 검증
- 빌드 OK, 재기동 정상.
- Node 로 3 케이스 sanity test:
  - "No maps found with collision ratios greater than 80%. Table Of Contents Created by Eclipse Memory Analyzer." → "충돌 비율이 80%를 초과하는 맵이 발견되지 않았습니다."
  - "Some content here. Table Of Contents" → "Some content here."
  - "Section text. Created by Eclipse Memory Analyzer" → "Section text."
- `/js/analyze.js` 응답 grep — 새 패턴 3 줄과 새 whitespace cleanup 1 줄 모두 출력 확인.

## [2026-05-21] MAT 리포트 번역 누락 보완 — soft/weak retained, 맵 충돌, fill ratio, finalize 카운트

**누락 사례:**
1. "39,615 objects totalling 2.1 MB are retained (kept alive) only via soft references." → 영문 그대로 노출. 기존 패턴 `([\d,]+ \w+) of objects are retained...` 가 실제 MAT 포맷(`N objects totalling X unit ...`)과 어순이 달라 매칭 실패.
2. "20 objects totalling 792 B are retained (kept alive) only via weak references." → 동일 원인.
3. "No maps found with collision ratios greater than 80%." → 패턴 미존재.
4. "A total of 1 object implement the finalize method." → 기존 finalize 패턴(`objects with finalizers? have been found`) 과 동사 형태가 달라 매칭 실패.
5. "Detected the following collections with fill ratios below 20%:" → 패턴 미존재.
6. "211 instances of java.util.concurrent.ConcurrentHashMap retain >= 6,898,592 bytes." → 패턴 미존재.

**변경 파일:**
- 수정: `src/main/resources/static/js/analyze.js` — `MAT_SENTENCE_PATTERNS` 에 6 패턴 추가:
  - `(\d[\d,]*) objects totalling ([\d,.]+ \w+) are retained \(kept alive\) only via (soft|weak|phantom) references\.` → "{ref} 참조만으로 유지(alive)되는 객체가 N개 있습니다 (총 X 단위)."
  - `(\d[\d,]*) objects totalling ([\d,.]+ \w+) are (softly|weakly) referenced and also strongly retained \(kept alive\) via (soft|weak|phantom) references\.` → "{r} 참조되면서 {t} 참조를 통해 강하게 유지(alive)되는 객체가 N개 있습니다 (총 X 단위)."
  - `A total of (\d[\d,]*) objects? implement the finalize method\.` → "총 N개의 객체가 finalize 메서드를 구현하고 있습니다."
  - `No maps found with collision ratios greater than (\d+)%\.` → "충돌 비율이 N%를 초과하는 맵이 발견되지 않았습니다."
  - `Detected the following maps with collision ratios greater than (\d+)%:` → "충돌 비율이 N%를 초과하는 다음 맵이 발견되었습니다:" (안전망)
  - `Detected the following collections with fill ratios below (\d+)%:` / `Detected the following arrays with fill ratios below (\d+)%:` → "채움 비율이 N% 미만인 다음 컬렉션/배열이 발견되었습니다:"
  - `(\d[\d,]*) instances? of ([\w.$]+) retain >= ([\d,]+) bytes\.` → "<class> 인스턴스 N개가 X 바이트 이상을 유지하고 있습니다."

### 검증
- 빌드 OK, 재기동 정상.
- Node 로 8 케이스 sanity test 수행 — 입력 8건 모두 한글로 정상 변환 확인 (소프트/약한 retained, 맵 충돌, finalize 카운트(단/복수), fill ratio 헤더, instances retain).
- `/js/analyze.js` 응답에 새 정규식 6 라인 모두 grep 확인.

## [2026-05-21] 3건 — progress 모바일 라인 정렬 / Finalizer IBM 문구 번역 / Download 모달 한글화

**요청 3건:**
1. 분석 진행 페이지(/progress)에서 sub-step(Overview/Top Components/Leak Suspects 리포트)의 점-아래 연결선이 모바일에서 점 오른쪽으로 어긋남 → 정렬.
2. 클래스 상세 모달의 "Finalizer Statistics" 섹션의 "Heap dump contains no java.lang.ref.Finalizer objects. IBM VMs implement Finalizer differently..." 문장이 영문 그대로 노출 → 한글 번역.
3. /analyze 의 Download 버튼 클릭 시 뜨는 모달(Download File / CSV Export)이 영문 → 한글화.

**원인 분석 (1번):**
`@media (max-width: 480px)` 에서 `.timeline-item.sub-step { padding-left: 14px }` 으로 점 시작 위치가 좌측으로 이동했으나, 베이스 sub-step 규칙의 `timeline-line { left: 35px }` 는 그대로 적용 → 점 중심(14+12=26) 과 라인 중심(35+1=36) 이 10px 어긋남.

**변경 파일:**
- 수정: `src/main/resources/templates/progress.html`
  - `@media (max-width: 480px)` 블록 안에 `.timeline-item.sub-step .timeline-line { left: 25px; }` 1줄 추가 — sub-step 24px dot 중심(14+12=26) 과 라인 중심(25+1=26) 일치.
- 수정: `src/main/resources/static/js/analyze.js`
  - `MAT_SENTENCE_PATTERNS` 의 Finalizer 섹션에 3 패턴 추가:
    - "Heap dump contains no java.lang.ref.Finalizer objects. IBM VMs implement Finalizer differently and are currently not supported by this report." 전체 일치
    - "Heap dump contains no java.lang.ref.Finalizer objects." 부분 일치 (안전망)
    - "IBM VMs implement Finalizer differently and are currently not supported by this report." 부분 일치 (안전망)
- 수정: `src/main/resources/templates/analyze.html`
  - Download File 모달: title "Download File" → "파일 다운로드", body "Heap dump file will be downloaded to your device." → "힙 덤프 파일을 사용자 기기로 다운로드합니다.", "File Size" → "파일 크기", "Format" → "형식", 버튼 "Cancel/Download" → "취소/다운로드".
  - CSV Export 모달: title "Export CSV" → "CSV 내보내기", body "Top Memory Consumers data will be exported as a CSV file." → "Top Memory Consumers 데이터를 CSV 파일로 내보냅니다.", "Rows/Columns" → "행 수/열 수", 버튼 "Cancel/Download" → "취소/다운로드".

### 검증
- 빌드 OK, 재기동 정상.
- `progress.html` source grep — 488 라인 `padding-left: 14px` 다음 라인 490 에 `timeline-line { left: 25px }` 출력 확인.
- `/js/analyze.js` 응답에 새 3 패턴 grep 확인 — IBM VMs / java.lang.ref.Finalizer 문구가 한글로 변환되는 정규식 정상 추가.
- `/analyze/jeus_admin_20260517.hprof` 응답에서 "파일 다운로드" / "힙 덤프 파일을 사용자 기기로 다운로드합니다." / "CSV 내보내기" / "Top Memory Consumers 데이터를 CSV 파일로 내보냅니다." 모두 grep 으로 출력 확인.

## [2026-05-21] Analysis History 모바일 — Detections KPI 한 줄 + 툴바 재배치

**요청 2건:**
1. Detections 위젯의 3 KPI (14d Total / 7d vs Prev / Peak Day) 가 모바일에서 1열 stack 으로 떨어져 세로 공간 차지 → 한 줄(3 열) 유지.
2. 검색 input 이 비정상적으로 거대해 보이고 상태 select / 선택 버튼 / deleted 표시 / 행 표시 가 각각 한 줄씩 차지 → 검색만 1행 전폭으로 두고 나머지는 같은 행에서 wrap 으로 컴팩트 배치.

**변경 파일:**
- 수정: `src/main/resources/templates/history.html`
  - `@media (max-width: 640px)` 의 `.det-kpi { grid-template-columns: 1fr }` → `repeat(3, 1fr); gap: 6px;`. 카드 내부 `.det-kpi-card { padding: 14px 16px → 8px 9px }`, `.det-kpi-lbl { font-size: 11 → 9px; letter-spacing: 0 }`, `.det-kpi-val { 24 → 16px }`, `.det-kpi-sub { 12 → 10px }` 로 컴팩트화 — 3 카드가 360px 화면에서도 자연 배치.
  - `@media (max-width: 640px)` 의 `.table-toolbar { flex-direction: column; align-items: stretch }` → `flex-direction: row; flex-wrap: wrap; align-items: center; gap: 8px`. 각 자식별 flex 규칙 신규:
    - `.search-bar { flex: 1 1 100%; padding: 10 14 → 8 12px; font-size: 13px }` — 1 행 full-width 강제, padding 축소로 높이 정상화.
    - `.filter-sel { flex: 1 1 130px; padding/font 축소 }` — 남는 공간 확장.
    - `.select-toggle-btn / .deleted-toggle { flex: 0 0 auto; padding/font 축소 }` — 자연 폭.
    - `.page-size-wrap { flex: 0 0 auto; margin-left: auto }` — 우측 정렬.
  - 결과: 1행 검색 input + 2행 (상태 select | 선택 | deleted 표시 | 행 표시) wrap 배치.

### 검증
- 빌드 OK, 재기동 정상.
- `/history` 렌더 HTML 의 `@media (max-width: 640px)` 블록에서 두 영역 모두 grep 으로 새 규칙 출력 확인.
- 모바일 (≤640px) 에서 Detections KPI 3 카드가 가로 1 행으로 표시되고, 검색바가 1 행 full-width, 그 아래 row 에 상태/선택/deleted/페이지 표시가 컴팩트하게 배치됨.

## [2026-05-21] 모바일 배너 — 탭 버튼과 Upload 사이 상단 여백 축소

**원인:** `.gb-body { padding: 42px 12px 14px }` 의 42px top padding 은 데스크탑에서 `position: absolute; top: 62px` 인 `.gb-toggle` (배너 접기 버튼) 과 콘텐츠가 겹치지 않게 두는 공간. 모바일에서는 toggle 이 `display: none !important` 로 숨겨지고 `.gb-mobile-tabs` 가 normal flow 로 그 위치에 들어오므로, 그 아래의 42px 가 그대로 빈 공간으로 남았다.

**변경 파일:**
- 수정: `src/main/resources/templates/fragments/banner.html`
  - `@media (max-width: 900px)` 블록 안에 `.gb-body { padding-top: 10px; }` 1줄 추가 — 모바일에서만 top padding 을 42px → 10px 로 축소. 좌우(12px) / 하단(14px) 은 그대로.

### 검증
- 빌드 OK, 재기동 정상.
- `/` 렌더 HTML 에서 데스크탑 기본 `.gb-body { padding: 42px 12px 14px }` 다음 줄에 모바일 override `.gb-body { padding-top: 10px; }` 가 cascade 순서대로 출력됨 (≤900px 화면에서 후자가 적용).
- 결과: 모바일에서 Navigation/Analysis/Chat 탭 버튼 하단 ↔ Upload 섹션 상단 사이의 빈 공간이 ~32px 축소.

## [2026-05-21] 비교 페이지 모바일 미세 조정 — Quick Reading 한 줄 + 스냅샷 가독성 회복

**요청 배경:**
- 직전 컴팩트화 작업에서 `.cmp-risk-row` 가 1fr 으로 강제되어 Quick Reading 의 Leak Suspects / Threads 가 모바일에서 세로 stack 됨 → 사용자 요청: 같은 라인으로 복원.
- Before/After 스냅샷이 2-column × 2x2 mini-metric 구조로 너무 압축돼 라벨/값 가독성 저하 → 사용자 요청: 더 시원하게.

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
  - `@media (max-width: 700px)` 블록 미세 조정:
    - `.cmp-picker-grid, .cmp-risk-row { 1fr }` → `.cmp-picker-grid { 1fr }` 만 남기고 `.cmp-risk-row { 1fr 1fr; gap: 8px }` 로 별도 규칙. Quick Reading 의 두 박스가 한 라인 유지.
    - `.cmp-snapshot-grid { 1fr 1fr → 1fr; gap: 8px → 10px; margin-bottom: 12 → 14px }` — 두 스냅샷 카드를 세로 stack 으로 변경하여 각 카드가 풀폭 확보.
    - `.cmp-snapshot-card { padding: 12 → 14px }`, `h3 { font-size: 12 → 13px; margin-bottom: 8 → 10px }` — 헤더 가독성.
    - `.cmp-metric-list { gap: 6 → 8px }` 유지 (2x2).
    - `.cmp-mini-metric { padding: 8 9 → 10 11; border-radius: 10 → 11 }` — 터치 타겟·여백 회복.
    - `.cmp-mini-label { font-size: 10 → 11; margin-bottom: 2 → 4 }`, `.cmp-mini-value { font-size: 12 → 15 }` — 값이 한눈에 들어오도록 폰트 키움.
  - 그 외 KPI/Hero/Summary 컴팩트 규칙은 직전 작업과 동일하게 유지 (스크롤 단축 효과 보존).

### 검증
- 빌드 OK, 재기동 정상.
- `/compare` 렌더 HTML 의 700px 미디어쿼리 블록에서 다음 모두 확인:
  - `.cmp-risk-row { grid-template-columns: 1fr 1fr; gap: 8px; }` 단독.
  - `.cmp-snapshot-grid { grid-template-columns: 1fr; gap: 10px; margin-bottom: 14px; }`.
  - `.cmp-mini-value { font-size: 15px; }`, `.cmp-mini-label { font-size: 11px; }`.
- 결과 (모바일 ≤700px): Before/After 스냅샷 카드가 세로 2 행으로 stack 되어 각 카드 안의 4 metric 이 2x2 로 더 넓게 배치, 값 폰트가 15px 로 확대돼 가독성 향상. Quick Reading 의 Leak Suspects + Threads 는 1 행 2 열로 한 줄 표시 유지.

## [2026-05-21] 모바일 UX 정돈 4건 — 배너 여백, 비교 페이지 요약/위험도/레이아웃

**요청 4건:**
1. 모바일 배너에서 Upload 와 System Status 사이 여백이 너무 큼 → 축소.
2. 비교 페이지의 "AI 인사이트 요약" 본문이 길면 9em 에서 잘려 사라짐 → 모두 노출.
3. 비교 페이지 "핵심 요약" 탭이 모바일에서 스크롤 폭발 → 2-column 유지 + 패딩 축소.
4. 비교 페이지 AI 인사이트의 위험도 결과가 오른쪽 메타데이터(일자/모델/레이턴시)와 같은 row 에 있어 좁은 화면에서 텍스트 줄바꿈 다발 → 세로 stack + 메타 하단 한 줄.

**변경 파일:**
- 수정: `src/main/resources/templates/fragments/banner.html`
  - 추가 CSS: `@media (max-width: 900px) { #gbNavUploadSlot:not(:empty) + .gb-section { margin-top: -8px; } }` — 모바일에서 Upload 슬롯과 System Status 사이의 .gb-body flex gap (16px) 을 -8px margin 으로 보정해 ~8px 로 축소.
- 수정: `src/main/resources/templates/index.html`
  - `clone.style.padding = '14px 12px'` → `'10px 12px 4px'` (상/좌우/하 패딩 축소, 특히 하단 6px → 4px 로 감소해 다음 섹션과의 간격 추가 단축).
- 수정: `src/main/resources/templates/compare.html`
  - `.cmp-ai-summary-body` 의 `max-height: 9em; overflow: hidden;` 제거. `word-break: break-word; overflow-wrap: anywhere;` 추가하여 긴 LLM 응답도 줄바꿈으로 전부 표시.
  - `@media (max-width: 700px)` 블록을 확장:
    - `.compare-header { grid-template-columns: 1fr 1fr; }` + `.compare-header .vs-circle { display: none; }` — VS 원 숨기고 Before/After 2-column 유지.
    - `.dump-card / .dump-card-label / .dump-card-name / .dump-card-meta-row` 폰트/패딩 축소.
    - `.cmp-snapshot-grid { 1fr 1fr; }` + `.cmp-metric-list { 1fr 1fr; }` — 모바일에서도 두 스냅샷을 가로로 2 열, 각 mini-metric 도 2x2 격자.
    - `.cmp-snapshot-card / .cmp-mini-metric / .cmp-mini-label / .cmp-mini-value` 패딩·폰트 축소.
    - `.kpi-grid { 1fr 1fr; }` + `.kpi-tile / .kpi-value / .kpi-label / .kpi-sub` 축소 — 6 KPI 를 6 행 stack 에서 3 행 × 2 열로 변경.
    - `.cmp-hero` 패딩/폰트 축소, `.cmp-summary-card / .cmp-recommend-card / .cmp-summary-main / .cmp-summary-list` 축소.
    - `.cmp-result-summary` gap·margin 축소.
  - 신규 `@media (max-width: 700px)` 안에서 `.ai-severity-banner` flex-direction 을 `column` 으로 변경, `align-items: flex-start; gap: 8px;`. 마지막 자식(메타 div) 을 `text-align: left !important; width: 100%; padding-top: 6px; border-top: 1px solid currentColor; opacity: .55; display: flex; flex-wrap: wrap; gap: 6px 12px;` 로 변경하여 일자·model·latency 가 위험도 결과 영역과 별도 행에 한 줄로 가로 배치되도록 함.

### 검증
- 빌드 OK, 재기동 정상.
- `/` 응답: `#gbNavUploadSlot:not(:empty) + .gb-section { margin-top: -8px; }` 및 clone padding `10px 12px 4px` 모두 출력됨.
- `/compare` 응답: `.cmp-ai-summary-body` 규칙에 `max-height` 없음. 700px 미디어쿼리 안에 `.compare-header .vs-circle { display: none; }` / `.cmp-snapshot-grid { 1fr 1fr; }` / `.kpi-grid { 1fr 1fr; }` / `.ai-severity-banner { flex-direction: column; }` 모두 출력됨.

## [2026-05-21] 모바일 배너 Upload 탭 제거 + Navigation 탭 통합 / Allow all ext 설명 정돈

**요청 2건:**
1. Settings 의 "Allow all file extensions" 설명 끝 "보안상 운영 환경에서 비활성 권장." 문구 제거.
2. 모바일 배너의 별도 Upload 탭을 삭제하고, 업로드 UI 를 Navigation 탭 안 System Status 위쪽으로 옮김.

**변경 파일:**
- 수정: `src/main/resources/templates/settings.html`
  - `Allow all file extensions` setting-desc 끝 보안 권장 문구 한 줄 제거 (label/toggle 동작 유지).
- 수정: `src/main/resources/templates/fragments/banner.html`
  - HTML: 모바일 탭바에서 `<button class="gb-mobile-tab" data-tab="upload">` 통째 제거. `#gbBody` 최상단(System Status 직전)에 `<div id="gbNavUploadSlot"></div>` 슬롯 추가. 기존 `<div class="gb-tab-content gb-tab-upload" id="gbMobileUpload"></div>` 슬롯 삭제.
  - CSS: dynamic-tab hide 규칙 목록에서 `[data-tab="upload"]` 제거, `body.has-upload-tab` 표시 규칙 제거, 데스크탑 `.gb-tab-content.gb-tab-upload` 숨김 규칙 제거. 신규 `#gbNavUploadSlot:empty { display: none; }` + `@media (min-width: 901px) { #gbNavUploadSlot { display: none; } }` — 모바일 + 콘텐츠 있을 때만 표시.
  - JS: `switchBannerTab` map 에서 `upload: 'gbMobileUpload'` 항목 제거. `registerBannerUploadTab(contentElement)` 가 `#gbMobileUpload` 대신 `#gbNavUploadSlot` 에 appendChild + `document.body.classList.add('has-upload-tab')` 호출 제거 (더는 필요 없음).
- index.html 변경 없음 — 기존 `registerBannerUploadTab(clone)` 호출이 그대로 새 슬롯에 주입됨. clone 의 inline padding(14px 12px) + `.gb-body` flex gap 16px 로 시각 정렬 유지.

### 검증
- 빌드 OK, 재기동 정상.
- `/` 응답 grep — `data-tab="upload"` / `gbMobileUpload` / `gb-tab-upload` / `has-upload-tab` 모두 0건. 잔존 dead 참조 없음.
- 신규 `#gbNavUploadSlot` 가 `#gbBody` 의 첫 자식, 직후 `<div class="gb-section">…System Status…</div>` 순서로 출력 확인 (즉, System Status 위에 위치).
- 데스크탑(≥901px) 에서는 슬롯 자체가 `display: none` → 좌측 사이드바의 Upload 와 중복 안 됨.
- 비-인덱스 페이지 (예: /history) 에서는 슬롯이 비어 있어 `:empty` 규칙으로 자동 숨김.
- Settings UI: "Allow all file extensions" setting-desc 끝부분이 "허용." 으로 단축됨.

## [2026-05-21] Settings — 비-ADMIN 토글 ON 하늘색 적용을 LLM/RAG 페이지로 확대

**요청 배경:** 비-ADMIN 진입 시 토글 ON 색을 하늘색으로 변경한 직전 변경(General Settings)이 `/settings/llm`, `/settings/rag` 에는 미반영. 동일 일관성 요청.

**변경 파일:**
- 수정: `src/main/resources/templates/llm-settings.html` — `.tog:checked + .tog-track` 규칙 직후에 `.container[data-is-admin="false"] .tog:checked + .tog-track { background: #7DD3FC; }` 한 줄 추가.
- 수정: `src/main/resources/templates/rag-settings.html` — 동일 한 줄 추가.

### 검증
- 빌드 OK, 재기동 정상.
- `/settings/llm`, `/settings/rag` 두 응답 모두에서 `.container[data-is-admin="false"] .tog:checked + .tog-track { background: #7DD3FC; }` 규칙 grep 확인.
- 두 페이지 모두 이미 `<div class="container" th:attr="data-is-admin=${isAdmin}">` 와 `applyReadOnlyMode()` 가 기존 구현되어 있어 CSS 만 추가하면 적용됨.

## [2026-05-21] Settings — Max upload size 볼드 / 확장자 제한 해제 토글 / 비-ADMIN 토글색

**요청 3건:**
1. Max upload size 현재값을 강조 표시(볼드).
2. 관리자가 기본 확장자 화이트리스트(.hprof/.bin/.dump + .gz)를 해제해 임의 확장자 업로드를 허용하는 토글 추가.
3. 비-ADMIN 사용자가 Settings 진입 시, ON 상태 토글의 색을 진한 파란색 대신 하늘색으로 표시.

**변경 파일:**
- 수정: `src/main/resources/templates/settings.html`
  - `#sMaxUploadCurrent` 인라인 style 보정 — `font-size:13px;font-weight:700;color:#1F2937;min-width:60px`.
  - Analysis Options 카드에 "Allow all file extensions" 토글 추가 (`#togAllowAllExt`), `onchange=confirmAllowAllExtensions(this)`. 활성화 시 경고 모달(`#allowAllExtModal`), 비활성화 시 모달 없이 즉시 저장.
  - `loadAllData()` 응답 핸들러에서 `d.allowAllExtensions` 로 토글 상태 동기화.
  - JS: `confirmAllowAllExtensions` / `closeAllowAllExtModal` / `doEnableAllowAllExt` / `saveAllowAllExtensions(v)` 신규. Escape 핸들러에 `closeAllowAllExtModal` 추가.
  - CSS: `.container[data-is-admin="false"] .tog:checked + .tog-track { background: #7DD3FC; }` 추가 — 비-ADMIN 진입 시 ON 토글 색을 sky blue 로 시각화 (기존 `applyReadOnlyMode` 의 opacity:.6 와 결합되어 readonly 인지가 더 분명함).
- 수정: `src/main/resources/templates/index.html`
  - 인라인 스크립트에 `ALLOW_ALL_EXT` 변수 추가 (Thymeleaf 인라인 binding).
  - 큐 검증 `validExts.some(...)` → `ALLOW_ALL_EXT || validExts.some(...)` 분기.
  - `<input type="file" accept>` 속성을 Thymeleaf `th:attr` 로 동적 — 토글 ON 이면 accept 미설정 (모든 파일), OFF 면 `.hprof,.bin,.dump,.gz`.
  - `.upload-zone-sub` 텍스트도 분기 — ON: "All file extensions · Max N GB", OFF: ".hprof .bin .dump (.gz) · Max N GB".
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
  - `volatile boolean allowAllExtensions` 필드 + `isAllowAllExtensions()` / `setAllowAllExtensions(boolean)` getter/setter (setter 가 `persistSettings()` 호출).
  - `loadPersistedSettings()` 복원 분기 추가 (Boolean / String 타입 안전 처리).
  - `persistSettings()` 에 settings.json 키 추가. `syncApplicationProperties()` 는 영향 없음 (Spring 바인딩 프로퍼티가 없음 — 앱 내부 toggle).
  - `uploadFile(MultipartFile)` 가 `fileMgmt.uploadFile(file, allowAllExtensions)` 로 플래그 전달.
- 수정: `src/main/java/com/heapdump/analyzer/service/FileManagementService.java`
  - `uploadFile(MultipartFile file)` → `uploadFile(MultipartFile file, boolean allowAllExtensions)`. 확장자 검증 분기 `if (!allowAllExtensions && !isValidHeapDumpFile(filename))`. 로그에 플래그 노출.
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapSystemApiController.java`
  - 신규 `POST /api/settings/allow-all-extensions?enabled={bool}` — ADMIN 전용 (`/api/settings/**` 매처에 자동 포함).
  - `GET /api/settings` 응답에 `allowAllExtensions` 키 추가.
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
  - `index()` model 에 `allowAllExtensions` attribute 주입 (Thymeleaf 인라인 변수 + accept 속성 + 안내 텍스트가 사용).

### 검증
- 빌드 OK, 재기동 정상.
- 토글 OFF: `/api/settings` → `allowAllExtensions=false`, settings.json 미존재 시 기본 false. 인덱스 페이지 `ALLOW_ALL_EXT=false`, 안내 ".hprof .bin .dump (.gz) · Max 5 GB", accept 속성 표시.
- 토글 ON: `POST /api/settings/allow-all-extensions?enabled=true` → 응답 success, settings.json `"allowAllExtensions" : true`. 인덱스 페이지 `ALLOW_ALL_EXT=true`, 안내 "All file extensions · Max 5 GB", accept 속성 제거.
- 토글 OFF 복원: 안내·accept 모두 원상 복귀.
- 비-ADMIN CSS: `.container[data-is-admin="false"] .tog:checked + .tog-track { background: #7DD3FC; }` 렌더 확인.

## [2026-05-21] Settings — DB 설정 변경 시 "재시작 필요" 안내 강화 (사전 경고 + 저장 후 영구 모달)

**요청 배경:**
- 기존에는 DB 패스워드 변경 후 백엔드가 내려준 "변경사항을 적용하려면 앱을 재시작하세요." 메시지를 2.5초 토스트로만 표시 → 사용자가 한눈 팔면 안내를 놓침.
- 백엔드 응답에 이미 존재하던 `requireRestart: true` 플래그를 프론트가 무시하고 있던 상태.

**변경 파일:**
- 수정: `src/main/resources/templates/settings.html`
  - DB 설정 변경 모달 (`#dbEditModal`) 내 저장 버튼 직전 `<div class="modal-warn">` 추가 — 저장 시 패스워드 AES-256 암호화 + 재시작 필요 명시 (사전 안내).
  - 신규 공용 영구 모달 `#restartRequiredModal` — title "변경 저장 완료 — 앱 재시작 필요" / desc(동적 메시지) / `modal-warn` (restart.sh 안내) / 확인 버튼 단일 (자동 닫힘 없음).
  - JS: `saveDbSettings()` 응답 분기 — `d.requireRestart` 가 true 면 토스트 대신 `openRestartRequiredModal(d.message)` 호출, false 면 기존 토스트 유지. 신규 헬퍼 `openRestartRequiredModal(msg)` / `closeRestartRequiredModal()`.
  - Escape 키 핸들러에 `closeRestartRequiredModal()` 추가.

### 동작 흐름
- 사전: DB 설정 변경 모달 진입 → "AES-256 암호화 / 재시작 필요" 경고를 사용자가 저장 전 확인.
- 저장 성공 (`requireRestart: true`) → DB 모달 닫힘 + `loadAllData(false)` 후 영구 모달 오픈 → 사용자가 "확인" 클릭해야 사라짐 (Esc / 배경 클릭 도 허용).
- 향후 다른 endpoint 가 `requireRestart: true` 를 리턴하면 같은 모달을 재사용 가능.

### 검증
- 빌드 OK, 재기동 정상.
- `/settings` 렌더 시 두 영역 모두 출력 확인: 사전 경고 (#dbEditModal 내) + 영구 모달 (`#restartRequiredModal`).
- 백엔드 응답 메시지가 영구 모달 desc 에 그대로 노출되는 패턴 (호출자가 메시지를 직접 보존).

## [2026-05-21] Settings General 카드 레이아웃 재구성 — AI/LLM 카드 제거 + Analysis Options 풀폭 확장

**요청 배경:**
- AI/LLM Configuration 카드가 좌측 배너 Settings 아코디언 안에 이미 별도 메뉴로 존재 → 중복 표시 제거 요청.
- 슬롯 재배치 요청: Database 를 구 AI/LLM 자리로, MAT Configuration 을 구 Database 자리로 이동.
- Analysis Options 가 가장 옵션이 많은 카드이므로 비게 된 row 1 col 1 (MAT 자리) 까지 가로로 확장.

**변경 파일:**
- 수정: `src/main/resources/templates/settings.html`
  - CSS: `#llmCard:hover` 규칙 삭제 (orphan), 신규 `.ao-grid` 2-column 내부 grid + 마지막 행 border 정리 + `@media (max-width: 900px)` 단일 컬럼 폴백 추가.
  - HTML: 4 카드 (MAT Configuration / Analysis Options / Database / AI/LLM) 블록을 통째로 잘라 새 순서로 재배치 — `<div class="card card-full">` Analysis Options (내부 `.ao-grid` 래퍼로 setting-row 9개를 2열 분배) → MAT Configuration (구 Database 자리, 표준 card) → Database (구 AI/LLM 자리, 표준 card). AI/LLM 카드는 완전 삭제.
  - JS: `loadAllData()` 안 `if (d.llm) { updateLlmCard(d.llm); }` 호출 + `updateLlmCard(llm)` 함수 정의 (22 라인) 모두 제거.

### 레이아웃 변경 (Before → After)
- Before: row1=[MAT|AnalysisOpts], row2=[Database|AI/LLM], row3=[Storage|Disk], row4=[System|JVM], API/Danger full.
- After: row1=AnalysisOpts(full), row2=[MAT|Database], row3=[Storage|Disk], row4=[System|JVM], API/Danger full.

### 검증
- 빌드 OK (mvn clean package -DskipTests).
- 재기동 정상.
- `/settings` HTML 응답에서 `AI / LLM Configuration` / `llmCard` / `updateLlmCard` / `llmStatusBadge` / `llmSummary` 모두 grep 0건 — 잔존 dead code 없음.
- 카드 순서 `Analysis Options (card-full)` → `MAT Configuration` → `Database` → `Storage` 순으로 출력 확인.
- 좌측 배너 Settings/LLM 메뉴는 영향 없음 (URL `/settings/llm` 그대로 유지).

## [2026-05-21] Settings — Max upload size 관리자 변경 기능 추가 (최대 50 GB)

**요청 배경:**
- 기존 업로드 한도 5 GB 가 application.properties 에 하드코딩 + index.html 클라이언트 검증에도 5 GB 가 박혀 있어 운영자가 바꾸려면 코드 수정 + 재배포가 필요.
- 운영자가 Settings 화면에서 5/10/20/30/40/50 GB 중 선택해 변경할 수 있도록 요청.

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
  - 신규 상수 `MAX_UPLOAD_LIMIT_BYTES = 50 GB`, 기본값 `DEFAULT_UPLOAD_SIZE_BYTES = 5 GB`
  - `volatile long maxUploadSizeBytes` 필드 + getter/setter (setter 가 0 초과 / 50 GB 이하 검증 후 `persistSettings()` 호출)
  - 생성자에 `MultipartProperties` 주입 — 부트 시점 application.properties 의 `spring.servlet.multipart.max-file-size` 값을 초기값으로 채택
  - `loadPersistedSettings()` 에 `maxUploadSizeBytes` 복원 분기 추가 (Number / String 타입 안전 처리, 0~50GB 범위 검증)
  - `persistSettings()` 에 settings.json 키 추가
  - `syncApplicationProperties()` 에 `spring.servlet.multipart.max-file-size` / `max-request-size` 두 키 동기화 추가
  - 신규 helper `formatBytesAsSpringSize(long)` — bytes → "5GB"/"512MB" Spring DataSize 표기 변환
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapSystemApiController.java`
  - 신규 `POST /api/settings/max-upload-size?bytes=N` — ADMIN 전용 (기존 `/api/settings/**` 매처에 자동 포함, SecurityConfig 변경 불필요), 검증 실패 시 400 JSON, 성공 시 `requireRestart: true` 응답
  - `GET /api/settings` 응답에 `maxUploadSizeBytes` / `maxUploadSizeGb` / `maxUploadSizeFormatted` / `maxUploadLimitBytes` 4 키 추가
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
  - `index()` 메서드에서 model 에 `maxUploadSizeBytes` / `maxUploadSizeGb` 두 attribute 추가
- 수정: `src/main/resources/templates/settings.html`
  - Analysis Options 카드 하단에 "Max upload size" 행 추가 — 현재값 표시 + 5/10/20/30/40/50 GB select + Apply 버튼
  - 신규 modal `#maxUploadModal` — "재시작 필요" 경고 포함 확인 모달
  - JS: `openMaxUploadModal()` / `closeMaxUploadModal()` / `doSaveMaxUploadSize()` — Common.fetchJSON 으로 POST, 성공 시 toast + loadAllData(false) 갱신
  - `loadAllData()` 의 `/api/settings` 응답 처리부에 `sMaxUploadCurrent` 텍스트 + select selectedIndex 복원 로직 추가 (옵션에 없는 값이면 동적 옵션 추가)
  - Escape 키 핸들러에 `closeMaxUploadModal()` 추가
  - 기존 비-ADMIN readonly 처리 (`applyReadOnlyMode`) 가 input/select/button 일괄 disable 하므로 별도 권한 체크 불필요
- 수정: `src/main/resources/templates/index.html`
  - `.upload-zone-sub` 텍스트 `Max 5 GB` → Thymeleaf `${maxUploadSizeGb}` 동적 치환
  - 인라인 `th:inline="javascript"` 블록에 `MAX_UPLOAD_BYTES` / `MAX_UPLOAD_GB` 전역 변수 추가
  - 큐 업로드 검증 `files[i].size > 5*1024*1024*1024` → `MAX_UPLOAD_BYTES`
  - 용량 초과 모달 "5 GB 제한" 텍스트 → `MAX_UPLOAD_GB + ' GB 제한'`

### 동작 방식
- 운영자가 Settings UI 에서 값 선택 → 확인 모달 → POST 가 settings.json + (외부 / 소스) application.properties 두 파일 동기화.
- 즉시 효과: 분석 서비스의 `maxUploadSizeBytes` getter 가 새 값 반환 → /api/settings, /(index) 의 model attribute 가 즉시 반영.
- Tomcat 멀티파트 한도는 부팅 시점 `MultipartProperties` 바인딩으로 고정되므로 실제 한도 적용은 앱 재시작 필요 (모달 + API 응답 메시지에 명시).
- 재시작 시 `loadPersistedSettings()` 가 settings.json 의 값을 application.properties 보다 우선 복원, `syncApplicationProperties()` 가 properties 파일도 같은 값으로 다시 정렬 → 다음 build+restart 시점에 JAR 번들 값도 정합.

### 검증
- 빌드 OK (mvn clean package -DskipTests).
- 재기동 12.6초 (`Started HeapAnalyzerApplication in 12.665 seconds`).
- `GET /api/settings` → `maxUploadSizeBytes: 5368709120 / maxUploadSizeGb: 5 / maxUploadSizeFormatted: "5.00 GB" / maxUploadLimitBytes: 53687091200`.
- `POST /api/settings/max-upload-size?bytes=10737418240` → `success: true / requireRestart: true`, settings.json 갱신 (`"maxUploadSizeBytes" : 10737418240`), application.properties `max-file-size=10GB / max-request-size=10GB` 동기화 확인 → 다시 5GB 로 원복 검증.
- 검증 실패 케이스: 50 GB 초과 (64 GB) → 400 `"업로드 크기는 최대 50 GB를 초과할 수 없습니다."`, 음수 → 400 `"업로드 크기는 0보다 커야 합니다."`.
- index 페이지 HTML 에서 `Max 5 GB` 표시 + `MAX_UPLOAD_BYTES = 5368709120` / `MAX_UPLOAD_GB = 5` 인라인 변수 + 검증 로직 모두 동적 치환 확인.
- settings 페이지 HTML 에서 "Max upload size" 행 + select + 모달 모두 렌더 확인.

## [2026-05-19] Dashboard (메인) Detections 패널 — Top Breakdown 위젯 추가

**변경 파일:**
- 수정: `src/main/resources/templates/index.html`
  - HTML: `.det-chart-wrap` (line 794~796) 직후에 `<div class="det-breakdown" id="dailyBreakdown">` 추가 (헤더: "Top Servers · 최근 14일 점유율" + 리스트)
  - CSS: `.det-breakdown*` / `.det-bd-row` (grid `110px 36px 1fr 44px`) / `.det-bd-bar` + `.det-bd-bar-fill` / `.det-bd-pct` 신규 + `@media (max-width: 640px)` 컴팩트 grid (`88px 32px 1fr 38px`). history.html 패턴과 1:1 미러링.
  - JS: 기존 차트 IIFE (line 920~) 안 `datasets` 생성 직후에 익명 IIFE `renderBreakdown()` 추가
    - `SERVER_SERIES` 의 각 `s.counts` 합산 → 총합 / 비율 계산
    - 카운트>0 만 필터, 내림차순 정렬, Top 5 slice
    - 동일 PALETTE/OTHERS_COLOR 사용 (차트 막대 색상과 1:1 매칭)

### 변경 의도
- 사용자가 "Detections · 최근 14일 · 서버별 레이아웃 하단이 여백이 많다" 보고 (메인 페이지)
- 좌측 Detections 패널이 우측 "탐지 현황" 패널보다 짧아 하단 빈 공간 발생
- history.html 에 먼저 같은 위젯을 추가했고, 사용자가 메인 페이지에도 동일 적용 요청
- 메인 페이지는 그룹 토글이 없는 **서버 모드 전용** 이므로 history 보다 단순한 구현 (서버 5개만 표시, severity 분기 불필요)
- 데이터 (`SERVER_SERIES`) 가 이미 SSR Thymeleaf 인라인으로 도착, 백엔드 변경 없음

### 검증
- 빌드 OK (mvn clean package -DskipTests).
- 재기동 12.7초 (`Started HeapAnalyzerApplication in 12.664 seconds`).
- JAR 패키징 확인: `dailyBreakdown` / `dailyBreakdownList` / `det-breakdown` / `det-bd-row` / `det-bd-bar-fill` / `renderBreakdown` 모두 임베드.
- `/` 메인 페이지에서:
  - 좌측 Detections 차트 하단에 Top 5 서버 막대 표시
  - 차트 stacked bar 색상과 breakdown 막대 색상 1:1 매칭
  - 빈 데이터 (kpiTotal14d = 0) 시 위젯 자체가 thymeleaf `th:if` 영역 밖이므로 차트 + breakdown 동시 숨김 (기존 `.det-empty-chart` 만 표시)
  - 모바일 ≤640px: 컴팩트 grid 자동 적용

## [2026-05-19] History Detections 패널 — Top Breakdown 위젯 추가

**변경 파일:**
- 수정: `src/main/resources/templates/history.html`
  - HTML: `.detect-panel` 안 차트(`.det-chart-wrap`) + empty 메시지 뒤에 `<div class="det-breakdown" id="detBreakdown">` 컨테이너 추가 (title + sub + list)
  - CSS: `.det-breakdown` / `.det-breakdown-hdr` / `.det-breakdown-ttl` / `.det-bd-row` (grid `110px 36px 1fr 44px`) / `.det-bd-bar` + `.det-bd-bar-fill` (progress) / `.det-bd-pct` 신규 (약 28라인)
  - JS: `renderTopBreakdown(rawDatasets, groupBy, total)` 함수 추가 (`applyKpi` 다음 위치)
    - 그룹 모드 `server`: Top 5 서버 + PALETTE 색상, "기타" 는 회색 OTHERS_COLOR
    - 그룹 모드 `severity`: critical/high/medium/low 4행 + SEVERITY_COLORS
    - 각 행: 이름 / 카운트 / progress bar (max 기준 상대 폭) / 백분율
    - 총 0 또는 빈 데이터 시 위젯 숨김
  - `renderChart()` 안에서 `applyKpi` / `applyRecent` 직후 `renderTopBreakdown(payload.datasets, _detGroup, payload.kpi.total)` 호출 → 기간/그룹 변경 시 자동 갱신
  - `@media (max-width: 640px)`: `.det-bd-row { grid-template-columns: 88px 32px 1fr 38px; font-size:11px }` 컴팩트화

### 변경 의도
- `detect-row { align-items: stretch }` 로 좌측 Detections 패널(헤더+KPI+차트 340px = 약 507px) 이 우측 Recent 패널(max-height 540px)에 맞춰 늘어나면서 차트 아래 빈 공간 발생
- 데이터는 이미 `/api/history/detections` 응답의 `datasets: ServerSeries[]` 에 그룹 모드별 카운트가 정렬되어 도착 (서버에서 Top 12 + "기타" 묶음). 클라이언트에서 `counts.reduce(sum)` 으로 점유율 계산 가능 → **백엔드 변경 없음**
- 차트와 동일 PALETTE/SEVERITY_COLORS 사용으로 시각적 일관성 (스택 막대의 각 색상 ↔ breakdown 막대 색상 1:1 매칭)

### 검증
- 빌드 OK (mvn clean package -DskipTests).
- 재기동 14.2초 (`Started HeapAnalyzerApplication in 14.19 seconds`).
- LeakRuleSeeder 정상 (library 66 / fallback 33 skip).
- JAR 패키징 확인: `det-breakdown` / `det-bd-row` / `det-bd-bar-fill` / `renderTopBreakdown` / `detBreakdownTtl` 모두 임베드.
- `/history` 페이지에서:
  - 기본(14d/서버별): Top 5 서버 막대 표시
  - 그룹 토글 "심각도": 헤더 "심각도 분포" + 4행 (CRITICAL/HIGH/MEDIUM/LOW)
  - 기간 칩(7/30/90d): API 재호출과 함께 위젯 자동 갱신
  - 빈 기간: 위젯 숨김
  - 모바일 ≤640px: 컴팩트 grid 자동 적용

## [2026-05-19] Analysis Files — 모바일 카드 레이아웃 + bottom sheet 모달

**변경 파일:**
- 수정: `src/main/resources/templates/files.html`
  - `<td>` 9개에 `data-label="..."` 부여 (선택/#/파일명/원본/압축/AI/서버/날짜/작업)
  - 신규 클래스: `col-fname` / `col-seq` / `col-server` / `col-actions` (≤640 selector 활용)
  - 다운로드 모달 파일명 인라인 `max-width:260px` 제거 → `.dl-modal-fname` 클래스로 추출 (인라인은 미디어쿼리 override 불가)
  - `@media (max-width: 640px)` 블록 교체 — 14 라인 → 약 100 라인:
    - 테이블 → 카드 변환 (`tr { display: block; background:#fff; border-radius:10px; padding:10px 14px; margin-bottom:10px; position:relative; }`)
    - `td::before { content: attr(data-label); }` 로 라벨 prefix
    - 파일명 = 카드 헤더 (풀폭, 줄바꿈 허용, 14px 굵게)
    - `# 번호` 카드 우상단 absolute (회색)
    - 체크박스 (선택 모드) 카드 좌상단 absolute + row-check 15→18px
    - 액션 버튼 26→38px, 6개 wrap 가능
    - panel/no-match/pagination 카드 톤 통일
  - 모달 4종 (단일/일괄/purge/다운로드) bottom sheet 변환 (`align-items: flex-end; border-radius:14px 14px 0 0;` + 버튼 풀폭 `flex:1 1 0`)
  - `toggleSelectMode` / `exitSelectMode` 에 `document.body.classList.toggle('has-select-bar')` 추가 → fixed 액션바가 마지막 카드 가리는 문제 해소 (`body.has-select-bar { padding-bottom: 72px; }`)
  - 툴바 모바일 재배치: 3개 select 동일줄 1/3 분할 (이전 50/50/50% 어색함 해소), 검색바 풀폭, 행 표시 라벨 숨김

### 변경 의도
- 기존 ≤640 처리는 (1) `col-date/col-compressed/col-hide-sm` 숨김으로 정보 누락 (2) `min-width:500px` 강제 가로 스크롤로 액션 컬럼 접근 어려움 + 작은 26x26 탭 타깃 → 모바일 사용성 저하
- leak-rules.html 의 검증된 카드 패턴 차용 (CLAUDE.md "Files/History 공통 데이터 그리드 패턴" 의 모바일 확장)
- 정렬 헤더 손실은 기본 정렬(`_sortKey='date', _sortDir='desc'` = 최신순) 이 모바일 워크플로에 부합해 정렬 select 미추가 (사용자 확인)

### 검증
- 빌드 OK (mvn clean package -DskipTests).
- 재기동 12.3초 (`Started HeapAnalyzerApplication in 12.307 seconds`).
- LeakRuleSeeder 정상 (library 66 / fallback 33 skip).
- JAR 패키징 확인: `data-label="..."` 7종, `col-fname/col-actions/dl-modal-fname` 3 클래스, `has-select-bar` 토글 모두 정상 임베드.
- 권장 viewport 시각 검증: 1280 (테이블) / 768 (테이블+스크롤) / 640 boundary / 414/375/360/320 (카드).

## [2026-05-19] Leak Rules 탭 디자인 개선 — pill 스타일 + 카운트 뱃지

**변경 파일:**
- 수정: `src/main/resources/templates/leak-rules.html`
  - `.tabs` 컨테이너: 투명 underline → 흰색 카드 (`background:#fff`, `border-radius:10px`, `padding:4px`, `box-shadow`)
  - `.tab-btn` 비활성: `color:#6B7280` → `#374151` (대비 강화), `border-radius:7px` pill 형태
  - `.tab-btn.active`: 파란 underline → 그라데이션 배경 (`linear-gradient(135deg,#2563EB,#4F46E5)`) + 흰 글자 + `box-shadow`
  - `.tab-btn:hover` 비활성: 배경 `#F3F4F6` 추가로 hover 명확화
  - `.tab-count` 뱃지 신규 — 탭 텍스트 옆 룰 개수 표시 (비활성=회색 pill / 활성=반투명 흰색 pill)
  - 모바일 (≤640px): `.tabs` 도 `display:flex; width:100%`로 균등 분배 유지
  - JS: `loadLibrary()`/`loadFallback()` 가 기존 `*CountChip` 외 신규 `*CountTab` 도 동시 갱신
  - HTML: `<button>` 안에 `<span class="tab-count" th:text="${...Count}">` 추가

### 변경 의도
- 사용자 보고: "라이브러리 / 정규식 탭의 배경색과 글자색이 겹쳐서 인식 어려움".
- 원인: 투명 배경 + `#6B7280` 회색 텍스트가 `#F3F4F6` 페이지 배경과 대비 부족, 활성 underline 만으론 영역 구분 약함.
- 개선: 카드형 컨테이너 + 활성 탭 강한 그라데이션 → 활성 / 비활성 즉시 구분. 카운트 뱃지로 어느 탭에 몇 개 룰이 있는지 한눈에.

### 검증
- 빌드 OK (mvn clean package -DskipTests).
- 재기동 12.4초 (`Started HeapAnalyzerApplication in 12.414 seconds`).
- LeakRuleSeeder 정상 (library 66 / fallback 33 skip).

## [2026-05-19] CLAUDE.md 광범위 갱신 — Boot 3 마이그레이션 반영

**변경 파일:**
- 수정: `CLAUDE.md` — 4 영역 갱신
  1. **Project Overview**: "Boot 2.7.18 + Java 11" → "Boot 3.5.14 + Java 17 (런타임 JDK 21) + Security 6.5 + Hibernate 6.6 + jakarta 네임스페이스"
  2. **Authentication & Security**: `authorizeRequests` → `authorizeHttpRequests(auth -> auth.requestMatchers(...))`, lambda DSL + `@EnableMethodSecurity` 명시
  3. **Pitfalls 신규 2건**:
     - #15: Spring Session JDBC 3.x ↔ 2.x 직렬화 비호환 (Boot 다운/업그레이드 시 TRUNCATE 필수)
     - #16: Hibernate 6 `@Lob String` → tinytext(255) default 변경, `@Column(columnDefinition="TEXT")` 권장
  4. **Refactoring History**: Boot 3 마이그레이션 항목 추가 (5 phase 요약 + 영향 통계 + 운영 인프라 변경)

### 변경 의도
- 마이그레이션 완료 후 향후 작업자가 CLAUDE.md 만 읽고도 현재 스택 / API / 함정을 정확히 파악 가능하게 동기화.
- 특히 SecurityConfig 의 lambda DSL + `requestMatchers` + `@EnableMethodSecurity` 는 신규 ADMIN endpoint 추가 시 잘못된 옛 API 사용 방지.
- Spring Session / Hibernate `@Lob` 함정 2건은 미래 Boot 버전 변경 또는 신규 entity 추가 시 재발 가능 — pitfalls 섹션에 영속화.

### 검증
- 텍스트 갱신만 (코드 변경 없음). 빌드/기동 영향 없음.

## [2026-05-19] 운영 환경 Maven 영구 업그레이드 (3.5.4 → 3.9.9)

**변경 (운영 시스템 측):**
- `/etc/profile.d/maven.sh` 신규 — 시스템-와이드 `MAVEN_HOME` / `M2_HOME` = `/opt/apache-maven-3.9.9`
- `alternatives --install /usr/bin/mvn mvn /opt/apache-maven-3.9.9/bin/mvn 200` + `--set mvn ...` (수동 모드 잠금)
- Phase 3 의 `/usr/local/bin/mvn` 심볼릭 (3.9.9) 그대로 보존 — 이중 안전망

**변경 (repo):**
- 수정: `CLAUDE.md` — Build & Run 섹션에 Maven 3.6.3+ 요구사항 한 줄 추가

### 변경 의도
- Phase 3 BIG BANG 시 임시로 `/usr/local/bin/mvn` 심볼릭으로 회피했던 Maven 버전 의존성을 영구화. RHEL `alternatives` 시스템 표준 방식 사용 → `/usr/bin/mvn` 도 3.9.9 가리킴. cron/systemd/CI 등 비대화식 컨텍스트에서도 일관됨.

### 검증
- `/usr/bin/mvn -version` → Apache Maven 3.9.9
- `/usr/local/bin/mvn -version` → Apache Maven 3.9.9
- `bash -lc 'echo $MAVEN_HOME; mvn -version | head -1'` → `MAVEN_HOME=/opt/apache-maven-3.9.9` + 3.9.9
- alternatives 상태: 수동 모드, `/opt/apache-maven-3.9.9/bin/mvn priority 200` (기존 RHEL 패키지 3.5.4 priority 0 은 그대로 두되 비활성)

### 신규 운영 환경 배포 절차
1. JDK 17+ 설치 (현재 OpenJDK 21)
2. `/opt/apache-maven-3.9.9/` 압축 해제 (또는 동일 버전 이상)
3. `alternatives --install /usr/bin/mvn mvn /opt/apache-maven-3.9.9/bin/mvn 200 --slave /usr/bin/mvnDebug mvnDebug /opt/apache-maven-3.9.9/bin/mvnDebug`
4. `alternatives --set mvn /opt/apache-maven-3.9.9/bin/mvn`
5. `/etc/profile.d/maven.sh` 동일 내용 배포

## [2026-05-19] Boot 3 마이그레이션 — Phase 4 안정화 (Thymeleaf 3.1 fragment syntax + 확장 smoke test)

**변경 파일:**
- 수정: 16 templates — `th:replace="fragments/banner :: banner"` → `th:replace="~{fragments/banner :: banner}"`
  - `analyze.html` / `ai-chat.html` / `comparison-history.html` / `compare.html`
  - `files.html` / `history.html` / `index.html` / `leak-rules.html`
  - `llm-settings.html` / `progress.html` / `rag-settings.html` / `settings.html`
  - `server-detail.html` / `server-logs.html` / `servers.html` / `admin/users.html`

### 변경 의도
- Thymeleaf 3.1 가 미감싼 fragment expression (`templateName :: fragmentName`) 을 deprecated 처리. 향후 버전에서 제거 예고. 모든 호출처를 권장 `~{...}` 문법으로 통일.
- 동시에 Phase 3 BIG BANG 직후 확장 smoke test 로 비-회귀 검증 수행.

### 내역
- 6+ 템플릿이 사용하는 banner fragment 포함 16 곳 일괄 sed. 다른 fragment 호출은 모두 이미 `~{...}` 사용 중이라 추가 변경 없음.
- 운영 환경 인프라 (이전 Phase 3 의 Maven 업그레이드 + DB 정리 + Session 초기화) 그대로 유지.

### 확장 검증 (Phase 4 추가)
- 14 페이지 페이지 GET 모두 200 (Phase 3 12 페이지 + `/servers/logs`, `/admin/leak-rules` + `/analyze/result/{file}`)
- 4 ADMIN API (`/api/admin/login-history`, `/active-sessions`, `/users`, `/account-requests`) 모두 200 + 페이지네이션·정렬 동작 (Hibernate 6 + jakarta.persistence + `Specification` API)
- 익명 `POST /api/account-requests` 빈 body → 400 + 한글 validation 메시지 (CSRF 면제 경로 동작)
- **PDF 생성** `/analyze/{file}/print-pdf` → HTTP 200, 45KB, `%PDF-1.4` magic, 2.2s 렌더 → **openhtmltopdf 1.0.10 + Java 17 + Boot 3 호환 확인** (plan Risk #3 해소)
- 기동 후 ERROR / Exception / Deprecated Thymeleaf WARN **0건**
- 기동 시간 12.82초

### Phase 4 잔여 (별도 작업)
- **운영 환경 Maven 영구 업그레이드** (현재는 작업 시스템만 `/opt/apache-maven-3.9.9`)
- **실제 분석 실행 흐름** (MAT CLI + SSE Emitter — 인터랙티브 검증 필요)
- **LLM Chat 스트리밍** (`/api/ai-chat/sessions/{id}/stream` — 외부 LLM 게이트웨이 통신)
- **Server SSH/SCP** 원격 덤프 전송 흐름

## [2026-05-19] Boot 3 마이그레이션 — Phase 3 BIG BANG (Boot 2.7 → 3.5.14 / Security 5.7 → 6.5.10)

**변경 파일:**
- 수정: `pom.xml`
  - `spring-boot-starter-parent` 2.7.18 → **3.5.14**
  - `mariadb-java-client` 3.1.4 → 3.4.2
  - `thymeleaf-extras-springsecurity5` → `thymeleaf-extras-springsecurity6`
- sed 일괄 치환 (28 파일): `javax.{persistence,servlet,annotation.PostConstruct/PreDestroy,transaction.Transactional}` → `jakarta.*`
  - `javax.{crypto,net.ssl,sql,xml}` 는 치환 금지 (jakarta 이전 대상 아님) — `AesEncryptor` / `DataSourceConfig` / `MatReportParser` / `LlmConfigService` SSL / `RagService` SSL / `RemoteDumpService` SSL / `EmbeddingService` 그대로
- 수정: `SecurityConfig.java` — `.antMatchers(...)` → `.requestMatchers(...)` 전체 (Security 6.x 에서 antMatchers/regexMatchers/mvcMatchers 제거됨)
- 수정: `application.properties` — `spring.jpa.properties.hibernate.dialect=MariaDB103Dialect` 주석 처리 (Hibernate 6 dialect 자동 감지)
- 수정: `LeakLibraryRule.java` / `LeakFallbackRule.java` — `@Lob` → `@Column(columnDefinition = "TEXT")` 명시 (Hibernate 6 가 `@Lob` String 을 `longtext` → `tinytext(255)` 로 default 변경한 영향 회피)
- 수동 DDL: `ALTER TABLE leak_{library,fallback}_rule MODIFY ... TEXT NOT NULL` 5 컬럼 정렬

**환경 인프라:**
- Maven 3.5.4 → 3.9.9 업그레이드 (`/opt/apache-maven-3.9.9/` 영구 설치 + `/usr/local/bin/mvn` 심볼릭) — Boot 3.5 의 maven-clean-plugin 3.4.1 이 Maven 3.6.3+ 요구
- DB: `TRUNCATE SPRING_SESSION_ATTRIBUTES; TRUNCATE SPRING_SESSION;` (Spring Session 3.x 가 2.x 직렬화 포맷 역직렬화 불가 — 모든 사용자 재로그인 필요)

### 변경 의도
- Boot 2.7 / Security 5.7 EOL 탈출. Spring Framework 6.2 + Hibernate 6.6 + Tomcat 10.1 + jakarta 네임스페이스 일괄 전환으로 신규 CVE 패치 대상 진입.

### 번들 검증 (실측)
```
spring-boot-3.5.14.jar
spring-security-{config,core,crypto,web}-6.5.10.jar
spring-session-{core,jdbc}-3.5.6.jar
hibernate-core-6.6.49.Final.jar
tomcat-embed-core-10.1.54.jar
thymeleaf-3.1.5.RELEASE.jar
jakarta.persistence-api-3.1.0.jar
jakarta.annotation-api-2.1.1.jar
jakarta.transaction-api-2.0.1.jar
mariadb-java-client-3.4.2.jar
```

### Smoke test 결과
- 12 인증 페이지 GET (`/`, `/files`, `/history`, `/compare`, `/settings`, `/settings/llm`, `/settings/rag`, `/admin/users`, `/admin/leak-rules`, `/ai-chat`, `/servers`, `/comparison-history`) 모두 **HTTP 200**
- 5 API endpoint (`/api/system/status`, `/api/history`, `/api/queue/status`, `/api/disk/check`, `/api/settings`) 모두 **HTTP 200**
- CSRF 보호: `POST /api/llm/test-connection` (헤더 없이) **403** / (포함) **200** / `POST /api/settings/general` (헤더 없이) **403** ← ADMIN 면제 차단 유지
- 미인증 8 페이지 모두 **302** redirect
- 기동 시간 **12.76초** (Boot 2.7 의 10초 대비 +2.7초)
- ERROR / Exception 0건

### Phase 4 안정화 TODO (비-블로커)
- Thymeleaf 3.1 deprecated fragment syntax: `fragments/banner :: banner` → `~{fragments/banner :: banner}` (banner 호출 6+ 템플릿). 현재 작동하나 향후 버전에서 제거 예고
- 운영 환경의 Maven 도 3.6.3+ 로 영구 업그레이드 필요 (현재 작업 시스템만 3.9.9 설치됨)
- LLM Chat 스트리밍 / SSE 동작 / PDF 생성 (openhtmltopdf) / 실제 분석 실행 흐름은 별도 수동 테스트 권장

## [2026-05-19] Boot 3 마이그레이션 — Phase 2 (SecurityConfig lambda DSL 사전 modernization)

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/config/SecurityConfig.java`
  - import: `EnableGlobalMethodSecurity` → `EnableMethodSecurity` (Spring Security 5.6+ 도입, 5.6+ 부터 `prePostEnabled=true` default)
  - 어노테이션: `@EnableGlobalMethodSecurity(prePostEnabled = true)` → `@EnableMethodSecurity`
  - `authorizeRequests()` → `authorizeHttpRequests(auth -> auth.…)` lambda DSL
  - `.formLogin()` / `.logout()` / `.headers()` / `.csrf()` 모두 lambda DSL 로 통일
  - `.and()` chain 완전 제거
  - `antMatchers` 는 유지 (Phase 3 에서 `requestMatchers` 로 일괄 치환 예정)

### 변경 의도
- Boot 3 / Security 6 BIG BANG PR 의 diff 크기를 사전에 축소. lambda DSL 과 `authorizeHttpRequests` / `@EnableMethodSecurity` 는 모두 5.6/5.7 호환 API 라 Boot 2.7 유지 상태에서 무위험 도입 가능.
- `@PreAuthorize` 6 곳 (AdminController + AccountRequestController 5 메서드) 활성 → `@EnableMethodSecurity` 유지 필수 확인.

### 내역
- 함수형/람다 전환으로 가독성 향상 (`.and()` 체인 보일러플레이트 제거).
- CSRF `ignoringRequestMatchers` 람다 본문은 1:1 보존 — ADMIN-only mutation endpoint 면제 차단 로직 (2026-05-18 의 CSRF tighten 작업) 그대로.
- `frameOptions().sameOrigin()` → `headers(h -> h.frameOptions(f -> f.sameOrigin()))` — Raw Data iframe 의 SAMEORIGIN 정책 유지 (CLAUDE.md pitfall #6).

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 기동 (Started in 10.0s).
- 6-시나리오 curl smoke test 전부 통과:
  1. 미인증 `GET /api/system/status`, `/admin/users`, `/` 모두 302 redirect
  2. CSRF 토큰 발급 OK
  3. ADMIN 로그인 302 → `/`
  4. 로그인 후 `/admin/users` / `/api/admin/active-sessions` / `/api/system/status` 모두 200
  5. CSRF 헤더 없이 `POST /api/llm/test-connection` → 403
  6. CSRF 헤더 포함 → 200

## [2026-05-19] Boot 3 마이그레이션 — Phase 0 + Phase 1 (Java compile target 11 → 17)

**변경 파일:**
- 신규: `BOOT3_MIGRATION_PLAN.md` (root) — 영향 인벤토리 / 5-Phase 전략 / 위험 매트릭스 / smoke test 체크리스트
- 수정: `pom.xml` — `<java.version>11</java.version>` → `17`
- 신규 (repo 외): `/opt/heapdumps/backups/HEAPDB-pre-boot3-20260519-1103.sql` (260KB, 14 테이블)
- 신규 브랜치: `migration/boot3` (main 에서 분기)

### 변경 의도
- Boot 2.7.18 / Spring Security 5.7.11 모두 OSS·상용 지원 종료 → Boot 3.4 + Security 6.4 마이그레이션 착수.
- Phase 1 은 가장 안전한 첫 단계: JVM 컴파일 타깃만 변경 (이미 운영 JVM 은 OpenJDK 21 사용 중이라 런타임 변경 없음). Boot 2.7 은 Java 8/11/17 모두 지원하므로 단독 무위험 검증 가능.

### 내역
- **Phase 0 산출물**: DB 백업 (mysqldump --single-transaction --routines --triggers --events --add-drop-table) — SHA256 `1cdb5a74...d517d8`, 14 테이블 (SPRING_SESSION 2종 / analysis_history / ai_chat_* 2종 / ai_insights / users / account_requests / leak_*_rule 2종 / target_servers / dump_transfer_log / login_history / comparison_history).
- **Phase 1 변경**: `pom.xml` 1 라인. maven-compiler-plugin 은 Spring Boot 2.7 parent default 사용 (Java 17 호환 plugin 자동 제공).
- 운영 JVM 은 변경 없음 (이미 JDK 21 사용). 검증 단계에서 코드가 17 바이트코드로 컴파일되어 JDK 21 위에서 정상 실행되는지만 확인.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 기동.
- 컴파일 target 확인: `javap -v target/classes/com/heapdump/analyzer/HeapAnalyzerApplication.class | head -3` → major version 61 (= Java 17).

## [2026-05-19] AI 인사이트 진행 단계 — 원 사이 연결선 수직 정렬 수정

**변경 파일:**
- 수정: `src/main/resources/static/css/analyze.css` — `.ai-step-line` `margin-bottom:18px` → `margin-top:17px`
- 수정: `src/main/resources/templates/analyze.html` — analyze.css 캐시 키 `?v=2026-05-12` → `?v=2026-05-19`

### 변경 의도
- `#aiStepIndicator` 컨테이너가 `align-items: flex-start` 이므로 모든 자식이 상단 정렬됨. line 은 2px 박스라 row 상단에 그대로 붙어 원의 위쪽 가장자리(또는 그보다 위)에 표시되고 있었음. 기존 `margin-bottom:18px` 는 row 하단 여백만 늘릴 뿐 line 자체의 top 위치를 이동시키지 못함.

### 내역
- 원 크기 36px (border-box, border 2px 포함) → 중심 y=18px. line 2px → top 17px 두면 line 중심이 y=18px 으로 원 중심과 일치.
- 단순히 margin-top 만 교체 → 동일한 row height 유지 (`.ai-step` 의 circle+label 합산이 여전히 row 높이 결정).
- CSS 변경이라 `?v=` 캐시 키 갱신 필요 — `2026-05-12` → `2026-05-19`.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 기동 (Started in 10.4s). 브라우저에서 AI 인사이트 실행 후 단계 라인이 원 가운데에 정렬되는지 수동 확인.

## [2026-05-19] History 페이지 — 분석 상태 필터 추가 + "실패" 라벨 통일

**변경 파일:**
- 수정: `src/main/resources/templates/history.html`
  - toolbar 검색바 옆에 `statusFilter` `<select>` 추가 (전체 / 분석완료 / 분석실패) — 컨트롤러가 SUCCESS/ERROR 만 통과시키므로 `NOT_ANALYZED` 옵션 제외
  - inline CSS: `.filter-sel` (files.html 동일 패턴) + `.table-toolbar { flex-wrap: wrap }` + `.search-bar { flex: 1 1 220px }` 좁은 폭 대응
  - JS `initRows()` 에 `localStorage('historyStatus')` 복원, `applyFilter()` 에 `data-sort-status === status` 분기, 신규 `onFilterChange()` 헬퍼
  - row 의 기존 `data-sort-status` 속성 재사용 (별도 `data-status` 추가 불필요)
- 수정: `src/main/resources/templates/files.html` — status 필터 옵션 `"실패"` → `"분석실패"` (history 와 라벨 통일)

### 변경 의도
- Files 페이지에 이어 Analysis History 에서도 동일하게 SUCCESS/ERROR 한 번에 골라내는 흐름 필요. 라벨도 "분석실패" 로 통일해 두 페이지 간 일관성 확보.

### 내역
- History 의 `data-sort-status` 는 이미 정렬용으로 존재해 필터 분기에 그대로 사용 — DOM 변경 최소화.
- localStorage 키 `historyStatus` (files 는 `filesStatus`) 분리 — 두 페이지 독립 보존.
- 라벨 변경은 dropdown text 만 영향, 백엔드 status enum 값 (`ERROR`) 은 그대로.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 기동 (Started in 9.9s).

## [2026-05-19] Files 페이지 — 분석 상태 필터 추가

**변경 파일:**
- 수정: `src/main/resources/templates/files.html`
  - toolbar `periodFilter` 옆에 `statusFilter` `<select>` 추가 (전체 / 분석완료(SUCCESS) / 실패(ERROR) / 미분석(NOT_ANALYZED))
  - `<tr>` 행에 `th:data-status="${h.status}"` 속성 추가 (클라이언트 필터 분기 기준)
  - JS `initRows()` 에 `localStorage('filesStatus')` 복원, `onFilterChange()` 에 저장 로직 추가
  - `applyFilter()` 에 `status` 분기 추가 — `'all'` 아닐 때 `data-status` 일치 행만 통과

### 변경 의도
- 실패(ERROR) 케이스 분류 또는 미분석(NOT_ANALYZED) 잔존 파일 정리 시, 기존엔 검색어 / 서버 / 기간만으로는 즉시 골라낼 수 없어 사용자가 시각적으로 빨간 아이콘을 훑어야 했음. 상태 필터로 한 번에 선별 → 일괄 삭제 / 재시도 흐름 단축.

### 내역
- 기존 `serverFilter` / `periodFilter` / `Calendar` 와 동일한 클라이언트 측 row hide 패턴. 페이지네이션·정렬·다중선택과 자연 호환.
- localStorage 키 `filesStatus` 로 새로고침 후에도 선택 유지 (기존 `filesPeriod` / `filesShowDeleted` / `filesPageSize` 와 동일 패턴).
- `cache busting` 키 변경 없음 — files.html 변경은 JAR 재패키징으로 캐시 무효화됨.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 기동 (Started in 9.6s).

## [2026-05-18] LLM Settings — Test Connection 중복 클릭 방지

**변경 파일:**
- 수정: `src/main/resources/templates/llm-settings.html`
  - 버튼 onclick: `testLlmConnection()` → `testLlmConnection(this)` (자기 참조 전달)
  - `testLlmConnection(btn)` 함수: 진입 시 `btn.disabled=true` + 라벨 "Testing..." + opacity/cursor 변경, 응답 수신(success/catch 어느 쪽이든) 후 `restore()` 로 원상복귀. 이미 disabled 상태로 호출되면 조기 return.

### 변경 의도
- 응답 지연이 긴 LLM 게이트웨이(사내 폐쇄망, SSL 협상 포함) 환경에서 사용자가 결과를 기다리지 못하고 Test Connection 을 연타하는 사례 발생. 동시 다중 요청이 백엔드 단일 connection pool 을 점유하고 race condition 으로 마지막 응답만 화면에 표시되는 혼란.

### 내역
- 진행 중 시각 단서 3종: 버튼 비활성화, 라벨 변경, 커서 `wait`. 결과 영역 스피너는 기존 유지.
- 복구는 `.then(restore, restore)` 로 fulfilled/rejected 양쪽 처리 (`.finally` 대체 패턴 — 일부 구형 브라우저 호환).
- 원본 라벨은 `dataset._origText` 에 저장해 i18n / 라벨 변경에도 안전.

## [2026-05-18] LLM Settings — 모바일 API Key 행 오버플로우 수정

**변경 파일:**
- 수정: `src/main/resources/templates/llm-settings.html` — API Key `.s-row` 인라인 스타일 보정

### 변경 의도
- 모바일 폭(≤768px)에서 `.s-row { flex-wrap: wrap }` 가 활성화되면 라벨/입력 래퍼가 별도 줄로 분리됨. 그런데 입력 래퍼에 `flex:1; min-width:0` 가 없고, 내부 `<input>` 이 고정 `width:380px;max-width:100%` 였음. flex item 기본 `min-width:auto` 때문에 래퍼가 380px 아래로 줄어들지 못해 카드/컨테이너를 가로로 뚫고 나가는 현상.

### 내역
- `.s-row` 에 `flex-wrap:wrap` 명시 (기본은 미디어쿼리 ≤768 에서만 적용 — desktop·tablet 의 wrap 강제는 영향 없음, 좁아질 때 더 일찍 wrap).
- 래퍼 div: `flex:1 1 240px; min-width:0; max-width:100%` 추가.
- input: `width:380px` 제거 → `flex:1 1 220px; min-width:0; max-width:380px; box-sizing:border-box` 로 교체. 데스크탑에선 최대 380px 유지, 좁아지면 자연스럽게 축소.
- 키 상태 표시 `span#llmKeyStatus` 에 `word-break:break-all` 추가 — 마스킹 문자열이 길어도 줄바꿈 가능.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 기동 후 모바일 viewport (375px, 360px, 320px) 에서 API Key 카드가 컨테이너 안에 머무는지 수동 확인 권장.

## [2026-05-18] CSRF 보호 — ADMIN-only mutation endpoint 면제 해제

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/config/SecurityConfig.java` — `csrf().ignoringRequestMatchers` 람다에 ADMIN endpoint 면제 차단 분기 3 그룹 추가 (`/api/settings/**`, `/api/llm/{enabled,config,apikey,test-connection,chat-prompt,chat-restore-mode}`, `/api/servers/{scan-interval,ssh-local-user}`)
- 수정: `src/main/resources/templates/settings.html` — `<meta name="_csrf">` 2 태그 추가 + `csrfHeaders()` 헬퍼 + 6 fetch 호출 (`/api/mat/heap`, `/api/servers/{scp-temp-dir,ssh-local-user,scan-interval}`, `/api/settings/database{,/test}`) 에 헤더 부착
- 수정: `src/main/resources/templates/llm-settings.html` — `<meta name="_csrf">` 2 태그 추가 (Common.fetchJSON 이 토큰 읽을 수 있도록)

### 변경 의도
- CSRF 점검 결과 `/api/admin/**` 외 `.hasRole("ADMIN")` 으로 보호되던 mutation endpoint 들이 모두 `/api/**` 면제 범위에 들어가 있었음. modern 브라우저 SameSite=Lax 와 CORS 부재로 실질 위험은 낮지만, 환경 변화(SameSite 정책 회귀, CORS 활성화) 에 무방비. 명시적 토큰 검증으로 방어 계층 추가.
- `settings.html`/`llm-settings.html` 에 CSRF meta 태그가 없어 토큰 부착이 불가능했음 (`rag-settings.html` 만 정상).

### 내역
- **SecurityConfig 분기**: `authorizeRequests` 의 `hasRole("ADMIN")` 매처와 1:1 미러링. 새 ADMIN mutation 추가 시 양쪽 동시 갱신 필요.
- **클라이언트 패턴**: `csrfHeaders({'Content-Type':'application/json'})` 형태. `Common.csrfToken()` null 안전 (토큰 없으면 헤더 미부착) — 점진적 적용 가능.
- **유지된 면제 범위**: 일반 사용자 액션 (`/api/upload*`, `/api/files/bulk-delete`, `/api/history/bulk-delete`, `/api/ai-chat/sessions*`, `/api/llm/{analyze,chat,chat/stream,insight/save,compare/analyze}`, `/api/comparison-history/*`, `/api/results/clear`, `/api/analyze/cancel/*`, `/api/servers` CRUD 등) — 인증은 유지, CSRF 면제만 유지.

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=1035374, Started in 10.3s)
- **smoke test** (admin 로그인 후 curl):
  - 토큰 없이 ADMIN endpoint 호출 → **HTTP 403** (`/api/llm/enabled`, `/api/settings/compress`, `/api/servers/scan-interval` 3/3) ✓
  - 토큰 부착 시 동일 endpoint → **HTTP 200** ✓
  - 중첩 경로 `/api/settings/rag/enabled` 도 `/api/settings/**` startsWith 분기로 정확히 매칭 ✓
  - 면제 유지 endpoint (`/api/upload/check`, `/api/ai-chat/sessions`) 는 토큰 없이도 200 — 회귀 없음 ✓
- **수동 확인 권장**: `/settings`/`/settings/llm`/`/settings/rag` 페이지의 모든 토글/저장 버튼이 정상 동작하는지

## [2026-05-18] Leak Rules — Export 확인 모달 추가

**변경 파일:**
- 수정: `src/main/resources/templates/leak-rules.html`
  - 두 Export 버튼 onclick: `exportRules('library'|'fallback')` → `openExport(...)`
  - 새 `#exportModal` (max-width 480px) — 종류/룰 개수/예상 파일명 미리보기 + 다운로드/취소 버튼
  - JS: `openExport`/`closeExport`/`confirmExport` 함수 추가, `exportRules` 함수는 `confirmExport` 안으로 흡수

### 변경 의도
- Export 버튼이 클릭 즉시 다운로드를 시작해 "내가 정말 그 탭의 룰을 받고 있는 게 맞나?" 확인 단계가 없었음. 빈 테이블에서도 빈 JSON 이 다운로드되는 무의미한 동작.

### 내역
- 모달이 보여주는 정보:
  - 종류 (`라이브러리 (prefix)` / `Fallback (정규식)`)
  - 현재 룰 개수 (`_libRules.length` 또는 `_fbRules.length`)
  - 예상 파일명 (`leak-{kind}-rules-YYYYMMDD-HHmmss.json` — 클라이언트 시각 기준, 실제 파일명은 서버 시각으로 결정됨을 캡션에 표기)
- 룰 개수 0이면 다운로드 버튼 disabled + 텍스트 "내보낼 룰 없음".
- 확인 시 기존 동작 그대로: `window.location.href = '/api/admin/leak-rules/{kind}/export'` (GET, CSRF 불필요).
- 기존 모달 CSS (`.modal-ov`/`.modal-box`/`.mbtn-confirm` 등) 재사용 — 모바일 bottom-sheet 패턴 자동 적용.

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=1015447, Started in 9.6s)
- **수동 확인 필요**: Export 클릭 → 모달 표시 → 종류/개수/파일명 확인 → 다운로드 버튼 클릭 시 기존과 동일하게 JSON 파일이 다운로드되는지

## [2026-05-18] Leak Rules — 모바일 카드 레이아웃 + 햄버거 버튼

**변경 파일:**
- 수정: `src/main/resources/templates/leak-rules.html`
  - `topbar` 에 `<button class="gb-menu-btn">` 추가 (모바일에서 배너 호출 불가하던 문제)
  - 두 `<table>` 을 `<div class="rule-table-wrap">` 으로 wrapping (태블릿 가로 스크롤용)
  - 두 render 함수의 모든 `<td>` 에 `data-label` 속성 추가 (8개 컬럼 × 2 탭)
  - CSS 미디어 쿼리 2단계 추가:
    - **≤900px**: `.rule-table-wrap { overflow-x: auto }` + `.rule-table { min-width: 760px }` → 태블릿에서 가로 스크롤
    - **≤640px**: 카드 레이아웃 (`thead` 숨김, `tr` → 카드, `td` flex + `::before { content: attr(data-label) }`), 툴바/페이저 세로 정렬, modal bottom-sheet, # 컬럼은 카드 헤더로 변환

### 변경 의도
- 8 컬럼 룰 테이블(Prefix/Library/Category/Severity/사용/작업 등)이 모바일에서 가로 잘림 + 횡 스크롤 미적용으로 우측 액션 버튼이 보이지 않았음.
- 햄버거 버튼 자체가 누락되어 모바일에서 좌측 배너에 접근할 길이 없었음.

### 내역
- **햄버거**: `files.html`/`history.html` 와 동일 패턴 (`gb-menu-btn` + `toggleMobileBanner()`). `.topbar-brand` 안 첫 자식.
- **wrapper 추가**: HTML 4 라인 증가. 기본 (`>900px`)에서는 시각적 변화 없음 — `.rule-table-wrap` 자체에 스타일 없음, 모바일에서만 활성화.
- **카드 레이아웃 (`≤640px`)**:
  - 각 row 가 `border-radius: 10px` + `box-shadow` + 10px gap 카드로 변환
  - 좌측 라벨 (`::before`, 64px min-width, 회색 caps) + 우측 값 (오른쪽 정렬)
  - `# (col-id)` 는 카드 상단의 헤더로 변환 (`border-bottom` 만)
  - `작업 (col-actions)` 는 카드 하단에 `border-top` 으로 분리, 라벨 숨김 + 우측 정렬 액션 버튼 2개 (terminal padding 8px 14px)
  - `Prefix`/`Pattern` 같은 긴 monospace 값은 `word-break: break-all` + `max-width: 70%` 로 카드 폭 내 줄바꿈
- **모달**: bottom sheet 스타일 (`align-items: flex-end` + `border-radius: 14px 14px 0 0` + `max-width: 100%`), 모달 버튼은 `flex: 1` 로 풀폭 분배

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=1014460, Started in 9.7s)
- **수동 확인 필요** (UI 변경):
  - 모바일(≤640px) `/admin/leak-rules`: 햄버거 → 배너 호출 가능 + 표가 카드로 변환 + 검색/Create/Export/Import 버튼이 잘 배치 + 편집 모달이 bottom-sheet 로 올라옴
  - 태블릿(641–900px): 표가 가로 스크롤되며 모든 컬럼 + 액션 버튼 접근 가능
  - 데스크톱(>900px): 기존 8 컬럼 표 동작 변화 없음

## [2026-05-18] 페이지 로딩 스피너 — 100ms 지연 표시로 flash 제거

**변경 파일:**
- 수정: `src/main/resources/templates/fragments/banner.html` — `_GB_LOADING_DELAY_MS = 100` 상수 + `_gbLoadingTimer` + `schedulePageLoading()` 추가. `handlePageNavClick` / `handlePageNavSubmit` 가 `showPageLoading()` 대신 `schedulePageLoading()` 호출. `hidePageLoading()` 에 timer clear 포함.

### 변경 의도
- 빠른 페이지 전환에서도 스피너가 짧게 깜빡이며 표시 → 시각적 노이즈. 100ms 안에 전환되면 표시 자체를 생략하는 게 표준 UX 패턴.

### 내역
- **메커니즘**: 클릭 시점에 `setTimeout(show, 100)` 만 예약. navigate 가 100ms 안에 완료되면 현재 페이지(와 함께 pending timer)가 파기되어 스피너 표시 함수 자체가 호출되지 않음. 100ms 이상 걸리는 무거운 페이지에서만 스피너 노출.
- 모바일도 동일 — 패널 닫힘(260ms) 후 navigate 시점부터 100ms 카운트. 패널 닫힘은 즉시 시작되므로 클릭 피드백은 유지.
- `hidePageLoading()` 이 pending timer 도 함께 clear → bfcache 복귀 시 중복 표시 방지.

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=1010932, Started in 9.5s)
- 수동 확인 권장: 빠른 페이지(`/files`, `/history` 등 캐시 hit) 이동 시 스피너 미표시, 무거운 페이지(`/analyze/xxx` 대용량) 이동 시 ~100ms 후 스피너 등장.

## [2026-05-18] 페이지 이동 UX — 모바일 배너 닫힘 jank 제거 + 전역 로딩 스피너

**변경 파일:**
- 수정: `src/main/resources/templates/fragments/banner.html`
  - CSS: `.gb-page-loading` / `.gb-page-loading-spinner` / `.gb-page-loading-label` + `@keyframes gbPageSpin` 추가
  - HTML: `gbBannerOverlay` 옆에 `<div id="gbPageLoading">` 전역 스피너 삽입
  - JS: 기존 mobile 자동닫기 핸들러(`navLinks.forEach`) → 통합 `handlePageNavClick` / `handlePageNavSubmit` 로 교체. `showPageLoading`/`hidePageLoading`/`_isNavigatingClick` 헬퍼 + `pageshow`(bfcache) 리스너 추가

### 변경 의도
- 모바일에서 좌측 드로어 열린 상태로 메뉴 링크 탭 시 **닫힘 애니메이션이 70~80% 진행 후 멈추고 새 페이지 로드 완료 시점에 비로소 사라지는 jank**. 원인: 클릭 직후 브라우저가 다음 페이지 요청을 시작하면서 현재 페이지의 transform transition 이 정지/중단됨.
- 동시에 데스크톱/모바일 모두 페이지 이동 중 "내가 클릭이 먹혔나" 피드백이 부족했음.

### 내역
- **모바일 드로어 닫힘 보장**: anchor 클릭 시 `e.preventDefault()` → `closeMobileBanner()` → 260ms `setTimeout` 후 `window.location.href = href`. CSS transition(300ms) 이 거의 완료된 뒤 navigate → 닫힘 동작이 매끄럽게 끝나고 새 페이지로 전환.
- **전역 페이지 로딩 스피너**: 모든 same-origin anchor 클릭 + same-window form submit 시 `.gb-page-loading.show` 표시. 새 페이지 paint 직전까지 현재 페이지 위에 떠 있음. fixed 오버레이 + 48px 회전 spinner + "페이지 로딩 중..." 라벨 + 반투명 backdrop.
- **클릭 판정 로직** (`_isNavigatingClick`): `defaultPrevented`/`target=_blank`/`download`/`Ctrl/Cmd/Shift/Alt`/`button≠0`/`#`/`javascript:`/`mailto:`/`tel:`/외부 origin/같은 페이지 hash 점프 — 모두 제외. submit 핸들러도 동일 원칙.
- **inline `onclick=preventDefault` 호환**: `toggleSubMenu(event,...)` 및 `logout` 링크는 `event.preventDefault()` 호출 → `defaultPrevented` 체크로 스피너 미표시. logout form submit 시점에는 submit 리스너가 스피너 표시.
- **bfcache 복귀 처리**: `window.pageshow(e.persisted)` 시 스피너 강제 hide → 브라우저 뒤로/앞으로로 돌아온 페이지에 잔존 스피너 방지.

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=1004780, Started in 9.5s)
- **수동 확인 필요 (UI 변경이라 자동 테스트 불가)**:
  - 모바일(폭 ≤900px): 햄버거 → 메뉴 열림 → "Files" 탭 → 드로어가 부드럽게 끝까지 슬라이드 닫힌 뒤 페이지 전환되는지 + 스피너 노출
  - 데스크톱: 좌측 배너에서 임의 메뉴 클릭 → 즉시 중앙 스피너 표시 → 새 페이지 로드 후 자연 소실
  - bfcache: 페이지 이동 후 브라우저 뒤로가기 → 스피너 잔존 없음
  - Logout / 외부 링크 / `#anchor` 클릭은 스피너 미표시인지

## [2026-05-17] Leak Suspect 룰 — Export / Import 기능 추가

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/controller/LeakRuleAdminController.java` — endpoint 4개 (`/library/export`, `/fallback/export`, `/library/import`, `/fallback/import`) + 공통 헬퍼 4개 (`exportEnvelope`, `validateImportRequest`, `importValidationErrors`, `importSuccess`) + `ImportRequest<T>` 내부 클래스 + 관련 import
- 수정: `src/main/resources/templates/leak-rules.html` — 두 탭 toolbar 에 Export/Import 버튼 2개씩, Import 모달 (파일선택 + Append/Replace 라디오 + 경고), JS 함수 6개 (`exportRules`, `openImport`, `closeImport`, `onImportModeChange`, `onImportFileChosen`, `submitImport`)

### 변경 의도
- ADMIN 이 룰을 환경 간 이동(dev↔prod)하거나 일괄 백업/복원을 하려면 페이지에서 한 건씩 클릭해야 했음. seeder JSON 포맷이 이미 있는데 운영 페이지에서 활용할 길이 없었음.

### 내역
- **Export (GET)**: wrapper 형식 `{version, type, exportedAt, count, rules}` 로 반환. `Content-Disposition: attachment; filename="leak-{library|fallback}-rules-YYYYMMDD-HHmmss.json"`. CSRF 불필요 (GET), 브라우저가 자동 다운로드.
- **Import (POST + JSON body)**: `{mode, rules}` 또는 export wrapper 그대로(`type` 필드로 탭 mismatch 차단) 수용. bare array 도 호환 (seeder 의 `src/main/resources/leak-rules/*.json` 직접 import 가능).
  - 모드: `append` (기본, 안전) / `replace` (truncate + insert)
  - **Atomicity 보장**: `@Transactional` + 전건 사전 validation → 한 건이라도 실패 시 400 + `errors:[{index,error}]` 반환, DB 미터치 (replace 의 DELETE 도 함께 롤백).
  - `id` 강제 null 화 → JPA 가 새 IDENTITY 발급 + `@PrePersist` 가 fresh timestamp.
  - 사이즈 상한 `IMPORT_MAX_ROWS = 10000`.
  - 성공 시 `ruleService.invalidate()` + `[LeakRuleImport]` INFO 로그.
- **UI**: `.btn-secondary` 재사용. Import 모달은 기존 `.modal-ov`/`.modal-box` 패턴. 클라이언트가 `FileReader` → `JSON.parse` → wrapper 면 `parsed.rules`, array 면 parsed 자체. type mismatch 사전 차단. replace 모드는 모달 경고 + `confirm()` 더블 가드 (CLAUDE.md "Settings 확인 모달 for destructive changes" 원칙).
- **Content-Disposition 함정**: 초기엔 `setContentDispositionFormData("attachment", ...)` 사용 → 헤더가 `form-data; name="attachment"` 로 잘못 생성됨 (form-data field name 의미). `h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"...\"")` 로 직접 set 으로 교체.

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=730255)
- API smoke (admin 로그인):
  - Export library: HTTP 200 + `Content-Disposition: attachment; filename="leak-library-rules-...json"` + wrapper 구조 (`type:"library"`, `count:66`) ✓
  - Export fallback: `type:"fallback"`, `count:33` ✓
  - Append round-trip: 66 → 132 (`{success:true, mode:"append", deleted:0, inserted:66}`) ✓
  - Replace round-trip: 132 → 66 (`{success:true, mode:"replace", deleted:132, inserted:66}`) ✓
  - **Atomicity**: replace 모드에서 첫 룰 prefix 를 빈 문자열로 변조 후 import → 400 + errors 반환, 테이블 카운트 변경 없음 (66 유지) ✓
  - Type mismatch: library 파일을 fallback import → 400 + "파일 type 이 'fallback' 가 아닙니다" ✓
  - mode 누락 → append 기본 ✓
  - CSRF 토큰 누락 → 403 ✓

## [2026-05-17] History — 검색바에 서버명 매칭 추가

**변경 파일:**
- 수정: `src/main/resources/templates/history.html` — `applyFilter()` 의 q 매칭 조건, `#searchInput` placeholder 텍스트

### 변경 의도
- History 페이지 검색바가 분석명(`data-name`) 만 매칭. 같은 서버에서 수집된 덤프들을 한 번에 좁히고 싶을 때 사용자가 dropdown 필터를 따로 사용해야 함. Files 페이지는 이미 "분석명·서버명 OR 매칭" 패턴으로 동작 → History 와 일관 유지가 필요.

### 내역
- `applyFilter()` q 분기: `data-name` 단독 → `data-name` OR `data-sort-server` (둘 중 하나라도 substring 일치하면 통과). Files 페이지의 동일 로직(`files.html` lines 729~733)과 동일.
- placeholder: `"분석명으로 검색…"` → `"분석명·서버명으로 검색…"` (사용자 힌트).

### 검증
- `mvn clean package -DskipTests` SUCCESS
- `bash restart.sh` 정상 기동 (PID=721869)
- 브라우저 확인 필요: History 검색바에 서버명 입력 → 해당 서버의 분석만 노출. 분석명·서버명 키워드 어느 쪽이든 일치 시 노출.

## [2026-05-17] AI Chat 검색 — LIKE ESCAPE 문자 '\\' → '|' 교체 (MariaDB SQL 문법 오류 수정)

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/repository/AiChatSessionRepository.java` — `searchSessions` JPQL `ESCAPE '\\'` → `ESCAPE '|'`
- 수정: `src/main/java/com/heapdump/analyzer/controller/AiChatController.java` — `listSessions()` 의 wildcard escape 로직을 `|` 기반으로 교체

### 변경 의도 (버그)
- 사용자 보고: 사이드바에서 "오늘 날씨를 알려줘" 검색해도 "채팅 이력이 없습니다" 표시.
- 원인: JPQL `ESCAPE '\\'` → Hibernate 가 SQL `ESCAPE '\'` (single backslash) 를 MariaDB 로 전송. MariaDB 기본 SQL_MODE 는 backslash-escape 활성화라 `'\'` 를 미완성 escape 시퀀스로 해석 → `1064 SQL syntax error near '\'))) order by`. 결과적으로 모든 검색이 500 으로 실패하던 상태.

### 내역
- JPQL ESCAPE 문자를 backslash 가 아닌 `|` (파이프) 로 교체 — MariaDB 문자열 리터럴에서 특별한 의미 없음. 컨트롤러 escape 도 동일하게 `|` → `||`, `%` → `|%`, `_` → `|_` 순서로 변경.

### 검증
- `mvn clean package -DskipTests` SUCCESS, `bash restart.sh` 정상 기동 (PID=720462)
- API smoke (admin 로그인 후):
  - `?q=오늘 날씨를 알려줘` → 2 hits (id=3, id=5, 정확히 매칭) ✓
  - `?q=날씨` → 3 hits (제목 매칭 2 + 메시지 본문 매칭 1) ✓
  - `?q=oom` (소문자) → 3 hits (대소문자 무시, 본문에 OOM 포함된 세션 매칭) ✓
  - `?q=50%` → 정상 응답 (wildcard escape 처리, SQL 오류 없음) ✓

## [2026-05-17] AI Chat — 세션 검색 (제목 + 메시지 본문) 추가

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/repository/AiChatSessionRepository.java` — 신규 `searchSessions` `@Query` 메서드 (EXISTS 서브쿼리)
- 수정: `src/main/java/com/heapdump/analyzer/controller/AiChatController.java` — `listSessions()` 에 `q` 파라미터 추가 + 분기 로직 + 파라미터 정규화 + LIKE wildcard escape
- 수정: `src/main/resources/templates/ai-chat.html` — `.session-filter-search` HTML/CSS 추가, `_searchDebounce`/`getCurrentQuery`/`syncQueryValue`/`onSessionSearchInput` JS 헬퍼 + `loadSessions()` 에 q 합류

### 변경 의도
- AI Chat 사이드바에 검색 입력이 없어, 사용자가 과거 대화를 찾으려면 카드를 일일이 훑거나 파일명 dropdown 으로만 좁힐 수 있었음. 메시지 본문 기준 검색이 불가능.

### 내역
- **Repository**: 단일 `@Query` 로 `(:usernameParam IS NULL OR ...) AND (:filenameParam IS NULL OR ...) AND (title LIKE :q OR EXISTS message)` 처리. EXISTS 는 semi-join 이라 DISTINCT 불필요. `ESCAPE '\\'` 로 wildcard 안전. `COALESCE(s.title, '')` 로 NULL 방어. 정렬은 기존 finder 와 동일 `updatedAt DESC`.
- **Controller**: q/filename/user 모두 trim + empty→null 로 일관 정규화. `effectiveUsername = admin ? userNorm : currentUser` — 비-ADMIN 은 user 파라미터 무시 강제 (기존 정책). q 가 있으면 신규 `searchSessions` 로 분기, 없으면 기존 4 finder 그대로 (회귀 방지). LIKE wildcard escape 는 `\` → `%` → `_` 순서.
- **Frontend**: `.session-filter-row` 최상단에 검색 input 배치 (셀렉트 위). `.session-filter-search` 클래스로 페이지 전용 CSS (공용 `.search-bar` 는 toolbar 전용이라 사이드바 220~320px 폭에 과대). 300ms debounce → `loadSessions()`. `querySelectorAll('.session-filter-search')` 로 배너 모바일 Chat 탭 클론 input 도 자동 동기화 (CLAUDE.md 함정 8번 패턴).
- DB 스키마/인덱스/마이그레이션 변경 없음. 권한 모델 변경 없음.

### 검증
- `mvn clean package -DskipTests` SUCCESS — JPA 시작 시 새 JPQL 검증 통과 (`Started HeapAnalyzerApplication in 9.873s`)
- `bash restart.sh` 정상 기동 (PID=718018)
- API smoke: `GET /api/ai-chat/sessions?q=test` → 302 (auth 필요 정상), JPA 쿼리 파싱 오류 없음
- 실기 확인 필요: 사이드바 검색창 입력 → 세션 제목/메시지 본문 매칭 세션만 노출, 빈 검색 시 무필터 복귀, `%`/`_` 리터럴 매칭, 한국어 매칭, 모바일 클론 동기화, 비-ADMIN 권한 우회 차단

## [2026-05-17] AI Chat — 모바일 진입 시 채팅 목록 기본 노출 + 인라인 백 네비게이션

**변경 파일:**
- 수정: `src/main/resources/templates/ai-chat.html` — `@media (max-width: 640px)` 레이아웃 재설계, `.chat-back-btn` 신규 스타일, `selectSession()`/`deleteSession()`/`backToSessionList()` JS 로직

### 변경 의도
- 사용자 보고: 모바일에서 AI Chat 진입 시 좌측 사이드바(채팅 목록)가 `display: none` 처리되어 빈 채팅 영역만 보이고, 채팅 목록은 배너 모바일 Chat 탭을 열어야만 접근 가능 → 처음 진입한 사용자가 "좌측"이 어디 있는지 혼란.

### 내역
- **CSS @media (max-width: 640px) 재설계**: 기본 진입 시 `.session-sidebar` 풀폭 표시 + `.chat-area` 숨김. `body.mobile-chat-active` 클래스가 붙으면 사이드바 숨김 + 채팅 영역 풀폭 전환.
- **`.chat-back-btn` 신규** — 채팅 영역 상단에 "← 채팅 목록" 백 버튼. 데스크탑/태블릿에서는 숨김, 모바일에서 `mobile-chat-active` 상태일 때만 노출.
- **`selectSession()` 모바일 분기 추가**: ≤640px 일 때 `body.mobile-chat-active` 클래스 추가 → 채팅 영역으로 전환. 기존 배너 자동 닫기 로직(≤900px)은 유지.
- **`backToSessionList()` 신규** — `mobile-chat-active` 클래스 제거하여 목록으로 복귀.
- **`deleteSession()` 정리**: 활성 세션 삭제 시 `mobile-chat-active` 클래스도 제거 (빈 채팅 영역에 머무르지 않도록).
- 데스크탑 ≥900px 동작 변경 없음. 배너 모바일 Chat 탭 클론도 그대로 유지 (보조 접근 경로).

### 검증
- `mvn clean package -DskipTests` SUCCESS
- `bash restart.sh` 정상 기동 (PID=710065)
- 모바일 실기 확인 필요: 진입 시 목록 노출 → 세션 클릭 → 채팅 영역 전환 → "← 채팅 목록" 버튼 복귀 흐름.

## [2026-05-17] History — 테이블 분석명 / 서버 chip 글자 크기 상향

**변경 파일:**
- 수정: `src/main/resources/templates/history.html` — `.hi-name` CSS + 서버 chip / Local 라벨 인라인 스타일

### 변경 의도
- History 테이블의 분석명(`hi-name`, 12px 모노스페이스), 서버 chip(10px), Local 라벨(11px) 이 다른 컬럼 텍스트 대비 작아 가독성 부족. Files 페이지와 동일 패턴으로 통일.

### 내역
- `.hi-name` `font-size: 12px → 13px`.
- 서버 chip `font-size: 10px → 12px`, padding `2px 7px → 2px 8px`.
- Local 라벨 `font-size: 11px → 12px` (서버 chip 과 동일 크기로 정렬).

### 검증
- `mvn clean package -DskipTests` SUCCESS
- `bash restart.sh` 정상 기동 (PID=708034)

## [2026-05-17] Files — 테이블 서버 칩 글자 크기 상향

**변경 파일:**
- 수정: `src/main/resources/templates/files.html` — 서버명 chip / Local 라벨 인라인 스타일

### 변경 의도
- Files 테이블 내 서버 컬럼의 chip 글자 크기(10px)와 Local 라벨(11px) 이 다른 컬럼 텍스트 대비 너무 작아 가독성이 떨어짐.

### 내역
- 서버명 chip `font-size: 10px → 12px`, padding `2px 7px → 2px 8px` (글자 크기에 맞춘 좌우 여백 미세 보정).
- Local 라벨 `font-size: 11px → 12px` (서버 chip 과 동일 크기로 정렬).

### 검증
- `mvn clean package -DskipTests` SUCCESS
- `bash restart.sh` 정상 기동 (PID=707101)

## [2026-05-17] Comparison History — 동일 (사용자, base, target) 조합 재진입 시 중복 row 누적 제거

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/ComparisonHistoryService.java` — `recordComparison()` upsert 전환, `DEDUPE_WINDOW_SEC`/`Duration` import 제거

### 변경 의도 (버그)
- 사용자 보고: Compare 화면에서 AI 인사이트 재분석 후 페이지 재방문 시 Comparison History 에 새 row 가 추가되는 현상. AI 인사이트 재분석은 기존 결과를 휘발(덮어쓰기)하는 동작이라 비교 이력에 새 row 가 누적될 이유가 없음.
- 원인: 기존 `recordComparison()` 은 `DEDUPE_WINDOW_SEC = 60L` 짧은 윈도우만 사용 → AI 인사이트 LLM 호출이 60초 이상 걸리면 재방문 시 새 row 가 생성됨.

### 내역
- `(comparedBy, baseFilename, targetFilename)` 조합으로 기존 row 가 있으면 **새 row 생성 대신 KPI/Suspect Count/`comparedAt` 갱신** (upsert). 없으면 신규 생성.
- 60초 dedupe 윈도우 + 관련 `Duration` import 제거. 어떤 시간 간격이든 같은 조합이면 항상 1건만 유지.
- 신규/갱신 로그 메시지 분리: `Created`/`Refreshed` 접두어.

### 검증
- `mvn clean package -DskipTests` SUCCESS
- `bash restart.sh` 정상 기동 (PID=693179)
- 동작 확인 필요: `/compare?base=X&target=Y` 진입 → AI 인사이트 재분석 → 재방문 시 Comparison History row 수가 1건으로 유지되고 `comparedAt` 만 갱신되는지 확인.

## [2026-05-17] 분석 진척도 역행 버그 수정 — Leak Suspects 단계 진입 시 55% 회귀

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java` — MAT 출력 reader 의 phase 전환 로직 + `phaseRank` static 헬퍼 추가

### 변경 의도 (버그)
- 사용자 보고: Top Components 리포트 중 진척도 68% → Leak Suspects 단계로 넘어가는 순간 55% 로 **역행**.
- 원인 분석:
  - MAT CLI 출력이 `Subtask: Leak Suspects` 진행 중에도 **`Subtask: Top Component...` 로 시작하는 라인을 다시 출력**할 수 있음 (MAT 의 sub-component report 단계).
  - 기존 코드의 분기 가드는 `!"top_components".equals(phase[0])` 형식 — phase 가 `suspects` 일 때도 조건이 true 가 되어 phase 가 `top_components` 로 **역방향 전이** + `pct[0] = 55` 로 강제 재설정.
  - 결과적으로 단계가 뒤에서 앞으로 회귀하고 진척도가 줄어드는 현상 발생.

### 내역
- **`phaseRank(String)` static 헬퍼 추가** — `init=0` / `overview=1` / `top_components=2` / `suspects=3`. 단방향 진행 순서를 정의.
- **분기 가드 변경**: `!"X".equals(phase[0])` → `phaseRank("X") > phaseRank(phase[0])`. 즉 *현재 단계보다 더 뒤 단계로의 전이만* 허용. 같은 단계/이전 단계로 돌아가려는 매칭은 무시.
- **`pct[0]` 재설정 보호**: 각 단계 진입 시 `pct[0] = N` → `pct[0] = Math.max(pct[0], N)`. 이미 더 높은 진척도라면 유지 (이중 안전망).
- 두 가드의 조합으로 어떤 출력 순서에서도 phase 와 진척도가 **단조 증가만** 보장.

### 검증
- `mvn clean package -DskipTests` SUCCESS (13.2s)
- `bash restart.sh` 정상 기동 (PID=686252)
- 다음 분석 실행 시 Leak Suspects 진입 후 진척도가 68 이상으로만 유지되는지 모니터링 필요 (코드상 역행 차단 보장).

## [2026-05-17] Compare — AI 인사이트 위험도 설명 글자 크기 증가

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html` — `cmpAiShowResult()` 의 `severityDesc` div 인라인 스타일 (12px/opacity .85 → 14px/line-height 1.5/opacity .92, margin-top 4→6px)

### 변경 의도
- AI 비교 분석 결과 위험도 배너의 사유 설명(`severityDesc`)이 12px + opacity 0.85 라 위험도 라벨(`CRITICAL`/`HIGH` 등 22px) 대비 너무 작고 흐림. 정보 가치 대비 가독성 부족.

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.2s)
- `bash restart.sh` 정상 기동 (PID=677991)

## [2026-05-17] Compare — AI 비교 분석 진행 중 페이지 이탈 방어 로직

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html` — 로딩 영역 시각 안내문 + `_cmpAiInFlight` 가드 핸들러 3종 (beforeunload / a click / form submit)

### 변경 의도
- AI 비교 분석은 LLM 호출이 포함되어 수 초~수십 초 소요. 진행 중 사용자가 배너 nav 클릭/URL 변경/탭 닫기/새로고침을 하면 분석이 중단되고 결과가 손실됨. 명시적 방어 필요.

### 내역
- **시각 안내**: `cmpAiLoading` 영역(데이터 수집 중 → LLM 분석 → 결과 저장 4-step indicator) 하단에 노란 배지 한 줄 추가: "⚠ 분석이 완료될 때까지 페이지를 이동하거나 새로고침/닫지 마세요. 작업이 중단됩니다."
- **`window.beforeunload`**: `_cmpAiInFlight === true` 일 때만 `e.preventDefault()` + `e.returnValue = ''` 로 브라우저 표준 confirm 다이얼로그 유도. (커스텀 메시지는 Chrome/Firefox 가 무시하지만 다이얼로그 자체는 표시됨.)
- **document `click` capture**: 진행 중 anchor 클릭 가로채기. `a[href]` 매치 후 fragment(`#`), `javascript:`, `target=_blank`, `download` 속성 케이스는 통과. 그 외는 confirm 으로 사용자 확인. `stopImmediatePropagation` 으로 페이지의 다른 click 핸들러 차단 (배너 nav 등).
- **document `submit` capture**: 폼 제출도 동일 confirm 패턴. capture phase 라 폼의 자체 submit 핸들러보다 먼저 실행.
- 핸들러는 IIFE 안에서 페이지 로드 시 1회만 바인딩 — `_cmpAiInFlight` 클로저 참조로 매번 최신 상태 검사.

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.5s)
- `bash restart.sh` 정상 기동 (PID=677187)

## [2026-05-17] UI 미세 조정 — Test Connection 스피너 + History 최근 탐지 건수 강조

**변경 파일:**
- 수정: `src/main/resources/templates/llm-settings.html` — `.spinner-tc` CSS + `@keyframes spinTC` 추가, `testLlmConnection()` 진행 메시지에 스피너 prepend
- 수정: `src/main/resources/templates/history.html` — `.det-recent-cnt` `font-size: 12px → 14px`, `color: #9CA3AF → #1F2937`, `font-weight: 600`

### 변경 의도
- "Testing connection..." 진행 상태가 정적 텍스트라 실제 진행 중인지 즉시 인지 어렵다는 피드백 → 좌측에 회전 스피너 (13×13 border-spin) 추가.
- Analysis History 상단 "최근 탐지 결과" 패널의 건수 표시가 회색 12px 라 정보 가치 대비 약해 보임 → 검정 14px + bold 로 강조.

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.0s)
- `bash restart.sh` 정상 기동 (PID=675050)

## [2026-05-17] UI 미세 조정 — Target Servers 가로 폭 + LLM Test Connection 결과 글자

**변경 파일:**
- 수정: `src/main/resources/templates/servers.html` (`.container max-width: 1280px → 1440px`)
- 수정: `src/main/resources/templates/llm-settings.html` (`#llmTestResult` `font-size: 12px → 13.5px`, `line-height: 1.5` 추가)

### 변경 의도
- Target Servers 페이지가 다른 데이터 그리드 페이지(files 1600, history 1800) 대비 좁아 가독성 떨어진다는 요청 → 1280 → 1440 (≈160px 증가).
- LLM Settings 의 Test Connection 결과 영역이 12px 로 다른 안내문 대비 작아 보임 → 13.5px + line-height 1.5 로 가독성 향상.

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.3s)
- `bash restart.sh` 정상 기동 (PID=670984)

## [2026-05-17] KRDS 캘린더 위젯 공통화 + History/Comparison/Transfer Logs 적용

**변경 파일:**
- 신규: `src/main/resources/static/js/calendar.js` (260 라인, `window.Calendar` 네임스페이스)
- 수정: `src/main/resources/static/css/common.css` (+115 라인, KRDS 캘린더 스타일 통합)
- 수정: `src/main/resources/templates/fragments/banner.html` (calendar.js 로드 1줄 추가)
- 수정: `src/main/resources/templates/files.html` (인라인 캘린더 CSS/JS 전부 제거 → Calendar.attach 한 줄로 축약, -270 라인)
- 수정: `src/main/resources/templates/history.html` (date-filter-row 추가 + applyFilter 에 캘린더 범위 필터)
- 수정: `src/main/resources/templates/comparison-history.html` (동일)
- 수정: `src/main/resources/templates/server-logs.html` (서버 사이드 — `_params.dateFrom`/`dateTo` 추가 + onChange 에서 loadData)
- 수정: `src/main/java/com/heapdump/analyzer/controller/ServerController.java` (3 endpoint + `buildSpec` 시그니처 확장, `parseDateBoundary` 헬퍼)
- 수정: 모든 페이지 `common.css?v=2026-05-17d → e` 캐시 무효화

### 변경 의도
- files 페이지에 도입한 KRDS 스타일 커스텀 달력 위젯을 **History / Comparison History / Transfer Logs** 에도 동일하게 적용 요청.
- 4페이지에 같은 코드를 중복하지 않도록 CSS·JS 를 공통 자원으로 추출 (common.* 패턴 준수).

### 내역
- **`/js/calendar.js` 신규 — `window.Calendar` 네임스페이스**:
  - `Calendar.attach({ startInputId, endInputId, startAreaId, endAreaId, storageKey, onChange })` — 페이지당 1 인스턴스
  - `Calendar.open(side)` / `Calendar.close(side)` / `Calendar.clear()` / `Calendar.getRange()`
  - localStorage 자동 영속화 (`<storageKey>PeriodStart` / `<storageKey>PeriodEnd`, ISO yyyy-mm-dd)
  - document 레벨 바깥 클릭 + ESC 핸들러는 1회만 바인딩 (`attach._docBound` 가드)
- **`common.css` 통합** — `.date-filter-row` / `.calendar-input` / `.cal-text` / `.krds-calendar-area` / `.calendar-wrap`·head·body·footer / `.btn-cal-move(.prev|.next)` / `.btn-cal-switch` / `.calendar-select` / `.calendar-tbl`·td 변형(`old/new/today/day-off/period.start.end/disabled`) / `.btn-set-date` / `.krds-btn(.text|.tertiary|.primary)` / `.sr-only` 등. 모바일(≤640px) 변형 포함.
- **`banner.html` 에 calendar.js 로드 추가** — common.js 직후, 모든 페이지에서 자동 사용 가능.
- **files.html 리팩터링** — 인라인 KRDS CSS/JS (~270 라인) 전부 제거. `Calendar.attach({ storageKey: 'files', onChange: applyFilter })` 한 줄로 대체. quick `periodFilter` 드롭다운과 캘린더 범위는 AND 결합.
- **history.html** — toolbar 아래 `date-filter-row` 신규. `storageKey: 'history'`. `applyFilter()` 가 `data-sort-date` (`h.lastModified`) 와 캘린더 범위 비교.
- **comparison-history.html** — `storageKey: 'cmpHistory'`. `data-sort-date` (`h.comparedAt`) 기준 필터.
- **server-logs.html — 서버 사이드 처리**:
  - `_params` 에 `dateFrom` / `dateTo` 필드 (yyyy-MM-dd) 추가
  - `Calendar.attach` onChange 콜백이 `_params.dateFrom/dateTo` 갱신 + `_params.page = 0` 후 `loadData()` 재호출
  - `toIsoDate(d)` 헬퍼, `onCalendarClear()` 래퍼 추가
  - localStorage 에서 초기 복원된 값을 `_params` 에도 반영 (페이지 reload 시 1회차 fetch 부터 기간 적용)
- **`ServerController` 백엔드 확장**:
  - `listTransferLogs` / `transferStats` / `exportTransferLogs` 3 endpoint 모두 `@RequestParam(required=false) String dateFrom, String dateTo` 추가
  - `buildSpec(q, status, serverId, dateFrom, dateTo)` — `parseDateBoundary(iso, endExclusive)` 로 LocalDateTime 변환 후 `cb.greaterThanOrEqualTo(startedAt, from)` + `cb.lessThan(startedAt, to+1day)` 술어 추가
  - 잘못된 입력(`invalid` 등)은 null 반환 → 필터 미적용으로 폴백 (방어적)
  - stats 도 `q + serverId + 기간` 적용 (status 만 무시)

### 검증
- `mvn clean package -DskipTests` SUCCESS (12.0s)
- `bash restart.sh` 정상 기동 (Started HeapAnalyzerApplication, PID=669488)
- `/js/calendar.js` HTTP 200 (13,596 bytes)
- `/css/common.css?v=2026-05-17e` HTTP 200 (12,629 bytes)
- `/api/servers/transfers?dateFrom=2026-01-01&dateTo=2026-12-31` HTTP 302 (인증 redirect — 컨트롤러까지 도달, 시그니처 정상)
- `dateFrom=invalid` 호출 시 500 없이 302 — `parseDateBoundary` null 폴백 정상
- `/files` `/history` `/comparison-history` `/servers/logs` 모두 HTTP 302 (인증 redirect)

## [2026-05-17] Files — 기간선택(KRDS 스타일 커스텀 달력 범위) 별도 행 추가

**변경 파일:**
- 수정: `src/main/resources/templates/files.html` (+~360 라인)
- 수정: `CHANGELOG.md`

### 변경 의도
- 기존 기간 quick 필터(전체/오늘/7·30·90일) 외에 임의 기간을 달력으로 지정해 조회하는 기능 요청.
- 사용자 제공 KRDS(한국 공공 디자인) 달력 마크업 스타일을 따르도록 toolbar 가 아닌 **별도 행**에 시작/종료일 두 개의 커스텀 캘린더 픽커 배치.

### 내역
- **별도 행 (`.date-filter-row`)**: toolbar 바로 아래 회색 패널에 `기간선택` 라벨 + 시작일 입력 + `~` + 종료일 입력 + `지우기` 버튼.
- **커스텀 캘린더 위젯 (KRDS 마크업)**: 입력 또는 달력 아이콘 클릭 시 `.krds-calendar-area` 팝업 표시.
  - 헤더: `‹` prev / `YYYY년` 드롭다운 / `MM월` 드롭다운 / `›` next
  - 본문: 7×6 day grid, 일/토 색상, `today` 테두리, 선택일 `period start end` 클래스로 강조
  - 푸터: `오늘` / `취소` / `확인` 3 버튼
  - 연도 드롭다운은 최근 21년 (현재년-20 ~ 현재년+1), 활성 옵션 자동 스크롤
- **상호작용**: 날짜 클릭은 임시 표시만, `확인` 클릭 시에만 input + localStorage + 필터 적용. `취소` 또는 바깥 클릭/ESC 로 닫기.
- **필터 동작**: quick 필터(`periodThreshold`) 와 커스텀 기간(`customStart`/`customEnd`) 은 **AND 결합** (둘 다 적용 시 더 좁은 범위). 종료일은 포함 (exclusive upper bound = end + 1day).
- **유효성**: 시작 > 종료 입력 시 양쪽 입력 빨간 테두리(`.invalid`) 시각 경고, 필터는 그대로 적용 → 자연스럽게 빈 결과.
- **영속화**: `filesPeriod` (quick 모드), `filesPeriodStart`/`filesPeriodEnd` (ISO yyyy-mm-dd). 페이지 reload 시 input 표시값 (YYYY.MM.DD) 복원.
- **모바일(≤640px)**: 라벨 풀폭, 두 input 가 절반씩 flex, 달력 폭 260px 축소.
- 이전(인라인 `<input type="date">`) 방식 CSS/HTML/JS 전체 제거.

### 검증
- `mvn clean package -DskipTests` SUCCESS (13.4s)
- `bash restart.sh` 정상 기동 (Started HeapAnalyzerApplication, PID=664977)
- `/files` GET 200 (16,237 bytes), `/login` 비인증 redirect 302 확인

## [2026-05-17] Phase 5C — analyze.html 인라인 JS 외부화 (-3,126 라인, -75%)

**변경 파일:**
- 신규: `src/main/resources/static/js/analyze.js` (3,125 라인, 149KB)
- 수정: `src/main/resources/templates/analyze.html` (4,183 → 1,057 라인)
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- analyze.html 이 4,183 라인 단일 파일로 비대화 (HTML 920 + 인라인 Thymeleaf 스크립트 17 + Thymeleaf 변수 블록 16 + **순수 JS 3,127 라인**). 브라우저 캐시 / 가독성 / IDE 처리 성능 모두 저해. 순수 JS 부분만 외부화하여 분리.

### 내역
- **추출 대상 확정**: 라인 1011-4137 의 `<script>...</script>` 블록 (3,127 라인). Thymeleaf 표현식 grep 결과 0 건 — 순수 JS 로 검증.
- **추출 방식**: `sed -n '1012,4136p' analyze.html > static/js/analyze.js` 로 JS 본문만 추출 (open/close `<script>` 태그 제외)
- **치환**: `sed -i '1011,4137c\<script src="/js/analyze.js?v=2026-05-17"></script>' analyze.html` 로 3,127 라인 → 1 라인 치환
- **잔존 인라인 JS (의도)**:
  - line 101-117: 에러 페이지 전용 `<script th:if="${error}" th:inline="javascript">` (Thymeleaf `[[${filename}]]` 사용) — 외부화 불가
  - line 993-1008: `<script th:inline="javascript">` 13 vars 정의 (`USED_BYTES`, `FREE_BYTES`, `TOTAL_BYTES`, `HEAP_PCT`, `FILENAME`, `LOG_TOTAL_LEN`, `OBJ_NAMES`, `OBJ_SIZES`, `OBJ_COUNTS`, `OBJ_PCTS`, `THREAD_STACKS`, `LLM_CHAT_RESTORE_INCLUDE_HISTORY`) — Thymeleaf 모델 노출용, 외부화 불가
- **script 로딩 순서 보장**: 인라인 변수 블록(L993-1008) → external script(L1011) 순서 그대로. 둘 다 sync (no defer/async) 라 정의 → 사용 순서 유지

### 검증
- `mvn clean package -DskipTests` SUCCESS (13.5s)
- `bash restart.sh` 정상 기동 (PID=652356)
- `/js/analyze.js` HTTP 200, 149,277 bytes, 3,125 라인 — 첫 라인 `// ── 사이드바 nav-item 위임 핸들러 ──` 확인
- `/analyze/result/jeus_admin.hprof` HTTP 200, 320,949 bytes, `<script src="/js/analyze.js">` 1 참조 확인

### Phase 5C 효과
- analyze.html **4,183 → 1,057 라인** (-3,126 라인, **-75%**)
- 브라우저 캐싱: 외부 JS 는 `?v=` 변경 시에만 재다운로드. HTML 매 요청 시 JS 본문 전송 불필요 → 약 149KB 절약/요청
- IDE/lint/git diff 가독성 ↑
- 보류 표 완전 비움 — **Phase 4/5/6/Phase 5B-2 모두 완결**

---

## [2026-05-17] Phase 5B-2 D2/D3 — settings/analyze 5 곳 추가 fetchJSON 치환

**변경 파일:**
- 수정: `settings.html` (3 곳 D2) — `saveSetting`, `saveCompressSetting`, `doDisableCompress`
- 수정: `analyze.html` (2 곳 D3) — `loadFullErrorLog` (line 109, r.text), 컴포넌트 parsed-detail (line 1321, r.json)
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- D1 라운드에서 보류한 D2(side-effect only) + D3(r.text)) 중 catch err 가 사용자 가시 메시지에 interpolate 되지 않는 안전 케이스 5 곳을 추가 정리. `Common.fetchJSON` 의 시맨틱이 그대로 적용 가능한 마지막 안전 후보들.

### 내역
- **D2 (3 곳, settings.html)**: `.then(r => { if(!r.ok) throw; toast(...) })` 패턴 → `Common.fetchJSON(url, {method:'POST'}).then(function(){ toast(...) })`. 파싱된 JSON 값을 사용하지 않으므로 `function()` (arg 없음) 으로 discard.
  - saveSetting (`/api/settings/unreachable?enabled=...`)
  - saveCompressSetting (`/api/settings/compress?enabled=true`)
  - doDisableCompress (`/api/settings/compress?enabled=false`)
- **D3 (2 곳, analyze.html)**: r.text() / r.json() 변형 케이스
  - line 109 `loadFullErrorLog` — 엔드포인트 `/analyze/log/{filename}` content-type `text/plain` → Common.fetchJSON 이 자동으로 text 반환. catch arg `err` 정의되어 있으나 본문에서 미사용 (`btn.textContent = 'Failed - Retry'` 만 호출). 안전.
  - line 1321 컴포넌트 parsed-detail — 엔드포인트 JSON 반환, catch 가 `function()` (arg 없음) 이라 'Not found' 메시지 회귀 무관. 안전.
- **D 최종 보류 (2 곳, 의도)**:
  - analyze.html line 1352 raw-detail: catch `err` 가 사용자 가시 fallback 텍스트 `'오류: ' + err` 에 interpolate → `Error: Not found` → `Error: HTTP 404: ...` 메시지 변화 회피
  - compare.html line 1543: catch err.message 가 `cmpAiShowError({error: e.message})` 로 사용자 표시 → 커스텀 한글 메시지 `'compare data 로드 실패 (404)'` 보존

### 검증
- `mvn clean package -DskipTests` SUCCESS (13.2s)
- `bash restart.sh` 정상 기동 (PID=650171)
- 잔존 grep: `if (!r.ok) throw new Error` 2 건 (compare + analyze raw-detail — 의도적 보류)
- `Common.fetchJSON` 총 사용처: 23 occurrences (D1 18 + D2/D3 5)
- 인증 후 페이지 검증:
  - `/settings` 200 OK + Common.fetchJSON 5 refs (loadAllData + doClearAll + 3 D2)
  - `/history` 200 OK + 2 refs (변경 없음, D1 라운드 결과 유지)
  - `/analyze/result/jeus_admin.hprof` 200 OK + 1 ref (parsed-detail fallback 로직)

### Phase 5B-2 최종 종합 (A + B + C + D1 + D2/D3)
- A (escHtml alias): 9 파일 12 정의
- B (appendCsrfToForm): 6 occurrences
- C (CSRF meta): ~14 occurrences
- D1 (fetchJSON 안전): 18 occurrences
- D2 (side-effect): 3 occurrences
- D3 (r.text/r.json 안전): 2 occurrences
- **누적 ~75 라인 인라인 축소** / 잔존 안전 fetch 패턴 0건 (사용자 가시 에러 보호 2건만 의도 보류)
- 보류 표 잔여: **analyze.html 분할** (SPA 화 결정 후 별도 사이클) 1 건만 남음

---

## [2026-05-17] Phase 5B-2 D1 — fetch + r.ok throw 패턴 18 곳 Common.fetchJSON 치환

**변경 파일:**
- 수정: `llm-settings.html` (7), `rag-settings.html` (1), `server-logs.html` (2), `settings.html` (2), `history.html` (2), `admin/users.html` (4) — 총 18 곳
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- `Common.fetchJSON` 의 시맨틱(non-2xx 시 throw + 자동 JSON 파싱 + 자동 CSRF/Content-Type 부착) 이 100% 일치하는 페이지 코드 패턴 18 곳을 일괄 치환. fetch 호출 라인 + r.ok throw 체크 + r.json 반환의 3 단계를 1 단계로 축소.

### 내역
- **치환 패턴**:
  ```js
  // Before (5~7 라인)
  fetch(url, { method:'POST', headers: {'Content-Type':'application/json'}, body: ... })
      .then(function(r){ if (!r.ok) throw new Error('HTTP '+r.status); return r.json(); })
      .then(function(d){ ... })

  // After (2~3 라인)
  Common.fetchJSON(url, { method:'POST', body: ... })
      .then(function(d){ ... })
  ```
- **자동 부착 효과**: POST 의 수동 `headers: {'Content-Type':'application/json'}` 구성 코드도 제거. fetchJSON 이 body 존재 시 자동 Content-Type 부착 + CSRF 토큰 자동 부착 (Spring Security 가 /api/** 면제라 헤더 추가는 무해).
- **회귀 위험 회피**:
  - 시맨틱 100% 일치 케이스만 선별 (`if (!r.ok) throw new Error('HTTP ' + r.status); return r.json();`)
  - 에러 메시지 포맷 차이가 있는 케이스 (compare.html `compare data 로드 실패 (404)`) 제외
  - side-effect only / r.text() 케이스 제외
- **대상별 효과**:
  - llm-settings.html (7): loadLlmSettings, toggleLlm, saveLlmConfig, saveLlmApiKey, testLlmConnection, saveChatPrompt, toggleChatRestoreMode
  - rag-settings.html (1): loadRagSettings
  - server-logs.html (2): Promise.all 의 두 fetch
  - settings.html (2): loadAllData, doClearAll
  - history.html (2): fetchDetect, openDetDayModal — `{credentials: 'same-origin'}` 옵션도 제거 (fetchJSON 이 자동 설정)
  - admin/users.html (4): loadUsers, loadRequests, loadHistory, loadActive

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.4s)
- `bash restart.sh` 정상 기동 (PID=647593)
- 잔존 grep: `if (!r.ok) throw new Error('HTTP'` 패턴 4 건 (D2/D3 — 의도 보류)
- `Common.fetchJSON` 사용처: 18 occurrences (마이그레이션 결과)
- 인증 세션으로 6 페이지 200 OK + 응답 HTML 에 `Common.fetchJSON` 참조 정상 분포 확인:
  - `/settings` (2) / `/settings/llm` (7) / `/settings/rag` (1) / `/history` (2) / `/admin/users` (4) / `/servers/logs` (2)

### Phase 5B-2 종합 (A + B + C + D1)
- A (escHtml alias): 9 파일 12 정의
- B (appendCsrfToForm): 6 occurrences
- C (CSRF meta): ~14 occurrences
- D1 (fetchJSON): 18 occurrences
- 누적 ~70 라인 인라인 축소 + 잔존 인라인 escHtml/CSRF 0건 / 안전 fetch 패턴 0건
- 보류 표 잔여: 5B-2 (D2/D3 — settings side-effect / analyze r.text()) + analyze.html 분할

---

## [2026-05-17] Phase 5B-2 C 그룹 — CSRF meta 직접 읽기 → Common.csrfToken/HeaderName 치환

**변경 파일:**
- 수정: `comparison-history.html` (2 occurrences) / `history.html` / `files.html` / `rag-settings.html` (`getCsrfHeaders` 함수 내부) — 4 라인 `var csrfMeta + csrfHeaderMeta + if(...)headers[...]=...` → 2 라인 `var csrfToken = Common.csrfToken(); if (csrfToken) headers[Common.csrfHeaderName()] = csrfToken;`
- 수정: `admin/users.html` / `server-detail.html` / `leak-rules.html` — 모듈 레벨 `var _csrf/_csrfToken/CSRF_TOKEN = document.querySelector('meta[name="_csrf"]').content` RHS만 `Common.csrfToken()` / `Common.csrfHeaderName()` 호출로 치환 (변수명·다운스트림 사용처 무변경)
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- A+B 라운드에서 마이그레이션 보류했던 CSRF meta 직접 읽기 ~14 occurrences 를 `Common.csrfToken()` / `Common.csrfHeaderName()` 로 통합. 잔존 인라인 `document.querySelector('meta[name="_csrf"]')` 패턴 0건 달성.

### 내역
- **var-stored csrfMeta 패턴** (4 파일 5 occurrences):
  - comparison-history.html `submitBulkDelete` + `confirmDelete` (2), history.html `submitBulkDelete`, files.html `submitBulkDelete`, rag-settings.html `getCsrfHeaders`
  - 4 라인 → 2 라인 압축
- **모듈 레벨 변수 패턴** (3 파일 6 라인):
  - admin/users.html `_csrfToken`/`_csrfHeader` (다운스트림 4 곳에서 재사용)
  - server-detail.html `_csrf`/`_csrfHeader` (다운스트림 fetch 헤더)
  - leak-rules.html `CSRF_TOKEN`/`CSRF_HEADER`
  - 변수명·재사용 코드 무변경 — RHS 만 Common.* 호출로 치환해 회귀 위험 최소화
- **D (fetchJSON) 의도적 보류**:
  - 107 fetch 호출이 페이지별로 에러 핸들링 양상이 다양 (Common.fetchJSON 은 non-2xx throw, 페이지 코드는 `r.json().then(d => if d.success)` 패턴 다수)
  - 일괄 sed 부적합 → rag-settings 등 페이지 단위 별도 사이클 진행 권장

### 검증
- `mvn clean package -DskipTests` SUCCESS (12.0s)
- `bash restart.sh` 정상 기동 (PID=640591)
- 잔존 grep: `document\.querySelector\(.*meta\[name=_csrf` 패턴 0건
- 인증 세션으로 6 페이지 200 OK + 응답 HTML 에 `Common.csrfToken`/`Common.csrfHeaderName` 참조 확인:
  - `/files` (2) / `/history` (2) / `/comparison-history` (4) / `/admin/users` (2) / `/settings/rag` (2) / `/admin/leak-rules` (2)

### Phase 5B-2 종합 (A + B + C)
- A (escHtml alias): 9 파일 12 정의 → 12 alias 라인
- B (appendCsrfToForm): 6 occurrences 18 라인 → 6 라인
- C (CSRF meta): ~14 occurrences ~28 라인 → ~14 라인
- 누적 인라인 ~50 라인 축소 + 잔존 인라인 escHtml/CSRF 0건 (D 제외)
- 보류 표 잔여: 5B-2 (D fetchJSON) + analyze.html 분할

---

## [2026-05-17] Phase 5B-2 — escHtml + CSRF appendCsrfToForm 마이그레이션

**변경 파일:**
- 수정: 9 페이지 escHtml/escapeHtml 12 정의 → `var ... = Common.escHtml;` 별칭
  - ai-chat / compare / index (2: escHtml + escapeHtml) / leak-rules / analyze (3: 2311 + 2579 + 3463) / admin/users / server-logs / history / servers
- 수정: 5 페이지 CSRF append 6 occurrences → `Common.appendCsrfToForm(f)` 1 라인
  - index / history / progress / analyze / files (2)
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- `common.js` (Round 5B-1 인프라) 가 `Common.escHtml` / `Common.appendCsrfToForm` 을 14 페이지에 배포한 상태에서, 페이지별 동명 함수 인라인 정의를 정리. 호출처는 무변경 (alias 패턴).
- 보너스: textContent 트릭 기반 escHtml 3 변종(`<>&` 만 escape)이 Common 의 5 문자(`&<>"'`) escape 로 강화 → 속성 컨텍스트에서 발생할 수 있는 XSS 잠재 위험 감소.

### 내역
- **A 그룹 (escHtml/escapeHtml)**:
  - 9 파일 12 정의 제거 → `var escHtml = Common.escHtml;` (또는 `var escapeHtml = Common.escHtml;`) 단일 라인 alias 로 치환
  - 호환성: 함수 선언(hoisted) → var 할당(non-hoisted) 전환이나, 모든 호출처가 DOM ready/이벤트 핸들러 내부에서 실행되어 runtime 시 alias 가 이미 할당된 상태 → 동작 동일
  - 변종 흡수:
    - textContent + innerHTML 트릭 (`<>&` 만) — index/analyze (2 곳)/servers → 5 문자 escape 강화
    - 3 문자 regex (`&<>`) — ai-chat/compare → 5 문자 escape 강화
    - 4 문자 regex (`&<>"`) — leak-rules → 5 문자 escape 강화
    - 5 문자 regex/맵 — admin-users/server-logs/history/analyze (3463) → 동작 100% 일치
    - `&#039;` vs `&#39;` — analyze (3463) → 양쪽 모두 동일 ' 엔터티이므로 시맨틱 동일
- **B 그룹 (CSRF appendCsrfToForm)**:
  - 6 occurrences 의 3 라인 패턴 (`var ci = createElement('input'); ci.type='hidden'; ci.name='_csrf'; ci.value = querySelector('meta[name="_csrf"]').content; f.appendChild(ci);`) → `Common.appendCsrfToForm(f)` 1 라인
  - 동작 100% 일치 (Common 구현이 동일 input 생성 + token 부착 + appendChild)
  - 페이지: index `postRedirect`, history 삭제 form, progress `postRerun`, analyze `rerunAnalysis`, files 2 곳 (`_delTarget` / `_purgeTarget` 삭제 form)
- **보류 (별도 사이클)**:
  - C 그룹: `var _csrf = document.querySelector('meta[name="_csrf"]').content` 형태 15+ 사용처 — 변수 저장 후 재사용 패턴이 페이지별로 달라 일괄 sed 부적합
  - D 그룹: `Common.fetchJSON` 마이그레이션 — 에러 핸들링이 페이지 코드와 다름 (Common 은 throw, 페이지는 `r.json().then(d => if d.success)`)

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.7s)
- `bash restart.sh` 정상 기동 (PID=635826)
- 잔존 인라인 grep: `function escHtml|function escapeHtml` 0 건 / `ci.value = ...meta[name="_csrf"]` 0 건
- 인증 세션으로 7 페이지 200 OK + 응답 HTML 에 `Common.escHtml`/`Common.appendCsrfToForm` 참조 확인:
  - `/` (5 refs) / `/files` (2) / `/history` (3) / `/servers` (2) / `/admin/users` (2) / `/comparison-history` (0, 마이그레이션 대상 없음) / `/ai-chat` (2)

---

## [2026-05-17] Phase 5A-3 Round 4 — 테이블 4 family base + sortable 통합

**변경 파일:**
- 수정: `src/main/resources/static/css/common.css` (+30 라인: 4 family table base + sortable 공통)
- 수정: 5 페이지 (comparison-history / history / files / servers / admin/users) 인라인 테이블 정의 제거 (~50 라인)
- 수정: 13 페이지 `common.css?v=2026-05-17c` → `?v=2026-05-17d`
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- `.htable`/`.ftable`/`.stable`/`.utable` 4 테이블 family 가 페이지별로 동일한 base 스타일을 중복 정의하던 패턴 통합. padding/font-size 변형(10px 12px @ 11px vs 12px 16px @ 12px) 과 sort-arrow font-size 차이(9 vs 10px) 는 인라인 유지하여 시각 회귀 0.

### 내역
- **common.css 확장 (table base)**: 4 family multi-selector 로 통합
  - `.htable, .ftable, .stable, .utable { width: 100%; border-collapse: collapse; font-size: 13px }`
  - `thead { background: #F9FAFB }`
  - `th { text-align: left; font-weight: 600; color: #6B7280; text-transform: uppercase; letter-spacing: .3px; border-bottom: 1px solid #E5E7EB; white-space: nowrap }`
  - `td { border-bottom: 1px solid #F3F4F6; vertical-align: middle }`
  - `tbody tr:last-child td { border-bottom: none }`
  - `tbody tr:hover { background: #F9FAFB }`
- **common.css 확장 (sortable)**: htable/ftable/stable 공통
  - `th.sortable { cursor: pointer; user-select: none }`
  - `th.sortable:hover { background: #F3F4F6 }`
  - `th.sortable.sort-active .sort-arrow { opacity: 1; color: #2563EB }`
- **페이지별 잔존 (의도)**:
  - 모든 테이블 family: `th { padding: ...; font-size: ... }` + `td { padding: ... }` 변형
  - htable: `min-width: 960px` (history) / `1100px` (comparison-history)
  - `.stable td a:hover { text-decoration: underline }` (페이지 고유)
  - sort-arrow 자체: htable/ftable 9px + vertical-align:middle, stable 10px — 인라인 유지
  - @media 반응형 `.htable { font-size: 14px }` 등 — 인라인 유지
- **변경 없음**:
  - `server-logs.html` (.ltable): hover `#FAFAFA` + th 직접 background 구조라 multi-selector 제외
  - 기타 페이지의 테이블이 아닌 list/grid 구조

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.9s)
- `bash restart.sh` 정상 기동 (PID=631467)
- `/css/common.css` 응답에 `.htable, .ftable, .stable, .utable { ... }` multi-selector 4 family 정의 확인
- 인증 세션으로 5 테이블 페이지 모두 **200 OK + 정상 렌더**:
  - `/files` (ftable, 11 refs) / `/history` (htable, 12 refs) / `/servers` (stable, 14 refs) / `/admin/users` (utable, 14 refs) / `/comparison-history` (htable, 12 refs)

### Phase 5A-3 종합 (Round 1~4)
- `common.css`: `.modal-ov` + `.modal-ov.open` + `@keyframes modalIn` + `.modal-box` base + btn 색상 3 그룹 + 테이블 4 family base + sortable 공통 ≈ **+80 라인**
- 10+ 페이지에서 **~150 라인** 인라인 중복 제거
- 시각 회귀 0 (모든 페이지 변형은 cascade override 패턴으로 보존)
- 보류 표에서 5A-3 완전 제거. ltable 통합은 hover 색상이 달라 별도 사이클.

---

## [2026-05-17] Phase 5A-3 Round 3 — 버튼 색상 utility 3 그룹 통합

**변경 파일:**
- 수정: `src/main/resources/static/css/common.css` (+22 라인: 3 색상 그룹 multi-selector + hover)
- 수정: 8 페이지 (comparison-history / login / servers / compare / settings / files / history / admin/users) 인라인 색상 정의 제거 (~40 라인)
- 수정: 13 페이지 `common.css?v=2026-05-17b` → `?v=2026-05-17c`
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- 동일한 색상(`#F3F4F6/#374151`, `#EF4444/#fff`, `#2563EB/#fff`)을 페이지별 다른 클래스명(`.mbtn-cancel` vs `.btn-cancel` vs `.sa-btn-cancel` 등)으로 8 페이지에 중복 정의하던 패턴을 multi-selector 로 통합. 박스 속성(padding/font-size/border-radius/font-weight) 변형은 페이지별로 큰 차이가 있어 인라인 유지 — 색상만 분리.

### 내역
- **common.css 확장**: 3 색상 그룹을 multi-selector 로 정의
  - cancel: `.mbtn-cancel, .btn-cancel, .sa-btn-cancel { background: #F3F4F6; color: #374151 }` + `:hover { background: #E5E7EB }`
  - danger: `.mbtn-del, .mbtn-danger, .btn-delete, .sa-btn-del { background: #EF4444; color: #fff }` + `:hover { background: #DC2626 }`
  - primary: `.mbtn-save, .mbtn-primary, .mbtn-confirm, .btn-download { background: #2563EB; color: #fff }` + `:hover { background: #1D4ED8 }`
- **페이지별 정리**: 각 페이지에서 위 패턴과 일치하는 인라인 색상 정의(`.클래스 { background:; color: }` + `:hover`) 제거, 박스 속성(`.modal-btns button { padding: ...; font-weight: ...; ... }`) 은 유지
- **인라인 잔존 (의도)**:
  - `.mbtn-save:disabled { background: #93C5FD; cursor: wait }` (login)
  - `.sa-btn-del:disabled { background: #FCA5A5; cursor: not-allowed }` (files / history / comparison-history)
  - `:disabled` variant 는 페이지별로 다르고 specificity 가 base 보다 높아 cascade override 가 정상 동작
- **변경 없음**: `leak-rules.html` (common.css 미적용 — 추후 별도 사이클)

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.8s)
- `bash restart.sh` 정상 기동 (PID=627115)
- `/css/common.css` 응답에 3 그룹 multi-selector 정의 모두 확인 (`.mbtn-cancel, .btn-cancel, .sa-btn-cancel { ... }` 형식)
- 8 페이지 색상 정의 제거 확인 (인라인 `/* 색상은 common.css 에서 제공 */` 주석으로 대체)

### Phase 5A-3 종합 (Round 1 + 2 + 3)
- `common.css`: `.modal-ov` + `.modal-ov.open` + `@keyframes modalIn` + `.modal-box` base 4 속성 + btn 색상 3 그룹 통합 ≈ 50 라인 추가
- 8~10 페이지에서 ~100 라인 인라인 중복 제거
- grid 패턴(`.htable`/`.ftable`/`.stable`/`.utable`)은 명명 + thead/td 토큰 차이가 커서 별도 사이클로 이연
- 시각 회귀 0 (모든 페이지 변형은 cascade override 패턴으로 보존)

---

## [2026-05-17] Phase 5A-3 Round 2 — .modal-box base 4 속성 추출

**변경 파일:**
- 수정: `src/main/resources/static/css/common.css` (+10 라인: `.modal-box { background; border-radius:12px; padding:24px; box-shadow }`)
- 수정: `src/main/resources/templates/history.html` / `comparison-history.html` / `admin/users.html` (.modal-box 4 속성 제거, width/max-width/animation만 유지)
- 수정: `src/main/resources/templates/login.html` (background/padding/box-shadow 제거, border-radius:14px 변형 override 유지)
- 수정: `src/main/resources/templates/settings.html` (`.modal-ov` + `@keyframes modalIn` 잔존 정리 + `.modal-box` 공통 속성 제거, padding:28px 30px 변형 override 유지)
- 수정: 13 페이지 `common.css?v=2026-05-17` → `?v=2026-05-17b` 캐시 무효화
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- Round 1 (`.modal-ov` base + `@keyframes modalIn`) 추출 후 잔존하던 `.modal-box` 공통 속성 4 개를 추가 추출. 페이지별 `width`/`max-width` 변형과 일부 페이지의 `border-radius:14px` / `padding:28 30` override 는 인라인 cascade 로 보존.

### 내역
- **common.css 확장**: `.modal-box { background:#fff; border-radius:12px; padding:24px; box-shadow:0 20px 60px rgba(0,0,0,.2); }` — 5 페이지에서 100% 일치하는 4 속성. `animation` 은 files.html 이 `modalIn` 미사용이라 미포함 (페이지별 인라인 유지).
- **페이지별 정리**:
  - `history.html` / `comparison-history.html`: `.modal-box` 가 1 라인으로 축소 (`width:90%; max-width:420px; animation:modalIn .2s ease`)
  - `admin/users.html`: 동일 패턴 (max-width:440px), `.modal-ov { background: rgba(0,0,0,.4) }` overlay 변형은 인라인 유지
  - `login.html`: `.modal-box { border-radius:14px; width:calc(100%-32px); max-width:440px; animation:modalIn; max-height:calc(100vh-32px); overflow-y:auto }` — `border-radius:14px` 변형 override 유지
  - `settings.html`: 전 라운드에서 누락된 `.modal-ov` + `.modal-ov.open` + `@keyframes modalIn` 잔존 블록 함께 정리. `.modal-box { padding:28px 30px; width:92%; max-width:500px; animation:modalIn .2s ease }`
- **변경 없음**: `files.html` (box-shadow `0 20px 40px rgba(0,0,0,.15)` 변형 + `fadeIn` 애니메이션, `modalIn` 미사용 — 인라인 override 가 common 을 가림) / `leak-rules.html` (common.css 미적용)

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.5s)
- `bash restart.sh` 정상 기동 (PID=622396)
- `/css/common.css` 응답에 `.modal-box base` 정의 확인 (`background: #fff; border-radius: 12px; padding: 24px; box-shadow: 0 20px 60px rgba(0, 0, 0, .2)`)
- `/login` 응답 200, 인라인은 `border-radius:14px` + width/max-height/overflow-y/animation 만 잔존 확인 (background/padding/box-shadow 중복 제거 확인)

---

## [2026-05-17] Phase 5A-3 — 모달 공통 base 패턴을 common.css 로 추출 (부분)

**변경 파일:**
- 수정: `src/main/resources/static/css/common.css` (+15 라인: `.modal-ov` base / `.modal-ov.open { display: flex }` / `@keyframes modalIn`)
- 수정: `src/main/resources/templates/history.html` (modal-ov + open + modalIn 3 블록 제거)
- 수정: `src/main/resources/templates/comparison-history.html` (동일 3 블록 제거)
- 수정: `src/main/resources/templates/login.html` (modal-ov + modalIn 제거, modalFade 인라인 유지)
- 수정: `src/main/resources/templates/admin/users.html` (modalIn 만 제거 — overlay 가 `.4` 변형이라 modal-ov 인라인 유지)
- 수정: 13 페이지 `common.css?v=2026-05-12` → `?v=2026-05-17` 캐시 무효화
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- 6 개 페이지에서 `.modal-ov` overlay base + `@keyframes modalIn` 키프레임이 거의 동일하게 중복 정의되어 있던 것을 common.css 로 통합. 페이지별 미세 변형(overlay opacity .35/.4/.45, padding 변형, fadeIn vs modalFade vs modalIn 애니메이션)은 인라인 override 패턴으로 보존하여 시각 회귀 0.

### 내역
- **common.css 추가**: 6 페이지에서 100% 일치하던 `.modal-ov` base 속성 (`position: fixed; inset: 0; z-index: 200; background: rgba(0,0,0,.45); align-items/justify-content: center`), `.modal-ov.open { display: flex }` 기본 동작, `@keyframes modalIn` 키프레임. 페이지별 `.modal-ov.open` 의 추가 animation 속성(`fadeIn`/`modalFade` 등) 은 CSS 캐스케이드 순서상 인라인이 common 을 override 하므로 충돌 없음.
- **history.html / comparison-history.html**: overlay `.45` + animation 없음 → 3 블록 모두 안전 제거.
- **login.html**: overlay `.45` 일치하나 `.modal-ov.open` 에 `animation: modalFade` 있음 → `.modal-ov` base 와 `@keyframes modalIn` 만 제거, `.modal-ov.open { animation: modalFade .15s ease }` 와 `@keyframes modalFade` 는 인라인 유지.
- **admin/users.html**: overlay 가 `.4` 변형(common 의 `.45` 와 다름) → `.modal-ov { background: rgba(0,0,0,.4) }` 인라인 유지, `@keyframes modalIn` 만 제거.
- **변경 없음 (변형 보존)**:
  - `files.html`: overlay `.35` + `fadeIn` 애니메이션 (modalIn 미사용)
  - `leak-rules.html`: common.css 미적용 + overlay `padding: 20px` 변형
- **btn/grid 패턴 미추출**: 페이지별 명명·디자인 토큰 차이 큼(`.btn-cancel`/`.mbtn-cancel`/`.mbtn-del`/`.mbtn-danger`, 버튼 padding `7px 16px`/`8px 18px`/`9px 16px`, `.modal-box` max-width `380`/`420`/`440`/`760px`, border-radius `12`/`14px`). 디자인 토큰 표준화 사이클 후 별도 진행 권장.

### 검증
- `mvn clean package -DskipTests` SUCCESS (11.6s)
- `bash restart.sh` 정상 기동 (10.4s, PID=619728)
- `/css/common.css` 200 + 본문 끝부분에 `.modal-ov` / `@keyframes modalIn` 정의 확인
- `/login` HTML 의 `?v=2026-05-17` 적용 + 인라인 `.modal-ov.open { animation: modalFade .15s ease }` 만 남음 (base/modalIn 중복 제거 확인)

---

## [2026-05-17] Phase 4B-2 — HeapDumpController API 도메인별 6 분할

**변경 파일:**
- 삭제: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java` (2,898 라인)
- 신규: `HeapAnalysisApiController` / `HeapReportApiController` / `HeapFileApiController` / `HeapHistoryApiController` / `HeapSystemApiController` / `HeapAiApiController`
- 신규: `model/dto/` 패키지 — DTO 11 개 (`AnalysisHistoryItem`/`DailyDetection`/`ServerSeries`/`DetectionSummaryItem`/`DetectionAggregate`/`DetectionDayFile`/`DetectionRecentItem`/`ClassDiff`/`HistogramDiff`/`SuspectDiff`/`KpiDiff`)
- 신규: `service/HeapHistoryAggregator.java` (585 라인) — `buildHistory` / `aggregateDetections` / `build*Diff` / `buildAnalysisName` / `truncateLog` / `formatDuration`
- 신규: `util/AuthUtil.java` — `isAdmin(Authentication)` static
- 수정: `HeapDumpViewController` (apiController 주입 제거 → HeapHistoryAggregator 주입, DTO import 갱신)
- 수정: `ComparisonHistoryController` (HeapDumpController 주입 제거 → AuthUtil 사용)
- 수정: `ComparisonHistoryService` (`HeapDumpController.KpiDiff` → `model.dto.KpiDiff` import 갱신)
- 수정: `SECURITY_REFACTOR_PLAN.md`, `CHANGELOG.md`

### 변경 의도
- 4B-1 (View + 단일 API 2 분할) 완료 후 잔존한 `HeapDumpController` (2,898 라인, 50 엔드포인트 + 11 inner DTO + 16 public helper) 를 도메인별로 분리 — 단일 파일 책임 비대화 해소 + 도메인 경계 명확화.

### 내역
- **6 도메인 분할** (URL/외부 시그니처 100% 무변경):
  - `HeapAnalysisApiController` (80): SSE `/analyze/progress/*`, `POST /api/analyze/cancel/*`, `GET /api/queue/status`
  - `HeapReportApiController` (350): MAT HTML (`/report/*/overview`/`/suspects`/`/top_components`/`component-detail*`/`component-list`), `/thread-stacks`, mat-page ZIP iframe, PDF print + log 청크
  - `HeapFileApiController` (141): `/api/files/bulk-delete`, `/api/upload`, `/api/upload/check`, `/download/*`
  - `HeapHistoryApiController` (283): `/api/history*`, `/api/history/detections*`, `/api/results/clear` (`/api/cache/clear` alias), `/api/compare/data`
  - `HeapSystemApiController` (432): `/api/settings/unreachable|compress|database*`, `/api/mat/heap*`, `/api/mat/heap-check`, `/api/system/status`, `/api/disk/check`, `/api/settings`
  - `HeapAiApiController` (754): `/api/llm/*` 15 + `/api/settings/rag*` 7 = 22 엔드포인트 (LLM 설정/연결/분석/인사이트/compare/chat/RAG 설정·검색·임베딩)
- **DTO 추출 (`model/dto/`)**: 기존 inner static class 11 개를 독립 파일로 이전. 컨트롤러 import 만 변경하면 동작.
- **헬퍼 추출 (`HeapHistoryAggregator` @Component)**: `analyzerService` 주입받아 `buildHistory` / `aggregateDetections` / `build*Diffs` / `buildKpiDiff` / `buildClassSizeMap` / `buildAnalysisName` / `truncateLog` / `formatDuration` 제공. 내부 헬퍼 (`normalizeSuspectKey`/`eqNullSafe`/`suspectStateOrder`/`severityWeight`) 는 private static.
- **AuthUtil**: `HeapDumpController.isAdmin()` 같은 시그니처의 static util 로 추출. View 컨트롤러·ComparisonHistoryController·6 API 컨트롤러 모두 직접 호출.
- **종속 컨트롤러 갱신**:
  - `HeapDumpViewController`: `apiController` (HeapDumpController) 주입 제거 → `HeapHistoryAggregator` 주입. DTO 참조 `HeapDumpController.AnalysisHistoryItem` → `com.heapdump.analyzer.model.dto.AnalysisHistoryItem` (등 11 종).
  - `ComparisonHistoryController`: HeapDumpController 주입 제거. `apiController.isAdmin(auth)` → `AuthUtil.isAdmin(auth)`.
  - `ComparisonHistoryService`: `import com.heapdump.analyzer.controller.HeapDumpController.KpiDiff` → `import com.heapdump.analyzer.model.dto.KpiDiff`.

### 검증
- `mvn clean package -DskipTests` SUCCESS (95 source files, 11.6s)
- `bash restart.sh` 정상 기동 (10.55s, PID=616090)
- 무인증: `/`, `/login`, `/api/*` 모두 302 (Spring Security login redirect) / `/login` 본문 200 — 매핑 정상
- 인증 세션 후 6 도메인 대표 API 모두 **200 + 정상 JSON 본문**:
  - `/api/system/status` → matCliReady/diskUsedPercent/jvm/queueSize
  - `/api/queue/status`, `/api/history`, `/api/disk/check`, `/api/mat/heap`, `/api/settings/rag` 모두 OK
- 회귀 위험 큰 SSE/PDF/iframe 경로는 로그인 후 화면 검증 권장 (URL 무변경이므로 클라이언트 코드 수정 불필요).

---

## [2026-05-17] SECURITY_REFACTOR_PLAN 보류 항목 표 정리

**변경 파일:**
- 수정: `SECURITY_REFACTOR_PLAN.md`
- 수정: `CHANGELOG.md`

### 변경 의도
- 문서 본문(Phase 4A, Line 50–63)에는 이미 4A-2 LlmConfigService / 4A-3 RagConfigService / 4A-4 FileManagementService 모두 2026-05-12 자 완료로 기록되어 있는데, 하단 "보류 항목" 표(Line 108–110)에 같은 항목이 잔존해 자체 모순이 발생.

### 내역
- 보류 항목 표에서 4A-2 / 4A-3 / 4A-4 행 제거. 실제로 보류 중인 4B-2 / 5A-3 / 5B-2 / analyze.html 분할만 남김.
- 표 하단에 정리 기록 주석(2026-05-17, 본문 Line 50–63 참조) 추가.
- 코드 영향 없음 — 문서 정리 단독 변경.

### 검증
- 실제 코드 상태로 검증: `LlmConfigService.java` (1,025 라인) / `RagConfigService.java` (421 라인) / `FileManagementService.java` 모두 존재. `HeapDumpAnalyzerService.java` 1,965 라인 — 플랜 본문이 명시한 완료 수치(`3,581 → 1,965`)와 정확히 일치.

---

## [2026-05-16] Leak Suspect 룰 엔진 DB 마이그레이션 Phase 4 (Admin CRUD UI)

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/controller/LeakRuleAdminController.java`
- 신규: `src/main/resources/templates/leak-rules.html`
- 수정: `src/main/resources/templates/fragments/banner.html` (Settings 아코디언에 "Leak Rules" 메뉴 추가, admin 전용 + active 하이라이트 분기)
- 수정: `CHANGELOG.md`

### 변경 의도
- DB로 옮긴 leak rule을 운영자가 코드 배포 없이 추가/수정/삭제/우선순위 조정/비활성화할 수 있는 관리 화면 제공. 미리보기 기능으로 템플릿 작성 시 즉시 결과 확인.

### 내역
- **권한**: 페이지를 `/admin/leak-rules`, API를 `/api/admin/leak-rules/**` 경로로 두어 기존 SecurityConfig의 `/admin/**`+`/api/admin/**` ADMIN 강제 규칙을 그대로 활용. CSRF 보호도 유지(`/api/admin/**` 면제 안 됨).
- **컨트롤러 (`LeakRuleAdminController`)**: 7개 엔드포인트.
  - View: `GET /admin/leak-rules` — Thymeleaf 페이지 렌더 (KPI: 룰 수)
  - Library: `GET/POST/PUT/DELETE /api/admin/leak-rules/library[/{id}]`
  - Fallback: `GET/POST/PUT/DELETE /api/admin/leak-rules/fallback[/{id}]`
  - Preview: `POST /api/admin/leak-rules/preview` — 샘플 컨텍스트 + 템플릿 → `LeakRuleTemplate.render()` 결과 + severity 반환
  - 모든 변경 후 `LeakRuleService.invalidate()` 호출로 캐시 즉시 갱신 → 다음 분석부터 반영
  - 검증: 필수 필드 + 정규식 컴파일 검증(`Pattern.compile`), 실패 시 400 + 한글 에러 메시지
- **페이지 (`leak-rules.html`)**:
  - **2탭**: 라이브러리(prefix) / Fallback(정규식). compare.html의 `.cmp-tab-btn` 언더라인 스타일 차용.
  - **테이블**: ID, 우선순위, 식별자(prefix or name), category, severity chip, enabled 토글, [편집][삭제]. 행 hover.
  - **검색**: 탭별 `<input class="search">`로 즉시 필터(toLowerCase 부분 매칭).
  - **페이지네이션**: 20개 단위, ‹Prev / 1 … 현재±2 … 마지막 / Next›.
  - **enabled 토글**: 행 토글 즉시 PUT — 클릭 시 한 줄 반영.
  - **편집/추가 모달**: 같은 폼 재사용(kind flag로 라이브러리/Fallback 입력 영역 토글). 우선순위 / severityHint / explanationTpl / adviceTpl / enabled 입력. 텍스트영역은 monospace 폰트 + 작은 hint로 템플릿 문법 안내(`{var}`, `{var|filter}`, `{#if flag}…{#else}…{/if}` + flag 목록).
  - **미리보기**: 모달 안 `<details>` 펼쳐 sample ctx(simpleClassName/instanceCount/bytes/percentage/accumulatorSimple/referencedFromClass) 입력 → 서버 preview API 호출 → explanation/advice/severity 즉시 렌더.
  - **삭제 확인 모달**: history.html과 동일 패턴(`.modal-ov`). target 표시 + 경고 문구 + 확인 시 DELETE 호출 + 행 즉시 제거.
  - CSRF 메타 헤더 전송, 응답 처리 OK/NOT_FOUND/FORBIDDEN/검증 실패에 따라 한글 알림.
- **Banner 메뉴**: Settings 아코디언에 `<a href="/admin/leak-rules" sec:authorize="hasRole('ADMIN')">Leak Rules</a>` 추가. 활성 경로 자동 펼침/하이라이트 로직(`path.startsWith('/admin/leak-rules')`)도 반영.

### 검증
- 빌드 SUCCESS / 기동 정상 / Library 66 + Fallback 33 데이터 유지.
- 비로그인 호출 시 페이지·API 모두 302 (로그인 리다이렉트) — Spring Security 규칙 정상 동작.
- 패키지된 템플릿에 핵심 마크업 10건 매칭 (`openLibCreate`/`openFbCreate`/`runPreview`/`submitEdit`/`tab-library`/`tab-fallback`).

### 후속 (선택)
- 람다 75개(`LeakSuspectAdvisor.java` 700+라인) 제거 — 사용자 회귀 검증 완료 후 코드 단순화.

## [2026-05-16] Leak Suspect 룰 엔진 DB 마이그레이션 Phase 2+3 (시드 마이그레이션 + 신규 21개 큐레이션)

**변경 파일:**
- 신규: `src/main/resources/leak-rules/library-rules.json` (66개)
- 신규: `src/main/resources/leak-rules/fallback-rules.json` (33개: 기존 9 + 신규 21 + catchall은 우선순위 9999로 재정렬)
- 신규: `src/main/java/com/heapdump/analyzer/config/LeakRuleSeeder.java`
- 수정: `src/main/java/com/heapdump/analyzer/util/LeakRuleTemplate.java` (Double 포맷 보정 — `String.valueOf(double)` 동일 동작)
- 수정: `CHANGELOG.md`
- 비고: `LeakSuspectAdvisor.java`의 람다 75개는 dual-path fallback 용도로 보존(다음 단계에서 제거 예정)

### 변경 의도
- Phase 1에서 깐 dual-path 인프라 위에 실제 DB 룰셋을 채워넣고, 사용자 요구사항인 키워드 fallback 룰 30+개를 큐레이션으로 확장. 시드 완료 후 모든 분석이 DB 경로로 처리되며 람다는 더 이상 호출되지 않음(검증 후 제거 예정).

### 내역
- **자동 변환 (66 KnownLibrary + 9 LeakRule 람다 → JSON 시드)**
  - Python 변환 스크립트(`/tmp/convert_rules.py`)로 원본 LeakSuspectAdvisor.java를 파싱해 람다 본문을 토큰화. `ctx.X` → `{X}`, `formatBytes(ctx.bytes)` → `{bytes|bytes}`, `formatInstanceCount(ctx.instanceCount)` → `{instanceCount|instances}`, ternary `(ctx.X != null ? A : B)` → `{#if hasX}A{#else}B{/if}` 등 1:1 변환. UNCONVERTED 마커 0건 — 모든 람다 정상 변환.
  - `simpleClassName.toLowerCase().contains(…)` 자유 표현식은 `LeakRuleContext`의 derived flag(`streamClass`/`sessionClass`/`threadClass`/`classLoaderClass`/`cacheClass`/`mapClass`)로 매핑.
- **신규 fallback 21개 큐레이션 추가** — 다음 9개 카테고리로 분류:
  - 비동기/리액티브 4종: CompletableFuture·Reactor Flux/Mono·RxJava·Disruptor RingBuffer
  - 스케줄러 2종: Quartz JobDataMap·Timer/TimerTask
  - 메시징 3종: Kafka Consumer·JMS Session·RabbitMQ Channel
  - JDBC/ORM 세분화 3종: Statement/PreparedStatement·MyBatis SqlSession·JPA 영속성 컨텍스트
  - HTTP/Web 3종: HTTP Connection Pool·WebSocket Session·ServletContext attribute
  - 캐시/분산 2종: L2 캐시·분산 캐시 노드(Hazelcast/Ignite/Infinispan)
  - 보안 1종: SSL/TLS 세션
  - 직렬화 1종: ObjectInputStream/Kryo
  - JVM 내부 2종: DirectByteBuffer·MappedByteBuffer/FileChannel
  - 도메인 3종: HTTP Session attribute·Spring Batch ExecutionContext·ETL staging 버퍼
- **우선순위 재정렬**: 기존 specific 8개(5001~5008) → 신규 21개(5009~5029) → catchall ".*" (9999). 더 구체적인 룰이 먼저 매칭되도록 보장.
- **`LeakRuleSeeder`** `ApplicationRunner` — 시작 시 두 테이블이 비어있을 때만 classpath의 JSON에서 INSERT. 이미 데이터가 있으면 skip(운영자가 DB에서 직접 편집 가능). 시드 후 `LeakRuleService.invalidate()` 호출로 캐시 즉시 갱신.
- **회귀 위험**: DB 우선 + 람다 fallback 구조이므로 DB 매칭이 람다와 동일한 출력을 내야 함. 변환 규칙이 1:1 매핑이고 Double 포맷도 `String.valueOf()` 동작으로 보정해 람다와 동일한 텍스트가 나오도록 처리. 미세한 차이가 보이면 람다(dual-path fallback)가 즉시 backup으로 작동하므로 분석 자체가 깨질 위험은 없음. 사용자 검증 후 다음 단계에서 람다 배열 제거.

### 후속 단계
- Phase 4: `/settings/leak-rules` admin CRUD UI (룰 추가·편집·우선순위·enabled·미리보기).
- Phase 4 완료 후 `LeakSuspectAdvisor`의 람다 배열(약 700라인) 제거 — 코드 단순화.

## [2026-05-16] Leak Suspect 룰 엔진 DB 마이그레이션 Phase 1 (인프라 + dual-path)

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/model/entity/LeakLibraryRule.java`
- 신규: `src/main/java/com/heapdump/analyzer/model/entity/LeakFallbackRule.java`
- 신규: `src/main/java/com/heapdump/analyzer/repository/LeakLibraryRuleRepository.java`
- 신규: `src/main/java/com/heapdump/analyzer/repository/LeakFallbackRuleRepository.java`
- 신규: `src/main/java/com/heapdump/analyzer/service/LeakRuleService.java`
- 신규: `src/main/java/com/heapdump/analyzer/util/LeakRuleContext.java`
- 신규: `src/main/java/com/heapdump/analyzer/util/LeakRuleTemplate.java`
- 신규: `src/main/java/com/heapdump/analyzer/config/LeakSuspectAdvisorBootstrap.java`
- 수정: `src/main/java/com/heapdump/analyzer/util/LeakSuspectAdvisor.java` (dual-path 추가, 기존 람다 75개 보존)
- 수정: `CHANGELOG.md`

### 변경 의도
- `/analyze/{file}` Leak Suspects Report 패널의 분석 설명·권장 조치 텍스트가 `LeakSuspectAdvisor.java`에 70+개 ContextFunction 람다(KNOWN_LIBRARIES 66개 + FALLBACK_RULES 9개)로 하드코딩되어 있어 운영 중 문구 수정·룰 추가에 코드 배포가 필요했음. B안(자체 템플릿 엔진 + DB 룰셋)으로 단계적 마이그레이션 시작. 회귀 위험을 줄이기 위해 Phase 1은 인프라 + dual-path만 깔고 기존 람다는 그대로 보존.

### 내역
- **DB 스키마 2종 (JPA ddl-auto=update 흐름 그대로 자동 생성, MariaDB 확인):**
  - `leak_library_rule(id, prefix, library_name, category, severity_hint, explanation_tpl, advice_tpl, enabled, priority, created_at, updated_at)` — `(enabled, priority)` 복합 인덱스 + `prefix` 인덱스
  - `leak_fallback_rule(id, name, category, pattern_regex, explanation_tpl, advice_tpl, severity_hint, enabled, priority, created_at, updated_at)` — `(enabled, priority)` 인덱스
- **미니 템플릿 엔진 `LeakRuleTemplate`** — 약 170줄, 문법: `{var}`, `{var|filter}`(filter: bytes/instances/number/percent), `{#if flag}…{#else}…{/if}`. 중첩 if 미지원(현 람다 분석상 불필요).
- **`LeakRuleContext`** — 기존 LeakSuspectAdvisor 내부 SuspectContext와 동일 필드 + derived boolean flag 8종(`hasAccumulator`, `hasReferencedFrom`, `hasInstanceCount`, `highPercentage`(≥30), `veryHighPercentage`(≥50), `streamClass`, `sessionClass`, `threadClass`, `classLoaderClass`, `cacheClass`, `mapClass`) — 람다의 자유 표현식(`.toLowerCase().contains(…)`, `>= 30`)을 템플릿 `{#if flag}`로 풀기 위한 사전 계산. `resolve(key)` / `truthy(key)` 헬퍼 노출.
- **`LeakRuleService`** — 룰셋 로딩/캐싱(`AtomicReference`) + fallback regex Pattern 사전 컴파일(`Pattern.CASE_INSENSITIVE | DOTALL`). `invalidate()` API로 변경 즉시 반영. invalid regex는 경고 로그 후 skip.
- **`LeakSuspectAdvisor` dual-path** — `analyze()` 진입 직후 `tryDbRules()` 우선 시도. DB 룰셋이 비어있거나 매칭 실패 시 기존 람다 75개 경로로 자동 fallback. `bindRuleService()`로 정적 참조 주입(MatReportParser가 정적 호출하므로 빈 직접 주입 불가).
- **`LeakSuspectAdvisorBootstrap`** `@Configuration` + `@PostConstruct` — 컨텍스트 초기화 후 service를 advisor에 바인딩. 로그: `[LeakRule] LeakSuspectAdvisor wired with DB rule service (dual-path enabled)`.
- **회귀 영향**: 현재 두 테이블이 비어있으므로 `tryDbRules()`가 항상 false 반환 → 기존 람다 경로 그대로. 모든 출력 텍스트 변화 없음.

### 후속 단계
- Phase 2: 기존 람다 75개 → JSON 시드로 1:1 변환 + `LeakRuleSeeder` ApplicationRunner로 첫 부팅 시 INSERT. 시드 완료 후 람다 배열 제거.
- Phase 3: 키워드 fallback 룰 21+개 큐레이션 추가(총 30+개 보장).
- Phase 4: `/settings/leak-rules` admin CRUD UI(룰 추가·편집·우선순위·enabled·미리보기).

## [2026-05-16] 비교 이력 단일 삭제 + 소유권 검증

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/ComparisonHistoryService.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/ComparisonHistoryController.java`
- 수정: `src/main/resources/templates/comparison-history.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- `/comparison-history`의 각 행에 "다시 보기"만 있어 단일 삭제가 불가능했음(일괄 삭제는 있었으나 다중선택 모드를 거쳐야 함). 또한 기존 bulk-delete는 누구든 ID만 알면 타인 이력도 삭제 가능한 권한 공백이 있었음. 사용자 요구: 단일 삭제 버튼 추가 + 본인만 삭제 가능 / 관리자는 모두 삭제.

### 내역
- **Service**: `DeleteResult` enum(`DELETED`/`NOT_FOUND`/`FORBIDDEN`) + `deleteOne(id, username, isAdmin)` 신설. 소유자 검증은 `comparedBy.equals(username)`. `bulkDelete(ids, username, isAdmin)`로 시그니처 변경 — 허용된 ID만 삭제하고 권한 없는 ID는 `skipped`로 카운트(404와 권한 거부를 한 번에 처리해 partial success 가능). `findAllById` → 소유권 필터 → `deleteByIdIn` 흐름.
- **Controller**: `DELETE /api/comparison-history/{id}` 추가 — `Authentication` 주입 + `ResponseEntity`로 200/404/403 분기. `bulk-delete`도 `Authentication` 받아 service에 username·isAdmin 전달, 응답에 `deleted`/`skipped`/`requested` 노출.
- **View**: `comparisonHistoryPage()` 모델에 `currentUsername` 주입. 각 `<tr>`에 `data-id`/`data-can-delete` 추가, 행 단위 `canDelete = isAdmin or comparedBy == currentUsername` 계산. "작업" 셀이 `<div class="row-actions">`로 묶이고 "다시 보기" 옆 빨간 톤 `.del-btn` 추가 — 권한 없는 행은 disabled + tooltip "본인이 실행한 비교 이력만 삭제할 수 있습니다". `#deleteModal`(단일 삭제 확인 모달, base vs target 표시) + JS `openDeleteModal()/closeDeleteModal()/submitDelete()` 추가, CSRF 메타 헤더 전송. 성공 시 행을 DOM에서 즉시 제거 + `initRows()` 재구성으로 페이지네이션·정렬 인덱스 동기화(전체 리로드 회피). 일괄 삭제도 `skipped > 0`이면 안내 alert.

## [2026-05-16] Compare hero 문구 한국어 어절 보존 (글자 단위 줄바꿈 수정)

**변경 파일:** `src/main/resources/templates/compare.html`, `CHANGELOG.md`

### 내역
- Comparison Setup 페이지의 hero 설명 문구가 "정/리합니다"처럼 한 어절 중간에서 줄이 끊기는 현상 수정. 원인은 `.cmp-hero p`에 한국어 줄바꿈 정책이 명시되지 않아 브라우저 기본값(`word-break: normal`)이 적용되었고, CJK 문자는 normal일 때 어절 중간(글자 사이)에서도 줄바꿈이 허용됨. `.cmp-hero p`에 `word-break: keep-all; overflow-wrap: break-word;` 추가 — CJK 어절은 공백 경계에서만 줄바꿈되고, 어절이 컨테이너 폭을 초과할 때만 안전망으로 글자 단위 break. 결과 화면의 hero 문구(`"…단계적으로 확인할 수 있습니다."`)에도 동일하게 적용됨.

## [2026-05-16] Compare AI 인사이트 패널 — 토글 제거 / 재분석 확인 모달 / 저장 칩 타이밍

**변경 파일:** `src/main/resources/templates/compare.html`, `CHANGELOG.md`

### 내역
- **AI 분석 토글 제거**: `panel-title` 우측의 on/off 토글(`#cmpAiToggle`)과 `onCmpAiToggle()` / `cmpAiVisible` localStorage 키, `.ai-toggle`·`.ai-toggle-text`·`#cmpAiPanel { display: none }` CSS, `cmpGoToAiTab()`의 토글 활성화 분기까지 모두 제거. 초기화 IIFE는 `initCmpAi()`로 단순화 — Result Summary AI 요약과 AI 탭 결과를 항상 즉시 로드.
- **재분석 확인 모달**: 결과 영역의 `재분석` 버튼이 native `confirm` 대신 history.html과 동일한 `.modal-ov` 패턴의 커스텀 모달(`#cmpReanalyzeModal`)을 띄움. 메시지: "기존에 저장된 AI 분석 결과가 새 결과로 덮어써집니다. … 복구할 수 없습니다." 확인 클릭 시 `cmpConfirmReanalyze()` → `startCmpAiAnalysis()`. 모달 배경 클릭으로 닫힘. NotAnalyzed 상태의 "AI 비교 분석 시작" 버튼은 첫 분석이므로 모달 없음.
- **"저장됨" 칩 타이밍 수정**: `cmpAiSetState(st)`에서 `st !== 'Result'`이면 무조건 `#cmpAiSavedChip`을 숨기도록 일원화. 분석 중/오류/미분석 상태에서 이전 결과의 칩이 잔존하지 않음. Result 상태에서는 `cmpAiShowResult(result, !!result.saved)`가 백엔드의 `result.saved` 플래그에 따라 표시.

## [2026-05-16] Compare Picker 좌·우 끝선 정렬 후속 수정

**변경 파일:** `src/main/resources/templates/compare.html`, `CHANGELOG.md`

### 내역
- 직전 시도(align-items: stretch + option-card flex:1)에도 좌측 패널 끝선이 우측 옵션 카드보다 16px 위로 떠 있어 사용자 피드백 발생. 원인은 글로벌 `.panel { margin-bottom: 16px }` — grid item의 visual 끝(border)이 row stretch 영역보다 위에 위치하게 되어 우측이 더 길게 보임. picker 컨텍스트 한정으로 `.cmp-picker-layout > .panel { margin-bottom: 0 }` 오버라이드 추가하여 좌측 패널 border 끝이 row 끝까지 도달하게 함. 다른 페이지/섹션의 `.panel` 마진은 영향 없음.

## [2026-05-16] Compare 페이지 미세 개선 — AI step 펄스 + Picker 컬럼 정렬

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 내역
- **AI 분석 로딩 step 펄스**: 4단계 진행 인디케이터의 active 단계 동그라미에 keyframes 기반 동적 효과 추가. ① 본체에 `aiStepCorePulse`(1.6s) box-shadow 펄스로 인디고 빛이 안→밖으로 12px 확산. ② `::before`/`::after` 두 ring이 `aiStepRingPulse`로 .85→1.55 배 확산하며 페이드아웃, `::after`는 0.8s 시차로 시작해 끊김 없이 연속된 파동처럼 보임. active 진입 시 동그라미 본체도 `scale(1.08)` + 색상 트랜지션. done/idle 상태는 영향 없음.
- **Comparison Setup 좌·우 컬럼 하단 정렬**: 기존 `.cmp-picker-layout`의 `align-items: start`로 좌측 "분석 대상 선택" 패널이 우측 "분석 준비 상태 + 적용 분석 옵션" aside보다 길어 하단 라인이 어긋났음. `align-items: stretch`로 변경 + `aside`를 `flex column`으로 만들고 마지막 `.cmp-option-card`에 `flex: 1 1 auto` 부여 → 우측 옵션 카드가 늘어나 좌측 패널 바닥과 정확히 일치. 모바일(`max-width: 720px`) 단일 컬럼 분기는 영향 없음.

## [2026-05-16] Comparison Result Summary에 AI 인사이트 요약 슬롯 신설

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 새로 도입한 4탭 레이아웃에서 사용자가 AI 인사이트 탭으로 들어가야만 분석 결과를 볼 수 있어, 첫 화면(핵심 요약)에서 AI 의견을 미리 인지하기 어려웠음. 또한 인사이트가 아직 수행되지 않은 경우 사용자가 그 사실 자체를 모르고 지나칠 수 있음.

### 내역
- `Result Summary` 카드 안에 `#cmpAiSummaryBox` 슬롯 신설(`data-state` 속성으로 5단계 상태 토글: `loading` / `ready` / `empty` / `disabled` / `error`).
  - **ready**: 저장된 인사이트 요약(`summary` 필드) 텍스트 + 위험도 chip(`CRITICAL/HIGH/MEDIUM/LOW`) + "전체 인사이트 보기 →" 링크.
  - **empty**: "아직 AI 비교 인사이트가 없습니다…" 안내 + `AI 분석 실행` 버튼(클릭 시 AI 탭으로 전환하고 분석 시작 자동 트리거).
  - **disabled**: LLM 비활성 시 빨간 배경 + Settings 링크.
  - **error**: 조회 실패 시 안내.
  - **loading**: 초기 페이지 진입 직후 확인 중 상태.
- 페이지 초기화 시 토글 ON/OFF와 무관하게 항상 `cmpAiCheckLlmStatus() → cmpAiLoadSaved()` 호출하도록 변경(기존엔 토글 ON일 때만 호출). 이로써 토글을 꺼둔 사용자도 Result Summary에서 AI 의견을 인지 가능.
- `cmpAiShowResult()`에 미러링 hook 추가 — 분석 직후 ready로 즉시 갱신. `deleteCmpAiInsight()` 성공 시 empty(또는 LLM 비활성이면 disabled)로 복귀.
- `cmpGoToAiTab(triggerStart)` 헬퍼 추가 — Result Summary 버튼/링크가 AI 탭 버튼을 click()해 hash 동기화 + (옵션) 분석 자동 시작.

## [2026-05-16] Comparison Analysis 페이지 탭 레이아웃 도입

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- `/compare` 결과 화면이 KPI / Before·After / 클래스 diff / 히스토그램 / 누수 의심 / AI 분석을 모두 세로로 한 번에 나열해 스크롤 부담이 컸음. 상단 sticky `.cmp-anchor-nav` 앵커는 시각 정리 효과가 약하고 비교 결과를 한눈에 파악하기 어려운 상태.

### 내역
- 4탭 분리: ① 핵심 요약(Before/After 카드 + Snapshot 그리드 + KPI 6칸) ② 클래스 분석(클래스 diff + 히스토그램 diff) ③ 누수 의심(SuspectDiff 4-카테고리) ④ AI 인사이트(AI 비교 분석 패널). 첫 진입 시 ①번 탭이 활성, 나머지는 `display:none`.
- 영웅 헤더(`.cmp-hero`)와 Result Summary(`.cmp-result-summary`)는 탭 바깥에 유지 — 모든 탭에서 공통 노출.
- 탭 UI: 수평 탭 + 언더라인. 활성 탭에 파란(`#2563EB`) 언더라인. 좁은 화면에서는 `overflow-x:auto`로 가로 스크롤.
- URL hash 동기화: 탭 클릭 시 `#summary` / `#class` / `#suspect` / `#ai`로 `history.replaceState` 갱신. 새 진입 시 hash → 해당 탭 활성, 미지정/잘못된 hash는 `summary`로 폴백. 브라우저 뒤로/앞으로(`hashchange`)도 동기화. 영웅 헤더의 기존 "AI 인사이트 확인" 버튼(`href="#ai"`)도 새 hash 체계와 그대로 호환.
- 기존 `.cmp-tabs-shell` + `.cmp-anchor-nav` DOM/CSS는 제거 — 다른 페이지/템플릿 참조 없음 확인. 패널 내부 id(`#kpi`, `#cls`, `#hist`, `#susp`, `#ai`) 및 JS hook(`classDiffBody`, `histDiffBody`, `cmpAiToggle` 등)은 모두 보존.
- `renderClassPage()` / `renderHistPage()` / AI 분석 함수는 `display:none` 상태의 DOM에서도 정상 작동하므로 탭 전환 시 별도 재호출 불필요.

## [2026-05-16] Analysis Files 페이지 컨테이너 폭 확장

**변경 파일:**
- 수정: `src/main/resources/templates/files.html`
- 수정: `CHANGELOG.md`

### 내역
- `/files` 페이지의 `.container { max-width }`를 1280px → 1600px로 확장. history.html(1800px) 대비 좁아 가로 여백이 과도하다는 사용자 피드백 반영. 모바일 미디어 쿼리(≤900px/≤640px)는 영향 없음.

## [2026-05-14] Comparison 분석 이력 기능 신설

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/model/entity/ComparisonHistoryEntity.java`
- 신규: `src/main/java/com/heapdump/analyzer/repository/ComparisonHistoryRepository.java`
- 신규: `src/main/java/com/heapdump/analyzer/service/ComparisonHistoryService.java`
- 신규: `src/main/java/com/heapdump/analyzer/controller/ComparisonHistoryController.java`
- 신규: `src/main/resources/templates/comparison-history.html`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- 수정: `src/main/resources/templates/fragments/banner.html`
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 기존 `/compare`는 매 요청마다 메모리에서 diff를 재계산할 뿐 비교 행위 자체에 대한 기록이 없어, 어떤 조합을 언제 비교했는지 추적이 불가능했음.
- 단일 dump는 `analysis_history` + `/history`로 관리되는데 비교에는 동일 자산이 없는 비대칭 상태였음.
- 자주 보는 비교 쌍에 1-click으로 다시 진입할 수 있는 경로 필요.

### 내역
- 신규 테이블 `comparison_history` (JPA `ddl-auto=update`로 자동 생성). 메타데이터(KPI delta 6종 + base/target suspectCount + 실행자 + 비교 일시)만 저장하고 ClassDiff/HistogramDiff/SuspectDiff 상세는 재진입 시 재계산.
- 인덱스 2종: `idx_ch_compared_at`, `idx_ch_compared_by`. (utf8mb4×500B×2 columns가 MariaDB 단일 인덱스 키 길이 한도 3072B 초과로 base+target 복합 인덱스는 채택 미보류 — 현재 dedupe 쿼리는 `compared_by` 인덱스로 충분.)
- `ComparisonHistoryService.recordComparison()` — 동일 user+base+target 60초 이내 중복 진입 시 dedupe (시계열 의미는 유지). 저장 실패해도 `/compare` 렌더링은 정상 진행 (try/catch 처리).
- `HeapDumpViewController.compareDumps()`에 `Authentication` 파라미터 추가 + buildKpiDiff 결과를 service로 자동 저장.
- 신규 페이지 `/comparison-history` — `history.html` 패턴 재사용 (검색·정렬·페이지네이션·일괄선택 + admin "deleted 표시" 토글). 컬럼: #, Base, Target, Heap Δ, Suspects Δ, Classes Δ, Objects Δ, 실행자, 비교 일시, "다시 보기".
- 가시성: 모두 공유 + ADMIN이 "deleted 표시" 토글로 양쪽 원본 파일이 삭제된 ghost 이력까지 조회 가능. `AnalysisHistoryRepository.findByFilename()` 조인으로 baseDeleted/targetDeleted 실시간 계산.
- "다시 보기" 액션 — base/target 원본 파일이 모두 살아있을 때만 활성, 한쪽이라도 삭제이면 disabled + 한글 tooltip.
- API: `GET /api/comparison-history` (JSON), `POST /api/comparison-history/bulk-delete` (`{ids: number[]}`). `/api/admin/**` 외이므로 CSRF 면제.
- 배너(`fragments/banner.html`): Comparison 메뉴를 Servers/Settings와 동일한 아코디언으로 전환 → 서브 메뉴 "새 비교" / "비교 이력". 활성 경로 자동 펼침/하이라이트 로직에 `/compare`, `/comparison-history` 추가. `ACCORDION_PARENTS` 배열로 일반 메뉴 하이라이트 예외 처리 일원화.
- compare.html hero: picker 화면과 결과 화면 모두 "비교 이력" CTA 추가.

## [2026-05-14] Comparison 분석 전 페이지 배경 그라데이션 끊김 수정

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 분석 전 페이지(파일 선택 화면)는 본문이 짧아 body 높이가 viewport보다 작은 상황에서, 기존 radial-gradient(상단 28%/30%에서 transparent로 페이드)가 페이지 중간 어디쯤에서 끊겨 보였음.
- 상단부에만 색이 깔리고 하단은 평면 `#F8FAFC`로 떨어지면서 시각적 단절이 발생.

### 내역
- `body` 배경을 다층 ellipse 그라데이션으로 재구성하고 페이드를 60~75%로 확장.
- 좌상/우상 블롭에 더해 하단 중앙에 옅은 indigo 블롭을 추가하고, 베이스에 `#F8FAFC → #EEF2FF` 수직 linear-gradient를 깔아 위·아래가 자연스럽게 이어지게 함.
- `background-attachment: fixed` 적용으로 그라데이션이 viewport 기준으로 고정되어 본문 길이와 무관하게 일관된 톤 유지.
- `min-height: 100vh`로 본문이 짧을 때도 canvas가 항상 viewport를 채우도록 보강.

## [2026-05-14] Comparison 페이지 분석 전/후 레이아웃 재설계

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 분석 전 화면은 파일 선택만 제공해 비교 흐름과 실행 조건이 한눈에 드러나지 않음.
- 분석 후 화면은 상세 표가 빠르게 노출되어 핵심 변화 요약과 Before/After 스냅샷의 시각적 우선순위가 약함.
- 기존 계획에 따라 준비 화면은 입력 유도 중심, 결과 화면은 요약 → KPI → 전후 비교 → 상세 분석 → AI 인사이트 흐름으로 재배치.

### 내역

**분석 전 화면**
- 상단 Hero 영역 추가: Comparison Setup, 분석 전 상태 chip, 분석 이력 이동 CTA 배치.
- Before / After 선택 카드를 세련된 카드형 레이아웃으로 확대하고 선택 완료 badge, 메타 정보(HEAP/DATE/SERVER)를 정리.
- 우측 준비 상태 Stepper 추가: Before 선택 → After 선택 → 비교 실행 준비 완료.
- 기본/상세/AI 분석 옵션 안내 카드를 추가해 실행 전 제공되는 비교 범위를 명확화.
- 비교 실행 버튼은 두 파일이 모두 선택되고 서로 다를 때만 활성화되도록 UX 개선.

**분석 후 화면**
- 결과 상단 Hero 영역 추가: 분석 완료 상태, 다른 파일 비교, AI 인사이트 CTA 배치.
- Result Summary 카드 추가: 힙 사용량 변화와 누수 의심 항목 변화를 최상단에서 요약.
- Quick Reading 카드 추가: Leak Suspects / Threads 전후 변화 수치를 별도 강조.
- 섹션 이동 nav를 탭형 shell로 재정리하고 `요약 KPI / Before After / 클래스 변화 / 히스토그램 / 누수 의심 / AI 분석` 흐름으로 변경.
- Before / After 스냅샷 카드를 추가해 Used Heap, Usage, Objects, Classes를 좌우 비교로 빠르게 확인 가능하게 구성.
- 카드 radius, shadow, background gradient, responsive breakpoint를 보강해 desktop/tablet/mobile 모두 1~3컬럼으로 자연스럽게 전환.

### 검증
- `mvn clean package -DskipTests` 실행 시도 → 현재 sandbox에 `mvn` 명령이 없어 수행 불가 (`/bin/bash: mvn: command not found`).
- 템플릿 정합성 기본 점검: `compare.html`의 script/style 태그 개수, 신규 레이아웃 class 존재 여부 확인.

---

## [2026-05-14] Compare AI 비교 분석 — 시작 버튼 노출 + 요청/응답 로직 마감

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- 수정: `CHANGELOG.md`

### 변경 의도
사용자 보고: "AI 비교 분석의 분석 시작 버튼이 보이지 않는다." 추적 결과 결정적 원인 1건 + 잠재 결함 4건 + UX 결정 1건 확인.

### 내역

**P-1 (결정적) — 시작 버튼 영구 숨김 해소**
- CSS `#cmpAiPanel { display: none; }`(compare.html:157) 가 JS 의 `panel.style.display = ''` 토글과 충돌해 inline 값 비움 → CSS rule 이 재적용 → 패널 영구 숨김 → 시작 버튼 노출 불가.
- 수정: `onCmpAiToggle()` / `initCmpAiToggle()` 두 곳을 `style.display = visible ? 'block' : 'none'` 로 명시적 block 지정. CSS rule 은 FOUC 방지 위해 유지.

**P-2 — 중복 클릭 가드 (`_cmpAiInFlight`)**
- 시작 버튼 빠른 연클릭 시 동일 base/target 으로 fetch 중복 발사 → 응답 순서 뒤바뀌어 결과 덮어쓰기 가능.
- 모듈 변수 `_cmpAiInFlight` 추가, `startCmpAiAnalysis()` 진입 시 가드 + then/catch 양쪽에서 복구.

**P-3 — `recommendations` 타입 검증**
- LlmConfigService 의 JSON 파싱 실패 fallback 이 `"recommendations":"-"` (String) 반환 시 `cmpAiShowResult()` 의 `.forEach` 가 폭발.
- `Array.isArray(d.recommendations)` 분기 + 문자열 fallback (`"-"` 제외) 표시 추가.

**P-4 — LLM 상태 체크 실패 시 NotAnalyzed 명시**
- `cmpAiCheckLlmStatus()` catch 가 disabled 안내만 표시하고 상태 머신 진입 누락 → 빈 패널 노출 가능.
- catch 블록에 `cmpAiSetState('NotAnalyzed')` + `cmpAiSetBadge('미분석', ...)` 추가.

**P-5 — `analysedAt` 응답 보강**
- 신규 분석 POST 응답에 시각이 없어 결과 카드 우상단 분석시각 빈칸.
- `HeapDumpController.analyzeCompareLlm()` 저장 성공 분기에 `result.put("analysedAt", System.currentTimeMillis())` 추가.
- `cmpAiShowResult()` 가 `d.analysedAt || result.analysedAt` 으로 두 응답 모양 모두 처리, epoch ms 면 `yyyy-MM-dd HH:mm` 포맷.

**P-6 — 토글 우측 라벨 제거 (UX 결정)**
- "켜기/끄기" 라벨이 현재 상태인지 다음 동작인지 혼동 → 좌측 `AI 분석` 만 유지, 우측 `#cmpAiToggleLabel` span 제거 + `onCmpAiToggle`/`initCmpAiToggle` 의 라벨 갱신 라인 제거.

### 검증
- `mvn clean package -DskipTests` 성공 / `bash restart.sh` 9.8초 기동 / 에러 로그 없음.
- 사용자 시나리오 (브라우저):
  - `/compare?base=...&target=...` 진입 시 AI 패널 보이고 **"AI 비교 분석 시작" 버튼 명확히 노출** (P-1).
  - 토글 우측 라벨 없음, 좌측 `AI 분석` 만 표시 (P-6).
  - 시작 클릭 — 4 step 진행 → 결과 카드 우상단에 분석 시각 채워짐 (P-5).
  - 시작 버튼 연클릭 시 단일 POST (P-2).
  - `/api/settings` 차단 후 토글 ON — 미분석 배지 + LLM 비활성 안내 + 패널 본문 NotAnalyzed (P-4).
  - 권장 조치 섹션: 배열/문자열 fallback 양쪽 모두 깨지지 않음 (P-3).
  - 새로고침 → 저장본 자동 로드, 삭제 → NotAnalyzed 복귀.

---

## [2026-05-14] Compare 페이지 레이아웃 개선 + 한국어 번역

**변경 파일:**
- 수정: `src/main/resources/templates/compare.html`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- 분석 전 picker 가 단순 select 2개로 파일명만 노출 → 어떤 덤프인지 분간 어려움.
- 분석 후 결과 헤더 카드의 used heap / objects / classes / suspects / threads 8라인이 바로 아래 KPI 6칸과 메타 중복.
- 전반 영어 라벨(Topbar, picker, Baseline/Target, KPI 라벨, 표 컬럼, Suspect 라벨, Pager Prev/Next)이 운영 한국어 톤과 불일치.

### 내역

**Picker (카드형 + 메타)**
- `select 2개 평행 배치` → 흰 카드 2개(`기준 (Before)` / `비교 (After)`) 좌우 grid. 모바일 ≤600px 1단 stack.
- 카드 내 메타 3줄: `📊` 힙 사용량 · `📅` 덤프 일시 · `🖥` 서버명. `/api/history` 응답의 `heapUsed / lastModified / serverName` 을 option dataset 으로 저장 후 select onchange 시 `updatePickerMeta()` 가 채움.
- 동일 파일 비교 금지 인라인 hint (`⚠ 동일한 파일은 비교할 수 없습니다.`), 오류 시 빨강(`⚠ 두 파일을 모두 선택해주세요.` / `⚠ 서로 다른 파일 두 개를 선택해주세요.`)으로 전환.
- 안내·라벨·placeholder 전면 한글화.

**결과 헤더 카드 (컴팩트화)**
- 8라인 메타 제거 → `📅 yyyy-MM-dd HH:mm · 파일크기` 1라인.
- `HeapDumpViewController.compareDumps()` 에 `baseModifiedFmt / targetModifiedFmt` 모델 attribute 추가 (Thymeleaf SpEL 에서 `new java.util.Date(long)` 회피용, `SimpleDateFormat` 으로 서버 측 포맷).

**섹션 앵커 네비게이션 (신규)**
- 결과 페이지 상단 sticky nav: `KPI / 클래스 / 히스토그램 / 의심 / AI`. 각 패널에 `id="kpi|cls|hist|susp|ai"` 부여. `html { scroll-behavior:smooth }` 적용. 모바일에서 가로 스크롤.

**Suspect 4 카드**
- 각 카테고리 헤드 우측에 건수 chip 추가, IIFE 로 `.suspect-item` 수를 카운팅. 빈 카테고리에는 "해당 없음" 자동 삽입.
- 카테고리 라벨 한글화: `▲ NEW · 비교에만 존재 / ▼ GONE · 기준에만 존재 / ⇄ 심각도 변경 / = PERSIST · 양쪽 모두 존재`.
- severity 칩 `unknown` → `미정`.

**AI 비교 분석 패널**
- 토글에 좌측 `AI 분석` 라벨 추가, ON/OFF → `켜기 / 끄기`.
- 패널에 `id="ai"` 부여 (앵커 nav 연동).

**한글화 전수**
- Topbar `Compare` → `비교`, `← Back` → `← 돌아가기`.
- KPI 6칸: `Used heap / Objects / Classes / Suspects / Threads / Top consumers` → `힙 사용량 / 객체 수 / 클래스 수 / 의심 항목 / 스레드 / 상위 소비자`. 보조 라인도 한글.
- Class diff 패널: 제목 `클래스별 메모리 변화 상위 (Retained heap)`, 도구 `필터 / 표시`, 옵션 `전체 / 증가만 / 감소만`, 컬럼 `클래스명 / 기준 / 비교 / 변화 / 추이`, 빈 상태 `두 덤프 간 의미 있는 차이가 없습니다.`.
- Histogram diff 패널: 제목 `히스토그램 클래스 비교`, 컬럼 `기준 객체 / 비교 객체 / Δ 객체 / 기준 Retained / 비교 Retained / Δ Retained`.
- Suspect 패널: 제목 `누수 의심 항목 비교 (Leak Suspects)`, 빈 상태 `두 덤프 모두 누수 의심 항목이 없습니다.`.
- Pager: `‹ Prev / Next ›` → `‹ 이전 / 다음 ›`.
- MAT 영문 용어(Retained heap)는 운영자 친숙도를 위해 그대로 유지 (한국어 + 영문 보조 톤).

### 검증
- `mvn clean package -DskipTests` 성공 / `bash restart.sh` 9.7초 기동 / 신규 에러 로그 없음.
- `GET /compare` 200 OK.
- SpEL `Math.min(80L, ...)` Trend 바 식은 손대지 않음 (CLAUDE.md 함정 #11).
- 분석 완료된 두 덤프로 결과 페이지 직접 검증 필요 항목 (브라우저):
  - 헤더 카드 컴팩트(파일명 + `📅 yyyy-MM-dd HH:mm · 크기`).
  - 앵커 nav sticky + 부드러운 스크롤로 5개 섹션 이동.
  - KPI 6칸 한글 라벨 + 색상 정상.
  - Class/Histogram diff 한글 컬럼 + 페이지네이션 `‹ 이전 / 다음 ›`.
  - Suspect 4 카드 우측 건수 chip + 빈 카테고리 "해당 없음".
  - AI 토글 좌측 `AI 분석` + 우측 `켜기/끄기` 토글 시 패널 노출/숨김.
- Picker 브라우저 검증:
  - 두 카드(기준/비교) 좌우 배치, 모바일 ≤600px 1단.
  - 파일 선택 시 `📊 / 📅 / 🖥` 메타 채워짐(서버명 null 인 로컬 업로드는 `—`).
  - 동일 파일 선택 후 "비교 →" → hint 빨강 전환.

---

## [2026-05-14] Compare 고도화 — KPI/히스토그램/누수의심 diff + AI 비교 분석(on/off)

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- 수정: `src/main/resources/templates/compare.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 기존 `/compare` 는 Top 20 클래스 메모리 diff + heap delta 한 줄만 노출 → 두 덤프 사이의 누수 진행/회귀 판단에 부족.
- MAT 가 이미 산출한 KPI / Histogram / Leak Suspects / Threads 가 비교 화면에는 거의 노출되지 않음.
- AI 인사이트도 단일 덤프(`analyze.html`)에만 존재. 두 덤프의 변화 해석은 사용자가 직접 추론해야 했음.
- LLM 비용/조용한 화면을 원하는 사용자를 위해 AI 패널은 토글로 on/off 가능해야 함.

### 내역

**Backend (HeapDumpController)**
- 신규 inner static class 3종: `HistogramDiff` / `SuspectDiff` (state=NEW/GONE/PERSIST/SEVERITY_CHANGED) / `KpiDiff` (heap/objects/classes/suspects/threads/topConsumer delta + formatted/up 헬퍼).
- 신규 public 빌더 4종 (`buildClassSizeMap` 옆):
  - `buildClassDiffs(base, target, limit)` — 기존 인라인 로직 추출.
  - `buildHistogramDiffs(base, target, limit)` — className 합집합, |retainedDelta| desc 정렬, top N.
  - `buildSuspectDiffs(base, target)` — title 정규화 키로 매칭, severity 변경 감지, 우선순위 정렬(SEVERITY > NEW > GONE > PERSIST).
  - `buildKpiDiff(base, target)`.
- 신규 endpoint:
  - `GET /api/compare/data?base=&target=` — 4종 diff + base/target 메타 JSON.
  - `POST /api/llm/compare/analyze` — body `{base, target, prompt, save}`. 합성 키 `__compare__:` + sha256(base+"|"+target)[0..40]) 로 `AiInsightManager.saveAiInsight()` 재사용.
  - `GET /api/llm/compare/insight?base=&target=` / `DELETE` 동형 — 동일 합성 키로 load/delete.
- `FilenameValidator.validate()` 모든 신규 엔드포인트에서 base/target 검증.

**HeapDumpViewController.compareDumps()**
- 신규 빌더 4종 호출, classDiffs limit 20 → 50, model attr 8개 추가 (`kpi`, `classDiffs`, `histogramDiffs`, `suspectDiffs`, `baseSuspectCount`, `targetSuspectCount`, `baseThreadCount`, `targetThreadCount`).
- 기존 `heapDelta`/`heapDeltaSign`/`heapDeltaUp` 호환 유지 (kpi 에서 파생).

**compare.html (297 → ~800 라인)**
- KPI 6칸 delta 배너 (Heap / Objects / Classes / Suspects / Threads / Top consumers) — sign + 색상 + base→target 보조 라인.
- Class diff 패널: 검색 + Increased/Decreased/All 필터 + Top 20/50/100 셀렉트(localStorage `cmpClassRows`) + ‹Prev/1…N/Next› 페이지네이션.
- Histogram diff 패널 (신규): Class / Base obj / Target obj / Δ obj / Base retained / Target retained / Δ retained. 검색 + 페이지네이션.
- Leak Suspects 4 카테고리 카드 (신규): NEW(빨강) / GONE(녹색) / SEVERITY_CHANGED(황색, base→target severity 칩) / PERSIST(회색).
- **AI 비교 분석 패널 (신규, 토글 on/off)** — analyze.html 의 panel-ai-insight 디자인을 compare 전용(`cmpAi*`) 으로 축소 이식.
  - 토글 OFF (기본은 ON, `localStorage('cmpAiVisible')`) 시 LLM 호출/패널 모두 없음.
  - 4 step 진행 (데이터 수집 / 프롬프트 구성 / LLM 분석 / 결과 저장) + 경과 시간 + 진행률 바.
  - 프롬프트 빌더 `buildComparePrompt(data)` — `/api/compare/data` 응답으로부터 KPI 변화 / Top10 클래스 / Top10 히스토그램 / suspect 4 카테고리를 채우고 JSON 응답 스키마(`summary / deltaInterpretation / regressionRisk / rootCause / recommendations / severity / severityDesc`) 명시.
  - 결과 카드: severity banner + summary / deltaInterpretation / regressionRisk / rootCause / recommendations.
  - 헤더 액션: 재분석 / 삭제. 페이지 로드 시 저장본 자동 로드(토글 ON 시).
  - LLM 비활성 시 안내 + 시작 버튼 비활성화(`/api/settings.llm.enabled` 확인).
- Topbar 줄바꿈 규약(`.topbar-brand` flex/min-width:0, `.topbar-right` flex-shrink:0) 적용.
- `<meta name="_csrf">` head 에 추가 (일관성).

### 검증
- `mvn clean package -DskipTests` 성공.
- `bash restart.sh` 정상 기동 (9.7초, "Started HeapAnalyzerApplication" 확인, 에러 로그 없음).
- `GET /compare` 200 OK (picker), 분석 완료된 두 덤프 선택 시 KPI 6칸 + 3 신규 diff 패널 + AI 패널 렌더링.
- 사용자 시나리오 검증 필요 항목:
  - AI 토글 OFF → 패널 숨김, `/api/llm/compare/insight` 호출 없음.
  - AI 토글 ON → 시작 → 4 step 진행 → severity/summary/deltaInterpretation/regressionRisk/rootCause/recommendations 렌더.
  - 새로고침 → 저장본 자동 로드. 재분석 / 삭제 동작.
  - `SELECT filename FROM ai_insights WHERE filename LIKE '__compare__:%'` 로 합성 키 행 1건/쌍 존재 확인.
  - 한 파일만 분석된 상태 `/compare?base=...&target=미분석` → 누락 파일 명시 에러.

---

## [2026-05-14] AI Chat — 좌측 세션 목록 카드형 UI + inline rename

**변경 파일:**
- 수정: `src/main/resources/templates/ai-chat.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 좌측 사이드바 세션 목록이 `border-bottom` 로 구분된 평면 리스트라 시각적 위계가 약하고 항목 식별이 어렵다는 피드백.
- 이미 존재하는 PUT `/api/ai-chat/sessions/{id}` 의 제목 변경 API 가 UI 미노출 상태라 함께 도입.

### 내역
- **카드형 디자인 (`.session-item`)**: 사이드바 배경 `#F9FAFB`, 카드 본체 흰색 + `border-radius:10px` + `box-shadow:0 1px 2px rgba(17,24,39,.04)`, 카드 간 8px gap. hover 시 `translateY(-1px)` + 그림자 강조. active 시 좌측 `border-left` 3px 막대 제거하고 파란 배경 + 보더 + 그림자 + `translateX(2px)` 로 강조.
- **헤드 라인 신설 (`.session-item-head`)**: 제목 + 사용자 칩 + 액션을 flex 한 줄로 배치. 사용자 태그가 카드 하단이 아닌 제목 옆에 위치.
- **메타 라인 순서 변경**: `날짜 · 메시지수` → `메시지수 · 날짜`.
- **inline rename**: 카드 hover 시 ✎(연필) 버튼 노출 → 클릭 시 제목이 input 으로 교체 → Enter/blur 저장, Esc 취소. 클릭된 카드만 `is-renaming` 토글하여 모바일 배너 클론 영향 방지.
- **신규 JS 함수**: `startRenameSession` / `commitRenameSession` / `cancelRenameSession` / `onRenameKeyDown` + 보조 `escAttr`. Esc↔blur 경합은 `_renamePending = 'cancelled'` 플래그로 차단, 중복 PUT 은 `_renamePending = id` 가드.
- **클론 호환성 (CLAUDE.md 함정 #8)**: 신규 input/button 모두 `id` 속성 미사용. `data-session-id` + 클래스 셀렉터로만 식별. `cloneNode(true)` 후 ID 제거 로직과 충돌 없음.
- **사이드바 폭 확대**: 데스크톱 `.session-sidebar` width `280px → 320px` (모바일 미디어쿼리 220px 는 유지).
- **버그 수정**: `createNewSession()` 이 미정의 함수 `getCurrentFilter()` 호출로 ReferenceError 발생 → `+ 새 채팅` 버튼 무반응. `getCurrentFileFilter()` 로 교정.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공 (9.4초 기동).
- 데스크톱: 카드 hover/active/✎/✕/Esc/blur/빈 문자열 정상 동작, 분석 삭제됨/이전 분석 배지 유지.
- 모바일 배너 클론: 풀블리드 카드 동일 스타일, rename 동작 정상.

---

## [2026-05-12] AI Chat — ADMIN 전체 조회 + 작성자 태그 + 사용자별 필터

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/repository/AiChatSessionRepository.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/AiChatController.java`
- 수정: `src/main/resources/templates/ai-chat.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- AI Chat 사이드바가 본인 세션만 보여줘 운영/감사 목적의 채팅 이력 검토 불가.
- ADMIN 은 전체 채팅 목록을 보고, 각 세션이 어느 계정의 질문인지 식별할 수 있어야 하며, 사용자별 필터링도 가능해야 함.

### 내역
- **AiChatSessionRepository** — admin 전용 메서드 추가:
  - `findAllByOrderByUpdatedAtDesc()` — 전체 세션 (최신순)
  - `findByFilenameOrderByUpdatedAtDesc(filename)` — 모든 사용자의 특정 파일 세션
  - `@Query select distinct s.username ... order by s.username` → `findDistinctUsernames()` (사용자 셀렉트 옵션용)
- **AiChatController.listSessions**:
  - `Principal` → `Authentication` 으로 교체. `isAdmin(authentication)` 분기.
  - ADMIN: `user` + `filename` 파라미터 조합으로 필터 (둘 다 / user / filename / 무필터 4 분기). 무필터 시 `findAllByOrderByUpdatedAtDesc()`.
  - USER: 기존 동작(본인 세션만, filename 옵션) 유지 — `user` 파라미터 무시.
  - 응답 Map 에 `username` 필드 추가 (프론트가 작성자 태그 표시).
- **AiChatController 신규 엔드포인트** `GET /api/ai-chat/users`:
  - ADMIN 전용 (그 외 403).
  - `findDistinctUsernames()` 반환 → 프론트의 사용자 셀렉트 채움.
- **AiChatController — 5 개 ownership 체크 분기**:
  - `deleteSession` / `updateSession` / `getMessages` / `saveMessage` / `streamChat` 모두 `Principal` → `Authentication`, `getUsername().equals(username)` → `canAccess(session, authentication)` 헬퍼로 교체.
  - `canAccess()` = ADMIN 이거나 세션 소유자(username 일치) 일 때 true. ADMIN 은 모든 세션 읽기/삭제/제목수정/메시지 송수신 가능.
  - 삭제 로그에 `by={admin}, owner={original}` 함께 출력.
- **AiChatController.aiChatPage** view 핸들러 — 모델에 `isAdmin`, `currentUser` attribute 노출.
- **ai-chat.html**:
  - `<div class="chat-layout" data-is-admin="${isAdmin}" data-current-user="${currentUser}">` — JS 가 권한/현재 사용자 즉시 캡처.
  - 사이드바 필터를 단일 셀렉트 → `.session-filter-row` 컨테이너 + `.session-filter-file` (전체 파일) + `.session-filter-user` (전체 사용자, ADMIN 일 때만 `th:if`) 2 단 구성.
  - CSS `.session-item-user` — 보라색 pill (작성자 username), `.is-self` 변형(녹색)으로 본인 표시. `::before` 점 마커.
  - JS:
    - `IS_ADMIN`/`CURRENT_USER` 상수를 layout 의 data-* 에서 읽음.
    - `loadUserFilter()` (admin 만 호출) → `/api/ai-chat/users` → 셀렉트 옵션 채움.
    - `getCurrentFileFilter()` / `getCurrentUserFilter()` 분리, `loadSessions()` 가 두 필터 결합해 `?filename=...&user=...` 쿼리 조립.
    - `renderSessionList()` — `s.username` 이 있고 (ADMIN 이거나 본인이 아닌 경우) 작성자 태그 렌더. 본인이면 `is-self` 클래스 + `(본인)` 툴팁.
  - 배너 클론 사이드바도 `querySelectorAll('.session-filter-file' / '.session-filter-user')` 패턴으로 양쪽 동시 갱신 (CLAUDE.md 함정 #8 준수).

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상 (`Started in 9.12s`).
- admin 로그인 후 `/api/ai-chat/sessions` → 모든 사용자 세션 10건, 각 항목에 `username` 필드 포함 (admin/sscuser 모두 노출).
- `?user=sscuser` 쿼리 → sscuser 세션 2건만 반환.
- `/api/ai-chat/users` → `["admin","sscuser"]` distinct 리스트 반환.
- 코드 상 비-ADMIN 분기는 항상 `findByUsername(currentUser, ...)` 로 본인 것만 조회 + canAccess 가 owner 일치 검사 → 권한 격리 유지.
- 기능 검증(요청): ADMIN 로그인해서 `/ai-chat` 진입 → 좌측 사이드바에 "전체 사용자" 셀렉트 노출 + 모든 사용자 세션의 우상단에 보라색 username 태그(본인은 녹색) 노출 확인.

## [2026-05-12] 종료 추적 — 신호별 로깅 + 비정상 종료 감지 (shutdown marker)

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/HeapAnalyzerApplication.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- `kill -15` / Ctrl+C 시 통합 메시지 `[Shutdown] Application is shutting down (signal received)` 한 줄만 남아 신호 종류 식별 불가.
- `kill -9` (SIGKILL) / Linux OOM Killer / JVM crash 등은 OS 가 즉시 죽여 JVM 어떤 코드도 실행 못 하므로 직접 로깅 불가능 → **간접 추적** 메커니즘 필요.

### 내역
- **신호별 핸들러 등록** (`installSignalHandlers()`):
  - SIGTERM/SIGINT/SIGHUP 각각 `sun.misc.Signal.handle()` 로 등록 (Java 11/17/21 의 `jdk.unsupported` 모듈 통해 사용 가능).
  - 핸들러 동작: `[Signal] Received SIG<NAME> (<num>) — initiating graceful shutdown` 로깅 후 `System.exit(0)` → 모든 shutdown hook 정상 트리거.
  - 등록 실패(SecurityManager 거부 등) 는 warn 로 남기고 기본 JVM 동작 유지.
  - 등록 직후 `[Signal] 신호 핸들러 등록 완료 — ... (SIGKILL 은 OS 가 직접 죽이므로 catch 불가)` 명시.
- **비정상 종료 감지** (`checkPreviousShutdown()` + `writeShutdownMarker()`):
  - 마커 파일: `${app.home:user.dir}/logs/.shutdown-marker` (기본 `/opt/genspark/webapp_dump/logs/.shutdown-marker`).
  - 기동 직후(SpringApplication.run 이전): 마커 파일이 남아있으면 직전 셧다운이 비정상이었음을 의미 → WARN 로그 4줄 (원인 후보 + 직전 마커 정보 + OS 확인 명령어 3종).
  - 새 마커 작성: `pidName=<pid>@<host>, startedAt=<ISO>, javaVersion=<v>`.
  - shutdown hook (정상 종료 경로): 마커 삭제 후 `[Shutdown] Shutdown marker cleared — next start will see this as a clean exit` 로깅 → 다음 기동 시 부재 = 정상 종료였음을 추정.
- **기존 shutdown hook 메시지 정리**: `[Shutdown] Application is shutting down (signal received)` → `[Shutdown] Application is shutting down` ((signal received) 는 이제 `[Signal] Received SIG... ` 가 별도 출력하므로 중복 제거).

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 정상.
- 시나리오 1 — `kill -15 <pid>`:
  - `[Signal] Received SIGTERM (15) — initiating graceful shutdown`
  - `[Shutdown] Application is shutting down`
  - `[Shutdown] Shutdown marker cleared — next start will see this as a clean exit`
  - 마커 파일 삭제 확인.
- 시나리오 2 — `kill -9 <pid>` 후 재기동:
  - 마커 파일 잔존 확인 (직전 PID/시각/JDK 버전 보존).
  - 다음 기동 시 `nohup.out` 에 `[Startup] ⚠ 직전 종료가 정상적으로 완료되지 않았습니다 ...` + 직전 마커 정보 + OS 확인 명령어 3종 출력.

### 운영 메모
- `[Startup]` WARN 은 logback 초기화(SpringApplication.run) 이전에 발생 → `heapdump-analyzer.log` 가 아니라 **`nohup.out`** 에 기록됨. `restart.sh` 가 nohup.out 을 자동 stream 하므로 운영자에게 자연스럽게 노출. 별도 추적이 필요하면 `tail -f /opt/genspark/webapp_dump/logs/nohup.out` 사용.
- SIGKILL 은 POSIX 명세상 catch 불가 — 본 메커니즘은 "직전 셧다운이 정상이었는지" 추정만 제공. 정확한 원인 분리는 `dmesg | grep -i 'killed process'` (OOM), `journalctl -k --since '1 hour ago'`, `ls -lt logs/hs_err_pid*.log` (JVM crash dump) 등 OS 도구 병행 필요.

## [2026-05-12] LLM 설정 UX 보강 + 경로 평문 노출 + 비-ADMIN 읽기 전용 + 버전 2.0.3

**변경 파일:**
- 수정: `pom.xml`, `run.sh`, `stop.sh`, `restart.sh`, `CLAUDE.md`
- 수정: `src/main/resources/templates/llm-settings.html`
- 수정: `src/main/resources/templates/settings.html`
- 수정: `src/main/resources/templates/rag-settings.html`
- 수정: `src/main/resources/templates/progress.html`
- 수정: `src/main/java/com/heapdump/analyzer/config/SecurityConfig.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- LLM custom hint 의 경로가 절대 경로 하드코딩 → 환경 토큰(`${APP_HOME}`)으로 일반화.
- API URL/Key 입력칸이 좁아 사내 게이트웨이 URL/긴 키가 화면 밖으로 잘려 가독성 저하.
- MAT CLI Path / Heap Dump Directory 가 `***/파일명` 으로 마스킹돼 운영자가 실제 경로를 바로 확인 못함 (서버 로컬 경로라 마스킹 효용 낮음).
- USER 계정도 Settings 페이지에서 토글/Save 버튼이 노출돼 잘못 누르면 권한 없음 에러 후 상태 꼬임. 조회는 가능하되 변경은 ADMIN 전용으로 명시 분리.
- 변경 사항 누적 → 마이너 버전 2.0.1 → 2.0.3 으로 올림.

### 내역
- **[1] llm-settings.html customHint** — `/opt/genspark/webapp_dump/certs/heap-truststore.jks` → `${APP_HOME}/certs/heap-truststore.jks`. 다른 환경에 배포될 때 경로 안내 일반화.
- **[2,3] llm-settings.html 입력 폭**:
  - API URL 컨테이너 `max-width: 420px → 620px`, `min-width: 320px` 추가. 입력 패딩 `5px → 6px`.
  - API Key 입력 `width: 200px → 380px` (`max-width: 100%` 안전망).
- **[4] HeapDumpController.maskPath() 제거**:
  - `getSettings()` 의 `heapDumpDirectory`, `getMatStatus()` 의 `mat.path` 가 `maskPath()` 를 거쳐 `***/이름` 으로 잘려서 응답 → 직접 `getHeapDumpDirectory()` / `getMatCliPath()` 를 그대로 반환.
  - 다른 호출 지점이 없어 `private maskPath()` 메서드 자체 삭제.
- **[5] 비-ADMIN 읽기 전용**:
  - `SecurityConfig` — `import HttpMethod` 추가 후 다음 패턴 ADMIN 전용 지정 (조회 GET 은 인증 사용자 모두 허용 유지):
    - `POST /api/settings/**` (general/database/RAG settings 모두 포함)
    - `POST /api/llm/{enabled,config,apikey,test-connection,chat-prompt,chat-restore-mode}`
    - `POST /api/servers/{scan-interval,ssh-local-user}`
    - `POST /api/llm/{analyze,insight/save,chat,chat/stream}` 및 `DELETE /api/llm/insight/{filename}` 은 사용자 액션이라 그대로 인증 사용자 허용.
  - `HeapDumpViewController` — `/settings`, `/settings/llm`, `/settings/rag` 3 핸들러에 `Authentication` 파라미터 + `model.addAttribute("isAdmin", ...)` 추가. `private boolean isAdmin(Authentication)` 헬퍼 신규.
  - `settings.html` / `llm-settings.html` / `rag-settings.html` 모두:
    - `<div class="container" th:attr="data-is-admin=${isAdmin}">` 마킹.
    - `th:if="${!isAdmin}"` 노란 "읽기 전용" 배너 (자물쇠 + 안내 문구).
    - 페이지 끝 인라인 IIFE: container 내부의 모든 `input/select/textarea` 에 `disabled = true`, `button` (단 `.refresh-btn` 과 배너 버튼 제외) 에 `disabled + opacity .5 + cursor not-allowed`, `label.tog/.switch/.tog` 에 `pointer-events: none + opacity .6`. llm/rag 는 비동기 로드 후에도 disabled 가 유지되도록 `setTimeout(apply, 600)` 한 번 더 호출.
- **[6] 버전 2.0.1 → 2.0.3**:
  - `pom.xml` `<version>` 갱신.
  - `run.sh` / `stop.sh` / `restart.sh` 의 `heap-analyzer-2.0.1.jar` 패턴 `sed` 치환.
  - `progress.html` 푸터: `v2.0.1` → `v2.0.3`.
  - `CLAUDE.md` 빌드 안내 jar 경로 갱신.

### 검증
- `mvn clean package -DskipTests` 성공 → `target/heap-analyzer-2.0.3.jar` 생성 확인.
- 옛 PID(2.0.1 으로 기동 중)는 restart.sh 의 grep 패턴이 새 jar 이름을 보므로 자동 종료 대상에서 누락 — 수동 `kill -15 <pid>` 후 다시 `bash restart.sh` 로 9.93s 만에 정상 기동(`Started HeapAnalyzerApplication`).
- 운영 메모: 향후 마이너 버전 변경 시 기존 프로세스를 먼저 옛 jar 패턴으로 종료한 다음에 restart.sh 를 실행하거나, `pkill -f heap-analyzer` 로 일괄 종료 권장.
- 기능 검증(요청):
  1. ADMIN 로그인 → /settings·/settings/llm·/settings/rag 모두 입력/버튼 정상 동작, 노란 배너 미노출.
  2. USER 로그인 → 세 페이지 모두 노란 자물쇠 배너 + 모든 입력/액션 버튼 disabled. POST 시도하면 백엔드도 403.
  3. /settings 에서 MAT CLI Path / Heap Dump Directory 가 마스킹 없이 절대 경로 그대로 표시.
  4. /settings/llm Custom hint 의 truststore 안내가 `${APP_HOME}/certs/heap-truststore.jks` 로 출력. API URL/Key 입력칸 가로 확장 확인.
  5. /progress/{filename} 푸터 v2.0.3 표시.

## [2026-05-12] AI 인사이트 — DB 저장 실패 로깅 강화 + 수동 재저장 버튼

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/AiInsightManager.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- 수정: `src/main/resources/templates/analyze.html`
- 수정: `CHANGELOG.md`

### 변경 의도 (현상 진단)
- `AiInsightManager.saveAiInsight()` 가 모든 예외를 `try { ... } catch (Exception e) { logger.error(...) }` 로 swallow → void 정상 반환.
  - 결과: 컨트롤러 `analyzeLlm()` 의 외부 `try { saveAiInsight(...) } catch (saveEx)` 블록이 **실제로는 도달 불가능한 dead code**. `result.put("saved", true)` 가 항상 설정됨.
  - 화면의 "저장실패" 배너(`#aiSaveErrorBanner`)도 결국 표시될 일 없음 — 사용자가 본 배너는 다른 경로(예: 분석 직후 즉시 페이지 이탈) 였을 가능성이 큼.
- 또한 `AiInsightManager` 의 logger.error 가 stack trace 없이 메시지만 남겨 디버깅 어려움.
- 저장 실패 시 분석 결과는 메모리에만 존재 → 페이지 이탈 시 영구 유실. 사용자가 수동으로 재저장할 방법이 없었음.

### 내역
- **AiInsightManager.saveAiInsight()**:
  - logger.error 에 `type=...` + 4번째 인자로 `e` 추가 → stack trace 출력.
  - `throw new RuntimeException("AI 인사이트 DB 저장 실패: ...", e)` rethrow → 컨트롤러 try/catch 가 실제로 catch 함.
  - JavaDoc 에 "실패 시 RuntimeException 으로 rethrow" 명시.
- **HeapDumpController.analyzeLlm()** catch 블록:
  - 로그에 `type` 필드 추가 (간결화 — stack trace 는 AiInsightManager 가 이미 출력).
  - `result.saveError` 를 prefix 없이 원본 메시지로 변경 (UI 가 컨텍스트 문구 조립).
  - **신규: `result.retryPayload`** — 저장 실패 시 보낸 인사이트 데이터를 응답에 포함 → 화면이 그대로 들고 있다 수동 저장 시 재전송.
- **HeapDumpController 신규 엔드포인트 `POST /api/llm/insight/save`**:
  - Body: `{ "filename": "...", "insightData": { ... } }`.
  - 검증: filename / insightData 누락 시 400 + errorCode `MISSING_FILENAME`/`MISSING_PAYLOAD`.
  - `analyzerService.saveAiInsight(...)` 호출. 성공: `{ success:true, savedTo:"database" }`. 실패: 500 + `{ success:false, errorCode:"SAVE_FAILED", error:... }`.
  - 모든 경로 logger 출력: `[AI-Insight][SAVE-RETRY] 수동 저장 요청/성공/실패`.
- **analyze.html — 저장 실패 시 수동 재시도 UX**:
  - `_aiUnsavedPayload` 전역 변수 추가 (자동 저장 실패 시 보관, 성공/재분석 시 클리어).
  - `aiSaveErrorBanner` 마크업: `flex-wrap` 허용 + 우측에 노란색 "저장 재시도" 버튼 추가 (회전 화살표 SVG 아이콘).
  - 안내 문구: `결과를 잃을 수 있습니다` → `"저장 재시도" 를 누르거나, 페이지를 벗어나기 전에 재시도하세요`.
  - `showAiResult()`: 저장 실패 분기에서 `_aiUnsavedPayload = result.retryPayload || _buildRetryPayloadFromResult(result)` 로 캐시. 성공 분기에서 클리어. 재시도 버튼 enabled 복구.
  - 신규 함수 `retrySaveAiInsight()`: `POST /api/llm/insight/save` 호출. in-flight 동안 버튼 비활성화 + "저장 중…" 표시. 성공 시 배너 닫고 "저장됨" indicator + 경로 배너로 전환. 실패 시 alert + 버튼 복구.
  - 신규 함수 `_buildRetryPayloadFromResult()`: 컨트롤러가 `retryPayload` 를 안 보내준 경우 result.model/latencyMs/data 에서 동등 payload 재조립 (구버전 호환).
  - `_doStartAiAnalysis()` 시작 시 `_aiUnsavedPayload = null` 클리어.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공, `Started HeapAnalyzerApplication in 9.33 seconds`.
- 기능 검증(요청):
  1. `/analyze/{filename}` → AI 분석 시작 → 정상 시 `[AI-Insight][SAVE] 저장 완료` 로그 + "저장됨" 배지.
  2. DB 일시 장애 재현(예: HEAPDB 일시 차단) → 분석 후 `[AI-Insight] Failed to save to DB ... type=DataAccessException, msg=...` (stack trace 포함) + 컨트롤러 `[AI-Insight][SAVE] 저장 실패` 로그 동시 출력. 화면에 노란 배너 + "저장 재시도" 버튼 노출.
  3. DB 복구 후 "저장 재시도" 클릭 → `[AI-Insight][SAVE-RETRY] 수동 저장 요청 → 성공` 로그. 배너 닫히고 "저장됨" 표시 전환.

## [2026-05-12] Transfer Logs — 긴 에러 메시지를 버튼+팝오버로 분리

**변경 파일:**
- 수정: `src/main/resources/templates/server-logs.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- `/servers/logs` 의 "에러" 컬럼이 인라인 텍스트 + hover 펼침 방식이라, 긴 에러 메시지가 들어오면 hover 시 다른 행이 압축돼 보이거나 셀이 부풀어 다른 컬럼이 좁아져 가독성 저하.
- 에러 표시를 작은 "에러 보기" 버튼으로 변경하고 클릭 시 별도 팝오버에서 전체 메시지를 보도록 분리. 행 높이가 항상 일정.

### 내역
- **CSS**: 기존 `.td-err` (인라인 + `:hover { white-space: normal }`) 제거. 다음 신규 스타일 추가:
  - `.err-btn` — 작은 빨간 pill 형태 버튼 ("에러 보기"). 행 높이에 영향 없음.
  - `.err-pop` — body 직속 절대위치 팝오버 (table overflow 의 영향 안 받음). 헤더(제목/복사/닫기) + body(`pre-wrap`, `max-height: 320px`, 스크롤).
- **renderRow()** — `errorMessage` 가 있으면 `<button class="err-btn" data-err="..." onclick="showErrorPopover(this)">에러 보기</button>`, 없으면 `-`.
- **신규 JS 함수**:
  - `showErrorPopover(btn)` — 버튼 위치 기준 아래 우선 배치, 화면 하단 부족 시 위로 펼침. `body.textContent` 로 XSS 이중 방어.
  - `hideErrorPopover()`, `copyErrorPopover()` (`navigator.clipboard` 우선, `execCommand` 폴백, "복사됨" 1.2s 플래시).
  - 외부 클릭 / Esc / window resize / scroll(capture) 시 자동 닫기. 팝오버 내부 클릭은 무시.
- 팝오버 DOM 은 단일 인스턴스로 body 직속 (`<div id="errPopover">`).

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공, `Started HeapAnalyzerApplication in 9.53 seconds`.
- 기능 검증(요청): `/servers/logs` 접근 → FAILED 행의 "에러 보기" 클릭 → 팝오버에 전체 에러 텍스트 표시. 다른 행 클릭/Esc/스크롤로 닫힘. "복사" 버튼으로 클립보드 복사 확인.

## [2026-05-12] 사내 폐쇄망 LLM 게이트웨이(OpenAI 호환) 연동 호환성 개선

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/LlmConfigService.java`
- 수정: `src/main/resources/templates/llm-settings.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- 사내 LLM 매뉴얼이 OpenAI 파이썬 SDK 예시 코드 형태로 배포됨 (`base_url = "https://apigtw.../openapi/model/<UUID>"`).
- OpenAI SDK 는 base_url 뒤에 `/chat/completions` 를 자동 부착하지만, 본 앱은 사용자가 입력한 URL 을 그대로 호출 → base_url 만 그대로 붙여 넣으면 404(`ione.apigtw.error.apisvc`) 발생.
- 사용자가 SDK 의 자동 부착 동작을 모르고 base_url 만 등록해도 동작하도록 보정 + UI 안내 강화.

### 내역
- **LlmConfigService.normalizeChatCompletionsUrl(url)** 헬퍼 추가:
  - 비-Claude provider 의 URL 이 `/chat/completions`/`/completions`/`/messages` 로 끝나지 않으면 자동으로 `/chat/completions` 접미 부착.
  - 보정 시 `[LLM] API URL 자동 보정 — '/chat/completions' 접미 부착: A → B` warn 로그.
  - 쿼리스트링 보존 (`?key=value` 분리 후 path 만 검사 → `path/chat/completions?...` 로 재조립).
  - Claude 는 `/v1/messages` 가 정식 경로이므로 보정 대상에서 제외.
- 적용 지점 (총 4 곳): `testLlmConnection()` / `callLlmAnalysis()` / `callLlmChat()` / `callLlmChatStream()` 모든 `new URL(llmApiUrl)` 호출을 `new URL(normalizeChatCompletionsUrl(llmApiUrl))` 로 교체.
- **llm-settings.html — `customHint` 패널 추가**: provider=`custom` 선택/저장값일 때만 노출되는 녹색 안내 박스. 내용:
  - URL 끝은 `/chat/completions` 이어야 하며 누락 시 자동 부착됨을 명시 (OpenAI SDK 동등 동작).
  - 사내 게이트웨이 URL 예시: `https://apigtw.example.com/openapi/model/<UUID>/chat/completions`.
  - 인증: `Authorization: Bearer <API Key>` 자동 부착.
  - Model: 게이트웨이에 등록된 모델명 그대로 입력 (예: `share-gpt-oss-120b`).
  - SSL: 사내 사설 CA 사용 시 SSL Verify OFF 또는 `certs/heap-truststore.jks` 등록 안내.
- `loadLlmSettings()` / `onProviderChange()` 양쪽에서 customHint 토글 추가.

### 동작 원리 (사내 게이트웨이 호환)
1. 사용자는 매뉴얼 base_url(`https://apigtw.../openapi/model/<UUID>`) 을 그대로 API URL 에 붙여넣어도 됨.
2. provider=`custom` 인 경우 호출 직전 `normalizeChatCompletionsUrl()` 가 `/chat/completions` 자동 부착 → `https://apigtw.../openapi/model/<UUID>/chat/completions` 로 호출.
3. Body/헤더는 OpenAI Chat Completions 호환 (`Authorization: Bearer`, `model/messages/max_tokens`).
4. SSL: 운영은 `restart.sh` 가 `certs/heap-truststore.jks` 자동 부착 (사내 CA 신뢰), 개발/임시는 settings 의 SSL Verify OFF.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공, `Started HeapAnalyzerApplication in 10.29 seconds`.
- 기능 검증(요청): /settings/llm 에서 provider=Custom 선택 → URL 에 `https://apigtw.gapdev.shinhan.com/openapi/model/<UUID>` 만 입력하고 저장 → Test Connection 시 로그에 `[LLM] API URL 자동 보정` warn → `[LLM-Test] 연결 테스트 시작 — url=...완성된 URL...` 확인. 200 응답 시 성공, 비정상 시 `[LLM-Test] 연결 테스트 실패 — provider/url/status/errorCode/latency/body` 전체 로그 확인.

## [2026-05-12] LLM 연결/테스트 에러 서버 로그 보강 + 404 안내 메시지 보완

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/LlmConfigService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- 사내 폐쇄망 LLM 게이트웨이 호출 시 화면에는 `HTTP 404: {"errorCode":"ione.apigtw.error.apisvc",...}` 가 노출되지만 서버 로그에는 흔적이 남지 않아 운영 추적이 불가능.
- `testLlmConnection()` 의 HTTP 4xx/5xx 응답 본문 / `callLlmChat()` / `callLlmChatStream()` 의 HTTP 에러·네트워크 예외 경로가 result Map 에만 적재되고 logger 호출이 누락된 상태였음 (`callLlmAnalysis()` 만 충실히 로깅).

### 내역
- **LlmConfigService.testLlmConnection()**:
  - 시작 시 `[LLM-Test] 연결 테스트 시작 — provider/model/url/sslVerify` info 로그 추가.
  - 성공 경로 `[LLM-Test] 연결 테스트 성공` info 로그 추가.
  - HTTP 비정상 경로 `[LLM-Test] 연결 테스트 실패 — provider/url/status/errorCode/latency/body` error 로그 추가, result 에 `errorCode` 필드도 함께 적재.
  - `SocketTimeoutException` / `ConnectException` / `UnknownHostException` / `SSLException` 별도 catch 로 분리 → 각각 전용 errorCode + 한국어 메시지 + error 로그.
  - 일반 `Exception` catch 는 stack trace 포함 logger.error.
  - API 키 / URL 미설정 거부 케이스도 warn 로그 추가.
- **LlmConfigService.callLlmChat()** HTTP 에러 / 네트워크 예외 경로 logger.error 추가 (provider/url/status/errorCode/elapsed/body), `SSLException` 전용 catch 추가.
- **LlmConfigService.callLlmChatStream()** HTTP 에러 / 네트워크 예외 경로 logger.error 추가, `SSLException` 전용 catch 추가.
- **buildHttpErrorMessage(404)** — 사내 게이트웨이 케이스 반영. 기존 `/chat/completions` 만 언급하던 문구를 (1) URL 경로/모델 UUID 정확성, (2) 모델 등록·활성 여부, (3) 키-모델 권한 매핑(권한 없음을 404 로 회신하는 정책 가능성) 3 가지 체크리스트로 확장.

### 사내 LLM 404 분석 (참고)
- `errorCode = ione.apigtw.error.apisvc` 는 게이트웨이는 통과했으나 백엔드 API Service 단계에서 라우팅 대상 자원을 못 찾은 케이스.
- `path = /openapi/model/<UUID>` 형태 → 모델 단위 라우팅. 가능 원인: 모델 UUID 오타/미등록, 호출 키에 해당 모델 권한 없음(사내 게이트웨이는 권한 없음을 404 로 위장), URL 패턴 불일치, 환경(dev/stg/prod) UUID 분리.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공, `Started HeapAnalyzerApplication in 9.32 seconds`.
- 다음 LLM 호출/테스트 발생 시 `logs/heapdump-analyzer.log` 에 `[LLM-Test]` / `[AI-Chat]` / `[AI-Chat-Stream]` HTTP 에러·예외 로그가 남는지 확인 예정.

## [2026-05-12] 계정 관리 페이지 — 탭 전환 시 최신 데이터 갱신

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/controller/AdminController.java`
- 수정: `src/main/resources/templates/admin/users.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- `/admin/users` 4 개 탭(사용자/현재접속/접속이력/계정신청)이 첫 진입 시에만 데이터를 로드하고, 다시 돌아올 때는 캐시된 화면을 그대로 보여주는 한계.
- 다른 관리자가 사용자를 추가/수정하거나 신청이 들어와도 페이지 새로고침 전까지 반영되지 않음.

### 내역
- **AdminController.java** — `GET /api/admin/users` JSON 엔드포인트 신규 추가:
  - 응답 필드: `id`, `username`, `displayName`, `role`, `enabled`, `createdAt`(yyyy-MM-dd HH:mm).
  - `@PreAuthorize("hasRole('ADMIN')")` 클래스 레벨 적용 그대로 상속, ADMIN 외 접근 차단.
- **users.html** — 탭 전환 동작 변경:
  - `switchTab()` 의 `_lhLoaded`/`_reqLoaded`/`_asLoaded` 1-회성 게이트 제거 → 탭 클릭마다 무조건 fetch.
  - 사용자 목록 탭은 기존에 Thymeleaf `th:each` 서버 렌더에만 의존 → 탭 재진입 시 갱신 불가능했음. 신규 `loadUsers()` + `renderUsers()` 함수가 JSON API 결과로 tbody 재렌더(badge / action 버튼 dataset 포함, `th:each` 마크업과 동일 구조).
  - 펜딩 배지(`refreshPendingCount()`)도 탭 전환마다 호출 → 카운트 항상 최신.
  - 미사용 변수 `_lhLoaded`/`_reqLoaded`/`_asLoaded` 선언부 정리.
- 초기 페이지 로드는 기존 `th:each` 가 그대로 처리 → FOUC/추가 fetch 없음. 탭 재진입에서만 API 호출.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공, `Started HeapAnalyzerApplication in 9.28 seconds`.
- `GET /api/admin/users` 비인증 호출 → 302 (로그인 리다이렉트, 정상).

## [2026-05-12] Tomcat Access Log 활성화

**변경 파일:**
- 수정: `src/main/resources/application.properties`
- 수정: `CHANGELOG.md`
- 신규 디렉토리: `/opt/genspark/webapp_dump/logs/access/`

### 변경 의도
- HTTP 요청 추적을 위한 Tomcat 내장 access log 도입.
- 애플리케이션 로그(`heapdump-analyzer.log`)와 분리하기 위해 별도 하위 디렉토리(`logs/access/`)에 생성.

### 내역
- `server.tomcat.accesslog.*` 속성 9 개 추가:
  - `enabled=true`
  - `directory=/opt/genspark/webapp_dump/logs/access`
  - `prefix=access`, `suffix=.log`, `file-date-format=.yyyy-MM-dd`
  - `pattern=%h %l %u %t "%r" %s %b %D "%{Referer}i" "%{User-Agent}i"` (`%D` = 응답 소요 ms)
  - `rotate=true`, `rename-on-rotate=true` — 활성 파일은 항상 `access.log`, 자정 회전 시 `access.YYYY-MM-DD.log`
  - `buffered=false` — AccessLogValve 기본값(true)은 즉시 flush 되지 않아 검증/디버깅 불편. 운영 트래픽 부하 시 재고려 가능
  - `encoding=UTF-8`, `locale=ko_KR`
- 보존 기간 자동 삭제는 내장 Tomcat 미지원 — 필요 시 OS `logrotate` / cron 으로 별도 정리 (앱 로그의 `logging.file.max-history`와 다른 점 주의).

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 성공, `Started HeapAnalyzerApplication in 9.97 seconds`.
- `curl http://localhost:18080/login` 후 `/opt/genspark/webapp_dump/logs/access/access.log` 에 접속 라인 즉시 기록 확인 (status code, bytes, 응답 ms, Referer/UA 모두 포함).

## [2026-05-12] Phase 4B — HeapDumpController View / API 분리

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- 수정: `SECURITY_REFACTOR_PLAN.md`
- 수정: `CHANGELOG.md`

### 변경 의도
- `SECURITY_REFACTOR_PLAN.md` Phase 4B. View 페이지 렌더와 REST API 가 한 컨트롤러(2,833 라인) 에 혼재되어 책임 구분이 흐릿했음.
- 사용자 선택 "단순 2 분할 (View + API) (Recommended 안전)" — 6-API-컨트롤러 분할 대신 위험이 가장 낮은 단일 분리부터 진행.
- 향후 API 도메인별(분석/리포트/파일/이력/시스템/AI) 분리는 별도 사이클로 이연.

### 내역
- **HeapDumpViewController (516 라인, 신규)** — Thymeleaf 페이지 + form POST→redirect 액션 15 개:
  - GET `/`, `/files`, `/history`, `/settings`, `/settings/llm`, `/settings/rag`, `/compare`
  - GET `/analyze/{filename}`, `/analyze/result/{filename}`, `/analyze/{filename}/print-preview`, `/analyze/{filename}/print-html`
  - POST `/upload`, `/delete/{filename}`, `/history/delete/{filename}`, `/analyze/rerun/{filename}`
  - 의존성: `HeapDumpAnalyzerService` + `HeapDumpConfig` + `PdfReportService` + `HeapDumpController` (헬퍼 위임용)
- **HeapDumpController 변경** — REST API · SSE · 바이너리(PDF, Resource) · iframe HTML 만 잔류:
  - 15 개 view 메서드 본문 제거
  - 공유 헬퍼 6 개 `private` → `public` 가시성 승격: `isAdmin`, `buildHistory`, `aggregateDetections`, `buildClassSizeMap`, `formatBytes`, `truncateLog`
  - 미사용 import 제거: `org.springframework.ui.Model`, `RedirectAttributes`
  - inner public static DTO 8 개 (`AnalysisHistoryItem` 외) 는 그대로 유지 — view 측에서 `HeapDumpController.AnalysisHistoryItem` 으로 참조
- **분리 전략**: HeapDumpViewController 가 HeapDumpController 를 주입받아 헬퍼 호출 (facade 패턴). 호출 그래프 변경 없음, 외부 URL 시그니처 100% 동일.

### 검증
- `mvn clean package -DskipTests` 성공.
- `bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.074 seconds` — 정상 기동.
- 페이지 7 개 (`/`, `/files`, `/history`, `/settings`, `/settings/llm`, `/settings/rag`, `/compare`) 모두 `200`.
- 분석 결과 페이지 4 개 (`/analyze/{f}` 302 redirect, `/analyze/result/{f}` 200, `/print-preview` 200, `/print-html` 200) 정상.
- API 4 개 (`/api/history`, `/api/system/status`, `/api/queue/status`, `/api/settings`) 모두 `200`.
- 로그 무 신규 ERROR.
- HeapDumpController 라인 수: **2,833 → 2,365** (-468). 신규 View 컨트롤러 516 라인. 엔드포인트: 65 → 50 (API) + 15 (View).

---

## [2026-05-12] Phase 7-5 — AiInsightManager 분리

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/service/AiInsightManager.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- Phase 4A 마지막 sub-task. AI 인사이트 저장/조회/삭제 + DB 마이그레이션을 별도 컴포넌트로.
- Plan 의 "선택" 항목이지만 시리즈 마무리를 위해 진행.

### 내역
- `AiInsightManager` (161 라인):
  - `saveAiInsight(filename, data)` — DB upsert
  - `loadAiInsight(filename)` — DB → 파일 폴백 → DB 마이그레이션
  - `deleteAiInsight(filename)` — DB + 파일 정리 (`@Transactional`)
  - `migrateAiInsightsToDb()` — 일회성 파일→DB 마이그레이션 (`restoreResultsFromDisk` 호출용)
  - 의존성: `AiInsightRepository`, `FileManagementService` (resultDirectory), `HeapDumpConfig`
  - `AI_INSIGHT_FILE` / `RESULT_JSON` 상수 내부 보유
- `HeapDumpAnalyzerService`:
  - 4 개 메서드 본문(108 라인) → facade 위임 (4 × 3 라인)
  - `AI_INSIGHT_FILE` 상수 제거 (AiInsightManager 로 이전)
  - 생성자에 `AiInsightManager aiInsight` 주입
  - `deleteHistory()` 내 `aiInsightRepository.deleteByFilename()` 직접 호출은 그대로 유지 (트랜잭션 컨텍스트 안)

### 검증
- 빌드 성공.
- `GET /api/llm/insight/{filename}` → 8 키(model/latencyMs/summary/rootCause/recommendations/severity/severityDesc/analysedAt) 정상 응답.
- `/api/history` → 43건, `/api/settings` → cachedResults: 13.
- `HeapDumpAnalyzerService` 라인 수: **2,064 → 1,965** (-99, **2000 라인 미만 달성**).
- Phase 6-1 이후 누적: **3,581 → 1,965** (-1,616 라인, **-45%**).

---

## [2026-05-12] Phase 7-4 — FileManagementService Phase 2 (I/O 메서드)

**변경 파일:**
- 수정: `src/main/java/com/heapdump/analyzer/service/FileManagementService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- Phase 4A-4 Phase 2. Phase 1 (유틸/조회) 에 이어 파일 I/O 메서드를 `FileManagementService` 로 이전.
- 분석 파이프라인(`analyzeWithProgress`) 결합도 높은 메서드 (`cleanupTmpDir`/`moveZipsToResultDir`/`deleteHistory(DB)`) 는 `HeapDumpAnalyzerService` 잔존.
- HeapDumpAnalyzerService 내부 31 군데 호출은 private delegate 메서드를 통해 facade 위임.

### 내역
- `FileManagementService` 추가 메서드 (~300 라인):
  - 디렉토리 helper: `tmpDirectory`/`resultDirectory(filename)`/`resultJsonFile(filename)`
  - I/O: `uploadFile(MultipartFile)`/`getFile(filename)`/`deleteFile(filename)`
  - 압축: `compressDumpFile(File)`/`decompressDumpFile(File, File)`
  - 정리: `cleanupDuplicateGzFiles(File[])`
  - 상수: `RESULT_JSON` / `TMP_DIR_NAME`
- `HeapDumpAnalyzerService` 변경:
  - `uploadFile`/`getFile`/`deleteFile` 본문(149 라인) → facade 위임 (3 메서드 × 3 라인)
  - `compressDumpFile`/`decompressDumpFile` 본문(102 라인) → facade 위임 (2 × 3 라인)
  - `cleanupDuplicateGzFiles` 본문(26 라인) → facade 위임 (3 라인)
  - private helper (`tmpDirectory`/`resultDirectory`/`resultJsonFile`) facade 위임 — 내부 31 호출 그대로 동작
- `FormatUtils.formatBytes()` 활용 (HDS 내 `formatBytes` 사용처 제거).

### 검증
- 빌드 성공.
- `Started HeapAnalyzerApplication` + 13 캐시 복원.
- `/`/`/files`/`/history` 페이지 200 OK.
- `POST /api/upload/check` → `{"status":"OK"}` 정상.
- `GET /download/{filename}` → 200, 23MB 파일 정상 다운로드.
- `HeapDumpAnalyzerService` 라인 수: **2,327 → 2,064** (-263).
- Phase 6-1 이후 누적: **3,581 → 2,064** (-1,517 라인, **-42%**).

---

## [2026-05-12] Phase 7-3 — RagConfigService 분리 + RagService/EmbeddingService 재배선

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/service/RagConfigService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/RagService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/EmbeddingService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- Phase 4A-3. RAG 26 필드 + getter/setter + AES 암호화 + settings 영속화를 별도 컴포넌트로 분리.
- `RagService`/`EmbeddingService` 생성자 재배선 — `HeapDumpAnalyzerService` 대신 `RagConfigService` 직접 주입 (구조 단순화).
- `HeapDumpAnalyzerService` 의 RAG 메서드는 facade 유지 (Controller 무변경).

### 내역
- `RagConfigService` (421 라인):
  - 26 RAG 필드 (기본 7 + 검색 6 + 청킹 6 + semantic-server 4 + embedding/kNN 8)
  - `@PostConstruct init()` — config 로부터 초기화 (`ragPassword`/`ragApiKey`/`ragEmbeddingApiKey` AES 복호화)
  - 32 개 getter + 6 개 마스킹/`isXxxSet`
  - 5 개 그룹 setter: `setRagEnabled`/`setRagConfig`/`setRagSemanticConfig`/`setRagEmbeddingConfig`/`setRagChunkingConfig`
  - `applyFromSettings(Map)` / `collectSettings(Map)` / `collectApplicationProperties(Map)` 영속화 hook
  - 자체 `encryptForStorage()` (AES ENC 포맷)
- `HeapDumpAnalyzerService`:
  - 26 RAG 필드 제거
  - 생성자에 `RagConfigService` 주입
  - `loadPersistedSettings` RAG 33 줄 inline 복원 → `ragConfig.applyFromSettings(saved)` 한 줄
  - `persistSettings` RAG 33 줄 → `ragConfig.collectSettings(settings)` 한 줄
  - `syncApplicationProperties` RAG 33 줄 → `ragConfig.collectApplicationProperties(updates)` 한 줄
  - RAG getter/setter 36 개 모두 facade 위임 (시그니처 유지)
  - `encryptForStorage`/`trimOrEmpty` 헬퍼 제거 (RagConfigService 내부로 이동)
- `RagService` 생성자: `(HeapDumpAnalyzerService, EmbeddingService)` → `(RagConfigService, EmbeddingService)`. 33 개 getter 호출 모두 `ragConfig.xxx()` 로 치환.
- `EmbeddingService` 생성자: `(HeapDumpAnalyzerService)` → `(RagConfigService)`. 7 개 호출 치환.

### 검증
- 빌드 성공.
- `Started HeapAnalyzerApplication` + `Restored 13 saved results from disk`.
- `/settings/rag` 페이지 200 OK, 70,084 bytes.
- `/api/settings/rag` 응답: `enabled=False`, `searchMode=keyword`, `index=heap-knowledge-base`, `passwordSet=True` — AES 복호화 정상.
- `/api/llm/test-connection` → genspark + claude-sonnet-4-5 성공 (LLM 영향 없음 확인).
- `HeapDumpAnalyzerService` 라인 수: **2,644 → 2,327** (-317, Phase 6-1 이후 누적 -1,254 라인, -35%).

---

## [2026-05-12] Phase 7-2 — LlmConfigService 분리

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/service/LlmConfigService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- Phase 4A-2. `HeapDumpAnalyzerService` 의 LLM 책임을 단일 컴포넌트로 분리.
- 외부 시그니처 무변경 (facade 유지) — 컨트롤러 5 개 호출 위치(`HeapDumpController` 4 곳 + `AiChatController` 1 곳) 수정 없음.

### 내역
- `LlmConfigService` (919 라인):
  - 12 개 LLM 런타임 필드 + `DEFAULT_CHAT_SYSTEM_PROMPT` + `GENSPARK_MODELS`
  - `@PostConstruct init()` — `HeapDumpConfig` 로부터 초기값 로드 + `LLM_API_KEY` 환경변수 우선
  - 17 개 getter/setter (setter 는 로깅만, persist 는 facade 에서 트리거)
  - 4 개 호출 메서드: `testLlmConnection`/`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`
  - SSL 토글 헬퍼 `disableSslVerification`
  - HTTP 오류 분류: `classifyHttpError`/`buildHttpErrorMessage`/`extractLlmText`
  - Settings 영속화 hook: `applyFromSettings(Map)` / `collectSettings(Map)` / `collectApplicationProperties(Map)`
- `HeapDumpAnalyzerService`:
  - 12 개 LLM 필드 + `DEFAULT_CHAT_SYSTEM_PROMPT` 제거
  - 생성자에 `LlmConfigService` 주입
  - `loadPersistedSettings` 의 LLM 13 줄 inline 로드 → `llmConfig.applyFromSettings(saved)` 한 줄
  - `persistSettings` 의 LLM 12 줄 → `llmConfig.collectSettings(settings)` 한 줄
  - `syncApplicationProperties` 의 LLM 11 줄 → `llmConfig.collectApplicationProperties(updates)` 한 줄
  - getter/setter + 호출 메서드 모두 facade 위임 (시그니처 유지)

### 검증
- 빌드 성공.
- `Started HeapAnalyzerApplication` + `Restored 13 saved results from disk`.
- `/settings/llm` 페이지 → 200 OK, 65,400 bytes.
- `POST /api/llm/test-connection` → `{"provider":"genspark","success":true,"latencyMs":7911,"model":"claude-sonnet-4-5"}` 정상.
- `/api/settings` 의 `llm.enabled/provider/model/apiKeySet/chatSystemPrompt` 모두 정상 복원.
- `HeapDumpAnalyzerService` 라인 수: **3,445 → 2,644** (-801, Phase 6-1 이후 누적 -937).

---

## [2026-05-12] Phase 7-1 — FileManagementService Phase 1 분리 (유틸/조회)

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/service/FileManagementService.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- `HeapDumpAnalyzerService` 3,581 라인의 책임 분리 — Phase 4A-4 의 안전한 Phase 1.
- 외부 의존성 없는 순수 유틸/조회 메서드만 우선 추출 — pilot 으로 PR 패턴 검증.
- 외부 API 시그니처 무변경 (facade 위임).

### 내역
- `FileManagementService` (211 라인): `dumpFilesDirectory`/`isValidHeapDumpFile`/`stripExtension`/`getExtension`/`computePartialHash`/`generateUniqueName`/`checkDuplicate`/`listFiles` 8 개 메서드.
- 의존성: `HeapDumpConfig` + `HeapAnalysisResultCache` (gz 파일의 originalSize 조회용).
- `HeapDumpAnalyzerService` 생성자에 `FileManagementService fileMgmt` 주입.
- 내부 17 개 호출 (`isValidHeapDumpFile` 5회, `stripExtension` 8회, `getExtension` 4회 등) 은 private delegate 메서드를 통해 그대로 동작.
- `MessageDigest` / `NoSuchAlgorithmException` import 제거 (FileManagementService 로 이동).

### 검증
- 빌드 성공, `Started HeapAnalyzerApplication` + `Restored 13 saved results from disk`.
- `/` `/files` `/history` `/compare` 4 페이지 정상 렌더.
- `POST /api/upload/check` → `{"status":"OK"}` 정상.
- `GET /api/settings` → `cachedResults: 13` 유지.
- `HeapDumpAnalyzerService` 라인 수: **3,581 → 3,445** (-136).

---

## [2026-05-12] Phase 6-5 — analyze.html 인라인 CSS 분리

**변경 파일:**
- 신규: `src/main/resources/static/css/analyze.css`
- 수정: `src/main/resources/templates/analyze.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- `analyze.html` 4,866 라인 중 인라인 `<style>` 가 738 라인 차지 — 페이지 가독성 + 브라우저 캐시 활용 저해.
- 페이지의 CSS 변수 (`--bg`/`--text` 등) 와 페이지 고유 layout (KPI/TopConsumers/Suspects/Histogram/Threads/AI/RawData) 을 별도 파일로 분리.
- HTML/JS 로직은 **무변경** (단순 분리만).

### 내역
- `analyze.css` (738 라인): Reset & Base, `:root` 변수, `.app-header`, `.sidebar`, KPI 카드, Top Consumers 트리, Suspects 패널, Histogram, Threads 상세, AI 인사이트 패널, 플로팅 채팅 FAB, Raw Data iframe, Component Detail 모달, media queries.
- `analyze.html` `<head>` 에 `<link rel="stylesheet" href="/css/analyze.css?v=2026-05-12">`.
- 인라인 `<style>` 블록 (12~753 라인) 완전 제거.
- analyze 페이지는 자체 CSS 변수와 reset 을 가지므로 `common.css` 미사용 (의도).

### 검증
- 빌드/기동 정상.
- `GET /css/analyze.css` → 200, 45961 bytes.
- `GET /analyze/result/{filename}` → 200, 463856 bytes, `app-header`/`sidebar` 요소 렌더 확인.
- `analyze.html` 라인 수: **4866 → 4127** (-739 라인).

---

## [2026-05-12] Phase 6-4 — 공통 JS 헬퍼 (common.js) 인프라

**변경 파일:**
- 신규: `src/main/resources/static/js/common.js`
- 수정: `src/main/resources/templates/fragments/banner.html`
- 수정: `CHANGELOG.md`

### 변경 의도
- `escHtml`/`csrfToken`/`csrfHeader` 등이 4 페이지에서 약간씩 다른 시그니처로 중복 정의 (3 char escape vs 5 char, null 처리 유무 등).
- 페이지 인라인 코드를 일괄 마이그레이션하면 회귀 위험 큼 → **인프라만 우선 도입**.

### 내역
- `window.Common` 네임스페이스:
  - `Common.escHtml(s)` — 5 char escape + null 안전
  - `Common.csrfToken()` / `Common.csrfHeaderName()` — meta 태그 조회
  - `Common.fetchJSON(url, opts)` — CSRF 자동 부착 + 4xx/5xx 시 throw
  - `Common.appendCsrfToForm(form)` — 동적 폼 패턴 (CLAUDE.md 의 함정 #4)
  - `Common.formatBytes(n)` — 표시 포맷 (FormatUtils JS 미러)
- `fragments/banner.html` 상단에서 `<script src="/js/common.js?v=2026-05-12"></script>` 로 1 회 로드.
- 14 개 페이지 (banner 사용) 에서 자동 가용. 페이지 인라인 스크립트가 `Common.*` 호출 가능.

### 보류 (의도적 — 위험 회피)
- 페이지의 동명 함수 (`escHtml`/CSRF 인라인) 마이그레이션은 별도 PR. 현재는 무변경 (각 페이지가 자체 정의를 그대로 사용).

### 검증
- 빌드/기동 정상.
- `GET /js/common.js` → 200, 3480 bytes, `application/javascript`.
- 6 개 페이지 샘플 (`/`, `/files`, `/history`, `/servers`, `/admin/users`, `/ai-chat`) 모두 1 회 참조.

---

## [2026-05-12] Phase 6-3 — 공통 CSS 추출 (common.css)

**변경 파일:**
- 신규: `src/main/resources/static/css/common.css`
- 수정: 12 개 템플릿 (`index.html`/`files.html`/`history.html`/`settings.html`/`servers.html`/`admin/users.html`/`ai-chat.html`/`llm-settings.html`/`rag-settings.html`/`server-detail.html`/`server-logs.html`/`login.html`)
- 수정: `CHANGELOG.md`

### 변경 의도
- 9 개 이상 페이지가 동일한 reset/body/topbar CSS 를 인라인 `<style>` 로 중복 정의 — 시각적 일관성 깨질 위험 + 유지보수 비용.
- 페이지간 **100% 일치하는** 패턴만 안전하게 추출.

### 내역
- `common.css` (70 라인): reset(`* { margin/padding/box-sizing }`, `html, body { height: 100% }`), `body` base (font/color/background), `.topbar` + `.topbar-brand`/`.topbar-logo`/`.topbar-title`/`.topbar-right`/`.topbar-btn` (anti-wrap 미적용 표준 패턴).
- 각 페이지의 `<head>` 에 `<link rel="stylesheet" href="/css/common.css?v=2026-05-12">` 1 줄 추가.
- anti-wrap 확장 topbar (`servers.html`/`server-logs.html`/`admin/users.html`) 는 인라인 유지 (의도된 변형).
- page-specific body 속성(`overflow-x: hidden` 등) 은 짧은 override 로 잔존.

### 검증
- 빌드/기동 정상, `Restored 13 saved results from disk` + `Started HeapAnalyzerApplication`.
- 12 개 페이지 모두 `GET` 200 OK + `common.css` 1 회 참조.
- `git diff --stat`: 165 라인 삭제 / 21 라인 추가 = 페이지에서 순 144 라인 감축 (common.css 70 라인 신규).

---

## [2026-05-12] Phase 6-2 — GlobalExceptionHandler 중앙화

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/controller/GlobalExceptionHandler.java`
- 수정: `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- 기존 `IllegalArgumentException` 핸들러가 `HeapDumpController` 한 곳에만 존재 →
  `ServerController`/`AdminController` 등에 `FilenameValidator` 동일 패턴 도입 시
  500 으로 응답되는 위험 방지.
- HTML 뷰 요청 (`Accept: text/html`) 시 JSON `400` 대신 홈 `?error=invalidFilename` 으로 redirect.

### 내역
- `@ControllerAdvice GlobalExceptionHandler`:
  - 분기 기준: 요청 URI `/api/`로 시작 / `X-Requested-With: XMLHttpRequest` → JSON 400
  - 그 외 + `Accept: text/html` → `?error=invalidFilename` 302 redirect
  - 기본 → JSON 400 (`{"error": "..."}`)
- `HeapDumpController.handleBadFilename()` 제거 (`GlobalExceptionHandler` 에서 통합 처리).

### 검증
- `GET /download/foo.txt` → 400 + `{"error":"Unsupported file type..."}` (JSON)
- `GET /analyze/result/foo.txt` (`Accept: text/html`) → 302 redirect `http://localhost:18080/?error=invalidFilename`
- `POST /api/history/bulk-delete` (잘못된 이름 포함) → 200 + 컨트롤러 자체 검증 결과 (기존 동작 유지)

---

## [2026-05-12] Phase 6-1 — HeapAnalysisResultCache 서비스 분리

**변경 파일:**
- 신규: `src/main/java/com/heapdump/analyzer/service/HeapAnalysisResultCache.java`
- 수정: `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- 수정: `CHANGELOG.md`

### 변경 의도
- `HeapDumpAnalyzerService` (3,581 라인) 의 책임 분리 시작 (Phase 4A 안전한 일부).
- `memCache` `ConcurrentHashMap<String, HeapAnalysisResult>` 와 순수 map 연산을 별도 `@Component` 로 추출.
- 외부 API (`getCachedResult`, `clearCache`, `getCachedResultCount`, `getAllCachedResults`, `getCacheKeys`) 시그니처 무변경 — 컨트롤러 코드 수정 없음 (facade 패턴).

### 내역
- 신규 `HeapAnalysisResultCache`: `get`/`put`/`remove`/`size`/`values`/`keys`/`entries` 7 개 메서드.
- `HeapDumpAnalyzerService` 생성자에 `HeapAnalysisResultCache` 주입, 내부 `memCache.xxx()` 호출 → `resultCache.xxx()` 위임.
- 디스크 폴백/FS 정리는 서비스에 잔존 (config 경로 의존성).

### 검증
- 빌드 성공 (`mvn clean package -DskipTests`).
- 기동: `Restored 13 saved results from disk (data directory)` + `Started HeapAnalyzerApplication`.
- `GET /api/settings` → `cachedResults: 13` 정상.
- `GET /api/history` → 43 건 응답 (캐시 폴백 정상).

---

## [2026-05-11] Transfer Logs — 잘리지 않은 짧은 경로는 hover 미표시

**변경 파일:** `src/main/resources/templates/server-logs.html`, `CHANGELOG.md`

### 내역
- `.td-path` hover 효과(펼침 + `cursor: help`) 적용 조건을 `.is-truncated` 클래스로 한정.
- JS `markTruncated()` 신규: 렌더 후 각 `.td-path` 의 `scrollWidth > clientWidth` 검사 →
  잘림 발생 시에만 `.is-truncated` 부여.
- `renderTable()` 종료 시 호출 + `window.resize` 시 debounce(150ms) 로 재계산.

### 효과
- 짧은 경로(잘림 없음): hover 효과 없음, 일반 텍스트로 표시.
- 긴 경로(ellipsis 발생): hover 시 펼침 + cursor: help 그대로 동작.

---

## [2026-05-11] Transfer Logs — 컨테이너 폭 확장 + 원격 경로 hover 펼침

**변경 파일:** `src/main/resources/templates/server-logs.html`, `CHANGELOG.md`

### 내역
- `.container { max-width: 1280px → 1440px }` — 와이드 화면 가용 폭 확대.
- `.td-path` (원격 경로 컬럼):
  - 기본 max-width 250 → 360px (더 긴 경로 노출).
  - `display: inline-block; cursor: help` 추가 (hover 가능 명시).
  - `:hover` 시 `white-space: normal; word-break: break-all; max-width: 480px` —
    `/shblog/infra/jeus/kzdomain/kz_1/JeusServer.log` 같은 긴 경로도 마우스 hover 로 전체 확인 가능.
- 기존 `title` tooltip 은 그대로 유지(빠른 미리보기용).

---

## [2026-05-11] SSH local user — 빈 값 저장 시 현재 프로세스 계정으로 자동 채움

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`
- `src/main/resources/templates/settings.html`
- `CHANGELOG.md`

### 변경 의도
- Settings 에서 SSH local user 입력을 빈 칸으로 저장 시 그대로 빈 값으로 들어가
  RemoteDumpService 가 `runuser` 없이 현재 프로세스 계정으로 동작 (의도와 일치하나 명시적 표시 없음).
- 사용자 요청: 빈 칸 저장 시 자동으로 "현재 프로세스 기동 중인 OS 계정" 으로 채워서 명시화.

### 내역
- `RemoteDumpService.setSshLocalUser(user)`:
  - 트림 후 빈 문자열이면 `System.getProperty("user.name")` 을 가져와서 자동 채움.
  - 자동 채움 시 별도 로그: `[RemoteDump] SSH local user empty → auto-filled with current process user 'xxx'`.
- `settings.html` UI:
  - 보조 설명에 "빈 값으로 저장 시 현재 프로세스 기동 계정으로 자동 채움" 안내 추가.
  - input placeholder 를 "비워두면 자동 채움" 으로 변경, 폭 120→140px.

### 검증 (curl, root 프로세스 기준)
1. 초기 상태: `localUser: "sscuser"` (DB/properties 값)
2. `POST {"localUser": ""}` → 응답 `{"success": true, "localUser": "root"}`
3. `GET` 재조회 → `localUser: "root"`
4. 로그: `SSH local user empty → auto-filled with current process user 'root'`

---

## [2026-05-11] 분석 페이지 — 모든 텍스트를 Sans-Serif 로 통일

**변경 파일:** `src/main/resources/templates/analyze.html`, `CHANGELOG.md`

### 변경 의도
- 분석 페이지에 monospace 와 sans-serif 가 혼재 (수치/클래스명/파일명/Top Consumers 표/Threads 표 등 → JetBrains Mono).
- 사용자 요청: 모든 글자를 sans-serif 스타일로 통일.

### 내역
- `:root` 의 `--mono` CSS 변수 값을 sans-serif 스택으로 교체:
  - 기존: `'JetBrains Mono','Fira Code','Cascadia Code','Consolas',monospace`
  - 변경: `-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif`
  - `var(--mono)` 를 사용하는 20여 곳(KPI 값, 사이드바 stat, 데이터 테이블 col-size/count, rank-num, pct-bar-text, cd-meta-value, cd-table.num, file-info-val, 채팅 코드 등) 일괄 자동 sans-serif.
- `--sans` 변수 신규 명시 (기존 `.cd-help-btn` 에서 `var(--sans)` 미정의 참조 → 명시 정의로 안정화).
- 인라인 hardcoded mono 폰트 제거:
  - AI 분석 실패 에러 코드 div 의 `font-family:monospace` 제거.
  - Chart.js Top Consumers Y 축 ticks 의 `family:"'JetBrains Mono',monospace"` 제거.

### 효과
- 분석 페이지(`/analyze/result/{filename}`) 전체에서 mono 폰트가 사라지고 sans-serif 로 통일.
- 차트·테이블·코드 라벨 모두 동일한 시각 톤.

### 검증
- 빌드·재기동 후 페이지 응답 정상 (509 KB).
- 응답 HTML 에서 `monospace` / `JetBrains Mono` / `Courier` 등 잔존 폰트 패밀리 키워드 0건.

---

## [2026-05-11] Analysis Files — 서버 필터에서 deleted 잔존 서버 제외

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `CHANGELOG.md`

### 변경 의도
- `/files` 서버 필터 dropdown 에 실제 파일이 존재하지 않는 서버(`app-server-01`, `app-server-02`)가 노출.
- 원인: analysis_history DB 에는 기록이 남아있지만 dumpfiles/ 디렉토리에서는 삭제되어 fileDeleted=true 인 행. admin 사용자에게는 history list 에 deleted 포함되지만 "deleted 표시" 토글 OFF 가 기본값이라 화면 행에는 안 보임 → 사용자 관점에서 "기록 없는 서버" 가 dropdown 에 떠 있는 모순.

### 내역
- `filesPage` 의 distinct serverName 추출 스트림에 `.filter(h -> !h.isFileDeleted())` 추가.
- 결과: 실제 파일이 존재하는 행에 기반한 서버만 dropdown 노출.

### 검증
- 변경 전: `JeusServer1`, `app-server-01`, `app-server-02` 표시.
- 변경 후: `JeusServer1` 만 표시. DB 잔존 ghost 서버 제거.

---

## [2026-05-11] 계정 관리 모바일 — Topbar 줄바꿈 수정 + 탭 toolbar 콤팩트 레이아웃

**변경 파일:** `src/main/resources/templates/admin/users.html`, `CHANGELOG.md`

### 내역
**(1) Topbar (server-logs/servers 와 동일 패턴 적용)**
- `.topbar-brand { display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1 1 auto; }` 명시.
- `.topbar-title` ellipsis 처리, `.topbar-right { flex-shrink: 0 }` 로 햄버거 + "계정 관리" 한 줄 정렬.

**(2) 접속 이력 / 계정 신청 탭 toolbar — 모바일 콤팩트 레이아웃 (≤640px)**
- 기존 `flex-direction: column` (검색/상태/페이지사이즈/새로고침 모두 풀폭 4행 스택) 제거.
- **3행 콤팩트 레이아웃**:
  - Row 1: 검색 `flex: 1 1 100%` (풀폭)
  - Row 2: 상태 select + 페이지 사이즈 select 각각 `flex: 1 1 calc(50% - 4px)` (50/50)
  - Row 3: 새로고침 버튼 `flex: 0 0 auto; margin-left: auto` (우측 정렬, 콤팩트)
- 접속 이력 탭(4 컨트롤): 위 3행 모두 사용. 계정 신청 탭(3 컨트롤, 새로고침은 page-hdr): 2행으로 종결.

### 효과
- 모바일에서 햄버거 + 타이틀이 한 줄 정렬.
- 검색·상태·라인 셀렉트가 그리드처럼 조화롭게 배치되어 세로 공간 절감.
- 새로고침 버튼은 우측 정렬되어 작업 흐름 일관성 유지.

---

## [2026-05-11] 접속 이력 모바일 — 상태 배지(성공/실패) 줄바꿈 수정

**변경 파일:** `src/main/resources/templates/admin/users.html`, `CHANGELOG.md`

### 내역
- `.badge` 공통 클래스에 `display: inline-block; white-space: nowrap` 추가.
- 좁은 모바일 폭에서 "성공"/"실패" 두 글자가 wrap 되어 행 높이가 변동하던 문제 해소.
- admin 페이지 모든 badge(역할/활성/승인 상태 등) 에 동일 효과.

---

## [2026-05-11] 최근 탐지 결과 패널 폰트 사이즈 확대

**변경 파일:** `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 내역
- 가독성 향상을 위해 `.det-recent-*` 클래스 폰트를 일괄 1~2px 인상.

| 항목 | 변경 |
|---|---|
| 패널 타이틀 | 13 → 15px |
| 카운트(`N건`) | 11 → 12px |
| severity / server 배지 | 10 → 11px (padding 2/7 → 3/8) |
| 날짜 | 11 → 12px |
| **본문 타이틀** | 12 → 14px |
| 메타(파일명) | 11 → 12px |
| empty state | 12 → 13px |
| 행 padding | 9px 8px → 11px 10px (간격 균형) |
| 행 내부 gap | 4 → 5px |

---

## [2026-05-11] Servers 모바일 작업 버튼 — 정확한 2×2 그리드 배치

**변경 파일:** `src/main/resources/templates/servers.html`, `CHANGELOG.md`

### 내역
- ≤640px `.actions-cell` 을 flex-wrap 기반 → **CSS Grid 2열 1fr 1fr** 로 변경.
- 버튼 width 100%, gap 4px 로 균일한 정렬.
- 결과: 스캔·테스트 / 수정·삭제 4버튼이 항상 2×2 매트릭스로 표시.

---

## [2026-05-11] Servers 페이지 모바일 헤더 동일 수정 + 테이블 모바일 최적화

**변경 파일:** `src/main/resources/templates/servers.html`, `CHANGELOG.md`

### 변경 의도
- Transfer Logs 와 동일하게 모바일에서 햄버거 버튼 아래 "Servers" 타이틀이 줄바꿈되는 현상.
- 7컬럼 테이블이 모바일 폭에 비해 너무 넓어 가독성 저하.

### 내역
**(1) Topbar 줄바꿈 수정**
- `.topbar-brand { display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1 1 auto; }` 명시.
- `.topbar-title` ellipsis 처리 + `.topbar-right { flex-shrink: 0 }` 로 우측 버튼 고정 폭.
- ≤640px 에서 우측 버튼 콤팩트화 (`padding: 5px 8px; font-size: 11px;`).

**(2) 테이블 모바일 최적화**
- 우선순위 낮은 3컬럼에 `col-hide-sm` 클래스 부여:
  - SSH 계정, 덤프 경로, 자동탐지 (≤640px 시 숨김).
- ≤640px 잔여 4컬럼: **이름 / 호스트 / 상태 / 작업** — 핵심 정보만 노출.
- `.actions-cell` 신규 정의:
  - 데스크톱: `white-space: nowrap` 유지 (한 줄).
  - 모바일: `white-space: normal` + 버튼 wrap 허용, padding/font 축소
    (`padding: 4px 8px; font-size: 11px;`).
- ≤900px: `.panel { overflow-x: auto; -webkit-overflow-scrolling: touch; }` 로 폭 초과 시 가로 스크롤 보장.
- 모바일 전용 사이즈 조정: `.stable` font 13→12px, th/td padding 12→10px, badge 11→10px, page-ttl 18→16px.

### 효과
- 햄버거 + "Servers" 타이틀이 한 줄에 깔끔 정렬.
- 모바일에서 4컬럼만 노출되어 가로 스크롤 없이 표가 화면에 들어옴.
- 작업 버튼(스캔/테스트/수정/삭제) 4개가 좁은 폭에서 2×2 wrap 되어 모두 접근 가능.
- 우선순위 낮은 컬럼 정보는 행 클릭(서버 상세 페이지 진입) 으로 여전히 접근 가능.

---

## [2026-05-11] Transfer Logs 모바일 헤더 — 햄버거 버튼·타이틀 줄바꿈 수정

**변경 파일:** `src/main/resources/templates/server-logs.html`, `CHANGELOG.md`

### 변경 의도
- 모바일에서 햄버거 버튼 아래로 "Transfer Logs" 글자가 떨어져 두 줄로 표시되는 현상.

### 원인
- 다른 페이지(files.html, history.html 등)에는 `.topbar-brand { display: flex; align-items: center; gap: 10px; }`
  가 명시되어 있는데 server-logs.html 에는 누락되어, 햄버거 SVG 와 텍스트가 인라인 흐름으로 배치 →
  우측 버튼(Servers / Dashboard) 가 차지하는 공간 탓에 좁은 화면에서 줄바꿈 발생.

### 내역
- `.topbar-brand` 명시: `display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1 1 auto;`
  (다른 페이지 패턴 + `min-width: 0` 으로 텍스트 ellipsis 가능하게 함).
- `.topbar-title`: `white-space: nowrap; overflow: hidden; text-overflow: ellipsis;` 추가 → 좁은 폭에서
  줄바꿈 대신 말줄임 처리.
- `.topbar-right`: `flex-shrink: 0` 명시 → 우측 버튼들이 줄어들지 않게 고정.
- ≤640px: 우측 버튼 콤팩트화 (`padding: 5px 8px; font-size: 11px;`), gap 축소(6→4px) 로 가용 공간 확보.

### 효과
- 햄버거 버튼과 "Transfer Logs" 타이틀이 한 줄에 정렬.
- 매우 좁은 폭에서도 타이틀은 ellipsis 처리, 우측 버튼은 유지되어 레이아웃이 깨지지 않음.

---

## [2026-05-11] Comparison picker — 삭제된 덤프 파일 제외

**변경 파일:** `src/main/resources/templates/compare.html`, `CHANGELOG.md`

### 변경 의도
- `/compare` picker 에 deleted 표시된 덤프 파일도 선택 옵션으로 노출됨. 선택 시 비교 결과가
  의미 없는 데이터에 기반하거나 오류 가능. (관리자만 deleted 가 `/api/history` 에 포함되는데
  picker 에서까지 노출시킬 필요 없음)

### 내역
- picker JS 필터 조건 강화: `status === 'SUCCESS'` AND `fileDeleted !== true`.

### 효과
- 검증 결과: SUCCESS 35건 중 26건이 fileDeleted=true → 제외, picker 에는 9건만 노출.
- deleted 파일 비교 가능성 차단.

---

## [2026-05-11] Analysis Files — 날짜 컬럼 연도 표시 + 서버·기간 필터 추가

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- `src/main/resources/templates/files.html`
- `CHANGELOG.md`

### 변경 의도
- `/files` (Analysis Files) 의 날짜 컬럼이 `MM-dd HH:mm` 만 표시되어 연도 분간 불가
  (예: `03-18 10:00` → 2025/2026 구분 어려움).
- 검색이 파일명 substring 매칭만 가능. 서버별/기간별 필터링 부재 → 누적된 분석 이력에서 원하는
  항목 찾기 어렵움.

### 내역
**(1) 날짜 포맷 — 연도 포함**
- `buildHistory(...)`: `SimpleDateFormat("MM-dd HH:mm")` → `"yyyy-MM-dd HH:mm"`.
- DB-only deleted 항목 분기에서 사용하는 `DateTimeFormatter.ofPattern("MM-dd HH:mm")` 도 동일하게
  `"yyyy-MM-dd HH:mm"` 로 변경.
- 영향: `/files`, `/history` 등 `buildHistory` 를 사용하는 모든 페이지의 `formattedDate` 출력값에
  연도 자동 포함 (예: `2026-05-08 00:21`).

**(2) filesPage 컨트롤러 — 서버 목록 주입**
- `analysisHistory` 에서 distinct `serverName` 추출 → 정렬 → `serverNames` 모델 attr.

**(3) files.html — 필터 dropdown UI**
- 검색 placeholder: "파일명·서버명으로 검색..." 로 안내 강화.
- `#serverFilter` select: 옵션 "서버: 전체" + "Local (서버 미지정)" + 동적 서버명 목록.
- `#periodFilter` select: "기간: 전체 / 오늘 / 최근 7일 / 최근 30일 / 최근 90일".
- 두 select 모두 `.filter-sel` 공통 클래스 + `onchange="onFilterChange()"`.

**(4) applyFilter JS 확장**
- 검색어: `data-name` + `data-sort-server` 양쪽 모두에서 substring 매치.
- 서버 필터: `data-sort-server` 와 비교. `__local__` 값은 빈 문자열(서버 미지정) 매치.
- 기간 필터: 클라이언트 `Date.now()` 기준 임계 epoch 계산 후 `data-sort-date` 와 비교.
  `today` 는 자정 기준(`setHours(0,0,0,0)`).
- 모든 필터 동시 적용 후 `_filteredRows` 갱신 → 페이지 1로 reset 후 render.

**(5) 모바일 콤팩트 레이아웃 (≤640px)**
- 기존 `flex-direction: column` (모두 풀폭 스택) 제거.
- 검색바 `flex: 1 1 100%` (1행 풀폭), 두 필터 select 각각 `flex: 1 1 calc(50% - 4px)` 로 2행 50/50.
- 선택 버튼/deleted toggle/행 표시 는 잔여 폭 자동 분배.

### 효과
- 한 화면에 작년/올해 파일이 섞여 있어도 즉시 식별 가능.
- 특정 서버에서 발생한 덤프만 빠르게 추리기 가능.
- "최근 7일/30일" 등 시간 단위 필터로 디버깅 콘텍스트 좁히기 용이.
- 검색어가 서버명에도 매치되어 "jeus" 등 키워드로 관련 파일 일괄 조회 가능.
- 데스크톱·모바일 모두에서 툴바가 한 눈에 들어옴.

---

## [2026-05-11] Transfer Logs 모바일 툴바 콤팩트 레이아웃 (5행 → 3행)

**변경 파일:** `src/main/resources/templates/server-logs.html`, `CHANGELOG.md`

### 변경 의도
- ≤640px 에서 검색/상태/서버/행표시/Export 가 모두 풀폭 세로 스택 (5행) 으로 화면을 과도하게 차지.
- 검색·상태·서버 컨트롤이 조화롭게 배치되지 않음.

### 내역 (≤640px 미디어 쿼리)
- `flex-direction: column` 제거 → 기본 wrap row 유지.
- **3행 콤팩트 레이아웃**:
  - Row 1: 검색바 `flex: 1 1 100%` (풀폭)
  - Row 2: 상태 select + 서버 select 각각 `flex: 1 1 calc(50% - 4px)`
  - Row 3: 행 표시 wrap + Export wrap 각각 `flex: 1 1 calc(50% - 4px)`
- 폰트 13 → 12px, padding 살짝 축소로 컨트롤 높이 균일화.
- `page-size-wrap` 내부 select 는 `flex: 0 0 80px` 로 고정, label 은 11px 로 축소.

### 효과
- 모바일에서 툴바 세로 점유 공간 약 40% 감소.
- 검색·상태·서버가 그리드처럼 정렬되어 시각적 조화 확보.
- 데스크톱(>640px) 동작 변동 없음.

---

## [2026-05-11] Analysis Failed 페이지 폭 확장 (720px → 960px)

**변경 파일:** `src/main/resources/templates/analyze.html`, `CHANGELOG.md`

- `.error-page-inner` max-width 720px → 960px.
- `.error-card` 내부 패딩 36/32/28 → 40/36/30 으로 비례 확대.
- `.error-msg` / `.error-meta` max-width 560px → 720px, 메타 박스 패딩·gap 도 소폭 키움.

---

## [2026-05-11] Analysis Failed 페이지 리디자인 — 중앙 정렬 + 카드 시각화 강화

**변경 파일:**
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- `/analyze/result/{filename}` 의 ERROR 상태 페이지(`th:if="${error}"` 블록)가 정중앙에 위치하지 않고
  디자인이 평면적이라는 사용자 피드백.
- 기존 구조 문제:
  - 컨테이너가 `.main-content` 클래스 + 인라인 `margin-left:0; max-width:860px; margin-left:auto;`.
    두 번째 `margin-left:auto` 가 첫 번째를 덮어써서 **뷰포트 전체 기준 중앙 정렬**이 되었으나,
    220px 고정 배너의 우측 가시 영역 기준으로는 **좌측 치우침**.
  - `.error-card` 가 단순 평면 배너(좌측 4px border + danger-light 배경)로 디자인 위계감 부족.
  - 메타 정보(Failed at/Duration/File Size)가 가로 plain 텍스트 나열.
  - 버튼이 인라인 스타일의 단색 사각형으로 hover/시각 피드백 미흡.

### 내역
**HTML 구조 재편 (`analyze.html` 711–755 블록)**
- 외곽 wrapper `<div class="error-page-wrap">` + 내부 `<div class="error-page-inner">` 도입.
- 배너 우측 가시 영역 한가운데 정렬을 위해 wrapper 에 `margin-left: var(--banner-w)` 적용 후
  flex `justify-content:center` 로 inner(720px max-width) 를 가시 영역 중앙에 배치.
- 카드 상단에 64px 원형 아이콘(SVG 경고 삼각형) 추가 → 위계감 확보.
- 메타 영역을 `<dl class="error-meta">` (dt/dd 컬럼 그룹)로 재구성. 회색 카드 안에서
  uppercase 라벨 + 모노스페이스 값 표시.
- 버튼은 별도 클래스 `.btn-rerun` / `.btn-home` 로 분리(인라인 스타일 제거).

**CSS 신규 (`analyze.html` style 블록)**
- `.error-page-wrap` — wrapper 정렬: header offset + banner offset + flex center + 48px top padding.
- `.error-page-inner` — `max-width: 720px`.
- `.error-card` — 흰 배경 + 빨간 톤 그림자(0 12px 32px rgba(220,38,38,.08)) 로 부드러운 카드 입체감.
- `.error-icon` — 64px circle, danger-light 배경, 안에 32px SVG.
- `.error-title` — 22px / 800 / -.01em letter-spacing.
- `.error-msg` — 4b5563 회색 14px / 1.6 line-height / pre-wrap 으로 멀티라인 메시지 자연 표시.
- `.error-meta` — 회색 박스 안 flex 그룹, dt(라벨)+dd(값) 세로 stack.
- `.btn-rerun` — warning 색 + hover 시 translateY(-1px) + colored box-shadow.
- `.btn-home` — primary 색 + 동일 hover 효과.
- 반응형: ≤900px 시 wrapper margin-left 0 (배너 숨김 모바일 폭),
  ≤600px 시 카드 padding 축소 + 버튼 width 100% (세로 스택).

### 효과
- 220px 배너가 좌측에 고정된 데스크톱 폭에서도 카드가 가시 영역의 중앙에 위치.
- 헤딩-아이콘-메시지-메타-액션 위계가 분명해져 분석 실패 상황을 한눈에 파악.
- 액션 버튼이 hover 시 살짝 떠오르고 colored shadow 가 입혀져 누를 곳임을 시각적으로 안내.
- 모바일에서는 카드 패딩 축소 + 버튼 풀폭으로 터치 사용성 확보.
- MAT CLI Log 카드는 그대로 유지(`error-log-card` 클래스만 부여).

---

## [2026-05-11] Compare 페이지 레이아웃 폭 확장 (1100px → 1400px)

**변경 파일:**
- `src/main/resources/templates/compare.html`
- `CHANGELOG.md`

### 변경 의도
- 사용자 요청: compare picker / 결과 페이지 가로 사이즈가 좁다.
- 다른 페이지: dashboard 1800px, history 등 1280px, settings 1080px.
  compare 는 1100px 로 비교 다이프 테이블의 클래스명 컬럼이 좁아 가독성 저하.

### 내역
- `.main` `max-width: 1100px` → `1400px` (한 곳만 변경).
- 반응형 미디어 쿼리 (≤900px, ≤600px) 는 그대로 유지.

---

## [2026-05-11] Compare 결과 페이지 빈 화면 수정 — SpEL Math.min overload ambiguity

**변경 파일:**
- `src/main/resources/templates/compare.html`
- `CHANGELOG.md`

### 변경 의도
- `/compare?base=X&target=Y` 진입 시 브라우저 콘솔에
  `Failed to load resource: net::ERR_INCOMPLETE_CHUNKED_ENCODING` 로 빈 페이지가 표시됨.
- 서버 로그 추적 결과:
  ```
  SpelEvaluationException: EL1033E: Method call of 'min' is ambiguous,
    supported type conversions allow multiple variants to match
  Exception evaluating SpringEL expression:
    "T(java.lang.Math).min(80, T(java.lang.Math).abs(diff.delta) / 1048576)"
    (template: "compare" - line 218)
  ```
- 원인: `diff.delta` 가 `long`, `Math.abs(long)` → `long`, `long / 1048576` → `long`.
  반면 `80` 은 `int` 리터럴. Spring EL 의 메서드 해석기가
  `Math.min(int, int)` (long → int 좁힘) 과 `Math.min(long, long)` (int → long 넓힘) 사이에서
  결정하지 못하고 ambiguous 로 실패.
- 응답 헤더와 body 일부가 이미 chunked 로 전송된 뒤 예외가 터져 종료 마커 미전송 →
  브라우저가 `ERR_INCOMPLETE_CHUNKED_ENCODING` 으로 페이지 폐기 → 빈 화면.

### 내역
- `compare.html` line 218 의 SpEL 표현식에서 정수 리터럴에 `L` 접미사 부착:
  - 기존: `T(java.lang.Math).min(80, T(java.lang.Math).abs(diff.delta) / 1048576)`
  - 변경: `T(java.lang.Math).min(80L, T(java.lang.Math).abs(diff.delta) / 1048576L)`
- `Math.min(long, long)` 단일 시그니처로 매칭되어 ambiguity 해소.

### 효과
- 결과 페이지 응답 길이 45,124 → 48,857 bytes (delta-bar 행 포함 정상 마감).
- `style="width:70px"` / `style="width:34px"` 등 동적 너비 인라인 스타일 정상 렌더.
- 서버 로그에 `EL1033E` / Thymeleaf TemplateInputException 새로 발생 없음.

---

## [2026-05-11] LLM Connection PKIX 에러 조치 — Truststore + llm.ssl.verify 토글

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`
- `src/main/java/com/heapdump/analyzer/config/HeapDumpConfig.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- `src/main/resources/application.properties`
- `src/main/resources/templates/llm-settings.html`
- `restart.sh`
- `.gitignore`
- `CHANGELOG.md`

### 변경 의도
- `/settings/llm` → Test Connection 시 사내 게이트웨이(예: `apigtw.gapdev.shinhan.com`) 호출이
  `PKIX path building failed: unable to find valid certification path to requested target` 로 실패.
- 원인: 사내 게이트웨이의 TLS 인증서가 사내 사설 CA 로 서명되어 있어 JDK 번들 cacerts 가
  신뢰하지 못함. Python 가이드는 `truststore` 라이브러리로 OS truststore(사내 CA 사전 설치)를
  사용해 우회하지만, 본 앱은 JDK 기본 cacerts 만 사용.
- LLM 호출 4개 메서드(`testLlmConnection`, `callLlmAnalysis`, `callLlmChat`, `callLlmChatStream`)는
  `HttpURLConnection` 을 SSL 커스터마이즈 없이 사용. RagService 의 `ragSslVerify` 패턴을 미러링해
  운영(JVM truststore)과 긴급 우회(토글) 양쪽 경로 제공.

### 내역
**(A) JVM Truststore — 정공법 / 운영 권장**
- `restart.sh`: `/opt/genspark/webapp_dump/certs/heap-truststore.jks` 가 존재하면
  `-Djavax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStorePassword` / `-Djavax.net.ssl.trustStoreType` 3종을
  자동 부착. 파일 미존재 시는 JDK 기본 cacerts 사용으로 fallback (기동에 영향 없음).
- `.gitignore`: `certs/`, `*.jks`, `*.p12`, `*.pem`, `*.crt`, `*.cer`, `*.key` 추가
  (인증서/키스토어 git 커밋 방지).
- 운영 절차: 사내 Root CA `.crt` 를 `keytool -importcert -alias shinhan-internal-ca` 로
  `heap-truststore.jks` 에 import 후 재기동.

**(B) `llm.ssl.verify` 토글 — RagService 패턴 미러링 / 긴급 우회**
- `application.properties`: `llm.ssl.verify=true` (기본값) 추가.
- `HeapDumpConfig`: `@Value("${llm.ssl.verify:true}") boolean llmSslVerify` + `isLlmSslVerify()` getter.
- `HeapDumpAnalyzerService`:
  - `volatile boolean llmSslVerify` 필드 + 생성자 초기화.
  - settings.json 로드/저장, `syncApplicationProperties()` 동기화.
  - `isLlmSslVerify()` / `setLlmSslVerify(boolean)` getter·setter.
  - private `disableSslVerification(HttpsURLConnection)` 헬퍼 추가
    (RagService/EmbeddingService 와 동일 패턴 — TrustManager 전역 trust + Hostname verifier all-allow).
  - 4개 LLM 메서드의 `openConnection()` 직후
    `if (!llmSslVerify && conn instanceof HttpsURLConnection) disableSslVerification(...)` 분기 추가.
- `HeapDumpController`:
  - `POST /api/llm/config`: 요청 body 에 `sslVerify` 키가 있으면 `setLlmSslVerify()` 호출.
    응답에 `sslVerify` 노출.
  - `GET /api/settings`: `llm.sslVerify` 필드 노출.
- `llm-settings.html`:
  - Provider/API URL/Model 카드에 "SSL Verify" 체크박스 + 안내 문구 추가.
  - `loadLlmSettings()` 가 `llm.sslVerify` 를 체크박스에 반영
    (`!== false` 로 미설정 시 true 기본).
  - `saveLlmConfig()` 가 `sslVerify` 를 body 에 포함해 전송.

### 효과
- 사내 Root CA 를 `heap-truststore.jks` 에 등록하면 PKIX 에러 해소 (운영 권장 경로).
- 개발/긴급 시에는 `/settings/llm` 의 "SSL Verify" 토글 OFF 로 즉시 우회 가능
  (MITM 위험 — 안내 문구로 사용자에게 명시).
- RagService / EmbeddingService 의 기존 SSL 동작에는 영향 없음.

### 후속 운영 작업 (코드 외)
1. 사내 보안팀에서 Root CA `.crt` 수령 또는 `openssl s_client -showcerts` 로 체인 추출.
2. `keytool -importcert -alias shinhan-internal-ca -file <ca.crt>
   -keystore /opt/genspark/webapp_dump/certs/heap-truststore.jks -storepass changeit -noprompt`.
3. `bash restart.sh` 재기동 — `[restart] Using custom truststore: ...` 메시지 확인.
4. `/settings/llm` 에서 baseUrl 을 사내 게이트웨이 풀 URL 로 변경, API Key 갱신, Test Connection.

---

## [2026-05-11] 원격 스캔 — find 비치명적 권한 오류 관용 처리

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`
- `CHANGELOG.md`

### 변경 의도
- 사내 시스템에서 Target Server 스캔 시 `stderr=원격 명령 실행 실패 (exit 1)` 로
  실패 처리됨. UTF-8 한글 로그 확인 후 분석하니, `find` 의 stderr 는 비어 있고
  단지 exit code 만 1.
- 원인: 원격 `find /tmp -maxdepth 2 ...` 가 다른 유저 소유 하위 디렉토리 진입 시
  `Permission denied` → find 가 exit 1 로 종료 → 기존 코드는
  `2>/dev/null` 로 stderr 를 묻고 있어 단서도 없이 `pr.exitCode != 0` 판정 →
  fallback 메시지만 노출.
- 사실상 정상 결과(0건)인데 에러로 처리되는 버그.

### 내역
- `scanSinglePath` 원격 명령어:
  - 기존: `find ... 2>/dev/null`
  - 변경: `find ... || true` — find 의 비치명적 exit 1 을 0 으로 평탄화.
    동시에 `2>/dev/null` 제거 → find stderr 는 SSH stderr 로 올라옴.
- 분기 로직 재구조화:
  - `exit 2/3`: 경로 미존재/읽기 불가 (기존 유지).
  - `exit != 0` (그 외): 진짜 SSH 클라이언트 실패 (auth/network 등) — stderr 그대로 노출.
  - `exit 0`: 성공 처리. stderr 가 비어있지 않으면 `summarizeStderr(...,5)` 로 앞 5줄만
    INFO 로그에 남겨 추후 디버깅 단서 확보.
- 새 헬퍼 `summarizeStderr(stderr, maxLines)`: 빈 줄 제거 후 첫 N 줄을 ` | ` 로 연결,
  잘려나간 줄 수를 ` (외 X줄)` 로 표시.

### 효과
- 다른 유저 디렉토리가 섞여 있는 공유 경로(예: `/tmp`) 스캔이 정상 0건 결과로 마무리.
- 진짜 SSH 실패(인증/네트워크)는 여전히 SSH_ERROR 로 명확히 노출.
- find permission denied 류 stderr 는 로그에 첫 5줄만 INFO 로 남겨 추후 분석 가능.

---

## [2026-05-11] 외부 리소스 의존성 제거 — Chart.js source map 404 + 이모지 폰트 의존 아이콘 제거

**변경 파일:**
- `src/main/resources/static/js/lib/chart.umd.min.js`
- `src/main/resources/templates/analyze.html`
- `src/main/resources/templates/index.html`
- `CHANGELOG.md`

### 변경 의도
- 사내(폐쇄망) 환경에서 분석 페이지의 위험도 아이콘이 빈 박스로 표시되고,
  DevTools 콘솔에 `Failed to load source map: chart.umd.js.map 404` 경고 발생.
- 외부 인터넷 리소스를 일절 로드하지 않도록 전수 점검 + 폰트 의존성 제거.

### 전수 점검 결과
- `<script src=>` / `<link href=>` 의 외부 CDN 참조: **없음** (모두 로컬 정적 리소스).
- Google Fonts / Font Awesome / 외부 JS lib 참조: **없음**.
- 인라인 `data:image/svg+xml` URI 만 사용 (네트워크 요청 없음).
- 유일하게 남아 있던 외부 의존: `chart.umd.min.js` 마지막 줄의
  `//# sourceMappingURL=chart.umd.js.map` 주석 → DevTools 가 .map 파일 자동 요청 → 404.

### 원인 (아이콘 미표시)
- 외부 리소스 문제 아님 — **이모지 폰트 의존**.
- `analyze.html` 의 위험도 표시 `🟠🟡🟢` (Unicode 12, 2019) 등 colored-emoji 코드포인트는
  구형 OS / 사내 표준 PC 의 폰트에 없거나 `.notdef` 글리프로 표시됨.
- `index.html` 의 업로드 큐 / idle 상태 아이콘 `✅❌⏳` 도 emoji-presentation 이라 동일 위험.

### 내역
- `chart.umd.min.js`: 마지막 줄 `//# sourceMappingURL=chart.umd.js.map` 제거.
  (Chart.js v4.4.0 동작에는 영향 없음 — DevTools 디버그용 sourcemap 참조일 뿐.)
- `analyze.html` `_SEV_CONFIG`:
  - `🔴🟠🟡🟢⚪` → **인라인 SVG 원형 (color-coded)**.
  - 새 헬퍼 `_sevSvg(fill, strokeAttr)` 도입 — `width="1em" height="1em"` 로 컨테이너
    `font-size:28~38px` 에 따라 자동 스케일.
  - `iconEl.textContent = sevCfg.icon` → `iconEl.innerHTML = sevCfg.icon`.
  - Unknown 만 outline (회색 stroke + 흰 fill).
- `index.html` 업로드 큐 상태 아이콘 `_UQ_ICON`:
  - `⬆✅❌⊘⏳` → 각각 인라인 SVG (up-arrow / check / X / slashed-circle / clock).
  - Idle 상태(`<div class="queue-idle-icon">✅</div>`) 도 SVG 로 교체.
  - 색상: uploading=#2563EB, done=#10B981, error=#EF4444, cancelled=#9CA3AF, pending=#F59E0B.

### 유지 항목
- `⚠ ✓ ✗ ✕` 같은 text-presentation 기호(Unicode 1.x~4.x)는 일반 폰트 글리프로 안정적
  렌더링되므로 그대로 유지 (files.html / history.html / rag-settings.html / index.html 일부).

### 효과
- 사내망 분석 페이지 위험도 아이콘이 모든 OS / 폰트 환경에서 동일하게 표시.
- DevTools 콘솔의 source map 404 경고 제거.
- 외부 네트워크 의존성 0 — `/etc/hosts` 의 DNS 차단 환경에서도 모든 페이지 정상 렌더.

---

## [2026-05-11] SSH/SCP stderr·로그 한글 깨짐 수정 (UTF-8 명시)

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`
- `restart.sh`
- `CHANGELOG.md`

### 변경 의도
- 사내 시스템(`LANG=C`) 에서 Target Server 스캔 시 실패 로그가
  `stderr=?? ?? ?? ?? (exit 1)` 로 한글이 모두 `?` 로 치환되어 원인 파악 불가.
- 로케일을 `ko_KR.utf8` 로 바꿔도 동일 — JVM 기동 시 결정되는
  `file.encoding` 이 ASCII 인 채로 고정돼 있었기 때문.

### 원인
1. `RemoteDumpService.executeCommand` / `executeCommandWithProgress` 의
   `new InputStreamReader(process.getInputStream())` 가 charset 미지정 →
   JVM 기본 charset (= `file.encoding` 시스템 프로퍼티) 사용.
   `LANG=C` 환경에서 `file.encoding` 은 `US-ASCII` → SSH stderr 의 UTF-8 한글
   바이트가 전부 `?` 로 치환.
2. `restart.sh` 기동 라인에 `-Dfile.encoding=UTF-8` 누락 → 별도 `logback.xml`
   이 없어 Spring Boot 기본 파일 appender 도 JVM 기본 charset 으로 기록.

### 내역
- `RemoteDumpService.java`:
  - `import java.nio.charset.StandardCharsets;` 추가.
  - `new InputStreamReader(process.getInputStream())` 4곳 →
    `new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)`
    (스캔/SSH 호출 + SCP 전송 진행률 모니터링 양쪽).
  - `new InputStreamReader(process.getErrorStream())` 도 동일하게 UTF-8 명시.
- `restart.sh`:
  - 기동 라인에 `-Dfile.encoding=UTF-8` 추가 → 로그 파일이 OS 로케일과 무관하게
    UTF-8 로 기록.

### 효과
- 배포 후 재기동 시 `heapdump-analyzer.log` 의 한글이 정상 표시.
- SSH 인증 실패·find 권한 오류 등 실제 stderr 메시지가 로그에 그대로 남아
  exit 1 류 원인을 추적 가능.

---

## [2026-05-11] Chart.js 사내망 로드 실패 → JAR 내부 정적 리소스로 번들

**변경 파일:**
- `src/main/resources/static/js/lib/chart.umd.min.js` (신규, 204,948 bytes — Chart.js v4.4.0 UMD)
- `src/main/resources/templates/index.html`
- `src/main/resources/templates/history.html`
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- 사내 시스템(폐쇄망)으로 마이그레이션 후 분석 화면 진입 시 브라우저 콘솔에
  `Failed to load resource: net::ERR_NAME_NOT_RESOLVED chart.umd.min.js:1` /
  `Uncaught ReferenceError: Chart is not defined at initCharts` 에러 발생.
- 원인: 세 템플릿(`index.html` / `history.html` / `analyze.html`)이 `https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js`
  CDN 을 직접 참조 → 사내망에서 DNS 해석 실패.

### 내역
- Chart.js v4.4.0 UMD 빌드(`chart.umd.js`, 204,948 bytes, 이미 minified)를 npm tarball 에서 추출해
  `src/main/resources/static/js/lib/chart.umd.min.js` 로 배치 → JAR 내부에 포함되어 외부 의존성 제거.
- 세 템플릿의 `<script src="https://cdn.jsdelivr.net/.../chart.umd.min.js">` →
  `<script src="/js/lib/chart.umd.min.js">` 로 일괄 교체.
- 기존 `static/js/chart.js` 는 프로젝트 차트 설정(라이브러리 아님)이므로 별도 `lib/` 디렉토리에 분리.

### 검증
- `mvn clean package -DskipTests` 빌드 성공, JAR(60.1MB) 내부 `BOOT-INF/classes/static/js/lib/chart.umd.min.js`
  204,948 bytes 확인.
- 기동 후 `GET /js/lib/chart.umd.min.js` → 200 OK, content-type=application/javascript, size=204,948.

## [2026-05-11] run.sh / restart.sh — Ctrl+C 가 자바 앱까지 죽이는 문제 보완

**변경 파일:**
- `run.sh`
- `restart.sh`
- `CHANGELOG.md`

### 변경 의도
- `bash run.sh` 또는 `bash restart.sh` 실행 후 로그 스트리밍 중 Ctrl+C 를 누르면
  `[Shutdown] Application is shutting down (signal received)` 가 nohup.out 에 찍히며 앱이 같이 종료되는 문제.
- 원인: 비대화식 셸 스크립트는 job control 이 꺼져 있어 `nohup ... &` 만으로는
  자바 프로세스가 셸과 **같은 PGID** 를 공유 → 터미널 Ctrl+C(SIGINT) 가 PGID 전체에 전달되어
  자바 앱의 shutdown hook 까지 발동. `nohup` 은 SIGHUP 만 무시하므로 SIGINT 는 막지 못함.

### 내역
- **`setsid nohup ... < /dev/null > ... 2>&1 &`** — `setsid` 로 자바 프로세스를 새 session/PGID 에 분리.
  터미널과의 연결이 끊겨 Ctrl+C 가 더 이상 자바 앱에 도달하지 않음. `disown` 으로 셸 job list 에서도 제거.
- **trap 핸들러 개선** — `INT/TERM` 발생 시 `INTERRUPTED=1` 플래그 + tail 만 종료 후 루프 탈출.
  종료 시 "로그 스트리밍 중단. 앱은 계속 실행 중 (PID=N)" 메시지 출력 후 `exit 0`.
- **부팅 감지 패턴 확장** — grep 패턴에 `[Shutdown] Application is shutting down` 포함.
  setsid 가 적용된 정상 환경에서는 발생하지 않지만, 외부 요인(다른 터미널의 `bash stop.sh`, OOM kill 등)으로
  기동 도중 죽는 경우를 빠르게 감지/메시지 출력.
- **프로세스 사망 즉시 감지** — 루프마다 `kill -0 $PID` 로 PID 존재 확인. 사라지면 즉시 break.
- **최종 상태 메시지 세분화** — 정상 기동 / FAILED TO START / 종료 신호 수신 / 프로세스 사망 / 타임아웃 5가지 케이스 분리.

## [2026-05-08] Target Servers — SCP 전송 진행률 실시간 표시 (MB 단위 + 진행바)

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`
- `src/main/java/com/heapdump/analyzer/controller/ServerController.java`
- `src/main/resources/templates/servers.html`
- `CHANGELOG.md`

### 변경 의도
- 기존: 스캔 후 전송 시 단순 펄스 애니메이션만 표시 — 실제 진행률·잔여 시간 추정 불가.
- 큰 덤프(수백 MB~수 GB) 전송 시 사용자가 "멈춘 게 아닌가" 의심.

### 내역
- **`RemoteDumpService`**:
  - `TransferProgressListener` 함수형 인터페이스 추가 (`onProgress(bytes, total)`).
  - `transferFile(server, remotePath, listener)` 오버로드 — 기존 `transferFile(server, remotePath)`는 listener=null로 위임 (자동 탐지 호환).
  - `fetchRemoteFileSize()`: SCP 시작 전 `ssh stat -c %s` 또는 `wc -c`로 원격 총 크기 확보. 실패 시 -1 (총량 미상으로 표시).
  - `executeCommandWithProgress()`: SCP 프로세스 실행 + daemon 모니터 스레드가 500ms마다 `tempFile.length()`로 진행률 보고.
- **`ServerController`** — `GET /api/servers/{id}/transfer/stream?remotePath=...` SSE 추가:
  - `progress` 이벤트: `{bytes, total}` (총량 미상이면 total=-1).
  - `done` 이벤트: `{success, filename, status, fileSize, message?}`.
  - 기존 POST `/api/servers/{id}/transfer`는 자동 탐지/외부 호환을 위해 유지.
- **`servers.html`** — 전송 UI 개편:
  - `transferFile()`: `EventSource` 기반으로 재작성. 시작 시 indeterminate 펄스 → progress 첫 수신 시 실제 % 채움.
  - 라벨: `123.4 / 567.8 MB (21.7%)` 형식. 1024 MB 초과 시 `2.34 GB`로 자동 단위.
  - 진행바 폭 80→140px, 라벨 폭 확장(min-width 260px), `font-variant-numeric: tabular-nums`로 자릿수 흔들림 방지.
  - `done` 후 EventSource 자동 재연결 차단 (`doneReceived` 플래그로 onerror 분기).
  - 완료 시 라벨에 최종 파일 크기 표시 ("완료 (174.0 MB)").

## [2026-05-08] Target Servers — 스캔 "전송됨" 판정에 로컬 파일 실존 검증 추가 (버그 수정)

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/repository/DumpTransferLogRepository.java`
- `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`
- `CHANGELOG.md`

### 문제
- JeusServer1 스캔 시 `jeus_admin.hprof`가 "전송됨"으로 표시되지만 Files 페이지에는 보이지 않음.
- 원인: `scanSinglePath()`가 `transferred` 플래그를 DB의 SUCCESS 로그 존재만으로 판정. 로컬 `dumpfiles/`에서 파일이 삭제된 후 재스캔 시 여전히 "전송됨"으로 잘못 표시됨.
- CLAUDE.md 명세: **전송됨 판정 = DB SUCCESS 로그 + 로컬 파일 실존(`.gz` 포함) 모두**.

### 내역
- `DumpTransferLogRepository`: `findByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(...)` 추가 — 동일 원격 파일에 여러 SUCCESS row가 있을 때(예: `_2`, `_3` suffix 재전송) 모두 후보로 평가하기 위함.
- `RemoteDumpService.scanSinglePath()`:
  - 매칭되는 모든 SUCCESS 로그를 최신순으로 가져온 뒤, 각 row의 로컬 저장명에 대해 `dumpfiles/<filename>` 또는 `dumpfiles/<filename>.gz` 실존 여부를 검사.
  - 실존하는 row가 있을 때만 `transferred=true`, 그 row의 `filename`으로 `analyzed` 판정.
  - 모든 row의 로컬 파일이 사라졌으면 `transferred=false` (분석 가능 상태로 다시 노출).

## [2026-05-07] AI Chat — 분석 삭제 / 동일 파일명 재업로드 시 채팅 세션 분리 및 상태 표시

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/model/entity/AiChatSession.java`
- `src/main/java/com/heapdump/analyzer/repository/AnalysisHistoryRepository.java`
- `src/main/java/com/heapdump/analyzer/controller/AiChatController.java`
- `src/main/resources/templates/ai-chat.html`
- `CHANGELOG.md`

### 변경 의도
- 기존: `AiChatSession`이 `filename`만 저장 → 분석 결과 삭제 후 동일 이름으로 새 분석을 시작해도 사이드바에서 이전 채팅과 새 채팅이 같은 분석으로 합쳐져 보이고, 분석이 삭제됐는지 사용자에게 표시되지 않음.
- 분석명 형식이 `[EXT]server_filename_yyyyMMdd`로 분석 시각(analyzedAt) 기준 일자가 다르므로, 채팅도 분석 인스턴스 단위로 분리되어야 함.

### 내역

**AiChatSession 엔티티 — `analyzedAt` 컬럼 추가**
- `@Column(name = "analyzed_at") LocalDateTime analyzedAt` 신규 필드. 세션 생성 시점의 `analysis_history.analyzed_at` 스냅샷을 저장.
- `(filename, analyzedAt)` 조합으로 분석 인스턴스 식별 — 동일 파일명이라도 분석이 새로 이뤄지면 다른 세션으로 자연 분리.
- 기존 row는 NULL로 남으며 마이그레이션 호환 유지(`ddl-auto=update`로 컬럼 자동 추가됨, datetime(6) NULL).

**AnalysisHistoryRepository — `existsByFilenameAndAnalyzedAt` 추가**
- 사이드바 렌더링 시 세션의 `analyzedAt`이 현재 분석과 일치하는지 빠르게 확인.

**AiChatController — `createSession` / `listSessions` 변경**
- `createSession`: `filename`이 있으면 `historyRepo.findByFilename(...)` 으로 분석 entity 조회 후 그 시점의 `analyzedAt`을 세션에 저장. analyze.html의 `ensureChatSession()`은 변경 불필요(filename만 보내면 백엔드가 자동 매칭).
- `listSessions`: 응답에 `analyzedAt` + `analysisState` 필드 추가. 상태 판정:
  - `currentAnalysis` — `(filename, analyzedAt)`이 `analysis_history`에 그대로 존재 (정상 연결)
  - `previousAnalysis` — 동일 filename이 존재하지만 `analyzedAt`이 다름 (재업로드/재분석된 이전 분석의 채팅)
  - `analysisDeleted` — 해당 filename의 분석 row가 아예 없음 (분석 기록 삭제됨)
  - `general` — `filename` 없는 일반 채팅
- 마이그레이션 호환: 세션의 `analyzedAt`이 NULL이면 filename 존재 여부만으로 `currentAnalysis` / `analysisDeleted` 판정.
- `historyRepo` 의존성 주입 추가.

**ai-chat.html — 사이드바 분석 상태 배지 + 분석 시각 표시**
- 신규 CSS:
  - `.session-item-state.deleted` — 빨간색 "분석 삭제됨" 배지
  - `.session-item-state.previous` — 노란색 "이전 분석" 배지
  - `.session-item.is-deleted` — 제목 회색 + 취소선, 파일 칩 흐림 처리
  - `.session-item-analyzed` — `분석 yyyy-MM-dd HH:mm` 보조 라벨
- `renderSessionList()` 변경: `s.analysisState`에 따라 배지/클래스 추가, `s.analyzedAt`을 세션 카드에 노출.

### 동작 시나리오
1. `jeus_admin.hprof` 분석 → 채팅 (세션 A: `analyzedAt=T1`)
2. 분석 결과 삭제(`/api/history/bulk-delete`) → `analysis_history`에서 row 삭제, 세션 A는 보존됨
3. 사이드바: 세션 A → "**분석 삭제됨**" 빨간 배지, 제목 취소선 (채팅 본문은 여전히 열람 가능)
4. 동일 이름 `jeus_admin.hprof` 재업로드/재분석 (analyzedAt=T2) → 새 채팅 시작 시 세션 B 생성 (`analyzedAt=T2`)
5. 사이드바: 세션 B는 "현재 분석"(배지 없음), 세션 A는 동일 filename이지만 `analyzedAt` 불일치 → "**이전 분석**" 노란 배지로 분리 표시

### 검증
- `mvn clean package -DskipTests` 빌드 성공.
- `bash restart.sh` 재기동 후 `Started HeapAnalyzerApplication in 9.141 seconds` 확인.
- `DESCRIBE ai_chat_sessions;` → `analyzed_at datetime(6) YES NULL` 컬럼 자동 추가 확인.
- `/api/ai-chat/sessions` 미인증 요청 → 302 (정상 로그인 리다이렉트).

---

## [2026-05-07] Analyze — RAW DATA 클릭 시 부모 페이지 이탈 방지 (강한 방어 처방)

**변경 파일:**
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- 이전 fix(iframe height 고정) 후에도 RAW DATA 항목 클릭 시 브라우저 주소가 `/report/{filename}/mat-page/overview/index.html`로 완전히 이동(top-level navigation)하는 증상이 재현된다는 사용자 보고.
- 코드 흐름상 `<button>` + `onclick="showPanel(...)"`은 `iframe.src`만 설정하므로 부모 URL이 바뀔 수 없음. 하지만 사용자 환경에서는 실제로 발생 (URL 바 변경 + 사이드바·헤더 사라짐 + 뒤로가기로 복귀). 외부 도메인(`park1v.mooo.com`) 접근, 브라우저 확장, 또는 인라인 onclick 처리의 미묘한 케이스가 의심됨.
- 원인 격리가 어려워 **방어적 처방** 적용: 어떤 원인이든 click 이벤트 시점에 부모 네비게이션을 차단.

### 내역

**사이드바 nav-item — 인라인 onclick 제거 + 위임 핸들러 + `type="button"`**
- 모든 `<button class="nav-item">` (Analysis / AI Analysis / Actions / Tools / Raw Data 5개 섹션, 총 15개)에 `type="button"` 명시 추가 — HTML 사양상 form 외부 button의 default type이 모호한 회색지대 차단.
- 인라인 `onclick="showPanel('...', this)"` → `data-panel="..."`로 교체. 부수 호출(loadLog, exportCSV 등)은 `data-extra`/`data-action`으로 분리.
- 신규 위임된 click 리스너:
  ```js
  document.addEventListener('click', function(e) {
      var btn = e.target.closest('.nav-item[data-panel], .nav-item[data-action]');
      if (!btn) return;
      e.preventDefault();
      e.stopPropagation();
      ...
      try {
          if (panel) showPanel(panel, btn);
          if (extra && typeof window[extra] === 'function') window[extra]();
          if (action && typeof window[action] === 'function') window[action]();
      } catch (err) { console.error('[nav-item handler]', err); }
      return false;
  }, false);
  ```
- 핵심: **`preventDefault()` + `stopPropagation()` 즉시 호출** — 인라인 onclick에선 못 잡는 click의 default behavior나 중간 단계 가로채기 가능성을 사전 차단.
- 배너 Analysis 탭의 클론 버튼도 같은 위임 핸들러로 자동 처리 (개별 onclick 등록 불필요).

### 동작 검증
- 빌드/기동: `mvn clean package -DskipTests` + `bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.394 seconds` 확인.
- 데스크톱 Chrome 외부 도메인 접근 환경에서 RAW DATA 항목 클릭 시 부모 URL 유지 + 패널 전환 동작 검증 필요 (사용자 측 재테스트 요청).
- 만약 이번 fix 후에도 동일 증상이라면 사용자 환경(브라우저 확장 / 프록시 / Chrome flag) 측 원인일 가능성이 매우 높음.

---

## [2026-05-07] Analyze — RAW DATA iframe 동적 height 제거 (전체 화면 전환 버그 수정)

**변경 파일:**
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- RAW DATA 섹션의 System Overview / Top Components / Suspect Details 클릭 시 iframe이 정상 동작하지 않고 페이지가 "전체 화면 전환"처럼 보이는 사용자 보고.
- 원인: 기존 코드가 iframe `style.height`를 매 load(초기 + 내부 네비게이션)마다 `Math.max(700, doc.body.scrollHeight + 40)`로 키워, 큰 MAT 리포트(예: Top Components 5000+px)에선 iframe이 콘텐츠 전체 세로를 점유 → 부모 페이지가 거대 단일 스크롤이 되며 사이드바 바깥의 시각 영역을 iframe이 모두 덮음. 모바일(`.main-content { margin-left: 0 }`)에선 가로폭까지 차지해 실제로 화면 전체가 iframe 콘텐츠로 보임.
- 부수 원인: `activateClassLinksInIframe`가 매 load 콜백 안에서 또 다른 `iframe.addEventListener('load', ...)`를 추가 → 내부 네비게이션할 때마다 핸들러 누적 (1, 2, 3, ... N+1번 호출). 메모리 누수 + 중복 height 계산.

### 내역

**iframe 인라인 스타일 — 고정 viewport 높이 + 내부 스크롤**
- 3개 MAT iframe(`matOverviewIframe` / `matTopIframe` / `matSuspectsIframe`)의 inline style:
  - `min-height:700px` → `height:75vh; min-height:500px; display:block`
- 콘텐츠가 길면 iframe 내부에서 스크롤되도록 (브라우저 기본 `scrolling=auto`).

**JS — load 핸들러 단일 등록 + 동적 height 제거**
- `showPanel()` 내 iframe lazy-load 블록:
  - `iframe.style.height = ...` 제거 (3곳: 초기 load + click 후 setTimeout + 내부 네비 후).
  - `iframe.contentWindow.addEventListener('click', ...)` 의 height 재계산 setTimeout 제거.
  - `iframe.src = data-src` 호출은 load 리스너 등록 *이후*로 이동 (race 방지).
- `activateClassLinksInIframe(doc, iframe)` → `activateClassLinksInIframe(doc)` (시그니처 단순화):
  - 함수 내부의 추가 `iframe.addEventListener('load', ...)` 등록 코드 삭제 — 호출자(showPanel)의 단일 load 리스너가 매번 호출하므로 중복 불필요.
  - 내부 `function activate(targetDoc) {...}; activate(doc)` 헬퍼 wrap 풀고 인라인화.

### 동작 검증
- `mvn clean package -DskipTests` + `bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.432 seconds` 확인.
- `/report/{filename}/mat-page/overview/index.html` curl 200 OK, 헤더에 `X-Frame-Options: SAMEORIGIN` 정상.
- Raw Data 클릭 시 iframe이 75vh 높이로 카드 내부에 안정적으로 표시되며, MAT 리포트의 긴 콘텐츠는 iframe 내부 스크롤로 탐색 가능. 부모 페이지는 정상 길이 유지.
- 클래스명 링크(FQCN 형태)는 매 내부 네비 후에도 활성화 동작 유지 (load 리스너 단일 등록으로 자동 재실행).

---

## [2026-05-07] Analyze — 재분석 확인 모달 한국어 번역

**변경 파일:**
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- `/analyze/{filename}` 페이지의 재분석 확인 모달이 영어로 노출되어 다른 모달(컴포넌트 상세, 새 대화 시작 등)과 언어 일관성이 어긋남.

### 내역
- 모달 타이틀: `Re-Analyze` → `재분석`
- 본문: `Saved results will be cleared and the heap dump will be re-analyzed with MAT CLI. This may take several minutes.` → `저장된 분석 결과가 삭제되고 MAT CLI로 힙 덤프를 다시 분석합니다. 수 분이 소요될 수 있습니다.`
- 메타 라벨: `File Size:` → `파일 크기:`, `Analysis Time:` → `분석 시간:`
- 버튼: `Cancel` → `취소`, `Re-Analyze` → `재분석`
- 마크업 코멘트도 `Re-Analyze Confirm Modal` → `재분석 확인 모달`로 통일.

### 동작 검증
- 헤더의 `Re-Analyze` 트리거 버튼은 사용자 요청 범위(모달만)에 한정해 영어 유지 — 기존 호출자 onclick 핸들러 변경 없음.
- 빌드/기동: `mvn clean package -DskipTests` + `bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.074 seconds` 확인.

---

## [2026-05-07] Progress — 좌/우 카드 높이 일치, 자동이동 5초화 + 머무르기 버튼

**변경 파일:**
- `src/main/resources/templates/progress.html`
- `CHANGELOG.md`

### 변경 의도
- "MAT CLI 실행 중..." 진행 카드(좌)와 "MAT CLI 로그" 카드(우)의 비주얼 하단이 어긋남 — 사용자 보고.
- 원인: `.progress-card`에만 `margin-bottom: 20px`가 있어 grid `align-items: stretch` 환경에서 우측 카드보다 시각적 하단이 20px 위로 어긋남. grid `gap: 20px`가 이미 카드 간 간격을 처리하므로 margin-bottom은 중복 + 불일치 원인.
- 자동 이동 3초가 다소 짧고, 결과를 천천히 보고 싶어도 강제 이동되어 사용자 의도 침해.

### 내역

**좌/우 카드 높이 일치**
- `.progress-card`의 `margin-bottom: 20px` 제거 (grid `gap: 20px`가 spacing 담당). 모바일 1열 폴백에서도 grid gap이 동일하게 적용됨.

**자동 이동 5초화 + 머무르기 버튼**
- `showComplete()`의 `DELAY = 3000` → `5000`. 초기 안내문 "3초" → "5초".
- 완료 배너에 `<button class="complete-btn-stay" id="stayBtn">머무르기</button>` 추가 (결과 보기 버튼 우측, 투명 배경 + 흰색 테두리 ghost 스타일).
- `cancelAutoRedirect()` 핸들러: setInterval 정리, 카운트다운 바 현재 폭 고정 후 숨김, 안내문 "자동 이동이 중지되었습니다. 결과 보기 버튼으로 이동하세요."로 교체, 머무르기 버튼 자체를 숨김.
- `_autoRedirectTimer` / `_autoRedirectCancelled` 모듈 변수로 timer 핸들 보관 — 외부에서 취소 가능.
- 카운트다운 setInterval 내부에서도 `_autoRedirectCancelled` 가드 — race condition 방지.

**스타일**
- `.complete-btn-stay` 추가: `background: transparent; color: #fff; border: 1px solid rgba(255,255,255,.6); padding: 8px 18px; font-size: 13px; border-radius: 8px; flex-shrink: 0;` + hover 시 `background: rgba(255,255,255,.15)`.
- 데스크톱(≥1024px) / 모바일(≤480px) 미디어쿼리에 `.complete-btn-stay` 사이즈 동기화.

### 동작 검증
- 데스크톱: 좌/우 카드 하단 정렬 일치. 완료 시 "5초 후 자동 이동" 안내 + 결과 보기 / 머무르기 두 버튼 가로 배치.
- 머무르기 클릭: 카운트다운 바 진행 멈춤 + 숨김, 안내문 변경, 머무르기 버튼 사라짐. 결과 보기 버튼만 남아 사용자가 원할 때 이동 가능.
- 빌드/기동: `mvn clean package -DskipTests` + `bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.346 seconds` 확인.

---

## [2026-05-07] Progress — 완료 배너 상단 이동 + 슬림화, 푸터 중앙정렬 보정

**변경 파일:**
- `src/main/resources/templates/progress.html`
- `CHANGELOG.md`

### 변경 의도
- 분석 완료 시 페이지 하단에 큰 완료 배너(48px 폭죽 이모지 + 32px 패딩 + 22px 제목 + 큰 버튼)가 출력되어 시선 이동이 길고 영역 점유 과다.
- 푸터 텍스트가 `text-align:center`만 적용되어 viewport 기준 중앙 → 좌측 고정 배너(220px) 폭만큼 시각적으로 우측 치우침.
- 푸터 버전 표기가 `v2.0.0`으로 pom 버전(`v2.0.1`)과 불일치.

### 내역

**완료 배너 — 상단 이동 + 슬림 한 줄 레이아웃**
- 마크업 위치: `.progress-grid` 아래(페이지 하단) → `.progress-grid` 위(상단)로 이동.
- 마크업 구조 변경: 세로 적층(아이콘/제목/부제/버튼/카운트다운) → 한 줄 flex 레이아웃 (`.complete-info` flex:1 + `.complete-btn` 우측 고정).
- 폭죽 이모지(`&#127881;`, `.complete-icon`) 제거.
- 패딩 32px → `12px 18px`, 제목 22px → 15px, 부제 14px → 12px, 버튼 `12px 32px / 15px` → `8px 18px / 13px`, countdown-bar 4px → 3px, countdown-text 13px → 11px.
- 데스크톱(≥1024px) / 모바일(≤480px) 미디어쿼리도 슬림 사이즈에 맞춰 재정의 (이전 26px 제목/16px 부제 강제 키움 제거).
- 모바일에서는 `flex-wrap: wrap`로 좁은 폭에서 버튼이 텍스트 아래로 떨어지도록.

**푸터 중앙정렬 보정**
- `.progress-footer`에 `padding-left: calc(var(--banner-w, 220px) + 20px)` 추가 — 좌측 고정 배너(220px ↔ 44px 토글) 폭만큼 패딩을 주어 콘텐츠 영역 기준 중앙에 위치.
- `transition: padding-left .25s` — 배너 토글과 동기화된 부드러운 이동.
- ≤900px 미디어쿼리에서 `padding-left: 20px` 리셋 (모바일은 배너 숨김).

**버전 표기 갱신**
- 푸터 `v2.0.0` → `v2.0.1` (pom.xml과 일치).

### 동작 검증
- 데스크톱(>1024px): 분석 완료 시 진행 카드 위에 슬림 배너(약 70px 높이) 가로 한 줄로 표시 — 제목/부제/카운트다운(3px 바 + 안내문) 좌측, "결과 보기" 버튼 우측. 푸터는 콘텐츠 영역 정중앙.
- 태블릿(900~1024px): 동일 슬림 레이아웃 유지.
- 모바일(≤480px): 배너 텍스트 + 버튼 wrap, 푸터 padding-left 0으로 viewport 중앙.
- 빌드/기동: `mvn clean package -DskipTests` + `bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.524 seconds` 확인.

---

## [2026-05-07] Dashboard — 모바일 가로 오버플로우 수정 (Detections / 탐지 현황 패널)

**변경 파일:**
- `src/main/resources/templates/index.html`
- `CHANGELOG.md`

### 변경 의도
- 모바일 화면(특히 ≤480px)에서 Detections 패널과 탐지 현황 패널이 메인 영역(`.main-content`) 우측을 약간 넘어가는 현상 발생.
- 원인: CSS Grid 자식 요소의 기본 `min-width: auto` 동작 — `.panel` 안에 `nowrap`/`flex-shrink: 0`인 행(detect-item의 칩 묶음 등)이 있으면 자식이 자기 콘텐츠 폭을 강제하여 1fr 트랙이 부풀고, 결과적으로 `.grid2`의 자식 패널 폭이 부모 컨테이너를 초과.

### 내역

**Grid 자식 가로 확장 방지 (전역 처방)**
- `.panel { min-width: 0; }` 추가 — Grid 자식의 기본 `min-width: auto` 우회. 핵심 처방.
- `.detect-summary`, `.det-kpi`의 `grid-template-columns`를 `repeat(N, 1fr)` → `repeat(N, minmax(0, 1fr))` 로 변경 — 트랙이 자식 콘텐츠 때문에 부풀지 않도록 강제.
- `.detect-card`, `.det-kpi-card`에 `min-width: 0` 추가 (이중 안전망).
- `.detect-item`에 `min-width: 0` 추가.
- `.detect-sevs`의 `flex-shrink: 0` 제거 + `flex-wrap: wrap` 추가 — 칩이 많을 때 행을 부풀리지 않고 줄바꿈.

**모바일 ≤480px 미디어쿼리 보강**
- `.detect-item { flex-wrap: wrap; gap: 6px 10px; }` + `.detect-fname { flex-basis: 100%; }` — 좁은 폭에서 파일명을 한 줄 전체로, 건수/칩/배지를 두 번째 줄로 자연 wrap.
- `.detect-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }` (기존 `repeat(2, 1fr)`을 minmax 형태로 통일).
- `.det-kpi { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }` + `.det-kpi-card` padding/폰트 축소 (`padding: 8px 10px`, `det-kpi-val` 18px → 16px) — 폭이 좁아도 3컬럼 KPI 카드가 들어가도록.
- `.main-content { overflow-x: hidden; }` 안전망 — 어떤 자식이 넘쳐도 가로 스크롤은 발생하지 않도록.

### 동작 검증
- 데스크톱: 변화 없음. detect-item 한 줄에 파일명 + 칩 + 배지 표시 그대로.
- 태블릿 (480~900px): grid 트랙이 부모 폭에 정확히 맞춰지고, 칩이 많을 때 detect-sevs가 자연 wrap.
- 모바일 (≤480px): 파일명 한 줄, 칩/배지가 두 번째 줄. Detections 패널 KPI 3카드는 padding/폰트 축소로 좁은 폭에 무리 없이 표시.

---

## [2026-05-07] PDF Report — 분석 페이지 내부 패널로 통합 (별도 페이지 → showPanel)

**변경 파일:**
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- 직전까지 PDF Report 메뉴는 `/analyze/{filename}/print-preview` 별도 페이지로 이동 → **좌측 글로벌 배너와 Heap Statistics 사이드바가 사라져 분석 컨텍스트 단절**.
- 다른 분석 메뉴(Overview/Top Consumers/AI 인사이트/MAT Reports 등)는 모두 `showPanel(name, this)`로 같은 페이지 내 패널 전환 패턴 → PDF Report만 다른 동작이라 일관성 깨짐.
- PDF Report도 동일한 패널 전환 방식으로 통합하여 **배너 + Heap Statistics + 분석 사이드바 컨텍스트가 그대로 유지되는 상태에서 PDF 미리보기**가 보이도록 변경.

### 내역

**메뉴 onclick 변경**
- 기존: `onclick="if(typeof FILENAME...) location.href='/analyze/'+...+'/print-preview';"` (별도 페이지 이동)
- 변경: `onclick="showPanel('pdf-report',this)"` (동일 페이지 패널 전환)

**`<div id="panel-pdf-report" class="panel">` 신규 (LOG 패널 직전 위치)**
- 카드 헤더: "PDF Report Preview" 타이틀 + 우측 [PDF 다운로드] 버튼 (파란색 primary).
- 본문: `<iframe id="pdfReportIframe">` (lazy-load, `min-height: 80vh`, 어두운 배경 #525659).
- Fallback: `<div id="pdfReportFallback">` 일부 브라우저에서 PDF 인라인 미지원 시 4초 후 노출.

**`loadPdfReportPanel()` lazy-load 함수**
- `showPanel('pdf-report', ...)` 진입 시 호출. iframe `src`가 비어있을 때만 1회 설정.
- viewport 폭 분기 (`window.matchMedia('(max-width: 900px)')`):
  - 데스크톱 → `/print-pdf?mode=inline` (브라우저 PDF 인라인 뷰어, mat-overview/top/suspects와 동일 lazy 패턴)
  - 모바일 → `/print-html` (HTML 렌더, iOS Safari/Android Chrome 의 PDF 인라인 한계 회피)
- 다운로드 버튼 `download` 속성을 baseName 기반으로 동적 설정 (`{base}-report.pdf`). 확장자 정규식 `\.(hprof|bin|dump)(\.gz)?$/i` 제거.
- PDF 모드에서만 4초 fallback 타이머 (HTML은 항상 정상 로드).

**유지된 라우트 (deep-link 호환)**
- `/analyze/{filename}/print-preview` (별도 미리보기 페이지) — 메뉴에서는 더 이상 호출 안 되지만 직접 URL 입력/북마크 호환을 위해 유지.
- `/analyze/{filename}/print-html`, `/analyze/{filename}/print-pdf?mode={inline|download}` — 패널 iframe과 다운로드에서 사용.

### 동작 검증
- 데스크톱: 좌측 배너(220px) + Heap Statistics 사이드바(300px) + 메인 영역에 PDF iframe 표시. 다른 메뉴 클릭 시 일반 패널 전환.
- 모바일: 햄버거 메뉴 사이드바 + 메인 영역에 HTML 미리보기 (반응형 @media screen 적용).
- 다운로드 버튼: `{base}-report.pdf` 파일명으로 attachment 다운로드.

---

## [2026-05-07] PDF Report — Preview 페이지 풀스크린화 (좌측 배너 미노출)

**변경 파일:**
- `src/main/resources/templates/analyze-print-preview.html`
- `CHANGELOG.md`

### 변경 의도
- 미리보기 페이지에서 좌측 220px 배너가 차지하면서 PDF 가시 영역이 좁아짐. PDF Preview는 단일 목적(미리보기 → 다운로드 결정) 페이지이므로 다른 메뉴로의 좌측 네비게이션 불필요.
- 풀 viewport 폭에서 PDF 콘텐츠를 더 크게 표시하여 가독성 확보.

### 내역
- `<div th:replace="fragments/banner :: banner"></div>` 제거 (페이지 진입 시 배너 미노출).
- `.topbar`/`.preview-wrap` 의 `left: var(--banner-w, 220px)` → `left: 0` 으로 변경.
- `:root` 변수에서 `--banner-w` 제거 (해당 페이지에서 미사용).
- 모바일 미디어쿼리의 `left: 0` 중복 규칙 제거 (이제 데스크톱 기본값과 동일).
- 네비게이션은 기존 topbar의 "분석으로 돌아가기" 버튼 + 모바일 하단 sticky 바의 [돌아가기] 버튼으로 유지.

### 영향 범위
- 다른 페이지(분석/Files/History 등)의 배너 동작은 무영향.
- PDF 다운로드/HTML 미리보기 콘텐츠 자체는 변경 없음 (레이아웃 컨테이너만 풀 폭으로).

---

## [2026-05-07] PDF Report — 모바일 미리보기 대응 (HTML iframe + 하단 sticky 액션 바)

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/service/PdfReportService.java`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- `src/main/resources/templates/analyze-print.html`
- `src/main/resources/templates/analyze-print-preview.html`
- `CHANGELOG.md`

### 변경 의도
- 직전 버전의 PDF 미리보기는 iframe 안에서 `/print-pdf?mode=inline`을 표시 → iOS Safari/Android Chrome 등 모바일 브라우저가 PDF 인라인 뷰를 거의 지원하지 않아 첫 페이지만 보이거나 다운로드가 강제되는 문제.
- A4 1페이지 레이아웃을 모바일 화면(폭 375px)에 그대로 펼치면 폰트 6~7pt가 그대로 작게 표시되어 가독성이 사실상 0.
- 모바일에서는 PDF 인라인 대신 **HTML 렌더 + @media screen 반응형 규칙**으로 미리보기를 제공하고, 다운로드 버튼은 thumb-friendly 한 하단 sticky 바로 분리.

### 내역

**서비스 — `PdfReportService.buildPrintModel(filename, result)` 공용 모델 빌더 추출**
- 기존 `renderPrintPdf(...)`의 `Context.setVariable(...)` 셋업 로직을 `Map<String,Object>` 반환 메서드로 추출.
- `renderPrintPdf`은 `buildPrintModel(...).forEach(ctx::setVariable)`로 단순화.
- 컨트롤러의 새 `/print-html` 라우트가 같은 모델을 `model.addAttribute(...)`로 펼쳐 PDF와 동일한 데이터 표시.

**컨트롤러 — `GET /analyze/{filename}/print-html` 신규**
- `analyze-print` Thymeleaf 템플릿을 HTML 그대로 반환 (PDF 변환 없음 → 서버 부하 0).
- 캐시 결과 없거나 실패 상태면 `/analyze/result/{filename}`으로 302.
- 모바일 미리보기 iframe에서만 사용. 데스크톱은 기존 `/print-pdf?mode=inline` 그대로.

**`analyze-print.html` — `@media screen and (max-width: 900px)` 반응형 추가**
- OpenHTMLtoPDF는 기본 print 미디어를 사용 → screen 미디어쿼리는 PDF 렌더에 영향 없음. 다운로드 PDF는 A4 1페이지 그대로 유지.
- 화면 모드(모바일 미리보기) 적용 사항:
  - `.report` 폭 100%, 높이 auto, mm 고정 해제 → 가로 스크롤 제거.
  - `.hdr` table → block 으로 stack (제목/메타 세로 배치).
  - `.kpi-grid` table → flex-wrap, KPI 카드 2열 3행 그리드 (3x2 → 2x3).
  - `table.tt` 인라인 mm 폭 무효화 + `td.cls` 줄바꿈 허용 (`white-space: normal`, `word-break: break-all`).
  - 폰트 9pt → 14px 베이스, KPI/섹션/Suspect/AI 박스 모두 px 단위로 재조정해 가독성 확보.
  - `.ftr` `position: absolute` → `static` (높이 고정 해제로 자연스럽게 흐름).

**`analyze-print-preview.html` — 모바일 분기 + 하단 sticky 바**
- `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">` 추가 (notch 안전 영역 인식).
- iframe `src`를 페이지 진입 시 `window.matchMedia('(max-width: 900px)')`로 분기:
  - 데스크톱 → `/print-pdf?mode=inline` (기존 PDF 인라인)
  - 모바일 → `/print-html` (새 HTML 라우트)
  - 4초 fallback 타이머는 PDF 모드에서만 동작 (HTML은 항상 정상 로드).
  - 브레이크포인트 교차 시 `change` 이벤트로 src 재로드 (Safari < 14는 `addListener` 폴백).
- 모바일에서 상단 `topbar-right` 숨김 → 하단 `mobile-action-bar` (fixed bottom, `safe-area-inset-bottom` 패딩) 노출. [돌아가기] [PDF 다운로드] 두 버튼 (다운로드 flex 1.4로 더 넓게).
- `preview-wrap` 모바일에서 `bottom: var(--mobile-bar-h, 60px)`로 액션 바 자리 확보, 배경을 `#525659` → `#fff`로 전환 (HTML 렌더에 어울리게).

### 동작 검증
- 데스크톱: 기존 PDF iframe 그대로. 다운로드 버튼 정상.
- 모바일 (≤900px): `/print-html` HTML 렌더 → KPI 2열, 폰트 가독, 가로 스크롤 없음. 하단 [돌아가기]/[PDF 다운로드] 버튼.
- PDF 다운로드 결과: 기존과 동일 A4 1페이지 (screen 미디어 무영향 확인).

---

## [2026-05-07] PDF Report — 미리보기 페이지 추가 (직접 다운로드 → 미리보기 + 다운로드 버튼)

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- `src/main/resources/templates/analyze-print-preview.html` (신규)
- `src/main/resources/templates/analyze.html`
- `CHANGELOG.md`

### 변경 의도
- 직전 버전에서 PDF Report 버튼 클릭 시 즉시 다운로드 → 사용자는 결과를 미리 확인하지 못한 채 파일을 받음.
- 미리보기 페이지에서 PDF 내용을 시각적으로 확인한 뒤 다운로드 여부를 선택할 수 있도록 변경.

### 내역

**새 라우트 / 엔드포인트 분기**
- `GET /analyze/{filename}/print-preview` (신규) — Thymeleaf 미리보기 페이지 반환. 캐시 결과 없으면 `/analyze/result/{filename}` 으로 302 리다이렉트.
- `GET /analyze/{filename}/print-pdf` 에 `?mode=inline|download` 쿼리 파라미터 추가:
  - `mode=download` (기본) → `Content-Disposition: attachment` (다운로드)
  - `mode=inline` → `Content-Disposition: inline` (iframe 표시용)

**미리보기 페이지 (analyze-print-preview.html)**
- 글로벌 배너 (`fragments/banner :: banner`) 그대로 적용 (좌측 220px ↔ 44px 토글, `--banner-w` 변수 일관).
- 상단 fixed topbar (52px): 좌측 빨간색 PDF 아이콘 + "PDF Report Preview" / 파일명 / 우측 버튼 2개.
  - "분석으로 돌아가기" → `/analyze/result/{filename}` 일반 anchor.
  - "PDF 다운로드" (primary blue) → `/analyze/{filename}/print-pdf?mode=download` + `download` 속성.
- 본문 영역 (topbar 아래 ~ viewport 끝): 어두운 배경(`#525659`) + `<iframe>` 100% × 100%로 PDF 인라인 표시. 브라우저 내장 PDF 뷰어가 자동 사용됨.
- 4초 후 iframe load 이벤트 미발생 시 fallback 메시지 표시 ("브라우저에서 PDF 미리보기 불가, 다운로드 버튼 사용").
- 모바일 ≤900px: 배너 숨김에 맞춰 `topbar/preview-wrap left:0`.

**버튼 onclick 변경 (analyze.html)**
- `/print-pdf` 직접 호출 → `/print-preview` 페이지 이동.
- title 문구도 "미리보기 및 다운로드"로 보강.

### Edge Cases
- 캐시 미존재/ERROR 상태에서 `/print-preview` 접근 시 분석 페이지로 리다이렉트.
- iframe inline PDF는 `X-Frame-Options: SAMEORIGIN` 정책 안에서만 동작 (동일 origin이라 OK).
- 브라우저가 PDF inline 뷰어를 지원하지 않을 경우(일부 모바일 브라우저) 4초 후 fallback 안내 표시.

## [2026-05-07] Print Report → A4 1페이지 PDF 다운로드 고도화

**변경 파일:**
- `pom.xml`
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- `src/main/java/com/heapdump/analyzer/service/PdfReportService.java` (신규)
- `src/main/resources/templates/analyze-print.html` (신규)
- `src/main/resources/templates/analyze.html`
- `src/main/resources/fonts/Pretendard-Regular.ttf`, `Pretendard-Bold.ttf`, `LICENSE.txt` (신규, OFL 1.1)
- `CHANGELOG.md`

### 변경 의도
- 기존 사이드바 "Print Report" 버튼은 `window.print()`만 호출 → 활성 1탭만 인쇄, `@media print` 6줄, 결과 분량/레이아웃 불일정 → 보고서 첨부 부적합.
- A4 1페이지에 핵심 분석 결과를 압축한 정형 PDF를 다운로드하는 방식으로 교체. 외부 보고/공유 용도 일관화.

### 내역

**의존성 (pom.xml)**
- `com.openhtmltopdf:openhtmltopdf-core:1.0.10` (LGPL 2.1)
- `com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10`
- HTML+CSS → PDF, A4 1페이지 정형 리포트 렌더에 적합. 한글 폰트 `useFont()` 임베딩.

**한글 폰트 임베딩**
- `src/main/resources/fonts/` 에 Pretendard 1.3.9 Regular/Bold 2 weights + LICENSE.txt(OFL 1.1) 번들. JAR 약 +5MB.

**신규 서비스 `PdfReportService`**
- `renderPrintPdf(filename, HeapAnalysisResult) -> byte[]` 단일 메서드.
- Thymeleaf `TemplateEngine.process("analyze-print", ctx)` → XHTML → `PdfRendererBuilder.toStream()` → byte[].
- `AiInsightRepository.findByFilename()`로 DB의 AI 인사이트 조회 후 `insightData` JSON을 Jackson으로 파싱(severity / summary / recommendations 추출). summary 200자, recommendations 150자 컷.
- 폰트는 ClassPathResource로 InputStream supplier 등록.

**인쇄 전용 템플릿 `analyze-print.html`**
- A4 portrait, margin 12mm, 인쇄 가능 영역 186 × 273mm.
- 섹션 배치: 헤더(파일명/일시/소요/모델) → KPI 6카드(3×2 grid) → Heap 구성 stacked bar → Top Consumers 5행 표 → Leak Suspects 상위 3 → AI 인사이트 요약 → 푸터.
- `.report { width:186mm; height:273mm; overflow:hidden }` 1페이지 강제.
- severity 색상: critical=#DC2626, high=#EA580C, medium=#CA8A04, low=#6B7280. 카드 좌측 컬러 보더(blue/orange/green/purple/cyan/red).
- 클래스명 `max-width:88mm; text-overflow:ellipsis`. 라벨 한국어 + 영문 병기.

**컨트롤러 엔드포인트**
- `GET /analyze/{filename:.+}/print-pdf` 신규 추가. `FilenameValidator.validate()` → `analyzerService.getCachedResult()` → null/ERROR 시 404.
- 응답: `application/pdf`, `Content-Disposition: attachment; filename="..."; filename*=UTF-8''<encoded>` (RFC 5987 한글 파일명), `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`.
- 다운로드 파일명: `{원본baseName}-report.pdf` (확장자 .hprof/.bin/.dump/.gz 제거).
- 인증: 기존 `/analyze/**` authenticated() 정책 적용.

**버튼 교체 (analyze.html L862-866)**
- `onclick="window.print()"` → `location.href='/analyze/...print-pdf'`로 교체.
- `hasHeapData=false` (분석 ERROR) 시 `th:disabled` 비활성화.
- 라벨 "Print Report" → "PDF Report", 아이콘 🖨️ → 📄.
- 기존 `@media print` 블록(L532-537)은 유지 (Ctrl+P 폴백).

### Edge Cases
- AI 미분석 → "AI 인사이트 미분석" placeholder.
- AI insightData JSON 파싱 실패 → "AI 데이터 파싱 실패" 표시, severity는 컬럼 값 사용.
- Leak Suspects 0개 → "탐지된 누수 의심 항목 없음" placeholder.
- Top Consumers 0개 → 표 본문에 "데이터 없음" 1행.
- summary/recommendations 길이 컷 (220/560자) — 1페이지 보호. AI 박스 max-height 68mm로 권장 조치 5번 항목까지 표시 가능.
- 한글 파일명 RFC 5987 인코딩.

## [2026-05-07] Analysis History — Detections 레이아웃 재구성 + Recent Detections 추가

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`
- `src/main/resources/templates/history.html`
- `CHANGELOG.md`

### 변경 의도
- 기존 `/history` 2단 그리드(좌: 차트+KPI / 우: 테이블)에서 차트 가로 폭이 460–560px로 좁아 90d 기간이 빽빽하게 보이고, KPI 3카드가 차트 위쪽에 묻혀 한눈에 안 들어왔음.
- 차트가 보여주는 일자별 막대만으로는 "어떤 의심 항목이 잡혔는지" 즉시 확인 불가 — 차트 클릭 후 모달까지 두 단계 필요.

### 내역

**레이아웃**
- 페이지를 2개 행으로 분리:
  - 상단 `.detect-row` (`1fr 1fr` 좌우 50:50): 좌 — Detections 패널(KPI 3카드 + 차트), 우 — Recent Detections 패널.
  - 하단 `.history-bottom` (전폭): 검색 툴바 + 테이블 + 페이지네이션.
- KPI 3카드는 기존 자리(차트 패널 내부 `repeat(3, 1fr)`)에 유지. Recent 패널은 좌측 패널과 같은 높이로 stretch, 리스트는 `flex: 1` + `max-height: 540px` 스크롤.
- ≤1024px: 단일 컬럼 스택.

**Recent Detections (신규)**
- 차트 선택 기간(7/14/30/90d) 내 LeakSuspect 평탄화 → 심각도 가중치(critical=4 ··· low=1) DESC, 분석 시각 DESC 정렬, Top 30 표시.
- 행 구성: 심각도 배지(`dds-*` 재사용) + 서버 배지/Local + 일자 + 의심 항목 title(또는 category 폴백) + 분석명(`buildAnalysisName`).
- 클릭 → `/analyze/result/{filename}` 이동. 삭제된 dump는 `.deleted` 클래스로 비활성(opacity 0.55, 클릭 무반응).
- SSR 초기 렌더(Thymeleaf) + 기간/그룹 토글 시 `/api/history/detections` 응답의 `recent` 배열로 동기 갱신.

**Backend**
- 신규 nested DTO `DetectionRecentItem` (filename / analysisName / serverName / severity / title / category / analyzedAtEpoch / dateLabel / fileDeleted).
- `DetectionAggregate.recent : List<DetectionRecentItem>` 필드 추가.
- `aggregateDetections()` 확장: 기존 LeakSuspect 루프 내에서 기간 필터 통과 시 `DetectionRecentItem` 누적, 마지막에 정렬+Top 30 컷. `analysisName`은 파일별 1회만 빌드(lazy-cache).
- `severityWeight(String)` 헬퍼.
- `historyPage()` 모델에 `detectRecent` 추가.
- `GET /api/history/detections` 응답에 `recent` 배열 포함 (드릴다운 endpoint는 변경 없음).

### 보안/엣지케이스
- 비관리자: `historyPage()`/API 모두 기존 deleted 필터로 자동 제외.
- XSS: 모든 동적 문자열 `escapeHtml()` 적용.
- title 길이 cut-off: `text-overflow: ellipsis` + `title=` 호버 툴팁.

## [2026-05-06] Servers — 다중 덤프 경로(최대 5개) + 상세 페이지 서버명/수정 버튼

**변경 파일:**
- `src/main/java/com/heapdump/analyzer/model/entity/TargetServer.java`
- `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`
- `src/main/resources/templates/servers.html`
- `src/main/resources/templates/server-detail.html`
- `CHANGELOG.md`

### 변경 의도
- 한 서버에서 덤프가 여러 위치(예: `/opt/app/logs`, `/var/log/heap`, JBoss 도메인별 경로 등)에 흩어져 떨어지는 운영 환경 대응. 기존에는 1개 경로만 가능 → 별도 서버를 등록해야 했음.
- 서버 상세 페이지(`/servers/{id}`)에서 서버 이름이 topbar에만 노출되어 본문 정보 카드만 보고는 어떤 서버인지 식별이 어려웠음. 또한 "수정"이 목록 페이지에서만 가능해 상세 → 목록 왕복이 필요했음.

### 내역

**Backend**
- `TargetServer`
  - `dump_path` 컬럼 길이 `500 → 2500` (5개 × 평균 500자 여유). JPA `ddl-auto=update`로 자동 alter.
  - `MAX_DUMP_PATHS = 5` 상수.
  - `@Transient List<String> getDumpPaths()` 헬퍼: 줄바꿈 분리 → trim → 빈 라인 제거 → 중복 제거 → 최대 5개. UI/서비스 공용.
- `RemoteDumpService.scanRemoteDumpsWithStatus`
  - 다중 경로 순회. 경로별 결과 합산 + 중복 path 제거(다중 경로 겹침 대비).
  - 부분 실패 처리: 하나라도 성공하면 서버 상태 OK + `pathErrors` 배열에 실패 경로 기록. 전체 실패 시 첫 번째 fatal error를 대표로 노출.
  - `scanSinglePath()` private 메서드로 단일 경로 스캔 로직 분리. 파일 객체에 `sourceDumpPath` 필드 추가(어느 경로에서 왔는지 그룹핑용).

**Frontend (servers.html)**
- 모달: 단일 input → 동적 경로 입력 리스트(`+ 경로 추가` 버튼, 최대 5개 도달 시 버튼 숨김, `−` 버튼으로 행 제거).
- 저장 시 input 값들을 `\n`으로 join하여 `dumpPath`에 전송. 빈/중복 경로는 자동 제거.
- 목록 테이블 셀: 첫 경로만 노출 + `+N` 배지(추가 경로 개수). `title=` 전체 경로 툴팁.
- 스캔 결과 패널: 다중 경로일 때 source 경로별 그룹 헤더 표시. `pathErrors` 있으면 노란 배너로 일부 경로 실패 안내.

**Frontend (server-detail.html)**
- 정보 카드 첫 번째 항목으로 `서버 이름` 추가(굵게).
- 덤프 경로 항목은 라벨에 개수(`덤프 경로 (3개)`) + 경로 1줄씩 나열.
- `info-actions`에 `수정` 버튼 추가. 동일 모달 구조를 페이지 내에 재구현(servers.html과 동기화 유지 필요 — 두 곳 다 같은 JS 함수명 사용).

### 검증
- `mvn clean package -DskipTests` 11.2초 빌드 성공, `restart.sh` 후 9.3초 기동 — DB 스키마 자동 alter(컬럼 확장).
- 기존 단일 경로 데이터 호환: split이 1개 원소 리스트 반환 → 표시/스캔 모두 그대로 동작.

### 함정 / 주의
- `th:data-dumppath`로 newline 포함 문자열을 attribute에 실어보냄. HTML5 attribute value는 `&#10;` 인코딩으로 newline 보존되며 `dataset.dumppath`로 raw string 복원됨. 만약 일부 브라우저/Thymeleaf 버전에서 정규화 이슈가 발견되면 base64 인코딩으로 전환 검토.
- 모달 JS(`setDumpPaths`/`collectDumpPaths` 등)는 servers.html과 server-detail.html 두 곳에 중복. 한쪽 수정 시 다른쪽도 동시 수정 필요.
- `sourceDumpPath` 필드는 신규 응답에만 존재 — 기존 자동 탐지 코드(`autoDetectAndTransfer`)는 path만 사용하므로 영향 없음.

## [2026-05-06] History 페이지 — "힙 사용량" 칼럼 제거

**변경 파일:** `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
분석 결과 페이지에 들어가면 더 상세한 힙 정보가 제공되므로 History 테이블에서는 우선순위가 낮음. 칼럼 1개 줄여 가로 밀도 완화.

### 내역
- 헤더 `<th data-sort-key="heap">힙 사용량</th>` 제거.
- 셀 `<td class="td-meta col-hide-sm" th:text="...heapUsed...">` 제거.
- 행 속성 `data-sort-heap` 제거(정렬 키 자체 사용 X).
- 백엔드 `AnalysisHistoryItem.heapUsed`/`heapUsedBytes` 필드는 그대로(다른 페이지/JSON export 사용).

## [2026-05-06] History 페이지 — Detections 패널 확대 + 페이지 폭 추가 확장

**변경 파일:** `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
좌측 차트 영역이 ~420px로는 KPI 카드와 차트 라벨이 빡빡하게 들어가서 가독성이 떨어짐. 페이지 폭을 추가로 키우고 좌측 차트 영역을 더 넓혀 KPI/차트 모두 여유있게 표시.

### 내역
- `.container { max-width: 1600px → 1800px }` — 추가 가용폭 확보.
- `.history-grid grid-template-columns: minmax(360px, 420px) → minmax(460px, 560px)` — 좌측 차트 폭을 ~140px 더 넓힘. gap 18 → 20.
- `.det-chart-wrap { height: 260px → 340px }` — 차트 세로 80px 증가 → 일자별 막대 분포가 더 잘 보임.
- KPI 카드 보강: padding 12 14 → 14 16, val font-size 20 → 24, lbl 10 → 11, sub 11 → 12. gap 10 → 12, margin-bottom 14 → 16.
- 모바일 `@media (max-width: 640px) { .det-chart-wrap { height: 220px } }`은 그대로 유지(좁은 화면에서는 컴팩트).

### 검증
- 1800px 이상 모니터: 좌측 차트 ~560px, 우측 테이블 ~1180px(배너 닫힘).
- 1600~1800px: 좌측 460~560px, 우측 충분.
- ≤1024px: 1열 stacking + 모바일 반응형 그대로.

## [2026-05-06] History 페이지 — 컨테이너 폭 확장 + 테이블 가로 스크롤

**변경 파일:** `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
2단 레이아웃 적용 후 우측 테이블 영역이 좁아져 분석명/서버명/날짜 등 칼럼이 잘려 보임. 페이지 max-width를 늘려 와이드 모니터에서 정보 밀도를 높이고, 우측이 좁을 때도 테이블이 가로 스크롤되어 모든 칼럼을 확인할 수 있도록 보장.

### 내역
- `.container { max-width: 1280px → 1600px }` — 와이드 모니터에서 가용폭 활용. 배너 펼친 상태(220px) 가용 컨텐츠 폭 ~1340px, 닫힘 상태 ~1516px.
- 신규 `.table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch }` wrapper — 테이블만 가로 스크롤, panel 자체 둥근 모서리/`overflow: hidden`은 유지. webkit 스크롤바 8px 슬림 스타일 추가.
- `.htable { min-width: 960px }` — 데스크탑에서도 우측 컬럼이 좁아지면 가로 스크롤 트리거. 모바일(`@media max-width:640px`)의 `min-width: 600px`은 그대로(col-hide-sm로 일부 컬럼 숨겨진 후 적용).
- HTML: `<table>`을 `<div class="table-scroll">…</div>` wrapper로 감쌌음. `th:unless` 조건은 wrapper로 이동(빈 결과 시 표 자체 미렌더). `.no-match` / `.pagination-bar`는 wrapper 밖에 두어 스크롤되지 않게 유지.

### 검증
- 1600px 이상 모니터: 우측 테이블에 모든 칼럼 자연스럽게 노출.
- 1024~1280px: 우측 폭이 부족할 때 테이블이 가로 스크롤(스크롤바 노출).
- ≤1024px: grid 1열 stacking + 동일하게 가로 스크롤.
- div 태그 균형 + 빌드 통과.

## [2026-05-06] History 페이지 — 차트 좌 / 테이블 우 2단 레이아웃

**변경 파일:** `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
세로 스택(차트 위 / 테이블 아래) 배치는 와이드 모니터에서 차트 영역 아래 큰 빈 공간이 생기고, 차트와 테이블을 동시에 볼 수 없어 좌우 비교가 불가능. 좌측 차트 / 우측 테이블 2단으로 재배치하면 한 화면에서 추세와 상세를 동시 확인 가능하고 가독성이 개선됨.

### 내역
- `.history-grid` CSS — `display: grid; grid-template-columns: minmax(360px, 420px) 1fr; gap: 18px; align-items: start;` 좌측 chart 폭 고정 범위, 우측은 나머지 폭 차지.
- `.history-right` — 우측 컬럼은 flex column으로 toolbar + 테이블 패널을 16px gap으로 묶음. `min-width: 0`으로 grid item shrink 시 테이블 가로 스크롤 정상 동작 보장.
- `.history-grid .detect-panel { margin-bottom: 0 }` — grid 안에서는 panel 자체 margin 제거(grid gap이 대신).
- 반응형 `@media (max-width: 1024px)` — 1열 stack(기존 동작과 동일).
- HTML 구조: `page-hdr` 다음에 `.history-grid` wrapper. detect-panel을 첫 grid item으로, table-toolbar + table panel을 `.history-right` wrapper로 묶어 두 번째 grid item으로 배치.

### 검증
- 1280px 폭: 좌측 차트 ~420px, 우측 테이블 ~800px → KPI 3카드/차트/테이블 모두 안정적 표시.
- 1024px 이하: 1열 stacking으로 모바일 호환.
- div 태그 균형(opens == closes) 확인 후 빌드.

## [2026-05-06] History 테이블 — 분석명 표시 + Local 시리즈 라벨 통일

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
앞 단계에서 드릴다운 모달에는 분석명(`[HPROF]server_filename_yyyyMMdd`)을 도입했지만 History 테이블에는 여전히 원본 파일명만 노출되어 두 화면의 식별 단위가 어긋남. 차트의 로컬 업로드 시리즈도 `(직접 업로드)` 라벨이라 분석명의 `local_` prefix와 시각적으로 일치하지 않아 사용자가 "로컬 파일이 차트에 포함되지 않는다"고 인식하는 부조화가 발생.

### 내역
- **`AnalysisHistoryItem`** — `analysisName` 필드 + getter/setter 추가. `buildHistory()` 끝부분에서 모든 history 항목에 대해 `buildAnalysisName(filename, serverName, ts)` 일괄 적용. 기준 시각은 `analyzedAtEpoch > 0`이면 분석시각, 아니면 `lastModified` 폴백 → DB-based / file fallback / cache fallback 3개 분기 모두 자동 커버.
- **`aggregateDetections`** — 차트 시리즈 라벨 `UNKNOWN_SERVER`를 `"(직접 업로드)"` → `"Local"`로 변경. 분석명 helper의 server 부분(`local_`)과 시각적 일관성. 기존 차트 데이터 의미는 동일(serverName이 null/blank인 항목 집계).
- **`history.html`**
  - 테이블 헤더 `파일명` → `분석명`. 셀 텍스트 `${h.filename}` → `${h.analysisName}`. 정렬/검색 키(`data-name`)도 분석명 기준 → 사용자가 분석명 일부(예: `jeusserver1`, `20260502`)로 검색 가능.
  - 검색 placeholder `"Search analysis history..."` → `"분석명으로 검색…"`.
  - 행에 `data-filename` 속성 추가(원본 파일명 보존). `<a th:title>`에 원본 파일명 → hover 시 확인 가능. 링크 타겟(`/analyze/result/{filename}`)은 그대로 원본 파일명.
  - `.hi-name` 스타일 보강: `max-width: 300px → 480px`, `font-family: monospace`, `font-size: 12px` — 분석명 가독성 + 잘림 방지.

### 검증
- BUILD SUCCESS, app `Started` ~10s.
- 회귀: `/api/history`(JSON export), `/api/history/bulk-delete`, `/history/delete/{filename}` 모두 `data-filename`/`row-check value` 사용 → 정상 동작.
- 차트 30d 응답에서 시리즈 이름 `Local` 노출 확인. KPI total 동일(라벨만 변경).
- 테이블에서 분석명 표시 + 검색 시 분석명 부분 매칭 동작.

## [2026-05-06] History 페이지 — Detections 드릴다운 모달에 "분석명" 표시

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
드릴다운 모달의 파일명 + 서버 배지 조합은 정보량이 부족하고 분리되어 있어 한눈에 파악이 어려움. 분석을 식별할 수 있는 표준 분석명(`[HPROF]jeusserver1_jeus_admin.hprof_20260506`) 한 줄로 통합하면 확장자/서버/대상파일/분석일자가 동시에 보여 식별·검색 모두 용이.

### 내역
- **백엔드 helper `buildAnalysisName(filename, serverName, analyzedAtEpoch)`** — 형식: `[{EXT}]{server_lower|"local"}_{filename}_{yyyyMMdd}`. `.gz` 접미사는 벗기고 안쪽 확장자 사용(`.hprof.gz` → `[HPROF]`). 확장자 없거나 인식 불가 시 `[DUMP]`. 서버 없으면 `local`. 분석 시각 epoch가 0이면 today 폴백.
- **`DetectionDayFile`** — `analysisName` 필드 + getter/setter 추가. 기존 `filename`/`serverName`은 호환 유지(링크 타겟 등에 사용).
- **`/api/history/detections/day`** — `buildAnalysisName(...)` 호출하여 응답에 `analysisName` 필드 포함.
- **`history.html` 드릴다운 모달**
  - 파일명 텍스트 → `f.analysisName` 표시(`f.filename`로 폴백). 링크 타겟(`/analyze/result/{filename}`)과 `title=` 속성은 원본 파일명 그대로 → hover 시 원본 파일명 확인 가능.
  - 별도 서버 배지(`.det-day-server`) 제거 — 분석명에 이미 서버명 포함되어 중복.
  - `fileDeleted=true` 케이스도 분석명 표시 + `deleted` 배지 + title에 원본 파일명.

### 검증
- BUILD SUCCESS, app `Started` ~10s.
- API: `GET /api/history/detections/day?date=YYYY-MM-DD` 응답 각 file에 `analysisName` 필드 포함 확인.
- UI: 막대 클릭 → 모달 → 분석명 한 줄 표시(서버 배지 사라짐) → hover 시 원본 파일명 → 클릭 시 `/analyze/result/{filename}` 진입.
- 엣지: `.gz`, 서버 없는 Local 업로드, 확장자 없는 파일 모두 폴백 작동 확인.

## [2026-05-06] History 페이지 — Detections 그래프 + 드릴다운 모달 추가

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `src/main/resources/templates/history.html`, `CHANGELOG.md`

### 변경 의도
대시보드의 "Detections 최근 14일" 차트는 14일 / 서버별 stacked만 보여줘서, 더 긴 기간이나 심각도 단위 추세 확인, 특정 일자에 어떤 파일들이 잡혔는지 확인하려면 매번 History 표를 수동 검색해야 했다. History 페이지에서 차트(7/14/30/90d, 서버별/심각도별 토글) + 막대 클릭 → 일자별 파일 모달 → 분석 결과 페이지 직링크 흐름 제공.

### 내역
- **백엔드 helper `aggregateDetections(history, days, topN)` 추출** — 기존 `dashboard()` 인라인 로직(line 130~255)을 일반화. `int[14]` → `int[days]`. 서버 시리즈 + 심각도 시리즈 + 파일별 breakdown + KPI(total/last7d/prev7d/delta7d/peak)를 한 번의 순회로 산출. `dashboard()` / `historyPage()` / 신규 API 모두 공유.
- **신규 inner DTO** — `DetectionAggregate`(labels, serverSeries, severitySeries, dailyDetections, detectionItems, KPI 필드), `DetectionDayFile`(filename, serverName, severity counts, fileDeleted, analyzedAtEpoch).
- **신규 API 2개**:
  - `GET /api/history/detections?days=7|14|30|90&groupBy=server|severity` — 차트 데이터 + KPI. 잘못된 days/groupBy는 14/server로 폴백.
  - `GET /api/history/detections/day?date=YYYY-MM-DD&groupBy=...` — 해당 일자 파일 리스트(critical desc → high desc → suspectCount desc 정렬). 잘못된 date 시 400.
  - 비관리자에게 `fileDeleted=true` 항목 제외(서버측 보안). CSRF는 GET이라 면제.
- **`historyPage()`** — `aggregateDetections(_, 14, 12)` 결과를 SSR로 모델 주입(detectInitDays/Group, detectLabels, detectServerSeries/SeveritySeries, KPI 6개) → 첫 페인트 깜빡임 방지.
- **`dashboard()` 리팩토링** — 기존 detection 집계 블록을 helper 호출로 교체. 모델 키(dailyDetections/serverSeries/kpiTotal14d 등)는 그대로 유지하여 `index.html` 회귀 없음.
- **`history.html`**
  - `<head>`에 Chart.js v4 CDN 추가.
  - 헤더와 toolbar 사이 신규 `.detect-panel` — 기간 칩(7d/14d/30d/90d) + 그룹 토글(서버별/심각도) + KPI 3카드 + `<canvas id="detectChart">`.
  - 인라인 CSS 추가(`.det-kpi*`, `.det-chart-wrap`, `.det-period-chip(.active)`, `.det-group-btn(.active)`, `.det-day-modal`, `.dds-critical/high/medium/low`, 모바일 반응형).
  - 신규 `#detDayModal` 드릴다운 모달 — 파일별 row[파일명 링크 → `/analyze/result/{filename}`] + 서버 배지(없으면 회색 "Local") + 심각도 배지(0건 제외). `fileDeleted=true` 시 비링크. ESC 닫기 + 외부 클릭 닫기.
  - JS: `readPrefs()` URL `?days`/`?groupBy` > localStorage(`historyDetectDays`/`historyDetectGroup`) > 기본 14d/server. `persistPrefs()`로 `history.replaceState` 동기화. Chart.js `onClick` → `openDetDayModal`. SSR 초기값과 prefs 일치 시 fetch 생략.
- **색상**: server 12색 팔레트 + "기타" `#9CA3AF` / severity `{critical:#DC2626, high:#EA580C, medium:#B45309, low:#16A34A}`.

### 검증
- BUILD SUCCESS, app `Started` ~10s.
- API 단위: `?days=14&groupBy=server` → kpi/datasets 정상, `?days=7&groupBy=severity` → 4개 severity datasets, `?days=99` → days=14 폴백, `/day?date=bad` → 400.
- UI: 14d/server 초기 노출 → 7d 칩 클릭 시 차트 축소 + URL `?days=7&groupBy=server` → F5 시 상태 복원 → 심각도 토글 시 4색 segments → 막대 클릭 시 모달 → 파일 클릭 시 `/analyze/result` 진입 → 비관리자 로그인 시 deleted 항목 차트/모달 양쪽 제외.
- 회귀: 대시보드 Detections 패널 변경 없음(helper 리팩토링 호환성 유지).

## [2026-05-06] 업로드 클라이언트 — XHR이 redirect를 success로 오인하던 버그 수정

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `src/main/resources/templates/index.html`, `CHANGELOG.md`

### 변경 의도
앞 단계 동일 파일명 차단 정책 적용 후에도 동일 파일 재업로드 시 UI에 "✅ 완료"로 표시되는 결함 보고. 원인:
- 서버 `/upload`는 차단 시 `redirect:/`(302) + flash `error` 메시지 반환.
- 클라이언트 XHR은 302를 따라 GET `/`로 이동(200)하고, `xhr.status === 200`만 보고 `item.status='done'`로 마킹.
- 결과: 서버에선 정상 차단되지만 사용자 화면엔 성공으로 보임.

### 내역
- **신규 `/api/upload` JSON 엔드포인트** — 성공: `200 + {status:"ok", filename, size}`. 실패: `400/500 + {status:"error", message}`. CSRF 면제(/api/**). 기존 `/upload`(form fallback, redirect+flash)는 호환성 위해 유지.
- **`index.html` XHR 핸들러** — `xhr.open('POST','/api/upload')`로 변경. `load` 이벤트에서 `xhr.status` 검사 → 2xx만 done, 그 외는 error. `responseText`를 JSON 파싱해 `item._error` 저장.
- **렌더** — `q.status==='error'` 시 라벨을 `'실패'` → `q._error || '실패'`로 변경 → 차단 사유("동일한 이름의 파일이 이미 존재합니다…")가 큐 항목 옆에 그대로 노출.

### 검증
- BUILD SUCCESS, app `Started` 9.8s.
- 재현(curl): `POST /api/upload` 동일 파일 → `HTTP=400`, `{"status":"error","message":"동일한 이름의 파일이 이미 존재합니다…"}`. 디스크의 기존 파일 timestamp 미변경(덮어쓰기 안 됨).
- 브라우저 큐 UI에선 ❌ 아이콘 + 에러 메시지가 그대로 노출됨.

## [2026-05-06] 업로드 버튼 — 동일 파일명 차단 정책 적용

**변경 파일:** `src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java`, `src/main/resources/templates/index.html`, `CHANGELOG.md`

### 변경 의도
업로드 버튼으로는 같은 이름의 파일을 덮어쓰거나 자동 rename 추가할 수 없도록 정책 변경. 이전에는 `DUPLICATE_NAME` 시 클라이언트 모달에 [이름 변경] / [덮어쓰기] / [건너뛰기]를 제공했는데, 정책상 동일 이름 업로드는 모두 차단한다. 사용자에게 파일명 변경 후 재시도를 안내.

(Servers scan 경로 — 원격 자동 전송 시 자동 rename은 그대로 유지. 본 정책은 업로드 버튼 한정.)

### 내역
- **`HeapDumpAnalyzerService.uploadFile()`** — 서버측 안전망: `dumpfiles/{filename}` 또는 `dumpfiles/{filename}.gz` 존재 시 `IllegalArgumentException`("동일한 이름의 파일이 이미 존재합니다") 즉시 throw. `Files.copy`의 `REPLACE_EXISTING` 옵션 제거(중복 시 명시적 차단 의도가 더 분명).
- **`index.html`**
  - `startDuplicateChecks()`에서 `DUPLICATE_NAME` 응답 시 신규 `showQueueDupNameBlockedModal()` 호출.
  - 신규 차단형 모달 — 빨간 X 아이콘, "업로드 차단" 헤더, 단일 [확인] 버튼. 모달 표시 직후 `item.status = 'skipped'`로 마킹 → 큐에서 본 파일은 업로드되지 않고 다음 항목으로 진행.
  - 기존 `showQueueDupNameModal()`(이름 변경/덮어쓰기/건너뛰기 3옵션) 함수 제거.
- 서버 `checkDuplicate()` 응답은 변동 없음(`DUPLICATE_NAME` + `suggestedName` 그대로 반환). 클라이언트에서 무시되어 `suggestedName` 필드는 향후 폐기 가능 — 본 PR에선 호환성 위해 유지.

### 검증
- BUILD SUCCESS, app `Started HeapAnalyzerApplication` 9.8s.
- 시나리오: 같은 이름의 파일 업로드 시도 → 차단 모달 표시 → [확인] → 큐 다음 항목으로 진행, 본 파일은 업로드되지 않음.
- 클라이언트 우회 (curl 등) 시도해도 서버측에서 `IllegalArgumentException`으로 거부 → 사용자 메시지 노출.

## [2026-05-06] Servers scan transferred 판정 정확화 — (서버, 원격 원본명, 크기) 키

**변경 파일:** `src/main/java/com/heapdump/analyzer/model/entity/DumpTransferLog.java`, `src/main/java/com/heapdump/analyzer/repository/DumpTransferLogRepository.java`, `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`, `CHANGELOG.md`

### 변경 의도
이름이 같아도 서버가 다르거나 크기가 다르면 별개의 파일로 인식해야 하는데, 기존 scan 판정은 `localExists(filename)` + `(serverId, filename) DB 매치`로만 결정됐다. transfer 시 자동 rename(`_2`)으로 DB의 `filename`은 로컬 저장명이 들어가, scan에서 들어오는 원격 원본명과 미스매치 → 같은 파일을 매번 "전송 안 됨"으로 잘못 판정 → 재전송 시 `_3`, `_4`로 무한 누적되는 결함.

수동 업로드(`/api/upload`)는 정책 그대로(서버 컨텍스트 없음) — 본 변경은 Servers scan 경로만 영향.

### 내역
- **`DumpTransferLog`** — `remote_filename` 컬럼 신규 (`varchar(500)`, nullable). ddl-auto=update이라 자동 반영.
- **`DumpTransferLogRepository`** — 신규 메서드:
  - `existsByServerIdAndRemoteFilenameAndFileSizeAndTransferStatus(...)` — transferred 판정.
  - `findFirstByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(...)` — analyzed 판정용 로컬 저장명 lookup.
  - `@Modifying @Query backfillRemoteFilenameFromLocal()` — 레거시 row 보정 (`UPDATE ... SET remote_filename = filename WHERE remote_filename IS NULL`).
- **`RemoteDumpService.transferFile()`** — 진입 즉시 원격 원본명을 캡처해 `log.setRemoteFilename(remoteFilename)`. rename은 `filename`(로컬 저장명)에만 반영, 원본명은 불변.
- **`RemoteDumpService.scanRemoteDumpsWithStatus()`** — transferred 판정을 `(serverId, remoteFilename, fileSize, SUCCESS)` 매치로 교체. analyzed는 매치된 로그의 `filename`(로컬명)으로 `analysisHistoryRepository.existsByFilename` 호출. localExists 의존 제거.
- **`RemoteDumpService.@PostConstruct backfillRemoteFilenames()`** — 부팅 시 1회 backfill 실행. 실패 시 경고만 출력하고 진행(부팅 차단 안 함).

### 검증
- BUILD SUCCESS, app `Started HeapAnalyzerApplication` 9.7s.
- 부팅 로그: `[RemoteDump] Backfilled remote_filename for 12 legacy transfer log row(s)` 확인.
- DB 스키마: `remote_filename varchar(500) YES` 컬럼 추가 확인.
- 데이터 보정: 12 / 0 NULL / 12 backfilled — 모든 기존 row 정상 채움.

## [2026-05-06] Files 페이지 — 서버 컬럼 추가 (history.html과 동일 패턴)

**변경 파일:** `src/main/resources/templates/files.html`, `CHANGELOG.md`

### 변경 의도
업로드 출처(어느 서버에서 수집된 dump인지)를 Files 목록에서 직접 식별 가능해야 함. History 페이지에는 이미 `서버` 컬럼이 있어 같은 패턴 차용.

### 내역
- `<th>` "서버" 컬럼을 AI 인사이트와 날짜 사이에 추가 (`col-hide-sm sortable` data-sort-key="server").
- `<tr>`의 `data-sort-server` 속성 추가.
- `<td>`에 history.html과 동일한 배지 스타일(`#EFF6FF`/`#2563EB`) — serverName 있으면 배지, 없으면 회색 "Local".
- 모바일 ≤640px 미디어 쿼리에 `.ftable .col-hide-sm { display: none }` 규칙 추가(.htable처럼).

### 검증
- BUILD SUCCESS, app `Started HeapAnalyzerApplication` 확인.
- 정렬: 서버명 헤더 클릭 시 알파벳/한글 사전순 동작(localeCompare 기준).

## [2026-05-06] Dashboard Detections 차트 — 막대 두께 축소

**변경 파일:** `src/main/resources/templates/index.html`, `CHANGELOG.md`

### 내역
Chart.js options에 `barPercentage: 0.6`, `categoryPercentage: 0.7` 추가 — 카테고리 폭의 약 42%로 좁아짐(기본 ~72% 대비 약 60% 수준).

## [2026-05-06] Dashboard Detections 차트 — 범주를 severity → 서버명으로 변경

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `src/main/resources/templates/index.html`, `CHANGELOG.md`

### 변경 의도
일자별 탐지건수 차트의 stacked bar 범주를 **탐지위험도(critical/high/medium/low)** 에서 **서버명**으로 변경. "어느 서버에서 언제 누수 의심이 몇 건 탐지됐는지"를 한 화면에서 추적 가능하도록.

### 내역
- **HeapDumpController.index()** — 일자 버킷을 `int[5]`(severity 카운트)에서 `Map<LocalDate, Map<String, Integer>>`(서버별 카운트)로 교체. 14일 누적 서버 순위(`serverTotals`) 기반 상위 12개 + "기타" 그룹화. 서버명 누락(직접 업로드)은 `(직접 업로드)`로 표기. 새 모델 attribute `serverSeries` (`List<ServerSeries>` — name + counts[14]). KPI(14d/7d/Peak)는 dayTotal 기준 그대로 유지.
- **DailyDetection** DTO 단순화 — date/total만 남기고 critical/high/medium/low 필드 제거.
- **ServerSeries** inner DTO 신규(name/counts).
- **index.html**
  - 패널 헤더 `Detections · 최근 14일 · 서버별`.
  - 인라인 직렬화에 `SERVER_SERIES` 추가, 차트 datasets를 12색 팔레트(+"기타" 회색)로 매핑.
  - tooltip — 0인 시리즈 숨김(`filter`) + 값 내림차순 정렬(`itemSort`).

### 검증
- `mvn clean package -DskipTests` BUILD SUCCESS, app `Started HeapAnalyzerApplication` 확인.
- 0건/1서버/다중서버/13서버 초과 케이스에서 시리즈 구성 정상.

## [2026-05-06] Dashboard 고도화 — Analysis Files → 일자별 탐지건수 차트

**변경 파일:** `src/main/java/com/heapdump/analyzer/controller/HeapDumpController.java`, `src/main/resources/templates/index.html`, `CHANGELOG.md`

### 변경 의도
대시보드 좌측 `Analysis Files` 패널은 사이드바 `Recent Files`와 정보가 중복되고 추세·집계가 보이지 않아 가치가 낮음. 누수 탐지 추이를 한눈에 파악할 수 있도록 **일자별 탐지건수 차트**로 교체.

### 내역
- **HeapDumpController.index()** — 기존 severity 누적 loop 안에서 `analyzedAt` 기반 일자 버킷에 함께 누적(별도 쿼리 없음). 최근 14일 daily detection 배열 + KPI(14d 누적 / 7d vs 직전 7d 변화율 / Peak day) 산출 후 모델에 주입. 사용 안 하게 된 `analysisHistory`/`hasMoreFiles`/`totalFileCount` model attribute 제거.
- **AnalysisHistoryItem** — `analyzedAtEpoch` 필드 추가, `buildHistory()` DB 분기에서 `analyzedAt` epoch ms로 세팅.
- **DailyDetection** inner DTO 신규(date/total/critical/high/medium/low).
- **index.html**
  - `<head>`에 Chart.js 4.4.0 CDN 추가(`analyze.html`과 동일 버전).
  - 좌측 패널을 **Detections · 최근 14일**로 교체: KPI 3카드 + Chart.js stacked bar(severity별 색상). 0건 시 안내 문구.
  - 우측 `탐지 현황` 패널 헤더에 "critical 우선" 라벨 보강.
  - 인라인 직렬화 `var DAILY_DETECT = ...` + 차트 초기화 IIFE.

### 검증
- `mvn clean package -DskipTests` BUILD SUCCESS.
- 분석 이력 0건: 패널이 빈 메시지로 표시되고 JS 에러 없음.
- 분석 이력 ≥1건(success+suspect): KPI 3칸 + 14일 stacked bar 렌더, 툴팁 footer Total 노출.
- 14일 윈도우는 누락일도 0으로 채워 일자 정렬 유지.
- `/files`·`/history`·`/api/history` JSON shape 변동 없음(buildHistory 새 필드는 노출 안 됨).

## [2026-05-06] 버전 2.0.0 → 2.0.1 업데이트

**변경 파일:** `pom.xml`, `restart.sh`, `README-DEPLOY.md`, `CLAUDE.md`, `CHANGELOG.md`

### 내역
- `pom.xml` `<version>2.0.0</version>` → `2.0.1`
- `restart.sh` — kill grep 패턴과 `nohup java -jar ... heap-analyzer-2.0.1.jar` 갱신
- `README-DEPLOY.md` 2.5절 restart.sh 예시 갱신
- `CLAUDE.md` Build & Run 섹션 jar 파일명 갱신
- 기존 2.0.0 프로세스 수동 종료(SIGTERM) 후 신규 2.0.1로 재기동. 다음 재기동부터는 `bash restart.sh`로 정상 처리됨

### 검증
- `Started HeapAnalyzerApplication in 9.472 seconds`
- `ps -ef | grep heap-analyzer-2.0.1` 정상 노출

## [2026-05-06] Target Servers 스캔 에러 코드 정비 — 덤프 경로 누락/권한 부족 시 메시지 명확화

**변경 파일:** `src/main/java/com/heapdump/analyzer/service/RemoteDumpService.java`, `src/main/java/com/heapdump/analyzer/controller/ServerController.java`, `src/main/resources/templates/servers.html`, `CHANGELOG.md`

### 증상
`/servers`에서 스캔 버튼 클릭 시 원격 덤프 경로 자체가 없거나 읽기 권한이 없을 때 빨간 "SSH/SCP Error" 배너에 본문이 비어 표시(`SSH 오류 (exit 1):`만 노출). 원인 추적 불가.

### 근본 원인
`scanRemoteDumpsWithStatus()`가 실행하는 find 명령에 `2>/dev/null`이 붙어 있어 `find: '/path': No such file or directory` 같은 핵심 진단 메시지가 통째로 삼켜짐. exit 코드만 비정상으로 떨어지고 stderr는 빈 문자열 → cleanSshError가 빈 문자열 반환.

### 수정 내역
- **RemoteDumpService.scanRemoteDumpsWithStatus()** — find 앞에 `[ -d ]`/`[ -r ]` 가드를 추가해 의도적으로 exit 2(NOT_FOUND) / exit 3(NOT_READABLE) 반환. 결과 Map에 `errorCode` 필드 신설:
  - `DUMP_PATH_NOT_FOUND` — 원격 덤프 경로가 존재하지 않음
  - `DUMP_PATH_NOT_READABLE` — SSH 계정에 읽기 권한 없음
  - `SSH_ERROR` — 그 외 SSH/원격 셸 오류 (exit 1 등). stderr 빈 경우 "원격 명령 실행 실패 (exit N)" 폴백
  - `SCAN_EXCEPTION` — Java Exception
  - dumpPath에 single quote가 들어가도 안전하도록 `'\''` 이스케이프 추가
- **ServerController.scanServer()** — `errorCode`/`dumpPath` 필드를 응답 JSON에 forwarding
- **servers.html scanServer()** — `errorCode` 별 배너 시각 분리:
  - 경로 문제(`DUMP_PATH_NOT_FOUND`/`DUMP_PATH_NOT_READABLE`): 노란 경고 배너 + "서버 상세에서 dump path 또는 SSH 계정 권한 확인" 보조 안내
  - 그 외: 기존 빨간 SSH/SCP Error 배너
  - 배너 제목에 `[ERROR_CODE]` 접두사 노출로 식별성 향상
- 디렉토리는 있으나 dump 파일만 없는 케이스(success + empty files)는 기존 회색 "덤프 파일이 없습니다." 안내 그대로 유지

### 검증
- 빌드 성공: `mvn clean package -DskipTests` (11.5s)
- 기동 성공: `Started HeapAnalyzerApplication in 9.435 seconds`

## [2026-04-30] README-DEPLOY.md 갱신 — 2026-04-26 이후 변경사항 일괄 반영

**변경 파일:** `README-DEPLOY.md`, `CHANGELOG.md`

### 배경
README-DEPLOY.md 작성(2026-04-26) 이후 4월 27일~30일 사이에 Spring Session JDBC, RAG Phase 2, 로그인 이력, 계정 신청 등 여러 기능이 추가되었으나 이관 가이드에 미반영. 사내 시스템으로 옮길 때 신규 키/테이블 누락으로 기동 실패 또는 기능 disable 가능성이 있어 일괄 점검·반영.

### 반영 내역
- **2.5 DB 비밀번호 암호화** — JAR 설치 경로를 `/opt/heap-analyzer` → `/opt/genspark/webapp_dump`로 통일. `restart.sh`에 `HEAP_ANALYZER_ENCRYPTION_KEY` export 추가 예시 보강(현재 `restart.sh`는 키를 export하지 않음). LLM/RAG도 동일 키 사용 명시.
- **2.6 application.properties 수정** — 기존에 DataSource 한 블록만 보여주던 것을 5개 서브섹션으로 분리:
  - (1) DataSource — 필수 변경
  - (2) Spring Session JDBC — 필수 (블록 누락 시 매 재기동마다 강제 로그아웃)
  - (3) LLM 분석 — 4-provider(claude/gpt/genspark/custom)
  - (4) RAG (Elasticsearch) — keyword + Phase 2 두 semantic 모드 + 청킹
  - (5) 기타 환경값 — MAT 타임아웃, 분석 스레드풀, 원격 SSH/SCP, 업로드/로깅
- **2.7 테이블 자동 생성 확인** — 7개 → 11개로 갱신:
  - `account_requests` (2026-04-30 self-signup), `login_history` (2026-04-29), `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` (2026-04-29 Spring Session JDBC) 4건 추가
  - 생성 주체(JPA / Spring Session JDBC) 칼럼 추가
  - `SPRING_SESSION` 검증 SQL 예시 추가
- **5. 애플리케이션 기동** — 경로 통일(`/opt/genspark/webapp_dump`), CLAUDE.md의 기동 검증 grep 명령 추가
- **6. 트러블슈팅** — 신규 6개 행 추가: SPRING_SESSION 생성 실패, 재기동마다 로그아웃, 계정 신청 403, LLM 401, RAG 검색 0건, RAG semantic 모드 에러, 5GB 업로드 실패
- **7. 이관 후 활성화 작업 (신설)** — 4개 서브섹션: 계정 관리(self-signup/접속 이력/활성 세션), LLM 활성화, RAG 활성화(ES 매핑 사전 준비 명시), 원격 SSH/SCP 자동 수집
- **8. 이관 패키지 체크리스트** (기존 7번에서 8번으로 이동) — 항목 보강: `application.properties` 경로 명확화, `restart.sh`에 암호화 키 export 추가, 11개 테이블 검증, `rag-data/` CSV 학습 데이터 포함, 계정 신청 정책 결정 항목

### 검증
- `grep -n "^## \|^### "` — 8개 메인 섹션 + 4.5개 서브섹션 정상 (Markdown 구조 유지)
- 라인 수 305 → 496 라인 (신규 추가 분량 대부분 application.properties 5개 블록 + 7번 활성화 가이드)

## [2026-04-30] login.html 폼 사라짐 버그 수정 — `<head>`에 `<meta name="_csrf">` 추가

**변경 파일:** `src/main/resources/templates/login.html`, `CHANGELOG.md`

### 증상
`/login` 페이지에서 ID/PWD 입력칸과 로그인 버튼이 사라짐. 응답 본문이 line 130(login-sub div)에서 끊겨 약 16KB 분량 후속 마크업이 미렌더.

### 근본 원인
```
java.lang.IllegalStateException: Cannot create a session after the response has been committed
  at HttpSessionCsrfTokenRepository.saveToken
  at SpringActionTagProcessor.doProcess (login - line 134, <form th:action="@{/login}">)
```
- login.html의 `<style>` 블록이 약 110줄로 응답 버퍼(Tomcat 기본 8KB)를 초과 → `<form th:action>` 처리 시점엔 응답이 이미 commit된 상태
- Spring Security의 `LazyCsrfTokenRepository`가 `<form>` 처리 중 hidden CSRF input 주입을 위해 `request.getSession()` 호출 → 세션 생성 시 `Set-Cookie` 헤더 작성 시도하나 응답이 commit되어 실패
- 결과적으로 Thymeleaf가 line 134에서 예외 던지고 렌더링 중단 → 본문이 잘린 채 클라이언트에 전송

### 해결
`<head>`에 `<meta name="_csrf" th:content="${_csrf.token}">` + `<meta name="_csrf_header">` 추가. 페이지 앞부분에서 `_csrf.token`이 평가되며 `LazyCsrfToken.getToken()` 호출 → 세션이 응답 버퍼 commit 전에 미리 생성됨. `<form th:action>` 처리 시점엔 이미 토큰이 캐싱되어 세션 신규 생성 불필요.

이 패턴은 이미 `files.html`/`history.html`/`server-logs.html` 등 8개 템플릿에 동일하게 적용되어 있었으나 login.html에만 누락. CLAUDE.md "CSRF 주의 (JS 동적 폼)" 항목에 명시된 패턴.

### 검증
- 기동 후 `curl http://localhost:18080/login` → 16803 bytes / 314줄 (이전 4400 bytes / 133줄에서 끊김)
- `<form action="/login" method="post">` + 자동 주입된 `<input type="hidden" name="_csrf" value="...">` + username/password 입력칸 + 로그인 버튼 모두 정상
- 재기동 후 신규 `TemplateInputException` 로그 없음

## [2026-04-30] Target Servers 페이지 데이터 그리드 풀 패턴 적용

**변경 파일:** `src/main/resources/templates/servers.html`, `CHANGELOG.md`

### 배경
`/servers` 페이지는 `serverRepository.findAll()`을 그대로 SSR 렌더링하여 검색·정렬·페이지네이션이 전혀 없었음. 등록 서버가 늘어날수록 특정 서버를 찾기 번거롭고 Files/History 페이지의 데이터 그리드 UX와 일관되지 않아 고도화 요청.

### 변경 내역 (servers.html 단일 파일, 백엔드 변경 없음)
- **툴바 추가**: 텍스트 검색(이름/호스트/SSH 계정/덤프 경로 OR 매칭) + 상태 필터(전체/정상/실패/미확인/비활성) + 자동탐지 필터(전체/ON/OFF) + 행 표시 셀렉트(20/30/50/100, localStorage `serversPageSize`)
- **헤더 클릭 정렬**: 6개 컬럼(이름/호스트/SSH 계정/덤프 경로/상태/자동탐지) — `data-sort-key`/`data-sort-type=str`, ▲/▼ 인디케이터, 한글 `localeCompare(s, 'ko')`. 기본 이름 오름차순.
- **페이지네이션**: 행 수 ≤ pageSize 시 숨김, 첫/마지막/현재±2 + ellipsis. 전체 건수/현재 범위 표시.
- **테이블 마크업**: 모든 `<tr>`에 `data-name`/`data-host`/`data-sshuser`/`data-dumppath`/`data-autodetect`/`data-enabled`/`data-status` 부여(클라이언트 필터/정렬용). `data-status`는 `enabled=false` → `DISABLED`, 그 외 `connStatus` 값.
- **검색 결과 없음**: `.no-match` 메시지("검색 결과가 없습니다.") — 등록 서버 0건일 때의 `.empty-msg`와 분리.
- **상태 동기화**: `updateStatusBadge()`에 `row.setAttribute('data-status', status)` 추가 — 연결 테스트/스캔으로 상태 변경 시 필터 일관성 유지.
- **모바일(≤900px)**: 툴바/페이지네이션 세로 적층.

### 디자인 결정
- **클라이언트 측 처리**: 서버 수가 일반적으로 수십 개 수준이라 SSR 구조를 유지한 채 클라이언트 측에서 필터/정렬/페이지네이션. files.html 패턴 그대로 이식하여 일관성 확보. localStorage 키는 `serversPageSize`로 분리.
- **백엔드 무변경**: `ServerController.serversPage()`/`TargetServerRepository`/`TargetServer` 엔티티 그대로 유지.

### 검증
- `mvn clean package -DskipTests && bash restart.sh` 후 `Started HeapAnalyzerApplication in 9.229 seconds` 확인
- JAR 내 `BOOT-INF/classes/templates/servers.html`에 신규 마커 26건 매칭

## [2026-04-30] RAG 학습 데이터 CSV — WAS 토픽 추가 (54→84건)

**변경 파일:** `rag-data/rag-knowledge-20260430.csv`, `rag-data/README.md`, `CHANGELOG.md`

### 배경
사내 운영 환경에서 자주 사용되는 WAS/WebServer 제품(JEUS / Webtob / Weblogic) 관련 토픽 30건을 추가 요청. 제품별로 검색·필터링이 정확히 되도록 새 category 3개를 분리하여 도입.

### 신규 카테고리 (각 10건)
- **`was_jeus`** — TmaxSoft JEUS: heap dump 캡처(jeusadmin/WebAdmin), 메모리 영역 구조, Session 관리, DataSource 풀, Web Container Thread Pool, GC 로그 활성, 클러스터 모니터링, 버전별 차이(7/8/9), Hot Deploy ClassLoader 누수, jeusadmin/WebAdmin 도구
- **`was_webtob`** — TmaxSoft Webtob: 프로세스 구조(HTH/HTM/HTL/JSV), JEUS 연동(JSV connector), HTH 메모리 패턴, 로그 분석, 503 에러 진단, 성능 튜닝, SSL 설정, http.m 파라미터, 관리 명령(wsadmin/wsboot/wsdown), 고가용성
- **`was_weblogic`** — Oracle Weblogic: heap dump 캡처(WLST/jcmd), Stuck Thread 진단, JDBC Connection Pool, Work Manager, Node Manager, Cluster 통신(Multicast/Unicast), Session Replication, Self-Tuning Thread Pool, 12c vs 14c 차이, Admin/Managed Server 구조

### 최종 분포
| category | 건수 |
|---|---|
| heap_analysis | 15 |
| java_spring | 15 |
| mat_tip | 13 |
| troubleshooting | 11 |
| was_jeus | 10 |
| was_webtob | 10 |
| was_weblogic | 10 |
| **합계** | **84** |

## [2026-04-30] RAG 학습 데이터 CSV 확장 (24→54건)

**변경 파일:** `rag-data/rag-knowledge-20260430.csv`, `rag-data/README.md`, `CHANGELOG.md`

### 배경
초안 24건이 학습 데이터로는 부족 — 50건 이상으로 확장 요청. 균등 분포 기준으로 30건 추가하여 카테고리별 11~15건 수준으로 보강.

### 추가 내역 (각 카테고리 +7~8건, 총 +30건)
- **heap_analysis** 7→15: ZGC OOM 분석, Stack Overflow vs Heap OOM, Compressed Oops 32GB 경계, Spring ApplicationListener 누수, JDBC Connection 누수, GC 로그 기본 해석, Promotion Failure 패턴, Allocation Rate 측정
- **java_spring** 8→15: Spring AOP CGLIB 메모리 비용, @Async ThreadPool 설정, @Cacheable maxSize 필수, Container 환경 메모리 인식, GC 알고리즘 선택 가이드, Heap Dump 캡처 명령 5종, JIT Code Cache
- **mat_tip** 5→13: Top Components 리포트, 두 dump 비교 분석, MAT Thread 분석, Inspector 뷰, OQL UNION/서브쿼리, OQL Duplicate Strings 탐지, Reachable vs Unreachable, Index 파일 의미
- **troubleshooting** 4→11: Logback async appender 큐 누적, HikariCP pool 고갈, Kafka Consumer rebalance OOM, Redis cache miss → DB 풀 고갈, 파일 업로드 메모리 누적, WebSocket 세션 누수, Scheduled 중첩 실행 (모두 가상 예시)

### 최종 분포
| category | 건수 |
|---|---|
| heap_analysis | 15 |
| java_spring | 15 |
| mat_tip | 13 |
| troubleshooting | 11 |
| **합계** | **54** |

## [2026-04-30] RAG 학습 데이터 CSV 초안 작성

**변경 파일:** `rag-data/rag-knowledge-20260430.csv`(신규), `rag-data/README.md`(신규), `CHANGELOG.md`

### 배경
사내 RAG(Elasticsearch) 환경에 별도 운영 중인 CSV → ES 인덱싱 도구로 투입할 학습 데이터 초안 작성. 검색 모드는 keyword(BM25) 기준. 본 프로젝트의 `RagService` 검색 코드 폴백 우선순위(`content` 필드)에 맞춰 컬럼 설계. 앱 측 코드 변경 없음(빌드/재기동 불필요).

### CSV 스펙
- 컬럼: `id`/`category`/`title`/`content`(필수 4) + `tags`/`source`/`severity`/`created_at`(선택)
- `content`가 BM25 검색 본문이자 LLM 컨텍스트 주입 대상 — 메타정보(버전·태그)는 본문에 자연어로 녹임
- 한 row = 한 청크(800~1500자 권장). 인덱싱 시점 자동 청킹 없음

### 산출물
- `rag-data/rag-knowledge-20260430.csv` 총 24행:
  - `heap_analysis` 7건 (Old Gen OOM × G1/CMS+Parallel, Metaspace OOM, Direct ByteBuffer OOM, Leak 3종 — Cache/ThreadLocal/Static)
  - `java_spring` 8건 (Spring Session JDBC, Actuator heapdump, RestTemplate pool, Hibernate N+1, JVM 메모리 영역, ClassLoader 누수, Thread Dump 해석, JMX)
  - `mat_tip` 5건 (Leak Suspects, OQL, Path To GC Roots, Dominator Tree, Histogram vs Class Loader Explorer)
  - `troubleshooting` 4건 (모두 가상 예시 — `source`에 명시. 사내 인시던트로 교체 권장)
- `rag-data/README.md` — CSV 컬럼 스펙·작성 가이드·ES 매핑 예시·등록 후 검증 절차
- 참고 plan 파일: `/root/.claude/plans/rag-csv-melodic-yeti.md`

### 등록 후 검증 절차
`/settings/rag`에서 keyword 모드·textField=content로 Save → Test Connection → 검색 프로브로 CSV 본문 매칭 확인 → `/ai-chat`에서 RAG ON으로 답변 품질 검증.

## [2026-04-30] 계정 관리 — 보호 동작에 안내 모달 적용

**변경 파일:** `admin/users.html`, `CHANGELOG.md`

### 배경
기존에는 (1) `admin` 사용자의 [삭제] 버튼과 (2) 본인 세션의 [강제 종료] 버튼이 `disabled` 상태로 비활성화돼 있어, 왜 클릭이 안 되는지 사용자에게 명확하지 않았음. 클릭은 가능하되 안내 모달로 사유와 대안을 제시하도록 개선.

### 수정 내용
- **공용 `infoModal` + `showInfoModal(title, messageHtml)` 헬퍼 신설** — 단순 안내 전용. [확인] 버튼만 노출. 두 보호 동작에서 재사용.
- **사용자 목록 `admin` 삭제** — `th:disabled` 제거, `th:data-admin="${u.username == 'admin'}"` 추가, 클릭 시 `onDeleteUserClick(this.dataset)` 호출. admin이면 모달로 "관리자(admin) 계정은 삭제할 수 없습니다. 시스템 운영을 위해 기본 관리자 계정은 보호됩니다." 안내. 그 외 기존 삭제 확인 모달 그대로.
- **현재 접속 — 본인 세션 강제 종료** — `disabled + title` 제거, `onclick="showSelfTerminateInfo()"`로 변경. 모달로 "본인 세션은 강제 종료할 수 없습니다. 로그아웃이 필요하다면 좌측 메뉴의 **Logout**을 이용해 주세요." 안내. (서버 측 본인 세션 종료 거부는 그대로 유지 — `DELETE /api/admin/active-sessions/{id}` 400 응답)

### 동작 검증
- 빌드/기동 성공 (`Started HeapAnalyzerApplication in 9.542 seconds`)
- admin 사용자의 삭제 버튼 클릭 → 안내 모달 노출 (DELETE API 호출되지 않음)
- 본인 세션의 강제 종료 클릭 → 안내 모달 노출

## [2026-04-30] 계정 신청(Self-signup) 기능 + 관리자 승인 흐름

**변경 파일:** `model/entity/AccountRequest.java`(신규), `repository/AccountRequestRepository.java`(신규), `service/AccountRequestService.java`(신규), `controller/AccountRequestController.java`(신규), `service/UserService.java`, `config/SecurityConfig.java`, `templates/login.html`, `templates/admin/users.html`, `CHANGELOG.md`

### 배경
관리자가 모든 계정을 직접 생성해야 하는 운영 부담을 줄이기 위해, 비로그인 사용자가 직접 계정을 신청할 수 있도록 하고 관리자 승인을 거쳐 계정이 활성화되는 흐름을 도입.

### A. 데이터 모델 — `account_requests` 테이블 (자동 생성, ddl-auto=update)
- 컬럼: id / username / password(BCrypt 인코딩 저장) / display_name / reason / status(PENDING/APPROVED/REJECTED) / requested_at / processed_at / processed_by / reject_reason / request_ip
- 인덱스: status, username, requested_at
- 비밀번호는 **신청 시점에 BCrypt 인코딩**해 저장. 승인 시 재인코딩 없이 `users.password`로 그대로 복사 → 평문이 DB에 남지 않음.

### B. `UserService` 리팩토링
- `validatePassword(String)` private → **public static**으로 추출 (AccountRequestService에서 동일 검증 재사용)
- `validateUsername(String)` 신설 — 3~50자, `[A-Za-z0-9_.-]`만 허용
- `createUserWithEncodedPassword(...)` 신설 — 이미 BCrypt 인코딩된 비밀번호로 사용자 생성 (승인 흐름 전용)
- `existsByUsername(String)` 노출 — service 레이어에서 사용자명 중복 검사

### C. `AccountRequestService`
- `submit(username, password, displayName, reason, ip)` — 사용자명 형식·비밀번호 정책·users 중복·기존 PENDING 중복 검사 → BCrypt 인코딩 후 저장
- `list(status, q, pageable)` — Specification 동적 쿼리 (LoginHistory 패턴 그대로)
- `approve(id, role, approverUsername)` — 트랜잭션 내에서 (1) 사용자 생성 (2) 신청 상태 APPROVED 마킹. 동시 신청자 동일 username 충돌 방어 위해 다시 한 번 `existsByUsername` 검사
- `reject(id, reason, approverUsername)` — REJECTED 마킹 + 사유 기록
- `deleteRequest(id)` — APPROVED/REJECTED만 삭제 가능 (PENDING은 거부 처리해야 함)

### D. `AccountRequestController` — REST 엔드포인트
- **공개**: `POST /api/account-requests` — 비로그인에서 호출. SecurityConfig `permitAll`. CSRF는 `/api/admin/`이 아니므로 기본 면제 정책 적용.
- **ADMIN**: `GET /api/admin/account-requests` (목록, status·q·페이징, pendingCount 동시 반환), `GET /pending-count`, `POST /{id}/approve`(role 지정), `POST /{id}/reject`(reason), `DELETE /{id}`
- 클라이언트 IP는 `X-Forwarded-For` → `X-Real-IP` → `request.getRemoteAddr()` 순으로 결정

### E. `SecurityConfig`
- `antMatchers(POST, "/api/account-requests").permitAll()` 추가 (login 페이지에서 호출 가능)
- `/api/admin/account-requests/**`는 기존 `hasRole("ADMIN")` + CSRF 보호 적용 룰에 자연스레 포함

### F. `login.html` — "계정 신청" 모달
- 로그인 버튼 아래 "계정이 없으신가요? **계정 신청**" 링크 추가
- 모달: 사용자명 / 비밀번호 / 비밀번호 확인 / 이름 / 신청 사유
- 비밀번호 일치 검사(클라이언트), 신청 진행 중 버튼 disabled, 성공 시 토스트 후 자동 닫힘
- 인라인 모달 스타일 추가 (`.modal-ov`, `.modal-box`, `.modal-input`, `.modal-textarea`, `.modal-msg`), 모바일 폭(≤480px)에서 풀폭에 가깝게 + 버튼 flex:1
- ESC 키로 모달 닫기

### G. `admin/users.html` — 4번째 탭 "계정 신청"
- 탭 라벨에 **빨간 펜딩 카운트 배지** (`.tab-badge`, 0이면 회색 zero 클래스). 페이지 로드 시 `/api/admin/account-requests/pending-count`로 즉시 갱신.
- 기본 필터 "대기 중"(PENDING)으로 진입. 검색(사용자명/이름) + 상태 필터 + 페이지 사이즈 + ↻ 새로고침
- 컬럼: # / 사용자명 / 이름 / 사유(말줄임 + hover wrap) / 신청 시각 / IP / 상태(뱃지) / 처리자·시각·거부사유 / 작업
- 작업 버튼: PENDING이면 [승인] [거부], 그 외 [삭제]
- 승인 모달: 부여 역할(USER/ADMIN) 선택. 거부 모달: 사유 입력(선택)
- 모바일 미디어쿼리: `#panel-requests .utable { min-width: 1040px }` (가장 컬럼 많음) + 기존 `.table-scroll` 좌우 스크롤 패턴 활용

### 동작 검증
- 빌드/기동 성공 (`Started HeapAnalyzerApplication in 9.643 seconds`)
- `account_requests` 테이블 ddl-auto로 자동 생성 (DESCRIBE로 11컬럼 확인)
- POST `/api/account-requests` 케이스 3종 검증
  - 정상 신청 → `{"success":true,"id":1}`, DB에 PENDING + BCrypt 인코딩(`$2a$10$...`) 저장 확인
  - 약한 비밀번호 → "비밀번호는 최소 8자 이상이어야 합니다."
  - 기존 username(admin) 중복 → "이미 사용 중인 사용자명입니다."
- 검증 후 테스트 행 삭제 완료

## [2026-04-30] Accounts(계정 관리) 모바일 레이아웃 + 좌우 스크롤

**변경 파일:** `admin/users.html`, `CHANGELOG.md`

### 배경
계정 관리 페이지(`/admin/users`)의 3개 탭(사용자 목록 / 현재 접속 / 접속 이력) 모두 `.panel { overflow: hidden }` 위에 직접 `<table class="utable">`만 있어, 모바일 폭에서 컬럼이 압축되거나 잘렸음. 좌우 스크롤도 불가능. 또 탭 메뉴, 페이지 헤더, toolbar, 모달, 토스트 등이 모바일 폭 대응이 빈약했음.

### 수정 내용
- **3개 테이블 좌우 스크롤** — 각 `<table class="utable">`을 `.table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch }` 래퍼로 감쌈. `.panel`의 `overflow: hidden`은 그대로 유지(border-radius 보존).
- **테이블 min-width** — 모바일에서 컬럼이 뭉개지지 않도록 `≤900px`에 `min-width: 760px` 기본, `#panel-active .utable`(현재 접속, 8컬럼)은 `920px`로 더 크게 지정. 좁은 화면에선 자연스레 가로 스크롤 노출.
- **탭 메뉴 가로 스크롤** — `≤768px`에서 `.tabs { overflow-x: auto; flex-wrap: nowrap; white-space: nowrap }`, `.tab { flex: 0 0 auto }` — 탭 3개가 좁아도 스크롤로 접근.
- **페이지 헤더 / 사용자 추가 버튼** — `≤640px`: `.page-hdr { flex-direction: column; align-items: stretch }`, 추가 버튼 / 자동 갱신 컨트롤 width 100%.
- **접속 이력 toolbar** — `≤640px`: 검색바·셀렉트·새로고침 모두 width 100% 세로 스택.
- **페이저 wrap** — `≤768px`: `.lh-pager { flex-wrap: wrap }` — 페이지 버튼 다수일 때 줄바꿈.
- **모달 / 토스트 모바일 대응** — `≤640px`: 모달 `max-width: calc(100vw - 24px)`, 모달 버튼 `flex: 1`로 폭 균등. 토스트는 `left/right: 12px; transform: none`으로 풀폭 가운데.
- **Container/Topbar 패딩 축소** — `≤768px`에서 container 좌우 14px, topbar 12px, 페이지 타이틀 16px.

### 동작 검증
- 빌드/기동 성공 (`Started HeapAnalyzerApplication in 9.462 seconds`)
- 데스크톱(≥1024px)에서는 기존 레이아웃 동일 — 새 룰은 모두 `max-width:768px/900px/640px` 미디어쿼리 안에만 정의

## [2026-04-30] Transfer Logs 모바일 레이아웃 + 좌우 스크롤

**변경 파일:** `server-logs.html`, `CHANGELOG.md`

### 배경
모바일/태블릿 폭에서 Transfer Logs 페이지의 테이블 컬럼이 압축되어 가독성 저하. 또한 `.panel { overflow: hidden }`만 정의되어 있고 모바일 오버라이드가 없어 컬럼이 화면 밖으로 나가도 좌우 스크롤이 불가능했음 (files.html / history.html에는 이미 적용된 패턴 누락).

### 수정 내용
- **테이블 좌우 스크롤** — 테이블을 `.table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch }` 래퍼로 감싸 모바일에서 가로 스와이프 가능. `.panel`의 `overflow: hidden`은 유지(border-radius 보존).
- **테이블 min-width** — 모바일에서 컬럼이 뭉개지지 않도록 `max-width: 900px`에서 `min-width: 880px`, `max-width: 640px`(원격 경로 컬럼 숨김)에서 `min-width: 720px` 지정. 화면이 좁으면 자연스레 스크롤바 노출.
- **Toolbar 모바일 정리** — `≤640px`: 검색바/셀렉트/Export 버튼 폭 100%로 stretch, `.page-size-wrap` justify-content space-between, Export 메뉴 좌우 0으로 펼침.
- **Pagination 모바일** — `≤640px`: flex-direction column, 정보/버튼 그룹 가운데 정렬, 페이지 버튼 wrap.
- **Topbar/Container 패딩** — `≤768px`에서 container 좌우 14px, topbar 패딩 12px로 축소. 페이지 타이틀 16px.

### 동작 검증
- 빌드/기동 성공 (`Started HeapAnalyzerApplication in 9.122 seconds`)
- 데스크톱(1280px)에서는 기존과 동일 (max-width 900px 미만에서만 적용되는 룰)
- 모바일 폭에서 테이블 가로 스크롤 가능 + 툴바/페이지네이션 세로 스택

## [2026-04-29] 활성 세션 모니터링 + 강제 로그아웃

**변경 파일:** `AdminController.java`, `AuthEventListener.java`, `admin/users.html`, `CHANGELOG.md`

### 배경
관리자가 현재 누가 시스템에 접속해 있는지 한눈에 파악하고, 의심 세션을 즉시 종료할 수 있어야 함. SPRING_SESSION 테이블이 이미 있으니 별도 인프라 없이 구현 가능.

### A. AuthEventListener 수정 — `InteractiveAuthenticationSuccessEvent`로 교체
- **문제 발견**: `AuthenticationSuccessEvent`는 `ProviderManager`에서 인증 검증 *직후* 발행되는데, 그 시점에는 Spring Security 기본값인 세션 고정 보호(session fixation protection)가 아직 새 세션을 발급하기 전. 결과적으로 login_history에 저장된 session_id가 SPRING_SESSION의 세션 ID와 달라 IP/UA 매칭 실패.
- **수정**: `InteractiveAuthenticationSuccessEvent`로 교체. 이는 `AbstractAuthenticationProcessingFilter#successfulAuthentication()` 안에서 sessionStrategy(세션 고정 보호) 적용 *이후* 발행되므로 현재 활성 세션 ID와 일치.
- 검증: 패치 후 신규 세션은 활성 세션 목록에서 IP/UA/loginAt 모두 정상 매칭 확인.

### B. `AdminController` — 활성 세션 API
- `JdbcTemplate` + `FindByIndexNameSessionRepository` 의존성 주입 (`@Autowired(required=false)` — Spring Session 미사용 환경에서 NPE 방지)
- `GET /api/admin/active-sessions` — SPRING_SESSION + login_history 최신 SUCCESS 행 + users 테이블 LEFT JOIN. 만료된 세션(`EXPIRY_TIME <= NOW`)은 제외. 현재 요청 세션은 `isCurrent=true`로 표시. 응답 필드: sessionId/username/displayName/role/ip/userAgent/createdAt/lastAccessAt/expiresAt/loginAt/idleSec/maxInactiveSec/isCurrent
- `DELETE /api/admin/active-sessions/{sessionId}` — Spring Session의 `sessionRepository.deleteById()` 우선 사용 (속성 테이블까지 정합성 유지). 미주입 환경 폴백: 자식(`SPRING_SESSION_ATTRIBUTES`) → 부모(`SPRING_SESSION`) 순서로 직접 DELETE.
- **본인 세션 종료 거부**: 현재 요청의 `request.getSession(false).getId()`와 비교 → 일치하면 400 + "본인의 세션은 강제 종료할 수 없습니다." 메시지

### C. `admin/users.html` — "현재 접속" 탭 추가
- 탭 3개로 확장: 사용자 목록 / **현재 접속** / 접속 이력
- 활성 세션 테이블 — 사용자(이름+role+UA) / IP / 로그인 시각 / 마지막 활동 / 유휴(`fmtIdle()`로 한글 변환: "5분 32초") / 만료 예정 / 작업
- 본인 세션 행: "본인" 뱃지 + 강제 종료 버튼 disabled (title="본인 세션은 종료할 수 없습니다")
- 강제 종료: 확인 모달 → DELETE API → 토스트 + 목록 즉시 갱신
- **자동 갱신 옵션**: 체크박스 ON 시 10초 간격 폴링. 다른 탭 이동 시 자동 OFF (불필요한 폴링 방지)
- 첫 진입 시 lazy-load (`_asLoaded` 플래그)

### 동작 검증 (2026-04-29 16:43 재시작 후)
1. admin + test 두 사용자 동시 로그인 → 활성 세션 5개 표시 (이전 세션 포함)
2. 신규 세션의 IP/UA/loginAt 정상 매칭 확인 (`InteractiveAuthenticationSuccessEvent` 효과)
3. admin이 test 세션 강제 종료 → `{success:true}`
4. 종료 직후 test 세션으로 `/api/system/status` 호출 → HTTP 302 → `/login` (세션 무효화 확인)
5. admin이 본인 세션 종료 시도 → `{success:false, message:"본인의 세션은 강제 종료할 수 없습니다."}`

### 보안
- ADMIN 전용 (클래스 레벨 `@PreAuthorize("hasRole('ADMIN')")`)
- DELETE 요청 CSRF 토큰 필수 (`/api/admin/**`은 CSRF 보호 유지 — Phase 1에서 이미 설정)
- 세션 ID 비교는 서버 측에서만 수행 (클라이언트 isCurrent 플래그는 UI 힌트, 실제 거부 결정은 백엔드)

## [2026-04-29] 로그인 페이지 새로고침 시 메시지 잔존 문제 개선

**변경 파일:** `login.html`, `CHANGELOG.md`

### 배경
로그인 실패 시 Spring Security가 `/login?error=true`로 리다이렉트 → 서버가 "아이디 또는 비밀번호가 올바르지 않습니다." 메시지 렌더. URL에 `?error=true`가 남아 있어 사용자가 브라우저 새로고침(F5)을 해도 같은 메시지가 계속 표시되는 UX 문제.

### 변경 내용
`login.html` 최상단 인라인 스크립트에 `history.replaceState()` 추가. 페이지 로드 직후 `?error` 또는 `?logout` 쿼리스트링을 발견하면 즉시 URL을 `/login`(쿼리 없음)으로 교체.

```javascript
if (location.search.indexOf('error') >= 0 || location.search.indexOf('logout') >= 0) {
    window.history.replaceState({}, document.title, location.pathname);
}
```

### 효과
- 첫 진입(로그인 실패 직후): URL은 `/login?error=true`로 들어오지만 즉시 `/login`으로 교체. 메시지는 DOM에 그대로 표시되어 사용자에게 정상 노출
- 새로고침: 브라우저가 현재 URL bar의 `/login`(쿼리 없음)을 다시 요청 → 서버는 `errorMessage` 모델 추가하지 않음 → 깨끗한 로그인 화면
- 로그아웃 메시지(`?logout=true`)도 동일 패턴으로 처리
- 백엔드 변경 없음 (Flash attribute 도입 시 `AuthenticationFailureHandler` 커스터마이즈 필요했으나, 클라이언트 정리만으로 동일 UX 달성)

## [2026-04-29] 로그인 접속 이력 기록 + 계정 관리 탭 UI

**변경 파일:** `LoginHistory.java`(신규), `LoginHistoryRepository.java`(신규), `AuthEventListener.java`(신규), `AdminController.java`, `admin/users.html`, `CHANGELOG.md`

### 배경
보안 감사/오용 추적을 위해 로그인 시도 내역을 영속화하고, 관리자가 한 화면에서 사용자 목록과 함께 접속 이력을 확인할 수 있는 통합 UI 필요.

### A. 신규 엔티티 / 리포지토리
- `LoginHistory` — `login_history` 테이블 매핑. 컬럼: id, username, login_at, ip, user_agent, status(SUCCESS/FAILURE), session_id, failure_reason
- 인덱스 3개: `idx_login_history_username`, `idx_login_history_login_at`, `idx_login_history_status` — 검색/시간 정렬/상태 필터 모두 인덱스 활용
- `LoginHistoryRepository extends JpaRepository, JpaSpecificationExecutor` — 동적 쿼리 + 페이지네이션

### B. 인증 이벤트 리스너 (`AuthEventListener`)
- Spring Security `AuthenticationSuccessEvent` / `AbstractAuthenticationFailureEvent` 수신
- `RequestContextHolder`로 현재 HttpServletRequest 추출 → IP / User-Agent / 세션 ID 기록
- IP는 `X-Forwarded-For` / `X-Real-IP` 등 프록시 헤더 우선 검사 후 `getRemoteAddr()` 폴백 (콤마 구분 시 첫 값만)
- 모든 컬럼 길이 안전 truncate, 기록 실패 시 로그인 흐름 영향 없도록 try/catch
- 실패 이벤트는 BadCredentialsException, DisabledException 등 모두 포함

### C. 조회 API (`AdminController`)
- `GET /api/admin/login-history?page&size&q&status` — ROLE_ADMIN 전용
- 검색(q): `username` OR `ip` (소문자 LIKE), 상태 필터: SUCCESS/FAILURE, 페이지 사이즈 1~200 클램프
- JPA `Specification` 동적 쿼리 + `PageRequest.of(.., Sort.by(DESC, "loginAt"))`
- 응답: `{items, page, size, totalElements, totalPages}` — server-logs 페이지와 동일 구조

### D. UI — `admin/users.html`
- 상단 탭 2개: "사용자 목록" / "접속 이력" (active 클래스 + 파란 underline)
- 접속 이력 패널:
  - 툴바: 검색(사용자/IP, 300ms debounce), 상태 셀렉트(전체/성공/실패), 페이지 사이즈(20/50/100), 새로고침
  - 테이블: # / 일시 / 사용자명 / 상태(뱃지) / IP / User Agent or 실패 사유 (`title` 속성으로 풀 텍스트 툴팁)
  - 페이지네이션: ‹이전 / 1 … 현재±2 … 마지막 / 다음› (server-logs 패턴 재사용)
  - 첫 진입 시 lazy-load (`_lhLoaded` 플래그) — 사용자 목록 탭만 보는 경우 불필요한 API 호출 방지
- XSS 방어: 모든 표시 텍스트 `escHtml()` 적용

### 동작 검증 (2026-04-29 16:27 재시작 후)
- `login_history` 테이블 자동 생성 (인덱스 3개 포함)
- 잘못된 비밀번호 로그인 → FAILURE 1건, "자격 증명에 실패하였습니다." 사유 기록
- 정상 로그인 → SUCCESS 1건, session_id까지 기록
- IP는 IPv6 localhost(`0:0:0:0:0:0:0:1`) / 외부 접속자(`112.218.186.181`) 모두 정상 캡처
- `/api/admin/login-history?status=FAILURE` → HTTP 200, 실패 건만 반환
- `/admin/users` → HTTP 200, 탭 전환 정상

### 보안
- 모든 `/api/admin/**` 경로는 SecurityConfig에서 ROLE_ADMIN 강제 + CSRF 보호 유지
- 비밀번호는 어떤 경우에도 기록되지 않음 (실패 사유는 Spring 메시지만 저장)

## [2026-04-29] 로그인 세션 MariaDB 영속화 (Spring Session JDBC)

**변경 파일:** `pom.xml`, `application.properties`, `CHANGELOG.md`

### 배경
지금까지는 Tomcat 메모리에만 세션이 저장되어 ① 앱 재시작 시 모든 사용자가 강제 로그아웃 ② 향후 다중 노드 운영 시 세션 공유 불가능 한계 존재. 이미 사용 중인 MariaDB(HEAPDB)에 세션을 영속화하여 두 문제를 동시에 해결.

### 변경 내용
- **`pom.xml`** — `spring-session-jdbc` 의존성 추가 (Spring Boot 2.7 BOM이 버전 관리)
- **`application.properties`** — 신규 "세션 관리" 블록 추가:
  - `server.servlet.session.timeout=60m` — 무동작 만료 60분
  - `spring.session.store-type=jdbc` — JDBC(MariaDB) 저장소 사용
  - `spring.session.jdbc.initialize-schema=always` — 기동 시 테이블 자동 생성/검증
  - `spring.session.jdbc.schema=classpath:org/springframework/session/jdbc/schema-mysql.sql` — MariaDB 호환 (MySQL 스크립트 재사용)
  - `spring.session.jdbc.cleanup-cron=0 */10 * * * *` — 만료 세션 10분 주기 정리
- 동시 세션 제한 **미설정** (단일 사용자 다중 브라우저/탭 허용)

### 자동 생성 테이블 (MariaDB HEAPDB)
- `SPRING_SESSION` — 세션 메타데이터 (PRIMARY_ID, SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL=3600, EXPIRY_TIME, PRINCIPAL_NAME)
- `SPRING_SESSION_ATTRIBUTES` — 세션 속성 직렬화 (BYTEA → MariaDB BLOB)
- 인덱스: `SPRING_SESSION_IX1(SESSION_ID 유니크)`, `SPRING_SESSION_IX2(EXPIRY_TIME)`, `SPRING_SESSION_IX3(PRINCIPAL_NAME)` — 만료 정리 + 사용자별 조회 성능 보장

### 동작 검증 (2026-04-29 12:07 재시작)
- 기동 로그: `Started HeapAnalyzerApplication in 9.109 seconds` (정상)
- 테이블 자동 생성 확인: `SHOW TABLES LIKE 'SPRING_SESSION%'` → 2개 모두 존재
- admin 로그인 후 `SELECT * FROM SPRING_SESSION` → 1행, `MAX_INACTIVE_INTERVAL=3600`, `PRINCIPAL_NAME=admin`, 만료 시각 = 마지막 접근 + 60분
- 기존 `ai_chat_sessions` 테이블과 충돌 없음 (서로 다른 도메인)

### 운영 효과
- 앱 재시작에도 로그인 유지 (MariaDB 재시작 시에만 강제 로그아웃)
- 향후 다중 노드 배포 시 세션 공유 자동 동작 (sticky session 불필요)
- 활성 세션을 SQL로 직접 모니터링 가능 (`SELECT PRINCIPAL_NAME, FROM_UNIXTIME(LAST_ACCESS_TIME/1000) FROM SPRING_SESSION`)
- 트레이드오프: 매 요청 시 `UPDATE LAST_ACCESS_TIME` 발생 (MariaDB 부하 약간 증가, 단일 인덱스 갱신 수준)

## [2026-04-27] RAG (Elasticsearch) 연동 — Phase 2 (Semantic Search 활성화)

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `HeapDumpAnalyzerService.java`, `EmbeddingService.java`(신규), `RagService.java`, `HeapDumpController.java`, `rag-settings.html`, `RAG_PHASE2_PLAN.md`, `MEMORY.md`

### 배경
Phase 1(2026-04-26)에서 `keyword(BM25)`만 동작. 운영팀 ES 예시 쿼리 부재 확인 후, 두 가지 semantic 모드를 모두 구현하여 사내 ES 매핑에 따라 Settings에서 선택 가능하게 활성화.

### 신규 검색 모드
- **`semantic-server`** — ES 서버측 임베딩. 두 가지 쿼리 타입 지원:
  - `text_expansion` (ELSER 스파스 토큰): `{ text_expansion: { <tokensField>: { model_id, model_text } } }`
  - `semantic` (ES 8.11+ semantic_text): `{ semantic: { field, query } }`
- **`semantic-client`** — 앱이 외부 임베딩 API 호출 후 kNN 쿼리: `{ knn: { field, query_vector, k, num_candidates } }`

### A. 신규 설정 키 (`application.properties`)
- semantic-server: `rag.search.semantic.{query-type, model-id, tokens-field, semantic-field}`
- semantic-client: `rag.embedding.{provider, api.url, api.key(ENC), model, dimension, timeout-seconds}`, `rag.search.knn.{vector-field, num-candidates}`

### B. `EmbeddingService` 신규
- `embed(text, overrides)` — provider별 분기 (openai / cohere / custom-OpenAI호환)
- `testConnection(overrides)` — 임의 텍스트("test") 임베딩 호출 후 dimension 반환
- API 키는 평문 메모리 보관 + `ENC(...)` AES-256 형식으로 `settings.json` / `application.properties` 저장 (Phase 1 password 패턴 재사용)
- Bearer 인증 (openai/cohere/custom 모두 동일)
- OpenAI/Cohere 응답 파싱: `data[0].embedding[]` vs `embeddings[0][]`

### C. `RagService` 확장
- 생성자에 `EmbeddingService` 주입
- `buildQueryBody(mode, ..., overrides)` switch 분기: `keyword` / `semantic-server` / `semantic-client`
- `semantic-client`은 `embeddingService.embed(query, overrides)` 호출 → `query_vector` 배열 변환 후 kNN 쿼리 본문 구성
- `text_expansion`은 `model-id` 필수, `semantic`은 `semantic-field` 필수 — 누락 시 명확한 에러 (Phase 1처럼 keyword로 폴백하지 않음)

### D. `HeapDumpAnalyzerService` 영속화 확장
- volatile 필드 12개 추가 (semantic 4 + embedding 8)
- `settings.json` load/save에 신규 키 12개 + ENC 암호화 (`ragEmbeddingApiKey`)
- `syncApplicationProperties()` 갱신
- 신규 setter 2개:
  - `setRagSemanticConfig(queryType, modelId, tokensField, semanticField)` — null 인자는 변경 없음
  - `setRagEmbeddingConfig(provider, apiUrl, apiKey, model, dim, timeout, vectorField, numCandidates)` — apiKey null=유지/빈문자열=삭제 패턴 (Phase 1과 동일)
- `getRagEmbeddingApiKeyMasked()`, `isRagEmbeddingApiKeySet()`

### E. Controller API 확장
- `GET /api/settings/rag` 응답에 `semantic`/`embedding` 객체 + `availableSemanticQueryTypes`/`availableEmbeddingProviders` 추가
- `POST /api/settings/rag` 페이로드에 semantic + embedding 필드 수용. 키 누락 시 변경 없음 (containsKey 가드)
- `POST /api/settings/rag/embedding/test` (신규) — 임베딩 API 연결 테스트. 빈 apiKey는 저장 키로 대체

### F. UI (`rag-settings.html`)
- Phase 1 안내 배너 → Phase 2 안내 배너로 교체 (3개 모드 설명)
- Search Mode select에 `onRagModeChange()` 핸들러 — 선택 모드에 따라 두 신규 카드 동적 노출
  - **semantic-server 카드**: queryType select(text_expansion/semantic) + 동적 박스(`semElserBox`/`semSemanticBox`)
  - **semantic-client 카드**: provider/api-url/api-key/model/dimension/timeout/vector-field/num-candidates 입력 + Test Embedding API 버튼
- API key 입력은 비어있으면 페이로드에서 제외 (저장 값 유지). placeholder hint로 마스킹 표시
- `buildConnPayload(includeSecrets)` — Phase 2 필드 모두 포함. `embeddingApiKey`는 includeSecrets=true이고 입력값 있을 때만 전달
- `testEmbeddingConn()` — 별도 테스트 결과 박스(`#embTestResult`)에 dimension 표시

### G. 보안
- 임베딩 API 키 ENC 저장: 평문은 메모리만, settings.json + application.properties 양쪽 모두 `ENC(...)` 형식
- UI는 마스킹 placeholder만 표시, 변경 시에만 입력하도록 유도 (Phase 1 password와 동일 UX)
- 잘못된 설정으로 인한 ES 호출 실패 시 keyword 폴백하지 않고 명확한 에러 — 디버깅 용이

### H. 테스트 가이드
- 사내 운영팀 ES 매핑 확인 후 모드 선택:
  1. ELSER 사용 시: `semantic-server` + `text_expansion` + 운영팀이 알려준 `model-id` / `tokens-field`
  2. semantic_text 사용 시: `semantic-server` + `semantic` + `semantic-field`
  3. dense_vector + 외부 임베딩 API 사용 시: `semantic-client` + provider/model/vector-field 설정. **반드시 색인 시 모델과 동일** 사용
- Settings 페이지에서 모드 변경 → Save → Test Connection 순서로 검증
- semantic-client는 추가로 "Test Embedding API"로 임베딩 호출만 별도 검증 가능

### I. `RAG_PHASE2_PLAN.md`
- 운영팀 답변 부재로 양 모드 모두 구현했음을 기록. 체크리스트 항목 모두 완료 표시.

## [2026-04-26] 사내 이관용 README-DEPLOY.md 작성 (DB 준비 가이드 중심)

**변경 파일:** `README-DEPLOY.md` (신규)

### 배경
사내 시스템 이관 시 "JAR 파일만 가져가면 되는지" 질의에 답하면서, 외부 의존성(MAT CLI, MariaDB, 디렉토리, SSH 계정, LLM/RAG 연동 등)을 함께 정리할 설치 가이드 필요.

### 내용
- **DB 준비 섹션 중심** — MariaDB 설치, 외부 접속 허용(`bind-address` + 방화벽), `HEAPDB` 생성 + `heap_user` 계정 + `GRANT` SQL, 접속 테스트(`mysql -h`), `heap_enc.sh`로 비밀번호 AES 암호화, `application.properties` 수정 예시, JPA `ddl-auto=update`로 자동 생성되는 7개 테이블 목록(users / target_servers / analysis_history / dump_transfer_log / ai_insights / ai_chat_sessions / ai_chat_messages), `mysqldump` 백업/복구 + cron 자동 백업
- **권한 최소화 옵션**: 운영 시 `GRANT ALL` 대신 `SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER/DROP/INDEX/REFERENCES`만 부여 가능함을 명시
- **암호화 키 설정**: `HEAP_ANALYZER_ENCRYPTION_KEY` 환경변수로 별도 키 지정 권장 (기본 키는 운영 부적합)
- **나머지 섹션**: 사전 준비(OS/Java/포트), 디렉토리 권한(`/opt/heapdumps/{dumpfiles,data,tmp}`), MAT CLI 설치, 앱 기동(`restart.sh`), 트러블슈팅 표(접속 실패/권한/한글 깨짐/`ENC()` 복호화 실패), 이관 패키지 체크리스트
- **기본 admin 계정 변경 강조** — admin/shinhan@10 자동 생성 후 즉시 변경 권고

## [2026-04-26] Transfer Logs 페이지 고도화 — 검색/필터/페이지네이션/Export

**변경 파일:** `DumpTransferLogRepository.java`, `ServerController.java`, `server-logs.html`, `CLAUDE.md`

### 배경
기존 `/servers/logs`는 서버별 아코디언 + 서버당 50개 하드코딩 컷 + 검색/정렬/페이지네이션 전무. 운영자가 "어제 어떤 파일이 실패했지"를 찾으려면 서버를 하나씩 펼치며 눈으로 스캔해야 했음. 누적 데이터 특성상 시간이 지나면 오래된 이력은 영구히 가려짐.

### A. 백엔드
- `DumpTransferLogRepository`: `JpaSpecificationExecutor<DumpTransferLog>` 추가 → 동적 검색·정렬·페이지네이션 가능
- `ServerController`:
  - `serverLogsPage(Model)` 단순화: `logsByServer`/`statsByServer` 모델 키 제거. `servers`(셀렉트박스용)만 SSR. 데이터는 클라이언트 fetch
  - `GET /api/servers/transfers` (신규) — `Page<TransferLogItem>` 반환. 파라미터: `page, size, sort, q, status, serverId`. JPA Specification으로 동적 쿼리 (`q`는 filename/remotePath/errorMessage OR 매칭, `cb.like(cb.lower(...), %q%)`). 정렬 필드 화이트리스트(`isAllowedSortField`) — 임의 필드 정렬 차단
  - `GET /api/servers/transfers/stats` (신규) — KPI 3개(total/success/failed). status는 무시하고 `q + serverId`만 적용 — Total과 Success/Failed 비교 의미 유지
  - `GET /api/servers/transfers/export?format=csv|json` (신규) — 현재 필터 그대로 적용된 결과 다운로드. 안전 cap 50,000건, 초과 시 `X-Truncated: true` 헤더. CSV는 RFC 4180 인용 (`csvCell`로 `,`/`"`/줄바꿈 escape)
  - Inner DTO `TransferLogItem` (HeapDumpController.AnalysisHistoryItem 패턴): id, serverId, serverName, filename, remotePath, transferStatus, fileSize+formattedSize, startedAt+startedAtMillis, completedAt, durationMs+formattedDuration, errorMessage
  - 서버명 조인은 `serverRepository.findAll()` Map lookup (JPA `@ManyToOne` 미도입 — 스키마 변경 회피)
  - Helper: `parseSort()`, `buildSpec()`, `statusEquals()`, `formatBytes()`, `formatDuration()`, `renderCsv()`, `renderJson()`

### B. 프론트엔드 (`server-logs.html` 전면 재작성)
- 아코디언 폐기 → **단일 통합 테이블** (서버 칼럼 추가)
- 상단 KPI 3카드: Total / Success / Failed (파란/녹색/빨강 아이콘. `index.html`의 stat-card 패턴 차용)
- 툴바: 검색 input(300ms debounce) + 상태 셀렉트(전체/SUCCESS/FAILED/IN_PROGRESS) + 서버 셀렉트(Thymeleaf로 채움) + 페이지 사이즈 셀렉트(20/30/50/100, `localStorage.logsPageSize`) + Export 드롭다운(CSV/JSON)
- 테이블 9칼럼: # / 상태 뱃지 / 서버(링크) / 파일명 / 원격경로(col-hide-sm) / 크기 / 시작 / 소요시간 / 에러
- 정렬 가능 헤더: id, transferStatus, filename, fileSize, startedAt (asc↔desc 토글, 화살표 인디케이터). 서버명·소요시간·에러는 의도적으로 정렬 비활성 (DB 계산 어려움 / 의미 적음)
- 페이지네이션: ‹Prev / 1 … 현재±2 … 마지막 / Next›, history.html 알고리즘 그대로 0-base 페이지로 변환
- 검색 결과 0건/전체 0건 분기 메시지
- IN_PROGRESS는 소요시간 "진행 중" 표시
- 모바일 반응형: 768px 이하 KPI 콤팩트, 640px 이하 KPI 1열 + 원격경로 숨김 + 툴바 세로 스택

### C. CLAUDE.md
- `server-logs.html` 항목을 갱신 (3 KPI / 단일 통합 테이블 / 서버 사이드 페이지네이션 / 3 endpoint)
- "Files/History 테이블 툴바 공통 패턴" 섹션 직전에 메모 추가: server-logs는 의도적으로 서버 사이드 페이지네이션 사용 — 누적형 데이터 특성 반영. history/files(클라이언트 사이드 그리드)와 다름

### 검증
- 빌드 성공 (`mvn clean package -DskipTests`)
- `/servers/logs` HTTP 200, KPI/검색/필터/페이지네이션 마크업 26개 매치
- `/api/servers/transfers?page=0&size=5` → totalElements=9, totalPages=2, 서버명 join + 포맷팅 정상
- `/api/servers/transfers/stats` → `{total:9, success:9, failed:0}`
- 검색 `q=admin` → 4건, 상태 `status=SUCCESS` → 9건, 정렬 `fileSize,asc` 정상
- Export CSV/JSON 모두 필터 적용된 응답 + Content-Disposition 헤더

---

## [2026-04-26] RAG 전용 페이지 분리 + 청킹 옵션 추가

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `HeapDumpAnalyzerService.java`,
`RagService.java`, `HeapDumpController.java`, `settings.html`, `rag-settings.html` (신규),
`fragments/banner.html`

### A. RAG 설정을 General 페이지 → 전용 페이지로 분리
- 신규 라우트 `GET /settings/rag` → `rag-settings.html` (LLM Configuration 페이지 패턴 재사용)
- `settings.html` (General)에서 RAG 카드/모달/JS 일괄 제거 → 링크 카드 1개로 대체 (LLM Configuration 카드와 동일 형태). 카드 클릭 시 `/settings/rag`로 이동
- 배너 Servers/Settings 서브메뉴 패턴 따라 Settings 서브메뉴에 `RAG Configuration` 항목 추가 (`fragments/banner.html`). `gb-nav-sub.open` max-height: 120px → 200px (4 → 5 항목 수용)
- 배너 active 처리: `/settings/rag` 경로 시 `gbNavSettingsRag` 활성화

### B. RAG 청킹 (post-retrieval) 옵션
- 신규 설정: `rag.chunking.{enabled,strategy,size,overlap,max-chunks-per-doc,max-total-chars}`
  - `strategy`: `fixed`(고정 길이 + overlap) | `paragraph`(빈 줄 단위) | `sentence`(문장 단위 + overlap)
  - `size`: 청크 최대 글자수 (기본 800, 100~8000 검증)
  - `overlap`: 인접 청크 중복 글자수 (기본 120, fixed/sentence에서 적용. size보다 작아야 함)
  - `max-chunks-per-doc`: 한 문서당 최대 청크 수 (기본 3, 1~20)
  - `max-total-chars`: LLM 컨텍스트 전체 글자수 한도 (기본 6000, 500~50000)
- `HeapDumpConfig` `@Value` 6개 + `HeapDumpAnalyzerService` `volatile` 필드 + 영속화(settings.json + application.properties 동기화) — 기존 RAG 패턴 재사용
- `setRagChunkingConfig()` 메서드: 입력값 범위 검증 + persist
- `RagService.fetchContextForLlm()`에 청킹 통합:
  - 청킹 ON: `chunkText(text, strategy, size, overlap, maxChunks)` 호출 → 자료별 청크를 `--- 자료 N.M ---` 헤더로 구분
  - `maxTotalChars` 한도 누적 체크 — 한도 초과 시 break (첫 청크가 한도를 넘으면 잘라서라도 일부 주입)
- `chunkText()` 구현:
  - **fixed**: `step = size - overlap`로 슬라이딩 윈도우 분할
  - **paragraph**: `\n{2,}` 빈 줄 분할 후 size 한도 내에서 머지, 단일 문단이 size 초과 시 fixed 폴백
  - **sentence**: `(?<=[\.!?。？！])\s+` 정규식으로 한국어/영문 문장 종결자 인식. tail overlap 유지
  - 모든 전략에서 `maxChunks` 초과 시 절단

### C. API
- `GET /api/settings/rag` 응답에 `chunking` 객체 + `availableChunkingStrategies` 배열 추가
- `POST /api/settings/rag/chunking` 신규 — 청킹 옵션 단독 저장 (연결 설정과 분리되어 있어 배포 후 청킹만 튜닝 가능)

### D. RAG Configuration 페이지 (`rag-settings.html`)
- 카드 구성: Enable RAG / Connection (URL, Index, SSL Verify) / Authentication (none|basic|api-key, 동적 필드 토글) / Search (Mode, Text Field, Top-K, Min Score, Timeout) / **Chunking** (Enable, Strategy, Size, Overlap, Max per Doc, Max Total) / Save All + Test Connection + Reload
- "Save All" 버튼: 연결 설정 + 청킹 옵션을 두 API에 순차 PUT (체이닝). 두 번째 실패 시 첫 번째는 이미 저장된 상태 — 실패 메시지로 알림
- "Test Connection" 버튼: 현재 폼 값으로 `/api/settings/rag/test` 호출 → cluster name/status, index 존재 여부 인라인 표시
- 비밀번호/API Key 입력은 빈 값이면 페이로드에서 제외 → 백엔드가 "기존 값 유지"로 해석 (저장된 마스킹 안내 표시)
- Phase 1 안내 배너: "Keyword(BM25)만 동작, Semantic 모드는 Phase 2 — RAG_PHASE2_PLAN.md 참고"
- CSRF 메타 태그 + `getCsrfHeaders()` 헬퍼 (다른 페이지 패턴 일관성)
- 입력 검증: URL 빈 값 거부, overlap >= size 거부 (toast)

### 검증
- 빌드 성공 (`mvn clean package -DskipTests`)
- `GET /api/settings/rag` → `chunking` 객체 정상 반환
- `POST /api/settings/rag/chunking` → settings.json + application.properties 양쪽 영속화 (paragraph/1000/150/4/8000 적용 확인)
- `/settings/rag` HTML 렌더링 — 모든 chunking 입력 필드 + 청킹 마커 12개 매치
- `/settings` General 페이지 — RAG 모달/JS 잔존 0, 링크 카드만 노출

---

## [2026-04-26] RAG (Elasticsearch) 연동 — Phase 1 (Keyword/BM25 + Settings UI)

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `HeapDumpAnalyzerService.java`,
`RagService.java` (신규), `HeapDumpController.java`, `AiChatController.java`, `settings.html`

### 배경
사내 운영 환경 ES를 RAG로 연동 가능하도록 Settings 화면에 ES 접속/검색 설정 추가. 사내 ES 매핑(임베딩 사전 색인 여부, inference endpoint 보유 여부)을 운영팀에 확인하기 전 단계라, 호환성 가장 높은 **Keyword(BM25) 모드를 Phase 1**로 구현. Semantic 모드(server-side text_expansion / client-side kNN)는 운영팀 답변 후 Phase 2에서 활성화 예정.

### A. 백엔드 설정/영속화 (LLM 설정 패턴 재사용)
- `application.properties` 신규 키: `rag.enabled`, `rag.elasticsearch.{url,auth-type,username,password,api-key,index,ssl-verify}`, `rag.search.{mode,text-field,top-k,min-score,timeout-seconds}`. 비밀번호/API Key는 `ENC(...)` AES-256-CBC 암호화 형식 (`AesEncryptor`).
- `HeapDumpConfig`: `@Value`로 13개 RAG 필드 + getter 추가
- `HeapDumpAnalyzerService`: `volatile` 런타임 필드 + 생성자 초기화(저장된 ENC 자동 복호화) + `loadPersistedSettings`/`persistSettings`/`syncApplicationProperties`에 RAG 항목 추가. 설정 저장 시 password/apiKey는 `encryptForStorage()`로 ENC(...) 변환 후 settings.json + application.properties에 동시 기록. `setRagConfig()`는 password/apiKey가 null이면 기존 값 유지 (마스킹된 폼 재제출 시 비밀 보존)

### B. RagService 신규 클래스
- `RagService.java` (신규): ES 검색 + 연결 테스트 전담. HeapDumpAnalyzerService의 런타임 getter를 통해 설정값 읽음
- `fetchContextForLlm(query)`: 활성화 시 검색 후 LLM 시스템 프롬프트에 `[참고 자료 (RAG)]` 형식으로 주입. 비활성/실패/빈 결과 시 빈 문자열 — 호출자는 무조건 안전하게 사용 가능
- `search(query, overrides)`: `POST {url}/{index}/_search` BM25 `match` 쿼리 (Phase 1). semantic-* 모드 선택 시 keyword 폴백 + 경고 로그
- `testConnection(overrides)`: `GET /_cluster/health` + `HEAD /{index}` 검증. cluster name/status, indexExists 반환
- 인증: `none` / `basic` (Authorization: Basic base64) / `api-key` (Authorization: ApiKey ...)
- SSL 검증 비활성 옵션 (자체 서명 인증서 사내 환경 대응) — `HttpsURLConnection` + 모든 cert 신뢰 TrustManager
- 검색 결과: `_score` >= minScore 필터 → preferredField → content/body/text/message 폴백

### C. API 엔드포인트
- `GET  /api/settings/rag` — 현재 설정 조회 (password/apiKey는 마스킹 + Set 플래그)
- `POST /api/settings/rag` — 설정 일괄 저장. password/apiKey는 키 자체가 없거나 null이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 갱신
- `POST /api/settings/rag/enabled` — 활성화 토글 단독
- `POST /api/settings/rag/test` — 연결 테스트 (request body의 overrides 우선, 누락 시 저장된 값 사용 → 저장 전 검증 가능)
- `POST /api/settings/rag/search` — 검색 프로브 (디버깅/검증용)

### D. LLM 채팅 흐름 통합
- `AiChatController.streamChat()`: 마지막 user 메시지로 `ragService.fetchContextForLlm()` 호출 → systemPrompt 끝에 추가
- `HeapDumpController.aiChat()` (legacy): 동일 패턴
- `HeapDumpController.aiChatStream()` (legacy): 동일 패턴
- 원샷 분석(`/api/llm/analyze`)은 prompt 자체가 분석 결과 데이터라 RAG 검색 키 부적합 → Phase 1에서는 미적용

### E. Settings 페이지 UI (DB 카드 패턴 재사용)
- 신규 카드 `RAG (Elasticsearch)`: ON/OFF 토글 + URL/Index/Mode/Auth/Top-K 요약 + "설정 변경" 버튼. ON 시 파란 뱃지
- 신규 모달 `ragEditModal`: URL/Index/Mode/Text Field/Top-K/Min Score/Timeout/Auth(none|basic|api-key)/Username/Password/API Key/SSL Verify. 인증 방식 변경 시 `onRagAuthChange()`로 관련 박스만 표시
- Phase 1 안내 박스: "Keyword(BM25) 모드만 동작. Semantic 모드는 사내 ES 매핑 확인 후 활성화 예정"
- "연결 테스트" 버튼: 현재 폼 값으로 `/api/settings/rag/test` 호출 → cluster name/status + index 존재 여부 인라인 표시 (성공: 녹색 / 경고: 노랑 / 실패: 빨강)
- 비밀번호/API Key 입력은 빈 값이면 페이로드에서 제외 → 백엔드가 "기존 값 유지"로 해석 (마스킹된 placeholder로 안내)
- `loadAllData()` → `loadRagSettings()` 추가, 설정 변경 후 자동 갱신

### 검증
- 빌드 성공 (`mvn clean package -DskipTests`)
- `GET /api/settings/rag` → 기본값 정상 반환
- `POST /api/settings/rag` → settings.json + application.properties 양쪽에 ENC(...) 형식 비밀번호 영속화 확인
- 마스킹 응답: `passwordMasked: "****23"` (마지막 2자리만 노출)
- `POST /api/settings/rag/test` (가짜 host) → `[UnknownHostException]` 명확한 에러 응답
- Settings 페이지 HTML 렌더링 — RAG 카드 + 모달 정상 노출

---

## [2026-04-26] 로그인 화면 사용자명 저장 기능 추가

**변경 파일:** `login.html`

- 로그인 폼에 "사용자명 저장" 체크박스 추가 (비밀번호 필드와 로그인 버튼 사이)
- `localStorage` 키 `heap_login_username`으로 username만 영속 (비밀번호는 저장하지 않음 — 보안)
- 페이지 로드 시 저장된 username 자동 채움 + 체크박스 자동 체크 + 포커스를 password 필드로 이동(사용성)
- 폼 submit 시 체크 상태에 따라 `setItem`/`removeItem` 분기. localStorage 차단 환경 대비 try/catch로 폴백 — 저장 실패해도 로그인 진행
- CSS: `.form-remember` (16px 체크박스, accent-color blue, 480px 이하 모바일에서 12px 폰트)

---

## [2026-04-21] 분석 페이지 플로팅 AI 채팅 대화 히스토리 복원 + LLM 설정 토글

**변경 파일:** `HeapDumpAnalyzerService.java`, `HeapDumpController.java`,
`application.properties`, `llm-settings.html`, `analyze.html`

### A. 플로팅 챗 대화 복원
- 분석 페이지 FAB 플로팅 채팅에서 같은 덤프(filename)에 대한 이전 AI 대화를 자동 복원
- 트리거: 채팅 패널 최초 open 시 `restoreChatHistory()` 호출 — `GET /api/ai-chat/sessions?filename=xxx` (본인 소유, updatedAt desc 정렬)로 최근 세션 1개 자동 바인딩, `GET /api/ai-chat/sessions/{id}/messages`로 대화 로드
- 신규 함수 (analyze.html): `restoreChatHistory()`, `loadChatMessages()`, `renderRestoredMessage()`, `appendContextResetNotice()`, `extractChatDate()`, `extractChatTime()`, `appendChatDateSep()`, `_parseChatDateTime()`
- 시나리오 분기:
  - 세션 0건 → 기존 welcome (첫 전송 시 세션 생성)
  - 세션 1건+ → 가장 최근 세션 복원, `_aiChatSessionId` 자동 설정 → `ensureChatSession()`이 재사용해 추가 메시지도 같은 세션에 이어짐
- 날짜 구분선 (오늘/어제/YYYY년 M월 D일) + 메시지별 시간 (오전/오후 H:MM) 표시 — `ai-chat.html` 패턴 재사용
- "초기화" 버튼 → "새 대화" 라벨 변경 + `_aiChatRestoreAttempted=true` 유지로 재복원 차단 (사용자 의도 존중, 과거 세션은 DB 보존)
- "새 대화" 버튼 클릭 시 **확인 모달** 노출 (`newChatModal`): "이전 대화는 DB에 보존 / 다음 메시지 전송 시 새 세션 생성" 안내 문구 + Cancel / 새 대화 시작 버튼
- 로딩 스피너, 인증 만료/네트워크 실패 시 welcome 폴백, `encodeURIComponent(FILENAME)` 적용
- 메시지 DB 저장 로직 무변경 — 기존 `/stream` 엔드포인트가 user/assistant 자동 저장 (기존)

### B. LLM 설정 — 이전 대화 컨텍스트 포함 토글
- 신규 설정 `llmChatRestoreIncludeHistory` (default: true, 기존 동작 호환)
  - ON: 복원된 이전 대화를 `_aiChatMessages`에 로드 → AI가 기억 (`trimChatMessages()` 24000자 컷 자동 적용)
  - OFF: UI에만 렌더, 컨텍스트는 빈 배열 → AI는 신규 대화로 인식. "— 여기까지 이전 대화 (AI는 기억하지 않음) —" 구분선 표시로 혼란 방지
- 신규 API: `POST /api/llm/chat-restore-mode {includeHistory: bool}`
- `/api/settings` 응답에 `llm.chatRestoreIncludeHistory` 필드 추가
- 설정 영속화: `settings.json` (`llmChatRestoreIncludeHistory`) + `application.properties` (`llm.chat.restore-include-history`) 양방향 동기화 — 기존 패턴 재사용
- LLM 설정 페이지(`/settings/llm`)에 "Chat History Restoration" 카드 + 토글 스위치 추가, 상태별 설명 문구 동적 전환, 토글 실패 시 원복
- 분석 페이지 Thymeleaf 모델에 `llmChatRestoreIncludeHistory` 주입 → JS 전역 `LLM_CHAT_RESTORE_INCLUDE_HISTORY`로 복원 로직이 조건 분기
- 모드 전환은 다음 페이지 로드 시 반영 (진행 중 대화 중단 방지)

---

## [2026-04-21] 분석 페이지 다이어그램 클릭 → Component Detail 모달 연결

**변경 파일:** `analyze.html`

- Overview 패널 4개 다이어그램 모두에서 클래스 클릭 시 Top Consumers 행 클릭과 동일한 `componentDetailModal` 열림
  - Memory Treemap 셀
  - Stacked Bar 세그먼트 및 Legend 항목
  - Pie Chart 슬라이스 및 범례(legend)
  - 수평 Bar Chart 막대
- 데이터 매칭: `OBJ_NAMES[i]`의 i가 Top Consumers `s.index`와 동일하므로 별도 역매칭 없이 `showComponentDetail(className, i)` 재사용
- `buildTreemap()` items / `buildStackedBar()` segments에 `index`/`clickable` 속성 추가 — "Others" 집계 셀은 `clickable:false`로 클릭 비활성 (cursor:default)
- Chart.js 다이어그램: `options.onClick` + `onHover`로 cursor pointer 자동 전환. Pie는 `pieClassCount` 경계로 "Others" 인덱스 차단
- 시각적 피드백: clickable 셀에 `cursor:pointer`, native `title="클릭하여 상세 보기"`, 커스텀 툴팁 하단에 힌트 텍스트 추가
- 모바일 touch → click 합성 호환: 클릭 시 `treemapTooltip` 명시 숨김 처리로 잔존 방지
- 모달/백엔드 수정 없음 — 호출부만 추가. parsed → raw → histogram fallback 체인이 모든 다이어그램에서 그대로 동작

---

## [2026-04-21] CLAUDE.md 갱신 (최근 변경 반영)

**변경 파일:** `CLAUDE.md`

- 통합 변경 반영: Comparison 네비, Bulk-delete API, 페이지네이션·정렬·다중선택 공통 패턴, deleted 가시성 제어, DB id 기반 순번, 모바일 Chat 탭, 채팅 커서 placeholder, 대시보드 intro 카드, Recent Files 카운터
- HeapDumpController 엔드포인트 목록에 bulk-delete API + Authentication/isAdmin 추가
- files/history 페이지 설명을 새 툴바 패턴 기준으로 갱신 (status dot 제거)
- compare.html을 picker 모드 포함하여 갱신
- Key Design Decisions에 9개 신규 패턴 추가

---

## [2026-04-21] AI 답변 대기 중 커서 깜빡임 수정

**변경 파일:** `ai-chat.html`, `analyze.html`

- 말풍선이 생성되었지만 첫 chunk가 아직 도착하지 않은 시점(초기 placeholder)에서 커서가 깜빡이지 않던 문제 수정
- 원인: 초기 cursor를 `<span style="color:#9CA3AF">▌</span>` (정적) 로 표시 후, 첫 chunk 도착 시에만 `chat-cursor`/`ai-chat-cursor` 클래스(애니메이션) 적용
- 수정: 초기 placeholder도 깜빡임 클래스(`chat-cursor` / `ai-chat-cursor`) 사용
- 적용 위치: AI Chat 페이지(`sendMessage`), 분석 페이지 플로팅 채팅(`createStreamBubble`)

---

## [2026-04-21] 정렬 기본값 변경 + 모바일 AI Chat 세션 탭 추가

**변경 파일:** `history.html`, `files.html`, `fragments/banner.html`, `ai-chat.html`

- **정렬 기본값**: 날짜 오름차순 → 내림차순(최신 → 오래된)으로 양 페이지 통일
- **모바일 AI Chat 채팅 목록 접근성 개선**:
  - 좌측 배너 모바일 탭에 "Chat" 탭 신설 (`data-tab="chat"`, 채팅 아이콘)
  - `body.has-chat-tab` 클래스로 ai-chat 페이지에서만 노출
  - banner.html에 `gbMobileChat` 슬롯 + `registerBannerChatTab()` 함수 추가
  - ai-chat.html: `DOMContentLoaded`에서 `.session-sidebar`를 cloneNode하여 배너 슬롯 등록 (내부 ID 제거로 충돌 방지)
  - `getElementById('sessionList'/'sessionFilter')` → `querySelectorAll('.session-list'/'.session-filter select')`로 전환, 원본+클론 동시 갱신
  - 필터 셀렉트는 `onFilterChange(this)` + `syncFilterValue()`로 양 인스턴스 동기화
  - 세션 클릭 시 모바일에서는 `closeMobileBanner()`로 배너 자동 닫고 채팅 본문 노출
  - `#gbMobileChat .session-sidebar`는 풀-블리드 표시되도록 CSS 오버라이드

---

## [2026-04-21] History/Files 테이블 헤더 클릭 정렬 기능

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- **DTO 확장**: `AnalysisHistoryItem`에 raw 바이트 필드 추가 (`sizeBytes`, `originalSizeBytes`, `compressedSizeBytes`, `heapUsedBytes`)
- `buildHistory()`에서 위 값들을 entity/file로부터 채움
- **공통 정렬 동작**:
  - 헤더 클릭 시 오름차순 ↔ 내림차순 토글, 다른 헤더 클릭 시 asc부터
  - 활성 헤더에 ▲/▼ 인디케이터 (파란색), 비활성은 흐린 ▲
  - 정렬 후 검색/필터/페이지네이션 자동 재적용 (`sortRows()` → `applyFilter()`)
  - 한글 정렬: `localeCompare(s, 'ko')`
- **History 정렬 칼럼**: # (id), 결과(status), 파일명, 분석 시간, 힙 사용량, Suspects, 파일 크기, 서버, 날짜
- **Files 정렬 칼럼**: # (id), 파일명, 원본 크기, 압축 크기, AI 인사이트(severity rank), 날짜
- **기본 정렬: 날짜 오름차순** (오래된 → 최신, 양 페이지 동일)
- raw 숫자값은 `data-sort-*` 속성으로 행에 직렬화 후 클라이언트 정렬

---

## [2026-04-21] 대시보드 Analysis Files에서 deleted 항목 항상 숨김

**변경 파일:** `HeapDumpController.java`

- `index()` 메서드에서 `buildHistory()` 결과를 stream filter로 `fileDeleted=true` 제외
- 모든 계정(관리자 포함) 동일하게 적용
- `totalFileCount`, `hasMoreFiles`, `analyzedCount`, `totalSuspects` 등 파생 값도 자동으로 deleted 제외 기준
- History/Files 페이지는 기존 정책 유지 (관리자 토글로 표시 가능)

---

## [2026-04-21] History/Files 다중 선택 일괄 삭제 기능

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- **신규 API**:
  - `POST /api/history/bulk-delete` — body `{filenames:[], deleteHeapDump:bool}` → 응답 `{success, failed, errors}`
  - `POST /api/files/bulk-delete` — body `{filenames:[]}` → 응답 동일
- **공통 UI 패턴 (history.html, files.html)**:
  - 검색바 우측에 "선택" 토글 버튼. 활성화 시 파란색 배경
  - 활성 시 좌측 첫 칼럼에 체크박스 표시 (CSS `select-mode` 클래스로 토글)
  - 헤더 체크박스로 현재 페이지 표시 행 일괄 선택/해제 (indeterminate 상태 지원)
  - 하단 고정 액션 바: "N건 선택됨 | 취소 | 선택 삭제"
  - 일괄 삭제 모달에서 확인 → API 호출 → 페이지 새로고침
- **History 모달**: "힙덤프 파일도 함께 삭제" 옵션 체크박스 (이미 deleted된 항목은 자동 건너뜀)
- **Files 모달**: 살아있는 파일은 `/api/files/bulk-delete` (heap dump 삭제), deleted 항목은 `/api/history/bulk-delete` (분석 기록 purge)로 자동 분리 호출
- 페이지/필터 이동 시에도 선택 카운트 자동 갱신 (`render()`에 훅)
- 부분 실패 시 실패 건수 + 에러 메시지를 alert로 표시

---

## [2026-04-21] deleted 기록 관리자 전용 표시/삭제 기능

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- **서버 측 필터링**: 비관리자에게는 `fileDeleted=true` 항목을 응답에서 제외 (보안)
- `historyPage()`/`filesPage()`에 `Authentication` 파라미터 + `isAdmin` 모델 속성 추가
- `isAdmin()` 헬퍼 메서드 추가 (ROLE_ADMIN 검사)
- **관리자 전용 토글**: 검색바 우측에 "deleted 표시" 체크박스 (기본 off, `localStorage` 기억)
  - history.html: `historyShowDeleted`, files.html: `filesShowDeleted`
  - `applyFilter()`가 `data-deleted="true"` 행을 토글 상태에 따라 제외/포함
- **History 페이지**: `deletedCount` 통계 추가 (관리자가 보는 경우만 표시)
- **Files 페이지**: deleted 행에도 관리자 전용 영구 삭제(purge) 버튼 추가
  - `confirmPurge()` → 별도 모달 → POST `/history/delete/{filename}`
- `/history/delete/{filename}` 엔드포인트: Referer 기반 리다이렉트 (`/files`에서 호출 시 `/files`로 복귀)

---

## [2026-04-21] Recent Files 카운터 표시 개선

**변경 파일:** `index.html`

- 사이드바 "Recent Files (12)" 라벨이 실제 표시 개수(5개로 제한)와 불일치하던 문제 수정
- 전체가 5건 이하: "Recent Files (3)" — 단일 숫자
- 전체가 5건 초과: "Recent Files (5 / 12)" — 표시 / 전체 형태
- `fileCount`(전체)와 `#lists.size(files)`(서버에서 잘린 표시 개수)를 함께 사용

---

## [2026-04-21] Quick Actions 정리 및 Compare/Export 동선 재배치

**변경 파일:** `HeapDumpController.java`, `compare.html`, `fragments/banner.html`, `history.html`, `index.html`

- Compare Two Dumps → 좌측 네비게이션 "Comparison" 항목으로 이동
  - `/compare` 컨트롤러: `base`/`target` 파라미터 옵셔널화. 미입력 시 파일 선택 화면 노출
  - `compare.html`: `/api/history`에서 SUCCESS 분석 이력 fetch → base/target 셀렉트 자동 채움 + Compare 버튼
  - banner: History 다음에 "Comparison" 링크 + collapsed icon 추가
- Export History → History 페이지 topbar로 이동
  - `history.html` topbar에 Export 버튼 + 모달 + 다운로드 JS (기존 `/api/history` 재사용)
- Settings → Quick Actions에서 제거 (좌측 네비에 이미 존재)
- `index.html` Quick Actions 섹션 통째로 제거. 관련 모달(`#compareModal`, `#exportHistoryModal`) 및 JS(`openCompare`, `closeCompare`, `startCompare`, `exportHistory`, `closeExportHistory`, `doExportHistory`, Esc 키 핸들러) 정리

---

## [2026-04-21] 대시보드 상단에 앱 소개 문구 추가

**변경 파일:** `index.html`

- `.main-content` alerts 영역 직후, disk-warn-banner 직전에 intro 카드 삽입
- 문구: "Eclipse MAT 기반 분석기. 원격 서버에서 덤프를 자동 수집하고, 누수 의심 대상을 요약하며, AI 로 근본 원인을 찾아냅니다."
- info SVG 아이콘 + 그라데이션(#EFF6FF→#F5F3FF) + 1px #DBEAFE 테두리
- `.dashboard-intro` CSS 신설, `@media (min-width:1024px)`에서 14px / `@media (max-width:900px)`에서 12px·padding 축소
- 신규 사용자에게 도구 가치를 즉시 전달, 빈 상태에서도 항상 노출 (`th:if` 없음)

---

## [2026-04-21] Analysis History/Files 순번을 DB id 기반으로 변경

**변경 파일:** `HeapDumpController.java`, `history.html`, `files.html`

- 행 시프트 문제 해결: 표시 인덱스(`#lists.size - iter.index`) → DB `analysis_history.id` (영구 식별자)
- 행을 삭제해도 다른 행의 번호가 변하지 않음. 같은 레코드는 항상 같은 번호로 식별
- `AnalysisHistoryItem` DTO에 `Long id` 필드 추가, `buildHistory()`에서 `e.getId()`로 채움
- `history.html`: `${h.id}` (모든 항목이 분석 이력이라 항상 존재)
- `files.html`: `${h.id != null ? h.id : '-'}` — NOT_ANALYZED 파일은 DB 레코드 없으므로 `-` 표시

---

## [2026-04-21] Files 페이지에 페이지네이션 + 순번 칼럼 + 상태 인디케이터 제거

**변경 파일:** `files.html`

- `상태` dot 칼럼 + 범례(legend) 영역 제거 (`.hdot`, `.hd-*`, `.legend*`, `.ld-*` CSS 정리)
- 신규 `#` 순번 칼럼 추가 (오래된=1, 최신=마지막). 식: `${#lists.size(analysisHistory) - iter.index}`
- 검색바 우측에 "행 표시" 콤보박스 (20/30/50/100, 기본 20, `localStorage('filesPageSize')` 저장)
- 패널 하단 페이지네이션 바 (전체 ≤ 페이지 크기 시 자동 숨김), ‹ Prev / 슬라이딩 윈도우 / Next ›
- 기존 `filterFiles()` → `applyFilter() + render() + renderPagination()` 구조

---

## [2026-04-21] Analysis History 페이지네이션 + 행 표시 갯수 선택 기능

**변경 파일:** `history.html`, DB seed (analysis_history)

- 더미 데이터 30건 DB 삽입 (`dummy_dump_01..30.hprof`, SUCCESS 24/ERROR 6 비율, `file_deleted=true`, 30일에 걸친 `analyzed_at`)
- 테이블 우측 상단 "행 표시" 콤보박스 추가 (20/30/50/100, 기본 20, `localStorage` 저장)
- 패널 하단 페이지네이션 바 추가 (전체 건수가 페이지 크기 초과 시 자동 활성화)
- 페이지 버튼: ‹ Prev, 1 … 현재±2 … 마지막, Next ›. 활성 버튼 파란 배경
- 검색(`onSearchInput`)과 페이지 크기 변경(`onPageSizeChange`)은 항상 1페이지로 리셋
- 기존 `filterItems()` → `applyFilter()` + `render()` + `renderPagination()` 구조로 리팩터링

---

## [2026-04-21] Analysis History 순번 채번 방향 변경 (오래된=1, 최신=마지막)

**변경 파일:** `history.html`

- 목록은 최신 정렬(`analyzedAt DESC`)이지만 순번은 오래된 데이터부터 1로 시작하도록 변경
- 표현식 변경: `${iter.count}` → `${#lists.size(analysisHistory) - iter.index}`
- 결과: 상단(최신)이 가장 큰 번호, 하단(오래된)이 1번

---

## [2026-04-21] Analysis History 테이블에 순번 칼럼 추가 및 상태 인디케이터 제거

**변경 파일:** `history.html`

- `상태` 칼럼(컬러 dot)을 제거 (직전 변경에서 추가된 `결과` 뱃지 칼럼이 동일 정보 제공)
- 신규 `#` 순번 칼럼 추가 (`th:each` 변수에 `iter` 추가, `${iter.count}` 사용)
- `.td-seq` CSS 추가 (`width: 48px; text-align: center; tabular-nums`)
- `.hdot`, `.hd-ok`, `.hd-err`, `.td-status` CSS 제거

---

## [2026-04-21] Analysis History 테이블에 결과 칼럼 분리

**변경 파일:** `history.html`

- 파일명 칼럼에 인라인으로 표시되던 `success`/`failed`/`deleted` 뱃지를 별도 "결과" 칼럼으로 분리
- `<thead>`에 `<th class="td-result">결과</th>` 추가 (상태 칼럼 다음 위치)
- `.td-result` CSS 추가 (`width: 84px; white-space: nowrap;`)
- 결과를 한눈에 정렬된 형태로 확인 가능, 파일명은 깔끔하게 표시

---

## [2026-04-14] 분석 실행 시 배너 System Status 자동 갱신

**변경 파일:** `progress.html`

- 분석 시작(첫 RUNNING 수신), 완료(COMPLETED), 에러(ERROR) 시 `refreshBannerStatus()` 호출 추가
- 분석 중 디스크 사용량, JVM 메모리, 분석 큐 상태가 배너에 실시간 반영

---

## [2026-04-14] Accounts 페이지 레이아웃 가로 폭 확대

**변경 파일:** `admin/users.html`

- 컨테이너 `max-width` 1100px → 1400px로 변경

---

## [2026-04-14] AI 채팅 세션 생성 시 인증 만료 에러 처리

**변경 파일:** `analyze.html`, `ai-chat.html`

- 로그인 세션 만료 상태에서 채팅 시 "Unexpected token '<'" JSON 파싱 에러 발생하던 문제 수정
- 원인: 세션 만료 시 API가 로그인 페이지 HTML(302 리다이렉트)을 반환, 이를 JSON으로 파싱 시도
- analyze.html `ensureChatSession()`: 응답 Content-Type 및 리다이렉트 체크 추가
- ai-chat.html: 공통 `checkAuth()` 함수 추가, 세션 생성/스트리밍 fetch에 적용
- 인증 만료 시 "로그인이 만료되었습니다. 페이지를 새로고침 해주세요." 안내 메시지 표시

---

## [2026-04-14] AI 채팅 메시지 DB 저장 안정성 보강

**변경 파일:** `AiChatController.java`

- 스트리밍 콜백(onDone)에서 assistant 메시지 저장 실패가 `catch (Exception ignored)`로 무시되던 문제 수정
- Assistant 메시지 저장에 3회 재시도 로직 추가 (500ms 간격)
- User/Assistant 메시지 저장 성공/실패 로그 추가 (`[AI-Chat-Stream]` prefix)
- onChunk/onError/스레드 에러 등 모든 catch 블록에 로그 추가
- 클라이언트에 저장 성공 여부 전달 (`done` 이벤트에 `saved` 필드 추가)

---

## [2026-04-14] Raw Data 패널 (System Overview / Top Components / Suspect Details) iframe 로드 수정

**변경 파일:** `SecurityConfig.java`, `analyze.html`

- Raw Data 패널의 iframe이 로드되지 않던 버그 수정
- 원인 1: Spring Security 기본값 `X-Frame-Options: DENY`가 iframe 내 페이지 표시를 차단
- 수정 1: `SecurityConfig`에 `.headers().frameOptions().sameOrigin()` 추가하여 같은 도메인 iframe 허용 (clickjacking 보호 유지)
- 원인 2: `!iframe.src` 조건에서 브라우저가 `"about:blank"`를 반환하면 lazy-load 미실행
- 수정 2: `!iframe.getAttribute('src')`로 변경하여 HTML 속성 기반으로 판단
- iframe의 `sandbox="allow-same-origin allow-scripts"` 속성 제거 (보안 경고 해소 + 불필요한 제약 제거)

---

## [2026-04-14] 분석 페이지 좌측 배너 AI 인사이트 완료 표시 수정

**변경 파일:** `analyze.html`

- AI 인사이트 완료/분석중/실패 상태가 좌측 배너에 반영되지 않던 버그 수정
- 원인: 사이드바가 배너에 `cloneNode`로 복제되어 `id="aiNavStatus"` 요소가 2개 존재, `getElementById`는 첫 번째(원본)만 업데이트
- 수정: `querySelectorAll('.ai-nav-status')`로 원본 + 복제본 모두 업데이트하도록 변경

---

## [2026-04-14] AI Chat 날짜 구분선 및 시간 표시 추가

**변경 파일:** `ai-chat.html`

- 메시지 목록에서 날짜가 바뀌는 시점에 날짜 구분선 표시 (오늘/어제/YYYY년 M월 D일)
- 각 메시지 버블 하단에 시간 표시 (오전/오후 H:MM 형식)
- 새 메시지 전송 시에도 날짜 구분선 및 시간 자동 표시
- 스트리밍 응답 완료 시 assistant 메시지에도 시간 표시

---

## [2026-04-14] Target Server 상세 페이지 추가

**변경 파일:** `ServerController.java`, `server-detail.html` (신규), `servers.html`

- `/servers/{id}` 상세 페이지 신규 추가
  - 서버 정보 카드: 호스트, SSH 계정, 덤프 경로, 상태, 자동탐지, 마지막 확인/에러
  - 분석 이력 테이블: 해당 서버에서 전송된 힙덤프의 분석 결과 (상태, 파일명, Heap 사용량, Suspects, 분석 시간)
  - 전송 이력 테이블: SCP 전송 로그 (상태, 파일명, 원격 경로, 크기, 시간, 에러)
  - 연결 테스트 / 스캔 액션 버튼
- ServerController에 `AnalysisHistoryRepository`, `DumpTransferLogRepository` 의존성 추가
- 존재하지 않는 서버 ID 접근 시 `/servers`로 리다이렉트 (flash error)
- servers.html에서 서버 이름을 클릭 가능한 링크(`/servers/{id}`)로 변경

---

## [2026-04-14] Favicon 추가 및 앱 전체 아이콘 도넛 차트로 통일

**변경 파일:** `favicon.svg` (신규), `SecurityConfig.java`, `banner.html`, `login.html`, 전체 템플릿 13개

- 간결한 도넛 차트 스타일 SVG favicon 추가 (파란 배경 + 흰색 단일 세그먼트 + 중앙 점)
- 모든 HTML 템플릿(13개)에 `<link rel="icon" type="image/svg+xml" href="/favicon.svg">` 삽입
- Spring Security에 `/favicon.svg` 경로 인증 면제 추가 (로그인 페이지에서도 표시)
- 배너 헤더 로고 (gb-header-logo) 막대 차트 → 도넛 차트 아이콘으로 변경
- 로그인 페이지 로고 (login-logo-icon) 막대 차트 → 도넛 차트 아이콘으로 변경

---

## [2026-04-14] History 삭제 시 DB 레코드 미삭제 버그 수정

**변경 파일:** `AnalysisHistoryRepository.java`, `HeapDumpAnalyzerService.java`

- History 페이지에서 이력 삭제 시 `analysis_history` DB 레코드가 삭제되지 않아 테이블에 계속 표시되던 문제 수정
- `AnalysisHistoryRepository`에 `deleteByFilename()` 메서드 추가
- `HeapDumpAnalyzerService.deleteHistory()`에 DB 레코드 삭제 로직 추가 (analysis_history + ai_insights)
- `@Transactional` 어노테이션 추가로 삭제 트랜잭션 보장
- Files 페이지에서도 삭제된 이력의 분석 완료 상태가 정상적으로 반영됨

---

## [2026-04-13] 모바일 배너에 Analysis 탭 추가

**변경 파일:** `banner.html`, `analyze.html`

- 모바일 배너 드로어에 Analysis 탭 추가 (Navigation | Analysis 전환)
- `analyze.html`에서 사이드바(Heap Statistics, Analysis, AI Analysis, Actions, Tools, Raw Data, File Info)를 배너 Analysis 탭에 동적 복제/주입
- Analysis 탭 내 패널 선택 시 배너 자동 닫기 + 해당 패널 표시
- Upload 탭과 동일한 동적 슬롯 패턴: `registerBannerAnalysisTab()` 함수, `body.has-analysis-tab` CSS 클래스

---

## [2026-04-13] 모바일 Files/History 테이블 가로 스크롤 수정

**변경 파일:** `files.html`, `history.html`

- `.panel { overflow: hidden }` 때문에 모바일에서 테이블이 잘리고 가로 스크롤이 불가능했던 문제 수정
- 900px 이하에서 `.panel { overflow-x: auto; -webkit-overflow-scrolling: touch }` 추가
- 640px 이하에서 테이블 `min-width` 지정 (files: 500px, history: 600px) — 터치 스크롤 보장

---

## [2026-04-13] 모바일 배너 드로어 + Navigation/Upload 탭 추가

**변경 파일:** `banner.html`, `index.html`, `analyze.html`, `history.html`, `files.html`, `settings.html`, `compare.html`, `servers.html`, `server-logs.html`, `admin/users.html`, `ai-chat.html`, `llm-settings.html`

- 모바일(900px 이하)에서 좌측 배너를 슬라이드인 드로어로 동작하도록 변경 (기존 `display: none` → 오프스크린 드로어)
- 배너 상단에 Navigation/Upload 모바일 전용 탭 추가. 기본 탭은 Navigation
- Navigation 탭: System Status, 페이지 네비게이션(Dashboard, Files, History, AI Chat, Servers, Settings), 로그아웃
- Upload 탭: `index.html`(Dashboard)에서만 표시 — Upload 영역을 배너에 동적 주입
- 배너에 `gb-menu-btn` 공용 햄버거 버튼 CSS 정의, 모든 페이지의 topbar에 햄버거 버튼 추가
- 배너 오버레이 (`gb-banner-overlay`) 추가 — 배너 외부 클릭 시 자동 닫기
- 내비게이션 링크 클릭 시 모바일 배너 자동 닫기
- `index.html`, `analyze.html`의 기존 `mobile-menu-btn` CSS/JS를 `gb-menu-btn`/`toggleMobileBanner`로 통합

---

## [2026-04-13] Analysis History 서버 정보 자동 매핑 개선

**변경 파일:** `HeapDumpAnalyzerService.java`, `DumpTransferLogRepository.java`

- 원격 서버에서 전송된 파일의 분석 이력에 서버 이름이 "Local"로 표시되던 문제 수정
- `saveAnalysisToDb(result)` 호출 시 `DumpTransferLog`에서 전송 성공 기록을 조회하여 `serverId`/`serverName`을 자동 매핑
- `DumpTransferLogRepository`에 `findByFilenameAndTransferStatusOrderByCompletedAtDesc` 메서드 추가
- `HeapDumpAnalyzerService`에 `DumpTransferLogRepository`, `TargetServerRepository` 의존성 주입
- 기동 시 기존 history 레코드 중 서버 정보가 누락된 항목을 전송 로그 기반으로 자동 보정하는 `fixMissingServerInfoInHistory()` 추가

---

## [2026-04-13] 플로팅 채팅 전체화면 → 확대/축소 토글로 변경

**변경 파일:** `analyze.html`

- 헤더의 전체화면 버튼 및 전체화면 오버레이 HTML/CSS/JS 전체 제거
- 플로팅 채팅 헤더에 확대/축소 토글 버튼 추가 (화살표 아이콘)
- 확대 시: 420×560px → 700px×80vh, CSS transition으로 부드러운 전환
- 축소 시: 원래 크기로 복귀, 아이콘도 변경
- `_aiChatFullscreen` 변수 → `_aiChatExpanded`로 변경
- `getChatContainer()`, `getChatInput()`, `getChatSendBtn()` 단순화 (전체화면 분기 제거)
- 모바일(640px 이하) 확대 시 전체 너비

---

## [2026-04-13] AI 채팅 이력 DB 저장 + 전용 페이지 + 전체화면

**변경 파일:** `AiChatSession.java` (신규), `AiChatMessage.java` (신규), `AiChatSessionRepository.java` (신규), `AiChatMessageRepository.java` (신규), `AiChatController.java` (신규), `ai-chat.html` (신규), `banner.html`, `analyze.html`

### DB 엔티티 및 API
- **`ai_chat_sessions`** 테이블: 세션별 username, filename, title, model, messageCount 저장
- **`ai_chat_messages`** 테이블: 세션별 role, content (MEDIUMTEXT) 저장
- **`AiChatController`**: 세션 CRUD (`/api/ai-chat/sessions`), 메시지 조회/저장, 세션 기반 스트리밍 (`/api/ai-chat/sessions/{id}/stream`)
- 계정별(Spring Security `Principal`) 세션 격리 + 덤프 파일별 세션 분류
- 스트리밍 응답 완료 시 user/assistant 메시지 자동 DB 저장
- 첫 질문에서 세션 제목 자동 생성 (40자 truncate)

### AI Chat 전용 페이지 (`/ai-chat`)
- 좌측 세션 사이드바 (280px): 세션 목록, 새 채팅 버튼, 덤프 파일 필터
- 우측 채팅 영역: 메시지 표시, 스트리밍 입력, 마크다운 렌더링
- 세션 선택 시 기존 메시지 이력 로드
- 세션 삭제 (cascade: 메시지 함께 삭제)

### 배너 네비게이션
- History ↔ Servers 사이에 "AI Chat" 탭 추가 (말풍선 아이콘)
- 접힌 상태 아이콘도 동일하게 추가

### 분석 페이지 전체화면 전환
- 헤더에 "Chat" 전체화면 버튼 추가 (LLM 활성화 시만 표시)
- 전체화면 오버레이: 별도 입력/메시지 영역, 모델 뱃지, 초기화 버튼
- 플로팅 채팅 ↔ 전체화면 간 메시지 동기화

### 분석 페이지 채팅 세션 연동
- `sendChatMessage()` 시 세션 자동 생성 (`ensureChatSession()`)
- 세션 기반 스트리밍 엔드포인트 사용 → DB 자동 저장
- 채팅 관련 DOM 접근을 `getChatContainer()`, `getChatInput()`, `getChatSendBtn()` 헬퍼로 추상화 (전체화면/플로팅 모드 통합)

---

## [2026-04-13] AI 채팅 스트리밍 응답 구현

**변경 파일:** `HeapDumpAnalyzerService.java`, `HeapDumpController.java`, `analyze.html`

- AI 채팅 응답을 실시간 스트리밍으로 출력하도록 변경 (기존 전체 대기 → 토큰 단위 실시간 렌더링)
- **Backend**: `callLlmChatStream()` 메서드 추가 — LLM API에 `stream: true`로 요청, SSE 청크 파싱 (Claude `content_block_delta` / OpenAI `choices.delta.content`)
- **Backend**: `POST /api/llm/chat/stream` 엔드포인트 — `SseEmitter` 반환, 비동기 스레드에서 스트리밍 실행
- **Frontend**: `fetch` + `ReadableStream`으로 SSE 이벤트 실시간 수신, 텍스트 청크마다 assistant 버블에 마크다운 렌더링 업데이트
- 스트리밍 중 깜빡이는 커서(▌) 표시, 완료 시 커서 제거
- SSE 이벤트 구조: `start` (모델 정보), `chunk` (텍스트 조각), `done` (완료), `error` (오류)

---

## [2026-04-13] AI 플로팅 채팅 시스템 구현 + 채팅 시스템 프롬프트 설정

**변경 파일:** `HeapDumpAnalyzerService.java`, `HeapDumpController.java`, `analyze.html`, `llm-settings.html`

### 플로팅 AI 채팅 (analyze.html)
- 분석 페이지 우하단에 56px 원형 플로팅 채팅 버튼 추가 (파란-보라 그라데이션)
- LLM 활성화 시에만 표시, 비활성화 시 자동 숨김
- 채팅 패널 (420×560px): 헤더(모델명 뱃지, 초기화, 닫기), 메시지 영역, 입력 영역
- 멀티턴 대화 지원: 대화 이력을 매 요청마다 전체 전송
- 분석 컨텍스트 자동 주입: KPI, Top Consumers, Leak Suspects, AI 인사이트 결과를 시스템 프롬프트에 포함
- 마크다운 렌더링: bold, 인라인 코드, 코드 블록, 줄바꿈 지원
- 타이핑 인디케이터 (점 3개 bounce 애니메이션)
- 토큰 관리: 메시지 총 길이 초과 시 오래된 메시지 자동 제거
- Enter 전송, Shift+Enter 줄바꿈, Escape 패널 닫기
- 모바일 반응형 (640px 이하 전체 너비)

### 채팅 시스템 프롬프트 설정 (llm-settings.html)
- LLM Settings 페이지에 "Chat System Prompt" 카드 추가
- textarea로 시스템 프롬프트 편집, Save Prompt / Reset to Default 버튼
- `POST /api/llm/chat-prompt` 엔드포인트로 저장, settings.json에 영속화

### Backend
- `HeapDumpAnalyzerService`: `callLlmChat()` 메서드 추가 (멀티턴 messages 배열 수신, 마크다운 응답 반환)
- `HeapDumpAnalyzerService`: `llmChatSystemPrompt` volatile 필드 + `DEFAULT_CHAT_SYSTEM_PROMPT` 상수 + getter/setter + 영속화
- `HeapDumpController`: `POST /api/llm/chat` (채팅 API), `POST /api/llm/chat-prompt` (프롬프트 저장 API) 엔드포인트 추가
- `/api/settings` 응답에 `chatSystemPrompt` 필드 추가

---

## [2026-04-13] 관리자 계정 비활성화 방지 및 Admin → Settings 하위 Accounts 이동

**변경 파일:** `fragments/banner.html`, `UserService.java`, `admin/users.html`

- ADMIN 역할 계정은 항상 활성 상태 유지, 비활성화 불가 (백엔드 + 프론트엔드 모두 적용)
  - `UserService.updateUser()`: ADMIN 역할로 설정된 계정에 `enabled=false` 요청 시 예외 발생
  - 수정 모달: 역할이 ADMIN이면 상태 select 비활성화(disabled) + 값 강제 `true`
  - 역할 변경(USER↔ADMIN) 시 상태 select 연동 (`syncEnabledState()`)
- 배너 Navigation에서 Admin 독립 링크 제거 → Settings 하위 메뉴에 "Accounts" 탭으로 이동 (ADMIN 전용, `sec:authorize`)
- Settings 서브 메뉴 max-height 80px → 120px (3개 항목 수용: General, LLM Configuration, Accounts)
- `/admin/users` 경로 진입 시 Settings 서브 메뉴 자동 펼침 + Accounts 하이라이트

---

## [2026-04-13] 배너 Settings 위치 변경 및 하위 메뉴 구성

**변경 파일:** `fragments/banner.html`, `llm-settings.html`

- Navigation에서 Settings를 Admin 아래로 이동
- Settings를 서브 메뉴(아코디언)로 변경: General / LLM Configuration 두 개 탭 구성
- `/settings` 및 `/settings/llm` 경로 자동 하이라이트 및 서브 메뉴 자동 펼침 적용
- 접힌 상태(collapsed) 아이콘 순서도 동일하게 변경
- LLM Configuration 페이지의 Genspark API 설명을 한국어로 변경

---

## [2026-04-13] AI 인사이트 권장 조치 번호 목록 렌더링 개선

**변경 파일:** `analyze.html`

- `setNumberedList()` 함수 추가: 번호 목록 텍스트를 `<ol>` 리스트로 변환
  - `"1. xxx 2. yyy"` (한 줄 연속) 및 `"1. xxx\n2. yyy"` (줄바꿈 분리) 모두 지원
  - 번호 없는 이어지는 줄은 이전 항목에 자동 병합
  - 번호 원형 뱃지(보라색) 스타일 적용, 항목 간 여백 추가
  - 번호 패턴 미감지 시 기존 `setTextWithLineBreaks()` 폴백
- 권장 조치(`aiRecommendations`) 렌더링에 `setNumberedList()` 적용

---

## [2026-04-13] AI 인사이트 위험도 배너 레이아웃 개선

**변경 파일:** `analyze.html`

- 위험도 배너에서 `severityDesc` 설명 텍스트 제거 (하단 "잠재적 위험 요소" 카드에서 전체 표시)
- `severityDesc`가 있으면 길이와 관계없이 항상 "잠재적 위험 요소" 카드 표시 (기존: 80자 초과 시에만)
- 모바일(768px 이하) 위험도 배너: 가로→세로 레이아웃 전환 (`flex-direction:column`), 글자 세로 출력 문제 해결
- 모바일(480px 이하) 추가 조정: 아이콘/글자 크기 축소, 카드 패딩 조정

---

## [2026-04-13] Files 페이지 테이블 열 확장 (원본/압축 크기 분리, AI 인사이트 표시)

**변경 파일:** `HeapDumpController.java`, `HeapDumpAnalyzerService.java`, `files.html`

- 기존 "크기" 열을 "원본 크기"와 "압축 크기" 2개 열로 분리
  - 원본 크기: 항상 표시 (압축 파일은 formattedOriginalSize, 비압축 파일은 formattedSize)
  - 압축 크기: 압축 파일만 크기 + GZ 뱃지 표시, 비압축 파일은 "—"
- "AI 인사이트" 열 추가
  - AI 분석 결과가 있는 파일: 심각도별 색상 뱃지 (critical=빨강, warning=노랑, info=파랑, 기타=초록)
  - AI 분석 결과가 없는 파일: "—" 표시
- `AnalysisHistoryItem` DTO에 `hasAiInsight`, `aiInsightSeverity` 필드 추가
- `buildHistory()`에서 `AiInsightRepository`를 통해 AI 인사이트 존재 여부 일괄 조회
- `HeapDumpAnalyzerService`에 `getAiInsightRepository()` getter 추가
- 모바일(640px 이하)에서 압축 크기 열 숨김

---

## [2026-04-13] 사용자 계정 및 암호화 보안 취약점 7건 조치

**변경 파일:** `AesEncryptor.java`, `SecurityConfig.java`, `UserService.java`, `AdminController.java`, `init.sql`, `admin/users.html`, `heap_enc.sh`, `heap_dec.sh`

### 3.1 [높음] AES 암호화 키 환경변수 이동
- `KEY_SEED` 하드코딩 제거, 환경변수 `HEAP_ANALYZER_ENCRYPTION_KEY`에서 로드
- 미설정 시 기본 키 폴백 + 경고 로그 출력

### 3.2 [높음] AES IV 랜덤 생성
- 암호화 시 `SecureRandom`으로 16바이트 랜덤 IV 생성, 암호문 앞에 IV 결합
- 복호화 시 HEX 길이 기반으로 레거시(고정 IV) / 신규(랜덤 IV) 자동 판별
- 기존 `ENC(...)` 암호화 값 호환성 유지

### 3.3 [높음] 기본 관리자 비밀번호 외부화
- `shinhan@10` 하드코딩 제거, 환경변수 `HEAP_ADMIN_DEFAULT_PASSWORD`에서 로드
- 미설정 시 UUID 자동 생성 + warn 로그로 비밀번호 출력

### 3.4 [높음] init.sql DB 비밀번호 제거
- `IDENTIFIED BY 'heap_pass_2026'` 평문 비밀번호를 주석 가이드로 대체

### 3.5 [중간] /api/admin/** CSRF 보호 복원
- `SecurityConfig`에서 `/api/admin/**` 경로를 CSRF 면제에서 제외 (`ignoringRequestMatchers` 커스텀 매처)
- `admin/users.html`에 CSRF 메타 태그 추가, 모든 fetch 호출에 CSRF 헤더(`X-CSRF-TOKEN`) 전송

### 3.6 [중간] 비밀번호 정책 서버 검증 추가
- `UserService.validatePassword()` 메서드 추가: 최소 8자, 영문+숫자+특수문자 필수
- `createUser()`, `resetPassword()` 호출 시 검증 실행, 미충족 시 400 응답

### 3.7 [낮음] @PreAuthorize 이중 방어 추가
- `SecurityConfig`에 `@EnableGlobalMethodSecurity(prePostEnabled = true)` 추가
- `AdminController`에 `@PreAuthorize("hasRole('ADMIN')")` 클래스 레벨 적용

---

## [2026-04-13] 사용자 계정 및 암호화 보안 점검 보고서 작성

**생성 파일:** `SECURITY_AUDIT_ACCOUNT.md`

- 사용자 인증/인가, DB 비밀번호 암호화, 계정 관리 API 대상 보안 점검 수행
- 양호 항목 6건 (BCrypt 해싱, SQL Injection 방지, 인증/인가 구조 등)
- 취약점 7건 발견: AES 키 하드코딩(높음), IV 고정(높음), 기본 관리자 비밀번호 하드코딩(높음), init.sql 평문 비밀번호(높음), API CSRF 비활성화(중간), 비밀번호 정책 부재(중간), 메서드 레벨 권한 검증 없음(낮음)
- 각 취약점별 수정 예시 코드 및 개선 방안 포함

---

## [2026-04-13] 대시보드 "탐지 현황" 패��� 추가

**변경 파일:** `HeapDumpController.java`, `index.html`

- Analysis History 패널 제거 (배너 History 링크로 충분)
- "탐지 현황" 패널 추가: 심각도별 카드 (Critical/High/Medium/Low) + 파일별 suspect 목록
- `DetectionSummaryItem` inner DTO 추가 (파일명, suspect수, 심각도별 카운트)
- 각 파일의 `LeakSuspect.severity`를 집계하여 심각도별 통계 표시
- 파일명 클릭 → 분석 결과 페이지 이동 (view 버���)
- suspects 없으면 "탐지된 누수 의심 항목이 없습니다" 빈 상태 표시
- 모바일(480px 이하) 심각도 카드 2x2 레이아웃

---

## [2026-04-13] AI 인사이트 DB 저장으로 전환

**변경 파일:** `AiInsightEntity.java`(신규), `AiInsightRepository.java`(신규), `HeapDumpAnalyzerService.java`, `HeapDumpController.java`, `analyze.html`

- `ai_insights` 테이블 생성 (filename, model, severity, latency_ms, insight_data(MEDIUMTEXT), analysed_at)
- `saveAiInsight()`: DB에 저장 (기존 결과 있으면 UPDATE)
- `loadAiInsight()`: DB 조회 우선, 없으면 파일 폴백 + 자동 DB 마이그레이션
- `deleteAiInsight()`: DB 삭제 + 잔존 파일도 함께 삭제
- 앱 기동 시 기존 `ai_insight.json` 파일을 DB로 자동 마이그레이션 (1회)
- Controller 응답에서 `savedPath` → `savedTo: "database"` 변경
- 프론트엔드 저장 경로 표시: "Database (MariaDB)" 로 변경

---

## [2026-04-12] 배너 Servers 서브 메뉴 아코디언

**변경 파일:** `fragments/banner.html`

- Servers 메뉴를 아코디언 형태로 변경: 클릭 시 하위 메뉴 펼침/접기
- 하위 메뉴: "Target Servers" (`/servers`), "Transfer Logs" (`/servers/logs`)
- `/servers` 또는 `/servers/logs` 경로 접속 시 서브 메뉴 자동 펼침 + 부모/자식 모두 active 하이라이트
- CSS: `.gb-nav-sub` 슬라이드 트랜지션 (max-height), `.gb-nav-toggle` 화살표 회전 애니메이션

---

## [2026-04-12] 전송 진행바 + Transfer Logs 페이지

**변경 파일:** `servers.html`, `server-logs.html`(신규), `ServerController.java`

**전송 진행바:**
- 전송 버튼 클릭 시 애니메이션 프로그레스 바로 교체 (pulse 애니메이션)
- 성공: 초록색 바 + "완료" 라벨 / 실패: 빨간색 바 + "실패" + 에러 메시지 + 재시도 버튼
- 모두 전송 시에도 각 파일별 개별 진행바 표시

**Transfer Logs 페이지 (`/servers/logs`):**
- Servers topbar에 "Transfer Logs" 버튼 추가
- 서버별 아코디언 레이아웃 (첫 번째 서버 기본 열림)
- 아코디언 헤더: 서버명, 호스트, 통계 (Total / Success / Failed)
- 로그 테이블: 상태(뱃지), 파일명, 원격 경로, 크기, 시작/완료 시간, 에러 메시지
- 서버당 최근 50건 표시, 에러 컬럼 hover 시 전체 메시지 노출

---

## [2026-04-12] 스캔 전송 상태 판정 수정 + 모두 전송 버튼 + CSRF 수정

**변경 파일:** `DumpTransferLogRepository.java`, `RemoteDumpService.java`, `servers.html`, `files.html`, `history.html`, `index.html`, `analyze.html`, `progress.html`

- "전송됨" 판정 로직 수정: `existsByServerIdAndFilename` → `existsByServerIdAndFilenameAndTransferStatus(..., "SUCCESS")` (실패 로그만 있으면 미전송으로 표시)
- 스캔 결과에서 미전송 파일 2개 이상이면 "All Transfer" 버튼 표시 → 순차 전송
- `transferFile(btn, callback)` 콜백 파라미터 추가로 순차 전송 지원
- 모든 동적 POST 폼에 CSRF `_csrf` 토큰 추가 (files/history/index/analyze/progress 6개 페이지) — 403 Forbidden 수정
- 실패 전송 로그 DB 정리 (36건 FAILED → 삭제)

---

## [2026-04-12] SCP 2단계 전송 (Permission Denied 수정) + 임시 경로 설정 + 에러 누적 수정

**변경 파일:** `RemoteDumpService.java`, `HeapDumpConfig.java`, `application.properties`, `ServerController.java`, `settings.html`, `servers.html`

**SCP 2단계 전송:**
- Phase 1: SCP를 sscuser로 실행 → 임시 디렉토리(`/tmp/heapdump_transfer_<UUID>_<filename>`)에 저장
- Phase 2: `Files.move()`로 앱 실행 계정(root) 권한으로 `/opt/heapdumps/dumpfiles/`에 이동
- 모든 에러 경로에서 `cleanupTempFile()` 헬퍼로 임시 파일 정리

**Settings — SCP temp directory 설정 추가:**
- `application.properties`에 `remote.scp.temp-dir=/tmp` 기본값
- Settings 페이지에서 런타임 변경 가능 (입력 + Save)
- API: `GET/POST /api/servers/scp-temp-dir`

**servers.html — 전송 에러 누적 표시 버그 수정:**
- 실패 시 기존 에러 div(`.transfer-err`)를 제거 후 새 에러 추가 → 재시도해도 에러 1개만 표시

---

## [2026-04-12] SSH 로컬 실행 계정 설정 + 상태 커서 수정

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `RemoteDumpService.java`, `ServerController.java`, `settings.html`, `servers.html`

- `remote.ssh.local-user=sscuser` 설정 추가 — SSH/SCP를 `su - sscuser -c "..."` 로 실행
- 현재 프로세스 계정과 동일하면 su 없이 직접 실행
- Settings 페이지 Analysis Options에 "SSH local user" 입력 필드 추가 (런타임 변경 가능)
- API: `GET/POST /api/servers/ssh-local-user`
- Servers 페이지 상태 뱃지의 `cursor: help` (`?` 커서) 제거

---

## [2026-04-12] 서버 연결 상태 DB 영속화 (테스트/스캔/자동탐지 실패 시 상태 반영)

**변경 파일:** `TargetServer.java`, `RemoteDumpService.java`, `servers.html`

- `target_servers` 테이블에 `conn_status`(OK/FAIL/UNKNOWN), `last_error`, `last_checked_at` 컬럼 추가
- 연결 테스트 성공 → `conn_status=OK`, 실패 → `conn_status=FAIL` + `last_error` 저장
- 수동 스캔 성공 → `OK`, SSH 에러 → `FAIL` + 에러 메시지 저장
- 자동 탐지 성공 → `OK`, 실패 → `FAIL` + 에러 메시지 저장
- Servers 페이지 상태 컬럼: `정상`(초록) / `실패`(빨강, hover 시 에러 메시지 표시) / `미확인`(회색) / `비활성`(회색)
- 테스트/스캔 후 페이지 새로고침 없이 JS로 상태 뱃지 즉시 갱신

---

## [2026-04-12] 스캔 주기 설정 + SSH/SCP 에러 표시

**변경 파일:** `application.properties`, `HeapDumpConfig.java`, `RemoteDumpService.java`, `ServerController.java`, `settings.html`, `servers.html`

**Settings — Remote scan interval 설정 추가:**
- Analysis Options 카드에 스캔 주기 드롭다운 추가 (10초 ~ 1시간)
- `application.properties`에 `remote.scan.interval-sec=60` 기본값
- API: `GET/POST /api/servers/scan-interval` (조회/변경)
- 고정 `@Scheduled(60초)` → 동적 주기로 변경 (10초 체크 루프 + 설정 주기 경과 시 실행)

**Target Servers — SSH/SCP 에러 표시:**
- 수동 스캔 시 SSH 에러 발생하면 빨간색 에러 배너로 상세 메시지 표시 (exit code, stderr)
- SCP 전송 실패 시 버튼 옆에 에러 메시지 인라인 표시
- 자동 탐지 에러: 서버별 마지막 에러를 `lastAutoScanErrors` 맵에 기록
- Servers 페이지 상단에 자동 스캔 에러 배너 (30초마다 갱신)
- `cleanSshError()`: SSH 배너/MOTD 텍스트 제거, 핵심 에러만 추출

---

## [2026-04-12] Settings 페이지 Database 카드 추가

**변경 파일:** `settings.html`, `HeapDumpController.java`

- Settings 페이지에 Database 카드 추가: 접속 상태(Connected/Disconnected 뱃지), Host, Port, Database, Username, Version, History Records 표시
- "설정 변경" 버튼 → 모달에서 DBMS IP, 포트, DB명, 계정, 패스워드 입력 가능
- "연결 테스트" 버튼: 입력한 정보로 실제 DB 연결 테스트 (성공 시 DB 버전 표시)
- "저장" 버튼: application.properties에 AES 암호화된 패스워드로 저장, 재시작 안내
- API: `POST /api/settings/database/test` (연결 테스트), `POST /api/settings/database` (설정 저장)
- `/api/settings` 응답에 `database` 섹션 추가 (host, port, database, username, connected, version, historyCount)

---

## [2026-04-12] MariaDB 연동, 로그인/계정관리, 원격 서버 덤프 탐지 기능 추가

### Phase 1: MariaDB 연동 기초 설정
**변경 파일:** `pom.xml`, `application.properties`, `db/init.sql`
- spring-boot-starter-data-jpa, mariadb-java-client, spring-boot-starter-security, thymeleaf-extras-springsecurity5 의존성 추가
- MariaDB 192.168.56.9:3306/HEAPDB 연결 설정 (heap_user 계정)
- JPA ddl-auto=update 로 테이블 자동 생성

### Phase 2: JPA Entity + Repository
**신규 파일:** `model/entity/User.java`, `model/entity/TargetServer.java`, `model/entity/AnalysisHistoryEntity.java`, `model/entity/DumpTransferLog.java`, `repository/UserRepository.java`, `repository/TargetServerRepository.java`, `repository/AnalysisHistoryRepository.java`, `repository/DumpTransferLogRepository.java`
- 4개 Entity 클래스 (users, target_servers, analysis_history, dump_transfer_log 테이블)
- Spring Data JPA Repository 인터페이스

### Phase 3: Spring Security + 로그인/계정 관리
**신규 파일:** `config/SecurityConfig.java`, `service/CustomUserDetailsService.java`, `service/UserService.java`, `controller/AuthController.java`, `controller/AdminController.java`, `templates/login.html`, `templates/admin/users.html`
- Spring Security 기반 세션 인증 (모든 페이지 인증 필요, /login 공개)
- 기본 관리자 계정: admin / shinhan@10 (BCrypt)
- ADMIN 전용 계정 관리 페이지 (/admin/users): 사용자 추가/수정/삭제/비밀번호 초기화
- CSRF: API 경로(/api/**) 제외, Thymeleaf 페이지는 자동 토큰 삽입

### Phase 4: History DB 마이그레이션
**변경 파일:** `service/HeapDumpAnalyzerService.java`, `controller/HeapDumpController.java`
- 분석 성공/실패 시 analysis_history 테이블에 메타데이터 자동 저장
- buildHistory() DB 기반으로 전환 (DB 우선 조회 + 미분석 파일 폴백)
- 앱 기동 시 기존 result.json 데이터를 DB로 자동 마이그레이션 (1회)
- AnalysisHistoryItem에 serverName 필드 추가

### Phase 5: 원격 서버 덤프 탐지/전송
**신규 파일:** `service/RemoteDumpService.java`, `controller/ServerController.java`, `templates/servers.html`
- SSH/SCP 기반 원격 서버 연결 (sscuser 계정, BatchMode 키 인증)
- 서버 등록/수정/삭제, 연결 테스트, 수동 스캔, 파일 전송 기능
- 자동 탐지: @Scheduled(60초)로 auto_detect=true 서버의 새 덤프 자동 전송
- 전송 이력 dump_transfer_log 기록, 중복 전송 방지 (서버ID + 파일명)

### Phase 6: 배너/네비게이션 업데이트
**변경 파일:** `templates/fragments/banner.html`
- Servers 메뉴 추가 (모든 사용자)
- Admin 메뉴 추가 (ADMIN 역할만 표시, sec:authorize)
- 로그인 사용자명 표시 + Logout 버튼 (CSRF 폼 기반)
- Collapsed icons에 Servers 아이콘 추가

---

## [2026-04-12] Files, History 페이지 테이블 레이아웃 전환

**변경 파일:** `files.html`, `history.html`

**Files 페이지 (`/files`):**
- 기존 `.file-item` flex 리스트 → `<table class="ftable">` 테이블 형식으로 변경
- 컬럼: 상태, 파일명, 크기, 날짜, 작업 (아이콘 버튼)
- 테이블 헤더 고정, hover 행 하이라이트, 640px 이하에서 날짜 컬럼 숨김
- 기존 기능(검색 필터, 삭제/다운로드 모달, GZ 뱃지) 모두 유지

**History 페이지 (`/history`):**
- 기존 `.hi` 카드형 리스트 → `<table class="htable">` 테이블 형식으로 변경
- 컬럼: 상태, 파일명(뱃지 포함), 분석 시간, 힙 사용량, Suspects, 파일 크기, 날짜, 작업
- 640px 이하에서 힙 사용량/Suspects 컬럼 숨김
- 기존 기능(검색 필터, 삭제 모달, 링크) 모두 유지

---

## [2026-04-11] AI 인사이트 패널 UI 개선 및 분석 확인 모달 추가

**변경 파일:** `analyze.html`

**AI 인사이트 패널 레이아웃/폰트 확대:**
- 패널 헤더: 제목 14→17px, 부제 11→13px, 뱃지 10→12px, 버튼 12→13px
- 미분석 상태: 타이틀 17→20px, 설명 13→15px, 태그 11→13px, 시작 버튼 14→16px
- 분석 중 상태: step circle 32→36px, label 10→12px, 메시지 14→16px, 상세 12→14px
- 완료 상태: severity 20→24px, 본문 13.5→15px, 카드 타이틀 11→13px, 메타 10→12px
- 에러 상태: 타이틀 15→18px, 메시지 13→15px, 버튼 13→14px
- 저장 경로 배너: 14px, 코드 13px
- 패널 max-width 960px + 가운데 정렬 (margin: 0 auto)

**AI 분석 확인 모달 추가:**
- `startAiAnalysis()` 호출 시 즉시 실행하지 않고 확인 모달 표시
- 모달에 API 비용 발생 경고, 페이지 이탈 주의사항 안내
- 재분석 시에는 "기존 결과가 새 결과로 대체됩니다" 추가 경고 표시
- 확인 시 `confirmAiAnalysis()` → `_doStartAiAnalysis()`로 실제 분석 진행

**기타:**
- LLM 비활성화 안내 링크를 `/settings` → `/settings/llm`으로 변경
- 에러 상태 Settings 링크도 `/settings/llm`으로 변경

## [2026-04-11] AI / LLM Configuration을 별도 페이지로 분리

Settings 페이지의 AI / LLM Configuration 섹션을 독립 페이지(`/settings/llm`)로 분리.

**변경 파일:**
- `HeapDumpController.java` — `@GetMapping("/settings/llm")` 라우트 추가
- `settings.html` — LLM 카드를 링크 카드로 교체 (상태 뱃지 + 요약 정보 표시), LLM 관련 JS 코드 제거
- `llm-settings.html` — 새 페이지 생성 (Enable, Provider & Model, API Key, Token Limits 카드 구성)

**상세 내역:**
- Settings 페이지에서 LLM 카드 클릭 시 `/settings/llm`으로 이동
- 링크 카드에 ON/OFF 뱃지와 Provider/Model/API Key 상태 요약 표시
- LLM Settings 페이지에 breadcrumb 네비게이션 (Settings > AI / LLM Configuration)
- 배너 네비게이션에서 `/settings/llm` 접근 시 Settings 링크 하이라이트 유지

## [2026-04-11] Genspark LLM 연동 버그 수정

HTTP 404 Not Found 오류 발생하던 Genspark LLM 연동 문제 수정.

**근본 원인:** Genspark API 엔드포인트는 `/chat/completions` 경로가 필요한데,
`getDefaultApiUrl("genspark")`이 빈 문자열을 반환하고 Settings UI에서도 URL 미입력 상태가 유지되어
`base_url`만으로 요청이 전달되었기 때문에 404 발생.
추가로 모델명(`gpt-4o`, `claude-sonnet-4-20250514` 등)이 Genspark 허용 모델 목록에 없어 에러 발생.

[service/HeapDumpAnalyzerService.java]
- `getDefaultApiUrl("genspark")`: `""` → `"https://www.genspark.ai/api/llm_proxy/v1/chat/completions"` 수정
- `GENSPARK_MODELS` 정적 상수 추가 — Genspark 허용 모델 21종 (GPT-5 계열, Claude 4 계열, Kimi, MiniMax)

[controller/HeapDumpController.java]
- `/api/settings` 응답의 `providerModels.genspark`: 빈 목록 → `GENSPARK_MODELS` 목록으로 수정

[templates/settings.html]
- `_defaultUrls.genspark`: `""` → `"https://www.genspark.ai/api/llm_proxy/v1/chat/completions"` 수정
- `_providerModels.genspark`: 드롭다운 목록 추가 (GPT-5/Claude 4/Kimi/MiniMax 21종)
- Genspark 선택 시 안내 박스 표시 (`#gensparkHint`):
  - Base URL / 엔드포인트 / API Key 형식(gsk-...) / 허용 모델 안내
- `loadLlmSettings()`: provider 로드 시 gensparkHint 표시 여부 반영
- `onProviderChange()`: provider 전환 시 gensparkHint 표시/숨김 처리


다중 LLM 프로바이더(Claude, GPT, Genspark, Custom) 지원 AI 분석 기능 추가.
사용자가 명시적으로 "AI 분석 시작" 버튼을 클릭해야만 LLM 호출 발생.

[application.properties]
- `llm.enabled`, `llm.provider`, `llm.api.url`, `llm.model`, `llm.api.key` 등 9개 LLM 설정 속성 추가

[config/HeapDumpConfig.java]
- LLM 관련 `@Value` 필드 9개 + getter 메서드 추가

[service/HeapDumpAnalyzerService.java]
- LLM 런타임 설정 volatile 필드 (provider, apiUrl, model, apiKey 등)
- `setLlmEnabled()`, `setLlmConfig()`, `setLlmApiKey()` — 런타임 설정 변경 + settings.json 영속화
- `getLlmApiKeyMasked()` — API 키 마스킹 (앞 7자 + ... + 뒤 4자)
- `getDefaultApiUrl(provider)` — 프로바이더별 기본 API URL
- `testLlmConnection()` — 프로바이더별 연결 테스트 (Claude: x-api-key, 나머지: Bearer)
- `callLlmAnalysis(prompt)` — 프로바이더별 LLM API 호출 + JSON 응답 파싱
- `extractLlmText(resp)` — Claude/OpenAI 응답 형식 분기 텍스트 추출
- `persistSettings()` / `loadPersistedSettings()` / `syncApplicationProperties()`에 LLM 설정 추가
- 환경변수 `LLM_API_KEY` 우선 적용

[controller/HeapDumpController.java]
- `POST /api/llm/enabled` — LLM 활성화/비활성화
- `POST /api/llm/config` — provider, apiUrl, model, maxTokens 일괄 변경
- `POST /api/llm/apikey` — API 키 저장
- `POST /api/llm/test-connection` — 연결 테스트
- `POST /api/llm/analyze` — AI 분석 요청 (프롬프트 기반)
- `GET /api/settings` 응답에 `llm` 섹션 추가 (enabled, provider, providerModels 등)

[templates/settings.html]
- "AI / LLM Configuration" 카드 추가: LLM 활성화 토글, Provider 드롭다운 (Claude/GPT/Genspark/Custom), API URL, Model 선택/입력, API Key, Max Tokens, Save Config + Test Connection 버튼
- `onProviderChange()` — Provider 변경 시 API URL 자동 채움 + 모델 드롭다운/텍스트 입력 전환
- `saveLlmConfig()`, `saveLlmApiKey()`, `testLlmConnection()` JS 함수

[templates/analyze.html]
- 헤더에 "AI Analyzed" / "AI Not Analyzed" 배지 추가 (파란색/회색 전환)
- 사이드바에 "AI Analysis" 섹션 + "AI 인사이트" 네비게이션 버튼
- `panel-ai-insight` 패널: 4가지 상태 (미분석/분석중/완료/에러)
  - 미분석: dashed 박스 + "AI 분석 시작" 버튼 (LLM 비활성화 시 disabled + 안내문)
  - 분석중: 스피너
  - 완료: 요약/위험도/근본원인/권장조치 카드 4개 + 재분석 버튼
  - 에러: 에러 메시지 + 재시도 버튼
- `startAiAnalysis()`, `collectAnalysisData()`, `buildAnalysisPrompt()` 등 AI 분석 JS 함수

## [2026-04-09] 파일 업로드 중복 감지 시스템

[HeapDumpAnalyzerService.java]
- `checkDuplicate(filename, fileSize, partialHash)` — 기존 파일과 크기+첫 64KB SHA-256 해시로 비교
- `computePartialHash(File, int)` — 일반/GZ 파일 모두 지원하는 부분 해시 계산
- `generateUniqueName(filename, directory)` — `{base}_2.{ext}` 패턴 자동 이름 생성
- `java.security.MessageDigest` import 추가

[HeapDumpController.java]
- `POST /api/upload/check` 엔드포인트 추가 — 요청: `{filename, fileSize, partialHash}`, 응답: `{status, existingFilename, suggestedName}`
- 응답 status: `OK`, `DUPLICATE_CONTENT`, `DUPLICATE_NAME`

[index.html]
- `computePartialHash(file)` — Web Crypto API로 클라이언트 측 첫 64KB SHA-256 해시 계산
- `checkDuplicate(file)` — API 호출 후 결과에 따라 모달 분기
- `showDuplicateContentModal()` — "이미 존재하는 파일과 동일한 내용" 모달 (취소/업로드)
- `showDuplicateNameModal()` — "같은 이름, 다른 내용" 모달 (취소/덮어쓰기/이름변경)
- `startUploadRenamed()` — `FormData.append(file, newName)` 으로 이름 변경 업로드
- `doUpload()` 수정: 디스크 체크 후 `checkDuplicate()` 호출로 변경
- 디스크 경고 모달 "Upload anyway" → `checkDuplicate()` 호출로 변경

## [2026-04-09] 배너 접힌 상태 오버플로우 + 페이지 이동 깜빡임 수정

[fragments/banner.html]
- 접힌 아이콘 스트립: padding 축소(42px→6px), `overflow-y: auto`, `flex: 1`로 뷰포트 내 스크롤 처리
- 아이콘 `flex-shrink: 0`, `min-height: 32px`로 크기 유지
- 페이지 로드 깜빡임 방지: `<style>` 앞에 인라인 `<script>`로 `banner-collapsed` 즉시 적용
- `banner-no-transition` 클래스로 초기 로드 시 transition 비활성화, `requestAnimationFrame` 2프레임 후 복원
- 배너 헤더 하단 border 제거

## [2026-04-09] 배너 헤더/경계 디자인 C안 적용

[fragments/banner.html]
- 배너 `border-right` 제거 → `box-shadow: 1px 0 3px rgba(0,0,0,.06)` 미세한 그림자로 교체
- 배너 헤더 배경: 본체와 동일한 `#F9FAFB`로 통일 (경계선 최소화)
- 헤더 하단 `border-bottom: 1px solid #E5E7EB` 유지 (본체와 구분)
- 페이지 topbar의 `box-shadow`와 배너 그림자가 자연스럽게 연결

## [2026-04-08] 배너 System Status 캐시 + Refresh 버튼

[fragments/banner.html]
- `localStorage` 캐시 도입: 페이지 이동 시 캐시된 데이터로 즉시 렌더링 (깜빡임 방지)
- 자동 갱신 주기 15초 → 60초(1분)로 변경
- 캐시가 60초 미만이면 페이지 로드 시 API 호출 생략
- System Status 타이틀 우측에 Refresh 아이콘 버튼 추가 (회전 애니메이션)
- `fetchBannerStatus()` → `applyBannerStatus()` DOM 업데이트 분리
- `refreshBannerStatus()` 수동 갱신 함수 추가
- JS 변수 `js` → `js_el`로 변경 (예약어 충돌 방지)

## [2026-04-08] 페이지 topbar 로고 제거 + 제목 페이지명으로 변경

[index.html] 로고 제거, 제목 "Heap Dump Analyzer" → "Dashboard", Settings 버튼 제거
[analyze.html] 로고 제거, 제목 "Heap Dump Analyzer" → "Analysis" (filename-badge 유지)
[history.html] 로고 제거, 제목 → "History"
[files.html] 로고 제거, 제목 → "Files"
[settings.html] 로고 제거, 제목 → "Settings"
[compare.html] 로고 제거, 제목 "Heap Dump Compare" → "Compare"
[progress.html] 변경 없음 (이미 로고 없고 "Analyzing" 제목 적절)

## [2026-04-08] 배너 헤더 영역 추가

[fragments/banner.html]
- 배너를 `top: 0`부터 시작하도록 변경 (기존 `top: 52px`)
- 52px 헤더 영역 추가: 앱 로고(파란 아이콘) + "Heap Dump Analyzer" 타이틀 + "v2.0 · MAT CLI" 서브텍스트
- 배너 z-index 90 → 110으로 상향 (페이지 topbar 위에 표시)
- 접힌 상태에서 헤더는 로고 아이콘만 표시
- 토글 버튼 위치 `top: 8px` → `top: 62px`로 헤더 아래로 이동

## [2026-04-08] 좌측 배너 전역 적용 + 접기/펼치기 토글

[fragments/banner.html] (신규)
- 공통 배너 Thymeleaf fragment 생성 — CSS, HTML, JS 자체 포함
- `position: fixed; left: 0; top: 52px` 으로 전 페이지 최좌측 고정
- System Status: MAT CLI 상태, 디스크 사용량 바, JVM 메모리 바, 분석 큐 상태
- Navigation: Dashboard, Files, History, Settings (현재 페이지 하이라이트)
- 접기/펼치기 토글 버튼 (220px ↔ 44px) + `localStorage` 상태 저장
- 접힌 상태: 아이콘 스트립 표시 (MAT 상태 dot + 네비 아이콘)
- `/api/system/status` API에서 15초 간격으로 데이터 갱신
- 900px 이하 모바일에서 자동 숨김

[HeapDumpController.java]
- `GET /api/system/status` 엔드포인트 추가 — MAT CLI, 디스크, JVM, 큐 상태 JSON 반환
- index()의 배너 전용 model attribute 제거 (API로 이전)

[index.html]
- 인라인 배너 HTML/CSS/JS 제거, fragment include로 교체
- grid `300px 220px 1fr` → `300px 1fr` + `margin-left: var(--banner-w)`
- topbar `left: var(--banner-w)` 적용

[analyze.html]
- `.app-header`, `.sidebar`, `.main-content`에 `left/margin-left: var(--banner-w)` 적용
- 768px 이하 모바일에서 리셋

[progress.html, history.html, files.html, settings.html, compare.html]
- 각 페이지 topbar에 `left: var(--banner-w)` 적용
- container에 `padding-left: calc(var(--banner-w) + 20px)` 적용
- 900px 이하 모바일 리셋 미디어쿼리 추가

## [2026-04-08] 메인 홈페이지 좌측 배너 추가

[HeapDumpController.java]
- `index()` 메서드에 MAT CLI 상태 (`matCliReady`, `matCliStatus`), JVM 메모리 (`jvmUsedMb`, `jvmMaxMb`, `jvmUsedPercent`) model attribute 추가

[index.html]
- 레이아웃을 `300px 1fr` → `300px 220px 1fr` 3컬럼 grid로 변경
- 좌측 배너 (`<div class="left-banner">`) 추가: System Status 섹션 + Navigation 섹션
  - MAT CLI 상태: Ready/Not Ready 인디케이터 (초록/빨간 dot)
  - 디스크 사용량: 프로그레스 바 + 퍼센트 (70%/90% 기준 색상 변경)
  - JVM 메모리: 프로그레스 바 + MB 표시
  - 분석 큐 상태: `renderQueuePanel()`과 동기화되는 실시간 표시
  - 네비게이션 링크: Files, History, Settings, Compare (SVG 아이콘)
- 반응형: `@media (max-width: 900px)` 에서 배너 숨김 처리
- `@media (min-width: 1024px)` desktop 스케일링에 배너 폰트 크기 추가
- `updateBannerQueue()` 함수 추가: 큐 polling 결과를 배너 카드에 동기화

## [2026-04-08] Overview KPI 카드 모바일 3x2 그리드 레이아웃

[analyze.html]
- `@media (max-width:480px)`: KPI 바를 `flex-direction:column`(1열) → `grid-template-columns:repeat(3, 1fr)`(3열 2행)로 변경
- 아이콘 26px, 값 13px, 라벨 10px으로 축소하여 3열에 맞게 조정
- 패딩 8px 10px, gap 6px로 밀도 높임
- 3n번째 아이템 `border-right:none`, 마지막 행 `border-bottom:none` 처리

## [2026-04-08] Analysis 모달 모바일 레이아웃 최적화

[analyze.html]
- `@media (max-width:768px)`: 일반 모달(`.modal-box`) 패딩·너비 축소, 아이콘·타이틀·바디·버튼 폰트 크기 축소
- `@media (max-width:768px)`: Component Detail 모달 전용 — 너비 `calc(100vw - 24px)`, 최대높이 88vh, 내부 패딩 축소
- `@media (max-width:480px)`: 일반 모달 패딩 18→16px, 너비 `calc(100vw - 32px)`, border-radius 12px로 축소
- `@media (max-width:480px)`: 모달 아이콘 34px, 타이틀 15px, 바디 12px, 버튼 12px으로 모바일 최적화
- `@media (max-width:480px)`: Component Detail 모달 — 너비 `calc(100vw - 16px)`, 최대높이 90vh, 내부 padding 최소화
- 미사용 `.modal-content` 규칙을 실제 `.modal-box` 및 Component Detail 모달에 맞게 재작성

## [2026-04-07] Overview Treemap/StackedBar 툴팁 오른쪽 넘침 수정

[analyze.html]
- `positionTooltip(tt, e)` 공통 함수 추가: 마우스 위치 기준으로 툴팁 배치 시 화면 경계 체크
  - 오른쪽 넘침 → 마우스 왼쪽으로 표시
  - 하단 넘침 → 마우스 위쪽으로 표시
  - 좌측/상단 최소 8px 여백 보장
- Treemap `mousemove` 핸들러: 인라인 좌표 계산 → `positionTooltip()` 호출로 변경
- Stacked Bar `mousemove` 핸들러: 동일하게 `positionTooltip()` 호출로 변경

## [2026-04-07] 메타 카드 ? 팝오버 모달 경계 기준 위치 보정

[analyze.html]
- 팝오버 수평 위치 계산을 `window.innerWidth` → 모달 박스(`.modal-box`)의 실제 경계(`getBoundingClientRect()`) 기준으로 변경
- 오른쪽 카드의 팝오버가 모달 밖으로 나가지 않도록 `rightBound - popupW - 8` 보정
- 왼쪽 카드도 동일하게 `leftBound + 8` 보정
- 화살표 위치도 팝오버 내부 범위(10px ~ popupW-22px)로 클램핑하여 화살표가 팝오버 밖으로 나가지 않도록 처리

## [2026-04-07] 메타 카드 ? 팝오버 레이아웃 잘림 수정

[analyze.html]
- 팝오버를 `position:absolute` (카드 내부) → `position:fixed` + `document.body` 에 직접 추가로 변경
  - 부모 요소의 `overflow:auto/hidden`에 의해 잘리던 문제 해결
- 버튼 위치 기준 동적 배치: `getBoundingClientRect()`로 정확한 좌표 계산
  - 화면 하단 공간 부족 시 자동으로 위쪽에 표시
  - 좌우 화면 밖으로 나가지 않도록 8px 여백 보정
- 화살표를 CSS `::before` pseudo-element → DOM 요소(`.cd-help-arrow`)로 변경하여 버튼 중심에 정확히 위치
- 모달 본문 스크롤 시 팝오버 자동 닫힘 추가
- `closeComponentDetail()`에서 잔여 팝오버 정리 추가

## [2026-04-07] 메타 카드 ? 버튼 부연설명 팝오버 추가

[analyze.html]
- `META_HELP` 사전 추가: 8개 메타 카드 라벨에 대한 한국어 부연설명
  - 크기, Retained Heap, 클래스 수, 객체 수, 클래스 로더, 힙 점유율, 얕은 힙, 보유 힙
- `cdMetaCard()` 함수 수정: META_HELP에 해당 라벨이 있으면 `?` 버튼 자동 추가
- `toggleMetaHelp()` 함수 추가: 클릭 시 카드 아래 다크 팝오버 토글
  - 같은 카드 재클릭 시 닫기, 다른 카드 클릭 시 이전 것 닫고 새 것 열기
  - 외부 클릭 시 자동 닫힘
- `escAttr()` 헬퍼 함수 추가 (HTML 속성 이스케이프)
- CSS 추가: `.cd-help-btn` (15px 원형 버튼, hover 시 파란색), `.cd-help-popup` (다크 테마 팝오버, 화살표, 애니메이션)
- Top Consumers 상세 모달 + Histogram 폴백 모달 모두 동일하게 적용

## [2026-04-07] Top Consumers 모달 힙 점유율 정확도 수정 및 Retained Heap 표시

[analyze.html]
- 힙 점유율 데이터 소스 수정: `metadata.sizeBytes` (컴포넌트 전체 크기) → Top Consumers 테이블의 `data-pct` (MAT dominator 분석 기준) 우선 사용
  - 기존: metadata의 Size (클래스 로더가 로드한 전체 객체 크기) → 실제보다 과대 표시
  - 수정: Top Consumers 테이블의 퍼센트 (MAT가 계산한 정확한 retained heap 비율)
  - Top Consumers에 없는 클래스는 metadata.sizeBytes로 폴백
- `findClassInTopConsumers()` 확장: `pct`, `size` 필드 추가 반환
- 메타 카드에 Retained Heap 항목 추가 (Top Consumers의 실제 retained heap 바이트 값)

## [2026-04-07] Top Consumers 상세 모달 — 부연설명, 요약, 연관 정보 추가

[analyze.html]
- `renderParsedDetail()` 대폭 개선:
  - **클래스 부연설명**: `CLASS_DESCRIPTIONS` 사전 연동 — 해당 클래스에 대한 설명 카드 표시
  - **힙 점유율 카드 + 바**: metadata.sizeBytes / TOTAL_BYTES 기반 퍼센트 계산 및 시각화
  - **분석 요약 카드**: 힙 점유율 평가, 경고/오류 건수, Leak Suspect 연관, Histogram 순위, 객체 크기 분석 자동 생성
  - **Leak Suspect 링크**: 관련 suspect 발견 시 클릭으로 해당 suspect로 직접 이동
- `countSectionSeverities()` 함수 추가: 섹션 트리 재귀 탐색하여 warning/error 수 집계
- `generateAnalysisSummary()` 함수 추가: 다양한 지표 기반 분석 포인트 자동 생성
  - 힙 점유율 30%↑ 경고, 10%↑ 주의, 그 외 정상
  - 오류/경고 감지 알림
  - Leak Suspect 언급 감지
  - Histogram 순위/객체 수/Shallow Heap 정보
  - 객체당 평균 크기 분석 (1MB↑ 대형 객체 경고, 10만↑ 다수 객체 경고)
- 요약 카드 색상: 오류(빨강), 경고/고점유율(노랑), 정상(초록) 자동 구분

## [2026-04-07] Histogram 클래스 부연설명 사전 추가

[analyze.html]
- `CLASS_DESCRIPTIONS` 사전 추가: 20개 주요 클래스에 대한 한국어 부연설명
  - 원시 배열: `byte[]`, `char[]`, `int[]`, `long[]`, `short[]`, `boolean[]`, `double[]`, `float[]`
  - 문자열/기본: `java.lang.String`, `java.lang.Object[]`, `java.lang.Class`
  - 컬렉션: `ArrayList`, `HashMap`, `HashMap$Node`, `LinkedHashMap$Entry`, `ConcurrentHashMap$Node`, `TreeMap$Entry`
  - 참조: `WeakReference`, `SoftReference`, `Finalizer`
- 각 클래스에 아이콘, 설명(`desc`), 상세 조언(`detail`) 포함
- `renderHistogramFallback()`에서 해당 클래스일 때 파란색 설명 카드 표시
- Java 버전별 차이(예: Java 9+ Compact Strings), 성능 최적화 팁 포함

## [2026-04-07] Histogram 클래스 클릭 시 상세 정보 모달 표시

[analyze.html]
- Histogram 테이블 행에 클릭 핸들러 추가: 클릭 시 `showComponentDetail()` 호출
- 행에 `data-retained`, `data-shallow`, `data-objects` 속성 추가 (원본 바이트값)
- `findClassInHistogram()` 확장: `retainedBytes`, `shallowBytes`, `objectCount` 원본 값 반환
- `renderHistogramFallback()` 대폭 개선:
  - 순위 뱃지 표시 (Histogram #N)
  - 힙 점유율 계산 및 퍼센트 바 시각화 (30%↑ 빨강, 10%↑ 노랑, 그 외 파랑)
  - 관련 Leak Suspect 자동 검색 및 링크 (클릭 시 해당 suspect로 이동)
  - Top Consumers 연관 검색: 해당 클래스가 Top Consumers에 있으면 상세 분석 링크 표시
- `findRelatedLeakSuspects()` 헬퍼 함수 추가: 클래스명(전체/단순)으로 suspect 검색
- `findClassInTopConsumers()` 헬퍼 함수 추가: Top Consumers 테이블에서 클래스 검색
- 동작 흐름: Top Components에 있는 클래스 → 풍부한 상세 표시, 없는 클래스 → 개선된 Histogram 폴백 표시

## [2026-04-07] Histogram Retained/Shallow Heap 값 사람이 읽기 쉬운 형식으로 개선

[HistogramEntry.java]
- `getRetainedHeapHuman()` 메서드 추가: `>= 1,020,644,584` → `≥ 973.4 MB` 형식으로 변환
- `getShallowHeapHuman()` 메서드 추가: 바이트 값을 KB/MB/GB 단위로 자동 변환
- `formatBytes()` 헬퍼 메서드 추가 (B/KB/MB/GB 자동 단위 선택)

[analyze.html]
- Histogram 테이블의 Shallow Heap, Retained Heap 컬럼을 human-readable 형식으로 표시
- 정확한 바이트 값은 마우스 hover 시 툴팁으로 표시

## [2026-04-07] Top Consumers 컴포넌트 상세 모달 한국어 번역

[analyze.html]
- 컴포넌트 상세 모달의 모든 UI 텍스트를 한국어로 번역:
  - 모달 제목: "Component Detail" → "컴포넌트 상세"
  - 탭: "Analysis" → "분석 결과", "Raw Data" → "원본 데이터"
  - 버튼: "Close" → "닫기"
  - 로딩/에러 메시지 한국어화
- MAT 영문 콘텐츠 한국어 번역 시스템 추가 (프론트엔드 JS):
  - `MAT_TITLE_MAP`: 섹션 제목 번역 사전 (Miscellaneous→기타 분석, Map Collision Ratios→맵 충돌 비율 등 30+항목)
  - `MAT_HEADER_MAP`: 테이블 헤더 번역 사전 (Class Name→클래스명, Shallow Heap→얕은 힙 등 25+항목)
  - `MAT_TEXT_PATTERNS`: 정규식 기반 본문 텍스트 번역 패턴 (인스턴스 보유 바이트, 충돌 비율 감지, 메모리 축적 등 15+패턴)
  - `trTitle()`, `trHeader()`, `trText()` 번역 함수
- 메타데이터 카드 라벨 한국어화: Size→크기, Classes→클래스 수, Objects→객체 수, Class Loader→클래스 로더
- 뱃지 텍스트 한국어화: Warning→경고, Error→오류
- 테이블 하단 "more rows" 메시지 한국어화
- HTML 엔티티(`&gt;`, `&#61;` 등) 디코딩 처리 추가 (`decodeHtmlEntities()`)
- `MAT_SENTENCE_PATTERNS` 추가: 전체 문장 단위 번역 패턴 (참조 통계, Finalizer, 빈 컬렉션, 채움 비율 등 20+패턴)
  - Soft/Weak/Phantom Reference Statistics 본문 전체 번역
  - Finalizer Statistics 본문 번역
  - "No excessive usage of ..." 계열 문구 전체 번역
  - "No serious amount of ..." 계열 문구 전체 번역
  - Duplicate Strings "Found N occurrences of char[] with at least M instances..." 번역
  - "N × value (bytes)" 리스트 항목 번역 (bytes→바이트)
  - `&#34;` (큰따옴표) HTML 엔티티 디코딩 추가

## [2026-04-07] Raw Data Suspects 상세 페이지 클래스명 링크 활성화

[analyze.html]
- Suspects Details iframe 내 클래스명(FQCN) 링크 클릭 시 컴포넌트 상세 모달 열리도록 구현
- `activateClassLinksInIframe()` 함수 추가:
  - `javascript:void(0)` 링크 중 FQCN 패턴 매칭하여 활성화
  - 비활성 스타일(opacity:0.4, cursor:not-allowed) → 클릭 가능 스타일(파란색 밑줄)로 변경
  - 클릭 시 부모 window의 `showComponentDetail(className)` 호출
  - 중복 바인딩 방지 (`_classLinkActivated` 플래그)
- iframe 내부 네비게이션(서브 페이지 이동) 후에도 자동 재활성화 (load 이벤트 재바인딩)
- "First 10 of N objects" 등 비-클래스명 링크는 무시
- Top Components에 없는 클래스 클릭 시 Histogram 폴백 표시:
  - `findClassInHistogram()`: Histogram 테이블에서 클래스명으로 객체 수/Shallow Heap/Retained Heap 검색
  - `renderHistogramFallback()`: 검색된 기본 정보를 메타데이터 카드로 표시 + 안내 메시지
  - Histogram에도 없는 경우 기존 에러 메시지 유지
  - `formatBytesHuman()`: 바이트 수를 GB/MB/KB/B 단위로 자동 변환, `>=` 접두사를 `≥`로 표시
  - 클래스명을 별도 full-width 행으로 분리하여 긴 FQCN이 레이아웃을 침범하지 않도록 `word-break:break-all` 적용

## [2026-03-20] 메인 페이지 대시보드 레이아웃 리팩토링

[index.html]
- 대시보드 상단 레이아웃을 2컬럼 구조로 변경:
  - 왼쪽: FILES, DISK, ANALYZED, SUSPECTS 스탯 카드 2×2 그리드
  - 오른쪽: Analysis completed 프로그레스 바 + Analysis Queue 패널 세로 스택
- 기존 가로 일렬 stats-bar를 개별 stat-card 컴포넌트로 분리
- 모바일(≤900px): 1컬럼으로 폴백, 스탯 카드 2열 유지
- 모바일(≤480px): 스탯 카드 2열 유지

- Settings 모달 전면 폰트/스타일 개선:
  - General/System/API 탭 간 폰트 크기 통일: s-key 13px, s-val 13px, s-status 13px
  - s-val에서 모노스페이스 제거 → 경로(s-val-path)와 수치(s-val-mono)에만 선별 적용
  - 새 클래스 s-val-mono 추가: 숫자/버전/메모리 값에 모노스페이스 12px 적용
  - s-key에 font-weight:500 추가로 라벨 가독성 향상
  - s-group-title에 하단 구분선(border-bottom) 추가
  - s-api-method, s-api-path, s-copy-btn에 vertical-align:middle 정렬
  - 데스크톱(≥1024px) 미디어 쿼리에 Settings 폰트 스케일링 추가

## [2026-03-20] 메인 페이지 큐 상태 패널 + Settings 모달 크기 고정

[index.html]
- 메인 페이지에 Analysis Queue 패널 추가: 현재 분석 중인 파일과 대기 수를 실시간 표시
- 큐 활성 시 5초 간격 자동 폴링, 큐 비활성 시 패널 자동 숨김 + 폴링 중지
- Refresh 버튼으로 수동 새로고침 가능 (회전 애니메이션)
- Settings 모달에 height: 520px 고정하여 탭 전환 시 크기 변동 방지

## [2026-03-20] 분석 동시 실행 제한 — 큐 기반 순차 처리

[HeapDumpAnalyzerService.java]
- Semaphore(1) 기반 동시 분석 제한: 한 번에 1개 파일만 MAT CLI 분석 실행
- 추가 분석 요청은 자동으로 큐에 적재, 순서대로 처리
- 큐 대기 중 3초 간격으로 SSE를 통해 대기 상태(순서, 현재 분석 파일) 전송
- 클라이언트 연결 해제/인터럽트 시 큐에서도 안전하게 제거
- getQueueSize(), getCurrentAnalysisFilename() API 메서드 추가

[HeapDumpController.java]
- GET /api/queue/status 엔드포인트 추가 (큐 크기, 현재 분석 중인 파일 조회)

[AnalysisProgress.java]
- queuePosition, currentAnalysis 필드 추가
- queued() 팩토리 메서드에 큐 위치/현재 분석 파일 파라미터 추가

[progress.html]
- QUEUED 상태 처리: 큐 대기 배너 UI 추가 (보라색 그라디언트, 대기 순서/현재 분석 파일 표시)
- 큐 대기 → 분석 시작 전환 시 배너 자동 숨김
- 데스크톱/모바일 반응형 스타일 적용

## [2026-03-20] Analysis Files 페이지 다운로드 모달 추가

[files.html]
- 파일 다운로드 버튼 클릭 시 확인 모달 표시 (파일명, 파일 크기 정보 포함)
- index.html의 다운로드 모달 패턴과 동일한 UX 적용
- 다운로드 버튼 스타일(.btn-download) 추가

## [2026-03-20] Heap 데이터 없는 분석 결과를 ERROR(failed)로 표시

[HeapDumpAnalyzerService.java]
- 분석 완료 후 Heap 데이터가 없으면 (totalHeapSize=0, usedHeapSize=0) ERROR 상태로 저장
- SSE에 error 이벤트 전송 → progress.html에서 에러 배너 표시
- 디스크 복원 시 ERROR 상태 결과도 캐시에 로드 (기존에는 SUCCESS만 로드)
- 기존에 SUCCESS로 저장된 Heap 데이터 없는 결과를 자동으로 ERROR로 보정 + result.json 업데이트

[index.html]
- Analysis Files: ERROR 상태 파일에 "failed" 배지 표시 (클릭 시 재분석 페이지 이동)
- Recent Analysis: ERROR 항목에 "failed" 배지 + 빨간색 파일명 (클릭 시 재분석)

[files.html]
- ERROR 상태 파일에도 Analyze(Re-Analyze) 버튼 표시

## [2026-03-20] 메인페이지 레이아웃 개선: Analysis completed + Recent Analysis 패널

[index.html]
- "Analysis coverage" 프로그레스 바 라벨을 "Analysis completed"로 변경
- 메인 영역 2열 그리드 활용: 좌측 Analysis Files, 우측 Recent Analysis 패널 배치
- Recent Analysis 패널: 분석 수행 이력(성공/에러) 최근 10건 표시
  - 파일명(클릭 시 결과 페이지 이동), 상태 배지, 소요 시간, Heap 사용량, Suspect 수, 날짜
  - 데스크탑/모바일 반응형 스타일 적용

[HeapDumpController.java]
- AnalysisHistoryItem에 analysisTime, formattedAnalysisTime, heapUsed 필드 추가
- buildHistory()에서 분석 시간/Heap 사용량 데이터 설정
- formatDuration() 유틸 메서드 추가 (ms → 사람이 읽기 쉬운 형태)
- recentAnalyses 모델 속성 추가 (성공+에러 이력, 최대 10건)

## [2026-03-20] Analysis Files 페이지에 다운로드/삭제 기능 추가

[files.html]
- 각 파일 항목에 다운로드/삭제 SVG 아이콘 버튼 추가 (index.html Recent Files와 동일한 스타일)
  - View(눈 아이콘, 녹색), Analyze(돋보기, 파란색), Download(화살표), Delete(휴지통, hover 빨간색)
- 삭제 확인 모달 (파일명 표시, 취소/삭제 버튼, 배경 클릭으로 닫기)
- Thymeleaf 보안 정책 대응: th:onclick → th:data-filename + onclick 방식으로 변경

[HeapDumpController.java]
- deleteFile() 메서드에 Referer 헤더 확인 추가 — /files 페이지에서 삭제 시 /files로 리다이렉트

## [2026-03-20] 사이드바 Files → Recent Files 이름 변경, 상단 Compare dumps 버튼 제거

[index.html]
- 좌측 사이드바 "Files" → "Recent Files"로 이름 변경
- 상단 topbar에서 "Compare dumps" 버튼 제거 (Quick actions 및 사이드바 Compare 섹션으로 접근 가능)

## [2026-03-20] View all 링크 상시 표시 + stats-bar 모바일 2x2 레이아웃

[index.html]
- "View all N files →" 링크: 5개 초과 조건 제거, 항상 표시
- stats-bar 모바일(≤768px): 2x2 그리드 레이아웃 (위 2개 + 아래 2개)
- stats-bar 초소형(≤480px): 기존 세로 1열 → 2x2 유지, 아이콘/폰트 축소

## [2026-03-20] Analysis Files 전체 목록 페이지 추가

[HeapDumpController.java]
- GET /files 엔드포인트 추가 — 전체 파일 목록 페이지
- 메인 페이지: analysisHistory를 최대 5개만 표시, hasMoreFiles/totalFileCount 모델 속성 추가

[files.html] (신규)
- 전체 파일 목록 전용 페이지
- 검색(필터) 기능 포함 — 파일명 실시간 필터링
- 상단에 파일 수, 분석 완료 수, 전체 용량 표시
- 상태별 아이콘 + view/error/analyze 배지
- 반응형 대응 (모바일에서 날짜 컬럼 숨김)

[index.html]
- Analysis Files 패널에 "View all N files →" 링크 추가 (5개 초과 시 표시)

## [2026-03-20] 메인페이지 패널 통합 (Files overview 제거)

[index.html]
- "Files overview" 패널 삭제 — "Analysis history"와 역할 중복
- "Analysis history" → "Analysis Files"로 이름 변경
- Analysis Files 패널을 full-width로 변경 (grid2 내 full 클래스 적용)
- pending 상태 파일에 "analyze" 링크 버튼 추가 (기존 Files overview의 Analyze 기능 통합)
- .hb-analyze 스타일 추가 (파란색 배지)
- "New" 배지 제거
- Export 모달 텍스트 "Analysis history" → "Analysis files"로 변경

## [2026-03-20] 메인페이지 Stats 레이아웃 개선

[index.html]
- Stats 영역: 4개 개별 카드(stats-row) → 아이콘 포함 가로 1줄 바(stats-bar)로 변경
  · 파일/디스크/체크/경고 SVG 아이콘 + 컬러 배경 추가
  · Analyzed 항목에 "분석완료 / 전체" 형식 표시 (예: 3 / 5)
  · 세로 공간 약 60% 절약
- Analysis coverage 프로그레스 바 추가
  · 전체 파일 대비 분석 완료율을 시각적으로 표시
  · 파란색→초록색 그라데이션
- 반응형: 태블릿(≤900px) 2x2 그리드, 모바일(≤480px) 세로 1열 스택

## [2026-03-20] 분석 화면(analyze.html) 레이아웃 개선

[analyze.html]
- KPI 영역: 6개 개별 카드(kpi-grid) → 아이콘 포함 가로 1줄 바(kpi-bar)로 통합
  · 각 항목에 컬러 배경 아이콘(차트/삼각형/퍼센트/톱니/경고 등)
  · label을 값 아래로 이동하여 시선 흐름 개선
  · 세로 공간 약 60% 절약
- 차트 영역 최적화
  · 차트 최대 높이 320px → 260px
  · 카드 padding 24px → 16px, margin 20px → 14px
  · Stacked Bar 높이 36px → 28px
  · Treemap 높이 200px → 160px
  · 전체 컨텐츠 padding 24px → 16px/20px
- 반응형: 태블릿(≤768px) KPI 2열 래핑, 모바일(≤480px) 1열 세로 스택

## [2026-03-20] API/Settings 기능 고도화

[HeapDumpController.java]
- GET /api/settings 응답 확장:
  · system: Java 버전/벤더, OS, CPU 코어, JVM 메모리(max/total/used/free)
  · disk: 전체/사용/여유 공간, 사용률(%)
  · mat: 파일 존재, 실행 가능, 읽기 가능, ready 상태, statusMessage
  · files: 총 파일 수, 총 크기, 분석 완료 수

[index.html]
- Quick Actions의 "API / Settings" 버튼: window.open(raw JSON) → 3탭 Settings 모달
  · General 탭: MAT CLI 경로/상태(Ready/Not found), Keep Unreachable 설정,
    저장소 경로, 캐시 수, 디스크 사용량 프로그레스바
  · System 탭: Java 버전/벤더, OS, CPU 코어, JVM 메모리 사용량 프로그레스바
  · API 탭: 6개 API 엔드포인트 카드(GET/POST 뱃지, 설명, Copy 버튼), Raw JSON 링크
- Refresh 버튼으로 서버에서 최신 데이터 재조회
- 디스크/JVM 사용률 바에 임계값별 색상 변경 (파랑→주황→빨강)
- 반응형: 모바일/태블릿 대응

## [2026-03-20] MAT CLI 시작 로그 및 에러 메시지 정비

[HeapDumpConfig.java]
- System.out.println → SLF4J Logger 전환
- 시각적 박스 형태로 MAT CLI 상태를 시작 로그에 출력 (READY / NOT FOUND)
- 5단계 MAT CLI 검증 로직:
  1. 파일 존재 여부
  2. 일반 파일 여부 (디렉토리가 아닌지)
  3. 읽기 권한
  4. 실행 권한 (자동 부여 시도)
  5. 파일 크기 (0바이트 아닌지)
- 각 단계 실패 시 한국어 해결 방법 안내 (설치 URL, chmod 명령어 등)
- 힙 덤프 디렉토리 쓰기 권한 검증, 디스크 여유 공간 경고 (500MB 미만)
- isMatCliReady() / getMatCliStatusMessage() 메서드 추가

[HeapDumpAnalyzerService.java]
- 분석 시작 전 config.isMatCliReady() 사전 검증
  · 실패 시 사용자에게 "MAT CLI를 사용할 수 없습니다" 에러 + 상세 원인 전달
- MAT CLI 타임아웃 에러 메시지 한국어 개선 (원인 힌트 포함)
- extractMatErrorHint() 메서드 추가:
  · OutOfMemoryError → Xmx 값 증가 안내
  · SnapshotException → 파일 손상/형식 오류 안내
  · Permission denied → 권한 확인 안내
  · No space left → 디스크 정리 안내
  · 기타 Exception/Error → 마지막 에러 라인 추출
- MAT CLI exit code != 0 시 상세 에러 로깅 (WARN → ERROR 레벨)

## [2026-03-22] 분석 완료 후 .index/.threads 파일을 결과 디렉토리로 이동

[HeapDumpAnalyzerService.java]
- moveArtifactsToResultDir() 추가: 분석 완료 후 .index/.threads 파일을 결과 디렉토리로 이동
- migrateStrayArtifacts() 추가: 서버 시작 시 상위 디렉토리에 남은 기존 파일 마이그레이션
- loadThreadStacksText(): 결과 디렉토리 우선 탐색 후 상위 디렉토리 fallback

────────────────────────────────────────────────────────────────────────────────

## [2026-03-22] 에러 페이지 MAT Log 항상 표시 + 분석 실패 일자/상세 정보 추가

[HeapDumpController.java]
- 에러 시 errorDate(실패 일시), errorAnalysisTime(소요 시간), errorFileSize(파일 크기) 모델에 추가

[analyze.html — ERROR STATE]
- 분석 실패 상세 정보 표시: Failed at / Duration / File Size
- MAT Log 카드를 항상 표시 (로그가 없으면 "MAT CLI 로그가 없습니다" 안내 메시지)
- 기존 th:if 조건 제거하여 MAT Log 영역이 항상 보이도록 변경

## [2026-03-22] 분석 실패 화면에 Re-Analyze 버튼 및 전체 MAT Log 보기 기능 추가

[HeapDumpController.java]
- 에러 상태 시 matLogTotalLen에 실제 로그 길이 전달 (기존: 0 고정)

[analyze.html — ERROR STATE]
- 에러 카드에 Re-Analyze 버튼 추가 (/analyze/rerun/{filename}으로 이동)
- MAT CLI Log를 별도 카드로 분리 (기존: <details> 접기)
- 로그가 5000자 초과 시 "Load Full Log" 버튼으로 전체 로그 비동기 로딩
- 헤더에도 에러 상태에서 filename badge + Re-Analyze 버튼 표시

## [2026-03-22] 분석 실패 결과 페이지에서도 MAT Log 접근 보장

[HeapDumpAnalyzerService.java]
- 분석 exception 시 에러 결과에 mat.log 파일 내용 포함 (기존: matLog 미설정)
- getCachedResult에서 ERROR 결과도 디스크에서 복원 (기존: SUCCESS만 복원)
  → 서버 재시작 후에도 에러 결과의 MAT Log를 /analyze/log/ API로 접근 가능

## [2026-03-22] 분석 실패 파일 클릭 시 실패 결과 페이지로 이동

[index.html — 사이드바 Recent Files]
- ERROR 파일 빨간 버튼: /analyze/{f} → /analyze/result/{f} 변경 (실패 결과 페이지로 이동)

[index.html — Analysis History]
- "failed" 배지: /analyze/{f} → /analyze/result/{f} 변경

[files.html — Analysis Files]
- ERROR/NOT_ANALYZED 공용 버튼을 분리:
  - ERROR: /analyze/result/{f}로 이동하는 빨간 버튼 (.fb.e)
  - NOT_ANALYZED: /analyze/{f}로 이동하는 파란 버튼 (.fb.p)
- .fb.e 스타일 추가 (빨간 배경 #EF4444)

## [2026-03-22] 분석 실패 결과 영속화 (파일 삭제 전까지 유지)

[HeapDumpAnalyzerService.java]
- 분석 실패(Exception) 시 ERROR 상태의 HeapAnalysisResult를 생성하여 memCache + 디스크(result.json)에 저장
  - 기존: catch 블록에서 SSE 에러 메시지만 전송, 실패 정보 유실
  - 수정: 실패 파일도 tmp→final 이동 후 ERROR 결과 저장, 파일 삭제 시 함께 정리
- cloneWithoutLog()에 errorMessage 필드 복사 누락 수정
  - 기존: 디스크 저장 시 errorMessage가 null로 저장됨
  - 수정: errorMessage도 정상 복사되어 서버 재시작 후에도 에러 메시지 유지

## [2026-03-22] 메인 페이지 사이드바 분석 실패 파일 빨간색 표시

[HeapDumpController.java]
- `errorFiles` Set 추가: ERROR 상태 파일명을 템플릿에 전달

[index.html — 사이드바 Recent Files]
- ERROR 파일: 행 전체 빨간 배경/테두리 (`.file-err`), 파일명 빨간색, ext-badge 빨간색
- ERROR 파일: 빨간 Re-Analyze 버튼 (`.fb.e`) — 기존 파란 Analyze 버튼 대신 표시
- Tooltip Status: Analyzed / Failed / Not analyzed 3단계 구분
- 미분석 Analyze 버튼: analyzedFiles와 errorFiles 모두 아닌 경우에만 표시

[index.html — Analysis History 패널]
- ERROR 상태 파일명에 `.hname-err` 빨간색 적용

[files.html — Analysis Files 목록]
- ERROR 상태 파일명에 `.fname-err` 빨간색 적용

## [2026-03-22] 잘못된 파일 분석 시 성공으로 표시되는 버그 수정

[HeapDumpAnalyzerService.java]
- MAT CLI가 0이 아닌 exit code로 종료 시 즉시 RuntimeException 발생하도록 수정
  - 기존: exit code만 로그하고 파싱 단계로 계속 진행 → 다른 파일 결과를 매칭하여 성공으로 표시
  - 수정: 에러 메시지와 함께 예외 발생 → catch 블록에서 분석 실패 메시지를 클라이언트에 전송

[MatReportParser.java]
- findZipFile()에서 baseName 없이 keyword만으로 ZIP을 매칭하는 fallback 로직 제거
  - 기존: base 이름 불일치해도 keyword(overview, suspects 등)만 일치하면 다른 파일의 ZIP 반환
  - 수정: 반드시 baseName + keyword가 모두 일치하는 ZIP만 매칭

## [2026-03-23] MAT CLI 리포트별 진행률 개별 표시 기능

▶ 대상 파일:
  - src/main/java/com/heapdump/analyzer/model/AnalysisProgress.java
  - src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java
  - src/main/resources/templates/progress.html

▶ 변경 내역:
  1. AnalysisProgress 모델에 reportPhase 필드 추가 (overview, top_components, suspects)
     - reportLog() 팩토리 메서드 추가: 리포트 단계 정보 포함 SSE 이벤트 전송
  2. HeapDumpAnalyzerService - MAT CLI 출력 실시간 파싱으로 리포트 단계 감지
     - MAT CLI 출력에서 "org.eclipse.mat.api:overview/top_components/suspects" 문자열 감지
     - 단계별 진행률 범위 설정: init(15-40%), overview(40-55%), top_components(55-68%), suspects(68-80%)
     - 명령어 실행 순서 변경: overview → top_components → suspects (UI 흐름에 맞게)
  3. progress.html - 3개 리포트 하위 단계(sub-step) UI 추가
     - Overview 리포트 (📊), Top Components 리포트 (🔝), Leak Suspects 리포트 (🕵️)
     - 하위 단계 CSS 스타일 (들여쓰기, 작은 아이콘, 축소 폰트)
     - SSE 이벤트의 reportPhase 필드 기반 단계별 아이콘 상태 전환 (waiting → active → done)

────────────────────────────────────────────────────────────────────────────────

## [2026-03-25] 분석 진행 페이지 2열 레이아웃 (진행 카드 + MAT CLI 로그)

▶ 대상 파일:
  - src/main/resources/templates/progress.html

▶ 변경 내역:
  1. 진행 카드(좌) + MAT CLI 로그(우) 2열 그리드 레이아웃(progress-grid) 적용
     - max-width 780px → 1200px 확대
     - 데스크톱(>900px): 로그 콘솔 항상 표시, 토글 버튼 숨김
     - 로그 콘솔이 진행 카드 높이에 맞춰 flex 확장
  2. 태블릿/모바일(≤900px): 1열 폴백, 기존처럼 토글 버튼으로 로그 열기/닫기
  3. 화면 크기 변경 시 logVisible 상태 자동 동기화

## [2026-03-25] 분석 진행 페이지(progress.html) 전면 개편

▶ 대상 파일:
  - src/main/resources/templates/progress.html

▶ 스타일 개편:
  1. 헤더 영역 통합 — 기존 큰 <header> + 파일 배너를 52px 고정 상단바(progress-topbar)로 전환
     - 파일명을 pill-chip 형태로 컴팩트하게 표시
     - 경과 시간(elapsed-chip) 모노스페이스로 표시
  2. 진행바 + 단계 리스트를 하나의 통합 카드(progress-card)로 합침
  3. 타임라인 스타일 단계 리스트 — 수직 연결선(timeline-line)으로 단계 시각 연결
     - 이모지 아이콘 → 숫자 표시, 완료 시 ✓ 체크 애니메이션(scaleIn)
     - 완료된 단계의 연결선 초록색으로 변경
  4. 에러 배너 — 그라디언트에서 카드 스타일(border-left: 4px solid red)로 변경
     - 실패 단계명 표시, 재분석/홈 버튼 추가
  5. 진행바 높이 18px → 12px로 슬림화
  6. 반응형 개선 — 600px/480px 브레이크포인트 추가, 모바일 터치 타겟 44px 보장

▶ 기능 추가:
  1. 경과 시간 표시 — 상단바에 MM:SS 형식 실시간 타이머 (RUNNING 첫 수신 시 시작)
  2. 단계별 소요 시간 — 각 단계 완료 시 소요 시간 표시 (예: "2.3s")
  3. 페이지 타이틀 진행률 — 브라우저 탭에 "(45%) 분석 중..." 형식으로 표시
  4. 취소 버튼 — SSE 닫기 + 확인 다이얼로그 후 홈 이동
  5. 에러 시 재분석 버튼 — /analyze/rerun/{filename} 링크 활용
  6. 로그 라인 수 표시 — 로그 헤더에 "N lines" 실시간 카운트

▶ 버그 수정:
  1. 로그 자동 스크롤 로직 반전 수정
     - 기존: autoScroll = scrollTop > 50 (맨 위에서 auto-scroll 꺼짐 역설)
     - 수정: scrollHeight - scrollTop - clientHeight < 60 (바닥 근처 감지)
  2. 로그 라인 카운트(lines) 리셋 버그 수정
     - 기존: DOM 트리밍 시 logLineCount 감소 → UI 카운트도 감소 후 재누적
     - 수정: 총 수신 수(logTotalCount) 별도 분리, UI에는 감소하지 않는 총 수 표시

────────────────────────────────────────────────────────────────────────────────

## [2026-03-27] 모바일 홈페이지 가로 스크롤 오버플로우 수정

[index.html]
- html, body에 overflow-x: hidden 추가하여 가로 스크롤 방지
- 900px 이하: main-content에 max-width: 100vw 적용, fname-tooltip 비활성화
- 480px 이하: dashboard-left를 1열 레이아웃으로 변경 (stat-card 가로 넘침 방지)
- 480px 이하: ra-meta에 flex-wrap 적용 (분석 메타정보 줄바꿈)
- 480px 이하: history-item에 flex-wrap 적용 (파일명이 한 줄 전체 사용)
- 480px 이하: main-content 패딩 20px → 12px 축소

## [2026-03-27] 모바일 대시보드 통계 카드 2x2 그리드 레이아웃 적용

[index.html]
- 480px 이하 모바일에서 FILES, DISK, ANALYZED, SUSPECTS 통계 카드가 1열(1fr)로
  세로 배열되던 것을 2x2 그리드(1fr 1fr)로 변경

## [2026-03-27] 토스트 메시지 위치를 상단 중앙으로 변경

[index.html]
- toast() 함수의 위치를 우측하단(bottom:20px;right:20px)에서 상단 중앙(top:20px;left:50%;transform:translateX(-50%))으로 변경
- box-shadow 추가로 가시성 향상

## [2026-03-27] 모바일 topbar에 Settings 버튼 추가

[index.html]
- 모바일(≤900px)에서 Quick actions까지 스크롤하지 않아도 Settings 모달에 접근할 수 있도록
  topbar 우측에 Settings 버튼 추가 (데스크탑에서는 숨김)
- 톱니바퀴 SVG 아이콘 포함

## [2026-03-27] 파일 업로드 시 디스크 사용량 90% 이상 경고

[HeapDumpController.java]
- 업로드 성공 후 파일시스템 사용량 체크, 90% 이상이면 warning flash attribute로 경고 메시지 표시

[index.html]
- .alert-warning 스타일 추가 (노란색 배경, 주황색 테두리)
- warning flash attribute 표시 영역 추가

## [2026-03-27] 파일 업로드 전 디스크 사용량 사전 체크 추가

[HeapDumpController.java]
- GET /api/disk/check API 추가: 디스크 사용률, 전체/가용 용량, 90% 이상 warning 플래그 반환

[index.html]
- doUpload()에서 업로드 전 /api/disk/check 호출하여 사전 체크
- 90% 이상 시 경고 모달(showDiskWarningModal) 표시: 사용률, 가용 공간, 파일 정보 안내
- Cancel / Upload anyway 버튼으로 사용자 선택 가능
- 기존 업로드 로직을 startUpload()로 분리

## [2026-03-27] 메인 페이지 DISK 카드에 사용량 퍼센트 표시

[HeapDumpController.java]
- index() 메서드에 diskUsedPercent, diskUsed, diskTotal 모델 속성 추가

[index.html]
- DISK 카드 라벨 옆에 사용량 퍼센트 배지 표시
- 70% 미만: 초록색, 70~89%: 노란색, 90% 이상: 빨간색 배지

## [2026-03-27] Settings 버튼 통일 (Quick Actions에서 제거, 상단 Settings로 통일)

[index.html]
- Quick Actions에서 "API / Settings" 버튼 삭제
- 상단 topbar의 Settings 버튼을 모바일/데스크탑 모두 항상 표시하도록 변경
  (기존: 모바일에서 display:none → 변경: 항상 inline-flex)
- 모바일 미디어쿼리 내 중복 topbar-settings-btn 선언 제거

## [2026-03-27] 분석 이력 영속화 및 데이터 디렉토리 분리

[HeapDumpConfig.java]
- getDataDirectory() 메서드 추가 (heapdump.directory/data)
- initHeapDumpDirectory()에서 data 디렉토리 자동 생성

[HeapDumpAnalyzerService.java]
- resultDirectory() 경로를 /opt/heapdumps/data/{basename}/ 으로 변경
- deleteFile()에서 clearCache() 호출 제거 → 힙덤프만 삭제, 분석 결과 보존
- restoreResultsFromDisk() 리팩토링: data 디렉토리 스캔
- migrateOldResultDirs() 추가: 기존 결과 디렉토리를 data/로 자동 마이그레이션
- migrateStrayArtifacts() 타겟을 data 디렉토리로 변경
- getAllCachedResults(), getCacheKeys() 메서드 추가

[HeapDumpController.java]
- AnalysisHistoryItem에 fileDeleted, lastModified 필드 추가
- buildHistory(): 물리 파일 외 캐시에만 남은 분석 결과도 포함 (fileDeleted=true)
- getHistory() API: fileDeleted 필드 포함
- clearAllCache(): 캐시 키 기반으로 전체 삭제 방식 변경

[index.html]
- "Recent Analysis" → "Analysis history" 이름 변경
- "View all history →" 버튼 추가
- 삭제된 파일 표시: ra-deleted 스타일, "deleted" 배지
- Analysis Files 패널에서 삭제된 파일 표시 (hi-deleted, hb-del)
- 삭제 모달 메시지 변경 ("분석 결과는 보존됩니다")

[files.html]
- 삭제된 파일 분석 이력 표시 (fi-deleted 스타일, fi-del-badge)
- 삭제된 파일: download/delete/analyze 버튼 숨김, view만 표시
- 삭제 모달 메시지 변경

## [2026-03-27] Analysis History 전용 페이지 추가

[HeapDumpController.java]
- GET /history 엔드포인트 추가 (분석 수행된 항목만 필터하여 표시)
- successCount, errorCount, totalCount 통계 제공

[history.html] (신규)
- 분석 이력 전용 페이지: SUCCESS/ERROR 항목만 표시 (NOT_ANALYZED 제외)
- 각 항목에 분석 시간, 힙 사용량, suspect 수, 파일 크기, 날짜 표시
- 삭제된 파일 표시 (deleted 배지, 흐린 스타일)
- 검색 필터 기능
- SUCCESS 항목은 결과 보기 링크, ERROR+파일존재는 재분석 링크 제공

[index.html]
- "View all history" 링크 대상을 /files → /history 로 변경

## [2026-03-27] 분석 진행 화면 — 리포트별 실시간 프로그레스 표시

[HeapDumpAnalyzerService.java]
- MAT CLI 리포트 phase 감지 패턴 수정 (기존 "org.eclipse.mat.api:*" → 실제 출력 패턴)
  - "Subtask: System Overview" → overview phase
  - "Subtask: Top Component" (Reports/s) → top_components phase
  - "Subtask: Leak Suspects" → suspects phase
- 동일 phase 재진입 방지 (이미 감지된 phase는 스킵)
- SSE 전송 최적화: 진행률 변경 시 또는 50줄마다 1회 전송 (과도한 SSE 방지)
- lineCount 추적 추가

[progress.html]
- 리포트 서브스텝에 미니 프로그레스 바 추가 (overview/topcomp/suspects)
- MAT CLI 시작(init phase) 시 세 개의 빈 프로그레스 트랙 미리 표시
- 각 리포트 실행 중 해당 바가 파란색으로 채워지고, 완료 시 초록색 전환
- 리포트 순서 독립적 처리 (_lastReportPhase 기반, MAT CLI 실행 순서 변동 대응)
- setSubProgress(), completeSubProgress(), showSubProgressTracks() 함수 추가

## [2026-03-27] 분석 실패 UI 개선 — 파스텔 배경 + 실패 단계 강조

[progress.html]
- 에러 배너 배경을 파스텔 빨간색 그라데이션으로 변경 (FFF5F5 → FEE2E2)
- 에러 아이콘을 빨간 원형 ✗ 마크로 변경 (기존 노란 경고)
- 에러 메시지를 반투명 배경 박스로 감싸 가독성 향상
- 실패 단계 표시를 헤더 바로 아래에 배치, 빨간 텍스트로 명확히 표시
- 에러 시 프로그레스 카드 전체에 파스텔 빨간 배경 적용 (.card-error)
- 실패한 타임라인 단계: 빨간 배경/테두리로 강조, 라벨/설명 빨간색
- 실패 단계 이후 대기 중인 단계들을 흐리게 처리 (opacity: 0.35)
- active 단계 없이 에러 발생 시 마지막 비완료 단계를 에러로 처리
- 실패 단계의 desc를 "{단계명} 실패"로 변경 (예: "파일 확인 실패", "MAT CLI 실행 실패")

## [2026-03-27] 디스크 용량 부족 업로드 차단 + 90% 이상 경고 배너

[HeapDumpController.java]
- /api/disk/check 응답에 usableSpaceBytes (raw bytes) 필드 추가

[index.html]
- 업로드 시 파일 크기가 디스크 여유 공간보다 클 경우 업로드 차단 모달 표시
  (빨간 X 아이콘, 파일 크기 vs 여유 공간 비교, 한국어 안내 메시지)
- doUpload()에서 usableSpaceBytes와 file.size 비교 로직 추가
- 메인 홈페이지 대시보드 상단에 디스크 사용률 90% 이상 경고 배너 추가
  (노란 그라데이션 배경, 경고 아이콘, 사용률 수치 표시)
- disk-warn-banner CSS 스타일 추가

## [2026-03-27] 분석 완료 후 덤프 파일 gzip 압축

[HeapDumpAnalyzerService.java]
- 분석 성공 후 덤프 파일을 자동으로 gzip 압축 (compressDumpFile 메서드 추가)
- 압축 전 디스크 여유 공간이 원본 파일 크기 이상인지 점검, 부족 시 압축 건너뜀
- isValidHeapDumpFile()에 .hprof.gz, .bin.gz, .dump.gz 확장자 추가
- getFile()에서 .gz 압축 파일도 탐색하도록 수정
- deleteFile()에서 .gz 압축 파일도 함께 삭제하도록 수정
- stripExtension()에서 .gz 이중 확장자 처리 (.hprof.gz → base name)
- 재분석(re-analyze) 시 .gz 압축 파일 자동 감지 → 압축 해제 후 분석 → 완료 후 재압축
- decompressDumpFile 메서드 추가 (디스크 여유 공간 점검 포함)
- 원본과 .gz 동시 존재 시 중복 처리:
  · cleanupDuplicateGzFiles(): 원본이 있으면 .gz 삭제 (디스크 절약)
  · @PostConstruct 기동 시 중복 파일 자동 정리
  · analyzeWithProgress(): 분석 시작 시 원본+.gz 동시 존재하면 .gz 삭제
  · compressDumpFile(): 기존 .gz 존재 시 삭제 후 재압축
  · listFiles(): 원본과 .gz 중복 노출 방지, .gz는 원본 이름으로 표시

[HeapDumpController.java]
- downloadFile()에서 실제 파일명(.gz 포함)을 다운로드 파일명으로 사용하도록 수정

## [2026-03-29] Cache → Save 용어 통일 (디스크 저장이므로 Cache가 아님)

[index.html]
- "Cache results to disk" → "Save results to disk"
- "Clear all cache" → "Clear all results"
- "Remove all cached results" → "Remove all saved results"
- "Clear All Cache" 모달 제목 → "Clear All Results"
- "All cached analysis results..." → "All saved analysis results..."
- "Cached Results" (Settings) → "Saved Results"
- API 문서 /api/cache/clear → /api/results/clear
- fetch 호출 경로 /api/cache/clear → /api/results/clear

[analyze.html]
- "Cached results will be cleared..." → "Saved results will be cleared..."

[HeapDumpController.java]
- API 엔드포인트 /api/results/clear 추가 (기존 /api/cache/clear도 호환 유지)
- 주석 및 로그 메시지의 "캐시" → "저장 결과"로 변경

[HeapDumpAnalyzerService.java]
- 로그 메시지의 "cached results" → "saved results" 변경
- "disk cache" → "saved result" 변경

## [2026-03-29] Analysis History 페이지 히스토리 삭제 기능 추가

[HeapDumpAnalyzerService.java]
- deleteHistory() 메서드 추가: 힙덤프 파일 + 분석 결과 디렉토리 + 인덱스 파일 + 메모리 캐시 일괄 삭제

[HeapDumpController.java]
- GET /history/delete/{filename} 엔드포인트 추가
- 삭제 성공/실패 시 flash 메시지로 결과 전달 후 /history로 리다이렉트

[history.html]
- 각 히스토리 항목에 삭제 버튼(휴지통 아이콘) 추가
- 삭제 확인 모달 추가 (파일명 표시, 경고 메시지, 취소/삭제 버튼)
- 힙덤프 파일이 이미 삭제된 경우 모달 내 파일명 옆에 "힙덤프 파일 이미 삭제됨" 뱃지 표시

[HeapDumpController.java]
- Settings API 디스크 사용량 계산을 getFreeSpace() → getUsableSpace()로 통일
  (홈페이지 DISK %와 Settings Disk Usage % 수치 불일치 해소)

## [2026-03-29] MAT JVM 힙 메모리 설정 기능 추가

[HeapDumpAnalyzerService.java]
- getMatHeapSize(): MemoryAnalyzer.ini에서 -Xmx 값을 바이트 단위로 읽기
- getMatHeapSizeString(): -Xmx 문자열 반환 (예: "2048m")
- setMatHeapSize(): MemoryAnalyzer.ini의 -Xmx 값 변경
- 분석 시작 시 덤프 크기 × 2 > MAT 힙 설정이면 SSE 경고 메시지 전송

[AnalysisProgress.java]
- warningMessage 필드 추가
- warning() 팩토리 메서드 추가 (분석 계속 진행하면서 경고 전달)

[HeapDumpController.java]
- GET /api/mat/heap: 현재 MAT 힙 설정 조회
- POST /api/mat/heap?size=4096m: MAT 힙 설정 변경
- GET /api/settings 응답에 mat.heapSize, mat.heapBytes, mat.heapFormatted 추가

[index.html]
- Settings 모달 MAT Configuration에 "MAT JVM Heap (-Xmx)" 표시 및 편집 UI 추가
- 프리셋 선택 (1GB ~ 16GB) + Apply/Cancel 버튼
- 권장 사항 안내 문구 표시

[progress.html]
- 경고 배너 (warn-banner) 추가: 노란색 그라데이션, 경고 아이콘
- SSE warningMessage 수신 시 경고 배너 표시
- Dashboard Settings 링크 안내

## [2026-03-29] 분석 전 힙 메모리 부족 경고 모달 및 전역 설정 추가

[HeapDumpController.java]
- GET /api/mat/heap-check?filename= 추가: 분석 전 덤프 크기 × 2 vs MAT 힙 사전 체크

[progress.html]
- SSE 시작 전 /api/mat/heap-check 호출로 사전 체크
- 힙 부족 시 경고 모달 표시: 덤프 크기, 권장 힙, 현재 설정 정보
- "분석 계속" / "취소" 버튼으로 사용자 선택
- "다음부터 이 경고를 표시하지 않고 바로 분석 진행" 체크박스
- skipHeapWarning=true면 경고 없이 바로 분석 시작

[index.html]
- Settings 사이드바에 "Skip heap memory warning" 토글 추가
- localStorage 기반 전역 설정 (skipHeapWarning)

[HeapDumpAnalyzerService.java]
- 서버 측 SSE 경고 제거 (클라이언트 사전 체크로 대체)

## [2026-03-29] Settings 별도 페이지 분리 + 홈페이지 레이아웃 개편

[settings.html] — 신규 생성
- 별도 전용 페이지로 Settings 전면 개편 (기존 모달 방식에서 분리)
- 2-column 그리드 레이아웃으로 관리자 친화적 배치:
  · MAT Configuration: CLI 경로, 상태, JVM Heap 설정 (인라인 편집)
  · Analysis Options: Keep Unreachable, Auto-analyze, Save to disk, Skip heap warning (토글)
  · Storage: 덤프 디렉토리, 저장된 결과 수, 파일 수, 총 크기
  · Disk Usage: 사용량/전체, 여유 공간, 프로그레스바
  · System Info: Java, OS, CPU
  · JVM Memory: Max/Used/Free, 프로그레스바
  · API Endpoints: 2-column 카드 레이아웃
  · Danger Zone: Clear All Results (빨간 경계선)
- 확인 모달: Keep Unreachable 해제, Auto-analyze 활성화, Clear All Results

[HeapDumpController.java]
- GET /settings 엔드포인트 추가 → settings.html 반환

[index.html] — 구조 개편
- MAT OPTIONS 사이드바 섹션 제거 (Settings 페이지로 이관)
- Quick Actions를 왼쪽 사이드바로 이동 (Compare, Export History, Settings 링크)
- Quick Actions 메인 패널 제거
- topbar Settings 버튼을 /settings 페이지 링크로 변경 (모달 방식 폐지)
- Settings 모달 HTML/CSS/JS 전체 제거
- Keep Unreachable, Auto-Analyze, Clear Cache 모달 제거
- 관련 JS 함수 정리 (confirmKeepUnreachable, toggleMatHeapEdit, saveMatHeap 등)

## [2026-03-29] MAT -Xms 설정 추가 + 힙 메모리 경고 검증

[HeapDumpAnalyzerService.java]
- readIniJvmArg/writeIniJvmArg 공통 메서드로 리팩토링
- getMatInitialHeapSize(), getMatInitialHeapSizeString(), setMatInitialHeapSize() 추가

[HeapDumpController.java]
- GET /api/mat/heap: xmsSize, xmsBytes, xmsFormatted, physicalMemory 정보 추가
- POST /api/mat/heap: type=xms 파라미터 지원 (-Xms 변경)
- GET /api/settings: mat 섹션에 xmsSize, xmsBytes, xmsFormatted 추가

[settings.html]
- MAT Configuration에 "MAT JVM Initial Heap (-Xms)" 행 + 인라인 편집 추가
- "System Physical Memory" 표시 행 추가
- -Xmx Apply 시 경고 모달:
  · 시스템 물리 메모리 초과 시 경고 (스왑/OOM Killer 위험)
  · -Xms보다 작을 경우 경고 (JVM 시작 불가)
- -Xms Apply 시 경고 모달:
  · -Xmx보다 클 경우 경고
  · 시스템 물리 메모리 초과 시 경고
- 경고 모달에서 "강제 적용" 또는 "Cancel" 선택 가능

## [2026-03-29] 분석 페이지 헤더/사이드바를 메인 페이지 기준으로 통일

[analyze.html]
- 헤더 높이 56px → 52px (메인 페이지와 동일)
- 사이드바 너비 290px → 300px (메인 페이지와 동일)
- 헤더에 box-shadow, justify-content:space-between 추가
- 헤더 HTML을 메인 페이지와 동일한 로고 아이콘(파란 둥근 사각형 + 체크) + 제목 구조로 변경
- 파일명 뱃지를 브랜드 영역에 포함
- 버튼 스타일 통일 (색상 #6B7280, hover 배경)
- 데스크톱/태블릿/모바일 미디어쿼리 조정

## [2026-03-29] [보안] XSS 방어 강화 — OWASP HTML Sanitizer 도입 (Phase 1A)

[pom.xml]
- owasp-java-html-sanitizer 20220608.1 의존성 추가

[신규: util/HtmlSanitizer.java]
- OWASP PolicyFactory 기반 whitelist HTML 새니타이저
- MAT 리포트에서 사용하는 태그만 허용 (table, div, span, a, 서식 태그 등)
- body 추출 패턴 사전 컴파일 (static final Pattern)
- 깨진 href → javascript:void(0) 치환

[parser/MatReportParser.java]
- sanitizeHtml(): regex 기반 제거 → HtmlSanitizer.sanitize() 위임
- 약 30줄의 regex 체인 제거

[service/HeapDumpAnalyzerService.java]
- extractBodyContent(): 동일한 regex 기반 제거 → HtmlSanitizer.sanitize() 위임
- MatReportParser와의 중복 코드 완전 제거

## [2026-03-29] [보안] Path Traversal 차단 — Controller 파일명 검증 (Phase 1B)

[신규: util/FilenameValidator.java]
- 파일명 검증 유틸리티: null/empty, path traversal(.., /, \), null byte 차단
- 허용 확장자 whitelist: .hprof, .bin, .dump (+.gz 변형)
- Paths.get().getFileName()으로 경로 구성요소 제거

[controller/HeapDumpController.java]
- 16개 엔드포인트 + compare의 2개 파라미터에 FilenameValidator.validate() 적용
- @ExceptionHandler(IllegalArgumentException.class) 추가 → 400 응답
- 검증 실패 시 로그 기록: "[Validation] Rejected request: ..."

## [2026-03-29] [보안] 정보 노출 축소 — /api/settings 응답 최소화 (Phase 1C)

[controller/HeapDumpController.java]
- 절대 경로 마스킹: heapDumpDirectory, mat.path → "***/파일명" 형태로 변환
  - maskPath() 헬퍼 메서드 추가
- 서버 상세 정보 제거: javaVendor, osName, osArch 필드 삭제
- MAT 파일 권한 상세 제거: exists, executable, readable 필드 삭제
  - ready + statusMessage만 유지 (운영 상태 확인에 충분)
- matCliPath 최상위 필드 제거 (mat.path에서 마스킹된 값으로 제공)

[templates/settings.html]
- System Info 카드에서 Java Vendor, OS 행 제거
- JS loadAllData()에서 javaVendor, osName, osArch 참조 제거

## [2026-03-29] [보안] 파괴적 GET → POST 전환 (Phase 1D)

[controller/HeapDumpController.java]
- GET /delete/{filename} → POST 전환 (파일 삭제)
- GET /history/delete/{filename} → POST 전환 (히스토리 삭제)
- GET /analyze/rerun/{filename} → POST 전환 (캐시 삭제 후 재분석)
  → CSRF, 크롤러 오삭제, 링크 프리페치 등에 의한 의도치 않은 파괴적 동작 방지

[templates/index.html]
- showDeleteModal() → postRedirect() 헬퍼로 POST form 제출

[templates/files.html]
- btnConfirmDel 클릭 → 동적 form POST 제출로 변경

[templates/history.html]
- deleteLink <a> 태그 → submitDelete() 함수 + 동적 form POST 제출로 변경

[templates/analyze.html]
- Re-Analyze Thymeleaf <a> 링크 2개 → <form method="post"> + <button> 전환
- doReanalyze() JS 함수 → 동적 form POST 제출로 변경

[templates/progress.html]
- retryBtn → onclick="postRerun()" + 동적 form POST 제출로 변경

## [2026-03-29] [안전성] 스레드 풀 바운딩 (Phase 2A)

[service/HeapDumpAnalyzerService.java]
- Executors.newCachedThreadPool() → ThreadPoolExecutor(core=2, max=4, queue=8) 전환
  - core 2: 평상시 유지되는 스레드 (분석 1 + SSE 1)
  - max 4: 동시 요청 폭주 시 최대 스레드 수 제한
  - queue 8: 대기 작업 큐 크기 제한
  - CallerRunsPolicy: 큐 포화 시 호출자 스레드에서 실행 (요청 거부 대신 백프레셔)
  - keepAlive 60초: 유휴 스레드 자동 회수
- 기존 Semaphore(1) 분석 직렬화 + shutdown 로직은 그대로 유지

## [2026-03-29] [성능] Regex 패턴 사전 컴파일 (Phase 2B)

[parser/MatReportParser.java]
- 12개 static final Pattern 상수 추가:
  NON_DIGIT_PATTERN, COMMA_SPACE_PATTERN, WHITESPACE_PATTERN, HEX_ADDR_PATTERN,
  HEX_ADDR_EXTRACT_PATTERN, TOTAL_ENTRIES_PATTERN, ALL_OBJECTS_SUFFIX_PATTERN,
  PROBLEM_SUSPECT_PATTERN, ARROW_CHAR_PATTERN, ARROW_SPACE_PATTERN,
  ONLY_OBJECT_PATTERN, FIRST_N_OF_PATTERN, ALL_N_OBJECTS_PATTERN, ONLY_N_OBJECTS_PATTERN
- parseSuspectsZip() 로컬 Pattern.compile 2개 → PROBLEM_SUSPECT_PATTERN, TAG_PATTERN 재사용
- parseHistogramEntries() 로컬 Pattern.compile 1개 → TOTAL_ENTRIES_PATTERN
- parseThreadInfoEntries() 로컬 Pattern.compile 1개 → HEX_ADDR_EXTRACT_PATTERN
- 인라인 String.replaceAll() 전체 → Pattern.matcher().replaceAll() 전환
- stripTags() 헬퍼: HTML 태그 제거 + 공백 정규화 (5곳 중복 제거)
- digitsOnly() 헬퍼: 숫자 외 문자 제거 (11곳 중복 제거)

## [2026-03-29] [리팩토링] formatBytes() 통합 (Phase 3A)

[신규: util/FormatUtils.java]
- public static formatBytes(long) — 바이트 크기를 B/KB/MB/GB 문자열로 변환
- KB: %.1f, MB/GB: %.2f 포맷 통일

[controller/HeapDumpController.java]
- private formatBytes() → FormatUtils.formatBytes() 위임

[service/HeapDumpAnalyzerService.java]
- private formatBytes() → FormatUtils.formatBytes() 위임

[model/HeapAnalysisResult.java]
- private formatBytes() → FormatUtils.formatBytes() 위임
- (기존 KB %.2f → %.1f로 통일)

## [2026-03-29] [리팩토링] 하드코딩 값 설정 추출 (Phase 3B)

[config/HeapDumpConfig.java]
- 8개 @Value 프로퍼티 추가 (application.properties로 재정의 가능):
  - mat.timeout.minutes (기본 30) — MAT CLI 실행 타임아웃
  - sse.emitter.timeout.minutes (기본 35) — SSE Emitter 타임아웃
  - dashboard.history.max-display (기본 5) — 대시보드 히스토리 표시 수
  - disk.warning.usage-percent (기본 90) — 디스크 사용률 경고 임계값
  - disk.warning.free-space-mb (기본 500) — 디스크 여유 공간 경고 임계값
  - analysis.top-objects.max-display (기본 10) — Top Memory Objects 표시 수
  - analysis.mat-log.max-display-chars (기본 5000) — 에러 시 MAT 로그 표시 길이
  - analysis.progress.log-update-lines (기본 50) — 진행률 로그 업데이트 빈도
- initHeapDumpDirectory() 내 500MB 하드코딩 → diskWarningFreeSpaceMb 참조

[service/HeapDumpAnalyzerService.java]
- MAT_TIMEOUT_MINUTES 상수 제거 → config.getMatTimeoutMinutes()
- lineCount % 50 → config.getProgressLogUpdateLines()

[controller/HeapDumpController.java]
- config 의존성 주입 추가
- maxDisplay 5 → config.getDashboardHistoryMaxDisplay()
- SSE 35분 → config.getSseEmitterTimeoutMinutes()
- 디스크 경고 90% → config.getDiskWarningUsagePercent()
- MAT 로그 5000자 → config.getMatLogMaxDisplayChars()
- Top Objects limit(10) → config.getTopObjectsMaxDisplay()

## [2026-03-29] application.properties에 Phase 3B 설정값 8개 명시 추가

[resources/application.properties]
- MAT CLI 실행 설정 섹션 추가: mat.timeout.minutes, sse.emitter.timeout.minutes,
  analysis.progress.log-update-lines
- 분석 결과 표시 설정 섹션 추가: dashboard.history.max-display,
  analysis.top-objects.max-display, analysis.mat-log.max-display-chars
- 디스크 모니터링 임계값 섹션 추가: disk.warning.usage-percent, disk.warning.free-space-mb

## [2026-03-29] 웹 Settings 변경 시 설정 영속화 (settings.json)

[service/HeapDumpAnalyzerService.java]
- 런타임 설정 영속화: {heapdump.directory}/data/settings.json에 저장/로드
- loadPersistedSettings(): @PostConstruct에서 settings.json 로드
  → application.properties 기본값을 settings.json 값으로 덮어씀
- persistSettings(): 설정 변경 시 즉시 settings.json에 기록
- setKeepUnreachableObjects() 변경 시 자동 영속화
- 기존 문제: 웹에서 Keep unreachable 변경 후 서버 재시작 시 유실 → 해결

설정 우선순위: settings.json (웹 변경) > application.properties (기본값)

## [2026-03-29] settings.json 방어 로직 강화

[service/HeapDumpAnalyzerService.java]
- loadPersistedSettings() 방어 시나리오 6가지:
  1. 파일 미존재 → 기본값으로 새로 생성
  2. 빈 파일 (0 bytes) → 기본값으로 재생성
  3. null/빈 맵 → 기본값으로 재생성
  4. 예상 외 타입 (String 등) → Boolean.parseBoolean 변환 + 경고 로그
  5. 정상 Boolean → 직접 캐스팅
  6. 깨진 JSON → .corrupted 백업 후 기본값으로 재생성
- persistSettings() 방어:
  - data 디렉토리 미존재 시 자동 생성 (mkdirs)

## [2026-03-30] Leak Suspects 지능형 분석 시스템 구현

[model/LeakSuspect.java]
- category, explanation, advice, severity 필드 추가
- @AllArgsConstructor 제거, 명시적 2인자 생성자 유지 (기존 호환)
- @JsonIgnoreProperties(ignoreUnknown = true) 추가 (기존 캐시 호환)

[util/LeakSuspectAdvisor.java] (신규 → 전면 재작성)
- MAT 텍스트에서 구조화된 정보를 추출하는 SuspectContext 파서:
  · 인스턴스 수, 클래스명(FQ/단순), ClassLoader, 메모리 크기/비율
  · 축적 대상 클래스(accumulator), 참조 출처(referenced from)
- 알려진 라이브러리 자동 감지 (KnownLibrary, 16개 규칙):
  · APM: New Relic, Dynatrace
  · WAS: JEUS, Apache Tomcat
  · ORM: Hibernate
  · Framework: Spring, Netty, Jackson, Elasticsearch
  · Connection Pool: HikariCP
  · Logging: Log4j, Logback
  · JDK: ZipFile, Reflection, Class 메타데이터
- 동적 설명 생성: 실제 클래스명, 인스턴스 수, 메모리 비율, 축적 대상을 설명에 삽입
- 심각도 자동 산정: critical(≥50%), high(≥25%), medium(≥10%), low(<10%)
- fallback: 기존 키워드 규칙 8개 + 동적 컨텍스트 보강 (enrichExplanation)

[parser/MatReportParser.java]
- parseSuspectsZip()에서 LeakSuspect 생성 후 LeakSuspectAdvisor.analyze() 호출
- primary/fallback 모두 advisor 연동

[templates/analyze.html]
- severity 기반 동적 색상: critical(암적색), high(빨강), medium(주황), low(녹색)
  · severity가 null이면 기존 position 기반 fallback (이전 캐시 호환)
- suspect-header에 카테고리 뱃지(파란색 라벨) 추가
- suspect-body에 분석 설명 박스(파란 배경) + 권장 조치 박스(녹색 배경) 추가
- CSS: .severity-critical, .suspect-category, .suspect-explanation, .suspect-advice 등

## [2026-03-30] KnownLibrary 규칙 대폭 확장 (17개 → 56개)

[util/LeakSuspectAdvisor.java]
- KnownLibrary 규칙 39개 추가 (총 56개):
  · APM: Datadog, Pinpoint, Micrometer
  · WAS: WebLogic, WebSphere, JBoss/WildFly, Undertow
  · 한국형 프레임워크: WebSquare(인스웨이브), Inswave, ProWorks, WMatrix
  · ORM: MyBatis, EclipseLink, jOOQ
  · DB 드라이버: MySQL, Oracle, PostgreSQL, Tibero(TmaxSoft)
  · 커넥션 풀: DBCP, Tomcat DBCP, C3P0
  · 캐시: Ehcache, Caffeine, Hazelcast
  · 메시징: Kafka, RabbitMQ, LMAX Disruptor
  · Redis/NoSQL: Jedis, Lettuce, MongoDB
  · HTTP 클라이언트: Apache HttpClient, OkHttp
  · DI/직렬화: Google Guice, Guava, Gson
  · 리액티브/gRPC: Project Reactor, gRPC
  · JDK: NIO Buffer

## [2026-03-30] 대기열 알림 지연 수정

[service/HeapDumpAnalyzerService.java]
- 대기열 진입 시 첫 QUEUED SSE 메시지를 즉시 전송하도록 수정
- 기존: tryAcquire(3초) 타임아웃 후에야 첫 QUEUED 알림 전송 → 최대 3초 지연
- 수정: 세마포어 획득 실패 직후 즉시 QUEUED 메시지 전송, 이후 3초 간격 갱신 유지

## [2026-03-30] 히스토리 삭제 시 힙덤프 파일 삭제 선택 옵션 추가

[templates/history.html]
- 삭제 모달에 "힙덤프 파일도 함께 삭제" 체크박스 추가
- 기본 동작: 분석 결과 데이터만 삭제 (힙덤프 파일 보존)
- 체크 시: 힙덤프 파일(.hprof, .gz, tmp)도 함께 삭제
- 힙덤프 파일이 이미 삭제된 경우 체크박스 자동 숨김

[controller/HeapDumpController.java]
- /history/delete 엔드포인트에 deleteHeapDump 파라미터 추가 (기본값 false)

[service/HeapDumpAnalyzerService.java]
- deleteHistory() 메서드에 deleteHeapDump 파라미터 추가
- deleteHeapDump=false면 힙덤프 파일 삭제 단계 스킵
- MAT 인덱스 파일 삭제 시 힙덤프 파일 확장자(.hprof, .bin, .dump, .gz) 제외 필터 추가
  (기존: baseName.* 패턴이 힙덤프 파일까지 매칭하여 의도치 않은 삭제 발생)

## [2026-03-30] 분석 스레드 풀 개선 — MAT 출력 리더 분리 + 설정 외부화

[config/HeapDumpConfig.java]
- 분석 스레드 풀 설정 3개 추가 (core-size, max-size, queue-capacity)
- @PostConstruct에서 설정값 검증 (core < 3 경고, max < core 보정)

[service/HeapDumpAnalyzerService.java]
- executor를 하드코딩(core=2,max=4,queue=8)에서 application.properties 기반으로 변경
- MAT CLI 출력 리더를 CompletableFuture.runAsync(executor)에서 전용 데몬 스레드로 분리
  → 근본 원인: MAT 리더가 executor core 스레드를 점유하여 대기 태스크가 실행 불가였음
- RejectedExecutionHandler에 스레드 풀 고갈 에러 로그 추가
  (active/poolSize/queueSize/completedTasks 출력 + CallerRuns 대체 실행)

[application.properties]
- analysis.thread-pool.core-size=3 (기본값, 최소 3 권장)
- analysis.thread-pool.max-size=5
- analysis.thread-pool.queue-capacity=12

## [2026-03-30] 대기열 취소 시 태스크가 큐에 남는 버그 수정

[service/HeapDumpAnalyzerService.java]
- sendProgress()에서 IOException 발생 시 스레드 인터럽트 누락 수정
- 기존: IllegalStateException만 인터럽트, IOException은 debug 로그만 남김
  → 클라이언트 disconnect 후에도 세마포어 대기 루프가 중단되지 않아 큐에 잔류
- 수정: 모든 SSE 전송 실패를 disconnect로 간주하고 즉시 인터럽트
  → 세마포어 대기 루프의 tryAcquire()가 InterruptedException 발생 → finally에서 queueSize 감소

## [2026-03-30] 대기열 취소 — 전용 Cancel API + 모달 UI 추가

[controller/HeapDumpController.java]
- POST /api/analyze/cancel/{filename} 엔드포인트 추가

[service/HeapDumpAnalyzerService.java]
- activeTasks(ConcurrentHashMap) 맵으로 분석 Future 추적
- cancelAnalysis(filename) 메서드: 맵에서 Future 조회 → cancel(true) 호출
- analyzeWithProgress: Future를 맵에 저장, finally에서 제거

[templates/progress.html]
- 대기열 상태에서 취소 시 모달("대기열에서 제거하고 취소하시겠습니까?") 표시
- 모달 확인 시 POST /api/analyze/cancel API 호출로 서버 측 즉시 취소
- RUNNING 상태에서는 기존 confirm 다이얼로그 유지
- _isQueued 플래그로 QUEUED/RUNNING 상태 추적
- 힙 경고 모달용 cancelAnalysis()를 cancelHeapWarnModal()로 분리
  (동일 함수명 중복 정의로 후자가 전자를 덮어써 모달/API 호출 불가했던 버그 수정)

## [2026-03-30] analyze.html Re-Analyze 버튼 크기 통일

[templates/analyze.html]
- 헤더 .btn-sm에 line-height, box-sizing, font-family 추가
- 본문 에러 영역 Re-Analyze <button>과 Back to Home <a> 버튼에도 동일 속성 적용
- 분석 실패 시 <form><button>과 <a> 태그 버튼 크기 통일

## [2026-03-30] 힙덤프 파일 보호 — 디렉토리 구조 개편 + 압축 검증 + 설정 UI

[디렉토리 구조]
- /opt/heapdumps/dumpfiles/ 신규: 원본 힙덤프 저장 (업로드, 감지 대상)
- data/, tmp/ 역할 기존 유지
- 기동 시 루트의 .hprof/.bin/.dump 파일 → dumpfiles/ 자동 마이그레이션

[service/HeapDumpAnalyzerService.java]
- 업로드: tmp/ → dumpfiles/로 변경
- 분석: dumpfiles/ → tmp/ copy → MAT CLI → tmp 삭제 (원본 안전)
- 디스크 여유 공간 체크: tmp copy 전 sourceSize*2 이상 확인
- compressDumpFile() .gz 검증 강화 (0바이트 방지, 검증 실패 시 원본 보존)
- compressAfterAnalysis 설정으로 압축 ON/OFF 제어 (settings.json 영속)
- listFiles()에서 cleanupDuplicateGzFiles 호출 제거 (레이스 컨디션 방지)
- deleteFile(), deleteHistory(), getFile() → dumpfiles/ 기준 + 레거시 fallback

[model/HeapDumpFile.java] compressed, originalSize, compressedSize 필드 추가
[model/HeapAnalysisResult.java] originalFileSize 필드 추가
[config/HeapDumpConfig.java] getDumpFilesDirectory(), compress-after-analysis 설정
[controller/HeapDumpController.java] POST /api/settings/compress API 추가
[templates/settings.html] "Compress after analysis" 토글 스위치 추가
[templates/index.html] 파일 목록에 압축 상태 (GZ 배지 + 원본/압축 크기) 표시
[application.properties] analysis.compress-after-analysis=true 추가

## [2026-03-30] MAT CLI 출력물(ZIP/인덱스) tmp 디렉토리 탐색 누락 수정

[service/HeapDumpAnalyzerService.java]
- moveZipsToResultDir(): MAT CLI가 tmp에서 실행되므로 ZIP이 tmp에 생성됨
  → tmp + 루트 디렉토리 모두 탐색하도록 변경
- moveArtifactsToResultDir(): 동일하게 tmp + 루트 탐색으로 변경
- 원인: 디렉토리 구조 개편으로 MAT CLI 실행 위치가 변경되었으나
  ZIP/인덱스 파일 수집 로직이 기존 루트만 탐색하여 "Heap data not available" 발생

## [2026-03-30] application.properties ↔ settings.json 동기화

[service/HeapDumpAnalyzerService.java]
- syncApplicationProperties() 추가: 런타임 설정 변경 시 application.properties도 동기화
- findExternalPropertiesFile() 추가: JAR 디렉토리 → 소스 디렉토리 순으로 탐색
- 줄 단위 치환으로 주석/포맷 보존하며 해당 키 값만 교체
- persistSettings(), loadPersistedSettings() 양쪽에서 호출
- 동기화 대상: mat.keep.unreachable.objects, analysis.compress-after-analysis

## [2026-03-31] Analysis Files 페이지 — 인디케이터 범례 및 압축 용량 표시

[templates/files.html]
- 상단에 인디케이터 색상별 범례 추가 (녹색=분석 완료, 노란색=미분석, 빨간색=분석 오류)
- 압축된 파일(GZ)의 경우 원본 크기 → 압축 크기 형태로 표시 + GZ 배지
- 비압축 파일은 기존 단일 크기 표시 유지

[controller/HeapDumpController.java]
- AnalysisHistoryItem에 compressed, formattedOriginalSize, formattedCompressedSize 필드 추가
- buildHistory()에서 HeapDumpFile의 압축 정보를 AnalysisHistoryItem에 매핑

## [2026-03-31] 업로드 기능 — .gz 파일 업로드 지원 추가

[templates/index.html]
- accept 속성에 .gz 확장자 추가
- 안내 텍스트에 (.gz) 표기 추가
- validateFileExt() 함수: .hprof.gz, .bin.gz, .dump.gz 확장자 허용
- showExtWarning() 모달: Supported Formats에 .gz 배지 추가

[static/js/main.js]
- 파일 선택(change) 핸들러: .gz 복합 확장자 검증으로 변경
- 드래그앤드롭(drop) 핸들러: validExts에 .gz 확장자 추가
- 폼 제출(submit) 핸들러: .gz 복합 확장자 검증으로 변경

[service/HeapDumpAnalyzerService.java]
- 업로드 거부 시 에러 메시지에 .gz 포함

※ 백엔드(isValidHeapDumpFile, FilenameValidator)는 이미 .gz를 허용하고 있었음
  → 프론트엔드만 차단하고 있어 프론트엔드 검증 로직만 수정

## [2026-03-31] 파일 용량 표시 — 압축 파일 원본 크기 기준으로 개선

[service/HeapDumpAnalyzerService.java]
- listFiles(): 압축 파일의 originalSize 필드 설정 누락 수정
  (캐시에 원본 크기 있으면 originalSize + size 모두 원본 값으로 설정)

[controller/HeapDumpController.java]
- filesPage(): totalSize=원본 크기(size) 합산, diskSize=압축 파일은 compressedSize 사용
- index(): totalSize를 원본 크기 기준으로 계산 (size가 이미 원본 크기)
- /api/settings: totalSize 원본 기준 + diskSize 추가 응답

[templates/files.html]
- 상단 통계: "X.XX GB original" + 압축 파일 존재 시 "X.XX GB disk" 파란색으로 추가 표시

## [2026-03-31] 분석 중(progress) 페이지 배경색 통일

[templates/progress.html]
- body 배경색을 메인 페이지와 동일한 #F3F4F6으로 변경 (기존: style.css의 #fff)

## [2026-03-31] Re-analyze 시 덤프 파일 없으면 기존 분석 데이터 보존

[controller/HeapDumpController.java]
- rerunAnalysis(): 캐시 삭제 전 덤프 파일 존재 여부 확인 (원본 + .gz + 레거시 경로)
- 파일 없으면 clearCache() 호출하지 않고 기존 결과 페이지로 리다이렉트 + 경고 메시지

[templates/analyze.html]
- header 아래에 rerunError 배너 추가 (노란색 경고, ERROR/SUCCESS 양쪽 결과 페이지 대응)

## [2026-04-07] RAW DATA 영역에 MAT Main Overview + Dominator Tree 페이지 추가

[parser/MatReportParser.java]
- findReportZip(): 외부에서 ZIP 파일을 찾을 수 있도록 public 래퍼 메서드 추가

[service/HeapDumpAnalyzerService.java]
- findReportZip(): filename + reportType으로 MAT 리포트 ZIP 파일 조회
- hasReportZip(): ZIP 파일 존재 여부 확인

[controller/HeapDumpController.java]
- GET /report/{filename}/mat-page/{reportType}/** 엔드포인트 추가
  - MAT 리포트 ZIP 내 파일을 직접 서빙 (HTML, CSS, JS, 이미지)
  - 파일 확장자 기반 Content-Type 자동 설정
  - mat:// 프로토콜 링크 비활성화 처리
  - path traversal 방지 + reportType 화이트리스트 보안 적용
  - 브라우저 캐싱 (Cache-Control: max-age=3600)
- analyzeResult(): hasOverviewZip 모델 속성 추가

[templates/analyze.html]
- Raw Data 사이드바에 "Main Overview" 버튼 추가 (overview ZIP 존재 시 표시)
- iframe 기반 MAT Main Overview 패널 추가
  - 내부 페이지 링크 탐색 가능 (System Properties, Thread Overview, Top Consumers 등)
  - MAT code.js (접기/펼치기) 기능 동작
  - lazy-load + 자동 높이 조절
- iframe 기반 Dominator Tree 패널 추가 (Top Components ZIP)
  - 클래스 로더별 도미네이터 분석, Retained Set, Top Consumers 등 서브 페이지 탐색 가능
  - 차트 이미지, 접기/펼치기 기능 동작
- iframe lazy-load 로직을 범용 맵 기반으로 리팩터링

## [2026-04-07] Top Consumers 컴포넌트 상세 — 정제된 분석 뷰 추가

[model/ComponentDetailParsed.java] (신규)
- 컴포넌트 상세 파싱 결과 최상위 모델 (className, metadata, sections, parsedSuccessfully)

[model/ComponentMetadata.java] (신규)
- 컴포넌트 메타데이터: Size, Classes, Objects, Class Loader

[model/ComponentSection.java] (신규)
- 섹션 모델: TABLE/TREE/TEXT 타입, 심각도(severity), 테이블/텍스트 콘텐츠

[model/TableData.java] (신규)
- 테이블 데이터: headers, rows, rightAligned 정렬 힌트

[model/TreeNode.java] (신규)
- 트리 노드: label, objectRef, depth, children, columns

[model/HeapAnalysisResult.java]
- componentDetailParsedMap 필드 추가 (Map<String, ComponentDetailParsed>)

[model/MatParseResult.java]
- componentDetailParsedMap 필드 추가

[parser/MatReportParser.java]
- parseComponentDetail(): sanitize된 HTML → 구조화된 ComponentDetailParsed 변환
  - 메타데이터(Size/Classes/Objects/ClassLoader) 추출
  - h2~h5 섹션 헤더 + exp### 콘텐츠 쌍으로 섹션 분할
  - <table class="result"> 감지 → TABLE 타입 파싱
  - 리프 텍스트 섹션 → TEXT 타입 + 목록 항목 추출
  - 컨테이너 섹션(하위 섹션 있는) 중복 방지
  - 심각도: 제목 키워드 기반 추론 (memory waste, duplicate, collision 등)
- buildSectionTree(): heading level 기반 flat → 부모-자식 트리 변환
  - h3(Possible Memory Waste) → h4(Duplicate Strings, Empty Collections...) 계층 자동 구성
  - 빈 섹션(텍스트/테이블/children 없음) 자동 제거
- extractComponentDetailPages(): raw HTML 저장 시 동시에 구조화 파싱 수행
- parseTableHtml(), extractTextDescription(), extractListItems(), parseCountString() 헬퍼

[service/HeapDumpAnalyzerService.java]
- getParser() public 접근자 추가
- buildResult(): componentDetailParsedMap 결과 복사
- cloneWithoutLog(): componentDetailParsedMap 복사
- sanitizeCachedHtml(): 기존 캐시에 parsedMap 없으면 lazy 파싱 수행 (하위 호환)
- reparsComponentDetails(): parsedMap도 함께 복원

[controller/HeapDumpController.java]
- GET /report/{fn}/component-detail-parsed: JSON API 엔드포인트 추가
  - parsedMap → raw HTML 즉석 파싱 → 404 순서로 조회

[templates/analyze.html]
- CSS: .cd-tabs, .cd-meta, .cd-section, .cd-table, .cd-badge 등 정제된 뷰 스타일
- 모달: 탭 바 (Analysis / Raw Data) + 두 뷰 컨테이너 구조
- JS: showComponentDetail() → parsed JSON 우선 fetch, 실패 시 raw HTML 폴백
  - renderParsedDetail(): 메타데이터 카드 + 섹션별 렌더링
  - renderCdTable(): 정렬 힌트 포함 테이블, 100행 초과 시 truncate 안내
  - renderCdSection(): 접히는 섹션 + 하위 섹션 재귀 렌더링, 심각도 배지, children 개수 표시
  - switchCdTab(): Raw Data lazy 로드, 탭 전환
  - toggleCdSection(): 섹션 토글 애니메이션

## [2026-04-07] RAW DATA 패널 iframe 전환 (내부 링크 네비게이션 활성화)

[controller/HeapDumpController.java]
- hasSuspectsZip model attribute 추가

[templates/analyze.html]
- RAW DATA 3개 패널(System Overview, Top Components, Suspects)을 mat-frame → iframe 전환
  - 기존: HtmlSanitizer가 ZIP 내부 상대 경로 링크를 javascript:void(0)으로 변환하여 클릭 불가
  - 변경: mat-page API를 통한 iframe으로 전환하여 ZIP 내부 페이지 네비게이션 활성화
  - mat:// 프로토콜 링크는 MAT 데스크톱 전용이므로 비활성 유지
- 기존 Main Overview iframe + System Overview mat-frame → System Overview iframe 1개로 통합
- 기존 Top Components mat-frame + Dominator Tree iframe → Top Components iframe 1개로 통합
- Suspects mat-frame → Suspects iframe 전환
- 사이드바 메뉴 정리: Main Overview, Dominator Tree 항목 제거 (중복)
- iframe lazy-load 맵 업데이트

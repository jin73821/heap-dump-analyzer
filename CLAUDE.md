# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Always respond in Korean (한국어). Code and technical identifiers remain in English.

## Project Overview

Java Spring Boot **3.5.14** + Java **17** (런타임 OpenJDK 21) 웹앱. Eclipse MAT CLI로 .hprof/.bin/.dump 분석. MariaDB(`192.168.56.9:3306/HEAPDB`) + Spring Security **6.5** 세션 기반. Hibernate **6.6** + jakarta 네임스페이스 (jakarta.persistence/servlet/annotation/transaction). 2026-05-19 Boot 2.7→3.5 마이그레이션 완료 — 상세는 `BOOT3_MIGRATION_PLAN.md` 참조.

## Build & Run

```bash
mvn clean package -DskipTests           # 빌드 (10~13초). test 디렉토리 비어있음
java -jar target/heap-analyzer-2.0.6.jar
bash restart.sh                          # 운영(18080) 재기동
```

**Maven 요구사항:** Maven 3.6.3+ (Boot 3.5 의 maven-clean-plugin 3.4.1 요구). 본 시스템 설정: Maven 3.9.9 (`/opt/apache-maven-3.9.9`, alternatives 수동 모드 + `/etc/profile.d/maven.sh` 로 `MAVEN_HOME` 설정). 신규 운영 환경 배포 시 동일 버전 설치 필수.

**CRITICAL:** 모든 프론트엔드 리소스(CSS/HTML/JS)는 JAR 내부에 있음. **어떤 변경이든 `mvn clean package -DskipTests && bash restart.sh` 필수.** 빌드+기동 약 20~24초.

**기동 검증:**
```bash
sleep 18 && grep -E "Started HeapAnalyzerApplication|FAILED|Exception in thread" \
  /opt/genspark/webapp_dump/logs/heapdump-analyzer.log | tail -3
```

**MariaDB 미연결 시 기동 실패** (Spring Session JDBC가 SPRING_SESSION 자동 생성). DB 점검: `mysql -h 192.168.56.9 -u heap_user -pshinhan@10 HEAPDB -e "..."`.

## Architecture

Spring MVC + JPA + MariaDB. **하이브리드 저장**: 메타데이터는 DB(`analysis_history`, `ai_insights`, `ai_chat_*`, `login_history` 등), 분석 상세(HTML/ZIP/result.json)는 파일 시스템.

**디렉토리:**
```
/opt/heapdumps/
├── dumpfiles/    원본 보존 (.hprof/.gz). 업로드 도착지
├── data/         결과(result.json/mat.log/*.zip/*.threads) + settings.json
└── tmp/          분석 중 복사본 (분석 후 항상 삭제)
```

**분석 흐름:** Upload → dumpfiles → tmp/ copy → MAT CLI(tmp 대상) → 성공 시 tmp 삭제 + data/{baseName}/ 저장 + (옵션)dumpfiles gzip / 실패 시 tmp만 삭제.

**Controllers (Phase 4B-2 — 도메인별 분할, 2026-05-17):**
- `HeapDumpViewController` — Thymeleaf 페이지 + form POST → redirect 액션 (대시보드/files/history/settings/compare/upload/delete/rerun 등)
- `HeapAnalysisApiController` — SSE 진행 스트림 (`/analyze/progress/*`) + 취소 + 큐 상태
- `HeapReportApiController` — MAT 리포트 HTML/iframe (`/report/{filename}/*`) + PDF + log 청크 + thread stacks
- `HeapFileApiController` — `/api/upload`, `/api/upload/check`, `/download/*`, `/api/files/bulk-delete`
- `HeapHistoryApiController` — `/api/history*`, `/api/history/detections*`, `/api/results/clear`, `/api/compare/data`
- `HeapSystemApiController` — `/api/settings/*`, `/api/mat/*`, `/api/system/status`, `/api/disk/check`, `/api/settings` (전체 설정 조회)
- `HeapAiApiController` — `/api/llm/*` (15 endpoints: enabled/config/apikey/test/analyze/insight/compare/chat/chat-stream/chat-prompt/chat-restore-mode) + `/api/settings/rag*` (7 endpoints)
- `AuthController`(`/login`), `AdminController`(`/admin/users` 4-탭: 사용자/계정신청/접속이력/현재접속), `ServerController`(`/servers`, `/servers/{id}`, `/servers/logs`), `AiChatController`(`/ai-chat` 세션 기반), `LeakRuleAdminController`(`/admin/leak-rules` ADMIN CRUD), `ComparisonHistoryController`(`/comparison-history`)
- `GlobalExceptionHandler` — `IllegalArgumentException` → JSON 400 (`/api/`/AJAX) or HTML 302 redirect (`?error=invalidFilename`)

**DTOs (`model/dto/` 패키지, Phase 4B-2):** `AnalysisHistoryItem` / `DailyDetection` / `ServerSeries` / `DetectionSummaryItem` / `DetectionAggregate` / `DetectionDayFile` / `DetectionRecentItem` / `ClassDiff` / `HistogramDiff` / `SuspectDiff` / `KpiDiff` — 11 DTO. 이전엔 `HeapDumpController` inner static class 였음.

**Services (Phase 4A 종합 추출, 2026-05-12):**
- `HeapDumpAnalyzerService` (1,965 라인) — 분석 + LLM facade + **모든 런타임 설정 영속화 단일 책임**: settings.json ↔ application.properties 동기화
- `LlmConfigService` (1,025 라인) — LLM 12 필드 + 17 getter/setter + 4 호출 메서드 (`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`/`testLlmConnection`) + `disableSslVerification` + `GENSPARK_MODELS`
- `RagConfigService` (421 라인) — RAG 26 필드 + 32 getter + 5 그룹 setter + AES 암호화
- `FileManagementService` — 업로드/중복검사/디스크 I/O/gzip
- `HeapHistoryAggregator` (585 라인, Phase 4B-2) — `buildHistory` / `aggregateDetections` / `build*Diffs` / `buildKpiDiff` / `buildAnalysisName` / `truncateLog` / `formatDuration` 등 공유 헬퍼. View/API 컨트롤러 7개에 주입.
- `HeapAnalysisResultCache` — `ConcurrentHashMap` 캐시 + 7 facade 메서드
- `AiInsightManager` — `saveAiInsight`/`loadAiInsight`/`deleteAiInsight`/`migrateAiInsightsToDb`
- `RagService`(ES 검색 + 청킹 + LLM 컨텍스트 주입), `EmbeddingService`(semantic-client 전용), `RemoteDumpService`(SSH/SCP), `UserService`/`CustomUserDetailsService`, `LeakRuleService`, `ComparisonHistoryService`

**Utils:**
- `AuthUtil.isAdmin(Authentication)` — static, 모든 컨트롤러 공용
- `FilenameValidator.validate(name)` — null/empty/../slash/null byte 차단 + 확장자 whitelist
- `FormatUtils.formatBytes(long)` — 통일 포맷 (controller/service/model 모두 위임)
- `HtmlSanitizer` — OWASP Java HTML Sanitizer wrapper
- `AesEncryptor` — AES-256-CBC HEX

**Listener:** `AuthEventListener` — 로그인 이력 기록 (아래 함정 참조).

**Parser:** `MatReportParser` — Overview/Top Components/Suspects ZIP 다단계 추출. `sanitizeHtml()`/`extractBodyContent()` 두 곳에 존재 (parser, service) — 동기화 유지 필수.

**External:** `/opt/mat/ParseHeapDump.sh` (suspects/overview/top_components 리포트, 30분 타임아웃).

## Frontend

Thymeleaf + vanilla JS + Chart.js. 빌드 도구 없음.

**공통 인프라 (Phase 5A/5B/5C — common.* 통합):**
- `/css/common.css` — reset / body base / `.topbar*` / `.modal-ov` + `@keyframes modalIn` + `.modal-box` base / btn 색상 utility 3 그룹 (cancel `.mbtn-cancel/.btn-cancel/.sa-btn-cancel` · danger `.mbtn-del/.mbtn-danger/.btn-delete/.sa-btn-del` · primary `.mbtn-save/.mbtn-primary/.mbtn-confirm/.btn-download`) / 데이터 테이블 4 family base (`.htable/.ftable/.stable/.utable` + sortable) — 80+ 라인 공통. 페이지별 변형(opacity 변형, padding/font-size, min-width 등)은 인라인 cascade override.
- `/js/common.js` — `window.Common` 네임스페이스: `escHtml(s)` (5문자 escape) / `csrfToken()` / `csrfHeaderName()` / `fetchJSON(url, opts)` (자동 CSRF/Content-Type, non-2xx throw, JSON 자동 파싱) / `appendCsrfToForm(form)` / `formatBytes(bytes)`. `banner.html`에서 1회 로드 → 14 페이지 자동 가용.
- 캐시 무효화: `?v=YYYY-MM-DD[a-z]` 쿼리 파라미터. 모든 페이지 일괄 갱신.

**페이지:** `/`(Dashboard, 멀티 업로드 큐), `/files`, `/history`, `/compare`(파라미터 없으면 picker), `/analyze/{filename}`(KPI/TopConsumers/Suspects/Histogram/Threads/AI/RawData + 플로팅 채팅 FAB), `/progress/{filename}`(SSE), `/settings`(General), `/settings/llm`, `/settings/rag`, `/ai-chat`(세션 사이드바), `/servers*`, `/admin/users`(ADMIN), `/admin/leak-rules`(ADMIN), `/comparison-history`, `/login`.

**analyze.html JS 외부화 (Phase 5C, 2026-05-17):** 인라인 3,125 라인을 `/js/analyze.js` (149KB)로 추출. analyze.html은 1,057 라인 (-75%). 인라인 잔존:
- 에러 페이지 전용 `<script th:if="${error}" th:inline="javascript">` (`[[${filename}]]` 사용)
- Thymeleaf 모델 변수 노출 `<script th:inline="javascript">` (`USED_BYTES`/`FILENAME`/`OBJ_NAMES`/`THREAD_STACKS` 등 13 vars)
- external analyze.js는 위 변수들을 글로벌로 참조 — script 순서(인라인 → external) 유지 필수.

**Global Banner** (`fragments/banner.html`) — 모든 페이지에 `th:replace`로 삽입되는 좌측 고정 배너. 220px ↔ 44px 토글, `localStorage('bannerCollapsed')`. CSS 변수 `--banner-w`가 모든 페이지의 topbar `left`/container `padding-left` 제어. 모바일(≤900px) 숨김. Navigation: Dashboard / Files / History / Comparison / AI Chat / Servers(아코디언) / Settings(아코디언: General/LLM/RAG/Accounts(ADMIN)/Leak Rules(ADMIN)) / Logout. System Status는 `/api/system/status` 60초 폴링 + `localStorage` 캐시. **Banner sub-menu**: `.gb-nav-sub.open { max-height: 200px }` 공용 트랜지션. **FOUC 방지**: `<style>` 앞 인라인 스크립트가 collapsed 클래스 즉시 적용.

**Files/History 공통 데이터 그리드 패턴:** 검색 + 행표시 셀렉트(20/30/50/100, localStorage) + admin "deleted 표시" 체크박스 + 다중선택 + 헤더 클릭 정렬(`data-sort-key`/`data-sort-type=num|str` ▲▼ 인디케이터, 한글 `localeCompare(s, 'ko')`) + 페이지네이션 (‹Prev / 1 … 현재±2 … 마지막 / Next›). raw 정렬값은 `<tr data-sort-*>` 직렬화 — `AnalysisHistoryItem`의 raw 바이트 필드(`sizeBytes`/`heapUsedBytes` 등) 사용. **다중 삭제 API 분리**: `/api/history/bulk-delete`(분석 기록 + 옵션 heap dump) vs `/api/files/bulk-delete`(heap dump만, 분석 기록 보존).

**server-logs.html은 의도적으로 서버 사이드 페이지네이션** (transfer log는 누적형 무제한 성장). `Page<TransferLogItem>` + KPI 별도 endpoint + Export 50,000 cap. JPA `Specification` 동적 쿼리. 정렬 필드 화이트리스트. `.ltable` 은 hover `#FAFAFA` + th 직접 background 구조라 common.css 4 family base 에서 제외 (인라인 유지).

**모바일 탭 (배너):** Navigation/Analysis/Chat/Upload — 페이지가 `body.has-{name}-tab` 토글. `registerBannerUploadTab/AnalysisTab/ChatTab(elem)` 헬퍼로 등록. Chat 탭은 `.session-sidebar`를 `cloneNode`로 복제 — **클론 내부 ID 충돌 방지를 위해 ID 제거** → 코드는 `getElementById` 금지, `querySelectorAll`로 원본+클론 동시 갱신.

**Topbar 줄바꿈 방지 규약:** 페이지 topbar 가 `position: fixed` + `display: flex; justify-content: space-between` 구조에서 햄버거 + 타이틀이 우측 버튼 폭 압력으로 줄바꿈되는 사례 다수. `.topbar-brand` 에 명시적 `display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1 1 auto` + `.topbar-title` 에 `white-space: nowrap; overflow: hidden; text-overflow: ellipsis` + `.topbar-right { flex-shrink: 0 }` 패턴 필수. 새 페이지 작성 시 `files.html` / `history.html` 패턴 그대로 복사.

## LLM / AI

**Multi-provider:** Claude (Messages API) / GPT (Chat Completions) / Genspark / Custom (OpenAI 호환). Provider별 헤더/본문 분기. 모든 설정은 `volatile` 필드 + settings.json 영속화. 구현은 `LlmConfigService`.

**3가지 호출 방식:** `callLlmAnalysis(prompt)` 원샷 JSON / `callLlmChat(messages, systemPrompt)` 멀티턴 / `callLlmChatStream(...)` SSE (Claude `content_block_delta`, OpenAI `choices.delta.content` 파싱).

**채팅 흐름 (`analyze.html` 플로팅):** `ensureChatSession()` → `POST /api/ai-chat/sessions` → `doStreamRequest()` → `POST /api/ai-chat/sessions/{id}/stream`. user 메시지는 스트리밍 시작 전 동기 저장, assistant는 `onDone`에서 3회 재시도(500ms) 저장. `done` SSE에 `saved` 필드.

**SSL 검증 토글 (`llm.ssl.verify`):** 기본 true. 사내 사설 CA 로 발급된 TLS 게이트웨이 호출 시 JVM 번들 cacerts 신뢰 못해 PKIX 에러 가능. 두 해결 경로 모두 지원:
- 운영: `restart.sh` 가 `/opt/genspark/webapp_dump/certs/heap-truststore.jks` 존재 시 `-Djavax.net.ssl.trustStore*` 3종 자동 부착. 미존재 시 JDK 기본 cacerts 사용.
- 우회: `/settings/llm` 토글 OFF → 4개 LLM 메서드(`testLlmConnection`/`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`) 가 `HttpsURLConnection` 캐스트 후 `disableSslVerification` 분기 진입.

RAG `ragSslVerify` 와 **1:1 미러링 패턴** (`volatile` 필드 / 생성자 init / settings.json 로드·저장 / `syncApplicationProperties()` 키 / getter·setter / `openConnection()` 직후 분기). 새 외부 HTTP 호출 추가 시 동일 패턴 따를 것.

## RAG (Elasticsearch)

**3개 검색 모드 모두 활성** (Phase 2 완료, `RAG_PHASE2_PLAN.md` 참조):
- `keyword` — BM25 `match`
- `semantic-server` — `text_expansion`(ELSER) 또는 `semantic`(semantic_text)
- `semantic-client` — 앱이 임베딩 호출 후 `knn` (provider: openai/cohere/custom)

semantic 설정 누락 시 keyword 폴백 없이 명확한 에러 (디버깅 용이).

**LLM 통합:** `RagService.fetchContextForLlm(query)`가 3곳에 주입 — `AiChatController.streamChat()`, `HeapAiApiController.aiChat()`, `HeapAiApiController.aiChatStream()`. systemPrompt 끝에 `[참고 자료 (RAG)]\n--- 자료 N ---\n...` 추가. 비활성/실패 시 빈 문자열 (호출자 안전).

**청킹 (post-retrieval):** `chunkText(text, strategy, size, overlap, maxChunks)` — fixed/paragraph/sentence. `maxTotalChars` 한도 내 주입. 첫 청크가 한도 초과해도 잘라서라도 일부 주입.

**인증:** none / Basic / ApiKey. password/apiKey는 `AesEncryptor.encrypt()`로 `ENC(...)` 암호화 후 settings.json + application.properties 저장. **POST `/api/settings/rag`에서 password/apiKey가 키 없거나 null이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 갱신** — UI는 마스킹 placeholder만 노출.

## Authentication & Security

Spring Security **6.5** 세션. `/login` 공개, `/admin/**` + `/api/admin/**` ADMIN 전용. **CSRF 보호 유지 (면제하지 않음)**: `/api/admin/**`, `/api/settings/**`, `/api/llm/{enabled,config,apikey,test-connection,chat-prompt,chat-restore-mode}`, `/api/servers/{scan-interval,ssh-local-user}` — 모두 `authorizeHttpRequests(auth -> auth.requestMatchers(...).hasRole("ADMIN"))` 매처와 1:1 미러링. 그 외 `/api/**` 는 CSRF 면제 (인증은 유지). **새 ADMIN mutation 추가 시 SecurityConfig 두 곳 (authorize + csrf ignore) 동시 갱신 필수**. `SecurityConfig` 는 lambda DSL + `@EnableMethodSecurity` (`@PreAuthorize` 지원) 사용 — `.and()` chain / `@EnableGlobalMethodSecurity` / `antMatchers` 미사용.

**Spring Session JDBC**: `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` 자동 생성. 무동작 만료 60분, cleanup cron 10분. 앱 재시작에도 로그인 유지.

**CSRF 동적 폼 (Common.* 사용 — 2026-05-17):**
```javascript
Common.appendCsrfToForm(f);  // 3 라인 직접 createElement 대신 1 라인
```
**fetch 호출 (안전 패턴):**
```javascript
Common.fetchJSON(url, { method: 'POST', body: JSON.stringify(...) })
  .then(function(d) { ... })  // CSRF/Content-Type 자동, non-2xx throw, JSON 파싱
  .catch(function(e) { ... });
```
페이지 `<head>`에 `<meta name="_csrf" th:content="${_csrf.token}">` + `<meta name="_csrf_header" th:content="${_csrf.headerName}">` 필요.

**기본 계정:** admin / shinhan@10 (BCrypt, `UserService.initDefaultAdmin()`).

**계정별 격리:** AI 채팅 세션은 `Principal.getName()`으로 username 일치 검증.

## ⚠️ 중요한 함정 (Pitfalls)

1. **`InteractiveAuthenticationSuccessEvent` 사용 필수** — `AuthenticationSuccessEvent`는 `ProviderManager`에서 발행되는데 그 시점은 **세션 고정 보호 적용 전**이라 `request.getSession().getId()`가 곧 폐기될 옛 세션 ID. SPRING_SESSION의 ID와 매칭 안 됨. `InteractiveAuthenticationSuccessEvent`는 `AbstractAuthenticationProcessingFilter#successfulAuthentication()`에서 sessionStrategy 적용 *후* 발행. 로그인 이력의 session_id를 활성 세션 매칭에 쓰는 코드는 모두 이 이벤트 사용.

2. **활성 세션 본인 종료 거부 (서버 측)** — `DELETE /api/admin/active-sessions/{id}`는 `request.getSession(false).getId()`와 path variable 비교 → 일치 시 400 + 한글 메시지. 클라이언트 `isCurrent` 플래그는 UI 힌트일 뿐, 실제 거부는 백엔드. 종료는 `FindByIndexNameSessionRepository.deleteById()` 우선 (속성 테이블 정합성), 폴백은 `SPRING_SESSION_ATTRIBUTES` → `SPRING_SESSION` 직접 DELETE.

3. **로그인 페이지 새로고침 메시지 잔존** — Spring Security가 `/login?error=true`로 리다이렉트 → F5 시 같은 메시지 재표시. `login.html` 최상단 인라인 스크립트가 `?error`/`?logout` 발견 시 즉시 `history.replaceState({}, document.title, location.pathname)`.

4. **Thymeleaf `th:onclick` 문자열 변수 차단** — restricted expression policy. `th:data-*` + 일반 `onclick="fn(this.dataset.x)"` 패턴 사용.

5. **MAT HTML sanitization 동기화** — `sanitizeHtml()`(parser)와 `extractBodyContent()`(service) 두 곳 존재. 양쪽 모두 `<body>` 추출 + script/link/이벤트 핸들러 제거 + href→`javascript:void(0)`. **변경 시 동시 수정**.

6. **Raw Data iframe** — `SecurityConfig`에서 `X-Frame-Options: SAMEORIGIN` 필수. iframe `sandbox` 속성 미사용 (allow-scripts + allow-same-origin은 sandbox 무력화 경고). lazy-load 조건은 `!iframe.getAttribute('src')` (`!iframe.src`는 브라우저별 `"about:blank"` 반환).

7. **`crypto.subtle` 폴백** — HTTPS/localhost에서만 사용 가능. HTTP 환경은 `simpleHash()` (FNV-1a) 자동 폴백. 모든 경로 try-catch로 Promise resolve/reject 보장.

8. **배너 사이드바 DOM 복제** — `analyze.html`/`ai-chat.html` 사이드바는 `cloneNode(true)`로 배너 탭에 복제. `getElementById`로는 원본만 접근 → 양쪽 갱신 필요한 요소는 반드시 `querySelectorAll('.class-name')`.

9. **MAT CLI 출력 reader 전용 daemon thread** — 분석 executor 사용 시 thread pool 고갈. 별도 daemon thread 사용.

10. **`@JsonIgnore` on `threadStacksText`** — result.json에 비저장(크기 큼). `.threads` 파일에서 lazy-load.

11. **SpEL `Math.min/max(int, long)` ambiguity** — Thymeleaf `${T(java.lang.Math).min(80, longExpr)}` 는 `EL1033E ambiguous` 로 실패 (int↔long widening/narrowing 양방향 매치). **정수 리터럴에 `L` 접미사 필수** (`80L`, `1048576L`) → `Math.min(long, long)` 단일 매칭. 응답 헤더가 이미 chunked 로 전송 중이면 종료 마커 미전송 → 브라우저 `ERR_INCOMPLETE_CHUNKED_ENCODING` 로 빈 페이지.

12. **필터 dropdown / picker 에서 `fileDeleted` 제외** — admin 사용자 `/api/history` 에는 dumpfiles/ 에 실제 파일이 없는 ghost 기록도 포함. UI 가 "deleted 표시" OFF 기본이라 행은 안 보이는데 dropdown 에는 잡히는 모순 발생. `/compare` picker JS, `/files` 서버 filter distinct 추출 모두 **`.filter(h -> !h.isFileDeleted())` 필수**. 새 dropdown/picker 추가 시 동일 조건 적용.

13. **CSS cascade override 패턴 (common.css ↔ 페이지 인라인)** — `common.css` 가 base 정의, 페이지 인라인 `<style>` 이 변형 override 하는 구조. 새 페이지에서 `.modal-ov`/`.modal-box`/`.htable`/`.mbtn-cancel` 등을 사용할 때 base 속성은 재정의 하지 말고 (중복) 페이지 고유 변형만 인라인 작성. common.css 변경 시 `?v=` 캐시 키 무효화 필수.

14. **`Common.fetchJSON` 시맨틱** — non-2xx 응답을 throw 한다. 페이지 코드가 에러 응답의 JSON body 를 검사해야 하는 경우 (`r.json().then(d => if d.success else show d.error)`) 마이그레이션 금지 — `.catch(e => ...)` 에서 `e.body` 는 raw 텍스트라 `JSON.parse` 추가 필요. 메시지 포맷도 `HTTP {status}: {body}` 라 커스텀 한글 메시지 보존이 필요한 곳은 원래 fetch 유지.

15. **Spring Session JDBC 3.x ↔ 2.x 직렬화 비호환** — Spring Session 3.x (Boot 3) 는 2.x (Boot 2.7) 가 저장한 SPRING_SESSION_ATTRIBUTES 행을 deserialize 못 함 (`ConversionFailedException: byte[] → Object`). 응답 자체는 200 이지만 백그라운드 session save 가 실패해 로그 오염. **Boot 버전 다운/업그레이드 시 `TRUNCATE SPRING_SESSION_ATTRIBUTES; TRUNCATE SPRING_SESSION;` 필수** — 모든 사용자 재로그인 발생.

16. **Hibernate 6 의 `@Lob String` default 변경** — Hibernate 5 는 `@Lob String` → `longtext` (MariaDB), Hibernate 6 는 동일 매핑을 **`tinytext(255)` 로 default 축소**. 기존 `longtext` 컬럼에 대해 `ALTER TABLE ... MODIFY ... tinytext` 시도 → 255 byte 초과 데이터 있으면 실패 WARN. **해결**: `@Lob` 제거 + `@Column(columnDefinition = "TEXT")` 명시. `leak_library_rule` / `leak_fallback_rule` 의 advice_tpl / explanation_tpl / pattern_regex 5 컬럼이 이미 적용됨. 신규 String 컬럼 추가 시 size 가 255 초과 가능하면 `columnDefinition` 또는 `length` 명시.

## Key Design Decisions

- **Two-tier cache:** In-memory `ConcurrentHashMap` ← disk `result.json` 복원. 누락 필드(componentDetailHtmlMap/histogramHtml/threadOverviewHtml)는 ZIP에서 lazy 재추출.
- **Serial analysis with queue:** `Semaphore(1)`. `analysis.thread-pool.*` 설정 가능. `AtomicInteger queueSize` + `volatile currentAnalysisFilename`. `GET /api/queue/status` 노출.
- **Cancellation:** `POST /api/analyze/cancel/{filename}` + `activeTasks` (`ConcurrentHashMap<String, Future<?>>`). SSE disconnect도 `task.cancel(true)`.
- **Component detail keying:** `className#index` (같은 클래스 다중 인스턴스 처리, 예: 여러 `ParallelWebappClassLoader`).
- **Thread stack matching:** `.threads` 파일을 `Thread 0x...` 블록으로 split → Thread Overview HTML의 hex address로 매칭.
- **Thread detail DOM 최적화:** 단일 공유 `<tr>`을 클릭 시 이동. 스택은 JS array (`THREAD_STACKS`)에 보관, DOM 사전 렌더 X (60+ 스레드 성능).
- **MAT CLI 검증:** `HeapDumpConfig.init()` 5단계 (exists/isFile/readable/executable/non-empty). `extractMatErrorHint()`가 OOM/SnapshotException/permission/disk full 패턴 한국어 안내.
- **Settings 영속화:** 토글/설정 → settings.json + `syncApplicationProperties()` 라인 단위 치환 (주석 보존). `findExternalPropertiesFile()`이 JAR/소스 디렉토리 탐색.
- **Settings 확인 모달:** 파괴적 변경(disable compress/save results/keep unreachable, enable auto-analyze)은 토글 revert → 모달 → 확인 시 API 호출.
- **Upload 중복 검사:** `POST /api/upload/check` — 클라이언트 첫 64KB SHA-256 (Web Crypto / `simpleHash` 폴백) → 서버가 파일크기+부분해시 비교. `OK`/`DUPLICATE_CONTENT`/`DUPLICATE_NAME`. `.gz`는 `GZIPInputStream` 해제 후 비교.
- **2단계 SCP:** `runuser -l sscuser -c "scp ..."` → 임시 경로 → `Files.move()`로 root 권한 최종 이동. **전송됨 판정**: DB SUCCESS 로그 + 로컬 파일 실존(`.gz` 포함) 모두.
- **SSH local user 빈 값 fallback:** `RemoteDumpService.setSshLocalUser(empty)` 가 `System.getProperty("user.name")` 으로 자동 채움. settings UI 의 빈 입력 = "현재 프로세스 계정으로 사용" 명시. POST `/api/servers/ssh-local-user` 응답에 채워진 값 그대로 반환.
- **순번 칼럼은 DB id 기반:** `analysis_history.id`(IDENTITY). NOT_ANALYZED는 `-`.
- **deleted 가시성:** `historyPage()`/`filesPage()`는 `Authentication`으로 ROLE_ADMIN 검사. 비관리자에게 `fileDeleted=true` 응답 제외(서버 측 보안). **대시보드 Analysis Files는 모든 계정에서 deleted 항상 제외**.
- **AES 암호화:** `util/AesEncryptor.java` AES-256-CBC HEX. CLI: `bash heap_enc.sh "평문"`, `bash heap_dec.sh "암호문"`. DB password / RAG password / API key 모두 `ENC(...)` 형식.
- **Leak Rule DB 마이그레이션 (Phase 4):** `leak_library_rules` (66 prefix-based) + `leak_fallback_rules` (33 regex-based) 테이블 + `LeakRuleAdminController` `/admin/leak-rules` ADMIN CRUD + `LeakSuspectAdvisor` 룰 엔진 + `LeakRuleSeeder` 부트스트랩. 코드 배포 없이 운영자가 추가/수정/우선순위 조정. `LeakRuleService.invalidate()` 로 캐시 즉시 갱신.

## Changelog

모든 변경 내용은 `CHANGELOG.md`에 누적 기록. 작업 완료 후 날짜·대상 파일·상세 내역과 함께 추가.

## Refactoring History

`SECURITY_REFACTOR_PLAN.md` 에 단계별 리팩토링 이력 보관. **2026-05-17 기준 모든 보류 항목 해소** (Phase 4A 서비스 분리 / 4B-2 컨트롤러 6 분할 / 5A-3 CSS 통합 / 5B-2 Common.* 마이그레이션 / 5C analyze.html JS 외부화). 새 항목 식별 시 본 문서에 추가.

**Boot 3 마이그레이션 (`BOOT3_MIGRATION_PLAN.md`, 2026-05-19 완료):** Boot 2.7.18 → 3.5.14 / Security 5.7.11 → 6.5.10 / Hibernate 5.6 → 6.6 / Tomcat 9 → 10 / Java 11 → 17 (런타임 JDK 21). 5 phase 분할 (DB 백업 → Java 17 baseline → SecurityConfig lambda DSL 사전 modernize → BIG BANG: jakarta 28 파일 일괄 치환 + requestMatchers + dialect auto-detect → 안정화: Thymeleaf 3.1 fragment syntax + smoke test). 영향: 50 파일 변경, +1185/−157 라인. 운영 인프라: Maven 3.5.4 → 3.9.9 영구 업그레이드 (alternatives + /etc/profile.d). DB: SPRING_SESSION 2종 TRUNCATE + leak_*_rule 5 컬럼 TEXT ALTER. 검증: 14 페이지 200 / 4 ADMIN API 200 / PDF 생성 OK / 기동 12.8s.

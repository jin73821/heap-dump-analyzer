# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Always respond in Korean (한국어). Code and technical identifiers remain in English.

## Project Overview

Java Spring Boot **3.5.14** + Java **17** (런타임 OpenJDK 21) 웹앱. Eclipse MAT CLI로 .hprof/.bin/.dump 분석. MariaDB(`192.168.56.9:3306/HEAPDB`) + Spring Security **6.5** 세션 기반. Hibernate **6.6** + jakarta 네임스페이스 (jakarta.persistence/servlet/annotation/transaction). 2026-05-19 Boot 2.7→3.5 마이그레이션 완료 — 상세는 `BOOT3_MIGRATION_PLAN.md` 참조.

## Build & Run

```bash
mvn clean package -DskipTests           # 빌드 (10~13초)
mvn test                                 # 단위 테스트 311건 (코어덤프 리비전·파일목록 15 / 원격전송 중복명 6 / 비밀번호 만료 6 / 시크릿 암호화 270 / 설정 복원 격리 4 / 결과 디렉토리 스킴 5 / DomRefs 전부-빈 가드 5)
java -jar target/heap-analyzer-2.1.0.jar   # 버전은 pom.xml <version>과 항상 일치
bash restart.sh                          # 운영(18080) 재기동
```

**Maven 요구사항:** Maven 3.6.3+ (Boot 3.5 의 maven-clean-plugin 3.4.1 요구). 본 시스템 설정: Maven 3.9.9 (`/opt/apache-maven-3.9.9`, alternatives 수동 모드 + `/etc/profile.d/maven.sh` 로 `MAVEN_HOME` 설정). 신규 운영 환경 배포 시 동일 버전 설치 필수.

**CRITICAL:** 모든 프론트엔드 리소스(CSS/HTML/JS)는 JAR 내부에 있음. **어떤 변경이든 `mvn clean package -DskipTests && bash restart.sh` 필수.** 빌드+기동 약 20~24초.

**버전 변경 체크리스트:** `pom.xml <version>` 변경 시 산출물 JAR 명이 바뀌므로 **`restart.sh`/`run.sh`/`stop.sh` 의 `heap-analyzer-X.Y.Z.jar` grep 패턴 + UI 표기(`fragments/banner.html`·`index.html`·`progress.html`) 동시 갱신** 필수. (`heap_enc.sh`/`heap_dec.sh` 는 2026-07-31 부터 JAR 자동 탐색이라 대상 아님 — 체크리스트에 없어서 2.0.0 고정인 채 방치됐던 전례.) ⚠️ 스크립트가 새 JAR 명으로만 프로세스를 grep 하므로, **첫 재기동 때 구버전 JAR 프로세스를 자동 종료하지 못해 포트 18080 충돌** 발생 → 구 프로세스 수동 `kill` 후 재기동(이후부터는 정상).

**기동 검증:**
```bash
sleep 18 && grep -E "Started HeapAnalyzerApplication|FAILED|Exception in thread" \
  /opt/genspark/webapp_dump/logs/heapdump-analyzer.log | tail -3
```

**MariaDB 미연결 시 기동 실패** (Spring Session JDBC가 SPRING_SESSION 자동 생성). DB 점검: `mysql -h 192.168.56.9 -u heap_user -p<REDACTED> HEAPDB -e "..."`.

## Architecture

Spring MVC + JPA + MariaDB. **하이브리드 저장**: 메타데이터 + 분석 상세는 DB(`analysis_history` 요약, `analysis_result_detail` 상세 JSON, `analysis_dominator_refs` 사전계산 refs, `ai_insights`, `ai_chat_*`, `login_history` 등), MAT 산출물(ZIP/`.index`/`.threads`/`mat.log`)만 파일 시스템. **2026-07-31 이전의 `data/{base}/result.json`·`dominator-refs.json` 은 폐지** — 기동 시 DB 이관 후 삭제된다.

**디렉토리:**
```
/opt/heapdumps/
├── dumpfiles/    원본 보존 (.hprof/.gz). 업로드 도착지
├── data/         결과 산출물(mat.log/*.zip/*.index/*.threads) + settings.json
│              디렉토리명 = **확장자 포함 파일명**(`data/jeus_admin.hprof/`, `data/jeus_admin.hprof.gz/`)
│              내부 파일명은 MAT 가 생성한 hprof base 기준 유지
└── tmp/          분석 중 복사본 (분석 후 항상 삭제)
```

**분석 흐름:** Upload → dumpfiles → tmp/ copy → MAT CLI(tmp 대상) → 성공 시 tmp 삭제 + data/{filename}/ 에 MAT 산출물 저장 + 상세 JSON 은 `analysis_result_detail` 저장 + (옵션)dumpfiles gzip / 실패 시 tmp만 삭제.

**Controllers (Phase 4B-2 — 도메인별 분할, 2026-05-17):**
- `HeapDumpViewController` — Thymeleaf 페이지 + form POST → redirect 액션 (대시보드/files/history/settings/compare/upload/delete/rerun 등)
- `HeapAnalysisApiController` — SSE 진행 스트림 (`/analyze/progress/*`) + 취소 + 큐 상태
- `HeapReportApiController` — MAT 리포트 HTML/iframe (`/report/{filename}/*`) + PDF + log 청크 + thread stacks
- `HeapFileApiController` — `/api/upload`, `/api/upload/check`, `/download/*`, `/api/files/bulk-delete`
- `HeapHistoryApiController` — `/api/history*`, `/api/history/detections*`, `/api/results/clear`, `/api/compare/data`
- `HeapSystemApiController` — `/api/settings/*`, `/api/mat/*`, `/api/system/status`, `/api/disk/check`, `/api/settings` (전체 설정 조회)
- `HeapAiApiController` — `/api/llm/*` (15 endpoints: enabled/config/apikey/test/analyze/insight/compare/chat/chat-stream/chat-prompt/chat-restore-mode) + `/api/settings/rag*` (7 endpoints)
- `AuthController`(`/login`), `AdminController`(`/admin/users` 5-탭: 사용자/현재접속/접속이력/계정신청/**설정**(2FA·비밀번호 만료, 2026-07-18 General 에서 이동)), `ServerController`(`/servers`, `/servers/{id}`, `/servers/logs`), `AiChatController`(`/ai-chat` 세션 기반), `LeakRuleAdminController`(`/admin/leak-rules` ADMIN CRUD), `ComparisonHistoryController`(`/comparison-history`)
- `GlobalExceptionHandler` — `IllegalArgumentException` → JSON 400 (`/api/`/AJAX) or HTML 302 redirect (`?error=invalidFilename`)

**감사 로깅 컨벤션:** 관리/변경 작업(룰 CRUD, 서버 CRUD 등)은 SLF4J 로 `[Domain] action=create|update|delete ... by={who(auth)}` 구조 로깅. `who(Authentication)` = `auth!=null ? auth.getName() : "unknown"` (각 컨트롤러 static 헬퍼). update 는 before→after diff + `fields=[변경키]`, delete 는 삭제 **전** 식별정보 캡처. `LeakRuleAdminController`(`[LeakRule]`) / `ServerController`(`[Server]`) 가 레퍼런스. **새 mutation 엔드포인트 추가 시 동일 패턴 적용.** (별도 DB 감사 테이블 아님 — 앱 로그 `logs/heapdump-analyzer.log`.)

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
- `MiddlewareDetector.detect(histogram, threads, sysProps)` — WAS/DB 벤더 추정 (sysprop 마커 가중치 100 권위적 확정 → 이름 best-effort). `matchCount` 는 대표 벤더 선택용 내부 점수(UI 미노출). analyze Overview 배지에 사용.
- `OomDetector.classifyMessage(msg)` — OOM 메시지 → 한국어 라벨/원인/권장. analyze Overview 진단 카드 + AI 컨텍스트 주입에 사용.

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

**기본 계정:** admin / <REDACTED> (BCrypt, `UserService.initDefaultAdmin()`).

**계정별 격리:** AI 채팅 세션은 `Principal.getName()`으로 username 일치 검증.

**로그인 2차인증 (2026-07-16, 설정 UI 는 2026-07-18 Accounts `/admin/users` '설정' 탭으로 이동):** `off|otp|sso` 3-state (`TwoFactorConfigService` — LLM/RAG 와 동일 3-hook 영속화). OTP 모드는 표준 TOTP(RFC 6238, `TotpUtil` 자체 구현 + zxing QR) — 1차 성공 시 `TwoFactorAuthenticationSuccessHandler` 가 SecurityContext 를 **`ROLE_PRE_AUTH` 부분 인증 토큰으로 교체** 후 `/login/otp(/setup)` 유도. 인가는 `anyRequest().hasAnyRole("ADMIN","USER")` 라 PRE_AUTH 는 구조적으로 다른 경로 접근 불가 (accessDeniedHandler 가 `/login/otp` redirect). seed 는 `users.otp_secret` 에 `ENC(...)` 저장, ±1 스텝 드리프트 + `otp_last_used_step` replay 방지. **OTP 10회 연속 실패 → `account_locked`** (enabled 와 별개, `CustomUserDetailsService` 가 `accountNonLocked` 매핑 → `LockedException` → `/login?error=locked`) — 해제/OTP 초기화는 `/admin/users` (`POST /api/admin/users/{id}/unlock|otp-reset`), 본인 초기화는 `/account` (`POST /api/account/otp-reset`, 현재 PW 확인). OTP 모드에선 `AuthEventListener.onSuccess` 가 skip — 완전 인증 성공만 `TwoFactorService` 가 login_history 기록 (`LoginHistoryRecorder` 공용). **관리자 OTP 정책** `security.two-factor.admin-policy` = `enforce|enforce_no_lock(기본)|exempt`: exempt 는 SuccessHandler 가 관리자 OTP 건너뜀(+onSuccess 가 성공 기록), enforce_no_lock 은 `verifyOtp` 가 관리자 실패 시 잠금/카운트 누적 안 함. 일반 USER 는 정책 무관 항상 OTP+잠금. **SSO 는 `isSsoConfigured()`(Endpoint URL·Client ID·Client Secret 3필드) 저장돼야만 mode=sso 활성화** (미충족 시 `/api/settings/two-factor?mode=sso` 400). SSO 실연동은 틀만 (`SsoAuthenticator`+`StubSsoAuthenticator`+`/sso/login·callback` 스텁). OTP 입력 화면은 자동제출 안 함(6자리 시 버튼 활성). admin 자기 잠금 비상 복구 SQL 은 CHANGELOG 2026-07-16 참조.

**비밀번호 만료 정책 (2026-07-18):** Accounts(`/admin/users`) '설정' 탭 "Password Expiry Policy" 카드 — `security.password.expiry-days`(0=비활성) + `expiry-admin-exempt`(기본 true). `PasswordPolicyConfigService`(LLM/RAG/2FA 와 동일 3-hook 영속화 + `HeapDumpAnalyzerService.setPasswordPolicy` facade). 판정 기준 = `users.password_changed_at`(신규 컬럼, `@PrePersist` 생성 스탬프, 모든 PW 변경 경로에서 갱신) 우선·없으면 `created_at` 폴백. `POST /api/settings/password-policy?expiryDays=&adminExempt=`(ADMIN+CSRF, `/api/settings/**` 매처 포함). **만료 강제 변경 흐름**: `PasswordPolicyConfigService.ROLE_PWD_EXPIRED` **부분 인증**(ROLE_PRE_AUTH 와 동일 철학 — `hasAnyRole("ADMIN","USER")` 인가상 `/login/password` 외 접근 구조적 차단, accessDeniedHandler 가 redirect). OTP 모드는 **OTP 통과 후** 만료 검사(`TwoFactorService.completeAuthentication` → `LoginCompletion.PWD_EXPIRED`), 미사용/SSO/관리자예외 모드는 1차 완료 시점(`TwoFactorAuthenticationSuccessHandler.finishOrForceChange`)에서 검사 → 둘 다 `/login/password`(`PasswordChangeController`, `changeOwnPassword` 재사용) → 변경 성공 시 `upgradeAfterPasswordChange` 완전 인증 승격(세션 회전 없음). login_history 성공은 2FA/1차 통과 시 이미 기록(만료는 post-auth 게이트). 검증 실패는 리다이렉트 대신 **재렌더**로 GlobalExceptionHandler(IllegalArgumentException→302) 우회. 정책 대기 중 비활성화 시 즉시 승격(자가 치유). `/admin/users` 상태 배지·필터에 `비번만료`(레드)·`만료 D-n`(앰버 14일내)·`비밀번호 만료` 옵션 + **수정 모달에 만료 잔여일**(`renderEditExpiry`, `_allUsers` 재사용). `/account` 는 만료 예정일 dt/dd + **7일 이하(또는 만료) & 정책 활성 시에만 상단 경고 뱃지**(`pwWarnSoon`, 미설정 시 미표기). **users 목록은 서버렌더 `th:each` 제거 → JS 단일 소스**(필터 정합성, JS 실패 시 미필터 전체목록 잔존 방지).

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

17. **common.css `.mbtn-*` 는 색상 전용** — `.mbtn-cancel/.mbtn-confirm/.mbtn-save/...` 는 `background/color` 만 정의. 버튼 **형태**(`padding/border:none/border-radius/font-size/font-weight/cursor`)는 페이지가 제공해야 함(예: `.modal-btns button` 또는 `#myModal .modal-box button`). 형태 규칙 없이 `.mbtn-*` 만 붙이면 **브라우저 기본 버튼**으로 렌더됨. 새 모달 추가 시 형태 규칙 동반 필수.

18. **모바일 미디어쿼리 `!important` 가 인라인 스타일 override** — 일부 페이지(`analyze.css` 등)는 `@media (max-width:...)` 에서 `!important` 로 인라인 스타일을 덮는다(예: 과거 `#aiSeverityBanner { flex-direction:column !important }`). 인라인 스타일을 바꿔도 화면이 안 변하면 **반드시 기존 미디어쿼리 `!important` 규칙부터 grep** 해서 함께 수정할 것. 인라인 변경만으로는 mobile 에서 무효.

19. **JS 파일 다운로드는 blob 방식** — `<a href=exportUrl>` 직접 네비게이션은, 서버 응답 `Content-Disposition` 이 `attachment` 가 아니거나(예: `form-data`) 브라우저별 처리 차이로 **탭 로딩 스피너가 무한 회전**할 수 있음. `fetch(url){credentials:'same-origin'}` → `r.blob()` → `URL.createObjectURL` → `a.download` 클릭 → `revokeObjectURL` 패턴 사용(페이지 네비게이션 없음). 파일명은 `Content-Disposition` 헤더 파싱. `server-logs.html` `confirmExport()` 레퍼런스.

20. **운영 MariaDB 검증은 읽기 전용** — `192.168.56.9/HEAPDB` 는 운영 데이터 보유. 기능 검증 시 UNIQUE 키(`ai_insights.filename`, `target_servers.name` 등) 대상 테스트 INSERT 에 **`ON DUPLICATE KEY UPDATE` 금지**(실데이터 덮어씀, binlog OFF·복구 어려움). 기존 데이터로 GET 검증하거나, 충돌 없는 새 키로 INSERT 후 그 행만 DELETE. 컬럼 조회 시 `2>/dev/null` 로 에러 숨기지 말 것(빈 테이블 오판).

21. **`DisabledException`을 `loadUserByUsername()`에서 직접 throw 금지** — `DaoAuthenticationProvider.retrieveUser()`가 `UserDetailsService`의 모든 예외를 `catch (Exception ex)`로 잡아 `InternalAuthenticationServiceException`으로 wrapping함. 결과적으로 `SecurityConfig`의 failureHandler에서 `ex instanceof DisabledException`이 절대 true가 되지 않아 `/login?error=disabled` 로 분기되지 않음. **올바른 방법**: `enabled=false`인 `UserDetails`를 반환 → Spring Security의 `DefaultPreAuthenticationChecks.check()`가 `retrieveUser()` 반환 후 `DisabledException`을 throw (wrapping되지 않음).

22. **심각도 색상은 4곳 동시 수정** — Critical/High/Medium/Low 색상이 분산되어 있어 하나라도 누락 시 불일치 발생. 수정 대상:
    - `templates/history.html` 인라인 CSS `.dds-critical/.dds-high/.dds-medium/.dds-low` (배지)
    - `templates/history.html` JS `SEVERITY_COLORS` 객체 (차트 색상)
    - `static/js/analyze.js` `_SEV_CONFIG` 객체 (AI 인사이트 배너·아이콘)
    - `templates/analyze-print.html` `.sev-*` border + `.ai-sev.*` 배경 (인쇄본)

23. **Thymeleaf 인라인 `[[` — 일반 `<script>` 안 JS 도 파싱 대상** — `th:inline="javascript"` 가 없는 평범한 인라인 `<script>` 블록이라도 Thymeleaf 3 는 여는 대괄호 2연속(`[[`)을 인라인 표현식 시작으로 해석한다. JS **중첩 배열 리터럴**(`var x = [['a','b'], ...]`)이 대표 사례 — `Could not parse as expression` 로 템플릿 파싱 실패 → 응답이 이미 chunked 전송 중이면 종료 마커 미전송 → 브라우저 `ERR_INCOMPLETE_CHUNKED_ENCODING` 빈 페이지 (+후속 `response is already committed` 로그 오염). **해결**: 객체 배열(`[{a:..},..]`) 등으로 `[[` 시퀀스 자체를 회피 (주석 안 `[[` 도 금지). 정적 픽스처(헤드리스 Chrome) 검증은 Thymeleaf 를 거치지 않아 이 오류를 **못 잡음** — 템플릿 수정 후 SpringTemplateEngine 단독 렌더 스모크(배너 스텁 + 빈 모델)로 검증 가능.

24. **MAT lazy 쿼리 hprof mtime > index mtime ⇒ 전체 reparse** — Dominator Refs/Loaded Classes/Class Instances 의 lazy·precompute 워킹 디렉토리는 원본 hprof 를 symlink 한다. 분석 후 덤프가 `.gz` 압축되면 재조회 시 tmp 로 1회 해제하는데, **해제본 mtime = 현재 시각** 이라 data/ 의 `.index` 보다 항상 최신 → MAT 가 `"hprof is newer than index"` 로 판단해 **전체 힙을 재파싱(30~60초)** 하거나, symlink 인덱스 덮어쓰기 충돌로 **`exit 13`** 실패한다. 후자는 precompute refs(`analysis_dominator_refs`, 2026-07-31 이전엔 `dominator-refs.json` 사이드카)를 **전부 빈 목록**으로 만들고, 이 빈 refs 가 정상 lazy 경로를 가려 재접속 시 "참조 없음" 오표시. **해결:** `HeapDumpAnalyzerService.alignHprofMtimeToIndex()` 가 `linkMatInputs()` 에서 hprof mtime 을 index 보다 60초 이전으로 조정 → MAT reopen(~4초). precompute 는 전부-빈이면 미저장(`hasAnyRefData()` 가드), 로드 시 전부-빈이면 무효 처리(자가 치유). 새 lazy MAT 경로 추가 시 반드시 `linkMatInputs()` 경유. **추가 함정:** precompute 는 분석 완료 직후 백그라운드로 도는데 분석 `finally` 가 공유 작업본 `tmp/{base}.hprof` 를 삭제하는 시점과 겹쳐, precompute 가 그 tmp 를 symlink 한 직후 삭제되면 dangling → exit 13(재접속 lazy 가 같은 경로로 .gz 재해제하기 전까지 지속). precompute 는 `resolveSourceHprof()`(공유 tmp) 가 아닌 **`resolveSourceHprofIsolated()`(전용 `{base}.precompute.hprof`, 사후 삭제)** 사용 필수. **동시성(메모리 기반 동적 게이트):** MAT 자식은 각 `MemoryAnalyzer.ini -Xmx` 만큼 힙 점유 → 모든 MAT spawn(`runMatCliWithProgress`(분석) + `runMatSingleQuery`(precompute/lazy))이 전역 공정 세마포어 `matSlots` 를 점유. 한도 = `recomputeMatConcurrency()` 가 `min(floor((hostRAM×0.8 − appXmx)/matXmx), cpus)` 로 산정(시작 시 + `setMatHeapSize` 시 재산정, `mat.max-concurrent-processes`>0 override). 4GB→1(직렬화/양보), 32GB→MAT -Xmx 에 따라 N(동시 실행). precompute 끼리는 `domRefPrecomputeExecutor`(single-thread)로도 직렬. lazy 는 slot 부족 시 SSE `waiting`/`cl-waiting`/`inst-waiting` 로 사용자에게 대기 안내(사전계산 HIT 는 게이트 이전 즉시 응답). **새 MAT 호출 추가 시 반드시 `runMatSingleQuery`/`runMatCliWithProgress` 경유**(직접 MAT spawn 금지 — 게이트 우회).

25. **`loadPersistedSettings()` 의 catch 범위 — 복원 예외를 JSON 파싱 실패로 오인 금지** — 예전엔 하나의 `try` 가 JSON 파싱과 복원 로직(`applyFromSettings` 5개)을 함께 감쌌고, `catch (Exception)` 이 무엇이 터지든 settings.json 을 `.corrupted` 로 rename 한 뒤 **LLM/RAG/2FA/비밀번호정책/원격 설정 전량을 기본값 리셋**했다. AES 복호화 예외 하나로 전 설정이 날아가는 구조(= `HEAP_ANALYZER_ENCRYPTION_KEY` 도입 시 즉시 발동하는 지뢰). 현재는 **파싱 try 와 복원 try 가 분리**돼 있고 복원은 `applyStep(failed, name, Runnable)` 로 그룹별 격리된다. **rename + `persistSettings()` 는 JSON 파싱 실패 경로에서만 호출할 것.** 복원 실패 그룹이 있으면 `syncApplicationProperties()` 도 생략한다(반쪽 상태의 2차 오염 차단). 회귀 방어는 `SettingsRestoreIsolationTest` 4건. **새 `applyFromSettings` 그룹 추가 시 반드시 `applyStep` 으로 감쌀 것.**

## Key Design Decisions

- **Two-tier cache:** In-memory `ConcurrentHashMap` ← DB `analysis_result_detail.result_json` 복원(`restoreResultsFromDb`). 누락 필드(componentDetailHtmlMap/histogramHtml/threadOverviewHtml)는 ZIP에서 lazy 재추출.
- **분석 상세 저장은 DB 단일 원본 (2026-07-31):** `persistResult()` 가 상세 JSON 을 `analysis_result_detail`(LONGTEXT)에, `mat.log` 만 결과 디렉토리에 쓴다. `analysis_history` 는 목록/집계용 요약 23컬럼 — **별도 테이블로 분리한 이유는 `findAll()` 마다 수 MB LOB 를 끌고 오지 않기 위해서**. 삭제 경로 3곳(`deleteHistory`/`clearCache`/이관 실패)에서 detail 행을 동반 처리해야 하며, 특히 `clearCache()`(재분석 직전 호출) 누락 시 **옛 결과가 되살아난다**. 파생 delete 는 트랜잭션 필수라 리포지토리 메서드에 `@Transactional` 명시(clearCache 는 비트랜잭션 컨텍스트). 레거시 `result.json` 은 기동 시 `migrateResultJsonToDb()` 가 **DB 저장 확인 후에만** 삭제(실패 시 다음 기동 재시도). ⚠️ **data/ 는 없어지지 않는다** — ZIP/`.index`/`.threads` 는 계속 파일.
- **Dominator Refs 사전계산도 DB (`analysis_dominator_refs`, 2026-07-31):** 구 `data/{filename}/dominator-refs.json` 사이드카. `saveDominatorRefsToDb()`/`loadDominatorRefsSidecar()` 가 담당하고 저장 JSON 구조는 사이드카와 동일(`{version, generatedAt, topN, capPerList, refs{}}`). **`analysis_result_detail` 과 또 별도 테이블인 이유**: refs 는 조회 시점에만 필요한 lazy 데이터(35~135KB/건)라 같은 행에 두면 기동 복원이 쓰지도 않을 LOB 를 매번 로드한다. 전부-빈 refs 미저장 가드는 `hasAnyRefData()` static (`DominatorRefsEmptyGuardTest` 5건) — 저장·이관 양쪽에 적용. 삭제는 `deleteHistory`/`clearCache` 동반.
- **결과 디렉토리 = 확장자 포함 파일명 (2026-07-31):** 구 스킴(`stripExtension`)에선 `X.hprof` 와 `X.hprof.gz` 가 히스토리 상 별개 행이면서 디렉토리는 base 하나를 공유해, 뒤 분석이 앞 결과를 덮어써 **목록엔 SUCCESS 인데 진입하면 결과가 없는 행**이 생겼다(운영 실측 2건). 기동 시 `migrateResultDirsToFilenameScheme()` 이 result.json 의 `filename` 을 근거로 rename 하므로 **DB 이관보다 반드시 먼저 실행**. base→디렉토리 역탐색이 필요하면 `findResultDirByBase()` 사용(직접 조합 금지). 회귀 방어는 `ResultDirectorySchemeTest`(5).
- **`dumpCreationTime` 은 `cloneWithoutLog()` 에 반드시 포함:** 빠지면 저장본이 `null` 이 되고 `sanitizeCachedHtml()` 의 `|| dumpCreationTime == null` 조건이 **매 기동 System_Overview ZIP 재파싱**을 영구 반복한다(자가치유라 증상이 안 보임). 신규 필드를 `HeapAnalysisResult` 에 추가할 때 `cloneWithoutLog()` 반영 여부를 항상 확인할 것.
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
- **`analysis_history.server_name` 이중 용도:** SSH 전송 시 출처 서버명 자동 기록 + analyze Overview "출처 호스트명" 칩으로 **수동 편집 가능**(`POST /api/history/{filename}/hostname`, 수동 업로드 덤프용). history 목록 Server 컬럼·detection 서버별 집계와 동일 컬럼 공유.
- **AI 인사이트 영속화:** `ai_insights.insight_data`(JSON mediumtext) 에 전체 맵 저장, `loadAiInsight()` 가 top-level 맵으로 반환(`summary`/`rootCause`/`recommendations`/`severity`/`analysedAt` 등). `saveAiInsight()` 가 `analysedAt` 을 입력 맵에 스탬프하므로 신규 분석 응답(`/api/llm/analyze`)도 즉시 시각 표시 가능. analyze Overview 의 Leak Suspects 하위 요약 카드 + 전용 AI 패널이 동일 데이터 사용. **DB 가 유일한 저장소 — 파일로 쓰는 경로 없음**(2026-07-31): 레거시 `data/{base}/ai_insight.json` 은 기동 시 `migrateAiInsightsToDb()` 또는 `loadAiInsight()` 폴백이 DB 이관(원본 `analysedAt` 보존 = `persistInsight(..., stampNow=false)`)한 뒤 **파일을 삭제**한다. DB 저장 실패 시에만 파일 잔존(다음 기동 재시도). 새 인사이트 종류 추가 시 파일 저장 금지 — `saveAiInsight(key, map)` 만 사용(코어덤프는 `__core__:`, 비교는 `__compare__:` 합성 키).
- **deleted 가시성:** `historyPage()`/`filesPage()`는 `Authentication`으로 ROLE_ADMIN 검사. 비관리자에게 `fileDeleted=true` 응답 제외(서버 측 보안). **대시보드 Analysis Files는 모든 계정에서 deleted 항상 제외**.
- **AES 암호화:** `util/AesEncryptor.java` AES-256-CBC. CLI: `bash heap_enc.sh "평문"`, `bash heap_dec.sh "암호문"` (JAR 자동 탐색 — 버전 하드코딩 없음, `HEAP_ANALYZER_JAR` 로 override). DB password / RAG password / API key / OTP seed / SSO secret 모두 `ENC(...)` 형식. **저장 형식 3종** (2026-07-31): ① `v2`+HEX = 현재(랜덤 IV, 마커로 명확) ② HEX>64 = 마커 이전 랜덤 IV ③ HEX≤64 = 레거시 고정 IV. ⚠️ **마커가 필요한 이유** — 랜덤 IV 형식은 평문 15바이트 이하일 때 `IV(16)+1블록(16)` = 정확히 64 HEX 라 레거시와 길이가 겹치고, 고정 IV 로 오복호화해도 CBC 특성상 2번째 블록은 정상 복원 + 패딩 유효라 **예외 없이 `쓰레기 16바이트+평문`** 이 반환됐다(15자 이하 비밀번호 = 조용한 손상). 마커 없는 값의 판별 규칙은 하위 호환을 위해 그대로 유지 — **변경 금지**.
- **`SecretSanity`** — 복호화 **결과값** 위생 검사(U+FFFD/C0/DEL/C1 결정적 4규칙, 휴리스틱 금지). "복호화 실패"를 잡는 검사는 무용지물 — 이 함정은 복호화가 **성공**한다. `describe()` 는 원문 미노출.
- **모호 구간 자동 복구** — 마커 없는 정확히 64 HEX 는 `decrypt()` 가 양쪽 해석을 모두 시도해 위생 검사로 채택(랜덤 IV만 정상이면 자동 복구 + INFO). `decryptIfEncryptedChecked()` 는 `Decrypted` record 반환, **절대 throw 하지 않음** → 선택 기능(RAG/SSO)의 `@PostConstruct` 소프트 페일용. DB(`DataSourceConfig`)만 fail-fast 유지.
- **`SecretValue`** — 로드 당시 암호문을 보관하고 **값이 실제로 바뀐 경우에만 재암호화**. 손상 평문의 재암호화 세탁(원본 영구 소실) 차단 + 기동 churn 제거. 암호화 실패 시 `forStorage()` 가 `null` → 호출자가 **키 자체를 생략**해 기존 저장값 보존(빈 문자열로 덮으면 시크릿 무경고 삭제). **새 시크릿 필드 추가 시 반드시 `SecretValue` + `putSecret()` 패턴 사용** — RAG 3종/SSO clientSecret 이 레퍼런스.
- ⚠️ **파일로 시크릿을 직접 정리할 때** — Spring 이 부팅 시 읽는 건 **JAR 내부 사본**(`BOOT-INF/classes/application.properties`)이다. settings.json + 소스 properties 만 고치면 `init()` 이 옛 값을 읽어 WARN 이 남는다(직후 `applyFromSettings` 가 덮어써 동작엔 무해). **재빌드까지 필요**.
- 회귀 방어: `AesEncryptorTest`(237, 반복 200 포함) / `SecretSanityTest`(22) / `RagConfigServiceSecretTest`(11, 세탁루프·churn) / `SettingsRestoreIsolationTest`(4) / `ResultDirectorySchemeTest`(5) / `DominatorRefsEmptyGuardTest`(5).
- 후속 항목(LLM API 키 평문 저장, OTP rekey 도구, `HEAP_ANALYZER_ENCRYPTION_KEY` 미설정)은 `SECRET_ENCRYPTION_FOLLOWUP.md`.
- **Dump Creation Time 파싱:** `HeapAnalysisResult.dumpCreationTime` 필드 — MAT System Overview ZIP `index.html`의 `<td>Date</td>`/`<td>Time</td>` TD 쌍을 파싱. MAT는 JVM 로케일(한국어)로 출력하므로 `"2026. 5. 29."` + `"오후 6시 18분 53초 GMT+9"` 형태. `HeapDumpAnalyzerService.parseDumpCreationTime()` 이 오전/오후 24h 변환 후 `"2026-05-29 18:18:53"` 반환. 기존 result.json에 필드 없을 경우 `reparseOverviewMeta()` 가 `dumpCreationTime == null` 조건으로 재파싱 (classLoader/gcRoot 0 조건과 OR).
- **Leak Rule DB 마이그레이션 (Phase 4):** `leak_library_rules` (98 prefix-based: 66 base + WebLogic 10·Tomcat 5·JEUS 10·Oracle 5·Tibero 2 보강 2026-05-31) + `leak_fallback_rules` (66 regex-based: 33 base + WebLogic 10·Tomcat 5·JEUS 10·Oracle 5·Tibero 3) 테이블 + `LeakRuleAdminController` `/admin/leak-rules` ADMIN CRUD + `LeakSuspectAdvisor` 룰 엔진 + `LeakRuleSeeder` 부트스트랩. 코드 배포 없이 운영자가 추가/수정/우선순위 조정. `LeakRuleService.invalidate()` 로 캐시 즉시 갱신.

## Changelog

모든 변경 내용은 `CHANGELOG.md`에 누적 기록. 작업 완료 후 날짜·대상 파일·상세 내역과 함께 추가.

## Refactoring History

`SECURITY_REFACTOR_PLAN.md` 에 단계별 리팩토링 이력 보관. **2026-05-17 기준 모든 보류 항목 해소** (Phase 4A 서비스 분리 / 4B-2 컨트롤러 6 분할 / 5A-3 CSS 통합 / 5B-2 Common.* 마이그레이션 / 5C analyze.html JS 외부화). 새 항목 식별 시 본 문서에 추가.

**Boot 3 마이그레이션 (`BOOT3_MIGRATION_PLAN.md`, 2026-05-19 완료):** Boot 2.7.18 → 3.5.14 / Security 5.7.11 → 6.5.10 / Hibernate 5.6 → 6.6 / Tomcat 9 → 10 / Java 11 → 17 (런타임 JDK 21). 5 phase 분할 (DB 백업 → Java 17 baseline → SecurityConfig lambda DSL 사전 modernize → BIG BANG: jakarta 28 파일 일괄 치환 + requestMatchers + dialect auto-detect → 안정화: Thymeleaf 3.1 fragment syntax + smoke test). 영향: 50 파일 변경, +1185/−157 라인. 운영 인프라: Maven 3.5.4 → 3.9.9 영구 업그레이드 (alternatives + /etc/profile.d). DB: SPRING_SESSION 2종 TRUNCATE + leak_*_rule 5 컬럼 TEXT ALTER. 검증: 14 페이지 200 / 4 ADMIN API 200 / PDF 생성 OK / 기동 12.8s.

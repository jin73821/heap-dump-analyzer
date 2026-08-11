# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Always respond in Korean (한국어). Code and technical identifiers remain in English.

## Project Overview

Java Spring Boot **3.5.14** + Java **17** (런타임 OpenJDK 21) 웹앱. Eclipse MAT CLI로 .hprof/.bin/.dump 분석. MariaDB(`192.168.56.9:3306/HEAPDB`) + Spring Security **6.5** 세션 기반. Hibernate **6.6** + jakarta 네임스페이스 (jakarta.persistence/servlet/annotation/transaction). 2026-05-19 Boot 2.7→3.5 마이그레이션 완료 — 상세는 `BOOT3_MIGRATION_PLAN.md` 참조.

## Build & Run

```bash
mvn clean package -DskipTests           # 빌드 (10~13초)
mvn test                                 # 단위 테스트 328건 (코어덤프 리비전·파일목록 15 / 원격전송 중복명 6 / 비밀번호 만료 6 / 시크릿 암호화 270 / 설정 복원 격리 4 / 결과 디렉토리 스킴 5 / DomRefs 전부-빈 가드 5 / Leak 룰 골든 12 / MAT suspects 파싱 5)
java -jar target/heap-analyzer-2.3.3.jar   # 버전은 pom.xml <version>과 항상 일치
bash restart.sh                          # 운영(18080) 재기동
```

**Maven 요구사항:** Maven 3.6.3+ (Boot 3.5 의 maven-clean-plugin 3.4.1 요구). 본 시스템 설정: Maven 3.9.9 (`/opt/apache-maven-3.9.9`, alternatives 수동 모드 + `/etc/profile.d/maven.sh` 로 `MAVEN_HOME` 설정). 신규 운영 환경 배포 시 동일 버전 설치 필수.

**CRITICAL:** 모든 프론트엔드 리소스(CSS/HTML/JS)는 JAR 내부에 있음. **어떤 변경이든 `mvn clean package -DskipTests && bash restart.sh` 필수.** 빌드+기동 약 20~24초.

**버전 변경 체크리스트:** `pom.xml <version>` 변경 시 **UI 표기(`fragments/banner.html`·`index.html`·`progress.html`) 갱신**만 하면 된다. **쉘 스크립트는 전부 버전 비의존** — `run.sh`/`restart.sh`/`stop.sh` 는 `env.sh` 를 source 해 `target/heap-analyzer-*.jar` 최신본을 자동 탐색하고(`APP_VERSION` 은 파일명에서 역산), 프로세스 매칭도 버전 무관 패턴(`APP_PROC_PATTERN`)이라 **구버전 JAR 로 떠 있는 프로세스도 정상 종료**한다(2026-08-10 이전엔 버전 하드코딩 grep 이라 첫 재기동 때 포트 18080 충돌이 났다). `heap_enc.sh`/`heap_dec.sh` 도 2026-07-31 부터 JAR 자동 탐색.

**기동 스크립트 설정은 `env.sh` 단일 지점:** 경로/포트(`SERVER_PORT`)/JVM 힙(`JVM_XMS`·`JVM_XMX`)/GC 로그/truststore(`TRUSTSTORE`·`TRUSTSTORE_PASS`)/대기시간(`STOP_WAIT_SECS`·`RESTART_STOP_WAIT_SECS`·`BOOT_WAIT_SECS`)/기동 판정 패턴이 모두 여기 있고, 공용 헬퍼(`app_pids`/`require_jar`/`build_trust_opts`/`stop_app`/`start_app`/`wait_for_boot`)도 여기 있다. 세 스크립트는 얇은 래퍼일 뿐이니 **값이든 로직이든 스크립트 본문에 리터럴을 새로 넣지 말 것.** 모든 변수는 `${VAR:-기본값}` 이라 환경변수로 override 가능(`SERVER_PORT=18081 bash run.sh`). 운영 비밀값(`HEAP_ANALYZER_ENCRYPTION_KEY` 등)은 `env.local.sh`(있으면 자동 source, `.gitignore` 대상)에 export.

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
- `AccountController` — 본인 자기서비스: `/account`(계정정보/비밀번호/OTP초기화/개인 메모장), `/account/memo`(메모장 새창 전용 **자립형** 페이지), `/api/account/{password,otp-reset,memo,memo-font,memo-autosave}`
- `AuthController`(`/login`), `AdminController`(`/admin/users` 5-탭: 사용자/현재접속/접속이력/계정신청/**설정**(2FA·비밀번호 만료, 2026-07-18 General 에서 이동)), `ServerController`(`/servers`, `/servers/{id}`, `/servers/logs`), `AiChatController`(`/ai-chat` 세션 기반), `LeakRuleAdminController`(`/admin/leak-rules` ADMIN CRUD), `ComparisonHistoryController`(`/comparison-history`)
- `GlobalExceptionHandler` — `IllegalArgumentException` → JSON 400 (`/api/`/AJAX) or HTML 302 redirect (`?error=invalidFilename`)

**감사 로깅 컨벤션:** 관리/변경 작업(룰 CRUD, 서버 CRUD 등)은 SLF4J 로 `[Domain] action=create|update|delete ... by={who(auth)}` 구조 로깅. `who(Authentication)` = `auth!=null ? auth.getName() : "unknown"` (각 컨트롤러 static 헬퍼). update 는 before→after diff + `fields=[변경키]`, delete 는 삭제 **전** 식별정보 캡처. `LeakRuleAdminController`(`[LeakRule]`) / `ServerController`(`[Server]`) 가 레퍼런스. **새 mutation 엔드포인트 추가 시 동일 패턴 적용.** (별도 DB 감사 테이블 아님 — 앱 로그 `logs/heapdump-analyzer.log`.)

**DTOs (`model/dto/` 패키지, Phase 4B-2):** `AnalysisHistoryItem` / `DailyDetection` / `ServerSeries` / `DetectionSummaryItem` / `DetectionAggregate` / `DetectionDayFile` / `DetectionRecentItem` / `ClassDiff` / `HistogramDiff` / `SuspectDiff` / `KpiDiff` — 11 DTO. 이전엔 `HeapDumpController` inner static class 였음.

**Services (Phase 4A 종합 추출, 2026-05-12):**
- `HeapDumpAnalyzerService` (3,951 라인) — 분석 + **모든 런타임 설정 영속화 단일 책임**: settings.json ↔ application.properties 동기화. ⚠ 2026-08-02 부터 **LLM/RAG/AiInsight getter facade 는 제거됨** — 조회는 `LlmConfigService`/`RagConfigService`/`AiInsightManager` 직접 주입. **setter facade 는 유지**(위임 후 `persistSettings()` 부수효과) — 설정 변경은 반드시 이 setter 경유.
- `LlmConfigService` (1,328 라인) — LLM 12 필드 + 17 getter/setter + 4 호출 메서드 (`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`/`testLlmConnection`) + `disableSslVerification` + `GENSPARK_MODELS`
- `RagConfigService` (398 라인) — RAG 26 필드 + 32 getter + 5 그룹 setter + AES 암호화
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

**Parser:** `MatReportParser` — Overview/Top Components/Suspects ZIP 다단계 추출. `sanitizeHtml()`/`extractBodyContent()` 두 곳에 존재 (parser, service) — 동기화 유지 필수. Suspects 섹션 추출은 **차트 이미지맵(`<map>…</map>`) 제거본**을 대상으로 하고(`MAP_BLOCK_PATTERN`), 등록 전 `isRealSuspectSection()` 로 검증한다 (함정 28). 상한은 `MAX_SUSPECTS`(20).

**External:** `/opt/mat/ParseHeapDump.sh` (suspects/overview/top_components 리포트, 30분 타임아웃).

## Frontend

Thymeleaf + vanilla JS + Chart.js. 빌드 도구 없음.

**공통 인프라 (Phase 5A/5B/5C — common.* 통합):**
- `/css/common.css` — reset / body base / `.topbar*` / `.modal-ov` + `@keyframes modalIn` + `.modal-box` base / btn 색상 utility 3 그룹 (cancel `.mbtn-cancel/.btn-cancel/.sa-btn-cancel` · danger `.mbtn-del/.mbtn-danger/.btn-delete/.sa-btn-del` · primary `.mbtn-save/.mbtn-primary/.mbtn-confirm/.btn-download`) / 데이터 테이블 4 family base (`.htable/.ftable/.stable/.utable` + sortable) — 80+ 라인 공통. 페이지별 변형(opacity 변형, padding/font-size, min-width 등)은 인라인 cascade override.
- `/js/common.js` — `window.Common` 네임스페이스: `escHtml(s)` (5문자 escape) / `csrfToken()` / `csrfHeaderName()` / `fetchJSON(url, opts)` (자동 CSRF/Content-Type, non-2xx throw, JSON 자동 파싱) / `appendCsrfToForm(form)` / `formatBytes(bytes)` / `toast(msg, type)` (settings 계열 — CSS 는 common.css `.toast/.toast-success/.toast-error`) / `showToast(msg, type)` (`#toast` 고정 엘리먼트 계열 — servers/server-detail/admin-users). `banner.html`에서 1회 로드 → 14 페이지 자동 가용.
- **페이지별 로드 공통 모듈 (banner 전역 아님, 2026-08-02):** `/js/table-grid.js`(데이터 그리드 엔진) · `/js/select-mode.js`(다중 선택 모드) · `/js/float-tooltip.js`(`[data-tooltip]` **커서 추종형** 툴팁 — servers/server-detail) · `/js/krds-tooltip.js`(`[data-tip]` **앵커드 팝오버** 툴팁, KRDS component_08_05 — history/settings) · `/js/memo.js`(개인 메모장 — 저장/자동저장/창간 동기화, account·account-memo 공유). 소비 페이지의 `<script>` 태그로만 로드.
- **툴팁은 2종 — 새로 붙일 때 성격에 맞는 쪽을 고를 것.** ① `float-tooltip.js`(`data-tooltip`): 커서를 따라다니고 최대 360px, hover 전용. 표 안 배지처럼 트리거가 작고 많은 곳에 적합. ② `krds-tooltip.js`(`data-tip`): 트리거 위 중앙에 붙고 공간 부족 시 아래로 뒤집히며 화살표가 트리거를 가리킴, 280px, hover+**키보드 focus**+터치 탭 토글+Esc/바깥클릭 닫기+`role="tooltip"`/`aria-describedby`. 설명이 길거나 접근성이 필요한 설정 항목용. **두 모듈을 한 페이지에 같이 싣지 말 것**(트리거 속성이 달라 동작은 하지만 디자인이 섞인다). krds 판은 `white-space:pre-wrap` 이라 문단 구분 `&#10;` 이 그대로 살고, CSS·싱글턴 DOM 을 스스로 주입하므로 페이지는 `<script>` 한 줄만 넣으면 된다. 키보드 접근이 필요한 트리거에는 `tabindex="0"` 필수.
- 캐시 무효화: `?v=YYYY-MM-DD[a-z]` 쿼리 파라미터. 모든 페이지 일괄 갱신.

**페이지:** `/`(Dashboard, 멀티 업로드 큐), `/files`, `/history`, `/compare`(파라미터 없으면 picker), `/analyze/{filename}`(KPI/TopConsumers/Suspects/Histogram/Threads/AI/RawData + 플로팅 채팅 FAB), `/progress/{filename}`(SSE), `/settings`(General), `/settings/llm`, `/settings/rag`, `/ai-chat`(세션 사이드바), `/servers*`, `/admin/users`(ADMIN), `/admin/leak-rules`(ADMIN), `/comparison-history`, `/account`, `/account/memo`(메모장 새창 — 배너 없는 독립 페이지), `/login`.

**analyze.html JS 외부화 (Phase 5C, 2026-05-17):** 인라인 3,125 라인을 `/js/analyze.js` (149KB)로 추출. analyze.html은 1,057 라인 (-75%). 인라인 잔존:
- 에러 페이지 전용 `<script th:if="${error}" th:inline="javascript">` (`[[${filename}]]` 사용)
- Thymeleaf 모델 변수 노출 `<script th:inline="javascript">` (`USED_BYTES`/`FILENAME`/`OBJ_NAMES`/`THREAD_STACKS` 등 13 vars)
- external analyze.js는 위 변수들을 글로벌로 참조 — script 순서(인라인 → external) 유지 필수.

**Dominator Tree 인라인 막대 (2026-08-09):** Shallow/Retained 두 컬럼은 각자 `.dom-bar-track`(좌우 8px 인셋) 안에서만 자란다 — 컬럼 경계에 항상 16px 여백이 남아 둘 다 100% 여도 맞닿지 않는다. 계열색은 **단색** `#5EEAD4`(shallow) / `#60A5FA`(retained) 이며 **그라디언트로 되돌리지 말 것**(과거 shallow 끝과 retained 시작이 둘 다 `#bfdbfe` 라 접합부 ΔE 0.0 으로 한 덩어리로 보였다). 이 두 색은 dataviz 검증기(`validate_palette.js`) 통과본(CVD ΔE 14.7 / 정상시야 21.4)이므로 **색을 바꾸려면 검증기를 다시 돌릴 것** — 파랑↔보라는 눈으로만 보면 멀쩡해도 deutan ΔE 0.3~2.3 으로 실패한다. 흰 배경 대비가 3:1 미만이라 **막대 위 값 라벨은 필수**(제거 금지). 헤더 스와치 `.dom-lgd-*` 는 막대와 같은 색을 유지.

**Global Banner** (`fragments/banner.html`) — 모든 페이지에 `th:replace`로 삽입되는 좌측 고정 배너. 220px ↔ 44px 토글, `localStorage('bannerCollapsed')`. CSS 변수 `--banner-w`가 모든 페이지의 topbar `left`/container `padding-left` 제어. 모바일(≤900px) 숨김. Navigation: Dashboard / Files / History / Comparison / AI Chat / Servers(아코디언) / Settings(아코디언: General/LLM/RAG/Accounts(ADMIN)/Leak Rules(ADMIN)) / Logout. System Status는 `/api/system/status` 60초 폴링 + `localStorage` 캐시. **Banner sub-menu**: `.gb-nav-sub.open { max-height: 200px }` 공용 트랜지션. **FOUC 방지**: `<style>` 앞 인라인 스크립트가 collapsed 클래스 즉시 적용.

**Files/History 공통 데이터 그리드 패턴 (2026-08-02 공통 엔진화):** 검색 + 행표시 셀렉트(20/30/50/100, localStorage) + admin "deleted 표시" 체크박스 + 다중선택 + 헤더 클릭 정렬(`data-sort-key`/`data-sort-type=num|str` ▲▼ 인디케이터, 한글 `localeCompare(s, 'ko')`) + 페이지네이션 (‹Prev / 1 … 현재±2 … 마지막 / Next›). **엔진은 `/js/table-grid.js`(`TableGrid.create(cfg)`) + `/js/select-mode.js`(`SelectMode.create(cfg)`) 공통 모듈** — files/history/servers/comparison-history 가 사용, 페이지는 얇은 전역 위임 함수(`gotoPage`/`onHeaderSort`/...)로 HTML inline 핸들러를 보존하고 고유 로직(필터 술어, files 의 exec sub-row 페어)은 cfg 훅으로 주입. server-logs 는 0-base 서버사이드라 미사용. 새 목록 페이지는 이 두 모듈 재사용할 것. raw 정렬값은 `<tr data-sort-*>` 직렬화 — `AnalysisHistoryItem`의 raw 바이트 필드(`sizeBytes`/`heapUsedBytes` 등) 사용. **다중 삭제 API 분리**: `/api/history/bulk-delete`(분석 기록 + 옵션 heap dump) vs `/api/files/bulk-delete`(heap dump만, 분석 기록 보존).

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

**세션 만료 응답 규약 (2026-08-06):** `/api/**` 미인증 → **401 JSON** `{"success":false,"code":"SESSION_EXPIRED","error":"..."}`, 비-API 경로 → 종전대로 `/login` 302. `SecurityConfig.exceptionHandling` 에 `defaultAuthenticationEntryPointFor` **두 개**(`/api/**` JSON · 나머지 `LoginUrlAuthenticationEntryPoint`)를 등록해 구현. `accessDeniedHandler` 도 `/api/**` 면 JSON(CSRF 소실/불일치 → 401 SESSION_EXPIRED, 그 외 403). `Common.fetchJSON` 은 401 에 `err.sessionExpired = true` + 한국어 메시지를 붙이므로 페이지 JS 는 `e.sessionExpired` 로 분기하면 된다. **재로그인 후에는 세션이 바뀌어 CSRF 토큰도 새로 발급**되므로 열린 페이지의 `<meta name="_csrf">` 를 `GET /api/csrf` 로 갱신해야 이후 POST 가 통과한다(리로드 대안). 작성 중 데이터가 있는 화면은 강제 리다이렉트 대신 **백업 + 재로그인 유도**로 처리할 것 — 메모장(`memo.js` 의 `backup`/`readBackup`/`refreshCsrf`, autosaver `suspend`/`resume`)이 레퍼런스.

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

26. **새창(팝업)은 opener 문서에 의존하면 안 됨** — `window.open('', name)` + `document.write()` 로 만든 팝업은 자체 스크립트가 없어, 버튼 핸들러가 **부모 문서의 함수/DOM 을 참조하는 클로저**가 된다. 부모가 다른 페이지로 이동하면 Document 와 그 JS 컨텍스트가 폐기돼 그 클로저는 죽은 객체를 참조한다(Firefox `can't access dead object` TypeError, Chrome 은 `getElementById` 가 null 반환) → **저장·동기화가 조용히 실패**(팝업 코드가 `catch (e) {}` 로 감싸여 있으면 증상조차 안 보임). 개인 메모장이 실제로 이 형태였고 2026-08-06 에 `GET /account/memo` **자립형 라우트 + 전용 템플릿**으로 전환했다. 새 팝업/새창 기능을 만들 때는 ① 서버 라우트를 가진 실제 페이지로 열고 ② 자기 컨텍스트에서 API 를 직접 호출하며 ③ opener 접근은 `window.opener && !window.opener.closed` 가드 안의 optional 경로로만 두고 ④ 창 간 상태 공유는 상대 DOM 조작이 아니라 **BroadcastChannel(폴백 `storage` 이벤트)** 로 할 것. 팝업 페이지도 CSRF 보호 대상 API 를 부르면 `<head>` 에 `_csrf`/`_csrf_header` meta 가 **자체적으로** 있어야 한다(부모 것을 못 빌린다). 이탈 시점의 마지막 저장은 `navigator.sendBeacon` 이 커스텀 헤더 불가라 CSRF 를 못 실으므로 **`fetch(..., {keepalive:true})`** 를 쓸 것.

27. **AJAX 가 인증 리다이렉트를 "성공"으로 오인** — `fetch` 는 302 를 자동 추종하므로 미인증 API 호출이 `/login` HTML 을 **200** 으로 받는다. `r.ok` 만 보는 코드(`Common.fetchJSON` 포함)는 이를 성공 처리하고, 응답 객체의 필드는 전부 `undefined` 가 된다 → 저장이 안 됐는데 "저장됨" 이 뜨는 조용한 실패. 2026-08-06 에 `/api/**` 를 401 JSON 으로 바꿔 서버 측에서 차단했다(위 *세션 만료 응답 규약*). **응답 본문을 신뢰하기 전에 `success === true` 를 확인**하는 습관을 유지할 것 — `memo.js` 의 `assertSaved()` 가 2중 방어 레퍼런스. 또한 `defaultAuthenticationEntryPointFor` 를 **하나만** 등록하면 Spring 이 그것을 모든 요청의 기본 EntryPoint 로 삼아 페이지 라우트까지 401 이 되므로, 반드시 비-API 매핑과 **쌍으로** 등록한다.

28. **MAT 리포트 파싱은 차트 이미지맵을 반드시 배제** — MAT Leak Suspects `index.html` 은 본문 위에 파이 차트 + `<map><area alt="Slice (a)  Problem Suspect 1: …"></map>` 을 둔다. 섹션 추출 정규식(`PROBLEM_SUSPECT_PATTERN`)은 **HTML 전체를 훑고 태그 구조를 보지 않으므로** area 의 alt 에서도 매칭이 시작돼 **차트 조각이 가짜 suspect 로 등록**된다(`stripTags()` 는 완전한 태그만 지우므로 잘린 `<area …>` 원문이 화면에 노출). 가짜 항목이 개수 상한을 잠식해 **뒤쪽 진짜 suspect 가 잘려나가는** 2차 피해까지 생긴다 — 운영 실측으로 MAT 6건이 화면 5건(진짜 3건)으로 표시된 사례. 2026-08-06 에 `MAP_BLOCK_PATTERN` 제거본에서 섹션을 찾도록 고쳤고(원본 `suspectsHtml` 은 Raw Data 탭 차트 때문에 **무변경**), `isRealSuspectSection()`(`instances of` + `occupy|occupies`)을 2차 방어로 뒀다. MAT 산출물에서 무언가를 정규식으로 뽑을 때는 **차트/이미지맵/목차 마크업이 섞이지 않는지 먼저 확인할 것**. 회귀 방어 `MatReportParserSuspectsTest`(5).

29. **Leak 룰 매칭에서 classLoader 는 className 보다 후순위** — `LeakSuspectAdvisor.tryDbRules()` 는 **2-pass**다: pass 1 이 누수 주체(`className`/`accumulatorClass`)로 전체 룰을 훑고, pass 2 가 미매칭일 때만 `classLoader` 로 훑는다. 세 필드를 동등 매칭하면 **priority 가 더 낮은 WAS 룰이 정확한 라이브러리 룰을 가로챈다** — 실제 사례로 `classLoader=jeus.server.classloader.RootClassLoader`(룰 928)가 `className=com.tmax.tibero.jdbc.driver.TbConnection`(룰 935)을 이겨, Tibero JDBC 커서 누수가 "JEUS 서버 코어 워커 스레드 풀 적체"로 안내됐다. WAS 클래스로더가 로드한 **모든 서드파티 클래스**가 WAS 룰로 흡수되던 결함이다. 클래스로더 매칭 자체는 WAS 자체 객체·ClassLoader 누수 식별에 유효하므로 **제거하지 말고 순위만 유지**할 것. 회귀 방어는 `LeakSuspectAdvisorGoldenTest` 의 `classLoaderDoesNotHijackClassNameRule` / `classLoaderStillMatchesWhenClassNameUnknown`.

30. **sticky 헤더 z-index 는 셀 내부 요소보다 반드시 커야 한다 (동률이면 tbody 가 이긴다)** — `.data-table thead th` 는 `position:sticky; z-index:3`. 셀 안에서 막대 위에 값을 띄우려고 `position:relative; z-index:1` 을 준 요소(`.dom-bar-val`)가 있는데, 부모 td 가 `position:relative` 여도 **`z-index:auto` 면 스태킹 컨텍스트가 안 생겨** 그 자식이 셀에 갇히지 않고 루트에서 헤더와 직접 경쟁한다. z-index 가 같으면 DOM 순서상 뒤인 tbody 가 이겨 **스크롤 시 값이 헤더를 뚫고 올라온다**(2026-08-09 Dominator Tree Shallow/Retained Heap 실제 제보 — 막대는 `z-index:0` 이라 멀쩡하고 값만 겹쳐 원인이 헷갈린다). **셀 내부에 z-index 를 쓰면 그 td 에 `isolation:isolate` 를 함께 줘서 경쟁을 셀 안에 가둘 것.** 중첩 표(`.dom-refs-tbl th` z:2)도 바깥 헤더보다 낮아야 한다. 검증은 스크롤 위치를 훑으며 헤더 밴드 좌표에서 `elementFromPoint` 가 `thead` 를 돌려주는지 보면 확정적이다(스크린샷 눈대중보다 신뢰).

## Key Design Decisions

- **Two-tier cache:** In-memory `ConcurrentHashMap` ← DB `analysis_result_detail.result_json` 복원(`restoreResultsFromDb`). 누락 필드(componentDetailHtmlMap/histogramHtml/threadOverviewHtml)는 ZIP에서 lazy 재추출.
- **분석 상세 저장은 DB 단일 원본 (2026-07-31):** `persistResult()` 가 상세 JSON 을 `analysis_result_detail`(LONGTEXT)에, `mat.log` 만 결과 디렉토리에 쓴다. `analysis_history` 는 목록/집계용 요약 23컬럼 — **별도 테이블로 분리한 이유는 `findAll()` 마다 수 MB LOB 를 끌고 오지 않기 위해서**. 삭제 경로 3곳(`deleteHistory`/`clearCache`/이관 실패)에서 detail 행을 동반 처리해야 하며, 특히 `clearCache()`(재분석 직전 호출) 누락 시 **옛 결과가 되살아난다**. 파생 delete 는 트랜잭션 필수라 리포지토리 메서드에 `@Transactional` 명시(clearCache 는 비트랜잭션 컨텍스트). 레거시 `result.json` 은 기동 시 `migrateResultJsonToDb()` 가 **DB 저장 확인 후에만** 삭제(실패 시 다음 기동 재시도). ⚠️ **data/ 는 없어지지 않는다** — ZIP/`.index`/`.threads` 는 계속 파일.
- - **MAT 진행 표시는 경로가 둘 (2026-08-06):** ① **행 클릭 on-demand lazy** — `/api/dominator-refs/*` 가 사전계산·LRU 캐시를 모두 MISS 해 **실제로 MAT 를 돌 때만** `lazy` SSE 이벤트를 보낸다(슬롯 대기 포함을 위해 `Semaphore` 획득 전 전송). ② **백그라운드 사전계산** — 재분석 직후 `dom-ref-precompute` 스레드가 수십 초~수 분 도는데 사용자 조작이 없어 SSE 로는 알릴 수 없다. `DomRefPrecomputeStatus`(파일별 메모리) + `GET /api/dominator-refs/status/{filename}` 을 `analyze.js` 가 2초 폴링한다. **둘 다 같은 UI**(사이드바 Actions 우측 스피너 + 패널 상태 바)를 쓰며 `_domLazyStart`/`_domLazyEnd`/`_domPreApply`/`_domPreFinish` 가 제어하고, 클릭 lazy 가 진행 중이면 폴링은 UI 를 덮지 않는다. ⚠ **사전계산이 끝난 객체는 행을 클릭해도 sidecar-hit 이라 스피너가 뜨지 않는 것이 정상** — 재분석 후 "스피너가 안 보인다"는 대부분 이 경우다. 사전계산은 **시간 예산**(`mat.dominator-refs.precompute.budget-seconds`, 기본 300초 · Settings > MAT Configuration 에서 60~1800초 조정, 변경은 `setDominatorRefsPrecomputeBudgetSeconds()` 경유)을 넘기면 거기까지만 저장하고 중단하므로 `done < total` 이 정상적으로 발생한다 — UI 는 이를 "완료"가 아니라 **"부분 완료"** 로 구분해 표시한다(항목당 MAT 쿼리 2회 ≈ 6초라 top-n 30 전량에는 190초 이상 필요). 사이드바 스피너는 배너 탭 클론 때문에 **ID 없이 `.dom-nav-spinner` class 로만** 갱신(함정 8). 새 lazy MAT 경로에 진행 표시가 필요하면 두 패턴 중 성격에 맞는 쪽을 따를 것.

**Dominator Refs 사전계산도 DB (`analysis_dominator_refs`, 2026-07-31):** 구 `data/{filename}/dominator-refs.json` 사이드카. `saveDominatorRefsToDb()`/`loadDominatorRefsSidecar()` 가 담당하고 저장 JSON 구조는 사이드카와 동일(`{version, generatedAt, topN, capPerList, refs{}}`). **`analysis_result_detail` 과 또 별도 테이블인 이유**: refs 는 조회 시점에만 필요한 lazy 데이터(35~135KB/건)라 같은 행에 두면 기동 복원이 쓰지도 않을 LOB 를 매번 로드한다. 전부-빈 refs 미저장 가드는 `hasAnyRefData()` static (`DominatorRefsEmptyGuardTest` 5건) — 저장·이관 양쪽에 적용. 삭제는 `deleteHistory`/`clearCache` 동반.
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
- 회귀 방어: `AesEncryptorTest`(237, 반복 200 포함) / `SecretSanityTest`(22) / `RagConfigServiceSecretTest`(11, 세탁루프·churn) / `SettingsRestoreIsolationTest`(4) / `ResultDirectorySchemeTest`(5) / `DominatorRefsEmptyGuardTest`(5) / `LeakSuspectAdvisorGoldenTest`(10, DB 룰 경로 결과 불변).
- 후속 항목(LLM API 키 평문 저장, OTP rekey 도구, `HEAP_ANALYZER_ENCRYPTION_KEY` 미설정)은 `SECRET_ENCRYPTION_FOLLOWUP.md`.
- **Dump Creation Time 파싱:** `HeapAnalysisResult.dumpCreationTime` 필드 — MAT System Overview ZIP `index.html`의 `<td>Date</td>`/`<td>Time</td>` TD 쌍을 파싱. MAT는 JVM 로케일(한국어)로 출력하므로 `"2026. 5. 29."` + `"오후 6시 18분 53초 GMT+9"` 형태. `HeapDumpAnalyzerService.parseDumpCreationTime()` 이 오전/오후 24h 변환 후 `"2026-05-29 18:18:53"` 반환. 기존 result.json에 필드 없을 경우 `reparseOverviewMeta()` 가 `dumpCreationTime == null` 조건으로 재파싱 (classLoader/gcRoot 0 조건과 OR).
- **Leak Rule DB 마이그레이션 (Phase 4):** `leak_library_rules` (98 prefix-based: 66 base + WebLogic 10·Tomcat 5·JEUS 10·Oracle 5·Tibero 2 보강 2026-05-31) + `leak_fallback_rules` (66 regex-based: 33 base + WebLogic 10·Tomcat 5·JEUS 10·Oracle 5·Tibero 3) 테이블 + `LeakRuleAdminController` `/admin/leak-rules` ADMIN CRUD + `LeakSuspectAdvisor` 룰 엔진 + `LeakRuleSeeder` 부트스트랩. 코드 배포 없이 운영자가 추가/수정/우선순위 조정. `LeakRuleService.invalidate()` 로 캐시 즉시 갱신. **2026-08-02 하드코딩 룰 배열(dual-path 폴백 769라인) 제거 — DB 룰 단일 경로**: 룰 미매칭/서비스 미주입/전체 비활성화 시 `analyze()` no-op(suspect 필드 null → UI null 가드가 원문+키워드만 표시). 시드에 catch-all(`.*`) fallback 룰이 있어 DB 룰 활성 시엔 어떤 텍스트든 최소 generic 카테고리를 받는다. 회귀 방어 `LeakSuspectAdvisorGoldenTest`(10).

## Changelog

모든 변경 내용은 `CHANGELOG.md`에 누적 기록. 작업 완료 후 날짜·대상 파일·상세 내역과 함께 추가.

## Refactoring History

`SECURITY_REFACTOR_PLAN.md` 에 단계별 리팩토링 이력 보관. **2026-05-17 기준 모든 보류 항목 해소** (Phase 4A 서비스 분리 / 4B-2 컨트롤러 6 분할 / 5A-3 CSS 통합 / 5B-2 Common.* 마이그레이션 / 5C analyze.html JS 외부화). 새 항목 식별 시 본 문서에 추가.

**Boot 3 마이그레이션 (`BOOT3_MIGRATION_PLAN.md`, 2026-05-19 완료):** Boot 2.7.18 → 3.5.14 / Security 5.7.11 → 6.5.10 / Hibernate 5.6 → 6.6 / Tomcat 9 → 10 / Java 11 → 17 (런타임 JDK 21). 5 phase 분할 (DB 백업 → Java 17 baseline → SecurityConfig lambda DSL 사전 modernize → BIG BANG: jakarta 28 파일 일괄 치환 + requestMatchers + dialect auto-detect → 안정화: Thymeleaf 3.1 fragment syntax + smoke test). 영향: 50 파일 변경, +1185/−157 라인. 운영 인프라: Maven 3.5.4 → 3.9.9 영구 업그레이드 (alternatives + /etc/profile.d). DB: SPRING_SESSION 2종 TRUNCATE + leak_*_rule 5 컬럼 TEXT ALTER. 검증: 14 페이지 200 / 4 ADMIN API 200 / PDF 생성 OK / 기동 12.8s.

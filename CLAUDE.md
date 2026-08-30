# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Always respond in Korean (한국어). Code and technical identifiers remain in English.

## Project Overview

Java Spring Boot **3.5.14** + Java **17** (런타임 OpenJDK 21) 웹앱. Eclipse MAT CLI로 .hprof/.bin/.dump 분석. MariaDB(`192.168.56.9:3306/HEAPDB`) + Spring Security **6.5** 세션 기반. Hibernate **6.6** + jakarta 네임스페이스 (jakarta.persistence/servlet/annotation/transaction). 2026-05-19 Boot 2.7→3.5 마이그레이션 완료 — 상세는 `BOOT3_MIGRATION_PLAN.md` 참조.

## Build & Run

```bash
mvn clean package -DskipTests           # 빌드 (10~13초)
mvn test                                 # 단위 테스트 489건 (라이브 4건은 기본 skip) (세션 유휴 만료 배선 5 / 정적 리소스 charset 4 / 코어덤프 리비전·파일목록 15 / 코어덤프 sysroot(gdb 명령 골든·아카이브 해제 가드·경고·업로드 형식) 39 / 코어덤프 PDF 리포트(모델 빌더 6 + 인쇄·결과화면 렌더/PDF 바이트 스모크 5 + 엔드포인트 MockMvc 7) 18 / 원격전송 중복명 6 / 비밀번호 만료 6 / 시크릿 암호화 270 / 설정 복원 격리 4 / 결과 디렉토리 스킴 5 / DomRefs 전부-빈 가드 5 / Leak 룰 골든 12 / MAT suspects 파싱 5 / LLM 호출량 제한 15 / LLM API 키 암호화 11 / llm-settings 렌더 스모크 3(액션 버튼 치수 포함) / account·account-memo 렌더 스모크 4 / 메모 이력 보관정책 14 / 계정 레이아웃 4 / Chroma 응답 정규화·스코어 변환·컬렉션 메타 파싱·연동 정합성 경고·검색 제외 필터 20 / 임베딩 사이드카 health 4 / Chroma 설정 5곳 동기화 9 / rag-settings 렌더 스모크 3(모드 중심 구조·결함 회귀·액션 버튼 치수) / 토스트 스택 4 / Chroma 라이브 통합 4(기본 skip, `-Dchroma.live=true`))
java -jar target/heap-analyzer-2.3.7.jar   # 버전은 pom.xml <version>과 항상 일치
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

Spring MVC + JPA + MariaDB. **하이브리드 저장**: 메타데이터 + 분석 상세는 DB(`analysis_history` 요약, `analysis_result_detail` 상세 JSON, `analysis_dominator_refs` 사전계산 refs, `ai_insights`, `ai_chat_*`, `login_history`, `memo_history` 등), MAT 산출물(ZIP/`.index`/`.threads`/`mat.log`)만 파일 시스템. **2026-07-31 이전의 `data/{base}/result.json`·`dominator-refs.json` 은 폐지** — 기동 시 DB 이관 후 삭제된다.

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
- `HeapAiApiController` — `/api/llm/*` (16 endpoints: enabled/config/apikey/test/analyze/insight/compare/chat/chat-stream/chat-prompt/chat-restore-mode/**ratelimit**) + `/api/settings/rag*` (8 endpoints, `GET …/chroma/status` 는 USER 열람·토큰 미노출)
- `AccountController` — 본인 자기서비스: `/account`(계정정보/비밀번호/OTP초기화/개인 메모장), `/account/memo`(메모장 새창 전용 **자립형** 페이지), `/api/account/{password,otp-reset,memo,memo-font,memo-autosave}` + **메모 변경 이력** `/api/account/memo/history{,/{id},/{id}/restore}`(소유권은 쿼리로 검증)
- `AuthController`(`/login`), `AdminController`(`/admin/users` 5-탭: 사용자/현재접속/접속이력/계정신청/**설정**(2FA·비밀번호 만료, 2026-07-18 General 에서 이동)), `ServerController`(`/servers`, `/servers/{id}`, `/servers/logs`), `AiChatController`(`/ai-chat` 세션 기반), `LeakRuleAdminController`(`/admin/leak-rules` ADMIN CRUD), `ComparisonHistoryController`(`/comparison-history`)
- `GlobalExceptionHandler` — `IllegalArgumentException` → JSON 400 (`/api/`/AJAX) or HTML 302 redirect (`?error=invalidFilename`)

**감사 로깅 컨벤션:** 관리/변경 작업(룰 CRUD, 서버 CRUD 등)은 SLF4J 로 `[Domain] action=create|update|delete ... by={who(auth)}` 구조 로깅. `who(Authentication)` = `auth!=null ? auth.getName() : "unknown"` (각 컨트롤러 static 헬퍼). update 는 before→after diff + `fields=[변경키]`, delete 는 삭제 **전** 식별정보 캡처. `LeakRuleAdminController`(`[LeakRule]`) / `ServerController`(`[Server]`) 가 레퍼런스. **새 mutation 엔드포인트 추가 시 동일 패턴 적용.** (별도 DB 감사 테이블 아님 — 앱 로그 `logs/heapdump-analyzer.log`.)

**DTOs (`model/dto/` 패키지, Phase 4B-2):** `AnalysisHistoryItem` / `DailyDetection` / `ServerSeries` / `DetectionSummaryItem` / `DetectionAggregate` / `DetectionDayFile` / `DetectionRecentItem` / `ClassDiff` / `HistogramDiff` / `SuspectDiff` / `KpiDiff` / `MemoHistoryItem` — 12 DTO. 이전엔 `HeapDumpController` inner static class 였음.

**Services (Phase 4A 종합 추출, 2026-05-12):**
- `HeapDumpAnalyzerService` (3,951 라인) — 분석 + **모든 런타임 설정 영속화 단일 책임**: settings.json ↔ application.properties 동기화. ⚠ 2026-08-02 부터 **LLM/RAG/AiInsight getter facade 는 제거됨** — 조회는 `LlmConfigService`/`RagConfigService`/`AiInsightManager` 직접 주입. **setter facade 는 유지**(위임 후 `persistSettings()` 부수효과) — 설정 변경은 반드시 이 setter 경유.
- `LlmConfigService` (1,470 라인) — LLM 12 필드 + 17 getter/setter + 4 호출 메서드 (`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`/`testLlmConnection`) + `disableSslVerification` + `GENSPARK_MODELS`. API 키는 `SecretValue`, 호출 메서드 5곳은 호출량 게이트 wrapper.
- `LlmRateLimitService` (366 라인) — LLM 호출량 제한 4 축(초당/분당/일일/동시) + `Lease`(AutoCloseable) + `toResponse()` 429 매핑 + 3-hook 영속화
- `RagConfigService` — RAG **41 필드**(Chroma 10 포함) + getter + **6 그룹 setter** + AES 암호화(시크릿 **4종**: password/apiKey/embeddingApiKey/**chromaToken**)
- `ChromaSearchService` (2026-08-29) — Chroma v2 REST + 응답 정규화. `normalizeHits`/`toScore` 는 테스트용 static 순수 함수
- `RagKnowledgeExportService` (2026-08-29) — 코드 상수(`OomDetector.OomKind`/`MatErrorHint`/`MiddlewareDetector.Vendor`/코어덤프 지식 33건)를 RAG 색인 문서로. `GET /api/admin/rag/knowledge-export` + CLI 진입점(`-Dloader.main=...RagKnowledgeExportService`)
- `FileManagementService` — 업로드/중복검사/디스크 I/O/gzip
- `HeapHistoryAggregator` (585 라인, Phase 4B-2) — `buildHistory` / `aggregateDetections` / `build*Diffs` / `buildKpiDiff` / `buildAnalysisName` / `truncateLog` / `formatDuration` 등 공유 헬퍼. View/API 컨트롤러 7개에 주입.
- `HeapAnalysisResultCache` — `ConcurrentHashMap` 캐시 + 7 facade 메서드
- `AiInsightManager` — `saveAiInsight`/`loadAiInsight`/`deleteAiInsight`/`migrateAiInsightsToDb`
- `RagService`(ES 검색 + 청킹 + LLM 컨텍스트 주입), `EmbeddingService`(semantic-client 전용), `RemoteDumpService`(SSH/SCP), `UserService`/`CustomUserDetailsService`, `LeakRuleService`, `ComparisonHistoryService`, `MemoHistoryService`(개인 메모 스냅샷 — 보관 정책 3중, 함정 34)

**Utils:**
- `AuthUtil.isAdmin(Authentication)` — static, 모든 컨트롤러 공용
- `FilenameValidator.validate(name)` — null/empty/../slash/null byte 차단 + 확장자 whitelist
- `FormatUtils.formatBytes(long)` — 통일 포맷 (controller/service/model 모두 위임)
- `HtmlSanitizer` — OWASP Java HTML Sanitizer wrapper
- `AesEncryptor` — AES-256-CBC HEX
- `MiddlewareDetector.detect(histogram, threads, sysProps)` — WAS/DB 벤더 추정 (sysprop 마커 가중치 100 권위적 확정 → 이름 best-effort). `matchCount` 는 대표 벤더 선택용 내부 점수(UI 미노출). analyze Overview 배지에 사용.
- `MatErrorHint` — MAT 실패 출력 → 한국어 안내 5종. `extractMatErrorHint()` 와 RAG 지식 색인이 **같은 상수**를 본다(화면 문구와 답변 용어 일치). ⚠ 선언 순서 = 매칭 우선순위
- `OomDetector.classifyMessage(msg)` — OOM 메시지 → 한국어 라벨/원인/권장. analyze Overview 진단 카드 + AI 컨텍스트 주입에 사용.

**Listener:** `AuthEventListener` — 로그인 이력 기록 (아래 함정 참조).

**Parser:** `MatReportParser` — Overview/Top Components/Suspects ZIP 다단계 추출. `sanitizeHtml()`/`extractBodyContent()` 두 곳에 존재 (parser, service) — 동기화 유지 필수. Suspects 섹션 추출은 **차트 이미지맵(`<map>…</map>`) 제거본**을 대상으로 하고(`MAP_BLOCK_PATTERN`), 등록 전 `isRealSuspectSection()` 로 검증한다 (함정 28). 상한은 `MAX_SUSPECTS`(20).

**External:** `/opt/mat/ParseHeapDump.sh` (suspects/overview/top_components 리포트, 30분 타임아웃).

## Frontend

Thymeleaf + vanilla JS + Chart.js. 빌드 도구 없음.

**공통 인프라 (Phase 5A/5B/5C — common.* 통합):**
- `/css/common.css` — reset / body base / `.topbar*` / `.modal-ov` + `@keyframes modalIn` + `.modal-box` base / btn 색상 utility 3 그룹 (cancel `.mbtn-cancel/.btn-cancel/.sa-btn-cancel` · danger `.mbtn-del/.mbtn-danger/.btn-delete/.sa-btn-del` · primary `.mbtn-save/.mbtn-primary/.mbtn-confirm/.btn-download`) / 데이터 테이블 4 family base (`.htable/.ftable/.stable/.utable` + sortable) — 80+ 라인 공통. 페이지별 변형(opacity 변형, padding/font-size, min-width 등)은 인라인 cascade override.
- `/js/common.js` — `window.Common` 네임스페이스: `escHtml(s)` (5문자 escape) / `csrfToken()` / `csrfHeaderName()` / `fetchJSON(url, opts)` (자동 CSRF/Content-Type, non-2xx throw, JSON 자동 파싱) / `appendCsrfToForm(form)` / `formatBytes(bytes)` / `toast(msg, type)` (settings 계열 — CSS 는 common.css `.toast/.toast-success/.toast-error`. ⚠ `.toast` 는 `top:70px` **고정 좌표**라 동시 표시 시 포개진다 — `restackToasts()` 가 `.toast-stack` 마커를 세어 세로로 쌓고 **생성·제거 양쪽**에서 재배치한다. `#toast` 고정 엘리먼트 계열과 섞이지 않도록 마커로 구분. 회귀 방어 `CommonToastStackTest`(4)) / `showToast(msg, type)` (`#toast` 고정 엘리먼트 계열 — servers/server-detail/admin-users). `banner.html`에서 1회 로드 → 14 페이지 자동 가용.
- **페이지별 로드 공통 모듈 (banner 전역 아님, 2026-08-02):** `/js/table-grid.js`(데이터 그리드 엔진) · `/js/select-mode.js`(다중 선택 모드) · `/js/float-tooltip.js`(`[data-tooltip]` **커서 추종형** 툴팁 — servers/server-detail) · `/js/krds-tooltip.js`(`[data-tip]` **앵커드 팝오버** 툴팁, KRDS component_08_05 — history/settings) · `/js/session-timeout.js`(세션 유휴 타이머 — **배너에서 전역 로드**, 폴링 게이트·경고 모달·자동 로그아웃, 함정 38) · `/js/memo.js`(개인 메모장 — 저장/자동저장/창간 동기화 + **미저장 백업 뷰어**(`Memo.openBackupViewer`) + **복구 되돌리기**(`saveUndo`/`readUndo`/`clearUndo`) + **서버 변경 이력 뷰어**(`Memo.openHistoryViewer`) — 모달은 CSS·DOM 자체 주입 싱글턴, account·account-memo 공유). 소비 페이지의 `<script>` 태그로만 로드.
- **툴팁은 2종 — 새로 붙일 때 성격에 맞는 쪽을 고를 것.** ① `float-tooltip.js`(`data-tooltip`): 커서를 따라다니고 최대 360px, hover 전용. 표 안 배지처럼 트리거가 작고 많은 곳에 적합. ② `krds-tooltip.js`(`data-tip`): 트리거 위 중앙에 붙고 공간 부족 시 아래로 뒤집히며 화살표가 트리거를 가리킴, 280px, hover+**키보드 focus**+터치 탭 토글+Esc/바깥클릭 닫기+`role="tooltip"`/`aria-describedby`. 설명이 길거나 접근성이 필요한 설정 항목용. **두 모듈을 한 페이지에 같이 싣지 말 것**(트리거 속성이 달라 동작은 하지만 디자인이 섞인다). krds 판은 `white-space:pre-wrap` 이라 문단 구분 `&#10;` 이 그대로 살고, CSS·싱글턴 DOM 을 스스로 주입하므로 페이지는 `<script>` 한 줄만 넣으면 된다. 키보드 접근이 필요한 트리거에는 `tabindex="0"` 필수.
- 캐시 무효화: `?v=YYYY-MM-DD[a-z]` 쿼리 파라미터. 모든 페이지 일괄 갱신.

**My Account 레이아웃 전환 (2026-08-12):** `/account` 제목 라인 우측 세그먼트로 **기본**(계정 정보 위 / 메모장 아래) ↔ **좌우 분할**(메모장 좌 / 계정 정보 우 360px, 컨테이너 1400px)을 고른다. 분할에서는 우측 카드가 짧아 좌측 아래가 비므로 입력 영역을 **`min-height: max(360px, calc(100vh - 400px))`** 로 화면 높이에 맞춰 늘린다(400 = topbar/제목/카드헤더/폰트바/메타/버튼행/여백 합 — 저장 버튼이 뷰포트 밖으로 나가지 않는 값). 선택은 **`users.account_layout` 컬럼에 계정별 저장**(memoFont 와 동일 정책, `POST /api/account/layout`, `stack|split` 화이트리스트 — 값이 그대로 CSS 클래스가 되므로 검증 필수)이라 재로그인·다른 기기에서도 유지되고, **초기 적용은 `<html th:classappend>` 서버 렌더**라 FOUC 스크립트가 필요 없다. DOM 순서는 계정→메모 그대로 두고 **`order` 로만 좌우를 바꿔** 탭 순서·낭독 순서를 보존하며, ≤1100px 에서는 grid 를 해제해 자동으로 1단이 된다(선택값은 유지). 기본 상태에서 `.acct-grid` 는 아무 CSS 도 걸리지 않는 블록이라 종전 렌더와 동일. ⚠ `max-width` 는 padding(배너 240 + 20)을 포함하므로 실제 콘텐츠 폭은 260 을 뺀 값이다.

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

**설정 화면 액션 버튼 치수 규약 (2026-08-30):** `/settings/rag`·`/settings/llm` 의 `.btn-primary/.btn-secondary/.btn-success` 는 **형태(치수) 규칙을 한 선언에 모으고 색만 개별**로 둔다. 두 가지가 조용히 높이를 어긋내기 때문이다 — ① `border` 를 `.btn-secondary` 에만 주면 그 버튼만 2px 높다 ② `line-height` 미지정이면 **라벨에 한글이 섞인 버튼**이 fallback 폰트의 큰 행상자를 그대로 높이로 가져가 4px 더 높다(실측 31/35/33px). 그래서 채움 버튼에도 `border: 1px solid transparent`, 3종 모두 `line-height: 18px` + `font-family: inherit`(button 은 폰트를 상속하지 않아 기본이 Arial). 액션 바의 확대 치수는 인라인 style 이 아니라 `.btn-act` 한 곳에 둔다 — 인라인으로 흩으면 다시 갈라진다. 라벨이 런타임에 바뀌는 버튼(모드에 따라 `Test Connection (Elasticsearch)` ↔ `(Chroma + 임베딩)`)이 있으므로 **높이는 라벨 길이·문자셋과 무관해야 한다.** 회귀 방어 `RagSettingsTemplateSmokeTest`/`LlmSettingsTemplateSmokeTest` 각 1건.

**Topbar 줄바꿈 방지 규약:** 페이지 topbar 가 `position: fixed` + `display: flex; justify-content: space-between` 구조에서 햄버거 + 타이틀이 우측 버튼 폭 압력으로 줄바꿈되는 사례 다수. `.topbar-brand` 에 명시적 `display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1 1 auto` + `.topbar-title` 에 `white-space: nowrap; overflow: hidden; text-overflow: ellipsis` + `.topbar-right { flex-shrink: 0 }` 패턴 필수. 새 페이지 작성 시 `files.html` / `history.html` 패턴 그대로 복사.

## LLM / AI

**Multi-provider:** Claude (Messages API) / GPT (Chat Completions) / Genspark / Custom (OpenAI 호환). Provider별 헤더/본문 분기. 모든 설정은 `volatile` 필드 + settings.json 영속화. 구현은 `LlmConfigService`.

**3가지 호출 방식:** `callLlmAnalysis(prompt)` 원샷 JSON / `callLlmChat(messages, systemPrompt)` 멀티턴 / `callLlmChatStream(...)` SSE (Claude `content_block_delta`, OpenAI `choices.delta.content` 파싱).

**API 키는 `ENC(...)` 암호화 (2026-08-12):** `llmApiKey` 는 `SecretValue` + `putSecret()` — RAG 3종과 동일 규약(값 미변경 시 재암호화 안 함 / 손상값은 `usable()` 이 차단 + 마스킹 `손상됨` / 암호화 실패 시 키 생략). 레거시 평문은 기동 시 `adopt()` 가 감지(`isLlmApiKeyUnsealed()`) → `loadPersistedSettings()` 가 **복원 완전 시에만** `persistSettings()` 로 1회 봉인. 소비는 `usableApiKey()` 로 하고 미설정/손상 안내는 `apiKeyErrorMessage()` 가 구분한다.

**호출량 제한 (`LlmRateLimitService`, 2026-08-12):** 사용자별 **초당/분당/일일/동시** 4 축, 각 **0 = 무제한**. 기본 `2/20/500/3`. 설정은 `/settings/llm` > Rate Limit 카드 (`POST /api/llm/ratelimit`, ADMIN+CSRF) 이고 영속화는 LLM/RAG/2FA 와 동일 3-hook + `setLlmRateLimit()` facade.
- **게이트는 `LlmConfigService` 의 호출 메서드 5곳이 유일한 초크포인트** — 컨트롤러가 아니라 여기 둔 이유는 새 엔드포인트 추가 시 누락을 막기 위해서다. **새 LLM 호출 메서드를 만들면 반드시 `acquireGate(scope)` 로 감싸고 `Lease` 를 `finally` 에서 close 할 것**(동시 슬롯이 거기서만 반납된다). 공개 메서드는 wrapper, 본문은 `doXxx()` private.
- Vision overload 는 **이미지 경로에만** 게이트(위임 분기는 base 가 이미 건다 — 중복 차감 방지).
- 거부 코드 3종 `LLM_RATE_LIMIT`/`LLM_DAILY_LIMIT`/`LLM_CONCURRENT_LIMIT`. JSON 엔드포인트는 `LlmRateLimitService.toResponse()` 로 **429 + `Retry-After`**, SSE 는 `error` 이벤트. `LLM_DISABLED`/`NO_API_KEY` 등 기존 코드는 **200 유지**(상태 코드를 바꾸면 프런트 분기가 깨진다). 프런트는 `Common.fetchJSON` 이 `err.rateLimited`/`err.retryAfterSeconds` 로 승격.

**채팅 흐름 (`analyze.html` 플로팅):** `ensureChatSession()` → `POST /api/ai-chat/sessions` → `doStreamRequest()` → `POST /api/ai-chat/sessions/{id}/stream`. user 메시지는 스트리밍 시작 전 동기 저장, assistant는 `onDone`에서 3회 재시도(500ms) 저장. `done` SSE에 `saved` 필드.

**SSL 검증 토글 (`llm.ssl.verify`):** 기본 true. 사내 사설 CA 로 발급된 TLS 게이트웨이 호출 시 JVM 번들 cacerts 신뢰 못해 PKIX 에러 가능. 두 해결 경로 모두 지원:
- 운영: `restart.sh` 가 `/opt/genspark/webapp_dump/certs/heap-truststore.jks` 존재 시 `-Djavax.net.ssl.trustStore*` 3종 자동 부착. 미존재 시 JDK 기본 cacerts 사용.
- 우회: `/settings/llm` 토글 OFF → 4개 LLM 메서드(`testLlmConnection`/`callLlmAnalysis`/`callLlmChat`/`callLlmChatStream`) 가 `HttpsURLConnection` 캐스트 후 `disableSslVerification` 분기 진입.

RAG `ragSslVerify` 와 **1:1 미러링 패턴** (`volatile` 필드 / 생성자 init / settings.json 로드·저장 / `syncApplicationProperties()` 키 / getter·setter / `openConnection()` 직후 분기). 새 외부 HTTP 호출 추가 시 동일 패턴 따를 것.

## RAG (Elasticsearch)

**4개 검색 모드** (`RAG_PHASE2_PLAN.md` 참조). 모드 목록의 단일 출처는 `RagConfigService.AVAILABLE_MODES` — 컨트롤러 `availableModes` 가 이걸 그대로 노출한다:
- `keyword` — BM25 `match`
- `semantic-server` — `text_expansion`(ELSER) 또는 `semantic`(semantic_text)
- `semantic-client` — 앱이 임베딩 호출 후 `knn` (provider: openai/cohere/custom)
- **`chroma`** (2026-08-29) — 로컬 Chroma 벡터 DB. `RagService.search()` 진입부에서 `ChromaSearchService` 로 분기하고 기존 ES 본문은 `searchElasticsearch()` 로 rename. `fetchContextForLlm(String)→String` 계약과 호출처 3곳은 무변경이라 **`rag.search.mode=keyword` 한 줄로 즉시 롤백**된다. **설정 화면(`/settings/rag`)은 모드 중심 구조**다(2026-08-29 2차 개편) — 최상단 전폭 `검색 모드` 카드(라디오 4종 + 공용 Top-K/Min Score, 값 보관은 숨긴 `<select id="ragMode">` 가 계속 담당해 `buildConnPayload` 계약을 유지)가 나머지 카드의 표시를 결정하고, **모드에 맞는 백엔드만 보인다**(chroma → ES 연결·인증·Semantic-server 숨김 / ES 계열 → Chroma 상태·설정 숨김). 가시성은 `_show()` 한 곳 경유. ES 모드에서도 모드 카드 하단 한 줄 요약 + `Chroma 설정 보기` 미리보기 토글로 발견성을 남긴다. 백엔드 카드는 `.rag-cols` 안에 있고 **≥1400px 에서만 flex-wrap 2열**(grid 아님 — 카드가 빠질 때 남은 장이 전폭을 쓰도록), 컨테이너 `max-width` 1500px. 4.9화면 → 2.3~2.9화면. 연동 상태 패널은 `GET /api/settings/rag/chroma/status`(`ChromaSearchService.integrationStatus()`, 저장 설정 기준, 프로브 `min(설정,5s)`, 항상 200)로 서버 버전·컬렉션 UUID·청크 수·hnsw space·차원·사이드카 health 를 보이고 `integrationWarnings()`(순수 함수)가 차원/space/provider/count 정합성을 경고한다 — **주기 폴링 없음**(로드 1회 + 새로고침 + 저장 후 1회, 함정 38). chroma 모드의 하단 Test Connection 은 클라이언트에서 `/chroma/test`+`/embedding/test` 로 라우팅한다(서버 병합 금지 — ES 병행 운용). local-onnx 기본값(`http://127.0.0.1:8001/embed`, 384)의 단일 출처는 `EmbeddingService.LOCAL_ONNX_DEFAULT_URL/DIMENSION` 이고 `GET /api/settings/rag` 의 `embedding.localOnnxDefaultUrl/localOnnxDimension` 로 UI 에 전달된다(JS 리터럴 금지).

**검색 품질은 눈대중하지 말 것** — 평가셋 `rag-data/eval/queries.jsonl`(42건)과 `/opt/chroma/app/evaluate.py` 로 Recall@k·MRR 을 잰다. 기준선은 `rag-data/eval/BASELINE.md`(2026-08-29: **k=10 에서 0.881**). 모델·청킹·색인 파이프라인을 바꾼 뒤 `evaluate.py --k 10 --min-recall 0.85` 로 회귀를 막는다. ⚠ **`rag.search.top-k` 권장값은 10**(기본 3) — 벡터 검색은 상위 점수 차가 0.01 안팎이라 k 가 클수록 유리하고, 청크가 350자라 10건이어도 3,500자다. ⚠ **Recall@1(0.476)을 품질 저하로 읽지 말 것**: e5 점수가 0.86~0.91 에 몰려 1위 자리는 종이 한 장 차이이고, RAG 는 상위 k 건을 넣으므로 Recall@k 가 실질 지표다.

**Chroma 스택 (`/opt/chroma`, 앱 외부 · systemd):** `chroma.service`(:8000, `MemoryMax=400M`) + `chroma-embed.service`(:8001, ONNX int8 384차원, `MemoryMax=700M`). 설정 단일 지점 `env.sh`, 버전 핀 `requirements.lock`. 색인은 앱이 아니라 `/opt/chroma/app/run-index.sh --sources all` 이 담당(현재 332청크). 지식 소스·마스킹은 `app/sources.py`, 임베딩 접두사 규칙은 `app/embedder.py` 에 가둬 둔다. ⚠ `chroma vacuum --path`(최상위 명령, `utils` 아님)를 주기적으로 돌릴 것 — WAL 이 쌓여 실측 23% 가 회수된다.

semantic 설정 누락 시 keyword 폴백 없이 명확한 에러 (디버깅 용이).

**LLM 통합:** `RagService.fetchContextForLlm(query)`가 3곳에 주입 — `AiChatController.streamChat()`, `HeapAiApiController.aiChat()`, `HeapAiApiController.aiChatStream()`. systemPrompt 끝에 `[참고 자료 (RAG)]\n--- 자료 N ---\n...` 추가. 비활성/실패 시 빈 문자열 (호출자 안전).

**청킹 (post-retrieval):** `chunkText(text, strategy, size, overlap, maxChunks)` — fixed/paragraph/sentence. `maxTotalChars` 한도 내 주입. 첫 청크가 한도 초과해도 잘라서라도 일부 주입.

**인증:** none / Basic / ApiKey. password/apiKey는 `AesEncryptor.encrypt()`로 `ENC(...)` 암호화 후 settings.json + application.properties 저장. **POST `/api/settings/rag`에서 password/apiKey가 키 없거나 null이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 갱신** — UI는 마스킹 placeholder만 노출.

## Authentication & Security

Spring Security **6.5** 세션. `/login` 공개, `/admin/**` + `/api/admin/**` ADMIN 전용. **CSRF 보호 유지 (면제하지 않음)**: `/api/admin/**`, `/api/settings/**`, `/api/llm/{enabled,config,apikey,test-connection,chat-prompt,chat-restore-mode,file-attach,ratelimit}`, `/api/servers/{scan-interval,ssh-local-user}` — 모두 `authorizeHttpRequests(auth -> auth.requestMatchers(...).hasRole("ADMIN"))` 매처와 1:1 미러링. 그 외 `/api/**` 는 CSRF 면제 (인증은 유지). **새 ADMIN mutation 추가 시 SecurityConfig 두 곳 (authorize + csrf ignore) 동시 갱신 필수**. `SecurityConfig` 는 lambda DSL + `@EnableMethodSecurity` (`@PreAuthorize` 지원) 사용 — `.and()` chain / `@EnableGlobalMethodSecurity` / `antMatchers` 미사용.

**Spring Session JDBC**: `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` 자동 생성. 무동작 만료 60분, cleanup cron 10분. 앱 재시작에도 로그인 유지.

**세션 만료 응답 규약 (2026-08-06):** `/api/**` 미인증 → **401 JSON** `{"success":false,"code":"SESSION_EXPIRED","error":"..."}`, 비-API 경로 → 종전대로 `/login` 302. `SecurityConfig.exceptionHandling` 에 `defaultAuthenticationEntryPointFor` **두 개**(`/api/**` JSON · 나머지 `LoginUrlAuthenticationEntryPoint`)를 등록해 구현. `accessDeniedHandler` 도 `/api/**` 면 JSON(CSRF 소실/불일치 → 401 SESSION_EXPIRED, 그 외 403). `Common.fetchJSON` 은 401 에 `err.sessionExpired = true` + 한국어 메시지를 붙이므로 페이지 JS 는 `e.sessionExpired` 로 분기하면 된다. **재로그인 후에는 세션이 바뀌어 CSRF 토큰도 새로 발급**되므로 열린 페이지의 `<meta name="_csrf">` 를 `GET /api/csrf` 로 갱신해야 이후 POST 가 통과한다(리로드 대안). **무동작 만료 자체는 `/js/session-timeout.js` 가 클라이언트에서 판정한다**(함정 38). 작성 중 데이터가 있는 화면은 강제 리다이렉트 대신 **백업 + 재로그인 유도**로 처리할 것 — 메모장(`memo.js` 의 `backup`/`readBackup`/`refreshCsrf`, autosaver `suspend`/`resume`)이 레퍼런스.

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

31. **`new Thread(...)` 는 SecurityContext 를 전파하지 않는다 — 사용자별 정책이 전원 한 버킷으로 뭉개진다** — SSE 스트리밍 두 곳(`HeapAiApiController.aiChatStream`, `AiChatController.streamChat`)은 요청 스레드에서 SseEmitter 를 만들고 **별도 스레드**에서 LLM 을 호출한다. `SecurityContextHolder` 는 기본이 `MODE_THREADLOCAL` 이라 그 스레드에서는 `getAuthentication()` 이 null → 호출량 게이트가 전원을 `"system"` 버킷에 넣어 **사용자별 제한이 사실상 전역 제한**이 된다(에러가 안 나고 조용히 잘못 동작하는 부류). 해결은 `new Thread(new DelegatingSecurityContextRunnable(() -> {...}), name)` — 생성 시점(=요청 스레드)의 컨텍스트를 캡처해 실행 스레드에 심는다. **SSE/비동기 스레드에서 인증 주체가 필요한 로직(감사 로깅·쿼터·소유권 검사)을 돌릴 때는 항상 이 래퍼를 쓸 것.** 게이트를 서비스 층에 두면 컨트롤러가 이 사실을 잊기 쉬우므로 `LlmConfigService` 클래스 주석에도 경고를 박아뒀다.

32. **슬라이딩 윈도우 한도는 "부수효과 있는 검사"를 마지막에 — 순서를 바꾸면 거부된 요청이 남의 할당량을 먹는다** — `RateLimiter.isAllowed()` 는 통과 시 타임스탬프를 **기록**한다. 그래서 여러 축(초당/분당/일일)을 순차 검사할 때 기록형 검사를 먼저 돌리면, 뒤 축에서 거부된 요청이 앞 축의 카운트를 이미 소모한 상태가 된다. `LlmRateLimitService.acquire()` 는 ① 동시(슬롯) ② **일일(읽기 전용 peek)** ③ 초당/분당(기록) ④ 통과 확정 후 일일 카운터 증가 순서다. 같은 이유로 **거부 시 앞서 잡은 동시 슬롯을 반드시 반납**해야 하는데, 동시 한도가 0(무제한)이면 애초에 잡지 않았으므로 반납하면 카운터가 음수가 된다 — 실제 점유 여부를 `heldSlot` 지역변수로 들고 다닌다. 슬롯이 누수되면 이후 **모든** 호출이 영구히 `LLM_CONCURRENT_LIMIT` 이 되므로 회귀 테스트(`LlmRateLimitServiceTest` 15건)를 반드시 유지할 것. 한도가 런타임에 바뀌는 값이라 동시 카운터는 `Semaphore`(permit 고정) 대신 **CAS `AtomicInteger`**, 윈도우 2종은 `applyFromSettings`/setter 에서 **재생성**한다(안 하면 복원 후 옛 한도가 남는다).

33. **브라우저에서 만든 시각을 서버 `LocalDateTime` 용 포맷터로 표시하면 9시간 어긋난다** — `Memo.formatTs()` 는 서버가 주는 ISO 문자열(로컬 시각)의 **앞부분을 자르는** 함수다. 그런데 `Memo.backup()` 은 `new Date().toISOString()`(**UTC**)로 기록했고 같은 포맷터로 표시해, "저장되지 못한 메모가 남아 있습니다 (2026-08-06 **05:15**)" 가 실제로는 14:15 인 사태가 있었다(2026-08-12 수정). 바로 옆에 서버발 "저장 시각"(로컬)이 함께 놓이는 화면이라 **어느 쪽이 최신인지 오판**하게 만든다 — 예외도 로그도 없이 조용히 틀리는 부류. **클라이언트가 시각을 만들어 서버 시각과 나란히 보여줄 때는 `Memo.localTs()` 처럼 로컬 기준으로 기록**하고, 이미 저장된 UTC 값은 `Memo.formatBackupTs()` 처럼 오프셋 유무를 보고 환산할 것. `toISOString()` 을 표시용 문자열로 쓰지 말 것.

34. **메모 이력의 보관량은 3중으로 막아야 한다 — 자동 저장이 5초 debounce 다** — `users.memo` 는 덮어쓰기 단일 컬럼이라 2026-08-12 에 `memo_history`(덮이기 직전 스냅샷)를 신설했다. 여기서 진짜 위험은 기능이 아니라 **양**이다: 메모장 자동 저장은 마지막 입력 후 5초에 1회 저장하므로 계속 타이핑하면 분당 10회 넘게 저장되고, 컬럼은 최대 10MB — 매 저장마다 행을 만들면 한 사용자가 며칠 만에 수 GB 를 만든다. `MemoHistoryService` 가 ① **최소 간격**(`min-interval-seconds`, 기본 60초) ② **사용자당 상한**(`max-per-user`, 기본 100, 초과분 즉시 축출) ③ **보관 기간**(`retention-days`, 기본 7일, 매일 03:30 정리) + 동일 내용·빈 내용 억제로 막는다. **단 복원/초기화 직전 스냅샷은 간격 제한 예외**(되돌리기 수요가 확실한 시점이라 여기서 억제하면 정작 필요한 걸 잃는다). 억제 판정은 전부 **읽기 전용**이고 INSERT 는 통과 확정 후에만 — 순서를 바꾸면 거부된 저장이 할당량을 갉아먹는다(함정 32 와 같은 계열). ⚠ 목록 조회는 `SUBSTRING` 투영으로 **본문을 빼고** 가져올 것(10MB LOB 를 목록마다 끌고 오면 안 된다). ⚠ 소유권 검증은 `findByIdAndUsername` 처럼 **쿼리에 넣을 것** — id 로 찾은 뒤 비교하는 방식은 한 곳만 빠뜨려도 개인 메모가 새어나간다. ⚠ 의존은 `UserService` → `MemoHistoryService` **단방향** 유지(복원 실행은 UserService, 이력 조회는 MemoHistoryService — 반대로 부르면 순환). 클라이언트 측 안전망(`memoBackup:*` 미저장 백업, `memoUndo:*` 복구 되돌리기)은 여전히 **브라우저 localStorage 전용**이라 서버·admin 은 접근할 수 없다.

35. **charset 을 명시하지 않은 텍스트 응답은 프록시가 라벨을 채워 넣어 한글을 깨뜨린다 — 페이지는 멀쩡하고 JS 만 깨진다** — Spring 의 `ResourceHttpRequestHandler` 는 확장자 MIME 을 `ServletContext#getMimeType()` 에서 **먼저** 찾고 Tomcat 기본 매핑은 `text/javascript` 처럼 **charset 파라미터가 없다**. 라벨이 없으면 브라우저가 HTML 문서 인코딩(UTF-8)을 스크립트에 상속시켜 직접 접속은 정상이지만, **HTTP charset 은 문서 상속보다 우선**하므로 중간 프록시가 `charset=ISO-8859-1` 을 채워 넣으면 그 값이 이겨 JS 안의 **한글 문자열 리터럴 전부**가 windows-1252 로 디코딩된다(2026-08-13 사내망 실제 제보: 업로드 모달 "업로드 준비 완료" → "ì—…ë¡œë“œ ì¤€ë¹„ ì™„ë£Œ"). Thymeleaf 페이지는 `text/html;charset=UTF-8` 을 명시하므로 **본문은 정상인데 JS 가 만든 텍스트만 깨지는 비대칭**이 나타나 원인을 파일·DB·업로드 쪽으로 오해하기 쉽다. 대응은 `StaticResourceCharsetConfig` — `addMimeMappings`(⚠ `setMimeMappings` 는 Tomcat 기본값을 통째로 교체해 woff2 등을 잃는다)로 js/mjs/css/svg/html/htm/txt 에 `;charset=UTF-8` 명시. **새 텍스트 확장자를 정적 리소스로 추가하면 여기에 등록할 것.** `ResponseEntity.contentType()` 으로 직접 지정하는 경로(예: `HeapReportApiController.guessMediaType()`)는 `StringHttpMessageConverter` 의 기본 charset 보정을 받지 못하므로 **호출부에서 직접 붙여야 한다**. 반대로 `produces` 만 선언하고 String 을 반환하면 자동으로 붙는다. `json`(RFC 8259)·SSE(`text/event-stream`)·`fetch().json()/.text()` 는 표준이 UTF-8 을 강제하므로 대상 아님. 깨진 문자열의 디코더 특정은 `s.encode('utf-8').decode(enc)` 를 후보 인코딩으로 돌려 제보 문자열과 대조하면 확정적이다(`—`/`“`/`€` 가 보이면 latin-1 이 아니라 **windows-1252**). 회귀 방어 `StaticResourceCharsetConfigTest`(4).

36. **이탈 경고 팝업(beforeunload) '취소' 는 사후 신호가 없다 — 스피너는 예약 전에 막아야 한다** — 배너의 페이지 로딩 스피너는 링크 클릭 시 `schedulePageLoading()` 으로 100ms 타이머를 예약한다. 이탈 경고 팝업이 뜨면 렌더러가 멈춰(탭-모달) 타이머가 밀렸다가 **팝업이 닫히는 순간 발화**하는데, 사용자가 '취소'를 골랐다면 네비게이션이 없으니 스피너를 내려 줄 코드가 영영 안 돈다 → 무한 회전. 2026-05-30 에 `window.focus`/`visibilitychange` 로 잡으려 했으나 **두 이벤트 모두 발화하지 않는다**(Chrome 128 CDP 실측 0건) — 무효 수정이었고 2026-08-19 메모장 제보로 드러났다. 취소와 이동은 렌더러 재개·타이머 발화·JS 실행이 전부 동일해 **사후 판별이 불가능**하므로, 경고를 띄울 페이지가 `registerUnloadGuard(fn)` 로 조건을 미리 등록하고 배너가 예약 자체를 건너뛴다. **새로 `beforeunload` 경고를 붙이는 페이지는 반드시 같은 조건을 가드로 등록할 것**(등록 조건과 핸들러 조건은 한 함수로 공유 — `account.html` 의 `memoWillWarnOnLeave` 가 레퍼런스). capture 단계에서 클릭을 가로채 `preventDefault()` 하는 페이지(analyze/compare)는 링크 경로만 안전하고 **form submit 경로는 여전히 새므로** 가드가 필요하다. 2차 방어로 키 입력·마우스 다운 시 제거 + 30초 표시 상한이 있다. 회귀 방어 `AccountMemoTemplateSmokeTest.unloadGuardIsWiredToBannerSpinner`.

37. **코어덤프 별도 서버 분석 — "같은 경로에 다른 버전 라이브러리"가 "없는 것"보다 위험하다** — GDB 는 코어에 기록된 `.so` 절대경로를 **분석 서버 로컬 파일시스템**에서 열어 심볼을 해석한다. 경로가 없으면 `??`+경고(정직한 실패)지만, 같은 경로에 다른 빌드가 있으면 gdb 8.2 는 build-id 검증 없이 **경고 0건으로 가짜 함수명·인자값·소스라인**을 출력한다(`Syms Read=Yes` 라 신뢰돼 보임 — 통제 실험 실증은 `COREDUMP_SYMBOL_ACCURACY_VERIFICATION.md`). 환경변수는 코어 안에 있어 GDB 실행 env 와 무관(불필요). 대응은 **sysroot 번들**: `{coredump.directory}/sysroots/{core}/` 에 원본 서버 라이브러리를 수집(원격 `collect-libs` tar 스트리밍 / 수동 업로드)하면 `buildGdbCommand()` 가 `-iex "set sysroot"` + `-iex "set solib-search-path"` 를 부착한다. ⚠ 반드시 **`-iex`** — positional `<exec> <core>` 는 `-ex` 보다 먼저 로드돼 `-ex` 로 주면 초기 solib 해석이 로컬 경로로 한 번 수행된다. ⚠ **두 옵션은 세트다**: sysroot 는 "sysroot+코어에 기록된 절대경로"가 실존할 때만 맞으므로 원경로를 모르는 개별 `.so`·구조 없는 아카이브는 `solib-search-path`(번들 내 파일 보유 디렉토리 목록)가 basename 으로 찾아준다. 반대로 **`solib-search-path` 단독은 무효** — sysroot 없이 주면 gdb 가 원경로 파일을 먼저 찾는다(케이스 F/F-2). ⚠ sysroot 설정 시 번들에 없는 라이브러리는 **호스트 폴백이 사라져 `No`** 가 되므로 코어가 참조하는 전체 `.so`+ld.so 를 수집해야 한다. ⚠ 코어 단독(exec 미페어링)은 링크맵 접근 불가라 sysroot 도 무력 — exec 페어링이 전제조건. **업로드는 형식 무관**(tar/tar.gz/tgz/tar.bz2/tar.xz/zip + 개별 파일, 다중 선택, 매직 바이트 판정, 기존 번들에 병합) — 확장자 검사로 되돌리지 말 것. ⚠ 해제 시 **`TarArchiveEntry.isFile()` 은 symlink 에도 true**, **zip 의 `isUnixSymlink()` 는 `ZipArchiveInputStream`(스트리밍)에선 항상 false**(external attributes 가 중앙 디렉토리에만 있음 → `ZipFile` 사용). 수집 경로는 코어의 gdb 출력 유래(신뢰 불가)라 화이트리스트 정규식 통과분만 원격 tar 인자로 쓴다(명령 주입 차단). 회귀 방어 `CoreDumpSysroot{Command,ExtractGuard,Warning,Upload}Test`(39).

38. **인증이 필요한 백그라운드 폴러는 무동작 세션 만료를 영구히 무력화한다 — 서버는 멀쩡한데 만료가 안 온다** — Spring Session 은 **요청마다** `SPRING_SESSION.LAST_ACCESS_TIME` 을 갱신한다. 배너(`fragments/banner.html`)가 20개 템플릿 전부에서 60초마다 `/api/system/status`(인증 필요)를 치고 있었기 때문에 **탭 하나만 열려 있어도 1시간 무동작 만료에 영원히 도달하지 못했다**(2026-08-23 제보: 2시간 방치해도 로그인 유지). 진단은 접속 로그가 확정적이다 — 완전 유휴 25분 구간의 요청이 `/api/system/status` 25건뿐(60초 간격, 전부 200)이었고, 브라우저를 닫아 폴링이 끊긴 13시간 뒤 첫 요청은 정상적으로 302 → `/login` 이었다. **서버 설정을 의심하지 말 것.** 대응은 `SessionTimeout.managedInterval()` — 유휴 2분 초과·숨은 탭이면 실행을 건너뛴다. **새 배경 폴러를 추가하면 반드시 이걸 경유할 것**(`setInterval` 직접 사용 금지). ⚠ **폴링을 끄면 드러나는 2차 함정:** `LAST_ACCESS_TIME` 은 요청 **진입 시 1회**만 찍히므로 90분짜리 XHR 업로드나 35분짜리 MAT SSE 는 시작할 때 한 번만 세션을 갱신한다 — 지금까지 장시간 업로드가 멀쩡했던 건 순전히 배너 폴링 덕이었다. 그래서 진행 중 작업은 `registerActivityGuard(fn)` 로 선언해야 하고 모듈이 5분 주기로 `GET /api/session/keepalive` 를 대신 친다(등록: upload-queue `_uploading` / analyze `_aiAnalysisInProgress` / compare `_cmpAiInFlight` / ai-chat `_sending` / progress `analysisRunning` / core-dump-progress `analysisLive`). ⚠ **활동 가드에는 끝나는 작업만 넣을 것** — `account.html` 의 `memoWillWarnOnLeave`("미저장 텍스트 있음")처럼 무기한 참일 수 있는 조건을 넣으면 메모 한 글자로 세션이 불멸이 된다. 그런 화면은 세션을 연장하지 않고 **이 탭만 이동을 막는** `registerNavigationBlock(fn)` 을 쓴다(작성 중 내용이 없으면 false 를 돌려 평범하게 이동). 가드가 영구히 참이 되는 실제 경로가 있어(`progress.html` 의 SSE `onerror` 는 HEAD 가 `!ok` 면 어느 종료 분기도 안 탄다) **8시간 런어웨이 백스톱**을 둔다. ⚠ 만료 시 `location.replace` 도 `beforeunload` 를 발화시켜 사용자가 '취소'하면 자동 이동이 무산되므로(함정 36), `addEventListener` 로 등록된 beforeunload 핸들러 4곳은 첫 줄에서 `SessionTimeout.isExpiring()` 을 보고 빠진다 — **새 이탈 경고를 붙이면 이 줄도 함께 넣을 것.** ⚠ 만료 간격은 설정값이 아니라 `HttpSession.getMaxInactiveInterval()`(`SessionModelAdvice`)에서 읽는다 — `setDefaultMaxInactiveInterval()` 은 신규 세션에만 적용돼 설정값을 쓰면 기존 세션이 경고 없이 401 을 맞는다. 활동 이벤트에 **`mousemove`·`scroll` 을 넣지 말 것**(마우스 지글러·프로그램적 자동 스크롤이 세션을 되살려 이 버그를 재현한다). 회귀 방어 `SessionTimeoutTemplateSmokeTest`(5).

39. **Chroma 는 distance, ES 는 score — 방향이 반대다. 틀려도 에러가 안 나고 "그럴듯한 오답"만 나온다** — ES `_score` 는 클수록, Chroma `distance` 는 작을수록 좋다. `ChromaSearchService.toScore()` 가 space 별로 "클수록 좋음"으로 변환해 기존 `minScore` 규약을 유지한다(cosine `1-d` / l2 `1/(1+d)` / ip `-d`). **space 를 잘못 잡으면 최악 문서가 최상위로 올라오는데 예외도 로그도 없다.** 3중 방어: ① `setRagChromaConfig` 가 space 화이트리스트(오타는 cosine) ② UUID 해석 시점에 컬렉션의 실제 `hnsw:space` 를 읽어 불일치 시 WARN 후 **실제값 채택** ③ `ChromaSearchServiceTest` 가 결과 score 의 **단조 비증가**를 단언(부호 오류를 잡는 유일한 확정적 검사). ⚠ `minScore` 는 모드 간 스케일이 다르다 — BM25 는 보통 5~30, 코사인은 0~1 이라 keyword 용 `2.0` 을 chroma 로 가져가면 **hits 0 건으로 fail-closed** 되고 컨텍스트가 조용히 사라진다(그래서 `fetchContextForLlm` 0건 경로에 로그를 넣었다). chroma 권장 초기값은 **0.45**(성공 질의 0.56~0.65 / 실패 0.36~0.41 실측).

40. **RAG 설정 동기화 지점은 3곳이 아니라 5곳이다 — 빠뜨리면 예외 없이 값만 사라진다** — 종전 문서는 `applyFromSettings`/`collectSettings`/`collectApplicationProperties` 3훅만 언급했지만, 앞에 **`HeapDumpConfig` 의 `@Value`** 와 **`RagConfigService.init()`** 이 더 있다. 이 둘이 빠지면 시드가 안 돼 **재기동 시 application.properties 왕복이 통째로 죽는다**(첫 기동은 멀쩡해서 더 늦게 발견된다). `RagChromaConfigTest.everyMutableFieldIsPersisted` 가 리플렉션으로 모든 `volatile` 필드가 `collectSettings` 에 있는지 + 두 훅의 항목 수가 같은지 단언해 구조적으로 막는다(기존 31필드까지 소급 보호). ⚠ `application.properties` 키는 케밥-닷 표기라(`rag.elasticsearch.auth-type` ↔ `ragAuthType`) **이름으로 대조할 수 없다** — 개수 정합성으로 판정할 것.

41. **Chroma 서버는 임베딩을 하지 않는다 — `embedding_function` 은 클라이언트 SDK 개념이다** — REST `/query` 는 `query_embeddings`(2D float 배열)만 받고 서버 프로세스에는 모델이 아예 로드돼 있지 않다(컬렉션 조회 응답의 `embedding_function` 이 항상 `null` — 실측 확인). 그래서 질의 임베딩은 `EmbeddingService`(provider=`local-onnx`)가 사이드카(:8001)에서 받아온다. ⚠ **색인/질의 접두사가 어긋나면 에러 없이 검색 품질만 무너진다**(e5 계열의 `passage:`/`query:`). 접두사 지식은 `app/embedder.py` 의 `PREFIXES` 한 곳에 가두고 Java 는 모르게 할 것. ⚠ 응답은 질의당 **중첩 배열(depth 2)** 이고 `include` 에서 빠진 필드는 빈 배열이 아니라 **`null`** 로 온다 — 언랩 코드가 NPE 를 내면 검색 전체가 실패한다. ⚠ `add`/`upsert`/`query` 는 **UUID** 를 요구하는데 `GET .../collections/{name}` 은 **이름**으로 받는다(이름→UUID 1회 해석 후 캐시, 캐시 키에 url+tenant+db+collection 전부 포함할 것 — 컬렉션만 키로 쓰면 UI 에서 대상을 바꿔도 재기동 전까지 옛 UUID 를 본다). ⚠ `chroma vacuum` 은 **최상위 명령**이다(`chroma utils vacuum` 아님, 1.5.9 Rust 재작성). ⚠ 재색인(`--reset`)이 남기는 `data/{uuid}/` 의 이름은 **컬렉션 ID 가 아니라 세그먼트 ID** 라, 컬렉션 목록과 대조해 지우면 **살아있는 세그먼트를 지운다** — 반드시 `chroma.sqlite3` 의 `segments` 테이블로 판정할 것. ⚠ 차원·space 불일치는 예외 없이 "그럴듯한 오답"만 내므로 설정 화면의 연동 상태 패널(`integrationWarnings`)이 컬렉션 실측(`dimension`·`hnsw.space`)과 설정·사이드카를 대조해 경고한다 — 모델을 바꾸면 이 패널부터 볼 것.

42. **RAG 지식은 "무엇을 넣느냐"보다 "무엇을 빼느냐"가 품질을 정한다** — `ai_chat_messages` 를 최상위 자산으로 보고 그대로 색인했더니 검색이 오염됐다. 31쌍 중 9쌍이 잡담("오늘 날씨를 알려줘", "안녕하세요", "아 망했어")인데 진짜 문제는 질문이 아니라 **답변**이다 — 잡담에 대한 어시스턴트의 정형 답변이 "메모리 누수 / JVM 튜닝 / OutOfMemoryError / 힙 덤프 해석" 이라는 **능력 메뉴를 나열**해서, 임베딩상 **어떤 도메인 질의에나 걸리는 만능 문서**가 된다. 실측으로 "SIGSEGV 심볼이 안 보인다", "MAT 이 OOM 으로 실패한다" 질의의 **상위 3건을 전부 차지**해 leak 룰과 CSV 를 밀어냈다. `sources._chat_pair_useful()` 이 잡담 정규식 + 답변 정형문으로 거른다. ⚠ **정형문 마커는 답변 앞 300자에서만 찾을 것** — 능력 메뉴는 첫머리부터 나열하지만 충실한 답변도 말미에 "덤프를 공유해주시면" 같은 맺음말을 붙이므로, 전체를 훑으면 좋은 답변까지 통째로 버려진다(실제로 "gc 튜닝 가이드 줘"의 훌륭한 답변이 그렇게 제외됐다가 복원). ⚠ `analysis_result_detail` 은 23건에 3.9MB 지만 `leakSuspects` 는 총 30개뿐이다 — 통째로 벡터화하면 HTML 3.9MB 를 넣고 쓸모 있는 건 30청크다. ⚠ `rag-data/rag-knowledge-20260430.csv` 는 **84행 전부 컬럼이 밀려 있다**(`tags` 가 인용 없이 콤마 포함 → 정상 8컬럼 행 0건). 복구본은 `rag-knowledge-v2.csv`, 규칙은 `tools/fix_knowledge_csv.py`. troubleshooting 11건은 **전부 가상 사례**라 `synthetic=true` 로 색인하고 검색 시 기본 제외한다 — 안 그러면 RAG 가 허구를 근거로 답한다. ⚠ **`ai_chat` 은 2026-08-29 부터 검색에서 제외**(`ChromaSearchService.EXCLUDED_SOURCE_TYPES`, 색인은 보존 — 되돌리려면 목록에서 지우면 되고 재색인 불필요). 평가셋 42건 실측에서 **상위 10건 슬롯의 39.5%(166/420)를 차지하며 정답을 밀어냈다**: 제외 시 Recall@10 0.857→0.952, Recall@5 0.786→0.905. 게다가 과거 대화에는 **다른 분석 건의 구체적 수치**가 그대로 들어 있어, 모델이 그것을 '현재 덤프 수치'로 제시하는 사고가 실제로 났다(한 답변 안에 `byte[] 347개/74.45MB`(진짜)와 `byte[] 17,574개/16.18MB`(다른 건)가 공존). 그래서 `fetchContextForLlm` 헤더에도 **"이 자료는 다른 분석 건의 사례·수치일 수 있다"** 경고를 넣었다 — 덤프 분석 결과와 RAG 자료가 프롬프트에 나란히 붙어 모델이 구분할 근거가 없기 때문이다. ⚠ Chroma `where` 는 조건 2개 이상이면 **`$and` 로 묶어야** 한다(단일 맵에 두 키를 넣으면 하나만 적용된다).

43. **RAG 임베딩은 "한국어를 잘하는 모델"이 아니라 "검색용으로 학습된 모델"을 골라야 한다** — `paraphrase-multilingual-MiniLM-L12-v2` 로 시작했다가 `intfloat/multilingual-e5-small` 로 교체했다. 이름 그대로 **패러프레이즈(대칭) 모델**은 "두 문장이 같은 말인가"를 재는데, RAG 는 **질문 → 문서의 비대칭 검색**이라 과업 자체가 다르다. 실측(질의 "코어덤프에서 SIGSEGV가 났는데 심볼이 안 보입니다"): MiniLM 은 정답 0.0925 / **오답 0.0652 가 다른 정답 0.0432 보다 위** 였고, e5 는 정답 0.843·0.818 이 오답 0.809·0.775 보다 확실히 위였다. 결정적인 건 **MiniLM 도 짧은 구절끼리는 멀쩡했다는 점**이다(`"심볼이 안 보입니다" ↔ "디버그 심볼 없음" = 0.937`) — 한국어를 못하는 게 아니라 과업이 달랐던 것이라, "한국어 성능"만 보고 고르면 이 함정에 그대로 빠진다. 모델 이름에 `paraphrase`·`similarity` 가 있으면 RAG 용이 아니다. ⚠ **e5 계열은 접두사가 필수**다(색인 `passage: ` / 질의 `query: `) — 어긋나면 에러 없이 품질만 무너지므로 `/opt/chroma/app/embedder.py` 의 `PREFIXES` 한 곳에 가둔다. ⚠ **점수 분포가 모델마다 다르다** — 패러프레이즈 0.0~0.6 / e5 **0.80~0.94**. 같은 `min-score` 숫자가 모델을 바꾸면 무필터가 되거나 전부 걸린다. e5 기준 경계는 **0.86**(도메인 밖 질의 0.81~0.85 / 안 0.87~0.94 실측)이고, 모델 교체 시 도메인 밖 질의 몇 개로 반드시 재측정할 것. ⚠ 양자화본 파일명(`model_qint8_avx512_vnni.onnx`)은 **최적 하드웨어 힌트일 뿐 실행 요건이 아니다** — AVX2 CPU 에서 정상 동작하고 fp32 와 순위가 같다(RSS 542MB vs 1148MB). 직접 양자화는 `quantize_dynamic` 이 449MB 모델에 3GB+ 를 써서 가용 2.0GB 에서도 OOM 으로 죽으니, 배포된 양자화본을 먼저 시험할 것.

44. **색인 시점 청킹은 post-retrieval 청킹과 별개다 — 없으면 정답을 품은 문서가 검색되지 않는다** — `RagService.chunkText` 는 **검색 결과를 LLM 에 넣기 전** 자르는 용도이고(`rag.chunking.*`), 색인 시점 청킹은 색인기가 따로 해야 한다. 이게 없으면 mean pooling 이 긴 문서의 벡터를 도메인 평균 쪽으로 끌어당겨, **정답 문장을 그대로 품고 있어도 유사도가 바닥**이 된다(실측: 같은 질의에 대해 짧은 구절 0.937 → 그 문장이 든 400자 문서 0.088). 잘 검색되던 leak 룰이 286~361자였던 건 우연이 아니다. `/opt/chroma/app/indexer.py` 가 350자/overlap 60 으로 분할한다 — **청크 길이는 모델에 맞춰야 하므로 모델을 바꾸면 함께 재조정**할 것. ⚠ **Q&A 를 색인할 때 질문을 임베딩에 넣지 말 것** — 사용자 질의도 질문이라 질문끼리의 유사도가 내용 관련성을 압도한다. 범용 질문 하나("서비스에 어떠한 영향을 미칩니까?")가 SIGSEGV·MAT·JEUS 질의를 전부 가져갔다(0.711). 답변만 색인하고 질문은 메타로 둔다. ⚠ 같은 이유로 **청크마다 제목을 반복해 얹지 말 것** — 제목이 라벨이면 도움이 되지만 질문·문장이면 짧은 청크를 지배한다(넣었다가 0.445→0.711 로 나빠져 되돌렸다).

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
- **PDF 리포트는 공용 렌더 코어 + 도메인별 모델 빌더 (2026-08-23):** `PdfReportService.renderPdf(templateName, model)` 가 Pretendard 임베딩 포함 유일한 렌더 코어 — 힙(`analyze-print`)과 코어덤프(`core-dump/analyze-print`, `CoreDumpPdfReportService`)가 공유. **새 인쇄 리포트 추가 시 이 코어를 재사용**하고, 모델 빌더는 `Map<String,Object>` 반환으로 만들어 PDF(Context)·HTML 미리보기(Model) 양쪽에 같은 템플릿을 쓴다. 인쇄 템플릿 제약: OpenHTMLtoPDF 는 **flex/grid/인라인 SVG 미지원 → display:table 전용**, XHTML 정합 필수(수치 엔티티만), script 0개, 파생 값은 전부 Java 에서 계산(함정 11/23 회피). 코어덤프 리포트 엔드포인트는 힙과 1:1 대칭(`/core-dump/analyze/{fn}/print-pdf`·`/print-html`, `?rev=` 로 보존 리비전 지원) — 코어 AI 인사이트는 top-level Map 이라 힙 `parseInsight`(엔티티 JSON) 재사용 불가. 코어덤프 결과 화면은 7탭(`요약`이 기본 활성 — 히어로/덤프 메타/AI/콜 체인, `data-tab` 위임 + hashchange 복원)이며 리포트 탭은 힙 PDF 패널과 동일한 iframe 미리보기(PDF↔HTML 토글 + 4초 자동 폴백). **요약 탭 카드 순서는 히어로 → 덤프 메타 → 경고 배너 → 2열 래퍼**(2026-08-23) — 구 3열 요약 스트립(`.crash-summary`, 원인·위치·조치)은 제거됐고 조치는 히어로 신뢰도 디스클로저(`hero-conf-guide`)+AI 패널이 대신한다. **크래시 요약의 원인·위치는 화면·인쇄 모두 2열** — 화면은 히어로 안 `.hero-rail`, 인쇄(`core-dump/analyze-print.html`)는 `.sum-grid` 2셀(`.sum-cell{width:50%}`)이다. ⚠ 인쇄 모델의 `fixText` 는 제거됐지만 이름이 비슷한 **`guidanceText` 는 살아 있다**(결함 모듈 박스) — 함께 지우지 말 것. ⚠ 두 템플릿은 별개 마크업이라 **한쪽만 고치면 어긋난다**(실제로 그렇게 어긋났었다).

**크래시 히어로 내부 구조 (2026-08-23 재설계):** `.crash-hero` 는 grid — 좌열 `.hero-main`(시그널 헤드 + 함수 + `.hero-place` + `#heroSource`) 1행 / `.hero-confidence` 2행, 우열 `.hero-rail`(라벨→값 dl, 두 행 span). 구 `.crash-hero-metachips`·`.hero-facts`·`.crash-hero-hints` 3개 박스는 성격이 같아 **레일 하나로 통합**됐다 — 값을 더할 땐 새 박스를 만들지 말고 `.hero-rail-row` 를 추가할 것. ⚠ `grid-template-rows: auto auto` 를 지우면 레일의 `grid-row: 1 / -1` 에서 `-1` 이 1번 라인으로 풀려 span 1 이 되고 **1행이 레일 높이만큼 늘어나 172px 죽은 여백**이 생긴다. ⚠ `row-gap` 은 0 이어야 한다(신뢰도 미렌더 시 빈 행 gap 잔존) — 행 간격은 `.hero-confidence` 의 `margin-top` 담당. ⚠ ≤1023px 1열 전환 시 **레일의 `grid-column`/`grid-row` 를 함께 풀어야** DOM 순서(정체성→팩트→신뢰도)대로 흐른다. ⚠ `.hero-source` 에 `min-height` 를 다시 주지 말 것 — JS fetch 실패 시 빈 구멍이 남는다. `id="heroSource"` 는 `core-dump-analyze.js` 계약이라 위치는 옮겨도 id 는 유지.

**요약 탭은 ≥1400px 에서만 2열**(`body.cd-analyze` 스코프, 컨테이너 1600px + `.cd-sum-cols` flex). **좌 `.cd-sum-main`(히어로·덤프 메타·경고 배너·콜 체인) · 우 AI 크래시 분석**이며(2026-08-26 — 종전엔 AI 좌·콜 체인 우, 히어로/메타는 래퍼 밖 전폭), 그 미만은 세로 1열 그대로다(1열 순서에서 AI 는 맨 뒤). 2열 래퍼는 **grid 고정 2열이 아니라 flex** 여야 한다(좌열만 렌더될 때 전폭을 쓰도록 — grid 면 빈 칸이 남는다). ⚠ AI 패널은 **래퍼 div 없이 `.cda-panel` 자체가 `.cd-sum-cols` 직계 자식** — 감싸면 `crashSignal == null` 로 미렌더일 때 빈 래퍼가 flex-basis 만큼 자리를 먹는다. ⚠ 사이드 폭은 `flex: 0 1 37%`(grow 0) — 이 값을 올리면 줄어드는 건 사이드 옆이 아니라 **히어로 본문**이다(레일 `min-width: 270px` 는 안 줄어듦). `1 1 0`(=50%) 으로 되돌리면 1400px 에서 본문이 ~300px 로 짓눌려 함수명이 글자 단위로 쪼개지고, 40% 초과부터 `crash-hero-func`(20px 모노)가 두 줄로 접힌다 — 조정 시 1400px 스크린샷으로 히어로부터 확인할 것. 회귀 방어 `CoreDumpPdfReportServiceTest`(6)/`CoreDumpPrintTemplateSmokeTest`(5, PDF `%PDF` 바이트 스모크 포함)/`CoreDumpReportEndpointTest`(7, standalone MockMvc — 라우팅·헤더·rev 분기·404/400).
- **deleted 가시성:** `historyPage()`/`filesPage()`는 `Authentication`으로 ROLE_ADMIN 검사. 비관리자에게 `fileDeleted=true` 응답 제외(서버 측 보안). **대시보드 Analysis Files는 모든 계정에서 deleted 항상 제외**.
- **AES 암호화:** `util/AesEncryptor.java` AES-256-CBC. CLI: `bash heap_enc.sh "평문"`, `bash heap_dec.sh "암호문"` (JAR 자동 탐색 — 버전 하드코딩 없음, `HEAP_ANALYZER_JAR` 로 override). DB password / RAG password / API key / OTP seed / SSO secret 모두 `ENC(...)` 형식. **저장 형식 3종** (2026-07-31): ① `v2`+HEX = 현재(랜덤 IV, 마커로 명확) ② HEX>64 = 마커 이전 랜덤 IV ③ HEX≤64 = 레거시 고정 IV. ⚠️ **마커가 필요한 이유** — 랜덤 IV 형식은 평문 15바이트 이하일 때 `IV(16)+1블록(16)` = 정확히 64 HEX 라 레거시와 길이가 겹치고, 고정 IV 로 오복호화해도 CBC 특성상 2번째 블록은 정상 복원 + 패딩 유효라 **예외 없이 `쓰레기 16바이트+평문`** 이 반환됐다(15자 이하 비밀번호 = 조용한 손상). 마커 없는 값의 판별 규칙은 하위 호환을 위해 그대로 유지 — **변경 금지**.
- **`SecretSanity`** — 복호화 **결과값** 위생 검사(U+FFFD/C0/DEL/C1 결정적 4규칙, 휴리스틱 금지). "복호화 실패"를 잡는 검사는 무용지물 — 이 함정은 복호화가 **성공**한다. `describe()` 는 원문 미노출.
- **모호 구간 자동 복구** — 마커 없는 정확히 64 HEX 는 `decrypt()` 가 양쪽 해석을 모두 시도해 위생 검사로 채택(랜덤 IV만 정상이면 자동 복구 + INFO). `decryptIfEncryptedChecked()` 는 `Decrypted` record 반환, **절대 throw 하지 않음** → 선택 기능(RAG/SSO)의 `@PostConstruct` 소프트 페일용. DB(`DataSourceConfig`)만 fail-fast 유지.
- **`SecretValue`** — 로드 당시 암호문을 보관하고 **값이 실제로 바뀐 경우에만 재암호화**. 손상 평문의 재암호화 세탁(원본 영구 소실) 차단 + 기동 churn 제거. 암호화 실패 시 `forStorage()` 가 `null` → 호출자가 **키 자체를 생략**해 기존 저장값 보존(빈 문자열로 덮으면 시크릿 무경고 삭제). **새 시크릿 필드 추가 시 반드시 `SecretValue` + `putSecret()` 패턴 사용** — RAG 3종/SSO clientSecret 이 레퍼런스.
- ⚠️ **파일로 시크릿을 직접 정리할 때** — Spring 이 부팅 시 읽는 건 **JAR 내부 사본**(`BOOT-INF/classes/application.properties`)이다. settings.json + 소스 properties 만 고치면 `init()` 이 옛 값을 읽어 WARN 이 남는다(직후 `applyFromSettings` 가 덮어써 동작엔 무해). **재빌드까지 필요**.
- 회귀 방어: `AesEncryptorTest`(237, 반복 200 포함) / `SecretSanityTest`(22) / `RagConfigServiceSecretTest`(11, 세탁루프·churn) / `SettingsRestoreIsolationTest`(4) / `ResultDirectorySchemeTest`(5) / `DominatorRefsEmptyGuardTest`(5) / `LeakSuspectAdvisorGoldenTest`(10, DB 룰 경로 결과 불변).
- 후속 항목(OTP rekey 도구, `HEAP_ANALYZER_ENCRYPTION_KEY` 미설정)은 `SECRET_ENCRYPTION_FOLLOWUP.md`. **LLM API 키 평문 저장은 2026-08-12 해결** — `SecretValue` 전환 + 기동 시 1회 자동 봉인.
- **Dump Creation Time 파싱:** `HeapAnalysisResult.dumpCreationTime` 필드 — MAT System Overview ZIP `index.html`의 `<td>Date</td>`/`<td>Time</td>` TD 쌍을 파싱. MAT는 JVM 로케일(한국어)로 출력하므로 `"2026. 5. 29."` + `"오후 6시 18분 53초 GMT+9"` 형태. `HeapDumpAnalyzerService.parseDumpCreationTime()` 이 오전/오후 24h 변환 후 `"2026-05-29 18:18:53"` 반환. 기존 result.json에 필드 없을 경우 `reparseOverviewMeta()` 가 `dumpCreationTime == null` 조건으로 재파싱 (classLoader/gcRoot 0 조건과 OR).
- **Leak Rule DB 마이그레이션 (Phase 4):** `leak_library_rules` (98 prefix-based: 66 base + WebLogic 10·Tomcat 5·JEUS 10·Oracle 5·Tibero 2 보강 2026-05-31) + `leak_fallback_rules` (66 regex-based: 33 base + WebLogic 10·Tomcat 5·JEUS 10·Oracle 5·Tibero 3) 테이블 + `LeakRuleAdminController` `/admin/leak-rules` ADMIN CRUD + `LeakSuspectAdvisor` 룰 엔진 + `LeakRuleSeeder` 부트스트랩. 코드 배포 없이 운영자가 추가/수정/우선순위 조정. `LeakRuleService.invalidate()` 로 캐시 즉시 갱신. **2026-08-02 하드코딩 룰 배열(dual-path 폴백 769라인) 제거 — DB 룰 단일 경로**: 룰 미매칭/서비스 미주입/전체 비활성화 시 `analyze()` no-op(suspect 필드 null → UI null 가드가 원문+키워드만 표시). 시드에 catch-all(`.*`) fallback 룰이 있어 DB 룰 활성 시엔 어떤 텍스트든 최소 generic 카테고리를 받는다. 회귀 방어 `LeakSuspectAdvisorGoldenTest`(10).

## Changelog

모든 변경 내용은 `CHANGELOG.md`에 누적 기록. 작업 완료 후 날짜·대상 파일·상세 내역과 함께 추가.

## Refactoring History

`SECURITY_REFACTOR_PLAN.md` 에 단계별 리팩토링 이력 보관. **2026-05-17 기준 모든 보류 항목 해소** (Phase 4A 서비스 분리 / 4B-2 컨트롤러 6 분할 / 5A-3 CSS 통합 / 5B-2 Common.* 마이그레이션 / 5C analyze.html JS 외부화). 새 항목 식별 시 본 문서에 추가.

**Boot 3 마이그레이션 (`BOOT3_MIGRATION_PLAN.md`, 2026-05-19 완료):** Boot 2.7.18 → 3.5.14 / Security 5.7.11 → 6.5.10 / Hibernate 5.6 → 6.6 / Tomcat 9 → 10 / Java 11 → 17 (런타임 JDK 21). 5 phase 분할 (DB 백업 → Java 17 baseline → SecurityConfig lambda DSL 사전 modernize → BIG BANG: jakarta 28 파일 일괄 치환 + requestMatchers + dialect auto-detect → 안정화: Thymeleaf 3.1 fragment syntax + smoke test). 영향: 50 파일 변경, +1185/−157 라인. 운영 인프라: Maven 3.5.4 → 3.9.9 영구 업그레이드 (alternatives + /etc/profile.d). DB: SPRING_SESSION 2종 TRUNCATE + leak_*_rule 5 컬럼 TEXT ALTER. 검증: 14 페이지 200 / 4 ADMIN API 200 / PDF 생성 OK / 기동 12.8s.

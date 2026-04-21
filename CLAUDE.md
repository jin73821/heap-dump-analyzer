# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

Always respond in Korean (한국어). All explanations, questions, and summaries must be in Korean. Code and technical identifiers (variable names, commands, etc.) remain in English.

## Project Overview

Java Spring Boot 2.7.18 web application for analyzing Java heap dump files (.hprof, .bin, .dump) using Eclipse MAT (Memory Analysis Tool) CLI. Runs on Java 11.

## Build & Run Commands

```bash
# Build (skip tests — test directory is empty)
mvn clean package -DskipTests

# Run (default port 8080)
java -jar target/heap-analyzer-2.0.0.jar

# Production run (port 18080, via restart script)
bash restart.sh

# Run with custom config
java -jar target/heap-analyzer-2.0.0.jar \
  --server.port=18080 \
  --heapdump.directory=/path/to/dumps \
  --mat.cli.path=/path/to/ParseHeapDump.sh
```

**CRITICAL:** Frontend changes (CSS, HTML, JS) are all inside the JAR. After any frontend or backend change, you MUST `mvn clean package -DskipTests && bash restart.sh` for changes to take effect.

No linter is configured. No CI/CD pipeline exists.

## Architecture

Spring MVC + JPA with MariaDB. Spring Security 세션 기반 인증. 분석 결과 메타데이터는 DB, 분석 상세 데이터(HTML/ZIP)는 파일 시스템 하이브리드.

**Request flow:**
```
Browser → Spring Security Filter → Controller → Service → MatReportParser
                                        ↓              ↓
                                   JPA Repository   ProcessBuilder (MAT CLI)
                                        ↓
                                   MariaDB (HEAPDB)
```

**Key layers:**
- **Controller** (`controller/HeapDumpController.java`) — REST/MVC endpoints for upload, analysis, comparison, settings, component detail, thread stacks, history, queue status, DB 설정. SSE `Future` tracking per emitter for client disconnect cancellation. Key API endpoints: `/api/history`, `/api/history/bulk-delete` (POST JSON `{filenames,deleteHeapDump}`), `/api/files/bulk-delete`, `/api/cache/clear`, `/api/settings/unreachable`, `/api/settings/compress`, `/api/settings/database` (DB 연결 설정), `/api/settings/database/test` (DB 연결 테스트), `/api/analyze/cancel/{filename}`, `/api/queue/status`, `/api/disk/check`, `/api/settings`, `/api/system/status`, `/api/upload/check`. `/compare` GET: `base`/`target` 파라미터 옵셔널 (미입력 시 파일 선택 화면). `historyPage()`/`filesPage()`는 `Authentication` 파라미터로 ROLE_ADMIN 검사 후 `isAdmin` 모델 속성 + 비관리자에게는 `fileDeleted=true` 항목 응답 제외. Inner DTOs: `AnalysisHistoryItem` (sizeBytes/originalSizeBytes/compressedSizeBytes/heapUsedBytes raw 필드 포함 — 클라이언트 정렬용), `DetectionSummaryItem`, `ClassDiff`.
- **Controller** (`controller/AuthController.java`) — `/login` 로그인 페이지
- **Controller** (`controller/AdminController.java`) — `/admin/users` 계정 관리 (ADMIN 전용). CRUD API: `/api/admin/users`, `/api/admin/users/{id}/reset-password`
- **Controller** (`controller/ServerController.java`) — `/servers` Target Server 관리, `/servers/{id}` 서버 상세 페이지 (분석 이력 + 전송 이력), `/servers/logs` 전송 로그 페이지. API: `/api/servers` (CRUD), `/api/servers/{id}/test` (연결 테스트), `/api/servers/{id}/scan` (수동 스캔), `/api/servers/{id}/transfer` (파일 전송), `/api/servers/scan-interval`, `/api/servers/ssh-local-user`, `/api/servers/scp-temp-dir`
- **Controller** (`controller/AiChatController.java`) — `/ai-chat` AI 채팅 전용 페이지. 세션 CRUD: `/api/ai-chat/sessions` (목록/생성), `/api/ai-chat/sessions/{id}` (수정/삭제). 메시지 조회/저장: `/api/ai-chat/sessions/{id}/messages`. 세션 기반 스트리밍: `POST /api/ai-chat/sessions/{id}/stream` (`SseEmitter`, user/assistant 메시지 자동 DB 저장). `Principal`로 계정별 세션 격리.
- **Service** (`service/HeapDumpAnalyzerService.java`) — Core logic: file management (dumpfiles → tmp copy → analysis → tmp cleanup), async MAT CLI invocation via `ProcessBuilder`, SSE progress streaming via `SseEmitter`, two-tier caching (in-memory `ConcurrentHashMap` + disk `result.json`/`mat.log`). 분석 완료 시 `analysis_history` 테이블에 메타데이터 DB 저장. AI 인사이트 DB 저장/조회/삭제 (`ai_insights` 테이블). LLM API 호출: `callLlmAnalysis(prompt)` (원샷 JSON 응답), `callLlmChat(messages, systemPrompt)` (멀티턴 텍스트 응답), `callLlmChatStream(messages, systemPrompt, onChunk, onDone, onError)` (SSE 스트리밍). Runtime settings persisted to `settings.json` and synced back to `application.properties`.
- **Service** (`service/RemoteDumpService.java`) — SSH/SCP 기반 원격 서버 덤프 탐지/전송. `runuser -l sscuser -c "ssh/scp ..."` 패턴으로 로컬 계정 전환. 2단계 SCP 전송 (임시 경로 → `Files.move()` 최종 경로). `@Scheduled` 동적 주기 자동 탐지. 서버 `connStatus` (OK/FAIL/UNKNOWN) DB 영속화.
- **Service** (`service/UserService.java`) — 사용자 CRUD, `@PostConstruct`에서 기본 admin 계정 자동 생성 (admin/shinhan@10)
- **Service** (`service/CustomUserDetailsService.java`) — Spring Security `UserDetailsService` 구현
- **Parser** (`parser/MatReportParser.java`) — Multi-tier extraction from MAT ZIP files:
  - Overview ZIP: heap stats from `<td>` key-value pairs, Class Histogram, Thread Overview
  - Top Components ZIP: per-component detail pages keyed as `className#index`
  - Suspects ZIP: `Problem/Suspect` section extraction
  - `sanitizeHtml()`: extracts `<body>` content, strips scripts/links/images/event handlers
- **Config** (`config/HeapDumpConfig.java`) — `@Value`-injected properties with startup validation
- **Config** (`config/SecurityConfig.java`) — Spring Security: 세션 인증, `/login` 공개, `/admin/**` ADMIN 전용, `/api/**` CSRF 면제, `X-Frame-Options: SAMEORIGIN` (iframe용)
- **Config** (`config/DataSourceConfig.java`) — `ENC(...)` 형식 DB 비밀번호 AES 자동 복호화
- **Util** (`util/AesEncryptor.java`) — AES-256-CBC HEX 암호화/복호화. CLI: `bash heap_enc.sh "평문"`, `bash heap_dec.sh "암호문"`

**Models** (all use Lombok `@Data`):
- `HeapAnalysisResult` — main result with heap stats, parsed objects, HTML fragments, histogram/thread data, `originalFileSize` (pre-compression size)
- `MatParseResult` — intermediate parse result passed between parser methods
- `HistogramEntry` — class name, object count, shallow/retained heap
- `ThreadInfo` — thread name, type, heap sizes, address, stack trace (matched from `.threads` file)
- `HeapDumpFile` — file info with `compressed`, `originalSize`, `compressedSize` for GZ display
- `LeakSuspect` — title, description, severity (critical/high/medium/low)
- `MemoryObject`, `AnalysisProgress`

**JPA Entities** (`model/entity/`):
- `User` — 사용자 (username, password(BCrypt), displayName, role(ADMIN/USER), enabled)
- `TargetServer` — 원격 서버 (name, host, port, sshUser, dumpPath, autoDetect, connStatus(OK/FAIL/UNKNOWN), lastError)
- `AnalysisHistoryEntity` — 분석 이력 메타데이터 (filename, status, heap sizes, suspect count, serverId, serverName)
- `DumpTransferLog` — SCP 전송 로그 (serverId, filename, remotePath, transferStatus(SUCCESS/FAILED/IN_PROGRESS), errorMessage)
- `AiInsightEntity` — AI 인사이트 결과 (filename, model, severity, insightData(MEDIUMTEXT JSON))
- `AiChatSession` — AI 채팅 세션 (username, filename(nullable=일반채팅), title, model, messageCount). 계정별·덤프별 세션 격리
- `AiChatMessage` — AI 채팅 메시지 (sessionId, role(user/assistant), content(MEDIUMTEXT))

**Repositories** (`repository/`): Spring Data JPA interfaces. `AnalysisHistoryRepository`, `UserRepository`, `TargetServerRepository`, `DumpTransferLogRepository`, `AiInsightRepository`, `AiChatSessionRepository`, `AiChatMessageRepository`

**Frontend:** Thymeleaf templates + vanilla JS + Chart.js. No build step. `index.html`, `analyze.html`, `files.html` have **complete inline `<style>` blocks**. `progress.html` and `compare.html` link `/css/style.css` plus additional inline styles. Modals use `.modal-ov.open` CSS pattern with `animation: modalIn .2s ease`. Tooltip positioning uses `positionTooltip(tt, e)` — auto-flips left/right and up/down to prevent viewport overflow.

**Global Banner** (`fragments/banner.html`) — 모든 페이지에 `th:replace="fragments/banner :: banner"`로 삽입되는 좌측 고정 배너. `position: fixed; left: 0; top: 0; bottom: 0; width: var(--banner-w)` (220px/44px). 포함 내용:
- **Header**: 앱 로고 + 제목 (클릭 시 Dashboard 이동), `<a href="/">` 태그
- **System Status**: MAT CLI 상태, 디스크 사용량, JVM 메모리, 분석 큐 — `/api/system/status` API에서 60초 간격 자동 갱신 + 수동 Refresh 버튼. `localStorage` 캐시로 페이지 이동 시 깜빡임 방지
- **Navigation**: Dashboard, Files, History, Comparison, AI Chat, Servers (아코디언: Target Servers / Transfer Logs), Settings (아코디언: General / LLM Configuration / Accounts(ADMIN only)), Logout. Thymeleaf `sec:authorize` 사용
- **Mobile tabs**: Navigation / Analysis(분석 페이지) / Chat(AI Chat 페이지) / Upload(대시보드). 페이지가 `body.has-{name}-tab` 클래스를 토글하여 동적 표시. 등록은 `registerBannerUploadTab/AnalysisTab/ChatTab(elem)` JS 헬퍼
- **접기/펼치기**: 토글 버튼으로 220px ↔ 44px 전환, `localStorage('bannerCollapsed')` 상태 저장. 접힌 상태에서는 아이콘 스트립이 하단에 `margin-top: auto`로 위치
- **깜빡임 방지**: `<style>` 앞 인라인 `<script>`에서 `banner-collapsed` 클래스 즉시 적용 + `banner-no-transition` 클래스로 초기 transition 차단, `requestAnimationFrame` 2프레임 후 복원
- **스타일 격리**: `.g-banner`에 `font-size: 14px; line-height: 1.5` 고정 — 페이지별 body font-size 차이에 영향받지 않음
- **레이아웃 영향**: 각 페이지의 topbar에 `left: var(--banner-w)`, container에 `padding-left: calc(var(--banner-w) + 20px)` 적용. `index.html`은 `.app-layout`에 `margin-left: var(--banner-w)`, `analyze.html`은 `.sidebar`/`.main-content`에 개별 적용. 900px 이하 모바일에서 배너 숨김 + 모든 offset 리셋
- **CSS 변수**: `:root { --banner-w: 220px; }`, `body.banner-collapsed { --banner-w: 44px; }`. 모바일에서 `--banner-w: 0px !important`
- **페이지 topbar**: 각 페이지 topbar에서 로고 제거됨. 제목은 페이지명 표시 (Dashboard, Analysis, Files, History, Settings, Compare, AI Chat)

**Favicon:** `/static/favicon.svg` — 도넛 차트 스타일 SVG (파란 배경 + 흰색 세그먼트). 모든 페이지 `<head>`에 `<link rel="icon" type="image/svg+xml" href="/favicon.svg">`. `SecurityConfig`에서 `/favicon.svg` permitAll 설정. 배너 헤더 로고(`gb-header-logo`)와 로그인 페이지 로고(`login-logo-icon`)도 동일한 도넛 차트 아이콘 사용.

**External dependency:** Eclipse MAT CLI binary at `/opt/mat/ParseHeapDump.sh`, invoked with reports: `org.eclipse.mat.api:suspects`, `org.eclipse.mat.api:overview`, `org.eclipse.mat.api:top_components`. 30-minute timeout.

## Frontend Structure

**index.html** — Dashboard with sidebar (upload, file list, MAT settings). Topbar title: "Dashboard". Grid layout `300px 1fr` with `margin-left: var(--banner-w)`. **Intro 카드**(`.dashboard-intro`)가 alerts 직후 항상 표시 (앱 가치 1줄 설명). Multi-file upload (max 5, sequential, `<input multiple>`) with queue progress modal, duplicate detection (content hash + name conflict), file filter modal (valid/invalid summary), upload cancel with XHR abort, `beforeunload` page-leave protection. Files list shows tooltips on hover with compressed file info (GZ badge + original/compressed sizes). **Recent Files 카운터**: 전체 ≤ 5건 시 `(N)`, 초과 시 `(표시/전체)` 형태. **Analysis Files 패널은 deleted 항목 항상 제외** (모든 계정). Analysis Queue panel (auto-polls `/api/queue/status` every 5s when active, idle state when empty). Modals for: Download, Clear Cache, Delete, Auto-Analyze warning, Keep Unreachable warning, Upload Progress, Duplicate Content, Duplicate Name, File Filter, Cancel Confirm. (Compare/Export History/Settings 트리거는 좌측 네비/History 페이지로 이동됨)

**analyze.html** — Analysis result page with sidebar navigation sections:
- **Analysis**: Overview (KPI cards, Memory Treemap, Stacked Bar charts), Top Consumers (sortable/searchable table with click-for-detail modal), Leak Suspects (accordion)
- **Actions**: Histogram (parsed data table, **click row → detail modal**), Thread Overview (click row to expand stack trace — single shared detail row for performance), Thread Stacks (lazy-loaded `.threads` file)
- **AI Analysis**: AI 인사이트 패널 (원샷 LLM 분석 — severity/summary/rootCause/recommendations)
- **Tools**: MAT Log (chunked loading), Export CSV, Print
- **Raw Data**: MAT original HTML for System Overview, Top Components, Suspect Details, Histogram, Thread Overview
- **플로팅 AI 채팅**: 우하단 56px FAB 버튼 → 슬라이드업 채팅 패널 (420×560px, 확대 시 700px×80vh). SSE 스트리밍 응답, 마크다운 렌더링, 분석 컨텍스트(KPI/Suspects/TopConsumers/AI인사이트) 자동 주입. 세션 기반 DB 자동 저장. `_aiChatExpanded` 토글로 확대/축소.
- **Component Detail Modal** (`componentDetailModal`): Shared by Top Consumers and Histogram. Two tabs (분석 결과/원본 데이터). Cascade: `/component-detail-parsed` → `/component-detail` (raw HTML) → `renderHistogramFallback()`. Features:
  - `CLASS_DESCRIPTIONS` dict (20 classes): Korean descriptions for common heap classes (`byte[]`, `HashMap$Node`, etc.)
  - `META_HELP` dict: `?` button on metadata cards shows help popover (크기 vs Retained Heap meaning, etc.)
  - `renderParsedDetail()`: auto-generates summary card with heap %, severity counts, Leak Suspect cross-refs, Histogram ranking
  - `findRelatedLeakSuspects()` / `findClassInTopConsumers()`: DOM-based cross-reference between panels
  - Heap % source: Top Consumers table `data-pct` (MAT dominator analysis) takes priority over `metadata.sizeBytes` (total loaded size)

**progress.html** — SSE-driven analysis progress with step indicators. Queue waiting banner (purple gradient) shown when analysis is queued behind another, with position and current analysis filename. Cancel button: QUEUED state shows modal + calls `POST /api/analyze/cancel/{filename}`; RUNNING state uses confirm dialog. `cancelAnalysis()` must not conflict with `cancelHeapWarnModal()` (separate functions — previously caused a bug due to duplicate function names).

**files.html** — Full file listing page (`/files`). 공통 테이블 툴바 패턴 적용 (search + 행표시 셀렉트 + 다중선택 + admin deleted 토글). 페이지네이션 바 (전체 > 페이지 크기일 때 자동 노출). 헤더 클릭 정렬 (default: 날짜 desc). DB id 기반 # 칼럼 (NOT_ANALYZED 파일은 `-`). SVG icon buttons (view/analyze/download/delete) matching `index.html` sidebar style. Delete/Download 확인 모달. 관리자: deleted 행 영구 삭제(purge) 버튼 + 별도 모달 → `POST /history/delete/{filename}`.

**history.html** — Full analysis history page (`/history`). 공통 테이블 툴바 패턴 동일. # (DB id) · 결과 뱃지 · 파일명 · 분석시간 · 힙사용량 · Suspects · 파일크기 · 서버 · 날짜. Topbar 우측에 Export 버튼 (모달 → `/api/history` JSON 다운로드). 다중선택 일괄 삭제 모달은 "힙덤프 파일도 함께 삭제" 옵션 포함. 관리자만 deleted 행+카운터 노출.

**settings.html** — Settings page (`/settings`). Toggle switches for runtime settings with confirmation modals. Toast notifications at top-center. MAT JVM heap/Xms inline editing. Database 카드 (접속 상태, IP/포트/계정 설정 모달, 연결 테스트). Remote scan 설정 (SCP temp dir, SSH local user, scan interval).

**compare.html** — Side-by-side dump comparison. `base`/`target` 파라미터 미입력 시 파일 선택 화면 표시 (`/api/history`에서 SUCCESS 분석 이력 fetch → 두 셀렉트 자동 채움). 좌측 네비 "Comparison" 진입 시 이 모드.

**login.html** — 로그인 페이지. 중앙 정렬 카드 폼 (username, password). CSRF 토큰 자동 삽입. 모바일 반응형 (480px 이하 축소).

**servers.html** — Target Server 관리 (`/servers`). 서버 CRUD 테이블, 연결 테스트, 수동 스캔 (SSH), 파일 전송 (SCP 2단계: 임시경로→최종경로). 전송 진행바 (pulse 애니메이션). "All Transfer" 일괄 전송. 서버 상태 뱃지 (정상/실패/미확인). 자동 스캔 에러 배너 (30초 갱신). 서버 이름 클릭 시 `/servers/{id}` 상세 페이지로 이동.

**server-detail.html** — 서버 상세 페이지 (`/servers/{id}`). 3개 섹션: 서버 정보 카드 (호스트/SSH계정/상태/자동탐지/마지막 에러), 분석 이력 테이블 (해당 서버의 힙덤프 분석 결과), 전송 이력 테이블 (SCP 전송 로그). 연결 테스트/스캔 액션 버튼 포함. 존재하지 않는 ID 접근 시 `/servers`로 리다이렉트.

**server-logs.html** — Transfer Logs (`/servers/logs`). 서버별 아코디언 레이아웃, 전송 이력 테이블 (상태/파일명/원격경로/크기/시간/에러).

**admin/users.html** — 계정 관리 (`/admin/users`, ADMIN 전용). 사용자 CRUD 테이블, 비밀번호 초기화 모달. 기본 관리자 삭제 불가.

**llm-settings.html** — LLM/AI 분석 설정 페이지 (`/settings/llm`). Provider 선택 (Claude/GPT/Genspark/Custom), 모델 선택, API URL/Key, Token Limits, Chat System Prompt (textarea 편집/Save/Reset), Test Connection.

**ai-chat.html** — AI 채팅 전용 페이지 (`/ai-chat`). 좌측 세션 사이드바 (280px): 세션 목록, 새 채팅 버튼, 덤프 파일 필터. 우측 채팅 영역: 스트리밍 메시지 표시, 마크다운 렌더링. 세션 기반 DB 저장 (계정별 격리). 날짜 구분선 (오늘/어제/YYYY년 M월 D일), 메시지별 시간 표시 (오전/오후 H:MM). `checkAuth()` 공통 함수로 인증 만료 시 로그인 안내.

## Directory Structure & File Flow

```
/opt/heapdumps/
├── dumpfiles/              ← 원본 힙덤프 저장 (업로드 대상, 파일 감지)
│   ├── heapdump.hprof
│   └── heapdump.hprof.gz   (compress-after-analysis=true 시)
├── data/                   ← 분석 결과 + 설정 영속화
│   ├── settings.json        (런타임 설정, application.properties와 동기화)
│   └── {baseName}/
│       ├── result.json, mat.log, *.zip
│       ├── *.index, *.threads
│       └── workspace/
└── tmp/                    ← 분석 중 임시 복사본 (분석 후 항상 삭제)
    └── heapdump.hprof       (dumpfiles에서 copy, MAT CLI 실행 대상)
```

**분석 흐름:**
```
Upload → /opt/heapdumps/dumpfiles/{filename}  (원본 보존)
                 │
                 ├─ Analysis start → dumpfiles → tmp/ copy (디스크 공간 체크)
                 │                 → MAT CLI는 tmp 파일로 실행
                 │
                 ├─ Analysis success → tmp 삭제, data/{baseName}/ 결과 저장
                 │                   → compress=true 시 dumpfiles 원본 gzip 압축
                 │
                 └─ Analysis fail/interrupt → tmp만 삭제 (dumpfiles 원본 안전)
```

On startup (`@PostConstruct`): 기존 루트의 덤프 파일 → dumpfiles/ 자동 마이그레이션, disk results restored, missing data re-parsed from ZIPs, `.threads` files loaded, tmp files cleaned, settings.json → application.properties 동기화, **기존 분석 결과 DB 마이그레이션** (result.json → `analysis_history` 테이블), **AI 인사이트 파일 → DB 마이그레이션** (ai_insight.json → `ai_insights` 테이블), **기본 admin 계정 자동 생성** (UserService). On shutdown (`@PreDestroy`): executor `shutdownNow()` + tmp cleanup.

## Database (MariaDB)

**접속 정보**: `192.168.56.9:3306/HEAPDB` (heap_user). 비밀번호는 `application.properties`에 `ENC(...)` AES-256 암호화 저장 → `DataSourceConfig`에서 자동 복호화.

**테이블**: `users`, `target_servers`, `analysis_history`, `dump_transfer_log`, `ai_insights`, `ai_chat_sessions`, `ai_chat_messages`. JPA `ddl-auto=update`로 자동 생성/업데이트.

**하이브리드 저장**: 분석 메타데이터(filename, status, heap size, suspect count)는 DB. 분석 상세 데이터(HTML fragments, ZIP, result.json)는 파일 시스템 유지. AI 인사이트는 DB(`insightData` MEDIUMTEXT JSON). AI 채팅은 DB(세션+메시지 테이블).

**히스토리 삭제**: `deleteHistory()`는 결과 디렉토리 + 메모리 캐시 + DB 레코드(`analysis_history` + `ai_insights`) 모두 삭제. `@Transactional` 적용. `AnalysisHistoryRepository.deleteByFilename()` 사용.

## Authentication & Security

**Spring Security** 세션 기반. `/login` 공개, `/admin/**` ADMIN 전용, 나머지 인증 필요. CSRF: `/api/**` 면제, 나머지 활성화.

**CSRF 주의**: JS로 동적 생성하는 POST 폼에 반드시 `_csrf` hidden input 추가 필요. 패턴:
```javascript
var ci = document.createElement('input'); ci.type = 'hidden'; ci.name = '_csrf';
ci.value = document.querySelector('meta[name="_csrf"]').content; f.appendChild(ci);
```
각 페이지 `<head>`에 `<meta name="_csrf" th:content="${_csrf.token}">` 필요.

**기본 계정**: admin / shinhan@10 (BCrypt, `UserService.initDefaultAdmin()`에서 자동 생성)

**계정별 데이터 격리**: AI 채팅 세션은 `Principal.getName()`으로 현재 사용자를 식별. 세션 조회/수정/삭제 시 username 일치 검증.

## LLM / AI Integration

**Multi-provider LLM**: Claude (Messages API), GPT (Chat Completions), Genspark, Custom (OpenAI-compatible). Provider별 헤더/본문 분기. `volatile` 런타임 설정 (settings.json 영속화).

**3가지 LLM 호출 방식:**
- `callLlmAnalysis(prompt)` — 원샷 분석, JSON 응답 파싱 (severity/summary/rootCause/recommendations)
- `callLlmChat(messages, systemPrompt)` — 멀티턴 대화, 텍스트 응답
- `callLlmChatStream(messages, systemPrompt, onChunk, onDone, onError)` — SSE 스트리밍, `stream:true` 요청. Claude `content_block_delta` / OpenAI `choices.delta.content` 파싱

**채팅 시스템 프롬프트**: `llmChatSystemPrompt` 필드, `DEFAULT_CHAT_SYSTEM_PROMPT` 상수. Settings 페이지에서 편집 가능 (`POST /api/llm/chat-prompt`).

**분석 페이지 플로팅 채팅 흐름:**
```
사용자 입력 → ensureChatSession() (세션 없으면 POST /api/ai-chat/sessions)
           → doStreamRequest() (POST /api/ai-chat/sessions/{id}/stream)
           → SSE 이벤트 수신 (start → chunk 반복 → done)
           → 실시간 마크다운 렌더링 + 커서 애니메이션
           → 완료 시 user/assistant 메시지 DB 자동 저장
```

## Remote Dump Transfer

**SSH/SCP 실행 계정**: `remote.ssh.local-user=sscuser` → `runuser -l sscuser -c "ssh/scp ..."` 패턴. 현재 프로세스와 동일 계정이면 runuser 생략.

**2단계 SCP**: Phase 1: `sscuser`로 SCP → 임시 경로(`remote.scp.temp-dir`, 기본 `/tmp`). Phase 2: `Files.move()`로 앱 계정(root) 권한으로 `/opt/heapdumps/dumpfiles/`에 이동. 임시 파일은 성공/실패 모두 자동 정리.

**전송됨 판정**: DB 전송 성공 로그(`transfer_status=SUCCESS`) **+** 로컬 파일 실제 존재(`.gz` 압축 포함) 두 조건 모두 충족 시.

**자동 탐지**: `@Scheduled(fixedDelay=10초)` 체크 루프 + `scanIntervalSec` 동적 주기. `auto_detect=true && enabled=true` 서버만 대상. 서버 `connStatus` DB 영속화.

## Key Design Decisions

- **Two-tier cache:** In-memory `ConcurrentHashMap` restored from disk (`result.json`) on startup. Missing fields (componentDetailHtmlMap, histogramHtml, threadOverviewHtml) are lazily re-extracted from ZIPs via `reparseComponentDetails()` / `reparseActions()`.
- **Serial analysis with queue:** `Semaphore(1)` ensures only one MAT CLI analysis runs at a time. Thread pool is configurable via `application.properties` (`analysis.thread-pool.core-size/max-size/queue-capacity`). MAT CLI output reader uses a dedicated daemon thread (not the analysis executor) to prevent thread pool exhaustion. `AtomicInteger queueSize` and `volatile currentAnalysisFilename` track queue state. `GET /api/queue/status` exposes queue info.
- **Analysis cancellation:** Explicit cancel via `POST /api/analyze/cancel/{filename}` + `activeTasks` (`ConcurrentHashMap<String, Future<?>>`) for reliable cancellation. SSE disconnect also triggers `task.cancel(true)` via emitter callbacks. QUEUED state shows cancel confirmation modal; RUNNING state uses confirm dialog.
- **Dumpfiles separation:** Uploads go to `{heapdump.directory}/dumpfiles/` (original preserved). Analysis copies to `tmp/`, runs MAT CLI on tmp copy, then deletes tmp. Original is never deleted by analysis — only by explicit compress or user delete.
- **Compression:** `compressDumpFile()` validates .gz size > 0 before deleting original. Controlled by `compressAfterAnalysis` runtime setting (Settings page toggle).
- **Component detail pages:** Keyed as `className#index` in `componentDetailHtmlMap` to handle multiple instances of the same class (e.g., multiple `ParallelWebappClassLoader` instances).
- **Thread stack matching:** `.threads` file parsed by splitting on `Thread 0x...` blocks; matched to `ThreadInfo` entries by hex address extracted from the Thread Overview HTML.
- **Thread detail optimization:** Single shared `<tr>` DOM element moved between rows on click. Stack traces stored in JS array (`THREAD_STACKS`), not pre-rendered in DOM, to avoid performance issues with 60+ threads.
- **MAT HTML sanitization:** `sanitizeHtml()` and `extractBodyContent()` must stay in sync — both exist in parser and service respectively. Both extract `<body>` content, remove scripts/links/event handlers, convert hrefs to `javascript:void(0)`.
- **`@JsonIgnore` on `threadStacksText`:** Not persisted to `result.json` (can be large); loaded on demand from `.threads` file.
- **Models use Lombok:** `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` throughout.
- **Desktop font scaling:** `@media (min-width: 1024px)` blocks in each template's inline styles for desktop readability. Mobile sizes are the base.
- **Mobile sidebar pattern:** `index.html` and `analyze.html` use a slide-in drawer sidebar on mobile (≤900px / ≤768px). Hamburger button (`#menuBtn`) toggles `.mobile-open` on `#mobileSidebar` + `.open` on `#sidebarOverlay`. Sidebar slides via `transform: translateX(-100%)` → `translateX(0)` with cubic-bezier easing. Hamburger animates to X via `.open` class on button. Sidebar includes `.mobile-sidebar-header` (blue gradient branding) visible only on mobile.
- **Thread Stacks auto-load:** `showPanel('thread-stacks')` triggers automatic first-load (`_threadStacksLoaded` flag). Server-side logging with `[ThreadStacks]` prefix in `HeapDumpController`.
- **MAT CLI startup validation:** `HeapDumpConfig.init()` runs 5-step validation (exists → isFile → readable → executable → non-empty). Results stored in `isMatCliReady()` / `getMatCliStatusMessage()`. Service checks `config.isMatCliReady()` before analysis.
- **MAT CLI error extraction:** `extractMatErrorHint()` in service detects OOM, SnapshotException, permission denied, disk full patterns from MAT output and logs Korean-language remediation hints.
- **Settings page:** `/settings` page with toggle switches for runtime settings (Compress after analysis, Keep unreachable objects, Auto-analyze, Save results, Skip heap warning). Settings persisted to `settings.json` AND synced to `application.properties` via `syncApplicationProperties()` (line-by-line replacement preserving comments). `findExternalPropertiesFile()` locates the properties file in JAR directory or source directory.
- **Settings confirmation modals:** Destructive setting changes (disable compress, disable save results, disable keep unreachable, enable auto-analyze) show confirmation modals before applying. Pattern: toggle reverts → modal opens → confirm button calls API + updates toggle.
- **Thymeleaf security restriction:** `th:onclick` with string variables is blocked by Thymeleaf's restricted expression policy. Use `th:data-*` attributes + plain `onclick="fn(this.dataset.x)"` pattern instead.
- **SVG icon button pattern:** `index.html` and `files.html` share the `.fb` icon button style (26×26px, 1px border, SVG stroke icons). Variants: `.fb.v` (green/view), `.fb.p` (blue/analyze), `.fb.d` (red hover/delete), plain (download). When adding file action buttons to new pages, replicate this pattern.
- **Tooltip positioning:** `positionTooltip(tt, e)` handles viewport-aware placement for Treemap/StackedBar tooltips. Flips left when right edge overflows, flips up when bottom overflows. All hover tooltips should use this function.
- **Meta card help popover:** `cdMetaCard(label, value)` auto-adds `?` button when `META_HELP[label]` exists. Popover rendered as `position:fixed` on `document.body` (avoids parent `overflow:hidden` clipping). Uses `toggleMetaHelp(btn)` with modal-boundary-aware positioning. When adding new metadata cards, add corresponding entry to `META_HELP` dict.
- **Class descriptions pattern:** `CLASS_DESCRIPTIONS` dict maps class names → `{desc, detail, icon}`. Used by both `renderHistogramFallback()` and `renderParsedDetail()`. When adding support for new common classes, add entries here.
- **Global banner fragment:** `fragments/banner.html` — CSS + HTML + JS 자체 포함 Thymeleaf fragment. 모든 페이지에 `th:replace`로 삽입. Navigation: Dashboard, Files, History, AI Chat, Settings (아코디언: General / LLM Configuration / Accounts), Servers (아코디언: Target Servers + Transfer Logs), 사용자명 표시 + Logout. System Status → `/api/system/status` JS fetch (60초 TTL `localStorage` 캐시).
- **Banner Servers sub-menu:** `toggleSubMenu()` → `.gb-nav-sub.open` 토글. `/servers*` 경로 시 자동 펼침. CSS `max-height: 0 → 80px` 트랜지션 + `.gb-nav-toggle` 화살표 회전.
- **Banner collapse persistence:** `localStorage('bannerCollapsed')` + 렌더링 전 인라인 스크립트로 FOUC 방지. CSS 변수 `--banner-w`가 모든 페이지의 topbar/container offset 제어.
- **Multi-file upload queue:** `index.html`에서 최대 5개 파일 동시 선택 가능 (`<input multiple>`). `enqueueFiles()` → 확장자/크기 검증 → `showFileFilterModal()`로 유효/무효 파일 요약 표시 → `startDuplicateChecks()` 순차 중복 검사 → `startQueueUploads()` 순차 업로드. 업로드 중 `showUploadProgressModal()` 모달로 파일별 진행 상태(⏳대기/⬆업로드중/✅완료/❌실패/⊘취소) 실시간 표시. `_currentXhr` 저장으로 취소 시 `xhr.abort()` 가능. `window.onbeforeunload`로 페이지 이탈 방지.
- **Upload duplicate detection:** `POST /api/upload/check` API — 클라이언트에서 `file.slice(0, 65536)` + Web Crypto API (또는 `simpleHash` 폴백)로 첫 64KB SHA-256 해시 계산 → 서버 `checkDuplicate()`가 기존 파일들과 파일크기+부분해시 비교. 결과: `OK`/`DUPLICATE_CONTENT`(동일 내용, 다른 이름)/`DUPLICATE_NAME`(같은 이름, 다른 내용). `.gz` 압축 파일은 `GZIPInputStream`으로 압축 해제 후 해시 비교. 이름 변경 시 `generateUniqueName()`으로 `{base}_2.{ext}` 패턴 자동 생성, 사용자 커스텀 이름도 서버 재검증.
- **crypto.subtle 폴백:** `crypto.subtle`은 HTTPS/localhost에서만 사용 가능. HTTP 환경에서는 `simpleHash()` (FNV-1a 기반) 자동 폴백. 모든 경로에 try-catch로 Promise가 반드시 resolve/reject 되도록 보장.
- **Cross-panel references:** Histogram detail and Top Consumers detail modals cross-reference Leak Suspects and each other via DOM search. Pattern: `findRelatedLeakSuspects(className)` searches `#panel-suspects .suspect-item` text; `findClassInTopConsumers(className)` searches `#topObjectsTable` rows. Clicking a cross-ref link closes modal → navigates to target panel → highlights/scrolls to item.
- **플로팅 채팅 확대/축소:** `toggleChatExpand()`로 `.ai-chat-panel`에 `.expanded` CSS 클래스 토글. 420×560px ↔ 700px×80vh. CSS `transition`으로 부드러운 전환. 확대/축소 아이콘 자동 변경. `getChatContainer()`, `getChatInput()`, `getChatSendBtn()` 헬퍼로 DOM 접근 추상화.
- **AI 채팅 세션 자동 생성:** `analyze.html`에서 첫 메시지 전송 시 `ensureChatSession()`이 `POST /api/ai-chat/sessions`로 세션 생성. 이후 `doStreamRequest()`가 세션 기반 스트리밍 엔드포인트 사용.
- **AI 채팅 메시지 DB 저장:** 스트리밍 엔드포인트(`/api/ai-chat/sessions/{id}/stream`)에서 user 메시지는 스트리밍 시작 전 동기 저장, assistant 메시지는 `onDone` 콜백에서 3회 재시도(500ms 간격)로 저장. 저장 성공/실패 로그 `[AI-Chat-Stream]` prefix. `done` SSE 이벤트에 `saved` 필드로 클라이언트에 저장 결과 전달.
- **인증 만료 방어:** `analyze.html`의 `ensureChatSession()`, `ai-chat.html`의 `checkAuth()` 함수에서 fetch 응답의 Content-Type/redirected/status를 확인하여 세션 만료 시 HTML 응답을 JSON으로 파싱하는 에러 방지. "로그인이 만료되었습니다" 안내 메시지 표시.
- **배너 사이드바 DOM 복제 주의:** `analyze.html`의 사이드바는 `cloneNode(true)`로 배너 Analysis 탭에 복제됨. `getElementById`로는 원본만 접근 가능하므로, 양쪽 모두 업데이트해야 할 요소는 `querySelectorAll('.class-name')` 사용 필수 (예: `.ai-nav-status`).
- **분석 진행 중 배너 상태 갱신:** `progress.html`에서 분석 시작(RUNNING)/완료(COMPLETED)/에러(ERROR) 시 `refreshBannerStatus()` 호출하여 디스크/JVM/큐 상태 실시간 반영.
- **Raw Data iframe:** MAT 리포트 ZIP 내 HTML을 `/report/{filename}/mat-page/{reportType}/**` 엔드포인트로 제공. `SecurityConfig`에서 `X-Frame-Options: SAMEORIGIN` 설정 필수. iframe에 `sandbox` 속성 미사용 (allow-scripts + allow-same-origin 조합은 sandbox 무력화 경고 유발). lazy-load 조건은 `!iframe.getAttribute('src')` 사용 (`!iframe.src`는 브라우저별로 `"about:blank"` 반환 가능).
- **Files/History 테이블 툴바 공통 패턴:** 두 페이지(`history.html`, `files.html`)는 동일한 클라이언트 사이드 데이터 그리드 패턴을 공유:
  - 검색 입력 + 행 표시 셀렉트(20/30/50/100, `localStorage` 기억) + admin 전용 "deleted 표시" 체크박스 + 다중선택 토글 버튼
  - 헤더 클릭 정렬: `data-sort-key`/`data-sort-type=num|str` 속성, ▲/▼ 인디케이터 (활성 파란). 기본 날짜 내림차순. 한글 정렬 `localeCompare(s, 'ko')`
  - raw 정렬값은 `<tr>`의 `data-sort-*` 속성으로 직렬화 — 숫자값은 `AnalysisHistoryItem`의 raw 바이트 필드(`sizeBytes`/`originalSizeBytes`/`compressedSizeBytes`/`heapUsedBytes`) 사용
  - 페이지네이션: ‹Prev / 1 … 현재±2 … 마지막 / Next›. 전체 ≤ 페이지 크기일 때 자동 숨김. `applyFilter()` → `render()` → `renderPagination()` 파이프라인. 페이지 이동 후에도 다중선택 상태/카운트 자동 갱신
  - 다중선택 액션바: 하단 고정. `getSelectedFilenames()`는 현재 페이지 visible 행만 대상. 헤더 체크박스 indeterminate 상태 지원
- **다중 삭제 API 분리:** `/api/history/bulk-delete` (분석 기록 + 옵션으로 heap dump 삭제) vs `/api/files/bulk-delete` (heap dump 삭제, 분석 기록 보존). Files 페이지의 일괄 삭제는 살아있는 파일/이미 deleted 항목으로 자동 분리해 두 엔드포인트 호출. 두 API 모두 `{success, failed, errors}` JSON 응답
- **순번 칼럼은 DB id 기반:** 표시 인덱스(시프트되는 순번)가 아닌 `analysis_history.id`(IDENTITY) 사용. 같은 레코드는 항상 같은 번호로 식별. NOT_ANALYZED 파일은 DB 레코드 없으므로 `-` 표시
- **deleted 기록 가시성 제어:** `historyPage()`/`filesPage()`는 `Authentication`으로 ROLE_ADMIN 검사. 비관리자에게는 `fileDeleted=true` 항목을 응답에서 제외(서버 측 보안). 관리자만 토글로 표시/숨김 가능. **대시보드 `Analysis Files` 패널은 모든 계정 deleted 항상 제외**
- **`/history/delete/{filename}` Referer 리다이렉트:** Referer가 `/files` 포함 시 `/files`로, 그 외 `/history`로 복귀. Files 페이지에서도 deleted 행 purge 가능
- **Comparison 페이지 두 모드:** 파라미터 없으면 picker(`/api/history` SUCCESS 항목으로 셀렉트 채움), 있으면 비교 결과. 좌측 네비 "Comparison" 진입 시 picker 모드
- **Export History 위치:** History 페이지 topbar의 Export 버튼이 단일 진입점. 기존 대시보드 Quick Actions의 Compare/Export/Settings 모두 제거됨
- **모바일 Chat 탭 (배너):** ai-chat.html이 `.session-sidebar`를 cloneNode로 복제 후 `registerBannerChatTab(clone)`로 배너 슬롯에 등록. 클론 내부 ID는 충돌 방지 위해 모두 제거 — 따라서 코드는 `getElementById` 대신 `querySelectorAll('.session-list'/'.session-filter select')`로 원본+클론 동시 갱신. 세션 클릭 시 `closeMobileBanner()`로 자동 닫고 채팅 본문 노출
- **채팅 커서 placeholder:** AI 답변 말풍선 생성 직후 첫 chunk 도착 전 시점에도 커서가 깜빡이도록, 초기 placeholder부터 `chat-cursor`/`ai-chat-cursor` 클래스(`cursorBlink` 애니메이션) 사용. 정적 인라인 색상 사용 금지

## Changelog

모든 변경 내용은 `CHANGELOG.md` 파일에 누적 기록합니다. 작업 완료 후 반드시 해당 파일에 변경 내용을 날짜, 대상 파일, 상세 내역과 함께 추가하세요.

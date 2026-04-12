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
- **Controller** (`controller/HeapDumpController.java`) — REST/MVC endpoints for upload, analysis, comparison, settings, component detail, thread stacks, history, queue status, DB 설정. SSE `Future` tracking per emitter for client disconnect cancellation. Key API endpoints: `/api/history`, `/api/cache/clear`, `/api/settings/unreachable`, `/api/settings/compress`, `/api/settings/database` (DB 연결 설정), `/api/settings/database/test` (DB 연결 테스트), `/api/analyze/cancel/{filename}`, `/api/queue/status`, `/api/disk/check`, `/api/settings`, `/api/system/status`, `/api/upload/check`. Inner DTOs: `AnalysisHistoryItem`, `DetectionSummaryItem`, `ClassDiff`.
- **Controller** (`controller/AuthController.java`) — `/login` 로그인 페이지
- **Controller** (`controller/AdminController.java`) — `/admin/users` 계정 관리 (ADMIN 전용). CRUD API: `/api/admin/users`, `/api/admin/users/{id}/reset-password`
- **Controller** (`controller/ServerController.java`) — `/servers` Target Server 관리, `/servers/logs` 전송 로그 페이지. API: `/api/servers` (CRUD), `/api/servers/{id}/test` (연결 테스트), `/api/servers/{id}/scan` (수동 스캔), `/api/servers/{id}/transfer` (파일 전송), `/api/servers/scan-interval`, `/api/servers/ssh-local-user`, `/api/servers/scp-temp-dir`
- **Service** (`service/HeapDumpAnalyzerService.java`) — Core logic: file management (dumpfiles → tmp copy → analysis → tmp cleanup), async MAT CLI invocation via `ProcessBuilder`, SSE progress streaming via `SseEmitter`, two-tier caching (in-memory `ConcurrentHashMap` + disk `result.json`/`mat.log`). 분석 완료 시 `analysis_history` 테이블에 메타데이터 DB 저장. AI 인사이트 DB 저장/조회/삭제 (`ai_insights` 테이블). Runtime settings persisted to `settings.json` and synced back to `application.properties`.
- **Service** (`service/RemoteDumpService.java`) — SSH/SCP 기반 원격 서버 덤프 탐지/전송. `runuser -l sscuser -c "ssh/scp ..."` 패턴으로 로컬 계정 전환. 2단계 SCP 전송 (임시 경로 → `Files.move()` 최종 경로). `@Scheduled` 동적 주기 자동 탐지. 서버 `connStatus` (OK/FAIL/UNKNOWN) DB 영속화.
- **Service** (`service/UserService.java`) — 사용자 CRUD, `@PostConstruct`에서 기본 admin 계정 자동 생성 (admin/shinhan@10)
- **Service** (`service/CustomUserDetailsService.java`) — Spring Security `UserDetailsService` 구현
- **Parser** (`parser/MatReportParser.java`) — Multi-tier extraction from MAT ZIP files:
  - Overview ZIP: heap stats from `<td>` key-value pairs, Class Histogram, Thread Overview
  - Top Components ZIP: per-component detail pages keyed as `className#index`
  - Suspects ZIP: `Problem/Suspect` section extraction
  - `sanitizeHtml()`: extracts `<body>` content, strips scripts/links/images/event handlers
- **Config** (`config/HeapDumpConfig.java`) — `@Value`-injected properties with startup validation
- **Config** (`config/SecurityConfig.java`) — Spring Security: 세션 인증, `/login` 공개, `/admin/**` ADMIN 전용, `/api/**` CSRF 면제
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

**Repositories** (`repository/`): Spring Data JPA interfaces. `AnalysisHistoryRepository`, `UserRepository`, `TargetServerRepository`, `DumpTransferLogRepository`, `AiInsightRepository`

**Frontend:** Thymeleaf templates + vanilla JS + Chart.js. No build step. `index.html`, `analyze.html`, `files.html` have **complete inline `<style>` blocks**. `progress.html` and `compare.html` link `/css/style.css` plus additional inline styles. Modals use `.modal-ov.open` CSS pattern with `animation: modalIn .2s ease`. Tooltip positioning uses `positionTooltip(tt, e)` — auto-flips left/right and up/down to prevent viewport overflow.

**Global Banner** (`fragments/banner.html`) — 모든 페이지에 `th:replace="fragments/banner :: banner"`로 삽입되는 좌측 고정 배너. `position: fixed; left: 0; top: 0; bottom: 0; width: var(--banner-w)` (220px/44px). 포함 내용:
- **Header**: 앱 로고 + 제목 (클릭 시 Dashboard 이동), `<a href="/">` 태그
- **System Status**: MAT CLI 상태, 디스크 사용량, JVM 메모리, 분석 큐 — `/api/system/status` API에서 60초 간격 자동 갱신 + 수동 Refresh 버튼. `localStorage` 캐시로 페이지 이동 시 깜빡임 방지
- **Navigation**: Dashboard, Files, History, Settings, Servers (아코디언: Target Servers / Transfer Logs), Admin (ADMIN only), Logout. Thymeleaf `sec:authorize` 사용
- **접기/펼치기**: 토글 버튼으로 220px ↔ 44px 전환, `localStorage('bannerCollapsed')` 상태 저장. 접힌 상태에서는 아이콘 스트립이 하단에 `margin-top: auto`로 위치
- **깜빡임 방지**: `<style>` 앞 인라인 `<script>`에서 `banner-collapsed` 클래스 즉시 적용 + `banner-no-transition` 클래스로 초기 transition 차단, `requestAnimationFrame` 2프레임 후 복원
- **스타일 격리**: `.g-banner`에 `font-size: 14px; line-height: 1.5` 고정 — 페이지별 body font-size 차이에 영향받지 않음
- **레이아웃 영향**: 각 페이지의 topbar에 `left: var(--banner-w)`, container에 `padding-left: calc(var(--banner-w) + 20px)` 적용. `index.html`은 `.app-layout`에 `margin-left: var(--banner-w)`, `analyze.html`은 `.sidebar`/`.main-content`에 개별 적용. 900px 이하 모바일에서 배너 숨김 + 모든 offset 리셋
- **CSS 변수**: `:root { --banner-w: 220px; }`, `body.banner-collapsed { --banner-w: 44px; }`. 모바일에서 `--banner-w: 0px !important`
- **페이지 topbar**: 각 페이지 topbar에서 로고 제거됨. 제목은 페이지명 표시 (Dashboard, Analysis, Files, History, Settings, Compare)

**External dependency:** Eclipse MAT CLI binary at `/opt/mat/ParseHeapDump.sh`, invoked with reports: `org.eclipse.mat.api:suspects`, `org.eclipse.mat.api:overview`, `org.eclipse.mat.api:top_components`. 30-minute timeout.

## Frontend Structure

**index.html** — Dashboard with sidebar (upload, file list, MAT settings). Topbar title: "Dashboard". Grid layout `300px 1fr` with `margin-left: var(--banner-w)`. Multi-file upload (max 5, sequential, `<input multiple>`) with queue progress modal, duplicate detection (content hash + name conflict), file filter modal (valid/invalid summary), upload cancel with XHR abort, `beforeunload` page-leave protection. Files list shows tooltips on hover with compressed file info (GZ badge + original/compressed sizes). Analysis Queue panel (auto-polls `/api/queue/status` every 5s when active, idle state when empty). Modals for: Download, Compare, Export History, Clear Cache, Delete, Auto-Analyze warning, Keep Unreachable warning, Upload Progress, Duplicate Content, Duplicate Name, File Filter, Cancel Confirm.

**analyze.html** — Analysis result page with sidebar navigation sections:
- **Analysis**: Overview (KPI cards, Memory Treemap, Stacked Bar charts), Top Consumers (sortable/searchable table with click-for-detail modal), Leak Suspects (accordion)
- **Actions**: Histogram (parsed data table, **click row → detail modal**), Thread Overview (click row to expand stack trace — single shared detail row for performance), Thread Stacks (lazy-loaded `.threads` file)
- **Tools**: MAT Log (chunked loading), Export CSV, Print
- **Raw Data**: MAT original HTML for System Overview, Top Components, Suspect Details, Histogram, Thread Overview
- **Component Detail Modal** (`componentDetailModal`): Shared by Top Consumers and Histogram. Two tabs (분석 결과/원본 데이터). Cascade: `/component-detail-parsed` → `/component-detail` (raw HTML) → `renderHistogramFallback()`. Features:
  - `CLASS_DESCRIPTIONS` dict (20 classes): Korean descriptions for common heap classes (`byte[]`, `HashMap$Node`, etc.)
  - `META_HELP` dict: `?` button on metadata cards shows help popover (크기 vs Retained Heap meaning, etc.)
  - `renderParsedDetail()`: auto-generates summary card with heap %, severity counts, Leak Suspect cross-refs, Histogram ranking
  - `findRelatedLeakSuspects()` / `findClassInTopConsumers()`: DOM-based cross-reference between panels
  - Heap % source: Top Consumers table `data-pct` (MAT dominator analysis) takes priority over `metadata.sizeBytes` (total loaded size)

**progress.html** — SSE-driven analysis progress with step indicators. Queue waiting banner (purple gradient) shown when analysis is queued behind another, with position and current analysis filename. Cancel button: QUEUED state shows modal + calls `POST /api/analyze/cancel/{filename}`; RUNNING state uses confirm dialog. `cancelAnalysis()` must not conflict with `cancelHeapWarnModal()` (separate functions — previously caused a bug due to duplicate function names).

**files.html** — Full file listing page (`/files`). Search filter, status dots, SVG icon buttons (view/analyze/download/delete) matching `index.html` sidebar style. Delete confirmation modal. Download confirmation modal (filename + size).

**history.html** — Full analysis history page (`/history`). Topbar navigation, search filter, status dots (success/error/pending), analysis metadata (file size, date, analysis time, heap size, suspect count), total statistics. Complete inline `<style>` block like `index.html`.

**settings.html** — Settings page (`/settings`). Toggle switches for runtime settings with confirmation modals. Toast notifications at top-center. MAT JVM heap/Xms inline editing. Database 카드 (접속 상태, IP/포트/계정 설정 모달, 연결 테스트). Remote scan 설정 (SCP temp dir, SSH local user, scan interval).

**compare.html** — Side-by-side dump comparison.

**login.html** — 로그인 페이지. 중앙 정렬 카드 폼 (username, password). CSRF 토큰 자동 삽입. 모바일 반응형 (480px 이하 축소).

**servers.html** — Target Server 관리 (`/servers`). 서버 CRUD 테이블, 연결 테스트, 수동 스캔 (SSH), 파일 전송 (SCP 2단계: 임시경로→최종경로). 전송 진행바 (pulse 애니메이션). "All Transfer" 일괄 전송. 서버 상태 뱃지 (정상/실패/미확인). 자동 스캔 에러 배너 (30초 갱신).

**server-logs.html** — Transfer Logs (`/servers/logs`). 서버별 아코디언 레이아웃, 전송 이력 테이블 (상태/파일명/원격경로/크기/시간/에러).

**admin/users.html** — 계정 관리 (`/admin/users`, ADMIN 전용). 사용자 CRUD 테이블, 비밀번호 초기화 모달. 기본 관리자 삭제 불가.

**llm-settings.html** — LLM/AI 분석 설정 페이지 (`/settings/llm`).

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

**테이블**: `users`, `target_servers`, `analysis_history`, `dump_transfer_log`, `ai_insights`. JPA `ddl-auto=update`로 자동 생성/업데이트.

**하이브리드 저장**: 분석 메타데이터(filename, status, heap size, suspect count)는 DB. 분석 상세 데이터(HTML fragments, ZIP, result.json)는 파일 시스템 유지. AI 인사이트는 DB(`insightData` MEDIUMTEXT JSON).

## Authentication & Security

**Spring Security** 세션 기반. `/login` 공개, `/admin/**` ADMIN 전용, 나머지 인증 필요. CSRF: `/api/**` 면제, 나머지 활성화.

**CSRF 주의**: JS로 동적 생성하는 POST 폼에 반드시 `_csrf` hidden input 추가 필요. 패턴:
```javascript
var ci = document.createElement('input'); ci.type = 'hidden'; ci.name = '_csrf';
ci.value = document.querySelector('meta[name="_csrf"]').content; f.appendChild(ci);
```
각 페이지 `<head>`에 `<meta name="_csrf" th:content="${_csrf.token}">` 필요.

**기본 계정**: admin / shinhan@10 (BCrypt, `UserService.initDefaultAdmin()`에서 자동 생성)

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
- **Global banner fragment:** `fragments/banner.html` — CSS + HTML + JS 자체 포함 Thymeleaf fragment. 모든 페이지에 `th:replace`로 삽입. Navigation: Dashboard, Files, History, Settings, Servers (아코디언: Target Servers + Transfer Logs), Admin (ADMIN only, `sec:authorize`), 사용자명 표시 + Logout. System Status → `/api/system/status` JS fetch (60초 TTL `localStorage` 캐시).
- **Banner Servers sub-menu:** `toggleSubMenu()` → `.gb-nav-sub.open` 토글. `/servers*` 경로 시 자동 펼침. CSS `max-height: 0 → 80px` 트랜지션 + `.gb-nav-toggle` 화살표 회전.
- **Banner collapse persistence:** `localStorage('bannerCollapsed')` + 렌더링 전 인라인 스크립트로 FOUC 방지. CSS 변수 `--banner-w`가 모든 페이지의 topbar/container offset 제어.
- **Multi-file upload queue:** `index.html`에서 최대 5개 파일 동시 선택 가능 (`<input multiple>`). `enqueueFiles()` → 확장자/크기 검증 → `showFileFilterModal()`로 유효/무효 파일 요약 표시 → `startDuplicateChecks()` 순차 중복 검사 → `startQueueUploads()` 순차 업로드. 업로드 중 `showUploadProgressModal()` 모달로 파일별 진행 상태(⏳대기/⬆업로드중/✅완료/❌실패/⊘취소) 실시간 표시. `_currentXhr` 저장으로 취소 시 `xhr.abort()` 가능. `window.onbeforeunload`로 페이지 이탈 방지.
- **Upload duplicate detection:** `POST /api/upload/check` API — 클라이언트에서 `file.slice(0, 65536)` + Web Crypto API (또는 `simpleHash` 폴백)로 첫 64KB SHA-256 해시 계산 → 서버 `checkDuplicate()`가 기존 파일들과 파일크기+부분해시 비교. 결과: `OK`/`DUPLICATE_CONTENT`(동일 내용, 다른 이름)/`DUPLICATE_NAME`(같은 이름, 다른 내용). `.gz` 압축 파일은 `GZIPInputStream`으로 압축 해제 후 해시 비교. 이름 변경 시 `generateUniqueName()`으로 `{base}_2.{ext}` 패턴 자동 생성, 사용자 커스텀 이름도 서버 재검증.
- **crypto.subtle 폴백:** `crypto.subtle`은 HTTPS/localhost에서만 사용 가능. HTTP 환경에서는 `simpleHash()` (FNV-1a 기반) 자동 폴백. 모든 경로에 try-catch로 Promise가 반드시 resolve/reject 되도록 보장.
- **Cross-panel references:** Histogram detail and Top Consumers detail modals cross-reference Leak Suspects and each other via DOM search. Pattern: `findRelatedLeakSuspects(className)` searches `#panel-suspects .suspect-item` text; `findClassInTopConsumers(className)` searches `#topObjectsTable` rows. Clicking a cross-ref link closes modal → navigates to target panel → highlights/scrolls to item.

## Changelog

모든 변경 내용은 `CHANGELOG.md` 파일에 누적 기록합니다. 작업 완료 후 반드시 해당 파일에 변경 내용을 날짜, 대상 파일, 상세 내역과 함께 추가하세요.

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

Classic Spring MVC with service layer. No database — all state is file-based.

**Request flow:**
```
Browser → HeapDumpController → HeapDumpAnalyzerService → MatReportParser
                                      ↓
                              ProcessBuilder (MAT CLI subprocess)
```

**Key layers:**
- **Controller** (`controller/HeapDumpController.java`) — REST/MVC endpoints for upload, analysis, comparison, settings, component detail, thread stacks, history, queue status. SSE `Future` tracking per emitter for client disconnect cancellation. Key API endpoints: `/api/history` (JSON history list), `/api/cache/clear`, `/api/settings/unreachable`, `/api/settings/compress`, `/api/analyze/cancel/{filename}`, `/api/queue/status`, `/api/disk/check`, `/api/settings`. `GET /analyze/rerun/{filename}` clears cache and restarts analysis.
- **Service** (`service/HeapDumpAnalyzerService.java`) — Core logic: file management (dumpfiles → tmp copy → analysis → tmp cleanup), async MAT CLI invocation via `ProcessBuilder`, SSE progress streaming via `SseEmitter`, two-tier caching (in-memory `ConcurrentHashMap` + disk `result.json`/`mat.log`). On disk cache restore, auto-reparses missing data from ZIPs (`reparseComponentDetails`, `reparseActions`). Runtime settings (`keepUnreachableObjects`, `compressAfterAnalysis`) persisted to `settings.json` and synced back to `application.properties`.
- **Parser** (`parser/MatReportParser.java`) — Multi-tier extraction from MAT ZIP files:
  - Overview ZIP: heap stats from `<td>` key-value pairs, Class Histogram from `Class_Histogram*.html`, Thread Overview from `Thread_Overview*.html`
  - Top Components ZIP: `index.html` `<h2>` tags for percentages (primary), sub-page tables (fallback), per-component detail pages keyed as `className#index`
  - Suspects ZIP: `Problem/Suspect` section extraction
  - `sanitizeHtml()`: extracts `<body>` content, strips scripts/links/images/event handlers, converts `href="#"` to `javascript:void(0)`
- **Config** (`config/HeapDumpConfig.java`) — `@Value`-injected properties with startup validation.

**Models** (all use Lombok `@Data`):
- `HeapAnalysisResult` — main result with heap stats, parsed objects, HTML fragments, histogram/thread data, `originalFileSize` (pre-compression size)
- `MatParseResult` — intermediate parse result passed between parser methods
- `HistogramEntry` — class name, object count, shallow/retained heap. `getRetainedHeapHuman()` / `getShallowHeapHuman()` for human-readable display (≥ 973.4 MB). `retainedHeapDisplay` preserves MAT's raw format (">= 1,020,644,584").
- `ThreadInfo` — thread name, type, heap sizes, address, stack trace (matched from `.threads` file)
- `HeapDumpFile` — file info with `compressed`, `originalSize`, `compressedSize` for GZ display
- `MemoryObject`, `LeakSuspect`, `AnalysisProgress`

**Frontend:** Thymeleaf templates + vanilla JS + Chart.js. No build step. `index.html`, `analyze.html`, `files.html` have **complete inline `<style>` blocks**. `progress.html` and `compare.html` link `/css/style.css` plus additional inline styles. Modals use `.modal-ov.open` CSS pattern with `animation: modalIn .2s ease`. Tooltip positioning uses `positionTooltip(tt, e)` — auto-flips left/right and up/down to prevent viewport overflow.

**Global Banner** (`fragments/banner.html`) — 모든 페이지에 `th:replace="fragments/banner :: banner"`로 삽입되는 좌측 고정 배너. `position: fixed; left: 0; top: 0; bottom: 0; width: var(--banner-w)` (220px/44px). 포함 내용:
- **Header**: 앱 로고 + 제목 (클릭 시 Dashboard 이동), `<a href="/">` 태그
- **System Status**: MAT CLI 상태, 디스크 사용량, JVM 메모리, 분석 큐 — `/api/system/status` API에서 60초 간격 자동 갱신 + 수동 Refresh 버튼. `localStorage` 캐시로 페이지 이동 시 깜빡임 방지
- **Navigation**: Dashboard, Files, History, Settings 링크 (현재 페이지 하이라이트)
- **접기/펼치기**: 토글 버튼으로 220px ↔ 44px 전환, `localStorage('bannerCollapsed')` 상태 저장. 접힌 상태에서는 아이콘 스트립이 하단에 `margin-top: auto`로 위치
- **깜빡임 방지**: `<style>` 앞 인라인 `<script>`에서 `banner-collapsed` 클래스 즉시 적용 + `banner-no-transition` 클래스로 초기 transition 차단, `requestAnimationFrame` 2프레임 후 복원
- **스타일 격리**: `.g-banner`에 `font-size: 14px; line-height: 1.5` 고정 — 페이지별 body font-size 차이에 영향받지 않음
- **레이아웃 영향**: 각 페이지의 topbar에 `left: var(--banner-w)`, container에 `padding-left: calc(var(--banner-w) + 20px)` 적용. `index.html`은 `.app-layout`에 `margin-left: var(--banner-w)`, `analyze.html`은 `.sidebar`/`.main-content`에 개별 적용. 900px 이하 모바일에서 배너 숨김 + 모든 offset 리셋
- **CSS 변수**: `:root { --banner-w: 220px; }`, `body.banner-collapsed { --banner-w: 44px; }`. 모바일에서 `--banner-w: 0px !important`
- **페이지 topbar**: 각 페이지 topbar에서 로고 제거됨. 제목은 페이지명 표시 (Dashboard, Analysis, Files, History, Settings, Compare)

**External dependency:** Eclipse MAT CLI binary at `/opt/mat/ParseHeapDump.sh`, invoked with reports: `org.eclipse.mat.api:suspects`, `org.eclipse.mat.api:overview`, `org.eclipse.mat.api:top_components`. 30-minute timeout.

## Frontend Structure

**index.html** — Dashboard with sidebar (upload, file list, MAT settings). Topbar title: "Dashboard". Grid layout `300px 1fr` with `margin-left: var(--banner-w)`. Files list shows tooltips on hover with compressed file info (GZ badge + original/compressed sizes). Analysis Queue panel (auto-polls `/api/queue/status` every 5s when active, idle state when empty). Modals for: Download, Compare, Export History, Clear Cache, Delete, Auto-Analyze warning, Keep Unreachable warning.

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

**settings.html** — Settings page (`/settings`). Toggle switches for runtime settings with confirmation modals. Toast notifications at top-center. MAT JVM heap/Xms inline editing with predefined select options.

**compare.html** — Side-by-side dump comparison.

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

On startup (`@PostConstruct`): 기존 루트의 덤프 파일 → dumpfiles/ 자동 마이그레이션, disk results restored, missing data re-parsed from ZIPs, `.threads` files loaded, tmp files cleaned, settings.json → application.properties 동기화. On shutdown (`@PreDestroy`): executor `shutdownNow()` + tmp cleanup.

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
- **Global banner fragment:** `fragments/banner.html`은 CSS + HTML + JS를 자체 포함하는 Thymeleaf fragment. 모든 7개 페이지에 `th:replace`로 삽입. System Status 데이터는 서버 렌더링이 아닌 `/api/system/status` JS fetch로 로드하여 controller 의존성 없음. `localStorage` 캐시로 페이지 이동 간 데이터 유지 (60초 TTL).
- **Banner collapse persistence:** `localStorage('bannerCollapsed')` + 렌더링 전 인라인 스크립트로 FOUC(Flash of Unstyled Content) 방지. CSS 변수 `--banner-w`가 모든 페이지의 topbar `left`, container `padding-left`/`margin-left`를 제어.
- **Cross-panel references:** Histogram detail and Top Consumers detail modals cross-reference Leak Suspects and each other via DOM search. Pattern: `findRelatedLeakSuspects(className)` searches `#panel-suspects .suspect-item` text; `findClassInTopConsumers(className)` searches `#topObjectsTable` rows. Clicking a cross-ref link closes modal → navigates to target panel → highlights/scrolls to item.

## Changelog

모든 변경 내용은 `CHANGELOG.md` 파일에 누적 기록합니다. 작업 완료 후 반드시 해당 파일에 변경 내용을 날짜, 대상 파일, 상세 내역과 함께 추가하세요.

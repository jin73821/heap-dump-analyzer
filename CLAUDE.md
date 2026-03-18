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
- **Controller** (`controller/HeapDumpController.java`) — REST/MVC endpoints for upload, analysis, comparison, settings, component detail, thread stacks. SSE `Future` tracking per emitter for client disconnect cancellation.
- **Service** (`service/HeapDumpAnalyzerService.java`) — Core logic: file management (tmp staging → final), async MAT CLI invocation via `ProcessBuilder`, SSE progress streaming via `SseEmitter`, two-tier caching (in-memory `ConcurrentHashMap` + disk `result.json`/`mat.log`). On disk cache restore, auto-reparses missing data from ZIPs (`reparseComponentDetails`, `reparseActions`).
- **Parser** (`parser/MatReportParser.java`) — Multi-tier extraction from MAT ZIP files:
  - Overview ZIP: heap stats from `<td>` key-value pairs, Class Histogram from `Class_Histogram*.html`, Thread Overview from `Thread_Overview*.html`
  - Top Components ZIP: `index.html` `<h2>` tags for percentages (primary), sub-page tables (fallback), per-component detail pages keyed as `className#index`
  - Suspects ZIP: `Problem/Suspect` section extraction
  - `sanitizeHtml()`: extracts `<body>` content, strips scripts/links/images/event handlers, converts `href="#"` to `javascript:void(0)`
- **Config** (`config/HeapDumpConfig.java`) — `@Value`-injected properties with startup validation.

**Models** (all use Lombok `@Data`):
- `HeapAnalysisResult` — main result with heap stats, parsed objects, HTML fragments, histogram/thread data
- `MatParseResult` — intermediate parse result passed between parser methods
- `HistogramEntry` — class name, object count, shallow/retained heap
- `ThreadInfo` — thread name, type, heap sizes, address, stack trace (matched from `.threads` file)
- `MemoryObject`, `LeakSuspect`, `AnalysisProgress`, `HeapDumpFile`

**Frontend:** Thymeleaf templates + vanilla JS + Chart.js. No build step. All styles are **inline `<style>` blocks** inside each template (NOT in `style.css` — `index.html` and `analyze.html` each have their own complete inline styles). Modals use `.modal-ov.open` CSS pattern with `animation: modalIn .2s ease`.

**External dependency:** Eclipse MAT CLI binary at `/opt/mat/ParseHeapDump.sh`, invoked with reports: `org.eclipse.mat.api:suspects`, `org.eclipse.mat.api:overview`, `org.eclipse.mat.api:top_components`. 30-minute timeout.

## Frontend Structure

**index.html** — Dashboard with sidebar (upload, file list, MAT settings). Files list shows tooltips on hover. Modals for: Download, Compare, Export History, Clear Cache, Delete, Auto-Analyze warning, Keep Unreachable warning.

**analyze.html** — Analysis result page with sidebar navigation sections:
- **Analysis**: Overview (KPI cards, charts), Top Consumers (sortable/searchable table with click-for-detail modal), Leak Suspects (accordion)
- **Actions**: Histogram (parsed data table), Thread Overview (click row to expand stack trace — single shared detail row for performance), Thread Stacks (lazy-loaded `.threads` file)
- **Tools**: MAT Log (chunked loading), Export CSV, Print
- **Raw Data**: MAT original HTML for System Overview, Top Components, Suspect Details, Histogram, Thread Overview

**progress.html** — SSE-driven analysis progress with step indicators.

**compare.html** — Side-by-side dump comparison.

## File Flow

```
Upload → /opt/heapdumps/tmp/{filename}     (staging)
                 │
                 ├─ Analysis success → /opt/heapdumps/{filename}  (Files.move)
                 │                   → /opt/heapdumps/{baseName}/  (result.json, mat.log, ZIPs)
                 │                   → /opt/heapdumps/{baseName}.threads  (thread stacks)
                 │                   → /opt/heapdumps/{baseName}.*.index  (MAT index files)
                 │
                 └─ Analysis fail/interrupt/shutdown → tmp file deleted
```

On startup (`@PostConstruct`): disk results restored, missing data re-parsed from ZIPs, `.threads` files loaded, tmp files cleaned. On shutdown (`@PreDestroy`): executor `shutdownNow()` + tmp cleanup.

## Key Design Decisions

- **Two-tier cache:** In-memory `ConcurrentHashMap` restored from disk (`result.json`) on startup. Missing fields (componentDetailHtmlMap, histogramHtml, threadOverviewHtml) are lazily re-extracted from ZIPs via `reparseComponentDetails()` / `reparseActions()`.
- **Async analysis with cancellation:** `CachedThreadPool` executor returns `Future<?>`. SSE emitter callbacks call `task.cancel(true)` on client disconnect.
- **Tmp staging:** Uploads go to `{heapdump.directory}/tmp/` first. Only moved to final location after successful analysis.
- **Component detail pages:** Keyed as `className#index` in `componentDetailHtmlMap` to handle multiple instances of the same class (e.g., multiple `ParallelWebappClassLoader` instances).
- **Thread stack matching:** `.threads` file parsed by splitting on `Thread 0x...` blocks; matched to `ThreadInfo` entries by hex address extracted from the Thread Overview HTML.
- **Thread detail optimization:** Single shared `<tr>` DOM element moved between rows on click. Stack traces stored in JS array (`THREAD_STACKS`), not pre-rendered in DOM, to avoid performance issues with 60+ threads.
- **MAT HTML sanitization:** `sanitizeHtml()` and `extractBodyContent()` must stay in sync — both exist in parser and service respectively. Both extract `<body>` content, remove scripts/links/event handlers, convert hrefs to `javascript:void(0)`.
- **`@JsonIgnore` on `threadStacksText`:** Not persisted to `result.json` (can be large); loaded on demand from `.threads` file.
- **Models use Lombok:** `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` throughout.
- **Desktop font scaling:** `@media (min-width: 1024px)` blocks in each template's inline styles for desktop readability. Mobile sizes are the base.

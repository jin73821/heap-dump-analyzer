# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java Spring Boot 2.7.18 web application for analyzing Java heap dump files (.hprof, .bin, .dump) using Eclipse MAT (Memory Analysis Tool) CLI. Runs on Java 11.

## Build & Run Commands

```bash
# Build
mvn clean package

# Run (default port 8080)
java -jar target/heap-analyzer-2.0.0.jar

# Run tests (test directory currently empty)
mvn test

# Run with custom config
java -jar target/heap-analyzer-2.0.0.jar \
  --heapdump.directory=/path/to/dumps \
  --mat.cli.path=/path/to/ParseHeapDump.sh
```

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
- **Controller** (`controller/HeapDumpController.java`) — 11+ REST/MVC endpoints handling upload, analysis, comparison, settings, and embedded MAT HTML reports
- **Service** (`service/HeapDumpAnalyzerService.java`) — Core logic: file management, async MAT CLI invocation via `ProcessBuilder`, SSE progress streaming via `SseEmitter`, two-tier caching (in-memory `ConcurrentHashMap` + disk `result.json`/`mat.log`)
- **Parser** (`parser/MatReportParser.java`) — Extracts data from MAT CLI output ZIP files using regex-based HTML parsing
- **Config** (`config/HeapDumpConfig.java`) — `@Value`-injected properties with startup validation

**Frontend:** Thymeleaf templates + vanilla JS + Chart.js. No build step for frontend assets.

**External dependency:** Eclipse MAT CLI binary (path configured via `mat.cli.path` property, invoked as subprocess with 30-minute timeout).

## Key Design Decisions

- **Two-tier cache:** In-memory `ConcurrentHashMap` restored from disk (`result.json`) on startup via `@PostConstruct`
- **Async analysis:** `CachedThreadPool` executor for heap dump analysis; progress streamed to browser via SSE
- **Runtime config:** MAT's `keep_unreachable_objects` toggle changeable via REST without restart
- **File storage:** Heap dumps and results stored in subdirectories under `heapdump.directory` (default `/opt/heapdumps`)
- **Models use Lombok:** `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` throughout the model package

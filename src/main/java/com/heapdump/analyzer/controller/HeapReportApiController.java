package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.ComponentDetailParsed;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.PdfReportService;
import com.heapdump.analyzer.util.FilenameValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MAT 리포트 HTML/iframe + PDF + 로그 + thread stacks API (Phase 4B-2).
 */
@Controller
public class HeapReportApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapReportApiController.class);

    private static final Set<String> ALLOWED_REPORT_TYPES = Set.of("overview", "top_components", "suspects", "dominator_tree");

    // Dominator Refs SSE: LRU 캐시(최대 200 항목) + 파일별 동시 MAT 쿼리 제한(최대 2)
    private static final Map<String, Map<String, Object>> DOM_REF_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Map<String, Object>>(201, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 200;
                }
            });
    private static final ConcurrentHashMap<String, Semaphore> DOM_SEMAPHORES = new ConcurrentHashMap<>();

    private final HeapDumpAnalyzerService analyzerService;
    private final PdfReportService pdfReportService;

    public HeapReportApiController(HeapDumpAnalyzerService analyzerService,
                                   PdfReportService pdfReportService) {
        this.analyzerService = analyzerService;
        this.pdfReportService = pdfReportService;
    }

    /**
     * PDF 바이트 스트림. mode=download(기본) → attachment, mode=inline → iframe 미리보기용.
     */
    @GetMapping("/analyze/{filename:.+}/print-pdf")
    public ResponseEntity<byte[]> downloadPrintPdf(
            @PathVariable String filename,
            @RequestParam(name = "mode", defaultValue = "download") String mode) {
        try {
            String safe = FilenameValidator.validate(filename);
            HeapAnalysisResult result = analyzerService.getCachedResult(safe);
            if (result == null
                    || result.getAnalysisStatus() != HeapAnalysisResult.AnalysisStatus.SUCCESS) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            byte[] pdf = pdfReportService.renderPrintPdf(safe, result);

            String base = safe.replaceAll("\\.(hprof|bin|dump)(\\.gz)?$", "") + "-report.pdf";
            String ascii = base.replaceAll("[^\\x20-\\x7E]", "_");
            String utf8 = URLEncoder.encode(base, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");

            String disposition = "inline".equalsIgnoreCase(mode) ? "inline" : "attachment";

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_PDF);
            h.add(HttpHeaders.CONTENT_DISPOSITION,
                    disposition + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8);
            h.setCacheControl("no-store");
            h.add("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<>(pdf, h, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            logger.error("[PrintPdf] PDF 생성 실패 (filename={}): {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping(value = "/analyze/log/{filename:.+}", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> getMatLog(
            @PathVariable String filename,
            @RequestParam(defaultValue = "0")     int offset,
            @RequestParam(defaultValue = "10000") int limit) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult result = analyzerService.getCachedResult(filename);
        if (result == null || result.getMatLog() == null)
            return ResponseEntity.notFound().build();
        String log   = result.getMatLog();
        int    start = Math.min(offset, log.length());
        int    end   = Math.min(start + limit, log.length());
        return ResponseEntity.ok()
                .header("X-Log-Total-Length", String.valueOf(log.length()))
                .header("X-Log-Offset",       String.valueOf(start))
                .header("X-Log-Has-More",     String.valueOf(end < log.length()))
                .body(log.substring(start, end));
    }

    @GetMapping("/report/{filename:.+}/overview")
    @ResponseBody
    public ResponseEntity<String> overviewHtml(@PathVariable String filename) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return htmlResponse(r != null ? r.getOverviewHtml() : null);
    }

    @GetMapping("/report/{filename:.+}/suspects")
    @ResponseBody
    public ResponseEntity<String> suspectsHtml(@PathVariable String filename) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return htmlResponse(r != null ? r.getSuspectsHtml() : null);
    }

    @GetMapping("/report/{filename:.+}/top_components")
    @ResponseBody
    public ResponseEntity<String> topComponentsHtml(@PathVariable String filename) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return htmlResponse(r != null ? r.getTopComponentsHtml() : null);
    }

    @GetMapping("/report/{filename:.+}/component-detail")
    @ResponseBody
    public ResponseEntity<String> componentDetailHtml(
            @PathVariable String filename,
            @RequestParam String className,
            @RequestParam(defaultValue = "-1") int index) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null || r.getComponentDetailHtmlMap() == null) {
            logger.warn("[ComponentDetail] Not found: file={}, className={}, index={}, reason={}",
                    filename, className, index,
                    r == null ? "no saved result" : "componentDetailHtmlMap is null");
            return ResponseEntity.notFound().build();
        }

        logger.debug("[ComponentDetail] Request: file={}, className={}, index={}, mapKeys={}",
                filename, className, index, r.getComponentDetailHtmlMap().keySet());

        if (index >= 0) {
            String key = className + "#" + index;
            String html = r.getComponentDetailHtmlMap().get(key);
            if (html != null && !html.isBlank()) return htmlResponse(html);
        }

        for (Map.Entry<String, String> e : r.getComponentDetailHtmlMap().entrySet()) {
            String mapKey = e.getKey().contains("#")
                    ? e.getKey().substring(0, e.getKey().lastIndexOf('#'))
                    : e.getKey();
            if (mapKey.equals(className)) {
                return htmlResponse(e.getValue());
            }
        }

        logger.warn("[ComponentDetail] Detail not found: file={}, className={}, index={}, availableKeys={}",
                filename, className, index, r.getComponentDetailHtmlMap().keySet());
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/report/{filename:.+}/component-detail-parsed")
    @ResponseBody
    public ResponseEntity<ComponentDetailParsed> componentDetailParsed(
            @PathVariable String filename,
            @RequestParam String className,
            @RequestParam(defaultValue = "-1") int index) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null) return ResponseEntity.notFound().build();

        Map<String, ComponentDetailParsed> parsedMap = r.getComponentDetailParsedMap();
        if (parsedMap != null && !parsedMap.isEmpty()) {
            if (index >= 0) {
                String key = className + "#" + index;
                ComponentDetailParsed p = parsedMap.get(key);
                if (p != null) return ResponseEntity.ok(p);
            }
            for (Map.Entry<String, ComponentDetailParsed> e : parsedMap.entrySet()) {
                String mapKey = e.getKey().contains("#")
                        ? e.getKey().substring(0, e.getKey().lastIndexOf('#')) : e.getKey();
                if (mapKey.equals(className)) return ResponseEntity.ok(e.getValue());
            }
        }

        if (r.getComponentDetailHtmlMap() != null) {
            String rawHtml = null;
            if (index >= 0) {
                rawHtml = r.getComponentDetailHtmlMap().get(className + "#" + index);
            }
            if (rawHtml == null) {
                for (Map.Entry<String, String> e : r.getComponentDetailHtmlMap().entrySet()) {
                    String mapKey = e.getKey().contains("#")
                            ? e.getKey().substring(0, e.getKey().lastIndexOf('#')) : e.getKey();
                    if (mapKey.equals(className)) { rawHtml = e.getValue(); break; }
                }
            }
            if (rawHtml != null) {
                ComponentDetailParsed p =
                        analyzerService.getParser().parseComponentDetail(rawHtml, className);
                return ResponseEntity.ok(p);
            }
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping("/report/{filename:.+}/component-list")
    @ResponseBody
    public ResponseEntity<List<String>> componentList(@PathVariable String filename) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null || r.getComponentDetailHtmlMap() == null)
            return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(new ArrayList<>(r.getComponentDetailHtmlMap().keySet()));
    }

    @GetMapping(value = "/report/{filename:.+}/thread-stacks", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> threadStacks(@PathVariable String filename) {
        filename = FilenameValidator.validate(filename);
        logger.info("[ThreadStacks] Thread stacks requested for: {}", filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null || r.getThreadStacksText() == null) {
            logger.warn("[ThreadStacks] Thread stacks not found for: {}", filename);
            return ResponseEntity.notFound().build();
        }
        String text = r.getThreadStacksText();
        int lineCount = text.split("\n").length;
        logger.info("[ThreadStacks] Loaded successfully for {}: {} lines, {} bytes", filename, lineCount, text.length());
        return ResponseEntity.ok(text);
    }

    @GetMapping("/report/{filename:.+}/mat-page/{reportType}/**")
    @ResponseBody
    public ResponseEntity<byte[]> matPageFile(
            @PathVariable String filename,
            @PathVariable String reportType,
            HttpServletRequest request) {

        filename = FilenameValidator.validate(filename);

        if (!ALLOWED_REPORT_TYPES.contains(reportType)) {
            return ResponseEntity.badRequest().build();
        }

        String prefix = "/report/" + filename + "/mat-page/" + reportType + "/";
        String fullPath = request.getRequestURI();
        int idx = fullPath.indexOf(prefix);
        String entryPath = (idx >= 0) ? fullPath.substring(idx + prefix.length()) : "";

        if (entryPath.isEmpty()) {
            entryPath = "index.html";
        }

        try {
            entryPath = URLDecoder.decode(entryPath, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (entryPath.contains("..") || entryPath.startsWith("/") || entryPath.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }

        File zip = analyzerService.findReportZip(filename, reportType);
        if (zip == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] content = readZipEntryBytes(zip, entryPath);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }

        MediaType contentType = guessMediaType(entryPath);
        if (MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
            String html = new String(content, StandardCharsets.UTF_8);
            html = suppressMatProtocolLinks(html);
            content = html.getBytes(StandardCharsets.UTF_8);
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .body(content);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────

    private ResponseEntity<String> htmlResponse(String html) {
        if (html == null || html.isBlank()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html);
    }

    private byte[] readZipEntryBytes(File zip, String entryPath) {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry entry = zf.getEntry(entryPath);
            if (entry == null || entry.isDirectory()) return null;
            if (entry.getSize() > 10 * 1024 * 1024) return null; // 10MB 제한

            try (InputStream is = zf.getInputStream(entry);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } catch (IOException e) {
            logger.warn("[MatPage] Failed to read '{}' from ZIP {}: {}", entryPath, zip.getName(), e.getMessage());
            return null;
        }
    }

    private MediaType guessMediaType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".css")) return new MediaType("text", "css", StandardCharsets.UTF_8);
        if (lower.endsWith(".js")) return new MediaType("application", "javascript", StandardCharsets.UTF_8);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".svg")) return new MediaType("image", "svg+xml");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String suppressMatProtocolLinks(String html) {
        return html.replaceAll(
                "href\\s*=\\s*\"mat://[^\"]*\"",
                "href=\"javascript:void(0)\" title=\"MAT desktop 전용 링크\" "
                        + "style=\"opacity:0.4;cursor:not-allowed\"");
    }

    // ── Dominator Tree 행 클릭 시점 lazy on-demand 참조 추출 (SSE 스트리밍) ─────
    // path2gc (incoming) → SSE "incoming" 이벤트 전송 → show_retained_set (outgoing)
    // → SSE "outgoing" 이벤트 전송 → SSE "done" 이벤트 → 연결 종료.
    // 클라이언트 disconnect 즉시 abort 가능; 동시 요청은 파일별 Semaphore(2) 제한.
    @GetMapping(value = "/api/dominator-refs/{filename:.+}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter dominatorRefsSse(@PathVariable String filename,
                                        @RequestParam("address") String address) {
        SseEmitter emitter = new SseEmitter(200_000L); // 90s×2 + 여유

        // 사전 검증 (동기)
        final String safe;
        try {
            safe = FilenameValidator.validate(filename);
        } catch (IllegalArgumentException e) {
            sendDomRefError(emitter, e.getMessage());
            return emitter;
        }
        if (address == null || !address.matches("0x[0-9a-fA-F]+")) {
            sendDomRefError(emitter, "유효하지 않은 객체 주소");
            return emitter;
        }

        final String addrHex   = address.substring(2);
        final String cacheKey  = safe + ":" + address;
        final String addrFinal = address;

        // 클린업 리소스 추적 (onTimeout/onError 와 finally 블록 중 1회만 실행)
        AtomicBoolean cleaned    = new AtomicBoolean(false);
        AtomicReference<Semaphore> semRef    = new AtomicReference<>();
        AtomicReference<File>      workDirRef = new AtomicReference<>();

        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                Semaphore s = semRef.get();
                if (s != null) s.release();
                File dir = workDirRef.get();
                if (dir != null) {
                    try { analyzerService.deleteDirectoryPublic(dir); } catch (Exception ignore) {}
                }
            }
        };
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        Thread t = new Thread(() -> {
            try {
                // 캐시 HIT → 즉시 반환
                Map<String, Object> hit = DOM_REF_CACHE.get(cacheKey);
                if (hit != null) {
                    emitter.send(SseEmitter.event().name("incoming")
                            .data(hit.get("incoming"), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("outgoing")
                            .data(hit.get("outgoing"), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("done").data("{}"));
                    emitter.complete();
                    logger.info("[Dominator Refs SSE] cache-hit {} for {}", addrFinal, safe);
                    return;
                }

                // 분석 결과 캐시 + 결과 디렉토리 확인
                HeapAnalysisResult cachedResult = analyzerService.getCachedResult(safe);
                if (cachedResult == null) {
                    sendDomRefError(emitter, "분석 결과 캐시 없음 — 재분석 필요");
                    return;
                }
                File resultDir = analyzerService.resultDirectoryPublic(safe);
                if (!resultDir.exists()) {
                    sendDomRefError(emitter, "결과 디렉토리 없음");
                    return;
                }

                // 파일별 동시 MAT 쿼리 Semaphore (최대 2)
                Semaphore sem = DOM_SEMAPHORES.computeIfAbsent(safe, k -> new Semaphore(2));
                if (!sem.tryAcquire(10, TimeUnit.SECONDS)) {
                    sendDomRefError(emitter, "서버가 바쁩니다 — 잠시 후 재시도하세요");
                    return;
                }
                semRef.set(sem);

                // sourceHprof 탐색: dumpfiles > tmp > .gz 압축해제
                String base      = safe.replaceAll("\\.(hprof|bin|dump)(\\.gz)?$", "");
                String dumpfiles = analyzerService.getHeapDumpDirectory() + File.separator + "dumpfiles";
                File originalHprof = new File(dumpfiles, safe);
                File tmpHprof      = new File(analyzerService.getHeapDumpDirectory() + File.separator + "tmp",
                                               base + ".hprof");
                File sourceHprof   = null;
                if (originalHprof.exists()) {
                    sourceHprof = originalHprof;
                } else if (tmpHprof.exists() && tmpHprof.length() > 0) {
                    sourceHprof = tmpHprof;
                } else {
                    File gzFile = new File(dumpfiles, safe + ".gz");
                    if (gzFile.exists()) {
                        if (!tmpHprof.getParentFile().exists()) tmpHprof.getParentFile().mkdirs();
                        logger.info("[Dominator Refs SSE] Decompressing {} → {} (1-time)",
                                gzFile.getName(), tmpHprof.getName());
                        try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(
                                java.nio.file.Files.newInputStream(gzFile.toPath()));
                             java.io.OutputStream os = java.nio.file.Files.newOutputStream(tmpHprof.toPath())) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = gis.read(buf)) != -1) os.write(buf, 0, n);
                        }
                        sourceHprof = tmpHprof;
                    } else {
                        sendDomRefError(emitter, "원본 hprof 파일을 찾을 수 없습니다");
                        return;
                    }
                }

                // 요청별 격리 temp 디렉토리
                File refWorkDir = new File(resultDir,
                        ".dom_ref_" + addrHex + "_" + System.currentTimeMillis());
                refWorkDir.mkdirs();
                workDirRef.set(refWorkDir);

                // index/threads 파일 복사
                File[] idxFiles = resultDir.listFiles((d, n) ->
                        n.startsWith(base + ".") && (n.endsWith(".index") || n.endsWith(".threads")));
                if (idxFiles != null) {
                    for (File f : idxFiles) {
                        java.nio.file.Files.copy(f.toPath(),
                                new File(refWorkDir, f.getName()).toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                // hprof 심볼릭 링크 (실패 시 copy 폴백)
                File workHprof = new File(refWorkDir, base + ".hprof");
                try {
                    java.nio.file.Files.createSymbolicLink(workHprof.toPath(), sourceHprof.toPath());
                } catch (IOException linkErr) {
                    logger.warn("[Dominator Refs SSE] symlink 실패, copy 폴백: {}", linkErr.getMessage());
                    java.nio.file.Files.copy(sourceHprof.toPath(), workHprof.toPath());
                }

                File queryZip = new File(refWorkDir, base + "_Query.zip");

                // 1) Incoming: path to GC roots
                analyzerService.runMatSingleQuery(workHprof.getAbsolutePath(), refWorkDir,
                        "path2gc " + addrFinal, 90L);
                List<com.heapdump.analyzer.model.DominatorRefEntry> incoming = queryZip.exists()
                        ? analyzerService.getParser().parseRefZipPath2gc(queryZip, 50)
                        : Collections.emptyList();
                if (queryZip.exists()) queryZip.delete();

                // incoming 먼저 전송 (클라이언트가 즉시 렌더)
                emitter.send(SseEmitter.event().name("incoming")
                        .data(incoming, MediaType.APPLICATION_JSON));

                // 2) Outgoing: retained set
                analyzerService.runMatSingleQuery(workHprof.getAbsolutePath(), refWorkDir,
                        "show_retained_set " + addrFinal, 90L);
                List<com.heapdump.analyzer.model.DominatorRefEntry> outgoing = queryZip.exists()
                        ? analyzerService.getParser().parseRefZipRetained(queryZip, 50)
                        : Collections.emptyList();

                emitter.send(SseEmitter.event().name("outgoing")
                        .data(outgoing, MediaType.APPLICATION_JSON));

                // 캐시에 저장
                Map<String, Object> result = new HashMap<>();
                result.put("incoming", incoming);
                result.put("outgoing", outgoing);
                DOM_REF_CACHE.put(cacheKey, result);

                emitter.send(SseEmitter.event().name("done").data("{}"));
                emitter.complete();
                logger.info("[Dominator Refs SSE] {} incoming={}, outgoing={} for {}",
                        addrFinal, incoming.size(), outgoing.size(), safe);

            } catch (IllegalStateException ise) {
                // 클라이언트 disconnect (정상)
                logger.debug("[Dominator Refs SSE] client disconnected ({}): {}", addrFinal, ise.getMessage());
            } catch (Exception e) {
                logger.error("[Dominator Refs SSE] 추출 실패 (filename={}, addr={}): {}",
                        filename, addrFinal, e.getMessage(), e);
                try { sendDomRefError(emitter, "참조 추출 실패: " + e.getMessage()); }
                catch (Exception ignore) {}
            } finally {
                cleanup.run();
            }
        });
        t.setDaemon(true);
        t.setName("dom-refs-" + addrHex);
        t.start();

        return emitter;
    }

    private static void sendDomRefError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("refs-error")
                    .data(Map.of("error", msg), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ignore) {}
    }
}

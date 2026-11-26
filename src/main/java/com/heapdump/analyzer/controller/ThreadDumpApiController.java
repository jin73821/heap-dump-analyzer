package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.service.LlmRateLimitService;
import com.heapdump.analyzer.service.ThreadDumpAnalyzerService;
import com.heapdump.analyzer.service.ThreadDumpPdfReportService;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpException;
import com.heapdump.analyzer.util.AuthUtil;
import com.heapdump.analyzer.util.DbOutageDetector;
import com.heapdump.analyzer.util.FormatUtils;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 스레드 덤프 JSON API + PDF 리포트 (2026-11-26). 경로는 {@code /api/thread-dump/**} — SecurityConfig 의 일반 규칙
 * (인증 사용자·CSRF 면제)에 해당해 별도 설정이 없다.
 *
 * <p>오류 응답은 {@code {success:false, code, error}} 3필드. 감사 로그는 {@code [ThreadDump] action=… file=… by=} 이고
 * 변경·다운로드·PDF 핸들러는 전부 {@link Principal} 을 받는다({@code FileOpAuditActorTest} 가 검사).
 */
@RestController
public class ThreadDumpApiController {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDumpApiController.class);

    private final ThreadDumpAnalyzerService service;
    private final ThreadDumpPdfReportService pdfReportService;

    /** 힙 덤프의 스레드 분석(2.7.1) — 선택 주입(없으면 힙 추출 경로가 503 으로 답한다). 생성자는 테스트를 위해 그대로 둔다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.heapdump.analyzer.service.HeapThreadService heapThreads;

    public ThreadDumpApiController(ThreadDumpAnalyzerService service, ThreadDumpPdfReportService pdfReportService) {
        this.service = service;
        this.pdfReportService = pdfReportService;
    }

    private static Map<String, Object> err(String code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("error", msg);
        return m;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(err("BAD_REQUEST", e.getMessage()));
    }

    /** 지금 상태로는 할 수 없는 요청(분석 중인 파일을 묶기 등). 예상 못 한 IllegalStateException 은 여전히 500(내용 숨김)이다. */
    @ExceptionHandler(ThreadDumpAnalyzerService.BusyException.class)
    public ResponseEntity<Map<String, Object>> conflict(ThreadDumpAnalyzerService.BusyException e) {
        logger.warn("[ThreadDump] action=request-rejected status=409 — {}", e.getMessage());
        return ResponseEntity.status(409).body(err("CONFLICT", e.getMessage()));
    }

    /** 분석 실패 코드가 붙은 예외(주로 힙 덤프 추출) — 코드로 HTTP 상태를 정한다. */
    @ExceptionHandler(ThreadDumpException.class)
    public ResponseEntity<Map<String, Object>> analysisError(ThreadDumpException e) {
        int status = switch (e.code()) {
            case "HEAP_NOT_FOUND", "HEAP_THREADS_UNAVAILABLE", "FILE_MISSING" -> 404;
            case "HEAP_NOT_ANALYZED" -> 409;
            case "IO_ERROR", "INTERNAL" -> 500;
            default -> 400;
        };
        return ResponseEntity.status(status).body(err(e.code(), e.getMessage()));
    }

    /**
     * 그 밖의 예외 — 스택과 함께 남기고 같은 3필드 모양으로 내린다.
     * 스프링이 상태를 정해 둔 예외(누락 파라미터 400·업로드 크기 초과 413 등)는 그 상태를 지킨다 —
     * 전부 500 으로 바꾸면 클라이언트 잘못이 서버 오류로 보인다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        if (e instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            logger.warn("[ThreadDump] action=request-rejected status={} — {}", status, e.getMessage());
            return ResponseEntity.status(status).body(err(status == 413 ? "TOO_LARGE" : "BAD_REQUEST",
                    status == 413 ? "업로드 크기 상한을 넘는 파일입니다." : "요청을 처리할 수 없습니다: " + e.getMessage()));
        }
        if (DbOutageDetector.isDbOutage(e)) {
            // DB 장애는 화면이 전용 배너로 알린다 — 전역 계약(503 + Retry-After + DB_UNAVAILABLE)을 그대로 지킨다
            logger.warn("[ThreadDump] action=request-failed reason=DB_UNAVAILABLE — {}", DbOutageDetector.describe(e));
            Map<String, Object> body = err(DbAwareErrorController.CODE, DbAwareErrorController.MESSAGE);
            body.put("retryAfter", 30);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30").body(body);
        }
        logger.error("[ThreadDump] action=request-failed — {}", e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(err("INTERNAL", "요청을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도하세요."));
    }

    // ── 업로드 ──────────────────────────────────────────────────

    @PostMapping("/api/thread-dump/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("threadDumpFile") MultipartFile file,
                                                      @RequestParam(value = "serverName", required = false) String serverName,
                                                      Principal principal) {
        String who = AuthUtil.who(principal);
        if (file == null || file.isEmpty()) {
            logger.warn("[ThreadDump] action=upload-rejected reason=EMPTY by={}", who);
            return ResponseEntity.badRequest().body(err("EMPTY", "스레드 덤프 파일이 필요합니다."));
        }
        String name = file.getOriginalFilename();
        try {
            ThreadDumpAnalysisEntity e = service.storeUpload(name, file.getSize(), file::transferTo, who, serverName);
            logger.info("[ThreadDump] action=upload file={} size={} by={}", e.getFilename(), e.getFileSize(), who);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            ok.put("filename", e.getFilename());
            ok.put("size", e.getFileSize());
            return ResponseEntity.ok(ok);
        } catch (ThreadDumpAnalyzerService.UploadRejectedException ex) {
            logger.warn("[ThreadDump] action=upload-rejected file={} reason={} size={} by={}", name, ex.code(), file.getSize(), who);
            return ResponseEntity.status(ex.httpStatus()).body(err(ex.code(), ex.getMessage()));
        } catch (IOException ex) {
            logger.error("[ThreadDump] action=upload-failed file={} reason=IO_ERROR by={} — {}", name, who, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(err("IO_ERROR", "파일 저장 중 오류: " + ex.getMessage()));
        }
    }

    // ── 분석 ────────────────────────────────────────────────────

    @PostMapping("/api/thread-dump/analyze/{filename:.+}")
    public ResponseEntity<Map<String, Object>> analyze(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        if (!service.fileOf(safe).isFile()) return ResponseEntity.status(404).body(err("NOT_FOUND", "스레드 덤프 파일이 없습니다: " + safe));
        Optional<ThreadDumpAnalysisEntity> existing = service.find(safe);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        if (existing.isPresent() && ThreadDumpAnalysisEntity.STATUS_SUCCESS.equals(existing.get().getStatus()) && !service.isAnalyzing(safe)) {
            ok.put("status", ThreadDumpAnalysisEntity.STATUS_SUCCESS);
            return ResponseEntity.ok(ok);
        }
        service.submitAnalysis(safe, AuthUtil.who(principal));
        ok.put("status", ThreadDumpAnalysisEntity.STATUS_ANALYZING);
        return ResponseEntity.ok(ok);
    }

    @PostMapping("/api/thread-dump/reanalyze/{filename:.+}")
    public ResponseEntity<Map<String, Object>> reanalyze(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        String who = AuthUtil.who(principal);
        if (!service.fileOf(safe).isFile()) return ResponseEntity.status(404).body(err("NOT_FOUND", "스레드 덤프 파일이 없습니다: " + safe));
        if (service.isAnalyzing(safe)) return ResponseEntity.status(409).body(err("ANALYZING", "이미 분석이 진행 중입니다."));
        logger.info("[ThreadDump] action=reanalyze file={} by={}", safe, who);
        service.submitAnalysis(safe, who);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("status", ThreadDumpAnalysisEntity.STATUS_ANALYZING);
        return ResponseEntity.ok(ok);
    }

    @PostMapping("/api/thread-dump/cancel/{filename:.+}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        boolean c = service.cancel(safe, AuthUtil.who(principal));
        return ResponseEntity.ok(Map.of("success", true, "cancelled", c));
    }

    @GetMapping("/api/thread-dump/{filename:.+}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String filename) {
        String safe = service.validateFilename(filename);
        Map<String, Object> m = service.getStatus(safe);
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /** 저장된 결과 JSON 을 그대로 내린다. 화면이 파싱에 실패하면(손상) 재분석을 안내한다. */
    @GetMapping("/api/thread-dump/{filename:.+}/result")
    public ResponseEntity<?> result(@PathVariable String filename) {
        String safe = service.validateFilename(filename);
        Optional<String> json = service.loadResultJson(safe);
        if (json.isEmpty()) return ResponseEntity.status(404).body(err("NO_RESULT", "분석 결과가 없습니다: " + safe));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.get());
    }

    @GetMapping("/api/thread-dump/history")
    public ResponseEntity<List<ThreadDumpAnalysisEntity>> history() {
        return ResponseEntity.ok(service.history());
    }

    // ── 파일 ────────────────────────────────────────────────────

    @GetMapping("/api/thread-dump/download/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        String who = AuthUtil.who(principal);
        File file = service.fileOf(safe);
        if (!file.isFile()) {
            logger.warn("[ThreadDump] action=download result=FAIL file={} reason=NOT_FOUND by={}", safe, who);
            return ResponseEntity.notFound().build();
        }
        logger.info("[ThreadDump] action=download file={} size={} by={}", safe, file.length(), who);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, HttpHeaderValues.attachment(safe))   // 함정 71
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/api/thread-dump/{filename:.+}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String filename,
                                                      @RequestParam(defaultValue = "true") boolean deleteFile,
                                                      Principal principal) {
        String safe = service.validateFilename(filename);
        service.delete(safe, deleteFile, AuthUtil.who(principal));
        return ResponseEntity.ok(Map.of("success", true, "filename", safe));
    }

    /** 덤프를 받은 서버 이름(선택) — body {@code serverName}, 빈 값이면 지운다. 리포트 머리말에 쓴다. */
    @PostMapping("/api/thread-dump/{filename:.+}/server-name")
    public ResponseEntity<Map<String, Object>> serverName(@PathVariable String filename, @RequestBody(required = false) Map<String, String> body,
                                                          Principal principal) {
        String safe = service.validateFilename(filename);
        Optional<ThreadDumpAnalysisEntity> e = service.find(safe);
        if (e.isEmpty()) return ResponseEntity.status(404).body(err("NOT_FOUND", "스레드 덤프 기록이 없습니다: " + safe));
        ThreadDumpAnalysisEntity saved = service.updateServerName(e.get(), body == null ? null : body.get("serverName"));
        logger.info("[ThreadDump] action=server-name file={} server={} by={}", safe, saved.getServerName(), AuthUtil.who(principal));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("serverName", saved.getServerName());
        return ResponseEntity.ok(ok);
    }

    // ── AI ──────────────────────────────────────────────────────

    @PostMapping("/api/thread-dump/{filename:.+}/ai-analyze")
    public ResponseEntity<Map<String, Object>> aiAnalyze(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        String who = AuthUtil.who(principal);
        if (!service.isLlmEnabled()) {
            logger.info("[ThreadDump-AI] action=analyze result=REJECTED file={} reason=LLM_DISABLED by={}", safe, who);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", false);
            e.put("errorCode", "LLM_DISABLED");
            e.put("error", "AI 분석이 비활성화되어 있습니다. 설정에서 LLM 을 활성화하세요.");
            return ResponseEntity.ok(e);
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> result;
        try {
            result = service.analyzeWithAi(safe);
        } catch (RuntimeException ex) {
            logger.error("[ThreadDump-AI] action=analyze result=FAIL file={} reason=EXCEPTION elapsed={}ms by={} — {}",
                    safe, System.currentTimeMillis() - t0, who, ex.toString(), ex);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", false);
            e.put("errorCode", "AI_ERROR");
            e.put("error", "AI 분석 중 오류가 발생했습니다: " + ex.getMessage());
            return ResponseEntity.ok(e);
        }
        long elapsed = System.currentTimeMillis() - t0;
        boolean success = result != null && Boolean.TRUE.equals(result.get("success"));
        if (result == null) result = new LinkedHashMap<>(Map.of("success", false, "errorCode", "AI_ERROR", "error", "AI 응답이 비어 있습니다."));
        Object dataObj = result.get("data");
        if (success) {
            String severity = dataObj instanceof Map ? String.valueOf(((Map<?, ?>) dataObj).get("severity")) : null;
            logger.info("[ThreadDump-AI] action=analyze result=OK file={} severity={} elapsed={}ms model={} by={}", safe, severity, elapsed, result.get("model"), who);
            Map<String, Object> toStore = new LinkedHashMap<>();
            toStore.put("model", result.get("model"));
            toStore.put("latencyMs", result.get("latencyMs"));
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> dm = (Map<String, Object>) dataObj;
                toStore.putAll(dm);
            }
            try {
                service.saveAiInsight(ThreadDumpAnalyzerService.insightKey(safe), toStore);
                result.put("saved", true);
                result.put("savedTo", "database");
                if (toStore.get("analysedAt") != null) result.put("analysedAt", toStore.get("analysedAt"));
            } catch (Exception ex) {
                // 해석은 얻었다 — 저장만 실패했으므로 화면에는 보여 주고 다시 저장할 수 있게 원본을 돌려준다
                logger.error("[ThreadDump-AI] action=analyze result=SAVE_FAILED file={} by={} — {}", safe, who, ex.getMessage(), ex);
                result.put("saved", false);
                result.put("saveError", ex.getMessage());
                result.put("saveErrorCode", "SAVE_FAILED");
                result.put("retryPayload", toStore);
            }
        } else {
            logger.warn("[ThreadDump-AI] action=analyze result=FAIL file={} errorCode={} elapsed={}ms by={}", safe, result.get("errorCode"), elapsed, who);
        }
        return LlmRateLimitService.toResponse(result);
    }

    @GetMapping("/api/thread-dump/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> getAiInsight(@PathVariable String filename) {
        String safe = service.validateFilename(filename);
        Map<String, Object> insight = service.loadAiInsight(ThreadDumpAnalyzerService.insightKey(safe));
        if (insight == null) return ResponseEntity.ok(Map.of("found", false));
        insight.put("found", true);
        insight.put("savedTo", "database");
        return ResponseEntity.ok(insight);
    }

    @DeleteMapping("/api/thread-dump/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> deleteAiInsight(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        boolean deleted = service.deleteAiInsight(ThreadDumpAnalyzerService.insightKey(safe));
        logger.info("[ThreadDump-AI] action=delete-insight file={} deleted={} by={}", safe, deleted, AuthUtil.who(principal));
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted));
    }

    // ── PDF 리포트 ──────────────────────────────────────────────
    // 힙 HeapReportApiController.downloadPrintPdf · 코어 CoreDumpApiController.printPdf 와 같은 규칙:
    // mode=download(기본) → attachment · mode=inline → 리포트 탭 iframe 미리보기.
    // download 성공은 INFO, inline 미리보기 성공은 DEBUG, 실패는 항상 남긴다.

    @GetMapping("/thread-dump/analyze/{filename:.+}/print-pdf")
    public ResponseEntity<byte[]> printPdf(@PathVariable String filename,
                                           @RequestParam(name = "mode", defaultValue = "download") String mode,
                                           Principal principal) {
        boolean inline = "inline".equalsIgnoreCase(mode);
        String action = inline ? "pdf-preview" : "pdf-download";
        String who = AuthUtil.who(principal);
        long t0 = System.currentTimeMillis();
        try {
            String safe = service.validateFilename(filename);
            ThreadDumpResult result = service.loadResult(safe).orElse(null);
            if (result == null) {
                logger.warn("[ThreadDump-PDF] action={} result=FAIL file={} reason=NO_RESULT by={}", action, safe, who);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            Map<String, Object> ai = loadAiQuietly(safe);
            byte[] pdf = pdfReportService.renderPdf(safe, service.find(safe).orElse(null), result, ai, heapLinkQuietly(safe));
            long elapsed = System.currentTimeMillis() - t0;
            if (inline) {
                logger.debug("[ThreadDump-PDF] action={} result=OK file={} size={} elapsed={}ms by={}", action, safe, FormatUtils.formatBytes(pdf.length), elapsed, who);
            } else {
                logger.info("[ThreadDump-PDF] action={} result=OK file={} size={} elapsed={}ms by={}", action, safe, FormatUtils.formatBytes(pdf.length), elapsed, who);
            }
            String base = safe.replaceAll("(?i)\\.(txt|log|out|tdump|dump|gz)$", "").replaceAll("(?i)\\.(txt|log|out|tdump|dump)$", "")
                    + "-thread-dump-report.pdf";
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_PDF);
            h.add(HttpHeaders.CONTENT_DISPOSITION, HttpHeaderValues.contentDisposition(inline ? "inline" : "attachment", base));
            h.setCacheControl("no-store");
            h.add("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<>(pdf, h, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            logger.warn("[ThreadDump-PDF] action={} result=FAIL file={} reason=INVALID_FILENAME by={} — {}", action, filename, who, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            logger.error("[ThreadDump-PDF] action={} result=FAIL file={} reason=RENDER_ERROR elapsed={}ms by={} — {}",
                    action, filename, System.currentTimeMillis() - t0, who, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 힙 덤프 연결 정보를 못 읽어도 리포트는 나가야 한다 — 그 칸만 비운다. */
    Map<String, Object> heapLinkQuietly(String safe) {
        try {
            return service.heapLinkView(safe);
        } catch (RuntimeException e) {
            logger.warn("[ThreadDump-PDF] 힙 덤프 연결 조회 실패 file={} — {}", safe, e.toString());
            return null;
        }
    }

    // ── 이어 붙이기(2.7.1) ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> body) {
        Object v = body == null ? null : body.get("filenames");
        if (!(v instanceof List<?> l)) throw new IllegalArgumentException("filenames 가 필요합니다.");
        List<String> out = new java.util.ArrayList<>();
        for (Object o : l) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    /** 이어 붙이기 미리보기 — 순서(시각순/고른 순)·파일별 스냅샷 수·문제. 파일·기록을 바꾸지 않는다. */
    @PostMapping("/api/thread-dump/combine/preview")
    public ResponseEntity<Map<String, Object>> combinePreview(@RequestBody Map<String, Object> body, Principal principal) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>(service.combinePreview(names(body)));
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /** 이어 붙여 분석 — 대표(순서상 첫 파일)에 묶고 분석을 시작한다. */
    @PostMapping("/api/thread-dump/combine")
    public ResponseEntity<Map<String, Object>> combine(@RequestBody Map<String, Object> body, Principal principal) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>(service.combine(names(body), AuthUtil.who(principal)));
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /** 묶음 해제 — 구성 파일은 단독으로 돌아가고 대표의 묶음 결과는 지워진다. */
    @PostMapping("/api/thread-dump/{filename:.+}/ungroup")
    public ResponseEntity<Map<String, Object>> ungroup(@PathVariable String filename, Principal principal) {
        String safe = service.validateFilename(filename);
        service.ungroup(safe, AuthUtil.who(principal));
        return ResponseEntity.ok(Map.of("success", true, "filename", safe));
    }

    /** 업로드 직후 이어 붙이기 제안 — 같은 JVM 실행으로 보이는 파일 묶음. 묶지는 않는다. */
    @PostMapping("/api/thread-dump/group-suggestion")
    public ResponseEntity<Map<String, Object>> groupSuggestion(@RequestBody Map<String, Object> body, Principal principal) {
        List<Map<String, Object>> groups = service.groupSuggestion(names(body));
        logger.info("[ThreadDump] action=group-suggestion groups={} by={}", groups.size(), AuthUtil.who(principal));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("groups", groups);
        return ResponseEntity.ok(m);
    }

    // ── 힙 덤프 연계(2.7.1, 수동) ─────────────────────────────────

    @GetMapping("/api/thread-dump/{filename:.+}/heap-candidates")
    public ResponseEntity<Map<String, Object>> heapCandidates(@PathVariable String filename) {
        String safe = service.validateFilename(filename);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("available", service.heapLinkAvailable());
        m.put("candidates", service.heapCandidates(safe));
        return ResponseEntity.ok(m);
    }

    @GetMapping("/api/thread-dump/{filename:.+}/heap-link")
    public ResponseEntity<Map<String, Object>> heapLink(@PathVariable String filename) {
        String safe = service.validateFilename(filename);
        Map<String, Object> m = new LinkedHashMap<>(service.heapLinkView(safe));
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /** 힙 덤프 연결(body {@code heapFilename}) · 해제(빈 값). */
    @PostMapping("/api/thread-dump/{filename:.+}/heap-link")
    public ResponseEntity<Map<String, Object>> setHeapLink(@PathVariable String filename, @RequestBody(required = false) Map<String, String> body,
                                                           Principal principal) {
        String safe = service.validateFilename(filename);
        service.linkHeap(safe, body == null ? null : body.get("heapFilename"), AuthUtil.who(principal));
        Map<String, Object> m = new LinkedHashMap<>(service.heapLinkView(safe));
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    // ── 힙 덤프의 스레드 분석(2.7.1) ─────────────────────────────

    private com.heapdump.analyzer.service.HeapThreadService heap() {
        if (heapThreads == null) throw new ThreadDumpException("HEAP_THREADS_UNAVAILABLE", "힙 덤프 스레드 분석을 사용할 수 없습니다.");
        return heapThreads;
    }

    @GetMapping("/api/thread-dump/heap/{filename:.+}/result")
    public ResponseEntity<String> heapResult(@PathVariable String filename) {
        var l = heap().load(heap().validateFilename(filename));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(l.json());
    }

    /** 힙 결과 화면 카드 — 요약 + 이 힙 덤프에 연결된 스레드 덤프. 실패해도 200(available=false)으로 답한다. */
    @GetMapping("/api/thread-dump/heap/{filename:.+}/summary")
    public ResponseEntity<Map<String, Object>> heapSummary(@PathVariable String filename) {
        String safe = heap().validateFilename(filename);
        Map<String, Object> m = new LinkedHashMap<>(heap().summary(safe));
        m.put("linkedThreadDumps", service.linkedToHeap(safe));
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/api/thread-dump/heap/{filename:.+}/ai-analyze")
    public ResponseEntity<Map<String, Object>> heapAiAnalyze(@PathVariable String filename, Principal principal) {
        String safe = heap().validateFilename(filename);
        String who = AuthUtil.who(principal);
        if (!heap().isLlmEnabled()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", false);
            e.put("errorCode", "LLM_DISABLED");
            e.put("error", "AI 분석이 비활성화되어 있습니다. 설정에서 LLM 을 활성화하세요.");
            return ResponseEntity.ok(e);
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> result;
        try {
            result = heap().analyzeWithAi(safe);
        } catch (RuntimeException ex) {
            logger.error("[HeapThreads-AI] action=analyze result=FAIL file={} reason=EXCEPTION by={} — {}", safe, who, ex.toString(), ex);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", false);
            e.put("errorCode", "AI_ERROR");
            e.put("error", "AI 분석 중 오류가 발생했습니다: " + ex.getMessage());
            return ResponseEntity.ok(e);
        }
        if (result == null) result = new LinkedHashMap<>(Map.of("success", false, "errorCode", "AI_ERROR", "error", "AI 응답이 비어 있습니다."));
        long elapsed = System.currentTimeMillis() - t0;
        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> toStore = new LinkedHashMap<>();
            toStore.put("model", result.get("model"));
            toStore.put("latencyMs", result.get("latencyMs"));
            if (result.get("data") instanceof Map<?, ?> dm) dm.forEach((k, v) -> toStore.put(String.valueOf(k), v));
            try {
                heap().saveAiInsight(com.heapdump.analyzer.service.HeapThreadService.insightKey(safe), toStore);
                result.put("saved", true);
            } catch (Exception ex) {
                logger.error("[HeapThreads-AI] action=analyze result=SAVE_FAILED file={} by={} — {}", safe, who, ex.getMessage(), ex);
                result.put("saved", false);
                result.put("saveError", ex.getMessage());
                result.put("saveErrorCode", "SAVE_FAILED");
                result.put("retryPayload", toStore);
            }
            logger.info("[HeapThreads-AI] action=analyze result=OK file={} elapsed={}ms by={}", safe, elapsed, who);
        } else {
            logger.warn("[HeapThreads-AI] action=analyze result=FAIL file={} errorCode={} elapsed={}ms by={}", safe, result.get("errorCode"), elapsed, who);
        }
        return LlmRateLimitService.toResponse(result);
    }

    @GetMapping("/api/thread-dump/heap/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> heapGetAiInsight(@PathVariable String filename) {
        String safe = heap().validateFilename(filename);
        Map<String, Object> insight = heap().loadAiInsight(com.heapdump.analyzer.service.HeapThreadService.insightKey(safe));
        if (insight == null) return ResponseEntity.ok(Map.of("found", false));
        insight.put("found", true);
        return ResponseEntity.ok(insight);
    }

    @DeleteMapping("/api/thread-dump/heap/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> heapDeleteAiInsight(@PathVariable String filename, Principal principal) {
        String safe = heap().validateFilename(filename);
        boolean deleted = heap().deleteAiInsight(com.heapdump.analyzer.service.HeapThreadService.insightKey(safe));
        logger.info("[HeapThreads-AI] action=delete-insight file={} deleted={} by={}", safe, deleted, AuthUtil.who(principal));
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted));
    }

    @GetMapping("/thread-dump/heap/{filename:.+}/print-pdf")
    public ResponseEntity<byte[]> heapPrintPdf(@PathVariable String filename,
                                               @RequestParam(name = "mode", defaultValue = "download") String mode,
                                               Principal principal) {
        boolean inline = "inline".equalsIgnoreCase(mode);
        String action = inline ? "pdf-preview" : "pdf-download";
        String who = AuthUtil.who(principal);
        long t0 = System.currentTimeMillis();
        try {
            String safe = heap().validateFilename(filename);
            var l = heap().load(safe);
            Map<String, Object> ai = null;
            try {
                ai = heap().loadAiInsight(com.heapdump.analyzer.service.HeapThreadService.insightKey(safe));
            } catch (RuntimeException e) {
                logger.warn("[HeapThreads-PDF] AI 해석 조회 실패 file={} — {}", safe, e.toString());
            }
            byte[] pdf = pdfReportService.renderPdf(safe, null, l.result(), ai, null);
            long elapsed = System.currentTimeMillis() - t0;
            if (inline) logger.debug("[HeapThreads-PDF] action={} result=OK file={} size={} elapsed={}ms by={}", action, safe, FormatUtils.formatBytes(pdf.length), elapsed, who);
            else logger.info("[HeapThreads-PDF] action={} result=OK file={} size={} elapsed={}ms by={}", action, safe, FormatUtils.formatBytes(pdf.length), elapsed, who);
            String base = safe.replaceAll("(?i)\\.(hprof|bin|dump|dmp)(\\.gz)?$", "") + "-thread-report.pdf";
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_PDF);
            h.add(HttpHeaders.CONTENT_DISPOSITION, HttpHeaderValues.contentDisposition(inline ? "inline" : "attachment", base));
            h.setCacheControl("no-store");
            h.add("X-Content-Type-Options", "nosniff");
            return new ResponseEntity<>(pdf, h, HttpStatus.OK);
        } catch (ThreadDumpException e) {
            logger.warn("[HeapThreads-PDF] action={} result=FAIL file={} reason={} by={} — {}", action, filename, e.code(), who, e.getMessage());
            return ResponseEntity.status("HEAP_NOT_ANALYZED".equals(e.code()) ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND).build();
        } catch (IllegalArgumentException e) {
            logger.warn("[HeapThreads-PDF] action={} result=FAIL file={} reason=INVALID_FILENAME by={} — {}", action, filename, who, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            logger.error("[HeapThreads-PDF] action={} result=FAIL file={} reason=RENDER_ERROR elapsed={}ms by={} — {}",
                    action, filename, System.currentTimeMillis() - t0, who, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** AI 해석을 못 읽어도 리포트는 나가야 한다 — 그 칸만 "미실행" 으로 비운다. */
    Map<String, Object> loadAiQuietly(String safe) {
        try {
            return service.loadAiInsight(ThreadDumpAnalyzerService.insightKey(safe));
        } catch (RuntimeException e) {
            logger.warn("[ThreadDump-PDF] AI 해석 조회 실패 file={} — {}", safe, e.toString());
            return null;
        }
    }
}

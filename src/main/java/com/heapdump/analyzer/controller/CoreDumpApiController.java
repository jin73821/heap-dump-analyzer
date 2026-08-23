package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.CoreDumpPdfReportService;
import com.heapdump.analyzer.service.CoreDumpSysrootService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.Future;

@RestController
public class CoreDumpApiController {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpApiController.class);

    private final CoreDumpAnalyzerService analyzerService;
    private final CoreDumpSysrootService sysrootService;
    private final CoreDumpPdfReportService pdfReportService;
    private final HeapDumpConfig config;

    public CoreDumpApiController(CoreDumpAnalyzerService analyzerService,
                                 CoreDumpSysrootService sysrootService,
                                 CoreDumpPdfReportService pdfReportService,
                                 HeapDumpConfig config) {
        this.analyzerService = analyzerService;
        this.sysrootService = sysrootService;
        this.pdfReportService = pdfReportService;
        this.config = config;
    }

    // ── 업로드 ────────────────────────────────────────────────────

    @PostMapping("/api/core-dump/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("coreFile") MultipartFile coreFile,
            @RequestParam(value = "execFile", required = false) MultipartFile execFile,
            Principal principal) {

        if (coreFile == null || coreFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", "코어 덤프 파일이 필요합니다."));
        }

        String originalName = coreFile.getOriginalFilename();
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(originalName);
        } catch (IllegalArgumentException e) {
            logger.warn("[CoreDump] 파일명 검증 실패: originalName='{}', reason='{}', by={}",
                    originalName, e.getMessage(), who);
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", e.getMessage()));
        }

        try {
            File dumpFilesDir = analyzerService.dumpFilesDir();
            dumpFilesDir.mkdirs();

            // 코어 파일 저장
            File dest = new File(dumpFilesDir, safe);
            coreFile.transferTo(dest);
            logger.info("[CoreDump] 코어 파일 업로드: {} ({} bytes) by {}", safe, dest.length(), who);

            // 실행 파일 저장 (선택) — 원본 파일명 유지 + 페어링 등록
            String executableName = null;
            if (execFile != null && !execFile.isEmpty()) {
                String origExec = execFile.getOriginalFilename();
                String safeExec = (origExec != null && !origExec.isBlank())
                        ? analyzerService.validateCoreDumpFilename(origExec)
                        : safe + ".exec";
                File execDest = new File(dumpFilesDir, safeExec);
                execFile.transferTo(execDest);
                analyzerService.saveExecPairing(safe, safeExec);
                executableName = safeExec;
                logger.info("[CoreDump] 실행 파일 업로드: {} ({} bytes) by {}",
                        executableName, execDest.length(), who);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("filename", safe);
            response.put("executableName", executableName);
            return ResponseEntity.ok(response);

        } catch (java.io.IOException e) {
            logger.error("[CoreDump] 업로드 I/O 실패: filename='{}', by={}, reason={}",
                    safe, who, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "파일 저장 중 오류가 발생했습니다: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[CoreDump] 업로드 실패: filename='{}', by={}", safe, who, e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "업로드 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    // ── 다운로드 ──────────────────────────────────────────────────

    @GetMapping("/api/core-dump/download/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String filename) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
        File file = new File(analyzerService.dumpFilesDir(), safe);
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safe + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }

    // ── PDF 크래시 리포트 ─────────────────────────────────────────
    // 힙덤프 HeapReportApiController.downloadPrintPdf 와 1:1 대칭.
    // mode=download(기본) → attachment, mode=inline → 리포트 탭 iframe 미리보기용.
    // rev 가 있으면 보존된 과거 리비전으로 리포트 생성 (blank = 현재 결과).

    @GetMapping("/core-dump/analyze/{filename:.+}/print-pdf")
    public ResponseEntity<byte[]> printPdf(
            @PathVariable String filename,
            @RequestParam(name = "mode", defaultValue = "download") String mode,
            @RequestParam(name = "rev", required = false) String rev) {
        try {
            String safe = analyzerService.validateCoreDumpFilename(filename);
            boolean viewingRevision = rev != null && !rev.isBlank();
            Optional<CoreDumpAnalysisResult> resultOpt = viewingRevision
                    ? analyzerService.loadRevisionResult(safe, rev)
                    : analyzerService.loadResult(safe);
            CoreDumpAnalysisResult result = resultOpt.orElse(null);
            // 성공 판정은 결과 화면 탭 렌더 가드와 동일 — GDB 파일 인식 실패(시그널 없음 + 오류만)면 404
            if (result == null || !isReportable(result)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            String revLabel = viewingRevision ? rev : null;
            byte[] pdf = pdfReportService.renderCorePdf(safe, revLabel, result);

            // 코어는 확장자가 임의(.core / core.12345 / 임의명)라 힙식 확장자 제거 정규식 부적합 — .core 접미사만 제거
            String base = safe.replaceAll("\\.core$", "");
            if (viewingRevision) base += "-" + rev;
            base += "-crash-report.pdf";
            String ascii = base.replaceAll("[^\\x20-\\x7E]", "_");
            String utf8 = URLEncoder.encode(base, StandardCharsets.UTF_8).replace("+", "%20");

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
            logger.error("[CoreDump-PDF] PDF 생성 실패 (filename={}): {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 리포트 생성 가능 판정 — 결과 화면 탭 가드 {@code crashSignal != null or errorMessage == null} 와 동일. */
    static boolean isReportable(CoreDumpAnalysisResult result) {
        return result.getCrashSignal() != null
                || result.getErrorMessage() == null || result.getErrorMessage().isEmpty();
    }

    // ── SSE 분석 진행 스트림 ──────────────────────────────────────

    @GetMapping(value = "/core-dump/analyze-progress/{filename:.+}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String filename, Principal principal) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
        String who = principal != null ? principal.getName() : "unknown";
        long timeoutMs = config.getCoreDumpTimeoutMinutes() * 60L * 1000;
        SseEmitter emitter = new SseEmitter(timeoutMs);

        Future<?> task = analyzerService.analyzeWithProgress(safe, emitter, who);
        Runnable cancel = () -> {
            if (task != null && !task.isDone()) task.cancel(true);
        };
        emitter.onTimeout(cancel);
        emitter.onError(e -> cancel.run());
        emitter.onCompletion(cancel);
        return emitter;
    }

    // ── 이력 조회 ─────────────────────────────────────────────────

    @GetMapping("/api/core-dump/history")
    public ResponseEntity<List<CoreDumpAnalysisEntity>> getHistory() {
        return ResponseEntity.ok(analyzerService.getHistory());
    }

    // ── 삭제 ──────────────────────────────────────────────────────

    @DeleteMapping("/api/core-dump/{filename:.+}")
    public ResponseEntity<Map<String, Object>> deleteDump(@PathVariable String filename,
                                                          @RequestParam(defaultValue = "true") boolean deleteFile,
                                                          Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", e.getMessage()));
        }
        try {
            if (deleteFile) {
                analyzerService.deleteDump(safe);
                logger.info("[CoreDump] action=delete, filename={}, deleteFile=true, by={}", safe, who);
            } else {
                analyzerService.deleteHistoryOnly(safe);
                logger.info("[CoreDump] action=delete-history-only, filename={}, deleteFile=false, by={}", safe, who);
            }
            return ResponseEntity.ok(Map.of("status", "ok", "filename", safe));
        } catch (Exception e) {
            logger.error("[CoreDump] 삭제 실패: filename={}, deleteFile={}, by={}, reason={}",
                    safe, deleteFile, who, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "삭제 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    // ── 소스 코드 뷰어 ──────────────────────────────────────────────

    @GetMapping("/api/core-dump/{filename:.+}/source")
    public ResponseEntity<Map<String, Object>> getSourceCode(
            @PathVariable String filename,
            @RequestParam String location,
            @RequestParam(defaultValue = "8") int context) {
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        if (!analyzerService.existsAnalysis(safe)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = analyzerService.readSourceContext(location, context);
        return ResponseEntity.ok(result);
    }

    // ── 재분석 ───────────────────────────────────────────────────

    @PostMapping("/api/core-dump/reanalyze/{filename:.+}")
    public ResponseEntity<Map<String, Object>> reanalyze(@PathVariable String filename,
                                                         Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", e.getMessage()));
        }

        if (analyzerService.isAnalyzing(safe)) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", "이미 분석이 진행 중입니다."));
        }

        // 기존 result.json 은 삭제하지 않고 revisions/{ts}/ 로 이관해 보존한다.
        // 이관이므로 현재 result.json 이 사라져 새 SSE 연결이 재분석을 트리거한다.
        String revisionId;
        try {
            revisionId = analyzerService.archiveCurrentResult(safe);
        } catch (java.io.IOException e) {
            logger.error("[CoreDump] 기존 결과 보존 실패 — 재분석 중단: filename={}, by={}, reason={}",
                    safe, who, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "기존 분석 결과를 보존하지 못해 재분석을 중단했습니다: " + e.getMessage()));
        }

        logger.info("[CoreDump] action=reanalyze, filename={}, archivedRevision={}, by={}",
                safe, revisionId != null ? revisionId : "none", who);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("filename", safe);
        body.put("archivedRevision", revisionId);
        body.put("message", "/core-dump/progress/" + safe + " 로 이동하여 재분석을 시작하세요.");
        return ResponseEntity.ok(body);
    }

    // ── 분석 리비전 목록 ──────────────────────────────────────────

    @GetMapping("/api/core-dump/{filename:.+}/revisions")
    public ResponseEntity<List<com.heapdump.analyzer.model.dto.CoreDumpRevision>> listRevisions(
            @PathVariable String filename) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
        return ResponseEntity.ok(analyzerService.listRevisions(safe));
    }

    // ── exec 첨부 (기존 코어에 실행 파일 연결) ─────────────────────
    // 업로드 파일(multipart) 또는 서버에 이미 있는 파일명(execFilename) 둘 다 허용.

    @PostMapping("/api/core-dump/{filename:.+}/exec")
    public ResponseEntity<Map<String, Object>> attachExec(
            @PathVariable String filename,
            @RequestParam(value = "execFile", required = false) MultipartFile execFile,
            @RequestParam(value = "execFilename", required = false) String execFilename,
            Principal principal) {

        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }

        boolean hasUpload = execFile != null && !execFile.isEmpty();
        boolean hasExisting = execFilename != null && !execFilename.isBlank();
        if (!hasUpload && !hasExisting) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", "실행 파일(execFile) 또는 서버 파일명(execFilename)이 필요합니다."));
        }

        File coreFile = new File(analyzerService.dumpFilesDir(), safe);
        if (!coreFile.isFile()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", "코어 파일을 찾을 수 없습니다: " + safe));
        }

        try {
            String safeExec;
            if (hasUpload) {
                String origExec = execFile.getOriginalFilename();
                safeExec = (origExec != null && !origExec.isBlank())
                        ? analyzerService.validateCoreDumpFilename(origExec)
                        : safe + ".exec";
                File execDest = new File(analyzerService.dumpFilesDir(), safeExec);
                execFile.transferTo(execDest);
                logger.info("[CoreDump] 실행 파일 업로드(첨부): {} ({} bytes) by {}",
                        safeExec, execDest.length(), who);
            } else {
                safeExec = analyzerService.validateCoreDumpFilename(execFilename);
                if (!new File(analyzerService.dumpFilesDir(), safeExec).isFile()) {
                    return ResponseEntity.badRequest().body(Map.of("status", "error",
                            "message", "서버에 실행 파일이 없습니다: " + safeExec));
                }
            }
            analyzerService.saveExecPairing(safe, safeExec);
            logger.info("[CoreDump] action=attach-exec, filename={}, exec={}, by={}", safe, safeExec, who);
            return ResponseEntity.ok(Map.of("status", "ok", "filename", safe, "executableName", safeExec));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("[CoreDump] exec 첨부 실패: filename={}, by={}", safe, who, e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "실행 파일 연결 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    // ── exec 페어링 해제 ──────────────────────────────────────────

    @DeleteMapping("/api/core-dump/{filename:.+}/exec")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unpairExec(
            @PathVariable String filename, Principal principal) {
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
        String who = principal != null ? principal.getName() : "unknown";
        try {
            analyzerService.unpairExec(safe);
            logger.info("[CoreDump] action=unpair-exec, filename={}, by={}", safe, who);
            return ResponseEntity.ok(Map.of("status", "ok", "filename", safe));
        } catch (java.io.IOException e) {
            logger.error("[CoreDump] exec 페어링 해제 실패: filename={}, reason={}", safe, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("status", "error", "message", "페어링 해제 실패: " + e.getMessage()));
        }
    }

    // ── sysroot 라이브러리 번들 (별도 서버 분석 심볼 정확도 복원) ──
    // 원격 출처 코어는 출처 서버에서 tar 스트리밍 자동 수집, 그 외는 tar.gz 수동 업로드.
    // 번들 존재 시 재분석이 -iex "set sysroot" 로 원본 서버 라이브러리를 사용한다.

    @GetMapping("/api/core-dump/{filename:.+}/libs")
    public ResponseEntity<Map<String, Object>> getLibBundleStatus(@PathVariable String filename) {
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
        CoreDumpSysrootService.SysrootStatus st = sysrootService.status(safe);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("present", st.present());
        body.put("fileCount", st.fileCount());
        body.put("totalBytes", st.totalBytes());
        body.put("collectable", st.collectable());
        body.put("originServerName", st.originServerName());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/core-dump/{filename:.+}/collect-libs")
    public ResponseEntity<Map<String, Object>> collectLibBundle(@PathVariable String filename,
                                                                Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }

        Optional<CoreDumpAnalysisResult> resultOpt = analyzerService.loadResult(safe);
        if (resultOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "code", "NO_RESULT",
                    "message", "분석 결과가 없습니다 — 라이브러리 목록은 1차 분석 결과에서 얻으므로 먼저 분석을 수행하세요."));
        }
        Optional<TargetServer> originOpt = sysrootService.findOriginServer(safe);
        if (originOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "code", "NO_ORIGIN",
                    "message", "이 코어의 출처 서버를 전송 이력에서 찾을 수 없습니다 — 수동 업로드(tar.gz)를 사용하세요."));
        }

        try {
            CoreDumpSysrootService.CollectResult r =
                    sysrootService.collectFromOrigin(originOpt.get(), safe, resultOpt.get());
            logger.info("[CoreDump] action=collect-libs server={} filename={} collected={}/{} bytes={} by={}",
                    r.serverName(), safe, r.collected(), r.requested(), r.totalBytes(), who);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("serverName", r.serverName());
            body.put("requested", r.requested());
            body.put("collected", r.collected());
            body.put("missing", r.missing());
            body.put("totalBytes", r.totalBytes());
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "code", "NO_RESULT",
                    "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("[CoreDump] action=collect-libs 실패: filename={}, by={}, reason={}",
                    safe, who, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "code", "SSH_FAIL",
                    "message", "라이브러리 수집 실패: " + e.getMessage()));
        }
    }

    /**
     * 라이브러리 번들 업로드 — **형식 제약 없음**. 아카이브(tar/tar.gz/tgz/tar.bz2/tar.xz/zip)는
     * 해제하고, 그 외 파일은 개별 라이브러리(.so 등)로 번들에 담는다(매직 바이트로 판정 — 확장자 무관).
     * 여러 파일을 한 번에 올릴 수 있고, 기존 번들에 **병합**된다.
     */
    @PostMapping("/api/core-dump/{filename:.+}/libs")
    public ResponseEntity<Map<String, Object>> uploadLibBundle(
            @PathVariable String filename,
            @RequestParam("bundleFile") MultipartFile[] bundleFile,
            Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
        List<MultipartFile> uploads = bundleFile == null ? List.of()
                : Arrays.stream(bundleFile).filter(f -> f != null && !f.isEmpty()).toList();
        if (uploads.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", "업로드할 파일(bundleFile)이 필요합니다."));
        }

        List<File> temps = new ArrayList<>();
        try {
            analyzerService.tmpDir().mkdirs();
            List<CoreDumpSysrootService.UploadItem> items = new ArrayList<>();
            for (MultipartFile mf : uploads) {
                File temp = new File(analyzerService.tmpDir(),
                        "libs_upload_" + UUID.randomUUID().toString().substring(0, 8) + ".bin");
                temps.add(temp);
                mf.transferTo(temp);
                items.add(new CoreDumpSysrootService.UploadItem(temp, mf.getOriginalFilename()));
            }
            CoreDumpSysrootService.UploadResult r = sysrootService.ingestUploads(items, safe);
            logger.info("[CoreDump] action=upload-libs filename={} files={} archives={} singles={} "
                            + "extracted={} skippedLinks={} bytes={} by={}",
                    safe, uploads.size(), r.archives(), r.singles(),
                    r.extracted(), r.skippedLinks(), r.totalBytes(), who);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("extracted", r.extracted());
            body.put("skippedLinks", r.skippedLinks());
            body.put("totalBytes", r.totalBytes());
            body.put("archives", r.archives());
            body.put("singles", r.singles());
            return ResponseEntity.ok(body);
        } catch (java.io.IOException e) {
            logger.warn("[CoreDump] action=upload-libs 실패: filename={}, by={}, reason={}",
                    safe, who, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", "번들 처리 실패: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("[CoreDump] action=upload-libs 오류: filename={}, by={}", safe, who, e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "번들 업로드 중 오류가 발생했습니다: " + e.getMessage()));
        } finally {
            for (File t : temps) {
                if (t.exists() && !t.delete())
                    logger.warn("[CoreDump] 번들 업로드 임시 파일 삭제 실패: {}", t.getAbsolutePath());
            }
        }
    }

    @DeleteMapping("/api/core-dump/{filename:.+}/libs")
    public ResponseEntity<Map<String, Object>> deleteLibBundle(@PathVariable String filename,
                                                               Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
        sysrootService.deleteBundle(safe);
        logger.info("[CoreDump] action=delete-libs filename={} by={}", safe, who);
        return ResponseEntity.ok(Map.of("status", "ok", "filename", safe));
    }

    // ── AI 크래시 분석 ────────────────────────────────────────────
    // heap 의 /api/llm/analyze 흐름을 미러링. ai_insights 테이블을
    // 합성 키("__core__:" + filename)로 재사용한다(별도 테이블 없음).

    @PostMapping("/api/core-dump/{filename:.+}/ai-analyze")
    public ResponseEntity<Map<String, Object>> aiAnalyze(@PathVariable String filename,
                                                         Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "errorCode", "INVALID_FILENAME", "error", e.getMessage()));
        }

        if (!analyzerService.isLlmEnabled()) {
            logger.info("[CoreDump-AI] action=analyze 거부 — LLM 비활성, filename={}, by={}", safe, who);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "LLM_DISABLED");
            err.put("error", "AI 분석이 비활성화되어 있습니다. 설정에서 LLM 을 활성화하세요.");
            return ResponseEntity.ok(err);
        }

        long reqStart = System.currentTimeMillis();
        Map<String, Object> result = analyzerService.analyzeCrashWithAi(safe);
        long elapsed = System.currentTimeMillis() - reqStart;

        boolean success = Boolean.TRUE.equals(result.get("success"));
        Object dataObj = result.get("data");
        String severity = null;
        if (dataObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dm = (Map<String, Object>) dataObj;
            severity = (String) dm.get("severity");
        }

        if (success) {
            logger.info("[CoreDump-AI] action=analyze filename={} severity={} elapsed={}ms model={} by={}",
                    safe, severity, elapsed, result.get("model"), who);
            String key = CoreDumpAnalyzerService.coreInsightKey(safe);
            Map<String, Object> toStore = new LinkedHashMap<>();
            toStore.put("model", result.get("model"));
            toStore.put("latencyMs", result.get("latencyMs"));
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                toStore.putAll(dataMap);
            }
            try {
                analyzerService.saveAiInsight(key, toStore);
                result.put("saved", true);
                result.put("savedTo", "database");
                if (toStore.get("analysedAt") != null) result.put("analysedAt", toStore.get("analysedAt"));
            } catch (Exception saveEx) {
                logger.error("[CoreDump-AI] action=analyze 저장 실패 — filename={}, msg={}", safe, saveEx.getMessage());
                result.put("saved", false);
                result.put("saveError", saveEx.getMessage());
                result.put("saveErrorCode", "SAVE_FAILED");
                result.put("retryPayload", toStore);
            }
        } else {
            logger.warn("[CoreDump-AI] action=analyze 실패 — filename={}, errorCode={}, elapsed={}ms, by={}",
                    safe, result.get("errorCode"), elapsed, who);
        }
        return com.heapdump.analyzer.service.LlmRateLimitService.toResponse(result);
    }

    @GetMapping("/api/core-dump/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> getAiInsight(@PathVariable String filename) {
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("found", false, "error", e.getMessage()));
        }
        Map<String, Object> insight = analyzerService.loadAiInsight(CoreDumpAnalyzerService.coreInsightKey(safe));
        if (insight == null) {
            return ResponseEntity.ok(Map.of("found", false));
        }
        insight.put("found", true);
        insight.put("savedTo", "database");
        return ResponseEntity.ok(insight);
    }

    @DeleteMapping("/api/core-dump/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> deleteAiInsight(@PathVariable String filename,
                                                               Principal principal) {
        String who = principal != null ? principal.getName() : "unknown";
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
        boolean deleted = analyzerService.deleteAiInsight(CoreDumpAnalyzerService.coreInsightKey(safe));
        logger.info("[CoreDump-AI] action=delete-insight filename={} deleted={} by={}", safe, deleted, who);
        return ResponseEntity.ok(Map.of("success", deleted));
    }
}

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.Future;

@RestController
public class CoreDumpApiController {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpApiController.class);

    private final CoreDumpAnalyzerService analyzerService;
    private final HeapDumpConfig config;

    public CoreDumpApiController(CoreDumpAnalyzerService analyzerService, HeapDumpConfig config) {
        this.analyzerService = analyzerService;
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

            // 실행 파일 저장 (선택)
            String executableName = null;
            if (execFile != null && !execFile.isEmpty()) {
                File execDest = new File(dumpFilesDir, safe + ".exec");
                execFile.transferTo(execDest);
                executableName = safe + ".exec";
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
            analyzerService.deleteDump(safe);
            logger.info("[CoreDump] action=delete, filename={}, by={}", safe, who);
            return ResponseEntity.ok(Map.of("status", "ok", "filename", safe));
        } catch (Exception e) {
            logger.error("[CoreDump] 삭제 실패: filename={}, by={}, reason={}", safe, who, e.getMessage(), e);
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

        // 기존 result.json 삭제 → 새 SSE 연결로 재분석 트리거
        analyzerService.resultJsonFile(safe).delete();
        logger.info("[CoreDump] action=reanalyze, filename={}, by={}", safe, who);
        return ResponseEntity.ok(Map.of("status", "ok", "filename", safe,
                "message", "/core-dump/progress/" + safe + " 로 이동하여 재분석을 시작하세요."));
    }
}

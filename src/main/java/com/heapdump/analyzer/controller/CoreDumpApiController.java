package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(originalName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", e.getMessage()));
        }

        try {
            File dumpFilesDir = analyzerService.dumpFilesDir();
            dumpFilesDir.mkdirs();

            // 코어 파일 저장
            File dest = new File(dumpFilesDir, safe);
            coreFile.transferTo(dest);
            logger.info("[CoreDump] 업로드: {} ({} bytes)", safe, dest.length());

            // 실행 파일 저장 (선택)
            String executableName = null;
            if (execFile != null && !execFile.isEmpty()) {
                File execDest = new File(dumpFilesDir, safe + ".exec");
                execFile.transferTo(execDest);
                executableName = safe + ".exec";
                logger.info("[CoreDump] 실행 파일 업로드: {}", executableName);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("filename", safe);
            response.put("executableName", executableName);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("[CoreDump] 업로드 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "업로드 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    // ── SSE 분석 진행 스트림 ──────────────────────────────────────

    @GetMapping(value = "/core-dump/analyze-progress/{filename:.+}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress(@PathVariable String filename) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
        long timeoutMs = config.getCoreDumpTimeoutMinutes() * 60L * 1000;
        SseEmitter emitter = new SseEmitter(timeoutMs);

        Future<?> task = analyzerService.analyzeWithProgress(safe, emitter);
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
    public ResponseEntity<Map<String, Object>> deleteDump(@PathVariable String filename) {
        String safe;
        try {
            safe = analyzerService.validateCoreDumpFilename(filename);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error",
                    "message", e.getMessage()));
        }
        try {
            analyzerService.deleteDump(safe);
            return ResponseEntity.ok(Map.of("status", "ok", "filename", safe));
        } catch (Exception e) {
            logger.error("[CoreDump] 삭제 실패: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error",
                    "message", "삭제 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    // ── 재분석 ───────────────────────────────────────────────────

    @PostMapping("/api/core-dump/reanalyze/{filename:.+}")
    public ResponseEntity<Map<String, Object>> reanalyze(@PathVariable String filename) {
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
        return ResponseEntity.ok(Map.of("status", "ok", "filename", safe,
                "message", "/core-dump/progress/" + safe + " 로 이동하여 재분석을 시작하세요."));
    }
}

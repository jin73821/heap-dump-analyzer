package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 파일 업로드/다운로드/일괄 삭제/중복 검사 API (Phase 4B-2).
 */
@Controller
public class HeapFileApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapFileApiController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final CoreDumpAnalyzerService coreDumpService;

    public HeapFileApiController(HeapDumpAnalyzerService analyzerService,
                                 CoreDumpAnalyzerService coreDumpService) {
        this.analyzerService = analyzerService;
        this.coreDumpService = coreDumpService;
    }

    @PostMapping("/api/files/bulk-delete")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bulkDeleteFiles(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> filenames = (List<String>) body.getOrDefault("filenames", Collections.emptyList());
        int success = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (String raw : filenames) {
            try {
                String safe = FilenameValidator.validate(raw);
                analyzerService.deleteFile(safe);
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(raw + ": " + e.getMessage());
                logger.warn("[BulkDeleteFile] Failed for '{}': {}", raw, e.getMessage());
            }
        }
        logger.info("[BulkDeleteFile] success={}, failed={}", success, failed);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", success);
        resp.put("failed", failed);
        resp.put("errors", errors);
        return ResponseEntity.ok(resp);
    }

    /**
     * XHR 큐 업로더용 JSON API. 성공 200 + {status:"ok", filename, size},
     * 실패 4xx/5xx + {status:"error", message}. CSRF 면제(/api/**).
     */
    @PostMapping("/api/upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadFileApi(@RequestParam("file") MultipartFile file,
                                                             Authentication authentication) {
        String originalName = file.getOriginalFilename();
        Map<String, Object> resp = new LinkedHashMap<>();
        logger.info("[Upload API] Request: filename={}, size={}", originalName, FormatUtils.formatBytes(file.getSize()));
        try {
            if (file.isEmpty()) {
                resp.put("status", "error");
                resp.put("message", "파일이 비어있습니다.");
                return ResponseEntity.badRequest().body(resp);
            }
            String filename = analyzerService.uploadFile(file);
            String uploadedBy = authentication != null ? authentication.getName() : null;
            analyzerService.saveUploadRecord(filename, file.getSize(), uploadedBy);
            resp.put("status", "ok");
            resp.put("filename", filename);
            resp.put("size", file.getSize());
            logger.info("[Upload API] Success: {}", filename);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            logger.warn("[Upload API] Validation failed for '{}': {}", originalName, e.getMessage());
            resp.put("status", "error");
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (IOException e) {
            logger.error("[Upload API] IO error for '{}': {}", originalName, e.getMessage(), e);
            resp.put("status", "error");
            resp.put("message", "Upload failed: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @PostMapping("/api/upload/check")
    @ResponseBody
    public ResponseEntity<Map<String, String>> checkUploadDuplicate(@RequestBody Map<String, Object> request) {
        String filename = (String) request.get("filename");
        Number fileSizeNum = (Number) request.get("fileSize");
        String partialHash = (String) request.get("partialHash");

        if (filename == null || fileSizeNum == null || partialHash == null) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("status", "OK");
            return ResponseEntity.ok(err);
        }

        filename = new File(filename).getName(); // path traversal 방지
        long fileSize = fileSizeNum.longValue();
        Map<String, String> result = analyzerService.checkDuplicate(filename, fileSize, partialHash);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/files/{filename:.+}/classify")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> classifyFile(
            @PathVariable String filename,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        filename = FilenameValidator.validate(filename);
        String fileType = body.get("fileType");
        Set<String> allowed = Set.of("core", "exec", "heapdump", "others");
        if (fileType == null || !allowed.contains(fileType)) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "유효하지 않은 파일 유형입니다. (core|exec|heapdump|others)");
            return ResponseEntity.badRequest().body(err);
        }
        try {
            String who = authentication != null ? authentication.getName() : "unknown";
            analyzerService.saveFileClassification(filename, fileType);
            logger.info("[ClassifyFile] action=classify file={} type={} by={}", filename, fileType, who);
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("filename", filename);
            resp.put("fileType", fileType);
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            logger.error("[ClassifyFile] Failed for '{}': {}", filename, e.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put("status", "error");
            err.put("message", "분류 저장 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/api/files/{filename:.+}/pair")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pairCoreExec(
            @PathVariable String filename,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        filename = FilenameValidator.validate(filename);
        String execFilename = body.get("execFilename");
        String who = authentication != null ? authentication.getName() : "unknown";
        Map<String, Object> resp = new HashMap<>();
        try {
            if (execFilename == null || execFilename.trim().isEmpty()) {
                removeExecCopyFromCoreDirIfNeeded(filename);
                analyzerService.removeCoreExecPairing(filename);
                logger.info("[FilePair] action=unpair core={} by={}", filename, who);
                resp.put("status", "ok");
                resp.put("action", "unpaired");
            } else {
                execFilename = FilenameValidator.validate(execFilename.trim());
                analyzerService.saveCoreExecPairing(filename, execFilename);
                logger.info("[FilePair] action=pair core={} exec={} by={}", filename, execFilename, who);
                // 코어파일이 코어덤프 디렉터리에 있고, 실행파일이 힙덤프 디렉터리에만 있으면 복사
                copyExecToCoreDirIfNeeded(filename, execFilename);
                resp.put("status", "ok");
                resp.put("action", "paired");
                resp.put("execFilename", execFilename);
            }
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            logger.error("[FilePair] Failed for '{}': {}", filename, e.getMessage());
            resp.put("status", "error");
            resp.put("message", "페어링 저장 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    @DeleteMapping("/api/files/{filename:.+}/pair")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unpairCoreExec(
            @PathVariable String filename,
            Authentication authentication) {
        filename = FilenameValidator.validate(filename);
        String who = authentication != null ? authentication.getName() : "unknown";
        Map<String, Object> resp = new HashMap<>();
        try {
            removeExecCopyFromCoreDirIfNeeded(filename);
            analyzerService.removeCoreExecPairing(filename);
            logger.info("[FilePair] action=unpair core={} by={}", filename, who);
            resp.put("status", "ok");
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            logger.error("[FilePair] Unpair failed for '{}': {}", filename, e.getMessage());
            resp.put("status", "error");
            resp.put("message", "페어링 해제 실패: " + e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * 코어파일이 코어덤프 디렉터리에 있고 실행파일이 힙덤프 디렉터리에만 존재하면
     * 코어덤프 디렉터리로 복사한다. GDB 분석 및 getExecFilename() 탐색이 코어덤프 디렉터리를
     * 기준으로 동작하기 때문에 복사가 필요하다.
     */
    private void copyExecToCoreDirIfNeeded(String coreFilename, String execFilename) {
        File coreInCoreDir = new File(coreDumpService.dumpFilesDir(), coreFilename);
        if (!coreInCoreDir.exists()) return; // 힙덤프 디렉터리의 "core" 타입 — 복사 불필요
        File execInCoreDir = new File(coreDumpService.dumpFilesDir(), execFilename);
        if (execInCoreDir.exists()) return;  // 이미 코어덤프 디렉터리에 있음
        try {
            File execInHeapDir = analyzerService.getFile(execFilename);
            Files.copy(execInHeapDir.toPath(), execInCoreDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("[FilePair] exec 파일 코어덤프 디렉터리로 복사 완료: {} → {}", execInHeapDir, execInCoreDir);
        } catch (IOException e) {
            logger.warn("[FilePair] exec 파일 복사 실패 (힙덤프 디렉터리에 없음): {} — {}", execFilename, e.getMessage());
        }
    }

    /**
     * 페어링 해제 시, exec 파일이 힙덤프 디렉터리에 원본이 있고
     * 코어덤프 디렉터리에 복사본만 있는 경우 복사본을 삭제한다.
     * 코어덤프 디렉터리에만 존재하는 파일(독립 업로드)은 삭제하지 않는다.
     */
    private void removeExecCopyFromCoreDirIfNeeded(String coreFilename) {
        try {
            String execFn = coreDumpService.getExecFilename(coreFilename);
            if (execFn == null) return;
            File execInCoreDir = new File(coreDumpService.dumpFilesDir(), execFn);
            if (!execInCoreDir.exists()) return;
            File execInHeapDir = analyzerService.getFile(execFn);
            if (execInHeapDir != null && execInHeapDir.exists()) {
                execInCoreDir.delete();
                logger.info("[FilePair] 페어링 해제: exec 복사본 삭제 {}", execFn);
            }
        } catch (Exception e) {
            logger.warn("[FilePair] exec 복사본 삭제 실패: {}", e.getMessage());
        }
    }

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        filename = FilenameValidator.validate(filename);
        try {
            File file = analyzerService.getFile(filename);
            String downloadName = file.getName();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + downloadName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(new FileSystemResource(file));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}

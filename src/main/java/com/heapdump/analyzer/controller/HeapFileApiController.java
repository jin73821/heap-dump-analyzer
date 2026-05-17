package com.heapdump.analyzer.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파일 업로드/다운로드/일괄 삭제/중복 검사 API (Phase 4B-2).
 */
@Controller
public class HeapFileApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapFileApiController.class);

    private final HeapDumpAnalyzerService analyzerService;

    public HeapFileApiController(HeapDumpAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
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
    public ResponseEntity<Map<String, Object>> uploadFileApi(@RequestParam("file") MultipartFile file) {
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

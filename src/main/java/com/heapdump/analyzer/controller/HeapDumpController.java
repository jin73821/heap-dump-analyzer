package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Heap Dump Analyzer 컨트롤러 (MAT CLI 연동 버전)
 */
@Controller
public class HeapDumpController {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpController.class);

    private final HeapDumpAnalyzerService analyzerService;

    public HeapDumpController(HeapDumpAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
    }

    // ── 메인 페이지 ──────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        model.addAttribute("files", files);
        model.addAttribute("fileCount", files.size());
        return "index";
    }

    // ── 파일 업로드 ──────────────────────────────────────────

    @PostMapping("/upload")
    public String uploadFile(@RequestParam("file") MultipartFile file,
                             RedirectAttributes redirectAttributes) {
        try {
            if (file.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please select a file to upload");
                return "redirect:/";
            }
            String filename = analyzerService.uploadFile(file);
            redirectAttributes.addFlashAttribute("success", "File uploaded successfully: " + filename);
            logger.info("Uploaded: {}", filename);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to upload file: " + e.getMessage());
            logger.error("Upload failed", e);
        }
        return "redirect:/";
    }

    // ── 파일 분석 (MAT CLI) ───────────────────────────────────

    @GetMapping("/analyze/{filename:.+}")
    public String analyzeFile(@PathVariable String filename, Model model) {
        try {
            logger.info("Analyzing: {}", filename);

            HeapAnalysisResult result = analyzerService.analyzeHeapDump(filename);

            if (result.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR) {
                model.addAttribute("error", result.getErrorMessage());
                model.addAttribute("filename", filename);
                model.addAttribute("matLog", result.getMatLog());
                return "analyze";
            }

            // 날짜 포맷
            String formattedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date(result.getLastModified()));
            model.addAttribute("formattedDate", formattedDate);
            model.addAttribute("result", result);

            logger.info("Analysis done: {} in {}ms", filename, result.getAnalysisTime());

        } catch (Exception e) {
            logger.error("Analysis exception for {}", filename, e);
            model.addAttribute("error", "Failed to analyze file: " + e.getMessage());
            model.addAttribute("filename", filename);
        }
        return "analyze";
    }

    // ── 파일 다운로드 ─────────────────────────────────────────

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            File file = analyzerService.getFile(filename);
            Resource resource = new FileSystemResource(file);
            logger.info("Downloading: {}", filename);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);
        } catch (IOException e) {
            logger.error("Download failed: {}", filename, e);
            return ResponseEntity.notFound().build();
        }
    }

    // ── 파일 삭제 ─────────────────────────────────────────────

    @GetMapping("/delete/{filename:.+}")
    public String deleteFile(@PathVariable String filename,
                             RedirectAttributes redirectAttributes) {
        try {
            analyzerService.deleteFile(filename);
            redirectAttributes.addFlashAttribute("success", "File deleted: " + filename);
            logger.info("Deleted: {}", filename);
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to delete file: " + e.getMessage());
            logger.error("Delete failed: {}", filename, e);
        }
        return "redirect:/";
    }

    // ── MAT 리포트 HTML 원문 반환 (팝업/iframe용) ─────────────

    @GetMapping("/report/{filename:.+}/overview")
    @ResponseBody
    public ResponseEntity<String> overviewHtml(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.analyzeHeapDump(filename);
        return htmlResponse(r.getOverviewHtml());
    }

    @GetMapping("/report/{filename:.+}/suspects")
    @ResponseBody
    public ResponseEntity<String> suspectsHtml(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.analyzeHeapDump(filename);
        return htmlResponse(r.getSuspectsHtml());
    }

    @GetMapping("/report/{filename:.+}/top_components")
    @ResponseBody
    public ResponseEntity<String> topComponentsHtml(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.analyzeHeapDump(filename);
        return htmlResponse(r.getTopComponentsHtml());
    }

    private ResponseEntity<String> htmlResponse(String html) {
        if (html == null || html.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html",
                        java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }
}

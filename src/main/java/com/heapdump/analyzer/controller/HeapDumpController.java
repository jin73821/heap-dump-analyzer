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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Heap Dump Analyzer 컨트롤러
 * - /analyze/{filename}        : 진행 상황 화면 (progress.html)
 * - /analyze/progress/{filename} : SSE 스트림 (실시간 로그)
 * - /analyze/result/{filename} : 완료 후 결과 화면 (analyze.html)
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
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to upload file: " + e.getMessage());
        }
        return "redirect:/";
    }

    // ── 분석 진행 화면 ───────────────────────────────────────
    // Analyze 버튼 클릭 시:
    //   캐시에 결과 있으면 -> 바로 결과 페이지로 이동 (재분석 불필요)
    //   없으면            -> 진행 상황 페이지(progress.html) 표시

    @GetMapping("/analyze/{filename:.+}")
    public String analyzeProgress(@PathVariable String filename, Model model) {
        // 이미 분석된 결과가 캐시에 있으면 바로 결과 페이지로
        HeapAnalysisResult cached = analyzerService.getCachedResult(filename);
        if (cached != null && cached.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS) {
            logger.info("Cache hit for {}, skipping re-analysis", filename);
            return "redirect:/analyze/result/" + filename;
        }
        // 없으면 진행 화면으로
        model.addAttribute("filename", filename);
        return "progress";
    }

    // ── [NEW] SSE 스트림 — MAT CLI 진행 상황 실시간 전송 ──────

    @GetMapping(value = "/analyze/progress/{filename:.+}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamProgress(@PathVariable String filename) {
        // 타임아웃: MAT_TIMEOUT(30분) + 여유 5분
        SseEmitter emitter = new SseEmitter(35L * 60 * 1000);

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> {
            logger.warn("SSE error for {}: {}", filename, e.getMessage());
            emitter.complete();
        });

        // 비동기 분석 시작
        analyzerService.analyzeWithProgress(filename, emitter);
        return emitter;
    }

    // ── [NEW] 분석 결과 화면 (캐시에서 조회) ─────────────────

    @GetMapping("/analyze/result/{filename:.+}")
    public String analyzeResult(@PathVariable String filename, Model model) {
        HeapAnalysisResult result = analyzerService.getCachedResult(filename);

        if (result == null) {
            // 캐시 미스 → 진행 화면으로 리다이렉트
            return "redirect:/analyze/" + filename;
        }

        if (result.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR) {
            model.addAttribute("error", result.getErrorMessage());
            model.addAttribute("filename", filename);
            model.addAttribute("matLog", result.getMatLog());
            return "analyze";
        }

        String formattedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(result.getLastModified()));
        model.addAttribute("formattedDate", formattedDate);
        model.addAttribute("result", result);
        return "analyze";
    }

    // ── 파일 다운로드 ─────────────────────────────────────────

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            File file = analyzerService.getFile(filename);
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);
        } catch (IOException e) {
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
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to delete file: " + e.getMessage());
        }
        return "redirect:/";
    }

    // ── MAT 리포트 HTML 원문 반환 ─────────────────────────────

    @GetMapping("/report/{filename:.+}/overview")
    @ResponseBody
    public ResponseEntity<String> overviewHtml(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return htmlResponse(r != null ? r.getOverviewHtml() : null);
    }

    @GetMapping("/report/{filename:.+}/suspects")
    @ResponseBody
    public ResponseEntity<String> suspectsHtml(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return htmlResponse(r != null ? r.getSuspectsHtml() : null);
    }

    @GetMapping("/report/{filename:.+}/top_components")
    @ResponseBody
    public ResponseEntity<String> topComponentsHtml(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return htmlResponse(r != null ? r.getTopComponentsHtml() : null);
    }

    private ResponseEntity<String> htmlResponse(String html) {
        if (html == null || html.isBlank()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }
}

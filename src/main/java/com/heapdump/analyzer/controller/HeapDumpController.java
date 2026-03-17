package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Heap Dump Analyzer 컨트롤러
 */
@Controller
public class HeapDumpController {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpController.class);
    private final HeapDumpAnalyzerService analyzerService;

    public HeapDumpController(HeapDumpAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
    }

    /**
     * 메인 페이지
     */
    @GetMapping("/")
    public String index(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        model.addAttribute("files", files);
        model.addAttribute("fileCount", files.size());
        return "index";
    }

    /**
     * 파일 업로드
     */
    @PostMapping("/upload")
    public String uploadFile(@RequestParam("file") MultipartFile file, 
                           RedirectAttributes redirectAttributes) {
        try {
            if (file.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please select a file to upload");
                return "redirect:/";
            }

            String filename = analyzerService.uploadFile(file);
            redirectAttributes.addFlashAttribute("success", 
                "File uploaded successfully: " + filename);
            
            logger.info("File uploaded successfully: {}", filename);
            
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            logger.error("Invalid file upload: {}", e.getMessage());
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", 
                "Failed to upload file: " + e.getMessage());
            logger.error("Failed to upload file", e);
        }
        
        return "redirect:/";
    }

    /**
     * 파일 분석
     */
    @GetMapping("/analyze/{filename:.+}")
    public String analyzeFile(@PathVariable String filename, Model model) {
        try {
            logger.info("Analyzing file: {}", filename);
            
            HeapAnalysisResult result = analyzerService.analyzeHeapDump(filename);
            
            if (result.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR) {
                model.addAttribute("error", result.getErrorMessage());
                model.addAttribute("filename", filename);
                return "analyze";
            }
            
            // 날짜 포맷팅
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String formattedDate = dateFormat.format(new Date(result.getLastModified()));
            model.addAttribute("formattedDate", formattedDate);
            
            model.addAttribute("result", result);
            
            logger.info("Analysis completed for file: {} in {}ms", 
                filename, result.getAnalysisTime());
            
        } catch (Exception e) {
            logger.error("Failed to analyze file: {}", filename, e);
            model.addAttribute("error", "Failed to analyze file: " + e.getMessage());
            model.addAttribute("filename", filename);
        }
        
        return "analyze";
    }

    /**
     * 파일 다운로드
     */
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            File file = analyzerService.getFile(filename);
            Resource resource = new FileSystemResource(file);
            
            logger.info("Downloading file: {}", filename);
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                        "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);
                    
        } catch (IOException e) {
            logger.error("Failed to download file: {}", filename, e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 파일 삭제
     */
    @GetMapping("/delete/{filename:.+}")
    public String deleteFile(@PathVariable String filename, 
                           RedirectAttributes redirectAttributes) {
        try {
            analyzerService.deleteFile(filename);
            redirectAttributes.addFlashAttribute("success", 
                "File deleted successfully: " + filename);
            
            logger.info("File deleted successfully: {}", filename);
            
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", 
                "Failed to delete file: " + e.getMessage());
            logger.error("Failed to delete file: {}", filename, e);
        }
        
        return "redirect:/";
    }
}

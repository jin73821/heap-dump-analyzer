package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
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

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Heap Dump Analyzer Controller
 *
 * 신규 엔드포인트:
 *   GET  /compare                         → 두 덤프 비교 결과 페이지
 *   GET  /api/history                     → 분석 히스토리 JSON
 *   POST /api/results/clear                → 전체 저장 결과 삭제
 *   POST /api/settings/unreachable        → keep_unreachable 설정 변경
 *   GET  /api/settings                    → 현재 설정 조회
 *   POST /analyze/rerun/{filename}        → 저장 결과 삭제 후 재분석
 *   POST /delete/{filename}               → 파일 삭제
 *   POST /history/delete/{filename}       → 히스토리 삭제
 */
@Controller
public class HeapDumpController {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final com.heapdump.analyzer.config.HeapDumpConfig config;

    public HeapDumpController(HeapDumpAnalyzerService analyzerService,
                              com.heapdump.analyzer.config.HeapDumpConfig config) {
        this.analyzerService = analyzerService;
        this.config = config;
    }

    // ── 파일명 검증 실패 핸들러 ─────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleBadFilename(IllegalArgumentException e) {
        logger.warn("[Validation] Rejected request: {}", e.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    // ── 메인 페이지 ──────────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        model.addAttribute("files", files);
        model.addAttribute("fileCount", files.size());

        // 통계 계산 (디스크 실제 사용량 기준: 압축 파일은 compressedSize 사용)
        long totalBytes = files.stream()
            .mapToLong(f -> f.isCompressed() && f.getCompressedSize() > 0 ? f.getCompressedSize() : f.getSize())
            .sum();
        model.addAttribute("totalSize", formatBytes(totalBytes));

        // 분석 히스토리 (캐시에서 로드)
        List<AnalysisHistoryItem> history = buildHistory(files);
        int maxDisplay = config.getDashboardHistoryMaxDisplay();
        model.addAttribute("analysisHistory",
            history.size() > maxDisplay ? history.subList(0, maxDisplay) : history);
        model.addAttribute("hasMoreFiles", history.size() > maxDisplay);
        model.addAttribute("totalFileCount", history.size());
        model.addAttribute("analyzedCount",
            history.stream().filter(h -> "SUCCESS".equals(h.getStatus())).count());

        // 전체 leak suspects 수
        long totalSuspects = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()))
            .mapToLong(AnalysisHistoryItem::getSuspectCount)
            .sum();
        model.addAttribute("totalSuspects", totalSuspects > 0 ? totalSuspects : null);

        // 분석 완료 파일 Set (View 버튼 표시용)
        Set<String> analyzedFiles = history.stream()
                .filter(h -> "SUCCESS".equals(h.getStatus()))
                .map(AnalysisHistoryItem::getFilename)
                .collect(Collectors.toSet());
        model.addAttribute("analyzedFiles", analyzedFiles);

        // 분석 실패 파일 Set (에러 표시용)
        Set<String> errorFiles = history.stream()
                .filter(h -> "ERROR".equals(h.getStatus()))
                .map(AnalysisHistoryItem::getFilename)
                .collect(Collectors.toSet());
        model.addAttribute("errorFiles", errorFiles);

        // 분석 수행 이력 (성공+에러, 최근 10개)
        List<AnalysisHistoryItem> recentAnalyses = history.stream()
                .filter(h -> "SUCCESS".equals(h.getStatus()) || "ERROR".equals(h.getStatus()))
                .limit(10)
                .collect(Collectors.toList());
        model.addAttribute("recentAnalyses", recentAnalyses);

        // 디스크 사용량
        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists() && dumpDir.getTotalSpace() > 0) {
            long total = dumpDir.getTotalSpace();
            long usable = dumpDir.getUsableSpace();
            long used = total - usable;
            model.addAttribute("diskUsedPercent", Math.round(used * 100.0 / total));
            model.addAttribute("diskUsed", formatBytes(used));
            model.addAttribute("diskTotal", formatBytes(total));
        }

        // MAT 설정
        model.addAttribute("matKeepUnreachable", analyzerService.isKeepUnreachableObjects());

        return "index";
    }

    // ── 전체 파일 목록 ────────────────────────────────────────────

    @GetMapping("/files")
    public String filesPage(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> history = buildHistory(files);
        model.addAttribute("analysisHistory", history);
        model.addAttribute("fileCount", files.size());

        long originalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        long diskBytes = files.stream()
            .mapToLong(f -> f.isCompressed() && f.getCompressedSize() > 0 ? f.getCompressedSize() : f.getSize())
            .sum();
        model.addAttribute("totalSize", formatBytes(originalBytes));
        model.addAttribute("diskSize", formatBytes(diskBytes));
        model.addAttribute("hasCompressed", originalBytes != diskBytes);

        long analyzedCount = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus())).count();
        model.addAttribute("analyzedCount", analyzedCount);

        return "files";
    }

    // ── 분석 이력 페이지 ─────────────────────────────────────────

    @GetMapping("/history")
    public String historyPage(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> history = buildHistory(files);

        // 분석 수행된 항목만 필터 (SUCCESS + ERROR)
        List<AnalysisHistoryItem> analysisOnly = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()) || "ERROR".equals(h.getStatus()))
            .collect(Collectors.toList());
        model.addAttribute("analysisHistory", analysisOnly);

        long successCount = analysisOnly.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus())).count();
        long errorCount = analysisOnly.stream()
            .filter(h -> "ERROR".equals(h.getStatus())).count();
        model.addAttribute("totalCount", analysisOnly.size());
        model.addAttribute("successCount", successCount);
        model.addAttribute("errorCount", errorCount);

        return "history";
    }

    // ── 설정 페이지 ─────────────────────────────────────────────

    @GetMapping("/settings")
    public String settingsPage(Model model) {
        model.addAttribute("matKeepUnreachable", analyzerService.isKeepUnreachableObjects());
        model.addAttribute("compressAfterAnalysis", analyzerService.isCompressAfterAnalysis());
        return "settings";
    }

    // ── 히스토리 삭제 ────────────────────────────────────────────

    @PostMapping("/history/delete/{filename:.+}")
    public String deleteHistory(@PathVariable String filename,
                                @RequestParam(value = "deleteHeapDump", defaultValue = "false") boolean deleteHeapDump,
                                RedirectAttributes redirectAttributes) {
        filename = FilenameValidator.validate(filename);
        logger.info("[DeleteHistory] Request received: filename={}, deleteHeapDump={}", filename, deleteHeapDump);
        try {
            analyzerService.deleteHistory(filename, deleteHeapDump);
            logger.info("[DeleteHistory] Success: {}", filename);
            redirectAttributes.addFlashAttribute("success", "히스토리 삭제 완료: " + filename);
        } catch (IOException e) {
            logger.error("[DeleteHistory] Failed for '{}': {}", filename, e.getMessage());
            redirectAttributes.addFlashAttribute("error", "히스토리 삭제 실패: " + e.getMessage());
        }
        return "redirect:/history";
    }

    // ── 파일 업로드 ──────────────────────────────────────────────

    @PostMapping("/upload")
    public String uploadFile(@RequestParam("file") MultipartFile file,
                             RedirectAttributes redirectAttributes) {
        String originalName = file.getOriginalFilename();
        logger.info("[Upload] Request received: filename={}, size={}", originalName, formatBytes(file.getSize()));

        try {
            if (file.isEmpty()) {
                logger.warn("[Upload] Rejected: empty file submitted");
                redirectAttributes.addFlashAttribute("error", "Please select a file to upload");
                return "redirect:/";
            }
            String filename = analyzerService.uploadFile(file);
            logger.info("[Upload] Success: {}", filename);
            String successMsg = "Uploaded: " + filename + " (" + formatBytes(file.getSize()) + ")";
            redirectAttributes.addFlashAttribute("success", successMsg);

            // 디스크 사용량 90% 이상 경고
            try {
                java.io.File dumpDir = new java.io.File(analyzerService.getHeapDumpDirectory());
                long totalSpace = dumpDir.getTotalSpace();
                long usableSpace = dumpDir.getUsableSpace();
                if (totalSpace > 0) {
                    double usagePercent = (double)(totalSpace - usableSpace) / totalSpace * 100;
                    if (usagePercent >= config.getDiskWarningUsagePercent()) {
                        logger.warn("[Upload] Disk usage warning: {}%", String.format("%.1f", usagePercent));
                        redirectAttributes.addFlashAttribute("warning",
                                String.format("디스크 사용량이 %.1f%%입니다. 불필요한 파일을 삭제해 주세요.", usagePercent));
                    }
                }
            } catch (Exception ex) {
                logger.debug("[Upload] Failed to check disk usage", ex);
            }
        } catch (IllegalArgumentException e) {
            logger.warn("[Upload] Validation failed for '{}': {}", originalName, e.getMessage());
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
            logger.error("[Upload] IO error for '{}': {}", originalName, e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", "Upload failed: " + e.getMessage());
        }
        return "redirect:/";
    }

    // ── 업로드 중복 검사 API ────────────────────────────────────────

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

    // ── 분석 진행 화면 ───────────────────────────────────────────

    @GetMapping("/analyze/{filename:.+}")
    public String analyzeProgress(@PathVariable String filename, Model model) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult cached = analyzerService.getCachedResult(filename);
        if (cached != null && cached.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS) {
            logger.info("Cache hit for {}, redirecting to result", filename);
            return "redirect:/analyze/result/" + filename;
        }
        model.addAttribute("filename", filename);
        return "progress";
    }

    // ── 재분석 ───────────────────────────────────────────────────

    @PostMapping("/analyze/rerun/{filename:.+}")
    public String rerunAnalysis(@PathVariable String filename, RedirectAttributes redirectAttributes) {
        filename = FilenameValidator.validate(filename);

        // 덤프 파일 존재 여부 확인 (원본 + .gz)
        File sourceFile = new File(config.getDumpFilesDirectory(), filename);
        File gzFile = new File(config.getDumpFilesDirectory(), filename + ".gz");
        File legacyFile = new File(config.getHeapDumpDirectory(), filename);
        if (!sourceFile.exists() && !gzFile.exists() && !legacyFile.exists()) {
            logger.warn("[Rerun] Dump file not found: {}. Preserving existing analysis data.", filename);
            redirectAttributes.addFlashAttribute("rerunError",
                    "덤프 파일이 존재하지 않아 재분석할 수 없습니다. 기존 분석 결과는 유지됩니다.");
            return "redirect:/analyze/result/" + filename;
        }

        analyzerService.clearCache(filename);
        logger.info("Cache cleared for {} → restarting", filename);
        return "redirect:/analyze/" + filename;
    }

    // ── SSE 진행 스트림 ──────────────────────────────────────────

    @GetMapping(value = "/analyze/progress/{filename:.+}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamProgress(@PathVariable String filename) {
        final String safe = FilenameValidator.validate(filename);
        SseEmitter emitter = new SseEmitter(config.getSseEmitterTimeoutMinutes() * 60L * 1000);
        java.util.concurrent.Future<?> task = analyzerService.analyzeWithProgress(safe, emitter);

        // 클라이언트 disconnect / 타임아웃 시 분석 스레드 중단
        Runnable cancelTask = () -> {
            if (task != null && !task.isDone()) {
                logger.info("[SSE] Client disconnected, cancelling analysis for: {}", safe);
                task.cancel(true);
            }
        };
        emitter.onTimeout(cancelTask);
        emitter.onError(e -> cancelTask.run());
        emitter.onCompletion(cancelTask);

        return emitter;
    }

    // ── 분석 취소 API ─────────────────────────────────────────────

    @PostMapping("/api/analyze/cancel/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> cancelAnalysis(@PathVariable String filename) {
        String safe = FilenameValidator.validate(filename);
        logger.info("[Cancel] Cancel requested for: {}", safe);
        boolean cancelled = analyzerService.cancelAnalysis(safe);
        Map<String, Object> resp = new HashMap<>();
        resp.put("cancelled", cancelled);
        resp.put("filename", safe);
        return ResponseEntity.ok(resp);
    }

    // ── 분석 결과 화면 ───────────────────────────────────────────

    @GetMapping("/analyze/result/{filename:.+}")
    public String analyzeResult(@PathVariable String filename, Model model) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult result = analyzerService.getCachedResult(filename);
        if (result == null) return "redirect:/analyze/" + filename;

        if (result.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR) {
            model.addAttribute("error", result.getErrorMessage());
            model.addAttribute("filename", filename);
            model.addAttribute("matLog", truncateLog(result.getMatLog(), config.getMatLogMaxDisplayChars()));
            model.addAttribute("hasHeapData", false);
            model.addAttribute("matLogTotalLen",
                    result.getMatLog() != null ? result.getMatLog().length() : 0);
            // 분석 실패 일자 및 소요 시간
            model.addAttribute("errorDate",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new Date(result.getLastModified())));
            model.addAttribute("errorAnalysisTime", result.getAnalysisTime());
            model.addAttribute("errorFileSize", formatBytes(result.getFileSize()));
            return "analyze";
        }

        String formattedDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(result.getLastModified()));
        model.addAttribute("formattedDate", formattedDate);
        model.addAttribute("result", result);

        boolean hasHeapData = result.getTotalHeapSize() > 0 || result.getUsedHeapSize() > 0;
        model.addAttribute("hasHeapData", hasHeapData);

        List<String> topObjNames  = new ArrayList<>();
        List<Long>   topObjSizes  = new ArrayList<>();
        List<Long>   topObjCounts = new ArrayList<>();
        List<Double> topObjPcts   = new ArrayList<>();

        if (result.getTopMemoryObjects() != null) {
            result.getTopMemoryObjects().stream().limit(config.getTopObjectsMaxDisplay()).forEach(o -> {
                topObjNames .add(o.getClassName()    != null ? o.getClassName() : "");
                topObjSizes .add(o.getTotalSize());
                topObjCounts.add(o.getObjectCount());
                topObjPcts  .add(o.getPercentOfHeap());
            });
        }
        model.addAttribute("topObjNames",  topObjNames);
        model.addAttribute("topObjSizes",  topObjSizes);
        model.addAttribute("topObjCounts", topObjCounts);
        model.addAttribute("topObjPcts",   topObjPcts);
        model.addAttribute("matLogTotalLen",
                result.getMatLog() != null ? result.getMatLog().length() : 0);

        // Actions: Histogram / Thread Overview / Thread Stacks
        model.addAttribute("hasHistogram", result.getHistogramHtml() != null && !result.getHistogramHtml().isEmpty());
        model.addAttribute("hasThreadOverview", result.getThreadOverviewHtml() != null && !result.getThreadOverviewHtml().isEmpty());
        model.addAttribute("hasThreadStacks", result.getThreadStacksText() != null && !result.getThreadStacksText().isEmpty());

        // Parsed Histogram / Thread data
        model.addAttribute("histogramEntries", result.getHistogramEntries());
        model.addAttribute("threadInfos", result.getThreadInfos());
        model.addAttribute("totalHistogramClasses", result.getTotalHistogramClasses());
        model.addAttribute("threadCount", result.getThreadInfos() != null ? result.getThreadInfos().size() : 0);

        // Thread stack traces as list for JS injection
        List<String> threadStacks = new ArrayList<>();
        if (result.getThreadInfos() != null) {
            for (com.heapdump.analyzer.model.ThreadInfo ti : result.getThreadInfos()) {
                threadStacks.add(ti.getStackTrace() != null ? ti.getStackTrace() : "");
            }
        }
        model.addAttribute("threadStacks", threadStacks);

        // Raw Data: MAT ZIP 존재 여부 (iframe용)
        model.addAttribute("hasOverviewZip", analyzerService.hasReportZip(filename, "overview"));
        model.addAttribute("hasTopComponentsZip", analyzerService.hasReportZip(filename, "top_components"));
        model.addAttribute("hasSuspectsZip", analyzerService.hasReportZip(filename, "suspects"));

        return "analyze";
    }

    // ── 로그 청크 API ────────────────────────────────────────────

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

    // ── 파일 다운로드 ────────────────────────────────────────────

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

    // ── 파일 삭제 ────────────────────────────────────────────────

    @PostMapping("/delete/{filename:.+}")
    public String deleteFile(@PathVariable String filename,
                             @RequestHeader(value = "Referer", required = false) String referer,
                             RedirectAttributes redirectAttributes) {
        filename = FilenameValidator.validate(filename);
        logger.info("[Delete] Request received: filename={}", filename);
        try {
            analyzerService.deleteFile(filename);
            logger.info("[Delete] Success: {}", filename);
            redirectAttributes.addFlashAttribute("success", "Deleted: " + filename);
        } catch (IOException e) {
            logger.error("[Delete] Failed for '{}': {}", filename, e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Delete failed: " + e.getMessage());
        }
        if (referer != null && referer.contains("/files")) {
            return "redirect:/files";
        }
        return "redirect:/";
    }

    // ── MAT 리포트 HTML ──────────────────────────────────────────

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

    // ── Top Component 상세 HTML ──────────────────────────────────

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

        // index 기반 키로 먼저 조회: "className#index"
        if (index >= 0) {
            String key = className + "#" + index;
            String html = r.getComponentDetailHtmlMap().get(key);
            if (html != null && !html.isBlank()) return htmlResponse(html);
        }

        // 키에 className이 포함된 첫 번째 항목 매칭
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
    public ResponseEntity<com.heapdump.analyzer.model.ComponentDetailParsed> componentDetailParsed(
            @PathVariable String filename,
            @RequestParam String className,
            @RequestParam(defaultValue = "-1") int index) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null) return ResponseEntity.notFound().build();

        // parsed map에서 조회
        Map<String, com.heapdump.analyzer.model.ComponentDetailParsed> parsedMap = r.getComponentDetailParsedMap();
        if (parsedMap != null && !parsedMap.isEmpty()) {
            // index 기반 키로 먼저 조회
            if (index >= 0) {
                String key = className + "#" + index;
                com.heapdump.analyzer.model.ComponentDetailParsed p = parsedMap.get(key);
                if (p != null) return ResponseEntity.ok(p);
            }
            // className 매칭
            for (Map.Entry<String, com.heapdump.analyzer.model.ComponentDetailParsed> e : parsedMap.entrySet()) {
                String mapKey = e.getKey().contains("#")
                        ? e.getKey().substring(0, e.getKey().lastIndexOf('#')) : e.getKey();
                if (mapKey.equals(className)) return ResponseEntity.ok(e.getValue());
            }
        }

        // parsed map에 없으면 raw HTML에서 즉석 파싱 시도
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
                com.heapdump.analyzer.model.ComponentDetailParsed p =
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

    // ── Thread Stacks API ──────────────────────────────────────────

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

    // ── MAT 리포트 ZIP 파일 서빙 (iframe용) ─────────────────────

    private static final Set<String> ALLOWED_REPORT_TYPES = Set.of("overview", "top_components", "suspects");

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

        // URI에서 ZIP 내 파일 경로 추출
        String prefix = "/report/" + filename + "/mat-page/" + reportType + "/";
        String fullPath = request.getRequestURI();
        int idx = fullPath.indexOf(prefix);
        String entryPath = (idx >= 0) ? fullPath.substring(idx + prefix.length()) : "";

        if (entryPath.isEmpty()) {
            entryPath = "index.html";
        }

        // URL 디코딩 + path traversal 방지
        try {
            entryPath = URLDecoder.decode(entryPath, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (entryPath.contains("..") || entryPath.startsWith("/") || entryPath.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }

        // ZIP 파일 찾기
        File zip = analyzerService.findReportZip(filename, reportType);
        if (zip == null) {
            return ResponseEntity.notFound().build();
        }

        // ZIP 엔트리 읽기
        byte[] content = readZipEntryBytes(zip, entryPath);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }

        // HTML 파일: mat:// 프로토콜 링크 비활성화
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

    // ── [NEW] Compare ────────────────────────────────────────────

    @GetMapping("/compare")
    public String compareDumps(
            @RequestParam String base,
            @RequestParam String target,
            Model model) {
        base = FilenameValidator.validate(base);
        target = FilenameValidator.validate(target);

        HeapAnalysisResult baseResult   = analyzerService.getCachedResult(base);
        HeapAnalysisResult targetResult = analyzerService.getCachedResult(target);

        if (baseResult == null || targetResult == null) {
            model.addAttribute("error",
                "Both files must be analyzed before comparison. " +
                "Please analyze: " +
                (baseResult == null ? base : "") + " " +
                (targetResult == null ? target : ""));
            return "compare";
        }

        model.addAttribute("baseResult",   baseResult);
        model.addAttribute("targetResult", targetResult);
        model.addAttribute("baseFile",   base);
        model.addAttribute("targetFile", target);

        // Top objects 비교: 클래스명 기준으로 retained heap 증감 계산
        Map<String, Long> baseMap   = buildClassSizeMap(baseResult);
        Map<String, Long> targetMap = buildClassSizeMap(targetResult);

        List<ClassDiff> diffs = new ArrayList<>();
        Set<String> allClasses = new HashSet<>();
        allClasses.addAll(baseMap.keySet());
        allClasses.addAll(targetMap.keySet());

        for (String cls : allClasses) {
            long baseSize   = baseMap.getOrDefault(cls, 0L);
            long targetSize = targetMap.getOrDefault(cls, 0L);
            long delta      = targetSize - baseSize;
            if (delta != 0) diffs.add(new ClassDiff(cls, baseSize, targetSize, delta));
        }
        diffs.sort((a, b) -> Long.compare(Math.abs(b.getDelta()), Math.abs(a.getDelta())));
        model.addAttribute("classDiffs", diffs.stream().limit(20).collect(Collectors.toList()));

        // 힙 크기 비교
        long heapDelta = targetResult.getUsedHeapSize() - baseResult.getUsedHeapSize();
        model.addAttribute("heapDelta", formatBytes(Math.abs(heapDelta)));
        model.addAttribute("heapDeltaSign", heapDelta >= 0 ? "+" : "-");
        model.addAttribute("heapDeltaUp", heapDelta > 0);

        return "compare";
    }

    // ── [NEW] API: 분석 히스토리 JSON ────────────────────────────

    @GetMapping("/api/history")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getHistory() {
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> items = buildHistory(files);
        List<Map<String, Object>> history = new ArrayList<>();

        for (AnalysisHistoryItem h : items) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename",      h.getFilename());
            item.put("formattedSize", h.getFormattedSize());
            item.put("lastModified",  h.getLastModified());
            item.put("status",        h.getStatus());
            item.put("fileDeleted",   h.isFileDeleted());
            if (!"NOT_ANALYZED".equals(h.getStatus())) {
                item.put("suspectCount",  h.getSuspectCount());
                item.put("analysisTime",  h.getAnalysisTime());
                item.put("heapUsed",      h.getHeapUsed());
            }
            history.add(item);
        }
        return ResponseEntity.ok(history);
    }

    // ── [NEW] API: 전체 저장 결과 삭제 ─────────────────────────────

    @PostMapping({"/api/results/clear", "/api/cache/clear"})
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearAllResults() {
        Set<String> keys = new HashSet<>(analyzerService.getCacheKeys());
        int cleared = 0;
        for (String key : keys) {
            analyzerService.clearCache(key);
            cleared++;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cleared", cleared);
        resp.put("message", "Cleared results for " + cleared + " files");
        logger.info("All saved results cleared: {} files", cleared);
        return ResponseEntity.ok(resp);
    }

    // ── [NEW] API: keep_unreachable 설정 ─────────────────────────

    @PostMapping("/api/settings/unreachable")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setUnreachable(
            @RequestParam boolean enabled) {
        analyzerService.setKeepUnreachableObjects(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keepUnreachableObjects", enabled);
        resp.put("message", "Setting updated. Takes effect on next analysis.");
        return ResponseEntity.ok(resp);
    }

    // ── [NEW] API: compress_after_analysis 설정 ────────────────

    @PostMapping("/api/settings/compress")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setCompressAfterAnalysis(
            @RequestParam boolean enabled) {
        analyzerService.setCompressAfterAnalysis(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("compressAfterAnalysis", enabled);
        resp.put("message", "Setting updated. Takes effect on next analysis.");
        return ResponseEntity.ok(resp);
    }

    // ── [NEW] API: 분석 큐 상태 ──────────────────────────────

    // ── [NEW] API: 분석 전 힙 메모리 사전 체크 ─────────────────────

    @GetMapping("/api/mat/heap-check")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkHeapBeforeAnalysis(@RequestParam String filename) {
        filename = FilenameValidator.validate(filename);
        Map<String, Object> resp = new LinkedHashMap<>();
        String safe = filename;

        // 덤프 파일 크기 확인
        File dumpFile = new File(analyzerService.getHeapDumpDirectory(), safe);
        File tmpFile = new File(analyzerService.getHeapDumpDirectory() + "/tmp", safe);
        File gzFile = new File(analyzerService.getHeapDumpDirectory(), safe + ".gz");

        long dumpSize = 0;
        if (tmpFile.exists()) dumpSize = tmpFile.length();
        else if (dumpFile.exists()) dumpSize = dumpFile.length();
        else if (gzFile.exists()) dumpSize = gzFile.length();  // 압축 파일은 원본보다 작지만 참고용

        long matHeap = analyzerService.getMatHeapSize();
        boolean warning = matHeap > 0 && dumpSize > 0 && dumpSize * 2 > matHeap;

        resp.put("warning", warning);
        resp.put("dumpSize", dumpSize);
        resp.put("dumpSizeFormatted", formatBytes(dumpSize));
        resp.put("recommendedHeap", formatBytes(dumpSize * 2));
        resp.put("matHeap", matHeap);
        resp.put("matHeapFormatted", matHeap > 0 ? formatBytes(matHeap) : "unknown");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/queue/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("queueSize", analyzerService.getQueueSize());
        resp.put("currentAnalysis", analyzerService.getCurrentAnalysisFilename());
        return ResponseEntity.ok(resp);
    }

    // ── API: 시스템 상태 (배너용) ──────────────────────────────────

    @GetMapping("/api/system/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();

        // MAT CLI
        resp.put("matCliReady", config.isMatCliReady());
        resp.put("matCliStatus", config.getMatCliStatusMessage());

        // Disk
        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists() && dumpDir.getTotalSpace() > 0) {
            long total = dumpDir.getTotalSpace();
            long usable = dumpDir.getUsableSpace();
            long used = total - usable;
            resp.put("diskUsedPercent", Math.round(used * 100.0 / total));
            resp.put("diskUsed", formatBytes(used));
            resp.put("diskTotal", formatBytes(total));
        }

        // JVM
        Runtime rt = Runtime.getRuntime();
        long jvmMax = rt.maxMemory();
        long jvmUsed = rt.totalMemory() - rt.freeMemory();
        resp.put("jvmUsedMb", jvmUsed / (1024 * 1024));
        resp.put("jvmMaxMb", jvmMax / (1024 * 1024));
        resp.put("jvmUsedPercent", Math.round(jvmUsed * 100.0 / jvmMax));

        // Queue
        resp.put("queueSize", analyzerService.getQueueSize());
        resp.put("currentAnalysis", analyzerService.getCurrentAnalysisFilename());

        return ResponseEntity.ok(resp);
    }

    // ── [NEW] API: MAT 힙 메모리 설정 ─────────────────────────────

    @GetMapping("/api/mat/heap")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMatHeap() {
        Map<String, Object> resp = new LinkedHashMap<>();
        String heapStr = analyzerService.getMatHeapSizeString();
        long heapBytes = analyzerService.getMatHeapSize();
        resp.put("heapSize", heapStr != null ? heapStr : "unknown");
        resp.put("heapBytes", heapBytes);
        resp.put("heapFormatted", heapBytes > 0 ? formatBytes(heapBytes) : "unknown");

        String xmsStr = analyzerService.getMatInitialHeapSizeString();
        long xmsBytes = analyzerService.getMatInitialHeapSize();
        resp.put("xmsSize", xmsStr != null ? xmsStr : "none");
        resp.put("xmsBytes", xmsBytes);
        resp.put("xmsFormatted", xmsBytes > 0 ? formatBytes(xmsBytes) : "not set");

        // 시스템 물리 메모리
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long physMem = osBean.getTotalPhysicalMemorySize();
            resp.put("physicalMemory", physMem);
            resp.put("physicalMemoryFormatted", formatBytes(physMem));
        } catch (Exception e) {
            resp.put("physicalMemory", -1);
            resp.put("physicalMemoryFormatted", "unknown");
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/mat/heap")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setMatHeap(
            @RequestParam String size,
            @RequestParam(required = false) String type) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!size.matches("\\d+[mMgG]")) {
            resp.put("error", "잘못된 형식입니다. 예: 4096m, 8g");
            return ResponseEntity.badRequest().body(resp);
        }
        try {
            if ("xms".equalsIgnoreCase(type)) {
                analyzerService.setMatInitialHeapSize(size);
                resp.put("message", "MAT 초기 힙 메모리가 -Xms" + size + "으로 변경되었습니다.");
                logger.info("[Settings] MAT initial heap (-Xms) changed to: {}", size);
            } else {
                analyzerService.setMatHeapSize(size);
                resp.put("message", "MAT 최대 힙 메모리가 -Xmx" + size + "으로 변경되었습니다.");
                logger.info("[Settings] MAT max heap (-Xmx) changed to: {}", size);
            }
            resp.put("heapSize", size);
            return ResponseEntity.ok(resp);
        } catch (IOException e) {
            resp.put("error", "설정 변경 실패: " + e.getMessage());
            logger.error("[Settings] Failed to change MAT heap: {}", e.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
    }

    // ── [NEW] API: 디스크 사용량 체크 ────────────────────────────
    @GetMapping("/api/disk/check")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkDisk() {
        Map<String, Object> resp = new LinkedHashMap<>();
        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists()) {
            long totalSpace = dumpDir.getTotalSpace();
            long usableSpace = dumpDir.getUsableSpace();
            double usagePercent = totalSpace > 0 ? (double)(totalSpace - usableSpace) / totalSpace * 100 : 0;
            resp.put("usagePercent", Math.round(usagePercent * 10) / 10.0);
            resp.put("totalSpace", formatBytes(totalSpace));
            resp.put("usableSpace", formatBytes(usableSpace));
            resp.put("usableSpaceBytes", usableSpace);
            resp.put("warning", usagePercent >= config.getDiskWarningUsagePercent());
        } else {
            resp.put("warning", false);
        }
        return ResponseEntity.ok(resp);
    }

    // ── LLM API 엔드포인트 ──────────────────────────────────────

    @PostMapping("/api/llm/enabled")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmEnabled(@RequestParam boolean enabled) {
        analyzerService.setLlmEnabled(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("enabled", enabled);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/config")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmConfig(@RequestBody Map<String, Object> body) {
        String provider = (String) body.getOrDefault("provider", analyzerService.getLlmProvider());
        String apiUrl = (String) body.get("apiUrl");
        String model = (String) body.get("model");
        int maxIn = body.containsKey("maxInputTokens")
                ? Integer.parseInt(String.valueOf(body.get("maxInputTokens")))
                : analyzerService.getLlmMaxInputTokens();
        int maxOut = body.containsKey("maxOutputTokens")
                ? Integer.parseInt(String.valueOf(body.get("maxOutputTokens")))
                : analyzerService.getLlmMaxOutputTokens();

        // provider 변경 시 apiUrl 미지정이면 기본값
        if (apiUrl == null || apiUrl.isEmpty()) {
            apiUrl = analyzerService.getDefaultApiUrl(provider);
        }
        if (model == null) model = analyzerService.getLlmModel();

        analyzerService.setLlmConfig(provider, apiUrl, model, maxIn, maxOut);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("provider", provider);
        resp.put("apiUrl", apiUrl);
        resp.put("model", model);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/apikey")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setLlmApiKey(@RequestBody Map<String, String> body) {
        String key = body.get("apiKey");
        if (key == null) key = "";
        analyzerService.setLlmApiKey(key.trim());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("apiKeySet", analyzerService.isLlmApiKeySet());
        resp.put("apiKeyMasked", analyzerService.getLlmApiKeyMasked());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/llm/test-connection")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testLlmConnection() {
        Map<String, Object> result = analyzerService.testLlmConnection();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/llm/analyze")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> analyzeLlm(@RequestBody Map<String, Object> body) {
        String prompt = (String) body.get("prompt");
        String filename = (String) body.get("filename");
        if (prompt == null || prompt.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "분석 프롬프트가 비어있습니다");
            return ResponseEntity.badRequest().body(err);
        }
        logger.info("[LLM] AI analysis requested for: {}", filename);
        Map<String, Object> result = analyzerService.callLlmAnalysis(prompt);
        return ResponseEntity.ok(result);
    }

    // ── [NEW] API: 현재 설정 조회 ────────────────────────────────

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("keepUnreachableObjects", analyzerService.isKeepUnreachableObjects());
        settings.put("heapDumpDirectory",      maskPath(analyzerService.getHeapDumpDirectory()));
        settings.put("cachedResults",          analyzerService.getCachedResultCount());

        // System info (JVM runtime only — no OS/vendor details)
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("javaVersion",  System.getProperty("java.version"));
        Runtime rt = Runtime.getRuntime();
        system.put("jvmMaxMemory",     formatBytes(rt.maxMemory()));
        system.put("jvmTotalMemory",   formatBytes(rt.totalMemory()));
        system.put("jvmFreeMemory",    formatBytes(rt.freeMemory()));
        system.put("jvmUsedMemory",    formatBytes(rt.totalMemory() - rt.freeMemory()));
        system.put("availableProcessors", rt.availableProcessors());
        settings.put("system", system);

        // Disk info (usage percentages only — no absolute capacity)
        Map<String, Object> disk = new LinkedHashMap<>();
        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists()) {
            disk.put("totalSpace",  formatBytes(dumpDir.getTotalSpace()));
            disk.put("freeSpace",   formatBytes(dumpDir.getUsableSpace()));
            disk.put("usableSpace", formatBytes(dumpDir.getUsableSpace()));
            long used = dumpDir.getTotalSpace() - dumpDir.getUsableSpace();
            disk.put("usedSpace",   formatBytes(used));
            disk.put("usedPercent", dumpDir.getTotalSpace() > 0
                    ? Math.round(used * 100.0 / dumpDir.getTotalSpace()) : 0);
        }
        settings.put("disk", disk);

        // MAT CLI status (ready/status only — no path or file permission details)
        Map<String, Object> mat = new LinkedHashMap<>();
        mat.put("path",       maskPath(analyzerService.getMatCliPath()));
        mat.put("ready",      analyzerService.isMatCliReady());
        mat.put("statusMessage", analyzerService.getMatCliStatusMessage());
        String matHeapStr = analyzerService.getMatHeapSizeString();
        long matHeapBytes = analyzerService.getMatHeapSize();
        mat.put("heapSize", matHeapStr != null ? matHeapStr : "unknown");
        mat.put("heapBytes", matHeapBytes);
        mat.put("heapFormatted", matHeapBytes > 0 ? formatBytes(matHeapBytes) : "unknown");
        String matXmsStr = analyzerService.getMatInitialHeapSizeString();
        long matXmsBytes = analyzerService.getMatInitialHeapSize();
        mat.put("xmsSize", matXmsStr != null ? matXmsStr : "none");
        mat.put("xmsBytes", matXmsBytes);
        mat.put("xmsFormatted", matXmsBytes > 0 ? formatBytes(matXmsBytes) : "not set");
        settings.put("mat", mat);

        // File stats
        List<HeapDumpFile> files = analyzerService.listFiles();
        Map<String, Object> fileStats = new LinkedHashMap<>();
        fileStats.put("totalFiles", files.size());
        long originalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        long diskBytes = files.stream()
            .mapToLong(f -> f.isCompressed() && f.getCompressedSize() > 0 ? f.getCompressedSize() : f.getSize())
            .sum();
        fileStats.put("totalSize", formatBytes(originalBytes));
        fileStats.put("diskSize", formatBytes(diskBytes));
        fileStats.put("analyzedCount", files.stream()
                .filter(f -> analyzerService.getCachedResult(f.getName()) != null)
                .count());
        settings.put("files", fileStats);

        // LLM 설정
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("enabled", analyzerService.isLlmEnabled());
        llm.put("provider", analyzerService.getLlmProvider());
        llm.put("apiUrl", analyzerService.getLlmApiUrl());
        llm.put("model", analyzerService.getLlmModel());
        llm.put("apiKeySet", analyzerService.isLlmApiKeySet());
        llm.put("apiKeyMasked", analyzerService.getLlmApiKeyMasked());
        llm.put("maxInputTokens", analyzerService.getLlmMaxInputTokens());
        llm.put("maxOutputTokens", analyzerService.getLlmMaxOutputTokens());
        llm.put("availableProviders", Arrays.asList("claude", "gpt", "genspark", "custom"));
        Map<String, List<String>> providerModels = new LinkedHashMap<>();
        providerModels.put("claude", Arrays.asList("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001", "claude-opus-4-20250514"));
        providerModels.put("gpt", Arrays.asList("gpt-4o", "gpt-4o-mini", "gpt-4-turbo"));
        providerModels.put("genspark", Collections.emptyList());
        providerModels.put("custom", Collections.emptyList());
        llm.put("providerModels", providerModels);
        settings.put("llm", llm);

        return ResponseEntity.ok(settings);
    }

    /** 절대 경로를 마스킹하여 파일/디렉토리 이름만 반환 */
    private String maskPath(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) return "-";
        java.nio.file.Path p = java.nio.file.Paths.get(absolutePath);
        java.nio.file.Path fileName = p.getFileName();
        return fileName != null ? "***/" + fileName.toString() : "-";
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────

    private ResponseEntity<String> htmlResponse(String html) {
        if (html == null || html.isBlank()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html",
                        java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }

    private String truncateLog(String log, int maxLen) {
        if (log == null) return "";
        if (log.length() <= maxLen) return log;
        return log.substring(0, maxLen) + "\n\n[truncated — " + log.length() + " chars total]";
    }

    private String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "-";
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec = sec % 60;
        return min + "m " + sec + "s";
    }

    private List<AnalysisHistoryItem> buildHistory(List<HeapDumpFile> files) {
        List<AnalysisHistoryItem> history = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
        Set<String> fileNames = new HashSet<>();

        // 1. 물리 파일이 존재하는 항목
        for (HeapDumpFile file : files) {
            fileNames.add(file.getName());
            HeapAnalysisResult result = analyzerService.getCachedResult(file.getName());
            AnalysisHistoryItem item = new AnalysisHistoryItem();
            item.setFilename(file.getName());
            item.setFormattedSize(file.getFormattedSize());
            item.setFormattedDate(sdf.format(new Date(file.getLastModified())));
            item.setLastModified(file.getLastModified());
            item.setFileDeleted(false);
            item.setCompressed(file.isCompressed());
            if (file.isCompressed()) {
                item.setFormattedOriginalSize(file.getFormattedOriginalSize());
                item.setFormattedCompressedSize(file.getFormattedCompressedSize());
            }
            if (result != null) {
                item.setStatus(result.getAnalysisStatus().name());
                item.setSuspectCount(result.getLeakSuspects() != null
                        ? result.getLeakSuspects().size() : 0);
                item.setAnalysisTime(result.getAnalysisTime());
                item.setFormattedAnalysisTime(formatDuration(result.getAnalysisTime()));
                item.setHeapUsed(result.getFormattedUsedHeapSize());
            } else {
                item.setStatus("NOT_ANALYZED");
            }
            history.add(item);
        }

        // 2. 소스 파일이 삭제되었지만 분석 결과가 남아있는 항목
        for (HeapAnalysisResult result : analyzerService.getAllCachedResults()) {
            if (result.getFilename() != null && !fileNames.contains(result.getFilename())) {
                AnalysisHistoryItem item = new AnalysisHistoryItem();
                item.setFilename(result.getFilename());
                item.setFormattedSize(formatBytes(result.getFileSize()));
                item.setFormattedDate(sdf.format(new Date(result.getLastModified())));
                item.setLastModified(result.getLastModified());
                item.setFileDeleted(true);
                item.setStatus(result.getAnalysisStatus().name());
                item.setSuspectCount(result.getLeakSuspects() != null
                        ? result.getLeakSuspects().size() : 0);
                item.setAnalysisTime(result.getAnalysisTime());
                item.setFormattedAnalysisTime(formatDuration(result.getAnalysisTime()));
                item.setHeapUsed(result.getFormattedUsedHeapSize());
                history.add(item);
            }
        }

        // lastModified 기준 내림차순 정렬
        history.sort(Comparator.comparingLong(AnalysisHistoryItem::getLastModified).reversed());
        return history;
    }

    private Map<String, Long> buildClassSizeMap(HeapAnalysisResult result) {
        Map<String, Long> map = new HashMap<>();
        if (result.getTopMemoryObjects() != null) {
            for (MemoryObject obj : result.getTopMemoryObjects()) {
                map.put(obj.getClassName(), obj.getTotalSize());
            }
        }
        return map;
    }

    // ── Inner DTOs ────────────────────────────────────────────────

    public static class AnalysisHistoryItem {
        private String  filename;
        private String  formattedSize;
        private String  formattedDate;
        private String  status;
        private int     suspectCount;
        private long    analysisTime;
        private String  formattedAnalysisTime;
        private String  heapUsed;
        private boolean fileDeleted;
        private long    lastModified;
        private boolean compressed;
        private String  formattedOriginalSize;
        private String  formattedCompressedSize;

        public String  getFilename()      { return filename; }
        public void    setFilename(String v)      { filename = v; }
        public String  getFormattedSize() { return formattedSize; }
        public void    setFormattedSize(String v) { formattedSize = v; }
        public String  getFormattedDate() { return formattedDate; }
        public void    setFormattedDate(String v) { formattedDate = v; }
        public String  getStatus()        { return status; }
        public void    setStatus(String v)        { status = v; }
        public int     getSuspectCount()  { return suspectCount; }
        public void    setSuspectCount(int v)     { suspectCount = v; }
        public long    getAnalysisTime()  { return analysisTime; }
        public void    setAnalysisTime(long v)    { analysisTime = v; }
        public String  getFormattedAnalysisTime() { return formattedAnalysisTime; }
        public void    setFormattedAnalysisTime(String v) { formattedAnalysisTime = v; }
        public String  getHeapUsed()      { return heapUsed; }
        public void    setHeapUsed(String v)      { heapUsed = v; }
        public boolean isFileDeleted()    { return fileDeleted; }
        public void    setFileDeleted(boolean v)  { fileDeleted = v; }
        public long    getLastModified()  { return lastModified; }
        public void    setLastModified(long v)    { lastModified = v; }
        public boolean isCompressed()    { return compressed; }
        public void    setCompressed(boolean v)   { compressed = v; }
        public String  getFormattedOriginalSize() { return formattedOriginalSize; }
        public void    setFormattedOriginalSize(String v) { formattedOriginalSize = v; }
        public String  getFormattedCompressedSize() { return formattedCompressedSize; }
        public void    setFormattedCompressedSize(String v) { formattedCompressedSize = v; }
    }

    public static class ClassDiff {
        private final String className;
        private final long   baseSize;
        private final long   targetSize;
        private final long   delta;

        public ClassDiff(String className, long baseSize, long targetSize, long delta) {
            this.className  = className;
            this.baseSize   = baseSize;
            this.targetSize = targetSize;
            this.delta      = delta;
        }
        public String getClassName()  { return className; }
        public long   getBaseSize()   { return baseSize; }
        public long   getTargetSize() { return targetSize; }
        public long   getDelta()      { return delta; }
        public String getFormattedDelta() {
            String sign = delta > 0 ? "+" : "";
            long abs = Math.abs(delta);
            if (abs < 1024) return sign + delta + " B";
            if (abs < 1048576) return sign + String.format("%.1f KB", delta / 1024.0);
            return sign + String.format("%.2f MB", delta / (1024.0 * 1024));
        }
        public boolean isIncrease() { return delta > 0; }
    }
}

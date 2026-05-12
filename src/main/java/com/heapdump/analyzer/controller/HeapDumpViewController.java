package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.PdfReportService;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Heap Dump Analyzer View Controller (Phase 4B).
 *
 * Thymeleaf 페이지 렌더 + form POST → redirect 액션만 담당.
 * REST API · SSE · 바이너리/iframe 컨텐츠는 {@link HeapDumpController} 잔류.
 *
 * 분리 전략 (2 분할):
 *  - View 컨트롤러는 {@link HeapDumpController} 를 주입받아 공유 헬퍼
 *    (isAdmin / buildHistory / aggregateDetections / buildClassSizeMap / truncateLog) 위임 호출
 *  - DTO ({@code AnalysisHistoryItem}, {@code DetectionAggregate} 등) 는 HeapDumpController 의 inner public static class 그대로 사용
 */
@Controller
public class HeapDumpViewController {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpViewController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final HeapDumpConfig config;
    private final PdfReportService pdfReportService;
    private final HeapDumpController apiController;

    public HeapDumpViewController(HeapDumpAnalyzerService analyzerService,
                                  HeapDumpConfig config,
                                  PdfReportService pdfReportService,
                                  HeapDumpController apiController) {
        this.analyzerService = analyzerService;
        this.config = config;
        this.pdfReportService = pdfReportService;
        this.apiController = apiController;
    }

    // ── 메인 페이지 ──────────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        model.addAttribute("files", files.size() > 5 ? files.subList(0, 5) : files);
        model.addAttribute("allFiles", files);
        model.addAttribute("fileCount", files.size());

        long totalBytes = files.stream()
            .mapToLong(f -> f.isCompressed() && f.getCompressedSize() > 0 ? f.getCompressedSize() : f.getSize())
            .sum();
        model.addAttribute("totalSize", FormatUtils.formatBytes(totalBytes));

        // 대시보드는 deleted 항목 항상 제외 (모든 계정)
        List<HeapDumpController.AnalysisHistoryItem> history = apiController.buildHistory(files).stream()
            .filter(h -> !h.isFileDeleted())
            .collect(Collectors.toList());
        model.addAttribute("analyzedCount",
            history.stream().filter(h -> "SUCCESS".equals(h.getStatus())).count());

        long totalSuspects = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()))
            .mapToLong(HeapDumpController.AnalysisHistoryItem::getSuspectCount)
            .sum();
        model.addAttribute("totalSuspects", totalSuspects > 0 ? totalSuspects : null);

        Set<String> analyzedFiles = history.stream()
                .filter(h -> "SUCCESS".equals(h.getStatus()))
                .map(HeapDumpController.AnalysisHistoryItem::getFilename)
                .collect(Collectors.toSet());
        model.addAttribute("analyzedFiles", analyzedFiles);

        Set<String> errorFiles = history.stream()
                .filter(h -> "ERROR".equals(h.getStatus()))
                .map(HeapDumpController.AnalysisHistoryItem::getFilename)
                .collect(Collectors.toSet());
        model.addAttribute("errorFiles", errorFiles);

        HeapDumpController.DetectionAggregate agg = apiController.aggregateDetections(history, 14, 12);
        model.addAttribute("criticalCount", agg.getCriticalCount());
        model.addAttribute("highCount", agg.getHighCount());
        model.addAttribute("mediumCount", agg.getMediumCount());
        model.addAttribute("lowCount", agg.getLowCount());
        model.addAttribute("detectionItems", agg.getDetectionItems());
        model.addAttribute("hasDetections",
            agg.getCriticalCount() + agg.getHighCount() + agg.getMediumCount() + agg.getLowCount() > 0);

        model.addAttribute("dailyDetections", agg.getDailyDetections());
        model.addAttribute("serverSeries", agg.getServerSeries());
        model.addAttribute("kpiTotal14d", agg.getTotal());
        model.addAttribute("kpiLast7d", agg.getLast7d());
        model.addAttribute("kpiPrev7d", agg.getPrev7d());
        model.addAttribute("kpiDelta7d", agg.getDelta7d());
        model.addAttribute("kpiPeakDay", agg.getPeakDay() != null ? agg.getPeakDay() : "-");
        model.addAttribute("kpiPeakCount", agg.getPeakCount());

        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists() && dumpDir.getTotalSpace() > 0) {
            long total = dumpDir.getTotalSpace();
            long usable = dumpDir.getUsableSpace();
            long used = total - usable;
            model.addAttribute("diskUsedPercent", Math.round(used * 100.0 / total));
            model.addAttribute("diskUsed", FormatUtils.formatBytes(used));
            model.addAttribute("diskTotal", FormatUtils.formatBytes(total));
        }

        model.addAttribute("matKeepUnreachable", analyzerService.isKeepUnreachableObjects());

        return "index";
    }

    // ── 전체 파일 목록 ────────────────────────────────────────────

    @GetMapping("/files")
    public String filesPage(Model model, Authentication authentication) {
        boolean isAdmin = apiController.isAdmin(authentication);
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<HeapDumpController.AnalysisHistoryItem> history = apiController.buildHistory(files);

        List<HeapDumpController.AnalysisHistoryItem> visible = isAdmin ? history :
                history.stream().filter(h -> !h.isFileDeleted()).collect(Collectors.toList());
        model.addAttribute("analysisHistory", visible);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("fileCount", files.size());

        // 실제 파일이 존재하는 행(fileDeleted=false)에 기반한 서버만 노출 — DB 잔존 기록 제외.
        List<String> serverNames = visible.stream()
                .filter(h -> !h.isFileDeleted())
                .map(HeapDumpController.AnalysisHistoryItem::getServerName)
                .filter(n -> n != null && !n.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        model.addAttribute("serverNames", serverNames);

        long originalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        long diskBytes = files.stream()
            .mapToLong(f -> f.isCompressed() && f.getCompressedSize() > 0 ? f.getCompressedSize() : f.getSize())
            .sum();
        model.addAttribute("totalSize", FormatUtils.formatBytes(originalBytes));
        model.addAttribute("diskSize", FormatUtils.formatBytes(diskBytes));
        model.addAttribute("hasCompressed", originalBytes != diskBytes);

        long analyzedCount = visible.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus())).count();
        model.addAttribute("analyzedCount", analyzedCount);

        return "files";
    }

    // ── 분석 이력 페이지 ─────────────────────────────────────────

    @GetMapping("/history")
    public String historyPage(Model model, Authentication authentication) {
        boolean isAdmin = apiController.isAdmin(authentication);
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<HeapDumpController.AnalysisHistoryItem> history = apiController.buildHistory(files);

        List<HeapDumpController.AnalysisHistoryItem> analysisOnly = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()) || "ERROR".equals(h.getStatus()))
            .filter(h -> isAdmin || !h.isFileDeleted())
            .collect(Collectors.toList());
        model.addAttribute("analysisHistory", analysisOnly);
        model.addAttribute("isAdmin", isAdmin);

        long successCount = analysisOnly.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus())).count();
        long errorCount = analysisOnly.stream()
            .filter(h -> "ERROR".equals(h.getStatus())).count();
        long deletedCount = analysisOnly.stream()
            .filter(HeapDumpController.AnalysisHistoryItem::isFileDeleted).count();
        model.addAttribute("totalCount", analysisOnly.size());
        model.addAttribute("successCount", successCount);
        model.addAttribute("errorCount", errorCount);
        model.addAttribute("deletedCount", deletedCount);

        HeapDumpController.DetectionAggregate detectAgg = apiController.aggregateDetections(analysisOnly, 14, 12);
        model.addAttribute("detectInitDays", 14);
        model.addAttribute("detectInitGroup", "server");
        model.addAttribute("detectLabels", detectAgg.getLabels());
        model.addAttribute("detectServerSeries", detectAgg.getServerSeries());
        model.addAttribute("detectSeveritySeries", detectAgg.getSeveritySeries());
        model.addAttribute("detectKpiTotal", detectAgg.getTotal());
        model.addAttribute("detectKpiLast7d", detectAgg.getLast7d());
        model.addAttribute("detectKpiPrev7d", detectAgg.getPrev7d());
        model.addAttribute("detectKpiDelta7d", detectAgg.getDelta7d());
        model.addAttribute("detectKpiPeakDay",
            detectAgg.getPeakDay() != null ? detectAgg.getPeakDay() : "-");
        model.addAttribute("detectKpiPeakCount", detectAgg.getPeakCount());
        model.addAttribute("detectRecent",
            detectAgg.getRecent() != null ? detectAgg.getRecent() : Collections.emptyList());

        return "history";
    }

    // ── 설정 페이지 ─────────────────────────────────────────────

    @GetMapping("/settings")
    public String settingsPage(Model model) {
        model.addAttribute("matKeepUnreachable", analyzerService.isKeepUnreachableObjects());
        model.addAttribute("compressAfterAnalysis", analyzerService.isCompressAfterAnalysis());
        return "settings";
    }

    @GetMapping("/settings/llm")
    public String llmSettingsPage() {
        return "llm-settings";
    }

    @GetMapping("/settings/rag")
    public String ragSettingsPage() {
        return "rag-settings";
    }

    // ── 히스토리 삭제 (form POST → redirect) ─────────────────────

    @PostMapping("/history/delete/{filename:.+}")
    public String deleteHistory(@PathVariable String filename,
                                @RequestParam(value = "deleteHeapDump", defaultValue = "false") boolean deleteHeapDump,
                                @RequestHeader(value = "Referer", required = false) String referer,
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
        if (referer != null && referer.contains("/files")) {
            return "redirect:/files";
        }
        return "redirect:/history";
    }

    // ── 파일 업로드 (form POST → redirect) ──────────────────────

    @PostMapping("/upload")
    public String uploadFile(@RequestParam("file") MultipartFile file,
                             RedirectAttributes redirectAttributes) {
        String originalName = file.getOriginalFilename();
        logger.info("[Upload] Request received: filename={}, size={}", originalName, FormatUtils.formatBytes(file.getSize()));

        try {
            if (file.isEmpty()) {
                logger.warn("[Upload] Rejected: empty file submitted");
                redirectAttributes.addFlashAttribute("error", "Please select a file to upload");
                return "redirect:/";
            }
            String filename = analyzerService.uploadFile(file);
            logger.info("[Upload] Success: {}", filename);
            String successMsg = "Uploaded: " + filename + " (" + FormatUtils.formatBytes(file.getSize()) + ")";
            redirectAttributes.addFlashAttribute("success", successMsg);

            // 디스크 사용량 경고
            try {
                File dumpDir = new File(analyzerService.getHeapDumpDirectory());
                long totalSpace = dumpDir.getTotalSpace();
                long usableSpace = dumpDir.getUsableSpace();
                if (totalSpace > 0) {
                    double usagePercent = (double) (totalSpace - usableSpace) / totalSpace * 100;
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

    // ── 재분석 (form POST → redirect) ──────────────────────────

    @PostMapping("/analyze/rerun/{filename:.+}")
    public String rerunAnalysis(@PathVariable String filename, RedirectAttributes redirectAttributes) {
        filename = FilenameValidator.validate(filename);

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

    // ── 분석 결과 화면 ───────────────────────────────────────────

    @GetMapping("/analyze/result/{filename:.+}")
    public String analyzeResult(@PathVariable String filename, Model model) {
        filename = FilenameValidator.validate(filename);
        HeapAnalysisResult result = analyzerService.getCachedResult(filename);
        if (result == null) return "redirect:/analyze/" + filename;

        if (result.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR) {
            model.addAttribute("error", result.getErrorMessage());
            model.addAttribute("filename", filename);
            model.addAttribute("matLog",
                    apiController.truncateLog(result.getMatLog(), config.getMatLogMaxDisplayChars()));
            model.addAttribute("hasHeapData", false);
            model.addAttribute("matLogTotalLen",
                    result.getMatLog() != null ? result.getMatLog().length() : 0);
            model.addAttribute("errorDate",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                            .format(new Date(result.getLastModified())));
            model.addAttribute("errorAnalysisTime", result.getAnalysisTime());
            model.addAttribute("errorFileSize", FormatUtils.formatBytes(result.getFileSize()));
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

        model.addAttribute("hasHistogram", result.getHistogramHtml() != null && !result.getHistogramHtml().isEmpty());
        model.addAttribute("hasThreadOverview", result.getThreadOverviewHtml() != null && !result.getThreadOverviewHtml().isEmpty());
        model.addAttribute("hasThreadStacks", result.getThreadStacksText() != null && !result.getThreadStacksText().isEmpty());

        model.addAttribute("histogramEntries", result.getHistogramEntries());
        model.addAttribute("threadInfos", result.getThreadInfos());
        model.addAttribute("totalHistogramClasses", result.getTotalHistogramClasses());
        model.addAttribute("threadCount", result.getThreadInfos() != null ? result.getThreadInfos().size() : 0);

        List<String> threadStacks = new ArrayList<>();
        if (result.getThreadInfos() != null) {
            for (com.heapdump.analyzer.model.ThreadInfo ti : result.getThreadInfos()) {
                threadStacks.add(ti.getStackTrace() != null ? ti.getStackTrace() : "");
            }
        }
        model.addAttribute("threadStacks", threadStacks);

        model.addAttribute("hasOverviewZip", analyzerService.hasReportZip(filename, "overview"));
        model.addAttribute("hasTopComponentsZip", analyzerService.hasReportZip(filename, "top_components"));
        model.addAttribute("hasSuspectsZip", analyzerService.hasReportZip(filename, "suspects"));

        model.addAttribute("llmChatRestoreIncludeHistory", analyzerService.isLlmChatRestoreIncludeHistory());

        return "analyze";
    }

    // ── A4 1페이지 PDF 리포트 미리보기 + HTML fallback ────────────

    @GetMapping("/analyze/{filename:.+}/print-preview")
    public String printPreviewPage(@PathVariable String filename, Model model) {
        String safe = FilenameValidator.validate(filename);
        HeapAnalysisResult result = analyzerService.getCachedResult(safe);
        if (result == null
                || result.getAnalysisStatus() != HeapAnalysisResult.AnalysisStatus.SUCCESS) {
            return "redirect:/analyze/result/" + safe;
        }
        model.addAttribute("filename", safe);
        String base = safe.replaceAll("\\.(hprof|bin|dump)(\\.gz)?$", "");
        model.addAttribute("baseName", base);
        model.addAttribute("downloadName", base + "-report.pdf");
        return "analyze-print-preview";
    }

    @GetMapping("/analyze/{filename:.+}/print-html")
    public String printHtmlPage(@PathVariable String filename, Model model) {
        String safe = FilenameValidator.validate(filename);
        HeapAnalysisResult result = analyzerService.getCachedResult(safe);
        if (result == null
                || result.getAnalysisStatus() != HeapAnalysisResult.AnalysisStatus.SUCCESS) {
            return "redirect:/analyze/result/" + safe;
        }
        pdfReportService.buildPrintModel(safe, result).forEach(model::addAttribute);
        return "analyze-print";
    }

    // ── 파일 삭제 (form POST → redirect) ────────────────────────

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

    // ── 두 덤프 비교 ────────────────────────────────────────────

    @GetMapping("/compare")
    public String compareDumps(
            @RequestParam(required = false) String base,
            @RequestParam(required = false) String target,
            Model model) {
        if (base == null || target == null || base.isEmpty() || target.isEmpty()) {
            return "compare";
        }
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

        Map<String, Long> baseMap   = apiController.buildClassSizeMap(baseResult);
        Map<String, Long> targetMap = apiController.buildClassSizeMap(targetResult);

        List<HeapDumpController.ClassDiff> diffs = new ArrayList<>();
        Set<String> allClasses = new HashSet<>();
        allClasses.addAll(baseMap.keySet());
        allClasses.addAll(targetMap.keySet());

        for (String cls : allClasses) {
            long baseSize   = baseMap.getOrDefault(cls, 0L);
            long targetSize = targetMap.getOrDefault(cls, 0L);
            long delta      = targetSize - baseSize;
            if (delta != 0) diffs.add(new HeapDumpController.ClassDiff(cls, baseSize, targetSize, delta));
        }
        diffs.sort((a, b) -> Long.compare(Math.abs(b.getDelta()), Math.abs(a.getDelta())));
        model.addAttribute("classDiffs", diffs.stream().limit(20).collect(Collectors.toList()));

        long heapDelta = targetResult.getUsedHeapSize() - baseResult.getUsedHeapSize();
        model.addAttribute("heapDelta", FormatUtils.formatBytes(Math.abs(heapDelta)));
        model.addAttribute("heapDeltaSign", heapDelta >= 0 ? "+" : "-");
        model.addAttribute("heapDeltaUp", heapDelta > 0);

        return "compare";
    }
}

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.ClassDiff;
import com.heapdump.analyzer.model.dto.DetectionAggregate;
import com.heapdump.analyzer.model.dto.HistogramDiff;
import com.heapdump.analyzer.model.dto.KpiDiff;
import com.heapdump.analyzer.model.dto.SuspectDiff;
import com.heapdump.analyzer.service.ComparisonHistoryService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.HeapHistoryAggregator;
import com.heapdump.analyzer.service.PdfReportService;
import com.heapdump.analyzer.util.AuthUtil;
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
 * REST API · SSE · 바이너리/iframe 컨텐츠는 6 개 API 컨트롤러로 도메인 분리됨 (Phase 4B-2).
 */
@Controller
public class HeapDumpViewController {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpViewController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final HeapDumpConfig config;
    private final PdfReportService pdfReportService;
    private final HeapHistoryAggregator aggregator;
    private final ComparisonHistoryService comparisonHistoryService;

    public HeapDumpViewController(HeapDumpAnalyzerService analyzerService,
                                  HeapDumpConfig config,
                                  PdfReportService pdfReportService,
                                  HeapHistoryAggregator aggregator,
                                  ComparisonHistoryService comparisonHistoryService) {
        this.analyzerService = analyzerService;
        this.config = config;
        this.pdfReportService = pdfReportService;
        this.aggregator = aggregator;
        this.comparisonHistoryService = comparisonHistoryService;
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
        List<AnalysisHistoryItem> history = aggregator.buildHistory(files).stream()
            .filter(h -> !h.isFileDeleted())
            .collect(Collectors.toList());
        model.addAttribute("analyzedCount",
            history.stream().filter(h -> "SUCCESS".equals(h.getStatus())).count());

        long totalSuspects = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()))
            .mapToLong(AnalysisHistoryItem::getSuspectCount)
            .sum();
        model.addAttribute("totalSuspects", totalSuspects > 0 ? totalSuspects : null);

        Set<String> analyzedFiles = history.stream()
                .filter(h -> "SUCCESS".equals(h.getStatus()))
                .map(AnalysisHistoryItem::getFilename)
                .collect(Collectors.toSet());
        model.addAttribute("analyzedFiles", analyzedFiles);

        Set<String> errorFiles = history.stream()
                .filter(h -> "ERROR".equals(h.getStatus()))
                .map(AnalysisHistoryItem::getFilename)
                .collect(Collectors.toSet());
        model.addAttribute("errorFiles", errorFiles);

        DetectionAggregate agg = aggregator.aggregateDetections(history, 14, 12);
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

        long maxUpload = analyzerService.getMaxUploadSizeBytes();
        model.addAttribute("maxUploadSizeBytes", maxUpload);
        model.addAttribute("maxUploadSizeGb", maxUpload / (1024L * 1024 * 1024));
        model.addAttribute("allowAllExtensions", analyzerService.isAllowAllExtensions());

        return "index";
    }

    // ── 전체 파일 목록 ────────────────────────────────────────────

    @GetMapping("/files")
    public String filesPage(Model model, Authentication authentication) {
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> history = aggregator.buildHistory(files);

        List<AnalysisHistoryItem> visible = isAdmin ? history :
                history.stream().filter(h -> !h.isFileDeleted()).collect(Collectors.toList());
        model.addAttribute("analysisHistory", visible);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("fileCount", files.size());

        // 실제 파일이 존재하는 행(fileDeleted=false)에 기반한 서버만 노출 — DB 잔존 기록 제외.
        List<String> serverNames = visible.stream()
                .filter(h -> !h.isFileDeleted())
                .map(AnalysisHistoryItem::getServerName)
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

        long maxUpload = analyzerService.getMaxUploadSizeBytes();
        model.addAttribute("maxUploadSizeBytes", maxUpload);
        model.addAttribute("maxUploadSizeGb", maxUpload / (1024L * 1024 * 1024));
        model.addAttribute("allowAllExtensions", analyzerService.isAllowAllExtensions());

        return "files";
    }

    // ── 분석 이력 페이지 ─────────────────────────────────────────

    @GetMapping("/history")
    public String historyPage(Model model, Authentication authentication) {
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> history = aggregator.buildHistory(files);

        List<AnalysisHistoryItem> analysisOnly = history.stream()
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
            .filter(AnalysisHistoryItem::isFileDeleted).count();
        model.addAttribute("totalCount", analysisOnly.size());
        model.addAttribute("successCount", successCount);
        model.addAttribute("errorCount", errorCount);
        model.addAttribute("deletedCount", deletedCount);

        DetectionAggregate detectAgg = aggregator.aggregateDetections(analysisOnly, 14, 12);
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
    public String settingsPage(Model model, Authentication authentication) {
        model.addAttribute("matKeepUnreachable", analyzerService.isKeepUnreachableObjects());
        model.addAttribute("compressAfterAnalysis", analyzerService.isCompressAfterAnalysis());
        model.addAttribute("isAdmin", AuthUtil.isAdmin(authentication));
        return "settings";
    }

    @GetMapping("/settings/llm")
    public String llmSettingsPage(Model model, Authentication authentication) {
        model.addAttribute("isAdmin", AuthUtil.isAdmin(authentication));
        return "llm-settings";
    }

    @GetMapping("/settings/rag")
    public String ragSettingsPage(Model model, Authentication authentication) {
        model.addAttribute("isAdmin", AuthUtil.isAdmin(authentication));
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
                             RedirectAttributes redirectAttributes,
                             Authentication authentication) {
        String originalName = file.getOriginalFilename();
        logger.info("[Upload] Request received: filename={}, size={}", originalName, FormatUtils.formatBytes(file.getSize()));

        try {
            if (file.isEmpty()) {
                logger.warn("[Upload] Rejected: empty file submitted");
                redirectAttributes.addFlashAttribute("error", "Please select a file to upload");
                return "redirect:/";
            }
            String filename = analyzerService.uploadFile(file);
            String uploadedBy = authentication != null ? authentication.getName() : null;
            analyzerService.saveUploadRecord(filename, file.getSize(), uploadedBy);
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
                    aggregator.truncateLog(result.getMatLog(), config.getMatLogMaxDisplayChars()));
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

        // OOM 감지 요약 (Thread Overview 배너용)
        int oomCount = 0;
        List<String> oomSamples = new ArrayList<>();
        List<Integer> oomIndices = new ArrayList<>();
        String oomFirstType = null;
        if (result.getThreadInfos() != null) {
            int idx = 0;
            for (com.heapdump.analyzer.model.ThreadInfo ti : result.getThreadInfos()) {
                if (ti.isOom()) {
                    oomCount++;
                    if (oomSamples.size() < 3) {
                        oomSamples.add(ti.getName() != null ? ti.getName() : ("Thread #" + idx));
                        oomIndices.add(idx);
                    }
                    if (oomFirstType == null && ti.getOomType() != null) {
                        oomFirstType = ti.getOomType();
                    }
                }
                idx++;
            }
        }
        model.addAttribute("oomThreadCount", oomCount);
        model.addAttribute("oomThreadSamples", oomSamples);
        model.addAttribute("oomThreadIndices", oomIndices);
        model.addAttribute("oomFirstType", oomFirstType);
        // OOM 종류 → 한국어 라벨/원인/권장조치 (Overview 진단 카드용). oomFirstType 은 exact heap 메시지 또는 "표준메시지 (추정)" 형태 — classifyMessage 가 양쪽 모두 contains 매칭.
        if (oomCount > 0) {
            com.heapdump.analyzer.util.OomDetector.OomKind oomKind =
                    com.heapdump.analyzer.util.OomDetector.classifyMessage(oomFirstType);
            model.addAttribute("oomKindLabel", oomKind.koLabel());
            model.addAttribute("oomCause", oomKind.cause());
            model.addAttribute("oomRecommendation", oomKind.recommendation());
        }

        // 미들웨어(WAS) 벤더 추정 — 히스토그램 클래스 prefix 기반 (대표 1개)
        com.heapdump.analyzer.util.MiddlewareDetector.Result mw =
                com.heapdump.analyzer.util.MiddlewareDetector.detect(
                        result.getHistogramEntries(), result.getThreadInfos(),
                        result.getSystemProperties());
        if (mw.detected()) {
            model.addAttribute("middlewareVendor", mw.displayName());
            model.addAttribute("middlewareCategory", mw.category());
            model.addAttribute("middlewareVersion", mw.version); // null 가능 (버전 미추출)
        }

        // System Properties 탭 — 전체 프로퍼티 표(검색 가능) + 배지용 JDK 버전
        model.addAttribute("systemProperties", result.getSystemProperties());
        model.addAttribute("hasSystemProperties", result.hasSystemProperties());
        model.addAttribute("jdkVersion", result.getJdkVersion()); // null 가능

        // 덤프 출처 호스트명 — SSH 전송 시 자동 기록(server_name), 수동 업로드는 빈 값(편집 가능)
        model.addAttribute("hostname", analyzerService.getAnalysisServerName(filename));

        model.addAttribute("hasOverviewZip", analyzerService.hasReportZip(filename, "overview"));
        model.addAttribute("hasTopComponentsZip", analyzerService.hasReportZip(filename, "top_components"));
        model.addAttribute("hasSuspectsZip", analyzerService.hasReportZip(filename, "suspects"));
        model.addAttribute("hasDominatorTreeZip", analyzerService.hasReportZip(filename, "dominator_tree"));

        boolean hasDominatorTree = result.getDominatorTreeEntries() != null
                && !result.getDominatorTreeEntries().isEmpty();
        model.addAttribute("hasDominatorTree", hasDominatorTree);

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
            Model model,
            Authentication authentication) {
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

        // ── Compare 빌더 4종 호출 ────────────────────────────────────────
        List<ClassDiff>     classDiffs     = aggregator.buildClassDiffs(baseResult, targetResult, 50);
        List<HistogramDiff> histogramDiffs = aggregator.buildHistogramDiffs(baseResult, targetResult, 30);
        List<SuspectDiff>   suspectDiffs   = aggregator.buildSuspectDiffs(baseResult, targetResult);
        KpiDiff             kpi            = aggregator.buildKpiDiff(baseResult, targetResult);

        model.addAttribute("kpi", kpi);
        model.addAttribute("classDiffs", classDiffs);
        model.addAttribute("histogramDiffs", histogramDiffs);
        model.addAttribute("suspectDiffs", suspectDiffs);

        // 비교 이력 자동 저장 (60초 dedupe). 저장 실패해도 화면은 정상 렌더.
        try {
            int baseSuspectCnt   = baseResult.getLeakSuspects()   != null ? baseResult.getLeakSuspects().size()   : 0;
            int targetSuspectCnt = targetResult.getLeakSuspects() != null ? targetResult.getLeakSuspects().size() : 0;
            String user = authentication != null ? authentication.getName() : "unknown";
            comparisonHistoryService.recordComparison(base, target, kpi, baseSuspectCnt, targetSuspectCnt, user);
        } catch (Exception ex) {
            logger.warn("[CompareHistory] Failed to record comparison: {}", ex.getMessage());
        }

        // 보조 count (배너 표시용)
        model.addAttribute("baseSuspectCount",   baseResult.getLeakSuspects()   != null ? baseResult.getLeakSuspects().size()   : 0);
        model.addAttribute("targetSuspectCount", targetResult.getLeakSuspects() != null ? targetResult.getLeakSuspects().size() : 0);
        model.addAttribute("baseThreadCount",    baseResult.getThreadInfos()    != null ? baseResult.getThreadInfos().size()    : 0);
        model.addAttribute("targetThreadCount",  targetResult.getThreadInfos()  != null ? targetResult.getThreadInfos().size()  : 0);

        // 헤더 카드 컴팩트 메타 — 덤프 파일 mtime 을 yyyy-MM-dd HH:mm 으로 포맷
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        model.addAttribute("baseModifiedFmt",   baseResult.getLastModified()   > 0 ? dateFmt.format(new java.util.Date(baseResult.getLastModified()))   : "—");
        model.addAttribute("targetModifiedFmt", targetResult.getLastModified() > 0 ? dateFmt.format(new java.util.Date(targetResult.getLastModified())) : "—");

        // 기존 호환: 단순 heap delta (Top 영역 헤더에서 사용 가능)
        long heapDelta = kpi.getUsedHeapDelta();
        model.addAttribute("heapDelta", FormatUtils.formatBytes(Math.abs(heapDelta)));
        model.addAttribute("heapDeltaSign", heapDelta >= 0 ? "+" : "-");
        model.addAttribute("heapDeltaUp", heapDelta > 0);

        return "compare";
    }
}

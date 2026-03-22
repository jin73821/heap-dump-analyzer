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
import java.util.stream.Collectors;

/**
 * Heap Dump Analyzer Controller
 *
 * 신규 엔드포인트:
 *   GET  /compare                         → 두 덤프 비교 결과 페이지
 *   GET  /api/history                     → 분석 히스토리 JSON
 *   POST /api/cache/clear                 → 전체 캐시 삭제
 *   POST /api/settings/unreachable        → keep_unreachable 설정 변경
 *   GET  /api/settings                    → 현재 설정 조회
 *   GET  /analyze/rerun/{filename}        → 캐시 삭제 후 재분석
 */
@Controller
public class HeapDumpController {

    private static final Logger logger = LoggerFactory.getLogger(HeapDumpController.class);

    private final HeapDumpAnalyzerService analyzerService;

    public HeapDumpController(HeapDumpAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
    }

    // ── 메인 페이지 ──────────────────────────────────────────────

    @GetMapping("/")
    public String index(Model model) {
        List<HeapDumpFile> files = analyzerService.listFiles();
        model.addAttribute("files", files);
        model.addAttribute("fileCount", files.size());

        // 통계 계산
        long totalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        model.addAttribute("totalSize", formatBytes(totalBytes));

        // 분석 히스토리 (캐시에서 로드)
        List<AnalysisHistoryItem> history = buildHistory(files);
        int maxDisplay = 5;
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

        long totalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        model.addAttribute("totalSize", formatBytes(totalBytes));

        long analyzedCount = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus())).count();
        model.addAttribute("analyzedCount", analyzedCount);

        return "files";
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
            redirectAttributes.addFlashAttribute("success",
                    "Uploaded: " + filename + " (" + formatBytes(file.getSize()) + ")");
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
        HeapAnalysisResult cached = analyzerService.getCachedResult(filename);
        if (cached != null && cached.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.SUCCESS) {
            logger.info("Cache hit for {}, redirecting to result", filename);
            return "redirect:/analyze/result/" + filename;
        }
        model.addAttribute("filename", filename);
        return "progress";
    }

    // ── 재분석 ───────────────────────────────────────────────────

    @GetMapping("/analyze/rerun/{filename:.+}")
    public String rerunAnalysis(@PathVariable String filename) {
        analyzerService.clearCache(filename);
        logger.info("Cache cleared for {} → restarting", filename);
        return "redirect:/analyze/" + filename;
    }

    // ── SSE 진행 스트림 ──────────────────────────────────────────

    @GetMapping(value = "/analyze/progress/{filename:.+}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamProgress(@PathVariable String filename) {
        SseEmitter emitter = new SseEmitter(35L * 60 * 1000);
        java.util.concurrent.Future<?> task = analyzerService.analyzeWithProgress(filename, emitter);

        // 클라이언트 disconnect / 타임아웃 시 분석 스레드 중단
        Runnable cancelTask = () -> {
            if (task != null && !task.isDone()) {
                logger.info("[SSE] Client disconnected, cancelling analysis for: {}", filename);
                task.cancel(true);
            }
        };
        emitter.onTimeout(cancelTask);
        emitter.onError(e -> cancelTask.run());
        emitter.onCompletion(cancelTask);

        return emitter;
    }

    // ── 분석 결과 화면 ───────────────────────────────────────────

    @GetMapping("/analyze/result/{filename:.+}")
    public String analyzeResult(@PathVariable String filename, Model model) {
        HeapAnalysisResult result = analyzerService.getCachedResult(filename);
        if (result == null) return "redirect:/analyze/" + filename;

        if (result.getAnalysisStatus() == HeapAnalysisResult.AnalysisStatus.ERROR) {
            model.addAttribute("error", result.getErrorMessage());
            model.addAttribute("filename", filename);
            model.addAttribute("matLog", truncateLog(result.getMatLog(), 5000));
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
            result.getTopMemoryObjects().stream().limit(10).forEach(o -> {
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

        return "analyze";
    }

    // ── 로그 청크 API ────────────────────────────────────────────

    @GetMapping(value = "/analyze/log/{filename:.+}", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> getMatLog(
            @PathVariable String filename,
            @RequestParam(defaultValue = "0")     int offset,
            @RequestParam(defaultValue = "10000") int limit) {
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
        try {
            File file = analyzerService.getFile(filename);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(new FileSystemResource(file));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── 파일 삭제 ────────────────────────────────────────────────

    @GetMapping("/delete/{filename:.+}")
    public String deleteFile(@PathVariable String filename,
                             @RequestHeader(value = "Referer", required = false) String referer,
                             RedirectAttributes redirectAttributes) {
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

    // ── Top Component 상세 HTML ──────────────────────────────────

    @GetMapping("/report/{filename:.+}/component-detail")
    @ResponseBody
    public ResponseEntity<String> componentDetailHtml(
            @PathVariable String filename,
            @RequestParam String className,
            @RequestParam(defaultValue = "-1") int index) {
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null || r.getComponentDetailHtmlMap() == null) {
            logger.warn("[ComponentDetail] Not found: file={}, className={}, index={}, reason={}",
                    filename, className, index,
                    r == null ? "no cached result" : "componentDetailHtmlMap is null");
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

    @GetMapping("/report/{filename:.+}/component-list")
    @ResponseBody
    public ResponseEntity<List<String>> componentList(@PathVariable String filename) {
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        if (r == null || r.getComponentDetailHtmlMap() == null)
            return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(new ArrayList<>(r.getComponentDetailHtmlMap().keySet()));
    }

    // ── Thread Stacks API ──────────────────────────────────────────

    @GetMapping(value = "/report/{filename:.+}/thread-stacks", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> threadStacks(@PathVariable String filename) {
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

    // ── [NEW] Compare ────────────────────────────────────────────

    @GetMapping("/compare")
    public String compareDumps(
            @RequestParam String base,
            @RequestParam String target,
            Model model) {

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
        List<Map<String, Object>> history = new ArrayList<>();

        for (HeapDumpFile file : files) {
            HeapAnalysisResult result = analyzerService.getCachedResult(file.getName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename",     file.getName());
            item.put("size",         file.getSize());
            item.put("formattedSize", file.getFormattedSize());
            item.put("lastModified", file.getLastModified());
            if (result != null) {
                item.put("status",        result.getAnalysisStatus().name());
                item.put("totalHeap",     result.getTotalHeapSize());
                item.put("usedHeap",      result.getUsedHeapSize());
                item.put("suspectCount",  result.getLeakSuspects() != null
                                           ? result.getLeakSuspects().size() : 0);
                item.put("analysisTime",  result.getAnalysisTime());
            } else {
                item.put("status", "NOT_ANALYZED");
            }
            history.add(item);
        }
        return ResponseEntity.ok(history);
    }

    // ── [NEW] API: 전체 캐시 삭제 ────────────────────────────────

    @PostMapping("/api/cache/clear")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearAllCache() {
        List<HeapDumpFile> files = analyzerService.listFiles();
        int cleared = 0;
        for (HeapDumpFile file : files) {
            analyzerService.clearCache(file.getName());
            cleared++;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cleared", cleared);
        resp.put("message", "Cleared cache for " + cleared + " files");
        logger.info("All cache cleared: {} files", cleared);
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

    // ── [NEW] API: 분석 큐 상태 ──────────────────────────────

    @GetMapping("/api/queue/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("queueSize", analyzerService.getQueueSize());
        resp.put("currentAnalysis", analyzerService.getCurrentAnalysisFilename());
        return ResponseEntity.ok(resp);
    }

    // ── [NEW] API: 현재 설정 조회 ────────────────────────────────

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("keepUnreachableObjects", analyzerService.isKeepUnreachableObjects());
        settings.put("heapDumpDirectory",      analyzerService.getHeapDumpDirectory());
        settings.put("matCliPath",             analyzerService.getMatCliPath());
        settings.put("cachedResults",          analyzerService.getCachedResultCount());

        // System info
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("javaVersion",  System.getProperty("java.version"));
        system.put("javaVendor",   System.getProperty("java.vendor"));
        system.put("osName",       System.getProperty("os.name"));
        system.put("osArch",       System.getProperty("os.arch"));
        Runtime rt = Runtime.getRuntime();
        system.put("jvmMaxMemory",     formatBytes(rt.maxMemory()));
        system.put("jvmTotalMemory",   formatBytes(rt.totalMemory()));
        system.put("jvmFreeMemory",    formatBytes(rt.freeMemory()));
        system.put("jvmUsedMemory",    formatBytes(rt.totalMemory() - rt.freeMemory()));
        system.put("availableProcessors", rt.availableProcessors());
        settings.put("system", system);

        // Disk info
        Map<String, Object> disk = new LinkedHashMap<>();
        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists()) {
            disk.put("totalSpace",  formatBytes(dumpDir.getTotalSpace()));
            disk.put("freeSpace",   formatBytes(dumpDir.getFreeSpace()));
            disk.put("usableSpace", formatBytes(dumpDir.getUsableSpace()));
            long used = dumpDir.getTotalSpace() - dumpDir.getFreeSpace();
            disk.put("usedSpace",   formatBytes(used));
            disk.put("usedPercent", dumpDir.getTotalSpace() > 0
                    ? Math.round(used * 100.0 / dumpDir.getTotalSpace()) : 0);
        }
        settings.put("disk", disk);

        // MAT CLI status
        Map<String, Object> mat = new LinkedHashMap<>();
        File matFile = new File(analyzerService.getMatCliPath());
        mat.put("exists",     matFile.exists());
        mat.put("executable", matFile.canExecute());
        mat.put("readable",   matFile.canRead());
        mat.put("path",       analyzerService.getMatCliPath());
        mat.put("ready",      analyzerService.isMatCliReady());
        mat.put("statusMessage", analyzerService.getMatCliStatusMessage());
        settings.put("mat", mat);

        // File stats
        List<HeapDumpFile> files = analyzerService.listFiles();
        Map<String, Object> fileStats = new LinkedHashMap<>();
        fileStats.put("totalFiles", files.size());
        long totalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        fileStats.put("totalSize", formatBytes(totalBytes));
        fileStats.put("analyzedCount", files.stream()
                .filter(f -> analyzerService.getCachedResult(f.getName()) != null)
                .count());
        settings.put("files", fileStats);

        return ResponseEntity.ok(settings);
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
        if (bytes <= 0)                  return "0 B";
        if (bytes < 1024)                return bytes + " B";
        if (bytes < 1024 * 1024)         return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
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
        for (HeapDumpFile file : files) {
            HeapAnalysisResult result = analyzerService.getCachedResult(file.getName());
            AnalysisHistoryItem item = new AnalysisHistoryItem();
            item.setFilename(file.getName());
            item.setFormattedSize(file.getFormattedSize());
            item.setFormattedDate(sdf.format(new Date(file.getLastModified())));
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
        private String filename;
        private String formattedSize;
        private String formattedDate;
        private String status;
        private int    suspectCount;
        private long   analysisTime;
        private String formattedAnalysisTime;
        private String heapUsed;

        public String getFilename()      { return filename; }
        public void   setFilename(String v)      { filename = v; }
        public String getFormattedSize() { return formattedSize; }
        public void   setFormattedSize(String v) { formattedSize = v; }
        public String getFormattedDate() { return formattedDate; }
        public void   setFormattedDate(String v) { formattedDate = v; }
        public String getStatus()        { return status; }
        public void   setStatus(String v)        { status = v; }
        public int    getSuspectCount()  { return suspectCount; }
        public void   setSuspectCount(int v)     { suspectCount = v; }
        public long   getAnalysisTime()  { return analysisTime; }
        public void   setAnalysisTime(long v)    { analysisTime = v; }
        public String getFormattedAnalysisTime() { return formattedAnalysisTime; }
        public void   setFormattedAnalysisTime(String v) { formattedAnalysisTime = v; }
        public String getHeapUsed()      { return heapUsed; }
        public void   setHeapUsed(String v)      { heapUsed = v; }
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

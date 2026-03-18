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
        model.addAttribute("analysisHistory", history);
        model.addAttribute("analyzedCount",
            history.stream().filter(h -> "SUCCESS".equals(h.getStatus())).count());

        // 전체 leak suspects 수
        long totalSuspects = history.stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()))
            .mapToLong(AnalysisHistoryItem::getSuspectCount)
            .sum();
        model.addAttribute("totalSuspects", totalSuspects > 0 ? totalSuspects : null);

        // MAT 설정
        model.addAttribute("matKeepUnreachable", analyzerService.isKeepUnreachableObjects());

        return "index";
    }

    // ── 파일 업로드 ──────────────────────────────────────────────

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
                    "Uploaded: " + filename + " (" + formatBytes(file.getSize()) + ")");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
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
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());
        analyzerService.analyzeWithProgress(filename, emitter);
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
            model.addAttribute("matLogTotalLen", 0);
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
                             RedirectAttributes redirectAttributes) {
        try {
            analyzerService.deleteFile(filename);
            redirectAttributes.addFlashAttribute("success", "Deleted: " + filename);
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Delete failed: " + e.getMessage());
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

    // ── [NEW] API: 현재 설정 조회 ────────────────────────────────

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("keepUnreachableObjects", analyzerService.isKeepUnreachableObjects());
        settings.put("heapDumpDirectory",      analyzerService.getHeapDumpDirectory());
        settings.put("matCliPath",             analyzerService.getMatCliPath());
        settings.put("cachedResults",          analyzerService.getCachedResultCount());
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

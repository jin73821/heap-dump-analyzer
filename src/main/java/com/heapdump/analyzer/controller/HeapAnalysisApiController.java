package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.AnalysisProgress;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.util.FilenameValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 실행/큐/취소/SSE 진행 스트림 API (Phase 4B-2).
 */
@Controller
public class HeapAnalysisApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapAnalysisApiController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final HeapDumpConfig config;

    public HeapAnalysisApiController(HeapDumpAnalyzerService analyzerService,
                                     HeapDumpConfig config) {
        this.analyzerService = analyzerService;
        this.config = config;
    }

    @GetMapping(value = "/analyze/progress/{filename:.+}",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamProgress(@PathVariable String filename) {
        final String safe = FilenameValidator.validate(filename);
        SseEmitter emitter = new SseEmitter(config.getSseEmitterTimeoutMinutes() * 60L * 1000);
        analyzerService.analyzeWithProgress(safe, emitter);

        // 클라이언트 disconnect(페이지 이탈/탭 닫기)는 분석을 취소하지 않는다 — 백그라운드로 계속 진행.
        // 죽은 emitter 로의 전송은 service.sendProgress 가 deadEmitters 로 조용히 스킵한다.
        // 명시적 취소는 오직 POST /api/analyze/cancel/{filename} 로만 수행된다.
        emitter.onTimeout(() -> logger.debug("[SSE] Emitter timeout (analysis continues in background): {}", safe));
        emitter.onError(e -> logger.debug("[SSE] Emitter error (analysis continues in background): {}", safe));
        emitter.onCompletion(() -> logger.debug("[SSE] Emitter completed: {}", safe));

        return emitter;
    }

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

    @GetMapping("/api/queue/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("queueSize", analyzerService.getQueueSize());
        resp.put("currentAnalysis", analyzerService.getCurrentAnalysisFilename());
        resp.put("inProgressFiles", analyzerService.getInProgressFilenames());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/analyze/in-progress/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkInProgress(@PathVariable String filename) {
        String safe = FilenameValidator.validate(filename);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("inProgress", analyzerService.isInProgress(safe));
        resp.put("currentAnalysis", analyzerService.getCurrentAnalysisFilename());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/analyze/live-snapshot/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLiveSnapshot(@PathVariable String filename) {
        String safe = FilenameValidator.validate(filename);
        AnalysisProgress progress = analyzerService.getLastProgress(safe);
        List<String> logLines = analyzerService.getRecentLogs(safe);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("inProgress", analyzerService.isInProgress(safe));
        resp.put("progress", progress);
        resp.put("logLines", logLines);
        return ResponseEntity.ok(resp);
    }
}

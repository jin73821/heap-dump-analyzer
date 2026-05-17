package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
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
import java.util.Map;
import java.util.concurrent.Future;

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
        Future<?> task = analyzerService.analyzeWithProgress(safe, emitter);

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
        return ResponseEntity.ok(resp);
    }
}

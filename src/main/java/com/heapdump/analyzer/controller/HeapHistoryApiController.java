package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.DetectionAggregate;
import com.heapdump.analyzer.model.dto.DetectionDayFile;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.HeapHistoryAggregator;
import com.heapdump.analyzer.service.JvmHeapInfoService;
import com.heapdump.analyzer.util.AuthUtil;
import com.heapdump.analyzer.util.FilenameValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 분석 이력 / detections 차트 / 캐시 클리어 / compare 데이터 API (Phase 4B-2).
 */
@Controller
public class HeapHistoryApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapHistoryApiController.class);

    private final HeapDumpAnalyzerService analyzerService;
    private final HeapHistoryAggregator aggregator;
    private final JvmHeapInfoService jvmHeapInfoService;

    public HeapHistoryApiController(HeapDumpAnalyzerService analyzerService,
                                    HeapHistoryAggregator aggregator,
                                    JvmHeapInfoService jvmHeapInfoService) {
        this.analyzerService = analyzerService;
        this.aggregator = aggregator;
        this.jvmHeapInfoService = jvmHeapInfoService;
    }

    private static String who(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }

    /** GC 로그 매칭(2026-09-14) — 선택 주입. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.heapdump.analyzer.service.GcLogMatchService gcLogMatchService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.heapdump.analyzer.repository.GcLogAnalysisRepository gcLogAnalysisRepository;

    /** 덤프 기준 GC 로그 연결 상태(칩·패널). */
    @GetMapping("/api/history/{filename:.+}/gc-log")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> gcLogView(@org.springframework.web.bind.annotation.PathVariable String filename) {
        String safe = FilenameValidator.validate(filename);
        Map<String, Object> v = gcLogMatchService == null ? new HashMap<>() : gcLogMatchService.viewForDump(safe);
        v.put("success", true);
        return ResponseEntity.ok(v);
    }

    /** 덤프 쪽에서 GC 로그 연결({@code gcLogFilename})/해제(null → 이 덤프를 가리키는 로그 전부 manual 해제). */
    @PostMapping("/api/history/{filename:.+}/gc-log")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> gcLogLink(@org.springframework.web.bind.annotation.PathVariable String filename,
                                                         @RequestBody Map<String, Object> body, Authentication auth) {
        String safe = FilenameValidator.validate(filename);
        Map<String, Object> resp = new HashMap<>();
        if (gcLogMatchService == null || gcLogAnalysisRepository == null) {
            resp.put("success", false); resp.put("error", "GC 로그 기능이 비활성입니다."); return ResponseEntity.status(503).body(resp);
        }
        Object gv = body.get("gcLogFilename");
        String gc = gv == null || gv.toString().isBlank() ? null : gv.toString();
        if (gc != null) {
            String safeGc = com.heapdump.analyzer.util.FilenameValidator.validateSafe(gc);
            com.heapdump.analyzer.model.entity.GcLogAnalysisEntity e = gcLogAnalysisRepository.findByFilename(safeGc).orElse(null);
            if (e == null) { resp.put("success", false); resp.put("error", "GC 로그 이력이 없습니다: " + safeGc); return ResponseEntity.status(404).body(resp); }
            gcLogMatchService.setManual(e, safe);
            logger.info("[GcLog] action=match source=dump-page dump={} gclog={} by={}", safe, safeGc, who(auth));
        } else {
            int n = 0;
            for (com.heapdump.analyzer.model.entity.GcLogAnalysisEntity e : gcLogAnalysisRepository.findByMatchedDumpFilename(safe)) {
                gcLogMatchService.setManual(e, null);
                n++;
            }
            logger.info("[GcLog] action=unlink source=dump-page dump={} count={} by={}", safe, n, who(auth));
        }
        Map<String, Object> v = gcLogMatchService.viewForDump(safe);
        v.put("success", true);
        return ResponseEntity.ok(v);
    }

    /** 덤프 쪽 수동 선택 모달용 GC 로그 목록. */
    @org.springframework.web.bind.annotation.GetMapping("/api/history/{filename:.+}/gc-log/options")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> gcLogOptions(@org.springframework.web.bind.annotation.PathVariable String filename,
                                                            @org.springframework.web.bind.annotation.RequestParam(value = "q", required = false) String q) {
        String safe = FilenameValidator.validate(filename);
        Map<String, Object> resp = new HashMap<>();
        List<Map<String, Object>> options = new java.util.ArrayList<>();
        if (gcLogAnalysisRepository != null) {
            String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
            for (com.heapdump.analyzer.model.entity.GcLogAnalysisEntity e : gcLogAnalysisRepository.findAllByOrderByCreatedAtDesc()) {
                if (!needle.isEmpty() && !(e.getFilename().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || (e.getServerName() != null && e.getServerName().toLowerCase(java.util.Locale.ROOT).contains(needle)))) continue;
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("filename", e.getFilename());
                m.put("serverName", e.getServerName());
                m.put("status", e.getStatus());
                m.put("collector", e.getCollector());
                m.put("logStart", e.getLogStart() == null ? null : e.getLogStart().toString().replace('T', ' '));
                m.put("logEnd", e.getLogEnd() == null ? null : e.getLogEnd().toString().replace('T', ' '));
                m.put("matchedDumpFilename", e.getMatchedDumpFilename());
                m.put("matchSource", e.getMatchSource());
                m.put("linkedHere", safe.equals(e.getMatchedDumpFilename()));
                options.add(m);
                if (options.size() >= 200) break;
            }
        }
        resp.put("success", true);
        resp.put("options", options);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/history/bulk-delete")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> bulkDeleteHistory(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> filenames = (List<String>) body.getOrDefault("filenames", Collections.emptyList());
        boolean deleteHeapDump = Boolean.TRUE.equals(body.get("deleteHeapDump"));
        boolean deleteAiChat   = Boolean.TRUE.equals(body.get("deleteAiChat"));
        int success = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (String raw : filenames) {
            try {
                String safe = FilenameValidator.validate(raw);
                analyzerService.deleteHistory(safe, deleteHeapDump, deleteAiChat);
                success++;
            } catch (Exception e) {
                failed++;
                errors.add(raw + ": " + e.getMessage());
                logger.warn("[BulkDeleteHistory] Failed for '{}': {}", raw, e.getMessage());
            }
        }
        logger.info("[BulkDeleteHistory] success={}, failed={}, deleteHeapDump={}, deleteAiChat={}", success, failed, deleteHeapDump, deleteAiChat);
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", success);
        resp.put("failed", failed);
        resp.put("errors", errors);
        return ResponseEntity.ok(resp);
    }

    /**
     * 덤프 출처 호스트명 수동 편집 — analysis_history.server_name 갱신.
     * SSH 전송 덤프는 자동 기록되지만 수동 업로드는 비어 있어 운영자가 직접 입력할 수 있게 한다.
     * 빈 값이면 미지정으로 초기화. 인증 필요(/api/** 공통), CSRF 면제 경로.
     */
    @PostMapping("/api/history/{filename:.+}/hostname")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateHostname(
            @org.springframework.web.bind.annotation.PathVariable String filename,
            @RequestBody Map<String, Object> body) {
        String safe = FilenameValidator.validate(filename);
        Object hv = body.get("hostname");
        String hostname = hv != null ? hv.toString() : "";
        String saved = analyzerService.updateAnalysisServerName(safe, hostname);
        Map<String, Object> resp = new HashMap<>();
        if (saved == null) {
            resp.put("success", false);
            resp.put("error", "분석 이력 레코드를 찾을 수 없습니다: " + safe);
            return ResponseEntity.status(404).body(resp);
        }
        logger.info("[Hostname] Updated for '{}': '{}'", safe, saved);
        resp.put("success", true);
        resp.put("hostname", saved);
        return ResponseEntity.ok(resp);
    }

    /**
     * JEUS Instance/Domain 수동 편집 — analysis_history.jeus_instance/jeus_domain 갱신.
     * System Properties(jeus.server.name/jeus.domain.name) 자동 식별이 안 되거나 수동 업로드 덤프에서 사용.
     * body 에 instance/domain 키가 있는 필드만 갱신(null=유지, ""=초기화). 인증 필요, CSRF 면제 경로.
     */
    @PostMapping("/api/history/{filename:.+}/jeus")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateJeusMeta(
            @org.springframework.web.bind.annotation.PathVariable String filename,
            @RequestBody Map<String, Object> body) {
        String safe = FilenameValidator.validate(filename);
        String instance = body.containsKey("instance") && body.get("instance") != null
                ? body.get("instance").toString() : null;
        String domain = body.containsKey("domain") && body.get("domain") != null
                ? body.get("domain").toString() : null;
        Map<String, String> saved = analyzerService.updateAnalysisJeus(safe, instance, domain);
        Map<String, Object> resp = new HashMap<>();
        if (saved == null) {
            resp.put("success", false);
            resp.put("error", "분석 이력 레코드를 찾을 수 없습니다: " + safe);
            return ResponseEntity.status(404).body(resp);
        }
        logger.info("[JeusMeta] Updated for '{}': instance='{}' domain='{}'",
                safe, saved.get("instance"), saved.get("domain"));
        resp.put("success", true);
        resp.put("instance", saved.get("instance"));
        resp.put("domain", saved.get("domain"));
        return ResponseEntity.ok(resp);
    }

    // ── JVM 힙 설정(-Xms/-Xmx) — 원격 전송 시 자동 수집 + 수동/후보 선택/재수집 (2026-09-11) ──
    // 인증 필요·CSRF 면제(/api/**)·USER 허용 — /hostname·/jeus 와 같은 정책(자기 덤프 메타 편집).

    /** used heap(=analyze 화면 Total Heap) — Xmx 대비 % 계산용. 결과 캐시에 없으면 null. */
    private Long usedHeapOf(String filename) {
        HeapAnalysisResult r = analyzerService.getCachedResult(filename);
        return r != null && r.getTotalHeapSize() > 0 ? r.getTotalHeapSize() : null;
    }

    @GetMapping("/api/history/{filename:.+}/jvm-heap")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getJvmHeap(
            @org.springframework.web.bind.annotation.PathVariable String filename) {
        String safe = FilenameValidator.validate(filename);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("view", jvmHeapInfoService.view(safe, usedHeapOf(safe)));
        resp.put("candidates", jvmHeapInfoService.candidates(safe));
        return ResponseEntity.ok(resp);
    }

    /**
     * body {@code {xms, xmx}} → 수동 입력(둘 다 비면 초기화) / body {@code {select: n}} → 후보 선택.
     * 잘못된 표기·범위 밖 인덱스는 IllegalArgumentException → GlobalExceptionHandler 400 JSON.
     */
    @PostMapping("/api/history/{filename:.+}/jvm-heap")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateJvmHeap(
            @org.springframework.web.bind.annotation.PathVariable String filename,
            @RequestBody Map<String, Object> body, Authentication auth) {
        String safe = FilenameValidator.validate(filename);
        Map<String, Object> view;
        String action;
        if (body.get("select") != null) {
            int idx;
            try { idx = Integer.parseInt(body.get("select").toString().trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("select 는 정수여야 합니다"); }
            view = jvmHeapInfoService.selectCandidate(safe, idx, usedHeapOf(safe));
            action = "select";
        } else {
            String xms = body.get("xms") == null ? "" : body.get("xms").toString();
            String xmx = body.get("xmx") == null ? "" : body.get("xmx").toString();
            view = jvmHeapInfoService.updateManual(safe, xms, xmx, usedHeapOf(safe));
            action = "update";
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        if (view == null) {
            resp.put("success", false);
            resp.put("error", "분석 이력 레코드를 찾을 수 없습니다: " + safe);
            return ResponseEntity.status(404).body(resp);
        }
        logger.info("[JvmHeap] action={} file={} xms={} xmx={} source={} by={}",
                action, safe, view.get("xms"), view.get("xmx"), view.get("source"), who(auth));
        resp.put("success", true);
        resp.put("view", view);
        return ResponseEntity.ok(resp);
    }

    /** 출처 서버에서 재수집. 후보가 여럿이면 {@code needsSelection:true + candidates}. */
    @PostMapping("/api/history/{filename:.+}/jvm-heap/recollect")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> recollectJvmHeap(
            @org.springframework.web.bind.annotation.PathVariable String filename, Authentication auth) {
        String safe = FilenameValidator.validate(filename);
        HeapAnalysisResult r = analyzerService.getCachedResult(safe);
        JvmHeapInfoService.RecollectResult rr = jvmHeapInfoService.recollect(safe,
                r != null ? r.getSystemProperties() : null,
                r != null ? r.getDumpCreationTime() : null,
                usedHeapOf(safe));
        Map<String, Object> resp = new LinkedHashMap<>();
        if (rr.code() != null) {
            logger.info("[JvmHeap] action=recollect file={} result={} by={}", safe, rr.code(), who(auth));
            resp.put("success", false);
            resp.put("code", rr.code());
            resp.put("error", rr.error());
            if (rr.view() != null) resp.put("view", rr.view());
            int status = "NOT_FOUND".equals(rr.code()) ? 404 : "BUSY".equals(rr.code()) ? 409 : 200;
            return ResponseEntity.status(status).body(resp);
        }
        logger.info("[JvmHeap] action=recollect file={} needsSelection={} xms={} xmx={} notice={} by={}",
                safe, rr.needsSelection(), rr.view().get("xms"), rr.view().get("xmx"),
                rr.notice() == null ? "-" : rr.notice().get("code"), who(auth));
        resp.put("success", true);
        resp.put("needsSelection", rr.needsSelection());
        resp.put("view", rr.view());
        if (rr.candidates() != null) resp.put("candidates", rr.candidates());
        // 후보 0개 — 수집은 됐지만 값을 못 정한 이유. 화면이 안내한다(종전엔 아무 반응 없음, 2026-09-16)
        if (rr.notice() != null) resp.put("notice", rr.notice());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/history")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getHistory() {
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> items = aggregator.buildHistory(files);
        List<Map<String, Object>> history = new ArrayList<>();

        for (AnalysisHistoryItem h : items) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename",         h.getFilename());
            item.put("formattedSize",    h.getFormattedSize());
            item.put("lastModified",     h.getLastModified());
            item.put("status",           h.getStatus());
            item.put("fileDeleted",      h.isFileDeleted());
            item.put("serverName",       h.getServerName());
            item.put("dumpCreationTime", h.getDumpCreationTime());
            item.put("jvmXmsBytes",      h.getJvmXmsBytes());
            item.put("jvmXmxBytes",      h.getJvmXmxBytes());
            item.put("jvmHeapSource",    h.getJvmHeapSource());
            if (!"NOT_ANALYZED".equals(h.getStatus())) {
                item.put("suspectCount",   h.getSuspectCount());
                item.put("analysisTime",   h.getAnalysisTime());
                item.put("heapUsed",       h.getHeapUsed());
                item.put("heapUsedBytes",  h.getHeapUsedBytes());
            }
            history.add(item);
        }
        return ResponseEntity.ok(history);
    }

    @GetMapping("/api/history/detections")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> historyDetections(
            @RequestParam(defaultValue = "14") int days,
            @RequestParam(defaultValue = "server") String groupBy,
            Authentication authentication) {
        int safeDays = (days == 7 || days == 14 || days == 30 || days == 90) ? days : 14;
        String safeGroup = "severity".equals(groupBy) ? "severity" : "server";

        boolean isAdmin = AuthUtil.isAdmin(authentication);
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> history = aggregator.buildHistory(files).stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()) || "ERROR".equals(h.getStatus()))
            .filter(h -> isAdmin || !h.isFileDeleted())
            .collect(Collectors.toList());

        DetectionAggregate agg = aggregator.aggregateDetections(history, safeDays, 12);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("days", safeDays);
        resp.put("groupBy", safeGroup);
        resp.put("labels", agg.getLabels());
        resp.put("datasets", "server".equals(safeGroup) ? agg.getServerSeries() : agg.getSeveritySeries());
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", agg.getTotal());
        kpi.put("last7d", agg.getLast7d());
        kpi.put("prev7d", agg.getPrev7d());
        kpi.put("delta7d", agg.getDelta7d());
        kpi.put("peakDay", agg.getPeakDay());
        kpi.put("peakCount", agg.getPeakCount());
        resp.put("kpi", kpi);
        resp.put("recent", agg.getRecent() != null ? agg.getRecent() : Collections.emptyList());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/history/detections/day")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> historyDetectionsDay(
            @RequestParam String date,
            @RequestParam(defaultValue = "server") String groupBy,
            Authentication authentication) {
        java.time.LocalDate target;
        try {
            target = java.time.LocalDate.parse(date);
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Invalid date");
            return ResponseEntity.badRequest().body(err);
        }
        String safeGroup = "severity".equals(groupBy) ? "severity" : "server";

        boolean isAdmin = AuthUtil.isAdmin(authentication);
        List<HeapDumpFile> files = analyzerService.listFiles();
        List<AnalysisHistoryItem> history = aggregator.buildHistory(files).stream()
            .filter(h -> "SUCCESS".equals(h.getStatus()))
            .filter(h -> isAdmin || !h.isFileDeleted())
            .collect(Collectors.toList());

        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        List<DetectionDayFile> result = new ArrayList<>();
        for (AnalysisHistoryItem h : history) {
            if (h.getAnalyzedAtEpoch() <= 0) continue;
            java.time.LocalDate day = java.time.Instant.ofEpochMilli(h.getAnalyzedAtEpoch())
                    .atZone(zone).toLocalDate();
            if (!day.equals(target)) continue;
            HeapAnalysisResult r = analyzerService.getCachedResult(h.getFilename());
            int fc = 0, fh = 0, fm = 0, fl = 0;
            if (r != null && r.getLeakSuspects() != null) {
                for (LeakSuspect s : r.getLeakSuspects()) {
                    String sev = s.getSeverity() != null ? s.getSeverity().toLowerCase() : "medium";
                    switch (sev) {
                        case "critical": fc++; break;
                        case "high":     fh++; break;
                        case "low":      fl++; break;
                        default:         fm++; break;
                    }
                }
            }
            int sum = fc + fh + fm + fl;
            if (sum == 0) continue;
            DetectionDayFile dd = new DetectionDayFile();
            dd.setFilename(h.getFilename());
            dd.setAnalysisName(aggregator.buildAnalysisName(h.getFilename(), h.getServerName(), h.getAnalyzedAtEpoch()));
            dd.setServerName(h.getServerName());
            dd.setCriticalCount(fc);
            dd.setHighCount(fh);
            dd.setMediumCount(fm);
            dd.setLowCount(fl);
            dd.setSuspectCount(sum);
            dd.setFileDeleted(h.isFileDeleted());
            dd.setAnalyzedAtEpoch(h.getAnalyzedAtEpoch());
            result.add(dd);
        }
        result.sort((a, b) -> {
            int c = Integer.compare(b.getCriticalCount(), a.getCriticalCount());
            if (c != 0) return c;
            c = Integer.compare(b.getHighCount(), a.getHighCount());
            if (c != 0) return c;
            return Integer.compare(b.getSuspectCount(), a.getSuspectCount());
        });
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("date", date);
        resp.put("groupBy", safeGroup);
        resp.put("files", result);
        return ResponseEntity.ok(resp);
    }

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

    /**
     * Compare 패널이 AI 프롬프트 빌더에 사용할 JSON 데이터.
     * 두 덤프 모두 분석 완료되어 있어야 한다.
     */
    @GetMapping("/api/compare/data")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> compareData(
            @RequestParam String base,
            @RequestParam String target) {
        base   = FilenameValidator.validate(base);
        target = FilenameValidator.validate(target);

        HeapAnalysisResult baseResult   = analyzerService.getCachedResult(base);
        HeapAnalysisResult targetResult = analyzerService.getCachedResult(target);

        Map<String, Object> resp = new LinkedHashMap<>();
        if (baseResult == null || targetResult == null) {
            resp.put("success", false);
            resp.put("errorCode", "NOT_ANALYZED");
            List<String> missing = new ArrayList<>();
            if (baseResult == null)   missing.add(base);
            if (targetResult == null) missing.add(target);
            resp.put("error", "두 덤프 모두 분석되어 있어야 합니다. 누락: " + String.join(", ", missing));
            resp.put("missing", missing);
            return ResponseEntity.status(404).body(resp);
        }

        Map<String, Object> baseMeta = new LinkedHashMap<>();
        baseMeta.put("filename",          base);
        baseMeta.put("analyzedAt",        baseResult.getAnalysisTime());
        baseMeta.put("usedHeap",          baseResult.getUsedHeapSize());
        baseMeta.put("totalHeap",         baseResult.getTotalHeapSize());
        baseMeta.put("heapUsagePercent",  baseResult.getHeapUsagePercent());
        baseMeta.put("totalObjects",      baseResult.getTotalObjects());
        baseMeta.put("totalClasses",      baseResult.getTotalClasses());
        baseMeta.put("suspectCount",      baseResult.getLeakSuspects()    != null ? baseResult.getLeakSuspects().size()    : 0);
        baseMeta.put("threadCount",       baseResult.getThreadInfos()     != null ? baseResult.getThreadInfos().size()     : 0);

        Map<String, Object> targetMeta = new LinkedHashMap<>();
        targetMeta.put("filename",         target);
        targetMeta.put("analyzedAt",       targetResult.getAnalysisTime());
        targetMeta.put("usedHeap",         targetResult.getUsedHeapSize());
        targetMeta.put("totalHeap",        targetResult.getTotalHeapSize());
        targetMeta.put("heapUsagePercent", targetResult.getHeapUsagePercent());
        targetMeta.put("totalObjects",     targetResult.getTotalObjects());
        targetMeta.put("totalClasses",     targetResult.getTotalClasses());
        targetMeta.put("suspectCount",     targetResult.getLeakSuspects()   != null ? targetResult.getLeakSuspects().size()   : 0);
        targetMeta.put("threadCount",      targetResult.getThreadInfos()    != null ? targetResult.getThreadInfos().size()    : 0);

        resp.put("success", true);
        resp.put("base",   baseMeta);
        resp.put("target", targetMeta);
        try {
            resp.put("kpi",            aggregator.buildKpiDiff(baseResult, targetResult));
            resp.put("classDiffs",     aggregator.buildClassDiffs(baseResult, targetResult, 50));
            resp.put("histogramDiffs", aggregator.buildHistogramDiffs(baseResult, targetResult, 30));
            resp.put("suspectDiffs",   aggregator.buildSuspectDiffs(baseResult, targetResult));
        } catch (Exception ex) {
            logger.error("[CompareData] 비교 데이터 구성 실패 base={} target={}: {}", base, target, ex.getMessage(), ex);
            resp.put("success", false);
            resp.put("error", "비교 데이터 구성 중 오류가 발생했습니다: " + ex.getMessage());
            return ResponseEntity.status(500).body(resp);
        }
        return ResponseEntity.ok(resp);
    }
}

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import com.heapdump.analyzer.service.RemoteDumpService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.persistence.criteria.Predicate;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class ServerController {

    private final TargetServerRepository serverRepository;
    private final RemoteDumpService remoteDumpService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final DumpTransferLogRepository dumpTransferLogRepository;

    public ServerController(TargetServerRepository serverRepository,
                            RemoteDumpService remoteDumpService,
                            AnalysisHistoryRepository analysisHistoryRepository,
                            DumpTransferLogRepository dumpTransferLogRepository) {
        this.serverRepository = serverRepository;
        this.remoteDumpService = remoteDumpService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.dumpTransferLogRepository = dumpTransferLogRepository;
    }

    @GetMapping("/servers")
    public String serversPage(Model model) {
        List<TargetServer> servers = serverRepository.findAll();
        model.addAttribute("servers", servers);
        return "servers";
    }

    @GetMapping("/servers/{id}")
    public String serverDetailPage(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Optional<TargetServer> opt = serverRepository.findById(id);
        if (!opt.isPresent()) {
            redirectAttributes.addFlashAttribute("error", "서버를 찾을 수 없습니다: " + id);
            return "redirect:/servers";
        }
        TargetServer server = opt.get();
        List<AnalysisHistoryEntity> histories = analysisHistoryRepository.findByServerIdOrderByAnalyzedAtDesc(server.getId());
        if (histories.size() > 100) histories = histories.subList(0, 100);
        List<DumpTransferLog> transferLogs = dumpTransferLogRepository.findByServerIdOrderByStartedAtDesc(server.getId());
        if (transferLogs.size() > 100) transferLogs = transferLogs.subList(0, 100);

        long analysisSuccess = histories.stream().filter(h -> "SUCCESS".equals(h.getStatus())).count();
        long analysisFailed = histories.stream().filter(h -> "ERROR".equals(h.getStatus())).count();
        long transferSuccess = transferLogs.stream().filter(l -> "SUCCESS".equals(l.getTransferStatus())).count();
        long transferFailed = transferLogs.stream().filter(l -> "FAILED".equals(l.getTransferStatus())).count();

        model.addAttribute("server", server);
        model.addAttribute("histories", histories);
        model.addAttribute("transferLogs", transferLogs);
        model.addAttribute("analysisSuccess", analysisSuccess);
        model.addAttribute("analysisFailed", analysisFailed);
        model.addAttribute("transferSuccess", transferSuccess);
        model.addAttribute("transferFailed", transferFailed);
        return "server-detail";
    }

    @GetMapping("/servers/logs")
    public String serverLogsPage(Model model) {
        // 페이지/KPI 데이터는 클라이언트가 fetch — 여기서는 서버 셀렉트박스용 servers만 SSR
        model.addAttribute("servers", serverRepository.findAll());
        return "server-logs";
    }

    // ── Server CRUD API ──────────────────────────────────

    @PostMapping("/api/servers")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createServer(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            TargetServer server = new TargetServer();
            server.setName((String) body.get("name"));
            server.setHost((String) body.get("host"));
            server.setPort(body.containsKey("port") ? ((Number) body.get("port")).intValue() : 22);
            server.setSshUser(body.containsKey("sshUser") ? (String) body.get("sshUser") : "sscuser");
            server.setDumpPath((String) body.get("dumpPath"));
            server.setAutoDetect(body.containsKey("autoDetect") && Boolean.TRUE.equals(body.get("autoDetect")));
            server.setScanIntervalSec(body.containsKey("scanIntervalSec")
                    ? ((Number) body.get("scanIntervalSec")).intValue() : 300);
            server.setEnabled(true);
            serverRepository.save(server);
            result.put("success", true);
            result.put("serverId", server.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PutMapping("/api/servers/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateServer(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            TargetServer server = serverRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다: " + id));
            if (body.containsKey("name")) server.setName((String) body.get("name"));
            if (body.containsKey("host")) server.setHost((String) body.get("host"));
            if (body.containsKey("port")) server.setPort(((Number) body.get("port")).intValue());
            if (body.containsKey("sshUser")) server.setSshUser((String) body.get("sshUser"));
            if (body.containsKey("dumpPath")) server.setDumpPath((String) body.get("dumpPath"));
            if (body.containsKey("autoDetect")) server.setAutoDetect(Boolean.TRUE.equals(body.get("autoDetect")));
            if (body.containsKey("scanIntervalSec")) server.setScanIntervalSec(((Number) body.get("scanIntervalSec")).intValue());
            if (body.containsKey("enabled")) server.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
            serverRepository.save(server);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @DeleteMapping("/api/servers/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteServer(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            serverRepository.deleteById(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ── Connection Test ──────────────────────────────────

    @PostMapping("/api/servers/{id}/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        TargetServer server = serverRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다: " + id));
        Map<String, Object> result = remoteDumpService.testConnection(server);
        return ResponseEntity.ok(result);
    }

    // ── Scan & Transfer ──────────────────────────────────

    @PostMapping("/api/servers/{id}/scan")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> scanServer(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            TargetServer server = serverRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다: " + id));
            Map<String, Object> scanResult = remoteDumpService.scanRemoteDumpsWithStatus(server);
            result.put("success", !scanResult.containsKey("error"));
            result.put("files", scanResult.getOrDefault("files", java.util.Collections.emptyList()));
            result.put("count", scanResult.getOrDefault("count", 0));
            if (scanResult.containsKey("error")) {
                result.put("error", scanResult.get("error"));
            }
            if (scanResult.containsKey("errorCode")) {
                result.put("errorCode", scanResult.get("errorCode"));
            }
            if (scanResult.containsKey("dumpPath")) {
                result.put("dumpPath", scanResult.get("dumpPath"));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/api/servers/{id}/transfer")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> transferFile(@PathVariable Long id,
                                                            @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            TargetServer server = serverRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다: " + id));
            String remotePath = body.get("remotePath");
            DumpTransferLog log = remoteDumpService.transferFile(server, remotePath);
            result.put("success", "SUCCESS".equals(log.getTransferStatus()));
            result.put("filename", log.getFilename());
            result.put("status", log.getTransferStatus());
            if (log.getErrorMessage() != null) result.put("message", log.getErrorMessage());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * SSE 전송 — SCP 진행 중 500ms마다 progress 이벤트, 완료 시 done 이벤트.
     * EventSource 호환성을 위해 GET. CSRF 면제 영역(/api/**).
     */
    @GetMapping(value = "/api/servers/{id}/transfer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter transferStream(
            @PathVariable Long id, @RequestParam("remotePath") String remotePath) {
        // 11분 — SCP timeout(10분)보다 약간 길게
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(11L * 60 * 1000);

        TargetServer server = serverRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("서버를 찾을 수 없습니다: " + id));

        Thread worker = new Thread(() -> {
            try {
                DumpTransferLog log = remoteDumpService.transferFile(server, remotePath, (bytes, total) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("bytes", bytes);
                    p.put("total", total);
                    try {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                .event().name("progress").data(p));
                    } catch (Exception ignored) { /* 클라이언트 disconnect */ }
                });
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("success", "SUCCESS".equals(log.getTransferStatus()));
                done.put("filename", log.getFilename());
                done.put("status", log.getTransferStatus());
                done.put("fileSize", log.getFileSize());
                if (log.getErrorMessage() != null) done.put("message", log.getErrorMessage());
                try {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event().name("done").data(done));
                } catch (Exception ignored) {}
                emitter.complete();
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("message", e.getMessage() != null ? e.getMessage() : "전송 오류");
                try {
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event().name("done").data(err));
                } catch (Exception ignored) {}
                emitter.complete();
            }
        }, "transfer-stream-" + id);
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    @GetMapping("/api/servers/{id}/transfers")
    @ResponseBody
    public ResponseEntity<List<DumpTransferLog>> getTransferLogs(@PathVariable Long id) {
        return ResponseEntity.ok(remoteDumpService.getTransferLogs(id));
    }

    // ── Transfer Logs 페이지: 서버 사이드 페이지네이션 + 검색 + 필터 + Export ──

    private static final DateTimeFormatter LOG_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int EXPORT_CAP = 50_000;

    @GetMapping("/api/servers/transfers")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listTransferLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startedAt,desc") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {

        size = Math.max(1, Math.min(100, size));
        page = Math.max(0, page);

        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        Specification<DumpTransferLog> spec = buildSpec(q, status, serverId, dateFrom, dateTo);

        Page<DumpTransferLog> result = dumpTransferLogRepository.findAll(spec, pageable);
        Map<Long, String> serverNames = buildServerNames();

        List<TransferLogItem> items = result.getContent().stream()
                .map(l -> toItem(l, serverNames))
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", items);
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("number", result.getNumber());
        body.put("size", result.getSize());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/servers/transfers/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> transferStats(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        // KPI는 status 필터 무시 — Total/Success/Failed 비교 의미 유지. q + serverId + 기간만 적용.
        Specification<DumpTransferLog> spec = buildSpec(q, null, serverId, dateFrom, dateTo);
        long total = dumpTransferLogRepository.count(spec);
        long success = dumpTransferLogRepository.count(spec.and(statusEquals("SUCCESS")));
        long failed = dumpTransferLogRepository.count(spec.and(statusEquals("FAILED")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", total);
        body.put("success", success);
        body.put("failed", failed);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/servers/transfers/export")
    public ResponseEntity<String> exportTransferLogs(
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "startedAt,desc") String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long serverId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {

        Pageable pageable = PageRequest.of(0, EXPORT_CAP, parseSort(sort));
        Specification<DumpTransferLog> spec = buildSpec(q, status, serverId, dateFrom, dateTo);
        Page<DumpTransferLog> page = dumpTransferLogRepository.findAll(spec, pageable);
        Map<Long, String> serverNames = buildServerNames();

        List<TransferLogItem> items = page.getContent().stream()
                .map(l -> toItem(l, serverNames))
                .collect(Collectors.toList());

        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        boolean truncated = page.getTotalElements() > EXPORT_CAP;

        HttpHeaders headers = new HttpHeaders();
        if (truncated) headers.add("X-Truncated", "true");

        String body;
        if ("json".equalsIgnoreCase(format)) {
            body = renderJson(items);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setContentDispositionFormData("attachment", "transfer-logs-" + ts + ".json");
        } else {
            body = renderCsv(items);
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            headers.setContentDispositionFormData("attachment", "transfer-logs-" + ts + ".csv");
        }
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    // ── Helpers ──────────────────────────────────────────

    private Sort parseSort(String sortParam) {
        // "startedAt,desc" / "fileSize,asc" 형태. 잘못된 입력은 기본값으로 폴백
        if (sortParam == null || sortParam.isEmpty()) {
            return Sort.by(Sort.Direction.DESC, "startedAt");
        }
        String[] parts = sortParam.split(",");
        String field = parts[0].trim();
        if (!isAllowedSortField(field)) field = "startedAt";
        Sort.Direction dir = Sort.Direction.DESC;
        if (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) {
            dir = Sort.Direction.ASC;
        }
        return Sort.by(dir, field);
    }

    private boolean isAllowedSortField(String field) {
        // 클라이언트가 임의 필드로 정렬 못 하도록 화이트리스트
        switch (field) {
            case "id": case "filename": case "fileSize":
            case "transferStatus": case "startedAt": case "completedAt":
                return true;
            default:
                return false;
        }
    }

    private Specification<DumpTransferLog> buildSpec(String q, String status, Long serverId,
                                                     String dateFrom, String dateTo) {
        LocalDateTime fromTs = parseDateBoundary(dateFrom, false);
        LocalDateTime toTs = parseDateBoundary(dateTo, true); // exclusive upper (다음 날 00:00)
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (q != null && !q.trim().isEmpty()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("filename")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("remotePath"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("errorMessage"), "")), like)
                ));
            }
            if (status != null && !status.isEmpty()) {
                preds.add(cb.equal(root.get("transferStatus"), status));
            }
            if (serverId != null) {
                preds.add(cb.equal(root.get("serverId"), serverId));
            }
            if (fromTs != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("startedAt"), fromTs));
            }
            if (toTs != null) {
                preds.add(cb.lessThan(root.get("startedAt"), toTs));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /** yyyy-MM-dd → LocalDateTime. endExclusive=true 면 다음 날 00:00 (종료일 포함 효과). 잘못된 입력은 null. */
    private LocalDateTime parseDateBoundary(String iso, boolean endExclusive) {
        if (iso == null || iso.trim().isEmpty()) return null;
        try {
            String[] p = iso.trim().split("-");
            if (p.length != 3) return null;
            LocalDateTime d = LocalDateTime.of(
                    Integer.parseInt(p[0]),
                    Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]),
                    0, 0, 0);
            return endExclusive ? d.plusDays(1) : d;
        } catch (Exception e) {
            return null;
        }
    }

    private Specification<DumpTransferLog> statusEquals(String status) {
        return (root, query, cb) -> cb.equal(root.get("transferStatus"), status);
    }

    private Map<Long, String> buildServerNames() {
        return serverRepository.findAll().stream()
                .collect(Collectors.toMap(TargetServer::getId, TargetServer::getName, (a, b) -> a));
    }

    private TransferLogItem toItem(DumpTransferLog l, Map<Long, String> serverNames) {
        TransferLogItem t = new TransferLogItem();
        t.id = l.getId();
        t.serverId = l.getServerId();
        t.serverName = serverNames.getOrDefault(l.getServerId(), "(삭제된 서버 #" + l.getServerId() + ")");
        t.filename = l.getFilename();
        t.remotePath = l.getRemotePath();
        t.transferStatus = l.getTransferStatus();
        t.fileSize = l.getFileSize();
        t.formattedSize = formatBytes(l.getFileSize());
        t.startedAt = l.getStartedAt() != null ? l.getStartedAt().format(LOG_TS_FMT) : null;
        t.startedAtMillis = l.getStartedAt() != null
                ? l.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : 0L;
        t.completedAt = l.getCompletedAt() != null ? l.getCompletedAt().format(LOG_TS_FMT) : null;
        if (l.getStartedAt() != null && l.getCompletedAt() != null) {
            long ms = Duration.between(l.getStartedAt(), l.getCompletedAt()).toMillis();
            t.durationMs = ms;
            t.formattedDuration = formatDuration(ms);
        } else if ("IN_PROGRESS".equals(l.getTransferStatus())) {
            t.durationMs = null;
            t.formattedDuration = "진행 중";
        } else {
            t.durationMs = null;
            t.formattedDuration = "-";
        }
        t.errorMessage = l.getErrorMessage();
        return t;
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null) return "-";
        double b = bytes.doubleValue();
        if (b < 1024) return bytes + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024);
        if (b < 1024 * 1024 * 1024) return String.format("%.1f MB", b / (1024 * 1024));
        return String.format("%.2f GB", b / (1024 * 1024 * 1024));
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "초";
        long m = s / 60; long rs = s % 60;
        if (m < 60) return rs == 0 ? m + "분" : (m + "분 " + rs + "초");
        long h = m / 60; long rm = m % 60;
        return rm == 0 ? h + "시간" : (h + "시간 " + rm + "분");
    }

    // ── Export rendering ─────────────────────────────────

    private String renderCsv(List<TransferLogItem> items) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("id,serverId,serverName,filename,remotePath,transferStatus,fileSize,startedAt,completedAt,durationMs,errorMessage");
        for (TransferLogItem t : items) {
            pw.println(String.join(",",
                    csvCell(t.id),
                    csvCell(t.serverId),
                    csvCell(t.serverName),
                    csvCell(t.filename),
                    csvCell(t.remotePath),
                    csvCell(t.transferStatus),
                    csvCell(t.fileSize),
                    csvCell(t.startedAt),
                    csvCell(t.completedAt),
                    csvCell(t.durationMs),
                    csvCell(t.errorMessage)));
        }
        pw.flush();
        return sw.toString();
    }

    private static String csvCell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String renderJson(List<TransferLogItem> items) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ── Inner DTO ────────────────────────────────────────
    public static class TransferLogItem {
        public Long id;
        public Long serverId;
        public String serverName;
        public String filename;
        public String remotePath;
        public String transferStatus;
        public Long fileSize;
        public String formattedSize;
        public String startedAt;
        public Long startedAtMillis;
        public String completedAt;
        public Long durationMs;
        public String formattedDuration;
        public String errorMessage;
    }

    // ── Scan interval ────────────────────────────────────

    @GetMapping("/api/servers/scan-interval")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getScanInterval() {
        Map<String, Object> result = new HashMap<>();
        result.put("intervalSec", remoteDumpService.getScanIntervalSec());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/servers/scan-interval")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setScanInterval(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            int sec = ((Number) body.get("intervalSec")).intValue();
            remoteDumpService.setScanIntervalSec(sec);
            result.put("success", true);
            result.put("intervalSec", remoteDumpService.getScanIntervalSec());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ── SCP temp dir ─────────────────────────────────────

    @GetMapping("/api/servers/scp-temp-dir")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getScpTempDir() {
        Map<String, Object> result = new HashMap<>();
        result.put("tempDir", remoteDumpService.getScpTempDir());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/servers/scp-temp-dir")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setScpTempDir(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String dir = body.getOrDefault("tempDir", "/tmp");
            remoteDumpService.setScpTempDir(dir);
            result.put("success", true);
            result.put("tempDir", remoteDumpService.getScpTempDir());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ── SSH local user ────────────────────────────────────

    @GetMapping("/api/servers/ssh-local-user")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSshLocalUser() {
        Map<String, Object> result = new HashMap<>();
        result.put("localUser", remoteDumpService.getSshLocalUser());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/servers/ssh-local-user")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setSshLocalUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String user = body.getOrDefault("localUser", "");
            remoteDumpService.setSshLocalUser(user);
            result.put("success", true);
            result.put("localUser", remoteDumpService.getSshLocalUser());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    // ── Auto-scan errors ─────────────────────────────────

    @GetMapping("/api/servers/scan-errors")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getScanErrors() {
        Map<String, Object> result = new HashMap<>();
        result.put("errors", remoteDumpService.getLastAutoScanErrors());
        return ResponseEntity.ok(result);
    }
}

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import com.heapdump.analyzer.service.RemoteDumpService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
        List<TargetServer> servers = serverRepository.findAll();
        model.addAttribute("servers", servers);
        Map<Long, List<DumpTransferLog>> logsByServer = new LinkedHashMap<>();
        Map<Long, long[]> statsByServer = new LinkedHashMap<>(); // [total, success, failed]
        for (TargetServer s : servers) {
            List<DumpTransferLog> logs = remoteDumpService.getTransferLogs(s.getId());
            if (logs.size() > 50) logs = logs.subList(0, 50);
            logsByServer.put(s.getId(), logs);
            long success = logs.stream().filter(l -> "SUCCESS".equals(l.getTransferStatus())).count();
            long failed = logs.stream().filter(l -> "FAILED".equals(l.getTransferStatus())).count();
            statsByServer.put(s.getId(), new long[]{logs.size(), success, failed});
        }
        model.addAttribute("logsByServer", logsByServer);
        model.addAttribute("statsByServer", statsByServer);
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

    @GetMapping("/api/servers/{id}/transfers")
    @ResponseBody
    public ResponseEntity<List<DumpTransferLog>> getTransferLogs(@PathVariable Long id) {
        return ResponseEntity.ok(remoteDumpService.getTransferLogs(id));
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

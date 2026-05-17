package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.util.AesEncryptor;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시스템/설정/DB/MAT/디스크 관련 API (Phase 4B-2).
 */
@Controller
public class HeapSystemApiController {

    private static final Logger logger = LoggerFactory.getLogger(HeapSystemApiController.class);

    private static final Pattern DB_URL_PATTERN = Pattern.compile("//([^:/]+)(?::(\\d+))?/([^?]+)");

    private final HeapDumpAnalyzerService analyzerService;
    private final HeapDumpConfig config;
    private final DataSourceProperties dataSourceProperties;
    private final DataSource dataSource;

    public HeapSystemApiController(HeapDumpAnalyzerService analyzerService,
                                   HeapDumpConfig config,
                                   DataSourceProperties dataSourceProperties,
                                   DataSource dataSource) {
        this.analyzerService = analyzerService;
        this.config = config;
        this.dataSourceProperties = dataSourceProperties;
        this.dataSource = dataSource;
    }

    @PostMapping("/api/settings/unreachable")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setUnreachable(@RequestParam boolean enabled) {
        analyzerService.setKeepUnreachableObjects(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("keepUnreachableObjects", enabled);
        resp.put("message", "Setting updated. Takes effect on next analysis.");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/settings/compress")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setCompressAfterAnalysis(@RequestParam boolean enabled) {
        analyzerService.setCompressAfterAnalysis(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("compressAfterAnalysis", enabled);
        resp.put("message", "Setting updated. Takes effect on next analysis.");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/settings/database/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testDbConnection(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String host = body.getOrDefault("host", "");
        String port = body.getOrDefault("port", "3306");
        String database = body.getOrDefault("database", "HEAPDB");
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Seoul";
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url, username, password)) {
                if (conn.isValid(5)) {
                    java.sql.DatabaseMetaData meta = conn.getMetaData();
                    resp.put("success", true);
                    resp.put("version", meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
                    resp.put("message", "연결 성공");
                } else {
                    resp.put("success", false);
                    resp.put("message", "연결 실패: 유효하지 않은 연결");
                }
            }
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "연결 실패: " + e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/settings/database")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveDatabaseSettings(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String host = body.getOrDefault("host", "");
        String port = body.getOrDefault("port", "3306");
        String database = body.getOrDefault("database", "HEAPDB");
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            resp.put("success", false);
            resp.put("message", "호스트, 계정, 패스워드를 모두 입력하세요.");
            return ResponseEntity.badRequest().body(resp);
        }

        String encryptedPw = AesEncryptor.encrypt(password);
        String newUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Seoul";

        File propsFile = analyzerService.findExternalPropertiesFilePublic();
        if (propsFile == null) {
            resp.put("success", false);
            resp.put("message", "application.properties 파일을 찾을 수 없습니다.");
            return ResponseEntity.ok(resp);
        }

        try {
            List<String> lines = Files.readAllLines(propsFile.toPath(), StandardCharsets.UTF_8);
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put("spring.datasource.url", newUrl);
            updates.put("spring.datasource.username", username);
            updates.put("spring.datasource.password", "ENC(" + encryptedPw + ")");

            List<String> newLines = new java.util.ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                boolean replaced = false;
                for (Map.Entry<String, String> entry : updates.entrySet()) {
                    if (trimmed.startsWith(entry.getKey() + "=")) {
                        newLines.add(entry.getKey() + "=" + entry.getValue());
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) newLines.add(line);
            }
            Files.write(propsFile.toPath(), newLines, StandardCharsets.UTF_8);

            resp.put("success", true);
            resp.put("message", "DB 설정이 저장되었습니다. 변경사항을 적용하려면 앱을 재시작하세요.");
            resp.put("requireRestart", true);
            logger.info("[Settings] DB 설정 변경: host={}, port={}, db={}, user={}", host, port, database, username);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("message", "설정 파일 저장 실패: " + e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/mat/heap-check")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> checkHeapBeforeAnalysis(@RequestParam String filename) {
        filename = FilenameValidator.validate(filename);
        Map<String, Object> resp = new LinkedHashMap<>();
        String safe = filename;

        File dumpFile = new File(analyzerService.getHeapDumpDirectory(), safe);
        File tmpFile = new File(analyzerService.getHeapDumpDirectory() + "/tmp", safe);
        File gzFile = new File(analyzerService.getHeapDumpDirectory(), safe + ".gz");

        long dumpSize = 0;
        if (tmpFile.exists()) dumpSize = tmpFile.length();
        else if (dumpFile.exists()) dumpSize = dumpFile.length();
        else if (gzFile.exists()) dumpSize = gzFile.length();

        long matHeap = analyzerService.getMatHeapSize();
        boolean warning = matHeap > 0 && dumpSize > 0 && dumpSize * 2 > matHeap;

        resp.put("warning", warning);
        resp.put("dumpSize", dumpSize);
        resp.put("dumpSizeFormatted", FormatUtils.formatBytes(dumpSize));
        resp.put("recommendedHeap", FormatUtils.formatBytes(dumpSize * 2));
        resp.put("matHeap", matHeap);
        resp.put("matHeapFormatted", matHeap > 0 ? FormatUtils.formatBytes(matHeap) : "unknown");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/system/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();

        resp.put("matCliReady", config.isMatCliReady());
        resp.put("matCliStatus", config.getMatCliStatusMessage());

        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists() && dumpDir.getTotalSpace() > 0) {
            long total = dumpDir.getTotalSpace();
            long usable = dumpDir.getUsableSpace();
            long used = total - usable;
            resp.put("diskUsedPercent", Math.round(used * 100.0 / total));
            resp.put("diskUsed", FormatUtils.formatBytes(used));
            resp.put("diskTotal", FormatUtils.formatBytes(total));
        }

        Runtime rt = Runtime.getRuntime();
        long jvmMax = rt.maxMemory();
        long jvmUsed = rt.totalMemory() - rt.freeMemory();
        resp.put("jvmUsedMb", jvmUsed / (1024 * 1024));
        resp.put("jvmMaxMb", jvmMax / (1024 * 1024));
        resp.put("jvmUsedPercent", Math.round(jvmUsed * 100.0 / jvmMax));

        resp.put("queueSize", analyzerService.getQueueSize());
        resp.put("currentAnalysis", analyzerService.getCurrentAnalysisFilename());

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/mat/heap")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMatHeap() {
        Map<String, Object> resp = new LinkedHashMap<>();
        String heapStr = analyzerService.getMatHeapSizeString();
        long heapBytes = analyzerService.getMatHeapSize();
        resp.put("heapSize", heapStr != null ? heapStr : "unknown");
        resp.put("heapBytes", heapBytes);
        resp.put("heapFormatted", heapBytes > 0 ? FormatUtils.formatBytes(heapBytes) : "unknown");

        String xmsStr = analyzerService.getMatInitialHeapSizeString();
        long xmsBytes = analyzerService.getMatInitialHeapSize();
        resp.put("xmsSize", xmsStr != null ? xmsStr : "none");
        resp.put("xmsBytes", xmsBytes);
        resp.put("xmsFormatted", xmsBytes > 0 ? FormatUtils.formatBytes(xmsBytes) : "not set");

        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long physMem = osBean.getTotalPhysicalMemorySize();
            resp.put("physicalMemory", physMem);
            resp.put("physicalMemoryFormatted", FormatUtils.formatBytes(physMem));
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
            resp.put("totalSpace", FormatUtils.formatBytes(totalSpace));
            resp.put("usableSpace", FormatUtils.formatBytes(usableSpace));
            resp.put("usableSpaceBytes", usableSpace);
            resp.put("warning", usagePercent >= config.getDiskWarningUsagePercent());
        } else {
            resp.put("warning", false);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("keepUnreachableObjects", analyzerService.isKeepUnreachableObjects());
        settings.put("heapDumpDirectory",      analyzerService.getHeapDumpDirectory());
        settings.put("cachedResults",          analyzerService.getCachedResultCount());

        // System info (JVM runtime only — no OS/vendor details)
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("javaVersion",  System.getProperty("java.version"));
        Runtime rt = Runtime.getRuntime();
        system.put("jvmMaxMemory",     FormatUtils.formatBytes(rt.maxMemory()));
        system.put("jvmTotalMemory",   FormatUtils.formatBytes(rt.totalMemory()));
        system.put("jvmFreeMemory",    FormatUtils.formatBytes(rt.freeMemory()));
        system.put("jvmUsedMemory",    FormatUtils.formatBytes(rt.totalMemory() - rt.freeMemory()));
        system.put("availableProcessors", rt.availableProcessors());
        settings.put("system", system);

        // Disk info (usage percentages only — no absolute capacity)
        Map<String, Object> disk = new LinkedHashMap<>();
        File dumpDir = new File(analyzerService.getHeapDumpDirectory());
        if (dumpDir.exists()) {
            disk.put("totalSpace",  FormatUtils.formatBytes(dumpDir.getTotalSpace()));
            disk.put("freeSpace",   FormatUtils.formatBytes(dumpDir.getUsableSpace()));
            disk.put("usableSpace", FormatUtils.formatBytes(dumpDir.getUsableSpace()));
            long used = dumpDir.getTotalSpace() - dumpDir.getUsableSpace();
            disk.put("usedSpace",   FormatUtils.formatBytes(used));
            disk.put("usedPercent", dumpDir.getTotalSpace() > 0
                    ? Math.round(used * 100.0 / dumpDir.getTotalSpace()) : 0);
        }
        settings.put("disk", disk);

        // MAT CLI status (ready/status only — no path or file permission details)
        Map<String, Object> mat = new LinkedHashMap<>();
        mat.put("path",       analyzerService.getMatCliPath());
        mat.put("ready",      analyzerService.isMatCliReady());
        mat.put("statusMessage", analyzerService.getMatCliStatusMessage());
        String matHeapStr = analyzerService.getMatHeapSizeString();
        long matHeapBytes = analyzerService.getMatHeapSize();
        mat.put("heapSize", matHeapStr != null ? matHeapStr : "unknown");
        mat.put("heapBytes", matHeapBytes);
        mat.put("heapFormatted", matHeapBytes > 0 ? FormatUtils.formatBytes(matHeapBytes) : "unknown");
        String matXmsStr = analyzerService.getMatInitialHeapSizeString();
        long matXmsBytes = analyzerService.getMatInitialHeapSize();
        mat.put("xmsSize", matXmsStr != null ? matXmsStr : "none");
        mat.put("xmsBytes", matXmsBytes);
        mat.put("xmsFormatted", matXmsBytes > 0 ? FormatUtils.formatBytes(matXmsBytes) : "not set");
        settings.put("mat", mat);

        // File stats
        List<HeapDumpFile> files = analyzerService.listFiles();
        Map<String, Object> fileStats = new LinkedHashMap<>();
        fileStats.put("totalFiles", files.size());
        long originalBytes = files.stream().mapToLong(HeapDumpFile::getSize).sum();
        long diskBytes = files.stream()
            .mapToLong(f -> f.isCompressed() && f.getCompressedSize() > 0 ? f.getCompressedSize() : f.getSize())
            .sum();
        fileStats.put("totalSize", FormatUtils.formatBytes(originalBytes));
        fileStats.put("diskSize", FormatUtils.formatBytes(diskBytes));
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
        providerModels.put("genspark", com.heapdump.analyzer.service.HeapDumpAnalyzerService.GENSPARK_MODELS);
        providerModels.put("custom", Collections.emptyList());
        llm.put("providerModels", providerModels);
        llm.put("chatSystemPrompt", analyzerService.getLlmChatSystemPrompt());
        llm.put("chatRestoreIncludeHistory", analyzerService.isLlmChatRestoreIncludeHistory());
        llm.put("sslVerify", analyzerService.isLlmSslVerify());
        settings.put("llm", llm);

        // Database 정보
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            String dbUrl = dataSourceProperties.getUrl() != null ? dataSourceProperties.getUrl() : "";
            String dbUser = dataSourceProperties.getUsername() != null ? dataSourceProperties.getUsername() : "";
            String dbHost = "-", dbPort = "3306", dbName = "-";
            Matcher m = DB_URL_PATTERN.matcher(dbUrl);
            if (m.find()) {
                dbHost = m.group(1);
                if (m.group(2) != null) dbPort = m.group(2);
                dbName = m.group(3);
            }
            db.put("host", dbHost);
            db.put("port", dbPort);
            db.put("database", dbName);
            db.put("username", dbUser);
            boolean connected = false;
            String dbVersion = "-";
            long historyCount = 0;
            try (java.sql.Connection conn = dataSource.getConnection()) {
                connected = conn.isValid(3);
                java.sql.DatabaseMetaData meta = conn.getMetaData();
                dbVersion = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
                historyCount = analyzerService.getAnalysisHistoryRepository().count();
            } catch (Exception ex) {
                dbVersion = "Connection failed";
            }
            db.put("connected", connected);
            db.put("version", dbVersion);
            db.put("historyCount", historyCount);
        } catch (Exception e) {
            db.put("connected", false);
            db.put("version", "Error");
        }
        settings.put("database", db);

        return ResponseEntity.ok(settings);
    }
}

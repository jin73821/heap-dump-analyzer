package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
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
import java.time.Duration;

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
    private final org.springframework.session.jdbc.JdbcIndexedSessionRepository jdbcSessionRepo;

    public HeapSystemApiController(HeapDumpAnalyzerService analyzerService,
                                   HeapDumpConfig config,
                                   DataSourceProperties dataSourceProperties,
                                   DataSource dataSource,
                                   org.springframework.session.jdbc.JdbcIndexedSessionRepository jdbcSessionRepo) {
        this.analyzerService = analyzerService;
        this.config = config;
        this.dataSourceProperties = dataSourceProperties;
        this.dataSource = dataSource;
        this.jdbcSessionRepo = jdbcSessionRepo;
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

    @PostMapping("/api/settings/allow-all-extensions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setAllowAllExtensions(@RequestParam boolean enabled) {
        analyzerService.setAllowAllExtensions(enabled);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("allowAllExtensions", enabled);
        resp.put("message", "확장자 제한 해제 = " + enabled + ". 다음 업로드부터 즉시 반영됩니다.");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/settings/max-upload-size")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setMaxUploadSize(@RequestParam long bytes) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            analyzerService.setMaxUploadSizeBytes(bytes);
        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
        long gb = bytes / (1024L * 1024 * 1024);
        resp.put("success", true);
        resp.put("maxUploadSizeBytes", bytes);
        resp.put("maxUploadSizeFormatted", FormatUtils.formatBytes(bytes));
        resp.put("requireRestart", true);
        resp.put("message", "최대 업로드 크기가 " + gb + " GB 로 변경되었습니다. Tomcat 멀티파트 한도는 앱 재시작 후 적용됩니다.");
        logger.info("[Settings] Max upload size changed to {} bytes ({} GB)", bytes, gb);
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

        long dumpSize = estimateUncompressedDumpSize(safe);
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

    /**
     * 힙 경고 판정을 위한 "비압축(압축 해제) 덤프 크기" 추정.
     * 압축된 .gz 만 존재할 때 압축 크기를 그대로 쓰면 실제 크기를 크게 과소평가하므로 비압축 크기를 추정한다.
     * 우선순위: ① 분석 중 tmp 작업본 → ② dumpfiles/ 비압축 원본 → ③ legacy 루트 비압축 원본
     *          → ④ .gz(dumpfiles/ 우선, 없으면 루트): 캐시된 분석결과 originalFileSize > gzip ISIZE > .gz 길이
     */
    private long estimateUncompressedDumpSize(String safe) {
        // ① 분석 중 tmp 작업본 (압축 해제됨 — 가장 정확)
        File tmpFile = new File(analyzerService.getHeapDumpDirectory() + File.separator + "tmp", safe);
        if (tmpFile.exists()) return tmpFile.length();

        // ② dumpfiles/ 비압축 원본
        File dumpFile = new File(config.getDumpFilesDirectory(), safe);
        if (dumpFile.exists()) return dumpFile.length();

        // ③ legacy 루트 비압축 원본 (마이그레이션 호환)
        File legacyDump = new File(analyzerService.getHeapDumpDirectory(), safe);
        if (legacyDump.exists()) return legacyDump.length();

        // ④ 압축본만 존재 — 비압축 크기 추정
        File gzFile = new File(config.getDumpFilesDirectory(), safe + ".gz");
        if (!gzFile.exists()) gzFile = new File(analyzerService.getHeapDumpDirectory(), safe + ".gz");
        if (gzFile.exists()) {
            // (a) 이전 분석 결과에 기록된 비압축 원본 크기 (가장 정확)
            HeapAnalysisResult cached = analyzerService.getCachedResult(safe);
            if (cached != null && cached.getOriginalFileSize() > gzFile.length()) {
                return cached.getOriginalFileSize();
            }
            // (b) gzip ISIZE (마지막 4바이트, little-endian, 비압축 mod 2^32 — <4GB 정확)
            long isize = readGzipUncompressedSize(gzFile);
            if (isize > gzFile.length()) return isize;
            // (c) 최후: 압축 크기 (과소평가 가능하나 0보다 안전)
            return gzFile.length();
        }
        return 0;
    }

    /** gzip 파일의 마지막 4바이트 ISIZE(비압축 크기 mod 2^32) 읽기. 실패 시 0. */
    private long readGzipUncompressedSize(File gz) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(gz, "r")) {
            long len = raf.length();
            if (len < 4) return 0;
            raf.seek(len - 4);
            byte[] b = new byte[4];
            raf.readFully(b);
            return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16) | ((b[3] & 0xFFL) << 24);
        } catch (Exception e) {
            return 0;
        }
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

    @PostMapping("/api/settings/session-timeout")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setSessionTimeout(@RequestParam int hours) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            analyzerService.setSessionTimeoutHours(hours);
            // 런타임 즉시 반영: 이후 생성되는 신규 세션에 적용
            jdbcSessionRepo.setDefaultMaxInactiveInterval(Duration.ofHours(hours));
        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
        resp.put("success", true);
        resp.put("sessionTimeoutHours", hours);
        resp.put("message", "세션 타임아웃이 " + hours + "시간으로 변경되었습니다. 기존 세션에는 다음 갱신 시 적용됩니다.");
        logger.info("[Settings] Session timeout changed to {}h", hours);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/api/settings/dashboard-detect-days")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> setDashboardDetectDays(@RequestParam int days) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            analyzerService.setDashboardDetectDays(days);
        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
        resp.put("success", true);
        resp.put("dashboardDetectDays", days);
        resp.put("message", "대시보드 탐지 기간이 " + days + "일로 변경되었습니다.");
        logger.info("[Settings] Dashboard detect days changed to {}", days);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/settings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("keepUnreachableObjects", analyzerService.isKeepUnreachableObjects());
        settings.put("heapDumpDirectory",      analyzerService.getHeapDumpDirectory());
        settings.put("cachedResults",          analyzerService.getCachedResultCount());
        settings.put("allowAllExtensions",     analyzerService.isAllowAllExtensions());
        settings.put("sessionTimeoutHours",    analyzerService.getSessionTimeoutHours());
        settings.put("dashboardDetectDays",    analyzerService.getDashboardDetectDays());

        long maxUploadBytes = analyzerService.getMaxUploadSizeBytes();
        settings.put("maxUploadSizeBytes",     maxUploadBytes);
        settings.put("maxUploadSizeGb",        maxUploadBytes / (1024L * 1024 * 1024));
        settings.put("maxUploadSizeFormatted", FormatUtils.formatBytes(maxUploadBytes));
        settings.put("maxUploadLimitBytes",    com.heapdump.analyzer.service.HeapDumpAnalyzerService.MAX_UPLOAD_LIMIT_BYTES);

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
        llm.put("fileAttachEnabled", analyzerService.isLlmFileAttachEnabled());
        llm.put("fileAttachCapable", analyzerService.isFileAttachCapable());
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

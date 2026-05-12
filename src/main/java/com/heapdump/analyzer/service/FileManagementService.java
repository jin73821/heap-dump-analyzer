package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 파일 관리 서비스 (Phase 4A-4, Phase 1 — 유틸/조회 메서드만).
 *
 * 현재 책임:
 *   - 디렉토리 helper: dumpFilesDirectory()
 *   - 파일명 유틸: isValidHeapDumpFile / stripExtension / getExtension
 *   - 중복 검사: computePartialHash / generateUniqueName / checkDuplicate
 *   - 조회: listFiles
 *
 * 추후 Phase 4A-4 Phase 2 에서 uploadFile/deleteFile/compressDumpFile 등 I/O 메서드 추가 예정.
 */
@Component
public class FileManagementService {

    private static final Logger logger = LoggerFactory.getLogger(FileManagementService.class);

    private final HeapDumpConfig config;
    private final HeapAnalysisResultCache resultCache;

    public FileManagementService(HeapDumpConfig config, HeapAnalysisResultCache resultCache) {
        this.config = config;
        this.resultCache = resultCache;
    }

    // ── 디렉토리 helper ──────────────────────────────────────────

    public File dumpFilesDirectory() {
        return new File(config.getDumpFilesDirectory());
    }

    // ── 파일명 유틸 ───────────────────────────────────────────────

    public boolean isValidHeapDumpFile(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.endsWith(".hprof") || l.endsWith(".bin") || l.endsWith(".dump")
                || l.endsWith(".hprof.gz") || l.endsWith(".bin.gz") || l.endsWith(".dump.gz");
    }

    /** `.hprof.gz` → base name (strip `.gz` first, then `.hprof`). */
    public String stripExtension(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
    }

    // ── 중복 검사 ─────────────────────────────────────────────────

    public String computePartialHash(File file, int bytes) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = file.getName().toLowerCase().endsWith(".gz")
                ? new GZIPInputStream(new FileInputStream(file))
                : new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int totalRead = 0;
            while (totalRead < bytes) {
                int read = is.read(buf, 0, Math.min(buf.length, bytes - totalRead));
                if (read < 0) break;
                digest.update(buf, 0, read);
                totalRead += read;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String generateUniqueName(String filename, File directory) {
        String base = stripExtension(filename);
        String ext = getExtension(filename);
        int counter = 2;
        String candidate;
        do {
            candidate = base + "_" + counter + "." + ext;
            counter++;
        } while (new File(directory, candidate).exists()
                || new File(directory, candidate + ".gz").exists());
        return candidate;
    }

    public Map<String, String> checkDuplicate(String filename, long fileSize, String partialHash) {
        Map<String, String> result = new LinkedHashMap<>();
        File dir = dumpFilesDirectory();
        File[] files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
        if (files == null) {
            result.put("status", "OK");
            return result;
        }

        boolean nameMatch = false;
        for (File f : files) {
            String fName = f.getName();
            long existingSize;
            boolean isGz = fName.toLowerCase().endsWith(".gz");
            if (isGz) {
                String displayName = fName.substring(0, fName.length() - 3);
                HeapAnalysisResult cached = resultCache.get(displayName);
                existingSize = (cached != null && cached.getOriginalFileSize() > 0)
                        ? cached.getOriginalFileSize() : -1;
            } else {
                existingSize = f.length();
            }

            String existingDisplayName = isGz ? fName.substring(0, fName.length() - 3) : fName;
            if (existingDisplayName.equals(filename)) {
                nameMatch = true;
            }

            if (existingSize == fileSize) {
                try {
                    String existingHash = computePartialHash(f, 65536);
                    if (existingHash.equals(partialHash)) {
                        result.put("status", "DUPLICATE_CONTENT");
                        result.put("existingFilename", existingDisplayName);
                        logger.info("[Upload Check] Duplicate content: '{}' matches '{}'", filename, existingDisplayName);
                        return result;
                    }
                } catch (Exception e) {
                    logger.debug("[Upload Check] Hash computation failed for {}: {}", fName, e.getMessage());
                }
            }
        }

        if (nameMatch) {
            result.put("status", "DUPLICATE_NAME");
            result.put("existingFilename", filename);
            result.put("suggestedName", generateUniqueName(filename, dir));
            logger.info("[Upload Check] Name conflict: '{}', suggested: '{}'", filename, result.get("suggestedName"));
            return result;
        }

        result.put("status", "OK");
        return result;
    }

    // ── 조회 ──────────────────────────────────────────────────────

    public List<HeapDumpFile> listFiles() {
        List<HeapDumpFile> result = new ArrayList<>();

        File dir = dumpFilesDirectory();
        File[] files = dir.listFiles((d, n) -> isValidHeapDumpFile(n));
        Set<String> existing = new HashSet<>();
        if (files != null) {
            for (File f : files) {
                String displayName = f.getName();
                boolean compressed = displayName.toLowerCase().endsWith(".gz");
                if (compressed) {
                    displayName = displayName.substring(0, displayName.length() - 3);
                }
                if (!existing.contains(displayName)) {
                    HeapDumpFile hdf = new HeapDumpFile();
                    hdf.setName(displayName);
                    hdf.setPath(f.getAbsolutePath());
                    hdf.setSize(f.length());
                    hdf.setLastModified(f.lastModified());
                    if (compressed) {
                        hdf.setCompressed(true);
                        hdf.setCompressedSize(f.length());
                        HeapAnalysisResult cached = resultCache.get(displayName);
                        if (cached != null && cached.getOriginalFileSize() > 0) {
                            hdf.setOriginalSize(cached.getOriginalFileSize());
                            hdf.setSize(cached.getOriginalFileSize());
                        } else {
                            hdf.setOriginalSize(f.length());
                        }
                    }
                    result.add(hdf);
                    existing.add(displayName);
                }
            }
        }

        result.sort(Comparator.comparingLong(HeapDumpFile::getLastModified).reversed());
        return result;
    }
}

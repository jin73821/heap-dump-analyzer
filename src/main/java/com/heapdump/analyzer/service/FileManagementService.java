package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 파일 관리 서비스 (Phase 4A-4).
 *
 * 책임:
 *   - 디렉토리 helper: dumpFilesDirectory / tmpDirectory / resultDirectory / resultJsonFile
 *   - 파일명 유틸: isValidHeapDumpFile / stripExtension / getExtension
 *   - 중복 검사: computePartialHash / generateUniqueName / checkDuplicate
 *   - 조회: listFiles / getFile
 *   - I/O: uploadFile / deleteFile / compressDumpFile / decompressDumpFile / cleanupDuplicateGzFiles
 *
 * 분석 파이프라인 결합도 높은 메서드는 HeapDumpAnalyzerService 잔존:
 *   - cleanupTmpDir / moveZipsToResultDir / moveArtifactsToResultDir / deleteHistory(DB) / 마이그레이션
 */
@Component
public class FileManagementService {

    private static final Logger logger = LoggerFactory.getLogger(FileManagementService.class);

    private static final String RESULT_JSON  = "result.json";
    private static final String TMP_DIR_NAME = "tmp";

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

    public File tmpDirectory() {
        return new File(config.getHeapDumpDirectory(), TMP_DIR_NAME);
    }

    public File resultDirectory(String filename) {
        return new File(config.getDataDirectory(), stripExtension(filename));
    }

    public File resultJsonFile(String filename) {
        return new File(resultDirectory(filename), RESULT_JSON);
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

    // ── I/O ───────────────────────────────────────────────────────

    /**
     * MultipartFile 을 dumpfiles/ 디렉토리에 저장. 검증 + 중복 차단 포함.
     * @return 저장된 파일명 (path 제거된)
     */
    public String uploadFile(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            logger.warn("[Upload] Rejected: empty file");
            throw new IllegalArgumentException("File is empty");
        }

        String originalName = file.getOriginalFilename();
        String filename = Optional.ofNullable(originalName)
                .map(n -> new File(n).getName()).filter(n -> !n.isEmpty())
                .orElseThrow(() -> {
                    logger.warn("[Upload] Rejected: invalid or missing filename");
                    return new IllegalArgumentException("Invalid filename");
                });

        logger.info("[Upload] Started: filename={}, size={}, contentType={}",
                filename, FormatUtils.formatBytes(file.getSize()), file.getContentType());

        if (!isValidHeapDumpFile(filename)) {
            String ext = getExtension(filename);
            logger.warn("[Upload] Rejected: invalid extension '{}' for file '{}'. Allowed: .hprof, .bin, .dump (+ .gz)",
                    ext, filename);
            throw new IllegalArgumentException(
                    "'" + ext + "' is not a supported file type. Only .hprof, .bin, .dump (+ .gz) files are allowed.");
        }

        File dumpDir = dumpFilesDirectory();
        Files.createDirectories(dumpDir.toPath());

        if (Files.exists(dumpDir.toPath().resolve(filename))
                || Files.exists(dumpDir.toPath().resolve(filename + ".gz"))) {
            logger.warn("[Upload] Rejected: same filename already exists '{}'", filename);
            throw new IllegalArgumentException(
                    "동일한 이름의 파일이 이미 존재합니다: '" + filename + "'. 다른 이름으로 변경 후 업로드해 주세요.");
        }

        Path target = dumpDir.toPath().resolve(filename);
        try {
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            logger.error("[Upload] Failed to write file '{}' to dumpfiles: {}", filename, e.getMessage(), e);
            throw e;
        }

        long writtenSize = Files.size(target);
        logger.info("[Upload] Completed: filename={}, writtenSize={}, path={} (dumpfiles)",
                filename, FormatUtils.formatBytes(writtenSize), target.toAbsolutePath());
        return filename;
    }

    /**
     * 파일 다운로드용 경로 조회. dumpfiles → legacy root → tmp 순으로 탐색.
     * .gz 압축본도 확인.
     */
    public File getFile(String filename) throws IOException {
        filename = new File(filename).getName();
        File file = new File(config.getDumpFilesDirectory(), filename);
        if (!file.exists()) {
            File gzFile = new File(config.getDumpFilesDirectory(), filename + ".gz");
            if (gzFile.exists()) return gzFile;
            file = new File(config.getHeapDumpDirectory(), filename);
        }
        if (!file.exists()) {
            File gzFile = new File(config.getHeapDumpDirectory(), filename + ".gz");
            if (gzFile.exists()) return gzFile;
        }
        if (!file.exists()) {
            File tmpFile = new File(tmpDirectory(), filename);
            if (tmpFile.exists()) return tmpFile;
        }
        if (file.exists() && file.isFile()) return file;
        throw new FileNotFoundException("File not found: " + filename);
    }

    /**
     * 힙덤프 파일만 삭제 (분석 결과 보존). .gz 및 관련 인덱스 파일도 함께.
     */
    public void deleteFile(String filename) throws IOException {
        String safe = new File(filename).getName();
        File file = new File(config.getDumpFilesDirectory(), safe);
        File tmpFile = new File(tmpDirectory(), safe);

        logger.info("[Delete] Started: filename={}", safe);

        if (tmpFile.exists()) {
            long tmpSize = tmpFile.length();
            if (tmpFile.delete()) {
                logger.info("[Delete] Tmp file deleted: filename={}, size={}", safe, FormatUtils.formatBytes(tmpSize));
            } else {
                logger.warn("[Delete] Failed to delete tmp file: {}", safe);
            }
        }

        File gzFile = new File(config.getDumpFilesDirectory(), safe + ".gz");

        if (!file.exists() && !tmpFile.exists() && !gzFile.exists()) {
            logger.warn("[Delete] Heap dump file not found: {}", safe);
            throw new FileNotFoundException("File not found: " + safe);
        }

        if (file.exists()) {
            long fileSize = file.length();
            if (!file.delete()) {
                logger.error("[Delete] Failed to delete heap dump file: {}", safe);
                throw new IOException("Failed to delete: " + safe);
            }
            logger.info("[Delete] Heap dump file deleted: filename={}, size={}", safe, FormatUtils.formatBytes(fileSize));
        }

        if (gzFile.exists()) {
            long gzSize = gzFile.length();
            if (gzFile.delete()) {
                logger.info("[Delete] Compressed file deleted: filename={}, size={}", gzFile.getName(), FormatUtils.formatBytes(gzSize));
            } else {
                logger.warn("[Delete] Failed to delete compressed file: {}", gzFile.getName());
            }
        }

        // 관련 MAT 인덱스 파일 삭제 (예: heapdump.a2s.index, heapdump.threads 등)
        String baseName = stripExtension(safe);
        File parentDir = dumpFilesDirectory();
        File[] relatedFiles = parentDir.listFiles((dir, name) ->
                name.startsWith(baseName + ".") && !name.equals(safe));
        int relatedCount = 0;
        if (relatedFiles != null) {
            for (File related : relatedFiles) {
                if (related.isFile()) {
                    if (related.delete()) {
                        relatedCount++;
                        logger.debug("[Delete] Related file deleted: {}", related.getName());
                    } else {
                        logger.warn("[Delete] Failed to delete related file: {}", related.getName());
                    }
                }
            }
        }
        if (relatedCount > 0) {
            logger.info("[Delete] {} related index files deleted for '{}'", relatedCount, safe);
        }

        logger.info("[Delete] Completed: heap dump file deleted for '{}', analysis data preserved in data/", safe);
    }

    /**
     * 원본 덤프 파일과 .gz 파일이 동시에 존재하면 .gz 파일을 삭제한다.
     */
    public void cleanupDuplicateGzFiles(File[] files) {
        Set<String> originals = new HashSet<>();
        List<File> gzFiles = new ArrayList<>();

        for (File f : files) {
            String name = f.getName();
            if (name.toLowerCase().endsWith(".gz")) {
                gzFiles.add(f);
            } else {
                originals.add(name);
            }
        }

        for (File gz : gzFiles) {
            String originalName = gz.getName().substring(0, gz.getName().length() - 3);
            if (originals.contains(originalName)) {
                long gzSize = gz.length();
                if (gz.delete()) {
                    logger.info("[Cleanup] 중복 .gz 파일 삭제: {} ({})", gz.getName(), FormatUtils.formatBytes(gzSize));
                } else {
                    logger.warn("[Cleanup] 중복 .gz 파일 삭제 실패: {}", gz.getName());
                }
            }
        }
    }

    /**
     * 분석 완료된 덤프 파일을 gzip 으로 압축한다.
     * 압축 전 디스크 여유 공간이 원본 파일 크기 이상인지 점검.
     */
    public void compressDumpFile(File dumpFile) {
        if (dumpFile == null || !dumpFile.exists() || !dumpFile.isFile()) {
            return;
        }

        if (dumpFile.getName().toLowerCase().endsWith(".gz")) {
            return;
        }

        long fileSize = dumpFile.length();
        long usableSpace = dumpFile.getParentFile().getUsableSpace();

        if (usableSpace < fileSize) {
            logger.warn("[Compress] 디스크 여유 공간 부족으로 압축 건너뜀: 필요={}, 여유={}, 파일={}",
                    FormatUtils.formatBytes(fileSize), FormatUtils.formatBytes(usableSpace), dumpFile.getName());
            return;
        }

        File gzFile = new File(dumpFile.getAbsolutePath() + ".gz");

        if (gzFile.exists()) {
            logger.info("[Compress] 기존 .gz 파일 삭제 후 재압축: {}", gzFile.getName());
            gzFile.delete();
        }

        logger.info("[Compress] 덤프 파일 gzip 압축 시작: {} ({})", dumpFile.getName(), FormatUtils.formatBytes(fileSize));

        try (FileInputStream fis = new FileInputStream(dumpFile);
             FileOutputStream fos = new FileOutputStream(gzFile);
             GZIPOutputStream gzos = new GZIPOutputStream(fos, 8192)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, len);
            }
            gzos.finish();
        } catch (IOException e) {
            logger.error("[Compress] gzip 압축 실패: {}", dumpFile.getName(), e);
            if (gzFile.exists()) {
                gzFile.delete();
            }
            return;
        }

        if (!gzFile.exists() || gzFile.length() == 0) {
            logger.error("[Compress] .gz 파일 검증 실패: 파일 없거나 0바이트. 원본 보존: {}", dumpFile.getName());
            if (gzFile.exists()) gzFile.delete();
            return;
        }

        if (dumpFile.delete()) {
            logger.info("[Compress] 압축 완료: {} → {} ({}→{})",
                    dumpFile.getName(), gzFile.getName(),
                    FormatUtils.formatBytes(fileSize), FormatUtils.formatBytes(gzFile.length()));
        } else {
            logger.warn("[Compress] 원본 파일 삭제 실패: {}", dumpFile.getName());
        }
    }

    /**
     * gzip 압축된 덤프 파일을 원본으로 복원. 디스크 여유 공간 점검 (압축 파일 크기의 3배 이상).
     */
    public void decompressDumpFile(File gzFile, File destFile) throws IOException {
        long gzSize = gzFile.length();
        long usableSpace = gzFile.getParentFile().getUsableSpace();

        if (usableSpace < gzSize * 3) {
            throw new IOException("디스크 여유 공간 부족으로 압축 해제 불가: 여유=" +
                    FormatUtils.formatBytes(usableSpace) + ", 압축파일=" + FormatUtils.formatBytes(gzSize));
        }

        logger.info("[Decompress] gzip 압축 해제 시작: {} ({})", gzFile.getName(), FormatUtils.formatBytes(gzSize));

        try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(gzFile), 8192);
             FileOutputStream fos = new FileOutputStream(destFile)) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        } catch (IOException e) {
            if (destFile.exists()) {
                destFile.delete();
            }
            throw e;
        }

        if (gzFile.delete()) {
            logger.info("[Decompress] 압축 해제 완료: {} → {} ({}→{})",
                    gzFile.getName(), destFile.getName(),
                    FormatUtils.formatBytes(gzSize), FormatUtils.formatBytes(destFile.length()));
        } else {
            logger.warn("[Decompress] 압축 파일 삭제 실패: {}", gzFile.getName());
        }
    }
}

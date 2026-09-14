package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.GdbSharedLib;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 코어덤프 sysroot 라이브러리 번들 관리.
 *
 * 별도 서버에서 GDB 분석 시 공유 라이브러리 심볼은 분석 서버 로컬 파일시스템에서 해석되는데,
 * 같은 경로에 다른 빌드가 있으면 gdb(8.2) 가 build-id 검증 없이 잘못된 함수명을 조용히 출력한다
 * (실증: COREDUMP_SYMBOL_ACCURACY_VERIFICATION.md 케이스 C). 원본 서버의 라이브러리를
 * {coredump.directory}/sysroots/{coreFilename}/ 에 절대경로 미러 구조로 수집해 두면
 * buildGdbCommand() 가 -iex "set sysroot" 로 정확도를 복원한다.
 *
 * 번들은 exec 와 같은 "입력물" 시맨틱: 이력만 삭제 시 보존, 코어 파일 삭제 시 동반 삭제.
 * ⚠ CoreDumpAnalyzerService → 본 서비스 단방향 의존 유지(순환 금지) — 분석 결과가 필요한
 *   메서드는 호출자가 CoreDumpAnalysisResult 를 파라미터로 넘긴다.
 */
@Service
public class CoreDumpSysrootService {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpSysrootService.class);

    /** 원격 수집 대상 경로 화이트리스트 — 코어의 gdb 출력 유래(신뢰 불가)라 명령 주입/경로 조작 차단. */
    private static final Pattern SAFE_REMOTE_PATH = Pattern.compile("^/[A-Za-z0-9._+/-]+$");
    /** tar 해제 총량 상한(실제 복사 바이트 누적) — 압축 폭탄 차단. */
    static final long MAX_BUNDLE_BYTES = 1024L * 1024 * 1024; // 1GB
    /** tar 해제 엔트리 수 상한. */
    static final int MAX_BUNDLE_ENTRIES = 2000;

    private final HeapDumpConfig config;
    private final DumpTransferLogRepository transferLogRepository;
    private final TargetServerRepository serverRepository;
    private final RemoteDumpService remoteDumpService;

    public CoreDumpSysrootService(HeapDumpConfig config,
                                  DumpTransferLogRepository transferLogRepository,
                                  TargetServerRepository serverRepository,
                                  RemoteDumpService remoteDumpService) {
        this.config = config;
        this.transferLogRepository = transferLogRepository;
        this.serverRepository = serverRepository;
        this.remoteDumpService = remoteDumpService;
    }

    // ── 경로/상태 ─────────────────────────────────────────────────

    public File sysrootDir(String coreFilename) {
        return new File(new File(config.getCoreDumpDirectory(), "sysroots"), coreFilename);
    }

    /** 번들이 실파일을 1개 이상 담고 있으면 그 디렉토리, 아니면 null (분석 시 sysroot 적용 판정). */
    public File activeSysrootDir(String coreFilename) {
        File dir = sysrootDir(coreFilename);
        return countFiles(dir) > 0 ? dir : null;
    }

    public int countFiles(File dir) {
        if (!dir.isDirectory()) return 0;
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            logger.warn("[CoreDump] sysroot 파일 수 집계 실패: {} — {}", dir, e.getMessage());
            return 0;
        }
    }

    public long totalBytes(File dir) {
        if (!dir.isDirectory()) return 0L;
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    public record SysrootStatus(boolean present, int fileCount, long totalBytes,
                                boolean collectable, String originServerName) {}

    public SysrootStatus status(String coreFilename) {
        File dir = sysrootDir(coreFilename);
        int files = countFiles(dir);
        Optional<TargetServer> origin = findOriginServer(coreFilename);
        return new SysrootStatus(files > 0, files, totalBytes(dir),
                origin.isPresent(), origin.map(TargetServer::getName).orElse(null));
    }

    public void deleteBundle(String coreFilename) {
        deleteDirectoryQuietly(sysrootDir(coreFilename));
    }

    // ── 출처 서버 역추적 ──────────────────────────────────────────

    /**
     * 로컬 코어 파일명 → 출처 TargetServer. dump_transfer_log 에는 fileType 이 없어
     * 힙덤프 전송과 로컬 파일명이 우연히 같으면 오귀속될 수 있으므로, 로그 fileSize 와
     * 코어 실물 크기가 일치하는 최신 SUCCESS 로그만 채택한다.
     */
    public Optional<TargetServer> findOriginServer(String coreFilename) {
        File coreFile = new File(new File(config.getCoreDumpDirectory(), "dumpfiles"), coreFilename);
        if (!coreFile.isFile()) return Optional.empty();
        long size = coreFile.length();
        List<DumpTransferLog> logs = transferLogRepository
                .findByFilenameAndTransferStatusOrderByCompletedAtDesc(coreFilename, "SUCCESS");
        for (DumpTransferLog l : logs) {
            if (l.getFileSize() == null || l.getFileSize() != size) continue;
            Optional<TargetServer> server = serverRepository.findById(l.getServerId());
            if (server.isPresent()) return server;
        }
        return Optional.empty();
    }

    // ── 수집 대상 경로 산출 ───────────────────────────────────────

    /**
     * 분석 결과에서 수집할 원격 라이브러리 절대경로 목록.
     * info sharedlibrary 경로 ∪ info proc mappings 의 .so/ld-* 경로 — 코어가 참조하는
     * 전체를 담아야 한다(sysroot 설정 시 번들에 없는 라이브러리는 호스트 폴백이 사라져 No 가 됨).
     * 화이트리스트 불통과 경로는 조용히 제외(원격 tar 명령 주입 차단).
     */
    public List<String> listRequiredLibraryPaths(CoreDumpAnalysisResult result) {
        Set<String> paths = new TreeSet<>();
        if (result.getSharedLibraries() != null) {
            for (GdbSharedLib lib : result.getSharedLibraries()) {
                addIfSafe(paths, lib.getPath());
            }
        }
        if (result.getMemoryMappings() != null) {
            for (String line : result.getMemoryMappings()) {
                String[] tok = line.trim().split("\\s+");
                if (tok.length < 5) continue;
                String p = tok[tok.length - 1];
                String base = p.substring(p.lastIndexOf('/') + 1);
                if (p.contains(".so") || base.startsWith("ld-")) addIfSafe(paths, p);
            }
        }
        return new ArrayList<>(paths);
    }

    private void addIfSafe(Set<String> paths, String p) {
        if (p == null) return;
        p = p.trim();
        if (p.isEmpty() || p.contains("(deleted)") || p.startsWith("/SYSV")) return;
        if (!SAFE_REMOTE_PATH.matcher(p).matches() || p.contains("..")) {
            logger.warn("[CoreDump] sysroot 수집 제외 (경로 화이트리스트 불통과): {}", p);
            return;
        }
        paths.add(p);
    }

    // ── 원격 수집 ─────────────────────────────────────────────────

    public record CollectResult(String serverName, int requested, int collected,
                                List<String> missing, long totalBytes) {}

    /**
     * 출처 서버에서 라이브러리 번들을 tar 스트리밍으로 수집해 sysroot 디렉토리를 교체(replace)한다.
     * 실패 시 기존 번들은 보존(교체는 수신·해제가 끝난 뒤에만 수행).
     */
    public CollectResult collectFromOrigin(TargetServer server, String coreFilename,
                                           CoreDumpAnalysisResult result) throws Exception {
        List<String> paths = listRequiredLibraryPaths(result);
        if (paths.isEmpty())
            throw new IllegalStateException("수집할 라이브러리 경로가 없습니다 — 분석 결과에 공유 라이브러리 정보가 없습니다.");

        File archive = remoteDumpService.fetchRemoteLibBundle(server, paths);
        File dest = sysrootDir(coreFilename);
        try {
            // 임시 디렉토리에 해제 후 원자적 교체에 준하게 스왑 — 해제 실패 시 기존 번들 무손상
            File staging = new File(dest.getParentFile(), coreFilename + ".staging");
            deleteDirectoryQuietly(staging);
            extractBundleSafely(archive, staging);
            deleteDirectoryQuietly(dest);
            if (!staging.renameTo(dest)) {
                deleteDirectoryQuietly(staging);
                throw new IOException("번들 디렉토리 교체 실패: " + dest.getAbsolutePath());
            }
        } finally {
            if (archive.exists() && !archive.delete())
                logger.warn("[CoreDump] 번들 임시 파일 삭제 실패: {}", archive.getAbsolutePath());
        }

        List<String> missing = new ArrayList<>();
        for (String p : paths) {
            if (!new File(dest, p.substring(1)).isFile()) missing.add(p);
        }
        return new CollectResult(server.getName(), paths.size(), paths.size() - missing.size(),
                missing, totalBytes(dest));
    }

    // ── 업로드 (형식 무관 — 아카이브 + 개별 라이브러리 파일) ──────

    public record ExtractResult(int extracted, int skippedLinks, long totalBytes) {}

    /** 업로드 1건 — 저장된 임시 파일 + 브라우저가 보낸 원본 파일명. */
    public record UploadItem(File file, String originalName) {}

    /** 업로드 처리 요약. archives/singles = 아카이브 / 개별 파일로 처리된 업로드 건수. */
    public record UploadResult(int extracted, int skippedLinks, long totalBytes,
                               int archives, int singles) {}

    /** 업로드 내용물 종류 — **확장자가 아니라 매직 바이트**로 판정한다. */
    enum BundleFormat { GZIP, BZIP2, XZ, ZIP, TAR, RAW }

    private static final int MAGIC_SCAN = 265;            // tar 의 "ustar" 마커는 오프셋 257
    private static final int MAX_SOLIB_SEARCH_DIRS = 64;  // gdb 인자 길이 방어

    /**
     * 업로드된 파일들을 번들에 **병합**한다.
     *
     * 형식 제약 없음 — tar / tar.gz / tgz / tar.bz2 / tar.xz / zip 아카이브는 해제하고,
     * 아카이브가 아니면 **개별 라이브러리 파일**(.so 등)로 보고 번들 루트에 basename 으로 저장한다.
     * 개별 파일은 원래 절대경로를 몰라도 gdb 의 solib-search-path 로 탐색된다(solibSearchPath() 참조).
     * 압축된 단일 파일(`libfoo.so.gz`)도 해제 후 내용물이 tar 가 아니면 개별 파일로 처리한다.
     *
     * 병합이라 이전 업로드·원격 수집분은 유지된다(같은 경로는 덮어씀). 처음부터 다시 구성하려면
     * '번들 삭제' 후 업로드할 것. 검증·해제는 staging 에서 수행하므로 **실패 시 기존 번들은 무손상**.
     */
    public UploadResult ingestUploads(List<UploadItem> items, String coreFilename) throws IOException {
        if (items == null || items.isEmpty())
            throw new IOException("업로드된 파일이 없습니다.");

        File dest = sysrootDir(coreFilename);
        File staging = new File(dest.getParentFile(), coreFilename + ".staging");
        deleteDirectoryQuietly(staging);
        if (!staging.mkdirs())
            throw new IOException("임시 해제 디렉토리 생성 실패: " + staging.getAbsolutePath());

        int extracted = 0, skipped = 0, archives = 0, singles = 0;
        long bytes = 0L, bytesLeft = MAX_BUNDLE_BYTES;
        int entriesLeft = MAX_BUNDLE_ENTRIES;

        try {
            for (UploadItem item : items) {
                File work = item.file();
                String name = safeUploadName(item.originalName(), work.getName());
                BundleFormat fmt = detectFormat(work);
                File decompressed = null;
                try {
                    // 압축 단일 스트림(gz/bz2/xz)은 먼저 풀고 내용물을 재판정 — tar 이면 아카이브,
                    // 아니면 압축된 개별 라이브러리 파일이다.
                    if (fmt == BundleFormat.GZIP || fmt == BundleFormat.BZIP2 || fmt == BundleFormat.XZ) {
                        decompressed = decompressToTemp(work, fmt, bytesLeft);
                        work = decompressed;
                        name = stripCompressionSuffix(name);
                        fmt = detectFormat(work);
                    }
                    ExtractResult r;
                    if (fmt == BundleFormat.TAR || fmt == BundleFormat.ZIP) {
                        r = extractBundleSafely(work, staging, bytesLeft, entriesLeft);
                        archives++;
                    } else {
                        r = storeSingleFile(work, name, staging, bytesLeft);
                        singles++;
                    }
                    extracted += r.extracted();
                    skipped += r.skippedLinks();
                    bytes += r.totalBytes();
                    bytesLeft -= r.totalBytes();
                    entriesLeft -= (r.extracted() + r.skippedLinks());
                    if (bytesLeft <= 0 || entriesLeft <= 0) {
                        abortExtract(staging);
                        throw new IOException("업로드 총량 상한 초과 ("
                                + (MAX_BUNDLE_BYTES / 1024 / 1024) + "MB / " + MAX_BUNDLE_ENTRIES + "개)");
                    }
                } finally {
                    if (decompressed != null && decompressed.exists() && !decompressed.delete())
                        logger.warn("[CoreDump] 압축 해제 임시 파일 삭제 실패: {}", decompressed.getAbsolutePath());
                }
            }

            if (extracted == 0) {
                throw new IOException("업로드에서 사용할 파일을 찾지 못했습니다 — 아카이브(tar/tar.gz/tgz/tar.bz2/tar.xz/zip) "
                        + "또는 라이브러리 파일(.so)을 올려 주세요.");
            }
            mergeInto(staging, dest);
        } finally {
            deleteDirectoryQuietly(staging);
        }
        return new UploadResult(extracted, skipped, bytes, archives, singles);
    }

    /** staging 내용을 번들 디렉토리에 병합 (같은 상대경로는 덮어쓰기). */
    private static void mergeInto(File staging, File dest) throws IOException {
        Path sRoot = staging.toPath();
        Path dRoot = dest.toPath();
        Files.createDirectories(dRoot);
        List<Path> all;
        try (Stream<Path> s = Files.walk(sRoot)) {
            all = s.toList(); // pre-order — 디렉토리가 그 하위 파일보다 먼저 온다
        }
        for (Path p : all) {
            Path rel = sRoot.relativize(p);
            if (rel.toString().isEmpty()) continue;
            Path target = dRoot.resolve(rel);
            if (Files.isDirectory(p)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 매직 바이트 기반 형식 판정 (확장자 무관 — 확장자는 신뢰할 수 없다). */
    static BundleFormat detectFormat(File f) throws IOException {
        byte[] h = new byte[MAGIC_SCAN];
        int n;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            n = in.readNBytes(h, 0, h.length);
        }
        if (n >= 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b) return BundleFormat.GZIP;
        if (n >= 3 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h') return BundleFormat.BZIP2;
        if (n >= 6 && (h[0] & 0xff) == 0xfd && h[1] == '7' && h[2] == 'z'
                && h[3] == 'X' && h[4] == 'Z' && h[5] == 0) return BundleFormat.XZ;
        if (n >= 4 && h[0] == 'P' && h[1] == 'K'
                && (h[2] == 3 || h[2] == 5 || h[2] == 7)) return BundleFormat.ZIP;
        if (n >= 262 && h[257] == 'u' && h[258] == 's' && h[259] == 't'
                && h[260] == 'a' && h[261] == 'r') return BundleFormat.TAR;
        return BundleFormat.RAW;
    }

    /** 압축 스트림을 임시 파일로 해제. 상한을 넘기면 중단 — 압축 폭탄 방어. */
    private File decompressToTemp(File src, BundleFormat fmt, long maxBytes) throws IOException {
        File tmpRoot = new File(config.getCoreDumpDirectory(), "tmp");
        if (!tmpRoot.exists() && !tmpRoot.mkdirs())
            throw new IOException("임시 디렉토리 생성 실패: " + tmpRoot.getAbsolutePath());
        File out = File.createTempFile("cd-libs-", ".bin", tmpRoot);
        long total = 0L;
        byte[] buf = new byte[64 * 1024];
        try (InputStream raw = new BufferedInputStream(new FileInputStream(src));
             InputStream in = wrapDecompressor(raw, fmt);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("압축 해제 용량 초과 ("
                            + (maxBytes / 1024 / 1024) + "MB 상한) — 압축 폭탄일 수 있습니다.");
                }
                os.write(buf, 0, n);
            }
        } catch (IOException e) {
            if (out.exists() && !out.delete())
                logger.warn("[CoreDump] 임시 파일 삭제 실패: {}", out.getAbsolutePath());
            throw e;
        }
        return out;
    }

    private static InputStream wrapDecompressor(InputStream in, BundleFormat fmt) throws IOException {
        return switch (fmt) {
            case GZIP -> new GzipCompressorInputStream(in, true); // 다중 멤버(.gz 연결) 허용
            case BZIP2 -> new BZip2CompressorInputStream(in, true);
            case XZ -> new XZCompressorInputStream(in, true);
            default -> in;
        };
    }

    /** 개별 라이브러리 파일을 번들 루트에 basename 으로 저장. */
    private static ExtractResult storeSingleFile(File src, String name, File destDir, long maxBytes)
            throws IOException {
        if (src.length() > maxBytes) {
            abortExtract(destDir);
            throw new IOException("번들 용량 초과 (" + (maxBytes / 1024 / 1024) + "MB 상한): " + name);
        }
        Path destRoot = destDir.toPath().toAbsolutePath().normalize();
        Files.createDirectories(destRoot);
        Path target = destRoot.resolve(name).normalize();
        if (!target.startsWith(destRoot) || target.equals(destRoot)) {
            abortExtract(destDir);
            throw new IOException("유효하지 않은 파일명: " + name);
        }
        Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        return new ExtractResult(1, 0, src.length());
    }

    /** 업로드 파일명에서 경로 성분·제어문자를 제거해 basename 만 남긴다. */
    static String safeUploadName(String original, String fallback) {
        String n = (original == null || original.isBlank()) ? fallback : original;
        n = n.replace('\\', '/').replace("\0", "");
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.trim();
        if (n.isEmpty() || ".".equals(n) || "..".equals(n)) n = fallback;
        return n;
    }

    /** `libfoo.so.gz` → `libfoo.so` (압축 해제 후 개별 파일로 저장할 때의 이름). */
    static String stripCompressionSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".gz", ".bz2", ".xz", ".z"}) {
            if (lower.endsWith(ext) && name.length() > ext.length())
                return name.substring(0, name.length() - ext.length());
        }
        return name;
    }

    /**
     * 번들 안에서 **파일을 직접 담고 있는 디렉토리 목록**을 gdb `set solib-search-path` 값으로 만든다.
     *
     * sysroot 는 "sysroot + 코어에 기록된 절대경로"가 실존할 때만 맞아떨어지므로, 원경로를 모르는
     * 개별 업로드나 구조 없이 압축된 아카이브는 sysroot 만으로는 못 찾는다. solib-search-path 는
     * **basename 으로** 찾아 주고, 실측상 **분석 서버 로컬의 같은 경로 파일보다 우선**한다
     * (COREDUMP_SYMBOL_ACCURACY_VERIFICATION.md 케이스 F). ⚠ 단 solib-search-path 는 sysroot 와
     * **함께** 줘야 한다 — sysroot 없이 주면 gdb 가 원경로 파일을 먼저 찾아버려 무효다.
     *
     * @return ':' 로 이은 절대경로 목록. 담을 것이 없으면 null.
     */
    public String solibSearchPath(File bundleDir) {
        if (bundleDir == null || !bundleDir.isDirectory()) return null;
        Set<String> dirs = new TreeSet<>();
        try (Stream<Path> s = Files.walk(bundleDir.toPath())) {
            s.filter(Files::isRegularFile)
             .map(Path::getParent)
             .filter(Objects::nonNull)
             .map(p -> p.toAbsolutePath().normalize().toString())
             .forEach(dirs::add);
        } catch (IOException e) {
            logger.warn("[CoreDump] solib-search-path 산출 실패: {} — {}", bundleDir, e.getMessage());
            return null;
        }
        // ':' 가 든 경로는 gdb 목록 구분자와 충돌 — 제외(sysroot 미러 경로로는 여전히 탐색된다)
        List<String> usable = new ArrayList<>();
        for (String d : dirs) {
            if (d.indexOf(':') >= 0) {
                logger.warn("[CoreDump] solib-search-path 제외 (경로에 ':' 포함): {}", d);
                continue;
            }
            usable.add(d);
            if (usable.size() >= MAX_SOLIB_SEARCH_DIRS) break;
        }
        if (dirs.size() > usable.size()) {
            logger.warn("[CoreDump] solib-search-path 디렉토리 {}개 중 {}개만 사용 (상한 {})",
                    dirs.size(), usable.size(), MAX_SOLIB_SEARCH_DIRS);
        }
        return usable.isEmpty() ? null : String.join(":", usable);
    }

    /**
     * 아카이브 안전 해제 (tar / zip 공통) — 가드 4종:
     * ① zip-slip(절대경로·..·NUL 엔트리 거부 + normalize 확인) ② 링크/디바이스 엔트리 스킵
     * ③ 실제 복사 바이트 총량·엔트리 수 상한(초과 시 중단 + 부분물 삭제) ④ 일반 파일/디렉토리만 생성.
     */
    static ExtractResult extractBundleSafely(File archive, File destDir) throws IOException {
        return extractBundleSafely(archive, destDir, MAX_BUNDLE_BYTES, MAX_BUNDLE_ENTRIES);
    }

    // 상한 파라미터 버전 — CoreDumpSysrootExtractGuardTest 가 작은 상한으로 폭탄 가드를 검증
    static ExtractResult extractBundleSafely(File archive, File destDir,
                                             long maxBytes, int maxEntries) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs())
            throw new IOException("해제 디렉토리 생성 실패: " + destDir.getAbsolutePath());
        Path destRoot = destDir.toPath().toAbsolutePath().normalize();
        Ctx ctx = new Ctx(destDir, destRoot, maxBytes, maxEntries);

        if (detectFormat(archive) == BundleFormat.ZIP) {
            // ⚠ ZipArchiveInputStream(스트리밍)은 **local file header** 만 읽어 external attributes 가
            // 없으므로 isUnixSymlink() 가 항상 false 다. 중앙 디렉토리를 읽는 ZipFile 을 써야
            // 링크 엔트리를 실제로 식별할 수 있다(파일 생성은 어느 쪽이든 일반 파일이라 탈출 위험은 없지만,
            // 링크 본문이 라이브러리 이름의 쓰레기 파일로 남는 것을 막는다).
            try (org.apache.commons.compress.archivers.zip.ZipFile zf =
                         org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(archive).get()) {
                Enumeration<ZipArchiveEntry> en = zf.getEntries();
                while (en.hasMoreElements()) {
                    ZipArchiveEntry e = en.nextElement();
                    boolean special = e.isUnixSymlink();
                    if (e.isDirectory() || special) {
                        copyEntry(ctx, InputStream.nullInputStream(), e.getName(), e.isDirectory(), special);
                        continue;
                    }
                    try (InputStream in = zf.getInputStream(e)) {
                        copyEntry(ctx, in, e.getName(), false, false);
                    }
                }
            }
        } else {
            try (InputStream fis = new BufferedInputStream(new FileInputStream(archive));
                 InputStream decompressed = maybeDecompress(fis);
                 TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
                TarArchiveEntry e;
                while ((e = tar.getNextEntry()) != null) {
                    // ⚠ TarArchiveEntry.isFile() 은 symlink 에도 true 를 반환하므로(이름이 / 로 안 끝나면
                    // file 취급) 특수 타입을 먼저 명시적으로 배제해야 한다 — 생성하지 않고 보고만.
                    boolean special = e.isSymbolicLink() || e.isLink() || e.isCharacterDevice()
                            || e.isBlockDevice() || e.isFIFO() || !e.isFile();
                    copyEntry(ctx, tar, e.getName(), e.isDirectory(), special);
                }
            }
        }
        return new ExtractResult(ctx.extracted, ctx.skippedLinks, ctx.total);
    }

    /** 해제 진행 상태 + 상한. */
    private static final class Ctx {
        final File destDir; final Path destRoot; final long maxBytes; final int maxEntries;
        int extracted, skippedLinks, entries;
        long total;
        Ctx(File destDir, Path destRoot, long maxBytes, int maxEntries) {
            this.destDir = destDir; this.destRoot = destRoot;
            this.maxBytes = maxBytes; this.maxEntries = maxEntries;
        }
    }

    /** 엔트리 1건 검증 + 기록. 위반 시 부분 해제물을 지우고 예외. */
    private static void copyEntry(Ctx ctx, InputStream src, String name,
                                  boolean isDirectory, boolean isSpecial) throws IOException {
        if (++ctx.entries > ctx.maxEntries) {
            abortExtract(ctx.destDir);
            throw new IOException("번들 엔트리 수 초과 (" + ctx.maxEntries + "개 상한)");
        }
        if (name == null || name.isEmpty() || name.contains("\0")
                || name.startsWith("/") || name.contains(":\\") || hasDotDotSegment(name)) {
            abortExtract(ctx.destDir);
            throw new IOException("유효하지 않은 번들 엔트리 경로: " + name);
        }
        Path target = ctx.destRoot.resolve(name).normalize();
        if (!target.startsWith(ctx.destRoot)) {
            abortExtract(ctx.destDir);
            throw new IOException("번들 엔트리가 해제 디렉토리를 벗어납니다: " + name);
        }
        if (isDirectory) {
            Files.createDirectories(target);
            return;
        }
        if (isSpecial) { // symlink/hardlink/device/FIFO — 생성하지 않고 보고만
            ctx.skippedLinks++;
            return;
        }
        Files.createDirectories(target.getParent());
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target.toFile()))) {
            int n;
            while ((n = src.read(buf)) != -1) {
                ctx.total += n;
                if (ctx.total > ctx.maxBytes) {
                    out.close();
                    abortExtract(ctx.destDir);
                    throw new IOException("번들 용량 초과 (" + (ctx.maxBytes / 1024 / 1024) + "MB 상한)");
                }
                out.write(buf, 0, n);
            }
        }
        ctx.extracted++;
    }

    private static InputStream maybeDecompress(InputStream in) throws IOException {
        in.mark(8);
        byte[] h = new byte[6];
        int n = in.readNBytes(h, 0, h.length);
        in.reset();
        if (n >= 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b)
            return new GzipCompressorInputStream(in, true);
        if (n >= 3 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h')
            return new BZip2CompressorInputStream(in, true);
        if (n >= 6 && (h[0] & 0xff) == 0xfd && h[1] == '7' && h[2] == 'z'
                && h[3] == 'X' && h[4] == 'Z' && h[5] == 0)
            return new XZCompressorInputStream(in, true);
        return in;
    }

    private static boolean hasDotDotSegment(String name) {
        for (String seg : name.split("/")) {
            if ("..".equals(seg)) return true;
        }
        return false;
    }

    private static void abortExtract(File destDir) {
        try (Stream<Path> s = Files.walk(destDir.toPath())) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            logger.warn("[Sysroot] 해제 중단 후 부분 산출물 정리 실패 — 수동 확인 필요 ({}): {}", destDir, e.toString());
        }
    }

    // ── 분석 품질 경고 (assessAnalysisQuality 직후 훅) ─────────────

    /**
     * sysroot 관점의 신뢰도 경고를 기존 qualityWarnings 에 덧붙인다.
     * - 원격 출처 코어가 번들 없이 분석 서버 로컬 라이브러리로 심볼 해석(Syms Read=Yes)된 경우:
     *   버전 불일치 시 gdb 가 경고 없이 잘못된 함수명을 출력하므로(검증 케이스 C) 앱이 대신 경고.
     * - 번들 사용 중 번들에 없는 라이브러리(Syms Read=No)가 남은 경우: 수집 보완 안내.
     */
    public void appendSysrootQualityWarnings(CoreDumpAnalysisResult result, boolean remoteOrigin) {
        List<GdbSharedLib> libs = result.getSharedLibraries() != null
                ? result.getSharedLibraries() : Collections.emptyList();
        List<String> warnings = result.getQualityWarnings();
        if (warnings == null) {
            warnings = new ArrayList<>();
            result.setQualityWarnings(warnings);
        }

        if (result.isSysrootUsed()) {
            List<String> unresolved = libs.stream()
                    .filter(l -> l.getSymsRead() != null && l.getSymsRead().startsWith("No"))
                    .map(l -> {
                        String p = l.getPath() != null ? l.getPath() : "(미상)";
                        return p.substring(p.lastIndexOf('/') + 1);
                    })
                    .toList();
            if (!unresolved.isEmpty()) {
                String head = String.join(", ", unresolved.subList(0, Math.min(5, unresolved.size())));
                warnings.add("라이브러리 번들에 없는 라이브러리 " + unresolved.size() + "건 (" + head
                        + (unresolved.size() > 5 ? " 외" : "") + ") — sysroot 설정 시 분석 서버 로컬 폴백이 "
                        + "적용되지 않으므로, 원본 서버에서 해당 파일을 번들에 보완 수집하면 심볼 해석이 향상됩니다.");
            }
        } else if (remoteOrigin) {
            long localResolved = libs.stream()
                    .filter(l -> l.getSymsRead() != null && l.getSymsRead().startsWith("Yes"))
                    .count();
            if (localResolved > 0) {
                warnings.add("원격 서버에서 수집한 코어를 분석 서버 로컬 라이브러리로 심볼 해석했습니다 ("
                        + localResolved + "건) — 두 서버의 라이브러리 버전이 다르면 GDB 는 경고 없이 "
                        + "잘못된 함수명을 표시할 수 있습니다. 정확한 분석을 위해 '라이브러리 번들 수집' 후 "
                        + "재분석을 권장합니다.");
            }
        }
    }

    private void deleteDirectoryQuietly(File dir) {
        if (!dir.exists()) return;
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile)
             .forEach(f -> { if (!f.delete()) logger.warn("[CoreDump] sysroot 삭제 실패: {}", f.getAbsolutePath()); });
        } catch (IOException e) {
            logger.warn("[CoreDump] sysroot 디렉토리 삭제 실패: {} — {}", dir.getAbsolutePath(), e.getMessage());
        }
    }
}

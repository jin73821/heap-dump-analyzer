package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 라이브러리 번들 업로드의 **형식 무관 수용** 검증.
 *
 * tar.gz 뿐 아니라 zip·bz2·xz·순수 tar 아카이브와 개별 라이브러리 파일(.so), 압축된 개별 파일까지
 * 받아야 하고(판정은 확장자가 아니라 매직 바이트), 여러 파일을 한 번에 올려도 병합돼야 한다.
 */
class CoreDumpSysrootUploadTest {

    @TempDir
    Path tmp;

    private CoreDumpSysrootService service;
    private static final String CORE = "core.1234";

    @BeforeEach
    void setUp() {
        HeapDumpConfig config = mock(HeapDumpConfig.class);
        when(config.getCoreDumpDirectory()).thenReturn(tmp.toFile().getAbsolutePath());
        service = new CoreDumpSysrootService(config, mock(DumpTransferLogRepository.class),
                mock(TargetServerRepository.class), mock(RemoteDumpService.class));
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────

    private File file(String name, byte[] data) throws IOException {
        File f = tmp.resolve("upload-" + name).toFile();
        Files.write(f.toPath(), data);
        return f;
    }

    private File tarFile(String name, String entry, byte[] data) throws IOException {
        File f = tmp.resolve(name).toFile();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new FileOutputStream(f))) {
            TarArchiveEntry e = new TarArchiveEntry(entry);
            e.setSize(data.length);
            tar.putArchiveEntry(e);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        return f;
    }

    private File compress(File src, String name, String kind) throws IOException {
        File f = tmp.resolve(name).toFile();
        try (InputStream in = new FileInputStream(src);
             OutputStream raw = new FileOutputStream(f);
             OutputStream out = switch (kind) {
                 case "gz" -> new GzipCompressorOutputStream(raw);
                 case "bz2" -> new BZip2CompressorOutputStream(raw);
                 default -> new XZCompressorOutputStream(raw);
             }) {
            in.transferTo(out);
        }
        return f;
    }

    private CoreDumpSysrootService.UploadResult upload(Object... pairs) throws IOException {
        List<CoreDumpSysrootService.UploadItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            items.add(new CoreDumpSysrootService.UploadItem((File) pairs[i], (String) pairs[i + 1]));
        }
        return service.ingestUploads(items, CORE);
    }

    private File bundleFile(String rel) {
        return new File(service.sysrootDir(CORE), rel);
    }

    // ── 아카이브 형식 ─────────────────────────────────────────────

    @Test
    void zipArchiveIsAccepted() throws Exception {
        File zip = tmp.resolve("libs.zip").toFile();
        try (ZipArchiveOutputStream z = new ZipArchiveOutputStream(zip)) {
            ZipArchiveEntry e = new ZipArchiveEntry("lib64/libc.so.6");
            z.putArchiveEntry(e);
            z.write("zip-libc".getBytes(StandardCharsets.UTF_8));
            z.closeArchiveEntry();
        }
        CoreDumpSysrootService.UploadResult r = upload(zip, "libs.zip");
        assertEquals(1, r.archives());
        assertEquals(1, r.extracted());
        assertEquals("zip-libc", Files.readString(bundleFile("lib64/libc.so.6").toPath()));
    }

    @Test
    void plainTarAndBzip2AndXzAreAccepted() throws Exception {
        File plainTar = tarFile("a.tar", "lib64/a.so", "A".getBytes(StandardCharsets.UTF_8));
        File bz2 = compress(tarFile("b.tar", "lib64/b.so", "B".getBytes(StandardCharsets.UTF_8)),
                "b.tar.bz2", "bz2");
        File xz = compress(tarFile("c.tar", "lib64/c.so", "C".getBytes(StandardCharsets.UTF_8)),
                "c.tar.xz", "xz");
        CoreDumpSysrootService.UploadResult r = upload(plainTar, "a.tar", bz2, "b.tar.bz2", xz, "c.tar.xz");
        assertEquals(3, r.archives());
        assertEquals(3, r.extracted());
        assertTrue(bundleFile("lib64/a.so").isFile());
        assertTrue(bundleFile("lib64/b.so").isFile());
        assertTrue(bundleFile("lib64/c.so").isFile());
    }

    @Test
    void extensionIsIgnored_contentDecides() throws Exception {
        // 내용은 tar 인데 이름은 .so — 확장자로 판정하면 개별 파일로 잘못 처리된다
        File mislabeled = tarFile("weird.so", "lib64/real.so", "R".getBytes(StandardCharsets.UTF_8));
        CoreDumpSysrootService.UploadResult r = upload(mislabeled, "weird.so");
        assertEquals(1, r.archives());
        assertEquals(0, r.singles());
        assertTrue(bundleFile("lib64/real.so").isFile());
    }

    // ── 개별 라이브러리 파일 ──────────────────────────────────────

    @Test
    void singleLibraryFileIsStoredByBasename() throws Exception {
        File so = file("libclntsh.so.19.1", new byte[]{0x7f, 'E', 'L', 'F', 1, 2, 3, 4});
        CoreDumpSysrootService.UploadResult r = upload(so, "libclntsh.so.19.1");
        assertEquals(1, r.singles());
        assertEquals(0, r.archives());
        assertEquals(1, r.extracted());
        assertTrue(bundleFile("libclntsh.so.19.1").isFile(), "번들 루트에 basename 으로 저장");
    }

    @Test
    void uploadNameWithPathIsSanitized() throws Exception {
        File so = file("evil", new byte[]{1, 2, 3});
        upload(so, "../../../etc/passwd");
        assertTrue(bundleFile("passwd").isFile(), "경로 성분을 떼고 basename 만 사용");
        assertFalse(new File(tmp.toFile(), "etc/passwd").exists());
    }

    @Test
    void gzippedSingleLibraryIsDecompressedAndSuffixStripped() throws Exception {
        File so = file("libfoo.so", "ELF-CONTENT".getBytes(StandardCharsets.UTF_8));
        File gz = compress(so, "libfoo.so.gz", "gz");
        CoreDumpSysrootService.UploadResult r = upload(gz, "libfoo.so.gz");
        assertEquals(1, r.singles());
        assertEquals("ELF-CONTENT", Files.readString(bundleFile("libfoo.so").toPath()),
                ".gz 접미사를 떼고 해제된 내용으로 저장");
    }

    // ── 다중 업로드 · 병합 ────────────────────────────────────────

    @Test
    void mixedArchiveAndSingleFilesInOneUpload() throws Exception {
        File tar = compress(tarFile("m.tar", "lib64/libc.so.6", "C".getBytes(StandardCharsets.UTF_8)),
                "m.tar.gz", "gz");
        File so1 = file("libone.so", new byte[]{1});
        File so2 = file("libtwo.so", new byte[]{2});
        CoreDumpSysrootService.UploadResult r = upload(tar, "m.tar.gz", so1, "libone.so", so2, "libtwo.so");
        assertEquals(1, r.archives());
        assertEquals(2, r.singles());
        assertEquals(3, r.extracted());
        assertTrue(bundleFile("lib64/libc.so.6").isFile());
        assertTrue(bundleFile("libone.so").isFile());
        assertTrue(bundleFile("libtwo.so").isFile());
    }

    @Test
    void secondUploadMergesInsteadOfReplacing() throws Exception {
        upload(file("libkeep.so", new byte[]{9}), "libkeep.so");
        upload(file("libnew.so", new byte[]{8}), "libnew.so");
        assertTrue(bundleFile("libkeep.so").isFile(), "이전 업로드분이 유지돼야 한다");
        assertTrue(bundleFile("libnew.so").isFile());
    }

    @Test
    void sameNameUploadOverwrites() throws Exception {
        upload(file("libx.so", "OLD".getBytes(StandardCharsets.UTF_8)), "libx.so");
        upload(file("libx2.so", "NEW".getBytes(StandardCharsets.UTF_8)), "libx.so");
        assertEquals("NEW", Files.readString(bundleFile("libx.so").toPath()));
    }

    @Test
    void failedUploadLeavesExistingBundleIntact() throws Exception {
        upload(file("libgood.so", new byte[]{7}), "libgood.so");
        File evil = tarFile("evil.tar", "../escape.so", new byte[]{1});
        assertThrows(IOException.class, () -> upload(evil, "evil.tar"));
        assertTrue(bundleFile("libgood.so").isFile(), "실패해도 기존 번들은 무손상");
        assertFalse(new File(service.sysrootDir(CORE).getParentFile(), CORE + ".staging").exists(),
                "staging 은 정리돼야 한다");
    }

    @Test
    void emptyUploadListRejected() {
        assertThrows(IOException.class, () -> service.ingestUploads(List.of(), CORE));
    }

    // ── 형식 판정 · solib-search-path ─────────────────────────────

    @Test
    void detectFormatReadsMagicBytes() throws Exception {
        assertEquals(CoreDumpSysrootService.BundleFormat.TAR,
                CoreDumpSysrootService.detectFormat(tarFile("d.tar", "x", new byte[]{1})));
        assertEquals(CoreDumpSysrootService.BundleFormat.GZIP,
                CoreDumpSysrootService.detectFormat(compress(file("p", new byte[]{1}), "p.gz", "gz")));
        assertEquals(CoreDumpSysrootService.BundleFormat.BZIP2,
                CoreDumpSysrootService.detectFormat(compress(file("q", new byte[]{1}), "q.bz2", "bz2")));
        assertEquals(CoreDumpSysrootService.BundleFormat.XZ,
                CoreDumpSysrootService.detectFormat(compress(file("r", new byte[]{1}), "r.xz", "xz")));
        assertEquals(CoreDumpSysrootService.BundleFormat.RAW,
                CoreDumpSysrootService.detectFormat(file("s.so", new byte[]{0x7f, 'E', 'L', 'F'})));
    }

    @Test
    void solibSearchPathListsDirectoriesHoldingFiles() throws Exception {
        upload(file("libflat.so", new byte[]{1}), "libflat.so");
        File nested = tarFile("n.tar", "usr/lib64/libnested.so", new byte[]{2});
        upload(nested, "n.tar");

        String sp = service.solibSearchPath(service.sysrootDir(CORE));
        assertNotNull(sp);
        List<String> dirs = List.of(sp.split(":"));
        String root = service.sysrootDir(CORE).getAbsolutePath();
        assertTrue(dirs.contains(root), "개별 파일이 있는 루트가 포함돼야 한다");
        assertTrue(dirs.contains(root + "/usr/lib64"), "중첩 디렉토리도 포함: " + sp);
        assertEquals(root, dirs.get(0), "루트가 먼저 와야 한다(개별 업로드 우선)");
    }

    @Test
    void solibSearchPathNullWhenBundleAbsentOrEmpty() throws Exception {
        assertNull(service.solibSearchPath(service.sysrootDir(CORE)));
        assertNull(service.solibSearchPath(null));
        File emptyDir = tmp.resolve("empty").toFile();
        assertTrue(emptyDir.mkdirs());
        assertNull(service.solibSearchPath(emptyDir));
    }

    @Test
    void uploadNameHelpers() {
        assertEquals("libc.so.6", CoreDumpSysrootService.safeUploadName("/a/b/libc.so.6", "fb"));
        assertEquals("libc.so.6", CoreDumpSysrootService.safeUploadName("C:\\tmp\\libc.so.6", "fb"));
        assertEquals("fb", CoreDumpSysrootService.safeUploadName("..", "fb"));
        assertEquals("fb", CoreDumpSysrootService.safeUploadName("   ", "fb"));
        assertEquals("libfoo.so", CoreDumpSysrootService.stripCompressionSuffix("libfoo.so.gz"));
        assertEquals("libfoo.so", CoreDumpSysrootService.stripCompressionSuffix("libfoo.so.XZ"));
        assertEquals("libfoo.so", CoreDumpSysrootService.stripCompressionSuffix("libfoo.so"));
    }
}

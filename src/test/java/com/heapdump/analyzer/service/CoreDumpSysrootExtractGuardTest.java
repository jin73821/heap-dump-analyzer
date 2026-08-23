package com.heapdump.analyzer.service;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sysroot 번들 tar 안전 해제 가드 검증 — zip-slip / 링크 엔트리 / 용량·엔트리 폭탄.
 * 픽스처 tar 는 commons-compress 로 테스트 안에서 직접 생성한다.
 */
class CoreDumpSysrootExtractGuardTest {

    @TempDir
    Path tmp;

    private File tarOf(TarWriter writer) throws IOException {
        File f = tmp.resolve("bundle-" + System.nanoTime() + ".tar").toFile();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new FileOutputStream(f))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            writer.write(tar);
        }
        return f;
    }

    private static void addFile(TarArchiveOutputStream tar, String name, byte[] data) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(data.length);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private interface TarWriter { void write(TarArchiveOutputStream tar) throws IOException; }

    private long fileCount(File dir) throws IOException {
        if (!dir.exists()) return 0;
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void normalBundleExtractsWithMirroredPaths() throws Exception {
        byte[] libc = "libc-bytes".getBytes(StandardCharsets.UTF_8);
        File archive = tarOf(tar -> {
            addFile(tar, "lib64/libc.so.6", libc);
            addFile(tar, "sw/oracle/lib/libclntsh.so.19.1", "x".getBytes(StandardCharsets.UTF_8));
        });
        File dest = tmp.resolve("dest1").toFile();
        CoreDumpSysrootService.ExtractResult r = CoreDumpSysrootService.extractBundleSafely(archive, dest);
        assertEquals(2, r.extracted());
        assertEquals(0, r.skippedLinks());
        assertArrayEquals(libc, Files.readAllBytes(dest.toPath().resolve("lib64/libc.so.6")));
        assertTrue(new File(dest, "sw/oracle/lib/libclntsh.so.19.1").isFile());
    }

    @Test
    void gzipBundleIsDetectedByMagic() throws Exception {
        File plain = tarOf(tar -> addFile(tar, "lib64/x.so", "gz".getBytes(StandardCharsets.UTF_8)));
        File gz = tmp.resolve("bundle.tar.gz").toFile();
        try (InputStream in = new FileInputStream(plain);
             OutputStream out = new GzipCompressorOutputStream(new FileOutputStream(gz))) {
            in.transferTo(out);
        }
        File dest = tmp.resolve("dest2").toFile();
        CoreDumpSysrootService.ExtractResult r = CoreDumpSysrootService.extractBundleSafely(gz, dest);
        assertEquals(1, r.extracted());
        assertTrue(new File(dest, "lib64/x.so").isFile());
    }

    @Test
    void traversalEntryAbortsAndCleansPartial() throws Exception {
        File archive = tarOf(tar -> {
            addFile(tar, "lib64/good.so", "ok".getBytes(StandardCharsets.UTF_8));
            addFile(tar, "../evil.so", "bad".getBytes(StandardCharsets.UTF_8));
        });
        File dest = tmp.resolve("nested/dest3").toFile();
        IOException ex = assertThrows(IOException.class,
                () -> CoreDumpSysrootService.extractBundleSafely(archive, dest));
        assertTrue(ex.getMessage().contains("경로") || ex.getMessage().contains("벗어"),
                "경로 위반 메시지: " + ex.getMessage());
        assertFalse(new File(tmp.resolve("nested").toFile(), "evil.so").exists(),
                "탈출 파일이 생성되면 안 된다");
        assertEquals(0, fileCount(dest), "중단 시 부분 해제물은 정리돼야 한다");
    }

    @Test
    void linkEntriesAreSkippedNotCreated() throws Exception {
        File archive = tarOf(tar -> {
            TarArchiveEntry link = new TarArchiveEntry("lib64/libc.so.6", TarConstants.LF_SYMLINK);
            link.setLinkName("/etc/passwd");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            addFile(tar, "lib64/real.so", "r".getBytes(StandardCharsets.UTF_8));
        });
        File dest = tmp.resolve("dest4").toFile();
        CoreDumpSysrootService.ExtractResult r = CoreDumpSysrootService.extractBundleSafely(archive, dest);
        assertEquals(1, r.extracted());
        assertEquals(1, r.skippedLinks());
        assertFalse(Files.isSymbolicLink(dest.toPath().resolve("lib64/libc.so.6")),
                "symlink 엔트리는 생성되지 않아야 한다");
        assertFalse(new File(dest, "lib64/libc.so.6").exists());
    }

    @Test
    void zipTraversalEntryAborts() throws Exception {
        File zip = tmp.resolve("evil.zip").toFile();
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream z =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(zip)) {
            z.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("lib64/ok.so"));
            z.write("ok".getBytes(StandardCharsets.UTF_8));
            z.closeArchiveEntry();
            z.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("../evil.so"));
            z.write("bad".getBytes(StandardCharsets.UTF_8));
            z.closeArchiveEntry();
        }
        File dest = tmp.resolve("nested2/destz").toFile();
        assertThrows(IOException.class, () -> CoreDumpSysrootService.extractBundleSafely(zip, dest));
        assertFalse(new File(tmp.resolve("nested2").toFile(), "evil.so").exists());
        assertEquals(0, fileCount(dest));
    }

    @Test
    void zipSymlinkEntryIsSkipped() throws Exception {
        File zip = tmp.resolve("link.zip").toFile();
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream z =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(zip)) {
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry link =
                    new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("lib64/libc.so.6");
            link.setUnixMode(0120777); // symlink 비트
            z.putArchiveEntry(link);
            z.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
            z.closeArchiveEntry();
            z.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("lib64/real.so"));
            z.write("r".getBytes(StandardCharsets.UTF_8));
            z.closeArchiveEntry();
        }
        File dest = tmp.resolve("destz2").toFile();
        CoreDumpSysrootService.ExtractResult r = CoreDumpSysrootService.extractBundleSafely(zip, dest);
        assertEquals(1, r.extracted());
        assertEquals(1, r.skippedLinks());
        assertFalse(new File(dest, "lib64/libc.so.6").exists());
    }

    @Test
    void byteCapAbortsAndCleans() throws Exception {
        File archive = tarOf(tar -> addFile(tar, "lib64/big.so", new byte[100]));
        File dest = tmp.resolve("dest5").toFile();
        IOException ex = assertThrows(IOException.class,
                () -> CoreDumpSysrootService.extractBundleSafely(archive, dest, 10L, 100));
        assertTrue(ex.getMessage().contains("용량"), ex.getMessage());
        assertEquals(0, fileCount(dest));
    }

    @Test
    void entryCapAborts() throws Exception {
        File archive = tarOf(tar -> {
            for (int i = 0; i < 4; i++) addFile(tar, "lib64/f" + i + ".so", new byte[]{1});
        });
        File dest = tmp.resolve("dest6").toFile();
        IOException ex = assertThrows(IOException.class,
                () -> CoreDumpSysrootService.extractBundleSafely(archive, dest, 1024L, 3));
        assertTrue(ex.getMessage().contains("엔트리 수"), ex.getMessage());
        assertEquals(0, fileCount(dest));
    }
}

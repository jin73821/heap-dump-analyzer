package com.heapdump.analyzer.util;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지식 문서 묶음(.zip) 읽기/쓰기. 내보내기가 zip 을 내므로 가져오기도 zip 을 받아야 왕복이 성립한다.
 *
 * <p>가드는 코어덤프 번들 해제와 같은 태도다 — 확장자가 아니라 <b>매직바이트</b>로 판정하고,
 * 엔트리명은 basename 으로 평탄화하며, 엔트리 수·해제 용량 상한으로 zip bomb 을 막는다.
 *
 * <p>⚠ 스트리밍 {@code ZipArchiveInputStream} 이 아니라 {@link ZipFile} 을 쓴다 —
 * 스트리밍 판에서는 심볼릭 링크 판별이 불가능하다(함정 37). {@code ZipFile} 은 seekable 소스가
 * 필요하므로 호출부가 업로드를 <b>임시 파일로 먼저 내려야</b> 한다.
 */
public final class RagZipCodec {

    private RagZipCodec() {}

    public static final int MAX_ENTRIES = 200;
    public static final long MAX_UNCOMPRESSED = 20L * 1024 * 1024;

    /** ZIP 매직바이트({@code PK\03\04}). 확장자를 믿지 않는다. */
    public static boolean looksLikeZip(byte[] head) {
        return head != null && head.length >= 4
                && head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04;
    }

    /**
     * zip 안의 {@code *.md} 만 뽑는다.
     *
     * @return 파일명(basename) → 본문. 같은 이름이 여러 번 나오면 마지막이 이긴다.
     */
    public static Map<String, String> extractMarkdown(File zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        long totalBytes = 0;
        int count = 0;

        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (e.isUnixSymlink()) continue;              // ZipFile 이라 판별 가능 (함정 37)

                String name = baseName(e.getName());
                if (name.isEmpty() || name.startsWith(".")) continue;
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.endsWith(".md") && !lower.endsWith(".markdown")) continue;

                if (++count > MAX_ENTRIES) {
                    throw new IllegalArgumentException("zip 엔트리가 너무 많습니다 (상한 " + MAX_ENTRIES + "개)");
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream is = zf.getInputStream(e)) {
                    byte[] chunk = new byte[16 * 1024];
                    int n;
                    while ((n = is.read(chunk)) != -1) {
                        totalBytes += n;
                        if (totalBytes > MAX_UNCOMPRESSED) {
                            throw new IllegalArgumentException("zip 해제 용량 초과 ("
                                    + (MAX_UNCOMPRESSED / 1024 / 1024) + "MB 상한) — 압축 폭탄일 수 있습니다");
                        }
                        buf.write(chunk, 0, n);
                    }
                }
                out.put(name, buf.toString(StandardCharsets.UTF_8));
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("zip 안에 .md 파일이 없습니다");
        return out;
    }

    /** 문서 묶음을 zip 바이트로. 엔트리명은 {@code {originId}.md}. */
    public static byte[] zipMarkdown(Map<String, String> nameToContent) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(bos)) {
            zos.setEncoding("UTF-8");
            zos.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.ALWAYS);
            for (Map.Entry<String, String> e : nameToContent.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(e.getKey());
                byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
                entry.setSize(data.length);
                zos.putArchiveEntry(entry);
                zos.write(data);
                zos.closeArchiveEntry();
            }
            zos.finish();
        }
        return bos.toByteArray();
    }

    /** 경로를 떼고 파일명만. {@code ../} 나 절대경로가 섞여도 여기서 무력화된다. */
    static String baseName(String entryName) {
        if (entryName == null) return "";
        String n = entryName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        String base = slash >= 0 ? n.substring(slash + 1) : n;
        return base.replace("..", "").trim();
    }
}

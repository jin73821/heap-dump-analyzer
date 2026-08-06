package com.heapdump.analyzer.parser;

import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.MatParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAT Leak Suspects ZIP 파싱 회귀 테스트.
 *
 * <p>배경(2026-08-06): MAT index.html 은 본문 위에 파이 차트 + 이미지맵을 넣는데,
 * {@code <area alt="Slice (a)  Problem Suspect 1: ...">} 의 alt 에서도 섹션 정규식이 매칭돼
 * <b>차트 조각이 가짜 suspect 로 등록</b>됐다. 그 가짜 항목이 5개 하드코딩 상한을 잠식해
 * 뒤쪽 진짜 suspect 가 잘려나갔다(운영 실측: MAT 6건 → 화면 5건 중 진짜는 3건).
 */
class MatReportParserSuspectsTest {

    private static final String BASE = "testdump";

    /** MAT 실제 출력 형식 그대로 index.html 을 합성한다 (suspect N 건 + 슬라이스 N+1 개). */
    private String buildMatIndexHtml(int suspectCount) {
        StringBuilder slices = new StringBuilder();
        for (int i = 0; i < suspectCount; i++) {
            slices.append("<area  shape=\"poly\" coords=\"690,").append(145 + 22 * i)
                  .append(",836,").append(145 + 22 * i).append(",836,").append(160 + 22 * i)
                  .append(",690,").append(160 + 22 * i).append("\" alt=\"Slice (")
                  .append((char) ('a' + i)).append(")  Problem Suspect ").append(i + 1)
                  .append(": Shallow Size: 0 B     Retained Size: ").append(200 - i * 10)
                  .append(" MB\" href=\"mat://object/0x").append(String.format("%08x", i))
                  .append("\" title=\"(").append((char) ('a' + i)).append(")  Problem Suspect ")
                  .append(i + 1).append("\">");
        }
        slices.append("<area  shape=\"poly\" coords=\"690,400,836,400,836,415,690,415\" ")
              .append("alt=\"Slice (z)  Remainder: Retained Size: 66.7 MB\"  title=\"(z)  Remainder\">");

        StringBuilder bodies = new StringBuilder();
        for (int i = 0; i < suspectCount; i++) {
            bodies.append("<h3 id=\"i").append(17 + i).append("\"><a href=\"#\" onclick=\"hide(this, 'exp")
                  .append(17 + i).append("'); return false;\" title=\"hide / unhide\">")
                  .append("<img src=\"img/opened.gif\" alt=\"\"></a> ")
                  .append("<img src=\"img/error.gif\" alt=\"Status: error.\"> Problem Suspect ").append(i + 1)
                  .append("</h3><div id=\"exp").append(17 + i).append("\"><div class=\"important\"><div>")
                  .append("<p>One instance of <strong><q>com.tmax.tibero.jdbc.driver.TbConnection")
                  .append(i).append("</q></strong> loaded by <strong><q>jeus.server.classloader.RootClassLoader")
                  .append("</q></strong> @ 0x80018790 occupies <strong>2").append(30 - i)
                  .append(",000,000 (1").append(2 - i % 3).append(".24%)</strong> bytes. ")
                  .append("The memory is accumulated in one instance of <strong><q>java.util.LinkedList</q></strong>, ")
                  .append("loaded by <strong><q>&lt;system class loader&gt;</q></strong>, which occupies ")
                  .append("<strong>2").append(30 - i).append(",000,000 (1").append(2 - i % 3).append(".24%)</strong> bytes.")
                  .append("<p><strong>Keywords</strong></p><ul style=\"list-style-type:none;\">")
                  .append("<li>com.tmax.tibero.jdbc.driver.TbConnection").append(i).append("</li>")
                  .append("<li>jeus.server.classloader.RootClassLoader</li></ul></div>")
                  .append("<div><a href=\"pages/").append(19 + i).append(".html\">Details &raquo;</a></div></div></div>");
        }

        return "<html><body><div id=\"content\"><h1>Leak Suspects</h1><h5>Overview</h5>"
             + "<div id=\"exp16\"><map name='chart16map'>" + slices + "</map>"
             + "<img src=\"chart16.png\" usemap='#chart16map' alt=\"Pie chart\"></div>"
             + bodies + "</div>"
             + "<div id=\"footer\" class=\"toc\"><a href=\"toc.html\">Table Of Contents</a></div>"
             + "</body></html>";
    }

    private void writeSuspectsZip(Path dir, String html) throws Exception {
        File zip = dir.resolve(BASE + "_Leak_Suspects.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("index.html"));
            zos.write(html.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }

    private List<LeakSuspect> parse(Path dir) {
        MatParseResult result = new MatParseResult();
        new MatReportParser().reparseSuspects(dir.toString(), BASE, result);
        return result.getLeakSuspects();
    }

    @Test
    void chartImageMapIsNotParsedAsSuspect(@TempDir Path dir) throws Exception {
        writeSuspectsZip(dir, buildMatIndexHtml(6));
        List<LeakSuspect> suspects = parse(dir);

        assertEquals(6, suspects.size(), "MAT 가 낸 6건이 그대로 나와야 한다 (차트 오탐 0건)");
        for (LeakSuspect s : suspects) {
            assertFalse(s.getDescription().contains("<area"),
                    "차트 이미지맵 조각이 본문에 남으면 안 된다: " + s.getDescription());
            assertFalse(s.getDescription().contains("Slice ("),
                    "차트 슬라이스 텍스트가 본문에 남으면 안 된다: " + s.getDescription());
            assertTrue(s.getDescription().contains("instance of"),
                    "진짜 suspect 본문이어야 한다: " + s.getDescription());
        }
    }

    /** suspect 순서가 MAT 원본과 일치해야 한다 (Suspect #1 = Problem Suspect 1). */
    @Test
    void suspectOrderMatchesMatReport(@TempDir Path dir) throws Exception {
        writeSuspectsZip(dir, buildMatIndexHtml(6));
        List<LeakSuspect> suspects = parse(dir);

        for (int i = 0; i < suspects.size(); i++) {
            assertEquals("Suspect #" + (i + 1), suspects.get(i).getTitle());
            assertTrue(suspects.get(i).getDescription().contains("TbConnection" + i),
                    "i=" + i + " → " + suspects.get(i).getDescription());
        }
    }

    /** 예전 상한(5)에 걸려 잘리던 구간 — 6건 이상도 전부 나와야 한다. */
    @Test
    void moreThanFiveSuspectsAreNotTruncated(@TempDir Path dir) throws Exception {
        writeSuspectsZip(dir, buildMatIndexHtml(12));
        assertEquals(12, parse(dir).size());
    }

    /** 상한 20 은 유지 — 이상 덤프에서 무한정 늘어나지 않는다. */
    @Test
    void suspectsAreCappedAtTwenty(@TempDir Path dir) throws Exception {
        writeSuspectsZip(dir, buildMatIndexHtml(25));
        assertEquals(20, parse(dir).size());
    }

    /** Keywords 추출은 종전대로 동작해야 한다 (섹션 경계가 바뀌지 않았음을 확인). */
    @Test
    void keywordsAreStillExtractedPerSuspect(@TempDir Path dir) throws Exception {
        writeSuspectsZip(dir, buildMatIndexHtml(3));
        List<LeakSuspect> suspects = parse(dir);

        assertEquals(3, suspects.size());
        for (int i = 0; i < suspects.size(); i++) {
            List<String> kws = suspects.get(i).getKeywords();
            assertNotNull(kws);
            assertTrue(kws.contains("com.tmax.tibero.jdbc.driver.TbConnection" + i), String.valueOf(kws));
            assertTrue(kws.contains("jeus.server.classloader.RootClassLoader"), String.valueOf(kws));
        }
    }
}

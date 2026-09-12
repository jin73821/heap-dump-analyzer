package com.heapdump.analyzer.parser;

import com.heapdump.analyzer.model.HistogramEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAT {@code histogram} 단독 쿼리 {@code _Query.zip} 파싱 (2026-09-13, Class Histogram 500행 확장).
 *
 * <p>픽스처는 실측 산출물(oom-test, {@code -derived_data_column=_default_=APPROXIMATE -sort_column=#3})의
 * index.html 구조를 그대로 옮긴 것이다 — 클래스 셀에 {@code <img>} + {@code <a>클래스</a><br><a>All objects</a>},
 * Retained 는 {@code &gt;= N} 근사값, 푸터 {@code Total: 500 of 619 entries; 119 more}.
 * 클래스 수가 limit 미만이면 푸터가 {@code Total: N entries}(of 없음)라 총계가 0 으로 남아야 하고,
 * 호출자({@code enrichWideHistogram})는 그때 Overview 총계를 유지한다.
 */
class MatReportParserHistogramQueryTest {

    @TempDir
    Path dir;

    private static final String HEAD = "<html><body><table class=\"result\"><thead>"
            + "<tr><th>Class Name</th><th>Objects</th><th>Shallow Heap</th><th>Retained Heap</th></tr></thead><tbody>";

    private static String row(String cls, String objects, String shallow, String retained) {
        return "<tr><td><img src=\"icons/i0.gif\" alt=\"\"><a href=\"mat://object/0xffdff9f0\">" + cls + "</a><br>"
                + "<a href=\"mat://query/oql+%22SELECT+*+FROM+27136%22\">All objects</a></td>"
                + "<td align=\"right\">" + objects + "</td><td align=\"right\">" + shallow + "</td>"
                + "<td align=\"right\">" + retained + "</td></tr>";
    }

    private File zipWithIndex(String html) throws IOException {
        File zip = dir.resolve("oom-test_Query.zip").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            zos.putNextEntry(new ZipEntry("styles.css"));
            zos.write("body{}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            if (html != null) {
                zos.putNextEntry(new ZipEntry("index.html"));
                zos.write(html.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    @DisplayName("쿼리 ZIP index.html — 4열 파싱 + 'All objects' 제거 + Total: N of M 총계")
    void parsesQueryZipRowsAndTotal() throws IOException {
        String html = HEAD
                + row("byte[]", "8,481", "100,761,152", "&gt;= 100,761,152")
                + row("java.lang.String", "12,345", "296,280", "&gt;= 1,234,567")
                + row("com.example.Foo$Bar", "7", "168", "&gt;= 168")
                + "<tr><td colspan=\"4\">Total: 500 of 25,086 entries; 24,586 more</td></tr>"
                + "</tbody></table></body></html>";

        MatReportParser.HistogramParse hp = new MatReportParser().parseHistogramQueryZip(zipWithIndex(html));

        List<HistogramEntry> e = hp.entries();
        assertEquals(3, e.size(), "데이터 행 3건(헤더·Total 행 제외)");
        assertEquals("byte[]", e.get(0).getClassName(), "'All objects' 링크 텍스트가 이름에 붙으면 안 된다");
        assertEquals(8481L, e.get(0).getObjectCount());
        assertEquals(100761152L, e.get(0).getShallowHeap());
        assertEquals(100761152L, e.get(0).getRetainedHeap());
        // 파서는 엔티티를 디코딩하지 않는다(Overview 경로와 동일) — HistogramEntry.getRetainedHeapHuman 이 ≥ 로 바꾼다
        String disp = e.get(0).getRetainedHeapDisplay();
        assertTrue(disp.contains("&gt;=") || disp.contains(">="), "근사 표기(>=) 보존: " + disp);
        assertTrue(e.get(0).getRetainedHeapHuman().startsWith("≥"), "근사값은 ≥ 접두: " + e.get(0).getRetainedHeapHuman());
        assertEquals("com.example.Foo$Bar", e.get(2).getClassName());
        assertEquals(25086, hp.totalClasses());
    }

    @Test
    @DisplayName("클래스 수가 limit 미만이면 푸터에 'of' 가 없어 총계 0 — 호출자가 Overview 총계를 유지한다")
    void totalStaysZeroWhenFooterHasNoOfClause() throws IOException {
        String html = HEAD
                + row("byte[]", "1", "16", "&gt;= 16")
                + "<tr><td colspan=\"4\">Total: 1 entries</td></tr>"
                + "</tbody></table></body></html>";

        MatReportParser.HistogramParse hp = new MatReportParser().parseHistogramQueryZip(zipWithIndex(html));

        assertEquals(1, hp.entries().size());
        assertEquals(0, hp.totalClasses());
    }

    @Test
    @DisplayName("index.html 이 없는 ZIP → 빈 결과 (예외 없음, 호출자가 Overview 행 유지)")
    void missingIndexYieldsEmpty() throws IOException {
        MatReportParser.HistogramParse hp = new MatReportParser().parseHistogramQueryZip(zipWithIndex(null));
        assertTrue(hp.entries().isEmpty());
        assertEquals(0, hp.totalClasses());
    }
}

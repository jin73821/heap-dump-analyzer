package com.heapdump.analyzer.parser;

import com.heapdump.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Eclipse MAT CLI 분석 결과 파서
 *
 * MAT CLI 실행 후 생성된 ZIP 파일 내부의 HTML/XML 리포트를 파싱하여
 * HeapAnalysisResult 객체로 변환합니다.
 *
 * MAT 출력 파일 구조:
 *   {dumpName}_suspects.zip  → Leak Suspects 리포트
 *   {dumpName}_overview.zip  → System Overview 리포트
 *   {dumpName}_top_components.zip → Top Components 리포트
 */
@Component
public class MatReportParser {

    private static final Logger logger = LoggerFactory.getLogger(MatReportParser.class);

    // ─── 정규식 패턴 ────────────────────────────────────────────────────────────
    // Overview HTML에서 힙 크기 추출: "Number format: 1,234,567 bytes"
    private static final Pattern HEAP_SIZE_PATTERN =
            Pattern.compile("(\\d[\\d,]+)\\s*bytes", Pattern.CASE_INSENSITIVE);

    // "Used heap size: 123,456,789" 또는 "Heap Size: 123,456,789"
    private static final Pattern USED_HEAP_PATTERN =
            Pattern.compile("(?:used|Used)\\s+heap\\s+size[^\\d]*(\\d[\\d,]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FREE_HEAP_PATTERN =
            Pattern.compile("(?:free|Free)\\s+heap[^\\d]*(\\d[\\d,]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TOTAL_HEAP_PATTERN =
            Pattern.compile("(?:total|Total|Heap\\s+Size)[^\\d]*(\\d[\\d,]+)\\s*bytes", Pattern.CASE_INSENSITIVE);

    // Classes / Objects 수
    private static final Pattern CLASSES_PATTERN =
            Pattern.compile("(\\d[\\d,]+)\\s+classes", Pattern.CASE_INSENSITIVE);

    private static final Pattern OBJECTS_PATTERN =
            Pattern.compile("(\\d[\\d,]+)\\s+objects", Pattern.CASE_INSENSITIVE);

    // Top Components: 클래스명과 크기
    private static final Pattern CLASS_SIZE_PATTERN =
            Pattern.compile("<td[^>]*>([\\w.$\\[\\]]+)</td>\\s*<td[^>]*>([\\d,]+)</td>\\s*<td[^>]*>([\\d,]+)</td>");

    // ─── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * MAT가 생성한 ZIP 리포트들을 파싱하여 분석 결과를 반환합니다.
     *
     * @param heapDumpDir   힙 덤프 디렉토리 (/opt/heapdumps)
     * @param dumpBaseName  덤프 파일 기본명 (확장자 제외, 예: "app_20240115")
     * @return 파싱된 분석 결과
     */
    public MatParseResult parse(String heapDumpDir, String dumpBaseName) {
        MatParseResult result = new MatParseResult();

        // 1) Overview ZIP 파싱
        File overviewZip = findZip(heapDumpDir, dumpBaseName, "overview");
        if (overviewZip != null) {
            parseOverviewZip(overviewZip, result);
        } else {
            logger.warn("overview ZIP not found for: {}", dumpBaseName);
        }

        // 2) Top Components ZIP 파싱
        File topZip = findZip(heapDumpDir, dumpBaseName, "top_components");
        if (topZip != null) {
            parseTopComponentsZip(topZip, result);
        } else {
            logger.warn("top_components ZIP not found for: {}", dumpBaseName);
        }

        // 3) Suspects ZIP 파싱
        File suspectsZip = findZip(heapDumpDir, dumpBaseName, "suspects");
        if (suspectsZip != null) {
            parseSuspectsZip(suspectsZip, result);
        } else {
            logger.warn("suspects ZIP not found for: {}", dumpBaseName);
        }

        return result;
    }

    // ─── ZIP 탐색 ─────────────────────────────────────────────────────────────

    /**
     * MAT가 생성하는 ZIP 파일을 탐색합니다.
     *
     * MAT CLI 실제 출력 파일명 예시:
     *   tomcat_heapdump_System_Overview.zip
     *   tomcat_heapdump_Top_Components.zip
     *   tomcat_heapdump_Leak_Suspects.zip
     *
     * @param reportType  "overview" | "top_components" | "suspects"
     */
    private File findZip(String dir, String base, String reportType) {
        File directory = new File(dir);
        if (!directory.exists()) return null;

        // reportType → MAT 실제 파일명에 포함되는 키워드 목록 (대소문자 무관 비교)
        List<String> keywords;
        switch (reportType) {
            case "overview":
                keywords = Arrays.asList("system_overview", "overview");
                break;
            case "top_components":
                keywords = Arrays.asList("top_components", "top_component");
                break;
            case "suspects":
                keywords = Arrays.asList("leak_suspects", "suspects");
                break;
            default:
                keywords = Collections.singletonList(reportType);
        }

        // 1) baseName 포함 + keyword 포함 파일 우선 탐색 (대소문자 무시)
        String baseLower = stripExt(base).toLowerCase();
        File[] allZips = directory.listFiles((d, n) -> n.toLowerCase().endsWith(".zip"));
        if (allZips != null) {
            for (File f : allZips) {
                String nameLower = f.getName().toLowerCase();
                boolean baseMatch    = nameLower.contains(baseLower);
                boolean keywordMatch = keywords.stream().anyMatch(nameLower::contains);
                if (baseMatch && keywordMatch) {
                    logger.info("Found {} ZIP: {}", reportType, f.getAbsolutePath());
                    return f;
                }
            }

            // 2) baseName 없이 keyword만으로 폴백 탐색
            for (File f : allZips) {
                String nameLower = f.getName().toLowerCase();
                boolean keywordMatch = keywords.stream().anyMatch(nameLower::contains);
                if (keywordMatch) {
                    logger.info("Fallback-matched {} ZIP: {}", reportType, f.getAbsolutePath());
                    return f;
                }
            }
        }

        logger.warn("No ZIP found for reportType='{}', base='{}'", reportType, base);
        return null;
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ─── Overview 파싱 ────────────────────────────────────────────────────────

    private void parseOverviewZip(File zip, MatParseResult result) {
        String html = extractHtmlFromZip(zip, "overview");
        if (html == null || html.isEmpty()) return;

        // Total heap size
        Matcher totalMatcher = TOTAL_HEAP_PATTERN.matcher(html);
        if (totalMatcher.find()) {
            result.setTotalHeapSize(parseLong(totalMatcher.group(1)));
        }

        // Fallback: 가장 큰 바이트 수치를 total로 추정
        if (result.getTotalHeapSize() == 0) {
            long max = 0;
            Matcher m = HEAP_SIZE_PATTERN.matcher(html);
            while (m.find()) {
                long v = parseLong(m.group(1));
                if (v > max) max = v;
            }
            result.setTotalHeapSize(max);
        }

        // Used heap
        Matcher usedM = USED_HEAP_PATTERN.matcher(html);
        if (usedM.find()) {
            result.setUsedHeapSize(parseLong(usedM.group(1)));
        }

        // Free heap
        Matcher freeM = FREE_HEAP_PATTERN.matcher(html);
        if (freeM.find()) {
            result.setFreeHeapSize(parseLong(freeM.group(1)));
        }

        // Classes count
        Matcher classM = CLASSES_PATTERN.matcher(html);
        if (classM.find()) {
            result.setTotalClasses((int) parseLong(classM.group(1)));
        }

        // Objects count
        Matcher objM = OBJECTS_PATTERN.matcher(html);
        if (objM.find()) {
            result.setTotalObjects(parseLong(objM.group(1)));
        }

        // Used / Free 보정
        if (result.getUsedHeapSize() == 0 && result.getTotalHeapSize() > 0) {
            result.setUsedHeapSize((long) (result.getTotalHeapSize() * 0.75));
        }
        if (result.getFreeHeapSize() == 0 && result.getTotalHeapSize() > 0) {
            result.setFreeHeapSize(result.getTotalHeapSize() - result.getUsedHeapSize());
        }

        result.setOverviewHtml(sanitizeHtml(html));
        logger.info("Parsed overview: total={}, used={}, free={}",
                result.getTotalHeapSize(), result.getUsedHeapSize(), result.getFreeHeapSize());
    }

    // ─── Top Components 파싱 ──────────────────────────────────────────────────

    private void parseTopComponentsZip(File zip, MatParseResult result) {
        String html = extractHtmlFromZip(zip, "top_components");
        if (html == null || html.isEmpty()) return;

        List<MemoryObject> objects = new ArrayList<>();

        // <tr> 행 단위로 파싱
        Pattern rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern cellPattern = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern tagPattern  = Pattern.compile("<[^>]+>");

        Matcher rowM = rowPattern.matcher(html);
        while (rowM.find() && objects.size() < 20) {
            String row = rowM.group(1);
            List<String> cells = new ArrayList<>();
            Matcher cellM = cellPattern.matcher(row);
            while (cellM.find()) {
                String cell = tagPattern.matcher(cellM.group(1)).replaceAll("").trim();
                cells.add(cell);
            }
            if (cells.size() >= 3) {
                try {
                    String className  = cells.get(0).trim();
                    long   objCount   = parseLong(cells.get(1));
                    long   totalBytes = parseLong(cells.get(2));

                    if (className.isEmpty() || className.equalsIgnoreCase("Class Name")
                            || className.equalsIgnoreCase("Class")) continue;
                    if (totalBytes == 0) continue;

                    double pct = result.getTotalHeapSize() > 0
                            ? (totalBytes * 100.0) / result.getTotalHeapSize() : 0.0;

                    objects.add(new MemoryObject(className, objCount, totalBytes, pct));
                } catch (Exception e) {
                    logger.debug("Skip row: {}", e.getMessage());
                }
            }
        }

        // percent 재계산 (totalHeap이 나중에 세팅되는 경우 대비)
        if (!objects.isEmpty() && result.getTotalHeapSize() > 0) {
            objects.forEach(o -> o.setPercentOfHeap(
                    (o.getTotalSize() * 100.0) / result.getTotalHeapSize()));
        }

        result.setTopMemoryObjects(objects);
        result.setTopComponentsHtml(sanitizeHtml(html));
        logger.info("Parsed {} top component objects", objects.size());
    }

    // ─── Suspects 파싱 ────────────────────────────────────────────────────────

    private void parseSuspectsZip(File zip, MatParseResult result) {
        String html = extractHtmlFromZip(zip, "suspects");
        if (html == null || html.isEmpty()) return;

        List<LeakSuspect> suspects = new ArrayList<>();

        // Problem X 섹션별 추출
        Pattern problemPattern = Pattern.compile(
                "(?:Problem|Suspect)\\s*\\d+[^<]*<.*?>(.*?)(?=(?:Problem|Suspect)\\s*\\d+|$)",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern tagPattern = Pattern.compile("<[^>]+>");

        Matcher pm = problemPattern.matcher(html);
        int idx = 1;
        while (pm.find() && suspects.size() < 5) {
            String section = tagPattern.matcher(pm.group(1)).replaceAll(" ").replaceAll("\\s+", " ").trim();
            if (section.length() > 30) {
                suspects.add(new LeakSuspect("Suspect #" + idx, section.substring(0, Math.min(section.length(), 500))));
                idx++;
            }
        }

        // 섹션 파싱 실패 시 전체 HTML에서 의심 패턴 추출
        if (suspects.isEmpty()) {
            String plain = tagPattern.matcher(html).replaceAll(" ").replaceAll("\\s+", " ");
            if (plain.length() > 100) {
                suspects.add(new LeakSuspect("Leak Analysis", plain.substring(0, Math.min(plain.length(), 1000))));
            }
        }

        result.setLeakSuspects(suspects);
        result.setSuspectsHtml(sanitizeHtml(html));
        logger.info("Parsed {} leak suspects", suspects.size());
    }

    // ─── ZIP 내 HTML 추출 ────────────────────────────────────────────────────

    private String extractHtmlFromZip(File zip, String reportType) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            String bestContent = null;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".htm")) {
                    String content = readZipEntry(zis);
                    // index.html 또는 리포트 타입명이 포함된 파일 우선
                    if (name.contains("index") || name.contains(reportType)) {
                        return content;
                    }
                    if (bestContent == null) bestContent = content;
                }
                zis.closeEntry();
            }
            return bestContent;
        } catch (IOException e) {
            logger.error("Failed to extract HTML from ZIP {}: {}", zip.getName(), e.getMessage());
            return null;
        }
    }

    private String readZipEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }

    // ─── HTML 정제 (iframe 삽입용) ────────────────────────────────────────────

    /**
     * MAT HTML에서 외부 리소스 참조를 상대 경로로 정리하고
     * 기본 스타일을 보강합니다.
     */
    private String sanitizeHtml(String html) {
        if (html == null) return "";
        // script 태그 제거 (XSS 방지)
        html = html.replaceAll("(?i)<script[^>]*>.*?</script>", "");
        // 절대 경로 이미지 제거
        html = html.replaceAll("(?i)<img[^>]+src\\s*=\\s*['\"][^'\"]*['\"][^>]*>", "");
        return html;
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────────

    private long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(s.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

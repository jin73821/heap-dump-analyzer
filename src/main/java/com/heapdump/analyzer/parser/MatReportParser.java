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

    // ─── 정규식 패턴 (MAT 실제 index.html 구조 기반) ──────────────────────────
    //
    // MAT index.html 실제 구조:
    //   <tr><td>Used heap dump</td><td>93.7 MB</td></tr>
    //   <tr><td>Number of objects</td><td>1,556,819</td></tr>
    //   <tr><td>Number of classes</td><td>24,537</td></tr>
    //
    // 크기 단위: "93.7 MB", "1.2 GB", "456 KB", "123,456,789" (숫자만)

    // <td>키</td><td>값</td> 패턴 — 키로 값 추출
    private static final Pattern TD_KEY_VALUE_PATTERN = Pattern.compile(
            "<td[^>]*>\\s*([^<]+?)\\s*</td>\\s*<td[^>]*>\\s*([^<]+?)\\s*</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 크기 문자열 파싱: "93.7 MB", "1.2 GB", "456 KB", "512 B"
    private static final Pattern SIZE_WITH_UNIT_PATTERN =
            Pattern.compile("([\\d.]+)\\s*(B|KB|MB|GB|TB)", Pattern.CASE_INSENSITIVE);

    // 숫자 (콤마 포함): "1,556,819"
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("([\\d,]+)");

    // Top Components 테이블 행 파싱
    private static final Pattern TR_PATTERN =
            Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TD_PATTERN =
            Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN =
            Pattern.compile("<[^>]+>");

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

        logger.info("[Parser] Starting parse: dir={}, base={}", heapDumpDir, dumpBaseName);

        // 디렉토리 내 파일 목록 로그 출력 (디버깅)
        File dir = new File(heapDumpDir);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                logger.info("[Parser] Files in dir: {}",
                    java.util.Arrays.stream(files)
                        .map(File::getName)
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
        } else {
            logger.error("[Parser] Directory does not exist: {}", heapDumpDir);
        }

        // 1) Overview ZIP 파싱
        File overviewZip = findZip(heapDumpDir, dumpBaseName, "overview");
        if (overviewZip != null) {
            parseOverviewZip(overviewZip, result);
        } else {
            logger.warn("[Parser] overview ZIP not found in: {} for base: {}", heapDumpDir, dumpBaseName);
        }

        // 2) Top Components ZIP 파싱
        File topZip = findZip(heapDumpDir, dumpBaseName, "top_components");
        if (topZip != null) {
            parseTopComponentsZip(topZip, result);
        } else {
            logger.warn("[Parser] top_components ZIP not found in: {} for base: {}", heapDumpDir, dumpBaseName);
        }

        // 3) Suspects ZIP 파싱
        File suspectsZip = findZip(heapDumpDir, dumpBaseName, "suspects");
        if (suspectsZip != null) {
            parseSuspectsZip(suspectsZip, result);
        } else {
            logger.warn("[Parser] suspects ZIP not found in: {} for base: {}", heapDumpDir, dumpBaseName);
        }

        logger.info("[Parser] Parse complete: totalHeap={}, usedHeap={}, freeHeap={}, topObjects={}, suspects={}",
            result.getTotalHeapSize(), result.getUsedHeapSize(), result.getFreeHeapSize(),
            result.getTopMemoryObjects().size(), result.getLeakSuspects().size());

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
        // MAT System_Overview.zip 에는 index.html 이 진입점
        // index.html 구조:
        //   <tr><td>Used heap dump</td><td>93.7 MB</td></tr>
        //   <tr><td>Number of objects</td><td>1,556,819</td></tr>
        //   <tr><td>Number of classes</td><td>24,537</td></tr>
        String html = extractHtmlFromZip(zip, "overview");
        if (html == null || html.isEmpty()) {
            logger.warn("[Parser] No HTML extracted from overview ZIP: {}", zip.getName());
            return;
        }
        logger.debug("[Parser] Overview HTML length: {}", html.length());

        // ── <td>키</td><td>값</td> 쌍 파싱 ─────────────────
        Matcher m = TD_KEY_VALUE_PATTERN.matcher(html);
        while (m.find()) {
            String key = TAG_PATTERN.matcher(m.group(1)).replaceAll("").trim();
            String val = TAG_PATTERN.matcher(m.group(2)).replaceAll("").trim();
            logger.debug("[Parser] KV: '{}' = '{}'", key, val);

            String keyL = key.toLowerCase();

            // "Used heap dump" → totalHeapSize (MAT는 used heap을 total로 표기)
            if (keyL.contains("used heap")) {
                long bytes = parseSizeString(val);
                if (bytes > 0) result.setTotalHeapSize(bytes);
                logger.info("[Parser] Found used heap dump: {} → {} bytes", val, bytes);
            }
            // "Number of objects"
            else if (keyL.contains("number of objects") || keyL.equals("objects")) {
                result.setTotalObjects(parseLong(val.replaceAll("[^\\d]", "")));
            }
            // "Number of classes"
            else if (keyL.contains("number of classes") || keyL.equals("classes")) {
                result.setTotalClasses((int) parseLong(val.replaceAll("[^\\d]", "")));
            }
        }

        // ── used/free 보정 ───────────────────────────────────
        // MAT index.html에는 "Used heap dump" 만 있고 free heap은 없음
        // total = used heap dump 값, used = total * 0.85 (GC overhead 고려), free = 나머지
        if (result.getTotalHeapSize() > 0) {
            if (result.getUsedHeapSize() == 0) {
                // "Used heap dump" 가 실제 used 메모리에 해당
                result.setUsedHeapSize(result.getTotalHeapSize());
            }
            if (result.getFreeHeapSize() == 0) {
                // File length(164,923,027 bytes ≒ 157MB)에서 heap을 뺀 값으로 추정
                // 또는 0으로 설정 (MAT는 free heap 정보 제공 안 함)
                result.setFreeHeapSize(0L);
            }
        }

        result.setOverviewHtml(sanitizeHtml(html));
        logger.info("[Parser] Overview parsed: totalHeap={} ({} objects, {} classes)",
                result.getTotalHeapSize(), result.getTotalObjects(), result.getTotalClasses());
    }

    /**
     * MAT 크기 문자열을 바이트로 변환
     * 예: "93.7 MB" → 98,238,668, "1.2 GB" → 1,288,490,188
     */
    private long parseSizeString(String s) {
        if (s == null || s.isBlank()) return 0L;
        Matcher m = SIZE_WITH_UNIT_PATTERN.matcher(s.trim());
        if (m.find()) {
            double num  = Double.parseDouble(m.group(1));
            String unit = m.group(2).toUpperCase();
            switch (unit) {
                case "TB": return (long)(num * 1024L * 1024 * 1024 * 1024);
                case "GB": return (long)(num * 1024L * 1024 * 1024);
                case "MB": return (long)(num * 1024L * 1024);
                case "KB": return (long)(num * 1024L);
                case "B":  return (long) num;
            }
        }
        // 단위 없이 숫자만 있으면 bytes로 간주
        Matcher nm = NUMBER_PATTERN.matcher(s);
        if (nm.find()) return parseLong(nm.group(1));
        return 0L;
    }

    // ─── Top Components 파싱 ──────────────────────────────────────────────────

    private void parseTopComponentsZip(File zip, MatParseResult result) {
        // Top_Consumers5.html 구조 (Biggest Objects 테이블):
        // <thead><tr><th>Class Name</th><th>Shallow Heap</th><th>Retained Heap</th></tr></thead>
        // <tbody><tr>
        //   <td>...<a href="...">com.example.Foo @ 0x...</a>...</td>
        //   <td align="right">40</td>          ← Shallow Heap (bytes)
        //   <td align="right">31,858,488</td>  ← Retained Heap (bytes)
        // </tr></tbody>
        //
        // 또는 Biggest Top-Level Dominator Classes 테이블:
        // <thead><tr><th>Label</th><th>Number of Objects</th>
        //             <th>Used Heap Size</th><th>Retained Heap Size</th>
        //             <th>Retained Heap, %</th></tr></thead>
        // <tbody><tr>
        //   <td>...<a>ClassName</a>...</td>
        //   <td align="right">1</td>            ← Number of Objects
        //   <td align="right">40</td>           ← Used Heap Size
        //   <td align="right">31,858,488</td>   ← Retained Heap Size
        //   <td align="right">32.44%</td>       ← %
        // </tr></tbody>

        String html = extractHtmlFromZip(zip, "top_components");
        if (html == null || html.isEmpty()) return;

        List<MemoryObject> objects = new ArrayList<>();

        Matcher rowM = TR_PATTERN.matcher(html);
        while (rowM.find() && objects.size() < 20) {
            String row = rowM.group(1);

            // 헤더 행 스킵
            if (row.contains("<th")) continue;
            // totals 행 스킵
            if (row.contains("totals") || row.contains("Total:")) continue;

            List<String> cells = new ArrayList<>();
            Matcher cellM = TD_PATTERN.matcher(row);
            while (cellM.find()) {
                String cell = TAG_PATTERN.matcher(cellM.group(1))
                        .replaceAll(" ").replaceAll("\\s+", " ").trim();
                cells.add(cell);
            }

            if (cells.size() < 2) continue;

            try {
                // 클래스명: 첫 번째 셀에서 추출 (링크 텍스트, @ 주소 제거)
                
                
                String className = extractCleanClassName(cells.get(0));
                // "First 10 of N objects" 같은 부가 텍스트 제거
                // 빈 값 / 헤더 스킵
                if (className.isEmpty() || className.equalsIgnoreCase("Class Name")
                        || className.equalsIgnoreCase("Label")
                        || className.equalsIgnoreCase("Package")
                        || className.startsWith("<")) continue;

                // 숫자 셀에서 Retained Heap 추출
                // 셀이 3개: [ClassName, ShallowHeap, RetainedHeap]
                // 셀이 5개: [Label, NumObjects, UsedHeapSize, RetainedHeapSize, Pct%]
                long retainedHeap = 0L;
                long objCount     = 0L;

                if (cells.size() >= 5) {
                    // 5열 형식: Label | NumObj | UsedSize | RetainedSize | Pct
                    objCount     = parseLong(cells.get(1).replaceAll("[^\\d]", ""));
                    retainedHeap = parseLong(cells.get(3).replaceAll("[^\\d]", ""));
                } else if (cells.size() >= 3) {
                    // 3열 형식: ClassName | ShallowHeap | RetainedHeap
                    retainedHeap = parseLong(cells.get(2).replaceAll("[^\\d]", ""));
                    objCount     = 1L;
                } else if (cells.size() == 2) {
                    retainedHeap = parseLong(cells.get(1).replaceAll("[^\\d]", ""));
                }

                if (retainedHeap == 0) continue;

                double pct = result.getTotalHeapSize() > 0
                        ? (retainedHeap * 100.0) / result.getTotalHeapSize() : 0.0;

                objects.add(new MemoryObject(className, objCount, retainedHeap, pct));
                logger.debug("[Parser] Top object: {} = {} bytes ({} objs)",
                        className, retainedHeap, objCount);

            } catch (Exception e) {
                logger.debug("[Parser] Skip row: {}", e.getMessage());
            }
        }

        // 중복 클래스명 병합: 동일 클래스는 최대 retained heap 유지
        Map<String, MemoryObject> dedup = new java.util.LinkedHashMap<>();
        for (MemoryObject o : objects) {
            String key = o.getClassName();
            MemoryObject exist = dedup.get(key);
            if (exist == null) { dedup.put(key, o); }
            else if (o.getTotalSize() > exist.getTotalSize()) { dedup.put(key, o); }
        }
        objects = new ArrayList<>(dedup.values());

                // percent 재계산
        if (!objects.isEmpty() && result.getTotalHeapSize() > 0) {
            objects.forEach(o -> o.setPercentOfHeap(
                    (o.getTotalSize() * 100.0) / result.getTotalHeapSize()));
        }

        // 크기 기준 내림차순 정렬
        objects.sort((a, b) -> Long.compare(b.getTotalSize(), a.getTotalSize()));

        // 상위 10개만 유지
        if (objects.size() > 10) objects = objects.subList(0, 10);

        result.setTopMemoryObjects(objects);
        result.setTopComponentsHtml(sanitizeHtml(html));
        logger.info("[Parser] Parsed {} top component objects", objects.size());
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
        // ZIP 내 HTML 파일 우선순위:
        //   overview      → index.html (Heap Dump Overview 테이블 포함)
        //   top_components → pages/Top_Consumers*.html (Biggest Objects 테이블)
        //   suspects       → index.html 또는 pages/*.html
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            Map<String, String> htmlFiles = new java.util.LinkedHashMap<>();
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".htm")) {
                    htmlFiles.put(entry.getName(), readZipEntry(zis));
                }
                zis.closeEntry();
            }

            if (htmlFiles.isEmpty()) return null;

            // 리포트 타입별 우선 파일 선택
            switch (reportType) {
                case "overview":
                    // index.html 우선 (Heap Dump Overview 테이블)
                    for (Map.Entry<String, String> e : htmlFiles.entrySet()) {
                        if (e.getKey().toLowerCase().equals("index.html")) return e.getValue();
                    }
                    break;
                case "top_components":
                    // pages/Top_Consumers*.html 우선 (가장 큰 파일)
                    String bestTop = null;
                    int bestTopSize = 0;
                    for (Map.Entry<String, String> e : htmlFiles.entrySet()) {
                        String k = e.getKey().toLowerCase();
                        if (k.contains("top_consumer") || k.contains("topconsumer")) {
                            if (e.getValue().length() > bestTopSize) {
                                bestTopSize = e.getValue().length();
                                bestTop = e.getValue();
                            }
                        }
                    }
                    if (bestTop != null) {
                        logger.info("[Parser] Using Top_Consumers HTML ({} chars)", bestTopSize);
                        return bestTop;
                    }
                    // 폴백: pages/ 하위에서 가장 큰 HTML
                    String largestPage = null;
                    int largestSize = 0;
                    for (Map.Entry<String, String> e : htmlFiles.entrySet()) {
                        if (e.getKey().toLowerCase().startsWith("pages/")
                                && e.getValue().length() > largestSize) {
                            largestSize = e.getValue().length();
                            largestPage = e.getValue();
                        }
                    }
                    if (largestPage != null) return largestPage;
                    break;
                case "suspects":
                    // index.html 우선
                    for (Map.Entry<String, String> e : htmlFiles.entrySet()) {
                        if (e.getKey().toLowerCase().equals("index.html")) return e.getValue();
                    }
                    break;
            }

            // 최후 폴백: 첫 번째 HTML
            return htmlFiles.values().iterator().next();

        } catch (IOException e) {
            logger.error("[Parser] Failed to extract HTML from ZIP {}: {}", zip.getName(), e.getMessage());
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

    private String extractCleanClassName(String raw) {
        if (raw == null) return "";
        String s = decodeHtmlEntities(raw);
        // 16진수 주소 제거: @ 0xc04ff6d8
        s = s.replaceAll("@?\\s*0x[0-9a-fA-F]+", "");
        // 화살표 문자 제거 (» \u00BB)
        s = s.replaceAll("\u00BB\\s*", " ");
        s = s.replaceAll("[\u00BB\u203A\u2039\u00AB]", "");
        // 부가 설명 제거 (콤마 포함 숫자 지원)
        s = s.replaceAll("(?i)\\bOnly\\s+object\\b.*", "");
        s = s.replaceAll("(?i)\\bFirst\\s+[\\d,]+\\s+of\\s+[\\d,]+\\s+objects?\\b.*", "");
        s = s.replaceAll("(?i)\\bAll\\s+[\\d,]+\\s+objects?\\b.*", "");
        s = s.replaceAll("(?i)\\bOnly\\s+[\\d,]+\\s+objects?\\b.*", "");
        // 공백 정리
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private String decodeHtmlEntities(String s) {
        if (s == null) return "";
        return s
            .replace("&raquo;",  "\u00BB")
            .replace("&laquo;",  "\u00AB")
            .replace("&rsaquo;", "\u203A")
            .replace("&gt;",     ">")
            .replace("&lt;",     "<")
            .replace("&amp;",    "&")
            .replace("&nbsp;",   " ")
            .replace("&quot;",   "\"")
            .replace("&#187;",   "\u00BB")
            .replace("&#171;",   "\u00AB");
    }

}

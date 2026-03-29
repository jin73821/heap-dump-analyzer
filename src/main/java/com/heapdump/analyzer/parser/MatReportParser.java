package com.heapdump.analyzer.parser;

import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.util.HtmlSanitizer;
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

    // ─── 인라인 replaceAll 대체용 사전 컴파일 패턴 ──────────────────────────────
    private static final Pattern NON_DIGIT_PATTERN = Pattern.compile("[^\\d]");
    private static final Pattern COMMA_SPACE_PATTERN = Pattern.compile("[,\\s]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern HEX_ADDR_PATTERN = Pattern.compile("@?\\s*0x[0-9a-fA-F]+");
    private static final Pattern HEX_ADDR_EXTRACT_PATTERN = Pattern.compile("0x([0-9a-fA-F]+)");
    private static final Pattern TOTAL_ENTRIES_PATTERN = Pattern.compile(
            "Total:\\s*\\d+\\s+of\\s+([\\d,]+)\\s+entries", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_OBJECTS_SUFFIX_PATTERN = Pattern.compile(
            "(?i)\\s*All\\s+objects\\s*$");
    private static final Pattern PROBLEM_SUSPECT_PATTERN = Pattern.compile(
            "(?:Problem|Suspect)\\s*\\d+[^<]*<.*?>(.*?)(?=(?:Problem|Suspect)\\s*\\d+|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // extractCleanClassName 용
    private static final Pattern ARROW_CHAR_PATTERN = Pattern.compile("[\u00BB\u203A\u2039\u00AB]");
    private static final Pattern ARROW_SPACE_PATTERN = Pattern.compile("\u00BB\\s*");
    private static final Pattern ONLY_OBJECT_PATTERN = Pattern.compile("(?i)\\bOnly\\s+object\\b.*");
    private static final Pattern FIRST_N_OF_PATTERN = Pattern.compile(
            "(?i)\\bFirst\\s+[\\d,]+\\s+of\\s+[\\d,]+\\s+objects?\\b.*");
    private static final Pattern ALL_N_OBJECTS_PATTERN = Pattern.compile(
            "(?i)\\bAll\\s+[\\d,]+\\s+objects?\\b.*");
    private static final Pattern ONLY_N_OBJECTS_PATTERN = Pattern.compile(
            "(?i)\\bOnly\\s+[\\d,]+\\s+objects?\\b.*");

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

    /**
     * 기존 캐시에 histogramHtml/threadOverviewHtml이 없을 때 ZIP에서 재추출하는 공개 메서드.
     */
    public void reparseActions(String heapDumpDir, String dumpBaseName, MatParseResult result) {
        File overviewZip = findZip(heapDumpDir, dumpBaseName, "overview");
        if (overviewZip == null) return;

        String histHtml = extractNamedPageFromZip(overviewZip, "class_histogram");
        if (histHtml != null) {
            result.setHistogramHtml(sanitizeHtml(histHtml));
            parseHistogramEntries(histHtml, result);
        }

        String threadHtml = extractNamedPageFromZip(overviewZip, "thread_overview");
        if (threadHtml != null) {
            result.setThreadOverviewHtml(sanitizeHtml(threadHtml));
            parseThreadInfoEntries(threadHtml, result);
        }

        logger.info("[Parser] Re-extracted actions: histogram={}, threadOverview={}",
                result.getHistogramHtml() != null && !result.getHistogramHtml().isEmpty(),
                result.getThreadOverviewHtml() != null && !result.getThreadOverviewHtml().isEmpty());
    }

    /**
     * 기존 캐시에 componentDetailHtmlMap이 없을 때 ZIP에서 재추출하는 공개 메서드.
     */
    public void reparseComponentDetails(String heapDumpDir, String dumpBaseName, MatParseResult result) {
        File topZip = findZip(heapDumpDir, dumpBaseName, "top_components");
        if (topZip == null) return;

        String indexHtml = extractHtmlFromZip(topZip, "top_components_index");
        if (indexHtml != null && !indexHtml.isEmpty()) {
            // objects 리스트는 result에서 가져옴 (이미 파싱된 것 사용)
            extractComponentDetailPages(topZip, indexHtml, result.getTopMemoryObjects(), result);
        }
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

            // 2) baseName 없이 keyword만으로 폴백 탐색은 제거
            //    다른 분석 결과의 ZIP을 잘못 매칭하는 심각한 버그 방지
            //    (예: ssh-to-pgp_234 분석 시 tomcat_heapdump ZIP이 매칭되는 문제)
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
                result.setTotalObjects(parseLong(digitsOnly(val)));
            }
            // "Number of classes"
            else if (keyL.contains("number of classes") || keyL.equals("classes")) {
                result.setTotalClasses((int) parseLong(digitsOnly(val)));
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

        // ── Histogram / Thread Overview 추출 ─────────────
        String histHtml = extractNamedPageFromZip(zip, "class_histogram");
        if (histHtml != null) {
            result.setHistogramHtml(sanitizeHtml(histHtml));
            parseHistogramEntries(histHtml, result);
        }

        String threadHtml = extractNamedPageFromZip(zip, "thread_overview");
        if (threadHtml != null) {
            result.setThreadOverviewHtml(sanitizeHtml(threadHtml));
            parseThreadInfoEntries(threadHtml, result);
        }

        logger.info("[Parser] Overview parsed: totalHeap={} ({} objects, {} classes), histogram={}, threadOverview={}",
                result.getTotalHeapSize(), result.getTotalObjects(), result.getTotalClasses(),
                result.getHistogramHtml() != null && !result.getHistogramHtml().isEmpty(),
                result.getThreadOverviewHtml() != null && !result.getThreadOverviewHtml().isEmpty());
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

    // index.html <h2> 패턴: <a href="...">ComponentName (43%)</a>
    private static final Pattern H2_COMPONENT_PATTERN = Pattern.compile(
            "<h2[^>]*>.*?<a[^>]*>([^<]+?)\\((\\d+)%\\)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // index.html <h2> + href 패턴: href="pages/xxx.html"
    private static final Pattern H2_COMPONENT_LINK_PATTERN = Pattern.compile(
            "<h2[^>]*>.*?<a\\s+href=\"([^\"]+)\"[^>]*>([^<]+?)\\((\\d+)%\\)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private void parseTopComponentsZip(File zip, MatParseResult result) {
        // ── 1단계: index.html의 <h2> 태그에서 최상위 컴포넌트 추출 ──
        //
        // MAT Top_Components.zip의 index.html 구조:
        //   <h2><a href="pages/xxx.html">&lt;system class loader&gt; (43%)</a></h2>
        //   <h2><a href="pages/xxx.html">com.example.ClassLoader @ 0x... (33%)</a></h2>
        //
        // 이 비율은 전체 힙 대비 각 Top-Level Dominator Component의 retained heap 비율.

        String indexHtml = extractHtmlFromZip(zip, "top_components_index");
        List<MemoryObject> objects = new ArrayList<>();

        if (indexHtml != null && !indexHtml.isEmpty() && result.getTotalHeapSize() > 0) {
            Matcher hm = H2_COMPONENT_PATTERN.matcher(indexHtml);
            while (hm.find() && objects.size() < 15) {
                String rawName = decodeHtmlEntities(hm.group(1)).trim();
                int pctInt = Integer.parseInt(hm.group(2));
                if (pctInt <= 0) continue;

                // 컴포넌트명 정리: @ 주소 제거
                String className = HEX_ADDR_PATTERN.matcher(rawName).replaceAll("").trim();
                if (className.isEmpty()) continue;

                // 비율에서 실제 바이트 크기 계산
                long retainedHeap = (long) (result.getTotalHeapSize() * pctInt / 100.0);
                double pct = (double) pctInt;

                objects.add(new MemoryObject(className, 0L, retainedHeap, pct));
                logger.info("[Parser] Top component from index: {} = {}% ({} bytes)",
                        className, pctInt, retainedHeap);
            }
        }

        // ── 2단계: index.html 파싱 실패 시 → 하위 페이지 테이블 폴백 ──
        if (objects.isEmpty()) {
            logger.info("[Parser] No components in index.html, falling back to sub-page table parsing");
            String subPageHtml = extractHtmlFromZip(zip, "top_components");
            if (subPageHtml != null && !subPageHtml.isEmpty()) {
                objects = parseTopComponentsFromTable(subPageHtml, result);
            }
        }

        // 크기 기준 내림차순 정렬
        objects.sort((a, b) -> Long.compare(b.getTotalSize(), a.getTotalSize()));

        // 상위 10개만 유지
        if (objects.size() > 10) objects = new ArrayList<>(objects.subList(0, 10));

        result.setTopMemoryObjects(objects);

        // 표시용 HTML은 index.html 사용 (전체 개요)
        if (indexHtml != null && !indexHtml.isEmpty()) {
            result.setTopComponentsHtml(sanitizeHtml(indexHtml));
        } else {
            String subHtml = extractHtmlFromZip(zip, "top_components");
            if (subHtml != null) result.setTopComponentsHtml(sanitizeHtml(subHtml));
        }

        // ── 3단계: 각 컴포넌트의 하위 페이지 HTML 추출 ──
        if (indexHtml != null && !indexHtml.isEmpty()) {
            extractComponentDetailPages(zip, indexHtml, objects, result);
        }

        logger.info("[Parser] Parsed {} top component objects (total retained: {} bytes), {} detail pages",
                objects.size(),
                objects.stream().mapToLong(MemoryObject::getTotalSize).sum(),
                result.getComponentDetailHtmlMap().size());
    }

    /**
     * 하위 페이지 Top_Consumers*.html의 테이블에서 Top Objects 추출 (폴백)
     */
    private List<MemoryObject> parseTopComponentsFromTable(String html, MatParseResult result) {
        List<MemoryObject> objects = new ArrayList<>();

        Matcher rowM = TR_PATTERN.matcher(html);
        while (rowM.find() && objects.size() < 20) {
            String row = rowM.group(1);
            if (row.contains("<th")) continue;
            if (row.contains("totals") || row.contains("Total:")) continue;

            List<String> cells = new ArrayList<>();
            Matcher cellM = TD_PATTERN.matcher(row);
            while (cellM.find()) {
                String cell = stripTags(cellM.group(1));
                cells.add(cell);
            }
            if (cells.size() < 2) continue;

            try {
                String className = extractCleanClassName(cells.get(0));
                if (className.isEmpty() || className.equalsIgnoreCase("Class Name")
                        || className.equalsIgnoreCase("Label")
                        || className.equalsIgnoreCase("Package")
                        || className.startsWith("<")) continue;

                long retainedHeap = 0L;
                long objCount = 0L;

                if (cells.size() >= 5) {
                    objCount     = parseLong(digitsOnly(cells.get(1)));
                    retainedHeap = parseLong(digitsOnly(cells.get(3)));
                } else if (cells.size() >= 3) {
                    retainedHeap = parseLong(digitsOnly(cells.get(2)));
                    objCount     = 1L;
                } else if (cells.size() == 2) {
                    retainedHeap = parseLong(digitsOnly(cells.get(1)));
                }
                if (retainedHeap == 0) continue;

                double pct = result.getTotalHeapSize() > 0
                        ? (retainedHeap * 100.0) / result.getTotalHeapSize() : 0.0;
                objects.add(new MemoryObject(className, objCount, retainedHeap, pct));
            } catch (Exception e) {
                logger.debug("[Parser] Skip row: {}", e.getMessage());
            }
        }

        // 중복 병합
        Map<String, MemoryObject> dedup = new java.util.LinkedHashMap<>();
        for (MemoryObject o : objects) {
            MemoryObject exist = dedup.get(o.getClassName());
            if (exist == null || o.getTotalSize() > exist.getTotalSize())
                dedup.put(o.getClassName(), o);
        }
        return new ArrayList<>(dedup.values());
    }

    // ─── Top Component 하위 페이지 추출 ────────────────────────────────────────

    /**
     * index.html의 h2 링크에서 각 컴포넌트의 하위 페이지 경로를 찾고,
     * ZIP에서 해당 HTML을 추출하여 componentDetailHtmlMap에 저장.
     */
    private void extractComponentDetailPages(File zip, String indexHtml,
                                              List<MemoryObject> objects, MatParseResult result) {
        // index.html에서 href → 키(className#순번) 매핑 구성
        // 같은 클래스명이 여러 인스턴스일 수 있으므로 순번으로 구분
        Map<String, String> hrefToKey = new java.util.LinkedHashMap<>();
        Map<String, Integer> nameCount = new java.util.HashMap<>();
        Matcher lm = H2_COMPONENT_LINK_PATTERN.matcher(indexHtml);
        int idx = 0;
        while (lm.find()) {
            String href = lm.group(1);
            String rawName = decodeHtmlEntities(lm.group(2)).trim();
            String className = HEX_ADDR_PATTERN.matcher(rawName).replaceAll("").trim();
            if (!className.isEmpty()) {
                int count = nameCount.getOrDefault(className, 0);
                nameCount.put(className, count + 1);
                // 키: 순번 기반 (프론트엔드에서 #idx 로 조회)
                String key = className + "#" + idx;
                hrefToKey.put(href, key);
                idx++;
            }
        }

        if (hrefToKey.isEmpty()) return;

        // ZIP에서 하위 페이지 HTML 읽기
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            Set<String> targetPaths = hrefToKey.keySet();
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (targetPaths.contains(entryName)) {
                    String html = readZipEntry(zis);
                    String key = hrefToKey.get(entryName);
                    if (html != null && !html.isEmpty()) {
                        result.getComponentDetailHtmlMap().put(key, sanitizeHtml(html));
                        logger.debug("[Parser] Extracted detail page for: {} ({} chars)",
                                key, html.length());
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            logger.warn("[Parser] Failed to extract component detail pages: {}", e.getMessage());
        }

        logger.info("[Parser] Extracted {} component detail pages", result.getComponentDetailHtmlMap().size());
    }

    // ─── Suspects 파싱 ────────────────────────────────────────────────────────

    private void parseSuspectsZip(File zip, MatParseResult result) {
        String html = extractHtmlFromZip(zip, "suspects");
        if (html == null || html.isEmpty()) return;

        List<LeakSuspect> suspects = new ArrayList<>();

        // Problem X 섹션별 추출
        Matcher pm = PROBLEM_SUSPECT_PATTERN.matcher(html);
        int idx = 1;
        while (pm.find() && suspects.size() < 5) {
            String section = stripTags(pm.group(1));
            if (section.length() > 30) {
                suspects.add(new LeakSuspect("Suspect #" + idx, section.substring(0, Math.min(section.length(), 500))));
                idx++;
            }
        }

        // 섹션 파싱 실패 시 전체 HTML에서 의심 패턴 추출
        if (suspects.isEmpty()) {
            String plain = stripTags(html);
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
                case "top_components_index":
                case "suspects":
                    // index.html 우선
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
            }

            // 최후 폴백: 첫 번째 HTML
            return htmlFiles.values().iterator().next();

        } catch (IOException e) {
            logger.error("[Parser] Failed to extract HTML from ZIP {}: {}", zip.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * ZIP 파일에서 이름(대소문자 무관)에 pattern이 포함된 HTML 엔트리를 찾아 반환.
     * pages/ 하위에서 먼저 탐색.
     */
    private String extractNamedPageFromZip(File zip, String namePattern) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            String patternLower = namePattern.toLowerCase();
            String bestMatch = null;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName().toLowerCase();
                if ((entryName.endsWith(".html") || entryName.endsWith(".htm"))
                        && entryName.contains(patternLower)) {
                    String html = readZipEntry(zis);
                    // pages/ 하위 파일 우선, 없으면 첫 매칭
                    if (entryName.startsWith("pages/")) {
                        return html;
                    }
                    if (bestMatch == null) bestMatch = html;
                }
                zis.closeEntry();
            }
            return bestMatch;
        } catch (IOException e) {
            logger.warn("[Parser] Failed to extract '{}' from ZIP {}: {}", namePattern, zip.getName(), e.getMessage());
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
    /**
     * MAT HTML 새니타이즈 — OWASP whitelist 기반.
     * script, 이벤트 핸들러, 외부 리소스 참조 등 위험 요소를 제거합니다.
     */
    private String sanitizeHtml(String html) {
        if (html == null) return "";
        return HtmlSanitizer.sanitize(html);
    }

    // ─── Histogram 파싱 ─────────────────────────────────────────────────────────

    /**
     * Histogram HTML 테이블에서 엔트리를 추출합니다.
     */
    private void parseHistogramEntries(String html, MatParseResult result) {
        List<HistogramEntry> entries = new ArrayList<>();
        int totalClasses = 0;

        Matcher rowM = TR_PATTERN.matcher(html);
        while (rowM.find()) {
            String row = rowM.group(1);
            // 헤더 행 스킵
            if (row.contains("<th")) continue;

            // Total 행에서 전체 클래스 수 추출
            if (row.toLowerCase().contains("total:") || row.toLowerCase().contains("total")) {
                String plainRow = TAG_PATTERN.matcher(row).replaceAll(" ").trim();
                // "Total: 25 of 25,086 entries" 패턴 매칭
                Matcher totalM = TOTAL_ENTRIES_PATTERN.matcher(plainRow);
                if (totalM.find()) {
                    totalClasses = (int) parseLong(totalM.group(1));
                }
                continue;
            }

            List<String> cells = new ArrayList<>();
            Matcher cellM = TD_PATTERN.matcher(row);
            while (cellM.find()) {
                String cell = stripTags(cellM.group(1));
                cells.add(cell);
            }

            // 최소 4개 컬럼 필요 (className, objectCount, shallowHeap, retainedHeap)
            if (cells.size() < 4) continue;

            try {
                String className = cells.get(0).trim();
                // "All objects" 접미사 제거
                className = ALL_OBJECTS_SUFFIX_PATTERN.matcher(className).replaceAll("").trim();
                if (className.isEmpty() || className.equalsIgnoreCase("Class Name")) continue;

                long objectCount = parseLong(digitsOnly(cells.get(1)));
                long shallowHeap = parseLong(digitsOnly(cells.get(2)));

                // retainedHeap: ">= NNN" 형식 처리
                String retainedRaw = cells.get(3).trim();
                String retainedDisplay = retainedRaw;
                long retainedHeap = parseLong(digitsOnly(retainedRaw));

                entries.add(new HistogramEntry(className, objectCount, shallowHeap, retainedHeap, retainedDisplay));
            } catch (Exception e) {
                logger.debug("[Parser] Skip histogram row: {}", e.getMessage());
            }
        }

        result.setHistogramEntries(entries);
        result.setTotalHistogramClasses(totalClasses);
        logger.info("[Parser] Parsed {} histogram entries, totalClasses={}", entries.size(), totalClasses);
    }

    // ─── Thread Overview 파싱 ──────────────────────────────────────────────────

    /**
     * Thread Overview HTML 테이블에서 스레드 정보를 추출합니다.
     */
    private void parseThreadInfoEntries(String html, MatParseResult result) {
        List<ThreadInfo> threads = new ArrayList<>();

        Matcher rowM = TR_PATTERN.matcher(html);
        while (rowM.find()) {
            String row = rowM.group(1);
            // 헤더 행 스킵
            if (row.contains("<th")) continue;
            // Total 행 스킵
            if (row.toLowerCase().contains("total:") || row.toLowerCase().contains("totals")) continue;

            List<String> cells = new ArrayList<>();
            Matcher cellM = TD_PATTERN.matcher(row);
            while (cellM.find()) {
                String cell = stripTags(cellM.group(1));
                cells.add(cell);
            }

            // 최소 4개 컬럼 필요
            if (cells.size() < 4) continue;

            try {
                String objectType = cells.get(0).trim();
                if (objectType.isEmpty() || objectType.equalsIgnoreCase("Object / Stack Frame")) continue;

                String name = cells.size() > 1 ? cells.get(1).trim() : "";
                long shallowHeap = parseLong(digitsOnly(cells.get(2)));
                long retainedHeap = parseLong(digitsOnly(cells.get(3)));

                String contextClassLoader = cells.size() > 5 ? cells.get(5).trim() : "";

                // objectType에서 주소 추출: "java.lang.Thread @ 0xc1299f88 »"
                String address = "";
                Matcher addrM = HEX_ADDR_EXTRACT_PATTERN.matcher(objectType);
                if (addrM.find()) {
                    address = "0x" + addrM.group(1);
                }

                ThreadInfo ti = new ThreadInfo();
                ti.setName(name);
                ti.setObjectType(objectType);
                ti.setShallowHeap(shallowHeap);
                ti.setRetainedHeap(retainedHeap);
                ti.setContextClassLoader(contextClassLoader);
                ti.setDaemon(false);
                ti.setAddress(address);

                threads.add(ti);
            } catch (Exception e) {
                logger.debug("[Parser] Skip thread row: {}", e.getMessage());
            }
        }

        result.setThreadInfos(threads);
        logger.info("[Parser] Parsed {} thread info entries", threads.size());
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────────

    private long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try {
            return Long.parseLong(COMMA_SPACE_PATTERN.matcher(s).replaceAll(""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** HTML 태그 제거 + 공백 정규화 */
    private String stripTags(String s) {
        if (s == null) return "";
        return WHITESPACE_PATTERN.matcher(TAG_PATTERN.matcher(s).replaceAll(" ")).replaceAll(" ").trim();
    }

    /** 숫자 외 문자 제거 (사전 컴파일 패턴 사용) */
    private String digitsOnly(String s) {
        if (s == null) return "";
        return NON_DIGIT_PATTERN.matcher(s).replaceAll("");
    }

    private String extractCleanClassName(String raw) {
        if (raw == null) return "";
        String s = decodeHtmlEntities(raw);
        // 16진수 주소 제거: @ 0xc04ff6d8
        s = HEX_ADDR_PATTERN.matcher(s).replaceAll("");
        // 화살표 문자 제거 (» \u00BB)
        s = ARROW_SPACE_PATTERN.matcher(s).replaceAll(" ");
        s = ARROW_CHAR_PATTERN.matcher(s).replaceAll("");
        // 부가 설명 제거 (콤마 포함 숫자 지원)
        s = ONLY_OBJECT_PATTERN.matcher(s).replaceAll("");
        s = FIRST_N_OF_PATTERN.matcher(s).replaceAll("");
        s = ALL_N_OBJECTS_PATTERN.matcher(s).replaceAll("");
        s = ONLY_N_OBJECTS_PATTERN.matcher(s).replaceAll("");
        // 공백 정리
        s = WHITESPACE_PATTERN.matcher(s).replaceAll(" ").trim();
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

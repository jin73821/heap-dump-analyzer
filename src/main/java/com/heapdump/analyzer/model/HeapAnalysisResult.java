package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 힙 분석 결과 모델 (MAT CLI 연동 버전)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeapAnalysisResult {

    // ── 파일 정보 ────────────────────────────────────
    private String filename;
    private long   fileSize;
    private long   originalFileSize;   // 압축 전 원본 크기 (result.json에 영속)
    private long   lastModified;
    private String format;

    // ── 힙 메모리 통계 ───────────────────────────────
    private long   totalHeapSize;
    private long   usedHeapSize;
    private long   freeHeapSize;
    private double heapUsagePercent;

    // ── 상위 메모리 객체 (Top Components) ─────────────
    private List<MemoryObject> topMemoryObjects = new ArrayList<>();

    // ── Leak Suspects ────────────────────────────────
    private List<LeakSuspect> leakSuspects = new ArrayList<>();

    // ── 통계 ─────────────────────────────────────────
    private int  totalClasses;
    private long totalObjects;
    private int  classLoaderCount;
    private long gcRootCount;

    // ── 분석 메타 ────────────────────────────────────
    private long   analysisTime;
    private AnalysisStatus analysisStatus;
    private String errorMessage;

    // ── MAT 원본 HTML (상세 탭용) ─────────────────────
    private String overviewHtml;
    private String topComponentsHtml;
    private String suspectsHtml;

    // ── Top Component 별 상세 HTML (className → HTML) ──
    private Map<String, String> componentDetailHtmlMap = new LinkedHashMap<>();

    // ── Top Component 별 파싱된 상세 데이터 (className → parsed) ──
    private Map<String, ComponentDetailParsed> componentDetailParsedMap = new LinkedHashMap<>();

    // ── Dominator Tree ────────────────────────────────────
    private List<DominatorTreeEntry> dominatorTreeEntries = new ArrayList<>();

    // ── MAT Actions (Histogram / Thread Overview) ─────
    private String histogramHtml;
    private String threadOverviewHtml;

    // ── Parsed Histogram / Thread data ──────────────────
    private List<HistogramEntry> histogramEntries = new ArrayList<>();
    private List<ThreadInfo> threadInfos = new ArrayList<>();
    private int totalHistogramClasses;

    // ── System Properties (MAT system_properties 쿼리에서 추출, key→value) ──
    // 신규 분석분만 채워짐 (기존 result.json 은 빈 맵). JDK/OS/WAS 식별·버전 근거.
    private Map<String, String> systemProperties = new LinkedHashMap<>();

    // ── Thread Stacks (.threads 파일) ──────────────────
    @JsonIgnore
    private String threadStacksText;

    // ── 실제 throw 된 OOM 의 정확한 detailMessage (힙의 OutOfMemoryError 객체에서 추출) ──
    // 스레드 스택 locals 와 OOM 인스턴스 주소 교집합으로 preallocated 템플릿을 배제한 값.
    // null 이면 미추출 — 이 경우 ThreadInfo.oomType 은 스택 시그니처 기반 "(추정)" 값 사용.
    private String oomDetailMessage;

    // ── MAT 실행 로그 ────────────────────────────────
    private String matLog;

    // ─────────────────────────────────────────────────
    public enum AnalysisStatus { SUCCESS, ERROR, RUNNING }

    // ── 포맷 헬퍼 ────────────────────────────────────
    public String getFormattedFileSize()        { return formatBytes(fileSize); }
    public String getFormattedTotalHeapSize()   { return formatBytes(totalHeapSize); }
    public String getFormattedUsedHeapSize()    { return formatBytes(usedHeapSize); }
    public String getFormattedFreeHeapSize()    { return formatBytes(freeHeapSize); }
    public String getFormattedHeapUsagePercent(){ return String.format("%.2f%%", heapUsagePercent); }
    public String getFormattedTotalClasses()    { return String.format("%,d", totalClasses); }
    public String getFormattedTotalObjects()      { return String.format("%,d", totalObjects); }
    public String getFormattedClassLoaderCount() { return String.format("%,d", classLoaderCount); }
    public String getFormattedGcRootCount()      { return String.format("%,d", gcRootCount); }

    public boolean hasLeakSuspects() {
        return leakSuspects != null && !leakSuspects.isEmpty();
    }

    @JsonIgnore
    public boolean hasSystemProperties() {
        return systemProperties != null && !systemProperties.isEmpty();
    }

    /** JDK/JVM 런타임 버전 (java.runtime.version → java.version → java.vm.version 순). 없으면 null. */
    @JsonIgnore
    public String getJdkVersion() {
        if (systemProperties == null) return null;
        for (String k : new String[]{"java.runtime.version", "java.version", "java.vm.version"}) {
            String v = systemProperties.get(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    // ── OOM 요약 (transient, threadInfos 파생) ─────────
    @JsonIgnore
    public int getOomThreadCount() {
        if (threadInfos == null || threadInfos.isEmpty()) return 0;
        int n = 0;
        for (ThreadInfo ti : threadInfos) if (ti != null && ti.isOom()) n++;
        return n;
    }

    @JsonIgnore
    public String getOomFirstType() {
        if (threadInfos == null || threadInfos.isEmpty()) return null;
        for (ThreadInfo ti : threadInfos) {
            if (ti != null && ti.isOom() && ti.getOomType() != null && !ti.getOomType().isEmpty()) {
                return ti.getOomType();
            }
        }
        return null;
    }

    @JsonIgnore
    public List<String> getOomThreadNames(int limit) {
        List<String> out = new ArrayList<>();
        if (threadInfos == null || limit <= 0) return out;
        for (ThreadInfo ti : threadInfos) {
            if (ti != null && ti.isOom() && ti.getName() != null) {
                out.add(ti.getName());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** LLM prompt 용 OOM 컨텍스트 블록. count==0 이면 "" 반환 (호출자 isEmpty 가드). */
    @JsonIgnore
    public String getOomContextSummary() {
        int count = getOomThreadCount();
        if (count == 0) return "";
        String firstType = getOomFirstType();
        int shown = Math.min(count, 5);
        List<String> names = getOomThreadNames(shown);
        StringBuilder sb = new StringBuilder();
        sb.append("== OutOfMemoryError 감지 ==\n");
        sb.append("감지된 스레드: ").append(count).append("개");
        if (firstType != null) sb.append(" (").append(firstType).append(")");
        sb.append('\n');
        for (String n : names) sb.append("- ").append(n).append('\n');
        if (count > names.size()) sb.append("(외 ").append(count - names.size()).append("개)\n");
        return sb.toString();
    }

    private String formatBytes(long bytes) {
        return com.heapdump.analyzer.util.FormatUtils.formatBytes(bytes);
    }
}

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

    // ── MAT Actions (Histogram / Thread Overview) ─────
    private String histogramHtml;
    private String threadOverviewHtml;

    // ── Parsed Histogram / Thread data ──────────────────
    private List<HistogramEntry> histogramEntries = new ArrayList<>();
    private List<ThreadInfo> threadInfos = new ArrayList<>();
    private int totalHistogramClasses;

    // ── Thread Stacks (.threads 파일) ──────────────────
    @JsonIgnore
    private String threadStacksText;

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
    public String getFormattedTotalObjects()    { return String.format("%,d", totalObjects); }

    public boolean hasLeakSuspects() {
        return leakSuspects != null && !leakSuspects.isEmpty();
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0)                return "0 B";
        if (bytes < 1024)              return bytes + " B";
        if (bytes < 1024 * 1024)       return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

package com.heapdump.analyzer.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MAT CLI 리포트 파싱 결과를 담는 중간 모델
 */
@Data
@NoArgsConstructor
public class MatParseResult {

    // ── 힙 메모리 통계 ──────────────────────────────
    private long totalHeapSize;
    private long usedHeapSize;
    private long freeHeapSize;
    private int  totalClasses;
    private long totalObjects;

    // ── 상위 메모리 객체 (Top Components) ────────────
    private List<MemoryObject> topMemoryObjects = new ArrayList<>();

    // ── Leak Suspects ───────────────────────────────
    private List<LeakSuspect> leakSuspects = new ArrayList<>();

    // ── 원본 HTML (iframe 또는 상세 탭에 표시) ────────
    private String overviewHtml      = "";
    private String topComponentsHtml = "";
    private String suspectsHtml      = "";

    // ── Top Component 별 상세 HTML (className → HTML) ──
    private Map<String, String> componentDetailHtmlMap = new LinkedHashMap<>();

    // ── Top Component 별 파싱된 상세 데이터 (className → parsed) ──
    private Map<String, ComponentDetailParsed> componentDetailParsedMap = new LinkedHashMap<>();

    // ── MAT Actions (Histogram / Thread Overview) ─────
    private String histogramHtml = "";
    private String threadOverviewHtml = "";

    // ── Parsed Histogram / Thread data ──────────────────
    private List<HistogramEntry> histogramEntries = new ArrayList<>();
    private List<ThreadInfo> threadInfos = new ArrayList<>();
    private int totalHistogramClasses;

    // ── 파싱 성공 여부 ───────────────────────────────
    public boolean hasData() {
        return totalHeapSize > 0 || !topMemoryObjects.isEmpty();
    }
}

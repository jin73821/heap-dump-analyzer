package com.heapdump.analyzer.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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

    // ── 파싱 성공 여부 ───────────────────────────────
    public boolean hasData() {
        return totalHeapSize > 0 || !topMemoryObjects.isEmpty();
    }
}

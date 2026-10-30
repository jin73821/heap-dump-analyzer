package com.heapdump.analyzer.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.StringJoiner;

public class DetectionSummaryItem {
    /** 높은 심각도부터 — 파생 getter 3종이 이 순서를 공유한다. */
    private static final String[] SEVERITIES = {"critical", "high", "medium", "low"};

    private String filename;
    private int suspectCount;
    private int criticalCount;
    private int highCount;
    private int mediumCount;
    private int lowCount;
    private boolean fileDeleted;

    public String  getFilename()      { return filename; }
    public void    setFilename(String v) { filename = v; }
    public int     getSuspectCount()  { return suspectCount; }
    public void    setSuspectCount(int v) { suspectCount = v; }
    public int     getCriticalCount() { return criticalCount; }
    public void    setCriticalCount(int v) { criticalCount = v; }
    public int     getHighCount()     { return highCount; }
    public void    setHighCount(int v) { highCount = v; }
    public int     getMediumCount()   { return mediumCount; }
    public void    setMediumCount(int v) { mediumCount = v; }
    public int     getLowCount()      { return lowCount; }
    public void    setLowCount(int v) { lowCount = v; }
    public boolean isFileDeleted()    { return fileDeleted; }
    public void    setFileDeleted(boolean v) { fileDeleted = v; }

    private int[] severityCounts() { return new int[]{criticalCount, highCount, mediumCount, lowCount}; }

    /** 가장 높은 심각도 이름. 집계가 전부 0 이면 null — 화면은 칩을 그리지 않는다. */
    @JsonIgnore
    public String getTopSeverity() {
        int[] counts = severityCounts();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) return SEVERITIES[i];
        }
        return null;
    }

    /** 대시보드 탐지 현황 요약 칩 — 가장 높은 심각도와 그 건수 + 나머지 건수. 예: {@code critical x3 +9}. */
    @JsonIgnore
    public String getSeverityChip() {
        int[] counts = severityCounts();
        int total = 0;
        for (int c : counts) total += c;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                int rest = total - counts[i];
                return SEVERITIES[i] + " x" + counts[i] + (rest > 0 ? " +" + rest : "");
            }
        }
        return null;
    }

    /** 요약 칩 툴팁 — 건수가 있는 심각도만 한 줄씩. */
    @JsonIgnore
    public String getSeverityTip() {
        int[] counts = severityCounts();
        StringJoiner lines = new StringJoiner("\n");
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) lines.add(SEVERITIES[i] + " " + counts[i] + "건");
        }
        return lines.toString();
    }
}

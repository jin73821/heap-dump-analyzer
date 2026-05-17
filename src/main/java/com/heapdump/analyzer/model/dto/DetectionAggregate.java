package com.heapdump.analyzer.model.dto;

import java.util.List;

public class DetectionAggregate {
    private List<String> labels;
    private List<ServerSeries> serverSeries;
    private List<ServerSeries> severitySeries;
    private List<DailyDetection> dailyDetections;
    private List<DetectionSummaryItem> detectionItems;
    private List<DetectionRecentItem> recent;
    private int criticalCount, highCount, mediumCount, lowCount;
    private int total, last7d, prev7d, peakCount;
    private Integer delta7d;
    private String peakDay;

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> v) { labels = v; }
    public List<ServerSeries> getServerSeries() { return serverSeries; }
    public void setServerSeries(List<ServerSeries> v) { serverSeries = v; }
    public List<ServerSeries> getSeveritySeries() { return severitySeries; }
    public void setSeveritySeries(List<ServerSeries> v) { severitySeries = v; }
    public List<DailyDetection> getDailyDetections() { return dailyDetections; }
    public void setDailyDetections(List<DailyDetection> v) { dailyDetections = v; }
    public List<DetectionSummaryItem> getDetectionItems() { return detectionItems; }
    public void setDetectionItems(List<DetectionSummaryItem> v) { detectionItems = v; }
    public List<DetectionRecentItem> getRecent() { return recent; }
    public void setRecent(List<DetectionRecentItem> v) { recent = v; }
    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int v) { criticalCount = v; }
    public int getHighCount() { return highCount; }
    public void setHighCount(int v) { highCount = v; }
    public int getMediumCount() { return mediumCount; }
    public void setMediumCount(int v) { mediumCount = v; }
    public int getLowCount() { return lowCount; }
    public void setLowCount(int v) { lowCount = v; }
    public int getTotal() { return total; }
    public void setTotal(int v) { total = v; }
    public int getLast7d() { return last7d; }
    public void setLast7d(int v) { last7d = v; }
    public int getPrev7d() { return prev7d; }
    public void setPrev7d(int v) { prev7d = v; }
    public Integer getDelta7d() { return delta7d; }
    public void setDelta7d(Integer v) { delta7d = v; }
    public String getPeakDay() { return peakDay; }
    public void setPeakDay(String v) { peakDay = v; }
    public int getPeakCount() { return peakCount; }
    public void setPeakCount(int v) { peakCount = v; }
}

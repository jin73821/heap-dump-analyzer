package com.heapdump.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 힙 분석 결과 모델
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeapAnalysisResult {
    
    /**
     * 파일명
     */
    private String filename;
    
    /**
     * 파일 크기 (바이트)
     */
    private long fileSize;
    
    /**
     * 수정 시간 (밀리초)
     */
    private long lastModified;
    
    /**
     * 파일 형식
     */
    private String format;
    
    /**
     * 총 힙 크기 (바이트)
     */
    private long totalHeapSize;
    
    /**
     * 사용된 힙 크기 (바이트)
     */
    private long usedHeapSize;
    
    /**
     * 여유 힙 크기 (바이트)
     */
    private long freeHeapSize;
    
    /**
     * 힙 사용률 (%)
     */
    private double heapUsagePercent;
    
    /**
     * 상위 메모리 객체 리스트
     */
    private List<MemoryObject> topMemoryObjects = new ArrayList<>();
    
    /**
     * 클래스 개수
     */
    private int totalClasses;
    
    /**
     * 객체 개수
     */
    private long totalObjects;
    
    /**
     * 분석 시간 (밀리초)
     */
    private long analysisTime;
    
    /**
     * 분석 상태
     */
    private AnalysisStatus analysisStatus;
    
    /**
     * 에러 메시지
     */
    private String errorMessage;
    
    /**
     * 분석 상태 enum
     */
    public enum AnalysisStatus {
        SUCCESS,
        ERROR
    }
    
    /**
     * 파일 크기를 읽기 쉬운 형식으로 변환
     */
    public String getFormattedFileSize() {
        return formatBytes(fileSize);
    }
    
    /**
     * 총 힙 크기를 읽기 쉬운 형식으로 변환
     */
    public String getFormattedTotalHeapSize() {
        return formatBytes(totalHeapSize);
    }
    
    /**
     * 사용된 힙 크기를 읽기 쉬운 형식으로 변환
     */
    public String getFormattedUsedHeapSize() {
        return formatBytes(usedHeapSize);
    }
    
    /**
     * 여유 힙 크기를 읽기 쉬운 형식으로 변환
     */
    public String getFormattedFreeHeapSize() {
        return formatBytes(freeHeapSize);
    }
    
    /**
     * 힙 사용률을 형식화하여 반환
     */
    public String getFormattedHeapUsagePercent() {
        return String.format("%.2f%%", heapUsagePercent);
    }
    
    /**
     * 클래스 개수를 형식화하여 반환
     */
    public String getFormattedTotalClasses() {
        return String.format("%,d", totalClasses);
    }
    
    /**
     * 객체 개수를 형식화하여 반환
     */
    public String getFormattedTotalObjects() {
        return String.format("%,d", totalObjects);
    }
    
    /**
     * 바이트를 읽기 쉬운 형식으로 변환하는 헬퍼 메서드
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}

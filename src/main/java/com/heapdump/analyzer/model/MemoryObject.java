package com.heapdump.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 메모리 객체 정보 모델
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryObject {
    
    /**
     * 클래스명
     */
    private String className;
    
    /**
     * 객체 개수
     */
    private long objectCount;
    
    /**
     * 총 메모리 크기 (바이트)
     */
    private long totalSize;
    
    /**
     * 힙 메모리 점유율 (%)
     */
    private double percentOfHeap;
    
    /**
     * 메모리 크기를 읽기 쉬운 형식으로 변환
     * @return 형식화된 메모리 크기
     */
    public String getFormattedSize() {
        if (totalSize < 1024) {
            return totalSize + " B";
        } else if (totalSize < 1024 * 1024) {
            return String.format("%.2f KB", totalSize / 1024.0);
        } else if (totalSize < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", totalSize / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", totalSize / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 객체 개수를 천 단위 구분하여 반환
     * @return 형식화된 객체 개수
     */
    public String getFormattedObjectCount() {
        return String.format("%,d", objectCount);
    }
    
    /**
     * 퍼센트를 형식화하여 반환
     * @return 형식화된 퍼센트 (예: 15.50%)
     */
    public String getFormattedPercent() {
        return String.format("%.2f%%", percentOfHeap);
    }
}

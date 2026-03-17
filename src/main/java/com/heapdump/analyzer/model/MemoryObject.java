package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 메모리 객체 정보 모델
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryObject {

    private String className;
    private long   objectCount;
    private long   totalSize;
    private double percentOfHeap;

    public String getFormattedSize() {
        if (totalSize < 1024)                  return totalSize + " B";
        if (totalSize < 1024 * 1024)           return String.format("%.2f KB", totalSize / 1024.0);
        if (totalSize < 1024L * 1024 * 1024)   return String.format("%.2f MB", totalSize / (1024.0 * 1024));
        return String.format("%.2f GB", totalSize / (1024.0 * 1024 * 1024));
    }

    public String getFormattedObjectCount() { return String.format("%,d", objectCount); }
    public String getFormattedPercent()      { return String.format("%.2f%%", percentOfHeap); }
}

package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistogramEntry {
    private String className;
    private long objectCount;
    private long shallowHeap;
    private long retainedHeap;
    private String retainedHeapDisplay; // ">= 43,994,024" format

    /**
     * Returns human-readable retained heap (e.g., ">= 973.4 MB").
     * Preserves ">=" prefix if present in retainedHeapDisplay.
     */
    public String getRetainedHeapHuman() {
        String prefix = "";
        if (retainedHeapDisplay != null && retainedHeapDisplay.contains(">=")) {
            prefix = "≥ ";
        }
        return prefix + formatBytes(retainedHeap);
    }

    /**
     * Returns human-readable shallow heap (e.g., "45.2 MB").
     */
    public String getShallowHeapHuman() {
        return formatBytes(shallowHeap);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

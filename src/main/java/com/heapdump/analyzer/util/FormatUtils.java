package com.heapdump.analyzer.util;

/**
 * 공통 포맷 유틸리티
 */
public final class FormatUtils {

    private FormatUtils() {}

    public static String formatBytes(long bytes) {
        if (bytes <= 0)                  return "0 B";
        if (bytes < 1024)                return bytes + " B";
        if (bytes < 1024 * 1024)         return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

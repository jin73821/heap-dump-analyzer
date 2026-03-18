package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThreadInfo {
    private String name;
    private String objectType;
    private long shallowHeap;
    private long retainedHeap;
    private String contextClassLoader;
    private boolean daemon;
    private String address;      // 0xc1299f88
    private String stackTrace;   // from .threads file

    public String getFormattedShallowHeap() { return String.format("%,d", shallowHeap); }
    public String getFormattedRetainedHeap() { return String.format("%,d", retainedHeap); }
    public String getShortObjectType() {
        if (objectType == null) return "";
        // Remove @ 0x... address
        String s = objectType.replaceAll("@\\s*0x[0-9a-fA-F]+", "").trim();
        // Remove >> character
        s = s.replace("\u00BB", "").replace("\u00BB", "").trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
    public String getShortClassLoader() {
        if (contextClassLoader == null || contextClassLoader.isEmpty()) return "-";
        String s = contextClassLoader.replaceAll("@\\s*0x[0-9a-fA-F]+", "").trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}

package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentMetadata {
    private String sizeDisplay;    // "42.3 MB"
    private long   sizeBytes;
    private int    classCount;
    private long   objectCount;
    private String classLoader;
}

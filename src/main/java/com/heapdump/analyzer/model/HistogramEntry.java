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
}

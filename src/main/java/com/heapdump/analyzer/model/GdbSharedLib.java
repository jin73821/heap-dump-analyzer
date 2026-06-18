package com.heapdump.analyzer.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GdbSharedLib {
    private String fromAddr;
    private String toAddr;
    private String symsRead;  // "Yes", "No", "Yes (*)" 등
    private String path;
}

package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TreeNode {
    private String label;
    private String objectRef;      // hex address (0x...)
    private int    depth;
    private List<String> columns = new ArrayList<>();   // additional column values
    private List<TreeNode> children = new ArrayList<>();
}

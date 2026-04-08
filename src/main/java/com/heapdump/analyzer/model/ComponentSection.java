package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentSection {
    private String id;
    private String title;
    private SectionType type;
    private String description;
    private String severity;       // "warning", "error", or null
    private List<TableData> tables = new ArrayList<>();
    private List<TreeNode> treeRoots = new ArrayList<>();
    private List<String> treeHeaders = new ArrayList<>();
    private String textContent;
    private int level;             // heading level (2-5)
    private List<ComponentSection> children = new ArrayList<>();

    public enum SectionType { TABLE, TREE, TEXT, UNKNOWN }
}

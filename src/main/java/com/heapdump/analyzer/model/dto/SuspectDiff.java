package com.heapdump.analyzer.model.dto;

public class SuspectDiff {
    // state: NEW(타겟에만) / GONE(베이스에만) / PERSIST(양쪽 동일 severity) / SEVERITY_CHANGED
    private final String key;
    private final String state;
    private final String title;
    private final String category;
    private final String description;
    private final String baseSeverity;
    private final String targetSeverity;

    public SuspectDiff(String key, String state, String title, String category, String description,
                       String baseSeverity, String targetSeverity) {
        this.key            = key;
        this.state          = state;
        this.title          = title;
        this.category       = category;
        this.description    = description;
        this.baseSeverity   = baseSeverity;
        this.targetSeverity = targetSeverity;
    }
    public String getKey()            { return key; }
    public String getState()          { return state; }
    public String getTitle()          { return title; }
    public String getCategory()       { return category; }
    public String getDescription()    { return description; }
    public String getBaseSeverity()   { return baseSeverity; }
    public String getTargetSeverity() { return targetSeverity; }
}

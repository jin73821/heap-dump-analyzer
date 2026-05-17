package com.heapdump.analyzer.model.dto;

public class DetectionRecentItem {
    private String  filename;
    private String  analysisName;
    private String  serverName;
    private String  severity;
    private String  title;
    private String  category;
    private long    analyzedAtEpoch;
    private String  dateLabel;
    private boolean fileDeleted;

    public String  getFilename()        { return filename; }
    public void    setFilename(String v) { filename = v; }
    public String  getAnalysisName()    { return analysisName; }
    public void    setAnalysisName(String v) { analysisName = v; }
    public String  getServerName()      { return serverName; }
    public void    setServerName(String v) { serverName = v; }
    public String  getSeverity()        { return severity; }
    public void    setSeverity(String v) { severity = v; }
    public String  getTitle()           { return title; }
    public void    setTitle(String v)   { title = v; }
    public String  getCategory()        { return category; }
    public void    setCategory(String v) { category = v; }
    public long    getAnalyzedAtEpoch() { return analyzedAtEpoch; }
    public void    setAnalyzedAtEpoch(long v) { analyzedAtEpoch = v; }
    public String  getDateLabel()       { return dateLabel; }
    public void    setDateLabel(String v) { dateLabel = v; }
    public boolean isFileDeleted()      { return fileDeleted; }
    public void    setFileDeleted(boolean v) { fileDeleted = v; }
}

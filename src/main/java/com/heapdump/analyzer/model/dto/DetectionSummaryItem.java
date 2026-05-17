package com.heapdump.analyzer.model.dto;

public class DetectionSummaryItem {
    private String filename;
    private int suspectCount;
    private int criticalCount;
    private int highCount;
    private int mediumCount;
    private int lowCount;
    private boolean fileDeleted;

    public String  getFilename()      { return filename; }
    public void    setFilename(String v) { filename = v; }
    public int     getSuspectCount()  { return suspectCount; }
    public void    setSuspectCount(int v) { suspectCount = v; }
    public int     getCriticalCount() { return criticalCount; }
    public void    setCriticalCount(int v) { criticalCount = v; }
    public int     getHighCount()     { return highCount; }
    public void    setHighCount(int v) { highCount = v; }
    public int     getMediumCount()   { return mediumCount; }
    public void    setMediumCount(int v) { mediumCount = v; }
    public int     getLowCount()      { return lowCount; }
    public void    setLowCount(int v) { lowCount = v; }
    public boolean isFileDeleted()    { return fileDeleted; }
    public void    setFileDeleted(boolean v) { fileDeleted = v; }
}

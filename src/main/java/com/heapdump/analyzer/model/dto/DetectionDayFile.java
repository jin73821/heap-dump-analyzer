package com.heapdump.analyzer.model.dto;

public class DetectionDayFile {
    private String filename;
    private String analysisName;
    private String serverName;
    private int suspectCount, criticalCount, highCount, mediumCount, lowCount;
    private boolean fileDeleted;
    private long analyzedAtEpoch;

    public String  getFilename()      { return filename; }
    public void    setFilename(String v) { filename = v; }
    public String  getAnalysisName()  { return analysisName; }
    public void    setAnalysisName(String v) { analysisName = v; }
    public String  getServerName()    { return serverName; }
    public void    setServerName(String v) { serverName = v; }
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
    public long    getAnalyzedAtEpoch() { return analyzedAtEpoch; }
    public void    setAnalyzedAtEpoch(long v) { analyzedAtEpoch = v; }
}

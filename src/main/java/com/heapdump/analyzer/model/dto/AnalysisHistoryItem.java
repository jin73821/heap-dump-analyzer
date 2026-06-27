package com.heapdump.analyzer.model.dto;

public class AnalysisHistoryItem {
    private Long    id;
    private String  filename;
    private String  analysisName;
    private String  formattedSize;
    private long    sizeBytes;
    private long    originalSizeBytes;
    private long    compressedSizeBytes;
    private String  formattedDate;
    private String  status;
    private int     suspectCount;
    private long    analysisTime;
    private String  formattedAnalysisTime;
    private String  heapUsed;
    private long    heapUsedBytes;
    private boolean fileDeleted;
    private long    lastModified;
    private boolean compressed;
    private String  formattedOriginalSize;
    private String  formattedCompressedSize;
    private String  serverName;
    private boolean hasAiInsight;
    private String  aiInsightSeverity;
    private long    analyzedAtEpoch;
    private String  dumpCreationTime;
    private String  fileType = "heapdump";
    private boolean hasExec;
    private String  pairedExecFilename;
    private boolean pairedExec;
    private boolean fromCoreDir;

    public Long    getId()            { return id; }
    public void    setId(Long v)              { id = v; }
    public long    getSizeBytes()       { return sizeBytes; }
    public void    setSizeBytes(long v)         { sizeBytes = v; }
    public long    getOriginalSizeBytes()   { return originalSizeBytes; }
    public void    setOriginalSizeBytes(long v) { originalSizeBytes = v; }
    public long    getCompressedSizeBytes() { return compressedSizeBytes; }
    public void    setCompressedSizeBytes(long v) { compressedSizeBytes = v; }
    public long    getHeapUsedBytes()   { return heapUsedBytes; }
    public void    setHeapUsedBytes(long v)     { heapUsedBytes = v; }
    public String  getFilename()      { return filename; }
    public void    setFilename(String v)      { filename = v; }
    public String  getAnalysisName()  { return analysisName; }
    public void    setAnalysisName(String v) { analysisName = v; }
    public String  getFormattedSize() { return formattedSize; }
    public void    setFormattedSize(String v) { formattedSize = v; }
    public String  getFormattedDate() { return formattedDate; }
    public void    setFormattedDate(String v) { formattedDate = v; }
    public String  getStatus()        { return status; }
    public void    setStatus(String v)        { status = v; }
    public int     getSuspectCount()  { return suspectCount; }
    public void    setSuspectCount(int v)     { suspectCount = v; }
    public long    getAnalysisTime()  { return analysisTime; }
    public void    setAnalysisTime(long v)    { analysisTime = v; }
    public String  getFormattedAnalysisTime() { return formattedAnalysisTime; }
    public void    setFormattedAnalysisTime(String v) { formattedAnalysisTime = v; }
    public String  getHeapUsed()      { return heapUsed; }
    public void    setHeapUsed(String v)      { heapUsed = v; }
    public boolean isFileDeleted()    { return fileDeleted; }
    public void    setFileDeleted(boolean v)  { fileDeleted = v; }
    public long    getLastModified()  { return lastModified; }
    public void    setLastModified(long v)    { lastModified = v; }
    public boolean isCompressed()    { return compressed; }
    public void    setCompressed(boolean v)   { compressed = v; }
    public String  getFormattedOriginalSize() { return formattedOriginalSize; }
    public void    setFormattedOriginalSize(String v) { formattedOriginalSize = v; }
    public String  getFormattedCompressedSize() { return formattedCompressedSize; }
    public void    setFormattedCompressedSize(String v) { formattedCompressedSize = v; }
    public String  getServerName()    { return serverName; }
    public void    setServerName(String v)    { serverName = v; }
    public boolean isHasAiInsight()  { return hasAiInsight; }
    public void    setHasAiInsight(boolean v) { hasAiInsight = v; }
    public String  getAiInsightSeverity() { return aiInsightSeverity; }
    public void    setAiInsightSeverity(String v) { aiInsightSeverity = v; }
    public long    getAnalyzedAtEpoch() { return analyzedAtEpoch; }
    public void    setAnalyzedAtEpoch(long v) { analyzedAtEpoch = v; }
    public String  getDumpCreationTime() { return dumpCreationTime; }
    public void    setDumpCreationTime(String v) { dumpCreationTime = v; }
    public String  getFileType()         { return fileType; }
    public void    setFileType(String v)          { fileType = v; }
    public boolean isHasExec()           { return hasExec; }
    public void    setHasExec(boolean v)          { hasExec = v; }
    public String  getPairedExecFilename()        { return pairedExecFilename; }
    public void    setPairedExecFilename(String v){ pairedExecFilename = v; }
    public boolean isPairedExec()                 { return pairedExec; }
    public void    setPairedExec(boolean v)       { pairedExec = v; }
    public boolean isFromCoreDir()                { return fromCoreDir; }
    public void    setFromCoreDir(boolean v)      { fromCoreDir = v; }
}

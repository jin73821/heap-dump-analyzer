package com.heapdump.analyzer.model.dto;

/**
 * 코어 덤프의 보존된 과거 분석 결과 1건 (data/{core}/revisions/{id}/).
 * 재분석 시 기존 result.json 을 덮어쓰지 않고 이 디렉토리로 이관해 보존한다.
 */
public class CoreDumpRevision {

    private String  id;                 // 디렉토리명 = yyyyMMdd-HHmmss[-N]
    private String  label;              // 화면 표기용 (예: "2026-07-17 14:20 · exec 없음")
    private String  analyzedAt;         // result.json 의 analyzedAt (ISO-8601). 없으면 null
    private String  executableName;     // 해당 분석에 사용된 exec. 없으면 null
    private String  crashSignal;
    private long    archivedAtEpoch;    // 아카이브 디렉토리 mtime — 정렬 키

    public String getId()                       { return id; }
    public void   setId(String v)               { id = v; }
    public String getLabel()                    { return label; }
    public void   setLabel(String v)            { label = v; }
    public String getAnalyzedAt()               { return analyzedAt; }
    public void   setAnalyzedAt(String v)       { analyzedAt = v; }
    public String getExecutableName()           { return executableName; }
    public void   setExecutableName(String v)   { executableName = v; }
    public String getCrashSignal()              { return crashSignal; }
    public void   setCrashSignal(String v)      { crashSignal = v; }
    public long   getArchivedAtEpoch()          { return archivedAtEpoch; }
    public void   setArchivedAtEpoch(long v)    { archivedAtEpoch = v; }
}

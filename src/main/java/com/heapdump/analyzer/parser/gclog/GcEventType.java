package com.heapdump.analyzer.parser.gclog;

/** GC 이벤트 유형. {@code isPause()} 가 true 인 유형만 일시정지 통계에 들어간다. */
public enum GcEventType {
    YOUNG("Young", true),
    MIXED("Mixed", true),
    FULL("Full", true),
    REMARK("Remark", true),
    CLEANUP("Cleanup", true),
    CMS_INITIAL_MARK("CMS Initial Mark", true),
    CMS_FINAL_REMARK("CMS Final Remark", true),
    CONCURRENT_CYCLE("Concurrent", false),
    OTHER("Other", true);

    private final String label;
    private final boolean pause;

    GcEventType(String label, boolean pause) { this.label = label; this.pause = pause; }

    public String label() { return label; }
    public boolean isPause() { return pause; }
}

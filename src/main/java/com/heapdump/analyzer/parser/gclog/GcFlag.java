package com.heapdump.analyzer.parser.gclog;

/** 이벤트에 붙는 이상 징후·원인 플래그. 소견 규칙이 카운트한다. */
public enum GcFlag {
    TO_SPACE_EXHAUSTED,
    HUMONGOUS_ALLOC,
    CONCURRENT_MODE_FAILURE,
    PROMOTION_FAILED,
    METADATA_THRESHOLD,
    SYSTEM_GC,
    GC_LOCKER,
    ERGONOMICS,
    ALLOCATION_FAILURE,
    INITIAL_MARK;

    /** 이벤트 표에 우선 보존할 "이상" 플래그 — 정상 원인(Allocation Failure/Ergonomics/Initial Mark)은 제외. */
    public boolean isAbnormal() {
        switch (this) {
            case ALLOCATION_FAILURE: case ERGONOMICS: case INITIAL_MARK: return false;
            default: return true;
        }
    }

    public static boolean anyAbnormal(java.util.Collection<String> names) {
        for (String n : names) {
            try { if (GcFlag.valueOf(n).isAbnormal()) return true; } catch (IllegalArgumentException e) { return true; }
        }
        return false;
    }
}

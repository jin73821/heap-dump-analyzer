package com.heapdump.analyzer.parser.gclog;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Full GC 가 <b>왜</b> 일어났는지 — 메모리 압박 판정의 단위(2026-09-16). "명시적을 뺀 Full GC" 만으로는 Metaspace 임계치·GCLocker 까지
 * 압박으로 세고, 회수율·직후 점유율을 묻지 못했다. 첫 일치 우선: 명시적 → Metaspace → GCLocker → 힙 압박 → 기타.
 */
public enum FullGcKind {
    /** System.gc()·힙 덤프·jmap·jcmd — 메모리 압박 신호가 아니다. */
    EXPLICIT,
    /** Metadata GC Threshold — Metaspace 가 찼다(힙 아님). */
    METASPACE,
    /** GCLocker Initiated GC — JNI critical 구간이 GC 를 미뤘다가 터뜨린 것. */
    GC_LOCKER,
    /** 힙이 차서(Allocation Failure·Ergonomics·승격 실패·concurrent mode failure·to-space exhausted·JDK 6/7 무원인). */
    HEAP_PRESSURE,
    OTHER;

    private static final Set<GcFlag> PRESSURE_FLAGS = EnumSet.of(GcFlag.ALLOCATION_FAILURE, GcFlag.ERGONOMICS, GcFlag.PROMOTION_FAILED,
            GcFlag.CONCURRENT_MODE_FAILURE, GcFlag.TO_SPACE_EXHAUSTED, GcFlag.HUMONGOUS_ALLOC);

    static FullGcKind of(GcEvent ev) {
        if (ev.flags.contains(GcFlag.SYSTEM_GC) || GcLogSupport.isExplicitCause(ev.cause)) return EXPLICIT;
        String c = ev.cause == null ? null : ev.cause.toLowerCase(Locale.ROOT);
        if (ev.flags.contains(GcFlag.METADATA_THRESHOLD) || (c != null && c.contains("metadata"))) return METASPACE;
        if (ev.flags.contains(GcFlag.GC_LOCKER)) return GC_LOCKER;
        if (c == null) return HEAP_PRESSURE;                                  // JDK 6/7 [Full GC 무괄호 · [GC [ParNew][CMS] 재분류
        for (GcFlag f : PRESSURE_FLAGS) if (ev.flags.contains(f)) return HEAP_PRESSURE;
        if (GcLogSupport.isPressureCause(c)) return HEAP_PRESSURE;
        if (ev.text != null && ev.text.contains("Degenerated")) return HEAP_PRESSURE;   // Shenandoah Pause Degenerated GC (Mark)
        return OTHER;
    }
}

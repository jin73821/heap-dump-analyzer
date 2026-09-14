package com.heapdump.analyzer.parser.gclog;

import java.util.EnumSet;
import java.util.Set;

/**
 * 파서가 만들어 sink 로 흘려보내는 GC 이벤트 하나. 파서 안에서는 가변으로 채우고 {@link #freeze()} 로 넘긴다.
 * 바이트 필드는 전부 bytes 단위(null = 로그에 없음), 시간은 {@code tsEpochMs}(절대, 없을 수 있음)와
 * {@code uptimeSec}(JVM 기동 후 초, 없을 수 있음) 두 축이다.
 */
public final class GcEvent {

    public int seq;
    public int line;
    public Long tsEpochMs;
    public Double uptimeSec;
    public GcEventType type = GcEventType.OTHER;
    public String cause;
    public Double pauseMs;
    public boolean concurrent;
    public Long heapBefore, heapAfter, heapTotal;
    public Long youngBefore, youngAfter, youngTotal;
    public Long oldBefore, oldAfter, oldTotal;
    public Long metaBefore, metaAfter, metaTotal;
    public Integer humongousRegions;
    public Double userSec, sysSec, realSec;
    public final Set<GcFlag> flags = EnumSet.noneOf(GcFlag.class);
    /** 원문 요약(첫 줄, 200자 절단) — 이벤트 표·소견 근거용. */
    public String text;

    public boolean isPause() { return !concurrent && type.isPause() && pauseMs != null; }

    public GcEvent freeze() {
        if (text != null && text.length() > 200) text = text.substring(0, 200);
        return this;
    }

    /** 소견 근거 문자열 — "GC(12) Pause Full 1,234ms @ 1234.5s". */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.label());
        if (cause != null) sb.append(" (").append(cause).append(')');
        if (pauseMs != null) sb.append(' ').append(String.format(java.util.Locale.ROOT, "%.1fms", pauseMs));
        if (uptimeSec != null) sb.append(" @ ").append(String.format(java.util.Locale.ROOT, "%.1fs", uptimeSec));
        return sb.toString();
    }
}

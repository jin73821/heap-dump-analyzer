package com.heapdump.analyzer.model.dto;

public class HistogramDiff {
    private final String className;
    private final long baseCount;
    private final long targetCount;
    private final long countDelta;
    private final long baseRetained;
    private final long targetRetained;
    private final long retainedDelta;

    public HistogramDiff(String className,
                         long baseCount, long targetCount,
                         long baseRetained, long targetRetained) {
        this.className      = className;
        this.baseCount      = baseCount;
        this.targetCount    = targetCount;
        this.countDelta     = targetCount - baseCount;
        this.baseRetained   = baseRetained;
        this.targetRetained = targetRetained;
        this.retainedDelta  = targetRetained - baseRetained;
    }
    public String getClassName()      { return className; }
    public long   getBaseCount()      { return baseCount; }
    public long   getTargetCount()    { return targetCount; }
    public long   getCountDelta()     { return countDelta; }
    public long   getBaseRetained()   { return baseRetained; }
    public long   getTargetRetained() { return targetRetained; }
    public long   getRetainedDelta()  { return retainedDelta; }
    public boolean isIncrease()       { return retainedDelta > 0; }
    public String getFormattedCountDelta() {
        String sign = countDelta > 0 ? "+" : "";
        return sign + String.format("%,d", countDelta);
    }
    public String getFormattedRetainedDelta() {
        String sign = retainedDelta > 0 ? "+" : "";
        long abs = Math.abs(retainedDelta);
        if (abs < 1024) return sign + retainedDelta + " B";
        if (abs < 1048576) return sign + String.format("%.1f KB", retainedDelta / 1024.0);
        return sign + String.format("%.2f MB", retainedDelta / (1024.0 * 1024));
    }
}

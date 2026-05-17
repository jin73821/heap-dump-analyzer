package com.heapdump.analyzer.model.dto;

public class KpiDiff {
    private final long usedHeapDelta;
    private final long totalHeapDelta;
    private final long freeHeapDelta;
    private final double usagePercentDelta;
    private final long objectsDelta;
    private final int  classesDelta;
    private final int  suspectsDelta;
    private final int  threadsDelta;
    private final int  topConsumerCountDelta;

    public KpiDiff(long usedHeapDelta, long totalHeapDelta, long freeHeapDelta,
                   double usagePercentDelta, long objectsDelta, int classesDelta,
                   int suspectsDelta, int threadsDelta, int topConsumerCountDelta) {
        this.usedHeapDelta          = usedHeapDelta;
        this.totalHeapDelta         = totalHeapDelta;
        this.freeHeapDelta          = freeHeapDelta;
        this.usagePercentDelta      = usagePercentDelta;
        this.objectsDelta           = objectsDelta;
        this.classesDelta           = classesDelta;
        this.suspectsDelta          = suspectsDelta;
        this.threadsDelta           = threadsDelta;
        this.topConsumerCountDelta  = topConsumerCountDelta;
    }
    public long   getUsedHeapDelta()         { return usedHeapDelta; }
    public long   getTotalHeapDelta()        { return totalHeapDelta; }
    public long   getFreeHeapDelta()         { return freeHeapDelta; }
    public double getUsagePercentDelta()     { return usagePercentDelta; }
    public long   getObjectsDelta()          { return objectsDelta; }
    public int    getClassesDelta()          { return classesDelta; }
    public int    getSuspectsDelta()         { return suspectsDelta; }
    public int    getThreadsDelta()          { return threadsDelta; }
    public int    getTopConsumerCountDelta() { return topConsumerCountDelta; }

    public boolean isUsedHeapUp()        { return usedHeapDelta > 0; }
    public boolean isUsagePercentUp()    { return usagePercentDelta > 0; }
    public boolean isObjectsUp()         { return objectsDelta > 0; }
    public boolean isClassesUp()         { return classesDelta > 0; }
    public boolean isSuspectsUp()        { return suspectsDelta > 0; }
    public boolean isThreadsUp()         { return threadsDelta > 0; }
    public boolean isTopConsumerCountUp(){ return topConsumerCountDelta > 0; }

    public String getFormattedUsedHeapDelta() {
        return formatBytesDelta(usedHeapDelta);
    }
    public String getFormattedUsagePercentDelta() {
        String sign = usagePercentDelta > 0 ? "+" : "";
        return sign + String.format("%.2f%%", usagePercentDelta);
    }
    public String getFormattedObjectsDelta() {
        String sign = objectsDelta > 0 ? "+" : "";
        return sign + String.format("%,d", objectsDelta);
    }
    public String getFormattedClassesDelta() {
        String sign = classesDelta > 0 ? "+" : "";
        return sign + String.format("%,d", classesDelta);
    }
    public String getFormattedSuspectsDelta() {
        String sign = suspectsDelta > 0 ? "+" : "";
        return sign + suspectsDelta;
    }
    public String getFormattedThreadsDelta() {
        String sign = threadsDelta > 0 ? "+" : "";
        return sign + threadsDelta;
    }
    public String getFormattedTopConsumerCountDelta() {
        String sign = topConsumerCountDelta > 0 ? "+" : "";
        return sign + topConsumerCountDelta;
    }
    private static String formatBytesDelta(long bytes) {
        String sign = bytes > 0 ? "+" : (bytes < 0 ? "-" : "");
        long abs = Math.abs(bytes);
        if (abs < 1024) return sign + abs + " B";
        if (abs < 1048576) return sign + String.format("%.1f KB", abs / 1024.0);
        if (abs < 1073741824L) return sign + String.format("%.2f MB", abs / (1024.0 * 1024));
        return sign + String.format("%.2f GB", abs / (1024.0 * 1024 * 1024));
    }
}

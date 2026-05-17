package com.heapdump.analyzer.model.dto;

public class ClassDiff {
    private final String className;
    private final long   baseSize;
    private final long   targetSize;
    private final long   delta;

    public ClassDiff(String className, long baseSize, long targetSize, long delta) {
        this.className  = className;
        this.baseSize   = baseSize;
        this.targetSize = targetSize;
        this.delta      = delta;
    }
    public String getClassName()  { return className; }
    public long   getBaseSize()   { return baseSize; }
    public long   getTargetSize() { return targetSize; }
    public long   getDelta()      { return delta; }
    public String getFormattedDelta() {
        String sign = delta > 0 ? "+" : "";
        long abs = Math.abs(delta);
        if (abs < 1024) return sign + delta + " B";
        if (abs < 1048576) return sign + String.format("%.1f KB", delta / 1024.0);
        return sign + String.format("%.2f MB", delta / (1024.0 * 1024));
    }
    public boolean isIncrease() { return delta > 0; }
}

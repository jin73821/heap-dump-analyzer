package com.heapdump.analyzer.util;

/**
 * MAT Leak Suspect 텍스트에서 추출된 동적 컨텍스트. 템플릿 렌더링 시 placeholder / 조건분기에 사용됨.
 * 자유 표현식(예: simpleClassName.toLowerCase().contains("stream"))은 derived boolean 플래그로 사전 계산해 둠.
 */
public class LeakRuleContext {

    public int instanceCount;
    public String className;
    public String simpleClassName;
    public String classLoader;
    public long bytes;
    public double percentage;
    public String accumulatorClass;
    public String accumulatorSimple;
    public long accumulatorBytes;
    public double accumulatorPercentage;
    public String referencedFromClass;
    public String severity;

    // ── derived flags (parseContext 마지막 단계에서 계산) ─────────────
    public boolean hasAccumulator;
    public boolean hasReferencedFrom;
    public boolean hasInstanceCount;
    public boolean highPercentage;   // >= 30
    public boolean veryHighPercentage; // >= 50
    public boolean streamClass;
    public boolean sessionClass;
    public boolean threadClass;
    public boolean classLoaderClass;
    public boolean cacheClass;
    public boolean mapClass;

    /** 템플릿 placeholder 접근용. 키가 없으면 null 반환. */
    public Object resolve(String key) {
        if (key == null) return null;
        switch (key) {
            case "instanceCount":           return instanceCount;
            case "className":               return className;
            case "simpleClassName":         return simpleClassName;
            case "classLoader":             return classLoader;
            case "bytes":                   return bytes;
            case "percentage":              return percentage;
            case "accumulatorClass":        return accumulatorClass;
            case "accumulatorSimple":       return accumulatorSimple;
            case "accumulatorBytes":        return accumulatorBytes;
            case "accumulatorPercentage":   return accumulatorPercentage;
            case "referencedFromClass":     return referencedFromClass;
            case "severity":                return severity;
            case "hasAccumulator":          return hasAccumulator;
            case "hasReferencedFrom":       return hasReferencedFrom;
            case "hasInstanceCount":        return hasInstanceCount;
            case "highPercentage":          return highPercentage;
            case "veryHighPercentage":      return veryHighPercentage;
            case "streamClass":             return streamClass;
            case "sessionClass":            return sessionClass;
            case "threadClass":             return threadClass;
            case "classLoaderClass":        return classLoaderClass;
            case "cacheClass":              return cacheClass;
            case "mapClass":                return mapClass;
            default: return null;
        }
    }

    /** {#if flag} 분기 평가용. boolean / 0이 아닌 숫자 / non-empty 문자열 → true. */
    public boolean truthy(String key) {
        Object v = resolve(key);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0.0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }
}

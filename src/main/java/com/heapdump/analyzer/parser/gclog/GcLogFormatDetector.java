package com.heapdump.analyzer.parser.gclog;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 첫 N 줄로 형식을 판별한다. 통합 로깅은 줄이 {@code [..][..]} 데코레이션으로 시작하고 태그에 {@code gc} 가 있으며,
 * JDK 8 은 {@code 2026-..T..: 0.325: [GC (} 또는 {@code 0.325: [GC (} / {@code [Full GC (} 로 시작한다.
 * 두 형식 모두 0줄이면 UNKNOWN — 업로드 거부·분석 ERROR 근거.
 */
public final class GcLogFormatDetector {

    private GcLogFormatDetector() {}

    public static final int SNIFF_LINES = 200;

    private static final Pattern UNIFIED = Pattern.compile("^(\\[[^\\]]*\\]){2,}\\s*.*");
    private static final Pattern UNIFIED_GC_TAG = Pattern.compile("\\[(gc|gc,[a-z,]+)\\s*\\]");
    private static final Pattern JDK8 = Pattern.compile(
            "^(?:\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}: )?(?:\\d+\\.\\d+: )?\\[(GC|Full GC|CMS-concurrent)");
    private static final Pattern JDK8_HEADER = Pattern.compile("^(Java HotSpot\\(TM\\)|OpenJDK) .*VM \\(.*\\) for ");

    public static GcLogFormat sniff(List<String> firstLines) {
        int unified = 0, jdk8 = 0;
        for (String raw : firstLines) {
            if (raw == null) continue;
            String line = raw.length() > 1000 ? raw.substring(0, 1000) : raw;
            if (line.isEmpty()) continue;
            if (UNIFIED.matcher(line).matches() && UNIFIED_GC_TAG.matcher(line).find()) unified++;
            else if (JDK8.matcher(line).find() || JDK8_HEADER.matcher(line).find()) jdk8++;
        }
        if (unified == 0 && jdk8 == 0) return GcLogFormat.UNKNOWN;
        return unified >= jdk8 ? GcLogFormat.UNIFIED : GcLogFormat.JDK8;
    }
}

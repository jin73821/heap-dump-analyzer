package com.heapdump.analyzer.parser.gclog;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 두 파서가 공유하는 시각·크기 파싱 헬퍼. */
final class GcLogSupport {

    private GcLogSupport() {}

    /** {@code 2026-09-14T00:52:33.485+0900} — JDK 8 DateStamps 와 통합 로깅 {@code time}/{@code utctime} 공통. */
    static final Pattern ISO_TS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

    /** ISO 문자열 → epoch ms. 형식이 어긋나면 null. */
    static Long parseIsoEpochMs(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s, ISO_FMT).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code 14M}, {@code 33280K}, {@code 24.0M}, {@code 0.0B}, {@code 4G} → bytes. 단위 없으면 bytes 로 본다. */
    static Long parseSize(String num, String unit) {
        if (num == null) return null;
        double v;
        try { v = Double.parseDouble(num); } catch (NumberFormatException e) { return null; }
        long mul = 1L;
        if (unit != null && !unit.isEmpty()) {
            switch (Character.toUpperCase(unit.charAt(0))) {
                case 'K': mul = 1L << 10; break;
                case 'M': mul = 1L << 20; break;
                case 'G': mul = 1L << 30; break;
                case 'T': mul = 1L << 40; break;
                default: mul = 1L;
            }
        }
        return (long) (v * mul);
    }

    private static final Pattern SIZE_TOKEN = Pattern.compile("^(\\d+(?:\\.\\d+)?)([KMGTB]?)B?$");

    /** {@code "1M"}·{@code "4G"}·{@code "1048576"} 한 토큰 → bytes. */
    static Long parseSizeToken(String tok) {
        if (tok == null) return null;
        Matcher m = SIZE_TOKEN.matcher(tok.trim());
        if (!m.matches()) return null;
        return parseSize(m.group(1), m.group(2));
    }

    static Double parseDoubleOrNull(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** 원인 문자열에서 플래그를 뽑는다(두 형식 공통 어휘). */
    static void flagsFromCause(String cause, GcEvent ev) {
        if (cause == null) return;
        String c = cause.toLowerCase(Locale.ROOT);
        if (c.contains("system.gc") || c.equals("system")) ev.flags.add(GcFlag.SYSTEM_GC);   // JDK 6/7 은 [Full GC (System)
        if (c.contains("metadata gc threshold") || c.contains("metadata gc clear")) ev.flags.add(GcFlag.METADATA_THRESHOLD);
        if (c.contains("ergonomics")) ev.flags.add(GcFlag.ERGONOMICS);
        if (c.contains("allocation failure")) ev.flags.add(GcFlag.ALLOCATION_FAILURE);
        if (c.contains("gclocker")) ev.flags.add(GcFlag.GC_LOCKER);
        if (c.contains("humongous")) ev.flags.add(GcFlag.HUMONGOUS_ALLOC);
    }
}

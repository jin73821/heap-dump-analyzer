package com.heapdump.analyzer.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

/**
 * GC 로그 분석명 {@code 호스트네임_인스턴스명_일자} 의 단일 출처 (2026-09-20).
 *
 * <p>파일명이 {@code gc.log}·{@code gc.log.1_20260920_1030} 처럼 서로 비슷해 목록에서 어느 서버 것인지 알 수 없어
 * 도입했다. <b>표시용 이름일 뿐 키가 아니다</b> — 키는 언제나 {@code gc_log_analysis.filename} 이고, 이름이 겹치면
 * {@code _2} 를 붙인다(DB unique 제약을 걸지 않는다).
 *
 * <p>일자는 <b>로그 시작일</b>이다. 자정을 넘는 로그(23:23~10:56)도 시작일 한 건으로 두고 화면에 실제 범위를 보인다 —
 * 파일을 쪼개면 원문·줄 번호 대조가 깨진다. 이어 붙여 여러 날에 걸치면 {@code 20260919-0920} 처럼 범위로 쓴다.
 */
public final class GcLogNameService {

    private GcLogNameService() {}

    public static final int MAX_LEN = 255;
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MMdd");

    /**
     * 분석명을 만든다. 호스트네임이 없으면 null — 화면은 그때만 파일명으로 떨어진다(수동 업로드는 호스트네임이 필수라
     * 정상 경로에서는 나오지 않는다).
     *
     * @param start 로그 시작일(없으면 null — 일자 없는 이름이 된다)
     * @param end   로그 끝일. start 와 같거나 null 이면 단일 일자로 쓴다.
     */
    public static String build(String host, String instance, LocalDate start, LocalDate end) {
        String h = sanitize(host);
        if (h.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(h);
        String i = sanitize(instance);
        if (!i.isEmpty()) sb.append('_').append(i);
        String d = datePart(start, end);
        if (!d.isEmpty()) sb.append('_').append(d);
        String name = sb.toString();
        return name.length() > MAX_LEN ? name.substring(0, MAX_LEN) : name;
    }

    private static String datePart(LocalDate start, LocalDate end) {
        if (start == null) return "";
        if (end == null || !end.isAfter(start)) return YMD.format(start);
        // 해가 같으면 뒤쪽은 월일만 — 20260919-0920
        return YMD.format(start) + "-" + (end.getYear() == start.getYear() ? MD.format(end) : YMD.format(end));
    }

    /**
     * 이름이 이미 쓰이고 있으면 {@code _2}, {@code _3} … 을 붙인다.
     *
     * @param taken 그 이름이 <b>다른 행에</b> 이미 있으면 true (자기 자신은 제외하고 판정할 것)
     */
    public static String assignUnique(String base, Predicate<String> taken) {
        if (base == null || base.isBlank()) return base;
        if (!taken.test(base)) return base;
        for (int n = 2; n <= 999; n++) {
            String candidate = trimTo(base, MAX_LEN - 4) + "_" + n;
            if (!taken.test(candidate)) return candidate;
        }
        return trimTo(base, MAX_LEN - 14) + "_" + System.currentTimeMillis() % 100000;
    }

    /** 경로·공백 문자를 {@code _} 로 바꾸고 연속된 {@code _} 를 합친다 — 이름이 파일 경로처럼 읽히면 안 된다. */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[\\s/\\\\:*?\"<>|]+", "_").replaceAll("_{2,}", "_");
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        return trimTo(s, 100);
    }

    private static String trimTo(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}

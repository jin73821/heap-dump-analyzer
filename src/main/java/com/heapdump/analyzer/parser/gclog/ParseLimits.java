package com.heapdump.analyzer.parser.gclog;

/**
 * 파싱 상한. {@code maxBytes} 는 gz 해제 후 바이트 기준(압축 폭탄 대비), {@code maxLineChars} 초과분은 절단,
 * {@code maxEvents} 초과 시 파싱을 멈추고 {@code LOG_TRUNCATED_LIMIT} 소견을 낸다.
 */
public record ParseLimits(long maxBytes, int maxLineChars, int maxEvents) {
    public static final ParseLimits DEFAULT = new ParseLimits(2L * 1024 * 1024 * 1024, 4096, 5_000_000);
}

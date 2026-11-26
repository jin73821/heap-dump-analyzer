package com.heapdump.analyzer.parser.threaddump;

/**
 * 파싱 상한. {@code maxBytes} 는 gz 해제 후 바이트 기준(압축 폭탄 대비), {@code maxLineChars} 초과분은 절단,
 * {@code maxThreadsPerSnapshot} 초과 스레드는 건너뛰고, {@code maxSnapshots} 를 넘으면 <b>마지막 N개</b>만 남긴다
 * (서버 stdout 에는 옛 사고의 덤프가 앞에 쌓여 있고 보고 싶은 것은 대개 마지막 묶음이다).
 */
public record ThreadDumpLimits(long maxBytes, int maxLineChars, int maxThreadsPerSnapshot, int maxSnapshots) {
    public static final ThreadDumpLimits DEFAULT = new ThreadDumpLimits(1024L * 1024 * 1024, 4096, 20_000, 30);
}

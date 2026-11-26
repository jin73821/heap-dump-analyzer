package com.heapdump.analyzer.parser.threaddump;

/**
 * 분석 자체의 실패(형식 불명·상한 초과·시간 초과·취소 등). 메시지는 사용자에게 그대로 보여도 되는 한국어다.
 *
 * <p>코드: {@code NOT_THREAD_DUMP}·{@code UNSUPPORTED_FORMAT}·{@code EMPTY}·{@code TOO_LARGE}·{@code TIMEOUT}·
 * {@code CANCELLED}·{@code FILE_MISSING}·{@code IO_ERROR}·{@code OUT_OF_MEMORY}·{@code INTERNAL}·{@code INTERRUPTED}.
 */
public final class ThreadDumpException extends RuntimeException {
    private final String code;

    public ThreadDumpException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}

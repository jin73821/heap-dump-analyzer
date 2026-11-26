package com.heapdump.analyzer.util;

/**
 * 마감 시각을 넘기면 {@link #charAt} 에서 {@link Timeout} 을 던지는 CharSequence (LLM Guard, 2026-11-26).
 *
 * <p>{@code java.util.regex} 는 실행 시간 제한이 없다. 관리자가 입력한 정규식이 파국적 역추적
 * ({@code (a+)+$} 류)을 일으키면 요청 스레드가 사실상 멈춘다. 매처는 입력을 오직 {@code charAt} 으로
 * 읽으므로, 여기서 시계를 보면 어느 지점에서든 끊을 수 있다.
 *
 * <p>시계는 {@value #CHECK_EVERY} 번 읽을 때마다 한 번만 본다({@code nanoTime} 비용 절감).
 * 내장 규칙은 선형 패턴만 쓰므로 이 래퍼를 쓰지 않는다 — <b>사용자 정의 정규식 전용</b>이다.
 */
public final class DeadlineCharSequence implements CharSequence {

    /** 정규식 실행이 마감 시각을 넘겼다. */
    public static final class Timeout extends RuntimeException {
        public Timeout() { super("regex deadline exceeded", null, false, false); }
    }

    private static final int CHECK_EVERY = 512;

    private final CharSequence inner;
    private final long deadlineNanos;
    private int reads;

    /** 지금부터 {@code budgetMillis} 뒤를 마감으로 하는 래퍼. */
    public static DeadlineCharSequence of(CharSequence inner, long budgetMillis) {
        return new DeadlineCharSequence(inner, System.nanoTime() + budgetMillis * 1_000_000L);
    }

    private DeadlineCharSequence(CharSequence inner, long deadlineNanos) {
        this.inner = inner;
        this.deadlineNanos = deadlineNanos;
    }

    @Override
    public char charAt(int index) {
        if (++reads >= CHECK_EVERY) {
            reads = 0;
            if (System.nanoTime() - deadlineNanos > 0) throw new Timeout();
        }
        return inner.charAt(index);
    }

    @Override
    public int length() { return inner.length(); }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos);
    }

    @Override
    public String toString() { return inner.toString(); }
}

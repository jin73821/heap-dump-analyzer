package com.heapdump.analyzer.parser.gclog;

/**
 * 줄 단위 push 파서. 엔진이 줄을 읽어 {@link #feedLine} 으로 넘기고 끝에 {@link #finish} 를 부른다.
 * 구현체는 미종결 이벤트 상태만 들고 있어야 한다(전량 보관 금지).
 */
public interface GcLogParser {
    void feedLine(int lineNo, String line);

    /** 입력 종료 — 미종결 이벤트는 버리고 {@link #droppedIncomplete()} 로 집계한다. */
    void finish();

    int droppedIncomplete();

    /** {@code maxEvents} 상한에 도달해 이후 줄을 버리고 있는가. */
    boolean limitReached();

    /** 형식을 판별한 결과(파서가 아는 형식). */
    GcLogFormat format();
}

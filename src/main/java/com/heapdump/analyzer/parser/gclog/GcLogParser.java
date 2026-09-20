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

    /**
     * 지금까지 부여한 이벤트 일련번호의 다음 값. 회전본을 이어 붙일 때 다음 조각의 파서에 이 값을
     * {@code seqBase} 로 넘겨야 {@code EventRow.seq} 가 중복되지 않는다(이벤트 표 기본 정렬 키다).
     */
    int seqEnd();
}

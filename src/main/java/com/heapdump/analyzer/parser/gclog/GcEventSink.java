package com.heapdump.analyzer.parser.gclog;

/** 파서 → 분석기 콜백. 파서는 이벤트를 보관하지 않는다(대용량 로그에서 메모리 상수 유지). */
public interface GcEventSink {
    void onEvent(GcEvent event);

    /** 이벤트가 아닌 메타 정보(수집기·JDK 버전·리전 크기·JVM 옵션 등). */
    default void onMeta(String key, String value) {}
}

package com.heapdump.analyzer.parser.gclog;

/** GC 로그 형식 — JDK 9+ 통합 로깅({@code -Xlog:gc*}) / JDK 8 이하({@code -XX:+PrintGCDetails}) / 판별 불가. */
public enum GcLogFormat {
    UNIFIED, JDK8, UNKNOWN
}

package com.heapdump.analyzer.parser.gclog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 이어붙여 분석할 GC 로그 한 조각(회전본 하나).
 *
 * <p>{@link GcLogEngine#analyze(java.util.List, ParseLimits, Long, GcLogEngine.Progress)} 가 이 목록을
 * <b>받은 순서 그대로</b> 읽는다 — 시간순 정렬은 호출자 몫이다(회전 번호는 시간순이 아니다, 함정 60).
 *
 * <p>⚠ {@code open} 은 <b>여러 번 열릴 수 있어야 한다</b>. 엔진이 멤버가 2개 이상이면 형식 판별을 위해
 * 각 조각의 앞부분을 먼저 한 번 읽고(헤더 없는 회전본이 UNKNOWN 으로 나와 전체가 거부되는 것을 막는다)
 * 본 분석에서 다시 연다. 파일 기반이 아닌 일회용 스트림은 단일 입력에서만 쓸 것.
 */
public record GcLogSource(String name, StreamOpener open, long rawBytes, Long mtimeEpochSec) {

    /** 열 때마다 새 스트림을 돌려준다(gz 여부는 엔진이 매직 바이트로 판별한다). */
    @FunctionalInterface
    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    /** 디스크 파일 한 개. */
    public static GcLogSource of(File f) {
        return new GcLogSource(f.getName(), () -> new FileInputStream(f), f.length(),
                f.lastModified() > 0 ? f.lastModified() / 1000L : null);
    }

    /** 이미 열린 스트림 한 개 — 단일 입력 전용(다시 열 수 없다). */
    static GcLogSource ofOpenStream(InputStream in) {
        return new GcLogSource(null, () -> in, -1L, null);
    }
}

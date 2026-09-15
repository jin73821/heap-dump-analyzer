package com.heapdump.analyzer.util;

import java.util.List;
import java.util.Map;

/**
 * 덤프 System Properties 에서 WAS 인스턴스 이름을 자동 식별한다.
 *
 * <p>analyze 화면 Instance 칩 · PDF 리포트 · GC 로그 인스턴스 카드가 모두 이 규칙 하나를 본다
 * (한 곳만 키를 늘리면 화면과 리포트가 서로 다른 이름을 보인다).
 *
 * <p>대조 순서는 {@link #KEYS} — JEUS {@code jeus.server.name} → WebLogic {@code weblogic.Name}.
 * 값이 비어 있거나 공백뿐인 키는 건너뛴다. 수동 편집값 우선 규칙은 호출자 몫이다.
 */
public final class WasInstanceName {

    private WasInstanceName() {}

    /** 인스턴스 이름을 담는 sysprop 키 — 앞에 있을수록 우선. */
    public static final List<String> KEYS = List.of("jeus.server.name", "weblogic.Name");

    /** 식별 결과. 미식별이면 {@code value} 는 빈 문자열, {@code key} 는 null. */
    public record Auto(String value, String key) {
        public boolean found() { return key != null; }
    }

    private static final Auto NONE = new Auto("", null);

    public static Auto resolve(Map<String, String> sysProps) {
        if (sysProps == null) return NONE;
        for (String key : KEYS) {
            String v = sysProps.get(key);
            if (v != null && !v.isBlank()) return new Auto(v.trim(), key);
        }
        return NONE;
    }

    /** 이름만 필요할 때 — 미식별이면 빈 문자열. */
    public static String of(Map<String, String> sysProps) {
        return resolve(sysProps).value();
    }
}

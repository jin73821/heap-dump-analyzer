package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인스턴스 이름 자동 식별 (2026-09-15) — jeus.server.name → weblogic.Name.
 * analyze 칩·PDF·GC 로그 인스턴스 카드가 공유하는 규칙이라 순서와 빈 값 처리를 고정한다.
 */
class WasInstanceNameTest {

    @Test
    @DisplayName("WebLogic 덤프: weblogic.Name 을 인스턴스로 채택하고 출처 키를 알린다")
    void weblogicName() {
        WasInstanceName.Auto a = WasInstanceName.resolve(Map.of(
                "weblogic.Name", " AdminServer ", "weblogic.home", "/u01/wlserver/server"));
        assertEquals("AdminServer", a.value());
        assertEquals("weblogic.Name", a.key());
        assertTrue(a.found());
        assertEquals("AdminServer", WasInstanceName.of(Map.of("weblogic.Name", "AdminServer")));
    }

    @Test
    @DisplayName("JEUS 덤프: 종전과 같이 jeus.server.name, 둘 다 있으면 JEUS 우선")
    void jeusFirst() {
        assertEquals("server1", WasInstanceName.of(Map.of("jeus.server.name", "server1")));
        WasInstanceName.Auto both = WasInstanceName.resolve(Map.of(
                "jeus.server.name", "server1", "weblogic.Name", "ManagedServer1"));
        assertEquals("server1", both.value());
        assertEquals("jeus.server.name", both.key());
    }

    @Test
    @DisplayName("공백뿐인 앞 키는 건너뛰고 다음 키로 폴백한다")
    void blankFallsThrough() {
        Map<String, String> p = new HashMap<>();
        p.put("jeus.server.name", "   ");
        p.put("weblogic.Name", "ms1");
        assertEquals("ms1", WasInstanceName.of(p));
    }

    @Test
    @DisplayName("미식별(null·빈 맵·Tomcat): 빈 문자열 + key null")
    void none() {
        for (Map<String, String> p : new Map[]{null, Map.of(), Map.of("catalina.base", "/opt/tomcat")}) {
            WasInstanceName.Auto a = WasInstanceName.resolve(p);
            assertEquals("", a.value());
            assertNull(a.key());
            assertFalse(a.found());
        }
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.DominatorRefEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dominator Refs 사전계산 저장 가드 회귀 테스트 (CLAUDE.md 함정 24).
 *
 * <p>MAT 쿼리가 전반적으로 실패하면(reparse / exit 13) refs 는 "주소는 있는데 목록은 전부 빈"
 * 형태로 만들어진다. 이걸 저장해 버리면 정상 동작하는 lazy 경로를 가려 UI 에 "참조 없음" 으로
 * 잘못 표시되는 회귀가 발생한다 — 저장하지 않고 lazy 폴백을 유지해야 한다.
 */
class DominatorRefsEmptyGuardTest {

    private static Map<String, Object> entry(List<DominatorRefEntry> in, List<DominatorRefEntry> out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incoming", in);
        m.put("outgoing", out);
        return m;
    }

    private static DominatorRefEntry ref() {
        DominatorRefEntry e = new DominatorRefEntry();
        e.setClassName("java.lang.Object");
        return e;
    }

    @Test
    @DisplayName("모든 주소의 incoming/outgoing 이 비어 있으면 저장 대상이 아니다")
    void allEmptyIsRejected() {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("0x1000", entry(List.of(), List.of()));
        refs.put("0x2000", entry(List.of(), List.of()));

        assertFalse(HeapDumpAnalyzerService.hasAnyRefData(refs));
    }

    @Test
    @DisplayName("한 주소라도 incoming 이 있으면 저장 대상이다")
    void anyIncomingIsAccepted() {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("0x1000", entry(List.of(), List.of()));
        refs.put("0x2000", entry(List.of(ref()), List.of()));

        assertTrue(HeapDumpAnalyzerService.hasAnyRefData(refs));
    }

    @Test
    @DisplayName("한 주소라도 outgoing 이 있으면 저장 대상이다")
    void anyOutgoingIsAccepted() {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("0x1000", entry(List.of(), List.of(ref())));

        assertTrue(HeapDumpAnalyzerService.hasAnyRefData(refs));
    }

    @Test
    @DisplayName("빈 맵·null 은 저장 대상이 아니다")
    void emptyOrNullIsRejected() {
        assertFalse(HeapDumpAnalyzerService.hasAnyRefData(new LinkedHashMap<>()));
        assertFalse(HeapDumpAnalyzerService.hasAnyRefData(null));
    }

    @Test
    @DisplayName("incoming/outgoing 키가 없거나 타입이 다른 항목은 데이터로 세지 않는다")
    void malformedEntriesDoNotCount() {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("0x1000", new LinkedHashMap<String, Object>());       // 키 없음
        refs.put("0x2000", entry(null, null));                          // null 목록
        refs.put("0x3000", "not-a-map");                                // 타입 불일치

        assertFalse(HeapDumpAnalyzerService.hasAnyRefData(refs));
    }
}

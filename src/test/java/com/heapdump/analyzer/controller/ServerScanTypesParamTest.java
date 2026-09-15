package com.heapdump.analyzer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code POST /api/servers/{id}/scan?types=} 파싱(2026-09-16) — 없으면 null(전부), 모르는 값 무시, 유효 값이 없으면 400. */
class ServerScanTypesParamTest {

    @Test
    @DisplayName("파라미터 없음 → null(서버 설정 전부 — 종전 동작)")
    void absentMeansAll() {
        assertNull(ServerController.parseScanTypes(null));
    }

    @Test
    @DisplayName("대소문자·공백·중복·모르는 값 정리, 순서 유지")
    void parsesAndNormalizes() {
        assertEquals(List.of("gclog", "heap"), List.copyOf(ServerController.parseScanTypes(" GCLOG , heap,heap, exe ")));
        assertEquals(List.of("core"), List.copyOf(ServerController.parseScanTypes("core")));
    }

    @Test
    @DisplayName("유효한 대상이 하나도 없으면 400 — 조용히 '전부 스캔'으로 떨어지지 않는다")
    void emptySelectionIsRejected() {
        assertEquals("스캔할 대상을 하나 이상 선택하세요.", assertThrows(IllegalArgumentException.class, () -> ServerController.parseScanTypes("")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> ServerController.parseScanTypes("foo,bar"));
    }
}

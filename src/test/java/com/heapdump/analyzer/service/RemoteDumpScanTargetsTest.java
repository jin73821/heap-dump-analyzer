package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.TargetServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스캔 대상 선택(2026-09-16) — 서버 설정의 탐지 대상이 상한이고 요청 {@code types} 가 그 안에서 좁힌다.
 * 설정에서 끈 대상은 선택해도 스캔하지 않는다(코어·GC 경로는 대상이 켜져 있을 때만 저장돼 낡았을 수 있다).
 */
class RemoteDumpScanTargetsTest {

    private static TargetServer server(Boolean heap, boolean core, boolean gc) {
        TargetServer s = new TargetServer();
        s.setScanHeap(heap);
        s.setScanCore(core);
        s.setScanGcLog(gc);
        s.setDumpPath("/opt/dumps\n/var/tmp");
        s.setCoreDumpPath("/var/crash");
        s.setGcLogPath("/logs/was1\n/logs/was2");
        return s;
    }

    @Test
    @DisplayName("types 없음 = 서버 설정 전부(자동 탐지·종전 호출)")
    void nullTypesMeansServerSettings() {
        List<List<String>> p = RemoteDumpService.resolveScanPaths(server(null, true, false), null);
        assertEquals(List.of("/opt/dumps", "/var/tmp"), p.get(0), "scanHeap null 은 켜짐");
        assertEquals(List.of("/var/crash"), p.get(1));
        assertTrue(p.get(2).isEmpty(), "GC 로그는 설정에서 꺼져 있다");
    }

    @Test
    @DisplayName("선택한 대상만 — 설정이 켜진 것 중에서")
    void typesNarrowWithinSettings() {
        TargetServer s = server(true, true, true);
        List<List<String>> onlyGc = RemoteDumpService.resolveScanPaths(s, Set.of("gclog"));
        assertTrue(onlyGc.get(0).isEmpty() && onlyGc.get(1).isEmpty());
        assertEquals(List.of("/logs/was1", "/logs/was2"), onlyGc.get(2));
        List<List<String>> heapCore = RemoteDumpService.resolveScanPaths(s, Set.of("heap", "core"));
        assertEquals(2, heapCore.get(0).size());
        assertEquals(1, heapCore.get(1).size());
        assertTrue(heapCore.get(2).isEmpty());
    }

    @Test
    @DisplayName("설정에서 끈 대상은 선택해도 스캔하지 않는다")
    void disabledTargetIsNotScannedEvenIfSelected() {
        List<List<String>> p = RemoteDumpService.resolveScanPaths(server(false, false, true), Set.of("heap", "core"));
        assertTrue(p.get(0).isEmpty() && p.get(1).isEmpty() && p.get(2).isEmpty());
    }

    @Test
    @DisplayName("대상 키 목록은 화면·요청 파라미터와 같은 이름")
    void scanTypeKeys() {
        assertEquals(List.of("heap", "core", "gclog"), RemoteDumpService.SCAN_TYPES);
    }
}

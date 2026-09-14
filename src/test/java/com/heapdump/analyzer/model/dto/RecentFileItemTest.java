package com.heapdump.analyzer.model.dto;

import com.heapdump.analyzer.model.HeapDumpFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대시보드 Recent Files 병합 (2026-09-14).
 *
 * <p>제보: GC 로그 저장소에서 분석이 끝난 {@code gc.log} 가 대시보드에 '미분석' 으로 보였다 — Recent Files 가 힙 저장소만 나열했고,
 * 힙 저장소에 같은 파일의 사본이 남아 있었다. 병합 규칙이 두 줄(분석 완료 GC + 미분석 힙)을 한 줄로 만들되,
 * 서로 다른 파일은 숨기지 않는지 고정한다.
 */
class RecentFileItemTest {

    private static RecentFileItem heap(String name, long size, long mtime, String status) {
        return new RecentFileItem(new HeapDumpFile(name, "/h/" + name, size, mtime, false, size, 0L), RecentFileItem.KIND_HEAP, status, false);
    }

    private static RecentFileItem gc(String name, long size, long mtime, String status) {
        return new RecentFileItem(new HeapDumpFile(name, "/g/" + name, size, mtime, false, size, 0L), RecentFileItem.KIND_GCLOG, status, false);
    }

    @Test
    @DisplayName("제보 재현: 힙 저장소의 미분석 사본(이름·크기 동일)은 빠지고 GC 로그 한 줄이 분석 완료로 남는다")
    void heapCopyOfAnalyzedGcLogCollapses() {
        List<RecentFileItem> out = RecentFileItem.merge(
                List.of(heap("gc.log", 53_251, 1_000, "NOT_ANALYZED"), heap("app.hprof", 9, 500, "SUCCESS")),
                List.of(gc("gc.log", 53_251, 1_060, "SUCCESS")));
        assertEquals(2, out.size());
        RecentFileItem first = out.get(0);
        assertEquals("gc.log", first.getName());
        assertTrue(first.isGcLog() && first.isAnalyzed(), "남은 gc.log 는 GC 로그 저장소 항목이어야 한다");
        assertEquals(1, out.stream().filter(i -> i.getName().equals("gc.log")).count(), "gc.log 가 두 줄로 보이면 안 된다");
    }

    @Test
    @DisplayName("다른 파일은 숨기지 않는다 — 크기가 다르거나, 힙에서 분석(성공·실패)한 파일")
    void distinctFilesAreKept() {
        List<RecentFileItem> sizeDiff = RecentFileItem.merge(
                List.of(heap("gc.log", 100, 1, "NOT_ANALYZED")), List.of(gc("gc.log", 200, 2, "SUCCESS")));
        assertEquals(2, sizeDiff.size(), "크기가 다르면 다른 파일");

        List<RecentFileItem> heapAnalyzed = RecentFileItem.merge(
                List.of(heap("gc.log", 100, 1, "ERROR")), List.of(gc("gc.log", 100, 2, "SUCCESS")));
        assertEquals(2, heapAnalyzed.size(), "힙에서 분석 기록이 있는 파일은 그 기록을 보여야 한다");
    }

    @Test
    @DisplayName("두 저장소를 수정 시각 최신순으로 섞는다")
    void sortedNewestFirstAcrossStores() {
        List<RecentFileItem> out = RecentFileItem.merge(
                List.of(heap("a.hprof", 1, 300, "SUCCESS"), heap("b.hprof", 1, 100, "NOT_ANALYZED")),
                List.of(gc("gc.log.1", 1, 200, "NOT_ANALYZED"), gc("gc.log.0", 1, 400, "ANALYZING")));
        assertEquals(List.of("gc.log.0", "a.hprof", "gc.log.1", "b.hprof"), out.stream().map(RecentFileItem::getName).toList());
    }

    @Test
    @DisplayName("배지·확인 모달 kind·상태 판정")
    void viewHelpers() {
        RecentFileItem g = gc("gc.log.0", 1, 1, "ANALYZING");
        assertEquals("GC", g.getBadge(), "회전 접미사가 확장자로 잡히지 않게 고정 라벨");
        assertEquals("gclog", g.getConfirmKind());
        assertTrue(g.isAnalyzing() && !g.isNotAnalyzed());

        RecentFileItem o = new RecentFileItem(new HeapDumpFile("x.bin2", "/h/x", 1, 1, false, 1, 0), "heap", null, true);
        assertEquals("others", o.getConfirmKind());
        assertTrue(o.isNotAnalyzed(), "상태 null 은 미분석");
        assertEquals("heap", heap("a.hprof", 1, 1, "SUCCESS").getConfirmKind());
        assertEquals("HPROF", heap("a.hprof", 1, 1, "SUCCESS").getBadge());
        assertFalse(heap("a.hprof", 1, 1, "SUCCESS").isGcLog());
    }
}

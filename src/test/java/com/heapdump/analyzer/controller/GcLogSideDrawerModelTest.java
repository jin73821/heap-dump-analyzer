package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.GcLogInstanceService;
import com.heapdump.analyzer.service.GcLogMatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좌측 파일 패널 서랍의 모델 계약 (2026-10-22) — {@code GET /gc-log} 의 {@code sideMembers}/{@code sideClaimed}.
 *
 * <p>고정하는 계약: 서랍은 <b>현존 파일만으로</b> 판정한다. ① 구성 파일이 지워졌으면 서랍에서 빠진다
 * ② 대표가 지워졌으면 구성 파일이 최상위로 남는다 — 어느 쪽에서도 그리지 않으면 목록에서 조용히 사라진다.
 * 이력 표 서랍({@code memberRows})은 기록 기준이라 지워진 파일도 남는다(둘이 달라야 한다).
 */
class GcLogSideDrawerModelTest {

    private GcLogAnalyzerService gc;
    private GcLogViewController controller;

    @BeforeEach
    void setUp() {
        gc = Mockito.mock(GcLogAnalyzerService.class);
        Mockito.when(gc.parseMembers(Mockito.any())).thenAnswer(inv -> {
            String json = inv.getArgument(0);
            if (json == null) return List.of();
            return java.util.Arrays.stream(json.split(","))
                    .map(n -> new GcLogAnalyzerService.MemberRef(n, 1L, null)).toList();
        });
        controller = new GcLogViewController(gc, Mockito.mock(GcLogMatchService.class), Mockito.mock(GcLogInstanceService.class));
    }

    private static GcLogAnalysisEntity row(String fn) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename(fn);
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        return e;
    }

    /** 대표 gc.log + 구성 gc.log.0·gc.log.1 (member_files 는 이 테스트의 parseMembers 스텁이 읽는 쉼표 목록). */
    private static GcLogAnalysisEntity[] group() {
        GcLogAnalysisEntity rep = row("gc.log");
        rep.setMemberCount(3);
        rep.setMemberFiles("gc.log.0,gc.log.1,gc.log");
        GcLogAnalysisEntity m0 = row("gc.log.0"); m0.setPartOf("gc.log");
        GcLogAnalysisEntity m1 = row("gc.log.1"); m1.setPartOf("gc.log");
        return new GcLogAnalysisEntity[] { rep, m0, m1 };
    }

    private Map<String, Object> index(List<GcLogAnalysisEntity> files, List<GcLogAnalysisEntity> history) {
        Mockito.when(gc.listExistingFiles()).thenReturn(files);
        Mockito.when(gc.history()).thenReturn(history);
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("gc-log/index", controller.index(model, null));
        return model;
    }

    @Test
    @DisplayName("모든 파일이 있으면 구성 파일이 대표 서랍으로 들어간다(대표 자신은 구성 목록에서 빠진다)")
    @SuppressWarnings("unchecked")
    void membersGoIntoRepresentativeDrawer() {
        GcLogAnalysisEntity[] g = group();
        GcLogAnalysisEntity solo = row("other.log");
        List<GcLogAnalysisEntity> all = List.of(g[0], g[1], g[2], solo);
        Map<String, Object> m = index(all, all);

        Map<String, List<GcLogAnalysisEntity>> side = (Map<String, List<GcLogAnalysisEntity>>) m.get("sideMembers");
        assertEquals(List.of("gc.log.0", "gc.log.1"), side.get("gc.log").stream().map(GcLogAnalysisEntity::getFilename).toList());
        assertEquals(Set.of("gc.log.0", "gc.log.1"), m.get("sideClaimed"));
    }

    @Test
    @DisplayName("구성 파일이 지워졌으면 사이드 서랍에서는 빠지고 이력 표 서랍에는 남는다")
    @SuppressWarnings("unchecked")
    void deletedMemberLeavesSideDrawerOnly() {
        GcLogAnalysisEntity[] g = group();
        Map<String, Object> m = index(List.of(g[0], g[1]), List.of(g[0], g[1], g[2]));

        Map<String, List<GcLogAnalysisEntity>> side = (Map<String, List<GcLogAnalysisEntity>>) m.get("sideMembers");
        assertEquals(List.of("gc.log.0"), side.get("gc.log").stream().map(GcLogAnalysisEntity::getFilename).toList());
        Map<String, List<GcLogAnalysisEntity>> table = (Map<String, List<GcLogAnalysisEntity>>) m.get("memberRows");
        assertEquals(2, table.get("gc.log").size(), "이력 표는 기록 기준이라 지워진 구성 파일도 보여야 한다");
    }

    @Test
    @DisplayName("대표가 지워졌으면 구성 파일이 사이드 패널 최상위로 남는다 — 조용히 사라지지 않는다")
    @SuppressWarnings("unchecked")
    void orphanedMembersStayTopLevel() {
        GcLogAnalysisEntity[] g = group();
        Map<String, Object> m = index(List.of(g[1], g[2]), List.of(g[0], g[1], g[2]));

        assertTrue(((Map<String, ?>) m.get("sideMembers")).isEmpty());
        Set<String> claimed = (Set<String>) m.get("sideClaimed");
        assertFalse(claimed.contains("gc.log.0") || claimed.contains("gc.log.1"), "대표 없는 구성 파일이 숨겨졌다");
    }
}

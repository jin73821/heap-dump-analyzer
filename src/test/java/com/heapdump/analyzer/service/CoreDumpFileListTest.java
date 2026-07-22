package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사이드바 파일 목록 — 코어에 연결된 exec 는 목록에서 숨기고,
 * 연결 정보는 코어 항목의 페어 칩(pairedExecFilename)으로만 노출하는지 검증.
 * 같은 파일이 코어 + exec 두 항목으로 중복 표시되던 문제의 회귀 방지.
 */
class CoreDumpFileListTest {

    private CoreDumpAnalyzerService service;
    private HeapDumpAnalyzerService heapFacade;
    private CoreDumpAnalysisRepository repository;
    private File dumpDir;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        HeapDumpConfig config = mock(HeapDumpConfig.class);
        when(config.getCoreDumpDirectory()).thenReturn(tmp.toFile().getAbsolutePath());
        heapFacade = mock(HeapDumpAnalyzerService.class);
        repository = mock(CoreDumpAnalysisRepository.class);
        service = new CoreDumpAnalyzerService(config, repository, new ObjectMapper(), heapFacade);
        dumpDir = service.dumpFilesDir();
        assertTrue(dumpDir.mkdirs());
    }

    private void touch(String name) throws Exception {
        Files.writeString(new File(dumpDir, name).toPath(), "x", StandardCharsets.UTF_8);
    }

    private CoreDumpAnalysisEntity entity(String filename, String status) {
        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
        e.setId(1L);
        e.setFilename(filename);
        e.setStatus(status);
        return e;
    }

    private AnalysisHistoryItem find(List<AnalysisHistoryItem> items, String name) {
        return items.stream().filter(i -> i.getFilename().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("항목 없음: " + name));
    }

    private boolean contains(List<AnalysisHistoryItem> items, String name) {
        return items.stream().anyMatch(i -> i.getFilename().equals(name));
    }

    @Test
    @DisplayName("코어에 연결된 exec 는 목록에서 숨기고 코어 항목의 페어 칩으로만 노출한다")
    void pairedExecIsHiddenFromList() throws Exception {
        touch("crash_demo_2.core");
        touch("crash_demo_2.core.exec");
        when(heapFacade.loadCoreExecPairings())
                .thenReturn(Map.of("crash_demo_2.core", "crash_demo_2.core.exec"));
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc())
                .thenReturn(List.of(entity("crash_demo_2.core", "SUCCESS")));

        List<AnalysisHistoryItem> items = service.listExistingDumpFiles();

        assertFalse(contains(items, "crash_demo_2.core.exec"), "연결된 exec 는 목록에 없어야 함");

        AnalysisHistoryItem core = find(items, "crash_demo_2.core");
        assertEquals("coredump", core.getFileType());
        assertEquals("SUCCESS", core.getStatus());
        assertEquals("crash_demo_2.core.exec", core.getPairedExecFilename(), "연결 정보는 코어 쪽에 유지");
    }

    @Test
    @DisplayName("연결이 없는 exec 는 독립 항목(미분석)으로 표시된다")
    void unpairedExecStaysVisible() throws Exception {
        touch("orphan.exec");
        when(heapFacade.loadCoreExecPairings()).thenReturn(Map.of());
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc()).thenReturn(List.of());

        AnalysisHistoryItem exec = find(service.listExistingDumpFiles(), "orphan.exec");
        assertEquals("exec", exec.getFileType());
        assertEquals("NOT_ANALYZED", exec.getStatus());
    }

    @Test
    @DisplayName("코어가 아직 미분석이어도 연결된 exec 는 숨긴다 (상태와 무관)")
    void pairedExecIsHiddenRegardlessOfCoreStatus() throws Exception {
        touch("fresh.core");
        touch("fresh.exec");
        when(heapFacade.loadCoreExecPairings()).thenReturn(Map.of("fresh.core", "fresh.exec"));
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc()).thenReturn(List.of());

        List<AnalysisHistoryItem> items = service.listExistingDumpFiles();
        assertFalse(contains(items, "fresh.exec"));
        assertEquals("fresh.exec", find(items, "fresh.core").getPairedExecFilename());
    }

    @Test
    @DisplayName("명시적 해제 마커(빈 값)는 연결로 치지 않는다 — exec 는 목록에 남는다")
    void explicitUnpairMarkerIsNotALink() throws Exception {
        touch("a.core");
        touch("a.core.exec");
        // 빈 값 = unpairExec 가 남기는 명시적 해제 마커
        Map<String, String> pairings = new HashMap<>();
        pairings.put("a.core", "");
        when(heapFacade.loadCoreExecPairings()).thenReturn(pairings);
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc())
                .thenReturn(List.of(entity("a.core", "SUCCESS")));

        AnalysisHistoryItem exec = find(service.listExistingDumpFiles(), "a.core.exec");
        assertEquals("exec", exec.getFileType(), ".exec 확장자라 여전히 exec 타입");
        assertEquals("NOT_ANALYZED", exec.getStatus(), "해제 상태면 독립 항목으로 표시");
    }

    @Test
    @DisplayName("한 exec 가 여러 코어에 연결돼도 숨긴다")
    void multiLinkedExecIsHidden() throws Exception {
        touch("zzz.core");
        touch("aaa.core");
        touch("mmm.core");
        touch("shared.exec");
        Map<String, String> pairings = new LinkedHashMap<>();
        pairings.put("zzz.core", "shared.exec");
        pairings.put("aaa.core", "shared.exec");
        pairings.put("mmm.core", "shared.exec");
        when(heapFacade.loadCoreExecPairings()).thenReturn(pairings);
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc())
                .thenReturn(List.of(entity("mmm.core", "SUCCESS")));

        List<AnalysisHistoryItem> items = service.listExistingDumpFiles();
        assertFalse(contains(items, "shared.exec"));
        // 각 코어 항목은 자기 페어 칩을 유지
        assertEquals("shared.exec", find(items, "mmm.core").getPairedExecFilename());
        assertEquals("shared.exec", find(items, "aaa.core").getPairedExecFilename());
    }

    @Test
    @DisplayName("레거시 {core}.exec 는 페어링 맵에 없어도 파일명 규칙으로 연결을 인식해 숨긴다")
    void legacyExecFallsBackToNamingConvention() throws Exception {
        touch("legacy.core");
        touch("legacy.core.exec");
        when(heapFacade.loadCoreExecPairings()).thenReturn(Map.of());   // 맵에 항목 없음
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc())
                .thenReturn(List.of(entity("legacy.core", "ERROR")));

        List<AnalysisHistoryItem> items = service.listExistingDumpFiles();
        assertFalse(contains(items, "legacy.core.exec"), "레거시 규칙으로 연결된 exec 도 숨김");
        assertEquals("legacy.core.exec", find(items, "legacy.core").getPairedExecFilename());
    }

    @Test
    @DisplayName("레거시 규칙: 같은 이름의 코어 파일이 없으면 연결하지 않는다 — 목록에 남는다")
    void legacyFallbackRequiresCoreToExist() throws Exception {
        touch("ghost.core.exec");   // ghost.core 는 존재하지 않음
        when(heapFacade.loadCoreExecPairings()).thenReturn(Map.of());
        when(repository.findByFileDeletedFalseOrderByCreatedAtDesc()).thenReturn(List.of());

        AnalysisHistoryItem exec = find(service.listExistingDumpFiles(), "ghost.core.exec");
        assertEquals("exec", exec.getFileType());
        assertEquals("NOT_ANALYZED", exec.getStatus());
    }
}

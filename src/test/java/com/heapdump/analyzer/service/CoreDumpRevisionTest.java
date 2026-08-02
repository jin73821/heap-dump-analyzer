package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.dto.CoreDumpRevision;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재분석 시 기존 결과를 revisions/ 로 보존하는 경로 검증.
 * 코어덤프 디렉토리를 임시 디렉토리로 갈아끼워 실제 파일 I/O 를 그대로 수행한다.
 */
class CoreDumpRevisionTest {

    private CoreDumpAnalyzerService service;
    private File coreRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        coreRoot = tmp.toFile();
        HeapDumpConfig config = mock(HeapDumpConfig.class);
        when(config.getCoreDumpDirectory()).thenReturn(coreRoot.getAbsolutePath());
        service = new CoreDumpAnalyzerService(
                config,
                mock(CoreDumpAnalysisRepository.class),
                new ObjectMapper(),
                mock(HeapDumpAnalyzerService.class),
                mock(LlmConfigService.class),
                mock(AiInsightManager.class));
    }

    /** data/{core}/ 에 result.json + gdb_output.txt 를 심는다. */
    private void seedResult(String core, String execName, String signal) throws Exception {
        File dir = service.dataDir(core);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setFilename(core);
        r.setExecutableName(execName);
        r.setCrashSignal(signal);
        r.setAnalyzedAt("2026-07-17T14:20:33");
        new ObjectMapper().writeValue(service.resultJsonFile(core), r);
        Files.writeString(new File(dir, "gdb_output.txt").toPath(), "raw gdb " + signal, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("아카이브: result.json + gdb_output.txt 가 revisions/{id}/ 로 이관되고 현재 결과는 비워진다")
    void archiveMovesCurrentResultIntoRevision() throws Exception {
        String core = "crash_demo_4nqolz.core";
        seedResult(core, null, "SIGSEGV");

        String revId = service.archiveCurrentResult(core);

        assertNotNull(revId, "리비전 ID 가 반환돼야 한다");
        assertTrue(revId.matches("\\d{8}-\\d{6}(-\\d+)?"), "리비전 ID 형식: " + revId);
        // 이관이므로 현재 결과는 사라져야 재분석이 트리거된다(progress 페이지 redirect 방어 로직)
        assertFalse(service.resultJsonFile(core).exists(), "현재 result.json 은 이관 후 사라져야 함");
        assertFalse(new File(service.dataDir(core), "gdb_output.txt").exists(), "gdb_output.txt 도 함께 이관");

        File revDir = new File(service.revisionsDir(core), revId);
        assertTrue(new File(revDir, "result.json").exists(), "보존된 result.json");
        assertTrue(new File(revDir, "gdb_output.txt").exists(), "보존된 gdb_output.txt");
        assertEquals("raw gdb SIGSEGV",
                Files.readString(new File(revDir, "gdb_output.txt").toPath(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("아카이브: 보존할 결과가 없으면 null 을 반환하고 아무것도 만들지 않는다")
    void archiveReturnsNullWhenNoCurrentResult() throws Exception {
        assertNull(service.archiveCurrentResult("no_such.core"));
        assertFalse(service.revisionsDir("no_such.core").exists());
    }

    @Test
    @DisplayName("보존된 단일 분석 결과가 새 분석 후에도 그대로 남고 리비전으로 조회된다 (항목 2 핵심)")
    void previousSingleAnalysisSurvivesReanalysis() throws Exception {
        String core = "crash_demo_4nqolz.core";
        // 1차: exec 없는 단일 분석
        seedResult(core, null, "SIGSEGV");
        String revId = service.archiveCurrentResult(core);
        // 2차: exec 를 붙여 재분석한 결과가 현재 자리에 저장됨
        seedResult(core, "crash_demo_2.core.exec", "SIGABRT");

        // 현재 결과 = 새 분석
        Optional<CoreDumpAnalysisResult> current = service.loadResult(core);
        assertTrue(current.isPresent());
        assertEquals("crash_demo_2.core.exec", current.get().getExecutableName());
        assertEquals("SIGABRT", current.get().getCrashSignal());

        // 과거 결과 = 보존된 단일 분석 (유지되어야 함)
        Optional<CoreDumpAnalysisResult> old = service.loadRevisionResult(core, revId);
        assertTrue(old.isPresent(), "이전 단일 분석 결과가 보존돼야 한다");
        assertNull(old.get().getExecutableName(), "이전 분석엔 exec 가 없었다");
        assertEquals("SIGSEGV", old.get().getCrashSignal());
    }

    @Test
    @DisplayName("리비전 목록: 최신순 정렬 + exec 유무가 라벨에 반영")
    void listRevisionsSortedWithLabels() throws Exception {
        String core = "crash_demo_4nqolz.core";
        seedResult(core, null, "SIGSEGV");
        String rev1 = service.archiveCurrentResult(core);
        seedResult(core, "libfoo.exec", "SIGABRT");
        String rev2 = service.archiveCurrentResult(core);

        List<CoreDumpRevision> revs = service.listRevisions(core);
        assertEquals(2, revs.size());
        // 같은 초에 생성되면 -2 접미사로 충돌을 피한다
        assertNotEquals(rev1, rev2, "동일 초 충돌 시 ID 가 달라야 한다");

        CoreDumpRevision withExec = revs.stream().filter(r -> r.getId().equals(rev2)).findFirst().orElseThrow();
        CoreDumpRevision noExec = revs.stream().filter(r -> r.getId().equals(rev1)).findFirst().orElseThrow();
        assertTrue(withExec.getLabel().contains("exec: libfoo.exec"), "라벨: " + withExec.getLabel());
        assertTrue(noExec.getLabel().contains("exec 없음"), "라벨: " + noExec.getLabel());
        assertTrue(withExec.getLabel().startsWith("2026-07-17 14:20"), "analyzedAt 기반 시각: " + withExec.getLabel());
        assertEquals("SIGABRT", withExec.getCrashSignal());
    }

    @Test
    @DisplayName("리비전 ID 검증: 경로 조작 문자열 차단")
    void validateRevisionIdRejectsTraversal() {
        for (String bad : new String[]{"../../etc", "..", "foo", "20260717", "20260717-142033/../x", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> service.validateRevisionId(bad),
                    "차단돼야 함: " + bad);
        }
        assertEquals("20260717-142033", service.validateRevisionId("20260717-142033"));
        assertEquals("20260717-142033-2", service.validateRevisionId("20260717-142033-2"));
    }

    @Test
    @DisplayName("리비전 로드: 존재하지 않는 ID 는 빈 Optional (예외 아님)")
    void loadMissingRevisionReturnsEmpty() throws Exception {
        seedResult("crash_demo_4nqolz.core", null, "SIGSEGV");
        assertTrue(service.loadRevisionResult("crash_demo_4nqolz.core", "20990101-000000").isEmpty());
    }

    @Test
    @DisplayName("리비전 목록: 깨진 result.json 은 목록에서 조용히 제외")
    void listRevisionsSkipsCorruptResult() throws Exception {
        String core = "crash_demo_4nqolz.core";
        seedResult(core, null, "SIGSEGV");
        String ok = service.archiveCurrentResult(core);

        File corrupt = new File(service.revisionsDir(core), "20260101-000000");
        assertTrue(corrupt.mkdirs());
        Files.writeString(new File(corrupt, "result.json").toPath(), "{ not json", StandardCharsets.UTF_8);
        // 리비전 ID 패턴에 맞지 않는 디렉토리도 무시돼야 한다
        assertTrue(new File(service.revisionsDir(core), "scratch").mkdirs());

        List<CoreDumpRevision> revs = service.listRevisions(core);
        assertEquals(1, revs.size(), "정상 리비전만 남아야 함");
        assertEquals(ok, revs.get(0).getId());
    }

    @Test
    @DisplayName("삭제: 코어 삭제 시 보존 리비전도 함께 정리된다")
    void deleteDumpRemovesRevisions() throws Exception {
        String core = "crash_demo_4nqolz.core";
        seedResult(core, null, "SIGSEGV");
        service.archiveCurrentResult(core);
        assertTrue(service.revisionsDir(core).exists());

        service.deleteHistoryOnly(core);
        assertFalse(service.dataDir(core).exists(), "data 디렉토리 통째 삭제 → 리비전도 정리");
    }
}

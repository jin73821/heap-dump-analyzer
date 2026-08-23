package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * buildGdbCommand() 골든 검증 — sysroot 번들 유/무 × exec 유/무 4조합 + 인자 순서.
 *
 * 순서가 계약이다: sysroot 는 반드시 -iex(파일 로드 전 실행)로 positional <exec> <core> 및
 * -ex core-file 보다 앞서야 한다. -ex 로 주면 positional 로드 시 분석 서버 로컬 라이브러리로
 * 초기 solib 해석이 한 번 수행된다 (COREDUMP_SYMBOL_ACCURACY_VERIFICATION.md 케이스 D-2 실증).
 */
class CoreDumpSysrootCommandTest {

    private CoreDumpAnalyzerService service;
    private File core;
    private File exec;
    private File sysroot;

    @BeforeEach
    void setUp() {
        HeapDumpConfig config = mock(HeapDumpConfig.class);
        when(config.getGdbCliPath()).thenReturn("gdb");
        service = new CoreDumpAnalyzerService(config,
                mock(CoreDumpAnalysisRepository.class), new ObjectMapper(),
                mock(HeapDumpAnalyzerService.class), mock(LlmConfigService.class),
                mock(AiInsightManager.class), mock(CoreDumpSysrootService.class));
        core = new File("/opt/coredumps/tmp/core.1234/core.1234");
        exec = new File("/opt/coredumps/tmp/core.1234/myapp") {
            @Override public boolean exists() { return true; }
        };
        sysroot = new File("/opt/coredumps/sysroots/core.1234");
    }

    @Test
    void execWithoutSysroot_noIexAndPositionalAtEnd() {
        List<String> cmd = service.buildGdbCommand(core, exec, null, null);
        assertFalse(cmd.contains("-iex"), "sysroot 미사용 시 -iex 가 없어야 한다");
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("core-file ")),
                "exec 분기는 -ex core-file 미사용 (positional 로드)");
        assertEquals(exec.getAbsolutePath(), cmd.get(cmd.size() - 2));
        assertEquals(core.getAbsolutePath(), cmd.get(cmd.size() - 1));
    }

    @Test
    void coreOnlyWithoutSysroot_usesExCoreFile() {
        List<String> cmd = service.buildGdbCommand(core, null, null, null);
        assertFalse(cmd.contains("-iex"));
        assertTrue(cmd.contains("core-file " + core.getAbsolutePath()),
                "코어 단독 분기는 -ex core-file 로 로드");
        assertNotEquals(core.getAbsolutePath(), cmd.get(cmd.size() - 1),
                "코어 단독 분기는 positional 인자가 없어야 한다");
    }

    @Test
    void execWithSysroot_iexImmediatelyAfterNx() {
        List<String> cmd = service.buildGdbCommand(core, exec, sysroot, null);
        int iex = cmd.indexOf("-iex");
        assertTrue(iex > 0, "-iex 존재");
        assertEquals("--nx", cmd.get(iex - 1), "-iex 는 --nx 직후");
        assertEquals("set sysroot " + sysroot.getAbsolutePath(), cmd.get(iex + 1));
        assertEquals(exec.getAbsolutePath(), cmd.get(cmd.size() - 2));
        assertEquals(core.getAbsolutePath(), cmd.get(cmd.size() - 1));
    }

    @Test
    void coreOnlyWithSysroot_iexPrecedesCoreFile() {
        List<String> cmd = service.buildGdbCommand(core, null, sysroot, null);
        int iex = cmd.indexOf("-iex");
        int coreFile = cmd.indexOf("core-file " + core.getAbsolutePath());
        assertTrue(iex >= 0 && coreFile >= 0);
        assertTrue(iex < coreFile, "-iex set sysroot 는 core-file 보다 앞서야 한다");
    }

    @Test
    void sysrootPrecedesEveryExCommand() {
        List<String> cmd = service.buildGdbCommand(core, exec, sysroot, null);
        int iex = cmd.indexOf("-iex");
        int firstEx = cmd.indexOf("-ex");
        assertTrue(iex < firstEx, "-iex 는 모든 -ex 보다 앞서야 한다");
    }

    // ── solib-search-path (개별 업로드 파일 basename 탐색) ────────

    @Test
    void solibSearchPathFollowsSysrootAsIex() {
        String sp = "/opt/coredumps/sysroots/core.1234:/opt/coredumps/sysroots/core.1234/lib64";
        List<String> cmd = service.buildGdbCommand(core, exec, sysroot, sp);
        int sysIdx = cmd.indexOf("set sysroot " + sysroot.getAbsolutePath());
        int spIdx = cmd.indexOf("set solib-search-path " + sp);
        assertTrue(sysIdx >= 0 && spIdx >= 0, "두 설정 모두 존재해야 한다");
        assertEquals("-iex", cmd.get(spIdx - 1), "solib-search-path 도 -iex 로 줘야 한다");
        assertTrue(sysIdx < spIdx, "sysroot 다음에 solib-search-path");
        assertTrue(spIdx < cmd.indexOf("-ex"), "-ex 보다 앞서야 한다");
    }

    @Test
    void solibSearchPathIgnoredWithoutSysroot() {
        // sysroot 없이 solib-search-path 만 주면 gdb 가 원경로 파일을 먼저 찾아 무효다
        // (COREDUMP_SYMBOL_ACCURACY_VERIFICATION.md 케이스 F-2) — 아예 붙이지 않는다.
        List<String> cmd = service.buildGdbCommand(core, exec, null, "/some/dir");
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("set solib-search-path")));
    }

    @Test
    void blankSolibSearchPathOmitted() {
        List<String> cmd = service.buildGdbCommand(core, exec, sysroot, "");
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("set solib-search-path")));
        assertTrue(cmd.contains("set sysroot " + sysroot.getAbsolutePath()));
    }
}

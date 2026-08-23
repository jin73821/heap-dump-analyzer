package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.GdbSharedLib;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * sysroot 관점 신뢰도 경고 + 수집 대상 경로 산출 검증.
 *
 * 핵심 시나리오(검증 케이스 C): 원격 출처 코어가 번들 없이 분석 서버 로컬 라이브러리로
 * Syms Read=Yes 해석된 경우 — gdb 는 버전 불일치를 침묵하므로 앱이 대신 경고해야 한다.
 */
class CoreDumpSysrootWarningTest {

    private CoreDumpSysrootService service;

    @BeforeEach
    void setUp() {
        service = new CoreDumpSysrootService(mock(HeapDumpConfig.class),
                mock(DumpTransferLogRepository.class), mock(TargetServerRepository.class),
                mock(RemoteDumpService.class));
    }

    private static GdbSharedLib lib(String path, String symsRead) {
        GdbSharedLib l = new GdbSharedLib();
        l.setPath(path);
        l.setSymsRead(symsRead);
        return l;
    }

    private static CoreDumpAnalysisResult result(boolean sysrootUsed, GdbSharedLib... libs) {
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setSysrootUsed(sysrootUsed);
        r.setSharedLibraries(new ArrayList<>(List.of(libs)));
        r.setQualityWarnings(new ArrayList<>());
        return r;
    }

    // ── 경고 분기 ─────────────────────────────────────────────────

    @Test
    void remoteOriginWithoutBundle_localSymbolsWarned() {
        CoreDumpAnalysisResult r = result(false,
                lib("/lib64/libc.so.6", "Yes (*)"), lib("/sw/tmax/lib/libsvr.so", "No"));
        service.appendSysrootQualityWarnings(r, true);
        assertEquals(1, r.getQualityWarnings().size());
        assertTrue(r.getQualityWarnings().get(0).contains("로컬 라이브러리"));
        assertTrue(r.getQualityWarnings().get(0).contains("라이브러리 번들 수집"));
    }

    @Test
    void bundleWithMissingLibs_supplementWarned() {
        CoreDumpAnalysisResult r = result(true,
                lib("/lib64/libc.so.6", "Yes"), lib("/lib64/libm.so.6", "No"));
        service.appendSysrootQualityWarnings(r, true);
        assertEquals(1, r.getQualityWarnings().size());
        assertTrue(r.getQualityWarnings().get(0).contains("번들에 없는 라이브러리 1건"));
        assertTrue(r.getQualityWarnings().get(0).contains("libm.so.6"));
    }

    @Test
    void completeBundle_noWarnings() {
        CoreDumpAnalysisResult r = result(true,
                lib("/lib64/libc.so.6", "Yes"), lib("/app/lib/libx.so", "Yes"));
        service.appendSysrootQualityWarnings(r, true);
        assertTrue(r.getQualityWarnings().isEmpty());
    }

    @Test
    void manualUploadOrigin_noRemoteWarning_andNullListCreated() {
        CoreDumpAnalysisResult r = result(false, lib("/lib64/libc.so.6", "Yes"));
        r.setQualityWarnings(null); // 구 result.json 하위 호환 — null 리스트도 안전해야 한다
        service.appendSysrootQualityWarnings(r, false);
        assertNotNull(r.getQualityWarnings());
        assertTrue(r.getQualityWarnings().isEmpty(), "수동 업로드(비원격) 코어는 경고 미발동");
    }

    // ── 수집 대상 경로 산출 ───────────────────────────────────────

    @Test
    void pathsComeFromSharedLibsAndMappings() {
        CoreDumpAnalysisResult r = result(false, lib("/lib64/libc.so.6", "No"));
        r.setMemoryMappings(List.of(
                "0x400000 0x401000 0x1000 0x0 /opt/app/bin/myapp",                 // exec — .so 아님, 제외
                "0x7f00 0x7f10 0x10 0x0 /lib64/ld-linux-x86-64.so.2",              // 인터프리터 — 포함
                "0x7f20 0x7f30 0x10 0x0 /sw/oracle/lib/libclntsh.so.19.1",         // 라이브러리 — 포함
                "0x7f40 0x7f50 0x10 0x0"));                                        // anonymous — 토큰 4개, 제외
        List<String> paths = service.listRequiredLibraryPaths(r);
        assertEquals(List.of("/lib64/ld-linux-x86-64.so.2", "/lib64/libc.so.6",
                "/sw/oracle/lib/libclntsh.so.19.1"), paths);
    }

    @Test
    void unsafeAndSpecialPathsExcluded() {
        CoreDumpAnalysisResult r = result(false,
                lib("/lib64/libc.so.6; rm -rf /", "No"),   // 화이트리스트 불통과 (명령 주입 시도)
                lib("/path with space/lib.so", "No"),      // 공백 — 불통과
                lib("/SYSV00000000 (deleted)", "No"),      // SysV 공유메모리 세그먼트 — 제외
                lib("/lib64/libok.so (deleted)", "No"),    // deleted 마커 — 제외
                lib("/lib64/libok.so.1", "No"));
        List<String> paths = service.listRequiredLibraryPaths(r);
        assertEquals(List.of("/lib64/libok.so.1"), paths);
    }

    @Test
    void duplicatePathsAppearOnce() {
        CoreDumpAnalysisResult r = result(false, lib("/lib64/libc.so.6", "Yes"));
        r.setMemoryMappings(List.of("0x1 0x2 0x1 0x0 /lib64/libc.so.6"));
        assertEquals(List.of("/lib64/libc.so.6"), service.listRequiredLibraryPaths(r));
    }

    @Test
    void emptyResultYieldsNoPaths() {
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        assertTrue(service.listRequiredLibraryPaths(r).isEmpty());
    }
}

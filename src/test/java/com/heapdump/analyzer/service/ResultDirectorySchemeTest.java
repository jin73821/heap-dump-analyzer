package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 결과 디렉토리 스킴 회귀 테스트.
 *
 * <p>배경: 결과 디렉토리가 {@code stripExtension(filename)} 이던 시절, 분석 후 원본이 gzip 되면
 * {@code X.hprof} 와 {@code X.hprof.gz} 가 analysis_history 상 **별개 행**이면서 결과 디렉토리는
 * base 하나를 공유했다. 뒤에 실행된 분석이 앞 결과(ZIP/.index)를 덮어써 두 행 중 하나는 결과를
 * 잃었고, 그 행은 목록에 SUCCESS 로 보이는데 진입하면 결과가 없었다.
 * (운영 DB 실제 사례: id 49 {@code jeus_admin.hprof} ↔ id 64 {@code jeus_admin.hprof.gz})
 */
class ResultDirectorySchemeTest {

    private FileManagementService fileMgmt;
    private File dataDir;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        HeapDumpConfig config = mock(HeapDumpConfig.class);
        dataDir = new File(tmp.toFile(), "data");
        when(config.getDataDirectory()).thenReturn(dataDir.getAbsolutePath());
        fileMgmt = new FileManagementService(config, mock(HeapAnalysisResultCache.class));
    }

    @Test
    @DisplayName(".hprof 와 .hprof.gz 는 서로 다른 결과 디렉토리를 갖는다")
    void gzAndPlainDoNotShareDirectory() {
        File plain = fileMgmt.resultDirectory("jeus_admin.hprof");
        File gz    = fileMgmt.resultDirectory("jeus_admin.hprof.gz");

        assertNotEquals(plain.getAbsolutePath(), gz.getAbsolutePath(),
                "확장자가 다른 두 히스토리 행이 결과 디렉토리를 공유하면 안 된다");
        assertEquals("jeus_admin.hprof", plain.getName());
        assertEquals("jeus_admin.hprof.gz", gz.getName());
    }

    @Test
    @DisplayName("결과 디렉토리명은 확장자를 포함한 파일명 그대로다")
    void directoryNameKeepsExtension() {
        assertEquals("oom-test.hprof", fileMgmt.resultDirectory("oom-test.hprof").getName());
        assertEquals("ssh-to-pgp.bin", fileMgmt.resultDirectory("ssh-to-pgp.bin").getName());
        assertEquals("heapApp_20260522.tar", fileMgmt.resultDirectory("heapApp_20260522.tar").getName());
    }

    @Test
    @DisplayName("구 스킴 helper 는 확장자 제거 base 를 유지한다 — 기동 시 rename 마이그레이션 근거")
    void legacySchemeStillStripsExtension() {
        assertEquals("jeus_admin", fileMgmt.legacyResultDirectory("jeus_admin.hprof").getName());
        assertEquals("jeus_admin", fileMgmt.legacyResultDirectory("jeus_admin.hprof.gz").getName());
    }

    @Test
    @DisplayName("경로 구분자가 섞여 들어와도 data/ 하위 단일 세그먼트로 고정된다")
    void directoryStaysUnderDataDir() {
        File dir = fileMgmt.resultDirectory("../../etc/passwd.hprof");

        assertEquals("passwd.hprof", dir.getName());
        assertEquals(dataDir.getAbsolutePath(), dir.getParentFile().getAbsolutePath());
    }

    @Test
    @DisplayName("result.json 경로는 파일명 스킴 디렉토리 하위를 가리킨다 (레거시 정리 대상 탐색용)")
    void resultJsonResolvesUnderFilenameDir() {
        File json = fileMgmt.resultJsonFile("tomcat_heapdump.hprof");

        assertEquals("result.json", json.getName());
        assertEquals("tomcat_heapdump.hprof", json.getParentFile().getName());
        assertTrue(json.getAbsolutePath().startsWith(dataDir.getAbsolutePath()));
    }
}

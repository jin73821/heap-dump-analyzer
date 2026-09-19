package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files 페이지 탭(Heap Dump / Corefile / Others) 판정 — Files·대시보드·현황판이 공유하는 단일 규칙.
 * 이 규칙이 화면마다 갈리면 같은 파일이 한 화면에서는 힙 덤프, 다른 화면에서는 others 로 보인다.
 */
class FileTypeRulesTest {

    private static final Map<String, String> CLS = Map.of(
            "tool.jar", "others",
            "MAWS_Setup.exe", "heapdump",
            "crash_demo.core", "core",
            "4nqolz", "exec",
            "bundle.tar.gz", "others");

    @Test
    @DisplayName("힙 덤프 확장자 인식 — 대소문자 무시, 힙 확장자의 .gz 압축본만. 단독 .gz(.tar.gz 등)는 아니다")
    void recognizedExtensions() {
        for (String n : new String[]{"a.hprof", "A.HPROF", "a.bin", "a.dump", "a.dmp", "a.hprof.gz", "a.BIN.GZ", "a.dump.gz", "a.dmp.gz"}) {
            assertTrue(FileTypeRules.hasRecognizedHeapDumpExtension(n), n);
        }
        // 2026-09-19 이전엔 모든 .gz 를 인정해 heapApp_20260622.tar.gz 같은 압축 아카이브가 힙 덤프로 집계됐다
        for (String n : new String[]{"heapApp_20260622.tar.gz", "ibm-semeru-open-jre_ppc64_aix_21.0.11.0.tar.gz", "gc.log.gz", "x.gz",
                "a.png", "gc.log", "a.jar", "core.1234", "a.phd", "hprof", "", "a.hprof.zip"}) {
            assertFalse(FileTypeRules.hasRecognizedHeapDumpExtension(n), n);
        }
        assertFalse(FileTypeRules.hasRecognizedHeapDumpExtension(null));
    }

    @Test
    @DisplayName("힙 저장소 — 모든 확장자 허용일 때만 라벨 적용, 라벨 없는 미인식 파일은 others, 인식 확장자는 라벨과 무관하게 힙 덤프")
    void heapDir() {
        assertEquals("others", FileTypeRules.heapDirType("front_design.png", true, CLS), "라벨 없는 미인식 파일");
        assertEquals("others", FileTypeRules.heapDirType("gc.log", true, CLS));
        assertEquals("others", FileTypeRules.heapDirType("tool.jar", true, CLS));
        assertEquals("heapdump", FileTypeRules.heapDirType("MAWS_Setup.exe", true, CLS), "힙 덤프로 명시 분류");
        assertEquals("core", FileTypeRules.heapDirType("crash_demo.core", true, CLS));
        assertEquals("exec", FileTypeRules.heapDirType("4nqolz", true, CLS));
        assertEquals("others", FileTypeRules.heapDirType("bundle.tar.gz", true, CLS), ".tar.gz 는 힙 확장자가 아니라 라벨이 적용된다");
        assertEquals("others", FileTypeRules.heapDirType("heapApp_20260622.tar.gz", true, CLS), "라벨 없는 .tar.gz 는 others");
        assertEquals("heapdump", FileTypeRules.heapDirType("a.hprof", true, CLS));
        assertEquals("heapdump", FileTypeRules.heapDirType("a.hprof.gz", true, Map.of("a.hprof.gz", "others")),
                "힙 확장자는 라벨이 others 여도 힙 덤프");
        assertEquals("heapdump", FileTypeRules.heapDirType("front_design.png", false, CLS), "확장자 제한 모드에서는 라벨·others 판정 없음");
        assertEquals("others", FileTypeRules.heapDirType("x.txt", true, null));
    }

    @Test
    @DisplayName("코어 저장소 — exec·others 라벨만 적용, core 라벨·인식 확장자·확장자 제한 모드는 현재 유형 유지(null)")
    void coreDir() {
        assertEquals("others", FileTypeRules.coreDirLabel("tool.jar", true, CLS));
        assertEquals("exec", FileTypeRules.coreDirLabel("4nqolz", true, CLS));
        assertNull(FileTypeRules.coreDirLabel("crash_demo.core", true, CLS), "core 라벨은 무시");
        assertNull(FileTypeRules.coreDirLabel("core.1234", true, CLS), "라벨 없는 코어는 코어 그대로(others 아님)");
        assertEquals("others", FileTypeRules.coreDirLabel("bundle.tar.gz", true, CLS));
        assertNull(FileTypeRules.coreDirLabel("a.dmp.gz", true, Map.of("a.dmp.gz", "others")));
        assertNull(FileTypeRules.coreDirLabel("tool.jar", false, CLS));
    }

    @Test
    @DisplayName("목록 이름 — .gz 로 끝나면 떼어낸다(대소문자 무시). 업로드 기록·파일 목록이 같은 이름을 쓴다")
    void listingName() {
        assertEquals("jeus_admin.hprof", FileTypeRules.listingName("jeus_admin.hprof.gz"));
        assertEquals("ibm-semeru-open-jre_ppc64_aix_21.0.11.0.tar", FileTypeRules.listingName("ibm-semeru-open-jre_ppc64_aix_21.0.11.0.tar.gz"));
        assertEquals("dump", FileTypeRules.listingName("dump.GZ"));
        assertEquals("a.hprof", FileTypeRules.listingName("a.hprof"));
        assertEquals("x.gz", FileTypeRules.listingName("x.gz.gz"), "한 겹만 뗀다");
        assertEquals(".gz", FileTypeRules.listingName(".gz"));
        assertNull(FileTypeRules.listingName(null));
    }
}

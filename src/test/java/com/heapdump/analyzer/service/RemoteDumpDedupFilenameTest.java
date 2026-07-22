package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 원격 전송 로컬 동명 충돌 회피명 — 타임스탬프 삽입 규칙 검증.
 * 분 단위 → 초 단위 → 카운터 3단계 폴백과 확장자 위치 유지를 고정 시각으로 확인한다.
 */
class RemoteDumpDedupFilenameTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 7, 17, 14, 30, 45);

    private void touch(File dir, String name) throws Exception {
        Files.writeString(new File(dir, name).toPath(), "x", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("충돌 시 마지막 확장자 앞에 _yyyyMMddHHmm 삽입")
    void insertsMinuteTimestampBeforeExtension(@TempDir Path tmp) {
        assertEquals("crash_demo_202607171430.core",
                RemoteDumpService.dedupFilename(tmp.toFile(), "crash_demo.core", T));
    }

    @Test
    @DisplayName("같은 분에 재충돌하면 초 단위로 확장")
    void fallsBackToSecondsOnSameMinuteCollision(@TempDir Path tmp) throws Exception {
        touch(tmp.toFile(), "crash_demo_202607171430.core");
        assertEquals("crash_demo_20260717143045.core",
                RemoteDumpService.dedupFilename(tmp.toFile(), "crash_demo.core", T));
    }

    @Test
    @DisplayName("같은 초까지 충돌하면 카운터 폴백")
    void fallsBackToCounterOnSameSecondCollision(@TempDir Path tmp) throws Exception {
        touch(tmp.toFile(), "crash_demo_202607171430.core");
        touch(tmp.toFile(), "crash_demo_20260717143045.core");
        assertEquals("crash_demo_20260717143045_2.core",
                RemoteDumpService.dedupFilename(tmp.toFile(), "crash_demo.core", T));
        touch(tmp.toFile(), "crash_demo_20260717143045_2.core");
        assertEquals("crash_demo_20260717143045_3.core",
                RemoteDumpService.dedupFilename(tmp.toFile(), "crash_demo.core", T));
    }

    @Test
    @DisplayName("확장자 없는 파일(exec 바이너리)은 이름 끝에 붙는다")
    void appendsAtEndForExtensionlessFile(@TempDir Path tmp) {
        assertEquals("myapp_202607171430",
                RemoteDumpService.dedupFilename(tmp.toFile(), "myapp", T));
    }

    @Test
    @DisplayName(".hprof.gz 이중 확장자는 마지막 확장자(.gz) 앞에 삽입 — 기존 _2 방식과 동일 특성")
    void doubleExtensionInsertsBeforeLastExtension(@TempDir Path tmp) {
        assertEquals("app.hprof_202607171430.gz",
                RemoteDumpService.dedupFilename(tmp.toFile(), "app.hprof.gz", T));
    }

    @Test
    @DisplayName("dotfile 성 이름(선행 점)은 통째로 base 취급")
    void leadingDotIsNotTreatedAsExtension(@TempDir Path tmp) {
        assertEquals(".core_202607171430",
                RemoteDumpService.dedupFilename(tmp.toFile(), ".core", T));
    }
}

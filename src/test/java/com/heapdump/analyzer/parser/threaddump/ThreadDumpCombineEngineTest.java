package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 여러 스레드 덤프 파일을 이어 붙여 분석(2.7.1). */
class ThreadDumpCombineEngineTest {

    /** 실측 JDK 21 덤프(3회 연속 jstack)를 스냅샷 단위 텍스트 3개로 나눈다. 각 조각은 시각 줄로 시작한다. */
    static List<String> splitJdk21() throws IOException {
        String all;
        try (InputStream in = open("jdk21-3snap.txt")) { all = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        List<String> parts = new ArrayList<>();
        int from = 0;
        while (true) {
            int next = all.indexOf("\nFull thread dump", all.indexOf("Full thread dump", from) + 10);
            if (next < 0) { parts.add(all.substring(from)); break; }
            int cut = all.lastIndexOf('\n', next - 1) + 1;       // 다음 덤프의 시각 줄 앞
            parts.add(all.substring(from, cut));
            from = cut;
        }
        assertEquals(3, parts.size());
        return parts;
    }

    static ThreadDumpEngine.Source src(String name, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return new ThreadDumpEngine.Source(name, () -> new ByteArrayInputStream(b));
    }

    static ThreadDumpResult combine(ThreadDumpEngine.Source... s) throws IOException {
        return ThreadDumpEngine.analyze(List.of(s), ThreadDumpLimits.DEFAULT, null, false);
    }

    @Test
    void 섞인_순서로_넣어도_시각순으로_다시_세우고_단일_파일과_같은_결론() throws IOException {
        List<String> p = splitJdk21();
        ThreadDumpResult r = combine(src("c.txt", p.get(2)), src("a.txt", p.get(0)), src("b.txt", p.get(1)));
        ThreadDumpResult single = fixture("jdk21-3snap.txt");
        assertEquals(List.of("c.txt", "a.txt", "b.txt"), r.getMeta().getFiles(), "구성은 받은 순서대로 남긴다");
        assertEquals(List.of("a.txt", "b.txt", "c.txt"), r.getSnapshots().stream().map(ThreadDumpResult.Snapshot::getFile).toList(),
                "스냅샷은 시각순");
        assertEquals(1, r.getMeta().getRuns());
        assertEquals(codes(single), codes(r), "이어 붙여도 같은 소견");
        assertEquals(single.getKpi().getStuck(), r.getKpi().getStuck());
        assertEquals(single.getDeadlocks().size(), r.getDeadlocks().size());
        assertEquals(single.getMeta().getSpanSec(), r.getMeta().getSpanSec());
        assertEquals(2, r.getSnapshots().get(0).getFirstLine(), "줄 번호는 각 파일 기준(1줄 = 시각, 2줄 = Full thread dump)");
        assertEquals(2, r.getSnapshots().get(1).getFirstLine());
        assertNull(single.getMeta().getFiles(), "단일 파일 결과에는 구성 목록이 없다");
        assertNull(single.getSnapshots().get(0).getFile());
    }

    @Test
    void 덤프가_없는_구성_파일은_빼고_진단에_남긴다() throws IOException {
        List<String> p = splitJdk21();
        ThreadDumpResult r = combine(src("a.txt", p.get(0)), src("notes.txt", "just a log\nline 2\n"), src("empty.txt", ""), src("b.txt", p.get(1)));
        assertEquals(2, r.getSnapshots().size());
        ThreadDumpResult.Finding f = finding(r, "MEMBER_NO_DUMP");
        assertNotNull(f);
        assertEquals("Low", f.getSeverity());
        assertEquals(List.of("notes.txt — 스레드 덤프 없음", "empty.txt — 빈 파일"), r.getDiagnostics().getMembersWithoutDump());
    }

    @Test
    void 모두_덤프가_없으면_실패_구성_파일이_없으면_FILE_MISSING() {
        ThreadDumpException e = assertThrows(ThreadDumpException.class, () -> combine(src("x.txt", "hello\n"), src("y.txt", "world\n")));
        assertEquals("NOT_THREAD_DUMP", e.code());
        assertTrue(e.getMessage().contains("구성 파일"));
        ThreadDumpException e2 = assertThrows(ThreadDumpException.class, () -> combine(src("x.txt", "hello\n"),
                ThreadDumpEngine.Source.of(new File("/nonexistent/td-missing.txt"))));
        assertEquals("FILE_MISSING", e2.code());
        assertTrue(e2.getMessage().contains("td-missing.txt"));
    }

    @Test
    void 시각이_없는_스냅샷이_있으면_받은_순서를_지킨다() throws IOException {
        List<String> p = splitJdk21();
        String noTs = p.get(0).substring(p.get(0).indexOf('\n') + 1);   // 첫 조각의 시각 줄 제거
        ThreadDumpResult r = combine(src("b.txt", p.get(1)), src("a.txt", noTs));
        assertEquals(List.of("b.txt", "a.txt"), r.getSnapshots().stream().map(ThreadDumpResult.Snapshot::getFile).toList());
    }

    @Test
    void 해석_못_한_줄에_파일명이_붙는다() throws IOException {
        List<String> p = splitJdk21();
        // 스레드 블록 사이(Threads class SMR info 블록 밖)에 로그 줄을 끼운다
        String dirty = p.get(1).replace("\n\n\"Reference Handler\"", "\n2026-11-26 WARN interleaved log line\n\n\"Reference Handler\"");
        assertNotEquals(p.get(1), dirty);
        ThreadDumpResult r = combine(src("a.txt", p.get(0)), src("b.txt", dirty));
        assertEquals(1, r.getDiagnostics().getUnparsedLines());
        assertEquals("b.txt", r.getDiagnostics().getUnparsedSamples().get(0).getFile());
    }

    @Test
    void 스냅샷_상한은_전체에서_마지막_N개() throws IOException {
        List<String> p = splitJdk21();
        ThreadDumpResult r = ThreadDumpEngine.analyze(List.of(src("a", p.get(0)), src("b", p.get(1)), src("c", p.get(2))),
                new ThreadDumpLimits(1L << 30, 4096, 20000, 2), null, false);
        assertEquals(List.of("b", "c"), r.getSnapshots().stream().map(ThreadDumpResult.Snapshot::getFile).toList());
        assertTrue(has(r, "SNAPSHOT_LIMIT_HIT"));
        assertEquals(3, r.getMeta().getSnapshotsSeen());
    }

    @Test
    void 다른_JVM_의_덤프를_이어_붙이면_실행을_나눈다() throws IOException {
        List<String> p = splitJdk21();
        String jdk8 = new String(open("jdk8-deadlock-3snap.txt").readAllBytes(), StandardCharsets.UTF_8);
        ThreadDumpResult r = combine(src("jdk21.txt", p.get(0)), src("jdk8.txt", jdk8));
        assertEquals(2, r.getMeta().getRuns());
        assertTrue(has(r, "RESTART_BETWEEN_SNAPSHOTS"));
    }
}

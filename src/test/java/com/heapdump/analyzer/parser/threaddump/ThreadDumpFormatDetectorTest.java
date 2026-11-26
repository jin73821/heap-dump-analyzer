package com.heapdump.analyzer.parser.threaddump;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreadDumpFormatDetectorTest {

    @Test
    void 파일명_규칙() {
        for (String ok : List.of("threaddump.txt", "thread_dump_20261126.log.gz", "jstack.12345", "was1-jstack-3.out",
                "x.tdump", "ThreadDump-prod.txt", "app.threaddump", "stackdump_1.txt", "tdump.txt.gz", "thread-dumps.log")) {
            assertTrue(ThreadDumpFormatDetector.isThreadDumpFilename(ok), ok);
        }
        for (String no : List.of("catalina.out", "server.log", "gc.log", "threads.hprof", "heapdump.hprof", "core.1234",
                "mythreaddumper.jar.txt2", "jstacked.bin")) {
            assertFalse(ThreadDumpFormatDetector.isThreadDumpFilename(no), no);
        }
        assertTrue(ThreadDumpFormatDetector.isThreadDumpFilename("/var/log/jstack.out"), "경로가 붙어도 파일명만 본다");
        assertFalse(ThreadDumpFormatDetector.isThreadDumpFilename(null));
    }

    @Test
    void 내용_판별_헤더와_스레드줄() {
        assertTrue(ThreadDumpFormatDetector.looksLikeThreadDump(List.of(
                "2026-11-26 10:00:00", VM8, "",
                "\"main\" #1 prio=5 os_prio=0 tid=0x00007f885404c800 nid=0x12dac2 waiting on condition [0x00007f885b5a8000]")));
        // 헤더만 있고 스레드 줄이 없으면 아니다(로그에 문구만 인용된 경우)
        assertFalse(ThreadDumpFormatDetector.looksLikeThreadDump(List.of("see " + VM8)));
        // 머리가 잘린 덤프 — 스레드 줄 3개 + 상태 줄
        assertTrue(ThreadDumpFormatDetector.looksLikeThreadDump(List.of(
                "\"a\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]",
                "   java.lang.Thread.State: RUNNABLE",
                "\"b\" #2 prio=5 os_prio=0 tid=0x00007f0000002000 nid=0x2 runnable [0x0]",
                "\"c\" #3 [77] prio=5 os_prio=0 cpu=1.0ms elapsed=2.0s tid=0x00007f0000003000 nid=77 runnable  [0x0]")));
        assertFalse(ThreadDumpFormatDetector.looksLikeThreadDump(List.of(
                "\"a\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]",
                "\"b\" #2 prio=5 os_prio=0 tid=0x00007f0000002000 nid=0x2 runnable [0x0]")));
        // GC 로그·일반 로그는 아니다
        assertFalse(ThreadDumpFormatDetector.looksLikeThreadDump(List.of(
                "OpenJDK 64-Bit Server VM (25.422-b05) for linux-amd64 JRE (1.8.0_422-b05), built on Jul 11 2024",
                "2026-09-14T00:52:33.123+0900: 0.325: [GC (Allocation Failure) [PSYoungGen: 1K->2K(3K)] 1K->2K(3K), 0.01 secs]",
                "[0.005s][info][gc] Using G1")));
    }

    @Test
    void 스트림_전체_스캔은_뒤쪽_덤프도_찾는다() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("2026-11-26 10:00:00 INFO  app.Service - request ").append(i).append('\n');
        sb.append(new Dump("2026-11-26 10:05:00", VM8).thread("main", "RUNNABLE", at("a.B.c(B.java:1)")).end());
        var sniff = ThreadDumpFormatDetector.scan(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), 1 << 30, 4096);
        assertEquals(ThreadDumpFormatDetector.Kind.HOTSPOT, sniff.kind());
        assertTrue(sniff.supported());
        assertTrue(sniff.linesScanned() > 5000);

        var gz = ThreadDumpFormatDetector.scan(new ByteArrayInputStream(gzip(sb.toString())), 1 << 30, 4096);
        assertEquals(ThreadDumpFormatDetector.Kind.HOTSPOT, gz.kind());
    }

    @Test
    void 미지원_형식은_아님과_구분한다() throws IOException {
        String javacore = "0SECTION       TITLE subcomponent dump routine\nNULL           ===============================\n"
                + "1TISIGINFO     Dump Event \"user\" (00004000) received\n3XMTHREADINFO      \"main\" J9VMThread:0x0, state:R\n";
        assertEquals(ThreadDumpFormatDetector.Kind.J9_JAVACORE,
                ThreadDumpFormatDetector.scan(new ByteArrayInputStream(javacore.getBytes(StandardCharsets.UTF_8)), 1 << 20, 4096).kind());
        String dtf = "12345\n2026-11-26T01:00:00Z\n21.0.1+12\n\n#1 \"main\"\n      java.base/java.lang.Thread.sleep(Thread.java:1)\n\n#9 \"Reference Handler\"\n\n#10 \"Finalizer\"\n";
        assertEquals(ThreadDumpFormatDetector.Kind.JCMD_DUMP_FILE,
                ThreadDumpFormatDetector.scan(new ByteArrayInputStream(dtf.getBytes(StandardCharsets.UTF_8)), 1 << 20, 4096).kind());
        assertEquals(ThreadDumpFormatDetector.Kind.UNKNOWN,
                ThreadDumpFormatDetector.scan(new ByteArrayInputStream("hello\nworld\n".getBytes(StandardCharsets.UTF_8)), 1 << 20, 4096).kind());
        assertTrue(ThreadDumpFormatDetector.unsupportedMessage(ThreadDumpFormatDetector.Kind.J9_JAVACORE).contains("javacore"));
    }
}

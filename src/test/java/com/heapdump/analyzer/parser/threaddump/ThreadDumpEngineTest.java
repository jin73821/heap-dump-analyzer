package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 엔진 — 지저분한 입력을 어디까지 보완하고 어디서 실패로 끝내는지. */
class ThreadDumpEngineTest {

    private static String simple() {
        return new Dump("2026-11-26 10:00:00", VM8).thread("main", "RUNNABLE", at("x.Y.z(Y.java:1)")).end();
    }

    private static String code(Runnable r) {
        return assertThrows(ThreadDumpException.class, r::run).code();
    }

    @Test
    void 빈_파일은_EMPTY() {
        assertEquals("EMPTY", code(() -> bytes(new byte[0])));
        assertEquals("EMPTY", code(() -> bytes(gzip(""))));
    }

    @Test
    void 스레드_덤프가_아니면_NOT_THREAD_DUMP() {
        ThreadDumpException e = assertThrows(ThreadDumpException.class, () -> text("just a log\nanother line\n"));
        assertEquals("NOT_THREAD_DUMP", e.code());
        assertTrue(e.getMessage().contains("jstack"), "사용자에게 그대로 보여 줄 수 있는 안내문이다");
    }

    @Test
    void 바이너리는_힙을_터뜨리지_않고_거부된다() {
        byte[] bin = new byte[3 * 1024 * 1024];      // 줄바꿈이 거의 없는 3MB
        new Random(7).nextBytes(bin);
        for (int i = 0; i < bin.length; i++) if (bin[i] == '\n' || bin[i] == '\r') bin[i] = 'x';
        assertEquals("NOT_THREAD_DUMP", code(() -> bytes(bin)));
    }

    @Test
    void 미지원_형식은_UNSUPPORTED_FORMAT() {
        String javacore = "0SECTION       TITLE subcomponent dump routine\n1TISIGINFO     Dump Event \"user\"\n3XMTHREADINFO      \"main\" J9VMThread:0x0\n";
        ThreadDumpException e = assertThrows(ThreadDumpException.class, () -> text(javacore));
        assertEquals("UNSUPPORTED_FORMAT", e.code());
        assertTrue(e.getMessage().contains("javacore"));
    }

    @Test
    void 없는_파일은_FILE_MISSING() {
        assertEquals("FILE_MISSING", code(() -> {
            try { ThreadDumpEngine.analyze(new File("/nonexistent/x.txt"), ThreadDumpLimits.DEFAULT, null); }
            catch (java.io.IOException ex) { throw new AssertionError(ex); }
        }));
    }

    @Test
    void gzip_과_평문은_같은_결과() {
        ThreadDumpResult a = text(simple());
        ThreadDumpResult b = bytes(gzip(simple()));
        assertEquals(a.getSnapshots().size(), b.getSnapshots().size());
        assertEquals(a.getTracks().size(), b.getTracks().size());
        assertEquals(a.getMeta().getLines(), b.getMeta().getLines());
        assertTrue(b.getMeta().getBytes() < a.getMeta().getBytes(), "bytes 는 원본(압축) 크기다");
    }

    @Test
    void 도중에_끊긴_gzip_은_읽힌_데까지_분석한다() {
        StringBuilder sb = new StringBuilder(simple());
        Random rnd = new Random(3);
        for (int i = 0; i < 4000; i++) sb.append("noise ").append(rnd.nextLong()).append('\n');   // 압축이 덜 되게
        byte[] gz = gzip(sb.toString());
        byte[] cut = Arrays.copyOf(gz, gz.length - 2000);
        ThreadDumpResult r = bytes(cut);
        assertEquals(1, r.getSnapshots().size());
        assertTrue(r.getDiagnostics().getLimitHits().contains("GZIP_TRUNCATED"));
        assertTrue(has(r, "FILE_READ_INCOMPLETE"));
        assertTrue(r.getMeta().isTruncated());
    }

    @Test
    void ANSI_이스케이프와_제어문자를_지운다() {
        String dirty = "\u001B[31m" + VM8 + "\u001B[0m\n\n"
                + "\"wor\u0000ker\u0007-1\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]\n"
                + "   java.lang.Thread.State: RUNNABLE\n\tat x.Y.z(Y.java:1)\u001B[K\n\nJNI global references: 1\n";
        ThreadDumpResult r = text(dirty);
        assertEquals("worker-1", r.getNames().get(0));
        assertEquals("x.Y.z(Y.java:1)", r.getFrames().get(0));
        assertTrue(r.getDiagnostics().getStrippedControlChars() >= 10);
        assertEquals(0, r.getDiagnostics().getUnparsedLines());
        String json = ThreadDumpResultCodec.toJson(r);
        assertFalse(json.contains("\\u001B") || json.contains("\\u0000"), "뒤쪽(JSON·PDF·프롬프트)은 깨끗한 문자열만 본다");
    }

    @Test
    void 깨진_UTF8_은_치환하고_센다() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write((VM8 + "\n\n\"스레드-").getBytes(StandardCharsets.UTF_8));
        bos.write(new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF});     // 잘못된 시퀀스
        bos.write("\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]\n   java.lang.Thread.State: RUNNABLE\n\nJNI global references: 1\n"
                .getBytes(StandardCharsets.UTF_8));
        ThreadDumpResult r = bytes(bos.toByteArray());
        assertEquals(1, r.getTracks().size());
        assertTrue(r.getNames().get(0).startsWith("스레드-"));
        assertTrue(r.getDiagnostics().getReplacedChars() >= 1);
    }

    @Test
    void 긴_줄은_자르고_센다() {
        String longName = "n".repeat(10_000);
        String d = VM8 + "\n\n\"" + longName + "\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 runnable [0x0]\n"
                + "\"ok\" #2 prio=5 os_prio=0 tid=0x00007f0000002000 nid=0x2 runnable [0x0]\n   java.lang.Thread.State: RUNNABLE\n\nJNI global references: 1\n";
        ThreadDumpResult r = text(d, new ThreadDumpLimits(1 << 30, 500, 1000, 30));
        assertEquals(1, r.getDiagnostics().getTruncatedLines());
        assertTrue(r.getMeta().isTruncated());
        assertNotNull(track(r, "ok"), "잘린 줄 뒤의 스레드는 정상적으로 읽힌다");
        assertEquals(1, r.getDiagnostics().getUnparsedLines(), "잘려서 헤더로 읽히지 않은 줄은 해석 못 한 줄로 드러난다");
    }

    @Test
    void 크기_상한에서_멈추고_읽힌_데까지_분석한다() {
        StringBuilder sb = new StringBuilder(simple());
        for (int i = 0; i < 20_000; i++) sb.append("2026-11-26 10:00:01 INFO filler line ").append(i).append('\n');
        sb.append(new Dump("2026-11-26 10:10:00", VM8).thread("late", "RUNNABLE", at("x.Y.z(Y.java:1)")).end());
        ThreadDumpResult r = text(sb.toString(), new ThreadDumpLimits(64 * 1024, 4096, 1000, 30));
        assertEquals(1, r.getSnapshots().size(), "상한 뒤의 스냅샷은 읽지 않는다");
        assertTrue(r.getDiagnostics().getLimitHits().contains("BYTES"));
        assertTrue(has(r, "FILE_READ_INCOMPLETE"));

        // 상한까지 읽었는데 덤프가 하나도 없으면 실패
        StringBuilder only = new StringBuilder();
        for (int i = 0; i < 20_000; i++) only.append("filler line ").append(i).append('\n');
        only.append(simple());
        assertEquals("TOO_LARGE", code(() -> text(only.toString(), new ThreadDumpLimits(64 * 1024, 4096, 1000, 30))));
    }

    @Test
    void CRLF_줄바꿈() {
        ThreadDumpResult r = text(simple().replace("\n", "\r\n"));
        assertEquals(1, r.getTracks().size());
        assertEquals(0, r.getDiagnostics().getUnparsedLines());
        assertEquals(text(simple()).getMeta().getLines(), r.getMeta().getLines(), "CRLF 를 두 줄로 세지 않는다");
    }

    @Test
    void 진행_콜백으로_시간초과와_취소를_알린다() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("filler ").append(i).append('\n');
        sb.append(simple());
        byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
        int[] calls = {0};
        ThreadDumpException e = assertThrows(ThreadDumpException.class, () -> ThreadDumpEngine.analyze(
                () -> new ByteArrayInputStream(b), ThreadDumpLimits.DEFAULT,
                (bytes, lines, snaps, threads) -> { if (++calls[0] >= 2) throw new ThreadDumpException("CANCELLED", "취소"); }));
        assertEquals("CANCELLED", e.code());
        assertEquals(2, calls[0]);
    }

    @Test
    void 원문_샘플과_메타() {
        String d = "boot log 1\nboot log 2\nboot log 3\n" + simple();
        ThreadDumpResult r = text(d);
        assertEquals("boot log 1", r.getRawSample().getHead().get(0));
        assertEquals(5, r.getRawSample().getFirstDumpLine());
        assertTrue(r.getRawSample().getFirstDumpHead().get(0).startsWith("Full thread dump"));
        assertTrue(r.getMeta().isEmbedded());
        assertEquals(VM8, r.getMeta().getJvmVersionLine());
        assertEquals(1, r.getMeta().getSnapshotsSeen());
        assertEquals(3, r.getDiagnostics().getNoiseLines());
    }
}

package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** 힙 덤프 MAT 산출물 → 스레드 덤프 텍스트(2.7.1). 픽스처는 MAT HTML 구조를 그대로 둔 합성본이다(운영 데이터 아님). */
class HeapThreadExtractorTest {

    static String res(String name) throws IOException {
        try (InputStream in = HeapThreadExtractorTest.class.getResourceAsStream("/threaddump/heap/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static ThreadDumpResult analyze(HeapThreadExtractor.Extract x) throws IOException {
        return HeapThreadExtractor.analyze(x, ThreadDumpLimits.DEFAULT, "app.hprof");
    }

    @Test
    void MAT_프레임을_jstack_모양으로_바꾼다() {
        assertEquals("java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)",
                HeapThreadExtractor.frame("java.util.concurrent.locks.LockSupport.park(Ljava/lang/Object;)V (LockSupport.java:175)"));
        assertEquals("sun.misc.Unsafe.park(Native Method)", HeapThreadExtractor.frame("sun.misc.Unsafe.park(ZJ)V (Unsafe.java(Native Method))"));
        assertEquals("a.B.c(Unknown Source)", HeapThreadExtractor.frame("a.B.c()V (Unknown Source)"));
        assertEquals("a.B$$Lambda$1/0x0000000800b8c440.run(Unknown Source)", HeapThreadExtractor.frame("a.B$$Lambda$1/0x0000000800b8c440.run()V"));
        assertNull(HeapThreadExtractor.frame("garbage"));
    }

    @Test
    void JVMTI_상태값을_jstack_상태줄로() {
        assertEquals("WAITING (parking)", HeapThreadExtractor.stateLine(0x291));
        assertEquals("WAITING (on object monitor)", HeapThreadExtractor.stateLine(0x191));
        assertEquals("TIMED_WAITING (sleeping)", HeapThreadExtractor.stateLine(0xe1));
        assertEquals("TIMED_WAITING (parking)", HeapThreadExtractor.stateLine(0x2a1));
        assertEquals("BLOCKED (on object monitor)", HeapThreadExtractor.stateLine(0x401));
        assertEquals("RUNNABLE", HeapThreadExtractor.stateLine(0x5));
        assertEquals("UNKNOWN", HeapThreadExtractor.stateLine(null));
        assertFalse(HeapThreadExtractor.alive(0x2), "TERMINATED");
        assertFalse(HeapThreadExtractor.alive(0x0), "NEW");
        assertTrue(HeapThreadExtractor.alive(0x401));
    }

    @Test
    void Overview_와_threads_를_주소로_잇는다() throws IOException {
        HeapThreadExtractor.Extract x = HeapThreadExtractor.extract(res("synthetic-overview.html"), res("synthetic.threads"), "2026-11-26 10:15:30");
        assertEquals(2, x.notAlive(), "TERMINATED·NEW 는 뺀다");
        assertEquals(1, x.stackOnly(), "Overview 에 없는 스레드는 이름 없이 넣는다");
        assertEquals(1, x.withoutStack());
        assertEquals(1, x.withoutState());
        assertEquals(16, x.threads().size());
        Map<String, HeapThreadExtractor.HeapThread> by = x.byAddress();
        HeapThreadExtractor.HeapThread db = by.get("0xc1201900");
        assertEquals("http-9", db.name());
        assertEquals("jeus.servlet.engine.WebThread", db.objectType());
        assertEquals(9_876_543L, db.retained());
        assertEquals(9_800_000L, db.localsRetained());
        assertTrue(db.daemon());
        assertEquals(5, db.priority());
        assertEquals("java.net.SocketInputStream.socketRead0(Native Method)", db.frames().get(0));
        assertEquals("batch & <report> worker", by.get("0xc1400100").name(), "HTML 엔티티를 푼다");
        assertNull(by.get("0xc1400200"), "TERMINATED 스레드");
        assertTrue(x.dumpText().startsWith("2026-11-26 10:15:30\n" + HeapThreadExtractor.VM_LINE));
        assertTrue(ThreadDumpFormatDetector.looksLikeThreadDump(List.of(x.dumpText().split("\n"))), "만든 텍스트는 스레드 덤프로 판별된다");
    }

    @Test
    void 만든_텍스트를_분석기가_그대로_읽는다() throws IOException {
        ThreadDumpResult r = analyze(HeapThreadExtractor.extract(res("synthetic-overview.html"), res("synthetic.threads"), "2026-11-26 10:15:30"));
        assertEquals(0, r.getDiagnostics().getUnparsedLines(), () -> "" + r.getDiagnostics().getUnparsedSamples());
        assertEquals("heap", r.getMeta().getSource());
        assertEquals(1, r.getSnapshots().size());
        ThreadDumpResult.Snapshot s = r.getSnapshots().get(0);
        assertEquals(16, s.getThreads());
        assertEquals(Map.of("RUNNABLE", 2, "BLOCKED", 1, "WAITING", 11, "TIMED_WAITING", 1, "UNKNOWN", 1), s.getStates());
        assertEquals("absolute", r.getMeta().getTimeSource(), "힙 덤프 생성 시각이 스냅샷 시각이 된다");
        assertTrue(has(r, "HEAP_DERIVED"));
        assertTrue(finding(r, "HEAP_DERIVED").getDetail().contains("2개는 뺐습니다"));
        assertTrue(finding(r, "HEAP_DERIVED").getDetail().contains("상태를 알 수 없습니다"));
        assertFalse(has(r, "SINGLE_SNAPSHOT"), "힙 덤프는 원래 한 시점이다 — 스냅샷 1개 경고를 내지 않는다");
        assertFalse(has(r, "NO_TIMESTAMP"));
        assertTrue(r.getDeadlocks().isEmpty() && r.getLocks().isEmpty(), "락 줄이 없으므로 락 그래프도 없다");
        // JEUS 웹 스레드(http-#)는 실행기 프레임으로 요청 처리 풀로 인식된다 — 유휴 9 · 작업 중 3
        ThreadDumpResult.Pool p = r.getPools().stream().filter(x -> x.getStem().equals("http-#")).findFirst().orElseThrow();
        assertEquals("worker", p.getKind());
        assertEquals(List.of(12), p.getSizes());
        assertEquals(List.of(9), p.getIdle());
        // 스레드 객체 주소가 tid 자리에 있다 — 힙 점유를 다시 붙일 수 있다
        assertNotNull(track(r, "http-9"));
        assertEquals("0xc1201900", track(r, "http-9").getTid());
        assertEquals(9_876_543L, track(r, "http-9").getHr(), "스레드별 힙 점유가 붙는다");
        assertEquals(9_800_000L, track(r, "http-9").getHl());
        assertEquals("jeus.servlet.engine.WebThread", track(r, "http-9").getHc());
        assertEquals("app.hprof", r.getMeta().getHeapFilename());
        assertEquals(2, r.getDiagnostics().getHeapNotAliveThreads());
        // 결과 JSON 왕복에도 남는다
        ThreadDumpResult back = ThreadDumpResultCodec.fromJson(ThreadDumpResultCodec.toJson(r));
        assertEquals(9_876_543L, back.getTracks().get(r.getTracks().indexOf(track(r, "http-9"))).getHr());
        assertEquals("heap", back.getMeta().getSource());
    }

    @Test
    void 상태_열이_없는_옛_MAT_결과() throws IOException {
        HeapThreadExtractor.Extract x = HeapThreadExtractor.extract(res("synthetic-overview-nostate.html"), res("synthetic.threads"), null);
        assertEquals(3 + 13, x.threads().size(), "Overview 3개 + Overview 에 없는 스택 13개");
        assertEquals(16, x.withoutState());
        ThreadDumpResult r = analyze(x);
        assertEquals(16, r.getSnapshots().get(0).getStates().get("UNKNOWN"));
        assertEquals("none", r.getMeta().getTimeSource());
        assertFalse(has(r, "NO_TIMESTAMP"));
    }

    @Test
    void 한쪽만_있어도_만든다_둘_다_없으면_UNAVAILABLE() throws IOException {
        HeapThreadExtractor.Extract onlyOverview = HeapThreadExtractor.extract(res("synthetic-overview.html"), null, null);
        assertEquals(15, onlyOverview.threads().size());
        assertEquals(0, onlyOverview.withoutStack(), ".threads 가 아예 없으면 '스택 없음' 으로 세지 않는다");
        HeapThreadExtractor.Extract onlyStacks = HeapThreadExtractor.extract(null, res("synthetic.threads"), null);
        assertEquals(16, onlyStacks.threads().size());
        assertTrue(onlyStacks.threads().get(0).name().startsWith("thread@0x"));

        ThreadDumpException e = assertThrows(ThreadDumpException.class, () -> HeapThreadExtractor.extract(null, null, null));
        assertEquals("HEAP_THREADS_UNAVAILABLE", e.code());
        ThreadDumpException e2 = assertThrows(ThreadDumpException.class, () -> HeapThreadExtractor.extract("<table><tr><th>Name</th></tr></table>", "", null));
        assertEquals("HEAP_THREADS_UNAVAILABLE", e2.code());
        String allDead = res("synthetic-overview.html").replace(">0x291<", ">0x2<").replace(">0x5<", ">0x2<").replace(">0x401<", ">0x2<")
                .replace(">0xe1<", ">0x2<").replace(">0x191<", ">0x2<");
        ThreadDumpException e3 = assertThrows(ThreadDumpException.class, () -> HeapThreadExtractor.extract(allDead, null, null));
        assertTrue(e3.getMessage().contains("NEW·TERMINATED"));
    }

    @Test
    void 이상한_시각_문자열은_시각_줄로_넣지_않는다() throws IOException {
        HeapThreadExtractor.Extract x = HeapThreadExtractor.extract(res("synthetic-overview.html"), null, "2026/11/26 10:15");
        assertTrue(x.dumpText().startsWith(HeapThreadExtractor.VM_LINE));
    }
}

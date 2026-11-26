package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.heapdump.analyzer.parser.threaddump.ThreadDumpTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreadDumpResultCodecTest {

    /** 스레드 n개 × 스냅샷 3개, 스레드마다 스택이 다르고 깊이 40. 앞 2개는 서로 교착. */
    private static ThreadDumpResult big(int n) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 3; s++) {
            Dump d = new Dump("2026-11-26 10:00:0" + s, VM8)
                    .thread("A", "BLOCKED (on object monitor)", at("x.Y.a(Y.java:1)"), waitingToLock("0x02", "java.lang.Object"), locked("0x01", "java.lang.Object"))
                    .thread("B", "BLOCKED (on object monitor)", at("x.Y.b(Y.java:2)"), waitingToLock("0x01", "java.lang.Object"), locked("0x02", "java.lang.Object"));
            for (int i = 0; i < n; i++) {
                String[] body = new String[40];
                for (int k = 0; k < 40; k++) body[k] = at("com.example.very.long.packagename.Service" + i + ".method" + k + "(Service" + i + ".java:" + (k + s) + ")");
                d.thread("worker-" + i, "RUNNABLE", body);
            }
            sb.append(d.end());
        }
        return text(sb.toString());
    }

    @Test
    void 왕복() {
        ThreadDumpResult r = fixture("jdk8-deadlock-3snap.txt");
        String json = ThreadDumpResultCodec.toJson(r);
        ThreadDumpResult back = ThreadDumpResultCodec.fromJson(json);
        assertNotNull(back);
        assertEquals(json, ThreadDumpResultCodec.toJson(back), "직렬화 → 역직렬화 → 직렬화가 같다");
        assertEquals(ThreadDumpResult.SCHEMA, back.getSchema());
        assertTrue(json.length() < 200_000, "36 스레드 × 3 스냅샷 결과는 작아야 한다: " + json.length());
    }

    @Test
    void 손상된_JSON_은_null() {
        assertNull(ThreadDumpResultCodec.fromJson(null));
        assertNull(ThreadDumpResultCodec.fromJson(""));
        assertNull(ThreadDumpResultCodec.fromJson("{\"schema\":1,\"tracks\":[{"));
        assertNotNull(ThreadDumpResultCodec.fromJson("{\"schema\":1,\"futureField\":true}"), "모르는 필드는 무시한다");
    }

    @Test
    void 상한_안이면_건드리지_않는다() {
        ThreadDumpResult r = big(20);
        int tracks = r.getTracks().size();
        String json = ThreadDumpResultCodec.toJsonCapped(r);
        assertEquals(tracks, r.getTracks().size());
        assertFalse(has(r, "RESULT_TRIMMED"));
        assertFalse(json.contains("RESULT_TRIMMED"));
    }

    @Test
    void 상한을_넘으면_줄이되_소견과_참조는_지킨다() {
        ThreadDumpResult r = big(300);
        int findingsBefore = r.getFindings().size();
        int snapshotsBefore = r.getSnapshots().size();
        int full = ThreadDumpResultCodec.toJson(r).getBytes(StandardCharsets.UTF_8).length;
        int cap = full / 6;
        String json = ThreadDumpResultCodec.toJsonCapped(r, cap);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= cap, "상한 안으로 들어와야 한다");
        assertTrue(has(r, "RESULT_TRIMMED"));
        assertEquals(findingsBefore + 1, r.getFindings().size(), "기존 소견은 하나도 빠지지 않는다");
        assertEquals(snapshotsBefore, r.getSnapshots().size());
        assertEquals(1, r.getDeadlocks().size());
        assertEquals(1, r.getKpi().getDeadlocks());

        // 재색인 후에도 모든 인덱스가 유효하고, 교착이 여전히 A·B 를 가리킨다
        assertIndexesValid(r);
        ThreadDumpResult.Deadlock d = r.getDeadlocks().get(0);
        assertEquals(List.of("A", "B"), d.getTracks().stream().map(t -> name(r, t)).sorted().toList());
        ThreadDumpResult back = ThreadDumpResultCodec.fromJson(json);
        assertIndexesValid(back);
    }

    @Test
    void 스레드를_빼면_참조하던_모든_블록이_새_번호를_쓴다() {
        ThreadDumpResult r = big(10);
        int a = r.getTracks().indexOf(track(r, "A")), b = r.getTracks().indexOf(track(r, "B"));
        ThreadDumpResultCodec.retainTracks(r, i -> i == a || i == b || i % 2 == 0);
        assertIndexesValid(r);
        assertNotNull(track(r, "A"));
        assertNotNull(track(r, "B"));
        ThreadDumpResult.LockInfo l = r.getLocks().get(0);
        assertTrue(List.of("A", "B").contains(name(r, l.getOwner())));
        assertEquals(r.getTracks().size(), r.getNames().size(), "쓰이지 않는 이름도 사전에서 빠진다");
    }

    @Test
    void 깊이를_줄이면_잘린_프레임_수를_남긴다() {
        ThreadDumpResult r = big(5);
        ThreadDumpResult.Track w = track(r, "worker-0");
        int sid = w.getK().get(0);
        assertEquals(40, r.getStacks().get(sid).getF().size());
        ThreadDumpResultCodec.capDepth(r, 16);
        w = track(r, "worker-0");
        ThreadDumpResult.Stack st = r.getStacks().get(w.getK().get(0));
        assertEquals(16, st.getF().size());
        assertEquals(24, st.getCut());
        assertEquals("com.example.very.long.packagename.Service0.method0(Service0.java:0)", frames(r, w.getK().get(0)).get(0), "top 프레임은 남는다");
        assertIndexesValid(r);
    }

    static void assertIndexesValid(ThreadDumpResult r) {
        int tracks = r.getTracks().size(), stacks = r.getStacks().size(), frames = r.getFrames().size(), names = r.getNames().size();
        for (ThreadDumpResult.Track t : r.getTracks()) {
            assertTrue(t.getN() >= 0 && t.getN() < names, "이름 인덱스");
            for (int k : t.getK()) assertTrue(k >= -1 && k < stacks, "스택 인덱스 " + k + "/" + stacks);
            assertEquals(t.getS().length(), t.getK().size());
        }
        for (ThreadDumpResult.Stack s : r.getStacks()) for (int f : s.getF()) assertTrue(f >= 0 && f < frames, "프레임 인덱스");
        for (ThreadDumpResult.Finding f : r.getFindings()) for (int t : f.getTracks()) assertTrue(t >= 0 && t < tracks, "소견 → 스레드");
        for (ThreadDumpResult.LockInfo l : r.getLocks()) {
            assertTrue(l.getOwner() >= -1 && l.getOwner() < tracks);
            assertTrue(l.getRootBlocker() >= -1 && l.getRootBlocker() < tracks);
            for (int t : l.getWaiters()) assertTrue(t >= 0 && t < tracks);
        }
        for (ThreadDumpResult.Deadlock d : r.getDeadlocks()) {
            for (int t : d.getTracks()) assertTrue(t >= 0 && t < tracks);
            for (ThreadDumpResult.Chain c : d.getChain()) assertTrue(c.getT() >= -1 && c.getT() < tracks && c.getHeldBy() >= -1 && c.getHeldBy() < tracks);
        }
        for (ThreadDumpResult.StackGroup g : r.getStackGroups()) {
            assertTrue(g.getStackId() >= -1 && g.getStackId() < stacks);
            for (int t : g.getSample()) assertTrue(t >= 0 && t < tracks);
        }
        for (ThreadDumpResult.Stuck s : r.getStuck()) assertTrue(s.getT() >= 0 && s.getT() < tracks && s.getStackId() >= -1 && s.getStackId() < stacks);
        for (ThreadDumpResult.CpuTop c : r.getCpuTop()) assertTrue(c.getT() >= 0 && c.getT() < tracks);
    }
}

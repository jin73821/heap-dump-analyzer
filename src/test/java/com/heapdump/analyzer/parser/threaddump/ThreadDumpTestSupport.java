package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** 스레드 덤프 테스트 공용 — 픽스처 로드, 합성 덤프 빌더, 결과 조회 헬퍼. */
final class ThreadDumpTestSupport {

    private ThreadDumpTestSupport() {}

    static final String VM8 = "Full thread dump OpenJDK 64-Bit Server VM (25.422-b05 mixed mode):";
    static final String VM11 = "Full thread dump OpenJDK 64-Bit Server VM (11.0.20+8-LTS mixed mode, sharing):";
    static final String VM17 = "Full thread dump OpenJDK 64-Bit Server VM (17.0.8+7-LTS mixed mode, sharing):";

    static ThreadDumpResult fixture(String name) {
        return run(() -> open(name), ThreadDumpLimits.DEFAULT);
    }

    static InputStream open(String name) {
        InputStream in = ThreadDumpTestSupport.class.getResourceAsStream("/threaddump/" + name);
        if (in == null) throw new IllegalStateException("픽스처 없음: " + name);
        return in;
    }

    static ThreadDumpResult text(String text) {
        return text(text, ThreadDumpLimits.DEFAULT);
    }

    static ThreadDumpResult text(String text, ThreadDumpLimits limits) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return run(() -> new ByteArrayInputStream(b), limits);
    }

    static ThreadDumpResult bytes(byte[] b) {
        return run(() -> new ByteArrayInputStream(b), ThreadDumpLimits.DEFAULT);
    }

    static ThreadDumpResult run(ThreadDumpEngine.StreamOpener opener, ThreadDumpLimits limits) {
        try {
            return ThreadDumpEngine.analyze(opener, limits, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(text.getBytes(StandardCharsets.UTF_8)); }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ThreadDumpResult.Finding finding(ThreadDumpResult r, String code) {
        return r.getFindings().stream().filter(f -> code.equals(f.getCode())).findFirst().orElse(null);
    }

    static boolean has(ThreadDumpResult r, String code) { return finding(r, code) != null; }

    static List<String> codes(ThreadDumpResult r) {
        List<String> out = new ArrayList<>();
        for (ThreadDumpResult.Finding f : r.getFindings()) out.add(f.getCode());
        return out;
    }

    static String name(ThreadDumpResult r, int track) { return r.getNames().get(r.getTracks().get(track).getN()); }

    static ThreadDumpResult.Track track(ThreadDumpResult r, String name) {
        for (ThreadDumpResult.Track t : r.getTracks()) if (r.getNames().get(t.getN()).equals(name)) return t;
        return null;
    }

    /** 스택 id → 프레임 문자열 목록. */
    static List<String> frames(ThreadDumpResult r, int stackId) {
        List<String> out = new ArrayList<>();
        if (stackId < 0) return out;
        for (int f : r.getStacks().get(stackId).getF()) out.add(r.getFrames().get(f));
        return out;
    }

    // ── 합성 덤프 빌더 ──────────────────────────────────────────

    /** 스냅샷 하나를 조립한다. */
    static final class Dump {
        private final StringBuilder sb = new StringBuilder();
        private int num = 1;
        private final boolean jdk11;
        private double elapsed;

        Dump(String ts, String vmLine) {
            this(ts, vmLine, 0);
        }

        Dump(String ts, String vmLine, double elapsedBase) {
            if (ts != null) sb.append(ts).append('\n');
            sb.append(vmLine).append("\n\n");
            this.jdk11 = !vmLine.contains("(25.");
            this.elapsed = elapsedBase;
        }

        Dump numStart(int n) { this.num = n; return this; }

        /** Java 스레드 — body 는 상태 줄 뒤의 줄들(탭으로 시작). */
        Dump thread(String name, String state, String... body) {
            return thread(name, state, 0, body);
        }

        Dump thread(String name, String state, double cpuMs, String... body) {
            int n = num++;
            sb.append('"').append(name).append("\" #").append(n).append(" prio=5 os_prio=0 ");
            if (jdk11) sb.append("cpu=").append(String.format("%.2f", cpuMs)).append("ms elapsed=").append(String.format("%.2f", elapsed + 10)).append("s ");
            sb.append("tid=0x00007f00").append(String.format("%08x", 0x1000 * n)).append(" nid=0x").append(Integer.toHexString(0x100 + n))
                    .append(" ").append(nativeStatus(state)).append(" [0x00007f0000000000]\n");
            sb.append("   java.lang.Thread.State: ").append(state).append('\n');
            for (String l : body) sb.append(l).append('\n');
            sb.append('\n');
            return this;
        }

        Dump raw(String line) { sb.append(line).append('\n'); return this; }

        Dump vmThread(String name) {
            sb.append('"').append(name).append("\" os_prio=0 tid=0x00007f00ffff0000 nid=0x99 runnable \n\n");
            return this;
        }

        String end() {
            return sb + "JNI global references: 12\n\n";
        }

        String endNoTrailer() { return sb.toString(); }

        private static String nativeStatus(String state) {
            if (state.startsWith("BLOCKED")) return "waiting for monitor entry";
            if (state.startsWith("RUNNABLE")) return "runnable";
            return "waiting on condition";
        }
    }

    static String at(String frame) { return "\tat " + frame; }
    static String locked(String addr, String cls) { return "\t- locked <" + addr + "> (a " + cls + ")"; }
    static String waitingToLock(String addr, String cls) { return "\t- waiting to lock <" + addr + "> (a " + cls + ")"; }
    static String parking(String addr, String cls) { return "\t- parking to wait for  <" + addr + "> (a " + cls + ")"; }

    static final String[] IDLE_BODY = {
            "\tat sun.misc.Unsafe.park(Native Method)",
            "\t- parking to wait for  <0x00000000e0000001> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)",
            "\tat java.util.concurrent.locks.LockSupport.park(LockSupport.java:175)",
            "\tat java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:442)",
            "\tat java.util.concurrent.ThreadPoolExecutor.getTask(ThreadPoolExecutor.java:1074)",
            "\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1134)",
            "\tat java.lang.Thread.run(Thread.java:750)",
    };
}

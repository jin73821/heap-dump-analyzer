package com.heapdump.analyzer.util;

import com.heapdump.analyzer.util.JvmHeapCapture.Candidate;
import com.heapdump.analyzer.util.JvmHeapCapture.Capture;
import com.heapdump.analyzer.util.JvmHeapCapture.Match;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원격 JVM 힙 설정 수집 유틸 회귀 테스트 (2026-09-11).
 *
 * <p>급소 세 가지 — ① 파싱: 마지막 지정이 이기고 env·컨테이너·@argfile 을 구분한다 ② 매칭: pid 재사용·다중 후보를
 * "그럴듯한 오답"이 아니라 미확정으로 처리한다 ③ 보안: 자격증명이 들어갈 수 있는 {@code -D}/{@code -javaagent}/
 * {@code -XX:OnOutOfMemoryError} 값이 JSON 어디에도 남지 않는다. 픽스처는 원격 스크립트 출력 형식을 코드로 합성한다.
 */
class JvmHeapCaptureTest {

    private static final long GB = 1L << 30;
    private static final long MB = 1L << 20;
    private static final String US = "\u001f";
    private static final long NOW = 1_800_000_000L;

    // ── 픽스처 빌더: 스크립트 출력 형식 그대로 ────────────────────

    private static String procBlock(int pid, String user, String etime, String cwd, String[] argv,
                                    String[] envLines, boolean envOk, boolean container) {
        StringBuilder sb = new StringBuilder();
        sb.append("__PID__ ").append(pid).append('\n');
        sb.append("__PSLINE__ ").append(user).append("   ").append(etime).append('\n');
        sb.append("__EXE__ ").append(argv[0]).append('\n'); // 실제 스크립트는 readlink /proc/pid/exe
        sb.append("__CWD__ ").append(cwd == null ? "" : cwd).append('\n');
        sb.append("__CMD__ ").append(String.join(US, argv)).append(US).append('\n');
        if (envOk) sb.append("__ENVOK__\n");
        if (envLines != null) for (String e : envLines) sb.append("__ENV__ ").append(e).append('\n');
        if (container) sb.append("__CONTAINER__\n");
        return sb.toString();
    }

    private static String output(long memTotalKb, int nproc, boolean ended, String... blocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Welcome to  SERVER  — Authorized use only\r\n"); // 배너 + CRLF
        sb.append("__MEM__\n");
        sb.append("MemTotal:       ").append(memTotalKb).append(" kB\n");
        sb.append("MemAvailable:   ").append(memTotalKb / 2).append(" kB\n");
        sb.append("__NOW__ ").append(NOW).append('\n');
        sb.append("__NPROC__ ").append(nproc).append('\n');
        sb.append("__PS__\n");
        for (String b : blocks) sb.append(b);
        if (ended) sb.append("__END__\n");
        return sb.toString();
    }

    private static Candidate cand(int pid, Long startEpoch, Long xmx, String heapDumpPath, String cwd,
                                  Map<String, String> markers, String mainClass) {
        return new Candidate(pid, "jeus", startEpoch, mainClass, cwd, "/opt/jdk/bin/java", null, xmx,
                xmx == null ? null : "cmdline", heapDumpPath, List.of(), 0, markers, true, false, false, false);
    }

    private static Capture capture(Long mtime, Candidate... cs) {
        return new Capture(1, NOW, NOW, 16 * GB, 8 * GB, 300, false, mtime, List.of(cs), null, null, List.of(), null);
    }

    private static Candidate parse(String... argv) {
        return JvmHeapCapture.parseCommandLine(1, "u", null, null, null, List.of(argv), Map.of(), true, 16 * GB, false);
    }

    // ── 크기 표기 ───────────────────────────────────────────────

    @Test
    void parseSizeHandlesUnitsAndBareBytes() {
        assertEquals(4 * GB, JvmHeapCapture.parseSize("4g"));
        assertEquals(4 * GB, JvmHeapCapture.parseSize("4G"));
        assertEquals(8192 * MB, JvmHeapCapture.parseSize("8192m"));
        assertEquals(64 * 1024L, JvmHeapCapture.parseSize("64k"));
        assertEquals(4294967296L, JvmHeapCapture.parseSize("4294967296"));
        assertEquals(2 * GB, JvmHeapCapture.parseSize(" 2g "));
        assertThrows(IllegalArgumentException.class, () -> JvmHeapCapture.parseSize("abc"));
        assertThrows(IllegalArgumentException.class, () -> JvmHeapCapture.parseSize(""));
        assertThrows(IllegalArgumentException.class, () -> JvmHeapCapture.parseSize(null));
        assertThrows(IllegalArgumentException.class, () -> JvmHeapCapture.parseSize("4gb;rm"));
    }

    @Test
    void formatSizePrefersLargestExactUnit() {
        assertEquals("8g", JvmHeapCapture.formatSize(8 * GB));
        assertEquals("512m", JvmHeapCapture.formatSize(512 * MB));
        assertEquals("1536m", JvmHeapCapture.formatSize(1536 * MB));
        assertEquals("64k", JvmHeapCapture.formatSize(64 * 1024));
        assertEquals("1000", JvmHeapCapture.formatSize(1000));
        for (String s : new String[]{"4g", "8192m", "3k"}) {
            assertEquals(s.equals("8192m") ? "8g" : s, JvmHeapCapture.formatSize(JvmHeapCapture.parseSize(s)));
        }
    }

    // ── 명령줄 해석 ─────────────────────────────────────────────

    @Test
    void lastSpecificationWins() {
        Candidate c = parse("java", "-Xmx2g", "-XX:MaxHeapSize=4294967296", "-Xmx8g", "-Xms1g", "Main");
        assertEquals(8 * GB, c.xmxBytes());
        assertEquals(1 * GB, c.xmsBytes());
        assertEquals("cmdline", c.xmxOrigin());
        assertEquals("Main", c.mainClass());
    }

    @Test
    void ramPercentageIsConvertedOnlyWhenNoExplicitValue() {
        Candidate pct = parse("java", "-XX:MaxRAMPercentage=50", "Main");
        assertEquals(8 * GB, pct.xmxBytes());
        assertEquals("ergonomic", pct.xmxOrigin());
        assertTrue(JvmHeapCapture.candidateFlags(pct).contains(JvmHeapCapture.FLAG_ERGONOMIC));

        Candidate explicit = parse("java", "-XX:MaxRAMPercentage=50", "-Xmx3g", "Main");
        assertEquals(3 * GB, explicit.xmxBytes());
        assertEquals("cmdline", explicit.xmxOrigin());

        Candidate maxRam = parse("java", "-XX:MaxRAM=8g", "-XX:MaxRAMPercentage=25", "Main");
        assertEquals(2 * GB, maxRam.xmxBytes());
    }

    @Test
    void missingXmxIsEstimatedAsQuarterOfMemoryExceptInContainer() {
        Candidate host = parse("java", "-jar", "app.jar");
        assertEquals(4 * GB, host.xmxBytes());
        assertEquals("estimated", host.xmxOrigin());
        assertEquals("app.jar", host.mainClass());
        assertTrue(JvmHeapCapture.candidateFlags(host).contains(JvmHeapCapture.FLAG_ESTIMATED));

        Candidate container = JvmHeapCapture.parseCommandLine(1, "u", null, null, null,
                List.of("java", "-jar", "app.jar"), Map.of(), true, 16 * GB, true);
        assertNull(container.xmxBytes(), "컨테이너에서는 호스트 메모리로 추정하면 안 된다");
        assertTrue(JvmHeapCapture.candidateFlags(container).contains(JvmHeapCapture.FLAG_CONTAINER));
        assertFalse(JvmHeapCapture.candidateFlags(container).contains(JvmHeapCapture.FLAG_ESTIMATED));
    }

    @Test
    void environmentOptionsFollowJvmPrecedence() {
        Map<String, String> env = Map.of(
                "JAVA_TOOL_OPTIONS", "-Xmx1g -Xms256m",
                "_JAVA_OPTIONS", "-Xmx6g");
        Candidate c = JvmHeapCapture.parseCommandLine(1, "u", null, null, null,
                List.of("java", "-Xmx4g", "Main"), env, true, 16 * GB, false);
        assertEquals(6 * GB, c.xmxBytes(), "_JAVA_OPTIONS 는 명령줄을 이긴다");
        assertEquals(256 * MB, c.xmsBytes(), "JAVA_TOOL_OPTIONS 는 명령줄에 없는 값을 채운다");
        assertEquals("env", c.xmxOrigin());
        assertTrue(JvmHeapCapture.candidateFlags(c).contains(JvmHeapCapture.FLAG_ENV));

        Candidate toolOnly = JvmHeapCapture.parseCommandLine(1, "u", null, null, null,
                List.of("java", "-Xmx4g", "Main"), Map.of("JAVA_TOOL_OPTIONS", "-Xmx1g"), true, 16 * GB, false);
        assertEquals(4 * GB, toolOnly.xmxBytes(), "JAVA_TOOL_OPTIONS 는 명령줄에 진다");
    }

    @Test
    void unreadableEnvironmentAndArgfileAreFlagged() {
        Candidate c = JvmHeapCapture.parseCommandLine(1, "u", null, null, null,
                List.of("java", "@/opt/app/jvm.options", "-Xms1g", "Main"), Map.of(), false, 16 * GB, false);
        assertTrue(c.argfile());
        var flags = JvmHeapCapture.candidateFlags(c);
        assertTrue(flags.contains(JvmHeapCapture.FLAG_ARGFILE));
        assertTrue(flags.contains(JvmHeapCapture.FLAG_ENV_UNKNOWN));
    }

    @Test
    void sensitiveOptionsAreDiscardedButMarkersKept() {
        Candidate c = parse("java", "-Ddb.password=S3cret!", "-Djeus.server.name=srv1", "-Dcatalina.base=/opt/tc",
                "-javaagent:/opt/agent.jar=licenseKey=ABC", "-XX:OnOutOfMemoryError=kill -9 %p",
                "-agentlib:jdwp=transport=dt_socket,address=5005", "-XX:+UseG1GC", "-Xmx2g",
                "-XX:HeapDumpPath=/dumps", "jeus.server.Bootstrapper", "-domain", "dom1", "-server", "srv1");
        Capture cap = capture(null, c);
        String json = JvmHeapCapture.toJson(cap);
        for (String forbidden : new String[]{"S3cret", "licenseKey", "OnOutOfMemoryError", "jdwp", "db.password"}) {
            assertFalse(json.contains(forbidden), "JSON 에 남으면 안 되는 값: " + forbidden);
        }
        assertEquals("srv1", c.markers().get("jeus.server.name"));
        assertEquals("dom1", c.markers().get("jeus.domain.name"), "JEUS 위치 인자 -domain 도 마커로");
        assertEquals("/opt/tc", c.markers().get("catalina.base"));
        assertTrue(c.options().contains("-XX:+UseG1GC"));
        assertTrue(c.options().contains("-Xmx2g"));
        assertEquals("/dumps", c.heapDumpPath());
        assertEquals(3, c.otherOptionCount(), "폐기된 옵션은 개수만(javaagent/OnOOM/agentlib)");
    }

    @Test
    void classpathValuesAreNotMistakenForMainClass() {
        Candidate c = parse("java", "-cp", "/opt/lib/a.jar:/opt/lib/b.jar", "-Xmx1g", "com.example.Main", "--port", "8080");
        assertEquals("com.example.Main", c.mainClass());
    }

    // ── 출력 파싱 ───────────────────────────────────────────────

    @Test
    void parseOutputIgnoresBannerHandlesCrlfAndUsSeparatedArgs() {
        String block = procBlock(4242, "jeus", "1-02:03:04", "/opt/jeus/domains/d1/servers/s1",
                new String[]{"/opt/jdk/bin/java", "-Xmx8g", "-XX:HeapDumpPath=/data/heap dumps", "Main"},
                new String[]{"JAVA_TOOL_OPTIONS=-Xms512m"}, true, false);
        Capture cap = JvmHeapCapture.parseOutput(output(16_777_216L, 300, true, block), NOW);
        assertFalse(cap.truncated());
        assertEquals(16 * GB, cap.memTotal());
        assertEquals(300, cap.procVisible());
        assertEquals(1, cap.candidates().size());
        Candidate c = cap.candidates().get(0);
        assertEquals(4242, c.pid());
        assertEquals("jeus", c.user());
        assertEquals("/data/heap dumps", c.heapDumpPath(), "공백이 든 인자는 US 구분자로 온전히 살아야 한다");
        assertEquals(8 * GB, c.xmxBytes());
        assertEquals(512 * MB, c.xmsBytes());
        long etime = (1 * 24 + 2) * 3600L + 3 * 60 + 4;
        assertEquals(NOW - etime, c.startEpoch());
        assertTrue(c.envReadable());
        assertNull(cap.note());
    }

    @Test
    void parseEtimeCoversAllFourForms() {
        assertEquals(34L, JvmHeapCapture.parseEtimeSeconds("00:34"));
        assertEquals(12 * 60 + 34L, JvmHeapCapture.parseEtimeSeconds("12:34"));
        assertEquals(2 * 3600 + 3 * 60 + 4L, JvmHeapCapture.parseEtimeSeconds("02:03:04"));
        assertEquals((5 * 24 + 2) * 3600 + 3 * 60 + 4L, JvmHeapCapture.parseEtimeSeconds("5-02:03:04"));
        assertNull(JvmHeapCapture.parseEtimeSeconds("garbage"));
    }

    @Test
    void missingEndMarkerIsTruncatedAndMissingMemMarkerIsNoted() {
        String block = procBlock(1, "u", "01:00", null, new String[]{"java", "-Xmx1g", "M"}, null, true, false);
        Capture cut = JvmHeapCapture.parseOutput(output(1_000_000L, 200, false, block), NOW);
        assertTrue(cut.truncated());
        assertEquals(1, cut.candidates().size(), "끊겨도 그때까지의 후보는 살린다");
        assertNotNull(cut.note());

        Capture none = JvmHeapCapture.parseOutput("Permission denied, please try again.", NOW);
        assertTrue(none.truncated());
        assertTrue(none.candidates().isEmpty());
    }

    @Test
    void mtimeLineBeforeMemMarkerIsPickedUp() {
        String out = "__MTIME__ 1799990000\n" + output(1_000_000L, 200, true);
        Capture cap = JvmHeapCapture.parseOutput(out, NOW);
        assertEquals(1799990000L, cap.dumpMtimeEpoch());
        assertTrue(cap.candidates().isEmpty());
        assertEquals("java 프로세스 없음", cap.note());
    }

    @Test
    void restrictedProcessListIsNoted() {
        Capture cap = JvmHeapCapture.parseOutput(output(1_000_000L, 12, true), NOW);
        assertTrue(cap.processListRestricted());
        assertTrue(cap.note().contains("제한"));
    }

    @Test
    void candidateWithoutJavaCommIsStillRecognizedByOptions() {
        String block = procBlock(7, "was", "01:00", null, new String[]{"/opt/was/bin/wrapper", "-Xmx3g", "-XX:+UseG1GC"}, null, true, false);
        Capture cap = JvmHeapCapture.parseOutput(output(1_000_000L, 200, true, block), NOW);
        assertEquals(1, cap.candidates().size());
        String nonJava = procBlock(8, "u", "01:00", null, new String[]{"/usr/bin/python3", "app.py"}, null, true, false);
        Capture cap2 = JvmHeapCapture.parseOutput(output(1_000_000L, 200, true, nonJava), NOW);
        assertTrue(cap2.candidates().isEmpty());
    }

    @Test
    void candidateCountIsCapped() {
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            blocks.add(procBlock(100 + i, "u", "01:00", null, new String[]{"java", "-Xmx1g", "M"}, null, true, false));
        }
        Capture cap = JvmHeapCapture.parseOutput(output(1_000_000L, 200, true, blocks.toArray(new String[0])), NOW);
        assertEquals(JvmHeapCapture.MAX_CANDIDATES, cap.candidates().size());
    }

    // ── 매칭 ────────────────────────────────────────────────────

    @Test
    void pidFromFilenameSurvivesGzipAndRename() {
        assertEquals(12345, JvmHeapCapture.pidFromFilename("java_pid12345.hprof"));
        assertEquals(12345, JvmHeapCapture.pidFromFilename("java_pid12345.hprof.gz"));
        assertEquals(12345, JvmHeapCapture.pidFromFilename("java_pid12345_202609101530.hprof"));
        assertNull(JvmHeapCapture.pidFromFilename("heap.hprof"));
    }

    @Test
    void matchByPidWhenProcessPredatesDump() {
        Capture cap = capture(NOW - 100,
                cand(100, NOW - 10_000, 2 * GB, null, null, Map.of(), "A"),
                cand(200, NOW - 10_000, 4 * GB, null, null, Map.of(), "B"));
        Match m = JvmHeapCapture.match(cap, "java_pid200.hprof", "/dumps", null, null);
        assertEquals(200, cap.candidates().get(m.index()).pid());
        assertEquals("pid", m.reason());
        assertFalse(m.flags().contains(JvmHeapCapture.FLAG_RESTARTED));
    }

    @Test
    void reusedPidAfterRestartIsDemotedAndFlagged() {
        // pid 200 은 덤프(NOW-5000) 뒤에 시작 — 재사용 가능성. 다른 근거(cwd)가 있으면 그쪽이 이긴다.
        Capture cap = capture(NOW - 5_000,
                cand(100, NOW - 10_000, 2 * GB, null, "/dumps", Map.of(), "A"),
                cand(200, NOW - 100, 4 * GB, null, "/elsewhere", Map.of(), "B"));
        Match m = JvmHeapCapture.match(cap, "java_pid200.hprof", "/dumps/", null, null);
        assertEquals(100, cap.candidates().get(m.index()).pid());
        assertEquals("cwd", m.reason());

        // 다른 근거가 없으면 약한 pid 근거를 쓰되 restarted 로 표시
        Capture cap2 = capture(NOW - 5_000,
                cand(100, NOW - 10_000, 2 * GB, null, null, Map.of(), "A"),
                cand(200, NOW - 100, 4 * GB, null, null, Map.of(), "B"));
        Match m2 = JvmHeapCapture.match(cap2, "java_pid200.hprof", "/dumps", null, null);
        assertEquals(200, cap2.candidates().get(m2.index()).pid());
        assertTrue(m2.flags().contains(JvmHeapCapture.FLAG_RESTARTED));
    }

    @Test
    void matchByHeapDumpPathRequiresUniqueness() {
        Capture cap = capture(null,
                cand(1, null, 2 * GB, "/dumps", null, Map.of(), "A"),
                cand(2, null, 4 * GB, "/other", null, Map.of(), "B"));
        Match m = JvmHeapCapture.match(cap, "heap.hprof", "/dumps", null, null);
        assertEquals(0, m.index());
        assertEquals("heapdumppath", m.reason());

        Capture both = capture(null,
                cand(1, null, 2 * GB, "/dumps", null, Map.of(), "A"),
                cand(2, null, 4 * GB, "/dumps/", null, Map.of(), "B"));
        Match m2 = JvmHeapCapture.match(both, "heap.hprof", "/dumps", null, null);
        assertNull(m2.index(), "둘 다 같은 HeapDumpPath 면 확정하면 안 된다");
        assertEquals("ambiguous", m2.reason());
    }

    @Test
    void matchBySystemPropertiesAfterAnalysis() {
        Capture cap = capture(null,
                cand(1, null, 2 * GB, null, null, Map.of("jeus.server.name", "srv1", "jeus.domain.name", "d1"), "jeus.server.Bootstrapper"),
                cand(2, null, 4 * GB, null, null, Map.of("jeus.server.name", "srv2", "jeus.domain.name", "d1"), "jeus.server.Bootstrapper"));
        Match m = JvmHeapCapture.match(cap, "heap.hprof", "/dumps",
                Map.of("jeus.server.name", "srv2", "jeus.domain.name", "d1"), null);
        assertEquals(1, m.index());
        assertEquals("sysprop:jeus.server.name", m.reason());

        Capture byMain = capture(null,
                cand(1, null, 2 * GB, null, null, Map.of(), "com.a.Main"),
                cand(2, null, 4 * GB, null, null, Map.of(), "com.b.Main"));
        Match m2 = JvmHeapCapture.match(byMain, "heap.hprof", "/dumps",
                Map.of("sun.java.command", "com.b.Main --port 80"), null);
        assertEquals(1, m2.index());
        assertEquals("maincommand", m2.reason());
    }

    @Test
    void singleCandidateAndSameXmxFallbacks() {
        Capture one = capture(null, cand(1, null, 2 * GB, null, null, Map.of(), "A"));
        assertEquals("single", JvmHeapCapture.match(one, "x.hprof", "/d", null, null).reason());

        Capture same = capture(null,
                cand(1, null, 2 * GB, null, null, Map.of(), "A"),
                cand(2, null, 2 * GB, null, null, Map.of(), "B"));
        Match m = JvmHeapCapture.match(same, "x.hprof", "/d", null, null);
        assertEquals(0, m.index());
        assertEquals("same-xmx", m.reason());
        assertTrue(m.flags().contains(JvmHeapCapture.FLAG_AMBIGUOUS_PID));

        Capture diff = capture(null,
                cand(1, null, 2 * GB, null, null, Map.of(), "A"),
                cand(2, null, 4 * GB, null, null, Map.of(), "B"));
        assertNull(JvmHeapCapture.match(diff, "x.hprof", "/d", null, null).index());

        assertEquals("no-candidates", JvmHeapCapture.match(capture(null), "x.hprof", "/d", null, null).reason());
    }

    @Test
    void explicitDumpEpochOverridesRemoteMtime() {
        Capture cap = capture(NOW - 100, cand(1, NOW - 10_000, 2 * GB, null, null, Map.of(), "A"));
        assertFalse(JvmHeapCapture.match(cap, "x.hprof", "/d", null, null).flags().contains(JvmHeapCapture.FLAG_RESTARTED),
                "mtime 기준으로는 재기동 아님");
        assertTrue(JvmHeapCapture.match(cap, "x.hprof", "/d", null, NOW - 20_000).flags().contains(JvmHeapCapture.FLAG_RESTARTED),
                "hprof 헤더 시각(더 정확)이 주어지면 그걸로 판정");
    }

    // ── JSON / 스크립트 ─────────────────────────────────────────

    @Test
    void jsonRoundTripAndShrinkUnderLimit() {
        Candidate c = parse("java", "-Xmx2g", "-XX:+UseG1GC", "Main");
        Capture cap = capture(NOW - 1, c).withMatch(new Match(0, "single", java.util.Set.of(JvmHeapCapture.FLAG_RESTARTED)));
        String json = JvmHeapCapture.toJson(cap);
        Capture back = JvmHeapCapture.fromJson(json);
        assertNotNull(back);
        assertEquals(cap, back);
        assertEquals(List.of(JvmHeapCapture.FLAG_RESTARTED), back.matchFlags());
        assertNull(JvmHeapCapture.fromJson("{not json"));
        assertNull(JvmHeapCapture.fromJson(null));
        assertNotNull(JvmHeapCapture.fromJson("{\"schema\":1,\"futureField\":true}"), "모르는 필드는 무시(전방 호환)");

        // 후보 20개 × 긴 옵션 40개 × 마커 → 60KB 초과 → 축소 후에도 파싱 가능·상한 이내
        List<Candidate> big = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<String> opts = new ArrayList<>();
            for (int k = 0; k < 40; k++) opts.add("-XX:HeapDumpPath=" + "x".repeat(180) + k);
            big.add(new Candidate(i, "u".repeat(30), NOW, "M".repeat(200), "/c".repeat(100), "/e".repeat(100), GB, GB, "cmdline",
                    "/h".repeat(100), opts, 0, Map.of("user.dir", "d".repeat(200), "catalina.base", "b".repeat(200)),
                    true, false, false, false));
        }
        Capture huge = new Capture(1, NOW, NOW, GB, GB, 100, false, null, big, 3, "pid", List.of(), null);
        String slim = JvmHeapCapture.toJson(huge);
        assertTrue(slim.getBytes(StandardCharsets.UTF_8).length <= JvmHeapCapture.MAX_JSON_BYTES, "TEXT 컬럼 상한");
        Capture slimBack = JvmHeapCapture.fromJson(slim);
        assertNotNull(slimBack);
        assertEquals(GB, slimBack.candidates().get(0).xmxBytes(), "축소해도 힙 값은 유지");
    }

    @Test
    void remoteCommandIsShellNeutralAndCarriesEscapedPath() {
        String cmd = JvmHeapCapture.buildRemoteCommand("/dumps/it's here/java_pid1.hprof");
        assertTrue(cmd.matches("^echo [A-Za-z0-9+/=]+ \\| base64 -d \\| sh$"), "셸 특수문자 없는 한 줄: " + cmd);
        String b64 = cmd.substring(5, cmd.indexOf(' ', 5));
        String script = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        assertTrue(script.startsWith("echo \"__MTIME__ $(stat -c %Y '/dumps/it'\\''s here/java_pid1.hprof'"));
        assertTrue(script.endsWith("echo __END__"));
        assertTrue(script.contains("/proc/$p/cmdline"));
        assertEquals(JvmHeapCapture.REMOTE_SCRIPT, JvmHeapCapture.buildScript(null));
        assertFalse(JvmHeapCapture.REMOTE_SCRIPT.contains("ps -eo pid,user,etimes,args"), "폭 절단·etimes 의존 명령은 쓰지 않는다");
    }

    @Test
    void flagLabelsAreKoreanAndKnown() {
        for (String f : new String[]{"estimated", "ergonomic", "restarted", "env", "env-unknown", "argfile", "container", "ambiguous-pid"}) {
            assertFalse(JvmHeapCapture.flagLabel(f).equals(f), "라벨 누락: " + f);
            assertFalse(JvmHeapCapture.flagHint(f).equals(f), "힌트 누락: " + f);
        }
        assertEquals("weird", JvmHeapCapture.flagLabel("weird"));
    }
}

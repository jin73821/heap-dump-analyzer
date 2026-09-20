package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 회전 GC 로그 이어 붙이기 — 엔진 다중 입력 계약 (2026-09-20).
 *
 * <p>가장 중요한 단언은 {@link #singleSourceJsonIsIdenticalToLegacyPath()} 다. 조각이 하나면 결과 JSON 이
 * 종전 경로와 <b>한 글자도</b> 달라지면 안 된다 — 이 기능 전체의 안전벨트이고, 실측 로그 회귀 테스트
 * ({@code GcLogRealLogRegressionTest}, 절대 줄 번호 105·65 포함)가 그대로 통과하는 근거다.
 */
class GcLogMultiSourceEngineTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneId.systemDefault());

    private static final long T0 = GcLogSupport.parseIsoEpochMs("2026-09-19T22:00:00.000+0900");

    static String iso(long epochMs) { return ISO.format(Instant.ofEpochMilli(epochMs)); }
    static String up(double sec) { return String.format(Locale.ROOT, "%.3f", sec); }

    /** JDK 8 Parallel 헤더 — 회전본 중 "실행의 첫 파일"에만 붙는다. */
    static String header(long heapMaxBytes) {
        return String.join("\n",
                "OpenJDK 64-Bit Server VM (25.422-b05) for linux-amd64 JRE (1.8.0_422-b05), built on Jul 11 2024",
                "Memory: 4k page, physical 131479652k(65699592k free), swap 67108860k(62273396k free)",
                "CommandLine flags: -XX:InitialHeapSize=2147483648 -XX:MaxHeapSize=" + heapMaxBytes + " -XX:+UseParallelGC",
                "");
    }

    static String young(long epochMs, double upSec, long beforeK, long afterK) {
        return iso(epochMs) + ": " + up(upSec) + ": [GC (Allocation Failure) [PSYoungGen: 524800K->39141K(611840K)] "
                + beforeK + "K->" + afterK + "K(2010112K), 0.0500000 secs] [Times: user=0.10 sys=0.01, real=0.05 secs]";
    }

    static String full(long epochMs, double upSec, long beforeK, long afterK) {
        return iso(epochMs) + ": " + up(upSec) + ": [Full GC (Ergonomics) [PSYoungGen: 640K->0K(697344K)] "
                + "[ParOldGen: " + beforeK + "K->" + afterK + "K(1398272K)] " + beforeK + "K->" + afterK + "K(2095616K), "
                + "[Metaspace: 87685K->87685K(1130496K)], 0.3000000 secs] [Times: user=0.50 sys=0.01, real=0.30 secs]";
    }

    /** young {@code n} 건짜리 조각. {@code header} 가 true 면 JVM 기동 헤더를 붙인다(= 새 실행). */
    static String segment(long startEpochMs, double startUptime, int n, double stepSec, boolean header) {
        StringBuilder sb = new StringBuilder();
        if (header) sb.append(header(2147483648L));
        for (int i = 0; i < n; i++) {
            sb.append(young(startEpochMs + (long) (i * stepSec * 1000), startUptime + i * stepSec, 500000, 40000)).append('\n');
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("조각이 하나면 결과 JSON 이 종전 단일 입력 경로와 완전히 같다")
    void singleSourceJsonIsIdenticalToLegacyPath() throws IOException {
        String text = segment(T0, 3.0, 40, 10, true);

        GcLogResult legacy = GcLogTestSupport.engine(text);
        GcLogResult viaList = GcLogTestSupport.engineMulti(List.of(GcLogTestSupport.src("gc.log", text)));

        assertEquals(GcLogResultCodec.toJson(legacy), GcLogResultCodec.toJson(viaList),
                "조각 1개짜리 목록 경로가 단일 입력과 다른 결과를 낸다 — 기존 분석·저장본이 전부 흔들린다");
        assertNull(viaList.getSegments(), "파일 하나인데 segments 가 실렸다 (null 이어야 '구성 파일 없음' 으로 읽힌다)");
        assertNull(viaList.getMeta().getXBasis(), "파일 하나인데 x 기준이 바뀌었다");
    }

    @Test
    @DisplayName("이벤트 예산과 seq 는 조각을 가로질러 전역이다 — 조각마다 0 으로 되돌리지 않는다")
    void eventBudgetAndSeqAreGlobalAcrossMembers() throws IOException {
        List<GcLogSource> sources = List.of(
                GcLogTestSupport.src("gc.log.0", segment(T0, 3.0, 5, 10, true)),
                GcLogTestSupport.src("gc.log.1", segment(T0 + 100_000, 103.0, 5, 10, false)),
                GcLogTestSupport.src("gc.log", segment(T0 + 200_000, 203.0, 5, 10, false)));

        GcLogResult r = GcLogTestSupport.engineMulti(sources, new ParseLimits(1 << 30, 4096, 7), null);

        assertEquals(7, r.getKpi().getEventCount(), "예산이 조각 수만큼 곱해졌다(조각마다 maxEvents 를 새로 준 것)");
        assertTrue(r.getMeta().isTruncated(), "상한에 걸렸는데 truncated 가 아니다");

        List<Integer> seqs = new ArrayList<>();
        for (GcLogResult.EventRow row : r.getEvents()) seqs.add(row.getSeq());
        assertEquals(seqs.size(), seqs.stream().distinct().count(), "seq 가 중복됐다 — 이벤트 표 기본 정렬이 뒤섞인다");

        boolean stopNoted = r.getFindings().stream().anyMatch(f -> "LOG_TRUNCATED_LIMIT".equals(f.getCode())
                && f.getDetail() != null && f.getDetail().contains("gc.log.1"));
        assertTrue(stopNoted, "어느 파일에서 멈췄는지 밝히지 않는다 — 뒤쪽 파일이 통째로 빠진 채 성공처럼 보인다");
    }

    @Test
    @DisplayName("조각 경계에서 파서를 새로 만든다 — 잘린 이벤트가 다음 파일 헤더와 한 덩어리가 되지 않는다")
    void memberBoundaryResetsParserState() throws IOException {
        // 앞 조각의 마지막 줄이 이벤트 도중에 끊겼다(회전 경계의 실제 모습)
        String cut = header(2147483648L)
                + young(T0, 3.0, 500000, 40000) + "\n"
                + iso(T0 + 10_000) + ": 13.000: [GC (Allocation Failure) [PSYoungGen: 524800K->39141K(611840K)]";
        String next = header(2147483648L) + young(T0 + 60_000, 3.0, 500000, 41000) + "\n";

        GcLogResult r = GcLogTestSupport.engineMulti(List.of(
                GcLogTestSupport.src("gc.log.0", cut), GcLogTestSupport.src("gc.log", next)));

        assertEquals(2, r.getKpi().getEventCount(), "잘린 이벤트가 다음 조각과 합쳐졌거나 통째로 사라졌다");
        assertTrue(r.getMeta().getDroppedIncomplete() >= 1, "미종결 이벤트를 세지 않았다");
        for (GcLogResult.EventRow row : r.getEvents()) {
            assertTrue(row.getPauseMs() != null && row.getPauseMs() < 1000,
                    "두 이벤트가 하나로 뭉쳐 비정상적인 pause 가 나왔다: " + row.getPauseMs());
        }
    }

    @Test
    @DisplayName("gz 조각과 평문 조각을 섞어도 읽는다 — gz 판정은 조각마다 한다")
    void gzAndPlainMembersMix() throws IOException {
        GcLogResult r = GcLogTestSupport.engineMulti(List.of(
                GcLogTestSupport.srcGz("gc.log.0.gz", segment(T0, 3.0, 4, 10, true)),
                GcLogTestSupport.src("gc.log", segment(T0 + 100_000, 103.0, 4, 10, false))));

        assertEquals(8, r.getKpi().getEventCount(), "gz + 평문 혼합을 읽지 못했다");
        assertNotNull(r.getSegments());
        assertEquals(2, r.getSegments().size());
    }

    @Test
    @DisplayName("형식이 다른 로그가 섞이면 파일명을 밝히고 거부한다")
    void formatMismatchIsRejected() {
        String unified = GcLogTestSupport.g1Young(0, GcLogTestSupport.TS, 1.0, "Normal", "G1 Evacuation Pause",
                "14M->2M(258M)", "3.363", 5, 0, 1, 1, 0, 0);
        GcLogEngine.GcLogException e = assertThrows(GcLogEngine.GcLogException.class, () ->
                GcLogTestSupport.engineMulti(List.of(
                        GcLogTestSupport.src("gc.log.0", segment(T0, 3.0, 3, 10, true)),
                        GcLogTestSupport.src("gc-unified.log", unified))));
        assertEquals("FORMAT_MISMATCH", e.code());
        assertTrue(e.getMessage().contains("gc-unified.log"), "어느 파일이 문제인지 밝히지 않는다: " + e.getMessage());
    }

    @Test
    @DisplayName("헤더 없는 회전본(형식 판별 불가)은 거부하지 않고 그룹 형식을 물려받는다")
    void headerlessMemberInheritsGroupFormat() throws IOException {
        // 앞 200줄(sniff 창)이 PrintHeapAtGC 블록뿐이라 단독으로는 UNKNOWN 인 조각(회전 직후의 실제 모습)
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            noise.append("{Heap before GC invocations=").append(i).append(" (full 0):\n")
                 .append(" PSYoungGen      total 611840K, used 524800K [0x0000000780000000, 0x00000007a0000000)\n")
                 .append("  eden space 524800K, 100% used\n");
        }
        noise.append(young(T0 + 300_000, 303.0, 500000, 40000)).append('\n');

        GcLogResult r = GcLogTestSupport.engineMulti(List.of(
                GcLogTestSupport.src("gc.log.0", segment(T0, 3.0, 3, 10, true)),
                GcLogTestSupport.src("gc.log", noise.toString())));

        assertEquals("JDK8", r.getMeta().getFormat());
        assertEquals(4, r.getKpi().getEventCount(), "헤더 없는 조각의 이벤트를 놓쳤다");
        assertEquals(Boolean.TRUE, r.getSegments().get(1).getFormatInferred(),
                "형식을 물려받았다는 표시가 없다 — 화면이 근거를 설명할 수 없다");
        assertNull(r.getSegments().get(0).getFormatInferred(), "스스로 형식을 밝힌 조각에 물려받음 표시가 붙었다");
    }

    @Test
    @DisplayName("줄 번호는 전역이고 조각이 자기 줄 범위를 들고 있다 — 화면이 원문 줄을 되찾을 수 있다")
    void lineNumbersAreGlobalAndSegmentsCarryRanges() throws IOException {
        String a = segment(T0, 3.0, 3, 10, true);
        String b = segment(T0 + 100_000, 103.0, 3, 10, false);
        int aLines = a.split("\n", -1).length - 1;   // 끝 개행

        GcLogResult r = GcLogTestSupport.engineMulti(List.of(
                GcLogTestSupport.src("gc.log.0", a), GcLogTestSupport.src("gc.log", b)));

        List<GcLogResult.Segment> segs = r.getSegments();
        assertEquals(2, segs.size());
        assertEquals(1, segs.get(0).getFirstLine());
        assertEquals(aLines, segs.get(0).getLastLine(), "첫 조각의 줄 범위가 맞지 않는다");
        assertEquals(aLines + 1, segs.get(1).getFirstLine(), "두 번째 조각이 1번 줄부터 다시 세고 있다");
        for (GcLogResult.EventRow row : r.getEvents()) {
            if (row.getSeg() != null && row.getSeg() == 1) {
                assertTrue(row.getLine() >= segs.get(1).getFirstLine(), "조각 1 의 이벤트 줄 번호가 전역이 아니다");
            }
        }
        assertEquals(0, segs.get(0).getIndex());
        assertEquals("gc.log", segs.get(1).getFilename());
    }

    @Test
    @DisplayName("누적 바이트 상한을 넘으면 어느 파일에서 넘겼는지 밝히고 전량 실패한다")
    void cumulativeByteLimitNamesTheMember() {
        String a = segment(T0, 3.0, 20, 10, true);
        String b = segment(T0 + 300_000, 303.0, 20, 10, false);
        long half = a.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 10;

        GcLogEngine.GcLogException e = assertThrows(GcLogEngine.GcLogException.class, () ->
                GcLogTestSupport.engineMulti(List.of(GcLogTestSupport.src("gc.log.0", a), GcLogTestSupport.src("gc.log", b)),
                        new ParseLimits(half, 4096, 5_000_000), null));
        assertEquals("TOO_LARGE", e.code());
        assertTrue(e.getMessage().contains("gc.log"), "어느 파일에서 넘겼는지 밝히지 않는다: " + e.getMessage());
    }

    @Test
    @DisplayName("통합 로깅은 재기동으로 GC(n) 이 0 부터 다시 시작해도 이전 실행의 열린 id 와 섞이지 않는다")
    void unifiedGcIdCollisionAcrossRestartsIsIsolated() throws IOException {
        // 앞 조각: GC(0) 시작 줄만 남기고 끊김 / 뒤 조각: 새 실행의 GC(0) 완전한 한 세트
        String cut = GcLogTestSupport.u(GcLogTestSupport.TS, "1.000s", "gc,start", "GC(0) Pause Young (Normal) (G1 Evacuation Pause)");
        String fresh = GcLogTestSupport.g1Young(0, "2026-09-14T01:52:33.485+0900", 1.0, "Normal", "G1 Evacuation Pause",
                "14M->2M(258M)", "3.363", 5, 0, 1, 1, 0, 0);

        GcLogResult r = GcLogTestSupport.engineMulti(List.of(
                GcLogTestSupport.src("gc.log.0", cut + "\n"), GcLogTestSupport.src("gc.log", fresh + "\n")));

        assertEquals(1, r.getKpi().getEventCount(), "끊긴 GC(0) 과 새 실행의 GC(0) 이 한 이벤트로 합쳐졌다");
        assertEquals(1, r.getMeta().getDroppedIncomplete(), "끊긴 이벤트를 세지 않았다");
        assertEquals(3.363, r.getEvents().get(0).getPauseMs(), 0.001, "뒤 조각의 이벤트가 앞 조각 상태에 오염됐다");
    }

    @Test
    @DisplayName("빈 목록은 분석하지 않는다")
    void emptySourceListIsRejected() {
        GcLogEngine.GcLogException e = assertThrows(GcLogEngine.GcLogException.class,
                () -> GcLogTestSupport.engineMulti(List.of()));
        assertEquals("NOT_GC_LOG", e.code());
        assertFalse(e.getMessage().isBlank());
    }
}

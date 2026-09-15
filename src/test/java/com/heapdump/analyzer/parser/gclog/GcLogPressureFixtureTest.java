package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 합성 메모리 압박 픽스처(2026-09-16) — 실측 spdomain 로그(전부 명시적 호출, Low)의 반대편. Ergonomics Full 8회의 회수율이
 * 25%→8% 로 떨어지고 간격이 1500초→300초로 짧아지다가 마지막 Allocation Failure Full 직후 힙이 92% 남는다.
 * 파일은 {@link GcLogTestSupport#pressureParallelLog()} 가 만든다({@code -Dgclog.fixture.write=true} 로 재생성).
 */
class GcLogPressureFixtureTest {

    private static final Path FIXTURE = Path.of("src/test/resources/gclog/synthetic-jdk8-parallel-pressure.log");
    private static GcLogResult r;
    private static String fileText;

    @BeforeAll
    static void analyzeOnce() throws Exception {
        if (Boolean.getBoolean("gclog.fixture.write")) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, GcLogTestSupport.pressureParallelLog(), StandardCharsets.UTF_8);
        }
        try (InputStream in = GcLogPressureFixtureTest.class.getResourceAsStream("/gclog/synthetic-jdk8-parallel-pressure.log")) {
            assertNotNull(in, "테스트 리소스 누락 — mvn test -Dtest=GcLogPressureFixtureTest -Dgclog.fixture.write=true 로 생성");
            fileText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        r = GcLogTestSupport.engine(fileText);
    }

    private static Optional<GcLogResult.Finding> finding(String code) {
        return r.getFindings().stream().filter(f -> code.equals(f.getCode())).findFirst();
    }

    @Test
    @DisplayName("커밋된 픽스처는 생성기 출력과 같다(단일 출처)")
    void fixtureMatchesGenerator() {
        assertEquals(GcLogTestSupport.pressureParallelLog(), fileText);
    }

    @Test
    @DisplayName("분류: 압박 9 · Metaspace 2 · 명시적 2 — 압박 원인 Ergonomics 8 · Allocation Failure 1 내림차순")
    void classificationAndFindings() {
        assertEquals("Parallel", r.getMeta().getCollector());
        assertEquals(1073741824L, r.getMeta().getHeapMaxBytes());
        GcLogResult.FullGcSummary s = r.getFullGcSummary();
        assertNotNull(s);
        assertEquals(13, s.getTotal());
        assertEquals(13, r.getKpi().getFullCount());
        assertEquals(9, s.getByKind().get("HEAP_PRESSURE"));
        assertEquals(2, s.getByKind().get("METASPACE"));
        assertEquals(2, s.getByKind().get("EXPLICIT"));
        assertEquals(0, s.getByKind().get("GC_LOCKER"));
        assertEquals(0, s.getByKind().get("OTHER"));
        assertEquals(9, s.getHeapPressureCount());
        assertEquals(List.of("Ergonomics", "Allocation Failure"), List.copyOf(s.getPressureByCause().keySet()));
        assertEquals(8, s.getPressureByCause().get("Ergonomics"));
        assertEquals(2, r.getKpi().getSystemGcCount(), "Young+Full 짝 = 호출 1회");
        assertEquals(2, r.getKpi().getExplicitFullCount());

        GcLogResult.Finding low = finding("FULL_GC_LOW_RECLAIM").orElseThrow();
        assertEquals("Critical", low.getSeverity(), "마지막 직후 최대 힙 대비 92%");
        assertTrue(low.getDetail().contains("9회 중 6회"), low.getDetail());
        assertTrue(low.getAdvice().contains("Parallel 은 Full GC 만이 Old 를 회수"), low.getAdvice());
        GcLogResult.Finding shrink = finding("FULL_GC_INTERVAL_SHRINKING").orElseThrow();
        assertEquals("High", shrink.getSeverity(), "뒤 절반 중앙값 300초 ≤ 앞 절반 1500초 × 0.25");
        GcLogResult.Finding freq = finding("FULL_GC_FREQUENT").orElseThrow();
        assertTrue(freq.getDetail().contains("힙 압박 9회(원인: Ergonomics 8 · Allocation Failure 1) · Metaspace 2회 · GCLocker 0회."), freq.getDetail());
        assertEquals(9, freq.getEvidence().get("heapPressureCount"));
        assertEquals("Critical", r.getKpi().getSeverity());
    }

    @Test
    @DisplayName("배너 입력값: 회수율 표본 9(명시적 제외) · 20% 미만 6 · 마지막 직후 92% · 간격 단축 · 차트 점 9 · 최저 표본 5")
    void bannerInputsPresent() {
        GcLogResult.FullGcSummary s = r.getFullGcSummary();
        assertEquals(9, s.getReclaimSamples());
        assertEquals(6, s.getLowReclaimCount(), "17·14·12·10·8·3.6% — 20% 는 미만이 아니다");
        assertEquals(4.0, s.getReclaimPctMin(), 0.5);
        assertEquals(14.0, s.getReclaimPctMedian(), 1.0);
        assertTrue(s.getLastAfterCapRatio() >= 0.85 && s.getLastAfterCapRatio() < 0.95, String.valueOf(s.getLastAfterCapRatio()));
        assertEquals(964_000L << 10, s.getLastAfterBytes());
        assertEquals(6900.0, s.getLastAtSec(), 1e-6);
        assertEquals(Boolean.TRUE, s.getIntervalShrinking());
        assertEquals(8, s.getIntervalCount());
        assertEquals(1500.0, s.getIntervalMedianFirstHalfSec(), 1e-6);
        assertEquals(300.0, s.getIntervalMedianSecondHalfSec(), 1e-6);
        assertEquals(9, s.getPoints().size());
        assertEquals(5, s.getWorstReclaim().size());
        assertEquals(6900.0, s.getWorstReclaim().get(0).getUptimeSec(), 1e-6, "최저 회수율은 마지막 Allocation Failure Full");
        assertTrue(s.getWorstReclaim().get(0).getReclaimPct() < s.getWorstReclaim().get(4).getReclaimPct());
        assertEquals(1, s.getMaxConsecutivePressure(), "압박 Full 사이마다 Young(20초 간격)이 있다");
        long explicitRows = r.getEvents().stream().filter(e -> "EXPLICIT".equals(e.getFullKind())).count();
        long pressureRows = r.getEvents().stream().filter(e -> "HEAP_PRESSURE".equals(e.getFullKind())).count();
        assertEquals(2, explicitRows);
        assertEquals(9, pressureRows);
        assertTrue(r.getEvents().stream().filter(e -> !"FULL".equals(e.getType())).allMatch(e -> e.getFullKind() == null));
    }

    @Test
    @DisplayName("JSON 왕복 후에도 분류 블록이 그대로다")
    void entitySummaryMatches() {
        GcLogResult back = GcLogResultCodec.fromJson(GcLogResultCodec.toJson(r));
        assertNotNull(back.getFullGcSummary());
        assertEquals(r.getFullGcSummary().getHeapPressureCount(), back.getFullGcSummary().getHeapPressureCount());
        assertEquals(r.getFullGcSummary().getPressureByCause(), back.getFullGcSummary().getPressureByCause());
        assertEquals(r.getFullGcSummary().getWorstReclaim().size(), back.getFullGcSummary().getWorstReclaim().size());
    }
}

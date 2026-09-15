package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실측 로그 회귀 (2026-09-15) — 사내 JEUS 8 {@code spwt_wgmanager_1} 의 71시간 GC 로그
 * (JDK 1.8.0_422 · {@code -XX:+UseParallelGC -XX:+PrintHeapAtGC} · 힙 2GB, 원본 {@code gc_log_spdomain.txt} gzip).
 *
 * <p>정상 로그인데 종전 엔진은 종합 <b>High</b> 였다 — ① 1시간 주기 RMI System.gc() Full 72회를 "Old 영역이 반복해서 차고 있습니다"
 * ② 71시간 +2MB(0.03 MB/h)를 "누수 의심" ③ 호출 72회를 이벤트 144건으로 "System.gc() 144회". 이 파일이 다시 그렇게 나오면 실패한다.
 *
 * <p>사용자 질의(2026-09-12 11:39:34 Full GC 가 26.04MB→25.85MB 로 미미한가)의 근거도 고정한다 — 같은 호출의 직전 Young 수집이
 * 83.1MB→26.0MB 를 회수했고 Full 단계는 살아남은 객체를 Old 로 옮겨 압축했을 뿐이다.
 */
class GcLogRealLogRegressionTest {

    private static GcLogResult r;

    @BeforeAll
    static void analyzeOnce() throws Exception {
        try (InputStream in = GcLogRealLogRegressionTest.class.getResourceAsStream("/gclog/spdomain-jdk8-parallel-heapatgc.log.gz")) {
            assertNotNull(in, "테스트 리소스 누락");
            r = GcLogEngine.analyze(in, ParseLimits.DEFAULT, null, null);
        }
    }

    private static Optional<GcLogResult.Finding> finding(String code) {
        return r.getFindings().stream().filter(f -> code.equals(f.getCode())).findFirst();
    }

    @Test
    @DisplayName("형식·수집기·최대 힙(CommandLine flags)·이벤트 수")
    void metaAndCounts() {
        assertEquals("JDK8", r.getMeta().getFormat());
        assertEquals("Parallel", r.getMeta().getCollector());
        assertEquals("1.8.0_422-b05", r.getMeta().getJdkVersion());
        assertEquals(2147483648L, r.getMeta().getHeapMaxBytes(), "-XX:MaxHeapSize 에서 채운다");
        assertEquals(159, r.getKpi().getEventCount());
        assertEquals(72, r.getKpi().getFullCount());
        assertEquals(72, r.getKpi().getSystemGcCount(), "Young+Full 두 줄을 한 호출로 센다(종전 144)");
        assertEquals(72, r.getKpi().getExplicitFullCount());
        assertEquals(0.0, r.getKpi().getPressureFullGcPerHour(), 1e-9);
        assertEquals(3600.11, r.getKpi().getSystemGcIntervalSec(), 0.05);
    }

    @Test
    @DisplayName("정상 로그 — Full GC 빈도·누수 오탐 없음, System.gc() 는 Low + 주기·RMI·수집기 맞춤 권고, 종합 Low")
    void noFalseAlarms() {
        assertTrue(finding("FULL_GC_FREQUENT").isEmpty(), "명시적 Full 은 Old 부족 신호가 아니다");
        assertTrue(finding("LEAK_TREND").isEmpty(), "71시간 +2MB 는 누수가 아니다");
        GcLogResult.Finding sys = finding("SYSTEM_GC").orElseThrow();
        assertEquals("Low", sys.getSeverity());
        assertTrue(sys.getTitle().contains("72회") && sys.getTitle().contains("60분"), sys.getTitle());
        assertTrue(sys.getDetail().contains("RMI") && sys.getDetail().contains("144건") && sys.getDetail().contains("기동 3.8초"), sys.getDetail());
        assertTrue(sys.getAdvice().contains("ExplicitGCInvokesConcurrent 는 Parallel 수집기에서는 효과가 없습니다"), sys.getAdvice());
        assertTrue(sys.getAdvice().contains("sun.rmi.dgc.server.gcInterval"), sys.getAdvice());
        assertEquals("Low", r.getKpi().getSeverity(), "종전 High");

        GcLogResult.Trend t = r.getTrend();
        assertNotNull(t.getSlopeMbPerHour());
        assertEquals(Boolean.FALSE, t.getSignificant());
        assertTrue(t.getNote() != null && t.getNote().contains("누수 신호로 보지 않았습니다"), String.valueOf(t.getNote()));
    }

    @Test
    @DisplayName("2026-09-12 11:39:34 Full GC 행 — Full 단계 26663K→26467K, 같은 호출 전체는 85143K 부터")
    void startupFullRowCarriesWholeCall() {
        GcLogResult.EventRow full = r.getEvents().stream().filter(e -> e.getLine() == 105).findFirst().orElseThrow();
        assertEquals("FULL", full.getType());
        assertEquals(26663L << 10, full.getHeapBefore());
        assertEquals(26467L << 10, full.getHeapAfter());
        assertEquals(85143L << 10, full.getCallHeapBefore(), "직전 [GC (System.gc())] 의 heapBefore");
        GcLogResult.EventRow young = r.getEvents().stream().filter(e -> e.getLine() == 65).findFirst().orElseThrow();
        assertEquals("YOUNG", young.getType());
        assertEquals(null, young.getCallHeapBefore(), "Young 단계 행에는 싣지 않는다");
        assertFalse(r.getEvents().stream().anyMatch(e -> "YOUNG".equals(e.getType()) && e.getCallHeapBefore() != null));
    }
}

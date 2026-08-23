package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.GdbSharedLib;
import com.heapdump.analyzer.model.GdbStackFrame;
import com.heapdump.analyzer.model.GdbThreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코어덤프 PDF 리포트 모델 빌더({@link CoreDumpPdfReportService#buildCorePrintModel}) 단위 테스트 (2026-08-23).
 *
 * <p>인쇄 템플릿은 SpEL 을 최소화하고 파생 값을 전부 Java 에서 계산하는 설계라,
 * 콜 체인 필터·신뢰도 라벨·프로그램 basename·AI Map 매핑이 이 빌더의 계약이다.
 * PDF 렌더 코어(pdfCore)는 모델 빌드에 관여하지 않으므로 null 주입으로 격리한다.
 * (클래스가 public 인 이유: 픽스처를 CoreDumpPrintTemplateSmokeTest 가 패키지 밖에서 공유)
 */
public class CoreDumpPdfReportServiceTest {

    private CoreDumpAnalyzerService analyzer;
    private CoreDumpPdfReportService service;

    @BeforeEach
    void setUp() {
        analyzer = Mockito.mock(CoreDumpAnalyzerService.class);
        service = new CoreDumpPdfReportService(null, analyzer);
    }

    // ── 픽스처 (CoreDumpPrintTemplateSmokeTest 와 공유 — public 유지) ──

    public static GdbStackFrame frame(int num, String func, String location, String library, String quality) {
        GdbStackFrame f = new GdbStackFrame();
        f.setFrameNumber(num);
        f.setFunction(func);
        f.setLocation(location);
        f.setLibrary(library);
        f.setQuality(quality);
        f.setAddress("0x0000000000400" + num);
        return f;
    }

    public static CoreDumpAnalysisResult fullResult() {
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setFilename("core.12345");
        r.setExecutableName("/app/bin/myapp");
        r.setGdbVersion("GNU gdb (GDB) Red Hat Enterprise Linux 8.2-20.el8.0.1 with very long distro suffix");
        r.setCrashSignal("SIGSEGV");
        r.setSignalDescription("Segmentation fault");
        r.setCoreProgramName("/app/bin/myapp -c /etc/myapp.conf");

        // 12 프레임 = 비노이즈 10 + GARBAGE 2 → 콜 체인 8행, 생략 4개
        List<GdbStackFrame> bt = new ArrayList<>();
        bt.add(frame(0, "crash_here", "main.c:42", null, "RESOLVED"));
        bt.add(frame(1, null, null, null, "GARBAGE"));
        bt.add(frame(2, "lib_fn", null, "/lib64/libfoo.so", "RESOLVED"));
        bt.add(frame(3, "??", null, null, "UNSYMBOLIZED"));
        for (int i = 4; i <= 10; i++) bt.add(frame(i, "fn" + i, "file.c:" + i, null, "RESOLVED"));
        bt.add(frame(11, null, null, null, "GARBAGE"));
        r.setMainBacktrace(bt);
        r.setFirstResolvedFrame(bt.get(0));

        GdbThreadInfo t1 = new GdbThreadInfo(); t1.setId(1); t1.setCurrent(true);
        GdbThreadInfo t2 = new GdbThreadInfo(); t2.setId(2);
        r.setAllThreads(List.of(t1, t2));

        GdbSharedLib l1 = new GdbSharedLib(); l1.setSymsRead("Yes"); l1.setPath("/lib64/libc.so.6");
        GdbSharedLib l2 = new GdbSharedLib(); l2.setSymsRead("Yes (*)"); l2.setPath("/lib64/libm.so.6");
        GdbSharedLib l3 = new GdbSharedLib(); l3.setSymsRead("No"); l3.setPath("/lib64/libfoo.so");
        r.setSharedLibraries(List.of(l1, l2, l3));

        r.setAnalyzedAt("2026-08-22T14:03:11.123456");
        r.setAnalysisTimeMs(2500);
        r.setAnalysisConfidence("MEDIUM");
        r.setQualityWarnings(List.of("w1", "w2", "w3", "w4", "w5", "w6"));
        r.setResolvedFrameCount(9);
        r.setTotalFrameCount(12);
        r.setFaultingModule("libfoo.so");
        r.setFaultingModuleVendor("FooVendor");
        r.setFaultingModuleHasSymbols(false);
        r.setGuidanceKind("THIRDPARTY_STRIPPED");
        r.setSysrootUsed(true);
        r.setSysrootFileCount(37);
        return r;
    }

    @SuppressWarnings("unchecked")
    static <T> T get(Map<String, Object> m, String key) { return (T) m.get(key); }

    // ── 테스트 ───────────────────────────────────────────────────

    @Test
    @DisplayName("풀 픽스처 — 콜 체인 필터(GARBAGE 제외 상위 8)·생략/노이즈 카운트·KPI 파생값")
    void fullModel() {
        Map<String, Object> m = service.buildCorePrintModel("core.12345", null, fullResult());

        List<CoreDumpPdfReportService.ChainRow> rows = get(m, "chainRows");
        assertEquals(8, rows.size(), "GARBAGE 제외 상위 8행이어야 한다");
        assertEquals("#0", rows.get(0).getNum());
        assertEquals("CRASH", rows.get(0).getTag());
        assertEquals("crash", rows.get(0).getCls());
        assertEquals("main.c:42", rows.get(0).getPlace());
        // GARBAGE(#1) 는 건너뛰고 #2 가 두 번째 행 — 라이브러리 경로가 place
        assertEquals("#2", rows.get(1).getNum());
        assertEquals("/lib64/libfoo.so", rows.get(1).getPlace());
        assertEquals("심볼", rows.get(1).getTag());
        assertEquals("심볼없음", rows.get(2).getTag());

        assertEquals(2, (int) get(m, "garbageCount"));
        assertEquals(4, (int) get(m, "hiddenFrameCount"), "12 프레임 - 8행 = 4개 생략");

        assertEquals("보통", get(m, "confidenceLabel"));
        assertEquals("medium", get(m, "confidenceLevel"));
        assertEquals("9 / 12", get(m, "symbolStat"));
        assertEquals(12, (int) get(m, "totalFrames"));
        assertEquals(2, (int) get(m, "threadCount"));
        assertEquals(3, (int) get(m, "libCount"));
        assertEquals(2, (int) get(m, "libSymCount"), "\"Yes\" 접두 2건(Yes / Yes (*))만 집계");
        assertEquals("myapp", get(m, "programBasename"));
        assertEquals("main.c:42", get(m, "whereText"));
        assertNull(m.get("fixText"), "조치 칸 제거(2026-08-23) — 모델 키도 남기지 않는다");
        assertNotNull(get(m, "guidanceText"));
        assertEquals("2026-08-22 14:03:11", get(m, "analyzedAtText"));
        assertEquals("2.5", get(m, "analysisTimeSec"));

        List<String> warnings = get(m, "qualityWarnings");
        assertEquals(5, warnings.size(), "품질 경고는 5건으로 클립");
        assertEquals(1, (int) get(m, "warningOverflow"));

        String gdb = get(m, "gdbVersionShort");
        assertTrue(gdb.length() <= 48, "GDB 버전은 48자 이하로 클립: " + gdb);
    }

    @Test
    @DisplayName("AI 인사이트 Map(top-level, __core__: 키) → DTO 매핑 — List형 recommendations 는 ' / ' join")
    void aiMapping() {
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("severity", "Critical");
        insight.put("summary", "널 포인터 역참조");
        insight.put("rootCause", "미초기화 핸들 사용");
        insight.put("recommendations", List.of("핸들 초기화", "가드 추가"));
        insight.put("model", "claude-fable-5");
        insight.put("analysedAt", "2026-08-22T15:00:00");
        Mockito.when(analyzer.loadAiInsight(CoreDumpAnalyzerService.coreInsightKey("core.12345")))
                .thenReturn(insight);

        Map<String, Object> m = service.buildCorePrintModel("core.12345", null, fullResult());
        CoreDumpPdfReportService.CoreAiSummaryDto ai = get(m, "ai");
        assertNotNull(ai);
        assertEquals("Critical", ai.getSeverity());
        assertEquals("널 포인터 역참조", ai.getSummary());
        assertEquals("미초기화 핸들 사용", ai.getRootCause());
        assertEquals("핸들 초기화 / 가드 추가", ai.getRecommendations());
        assertEquals("claude-fable-5", ai.getModel());
    }

    @Test
    @DisplayName("AI 조회 예외는 리포트를 막지 않는다 — ai=null 로 소프트 페일")
    void aiFailureIsSoft() {
        Mockito.when(analyzer.loadAiInsight(Mockito.anyString()))
                .thenThrow(new RuntimeException("DB down"));
        Map<String, Object> m = service.buildCorePrintModel("core.12345", null, fullResult());
        assertNull(m.get("ai"));
        assertNotNull(m.get("chainRows"), "나머지 모델은 정상 빌드");
    }

    @Test
    @DisplayName("빈 결과(백트레이스/스레드/라이브러리 전부 null) — NPE 없이 기본값")
    void emptyResult() {
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setFilename("core.empty");
        Map<String, Object> m = service.buildCorePrintModel("core.empty", null, r);

        List<CoreDumpPdfReportService.ChainRow> rows = get(m, "chainRows");
        assertTrue(rows.isEmpty());
        assertNull(m.get("frame0"));
        assertEquals(0, (int) get(m, "totalFrames"));
        assertEquals("0 / 0", get(m, "symbolStat"));
        assertEquals(0, (int) get(m, "threadCount"));
        assertEquals(0, (int) get(m, "libCount"));
        assertNull(m.get("confidenceLabel"));
        assertNull(m.get("programBasename"));
        assertNull(m.get("whereText"));
        assertNull(m.get("guidanceText"), "guidanceKind 미지정이면 결함 안내 없음");
        assertNull(m.get("fixText"), "조치 칸 제거(2026-08-23) — 빈 결과에서도 키가 없다");
        assertEquals(0, (int) get(m, "warningOverflow"));
        assertNull(m.get("analyzedAtText"));
    }

    @Test
    @DisplayName("ChainRow place 폴백 — location > library > module+offset > address")
    void chainRowPlaceFallback() {
        GdbStackFrame modFrame = frame(5, "??", null, null, "UNSYMBOLIZED");
        modFrame.setModule("libclntsh.so.19.1");
        modFrame.setModuleOffset("0x28fb14f");
        CoreDumpAnalysisResult r = new CoreDumpAnalysisResult();
        r.setMainBacktrace(List.of(
                frame(0, "f0", "a.c:1", null, "RESOLVED"),
                modFrame,
                frame(6, "??", null, null, "UNSYMBOLIZED")));

        Map<String, Object> m = service.buildCorePrintModel("core.x", null, r);
        List<CoreDumpPdfReportService.ChainRow> rows = get(m, "chainRows");
        assertEquals("libclntsh.so.19.1 + 0x28fb14f", rows.get(1).getPlace(), "모듈+오프셋 폴백");
        assertEquals("0x00000000004006", rows.get(2).getPlace(), "주소 최종 폴백");
        assertEquals("??", rows.get(1).getFunc());
    }

    @Test
    @DisplayName("리비전 라벨은 모델에 그대로 실린다 (rev 리포트 헤더 배지)")
    void revLabelPassThrough() {
        Map<String, Object> m = service.buildCorePrintModel("core.12345", "20260801-120000", fullResult());
        assertEquals("20260801-120000", m.get("revLabel"));
    }
}

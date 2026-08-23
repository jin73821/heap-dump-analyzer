package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.GdbSharedLib;
import com.heapdump.analyzer.model.GdbStackFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 코어덤프 분석 결과를 A4 요약 리포트(PDF/HTML 미리보기)로 렌더링하는 서비스.
 *
 * <p>렌더 코어(폰트 임베딩·PdfRendererBuilder)는 {@link PdfReportService#renderPdf} 를 재사용하고,
 * 이 클래스는 코어덤프 도메인 모델 빌드만 담당한다. 힙덤프의 buildPrintModel 과 동일하게
 * {@code Map<String,Object>} 를 반환해 PDF(Context)와 HTML 미리보기(Model) 양쪽에서
 * 같은 템플릿({@code templates/core-dump/analyze-print.html})을 공유한다.
 *
 * <p>모든 파생 값(콜 체인 필터·신뢰도 라벨·프로그램 basename 등)은 여기서 계산한다 —
 * 인쇄 템플릿의 SpEL 을 최소화해 함정 11(SpEL long 리터럴)/23(인라인 {@code [[}) 리스크를 줄인다.
 */
@Service
public class CoreDumpPdfReportService {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpPdfReportService.class);

    /** 콜 체인 표 행 수 (GARBAGE 제외 상위 N — 화면 콜 체인 6개보다 여유). */
    private static final int CHAIN_LIMIT = 8;
    /** 품질 경고 표기 상한. */
    private static final int WARNING_LIMIT = 5;
    /** GDB 버전 문자열 표기 상한 (배포판 풀네임이 매우 길다). */
    private static final int GDB_VERSION_MAX_CHARS = 48;
    // 리포트가 여러 페이지로 흐르므로 실제 AI 텍스트가 잘리지 않도록 넉넉한 안전 상한만 유지 (힙과 동일).
    private static final int SUMMARY_MAX_CHARS = 4000;
    private static final int RECOMMEND_MAX_CHARS = 8000;

    private final PdfReportService pdfCore;
    private final CoreDumpAnalyzerService analyzerService;

    public CoreDumpPdfReportService(PdfReportService pdfCore,
                                    CoreDumpAnalyzerService analyzerService) {
        this.pdfCore = pdfCore;
        this.analyzerService = analyzerService;
    }

    /**
     * 코어덤프 분석 결과를 A4 요약 PDF 바이트로 렌더링.
     * @param revLabel 보존 리비전 조회 시 리비전 id (현재 결과면 null)
     */
    public byte[] renderCorePdf(String filename, String revLabel, CoreDumpAnalysisResult result) throws IOException {
        return pdfCore.renderPdf("core-dump/analyze-print", buildCorePrintModel(filename, revLabel, result));
    }

    /**
     * PDF 렌더링과 미리보기(HTML) 렌더링이 공유하는 Thymeleaf 모델 빌드.
     * 컨트롤러에서 model.addAttribute(...)로 펼치고, 서비스에서 Context 변수로 펼침.
     */
    public Map<String, Object> buildCorePrintModel(String filename, String revLabel, CoreDumpAnalysisResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filename", filename);
        m.put("result", result);
        m.put("revLabel", revLabel);
        m.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        m.put("analyzedAtText", formatAnalyzedAt(result.getAnalyzedAt()));
        m.put("analysisTimeSec", String.format("%.1f", result.getAnalysisTimeMs() / 1000.0));
        m.put("gdbVersionShort", PdfReportService.clip(result.getGdbVersion(), GDB_VERSION_MAX_CHARS));
        m.put("programBasename", programBasename(result.getCoreProgramName()));

        // ── 크래시 개요 + 콜 체인 (GARBAGE 제외 상위 CHAIN_LIMIT, 셀 텍스트 사전 계산) ──
        List<GdbStackFrame> bt = result.getMainBacktrace() != null ? result.getMainBacktrace() : List.of();
        GdbStackFrame frame0 = bt.isEmpty() ? null : bt.get(0);
        m.put("frame0", frame0);

        List<ChainRow> chainRows = new ArrayList<>();
        int garbageCount = 0;
        for (GdbStackFrame f : bt) {
            if ("GARBAGE".equals(f.getQuality())) { garbageCount++; continue; }
            if (chainRows.size() < CHAIN_LIMIT) chainRows.add(ChainRow.of(f));
        }
        m.put("chainRows", chainRows);
        m.put("garbageCount", garbageCount);
        m.put("hiddenFrameCount", Math.max(0, bt.size() - chainRows.size()));

        // ── KPI ──
        int totalFrames = result.getTotalFrameCount() > 0 ? result.getTotalFrameCount() : bt.size();
        m.put("totalFrames", totalFrames);
        m.put("symbolStat", result.getResolvedFrameCount() + " / " + totalFrames);
        m.put("threadCount", result.getAllThreads() != null ? result.getAllThreads().size() : 0);
        int libCount = 0, libSymCount = 0;
        if (result.getSharedLibraries() != null) {
            libCount = result.getSharedLibraries().size();
            for (GdbSharedLib lib : result.getSharedLibraries()) {
                if (lib.getSymsRead() != null && lib.getSymsRead().startsWith("Yes")) libSymCount++;
            }
        }
        m.put("libCount", libCount);
        m.put("libSymCount", libSymCount);

        String conf = result.getAnalysisConfidence();
        String confidenceLabel = null, confidenceLevel = null;
        if ("HIGH".equals(conf))        { confidenceLabel = "높음"; confidenceLevel = "high"; }
        else if ("MEDIUM".equals(conf)) { confidenceLabel = "보통"; confidenceLevel = "medium"; }
        else if ("LOW".equals(conf))    { confidenceLabel = "낮음"; confidenceLevel = "low"; }
        m.put("confidenceLabel", confidenceLabel);
        m.put("confidenceLevel", confidenceLevel);

        // ── 요약 2열 (원인 · 위치 — 화면 히어로 팩트 레일 미러) ──
        // 조치 칸은 2026-08-23 제거: 화면과 동일하게 결함 모듈 박스의 guidanceText 가 안내를 맡는다.
        String whereText = null;
        if (result.getFirstResolvedFrame() != null && result.getFirstResolvedFrame().getLocation() != null) {
            whereText = result.getFirstResolvedFrame().getLocation();
        } else if (frame0 != null && frame0.getLocation() != null) {
            whereText = frame0.getLocation();
        }
        m.put("whereText", whereText);
        m.put("guidanceText", guidanceText(result.getGuidanceKind()));

        // ── 품질 경고 (상한 초과분은 개수만 표기) ──
        List<String> warnings = PdfReportService.limit(result.getQualityWarnings(), WARNING_LIMIT);
        m.put("qualityWarnings", warnings);
        int totalWarnings = result.getQualityWarnings() != null ? result.getQualityWarnings().size() : 0;
        m.put("warningOverflow", Math.max(0, totalWarnings - warnings.size()));

        m.put("ai", loadAi(filename));
        return m;
    }

    /** 화면 hero-conf-guide 와 동일 취지의 상세 안내 (결함 모듈 박스용, 해당 없으면 null). */
    static String guidanceText(String guidanceKind) {
        if ("THIRDPARTY_STRIPPED".equals(guidanceKind)) {
            return "애플리케이션 실행 파일 페어링은 정상입니다. 결함은 서드파티 라이브러리 내부에서 발생했으며 "
                    + "해당 라이브러리에 심볼이 없어 콜스택 해석이 제한됩니다. "
                    + "해당 벤더의 debuginfo 확보 후 재분석하거나 벤더 측 이슈로 에스컬레이션하세요.";
        }
        if ("EXEC_MISSING".equals(guidanceKind)) {
            return "실행 파일 없이 코어 단독으로 분석되었습니다. 정확한 콜스택을 위해 "
                    + "코어 생성 시점의 동일 실행 파일을 페어링한 후 재분석하세요.";
        }
        if ("APP_STRIPPED".equals(guidanceKind)) {
            return "정확한 분석을 위해 코어 생성 시점의 stripped 되지 않은 동일 실행 파일을 페어링하거나 "
                    + "디버그 심볼(debuginfo)을 설치한 후 재분석하세요.";
        }
        return null;
    }

    /** "Core was generated by `/path/to/exec args...`" 원문에서 실행 파일 basename 추출 (화면 메타 dl 과 동일 로직). */
    static String programBasename(String coreProgramName) {
        if (coreProgramName == null || coreProgramName.isBlank()) return null;
        String execPath = coreProgramName.contains(" ")
                ? coreProgramName.substring(0, coreProgramName.indexOf(' '))
                : coreProgramName;
        int slash = execPath.lastIndexOf('/');
        return slash >= 0 ? execPath.substring(slash + 1) : execPath;
    }

    /** ISO-8601("2026-08-22T14:03:11.123") → "2026-08-22 14:03:11" (파싱 실패해도 원문 유지). */
    static String formatAnalyzedAt(String iso) {
        if (iso == null || iso.isBlank()) return null;
        String s = iso.replace('T', ' ');
        return s.length() > 19 ? s.substring(0, 19) : s;
    }

    /**
     * 코어 AI 인사이트({@code ai_insights} 합성 키 {@code __core__:filename}) 로드.
     * 힙의 AiInsightEntity JSON 과 달리 top-level Map(summary/rootCause/recommendations/severity/...)이라
     * PdfReportService.parseInsight 는 재사용 불가 — Map 기반으로 직접 매핑한다. 실패 시 null(리포트는 "미분석" 표기).
     */
    private CoreAiSummaryDto loadAi(String filename) {
        try {
            Map<String, Object> raw = analyzerService.loadAiInsight(CoreDumpAnalyzerService.coreInsightKey(filename));
            if (raw == null) return null;
            CoreAiSummaryDto d = new CoreAiSummaryDto();
            d.severity = str(raw.get("severity"));
            d.summary = PdfReportService.clip(str(raw.get("summary")), SUMMARY_MAX_CHARS);
            d.rootCause = PdfReportService.clip(str(raw.get("rootCause")), SUMMARY_MAX_CHARS);
            d.recommendations = PdfReportService.clip(joined(raw.get("recommendations")), RECOMMEND_MAX_CHARS);
            d.model = str(raw.get("model"));
            d.analysedAt = str(raw.get("analysedAt"));
            return d;
        } catch (Exception e) {
            logger.warn("[CoreDump-PDF] AI insight 조회 실패 (filename={}): {}", filename, e.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** recommendations 는 저장 형식에 따라 String(줄바꿈 번호 목록) 또는 List 일 수 있다. */
    private static String joined(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object x : list) if (x != null) items.add(String.valueOf(x));
            return items.isEmpty() ? null : String.join(" / ", items);
        }
        return String.valueOf(o);
    }

    /**
     * 인쇄 템플릿용 콜 체인 표 1행 — 셀 텍스트/태그를 전부 사전 계산.
     * Thymeleaf 에서 row.num / row.func / row.place / row.tag / row.cls 로 접근.
     */
    public static class ChainRow {
        private final String num;
        private final String func;
        private final String place;
        private final String tag;
        private final String cls;   // crash | src | sym | unsym — 인쇄 CSS 배지 클래스 접미사

        private ChainRow(String num, String func, String place, String tag, String cls) {
            this.num = num; this.func = func; this.place = place; this.tag = tag; this.cls = cls;
        }

        static ChainRow of(GdbStackFrame f) {
            String func = f.getFunction() != null ? f.getFunction() : "??";
            String place;
            if (f.getLocation() != null) place = f.getLocation();
            else if (f.getLibrary() != null) place = f.getLibrary();
            else if (f.getModule() != null) {
                place = f.getModule() + (f.getModuleOffset() != null ? " + " + f.getModuleOffset() : "");
            } else place = f.getAddress() != null ? f.getAddress() : "-";

            String tag, cls;
            if (f.getFrameNumber() == 0)            { tag = "CRASH";   cls = "crash"; }
            else if (f.getLocation() != null)        { tag = "소스";    cls = "src"; }
            else if ("UNSYMBOLIZED".equals(f.getQuality())) { tag = "심볼없음"; cls = "unsym"; }
            else                                     { tag = "심볼";    cls = "sym"; }
            return new ChainRow("#" + f.getFrameNumber(), func, place, tag, cls);
        }

        public String getNum()   { return num; }
        public String getFunc()  { return func; }
        public String getPlace() { return place; }
        public String getTag()   { return tag; }
        public String getCls()   { return cls; }
    }

    /**
     * 인쇄 템플릿용 코어 AI 인사이트 요약 DTO.
     * Thymeleaf 에서 ai.severity / ai.summary / ai.rootCause / ai.recommendations / ai.model 로 접근.
     */
    public static class CoreAiSummaryDto {
        private String severity;
        private String summary;
        private String rootCause;
        private String recommendations;
        private String model;
        private String analysedAt;

        public String getSeverity() { return severity; }
        public String getSummary() { return summary; }
        public String getRootCause() { return rootCause; }
        public String getRecommendations() { return recommendations; }
        public String getModel() { return model; }
        public String getAnalysedAt() { return analysedAt; }
    }
}

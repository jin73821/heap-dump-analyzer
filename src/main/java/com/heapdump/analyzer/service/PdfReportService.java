package com.heapdump.analyzer.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.MemoryObject;
import com.heapdump.analyzer.util.OomDetector;
import com.heapdump.analyzer.util.MiddlewareDetector;
import com.heapdump.analyzer.model.entity.AiInsightEntity;
import com.heapdump.analyzer.repository.AiInsightRepository;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 분석 결과를 A4 1페이지 PDF로 렌더링하는 서비스.
 * Thymeleaf 인쇄 전용 템플릿(analyze-print.html)을 OpenHTMLtoPDF로 변환한다.
 */
@Service
public class PdfReportService {

    private static final Logger logger = LoggerFactory.getLogger(PdfReportService.class);

    private static final int TOP_MEMORY_LIMIT = 5;
    private static final int SUSPECT_LIMIT = 3;
    // 리포트가 여러 페이지로 흐르므로 실제 AI 텍스트가 잘리지 않도록 넉넉한 안전 상한만 유지.
    private static final int SUMMARY_MAX_CHARS = 4000;
    private static final int RECOMMEND_MAX_CHARS = 8000;

    private final TemplateEngine templateEngine;
    private final AiInsightRepository aiInsightRepository;
    private final HeapDumpAnalyzerService analyzerService;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    /** JVM 힙 설정 한 줄(환경 스트립). 필드 주입 — 테스트가 3-인자 생성자를 직접 부르므로 null 가드. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private JvmHeapInfoService jvmHeapInfoService;

    public PdfReportService(TemplateEngine templateEngine,
                            AiInsightRepository aiInsightRepository,
                            HeapDumpAnalyzerService analyzerService) {
        this.templateEngine = templateEngine;
        this.aiInsightRepository = aiInsightRepository;
        this.analyzerService = analyzerService;
    }

    /**
     * 분석 결과를 A4 세로 1페이지 PDF 바이트로 렌더링.
     */
    public byte[] renderPrintPdf(String filename, HeapAnalysisResult result) throws IOException {
        return renderPdf("analyze-print", buildPrintModel(filename, result));
    }

    /**
     * 임의 인쇄 템플릿 + 모델 → A4 PDF 바이트 (Pretendard 임베딩 포함 공용 렌더 코어).
     * 힙덤프(analyze-print)와 코어덤프(core-dump/analyze-print) 리포트가 공유한다.
     * 템플릿 제약: OpenHTMLtoPDF 는 flex/grid/인라인 SVG 미지원 — display:table 기반으로 작성할 것.
     */
    public byte[] renderPdf(String templateName, Map<String, Object> model) throws IOException {
        Context ctx = new Context();
        model.forEach(ctx::setVariable);
        String html = templateEngine.process(templateName, ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            registerFonts(b);
            b.withHtmlContent(html, null);
            b.toStream(os);
            b.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IOException("PDF 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * PDF 렌더링과 미리보기(HTML) 렌더링이 공유하는 Thymeleaf 모델 빌드.
     * 컨트롤러에서 model.addAttribute(...)로 펼치고, 서비스에서 Context 변수로 펼침.
     */
    public Map<String, Object> buildPrintModel(String filename, HeapAnalysisResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filename", filename);
        m.put("result", result);
        m.put("topMem", limit(result.getTopMemoryObjects(), TOP_MEMORY_LIMIT));
        m.put("topSuspects", limit(result.getLeakSuspects(), SUSPECT_LIMIT));
        m.put("ai", loadAiInsight(filename));

        // 환경 정보 (HOST / Middleware / Instance / Domain) — analyze 뷰(HeapDumpViewController)와 동일 로직.
        // HOST: server_name(SSH 자동/수동 편집), Middleware: 히스토그램·sysprop 기반 벤더 추정,
        // Instance/Domain: sysProps(jeus.server.name/jeus.domain.name) 자동 식별 + 수동 편집값 우선.
        m.put("hostname", analyzerService.getAnalysisServerName(filename));
        MiddlewareDetector.Result mw = MiddlewareDetector.detect(
                result.getHistogramEntries(), result.getThreadInfos(), result.getSystemProperties());
        m.put("middlewareVendor", mw.detected() ? mw.displayName() : "");
        Map<String, String> sysProps = result.getSystemProperties();
        String jeusInstanceAuto = sysProps != null && sysProps.get("jeus.server.name") != null
                ? sysProps.get("jeus.server.name").trim() : "";
        String jeusDomainAuto = sysProps != null && sysProps.get("jeus.domain.name") != null
                ? sysProps.get("jeus.domain.name").trim() : "";
        String jeusInstanceManual = analyzerService.getAnalysisJeusInstance(filename);
        String jeusDomainManual = analyzerService.getAnalysisJeusDomain(filename);
        m.put("jeusInstance", !jeusInstanceManual.isEmpty() ? jeusInstanceManual : jeusInstanceAuto);
        m.put("jeusDomain", !jeusDomainManual.isEmpty() ? jeusDomainManual : jeusDomainAuto);
        // JVM Heap: 원격 전송 시 수집한 -Xms/-Xmx (출처·신뢰도 라벨 포함) — 문자열로 완성해 넘긴다(템플릿 계산 금지)
        m.put("jvmHeapLabel", jvmHeapInfoService != null ? jvmHeapInfoService.label(filename) : "미지정");

        long usedPct = result.getTotalHeapSize() > 0
                ? Math.round(100.0 * result.getUsedHeapSize() / result.getTotalHeapSize())
                : 0;
        m.put("usedBarPct", Math.max(0, Math.min(100, usedPct)));
        m.put("freeBarPct", Math.max(0, Math.min(100, 100 - usedPct)));

        m.put("analysisTimeSec", String.format("%.1f", result.getAnalysisTime() / 1000.0));
        m.put("formattedDate",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(result.getLastModified())));
        m.put("generatedAt",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        m.put("suspectCount",
                result.hasLeakSuspects() ? result.getLeakSuspects().size() : 0);

        // OOM 진단 데이터
        int oomCount = result.getOomThreadCount();
        m.put("oomThreadCount", oomCount);
        if (oomCount > 0) {
            String firstType = result.getOomFirstType();
            m.put("oomFirstType", firstType);
            OomDetector.OomKind kind = OomDetector.classifyMessage(firstType);
            m.put("oomKindLabel", kind.koLabel());
            m.put("oomCause", clip(kind.cause(), 280));
            m.put("oomRecommendation", clip(kind.recommendation(), 280));
            m.put("oomThreadSamples", result.getOomThreadNames(3));
        }

        return m;
    }

    private void registerFonts(PdfRendererBuilder b) {
        b.useFont(() -> openFont("fonts/Pretendard-Regular.ttf"),
                "Pretendard", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        b.useFont(() -> openFont("fonts/Pretendard-Bold.ttf"),
                "Pretendard", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
    }

    private static java.io.InputStream openFont(String classpath) {
        try {
            return new ClassPathResource(classpath).getInputStream();
        } catch (IOException e) {
            throw new RuntimeException("폰트 로드 실패: " + classpath, e);
        }
    }

    private AiSummaryDto loadAiInsight(String filename) {
        try {
            Optional<AiInsightEntity> opt = aiInsightRepository.findByFilename(filename);
            if (!opt.isPresent()) return null;
            return parseInsight(opt.get());
        } catch (Exception e) {
            logger.warn("[PDF] AI insight 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private AiSummaryDto parseInsight(AiInsightEntity e) {
        AiSummaryDto d = new AiSummaryDto();
        d.severity = e.getSeverity();
        d.model = e.getModel();
        d.analysedAt = e.getAnalysedAt() != null ? e.getAnalysedAt().toString() : null;
        try {
            JsonNode n = jsonMapper.readTree(e.getInsightData());
            if (d.severity == null) d.severity = textOf(n, "severity");
            d.summary = clip(textOf(n, "summary"), SUMMARY_MAX_CHARS);
            JsonNode rec = n.get("recommendations");
            if (rec != null && rec.isArray() && rec.size() > 0) {
                List<String> items = new ArrayList<>();
                rec.forEach(x -> items.add(x.asText()));
                d.recommendations = clip(String.join(" / ", items), RECOMMEND_MAX_CHARS);
            } else if (rec != null && rec.isTextual()) {
                d.recommendations = clip(rec.asText(), RECOMMEND_MAX_CHARS);
            }
            if (d.summary == null || d.summary.isEmpty()) {
                String rc = textOf(n, "rootCause");
                if (rc != null) d.summary = clip(rc, SUMMARY_MAX_CHARS);
            }
        } catch (Exception ex) {
            logger.warn("[PDF] AI insightData JSON 파싱 실패 (filename={}): {}", e.getFilename(), ex.getMessage());
            d.parseError = true;
        }
        return d;
    }

    private static String textOf(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    /** package-private — CoreDumpPdfReportService 등 인쇄 모델 빌더가 공유. */
    static String clip(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** package-private — CoreDumpPdfReportService 등 인쇄 모델 빌더가 공유. */
    static <T> List<T> limit(List<T> src, int max) {
        if (src == null || src.isEmpty()) return new ArrayList<>();
        if (src.size() <= max) return src;
        return new ArrayList<>(src.subList(0, max));
    }

    /**
     * 인쇄 템플릿용 AI 인사이트 요약 DTO.
     * Thymeleaf에서 ai.severity / ai.summary / ai.recommendations / ai.model 로 접근.
     */
    public static class AiSummaryDto {
        private String severity;
        private String summary;
        private String recommendations;
        private String model;
        private String analysedAt;
        private boolean parseError;

        public String getSeverity() { return severity; }
        public String getSummary() { return summary; }
        public String getRecommendations() { return recommendations; }
        public String getModel() { return model; }
        public String getAnalysedAt() { return analysedAt; }
        public boolean isParseError() { return parseError; }
    }
}

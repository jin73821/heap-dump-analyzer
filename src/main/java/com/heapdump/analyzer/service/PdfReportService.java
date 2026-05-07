package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.MemoryObject;
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
    private static final int SUMMARY_MAX_CHARS = 220;
    private static final int RECOMMEND_MAX_CHARS = 560;

    private final TemplateEngine templateEngine;
    private final AiInsightRepository aiInsightRepository;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public PdfReportService(TemplateEngine templateEngine,
                            AiInsightRepository aiInsightRepository) {
        this.templateEngine = templateEngine;
        this.aiInsightRepository = aiInsightRepository;
    }

    /**
     * 분석 결과를 A4 세로 1페이지 PDF 바이트로 렌더링.
     */
    public byte[] renderPrintPdf(String filename, HeapAnalysisResult result) throws IOException {
        Context ctx = new Context();
        buildPrintModel(filename, result).forEach(ctx::setVariable);
        String html = templateEngine.process("analyze-print", ctx);

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

    private static String clip(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static <T> List<T> limit(List<T> src, int max) {
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

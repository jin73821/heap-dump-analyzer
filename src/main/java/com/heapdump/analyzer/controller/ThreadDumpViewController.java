package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.service.ThreadDumpAnalyzerService;
import com.heapdump.analyzer.service.ThreadDumpPdfReportService;
import com.heapdump.analyzer.util.AuthUtil;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 스레드 덤프 화면 (2026-11-26) — 목록·업로드({@code /thread-dump}), 결과({@code /thread-dump/analyze/{fn}}),
 * 리포트 HTML 미리보기({@code …/print-html}). 결과 본문은 화면이 {@code /api/thread-dump/{fn}/result} 로 받는다.
 */
@Controller
@RequestMapping("/thread-dump")
public class ThreadDumpViewController {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDumpViewController.class);

    /*
     * 리다이렉트는 상대 경로로 보낸다(2026-11-27, CLAUDE.md 함정 92) — 내부망 프록시는 /dump 접두사를 떼고 넘겨
     * 서버가 접두사를 모르므로, 루트 기준(/thread-dump…)으로 보내면 프록시 경유 시 접두사가 빠진다.
     * 상대 경로는 브라우저가 지금 주소(/dump/thread-dump/… 포함)를 기준으로 푼다. Tomcat 기본(useRelativeRedirects)은 그대로 내보낸다.
     * 기준 경로: /thread-dump/heap/{fn} · /thread-dump/{heap|analyze}/{fn}/print-html — 파일명에 '/' 는 없다(validateFilename).
     */
    /** /thread-dump/heap/{fn} → /thread-dump */
    static final String REDIRECT_LIST_FROM_ITEM = "redirect:../../thread-dump";
    /** /thread-dump/heap/{fn}/print-html → /thread-dump */
    static final String REDIRECT_LIST_FROM_PRINT = "redirect:../../../thread-dump";

    private final ThreadDumpAnalyzerService service;
    private final ThreadDumpPdfReportService pdfReportService;

    /** 힙 덤프의 스레드 분석(2.7.1) — 선택 주입. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.heapdump.analyzer.service.HeapThreadService heapThreads;

    public ThreadDumpViewController(ThreadDumpAnalyzerService service, ThreadDumpPdfReportService pdfReportService) {
        this.service = service;
        this.pdfReportService = pdfReportService;
    }

    @GetMapping
    public String index(Model model, Authentication auth) {
        // 디스크와 기록을 맞춘 뒤 기록 전체를 그린다 — 파일이 지워진 기록(결과만 남은 것)도 목록에 보여야 한다
        List<ThreadDumpAnalysisEntity> files;
        try {
            files = service.syncWithDisk();
        } catch (RuntimeException e) {
            logger.warn("[ThreadDump] action=list-sync-failed — {}", e.toString());
            files = List.of();
        }
        model.addAttribute("files", files);
        model.addAttribute("history", service.history());
        model.addAttribute("isAdmin", AuthUtil.isAdmin(auth));
        model.addAttribute("maxBytes", service.maxFileBytes());
        return "thread-dump/index";
    }

    @GetMapping("/analyze/{filename:.+}")
    public String analyze(@PathVariable String filename, Model model) {
        String safe = service.validateFilename(filename);
        Optional<ThreadDumpAnalysisEntity> entity = service.find(safe);
        boolean fileExists = service.fileOf(safe).isFile();
        String status = entity.map(ThreadDumpAnalysisEntity::getStatus)
                .orElse(fileExists ? ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED : "MISSING");
        if (service.isAnalyzing(safe)) status = ThreadDumpAnalysisEntity.STATUS_ANALYZING;
        model.addAttribute("filename", safe);
        model.addAttribute("entity", entity.orElse(null));
        model.addAttribute("fileExists", fileExists);
        model.addAttribute("status", status);
        model.addAttribute("llmEnabled", service.isLlmEnabled());
        model.addAttribute("heapMode", false);
        model.addAttribute("heapEntity", null);
        return "thread-dump/analyze";
    }

    /**
     * 힙 덤프의 스레드 정보로 만든 분석(2.7.1) — 스레드 덤프 결과 화면을 '힙 덤프에서 추출' 모드로 쓴다(분석·삭제 없음).
     * 결과는 화면이 {@code /api/thread-dump/heap/{fn}/result} 로 받고, 만들 수 없으면 그 응답의 코드로 안내한다.
     */
    @GetMapping("/heap/{filename:.+}")
    public String heapAnalyze(@PathVariable String filename, Model model) {
        if (heapThreads == null) return REDIRECT_LIST_FROM_ITEM;
        String safe = heapThreads.validateFilename(filename);
        var he = heapThreads.entity(safe).orElse(null);
        model.addAttribute("filename", safe);
        model.addAttribute("entity", null);
        model.addAttribute("fileExists", false);
        model.addAttribute("status", "SUCCESS");
        model.addAttribute("llmEnabled", heapThreads.isLlmEnabled());
        model.addAttribute("heapMode", true);
        model.addAttribute("heapEntity", he);
        model.addAttribute("heapUsedText", he == null || he.getUsedHeapSize() == null ? null
                : com.heapdump.analyzer.util.FormatUtils.formatBytes(he.getUsedHeapSize())
                  + (he.getTotalHeapSize() == null ? "" : " / " + com.heapdump.analyzer.util.FormatUtils.formatBytes(he.getTotalHeapSize())));
        return "thread-dump/analyze";
    }

    @GetMapping("/heap/{filename:.+}/print-html")
    public String heapPrintHtml(@PathVariable String filename, Model model) {
        if (heapThreads == null) return REDIRECT_LIST_FROM_PRINT;
        String safe = heapThreads.validateFilename(filename);
        com.heapdump.analyzer.service.HeapThreadService.Loaded l;
        try {
            l = heapThreads.load(safe);
        } catch (com.heapdump.analyzer.parser.threaddump.ThreadDumpException e) {
            logger.warn("[HeapThreads-PDF] action=print-html result=FAIL file={} code={} — {}", safe, e.code(), e.getMessage());
            return "redirect:../" + HttpHeaderValues.pathSegment(safe);   // …/heap/{fn}/print-html → …/heap/{fn}
        }
        Map<String, Object> ai = null;
        try {
            ai = heapThreads.loadAiInsight(com.heapdump.analyzer.service.HeapThreadService.insightKey(safe));
        } catch (RuntimeException e) {
            logger.warn("[HeapThreads-PDF] AI 해석 조회 실패 file={} — {}", safe, e.toString());
        }
        pdfReportService.buildPrintModel(safe, null, l.result(), ai, null).forEach(model::addAttribute);
        return "thread-dump/analyze-print";
    }

    /** PDF 리포트의 HTML 미리보기 — print-pdf 와 같은 모델·같은 템플릿. 결과가 없으면 결과 화면으로 돌려보낸다. */
    @GetMapping("/analyze/{filename:.+}/print-html")
    public String printHtml(@PathVariable String filename, Model model) {
        String safe = service.validateFilename(filename);
        ThreadDumpResult result = service.loadResult(safe).orElse(null);
        if (result == null) return "redirect:../" + HttpHeaderValues.pathSegment(safe);   // …/analyze/{fn}/print-html → …/analyze/{fn}
        Map<String, Object> ai = null;
        try {
            ai = service.loadAiInsight(ThreadDumpAnalyzerService.insightKey(safe));
        } catch (RuntimeException e) {
            logger.warn("[ThreadDump-PDF] AI 해석 조회 실패 file={} — {}", safe, e.toString());
        }
        Map<String, Object> link = null;
        try {
            link = service.heapLinkView(safe);
        } catch (RuntimeException e) {
            logger.warn("[ThreadDump-PDF] 힙 덤프 연결 조회 실패 file={} — {}", safe, e.toString());
        }
        pdfReportService.buildPrintModel(safe, service.find(safe).orElse(null), result, ai, link).forEach(model::addAttribute);
        return "thread-dump/analyze-print";
    }
}

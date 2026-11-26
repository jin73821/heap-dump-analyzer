package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.dto.CoreDumpRevision;
import com.heapdump.analyzer.model.dto.CoreFileRow;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.CoreDumpPdfReportService;
import com.heapdump.analyzer.service.CoreFileListBuilder;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/core-dump")
public class CoreDumpViewController {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpViewController.class);

    private final CoreDumpAnalyzerService analyzerService;
    private final CoreDumpPdfReportService pdfReportService;

    public CoreDumpViewController(CoreDumpAnalyzerService analyzerService,
                                  CoreDumpPdfReportService pdfReportService) {
        this.analyzerService = analyzerService;
        this.pdfReportService = pdfReportService;
    }

    /**
     * 코어 파일 목록 — 서버 파일과 분석 이력을 한 표로 합친 행(2026-11-26 개편).
     * 실행 파일 크기는 dumpfiles/ 에서 직접 잰다(연결된 실행 파일은 목록에서 빠져 크기를 따로 알 수 없다).
     */
    @GetMapping
    public String indexPage(Model model) {
        List<CoreDumpAnalysisEntity> history = analyzerService.getHistory();
        java.io.File dir = analyzerService.dumpFilesDir();
        List<CoreFileRow> rows = CoreFileListBuilder.build(analyzerService.listExistingDumpFiles(), history,
                name -> {
                    java.io.File f = new java.io.File(dir, name);
                    return f.isFile() ? f.length() : null;
                });
        model.addAttribute("coreFiles", rows);
        model.addAttribute("coreRepeatGroups", CoreFileListBuilder.repeatGroups(rows));
        return "core-dump/index";
    }

    @GetMapping("/progress/{filename:.+}")
    public String progressPage(@PathVariable String filename, Model model) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
        // 방어 로직: 이미 정상 분석 결과가 있으면 분석중 페이지를 렌더하지 않고 결과 페이지로 리다이렉트.
        // (결과 페이지에서 뒤로가기 → 분석중 페이지 재노출 + GDB 전체 재분석 재실행 방지.
        //  재분석은 reanalyze 가 result.json 을 삭제하므로 이 조건에 걸리지 않아 정상 동작.)
        Optional<CoreDumpAnalysisResult> done = analyzerService.loadResult(safe);
        if (done.isPresent()
                && (done.get().getErrorMessage() == null || done.get().getErrorMessage().isEmpty())) {
            return "redirect:/core-dump/analyze/" + HttpHeaderValues.pathSegment(safe);
        }
        model.addAttribute("filename", safe);
        return "core-dump/progress";
    }

    /**
     * 결과 페이지. rev 파라미터가 있으면 보존된 과거 리비전을 읽기 전용으로 렌더한다.
     * (현재 결과는 rev 없음 — 재분석은 항상 현재 결과에 대해서만 수행)
     */
    @GetMapping("/analyze/{filename:.+}")
    public String analyzePage(@PathVariable String filename,
                              @RequestParam(value = "rev", required = false) String rev,
                              Model model) {
        String safe = analyzerService.validateCoreDumpFilename(filename);

        List<CoreDumpRevision> revisions = analyzerService.listRevisions(safe);
        Optional<CoreDumpAnalysisEntity> entityOpt = analyzerService.getEntity(safe);
        boolean viewingRevision = rev != null && !rev.isBlank();

        Optional<CoreDumpAnalysisResult> resultOpt;
        if (viewingRevision) {
            try {
                resultOpt = analyzerService.loadRevisionResult(safe, rev);
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", e.getMessage());
                model.addAttribute("filename", safe);
                return "core-dump/analyze";
            }
            if (resultOpt.isEmpty()) {
                model.addAttribute("error", "보존된 분석 리비전을 찾을 수 없습니다: " + rev);
                model.addAttribute("filename", safe);
                return "core-dump/analyze";
            }
        } else {
            resultOpt = analyzerService.loadResult(safe);
            if (resultOpt.isEmpty() && entityOpt.isEmpty()) {
                model.addAttribute("error", "분석 결과를 찾을 수 없습니다: " + safe);
                model.addAttribute("filename", safe);
                return "core-dump/analyze";
            }
        }

        model.addAttribute("result", resultOpt.orElse(null));
        model.addAttribute("entity", entityOpt.orElse(null));
        model.addAttribute("filename", safe);
        model.addAttribute("revisions", revisions);
        model.addAttribute("currentRevision", viewingRevision ? rev : null);
        return "core-dump/analyze";
    }

    /**
     * PDF 리포트의 HTML 미리보기 — print-pdf 와 동일한 모델로 같은 인쇄 템플릿을 렌더한다
     * (힙덤프 /analyze/{fn}/print-html 과 1:1 대칭). 리포트 불가 상태면 결과 페이지로 리다이렉트.
     */
    @GetMapping("/analyze/{filename:.+}/print-html")
    public String printHtml(@PathVariable String filename,
                            @RequestParam(value = "rev", required = false) String rev,
                            Model model) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
        boolean viewingRevision = rev != null && !rev.isBlank();
        Optional<CoreDumpAnalysisResult> resultOpt;
        try {
            resultOpt = viewingRevision
                    ? analyzerService.loadRevisionResult(safe, rev)
                    : analyzerService.loadResult(safe);
        } catch (IllegalArgumentException e) {
            return "redirect:/core-dump/analyze/" + HttpHeaderValues.pathSegment(safe);
        }
        CoreDumpAnalysisResult result = resultOpt.orElse(null);
        if (result == null || !CoreDumpApiController.isReportable(result)) {
            return "redirect:/core-dump/analyze/" + HttpHeaderValues.pathSegment(safe);
        }
        pdfReportService.buildCorePrintModel(safe, viewingRevision ? rev : null, result)
                .forEach(model::addAttribute);
        return "core-dump/analyze-print";
    }
}

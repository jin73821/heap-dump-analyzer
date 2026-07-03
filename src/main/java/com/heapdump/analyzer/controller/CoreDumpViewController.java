package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.CoreDumpAnalysisResult;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/core-dump")
public class CoreDumpViewController {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpViewController.class);

    private final CoreDumpAnalyzerService analyzerService;

    public CoreDumpViewController(CoreDumpAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
    }

    @GetMapping
    public String indexPage(Model model) {
        List<CoreDumpAnalysisEntity> history = analyzerService.getHistory();
        model.addAttribute("history", history);
        model.addAttribute("coreDumpFiles", analyzerService.listExistingDumpFiles());
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
            return "redirect:/core-dump/analyze/" + safe;
        }
        model.addAttribute("filename", safe);
        return "core-dump/progress";
    }

    @GetMapping("/analyze/{filename:.+}")
    public String analyzePage(@PathVariable String filename, Model model) {
        String safe = analyzerService.validateCoreDumpFilename(filename);

        Optional<CoreDumpAnalysisResult> resultOpt = analyzerService.loadResult(safe);
        Optional<CoreDumpAnalysisEntity> entityOpt = analyzerService.getEntity(safe);

        if (resultOpt.isEmpty() && entityOpt.isEmpty()) {
            model.addAttribute("error", "분석 결과를 찾을 수 없습니다: " + safe);
            model.addAttribute("filename", safe);
            return "core-dump/analyze";
        }

        model.addAttribute("result", resultOpt.orElse(null));
        model.addAttribute("entity", entityOpt.orElse(null));
        model.addAttribute("filename", safe);
        return "core-dump/analyze";
    }
}

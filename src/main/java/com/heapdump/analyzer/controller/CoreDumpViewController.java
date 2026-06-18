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
        return "core-dump/index";
    }

    @GetMapping("/progress/{filename:.+}")
    public String progressPage(@PathVariable String filename, Model model) {
        String safe = analyzerService.validateCoreDumpFilename(filename);
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

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.GcLogResult;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.parser.gclog.GcLogResultCodec;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.GcLogInstanceService;
import com.heapdump.analyzer.service.GcLogMatchService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Optional;

/**
 * GC 로그 페이지 (2026-09-14) — 목록/업로드/이력({@code /gc-log}) + 결과({@code /gc-log/analyze/{fn}}).
 * 결과 페이지는 SUCCESS 가 아니면 상태만 넘기고 JS 가 분석을 시작·폴링한다(진행 화면 별도 없음).
 */
@Controller
@RequestMapping("/gc-log")
public class GcLogViewController {

    private final GcLogAnalyzerService service;
    private final GcLogMatchService matchService;
    private final GcLogInstanceService instanceService;

    public GcLogViewController(GcLogAnalyzerService service, GcLogMatchService matchService, GcLogInstanceService instanceService) {
        this.service = service;
        this.matchService = matchService;
        this.instanceService = instanceService;
    }

    @GetMapping
    public String index(Model model, Authentication auth) {
        List<GcLogAnalysisEntity> files = service.listExistingFiles();
        model.addAttribute("gcLogFiles", files);
        model.addAttribute("history", service.history());
        model.addAttribute("isAdmin", com.heapdump.analyzer.util.AuthUtil.isAdmin(auth));
        return "gc-log/index";
    }

    @GetMapping("/analyze/{filename:.+}")
    public String analyze(@PathVariable String filename, Model model) {
        String safe = service.validateGcLogFilename(filename);
        Optional<GcLogAnalysisEntity> entity = service.find(safe);
        boolean fileExists = service.fileOf(safe).isFile();
        model.addAttribute("filename", safe);
        model.addAttribute("entity", entity.orElse(null));
        model.addAttribute("fileExists", fileExists);
        String status = entity.map(GcLogAnalysisEntity::getStatus).orElse(fileExists ? GcLogAnalysisEntity.STATUS_NOT_ANALYZED : "MISSING");
        if (service.isAnalyzing(safe)) status = GcLogAnalysisEntity.STATUS_ANALYZING;
        model.addAttribute("status", status);
        GcLogResult result = null;
        if (GcLogAnalysisEntity.STATUS_SUCCESS.equals(status)) result = service.loadResult(safe).orElse(null);
        model.addAttribute("result", result);
        // 결과 JSON 은 <script type="application/json"> 에 th:utext 로 넣는다(함정 23 회피). '</' 는 반드시 이스케이프.
        model.addAttribute("resultJson", result == null ? "null" : GcLogResultCodec.toJson(result).replace("</", "<\\/"));
        model.addAttribute("candidates", entity.map(matchService::candidatesOf).orElse(List.of()));
        // KPI '인스턴스' 카드 초기값 — 수동 입력 > 연결된 힙 덤프의 Instance. 결과가 있을 때만 카드가 그려지므로 그때만 덤프를 조회한다
        model.addAttribute("instance", result != null ? instanceService.viewOf(entity.orElse(null)) : GcLogInstanceService.view(null, null, null));
        return "gc-log/analyze";
    }
}

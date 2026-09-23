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
        List<GcLogAnalysisEntity> history = service.history();
        model.addAttribute("gcLogFiles", files);
        model.addAttribute("history", history);
        // 서랍(대표 행 + 접힌 구성 파일) 렌더용 — 템플릿이 대표만 최상위로 뽑고 구성 파일을 그 뒤에 붙인다.
        // ⚠ 늘 non-null 이어야 한다 — 템플릿의 th:each 가 null 을 만나면 안 되고,
        //   th:if 안에 삼항을 쓰면 값이 조용히 null 이 된다(함정 59).
        java.util.Map<String, List<GcLogAnalysisEntity>> members = membersByParent(history);
        java.util.Set<String> claimed = new java.util.LinkedHashSet<>();
        members.values().forEach(list -> list.forEach(m -> claimed.add(m.getFilename())));
        model.addAttribute("memberRows", members);
        // ⚠ 최상위 여부를 part_of 로 판정하지 말 것 — 대표의 member_files 에서 빠진 구성 파일이 있으면
        //   어느 쪽에서도 렌더되지 않아 목록에서 조용히 사라진다. '어느 묶음이 실제로 그리는가' 로 판정한다.
        model.addAttribute("claimed", claimed);
        model.addAttribute("isAdmin", com.heapdump.analyzer.util.AuthUtil.isAdmin(auth));
        return "gc-log/index";
    }

    /** 대표 파일명 → 구성 파일 행(병합 순서). 순서는 대표의 {@code member_files} 가 정한다. */
    private java.util.Map<String, List<GcLogAnalysisEntity>> membersByParent(List<GcLogAnalysisEntity> history) {
        java.util.Map<String, GcLogAnalysisEntity> byName = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<GcLogAnalysisEntity>> out = new java.util.LinkedHashMap<>();
        for (GcLogAnalysisEntity e : history) byName.put(e.getFilename(), e);
        for (GcLogAnalysisEntity rep : history) {
            if (rep.getPartOf() != null || !rep.isCombined()) continue;
            // 대표 자신이 남의 구성원이면 그릴 수 없다 — 위 조건이 이미 걸렀다.
            List<GcLogAnalysisEntity> members = new java.util.ArrayList<>();
            for (var ref : service.parseMembers(rep.getMemberFiles())) {
                if (ref.filename().equals(rep.getFilename())) continue;   // 대표 자신은 구성 목록에 들어 있다
                GcLogAnalysisEntity m = byName.get(ref.filename());
                if (m != null) members.add(m);
            }
            if (!members.isEmpty()) out.put(rep.getFilename(), members);
        }
        return out;
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
        // 이어 붙인 구성 — 화면이 '구성 파일 N개' 알약과 누락 안내를 서버 렌더로 띄운다(함정 63: 파일이 사라지면 재분석이 막힌다)
        List<String> members = entity.map(e -> service.parseMembers(e.getMemberFiles()).stream()
                .map(GcLogAnalyzerService.MemberRef::filename).toList()).orElse(List.of());
        model.addAttribute("members", members);
        model.addAttribute("missingMembers", members.stream().filter(m -> !service.fileOf(m).isFile()).toList());
        // KPI '인스턴스' 카드 초기값 — 수동 입력 > 연결된 힙 덤프의 Instance. 결과가 있을 때만 카드가 그려지므로 그때만 덤프를 조회한다
        model.addAttribute("instance", result != null ? instanceService.viewOf(entity.orElse(null)) : GcLogInstanceService.view(null, null, null));
        return "gc-log/analyze";
    }
}

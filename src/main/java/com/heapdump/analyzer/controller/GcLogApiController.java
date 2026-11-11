package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.GcLogResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.parser.gclog.GcLogFormat;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.GcLogGroupPlanner;
import com.heapdump.analyzer.service.GcLogInstanceService;
import com.heapdump.analyzer.service.GcLogMatchService;
import com.heapdump.analyzer.service.LlmRateLimitService;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GC 로그 API (2026-09-14). 전부 {@code /api/gc-log/**} — 인증 필요(anyRequest), CSRF 면제 영역, USER/ADMIN 공통.
 * 오류 응답은 {@code {success,code,error}} 3필드(함정 14). 감사 로그는 {@code [GcLog] action=… by=}.
 */
@RestController
public class GcLogApiController {

    private static final Logger logger = LoggerFactory.getLogger(GcLogApiController.class);

    private final GcLogAnalyzerService service;
    private final GcLogMatchService matchService;
    private final GcLogInstanceService instanceService;
    private final AnalysisHistoryRepository historyRepository;
    private final HeapDumpConfig config;

    public GcLogApiController(GcLogAnalyzerService service, GcLogMatchService matchService, GcLogInstanceService instanceService,
                              AnalysisHistoryRepository historyRepository, HeapDumpConfig config) {
        this.service = service;
        this.matchService = matchService;
        this.instanceService = instanceService;
        this.historyRepository = historyRepository;
        this.config = config;
    }

    private static String who(Principal p) { return p != null ? p.getName() : "unknown"; }

    private static Map<String, Object> err(String code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("error", msg);
        return m;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(err("BAD_REQUEST", e.getMessage()));
    }

    /** 호스트네임 없이 분석을 시작하려 했다 — 화면이 입력 칸을 펼칠 수 있게 전용 코드로 내린다. */
    @ExceptionHandler(GcLogAnalyzerService.MissingHostnameException.class)
    public ResponseEntity<Map<String, Object>> noHostname(GcLogAnalyzerService.MissingHostnameException e) {
        return ResponseEntity.badRequest().body(err("NO_HOSTNAME", e.getMessage()));
    }

    // ── 업로드 ──────────────────────────────────────────────────

    @PostMapping("/api/gc-log/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("gcLogFile") MultipartFile file,
                                                      @RequestParam(value = "serverName", required = false) String serverName,
                                                      @RequestParam(value = "instanceName", required = false) String instanceName,
                                                      Principal principal) {
        if (file == null || file.isEmpty()) return ResponseEntity.badRequest().body(err("EMPTY", "GC 로그 파일이 필요합니다."));
        String safe = service.validateGcLogFilename(file.getOriginalFilename());
        if (file.getSize() > config.getGcLogMaxFileBytes()) {
            return ResponseEntity.status(413).body(err("TOO_LARGE", "파일이 상한(" + (config.getGcLogMaxFileBytes() >> 20) + " MB)을 넘습니다."));
        }
        File dir = service.dumpFilesDir();
        dir.mkdirs();
        File dest = new File(dir, safe);
        if (dest.exists()) {
            logger.warn("[GcLog] action=upload-rejected file={} reason=DUPLICATE_NAME by={}", safe, who(principal));
            return ResponseEntity.status(409).body(err("DUPLICATE_NAME", "같은 이름의 GC 로그가 이미 있습니다: " + safe));
        }
        try {
            file.transferTo(dest);
            GcLogFormat fmt = service.sniffFormat(dest);
            if (fmt == GcLogFormat.UNKNOWN) {
                if (!dest.delete()) logger.warn("[GcLog] 거부 파일 삭제 실패: {}", dest);
                logger.info("[GcLog] action=upload-rejected file={} reason=NOT_GC_LOG by={}", safe, who(principal));
                return ResponseEntity.badRequest().body(err("NOT_GC_LOG",
                        "GC 로그 형식을 인식하지 못했습니다. JDK 9+ 통합 로깅(-Xlog:gc*) 또는 JDK 8 이하 -XX:+PrintGCDetails 출력만 지원합니다."));
            }
            GcLogAnalysisEntity e = service.registerUploaded(safe, dest.length(), who(principal), serverName, instanceName);
            logger.info("[GcLog] action=upload file={} size={} format={} by={}", safe, dest.length(), fmt, who(principal));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            ok.put("filename", safe);
            ok.put("size", dest.length());
            ok.put("format", fmt.name());
            ok.put("matched", e.getMatchedDumpFilename());
            ok.put("analysisName", e.getAnalysisName());
            ok.put("analysisDate", e.getAnalysisDate() == null ? null : e.getAnalysisDate().toString());
            return ResponseEntity.ok(ok);
        } catch (IOException e) {
            logger.error("[GcLog] 업로드 I/O 실패 file={} by={} — {}", safe, who(principal), e.getMessage());
            return ResponseEntity.internalServerError().body(err("IO_ERROR", "파일 저장 중 오류: " + e.getMessage()));
        }
    }

    // ── 분석 ────────────────────────────────────────────────────

    @PostMapping("/api/gc-log/analyze/{filename:.+}")
    public ResponseEntity<Map<String, Object>> analyze(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        if (!service.fileOf(safe).isFile()) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 파일이 없습니다: " + safe));
        Optional<GcLogAnalysisEntity> existing = service.find(safe);
        if (existing.isPresent() && GcLogAnalysisEntity.STATUS_SUCCESS.equals(existing.get().getStatus()) && !service.isAnalyzing(safe)) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            ok.put("status", GcLogAnalysisEntity.STATUS_SUCCESS);
            return ResponseEntity.ok(ok);
        }
        service.submitAnalysis(safe, who(principal));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("status", GcLogAnalysisEntity.STATUS_ANALYZING);
        return ResponseEntity.ok(ok);
    }

    @PostMapping("/api/gc-log/reanalyze/{filename:.+}")
    public ResponseEntity<Map<String, Object>> reanalyze(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        if (!service.fileOf(safe).isFile()) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 파일이 없습니다: " + safe));
        if (service.isAnalyzing(safe)) return ResponseEntity.badRequest().body(err("ANALYZING", "이미 분석이 진행 중입니다."));
        service.submitAnalysis(safe, who(principal));
        logger.info("[GcLog] action=reanalyze file={} by={}", safe, who(principal));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("status", GcLogAnalysisEntity.STATUS_ANALYZING);
        return ResponseEntity.ok(ok);
    }

    @PostMapping("/api/gc-log/cancel/{filename:.+}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        boolean c = service.cancel(safe);
        logger.info("[GcLog] action=cancel file={} cancelled={} by={}", safe, c, who(principal));
        return ResponseEntity.ok(Map.of("success", true, "cancelled", c));
    }

    @GetMapping("/api/gc-log/{filename:.+}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String filename) {
        String safe = service.validateGcLogFilename(filename);
        Map<String, Object> m = service.getStatus(safe);
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    @GetMapping("/api/gc-log/{filename:.+}/result")
    public ResponseEntity<?> result(@PathVariable String filename) {
        String safe = service.validateGcLogFilename(filename);
        Optional<GcLogResult> r = service.loadResult(safe);
        if (r.isEmpty()) return ResponseEntity.status(404).body(err("NO_RESULT", "분석 결과가 없습니다: " + safe));
        return ResponseEntity.ok(r.get());
    }

    /** 힙 analyze 패널용 축약(연결된 덤프 시점 포함). */
    @GetMapping("/api/gc-log/{filename:.+}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String filename,
                                                       @RequestParam(value = "dump", required = false) String dump) {
        String safe = service.validateGcLogFilename(filename);
        AnalysisHistoryEntity d = null;
        if (dump != null && !dump.isBlank()) d = historyRepository.findByFilename(FilenameValidator.validate(dump)).orElse(null);
        else {
            Optional<GcLogAnalysisEntity> e = service.find(safe);
            if (e.isPresent() && e.get().getMatchedDumpFilename() != null) d = historyRepository.findByFilename(e.get().getMatchedDumpFilename()).orElse(null);
        }
        Map<String, Object> m = service.summaryForDump(safe, d);
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /** 원본 다운로드 — Files 페이지 GC Log 탭·일괄 다운로드용(코어 덤프 다운로드와 대칭). */
    @GetMapping("/api/gc-log/download/{filename:.+}")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String filename) {
        String safe = service.validateGcLogFilename(filename);
        File file = service.fileOf(safe);
        if (!file.isFile()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, HttpHeaderValues.attachment(safe))   // 함정 71
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(new org.springframework.core.io.FileSystemResource(file));
    }

    @GetMapping("/api/gc-log/history")
    public ResponseEntity<List<GcLogAnalysisEntity>> history() {
        return ResponseEntity.ok(service.history());
    }

    @DeleteMapping("/api/gc-log/{filename:.+}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String filename,
                                                      @RequestParam(defaultValue = "true") boolean deleteFile,
                                                      Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        service.delete(safe, deleteFile);
        logger.info("[GcLog] action=delete file={} deleteFile={} by={}", safe, deleteFile, who(principal));
        return ResponseEntity.ok(Map.of("success", true, "filename", safe));
    }

    /**
     * 출처 서버명(수동) — body {@code hostname}, 빈 값이면 지운다. 결과 페이지 헤더의 '서버' 알약이 부른다.
     * 서버명은 자동 매칭 신호라 저장 후 재평가한다(수동 연결은 덮지 않음). 응답은 매칭 뷰 + {@code hostname} —
     * 칩·인스턴스 카드를 같은 응답으로 다시 그린다. {@code hostname} 키가 없으면 400(조용히 지우지 않는다).
     */
    @PostMapping("/api/gc-log/{filename:.+}/hostname")
    public ResponseEntity<Map<String, Object>> hostname(@PathVariable String filename,
                                                        @RequestBody Map<String, Object> body, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        if (!body.containsKey("hostname")) throw new IllegalArgumentException("hostname 값이 필요합니다.");
        Object hv = body.get("hostname");
        String before = e.getServerName();
        String beforeMatch = e.getMatchedDumpFilename();
        e = service.updateServerName(e, hv == null ? null : hv.toString());
        logger.info("[GcLog] action=hostname file={} hostname='{}'->'{}' match={}->{} by={}",
                safe, before, e.getServerName(), beforeMatch, e.getMatchedDumpFilename(), who(principal));
        Map<String, Object> ok = matchView(e);
        ok.put("hostname", e.getServerName());
        ok.put("analysisName", e.getAnalysisName());
        return ResponseEntity.ok(ok);
    }

    /**
     * 인스턴스명 수동 입력 — body {@code instance}. 빈 값이면 수동값을 지워 연결된 힙 덤프의 Instance 로 되돌린다.
     * 응답의 {@code instance} 는 결과 페이지 KPI 카드가 그대로 다시 그리는 뷰다({@link GcLogInstanceService#view}).
     */
    @PostMapping("/api/gc-log/{filename:.+}/instance")
    public ResponseEntity<Map<String, Object>> instance(@PathVariable String filename,
                                                        @RequestBody Map<String, Object> body, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        if (!body.containsKey("instance")) throw new IllegalArgumentException("instance 값이 필요합니다.");
        Object iv = body.get("instance");
        String before = e.getInstanceName();
        e = instanceService.updateManual(e, iv == null ? null : iv.toString());
        service.renameAfterInstanceChange(e);   // 분석명이 인스턴스명을 담는다(같은 객체를 고친다)
        logger.info("[GcLog] action=instance file={} instance='{}'->'{}' name={} by={}",
                safe, before, e.getInstanceName(), e.getAnalysisName(), who(principal));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("instance", instanceService.viewOf(e));
        ok.put("analysisName", e.getAnalysisName());
        return ResponseEntity.ok(ok);
    }

    // ── 이어 붙이기 (2026-09-20) ────────────────────────────────

    /**
     * 회전 로그를 이어 붙여 다시 분석한다 — body {@code {filenames:[...]}}(대표는 없어도 자동 포함).
     * 빈 목록이면 구성을 해제하고 대표 단독으로 다시 분석한다.
     */
    @PostMapping("/api/gc-log/{filename:.+}/combine")
    public ResponseEntity<Map<String, Object>> combine(@PathVariable String filename,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        if (service.find(safe).isEmpty()) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        List<String> names = filenames(body);
        // via 는 감사 로그 표지일 뿐이라 화이트리스트 밖의 값은 manual 로 둔다(로그에 임의 문자열이 들어가지 않게)
        String via = body != null && "suggest".equals(body.get("via")) ? "suggest" : "manual";
        service.submitCombined(safe, names, who(principal), via);
        GcLogAnalysisEntity e = service.find(safe).orElseThrow();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("filename", safe);
        ok.put("status", e.getStatus());
        ok.put("memberCount", e.getMemberCount());
        ok.put("analysisName", e.getAnalysisName());
        return ResponseEntity.ok(ok);
    }

    /** 이어 붙이기 미리보기 — 순서와 경고만 돌려주고 분석은 시작하지 않는다. */
    @PostMapping("/api/gc-log/{filename:.+}/combine/preview")
    public ResponseEntity<Map<String, Object>> combinePreview(@PathVariable String filename,
                                                              @RequestBody(required = false) Map<String, Object> body) {
        String safe = service.validateGcLogFilename(filename);
        if (service.find(safe).isEmpty()) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        GcLogAnalyzerService.CombinePlan plan = service.planCombine(safe, filenames(body));
        List<Map<String, Object>> order = new ArrayList<>();
        for (GcLogAnalyzerService.MemberRef ref : plan.ordered()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", ref.filename());
            m.put("size", ref.size());
            order.add(m);
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("order", order);
        ok.put("warnings", plan.warnings());
        ok.put("totalBytes", plan.totalBytes());
        ok.put("orderBasis", plan.orderBasis());
        return ResponseEntity.ok(ok);
    }

    /** 이어 붙일 후보 — 같은 회전 계열·서버·일자면 {@code suggested} 가 true 로 온다. */
    @GetMapping("/api/gc-log/{filename:.+}/combine-options")
    public ResponseEntity<Map<String, Object>> combineOptions(@PathVariable String filename,
                                                              @RequestParam(value = "q", required = false) String q) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("options", service.combineOptions(e, q, 200));
        ok.put("current", service.combineCurrent(e));
        ok.put("members", service.parseMembers(e.getMemberFiles()).stream().map(GcLogAnalyzerService.MemberRef::filename).toList());
        ok.put("maxMembers", GcLogAnalyzerService.MAX_MEMBERS);
        return ResponseEntity.ok(ok);
    }

    /**
     * 구성만 바꾼다 — 묶기·해제·구성원 빼기. <b>분석은 시작하지 않는다</b>(2026-09-21).
     *
     * <p>{@code /combine} 의 쌍둥이다. 빈 배열이면 해제, 일부만 주면 그만 남긴다.
     * 묶기는 분석이 아니므로 호스트네임을 요구하지 않는다(함정 66).
     */
    @PostMapping("/api/gc-log/{filename:.+}/combine/link")
    public ResponseEntity<Map<String, Object>> combineLink(@PathVariable String filename,
                                                           @RequestBody(required = false) Map<String, Object> body,
                                                           Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        if (service.find(safe).isEmpty()) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        service.linkCombined(safe, filenames(body), who(principal), "manual");
        GcLogAnalysisEntity e = service.find(safe).orElseThrow();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("filename", safe);
        ok.put("status", e.getStatus());
        ok.put("memberCount", e.getMemberCount());
        ok.put("members", service.parseMembers(e.getMemberFiles()).stream()
                .map(GcLogAnalyzerService.MemberRef::filename).toList());
        ok.put("analysisName", e.getAnalysisName());
        return ResponseEntity.ok(ok);
    }

    /**
     * 분석 시작 전 연속 로그 제안(2026-09-23) — 이 파일이 속한 연속 회전본을 찾는다. <b>쓰기 없음</b>.
     *
     * <p>분석 시작 확인 창(analyze-confirm.js)이 GC 로그를 열 때 부른다. 원격 전송은 파일마다 따로 도착해
     * 업로드 큐의 자동 묶기가 돌 자리가 없으므로, 여기서 찾아 보여 주고 사용자가 수락하면
     * {@code POST …/{대표}/combine {via:"suggest"}} 로 이어 붙여 분석한다.
     */
    @GetMapping("/api/gc-log/{filename:.+}/group-suggestion")
    public ResponseEntity<Map<String, Object>> groupSuggestion(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        if (service.find(safe).isEmpty()) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        GcLogAnalyzerService.GroupSuggestion s = service.suggestGroup(safe);
        logger.info("[GcLog] action=group-suggest file={} mode={} rep={} members={} candidates={} excluded={} reason={} by={}",
                safe, s.mode(), s.representative(), s.members().size(), s.candidates(), s.excludedGrouped(),
                s.reason(), who(principal));
        return ResponseEntity.ok(suggestionBody(s));
    }

    private static Map<String, Object> suggestionBody(GcLogAnalyzerService.GroupSuggestion s) {
        List<Map<String, Object>> members = new ArrayList<>(s.members().size());
        for (GcLogAnalyzerService.SuggestedMember m : s.members()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("filename", m.filename());
            row.put("size", m.size());
            row.put("start", m.start());
            row.put("end", m.end());
            row.put("status", m.status());
            row.put("analysisName", m.analysisName());
            row.put("missing", m.missing());
            members.add(row);
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("mode", s.mode());
        ok.put("filename", s.filename());
        ok.put("representative", s.representative());
        ok.put("series", s.series());
        ok.put("members", members);
        ok.put("axes", s.axes() == null ? null : Map.of("time", s.axes().time().name(),
                                                         "origin", s.axes().origin().name(),
                                                         "jvm", s.axes().jvm().name()));
        ok.put("warnings", s.warnings());
        ok.put("reason", s.reason());
        ok.put("candidates", s.candidates());
        ok.put("excludedGrouped", s.excludedGrouped());
        ok.put("totalBytes", s.totalBytes());
        ok.put("orderBasis", s.orderBasis());
        ok.put("serverName", s.serverName());
        ok.put("collector", s.collector());
        ok.put("heapMaxBytes", s.heapMaxBytes());
        ok.put("maxMembers", GcLogAnalyzerService.MAX_MEMBERS);
        return ok;
    }

    /** 연속 회전본 자동 판정 — 판정만 한다(쓰기 없음). */
    @PostMapping("/api/gc-log/group/preview")
    public ResponseEntity<Map<String, Object>> groupPreview(@RequestBody(required = false) Map<String, Object> body,
                                                            Principal principal) {
        return ResponseEntity.ok(groupBody(service.autoGroup(filenames(body), who(principal), false)));
    }

    /**
     * 연속 회전본 자동 판정 + <b>묶기</b>. ⚠ 분석은 시작하지 않는다 — 대표 행은 미분석으로 남고
     * 사용자가 '분석'을 누를 때 저장된 구성 그대로 이어 붙여 분석된다.
     */
    @PostMapping("/api/gc-log/group/auto")
    public ResponseEntity<Map<String, Object>> groupAuto(@RequestBody(required = false) Map<String, Object> body,
                                                         Principal principal) {
        return ResponseEntity.ok(groupBody(service.autoGroup(filenames(body), who(principal), true)));
    }

    /** 자동 판정 응답 — 묶이지 않은 파일과 이유까지 실어 화면이 설명할 수 있게 한다. */
    private static Map<String, Object> groupBody(GcLogAnalyzerService.AutoGroupResult r) {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GcLogAnalyzerService.GroupOutcome o : r.groups()) {
            GcLogGroupPlanner.Group g = o.group();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("representative", g.representative());
            m.put("members", g.members());
            m.put("memberCount", g.members().size());
            m.put("totalBytes", g.totalBytes());
            m.put("orderBasis", g.orderBasis());
            m.put("axes", Map.of("time", g.axes().time().name(),
                                 "origin", g.axes().origin().name(),
                                 "jvm", g.axes().jvm().name()));
            m.put("warnings", g.warnings());
            m.put("linked", o.linked());
            m.put("analysisName", o.analysisName());
            m.put("error", o.error());
            groups.add(m);
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("groups", groups);
        ok.put("singles", r.singles());
        ok.put("singleReasons", r.singleReasons());
        ok.put("skipped", r.skipped());
        return ok;
    }

    @SuppressWarnings("unchecked")
    private static List<String> filenames(Map<String, Object> body) {
        Object v = body == null ? null : body.get("filenames");
        if (v == null) return List.of();
        if (!(v instanceof List<?> raw)) throw new IllegalArgumentException("filenames 는 배열이어야 합니다.");
        List<String> out = new ArrayList<>();
        for (Object o : raw) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    // ── 매칭 ────────────────────────────────────────────────────

    @GetMapping("/api/gc-log/{filename:.+}/match")
    public ResponseEntity<Map<String, Object>> getMatch(@PathVariable String filename) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        return ResponseEntity.ok(matchView(e));
    }

    /** 수동 연결({@code dumpFilename}) / 해제({@code null}). 이후 자동 매칭이 덮지 않는다. */
    @PostMapping("/api/gc-log/{filename:.+}/match")
    public ResponseEntity<Map<String, Object>> setMatch(@PathVariable String filename,
                                                        @RequestBody Map<String, Object> body, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        Object dv = body.get("dumpFilename");
        String dump = dv == null || dv.toString().isBlank() ? null : FilenameValidator.validate(dv.toString());
        String before = e.getMatchedDumpFilename();
        e = matchService.setManual(e, dump);
        logger.info("[GcLog] action={} file={} dump={}->{} by={}", dump == null ? "unlink" : "match", safe, before, dump, who(principal));
        return ResponseEntity.ok(matchView(e));
    }

    /** 자동 재평가 — manual 도 덮는다(명시적 ⟳). */
    @PostMapping("/api/gc-log/{filename:.+}/rematch")
    public ResponseEntity<Map<String, Object>> rematch(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        GcLogMatchService.Decision d = matchService.tryAutoMatch(e, true);
        logger.info("[GcLog] action=rematch file={} result={} by={}", safe, d == null ? "error" : d.reason(), who(principal));
        e = service.find(safe).orElse(e);
        return ResponseEntity.ok(matchView(e));
    }

    @GetMapping("/api/gc-log/{filename:.+}/match-options")
    public ResponseEntity<Map<String, Object>> matchOptions(@PathVariable String filename,
                                                            @RequestParam(value = "q", required = false) String q) {
        String safe = service.validateGcLogFilename(filename);
        GcLogAnalysisEntity e = service.find(safe).orElse(null);
        if (e == null) return ResponseEntity.status(404).body(err("NOT_FOUND", "GC 로그 이력이 없습니다: " + safe));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("options", matchService.matchOptions(e, q, 200));
        return ResponseEntity.ok(m);
    }

    private Map<String, Object> matchView(GcLogAnalysisEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("filename", e.getFilename());
        if (e.getMatchedDumpFilename() != null) {
            Map<String, Object> matched = new LinkedHashMap<>();
            matched.put("dumpFilename", e.getMatchedDumpFilename());
            matched.put("source", e.getMatchSource());
            matched.put("reason", e.getMatchReason());
            matched.put("matchedAt", e.getMatchedAt() == null ? null : e.getMatchedAt().toString());
            m.put("matched", matched);
        } else {
            m.put("matched", null);
        }
        m.put("source", e.getMatchSource());
        m.put("sticky", e.isManualMatch());
        m.put("candidates", matchService.candidatesOf(e));
        m.put("serverName", e.getServerName());
        // 연결이 바뀌면 인스턴스 카드의 덤프 값도 바뀐다 — 칩 갱신과 같은 응답으로 카드도 다시 그린다
        m.put("instance", instanceService.viewOf(e));
        return ResponseEntity.ok(m).getBody();
    }

    // ── AI ──────────────────────────────────────────────────────

    @PostMapping("/api/gc-log/{filename:.+}/ai-analyze")
    public ResponseEntity<Map<String, Object>> aiAnalyze(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        String who = who(principal);
        if (!service.isLlmEnabled()) {
            logger.info("[GcLog-AI] action=analyze 거부 — LLM 비활성, file={}, by={}", safe, who);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("success", false);
            e.put("errorCode", "LLM_DISABLED");
            e.put("error", "AI 분석이 비활성화되어 있습니다. 설정에서 LLM 을 활성화하세요.");
            return ResponseEntity.ok(e);
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> result = service.analyzeWithAi(safe);
        long elapsed = System.currentTimeMillis() - t0;
        boolean success = Boolean.TRUE.equals(result.get("success"));
        Object dataObj = result.get("data");
        String severity = dataObj instanceof Map ? String.valueOf(((Map<?, ?>) dataObj).get("severity")) : null;
        if (success) {
            logger.info("[GcLog-AI] action=analyze file={} severity={} elapsed={}ms model={} by={}", safe, severity, elapsed, result.get("model"), who);
            Map<String, Object> toStore = new LinkedHashMap<>();
            toStore.put("model", result.get("model"));
            toStore.put("latencyMs", result.get("latencyMs"));
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> dm = (Map<String, Object>) dataObj;
                toStore.putAll(dm);
            }
            try {
                service.saveAiInsight(GcLogAnalyzerService.gcInsightKey(safe), toStore);
                result.put("saved", true);
                result.put("savedTo", "database");
                if (toStore.get("analysedAt") != null) result.put("analysedAt", toStore.get("analysedAt"));
            } catch (Exception ex) {
                logger.error("[GcLog-AI] action=analyze 저장 실패 file={} — {}", safe, ex.getMessage());
                result.put("saved", false);
                result.put("saveError", ex.getMessage());
                result.put("saveErrorCode", "SAVE_FAILED");
                result.put("retryPayload", toStore);
            }
        } else {
            logger.warn("[GcLog-AI] action=analyze 실패 file={} errorCode={} elapsed={}ms by={}", safe, result.get("errorCode"), elapsed, who);
        }
        return LlmRateLimitService.toResponse(result);
    }

    @GetMapping("/api/gc-log/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> getAiInsight(@PathVariable String filename) {
        String safe = service.validateGcLogFilename(filename);
        Map<String, Object> insight = service.loadAiInsight(GcLogAnalyzerService.gcInsightKey(safe));
        if (insight == null) return ResponseEntity.ok(Map.of("found", false));
        insight.put("found", true);
        insight.put("savedTo", "database");
        return ResponseEntity.ok(insight);
    }

    @DeleteMapping("/api/gc-log/{filename:.+}/ai-insight")
    public ResponseEntity<Map<String, Object>> deleteAiInsight(@PathVariable String filename, Principal principal) {
        String safe = service.validateGcLogFilename(filename);
        boolean deleted = service.deleteAiInsight(GcLogAnalyzerService.gcInsightKey(safe));
        logger.info("[GcLog-AI] action=delete-insight file={} deleted={} by={}", safe, deleted, who(principal));
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted));
    }
}

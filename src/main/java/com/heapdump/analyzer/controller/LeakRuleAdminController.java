package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.LeakFallbackRule;
import com.heapdump.analyzer.model.entity.LeakLibraryRule;
import com.heapdump.analyzer.repository.LeakFallbackRuleRepository;
import com.heapdump.analyzer.repository.LeakLibraryRuleRepository;
import com.heapdump.analyzer.service.LeakRuleService;
import com.heapdump.analyzer.util.LeakRuleContext;
import com.heapdump.analyzer.util.LeakRuleTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Leak Suspect 룰 관리(ADMIN 전용). /admin/** 와 /api/admin/** 는 SecurityConfig 에서 hasRole("ADMIN") 강제됨.
 */
@Controller
public class LeakRuleAdminController {

    private static final Logger logger = LoggerFactory.getLogger(LeakRuleAdminController.class);

    private final LeakLibraryRuleRepository libraryRepo;
    private final LeakFallbackRuleRepository fallbackRepo;
    private final LeakRuleService ruleService;

    public LeakRuleAdminController(LeakLibraryRuleRepository libraryRepo,
                                   LeakFallbackRuleRepository fallbackRepo,
                                   LeakRuleService ruleService) {
        this.libraryRepo = libraryRepo;
        this.fallbackRepo = fallbackRepo;
        this.ruleService = ruleService;
    }

    // ─── View ─────────────────────────────────────────────────────────
    @GetMapping("/admin/leak-rules")
    public String page(Model model) {
        model.addAttribute("libraryCount", libraryRepo.count());
        model.addAttribute("fallbackCount", fallbackRepo.count());
        return "leak-rules";
    }

    // ─── Library rules ────────────────────────────────────────────────
    @GetMapping("/api/admin/leak-rules/library")
    @ResponseBody
    public List<LeakLibraryRule> listLibrary() {
        return libraryRepo.findAllByOrderByPriorityAscIdAsc();
    }

    @PostMapping("/api/admin/leak-rules/library")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createLibrary(@RequestBody LeakLibraryRule body, Authentication auth) {
        Map<String, Object> err = validateLibrary(body);
        if (err != null) return ResponseEntity.badRequest().body(err);
        body.setId(null);
        LeakLibraryRule saved = libraryRepo.save(body);
        ruleService.invalidate();
        logger.info("[LeakRule] action=create kind=library id={} prefix='{}' priority={} enabled={} by={}",
                saved.getId(), saved.getPrefix(), saved.getPriority(), saved.isEnabled(), who(auth));
        return ok(java.util.Collections.singletonMap("id", (Object) saved.getId()));
    }

    @PutMapping("/api/admin/leak-rules/library/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateLibrary(@PathVariable Long id,
                                                             @RequestBody LeakLibraryRule body,
                                                             Authentication auth) {
        Optional<LeakLibraryRule> opt = libraryRepo.findById(id);
        if (!opt.isPresent()) return notFound("library 룰");
        Map<String, Object> err = validateLibrary(body);
        if (err != null) return ResponseEntity.badRequest().body(err);
        LeakLibraryRule cur = opt.get();
        boolean enabledChanged = cur.isEnabled() != body.isEnabled();
        boolean onlyEnabledChanged = enabledChanged
                && eq(cur.getPrefix(), body.getPrefix())
                && eq(cur.getLibraryName(), body.getLibraryName())
                && eq(cur.getCategory(), body.getCategory())
                && eq(cur.getSeverityHint(), body.getSeverityHint())
                && eq(cur.getExplanationTpl(), body.getExplanationTpl())
                && eq(cur.getAdviceTpl(), body.getAdviceTpl())
                && cur.getPriority() == body.getPriority();
        boolean oldEnabled = cur.isEnabled();
        cur.setPrefix(body.getPrefix());
        cur.setLibraryName(body.getLibraryName());
        cur.setCategory(body.getCategory());
        cur.setSeverityHint(body.getSeverityHint());
        cur.setExplanationTpl(body.getExplanationTpl());
        cur.setAdviceTpl(body.getAdviceTpl());
        cur.setEnabled(body.isEnabled());
        cur.setPriority(body.getPriority());
        libraryRepo.save(cur);
        ruleService.invalidate();
        if (onlyEnabledChanged) {
            logger.info("[LeakRule] action=toggle kind=library id={} prefix='{}' enabled={}->{} by={}",
                    id, cur.getPrefix(), oldEnabled, cur.isEnabled(), who(auth));
        } else {
            logger.info("[LeakRule] action=update kind=library id={} prefix='{}' priority={} enabled={} by={}",
                    id, cur.getPrefix(), cur.getPriority(), cur.isEnabled(), who(auth));
        }
        return ok(null);
    }

    @DeleteMapping("/api/admin/leak-rules/library/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteLibrary(@PathVariable Long id, Authentication auth) {
        Optional<LeakLibraryRule> opt = libraryRepo.findById(id);
        if (!opt.isPresent()) return notFound("library 룰");
        String prefix = opt.get().getPrefix();
        libraryRepo.deleteById(id);
        ruleService.invalidate();
        logger.info("[LeakRule] action=delete kind=library id={} prefix='{}' by={}", id, prefix, who(auth));
        return ok(null);
    }

    // ─── Fallback rules ───────────────────────────────────────────────
    @GetMapping("/api/admin/leak-rules/fallback")
    @ResponseBody
    public List<LeakFallbackRule> listFallback() {
        return fallbackRepo.findAllByOrderByPriorityAscIdAsc();
    }

    @PostMapping("/api/admin/leak-rules/fallback")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createFallback(@RequestBody LeakFallbackRule body, Authentication auth) {
        Map<String, Object> err = validateFallback(body);
        if (err != null) return ResponseEntity.badRequest().body(err);
        body.setId(null);
        LeakFallbackRule saved = fallbackRepo.save(body);
        ruleService.invalidate();
        logger.info("[LeakRule] action=create kind=fallback id={} name='{}' priority={} enabled={} by={}",
                saved.getId(), saved.getName(), saved.getPriority(), saved.isEnabled(), who(auth));
        return ok(java.util.Collections.singletonMap("id", (Object) saved.getId()));
    }

    @PutMapping("/api/admin/leak-rules/fallback/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateFallback(@PathVariable Long id,
                                                              @RequestBody LeakFallbackRule body,
                                                              Authentication auth) {
        Optional<LeakFallbackRule> opt = fallbackRepo.findById(id);
        if (!opt.isPresent()) return notFound("fallback 룰");
        Map<String, Object> err = validateFallback(body);
        if (err != null) return ResponseEntity.badRequest().body(err);
        LeakFallbackRule cur = opt.get();
        boolean enabledChanged = cur.isEnabled() != body.isEnabled();
        boolean onlyEnabledChanged = enabledChanged
                && eq(cur.getName(), body.getName())
                && eq(cur.getCategory(), body.getCategory())
                && eq(cur.getPatternRegex(), body.getPatternRegex())
                && eq(cur.getExplanationTpl(), body.getExplanationTpl())
                && eq(cur.getAdviceTpl(), body.getAdviceTpl())
                && eq(cur.getSeverityHint(), body.getSeverityHint())
                && cur.getPriority() == body.getPriority();
        boolean oldEnabled = cur.isEnabled();
        cur.setName(body.getName());
        cur.setCategory(body.getCategory());
        cur.setPatternRegex(body.getPatternRegex());
        cur.setExplanationTpl(body.getExplanationTpl());
        cur.setAdviceTpl(body.getAdviceTpl());
        cur.setSeverityHint(body.getSeverityHint());
        cur.setEnabled(body.isEnabled());
        cur.setPriority(body.getPriority());
        fallbackRepo.save(cur);
        ruleService.invalidate();
        if (onlyEnabledChanged) {
            logger.info("[LeakRule] action=toggle kind=fallback id={} name='{}' enabled={}->{} by={}",
                    id, cur.getName(), oldEnabled, cur.isEnabled(), who(auth));
        } else {
            logger.info("[LeakRule] action=update kind=fallback id={} name='{}' priority={} enabled={} by={}",
                    id, cur.getName(), cur.getPriority(), cur.isEnabled(), who(auth));
        }
        return ok(null);
    }

    @DeleteMapping("/api/admin/leak-rules/fallback/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteFallback(@PathVariable Long id, Authentication auth) {
        Optional<LeakFallbackRule> opt = fallbackRepo.findById(id);
        if (!opt.isPresent()) return notFound("fallback 룰");
        String name = opt.get().getName();
        fallbackRepo.deleteById(id);
        ruleService.invalidate();
        logger.info("[LeakRule] action=delete kind=fallback id={} name='{}' by={}", id, name, who(auth));
        return ok(null);
    }

    // ─── Export / Import (라이브러리, Fallback) ────────────────────────
    private static final int IMPORT_MAX_ROWS = 10000;
    private static final DateTimeFormatter FILENAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @GetMapping("/api/admin/leak-rules/library/export")
    public ResponseEntity<Map<String, Object>> exportLibrary(Authentication auth) {
        List<LeakLibraryRule> rows = libraryRepo.findAllByOrderByPriorityAscIdAsc();
        logger.info("[LeakRule] action=export kind=library count={} by={}", rows.size(), who(auth));
        return exportEnvelope(rows, "library", "leak-library-rules");
    }

    @GetMapping("/api/admin/leak-rules/fallback/export")
    public ResponseEntity<Map<String, Object>> exportFallback(Authentication auth) {
        List<LeakFallbackRule> rows = fallbackRepo.findAllByOrderByPriorityAscIdAsc();
        logger.info("[LeakRule] action=export kind=fallback count={} by={}", rows.size(), who(auth));
        return exportEnvelope(rows, "fallback", "leak-fallback-rules");
    }

    private ResponseEntity<Map<String, Object>> exportEnvelope(List<?> rows, String type, String filenamePrefix) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("type", type);
        body.put("exportedAt", now.toString());
        body.put("count", rows.size());
        body.put("rules", rows);
        String filename = filenamePrefix + "-" + FILENAME_TS.format(now) + ".json";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        h.setCacheControl("no-store");
        return new ResponseEntity<>(body, h, HttpStatus.OK);
    }

    @PostMapping("/api/admin/leak-rules/library/import")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> importLibrary(@RequestBody ImportRequest<LeakLibraryRule> req,
                                                             Authentication auth) {
        // 1) 상위 검증
        ResponseEntity<Map<String, Object>> reqErr = validateImportRequest(req, "library");
        if (reqErr != null) return reqErr;
        String mode = (req.mode == null ? "append" : req.mode.toLowerCase());

        // 2) 전건 사전 validation
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < req.rules.size(); i++) {
            Map<String, Object> err = validateLibrary(req.rules.get(i));
            if (err != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("index", i);
                e.put("error", err.get("error"));
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) return importValidationErrors(errors);

        // 3) replace 모드: 사전 truncate
        long deleted = 0;
        if ("replace".equals(mode)) {
            deleted = libraryRepo.count();
            libraryRepo.deleteAllInBatch();
            libraryRepo.flush();
        }

        // 4) id null 강제 → JPA 가 새 IDENTITY 발급, @PrePersist 가 timestamp 부여
        for (LeakLibraryRule r : req.rules) r.setId(null);
        libraryRepo.saveAll(req.rules);

        // 5) 캐시 무효화
        ruleService.invalidate();

        logger.info("[LeakRule] action=import kind=library mode={} deleted={} inserted={} by={}",
                mode, deleted, req.rules.size(), who(auth));
        return importSuccess(mode, deleted, req.rules.size());
    }

    @PostMapping("/api/admin/leak-rules/fallback/import")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> importFallback(@RequestBody ImportRequest<LeakFallbackRule> req,
                                                              Authentication auth) {
        ResponseEntity<Map<String, Object>> reqErr = validateImportRequest(req, "fallback");
        if (reqErr != null) return reqErr;
        String mode = (req.mode == null ? "append" : req.mode.toLowerCase());

        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < req.rules.size(); i++) {
            Map<String, Object> err = validateFallback(req.rules.get(i));
            if (err != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("index", i);
                e.put("error", err.get("error"));
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) return importValidationErrors(errors);

        long deleted = 0;
        if ("replace".equals(mode)) {
            deleted = fallbackRepo.count();
            fallbackRepo.deleteAllInBatch();
            fallbackRepo.flush();
        }

        for (LeakFallbackRule r : req.rules) r.setId(null);
        fallbackRepo.saveAll(req.rules);
        ruleService.invalidate();

        logger.info("[LeakRule] action=import kind=fallback mode={} deleted={} inserted={} by={}",
                mode, deleted, req.rules.size(), who(auth));
        return importSuccess(mode, deleted, req.rules.size());
    }

    /** Import 요청 공통 검증: rules 존재, mode 형식, type wrapper 매칭, 사이즈 상한. */
    private static ResponseEntity<Map<String, Object>> validateImportRequest(ImportRequest<?> req, String expectedType) {
        if (req == null || req.rules == null) {
            return ResponseEntity.badRequest().body(errMap("요청 본문이 비어있거나 rules 가 없습니다."));
        }
        String mode = (req.mode == null ? "append" : req.mode.toLowerCase());
        if (!"replace".equals(mode) && !"append".equals(mode)) {
            return ResponseEntity.badRequest().body(errMap("mode 는 'replace' 또는 'append' 여야 합니다."));
        }
        if (req.type != null && !expectedType.equals(req.type)) {
            return ResponseEntity.badRequest().body(errMap(
                    "파일 type 이 '" + expectedType + "' 가 아닙니다 (실제: " + req.type + ")"));
        }
        if (req.rules.size() > IMPORT_MAX_ROWS) {
            return ResponseEntity.badRequest().body(errMap(
                    "rules 가 너무 많습니다 (최대 " + IMPORT_MAX_ROWS + "건)."));
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> importValidationErrors(List<Map<String, Object>> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "검증 실패: " + errors.size() + "건의 룰에 오류가 있습니다. 가져오기를 중단했습니다.");
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<Map<String, Object>> importSuccess(String mode, long deleted, int inserted) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("mode", mode);
        body.put("deleted", deleted);
        body.put("inserted", inserted);
        return ResponseEntity.ok(body);
    }

    // ─── Preview ──────────────────────────────────────────────────────
    @PostMapping("/api/admin/leak-rules/preview")
    @ResponseBody
    public Map<String, Object> preview(@RequestBody PreviewRequest body) {
        LeakRuleContext ctx = new LeakRuleContext();
        ctx.simpleClassName = orDefault(body.simpleClassName, "Sample");
        ctx.className = orDefault(body.className, "com.example." + ctx.simpleClassName);
        ctx.classLoader = body.classLoader;
        ctx.instanceCount = body.instanceCount;
        ctx.bytes = body.bytes;
        ctx.percentage = body.percentage;
        ctx.accumulatorSimple = body.accumulatorSimple;
        ctx.accumulatorClass = (body.accumulatorSimple != null && !body.accumulatorSimple.isEmpty())
                ? "com.example." + body.accumulatorSimple : null;
        ctx.referencedFromClass = body.referencedFromClass;
        ctx.severity = severityFrom(ctx.percentage);
        // derived flags
        ctx.hasAccumulator = ctx.accumulatorClass != null;
        ctx.hasReferencedFrom = ctx.referencedFromClass != null;
        ctx.hasInstanceCount = ctx.instanceCount > 0;
        ctx.highPercentage = ctx.percentage >= 30.0;
        ctx.veryHighPercentage = ctx.percentage >= 50.0;
        String lcn = ctx.simpleClassName.toLowerCase();
        ctx.streamClass = lcn.contains("inputstream") || lcn.contains("outputstream") || lcn.contains("reader") || lcn.contains("writer");
        ctx.sessionClass = lcn.contains("session");
        ctx.threadClass = lcn.contains("thread");
        ctx.classLoaderClass = lcn.contains("classloader");
        ctx.cacheClass = lcn.contains("cache");
        ctx.mapClass = lcn.contains("map") || lcn.contains("hashtable") || lcn.contains("dictionary");

        Map<String, Object> resp = new HashMap<>();
        resp.put("explanation", LeakRuleTemplate.render(body.explanationTpl, ctx));
        resp.put("advice", LeakRuleTemplate.render(body.adviceTpl, ctx));
        resp.put("severity", ctx.severity);
        return resp;
    }

    // ─── 검증 ─────────────────────────────────────────────────────────
    private Map<String, Object> validateLibrary(LeakLibraryRule r) {
        if (r == null) return errMap("요청 본문이 비어있습니다.");
        if (isBlank(r.getPrefix()))         return errMap("prefix 는 필수입니다.");
        if (isBlank(r.getLibraryName()))    return errMap("libraryName 은 필수입니다.");
        if (isBlank(r.getCategory()))       return errMap("category 는 필수입니다.");
        if (isBlank(r.getExplanationTpl())) return errMap("explanationTpl 은 필수입니다.");
        if (isBlank(r.getAdviceTpl()))      return errMap("adviceTpl 은 필수입니다.");
        return null;
    }
    private Map<String, Object> validateFallback(LeakFallbackRule r) {
        if (r == null) return errMap("요청 본문이 비어있습니다.");
        if (isBlank(r.getName()))           return errMap("name 은 필수입니다.");
        if (isBlank(r.getCategory()))       return errMap("category 는 필수입니다.");
        if (isBlank(r.getPatternRegex()))   return errMap("patternRegex 는 필수입니다.");
        try { Pattern.compile(r.getPatternRegex()); }
        catch (PatternSyntaxException e)    { return errMap("정규식 오류: " + e.getDescription()); }
        if (isBlank(r.getExplanationTpl())) return errMap("explanationTpl 은 필수입니다.");
        if (isBlank(r.getAdviceTpl()))      return errMap("adviceTpl 은 필수입니다.");
        return null;
    }

    private static String severityFrom(double p) {
        if (p >= 50) return "critical";
        if (p >= 25) return "high";
        if (p >= 10) return "medium";
        if (p > 0)   return "low";
        return "medium";
    }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String orDefault(String s, String def) { return (s == null || s.isEmpty()) ? def : s; }

    // ─── 감사 로깅 헬퍼 ──────────────────────────────────────────────
    private static String who(Authentication auth) { return auth != null ? auth.getName() : "unknown"; }
    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> extra) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        if (extra != null) r.putAll(extra);
        return ResponseEntity.ok(r);
    }
    private static ResponseEntity<Map<String, Object>> notFound(String what) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("error", what + "을(를) 찾을 수 없습니다.");
        return ResponseEntity.status(404).body(r);
    }
    private static Map<String, Object> errMap(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("error", msg);
        return r;
    }

    // ─── Import payload ──────────────────────────────────────────────
    /**
     * Import 요청 본문. wrapper 형식({version,type,exportedAt,count,rules}) 그대로 수용.
     * bare array 호환을 위해 클라이언트가 {mode, rules:[...]} 로 감싸서 전송.
     */
    public static class ImportRequest<T> {
        public String mode;     // "replace" | "append" (대소문자 무시). null 이면 append.
        public String type;     // 선택. wrapper 일 때만 검증.
        public Integer version; // 선택. 현재 미사용 (스키마 진화 대비).
        public List<T> rules;   // 필수.
    }

    // ─── Preview payload ─────────────────────────────────────────────
    public static class PreviewRequest {
        public String explanationTpl;
        public String adviceTpl;
        public String simpleClassName;
        public String className;
        public String classLoader;
        public int instanceCount;
        public long bytes;
        public double percentage;
        public String accumulatorSimple;
        public String referencedFromClass;
    }

}

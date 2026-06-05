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
import org.springframework.data.jpa.repository.JpaRepository;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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

    // ─── 일괄 작업 (설정 모달: 전체 사용설정/사용해제/삭제) ─────────────
    @PostMapping("/api/admin/leak-rules/bulk-enabled")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> bulkEnabled(@RequestBody BulkRequest req, Authentication auth) {
        String target = normalizeTarget(req == null ? null : req.target);
        if (target == null) return ResponseEntity.badRequest().body(errMap("target 은 library / fallback / all 중 하나여야 합니다."));
        if (req.enabled == null) return ResponseEntity.badRequest().body(errMap("enabled 는 필수입니다."));
        boolean enabled = req.enabled;
        long libChanged = 0, fbChanged = 0;
        if ("library".equals(target) || "all".equals(target)) {
            List<LeakLibraryRule> rows = libraryRepo.findAll();
            for (LeakLibraryRule r : rows) if (r.isEnabled() != enabled) { r.setEnabled(enabled); libChanged++; }
            libraryRepo.saveAll(rows);
        }
        if ("fallback".equals(target) || "all".equals(target)) {
            List<LeakFallbackRule> rows = fallbackRepo.findAll();
            for (LeakFallbackRule r : rows) if (r.isEnabled() != enabled) { r.setEnabled(enabled); fbChanged++; }
            fallbackRepo.saveAll(rows);
        }
        ruleService.invalidate();
        logger.info("[LeakRule] action=bulk-enabled target={} enabled={} libraryChanged={} fallbackChanged={} by={}",
                target, enabled, libChanged, fbChanged, who(auth));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("libraryChanged", libChanged);
        extra.put("fallbackChanged", fbChanged);
        return ok(extra);
    }

    @PostMapping("/api/admin/leak-rules/bulk-delete")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody BulkRequest req, Authentication auth) {
        String target = normalizeTarget(req == null ? null : req.target);
        if (target == null) return ResponseEntity.badRequest().body(errMap("target 은 library / fallback / all 중 하나여야 합니다."));
        // 삭제 전 식별 정보(건수) 캡처 — 감사 로깅 컨벤션
        long libDeleted = 0, fbDeleted = 0;
        if ("library".equals(target) || "all".equals(target)) {
            libDeleted = libraryRepo.count();
            libraryRepo.deleteAllInBatch();
        }
        if ("fallback".equals(target) || "all".equals(target)) {
            fbDeleted = fallbackRepo.count();
            fallbackRepo.deleteAllInBatch();
        }
        ruleService.invalidate();
        logger.info("[LeakRule] action=bulk-delete target={} libraryDeleted={} fallbackDeleted={} by={}",
                target, libDeleted, fbDeleted, who(auth));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("libraryDeleted", libDeleted);
        extra.put("fallbackDeleted", fbDeleted);
        return ok(extra);
    }

    public static class BulkRequest {
        public String target;   // "library" | "fallback" | "all"
        public Boolean enabled; // bulk-enabled 전용
    }

    private static String normalizeTarget(String t) {
        if (t == null) return null;
        t = t.trim().toLowerCase();
        return ("library".equals(t) || "fallback".equals(t) || "all".equals(t)) ? t : null;
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
        String dup = (req.onDuplicate == null ? "append" : req.onDuplicate.toLowerCase());

        // 2) 전건 사전 validation (skip 대상 포함 — 부분 적용 혼란 방지)
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

        // 3) 적용 (자연 키 = prefix, trim/case-sensitive)
        ImportOutcome out = applyImport(mode, dup, req.rules, libraryRepo,
                r -> r.getPrefix() == null ? "" : r.getPrefix().trim(),
                LeakRuleAdminController::copyLibraryBusinessFields,
                r -> r.setId(null), "library");

        ruleService.invalidate();
        logger.info("[LeakRule] action=import kind=library mode={} onDuplicate={} deleted={} inserted={} updated={} skipped={} intraDup={} by={}",
                mode, dup, out.deleted, out.inserted, out.updated, out.skipped, out.intraDup, who(auth));
        return importSuccess(mode, dup, out);
    }

    @PostMapping("/api/admin/leak-rules/fallback/import")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> importFallback(@RequestBody ImportRequest<LeakFallbackRule> req,
                                                              Authentication auth) {
        ResponseEntity<Map<String, Object>> reqErr = validateImportRequest(req, "fallback");
        if (reqErr != null) return reqErr;
        String mode = (req.mode == null ? "append" : req.mode.toLowerCase());
        String dup = (req.onDuplicate == null ? "append" : req.onDuplicate.toLowerCase());

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

        // 적용 (자연 키 = patternRegex, trim/case-sensitive)
        ImportOutcome out = applyImport(mode, dup, req.rules, fallbackRepo,
                r -> r.getPatternRegex() == null ? "" : r.getPatternRegex().trim(),
                LeakRuleAdminController::copyFallbackBusinessFields,
                r -> r.setId(null), "fallback");

        ruleService.invalidate();
        logger.info("[LeakRule] action=import kind=fallback mode={} onDuplicate={} deleted={} inserted={} updated={} skipped={} intraDup={} by={}",
                mode, dup, out.deleted, out.inserted, out.updated, out.skipped, out.intraDup, who(auth));
        return importSuccess(mode, dup, out);
    }

    /** import 적용 결과 집계 */
    private static class ImportOutcome {
        long deleted; int inserted; int updated; int skipped; int intraDup;
    }

    /**
     * import 공통 적용 로직 (library/fallback 대칭).
     * - replace: 전체 삭제 후 전건 insert (onDuplicate 무시)
     * - append + append: 현행 동작 — 중복 무시 전건 insert
     * - append + skip|overwrite: 파일 내부 중복 last-wins 정리(intraDup) 후,
     *   DB 현재 상태 기준 자연 키 매칭 — 미존재 insert / skip 건너뜀 / overwrite 첫 매칭 행만 갱신.
     */
    private <T> ImportOutcome applyImport(String mode, String dup, List<T> rules,
                                          JpaRepository<T, Long> repo,
                                          Function<T, String> keyFn,
                                          BiConsumer<T, T> copyFn,
                                          Consumer<T> idNuller,
                                          String kind) {
        ImportOutcome out = new ImportOutcome();
        boolean dedupe = "append".equals(mode) && ("skip".equals(dup) || "overwrite".equals(dup));

        // 파일 내부 중복: skip/overwrite 일 때만 last-wins 로 정리 (LinkedHashMap — 순서 보존 + 마지막 값 우선)
        List<T> effective = rules;
        if (dedupe) {
            LinkedHashMap<String, T> fileMap = new LinkedHashMap<>();
            for (T r : rules) fileMap.put(keyFn.apply(r), r);
            out.intraDup = rules.size() - fileMap.size();
            effective = new ArrayList<>(fileMap.values());
        }

        if ("replace".equals(mode)) {
            out.deleted = repo.count();
            repo.deleteAllInBatch();
            repo.flush();
            for (T r : effective) idNuller.accept(r);
            repo.saveAll(effective);
            out.inserted = effective.size();
            return out;
        }

        if (!dedupe) { // append + append(모두 추가) — 현행 동작 유지 (하위호환)
            for (T r : effective) idNuller.accept(r);
            repo.saveAll(effective);
            out.inserted = effective.size();
            return out;
        }

        // append + skip|overwrite — 서버가 DB 현재 상태 기준 권위적 판정
        Map<String, List<T>> existingByKey = new HashMap<>();
        for (T cur : repo.findAll()) {
            existingByKey.computeIfAbsent(keyFn.apply(cur), k -> new ArrayList<>()).add(cur);
        }
        List<T> toInsert = new ArrayList<>();
        List<T> toUpdate = new ArrayList<>();
        for (T r : effective) {
            String key = keyFn.apply(r);
            List<T> hits = existingByKey.get(key);
            if (hits == null || hits.isEmpty()) {
                idNuller.accept(r);
                toInsert.add(r);
            } else if ("skip".equals(dup)) {
                out.skipped++;
            } else { // overwrite — 첫 매칭 행만 갱신 (기존 데이터 자체가 중복이면 파괴적 동작 회피)
                T cur = hits.get(0);
                copyFn.accept(cur, r); // id/createdAt 유지, @PreUpdate 가 updatedAt 갱신
                toUpdate.add(cur);
                if (hits.size() > 1) {
                    logger.warn("[LeakRule] import overwrite kind={} key='{}' matched {} existing rows; only first updated",
                            kind, key, hits.size());
                }
            }
        }
        repo.saveAll(toInsert);
        repo.saveAll(toUpdate);
        out.inserted = toInsert.size();
        out.updated = toUpdate.size();
        return out;
    }

    /** overwrite 시 기존 행에 파일 값 복사 — id/createdAt 유지 */
    private static void copyLibraryBusinessFields(LeakLibraryRule cur, LeakLibraryRule src) {
        cur.setPrefix(src.getPrefix());
        cur.setLibraryName(src.getLibraryName());
        cur.setCategory(src.getCategory());
        cur.setSeverityHint(src.getSeverityHint());
        cur.setExplanationTpl(src.getExplanationTpl());
        cur.setAdviceTpl(src.getAdviceTpl());
        cur.setEnabled(src.isEnabled());
        cur.setPriority(src.getPriority());
    }

    private static void copyFallbackBusinessFields(LeakFallbackRule cur, LeakFallbackRule src) {
        cur.setName(src.getName());
        cur.setCategory(src.getCategory());
        cur.setPatternRegex(src.getPatternRegex());
        cur.setExplanationTpl(src.getExplanationTpl());
        cur.setAdviceTpl(src.getAdviceTpl());
        cur.setSeverityHint(src.getSeverityHint());
        cur.setEnabled(src.isEnabled());
        cur.setPriority(src.getPriority());
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
        String dup = (req.onDuplicate == null ? "append" : req.onDuplicate.toLowerCase());
        if (!"skip".equals(dup) && !"overwrite".equals(dup) && !"append".equals(dup)) {
            return ResponseEntity.badRequest().body(errMap("onDuplicate 는 'skip' / 'overwrite' / 'append' 중 하나여야 합니다."));
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

    private static ResponseEntity<Map<String, Object>> importSuccess(String mode, String onDuplicate, ImportOutcome out) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("mode", mode);
        body.put("onDuplicate", onDuplicate);
        body.put("deleted", out.deleted);    // 하위호환 필드 유지
        body.put("inserted", out.inserted);  // 하위호환 필드 유지
        body.put("updated", out.updated);
        body.put("skipped", out.skipped);
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
        public String mode;        // "replace" | "append" (대소문자 무시). null 이면 append.
        public String onDuplicate; // "skip" | "overwrite" | "append" (대소문자 무시). null 이면 append. replace 모드에선 무시.
        public String type;        // 선택. wrapper 일 때만 검증.
        public Integer version;    // 선택. 현재 미사용 (스키마 진화 대비).
        public List<T> rules;      // 필수.
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

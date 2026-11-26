package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.LlmGuardLog;
import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.llmguard.CompiledRule;
import com.heapdump.analyzer.service.llmguard.LlmGuardLogService;
import com.heapdump.analyzer.service.llmguard.LlmGuardService;
import com.heapdump.analyzer.util.AuthUtil;
import com.heapdump.analyzer.util.CsvCodec;
import com.heapdump.analyzer.util.HttpHeaderValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 민감정보 탐지(LLM Guard) 관리 API — ADMIN 전용 (2026-11-26).
 *
 * <p>경로가 모두 {@code /api/admin/**} 아래라 {@code SecurityConfig} 의 ADMIN 매처와 CSRF 보호에 그대로 들어간다
 * ({@code LeakRuleAdminController} 와 같은 방식 — SecurityConfig 를 고치지 않는다). 클래스에 {@code @PreAuthorize} 를 겹쳐 둔다.
 *
 * <p>⚠ <b>탐지 이력 삭제 API 는 두지 않는다</b>(변조 방지 — 정리는 1년 보관 스케줄러만). 변경 API 는
 * {@code [LlmGuard] action=… by=…} 감사 로그를 남긴다.
 */
@RestController
@RequestMapping("/api/admin/llm-guard")
@PreAuthorize("hasRole('ADMIN')")
public class LlmGuardAdminController {

    private static final Logger logger = LoggerFactory.getLogger(LlmGuardAdminController.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList("개인식별", "개인신용", "중요정보", "기타"));

    private final LlmGuardService guard;
    private final LlmGuardLogService logService;
    private final LlmGuardRuleRepository ruleRepo;
    private final HeapDumpAnalyzerService analyzerService;

    public LlmGuardAdminController(LlmGuardService guard, LlmGuardLogService logService,
                                   LlmGuardRuleRepository ruleRepo, HeapDumpAnalyzerService analyzerService) {
        this.guard = guard;
        this.logService = logService;
        this.ruleRepo = ruleRepo;
        this.analyzerService = analyzerService;
    }

    // ── 설정 ──────────────────────────────────────────────────────

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("inputCheckEnabled", guard.isInputCheckEnabled());
        m.put("outputMaskEnabled", guard.isOutputMaskEnabled());
        m.put("outputInstructionEnabled", guard.isOutputInstructionEnabled());
        m.put("retentionDays", logService.effectiveRetentionDays());
        return m;
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> body,
                                                            Authentication authentication) {
        Boolean in = bool(body.get("inputCheckEnabled"));
        Boolean out = bool(body.get("outputMaskEnabled"));
        Boolean instr = bool(body.get("outputInstructionEnabled"));
        if (in == null || out == null || instr == null) {
            return bad("INVALID_SETTINGS", "inputCheckEnabled·outputMaskEnabled·outputInstructionEnabled 는 true/false 여야 합니다.");
        }
        boolean persisted = analyzerService.setLlmGuardSettings(in, out, instr);
        logger.info("[LlmGuard] action=settings-update inputCheck={} outputMask={} outputInstruction={} persisted={} by={}",
                in, out, instr, persisted, AuthUtil.who(authentication));
        Map<String, Object> m = getSettings();
        m.put("persisted", persisted);
        if (!persisted) m.put("warning", "설정은 적용됐지만 settings.json 기록에 실패했습니다. 재기동하면 이전 값으로 돌아갑니다.");
        return ResponseEntity.ok(m);
    }

    private static Boolean bool(Object v) {
        return v instanceof Boolean ? (Boolean) v : null;
    }

    // ── 규칙 CRUD ─────────────────────────────────────────────────

    @GetMapping("/rules")
    public Map<String, Object> listRules() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (LlmGuardRule r : ruleRepo.findAllByOrderBySortOrderAscIdAsc()) items.add(toMap(r));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("items", items);
        return m;
    }

    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody Map<String, Object> body,
                                                          Authentication authentication) {
        LlmGuardRule r = new LlmGuardRule();
        String err = applyEditable(r, body, false);
        if (err != null) return bad("INVALID_RULE", err);
        String type = str(body.get("matchType"));
        if (!LlmGuardRule.TYPE_REGEX.equals(type) && !LlmGuardRule.TYPE_KEYWORD.equals(type)) {
            return bad("INVALID_RULE", "규칙 유형은 REGEX 또는 KEYWORD 여야 합니다.");
        }
        r.setMatchType(type);
        r.setPattern(normalizePattern(type, str(body.get("pattern"))));
        err = validate(r);
        if (err != null) return bad("INVALID_RULE", err);
        String by = AuthUtil.who(authentication);
        LocalDateTime now = LocalDateTime.now();
        r.setCreatedAt(now);
        r.setCreatedBy(by);
        r.setUpdatedAt(now);
        r.setUpdatedBy(by);
        ruleRepo.save(r);
        guard.reload();
        logger.info("[LlmGuard] action=rule-create id={} name={} type={} by={}", r.getId(), r.getName(), type, by);
        return ResponseEntity.ok(ok(toMap(r)));
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                          Authentication authentication) {
        Optional<LlmGuardRule> opt = ruleRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(err("NOT_FOUND", "규칙을 찾을 수 없습니다."));
        LlmGuardRule r = opt.get();
        String err = applyEditable(r, body, r.isBuiltin());
        if (err != null) return bad("INVALID_RULE", err);
        if (body.containsKey("matchType") && !String.valueOf(body.get("matchType")).equals(r.getMatchType())) {
            if (r.isBuiltin()) return bad("BUILTIN_READONLY", "내장 규칙의 유형은 바꿀 수 없습니다.");
            String type = str(body.get("matchType"));
            if (!LlmGuardRule.TYPE_REGEX.equals(type) && !LlmGuardRule.TYPE_KEYWORD.equals(type)) {
                return bad("INVALID_RULE", "규칙 유형은 REGEX 또는 KEYWORD 여야 합니다.");
            }
            r.setMatchType(type);
        }
        if (body.containsKey("pattern")) {
            String p = normalizePattern(r.getMatchType(), str(body.get("pattern")));
            if (LlmGuardRule.TYPE_BUILTIN.equals(r.getMatchType())) {
                if (!String.valueOf(p).equals(r.getPattern())) {
                    return bad("BUILTIN_READONLY", "내장 규칙의 패턴은 바꿀 수 없습니다(Java 검증기로 판정합니다).");
                }
            } else {
                r.setPattern(p);
            }
        }
        err = validate(r);
        if (err != null) return bad("INVALID_RULE", err);
        String by = AuthUtil.who(authentication);
        r.setUpdatedAt(LocalDateTime.now());
        r.setUpdatedBy(by);
        ruleRepo.save(r);
        guard.reload();
        logger.info("[LlmGuard] action=rule-update id={} name={} enabled={} input={}/{} output={} by={}",
                r.getId(), r.getName(), r.isEnabled(), r.isCheckInput(), r.getInputAction(), r.isCheckOutput(), by);
        return ResponseEntity.ok(ok(toMap(r)));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable Long id, Authentication authentication) {
        Optional<LlmGuardRule> opt = ruleRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(err("NOT_FOUND", "규칙을 찾을 수 없습니다."));
        LlmGuardRule r = opt.get();
        if (r.isBuiltin()) return bad("BUILTIN_READONLY", "내장 규칙은 삭제할 수 없습니다. 사용을 끄세요.");
        ruleRepo.delete(r);
        guard.reload();
        logger.info("[LlmGuard] action=rule-delete id={} name={} by={}", id, r.getName(), AuthUtil.who(authentication));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /**
     * 편집 가능한 필드 적용. 내장 규칙은 이름·사용 여부·입력·출력 적용 여부·입력 조치만(키워드 목록은 pattern 경로).
     * @return 오류 문구, 정상이면 null
     */
    private static String applyEditable(LlmGuardRule r, Map<String, Object> body, boolean builtin) {
        if (body.containsKey("name") || r.getName() == null) {
            String name = str(body.get("name"));
            if (name == null || name.trim().isEmpty()) return "이름을 입력하세요.";
            if (name.trim().length() > 100) return "이름은 100자 이하여야 합니다.";
            r.setName(name.trim());
        }
        if (body.containsKey("enabled")) {
            if (!(body.get("enabled") instanceof Boolean)) return "enabled 는 true/false 여야 합니다.";
            r.setEnabled((Boolean) body.get("enabled"));
        }
        if (body.containsKey("checkInput")) {
            if (!(body.get("checkInput") instanceof Boolean)) return "checkInput 은 true/false 여야 합니다.";
            r.setCheckInput((Boolean) body.get("checkInput"));
        }
        if (body.containsKey("checkOutput")) {
            if (!(body.get("checkOutput") instanceof Boolean)) return "checkOutput 은 true/false 여야 합니다.";
            r.setCheckOutput((Boolean) body.get("checkOutput"));
        }
        if (body.containsKey("inputAction")) {
            String a = str(body.get("inputAction"));
            if (!LlmGuardRule.ACTION_CONFIRM.equals(a) && !LlmGuardRule.ACTION_BLOCK.equals(a)) {
                return "입력 조치는 CONFIRM(확인 후 전송) 또는 BLOCK(차단)이어야 합니다.";
            }
            r.setInputAction(a);
        }
        if (builtin) return null;
        if (body.containsKey("category") || r.getCategory() == null) {
            String c = str(body.get("category"));
            if (c == null || !CATEGORIES.contains(c)) return "분류는 개인식별·개인신용·중요정보·기타 중 하나여야 합니다.";
            r.setCategory(c);
        }
        if (body.containsKey("description")) {
            String d = str(body.get("description"));
            if (d != null && d.length() > 255) return "설명은 255자 이하여야 합니다.";
            r.setDescription(d == null || d.isBlank() ? null : d.trim());
        }
        if (body.containsKey("sortOrder") && body.get("sortOrder") instanceof Number) {
            r.setSortOrder(((Number) body.get("sortOrder")).intValue());
        } else if (r.getId() == null) {
            r.setSortOrder(1000);
        }
        return null;
    }

    /** 키워드는 줄 단위로 정규화해 저장한다(앞뒤 공백·빈 줄·중복 제거). */
    private static String normalizePattern(String type, String pattern) {
        if (pattern == null) return null;
        if (LlmGuardRule.TYPE_KEYWORD.equals(type)) return String.join("\n", CompiledRule.parseKeywords(pattern));
        return pattern;
    }

    private static String validate(LlmGuardRule r) {
        try {
            CompiledRule.compile(r);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ── 시험 ──────────────────────────────────────────────────────

    /** {@code {text, rule?}} — 입력 판정 + 출력 가림 미리보기. 저장 전 초안 규칙도 시험한다. 기록하지 않는다. */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(@RequestBody Map<String, Object> body) {
        String text = str(body.get("text"));
        if (text == null || text.isEmpty()) return bad("EMPTY_TEXT", "시험할 문장을 입력하세요.");
        LlmGuardRule draft = null;
        if (body.get("rule") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rb = (Map<String, Object>) body.get("rule");
            draft = new LlmGuardRule();
            draft.setId(null);
            if (!rb.containsKey("name")) rb.put("name", "초안");
            if (!rb.containsKey("category")) rb.put("category", "기타");
            String err = applyEditable(draft, rb, false);
            if (err != null) return bad("INVALID_RULE", err);
            String type = str(rb.get("matchType"));
            if (!LlmGuardRule.TYPE_REGEX.equals(type) && !LlmGuardRule.TYPE_KEYWORD.equals(type)) {
                return bad("INVALID_RULE", "규칙 유형은 REGEX 또는 KEYWORD 여야 합니다.");
            }
            draft.setMatchType(type);
            draft.setPattern(normalizePattern(type, str(rb.get("pattern"))));
            err = validate(draft);
            if (err != null) return bad("INVALID_RULE", err);
        }
        Map<String, Object> m = guard.preview(text, draft);
        m.put("success", true);
        m.put("draft", draft != null);
        return ResponseEntity.ok(m);
    }

    // ── 탐지 이력 ─────────────────────────────────────────────────

    @GetMapping("/logs")
    public ResponseEntity<Map<String, Object>> logs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long ruleId) {
        LlmGuardLogService.Query q;
        try {
            q = query(from, to, user, direction, action, ruleId);
        } catch (DateTimeParseException e) {
            return bad("INVALID_DATE", "날짜는 yyyy-MM-dd 형식이어야 합니다.");
        }
        Page<LlmGuardLog> result = logService.search(q, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LlmGuardLog l : result.getContent()) items.add(toMap(l));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", items);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("retentionDays", logService.effectiveRetentionDays());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/logs/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long ruleId,
            Authentication authentication) {
        LlmGuardLogService.Query q;
        try {
            q = query(from, to, user, direction, action, ruleId);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("날짜는 yyyy-MM-dd 형식이어야 합니다.");
        }
        List<LlmGuardLog> rows = logService.exportRows(q);
        String[] header = {"탐지 시각", "탐지 묶음", "사용자", "IP", "구분", "조치", "기능", "요청 URI", "규칙 ID", "규칙", "분류", "건수", "발췌(가린 값)"};
        List<String[]> out = new ArrayList<>();
        for (LlmGuardLog l : rows) {
            out.add(new String[]{
                    l.getDetectedAt() != null ? l.getDetectedAt().format(DT) : "",
                    l.getEventId(), safeCell(l.getUsername()), l.getClientIp(), l.getDirection(), l.getAction(),
                    l.getFeature(), safeCell(l.getRequestUri()), l.getRuleId() != null ? String.valueOf(l.getRuleId()) : "",
                    safeCell(l.getRuleName()), l.getCategory(), String.valueOf(l.getMatchCount()), safeCell(l.getSample())});
        }
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, HttpHeaderValues.attachment("llm-guard-log-" + ts + ".csv"));
        if (rows.size() >= LlmGuardLogService.EXPORT_MAX_ROWS) headers.add("X-Truncated", "true");
        logger.info("[LlmGuard] action=log-export rows={} by={}", rows.size(), AuthUtil.who(authentication));
        // 엑셀이 UTF-8 로 열도록 BOM 을 붙인다(이 파일은 색인기가 읽지 않는다)
        return new ResponseEntity<>("﻿" + CsvCodec.write(header, out), headers, HttpStatus.OK);
    }

    /** 엑셀 수식 주입 방지 — =,+,-,@ 로 시작하는 셀은 앞에 ' 를 붙인다(관리자 입력·사용자 발췌가 들어간다). */
    static String safeCell(String v) {
        if (v == null || v.isEmpty()) return v;
        char c = v.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + v : v;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> m = logService.stats(days);
        m.put("success", true);
        return m;
    }

    private static LlmGuardLogService.Query query(String from, String to, String user, String direction,
                                                  String action, Long ruleId) {
        LlmGuardLogService.Query q = new LlmGuardLogService.Query();
        q.from = from == null || from.isBlank() ? null : LocalDate.parse(from.trim());
        q.to = to == null || to.isBlank() ? null : LocalDate.parse(to.trim());
        q.user = user;
        q.direction = direction;
        q.action = action;
        q.ruleId = ruleId;
        return q;
    }

    // ── 직렬화·응답 헬퍼 ───────────────────────────────────────────

    private static Map<String, Object> toMap(LlmGuardRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("category", r.getCategory());
        m.put("matchType", r.getMatchType());
        m.put("pattern", r.getPattern());
        m.put("builtin", r.isBuiltin());
        m.put("builtinKey", r.getBuiltinKey());
        m.put("checkInput", r.isCheckInput());
        m.put("inputAction", r.getInputAction());
        m.put("checkOutput", r.isCheckOutput());
        m.put("enabled", r.isEnabled());
        m.put("description", r.getDescription());
        m.put("sortOrder", r.getSortOrder());
        m.put("updatedAt", r.getUpdatedAt() != null ? r.getUpdatedAt().format(DT) : null);
        m.put("updatedBy", r.getUpdatedBy());
        return m;
    }

    private static Map<String, Object> toMap(LlmGuardLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("eventId", l.getEventId());
        m.put("detectedAt", l.getDetectedAt() != null ? l.getDetectedAt().format(DT) : null);
        m.put("username", l.getUsername());
        m.put("clientIp", l.getClientIp());
        m.put("direction", l.getDirection());
        m.put("action", l.getAction());
        m.put("feature", l.getFeature());
        m.put("requestUri", l.getRequestUri());
        m.put("ruleId", l.getRuleId());
        m.put("ruleName", l.getRuleName());
        m.put("category", l.getCategory());
        m.put("matchCount", l.getMatchCount());
        m.put("sample", l.getSample());
        return m;
    }

    private static Map<String, Object> ok(Map<String, Object> rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("rule", rule);
        return m;
    }

    private static Map<String, Object> err(String code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("error", msg);
        return m;
    }

    private static ResponseEntity<Map<String, Object>> bad(String code, String msg) {
        return ResponseEntity.badRequest().body(err(code, msg));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

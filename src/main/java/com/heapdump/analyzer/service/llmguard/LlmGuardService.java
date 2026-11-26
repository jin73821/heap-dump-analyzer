package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.LlmGuardLog;
import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.util.LlmGuardContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 입·출력 민감정보 탐지(LLM Guard) — 설정·규칙 캐시·판정·기록 진입점 (2026-11-26).
 *
 * <p><b>입력</b>: {@link #checkInput} — 사용자가 직접 입력한 내용(마지막 user 역할 메시지·텍스트 첨부·첨부 파일명)만 본다.
 * 컨트롤러가 저장·RAG·게이트보다 <b>앞</b>에서 부른다(호출량 차감 없음). 판정·기록·앱 로그를 여기서 다 한다.
 *
 * <p><b>출력</b>: {@code LlmConfigService} 의 게이트 지점이 {@link #maskText}/{@link #maskInPlace}/{@link #newStreamMasker}
 * 로 가리고 {@link #recordOutput} 으로 기록한다. 컨트롤러는 출력 가림을 직접 하지 않는다(이중 마스킹 방지).
 *
 * <p>⚠ 원문은 어디에도 저장하지 않는다 — 기록에는 가린 발췌만 들어간다.
 * <p>⚠ 보안 통제라 의존성을 {@code required=false} 로 두지 않는다. 규칙을 DB 에서 못 읽으면 내장 기본 규칙으로 동작한다.
 */
@Service
public class LlmGuardService {

    private static final Logger logger = LoggerFactory.getLogger(LlmGuardService.class);

    /** 출력 금지 지시문의 표지 — 중복 부착 방지에도 쓴다. */
    static final String INSTRUCTION_MARK = "[개인정보 보호 지침]";
    static final String OUTPUT_INSTRUCTION = "\n\n" + INSTRUCTION_MARK
            + " 응답에 주민등록번호·외국인등록번호·카드번호·계좌번호·휴대전화번호·이메일·여권번호·운전면허번호·"
            + "비밀번호/키 값 등 특정 개인을 식별하거나 유추할 수 있는 정보를 그대로 쓰지 마세요. "
            + "꼭 언급해야 하면 일부를 *** 로 가려서 쓰세요.";

    /** 결과 맵에서 가리지 않는 키 — 파일명·모델명 같은 식별자(가리면 화면의 참조가 깨진다). */
    private static final Set<String> NON_TEXT_KEYS = new HashSet<>(java.util.Arrays.asList(
            "model", "filename", "compareBase", "compareTarget", "severity", "analysedAt", "errorCode"));

    private final HeapDumpConfig config;
    private final LlmGuardRuleRepository ruleRepo;
    private final LlmGuardLogService logService;

    // ── 런타임 설정 (함정 40 — 5곳 동기화: HeapDumpConfig @Value · init · 3-hook) ──
    private volatile boolean inputCheckEnabled = true;
    private volatile boolean outputMaskEnabled = true;
    private volatile boolean outputInstructionEnabled = true;

    /** 규칙 캐시 — CRUD 뒤·시더 끝에 {@link #reload()} 가 통째로 교체한다. */
    private final AtomicReference<List<CompiledRule>> rules = new AtomicReference<>(Collections.emptyList());
    private final AckToken ack = new AckToken();

    public LlmGuardService(HeapDumpConfig config, LlmGuardRuleRepository ruleRepo, LlmGuardLogService logService) {
        this.config = config;
        this.ruleRepo = ruleRepo;
        this.logService = logService;
    }

    @PostConstruct
    public void init() {
        if (config != null) {
            this.inputCheckEnabled = config.isLlmGuardInputCheckEnabled();
            this.outputMaskEnabled = config.isLlmGuardOutputMaskEnabled();
            this.outputInstructionEnabled = config.isLlmGuardOutputInstructionEnabled();
        }
        reload();
    }

    // ── 규칙 캐시 ──────────────────────────────────────────────────

    /** DB 규칙을 다시 컴파일한다. DB 를 못 읽거나 비어 있으면(시드 전) 내장 기본 규칙으로 동작한다. */
    public void reload() {
        List<LlmGuardRule> src = null;
        if (ruleRepo != null) {
            try {
                src = ruleRepo.findAllByOrderBySortOrderAscIdAsc();
            } catch (RuntimeException e) {
                logger.warn("[LlmGuard] 규칙 조회 실패 — 내장 기본 규칙으로 동작: {}", e.toString());
            }
        }
        boolean fallback = src == null || src.isEmpty();
        if (fallback) {
            src = new ArrayList<>();
            for (BuiltinDetectors.Def d : BuiltinDetectors.DEFAULTS) src.add(d.toEntity());
        }
        List<CompiledRule> compiled = new ArrayList<>();
        for (LlmGuardRule r : src) {
            try {
                compiled.add(CompiledRule.compile(r));
            } catch (IllegalArgumentException e) {
                logger.warn("[LlmGuard] 규칙 컴파일 실패 id={} name={} — 건너뜀: {}", r.getId(), r.getName(), e.getMessage());
            }
        }
        rules.set(Collections.unmodifiableList(compiled));
        logger.info("[LlmGuard] 규칙 {}건 적재{}", compiled.size(), fallback ? " (내장 기본값)" : "");
    }

    List<CompiledRule> rules() { return rules.get(); }

    // ── 설정 ──────────────────────────────────────────────────────

    public boolean isInputCheckEnabled()        { return inputCheckEnabled; }
    public boolean isOutputMaskEnabled()        { return outputMaskEnabled; }
    public boolean isOutputInstructionEnabled() { return outputInstructionEnabled; }

    public void setSettings(boolean inputCheck, boolean outputMask, boolean outputInstruction) {
        this.inputCheckEnabled = inputCheck;
        this.outputMaskEnabled = outputMask;
        this.outputInstructionEnabled = outputInstruction;
    }

    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("llmGuardInputCheckEnabled")) {
            this.inputCheckEnabled = Boolean.parseBoolean(String.valueOf(saved.get("llmGuardInputCheckEnabled")));
        }
        if (saved.containsKey("llmGuardOutputMaskEnabled")) {
            this.outputMaskEnabled = Boolean.parseBoolean(String.valueOf(saved.get("llmGuardOutputMaskEnabled")));
        }
        if (saved.containsKey("llmGuardOutputInstructionEnabled")) {
            this.outputInstructionEnabled = Boolean.parseBoolean(String.valueOf(saved.get("llmGuardOutputInstructionEnabled")));
        }
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("llmGuardInputCheckEnabled", inputCheckEnabled);
        settings.put("llmGuardOutputMaskEnabled", outputMaskEnabled);
        settings.put("llmGuardOutputInstructionEnabled", outputInstructionEnabled);
    }

    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("llm.guard.input-check.enabled", String.valueOf(inputCheckEnabled));
        updates.put("llm.guard.output-mask.enabled", String.valueOf(outputMaskEnabled));
        updates.put("llm.guard.output-instruction.enabled", String.valueOf(outputInstructionEnabled));
    }

    // ── 입력 점검 ──────────────────────────────────────────────────

    /**
     * 채팅 입력 점검. 대상은 <b>마지막 user 역할 메시지</b>(LLM 쪽 {@code appendTextToLastUserMessage} 와 같은 인덱스 규칙),
     * 첨부 파일명, 텍스트 첨부 본문이다. 이미지는 점검하지 않는다. 이력으로 다시 보내는 앞선 user 메시지는 다시 보지 않는다
     * (최초 전송 때 점검했고, 다시 보면 매 턴 확인 창이 뜬다).
     */
    public InputVerdict checkInput(String user, List<Map<String, String>> messages,
                                   List<Map<String, Object>> attachments, String ackToken) {
        if (!inputCheckEnabled) return InputVerdict.allow();
        List<String> segments = new ArrayList<>();
        int target = lastUserIndex(messages);
        if (target >= 0) {
            String c = messages.get(target).get("content");
            if (c != null && !c.isEmpty()) segments.add(c);
        }
        boolean imagesSkipped = false;
        if (attachments != null) {
            for (Map<String, Object> att : attachments) {
                if (att == null) continue;
                Object name = att.get("name");
                if (name != null && !String.valueOf(name).isEmpty()) segments.add(String.valueOf(name));
                Object mt = att.get("mediaType");
                if (mt != null && String.valueOf(mt).startsWith("image/")) {
                    imagesSkipped = true;
                    continue;
                }
                Object data = att.get("data");
                String decoded = data instanceof String
                        ? com.heapdump.analyzer.service.LlmConfigService.decodeAttachmentText((String) data) : null;
                if (decoded != null && !decoded.isEmpty()) segments.add(decoded);
            }
        }
        return evaluate(user, segments, imagesSkipped, ackToken);
    }

    /** 단일 텍스트 점검 — 메시지 저장 우회 경로({@code role=user}) 등. */
    public InputVerdict checkText(String user, String text, String ackToken) {
        if (!inputCheckEnabled) return InputVerdict.allow();
        List<String> segments = new ArrayList<>();
        if (text != null && !text.isEmpty()) segments.add(text);
        return evaluate(user, segments, false, ackToken);
    }

    /** LLM 쪽과 같은 규칙 — 마지막 user, 없으면 마지막 메시지. */
    static int lastUserIndex(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) return -1;
        int target = -1;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> m = messages.get(i);
            if (m != null && "user".equals(m.get("role"))) target = i;
        }
        return target >= 0 ? target : messages.size() - 1;
    }

    private InputVerdict evaluate(String user, List<String> segments, boolean imagesSkipped, String ackToken) {
        if (segments.isEmpty()) return InputVerdict.allow();
        List<CompiledRule> snap = rules.get();
        Map<CompiledRule, InputVerdict.Finding> found = new LinkedHashMap<>();
        for (String seg : segments) {
            GuardEngine.Scan s = GuardEngine.scan(snap, GuardEngine.Mode.INPUT, seg, 0, true, null);
            for (CompiledRule r : s.timedOut) {
                logger.warn("[LlmGuard] action=rule-timeout id={} direction=INPUT — 확인 후 전송으로 취급(fail-safe)", r.id);
                found.computeIfAbsent(r, InputVerdict.Finding::new).timeout = true;
            }
            GuardEngine.Masked sampleMask = null;
            for (Hit h : s.raw) {
                if (!h.changes(seg)) continue;
                InputVerdict.Finding f = found.computeIfAbsent(h.rule, InputVerdict.Finding::new);
                f.count++;
                if (f.sample == null) {
                    try {
                        if (sampleMask == null) sampleMask = GuardEngine.maskAll(snap, GuardEngine.Mode.SAMPLE, seg, null);
                        f.sample = GuardEngine.excerpt(snap, sampleMask.text,
                                sampleMask.map(h.start, false), sampleMask.map(h.end, true));
                    } catch (RuntimeException e) {
                        logger.warn("[LlmGuard] 입력 발췌 생성 실패 rule={} — 발췌 없이 진행: {}", h.rule.code(), e.toString());
                    }
                }
            }
        }
        if (found.isEmpty()) return InputVerdict.allow();

        List<InputVerdict.Finding> findings = new ArrayList<>(found.values());
        boolean block = false;
        for (InputVerdict.Finding f : findings) {
            if (f.rule.block && f.count > 0) { block = true; break; }
        }
        String joined = String.join("\u0000", segments);
        InputVerdict v;
        String act;
        if (block) {
            v = new InputVerdict(InputVerdict.Kind.BLOCK, findings, null, imagesSkipped);
            act = LlmGuardLog.ACT_BLOCKED;
        } else if (ackToken != null && ack.verify(ackToken, user, joined)) {
            v = new InputVerdict(InputVerdict.Kind.CONFIRMED, findings, null, imagesSkipped);
            act = LlmGuardLog.ACT_CONFIRMED;
        } else {
            v = new InputVerdict(InputVerdict.Kind.CONFIRM, findings, ack.issue(user, joined), imagesSkipped);
            act = LlmGuardLog.ACT_CONFIRM_REQUESTED;
        }
        recordInput(user, act, findings);
        return v;
    }

    private void recordInput(String user, String action, List<InputVerdict.Finding> findings) {
        LlmGuardContext.Snapshot ctx = LlmGuardContext.capture();
        StringBuilder sum = new StringBuilder();
        for (InputVerdict.Finding f : findings) {
            if (sum.length() > 0) sum.append(',');
            sum.append(f.rule.code()).append('×').append(f.timeout && f.count == 0 ? "timeout" : String.valueOf(f.count));
        }
        String verb = LlmGuardLog.ACT_BLOCKED.equals(action) ? "input-block"
                : LlmGuardLog.ACT_CONFIRMED.equals(action) ? "input-confirmed" : "input-confirm";
        logger.info("[LlmGuard] action={} user={} feature={} rules={}", verb, user, ctx.feature(), sum);
        if (logService == null) return;
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        List<LlmGuardLog> rows = new ArrayList<>();
        for (InputVerdict.Finding f : findings) {
            rows.add(row(eventId, now, user, ctx, LlmGuardLog.DIR_INPUT, action, f.rule, f.count,
                    f.timeout && f.sample == null ? "(정규식 시간 초과 — 확인 후 전송으로 취급)" : f.sample));
        }
        logService.write(rows);
    }

    private static LlmGuardLog row(String eventId, LocalDateTime now, String user, LlmGuardContext.Snapshot ctx,
                                   String direction, String action, CompiledRule r, int count, String sample) {
        LlmGuardLog l = new LlmGuardLog();
        l.setEventId(eventId);
        l.setDetectedAt(now);
        l.setUsername(cut(user, 50));
        l.setClientIp(ctx.clientIp());
        l.setDirection(direction);
        l.setAction(action);
        l.setFeature(cut(ctx.feature(), 30));
        l.setRequestUri(ctx.requestUri());
        l.setRuleId(r.id);
        l.setRuleName(cut(r.name, 100));
        l.setCategory(cut(r.category, 20));
        l.setMatchCount(count);
        l.setSample(cut(sample, 500));
        return l;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ── 출력 가림 ──────────────────────────────────────────────────

    /** 시스템 프롬프트에 개인 식별 정보 출력 금지 지시를 덧붙인다(토글). 이미 붙어 있으면 그대로. */
    public String decorateSystemPrompt(String systemPrompt) {
        if (!outputInstructionEnabled || systemPrompt == null) return systemPrompt;
        if (systemPrompt.contains(INSTRUCTION_MARK)) return systemPrompt;
        return systemPrompt + OUTPUT_INSTRUCTION;
    }

    /** 문자열 1개를 가린다(기록 없음). 출력 가림이 꺼져 있으면 원문 그대로·건수 0. */
    public MaskResult maskText(String s) {
        MaskResult r = new MaskResult();
        if (s == null || s.isEmpty() || !outputMaskEnabled) {
            r.setText(s);
            return r;
        }
        List<CompiledRule> snap = rules.get();
        Set<CompiledRule> timedOut = new HashSet<>();
        GuardEngine.Masked m = GuardEngine.maskAll(snap, GuardEngine.Mode.OUTPUT, s, timedOut);
        for (CompiledRule t : timedOut) {
            logger.warn("[LlmGuard] action=rule-timeout id={} direction=OUTPUT — 건너뜀", t.id);
            r.addTimedOut(t);
        }
        Map<CompiledRule, Boolean> sampled = new LinkedHashMap<>();
        for (Hit h : m.kept) {
            String sample = null;
            if (!sampled.containsKey(h.rule)) {
                sampled.put(h.rule, true);
                try {
                    sample = GuardEngine.excerpt(snap, m.text, m.map(h.start, false), m.map(h.end, true));
                } catch (RuntimeException e) {
                    logger.warn("[LlmGuard] 출력 발췌 생성 실패 rule={}: {}", h.rule.code(), e.toString());
                }
            }
            r.add(h.rule, 1, sample);
        }
        r.setText(m.text);
        return r;
    }

    /** Map/List 안의 문자열을 재귀로 가린다(제자리 변경, 기록 없음). 식별자 키는 건너뛴다. */
    @SuppressWarnings("unchecked")
    public MaskResult maskInPlace(Object root) {
        MaskResult acc = new MaskResult();
        if (!outputMaskEnabled || root == null) return acc;
        if (root instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root).entrySet()) {
                if (NON_TEXT_KEYS.contains(e.getKey())) continue;
                Object v = e.getValue();
                if (v instanceof String) {
                    MaskResult r = maskText((String) v);
                    if (!r.isEmpty()) {
                        e.setValue(r.text());
                        acc.merge(r);
                    }
                } else if (v instanceof Map || v instanceof List) {
                    acc.merge(maskInPlace(v));
                }
            }
        } else if (root instanceof List) {
            ListIterator<Object> it = ((List<Object>) root).listIterator();
            while (it.hasNext()) {
                Object v = it.next();
                if (v instanceof String) {
                    MaskResult r = maskText((String) v);
                    if (!r.isEmpty()) {
                        it.set(r.text());
                        acc.merge(r);
                    }
                } else if (v instanceof Map || v instanceof List) {
                    acc.merge(maskInPlace(v));
                }
            }
        }
        return acc;
    }

    /** 읽기 시점 가림(배포 이전 저장분) — 기록 없음, 데이터는 바꾸지 않는다(호출자가 복사본에 쓴다). */
    public String maskForDisplay(String s) {
        return maskText(s).text();
    }

    /** 스트림 하나 전용 마스커. 출력 가림이 꺼져 있으면 그대로 통과시킨다. */
    public StreamingMasker newStreamMasker() {
        return new StreamingMasker(rules.get(), outputMaskEnabled);
    }

    /** 출력 가림 기록(건수 0 이면 아무것도 안 한다). 실패해도 던지지 않는다. */
    public void recordOutput(String user, MaskResult r) {
        if (r == null || r.isEmpty()) return;
        try {
            LlmGuardContext.Snapshot ctx = LlmGuardContext.capture();
            logger.info("[LlmGuard] action=output-mask user={} feature={} rules={}", user, ctx.feature(), r.rulesSummary());
            if (logService == null) return;
            String eventId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            List<LlmGuardLog> rows = new ArrayList<>();
            r.counts().forEach((rule, n) -> rows.add(row(eventId, now, user, ctx, LlmGuardLog.DIR_OUTPUT,
                    LlmGuardLog.ACT_MASKED, rule, n, r.samples().get(rule))));
            logService.write(rows);
        } catch (RuntimeException e) {
            logger.warn("[LlmGuard] 출력 가림 기록 실패 — 흐름은 계속: {}", e.toString());
        }
    }

    // ── 관리자: 규칙 시험 ──────────────────────────────────────────

    public static final int PREVIEW_MAX_CHARS = 20_000;

    /**
     * 입력 판정 + 출력 가림 미리보기. {@code draft} 가 있으면 그 규칙 하나만으로 시험한다(저장 전 초안).
     * 기록하지 않으며 토글과 무관하게 동작한다.
     */
    public Map<String, Object> preview(String text, LlmGuardRule draft) {
        String t = text == null ? "" : text;
        if (t.length() > PREVIEW_MAX_CHARS) t = t.substring(0, PREVIEW_MAX_CHARS);
        List<CompiledRule> snap;
        if (draft != null) {
            draft.setEnabled(true);
            snap = Collections.singletonList(CompiledRule.compile(draft));
        } else {
            snap = rules.get();
        }
        Map<String, Object> out = new LinkedHashMap<>();

        GuardEngine.Scan s = GuardEngine.scan(snap, GuardEngine.Mode.INPUT, t, 0, true, null);
        Map<CompiledRule, int[]> counts = new LinkedHashMap<>();
        for (Hit h : s.raw) {
            if (h.changes(t)) counts.computeIfAbsent(h.rule, k -> new int[1])[0]++;
        }
        boolean block = false;
        List<Map<String, Object>> inItems = new ArrayList<>();
        for (Map.Entry<CompiledRule, int[]> e : counts.entrySet()) {
            CompiledRule r = e.getKey();
            if (r.block) block = true;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("rule", r.name);
            it.put("category", r.category);
            it.put("count", e.getValue()[0]);
            it.put("action", r.block ? "BLOCK" : "CONFIRM");
            inItems.add(it);
        }
        for (CompiledRule r : s.timedOut) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("rule", r.name);
            it.put("category", r.category);
            it.put("count", 0);
            it.put("action", "CONFIRM");
            it.put("timeout", true);
            inItems.add(it);
        }
        out.put("inputVerdict", block ? "BLOCK" : inItems.isEmpty() ? "ALLOW" : "CONFIRM");
        out.put("inputFindings", inItems);

        Set<CompiledRule> outTimedOut = new HashSet<>();
        GuardEngine.Masked m = GuardEngine.maskAll(snap, GuardEngine.Mode.OUTPUT, t, outTimedOut);
        Map<CompiledRule, int[]> outCounts = new LinkedHashMap<>();
        for (Hit h : m.kept) outCounts.computeIfAbsent(h.rule, k -> new int[1])[0]++;
        List<Map<String, Object>> outItems = new ArrayList<>();
        outCounts.forEach((r, n) -> {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("rule", r.name);
            it.put("count", n[0]);
            outItems.add(it);
        });
        out.put("maskedText", m.text);
        out.put("outputFindings", outItems);
        List<String> to = new ArrayList<>();
        for (CompiledRule r : outTimedOut) to.add(r.name);
        if (!s.timedOut.isEmpty() || !to.isEmpty()) out.put("timedOutRules", to.isEmpty() ? names(s.timedOut) : to);
        out.put("truncated", text != null && text.length() > PREVIEW_MAX_CHARS);
        return out;
    }

    private static List<String> names(Set<CompiledRule> rs) {
        List<String> l = new ArrayList<>();
        for (CompiledRule r : rs) l.add(r.name);
        return l;
    }
}

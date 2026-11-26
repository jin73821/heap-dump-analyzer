package com.heapdump.analyzer.service.llmguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 입력 점검 판정 — {@code ALLOW | CONFIRM | BLOCK | CONFIRMED} 와 규칙별 탐지 내역.
 * BLOCK 이 CONFIRM 보다 우선한다(토큰이 있어도 차단).
 */
public final class InputVerdict {

    public enum Kind { ALLOW, CONFIRM, BLOCK, CONFIRMED }

    public static final String CODE_CONFIRM = "SENSITIVE_INPUT_CONFIRM";
    public static final String CODE_BLOCKED = "SENSITIVE_INPUT_BLOCKED";

    /** 규칙 1개의 탐지 내역. sample 은 가린 발췌(원문 아님). */
    public static final class Finding {
        final CompiledRule rule;
        int count;
        String sample;
        boolean timeout;

        Finding(CompiledRule rule) { this.rule = rule; }

        public CompiledRule rule() { return rule; }
        public int count()         { return count; }
        public String sample()     { return sample; }
        public boolean timeout()   { return timeout; }

        /** 이 탐지가 요구하는 조치 — 시간 초과는 CONFIRM(fail-safe). */
        public String action() { return rule.block && count > 0 ? "BLOCK" : "CONFIRM"; }
    }

    private static final InputVerdict ALLOW = new InputVerdict(Kind.ALLOW, Collections.emptyList(), null, false);

    private final Kind kind;
    private final List<Finding> findings;
    private final String ackToken;
    private final boolean imagesSkipped;

    InputVerdict(Kind kind, List<Finding> findings, String ackToken, boolean imagesSkipped) {
        this.kind = kind;
        this.findings = findings;
        this.ackToken = ackToken;
        this.imagesSkipped = imagesSkipped;
    }

    public static InputVerdict allow() { return ALLOW; }

    public Kind kind()               { return kind; }
    public List<Finding> findings()  { return findings; }
    public String ackToken()         { return ackToken; }
    public boolean imagesSkipped()   { return imagesSkipped; }

    /** 전송해도 되는가 — ALLOW 또는 확인 완료(CONFIRMED). */
    public boolean passes() { return kind == Kind.ALLOW || kind == Kind.CONFIRMED; }

    public String errorCode() {
        if (kind == Kind.BLOCK) return CODE_BLOCKED;
        if (kind == Kind.CONFIRM) return CODE_CONFIRM;
        return null;
    }

    public String message() {
        if (kind == Kind.BLOCK) return "개인신용정보·중요정보가 포함되어 전송하지 않았습니다. 해당 내용을 지우고 다시 보내 주세요.";
        if (kind == Kind.CONFIRM) return "입력에 개인신용정보·중요정보로 보이는 내용이 있습니다. 확인 후 전송하세요.";
        return null;
    }

    /** SSE/JSON 응답의 {@code guard} 객체 — 규칙명이 관리자 입력이라 반드시 Jackson 으로 직렬화할 것. */
    public Map<String, Object> guardPayload() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("verdict", kind.name());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("rule", f.rule.name);
            it.put("category", f.rule.category);
            it.put("count", f.count);
            it.put("action", f.action());
            if (f.sample != null) it.put("sample", f.sample);
            if (f.timeout) it.put("timeout", true);
            items.add(it);
        }
        g.put("findings", items);
        if (ackToken != null) g.put("ackToken", ackToken);
        g.put("imagesSkipped", imagesSkipped);
        if (imagesSkipped) g.put("imageNote", "이미지 첨부는 내용 점검 대상이 아닙니다. 이미지에 개인정보가 없는지 직접 확인하세요.");
        return g;
    }

    /** JSON 경로 응답 본문 — {@code {success:false, errorCode, error, guard}} (200 유지 규약). */
    public Map<String, Object> toJsonError() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("errorCode", errorCode());
        m.put("error", message());
        m.put("guard", guardPayload());
        return m;
    }
}

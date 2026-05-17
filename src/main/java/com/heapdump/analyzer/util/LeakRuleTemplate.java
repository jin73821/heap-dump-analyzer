package com.heapdump.analyzer.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Leak Suspect 룰 템플릿 엔진. 지원 문법:
 *
 *   {var}              — LeakRuleContext.resolve("var")의 toString. null/0/빈문자는 "—" 로 치환 안함, 그대로 출력 회피.
 *   {var|filter}       — 필터 적용. 지원 필터: bytes (formatBytes), instances (formatInstanceCount),
 *                                            number (콤마 천단위), percent (소수점 1자리)
 *   {#if flag}A{/if}                — flag가 truthy면 A 출력
 *   {#if flag}A{#else}B{/if}        — flag가 truthy면 A, 아니면 B (#else 키워드)
 *
 * 중첩 if는 지원하지 않음(현재 룰 분석상 불필요). 알 수 없는 placeholder는 빈 문자열로 치환.
 */
public final class LeakRuleTemplate {

    private LeakRuleTemplate() {}

    public static String render(String template, LeakRuleContext ctx) {
        if (template == null || template.isEmpty()) return "";
        if (ctx == null) ctx = new LeakRuleContext();
        List<Token> tokens = tokenize(template);
        StringBuilder out = new StringBuilder(template.length() + 64);
        renderTokens(tokens, 0, tokens.size(), ctx, out);
        return out.toString();
    }

    // ─── 토큰화 ──────────────────────────────────────────────────────────
    private static List<Token> tokenize(String tpl) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = tpl.length();
        StringBuilder lit = new StringBuilder();
        while (i < len) {
            char c = tpl.charAt(i);
            if (c == '{') {
                int end = tpl.indexOf('}', i + 1);
                if (end < 0) { lit.append(c); i++; continue; }
                String inner = tpl.substring(i + 1, end);
                // flush literal
                if (lit.length() > 0) { tokens.add(Token.lit(lit.toString())); lit.setLength(0); }
                if (inner.startsWith("#if ")) {
                    tokens.add(Token.ifStart(inner.substring(4).trim()));
                } else if (inner.equals("#else")) {
                    tokens.add(Token.elseTag());
                } else if (inner.equals("/if")) {
                    tokens.add(Token.endIf());
                } else {
                    int pipe = inner.indexOf('|');
                    if (pipe > 0) {
                        tokens.add(Token.var(inner.substring(0, pipe).trim(), inner.substring(pipe + 1).trim()));
                    } else {
                        tokens.add(Token.var(inner.trim(), null));
                    }
                }
                i = end + 1;
            } else {
                lit.append(c);
                i++;
            }
        }
        if (lit.length() > 0) tokens.add(Token.lit(lit.toString()));
        return tokens;
    }

    // ─── 렌더 (start..end 범위, 비중첩) ─────────────────────────────────
    private static int renderTokens(List<Token> tokens, int start, int end, LeakRuleContext ctx, StringBuilder out) {
        int i = start;
        while (i < end) {
            Token t = tokens.get(i);
            switch (t.kind) {
                case LITERAL:
                    out.append(t.value);
                    i++;
                    break;
                case VAR:
                    out.append(formatValue(ctx.resolve(t.value), t.filter));
                    i++;
                    break;
                case IF_START: {
                    boolean cond = ctx.truthy(t.value);
                    int elseIdx = findElseOrEndIf(tokens, i + 1, end, true);
                    int endIdx = (elseIdx >= 0 && tokens.get(elseIdx).kind == TokenKind.ELSE)
                            ? findElseOrEndIf(tokens, elseIdx + 1, end, false) : elseIdx;
                    if (endIdx < 0) {
                        // 잘못된 템플릿: 무시
                        i++;
                        break;
                    }
                    if (cond) {
                        int trueEnd = (tokens.get(elseIdx).kind == TokenKind.ELSE) ? elseIdx : endIdx;
                        renderTokens(tokens, i + 1, trueEnd, ctx, out);
                    } else if (tokens.get(elseIdx).kind == TokenKind.ELSE) {
                        renderTokens(tokens, elseIdx + 1, endIdx, ctx, out);
                    }
                    i = endIdx + 1;
                    break;
                }
                case ELSE:
                case END_IF:
                    return i; // 호출자가 범위 통제
                default:
                    i++;
            }
        }
        return i;
    }

    /** start..end 범위에서 같은 깊이의 #else 또는 /if 위치를 반환. ELSE 포함 여부는 acceptElse. */
    private static int findElseOrEndIf(List<Token> tokens, int start, int end, boolean acceptElse) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            TokenKind k = tokens.get(i).kind;
            if (k == TokenKind.IF_START) depth++;
            else if (k == TokenKind.END_IF) {
                if (depth == 0) return i;
                depth--;
            } else if (k == TokenKind.ELSE && depth == 0 && acceptElse) {
                return i;
            }
        }
        return -1;
    }

    // ─── 값 포맷 ─────────────────────────────────────────────────────────
    private static String formatValue(Object v, String filter) {
        if (v == null) return "";
        if (filter == null || filter.isEmpty()) {
            // 원본 람다는 String concat 시 Double.toString을 사용(예: 24.5). 동일 동작 유지.
            return String.valueOf(v);
        }
        switch (filter) {
            case "bytes": {
                long b = (v instanceof Number) ? ((Number) v).longValue() : 0L;
                return formatBytes(b);
            }
            case "instances": {
                int c = (v instanceof Number) ? ((Number) v).intValue() : 0;
                return formatInstanceCount(c);
            }
            case "number": {
                long n = (v instanceof Number) ? ((Number) v).longValue() : 0L;
                return String.format("%,d", n);
            }
            case "percent": {
                double d = (v instanceof Number) ? ((Number) v).doubleValue() : 0.0;
                return String.format("%.1f", d);
            }
            default:
                return String.valueOf(v);
        }
    }

    static String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("약 %.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("약 %.1fMB", bytes / (1024.0 * 1024));
        return String.format("약 %.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    static String formatInstanceCount(int count) {
        if (count <= 0) return "";
        if (count == 1) return "1개 인스턴스";
        return String.format("%,d개 인스턴스", count);
    }

    // ─── 토큰 ────────────────────────────────────────────────────────────
    private enum TokenKind { LITERAL, VAR, IF_START, ELSE, END_IF }
    private static final class Token {
        final TokenKind kind;
        final String value;   // LITERAL: 내용, VAR: 이름, IF_START: 조건
        final String filter;  // VAR 전용
        private Token(TokenKind k, String v, String f) { this.kind = k; this.value = v; this.filter = f; }
        static Token lit(String s)               { return new Token(TokenKind.LITERAL, s, null); }
        static Token var(String name, String fl) { return new Token(TokenKind.VAR, name, fl); }
        static Token ifStart(String cond)        { return new Token(TokenKind.IF_START, cond, null); }
        static Token elseTag()                   { return new Token(TokenKind.ELSE, null, null); }
        static Token endIf()                     { return new Token(TokenKind.END_IF, null, null); }
    }
}

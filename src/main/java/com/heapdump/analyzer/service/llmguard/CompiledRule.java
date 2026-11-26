package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.util.DeadlineCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 판정용으로 컴파일한 규칙(불변). DB 엔티티와 분리해 규칙 캐시를 {@code AtomicReference} 로 통째로 교체한다.
 *
 * <ul>
 *   <li>{@code REGEX} — named group {@code v} 가 있으면 그 그룹만 가린다. 마감 {@value #USER_REGEX_BUDGET_MS}ms 를 넘기면
 *       {@link DeadlineCharSequence.Timeout} 을 던진다(입력은 CONFIRM 취급, 출력은 건너뜀 — 호출자가 처리).</li>
 *   <li>{@code KEYWORD} — 줄마다 단어 하나, 대소문자 무시 리터럴. 영문·숫자로 시작/끝나는 단어는 영숫자 경계를 요구한다
 *       (식별자 안의 일치를 피하려고). 한글은 조사가 붙으므로 경계를 두지 않는다.</li>
 *   <li>{@code BUILTIN} — {@link BuiltinDetectors} 의 Java 검증기.</li>
 * </ul>
 */
public final class CompiledRule {

    private static final Logger logger = LoggerFactory.getLogger(CompiledRule.class);

    public static final long USER_REGEX_BUDGET_MS = 200;
    public static final int MAX_REGEX_LENGTH = 500;
    public static final int MAX_KEYWORDS = 200;
    public static final int MAX_KEYWORD_LENGTH = 100;

    final Long id;
    final String name;
    final String category;
    final String matchType;
    final String builtinKey;
    final boolean enabled;
    final boolean checkInput;
    final boolean block;
    final boolean checkOutput;
    final int sortOrder;

    private final Pattern pattern;
    private final boolean hasGroupV;
    private final BuiltinDetectors.Detector detector;

    private CompiledRule(LlmGuardRule r, Pattern pattern, boolean hasGroupV, BuiltinDetectors.Detector detector) {
        this.id = r.getId();
        this.name = r.getName() != null ? r.getName() : "(이름 없음)";
        this.category = r.getCategory();
        this.matchType = r.getMatchType();
        this.builtinKey = r.getBuiltinKey();
        this.enabled = r.isEnabled();
        this.checkInput = r.isCheckInput();
        this.block = LlmGuardRule.ACTION_BLOCK.equals(r.getInputAction());
        this.checkOutput = r.isCheckOutput();
        this.sortOrder = r.getSortOrder();
        this.pattern = pattern;
        this.hasGroupV = hasGroupV;
        this.detector = detector;
    }

    /** 엔티티 → 판정 규칙. 형식 오류면 {@link IllegalArgumentException}(한국어 문구). */
    public static CompiledRule compile(LlmGuardRule r) {
        String type = r.getMatchType();
        if (LlmGuardRule.TYPE_BUILTIN.equals(type)) {
            BuiltinDetectors.Detector d = BuiltinDetectors.detectorFor(r.getBuiltinKey());
            if (d == null) throw new IllegalArgumentException("알 수 없는 내장 규칙입니다: " + r.getBuiltinKey());
            return new CompiledRule(r, null, false, d);
        }
        if (LlmGuardRule.TYPE_REGEX.equals(type)) {
            Pattern p = compileRegex(r.getPattern());
            return new CompiledRule(r, p, p.pattern().contains("(?<v>"), null);
        }
        if (LlmGuardRule.TYPE_KEYWORD.equals(type)) {
            return new CompiledRule(r, compileKeywords(r.getPattern()), false, null);
        }
        throw new IllegalArgumentException("규칙 유형은 REGEX 또는 KEYWORD 여야 합니다.");
    }

    static Pattern compileRegex(String src) {
        if (src == null || src.isEmpty()) throw new IllegalArgumentException("정규식이 비어 있습니다.");
        if (src.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException("정규식은 " + MAX_REGEX_LENGTH + "자 이하여야 합니다.");
        }
        try {
            return Pattern.compile(src);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("정규식 오류: " + e.getDescription() + " (위치 " + e.getIndex() + ")");
        }
    }

    /** 단어 목록(줄 단위) 정규화 — 앞뒤 공백 제거·빈 줄·중복 제거. */
    public static List<String> parseKeywords(String src) {
        Set<String> words = new LinkedHashSet<>();
        if (src != null) {
            for (String line : src.split("\\r?\\n")) {
                String w = line.trim();
                if (!w.isEmpty()) words.add(w);
            }
        }
        return new ArrayList<>(words);
    }

    static Pattern compileKeywords(String src) {
        List<String> words = parseKeywords(src);
        if (words.isEmpty()) throw new IllegalArgumentException("단어를 한 줄에 하나씩 1개 이상 입력하세요.");
        if (words.size() > MAX_KEYWORDS) throw new IllegalArgumentException("단어는 " + MAX_KEYWORDS + "개까지 등록할 수 있습니다.");
        for (String w : words) {
            if (w.length() > MAX_KEYWORD_LENGTH) {
                throw new IllegalArgumentException("단어는 " + MAX_KEYWORD_LENGTH + "자 이하여야 합니다: " + w.substring(0, 20) + "…");
            }
        }
        // 같은 위치에서 긴 단어가 이기도록(신용카드번호 > 카드번호)
        words.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append('|');
            if (isAsciiAlnum(w.charAt(0))) sb.append("(?<![A-Za-z0-9])");
            sb.append(Pattern.quote(w));
            if (isAsciiAlnum(w.charAt(w.length() - 1))) sb.append("(?![A-Za-z0-9])");
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    // ── 판정 ──────────────────────────────────────────────────────

    /**
     * {@code [from,to)} 를 투명 경계로 훑어 일치를 {@code out} 에 더한다. 미완결 일치(PEM 시작 표지만 있음)의
     * 시작 위치를 돌려주고, 없으면 -1.
     *
     * @throws DeadlineCharSequence.Timeout 사용자 정규식이 마감을 넘김
     */
    int scan(CharSequence text, int from, int to, boolean atEnd, List<Hit> out) {
        if (detector != null) return detector.scan(this, text, from, to, atEnd, out);
        CharSequence src = isUserRegex() ? DeadlineCharSequence.of(text, USER_REGEX_BUDGET_MS) : text;
        Matcher m = pattern.matcher(src);
        m.region(from, to);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        while (m.find()) {
            if (m.end() <= m.start()) continue;
            int rs = m.start();
            int re = m.end();
            if (hasGroupV) {
                try {
                    int vs = m.start("v");
                    if (vs >= 0) { rs = vs; re = m.end("v"); }
                } catch (IllegalArgumentException e) {
                    // "(?<v>" 가 문자 클래스·이스케이프 안에 있던 경우 — 그룹이 없으니 전체를 가린다
                    logger.debug("[LlmGuard] 규칙 {} 에 그룹 v 없음 — 전체 일치를 가림", code());
                }
            }
            if (re <= rs) {
                out.add(new Hit(this, m.start(), m.end(), rs, rs, null));
                continue;
            }
            String value = text.subSequence(rs, re).toString();
            String rep = BuiltinDetectors.genericMask(value);
            out.add(new Hit(this, m.start(), m.end(), rs, re, rep));
        }
        return -1;
    }

    public boolean isUserRegex()       { return LlmGuardRule.TYPE_REGEX.equals(matchType); }
    public boolean isBuiltinKeyword()  { return LlmGuardRule.TYPE_KEYWORD.equals(matchType) && builtinKey != null; }
    public Long getId()                { return id; }
    public String getName()            { return name; }
    public String getCategory()        { return category; }
    public boolean isBlock()           { return block; }

    /** 앱 로그용 짧은 이름 — 내장은 키, 사용자 규칙은 R{id}. */
    public String code() {
        if (builtinKey != null) return builtinKey;
        return id != null ? "R" + id : "DRAFT";
    }
}

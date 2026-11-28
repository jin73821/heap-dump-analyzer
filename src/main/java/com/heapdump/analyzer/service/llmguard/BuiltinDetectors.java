package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.model.entity.LlmGuardRule;

import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내장 탐지 규칙의 Java 검증기·마스커와 기본 규칙 정의 (LLM Guard, 2026-11-26).
 *
 * <p>⚠ 여기 정규식은 <b>선형 패턴만</b> 쓴다(시간 제한을 두지 않는다). 중첩 수량자·겹치는 교대를 넣지 말 것.
 * 정규식은 후보만 고르고, 진짜 여부는 Java 검증기(Luhn·생년월일·전화번호/날짜 모양 제외 등)가 판정한다.
 *
 * <p>검증에서 떨어진 일치도 {@code replacement=null} 인 '경계' 로 보고한다 — 스트림 마스커가 자르는 지점을
 * 매처 일치 구간 안에 두지 않아야 조각별 결과와 전문 결과가 같아진다({@link StreamingMasker}).
 */
public final class BuiltinDetectors {

    private BuiltinDetectors() {}

    /** 내장 규칙 판정기. {@code [from,to)} 구간을 투명 경계로 훑고, 미완결 일치의 시작(없으면 -1)을 돌려준다. */
    interface Detector {
        int scan(CompiledRule rule, CharSequence text, int from, int to, boolean atEnd, List<Hit> out);
    }

    // ── 기본 규칙 정의 ─────────────────────────────────────────────

    public static final String CAT_ID = "개인식별";
    public static final String CAT_CREDIT = "개인신용";
    public static final String CAT_SECRET = "중요정보";

    public static final String RRN = "RRN";
    public static final String CARD = "CARD";
    public static final String CARD_CVC = "CARD_CVC";
    public static final String ACCOUNT = "ACCOUNT";
    public static final String MOBILE = "MOBILE";
    public static final String EMAIL = "EMAIL";
    public static final String PASSPORT = "PASSPORT";
    public static final String DRIVER = "DRIVER";
    public static final String SECRET_KV = "SECRET_KV";
    public static final String PEM_KEY = "PEM_KEY";
    public static final String KW_CREDIT = "KW_CREDIT";
    public static final String KW_SECRET = "KW_SECRET";

    /** 시드·DB 장애 시 폴백에 쓰는 기본 규칙 정의. */
    public static final class Def {
        public final String key, name, category, matchType, pattern, inputAction, description;
        public final boolean checkOutput;
        public final int sortOrder;

        Def(String key, String name, String category, String matchType, String pattern,
            String inputAction, boolean checkOutput, String description, int sortOrder) {
            this.key = key; this.name = name; this.category = category; this.matchType = matchType;
            this.pattern = pattern; this.inputAction = inputAction; this.checkOutput = checkOutput;
            this.description = description; this.sortOrder = sortOrder;
        }

        public LlmGuardRule toEntity() {
            LlmGuardRule r = new LlmGuardRule();
            r.setName(name);
            r.setCategory(category);
            r.setMatchType(matchType);
            r.setPattern(pattern);
            r.setBuiltinKey(key);
            r.setCheckInput(true);
            r.setInputAction(inputAction);
            r.setCheckOutput(checkOutput);
            r.setEnabled(true);
            r.setDescription(description);
            r.setSortOrder(sortOrder);
            return r;
        }
    }

    static final String KW_CREDIT_WORDS = String.join("\n",
            "주민등록번호", "주민번호", "외국인등록번호", "계좌번호", "신용카드번호", "카드번호",
            "CVC", "CVV", "카드 유효기간", "신용등급", "신용점수", "신용정보", "연체", "대출잔액", "채무");
    static final String KW_SECRET_WORDS = String.join("\n",
            "대외비", "기밀", "극비", "고객정보", "개인정보 원본", "영업비밀", "내부 한정", "CONFIDENTIAL");

    private static final String B = LlmGuardRule.TYPE_BUILTIN;
    private static final String K = LlmGuardRule.TYPE_KEYWORD;
    private static final String BLOCK = LlmGuardRule.ACTION_BLOCK;
    private static final String CONFIRM = LlmGuardRule.ACTION_CONFIRM;

    public static final List<Def> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
        new Def(RRN, "주민·외국인등록번호", CAT_ID, B,
                "YYMMDD-GNNNNNN (구분자 하이픈·대시·공백·없음 / 생년월일·성별자리 검증, 붙여 쓰면 검증번호 또는 앞 단어 필요)",
                BLOCK, true, "날짜(윤년 포함)와 성별자리의 출생 연대가 맞아야 하고 미래 출생은 제외합니다. "
                        + "하이픈 없이 붙인 13자리는 검증번호가 맞거나 앞에 주민번호·외국인등록번호 같은 단어가 있을 때만 잡습니다(로그 타임스탬프 오탐 방지).", 10),
        new Def(CARD, "신용카드번호", CAT_CREDIT, B,
                "14·15·16·19자리 (구분자 하이픈·공백·점·없음 / Luhn·카드사 번호대 검증)",
                BLOCK, true, "Luhn 검증과 길이별 카드사 번호대(16자리 2·3·4·5·6·9, Amex 34·37, Diners 30·36·38·39, 19자리 4·5·6)를 봅니다. "
                        + "영문·밑줄에 붙은 숫자열(해시·ID)은 제외합니다.", 20),
        new Def(CARD_CVC, "카드 보안코드·유효기간", CAT_CREDIT, B,
                "CVC·CVV·보안코드 뒤 3~4자리 / 유효기간·만료일·EXP 뒤 MM/YY",
                BLOCK, true, "단어 바로 뒤의 값만 가립니다. 유효기간은 MM/YY(MM/YYYY) 형태만 봅니다.", 25),
        new Def(ACCOUNT, "계좌번호", CAT_CREDIT, B,
                "하이픈 구분 10~14자리 / 계좌·입금·송금·이체·은행명·account 뒤 10~14자리",
                CONFIRM, true, "하이픈 없는 숫자열은 앞 15자 안에 계좌·입금·송금·이체·은행명·account 가 있을 때만 잡습니다. "
                        + "전화번호(국제전화 포함)·날짜·주민번호·사업자번호 모양과 금액·건수 단위가 붙은 숫자는 제외합니다.", 30),
        new Def(MOBILE, "휴대전화번호", CAT_ID, B, "01[016789]-NNN(N)-NNNN (구분자 -, ., 공백 또는 없음)",
                CONFIRM, true, null, 40),
        new Def(EMAIL, "이메일", CAT_ID, B, "local@domain.tld (example.com·.test 등 예시 도메인 제외)",
                CONFIRM, true, null, 50),
        new Def(PASSPORT, "여권번호", CAT_ID, B, "[MSRODG]NNNNNNNN 또는 [MSRODG]NNN[A-Z]NNNN",
                CONFIRM, true, null, 60),
        new Def(DRIVER, "운전면허번호", CAT_ID, B, "NN-NN-NNNNNN-NN (하이픈 필수)",
                CONFIRM, true, null, 70),
        new Def(SECRET_KV, "비밀번호·키 값", CAT_SECRET, B, "password= · secret: · api_key= · token= 등의 값",
                CONFIRM, true, "${…}·ENC(…)·****·빈 값은 제외합니다. 출력에서는 값만 가립니다.", 80),
        new Def(PEM_KEY, "PEM 개인키 블록", CAT_SECRET, B, "-----BEGIN … PRIVATE KEY----- ~ -----END … PRIVATE KEY-----",
                BLOCK, true, "출력에서는 블록 전체를 가립니다.", 90),
        new Def(KW_CREDIT, "개인신용정보 키워드", CAT_CREDIT, K, KW_CREDIT_WORDS,
                CONFIRM, false, "단어가 들어 있으면 확인을 받습니다. 단어 목록은 편집할 수 있습니다.", 100),
        new Def(KW_SECRET, "중요정보 키워드", CAT_SECRET, K, KW_SECRET_WORDS,
                CONFIRM, false, "단어가 들어 있으면 확인을 받습니다. 단어 목록은 편집할 수 있습니다.", 110)
    ));

    private static final Map<String, Detector> DETECTORS = new LinkedHashMap<>();
    static {
        DETECTORS.put(RRN, new RrnDetector());
        DETECTORS.put(CARD, new CardDetector());
        DETECTORS.put(CARD_CVC, new CardCvcDetector());
        DETECTORS.put(ACCOUNT, new AccountDetector());
        DETECTORS.put(MOBILE, new MobileDetector());
        DETECTORS.put(EMAIL, new EmailDetector());
        DETECTORS.put(PASSPORT, new PassportDetector());
        DETECTORS.put(DRIVER, new DriverDetector());
        DETECTORS.put(SECRET_KV, new SecretKvDetector());
        DETECTORS.put(PEM_KEY, new PemDetector());
    }

    /** 내장 판정기(키워드 규칙은 null — 단어 목록으로 판정한다). */
    static Detector detectorFor(String builtinKey) {
        return builtinKey == null ? null : DETECTORS.get(builtinKey);
    }

    // ── 공용 마스킹 헬퍼 ──────────────────────────────────────────

    /** 끝의 {@code keep} 자리 숫자만 남기고 숫자를 '*' 로. 구분자는 보존. */
    static String maskDigitsExceptLast(String s, int keep) {
        int digits = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) digits++;
        int toMask = Math.max(0, digits - keep);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) && toMask > 0) { sb.append('*'); toMask--; }
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 글자·숫자를 '*' 로. */
    static String maskAlnum(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? '*' : c);
        }
        return sb.toString();
    }

    /** 사용자 규칙 공용 — 2자 이하는 전부, 그보다 길면 첫 글자만 남기고 글자·숫자를 '*' 로. */
    static String genericMask(String s) {
        if (s.length() <= 2) return maskAlnum(s);
        return s.charAt(0) + maskAlnum(s.substring(1));
    }

    /** 정규식 한 개로 {@code [from,to)} 를 투명 경계로 훑는 매처. */
    static Matcher regionMatcher(Pattern p, CharSequence text, int from, int to) {
        Matcher m = p.matcher(text);
        m.region(from, to);
        m.useTransparentBounds(true);
        m.useAnchoringBounds(false);
        return m;
    }

    /**
     * 한 판정기의 여러 패턴이 같은 값을 잡으면 입력 건수가 두 번 센다({@code LlmGuardService.evaluate} 는 hit 마다 센다).
     * 다른 유효 일치의 치환 구간 안에 들고 전체 구간이 더 짧은 유효 일치는 경계(replacement=null)로 낮춘다.
     * 경계로 남겨야 스트림 자르기가 그 구간 안에 들어가지 않는다.
     */
    static void dropContained(List<Hit> local, List<Hit> out) {
        for (Hit h : local) {
            boolean inner = false;
            if (h.replacement != null) {
                for (Hit o : local) {
                    if (o == h || o.replacement == null) continue;
                    if (o.replStart <= h.replStart && h.replEnd <= o.replEnd && (o.end - o.start) > (h.end - h.start)) {
                        inner = true;
                        break;
                    }
                }
            }
            out.add(inner ? new Hit(h.rule, h.start, h.end, h.start, h.start, null) : h);
        }
    }

    // ── 정규식 판정기 공통 ─────────────────────────────────────────

    abstract static class RegexDetector implements Detector {
        final Pattern pattern;

        RegexDetector(Pattern pattern) { this.pattern = pattern; }

        @Override
        public int scan(CompiledRule rule, CharSequence text, int from, int to, boolean atEnd, List<Hit> out) {
            Matcher m = pattern.matcher(text);
            m.region(from, to);
            m.useTransparentBounds(true);
            m.useAnchoringBounds(false);
            while (m.find()) {
                if (m.end() <= m.start()) continue;
                Hit h = toHit(rule, m);
                // 검증 탈락도 경계로 남긴다(replacement=null) — 스트림 자르기 일관성
                out.add(h != null ? h : new Hit(rule, m.start(), m.end(), m.start(), m.start(), null));
            }
            return -1;
        }

        /** 검증 통과면 Hit, 아니면 null. */
        abstract Hit toHit(CompiledRule rule, Matcher m);

        static Hit whole(CompiledRule rule, Matcher m, String replacement) {
            return new Hit(rule, m.start(), m.end(), m.start(), m.end(), replacement);
        }
    }

    // ── 주민·외국인등록번호 ────────────────────────────────────────

    /**
     * 2026-11-28 고도화.
     * <ul>
     *   <li>구분자는 하이픈·유니코드 대시·전각 하이픈(앞뒤 공백 허용)·공백 하나·없음을 본다.</li>
     *   <li>성별자리로 출생 연대를 정한다(1·2·5·6 → 1900년대, 3·4·7·8 → 2000년대). 윤년과 미래 출생까지 검증한다.</li>
     *   <li>하이픈류 구분이 없으면(붙임·공백) 검증번호가 맞거나 앞 단어(주민·외국인·등록번호·RRN 등)가 있어야 한다.
     *       epoch 밀리초(예: 2024년 {@code 1704067200000})가 날짜로 읽혀 입력이 차단되던 오탐을 막는다.
     *       2020-10 이후 발급 번호는 검증번호가 없어, 하이픈 형태에서는 검증번호를 요구하지 않는다.</li>
     * </ul>
     * ⚠ 앞 단어는 <b>일치 구간 안</b>에 넣는다(Hit.start = 단어 시작) — 일치 밖 문맥을 따로 읽으면 스트림(앞 16자만 봄)과
     * 전문 결과가 갈린다(함정 94).
     */
    static final class RrnDetector extends RegexDetector {
        private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

        RrnDetector() {
            super(Pattern.compile("(?:(?<kw>주민|외국인|등록번호|거소|(?i:\\bRRN|\\bSSN))[^0-9\\r\\n]{0,12})?"
                    + "(?<![0-9])(?<yy>[0-9]{2})(?<mm>[0-9]{2})(?<dd>[0-9]{2})"
                    + "(?<sep>[ \\t]?[-\\u2010-\\u2015\\uFF0D][ \\t]?|[ \\t])?"
                    + "(?<g>[1-8])(?<rest>[0-9]{6})(?![0-9])"));
        }

        @Override
        Hit toHit(CompiledRule rule, Matcher m) {
            int yy = Integer.parseInt(m.group("yy"));
            int mm = Integer.parseInt(m.group("mm"));
            int dd = Integer.parseInt(m.group("dd"));
            int g = m.group("g").charAt(0) - '0';
            if (!validBirth(yy, mm, dd, g)) return null;
            String sep = m.group("sep") == null ? "" : m.group("sep");
            boolean hyphen = !sep.trim().isEmpty();
            if (!hyphen && m.group("kw") == null) {
                String digits = m.group("yy") + m.group("mm") + m.group("dd") + m.group("g") + m.group("rest");
                if (!rrnChecksum(digits)) return null;
            }
            String rep = m.group("yy") + m.group("mm") + m.group("dd") + sep + m.group("g") + "******";
            return new Hit(rule, m.start(), m.end(), m.start("yy"), m.end(), rep);
        }

        /** 성별자리로 정한 출생 연대에서 날짜가 실제로 있고(윤년 포함) 오늘 이전인가. */
        static boolean validBirth(int yy, int mm, int dd, int g) {
            if (!validMonthDay(mm, dd)) return false;
            int year = (g == 1 || g == 2 || g == 5 || g == 6 ? 1900 : 2000) + yy;
            if (mm == 2 && dd == 29 && !Year.isLeap(year)) return false;
            return !LocalDate.of(year, mm, dd).isAfter(LocalDate.now(SEOUL));
        }
    }

    private static final int[] RRN_WEIGHTS = {2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5};

    /**
     * 13자리 검증번호. 주민: {@code (11 − s%11) % 10}. 외국인(성별자리 5~8): 주민 공식 또는 {@code (13 − s%11) % 10}
     * (외국인등록번호 공식 — 발급 시기에 따라 두 공식이 섞여 있어 둘 다 받는다).
     */
    static boolean rrnChecksum(String d) {
        if (d.length() != 13) return false;
        int s = 0;
        for (int i = 0; i < 12; i++) s += (d.charAt(i) - '0') * RRN_WEIGHTS[i];
        int last = d.charAt(12) - '0';
        if ((11 - s % 11) % 10 == last) return true;
        int g = d.charAt(6) - '0';
        return g >= 5 && (13 - s % 11) % 10 == last;
    }

    static boolean validMonthDay(int mm, int dd) {
        if (mm < 1 || mm > 12 || dd < 1) return false;
        int[] max = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return dd <= max[mm - 1];
    }

    // ── 신용카드 ──────────────────────────────────────────────────

    /**
     * 2026-11-28 고도화 — 14(Diners 4-6-4)·15(Amex 4-6-5)·16(4-4-4-4)·19(4-4-4-4-3)자리, 구분자 하이픈·공백·점(한 가지만)·없음.
     * 앞뒤가 영문·밑줄이면 제외한다(해시·hex·식별자 안의 숫자열). 16자리를 품은 19자리처럼 같은 값을 두 패턴이 잡으면
     * 긴 쪽만 센다({@link #dropContained}).
     */
    static final class CardDetector implements Detector {
        private static final String L = "(?<![0-9A-Za-z_])";
        private static final String R = "(?![0-9A-Za-z_])";
        private static final Pattern P16 = Pattern.compile(
                L + "[0-9]{4}([- .]?)[0-9]{4}\\1[0-9]{4}\\1[0-9]{4}" + R);
        private static final Pattern P19 = Pattern.compile(
                L + "[0-9]{4}([- .]?)[0-9]{4}\\1[0-9]{4}\\1[0-9]{4}\\1[0-9]{3}" + R);
        private static final Pattern P15 = Pattern.compile(
                L + "3[47][0-9]{2}([- .]?)[0-9]{6}\\1[0-9]{5}" + R);
        private static final Pattern P14 = Pattern.compile(
                L + "3[0689][0-9]{2}([- .]?)[0-9]{6}\\1[0-9]{4}" + R);

        @Override
        public int scan(CompiledRule rule, CharSequence text, int from, int to, boolean atEnd, List<Hit> out) {
            List<Hit> local = new ArrayList<>();
            scanOne(P16, rule, text, from, to, local);
            scanOne(P19, rule, text, from, to, local);
            scanOne(P15, rule, text, from, to, local);
            scanOne(P14, rule, text, from, to, local);
            dropContained(local, out);
            return -1;
        }

        private static void scanOne(Pattern p, CompiledRule rule, CharSequence text, int from, int to, List<Hit> out) {
            Matcher m = regionMatcher(p, text, from, to);
            while (m.find()) {
                String s = m.group();
                String digits = s.replaceAll("[^0-9]", "");
                out.add(new Hit(rule, m.start(), m.end(), m.start(), m.end(),
                        validCard(digits) ? maskDigitsExceptLast(s, 4) : null));
            }
        }

        /** 길이별 카드사 번호대 + Luhn + 같은 숫자 반복 제외. */
        static boolean validCard(String d) {
            if (allSame(d) || !luhn(d)) return false;
            int p2 = Integer.parseInt(d.substring(0, 2));
            switch (d.length()) {
                case 14: {
                    int p3 = Integer.parseInt(d.substring(0, 3));
                    return (p3 >= 300 && p3 <= 305) || p3 == 309 || p2 == 36 || p2 == 38 || p2 == 39;
                }
                case 15: return p2 == 34 || p2 == 37;
                case 16: return issuerPrefix(d);
                case 19: { char c = d.charAt(0); return c == '4' || c == '5' || c == '6'; }
                default: return false;
            }
        }

        static boolean issuerPrefix(String d) {
            char c = d.charAt(0);
            if (c == '3' || c == '4' || c == '5' || c == '6' || c == '9') return true;
            if (c == '2' && d.length() >= 4) {
                int p4 = Integer.parseInt(d.substring(0, 4));
                return p4 >= 2221 && p4 <= 2720;
            }
            return false;
        }

        static boolean allSame(String d) {
            for (int i = 1; i < d.length(); i++) if (d.charAt(i) != d.charAt(0)) return false;
            return true;
        }
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (dbl) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    // ── 계좌번호 ──────────────────────────────────────────────────

    /**
     * 2026-11-28 고도화 — (a) 하이픈 형태(국제전화 {@code +82-…}·{@code 82-10-…} 제외 추가) + (b) 문맥 형태:
     * 앞 15자 안에 계좌·입금·송금·이체·은행명·account 가 있으면 붙임·공백 구분 10~14자리도 잡는다(금액·건수 단위가 붙으면 제외).
     * (b)는 단어를 일치 구간 안에 넣는다(함정 94). 같은 값을 (a)·(b)가 모두 잡으면 (b)만 센다.
     */
    static final class AccountDetector implements Detector {
        private static final Pattern HYPHEN = Pattern.compile("(?<![0-9+-])[0-9]{2,6}(?:-[0-9]{1,7}){1,3}(?![0-9-])");
        private static final Pattern CONTEXT = Pattern.compile(
                "(?:계좌|입금|송금|이체|은행|뱅크|농협|신협|수협|새마을금고|우체국|신한|IBK|SC제일|씨티|(?i:\\baccount|\\bacct))"
                + "[^0-9\\r\\n]{0,15}(?<v>[0-9](?:[- ]?[0-9]){9,13})(?![- ]?[0-9])"
                + "(?![ \\t]?(?:원|건|개|회|명|초|분|바이트|(?i:bytes?|ms|kb|mb|gb)))");
        private static final Pattern INTL_PHONE = Pattern.compile(
                "82-0?(?:1[016789]|[2-6][0-9]?)-[0-9]{3,4}-[0-9]{4}");
        private static final Pattern COMPACT_PHONE = Pattern.compile("01[016789][0-9]{7,8}|0[2-6][0-9]{7,9}");
        private static final Pattern COMPACT_DATE = Pattern.compile(
                "(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]*");
        private static final Pattern PHONE = Pattern.compile("0[0-9]{1,2}-[0-9]{3,4}-[0-9]{4}");
        private static final Pattern DATE_START = Pattern.compile(
                "(?:19|20)[0-9]{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])(?:-.*)?");
        private static final Pattern RRN_SHAPE = Pattern.compile("[0-9]{6}-[0-9]{7}");
        private static final Pattern DRIVER_SHAPE = Pattern.compile("[0-9]{2}-[0-9]{2}-[0-9]{6}-[0-9]{2}");
        private static final Pattern BIZ_SHAPE = Pattern.compile("[0-9]{3}-[0-9]{2}-[0-9]{5}");
        private static final Pattern COMPACT_DATE_START = Pattern.compile(
                "(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])-.*");

        @Override
        public int scan(CompiledRule rule, CharSequence text, int from, int to, boolean atEnd, List<Hit> out) {
            List<Hit> local = new ArrayList<>();
            Matcher m = regionMatcher(HYPHEN, text, from, to);
            while (m.find()) {
                String s = m.group();
                local.add(new Hit(rule, m.start(), m.end(), m.start(), m.end(),
                        hyphenAccount(s) ? maskAccount(s) : null));
            }
            Matcher c = regionMatcher(CONTEXT, text, from, to);
            while (c.find()) {
                String v = c.group("v");
                local.add(new Hit(rule, c.start(), c.end(), c.start("v"), c.end("v"),
                        contextAccount(v) ? maskAccount(v) : null));
            }
            dropContained(local, out);
            return -1;
        }

        /** 하이픈 형태 — 10~14자리, 전화·날짜·주민·운전면허·사업자·국제전화 모양 제외. */
        static boolean hyphenAccount(String s) {
            int digits = 0;
            for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) digits++;
            if (digits < 10 || digits > 14) return false;
            return !(PHONE.matcher(s).matches() || DATE_START.matcher(s).matches()
                    || RRN_SHAPE.matcher(s).matches() || DRIVER_SHAPE.matcher(s).matches()
                    || BIZ_SHAPE.matcher(s).matches() || COMPACT_DATE_START.matcher(s).matches()
                    || INTL_PHONE.matcher(s).matches());
        }

        /** 문맥 형태 — 하이픈이 있으면 하이픈 형태 규칙, 붙임·공백이면 전화·날짜로 시작하는 모양 제외. */
        static boolean contextAccount(String v) {
            if (v.indexOf('-') >= 0 && v.indexOf(' ') < 0) return hyphenAccount(v);
            String d = v.replaceAll("[^0-9]", "");
            return !(COMPACT_PHONE.matcher(d).matches() || COMPACT_DATE.matcher(d).matches());
        }
    }

    /** 계좌 가림 — 첫 구분자(하이픈·공백) 앞 묶음은 보존, 나머지는 끝 3자리만 남긴다. 붙인 값은 끝 3자리만. */
    static String maskAccount(String s) {
        int sep = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '-' || ch == ' ') { sep = i; break; }
        }
        if (sep < 0) return maskDigitsExceptLast(s, 3);
        return s.substring(0, sep) + maskDigitsExceptLast(s.substring(sep), 3);
    }

    // ── 카드 보안코드·유효기간 ─────────────────────────────────────

    /**
     * 2026-11-28 신규 — 단어(CVC·CVV·보안코드·카드 인증번호 / 유효기간·만료일·EXP·valid thru) 바로 뒤의 값만 가린다.
     * 유효기간은 MM/YY(MM/YYYY) 슬래시 형태만 본다(인증서 2027-12·JWT exp 값 제외). 영문 단어는 단어 경계를 둔다
     * ({@code expected 10/12} 의 exp 오인 방지). 단어는 일치 구간 안에 둔다(함정 94).
     */
    static final class CardCvcDetector implements Detector {
        private static final Pattern CVC = Pattern.compile(
                "(?:(?i:\\bCVC2?\\b|\\bCVV2?\\b)|카드[ ]?보안[ ]?코드|보안[ ]?코드|카드[ ]?인증[ ]?번호)"
                + "[^0-9\\r\\n]{0,6}(?<v>[0-9]{3,4})(?![0-9])");
        private static final Pattern EXP = Pattern.compile(
                "(?:유효[ ]?기간|만료[ ]?일|(?i:\\bexp(?:iry|iration)?(?:[ ]?date)?\\b|\\bvalid[ ]?thru\\b))"
                + "[^0-9\\r\\n]{0,12}(?<v>(?:0[1-9]|1[0-2])[ ]?/[ ]?(?:20)?[0-9]{2})(?![0-9/])");

        @Override
        public int scan(CompiledRule rule, CharSequence text, int from, int to, boolean atEnd, List<Hit> out) {
            Matcher m = regionMatcher(CVC, text, from, to);
            while (m.find()) {
                out.add(new Hit(rule, m.start(), m.end(), m.start("v"), m.end("v"), "*".repeat(m.group("v").length())));
            }
            Matcher e = regionMatcher(EXP, text, from, to);
            while (e.find()) {
                out.add(new Hit(rule, e.start(), e.end(), e.start("v"), e.end("v"), maskAlnum(e.group("v"))));
            }
            return -1;
        }
    }

    // ── 휴대전화 ──────────────────────────────────────────────────

    static final class MobileDetector extends RegexDetector {
        MobileDetector() {
            super(Pattern.compile("(?<![0-9-])(01[016789])([-. ]?)([0-9]{3,4})\\2([0-9]{4})(?![0-9-])"));
        }

        @Override
        Hit toHit(CompiledRule rule, Matcher m) {
            String rep = m.group(1) + m.group(2) + "*".repeat(m.group(3).length()) + m.group(2) + m.group(4);
            return whole(rule, m, rep);
        }
    }

    // ── 이메일 ───────────────────────────────────────────────────

    static final class EmailDetector extends RegexDetector {
        private static final Pattern PLACEHOLDER_DOMAIN = Pattern.compile(
                "(?i)(?:.*\\.)?(?:example\\.(?:com|org|net)|example|invalid|test|localhost|domain\\.com)");
        private static final java.util.Set<String> SERVICE_LOCAL = new java.util.HashSet<>(Arrays.asList(
                "git", "noreply", "no-reply", "donotreply", "do-not-reply"));

        EmailDetector() {
            super(Pattern.compile("(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]{1,64})@([A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,24})(?![A-Za-z0-9-])"));
        }

        @Override
        Hit toHit(CompiledRule rule, Matcher m) {
            String local = m.group(1);
            String domain = m.group(2);
            if (PLACEHOLDER_DOMAIN.matcher(domain).matches()) return null;
            if (SERVICE_LOCAL.contains(local.toLowerCase())) return null;
            int keep = local.length() >= 3 ? 2 : (local.length() == 2 ? 1 : 0);
            String rep = local.substring(0, keep) + "*".repeat(local.length() - keep) + "@" + domain;
            return whole(rule, m, rep);
        }
    }

    // ── 여권 ─────────────────────────────────────────────────────

    static final class PassportDetector extends RegexDetector {
        PassportDetector() {
            super(Pattern.compile("(?<![A-Za-z0-9])[MSRODG](?:[0-9]{8}|[0-9]{3}[A-Z][0-9]{4})(?![A-Za-z0-9])"));
        }

        @Override
        Hit toHit(CompiledRule rule, Matcher m) {
            String s = m.group();
            return whole(rule, m, s.substring(0, 2) + "*".repeat(s.length() - 2));
        }
    }

    // ── 운전면허 ──────────────────────────────────────────────────

    static final class DriverDetector extends RegexDetector {
        DriverDetector() {
            super(Pattern.compile("(?<![0-9-])([0-9]{2})-([0-9]{2})-([0-9]{6})-([0-9]{2})(?![0-9-])"));
        }

        @Override
        Hit toHit(CompiledRule rule, Matcher m) {
            return whole(rule, m, m.group(1) + "-**-******-**");
        }
    }

    // ── 비밀번호·키 값 ────────────────────────────────────────────

    /**
     * 키 앞 경계를 두지 않는다 — {@code DB_PASSWORD=}·{@code dbPassword=}·{@code jdbc.password=} 를 모두 잡기 위해서다.
     * 대신 값이 true/false·null 이면 설정 플래그로 보고 제외한다.
     */
    static final class SecretKvDetector extends RegexDetector {
        SecretKvDetector() {
            super(Pattern.compile("(?i)(?:password|passwd|pwd|passphrase|secret|client[_-]?secret"
                    + "|api[_-]?key|access[_-]?key|secret[_-]?key|private[_-]?key|auth[_-]?token|access[_-]?token|token)"
                    + "[\"']?[ \\t]*[:=][ \\t]*(?<v>\"[^\"\\r\\n]{1,200}\"|'[^'\\r\\n]{1,200}'|[^\\s\"',;&<>]{1,200})"));
        }

        @Override
        Hit toHit(CompiledRule rule, Matcher m) {
            String v = m.group("v");
            int vs = m.start("v");
            int ve = m.end("v");
            String inner = v;
            String q = "";
            if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
                q = String.valueOf(v.charAt(0));
                inner = v.substring(1, v.length() - 1);
            }
            if (isPlaceholderSecret(inner)) return null;
            return new Hit(rule, m.start(), m.end(), vs, ve, q + "****" + q);
        }
    }

    static boolean isPlaceholderSecret(String v) {
        String t = v.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("${") || t.startsWith("#{") || t.startsWith("{{")) return true;
        if (t.regionMatches(true, 0, "ENC(", 0, 4)) return true;
        if (t.startsWith("<") && t.endsWith(">")) return true;
        if ("null".equalsIgnoreCase(t) || "none".equalsIgnoreCase(t)
                || "true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) return true;
        boolean allStar = true;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) != '*') { allStar = false; break; }
        return allStar;
    }

    // ── PEM 개인키 블록 ───────────────────────────────────────────

    static final String PEM_MASK = "[PRIVATE KEY 가림]";

    static final class PemDetector implements Detector {
        static final Pattern BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----");
        static final Pattern END = Pattern.compile("-----END [A-Z0-9 ]{0,40}PRIVATE KEY-----");

        /**
         * 블록이 약 1.6KB 라 스트림 홀드백(128자)보다 길다. 시작 표지를 만나면 끝 표지가 올 때까지
         * 미완결로 보고해 그 앞까지만 내보내게 한다. 스트림 끝(또는 전문)에서 끝 표지가 없으면 끝까지 가린다.
         */
        @Override
        public int scan(CompiledRule rule, CharSequence text, int from, int to, boolean atEnd, List<Hit> out) {
            Matcher b = BEGIN.matcher(text);
            b.region(from, to);
            b.useTransparentBounds(true);
            while (b.find()) {
                int bs = b.start();
                Matcher e = END.matcher(text);
                e.region(b.end(), to);
                if (e.find()) {
                    out.add(new Hit(rule, bs, e.end(), bs, e.end(), PEM_MASK));
                    if (e.end() >= to) break;
                    b.region(e.end(), to);
                } else if (atEnd) {
                    out.add(new Hit(rule, bs, to, bs, to, PEM_MASK));
                    break;
                } else {
                    return bs;
                }
            }
            return -1;
        }
    }
}

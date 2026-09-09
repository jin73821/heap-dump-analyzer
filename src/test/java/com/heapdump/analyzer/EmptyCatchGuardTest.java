package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실행문 없는 catch 블록 금지 (2026-09-09 정적분석 지적 조치 — 04.02 오류 상황 대응 부재).
 *
 * <p>사내 점검 도구가 지적한 47건은 "catch 본문에 실행문이 하나도 없는" 블록이다(주석만 있어도 지적).
 * 전수 조사에서는 82건이 나왔고, 그중 일부는 단순 로그 누락이 아니라 <b>조용한 실패</b>였다 —
 * SSE 핸들러 전체를 감싼 catch 때문에 렌더 오류가 나면 스켈레톤이 영원히 돌았다.
 *
 * <p>여기서는 재발을 구조적으로 막는다. 정규식으로 훑으면 {@code 'http://'} 같은 문자열을 주석으로
 * 오인하므로, 문자열/템플릿리터럴/정규식리터럴/주석을 공백으로 지우는 작은 스캐너를 거친 뒤
 * 중괄호 균형으로 본문을 잘라 판정한다. 스캐너가 조용히 망가져 테스트가 무의미하게 통과하는 것을
 * 막기 위해 {@link #scannerDetectsKnownShapes()} 가 스캐너 자체를 검증한다.
 */
class EmptyCatchGuardTest {

    private static final Path JS_ROOT  = Paths.get("src/main/resources/static/js");
    private static final Path TPL_ROOT = Paths.get("src/main/resources/templates");

    @Test
    @DisplayName("static/js 와 templates 인라인 스크립트에 빈 catch 블록이 없다")
    void noEmptyCatchBlocks() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path p : filesUnder(JS_ROOT, ".js")) {
            // lib/ 는 외부 라이브러리(minified) — 우리 코드가 아니다
            if (p.toString().replace('\\', '/').contains("/js/lib/")) continue;
            violations.addAll(scan(p.toString(), blankNonCode(read(p))));
        }
        for (Path p : filesUnder(TPL_ROOT, ".html")) {
            violations.addAll(scan(p.toString(), blankNonCode(scriptsOnly(read(p)))));
        }

        assertTrue(violations.isEmpty(),
                "실행문 없는 catch 블록이 " + violations.size() + "건 남아 있다 "
                        + "(정적분석 04.02 재지적 대상 — 최소한 Common.logIgnored/logError 한 줄을 넣을 것):\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("스캐너는 빈 catch 만 골라내고 문자열·주석 안의 catch 는 세지 않는다")
    void scannerDetectsKnownShapes() {
        assertEquals(1, count("try { a(); } catch (e) {}"),                  "완전 공백 catch 를 못 잡는다");
        assertEquals(1, count("try { a(); } catch (e) { /* 무시 */ }"),      "주석만 있는 catch 를 못 잡는다");
        assertEquals(0, count("try { a(); } catch (e) { log(e); }"),         "실행문 있는 catch 를 잘못 잡는다");
        assertEquals(0, count("try { a(); } catch (e) { return; }"),         "return 도 실행문이다");
        assertEquals(1, count("p.catch(function () {});"),                   "빈 .catch 핸들러를 못 잡는다");
        assertEquals(1, count("p.catch(function (e) { /* x */ });"),         "주석만 있는 .catch 를 못 잡는다");
        assertEquals(0, count("p.catch(function (e) { show(e); });"),        "실행문 있는 .catch 를 잘못 잡는다");
        assertEquals(0, count("p.catch(handler);"),                          "핸들러 참조형 .catch 를 잘못 잡는다");
        assertEquals(0, count("var s = 'catch (e) {}';"),                    "문자열 안의 catch 를 세고 있다");
        assertEquals(0, count("// catch (e) {}\nvar u = 'http://x/a';"),     "주석 안의 catch 를 세고 있다");
        assertEquals(0, count("var re = /catch \\(e\\) \\{\\}/;"),           "정규식 리터럴 안의 catch 를 세고 있다");
        assertEquals(1, count("try { a(); } catch (e) { // 설명\n }"),       "줄 주석만 있는 catch 를 못 잡는다");
    }

    private static int count(String js) {
        return scan("<sample>", blankNonCode(js)).size();
    }

    /* ── 파일 수집 ─────────────────────────────────────────── */

    private static List<Path> filesUnder(Path root, String ext) throws IOException {
        assertTrue(Files.isDirectory(root), root + " 가 없다 — 테스트 작업 디렉토리가 프로젝트 루트가 아니다");
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /* ── 전처리 ────────────────────────────────────────────── */

    /** HTML 에서 &lt;script&gt; 본문만 남기고 나머지는 공백으로 (줄 번호 보존). */
    static String scriptsOnly(String html) {
        char[] out = new char[html.length()];
        for (int i = 0; i < out.length; i++) out[i] = html.charAt(i) == '\n' ? '\n' : ' ';
        String lower = html.toLowerCase();
        int idx = 0;
        while ((idx = lower.indexOf("<script", idx)) >= 0) {
            int open = lower.indexOf('>', idx);
            if (open < 0) break;
            int close = lower.indexOf("</script", open);
            if (close < 0) close = html.length();
            for (int i = open + 1; i < close; i++) out[i] = html.charAt(i);
            idx = close + 1;
        }
        return new String(out);
    }

    /**
     * 문자열·템플릿리터럴·정규식리터럴·주석을 공백으로 치환 (줄 번호·오프셋 보존).
     * 이후 중괄호 균형 계산이 안전해진다.
     */
    static String blankNonCode(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        char prevSignificant = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') { out[i] = ' '; i++; }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out[i] = ' '; out[i + 1] = ' '; i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) { out[i] = ' '; if (i + 1 < n) out[i + 1] = ' '; i += 2; }
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;                                   // 여는 따옴표는 남겨 둔다(토큰 경계 유지)
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') { if (src.charAt(i) != '\n') out[i] = ' '; i++; }
                    if (i < n && src.charAt(i) != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;                        // 닫는 따옴표
                prevSignificant = quote;
            } else if (c == '/' && isRegexStart(prevSignificant)) {
                i++;
                while (i < n && src.charAt(i) != '/' && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\') { out[i] = ' '; i++; }
                    if (i < n) { out[i] = ' '; i++; }
                }
                if (i < n && src.charAt(i) == '/') i++;
                prevSignificant = '/';
            } else {
                if (!Character.isWhitespace(c)) prevSignificant = c;
                i++;
            }
        }
        return new String(out);
    }

    private static boolean isRegexStart(char prev) {
        return prev == 0 || "(,=:[!&|?{};+-*%~^<>".indexOf(prev) >= 0;
    }

    /* ── 판정 ──────────────────────────────────────────────── */

    private static List<String> scan(String label, String code) {
        List<String> found = new ArrayList<>();
        found.addAll(scanCatchStatements(label, code));
        found.addAll(scanCatchHandlers(label, code));
        return found;
    }

    /** {@code catch (e) { … }} */
    private static List<String> scanCatchStatements(String label, String code) {
        List<String> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = code.indexOf("catch", from);
            if (at < 0) break;
            from = at + 5;
            if (at > 0 && (Character.isJavaIdentifierPart(code.charAt(at - 1)) || code.charAt(at - 1) == '.')) continue;
            int p = skipSpace(code, at + 5);
            if (p >= code.length() || code.charAt(p) != '(') continue;
            int paren = matchBrace(code, p, '(', ')');
            if (paren < 0) continue;
            int b = skipSpace(code, paren + 1);
            if (b >= code.length() || code.charAt(b) != '{') continue;
            int end = matchBrace(code, b, '{', '}');
            if (end < 0) continue;
            if (isBlank(code, b + 1, end)) found.add(label + ":" + lineOf(code, at) + "  (catch 문)");
        }
        return found;
    }

    /** {@code .catch(function (e) { … })} */
    private static List<String> scanCatchHandlers(String label, String code) {
        List<String> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = code.indexOf(".catch", from);
            if (at < 0) break;
            from = at + 6;
            int p = skipSpace(code, at + 6);
            if (p >= code.length() || code.charAt(p) != '(') continue;
            int close = matchBrace(code, p, '(', ')');
            if (close < 0) continue;
            int b = code.indexOf('{', p);
            if (b < 0 || b > close) continue;           // .catch(handler) — 본문 없음
            int end = matchBrace(code, b, '{', '}');
            if (end < 0 || end > close) continue;
            if (isBlank(code, b + 1, end)) found.add(label + ":" + lineOf(code, at) + "  (.catch 핸들러)");
        }
        return found;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int matchBrace(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return -1;
    }

    private static boolean isBlank(String s, int from, int to) {
        for (int i = from; i < to; i++) if (!Character.isWhitespace(s.charAt(i))) return false;
        return true;
    }

    private static int lineOf(String s, int idx) {
        int line = 1;
        for (int i = 0; i < idx; i++) if (s.charAt(i) == '\n') line++;
        return line;
    }
}

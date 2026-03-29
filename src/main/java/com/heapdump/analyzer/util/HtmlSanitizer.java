package com.heapdump.analyzer.util;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MAT 리포트 HTML 새니타이저 (OWASP whitelist 기반)
 *
 * MAT CLI가 생성하는 HTML 리포트에서 안전한 태그/속성만 허용하고,
 * XSS 벡터를 제거합니다.
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {}

    /** body 태그 내용 추출용 패턴 (사전 컴파일) */
    private static final Pattern BODY_PATTERN = Pattern.compile(
            "<body[^>]*>(.*)</body>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 이미 body만 추출된 HTML인지 판별 */
    private static final Pattern FULL_HTML_PATTERN = Pattern.compile(
            "<!DOCTYPE|<html", Pattern.CASE_INSENSITIVE);

    /**
     * MAT 리포트에서 허용할 HTML 태그/속성 whitelist.
     * 테이블, 기본 서식, 링크 등 MAT 리포트가 사용하는 요소만 허용.
     */
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            // 구조/레이아웃
            .allowElements("div", "span", "p", "br", "hr")
            .allowElements("h1", "h2", "h3", "h4", "h5", "h6")
            // 테이블 (MAT 리포트 핵심)
            .allowElements("table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col")
            // 텍스트 서식
            .allowElements("b", "i", "u", "strong", "em", "small", "sub", "sup", "code", "pre")
            // 리스트
            .allowElements("ul", "ol", "li", "dl", "dt", "dd")
            // 링크 (href는 안전한 프로토콜만)
            .allowElements("a")
            .allowAttributes("href").onElements("a")
            .allowUrlProtocols("http", "https", "javascript")
            .requireRelNofollowOnLinks()
            // 공통 속성
            .allowAttributes("class", "id", "title").globally()
            .allowAttributes("style").globally()
            .allowAttributes("width", "height", "border", "cellpadding", "cellspacing",
                    "align", "valign", "bgcolor", "colspan", "rowspan", "scope")
                    .onElements("table", "th", "td", "tr", "col", "colgroup")
            .allowAttributes("nowrap").onElements("td", "th")
            .toFactory();

    /**
     * MAT HTML을 새니타이즈합니다.
     * 1) body 내부 콘텐츠 추출
     * 2) OWASP whitelist 기반 태그/속성 필터링
     * 3) 깨진 href 정리
     */
    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) return html;

        // 1) body 추출 (전체 HTML 문서인 경우)
        html = extractBody(html);

        // 2) OWASP whitelist 새니타이즈
        html = POLICY.sanitize(html);

        // 3) 깨진 href 정리 (MAT 내부 링크 → javascript:void(0))
        html = fixBrokenHrefs(html);

        return html.trim();
    }

    /**
     * body 태그 내부 콘텐츠만 추출합니다.
     * 이미 body만 포함된 HTML이면 그대로 반환합니다.
     */
    static String extractBody(String html) {
        if (!FULL_HTML_PATTERN.matcher(html).find()) return html;

        Matcher m = BODY_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return html;
    }

    /**
     * MAT 내부 링크(상대경로, mat:// 등)를 javascript:void(0)으로 치환합니다.
     */
    private static final Pattern HREF_BROKEN = Pattern.compile(
            "(?i)href\\s*=\\s*\"(?!https?://|javascript:|#\")[^\"]*\"");
    private static final Pattern HREF_HASH = Pattern.compile(
            "(?i)href\\s*=\\s*['\"]#['\"]");

    static String fixBrokenHrefs(String html) {
        html = HREF_BROKEN.matcher(html).replaceAll("href=\"javascript:void(0)\"");
        html = HREF_HASH.matcher(html).replaceAll("href=\"javascript:void(0)\"");
        return html;
    }
}

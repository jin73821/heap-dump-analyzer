package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 파일명이 들어가는 응답 헤더 값 (2026-10-29, 함정 71).
 *
 * <p>사내망 제보: Files 페이지에서 {@code nodejs 사용여부 전수조사_v1.0.xlsx} 를 받으면 Tomcat 이
 * Content-Disposition 을 지우고(WARN) 화면은 로그인 페이지로 이동했다. Tomcat 의 판정은 "ISO-8859-1 로
 * 인코딩할 수 있는가" 하나이므로 여기서도 같은 인코더로 단언한다(실제 Tomcat 왕복은
 * {@link HttpHeaderValuesTomcatTest}).
 */
class HttpHeaderValuesTest {

    static final String INCIDENT = "nodejs 사용여부 전수조사_v1.0.xlsx";

    /** Tomcat {@code MessageBytes.toBytes} 가 헤더를 쓰는 조건 그대로 */
    static boolean tomcatCanWrite(String v) {
        return StandardCharsets.ISO_8859_1.newEncoder().canEncode(v);
    }

    /** RFC 5987 ext-value 를 퍼센트 해제만으로 되돌린다('+' 는 글자 그대로 — 공백이 아니다) */
    static String decodeExt(String headerValue) {
        Matcher m = Pattern.compile("filename\\*=UTF-8''([^;\\s]+)").matcher(headerValue);
        assertTrue(m.find(), "filename* 없음: " + headerValue);
        return UriUtils.decode(m.group(1), StandardCharsets.UTF_8);
    }

    static String quotedFallback(String headerValue) {
        Matcher m = Pattern.compile("filename=\"([^\"]*)\"").matcher(headerValue);
        assertTrue(m.find(), "filename= 없음: " + headerValue);
        return m.group(1);
    }

    @Test
    @DisplayName("제보 파일명 — 종전 형식은 Tomcat 이 쓸 수 없고, 새 형식은 순수 ASCII 이며 filename* 가 원래 이름으로 돌아온다")
    void incidentFilename() {
        assertFalse(tomcatCanWrite("attachment; filename=\"" + INCIDENT + "\""), "원인 재확인 — 종전 형식");

        String v = HttpHeaderValues.attachment(INCIDENT);
        assertTrue(tomcatCanWrite(v));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(v), "순수 ASCII");
        assertEquals("attachment; filename=\"nodejs ____ _____v1.0.xlsx\"; filename*=UTF-8''"
                + "nodejs%20%EC%82%AC%EC%9A%A9%EC%97%AC%EB%B6%80%20%EC%A0%84%EC%88%98%EC%A1%B0%EC%82%AC_v1.0.xlsx", v);
        assertEquals(INCIDENT, decodeExt(v));
    }

    @Test
    @DisplayName("ASCII 이름은 filename= 에 그대로 — 기존 PDF·내보내기 헤더의 앞부분이 바뀌지 않는다")
    void asciiNameUnchanged() {
        String v = HttpHeaderValues.attachment("heapdump-2026.10.29_01.hprof.gz");
        assertTrue(v.startsWith("attachment; filename=\"heapdump-2026.10.29_01.hprof.gz\"; filename*=UTF-8''"));
        assertEquals("heapdump-2026.10.29_01.hprof.gz", decodeExt(v));
        assertTrue(HttpHeaderValues.inline("core.12345-crash-report.pdf")
                .startsWith("inline; filename=\"core.12345-crash-report.pdf\""));
    }

    @Test
    @DisplayName("따옴표·역슬래시·세미콜론·제어문자 — 따옴표 문자열을 깨거나 헤더를 끊지 못하고, filename* 는 원래 이름 그대로")
    void hostileCharacters() {
        String name = "a \"b\" \\c;d+e*f~g%h'i\r\nSet-Cookie: x=1.txt";
        String v = HttpHeaderValues.attachment(name);
        assertTrue(tomcatCanWrite(v));
        assertFalse(v.contains("\r") || v.contains("\n"), "CR/LF 로 헤더를 끊을 수 없다");
        String fb = quotedFallback(v);
        assertFalse(fb.contains("\"") || fb.contains("\\"), "대체값 안에 따옴표·역슬래시 없음: " + fb);
        assertEquals(name, decodeExt(v), "filename* 는 손실 없이 왕복");
        String ext = v.substring(v.indexOf("filename*="));
        assertFalse(ext.contains(" ") || ext.contains("*f") || ext.contains("%h") || ext.contains("'i") || ext.contains(";d"),
                "attr-char 가 아닌 공백·* · % · ' · ; 은 인코딩돼야 한다: " + ext);
        assertTrue(ext.contains("+e") && ext.contains("~g"), "attr-char 인 + ~ 는 그대로");
    }

    @Test
    @DisplayName("RFC 5987 attr-char 집합 — 인쇄 가능 ASCII 중 그대로 남는 글자가 정확히 규격의 집합이다")
    void attrCharSetMatchesRfc5987() {
        StringBuilder kept = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            String enc = HttpHeaderValues.rfc5987(String.valueOf(c)).substring("UTF-8''".length());
            if (enc.equals(String.valueOf(c))) kept.append(c);
        }
        assertEquals("!#$&+-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ^_`abcdefghijklmnopqrstuvwxyz|~", kept.toString());
    }

    @Test
    @DisplayName("빈 이름·null — 'download' 로 대체(빈 filename 은 브라우저마다 다르게 해석된다)")
    void blankName() {
        assertEquals("attachment; filename=\"download\"; filename*=UTF-8''download", HttpHeaderValues.attachment(null));
        assertEquals("attachment; filename=\"download\"; filename*=UTF-8''download", HttpHeaderValues.attachment("  "));
    }

    @Test
    @DisplayName("리다이렉트 경로 조각 — 한글·공백·? # % { } 를 인코딩해 Location 이 ASCII 가 되고, 디코딩하면 원래 이름")
    void pathSegment() {
        String name = "코어 덤프?#%{x}.core";
        String seg = HttpHeaderValues.pathSegment(name);
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(seg));
        assertFalse(seg.contains(" ") || seg.contains("?") || seg.contains("#") || seg.contains("{") || seg.contains("/"), seg);
        assertEquals(name, UriUtils.decode(seg, StandardCharsets.UTF_8));
        assertEquals("core.12345", HttpHeaderValues.pathSegment("core.12345"), "ASCII 파일명은 그대로");
    }
}

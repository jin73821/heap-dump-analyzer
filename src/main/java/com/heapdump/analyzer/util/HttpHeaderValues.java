package com.heapdump.analyzer.util;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 파일명이 들어가는 응답 헤더 값의 단일 출처 (2026-10-29).
 *
 * <p>Tomcat 은 응답 헤더를 ISO-8859-1 로 인코딩하고, 인코딩할 수 없는 값은 <b>그 헤더만 지운 채</b>
 * 응답을 계속 보낸다(WARN "has been removed from the response because it is invalid"). 예외가 호출부로
 * 돌아오지 않으므로 서버 쪽에서는 200·302 로 "성공"한다 — 사내망 실측:
 * <ul>
 *   <li>{@code Content-Disposition} 에 한글 파일명 → 헤더가 사라진 200. Files 페이지는 이걸 세션 만료로
 *       판정해 다운로드 대신 {@code /login} 으로 이동했다.</li>
 *   <li>{@code redirect:/core-dump/analyze/} + 한글 파일명 → <b>Location 없는 302</b>(빈 화면).</li>
 * </ul>
 * 그래서 이 클래스가 만드는 값은 전부 <b>순수 ASCII</b> 다. 파일명을 헤더에 넣는 새 코드는 여기를 거칠 것.
 */
public final class HttpHeaderValues {

    private HttpHeaderValues() {}

    private static final String FALLBACK_NAME = "download";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** {@code attachment; filename="ASCII 대체값"; filename*=UTF-8''원래이름} */
    public static String attachment(String filename) {
        return contentDisposition("attachment", filename);
    }

    /** {@code inline; …} — iframe 미리보기용 */
    public static String inline(String filename) {
        return contentDisposition("inline", filename);
    }

    /**
     * RFC 6266 형식. 브라우저는 {@code filename*}(RFC 5987, UTF-8)를 우선하고, 그것을 모르는 클라이언트만
     * {@code filename}(ASCII 대체값 — 비ASCII·따옴표·역슬래시를 {@code _} 로 바꾼 것)을 쓴다.
     */
    public static String contentDisposition(String type, String filename) {
        String name = (filename == null || filename.isBlank()) ? FALLBACK_NAME : filename;
        return type + "; filename=\"" + asciiFallback(name) + "\"; filename*=" + rfc5987(name);
    }

    /**
     * {@code redirect:} 뷰 이름에 넣을 경로 조각 — UTF-8 퍼센트 인코딩.
     * 한글뿐 아니라 공백·{@code ? # % {}} 도 인코딩되므로 경로가 잘리거나 URI 템플릿으로 오해되지 않는다.
     */
    public static String pathSegment(String value) {
        return UriUtils.encodePathSegment(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 출력 가능한 ASCII(0x20~0x7E) 밖의 문자와 {@code " \} 를 {@code _} 로 — 따옴표 문자열 안이라 이 둘도 안전하지 않다. */
    static String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c >= 0x20 && c <= 0x7E && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }

    /**
     * RFC 5987 ext-value. attr-char 만 그대로 두고 나머지 UTF-8 바이트는 {@code %XX}.
     * ⚠ {@code URLEncoder} 를 쓰지 말 것 — 공백을 {@code +} 로 바꾸고 attr-char 가 아닌 {@code *} 를 남긴다.
     */
    static String rfc5987(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("UTF-8''");
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isAttrChar(c)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    private static boolean isAttrChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$&+-.^_`|~".indexOf(c) >= 0;
    }
}

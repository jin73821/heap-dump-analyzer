package com.heapdump.analyzer.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * RAG 지식/학습 문서의 중복 판정 키. 순수 static.
 *
 * <p>중복은 <b>내용 해시</b>와 <b>제목</b> 두 축으로 따로 본다 — 본문만 해싱하므로
 * "제목만 바꾼 같은 글"은 {@code DUP_CONTENT}, "다른 글에 같은 제목"은 {@code DUP_TITLE} 로
 * 직교하게 잡힌다. 제목·메타까지 해시에 넣으면 두 신호가 뭉개진다.
 *
 * <p>⚠ 해시 전 <b>정규화가 핵심</b>이다. 정규화 없이 해싱하면 Windows 에서 편집해 되올린
 * 같은 문서가 CRLF 차이만으로 매번 "신규"가 되어 코퍼스가 중복으로 불어난다.
 */
public final class RagDocHash {

    private RagDocHash() {}

    /** 내용 해시 — 정규화 후 SHA-256 소문자 hex. */
    public static String contentHash(String content) {
        return sha256Hex(normalize(content));
    }

    /** 제목 정규화 — 앞뒤 공백 제거 + 연속 공백 1칸 + 소문자. 비교 전용이며 표시에 쓰지 않는다. */
    public static String titleNorm(String title) {
        if (title == null) return "";
        return title.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 본문 정규화 — BOM 제거 + CRLF/CR → LF + 줄 끝 공백 제거 + 앞뒤 공백 제거.
     * 눈에 보이지 않는 차이로 중복 판정이 흔들리지 않게 한다.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s;
        if (!t.isEmpty() && t.charAt(0) == '﻿') t = t.substring(1);
        t = t.replace("\r\n", "\n").replace('\r', '\n');
        // 줄 끝 공백은 편집기마다 달라 붙었다 떨어졌다 한다 — 비교 대상에서 뺀다.
        t = t.replaceAll("[ \t]+\n", "\n");
        return t.strip();
    }

    /** SHA-256 소문자 hex. 저장소에 공용 헬퍼가 없어 여기 하나만 둔다(4곳이 각자 인라인이었다). */
    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 필수 알고리즘이라 실제로는 발생하지 않는다.
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}

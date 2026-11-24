package com.heapdump.analyzer.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public final class AuthUtil {

    private AuthUtil() {}

    /**
     * 감사 로그의 {@code by=} 값 — 파일 조작·관리 작업 로그는 반드시 수행자를 남긴다(2026-11-11).
     * {@link Authentication} 도 {@link java.security.Principal} 이라 둘 다 이 메서드 하나로 받는다.
     * 인증 정보가 없으면(필터 밖 호출·테스트) {@code unknown}.
     */
    public static String who(java.security.Principal principal) {
        if (principal == null) return "unknown";
        String n = principal.getName();
        return n == null || n.isBlank() ? "unknown" : n;
    }

    public static boolean isAdmin(Authentication authentication) {
        if (authentication == null) return false;
        for (GrantedAuthority a : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(a.getAuthority())) return true;
        }
        return false;
    }
}

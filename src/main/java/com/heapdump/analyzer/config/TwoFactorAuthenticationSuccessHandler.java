package com.heapdump.analyzer.config;

import com.heapdump.analyzer.service.TwoFactorConfigService;
import com.heapdump.analyzer.service.TwoFactorService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * 폼 로그인(1차 인증) 성공 핸들러.
 *
 * mode != otp: 기존 defaultSuccessUrl("/", true) 시맨틱 그대로 — 추가 오버헤드 없음.
 * mode == otp: SecurityContext 를 ROLE_PRE_AUTH 부분 인증 토큰으로 교체 후
 *              OTP 검증(등록) 페이지로 리다이렉트. 인가 규칙이 hasAnyRole("ADMIN","USER") 라
 *              부분 인증 상태로는 다른 어떤 경로도 접근 불가 (구조적 우회 차단).
 *
 * Security 6 는 SecurityContext 자동 저장을 보장하지 않으므로 saveContext() 명시 호출 필수.
 */
@Component
public class TwoFactorAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    public static final String ROLE_PRE_AUTH = "ROLE_PRE_AUTH";

    private final TwoFactorConfigService twoFactorConfig;
    private final TwoFactorService twoFactorService;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public TwoFactorAuthenticationSuccessHandler(TwoFactorConfigService twoFactorConfig,
                                                 TwoFactorService twoFactorService) {
        this.twoFactorConfig = twoFactorConfig;
        this.twoFactorService = twoFactorService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!twoFactorConfig.isOtpMode()) {
            response.sendRedirect("/");
            return;
        }

        // 관리자 OTP 예외 정책: 관리자는 OTP 단계를 건너뛰고 즉시 완전 인증
        if (isAdmin(authentication) && twoFactorConfig.isAdminExempt()) {
            response.sendRedirect("/");
            return;
        }

        String username = authentication.getName();
        UsernamePasswordAuthenticationToken preAuth = UsernamePasswordAuthenticationToken.authenticated(
                username, null, List.of(new SimpleGrantedAuthority(ROLE_PRE_AUTH)));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(preAuth);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);

        response.sendRedirect(twoFactorService.isEnrolled(username) ? "/login/otp" : "/login/otp/setup");
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}

package com.heapdump.analyzer.config;

import com.heapdump.analyzer.service.CustomUserDetailsService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final TwoFactorAuthenticationSuccessHandler twoFactorSuccessHandler;

    public SecurityConfig(CustomUserDetailsService userDetailsService,
                          TwoFactorAuthenticationSuccessHandler twoFactorSuccessHandler) {
        this.userDetailsService = userDetailsService;
        this.twoFactorSuccessHandler = twoFactorSuccessHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .userDetailsService(userDetailsService)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico", "/favicon.svg").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/account-requests").permitAll()
                // SSO 연동 진입점 (mode=sso 아닐 때는 컨트롤러가 /login redirect)
                .requestMatchers("/sso/login", "/sso/callback").permitAll()
                // OTP 2차인증 페이지 — ROLE_PRE_AUTH 부분 인증 토큰도 접근 가능해야 함
                .requestMatchers("/login/otp", "/login/otp/setup").authenticated()
                .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")

                // ── Settings 변경 API: ADMIN 전용 (USER 는 GET 으로 조회만 가능) ──
                // 일반 General 설정 (compress / unreachable / DB 설정 / session timeout / dashboard days)
                .requestMatchers(HttpMethod.POST, "/api/settings/**").hasRole("ADMIN")
                // LLM 설정 mutation (분석/채팅/인사이트 액션과 분리)
                .requestMatchers(HttpMethod.POST,
                    "/api/llm/enabled",
                    "/api/llm/config",
                    "/api/llm/apikey",
                    "/api/llm/test-connection",
                    "/api/llm/chat-prompt",
                    "/api/llm/chat-restore-mode",
                    "/api/llm/file-attach"
                ).hasRole("ADMIN")
                // RAG 설정 mutation 은 위 /api/settings/** 패턴에 이미 포함됨
                // 서버 스캔 주기 / SSH local user 변경 (Servers Settings 영역)
                .requestMatchers(HttpMethod.POST,
                    "/api/servers/scan-interval",
                    "/api/servers/ssh-local-user"
                ).hasRole("ADMIN")

                // 본인 자기서비스 — 완전 인증된 모든 사용자 (PRE_AUTH 부분 인증 차단)
                .requestMatchers("/account", "/api/account/**").hasAnyRole("ADMIN", "USER")

                // authenticated() 대신 hasAnyRole — OTP 대기(ROLE_PRE_AUTH) 상태의
                // 다른 경로 접근을 구조적으로 차단 (모든 계정 role 은 ADMIN|USER 뿐이라 의미 동일)
                .anyRequest().hasAnyRole("ADMIN", "USER")
            )
            .formLogin(form -> form
                .loginPage("/login")
                // 2FA 미사용/SSO 모드: "/" 즉시 redirect (기존 defaultSuccessUrl 시맨틱)
                // OTP 모드: ROLE_PRE_AUTH 토큰 교체 후 /login/otp(/setup) redirect
                .successHandler(twoFactorSuccessHandler)
                .failureHandler((req, res, ex) -> {
                    String url;
                    if (ex instanceof DisabledException) {
                        url = "/login?error=disabled";
                    } else if (ex instanceof LockedException) {
                        url = "/login?error=locked";
                    } else {
                        url = "/login?error=true";
                    }
                    res.sendRedirect(url);
                })
                .permitAll()
            )
            // PRE_AUTH(OTP 대기) 상태로 다른 경로 접근 시 403 대신 OTP 페이지로 유도
            .exceptionHandling(eh -> eh.accessDeniedHandler((req, res, ex) -> {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                boolean preAuth = a != null && a.getAuthorities().stream()
                        .anyMatch(g -> TwoFactorAuthenticationSuccessHandler.ROLE_PRE_AUTH.equals(g.getAuthority()));
                if (preAuth) {
                    res.sendRedirect("/login/otp");
                    return;
                }
                res.sendError(HttpServletResponse.SC_FORBIDDEN); // 기존 기본 403 동작 보존 (CSRF 거부 포함)
            }))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(request -> {
                    String uri = request.getRequestURI();
                    // ── CSRF 보호 유지 (면제하지 않음) ──
                    // 1) /api/admin/** — ADMIN 페이지 전용 영역
                    if (uri.startsWith("/api/admin/")) return false;
                    // 2) ADMIN-only mutation (authorizeHttpRequests 의 hasRole("ADMIN") 매처와 1:1 매칭)
                    if (uri.startsWith("/api/settings/")) return false;
                    if (uri.equals("/api/llm/enabled")
                        || uri.equals("/api/llm/config")
                        || uri.equals("/api/llm/apikey")
                        || uri.equals("/api/llm/test-connection")
                        || uri.equals("/api/llm/chat-prompt")
                        || uri.equals("/api/llm/chat-restore-mode")
                        || uri.equals("/api/llm/file-attach")) return false;
                    if (uri.equals("/api/servers/scan-interval")
                        || uri.equals("/api/servers/ssh-local-user")) return false;
                    // 3) 본인 자기서비스 — CSRF 보호 유지 (비밀번호/메모 변경은 민감)
                    if (uri.startsWith("/api/account/")) return false;
                    // ── 나머지 /api/** 경로는 CSRF 면제 (일반 사용자 액션) ──
                    return uri.startsWith("/api/");
                })
            );

        return http.build();
    }
}

package com.heapdump.analyzer.config;

import com.heapdump.analyzer.service.CustomUserDetailsService;
import com.heapdump.analyzer.service.PasswordPolicyConfigService;
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
                // 비밀번호 만료 강제 변경 페이지 — ROLE_PWD_EXPIRED 부분 인증 토큰도 접근 가능해야 함
                .requestMatchers("/login/password").authenticated()
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
                    "/api/llm/file-attach",
                    "/api/llm/ratelimit"
                ).hasRole("ADMIN")
                // RAG 설정 mutation 은 위 /api/settings/** 패턴에 이미 포함됨
                // 서버 스캔 주기 / SSH local user 변경 (Servers Settings 영역)
                .requestMatchers(HttpMethod.POST,
                    "/api/servers/scan-interval",
                    "/api/servers/ssh-local-user"
                ).hasRole("ADMIN")

                // 본인 자기서비스 — 완전 인증된 모든 사용자 (PRE_AUTH 부분 인증 차단)
                // (/account/** — /account/memo 새창 페이지 포함)
                .requestMatchers("/account", "/account/**", "/api/account/**").hasAnyRole("ADMIN", "USER")

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
            .exceptionHandling(eh -> eh
                /*
                 * /api/** 는 302 → /login 대신 401 JSON.
                 * 기본 EntryPoint(formLogin) 는 리다이렉트를 보내는데, 브라우저 fetch 는 이를 자동 추종해
                 * 로그인 페이지 HTML 을 200 으로 받는다 → Common.fetchJSON 이 r.ok=true 로 보고 **성공 처리**.
                 * 세션 만료/로그아웃 후 저장이 "성공"으로 표시되고 데이터는 유실되는 조용한 실패였다.
                 * (익명 사용자의 CSRF 실패도 ExceptionTranslationFilter 가 AccessDeniedHandler 가 아니라
                 *  이 EntryPoint 로 보내므로 세션 만료 감지는 여기가 단일 지점이다.)
                 */
                .defaultAuthenticationEntryPointFor(
                    (req, res, ex) -> writeApiError(res, HttpServletResponse.SC_UNAUTHORIZED,
                            "SESSION_EXPIRED", "로그인 세션이 만료되었습니다. 다시 로그인해 주세요."),
                    req -> req.getRequestURI().startsWith("/api/"))
                /*
                 * ⚠ 위 매핑만 등록하면 ExceptionHandlingConfigurer 가 "매핑이 하나뿐"이라는 이유로
                 * 그것을 **모든 요청의 기본 EntryPoint** 로 써버려, 페이지 라우트(/account 등)까지
                 * 로그인 리다이렉트 대신 401 JSON 을 받는다. 비-API 경로용 매핑을 명시적으로 함께 등록한다.
                 */
                .defaultAuthenticationEntryPointFor(
                    new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                    req -> !req.getRequestURI().startsWith("/api/"))
                .accessDeniedHandler((req, res, ex) -> {
                // 인증은 됐지만 거부된 API 요청도 HTML 이 아닌 JSON 으로 (호출자가 분기 가능하도록)
                if (req.getRequestURI().startsWith("/api/")) {
                    boolean csrf = ex instanceof org.springframework.security.web.csrf.MissingCsrfTokenException
                                || ex instanceof org.springframework.security.web.csrf.InvalidCsrfTokenException;
                    if (csrf) {
                        // CSRF 토큰 소실/불일치 = 세션이 교체됐다는 신호 → 재로그인 유도
                        writeApiError(res, HttpServletResponse.SC_UNAUTHORIZED,
                                "SESSION_EXPIRED", "로그인 세션이 만료되었습니다. 다시 로그인해 주세요.");
                    } else {
                        writeApiError(res, HttpServletResponse.SC_FORBIDDEN,
                                "FORBIDDEN", "이 작업을 수행할 권한이 없습니다.");
                    }
                    return;
                }
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                if (a != null) {
                    boolean preAuth = a.getAuthorities().stream()
                            .anyMatch(g -> TwoFactorAuthenticationSuccessHandler.ROLE_PRE_AUTH.equals(g.getAuthority()));
                    if (preAuth) {
                        res.sendRedirect("/login/otp");
                        return;
                    }
                    // 비밀번호 만료(ROLE_PWD_EXPIRED) 부분 인증 → 강제 변경 페이지로 유도
                    boolean pwdExpired = a.getAuthorities().stream()
                            .anyMatch(g -> PasswordPolicyConfigService.ROLE_PWD_EXPIRED.equals(g.getAuthority()));
                    if (pwdExpired) {
                        res.sendRedirect("/login/password");
                        return;
                    }
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
                        || uri.equals("/api/llm/file-attach")
                        || uri.equals("/api/llm/ratelimit")) return false;
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

    /**
     * /api/** 의 인증/인가 실패 응답 — 리다이렉트 대신 JSON.
     * code 는 클라이언트 분기용(SESSION_EXPIRED 면 재로그인 유도), error 는 그대로 노출 가능한 한국어 메시지.
     * 값에 따옴표/역슬래시가 없는 상수만 전달하므로 별도 escape 없이 조립한다.
     */
    private static void writeApiError(HttpServletResponse res, int status, String code, String message) {
        try {
            res.setStatus(status);
            res.setContentType("application/json;charset=UTF-8");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"success\":false,\"code\":\"" + code + "\",\"error\":\"" + message + "\"}");
        } catch (java.io.IOException ignored) {
            // 응답이 이미 커밋된 경우 — 더 할 수 있는 일이 없다
        }
    }
}

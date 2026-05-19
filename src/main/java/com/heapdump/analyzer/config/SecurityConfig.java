package com.heapdump.analyzer.config;

import com.heapdump.analyzer.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
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
                .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")

                // ── Settings 변경 API: ADMIN 전용 (USER 는 GET 으로 조회만 가능) ──
                // 일반 General 설정 (compress / unreachable / DB 설정)
                .requestMatchers(HttpMethod.POST, "/api/settings/**").hasRole("ADMIN")
                // LLM 설정 mutation (분석/채팅/인사이트 액션과 분리)
                .requestMatchers(HttpMethod.POST,
                    "/api/llm/enabled",
                    "/api/llm/config",
                    "/api/llm/apikey",
                    "/api/llm/test-connection",
                    "/api/llm/chat-prompt",
                    "/api/llm/chat-restore-mode"
                ).hasRole("ADMIN")
                // RAG 설정 mutation 은 위 /api/settings/** 패턴에 이미 포함됨
                // 서버 스캔 주기 / SSH local user 변경 (Servers Settings 영역)
                .requestMatchers(HttpMethod.POST,
                    "/api/servers/scan-interval",
                    "/api/servers/ssh-local-user"
                ).hasRole("ADMIN")

                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error=true")
                .permitAll()
            )
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
                        || uri.equals("/api/llm/chat-restore-mode")) return false;
                    if (uri.equals("/api/servers/scan-interval")
                        || uri.equals("/api/servers/ssh-local-user")) return false;
                    // ── 나머지 /api/** 경로는 CSRF 면제 (일반 사용자 액션) ──
                    return uri.startsWith("/api/");
                })
            );

        return http.build();
    }
}

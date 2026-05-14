package com.heapdump.analyzer.config;

import com.heapdump.analyzer.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
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
            .authorizeRequests()
                .antMatchers("/login", "/css/**", "/js/**", "/favicon.ico", "/favicon.svg").permitAll()
                .antMatchers(HttpMethod.POST, "/api/account-requests").permitAll()
                .antMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")

                // ── Settings 변경 API: ADMIN 전용 (USER 는 GET 으로 조회만 가능) ──
                // 일반 General 설정 (compress / unreachable / DB 설정)
                .antMatchers(HttpMethod.POST, "/api/settings/**").hasRole("ADMIN")
                // LLM 설정 mutation (분석/채팅/인사이트 액션과 분리)
                .antMatchers(HttpMethod.POST,
                    "/api/llm/enabled",
                    "/api/llm/config",
                    "/api/llm/apikey",
                    "/api/llm/test-connection",
                    "/api/llm/chat-prompt",
                    "/api/llm/chat-restore-mode"
                ).hasRole("ADMIN")
                // RAG 설정 mutation 은 위 /api/settings/** 패턴에 이미 포함됨
                // 서버 스캔 주기 / SSH local user 변경 (Servers Settings 영역)
                .antMatchers(HttpMethod.POST,
                    "/api/servers/scan-interval",
                    "/api/servers/ssh-local-user"
                ).hasRole("ADMIN")

                .anyRequest().authenticated()
            .and()
            .formLogin()
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error=true")
                .permitAll()
            .and()
            .logout()
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            .and()
            .headers()
                .frameOptions().sameOrigin()
            .and()
            .csrf()
                .ignoringRequestMatchers(request -> {
                    String uri = request.getRequestURI();
                    // /api/admin/** 경로는 CSRF 보호 유지 (면제하지 않음)
                    if (uri.startsWith("/api/admin/")) {
                        return false;
                    }
                    // 나머지 /api/** 경로는 CSRF 면제
                    return uri.startsWith("/api/");
                });

        return http.build();
    }
}

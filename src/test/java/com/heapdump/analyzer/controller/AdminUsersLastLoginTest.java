package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.LoginHistoryRepository;
import com.heapdump.analyzer.service.AccountLockPolicyConfigService;
import com.heapdump.analyzer.service.PasswordPolicyConfigService;
import com.heapdump.analyzer.service.TwoFactorConfigService;
import com.heapdump.analyzer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Accounts 사용자 목록 '최근 접속' 열 — {@code /api/admin/users} 의 {@code lastLoginAt/lastLoginIp} (2026-09-14).
 *
 * <p>고정하는 계약: ① 사용자별 마지막 로그인 성공을 <b>한 번의 집계 쿼리</b>로 붙인다(사용자마다 조회 금지)
 * ② 네이티브 쿼리의 DATETIME 이 Timestamp·LocalDateTime 어느 쪽으로 와도 같은 문자열 ③ 기록 없는 사용자는 null
 * ④ 같은 시각 중복 행은 첫 행 ⑤ 집계가 실패해도 목록 자체는 200 으로 나온다.
 */
class AdminUsersLastLoginTest {

    private UserService users;
    private LoginHistoryRepository history;
    private MockMvc mvc;

    private static User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        u.setRole(User.Role.USER);
        u.setEnabled(true);
        return u;
    }

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserService.class);
        history = Mockito.mock(LoginHistoryRepository.class);
        Mockito.when(users.findAll()).thenReturn(List.of(user(1, "alice"), user(2, "bob"), user(3, "carol")));
        mvc = MockMvcBuilders.standaloneSetup(new AdminController(users, history, Mockito.mock(JdbcTemplate.class),
                Mockito.mock(PasswordPolicyConfigService.class), Mockito.mock(TwoFactorConfigService.class),
                Mockito.mock(AccountLockPolicyConfigService.class))).build();
    }

    @Test
    @DisplayName("사용자별 마지막 로그인 성공 시각·IP 를 붙이고, 기록 없는 사용자는 null")
    void attachesLastLogin() throws Exception {
        Mockito.when(history.findLastSuccessfulLogins()).thenReturn(List.of(
                new Object[]{"alice", Timestamp.valueOf(LocalDateTime.of(2026, 9, 10, 14, 22, 5)), "10.0.0.9"},
                new Object[]{"alice", Timestamp.valueOf(LocalDateTime.of(2026, 9, 10, 14, 22, 5)), "10.0.0.99"},   // 같은 시각 중복
                new Object[]{"bob", LocalDateTime.of(2026, 1, 2, 3, 4), null}));

        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].lastLoginAt").value("2026-09-10 14:22"))
                .andExpect(jsonPath("$[0].lastLoginIp").value("10.0.0.9"))
                .andExpect(jsonPath("$[1].lastLoginAt").value("2026-01-02 03:04"))
                .andExpect(jsonPath("$[1].lastLoginIp").doesNotExist())
                .andExpect(jsonPath("$[2].lastLoginAt").doesNotExist());

        // N+1 금지 — 사용자 수와 무관하게 집계 1회
        Mockito.verify(history, Mockito.times(1)).findLastSuccessfulLogins();
    }

    @Test
    @DisplayName("집계 쿼리가 실패해도 사용자 목록은 나온다 (최근 접속만 비움)")
    void aggregateFailureDoesNotBreakList() throws Exception {
        Mockito.when(history.findLastSuccessfulLogins()).thenThrow(new RuntimeException("db down"));

        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].lastLoginAt").doesNotExist());
    }

    @Test
    @DisplayName("DATETIME 변환 — Timestamp/LocalDateTime/null/알 수 없는 타입")
    void toLocalDateTimeVariants() {
        LocalDateTime t = LocalDateTime.of(2026, 9, 14, 0, 10);
        assertEquals(t, AdminController.toLocalDateTime(t));
        assertEquals(t, AdminController.toLocalDateTime(Timestamp.valueOf(t)));
        assertNull(AdminController.toLocalDateTime(null));
        assertNull(AdminController.toLocalDateTime("2026-09-14 00:10"));
    }
}

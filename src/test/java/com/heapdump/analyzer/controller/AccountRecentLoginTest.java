package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.LoginHistory;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.LoginHistoryRepository;
import com.heapdump.analyzer.service.MemoHistoryService;
import com.heapdump.analyzer.service.PasswordPolicyConfigService;
import com.heapdump.analyzer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * /account 계정 정보의 '최근 접속'·'이전 접속' (2026-09-19).
 *
 * <p>최근 접속은 Accounts 사용자 목록과 같은 정의(마지막 로그인 성공)라 이 화면에서는 대개 지금 이 접속이다.
 * 그래서 현재 세션 표시와 직전 로그인(이전 접속)을 함께 싣는다. 여기서는 ① 행 → 표시 값 변환
 * ② 조회가 로그인 사용자 기준·성공 행만인지 ③ 조회 실패가 계정 화면을 막지 않는지를 고정한다.
 */
class AccountRecentLoginTest {

    private static LoginHistory login(LocalDateTime at, String ip, String sessionId) {
        LoginHistory h = new LoginHistory();
        h.setUsername("alice");
        h.setLoginAt(at);
        h.setIp(ip);
        h.setSessionId(sessionId);
        h.setStatus(LoginHistory.Status.SUCCESS);
        return h;
    }

    private static final LocalDateTime NOW  = LocalDateTime.of(2026, 9, 19, 16, 40, 12);
    private static final LocalDateTime PREV = LocalDateTime.of(2026, 9, 18, 9, 5, 59);

    @Test
    @DisplayName("최신 행이 현재 세션이면 '현재 세션', 둘째 행이 이전 접속이 된다")
    void currentSessionAndPrevious() {
        Map<String, Object> m = AccountController.recentLoginModel(
                List.of(login(NOW, "192.168.55.1", "sess-now"), login(PREV, "10.0.0.5", "sess-old")), "sess-now");

        assertEquals("2026-09-19 16:40", m.get("lastLoginAt"));
        assertEquals("192.168.55.1", m.get("lastLoginIp"));
        assertEquals(true, m.get("lastLoginCurrent"));
        assertEquals("2026-09-18 09:05", m.get("prevLoginAt"));
        assertEquals("10.0.0.5", m.get("prevLoginIp"));
    }

    @Test
    @DisplayName("다른 기기에서 그 뒤에 로그인했으면 '현재 세션' 을 붙이지 않는다 — 세션 정보가 없어도 마찬가지")
    void notCurrentWhenAnotherSessionLoggedInLater() {
        List<LoginHistory> rows = List.of(login(NOW, "10.9.9.9", "sess-other"), login(PREV, "192.168.55.1", "sess-mine"));
        assertEquals(false, AccountController.recentLoginModel(rows, "sess-mine").get("lastLoginCurrent"));
        // 세션 ID 가 둘 다 없을 때 null == null 로 '현재 세션' 이 되면 안 된다
        assertEquals(false, AccountController.recentLoginModel(
                List.of(login(NOW, "10.9.9.9", null)), null).get("lastLoginCurrent"));
    }

    @Test
    @DisplayName("첫 접속·기록 없음은 null — 화면은 '—'")
    void missingRowsAreNull() {
        Map<String, Object> one = AccountController.recentLoginModel(List.of(login(NOW, null, "s")), "s");
        assertEquals("2026-09-19 16:40", one.get("lastLoginAt"));
        assertNull(one.get("lastLoginIp"));
        assertNull(one.get("prevLoginAt"));
        assertNull(one.get("prevLoginIp"));

        Map<String, Object> none = AccountController.recentLoginModel(Collections.emptyList(), "s");
        assertNull(none.get("lastLoginAt"));
        assertEquals(false, none.get("lastLoginCurrent"));
        assertNull(AccountController.recentLoginModel(null, null).get("lastLoginAt"));
    }

    // ── 컨트롤러 배선 ─────────────────────────────────────────

    private LoginHistoryRepository repo;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        UserService users = Mockito.mock(UserService.class);
        User alice = new User();
        alice.setUsername("alice");
        alice.setRole(User.Role.USER);
        alice.setEnabled(true);
        Mockito.when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        repo = Mockito.mock(LoginHistoryRepository.class);
        AccountController controller = new AccountController(users, Mockito.mock(MemoHistoryService.class),
                Mockito.mock(PasswordPolicyConfigService.class), repo);
        // 뷰 이름 'account' 가 요청 경로 /account 로 되돌아가는 순환 포워드를 피하려고 렌더는 생략한다(템플릿은 AccountMemoTemplateSmokeTest 소관)
        mvc = MockMvcBuilders.standaloneSetup(controller).setSingleView((model, req, res) -> { }).build();
    }

    @Test
    @DisplayName("로그인 사용자의 성공 기록만 조회하고, 현재 세션을 대조해 모델에 싣는다")
    void pageCarriesRecentLogins() throws Exception {
        Mockito.when(repo.findTop2ByUsernameAndStatusOrderByLoginAtDesc("alice", LoginHistory.Status.SUCCESS))
                .thenReturn(List.of(login(NOW, "192.168.55.1", "sess-now"), login(PREV, "10.0.0.5", "sess-old")));

        mvc.perform(get("/account").principal(() -> "alice").session(new MockHttpSession(null, "sess-now")))
                .andExpect(status().isOk())
                .andExpect(view().name("account"))
                .andExpect(model().attribute("lastLoginAt", "2026-09-19 16:40"))
                .andExpect(model().attribute("lastLoginCurrent", true))
                .andExpect(model().attribute("prevLoginAt", "2026-09-18 09:05"))
                .andExpect(model().attribute("prevLoginIp", "10.0.0.5"));

        Mockito.verify(repo).findTop2ByUsernameAndStatusOrderByLoginAtDesc(eq("alice"), eq(LoginHistory.Status.SUCCESS));
    }

    @Test
    @DisplayName("접속 기록 조회가 실패해도 계정 화면은 열린다 — 두 칸만 비운다")
    void lookupFailureDoesNotBreakPage() throws Exception {
        Mockito.when(repo.findTop2ByUsernameAndStatusOrderByLoginAtDesc(anyString(), Mockito.any()))
                .thenThrow(new RuntimeException("DB down"));

        mvc.perform(get("/account").principal(() -> "alice"))
                .andExpect(status().isOk())
                .andExpect(view().name("account"))
                .andExpect(model().attributeExists("user"))
                .andExpect(model().attribute("lastLoginAt", (Object) null))
                .andExpect(model().attribute("prevLoginAt", (Object) null))
                .andExpect(model().attribute("lastLoginCurrent", false));
    }
}

package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.repository.LoginHistoryRepository;
import com.heapdump.analyzer.service.AccountLockPolicyConfigService;
import com.heapdump.analyzer.service.PasswordPolicyConfigService;
import com.heapdump.analyzer.service.TwoFactorConfigService;
import com.heapdump.analyzer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 현재 접속 세션 종료 — {@code POST /api/admin/active-sessions/terminate} 와 단건 DELETE (2026-11-03).
 *
 * <p>고정하는 계약: ① 본인 세션은 요청에 들어와도 종료하지 않는다(함정 2 — 화면의 체크박스 제외는 힌트일 뿐)
 * ② 이미 없는 세션은 실패가 아니라 별도 건수 ③ 한 건이 실패해도 나머지를 계속 종료하고 success=false 로 알린다
 * ④ 빈 요청·상한 초과는 아무것도 지우지 않는다 ⑤ 단건 DELETE 도 없는 세션을 성공으로 답하지 않는다.
 */
class AdminActiveSessionsTerminateTest {

    private static final String OWNER_SQL = "SELECT PRINCIPAL_NAME FROM SPRING_SESSION WHERE SESSION_ID = ?";

    private JdbcTemplate jdbc;
    private FindByIndexNameSessionRepository<?> repo;
    private MockMvc mvc;
    private final MockHttpSession self = new MockHttpSession(null, "self");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        repo = Mockito.mock(FindByIndexNameSessionRepository.class);
        AdminController controller = new AdminController(Mockito.mock(UserService.class),
                Mockito.mock(LoginHistoryRepository.class), jdbc,
                Mockito.mock(PasswordPolicyConfigService.class), Mockito.mock(TwoFactorConfigService.class),
                Mockito.mock(AccountLockPolicyConfigService.class));
        ReflectionTestUtils.setField(controller, "sessionRepository", repo);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Mockito.when(jdbc.queryForList(eq(OWNER_SQL), eq(String.class), anyString())).thenReturn(List.of("alice"));
    }

    private void sessionGone(String id) {
        Mockito.when(jdbc.queryForList(OWNER_SQL, String.class, id)).thenReturn(Collections.emptyList());
    }

    private org.springframework.test.web.servlet.ResultActions terminate(String json) throws Exception {
        return mvc.perform(post("/api/admin/active-sessions/terminate").session(self)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("본인 세션은 건너뛰고, 없는 세션은 별도 건수, 중복 id 는 한 번만 처리한다")
    void skipsSelfAndCountsGone() throws Exception {
        sessionGone("gone");

        terminate("{\"sessionIds\":[\"a\",\"self\",\"gone\",\"b\",\"a\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.terminated").value(2))
                .andExpect(jsonPath("$.alreadyGone").value(1))
                .andExpect(jsonPath("$.skippedSelf").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        Mockito.verify(repo).deleteById("a");
        Mockito.verify(repo).deleteById("b");
        Mockito.verify(repo, Mockito.never()).deleteById("self");
        Mockito.verify(repo, Mockito.never()).deleteById("gone");
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지를 계속 종료하고 success=false 로 알린다")
    void continuesAfterFailure() throws Exception {
        Mockito.doThrow(new IllegalStateException("db")).when(repo).deleteById("bad");

        terminate("{\"sessionIds\":[\"bad\",\"ok\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.terminated").value(1))
                .andExpect(jsonPath("$.failed").value(1));

        Mockito.verify(repo).deleteById("ok");
    }

    @Test
    @DisplayName("빈 요청·잘못된 형식·상한 초과는 400 이고 아무것도 지우지 않는다")
    void rejectsEmptyAndOverLimit() throws Exception {
        terminate("{\"sessionIds\":[]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        terminate("{\"sessionIds\":\"a\"}").andExpect(status().isBadRequest());
        terminate("{}").andExpect(status().isBadRequest());

        String ids = IntStream.range(0, 501).mapToObj(i -> "\"s" + i + "\"").collect(Collectors.joining(","));
        terminate("{\"sessionIds\":[" + ids + "]}").andExpect(status().isBadRequest());

        Mockito.verify(repo, Mockito.never()).deleteById(any());
    }

    @Test
    @DisplayName("단건 DELETE: 본인은 400, 없는 세션은 success=false, 있으면 종료")
    void singleDelete() throws Exception {
        mvc.perform(delete("/api/admin/active-sessions/self").session(self))
                .andExpect(status().isBadRequest());

        sessionGone("gone");
        mvc.perform(delete("/api/admin/active-sessions/gone").session(self))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        mvc.perform(delete("/api/admin/active-sessions/a").session(self))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Mockito.verify(repo).deleteById("a");
        Mockito.verify(repo, Mockito.never()).deleteById("self");
        Mockito.verify(repo, Mockito.never()).deleteById("gone");
    }
}

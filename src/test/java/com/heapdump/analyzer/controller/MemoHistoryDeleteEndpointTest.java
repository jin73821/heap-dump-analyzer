package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.MemoHistoryService;
import com.heapdump.analyzer.service.PasswordPolicyConfigService;
import com.heapdump.analyzer.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 메모 변경 이력 삭제(개별·전체) 엔드포인트 + 이력 창 배선 계약 (2026-09-14).
 *
 * <p>소유권·보관 정책 로직은 {@code MemoHistoryServiceTest} 소관이고, 여기서는 ① 라우팅과 응답 모양
 * ({@code memo.js} 가 {@code success===true} 를 확인하고 {@code remaining} 을 쓴다) ② 남의 id 거부가
 * 400 JSON 으로 나가는지 ③ 두 메모 페이지가 변경점 표시의 비교 기준을 넘기는지를 고정한다.
 * JS 동작(줄 비교·토글·확인 바)은 헤드리스 픽스처와 node 무작위 검증으로 확인했다(JS 러너 없음).
 */
class MemoHistoryDeleteEndpointTest {

    private MemoHistoryService history;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        history = Mockito.mock(MemoHistoryService.class);
        AccountController controller = new AccountController(
                Mockito.mock(UserService.class), history, Mockito.mock(PasswordPolicyConfigService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("개별 삭제 — 로그인 사용자 기준으로 지우고 남은 건수를 돌려준다")
    void deleteSingle() throws Exception {
        Mockito.when(history.delete("alice", 5L)).thenReturn(2L);

        mvc.perform(delete("/api/account/memo/history/5").principal(() -> "alice").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.remaining").value(2));

        Mockito.verify(history).delete("alice", 5L);
        Mockito.verify(history, Mockito.never()).deleteAll(anyString());
    }

    @Test
    @DisplayName("남의 이력·없는 id 는 400 JSON — 성공으로 보고하지 않는다")
    void deleteOthersIsRejected() throws Exception {
        Mockito.when(history.delete(anyString(), anyLong()))
                .thenThrow(new IllegalArgumentException("이력을 찾을 수 없습니다."));

        mvc.perform(delete("/api/account/memo/history/9").principal(() -> "bob").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이력을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("전체 삭제 — 삭제 건수를 돌려준다 (개별 경로와 섞이지 않는다)")
    void deleteAll() throws Exception {
        Mockito.when(history.deleteAll("alice")).thenReturn(3);

        mvc.perform(delete("/api/account/memo/history").principal(() -> "alice").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deleted").value(3))
                .andExpect(jsonPath("$.remaining").value(0));

        Mockito.verify(history, Mockito.never()).delete(anyString(), anyLong());
    }

    @Test
    @DisplayName("두 메모 페이지가 변경점 비교 기준(편집 중인 내용)을 넘기고, memo.js 가 삭제 API·토글 단축키를 갖는다")
    void viewerWiring() throws IOException {
        String account = read("src/main/resources/templates/account.html");
        String popup = read("src/main/resources/templates/account-memo.html");
        String js = read("src/main/resources/static/js/memo.js");

        // getCurrent 가 빠지면 토글이 조용히 비활성화된다 — 서버 저장본이 아니라 편집 중인 값이어야 한다
        assertTrue(account.contains("getCurrent: function () { return document.getElementById('memoBox').value; }"),
                "/account 이력 창에 비교 기준이 없다");
        assertTrue(popup.contains("getCurrent: function () { return box.value; }"),
                "/account/memo 이력 창에 비교 기준이 없다");

        assertTrue(js.contains("'/api/account/memo/history/' + encodeURIComponent(id),\n            { method: 'DELETE' }"),
                "개별 삭제 API 호출");
        assertTrue(js.contains("Common.fetchJSON('/api/account/memo/history', { method: 'DELETE' })"), "전체 삭제 API 호출");
        // 한글 입력 상태에서는 key 가 'ㅇ' 이라 code 를 봐야 토글이 먹는다
        assertTrue(js.contains("ev.code === 'KeyD'"), "토글 단축키가 물리 키(code)를 보지 않는다");
        // 줄 표시는 textContent 로만 — 메모는 사용자 입력이다
        assertTrue(js.contains("el.textContent = op.s;"), "변경점 줄을 textContent 가 아닌 방식으로 넣는다");

        assertEquals(1, count(account, "/js/memo.js?v="), "memo.js 로드");
        assertEquals(1, count(popup, "/js/memo.js?v="), "memo.js 로드");
    }

    private static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}

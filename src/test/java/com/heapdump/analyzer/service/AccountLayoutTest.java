package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * My Account 레이아웃의 계정별 영속화 (2026-08-12).
 *
 * <p>값이 그대로 CSS 클래스(`acct-split`)로 흘러가므로 <b>화이트리스트 밖의 입력은 저장되면 안 된다</b>.
 * 또 미설정(null)/오염된 값은 기본 'stack' 으로 해석돼야 화면이 깨지지 않는다.
 */
class AccountLayoutTest {

    private static User user() {
        User u = new User();
        u.setUsername("alice");
        return u;
    }

    private static UserService service(UserRepository repo) {
        return new UserService(repo, mock(PasswordEncoder.class), mock(MemoHistoryService.class));
    }

    // ── 해석 ────────────────────────────────────────────

    @Test
    @DisplayName("미설정/알 수 없는 값은 기본 stack 으로 해석된다")
    void unknownFallsBackToStack() {
        assertEquals("stack", UserService.accountLayoutOf(null));
        assertEquals("stack", UserService.accountLayoutOf(user()));           // null 컬럼

        User u = user();
        u.setAccountLayout("");
        assertEquals("stack", UserService.accountLayoutOf(u));
        u.setAccountLayout("grid");
        assertEquals("stack", UserService.accountLayoutOf(u), "미지원 값이 클래스로 새어나가면 안 된다");
    }

    @Test
    @DisplayName("저장된 값은 그대로 해석된다")
    void knownValuesPassThrough() {
        User u = user();
        u.setAccountLayout("split");
        assertEquals("split", UserService.accountLayoutOf(u));
        u.setAccountLayout("stack");
        assertEquals("stack", UserService.accountLayoutOf(u));
    }

    // ── 저장 ────────────────────────────────────────────

    @Test
    @DisplayName("stack/split 은 계정에 저장된다")
    void savesAllowedValues() {
        UserRepository repo = mock(UserRepository.class);
        User u = user();
        when(repo.findByUsername("alice")).thenReturn(Optional.of(u));
        UserService svc = service(repo);

        svc.saveAccountLayout("alice", "split");
        assertEquals("split", u.getAccountLayout());

        svc.saveAccountLayout("alice", " stack ");   // 공백은 트림
        assertEquals("stack", u.getAccountLayout());
    }

    @Test
    @DisplayName("화이트리스트 밖의 값은 거부하고 저장하지 않는다")
    void rejectsUnknownValues() {
        UserRepository repo = mock(UserRepository.class);
        UserService svc = service(repo);

        for (String bad : new String[]{ "grid", "", null, "SPLIT", "acct-split\" onload=x" }) {
            assertThrows(IllegalArgumentException.class, () -> svc.saveAccountLayout("alice", bad),
                    "거부해야 할 값: " + bad);
        }
        verify(repo, never()).save(any());
    }
}

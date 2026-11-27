package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.controller.PasswordChangeController;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 관리자 '비밀번호 초기화' (2026-11-27) — 비밀번호는 그대로 두고 사용자가 새 비밀번호를 직접 정하게 한다.
 * 고정하는 계약: ① 초기화는 표시만 켜고 비밀번호를 바꾸지 않는다 ② 로그인 게이트는 만료 정책·관리자 예외와 무관하게 표시를 본다
 * ③ 초기화 뒤 첫 로그인 변경은 현재 비밀번호 없이(표시가 있을 때만) ④ 본인 변경·관리자 '수정' 은 표시를 푼다
 * ⑤ 강제 변경 화면은 사유(reset/expired)에 따라 다른 변경 경로를 탄다.
 */
class PasswordResetRequiredTest {

    private User user(User.Role role) {
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        u.setPassword("$enc$old");
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(LocalDateTime.now().minusDays(10));
        u.setPasswordChangedAt(LocalDateTime.now().minusDays(10));
        return u;
    }

    private PasswordPolicyConfigService policy(int days, boolean adminExempt) {
        PasswordPolicyConfigService p = new PasswordPolicyConfigService(mock(HeapDumpConfig.class));
        p.setPasswordPolicy(days, adminExempt);
        return p;
    }

    @Test
    @DisplayName("초기화: 표시만 켜고 비밀번호·변경 시각은 그대로 둔다")
    void requireKeepsPassword() {
        UserRepository repo = mock(UserRepository.class);
        UserService svc = new UserService(repo, mock(PasswordEncoder.class), mock(MemoHistoryService.class));
        User u = user(User.Role.USER);
        LocalDateTime changedAt = u.getPasswordChangedAt();
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        when(repo.save(u)).thenReturn(u);

        svc.requirePasswordChange(1L);

        assertTrue(u.isPasswordChangeRequiredFlag());
        assertEquals("$enc$old", u.getPassword(), "기존 비밀번호로 로그인할 수 있어야 한다");
        assertEquals(changedAt, u.getPasswordChangedAt());
    }

    @Test
    @DisplayName("로그인 게이트: 초기화 표시는 만료 정책 비활성·관리자 예외여도 변경을 요구한다")
    void gateIgnoresExpiryPolicy() {
        PasswordPolicyConfigService off = policy(0, true);
        User admin = user(User.Role.ADMIN);
        assertFalse(off.mustChangeAtLogin(admin), "표시 없음 + 정책 꺼짐 → 그대로 로그인");
        admin.setPasswordChangeRequired(Boolean.TRUE);
        assertTrue(off.mustChangeAtLogin(admin));
        assertFalse(off.mustChangeAtLogin(null));

        PasswordPolicyConfigService on = policy(5, false);
        User expired = user(User.Role.USER);   // 10일 전 변경 > 5일
        assertTrue(on.mustChangeAtLogin(expired), "만료도 그대로 게이트에 걸린다");
    }

    @Test
    @DisplayName("초기화 뒤 새 비밀번호 설정: 현재 비밀번호 없이 바꾸고 표시를 푼다")
    void setNewAfterReset() {
        UserRepository repo = mock(UserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        UserService svc = new UserService(repo, enc, mock(MemoHistoryService.class));
        User u = user(User.Role.USER);
        u.setPasswordChangeRequired(Boolean.TRUE);
        u.setPasswordFailCount(3);
        LocalDateTime old = u.getPasswordChangedAt();
        when(repo.findByUsername("alice")).thenReturn(Optional.of(u));
        when(enc.matches("NewPass1!", "$enc$old")).thenReturn(false);
        when(enc.encode("NewPass1!")).thenReturn("$enc$new");

        svc.setNewPasswordAfterReset("alice", "NewPass1!");

        assertEquals("$enc$new", u.getPassword());
        assertFalse(u.isPasswordChangeRequiredFlag());
        assertTrue(u.getPasswordChangedAt().isAfter(old));
        assertEquals(0, u.getPasswordFailCount());
        verify(repo).save(u);
    }

    @Test
    @DisplayName("초기화 뒤 새 비밀번호 설정: 표시가 없으면 거부(현재 비밀번호 확인 우회 방지)·기존과 같으면 거부·복잡도 검사")
    void setNewAfterResetGuards() {
        UserRepository repo = mock(UserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        UserService svc = new UserService(repo, enc, mock(MemoHistoryService.class));
        User u = user(User.Role.USER);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(u));

        assertThrows(IllegalArgumentException.class, () -> svc.setNewPasswordAfterReset("alice", "NewPass1!"), "표시 없음");

        u.setPasswordChangeRequired(Boolean.TRUE);
        when(enc.matches("SamePass1!", "$enc$old")).thenReturn(true);
        IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
                () -> svc.setNewPasswordAfterReset("alice", "SamePass1!"));
        assertTrue(same.getMessage().contains("기존 비밀번호와 달라야"));
        assertThrows(IllegalArgumentException.class, () -> svc.setNewPasswordAfterReset("alice", "short"), "복잡도");
        verify(repo, never()).save(any());
        assertTrue(u.isPasswordChangeRequiredFlag(), "실패하면 표시가 남는다");
    }

    @Test
    @DisplayName("접속 중 본인 변경(현재 + 새 + 확인)·관리자 '비밀번호 수정' 은 초기화 표시를 푼다")
    void otherPathsClearFlag() {
        UserRepository repo = mock(UserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        UserService svc = new UserService(repo, enc, mock(MemoHistoryService.class));

        User a = user(User.Role.USER);
        a.setPasswordChangeRequired(Boolean.TRUE);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(a));
        when(enc.matches("Curr3nt!", "$enc$old")).thenReturn(true);
        when(enc.matches("NewPass1!", "$enc$old")).thenReturn(false);
        when(enc.encode(anyString())).thenReturn("$enc$new");
        svc.changeOwnPassword("alice", "Curr3nt!", "NewPass1!");
        assertFalse(a.isPasswordChangeRequiredFlag());

        User b = user(User.Role.USER);
        b.setPasswordChangeRequired(Boolean.TRUE);
        when(repo.findById(1L)).thenReturn(Optional.of(b));
        svc.resetPassword(1L, "Admin5et!");
        assertFalse(b.isPasswordChangeRequiredFlag());
    }

    // ── 강제 변경 화면(/login/password) — 사유별 경로 ──

    private static Authentication pwdExpiredAuth() {
        return UsernamePasswordAuthenticationToken.authenticated("alice", null,
                List.of(new SimpleGrantedAuthority(PasswordPolicyConfigService.ROLE_PWD_EXPIRED)));
    }

    @Test
    @DisplayName("/login/password: 초기화 사유면 현재 비밀번호 없이 setNewPasswordAfterReset, 만료면 changeOwnPassword")
    void controllerBranchesOnReason() {
        TwoFactorService tfs = mock(TwoFactorService.class);
        when(tfs.upgradeAfterPasswordChange(any(), any())).thenReturn(true);
        UserService us = mock(UserService.class);
        User u = user(User.Role.USER);
        u.setPasswordChangeRequired(Boolean.TRUE);
        when(us.findByUsername("alice")).thenReturn(Optional.of(u));
        when(us.isPasswordChangeRequired("alice")).thenReturn(true);
        PasswordChangeController c = new PasswordChangeController(tfs, policy(0, true), us);

        ExtendedModelMap m = new ExtendedModelMap();
        assertEquals("login-password-expired", c.page(pwdExpiredAuth(), new MockHttpServletRequest(), new MockHttpServletResponse(), m));
        assertEquals("reset", m.get("reason"));

        String view = c.change(pwdExpiredAuth(), new MockHttpServletRequest(), new MockHttpServletResponse(),
                null, "NewPass1!", "NewPass1!", new ExtendedModelMap());
        assertEquals("redirect:/", view);
        verify(us).setNewPasswordAfterReset("alice", "NewPass1!");
        verify(us, never()).changeOwnPassword(anyString(), any(), anyString());

        // 만료 사유 — 종전대로 현재 비밀번호 검증 경로
        UserService us2 = mock(UserService.class);
        when(us2.findByUsername("alice")).thenReturn(Optional.of(user(User.Role.USER)));
        when(us2.isPasswordChangeRequired("alice")).thenReturn(false);
        PasswordChangeController c2 = new PasswordChangeController(tfs, policy(5, false), us2);
        ExtendedModelMap m2 = new ExtendedModelMap();
        c2.page(pwdExpiredAuth(), new MockHttpServletRequest(), new MockHttpServletResponse(), m2);
        assertEquals("expired", m2.get("reason"));
        c2.change(pwdExpiredAuth(), new MockHttpServletRequest(), new MockHttpServletResponse(),
                "Curr3nt!", "NewPass1!", "NewPass1!", new ExtendedModelMap());
        verify(us2).changeOwnPassword("alice", "Curr3nt!", "NewPass1!");
        verify(us2, never()).setNewPasswordAfterReset(anyString(), anyString());
    }

    @Test
    @DisplayName("/login/password: 대기 중 사유가 사라지면(관리자가 직접 수정·정책 끔) 바로 승격한다")
    void controllerPromotesWhenNoLongerRequired() {
        TwoFactorService tfs = mock(TwoFactorService.class);
        when(tfs.upgradeAfterPasswordChange(any(), any())).thenReturn(true);
        UserService us = mock(UserService.class);
        when(us.findByUsername("alice")).thenReturn(Optional.of(user(User.Role.USER)));   // 표시 없음 + 미만료
        PasswordChangeController c = new PasswordChangeController(tfs, policy(30, false), us);

        assertEquals("redirect:/", c.page(pwdExpiredAuth(), new MockHttpServletRequest(), new MockHttpServletResponse(), new ExtendedModelMap()));
        verify(tfs).upgradeAfterPasswordChange(any(), any());
    }

    @Test
    @DisplayName("/login/password: 새 비밀번호 확인 불일치는 재렌더(사유 유지)")
    void controllerMismatchRerenders() {
        TwoFactorService tfs = mock(TwoFactorService.class);
        UserService us = mock(UserService.class);
        User u = user(User.Role.USER);
        u.setPasswordChangeRequired(Boolean.TRUE);
        when(us.findByUsername("alice")).thenReturn(Optional.of(u));
        when(us.isPasswordChangeRequired("alice")).thenReturn(true);
        PasswordChangeController c = new PasswordChangeController(tfs, policy(0, true), us);
        ExtendedModelMap m = new ExtendedModelMap();
        assertEquals("login-password-expired", c.change(pwdExpiredAuth(), new MockHttpServletRequest(),
                new MockHttpServletResponse(), null, "NewPass1!", "Other1!!", m));
        assertEquals("reset", m.get("reason"));
        assertNotNull(m.get("errorMessage"));
        verify(us, never()).setNewPasswordAfterReset(anyString(), anyString());
        verify(tfs, never()).upgradeAfterPasswordChange(any(), any());
    }
}

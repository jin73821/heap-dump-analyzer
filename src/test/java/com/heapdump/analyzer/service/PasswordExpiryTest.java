package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 비밀번호 만료 정책 판정 + 수동 변경 시 passwordChangedAt 초기화 검증 (Task 4).
 * DB 없이 Mockito 로 UserRepository/PasswordEncoder 를 모킹한다.
 */
class PasswordExpiryTest {

    private User user(LocalDateTime changedAt, User.Role role) {
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        u.setPassword("$enc$old");
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(LocalDateTime.now().minusDays(200));
        u.setPasswordChangedAt(changedAt);
        return u;
    }

    // ── UserService: 수동 변경 시 passwordChangedAt 초기화 (Task 4) ──

    @Test
    @DisplayName("changeOwnPassword: 만료 전이라도 수동 변경하면 passwordChangedAt 가 현재로 초기화")
    void changeOwnPasswordResetsChangedAt() {
        UserRepository repo = mock(UserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        UserService svc = new UserService(repo, enc, mock(MemoHistoryService.class));

        LocalDateTime old = LocalDateTime.now().minusDays(80);
        User u = user(old, User.Role.USER);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(u));
        when(enc.matches("Curr3nt!", "$enc$old")).thenReturn(true);    // 현재 PW 일치
        when(enc.matches("NewPass1!", "$enc$old")).thenReturn(false);  // 새 PW 는 다름
        when(enc.encode("NewPass1!")).thenReturn("$enc$new");

        LocalDateTime before = LocalDateTime.now();
        svc.changeOwnPassword("alice", "Curr3nt!", "NewPass1!");

        assertNotNull(u.getPasswordChangedAt());
        assertTrue(u.getPasswordChangedAt().isAfter(old), "passwordChangedAt 가 갱신되어야 함");
        assertFalse(u.getPasswordChangedAt().isBefore(before.minusSeconds(5)), "대략 현재 시각이어야 함");
        assertEquals("$enc$new", u.getPassword());
        verify(repo).save(u);
    }

    @Test
    @DisplayName("resetPassword(관리자 초기화): passwordChangedAt 초기화")
    void adminResetResetsChangedAt() {
        UserRepository repo = mock(UserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        UserService svc = new UserService(repo, enc, mock(MemoHistoryService.class));

        LocalDateTime old = LocalDateTime.now().minusDays(120);
        User u = user(old, User.Role.USER);
        when(repo.findById(1L)).thenReturn(Optional.of(u));
        when(enc.encode("Reset123!")).thenReturn("$enc$reset");

        svc.resetPassword(1L, "Reset123!");

        assertNotNull(u.getPasswordChangedAt());
        assertTrue(u.getPasswordChangedAt().isAfter(old), "관리자 초기화 시에도 갱신되어야 함");
        verify(repo).save(u);
    }

    // ── PasswordPolicyConfigService: 만료 판정 ──

    private PasswordPolicyConfigService policy(int days, boolean adminExempt) {
        PasswordPolicyConfigService p = new PasswordPolicyConfigService(mock(HeapDumpConfig.class));
        p.setPasswordPolicy(days, adminExempt);   // @PostConstruct init 우회
        return p;
    }

    @Test
    @DisplayName("정책 비활성(0일)은 항상 미만료")
    void disabledNeverExpires() {
        PasswordPolicyConfigService p = policy(0, true);
        User u = user(LocalDateTime.now().minusDays(999), User.Role.USER);
        assertFalse(p.isExpired(u));
        assertNull(p.daysUntilExpiry(u));
    }

    @Test
    @DisplayName("관리자 예외 ON: 관리자는 미만료, 일반 사용자는 만료")
    void adminExempt() {
        PasswordPolicyConfigService p = policy(30, true);
        assertFalse(p.isExpired(user(LocalDateTime.now().minusDays(60), User.Role.ADMIN)));
        assertTrue(p.isExpired(user(LocalDateTime.now().minusDays(60), User.Role.USER)));
    }

    @Test
    @DisplayName("변경 후 정책기간 미경과면 미만료 + 잔여일 계산")
    void freshNotExpired() {
        PasswordPolicyConfigService p = policy(30, false);
        User u = user(LocalDateTime.now().minusDays(25), User.Role.USER);
        assertFalse(p.isExpired(u));
        Long left = p.daysUntilExpiry(u);
        assertNotNull(left);
        assertTrue(left >= 4 && left <= 5, "약 5일 남아야 함: " + left);
    }

    @Test
    @DisplayName("passwordChangedAt 없으면 createdAt 으로 폴백 판정")
    void fallbackToCreatedAt() {
        PasswordPolicyConfigService p = policy(30, false);
        User u = user(null, User.Role.USER);
        u.setCreatedAt(LocalDateTime.now().minusDays(40));   // 40일 전 생성, 변경 이력 없음
        assertTrue(p.isExpired(u));
    }
}

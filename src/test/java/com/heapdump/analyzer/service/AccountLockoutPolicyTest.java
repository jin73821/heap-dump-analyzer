package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 비밀번호 반복 실패 계정 잠금 정책 — 설정 보관(AccountLockPolicyConfigService) +
 * 실행(LoginAttemptService) + 잠금 해제(UserService) 검증.
 *
 * DB 없이 Mockito 로 UserRepository/HeapDumpConfig 를 모킹한다 (PasswordExpiryTest 와 동일 방식).
 */
class AccountLockoutPolicyTest {

    // ── 헬퍼 ──────────────────────────────────────────────────────

    private static User user(User.Role role, int failCount, boolean locked) {
        User u = new User();
        u.setId(7L);
        u.setUsername("alice");
        u.setPassword("$enc$pw");
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(LocalDateTime.now().minusDays(10));
        u.setPasswordFailCount(failCount);
        u.setAccountLocked(locked);
        return u;
    }

    private static AccountLockPolicyConfigService policy(boolean enabled, int threshold, boolean adminExempt) {
        HeapDumpConfig cfg = mock(HeapDumpConfig.class);
        when(cfg.isAccountLockoutEnabled()).thenReturn(enabled);
        when(cfg.getAccountLockoutThreshold()).thenReturn(threshold);
        when(cfg.isAccountLockoutAdminExempt()).thenReturn(adminExempt);
        AccountLockPolicyConfigService svc = new AccountLockPolicyConfigService(cfg);
        svc.init();
        return svc;
    }

    /** increment 호출이 실제로 엔티티 카운트를 올리도록 스텁 (DB 원자 증가 흉내) */
    private static UserRepository repoFor(User u) {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername(u.getUsername())).thenReturn(Optional.of(u));
        when(repo.findById(u.getId())).thenReturn(Optional.of(u));
        when(repo.incrementPasswordFailCount(anyLong())).thenAnswer(inv -> {
            u.setPasswordFailCount(u.getPasswordFailCount() + 1);
            return 1;
        });
        when(repo.resetPasswordFailCount(anyLong())).thenAnswer(inv -> {
            u.setPasswordFailCount(0);
            return 1;
        });
        return repo;
    }

    // ── 설정 서비스 ───────────────────────────────────────────────

    @Nested
    @DisplayName("AccountLockPolicyConfigService")
    class ConfigService {

        @Test
        @DisplayName("임계 횟수 정규화: 0/음수는 기본값 10, 상한 초과는 100 으로 보정")
        void normalizeThreshold() {
            assertEquals(10, AccountLockPolicyConfigService.normalizeThreshold(0));
            assertEquals(10, AccountLockPolicyConfigService.normalizeThreshold(-5));
            assertEquals(1, AccountLockPolicyConfigService.normalizeThreshold(1));
            assertEquals(5, AccountLockPolicyConfigService.normalizeThreshold(5));
            assertEquals(100, AccountLockPolicyConfigService.normalizeThreshold(100));
            assertEquals(100, AccountLockPolicyConfigService.normalizeThreshold(9999));
        }

        @Test
        @DisplayName("init: application.properties 시드가 런타임 값이 된다 (5곳 동기화 중 1·2번째 훅)")
        void initSeedsFromConfig() {
            AccountLockPolicyConfigService svc = policy(true, 5, false);
            assertTrue(svc.isEnabled());
            assertEquals(5, svc.getThreshold());
            assertFalse(svc.isAdminExempt());
        }

        @Test
        @DisplayName("3-hook 왕복: collectSettings 로 저장한 값이 applyFromSettings 로 그대로 복원")
        void settingsRoundTrip() {
            AccountLockPolicyConfigService saved = policy(true, 7, false);
            Map<String, Object> settings = new LinkedHashMap<>();
            saved.collectSettings(settings);

            AccountLockPolicyConfigService restored = policy(false, 10, true);
            restored.applyFromSettings(settings);

            assertTrue(restored.isEnabled());
            assertEquals(7, restored.getThreshold());
            assertFalse(restored.isAdminExempt());
        }

        @Test
        @DisplayName("applyFromSettings: 숫자가 아닌 threshold 는 무시하고 기존 값을 유지 (설정 파일 손상 방어)")
        void malformedThresholdKeepsCurrent() {
            AccountLockPolicyConfigService svc = policy(true, 6, true);
            Map<String, Object> broken = new HashMap<>();
            broken.put("accountLockoutThreshold", "다섯");
            svc.applyFromSettings(broken);
            assertEquals(6, svc.getThreshold());
        }

        @Test
        @DisplayName("collectApplicationProperties: security.password.lockout-* 3 키를 모두 내보낸다")
        void applicationPropertyKeys() {
            Map<String, String> updates = new LinkedHashMap<>();
            policy(true, 4, false).collectApplicationProperties(updates);
            assertEquals("true", updates.get("security.password.lockout-enabled"));
            assertEquals("4", updates.get("security.password.lockout-threshold"));
            assertEquals("false", updates.get("security.password.lockout-admin-exempt"));
            assertEquals(3, updates.size());
        }
    }

    // ── 실행 서비스 ───────────────────────────────────────────────

    @Nested
    @DisplayName("LoginAttemptService")
    class Attempts {

        @Test
        @DisplayName("정책 OFF: 비밀번호를 몇 번 틀려도 카운트조차 올리지 않는다 (종전 동작 보존)")
        void disabledPolicyDoesNothing() {
            User u = user(User.Role.USER, 0, false);
            UserRepository repo = repoFor(u);
            LoginAttemptService svc = new LoginAttemptService(repo, policy(false, 10, true));

            for (int i = 0; i < 20; i++) {
                assertEquals(LoginAttemptService.Outcome.IGNORED, svc.recordFailure("alice").outcome());
            }
            assertEquals(0, u.getPasswordFailCount());
            assertFalse(u.isAccountLocked());
            verify(repo, never()).incrementPasswordFailCount(anyLong());
        }

        @Test
        @DisplayName("임계 미도달: 카운트만 증가하고 남은 시도 횟수를 돌려준다")
        void countsBelowThreshold() {
            User u = user(User.Role.USER, 0, false);
            LoginAttemptService svc = new LoginAttemptService(repoFor(u), policy(true, 5, true));

            LoginAttemptService.AttemptResult r1 = svc.recordFailure("alice");
            assertEquals(LoginAttemptService.Outcome.COUNTED, r1.outcome());
            assertEquals(1, r1.failCount());
            assertEquals(4, r1.remaining());

            LoginAttemptService.AttemptResult r2 = svc.recordFailure("alice");
            assertEquals(2, r2.failCount());
            assertEquals(3, r2.remaining());
            assertFalse(u.isAccountLocked());
        }

        @Test
        @DisplayName("임계 도달: 계정이 잠기고 lock_reason=PASSWORD, locked_at 이 기록된다")
        void locksAtThreshold() {
            User u = user(User.Role.USER, 0, false);
            LoginAttemptService svc = new LoginAttemptService(repoFor(u), policy(true, 3, true));

            assertTrue(svc.recordFailure("alice").counted());
            assertTrue(svc.recordFailure("alice").counted());
            LoginAttemptService.AttemptResult last = svc.recordFailure("alice");

            assertTrue(last.locked(), "3회째 실패에서 잠겨야 함");
            assertEquals(3, last.failCount());
            assertTrue(u.isAccountLocked());
            assertEquals(User.LOCK_REASON_PASSWORD, u.getLockReason());
            assertNotNull(u.getLockedAt());
        }

        @Test
        @DisplayName("임계 1회: 첫 실패에서 즉시 잠긴다 (경계값)")
        void thresholdOneLocksImmediately() {
            User u = user(User.Role.USER, 0, false);
            LoginAttemptService svc = new LoginAttemptService(repoFor(u), policy(true, 1, true));
            assertTrue(svc.recordFailure("alice").locked());
            assertTrue(u.isAccountLocked());
        }

        @Test
        @DisplayName("관리자 예외 ON: ADMIN 은 카운트가 누적되지 않아 잠기지 않는다 (자기 잠금 방지)")
        void adminExemptNeverLocks() {
            User admin = user(User.Role.ADMIN, 0, false);
            LoginAttemptService svc = new LoginAttemptService(repoFor(admin), policy(true, 3, true));

            for (int i = 0; i < 10; i++) {
                assertEquals(LoginAttemptService.Outcome.IGNORED, svc.recordFailure("alice").outcome());
            }
            assertFalse(admin.isAccountLocked());
            assertEquals(0, admin.getPasswordFailCount());
        }

        @Test
        @DisplayName("관리자 예외 OFF: ADMIN 도 임계 도달 시 잠긴다")
        void adminLocksWhenExemptDisabled() {
            User admin = user(User.Role.ADMIN, 0, false);
            LoginAttemptService svc = new LoginAttemptService(repoFor(admin), policy(true, 2, false));

            assertTrue(svc.recordFailure("alice").counted());
            assertTrue(svc.recordFailure("alice").locked());
            assertTrue(admin.isAccountLocked());
            assertEquals(User.LOCK_REASON_PASSWORD, admin.getLockReason());
        }

        @Test
        @DisplayName("미존재 계정: 잠글 대상이 없으므로 무시하고 DB 쓰기도 하지 않는다")
        void unknownUserIgnored() {
            UserRepository repo = mock(UserRepository.class);
            when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
            LoginAttemptService svc = new LoginAttemptService(repo, policy(true, 3, true));

            assertEquals(LoginAttemptService.Outcome.IGNORED, svc.recordFailure("ghost").outcome());
            verify(repo, never()).incrementPasswordFailCount(anyLong());
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("이미 잠긴 계정: 카운트를 더 올리지 않는다 (해제 후 곧바로 재잠금 방지)")
        void alreadyLockedNotCounted() {
            User u = user(User.Role.USER, 3, true);
            UserRepository repo = repoFor(u);
            LoginAttemptService svc = new LoginAttemptService(repo, policy(true, 3, true));

            assertEquals(LoginAttemptService.Outcome.IGNORED, svc.recordFailure("alice").outcome());
            assertEquals(3, u.getPasswordFailCount());
            verify(repo, never()).incrementPasswordFailCount(anyLong());
        }

        @Test
        @DisplayName("로그인 성공: 누적 카운트가 0 으로 리셋된다")
        void successResetsCount() {
            User u = user(User.Role.USER, 4, false);
            UserRepository repo = repoFor(u);
            LoginAttemptService svc = new LoginAttemptService(repo, policy(true, 10, true));

            svc.recordSuccess("alice");
            assertEquals(0, u.getPasswordFailCount());
            verify(repo).resetPasswordFailCount(u.getId());
        }

        @Test
        @DisplayName("로그인 성공 + 카운트 0: 불필요한 UPDATE 를 보내지 않는다")
        void successSkipsWriteWhenAlreadyZero() {
            User u = user(User.Role.USER, 0, false);
            UserRepository repo = repoFor(u);
            new LoginAttemptService(repo, policy(true, 10, true)).recordSuccess("alice");
            verify(repo, never()).resetPasswordFailCount(anyLong());
        }

        @Test
        @DisplayName("정책 OFF 에서도 성공 시 리셋한다 — 껐다 켰을 때 옛 카운트로 즉시 잠기는 것 방지")
        void successResetsEvenWhenPolicyOff() {
            User u = user(User.Role.USER, 9, false);
            LoginAttemptService svc = new LoginAttemptService(repoFor(u), policy(false, 10, true));
            svc.recordSuccess("alice");
            assertEquals(0, u.getPasswordFailCount());
        }

        @Test
        @DisplayName("lockReasonText: PASSWORD/OTP 를 구분하고, 사유 미기록(레거시)은 OTP 로 해석")
        void lockReasonTextBranches() {
            User pwLocked = user(User.Role.USER, 3, true);
            pwLocked.setLockReason(User.LOCK_REASON_PASSWORD);
            LoginAttemptService pwSvc = new LoginAttemptService(repoFor(pwLocked), policy(true, 3, true));
            assertTrue(pwSvc.lockReasonText("alice").contains("비밀번호"));

            User legacy = user(User.Role.USER, 0, true);   // lockReason == null (도입 이전 잠금)
            LoginAttemptService legacySvc = new LoginAttemptService(repoFor(legacy), policy(true, 3, true));
            assertTrue(legacySvc.lockReasonText("alice").contains("OTP"));
        }
    }

    // ── 잠금 해제 ─────────────────────────────────────────────────

    @Test
    @DisplayName("unlockUser: 잠금·사유·OTP/비밀번호 두 카운트를 모두 리셋한다")
    void unlockResetsBothCounters() {
        User u = user(User.Role.USER, 10, true);
        u.setLockReason(User.LOCK_REASON_PASSWORD);
        u.setLockedAt(LocalDateTime.now());
        u.setOtpFailCount(4);

        UserRepository repo = mock(UserRepository.class);
        when(repo.findById(u.getId())).thenReturn(Optional.of(u));
        when(repo.save(u)).thenReturn(u);
        UserService svc = new UserService(repo, mock(PasswordEncoder.class), mock(MemoHistoryService.class));

        User unlocked = svc.unlockUser(u.getId());

        assertFalse(unlocked.isAccountLocked());
        assertNull(unlocked.getLockedAt());
        assertNull(unlocked.getLockReason());
        assertEquals(0, unlocked.getPasswordFailCount());
        assertEquals(0, unlocked.getOtpFailCount());
    }
}

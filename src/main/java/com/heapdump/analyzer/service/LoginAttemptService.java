package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 비밀번호(1차 인증) 연속 실패 누적 + 임계 도달 시 계정 잠금 실행 서비스.
 *
 * 정책 값 보관은 {@link AccountLockPolicyConfigService}, 실행은 여기 — OTP 쪽의
 * TwoFactorConfigService / TwoFactorService 분리와 같은 구조다.
 * 호출 지점은 {@code AuthEventListener} 하나뿐이다 (인증 실패/성공 이벤트).
 *
 * 잠금 상태는 OTP 잠금과 동일하게 {@code users.account_locked} 에 기록되므로
 * CustomUserDetailsService 의 accountNonLocked 매핑 → LockedException → /login?error=locked
 * 흐름과 관리자 화면의 "잠김" 배지·잠금 해제 버튼이 그대로 재사용된다.
 * 사유 구분은 {@code users.lock_reason} 이 담당한다.
 */
@Service
public class LoginAttemptService {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserRepository userRepository;
    private final AccountLockPolicyConfigService lockPolicy;

    public LoginAttemptService(UserRepository userRepository, AccountLockPolicyConfigService lockPolicy) {
        this.userRepository = userRepository;
        this.lockPolicy = lockPolicy;
    }

    /** 실패 처리 결과 */
    public enum Outcome {
        /** 정책 비활성 / 미존재 계정 / 관리자 예외 / 이미 잠김 — 카운트·잠금 없음 */
        IGNORED,
        /** 카운트만 증가 (아직 임계 미도달) */
        COUNTED,
        /** 이번 실패로 계정이 잠김 */
        LOCKED
    }

    /**
     * @param outcome   처리 결과
     * @param failCount 누적 실패 횟수 (IGNORED 면 0)
     * @param remaining 잠금까지 남은 시도 횟수 (COUNTED 일 때만 의미 있음)
     */
    public record AttemptResult(Outcome outcome, int failCount, int remaining) {
        public static AttemptResult ignored() { return new AttemptResult(Outcome.IGNORED, 0, -1); }
        public boolean locked() { return outcome == Outcome.LOCKED; }
        public boolean counted() { return outcome == Outcome.COUNTED; }
    }

    /**
     * 비밀번호 실패 1회 누적. 임계 도달 시 계정을 잠근다.
     *
     * 미존재 계정은 잠글 대상이 없으므로 무시한다 (존재 여부에 따라 응답이 달라지지 않도록
     * 호출부의 로그인 화면 문구도 동일하게 유지할 것 — 사용자 열거 방지).
     */
    @Transactional
    public AttemptResult recordFailure(String username) {
        if (!lockPolicy.isEnabled() || username == null || username.isEmpty()) {
            return AttemptResult.ignored();
        }
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return AttemptResult.ignored();
        }
        User user = found.get();

        // 관리자 자기 잠금 방지: 카운트를 누적하지 않는다 (매 실패마다 리셋 — OTP enforce_no_lock 과 동일 철학)
        if (lockPolicy.isExempt(user)) {
            resetIfNeeded(user);
            return AttemptResult.ignored();
        }
        // 이미 잠긴 계정은 LockedException 경로라 여기 오지 않지만, 방어적으로 카운트를 올리지 않는다
        if (user.isAccountLocked()) {
            return AttemptResult.ignored();
        }

        userRepository.incrementPasswordFailCount(user.getId());
        User fresh = userRepository.findById(user.getId()).orElse(user);
        int failCount = fresh.getPasswordFailCount();

        if (lockPolicy.reachedThreshold(failCount)) {
            fresh.setAccountLocked(true);
            fresh.setLockedAt(LocalDateTime.now());
            fresh.setLockReason(User.LOCK_REASON_PASSWORD);
            userRepository.save(fresh);
            logger.warn("[LockPolicy] action=lock target={} by=system reason=password-fail-limit count={} threshold={}",
                    username, failCount, lockPolicy.getThreshold());
            return new AttemptResult(Outcome.LOCKED, failCount, 0);
        }
        return new AttemptResult(Outcome.COUNTED, failCount, lockPolicy.getThreshold() - failCount);
    }

    /**
     * 비밀번호 검증 성공 → 누적 카운트 리셋.
     *
     * 정책이 꺼져 있어도 리셋한다: 정책을 잠깐 껐다 켜는 사이 남아 있던 옛 카운트가
     * 다시 켰을 때 곧바로 잠금을 유발하는 것을 막는다.
     * 2차인증(OTP) 모드에서도 1차 통과 시점에 호출된다 — 비밀번호는 이미 맞았기 때문.
     */
    @Transactional
    public void recordSuccess(String username) {
        if (username == null || username.isEmpty()) return;
        userRepository.findByUsername(username).ifPresent(this::resetIfNeeded);
    }

    /** 카운트가 0 이 아닐 때만 UPDATE — 로그인마다 불필요한 쓰기가 생기지 않도록 */
    private void resetIfNeeded(User user) {
        if (user.getPasswordFailCount() > 0) {
            userRepository.resetPasswordFailCount(user.getId());
        }
    }

    /**
     * 잠긴 계정의 사유 문구 (login_history 기록용).
     * lock_reason 이 비어 있으면 비밀번호 잠금 도입 이전의 잠금이므로 OTP 로 해석한다.
     */
    public String lockReasonText(String username) {
        if (username == null || username.isEmpty()) return "잠긴 계정";
        return userRepository.findByUsername(username)
                .map(u -> User.LOCK_REASON_PASSWORD.equals(u.getLockReason())
                        ? "잠긴 계정 (비밀번호 반복 실패)"
                        : "잠긴 계정 (OTP 반복 실패)")
                .orElse("잠긴 계정");
    }
}

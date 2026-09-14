package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * 비밀번호 반복 실패 계정 잠금 정책 설정 서비스.
 *
 * 책임:
 *   - 기능 사용 여부 + 임계 횟수 + 관리자 예외 런타임 보관
 *   - settings.json / application.properties 영속화 hook
 *     (LlmConfigService/RagConfigService/TwoFactorConfigService/PasswordPolicyConfigService 와 동일 3-hook 패턴)
 *
 * 판정/실행(카운트 증가·잠금)은 {@link LoginAttemptService} 가 담당한다 — 설정 보관과 분리.
 * 영속화 트리거(persistSettings 호출)는 호출자(HeapDumpAnalyzerService facade setter)가 담당.
 *
 * OTP 잠금({@link TwoFactorConfigService#OTP_MAX_FAIL}, 고정 10회)과는 별개 축이다:
 * OTP 는 2차인증 단계, 여기는 1차(ID/PW) 단계. 두 경로 모두 users.account_locked 를 공유하고
 * 사유는 users.lock_reason 으로 구분한다.
 */
@Component
public class AccountLockPolicyConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AccountLockPolicyConfigService.class);

    /** 임계 횟수 기본값 — 기존 OTP 잠금(10회)과 동일하게 맞춘다 */
    public static final int DEFAULT_THRESHOLD = 10;
    /** 임계 횟수 하한 — 1회면 오타 한 번에 잠기므로 UI 에서도 3 이상을 권장하지만 하한 자체는 1 */
    public static final int MIN_THRESHOLD = 1;
    /** 임계 횟수 상한 — 사실상 무의미해지는 값 방어 */
    public static final int MAX_THRESHOLD = 100;

    private final HeapDumpConfig config;

    /** 기능 사용 여부. false = 비밀번호를 몇 번 틀려도 잠기지 않음 (종전 동작) */
    private volatile boolean lockoutEnabled = false;
    /** 연속 실패 임계 횟수 */
    private volatile int lockoutThreshold = DEFAULT_THRESHOLD;
    /** 관리자(ADMIN) 자기 잠금 방지 */
    private volatile boolean lockoutAdminExempt = true;

    public AccountLockPolicyConfigService(HeapDumpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.lockoutEnabled = config.isAccountLockoutEnabled();
        this.lockoutThreshold = normalizeThreshold(config.getAccountLockoutThreshold());
        this.lockoutAdminExempt = config.isAccountLockoutAdminExempt();
    }

    // ── Getter ────────────────────────────────────────────────────

    /** 정책 활성화 여부 */
    public boolean isEnabled()      { return lockoutEnabled; }
    public int     getThreshold()   { return lockoutThreshold; }
    public boolean isAdminExempt()  { return lockoutAdminExempt; }

    /** 이 계정의 잠금 예외 대상 여부 (관리자 예외 정책 반영) */
    public boolean isExempt(User user) {
        return user != null && lockoutAdminExempt && user.getRole() == User.Role.ADMIN;
    }

    /** 누적 실패 횟수가 임계에 도달했는가 */
    public boolean reachedThreshold(int failCount) {
        return failCount >= lockoutThreshold;
    }

    // ── Setter ────────────────────────────────────────────────────

    public void setAccountLockPolicy(boolean enabled, int threshold, boolean adminExempt) {
        this.lockoutEnabled = enabled;
        this.lockoutThreshold = normalizeThreshold(threshold);
        this.lockoutAdminExempt = adminExempt;
        logger.info("[LockPolicy] enabled={} threshold={} adminExempt={}",
                this.lockoutEnabled, this.lockoutThreshold, this.lockoutAdminExempt);
    }

    /** 범위를 벗어난 값은 기본값/경계로 보정 (설정 파일 손상·수기 편집 방어) */
    public static int normalizeThreshold(int threshold) {
        if (threshold <= 0) return DEFAULT_THRESHOLD;
        return Math.min(Math.max(threshold, MIN_THRESHOLD), MAX_THRESHOLD);
    }

    // ── Settings 영속화 hook ─────────────────────────────────────

    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("accountLockoutEnabled")) {
            this.lockoutEnabled = Boolean.parseBoolean(String.valueOf(saved.get("accountLockoutEnabled")));
        }
        if (saved.containsKey("accountLockoutThreshold")) {
            try {
                this.lockoutThreshold = normalizeThreshold(
                        Integer.parseInt(String.valueOf(saved.get("accountLockoutThreshold"))));
            } catch (NumberFormatException e) {
                logger.warn("[LockPolicy] settings.json accountLockoutThreshold 값이 숫자가 아님 — 기존 값 유지: {}", saved.get("accountLockoutThreshold"));
            }
        }
        if (saved.containsKey("accountLockoutAdminExempt")) {
            this.lockoutAdminExempt = Boolean.parseBoolean(String.valueOf(saved.get("accountLockoutAdminExempt")));
        }
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("accountLockoutEnabled", lockoutEnabled);
        settings.put("accountLockoutThreshold", lockoutThreshold);
        settings.put("accountLockoutAdminExempt", lockoutAdminExempt);
    }

    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("security.password.lockout-enabled", String.valueOf(lockoutEnabled));
        updates.put("security.password.lockout-threshold", String.valueOf(lockoutThreshold));
        updates.put("security.password.lockout-admin-exempt", String.valueOf(lockoutAdminExempt));
    }
}

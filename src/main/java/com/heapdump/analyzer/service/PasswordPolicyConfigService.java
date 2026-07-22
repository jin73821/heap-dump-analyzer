package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 비밀번호 만료 정책 설정 서비스.
 *
 * 책임:
 *   - 만료 기간(일) + 관리자 예외 여부 런타임 보관
 *   - 특정 계정의 만료 여부/잔여 일수 판정 (판정 기준: passwordChangedAt, 없으면 createdAt 폴백)
 *   - settings.json / application.properties 영속화 hook
 *     (LlmConfigService/RagConfigService/TwoFactorConfigService 와 동일 3-hook 패턴)
 *
 * 영속화 트리거(persistSettings 호출)는 호출자(HeapDumpAnalyzerService facade setter)가 담당.
 */
@Component
public class PasswordPolicyConfigService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordPolicyConfigService.class);

    /**
     * 비밀번호 만료 도래 시 부여되는 부분 인증 role.
     * anyRequest().hasAnyRole("ADMIN","USER") 인가 규칙상 이 role 만으로는 어떤 경로도 접근 불가 →
     * /login/password (강제 변경) 외 접근을 구조적으로 차단 (ROLE_PRE_AUTH 와 동일 철학).
     */
    public static final String ROLE_PWD_EXPIRED = "ROLE_PWD_EXPIRED";

    /** 만료 임박 배지 노출 임계(일) — UI 표기용 */
    public static final int WARN_WITHIN_DAYS = 14;

    /** 만료 기간 상한 (10년) — 잘못된 입력 방어 */
    public static final int MAX_EXPIRY_DAYS = 3650;

    private final HeapDumpConfig config;

    /** 만료 기간(일). 0 이하 = 비활성 */
    private volatile int passwordExpiryDays = 0;
    /** 관리자 만료 예외 */
    private volatile boolean passwordExpiryAdminExempt = true;

    public PasswordPolicyConfigService(HeapDumpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.passwordExpiryDays = normalizeDays(config.getPasswordExpiryDays());
        this.passwordExpiryAdminExempt = config.isPasswordExpiryAdminExempt();
    }

    // ── Getter ────────────────────────────────────────────────────

    public int     getPasswordExpiryDays()   { return passwordExpiryDays; }
    public boolean isAdminExempt()           { return passwordExpiryAdminExempt; }
    /** 만료 정책 활성화 여부 */
    public boolean isEnabled()               { return passwordExpiryDays > 0; }

    // ── 만료 판정 ─────────────────────────────────────────────────

    /** 판정 기준 시각 — passwordChangedAt 우선, 없으면 createdAt 폴백 */
    private static LocalDateTime baseline(User user) {
        if (user == null) return null;
        return user.getPasswordChangedAt() != null ? user.getPasswordChangedAt() : user.getCreatedAt();
    }

    /** 이 계정의 만료 예외 대상 여부 (관리자 예외 정책 반영) */
    public boolean isExempt(User user) {
        return user != null && passwordExpiryAdminExempt && user.getRole() == User.Role.ADMIN;
    }

    /**
     * 비밀번호 만료 여부.
     * 정책 비활성/예외/기준시각 없음 → 항상 false (강제 변경 미유발).
     */
    public boolean isExpired(User user) {
        if (!isEnabled() || isExempt(user)) return false;
        LocalDateTime base = baseline(user);
        if (base == null) return false;
        return base.plusDays(passwordExpiryDays).isBefore(LocalDateTime.now());
    }

    /**
     * 만료까지 남은 일수. 만료됨이면 음수, 정책 비활성/예외/기준없음이면 null(판정 불가).
     * UI 배지(D-n) 및 만료 예정일 표기에 사용.
     */
    public Long daysUntilExpiry(User user) {
        if (!isEnabled() || isExempt(user)) return null;
        LocalDateTime base = baseline(user);
        if (base == null) return null;
        return ChronoUnit.DAYS.between(LocalDateTime.now(), base.plusDays(passwordExpiryDays));
    }

    /** 만료 예정일 (정책 비활성/예외/기준없음이면 null) */
    public LocalDateTime expiresAt(User user) {
        if (!isEnabled() || isExempt(user)) return null;
        LocalDateTime base = baseline(user);
        return base != null ? base.plusDays(passwordExpiryDays) : null;
    }

    // ── Setter ────────────────────────────────────────────────────

    public void setPasswordPolicy(int expiryDays, boolean adminExempt) {
        this.passwordExpiryDays = normalizeDays(expiryDays);
        this.passwordExpiryAdminExempt = adminExempt;
        logger.info("[PwdPolicy] expiryDays={} adminExempt={}", this.passwordExpiryDays, this.passwordExpiryAdminExempt);
    }

    private static int normalizeDays(int days) {
        if (days <= 0) return 0;
        return Math.min(days, MAX_EXPIRY_DAYS);
    }

    // ── Settings 영속화 hook ─────────────────────────────────────

    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("passwordExpiryDays")) {
            try {
                this.passwordExpiryDays = normalizeDays(Integer.parseInt(String.valueOf(saved.get("passwordExpiryDays"))));
            } catch (NumberFormatException ignored) { /* 잘못된 값은 무시 */ }
        }
        if (saved.containsKey("passwordExpiryAdminExempt")) {
            this.passwordExpiryAdminExempt = Boolean.parseBoolean(String.valueOf(saved.get("passwordExpiryAdminExempt")));
        }
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("passwordExpiryDays", passwordExpiryDays);
        settings.put("passwordExpiryAdminExempt", passwordExpiryAdminExempt);
    }

    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("security.password.expiry-days", String.valueOf(passwordExpiryDays));
        updates.put("security.password.expiry-admin-exempt", String.valueOf(passwordExpiryAdminExempt));
    }
}

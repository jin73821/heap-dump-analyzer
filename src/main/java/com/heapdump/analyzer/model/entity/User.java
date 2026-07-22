package com.heapdump.analyzer.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 마지막 비밀번호 변경 시각 — 비밀번호 만료 정책 판정 기준.
     * null 이면 판정 시 createdAt 으로 폴백 (기존 계정 무중단 마이그레이션).
     * 생성 시 onCreate 에서 스탬프, 이후 모든 비밀번호 변경 경로에서 갱신.
     */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "memo", columnDefinition = "MEDIUMTEXT")
    private String memo;

    @Column(name = "memo_updated_at")
    private LocalDateTime memoUpdatedAt;

    @Column(name = "memo_font", length = 20)
    private String memoFont;

    // ── 2차인증 (OTP) / 계정 잠금 ──

    /** TOTP Base32 seed — AesEncryptor ENC(...) 형식으로 암호화 저장. null = 미등록 */
    @Column(name = "otp_secret", length = 512)
    private String otpSecret;

    @Column(name = "otp_enrolled_at")
    private LocalDateTime otpEnrolledAt;

    /** OTP 연속 실패 횟수 (10회 도달 시 accountLocked) */
    @Column(name = "otp_fail_count", nullable = false)
    private int otpFailCount = 0;

    /** 마지막 성공 TOTP timestep — 동일 코드 재사용(replay) 방지 */
    @Column(name = "otp_last_used_step")
    private Long otpLastUsedStep;

    /** OTP 반복 실패 잠금 상태 (enabled 비활성화와 별개) */
    @Column(name = "account_locked", nullable = false)
    private boolean accountLocked = false;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    public enum Role {
        ADMIN, USER
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (passwordChangedAt == null) {
            passwordChangedAt = createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

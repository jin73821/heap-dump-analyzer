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

    /**
     * My Account 페이지 레이아웃 — {@code stack}(계정 정보 위/메모장 아래) | {@code split}(메모장 좌/계정 우).
     * memoFont 와 같은 정책으로 <b>브라우저가 아니라 계정에</b> 저장해 재로그인·다른 기기에서도 유지된다.
     * null(미설정)은 stack 으로 해석 — {@code UserService.accountLayoutOf(user)}.
     */
    @Column(name = "account_layout", length = 10)
    private String accountLayout;

    /**
     * 메모장 자동 저장 토글 — 계정별 영속화(브라우저가 아닌 DB, memoFont 와 동일 정책).
     * null = 미설정 → 기본 ON 으로 해석 (기존 계정 무중단, 유실 방지가 기본값).
     */
    @Column(name = "memo_autosave")
    private Boolean memoAutosave;

    // ── 2차인증 (OTP) / 계정 잠금 ──

    /** 잠금 사유: OTP 코드 반복 실패 */
    public static final String LOCK_REASON_OTP = "OTP";
    /** 잠금 사유: 비밀번호(1차 인증) 반복 실패 */
    public static final String LOCK_REASON_PASSWORD = "PASSWORD";

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

    /** 반복 실패 잠금 상태 (enabled 비활성화와 별개). 사유는 lockReason 참조 */
    @Column(name = "account_locked", nullable = false)
    private boolean accountLocked = false;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /**
     * 잠금 사유 — {@link #LOCK_REASON_OTP} | {@link #LOCK_REASON_PASSWORD}.
     * null = 사유 미기록. 비밀번호 잠금 도입(2026-09-13) 이전의 잠금은 전부 OTP 실패였으므로
     * 표시 계층은 null 을 OTP 로 해석한다.
     */
    @Column(name = "lock_reason", length = 20)
    private String lockReason;

    /** 비밀번호(1차 인증) 연속 실패 횟수 — 정책 임계 도달 시 accountLocked. 성공 시 0 리셋 */
    @Column(name = "password_fail_count", nullable = false)
    private int passwordFailCount = 0;

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

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.UserRepository;
import com.heapdump.analyzer.util.AesEncryptor;
import com.heapdump.analyzer.util.TotpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * OTP(TOTP) 2차인증 비즈니스 로직.
 *   - OTP 코드 검증 (드리프트 ±1 스텝, 재사용 방지, 연속 실패 잠금)
 *   - Seed 등록 확정
 *   - PRE_AUTH → 완전 인증 승격 (SecurityContext 교체 + 세션 저장)
 */
@Service
public class TwoFactorService {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorService.class);

    /** TOTP 검증 허용 드리프트 (현재±1 스텝 = 총 90초 창) */
    private static final int TOTP_WINDOW = 1;

    public enum OtpResult { SUCCESS, INVALID, LOCKED, NOT_ENROLLED, USER_GONE, DISABLED }

    public enum EnrollResult { SUCCESS, INVALID_CODE, USER_GONE }

    /** verifyOtp 결과 + 남은 시도 횟수 */
    public record OtpVerification(OtpResult result, int remainingAttempts) {
        static OtpVerification of(OtpResult r) { return new OtpVerification(r, 0); }
    }

    private final UserRepository userRepository;
    private final CustomUserDetailsService userDetailsService;
    private final LoginHistoryRecorder historyRecorder;
    private final TwoFactorConfigService twoFactorConfig;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public TwoFactorService(UserRepository userRepository,
                            CustomUserDetailsService userDetailsService,
                            LoginHistoryRecorder historyRecorder,
                            TwoFactorConfigService twoFactorConfig) {
        this.userRepository = userRepository;
        this.userDetailsService = userDetailsService;
        this.historyRecorder = historyRecorder;
        this.twoFactorConfig = twoFactorConfig;
    }

    /** OTP seed 등록 여부 */
    public boolean isEnrolled(String username) {
        return userRepository.findByUsername(username)
                .map(u -> u.getOtpSecret() != null && !u.getOtpSecret().isEmpty())
                .orElse(false);
    }

    /**
     * OTP 코드 검증.
     * 실패 시 카운트를 원자 증가시키고 {@link TwoFactorConfigService#OTP_MAX_FAIL} 도달 시 계정을 잠근다.
     */
    @Transactional
    public OtpVerification verifyOtp(String username, String code, HttpServletRequest req) {
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return OtpVerification.of(OtpResult.USER_GONE);
        }
        User user = found.get();
        if (!user.isEnabled()) {
            return OtpVerification.of(OtpResult.DISABLED);
        }
        if (user.isAccountLocked()) {
            return OtpVerification.of(OtpResult.LOCKED);
        }
        if (user.getOtpSecret() == null || user.getOtpSecret().isEmpty()) {
            return OtpVerification.of(OtpResult.NOT_ENROLLED);
        }

        String secret = AesEncryptor.decryptIfEncrypted(user.getOtpSecret());
        long matchedStep = TotpUtil.verify(secret, code, TOTP_WINDOW, user.getOtpLastUsedStep());

        if (matchedStep >= 0) {
            user.setOtpFailCount(0);
            user.setOtpLastUsedStep(matchedStep);
            userRepository.save(user);
            return OtpVerification.of(OtpResult.SUCCESS);
        }

        // 관리자 자기 잠금 방지 정책: 실패해도 잠기지 않음 (카운트 누적 없이 매 실패 리셋)
        boolean adminLockExempt = user.getRole() == User.Role.ADMIN && twoFactorConfig.isAdminLockDisabled();

        // 실패: 원자 증가 후 최신 상태 재조회 (동시 다중 브라우저 카운트 유실 방지)
        userRepository.incrementOtpFailCount(user.getId());
        User fresh = userRepository.findById(user.getId()).orElse(user);

        if (adminLockExempt) {
            fresh.setOtpFailCount(0);   // 관리자: 카운트 누적/잠금 안 함
            userRepository.save(fresh);
            historyRecorder.recordFailure(username, "OTP 코드 불일치 (관리자 잠금 예외)", req);
            return new OtpVerification(OtpResult.INVALID, -1);   // remain 미표시(무제한)
        }

        int failCount = fresh.getOtpFailCount();
        if (failCount >= TwoFactorConfigService.OTP_MAX_FAIL) {
            fresh.setAccountLocked(true);
            fresh.setLockedAt(LocalDateTime.now());
            userRepository.save(fresh);
            historyRecorder.recordFailure(username,
                    "OTP " + TwoFactorConfigService.OTP_MAX_FAIL + "회 연속 실패 — 계정 잠금", req);
            logger.warn("[TwoFactor] action=lock user={} by=system reason=otp-fail-limit", username);
            return OtpVerification.of(OtpResult.LOCKED);
        }
        int remain = TwoFactorConfigService.OTP_MAX_FAIL - failCount;
        historyRecorder.recordFailure(username, "OTP 코드 불일치 (남은 시도 " + remain + "회)", req);
        return new OtpVerification(OtpResult.INVALID, remain);
    }

    /**
     * Seed 등록 확정 — 세션에 보관하던 pending seed 를 첫 코드 검증 성공 시 DB 에 암호화 저장.
     * 등록 단계 실패는 잠금 카운트를 적용하지 않는다 (seed 미확정 상태의 오타는 탈취 신호가 아님).
     */
    @Transactional
    public EnrollResult enroll(String username, String pendingSecretBase32, String code) {
        long matchedStep = TotpUtil.verify(pendingSecretBase32, code, TOTP_WINDOW, null);
        if (matchedStep < 0) {
            return EnrollResult.INVALID_CODE;
        }
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return EnrollResult.USER_GONE;
        }
        User user = found.get();
        try {
            user.setOtpSecret("ENC(" + AesEncryptor.encrypt(pendingSecretBase32) + ")");
        } catch (Exception e) {
            logger.error("[TwoFactor] OTP seed 암호화 실패 — 등록 중단: {}", e.getMessage());
            return EnrollResult.INVALID_CODE;
        }
        user.setOtpEnrolledAt(LocalDateTime.now());
        user.setOtpLastUsedStep(matchedStep);
        user.setOtpFailCount(0);
        userRepository.save(user);
        logger.info("[TwoFactor] action=enroll user={}", username);
        return EnrollResult.SUCCESS;
    }

    /**
     * PRE_AUTH 상태를 완전 인증으로 승격.
     * DB 에서 UserDetails 를 재로드 (OTP 대기 중 비활성화/삭제/잠금 감지) 후
     * SecurityContext 를 교체하고 세션에 명시 저장 (Security 6 explicit save).
     *
     * @return 승격 성공 여부. false 면 호출자가 세션 무효화 + /login redirect 처리.
     */
    public boolean completeAuthentication(HttpServletRequest req, HttpServletResponse res) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null) {
            return false;
        }
        String username = current.getName();
        UserDetails ud;
        try {
            ud = userDetailsService.loadUserByUsername(username);
        } catch (Exception e) {
            logger.warn("[TwoFactor] 완전 인증 승격 실패 — 사용자 재조회 불가: {}", username);
            return false;
        }
        if (!ud.isEnabled() || !ud.isAccountNonLocked()) {
            logger.warn("[TwoFactor] 완전 인증 승격 거부 — enabled={}, nonLocked={}, user={}",
                    ud.isEnabled(), ud.isAccountNonLocked(), username);
            return false;
        }
        UsernamePasswordAuthenticationToken full =
                UsernamePasswordAuthenticationToken.authenticated(ud, null, ud.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(full);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
        // 세션 회전 없음 → login_history sessionId ↔ 활성 세션 매칭 유지
        historyRecorder.recordSuccess(username, req);
        logger.info("[TwoFactor] action=verify-success user={}", username);
        return true;
    }
}

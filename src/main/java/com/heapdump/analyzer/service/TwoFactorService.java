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

    /**
     * 로그인 최종 확정 결과.
     *   COMPLETE    — 완전 인증 승격 완료 → "/"
     *   PWD_EXPIRED — 2FA 통과했으나 비밀번호 만료 → 강제 변경(/login/password)
     *   FAILED      — 사용자 재조회 불가/비활성/잠금 → 세션 무효화
     */
    public enum LoginCompletion { COMPLETE, PWD_EXPIRED, FAILED }

    /** verifyOtp 결과 + 남은 시도 횟수 */
    public record OtpVerification(OtpResult result, int remainingAttempts) {
        static OtpVerification of(OtpResult r) { return new OtpVerification(r, 0); }
    }

    private final UserRepository userRepository;
    private final CustomUserDetailsService userDetailsService;
    private final LoginHistoryRecorder historyRecorder;
    private final TwoFactorConfigService twoFactorConfig;
    private final PasswordPolicyConfigService passwordPolicy;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public TwoFactorService(UserRepository userRepository,
                            CustomUserDetailsService userDetailsService,
                            LoginHistoryRecorder historyRecorder,
                            TwoFactorConfigService twoFactorConfig,
                            PasswordPolicyConfigService passwordPolicy) {
        this.userRepository = userRepository;
        this.userDetailsService = userDetailsService;
        this.historyRecorder = historyRecorder;
        this.twoFactorConfig = twoFactorConfig;
        this.passwordPolicy = passwordPolicy;
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

        // seed 복호화 실패/손상 시 fail-closed — NOT_ENROLLED 로 떨어뜨리면 OTP 우회 여지가 생긴다.
        // 잠금 카운트는 올리지 않는다: 사용자 잘못이 아니고, 관리자 OTP 초기화 전엔 어차피 성공할 수 없다.
        AesEncryptor.Decrypted seed = AesEncryptor.decryptIfEncryptedChecked(user.getOtpSecret());
        if (!seed.healthy()) {
            logger.error("[TwoFactor] OTP seed 손상 — username={}, {} (관리자 OTP 초기화 필요)",
                    user.getUsername(), seed.issue());
            return OtpVerification.of(OtpResult.INVALID);
        }
        String secret = seed.value();
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
     * PRE_AUTH 상태를 완전 인증으로 승격 (OTP/SSO 최종 확정 지점).
     * DB 에서 UserDetails 를 재로드 (OTP 대기 중 비활성화/삭제/잠금 감지) 후
     * SecurityContext 를 교체하고 세션에 명시 저장 (Security 6 explicit save).
     *
     * 2FA 통과 = 로그인 성공으로 기록(sessionId 포함, 활성 세션 매칭용). 이후 비밀번호 만료면
     * 완전 인증 대신 ROLE_PWD_EXPIRED 부분 인증으로 교체해 강제 변경 페이지로 유도한다.
     *
     * @return {@link LoginCompletion}. FAILED 면 호출자가 세션 무효화 + /login redirect 처리.
     */
    public LoginCompletion completeAuthentication(HttpServletRequest req, HttpServletResponse res) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null) {
            return LoginCompletion.FAILED;
        }
        String username = current.getName();
        UserDetails ud;
        try {
            ud = userDetailsService.loadUserByUsername(username);
        } catch (Exception e) {
            logger.warn("[TwoFactor] 완전 인증 승격 실패 — 사용자 재조회 불가: {}", username);
            return LoginCompletion.FAILED;
        }
        if (!ud.isEnabled() || !ud.isAccountNonLocked()) {
            logger.warn("[TwoFactor] 완전 인증 승격 거부 — enabled={}, nonLocked={}, user={}",
                    ud.isEnabled(), ud.isAccountNonLocked(), username);
            return LoginCompletion.FAILED;
        }
        // 세션 회전 없음 → login_history sessionId ↔ 활성 세션 매칭 유지
        historyRecorder.recordSuccess(username, req);

        User user = userRepository.findByUsername(username).orElse(null);
        if (user != null && passwordPolicy.isExpired(user)) {
            establishPasswordExpiredContext(username, req, res);
            logger.info("[TwoFactor] action=verify-success user={} pwdExpired=true → 강제 변경", username);
            return LoginCompletion.PWD_EXPIRED;
        }

        UsernamePasswordAuthenticationToken full =
                UsernamePasswordAuthenticationToken.authenticated(ud, null, ud.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(full);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
        logger.info("[TwoFactor] action=verify-success user={}", username);
        return LoginCompletion.COMPLETE;
    }

    // ── 비밀번호 만료 강제 변경 게이트 (Task2) ──────────────────────

    /**
     * 로그인 최종 완료 시점(2FA 미사용/관리자 예외)에서 비밀번호 만료면 부분 인증으로 강등.
     * 폼 로그인 필터가 이미 완전 인증을 세팅한 상태에서 SuccessHandler 가 호출 → 교체 저장.
     *
     * @return true = 만료로 강제 변경 필요(호출자가 /login/password redirect), false = 정상.
     */
    public boolean forcePasswordChangeIfExpired(String username, HttpServletRequest req, HttpServletResponse res) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordPolicy.isExpired(user)) {
            return false;
        }
        establishPasswordExpiredContext(username, req, res);
        logger.info("[PwdPolicy] password expired at login — forcing change: user={}", username);
        return true;
    }

    /** SecurityContext 를 ROLE_PWD_EXPIRED 부분 인증 토큰으로 교체 + 세션 저장 */
    private void establishPasswordExpiredContext(String username, HttpServletRequest req, HttpServletResponse res) {
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                username, null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        PasswordPolicyConfigService.ROLE_PWD_EXPIRED)));
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
    }

    /**
     * 강제 비밀번호 변경 완료 후 ROLE_PWD_EXPIRED → 완전 인증 승격.
     * 로그인 성공은 2FA/1차 통과 시 이미 기록됨 → 재기록하지 않음.
     *
     * @return 승격 성공 여부. false 면 호출자가 세션 무효화 처리.
     */
    public boolean upgradeAfterPasswordChange(HttpServletRequest req, HttpServletResponse res) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null) {
            return false;
        }
        String username = current.getName();
        UserDetails ud;
        try {
            ud = userDetailsService.loadUserByUsername(username);
        } catch (Exception e) {
            logger.warn("[PwdPolicy] 변경 후 승격 실패 — 사용자 재조회 불가: {}", username);
            return false;
        }
        if (!ud.isEnabled() || !ud.isAccountNonLocked()) {
            return false;
        }
        UsernamePasswordAuthenticationToken full =
                UsernamePasswordAuthenticationToken.authenticated(ud, null, ud.getAuthorities());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(full);
        SecurityContextHolder.setContext(ctx);
        contextRepository.saveContext(ctx, req, res);
        logger.info("[PwdPolicy] action=change-success user={} — 완전 인증 승격", username);
        return true;
    }
}

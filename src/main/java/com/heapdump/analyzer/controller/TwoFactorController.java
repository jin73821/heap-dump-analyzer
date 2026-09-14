package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.TwoFactorAuthenticationSuccessHandler;
import com.heapdump.analyzer.service.TwoFactorConfigService;
import com.heapdump.analyzer.service.TwoFactorService;
import com.heapdump.analyzer.util.QrCodeUtil;
import com.heapdump.analyzer.util.TotpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * OTP 2차인증 페이지 (검증 + Seed 등록).
 * ROLE_PRE_AUTH 부분 인증 상태에서만 진입 — 완전 인증자는 "/", 익명은 entry point 가 "/login" 처리.
 * 폼 POST 는 /api/ 밖 경로라 CSRF 보호 자동 적용 (Thymeleaf th:action 이 hidden 토큰 삽입).
 */
@Controller
public class TwoFactorController {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorController.class);

    /** Seed 등록 확정 전 임시 seed 세션 키 (검증 성공 전 DB 미저장) */
    private static final String PENDING_SECRET_ATTR = "TFA_PENDING_SECRET";
    private static final String OTP_ISSUER = "Heap Dump Analyzer";
    private static final int QR_SIZE_PX = 220;

    private final TwoFactorConfigService twoFactorConfig;
    private final TwoFactorService twoFactorService;

    public TwoFactorController(TwoFactorConfigService twoFactorConfig, TwoFactorService twoFactorService) {
        this.twoFactorConfig = twoFactorConfig;
        this.twoFactorService = twoFactorService;
    }

    // ── OTP 검증 ─────────────────────────────────────────────────

    @GetMapping("/login/otp")
    public String otpPage(Authentication auth, HttpServletRequest req, HttpServletResponse res,
                          @RequestParam(value = "error", required = false) String error,
                          @RequestParam(value = "remain", required = false) Integer remain,
                          Model model) {
        String redirect = guardOrComplete(auth, req, res);
        if (redirect != null) return redirect;
        if (!twoFactorService.isEnrolled(auth.getName())) {
            return "redirect:/login/otp/setup";
        }
        if ("invalid".equals(error)) {
            model.addAttribute("errorMessage", "OTP 코드가 올바르지 않습니다."
                    + (remain != null ? " (남은 시도 " + remain + "회)" : ""));
            model.addAttribute("remain", remain);
        }
        model.addAttribute("username", auth.getName());
        return "login-otp";
    }

    @PostMapping("/login/otp")
    public String verifyOtp(Authentication auth, HttpServletRequest req, HttpServletResponse res,
                            @RequestParam("code") String code) {
        String redirect = guardOrComplete(auth, req, res);
        if (redirect != null) return redirect;

        TwoFactorService.OtpVerification v = twoFactorService.verifyOtp(auth.getName(), code, req);
        switch (v.result()) {
            case SUCCESS:
                return completeOrFallback(req, res);
            case LOCKED:
                invalidateSession(req);
                return "redirect:/login?error=locked";
            case INVALID:
                // remain < 0 (관리자 잠금 예외) 은 남은 시도 미표시
                return v.remainingAttempts() >= 0
                        ? "redirect:/login/otp?error=invalid&remain=" + v.remainingAttempts()
                        : "redirect:/login/otp?error=invalid";
            case NOT_ENROLLED:
                return "redirect:/login/otp/setup";
            case DISABLED:
                invalidateSession(req);
                return "redirect:/login?error=disabled";
            case USER_GONE:
            default:
                invalidateSession(req);
                return "redirect:/login?error=true";
        }
    }

    // ── Seed 등록 ─────────────────────────────────────────────────

    @GetMapping("/login/otp/setup")
    public String setupPage(Authentication auth, HttpServletRequest req, HttpServletResponse res,
                            @RequestParam(value = "renew", required = false) String renew,
                            @RequestParam(value = "error", required = false) String error,
                            Model model) {
        String redirect = guardOrComplete(auth, req, res);
        if (redirect != null) return redirect;
        if (twoFactorService.isEnrolled(auth.getName())) {
            return "redirect:/login/otp";
        }
        HttpSession session = req.getSession(false);
        if (session == null) {
            return "redirect:/login";
        }
        String pending = (String) session.getAttribute(PENDING_SECRET_ATTR);
        if (pending == null || "1".equals(renew)) {
            pending = TotpUtil.generateSecretBase32();
            session.setAttribute(PENDING_SECRET_ATTR, pending);
        }
        String otpAuthUri = TotpUtil.buildOtpAuthUri(OTP_ISSUER, auth.getName(), pending);
        model.addAttribute("qrDataUri", QrCodeUtil.toPngDataUri(otpAuthUri, QR_SIZE_PX));
        model.addAttribute("manualCode", TotpUtil.groupForDisplay(pending));
        model.addAttribute("username", auth.getName());
        if ("invalid".equals(error)) {
            model.addAttribute("errorMessage", "OTP 코드가 올바르지 않습니다. 앱에 표시된 최신 코드를 다시 입력하세요.");
        }
        // seed 노출 최소화 — 브라우저/프록시 캐시 금지
        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        return "login-otp-setup";
    }

    @PostMapping("/login/otp/setup")
    public String confirmSetup(Authentication auth, HttpServletRequest req, HttpServletResponse res,
                               @RequestParam("code") String code) {
        String redirect = guardOrComplete(auth, req, res);
        if (redirect != null) return redirect;
        if (twoFactorService.isEnrolled(auth.getName())) {
            return "redirect:/login/otp";
        }
        HttpSession session = req.getSession(false);
        String pending = session != null ? (String) session.getAttribute(PENDING_SECRET_ATTR) : null;
        if (pending == null) {
            return "redirect:/login/otp/setup";
        }
        TwoFactorService.EnrollResult result = twoFactorService.enroll(auth.getName(), pending, code);
        switch (result) {
            case SUCCESS:
                session.removeAttribute(PENDING_SECRET_ATTR);
                return completeOrFallback(req, res);
            case INVALID_CODE:
                // seed 유지 — 등록 단계 오타는 잠금 카운트 미적용
                return "redirect:/login/otp/setup?error=invalid";
            case USER_GONE:
            default:
                invalidateSession(req);
                return "redirect:/login?error=true";
        }
    }

    // ── 공통 가드/헬퍼 ────────────────────────────────────────────

    /**
     * PRE_AUTH 상태 검증.
     * 완전 인증자 → "/" / OTP 모드 해제됨 → 저장된 1차 인증으로 즉시 승격 / PRE_AUTH → null (계속 진행).
     */
    private String guardOrComplete(Authentication auth, HttpServletRequest req, HttpServletResponse res) {
        if (auth == null) {
            return "redirect:/login";
        }
        boolean preAuth = auth.getAuthorities().stream()
                .anyMatch(a -> TwoFactorAuthenticationSuccessHandler.ROLE_PRE_AUTH.equals(a.getAuthority()));
        if (!preAuth) {
            return "redirect:/";
        }
        if (!twoFactorConfig.isOtpMode()) {
            // OTP 대기 중 관리자가 모드를 변경 — 1차 인증은 유효하므로 즉시 완전 인증 승격
            logger.info("[TwoFactor] OTP 모드 해제 감지 — 1차 인증으로 즉시 승격: user={}", auth.getName());
            return completeOrFallback(req, res);
        }
        return null;
    }

    private String completeOrFallback(HttpServletRequest req, HttpServletResponse res) {
        switch (twoFactorService.completeAuthentication(req, res)) {
            case COMPLETE:
                return "redirect:/";
            case PWD_EXPIRED:
                // 2FA 통과했으나 비밀번호 만료 → 강제 변경 페이지로 유도
                return "redirect:/login/password";
            case FAILED:
            default:
                invalidateSession(req);
                return "redirect:/login?error=true";
        }
    }

    private void invalidateSession(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException e) {
                logger.debug("세션이 이미 무효화됨: {}", e.getMessage());
            }
        }
    }
}

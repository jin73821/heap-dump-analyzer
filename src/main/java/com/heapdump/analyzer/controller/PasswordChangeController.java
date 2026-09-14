package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.PasswordPolicyConfigService;
import com.heapdump.analyzer.service.TwoFactorService;
import com.heapdump.analyzer.service.UserService;
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
 * 비밀번호 만료 강제 변경 페이지 (/login/password).
 *
 * ROLE_PWD_EXPIRED 부분 인증 상태에서만 진입 — 완전 인증자는 "/", 익명은 "/login".
 * OTP 모드에서는 OTP 통과 후 이 상태로 진입하므로 "2차인증을 통해 비밀번호를 바꾸는" 요구를 충족한다.
 * 폼 POST 는 /api/ 밖 경로라 CSRF 보호 자동 적용 (Thymeleaf th:action 이 hidden 토큰 삽입).
 */
@Controller
public class PasswordChangeController {

    private static final Logger logger = LoggerFactory.getLogger(PasswordChangeController.class);

    private final TwoFactorService twoFactorService;
    private final PasswordPolicyConfigService passwordPolicy;
    private final UserService userService;

    public PasswordChangeController(TwoFactorService twoFactorService,
                                    PasswordPolicyConfigService passwordPolicy,
                                    UserService userService) {
        this.twoFactorService = twoFactorService;
        this.passwordPolicy = passwordPolicy;
        this.userService = userService;
    }

    @GetMapping("/login/password")
    public String page(Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        String redirect = guard(auth, req, res);
        if (redirect != null) return redirect;
        model.addAttribute("username", auth.getName());
        model.addAttribute("expiryDays", passwordPolicy.getPasswordExpiryDays());
        // 만료 seed/토큰 노출 최소화 — 캐시 금지
        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        return "login-password-expired";
    }

    @PostMapping("/login/password")
    public String change(Authentication auth, HttpServletRequest req, HttpServletResponse res,
                         @RequestParam("currentPassword") String currentPassword,
                         @RequestParam("newPassword") String newPassword,
                         @RequestParam("confirmPassword") String confirmPassword,
                         Model model) {
        String redirect = guard(auth, req, res);
        if (redirect != null) return redirect;

        try {
            if (newPassword == null || confirmPassword == null || !newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("새 비밀번호와 확인 입력이 일치하지 않습니다.");
            }
            // 현재 PW 검증 + 복잡도 + 동일 PW 차단 + passwordChangedAt 갱신(만료 리셋)
            userService.changeOwnPassword(auth.getName(), currentPassword, newPassword);
        } catch (IllegalArgumentException e) {
            // 재렌더(리다이렉트 아님) — GlobalExceptionHandler 우회 + 한글 메시지 그대로 노출
            model.addAttribute("username", auth.getName());
            model.addAttribute("expiryDays", passwordPolicy.getPasswordExpiryDays());
            model.addAttribute("errorMessage", e.getMessage());
            return "login-password-expired";
        }

        // 변경 성공 → ROLE_PWD_EXPIRED 를 완전 인증으로 승격 (세션 회전 없음)
        if (twoFactorService.upgradeAfterPasswordChange(req, res)) {
            logger.info("[PwdPolicy] forced change completed: user={}", auth.getName());
            return "redirect:/";
        }
        invalidateSession(req);
        return "redirect:/login?error=true";
    }

    /**
     * PWD_EXPIRED 부분 인증 상태 검증.
     * 완전 인증자 → "/" / 만료 정책 비활성화됨(관리자가 끔) → 즉시 승격 후 "/" / 익명 → "/login" /
     * PWD_EXPIRED → null (계속 진행).
     */
    private String guard(Authentication auth, HttpServletRequest req, HttpServletResponse res) {
        if (auth == null) {
            return "redirect:/login";
        }
        boolean pwdExpired = auth.getAuthorities().stream()
                .anyMatch(a -> PasswordPolicyConfigService.ROLE_PWD_EXPIRED.equals(a.getAuthority()));
        if (!pwdExpired) {
            return "redirect:/";
        }
        if (!passwordPolicy.isEnabled()) {
            // 강제 변경 대기 중 관리자가 만료 정책을 비활성화 — 1차/2차 인증은 유효하므로 즉시 승격
            logger.info("[PwdPolicy] expiry policy disabled while pending — 즉시 승격: user={}", auth.getName());
            if (twoFactorService.upgradeAfterPasswordChange(req, res)) {
                return "redirect:/";
            }
            invalidateSession(req);
            return "redirect:/login?error=true";
        }
        return null;
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

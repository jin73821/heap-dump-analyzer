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
 * 로그인 직후 강제 비밀번호 변경 (/login/password) — 만료 또는 관리자 '비밀번호 초기화'(2026-11-27).
 * 화면은 로그인 카드 위의 닫을 수 없는 모달이다(login-password-expired.html).
 * 사유별 입력: 초기화({@code reason=reset}) = 새 비밀번호 + 확인(방금 1차·OTP 를 통과했으므로 현재 비밀번호를 다시 묻지 않는다),
 * 만료({@code reason=expired}) = 현재 + 새 + 확인(종전 그대로). 사유는 매 요청 DB 에서 다시 판정한다.
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
        fillModel(model, auth.getName());
        // 만료 seed/토큰 노출 최소화 — 캐시 금지
        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        res.setHeader("Pragma", "no-cache");
        return "login-password-expired";
    }

    @PostMapping("/login/password")
    public String change(Authentication auth, HttpServletRequest req, HttpServletResponse res,
                         @RequestParam(value = "currentPassword", required = false) String currentPassword,
                         @RequestParam("newPassword") String newPassword,
                         @RequestParam("confirmPassword") String confirmPassword,
                         Model model) {
        String redirect = guard(auth, req, res);
        if (redirect != null) return redirect;

        try {
            if (newPassword == null || confirmPassword == null || !newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("새 비밀번호와 확인 입력이 일치하지 않습니다.");
            }
            if (isResetRequired(auth.getName())) {
                // 관리자 초기화 → 새 비밀번호만(복잡도 + 기존과 다름 + 표시 해제). 표시가 없으면 서비스가 거부한다
                userService.setNewPasswordAfterReset(auth.getName(), newPassword);
            } else {
                // 만료 → 현재 PW 검증 + 복잡도 + 동일 PW 차단 + passwordChangedAt 갱신(만료 리셋)
                userService.changeOwnPassword(auth.getName(), currentPassword, newPassword);
            }
        } catch (IllegalArgumentException e) {
            // 재렌더(리다이렉트 아님) — GlobalExceptionHandler 우회 + 한글 메시지 그대로 노출
            fillModel(model, auth.getName());
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
        if (!passwordPolicy.mustChangeAtLogin(userService.findByUsername(auth.getName()).orElse(null))) {
            // 강제 변경 대기 중 사유가 사라짐(관리자가 만료 정책을 끔·'비밀번호 수정'으로 직접 정함) — 1차/2차 인증은 유효하므로 즉시 승격
            logger.info("[PwdPolicy] change no longer required while pending — 즉시 승격: user={}", auth.getName());
            if (twoFactorService.upgradeAfterPasswordChange(req, res)) {
                return "redirect:/";
            }
            invalidateSession(req);
            return "redirect:/login?error=true";
        }
        return null;
    }

    private boolean isResetRequired(String username) {
        return userService.isPasswordChangeRequired(username);
    }

    /** 모달 문구·입력 칸 — reason: reset(관리자 초기화, 새 + 확인) | expired(만료, 현재 + 새 + 확인). */
    private void fillModel(Model model, String username) {
        model.addAttribute("username", username);
        model.addAttribute("expiryDays", passwordPolicy.getPasswordExpiryDays());
        model.addAttribute("reason", isResetRequired(username) ? "reset" : "expired");
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

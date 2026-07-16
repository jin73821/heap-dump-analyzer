package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.TwoFactorConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final TwoFactorConfigService twoFactorConfig;

    public AuthController(TwoFactorConfigService twoFactorConfig) {
        this.twoFactorConfig = twoFactorConfig;
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "logout", required = false) String logout,
                            @RequestParam(value = "expired", required = false) String expired,
                            @RequestParam(value = "ssoNotice", required = false) String ssoNotice,
                            Model model) {
        if ("disabled".equals(error)) {
            model.addAttribute("disabledMessage", "비활성화된 계정입니다. 관리자에게 문의하세요.");
        } else if ("locked".equals(error)) {
            model.addAttribute("disabledMessage", "계정이 잠겼습니다 (OTP 반복 실패). 관리자에게 잠금 해제를 문의하세요.");
        } else if (error != null) {
            model.addAttribute("errorMessage", "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        if (logout != null) {
            model.addAttribute("logoutMessage", "로그아웃 되었습니다.");
        }
        if (expired != null) {
            model.addAttribute("expiredMessage", "세션이 만료되어 자동으로 로그아웃 되었습니다. 다시 로그인해 주세요.");
        }
        if (ssoNotice != null) {
            model.addAttribute("logoutMessage", "SSO 연동은 사내 가이드 확인 후 제공 예정입니다. ID/PW 로그인을 이용해 주세요.");
        }
        // mode=sso 일 때만 로그인 화면에 SSO 로그인 버튼 표시 (off/otp 는 기존 화면 그대로)
        model.addAttribute("twoFactorMode", twoFactorConfig.getTwoFactorMode());
        return "login";
    }
}

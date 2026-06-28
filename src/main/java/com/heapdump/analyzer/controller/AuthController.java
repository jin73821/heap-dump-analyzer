package com.heapdump.analyzer.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "logout", required = false) String logout,
                            @RequestParam(value = "expired", required = false) String expired,
                            Model model) {
        if ("disabled".equals(error)) {
            model.addAttribute("disabledMessage", "비활성화된 계정입니다. 관리자에게 문의하세요.");
        } else if (error != null) {
            model.addAttribute("errorMessage", "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        if (logout != null) {
            model.addAttribute("logoutMessage", "로그아웃 되었습니다.");
        }
        if (expired != null) {
            model.addAttribute("expiredMessage", "세션이 만료되어 자동으로 로그아웃 되었습니다. 다시 로그인해 주세요.");
        }
        return "login";
    }
}

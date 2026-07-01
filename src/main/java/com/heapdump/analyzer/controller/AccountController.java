package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * /account — 본인 계정 자기서비스 페이지 (계정 정보 / 비밀번호 변경 / 개인 메모장).
 * 모든 mutation 은 Principal.getName() 으로 본인 username 을 확정해
 * 다른 사용자의 데이터에 접근할 수 있는 경로를 차단한다.
 */
@Controller
public class AccountController {

    private final UserService userService;

    public AccountController(UserService userService) {
        this.userService = userService;
    }

    // ── 페이지 ────────────────────────────────────────────────

    @GetMapping("/account")
    public String accountPage(Principal principal, Model model) {
        String username = principal.getName();
        User user = userService.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        model.addAttribute("user", user);
        return "account";
    }

    // ── 비밀번호 변경 ─────────────────────────────────────────

    @PostMapping("/api/account/password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> body,
                                                              Principal principal) {
        String current = body.get("currentPassword");
        String next    = body.get("newPassword");
        String confirm = body.get("confirmPassword");
        if (next == null || confirm == null || !next.equals(confirm)) {
            throw new IllegalArgumentException("새 비밀번호와 확인 입력이 일치하지 않습니다.");
        }
        userService.changeOwnPassword(principal.getName(), current, next);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        return ResponseEntity.ok(res);
    }

    // ── 메모장 ──────────────────────────────────────────────

    @GetMapping("/api/account/memo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMemo(Principal principal) {
        User user = userService.getOwnMemo(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        Map<String, Object> res = new HashMap<>();
        res.put("memo", user.getMemo() == null ? "" : user.getMemo());
        res.put("memoUpdatedAt", user.getMemoUpdatedAt());
        res.put("memoFont", user.getMemoFont() == null ? "d2coding" : user.getMemoFont());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/account/memo-font")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveMemoFont(@RequestBody Map<String, String> body,
                                                            Principal principal) {
        userService.saveMemoFont(principal.getName(), body.get("font"));
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/account/memo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveMemo(@RequestBody Map<String, String> body,
                                                        Principal principal) {
        LocalDateTime updatedAt = userService.saveMemo(principal.getName(), body.get("memo"));
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("memoUpdatedAt", updatedAt);
        return ResponseEntity.ok(res);
    }

    @DeleteMapping("/api/account/memo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> clearMemo(Principal principal) {
        userService.clearMemo(principal.getName());
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        return ResponseEntity.ok(res);
    }
}

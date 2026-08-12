package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.MemoHistory;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.service.MemoHistoryService;
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
    private final MemoHistoryService memoHistoryService;
    private final com.heapdump.analyzer.service.PasswordPolicyConfigService passwordPolicy;

    public AccountController(UserService userService,
                            MemoHistoryService memoHistoryService,
                            com.heapdump.analyzer.service.PasswordPolicyConfigService passwordPolicy) {
        this.userService = userService;
        this.memoHistoryService = memoHistoryService;
        this.passwordPolicy = passwordPolicy;
    }

    // ── 페이지 ────────────────────────────────────────────────

    @GetMapping("/account")
    public String accountPage(Principal principal, Model model) {
        String username = principal.getName();
        User user = userService.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        model.addAttribute("user", user);
        model.addAttribute("memoAutosave", UserService.isMemoAutosaveOn(user));
        // 레이아웃은 서버가 렌더 시점에 <html> 클래스로 내려준다 — 클라이언트 스크립트로 적용하면 화면이 한 번 튄다
        model.addAttribute("accountLayout", UserService.accountLayoutOf(user));
        // 비밀번호 만료 정책 상태 (본인 계정 기준) — 표시 문자열은 컨트롤러에서 조립(Thymeleaf 단순화)
        boolean pwEnabled = passwordPolicy.isEnabled() && !passwordPolicy.isExempt(user);
        boolean pwExpired = passwordPolicy.isExpired(user);
        java.time.LocalDateTime expiresAt = passwordPolicy.expiresAt(user);
        Long daysLeft = passwordPolicy.daysUntilExpiry(user);
        String pwExpiryText = null;
        if (pwEnabled && expiresAt != null) {
            pwExpiryText = expiresAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " 만료"
                    + (daysLeft != null ? " (D-" + daysLeft + ")" : "");
        }
        // 만료 7일 이하(또는 이미 만료) + 정책 활성 시에만 상단 경고 뱃지 표기 (미설정 시 미표기)
        boolean pwWarnSoon = pwEnabled && daysLeft != null && daysLeft <= 7;
        model.addAttribute("pwExpiryEnabled", pwEnabled);
        model.addAttribute("pwExpired", pwExpired);
        model.addAttribute("pwExpiryText", pwExpiryText);
        model.addAttribute("pwWarnSoon", pwWarnSoon);
        model.addAttribute("pwDaysLeft", daysLeft);
        return "account";
    }

    /**
     * /account/memo — 개인 메모장 새창(팝업) 전용 자립형 페이지.
     * 과거에는 about:blank 팝업에 document.write 로 마크업만 넣고 저장 로직은 부모(/account)
     * 문서의 함수를 호출했다. 부모가 다른 페이지로 이동하면 부모 Document 와 그 JS 컨텍스트가
     * 폐기돼 팝업의 저장이 조용히 실패했다. 이 라우트는 팝업이 자기 스크립트로
     * /api/account/memo 를 직접 호출하게 해 opener 생존 여부와 무관하게 만든다.
     */
    @GetMapping("/account/memo")
    public String accountMemoPage(Principal principal, Model model) {
        User user = userService.findByUsername(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        model.addAttribute("user", user);
        model.addAttribute("memoAutosave", UserService.isMemoAutosaveOn(user));
        return "account-memo";
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

    // ── OTP 초기화 (자기서비스) ────────────────────────────────

    @PostMapping("/api/account/otp-reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetOwnOtp(@RequestBody Map<String, String> body,
                                                           Principal principal) {
        userService.resetOwnOtp(principal.getName(), body.get("currentPassword"));
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
        res.put("memoAutosave", UserService.isMemoAutosaveOn(user));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/account/memo-autosave")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveMemoAutosave(@RequestBody Map<String, Object> body,
                                                                Principal principal) {
        boolean on = Boolean.TRUE.equals(body.get("autosave")) || "true".equals(String.valueOf(body.get("autosave")));
        userService.saveMemoAutosave(principal.getName(), on);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("autosave", on);
        return ResponseEntity.ok(res);
    }

    /** My Account 레이아웃 저장 (본인 계정). stack | split 외의 값은 400. */
    @PostMapping("/api/account/layout")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveAccountLayout(@RequestBody Map<String, String> body,
                                                                 Principal principal) {
        userService.saveAccountLayout(principal.getName(), body.get("layout"));
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("layout", body.get("layout"));
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

    // ── 메모 변경 이력 ───────────────────────────────────────
    // 모든 엔드포인트가 principal 기준 본인 것만 다룬다. 소유권 검증은
    // MemoHistoryService.require() 가 쿼리(findByIdAndUsername)로 수행하므로
    // id 를 바꿔 넣어도 남의 스냅샷에 접근할 수 없다.

    @GetMapping("/api/account/memo/history")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> memoHistoryList(Principal principal) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("enabled", memoHistoryService.isEnabled());
        res.put("retentionDays", memoHistoryService.getRetentionDays());
        res.put("maxPerUser", memoHistoryService.getMaxPerUser());
        res.put("items", memoHistoryService.list(principal.getName()));
        return ResponseEntity.ok(res);
    }

    @GetMapping("/api/account/memo/history/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> memoHistoryDetail(@PathVariable Long id, Principal principal) {
        MemoHistory h = memoHistoryService.require(principal.getName(), id);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("id", h.getId());
        res.put("memo", h.getMemo() == null ? "" : h.getMemo());
        res.put("createdAt", h.getCreatedAt());
        res.put("byteSize", h.getByteSize());
        res.put("reason", h.getReason());
        return ResponseEntity.ok(res);
    }

    /** 해당 시점으로 복원. 복원 직전 내용도 이력에 남아 다시 되돌릴 수 있다. */
    @PostMapping("/api/account/memo/history/{id}/restore")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> memoHistoryRestore(@PathVariable Long id, Principal principal) {
        UserService.RestoredMemo restored = userService.restoreMemo(principal.getName(), id);
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("memo", restored.memo());
        res.put("memoUpdatedAt", restored.memoUpdatedAt());
        return ResponseEntity.ok(res);
    }

    @DeleteMapping("/api/account/memo/history")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> memoHistoryClear(Principal principal) {
        memoHistoryService.deleteAll(principal.getName());
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        return ResponseEntity.ok(res);
    }
}

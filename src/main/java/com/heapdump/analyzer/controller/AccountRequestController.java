package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AccountRequest;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.service.AccountRequestService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.heapdump.analyzer.util.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AccountRequestController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AccountRequestService service;
    private final RateLimiter signupLimiter = new RateLimiter(5, 60_000);

    public AccountRequestController(AccountRequestService service) {
        this.service = service;
    }

    @PostMapping("/api/account-requests")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String ip = clientIp(request);
        if (!signupLimiter.isAllowed(ip)) {
            result.put("success", false);
            result.put("message", "요청이 너무 많습니다. 잠시 후 다시 시도하세요.");
            return ResponseEntity.status(429).body(result);
        }
        try {
            String username = nz(body.get("username"));
            String password = body.get("password");
            String displayName = nz(body.get("displayName"));
            String reason = nz(body.get("reason"));
            service.submit(username, password, displayName, reason, ip);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "신청 처리 중 오류가 발생했습니다.");
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/api/admin/account-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {

        int safeSize = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<AccountRequest> result = service.list(status, q, pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (AccountRequest r : result.getContent()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("username", r.getUsername());
            m.put("displayName", r.getDisplayName());
            m.put("reason", r.getReason());
            m.put("status", r.getStatus().name());
            m.put("requestedAt", r.getRequestedAt() != null ? r.getRequestedAt().format(DT_FMT) : null);
            m.put("processedAt", r.getProcessedAt() != null ? r.getProcessedAt().format(DT_FMT) : null);
            m.put("processedBy", r.getProcessedBy());
            m.put("rejectReason", r.getRejectReason());
            m.put("requestIp", r.getRequestIp());
            items.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("pendingCount", service.pendingCount());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/admin/account-requests/pending-count")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> pendingCount() {
        Map<String, Object> body = new HashMap<>();
        body.put("count", service.pendingCount());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/admin/account-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, String> body,
                                                       Principal principal) {
        Map<String, Object> result = new HashMap<>();
        try {
            User.Role role = User.Role.USER;
            if (body != null && body.get("role") != null) {
                try { role = User.Role.valueOf(body.get("role")); }
                catch (IllegalArgumentException ignored) {}
            }
            String approver = principal != null ? principal.getName() : "unknown";
            service.approve(id, role, approver);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/api/admin/account-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, String> body,
                                                      Principal principal) {
        Map<String, Object> result = new HashMap<>();
        try {
            String reason = body != null ? body.get("reason") : null;
            String approver = principal != null ? principal.getName() : "unknown";
            service.reject(id, reason, approver);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @DeleteMapping("/api/admin/account-requests/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            service.deleteRequest(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanupRateLimiter() {
        signupLimiter.evictExpired();
    }

    private static String nz(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isEmpty()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isEmpty()) return real;
        return req.getRemoteAddr();
    }
}

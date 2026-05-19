package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.LoginHistory;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.LoginHistoryRepository;
import com.heapdump.analyzer.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserService userService;
    private final LoginHistoryRepository loginHistoryRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public AdminController(UserService userService,
                           LoginHistoryRepository loginHistoryRepository,
                           JdbcTemplate jdbcTemplate) {
        this.userService = userService;
        this.loginHistoryRepository = loginHistoryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/admin/users")
    public String usersPage(Model model) {
        List<User> users = userService.findAll();
        model.addAttribute("users", users);
        return "admin/users";
    }

    @GetMapping("/api/admin/users")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : userService.findAll()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("role", u.getRole().name());
            m.put("enabled", u.isEnabled());
            m.put("createdAt", u.getCreatedAt() != null
                    ? u.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null);
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/admin/users")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = body.get("username");
            String password = body.get("password");
            String displayName = body.get("displayName");
            String roleStr = body.getOrDefault("role", "USER");
            User.Role role = User.Role.valueOf(roleStr);

            User user = userService.createUser(username, password, displayName, role);
            result.put("success", true);
            result.put("userId", user.getId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PutMapping("/api/admin/users/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long id,
                                                          @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String displayName = body.get("displayName");
            User.Role role = body.containsKey("role") ? User.Role.valueOf(body.get("role")) : null;
            Boolean enabled = body.containsKey("enabled") ? Boolean.valueOf(body.get("enabled")) : null;

            userService.updateUser(id, displayName, role, enabled);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/api/admin/users/{id}/reset-password")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@PathVariable Long id,
                                                             @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String newPassword = body.get("password");
            userService.resetPassword(id, newPassword);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @DeleteMapping("/api/admin/users/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            userService.deleteUser(id);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/api/admin/login-history")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> loginHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {

        int safeSize = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "loginAt"));

        Specification<LoginHistory> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.trim().isEmpty()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("ip")), like)
                ));
            }
            if (status != null && !status.isEmpty()) {
                try {
                    ps.add(cb.equal(root.get("status"), LoginHistory.Status.valueOf(status)));
                } catch (IllegalArgumentException ignored) {}
            }
            return ps.isEmpty() ? null : cb.and(ps.toArray(new Predicate[0]));
        };

        Page<LoginHistory> result = loginHistoryRepository.findAll(spec, pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (LoginHistory h : result.getContent()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", h.getId());
            m.put("username", h.getUsername());
            m.put("loginAt", h.getLoginAt() != null ? h.getLoginAt().format(DT_FMT) : null);
            m.put("ip", h.getIp());
            m.put("userAgent", h.getUserAgent());
            m.put("status", h.getStatus() != null ? h.getStatus().name() : null);
            m.put("sessionId", h.getSessionId());
            m.put("failureReason", h.getFailureReason());
            items.add(m);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/admin/active-sessions")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> activeSessions(HttpServletRequest request) {
        String currentSessionId = (request.getSession(false) != null) ? request.getSession(false).getId() : null;

        // SPRING_SESSION + login_history(가장 최근 SUCCESS) + users 조인
        // login_history는 session_id 단위로 1행 가정, 다중 매칭 시 최신 1건만 사용
        String sql =
                "SELECT s.SESSION_ID, s.PRINCIPAL_NAME, s.CREATION_TIME, s.LAST_ACCESS_TIME, " +
                "       s.MAX_INACTIVE_INTERVAL, s.EXPIRY_TIME, " +
                "       u.display_name AS DISPLAY_NAME, u.role AS ROLE_NAME, " +
                "       (SELECT lh.ip FROM login_history lh WHERE lh.session_id = s.SESSION_ID " +
                "          AND lh.status = 'SUCCESS' ORDER BY lh.login_at DESC LIMIT 1) AS IP, " +
                "       (SELECT lh.user_agent FROM login_history lh WHERE lh.session_id = s.SESSION_ID " +
                "          AND lh.status = 'SUCCESS' ORDER BY lh.login_at DESC LIMIT 1) AS USER_AGENT, " +
                "       (SELECT lh.login_at FROM login_history lh WHERE lh.session_id = s.SESSION_ID " +
                "          AND lh.status = 'SUCCESS' ORDER BY lh.login_at DESC LIMIT 1) AS LOGIN_AT " +
                "  FROM SPRING_SESSION s " +
                "  LEFT JOIN users u ON u.username = s.PRINCIPAL_NAME " +
                " WHERE s.PRINCIPAL_NAME IS NOT NULL " +
                "   AND s.EXPIRY_TIME > ? " +
                " ORDER BY s.LAST_ACCESS_TIME DESC";

        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();

        List<Map<String, Object>> items = jdbcTemplate.query(sql, (rs, rn) -> {
            Map<String, Object> m = new HashMap<>();
            String sid = rs.getString("SESSION_ID");
            m.put("sessionId", sid);
            m.put("username", rs.getString("PRINCIPAL_NAME"));
            m.put("displayName", rs.getString("DISPLAY_NAME"));
            m.put("role", rs.getString("ROLE_NAME"));
            m.put("ip", rs.getString("IP"));
            m.put("userAgent", rs.getString("USER_AGENT"));

            long creation = rs.getLong("CREATION_TIME");
            long lastAccess = rs.getLong("LAST_ACCESS_TIME");
            long expiry = rs.getLong("EXPIRY_TIME");
            int maxInactive = rs.getInt("MAX_INACTIVE_INTERVAL");

            m.put("createdAt", LocalDateTime.ofInstant(Instant.ofEpochMilli(creation), zone).format(DT_FMT));
            m.put("lastAccessAt", LocalDateTime.ofInstant(Instant.ofEpochMilli(lastAccess), zone).format(DT_FMT));
            m.put("expiresAt", LocalDateTime.ofInstant(Instant.ofEpochMilli(expiry), zone).format(DT_FMT));
            m.put("maxInactiveSec", maxInactive);
            m.put("idleSec", Math.max(0, (now - lastAccess) / 1000));

            java.sql.Timestamp loginAt = rs.getTimestamp("LOGIN_AT");
            m.put("loginAt", loginAt != null
                    ? loginAt.toLocalDateTime().format(DT_FMT) : null);

            m.put("isCurrent", sid != null && sid.equals(currentSessionId));
            return m;
        }, now);

        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("totalElements", items.size());
        body.put("currentSessionId", currentSessionId);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/api/admin/active-sessions/{sessionId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> terminateSession(@PathVariable String sessionId,
                                                                HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String currentSessionId = (request.getSession(false) != null)
                    ? request.getSession(false).getId() : null;
            if (sessionId.equals(currentSessionId)) {
                result.put("success", false);
                result.put("message", "본인의 세션은 강제 종료할 수 없습니다.");
                return ResponseEntity.badRequest().body(result);
            }

            int deleted;
            if (sessionRepository != null) {
                // Spring Session 표준 경로 (속성 테이블까지 정합성 유지)
                sessionRepository.deleteById(sessionId);
                deleted = 1;
            } else {
                // 폴백: 직접 삭제 (자식 → 부모 순)
                jdbcTemplate.update(
                        "DELETE FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID IN " +
                                "(SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?)",
                        sessionId);
                deleted = jdbcTemplate.update(
                        "DELETE FROM SPRING_SESSION WHERE SESSION_ID = ?", sessionId);
            }

            result.put("success", deleted > 0);
            if (deleted == 0) {
                result.put("message", "이미 만료되었거나 존재하지 않는 세션입니다.");
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}

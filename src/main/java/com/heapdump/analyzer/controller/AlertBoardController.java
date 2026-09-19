package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.AlertBoardService;
import com.heapdump.analyzer.util.AuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 알림 현황판 — {@code /alerts} 페이지 + {@code /api/alerts/**}.
 * ACK 는 모든 사용자가 공유한다(USER 도 확인·취소 가능 — 감사 로그 {@code [Alert] action=… by=} 로 추적).
 * SecurityConfig 무변경: 페이지·API 모두 {@code anyRequest().hasAnyRole}, {@code /api/**} CSRF 면제.
 */
@Controller
public class AlertBoardController {

    private static final Logger logger = LoggerFactory.getLogger(AlertBoardController.class);

    private final AlertBoardService alertBoardService;

    public AlertBoardController(AlertBoardService alertBoardService) {
        this.alertBoardService = alertBoardService;
    }

    private static String who(Authentication auth) {
        return auth != null ? auth.getName() : "unknown";
    }

    @GetMapping("/alerts")
    public String page(Authentication authentication, Model model) {
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        AlertBoardService.Board b = alertBoardService.board(isAdmin);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("kpi", b.kpi);
        model.addAttribute("rows", b.rows);
        model.addAttribute("alerts", b.alerts);
        model.addAttribute("unackedCount", b.unackedCount);
        model.addAttribute("deletedCount", b.deletedCount);
        return "alerts";
    }

    @GetMapping("/api/alerts/top")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> top(@RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.putAll(alertBoardService.top(Math.max(1, Math.min(50, limit))));
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/alerts/ack")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> ack(@RequestBody(required = false) Map<String, Object> body,
                                                   Authentication authentication) {
        String key = keyOf(body);
        boolean fresh = alertBoardService.ack(key, who(authentication));
        logger.info("[Alert] action=ack key={} already={} by={}", key, !fresh, who(authentication));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("key", key);
        res.put("already", !fresh);
        res.put("unackedCount", alertBoardService.unacked().size());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/alerts/unack")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unack(@RequestBody(required = false) Map<String, Object> body,
                                                     Authentication authentication) {
        String key = keyOf(body);
        String prevBy = alertBoardService.unack(key);
        logger.info("[Alert] action=unack key={} removed={} prevBy={} by={}", key, prevBy != null, prevBy, who(authentication));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("key", key);
        res.put("removed", prevBy != null);
        res.put("unackedCount", alertBoardService.unacked().size());
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/alerts/ack-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> ackAll(Authentication authentication) {
        int n = alertBoardService.ackAll(who(authentication));
        logger.info("[Alert] action=ack-all count={} by={}", n, who(authentication));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("acked", n);
        res.put("unackedCount", alertBoardService.unacked().size());
        return ResponseEntity.ok(res);
    }

    private static String keyOf(Map<String, Object> body) {
        Object k = body != null ? body.get("key") : null;
        if (!(k instanceof String) || ((String) k).isBlank()) {
            throw new IllegalArgumentException("알람 key 가 없습니다.");
        }
        return (String) k;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", false);
        res.put("code", "ALERT_BAD_REQUEST");
        res.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(res);
    }
}

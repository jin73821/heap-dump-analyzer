package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.AlertBoardService;
import com.heapdump.analyzer.util.AuthUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.view.FragmentsRendering;

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
        populateBoardModel(authentication, model);
        return "alerts";
    }

    /**
     * 부분 갱신 조각(2026-09-26) — 페이지와 같은 템플릿의 {@code th:fragment} 3개(KPI·알람 목록·최근 분석 표)를
     * 한 응답으로 돌려준다. {@code /js/live-refresh.js} 가 설정 주기(Settings &gt; General '화면 자동 갱신 주기')로 받아
     * id 가 같은 영역만 갈아 끼운다. ⚠ 경로는 {@code /api/} 접두여야 한다 — 세션 만료 시 401 JSON 을 받아야
     * 클라이언트가 만료로 판정한다(페이지 경로면 302→로그인 HTML 이 200 으로 와 조용히 실패, 함정 27).
     */
    @GetMapping(value = "/api/alerts/fragments", produces = MediaType.TEXT_HTML_VALUE)
    public FragmentsRendering fragments(Authentication authentication, Model model) {
        populateBoardModel(authentication, model);
        return FragmentsRendering.fragment("alerts :: alertKpi")
                .fragment("alerts :: alertList")
                .fragment("alerts :: alertRows")
                .header("Cache-Control", "no-store")
                .build();
    }

    private void populateBoardModel(Authentication authentication, Model model) {
        boolean isAdmin = AuthUtil.isAdmin(authentication);
        AlertBoardService.Board b = alertBoardService.board(isAdmin);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("kpi", b.kpi);
        model.addAttribute("rows", b.rows);
        model.addAttribute("alerts", b.alerts);
        model.addAttribute("unackedCount", b.unackedCount);
        model.addAttribute("deletedCount", b.deletedCount);
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
        // 확인자·시각은 서버가 정한 값이 진실이다(이미 다른 사용자가 확인해 뒀을 수 있다).
        // 화면은 이걸로 해당 행만 고쳐 그린다 — 종전처럼 페이지를 통째로 새로고침하지 않는다.
        res.put("ack", alertBoardService.ackInfo(key));
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
        res.put("acks", alertBoardService.ackInfoAll());   // 화면이 행마다 확인자·시각을 그대로 그린다
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

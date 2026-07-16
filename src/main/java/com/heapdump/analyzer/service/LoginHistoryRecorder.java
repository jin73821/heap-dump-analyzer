package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.LoginHistory;
import com.heapdump.analyzer.repository.LoginHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

/**
 * login_history 기록 공용 컴포넌트.
 * AuthEventListener(폼 로그인 이벤트)와 TwoFactorService(OTP 단계 성공/실패)가 공유한다.
 * ip/userAgent/truncate 헬퍼는 기존 AuthEventListener 의 private 메서드를 추출한 것.
 */
@Component
public class LoginHistoryRecorder {

    private static final Logger logger = LoggerFactory.getLogger(LoginHistoryRecorder.class);

    private final LoginHistoryRepository repository;

    public LoginHistoryRecorder(LoginHistoryRepository repository) {
        this.repository = repository;
    }

    /** 완전 인증 성공 기록 (sessionId 포함 — 활성 세션 매칭용) */
    public void recordSuccess(String username, HttpServletRequest req) {
        try {
            LoginHistory h = new LoginHistory();
            h.setUsername(truncate(username, 100));
            h.setLoginAt(LocalDateTime.now());
            h.setStatus(LoginHistory.Status.SUCCESS);
            if (req != null) {
                h.setIp(truncate(extractIp(req), 64));
                h.setUserAgent(truncate(req.getHeader("User-Agent"), 512));
                if (req.getSession(false) != null) {
                    h.setSessionId(truncate(req.getSession(false).getId(), 64));
                }
            }
            repository.save(h);
        } catch (Exception e) {
            logger.warn("[LoginHistory] 성공 기록 실패: {}", e.toString());
        }
    }

    /** 인증 실패 기록 (사유 포함) */
    public void recordFailure(String username, String reason, HttpServletRequest req) {
        try {
            LoginHistory h = new LoginHistory();
            h.setUsername(truncate(username, 100));
            h.setLoginAt(LocalDateTime.now());
            h.setStatus(LoginHistory.Status.FAILURE);
            h.setFailureReason(truncate(reason, 200));
            if (req != null) {
                h.setIp(truncate(extractIp(req), 64));
                h.setUserAgent(truncate(req.getHeader("User-Agent"), 512));
            }
            repository.save(h);
        } catch (Exception e) {
            logger.warn("[LoginHistory] 실패 기록 실패: {}", e.toString());
        }
    }

    public static String extractIp(HttpServletRequest req) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String v = req.getHeader(h);
            if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return comma > 0 ? v.substring(0, comma).trim() : v.trim();
            }
        }
        return req.getRemoteAddr();
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

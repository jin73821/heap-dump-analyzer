package com.heapdump.analyzer.listener;

import com.heapdump.analyzer.model.entity.LoginHistory;
import com.heapdump.analyzer.repository.LoginHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

@Component
public class AuthEventListener {

    private static final Logger logger = LoggerFactory.getLogger(AuthEventListener.class);

    private final LoginHistoryRepository repository;

    public AuthEventListener(LoginHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * InteractiveAuthenticationSuccessEvent는 AbstractAuthenticationProcessingFilter#successfulAuthentication()
     * 안에서 세션 고정 보호(session fixation protection)가 새 세션을 발급한 *이후*에 발행됩니다.
     * AuthenticationSuccessEvent를 쓰면 기존(폐기 예정) 세션 ID가 잡혀 SPRING_SESSION과 매칭되지 않습니다.
     */
    @EventListener
    public void onSuccess(InteractiveAuthenticationSuccessEvent event) {
        try {
            Authentication auth = event.getAuthentication();
            String username = auth != null ? auth.getName() : "unknown";
            HttpServletRequest req = currentRequest();
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

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        try {
            String username = "unknown";
            if (event.getAuthentication() != null && event.getAuthentication().getName() != null) {
                username = event.getAuthentication().getName();
            }
            HttpServletRequest req = currentRequest();
            LoginHistory h = new LoginHistory();
            h.setUsername(truncate(username, 100));
            h.setLoginAt(LocalDateTime.now());
            h.setStatus(LoginHistory.Status.FAILURE);
            Exception ex = event.getException();
            String reason;
            if (ex instanceof DisabledException) {
                reason = "비활성화된 계정";
            } else if (ex instanceof BadCredentialsException) {
                reason = "자격 증명에 실패하였습니다.";
            } else {
                reason = ex != null ? ex.getMessage() : event.getClass().getSimpleName();
            }
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

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractIp(HttpServletRequest req) {
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

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

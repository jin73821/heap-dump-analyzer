package com.heapdump.analyzer.listener;

import com.heapdump.analyzer.service.LoginHistoryRecorder;
import com.heapdump.analyzer.service.TwoFactorConfigService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class AuthEventListener {

    private final LoginHistoryRecorder recorder;
    private final TwoFactorConfigService twoFactorConfig;

    public AuthEventListener(LoginHistoryRecorder recorder, TwoFactorConfigService twoFactorConfig) {
        this.recorder = recorder;
        this.twoFactorConfig = twoFactorConfig;
    }

    /**
     * InteractiveAuthenticationSuccessEvent는 AbstractAuthenticationProcessingFilter#successfulAuthentication()
     * 안에서 세션 고정 보호(session fixation protection)가 새 세션을 발급한 *이후*에 발행됩니다.
     * AuthenticationSuccessEvent를 쓰면 기존(폐기 예정) 세션 ID가 잡혀 SPRING_SESSION과 매칭되지 않습니다.
     *
     * OTP 모드에서는 1차(ID/PW) 통과 시점이라 SUCCESS 로 기록하지 않는다 —
     * "SUCCESS = 완전 인증 완료" 의미 보존. OTP 최종 성공은 TwoFactorService 가 기록.
     */
    @EventListener
    public void onSuccess(InteractiveAuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        // OTP 모드에서 실제로 OTP 단계를 거치는 경우엔 여기서 기록하지 않는다 (완전 인증 성공은 TwoFactorService 가 기록).
        // 단, 관리자 OTP 예외(exempt)라 OTP 없이 바로 완전 인증되는 경우는 여기가 최종 성공 지점이므로 기록.
        if (twoFactorConfig.isOtpMode() && !(isAdmin(auth) && twoFactorConfig.isAdminExempt())) {
            return;
        }
        String username = auth != null ? auth.getName() : "unknown";
        recorder.recordSuccess(username, currentRequest());
    }

    private static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = "unknown";
        if (event.getAuthentication() != null && event.getAuthentication().getName() != null) {
            username = event.getAuthentication().getName();
        }
        Exception ex = event.getException();
        String reason;
        if (ex instanceof DisabledException) {
            reason = "비활성화된 계정";
        } else if (ex instanceof LockedException) {
            reason = "잠긴 계정 (OTP 반복 실패)";
        } else if (ex instanceof BadCredentialsException) {
            reason = "자격 증명에 실패하였습니다.";
        } else {
            reason = ex != null ? ex.getMessage() : event.getClass().getSimpleName();
        }
        recorder.recordFailure(username, reason, currentRequest());
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

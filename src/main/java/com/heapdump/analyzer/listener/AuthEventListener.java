package com.heapdump.analyzer.listener;

import com.heapdump.analyzer.service.LoginAttemptService;
import com.heapdump.analyzer.service.LoginHistoryRecorder;
import com.heapdump.analyzer.service.TwoFactorConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(AuthEventListener.class);

    private final LoginHistoryRecorder recorder;
    private final TwoFactorConfigService twoFactorConfig;
    private final LoginAttemptService loginAttempts;

    public AuthEventListener(LoginHistoryRecorder recorder,
                             TwoFactorConfigService twoFactorConfig,
                             LoginAttemptService loginAttempts) {
        this.recorder = recorder;
        this.twoFactorConfig = twoFactorConfig;
        this.loginAttempts = loginAttempts;
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
        String username = auth != null ? auth.getName() : "unknown";

        // 비밀번호 실패 카운트 리셋은 login_history 기록보다 앞이다 —
        // 이 이벤트는 OTP 모드에서도 1차(ID/PW) 통과 시점에 발행되고, 그 시점에 비밀번호는 이미 맞았다.
        // 아래 OTP early-return 뒤에 두면 OTP 모드에서 카운트가 영원히 리셋되지 않는다.
        try {
            loginAttempts.recordSuccess(username);
        } catch (Exception e) {
            // 카운트 리셋 실패가 로그인 자체를 막아서는 안 된다 (다음 성공 때 다시 리셋된다)
            logger.warn("[LockPolicy] 실패 카운트 리셋 실패 — user={}: {}", username, e.toString());
        }

        // OTP 모드에서 실제로 OTP 단계를 거치는 경우엔 여기서 기록하지 않는다 (완전 인증 성공은 TwoFactorService 가 기록).
        // 단, 관리자 OTP 예외(exempt)라 OTP 없이 바로 완전 인증되는 경우는 여기가 최종 성공 지점이므로 기록.
        if (twoFactorConfig.isOtpMode() && !(isAdmin(auth) && twoFactorConfig.isAdminExempt())) {
            return;
        }
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
            // 잠금 사유는 OTP/비밀번호 두 갈래 — lock_reason 을 읽어 구분한다
            reason = lockReasonTextSafe(username);
        } else if (ex instanceof BadCredentialsException) {
            reason = countFailureSafe(username);
        } else {
            reason = ex != null ? ex.getMessage() : event.getClass().getSimpleName();
        }
        recorder.recordFailure(username, reason, currentRequest());
    }

    /**
     * 비밀번호 실패 누적 + 임계 도달 시 잠금 (정책 비활성이면 no-op).
     *
     * DB 쓰기가 실패해도 로그인 처리 자체는 원래대로 진행돼야 하므로 여기서 흡수한다 —
     * 이 예외를 흘리면 "비밀번호가 틀렸다"가 500 오류 화면으로 바뀐다. 대신 경고를 남긴다
     * (잠기지 않는 쪽으로 열리는 실패이므로 조용히 넘어가면 안 된다).
     */
    private String countFailureSafe(String username) {
        String base = "자격 증명에 실패하였습니다.";
        try {
            LoginAttemptService.AttemptResult r = loginAttempts.recordFailure(username);
            if (r.locked()) {
                return base + " (비밀번호 " + r.failCount() + "회 연속 실패 — 계정 잠금)";
            }
            if (r.counted()) {
                return base + " (남은 시도 " + r.remaining() + "회)";
            }
            return base;
        } catch (Exception e) {
            logger.warn("[LockPolicy] 실패 카운트 누적 실패 — user={}: {}", username, e.toString());
            return base;
        }
    }

    private String lockReasonTextSafe(String username) {
        try {
            return loginAttempts.lockReasonText(username);
        } catch (Exception e) {
            logger.warn("[LockPolicy] 잠금 사유 조회 실패 — user={}: {}", username, e.toString());
            return "잠긴 계정";
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
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.util.SecretValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * 로그인 2차인증 설정 서비스.
 *
 * 책임:
 *   - 2차인증 모드 (off | otp | sso) + SSO 연동 4 필드 런타임 보관
 *   - ssoClientSecret 의 AES 암호화 처리 (settings.json/properties 에는 ENC(...))
 *   - settings.json / application.properties 영속화 hook (LlmConfigService/RagConfigService 와 동일 3-hook 패턴)
 *
 * 영속화 트리거(persistSettings 호출)는 호출자(HeapDumpAnalyzerService facade setter)가 담당.
 */
@Component
public class TwoFactorConfigService {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorConfigService.class);

    public static final String MODE_OFF = "off";
    public static final String MODE_OTP = "otp";
    public static final String MODE_SSO = "sso";

    /** 관리자 OTP 정책 */
    public static final String ADMIN_ENFORCE          = "enforce";          // 일반 사용자와 동일 (실패 시 잠금)
    public static final String ADMIN_ENFORCE_NO_LOCK  = "enforce_no_lock";  // OTP 필수 + 자기 잠금 방지 (기본)
    public static final String ADMIN_EXEMPT           = "exempt";           // 관리자 OTP 예외

    /** OTP 연속 실패 잠금 임계치 */
    public static final int OTP_MAX_FAIL = 10;

    private final HeapDumpConfig config;

    /** 2차인증 모드: off | otp | sso */
    private volatile String twoFactorMode = MODE_OFF;

    /** 관리자 OTP 정책: enforce | enforce_no_lock | exempt */
    private volatile String twoFactorAdminPolicy = ADMIN_ENFORCE_NO_LOCK;

    // ── 커스텀 SSO 연동 필드 (사내 가이드 확정 전 틀) ──
    private volatile String ssoEndpointUrl;
    private volatile String ssoClientId;
    private final SecretValue ssoClientSecret = SecretValue.empty();  // settings.json 에는 ENC
    private volatile String ssoRedirectUri;

    public TwoFactorConfigService(HeapDumpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.twoFactorMode = normalizeMode(config.getTwoFactorMode());
        this.twoFactorAdminPolicy = normalizeAdminPolicy(config.getTwoFactorAdminPolicy());
        this.ssoEndpointUrl = config.getSsoEndpointUrl();
        this.ssoClientId = config.getSsoClientId();
        adoptSecret(config.getSsoClientSecret());
        this.ssoRedirectUri = config.getSsoRedirectUri();
    }

    // ── Getter ────────────────────────────────────────────────────

    public String  getTwoFactorMode()  { return twoFactorMode; }
    public boolean isOtpMode()         { return MODE_OTP.equals(twoFactorMode); }
    public boolean isSsoMode()         { return MODE_SSO.equals(twoFactorMode); }
    public String  getSsoEndpointUrl() { return ssoEndpointUrl; }
    public String  getSsoClientId()    { return ssoClientId; }
    public String  getSsoClientSecret(){ return ssoClientSecret.usable(); }
    public String  getSsoRedirectUri() { return ssoRedirectUri; }

    public boolean isSsoClientSecretSet() {
        return ssoClientSecret.isSet();
    }

    public boolean isSsoClientSecretHealthy() { return ssoClientSecret.isHealthy(); }
    public String  getSsoClientSecretIssue()  {
        return ssoClientSecret.issue() != null ? ssoClientSecret.issue() : "";
    }

    /**
     * SSO 연동 활성화 가능 여부 — 필수 3필드(Endpoint URL·Client ID·Client Secret) 모두 저장됨.
     * 손상된 secret 으로는 활성화할 수 없다 (인증이 조용히 실패하는 것보다 낫다).
     */
    public boolean isSsoConfigured() {
        return ssoEndpointUrl != null && !ssoEndpointUrl.trim().isEmpty()
                && ssoClientId != null && !ssoClientId.trim().isEmpty()
                && isSsoClientSecretSet()
                && ssoClientSecret.isHealthy();
    }

    // ── 관리자 OTP 정책 ──────────────────────────────────────────

    public String getTwoFactorAdminPolicy() { return twoFactorAdminPolicy; }

    /** 관리자는 OTP 를 아예 거치지 않음 */
    public boolean isAdminExempt() { return ADMIN_EXEMPT.equals(twoFactorAdminPolicy); }

    /** 관리자는 OTP 실패로 잠기지 않음 (exempt 는 애초에 OTP 미진입이지만 방어적으로 포함) */
    public boolean isAdminLockDisabled() {
        return ADMIN_EXEMPT.equals(twoFactorAdminPolicy) || ADMIN_ENFORCE_NO_LOCK.equals(twoFactorAdminPolicy);
    }

    // ── Setter ────────────────────────────────────────────────────

    public void setTwoFactorMode(String mode) {
        this.twoFactorMode = normalizeMode(mode);
        logger.info("[TwoFactor] mode={}", this.twoFactorMode);
    }

    public void setTwoFactorAdminPolicy(String policy) {
        this.twoFactorAdminPolicy = normalizeAdminPolicy(policy);
        logger.info("[TwoFactor] adminPolicy={}", this.twoFactorAdminPolicy);
    }

    /**
     * SSO 연동 설정 일괄 업데이트.
     * clientSecret 은 null 이면 기존 값 유지, 빈 문자열이면 삭제, 그 외는 새 값으로 교체 (RAG password 시맨틱).
     */
    public void setSsoConfig(String endpointUrl, String clientId, String clientSecret, String redirectUri) {
        this.ssoEndpointUrl = trimOrEmpty(endpointUrl);
        this.ssoClientId = trimOrEmpty(clientId);
        if (clientSecret != null) this.ssoClientSecret.set(clientSecret);
        this.ssoRedirectUri = trimOrEmpty(redirectUri);
        logger.info("[TwoFactor] sso config updated: endpointUrl={}, clientId={}, redirectUri={}, secretSet={}",
                ssoEndpointUrl, ssoClientId, ssoRedirectUri, isSsoClientSecretSet());
    }

    private static String normalizeMode(String mode) {
        if (mode == null) return MODE_OFF;
        String m = mode.trim().toLowerCase();
        return (m.equals(MODE_OTP) || m.equals(MODE_SSO)) ? m : MODE_OFF;
    }

    private static String normalizeAdminPolicy(String policy) {
        if (policy == null) return ADMIN_ENFORCE_NO_LOCK;
        String p = policy.trim().toLowerCase();
        return (p.equals(ADMIN_ENFORCE) || p.equals(ADMIN_EXEMPT)) ? p : ADMIN_ENFORCE_NO_LOCK;
    }

    private static String trimOrEmpty(String s) { return s == null ? "" : s.trim(); }

    // ── Settings 영속화 hook ─────────────────────────────────────

    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("twoFactorMode")) {
            this.twoFactorMode = normalizeMode(String.valueOf(saved.get("twoFactorMode")));
        }
        if (saved.containsKey("twoFactorAdminPolicy")) {
            this.twoFactorAdminPolicy = normalizeAdminPolicy(String.valueOf(saved.get("twoFactorAdminPolicy")));
        }
        if (saved.containsKey("ssoEndpointUrl")) {
            this.ssoEndpointUrl = String.valueOf(saved.get("ssoEndpointUrl"));
        }
        if (saved.containsKey("ssoClientId")) {
            this.ssoClientId = String.valueOf(saved.get("ssoClientId"));
        }
        if (saved.containsKey("ssoClientSecret")) {
            adoptSecret(String.valueOf(saved.get("ssoClientSecret")));
        }
        if (saved.containsKey("ssoRedirectUri")) {
            this.ssoRedirectUri = String.valueOf(saved.get("ssoRedirectUri"));
        }
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("twoFactorMode", twoFactorMode);
        settings.put("twoFactorAdminPolicy", twoFactorAdminPolicy);
        settings.put("ssoEndpointUrl", ssoEndpointUrl != null ? ssoEndpointUrl : "");
        settings.put("ssoClientId", ssoClientId != null ? ssoClientId : "");
        putSecret(settings, "ssoClientSecret", ssoClientSecret);
        settings.put("ssoRedirectUri", ssoRedirectUri != null ? ssoRedirectUri : "");
    }

    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("security.two-factor.mode", twoFactorMode);
        updates.put("security.two-factor.admin-policy", twoFactorAdminPolicy);
        updates.put("security.sso.endpoint-url", ssoEndpointUrl != null ? ssoEndpointUrl : "");
        updates.put("security.sso.client-id", ssoClientId != null ? ssoClientId : "");
        putSecret(updates, "security.sso.client-secret", ssoClientSecret);
        updates.put("security.sso.redirect-uri", ssoRedirectUri != null ? ssoRedirectUri : "");
    }

    /**
     * 시크릿을 저장 맵에 기록. 암호화 실패(forStorage()==null)면 <b>키 자체를 생략</b>해
     * 기존 저장값을 보존한다 — 빈 문자열로 덮으면 시크릿이 무경고로 삭제된다.
     */
    private static <T> void putSecret(Map<String, T> target, String key, SecretValue secret) {
        String stored = secret.forStorage();
        if (stored == null) {
            logger.error("[Settings] '{}' AES 암호화 실패 — 키를 기록하지 않고 기존 저장값을 유지합니다", key);
            return;
        }
        @SuppressWarnings("unchecked")
        T value = (T) stored;
        target.put(key, value);
    }

    /** 저장값을 SecretValue 에 로드하고 손상 시 경고. 예외를 던지지 않는다. */
    private void adoptSecret(String stored) {
        SecretValue loaded = SecretValue.load(stored);
        ssoClientSecret.adoptFrom(loaded);
        if (!loaded.isHealthy()) {
            logger.warn("[TwoFactor] 저장된 SSO client secret 을 사용할 수 없습니다 — {} (재입력 필요)",
                    loaded.issue());
        }
    }
}

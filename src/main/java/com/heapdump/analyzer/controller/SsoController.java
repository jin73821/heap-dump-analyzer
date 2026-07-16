package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.service.TwoFactorConfigService;
import com.heapdump.analyzer.service.sso.SsoAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 커스텀 SSO 연동 스텁 엔드포인트 (틀).
 *
 * 현재는 mode=sso 여도 "사내 가이드 확인 후 연동 예정" 안내로 리다이렉트한다.
 * 실제 연동 시 (사내 가이드 확정 후):
 *   - /sso/login    : state 생성/세션 저장 → ssoAuthenticator.buildAuthorizationUrl(state) 로 redirect
 *   - /sso/callback : state 검증 → ssoAuthenticator.authenticate(code, state) → users 매칭 →
 *                     SecurityContext 확립 (TwoFactorService.completeAuthentication 승격 로직 재사용 가능)
 */
@Controller
public class SsoController {

    private static final Logger logger = LoggerFactory.getLogger(SsoController.class);

    private final TwoFactorConfigService twoFactorConfig;
    private final SsoAuthenticator ssoAuthenticator;

    public SsoController(TwoFactorConfigService twoFactorConfig, SsoAuthenticator ssoAuthenticator) {
        this.twoFactorConfig = twoFactorConfig;
        this.ssoAuthenticator = ssoAuthenticator;
    }

    @GetMapping("/sso/login")
    public String ssoLogin() {
        if (!twoFactorConfig.isSsoMode()) {
            return "redirect:/login";
        }
        // TODO(사내 SSO 가이드 확정 시): state 생성 후 ssoAuthenticator.buildAuthorizationUrl(state) redirect
        logger.info("[TwoFactor] sso login 요청 — 스텁 응답 (가이드 확정 전). endpointUrl={}",
                twoFactorConfig.getSsoEndpointUrl());
        return "redirect:/login?ssoNotice=1";
    }

    @GetMapping("/sso/callback")
    public String ssoCallback(@RequestParam(value = "code", required = false) String code,
                              @RequestParam(value = "state", required = false) String state) {
        if (!twoFactorConfig.isSsoMode()) {
            return "redirect:/login";
        }
        // TODO(사내 SSO 가이드 확정 시): state 검증 → ssoAuthenticator.authenticate(code, state)
        //   → users 테이블 매칭 → SecurityContext 확립 → redirect:/
        logger.info("[TwoFactor] sso callback 수신 — 스텁 응답 (가이드 확정 전). codePresent={}", code != null);
        return "redirect:/login?ssoNotice=1";
    }
}

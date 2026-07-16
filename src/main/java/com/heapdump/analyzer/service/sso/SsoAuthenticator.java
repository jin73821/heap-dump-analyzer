package com.heapdump.analyzer.service.sso;

/**
 * 커스텀 SSO 연동 인터페이스 (틀).
 *
 * 사내 SSO 가이드 확정 시 이 인터페이스의 실제 구현체를 작성하고
 * {@link StubSsoAuthenticator} 를 대체하면 된다 (@Primary 또는 교체).
 * 연동 필드(엔드포인트 URL/클라이언트 ID/시크릿/리다이렉트 URI)는
 * TwoFactorConfigService 가 보관/영속화한다.
 *
 * 예상 흐름 (OAuth2/OIDC 형태 가정 — 가이드에 따라 조정):
 *   1. GET /sso/login  → buildAuthorizationUrl(state) 로 리다이렉트
 *   2. 사내 SSO 인증 후 GET /sso/callback?code=...&state=...
 *   3. authenticate(code, state) → SsoUserInfo (username 매칭/검증)
 *   4. SsoController 가 세션 인증 확립 (TwoFactorService.completeAuthentication 재사용 가능)
 */
public interface SsoAuthenticator {

    /** SSO 인증 요청 URL 생성 (state = CSRF 방지 토큰) */
    String buildAuthorizationUrl(String state);

    /** 콜백의 인가 코드를 검증하고 사용자 정보를 반환 */
    SsoUserInfo authenticate(String code, String state);

    /** SSO 인증 결과 사용자 정보 */
    record SsoUserInfo(String username, String displayName, String email) {
    }
}

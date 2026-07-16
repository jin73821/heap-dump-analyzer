package com.heapdump.analyzer.service.sso;

import org.springframework.stereotype.Component;

/**
 * SSO 연동 스텁 — 사내 SSO 가이드 확정 전 placeholder.
 * 실제 연동 구현 시 이 클래스를 대체(또는 새 구현체에 @Primary)하면
 * SsoController 는 수정 없이 동작하도록 배선되어 있다.
 */
@Component
public class StubSsoAuthenticator implements SsoAuthenticator {

    public static final String NOT_READY_MESSAGE = "사내 SSO 가이드 확인 후 연동 예정입니다.";

    /** 구현체 준비 여부 — 스텁은 항상 false. SsoController 가 분기에 사용 */
    public boolean isReady() {
        return false;
    }

    @Override
    public String buildAuthorizationUrl(String state) {
        throw new UnsupportedOperationException(NOT_READY_MESSAGE);
    }

    @Override
    public SsoUserInfo authenticate(String code, String state) {
        throw new UnsupportedOperationException(NOT_READY_MESSAGE);
    }
}

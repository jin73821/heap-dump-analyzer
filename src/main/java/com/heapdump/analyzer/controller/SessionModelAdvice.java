package com.heapdump.analyzer.controller;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 모든 Thymeleaf 모델에 세션 무동작 만료 시간(초)을 노출한다 (2026-08-23).
 *
 * <p>클라이언트 유휴 타이머(`/js/session-timeout.js`)가 "언제 경고하고 언제 로그아웃할지"를
 * 계산하려면 만료 간격을 알아야 하는데, Thymeleaf 3.1 은 {@code #session}/{@code #request} 를
 * 제거했으므로 서버가 모델로 내려줄 수밖에 없다. 배너 fragment 가 이 값을
 * {@code window.SESSION_TIMEOUT_SECONDS} 로 노출한다.
 *
 * <p><b>왜 {@code HeapDumpAnalyzerService.getSessionTimeoutHours()} 가 아니라 세션에서 읽는가.</b>
 * 관리자가 {@code POST /api/settings/session-timeout} 으로 값을 바꾸면
 * {@code JdbcIndexedSessionRepository.setDefaultMaxInactiveInterval()} 이 호출되는데 이건
 * <b>이후 생성되는 세션에만</b> 적용된다. {@code SPRING_SESSION.MAX_INACTIVE_INTERVAL} 은 행별 값이라
 * 1시간일 때 로그인한 사용자는 관리자가 6시간으로 바꿔도 여전히 1시간에 만료된다.
 * 설정값을 내려보내면 클라이언트가 6시간으로 계산해 경고도 못 띄운 채 401 을 맞는다.
 * 세션에서 직접 읽으면 이 불일치가 구조적으로 사라진다 — 각 사용자는 항상 자기 세션의 진짜 값을 받는다.
 *
 * <p><b>redirect 안전성.</b> {@code @ControllerAdvice} 의 {@code @ModelAttribute} 는 기본 모델에
 * 들어가므로 {@code return "redirect:..."} 에서 쿼리스트링으로 샐 여지가 이론상 있으나,
 * Spring 6.2 의 {@code ModelAndViewContainer.ignoreDefaultModelOnRedirect} 기본값이 {@code true} 라
 * redirect 시 기본 모델은 폐기된다. {@code GlobalExceptionHandler} 의 명시적 {@code RedirectView} 도
 * {@code isRedirectView()} 가 true 라 동일하게 안전하다.
 */
@ControllerAdvice
public class SessionModelAdvice {

    /** 세션이 없을 때(비인증 페이지 등) 쓰는 기본값 — application.properties 의 기본 1h 과 동일. */
    static final int DEFAULT_TIMEOUT_SECONDS = 3600;

    @ModelAttribute("sessionTimeoutSeconds")
    public int sessionTimeoutSeconds(HttpServletRequest request) {
        // 세션을 새로 만들지 않는다 — 여기서 생성하면 비인증 요청마다 빈 세션이 쌓인다.
        HttpSession session = request.getSession(false);
        if (session == null) return DEFAULT_TIMEOUT_SECONDS;
        int interval = session.getMaxInactiveInterval();
        // 0 이하 = 만료 없음. 클라이언트 타이머가 즉시 만료시키지 않도록 기본값으로 대체한다.
        return interval > 0 ? interval : DEFAULT_TIMEOUT_SECONDS;
    }
}

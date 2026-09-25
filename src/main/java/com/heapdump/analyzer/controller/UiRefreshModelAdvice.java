package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 Thymeleaf 모델에 화면 자동 갱신 주기(초)를 노출한다 (2026-09-26).
 *
 * <p>Settings &gt; General 의 '화면 자동 갱신 주기'(10/30/60초)를 배너 fragment 가
 * {@code window.UI_REFRESH_SECONDS} 로 내려보내고, 배너 System Status 폴링 · 알림 벨(배지·말풍선) ·
 * 대시보드/현황판 부분 갱신({@code live-refresh.js})이 같은 값을 읽는다. Analysis Queue 의 5초 폴링은 대상이 아니다.
 *
 * <p>값은 {@link HeapDumpConfig#getUiRefreshIntervalSec()} 그대로다 — 설정 변경은 다음 페이지 로드부터
 * 적용된다(열린 탭의 즉시 적응은 의도적으로 두지 않았다). redirect 안전성은 {@link SessionModelAdvice} 와 같다.
 */
@ControllerAdvice
public class UiRefreshModelAdvice {

    private final HeapDumpConfig config;

    public UiRefreshModelAdvice(HeapDumpConfig config) {
        this.config = config;
    }

    @ModelAttribute("uiRefreshSeconds")
    public int uiRefreshSeconds() {
        int sec = config.getUiRefreshIntervalSec();
        return sec > 0 ? sec : 60;
    }
}

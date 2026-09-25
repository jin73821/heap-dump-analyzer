package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 화면 자동 갱신이 세션 유휴 만료를 되돌리지 않는다는 소스 계약 (2026-09-26).
 *
 * <p>{@code session-timeout.js} 의 {@code init()} 은 페이지 로드마다 {@code lastActivityAt = now()} 를 쓴다 —
 * "이 앱에 주기적 자동 새로고침이 없다" 는 전제 위에서만 안전하다. 그래서 부분 갱신 모듈과 그 소비 페이지는
 * ① 타이머로 {@code location.reload()} 를 하지 않고(레이아웃이 바뀌면 멈추고 사용자에게 버튼을 준다)
 * ② {@code SessionTimeout.touch()} 를 부르지 않으며 ③ 타이머는 {@code managedInterval} 만 쓴다(함정 38).
 * 대시보드의 종전 '분석 완료 → reload' 도 부분 갱신으로 바뀌었다.
 */
class LiveRefreshSessionContractTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    @DisplayName("live-refresh.js — reload 는 사용자 클릭 핸들러 한 곳뿐, touch()·raw setInterval 없음, managedInterval 사용")
    void liveRefreshNeverReloadsOrTouchesOnItsOwn() throws IOException {
        String js = read("src/main/resources/static/js/live-refresh.js");
        assertEquals(1, count(js, "location.reload("), "reload 호출이 한 곳이 아니다");
        assertTrue(js.contains("b.addEventListener('click', function () { global.location.reload(); });"),
                "유일한 reload 가 사용자 클릭 핸들러 안에 있지 않다");
        assertFalse(js.contains("SessionTimeout.touch(") || js.contains(".touch("), "갱신 모듈이 유휴 시계를 건드린다");
        assertFalse(js.contains("setInterval(") || js.contains("setTimeout("), "raw 타이머 — 유휴·숨은 탭 게이트를 우회한다(함정 38)");
        assertTrue(js.contains("SessionTimeout.managedInterval(tick, interval)"), "managedInterval 을 쓰지 않는다");
        assertTrue(js.contains("UI_REFRESH_SECONDS"), "설정 주기를 읽지 않는다");
        assertTrue(js.contains("'X-Requested-With': 'LiveRefresh'"), "접속 로그 식별 헤더");
        assertTrue(js.contains("r.status === 401 || r.status === 403") && js.contains("notifyExpired('live-refresh')"), "만료 처리");
    }

    @Test
    @DisplayName("대시보드 — 분석 완료 시 reload 대신 부분 갱신, 큐 폴링(5초)은 그대로")
    void dashboardUsesPartialRefresh() throws IOException {
        String html = read("src/main/resources/templates/index.html");
        assertFalse(html.contains("if (completed) { location.reload(); return; }"), "분석 완료 reload 가 되살아났다");
        assertTrue(html.contains("if (window._dashLive) { _dashLive.refreshNow(); }"), "분석 완료 → 부분 갱신 배선이 없다");
        assertTrue(html.contains("url: '/api/dashboard/fragments'"), "조각 엔드포인트는 /api/ 접두(만료 시 401 JSON)");
        assertTrue(html.contains("_queuePollTimer = setInterval(function() {") && html.contains("}, 5000);"),
                "Analysis Queue 5초 폴링은 갱신 주기 설정의 대상이 아니다 — 손대지 말 것");
        assertTrue(html.contains("/js/live-refresh.js"), "모듈 로드");
        assertTrue(html.contains("document.addEventListener('liverefresh:swapped'"), "탐지 종류 토글이 교체 뒤 hidden 을 다시 맞추지 않는다");
        assertTrue(html.contains("_detectChart.destroy()"), "차트 재생성 전 해제(레지스트리 누수)");
    }

    @Test
    @DisplayName("현황판 — ACK 모달·처리 중·연출 중엔 갱신을 건너뛴다")
    void alertBoardGatesRefresh() throws IOException {
        String html = read("src/main/resources/templates/alerts.html");
        int at = html.indexOf("LiveRefresh.start({");
        assertTrue(at > 0);
        String cfg = html.substring(at, html.indexOf("}) : null;", at));
        assertTrue(cfg.contains("!cfModal.classList.contains('open')") && cfg.contains("!cfOk.disabled")
                && cfg.contains("inflight === 0") && cfg.contains(".leaving, .returning"), "게이트 조건이 빠졌다");
        assertFalse(cfg.contains("location.reload"), "현황판 갱신에 reload");
    }

    @Test
    @DisplayName("session-timeout.js — managedInterval 틱은 noteServerTouch 만 찍고 touch() 는 부르지 않는다")
    void managedIntervalDoesNotTouchIdleClock() throws IOException {
        String js = read("src/main/resources/static/js/session-timeout.js");
        int from = js.indexOf("ST.managedInterval = function");
        int to = js.indexOf("_managed.push(handle);", from);
        assertTrue(from > 0 && to > from);
        String body = js.substring(from, to);
        assertTrue(body.contains("ST.noteServerTouch();"), "서버 접촉 기록이 없다(keep-alive 와 중복 호출)");
        assertFalse(body.contains("ST.touch(") || body.contains("lastActivityAt"), "배경 폴링 틱이 유휴 시계를 되돌린다 — 세션이 영원히 만료되지 않는다");
    }

    @Test
    @DisplayName("배너·알림 벨 주기는 설정값(UI_REFRESH_SECONDS)을 읽는다")
    void bannerAndBellReadSetting() throws IOException {
        String bell = read("src/main/resources/static/js/alert-bell.js");
        assertTrue(bell.contains("global.UI_REFRESH_SECONDS > 0 ? global.UI_REFRESH_SECONDS : 60"), "알림 벨 주기가 설정을 읽지 않는다");
        assertFalse(bell.contains("opts.interval || 60000"), "알림 벨 60000 하드코딩");
        String banner = read("src/main/resources/templates/fragments/banner.html");
        assertTrue(banner.contains("window.UI_REFRESH_SECONDS = [[${uiRefreshSeconds}]]"), "배너가 설정값을 내려보내지 않는다");
    }
}

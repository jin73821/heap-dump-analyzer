package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세그먼트 토글 애니메이션 공용 모듈(seg-thumb.js)과 세 페이지 배선 (2026-09-19).
 * 헤드리스 좌표 검증은 별도로 했다 — 여기서는 조용히 깨지는 부류(감지 루프·첫 배치 날아옴·동작 줄이기 무시·배선 누락)를 앵커로 고정한다.
 */
class SegThumbWiringTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = SegThumbWiringTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " 를 클래스패스에서 찾을 수 없다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("모듈 — 선택 변화 감지·감지 루프 방지·첫 배치 무이동·스크립트 없을 때 폴백·동작 줄이기")
    void module() throws IOException {
        String js = resource("/static/js/seg-thumb.js");
        // 페이지 토글 로직을 건드리지 않고 class/aria-pressed 변화를 따라간다
        assertTrue(js.contains("attributeFilter: ['class', 'aria-pressed']"), "선택 변화 감지가 없다");
        // classList 조작은 값이 같아도 속성 변경을 만든다 — 바뀔 때만 건드리지 않으면 감지 → 수정 → 감지 무한 루프
        assertTrue(js.contains("if (el.classList.contains(cls) !== on) el.classList.toggle(cls, on);"), "감지 루프 방지가 없다");
        // 첫 배치는 움직이지 않는다 — 트랜지션은 두 프레임 뒤
        assertTrue(js.contains("requestAnimationFrame(function () { requestAnimationFrame(function () { setClass(host, 'seg-thumb-anim', true); }); });"),
                "첫 배치에서 선택 표시가 날아온다");
        // 버튼 자체 배경은 선택 표시가 자리를 잡은 뒤에만 끈다
        assertTrue(js.contains(".seg-thumb-host.seg-thumb-ready>.seg-thumb-on{background:transparent!important"), "폴백 규칙");
        assertTrue(js.contains("@media (prefers-reduced-motion:reduce){.seg-thumb-host.seg-thumb-anim>.seg-thumb{transition:none}.seg-slide-in{animation:none}}"),
                "동작 줄이기에서 끄지 않는다");
        assertTrue(js.contains("new global.ResizeObserver(place)"), "버튼 폭 변화(글꼴 굵기·웹폰트·숨김 해제)를 따라가지 않는다");
        assertFalse(js.contains("setInterval("), "배경 타이머");
    }

    @Test
    @DisplayName("배선 — 현황판(알람 필터 2종·최근 분석 탭)·History(기간·그룹)·대시보드가 같은 모듈을 쓴다")
    void pagesUseModule() throws IOException {
        String alerts = resource("/templates/alerts.html");
        assertTrue(alerts.contains("/js/seg-thumb.js?v="), "현황판이 모듈을 싣지 않는다");
        assertTrue(alerts.contains("['ackFilter', 'domFilter', 'rowTabs'].forEach(function (id) {")
                && alerts.contains("SegThumb.attach(document.getElementById(id), { active: '[aria-pressed=\"true\"]' });"),
                "현황판 토글 3종 배선");
        assertTrue(alerts.contains("SegThumb.slideIn(document.querySelectorAll('[data-rows=\"' + tab + '\"]'), dir);")
                && alerts.contains("if (tab === curTab) return;"), "최근 분석 탭 전환 슬라이드 / 같은 탭 재클릭 무시");

        String history = resource("/templates/history.html");
        assertTrue(history.contains("/js/seg-thumb.js?v="), "History 가 모듈을 싣지 않는다");
        assertTrue(history.contains("SegThumb.attach(document.getElementById('detPeriodChips'), { active: '.active' });")
                && history.contains("SegThumb.attach(document.getElementById('detGroupToggle'), { active: '.active' });"),
                "History Detections 토글 배선 — History 는 선택을 .active 로 표시한다");

        String index = resource("/templates/index.html");
        assertTrue(index.contains("SegThumb.attach(box,"), "대시보드 배선");
    }
}

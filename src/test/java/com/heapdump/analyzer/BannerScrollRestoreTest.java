package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좌측 배너 스크롤 위치 유지 (2026-11-28).
 *
 * <p>배너는 페이지마다 새로 그려져 메뉴를 누르면 스크롤이 맨 위로 돌아갔다. 떠날 때 sessionStorage 에 저장하고
 * 그릴 때 복원한다. 아래 두 조건이 깨지면 예외 없이 "복원이 안 되는" 것으로만 나타나므로 정적으로 막아 둔다.
 */
class BannerScrollRestoreTest {

    private static String banner() throws IOException {
        try (InputStream in = BannerScrollRestoreTest.class.getResourceAsStream("/templates/fragments/banner.html")) {
            assertNotNull(in, "banner.html 없음");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("저장 키는 복원을 부르는 즉시 실행 함수보다 먼저 선언된다 — 뒤에 두면 호이스팅으로 undefined 키가 된다")
    void keyDeclaredBeforeRestoreCall() throws IOException {
        String s = banner();
        int key = s.indexOf("var _GB_SCROLL_KEY");
        int call = s.indexOf("restoreBannerScroll();");
        assertTrue(key > 0 && call > 0, "저장 키 선언·복원 호출이 있어야 한다");
        assertTrue(key < call, "_GB_SCROLL_KEY 선언이 restoreBannerScroll() 호출보다 앞에 있어야 한다");
        assertTrue(s.contains("addEventListener('pagehide', saveBannerScroll)"), "떠날 때 저장해야 한다");
        assertTrue(call > s.indexOf("highlightActiveNav();"), "서브 메뉴를 펼친 뒤에 복원해야 한다(높이 확정 후)");
    }

    @Test
    @DisplayName("로드 중에는 서브 메뉴 펼침 애니메이션을 끈다 — 펼치는 동안 높이가 모자라 복원 위치가 잘린다")
    void subMenuOpensInstantlyOnLoad() throws IOException {
        String s = banner();
        int rule = s.indexOf("body.banner-no-transition .gb-nav-sub");
        assertTrue(rule > 0, "banner-no-transition 규칙에 .gb-nav-sub 가 있어야 한다");
        assertTrue(s.indexOf("transition: none !important", rule) - rule < 80, ".gb-nav-sub 도 transition:none 이어야 한다");
    }
}

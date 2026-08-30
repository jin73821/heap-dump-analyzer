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
 * 토스트 겹침 방어 (2026-08-29 제보: Save All 의 "저장 완료" 와 경고 문구가 포개져 읽혔다).
 *
 * <p>원인은 CSS 와 JS 의 상호작용이라 어느 한쪽만 보면 드러나지 않는다 —
 * {@code common.css} 의 {@code .toast} 는 {@code position: fixed; top: 70px} <b>고정 좌표</b>인데
 * {@code Common.toast} 는 호출될 때마다 새 div 를 만든다. 두 개가 동시에 뜨면 같은 자리에
 * 정확히 겹친다. {@code restackToasts()} 가 살아 있는 토스트를 세어 top 을 다시 계산한다.
 *
 * <p>여기서는 그 배선이 남아 있는지만 정적으로 확인한다(브라우저 좌표 검증은 헤드리스로 별도 수행).
 * 조용히 회귀하는 부류라 — 겹쳐도 예외가 없고 스크린샷 없이는 눈에 띄지 않는다 — 앵커를 박아 둔다.
 */
class CommonToastStackTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = CommonToastStackTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " 를 클래스패스에서 찾을 수 없다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Common.toast 는 .toast-stack 마커를 붙이고 생성·제거 양쪽에서 재배치한다")
    void toastStacksInsteadOfOverlapping() throws IOException {
        String js = resource("/static/js/common.js");

        assertTrue(js.contains("function restackToasts"), "재배치 함수가 사라졌다 — 토스트가 다시 포개진다");
        assertTrue(js.contains("'toast toast-stack toast-'"),
                "생성되는 토스트에 .toast-stack 마커가 없다 — restackToasts 가 아무것도 세지 못한다");

        // 생성 직후와 제거 직후 모두 재배치해야 한다. 제거 쪽을 빠뜨리면 앞 토스트가 사라진 뒤
        // 뒤 토스트가 공중에 뜬 채 남는다.
        int calls = js.split("restackToasts\\(\\);", -1).length - 1;
        assertTrue(calls >= 2, "restackToasts 호출이 " + calls + "회뿐이다 — 생성/제거 양쪽에 필요하다");
        assertTrue(js.indexOf("t.remove(); restackToasts();") > 0, "제거 후 재배치가 없다");
    }

    @Test
    @DisplayName("#toast 고정 엘리먼트 계열과 좌표 계산이 섞이지 않는다")
    void fixedElementToastIsNotCounted() throws IOException {
        String js = resource("/static/js/common.js");
        // servers/server-detail/admin-users 의 Common.showToast 는 기존 #toast 엘리먼트를 재사용하며
        // .toast 클래스를 쓴다. 재배치가 .toast 를 세면 그 엘리먼트까지 잡아 좌표가 어긋난다.
        assertTrue(js.contains("querySelectorAll('.toast-stack')"),
                "재배치가 .toast-stack 이 아닌 셀렉터를 쓴다 — showToast 계열과 섞인다");
        assertFalse(js.contains("querySelectorAll('.toast')"), ".toast 전체를 세면 안 된다");
    }

    @Test
    @DisplayName("스택이 필요한 전제(.toast 고정 top)가 그대로이고, 마커에는 스타일이 없다")
    void cssPremiseHolds() throws IOException {
        String css = resource("/static/css/common.css");
        assertTrue(css.contains(".toast { position: fixed; top: 70px;"),
                ".toast 의 고정 좌표가 바뀌었다 — restackToasts 의 시작 top(70) 과 맞는지 확인할 것");
        // .toast-stack 은 순수 마커다. 스타일을 주면 .toast 와 우선순위 다툼이 생긴다.
        assertFalse(css.contains(".toast-stack"), ".toast-stack 에 CSS 규칙이 생겼다 — 마커 전용이어야 한다");
    }

    @Test
    @DisplayName("common.js 를 로드하는 모든 템플릿의 캐시 키가 같다")
    void cacheKeysAreInSync() throws IOException {
        // 캐시 키가 갈라지면 한 페이지만 옛 common.js 를 받아 겹침이 남는다.
        String banner = resource("/templates/fragments/banner.html");
        String memo = resource("/templates/account-memo.html");
        String key = "/js/common.js?v=2026-08-29";
        assertTrue(banner.contains(key), "banner.html 의 common.js 캐시 키가 갱신되지 않았다");
        assertTrue(memo.contains(key), "account-memo.html 의 common.js 캐시 키가 갱신되지 않았다");
    }
}

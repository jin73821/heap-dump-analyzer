package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모달·목록 항목 연출의 공통 계약 (2026-09-20).
 *
 * <p>판정({@code prefers-reduced-motion})과 끝 이벤트 대기는 {@code common.js} 한 곳에 둔다 —
 * 같은 코드가 모듈마다 복사되면 어느 한쪽만 고쳐져 '동작 줄이기'에서 창이 안 닫히는 식으로 갈라진다.
 *
 * <p>연출을 붙일 때 되풀이되는 함정 둘을 여기서 고정한다.
 * ① <b>감추기는 연출이 끝난 뒤에</b> — {@code [hidden]}·{@code display:none} 을 먼저 걸면 사라지는 모습이
 *    통째로 잘린다. ② <b>끝 이벤트를 기다리되 시간 상한을 둔다</b> — 숨은 탭에서는 이벤트가 오지 않아
 *    창이 영영 남는다. '동작 줄이기'면 기다리지 않고 즉시 처리한다.
 */
class ModalMotionTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = ModalMotionTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " 를 클래스패스에서 찾을 수 없다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("모션 헬퍼는 common.js 단일 출처 — 소비 모듈은 자기 구현을 두지 않는다")
    void motionHelpersLiveInCommonJs() throws IOException {
        String common = resource("/static/js/common.js");
        assertTrue(common.contains("Common.reduceMotion = function"), "Common.reduceMotion 이 없다");
        assertTrue(common.contains("Common.afterMotion = function"), "Common.afterMotion 이 없다");
        // 자식에서 올라온 이벤트·다른 속성은 무시하고, 이벤트가 안 와도 상한 뒤엔 반드시 진행한다
        assertTrue(common.contains("ev.target !== el") && common.contains("prop && ev.propertyName !== prop"),
                "끝 이벤트 필터가 없다 — 겹친 모달에서 자식 애니메이션에 먼저 반응한다");
        assertTrue(common.contains("global.setTimeout(fin, timeoutMs);"),
                "시간 상한이 없다 — 숨은 탭에서 끝 이벤트가 오지 않으면 영영 기다린다");

        for (String js : new String[] { "/static/js/memo.js", "/static/js/alert-bell.js" }) {
            String s = resource(js);
            assertTrue(s.contains("Common.afterMotion("), js + " 가 공통 헬퍼를 쓰지 않는다");
            assertFalse(s.contains("matchMedia('(prefers-reduced-motion: reduce)')"),
                    js + " 가 동작 줄이기 판정을 자체 구현한다 — common.js 와 갈라진다");
        }
    }

    @Test
    @DisplayName("메모 변경 이력 창은 열고 닫을 때 연출하고, 감추기는 연출이 끝난 뒤에 한다")
    void memoHistoryViewerAnimates() throws IOException {
        String js = resource("/static/js/memo.js");
        assertTrue(js.contains("@keyframes memo-hv-in") && js.contains("@keyframes memo-hv-out"),
                "이력 창 열기·닫기 애니메이션이 없다");
        assertTrue(js.contains("'.memo-hv-ov.hv-in .memo-hv-box{animation:memo-hv-in"), "창 본체가 커지며 나타나지 않는다");
        assertTrue(js.contains("'.memo-hv-ov.hv-out{animation:memo-hv-fade-out") && js.contains("pointer-events:none}'"),
                "사라지는 창이 클릭을 받는다");
        assertTrue(js.contains("prefers-reduced-motion:reduce"), "동작 줄이기에서 연출을 끄지 않는다");

        // 열기는 show() 한 곳 — hidden 을 직접 만지면 연출을 건너뛴 경로가 생긴다
        assertTrue(js.contains("hv.show();"), "이력 창을 show() 로 열지 않는다");
        assertFalse(js.contains("hv.ov.hidden = false;"), "이력 창을 hidden 조작으로 바로 연다 — 연출을 건너뛴다");

        // 닫기: hidden 은 애니메이션이 끝난 뒤. 그 사이 다시 열렸으면 뒤늦은 감추기를 버린다
        int close = js.indexOf("        function close() {\n            hideConfirm();");
        assertTrue(close > 0, "이력 창 close() 를 찾을 수 없다");
        String body = js.substring(close, js.indexOf("api.close = close;", close));
        assertTrue(body.contains("ov.classList.add('hv-out');"), "닫기 연출을 걸지 않는다");
        int wait = body.indexOf("afterMotion(ov, 'animationend'");
        assertTrue(wait > 0, "닫기 연출이 끝나기를 기다리지 않는다");
        assertTrue(body.lastIndexOf("ov.hidden = true;") > wait, "연출 전에 hidden 을 붙인다 — 사라지는 모습이 잘린다");
        assertTrue(body.contains("if (seq !== motionSeq) return;"),
                "닫히는 사이 다시 열리면 뒤늦은 감추기가 새 창을 지운다");
        assertTrue(body.contains("if (motionOff()) { ov.hidden = true; return; }"),
                "동작 줄이기에서 끝 이벤트를 기다린다 — 창이 남는다");
    }

    @Test
    @DisplayName("GC 로그 화면 모달은 열고 닫을 때 연출하고, .open 은 사라지는 연출이 끝난 뒤에 뗀다 (2026-10-22)")
    void gcLogModalsAnimate() throws IOException {
        String css = resource("/static/css/gc-log.css");
        assertTrue(css.contains(".gcl-modal.open .modal-box { animation: gclBoxIn"), "모달 상자가 나타나는 연출이 없다");
        assertTrue(css.contains(".gcl-modal.open { animation: gclOvIn"), "배경이 페이드인되지 않는다");
        int open = css.indexOf(".gcl-modal.open .modal-box {"), closing = css.indexOf(".gcl-modal.closing .modal-box {");
        assertTrue(open > 0 && closing > open,
                ".closing 규칙이 .open 뒤에 없다 — 사라지는 동안에도 .open 이 붙어 있어 명시도가 같으면 나타나기 연출이 이긴다");
        assertTrue(css.contains(".gcl-modal.closing { animation: gclOvOut .14s ease-in both; pointer-events: none; }"),
                "사라지는 창이 클릭을 받는다");
        assertTrue(css.contains(".gcl-modal.open, .gcl-modal.open .modal-box, .gcl-modal.closing, .gcl-modal.closing .modal-box { animation: none; }"),
                "동작 줄이기에서 연출을 끄지 않는다");

        // 결과 화면 모달 5종(힙 덤프 선택·AI 분석·재분석·이어붙이기·삭제) + 목록 화면 3종은 열고 닫을 때 헬퍼만 거친다
        for (String js : new String[] { "/static/js/gc-log-analyze.js", "/static/js/gc-log-index.js" }) {
            String s = resource(js);
            int hide = s.indexOf("    function hideModal(ov) {");
            assertTrue(hide > 0, js + " 에 hideModal 이 없다");
            String body = s.substring(hide, s.indexOf("\n    }\n", hide));
            int wait = body.indexOf("Common.afterMotion(ov, 'animationend'");
            assertTrue(body.indexOf("ov.classList.add('closing');") > 0 && wait > 0, js + " 가 사라지는 연출을 기다리지 않는다");
            assertTrue(body.contains("if (ov._gclSeq === seq) ov.classList.remove('open', 'closing');"),
                    js + " — 닫히는 사이 다시 열리면 뒤늦은 떼기가 새 창을 닫는다");
            assertTrue(body.contains("Common.reduceMotion()) { fin(); return; }"), js + " — 동작 줄이기에서 끝 이벤트를 기다린다");
            // .open 을 직접 붙이고 떼는 곳은 헬퍼 안 한 곳씩뿐 — 그 밖에서 만지면 연출을 건너뛴 경로가 생긴다
            assertEquals(1, count(s, "classList.add('open')"), js + " 에 showModal 밖의 직접 열기가 있다");
            assertEquals(0, count(s, "classList.remove('open')"), js + " 에 연출 없이 바로 닫는 경로가 있다");
        }
        String an = resource("/static/js/gc-log-analyze.js");
        for (String id : new String[] { "aiConfirmModal", "deleteGcModal", "matchPickModal", "gclCombineModal" }) {
            assertTrue(an.contains("hideModal(document.getElementById('" + id + "'))") || an.contains("hideModal(modal)"),
                    id + " 를 연출 없이 닫는다");
        }
        assertTrue(an.contains("showModal(document.getElementById('aiConfirmModal'))")
                && an.contains("showModal(document.getElementById('deleteGcModal'))")
                && an.contains("showModal(ov)") && an.contains("showModal(modal)"), "결과 화면 모달이 showModal 로 열리지 않는다");
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}

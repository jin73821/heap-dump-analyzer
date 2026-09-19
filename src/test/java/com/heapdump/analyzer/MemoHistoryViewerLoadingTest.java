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
 * 메모 변경 이력 창의 조회 중 표시 (2026-09-19 제보: 이력을 고를 때마다 창이 커졌다 작아지며 깜빡였다).
 *
 * <p>원인은 두 가지가 겹친 것이다 — 창이 {@code max-height} 라 내용 높이를 따랐고, 조회를 시작할 때마다
 * 본문을 한 줄짜리 '불러오는 중…' 으로 갈아 끼웠다. 응답이 10~30ms 라 창이 800→376→800px 로 한 프레임
 * 사이에 줄었다 늘었다(헤드리스 실측). 예외도 로그도 없고 화면을 봐야만 드러나는 부류라 앵커를 박아 둔다.
 * 브라우저 좌표 검증(폭 1440/900/500 에서 창 높이·본문 위치 샘플링)은 헤드리스 픽스처로 별도 수행했다.
 */
class MemoHistoryViewerLoadingTest {

    private static String memoJs() throws IOException {
        try (InputStream in = MemoHistoryViewerLoadingTest.class.getResourceAsStream("/static/js/memo.js")) {
            assertNotNull(in, "/static/js/memo.js 를 클래스패스에서 찾을 수 없다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** start 부터 처음 나오는 end 까지 (end 포함 안 함). */
    private static String slice(String s, String start, String end) {
        int i = s.indexOf(start);
        assertTrue(i >= 0, start + " 를 찾을 수 없다");
        int j = s.indexOf(end, i + start.length());
        assertTrue(j > i, start + " 뒤에서 " + end + " 를 찾을 수 없다");
        return s.substring(i, j);
    }

    @Test
    @DisplayName("이력 창은 높이가 고정이고, 조회 중에 본문을 짧은 문구로 갈아 끼우지 않는다")
    void viewerSizeDoesNotFollowLoadingState() throws IOException {
        String js = memoJs();

        String box = slice(js, "'.memo-hv-box{", "}'");
        assertTrue(box.contains(";height:min("), "이력 창 높이가 고정이 아니다 — 내용 높이를 따르면 조회 때마다 창이 줄었다 늘어난다");
        assertFalse(box.contains("max-height"), "이력 창이 다시 max-height 다 — 내용 높이를 따른다");

        // 조회 중 문구는 본문이 아니라 스피너 덮개가 맡는다
        assertFalse(js.contains("showPlaceholder('불러오는 중…')"),
                "조회 시작 때 본문을 '불러오는 중…' 한 줄로 갈아 끼운다 — 응답이 오면 되돌아가며 깜빡인다");

        // 비교 통계를 비우면 도구줄이 접히는 폭에서 줄 수가 바뀌어 본문이 튄다(실측 406→382→406px)
        String begin = slice(js, "api.beginDetailLoad = function", "};");
        assertTrue(begin.contains("api.bodyBusy.start()"), "상세 조회가 스피너를 시작하지 않는다");
        assertFalse(begin.contains("stat.textContent"), "상세 조회 시작 때 비교 통계를 지운다 — 좁은 폭에서 본문이 튄다");
        assertFalse(begin.contains("legend.hidden"), "상세 조회 시작 때 범례를 감춘다");
    }

    @Test
    @DisplayName("스피너는 지연 표시·최소 표시 시간을 갖고, 목록·상세 조회 양쪽에 배선돼 있다")
    void spinnerIsDelayedAndWired() throws IOException {
        String js = memoJs();

        // 지연이 없으면 10~30ms 응답마다 스피너가 한 프레임 번쩍인다 — 제보된 깜빡임을 스피너로 되살리는 셈
        assertTrue(js.contains("var BUSY_DELAY_MS = "), "스피너 표시 지연이 없다");
        assertTrue(js.contains("var BUSY_MIN_MS = "), "스피너 최소 표시 시간이 없다");
        assertTrue(js.contains("@keyframes memo-hv-spin"), "스피너 애니메이션이 없다");

        assertTrue(js.contains("api.listBusy = createBusy("), "목록 조회 스피너 배선이 없다");
        assertTrue(js.contains("api.bodyBusy = createBusy("), "상세 조회 스피너 배선이 없다");
        assertTrue(js.contains("hv.listBusy.start();"), "창을 열 때 목록 스피너를 시작하지 않는다");

        // 안내 문구가 결과를 대신하는 경로(오류·삭제로 선택 해제)에서 스피너가 남으면 영원히 돈다
        String placeholder = slice(js, "function showPlaceholder(msg)", "api.showPlaceholder = showPlaceholder;");
        assertTrue(placeholder.contains("api.bodyBusy.stop()"), "안내 문구 경로가 상세 스피너를 내리지 않는다");

        // 닫았다 다시 연 사이 도착한 옛 목록 응답은 새 스피너를 내리고 목록을 두 벌 쌓는다
        String open = slice(js, "Memo.openHistoryViewer = function", "// ── 자동 저장");
        int guards = open.split("if \\(seq !== hv\\.loadSeq\\) return;", -1).length - 1;
        assertTrue(guards >= 2, "목록 응답 세대 검사가 성공·실패 양쪽에 없다 (" + guards + "곳)");
    }

    @Test
    @DisplayName("범례는 [hidden] 일 때 자리를 남긴 채 감춰진다")
    void legendHiddenKeepsItsSpace() throws IOException {
        String js = memoJs();
        // .memo-hv-legend{display:inline-flex} 가 UA 의 [hidden]{display:none} 을 이겨 범례가 늘 보였다.
        // display:none 으로 고치면 좁은 폭에서 둘째 줄이 생겼다 사라져 본문이 밀린다 — visibility 로 감춘다.
        assertTrue(js.contains("'.memo-hv-legend[hidden]{display:inline-flex;visibility:hidden}'"),
                "범례 [hidden] 규칙이 없거나 자리를 빼는 방식이다");
    }
}

package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대시보드·Files 업로드 큐의 GC 로그 배선 (2026-09-21).
 *
 * <p>고치기 전 증상: 큐로 올린 GC 로그는 {@code serverName} 을 보내지 않아 <b>업로드는 성공하고 분석만</b>
 * 400 {@code NO_HOSTNAME} 으로 막혔다. 조용히 어긋나는 부류라 배선을 문자열로 고정한다.
 *
 * <p>함께 고정 — ① 큐 상한이 회전 세트(6개)보다 작으면 안 된다 ② 상한 초과를 토스트로 흘리지 않는다
 * ③ 업로드가 끝나면 연속 로그 자동 판정을 한 번 부른다 ④ 전송 시작은 출처 입력을 반드시 거친다.
 */
class UploadQueueGcOriginTest {

    private static String js() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/js/upload-queue.js"), StandardCharsets.UTF_8);
    }

    private static String template(String name) throws IOException {
        return Files.readString(Path.of("src/main/resources/templates/" + name), StandardCharsets.UTF_8);
    }

    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("★ GC 로그 전송에 호스트네임·인스턴스명을 싣는다")
    void gcLogUploadCarriesOrigin() throws IOException {
        String s = js();
        int at = s.indexOf("'/api/gc-log/upload'");
        assertTrue(at > 0, "GC 로그 업로드 분기가 없다");
        String block = s.substring(at, Math.min(s.length(), at + 700));
        assertTrue(block.contains("fdg.append('serverName'"), "serverName 을 보내지 않는다 — 분석이 NO_HOSTNAME 으로 막힌다");
        assertTrue(block.contains("fdg.append('instanceName'"), "instanceName 을 보내지 않는다");
        assertTrue(block.contains("_gcOrigin.serverName"), "출처 모달에서 받은 값을 쓰지 않는다");
    }

    @Test
    @DisplayName("전송 시작은 출처 입력을 거친다 — startQueueUploads 를 곧바로 부르지 않는다")
    void startGoesThroughOriginPrompt() throws IOException {
        String s = js();
        assertTrue(s.contains("askGcOriginThenStart()"), "출처 입력 단계가 없다");

        int at = s.indexOf("#stagedStartBtn').onclick");
        assertTrue(at > 0, "전송 시작 버튼 배선을 찾지 못했다");
        String block = s.substring(at, Math.min(s.length(), at + 200));
        assertTrue(block.contains("askGcOriginThenStart()"), "전송 시작이 출처 입력을 건너뛴다");
        assertFalse(block.contains("startQueueUploads()"), "전송 시작이 업로드를 바로 시작한다");

        // GC 로그가 없으면 곧장 시작한다(힙·코어 업로드에 불필요한 질문을 붙이지 않는다)
        int fn = s.indexOf("function askGcOriginThenStart");
        String body = s.substring(fn, Math.min(s.length(), fn + 700));
        assertTrue(body.contains("if (!gc.length) { startQueueUploads(); return; }"), "GC 로그가 없을 때의 통과 경로가 없다");
        assertTrue(body.contains("if (!origin)"), "취소 경로가 없다");
    }

    @Test
    @DisplayName("호스트네임 없이 올리는 길을 열어 두지 않는다 — 그게 막다른 길을 만들었다")
    void cancelMeansNoUpload() throws IOException {
        String s = js();
        int fn = s.indexOf("function askGcOriginThenStart");
        String body = s.substring(fn, Math.min(s.length(), fn + 700));
        // 취소는 큐를 비운다(= 업로드하지 않는다). '그냥 올리기' 같은 우회 버튼이 없어야 한다.
        assertTrue(body.contains("_uploadQueue = []"), "취소가 큐를 비우지 않는다");
        assertFalse(js().contains("호스트네임 없이"), "호스트네임 없이 올리는 우회가 생겼다");
    }

    @Test
    @DisplayName("큐 상한은 회전 세트(gc.log.0~.4 + gc.log = 6)보다 커야 한다")
    void queueCapFitsARotationSet() throws IOException {
        String s = js();
        Matcher m = Pattern.compile("var _MAX_QUEUE = (\\d+);").matcher(s);
        assertTrue(m.find(), "_MAX_QUEUE 선언을 찾지 못했다");
        int cap = Integer.parseInt(m.group(1));
        assertTrue(cap >= 6, "큐 상한이 " + cap + " 이라 회전 세트 6개가 조용히 잘린다(함정 60)");
    }

    @Test
    @DisplayName("상한 초과를 토스트로 흘리지 않는다 — 어떤 파일이 빠지는지 보여 주고 고르게 한다")
    void overflowUsesBlockingModal() throws IOException {
        String s = js();
        int at = s.indexOf("function continueEnqueue");
        String block = s.substring(at, Math.min(s.length(), at + 700));
        assertTrue(block.contains("showQueueOverflowModal"), "상한 초과가 모달을 거치지 않는다");
        assertFalse(block.contains("toast('최대 '"), "상한 초과를 토스트로 알리고 그냥 잘라낸다");
        assertTrue(s.contains("빠지는 파일:"), "잘려 나갈 파일명을 보여 주지 않는다");
    }

    @Test
    @DisplayName("업로드가 끝나면 연속 로그 자동 판정을 한 번 부른다 — 분석은 시작하지 않는다")
    void autoGroupRunsAfterQueue() throws IOException {
        String s = js();
        assertTrue(s.contains("maybeAutoGroupGcLogs(sub)"), "큐 종료 후 자동 판정 호출이 없다");
        int fn = s.indexOf("function maybeAutoGroupGcLogs");
        assertTrue(fn > 0, "자동 판정 함수가 없다");
        String body = s.substring(fn, Math.min(s.length(), fn + 1600));
        assertTrue(body.contains("'/api/gc-log/group/auto'"), "자동 묶기 엔드포인트를 부르지 않는다");
        assertTrue(body.contains("names.length < 2"), "1개만 올려도 판정을 부른다");
        assertTrue(body.contains("_gcOrigin = { serverName: '', instanceName: '' }"), "출처가 다음 업로드로 샌다");
        assertFalse(body.contains("/api/gc-log/analyze/"), "자동 경로가 분석까지 시작한다 — '묶기만 자동' 계약이 깨졌다");
    }

    @Test
    @DisplayName("두 소비 페이지가 갱신된 모듈을 싣는다(캐시 무효화)")
    void consumersLoadFreshModule() throws IOException {
        for (String t : new String[]{"index.html", "files.html"}) {
            String html = template(t);
            Matcher m = Pattern.compile("/js/upload-queue\\.js\\?v=([0-9a-z-]+)").matcher(html);
            assertTrue(m.find(), t + " 가 upload-queue.js 를 싣지 않는다");
            assertTrue(m.group(1).compareTo("2026-09-21") >= 0,
                    t + " 의 캐시 키가 낡았다(" + m.group(1) + ") — 브라우저가 옛 모듈을 쓴다");
        }
    }

    @Test
    @DisplayName("폴백은 조용히 삼키지 않는다 — Common.logIgnored/logError 를 쓴다")
    void fallbacksAreLogged() throws IOException {
        String s = js();
        int fn = s.indexOf("function showGcOriginModal");
        String body = s.substring(fn, Math.min(s.length(), fn + 900));
        assertTrue(body.contains("logIgnored"), "localStorage 폴백을 빈 catch 로 삼킨다");
        assertTrue(s.contains("global.Common.logError('[UploadQueue] 연속 로그 자동 판정'"), "자동 판정 실패를 기록하지 않는다");
    }
}

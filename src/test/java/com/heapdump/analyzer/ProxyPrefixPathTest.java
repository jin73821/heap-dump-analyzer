package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 프록시 접두사(/dump) 대응 — 스레드 덤프 화면 경로 (2026-11-27, CLAUDE.md 함정 92).
 *
 * <p>내부망 프록시는 {@code /dump/*} 를 접두사를 떼고 앱으로 넘긴다. 기존 경로(/gc-log 등)는 프록시가 보정하지만
 * {@code /thread-dump} 는 보정 대상이 아니라, 루트 기준으로 이동하면 프록시 경유 시 {@code /thread-dump} 로 가서 오류가 난다.
 * 화면 경로는 {@code Common.appPath} 로 접두사를 붙이고(직접 접속이면 그대로), 서버 리다이렉트는 상대 경로로 보낸다.
 * 새 이동 코드가 루트 리터럴로 회귀하면 예외 없이 프록시 환경에서만 깨지므로 정적으로 막아 둔다.
 */
class ProxyPrefixPathTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = ProxyPrefixPathTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "리소스 없음: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final Pattern TD_LITERAL = Pattern.compile("['\"]/thread-dump");

    @Test
    @DisplayName("스레드 덤프 화면으로 가는 JS 경로는 모두 appPath 로 감싼다 — 루트 리터럴 금지")
    void jsPathsAreWrapped() throws IOException {
        String[] files = {
                "/static/js/thread-dump-index.js", "/static/js/thread-dump-analyze.js",
                "/static/js/analyze-confirm.js", "/static/js/heap-threads-card.js",
        };
        List<String> bad = new ArrayList<>();
        for (String f : files) {
            String[] lines = resource(f).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i];
                if (!TD_LITERAL.matcher(ln).find()) continue;
                if (!(ln.contains("appPath(") || ln.contains("tdPath("))) bad.add(f + ":" + (i + 1) + " " + ln.trim());
            }
        }
        assertTrue(bad.isEmpty(), "appPath 없이 /thread-dump 로 이동: " + bad);
    }

    @Test
    @DisplayName("Common.appPath — /dump 판별·이미 붙은 접두사 중복 방지·템플릿 고정 링크 일괄 보정")
    void commonHelperContract() throws IOException {
        String js = resource("/static/js/common.js");
        assertTrue(js.contains("var PROXY_PREFIXES = ['/dump'];"), "프록시 접두사 후보");
        assertTrue(js.contains("if (p === px || p.indexOf(px + '/') === 0) return px;"), "/dumpXYZ 같은 다른 경로를 접두사로 오인하면 안 된다");
        assertTrue(js.contains("if (path === px || path.indexOf(px + '/') === 0) return path;"), "중복 접두사(/dump/dump) 방지");
        assertTrue(js.contains("var PREFIXED_PAGES = ['/thread-dump'];"), "고정 링크 보정 대상");
        assertTrue(js.contains("'a[href^=\"' + base + '\"], [data-href^=\"' + base + '\"]'"), "href·data-href 둘 다 보정");
        assertTrue(js.contains("Common.stripAppPrefix = function"), "메뉴 활성 표시용");
    }

    @Test
    @DisplayName("대시보드 이동·배너 메뉴 활성 표시도 접두사를 고려한다")
    void templatesUsePrefix() throws IOException {
        String index = resource("/templates/index.html");
        assertTrue(index.contains("Common.appPath(turl)"), "대시보드 업로드 후 스레드 덤프 화면 이동");
        String banner = resource("/templates/fragments/banner.html");
        assertTrue(banner.contains("Common.stripAppPrefix(path) : path).startsWith('/thread-dump')"), "배너 메뉴 활성 표시");
        Matcher ck = Pattern.compile("/js/common\\.js\\?v=(\\d{4}-\\d{2}-\\d{2}\\w*)").matcher(banner);
        assertTrue(ck.find() && ck.group(1).compareTo("2026-11-27") >= 0, "common.js 캐시 키 갱신");
    }

    @Test
    @DisplayName("ThreadDumpViewController 리다이렉트는 상대 경로 — 루트 기준(redirect:/thread-dump) 금지")
    void controllerRedirectsAreRelative() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/controller/ThreadDumpViewController.java"));
        assertFalse(src.contains("\"redirect:/thread-dump"), "루트 기준 리다이렉트는 프록시 경유 시 /dump 를 잃는다");
        assertTrue(src.contains("REDIRECT_LIST_FROM_ITEM = \"redirect:../../thread-dump\""));
        assertTrue(src.contains("REDIRECT_LIST_FROM_PRINT = \"redirect:../../../thread-dump\""));
    }
}

package com.heapdump.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 화면 글꼴의 단일 출처 (2026-09-20 제보: 현황판에서 코어 파일·GC 로그를 열면 한글 글꼴이 달라졌다).
 *
 * <p>원인은 {@code core-dump.css} 가 자기 {@code :root} 에 {@code --font-sans} 를 다시 선언하면서 목록이
 * 달랐던 것이다 — common.css 는 {@code 'Segoe UI','Trebuchet MS',sans-serif} 인데 core-dump.css 는 사이에
 * {@code 'Malgun Gothic','Nanum Gothic Coding'} 을 끼워 뒀다. 앞 두 글꼴에 한글이 없으므로 <b>한글만</b>
 * 다음 후보로 넘어가는데, Malgun Gothic 이 없는 클라이언트(리눅스·macOS)에서는 번들 웹폰트인
 * Nanum Gothic Coding(코딩용)이 당첨돼 코어덤프·GC 로그 페이지의 한글만 다른 글꼴로 그려졌다.
 * 오류도 경고도 없고 화면을 봐야만 드러나는 부류라 앵커를 박아 둔다.
 */
class PageFontSourceTest {

    /** core-dump.css 를 싣는 페이지들 — 이 목록 밖에서 싣기 시작하면 아래 스캔이 잡는다. */
    private static final Path TPL_ROOT = Paths.get("src/main/resources/templates");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String css(String name) throws IOException {
        return read(Paths.get("src/main/resources/static/css").resolve(name));
    }

    private static List<Path> templates() throws IOException {
        try (Stream<Path> s = Files.walk(TPL_ROOT)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("글꼴은 common.css :root 한 곳에서만 정한다 — core-dump.css/gc-log.css 는 다시 선언하지 않는다")
    void fontStackHasSingleSource() throws IOException {
        String common = css("common.css");
        assertTrue(common.contains("--font-sans:"), "common.css 에 --font-sans 정의가 없다(단일 출처가 사라졌다)");
        assertTrue(common.contains("--font-mono:"), "common.css 에 --font-mono 정의가 없다");
        assertTrue(common.contains("font-family: var(--font-sans);"),
                "common.css 의 body 가 변수를 쓰지 않는다 — 값이 둘로 갈린다");

        String coreDump = css("core-dump.css");
        assertFalse(coreDump.contains("--font-sans:"),
                "core-dump.css 가 --font-sans 를 다시 선언한다 — 목록이 어긋나면 같은 한글이 페이지마다 다르게 그려진다");
        assertFalse(coreDump.contains("--font-mono:"), "core-dump.css 가 --font-mono 를 다시 선언한다");
        assertTrue(coreDump.contains("font-family: var(--font-sans)"),
                "core-dump.css 의 body 가 변수를 쓰지 않는다");

        // 번들 웹폰트(코딩용)가 본문 sans 폴백에 끼면 한글만 코딩 글꼴로 그려진다 — 그게 이 결함의 정체였다
        int root = common.indexOf(":root {");
        int rootEnd = common.indexOf('}', root);
        String rootBlock = common.substring(root, rootEnd);
        assertFalse(rootBlock.contains("Nanum Gothic Coding"),
                "본문 글꼴 폴백에 번들 코딩 글꼴이 들어갔다 — 한글만 다른 글꼴이 된다");

        assertFalse(css("gc-log.css").contains("--font-sans:"), "gc-log.css 가 글꼴 변수를 다시 선언한다");
    }

    @Test
    @DisplayName("core-dump.css 를 싣는 페이지는 common.css 를 먼저 싣는다 — 아니면 var(--font-sans) 가 풀리지 않는다")
    void coreDumpPagesLoadCommonFirst() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path p : templates()) {
            String html = read(p);
            int cd = html.indexOf("/css/core-dump.css");
            if (cd < 0) continue;
            int common = html.indexOf("/css/common.css");
            if (common < 0 || common > cd) broken.add(TPL_ROOT.relativize(p).toString());
        }
        assertTrue(broken.isEmpty(),
                "common.css 를 먼저 싣지 않는 페이지: " + broken + " — 글꼴 변수가 비어 브라우저 기본 글꼴로 떨어진다");
    }
}

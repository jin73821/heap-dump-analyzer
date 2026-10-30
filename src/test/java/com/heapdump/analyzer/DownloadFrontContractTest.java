package com.heapdump.analyzer;

import com.heapdump.analyzer.util.HttpHeaderValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 다운로드 화면 계약 (2026-10-29, 함정 71) — JS 러너가 없으므로 소스 계약 + 정규식 교차 검증.
 *
 * <p>사내망 제보: Files 페이지 단건·일괄 다운로드가 "Content-Disposition 이 없으면 세션 만료" 로 판정해, 한글 파일명
 * (Tomcat 이 헤더를 지운 응답)에서 다운로드 대신 로그인 화면으로 이동했다. 여기서 막는 것:
 * <ol>
 *   <li>{@code Common.filenameFromDisposition} 의 정규식을 뽑아 Java 로 컴파일하고 <b>서버가 실제로 만드는 헤더</b>
 *       ({@link HttpHeaderValues})에 적용해 원래 이름이 나오는지 — filename*(UTF-8)이 ASCII 대체값보다 먼저다.</li>
 *   <li>힙·코어·GC 로그 다운로드 버튼이 전부 {@code Common.downloadFile} 을 거치고, 헤더 유무로 세션을 판정하던
 *       옛 코드와 blob 수신이 남아 있지 않은지.</li>
 *   <li>서버 소스에 파일명을 헤더·리다이렉트에 그대로 붙이는 코드가 다시 생기지 않는지.</li>
 * </ol>
 */
class DownloadFrontContractTest {

    private static String read(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    /** common.js 의 {@code var NAME = /…/flags;} 리터럴을 Java Pattern 으로 */
    private static Pattern jsRegex(String js, String name) {
        Matcher m = Pattern.compile("var " + name + " = /(.+)/([a-z]*);").matcher(js);
        assertTrue(m.find(), name + " 리터럴을 찾지 못했다 — 이름을 바꾸면 이 테스트도 고칠 것");
        return Pattern.compile(m.group(1), m.group(2).contains("i") ? Pattern.CASE_INSENSITIVE : 0);
    }

    /** {@code Common.filenameFromDisposition} 과 같은 순서로 Java 에서 흉내 낸다 */
    private static String parse(Pattern ext, Pattern quoted, Pattern token, String cd) {
        Matcher m = ext.matcher(cd);
        if (m.find()) {
            assertTrue(m.group(1).equalsIgnoreCase("UTF-8"), "charset: " + m.group(1));
            return UriUtils.decode(m.group(2), StandardCharsets.UTF_8);
        }
        m = quoted.matcher(cd);
        if (m.find()) return m.group(1).replaceAll("\\\\(.)", "$1");
        m = token.matcher(cd);
        return m.find() ? m.group(1) : null;
    }

    @Test
    @DisplayName("JS 파일명 해석 ↔ 서버 헤더 — filename* 우선, 한글·공백·특수문자·ASCII 모두 원래 이름 / 옛 형식도 읽는다")
    void filenameParserAgreesWithServer() throws IOException {
        String js = read("src/main/resources/static/js/common.js");
        Pattern ext = jsRegex(js, "DISPO_EXT_RE");
        Pattern quoted = jsRegex(js, "DISPO_QUOTED_RE");
        Pattern token = jsRegex(js, "DISPO_TOKEN_RE");

        for (String name : List.of("nodejs 사용여부 전수조사_v1.0.xlsx", "코어 덤프_운영1.core", "운영서버_gc.log.1",
                "a \"b\" \\c;d+e*f~g%h'i.txt", "heapdump-2026.10.29_01.hprof.gz", "캄보디아_AITHER_5Why_보고서_261029_153329.pdf")) {
            assertEquals(name, parse(ext, quoted, token, HttpHeaderValues.attachment(name)), "attachment: " + name);
            assertEquals(name, parse(ext, quoted, token, HttpHeaderValues.inline(name)), "inline: " + name);
        }
        // 옛 서버·다른 서버의 형식
        assertEquals("transfer-logs-20261029.csv",
                parse(ext, quoted, token, "form-data; name=\"attachment\"; filename=\"transfer-logs-20261029.csv\""));
        assertEquals("plain.hprof", parse(ext, quoted, token, "attachment; filename=plain.hprof"));
        assertEquals(null, parse(ext, quoted, token, "attachment"));

        // 함수 안에서도 filename* 를 먼저 본다
        int fn = js.indexOf("Common.filenameFromDisposition = function");
        assertTrue(fn > 0);
        assertTrue(js.indexOf("DISPO_EXT_RE.exec", fn) < js.indexOf("DISPO_QUOTED_RE.exec", fn), "filename* 가 먼저");
    }

    @Test
    @DisplayName("Common.downloadFile — Range 1바이트 사전 확인 · 세션 판정은 401/로그인 리다이렉트 · blob 미사용 · 브라우저 기본 다운로드")
    void downloadFileContract() throws IOException {
        String js = read("src/main/resources/static/js/common.js");
        int start = js.indexOf("Common.downloadFile = function");
        assertTrue(start > 0);
        String body = js.substring(start, js.indexOf("\n    };", start));
        assertTrue(body.contains("'Range': 'bytes=0-0'"), "HEAD 는 Tomcat 이 파일을 끝까지 읽는다 — Range 1바이트");
        assertTrue(body.contains("'X-Requested-With': 'XMLHttpRequest'"), "비-API 경로 오류도 JSON 으로");
        assertTrue(body.contains("r.status === 401 || toLogin"), "세션 만료 = 401 또는 /login 리다이렉트");
        assertFalse(body.contains("indexOf('attachment')"), "헤더 유무로 세션을 판정하지 않는다");
        assertFalse(body.contains(".blob()"), "수 GB 덤프를 브라우저 메모리로 받지 않는다");
        assertTrue(body.contains("a.download = name") && body.contains("a.click()"), "브라우저 기본 다운로드");
        assertTrue(body.contains("ctrl.abort()"), "Range 를 무시한 200 본문은 끊는다");
        assertTrue(body.contains("Common.isDbUnavailable(r.status, t)"), "DB 장애는 공용 판정");
    }

    @Test
    @DisplayName("Files 페이지 단건·일괄 — Common.downloadFile 경유, 로그인 이동은 세션 만료일 때만, 일괄은 실패를 모아 알린다")
    void filesPage() throws IOException {
        String html = read("src/main/resources/templates/files.html");
        assertFalse(html.contains("indexOf('attachment')"), "옛 세션 판정");
        assertFalse(html.contains("cd.match("), "옛 파일명 정규식(ASCII 대체값을 먼저 잡았다)");
        assertFalse(html.contains("r.blob()"), "blob 수신");
        assertTrue(html.contains("Common.downloadFile(downloadUrlOf(filename, kind)"), "단건");
        assertTrue(html.contains("Common.downloadFile(item.url"), "일괄");
        assertTrue(html.contains("function reportBulkDownload(") && html.contains("e.sessionExpired || e.dbUnavailable"),
                "일괄 — 세션 만료·DB 장애에서 멈추고 결과를 모아 알린다");
        assertTrue(html.contains("'/api/core-dump/download/'") && html.contains("'/api/gc-log/download/'") && html.contains("'/download/'"),
                "저장소별 경로(코어·GC 로그는 힙 경로로 못 찾는다)");
        for (String line : html.split("\n")) {
            if (line.contains("location.href = '/login'")) {
                assertTrue(line.contains("sessionExpired"), "세션 만료가 아닌데 로그인으로 보내는 줄: " + line.trim());
            }
        }
    }

    @Test
    @DisplayName("대시보드·분석 화면·GC 로그 목록 — 다운로드를 링크로 바로 열지 않고 Common.downloadFile 경유")
    void otherEntryPoints() throws IOException {
        String index = read("src/main/resources/templates/index.html");
        assertTrue(index.contains("Common.downloadFile(url, { fallbackName: name })"), "대시보드 Recent Files");
        assertFalse(index.contains("window.location.href = (_dlKind"), "옛 직접 이동");

        String analyze = read("src/main/resources/static/js/analyze.js");
        assertTrue(analyze.contains("Common.downloadFile('/download/' + encodeURIComponent(FILENAME)"), "분석 화면");
        assertFalse(analyze.contains("window.location.href = '/download/'"), "옛 직접 이동");

        String gcHtml = read("src/main/resources/templates/gc-log/index.html");
        int a = gcHtml.indexOf("@{/api/gc-log/download/{f}(f=${mem.filename})}");
        assertTrue(a > 0);
        String anchor = gcHtml.substring(a, gcHtml.indexOf(">", a));
        assertTrue(anchor.contains("data-download") && anchor.contains("th:data-filename=\"${mem.filename}\""), "GC 구성 파일 다운로드 표지");
        String gcJs = read("src/main/resources/static/js/gc-log-index.js");
        assertTrue(gcJs.contains("item.hasAttribute('data-download')") && gcJs.contains("ev.preventDefault(); downloadFromMenu(item)"),
                "⋯ 메뉴가 링크 이동을 막고 공용 다운로드로");
        assertTrue(gcJs.contains("Common.downloadFile(a.getAttribute('href')"));

        for (String p : List.of("src/main/resources/templates/rag-settings.html", "src/main/resources/templates/server-logs.html")) {
            String s = read(p);
            assertTrue(s.contains("Common.filenameFromDisposition(r.headers.get('Content-Disposition'))"), p);
            assertFalse(s.contains("/filename\\*?=(?:UTF-8'')?"), "옛 정규식(ASCII 대체값을 먼저 잡는다): " + p);
        }
    }

    @Test
    @DisplayName("서버 — 파일명을 Content-Disposition·redirect 경로에 그대로 붙이는 코드가 없다(HttpHeaderValues 단일 출처)")
    void serverSourcesUseHelper() throws IOException {
        // `filename=\"" + 이름` · `filename*=UTF-8''" + 이름` — 직접 조립하는 두 형태
        Pattern rawDisposition = Pattern.compile("filename(=\\\\\"|\\*=UTF-8'')\"\\s*\\+");
        Pattern rawRedirect = Pattern.compile("\"redirect:[^\"]*/\"\\s*\\+\\s*+(?!HttpHeaderValues\\.pathSegment\\()");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (p.endsWith("HttpHeaderValues.java")) continue;
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (rawDisposition.matcher(src).find()) hits.add(p + " — Content-Disposition 직접 조립");
                if (src.contains("setContentDispositionFormData")) hits.add(p + " — form-data disposition(함정 19)");
                if (rawRedirect.matcher(src).find()) hits.add(p + " — redirect 경로에 이름을 그대로");
            }
        }
        assertEquals(List.of(), hits);
    }
}

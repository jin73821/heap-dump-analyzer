package com.heapdump.analyzer.util;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Tomcat 왕복 (2026-10-29, 함정 71) — 사내망 장애를 그대로 재현한다.
 *
 * <p>Tomcat 은 ISO-8859-1 로 쓸 수 없는 헤더를 <b>예외 없이 지우고</b> 응답을 계속 보낸다. 그래서 단위 테스트로
 * 형식만 보면 "지워진다"는 사실 자체는 확인되지 않는다 — 이 테스트가 내장 Tomcat(앱과 같은 버전)으로
 * ① 한글 Content-Disposition 이 실제로 사라지고 ② 한글 경로 리다이렉트가 Location 없는 302 가 되며
 * ③ {@link HttpHeaderValues} 를 거친 값은 둘 다 살아서 도착함을 고정한다. Tomcat 이 동작을 바꾸면 여기서 드러난다.
 */
class HttpHeaderValuesTomcatTest {

    static final String NAME = "nodejs 사용여부 전수조사_v1.0.xlsx";
    static final String CORE = "코어 덤프.core";

    @TempDir
    static Path base;
    static Tomcat tomcat;
    static int port;
    static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll
    static void start() throws LifecycleException {
        tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(base.toString());
        tomcat.getConnector();
        Context ctx = tomcat.addContext("", base.toString());
        Tomcat.addServlet(ctx, "probe", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
                switch (req.getParameter("m")) {
                    case "rawDisposition" -> res.setHeader("Content-Disposition", "attachment; filename=\"" + NAME + "\"");
                    case "disposition" -> res.setHeader("Content-Disposition", HttpHeaderValues.attachment(NAME));
                    case "rawRedirect" -> { res.sendRedirect("/core-dump/analyze/" + CORE); return; }
                    case "redirect" -> { res.sendRedirect("/core-dump/analyze/" + HttpHeaderValues.pathSegment(CORE)); return; }
                    default -> { res.sendError(400); return; }
                }
                res.setContentType("application/octet-stream");
                res.getOutputStream().write(new byte[] {1, 2, 3});
            }
        });
        ctx.addServletMappingDecoded("/*", "probe");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws LifecycleException {
        if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
    }

    static HttpResponse<byte[]> get(String mode) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x?m=" + mode)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("재현 — 한글 Content-Disposition 은 지워진 채 200·본문은 그대로 나간다(서버 쪽에서는 '성공')")
    void rawDispositionIsDroppedSilently() throws Exception {
        HttpResponse<byte[]> r = get("rawDisposition");
        assertEquals(200, r.statusCode());
        assertEquals(Optional.empty(), r.headers().firstValue("Content-Disposition"));
        assertEquals(3, r.body().length);
    }

    @Test
    @DisplayName("수정 — HttpHeaderValues.attachment 는 그대로 도착한다")
    void dispositionSurvives() throws Exception {
        HttpResponse<byte[]> r = get("disposition");
        assertEquals(200, r.statusCode());
        assertEquals(Optional.of(HttpHeaderValues.attachment(NAME)), r.headers().firstValue("Content-Disposition"));
    }

    @Test
    @DisplayName("재현 — 한글 경로 리다이렉트는 Location 없는 302(브라우저는 빈 화면) / 수정 — 인코딩한 경로는 도착한다")
    void redirectLocation() throws Exception {
        HttpResponse<byte[]> raw = get("rawRedirect");
        assertEquals(302, raw.statusCode());
        assertEquals(Optional.empty(), raw.headers().firstValue("Location"));

        HttpResponse<byte[]> fixed = get("redirect");
        assertEquals(302, fixed.statusCode());
        String loc = fixed.headers().firstValue("Location").orElse("");
        assertTrue(loc.endsWith("/core-dump/analyze/" + HttpHeaderValues.pathSegment(CORE)), loc);
    }
}

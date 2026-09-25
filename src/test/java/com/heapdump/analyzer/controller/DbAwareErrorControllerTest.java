package com.heapdump.analyzer.controller;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import java.sql.SQLNonTransientConnectionException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB 장애 오류 컨트롤러 (2026-09-26). 고정하는 계약: ① DB 연결 불가 + 페이지 → 503 + 자립형 뷰 + Retry-After
 * ② DB 연결 불가 + /api/ 또는 XHR → 503 JSON {success,code:DB_UNAVAILABLE,error} ③ 그 외 예외는 Boot 기본(500·"error" 뷰)
 * ④ '다시 시도' 경로는 절대 경로만.
 */
class DbAwareErrorControllerTest {

    private static DbAwareErrorController controller() {
        return new DbAwareErrorController(new DefaultErrorAttributes(), new ErrorProperties(), List.of());
    }

    private static MockHttpServletRequest errorRequest(String originalUri, Throwable ex) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/error");
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        if (ex != null) req.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ex);
        return req;
    }

    private static Throwable dbDown() {
        return new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLNonTransientConnectionException("Socket fail to connect to host:192.168.56.9, port:3306. Connection refused", "08001"));
    }

    @Test
    @DisplayName("① 페이지 요청 — 503 + error/db-unavailable + Retry-After + 원래 경로")
    void pageGets503View() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ModelAndView mav = controller().errorHtml(errorRequest("/alerts", dbDown()), res);
        assertNotNull(mav);
        assertEquals(DbAwareErrorController.VIEW, mav.getViewName());
        assertEquals(503, res.getStatus());
        assertEquals("30", res.getHeader("Retry-After"));
        assertEquals("/alerts", mav.getModel().get("path"));
        assertEquals(DbAwareErrorController.MESSAGE, mav.getModel().get("message"));
        assertTrue(String.valueOf(mav.getModel().get("detail")).contains("Connection refused"));
    }

    @Test
    @DisplayName("② /api/ 경로 또는 XHR 헤더 — Accept 와 무관하게 503 JSON 3필드 계약")
    void apiGetsJson() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertNull(controller().errorHtml(errorRequest("/api/system/status", dbDown()), res), "응답을 직접 썼으면 null");
        assertEquals(503, res.getStatus());
        assertEquals("30", res.getHeader("Retry-After"));
        assertTrue(res.getContentType().startsWith("application/json"), res.getContentType());
        String body = res.getContentAsString();
        assertTrue(body.contains("\"success\":false") && body.contains("\"code\":\"DB_UNAVAILABLE\"") && body.contains("\"error\":\""), body);

        MockHttpServletRequest xhr = errorRequest("/gc-log", dbDown());
        xhr.addHeader("X-Requested-With", "LiveRefresh");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        assertNull(controller().errorHtml(xhr, res2));
        assertEquals(503, res2.getStatus());

        ResponseEntity<Map<String, Object>> json = controller().error(errorRequest("/api/alerts/top", dbDown()));
        assertEquals(503, json.getStatusCode().value());
        assertEquals("DB_UNAVAILABLE", json.getBody().get("code"));
        assertEquals(false, json.getBody().get("success"));
        assertEquals("30", json.getHeaders().getFirst("Retry-After"));
    }

    @Test
    @DisplayName("③ DB 와 무관한 예외는 Boot 기본 처리 — 500 · 'error' 뷰 · JSON 은 status 500")
    void otherErrorsFallThrough() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        ModelAndView mav = controller().errorHtml(errorRequest("/files", new IllegalStateException("boom")), res);
        assertNotNull(mav);
        assertEquals("error", mav.getViewName());
        assertEquals(500, res.getStatus());
        assertNull(res.getHeader("Retry-After"));

        ResponseEntity<Map<String, Object>> json = controller().error(errorRequest("/api/x", new IllegalStateException("boom")));
        assertEquals(500, json.getStatusCode().value());
        assertFalse(json.getBody().containsKey("code"));

        // 예외 속성이 없으면(직접 /error 접근) 역시 기본 처리
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        assertEquals("error", controller().errorHtml(errorRequest("/x", null), res3).getViewName());
    }

    @Test
    @DisplayName("④ 다시 시도 경로 — 절대 경로만, 프로토콜 상대·개행은 / 로")
    void safePath() {
        assertEquals("/alerts?x=1", DbAwareErrorController.safePath("/alerts?x=1"));
        assertEquals("/", DbAwareErrorController.safePath(null));
        assertEquals("/", DbAwareErrorController.safePath("//evil.example"));
        assertEquals("/", DbAwareErrorController.safePath("https://evil.example"));
        assertEquals("/", DbAwareErrorController.safePath("/a\r\nLocation: x"));
    }
}

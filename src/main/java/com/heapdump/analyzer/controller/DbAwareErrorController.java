package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.util.DbOutageDetector;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DB 장애를 아는 오류 컨트롤러 (2026-09-26, docs/DB_OUTAGE_DISPLAY_PLAN.md ②).
 *
 * <p>세션 저장소가 DB 라 장애 시 요청은 컨트롤러 전 {@code SessionRepositoryFilter} 에서 죽고 Tomcat 이 {@code /error} 로
 * ERROR 디스패치한다 — {@code @ControllerAdvice} 는 닿지 않는다. 예외 사슬이 DB 연결 불가({@link DbOutageDetector})면
 * <b>503 + Retry-After</b> 로 통일한다: 원래 요청이 {@code /api/**}(또는 XHR)면 JSON
 * {@code {success:false, code:DB_UNAVAILABLE, error}} — 기존 3필드 계약, 페이지면 배너·세션·CSRF 를 참조하지 않는
 * 자립형 {@code error/db-unavailable}. 그 외 오류는 Boot 기본({@link BasicErrorController}) 그대로다.
 *
 * <p>⚠ 판정은 {@code DbHealthService} 캐시가 아니라 <b>예외</b>로 한다(예외가 진실, 캐시는 표시용).
 * ⚠ ERROR 디스패치는 세션 필터를 타지 않아 익명이다 — {@code SecurityConfig} 가 ERROR 디스패치를 permitAll + CSRF 면제
 * 하지 않으면 이 컨트롤러 대신 로그인 리다이렉트/401 이 나간다.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class DbAwareErrorController extends BasicErrorController {

    private static final Logger logger = LoggerFactory.getLogger(DbAwareErrorController.class);

    public static final String CODE = "DB_UNAVAILABLE";
    public static final String MESSAGE = "데이터베이스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    public static final int RETRY_AFTER_SEC = 30;
    static final String VIEW = "error/db-unavailable";
    private static final long LOG_INTERVAL_MS = 30_000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AtomicLong lastWarnAt = new AtomicLong();

    /** Boot 4 는 {@code server.error.*} 를 {@link WebProperties#getError()} 로 바인딩한다(ErrorMvcAutoConfiguration 과 동일). */
    @org.springframework.beans.factory.annotation.Autowired   // 생성자가 둘(테스트용 package-private)이라 명시해야 스프링이 고른다
    public DbAwareErrorController(ErrorAttributes errorAttributes, WebProperties webProperties,
                                  ObjectProvider<ErrorViewResolver> errorViewResolvers) {
        this(errorAttributes, webProperties.getError(), errorViewResolvers.orderedStream().toList());
    }

    DbAwareErrorController(ErrorAttributes errorAttributes, ErrorProperties errorProperties, List<ErrorViewResolver> resolvers) {
        super(errorAttributes, errorProperties, resolvers);
    }

    @Override
    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response) {
        Throwable ex = errorException(request);
        if (!DbOutageDetector.isDbOutage(ex)) return super.errorHtml(request, response);
        String path = originalPath(request);
        logOutage(path, ex);
        if (isApiRequest(request, path)) {
            writeJson(response);
            return null;   // 응답을 직접 썼다 — null ModelAndView = 처리 완료
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SEC));
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("message", MESSAGE);
        model.put("path", safePath(path));
        model.put("occurredAt", TS.format(LocalDateTime.now()));
        model.put("retryAfter", RETRY_AFTER_SEC);
        model.put("detail", DbOutageDetector.describe(ex));
        return new ModelAndView(VIEW, model, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    @RequestMapping
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        Throwable ex = errorException(request);
        if (!DbOutageDetector.isDbOutage(ex)) return super.error(request);
        logOutage(originalPath(request), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(RETRY_AFTER_SEC))
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody());
    }

    // ── helpers ──────────────────────────────────────────────

    static Throwable errorException(HttpServletRequest request) {
        Object ex = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (ex == null) ex = request.getAttribute("org.springframework.boot.webmvc.error.DefaultErrorAttributes.ERROR");
        return ex instanceof Throwable ? (Throwable) ex : null;
    }

    static String originalPath(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri != null ? uri.toString() : request.getRequestURI();
    }

    /** 원래 요청이 API/XHR 이면 HTML 대신 JSON — Accept 로 가르면 fetch 의 기본 Accept(모든 타입)가 HTML 을 고른다(함정 14). */
    static boolean isApiRequest(HttpServletRequest request, String path) {
        if (path != null && path.startsWith("/api/")) return true;
        return request.getHeader("X-Requested-With") != null;
    }

    /** 페이지의 '다시 시도' 가 이동할 경로 — 절대 경로만, 개행·스킴 주입 차단. */
    static String safePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) return "/";
        return path;
    }

    static Map<String, Object> jsonBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", CODE);
        body.put("error", MESSAGE);
        body.put("retryAfter", RETRY_AFTER_SEC);
        return body;
    }

    private static void writeJson(HttpServletResponse response) {
        try {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SEC));
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            // 값이 전부 상수(따옴표·역슬래시 없음)라 별도 escape 없이 조립한다 — SecurityConfig.writeApiError 와 같은 형식
            response.getWriter().write("{\"success\":false,\"code\":\"" + CODE + "\",\"error\":\"" + MESSAGE + "\",\"retryAfter\":" + RETRY_AFTER_SEC + "}");
        } catch (IOException e) {
            logger.debug("[DbOutage] API 오류 응답 쓰기 실패(응답이 이미 커밋됨): {}", e.toString());
        }
    }

    /** 장애 중 요청마다 WARN 을 찍으면 로그가 넘친다 — 30초에 한 번 WARN, 나머지는 DEBUG. */
    private void logOutage(String path, Throwable ex) {
        long now = System.currentTimeMillis();
        long prev = lastWarnAt.get();
        if (now - prev >= LOG_INTERVAL_MS && lastWarnAt.compareAndSet(prev, now)) {
            logger.warn("[DbOutage] 데이터베이스 연결 불가 → 503 (path={}) — {}", path, DbOutageDetector.describe(ex));
        } else {
            logger.debug("[DbOutage] 503 (path={}) — {}", path, DbOutageDetector.describe(ex));
        }
    }
}

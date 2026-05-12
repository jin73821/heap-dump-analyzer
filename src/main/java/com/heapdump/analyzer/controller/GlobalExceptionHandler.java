package com.heapdump.analyzer.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전역 예외 핸들러.
 *
 * `IllegalArgumentException` 은 보통 `FilenameValidator` 등 입력 검증에서 던져진다.
 * Accept 헤더가 HTML 인 경우(브라우저 GET 페이지) 홈으로 redirect 하고,
 * 그 외(REST/AJAX/POST 폼)는 400 JSON 응답으로 변환한다.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        logger.warn("[Validation] Rejected {} {}: {}",
                request.getMethod(), request.getRequestURI(), e.getMessage());

        if (preferHtml(request)) {
            ModelAndView mv = new ModelAndView(new RedirectView("/?error=invalidFilename", true));
            return mv;
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private boolean preferHtml(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) return false;

        String xrw = request.getHeader("X-Requested-With");
        if (xrw != null && xrw.equalsIgnoreCase("XMLHttpRequest")) return false;

        String accept = request.getHeader("Accept");
        if (accept == null) return false;
        return accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}

package com.heapdump.analyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 정적 리소스(JS/CSS/SVG/HTML) 응답 Content-Type 에 charset=UTF-8 을 명시한다.
 *
 * <p><b>왜 필요한가 (2026-08-13 사내망 프록시 한글 깨짐):</b>
 * Spring 의 {@code ResourceHttpRequestHandler} 는 확장자 MIME 을
 * {@code ServletContext#getMimeType()} 에서 먼저 찾는데, Tomcat 기본 매핑은
 * {@code text/javascript} 처럼 <b>charset 파라미터가 없다</b>. 직접 접속 시엔
 * 브라우저가 HTML 문서 인코딩(UTF-8)을 스크립트에 상속시켜 문제가 없지만,
 * charset 라벨이 없는 응답은 중간 경로에서 라벨이 채워질 수 있다 — 사내 프록시가
 * {@code charset=ISO-8859-1} 을 붙이면 브라우저는 (HTML 표준상 ISO-8859-1 →
 * windows-1252 별칭) JS 파일을 windows-1252 로 디코딩하고, JS 안의 한글 문자열
 * 리터럴이 그대로 깨진다("업로드 준비 완료" → "ì—…ë¡œë“œ ì¤€ë¹„ ì™„ë£Œ").
 * HTTP charset 은 문서 인코딩 상속보다 우선하므로 페이지는 정상인데 JS 가
 * 만든 모달 텍스트만 깨지는 형태로 나타난다.
 *
 * <p>따라서 <b>서버가 charset 을 명시</b>해 프록시가 채워 넣을 여지를 없앤다.
 * 이 파일의 한글 문자열은 프론트엔드 JS/CSS 전반(JS 1,283줄 · CSS 1줄)에 걸쳐
 * 있어 {@code \\uXXXX} 이스케이프로 회피하는 것은 비현실적이다.
 *
 * <p>{@code setMimeMappings} 가 아니라 {@code addMimeMappings} 를 쓰는 이유:
 * 전자는 매핑 전체를 교체해 Tomcat 기본값(woff2 등)을 잃는다. 후자는 기존
 * 매핑 위에 덮어쓴다. <b>새 정적 리소스 확장자에 텍스트가 들어가면 여기에 추가할 것.</b>
 *
 * <p>참고: SSE({@code text/event-stream})와 {@code fetch().json()/text()} 는
 * 표준이 UTF-8 디코딩을 강제하므로 charset 파라미터 영향을 받지 않는다.
 * Thymeleaf 페이지는 이미 {@code text/html;charset=UTF-8} 로 응답한다.
 */
@Configuration
public class StaticResourceCharsetConfig {

    private static final Logger logger = LoggerFactory.getLogger(StaticResourceCharsetConfig.class);

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> staticResourceCharsetCustomizer() {
        return factory -> {
            MimeMappings mappings = new MimeMappings();
            mappings.add("js",   "text/javascript;charset=UTF-8");
            mappings.add("mjs",  "text/javascript;charset=UTF-8");
            mappings.add("css",  "text/css;charset=UTF-8");
            mappings.add("svg",  "image/svg+xml;charset=UTF-8");
            mappings.add("html", "text/html;charset=UTF-8");
            mappings.add("htm",  "text/html;charset=UTF-8");
            mappings.add("txt",  "text/plain;charset=UTF-8");
            // json 은 의도적으로 제외 — RFC 8259 가 charset 파라미터를 금지하고 항상 UTF-8 로 규정한다.
            factory.addMimeMappings(mappings);
            logger.info("[Charset] 정적 리소스 MIME 에 charset=UTF-8 명시 (js/mjs/css/svg/html/htm/txt) — 프록시 charset 오지정 방지");
        };
    }
}

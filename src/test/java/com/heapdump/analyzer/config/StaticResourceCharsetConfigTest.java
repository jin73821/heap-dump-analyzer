package com.heapdump.analyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 정적 리소스 charset 명시 (2026-08-13 사내망 프록시 한글 깨짐 대응).
 *
 * <p>이 매핑이 사라지면 JS/CSS 응답에 charset 라벨이 없어지고, 라벨 없는 응답에
 * 프록시가 {@code charset=ISO-8859-1} 을 채워 넣으면 브라우저가 JS 를 windows-1252 로
 * 디코딩해 <b>JS 안의 한글 문자열 전부가 깨진다</b>(헤드리스 Chrome 실측 재현:
 * "업로드 준비 완료" → "ì—…ë¡œë“œ ì¤€ë¹„ ì™„ë£Œ"). HTTP charset 이 HTML 문서 인코딩
 * 상속보다 우선하므로 <b>페이지는 정상인데 JS 가 만든 텍스트만</b> 깨져 원인 파악이 어렵다.
 *
 * <p>확장자 문자열 오타나 {@code addMimeMappings} → {@code setMimeMappings} 회귀
 * (기본 매핑 소실)를 잡는 것이 이 테스트의 목적이다.
 */
class StaticResourceCharsetConfigTest {

    /** 커스터마이저를 실제 팩토리에 적용한 뒤 최종 매핑을 돌려준다. */
    private static MimeMappings applied() {
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> customizer =
                new StaticResourceCharsetConfig().staticResourceCharsetCustomizer();
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        customizer.customize(factory);
        return factory.getMimeMappings();
    }

    // ── charset 명시 ────────────────────────────────────────────

    @Test
    @DisplayName("텍스트 정적 리소스는 charset=UTF-8 을 명시한다")
    void textResourcesDeclareUtf8() {
        MimeMappings m = applied();
        for (String ext : new String[]{"js", "mjs", "css", "svg", "html", "htm", "txt"}) {
            String mapping = m.get(ext);
            assertNotNull(mapping, ext + " 매핑이 없다");
            assertEquals(StandardCharsets.UTF_8, MediaType.parseMediaType(mapping).getCharset(),
                    ext + " 매핑에 charset=UTF-8 이 없다: " + mapping);
        }
    }

    @Test
    @DisplayName("모달 텍스트를 만드는 JS 는 text/javascript;charset=UTF-8 로 응답한다")
    void javascriptMappingIsExact() {
        assertEquals("text/javascript;charset=UTF-8", applied().get("js"));
    }

    @Test
    @DisplayName("JSON 에는 charset 을 붙이지 않는다 (RFC 8259)")
    void jsonHasNoCharsetParameter() {
        String mapping = applied().get("json");
        // 기본 매핑(application/json)이 그대로여야 한다
        assertEquals(MimeMappings.DEFAULT.get("json"), mapping);
        assertFalse(mapping != null && mapping.toLowerCase().contains("charset"),
                "application/json 에 charset 파라미터가 붙었다: " + mapping);
    }

    // ── 기본 매핑 보존 (addMimeMappings 회귀 방어) ──────────────

    @Test
    @DisplayName("덮어쓰지 않은 확장자의 기본 매핑은 그대로 유지된다")
    void untouchedDefaultsSurvive() {
        MimeMappings m = applied();
        // setMimeMappings 로 되돌리면 폰트/이미지 매핑이 통째로 사라진다
        assertEquals("image/png", m.get("png"));
        for (String ext : new String[]{"woff2", "woff", "png", "gif", "jpg", "ico", "zip", "pdf"}) {
            assertEquals(MimeMappings.DEFAULT.get(ext), m.get(ext), ext + " 기본 매핑이 유실됐다");
        }
        assertTrue(m.getAll().size() >= MimeMappings.DEFAULT.getAll().size(),
                "매핑 수가 기본값보다 줄었다 — 전체 교체 회귀 의심");
    }
}

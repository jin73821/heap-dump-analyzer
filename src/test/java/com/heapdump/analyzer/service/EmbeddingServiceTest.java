package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * local-onnx 사이드카 health 조회 (2026-08-29, RAG 설정 화면 상태 패널).
 *
 * <p>방어 대상: ① health URL 은 임베딩 URL 에서 <b>유도</b>하되 {@code /embed} 로 끝나지 않으면
 * 추측하지 않는다 — 엉뚱한 서버(OpenAI 등)의 {@code /health} 를 두드려 "정상"으로 오판하면 안 된다.
 * ② {@code sidecarHealth} 는 절대 throw 하지 않는다 — 상태 패널이 페이지 로드마다 부르므로
 * 사이드카가 죽어 있어도 설정 화면 전체가 에러 토스트를 띄우면 안 된다.
 */
class EmbeddingServiceTest {

    @Test
    @DisplayName("healthUrlFor: .../embed → .../health (후행 슬래시 허용)")
    void healthUrlDerivedFromEmbedUrl() {
        assertEquals("http://127.0.0.1:8001/health",
                EmbeddingService.healthUrlFor("http://127.0.0.1:8001/embed"));
        assertEquals("http://127.0.0.1:8001/health",
                EmbeddingService.healthUrlFor("http://127.0.0.1:8001/embed/"));
        assertEquals("http://127.0.0.1:8001/health",
                EmbeddingService.healthUrlFor("  http://127.0.0.1:8001/embed  "));
        // 기본값 상수 자체도 규약을 지켜야 한다 — 상수를 바꾸면 여기서 잡힌다
        assertEquals("http://127.0.0.1:8001/health",
                EmbeddingService.healthUrlFor(EmbeddingService.LOCAL_ONNX_DEFAULT_URL));
    }

    @Test
    @DisplayName("healthUrlFor: /embed 로 끝나지 않으면 추측하지 않고 null")
    void healthUrlIsNotGuessed() {
        assertNull(EmbeddingService.healthUrlFor("https://api.openai.com/v1/embeddings"));
        assertNull(EmbeddingService.healthUrlFor("http://127.0.0.1:8001"));
        assertNull(EmbeddingService.healthUrlFor("http://127.0.0.1:8001/embedding"));
        assertNull(EmbeddingService.healthUrlFor(""));
        assertNull(EmbeddingService.healthUrlFor("   "));
        assertNull(EmbeddingService.healthUrlFor(null));
    }

    @Test
    @DisplayName("sidecarHealth: 연결 거부에도 throw 하지 않고 success=false 를 돌려준다")
    void sidecarHealthNeverThrowsOnConnectionRefused() {
        EmbeddingService svc = new EmbeddingService(new RagConfigService(new HeapDumpConfig()));
        // 포트 1 은 어떤 프로세스도 듣지 않는다 — 즉시 connection refused
        Map<String, Object> r = svc.sidecarHealth("http://127.0.0.1:1/embed", 1);
        assertFalse((Boolean) r.get("success"));
        assertEquals("http://127.0.0.1:1/health", r.get("healthUrl"));
        assertNotNull(r.get("error"));
        assertTrue(String.valueOf(r.get("error")).length() > 0);
    }

    @Test
    @DisplayName("sidecarHealth: URL 을 유도할 수 없으면 HTTP 없이 즉시 실패")
    void sidecarHealthFailsFastWithoutDerivableUrl() {
        EmbeddingService svc = new EmbeddingService(new RagConfigService(new HeapDumpConfig()));
        Map<String, Object> r = svc.sidecarHealth("https://api.openai.com/v1/embeddings", 1);
        assertFalse((Boolean) r.get("success"));
        assertNull(r.get("healthUrl"));
        assertTrue(String.valueOf(r.get("error")).contains("/embed"));

        Map<String, Object> r2 = svc.sidecarHealth(null, 1);
        assertFalse((Boolean) r2.get("success"));
        assertTrue(String.valueOf(r2.get("error")).contains("미설정"));
    }
}

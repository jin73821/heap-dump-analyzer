package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 호출량 제한 게이트 테스트.
 *
 * <p>방어 대상:
 * <ol>
 *   <li>거부된 요청이 <b>다른 축의 할당량을 갉아먹지 않을 것</b> — 분당에서 막힌 요청이
 *       일일 카운터를 소모하면 사용자는 쓰지도 않은 일일 한도를 잃는다.</li>
 *   <li>동시 호출 슬롯이 <b>거부 경로에서도 반드시 반납될 것</b> — 누수되면 이후 모든 호출이
 *       CONCURRENT_LIMIT 으로 영구 차단된다.</li>
 *   <li>0 = 무제한 시맨틱이 축마다 독립적으로 동작할 것.</li>
 * </ol>
 *
 * <p>Spring 컨텍스트 없이 {@code setLlmRateLimit()} 으로 한도를 직접 지정한다
 * ({@code @PostConstruct init()} 은 부르지 않는다).
 */
class LlmRateLimitServiceTest {

    private LlmRateLimitService svc;

    @BeforeEach
    void setUp() {
        svc = new LlmRateLimitService(new HeapDumpConfig());
    }

    /** 허용된 Lease 를 즉시 반납 — 동시 한도를 소모하지 않고 횟수만 쌓고 싶을 때. */
    private static boolean allowAndRelease(LlmRateLimitService svc, String user) {
        LlmRateLimitService.Lease lease = svc.acquire(user, "test");
        boolean ok = lease.allowed();
        lease.close();
        return ok;
    }

    // ── 기본 동작 ────────────────────────────────────────

    @Test
    @DisplayName("비활성화 상태면 무제한 통과한다")
    void disabledGateAlwaysAllows() {
        svc.setLlmRateLimit(false, 1, 1, 1, 1);
        for (int i = 0; i < 50; i++) {
            assertTrue(allowAndRelease(svc, "alice"), i + "번째 호출이 막혔다");
        }
    }

    @Test
    @DisplayName("분당 한도 초과 시 LLM_RATE_LIMIT 으로 거부하고 retryAfter 를 준다")
    void perMinuteLimitRejects() {
        svc.setLlmRateLimit(true, 0, 3, 0, 0);

        for (int i = 0; i < 3; i++) {
            assertTrue(allowAndRelease(svc, "alice"), (i + 1) + "번째는 통과해야 한다");
        }
        LlmRateLimitService.Lease denied = svc.acquire("alice", "test");
        assertFalse(denied.allowed());
        assertEquals("LLM_RATE_LIMIT", denied.code());
        assertNotNull(denied.message());
        assertTrue(denied.retryAfterSeconds() > 0, "재시도 시각 안내가 있어야 한다");
        denied.close();
    }

    @Test
    @DisplayName("사용자별로 버킷이 분리된다")
    void bucketsArePerUser() {
        svc.setLlmRateLimit(true, 0, 2, 0, 0);

        assertTrue(allowAndRelease(svc, "alice"));
        assertTrue(allowAndRelease(svc, "alice"));
        assertFalse(allowAndRelease(svc, "alice"), "alice 는 한도 소진");

        assertTrue(allowAndRelease(svc, "bob"), "bob 은 영향을 받으면 안 된다");
        assertTrue(allowAndRelease(svc, "bob"));
    }

    @Test
    @DisplayName("일일 한도 초과 시 LLM_DAILY_LIMIT 으로 거부한다")
    void perDayLimitRejects() {
        svc.setLlmRateLimit(true, 0, 0, 2, 0);

        assertTrue(allowAndRelease(svc, "alice"));
        assertTrue(allowAndRelease(svc, "alice"));

        LlmRateLimitService.Lease denied = svc.acquire("alice", "test");
        assertFalse(denied.allowed());
        assertEquals("LLM_DAILY_LIMIT", denied.code());
        denied.close();
        assertEquals(2, svc.usedToday("alice"));
    }

    // ── 동시 호출 ────────────────────────────────────────

    @Test
    @DisplayName("동시 호출 상한 초과 시 거부하고, close 하면 슬롯이 즉시 반납된다")
    void concurrentLimitRejectsAndReleases() {
        svc.setLlmRateLimit(true, 0, 0, 0, 2);

        LlmRateLimitService.Lease a = svc.acquire("alice", "test");
        LlmRateLimitService.Lease b = svc.acquire("alice", "test");
        assertTrue(a.allowed());
        assertTrue(b.allowed());
        assertEquals(2, svc.inFlightCount("alice"));

        LlmRateLimitService.Lease c = svc.acquire("alice", "test");
        assertFalse(c.allowed());
        assertEquals("LLM_CONCURRENT_LIMIT", c.code());
        c.close();
        assertEquals(2, svc.inFlightCount("alice"), "거부 Lease 는 슬롯을 건드리면 안 된다");

        a.close();
        assertEquals(1, svc.inFlightCount("alice"));
        assertTrue(svc.acquire("alice", "test").allowed(), "반납된 슬롯을 다시 쓸 수 있어야 한다");
    }

    @Test
    @DisplayName("close 는 멱등 — 중복 호출해도 슬롯이 이중 반납되지 않는다")
    void closeIsIdempotent() {
        svc.setLlmRateLimit(true, 0, 0, 0, 2);

        LlmRateLimitService.Lease a = svc.acquire("alice", "test");
        LlmRateLimitService.Lease b = svc.acquire("alice", "test");
        assertEquals(2, svc.inFlightCount("alice"));

        a.close();
        a.close();
        a.close();
        assertEquals(1, svc.inFlightCount("alice"), "3번 close 해도 1건만 반납돼야 한다");
        b.close();
        assertEquals(0, svc.inFlightCount("alice"));
    }

    @Test
    @DisplayName("횟수 한도로 거부될 때도 먼저 잡은 동시 슬롯은 반납된다 (누수 방지)")
    void rejectedByCountStillReleasesConcurrentSlot() {
        svc.setLlmRateLimit(true, 0, 1, 0, 2);

        assertTrue(allowAndRelease(svc, "alice"));            // 분당 1건 소진
        assertEquals(0, svc.inFlightCount("alice"));

        LlmRateLimitService.Lease denied = svc.acquire("alice", "test");
        assertFalse(denied.allowed());
        assertEquals("LLM_RATE_LIMIT", denied.code());
        assertEquals(0, svc.inFlightCount("alice"),
                "분당 한도에서 막혔어도 동시 슬롯은 반납돼야 한다 — 누수되면 이후 전부 CONCURRENT_LIMIT");
    }

    // ── 축 간 간섭 없음 ──────────────────────────────────

    @Test
    @DisplayName("분당 한도에서 거부된 요청은 일일 카운터를 소모하지 않는다")
    void rejectedRequestDoesNotConsumeDailyQuota() {
        svc.setLlmRateLimit(true, 0, 1, 100, 0);

        assertTrue(allowAndRelease(svc, "alice"));
        assertEquals(1, svc.usedToday("alice"));

        for (int i = 0; i < 5; i++) {
            assertFalse(allowAndRelease(svc, "alice"));
        }
        assertEquals(1, svc.usedToday("alice"),
                "거부된 5건이 일일 할당량을 갉아먹으면 안 된다");
    }

    @Test
    @DisplayName("동시 한도에서 거부된 요청도 일일 카운터를 소모하지 않는다")
    void concurrentRejectDoesNotConsumeDailyQuota() {
        svc.setLlmRateLimit(true, 0, 0, 100, 1);

        LlmRateLimitService.Lease held = svc.acquire("alice", "test");
        assertTrue(held.allowed());
        assertEquals(1, svc.usedToday("alice"));

        LlmRateLimitService.Lease denied = svc.acquire("alice", "test");
        assertFalse(denied.allowed());
        assertEquals(1, svc.usedToday("alice"));
        denied.close();
        held.close();
    }

    // ── 0 = 무제한 / clamp ───────────────────────────────

    @Test
    @DisplayName("0 은 해당 축만 무제한으로 만든다")
    void zeroMeansUnlimitedPerAxis() {
        svc.setLlmRateLimit(true, 0, 0, 0, 0);
        for (int i = 0; i < 100; i++) {
            assertTrue(allowAndRelease(svc, "alice"));
        }
        assertEquals(0, svc.usedToday("alice"), "일일 무제한이면 카운트도 하지 않는다");

        // 동시만 제한 — 횟수는 계속 무제한
        svc.setLlmRateLimit(true, 0, 0, 0, 1);
        LlmRateLimitService.Lease a = svc.acquire("bob", "test");
        assertTrue(a.allowed());
        assertFalse(svc.acquire("bob", "test").allowed());
        a.close();
    }

    @Test
    @DisplayName("음수는 0(무제한)으로, 과대값은 상한으로 정규화된다")
    void valuesAreNormalized() {
        svc.setLlmRateLimit(true, -5, -1, -100, -3);
        assertEquals(0, svc.getLlmRateLimitPerSecond());
        assertEquals(0, svc.getLlmRateLimitPerMinute());
        assertEquals(0, svc.getLlmRateLimitPerDay());
        assertEquals(0, svc.getLlmRateLimitConcurrent());

        svc.setLlmRateLimit(true, 9999, 99999, 9_999_999, 999);
        assertEquals(100, svc.getLlmRateLimitPerSecond());
        assertEquals(1_000, svc.getLlmRateLimitPerMinute());
        assertEquals(100_000, svc.getLlmRateLimitPerDay());
        assertEquals(50, svc.getLlmRateLimitConcurrent());
    }

    // ── 영속화 3-hook ────────────────────────────────────

    @Test
    @DisplayName("settings.json 왕복 — collect → apply 후 값이 보존된다")
    void settingsRoundTrip() {
        svc.setLlmRateLimit(true, 5, 30, 700, 4);

        Map<String, Object> saved = new LinkedHashMap<>();
        svc.collectSettings(saved);

        LlmRateLimitService restored = new LlmRateLimitService(new HeapDumpConfig());
        restored.applyFromSettings(saved);

        assertTrue(restored.isLlmRateLimitEnabled());
        assertEquals(5, restored.getLlmRateLimitPerSecond());
        assertEquals(30, restored.getLlmRateLimitPerMinute());
        assertEquals(700, restored.getLlmRateLimitPerDay());
        assertEquals(4, restored.getLlmRateLimitConcurrent());
    }

    @Test
    @DisplayName("복원 후에도 한도가 실제로 걸린다 (limiter 재생성 확인)")
    void restoredLimitsAreEffective() {
        svc.setLlmRateLimit(true, 0, 2, 0, 0);
        Map<String, Object> saved = new LinkedHashMap<>();
        svc.collectSettings(saved);

        LlmRateLimitService restored = new LlmRateLimitService(new HeapDumpConfig());
        restored.applyFromSettings(saved);

        assertTrue(allowAndRelease(restored, "alice"));
        assertTrue(allowAndRelease(restored, "alice"));
        assertFalse(allowAndRelease(restored, "alice"),
                "applyFromSettings 후 limiter 를 재생성하지 않으면 옛 한도가 남는다");
    }

    @Test
    @DisplayName("application.properties 키 5개가 모두 채워진다")
    void applicationPropertiesKeys() {
        svc.setLlmRateLimit(true, 2, 20, 500, 3);
        Map<String, String> updates = new LinkedHashMap<>();
        svc.collectApplicationProperties(updates);

        assertEquals("true", updates.get("llm.ratelimit.enabled"));
        assertEquals("2", updates.get("llm.ratelimit.per-second"));
        assertEquals("20", updates.get("llm.ratelimit.per-minute"));
        assertEquals("500", updates.get("llm.ratelimit.per-day"));
        assertEquals("3", updates.get("llm.ratelimit.concurrent"));
    }

    // ── HTTP 매핑 ────────────────────────────────────────

    @Test
    @DisplayName("호출량 거부 결과만 429 로, 나머지 실패는 200 을 유지한다")
    void httpMappingOnlyRateLimitBecomes429() {
        Map<String, Object> limited = new LinkedHashMap<>();
        limited.put("success", false);
        limited.put("errorCode", "LLM_RATE_LIMIT");
        limited.put("retryAfterSeconds", 30L);
        assertTrue(LlmRateLimitService.isRateLimited(limited));
        assertEquals(429, LlmRateLimitService.toResponse(limited).getStatusCode().value());
        assertEquals("30", LlmRateLimitService.toResponse(limited).getHeaders().getFirst("Retry-After"));

        Map<String, Object> disabled = new LinkedHashMap<>();
        disabled.put("success", false);
        disabled.put("errorCode", "LLM_DISABLED");
        assertFalse(LlmRateLimitService.isRateLimited(disabled));
        assertEquals(200, LlmRateLimitService.toResponse(disabled).getStatusCode().value(),
                "기존 오류 코드의 응답 상태를 바꾸면 프런트 분기가 깨진다");

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        assertEquals(200, LlmRateLimitService.toResponse(ok).getStatusCode().value());
    }
}

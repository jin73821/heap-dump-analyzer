package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 호출량 제한 서비스 (2026-08-12).
 *
 * <p>도입 배경: {@code /api/llm/*} · {@code /api/ai-chat/*} 는 로그인만 통과하면 호출 횟수에
 * 제동이 전혀 없었다. 화면의 전송 버튼 비활성화는 UI 힌트일 뿐이라 스크립트로 직접 호출하면
 * 업스트림 과금이 무한정 발생한다. 이 서비스가 <b>사용자별</b> 4 축으로 그 상한을 건다.
 *
 * <ul>
 *   <li>초당(burst) — 연타/스크립트 폭주 차단</li>
 *   <li>분당 — 지속적인 남용 차단</li>
 *   <li>일일 — 과금 총량 상한</li>
 *   <li>동시 — in-flight 상한. 스트리밍이 read-timeout 까지 스레드를 잡으므로 실효성이 가장 크다</li>
 * </ul>
 *
 * <p>각 항목 <b>0 = 무제한</b>. 판정 순서는 <i>부수효과가 없는 검사 → 기록하는 검사</i> 로,
 * 거부된 요청이 다른 축의 할당량을 갉아먹지 않도록 한다(일일 카운터는 통과가 확정된 뒤에만 증가).
 *
 * <p>영속화는 LLM/RAG/2FA/비밀번호정책과 동일한 3-hook
 * ({@code applyFromSettings}/{@code collectSettings}/{@code collectApplicationProperties}) 이며,
 * 트리거는 호출자({@code HeapDumpAnalyzerService})가 담당한다.
 */
@Component
public class LlmRateLimitService {

    private static final Logger logger = LoggerFactory.getLogger(LlmRateLimitService.class);

    /** 인증 정보를 못 찾은 백그라운드 호출이 쓰는 버킷 이름. */
    static final String SYSTEM_USER = "system";

    private final HeapDumpConfig config;

    // ── 런타임 설정 (5 필드) ──────────────────────────────────────
    private volatile boolean llmRateLimitEnabled;
    private volatile int     llmRateLimitPerSecond;
    private volatile int     llmRateLimitPerMinute;
    private volatile int     llmRateLimitPerDay;
    private volatile int     llmRateLimitConcurrent;

    /** 한도 변경 시 통째로 교체 — RateLimiter 는 생성 시점에 한도가 고정되기 때문. */
    private volatile RateLimiter secondLimiter;
    private volatile RateLimiter minuteLimiter;

    /** 사용자별 일일 카운터. 날짜가 바뀌면 자가 리셋. */
    private final ConcurrentHashMap<String, DayCount> dayCounts = new ConcurrentHashMap<>();
    /** 사용자별 in-flight 수. 한도가 런타임에 바뀌므로 Semaphore 대신 CAS 카운터. */
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public LlmRateLimitService(HeapDumpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.llmRateLimitEnabled    = config.isLlmRateLimitEnabled();
        this.llmRateLimitPerSecond  = Math.max(0, config.getLlmRateLimitPerSecond());
        this.llmRateLimitPerMinute  = Math.max(0, config.getLlmRateLimitPerMinute());
        this.llmRateLimitPerDay     = Math.max(0, config.getLlmRateLimitPerDay());
        this.llmRateLimitConcurrent = Math.max(0, config.getLlmRateLimitConcurrent());
        rebuildLimiters();
    }

    private void rebuildLimiters() {
        this.secondLimiter = new RateLimiter(Math.max(1, llmRateLimitPerSecond), 1_000L);
        this.minuteLimiter = new RateLimiter(Math.max(1, llmRateLimitPerMinute), 60_000L);
    }

    // ── Getter ────────────────────────────────────────────────────

    public boolean isLlmRateLimitEnabled()  { return llmRateLimitEnabled; }
    public int     getLlmRateLimitPerSecond()  { return llmRateLimitPerSecond; }
    public int     getLlmRateLimitPerMinute()  { return llmRateLimitPerMinute; }
    public int     getLlmRateLimitPerDay()     { return llmRateLimitPerDay; }
    public int     getLlmRateLimitConcurrent() { return llmRateLimitConcurrent; }

    /** 특정 사용자의 오늘 사용량 — UI/진단용. */
    public int usedToday(String user) {
        DayCount dc = dayCounts.get(key(user));
        if (dc == null) return 0;
        synchronized (dc) {
            return dc.day.equals(LocalDate.now()) ? dc.n : 0;
        }
    }

    // ── Setter ────────────────────────────────────────────────────

    /**
     * 5 개 값 일괄 갱신. 음수는 0(무제한)으로 정규화하고 상식 범위로 clamp 한다.
     * 영속화(persistSettings)는 호출자가 트리거.
     */
    public void setLlmRateLimit(boolean enabled, int perSecond, int perMinute, int perDay, int concurrent) {
        this.llmRateLimitEnabled    = enabled;
        this.llmRateLimitPerSecond  = clamp(perSecond, 100);
        this.llmRateLimitPerMinute  = clamp(perMinute, 1_000);
        this.llmRateLimitPerDay     = clamp(perDay, 100_000);
        this.llmRateLimitConcurrent = clamp(concurrent, 50);
        rebuildLimiters();
        logger.info("[LlmRateLimit] 설정 변경 — enabled={}, perSecond={}, perMinute={}, perDay={}, concurrent={} (0=무제한)",
                llmRateLimitEnabled, llmRateLimitPerSecond, llmRateLimitPerMinute,
                llmRateLimitPerDay, llmRateLimitConcurrent);
    }

    private static int clamp(int v, int max) {
        if (v <= 0) return 0;          // 0 이하 = 무제한
        return Math.min(v, max);
    }

    // ── 게이트 ────────────────────────────────────────────────────

    /**
     * 호출 권한 1건 획득. <b>반환된 Lease 는 반드시 {@link Lease#close()} 해야 한다</b> —
     * 동시 호출 슬롯이 여기서만 반납되기 때문이다.
     *
     * <p>거부되면 {@link Lease#allowed()} 가 false 이고 슬롯을 점유하지 않으므로
     * close 는 무해한 no-op 이다(try-with-resources 로 감싸도 안전).
     */
    public Lease acquire(String user, String scope) {
        if (!llmRateLimitEnabled) return Lease.allowed(this, null);

        String k = key(user);

        // 1) 동시 호출 — 슬롯을 먼저 잡아야 이후 검사 통과 시 곧바로 진행할 수 있다.
        if (llmRateLimitConcurrent > 0 && !enterConcurrent(k)) {
            return reject(k, scope, "LLM_CONCURRENT_LIMIT",
                    "동시에 처리 중인 AI 요청이 " + llmRateLimitConcurrent + "건을 초과했습니다. 진행 중인 응답이 끝난 뒤 다시 시도하세요.",
                    1, null);
        }

        // 이후 단계에서 거부되면 위에서 잡은 슬롯을 되돌려줘야 한다.
        // 동시 한도가 0(무제한)이면 애초에 잡지 않았으므로 반납 대상도 없다.
        final String heldSlot = (llmRateLimitConcurrent > 0) ? k : null;

        // 2) 일일 한도 — 읽기 전용 검사. 실제 증가는 모든 검사를 통과한 뒤(아래 3-1).
        if (llmRateLimitPerDay > 0 && peekToday(k) >= llmRateLimitPerDay) {
            return reject(k, scope, "LLM_DAILY_LIMIT",
                    "오늘 사용 가능한 AI 요청 " + llmRateLimitPerDay + "건을 모두 사용했습니다. 내일 다시 시도하거나 관리자에게 문의하세요.",
                    secondsUntilMidnight(), heldSlot);
        }

        // 3) 초당/분당 — isAllowed 는 통과 시 기록을 남긴다(부수효과).
        if (llmRateLimitPerSecond > 0 && !secondLimiter.isAllowed(k)) {
            return reject(k, scope, "LLM_RATE_LIMIT",
                    "AI 요청이 너무 빠릅니다 (초당 " + llmRateLimitPerSecond + "건 제한). 잠시 후 다시 시도하세요.",
                    Math.max(1, ceilSeconds(secondLimiter.retryAfterMillis(k))), heldSlot);
        }
        if (llmRateLimitPerMinute > 0 && !minuteLimiter.isAllowed(k)) {
            return reject(k, scope, "LLM_RATE_LIMIT",
                    "AI 요청이 분당 한도(" + llmRateLimitPerMinute + "건)를 초과했습니다. 잠시 후 다시 시도하세요.",
                    Math.max(1, ceilSeconds(minuteLimiter.retryAfterMillis(k))), heldSlot);
        }

        // 3-1) 통과 확정 — 이제서야 일일 카운터 증가
        if (llmRateLimitPerDay > 0) incrementToday(k);

        return Lease.allowed(this, heldSlot);
    }

    /** 거부 Lease 생성 + 점유했던 동시 슬롯 반납 + 감사 로깅. */
    private Lease reject(String k, String scope, String code, String message,
                         long retryAfterSeconds, String slotHolderToRelease) {
        if (slotHolderToRelease != null) exitConcurrent(slotHolderToRelease);
        logger.warn("[LlmRateLimit] 호출 거부 — user={}, scope={}, code={}, retryAfter={}s "
                        + "(perSecond={}, perMinute={}, perDay={}, concurrent={}, usedToday={})",
                k, scope, code, retryAfterSeconds, llmRateLimitPerSecond, llmRateLimitPerMinute,
                llmRateLimitPerDay, llmRateLimitConcurrent, peekToday(k));
        return Lease.denied(code, message, retryAfterSeconds);
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999) / 1000;
    }

    private static long secondsUntilMidnight() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return Math.max(1, java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds());
    }

    static String key(String user) {
        return (user == null || user.trim().isEmpty()) ? SYSTEM_USER : user.trim();
    }

    // ── 동시 호출 카운터 ──────────────────────────────────────────

    private boolean enterConcurrent(String k) {
        AtomicInteger c = inFlight.computeIfAbsent(k, x -> new AtomicInteger());
        while (true) {
            int cur = c.get();
            if (cur >= llmRateLimitConcurrent) return false;
            if (c.compareAndSet(cur, cur + 1)) return true;
        }
    }

    void exitConcurrent(String k) {
        AtomicInteger c = inFlight.get(k);
        if (c == null) return;
        // 한도를 낮추는 설정 변경 도중에도 음수로 내려가지 않게 방어
        c.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /** 진단용 — 현재 in-flight 수. */
    public int inFlightCount(String user) {
        AtomicInteger c = inFlight.get(key(user));
        return c != null ? c.get() : 0;
    }

    // ── 일일 카운터 ───────────────────────────────────────────────

    private static final class DayCount {
        LocalDate day = LocalDate.now();
        int n;
    }

    private int peekToday(String k) {
        DayCount dc = dayCounts.get(k);
        if (dc == null) return 0;
        synchronized (dc) {
            if (!dc.day.equals(LocalDate.now())) return 0;   // 날짜가 바뀌었으면 사실상 0
            return dc.n;
        }
    }

    private void incrementToday(String k) {
        DayCount dc = dayCounts.computeIfAbsent(k, x -> new DayCount());
        synchronized (dc) {
            LocalDate today = LocalDate.now();
            if (!dc.day.equals(today)) { dc.day = today; dc.n = 0; }
            dc.n++;
        }
    }

    // ── 정리 ──────────────────────────────────────────────────────

    /** 만료된 슬라이딩 윈도우 항목과 지난 날짜/유휴 사용자 엔트리 제거 (메모리 누수 방지). */
    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        secondLimiter.evictExpired();
        minuteLimiter.evictExpired();
        LocalDate today = LocalDate.now();
        dayCounts.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return !e.getValue().day.equals(today);
            }
        });
        inFlight.entrySet().removeIf(e -> e.getValue().get() <= 0);
    }

    // ── Settings 영속화 hook (3-hook) ─────────────────────────────

    public void applyFromSettings(Map<String, Object> saved) {
        if (saved.containsKey("llmRateLimitEnabled")) {
            this.llmRateLimitEnabled = Boolean.parseBoolean(String.valueOf(saved.get("llmRateLimitEnabled")));
        }
        if (saved.containsKey("llmRateLimitPerSecond")) {
            this.llmRateLimitPerSecond = clamp(Integer.parseInt(String.valueOf(saved.get("llmRateLimitPerSecond"))), 100);
        }
        if (saved.containsKey("llmRateLimitPerMinute")) {
            this.llmRateLimitPerMinute = clamp(Integer.parseInt(String.valueOf(saved.get("llmRateLimitPerMinute"))), 1_000);
        }
        if (saved.containsKey("llmRateLimitPerDay")) {
            this.llmRateLimitPerDay = clamp(Integer.parseInt(String.valueOf(saved.get("llmRateLimitPerDay"))), 100_000);
        }
        if (saved.containsKey("llmRateLimitConcurrent")) {
            this.llmRateLimitConcurrent = clamp(Integer.parseInt(String.valueOf(saved.get("llmRateLimitConcurrent"))), 50);
        }
        rebuildLimiters();
    }

    public void collectSettings(Map<String, Object> settings) {
        settings.put("llmRateLimitEnabled", llmRateLimitEnabled);
        settings.put("llmRateLimitPerSecond", llmRateLimitPerSecond);
        settings.put("llmRateLimitPerMinute", llmRateLimitPerMinute);
        settings.put("llmRateLimitPerDay", llmRateLimitPerDay);
        settings.put("llmRateLimitConcurrent", llmRateLimitConcurrent);
    }

    public void collectApplicationProperties(Map<String, String> updates) {
        updates.put("llm.ratelimit.enabled", String.valueOf(llmRateLimitEnabled));
        updates.put("llm.ratelimit.per-second", String.valueOf(llmRateLimitPerSecond));
        updates.put("llm.ratelimit.per-minute", String.valueOf(llmRateLimitPerMinute));
        updates.put("llm.ratelimit.per-day", String.valueOf(llmRateLimitPerDay));
        updates.put("llm.ratelimit.concurrent", String.valueOf(llmRateLimitConcurrent));
    }

    // ── HTTP 응답 매핑 ────────────────────────────────────────────

    /** 호출량 거부를 뜻하는 errorCode 3종. 코드 정의와 같은 자리에 둬서 드리프트를 막는다. */
    private static final java.util.Set<String> RATE_LIMIT_CODES =
            java.util.Set.of("LLM_RATE_LIMIT", "LLM_DAILY_LIMIT", "LLM_CONCURRENT_LIMIT");

    public static boolean isRateLimited(Map<String, Object> result) {
        return result != null && RATE_LIMIT_CODES.contains(String.valueOf(result.get("errorCode")));
    }

    /**
     * LLM 호출 결과 맵 → HTTP 응답.
     * 호출량 거부면 <b>429 + Retry-After</b>, 그 외에는 기존과 동일하게 200
     * (LLM_DISABLED/NO_API_KEY 등은 종전대로 본문의 success=false 로 전달).
     */
    public static org.springframework.http.ResponseEntity<Map<String, Object>> toResponse(Map<String, Object> result) {
        if (!isRateLimited(result)) {
            return org.springframework.http.ResponseEntity.ok(result);
        }
        org.springframework.http.ResponseEntity.BodyBuilder b =
                org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        Object retryAfter = result.get("retryAfterSeconds");
        if (retryAfter != null) b.header("Retry-After", String.valueOf(retryAfter));
        return b.body(result);
    }

    // ── Lease ─────────────────────────────────────────────────────

    /**
     * 획득한 호출 권한. 동시 호출 슬롯을 물고 있으므로 <b>반드시 close</b> 해야 한다.
     * 거부 Lease 는 슬롯을 갖지 않아 close 가 no-op.
     */
    public static final class Lease implements AutoCloseable {

        private final LlmRateLimitService owner;
        private final String slotKey;            // null 이면 반납할 슬롯 없음
        private final boolean allowed;
        private final String code;
        private final String message;
        private final long retryAfterSeconds;
        private volatile boolean closed;

        private Lease(LlmRateLimitService owner, String slotKey, boolean allowed,
                      String code, String message, long retryAfterSeconds) {
            this.owner = owner;
            this.slotKey = slotKey;
            this.allowed = allowed;
            this.code = code;
            this.message = message;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static Lease allowed(LlmRateLimitService owner, String slotKey) {
            return new Lease(owner, slotKey, true, null, null, 0);
        }

        static Lease denied(String code, String message, long retryAfterSeconds) {
            return new Lease(null, null, false, code, message, retryAfterSeconds);
        }

        public boolean allowed()          { return allowed; }
        public String  code()             { return code; }
        public String  message()          { return message; }
        public long    retryAfterSeconds(){ return retryAfterSeconds; }

        /** 멱등 — 중복 호출해도 슬롯이 이중 반납되지 않는다. */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (owner != null && slotKey != null) owner.exitConcurrent(slotKey);
        }
    }
}

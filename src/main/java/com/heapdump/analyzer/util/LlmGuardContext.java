package com.heapdump.analyzer.util;

import com.heapdump.analyzer.service.LoginHistoryRecorder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * LLM Guard 기록용 요청 맥락(기능·URI·클라이언트 IP) + 직전 출력 가림 결과 (2026-11-26).
 *
 * <p>바인딩이 없으면 {@link RequestContextHolder} 에서 값을 읽고 URI 로 기능을 정한다 — 그래서
 * 요청 스레드에서 끝나는 동기 JSON 경로는 컨트롤러를 고치지 않아도 된다.
 *
 * <p>⚠ <b>SSE 2곳</b>은 별도 스레드에서 LLM 을 부르므로 RequestContextHolder 가 비어 있다.
 * 요청 스레드에서 {@link #capture()} 하고, 스레드 <b>첫 줄에서 {@link #bind}</b>,
 * {@code finally} 에서 {@link #clear()} 할 것. (사용자 식별은 기존 {@code DelegatingSecurityContextRunnable} 이 맡는다 — 함정 31.)
 */
public final class LlmGuardContext {

    private static final Logger logger = LoggerFactory.getLogger(LlmGuardContext.class);

    /** 불변 스냅샷. */
    public static final class Snapshot {
        private final String feature;
        private final String requestUri;
        private final String clientIp;

        public Snapshot(String feature, String requestUri, String clientIp) {
            this.feature = feature;
            this.requestUri = requestUri;
            this.clientIp = clientIp;
        }

        public String feature()    { return feature; }
        public String requestUri() { return requestUri; }
        public String clientIp()   { return clientIp; }
    }

    private static final Snapshot EMPTY = new Snapshot("system", null, null);

    private static final ThreadLocal<Snapshot> BOUND = new ThreadLocal<>();
    /** 바인딩과 무관하게 동작 — 스트림 onDone 이 같은 스레드의 컨트롤러 콜백에 넘겨 준다. */
    private static final ThreadLocal<Map<String, Object>> LAST_OUTPUT_GUARD = new ThreadLocal<>();

    private LlmGuardContext() {}

    /** 요청 스레드에서 호출 — 현재 바인딩, 없으면 현재 HTTP 요청에서 만든 스냅샷. */
    public static Snapshot capture() {
        Snapshot b = BOUND.get();
        if (b != null) return b;
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (ra instanceof ServletRequestAttributes) {
                HttpServletRequest req = ((ServletRequestAttributes) ra).getRequest();
                String uri = req.getRequestURI();
                return new Snapshot(featureOf(uri), truncate(uri, 255),
                        truncate(LoginHistoryRecorder.extractIp(req), 45));
            }
        } catch (RuntimeException e) {
            logger.debug("[LlmGuard] 요청 맥락 조회 실패 — system 으로 기록: {}", e.toString());
        }
        return EMPTY;
    }

    public static void bind(Snapshot s) {
        if (s != null) BOUND.set(s);
    }

    public static void clear() {
        BOUND.remove();
        LAST_OUTPUT_GUARD.remove();
    }

    public static void setLastOutputGuard(Map<String, Object> guard) {
        if (guard == null) LAST_OUTPUT_GUARD.remove();
        else LAST_OUTPUT_GUARD.set(guard);
    }

    /** 직전 스트림의 출력 가림 결과(없으면 null). 읽으면 지워진다. */
    public static Map<String, Object> takeLastOutputGuard() {
        Map<String, Object> g = LAST_OUTPUT_GUARD.get();
        LAST_OUTPUT_GUARD.remove();
        return g;
    }

    /** URI → 기능 이름. 기록·통계 표시용이라 정밀할 필요는 없다. */
    public static String featureOf(String uri) {
        if (uri == null) return "system";
        if (uri.startsWith("/api/ai-chat/")) return "chat";
        if (uri.startsWith("/api/llm/chat")) return "heap-chat";
        if (uri.startsWith("/api/llm/compare/")) return "compare-ai";
        if (uri.startsWith("/api/llm/")) return "heap-ai";
        if (uri.startsWith("/api/gc-log/")) return "gc-ai";
        if (uri.startsWith("/api/thread-dump/heap/")) return "heap-thread-ai";
        if (uri.startsWith("/api/thread-dump/")) return "thread-ai";
        if (uri.startsWith("/api/core-dump/")) return "core-ai";
        if (uri.startsWith("/api/admin/llm-guard/")) return "admin-test";
        return "other";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

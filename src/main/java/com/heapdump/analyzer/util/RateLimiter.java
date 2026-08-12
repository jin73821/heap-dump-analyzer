package com.heapdump.analyzer.util;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class RateLimiter {

    private final ConcurrentHashMap<String, Deque<Long>> counters = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;

    public RateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    public boolean isAllowed(String key) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMillis;

        Deque<Long> timestamps = counters.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxRequests) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    /**
     * {@code key} 가 지금 거부된다면 몇 ms 뒤에 다시 허용되는지 — 창 안의 가장 오래된
     * 요청이 빠져나가는 시각까지의 잔여. 한도 미만이면 0.
     *
     * <p>{@link #isAllowed(String)} 와 달리 <b>기록을 남기지 않는다</b>
     * (거부 응답의 Retry-After 계산 전용).
     */
    public long retryAfterMillis(String key) {
        Deque<Long> timestamps = counters.get(key);
        if (timestamps == null || timestamps.size() < maxRequests) return 0;
        Long oldest = timestamps.peekFirst();
        if (oldest == null) return 0;
        return Math.max(0, oldest + windowMillis - System.currentTimeMillis());
    }

    public void evictExpired() {
        long cutoff = System.currentTimeMillis() - windowMillis;
        Iterator<Map.Entry<String, Deque<Long>>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = it.next();
            Deque<Long> ts = entry.getValue();
            while (!ts.isEmpty() && ts.peekFirst() < cutoff) {
                ts.pollFirst();
            }
            if (ts.isEmpty()) {
                it.remove();
            }
        }
    }
}

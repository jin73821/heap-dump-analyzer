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

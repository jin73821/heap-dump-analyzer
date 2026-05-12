package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 분석 결과 1차 메모리 캐시 (key = filename).
 *
 * 디스크 폴백/FS 정리는 호출자(HeapDumpAnalyzerService)가 담당하고,
 * 본 컴포넌트는 ConcurrentHashMap 순수 연산만 책임진다.
 */
@Component
public class HeapAnalysisResultCache {

    private final ConcurrentHashMap<String, HeapAnalysisResult> memCache = new ConcurrentHashMap<>();

    public HeapAnalysisResult get(String filename) {
        return memCache.get(filename);
    }

    public void put(String filename, HeapAnalysisResult result) {
        memCache.put(filename, result);
    }

    public void remove(String filename) {
        memCache.remove(filename);
    }

    public int size() {
        return memCache.size();
    }

    public Collection<HeapAnalysisResult> values() {
        return Collections.unmodifiableCollection(memCache.values());
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(memCache.keySet());
    }

    public Set<Map.Entry<String, HeapAnalysisResult>> entries() {
        return Collections.unmodifiableSet(memCache.entrySet());
    }
}

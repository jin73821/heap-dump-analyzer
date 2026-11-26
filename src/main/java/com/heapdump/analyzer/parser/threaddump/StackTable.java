package com.heapdump.analyzer.parser.threaddump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 프레임·스택 사전. 풀 스레드 수천 개가 같은 스택을 갖는 것이 보통이라, 스레드 레코드는 스택 id(정수) 하나만 든다 —
 * 그래서 파싱 중 메모리가 파일 크기가 아니라 <b>서로 다른 스택 수</b>에 비례한다.
 */
final class StackTable {

    private final Map<String, Integer> frameIds = new HashMap<>();
    private final List<String> frames = new ArrayList<>();
    private final Map<Key, Integer> stackIds = new HashMap<>();
    private final List<int[]> stacks = new ArrayList<>();

    int frame(String text) {
        Integer id = frameIds.get(text);
        if (id != null) return id;
        int n = frames.size();
        frames.add(text);
        frameIds.put(text, n);
        return n;
    }

    /** @return 스택 id. 프레임이 없으면 -1. */
    int stack(int[] buf, int len) {
        if (len <= 0) return -1;
        int[] copy = Arrays.copyOf(buf, len);
        Key k = new Key(copy);
        Integer id = stackIds.get(k);
        if (id != null) return id;
        int n = stacks.size();
        stacks.add(copy);
        stackIds.put(k, n);
        return n;
    }

    int[] framesOf(int stackId) { return stackId < 0 ? EMPTY : stacks.get(stackId); }

    String frameText(int frameId) { return frames.get(frameId); }

    /** 맨 위 프레임 문자열. 스택이 없으면 빈 문자열. */
    String top(int stackId) {
        int[] f = framesOf(stackId);
        return f.length == 0 ? "" : frames.get(f[0]);
    }

    int stackCount() { return stacks.size(); }

    private static final int[] EMPTY = new int[0];

    private static final class Key {
        private final int[] f;
        private final int hash;
        Key(int[] f) { this.f = f; this.hash = Arrays.hashCode(f); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) { return o instanceof Key k && Arrays.equals(f, k.f); }
    }
}

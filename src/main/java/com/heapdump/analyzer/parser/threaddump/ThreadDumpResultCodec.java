package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * {@link ThreadDumpResult} ↔ JSON (Jackson 3). {@code thread_dump_result_detail.result_json} 상한(4MB)을 넘으면
 * 원문 샘플 → 스택 깊이 → 스레드 수 순으로 줄여 다시 직렬화하고 {@code RESULT_TRIMMED} 소견을 붙인다.
 * <b>소견·KPI·스냅샷 표·교착·파싱 진단은 절대 줄이지 않는다.</b>
 *
 * <p>⚠ 결과는 인덱스로 얽혀 있다(스레드 → 스택 → 프레임, 소견·락·교착 → 스레드). 무엇을 빼든 반드시
 * {@link #retainTracks}·{@link #compactDictionaries} 를 거쳐 재색인할 것 — 리스트에서 원소만 지우면 화면이 엉뚱한 스레드를 가리킨다.
 */
public final class ThreadDumpResultCodec {

    private ThreadDumpResultCodec() {}

    public static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static String toJson(ThreadDumpResult r) {
        return MAPPER.writeValueAsString(r);
    }

    public static String toJsonCapped(ThreadDumpResult r) {
        return toJsonCapped(r, MAX_JSON_BYTES);
    }

    /** 상한 안에 들어오도록 줄여 직렬화한다(원본 객체도 함께 줄어든다 — 화면과 저장본이 같아야 하므로 의도된 동작). */
    public static String toJsonCapped(ThreadDumpResult r, int maxBytes) {
        String s = toJson(r);
        if (size(s) <= maxBytes) return s;
        int step = 0;
        while (size(s) > maxBytes && step < 8) {
            switch (step++) {
                case 0 -> trimRaw(r);
                case 1 -> { dropUnreferencedExtras(r); capDepth(r, 32); }
                case 2 -> capDepth(r, 16);
                case 3 -> { boolean[] ref = referenced(r); retainTracks(r, i -> ref[i] || inFocus(r, i)); r.getMeta().setTracksTrimmed(true); }
                case 4 -> { boolean[] ref = referenced(r); retainTracks(r, i -> ref[i] || (inFocus(r, i) && !r.getTracks().get(i).isIdle())); }
                case 5 -> halveLists(r);
                case 6 -> { boolean[] ref = referenced(r); retainTracks(r, i -> ref[i]); }
                default -> { capDepth(r, 8); r.getStackGroups().clear(); r.getTopFrames().clear(); }
            }
            s = toJson(r);
        }
        if (r.getFindings().stream().noneMatch(f -> "RESULT_TRIMMED".equals(f.getCode()))) {
            ThreadDumpResult.Finding f = new ThreadDumpResult.Finding();
            f.setCode("RESULT_TRIMMED");
            f.setSeverity("Info");
            f.setTitle("저장 크기 상한으로 스레드 목록·스택을 줄였습니다");
            f.setDetail("소견과 요약 수치는 전량 기준입니다. 스레드 표와 스택 깊이만 간략화됐습니다.");
            f.setAdvice("");
            r.getFindings().add(f);
            s = toJson(r);
        }
        return s;
    }

    public static ThreadDumpResult fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, ThreadDumpResult.class);
        } catch (RuntimeException e) { // JacksonException 은 unchecked
            return null;
        }
    }

    private static int size(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    // ── 재색인 ──────────────────────────────────────────────────

    /**
     * {@code keep} 이 참인 스레드만 남기고, 스레드 인덱스를 쓰는 모든 블록(소견·락·교착·스택 그룹·정체·CPU)을 새 번호로 고친다.
     * 빠진 스레드를 가리키던 참조는 지운다(소유자·사슬 끝은 -1).
     */
    public static void retainTracks(ThreadDumpResult r, IntPredicate keep) {
        List<ThreadDumpResult.Track> old = r.getTracks();
        int[] map = new int[old.size()];
        List<ThreadDumpResult.Track> kept = new ArrayList<>();
        for (int i = 0; i < old.size(); i++) {
            if (keep.test(i)) { map[i] = kept.size(); kept.add(old.get(i)); } else map[i] = -1;
        }
        if (kept.size() == old.size()) return;
        r.setTracks(kept);
        for (ThreadDumpResult.Finding f : r.getFindings()) f.setTracks(remapList(f.getTracks(), map));
        for (ThreadDumpResult.LockInfo l : r.getLocks()) {
            l.setOwner(remap(l.getOwner(), map));
            l.setRootBlocker(remap(l.getRootBlocker(), map));
            l.setWaiters(remapList(l.getWaiters(), map));
        }
        for (ThreadDumpResult.Deadlock d : r.getDeadlocks()) {
            d.setTracks(remapList(d.getTracks(), map));
            for (ThreadDumpResult.Chain c : d.getChain()) { c.setT(remap(c.getT(), map)); c.setHeldBy(remap(c.getHeldBy(), map)); }
        }
        for (ThreadDumpResult.StackGroup g : r.getStackGroups()) g.setSample(remapList(g.getSample(), map));
        r.getStuck().removeIf(s -> remap(s.getT(), map) < 0);
        for (ThreadDumpResult.Stuck s : r.getStuck()) s.setT(map[s.getT()]);
        r.getCpuTop().removeIf(c -> remap(c.getT(), map) < 0);
        for (ThreadDumpResult.CpuTop c : r.getCpuTop()) c.setT(map[c.getT()]);
        compactDictionaries(r);
    }

    /** 아무도 참조하지 않는 스택·프레임·이름을 빼고 재색인한다. */
    public static void compactDictionaries(ThreadDumpResult r) {
        int[] stackMap = new int[r.getStacks().size()];
        Arrays.fill(stackMap, -1);
        List<ThreadDumpResult.Stack> stacks = new ArrayList<>();
        for (ThreadDumpResult.Track t : r.getTracks()) {
            List<Integer> k = t.getK();
            for (int i = 0; i < k.size(); i++) k.set(i, useStack(k.get(i), stackMap, stacks, r));
        }
        for (ThreadDumpResult.StackGroup g : r.getStackGroups()) g.setStackId(useStack(g.getStackId(), stackMap, stacks, r));
        for (ThreadDumpResult.Stuck s : r.getStuck()) s.setStackId(useStack(s.getStackId(), stackMap, stacks, r));
        r.setStacks(stacks);

        int[] frameMap = new int[r.getFrames().size()];
        Arrays.fill(frameMap, -1);
        List<String> frames = new ArrayList<>();
        for (ThreadDumpResult.Stack st : stacks) {
            List<Integer> f = st.getF();
            for (int i = 0; i < f.size(); i++) {
                int old = f.get(i);
                if (old < 0 || old >= frameMap.length) { f.set(i, -1); continue; }
                if (frameMap[old] < 0) { frameMap[old] = frames.size(); frames.add(r.getFrames().get(old)); }
                f.set(i, frameMap[old]);
            }
        }
        r.setFrames(frames);

        int[] nameMap = new int[r.getNames().size()];
        Arrays.fill(nameMap, -1);
        List<String> names = new ArrayList<>();
        for (ThreadDumpResult.Track t : r.getTracks()) {
            int old = t.getN();
            if (old < 0 || old >= nameMap.length) continue;
            if (nameMap[old] < 0) { nameMap[old] = names.size(); names.add(r.getNames().get(old)); }
            t.setN(nameMap[old]);
        }
        r.setNames(names);
    }

    private static int useStack(Integer id, int[] map, List<ThreadDumpResult.Stack> out, ThreadDumpResult r) {
        if (id == null || id < 0 || id >= map.length) return -1;
        if (map[id] < 0) { map[id] = out.size(); out.add(r.getStacks().get(id)); }
        return map[id];
    }

    private static int remap(int idx, int[] map) { return idx < 0 || idx >= map.length ? -1 : map[idx]; }

    private static List<Integer> remapList(List<Integer> in, int[] map) {
        List<Integer> out = new ArrayList<>(in.size());
        for (Integer i : in) { int m = i == null ? -1 : remap(i, map); if (m >= 0) out.add(m); }
        return out;
    }

    // ── 줄이기 단계 ─────────────────────────────────────────────

    /** 소견·락·교착·정체·CPU·스택 그룹 표본이 가리키는 스레드. 이것들은 끝까지 남긴다. */
    static boolean[] referenced(ThreadDumpResult r) {
        boolean[] ref = new boolean[r.getTracks().size()];
        for (ThreadDumpResult.Finding f : r.getFindings()) mark(ref, f.getTracks());
        for (ThreadDumpResult.LockInfo l : r.getLocks()) { mark(ref, l.getOwner()); mark(ref, l.getRootBlocker()); mark(ref, l.getWaiters()); }
        for (ThreadDumpResult.Deadlock d : r.getDeadlocks()) {
            mark(ref, d.getTracks());
            for (ThreadDumpResult.Chain c : d.getChain()) { mark(ref, c.getT()); mark(ref, c.getHeldBy()); }
        }
        for (ThreadDumpResult.StackGroup g : r.getStackGroups()) mark(ref, g.getSample());
        for (ThreadDumpResult.Stuck s : r.getStuck()) mark(ref, s.getT());
        for (ThreadDumpResult.CpuTop c : r.getCpuTop()) mark(ref, c.getT());
        return ref;
    }

    private static void mark(boolean[] ref, List<Integer> ids) { for (Integer i : ids) if (i != null) mark(ref, i); }
    private static void mark(boolean[] ref, int i) { if (i >= 0 && i < ref.length) ref[i] = true; }

    private static boolean inFocus(ThreadDumpResult r, int trackIdx) {
        ThreadDumpResult.Track t = r.getTracks().get(trackIdx);
        int off = r.getMeta().getFocusSnapshot() - t.getFirst();
        return off >= 0 && off < t.getS().length() && t.getS().charAt(off) != ThreadDumpResult.ST_ABSENT;
    }

    private static void trimRaw(ThreadDumpResult r) {
        ThreadDumpResult.RawSample raw = r.getRawSample();
        if (raw.getHead().size() > 20) raw.setHead(new ArrayList<>(raw.getHead().subList(0, 20)));
        if (raw.getFirstDumpHead().size() > 20) raw.setFirstDumpHead(new ArrayList<>(raw.getFirstDumpHead().subList(0, 20)));
    }

    private static void dropUnreferencedExtras(ThreadDumpResult r) {
        boolean[] ref = referenced(r);
        for (int i = 0; i < ref.length; i++) {
            if (ref[i]) continue;
            r.getTracks().get(i).setCpu(null);
            r.getTracks().get(i).setOps(null);
        }
    }

    /** 스택 저장 깊이를 줄인다. 잘라 낸 프레임 수는 {@code cut} 에 더해 화면이 "… N개 프레임 생략" 을 보여 줄 수 있게 한다. */
    static void capDepth(ThreadDumpResult r, int depth) {
        for (ThreadDumpResult.Stack st : r.getStacks()) {
            List<Integer> f = st.getF();
            if (f.size() <= depth) continue;
            st.setCut(st.getCut() + f.size() - depth);
            st.setF(new ArrayList<>(f.subList(0, depth)));
        }
        for (ThreadDumpResult.Track t : r.getTracks()) {
            if (t.getOps() != null) t.getOps().removeIf(o -> o.getAt() > depth);
        }
        compactDictionaries(r);
    }

    private static void halveLists(ThreadDumpResult r) {
        for (ThreadDumpResult.LockInfo l : r.getLocks()) {
            if (l.getWaiters().size() > 10) l.setWaiters(new ArrayList<>(l.getWaiters().subList(0, l.getWaiters().size() / 2)));
        }
        if (r.getStackGroups().size() > 10) r.setStackGroups(new ArrayList<>(r.getStackGroups().subList(0, r.getStackGroups().size() / 2)));
        if (r.getStuck().size() > 50) r.setStuck(new ArrayList<>(r.getStuck().subList(0, r.getStuck().size() / 2)));
        compactDictionaries(r);
    }
}

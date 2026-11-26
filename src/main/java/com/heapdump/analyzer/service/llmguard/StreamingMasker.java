package com.heapdump.analyzer.service.llmguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 스트리밍 출력 가림 — 버퍼 끝 {@value #HOLD}자를 붙잡아 두고 그 앞부분만 가려서 내보낸다 (LLM Guard, 2026-11-26).
 *
 * <p><b>불변식:</b> {@link #push} 와 {@link #flush} 가 돌려준 조각을 모두 이어 붙인 결과는 전문을 한 번에 가린 결과와 같다
 * (일치 길이가 {@value #HOLD}자 이하인 규칙에 한해 — 그보다 긴 사용자 정규식은 앞부분이 먼저 나갈 수 있다).
 * 이를 위해 자르는 지점을 <b>어떤 규칙의 매처 일치 구간 안에도</b> 두지 않는다(검증 탈락 일치 포함) —
 * 다음 조각의 매처가 같은 지점부터 같은 일치 순서를 다시 만들기 때문이다. {@code \b}·뒤보기 판정을 위해
 * 직전에 내보낸 원문 꼬리 {@value #CTX}자를 투명 경계로 함께 본다.
 *
 * <p><b>PEM 블록 모드:</b> 개인키 블록은 약 1.6KB 라 홀드백보다 길다. 시작 표지를 만나면 끝 표지(또는 flush)까지
 * 그 뒤를 하나도 내보내지 않고 블록 전체를 가린다.
 *
 * <p>내부 예외는 잡아서 해당 조각을 내보내지 않는다(fail-closed) — 호출자의 {@code catch(Exception)} 으로 새어
 * INTERNAL_ERROR 로 스트림이 끊기지 않게 한다.
 *
 * <p>⚠ 한 스트림 전용(상태 보유), 스레드 안전하지 않다.
 */
public final class StreamingMasker {

    private static final Logger logger = LoggerFactory.getLogger(StreamingMasker.class);

    public static final int HOLD = 128;
    static final int CTX = 16;

    private final List<CompiledRule> rules;
    private final boolean enabled;
    private final StringBuilder buf = new StringBuilder();
    private String tail = "";
    private final StringBuilder maskedAll = new StringBuilder();
    private final MaskResult result = new MaskResult();
    private final Set<CompiledRule> disabled = new HashSet<>();
    private final Map<CompiledRule, int[]> firstPos = new LinkedHashMap<>();
    private boolean failed;

    StreamingMasker(List<CompiledRule> rules, boolean enabled) {
        this.rules = rules;
        this.enabled = enabled;
    }

    /** 조각을 받아 지금 내보낼 수 있는 가린 텍스트를 돌려준다(빈 문자열 가능). */
    public String push(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        if (!enabled) {
            maskedAll.append(chunk);
            return chunk;
        }
        buf.append(chunk);
        return drain(false);
    }

    /** 스트림 끝 — 붙잡아 둔 나머지를 가려서 돌려준다. 호출자는 이것을 <b>onDone 보다 먼저 onChunk 로</b> 보내야 한다. */
    public String flush() {
        if (!enabled) return "";
        String out = drain(true);
        for (Map.Entry<CompiledRule, int[]> e : firstPos.entrySet()) {
            try {
                result.samples().putIfAbsent(e.getKey(),
                        GuardEngine.excerpt(rules, maskedAll.toString(), e.getValue()[0], e.getValue()[1]));
            } catch (RuntimeException ex) {
                logger.warn("[LlmGuard] 출력 발췌 생성 실패 rule={} — 발췌 없이 기록: {}", e.getKey().code(), ex.toString());
            }
        }
        return out;
    }

    /** 지금까지 내보낸 가린 전문. */
    public String maskedText() { return maskedAll.toString(); }

    public MaskResult result() { return result; }

    /** 내부 오류로 조각을 버린 적이 있는가. */
    public boolean failed() { return failed; }

    private String drain(boolean atEnd) {
        try {
            String text = tail + buf;
            int c = tail.length();
            int len = text.length();
            if (len <= c) return "";
            GuardEngine.Scan s = GuardEngine.scan(rules, GuardEngine.Mode.OUTPUT, text, c, atEnd, disabled);
            for (CompiledRule r : s.timedOut) {
                if (disabled.add(r)) {
                    logger.warn("[LlmGuard] action=rule-timeout id={} direction=OUTPUT — 이 스트림에서는 건너뜀", r.id);
                }
                result.addTimedOut(r);
            }
            int cut;
            if (atEnd) {
                cut = len;
            } else {
                cut = len - HOLD;
                if (s.incompleteAt >= 0) cut = Math.min(cut, s.incompleteAt);
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (Hit h : s.raw) {
                        if (h.start < cut && cut < h.end) {
                            cut = h.start;
                            changed = true;
                        }
                    }
                }
            }
            if (cut <= c) return "";
            List<Hit> kept = GuardEngine.resolve(s.raw, text, cut);
            GuardEngine.Masked m = GuardEngine.apply(text, c, cut, kept);
            int base = maskedAll.length();
            for (Hit h : kept) {
                result.add(h.rule, 1, null);
                firstPos.putIfAbsent(h.rule, new int[]{base + m.map(h.start, false), base + m.map(h.end, true)});
            }
            maskedAll.append(m.text);
            String emittedRaw = text.substring(0, cut);
            tail = emittedRaw.length() > CTX ? emittedRaw.substring(emittedRaw.length() - CTX) : emittedRaw;
            buf.setLength(0);
            buf.append(text, cut, len);
            return m.text;
        } catch (RuntimeException e) {
            logger.warn("[LlmGuard] action=stream-mask-error — 조각을 내보내지 않음: {}", e.toString());
            buf.setLength(0);
            failed = true;
            return "";
        }
    }
}

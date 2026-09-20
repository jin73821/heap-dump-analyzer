package com.heapdump.analyzer.parser.gclog;

import com.heapdump.analyzer.model.GcLogResult;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * GC 로그 분석 오케스트레이터 — gz 판별 → 줄 읽기 → 형식 판별 → 파서 → {@link GcLogAnalyzer} → {@link GcLogResult}.
 *
 * <p>상한은 {@link ParseLimits}: gz 는 <b>해제 후</b> 바이트로 재고(압축 폭탄), 줄은 {@code maxLineChars} 에서 절단,
 * 이벤트는 {@code maxEvents} 에서 멈춘다. 진행률은 원본(압축) 바이트 기준이라 파일 크기와 대응한다.
 * 원문 샘플(앞 100·뒤 100·최장 pause ±20줄)은 여기서 모은다 — 파서는 줄을 보관하지 않는다.
 */
public final class GcLogEngine {

    public static final int HEAD_LINES = 100;
    public static final int TAIL_LINES = 100;
    public static final int AROUND_LINES = 20;
    private static final int RING = 400;
    private static final int PROGRESS_EVERY = 2000;
    /** 이어 붙이기에서 조각마다 따로 모으는 원문 샘플 줄 수(앞·뒤 각각). */
    private static final int SEG_SAMPLE_LINES = 30;

    /** 진행 콜백 — 읽은 원본 바이트·줄·이벤트. */
    public interface Progress {
        void report(long bytesRead, long lines, int events);
    }

    /** 형식 판별 불가·상한 초과 등 분석 자체의 실패. 메시지는 사용자에게 그대로 보여도 되는 한국어. */
    public static final class GcLogException extends RuntimeException {
        private final String code;
        public GcLogException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    private GcLogEngine() {}

    /**
     * @param in                  원본 스트림(gz 여부는 매직 바이트로 판별)
     * @param limits              상한
     * @param fallbackEndEpochSec 절대 시각이 없을 때 로그 끝으로 쓸 파일 mtime(초), 없으면 null
     * @param progress            진행 콜백(null 가능)
     */
    public static GcLogResult analyze(InputStream in, ParseLimits limits, Long fallbackEndEpochSec, Progress progress) throws IOException {
        return analyze(List.of(GcLogSource.ofOpenStream(in)), limits, fallbackEndEpochSec, progress);
    }

    /**
     * 회전 로그 여러 개를 <b>받은 순서대로</b> 이어 붙여 한 번에 분석한다(시간순 정렬은 호출자 몫 — 함정 60).
     *
     * <p>조각마다 gz 를 따로 판별하고 <b>파서를 새로 만든다</b> — 파일 끝에서 잘린 이벤트에 다음 파일의 헤더가
     * 이어붙으면 두 이벤트가 하나로 뭉치고, 통합 로깅은 재기동 시 {@code GC(n)} id 가 0 부터 다시 시작해 이전
     * 실행의 열린 id 와 충돌한다(함정 62). 다만 이벤트 예산과 {@code seq} 는 <b>전역</b>으로 이어 준다.
     *
     * <p>줄 번호도 전역이다. 조각 안 줄 번호는 {@code line - Segment.firstLine + 1} 로 구한다.
     *
     * @param sources 조각 목록(1개면 결과는 단일 입력과 완전히 같다 — {@code segments} 도 null)
     */
    public static GcLogResult analyze(List<GcLogSource> sources, ParseLimits limits, Long fallbackEndEpochSec, Progress progress)
            throws IOException {
        if (sources == null || sources.isEmpty()) throw new GcLogException("NOT_GC_LOG", "분석할 GC 로그가 없습니다.");
        boolean multi = sources.size() > 1;

        GcLogAnalyzer analyzer = new GcLogAnalyzer();
        List<String> head = new ArrayList<>(HEAD_LINES);
        ArrayDeque<String> tail = new ArrayDeque<>(TAIL_LINES);
        ArrayDeque<String[]> ring = new ArrayDeque<>(RING);   // {lineNo, text}
        List<String> around = new ArrayList<>();
        int aroundFirst = -1, aroundRemain = 0, lastMaxLine = -1;
        int truncatedLines = 0;
        long lines = 0;
        long rawTotal = 0;      // 원본(압축) 바이트 누적 — 진행률·meta.bytes
        long countedTotal = 0;  // 해제 후 누적 — maxBytes 상한은 조각 합계에 걸린다
        int seqBase = 0;
        int dropped = 0;
        boolean limitHit = false;

        // 조각이 2개 이상이면 형식을 먼저 훑는다 — 헤더 없는 회전본은 단독으로 UNKNOWN 이 나오는데
        // 그것 때문에 전체를 거부하면 안 된다(그룹 형식을 물려받는다). 단일 입력은 다시 열 수 없어 건너뛴다.
        GcLogFormat groupFormat = null;
        boolean[] inferred = new boolean[sources.size()];
        if (multi) {
            GcLogFormat[] fmts = new GcLogFormat[sources.size()];
            for (int i = 0; i < sources.size(); i++) fmts[i] = sniffMember(sources.get(i), limits);
            for (GcLogFormat f : fmts) if (f != GcLogFormat.UNKNOWN) { groupFormat = f; break; }
            if (groupFormat == null) throw notGcLog();
            for (int i = 0; i < sources.size(); i++) {
                if (fmts[i] == GcLogFormat.UNKNOWN) { inferred[i] = true; continue; }
                if (fmts[i] != groupFormat) {
                    throw new GcLogException("FORMAT_MISMATCH", "형식이 다른 로그를 함께 분석할 수 없습니다 — "
                            + name(sources.get(i)) + " 은(는) " + fmts[i] + ", 나머지는 " + groupFormat + " 입니다.");
                }
            }
        }

        for (int si = 0; si < sources.size(); si++) {
            GcLogSource src = sources.get(si);
            int segFirstLine = (int) Math.min(Integer.MAX_VALUE, lines + 1);
            List<String> segHead = multi ? new ArrayList<>(SEG_SAMPLE_LINES) : null;
            ArrayDeque<String> segTail = multi ? new ArrayDeque<>(SEG_SAMPLE_LINES) : null;
            long segLines = 0;
            GcLogParser parser;
            CountingInputStream raw;

            try (InputStream memberIn = src.open().open()) {
                raw = new CountingInputStream(new BufferedInputStream(memberIn, 1 << 16));
                PushbackInputStream pb = new PushbackInputStream(raw, 2);
                int b1 = pb.read(), b2 = pb.read();
                if (b2 >= 0) pb.unread(b2);
                if (b1 >= 0) pb.unread(b1);
                boolean gz = b1 == 0x1f && b2 == 0x8b;
                InputStream body = gz ? new GZIPInputStream(pb, 1 << 16) : pb;
                CountingInputStream counted = new CountingInputStream(body);
                counted.limit = Math.max(0, limits.maxBytes() - countedTotal);

                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
                BufferedReader reader = new BufferedReader(new InputStreamReader(counted, decoder), 1 << 16);

                // 1) 형식 판별 — 단일 입력만 여기서 한다(첫 200줄을 모아 sniff 한 뒤 같은 줄을 파서에 다시 먹인다)
                List<String> sniff = null;
                if (groupFormat == null) {
                    sniff = new ArrayList<>(GcLogFormatDetector.SNIFF_LINES);
                    String l;
                    try {
                        while (sniff.size() < GcLogFormatDetector.SNIFF_LINES && (l = reader.readLine()) != null) sniff.add(l);
                    } catch (LimitExceeded e) { throw tooLarge(limits, multi ? name(src) : null); }
                    groupFormat = GcLogFormatDetector.sniff(sniff);
                    if (groupFormat == GcLogFormat.UNKNOWN) throw notGcLog();
                }
                parser = groupFormat == GcLogFormat.UNIFIED
                        ? new UnifiedGcLogParser(analyzer, limits.maxEvents(), seqBase)
                        : new Jdk8GcLogParser(analyzer, limits.maxEvents(), seqBase);
                if (multi) analyzer.beginSegment(si, name(src), groupFormat, inferred[si], segFirstLine);

                // 2) 줄 공급
                int i = 0;
                String next;
                while (true) {
                    if (sniff != null && i < sniff.size()) next = sniff.get(i++);
                    else {
                        try { next = reader.readLine(); }
                        catch (LimitExceeded e) { throw tooLarge(limits, multi ? name(src) : null); }
                        if (next == null) break;
                    }
                    lines++;
                    segLines++;
                    int lineNo = (int) Math.min(Integer.MAX_VALUE, lines);
                    if (next.length() > limits.maxLineChars()) { next = next.substring(0, limits.maxLineChars()); truncatedLines++; }

                    if (head.size() < HEAD_LINES) head.add(next);
                    if (tail.size() >= TAIL_LINES) tail.pollFirst();
                    tail.addLast(next);
                    if (segHead != null && segHead.size() < SEG_SAMPLE_LINES) segHead.add(next);
                    if (segTail != null) { if (segTail.size() >= SEG_SAMPLE_LINES) segTail.pollFirst(); segTail.addLast(next); }
                    if (ring.size() >= RING) ring.pollFirst();
                    ring.addLast(new String[]{Integer.toString(lineNo), next});
                    if (aroundRemain > 0) { around.add(next); aroundRemain--; }

                    if (!parser.limitReached()) parser.feedLine(lineNo, next);

                    int ml = analyzer.maxPauseLine();
                    if (ml != lastMaxLine && ml > 0) {
                        lastMaxLine = ml;
                        around.clear();
                        aroundFirst = -1;
                        for (String[] r : ring) {
                            int ln = Integer.parseInt(r[0]);
                            if (ln >= ml - AROUND_LINES && ln <= ml + AROUND_LINES) {
                                if (aroundFirst < 0) aroundFirst = ln;
                                around.add(r[1]);
                            }
                        }
                        int lastIncluded = lineNo;
                        aroundRemain = Math.max(0, ml + AROUND_LINES - lastIncluded);
                    }
                    if (progress != null && lines % PROGRESS_EVERY == 0) progress.report(rawTotal + raw.count, lines, analyzerEvents(analyzer));
                }
                countedTotal += counted.count;
            }

            parser.finish();
            dropped += parser.droppedIncomplete();
            seqBase = parser.seqEnd();
            rawTotal += raw.count;
            if (multi) analyzer.endSegment((int) Math.min(Integer.MAX_VALUE, lines), raw.count, segLines,
                    segHead, segTail == null ? null : new ArrayList<>(segTail));
            if (parser.limitReached()) {
                limitHit = true;
                if (multi) analyzer.setLimitStop(name(src), sources.size() - si - 1);
                break;   // 예산이 끝났다 — 남은 조각을 읽어 봐야 이벤트가 들어가지 않는다
            }
        }

        analyzer.setDroppedIncomplete(dropped);
        GcLogResult result = analyzer.finish(groupFormat, fallbackEndEpochSec, limitHit, dropped);
        result.getMeta().setLines(lines);
        result.getMeta().setBytes(rawTotal);
        result.getMeta().setTruncatedLines(truncatedLines);
        if (truncatedLines > 0 || limitHit) result.getMeta().setTruncated(true);
        result.getRawSample().setHead(head);
        result.getRawSample().setTail(new ArrayList<>(tail));
        result.getRawSample().setAroundMaxPause(around);
        result.getRawSample().setAroundMaxPauseFirstLine(aroundFirst < 0 ? null : aroundFirst);
        if (progress != null) progress.report(rawTotal, lines, result.getKpi().getEventCount());
        return result;
    }

    /** 조각 하나의 형식만 훑는다(앞 200줄). 스트림을 열었다 닫으므로 다시 열 수 있는 조각에만 쓴다. */
    private static GcLogFormat sniffMember(GcLogSource src, ParseLimits limits) throws IOException {
        try (InputStream in = src.open().open()) {
            PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(in, 1 << 16), 2);
            int b1 = pb.read(), b2 = pb.read();
            if (b2 >= 0) pb.unread(b2);
            if (b1 >= 0) pb.unread(b1);
            InputStream body = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(pb, 1 << 16) : pb;
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, decoder), 1 << 16);
            List<String> sniff = new ArrayList<>(GcLogFormatDetector.SNIFF_LINES);
            String l;
            while (sniff.size() < GcLogFormatDetector.SNIFF_LINES && (l = reader.readLine()) != null) {
                sniff.add(l.length() > limits.maxLineChars() ? l.substring(0, limits.maxLineChars()) : l);
            }
            return GcLogFormatDetector.sniff(sniff);
        }
    }

    private static String name(GcLogSource src) { return src.name() == null ? "로그" : src.name(); }

    private static GcLogException notGcLog() {
        return new GcLogException("NOT_GC_LOG", "GC 로그 형식을 인식하지 못했습니다. JDK 9+ 통합 로깅(-Xlog:gc*) 또는 JDK 8 이하 -XX:+PrintGCDetails 출력만 지원합니다.");
    }

    private static int analyzerEvents(GcLogAnalyzer a) { return a.eventCountSoFar(); }

    private static GcLogException tooLarge(ParseLimits limits, String member) {
        return new GcLogException("TOO_LARGE", "GC 로그가 상한(" + (limits.maxBytes() >> 20) + " MB, 압축 해제 기준)을 넘습니다."
                + (member == null ? "" : " (" + member + " 를 읽던 중 — 이어 붙이는 파일 수를 줄이세요)"));
    }

    // ── 스트림 헬퍼 ─────────────────────────────────────────────

    static final class LimitExceeded extends IOException {
        LimitExceeded() { super("limit"); }
    }

    static final class CountingInputStream extends InputStream {
        private final InputStream in;
        long count;
        long limit = Long.MAX_VALUE;

        CountingInputStream(InputStream in) { this.in = in; }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && ++count > limit) throw new LimitExceeded();
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) { count += n; if (count > limit) throw new LimitExceeded(); }
            return n;
        }

        @Override public void close() throws IOException { in.close(); }
    }
}

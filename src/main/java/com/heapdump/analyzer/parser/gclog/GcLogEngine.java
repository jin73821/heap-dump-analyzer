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
        CountingInputStream raw = new CountingInputStream(new BufferedInputStream(in, 1 << 16));
        PushbackInputStream pb = new PushbackInputStream(raw, 2);
        int b1 = pb.read(), b2 = pb.read();
        if (b2 >= 0) pb.unread(b2);
        if (b1 >= 0) pb.unread(b1);
        boolean gz = b1 == 0x1f && b2 == 0x8b;
        InputStream body = gz ? new GZIPInputStream(pb, 1 << 16) : pb;
        CountingInputStream counted = new CountingInputStream(body);
        counted.limit = limits.maxBytes();

        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        BufferedReader reader = new BufferedReader(new InputStreamReader(counted, decoder), 1 << 16);

        GcLogAnalyzer analyzer = new GcLogAnalyzer();
        List<String> head = new ArrayList<>(HEAD_LINES);
        ArrayDeque<String> tail = new ArrayDeque<>(TAIL_LINES);
        ArrayDeque<String[]> ring = new ArrayDeque<>(RING);   // {lineNo, text}
        List<String> around = new ArrayList<>();
        int aroundFirst = -1, aroundRemain = 0, lastMaxLine = -1;
        int truncatedLines = 0;

        // 1) 형식 판별 — 첫 200줄을 모아 sniff 한 뒤 같은 줄을 파서에 다시 먹인다
        List<String> sniff = new ArrayList<>(GcLogFormatDetector.SNIFF_LINES);
        String line;
        try {
            while (sniff.size() < GcLogFormatDetector.SNIFF_LINES && (line = reader.readLine()) != null) sniff.add(line);
        } catch (LimitExceeded e) { throw tooLarge(limits); }
        GcLogFormat format = GcLogFormatDetector.sniff(sniff);
        if (format == GcLogFormat.UNKNOWN) {
            throw new GcLogException("NOT_GC_LOG", "GC 로그 형식을 인식하지 못했습니다. JDK 9+ 통합 로깅(-Xlog:gc*) 또는 JDK 8 이하 -XX:+PrintGCDetails 출력만 지원합니다.");
        }
        GcLogParser parser = format == GcLogFormat.UNIFIED
                ? new UnifiedGcLogParser(analyzer, limits.maxEvents())
                : new Jdk8GcLogParser(analyzer, limits.maxEvents());

        // 2) 줄 공급
        long lines = 0;
        int i = 0;
        String next;
        while (true) {
            if (i < sniff.size()) next = sniff.get(i++);
            else {
                try { next = reader.readLine(); }
                catch (LimitExceeded e) { throw tooLarge(limits); }
                if (next == null) break;
            }
            lines++;
            int lineNo = (int) Math.min(Integer.MAX_VALUE, lines);
            if (next.length() > limits.maxLineChars()) { next = next.substring(0, limits.maxLineChars()); truncatedLines++; }

            if (head.size() < HEAD_LINES) head.add(next);
            if (tail.size() >= TAIL_LINES) tail.pollFirst();
            tail.addLast(next);
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
            if (progress != null && lines % PROGRESS_EVERY == 0) progress.report(raw.count, lines, analyzerEvents(analyzer));
        }
        parser.finish();
        analyzer.setDroppedIncomplete(parser.droppedIncomplete());

        GcLogResult result = analyzer.finish(format, fallbackEndEpochSec, parser.limitReached(), parser.droppedIncomplete());
        result.getMeta().setLines(lines);
        result.getMeta().setBytes(raw.count);
        result.getMeta().setTruncatedLines(truncatedLines);
        if (truncatedLines > 0 || parser.limitReached()) result.getMeta().setTruncated(true);
        result.getRawSample().setHead(head);
        result.getRawSample().setTail(new ArrayList<>(tail));
        result.getRawSample().setAroundMaxPause(around);
        result.getRawSample().setAroundMaxPauseFirstLine(aroundFirst < 0 ? null : aroundFirst);
        if (progress != null) progress.report(raw.count, lines, result.getKpi().getEventCount());
        return result;
    }

    private static int analyzerEvents(GcLogAnalyzer a) { return a.eventCountSoFar(); }

    private static GcLogException tooLarge(ParseLimits limits) {
        return new GcLogException("TOO_LARGE", "GC 로그가 상한(" + (limits.maxBytes() >> 20) + " MB, 압축 해제 기준)을 넘습니다.");
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

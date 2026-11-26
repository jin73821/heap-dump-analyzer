package com.heapdump.analyzer.parser.threaddump;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 스레드 덤프 판별 — 파일명 규칙과 내용 마커.
 *
 * <p>⚠ {@link #NAME_RE}·{@link #HEADER_RE}·{@link #THREAD_RE}·{@link #STATE_RE} 는 {@code upload-queue.js} 의
 * {@code _TD_NAME_RE}·{@code _TD_HEADER}·{@code _TD_THREAD}·{@code _TD_STATE} 와 <b>글자 그대로 같아야</b> 한다
 * (브라우저가 고른 분류와 서버 판정이 어긋나면 업로드가 거부된다). {@code ThreadDumpTemplateSmokeTest} 가 대조한다.
 */
public final class ThreadDumpFormatDetector {

    private ThreadDumpFormatDetector() {}

    public static final String NAME_RE =
            "(^|[-_.])(thread[-_.]?dumps?|tdump|jstack|stack[-_.]?dump)([-_.][^\\/]*)?(\\.(txt|log|out|dump|tdump))?(\\.\\d+)?(\\.gz)?$|\\.(tdump|threaddump|jstack)(\\.gz)?$";
    public static final String HEADER_RE = "Full thread dump .*VM \\(.*\\):";
    public static final String THREAD_RE = "^\".+\" .*\\btid=0x[0-9a-f]+ nid=(?:0x[0-9a-f]+|\\d+)\\b";
    public static final String STATE_RE = "^\\s+java\\.lang\\.Thread\\.State: [A-Z_]+";

    private static final Pattern NAME = Pattern.compile(NAME_RE, Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile(HEADER_RE);
    private static final Pattern THREAD = Pattern.compile(THREAD_RE);
    private static final Pattern STATE = Pattern.compile(STATE_RE);

    /** IBM J9/OpenJ9 javacore — 미지원이지만 "스레드 덤프가 아님" 과 구분해 안내한다. */
    private static final Pattern J9 = Pattern.compile("^(0SECTION|1TISIGINFO|3XMTHREADINFO|1XMTHDINFO)\\b");
    /** {@code jcmd Thread.dump_to_file} 텍스트 형식 — {@code #1 "main"} 로 시작하는 줄. */
    private static final Pattern DUMP_TO_FILE = Pattern.compile("^#\\d+ \".*\"");

    public enum Kind { HOTSPOT, J9_JAVACORE, JCMD_DUMP_FILE, UNKNOWN }

    public record Sniff(Kind kind, long linesScanned) {
        public boolean supported() { return kind == Kind.HOTSPOT; }
    }

    public static boolean isThreadDumpFilename(String name) {
        if (name == null) return false;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return NAME.matcher(slash >= 0 ? name.substring(slash + 1) : name).find();
    }

    /** 줄 묶음이 HotSpot 스레드 덤프로 보이는가 — 브라우저의 {@code looksLikeThreadDumpText} 와 같은 규칙. */
    public static boolean looksLikeThreadDump(List<String> lines) {
        Counter c = new Counter();
        for (String l : lines) if (c.feed(l)) return true;
        return false;
    }

    /**
     * 스트림 전체를 훑어 판정한다(gz 는 매직 바이트로 판별, 첫 양성에서 끝). 서버 stdout 처럼 덤프가 한참 뒤에 있는
     * 파일도 받아야 해서 앞부분만 보지 않는다. {@code maxBytes}(해제 후)를 넘으면 그때까지 본 것으로 판정한다.
     */
    public static Sniff scan(InputStream in, long maxBytes, int maxLineChars) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(in, 1 << 16), 2);
        int b1 = pb.read(), b2 = pb.read();
        if (b2 >= 0) pb.unread(b2);
        if (b1 >= 0) pb.unread(b1);
        InputStream body = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(pb, 1 << 16) : pb;
        ThreadDumpEngine.LineReader reader = new ThreadDumpEngine.LineReader(body, maxBytes, maxLineChars);
        Counter c = new Counter();
        int j9 = 0, dtf = 0;
        long lines = 0;
        String l;
        while ((l = reader.readLine()) != null) {
            lines++;
            if (c.feed(l)) return new Sniff(Kind.HOTSPOT, lines);
            if (j9 < 3 && J9.matcher(l).find()) j9++;
            if (dtf < 3 && DUMP_TO_FILE.matcher(l).find()) dtf++;
            if (reader.limitHit()) break;
        }
        if (j9 >= 2) return new Sniff(Kind.J9_JAVACORE, lines);
        if (dtf >= 3) return new Sniff(Kind.JCMD_DUMP_FILE, lines);
        return new Sniff(Kind.UNKNOWN, lines);
    }

    public static String unsupportedMessage(Kind kind) {
        if (kind == Kind.J9_JAVACORE) {
            return "IBM J9/OpenJ9 javacore 형식은 아직 지원하지 않습니다. HotSpot 계열 JVM 의 jstack · kill -3 · jcmd Thread.print 출력만 분석합니다.";
        }
        if (kind == Kind.JCMD_DUMP_FILE) {
            return "jcmd Thread.dump_to_file 형식은 아직 지원하지 않습니다. jcmd <pid> Thread.print 또는 jstack -l 출력으로 다시 받아 주세요.";
        }
        return "스레드 덤프 형식을 인식하지 못했습니다. HotSpot 계열 JVM 의 jstack · kill -3 · jcmd Thread.print 출력만 지원합니다.";
    }

    /** 양성 조건: (헤더 그리고 스레드 줄 ≥1) 또는 (스레드 줄 ≥3 그리고 상태 줄 ≥1 — 머리가 잘린 덤프). */
    private static final class Counter {
        private boolean header;
        private int threads, states;

        boolean feed(String raw) {
            if (raw == null || raw.isEmpty()) return false;
            String line = raw.length() > 1000 ? raw.substring(0, 1000) : raw;
            if (!header && HEADER.matcher(line).find()) header = true;
            else if (THREAD.matcher(line).find()) threads++;
            else if (states == 0 && STATE.matcher(line).find()) states++;
            return (header && threads >= 1) || (threads >= 3 && states >= 1);
        }
    }
}

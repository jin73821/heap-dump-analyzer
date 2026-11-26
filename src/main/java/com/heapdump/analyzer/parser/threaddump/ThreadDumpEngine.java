package com.heapdump.analyzer.parser.threaddump;

import com.heapdump.analyzer.model.ThreadDumpResult;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * 스레드 덤프 분석 오케스트레이터 — gz 판별 → 줄 읽기(정리·절단) → {@link ThreadDumpParser} → {@link ThreadDumpAnalyzer}.
 *
 * <p><b>입력이 지저분해도 읽힌 만큼으로 결과를 만든다.</b> 깨진 바이트는 치환하고, ANSI 이스케이프·제어문자는 지우고,
 * 긴 줄은 자르고, 해제 후 크기 상한을 넘으면 거기서 멈춘다 — 전부 {@link ThreadDumpResult.Diagnostics} 에 세어 둔다.
 * 예외({@link ThreadDumpException})는 결과를 아예 만들 수 없을 때만 던진다(빈 파일·스레드 덤프 아님·미지원 형식·시간 초과·취소).
 *
 * <p>제어문자를 입구에서 지우는 이유: 스레드 이름·프레임에 섞인 0x1B·NUL 이 그대로 가면 PDF(XHTML 파서)·JSON·LLM 프롬프트가
 * 제각각 다른 곳에서 깨진다. 한 곳에서 지우면 뒤쪽은 모두 깨끗한 문자열만 본다.
 */
public final class ThreadDumpEngine {

    public static final int HEAD_LINES = 60;
    public static final int FIRST_DUMP_LINES = 40;
    private static final int PROGRESS_EVERY = 2000;

    private static final Pattern J9 = Pattern.compile("^(0SECTION|1TISIGINFO|3XMTHREADINFO|1XMTHDINFO)\\b");
    private static final Pattern DUMP_TO_FILE = Pattern.compile("^#\\d+ \".*\"");

    /** 진행 콜백 — 읽은 원본 바이트·줄·스냅샷·스레드. 시간 초과·취소는 여기서 {@link ThreadDumpException} 을 던져 알린다. */
    public interface Progress {
        void report(long bytesRead, long lines, int snapshots, int threads);
    }

    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    /** 이어 붙여 분석할 입력 하나 — 이름은 화면·진단에 쓰는 표시명(파일명). */
    public record Source(String name, StreamOpener opener) {
        public static Source of(File f) {
            return new Source(f.getName(), () -> {
                if (!f.isFile()) throw new ThreadDumpException("FILE_MISSING", "구성 파일을 찾을 수 없습니다: " + f.getName());
                return new FileInputStream(f);
            });
        }
    }

    private ThreadDumpEngine() {}

    public static ThreadDumpResult analyze(File file, ThreadDumpLimits limits, Progress progress) throws IOException {
        if (!file.isFile()) throw new ThreadDumpException("FILE_MISSING", "파일을 찾을 수 없습니다: " + file.getName());
        return analyze(() -> new FileInputStream(file), limits, progress);
    }

    public static ThreadDumpResult analyze(StreamOpener opener, ThreadDumpLimits limits, Progress progress) throws IOException {
        return analyze(List.of(new Source(null, opener)), limits, progress, false);
    }

    /**
     * 입력 여러 개를 <b>이어 붙여</b> 한 번에 분석한다(2.7.1). 파일마다 파서를 새로 만들고(줄 번호는 파일 기준),
     * 스택 사전·진단은 함께 쓴다. 모든 스냅샷에 시각이 있으면 시각순으로 다시 세우고, 하나라도 없으면 받은 순서를 지킨다.
     *
     * <p>입력이 하나면 종전과 완전히 같다(오류 코드·진단 포함). 여럿일 때는 스레드 덤프가 없는 구성 파일 하나 때문에 전체를
     * 실패시키지 않는다 — {@code membersWithoutDump} 에 적고 나머지로 분석한다(전부 없으면 실패). 구성 파일이 아예 없으면
     * {@code FILE_MISSING} 이다 — 사용자가 고른 구성이 깨진 것이라 조용히 넘기면 안 된다.
     *
     * @param heapDerived 힙 덤프의 MAT 산출물에서 만든 입력인가 — 시각·스냅샷 수 진단 대신 "힙 덤프에서 추출" 안내를 낸다
     */
    public static ThreadDumpResult analyze(List<Source> sources, ThreadDumpLimits limits, Progress progress, boolean heapDerived) throws IOException {
        return analyze(sources, limits, progress, heapDerived, null);
    }

    /** @param seed 미리 채운 진단(힙 덤프 추출 집계 등). null 이면 새로 만든다. */
    static ThreadDumpResult analyze(List<Source> sources, ThreadDumpLimits limits, Progress progress, boolean heapDerived,
                                    ThreadDumpResult.Diagnostics seed) throws IOException {
        if (sources == null || sources.isEmpty()) throw new ThreadDumpException("EMPTY", "분석할 파일이 없습니다.");
        boolean multi = sources.size() > 1;
        ThreadDumpResult.Diagnostics diag = seed != null ? seed : new ThreadDumpResult.Diagnostics();
        StackTable table = new StackTable();
        List<String> head = new ArrayList<>(HEAD_LINES);
        List<String> firstDump = new ArrayList<>(FIRST_DUMP_LINES);
        List<ThreadDumpParser.ParsedSnapshot> all = new ArrayList<>();
        long lines = 0, rawBytes = 0, charsUsed = 0, outside = 0, truncated = 0, replaced = 0, stripped = 0;
        int seen = 0, j9 = 0, dtf = 0, firstDumpLine = -1, firstDumpSrc = -1, threadsDone = 0;
        boolean byteLimit = false, anyChars = false;

        for (int si = 0; si < sources.size(); si++) {
            Source src = sources.get(si);
            if (byteLimit) {
                // 크기 상한에 이미 닿았다 — 남은 구성 파일은 읽지 않는다
                diag.getMembersWithoutDump().add(src.name() + " — 크기 상한으로 읽지 않음");
                continue;
            }
            ThreadDumpParser parser = new ThreadDumpParser(limits, table, diag);
            if (multi) parser.setFileName(src.name());
            long fileLines = 0, fileRaw = 0;
            int fileJ9 = 0, fileDtf = 0;
            LineReader reader = null;
            try (InputStream in = src.opener().open()) {
                CountingInputStream raw = new CountingInputStream(new BufferedInputStream(in, 1 << 16));
                PushbackInputStream pb = new PushbackInputStream(raw, 2);
                int b1 = pb.read(), b2 = pb.read();
                if (b2 >= 0) pb.unread(b2);
                if (b1 >= 0) pb.unread(b1);
                if (b1 < 0) {
                    if (!multi) throw new ThreadDumpException("EMPTY", "빈 파일입니다.");
                    diag.getMembersWithoutDump().add(src.name() + " — 빈 파일");
                    continue;
                }
                boolean gz = b1 == 0x1f && b2 == 0x8b;
                InputStream body;
                try {
                    body = gz ? new GZIPInputStream(pb, 1 << 16) : pb;
                } catch (ZipException e) {
                    if (!multi) throw new ThreadDumpException("NOT_THREAD_DUMP", "gzip 파일이 손상되어 열 수 없습니다: " + e.getMessage());
                    diag.getMembersWithoutDump().add(src.name() + " — gzip 손상");
                    continue;
                }
                reader = new LineReader(body, Math.max(1, limits.maxBytes() - charsUsed), limits.maxLineChars());
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        lines++;
                        fileLines++;
                        int lineNo = (int) Math.min(Integer.MAX_VALUE, fileLines);
                        if (si == 0 && head.size() < HEAD_LINES) head.add(line);     // 원문 앞부분은 첫 파일에서
                        parser.feedLine(lineNo, line);
                        int fd = parser.firstDumpLine();
                        // 첫 덤프 샘플은 덤프가 처음 나온 파일에서만 모은다
                        if (fd > 0 && (firstDumpSrc < 0 || firstDumpSrc == si)) {
                            if (firstDumpSrc < 0) { firstDumpSrc = si; firstDumpLine = fd; }
                            if (lineNo >= fd && firstDump.size() < FIRST_DUMP_LINES) firstDump.add(line);
                        }
                        if (fd < 0 && !line.isEmpty()) {
                            char c = line.charAt(0);
                            if (fileJ9 < 3 && c >= '0' && c <= '9' && J9.matcher(line).find()) fileJ9++;
                            else if (fileDtf < 3 && c == '#' && DUMP_TO_FILE.matcher(line).find()) fileDtf++;
                        }
                        if (progress != null && lines % PROGRESS_EVERY == 0) {
                            progress.report(rawBytes + raw.count, lines, seen + parser.snapshotsSeen(), threadsDone + parser.threadsSoFar());
                        }
                    }
                } catch (ZipException | java.io.EOFException e) {
                    // 압축 스트림이 도중에 끊겼다 — 여기까지 읽은 것으로 계속한다
                    if (!diag.getLimitHits().contains("GZIP_TRUNCATED")) diag.getLimitHits().add("GZIP_TRUNCATED");
                }
                fileRaw = raw.count;
            }
            parser.finish((int) Math.min(Integer.MAX_VALUE, fileLines));
            rawBytes += fileRaw;
            charsUsed += reader.totalChars();
            if (reader.totalChars() > 0) anyChars = true;
            truncated += reader.truncatedLines();
            replaced += reader.replacedChars();
            stripped += reader.strippedChars();
            if (reader.limitHit()) byteLimit = true;
            outside += parser.outsideNonBlank();
            j9 += fileJ9;
            dtf += fileDtf;
            List<ThreadDumpParser.ParsedSnapshot> got = parser.snapshots();
            if (multi && got.isEmpty()) {
                diag.getMembersWithoutDump().add(src.name() + " — 스레드 덤프 없음");
            }
            for (ThreadDumpParser.ParsedSnapshot ps : got) threadsDone += ps.threads.size();
            all.addAll(got);
            seen += parser.snapshotsSeen();
        }

        diag.setTruncatedLines(truncated);
        diag.setReplacedChars(replaced);
        diag.setStrippedControlChars(stripped);
        if (byteLimit && !diag.getLimitHits().contains("BYTES")) diag.getLimitHits().add("BYTES");

        if (all.isEmpty()) {
            if (lines == 0 || !anyChars) throw new ThreadDumpException("EMPTY", multi ? "구성 파일이 모두 비어 있습니다." : "빈 파일입니다.");
            if (j9 >= 2) throw new ThreadDumpException("UNSUPPORTED_FORMAT",
                    ThreadDumpFormatDetector.unsupportedMessage(ThreadDumpFormatDetector.Kind.J9_JAVACORE));
            if (dtf >= 3) throw new ThreadDumpException("UNSUPPORTED_FORMAT",
                    ThreadDumpFormatDetector.unsupportedMessage(ThreadDumpFormatDetector.Kind.JCMD_DUMP_FILE));
            if (byteLimit) throw new ThreadDumpException("TOO_LARGE", "상한(" + (limits.maxBytes() >> 20)
                    + " MB, 압축 해제 기준)까지 읽었지만 스레드 덤프를 찾지 못했습니다.");
            throw new ThreadDumpException("NOT_THREAD_DUMP", multi
                    ? "구성 파일 어디에서도 스레드 덤프를 찾지 못했습니다. " + ThreadDumpFormatDetector.unsupportedMessage(ThreadDumpFormatDetector.Kind.UNKNOWN)
                    : ThreadDumpFormatDetector.unsupportedMessage(ThreadDumpFormatDetector.Kind.UNKNOWN));
        }

        if (multi) {
            // 모든 스냅샷에 시각이 있으면 시각순으로(파일 순서가 시간순이라는 보장은 없다). 하나라도 없으면 받은 순서 그대로
            boolean allTs = all.stream().allMatch(ps -> ps.ts != null);
            if (allTs) all.sort(java.util.Comparator.comparingLong(ps -> ps.ts));
            if (all.size() > limits.maxSnapshots()) {
                all = new ArrayList<>(all.subList(all.size() - limits.maxSnapshots(), all.size()));
                if (!diag.getLimitHits().contains("SNAPSHOTS")) diag.getLimitHits().add("SNAPSHOTS");
            }
        }

        final long fLines = lines, fRaw = rawBytes;
        final int fSeen = seen, fThreads = threadsDone;
        Runnable checkpoint = progress == null ? null : () -> progress.report(fRaw, fLines, fSeen, fThreads);
        diag.setDumpLines(Math.max(0, lines - diag.getNoiseLines()));
        ThreadDumpResult result = new ThreadDumpAnalyzer(all, seen, table, diag, checkpoint, heapDerived).analyze();

        ThreadDumpResult.Meta meta = result.getMeta();
        meta.setBytes(rawBytes);
        meta.setLines(lines);
        meta.setEmbedded(outside > 2);
        meta.setTruncated(byteLimit || diag.getTruncatedLines() > 0 || !diag.getLimitHits().isEmpty());
        if (multi) {
            List<String> names = new ArrayList<>();
            for (Source s : sources) names.add(s.name());
            meta.setFiles(names);
        }
        if (heapDerived) meta.setSource("heap");
        result.getRawSample().setHead(head);
        result.getRawSample().setFirstDumpHead(firstDump);
        result.getRawSample().setFirstDumpLine(firstDumpLine > 0 && !multi ? firstDumpLine : null);

        if (result.getTracks().size() > ThreadDumpAnalyzer.TRACKS_CAP) {
            // 스레드가 계속 생겼다 사라지는 덤프 — 소견이 가리키는 것과 focus 스냅샷의 것부터 남긴다
            boolean[] ref = ThreadDumpResultCodec.referenced(result);
            int focus = meta.getFocusSnapshot();
            int[] budget = {ThreadDumpAnalyzer.TRACKS_CAP};
            ThreadDumpResultCodec.retainTracks(result, i -> {
                if (ref[i]) { budget[0]--; return true; }
                ThreadDumpResult.Track t = result.getTracks().get(i);
                int off = focus - t.getFirst();
                boolean inFocus = off >= 0 && off < t.getS().length() && t.getS().charAt(off) != ThreadDumpResult.ST_ABSENT;
                if (inFocus && budget[0] > 0) { budget[0]--; return true; }
                return false;
            });
            meta.setTracksTrimmed(true);
        }
        if (progress != null) progress.report(rawBytes, lines, seen, threadsDone);
        return result;
    }

    /** 빠른 스냅샷 훑기 결과 — 이어 붙이기 미리보기의 순서 판단용. */
    public record SnapshotScan(int snapshots, Long firstTs, Long lastTs) {}

    /**
     * 파일을 분석하지 않고 스냅샷 헤더({@code Full thread dump})와 그 앞 시각 줄만 센다(gz 지원, 상한까지).
     * 이어 붙이기 미리보기가 파일 순서를 정할 때 쓴다 — 실패해도 0개로 돌려줄 뿐 던지지 않는다(미리보기를 막지 않게).
     */
    public static SnapshotScan scanSnapshots(File f, long maxBytes, int maxLineChars) {
        int n = 0;
        Long first = null, last = null;
        try (InputStream in = new FileInputStream(f)) {
            PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(in, 1 << 16), 2);
            int b1 = pb.read(), b2 = pb.read();
            if (b2 >= 0) pb.unread(b2);
            if (b1 >= 0) pb.unread(b1);
            InputStream body = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(pb, 1 << 16) : pb;
            LineReader r = new LineReader(body, maxBytes, maxLineChars);
            String line, prev = null;
            while ((line = r.readLine()) != null) {
                int mark = line.indexOf("Full thread dump ");
                if (mark >= 0 && line.indexOf(" VM (", mark) > 0) {
                    n++;
                    Long ts = prev == null ? null : ThreadDumpParser.parseTimestamp(prev.strip());
                    if (ts != null) { if (first == null) first = ts; last = ts; }
                }
                if (!line.isBlank()) prev = line;
            }
        } catch (IOException e) {
            // 미리보기는 순서 판단용일 뿐이다 — 읽지 못한 파일은 '덤프 0개' 로 보이고 실제 분석이 정확한 사유를 남긴다
            return new SnapshotScan(n, first, last);
        }
        return new SnapshotScan(n, first, last);
    }

    // ── 스트림 헬퍼 ─────────────────────────────────────────────

    static final class CountingInputStream extends InputStream {
        private final InputStream in;
        long count;

        CountingInputStream(InputStream in) { this.in = in; }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override public void close() throws IOException { in.close(); }
    }

    /**
     * 상한이 있는 줄 리더. {@code BufferedReader.readLine} 은 줄바꿈 없는 바이너리를 만나면 파일 전체를 한 줄로 쌓아
     * 힙을 터뜨린다 — 여기서는 줄 버퍼가 {@code maxLineChars} 를 넘지 않는다(넘는 부분은 버리고 센다).
     *
     * <p>읽으면서 정리한다: 깨진 UTF-8 은 U+FFFD 로 치환, ANSI 이스케이프(ESC [ … 문자)와 탭 외 제어문자는 제거.
     * 해제 후 {@code maxBytes} 를 넘으면 예외 없이 EOF 처럼 끝내고 {@link #limitHit()} 로 알린다.
     */
    static final class LineReader {
        private final Reader reader;
        private final long maxChars;
        private final int maxLineChars;
        private final char[] buf = new char[1 << 15];
        private int pos, len;
        private boolean eof, limit, lastWasCr;
        private long total, truncated, replaced, stripped;
        private final StringBuilder sb = new StringBuilder(256);
        /** 0 = 평문, 1 = ESC 직후, 2 = ESC [ 뒤(문자 하나가 나올 때까지 버린다). */
        private int esc;

        LineReader(InputStream in, long maxBytes, int maxLineChars) {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            this.reader = new InputStreamReader(in, decoder);
            // 문자 수로 세는 근사치 — ASCII 위주인 덤프에서는 바이트와 같고, 멀티바이트면 더 너그럽다
            this.maxChars = maxBytes;
            this.maxLineChars = Math.max(64, maxLineChars);
        }

        String readLine() throws IOException {
            if (eof) return null;
            sb.setLength(0);
            boolean any = false, over = false;
            while (true) {
                if (pos >= len) {
                    if (total >= maxChars) { limit = true; eof = true; break; }
                    len = reader.read(buf, 0, buf.length);
                    pos = 0;
                    if (len <= 0) { eof = true; break; }
                    total += len;
                }
                char c = buf[pos++];
                if (c == '\n') {
                    if (lastWasCr) { lastWasCr = false; continue; }   // CRLF 의 LF
                    any = true;
                    break;
                }
                lastWasCr = false;
                if (c == '\r') { lastWasCr = true; any = true; break; }
                any = true;
                if (esc == 1) { esc = c == '[' ? 2 : 0; stripped++; continue; }
                if (esc == 2) { stripped++; if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) esc = 0; continue; }
                if (c == 0x1B) { esc = 1; stripped++; continue; }
                if ((c < 0x20 && c != '\t') || c == 0x7F) { stripped++; continue; }
                if (c == '�') replaced++;
                if (sb.length() < maxLineChars) sb.append(c);
                else over = true;
            }
            esc = 0;
            if (over) truncated++;
            if (!any && eof) return null;
            return sb.toString();
        }

        boolean limitHit() { return limit; }
        long truncatedLines() { return truncated; }
        long replacedChars() { return replaced; }
        long strippedChars() { return stripped; }
        long totalChars() { return total; }
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.CoreFileRow;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 코어 파일 화면(/core-dump) 표 조립 — 서버 dumpfiles/ 목록과 분석 이력(DB)을 파일명으로 합쳐 한 표로 만든다.
 * 2026-11-26 화면 개편 전에는 좌측 '서버 코어 파일' 목록과 '분석 이력' 표가 따로 있어 같은 파일이 두 번 보였다.
 *
 * <ul>
 *   <li>디스크에 있는 코어 = 행. 이력이 있으면 상태·시그널·백트레이스 요약을 채우고, 없으면 미분석.</li>
 *   <li>이력만 있고 원본이 없는 코어 = {@code fileMissing} 행(결과는 DB 에 있어 볼 수 있다).</li>
 *   <li>코어에 연결되지 않은 실행 파일 = {@code exec} 행. 연결된 실행 파일은 코어 행의 '실행 파일' 칸으로만 보인다
 *       ({@link CoreDumpAnalyzerService#listExistingDumpFiles()} 가 이미 숨긴다).</li>
 *   <li>'같은 지점' = 백트레이스 요약 첫 줄의 함수 + 소스 위치(file:line)가 같은 코어. 소스 위치가 없는 프레임
 *       (라이브러리 안의 raise·memcpy 등)은 묶지 않는다 — 서로 다른 원인이 한 지점처럼 보이기 때문.</li>
 * </ul>
 * 스프링 빈이 아닌 순수 함수 모음이다(테스트: {@code CoreFileListBuilderTest}).
 */
public final class CoreFileListBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CoreFileListBuilder.class);

    /** {@code crashSummary} 한 줄: {@code #0 0x55d0... in fn at file.c:12} — 주소·위치는 생략될 수 있다. */
    private static final Pattern FRAME =
            Pattern.compile("^(#\\d+)\\s+(?:(0x[0-9a-fA-F]+)\\s+in\\s+)?(.*?)(?:\\s+at\\s+(\\S+:\\d+))?\\s*$");

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Linux x86_64 시그널 번호 — 표시용. */
    private static final Map<String, Integer> SIG_NO = Map.ofEntries(
            Map.entry("SIGHUP", 1), Map.entry("SIGINT", 2), Map.entry("SIGQUIT", 3), Map.entry("SIGILL", 4),
            Map.entry("SIGTRAP", 5), Map.entry("SIGABRT", 6), Map.entry("SIGBUS", 7), Map.entry("SIGFPE", 8),
            Map.entry("SIGKILL", 9), Map.entry("SIGUSR1", 10), Map.entry("SIGSEGV", 11), Map.entry("SIGUSR2", 12),
            Map.entry("SIGPIPE", 13), Map.entry("SIGALRM", 14), Map.entry("SIGTERM", 15), Map.entry("SIGXCPU", 24),
            Map.entry("SIGXFSZ", 25), Map.entry("SIGSYS", 31));

    private static final Map<String, String> SIG_KO = Map.ofEntries(
            Map.entry("SIGSEGV", "잘못된 메모리 접근"),
            Map.entry("SIGBUS", "버스 오류 — 매핑·정렬 문제"),
            Map.entry("SIGABRT", "프로세스가 스스로 중단(abort)"),
            Map.entry("SIGFPE", "산술 오류 — 0으로 나누기 등"),
            Map.entry("SIGILL", "잘못된 CPU 명령"),
            Map.entry("SIGTRAP", "디버그 트랩"),
            Map.entry("SIGQUIT", "종료 요청(코어 생성)"),
            Map.entry("SIGKILL", "강제 종료"),
            Map.entry("SIGTERM", "종료 요청"),
            Map.entry("SIGSYS", "잘못된 시스템 호출"),
            Map.entry("SIGXCPU", "CPU 시간 한도 초과"),
            Map.entry("SIGXFSZ", "파일 크기 한도 초과"));

    /** 같은 지점에서 두 번 이상 종료한 코어 묶음 — 표 위 안내 줄. */
    public static class RepeatGroup {
        private final String key;
        private final String function;
        private final String location;
        private final int count;
        private final String execName;
        private final LocalDateTime latest;

        RepeatGroup(String key, String function, String location, int count, String execName, LocalDateTime latest) {
            this.key = key; this.function = function; this.location = location;
            this.count = count; this.execName = execName; this.latest = latest;
        }
        public String getKey()      { return key; }
        public String getFunction() { return function; }
        public String getLocation() { return location; }
        /** 안내 줄에 쓰는 짧은 위치({@code 파일명:줄}) — 전체 경로는 버튼 툴팁에 둔다. */
        public String getLocationShort() { return CoreFileRow.shortLocation(location); }
        public int getCount()       { return count; }
        /** 묶음의 코어가 모두 같은 실행 파일이면 그 이름, 아니면 null. */
        public String getExecName() { return execName; }
        public LocalDateTime getLatest() { return latest; }
    }

    private CoreFileListBuilder() { }

    /**
     * @param files   {@link CoreDumpAnalyzerService#listExistingDumpFiles()} — 디스크 기준(연결된 실행 파일은 이미 빠짐)
     * @param history {@link CoreDumpAnalyzerService#getHistory()} — fileDeleted=false 인 이력
     * @param sizeOf  dumpfiles/ 안 파일 크기 조회(없으면 null 또는 음수) — 실행 파일 크기 표시용
     */
    public static List<CoreFileRow> build(List<AnalysisHistoryItem> files,
                                          List<CoreDumpAnalysisEntity> history,
                                          Function<String, Long> sizeOf) {
        return build(files, history, sizeOf, LocalDate.now());
    }

    /** {@code today} — '오늘'·'어제' 표기 기준일(테스트용으로 열어 둔다). */
    public static List<CoreFileRow> build(List<AnalysisHistoryItem> files,
                                          List<CoreDumpAnalysisEntity> history,
                                          Function<String, Long> sizeOf,
                                          LocalDate today) {
        Map<String, CoreDumpAnalysisEntity> byName = new LinkedHashMap<>();
        if (history != null) {
            for (CoreDumpAnalysisEntity e : history) {
                if (e != null && e.getFilename() != null) byName.putIfAbsent(e.getFilename(), e);
            }
        }

        List<CoreFileRow> rows = new ArrayList<>();
        if (files != null) {
            for (AnalysisHistoryItem f : files) {
                if (f == null || f.getFilename() == null) continue;
                CoreFileRow r = new CoreFileRow();
                r.setFilename(f.getFilename());
                r.setSizeBytes(f.getSizeBytes());
                r.setFormattedSize(f.getFormattedSize() != null ? f.getFormattedSize() : FormatUtils.formatBytes(f.getSizeBytes()));
                LocalDateTime modified = f.getLastModified() > 0
                        ? LocalDateTime.ofInstant(Instant.ofEpochMilli(f.getLastModified()), ZoneId.systemDefault())
                        : null;
                if ("exec".equals(f.getFileType())) {
                    r.setFileType("exec");
                    r.setStatus("EXEC");
                    r.setUploadedAt(modified);
                    rows.add(r);
                    continue;
                }
                CoreDumpAnalysisEntity e = byName.remove(f.getFilename());
                r.setUploadedAt(modified);
                fill(r, e);
                String exec = f.getPairedExecFilename();
                if (exec == null || exec.isEmpty()) exec = e != null ? e.getExecutableName() : null;
                setExec(r, exec, e, sizeOf);
                rows.add(r);
            }
        }
        // 이력은 있는데 원본이 디스크에 없는 코어
        for (CoreDumpAnalysisEntity e : byName.values()) {
            CoreFileRow r = new CoreFileRow();
            r.setFilename(e.getFilename());
            r.setFileMissing(true);
            if (e.getFileSize() != null) {
                r.setSizeBytes(e.getFileSize());
                r.setFormattedSize(FormatUtils.formatBytes(e.getFileSize()));
            }
            fill(r, e);
            setExec(r, e.getExecutableName(), e, n -> null);
            rows.add(r);
        }

        for (CoreFileRow r : rows) r.setUploadedLabel(relativeLabel(r.getUploadedAt(), today));
        markRepeats(rows);
        rows.sort(Comparator.comparing(CoreFileRow::getUploadedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    /** 같은 지점 묶음 — 큰 묶음부터, 같으면 최근 것부터. */
    public static List<RepeatGroup> repeatGroups(List<CoreFileRow> rows) {
        Map<String, List<CoreFileRow>> byKey = new LinkedHashMap<>();
        for (CoreFileRow r : rows) {
            if (r.isRepeated()) byKey.computeIfAbsent(r.getRepeatKey(), k -> new ArrayList<>()).add(r);
        }
        List<RepeatGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<CoreFileRow>> en : byKey.entrySet()) {
            List<CoreFileRow> g = en.getValue();
            CoreFileRow first = g.get(0);
            String exec = first.getExecName();
            for (CoreFileRow r : g) {
                if (!Objects.equals(exec, r.getExecName())) { exec = null; break; }
            }
            LocalDateTime latest = g.stream().map(CoreFileRow::getUploadedAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            out.add(new RepeatGroup(en.getKey(), first.getCrashFunction(), first.getCrashLocation(), g.size(), exec, latest));
        }
        out.sort(Comparator.comparingInt(RepeatGroup::getCount).reversed()
                .thenComparing(RepeatGroup::getLatest, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** {@code crashSummary} 를 프레임으로. 형식이 다른 줄은 함수 칸에 원문 그대로 둔다. */
    static List<CoreFileRow.Frame> parseFrames(String summary) {
        List<CoreFileRow.Frame> out = new ArrayList<>();
        if (summary == null || summary.isBlank()) return out;
        for (String line : summary.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            Matcher m = FRAME.matcher(s);
            if (m.matches() && !m.group(3).isEmpty()) {
                out.add(new CoreFileRow.Frame(m.group(1), m.group(2), m.group(3), m.group(4)));
            } else {
                out.add(new CoreFileRow.Frame(null, null, s, null));
            }
        }
        return out;
    }

    /** 업로드 시각 — 오늘 14:32 / 어제 22:47 / 11-24 11:20(올해) / 2025-11-24(지난해 이전). */
    static String relativeLabel(LocalDateTime t, LocalDate today) {
        if (t == null) return null;
        LocalDate d = t.toLocalDate();
        if (today != null && d.equals(today)) return "오늘 " + t.format(HM);
        if (today != null && d.equals(today.minusDays(1))) return "어제 " + t.format(HM);
        if (today != null && d.getYear() != today.getYear()) return t.format(YMD);
        return t.format(MD_HM);
    }

    /** 분석 소요 시간 — 2분 41초 / 58초 / 1초 미만. */
    static String formatDuration(Long ms) {
        if (ms == null || ms < 0) return null;
        if (ms < 1000) return "1초 미만";
        long s = Math.round(ms / 1000.0);
        if (s < 60) return s + "초";
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "시간 " + m + "분";
        return sec == 0 ? m + "분" : m + "분 " + sec + "초";
    }

    private static void fill(CoreFileRow r, CoreDumpAnalysisEntity e) {
        if (e == null) {
            r.setStatus("NOT_ANALYZED");
            return;
        }
        r.setStatus(e.getStatus() != null ? e.getStatus() : "NOT_ANALYZED");
        if (e.getCreatedAt() != null) r.setUploadedAt(e.getCreatedAt());
        r.setUploadedBy(e.getUploadedBy());
        r.setAnalyzedAt(e.getAnalyzedAt());
        r.setFormattedAnalysisTime(formatDuration(e.getAnalysisTimeMs()));
        String sig = e.getCrashSignal();
        if (sig != null && !sig.isBlank()) {
            r.setCrashSignal(sig);
            r.setSignalNumber(SIG_NO.get(sig));
            r.setSignalMeaning(SIG_KO.get(sig));
        }
        r.setSignalDescription(e.getSignalDescription());
        r.setErrorMessage(e.getErrorMessage());
        List<CoreFileRow.Frame> frames = parseFrames(e.getCrashSummary());
        r.setFrames(frames);
        if (!frames.isEmpty()) {
            r.setCrashFunction(frames.get(0).getFn());
            r.setCrashLocation(frames.get(0).getLoc());
        }
    }

    private static void setExec(CoreFileRow r, String exec, CoreDumpAnalysisEntity e, Function<String, Long> sizeOf) {
        if (exec == null || exec.isEmpty()) return;
        r.setExecName(exec);
        Long size = null;
        try {
            size = sizeOf != null ? sizeOf.apply(exec) : null;
        } catch (RuntimeException ex) {
            // 크기는 부가 정보 — 조회 실패해도 행은 그린다
            logger.debug("[CoreDump] 실행 파일 크기 조회 실패 ({}): {}", exec, ex.toString());
        }
        if ((size == null || size < 0) && e != null && exec.equals(e.getExecutableName())) size = e.getExecutableSize();
        if (size != null && size >= 0) r.setExecFormattedSize(FormatUtils.formatBytes(size));
    }

    private static void markRepeats(List<CoreFileRow> rows) {
        Map<String, List<CoreFileRow>> byKey = new HashMap<>();
        for (CoreFileRow r : rows) {
            // 크래시 지점은 완료 행에만 보이므로 묶음도 완료 행끼리만(파서 오류로 끝난 ERROR 행에도 요약이 남을 수 있다)
            if (!r.isSuccess() || r.getCrashFunction() == null || r.getCrashLocation() == null) continue;
            String key = r.getCrashFunction() + " @ " + r.getCrashLocation();
            r.setRepeatKey(key);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        for (List<CoreFileRow> g : byKey.values()) {
            if (g.size() < 2) continue;
            g.sort(Comparator.comparing(CoreFileRow::getUploadedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            for (CoreFileRow r : g) {
                r.setRepeatCount(g.size());
                List<String> others = new ArrayList<>();
                for (CoreFileRow o : g) {
                    if (o == r) continue;
                    others.add(o.getUploadedAt() != null
                            ? o.getFilename() + " (" + o.getUploadedAt().format(MD) + ")"
                            : o.getFilename());
                }
                r.setRepeatOthers(others);
            }
        }
    }
}

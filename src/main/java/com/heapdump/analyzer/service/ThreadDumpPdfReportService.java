package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 스레드 덤프 분석 리포트 — PDF 와 HTML 미리보기가 같은 모델({@link #buildPrintModel})·같은 템플릿
 * ({@code thread-dump/analyze-print})을 쓴다. 렌더 코어(Pretendard 임베딩)는 {@link PdfReportService#renderPdf} 를 공유한다.
 *
 * <p>인쇄 템플릿은 스크립트가 없고 삼항·산술을 최소화해야 하므로(함정 11·23) <b>표시 문자열·막대 폭·상한 초과 건수까지 전부 여기서 계산</b>한다.
 * 행은 맵이다 — 템플릿 엔진이 SpEL 이든 OGNL 이든 {@code row.name} 으로 읽힌다.
 * 리포트는 요약 문서라 스레드 전체 표는 넣지 않는다(수천 행 PDF 는 아무도 읽지 않는다 — 화면의 스레드 표를 쓴다).
 */
@Service
public class ThreadDumpPdfReportService {

    static final int FINDINGS_MAX = 30, DEADLOCKS_MAX = 10, LOCKS_MAX = 15, POOLS_MAX = 20, STUCK_MAX = 30,
            GROUPS_MAX = 10, GROUP_FRAMES = 12, SNAPSHOTS_MAX = 60, CPU_MAX = 10, DEADLOCK_FRAMES = 6;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PdfReportService pdfCore;

    public ThreadDumpPdfReportService(PdfReportService pdfCore) {
        this.pdfCore = pdfCore;
    }

    public byte[] renderPdf(String filename, ThreadDumpAnalysisEntity entity, ThreadDumpResult result, Map<String, Object> aiInsight) throws IOException {
        return renderPdf(filename, entity, result, aiInsight, null);
    }

    /** @param heapLink 연결된 힙 덤프 뷰({@code ThreadDumpAnalyzerService.heapLinkView}) — 없으면 null */
    public byte[] renderPdf(String filename, ThreadDumpAnalysisEntity entity, ThreadDumpResult result, Map<String, Object> aiInsight,
                            Map<String, Object> heapLink) throws IOException {
        return pdfCore.renderPdf("thread-dump/analyze-print", buildPrintModel(filename, entity, result, aiInsight, heapLink));
    }

    public Map<String, Object> buildPrintModel(String filename, ThreadDumpAnalysisEntity entity, ThreadDumpResult r, Map<String, Object> aiInsight) {
        return buildPrintModel(filename, entity, r, aiInsight, null);
    }

    public Map<String, Object> buildPrintModel(String filename, ThreadDumpAnalysisEntity entity, ThreadDumpResult r, Map<String, Object> aiInsight,
                                               Map<String, Object> heapLink) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean heap = "heap".equals(r.getMeta().getSource());
        m.put("titleText", heap ? "Thread Analysis Report (Heap Dump)" : "Thread Dump Analysis Report");
        m.put("sourceLabel", heap ? "힙 덤프를 만든 한 시점의 스레드 정보에서 추출 — 스냅샷 비교·CPU·synchronized 락 관계는 없음" : null);
        m.put("memberFiles", r.getMeta().getFiles() == null ? null : String.join(", ", r.getMeta().getFiles()));
        m.put("showFileCol", r.getMeta().getFiles() != null);
        m.put("memRows", heap ? heapMemoryRows(r) : linkMemoryRows(heapLink));
        m.put("memTitle", heap ? "힙 점유가 큰 스레드" : "힙 점유가 큰 스레드 (연결된 힙 덤프 기준)");
        m.put("linkText", linkText(heapLink));
        ThreadDumpResult.Meta meta = r.getMeta();
        ThreadDumpResult.Kpi k = r.getKpi();
        int focus = Math.max(0, Math.min(meta.getFocusSnapshot(), Math.max(0, r.getSnapshots().size() - 1)));

        // ── 머리말 ──
        m.put("filename", filename);
        m.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        m.put("serverName", entity == null ? null : entity.getServerName());
        m.put("analyzedAtText", entity == null || entity.getAnalyzedAt() == null ? null : entity.getAnalyzedAt().format(DT));
        m.put("jvmLine", PdfReportService.clip(stripHeader(meta.getJvmVersionLine()), 90));
        m.put("jdkText", meta.getJdkMajor() == null ? null : "JDK " + meta.getJdkMajor());
        m.put("snapshotText", r.getSnapshots().size() + "개" + (meta.getSnapshotsSeen() > r.getSnapshots().size()
                ? " (파일에는 " + meta.getSnapshotsSeen() + "개 — 마지막 것만 분석)" : ""));
        m.put("spanText", spanText(r));
        m.put("severity", k.getSeverity());
        m.put("severityClass", sevClass(k.getSeverity()));
        m.put("severityLabel", sevLabel(k.getSeverity()));

        // ── KPI ──
        m.put("kThreads", k.getThreadsLast());
        m.put("kThreadsPeak", k.getThreadsPeak());
        m.put("kBlocked", k.getBlockedMax());
        m.put("kDeadlocks", k.getDeadlocks());
        m.put("kStuck", k.getStuck());
        m.put("kLockWaiters", k.getTopLockWaiters());

        // ── 상태 분포 (focus 스냅샷) ──
        List<Map<String, Object>> stateRows = new ArrayList<>();
        if (!r.getSnapshots().isEmpty()) {
            ThreadDumpResult.Snapshot fs = r.getSnapshots().get(focus);
            int total = Math.max(1, fs.getThreads());
            for (Map.Entry<String, Integer> e : fs.getStates().entrySet()) {
                double pct = 100.0 * e.getValue() / total;
                stateRows.add(row("label", stateLabel(e.getKey()), "cls", "st-" + e.getKey().toLowerCase(Locale.ROOT), "count", e.getValue(),
                        "pctText", String.format(Locale.ROOT, "%.1f%%", pct),
                        "barStyle", "width:" + String.format(Locale.ROOT, "%.1f", Math.max(1.0, pct)) + "%;"));
            }
            m.put("focusNo", focus + 1);
            m.put("focusTime", fs.getTsText());
        }
        m.put("stateRows", stateRows);

        // ── 소견 (Info 는 제목만 따로) ──
        List<Map<String, Object>> findings = new ArrayList<>();
        List<String> infoTitles = new ArrayList<>();
        int findingTotal = 0;
        for (ThreadDumpResult.Finding f : r.getFindings()) {
            if ("Info".equals(f.getSeverity())) { infoTitles.add(PdfReportService.clip(f.getTitle(), 80)); continue; }
            findingTotal++;
            if (findings.size() >= FINDINGS_MAX) continue;
            findings.add(row("severity", sevLabel(f.getSeverity()), "sevClass", sevClass(f.getSeverity()),
                    "title", PdfReportService.clip(f.getTitle(), 120), "detail", PdfReportService.clip(f.getDetail(), 600),
                    "advice", blankToNull(PdfReportService.clip(f.getAdvice(), 400))));
        }
        m.put("findings", findings);
        m.put("findingOverflow", Math.max(0, findingTotal - findings.size()));
        m.put("infoTitles", PdfReportService.limit(infoTitles, 12));

        // ── 교착 ──
        List<Map<String, Object>> deadlocks = new ArrayList<>();
        for (ThreadDumpResult.Deadlock d : PdfReportService.limit(r.getDeadlocks(), DEADLOCKS_MAX)) {
            List<Map<String, Object>> chain = new ArrayList<>();
            for (ThreadDumpResult.Chain c : d.getChain()) {
                chain.add(row("name", PdfReportService.clip(c.getName(), 90), "lock", lockText(c.getAddr(), c.getCls()),
                        "heldBy", PdfReportService.clip(c.getHeldByName(), 90), "frames", framesOfTrack(r, c.getT(), focus, DEADLOCK_FRAMES)));
            }
            deadlocks.add(row("no", deadlocks.size() + 1, "sourceText", "jvm".equals(d.getSource()) ? "JVM 이 보고" : "락 대기 그래프에서 확인",
                    "snapNo", d.getSnap() + 1, "chain", chain));
        }
        m.put("deadlocks", deadlocks);
        m.put("deadlockOverflow", Math.max(0, r.getDeadlocks().size() - deadlocks.size()));

        // ── 락 경합 ──
        List<Map<String, Object>> locks = new ArrayList<>();
        for (ThreadDumpResult.LockInfo l : PdfReportService.limit(r.getLocks(), LOCKS_MAX)) {
            locks.add(row("lock", lockText(l.getAddr(), l.getCls()), "kind", "juc".equals(l.getKind()) ? "ReentrantLock" : "모니터",
                    "waiters", l.getWaiterCount(), "owner", l.getOwner() >= 0 ? PdfReportService.clip(nameOf(r, l.getOwner()), 60) : "알 수 없음",
                    "ownerTop", PdfReportService.clip(topFrameOfTrack(r, l.getOwner(), focus), 90)));
        }
        m.put("locks", locks);
        m.put("lockOverflow", Math.max(0, r.getLocks().size() - locks.size()));

        // ── 스레드 풀 (focus) ──
        List<Map<String, Object>> pools = new ArrayList<>();
        List<ThreadDumpResult.Pool> sorted = new ArrayList<>(r.getPools());
        sorted.sort((a, b) -> at(b.getSizes(), focus) - at(a.getSizes(), focus));
        int poolTotal = 0;
        for (ThreadDumpResult.Pool p : sorted) {
            int size = at(p.getSizes(), focus);
            if (size == 0) continue;
            poolTotal++;
            if (pools.size() >= POOLS_MAX) continue;
            pools.add(row("stem", PdfReportService.clip(p.getStem(), 70), "kind", "worker".equals(p.getKind()) ? "요청 처리" : "기타",
                    "size", size, "idle", at(p.getIdle(), focus), "active", at(p.getActive(), focus)));
        }
        m.put("pools", pools);
        m.put("poolOverflow", Math.max(0, poolTotal - pools.size()));

        // ── 정체 스레드 ──
        List<Map<String, Object>> stuck = new ArrayList<>();
        for (ThreadDumpResult.Stuck s : PdfReportService.limit(r.getStuck(), STUCK_MAX)) {
            stuck.add(row("name", PdfReportService.clip(nameOf(r, s.getT()), 60), "category", categoryLabel(s.getCategory()),
                    "span", (s.getFrom() + 1) + "~" + (s.getTo() + 1) + "번" + (s.getDurationSec() == null ? "" : " (" + fmtSec(s.getDurationSec()) + ")"),
                    "top", PdfReportService.clip(frameAt(r, s.getStackId(), 0), 95)));
        }
        m.put("stuck", stuck);
        m.put("stuckOverflow", Math.max(0, r.getStuck().size() - stuck.size()));

        // ── CPU 상위 ──
        List<Map<String, Object>> cpu = new ArrayList<>();
        for (ThreadDumpResult.CpuTop c : PdfReportService.limit(r.getCpuTop(), CPU_MAX)) {
            cpu.add(row("name", PdfReportService.clip(nameOf(r, c.getT()), 60), "delta", String.format(Locale.ROOT, "%,.0f ms", c.getDeltaMs()),
                    "pct", String.format(Locale.ROOT, "%.0f%%", c.getPct()), "top", PdfReportService.clip(topFrameOfTrack(r, c.getT(), c.getTo()), 80)));
        }
        m.put("cpuTop", cpu);

        // ── 상위 스택 그룹 ──
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ThreadDumpResult.StackGroup g : PdfReportService.limit(r.getStackGroups(), GROUPS_MAX)) {
            if (g.getCount() < 2 && !groups.isEmpty()) break;       // 한 개짜리 스택은 묶음이 아니다
            List<String> frames = frames(r, g.getStackId(), GROUP_FRAMES);
            int total = stackDepth(r, g.getStackId());
            groups.add(row("count", g.getCount(), "state", stateLabel(g.getState()), "frames", frames,
                    "more", Math.max(0, total - frames.size())));
        }
        m.put("stackGroups", groups);

        // ── 스냅샷 표 ──
        List<Map<String, Object>> snaps = new ArrayList<>();
        for (ThreadDumpResult.Snapshot s : PdfReportService.limit(r.getSnapshots(), SNAPSHOTS_MAX)) {
            Map<String, Integer> st = s.getStates();
            snaps.add(row("no", s.getIdx() + 1, "time", s.getTsText() == null ? "-" : s.getTsText(), "file", s.getFile() == null ? "" : PdfReportService.clip(s.getFile(), 40), "threads", s.getThreads(),
                    "runnable", st.getOrDefault("RUNNABLE", 0), "blocked", st.getOrDefault("BLOCKED", 0),
                    "waiting", st.getOrDefault("WAITING", 0) + st.getOrDefault("TIMED_WAITING", 0),
                    "deadlocks", s.getDeadlocks(), "note", snapshotNote(s)));
        }
        m.put("snapshots", snaps);
        m.put("snapshotOverflow", Math.max(0, r.getSnapshots().size() - snaps.size()));

        // ── AI ──
        m.put("ai", aiRow(aiInsight));

        // ── 파싱 진단 ──
        m.put("diagRows", diagRows(r));
        List<Map<String, Object>> samples = new ArrayList<>();
        for (ThreadDumpResult.LineSample ls : PdfReportService.limit(r.getDiagnostics().getUnparsedSamples(), 8)) {
            samples.add(row("line", ls.getLine(), "text", PdfReportService.clip(ls.getText(), 110)));
        }
        m.put("unparsedSamples", samples);
        return m;
    }

    // ── 힙 점유 ─────────────────────────────────────────────────

    static final int MEM_ROWS = 15;

    /** 힙 덤프에서 추출한 결과 — 스레드마다 붙은 Retained Heap 상위. */
    private static List<Map<String, Object>> heapMemoryRows(ThreadDumpResult r) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : HeapThreadService.topMemory(r, MEM_ROWS)) {
            rows.add(row("name", PdfReportService.clip(String.valueOf(t.get("name")), 60), "state", stateLabel(String.valueOf(t.get("state"))),
                    "retained", t.get("retainedText"),
                    "locals", t.get("locals") == null ? "-" : com.heapdump.analyzer.util.FormatUtils.formatBytes((Long) t.get("locals")),
                    "top", PdfReportService.clip(String.valueOf(t.get("top")), 90)));
        }
        return rows;
    }

    /** 연결된 힙 덤프 — 이름이 일치한 스레드의 Retained Heap 상위. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> linkMemoryRows(Map<String, Object> link) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (link == null || !(link.get("topThreads") instanceof List<?> top)) return rows;
        for (Object o : top) {
            if (rows.size() >= MEM_ROWS) break;
            Map<String, Object> t = (Map<String, Object>) o;
            rows.add(row("name", PdfReportService.clip(String.valueOf(t.get("name")), 60), "state", String.valueOf(t.get("heapState")),
                    "retained", t.get("retainedText"), "locals", "-", "top", "-"));
        }
        return rows;
    }

    private static String linkText(Map<String, Object> link) {
        if (link == null || !Boolean.TRUE.equals(link.get("linked"))) return null;
        StringBuilder sb = new StringBuilder(String.valueOf(link.get("heapFilename")));
        if (!Boolean.TRUE.equals(link.get("heapExists"))) return sb.append(" (분석 기록 없음)").toString();
        if (link.get("dumpTime") != null) sb.append(" · 생성 ").append(link.get("dumpTime"));
        if (link.get("gapSec") instanceof Long g) sb.append(g == 0 ? " · 스냅샷 구간 안" : " · 스냅샷과 " + ThreadDumpAnalyzerService.fmtGap(g * 1000) + " 차이");
        if (link.get("matchedThreads") != null) sb.append(" · 이름 일치 스레드 ").append(link.get("matchedThreads")).append('/').append(link.get("threadCount"));
        return sb.toString();
    }

    // ── 표시 문자열 ─────────────────────────────────────────────

    private static String spanText(ThreadDumpResult r) {
        ThreadDumpResult.Meta meta = r.getMeta();
        StringBuilder sb = new StringBuilder();
        List<ThreadDumpResult.Snapshot> ss = r.getSnapshots();
        String first = null, last = null;
        for (ThreadDumpResult.Snapshot s : ss) if (s.getTsText() != null) { if (first == null) first = s.getTsText(); last = s.getTsText(); }
        if (first != null) sb.append(first.equals(last) ? first : first + " ~ " + last);
        if (meta.getSpanSec() != null) sb.append(sb.length() > 0 ? " · " : "").append(fmtSec(meta.getSpanSec()))
                .append("elapsed".equals(meta.getIntervalSource()) ? " (elapsed 값으로 추정)" : "");
        if (sb.length() == 0) sb.append("시각 정보 없음");
        return sb.toString();
    }

    private static String snapshotNote(ThreadDumpResult.Snapshot s) {
        List<String> notes = new ArrayList<>();
        if (!s.isComplete()) {
            notes.add("EOF".equals(s.getIncompleteReason()) ? "파일 끝에서 잘림"
                    : "INTERRUPTED_BY_LOG".equals(s.getIncompleteReason()) ? "로그에 끊김"
                    : "NEXT_DUMP".equals(s.getIncompleteReason()) ? "다음 덤프에 끊김" : "트레일러 없음");
        }
        if (s.isHeaderless()) notes.add("헤더 없음");
        if (s.getSkippedThreads() > 0) notes.add(s.getSkippedThreads() + "개 건너뜀");
        return notes.isEmpty() ? "" : String.join(", ", notes);
    }

    private static List<Map<String, Object>> diagRows(ThreadDumpResult r) {
        ThreadDumpResult.Diagnostics d = r.getDiagnostics();
        List<Map<String, Object>> rows = new ArrayList<>();
        addDiag(rows, "읽은 줄", r.getMeta().getLines(), true);
        addDiag(rows, "덤프 밖 줄(서버 로그)", d.getNoiseLines(), false);
        addDiag(rows, "해석하지 못한 줄", d.getUnparsedLines(), false);
        addDiag(rows, "끝까지 읽히지 않은 스냅샷", d.getIncompleteSnapshots(), false);
        addDiag(rows, "버린 미완성 스레드", d.getIncompleteThreads(), false);
        addDiag(rows, "잘라 낸 긴 줄", d.getTruncatedLines(), false);
        addDiag(rows, "치환한 깨진 문자", d.getReplacedChars(), false);
        addDiag(rows, "지운 제어문자", d.getStrippedControlChars(), false);
        addDiag(rows, "알 수 없는 스레드 상태", d.getUnknownStates(), false);
        addDiag(rows, "알 수 없는 락 줄", d.getUnknownLockLines(), false);
        addDiag(rows, "시각 줄 없는 스냅샷", d.getMissingTimestamps(), false);
        if (!d.getLimitHits().isEmpty()) rows.add(row("label", "도달한 상한", "value", String.join(", ", d.getLimitHits())));
        if (d.getDecoratedPrefix() != null) rows.add(row("label", "줄 앞 접두(제거함)", "value", PdfReportService.clip(d.getDecoratedPrefix(), 60)));
        if (!d.getRuleFailures().isEmpty()) rows.add(row("label", "건너뛴 분석 규칙", "value", d.getRuleFailures().size() + "개"));
        return rows;
    }

    private static void addDiag(List<Map<String, Object>> rows, String label, long value, boolean always) {
        if (value > 0 || always) rows.add(row("label", label, "value", String.format(Locale.ROOT, "%,d", value)));
    }

    private static Map<String, Object> aiRow(Map<String, Object> ai) {
        if (ai == null || ai.isEmpty()) return null;
        String sev = ai.get("severity") == null ? "Unknown" : String.valueOf(ai.get("severity"));
        Object reco = ai.get("recommendations");
        List<String> recos = new ArrayList<>();
        if (reco instanceof Collection<?> c) { for (Object o : c) if (o != null) recos.add(PdfReportService.clip(String.valueOf(o), 300)); }
        else if (reco != null && !String.valueOf(reco).isBlank()) recos.add(PdfReportService.clip(String.valueOf(reco), 600));
        return row("severity", sev.toUpperCase(Locale.ROOT), "sevClass", sevClass(sev), "model", str(ai.get("model")),
                "summary", PdfReportService.clip(str(ai.get("summary")), 1200), "rootCause", PdfReportService.clip(str(ai.get("rootCause")), 1200),
                "recommendations", PdfReportService.limit(recos, 5), "codeAdvice", PdfReportService.clip(str(ai.get("codeAdvice")), 1200));
    }

    static String sevClass(String s) {
        if (s == null) return "info";
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "critical" -> "critical";
            case "high" -> "high";
            case "medium" -> "medium";
            case "low" -> "low";
            default -> "info";
        };
    }

    static String sevLabel(String s) {
        return switch (sevClass(s)) {
            case "critical" -> "심각";
            case "high" -> "높음";
            case "medium" -> "보통";
            case "low" -> "낮음";
            default -> "정보";
        };
    }

    static String stateLabel(String s) {
        if (s == null) return "-";
        return switch (s) {
            case "RUNNABLE" -> "RUNNABLE (실행)";
            case "BLOCKED" -> "BLOCKED (락 대기)";
            case "WAITING" -> "WAITING (대기)";
            case "TIMED_WAITING" -> "TIMED_WAITING (시한 대기)";
            case "VM" -> "JVM 내부 스레드";
            case "UNKNOWN" -> "알 수 없음";
            default -> s;
        };
    }

    static String categoryLabel(String c) {
        if (c == null) return "-";
        return switch (c) {
            case "deadlock" -> "교착";
            case "blocked" -> "락 대기(BLOCKED)";
            case "lock-wait" -> "락 대기(ReentrantLock)";
            case "pool-wait" -> "커넥션 풀 대기";
            case "io" -> "I/O 응답 대기";
            case "running" -> "실행 중";
            case "sleeping" -> "sleep";
            case "waiting" -> "대기";
            default -> c;
        };
    }

    private static String fmtSec(double sec) {
        if (sec < 60) return String.format(Locale.ROOT, "%.0f초", sec);
        if (sec < 3600) return String.format(Locale.ROOT, "%d분 %d초", (int) (sec / 60), (int) (sec % 60));
        return String.format(Locale.ROOT, "%d시간 %d분", (int) (sec / 3600), (int) ((sec % 3600) / 60));
    }

    private static String lockText(String addr, String cls) {
        String c = cls == null ? "?" : cls;
        int i = c.lastIndexOf('.');
        return (addr == null ? "?" : addr) + " (" + PdfReportService.clip(i < 0 ? c : c.substring(i + 1), 50) + ")";
    }

    private static String stripHeader(String vmLine) {
        if (vmLine == null) return null;
        String s = vmLine.startsWith("Full thread dump ") ? vmLine.substring("Full thread dump ".length()) : vmLine;
        return s.endsWith(":") ? s.substring(0, s.length() - 1) : s;
    }

    // ── 인덱스 조회(범위를 벗어나도 던지지 않는다 — 줄여 저장된 결과도 그대로 렌더돼야 한다) ──

    private static String nameOf(ThreadDumpResult r, int track) {
        if (track < 0 || track >= r.getTracks().size()) return "?";
        int n = r.getTracks().get(track).getN();
        return n >= 0 && n < r.getNames().size() ? r.getNames().get(n) : "?";
    }

    private static int stackOfTrack(ThreadDumpResult r, int track, int snap) {
        if (track < 0 || track >= r.getTracks().size()) return -1;
        ThreadDumpResult.Track t = r.getTracks().get(track);
        List<Integer> k = t.getK();
        int off = snap - t.getFirst();
        int sid = off >= 0 && off < k.size() && k.get(off) != null ? k.get(off) : -1;
        for (int i = k.size() - 1; sid < 0 && i >= 0; i--) if (k.get(i) != null) sid = k.get(i);
        return sid;
    }

    private static String topFrameOfTrack(ThreadDumpResult r, int track, int snap) {
        String f = frameAt(r, stackOfTrack(r, track, snap), 0);
        return f == null ? "-" : f;
    }

    private static List<String> framesOfTrack(ThreadDumpResult r, int track, int snap, int max) {
        return frames(r, stackOfTrack(r, track, snap), max);
    }

    private static List<String> frames(ThreadDumpResult r, int stackId, int max) {
        List<String> out = new ArrayList<>();
        if (stackId < 0 || stackId >= r.getStacks().size()) return out;
        List<Integer> f = r.getStacks().get(stackId).getF();
        for (int i = 0; i < Math.min(max, f.size()); i++) {
            Integer fi = f.get(i);
            if (fi != null && fi >= 0 && fi < r.getFrames().size()) out.add(PdfReportService.clip(r.getFrames().get(fi), 120));
        }
        return out;
    }

    private static int stackDepth(ThreadDumpResult r, int stackId) {
        if (stackId < 0 || stackId >= r.getStacks().size()) return 0;
        return r.getStacks().get(stackId).getF().size() + r.getStacks().get(stackId).getCut();
    }

    private static String frameAt(ThreadDumpResult r, int stackId, int idx) {
        List<String> f = frames(r, stackId, idx + 1);
        return f.size() > idx ? f.get(idx) : null;
    }

    private static int at(List<Integer> list, int idx) {
        return idx >= 0 && idx < list.size() && list.get(idx) != null ? list.get(idx) : 0;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}

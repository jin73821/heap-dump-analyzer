package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.TargetServerRepository;
import com.heapdump.analyzer.util.JvmHeapCapture;
import com.heapdump.analyzer.util.JvmHeapCapture.Candidate;
import com.heapdump.analyzer.util.JvmHeapCapture.Capture;
import com.heapdump.analyzer.util.JvmHeapCapture.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 분석 건의 JVM 힙 설정(-Xms/-Xmx) — "현재 진실"({@code analysis_history.jvm_*}) 관리 (2026-09-11).
 *
 * <p>전송 시점 캡처({@code dump_transfer_log.jvm_info})는 불변 스냅샷이고, 분석 완료 시 {@link #reconcile} 이
 * MAT System Properties 로 재매칭해 여기로 복사한다. 이후 운영자가 수동 입력({@code manual})·후보 선택({@code selected})
 * 하면 자동 경로(재분석)는 덮지 않는다 — 규칙은 {@link #mayOverwrite} 한 곳. 재수집(⟳)은 사용자가 누른 것이라 덮는다.
 *
 * <p>의존 방향: {@code HeapDumpAnalyzerService → JvmHeapInfoService → RemoteDumpService}. 반대로 부르면 순환.
 */
@Service
public class JvmHeapInfoService {

    private static final Logger logger = LoggerFactory.getLogger(JvmHeapInfoService.class);
    private static final DateTimeFormatter DUMP_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter SHORT_TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final long RECOLLECT_GUARD_MS = 10_000L;

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_SELECTED = "selected";
    public static final String SOURCE_MANUAL = "manual";

    private final AnalysisHistoryRepository historyRepository;
    private final DumpTransferLogRepository transferLogRepository;
    private final TargetServerRepository targetServerRepository;
    private final RemoteDumpService remoteDumpService;
    private final Map<String, Long> recollectInFlight = new ConcurrentHashMap<>();

    public JvmHeapInfoService(AnalysisHistoryRepository historyRepository,
                              DumpTransferLogRepository transferLogRepository,
                              TargetServerRepository targetServerRepository,
                              RemoteDumpService remoteDumpService) {
        this.historyRepository = historyRepository;
        this.transferLogRepository = transferLogRepository;
        this.targetServerRepository = targetServerRepository;
        this.remoteDumpService = remoteDumpService;
    }

    /** 캡처 + 매칭을 엔티티 컬럼 모양으로 편 결과. matched 가 false 면 값은 없고 infoJson(후보)만 있다. */
    public record Resolution(boolean matched, Long xms, Long xmx, String flags, String options,
                             Integer pid, LocalDateTime capturedAt, String infoJson, String reason) {}

    // ── 분석 완료 시 확정 ───────────────────────────────────────

    /**
     * 전송 로그의 캡처를 분석 결과(sysProps·hprof 생성 시각)로 재매칭한다. 캡처가 없으면 null.
     * 서비스가 없거나 리포지토리 접근이 실패해도 분석 저장을 막지 않도록 절대 던지지 않는다.
     */
    public Resolution reconcile(DumpTransferLog log, HeapAnalysisResult result) {
        try {
            if (log == null) return null;
            Capture cap = JvmHeapCapture.fromJson(log.getJvmInfo());
            if (cap == null) return null;
            String remoteName = log.getRemoteFilename() != null ? log.getRemoteFilename() : log.getFilename();
            Map<String, String> sysProps = result != null ? result.getSystemProperties() : null;
            Long dumpEpoch = result != null ? toEpoch(result.getDumpCreationTime()) : null;
            Match m = JvmHeapCapture.match(cap, remoteName, JvmHeapCapture.dirOf(log.getRemotePath()), sysProps, dumpEpoch);
            return toResolution(cap, m);
        } catch (RuntimeException e) {
            logger.warn("[JvmHeap] reconcile failed file={} — {}", log != null ? log.getFilename() : "?", e.getMessage());
            return null;
        }
    }

    /** 수동 입력·후보 선택값은 자동 경로가 덮지 않는다. */
    public boolean mayOverwrite(AnalysisHistoryEntity e) {
        if (e == null) return true;
        String s = e.getJvmHeapSource();
        return !(SOURCE_MANUAL.equals(s) || SOURCE_SELECTED.equals(s));
    }

    /**
     * Resolution 을 엔티티에 적용(저장은 호출자). 매칭됐으면 값 전부 + source=auto, 미확정이면 후보(jvm_info)만 갱신해
     * 화면에서 고를 수 있게 한다. {@link #mayOverwrite} 가 false 면 아무것도 바꾸지 않는다(선택 인덱스가 참조하는 jvm_info 포함).
     */
    public void apply(AnalysisHistoryEntity e, Resolution r) {
        if (e == null || r == null || !mayOverwrite(e)) return;
        e.setJvmInfo(r.infoJson());
        if (!r.matched()) return;
        e.setJvmXmsBytes(r.xms());
        e.setJvmXmxBytes(r.xmx());
        e.setJvmHeapSource(SOURCE_AUTO);
        e.setJvmHeapFlags(r.flags());
        e.setJvmOptions(r.options());
        e.setJvmPid(r.pid());
        e.setJvmCapturedAt(r.capturedAt());
    }

    private static Resolution toResolution(Capture cap, Match m) {
        Capture withMatch = cap.withMatch(m);
        String json = JvmHeapCapture.toJson(withMatch);
        Candidate c = withMatch.matched();
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochSecond(cap.capturedAtEpoch()), ZoneId.systemDefault());
        if (c == null) return new Resolution(false, null, null, null, null, null, at, json, m.reason());
        Set<String> flags = new LinkedHashSet<>(JvmHeapCapture.candidateFlags(c));
        flags.addAll(m.flags());
        String options = String.join(" ", c.options());
        if (options.length() > 2000) options = options.substring(0, 2000);
        return new Resolution(true, c.xmsBytes(), c.xmxBytes(), flags.isEmpty() ? null : String.join(",", flags),
                options.isEmpty() ? null : options, c.pid(), at, json, m.reason());
    }

    /** "2026-05-29 18:18:53"(MAT 파싱값, 서버 로컬 시각) → epoch 초. 형식이 다르면 null. */
    static Long toEpoch(String dumpCreationTime) {
        if (dumpCreationTime == null || dumpCreationTime.isBlank()) return null;
        try {
            return LocalDateTime.parse(dumpCreationTime.trim(), DUMP_TS).atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 조회 ────────────────────────────────────────────────────

    /**
     * 칩/AI 모달/PDF 공용 뷰. 엔티티가 없어도 빈 뷰를 돌려준다(템플릿 null 가드 불필요).
     * @param usedHeapBytes MAT 의 used heap(=analyze 화면 Total Heap) — Xmx 대비 % 계산용. 모르면 null.
     */
    public Map<String, Object> view(String filename, Long usedHeapBytes) {
        AnalysisHistoryEntity e = find(filename);
        return toView(e, usedHeapBytes);
    }

    private Map<String, Object> toView(AnalysisHistoryEntity e, Long usedHeapBytes) {
        Map<String, Object> v = new LinkedHashMap<>();
        Long xms = e == null ? null : e.getJvmXmsBytes();
        Long xmx = e == null ? null : e.getJvmXmxBytes();
        v.put("hasValue", xms != null || xmx != null);
        v.put("xmsBytes", xms);
        v.put("xmxBytes", xmx);
        v.put("xms", JvmHeapCapture.formatSizeOrNull(xms));
        v.put("xmx", JvmHeapCapture.formatSizeOrNull(xmx));
        String source = e == null ? null : e.getJvmHeapSource();
        v.put("source", source);
        v.put("sourceLabel", sourceLabel(source));
        List<String> flags = splitFlags(e == null ? null : e.getJvmHeapFlags());
        v.put("flags", flags);
        List<Map<String, String>> fl = new ArrayList<>();
        for (String f : flags) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("flag", f);
            m.put("label", JvmHeapCapture.flagLabel(f));
            m.put("hint", JvmHeapCapture.flagHint(f));
            fl.add(m);
        }
        v.put("flagLabels", fl);
        v.put("pid", e == null ? null : e.getJvmPid());
        v.put("options", e == null ? null : e.getJvmOptions());
        LocalDateTime at = e == null ? null : e.getJvmCapturedAt();
        v.put("capturedAt", at == null ? null : at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        v.put("capturedAtShort", at == null ? null : at.format(SHORT_TS));
        Capture cap = e == null ? null : JvmHeapCapture.fromJson(e.getJvmInfo());
        int candidateCount = cap == null ? 0 : cap.candidates().size();
        boolean ambiguous = candidateCount > 1 && (xms == null && xmx == null);
        v.put("candidateCount", candidateCount);
        v.put("ambiguous", ambiguous);
        v.put("processListRestricted", cap != null && cap.processListRestricted());
        v.put("captureNote", cap == null ? null : cap.note());
        v.put("recollectable", e != null && originServer(e).isPresent());
        Integer pct = null;
        if (xmx != null && xmx > 0 && usedHeapBytes != null && usedHeapBytes > 0) {
            pct = (int) Math.round(100.0 * usedHeapBytes / xmx);
        }
        v.put("usedPctOfXmx", pct);
        v.put("summary", summaryText(xms, xmx, source, flags));
        v.put("tipText", tipText(xms, xmx, source, fl, e, cap, candidateCount, ambiguous));
        return v;
    }

    /** PDF·툴팁용 한 줄: {@code Xms 2g / Xmx 8g (자동 · 추정)} / {@code 미지정}. */
    public String label(String filename) {
        AnalysisHistoryEntity e = find(filename);
        if (e == null) return "미지정";
        return summaryText(e.getJvmXmsBytes(), e.getJvmXmxBytes(), e.getJvmHeapSource(), splitFlags(e.getJvmHeapFlags()));
    }

    private static String summaryText(Long xms, Long xmx, String source, List<String> flags) {
        if (xms == null && xmx == null) return "미지정";
        StringBuilder sb = new StringBuilder();
        sb.append("Xms ").append(xms == null ? "-" : JvmHeapCapture.formatSize(xms));
        sb.append(" / Xmx ").append(xmx == null ? "-" : JvmHeapCapture.formatSize(xmx));
        List<String> tags = new ArrayList<>();
        String sl = sourceLabel(source);
        if (sl != null) tags.add(sl);
        for (String f : flags) tags.add(JvmHeapCapture.flagLabel(f));
        if (!tags.isEmpty()) sb.append(" (").append(String.join(" · ", tags)).append(")");
        return sb.toString();
    }

    /**
     * ⓘ 아이콘 툴팁 본문 (2026-09-12) — 칩에 글자 배지로 흩어져 있던 출처·신뢰도와, 그동안 화면에 없던
     * 수집 시각(pid 포함)을 한데 모은다. <b>서버가 유일한 출처다</b>: 같은 문구를 Thymeleaf 초기 렌더와
     * {@code renderJvmChip} 재렌더가 함께 쓰는데(칩은 편집·후보 선택·재수집 때마다 JS 가 다시 그린다)
     * 양쪽에서 조립하면 예외 없이 문구만 갈라진다.
     *
     * <p>줄바꿈은 {@code \n} — krds 팝오버가 {@code white-space: pre-wrap} 이라 그대로 보인다.
     * 빈 줄({@code ""} 항목)로 단락을 나눈다: 값·출처·시각 / 신뢰도 / 안내 / 옵션.
     */
    private static String tipText(Long xms, Long xmx, String source, List<Map<String, String>> flagLabels,
                                  AnalysisHistoryEntity e, Capture cap, int candidateCount, boolean ambiguous) {
        List<String> lines = new ArrayList<>();
        boolean hasValue = xms != null || xmx != null;
        lines.add("JVM 힙 설정 — " + (hasValue
                ? "Xms " + (xms == null ? "-" : JvmHeapCapture.formatSize(xms))
                        + " / Xmx " + (xmx == null ? "-" : JvmHeapCapture.formatSize(xmx))
                : "미지정"));

        String sl = sourceLabel(source);
        if (sl != null) lines.add("출처: " + sl);

        LocalDateTime at = e == null ? null : e.getJvmCapturedAt();
        Integer pid = e == null ? null : e.getJvmPid();
        if (at != null) {
            lines.add("수집 시각: " + at.format(DUMP_TS) + (pid != null ? " (pid " + pid + ")" : ""));
        }

        if (!flagLabels.isEmpty()) lines.add("");
        for (Map<String, String> f : flagLabels) {
            String label = f.get("label");
            String hint = f.get("hint");
            lines.add(hint == null || hint.isBlank() ? label : label + " — " + hint);
        }

        if (ambiguous) {
            lines.add("");
            lines.add("java 프로세스가 " + candidateCount + "개라 자동 확정하지 못했습니다 — 목록(☰)에서 고르세요.");
        } else if (!hasValue) {
            lines.add("");
            lines.add("SSH 전송 시 원격 서버의 java 프로세스에서 자동 수집됩니다. 연필(✎)로 직접 입력할 수 있습니다.");
        }
        String note = cap == null ? null : cap.note();
        if (!hasValue && note != null && !note.isBlank()) lines.add("수집 메모: " + note);

        String options = e == null ? null : e.getJvmOptions();
        if (options != null && !options.isBlank()) {
            lines.add("");
            lines.add("JVM 옵션: " + options);
        }
        return String.join("\n", lines);
    }

    public static String sourceLabel(String source) {
        if (source == null) return null;
        return switch (source) {
            case SOURCE_AUTO -> "자동 수집";
            case SOURCE_SELECTED -> "후보 선택";
            case SOURCE_MANUAL -> "수동 입력";
            default -> source;
        };
    }

    private static List<String> splitFlags(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String f : s.split(",")) if (!f.isBlank()) out.add(f.trim());
        return out;
    }

    /** 후보 선택 모달용 — 옵션·cwd·exe 는 빼고 식별에 필요한 것만. */
    public List<Map<String, Object>> candidates(String filename) {
        AnalysisHistoryEntity e = find(filename);
        return candidatesForClient(e == null ? null : e.getJvmInfo());
    }

    public static List<Map<String, Object>> candidatesForClient(String infoJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        Capture cap = JvmHeapCapture.fromJson(infoJson);
        if (cap == null) return out;
        for (int i = 0; i < cap.candidates().size(); i++) {
            Candidate c = cap.candidates().get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("pid", c.pid());
            m.put("user", c.user());
            m.put("mainClass", c.mainClass());
            m.put("xms", JvmHeapCapture.formatSizeOrNull(c.xmsBytes()));
            m.put("xmx", JvmHeapCapture.formatSizeOrNull(c.xmxBytes()));
            m.put("startedAt", c.startEpoch() == null ? null
                    : LocalDateTime.ofInstant(Instant.ofEpochSecond(c.startEpoch()), ZoneId.systemDefault()).format(SHORT_TS));
            m.put("restarted", cap.dumpMtimeEpoch() != null && c.startEpoch() != null && c.startEpoch() > cap.dumpMtimeEpoch());
            m.put("markers", c.markers());
            List<String> fl = new ArrayList<>();
            for (String f : JvmHeapCapture.candidateFlags(c)) fl.add(JvmHeapCapture.flagLabel(f));
            m.put("flagLabels", fl);
            m.put("matched", cap.matchedIndex() != null && cap.matchedIndex() == i);
            out.add(m);
        }
        return out;
    }

    /** 전송 SSE done 이벤트용 요약 — 값·개수만. */
    public static Map<String, Object> summaryForClient(String infoJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        Capture cap = JvmHeapCapture.fromJson(infoJson);
        if (cap == null) { m.put("status", "none"); return m; }
        Candidate c = cap.matched();
        m.put("candidateCount", cap.candidates().size());
        if (c != null) {
            m.put("status", "collected");
            m.put("xms", JvmHeapCapture.formatSizeOrNull(c.xmsBytes()));
            m.put("xmx", JvmHeapCapture.formatSizeOrNull(c.xmxBytes()));
            m.put("pid", c.pid());
            List<String> fl = new ArrayList<>();
            for (String f : JvmHeapCapture.candidateFlags(c)) fl.add(JvmHeapCapture.flagLabel(f));
            for (String f : cap.matchFlags()) if (!fl.contains(JvmHeapCapture.flagLabel(f))) fl.add(JvmHeapCapture.flagLabel(f));
            m.put("flagLabels", fl);
        } else if (!cap.candidates().isEmpty()) {
            m.put("status", "ambiguous");
        } else {
            m.put("status", cap.truncated() ? "error" : "none");
            m.put("note", cap.note());
        }
        return m;
    }

    // ── 편집 ────────────────────────────────────────────────────

    /** 수동 입력. 둘 다 비면 미지정으로 초기화. 잘못된 표기는 IllegalArgumentException(→ 400). 레코드 없으면 null. */
    @Transactional
    public Map<String, Object> updateManual(String filename, String xms, String xmx, Long usedHeapBytes) {
        AnalysisHistoryEntity e = find(filename);
        if (e == null) return null;
        Long xmsB = parseOrNull(xms), xmxB = parseOrNull(xmx);
        if (xmsB != null && xmxB != null && xmsB > xmxB) throw new IllegalArgumentException("-Xms 가 -Xmx 보다 큽니다");
        e.setJvmXmsBytes(xmsB);
        e.setJvmXmxBytes(xmxB);
        e.setJvmHeapFlags(null);
        e.setJvmOptions(null);
        e.setJvmPid(null);
        e.setJvmCapturedAt(null);
        e.setJvmHeapSource(xmsB == null && xmxB == null ? null : SOURCE_MANUAL);
        historyRepository.save(e);
        return toView(e, usedHeapBytes);
    }

    private static Long parseOrNull(String s) {
        return s == null || s.isBlank() ? null : JvmHeapCapture.parseSize(s);
    }

    /** 후보 선택. 범위 밖 인덱스는 IllegalArgumentException(→ 400). 레코드 없으면 null. */
    @Transactional
    public Map<String, Object> selectCandidate(String filename, int index, Long usedHeapBytes) {
        AnalysisHistoryEntity e = find(filename);
        if (e == null) return null;
        Capture cap = JvmHeapCapture.fromJson(e.getJvmInfo());
        if (cap == null || index < 0 || index >= cap.candidates().size()) {
            throw new IllegalArgumentException("선택할 수 있는 후보가 아닙니다: " + index);
        }
        Candidate c = cap.candidates().get(index);
        e.setJvmXmsBytes(c.xmsBytes());
        e.setJvmXmxBytes(c.xmxBytes());
        e.setJvmHeapSource(SOURCE_SELECTED);
        Set<String> flags = new LinkedHashSet<>(JvmHeapCapture.candidateFlags(c));
        if (cap.dumpMtimeEpoch() != null && c.startEpoch() != null && c.startEpoch() > cap.dumpMtimeEpoch()) {
            flags.add(JvmHeapCapture.FLAG_RESTARTED);
        }
        e.setJvmHeapFlags(flags.isEmpty() ? null : String.join(",", flags));
        String options = String.join(" ", c.options());
        e.setJvmOptions(options.isEmpty() ? null : (options.length() > 2000 ? options.substring(0, 2000) : options));
        e.setJvmPid(c.pid());
        e.setJvmCapturedAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(cap.capturedAtEpoch()), ZoneId.systemDefault()));
        historyRepository.save(e);
        return toView(e, usedHeapBytes);
    }

    /** 재수집 결과. code 는 null(성공) / NOT_FOUND / NO_ORIGIN / BUSY / SSH_FAIL. */
    public record RecollectResult(String code, String error, boolean needsSelection,
                                  Map<String, Object> view, List<Map<String, Object>> candidates) {}

    /**
     * 출처 서버에서 다시 수집한다. 사용자가 명시적으로 누른 동작이라 매칭이 확정되면 source 와 무관하게 auto 로 덮고,
     * 후보가 여럿이면 값은 두고 후보만 갱신해 선택 모달을 띄우게 한다. 캐시는 쓰지 않는다(새 값을 보려고 누른 것).
     * @param sysProps / dumpCreationTime 분석 결과가 있으면 재매칭 힌트로 사용(없으면 null)
     */
    public RecollectResult recollect(String filename, Map<String, String> sysProps, String dumpCreationTime, Long usedHeapBytes) {
        AnalysisHistoryEntity e = find(filename);
        if (e == null) return new RecollectResult("NOT_FOUND", "분석 이력 레코드를 찾을 수 없습니다: " + filename, false, null, null);
        Optional<TargetServer> origin = originServer(e);
        if (origin.isEmpty()) {
            return new RecollectResult("NO_ORIGIN", "출처 서버를 알 수 없어 재수집할 수 없습니다 (수동 업로드 덤프는 직접 입력하세요)",
                    false, toView(e, usedHeapBytes), null);
        }
        long now = System.currentTimeMillis();
        Long prev = recollectInFlight.putIfAbsent(filename, now);
        if (prev != null && now - prev < RECOLLECT_GUARD_MS) {
            return new RecollectResult("BUSY", "재수집이 이미 진행 중입니다", false, toView(e, usedHeapBytes), null);
        }
        recollectInFlight.put(filename, now);
        try {
            TargetServer server = origin.get();
            String remotePath = null;
            List<DumpTransferLog> logs = transferLogRepository
                    .findByFilenameAndTransferStatusOrderByCompletedAtDesc(filename, "SUCCESS");
            if (!logs.isEmpty()) remotePath = logs.get(0).getRemotePath();
            remoteDumpService.clearJvmCaptureCache();
            String json = remoteDumpService.captureJvmInfo(server, remotePath);
            Capture cap = JvmHeapCapture.fromJson(json);
            if (cap == null || (cap.truncated() && cap.candidates().isEmpty())) {
                String note = cap == null ? "응답 없음" : cap.note();
                return new RecollectResult("SSH_FAIL", "원격 수집 실패: " + note, false, toView(e, usedHeapBytes), null);
            }
            String remoteName = remotePath == null ? filename : remotePath.substring(remotePath.lastIndexOf('/') + 1);
            Match m = JvmHeapCapture.match(cap, remoteName, JvmHeapCapture.dirOf(remotePath), sysProps, toEpoch(dumpCreationTime));
            Resolution r = toResolution(cap, m);
            e.setJvmInfo(r.infoJson());
            if (r.matched()) {
                e.setJvmXmsBytes(r.xms());
                e.setJvmXmxBytes(r.xmx());
                e.setJvmHeapSource(SOURCE_AUTO);
                e.setJvmHeapFlags(r.flags());
                e.setJvmOptions(r.options());
                e.setJvmPid(r.pid());
                e.setJvmCapturedAt(r.capturedAt());
            }
            historyRepository.save(e);
            boolean needsSelection = !r.matched() && !cap.candidates().isEmpty();
            return new RecollectResult(null, null, needsSelection, toView(e, usedHeapBytes),
                    needsSelection ? candidatesForClient(r.infoJson()) : null);
        } finally {
            recollectInFlight.remove(filename);
        }
    }

    // ── 내부 ────────────────────────────────────────────────────

    private AnalysisHistoryEntity find(String filename) {
        if (filename == null) return null;
        try {
            return historyRepository.findByFilename(filename).orElse(null);
        } catch (RuntimeException ex) {
            logger.debug("[JvmHeap] history lookup failed for {}: {}", filename, ex.getMessage());
            return null;
        }
    }

    /** 출처 서버 — server_id 우선, 없으면 server_name 이 등록 서버명과 같을 때(수동 업로드 덤프도 재수집 가능). */
    private Optional<TargetServer> originServer(AnalysisHistoryEntity e) {
        try {
            if (e.getServerId() != null) {
                Optional<TargetServer> byId = targetServerRepository.findById(e.getServerId());
                if (byId.isPresent()) return byId;
            }
            String name = e.getServerName();
            if (name != null && !name.isBlank()) {
                for (TargetServer s : targetServerRepository.findAll()) {
                    if (name.trim().equalsIgnoreCase(s.getName())) return Optional.of(s);
                }
            }
        } catch (RuntimeException ex) {
            logger.debug("[JvmHeap] origin lookup failed: {}", ex.getMessage());
        }
        return Optional.empty();
    }
}

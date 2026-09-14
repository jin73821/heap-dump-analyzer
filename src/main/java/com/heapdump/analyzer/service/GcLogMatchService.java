package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.util.JvmHeapCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GC 로그 ↔ 힙 덤프 매칭 (2026-09-14).
 *
 * <p>신호 3종 — ① 출처 서버 일치(server_id 또는 server_name 대소문자 무시) ② 덤프 생성 시각이 로그 시간 범위
 * ±tolerance 안(time_source 가 mtime 이면 tol×6 + 0.5점 WEAK, none 이면 0) ③ 경로: 덤프 JVM 수집의 매칭 후보가 쓰는
 * {@code -Xloggc}/{@code -Xlog} 경로 == 로그 원격 경로(회전 접미사·%t 정규화), 보조로 로그 JVM 후보의
 * {@code -XX:HeapDumpPath} 디렉토리 == 덤프 전송 원격 디렉토리.
 *
 * <p><b>강한 단일 후보만 자동 확정</b>: score ≥ 2 인 후보가 정확히 1개일 때 {@code auto}. 그 외는 상위 10건을 후보로
 * 저장하고 {@code none} 유지 — JVM 힙 수집의 "그럴듯한 오답보다 미확정" 원칙과 같다.
 * {@code manual} 은 연결·해제 모두 자동이 덮지 않는다({@link #mayOverwrite}); {@code rematch} 만 예외.
 */
@Service
public class GcLogMatchService {

    private static final Logger logger = LoggerFactory.getLogger(GcLogMatchService.class);
    private static final int MAX_CANDIDATES = 10;
    private static final long CANDIDATE_WINDOW_SEC = 86_400L;   // 로그 범위 ±1일 안의 덤프만 후보

    private final GcLogAnalysisRepository gcLogRepository;
    private final AnalysisHistoryRepository historyRepository;
    private final DumpTransferLogRepository transferLogRepository;
    private final HeapDumpConfig config;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public GcLogMatchService(GcLogAnalysisRepository gcLogRepository,
                             AnalysisHistoryRepository historyRepository,
                             DumpTransferLogRepository transferLogRepository,
                             HeapDumpConfig config) {
        this.gcLogRepository = gcLogRepository;
        this.historyRepository = historyRepository;
        this.transferLogRepository = transferLogRepository;
        this.config = config;
    }

    // ── 순수 판정 (테스트 대상) ───────────────────────────────────

    /** 후보 1건의 점수와 근거. */
    public record Candidate(String dumpFilename, double score, List<String> reasons, String dumpCreationTime, String serverName) {}

    /** 자동 매칭 판정 결과 — {@code confirmed} 가 null 이면 미확정. */
    public record Decision(Candidate confirmed, List<Candidate> candidates, String reason) {}

    /** 매칭 판정 입력(덤프 쪽) — 엔티티·전송로그에서 뽑은 최소 정보. */
    public record DumpFacts(String filename, Long serverId, String serverName, Long dumpEpochSec,
                            String jvmGcLogPath, String jvmCwd, String remoteDumpDir) {}

    /** 매칭 판정 입력(로그 쪽). */
    public record LogFacts(Long serverId, String serverName, String remotePath, Long logStartEpochSec, Long logEndEpochSec,
                           String timeSource, String jvmHeapDumpPathDir) {}

    public static boolean mayOverwrite(GcLogAnalysisEntity log) {
        return !GcLogAnalysisEntity.MATCH_MANUAL.equals(log.getMatchSource());
    }

    public static Decision decide(LogFacts log, List<DumpFacts> dumps, int toleranceMin) {
        List<Candidate> scored = new ArrayList<>();
        for (DumpFacts d : dumps) {
            List<String> reasons = new ArrayList<>();
            double score = 0;
            if (sameServer(log.serverId(), log.serverName(), d.serverId(), d.serverName())) { score += 1; reasons.add("server"); }
            double t = timeScore(log, d, toleranceMin);
            if (t > 0) { score += t; reasons.add(t >= 1 ? "time" : "time-weak"); }
            if (pathHit(log, d)) { score += 1; reasons.add("path"); }
            if (score > 0) scored.add(new Candidate(d.filename(), score, reasons, epochToLocal(d.dumpEpochSec()), d.serverName()));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Candidate> strong = scored.stream().filter(c -> c.score() >= 2).toList();
        List<Candidate> top = scored.size() > MAX_CANDIDATES ? new ArrayList<>(scored.subList(0, MAX_CANDIDATES)) : scored;
        if (strong.size() == 1) return new Decision(strong.get(0), top, String.join("+", strong.get(0).reasons()));
        if (strong.size() > 1) return new Decision(null, top, "ambiguous:" + strong.size());
        return new Decision(null, top, scored.isEmpty() ? "no-candidates" : "weak");
    }

    static boolean sameServer(Long lid, String lname, Long did, String dname) {
        if (lid != null && did != null) return lid.equals(did);
        if (lname != null && dname != null) return lname.trim().equalsIgnoreCase(dname.trim());
        return false;
    }

    private static double timeScore(LogFacts log, DumpFacts d, int toleranceMin) {
        if (d.dumpEpochSec() == null || log.logStartEpochSec() == null || log.logEndEpochSec() == null) return 0;
        String src = log.timeSource() == null ? "none" : log.timeSource();
        if ("none".equals(src)) return 0;
        long tol = Math.max(0, toleranceMin) * 60L * ("mtime".equals(src) ? 6 : 1);
        long ts = d.dumpEpochSec();
        if (ts < log.logStartEpochSec() - tol || ts > log.logEndEpochSec() + tol) return 0;
        return "mtime".equals(src) ? 0.5 : 1.0;
    }

    private static boolean pathHit(LogFacts log, DumpFacts d) {
        if (d.jvmGcLogPath() != null && log.remotePath() != null
                && JvmHeapCapture.sameGcLogPath(d.jvmGcLogPath(), log.remotePath(), d.jvmCwd())) return true;
        if (log.jvmHeapDumpPathDir() != null && d.remoteDumpDir() != null
                && normDir(log.jvmHeapDumpPathDir()).equals(normDir(d.remoteDumpDir()))) return true;
        return false;
    }

    private static String normDir(String s) {
        String r = s.trim();
        while (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static String epochToLocal(Long epochSec) {
        if (epochSec == null) return null;
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSec), ZoneId.systemDefault()).toString().replace('T', ' ');
    }

    // ── 엔티티 적용 ──────────────────────────────────────────────

    /** 자동 매칭 시도. manual 이면 no-op. 결과를 엔티티에 쓰고 저장한다(예외 전부 흡수). */
    public void tryAutoMatch(GcLogAnalysisEntity log) {
        tryAutoMatch(log, false);
    }

    public Decision tryAutoMatch(GcLogAnalysisEntity log, boolean force) {
        if (log == null) return null;
        if (!force && !mayOverwrite(log)) return null;
        try {
            LogFacts lf = logFacts(log);
            List<DumpFacts> dumps = candidateDumps(lf);
            Decision d = decide(lf, dumps, config.getGcLogMatchToleranceMin());
            apply(log, d);
            gcLogRepository.save(log);
            logger.info("[GcLog] action=auto-match file={} result={} reason={} candidates={}",
                    log.getFilename(), d.confirmed() == null ? "-" : d.confirmed().dumpFilename(), d.reason(), d.candidates().size());
            return d;
        } catch (Exception e) {
            logger.warn("[GcLog] auto-match failed file={} — {}", log.getFilename(), e.getMessage());
            return null;
        }
    }

    private void apply(GcLogAnalysisEntity log, Decision d) {
        log.setMatchCandidates(toJson(d.candidates()));
        if (d.confirmed() != null) {
            log.setMatchedDumpFilename(d.confirmed().dumpFilename());
            log.setMatchSource(GcLogAnalysisEntity.MATCH_AUTO);
            log.setMatchReason(d.reason());
            log.setMatchedAt(LocalDateTime.now());
        } else {
            log.setMatchedDumpFilename(null);
            log.setMatchSource(GcLogAnalysisEntity.MATCH_NONE);
            log.setMatchReason(d.reason());
            log.setMatchedAt(null);
        }
    }

    /** 수동 연결/해제. {@code dumpFilename==null} 은 해제(이후 자동 재연결 금지). */
    public GcLogAnalysisEntity setManual(GcLogAnalysisEntity log, String dumpFilename) {
        if (dumpFilename != null && !historyRepository.existsByFilename(dumpFilename)) {
            throw new IllegalArgumentException("힙 덤프 분석 기록이 없습니다: " + dumpFilename);
        }
        log.setMatchedDumpFilename(dumpFilename);
        log.setMatchSource(GcLogAnalysisEntity.MATCH_MANUAL);
        log.setMatchReason(dumpFilename == null ? "manual-unlink" : "manual");
        log.setMatchedAt(dumpFilename == null ? null : LocalDateTime.now());
        return gcLogRepository.save(log);
    }

    /** 힙 저장 이벤트 — 이 덤프의 서버와 같은 서버의 미확정 로그만 재평가(힙 분석 스레드에서 동기 실행 — 좁게). */
    @EventListener
    public void onHeapAnalysisSaved(HeapAnalysisSavedEvent ev) {
        try {
            Optional<AnalysisHistoryEntity> dump = historyRepository.findByFilename(ev.filename());
            if (dump.isEmpty()) return;
            AnalysisHistoryEntity h = dump.get();
            int n = 0;
            for (GcLogAnalysisEntity log : gcLogRepository.findByMatchSource(GcLogAnalysisEntity.MATCH_NONE)) {
                if (!sameServer(log.getServerId(), log.getServerName(), h.getServerId(), h.getServerName())
                        && log.getServerId() == null && log.getServerName() == null) continue; // 출처 없는 업로드 로그는 경로 신호도 없다
                if (!sameServer(log.getServerId(), log.getServerName(), h.getServerId(), h.getServerName())) continue;
                tryAutoMatch(log);
                n++;
            }
            if (n > 0) logger.info("[GcLog] heap-saved re-evaluated {} log(s) for dump={}", n, ev.filename());
        } catch (Exception e) {
            logger.warn("[GcLog] heap-saved hook failed dump={} — {}", ev.filename(), e.getMessage());
        }
    }

    // ── 조회 뷰 ──────────────────────────────────────────────────

    /** 덤프 기준 역방향 뷰 — 힙 analyze 페이지 칩/패널이 읽는다(항상 Map, null 없음). */
    public Map<String, Object> viewForDump(String dumpFilename) {
        Map<String, Object> v = new LinkedHashMap<>();
        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        try {
            for (GcLogAnalysisEntity l : gcLogRepository.findByMatchedDumpFilename(dumpFilename)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("filename", l.getFilename());
                m.put("source", l.getMatchSource());
                m.put("reason", l.getMatchReason());
                m.put("status", l.getStatus());
                m.put("collector", l.getCollector());
                m.put("severity", l.getSeverity());
                m.put("logStart", l.getLogStart() == null ? null : l.getLogStart().toString().replace('T', ' '));
                m.put("logEnd", l.getLogEnd() == null ? null : l.getLogEnd().toString().replace('T', ' '));
                m.put("fullGcCount", l.getFullGcCount());
                m.put("throughputPct", l.getThroughputPct());
                matched.add(m);
            }
            for (GcLogAnalysisEntity l : gcLogRepository.findByMatchSource(GcLogAnalysisEntity.MATCH_NONE)) {
                List<Candidate> cs = fromJson(l.getMatchCandidates());
                for (Candidate c : cs) {
                    if (dumpFilename.equals(c.dumpFilename())) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("filename", l.getFilename());
                        m.put("score", c.score());
                        m.put("reasons", c.reasons());
                        m.put("status", l.getStatus());
                        m.put("collector", l.getCollector());
                        candidates.add(m);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[GcLog] viewForDump failed dump={} — {}", dumpFilename, e.getMessage());
        }
        v.put("matched", matched);
        v.put("candidates", candidates);
        v.put("count", matched.size());
        v.put("candidateCount", candidates.size());
        v.put("hasMatch", !matched.isEmpty());
        return v;
    }

    /** 로그 기준 후보 목록(JSON 역직렬화). */
    public List<Candidate> candidatesOf(GcLogAnalysisEntity log) {
        return fromJson(log.getMatchCandidates());
    }

    // ── 사실 수집 ────────────────────────────────────────────────

    LogFacts logFacts(GcLogAnalysisEntity log) {
        String hdpDir = null;
        JvmHeapCapture.Capture cap = JvmHeapCapture.fromJson(log.getJvmInfo());
        if (cap != null && cap.matched() != null && cap.matched().heapDumpPath() != null) {
            String h = cap.matched().heapDumpPath();
            hdpDir = h.endsWith("/") ? h : (h.contains(".") ? JvmHeapCapture.dirOf(h) : h);
        }
        return new LogFacts(log.getServerId(), log.getServerName(), log.getRemotePath(),
                toEpoch(log.getLogStart()), toEpoch(log.getLogEnd()), log.getTimeSource(), hdpDir);
    }

    private List<DumpFacts> candidateDumps(LogFacts lf) {
        List<AnalysisHistoryEntity> pool;
        if (lf.serverId() != null) pool = historyRepository.findByServerIdOrderByAnalyzedAtDesc(lf.serverId());
        else pool = historyRepository.findAllByOrderByAnalyzedAtDesc();
        List<DumpFacts> out = new ArrayList<>();
        for (AnalysisHistoryEntity h : pool) {
            if (Boolean.TRUE.equals(h.getFileDeleted()) && !"SUCCESS".equals(h.getStatus())) continue;
            Long ts = JvmHeapInfoService.toEpoch(h.getDumpCreationTime());
            // 서버 id 가 없는 로그(업로드)는 이름·시간으로만 좁힌다 — 전체 이력을 다 보되 ±1일 창으로 제한
            if (lf.serverId() == null && lf.logStartEpochSec() != null && ts != null
                    && (ts < lf.logStartEpochSec() - CANDIDATE_WINDOW_SEC || ts > lf.logEndEpochSec() + CANDIDATE_WINDOW_SEC)) continue;
            String gcPath = null, cwd = null, remoteDir = null;
            JvmHeapCapture.Capture cap = JvmHeapCapture.fromJson(h.getJvmInfo());
            if (cap != null && cap.matched() != null) { gcPath = cap.matched().gcLogPath(); cwd = cap.matched().cwd(); }
            List<DumpTransferLog> tl = transferLogRepository.findByFilenameAndTransferStatusOrderByCompletedAtDesc(h.getFilename(), "SUCCESS");
            if (!tl.isEmpty() && tl.get(0).getRemotePath() != null) remoteDir = JvmHeapCapture.dirOf(tl.get(0).getRemotePath());
            out.add(new DumpFacts(h.getFilename(), h.getServerId(), h.getServerName(), ts, gcPath, cwd, remoteDir));
        }
        return out;
    }

    static Long toEpoch(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private String toJson(List<Candidate> cs) {
        try { return mapper.writeValueAsString(cs); } catch (RuntimeException e) { return "[]"; }
    }

    private List<Candidate> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, Candidate.class));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 수동 선택 모달용 — 최근 덤프 목록(+ 이 로그 기준 점수). */
    public List<Map<String, Object>> matchOptions(GcLogAnalysisEntity log, String q, int limit) {
        LogFacts lf = logFacts(log);
        List<AnalysisHistoryEntity> all = historyRepository.findAllByOrderByAnalyzedAtDesc();
        Map<String, Candidate> scored = new LinkedHashMap<>();
        for (Candidate c : fromJson(log.getMatchCandidates())) scored.put(c.dumpFilename(), c);
        List<Map<String, Object>> out = new ArrayList<>();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        for (AnalysisHistoryEntity h : all) {
            if (!needle.isEmpty() && !(h.getFilename().toLowerCase(Locale.ROOT).contains(needle)
                    || (h.getServerName() != null && h.getServerName().toLowerCase(Locale.ROOT).contains(needle)))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", h.getFilename());
            m.put("serverName", h.getServerName());
            m.put("dumpCreationTime", h.getDumpCreationTime());
            m.put("status", h.getStatus());
            m.put("fileDeleted", Boolean.TRUE.equals(h.getFileDeleted()));
            Candidate c = scored.get(h.getFilename());
            m.put("score", c == null ? 0 : c.score());
            m.put("reasons", c == null ? List.of() : c.reasons());
            m.put("sameServer", sameServer(lf.serverId(), lf.serverName(), h.getServerId(), h.getServerName()));
            out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 존재 확인 — 파일 기준 dumpfiles 디렉토리. */
    public boolean gcLogFileExists(String filename) {
        return new File(config.getGcLogDumpFilesDirectory(), filename).isFile();
    }
}

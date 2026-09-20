package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.dto.AlertBoardRow;
import com.heapdump.analyzer.model.dto.AlertDomainKpi;
import com.heapdump.analyzer.model.dto.AlertItem;
import com.heapdump.analyzer.model.dto.DetectKindSummary;
import com.heapdump.analyzer.model.entity.AlertAck;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AiInsightRepository;
import com.heapdump.analyzer.repository.AlertAckRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
import com.heapdump.analyzer.util.FileTypeRules;
import com.heapdump.analyzer.util.OomDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 알림 현황판 (2026-09-19) — 힙 덤프·코어덤프·GC 로그의 분석 현황과 최우선 알람(Critical·OOM).
 *
 * <p>세 도메인의 요약 테이블만 읽는다. 힙은 심각도·OOM 이 요약 컬럼에 없어 분석 결과 캐시
 * ({@code getCachedResult} — 기동 시 전량 복원)를 본다. ⚠ {@code HeapHistoryAggregator.aggregateDetections} 는
 * suspect 0건을 건너뛰므로 <b>OOM 만 있는 덤프를 놓친다</b> — 여기서 자체 루프를 도는 이유다.
 * ⚠ 힙 {@code analysis_history.file_deleted} 는 true 로 쓰이는 곳이 없는 죽은 컬럼이다 — 파일 실존으로 판정한다.
 * ⚠ GC 는 {@code history()} 만 쓴다({@code listExistingFiles()} 는 DB 에 쓴다).
 *
 * <p>기존 서비스는 이 서비스를 주입받지 않는다(순환 방지) — 의존은 이쪽에서 바깥으로만.
 */
@Service
public class AlertBoardService {

    private static final Logger logger = LoggerFactory.getLogger(AlertBoardService.class);

    public static final String DOMAIN_HEAP = "heap";
    public static final String DOMAIN_CORE = "core";
    public static final String DOMAIN_GCLOG = "gclog";

    public static final String CRITICAL = "Critical";
    public static final String HIGH = "High";
    public static final String MEDIUM = "Medium";
    public static final String LOW = "Low";

    static final String CORE_INSIGHT_PREFIX = "__core__:";
    /** 수집 스냅샷 유효 시간 — 여러 탭의 60초 폴링·페이지 진입이 겹쳐도 쿼리·디렉토리 스캔을 한 번으로 묶는다 */
    static final long SNAPSHOT_TTL_MS = 8_000L;
    static final int BOARD_ROWS_PER_DOMAIN = 50;
    static final int DASHBOARD_ITEMS = 12;
    static final int ORPHAN_ACK_RETENTION_DAYS = 30;
    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HeapDumpAnalyzerService analyzerService;
    private final AnalysisHistoryRepository heapRepository;
    private final CoreDumpAnalysisRepository coreRepository;
    private final GcLogAnalyzerService gcLogService;
    private final AiInsightRepository insightRepository;
    private final AlertAckRepository ackRepository;

    private volatile Snapshot snapshot;

    public AlertBoardService(HeapDumpAnalyzerService analyzerService,
                             AnalysisHistoryRepository heapRepository,
                             CoreDumpAnalysisRepository coreRepository,
                             GcLogAnalyzerService gcLogService,
                             AiInsightRepository insightRepository,
                             AlertAckRepository ackRepository) {
        this.analyzerService = analyzerService;
        this.heapRepository = heapRepository;
        this.coreRepository = coreRepository;
        this.gcLogService = gcLogService;
        this.insightRepository = insightRepository;
        this.ackRepository = ackRepository;
    }

    /** 한 번의 수집 결과 (삭제 파일 포함 — 요청마다 권한에 맞게 거른다). */
    static final class Snapshot {
        final long at = System.currentTimeMillis();
        final List<AlertItem> alerts = new ArrayList<>();
        final Map<String, List<AlertBoardRow>> rows = new LinkedHashMap<>();
        /** 한 도메인이라도 수집에 실패했는가 — 불완전한 목록으로 고아 ACK 를 판정하면 살아 있는 확인을 지운다 */
        boolean partial;
    }

    /** 현황판 페이지 모델. */
    public static final class Board {
        public final Map<String, AlertDomainKpi> kpi = new LinkedHashMap<>();
        public final Map<String, List<AlertBoardRow>> rows = new LinkedHashMap<>();
        public List<AlertItem> alerts = new ArrayList<>();
        public int unackedCount;
        /** 관리자에게 함께 보낸 삭제된 파일 행·알람 수 — 화면은 기본으로 숨기고 토글로 보인다 */
        public int deletedCount;
    }

    // ── 순수 함수 ─────────────────────────────────────────────

    /**
     * 심각도 어휘 정규화. ai_insights 에는 현행 {@code Critical|High|Medium|Low} 와 레거시
     * {@code critical|warning|info} 가 섞여 있다. 모르는 값은 null(판정 없음).
     */
    public static String normalizeSeverity(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase()) {
            case "critical": return CRITICAL;
            case "high": case "warning": return HIGH;
            case "medium": return MEDIUM;
            case "low": case "info": return LOW;
            default: return null;
        }
    }

    /** 크래시 시그널 → 심각도. 코어덤프 목록의 칩 색 규칙(chip-fatal / chip-warn)과 같은 분류다. */
    public static String coreSignalSeverity(String signal) {
        if (signal == null) return MEDIUM;
        String s = signal.trim().toUpperCase();
        if (s.startsWith("SIGSEGV") || s.startsWith("SIGBUS") || s.startsWith("SIGILL")) return CRITICAL;
        if (s.startsWith("SIGABRT") || s.startsWith("SIGFPE")) return HIGH;
        return MEDIUM;
    }

    /** 코어덤프 심각도 = AI 인사이트 우선, 없거나 알 수 없는 값이면 시그널 폴백. */
    public static String coreSeverity(String aiSeverityRaw, String signal) {
        String ai = normalizeSeverity(aiSeverityRaw);
        return ai != null ? ai : coreSignalSeverity(signal);
    }

    public static String alertKey(String domain, String filename, long epoch) {
        return domain + ":" + filename + ":" + epoch;
    }

    /** key 의 도메인 검증 — 임의 문자열이 ACK 테이블에 들어가지 않게. */
    public static String parseKeyDomain(String key) {
        if (key == null || key.isBlank() || key.length() > 560) {
            throw new IllegalArgumentException("알람 key 가 올바르지 않습니다.");
        }
        int i = key.indexOf(':');
        String d = i > 0 ? key.substring(0, i) : "";
        if (!DOMAIN_HEAP.equals(d) && !DOMAIN_CORE.equals(d) && !DOMAIN_GCLOG.equals(d)) {
            throw new IllegalArgumentException("알람 key 가 올바르지 않습니다.");
        }
        return d;
    }

    static int severityRank(String sev) {
        if (CRITICAL.equals(sev)) return 4;
        if (HIGH.equals(sev)) return 3;
        if (MEDIUM.equals(sev)) return 2;
        if (LOW.equals(sev)) return 1;
        return 0;
    }

    static int criticalSuspectCount(HeapAnalysisResult r) {
        int n = 0;
        if (r != null && r.getLeakSuspects() != null) {
            for (LeakSuspect s : r.getLeakSuspects()) {
                if (s != null && "critical".equalsIgnoreCase(s.getSeverity())) n++;
            }
        }
        return n;
    }

    /** 힙 덤프 파일 단위 심각도 — OOM 이 있으면 Critical, 아니면 suspect 최대 심각도(없으면 null). */
    public static String heapFileSeverity(HeapAnalysisResult r) {
        if (r == null) return null;
        if (r.getOomThreadCount() > 0) return CRITICAL;
        String max = null;
        if (r.getLeakSuspects() != null) {
            for (LeakSuspect s : r.getLeakSuspects()) {
                if (s == null) continue;
                // LeakSuspectAdvisor 는 비율 추출 실패 시 medium 을 준다 — 빈 값도 같은 기본값으로 본다
                String sev = normalizeSeverity(s.getSeverity());
                if (sev == null) sev = MEDIUM;
                if (severityRank(sev) > severityRank(max)) max = sev;
            }
        }
        return max;
    }

    static String oomLabel(HeapAnalysisResult r) {
        OomDetector.OomKind kind = OomDetector.classifyMessage(r.getOomFirstType());
        return kind == OomDetector.OomKind.UNKNOWN ? "OutOfMemoryError" : kind.koLabel();
    }

    /** 힙 알람: 분석 성공 + (OOM 스레드 탐지 또는 critical suspect). 제목은 OOM 우선. */
    public static AlertItem buildHeapAlert(AnalysisHistoryEntity e, HeapAnalysisResult r, boolean fileDeleted) {
        if (e == null || r == null || !"SUCCESS".equals(e.getStatus())) return null;
        int oom = r.getOomThreadCount();
        int crit = criticalSuspectCount(r);
        if (oom == 0 && crit == 0) return null;
        long epoch = epochOf(e.getAnalyzedAt());
        AlertItem a = base(DOMAIN_HEAP, e.getFilename(), epoch, "/analyze/result/", fileDeleted);
        a.setServerName(e.getServerName());
        if (oom > 0) {
            a.setReasonCode("HEAP_OOM");
            a.setTitle("OOM 탐지 — " + oomLabel(r) + " (스레드 " + oom + "개)");
            a.setDetail(crit > 0 ? "Critical 누수 의심 " + crit + "건 동반" : "OutOfMemoryError 가 발생한 스레드가 덤프에 있습니다");
        } else {
            a.setReasonCode("HEAP_CRITICAL_SUSPECT");
            a.setTitle("Critical 누수 의심 " + crit + "건");
            a.setDetail("힙의 절반 이상을 점유한 누수 의심 항목이 있습니다");
        }
        return a;
    }

    /**
     * 코어 알람: 분석 성공 + 심각도 Critical. AI 가 분석 뒤에 Critical 을 주는 경우가 있어
     * 발생 시각은 분석·AI 시각 중 늦은 쪽이다(최신 10건에 뜨게). key 는 분석 시각 기준이라 흔들리지 않는다.
     */
    public static AlertItem buildCoreAlert(CoreDumpAnalysisEntity e, String aiSeverityRaw, LocalDateTime aiAt) {
        if (e == null || !"SUCCESS".equals(e.getStatus())) return null;
        if (!CRITICAL.equals(coreSeverity(aiSeverityRaw, e.getCrashSignal()))) return null;
        boolean byAi = CRITICAL.equals(normalizeSeverity(aiSeverityRaw));
        long epoch = epochOf(e.getAnalyzedAt());
        AlertItem a = base(DOMAIN_CORE, e.getFilename(), epoch, "/core-dump/analyze/", Boolean.TRUE.equals(e.getFileDeleted()));
        a.setServerName(e.getExecutableName());   // 코어덤프에는 서버명이 없다 — 실행 파일명으로 대신한다
        a.setReasonCode(byAi ? "CORE_AI_CRITICAL" : "CORE_FATAL_SIGNAL");
        String signal = e.getCrashSignal() != null ? e.getCrashSignal() : "시그널 미상";
        a.setTitle("크래시 " + signal
                + (e.getSignalDescription() != null && !e.getSignalDescription().isBlank() ? " — " + e.getSignalDescription() : ""));
        a.setDetail(byAi ? "AI 분석이 Critical 로 판정했습니다" : "치명적 시그널로 프로세스가 종료됐습니다");
        long occurred = Math.max(epoch, epochOf(aiAt));
        a.setOccurredAtEpoch(occurred);
        a.setOccurredAtLabel(label(occurred));
        return a;
    }

    /** GC 알람: 분석 성공 + 종합 심각도 Critical. 메모리 압박 Full GC 가 있으면 제목에 싣는다. */
    public static AlertItem buildGcAlert(GcLogAnalysisEntity e) {
        if (e == null || !GcLogAnalysisEntity.STATUS_SUCCESS.equals(e.getStatus())) return null;
        if (!CRITICAL.equals(normalizeSeverity(e.getSeverity()))) return null;
        long epoch = epochOf(e.getAnalyzedAt());
        AlertItem a = base(DOMAIN_GCLOG, e.getFilename(), epoch, "/gc-log/analyze/", e.isFileDeleted());
        a.setServerName(e.getServerName());
        a.setReasonCode("GC_CRITICAL");
        int pressure = e.getPressureFullGcCount() != null ? e.getPressureFullGcCount() : 0;
        a.setTitle("GC 로그 Critical" + (pressure > 0 ? " — 메모리 압박 Full GC " + pressure + "회" : ""));
        a.setDetail("소견 " + (e.getFindingsCount() != null ? e.getFindingsCount() : 0) + "건");
        return a;
    }

    private static AlertItem base(String domain, String filename, long epoch, String linkPrefix, boolean fileDeleted) {
        AlertItem a = new AlertItem();
        a.setDomain(domain);
        a.setFilename(filename);
        a.setKey(alertKey(domain, filename, epoch));
        a.setSeverity(CRITICAL);
        a.setOccurredAtEpoch(epoch);
        a.setOccurredAtLabel(label(epoch));
        a.setFileDeleted(fileDeleted);
        a.setLink(fileDeleted ? null : link(linkPrefix, filename));
        return a;
    }

    static String link(String prefix, String filename) {
        return prefix + UriUtils.encodePathSegment(filename, StandardCharsets.UTF_8);
    }

    static long epochOf(LocalDateTime t) {
        return t == null ? 0L : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static String label(long epochMs) {
        if (epochMs <= 0) return "—";
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(LABEL_FMT);
    }

    /** 최신순 — 동률이면 key 순(결정적). */
    public static void sortNewest(List<AlertItem> items) {
        items.sort(Comparator.comparingLong(AlertItem::getOccurredAtEpoch).reversed()
                .thenComparing(AlertItem::getKey));
    }

    static AlertDomainKpi kpiOf(List<AlertBoardRow> rows) {
        AlertDomainKpi k = new AlertDomainKpi();
        for (AlertBoardRow r : rows) {
            k.setTotal(k.getTotal() + 1);
            switch (r.getStatus() == null ? "" : r.getStatus()) {
                case "SUCCESS": k.setSuccess(k.getSuccess() + 1); break;
                case "ERROR": k.setError(k.getError() + 1); break;
                case "ANALYZING": k.setAnalyzing(k.getAnalyzing() + 1); break;
                default: k.setNotAnalyzed(k.getNotAnalyzed() + 1);
            }
            if (CRITICAL.equals(r.getSeverity())) k.setCritical(k.getCritical() + 1);
            else if (HIGH.equals(r.getSeverity())) k.setHigh(k.getHigh() + 1);
            else if (MEDIUM.equals(r.getSeverity())) k.setMedium(k.getMedium() + 1);
            else if (LOW.equals(r.getSeverity())) k.setLow(k.getLow() + 1);
        }
        return k;
    }

    /** 대시보드 탐지 현황용 요약 — 심각도가 있는 성공 건만, Critical 우선·최신순 상위 N. */
    static DetectKindSummary summarize(List<AlertBoardRow> rows, int limit) {
        DetectKindSummary s = new DetectKindSummary();
        List<AlertBoardRow> hit = new ArrayList<>();
        for (AlertBoardRow r : rows) {
            if (r.isFileDeleted() || !"SUCCESS".equals(r.getStatus()) || r.getSeverity() == null) continue;
            hit.add(r);
            if (CRITICAL.equals(r.getSeverity())) s.setCritical(s.getCritical() + 1);
            else if (HIGH.equals(r.getSeverity())) s.setHigh(s.getHigh() + 1);
            else if (MEDIUM.equals(r.getSeverity())) s.setMedium(s.getMedium() + 1);
            else s.setLow(s.getLow() + 1);
        }
        hit.sort(Comparator.comparingInt((AlertBoardRow r) -> severityRank(r.getSeverity())).reversed()
                .thenComparing(Comparator.comparingLong(AlertBoardRow::getAnalyzedAtEpoch).reversed()));
        s.setItems(hit.size() > limit ? new ArrayList<>(hit.subList(0, limit)) : hit);
        return s;
    }

    // ── 수집 ─────────────────────────────────────────────────

    Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s != null && System.currentTimeMillis() - s.at < SNAPSHOT_TTL_MS) return s;
        s = collect();
        snapshot = s;
        return s;
    }

    void invalidate() { snapshot = null; }

    /** 도메인별로 격리 — 한 도메인의 조회 실패가 나머지 현황을 막지 않는다. */
    Snapshot collect() {
        Snapshot s = new Snapshot();
        s.rows.put(DOMAIN_HEAP, new ArrayList<>());
        s.rows.put(DOMAIN_CORE, new ArrayList<>());
        s.rows.put(DOMAIN_GCLOG, new ArrayList<>());
        try { collectHeap(s); } catch (Exception e) { s.partial = true; logger.warn("[Alert] 힙 덤프 현황 수집 실패: {}", e.toString()); }
        try { collectCore(s); } catch (Exception e) { s.partial = true; logger.warn("[Alert] 코어덤프 현황 수집 실패: {}", e.toString()); }
        try { collectGc(s); } catch (Exception e) { s.partial = true; logger.warn("[Alert] GC 로그 현황 수집 실패: {}", e.toString()); }
        sortNewest(s.alerts);
        return s;
    }

    private void collectHeap(Snapshot s) {
        List<AlertBoardRow> rows = s.rows.get(DOMAIN_HEAP);
        Map<String, HeapDumpFile> onDisk = new LinkedHashMap<>();
        for (HeapDumpFile f : analyzerService.listFiles()) onDisk.put(f.getName(), f);
        String analyzing = analyzerService.getCurrentAnalysisFilename();
        Set<String> seen = new HashSet<>();
        // Files 페이지 Others 탭(힙 덤프 확장자가 없고 라벨이 없거나 others)·core/exec 라벨은 힙 덤프 표·KPI 에서 뺀다.
        // 알람은 그대로 둔다 — MAT 분석이 성공해 OOM·Critical 이 나왔다면 이름과 무관하게 힙 덤프이고, 숨기면 진짜 OOM 을 놓친다
        boolean allowAll = analyzerService.isAllowAllExtensions();
        Map<String, String> classifications = allowAll ? analyzerService.loadFileClassifications() : Map.of();

        for (AnalysisHistoryEntity e : heapRepository.findAllByOrderByAnalyzedAtDesc()) {
            if (e.getFilename() == null || !seen.add(e.getFilename())) continue;
            boolean listed = isHeapDumpType(e.getFilename(), allowAll, classifications);
            boolean deleted = !onDisk.containsKey(e.getFilename());
            AlertBoardRow row = row(DOMAIN_HEAP, e.getFilename(), e.getServerName(), e.getStatus(),
                    epochOf(e.getAnalyzedAt()), "/analyze/result/", deleted);
            if (e.getFilename().equals(analyzing)) row.setStatus("ANALYZING");
            if ("SUCCESS".equals(row.getStatus())) {
                HeapAnalysisResult r = analyzerService.getCachedResult(e.getFilename());
                row.setSeverity(heapFileSeverity(r));
                if (r != null && r.getOomThreadCount() > 0) row.setKeySignal("OOM · " + oomLabel(r));
                else if (e.getSuspectCount() != null && e.getSuspectCount() > 0) row.setKeySignal("누수 의심 " + e.getSuspectCount() + "건");
                AlertItem a = buildHeapAlert(e, r, deleted);
                if (a != null) s.alerts.add(a);
            } else if ("ERROR".equals(row.getStatus())) {
                row.setKeySignal("분석 실패");
            }
            if (listed) rows.add(row);
        }
        // 분석 기록이 없는 디스크 파일 = 미분석
        for (HeapDumpFile f : onDisk.values()) {
            if (seen.contains(f.getName()) || !isHeapDumpType(f.getName(), allowAll, classifications)) continue;
            AlertBoardRow row = row(DOMAIN_HEAP, f.getName(), null,
                    f.getName().equals(analyzing) ? "ANALYZING" : "NOT_ANALYZED", 0L, "/analyze/result/", false);
            row.setLink(null);
            rows.add(row);
        }
    }

    private void collectCore(Snapshot s) {
        Map<String, AiInsightRepository.SeverityRow> insights = new HashMap<>();
        for (AiInsightRepository.SeverityRow r : insightRepository.findByFilenameStartingWith(CORE_INSIGHT_PREFIX)) {
            if (r.getFilename() != null) insights.put(r.getFilename().substring(CORE_INSIGHT_PREFIX.length()), r);
        }
        List<AlertBoardRow> rows = s.rows.get(DOMAIN_CORE);
        boolean allowAll = analyzerService.isAllowAllExtensions();
        Map<String, String> classifications = allowAll ? analyzerService.loadFileClassifications() : Map.of();
        for (CoreDumpAnalysisEntity e : coreRepository.findAllByOrderByCreatedAtDesc()) {
            // Files 페이지에서 Others 탭으로 가는 코어 저장소 파일은 표·KPI 에서 뺀다(알람은 유지 — collectHeap 과 같은 이유)
            boolean listed = !FileTypeRules.OTHERS.equals(FileTypeRules.coreDirLabel(e.getFilename(), allowAll, classifications));
            boolean deleted = Boolean.TRUE.equals(e.getFileDeleted());
            long at = epochOf(e.getAnalyzedAt() != null ? e.getAnalyzedAt() : e.getCreatedAt());
            AlertBoardRow row = row(DOMAIN_CORE, e.getFilename(), e.getExecutableName(), e.getStatus(), at,
                    "/core-dump/analyze/", deleted);
            if ("SUCCESS".equals(e.getStatus())) {
                AiInsightRepository.SeverityRow ai = insights.get(e.getFilename());
                row.setSeverity(coreSeverity(ai != null ? ai.getSeverity() : null, e.getCrashSignal()));
                row.setKeySignal(e.getCrashSignal());
                AlertItem a = buildCoreAlert(e, ai != null ? ai.getSeverity() : null, ai != null ? ai.getAnalysedAt() : null);
                if (a != null) s.alerts.add(a);
            } else if ("ERROR".equals(e.getStatus())) {
                row.setKeySignal("분석 실패");
            }
            if (listed) rows.add(row);
        }
    }

    private void collectGc(Snapshot s) {
        List<AlertBoardRow> rows = s.rows.get(DOMAIN_GCLOG);
        for (GcLogAnalysisEntity e : gcLogService.history()) {
            // 다른 분석의 구성 파일(이어 붙이기 멤버)은 그 자체로 분석 대상이 아니다 — 현황판에 '미분석' 으로 세면
            // 회전본 수만큼 건수가 부풀고 사용자가 같은 로그를 또 분석하게 된다(2026-09-20)
            if (e.getPartOf() != null) continue;
            long at = epochOf(e.getAnalyzedAt() != null ? e.getAnalyzedAt() : e.getCreatedAt());
            String status = gcLogService.isAnalyzing(e.getFilename()) ? "ANALYZING" : e.getStatus();
            AlertBoardRow row = row(DOMAIN_GCLOG, e.getFilename(), e.getServerName(), status, at,
                    "/gc-log/analyze/", e.isFileDeleted());
            if (GcLogAnalysisEntity.STATUS_SUCCESS.equals(status)) {
                row.setSeverity(normalizeSeverity(e.getSeverity()));
                int pressure = e.getPressureFullGcCount() != null ? e.getPressureFullGcCount() : 0;
                int full = e.getFullGcCount() != null ? e.getFullGcCount() : 0;
                if (pressure > 0) row.setKeySignal("압박 Full GC " + pressure + "회");
                else if (full > 0) row.setKeySignal("Full GC " + full + "회");
                AlertItem a = buildGcAlert(e);
                if (a != null) s.alerts.add(a);
            } else if (GcLogAnalysisEntity.STATUS_ERROR.equals(status)) {
                row.setKeySignal("분석 실패");
            }
            rows.add(row);
        }
    }

    /** 현황판 힙 덤프 표에 싣는가 — Files 페이지 Heap Dump 탭과 같은 판정(FileTypeRules 단일 출처). */
    static boolean isHeapDumpType(String filename, boolean allowAll, Map<String, String> classifications) {
        return FileTypeRules.HEAPDUMP.equals(FileTypeRules.heapDirType(filename, allowAll, classifications));
    }

    private static AlertBoardRow row(String domain, String filename, String server, String status, long epoch,
                                     String linkPrefix, boolean deleted) {
        AlertBoardRow r = new AlertBoardRow();
        r.setDomain(domain);
        r.setFilename(filename);
        r.setServerName(server);
        r.setStatus(status == null ? "NOT_ANALYZED" : status);
        r.setAnalyzedAtEpoch(epoch);
        r.setAnalyzedAtLabel(label(epoch));
        r.setFileDeleted(deleted);
        r.setLink(deleted || filename == null ? null : link(linkPrefix, filename));
        return r;
    }

    // ── 조회 ─────────────────────────────────────────────────

    /** 알람 전체(최신순) + ACK 상태. ACK 는 스냅샷 밖에서 매번 붙인다 — 다른 사용자의 확인이 바로 반영되게. */
    public List<AlertItem> alerts(boolean includeDeleted) {
        Map<String, AlertAck> acks = new HashMap<>();
        for (AlertAck a : ackRepository.findAll()) acks.put(a.getAlertKey(), a);
        List<AlertItem> out = new ArrayList<>();
        for (AlertItem src : snapshot().alerts) {
            if (src.isFileDeleted() && !includeDeleted) continue;
            AlertItem a = new AlertItem();
            BeanUtils.copyProperties(src, a);
            AlertAck ack = acks.get(a.getKey());
            if (ack != null) {
                a.setAcked(true);
                a.setAckedBy(ack.getAckedBy());
                a.setAckedAtLabel(ack.getAckedAt() != null ? ack.getAckedAt().format(LABEL_FMT) : null);
            }
            out.add(a);
        }
        return out;
    }

    /** 말풍선용 — 미확인·파일이 남아 있는 알람만. 삭제된 파일은 이미 조치된 건이라 관리자에게도 싣지 않는다. */
    public List<AlertItem> unacked() {
        return alerts(false).stream().filter(a -> !a.isAcked()).collect(Collectors.toList());
    }

    public Map<String, Object> top(int limit) {
        List<AlertItem> un = unacked();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("unackedCount", un.size());
        m.put("alerts", un.size() > limit ? un.subList(0, limit) : un);
        return m;
    }

    /**
     * 현황판 모델. KPI 는 누구에게나 <b>현존 파일만</b> 센다(대시보드와 같은 규약 — 삭제된 파일은 '현황' 이 아니다).
     * 삭제된 파일의 행·알람은 관리자에게만 보내고(서버 측 차단), 화면은 기본으로 숨긴 채 관리자 전용 토글로 보인다.
     * 최근 표는 현존 파일 최근 N건을 먼저 채우고 삭제분은 따로 N건까지 덧붙인다 — 숨겼을 때 현존 행이 N건 아래로 줄지 않게.
     */
    public Board board(boolean isAdmin) {
        Snapshot s = snapshot();
        Board b = new Board();
        Comparator<AlertBoardRow> newest = Comparator.comparingLong(AlertBoardRow::getAnalyzedAtEpoch).reversed();
        for (Map.Entry<String, List<AlertBoardRow>> en : s.rows.entrySet()) {
            List<AlertBoardRow> live = en.getValue().stream().filter(r -> !r.isFileDeleted()).sorted(newest)
                    .collect(Collectors.toList());
            b.kpi.put(en.getKey(), kpiOf(live));
            List<AlertBoardRow> out = new ArrayList<>(live.subList(0, Math.min(live.size(), BOARD_ROWS_PER_DOMAIN)));
            if (isAdmin) {
                List<AlertBoardRow> gone = en.getValue().stream().filter(AlertBoardRow::isFileDeleted).sorted(newest)
                        .limit(BOARD_ROWS_PER_DOMAIN).collect(Collectors.toList());
                b.deletedCount += gone.size();
                out.addAll(gone);
                out.sort(newest);
            }
            b.rows.put(en.getKey(), out);
        }
        b.alerts = alerts(isAdmin);
        b.deletedCount += (int) b.alerts.stream().filter(AlertItem::isFileDeleted).count();
        b.unackedCount = (int) b.alerts.stream().filter(a -> !a.isAcked() && !a.isFileDeleted()).count();
        return b;
    }

    /** 대시보드 탐지 현황 토글용 — 코어덤프·GC 로그 (힙은 기존 집계를 그대로 쓴다). */
    public Map<String, DetectKindSummary> dashboardKinds() {
        Snapshot s = snapshot();
        Map<String, DetectKindSummary> m = new LinkedHashMap<>();
        m.put(DOMAIN_CORE, summarize(s.rows.get(DOMAIN_CORE), DASHBOARD_ITEMS));
        m.put(DOMAIN_GCLOG, summarize(s.rows.get(DOMAIN_GCLOG), DASHBOARD_ITEMS));
        return m;
    }

    // ── ACK ──────────────────────────────────────────────────

    /** @return 새로 확인했으면 true, 이미 확인돼 있었으면 false (기존 확인자·시각 유지) */
    public boolean ack(String key, String who) {
        String domain = parseKeyDomain(key);
        AlertItem item = alerts(true).stream().filter(a -> key.equals(a.getKey())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("해당 알람을 찾을 수 없습니다. 새로고침 후 다시 시도하세요."));
        if (item.isAcked()) return false;
        AlertAck a = new AlertAck();
        a.setAlertKey(key);
        a.setDomain(domain);
        a.setFilename(item.getFilename());
        a.setAckedBy(who);
        a.setAckedAt(LocalDateTime.now());
        try {
            ackRepository.save(a);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 두 사용자가 동시에 확인 — unique 키가 한쪽을 거른다. 결과는 같으니 '이미 확인됨' 으로 본다
            logger.debug("[Alert] 동시 ACK 흡수 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    /** @return 삭제 전 확인자(없었으면 null) */
    public String unack(String key) {
        parseKeyDomain(key);
        String prev = ackRepository.findByAlertKey(key).map(AlertAck::getAckedBy).orElse(null);
        if (prev != null) ackRepository.deleteByAlertKey(key);
        return prev;
    }

    /**
     * 확인 정보(확인자·시각 라벨). 확인되지 않은 key 는 null.
     *
     * <p>ACK 직후 화면을 <b>서버가 정한 값</b>으로 갱신하라고 있는 것이다 — 이미 다른 사용자가 확인해 둔
     * 알람이면 그 사용자의 이름·시각이 돌아온다(누른 사람 이름으로 그리면 거짓이 된다). 종전에는 이걸
     * 알 방법이 없어 현황판이 확인 직후 페이지를 통째로 새로고침했다.
     */
    public Map<String, String> ackInfo(String key) {
        return ackRepository.findByAlertKey(key).map(AlertBoardService::ackView).orElse(null);
    }

    /** 현재 확인된 모든 알람의 key → 확인 정보 (전체 확인 직후 화면 갱신용). */
    public Map<String, Map<String, String>> ackInfoAll() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (AlertAck a : ackRepository.findAll()) out.put(a.getAlertKey(), ackView(a));
        return out;
    }

    private static Map<String, String> ackView(AlertAck a) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("by", a.getAckedBy() != null ? a.getAckedBy() : "");
        m.put("atLabel", a.getAckedAt() != null ? a.getAckedAt().format(LABEL_FMT) : "");
        return m;
    }

    public int ackAll(String who) {
        int n = 0;
        for (AlertItem a : unacked()) if (ack(a.getKey(), who)) n++;
        return n;
    }

    /** 이력 삭제·재분석으로 알람이 사라진 뒤 남은 ACK 행 정리 (30일 경과분만 — 잠시 사라졌다 돌아오는 알람의 확인을 지키려고). */
    @Scheduled(cron = "0 40 3 * * *")
    public void purgeOrphanAcks() {
        try {
            Snapshot now = collect();
            if (now.partial) return;
            Set<String> live = now.alerts.stream().map(AlertItem::getKey).collect(Collectors.toSet());
            LocalDateTime cutoff = LocalDateTime.now().minusDays(ORPHAN_ACK_RETENTION_DAYS);
            List<AlertAck> orphans = ackRepository.findAll().stream()
                    .filter(a -> !live.contains(a.getAlertKey()) && a.getAckedAt() != null && a.getAckedAt().isBefore(cutoff))
                    .collect(Collectors.toList());
            if (!orphans.isEmpty()) {
                ackRepository.deleteAll(orphans);
                logger.info("[Alert] action=purge-orphan-acks count={} by=system", orphans.size());
            }
        } catch (Exception e) {
            logger.warn("[Alert] 고아 ACK 정리 실패: {}", e.toString());
        }
    }
}

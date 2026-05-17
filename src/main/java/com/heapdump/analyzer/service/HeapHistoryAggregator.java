package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.HeapAnalysisResult;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.HistogramEntry;
import com.heapdump.analyzer.model.LeakSuspect;
import com.heapdump.analyzer.model.MemoryObject;
import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.ClassDiff;
import com.heapdump.analyzer.model.dto.DailyDetection;
import com.heapdump.analyzer.model.dto.DetectionAggregate;
import com.heapdump.analyzer.model.dto.DetectionRecentItem;
import com.heapdump.analyzer.model.dto.DetectionSummaryItem;
import com.heapdump.analyzer.model.dto.HistogramDiff;
import com.heapdump.analyzer.model.dto.KpiDiff;
import com.heapdump.analyzer.model.dto.ServerSeries;
import com.heapdump.analyzer.model.dto.SuspectDiff;
import com.heapdump.analyzer.model.entity.AiInsightEntity;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.repository.AiInsightRepository;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * History/Detections/Compare 빌더 공유 컴포넌트 (Phase 4B-2).
 *
 * 기존 {@code HeapDumpController} 잔존 헬퍼를 모두 이전하여
 * 6 개 도메인 컨트롤러와 {@code HeapDumpViewController} 가 공용으로 주입받아 사용.
 */
@Component
public class HeapHistoryAggregator {

    private static final Logger logger = LoggerFactory.getLogger(HeapHistoryAggregator.class);

    private final HeapDumpAnalyzerService analyzerService;

    public HeapHistoryAggregator(HeapDumpAnalyzerService analyzerService) {
        this.analyzerService = analyzerService;
    }

    public String truncateLog(String log, int maxLen) {
        if (log == null) return "";
        if (log.length() <= maxLen) return log;
        return log.substring(0, maxLen) + "\n\n[truncated — " + log.length() + " chars total]";
    }

    public String formatDuration(long ms) {
        if (ms <= 0) return "-";
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec = sec % 60;
        return min + "m " + sec + "s";
    }

    public List<AnalysisHistoryItem> buildHistory(List<HeapDumpFile> files) {
        List<AnalysisHistoryItem> history = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Set<String> processedNames = new HashSet<>();

        // 파일명 → HeapDumpFile 매핑
        Map<String, HeapDumpFile> fileMap = new HashMap<>();
        for (HeapDumpFile file : files) {
            fileMap.put(file.getName(), file);
        }

        // 1. DB 기반: 분석 이력이 있는 항목
        AnalysisHistoryRepository repo = analyzerService.getAnalysisHistoryRepository();
        List<AnalysisHistoryEntity> dbEntries = repo.findAllByOrderByAnalyzedAtDesc();
        for (AnalysisHistoryEntity e : dbEntries) {
            processedNames.add(e.getFilename());
            AnalysisHistoryItem item = new AnalysisHistoryItem();
            item.setId(e.getId());
            item.setFilename(e.getFilename());
            item.setStatus(e.getStatus());
            item.setSuspectCount(e.getSuspectCount() != null ? e.getSuspectCount() : 0);
            item.setAnalysisTime(e.getAnalysisTimeMs() != null ? e.getAnalysisTimeMs() : 0);
            item.setFormattedAnalysisTime(formatDuration(item.getAnalysisTime()));
            item.setHeapUsed(e.getUsedHeapSize() != null ? FormatUtils.formatBytes(e.getUsedHeapSize()) : "-");
            item.setHeapUsedBytes(e.getUsedHeapSize() != null ? e.getUsedHeapSize() : 0);
            item.setServerName(e.getServerName());
            item.setAnalyzedAtEpoch(e.getAnalyzedAt() != null
                    ? e.getAnalyzedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : 0);

            HeapDumpFile file = fileMap.get(e.getFilename());
            if (file != null) {
                item.setFileDeleted(false);
                item.setFormattedSize(file.getFormattedSize());
                item.setSizeBytes(file.getSize());
                item.setFormattedDate(sdf.format(new Date(file.getLastModified())));
                item.setLastModified(file.getLastModified());
                item.setCompressed(file.isCompressed());
                if (file.isCompressed()) {
                    item.setFormattedOriginalSize(file.getFormattedOriginalSize());
                    item.setFormattedCompressedSize(file.getFormattedCompressedSize());
                    item.setOriginalSizeBytes(file.getOriginalSize() > 0 ? file.getOriginalSize() : file.getSize());
                    item.setCompressedSizeBytes(file.getCompressedSize());
                } else {
                    item.setOriginalSizeBytes(file.getSize());
                    item.setCompressedSizeBytes(0);
                }
            } else {
                item.setFileDeleted(true);
                long fb = e.getFileSize() != null ? e.getFileSize() : 0;
                item.setFormattedSize(e.getFileSize() != null ? FormatUtils.formatBytes(e.getFileSize()) : "-");
                item.setSizeBytes(fb);
                item.setOriginalSizeBytes(e.getOriginalFileSize() != null ? e.getOriginalFileSize() : fb);
                item.setCompressedSizeBytes(0);
                item.setFormattedDate(e.getAnalyzedAt() != null
                        ? e.getAnalyzedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "-");
                item.setLastModified(e.getAnalyzedAt() != null
                        ? e.getAnalyzedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        : 0);
            }
            history.add(item);
        }

        // 2. 파일은 있지만 DB에 없는 항목 (memCache 기반 폴백 + 미분석)
        for (HeapDumpFile file : files) {
            if (processedNames.contains(file.getName())) continue;
            processedNames.add(file.getName());
            HeapAnalysisResult result = analyzerService.getCachedResult(file.getName());
            AnalysisHistoryItem item = new AnalysisHistoryItem();
            item.setFilename(file.getName());
            item.setFormattedSize(file.getFormattedSize());
            item.setSizeBytes(file.getSize());
            item.setFormattedDate(sdf.format(new Date(file.getLastModified())));
            item.setLastModified(file.getLastModified());
            item.setFileDeleted(false);
            item.setCompressed(file.isCompressed());
            if (file.isCompressed()) {
                item.setFormattedOriginalSize(file.getFormattedOriginalSize());
                item.setFormattedCompressedSize(file.getFormattedCompressedSize());
                item.setOriginalSizeBytes(file.getOriginalSize() > 0 ? file.getOriginalSize() : file.getSize());
                item.setCompressedSizeBytes(file.getCompressedSize());
            } else {
                item.setOriginalSizeBytes(file.getSize());
                item.setCompressedSizeBytes(0);
            }
            if (result != null) {
                item.setStatus(result.getAnalysisStatus().name());
                item.setSuspectCount(result.getLeakSuspects() != null
                        ? result.getLeakSuspects().size() : 0);
                item.setAnalysisTime(result.getAnalysisTime());
                item.setFormattedAnalysisTime(formatDuration(result.getAnalysisTime()));
                item.setHeapUsed(result.getFormattedUsedHeapSize());
            } else {
                item.setStatus("NOT_ANALYZED");
            }
            history.add(item);
        }

        // 3. memCache에는 있지만 DB/파일 모두 없는 항목 (기존 호환성)
        for (HeapAnalysisResult result : analyzerService.getAllCachedResults()) {
            if (result.getFilename() != null && !processedNames.contains(result.getFilename())) {
                AnalysisHistoryItem item = new AnalysisHistoryItem();
                item.setFilename(result.getFilename());
                item.setFormattedSize(FormatUtils.formatBytes(result.getFileSize()));
                item.setFormattedDate(sdf.format(new Date(result.getLastModified())));
                item.setLastModified(result.getLastModified());
                item.setFileDeleted(true);
                item.setStatus(result.getAnalysisStatus().name());
                item.setSuspectCount(result.getLeakSuspects() != null
                        ? result.getLeakSuspects().size() : 0);
                item.setAnalysisTime(result.getAnalysisTime());
                item.setFormattedAnalysisTime(formatDuration(result.getAnalysisTime()));
                item.setHeapUsed(result.getFormattedUsedHeapSize());
                history.add(item);
            }
        }

        // AI 인사이트 여부 일괄 설정
        try {
            AiInsightRepository aiRepo = analyzerService.getAiInsightRepository();
            for (AnalysisHistoryItem item : history) {
                Optional<AiInsightEntity> aiOpt = aiRepo.findByFilename(item.getFilename());
                if (aiOpt.isPresent()) {
                    item.setHasAiInsight(true);
                    item.setAiInsightSeverity(aiOpt.get().getSeverity());
                }
            }
        } catch (Exception e) {
            logger.warn("[Files] AI 인사이트 조회 실패: {}", e.getMessage());
        }

        // 분석명 일괄 설정 (DB/파일/캐시 분기 모두 커버)
        for (AnalysisHistoryItem item : history) {
            long ts = item.getAnalyzedAtEpoch() > 0 ? item.getAnalyzedAtEpoch() : item.getLastModified();
            item.setAnalysisName(buildAnalysisName(item.getFilename(), item.getServerName(), ts));
        }

        history.sort(Comparator.comparingLong(AnalysisHistoryItem::getLastModified).reversed());
        return history;
    }

    public Map<String, Long> buildClassSizeMap(HeapAnalysisResult result) {
        Map<String, Long> map = new HashMap<>();
        if (result.getTopMemoryObjects() != null) {
            for (MemoryObject obj : result.getTopMemoryObjects()) {
                map.put(obj.getClassName(), obj.getTotalSize());
            }
        }
        return map;
    }

    public List<ClassDiff> buildClassDiffs(HeapAnalysisResult base, HeapAnalysisResult target, int limit) {
        Map<String, Long> baseMap   = buildClassSizeMap(base);
        Map<String, Long> targetMap = buildClassSizeMap(target);
        Set<String> all = new HashSet<>();
        all.addAll(baseMap.keySet());
        all.addAll(targetMap.keySet());

        List<ClassDiff> diffs = new ArrayList<>();
        for (String cls : all) {
            long bs = baseMap.getOrDefault(cls, 0L);
            long ts = targetMap.getOrDefault(cls, 0L);
            long d  = ts - bs;
            if (d != 0) diffs.add(new ClassDiff(cls, bs, ts, d));
        }
        diffs.sort((a, b) -> Long.compare(Math.abs(b.getDelta()), Math.abs(a.getDelta())));
        if (limit > 0 && diffs.size() > limit) {
            return new ArrayList<>(diffs.subList(0, limit));
        }
        return diffs;
    }

    public List<HistogramDiff> buildHistogramDiffs(HeapAnalysisResult base, HeapAnalysisResult target, int limit) {
        Map<String, HistogramEntry> baseMap   = new HashMap<>();
        Map<String, HistogramEntry> targetMap = new HashMap<>();
        if (base.getHistogramEntries() != null) {
            for (HistogramEntry h : base.getHistogramEntries()) {
                if (h.getClassName() != null) baseMap.put(h.getClassName(), h);
            }
        }
        if (target.getHistogramEntries() != null) {
            for (HistogramEntry h : target.getHistogramEntries()) {
                if (h.getClassName() != null) targetMap.put(h.getClassName(), h);
            }
        }
        Set<String> all = new HashSet<>();
        all.addAll(baseMap.keySet());
        all.addAll(targetMap.keySet());

        List<HistogramDiff> out = new ArrayList<>();
        for (String cls : all) {
            HistogramEntry b = baseMap.get(cls);
            HistogramEntry t = targetMap.get(cls);
            long bc = b != null ? b.getObjectCount()  : 0L;
            long tc = t != null ? t.getObjectCount()  : 0L;
            long br = b != null ? b.getRetainedHeap() : 0L;
            long tr = t != null ? t.getRetainedHeap() : 0L;
            if (bc == tc && br == tr) continue;
            out.add(new HistogramDiff(cls, bc, tc, br, tr));
        }
        out.sort((a, b) -> Long.compare(Math.abs(b.getRetainedDelta()), Math.abs(a.getRetainedDelta())));
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public List<SuspectDiff> buildSuspectDiffs(HeapAnalysisResult base, HeapAnalysisResult target) {
        Map<String, LeakSuspect> baseMap   = new LinkedHashMap<>();
        Map<String, LeakSuspect> targetMap = new LinkedHashMap<>();
        if (base.getLeakSuspects() != null) {
            for (LeakSuspect s : base.getLeakSuspects()) {
                baseMap.put(normalizeSuspectKey(s.getTitle()), s);
            }
        }
        if (target.getLeakSuspects() != null) {
            for (LeakSuspect s : target.getLeakSuspects()) {
                targetMap.put(normalizeSuspectKey(s.getTitle()), s);
            }
        }
        List<SuspectDiff> out = new ArrayList<>();
        for (Map.Entry<String, LeakSuspect> e : targetMap.entrySet()) {
            String key = e.getKey();
            LeakSuspect t = e.getValue();
            LeakSuspect b = baseMap.get(key);
            String state;
            String baseSev = b != null ? b.getSeverity() : null;
            String targetSev = t.getSeverity();
            if (b == null) {
                state = "NEW";
            } else if (!eqNullSafe(baseSev, targetSev)) {
                state = "SEVERITY_CHANGED";
            } else {
                state = "PERSIST";
            }
            out.add(new SuspectDiff(key, state, t.getTitle(), t.getCategory(),
                    t.getDescription(), baseSev, targetSev));
        }
        for (Map.Entry<String, LeakSuspect> e : baseMap.entrySet()) {
            if (!targetMap.containsKey(e.getKey())) {
                LeakSuspect b = e.getValue();
                out.add(new SuspectDiff(e.getKey(), "GONE", b.getTitle(), b.getCategory(),
                        b.getDescription(), b.getSeverity(), null));
            }
        }
        out.sort(Comparator.comparingInt(d -> suspectStateOrder(d.getState())));
        return out;
    }

    public KpiDiff buildKpiDiff(HeapAnalysisResult base, HeapAnalysisResult target) {
        int baseSuspects   = base.getLeakSuspects()    != null ? base.getLeakSuspects().size()    : 0;
        int targetSuspects = target.getLeakSuspects()  != null ? target.getLeakSuspects().size()  : 0;
        int baseThreads    = base.getThreadInfos()     != null ? base.getThreadInfos().size()     : 0;
        int targetThreads  = target.getThreadInfos()   != null ? target.getThreadInfos().size()   : 0;
        int baseTop        = base.getTopMemoryObjects()   != null ? base.getTopMemoryObjects().size()   : 0;
        int targetTop      = target.getTopMemoryObjects() != null ? target.getTopMemoryObjects().size() : 0;

        return new KpiDiff(
            target.getUsedHeapSize()     - base.getUsedHeapSize(),
            target.getTotalHeapSize()    - base.getTotalHeapSize(),
            target.getFreeHeapSize()     - base.getFreeHeapSize(),
            target.getHeapUsagePercent() - base.getHeapUsagePercent(),
            target.getTotalObjects()     - base.getTotalObjects(),
            target.getTotalClasses()     - base.getTotalClasses(),
            targetSuspects - baseSuspects,
            targetThreads  - baseThreads,
            targetTop      - baseTop
        );
    }

    private static String normalizeSuspectKey(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
    private static boolean eqNullSafe(String a, String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }
    private static int suspectStateOrder(String s) {
        if ("SEVERITY_CHANGED".equals(s)) return 0;
        if ("NEW".equals(s))              return 1;
        if ("GONE".equals(s))             return 2;
        return 3;
    }

    public DetectionAggregate aggregateDetections(List<AnalysisHistoryItem> history, int days, int topN) {
        DetectionAggregate agg = new DetectionAggregate();
        if (days <= 0) days = 14;
        if (topN <= 0) topN = 12;

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate from = today.minusDays(days - 1L);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.format.DateTimeFormatter dateFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");

        final String UNKNOWN_SERVER = "Local";
        final String OTHERS_LABEL = "기타";

        Map<java.time.LocalDate, Map<String, Integer>> dailyServerBuckets = new HashMap<>();
        Map<java.time.LocalDate, int[]> dailySeverityBuckets = new HashMap<>();
        Map<java.time.LocalDate, Integer> dailyTotals = new HashMap<>();
        Map<String, Integer> serverTotals = new HashMap<>();

        int criticalCount = 0, highCount = 0, mediumCount = 0, lowCount = 0;
        List<DetectionSummaryItem> detectionItems = new ArrayList<>();
        List<DetectionRecentItem> recent = new ArrayList<>();

        for (AnalysisHistoryItem h : history) {
            if (!"SUCCESS".equals(h.getStatus()) || h.getSuspectCount() == 0) continue;
            HeapAnalysisResult r = analyzerService.getCachedResult(h.getFilename());
            if (r == null || r.getLeakSuspects() == null) continue;

            java.time.LocalDate day = null;
            boolean inPeriod = false;
            String dateLabelForRecent = null;
            if (h.getAnalyzedAtEpoch() > 0) {
                day = java.time.Instant.ofEpochMilli(h.getAnalyzedAtEpoch())
                        .atZone(zone).toLocalDate();
                inPeriod = !day.isBefore(from) && !day.isAfter(today);
                if (inPeriod) dateLabelForRecent = day.format(dateFmt);
            }
            String analysisNameCached = null;

            int fc = 0, fh = 0, fm = 0, fl = 0;
            for (LeakSuspect s : r.getLeakSuspects()) {
                String sev = s.getSeverity() != null ? s.getSeverity().toLowerCase() : "medium";
                switch (sev) {
                    case "critical": fc++; criticalCount++; break;
                    case "high":     fh++; highCount++; break;
                    case "low":      fl++; lowCount++; break;
                    default:         fm++; mediumCount++; break;
                }
                if (inPeriod) {
                    if (analysisNameCached == null) {
                        analysisNameCached = buildAnalysisName(h.getFilename(), h.getServerName(), h.getAnalyzedAtEpoch());
                    }
                    DetectionRecentItem ri = new DetectionRecentItem();
                    ri.setFilename(h.getFilename());
                    ri.setAnalysisName(analysisNameCached);
                    ri.setServerName(h.getServerName());
                    ri.setSeverity(sev);
                    String t = s.getTitle();
                    if (t == null || t.isEmpty()) {
                        t = (s.getCategory() != null && !s.getCategory().isEmpty()) ? s.getCategory() : "Suspect";
                    }
                    ri.setTitle(t);
                    ri.setCategory(s.getCategory());
                    ri.setAnalyzedAtEpoch(h.getAnalyzedAtEpoch());
                    ri.setDateLabel(dateLabelForRecent);
                    ri.setFileDeleted(h.isFileDeleted());
                    recent.add(ri);
                }
            }
            DetectionSummaryItem di = new DetectionSummaryItem();
            di.setFilename(h.getFilename());
            di.setSuspectCount(h.getSuspectCount());
            di.setCriticalCount(fc);
            di.setHighCount(fh);
            di.setMediumCount(fm);
            di.setLowCount(fl);
            di.setFileDeleted(h.isFileDeleted());
            detectionItems.add(di);

            if (inPeriod) {
                int suspectsForFile = fc + fh + fm + fl;
                String sname = h.getServerName();
                if (sname == null || sname.trim().isEmpty()) sname = UNKNOWN_SERVER;
                dailyServerBuckets.computeIfAbsent(day, k -> new HashMap<>())
                                  .merge(sname, suspectsForFile, Integer::sum);
                dailyTotals.merge(day, suspectsForFile, Integer::sum);
                serverTotals.merge(sname, suspectsForFile, Integer::sum);
                int[] sb = dailySeverityBuckets.computeIfAbsent(day, k -> new int[4]);
                sb[0] += fc; sb[1] += fh; sb[2] += fm; sb[3] += fl;
            }
        }

        recent.sort((a, b) -> {
            int sa = severityWeight(a.getSeverity());
            int sb = severityWeight(b.getSeverity());
            if (sa != sb) return Integer.compare(sb, sa);
            return Long.compare(b.getAnalyzedAtEpoch(), a.getAnalyzedAtEpoch());
        });
        if (recent.size() > 30) recent = new ArrayList<>(recent.subList(0, 30));

        List<Map.Entry<String, Integer>> sortedServers = new ArrayList<>(serverTotals.entrySet());
        sortedServers.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Set<String> topServerSet = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(topN, sortedServers.size()); i++) {
            topServerSet.add(sortedServers.get(i).getKey());
        }
        boolean hasOthers = sortedServers.size() > topN;

        Map<String, int[]> seriesCounts = new LinkedHashMap<>();
        for (String name : topServerSet) seriesCounts.put(name, new int[days]);
        if (hasOthers) seriesCounts.put(OTHERS_LABEL, new int[days]);

        int[] sevCritical = new int[days];
        int[] sevHigh     = new int[days];
        int[] sevMedium   = new int[days];
        int[] sevLow      = new int[days];

        int last7Start = days - 7;
        int prev7Start = Math.max(0, days - 14);
        int prev7End   = Math.max(0, days - 7);

        List<String> labels = new ArrayList<>(days);
        List<DailyDetection> dailyDetections = new ArrayList<>(days);
        int total = 0, last7d = 0, prev7d = 0, peakCount = 0;
        java.time.LocalDate peakDay = null;

        for (int i = 0; i < days; i++) {
            java.time.LocalDate d = from.plusDays(i);
            String dStr = d.format(dateFmt);
            labels.add(dStr);
            int dayTotal = dailyTotals.getOrDefault(d, 0);
            DailyDetection dd = new DailyDetection();
            dd.setDate(dStr);
            dd.setTotal(dayTotal);
            dailyDetections.add(dd);
            total += dayTotal;
            if (i >= last7Start) last7d += dayTotal;
            else if (i >= prev7Start && i < prev7End) prev7d += dayTotal;
            if (dayTotal > peakCount) { peakCount = dayTotal; peakDay = d; }

            Map<String, Integer> serverMap = dailyServerBuckets.get(d);
            if (serverMap != null) {
                for (Map.Entry<String, Integer> e : serverMap.entrySet()) {
                    String key = topServerSet.contains(e.getKey()) ? e.getKey() : OTHERS_LABEL;
                    int[] arr = seriesCounts.get(key);
                    if (arr != null) arr[i] += e.getValue();
                }
            }
            int[] sevBuckets = dailySeverityBuckets.get(d);
            if (sevBuckets != null) {
                sevCritical[i] = sevBuckets[0];
                sevHigh[i]     = sevBuckets[1];
                sevMedium[i]   = sevBuckets[2];
                sevLow[i]      = sevBuckets[3];
            }
        }

        List<ServerSeries> serverSeries = new ArrayList<>();
        for (Map.Entry<String, int[]> e : seriesCounts.entrySet()) {
            ServerSeries ss = new ServerSeries();
            ss.setName(e.getKey());
            ss.setCounts(e.getValue());
            serverSeries.add(ss);
        }

        List<ServerSeries> severitySeries = new ArrayList<>();
        ServerSeries sc = new ServerSeries(); sc.setName("critical"); sc.setCounts(sevCritical); severitySeries.add(sc);
        ServerSeries sh = new ServerSeries(); sh.setName("high");     sh.setCounts(sevHigh);     severitySeries.add(sh);
        ServerSeries sm = new ServerSeries(); sm.setName("medium");   sm.setCounts(sevMedium);   severitySeries.add(sm);
        ServerSeries sl = new ServerSeries(); sl.setName("low");      sl.setCounts(sevLow);      severitySeries.add(sl);

        Integer delta7d = null;
        if (prev7d > 0) {
            delta7d = (int) Math.round((last7d - prev7d) * 100.0 / prev7d);
        }

        agg.setLabels(labels);
        agg.setServerSeries(serverSeries);
        agg.setSeveritySeries(severitySeries);
        agg.setDailyDetections(dailyDetections);
        agg.setDetectionItems(detectionItems);
        agg.setCriticalCount(criticalCount);
        agg.setHighCount(highCount);
        agg.setMediumCount(mediumCount);
        agg.setLowCount(lowCount);
        agg.setTotal(total);
        agg.setLast7d(last7d);
        agg.setPrev7d(prev7d);
        agg.setDelta7d(delta7d);
        agg.setPeakDay(peakDay != null ? peakDay.format(dateFmt) : null);
        agg.setPeakCount(peakCount);
        agg.setRecent(recent);
        return agg;
    }

    private static int severityWeight(String s) {
        if (s == null) return 0;
        switch (s) {
            case "critical": return 4;
            case "high":     return 3;
            case "medium":   return 2;
            case "low":      return 1;
            default:         return 0;
        }
    }

    /**
     * 분석명 포맷: [{EXT}]{servername_lower|local}_{filename}_{yyyyMMdd}
     * .gz 접미사는 벗기고 안쪽 확장자 사용. 확장자 없거나 인식 불가 시 [DUMP].
     */
    public String buildAnalysisName(String filename, String serverName, long analyzedAtEpoch) {
        if (filename == null) filename = "";
        String lower = filename.toLowerCase();
        String stripped = lower.endsWith(".gz") ? lower.substring(0, lower.length() - 3) : lower;
        int dotIdx = stripped.lastIndexOf('.');
        String extUpper = (dotIdx == -1 || dotIdx == stripped.length() - 1)
                ? "DUMP"
                : stripped.substring(dotIdx + 1).toUpperCase();
        String server = (serverName == null || serverName.trim().isEmpty())
                ? "local"
                : serverName.toLowerCase();
        java.time.format.DateTimeFormatter ymdFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        String dateStr = (analyzedAtEpoch > 0)
                ? java.time.Instant.ofEpochMilli(analyzedAtEpoch)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(ymdFmt)
                : java.time.LocalDate.now().format(ymdFmt);
        return "[" + extUpper + "]" + server + "_" + filename + "_" + dateStr;
    }
}

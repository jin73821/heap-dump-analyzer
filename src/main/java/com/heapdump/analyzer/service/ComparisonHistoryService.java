package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.dto.KpiDiff;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.ComparisonHistoryEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.ComparisonHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ComparisonHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ComparisonHistoryService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ComparisonHistoryRepository repo;
    private final AnalysisHistoryRepository analysisRepo;

    public ComparisonHistoryService(ComparisonHistoryRepository repo,
                                    AnalysisHistoryRepository analysisRepo) {
        this.repo = repo;
        this.analysisRepo = analysisRepo;
    }

    /**
     * (사용자, base, target) 단위로 1건만 유지. 같은 조합 재진입 시 기존 row 의 KPI/시각만 갱신
     * (새 row 생성 안 함). AI 인사이트 재분석이나 단순 재방문이 누적되지 않도록 idempotent 보장.
     */
    @Transactional
    public void recordComparison(String baseFilename, String targetFilename, KpiDiff kpi,
                                 int baseSuspectCount, int targetSuspectCount,
                                 String username) {
        if (baseFilename == null || targetFilename == null || kpi == null) return;
        String user = (username == null || username.isEmpty()) ? "unknown" : username;

        Optional<ComparisonHistoryEntity> existing = repo
                .findFirstByComparedByAndBaseFilenameAndTargetFilenameOrderByComparedAtDesc(
                        user, baseFilename, targetFilename);

        ComparisonHistoryEntity e = existing.orElseGet(ComparisonHistoryEntity::new);
        boolean isNew = !existing.isPresent();

        e.setBaseFilename(baseFilename);
        e.setTargetFilename(targetFilename);
        e.setUsedHeapDelta(kpi.getUsedHeapDelta());
        e.setObjectsDelta(kpi.getObjectsDelta());
        e.setClassesDelta(kpi.getClassesDelta());
        e.setSuspectsDelta(kpi.getSuspectsDelta());
        e.setThreadsDelta(kpi.getThreadsDelta());
        e.setUsagePercentDelta(kpi.getUsagePercentDelta());
        e.setBaseSuspectCount(baseSuspectCount);
        e.setTargetSuspectCount(targetSuspectCount);
        e.setComparedBy(user);
        if (!isNew) e.setComparedAt(LocalDateTime.now());
        repo.save(e);
        logger.info("[CompareHistory] {} base={} target={} by={} usedHeapΔ={}",
                isNew ? "Created" : "Refreshed", baseFilename, targetFilename, user, kpi.getUsedHeapDelta());
    }

    @Transactional(readOnly = true)
    public List<ComparisonHistoryItem> list(boolean isAdmin) {
        List<ComparisonHistoryEntity> all = repo.findAllByOrderByComparedAtDesc();
        List<ComparisonHistoryItem> out = new ArrayList<>(all.size());
        for (ComparisonHistoryEntity e : all) {
            boolean baseDeleted   = isSourceMissing(e.getBaseFilename());
            boolean targetDeleted = isSourceMissing(e.getTargetFilename());
            if (!isAdmin && (baseDeleted || targetDeleted)) continue;
            out.add(toItem(e, baseDeleted, targetDeleted));
        }
        return out;
    }

    public enum DeleteResult { DELETED, NOT_FOUND, FORBIDDEN }

    /** 본인 소유 항목만 삭제. isAdmin=true 면 소유자 무관 삭제. */
    @Transactional
    public DeleteResult deleteOne(Long id, String username, boolean isAdmin) {
        if (id == null) return DeleteResult.NOT_FOUND;
        Optional<ComparisonHistoryEntity> opt = repo.findById(id);
        if (!opt.isPresent()) return DeleteResult.NOT_FOUND;
        ComparisonHistoryEntity e = opt.get();
        if (!isAdmin && !isOwner(e, username)) return DeleteResult.FORBIDDEN;
        repo.deleteById(id);
        logger.info("[CompareHistory] Delete id={} by={} admin={} owner={}",
                id, username, isAdmin, e.getComparedBy());
        return DeleteResult.DELETED;
    }

    /** 본인 소유 항목만 일괄 삭제. isAdmin=true 면 소유자 무관. 결과는 deleted/skipped 카운트. */
    @Transactional
    public BulkDeleteResult bulkDelete(List<Long> ids, String username, boolean isAdmin) {
        BulkDeleteResult r = new BulkDeleteResult();
        if (ids == null || ids.isEmpty()) return r;
        List<Long> allowed = new ArrayList<>();
        for (ComparisonHistoryEntity e : repo.findAllById(ids)) {
            if (isAdmin || isOwner(e, username)) allowed.add(e.getId());
            else r.skipped++;
        }
        // ids 중 DB에 존재하지 않는 항목은 skipped 로 집계하지 않음 (이미 사라진 상태).
        if (!allowed.isEmpty()) r.deleted = repo.deleteByIdIn(allowed);
        logger.info("[CompareHistory] Bulk delete by={} admin={} deleted={} skipped={}",
                username, isAdmin, r.deleted, r.skipped);
        return r;
    }

    private static boolean isOwner(ComparisonHistoryEntity e, String username) {
        if (username == null || username.isEmpty()) return false;
        String by = e.getComparedBy();
        return by != null && !by.isEmpty() && by.equals(username);
    }

    public static class BulkDeleteResult {
        public int deleted;
        public int skipped;
        public int getDeleted() { return deleted; }
        public int getSkipped() { return skipped; }
    }

    private boolean isSourceMissing(String filename) {
        if (filename == null || filename.isEmpty()) return true;
        Optional<AnalysisHistoryEntity> h = analysisRepo.findByFilename(filename);
        if (!h.isPresent()) return true;
        Boolean deleted = h.get().getFileDeleted();
        return Boolean.TRUE.equals(deleted);
    }

    private ComparisonHistoryItem toItem(ComparisonHistoryEntity e, boolean baseDeleted, boolean targetDeleted) {
        ComparisonHistoryItem it = new ComparisonHistoryItem();
        it.id = e.getId();
        it.baseFilename = e.getBaseFilename();
        it.targetFilename = e.getTargetFilename();
        it.baseDeleted = baseDeleted;
        it.targetDeleted = targetDeleted;
        it.usedHeapDelta = nz(e.getUsedHeapDelta());
        it.objectsDelta = nz(e.getObjectsDelta());
        it.classesDelta = nzI(e.getClassesDelta());
        it.suspectsDelta = nzI(e.getSuspectsDelta());
        it.threadsDelta = nzI(e.getThreadsDelta());
        it.usagePercentDelta = e.getUsagePercentDelta() == null ? 0.0 : e.getUsagePercentDelta();
        it.baseSuspectCount = nzI(e.getBaseSuspectCount());
        it.targetSuspectCount = nzI(e.getTargetSuspectCount());
        it.comparedBy = e.getComparedBy() == null ? "" : e.getComparedBy();
        it.comparedAt = e.getComparedAt() == null ? 0L
                : e.getComparedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        it.formattedComparedAt = e.getComparedAt() == null ? "—" : e.getComparedAt().format(DT_FMT);
        it.formattedUsedHeapDelta    = formatBytesDelta(it.usedHeapDelta);
        it.formattedObjectsDelta     = formatSignedLong(it.objectsDelta);
        it.formattedClassesDelta     = formatSignedInt(it.classesDelta);
        it.formattedSuspectsDelta    = formatSignedInt(it.suspectsDelta);
        it.formattedThreadsDelta     = formatSignedInt(it.threadsDelta);
        it.formattedUsagePercentDelta = formatSignedPct(it.usagePercentDelta);
        it.runnable = !baseDeleted && !targetDeleted;
        return it;
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
    private static int  nzI(Integer v) { return v == null ? 0 : v; }

    private static String formatBytesDelta(long bytes) {
        String sign = bytes > 0 ? "+" : (bytes < 0 ? "-" : "");
        long abs = Math.abs(bytes);
        if (abs < 1024) return sign + abs + " B";
        if (abs < 1048576L) return sign + String.format("%.1f KB", abs / 1024.0);
        if (abs < 1073741824L) return sign + String.format("%.2f MB", abs / (1024.0 * 1024));
        return sign + String.format("%.2f GB", abs / (1024.0 * 1024 * 1024));
    }
    private static String formatSignedLong(long v) { return (v > 0 ? "+" : "") + String.format("%,d", v); }
    private static String formatSignedInt(int v)   { return (v > 0 ? "+" : "") + String.format("%,d", v); }
    private static String formatSignedPct(double v){ return (v > 0 ? "+" : "") + String.format("%.2f%%", v); }

    public static class ComparisonHistoryItem {
        private Long id;
        private String baseFilename;
        private String targetFilename;
        private boolean baseDeleted;
        private boolean targetDeleted;
        private long usedHeapDelta;
        private long objectsDelta;
        private int  classesDelta;
        private int  suspectsDelta;
        private int  threadsDelta;
        private double usagePercentDelta;
        private int baseSuspectCount;
        private int targetSuspectCount;
        private String comparedBy;
        private long comparedAt;
        private String formattedComparedAt;
        private String formattedUsedHeapDelta;
        private String formattedObjectsDelta;
        private String formattedClassesDelta;
        private String formattedSuspectsDelta;
        private String formattedThreadsDelta;
        private String formattedUsagePercentDelta;
        private boolean runnable;

        public Long getId() { return id; }
        public String getBaseFilename() { return baseFilename; }
        public String getTargetFilename() { return targetFilename; }
        public boolean isBaseDeleted() { return baseDeleted; }
        public boolean isTargetDeleted() { return targetDeleted; }
        public long getUsedHeapDelta() { return usedHeapDelta; }
        public long getObjectsDelta() { return objectsDelta; }
        public int  getClassesDelta() { return classesDelta; }
        public int  getSuspectsDelta() { return suspectsDelta; }
        public int  getThreadsDelta() { return threadsDelta; }
        public double getUsagePercentDelta() { return usagePercentDelta; }
        public int getBaseSuspectCount() { return baseSuspectCount; }
        public int getTargetSuspectCount() { return targetSuspectCount; }
        public String getComparedBy() { return comparedBy; }
        public long getComparedAt() { return comparedAt; }
        public String getFormattedComparedAt() { return formattedComparedAt; }
        public String getFormattedUsedHeapDelta() { return formattedUsedHeapDelta; }
        public String getFormattedObjectsDelta() { return formattedObjectsDelta; }
        public String getFormattedClassesDelta() { return formattedClassesDelta; }
        public String getFormattedSuspectsDelta() { return formattedSuspectsDelta; }
        public String getFormattedThreadsDelta() { return formattedThreadsDelta; }
        public String getFormattedUsagePercentDelta() { return formattedUsagePercentDelta; }
        public boolean isRunnable() { return runnable; }
        public boolean isUsedHeapUp() { return usedHeapDelta > 0; }
        public boolean isUsedHeapDown() { return usedHeapDelta < 0; }
        public boolean isSuspectsUp() { return suspectsDelta > 0; }
        public boolean isSuspectsDown() { return suspectsDelta < 0; }
        public boolean isObjectsUp() { return objectsDelta > 0; }
        public boolean isObjectsDown() { return objectsDelta < 0; }
        public boolean isClassesUp() { return classesDelta > 0; }
        public boolean isClassesDown() { return classesDelta < 0; }
    }
}

package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.model.entity.LlmGuardLog;
import com.heapdump.analyzer.repository.LlmGuardLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 탐지 이력 기록·조회·보관 (LLM Guard, 2026-11-26).
 *
 * <p>⚠ 원문은 저장하지 않는다 — 행에 들어가는 {@code sample} 은 이미 가린 발췌다.
 * 기록 실패는 흐름을 막지 않는다(warn). <b>이력 삭제 API 는 없다</b> — 정리는 {@link #purgeExpired()} 하나뿐이고,
 * 보관일수는 정책 하한 {@value #MIN_RETENTION_DAYS}일 아래로 내려가지 않는다.
 */
@Service
public class LlmGuardLogService {

    private static final Logger logger = LoggerFactory.getLogger(LlmGuardLogService.class);

    public static final int MIN_RETENTION_DAYS = 365;

    private final LlmGuardLogRepository repository;

    @Value("${llm.guard.log.retention-days:365}")
    private int retentionDays = MIN_RETENTION_DAYS;

    public LlmGuardLogService(LlmGuardLogRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        if (retentionDays < MIN_RETENTION_DAYS) {
            logger.warn("[LlmGuard] llm.guard.log.retention-days={} 는 정책 하한 미만 — {}일로 올려 적용합니다",
                    retentionDays, MIN_RETENTION_DAYS);
        }
    }

    /** 실제 적용 보관일수 — 설정값이 하한 미만이면 하한. */
    public int effectiveRetentionDays() {
        return Math.max(MIN_RETENTION_DAYS, retentionDays);
    }

    void setRetentionDaysForTest(int days) { this.retentionDays = days; }

    /** 기록 — 실패해도 예외를 던지지 않는다. */
    public boolean write(List<LlmGuardLog> rows) {
        if (rows == null || rows.isEmpty() || repository == null) return false;
        try {
            repository.saveAll(rows);
            return true;
        } catch (RuntimeException e) {
            logger.warn("[LlmGuard] 탐지 이력 기록 실패 rows={} — 흐름은 계속: {}", rows.size(), e.toString());
            return false;
        }
    }

    /** 매일 03:50 보관기간(최소 1년) 초과 이력 정리. */
    @Scheduled(cron = "${llm.guard.log.purge-cron:0 50 3 * * *}")
    public int purgeExpired() {
        LocalDateTime cutoff = cutoff(LocalDateTime.now());
        try {
            int deleted = repository.deleteOlderThan(cutoff);
            logger.info("[LlmGuard] action=purge-log deleted={} cutoff={} retentionDays={} by=system",
                    deleted, cutoff, effectiveRetentionDays());
            return deleted;
        } catch (RuntimeException e) {
            logger.warn("[LlmGuard] 탐지 이력 정리 실패 — 다음 주기에 재시도: {}", e.toString());
            return 0;
        }
    }

    LocalDateTime cutoff(LocalDateTime now) {
        return now.minusDays(effectiveRetentionDays());
    }

    // ── 조회 ─────────────────────────────────────────────────────

    /** 검색 조건. 모두 선택. */
    public static final class Query {
        public LocalDate from;
        public LocalDate to;
        public String user;
        public String direction;
        public String action;
        public Long ruleId;
    }

    public Specification<LlmGuardLog> spec(Query q) {
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q.from != null) ps.add(cb.greaterThanOrEqualTo(root.get("detectedAt"), q.from.atStartOfDay()));
            if (q.to != null) ps.add(cb.lessThan(root.get("detectedAt"), q.to.plusDays(1).atStartOfDay()));
            if (q.user != null && !q.user.isBlank()) {
                String like = "%" + q.user.trim().toLowerCase().replace("%", "").replace("_", "") + "%";
                ps.add(cb.like(cb.lower(root.get("username")), like));
            }
            if (q.direction != null && !q.direction.isBlank()) ps.add(cb.equal(root.get("direction"), q.direction));
            if (q.action != null && !q.action.isBlank()) ps.add(cb.equal(root.get("action"), q.action));
            if (q.ruleId != null) ps.add(cb.equal(root.get("ruleId"), q.ruleId));
            return ps.isEmpty() ? null : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    public Page<LlmGuardLog> search(Query q, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "detectedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return repository.findAll(spec(q), pageable);
    }

    public static final int EXPORT_MAX_ROWS = 50_000;

    public List<LlmGuardLog> exportRows(Query q) {
        Pageable pageable = PageRequest.of(0, EXPORT_MAX_ROWS,
                Sort.by(Sort.Direction.DESC, "detectedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return repository.findAll(spec(q), pageable).getContent();
    }

    /** 최근 n일 통계 — 조치별 이벤트 수 + 규칙별 일치 수 상위 10. */
    public Map<String, Object> stats(int days) {
        int d = Math.min(Math.max(days, 1), 365);
        LocalDateTime from = LocalDate.now().minusDays(d - 1L).atStartOfDay();
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Long> events = new LinkedHashMap<>();
        events.put(LlmGuardLog.ACT_CONFIRM_REQUESTED, 0L);
        events.put(LlmGuardLog.ACT_CONFIRMED, 0L);
        events.put(LlmGuardLog.ACT_BLOCKED, 0L);
        events.put(LlmGuardLog.ACT_MASKED, 0L);
        for (Object[] row : repository.countEventsByActionSince(from)) {
            events.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> top = new ArrayList<>();
        for (Object[] row : repository.sumByRuleSince(from)) {
            if (top.size() >= 10) break;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("rule", row[0]);
            it.put("matches", row[1] != null ? ((Number) row[1]).longValue() : 0L);
            top.add(it);
        }
        m.put("days", d);
        m.put("events", events);
        m.put("topRules", top);
        m.put("retentionDays", effectiveRetentionDays());
        return m;
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.dto.MemoHistoryItem;
import com.heapdump.analyzer.model.entity.MemoHistory;
import com.heapdump.analyzer.repository.MemoHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 개인 메모 변경 이력 (2026-08-12).
 *
 * <p>{@code users.memo} 는 덮어쓰기 단일 컬럼이라 이전 내용을 되돌릴 수 없었다.
 * 여기서 <b>덮이기 직전 스냅샷</b>을 남겨 서버 측 복원을 가능하게 한다.
 *
 * <p><b>보관량 통제가 이 기능의 급소다.</b> 메모장 자동 저장은 5초 debounce 라 타이핑을
 * 계속하면 분당 10회 이상 저장될 수 있고, 컬럼은 최대 10MB 다. 아무 제약 없이 매 저장마다
 * 행을 만들면 한 사용자가 며칠 만에 수 GB 를 만든다. 그래서 3중으로 막는다:
 *
 * <ol>
 *   <li><b>최소 간격</b>({@code min-interval-seconds}) — 직전 스냅샷 이후 이 시간 안의 저장은
 *       기록하지 않는다. 다만 복원/초기화처럼 되돌리기 수요가 확실한 시점은 예외로 항상 남긴다.</li>
 *   <li><b>사용자당 상한</b>({@code max-per-user}) — 초과분은 오래된 것부터 즉시 삭제.</li>
 *   <li><b>보관 기간</b>({@code retention-days}, 기본 7일) — 매일 새벽 정리.</li>
 * </ol>
 *
 * <p>추가로 <b>직전 스냅샷과 내용이 같으면 기록하지 않고</b>, <b>빈 내용도 기록하지 않는다</b>
 * (복원 대상으로서 가치가 없고 목록만 어지럽힌다).
 *
 * <p>⚠ 이 클래스는 {@link UserService} 를 참조하지 않는다 — 순환 의존을 피하기 위해
 * 복원 실행은 {@code UserService.restoreMemo()} 가 담당하고 여기서는 조회만 제공한다.
 */
@Service
public class MemoHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoHistoryService.class);

    public static final String REASON_SAVE    = "save";
    public static final String REASON_RESTORE = "restore";
    public static final String REASON_CLEAR   = "clear";

    /** 목록 미리보기 길이 — 쿼리에서 자르는 길이(160)보다 짧게 잡아 정규화 여유를 둔다. */
    private static final int PREVIEW_CHARS = 120;

    private final MemoHistoryRepository repository;

    @Value("${memo.history.enabled:true}")
    private boolean enabled;
    @Value("${memo.history.retention-days:7}")
    private int retentionDays;
    @Value("${memo.history.max-per-user:100}")
    private int maxPerUser;
    @Value("${memo.history.min-interval-seconds:60}")
    private int minIntervalSeconds;

    public MemoHistoryService(MemoHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 기동 시 이력 테이블 가용성 확인 (SpringSessionTableChecker 와 같은 성격).
     * {@code ddl-auto=update} 라 보통은 자동 생성되지만, 실패하면 사용자가 "변경 이력" 을
     * 눌러야 알게 된다 — 그 전에 로그로 드러내 둔다. 기동을 막지는 않는다.
     */
    @jakarta.annotation.PostConstruct
    public void checkTable() {
        if (!enabled) {
            logger.info("[MemoHistory] 변경 이력 기록 비활성화 (memo.history.enabled=false)");
            return;
        }
        try {
            long total = repository.count();
            logger.info("[MemoHistory] 이력 테이블 확인 — 보관 {}건, 정책: {}일 / 사용자당 {}건 / 최소간격 {}초",
                    total, retentionDays, maxPerUser, minIntervalSeconds);
        } catch (Exception e) {
            logger.error("[MemoHistory] 이력 테이블에 접근할 수 없습니다 — 변경 이력 기능이 동작하지 않습니다. "
                    + "({}: {})", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    public boolean isEnabled()        { return enabled; }
    public int getRetentionDays()     { return retentionDays; }
    public int getMaxPerUser()        { return maxPerUser; }
    public int getMinIntervalSeconds(){ return minIntervalSeconds; }

    // ── 기록 ──────────────────────────────────────────────────────

    /**
     * 덮어쓰기 직전 스냅샷 기록.
     *
     * @param previousMemo 덮이기 <b>전</b>의 내용 (새 값이 아니다)
     * @return 실제로 행을 만들었으면 true (억제 정책에 걸리면 false)
     */
    public boolean record(String username, String previousMemo, String reason) {
        if (!enabled) return false;
        String prev = previousMemo == null ? "" : previousMemo;
        if (prev.isEmpty()) return false;               // 빈 내용은 복원 가치가 없다

        LocalDateTime now = LocalDateTime.now();
        MemoHistory last = repository.findFirstByUsernameOrderByCreatedAtDescIdDesc(username).orElse(null);
        if (last != null) {
            String lastMemo = last.getMemo() == null ? "" : last.getMemo();
            if (lastMemo.equals(prev)) return false;    // 같은 내용을 두 번 쌓지 않는다

            // 복원/초기화는 되돌리기 수요가 확실하므로 간격 제한을 적용하지 않는다
            boolean important = REASON_RESTORE.equals(reason) || REASON_CLEAR.equals(reason);
            if (!important && minIntervalSeconds > 0
                    && last.getCreatedAt() != null
                    && last.getCreatedAt().isAfter(now.minusSeconds(minIntervalSeconds))) {
                return false;
            }
        }

        int bytes = prev.getBytes(StandardCharsets.UTF_8).length;
        repository.save(new MemoHistory(username, prev, bytes, now, normalizeReason(reason)));
        enforceMaxPerUser(username);
        logger.debug("[MemoHistory] 스냅샷 기록 — user={}, bytes={}, reason={}", username, bytes, reason);
        return true;
    }

    private static String normalizeReason(String reason) {
        if (REASON_RESTORE.equals(reason) || REASON_CLEAR.equals(reason)) return reason;
        return REASON_SAVE;
    }

    /** 사용자당 상한 초과분을 오래된 것부터 제거. */
    private void enforceMaxPerUser(String username) {
        if (maxPerUser <= 0) return;
        long count = repository.countByUsername(username);
        if (count <= maxPerUser) return;
        int excess = (int) (count - maxPerUser);
        List<Long> ids = repository.findIdsOldestFirst(username, PageRequest.of(0, excess));
        if (!ids.isEmpty()) {
            repository.deleteByIds(ids);
            logger.info("[MemoHistory] 사용자당 상한({}) 초과 — user={}, 오래된 {}건 삭제", maxPerUser, username, ids.size());
        }
    }

    // ── 조회 ──────────────────────────────────────────────────────

    /** 최신순 목록 (본문 제외). 상한은 maxPerUser 로 자연히 제한된다. */
    public List<MemoHistoryItem> list(String username) {
        int limit = maxPerUser > 0 ? maxPerUser : 200;
        List<Object[]> rows = repository.findSummaries(username, PageRequest.of(0, limit));
        List<MemoHistoryItem> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new MemoHistoryItem(
                    (Long) r[0],
                    (LocalDateTime) r[1],
                    r[2] == null ? 0 : ((Number) r[2]).intValue(),
                    (String) r[3],
                    toPreview((String) r[4])));
        }
        return out;
    }

    /** 줄바꿈/연속 공백을 한 칸으로 눌러 한 줄 미리보기로. */
    private static String toPreview(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > PREVIEW_CHARS ? s.substring(0, PREVIEW_CHARS) + "…" : s;
    }

    /**
     * 단건 조회 — <b>소유권 검증을 쿼리에 포함</b>한다.
     * id 만으로 찾은 뒤 비교하는 방식은 검증을 잊기 쉬워 남의 메모가 새어나간다.
     */
    public MemoHistory require(String username, Long id) {
        return repository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new IllegalArgumentException("이력을 찾을 수 없습니다."));
    }

    public long count(String username) {
        return repository.countByUsername(username);
    }

    public void deleteAll(String username) {
        repository.deleteByUsername(username);
        logger.info("[MemoHistory] action=clear-history user={}", username);
    }

    // ── 보관기간 정리 ─────────────────────────────────────────────

    /** 매일 03:30 만료 스냅샷 정리. */
    @Scheduled(cron = "${memo.history.purge-cron:0 30 3 * * *}")
    public void purgeExpired() {
        if (retentionDays <= 0) return;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            logger.info("[MemoHistory] 보관기간({}일) 초과 스냅샷 {}건 정리 (cutoff={})", retentionDays, deleted, cutoff);
        }
    }
}

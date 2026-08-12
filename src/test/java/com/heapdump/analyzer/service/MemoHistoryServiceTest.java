package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.dto.MemoHistoryItem;
import com.heapdump.analyzer.model.entity.MemoHistory;
import com.heapdump.analyzer.repository.MemoHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 메모 이력 보관 정책 테스트 (2026-08-12).
 *
 * <p>이 기능의 급소는 <b>보관량 통제</b>다. 메모장 자동 저장은 5초 debounce 라 타이핑을 계속하면
 * 분당 10회 넘게 저장되고 컬럼은 최대 10MB — 아무 제약 없이 스냅샷을 쌓으면 한 사용자가
 * 며칠 만에 수 GB 를 만든다. 최소 간격 / 사용자당 상한 / 중복·빈 값 억제가 모두 살아 있어야 한다.
 *
 * <p>소유권 검증(남의 스냅샷 접근 차단)도 함께 고정한다 — 조회 경로가 여러 개라 한 곳만
 * 빠뜨려도 개인 메모가 새어나간다.
 *
 * <p>Spring 컨텍스트 없이 in-memory 리포지토리 스텁으로 검증한다.
 */
class MemoHistoryServiceTest {

    /** JPA 없이 서비스 로직만 보기 위한 최소 스텁 (필요한 메서드만 구현). */
    private static class FakeRepo implements MemoHistoryRepository {

        final List<MemoHistory> rows = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override
        public <S extends MemoHistory> S save(S entity) {
            if (entity.getId() == null) entity.setId(seq.getAndIncrement());
            rows.removeIf(r -> r.getId().equals(entity.getId()));
            rows.add(entity);
            return entity;
        }

        @Override
        public Optional<MemoHistory> findFirstByUsernameOrderByCreatedAtDescIdDesc(String username) {
            return rows.stream()
                    .filter(r -> r.getUsername().equals(username))
                    .max(Comparator.comparing(MemoHistory::getCreatedAt).thenComparing(MemoHistory::getId));
        }

        @Override
        public Optional<MemoHistory> findByIdAndUsername(Long id, String username) {
            return rows.stream()
                    .filter(r -> r.getId().equals(id) && r.getUsername().equals(username))
                    .findFirst();
        }

        @Override
        public long countByUsername(String username) {
            return rows.stream().filter(r -> r.getUsername().equals(username)).count();
        }

        @Override
        public List<Long> findIdsOldestFirst(String username, Pageable pageable) {
            return rows.stream()
                    .filter(r -> r.getUsername().equals(username))
                    .sorted(Comparator.comparing(MemoHistory::getCreatedAt).thenComparing(MemoHistory::getId))
                    .limit(pageable.getPageSize())
                    .map(MemoHistory::getId)
                    .collect(Collectors.toList());
        }

        @Override
        public int deleteByIds(List<Long> ids) {
            int before = rows.size();
            rows.removeIf(r -> ids.contains(r.getId()));
            return before - rows.size();
        }

        @Override
        public int deleteOlderThan(LocalDateTime cutoff) {
            int before = rows.size();
            rows.removeIf(r -> r.getCreatedAt().isBefore(cutoff));
            return before - rows.size();
        }

        @Override
        public List<Object[]> findSummaries(String username, Pageable pageable) {
            return rows.stream()
                    .filter(r -> r.getUsername().equals(username))
                    .sorted(Comparator.comparing(MemoHistory::getCreatedAt).thenComparing(MemoHistory::getId).reversed())
                    .limit(pageable.getPageSize())
                    .map(r -> new Object[]{
                            r.getId(), r.getCreatedAt(), r.getByteSize(), r.getReason(),
                            r.getMemo() == null ? null
                                    : r.getMemo().substring(0, Math.min(160, r.getMemo().length()))})
                    .collect(Collectors.toList());
        }

        @Override
        public void deleteByUsername(String username) {
            rows.removeIf(r -> r.getUsername().equals(username));
        }

        // ── 미사용 JpaRepository 메서드 ────────────────────────────
        @Override public List<MemoHistory> findAll() { return new ArrayList<>(rows); }
        @Override public List<MemoHistory> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<MemoHistory> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public List<MemoHistory> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public Optional<MemoHistory> findById(Long id) { return rows.stream().filter(r -> r.getId().equals(id)).findFirst(); }
        @Override public boolean existsById(Long id) { return findById(id).isPresent(); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { rows.removeIf(r -> r.getId().equals(id)); }
        @Override public void delete(MemoHistory entity) { rows.remove(entity); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends MemoHistory> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends MemoHistory> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends MemoHistory> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<MemoHistory> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public MemoHistory getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public MemoHistory getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public MemoHistory getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends MemoHistory, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }

    private FakeRepo repo;
    private MemoHistoryService svc;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        svc = new MemoHistoryService(repo);
        configure(true, 7, 100, 0);   // 기본은 간격 제한 없음 (개별 테스트에서 켠다)
    }

    private void configure(boolean enabled, int retentionDays, int maxPerUser, int minIntervalSeconds) {
        ReflectionTestUtils.setField(svc, "enabled", enabled);
        ReflectionTestUtils.setField(svc, "retentionDays", retentionDays);
        ReflectionTestUtils.setField(svc, "maxPerUser", maxPerUser);
        ReflectionTestUtils.setField(svc, "minIntervalSeconds", minIntervalSeconds);
    }

    // ── 기록 억제 ────────────────────────────────────────

    @Test
    @DisplayName("정상 기록 — 덮이기 전 내용과 크기·사유가 남는다")
    void recordsSnapshot() {
        assertTrue(svc.record("alice", "이전 내용", MemoHistoryService.REASON_SAVE));

        assertEquals(1, repo.rows.size());
        MemoHistory h = repo.rows.get(0);
        assertEquals("alice", h.getUsername());
        assertEquals("이전 내용", h.getMemo());
        assertEquals("이전 내용".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, h.getByteSize());
        assertEquals("save", h.getReason());
    }

    @Test
    @DisplayName("빈 내용은 기록하지 않는다 (복원 가치 없음)")
    void emptyPreviousIsNotRecorded() {
        assertFalse(svc.record("alice", null, MemoHistoryService.REASON_SAVE));
        assertFalse(svc.record("alice", "", MemoHistoryService.REASON_SAVE));
        assertEquals(0, repo.rows.size());
    }

    @Test
    @DisplayName("직전 스냅샷과 내용이 같으면 기록하지 않는다")
    void duplicateContentIsSkipped() {
        assertTrue(svc.record("alice", "같은 내용", MemoHistoryService.REASON_SAVE));
        assertFalse(svc.record("alice", "같은 내용", MemoHistoryService.REASON_SAVE));
        assertEquals(1, repo.rows.size());
    }

    @Test
    @DisplayName("최소 간격 안의 연속 저장은 기록하지 않는다 (자동 저장 폭주 방어)")
    void minIntervalSuppressesBurst() {
        configure(true, 7, 100, 60);

        assertTrue(svc.record("alice", "v1", MemoHistoryService.REASON_SAVE));
        for (int i = 2; i <= 20; i++) {
            assertFalse(svc.record("alice", "v" + i, MemoHistoryService.REASON_SAVE),
                    i + "번째 저장이 기록됐다 — 5초 debounce 자동 저장이면 순식간에 수천 건이 쌓인다");
        }
        assertEquals(1, repo.rows.size());
    }

    @Test
    @DisplayName("복원/초기화는 간격 제한을 무시하고 항상 기록한다")
    void importantReasonsBypassInterval() {
        configure(true, 7, 100, 60);

        assertTrue(svc.record("alice", "v1", MemoHistoryService.REASON_SAVE));
        assertTrue(svc.record("alice", "v2", MemoHistoryService.REASON_RESTORE),
                "복원 직전 내용은 되돌리기 수요가 확실하다");
        assertTrue(svc.record("alice", "v3", MemoHistoryService.REASON_CLEAR),
                "초기화 직전 내용을 놓치면 영구 소실이다");
        assertEquals(3, repo.rows.size());
    }

    @Test
    @DisplayName("비활성화 상태면 아무것도 기록하지 않는다")
    void disabledRecordsNothing() {
        configure(false, 7, 100, 0);
        assertFalse(svc.record("alice", "내용", MemoHistoryService.REASON_SAVE));
        assertEquals(0, repo.rows.size());
    }

    // ── 상한/보관기간 ────────────────────────────────────

    @Test
    @DisplayName("사용자당 상한 초과분은 오래된 것부터 삭제된다")
    void maxPerUserEvictsOldest() {
        configure(true, 7, 3, 0);

        for (int i = 1; i <= 6; i++) {
            svc.record("alice", "v" + i, MemoHistoryService.REASON_SAVE);
        }

        assertEquals(3, repo.rows.size());
        List<String> kept = repo.rows.stream().map(MemoHistory::getMemo).sorted().collect(Collectors.toList());
        assertEquals(List.of("v4", "v5", "v6"), kept, "최신 3건만 남아야 한다");
    }

    @Test
    @DisplayName("상한은 사용자별로 적용된다")
    void maxPerUserIsPerUser() {
        configure(true, 7, 2, 0);

        svc.record("alice", "a1", MemoHistoryService.REASON_SAVE);
        svc.record("alice", "a2", MemoHistoryService.REASON_SAVE);
        svc.record("alice", "a3", MemoHistoryService.REASON_SAVE);
        svc.record("bob", "b1", MemoHistoryService.REASON_SAVE);

        assertEquals(2, svc.count("alice"));
        assertEquals(1, svc.count("bob"), "alice 의 상한 정리가 bob 을 건드리면 안 된다");
    }

    @Test
    @DisplayName("보관기간이 지난 스냅샷만 정리된다")
    void purgeRemovesOnlyExpired() {
        configure(true, 7, 100, 0);
        svc.record("alice", "old", MemoHistoryService.REASON_SAVE);
        svc.record("alice", "fresh", MemoHistoryService.REASON_SAVE);
        repo.rows.get(0).setCreatedAt(LocalDateTime.now().minusDays(8));

        svc.purgeExpired();

        assertEquals(1, repo.rows.size());
        assertEquals("fresh", repo.rows.get(0).getMemo());
    }

    @Test
    @DisplayName("retentionDays=0 이면 기간 정리를 하지 않는다 (무기한 보관)")
    void purgeDisabledWhenZero() {
        configure(true, 0, 100, 0);
        svc.record("alice", "old", MemoHistoryService.REASON_SAVE);
        repo.rows.get(0).setCreatedAt(LocalDateTime.now().minusDays(3650));

        svc.purgeExpired();

        assertEquals(1, repo.rows.size());
    }

    // ── 소유권 ──────────────────────────────────────────

    @Test
    @DisplayName("다른 사용자의 스냅샷은 조회할 수 없다")
    void cannotReadOthersSnapshot() {
        svc.record("alice", "앨리스의 비밀 메모", MemoHistoryService.REASON_SAVE);
        Long id = repo.rows.get(0).getId();

        assertEquals("앨리스의 비밀 메모", svc.require("alice", id).getMemo());
        assertThrows(IllegalArgumentException.class, () -> svc.require("bob", id),
                "id 만 알면 남의 메모를 읽을 수 있으면 안 된다");
    }

    @Test
    @DisplayName("목록은 본인 것만, 최신순으로")
    void listIsOwnOnlyAndNewestFirst() {
        configure(true, 7, 100, 0);
        svc.record("alice", "첫번째", MemoHistoryService.REASON_SAVE);
        svc.record("alice", "두번째", MemoHistoryService.REASON_SAVE);
        svc.record("bob", "밥의 메모", MemoHistoryService.REASON_SAVE);

        List<MemoHistoryItem> items = svc.list("alice");

        assertEquals(2, items.size());
        assertEquals("두번째", items.get(0).getPreview());
        assertEquals("첫번째", items.get(1).getPreview());
    }

    @Test
    @DisplayName("미리보기는 줄바꿈을 눌러 한 줄로 만들고 길이를 제한한다")
    void previewIsFlattenedAndTruncated() {
        svc.record("alice", "첫 줄\n\n둘째 줄\t탭", MemoHistoryService.REASON_SAVE);
        assertEquals("첫 줄 둘째 줄 탭", svc.list("alice").get(0).getPreview());

        repo.rows.clear();
        svc.record("alice", "가".repeat(300), MemoHistoryService.REASON_SAVE);
        String preview = svc.list("alice").get(0).getPreview();
        assertTrue(preview.endsWith("…"), preview);
        assertTrue(preview.length() <= 121, "길이=" + preview.length());
    }

    @Test
    @DisplayName("전체 삭제는 본인 것만 지운다")
    void deleteAllIsScopedToUser() {
        svc.record("alice", "a", MemoHistoryService.REASON_SAVE);
        svc.record("bob", "b", MemoHistoryService.REASON_SAVE);

        svc.deleteAll("alice");

        assertEquals(0, svc.count("alice"));
        assertEquals(1, svc.count("bob"));
    }
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.MemoHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemoHistoryRepository extends JpaRepository<MemoHistory, Long> {

    /**
     * 목록/미리보기용 — <b>본문(memo)을 제외</b>한 투영.
     * 10MB LOB 가 섞인 행을 목록마다 통째로 끌고 오면 안 된다(analysis_history 를 요약/상세로
     * 분리한 것과 같은 이유). 본문은 상세 조회에서만 읽는다.
     */
    @Query("SELECT h.id, h.createdAt, h.byteSize, h.reason, SUBSTRING(h.memo, 1, 160) "
         + "FROM MemoHistory h WHERE h.username = :username ORDER BY h.createdAt DESC, h.id DESC")
    List<Object[]> findSummaries(@Param("username") String username, Pageable pageable);

    /** 억제 정책 판정용 — 가장 최근 스냅샷 1건(본문 포함, 중복 비교에 필요). */
    Optional<MemoHistory> findFirstByUsernameOrderByCreatedAtDescIdDesc(String username);

    /** 소유권 검증을 쿼리에 포함 — 남의 id 를 넣어도 빈 결과가 된다. */
    Optional<MemoHistory> findByIdAndUsername(Long id, String username);

    long countByUsername(String username);

    /**
     * 전체 삭제 — <b>벌크 JPQL</b>. 파생 delete({@code deleteByUsername})는 행마다 엔티티를 먼저 로드하므로
     * 최대 10MB 본문 × 사용자당 100건을 메모리로 끌고 온 뒤 한 건씩 지운다.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM MemoHistory h WHERE h.username = :username")
    int deleteAllOwnedBy(@Param("username") String username);

    /** 개별 삭제 전 감사 로그용 식별정보(시각·크기·사유) — 본문 제외, 소유권 조건 포함. */
    @Query("SELECT h.createdAt, h.byteSize, h.reason FROM MemoHistory h WHERE h.id = :id AND h.username = :username")
    List<Object[]> findMetaOwnedBy(@Param("id") Long id, @Param("username") String username);

    /** 개별 삭제 — 소유권을 WHERE 에 넣는다. 남의 id 면 0건. */
    @Modifying
    @Transactional
    @Query("DELETE FROM MemoHistory h WHERE h.id = :id AND h.username = :username")
    int deleteOwnedBy(@Param("id") Long id, @Param("username") String username);

    /** 보관기간 만료 정리. */
    @Modifying
    @Transactional
    @Query("DELETE FROM MemoHistory h WHERE h.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** 사용자당 상한 초과분 정리용 — 오래된 것부터 지우기 위해 id 만 뽑는다. */
    @Query("SELECT h.id FROM MemoHistory h WHERE h.username = :username ORDER BY h.createdAt ASC, h.id ASC")
    List<Long> findIdsOldestFirst(@Param("username") String username, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM MemoHistory h WHERE h.id IN :ids")
    int deleteByIds(@Param("ids") List<Long> ids);
}

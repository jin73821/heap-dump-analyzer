package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.LlmGuardLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface LlmGuardLogRepository
        extends JpaRepository<LlmGuardLog, Long>, JpaSpecificationExecutor<LlmGuardLog> {

    /**
     * 보관기간 만료 정리 — <b>벌크 JPQL</b>(파생 delete 는 행마다 엔티티를 먼저 로드한다).
     * 이력 삭제는 이 스케줄 경로 하나뿐이다(변조 방지 — 관리자 삭제 API 없음).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM LlmGuardLog l WHERE l.detectedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** 기간 내 조치별 행 수 — [action, count]. 통계 카드용(규칙별 행이라 '탐지 건수'가 아니라 '규칙 일치 행' 수). */
    @Query("SELECT l.action, COUNT(l) FROM LlmGuardLog l WHERE l.detectedAt >= :from GROUP BY l.action")
    List<Object[]> countByActionSince(@Param("from") LocalDateTime from);

    /** 기간 내 조치별 이벤트 수 — [action, count(distinct eventId)]. */
    @Query("SELECT l.action, COUNT(DISTINCT l.eventId) FROM LlmGuardLog l WHERE l.detectedAt >= :from GROUP BY l.action")
    List<Object[]> countEventsByActionSince(@Param("from") LocalDateTime from);

    /** 기간 내 규칙별 일치 수 상위 — [ruleName, sum(matchCount)]. */
    @Query("SELECT l.ruleName, SUM(l.matchCount) FROM LlmGuardLog l WHERE l.detectedAt >= :from "
            + "GROUP BY l.ruleName ORDER BY SUM(l.matchCount) DESC")
    List<Object[]> sumByRuleSince(@Param("from") LocalDateTime from);
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiInsightEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiInsightRepository extends JpaRepository<AiInsightEntity, Long> {
    Optional<AiInsightEntity> findByFilename(String filename);
    boolean existsByFilename(String filename);
    void deleteByFilename(String filename);

    /** 심각도만 뽑는 프로젝션 — insight_data(MEDIUMTEXT)를 SELECT 하지 않는다. */
    interface SeverityRow {
        String getFilename();
        String getSeverity();
        LocalDateTime getAnalysedAt();
    }

    /** 합성 키 접두(`__core__:` 등)로 한 번에 조회 — 현황판이 행마다 findByFilename 을 부르지 않게(N+1 방지). */
    List<SeverityRow> findByFilenameStartingWith(String prefix);
}

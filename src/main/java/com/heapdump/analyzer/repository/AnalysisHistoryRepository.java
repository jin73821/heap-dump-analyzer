package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistoryEntity, Long> {
    List<AnalysisHistoryEntity> findAllByOrderByAnalyzedAtDesc();
    List<AnalysisHistoryEntity> findByServerIdOrderByAnalyzedAtDesc(Long serverId);
    List<AnalysisHistoryEntity> findByServerIdIsNullOrderByAnalyzedAtDesc();
    Optional<AnalysisHistoryEntity> findByFilename(String filename);
    boolean existsByFilename(String filename);
    void deleteByFilename(String filename);
    List<AnalysisHistoryEntity> findByStatus(String status);
    long countByStatus(String status);
}

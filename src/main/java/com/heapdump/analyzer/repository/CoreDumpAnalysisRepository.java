package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoreDumpAnalysisRepository extends JpaRepository<CoreDumpAnalysisEntity, Long> {
    Optional<CoreDumpAnalysisEntity> findByFilename(String filename);
    List<CoreDumpAnalysisEntity> findAllByOrderByCreatedAtDesc();
    List<CoreDumpAnalysisEntity> findByFileDeletedFalseOrderByCreatedAtDesc();
    boolean existsByFilename(String filename);
}

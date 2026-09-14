package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GcLogAnalysisRepository extends JpaRepository<GcLogAnalysisEntity, Long> {
    Optional<GcLogAnalysisEntity> findByFilename(String filename);
    boolean existsByFilename(String filename);
    boolean existsByFilenameAndStatus(String filename, String status);
    List<GcLogAnalysisEntity> findAllByOrderByCreatedAtDesc();
    List<GcLogAnalysisEntity> findByFileDeletedFalseOrderByCreatedAtDesc();
    List<GcLogAnalysisEntity> findByMatchedDumpFilename(String dumpFilename);
    List<GcLogAnalysisEntity> findByMatchSource(String matchSource);
    List<GcLogAnalysisEntity> findByServerIdOrderByCreatedAtDesc(Long serverId);
}

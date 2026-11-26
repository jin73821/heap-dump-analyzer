package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ThreadDumpAnalysisRepository extends JpaRepository<ThreadDumpAnalysisEntity, Long> {
    Optional<ThreadDumpAnalysisEntity> findByFilename(String filename);

    List<ThreadDumpAnalysisEntity> findAllByOrderByCreatedAtDesc();

    List<ThreadDumpAnalysisEntity> findByStatus(String status);

    List<ThreadDumpAnalysisEntity> findByLinkedHeapFilename(String linkedHeapFilename);

    List<ThreadDumpAnalysisEntity> findByPartOf(String partOf);
}

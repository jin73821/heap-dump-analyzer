package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiInsightEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiInsightRepository extends JpaRepository<AiInsightEntity, Long> {
    Optional<AiInsightEntity> findByFilename(String filename);
    boolean existsByFilename(String filename);
    void deleteByFilename(String filename);
}

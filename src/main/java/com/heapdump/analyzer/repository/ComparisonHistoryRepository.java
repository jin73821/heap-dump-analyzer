package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.ComparisonHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ComparisonHistoryRepository extends JpaRepository<ComparisonHistoryEntity, Long> {

    List<ComparisonHistoryEntity> findAllByOrderByComparedAtDesc();

    Optional<ComparisonHistoryEntity>
        findFirstByComparedByAndBaseFilenameAndTargetFilenameOrderByComparedAtDesc(
            String comparedBy, String baseFilename, String targetFilename);

    @Modifying
    @Transactional
    int deleteByIdIn(List<Long> ids);
}

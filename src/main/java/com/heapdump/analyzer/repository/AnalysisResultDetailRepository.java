package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AnalysisResultDetailEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AnalysisResultDetailRepository extends JpaRepository<AnalysisResultDetailEntity, Long> {

    Optional<AnalysisResultDetailEntity> findByFilename(String filename);

    boolean existsByFilename(String filename);

    /**
     * clearCache() 등 트랜잭션 밖에서도 호출되므로 메서드 단위 트랜잭션을 명시한다.
     * (파생 delete 쿼리는 트랜잭션이 없으면 InvalidDataAccessApiUsageException 으로 실패)
     */
    @Transactional
    void deleteByFilename(String filename);

    /** 기동 복원 시 LOB 를 제외한 파일명 목록만 필요한 경우 (진단/정합성 점검용). */
    @Query("SELECT d.filename FROM AnalysisResultDetailEntity d")
    List<String> findAllFilenames();
}

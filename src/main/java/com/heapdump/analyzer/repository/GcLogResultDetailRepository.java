package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.GcLogResultDetailEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface GcLogResultDetailRepository extends JpaRepository<GcLogResultDetailEntity, Long> {
    Optional<GcLogResultDetailEntity> findByFilename(String filename);

    /** 파생 delete 는 트랜잭션 필수 — 비트랜잭션 서비스 컨텍스트에서 호출된다. */
    @Transactional
    void deleteByFilename(String filename);
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.ThreadDumpResultDetailEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ThreadDumpResultDetailRepository extends JpaRepository<ThreadDumpResultDetailEntity, Long> {
    Optional<ThreadDumpResultDetailEntity> findByFilename(String filename);

    /** 파생 delete 는 트랜잭션 필수 — 비트랜잭션 서비스 컨텍스트에서 호출된다. */
    @Transactional
    void deleteByFilename(String filename);
}

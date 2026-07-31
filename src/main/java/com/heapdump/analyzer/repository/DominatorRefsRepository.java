package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.DominatorRefsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface DominatorRefsRepository extends JpaRepository<DominatorRefsEntity, Long> {

    Optional<DominatorRefsEntity> findByFilename(String filename);

    boolean existsByFilename(String filename);

    /**
     * clearCache() 등 트랜잭션 밖에서도 호출되므로 메서드 단위 트랜잭션을 명시한다.
     * (파생 delete 쿼리는 트랜잭션이 없으면 InvalidDataAccessApiUsageException 으로 실패)
     */
    @Transactional
    void deleteByFilename(String filename);
}

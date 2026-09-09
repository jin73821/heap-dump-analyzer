package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.RagLearningDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RagLearningDocRepository extends JpaRepository<RagLearningDoc, Long> {

    /** 내보내기 순서 — 원본 CSV 의 작성 순서를 그대로 재현해야 바이트 동일이 성립한다. */
    List<RagLearningDoc> findAllByOrderBySortOrderAscDocIdAsc();

    Optional<RagLearningDoc> findByDocId(String docId);

    /** 중복 판정은 배치 2회로 끝낸다 — 행마다 조회하면 84행 import 에 168 쿼리가 나간다. */
    List<RagLearningDoc> findByDocIdIn(Collection<String> docIds);

    List<RagLearningDoc> findByContentHashIn(Collection<String> hashes);

    List<RagLearningDoc> findByTitleNormIn(Collection<String> titleNorms);

    @Query("select coalesce(max(d.sortOrder), 0) from RagLearningDoc d")
    int maxSortOrder();
}

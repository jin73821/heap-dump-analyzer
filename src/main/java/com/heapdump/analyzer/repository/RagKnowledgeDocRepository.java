package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.RagKnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RagKnowledgeDocRepository extends JpaRepository<RagKnowledgeDoc, Long> {

    List<RagKnowledgeDoc> findAllByOrderByOriginIdAsc();

    Optional<RagKnowledgeDoc> findByOriginId(String originId);

    List<RagKnowledgeDoc> findByOriginIdIn(Collection<String> originIds);

    List<RagKnowledgeDoc> findByContentHashIn(Collection<String> hashes);

    List<RagKnowledgeDoc> findByTitleNormIn(Collection<String> titleNorms);
}

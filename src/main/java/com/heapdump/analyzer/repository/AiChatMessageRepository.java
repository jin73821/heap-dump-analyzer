package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    @Transactional
    void deleteBySessionId(Long sessionId);

    long countBySessionId(Long sessionId);

    @Modifying
    @Transactional
    @Query("DELETE FROM AiChatMessage m WHERE m.sessionId IN " +
           "(SELECT s.id FROM AiChatSession s WHERE s.filename = :filename)")
    void deleteByFilename(@Param("filename") String filename);
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    void deleteBySessionId(Long sessionId);

    long countBySessionId(Long sessionId);
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    List<AiChatSession> findByUsernameOrderByUpdatedAtDesc(String username);

    List<AiChatSession> findByUsernameAndFilenameOrderByUpdatedAtDesc(String username, String filename);

    List<AiChatSession> findByUsernameAndFilenameIsNullOrderByUpdatedAtDesc(String username);

    long countByUsername(String username);
}

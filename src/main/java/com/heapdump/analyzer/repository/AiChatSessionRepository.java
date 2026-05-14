package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Long> {

    List<AiChatSession> findByUsernameOrderByUpdatedAtDesc(String username);

    List<AiChatSession> findByUsernameAndFilenameOrderByUpdatedAtDesc(String username, String filename);

    List<AiChatSession> findByUsernameAndFilenameIsNullOrderByUpdatedAtDesc(String username);

    long countByUsername(String username);

    // ── ADMIN 전용: 전체/필터 조회 ────────────────────────────────
    List<AiChatSession> findAllByOrderByUpdatedAtDesc();

    List<AiChatSession> findByFilenameOrderByUpdatedAtDesc(String filename);
    // (username 만 ADMIN 필터링은 기존 findByUsernameOrderByUpdatedAtDesc 재사용)

    // 모든 채팅 작성자 username 의 distinct 목록 (admin 사용자 셀렉트 옵션용)
    @org.springframework.data.jpa.repository.Query(
        "select distinct s.username from AiChatSession s where s.username is not null order by s.username")
    List<String> findDistinctUsernames();
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    @Modifying
    @Transactional
    @Query("DELETE FROM AiChatSession s WHERE s.filename = :filename")
    void deleteByFilename(@Param("filename") String filename);

    // 모든 채팅 작성자 username 의 distinct 목록 (admin 사용자 셀렉트 옵션용)
    @org.springframework.data.jpa.repository.Query(
        "select distinct s.username from AiChatSession s where s.username is not null order by s.username")
    List<String> findDistinctUsernames();

    /**
     * 세션 검색 — 제목 또는 세션 내 임의 메시지 본문(content) 매칭 + 옵션 username/filename 필터.
     * <p>q 는 호출자가 LIKE 와일드카드(% _)를 '|' 로 escape 한 뒤 양끝에 '%' 를 감싸 전달.
     * usernameParam/filenameParam 은 null 이면 해당 조건 무시 (ADMIN 의 전체 검색 케이스).
     * EXISTS 서브쿼리는 semi-join 이라 같은 세션의 여러 메시지 매칭이 row 를 늘리지 않음 — DISTINCT 불필요.
     * ESCAPE 문자로 '|' 사용 (MariaDB backslash-escape 모드에서 ESCAPE '\\' 가 SQL 문법 오류를 일으키는 함정 회피).
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT s FROM AiChatSession s " +
        "WHERE (:usernameParam IS NULL OR s.username = :usernameParam) " +
        "  AND (:filenameParam IS NULL OR s.filename = :filenameParam) " +
        "  AND ( LOWER(COALESCE(s.title, '')) LIKE LOWER(:q) ESCAPE '|' " +
        "        OR EXISTS (SELECT 1 FROM AiChatMessage m " +
        "                   WHERE m.sessionId = s.id " +
        "                     AND LOWER(m.content) LIKE LOWER(:q) ESCAPE '|') ) " +
        "ORDER BY s.updatedAt DESC")
    List<AiChatSession> searchSessions(
        @org.springframework.data.repository.query.Param("q") String q,
        @org.springframework.data.repository.query.Param("usernameParam") String usernameParam,
        @org.springframework.data.repository.query.Param("filenameParam") String filenameParam);
}

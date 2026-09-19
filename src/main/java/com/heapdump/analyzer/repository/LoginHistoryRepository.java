package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.LoginHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LoginHistoryRepository
        extends JpaRepository<LoginHistory, Long>, JpaSpecificationExecutor<LoginHistory> {

    Page<LoginHistory> findAllByOrderByLoginAtDesc(Pageable pageable);

    /**
     * 사용자별 <b>마지막 로그인 성공</b> 시각·IP — Accounts 사용자 목록의 '최근 접속' 열.
     * 사용자마다 조회하면 N+1 이라, 사용자명별 MAX(login_at) 를 한 번 집계한 뒤 그 행의 IP 를 붙인다.
     * 같은 시각의 성공 행이 둘이면 두 행이 나온다 — 호출자가 사용자명당 첫 행만 쓴다.
     * 반환: [username, login_at, ip]
     */
    @Query(value = "SELECT lh.username, lh.login_at, lh.ip FROM login_history lh "
            + "JOIN (SELECT username, MAX(login_at) AS last_at FROM login_history "
            + "      WHERE status = 'SUCCESS' GROUP BY username) t "
            + "  ON t.username = lh.username AND t.last_at = lh.login_at "
            + "WHERE lh.status = 'SUCCESS'", nativeQuery = true)
    List<Object[]> findLastSuccessfulLogins();

    /**
     * 한 사용자의 최근 로그인 성공 2건(최신순) — /account 의 '최근 접속'·'이전 접속'.
     * 최신 1건은 대개 지금 이 접속이라, 기억에 없는 접속을 알아채는 데 쓰이는 건 둘째 건이다.
     */
    List<LoginHistory> findTop2ByUsernameAndStatusOrderByLoginAtDesc(String username, LoginHistory.Status status);
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /**
     * OTP 실패 카운트 원자 증가 (동시 다중 브라우저 실패 시 카운트 유실 방지).
     * clearAutomatically — 증가 직후 재조회가 영속성 컨텍스트의 stale 엔티티를 반환하지 않도록.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.otpFailCount = u.otpFailCount + 1 where u.id = :id")
    int incrementOtpFailCount(@Param("id") Long id);
}

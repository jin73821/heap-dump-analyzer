package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AlertAck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface AlertAckRepository extends JpaRepository<AlertAck, Long> {
    Optional<AlertAck> findByAlertKey(String alertKey);

    /** 파생 delete 는 트랜잭션 필수 — 컨트롤러(비트랜잭션)에서 직접 불린다. */
    @Transactional
    long deleteByAlertKey(String alertKey);
}

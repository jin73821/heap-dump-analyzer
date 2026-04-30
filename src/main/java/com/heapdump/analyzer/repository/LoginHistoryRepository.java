package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.LoginHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LoginHistoryRepository
        extends JpaRepository<LoginHistory, Long>, JpaSpecificationExecutor<LoginHistory> {

    Page<LoginHistory> findAllByOrderByLoginAtDesc(Pageable pageable);
}

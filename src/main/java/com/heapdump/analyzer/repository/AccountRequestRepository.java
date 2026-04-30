package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.AccountRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountRequestRepository extends JpaRepository<AccountRequest, Long>,
        JpaSpecificationExecutor<AccountRequest> {

    boolean existsByUsernameAndStatus(String username, AccountRequest.Status status);

    long countByStatus(AccountRequest.Status status);
}

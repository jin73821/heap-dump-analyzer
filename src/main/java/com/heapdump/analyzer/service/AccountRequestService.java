package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.AccountRequest;
import com.heapdump.analyzer.model.entity.User;
import com.heapdump.analyzer.repository.AccountRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AccountRequestService {

    private static final Logger logger = LoggerFactory.getLogger(AccountRequestService.class);

    private final AccountRequestRepository repository;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public AccountRequestService(AccountRequestRepository repository,
                                 UserService userService,
                                 PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AccountRequest submit(String username, String password, String displayName,
                                 String reason, String requestIp) {
        UserService.validateUsername(username);
        UserService.validatePassword(password);

        String encoded = passwordEncoder.encode(password);

        if (userService.existsByUsername(username)) {
            logger.warn("[AccountRequest] 기존 사용자명과 충돌 — 무시: username={}", username);
            return null;
        }
        if (repository.existsByUsernameAndStatus(username, AccountRequest.Status.PENDING)) {
            logger.warn("[AccountRequest] 대기 중 신청과 충돌 — 무시: username={}", username);
            return null;
        }

        AccountRequest req = new AccountRequest();
        req.setUsername(username);
        req.setPassword(encoded);
        req.setDisplayName(displayName);
        req.setReason(reason);
        req.setStatus(AccountRequest.Status.PENDING);
        req.setRequestIp(requestIp);
        AccountRequest saved = repository.save(req);
        logger.info("[AccountRequest] submitted username={} ip={}", username, requestIp);
        return saved;
    }

    public Page<AccountRequest> list(String status, String q, Pageable pageable) {
        Specification<AccountRequest> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null && !status.isEmpty()) {
                try {
                    ps.add(cb.equal(root.get("status"), AccountRequest.Status.valueOf(status)));
                } catch (IllegalArgumentException ignored) {}
            }
            if (q != null && !q.trim().isEmpty()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("displayName"), "")), like)
                ));
            }
            return ps.isEmpty() ? null : cb.and(ps.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, pageable);
    }

    public long pendingCount() {
        return repository.countByStatus(AccountRequest.Status.PENDING);
    }

    @Transactional
    public AccountRequest approve(Long id, User.Role role, String approverUsername) {
        AccountRequest req = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("신청을 찾을 수 없습니다: " + id));
        if (req.getStatus() != AccountRequest.Status.PENDING) {
            throw new IllegalArgumentException("이미 처리된 신청입니다.");
        }
        if (userService.existsByUsername(req.getUsername())) {
            throw new IllegalArgumentException("이미 동일 사용자명이 존재합니다. 신청을 거부하세요.");
        }
        User.Role effectiveRole = (role != null) ? role : User.Role.USER;
        userService.createUserWithEncodedPassword(
                req.getUsername(), req.getPassword(), req.getDisplayName(), effectiveRole);

        req.setStatus(AccountRequest.Status.APPROVED);
        req.setProcessedAt(LocalDateTime.now());
        req.setProcessedBy(approverUsername);
        AccountRequest saved = repository.save(req);
        logger.info("[AccountRequest] approved id={} username={} by={}", id, req.getUsername(), approverUsername);
        return saved;
    }

    @Transactional
    public AccountRequest reject(Long id, String rejectReason, String approverUsername) {
        AccountRequest req = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("신청을 찾을 수 없습니다: " + id));
        if (req.getStatus() != AccountRequest.Status.PENDING) {
            throw new IllegalArgumentException("이미 처리된 신청입니다.");
        }
        req.setStatus(AccountRequest.Status.REJECTED);
        req.setProcessedAt(LocalDateTime.now());
        req.setProcessedBy(approverUsername);
        req.setRejectReason(rejectReason);
        AccountRequest saved = repository.save(req);
        logger.info("[AccountRequest] rejected id={} username={} by={}", id, req.getUsername(), approverUsername);
        return saved;
    }

    @Transactional
    public void deleteRequest(Long id) {
        AccountRequest req = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("신청을 찾을 수 없습니다: " + id));
        if (req.getStatus() == AccountRequest.Status.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청은 삭제할 수 없습니다. 거부 처리하세요.");
        }
        repository.deleteById(id);
    }
}

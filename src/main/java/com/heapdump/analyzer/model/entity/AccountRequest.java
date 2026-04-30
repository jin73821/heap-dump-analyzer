package com.heapdump.analyzer.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "account_requests", indexes = {
        @Index(name = "idx_acct_req_status", columnList = "status"),
        @Index(name = "idx_acct_req_username", columnList = "username"),
        @Index(name = "idx_acct_req_requested_at", columnList = "requested_at")
})
public class AccountRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    /** BCrypt 인코딩된 비밀번호. 승인 시 users.password로 그대로 복사. */
    @Column(nullable = false, length = 255)
    private String password;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** 승인/거부한 관리자 username */
    @Column(name = "processed_by", length = 50)
    private String processedBy;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) requestedAt = LocalDateTime.now();
        if (status == null) status = Status.PENDING;
    }
}

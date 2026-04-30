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
@Table(name = "login_history", indexes = {
        @Index(name = "idx_login_history_username", columnList = "username"),
        @Index(name = "idx_login_history_login_at", columnList = "login_at"),
        @Index(name = "idx_login_history_status", columnList = "status")
})
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    public enum Status {
        SUCCESS, FAILURE
    }

    @PrePersist
    protected void onCreate() {
        if (loginAt == null) loginAt = LocalDateTime.now();
    }
}

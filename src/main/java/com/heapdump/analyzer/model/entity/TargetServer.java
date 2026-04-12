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
@Table(name = "target_servers")
public class TargetServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port = 22;

    @Column(name = "ssh_user", nullable = false, length = 50)
    private String sshUser = "sscuser";

    @Column(name = "dump_path", nullable = false, length = 500)
    private String dumpPath;

    @Column(name = "auto_detect", nullable = false)
    private boolean autoDetect = false;

    @Column(name = "scan_interval_sec", nullable = false)
    private int scanIntervalSec = 300;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 연결 상태: OK, FAIL, UNKNOWN */
    @Column(name = "conn_status", length = 20)
    private String connStatus = "UNKNOWN";

    /** 마지막 에러 메시지 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** 마지막 상태 확인 시각 */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

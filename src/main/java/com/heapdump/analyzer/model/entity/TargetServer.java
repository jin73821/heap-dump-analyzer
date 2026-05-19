package com.heapdump.analyzer.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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

    /** 덤프 파일 경로 — 1~5개. 다중 경로는 줄바꿈(\n)으로 구분 저장. */
    @Column(name = "dump_path", nullable = false, length = 2500)
    private String dumpPath;

    /** 다중 경로 최대 개수 — UI/서비스 공용 상한. */
    public static final int MAX_DUMP_PATHS = 5;

    /** dumpPath를 줄바꿈 기준으로 분리 — 빈/공백 제거, 최대 MAX_DUMP_PATHS개. */
    @Transient
    public List<String> getDumpPaths() {
        if (dumpPath == null || dumpPath.isEmpty()) return Collections.emptyList();
        List<String> result = Arrays.stream(dumpPath.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(MAX_DUMP_PATHS)
                .collect(Collectors.toList());
        return result;
    }

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

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
@Table(name = "dump_transfer_log")
public class DumpTransferLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(nullable = false, length = 500)
    private String filename;

    /** 원격 서버의 원본 파일명 — 로컬 rename 전 식별자. scan 시 transferred 판정 키. */
    @Column(name = "remote_filename", length = 500)
    private String remoteFilename;

    @Column(name = "remote_path", length = 1000)
    private String remotePath;

    @Column(name = "transfer_status", nullable = false, length = 20)
    private String transferStatus;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}

package com.heapdump.analyzer.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
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

    /** 전송 파일 종류 — heap / core / coreexec / gclog. 2026-09-14 도입, null = 도입 이전 힙 전송(백필 없음). */
    @Column(name = "file_type", length = 10)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 전송 직후 원격 서버에서 수집한 JVM 힙 설정 스냅샷(JSON, {@code JvmHeapCapture.Capture}) — 전송 시점 기록이라 불변.
     * 후보 프로세스 전부 + 매칭 결과 + MemTotal. 힙 전송에서만 채워지고 코어 전송은 null.
     * 255 초과가 확실하므로 TEXT 명시(함정 16). 저장 내용은 allow-list 옵션·매칭 마커뿐이라 자격증명이 들어오지 않는다.
     */
    @Column(name = "jvm_info", columnDefinition = "TEXT")
    private String jvmInfo;
}

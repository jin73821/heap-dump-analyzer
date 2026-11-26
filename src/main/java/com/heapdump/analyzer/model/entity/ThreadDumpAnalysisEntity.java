package com.heapdump.analyzer.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 스레드 덤프 파일 하나의 등록·분석 요약(2026-11-26). 상세 결과(JSON)는 {@link ThreadDumpResultDetailEntity} 에 둔다.
 *
 * <p>⚠ 요약 컬럼(스냅샷 수 ~ 분석 시각)을 추가하면 {@code ThreadDumpAnalyzerService.forgetAnalysis} 에도 넣을 것 —
 * 같은 이름으로 다른 파일이 들어왔을 때 옛 결과의 흔적이 남는다.
 */
@Entity
@Table(name = "thread_dump_analysis")
@Getter
@Setter
public class ThreadDumpAnalysisEntity {

    public static final String STATUS_NOT_ANALYZED = "NOT_ANALYZED";
    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String filename;

    @Column(nullable = false, length = 20)
    private String status = STATUS_NOT_ANALYZED;

    /** 실패 코드(NOT_THREAD_DUMP·TIMEOUT·CANCELLED·OUT_OF_MEMORY·INTERRUPTED …). 성공이면 null. */
    @Column(name = "error_code", length = 40)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(nullable = false)
    private boolean compressed;

    @Column(name = "file_deleted", nullable = false)
    private boolean fileDeleted;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    /** 덤프를 받은 서버(선택 입력 — 리포트 머리말에 쓴다). */
    @Column(name = "server_name", length = 100)
    private String serverName;

    // ── 분석 요약 ───────────────────────────────────────────────
    @Column(name = "snapshot_count")
    private Integer snapshotCount;

    @Column(name = "thread_count")
    private Integer threadCount;

    @Column(name = "deadlock_count")
    private Integer deadlockCount;

    @Column(name = "blocked_max")
    private Integer blockedMax;

    @Column(name = "stuck_count")
    private Integer stuckCount;

    @Column(name = "finding_count")
    private Integer findingCount;

    @Column(length = 20)
    private String severity;

    @Column(name = "jvm_version", length = 300)
    private String jvmVersion;

    @Column(name = "jdk_major")
    private Integer jdkMajor;

    @Column(length = 100)
    private String middleware;

    @Column(name = "first_snapshot_at")
    private LocalDateTime firstSnapshotAt;

    @Column(name = "last_snapshot_at")
    private LocalDateTime lastSnapshotAt;

    @Column(name = "analysis_time_ms")
    private Long analysisTimeMs;

    // ── 힙 덤프 연계(2.7.1, 수동 연결만) ──────────────────────────
    /** 사용자가 연결한 힙 덤프(analysis_history.filename). 자동으로 채우지 않는다. */
    @Column(name = "linked_heap_filename", length = 500)
    private String linkedHeapFilename;

    @Column(name = "linked_at")
    private LocalDateTime linkedAt;

    @Column(name = "linked_by", length = 100)
    private String linkedBy;

    // ── 이어 붙이기(2.7.1) ────────────────────────────────────────
    /** 대표 행에만 — 이어 붙인 구성 파일 이름(JSON 배열, 분석 순서). 대표 자신도 들어 있다. */
    @Column(name = "member_files", columnDefinition = "TEXT")
    private String memberFiles;

    @Column(name = "member_count")
    private Integer memberCount;

    /** 구성 파일 행에만 — 속한 묶음의 대표 파일명. */
    @Column(name = "part_of", length = 500)
    private String partOf;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() { updatedAt = LocalDateTime.now(); }

    /** 이어 붙인 묶음의 대표인가(구성 파일 2개 이상). */
    public boolean isCombined() { return memberCount != null && memberCount > 1; }

    /** 화면 표시용 크기 문자열(영속 필드 아님 — 필드 접근이라 매핑되지 않는다). */
    public String getFormattedSize() {
        return fileSize == null ? "-" : com.heapdump.analyzer.util.FormatUtils.formatBytes(fileSize);
    }
}

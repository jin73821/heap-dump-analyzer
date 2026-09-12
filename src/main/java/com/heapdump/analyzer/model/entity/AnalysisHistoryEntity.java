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
@Table(name = "analysis_history")
public class AnalysisHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "original_file_size")
    private Long originalFileSize;

    @Column(name = "total_heap_size")
    private Long totalHeapSize;

    @Column(name = "used_heap_size")
    private Long usedHeapSize;

    @Column(name = "heap_usage_percent")
    private Double heapUsagePercent;

    @Column(name = "suspect_count")
    private Integer suspectCount;

    @Column(name = "total_classes")
    private Integer totalClasses;

    @Column(name = "total_objects")
    private Long totalObjects;

    @Column(name = "analysis_time_ms")
    private Long analysisTimeMs;

    private Boolean compressed;

    @Column(name = "file_deleted")
    private Boolean fileDeleted = false;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "dump_creation_time", length = 50)
    private String dumpCreationTime;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "server_name", length = 100)
    private String serverName;

    // JEUS 인스턴스/도메인 수동 편집값 — System Properties(jeus.server.name/jeus.domain.name)
    // 자동 식별이 안 되거나 수동 업로드 덤프에서 운영자가 직접 입력. 비어 있으면 자동값으로 폴백.
    @Column(name = "jeus_instance", length = 100)
    private String jeusInstance;

    @Column(name = "jeus_domain", length = 100)
    private String jeusDomain;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    // ── JVM 힙 설정 (2026-09-11) — 원격 전송 시 수집한 -Xms/-Xmx 의 "현재 진실".
    // hprof 에는 이 값이 없어 원격 서버 프로세스에서 읽는다. source 는 "누가 정했나"(auto/selected/manual),
    // flags 는 "얼마나 믿을 만한가"(estimated/restarted/container… 콤마 목록 — 화면 글자 배지). manual/selected 는
    // 재분석·재수집이 덮지 않는다(JvmHeapInfoService.mayOverwrite). saveAnalysisToDb 는 이 필드를 무조건 덮지 않는다.
    @Column(name = "jvm_xms_bytes")
    private Long jvmXmsBytes;

    @Column(name = "jvm_xmx_bytes")
    private Long jvmXmxBytes;

    @Column(name = "jvm_heap_source", length = 20)
    private String jvmHeapSource;

    @Column(name = "jvm_heap_flags", length = 120)
    private String jvmHeapFlags;

    /** allow-list 통과 JVM 옵션(공백 join, 2000자 절단) — 칩 툴팁·PDF 용. */
    @Column(name = "jvm_options", length = 2000)
    private String jvmOptions;

    @Column(name = "jvm_pid")
    private Integer jvmPid;

    @Column(name = "jvm_captured_at")
    private LocalDateTime jvmCapturedAt;

    /** 최신 캡처 사본(JSON) — 후보 선택 모달·재수집용. 분석 시 전송 로그에서 복사, 재수집 시 교체. */
    @Column(name = "jvm_info", columnDefinition = "TEXT")
    private String jvmInfo;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (analyzedAt == null) analyzedAt = LocalDateTime.now();
    }
}

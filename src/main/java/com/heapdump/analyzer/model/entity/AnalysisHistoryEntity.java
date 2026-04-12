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

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "server_name", length = 100)
    private String serverName;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

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

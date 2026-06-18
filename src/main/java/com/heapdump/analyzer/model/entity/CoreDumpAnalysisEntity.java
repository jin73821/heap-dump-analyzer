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
@Table(name = "core_dump_analysis_history")
public class CoreDumpAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "executable_name", length = 500)
    private String executableName;

    @Column(nullable = false, length = 20)
    private String status;   // NOT_ANALYZED / ANALYZING / SUCCESS / ERROR

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "executable_size")
    private Long executableSize;

    @Column(name = "crash_signal", length = 30)
    private String crashSignal;

    @Column(name = "signal_description", length = 200)
    private String signalDescription;

    @Column(name = "crash_summary", columnDefinition = "TEXT")
    private String crashSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    @Column(name = "analysis_time_ms")
    private Long analysisTimeMs;

    @Column(name = "file_deleted")
    private Boolean fileDeleted = false;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

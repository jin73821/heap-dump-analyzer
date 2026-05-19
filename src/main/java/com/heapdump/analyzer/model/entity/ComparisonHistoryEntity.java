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
@Table(name = "comparison_history",
        indexes = {
                @Index(name = "idx_ch_compared_at",  columnList = "compared_at"),
                @Index(name = "idx_ch_compared_by",  columnList = "compared_by")
        })
public class ComparisonHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "base_filename", nullable = false, length = 500)
    private String baseFilename;

    @Column(name = "target_filename", nullable = false, length = 500)
    private String targetFilename;

    @Column(name = "used_heap_delta")
    private Long usedHeapDelta;

    @Column(name = "objects_delta")
    private Long objectsDelta;

    @Column(name = "classes_delta")
    private Integer classesDelta;

    @Column(name = "suspects_delta")
    private Integer suspectsDelta;

    @Column(name = "threads_delta")
    private Integer threadsDelta;

    @Column(name = "usage_percent_delta")
    private Double usagePercentDelta;

    @Column(name = "base_suspect_count")
    private Integer baseSuspectCount;

    @Column(name = "target_suspect_count")
    private Integer targetSuspectCount;

    @Column(name = "compared_by", length = 50)
    private String comparedBy;

    @Column(name = "compared_at")
    private LocalDateTime comparedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt  == null) createdAt  = now;
        if (comparedAt == null) comparedAt = now;
    }
}

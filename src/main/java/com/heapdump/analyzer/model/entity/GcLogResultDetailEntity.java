package com.heapdump.analyzer.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * GC 로그 분석 상세 결과(JSON, {@code GcLogResult}). {@code analysis_result_detail} 을 공유하지 않는 이유:
 * 그 테이블의 {@code findAllFilenames}/{@code deleteByFilename} 이 힙 정리 흐름에서 쓰여 GC 행이 섞이면 지워지거나
 * 동명 파일과 충돌한다. LONGTEXT 명시(함정 16), 상한 4MB 는 {@code GcLogResultCodec} 이 지킨다.
 */
@Entity
@Table(name = "gc_log_result_detail")
public class GcLogResultDetailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String filename;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(name = "json_size")
    private Integer jsonSize;

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

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
        this.jsonSize = resultJson != null ? resultJson.length() : 0;
    }
    public Integer getJsonSize() { return jsonSize; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

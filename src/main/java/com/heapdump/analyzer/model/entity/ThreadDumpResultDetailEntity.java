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
 * 스레드 덤프 분석 상세 결과(JSON, {@code ThreadDumpResult}). 힙·GC 상세 테이블과 나눈 이유는 {@code GcLogResultDetailEntity} 와 같다 —
 * 다른 도메인의 정리 흐름이 파일명으로 행을 지우므로 섞이면 동명 파일끼리 충돌한다.
 * LONGTEXT 명시(함정 16), 상한 4MB 는 {@code ThreadDumpResultCodec} 이 지킨다.
 */
@Entity
@Table(name = "thread_dump_result_detail")
public class ThreadDumpResultDetailEntity {

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

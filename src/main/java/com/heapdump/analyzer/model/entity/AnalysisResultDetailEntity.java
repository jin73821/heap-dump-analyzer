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
 * 분석 상세 결과 저장 엔티티 (구 data/{filename}/result.json).
 *
 * analysis_history 는 목록/집계용 요약 수치만 갖고, 본 테이블이 analyze 화면이 필요로 하는
 * 상세 데이터(스레드/도미네이터/히스토그램/MAT HTML/systemProperties 등) 전량을 JSON 으로 보관한다.
 * 목록 조회(findAll) 마다 수 MB LOB 를 끌고 오지 않도록 analysis_history 와 **별도 테이블**로 분리한다.
 *
 * ⚠️ result_json 은 수 MB 까지 커질 수 있어 LONGTEXT 를 명시한다 (Hibernate 6 의 @Lob String →
 * tinytext 축소 함정 회피 — CLAUDE.md 함정 16 참조).
 */
@Entity
@Table(name = "analysis_result_detail")
public class AnalysisResultDetailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String filename;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    /** 저장된 JSON 문자 길이 — 용량 점검용(운영 진단). */
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
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
        this.jsonSize = resultJson != null ? resultJson.length() : 0;
    }

    public Integer getJsonSize() { return jsonSize; }
    public void setJsonSize(Integer jsonSize) { this.jsonSize = jsonSize; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

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
 * Dominator Refs 사전계산 결과 저장 엔티티 (구 data/{filename}/dominator-refs.json).
 *
 * <p>{@code analysis_result_detail} 과 **별도 테이블**인 이유: 기동 시 상세 결과는 전량 복원되지만
 * refs 는 Dominator Refs 조회 시점에만 필요한 lazy 데이터다(항목당 100~200KB). 같은 행에 두면
 * 기동 복원 {@code findAll()} 이 쓰지도 않을 LOB 를 매번 끌고 온다.
 *
 * <p>저장 JSON 구조는 사이드카 파일과 동일: {@code {version, generatedAt, topN, capPerList, refs{}}}.
 */
@Entity
@Table(name = "analysis_dominator_refs")
public class DominatorRefsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String filename;

    @Column(name = "refs_json", columnDefinition = "LONGTEXT")
    private String refsJson;

    /** refs 맵의 주소 개수 — 운영 진단용. */
    @Column(name = "address_count")
    private Integer addressCount;

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

    public String getRefsJson() { return refsJson; }
    public void setRefsJson(String refsJson) {
        this.refsJson = refsJson;
        this.jsonSize = refsJson != null ? refsJson.length() : 0;
    }

    public Integer getAddressCount() { return addressCount; }
    public void setAddressCount(Integer addressCount) { this.addressCount = addressCount; }

    public Integer getJsonSize() { return jsonSize; }
    public void setJsonSize(Integer jsonSize) { this.jsonSize = jsonSize; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

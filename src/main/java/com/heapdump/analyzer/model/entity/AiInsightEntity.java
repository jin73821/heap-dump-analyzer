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
@Table(name = "ai_insights")
public class AiInsightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 500)
    private String filename;

    /** AI 모델명 */
    @Column(length = 100)
    private String model;

    /** 심각도 (critical / warning / info) */
    @Column(length = 20)
    private String severity;

    /** LLM 응답 지연 시간 (ms) */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** 전체 인사이트 JSON 데이터 */
    @Column(name = "insight_data", columnDefinition = "MEDIUMTEXT")
    private String insightData;

    @Column(name = "analysed_at")
    private LocalDateTime analysedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (analysedAt == null) analysedAt = LocalDateTime.now();
    }
}

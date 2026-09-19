package com.heapdump.analyzer.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 최우선 알람의 공유 ACK(확인) 기록 — 한 명이 확인하면 모든 사용자의 알림 말풍선에서 빠진다.
 * alert_key = {@code domain:filename:epoch} (AlertBoardService 의 key 헬퍼가 단일 출처).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "alert_ack", indexes = @Index(name = "idx_alert_ack_acked_at", columnList = "acked_at"))
public class AlertAck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_key", nullable = false, unique = true, length = 560)
    private String alertKey;

    @Column(nullable = false, length = 10)
    private String domain;

    @Column(length = 500)
    private String filename;

    @Column(name = "acked_by", nullable = false, length = 100)
    private String ackedBy;

    @Column(name = "acked_at", nullable = false)
    private LocalDateTime ackedAt;

    @PrePersist
    protected void onCreate() {
        if (ackedAt == null) ackedAt = LocalDateTime.now();
    }
}

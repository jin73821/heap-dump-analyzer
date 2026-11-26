package com.heapdump.analyzer.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 민감정보 탐지 이력 (LLM Guard, 2026-11-26) — 1년 보관 후 자동 삭제.
 *
 * <p>⚠ <b>원문은 저장하지 않는다.</b> {@code sample} 은 일치값을 가린 ±20자 발췌이며,
 * 다른 규칙으로도 한 번 더 가린 값이다. 한 번 탐지될 때 규칙마다 1행을 쓰고 같은 탐지의 행은
 * {@code event_id} 로 묶는다. 이력을 지우는 API 는 두지 않는다(변조 방지 — 정리는 보관기간 스케줄러만).
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "llm_guard_log", indexes = {
        @Index(name = "idx_llm_guard_log_detected", columnList = "detected_at"),
        @Index(name = "idx_llm_guard_log_user_detected", columnList = "username, detected_at")
})
public class LlmGuardLog {

    public static final String DIR_INPUT = "INPUT";
    public static final String DIR_OUTPUT = "OUTPUT";

    public static final String ACT_CONFIRM_REQUESTED = "CONFIRM_REQUESTED";
    public static final String ACT_CONFIRMED = "CONFIRMED";
    public static final String ACT_BLOCKED = "BLOCKED";
    public static final String ACT_MASKED = "MASKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "username", length = 50)
    private String username;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /** INPUT | OUTPUT */
    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    /** CONFIRM_REQUESTED | CONFIRMED | BLOCKED | MASKED */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "feature", length = 30)
    private String feature;

    @Column(name = "request_uri", length = 255)
    private String requestUri;

    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_name", length = 100)
    private String ruleName;

    @Column(name = "category", length = 20)
    private String category;

    @Column(name = "match_count", nullable = false)
    private int matchCount;

    /** 일치값을 가린 ±20자 발췌. 원문 아님. */
    @Column(name = "sample", length = 500)
    private String sample;
}

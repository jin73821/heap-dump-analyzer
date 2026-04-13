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
@Table(name = "ai_chat_sessions")
public class AiChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 세션 소유자 (Spring Security username) */
    @Column(nullable = false, length = 50)
    private String username;

    /** 연결된 덤프 파일명 (null이면 일반 채팅) */
    @Column(length = 500)
    private String filename;

    /** 세션 제목 (첫 질문에서 자동 생성) */
    @Column(length = 200)
    private String title;

    /** 사용된 LLM 모델명 */
    @Column(length = 100)
    private String model;

    /** 메시지 수 (캐시) */
    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

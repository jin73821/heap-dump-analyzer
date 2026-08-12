package com.heapdump.analyzer.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 개인 메모 변경 이력 (2026-08-12).
 *
 * <p>{@code users.memo} 는 단일 컬럼 덮어쓰기라 한 번 저장하면 이전 내용을 되돌릴 방법이
 * 없었다. 이 테이블이 <b>덮어쓰기 직전 스냅샷</b>을 보관해 서버 측 복원을 가능하게 한다.
 *
 * <p>행 하나 = "그 시점까지 저장돼 있던 내용". 즉 {@link #createdAt} 은 <b>스냅샷을 뜬 시각</b>
 * (= 새 값으로 덮인 시각)이지 그 내용을 작성한 시각이 아니다.
 *
 * <p>⚠ {@code memo} 는 최대 10MB 라 {@code columnDefinition} 을 명시한다 — Hibernate 6 는
 * {@code @Lob String} 을 tinytext(255)로 축소 매핑한다(CLAUDE.md 함정 16).
 * 보관량은 {@code MemoHistoryService} 의 3중 정책(보관일수·사용자당 상한·최소 간격)이 통제한다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "memo_history", indexes = {
        // 목록 조회(사용자별 최신순)와 상한 정리가 모두 이 조합을 탄다
        @Index(name = "idx_memo_history_user_created", columnList = "username, created_at"),
        // 보관기간 만료 정리 전용 (전체 사용자 대상 스캔)
        @Index(name = "idx_memo_history_created", columnList = "created_at")
})
public class MemoHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    /** 스냅샷 당시의 메모 전문. */
    @Column(name = "memo", columnDefinition = "MEDIUMTEXT")
    private String memo;

    /** UTF-8 바이트 수 — 목록에서 본문을 읽지 않고 크기를 보여주기 위해 미리 계산해 둔다. */
    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    /** 스냅샷을 뜬 시각(= 이 내용이 다른 값으로 덮인 시각). */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 어떤 동작 직전의 스냅샷인지 — save | restore | clear. UI 배지로 노출. */
    @Column(name = "reason", nullable = false, length = 20)
    private String reason;

    public MemoHistory(String username, String memo, int byteSize, LocalDateTime createdAt, String reason) {
        this.username = username;
        this.memo = memo;
        this.byteSize = byteSize;
        this.createdAt = createdAt;
        this.reason = reason;
    }
}

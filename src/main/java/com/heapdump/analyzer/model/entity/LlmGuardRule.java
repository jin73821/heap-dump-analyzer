package com.heapdump.analyzer.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 입·출력 민감정보 탐지 규칙 (LLM Guard, 2026-11-26).
 *
 * <p>{@code match_type}:
 * <ul>
 *   <li>{@code BUILTIN} — {@code builtin_key} 의 Java 검증기({@code BuiltinDetectors})가 판정한다. 패턴은 설명용·읽기 전용.</li>
 *   <li>{@code REGEX} — 사용자 정의 정규식. named group {@code v} 가 있으면 그 그룹만 가린다.</li>
 *   <li>{@code KEYWORD} — 줄마다 단어 하나. 대소문자 무시 리터럴. 내장 키워드 규칙도 단어 목록은 편집할 수 있다.</li>
 * </ul>
 *
 * <p>내장 규칙({@code builtin_key} 있음)은 삭제할 수 없고 패턴도 바꿀 수 없다(키워드 목록 제외).
 * 시더는 {@code builtin_key} 가 없는 규칙만 INSERT 하므로 관리자가 바꾼 값은 재기동해도 보존된다.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "llm_guard_rule")
public class LlmGuardRule {

    public static final String TYPE_BUILTIN = "BUILTIN";
    public static final String TYPE_REGEX = "REGEX";
    public static final String TYPE_KEYWORD = "KEYWORD";

    public static final String ACTION_CONFIRM = "CONFIRM";
    public static final String ACTION_BLOCK = "BLOCK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 개인식별 | 개인신용 | 중요정보 | 기타 */
    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "match_type", nullable = false, length = 10)
    private String matchType;

    @Column(name = "pattern", columnDefinition = "TEXT")
    private String pattern;

    @Column(name = "builtin_key", length = 30, unique = true)
    private String builtinKey;

    @Column(name = "check_input", nullable = false)
    private boolean checkInput = true;

    /** CONFIRM | BLOCK */
    @Column(name = "input_action", nullable = false, length = 10)
    private String inputAction = ACTION_CONFIRM;

    @Column(name = "check_output", nullable = false)
    private boolean checkOutput = true;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 1000;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    public boolean isBuiltin() {
        return builtinKey != null && !builtinKey.isEmpty();
    }
}

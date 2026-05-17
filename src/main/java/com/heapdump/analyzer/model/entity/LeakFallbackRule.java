package com.heapdump.analyzer.model.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Leak Suspect 분석의 fallback 키워드 룰. KnownLibrary 매칭 실패 시 정규식 패턴을 전체 텍스트에 적용.
 * 매칭 결과는 enrichExplanation()와 결합되어 동적 컨텍스트(클래스명/비율 등)를 prepend.
 */
@Entity
@Table(name = "leak_fallback_rule", indexes = {
        @Index(name = "idx_fb_priority", columnList = "enabled,priority")
})
public class LeakFallbackRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "category", nullable = false, length = 80)
    private String category;

    @Lob
    @Column(name = "pattern_regex", nullable = false)
    private String patternRegex;

    @Lob
    @Column(name = "explanation_tpl", nullable = false)
    private String explanationTpl;

    @Lob
    @Column(name = "advice_tpl", nullable = false)
    private String adviceTpl;

    @Column(name = "severity_hint", length = 16)
    private String severityHint;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "priority", nullable = false)
    private int priority = 1000;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPatternRegex() { return patternRegex; }
    public void setPatternRegex(String patternRegex) { this.patternRegex = patternRegex; }
    public String getExplanationTpl() { return explanationTpl; }
    public void setExplanationTpl(String explanationTpl) { this.explanationTpl = explanationTpl; }
    public String getAdviceTpl() { return adviceTpl; }
    public void setAdviceTpl(String adviceTpl) { this.adviceTpl = adviceTpl; }
    public String getSeverityHint() { return severityHint; }
    public void setSeverityHint(String severityHint) { this.severityHint = severityHint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

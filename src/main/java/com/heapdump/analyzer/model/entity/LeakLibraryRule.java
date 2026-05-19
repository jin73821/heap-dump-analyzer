package com.heapdump.analyzer.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Leak Suspect 분석에서 알려진 라이브러리(클래스 prefix 기반) 룰.
 * MAT 텍스트의 className/accumulatorClass/classLoader 중 하나가 prefix로 시작하면 매칭.
 */
@Entity
@Table(name = "leak_library_rule", indexes = {
        @Index(name = "idx_lib_priority", columnList = "enabled,priority"),
        @Index(name = "idx_lib_prefix", columnList = "prefix")
})
public class LeakLibraryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prefix", nullable = false, length = 200)
    private String prefix;

    @Column(name = "library_name", nullable = false, length = 80)
    private String libraryName;

    @Column(name = "category", nullable = false, length = 80)
    private String category;

    @Column(name = "severity_hint", length = 16)
    private String severityHint;

    @Column(name = "explanation_tpl", nullable = false, columnDefinition = "TEXT")
    private String explanationTpl;

    @Column(name = "advice_tpl", nullable = false, columnDefinition = "TEXT")
    private String adviceTpl;

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
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getLibraryName() { return libraryName; }
    public void setLibraryName(String libraryName) { this.libraryName = libraryName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSeverityHint() { return severityHint; }
    public void setSeverityHint(String severityHint) { this.severityHint = severityHint; }
    public String getExplanationTpl() { return explanationTpl; }
    public void setExplanationTpl(String explanationTpl) { this.explanationTpl = explanationTpl; }
    public String getAdviceTpl() { return adviceTpl; }
    public void setAdviceTpl(String adviceTpl) { this.adviceTpl = adviceTpl; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

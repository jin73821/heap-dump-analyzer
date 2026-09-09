package com.heapdump.analyzer.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 사용자가 등록한 지식 문서(Markdown). Chroma 문서 id = {@code user_doc:{originId}}.
 *
 * <p>⚠ {@code enabled} 는 <b>색인 시점</b> 스위치다 — 끄면 다음 색인 대상에서 빠질 뿐,
 * 이미 색인된 청크는 남아 계속 검색에 쓰인다(upsert 는 삭제하지 않는다).
 * 즉시 반영되는 검색 시점 스위치는 {@code ChromaSearchService.EXCLUDED_SOURCE_TYPES} 뿐이다.
 *
 * <p>⚠ 함정 16 — 긴 문자열은 {@code length}/{@code columnDefinition} 명시.
 * 본문은 사용자 입력이라 상한이 없어 MEDIUMTEXT(16MB) + 서비스단 1MB 검증.
 */
@Entity
@Table(name = "rag_knowledge_doc", indexes = {
        @Index(name = "idx_rkd_hash",  columnList = "content_hash"),
        @Index(name = "idx_rkd_title", columnList = "title_norm")
})
public class RagKnowledgeDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "origin_id", nullable = false, unique = true, length = 190)
    private String originId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "source", length = 500)
    private String source;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity = "info";

    @Column(name = "doc_date", length = 10)
    private String docDate;

    @Column(name = "synthetic", nullable = false)
    private boolean synthetic = false;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "title_norm", nullable = false, length = 300)
    private String titleNorm;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getOriginId() { return originId; }
    public void setOriginId(String v) { originId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { category = v; }
    public String getTags() { return tags; }
    public void setTags(String v) { tags = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { severity = v; }
    public String getDocDate() { return docDate; }
    public void setDocDate(String v) { docDate = v; }
    public boolean isSynthetic() { return synthetic; }
    public void setSynthetic(boolean v) { synthetic = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v; }
    public String getTitleNorm() { return titleNorm; }
    public void setTitleNorm(String v) { titleNorm = v; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String v) { originalFilename = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { updatedBy = v; }
}

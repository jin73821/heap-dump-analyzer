package com.heapdump.analyzer.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 학습 코퍼스 1행 — {@code rag-data/rag-knowledge-v2.csv} 의 9컬럼을 그대로 담는다.
 *
 * <p><b>⚠ {@code docId} 는 재생성하지 말 것.</b> Chroma 문서 id 가 {@code csv:{docId}} 이고,
 * 평가셋({@code rag-data/eval/queries.jsonl}) 의 정답 id 8건이 이 값에 묶여 있다.
 * 값이 바뀌면 기준선(Recall@10 0.881)이 무의미해지고 옛 청크는 고아가 된다.
 *
 * <p><b>⚠ {@code sortOrder} 는 장식이 아니다.</b> 원본 CSV 는 id 정렬이 아니라
 * ({@code oom-…→leak-…→java-spring-…}) 작성 순서다. 이 값이 없으면 내보내기가 원본과
 * 바이트 동일해질 수 없고, 그러면 DB 전환이 안전한지 판정할 방법이 사라진다.
 *
 * <p>⚠ 함정 16 — Hibernate 6 는 {@code @Lob String} 을 tinytext(255)로 만든다.
 * 255바이트를 넘길 수 있는 컬럼은 전부 {@code length}/{@code columnDefinition} 을 명시한다.
 */
@Entity
@Table(name = "rag_learning_doc", indexes = {
        @Index(name = "idx_rld_hash",  columnList = "content_hash"),
        @Index(name = "idx_rld_title", columnList = "title_norm"),
        @Index(name = "idx_rld_order", columnList = "sort_order")
})
public class RagLearningDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CSV 의 {@code id}. Chroma 문서 id = {@code csv:{docId}}. 190 = utf8mb4 UNIQUE 인덱스 안전선. */
    @Column(name = "doc_id", nullable = false, unique = true, length = 190)
    private String docId;

    @Column(name = "category", length = 80)
    private String category;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    /** TEXT = 65,535 <b>바이트</b>(한글 약 21,800자). 서비스가 20,000자로 사전 검증한다. */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 콤마 결합 문자열. ⚠ 리스트로 모델링 금지 — Chroma 메타는 스칼라만 받는다. */
    @Column(name = "tags", length = 500)
    private String tags;

    @Column(name = "source", length = 500)
    private String source;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity = "info";

    /** CSV 의 {@code created_at}(작성일). ⚠ 행 생성 시각인 {@link #createdAt} 과 다르다. */
    @Column(name = "doc_date", length = 10)
    private String docDate;

    /** true 면 검색에서 제외된다(가상 사례). */
    @Column(name = "synthetic", nullable = false)
    private boolean synthetic = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
    public String getDocId() { return docId; }
    public void setDocId(String v) { docId = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { category = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
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
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { sortOrder = v; }
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

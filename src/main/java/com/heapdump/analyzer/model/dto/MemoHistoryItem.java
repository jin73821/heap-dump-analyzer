package com.heapdump.analyzer.model.dto;

import java.time.LocalDateTime;

/**
 * 메모 변경 이력 목록 항목 — <b>본문 전문을 담지 않는다</b>.
 * 10MB 가능한 LOB 를 목록마다 끌고 오지 않기 위해 미리보기(preview)만 싣고,
 * 전문은 상세 조회(GET /api/account/memo/history/{id})에서만 읽는다.
 */
public class MemoHistoryItem {

    private Long          id;
    private LocalDateTime createdAt;
    private int           byteSize;
    private String        reason;   // save | restore | clear
    private String        preview;

    public MemoHistoryItem() { }

    public MemoHistoryItem(Long id, LocalDateTime createdAt, int byteSize, String reason, String preview) {
        this.id = id;
        this.createdAt = createdAt;
        this.byteSize = byteSize;
        this.reason = reason;
        this.preview = preview;
    }

    public Long          getId()        { return id; }
    public void          setId(Long v)  { id = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void          setCreatedAt(LocalDateTime v) { createdAt = v; }
    public int           getByteSize()  { return byteSize; }
    public void          setByteSize(int v) { byteSize = v; }
    public String        getReason()    { return reason; }
    public void          setReason(String v) { reason = v; }
    public String        getPreview()   { return preview; }
    public void          setPreview(String v) { preview = v; }
}

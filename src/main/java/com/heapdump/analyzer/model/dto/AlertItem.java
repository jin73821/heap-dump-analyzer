package com.heapdump.analyzer.model.dto;

import lombok.Data;

/** 최우선 알람 1건 (알림 말풍선·현황판 공용). */
@Data
public class AlertItem {
    private String key;
    /** heap | core | gclog */
    private String domain;
    private String filename;
    private String serverName;
    private String severity;
    /** HEAP_OOM | HEAP_CRITICAL_SUSPECT | CORE_AI_CRITICAL | CORE_FATAL_SIGNAL | GC_CRITICAL */
    private String reasonCode;
    private String title;
    private String detail;
    private long occurredAtEpoch;
    private String occurredAtLabel;
    private String link;
    private boolean fileDeleted;
    private boolean acked;
    private String ackedBy;
    private String ackedAtLabel;
}

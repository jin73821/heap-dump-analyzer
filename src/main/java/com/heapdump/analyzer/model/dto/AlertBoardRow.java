package com.heapdump.analyzer.model.dto;

import lombok.Data;

/** 현황판·대시보드 탐지 현황의 분석 1건. */
@Data
public class AlertBoardRow {
    private String domain;
    private String filename;
    private String serverName;
    private String status;
    /** Critical | High | Medium | Low | null(판정 없음) */
    private String severity;
    /** 핵심 신호 — OOM 종류 / 크래시 시그널 / 압박 Full GC */
    private String keySignal;
    private String analyzedAtLabel;
    private long analyzedAtEpoch;
    private String link;
    private boolean fileDeleted;
}

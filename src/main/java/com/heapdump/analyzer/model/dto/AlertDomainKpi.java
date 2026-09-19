package com.heapdump.analyzer.model.dto;

import lombok.Data;

/** 현황판 도메인별 KPI — 분석 상태 + 파일 단위 심각도 건수. */
@Data
public class AlertDomainKpi {
    private int total;
    private int success;
    private int error;
    private int analyzing;
    private int notAnalyzed;
    private int critical;
    private int high;
    private int medium;
    private int low;
}

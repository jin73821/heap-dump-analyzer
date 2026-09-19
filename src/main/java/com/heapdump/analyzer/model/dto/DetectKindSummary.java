package com.heapdump.analyzer.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 대시보드 탐지 현황의 코어덤프·GC 로그 요약 — 건수는 파일 단위다(힙은 suspect 단위). */
@Data
public class DetectKindSummary {
    private int critical;
    private int high;
    private int medium;
    private int low;
    private List<AlertBoardRow> items = new ArrayList<>();

    public int getTotal() { return critical + high + medium + low; }
}

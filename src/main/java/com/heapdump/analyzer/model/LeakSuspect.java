package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MAT Leak Suspects 리포트에서 추출한 메모리 누수 의심 항목
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeakSuspect {

    /** 의심 항목 제목 (예: "Suspect #1") */
    private String title;

    /** 의심 항목 상세 설명 */
    private String description;

    /** 누수 유형 카테고리 (예: "ClassLoader 누수") */
    private String category;

    /** 한국어 설명 */
    private String explanation;

    /** 한국어 권장 조치 (HTML 형식) */
    private String advice;

    /** 심각도: "critical" / "high" / "medium" / "low" */
    private String severity;

    public LeakSuspect(String title, String description) {
        this.title = title;
        this.description = description;
    }
}

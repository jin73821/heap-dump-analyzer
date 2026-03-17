package com.heapdump.analyzer.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MAT Leak Suspects 리포트에서 추출한 메모리 누수 의심 항목
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeakSuspect {

    /**
     * 의심 항목 제목 (예: "Suspect #1")
     */
    private String title;

    /**
     * 의심 항목 상세 설명
     */
    private String description;
}

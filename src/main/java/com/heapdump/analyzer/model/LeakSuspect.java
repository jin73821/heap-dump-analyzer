package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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

    /** stacktrace 페이지 경로 (ZIP 내 상대 경로, 예: "pages/25.html") */
    private String stacktracePage;

    /** stacktrace with local variables 페이지 경로 */
    private String stacktraceLocalVarsPage;

    /** MAT 리포트의 Keywords 섹션에서 추출한 정확한 FQCN 리스트 (클래스/클래스로더/배열) */
    private List<String> keywords = new ArrayList<>();

    public LeakSuspect(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /** UI 전용: explanation 에서 "관련 키워드: ..." 라인을 제외한 본문만 반환. 키워드는 별도 chip 영역에서 렌더되므로 본문 중복을 피한다. */
    @JsonIgnore
    public String getExplanationBody() {
        if (explanation == null) return null;
        int i = explanation.indexOf("\n\n관련 키워드:");
        if (i < 0) i = explanation.indexOf("관련 키워드:");
        return i >= 0 ? explanation.substring(0, i).trim() : explanation;
    }
}

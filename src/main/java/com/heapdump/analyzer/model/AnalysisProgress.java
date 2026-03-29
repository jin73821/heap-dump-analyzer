package com.heapdump.analyzer.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MAT CLI 분석 진행 상황 모델
 * SSE(Server-Sent Events)로 클라이언트에 전달됩니다.
 */
@Data
@NoArgsConstructor
public class AnalysisProgress {

    public enum Status {
        QUEUED,      // 대기 중
        RUNNING,     // 분석 중
        PARSING,     // ZIP 파싱 중
        COMPLETED,   // 완료
        ERROR        // 오류
    }

    /** 파일명 */
    private String filename;

    /** 현재 상태 */
    private Status status;

    /** 진척도 0~100 */
    private int percent;

    /** 현재 단계 메시지 */
    private String message;

    /** MAT CLI 로그 (실시간 누적) */
    private String logLine;

    /** 에러 메시지 */
    private String errorMessage;

    /** 분석 완료 후 결과 페이지 URL */
    private String resultUrl;

    /** 큐 대기 위치 (1 = 다음 실행 예정) */
    private int queuePosition;

    /** 현재 분석 중인 파일명 */
    private String currentAnalysis;

    /** MAT CLI 리포트 단계 (overview, top_components, suspects) */
    private String reportPhase;

    public static AnalysisProgress queued(String filename, int queuePosition, String currentAnalysis) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename = filename;
        p.status   = Status.QUEUED;
        p.percent  = 0;
        p.queuePosition = queuePosition;
        p.currentAnalysis = currentAnalysis;
        p.message  = "분석 대기 중... (대기 순서: " + queuePosition + "번째)";
        return p;
    }

    public static AnalysisProgress step(String filename, int percent, String message) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename = filename;
        p.status   = Status.RUNNING;
        p.percent  = percent;
        p.message  = message;
        return p;
    }

    public static AnalysisProgress log(String filename, int percent, String logLine) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename = filename;
        p.status   = Status.RUNNING;
        p.percent  = percent;
        p.message  = "MAT CLI 실행 중...";
        p.logLine  = logLine;
        return p;
    }

    public static AnalysisProgress reportLog(String filename, int percent, String logLine,
                                              String reportPhase, String message) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename    = filename;
        p.status      = Status.RUNNING;
        p.percent     = percent;
        p.logLine     = logLine;
        p.reportPhase = reportPhase;
        p.message     = message != null ? message : "MAT CLI 실행 중...";
        return p;
    }

    public static AnalysisProgress parsing(String filename, int percent, String message) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename = filename;
        p.status   = Status.PARSING;
        p.percent  = percent;
        p.message  = message;
        return p;
    }

    public static AnalysisProgress completed(String filename, String resultUrl) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename  = filename;
        p.status    = Status.COMPLETED;
        p.percent   = 100;
        p.message   = "분석 완료!";
        p.resultUrl = resultUrl;
        return p;
    }

    /** 경고 메시지 (분석은 계속 진행) */
    private String warningMessage;

    public static AnalysisProgress warning(String filename, String warningMessage) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename       = filename;
        p.status         = Status.RUNNING;
        p.percent        = 8;
        p.message        = "경고 확인 중...";
        p.warningMessage = warningMessage;
        return p;
    }

    public static AnalysisProgress error(String filename, String errorMessage) {
        AnalysisProgress p = new AnalysisProgress();
        p.filename     = filename;
        p.status       = Status.ERROR;
        p.percent      = 0;
        p.message      = "분석 실패";
        p.errorMessage = errorMessage;
        return p;
    }
}

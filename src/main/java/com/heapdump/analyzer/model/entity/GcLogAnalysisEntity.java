package com.heapdump.analyzer.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GC 로그 분석 이력 + 요약 + 덤프 매칭 상태 (2026-09-14). 상세 결과 JSON 은 {@link GcLogResultDetailEntity} 에 따로 둔다
 * (목록 조회마다 LOB 를 끌고 오지 않기 위해 — analysis_history / analysis_result_detail 과 같은 분리).
 *
 * <p>매칭 컬럼 규약: {@code match_source} 가 {@code manual} 이면 자동 매칭이 절대 덮지 않는다 — 수동 <b>해제</b>
 * ({@code manual} + {@code matched_dump_filename=null})도 포함이다. {@code /rematch} 만 예외.
 */
@Data
@Entity
@Table(name = "gc_log_analysis")
public class GcLogAnalysisEntity {

    public static final String STATUS_NOT_ANALYZED = "NOT_ANALYZED";
    public static final String STATUS_ANALYZING = "ANALYZING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";

    public static final String MATCH_NONE = "none";
    public static final String MATCH_AUTO = "auto";
    public static final String MATCH_MANUAL = "manual";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로컬 파일명(dumpfiles/ 기준). */
    @Column(nullable = false, unique = true, length = 500)
    private String filename;

    @Column(nullable = false, length = 20)
    private String status = STATUS_NOT_ANALYZED;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(nullable = false)
    private boolean compressed;

    @Column(name = "file_deleted", nullable = false)
    private boolean fileDeleted;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    // ── 출처 ────────────────────────────────────────────────────
    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "server_name", length = 100)
    private String serverName;

    @Column(name = "remote_path", length = 1000)
    private String remotePath;

    /**
     * 인스턴스명 <b>수동 입력값</b>(2026-09-14). null 이면 연결된 힙 덤프의 Instance 로 폴백한다 — 연결은 바뀔 수 있어
     * 덤프 쪽 값은 여기에 복사하지 않고 조회 시점에 읽는다({@code GcLogInstanceService}). 길이는 힙의 jeus_instance 와 같다.
     */
    @Column(name = "instance_name", length = 100)
    private String instanceName;

    /** 전송 시 원격 stat mtime — 절대 시각 없는 로그의 종료 시각 근거. */
    @Column(name = "remote_mtime")
    private LocalDateTime remoteMtime;

    /** 전송 직후 JVM 수집 스냅샷(JvmHeapCapture.Capture JSON, gclog 매칭 결과 포함). */
    @Column(name = "jvm_info", columnDefinition = "TEXT")
    private String jvmInfo;

    // ── 분석 요약 ────────────────────────────────────────────────
    @Column(name = "log_format", length = 20)
    private String logFormat;

    @Column(length = 30)
    private String collector;

    @Column(name = "jdk_version", length = 50)
    private String jdkVersion;

    @Column(name = "log_start")
    private LocalDateTime logStart;

    @Column(name = "log_end")
    private LocalDateTime logEnd;

    /** absolute / mtime / none */
    @Column(name = "time_source", length = 10)
    private String timeSource;

    @Column(name = "event_count")
    private Integer eventCount;

    @Column(name = "full_gc_count")
    private Integer fullGcCount;

    @Column(name = "findings_count")
    private Integer findingsCount;

    @Column(name = "max_pause_ms")
    private Double maxPauseMs;

    @Column(name = "p99_pause_ms")
    private Double p99PauseMs;

    @Column(name = "throughput_pct")
    private Double throughputPct;

    @Column(name = "max_heap_bytes")
    private Long maxHeapBytes;

    @Column(length = 10)
    private String severity;

    // ── 덤프 매칭 ────────────────────────────────────────────────
    @Column(name = "matched_dump_filename", length = 500)
    private String matchedDumpFilename;

    @Column(name = "match_source", length = 10)
    private String matchSource = MATCH_NONE;

    @Column(name = "match_reason", length = 200)
    private String matchReason;

    /** 후보 JSON — [{dumpFilename, score, reasons[], dumpCreationTime, serverName}] ≤10. */
    @Column(name = "match_candidates", columnDefinition = "TEXT")
    private String matchCandidates;

    @Column(name = "matched_at")
    private LocalDateTime matchedAt;

    @Column(name = "analysis_time_ms")
    private Long analysisTimeMs;

    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = STATUS_NOT_ANALYZED;
        if (matchSource == null) matchSource = MATCH_NONE;
    }

    public boolean isManualMatch() { return MATCH_MANUAL.equals(matchSource); }

    /** 화면용 크기 문자열 — 템플릿에서 T(FormatUtils) 정적 접근이 제한되는 속성 컨텍스트가 있어 여기서 만든다. */
    public String getFormattedSize() {
        return fileSize == null ? "-" : com.heapdump.analyzer.util.FormatUtils.formatBytes(fileSize);
    }
}

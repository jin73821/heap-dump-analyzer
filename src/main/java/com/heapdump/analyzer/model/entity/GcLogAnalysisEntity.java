package com.heapdump.analyzer.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
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

    /** 메모리 압박(HEAP_PRESSURE) Full GC 수 — 명시적 호출·Metaspace·GCLocker 제외(2026-09-16). null = 분석 전 또는 분류 이전 옛 결과. */
    @Column(name = "pressure_full_gc_count")
    private Integer pressureFullGcCount;

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

    // ── 분석명 (2026-09-20) ──────────────────────────────────────
    /**
     * 화면에 보이는 분석 이름 {@code 호스트네임_인스턴스명_일자}. <b>표시용일 뿐 키가 아니다</b> — 키는 언제나
     * {@code filename} 이고, 같은 이름이 둘 이상이면 {@code _2} 가 붙는다({@code GcLogNameService}).
     */
    @Column(name = "analysis_name", length = 255)
    private String analysisName;

    /** 분석명의 일자 = <b>로그 시작일</b>. 분석 전에는 전송 mtime·업로드일로 잠정 표기하고 분석 성공 시 확정한다. */
    @Column(name = "analysis_date")
    private LocalDate analysisDate;

    // ── 이어 붙이기 (2026-09-20) ─────────────────────────────────
    /**
     * 이어 붙인 구성 파일 목록 JSON {@code [{"f":이름,"size":바이트,"mtime":epoch초}]} — <b>병합 순서</b>이고
     * 대표(이 행) 자신도 포함한다. 크기·mtime 스냅샷은 구성 파일이 바뀌었는지 판정하는 근거다.
     */
    @Column(name = "member_files", columnDefinition = "TEXT")
    private String memberFiles;

    @Column(name = "member_count")
    private Integer memberCount;

    /**
     * 구성 파일 크기 합. ⚠ {@code fileSize} 에 넣지 말 것 — {@code listExistingFiles()} 가 디스크 크기와 달라진 것을
     * "다른 파일"로 보고 {@code forgetAnalysis} 를 돌려 결과가 조용히 사라진다(함정 63).
     */
    @Column(name = "members_bytes")
    private Long membersBytes;

    /** 이 파일이 구성 파일로 들어간 대표 분석의 filename. 대표 자신은 null. */
    @Column(name = "part_of", length = 500)
    private String partOf;

    /**
     * 덤프 매칭에만 쓰는 시간창 = <b>대표 파일 자신의 구간</b>. 이어 붙여 범위가 넓어져도 매칭 동작이 변하지 않게
     * 화면용 {@code logStart/logEnd} 와 분리한다 — 넓은 범위는 후보를 전부 strong 으로 만들어 자동 연결을 지운다(함정 64).
     */
    @Column(name = "match_window_start")
    private LocalDateTime matchWindowStart;

    @Column(name = "match_window_end")
    private LocalDateTime matchWindowEnd;

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

    /** 이어 붙인 분석인가(구성 파일 2개 이상). */
    public boolean isCombined() { return memberCount != null && memberCount > 1; }

    /** 목록·제목에 쓸 이름 — 분석명이 없으면 파일명. */
    public String getDisplayName() { return analysisName == null || analysisName.isBlank() ? filename : analysisName; }

    /** 화면용 크기 문자열 — 템플릿에서 T(FormatUtils) 정적 접근이 제한되는 속성 컨텍스트가 있어 여기서 만든다. */
    public String getFormattedSize() {
        return fileSize == null ? "-" : com.heapdump.analyzer.util.FormatUtils.formatBytes(fileSize);
    }
}

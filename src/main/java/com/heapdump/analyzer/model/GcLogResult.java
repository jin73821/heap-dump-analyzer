package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GC 로그 분석 결과 — {@code gc_log_result_detail.result_json} 에 통째로 저장되고 결과 화면·힙 analyze 패널·LLM 프롬프트가 읽는다.
 * 시계열은 다운샘플(≤2000점)·이벤트 표는 상한(Full/이상 전부 + 앞뒤 2500) 이라 크기가 로그 길이에 비례하지 않는다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GcLogResult {

    public static final int SCHEMA = 1;

    private int schema = SCHEMA;
    private Meta meta = new Meta();
    private Kpi kpi = new Kpi();
    private PauseStats pauseStats = new PauseStats();
    private PauseStats youngPauseStats = new PauseStats();
    private PauseStats fullPauseStats = new PauseStats();
    private Counts counts = new Counts();
    private Trend trend = new Trend();
    private Series series = new Series();
    private List<EventRow> events = new ArrayList<>();
    private List<Finding> findings = new ArrayList<>();
    private RawSample rawSample = new RawSample();
    private List<String> jvmOptions = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        private String format;          // UNIFIED / JDK8
        private String collector;       // G1 / Parallel / CMS / Serial / ZGC / Shenandoah / unknown
        private String jdkVersion;
        private String decorations;
        private String timeSource;      // absolute / mtime / none
        private Long logStartEpochMs;
        private Long logEndEpochMs;
        private Double firstUptimeSec;
        private Double lastUptimeSec;
        private Double durationSec;
        private long lines;
        private long bytes;
        private boolean truncated;      // 상한 도달·미종결 폐기 등
        private int droppedIncomplete;
        private int truncatedLines;
        private Long regionSizeBytes;
        private Long heapMaxBytes;
        /** 로그에 PermGen 라벨이 있었다(JDK 7 이하) — 화면·프롬프트가 metaspace* 값을 PermGen 으로 부른다. null = Metaspace/미상. */
        private Boolean permGen;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kpi {
        private int eventCount;
        private int pauseCount;
        private int youngCount;
        private int mixedCount;
        private int fullCount;
        private int concurrentCount;
        private Double throughputPct;
        private Double allocationRateMbPerSec;
        private Double promotionRateMbPerSec;
        private Double fullGcPerHour;
        private int maxConsecutiveFull;
        private Long maxHeapTotalBytes;
        private Long maxHeapAfterBytes;
        private Long lastHeapAfterBytes;
        private Double maxOverheadPct10m;
        private Double maxOverheadWindowStartSec;
        private Long metaspaceFirstBytes;
        private Long metaspaceLastBytes;
        private Long metaspaceMaxTotalBytes;
        private Integer maxHumongousRegions;
        private int sysTimeHighCount;
        private int cpuStarvationCount;
        private int systemGcCount;
        private String severity;        // 소견 최대 심각도
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PauseStats {
        private int count;
        private double totalMs;
        private Double avgMs;
        private Double maxMs;
        private Double p50Ms;
        private Double p95Ms;
        private Double p99Ms;
        private boolean approximate;
        private Double maxAtUptimeSec;
        private Long maxAtEpochMs;
        private Integer maxAtLine;
        private String maxDescription;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Counts {
        private Map<String, Integer> byType = new LinkedHashMap<>();
        private Map<String, Integer> byCause = new LinkedHashMap<>();
        private Map<String, Integer> byFlag = new LinkedHashMap<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Trend {
        /** Full GC 직후 힙 사용량 점 — [uptimeSec, afterBytes]. Full 이 없으면 Remark/Mixed 직후 값이고 {@code basis} 가 말한다. */
        private List<double[]> afterPoints = new ArrayList<>();
        private String basis;            // full / remark / mixed / none
        private Double slopeMbPerHour;
        private Double r2;
        private Double firstAfterBytes;
        private Double lastAfterBytes;
        /** 회귀에 실제로 쓴 점 수 — 기동 워밍업 제외 후(2026-09-14). afterPoints 는 차트용 전체 점. */
        private Integer pointsUsed;
        /** 기동 직후(uptime < 300s)라 회귀에서 뺀 점 수. */
        private int excludedWarmup;
        /** 회귀에 쓴 점들의 시간 폭(초). 600s 미만이면 기울기를 계산하지 않는다. */
        private Double spanSec;
        /** 기울기를 계산하지 않은 이유(사람이 읽는 문구). 계산했으면 null. */
        private String note;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Series {
        private List<Double> uptimeSec = new ArrayList<>();
        private List<Long> heapBefore = new ArrayList<>();
        private List<Long> heapAfter = new ArrayList<>();
        private List<Long> heapTotal = new ArrayList<>();
        private List<Double> pauseMs = new ArrayList<>();
        private List<String> type = new ArrayList<>();
        private int mergeFactor = 1;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventRow {
        private int seq;
        private int line;
        private Long tsEpochMs;
        private Double uptimeSec;
        private String type;
        private String cause;
        private Double pauseMs;
        private boolean concurrent;
        private Long heapBefore;
        private Long heapAfter;
        private Long heapTotal;
        private Long oldAfter;
        private Long metaAfter;
        private List<String> flags = new ArrayList<>();
        private Double userSec;
        private Double sysSec;
        private Double realSec;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Finding {
        private String code;
        private String severity;         // Critical / High / Medium / Low / Info
        private String title;
        private String detail;
        private String advice;
        private Map<String, Object> evidence = new LinkedHashMap<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawSample {
        private List<String> head = new ArrayList<>();
        private List<String> tail = new ArrayList<>();
        private List<String> aroundMaxPause = new ArrayList<>();
        private Integer aroundMaxPauseFirstLine;
    }
}

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
    /**
     * Full GC 분류·메모리 압박 요약(2026-09-16). ⚠ {@code = new} 로 초기화하지 말 것 — 이 필드가 없는 옛 JSON 은 null 이어야
     * 화면이 "옛 결과(재분석 안내)"와 "압박 Full GC 0회"를 구분한다(함정 58).
     */
    private FullGcSummary fullGcSummary;

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
        /** System.gc() <b>호출</b> 수 — Parallel 은 호출 1회를 Young+Full 두 이벤트로 남기므로 짝지어 한 번만 센다(2026-09-15). */
        private int systemGcCount;
        /** 명시적 원인(System.gc()·힙 덤프·jmap·jcmd)으로 일어난 Full GC 수. */
        private int explicitFullCount;
        /** 명시적 Full 을 뺀 시간당 Full GC — 메모리 압박 판정·경고색 기준. 기간을 모르면 null. */
        private Double pressureFullGcPerHour;
        /** System.gc() 호출 간격 중앙값(초) — 간격이 규칙적일 때만(3개 이상·80% 가 ±5% 이내). */
        private Double systemGcIntervalSec;
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
        /** 기울기를 계산하지 않은 이유, 또는 계산했지만 누수 신호로 보지 않은 이유(사람이 읽는 문구). */
        private String note;
        /**
         * 누수 신호로 볼 만한 증가인가 — 기울기·R² 에 더해 실질 증가량(≥ max(32MB, 최대 힙 2%))과 최대 힙 90% 도달 예상(≤ 30일)을
         * 모두 넘겨야 true(2026-09-15). 기울기를 못 냈으면 null. 필드가 없는 옛 결과는 화면이 종전 규칙(slope&gt;0·R²&gt;0.5)으로 판단한다.
         */
        private Boolean significant;
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
        /**
         * 명시적 호출의 Full 단계 행에만 — 같은 호출의 직전 Young 수집 전 힙(Parallel ScavengeBeforeFullGC). Full 단계만 보면
         * 해제량이 미미해 보이는데(Young 에서 살아남은 객체를 Old 로 옮길 뿐) 호출 전체로는 이만큼 회수했다는 근거다.
         */
        private Long callHeapBefore;
        /** FULL 행만 — EXPLICIT / METASPACE / GC_LOCKER / HEAP_PRESSURE / OTHER({@code FullGcKind}). 옛 결과·비-FULL 행은 null. */
        private String fullKind;
    }

    /**
     * Full GC 를 원인별로 나눈 요약 + <b>메모리 압박(HEAP_PRESSURE)</b> Full GC 의 회수율·직후 점유율·간격 추세.
     * 명시적 호출(System.gc() 등)은 회수율 표본에서 뺀다 — Full 단계만 보면 회수가 미미해 보이기 때문이다(함정 57).
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FullGcSummary {
        private int total;                                                   // == kpi.fullCount
        private Map<String, Integer> byKind = new LinkedHashMap<>();          // 5 종 전부(0 포함), FullGcKind 순서
        private int heapPressureCount;
        private Double heapPressurePerHour;                                  // 기간을 모르면 null
        private Map<String, Integer> pressureByCause = new LinkedHashMap<>(); // HEAP_PRESSURE 만, 내림차순 ≤12, 원인 없음 = "(원인 미기록)"
        private int maxConsecutivePressure;
        private Double firstAtSec;
        private Double lastAtSec;
        private Integer lastLine;
        private Long lastBeforeBytes;
        private Long lastAfterBytes;
        private Long lastTotalBytes;
        /** 마지막 압박 Full GC 직후 사용량 / 그 시점 committed 총량. */
        private Double lastAfterRatio;
        /** 마지막 압박 Full GC 직후 사용량 / 최대 힙(-Xmx, 없으면 관측 최대 총량). */
        private Double lastAfterCapRatio;
        private Double maxAfterRatio;
        /** before·after 가 모두 있고 before > 0 인 압박 Full GC 수(회수율 표본). */
        private int reclaimSamples;
        private Double reclaimPctMin;
        private Double reclaimPctMedian;
        private Double reclaimPctLast;
        /** 회수율 20% 미만 압박 Full GC 수. */
        private int lowReclaimCount;
        /** 직후 점유율(after/total) 85% 이상 압박 Full GC 수. */
        private int highOccupancyCount;
        private Integer intervalCount;
        private Double intervalMedianFirstHalfSec;
        private Double intervalMedianSecondHalfSec;
        /** 뒤 절반 간격 중앙값이 앞 절반의 절반 이하면 true. 간격이 6개 미만이면 판단하지 않아 null. */
        private Boolean intervalShrinking;
        /** 압박 Full GC 만 — [x, heapAfter, heapBefore], ≤500 점(차트 ▲ 표시용). */
        private List<double[]> points = new ArrayList<>();
        /** 회수율이 가장 낮은 압박 Full GC ≤5 건. */
        private List<FullGcSample> worstReclaim = new ArrayList<>();
        private String note;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FullGcSample {
        private int line;
        private Double uptimeSec;
        private String cause;
        private Long heapBefore;
        private Long heapAfter;
        private Long heapTotal;
        private Double reclaimPct;
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

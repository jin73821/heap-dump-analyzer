package com.heapdump.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 스레드 덤프 분석 결과 — {@code thread_dump_result_detail.result_json} 에 통째로 저장되고 결과 화면·PDF·LLM 프롬프트가 읽는다.
 *
 * <p>크기를 스레드 수 × 스냅샷 수에 비례하지 않게 하려고 <b>사전화</b>한다: 프레임 문자열은 {@link #frames},
 * 스택은 {@link #stacks}(프레임 인덱스 열), 스레드 이름은 {@link #names} 에 한 번씩만 두고
 * {@link Track}(스냅샷을 가로지르는 스레드 하나)이 인덱스로 참조한다.
 * 인덱스를 쓰는 곳이 여러 블록이라 줄일 때는 반드시 {@code ThreadDumpResultCodec} 의 재색인 함수를 거친다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ThreadDumpResult {

    public static final int SCHEMA = 1;

    /** 스레드 상태 한 글자 코드 — {@link Track#getS()} 의 문자. */
    public static final char ST_RUNNABLE = 'R', ST_BLOCKED = 'B', ST_WAITING = 'W', ST_TIMED = 'T',
            ST_NEW = 'N', ST_TERMINATED = 'X', ST_VM = 'V', ST_UNKNOWN = 'U', ST_ABSENT = '-';

    private int schema = SCHEMA;
    private Meta meta = new Meta();
    private List<Snapshot> snapshots = new ArrayList<>();
    private Kpi kpi = new Kpi();
    private List<Finding> findings = new ArrayList<>();
    private List<String> frames = new ArrayList<>();
    private List<Stack> stacks = new ArrayList<>();
    private List<String> names = new ArrayList<>();
    private List<Track> tracks = new ArrayList<>();
    private List<Pool> pools = new ArrayList<>();
    private List<LockInfo> locks = new ArrayList<>();
    private List<Deadlock> deadlocks = new ArrayList<>();
    private List<StackGroup> stackGroups = new ArrayList<>();
    private List<TopFrame> topFrames = new ArrayList<>();
    private List<Stuck> stuck = new ArrayList<>();
    private List<CpuTop> cpuTop = new ArrayList<>();
    private Diagnostics diagnostics = new Diagnostics();
    private RawSample rawSample = new RawSample();
    /**
     * 종합 진단(2.7.4) — 소견들을 원인·결과로 이은 한 줄 + 근거. 이어지는 소견이 없으면 null(화면·PDF 는 최상위 소견으로 대신한다).
     * 이름이 {@link #diagnostics}(파싱 진단)와 비슷해 헷갈리지 않게 verdict 로 둔다. ⚠ 초기화하지 말 것 — 옛 결과는 이 키가 없다(함정 58 규약).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Verdict verdict;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        private String format = "HOTSPOT";
        /** {@code Full thread dump} 헤더 줄(첫 스냅샷). */
        private String jvmVersionLine;
        private Integer jdkMajor;
        private long bytes;
        private long lines;
        /** 파일에서 본 스냅샷 수 / 결과에 담은 수({@code max-snapshots} 를 넘으면 마지막 N개만 담는다). */
        private int snapshotsSeen;
        private int snapshotsParsed;
        /** JVM 실행 수 — 스냅샷 사이에 재기동이 있으면 2 이상. 비교 규칙은 같은 실행 안에서만 돈다. */
        private int runs = 1;
        /** absolute = 덤프 앞 타임스탬프 줄 / none = 시각 정보 없음(파일 mtime 은 쓰지 않는다 — 업로드 시각일 뿐이다). */
        private String timeSource = "none";
        private Long firstTs;
        private Long lastTs;
        private Double spanSec;
        /** 스냅샷 간격을 어디서 구했는지: timestamp / elapsed(JDK 11+ {@code elapsed=} 차이) / none. */
        private String intervalSource = "none";
        /** 덤프 밖 줄(서버 로그)이 섞여 있었는지. */
        private boolean embedded;
        /** 화면·PDF 가 기본으로 보여 주는 스냅샷(마지막으로 스레드가 읽힌 스냅샷). */
        private int focusSnapshot;
        private String middleware;
        private boolean truncated;
        /** 저장 크기 상한으로 일부 스레드를 뺐는지. */
        private boolean tracksTrimmed;
        /**
         * 입력 출처 — null = 스레드 덤프 파일, "heap" = 힙 덤프의 MAT 산출물에서 추출(2.7.1). ⚠ {@code = "..."} 로 초기화하지 말 것 —
         * 옛 결과는 이 키가 없고 null 이어야 "스레드 덤프" 로 읽힌다(함정 58 규약).
         */
        private String source;
        /** source=heap 일 때 원본 힙 덤프 파일명. */
        private String heapFilename;
        /** 이어 붙여 분석한 구성 파일(분석 순서). 파일 하나짜리 분석이면 null. */
        private List<String> files;
        /**
         * 소견 규칙 세대(2.7.4 = 2). null 이면 근거 줄·원인 연결이 없던 옛 규칙의 결과다 — 화면이 재분석을 안내한다.
         * ⚠ 초기화하지 말 것(분석기가 넣는다).
         */
        private Integer ruleset;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Snapshot {
        private int idx;
        private int run;
        private Long ts;
        private String tsText;
        private int firstLine;
        private int lastLine;
        private int threads;
        private int javaThreads;
        private int daemon;
        private Map<String, Integer> states = new LinkedHashMap<>();
        private int blocked;
        private int deadlocks;
        private Integer jniRefs;
        private boolean complete;
        /** EOF / INTERRUPTED_BY_LOG / null. */
        private String incompleteReason;
        /** {@code Full thread dump} 헤더 없이 스레드 줄만 있던 덤프. */
        private boolean headerless;
        private int skippedThreads;
        /** 이어 붙인 분석에서 이 스냅샷이 나온 파일. 파일 하나짜리 분석이면 null(줄 번호는 이 파일 기준). */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String file;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kpi {
        private String severity = "Info";
        private int threadsLast;
        private int threadsPeak;
        private int blockedMax;
        private int deadlocks;
        private int stuck;
        private int topLockWaiters;
        private int findingCount;
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
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Integer snapshotIdx;
        /** 관련 스레드({@link ThreadDumpResult#getTracks()} 인덱스, ≤20). */
        private List<Integer> tracks = new ArrayList<>();
        /**
         * 근거 줄(2.7.4) — 규칙이 센 수치를 사람이 읽는 문장으로. 화면·PDF·AI 프롬프트가 그대로 싣는다.
         * ⚠ 스레드·스택 <b>인덱스를 넣지 말 것</b> — 코덱의 줄이기·재색인은 {@link #tracks} 만 고친다. 옛 결과는 null.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> basis;
        /** 원인·결과로 이어진 소견 코드(2.7.4). 같은 코드가 여러 건이면 화면은 첫 카드로 간다. 옛 결과는 null. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> related;
    }

    /** 종합 진단 — {@link ThreadDumpResult#getVerdict()}. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Verdict {
        private String headline;
        private List<String> points = new ArrayList<>();
        /** 이어진 소견 코드(앞이 결과, 뒤가 원인 쪽). */
        private List<String> codes = new ArrayList<>();
    }

    /** 스택 하나 — 프레임 인덱스 열(위가 top). {@code cut} 은 저장 깊이 상한으로 잘라 낸 프레임 수. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stack {
        private List<Integer> f = new ArrayList<>();
        private int cut;
    }

    /** 스냅샷을 가로지르는 스레드 하나. {@code s}·{@code k}·{@code cpu} 는 {@code first} 번째 스냅샷부터의 열이다. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Track {
        /** 이름 인덱스. */
        private int n;
        /** {@code #n} — 없으면 null(JVM 내부 스레드·JDK 7). */
        private Long num;
        private String tid;
        private String nid;
        /** daemon. */
        private boolean d;
        /** 풀 인덱스(-1 = 풀 아님). */
        private int p = -1;
        /** JVM/시스템 스레드(GC·컴파일러·Finalizer 등). */
        private boolean sys;
        private int first;
        /** 스냅샷별 상태 코드 문자열. */
        private String s = "";
        /** 스냅샷별 스택 id(-1 = 스택 없음/부재). */
        private List<Integer> k = new ArrayList<>();
        /** 스냅샷별 누적 CPU ms(JDK 11+). 없으면 null. */
        private List<Double> cpu;
        /** focus 스냅샷의 유휴 여부. */
        private boolean idle;
        /** focus 스냅샷의 native 상태 문구({@code waiting on condition} 등). */
        private String nat;
        /** focus 스냅샷의 락 줄. */
        private List<Op> ops;
        /** 힙 점유(Retained Heap, 바이트) — 힙 덤프에서 추출한 결과에만 있다. */
        private Long hr;
        /** 스택 지역 변수가 붙잡은 객체 중 최대 Retained Heap — 힙 덤프에서 추출한 결과에만 있다. */
        private Long hl;
        /** 스레드 객체의 클래스(예: jeus.servlet.engine.WebThread) — 힙 덤프에서 추출한 결과에만 있다. */
        private String hc;
    }

    /** 스택 사이에 끼는 락 줄 — {@code at} 번째 프레임 뒤에 놓인다. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Op {
        private int at;
        /** locked / waiting to lock / waiting on / parking to wait for / owns(Locked ownable synchronizers). */
        private String v;
        private String a;
        private String c;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pool {
        private String stem;
        /** worker(요청 처리 풀로 인식) / other. */
        private String kind = "other";
        private List<Integer> sizes = new ArrayList<>();
        private List<Integer> idle = new ArrayList<>();
        private List<Integer> active = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LockInfo {
        private int snap;
        private String addr;
        private String cls;
        /** monitor / juc. */
        private String kind = "monitor";
        /** 소유 스레드(track 인덱스, 모르면 -1). */
        private int owner = -1;
        private List<Integer> waiters = new ArrayList<>();
        private int waiterCount;
        /** 소유자 사슬의 끝(이 스레드가 풀려야 나머지가 풀린다). 없으면 -1. */
        private int rootBlocker = -1;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Deadlock {
        private int snap;
        /** jvm(JVM 이 보고) / graph(락 대기 그래프에서 찾음). */
        private String source = "graph";
        private List<Integer> tracks = new ArrayList<>();
        private List<Chain> chain = new ArrayList<>();
        private List<String> raw = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chain {
        private int t = -1;
        private String name;
        private String addr;
        private String cls;
        private int heldBy = -1;
        private String heldByName;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StackGroup {
        private int stackId;
        private int count;
        private String state;
        private List<Integer> sample = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopFrame {
        private String frame;
        private int count;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stuck {
        private int t;
        private int stackId;
        private int from;
        private int to;
        private Double durationSec;
        /** blocked / lock-wait / pool-wait / io / running / waiting / sleeping. */
        private String category;
        private Double cpuDeltaMs;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CpuTop {
        private int t;
        private double deltaMs;
        private double pct;
        private int from;
        private int to;
    }

    /** 실패시키지 않고 보완한 내역 — 화면·PDF 의 "파싱 진단" 카드. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Diagnostics {
        private long noiseLines;
        private long unparsedLines;
        private List<LineSample> unparsedSamples = new ArrayList<>();
        private long truncatedLines;
        private long replacedChars;
        private long strippedControlChars;
        private int incompleteSnapshots;
        private int incompleteThreads;
        private int discardedFragments;
        private int unknownStates;
        private int unknownLockLines;
        private int missingTimestamps;
        private String decoratedPrefix;
        private List<String> limitHits = new ArrayList<>();
        private List<RuleFailure> ruleFailures = new ArrayList<>();
        private List<String> oomLines = new ArrayList<>();
        private long dumpLines;
        /** 이어 붙인 구성 파일 중 스레드 덤프가 하나도 없던 파일("이름 — 사유"). */
        private List<String> membersWithoutDump = new ArrayList<>();
        /** 힙 덤프 추출: 살아 있지 않아(NEW·TERMINATED) 뺀 Thread 객체 수. */
        private int heapNotAliveThreads;
        /** 힙 덤프 추출: 스택이 없던 스레드 수(.threads 에 없음). */
        private int heapThreadsWithoutStack;
        /** 힙 덤프 추출: MAT 표에 상태 열이 없어 상태를 모른 채 넣은 스레드 수. */
        private int heapThreadsWithoutState;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LineSample {
        private int line;
        private String text;
        /** 이어 붙인 분석에서 이 줄이 있던 파일(줄 번호는 이 파일 기준). */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String file;
        public LineSample() {}
        public LineSample(int line, String text) { this.line = line; this.text = text; }
        public LineSample(int line, String text, String file) { this.line = line; this.text = text; this.file = file; }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuleFailure {
        private String rule;
        private String error;
        public RuleFailure() {}
        public RuleFailure(String rule, String error) { this.rule = rule; this.error = error; }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RawSample {
        private List<String> head = new ArrayList<>();
        private List<String> firstDumpHead = new ArrayList<>();
        private Integer firstDumpLine;
    }
}

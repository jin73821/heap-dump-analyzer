package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.GcLogResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.DumpTransferLog;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.model.entity.GcLogResultDetailEntity;
import com.heapdump.analyzer.model.entity.TargetServer;
import com.heapdump.analyzer.parser.gclog.GcLogEngine;
import com.heapdump.analyzer.parser.gclog.GcLogFormat;
import com.heapdump.analyzer.parser.gclog.GcLogFormatDetector;
import com.heapdump.analyzer.parser.gclog.GcLogResultCodec;
import com.heapdump.analyzer.parser.gclog.GcLogSource;
import com.heapdump.analyzer.parser.gclog.ParseLimits;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.repository.GcLogResultDetailRepository;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import com.heapdump.analyzer.util.WasInstanceName;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * GC 로그 등록·분석·결과 보관 (2026-09-14). {@code CoreDumpAnalyzerService} 의 구조를 따르되 결과는 디스크가 아니라
 * DB({@code gc_log_result_detail})에 둔다.
 *
 * <p>분석은 자체 executor(2)에서 돌고 진행은 {@link #getStatus} 폴링으로 본다(SSE 아님 — CPU 작업이고 외부 프로세스
 * 로그 스트림이 없다). 완료 시 요약 컬럼 + 상세 JSON 저장 후 {@link GcLogMatchService#tryAutoMatch} 를 부른다.
 */
@Service
public class GcLogAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(GcLogAnalyzerService.class);
    private static final int RESULT_CACHE = 10;
    private static final int MAX_FILENAME_LEN = 480;   // ai_insights.filename(500) − "__gclog__:" 접두 여유

    private final HeapDumpConfig config;
    private final GcLogAnalysisRepository repository;
    private final GcLogResultDetailRepository detailRepository;
    private final AnalysisHistoryRepository historyRepository;
    private final GcLogMatchService matchService;
    private final LlmConfigService llmConfig;
    private final AiInsightManager aiInsight;
    private final DumpTransferLogRepository transferLogRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gclog-analyzer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, JobProgress> jobs = new ConcurrentHashMap<>();
    private final Map<String, GcLogResult> resultCache = Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, GcLogResult> e) { return size() > RESULT_CACHE; }
            });

    /** 진행 상태(파일별). */
    public static final class JobProgress {
        public volatile long bytesRead, totalBytes, lines;
        public volatile int events;
        public volatile String phase = "queued";
        public final long startedAt = System.currentTimeMillis();
        volatile Future<?> future;
    }

    public GcLogAnalyzerService(HeapDumpConfig config,
                                GcLogAnalysisRepository repository,
                                GcLogResultDetailRepository detailRepository,
                                AnalysisHistoryRepository historyRepository,
                                GcLogMatchService matchService,
                                LlmConfigService llmConfig,
                                AiInsightManager aiInsight,
                                DumpTransferLogRepository transferLogRepository) {
        this.transferLogRepository = transferLogRepository;
        this.config = config;
        this.repository = repository;
        this.detailRepository = detailRepository;
        this.historyRepository = historyRepository;
        this.matchService = matchService;
        this.llmConfig = llmConfig;
        this.aiInsight = aiInsight;
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    // ── 경로·검증 ────────────────────────────────────────────────

    public File dumpFilesDir() { return new File(config.getGcLogDumpFilesDirectory()); }

    public File fileOf(String safeFilename) { return new File(dumpFilesDir(), safeFilename); }

    /** 확장자 제한 없음(gc.log.3 / .current / .gz 전부 유효) — 경로 요소·길이만 검사. */
    public String validateGcLogFilename(String filename) {
        String safe = FilenameValidator.validateSafe(filename);
        if (safe.length() > MAX_FILENAME_LEN) throw new IllegalArgumentException("파일명이 너무 깁니다(최대 " + MAX_FILENAME_LEN + "자)");
        return safe;
    }

    public static String gcInsightKey(String filename) { return "__gclog__:" + filename; }

    // ── 등록 ────────────────────────────────────────────────────

    /** 원격 전송 성공 직후 — NOT_ANALYZED 행 생성(있으면 출처 갱신) + 자동 매칭 1차 시도. */
    public GcLogAnalysisEntity registerTransferred(DumpTransferLog log, TargetServer server) {
        GcLogAnalysisEntity e = repository.findByFilename(log.getFilename()).orElseGet(GcLogAnalysisEntity::new);
        boolean fresh = e.getId() == null;
        e.setFilename(log.getFilename());
        if (fresh) e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        // 전송은 방금 디스크에 놓인 새 파일이다 — 같은 이름의 기록이 남아 있으면(전송 측 회피명이 놓친 경합 등) 옛 결과를 이어 쓰지 않는다
        else forgetAnalysis(e, "transfer");
        e.setFileSize(log.getFileSize());
        e.setCompressed(log.getFilename().toLowerCase(Locale.ROOT).endsWith(".gz"));
        e.setFileDeleted(false);
        e.setServerId(server.getId());
        e.setServerName(server.getName());
        e.setRemotePath(log.getRemotePath());
        e.setJvmInfo(log.getJvmInfo());
        Long mtime = mtimeFromJvmInfo(log.getJvmInfo());
        if (mtime != null) e.setRemoteMtime(LocalDateTime.ofInstant(Instant.ofEpochSecond(mtime), ZoneId.systemDefault()));
        // 전송본은 출처가 분명하다 — 호스트네임은 대상 서버 이름, 인스턴스는 수집한 JVM 마커에서 자동으로 채운다
        if (e.getInstanceName() == null || e.getInstanceName().isBlank()) {
            String inst = instanceFromJvmInfo(log.getJvmInfo());
            if (inst != null) e.setInstanceName(trunc(inst, 100));
        }
        refreshAnalysisName(e);
        e = repository.save(e);
        logger.info("[GcLog] action=register source=transfer file={} server={} instance={} name={} remotePath={}",
                e.getFilename(), server.getName(), e.getInstanceName(), e.getAnalysisName(), log.getRemotePath());
        matchService.tryAutoMatch(e);
        return e;
    }

    /** 업로드 — 출처 서버 없음(호스트명 수동 입력 가능). */
    public GcLogAnalysisEntity registerUploaded(String safeFilename, long size, String who, String serverName) {
        return registerUploaded(safeFilename, size, who, serverName, null);
    }

    /**
     * 업로드 — 수동 업로드는 호스트네임이 <b>필수</b>다(분석 시작 때 {@link #requireHostname} 이 한 번 더 막는다).
     * 인스턴스명은 선택이며 분석명 {@code 호스트_인스턴스_일자} 에 들어간다.
     */
    public GcLogAnalysisEntity registerUploaded(String safeFilename, long size, String who, String serverName, String instanceName) {
        GcLogAnalysisEntity e = repository.findByFilename(safeFilename).orElseGet(GcLogAnalysisEntity::new);
        boolean fresh = e.getId() == null;
        e.setFilename(safeFilename);
        if (fresh) e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        // 업로드는 같은 이름 파일이 없을 때만 허용되므로 늘 새 파일이다 — 파일 없이 남은 옛 기록(디스크에서 지운 파일)의 결과를 붙이지 않는다
        else forgetAnalysis(e, "upload");
        e.setFileSize(size);
        e.setCompressed(safeFilename.toLowerCase(Locale.ROOT).endsWith(".gz"));
        e.setFileDeleted(false);
        e.setUploadedBy(who);
        if (serverName != null && !serverName.isBlank()) e.setServerName(trunc(serverName.trim(), 100));
        if (instanceName != null && !instanceName.isBlank()) e.setInstanceName(trunc(instanceName.trim(), 100));
        refreshAnalysisName(e);
        e = repository.save(e);
        logger.info("[GcLog] action=register source=upload file={} size={} server={} instance={} name={} by={}",
                safeFilename, size, e.getServerName(), e.getInstanceName(), e.getAnalysisName(), who);
        matchService.tryAutoMatch(e);
        return e;
    }

    // ── 파일 분류 이동 (Files 페이지 '파일 분류 설정', 2026-09-14) ─────────────

    /**
     * 다른 저장소(힙·코어 dumpfiles)에 있는 파일을 GC 로그로 편입한다 — 형식 검사 → 이동 → 등록 → 자동 매칭.
     * 원본 저장소 쪽 기록 정리는 호출자(컨트롤러)가 한다(서비스끼리 서로 주입하면 순환 참조로 기동 실패).
     * 전송 기록이 있으면 출처 서버·원격 경로를 이어받아 매칭 신호로 쓴다.
     *
     * @throws IllegalArgumentException GC 로그 형식이 아님
     * @throws IllegalStateException    GC 로그 저장소에 같은 이름이 이미 있음
     */
    public GcLogAnalysisEntity adoptFile(File source, String who, String serverName) throws IOException {
        String safe = validateGcLogFilename(source.getName());
        File target = fileOf(safe);
        if (target.exists()) throw new IllegalStateException("GC 로그 저장소에 같은 이름의 파일이 이미 있습니다: " + safe);
        if (sniffFormat(source) == GcLogFormat.UNKNOWN) {
            throw new IllegalArgumentException("GC 로그 형식을 인식하지 못했습니다. JDK 9+ 통합 로깅(-Xlog:gc*) 또는 JDK 8 이하 -XX:+PrintGCDetails 출력만 GC 로그로 분류할 수 있습니다.");
        }
        dumpFilesDir().mkdirs();
        java.nio.file.Files.move(source.toPath(), target.toPath());

        // 같은 이름의 옛 GC 기록(이전에 내보냈다 다시 들인 경우 등)은 결과를 비우고 새로 시작한다
        Optional<GcLogAnalysisEntity> previous = repository.findByFilename(safe);
        GcLogAnalysisEntity e = previous.orElseGet(GcLogAnalysisEntity::new);
        if (previous.isPresent()) forgetAnalysis(e, "adopt");
        else { resultCache.remove(safe); detailRepository.deleteByFilename(safe); aiInsight.deleteAiInsight(gcInsightKey(safe)); }
        e.setFilename(safe);
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setFileSize(target.length());
        e.setCompressed(safe.toLowerCase(Locale.ROOT).endsWith(".gz"));
        e.setFileDeleted(false);
        if (e.getUploadedBy() == null) e.setUploadedBy(who);
        transferLogRepository.findByFilenameAndTransferStatusOrderByCompletedAtDesc(safe, "SUCCESS").stream().findFirst().ifPresent(t -> {
            e.setServerId(t.getServerId());
            e.setRemotePath(t.getRemotePath());
        });
        if (serverName != null && !serverName.isBlank() && e.getServerName() == null) {
            e.setServerName(serverName.trim().length() > 100 ? serverName.trim().substring(0, 100) : serverName.trim());
        }
        GcLogAnalysisEntity saved = repository.save(e);
        logger.info("[GcLog] action=adopt file={} from={} size={} by={}", safe, source.getParent(), target.length(), who);
        matchService.tryAutoMatch(saved);
        return saved;
    }

    /**
     * GC 로그를 다른 저장소로 내보낸다(분류를 GC 로그가 아닌 것으로 바꿀 때). 분석 결과·AI 해석·매칭 기록은 함께 지운다.
     *
     * @throws IllegalStateException 분석 중이거나 대상 저장소에 같은 이름이 있음
     */
    public void releaseFile(String safeFilename, File targetDir, String who) throws IOException {
        String safe = validateGcLogFilename(safeFilename);
        if (isAnalyzing(safe)) throw new IllegalStateException("분석이 진행 중인 GC 로그는 분류를 바꿀 수 없습니다.");
        File source = fileOf(safe);
        if (!source.isFile()) throw new IllegalArgumentException("GC 로그 파일이 없습니다: " + safe);
        File target = new File(targetDir, safe);
        if (target.exists()) throw new IllegalStateException("대상 저장소에 같은 이름의 파일이 이미 있습니다: " + safe);
        targetDir.mkdirs();
        java.nio.file.Files.move(source.toPath(), target.toPath());
        jobs.remove(safe);
        resultCache.remove(safe);
        detailRepository.deleteByFilename(safe);
        aiInsight.deleteAiInsight(gcInsightKey(safe));
        repository.findByFilename(safe).ifPresent(x -> { clearMemberLinks(x); repository.delete(x); });
        detachFromGroup(safe);
        logger.info("[GcLog] action=release file={} to={} by={}", safe, targetDir, who);
    }

    /**
     * 같은 이름의 기록을 <b>새 파일</b>로 다시 쓰기 전에 옛 분석의 흔적을 모두 지운다(2026-09-14) — 진행 중 작업·결과 캐시·결과 JSON·AI 해석과
     * 엔티티의 요약·오류·매칭·인스턴스명·출처(서버·원격 경로·JVM 수집). 저장은 호출자가 한다(값을 이어서 채우므로).
     * <p>⚠ 이게 없으면 파일을 디스크에서 지운 뒤 같은 이름으로 새 파일이 들어올 때 옛 기록의 {@code SUCCESS} 와 결과가 그대로 이어져,
     * <b>새 파일을 열면 예전 파일의 분석 결과가 오류 없이 보였다</b>.
     */
    void forgetAnalysis(GcLogAnalysisEntity e, String reason) {
        String fn = e.getFilename();
        JobProgress p = jobs.remove(fn);
        if (p != null && p.future != null) p.future.cancel(true);
        resultCache.remove(fn);
        detailRepository.deleteByFilename(fn);
        aiInsight.deleteAiInsight(gcInsightKey(fn));
        boolean hadResult = GcLogAnalysisEntity.STATUS_SUCCESS.equals(e.getStatus()) || GcLogAnalysisEntity.STATUS_ERROR.equals(e.getStatus())
                || e.getMatchedDumpFilename() != null;
        e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setErrorMessage(null);
        e.setUploadedBy(null);
        e.setServerId(null);
        e.setServerName(null);
        e.setRemotePath(null);
        e.setRemoteMtime(null);
        e.setJvmInfo(null);
        e.setInstanceName(null);
        e.setLogFormat(null);
        e.setCollector(null);
        e.setJdkVersion(null);
        e.setLogStart(null);
        e.setLogEnd(null);
        e.setTimeSource(null);
        e.setEventCount(null);
        e.setFullGcCount(null);
        e.setPressureFullGcCount(null);
        e.setFindingsCount(null);
        e.setMaxPauseMs(null);
        e.setP99PauseMs(null);
        e.setThroughputPct(null);
        e.setMaxHeapBytes(null);
        e.setSeverity(null);
        e.setMatchedDumpFilename(null);
        e.setMatchSource(GcLogAnalysisEntity.MATCH_NONE);
        e.setMatchReason(null);
        e.setMatchCandidates(null);
        e.setMatchedAt(null);
        e.setAnalysisTimeMs(null);
        e.setAnalyzedAt(null);
        e.setMatchWindowStart(null);
        e.setMatchWindowEnd(null);
        e.setAnalysisName(null);
        e.setAnalysisDate(null);
        // ⚠ 구성(이어 붙이기)도 함께 지운다 — 남겨 두면 같은 이름으로 새 파일이 들어온 뒤 재분석할 때
        // 옛 구성 그대로 엉뚱한 파일과 합쳐 분석한다(함정 63).
        clearMemberLinks(e);
        logger.info("[GcLog] action=forget-previous file={} reason={} hadResult={}", fn, reason, hadResult);
    }

    // ── 이어 붙이기(회전 로그 병합, 2026-09-20) ─────────────────
    //
    // 결과는 별도 그룹 행이 아니라 **사용자가 연 로그(대표)의 행**에 저장하고, 구성 파일 목록을 member_files 에 둔다.
    // {filename} 키의 기존 엔드포인트·Files 탭·대시보드·현황판·AI 인사이트 키가 그대로 동작한다(함정 63).

    /** 한 분석에 이어 붙일 수 있는 파일 수 상한. */
    public static final int MAX_MEMBERS = 20;

    private final JsonMapper memberMapper = JsonMapper.builder().build();

    /** 구성 파일 한 개 — 그때의 크기·mtime 스냅샷을 함께 남겨 구성이 바뀌었는지 판정한다. */
    public record MemberRef(String filename, long size, Long mtimeEpochSec) {}

    /** 조각 앞부분만 훑은 결과 — 순서 결정과 형식 대조에 쓴다. */
    public record Peek(String filename, GcLogFormat format, Long firstTsEpochMs, Double firstUptimeSec,
                       long mtimeEpochSec, long size) {}

    /** 이어 붙이기 계획 — 검증·정렬만 하고 분석은 시작하지 않는다(미리보기). */
    public record CombinePlan(List<MemberRef> ordered, List<String> warnings, long totalBytes, String orderBasis) {}

    private static final java.util.regex.Pattern ISO_ANY = java.util.regex.Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4})");
    private static final java.util.regex.Pattern UP_UNIFIED = java.util.regex.Pattern.compile("\\[(\\d+(?:\\.\\d+)?)s\\]");
    private static final java.util.regex.Pattern UP_JDK8 = java.util.regex.Pattern.compile(
            "^(?:\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}: )?(\\d+\\.\\d+): ");

    /**
     * 조각 앞부분(≤200줄)만 읽어 형식·첫 시각·첫 uptime 을 본다. gz 도 읽는다.
     *
     * @param preferredMtimeEpochSec 전송본의 <b>원격</b> mtime — 로컬 mtime 은 scp 가 새로 찍어 순서 근거가 못 된다
     */
    public Peek peek(File f, Long preferredMtimeEpochSec) throws IOException {
        List<String> lines = new ArrayList<>(GcLogFormatDetector.SNIFF_LINES);
        try (InputStream raw = new FileInputStream(f)) {
            InputStream in = isGzip(f) ? new GZIPInputStream(raw, 1 << 16) : raw;
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, decoder), 1 << 16);
            String line;
            long read = 0;
            while (lines.size() < GcLogFormatDetector.SNIFF_LINES && read < 64 * 1024 && (line = r.readLine()) != null) {
                read += line.length() + 1;
                lines.add(line);
            }
        }
        GcLogFormat fmt = GcLogFormatDetector.sniff(lines);
        Long firstTs = null;
        Double firstUp = null;
        for (String line : lines) {
            if (firstTs == null) {
                var m = ISO_ANY.matcher(line);
                if (m.find()) firstTs = parseLogTs(m.group(1));
            }
            if (firstUp == null) {
                var mu = (fmt == GcLogFormat.UNIFIED ? UP_UNIFIED : UP_JDK8).matcher(line);
                if (fmt == GcLogFormat.UNIFIED ? mu.find() : mu.lookingAt()) {
                    try { firstUp = Double.parseDouble(mu.group(1)); } catch (RuntimeException ignore) { firstUp = null; }
                }
            }
            if (firstTs != null && firstUp != null) break;
        }
        long mtime = preferredMtimeEpochSec != null ? preferredMtimeEpochSec
                : (f.lastModified() > 0 ? f.lastModified() / 1000L : 0L);
        return new Peek(f.getName(), fmt, firstTs, firstUp, mtime, f.length());
    }

    /** {@code 2026-09-12T11:39:34.763+0900} → epoch ms. 파서의 같은 규칙을 화면 밖(정렬)에서 쓴다. */
    private static final java.time.format.DateTimeFormatter LOG_TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private static Long parseLogTs(String iso) {
        try { return java.time.OffsetDateTime.parse(iso, LOG_TS).toInstant().toEpochMilli(); }
        catch (RuntimeException e) { return null; }
    }

    private static boolean isGzip(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            int b1 = in.read(), b2 = in.read();
            return b1 == 0x1f && b2 == 0x8b;
        }
    }

    /**
     * 조각을 시간순으로 정렬한다.
     *
     * <p>⚠ <b>파일명·회전 번호로 정렬하지 않는다</b> — JDK 9+ 는 아카이브 슬롯을 돌려 쓰고 JDK 8 은 현재 파일이
     * {@code .current} 라 이름순이 시간순이 아니다(함정 60). 근거는 ① 내용의 첫 절대 시각 ② (전부 없으면) mtime
     * ③ mtime 이 같을 때 첫 uptime 이다.
     */
    static List<Peek> orderMembers(List<Peek> peeks, List<String> warnings) {
        List<Peek> out = new ArrayList<>(peeks);
        boolean allTs = out.stream().allMatch(x -> x.firstTsEpochMs() != null);
        boolean anyTs = out.stream().anyMatch(x -> x.firstTsEpochMs() != null);
        if (allTs) {
            out.sort(java.util.Comparator.comparing(Peek::firstTsEpochMs).thenComparing(Peek::filename));
            return out;
        }
        out.sort(java.util.Comparator.comparingLong(Peek::mtimeEpochSec)
                .thenComparing(x -> x.firstUptimeSec() == null ? 0.0 : x.firstUptimeSec())
                .thenComparing(Peek::filename));
        if (warnings != null) {
            warnings.add(anyTs
                    ? "일부 로그에만 절대 시각이 있어 파일 시각 순으로 이어 붙였습니다. 순서를 확인하세요."
                    : "로그에 절대 시각이 없어 파일 시각 순으로 이어 붙였습니다. 순서를 확인하세요.");
        }
        return out;
    }

    static String orderBasisOf(List<Peek> peeks) {
        return peeks.stream().allMatch(x -> x.firstTsEpochMs() != null) ? "absolute" : "mtime";
    }

    /** 대표 행의 구성 파일 목록(병합 순서). 구성이 없으면 대표 파일 하나. 파일이 없으면 이름을 밝히고 예외. */
    List<File> memberFilesOf(GcLogAnalysisEntity e) {
        List<MemberRef> refs = parseMembers(e.getMemberFiles());
        if (refs.isEmpty()) return List.of(fileOf(e.getFilename()));
        List<File> out = new ArrayList<>(refs.size());
        List<String> missing = new ArrayList<>();
        for (MemberRef ref : refs) {
            File f = fileOf(ref.filename());
            if (!f.isFile()) missing.add(ref.filename());
            else out.add(f);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("이어 붙인 구성 파일이 없습니다: " + String.join(", ", missing)
                    + " — 구성에서 빼고 다시 분석하세요.");
        }
        return out;
    }

    public List<MemberRef> parseMembers(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return memberMapper.readValue(json, new TypeReference<List<MemberRef>>() {});
        } catch (RuntimeException ex) {
            logger.warn("[GcLog] member_files 파싱 실패 — 구성을 무시한다: {}", ex.getMessage());
            return List.of();
        }
    }

    private String writeMembers(List<MemberRef> refs) {
        return memberMapper.writeValueAsString(refs);
    }

    /**
     * 이 파일이 구성 파일로 들어가 있던 대표 분석에서 빼낸다 — 대표의 결과는 <b>그대로 둔다</b>(그 시점의 진실이다).
     * 화면이 "구성 파일 누락" 을 알아볼 수 있게 member_files 는 남기고 역참조만 끊는다.
     */
    private void detachFromGroup(String safeFilename) {
        repository.findByFilename(safeFilename).ifPresent(x -> x.setPartOf(null));
    }

    /** 대표 행의 구성 표시를 지우고 구성 파일들의 역참조({@code part_of})도 함께 푼다. 파일은 건드리지 않는다. */
    void clearMemberLinks(GcLogAnalysisEntity e) {
        e.setMemberFiles(null);
        e.setMemberCount(null);
        e.setMembersBytes(null);
        List<GcLogAnalysisEntity> members = repository.findByPartOf(e.getFilename());
        if (members == null) return;   // 목 객체 방어 — stub 이 없으면 null 이 온다
        for (GcLogAnalysisEntity m : members) {
            if (m.getFilename().equals(e.getFilename())) continue;
            m.setPartOf(null);
            repository.save(m);
        }
    }

    /**
     * 이어 붙이기 검증·정렬(분석은 시작하지 않는다). 대표는 목록에 없어도 자동으로 포함된다.
     */
    public CombinePlan planCombine(String repSafe, List<String> memberNames) {
        GcLogAnalysisEntity rep = repository.findByFilename(repSafe)
                .orElseThrow(() -> new IllegalArgumentException("GC 로그 기록이 없습니다: " + repSafe));
        Set<String> names = new LinkedHashSet<>();
        names.add(repSafe);
        if (memberNames != null) {
            for (String n : memberNames) {
                if (n == null || n.isBlank()) continue;
                names.add(validateGcLogFilename(n));
            }
        }
        if (names.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("한 번에 이어 붙일 수 있는 로그는 " + MAX_MEMBERS + "개까지입니다 (선택 " + names.size() + "개).");
        }
        List<String> warnings = new ArrayList<>();
        List<Peek> peeks = new ArrayList<>();
        long total = 0;
        Set<String> dates = new LinkedHashSet<>();
        Set<String> servers = new LinkedHashSet<>();
        for (String n : names) {
            File f = fileOf(n);
            if (!f.isFile()) throw new IllegalArgumentException("GC 로그 파일이 없습니다: " + n);
            GcLogAnalysisEntity m = repository.findByFilename(n).orElse(null);
            if (m == null && !n.equals(repSafe)) {
                // 등록된 GC 로그만 이어 붙인다 — 업로드 sniff 를 통과한 적 없는 파일이 헤더 없는 회전본 행세를 할 수 있다
                throw new IllegalArgumentException(n + " 은(는) GC 로그로 등록돼 있지 않습니다.");
            }
            if (m != null && !n.equals(repSafe)) {
                if (m.isCombined()) throw new IllegalArgumentException(n + " 은(는) 이미 다른 로그를 이어 붙인 분석입니다. 먼저 구성을 해제하세요.");
                if (m.getPartOf() != null && !repSafe.equals(m.getPartOf())) {
                    warnings.add(n + " 은(는) " + m.getPartOf() + " 의 구성 파일이었습니다 — 이 분석으로 옮깁니다.");
                }
                if (isAnalyzing(n)) throw new IllegalArgumentException(n + " 은(는) 지금 분석 중입니다. 끝난 뒤 다시 시도하세요.");
            }
            if (m != null && m.getAnalysisDate() != null) dates.add(m.getAnalysisDate().toString());
            if (m != null && m.getServerName() != null && !m.getServerName().isBlank()) servers.add(m.getServerName());
            try {
                peeks.add(peek(f, m == null || m.getRemoteMtime() == null ? null
                        : m.getRemoteMtime().atZone(ZoneId.systemDefault()).toEpochSecond()));
            } catch (IOException io) {
                throw new IllegalArgumentException(n + " 을(를) 읽지 못했습니다: " + io.getMessage());
            }
            total += f.length();
        }
        // 형식 대조 — 헤더 없는 회전본(UNKNOWN)은 그룹 형식을 물려받으므로 거부하지 않는다
        Set<GcLogFormat> known = new LinkedHashSet<>();
        for (Peek pk : peeks) if (pk.format() != GcLogFormat.UNKNOWN) known.add(pk.format());
        if (known.size() > 1) {
            throw new IllegalArgumentException("형식이 다른 로그를 함께 분석할 수 없습니다 (" + known + ").");
        }
        if (known.isEmpty()) throw new IllegalArgumentException("GC 로그 형식을 인식하지 못했습니다.");
        for (Peek pk : peeks) {
            if (pk.format() == GcLogFormat.UNKNOWN) {
                warnings.add(pk.filename() + " 은(는) 헤더가 없어 형식을 확인하지 못했습니다 — 같은 계열의 회전본인지 확인하세요.");
            }
        }
        if (total > config.getGcLogMaxFileBytes()) {
            throw new IllegalArgumentException("구성 파일 합계가 상한("
                    + FormatUtils.formatBytes(config.getGcLogMaxFileBytes()) + ")을 넘습니다 ("
                    + FormatUtils.formatBytes(total) + "). 파일 수를 줄이세요.");
        }
        if (servers.size() > 1) warnings.add("출처 서버가 다른 로그가 섞였습니다: " + String.join(", ", servers));
        if (dates.size() > 1) warnings.add("일자가 다른 로그를 함께 묶었습니다 — 분석명이 기간 표기로 바뀝니다.");

        List<Peek> ordered = orderMembers(peeks, warnings);
        List<MemberRef> refs = new ArrayList<>(ordered.size());
        for (Peek pk : ordered) refs.add(new MemberRef(pk.filename(), pk.size(), pk.mtimeEpochSec()));
        return new CombinePlan(refs, warnings, total, orderBasisOf(ordered));
    }

    /** 이어 붙여 분석 시작. {@code memberNames} 가 비면 구성을 해제하고 대표 단독으로 다시 분석한다. */
    public synchronized JobProgress submitCombined(String repSafe, List<String> memberNames, String who) {
        GcLogAnalysisEntity rep = repository.findByFilename(repSafe)
                .orElseThrow(() -> new IllegalArgumentException("GC 로그 기록이 없습니다: " + repSafe));
        if (isAnalyzing(repSafe)) throw new IllegalArgumentException("이미 분석 중입니다.");
        requireHostname(rep);

        boolean ungroup = memberNames == null || memberNames.stream().filter(n -> n != null && !n.isBlank())
                .noneMatch(n -> !n.equals(repSafe));
        if (ungroup) {
            clearCombination(repSafe, "ungroup");
            logger.info("[GcLog] action=combine file={} members=1 order=[] name={} by={}", repSafe, rep.getAnalysisName(), who);
            return submitAnalysis(repSafe, who);
        }

        CombinePlan plan = planCombine(repSafe, memberNames);
        clearMemberLinks(rep);
        long bytes = 0;
        for (MemberRef ref : plan.ordered()) bytes += ref.size();
        rep.setMemberFiles(writeMembers(plan.ordered()));
        rep.setMemberCount(plan.ordered().size());
        rep.setMembersBytes(bytes);
        rep.setPartOf(null);
        repository.save(rep);
        for (MemberRef ref : plan.ordered()) {
            if (ref.filename().equals(repSafe)) continue;
            GcLogAnalysisEntity m = repository.findByFilename(ref.filename()).orElse(null);
            if (m == null) continue;
            m.setPartOf(repSafe);
            repository.save(m);
        }
        // 결과가 달라지므로 옛 AI 해석을 남겨 두지 않는다
        aiInsight.deleteAiInsight(gcInsightKey(repSafe));
        detailRepository.deleteByFilename(repSafe);
        resultCache.remove(repSafe);

        List<String> order = new ArrayList<>();
        for (MemberRef ref : plan.ordered()) order.add(ref.filename());
        logger.info("[GcLog] action=combine file={} members={} order={} basis={} name={} by={}",
                repSafe, plan.ordered().size(), order, plan.orderBasis(), rep.getAnalysisName(), who);
        return submitAnalysis(repSafe, who);
    }

    /** 구성 해제 — 표시·역참조만 지우고 파일은 건드리지 않는다. */
    public void clearCombination(String repSafe, String reason) {
        GcLogAnalysisEntity rep = repository.findByFilename(repSafe).orElse(null);
        if (rep == null) return;
        clearMemberLinks(rep);
        repository.save(rep);
        logger.info("[GcLog] action=combine-clear file={} reason={}", repSafe, reason);
    }

    /**
     * 이어 붙일 후보 목록. 같은 회전 계열(원격 경로 base 또는 파일명 base)·같은 서버·<b>같은 일자</b>면
     * {@code suggested=true} 로 내려 화면이 미리 체크해 준다.
     */
    public List<Map<String, Object>> combineOptions(GcLogAnalysisEntity rep, String q, int limit) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Set<String> current = new LinkedHashSet<>();
        for (MemberRef ref : parseMembers(rep.getMemberFiles())) current.add(ref.filename());
        String repBase = rotationBase(rep);
        List<Map<String, Object>> out = new ArrayList<>();
        for (GcLogAnalysisEntity e : repository.findAllByOrderByCreatedAtDesc()) {
            if (e.getFilename().equals(rep.getFilename())) continue;
            if (e.isFileDeleted()) continue;
            if (e.isCombined()) continue;   // 이미 다른 구성의 대표
            if (!needle.isEmpty() && !e.getFilename().toLowerCase(Locale.ROOT).contains(needle)
                    && (e.getAnalysisName() == null || !e.getAnalysisName().toLowerCase(Locale.ROOT).contains(needle))) continue;
            boolean sameServer = rep.getServerName() != null && rep.getServerName().equalsIgnoreCase(e.getServerName());
            boolean sameBase = repBase != null && repBase.equals(rotationBase(e));
            boolean sameDate = rep.getAnalysisDate() != null && rep.getAnalysisDate().equals(e.getAnalysisDate());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", e.getFilename());
            m.put("analysisName", e.getAnalysisName());
            m.put("serverName", e.getServerName());
            m.put("instanceName", e.getInstanceName());
            m.put("size", e.getFileSize());
            m.put("formattedSize", e.getFormattedSize());
            m.put("status", e.getStatus());
            m.put("logStart", e.getLogStart() == null ? null : e.getLogStart().toString());
            m.put("logEnd", e.getLogEnd() == null ? null : e.getLogEnd().toString());
            m.put("analysisDate", e.getAnalysisDate() == null ? null : e.getAnalysisDate().toString());
            m.put("selected", current.contains(e.getFilename()));
            m.put("suggested", (sameBase || sameServer) && sameDate);
            m.put("sameServer", sameServer);
            m.put("sameSeries", sameBase);
            m.put("partOf", e.getPartOf());
            out.add(m);
        }
        out.sort((a, b) -> {
            int s = Boolean.compare(Boolean.TRUE.equals(b.get("suggested")), Boolean.TRUE.equals(a.get("suggested")));
            if (s != 0) return s;
            String x = (String) a.get("logStart"), y = (String) b.get("logStart");
            if (x != null && y != null) return y.compareTo(x);
            return 0;
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 회전 접미사를 뗀 계열 이름 — 원격 경로가 있으면 그쪽이 더 정확하다(전송 회피명은 파일명 base 를 망가뜨린다). */
    private static String rotationBase(GcLogAnalysisEntity e) {
        if (e.getRemotePath() != null && !e.getRemotePath().isBlank()) {
            return com.heapdump.analyzer.util.JvmHeapCapture.stripRotation(e.getRemotePath());
        }
        return e.getFilename() == null ? null : com.heapdump.analyzer.util.JvmHeapCapture.stripRotation(e.getFilename());
    }

    // ── 분석명 ──────────────────────────────────────────────────

    /**
     * 분석명을 다시 만든다(등록·분석 성공·호스트네임/인스턴스 변경·이어 붙이기 네 시점에서 부른다).
     * 일자는 로그 시작일이고, 분석 전에는 전송 mtime → 등록 시각으로 잠정 표기한다.
     */
    public void refreshAnalysisName(GcLogAnalysisEntity e) {
        LocalDate start = e.getLogStart() != null ? e.getLogStart().toLocalDate()
                : e.getRemoteMtime() != null ? e.getRemoteMtime().toLocalDate()
                : e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate() : LocalDate.now();
        LocalDate end = e.getLogEnd() != null ? e.getLogEnd().toLocalDate() : start;
        e.setAnalysisDate(start);
        String base = GcLogNameService.build(e.getServerName(), e.getInstanceName(), start, end);
        if (base == null) { e.setAnalysisName(null); return; }
        e.setAnalysisName(GcLogNameService.assignUnique(base, n -> nameTakenByOther(n, e)));
    }

    /** 인스턴스명이 바뀐 뒤 분석명을 다시 만든다(컨트롤러 전용 — 인스턴스 저장은 {@code GcLogInstanceService} 몫). */
    public void renameAfterInstanceChange(GcLogAnalysisEntity e) {
        if (e == null) return;
        refreshAnalysisName(e);
        repository.save(e);
    }

    private boolean nameTakenByOther(String name, GcLogAnalysisEntity self) {
        List<GcLogAnalysisEntity> hits = repository.findByAnalysisName(name);
        if (hits == null) return false;
        for (GcLogAnalysisEntity h : hits) if (!h.getFilename().equals(self.getFilename())) return true;
        return false;
    }

    /** 전송본의 JVM 정보에서 WAS 인스턴스명을 읽는다 — 규칙은 {@link WasInstanceName} 하나뿐이다. */
    static String instanceFromJvmInfo(String jvmInfo) {
        com.heapdump.analyzer.util.JvmHeapCapture.Capture cap = com.heapdump.analyzer.util.JvmHeapCapture.fromJson(jvmInfo);
        if (cap == null || cap.candidates().isEmpty()) return null;
        int idx = cap.matchedIndex() != null ? cap.matchedIndex() : (cap.candidates().size() == 1 ? 0 : -1);
        if (idx < 0 || idx >= cap.candidates().size()) return null;
        String v = WasInstanceName.of(cap.candidates().get(idx).markers());
        return v == null || v.isBlank() ? null : v;
    }

    private static Long mtimeFromJvmInfo(String jvmInfo) {
        com.heapdump.analyzer.util.JvmHeapCapture.Capture cap = com.heapdump.analyzer.util.JvmHeapCapture.fromJson(jvmInfo);
        return cap == null ? null : cap.dumpMtimeEpoch();
    }

    /** 디스크에만 있는 파일을 NOT_ANALYZED 로 등록하고 목록을 돌려준다(index 페이지 좌측 패널). */
    public List<GcLogAnalysisEntity> listExistingFiles() {
        File[] files = dumpFilesDir().listFiles();
        Map<String, GcLogAnalysisEntity> byName = new LinkedHashMap<>();
        for (GcLogAnalysisEntity e : repository.findAllByOrderByCreatedAtDesc()) byName.put(e.getFilename(), e);
        List<GcLogAnalysisEntity> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                GcLogAnalysisEntity e = byName.get(f.getName());
                if (e == null) {
                    e = new GcLogAnalysisEntity();
                    e.setFilename(f.getName());
                    e.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED);
                    e.setFileSize(f.length());
                    e.setCompressed(f.getName().toLowerCase(Locale.ROOT).endsWith(".gz"));
                    e = repository.save(e);
                    byName.put(e.getFilename(), e);
                } else if (e.isFileDeleted()) {
                    // 사라졌던 이름에 파일이 다시 나타났다. 크기가 기록과 다르면 다른 파일이다 — 옛 결과·AI·매칭을 붙인 채 보이면 안 된다.
                    // 크기가 같으면 같은 파일을 되돌려 놓은 것으로 보고 결과를 살린다(fileSize 는 늘 분석·등록 시점의 디스크 크기).
                    if (e.getFileSize() == null || e.getFileSize() != f.length()) {
                        forgetAnalysis(e, "reappeared");
                        e.setFileSize(f.length());
                        e.setCompressed(f.getName().toLowerCase(Locale.ROOT).endsWith(".gz"));
                    }
                    e.setFileDeleted(false);
                    e = repository.save(e);
                    byName.put(e.getFilename(), e);
                }
                out.add(e);
            }
        }
        for (GcLogAnalysisEntity e : byName.values()) {
            if (!out.contains(e) && !e.isFileDeleted() && !fileOf(e.getFilename()).isFile()) {
                e.setFileDeleted(true);
                repository.save(e);
            }
        }
        out.sort((a, b) -> Long.compare(b.getFileSize() == null ? 0 : fileOf(b.getFilename()).lastModified(),
                a.getFileSize() == null ? 0 : fileOf(a.getFilename()).lastModified()));
        return out;
    }

    public List<GcLogAnalysisEntity> history() { return repository.findAllByOrderByCreatedAtDesc(); }

    public Optional<GcLogAnalysisEntity> find(String safeFilename) { return repository.findByFilename(safeFilename); }

    // ── 업로드 검증 ──────────────────────────────────────────────

    /** 첫 64KB 로 형식 판별 — GC 로그가 아니면 예외(400). gz 는 해제해서 본다. */
    public GcLogFormat sniffFormat(File f) throws IOException {
        try (InputStream raw = new FileInputStream(f)) {
            InputStream in = raw;
            byte[] head = raw.readNBytes(2);
            in = new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(head), raw);
            if (head.length == 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) in = new GZIPInputStream(in);
            var dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, dec));
            List<String> lines = new ArrayList<>();
            String line;
            long read = 0;
            while (lines.size() < GcLogFormatDetector.SNIFF_LINES && read < 64 * 1024 && (line = r.readLine()) != null) {
                lines.add(line);
                read += line.length() + 1;
            }
            return GcLogFormatDetector.sniff(lines);
        }
    }

    // ── 분석 실행 ────────────────────────────────────────────────

    public boolean isAnalyzing(String safeFilename) {
        JobProgress p = jobs.get(safeFilename);
        return p != null && p.future != null && !p.future.isDone();
    }

    /** 분석 제출 — 이미 진행 중이면 그 상태를 돌려준다. 파일 없음/등록 없음은 IllegalArgumentException. */
    public synchronized JobProgress submitAnalysis(String safeFilename, String who) {
        if (isAnalyzing(safeFilename)) return jobs.get(safeFilename);
        File f = fileOf(safeFilename);
        if (!f.isFile()) throw new IllegalArgumentException("GC 로그 파일이 없습니다: " + safeFilename);
        GcLogAnalysisEntity e = repository.findByFilename(safeFilename).orElseGet(() -> {
            GcLogAnalysisEntity n = new GcLogAnalysisEntity();
            n.setFilename(safeFilename);
            n.setFileSize(f.length());
            n.setCompressed(safeFilename.toLowerCase(Locale.ROOT).endsWith(".gz"));
            return repository.save(n);
        });
        requireHostname(e);
        List<File> members = memberFilesOf(e);   // 이어 붙인 구성이 저장돼 있으면 그대로 다시 분석한다
        e.setStatus(GcLogAnalysisEntity.STATUS_ANALYZING);
        e.setErrorMessage(null);
        if (e.getUploadedBy() == null) e.setUploadedBy(who);
        repository.save(e);
        JobProgress p = new JobProgress();
        for (File m : members) p.totalBytes += m.length();
        jobs.put(safeFilename, p);
        p.future = executor.submit(() -> runAnalysis(safeFilename, members, p));
        logger.info("[GcLog] action=analyze-submit file={} members={} size={} by={}",
                safeFilename, members.size(), p.totalBytes, who);
        return p;
    }

    /**
     * 분석 시작 전 출처(호스트네임) 확인. 전송본은 {@code registerTransferred} 가 이미 채우므로 수동 업로드만 걸린다.
     *
     * <p>⚠ 프런트에서만 막으면 대시보드 업로드 큐·직접 API 호출 같은 새는 경로가 생긴다. 분석명({@code 호스트_인스턴스_일자})
     * 과 덤프 자동 매칭의 '같은 서버' 신호가 모두 이 값에 달려 있어 여기서 한 번 더 막는다.
     */
    private void requireHostname(GcLogAnalysisEntity e) {
        if (e.getServerName() != null && !e.getServerName().isBlank()) return;
        throw new MissingHostnameException("GC 로그를 수집한 서버(호스트네임)를 입력해야 분석할 수 있습니다. "
                + "결과 화면의 '서버' 항목 연필 버튼으로 입력한 뒤 다시 시도하세요.");
    }

    /** 호스트네임 없이 분석을 시작하려 했다 — 컨트롤러가 {@code NO_HOSTNAME} 400 으로 바꾼다. */
    public static class MissingHostnameException extends IllegalArgumentException {
        public MissingHostnameException(String message) { super(message); }
    }

    private void runAnalysis(String filename, List<File> files, JobProgress p) {
        long t0 = System.currentTimeMillis();
        p.phase = "parsing";
        GcLogAnalysisEntity e = repository.findByFilename(filename).orElse(null);
        if (e == null) return;
        try {
            ParseLimits limits = new ParseLimits(config.getGcLogMaxFileBytes(), config.getGcLogMaxLineChars(), ParseLimits.DEFAULT.maxEvents());
            // ⚠ 끝 시각의 근거는 대표가 아니라 **마지막 구성 파일**의 mtime 이다(가장 최근 로그가 마지막에 온다)
            Long fallback = fallbackEndEpochSec(e, files.isEmpty() ? null : files.get(files.size() - 1));
            final long timeoutAt = t0 + config.getGcLogAnalysisTimeoutMinutes() * 60_000L;
            List<GcLogSource> sources = new ArrayList<>(files.size());
            for (File m : files) sources.add(GcLogSource.of(m));
            GcLogResult r = GcLogEngine.analyze(sources, limits, fallback, (bytes, lines, events) -> {
                p.bytesRead = bytes; p.lines = lines; p.events = events;
                if (System.currentTimeMillis() > timeoutAt) throw new GcLogEngine.GcLogException("TIMEOUT", "분석 시간 상한(" + config.getGcLogAnalysisTimeoutMinutes() + "분)을 넘었습니다.");
                if (Thread.currentThread().isInterrupted()) throw new GcLogEngine.GcLogException("CANCELLED", "분석이 취소되었습니다.");
            });
            p.phase = "saving";
            String json = GcLogResultCodec.toJsonCapped(r);
            GcLogResultDetailEntity d = detailRepository.findByFilename(filename).orElseGet(GcLogResultDetailEntity::new);
            d.setFilename(filename);
            d.setResultJson(json);
            detailRepository.save(d);
            applySummary(e, r);
            e.setStatus(GcLogAnalysisEntity.STATUS_SUCCESS);
            e.setAnalysisTimeMs(System.currentTimeMillis() - t0);
            e.setAnalyzedAt(LocalDateTime.now());
            // ⚠ file_size 는 대표 파일 자신의 크기여야 한다 — 합계를 넣으면 다음 목록 조회가 '다른 파일'로 보고
            // forgetAnalysis 를 돌려 결과가 조용히 사라진다(함정 63). 합계는 members_bytes 에 따로 둔다.
            if (!files.isEmpty()) e.setFileSize(files.get(0).length());
            File own = fileOf(filename);
            if (own.isFile()) e.setFileSize(own.length());
            refreshAnalysisName(e);
            repository.save(e);
            resultCache.put(filename, r);
            p.phase = "done";
            logger.info("[GcLog] action=analyze-done file={} format={} collector={} events={} findings={} severity={} elapsed={}ms json={}B",
                    filename, r.getMeta().getFormat(), r.getMeta().getCollector(), r.getKpi().getEventCount(),
                    r.getFindings().size(), r.getKpi().getSeverity(), e.getAnalysisTimeMs(), json.length());
            matchService.tryAutoMatch(e);
        } catch (GcLogEngine.GcLogException ex) {
            fail(e, ex.code() + ": " + ex.getMessage(), p, t0);
        } catch (Exception ex) {
            // 예상 밖 예외는 위치가 있어야 고칠 수 있다 — 메시지만 남기면 NPE 가 어디서 났는지 알 수 없다(2026-09-14 실제 사례)
            logger.warn("[GcLog] action=analyze-failed file={} unexpected exception", filename, ex);
            fail(e, "내부 오류로 분석하지 못했습니다 (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")", p, t0);
        }
    }

    /**
     * 로그에 절대 시각이 없을 때 로그 끝으로 쓸 시각(epoch 초). 원격 전송의 stat mtime → 전송 파일의 로컬 mtime → 없음(null).
     * 업로드본의 로컬 mtime 은 업로드 시각이라 신뢰할 수 없어 쓰지 않는다(→ time_source=none).
     *
     * <p>⚠ 삼항으로 쓰지 말 것 — {@code cond ? long : (cond ? long : null)} 은 결과 타입이 원시 {@code long} 이 되어
     * 안쪽이 null 을 내는 순간(= 업로드 파일) 언박싱 NPE 가 난다. 2026-09-14 업로드 GC 로그 분석이 전부 이렇게 실패했다.
     */
    static Long fallbackEndEpochSec(GcLogAnalysisEntity e, File f) {
        if (e.getRemoteMtime() != null) return e.getRemoteMtime().atZone(ZoneId.systemDefault()).toEpochSecond();
        if (e.getServerId() != null && f != null && f.lastModified() > 0) return f.lastModified() / 1000L;
        return null;
    }

    private void fail(GcLogAnalysisEntity e, String msg, JobProgress p, long t0) {
        e.setStatus(GcLogAnalysisEntity.STATUS_ERROR);
        e.setErrorMessage(msg);
        e.setAnalysisTimeMs(System.currentTimeMillis() - t0);
        e.setAnalyzedAt(LocalDateTime.now());
        repository.save(e);
        p.phase = "error";
        logger.warn("[GcLog] action=analyze-failed file={} — {}", e.getFilename(), msg);
    }

    private static void applySummary(GcLogAnalysisEntity e, GcLogResult r) {
        GcLogResult.Meta m = r.getMeta();
        GcLogResult.Kpi k = r.getKpi();
        e.setLogFormat(m.getFormat());
        e.setCollector(m.getCollector());
        e.setJdkVersion(trunc(m.getJdkVersion(), 50));
        e.setTimeSource(m.getTimeSource());
        e.setLogStart(m.getLogStartEpochMs() == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(m.getLogStartEpochMs()), ZoneId.systemDefault()));
        e.setLogEnd(m.getLogEndEpochMs() == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(m.getLogEndEpochMs()), ZoneId.systemDefault()));
        e.setEventCount(k.getEventCount());
        e.setFullGcCount(k.getFullCount());
        e.setPressureFullGcCount(r.getFullGcSummary() == null ? null : r.getFullGcSummary().getHeapPressureCount());
        e.setFindingsCount((int) r.getFindings().stream().filter(f -> !"Info".equals(f.getSeverity())).count());
        e.setMaxPauseMs(r.getPauseStats().getMaxMs());
        e.setP99PauseMs(r.getPauseStats().getP99Ms());
        e.setThroughputPct(k.getThroughputPct());
        e.setMaxHeapBytes(k.getMaxHeapTotalBytes());
        e.setSeverity(k.getSeverity());

        // 매칭 시간창 = **대표 파일 자신의 구간**. 이어 붙여 넓어진 범위로 매칭하면 그 안의 덤프가 전부 strong 이 되어
        // ambiguous 로 퇴화하고, 잘 붙어 있던 자동 연결이 재분석 한 번에 지워진다(함정 64).
        Long ws = m.getLogStartEpochMs(), we = m.getLogEndEpochMs();
        if (r.getSegments() != null) {
            for (GcLogResult.Segment sg : r.getSegments()) {
                if (e.getFilename().equals(sg.getFilename())) {
                    if (sg.getFirstTsEpochMs() != null) ws = sg.getFirstTsEpochMs();
                    if (sg.getLastTsEpochMs() != null) we = sg.getLastTsEpochMs();
                    break;
                }
            }
        }
        e.setMatchWindowStart(ws == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(ws), ZoneId.systemDefault()));
        e.setMatchWindowEnd(we == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(we), ZoneId.systemDefault()));
    }

    private static String trunc(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }

    /** 진행 상태 맵 — 폴링 응답. */
    public Map<String, Object> getStatus(String safeFilename) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<GcLogAnalysisEntity> e = repository.findByFilename(safeFilename);
        String status = e.map(GcLogAnalysisEntity::getStatus).orElse(fileOf(safeFilename).isFile() ? GcLogAnalysisEntity.STATUS_NOT_ANALYZED : "MISSING");
        m.put("status", status);
        JobProgress p = jobs.get(safeFilename);
        if (p != null) {
            m.put("phase", p.phase);
            m.put("bytesRead", p.bytesRead);
            m.put("totalBytes", p.totalBytes);
            m.put("lines", p.lines);
            m.put("events", p.events);
            m.put("progressPct", p.totalBytes > 0 ? Math.min(100, Math.round(100.0 * p.bytesRead / p.totalBytes)) : 0);
            m.put("elapsedMs", System.currentTimeMillis() - p.startedAt);
        }
        e.ifPresent(x -> { if (x.getErrorMessage() != null) m.put("error", x.getErrorMessage()); });
        return m;
    }

    // ── 결과 조회 ────────────────────────────────────────────────

    public Optional<GcLogResult> loadResult(String safeFilename) {
        GcLogResult cached = resultCache.get(safeFilename);
        if (cached != null) return Optional.of(cached);
        Optional<GcLogResultDetailEntity> d = detailRepository.findByFilename(safeFilename);
        if (d.isEmpty()) return Optional.empty();
        GcLogResult r = GcLogResultCodec.fromJson(d.get().getResultJson());
        if (r == null) {
            logger.warn("[GcLog] result_json 손상 file={}", safeFilename);
            return Optional.empty();
        }
        resultCache.put(safeFilename, r);
        return Optional.of(r);
    }

    /** 힙 analyze 패널용 축약 — kpi + 시계열 ≤300점 + 소견 top3 + 덤프 시점 오프셋. */
    public Map<String, Object> summaryForDump(String gcLogFilename, AnalysisHistoryEntity dump) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<GcLogResult> or = loadResult(gcLogFilename);
        if (or.isEmpty()) { m.put("available", false); return m; }
        GcLogResult r = or.get();
        m.put("available", true);
        m.put("filename", gcLogFilename);
        m.put("meta", r.getMeta());
        m.put("kpi", r.getKpi());
        m.put("pauseStats", r.getPauseStats());
        m.put("trend", r.getTrend());
        m.put("fullGcSummary", r.getFullGcSummary());
        List<GcLogResult.Finding> top = new ArrayList<>();
        for (GcLogResult.Finding f : r.getFindings()) { if (!"Info".equals(f.getSeverity())) top.add(f); if (top.size() >= 3) break; }
        m.put("findings", top);
        m.put("findingsTotal", r.getFindings().size());
        m.put("series", thinSeries(r.getSeries(), 300));
        Long dumpEpoch = dump == null ? null : JvmHeapInfoService.toEpoch(dump.getDumpCreationTime());
        Map<String, Object> pos = new LinkedHashMap<>();
        if (dumpEpoch != null && r.getMeta().getLogStartEpochMs() != null) {
            double offsetSec = dumpEpoch - r.getMeta().getLogStartEpochMs() / 1000.0;
            double first = r.getMeta().getFirstUptimeSec() == null ? 0 : r.getMeta().getFirstUptimeSec();
            pos.put("uptimeSec", first + offsetSec);
            pos.put("offsetSec", offsetSec);
            pos.put("inside", offsetSec >= 0 && r.getMeta().getDurationSec() != null && offsetSec <= r.getMeta().getDurationSec());
        }
        m.put("dumpPosition", pos);
        return m;
    }

    private static Map<String, Object> thinSeries(GcLogResult.Series s, int max) {
        int n = s.getUptimeSec().size();
        int step = n <= max ? 1 : (int) Math.ceil((double) n / max);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Double> x = new ArrayList<>(); List<Long> after = new ArrayList<>(); List<Long> total = new ArrayList<>(); List<Double> pause = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            x.add(s.getUptimeSec().get(i)); after.add(s.getHeapAfter().get(i)); total.add(s.getHeapTotal().get(i)); pause.add(s.getPauseMs().get(i));
        }
        out.put("uptimeSec", x); out.put("heapAfter", after); out.put("heapTotal", total); out.put("pauseMs", pause);
        return out;
    }

    // ── 삭제·재분석 ─────────────────────────────────────────────

    public void delete(String safeFilename, boolean deleteFile) {
        JobProgress p = jobs.remove(safeFilename);
        if (p != null && p.future != null) p.future.cancel(true);
        resultCache.remove(safeFilename);
        detailRepository.deleteByFilename(safeFilename);
        aiInsight.deleteAiInsight(gcInsightKey(safeFilename));
        Optional<GcLogAnalysisEntity> e = repository.findByFilename(safeFilename);
        // 이어 붙이기 참조는 양방향으로 끊는다 — 유령 링크가 남으면 재분석이 없는 파일을 찾는다.
        // ⚠ 참조만 끊고 다른 파일은 절대 건드리지 않는다(함정 53 — 형제 회전본 보존).
        e.ifPresent(this::clearMemberLinks);
        detachFromGroup(safeFilename);
        if (deleteFile) {
            File f = fileOf(safeFilename);
            if (f.isFile() && !f.delete()) logger.warn("[GcLog] 파일 삭제 실패: {}", f);
            e.ifPresent(repository::delete);
        } else {
            e.ifPresent(x -> { x.setStatus(GcLogAnalysisEntity.STATUS_NOT_ANALYZED); x.setSeverity(null); x.setErrorMessage(null); repository.save(x); });
        }
    }

    public boolean cancel(String safeFilename) {
        JobProgress p = jobs.get(safeFilename);
        if (p == null || p.future == null || p.future.isDone()) return false;
        return p.future.cancel(true);
    }

    /** 수동 호스트명(업로드 로그의 출처). */
    public GcLogAnalysisEntity updateServerName(GcLogAnalysisEntity e, String serverName) {
        String v = serverName == null ? null : serverName.trim();
        if (v != null && v.isEmpty()) v = null;
        if (v != null && v.length() > 100) v = v.substring(0, 100);
        e.setServerName(v);
        refreshAnalysisName(e);   // 분석명이 호스트네임을 담는다 — 바뀌면 이름도 같이 간다
        repository.save(e);
        matchService.tryAutoMatch(e);
        return e;
    }

    // ── LLM ─────────────────────────────────────────────────────

    public boolean isLlmEnabled() { return llmConfig.isLlmEnabled(); }
    public void saveAiInsight(String key, Map<String, Object> data) { aiInsight.saveAiInsight(key, data); }
    public Map<String, Object> loadAiInsight(String key) { return aiInsight.loadAiInsight(key); }
    public boolean deleteAiInsight(String key) { return aiInsight.deleteAiInsight(key); }

    static final String GC_SYSTEM_PROMPT = "당신은 JVM GC 로그 분석 전문가입니다. 주어진 GC 로그 통계(수집기·일시정지·처리량·할당률·Full GC 추세·규칙 기반 이상 징후)와, "
            + "연결된 힙 덤프 정보가 있으면 그것까지 함께 해석해 근본 원인과 튜닝 권고를 제시하세요. 수치는 주어진 값만 인용하고 추측 수치를 만들지 마세요. "
            + "Full GC 는 주어진 분류(메모리 압박/Metaspace/GCLocker/명시적)를 따르세요 — 명시적 호출(System.gc()·힙 덤프)의 Full GC 는 메모리 압박 신호가 아니며, "
            + "메모리 압박 Full GC 의 회수율이 낮거나 직후 점유율이 높으면 누수·힙 부족을 먼저 의심하세요. "
            + "recommendations 는 우선순위가 높은 3개까지, gcTuningAdvice 에는 구체적 JVM 옵션을 적으세요. severity 는 Critical|High|Medium|Low 네 값 중 하나만 쓰세요. "
            + "반드시 마크다운 없이 순수 JSON 만 출력하세요.";

    public Map<String, Object> analyzeWithAi(String safeFilename) {
        GcLogAnalysisEntity e = repository.findByFilename(safeFilename).orElse(null);
        Optional<GcLogResult> r = loadResult(safeFilename);
        if (e == null || r.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "NO_RESULT");
            err.put("error", "분석 결과가 없습니다. GC 로그를 먼저 분석하세요.");
            return err;
        }
        AnalysisHistoryEntity dump = e.getMatchedDumpFilename() == null ? null
                : historyRepository.findByFilename(e.getMatchedDumpFilename()).orElse(null);
        String prompt = buildGcPrompt(r.get(), e, dump);
        Map<String, Object> res = llmConfig.callLlmAnalysis(prompt, GC_SYSTEM_PROMPT);
        // 응답 모양 정규화(2026-09-16) — severity 4값·recommendations 리스트·길이 상한. 컨트롤러가 이 data 를 그대로 저장하므로 조회도 같은 모양이다
        if (res != null && Boolean.TRUE.equals(res.get("success")) && res.get("data") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) res.get("data");
            res.put("data", GcAiResponseNormalizer.normalize(data));
        }
        return res;
    }

    /** LLM 프롬프트 — 서버 측 조립(코어덤프 buildCrashPrompt 와 같은 방식). ≤ 12k chars. */
    public String buildGcPrompt(GcLogResult r, GcLogAnalysisEntity e, AnalysisHistoryEntity dump) {
        StringBuilder sb = new StringBuilder(8192);
        GcLogResult.Meta m = r.getMeta();
        GcLogResult.Kpi k = r.getKpi();
        sb.append("다음 JVM GC 로그 분석 결과를 해석하고 근본 원인·권고를 제시하세요.\n");
        sb.append("\n== GC 로그 개요 ==\n");
        sb.append("파일: ").append(e.getFilename()).append('\n');
        if (e.getServerName() != null) sb.append("출처 서버: ").append(e.getServerName()).append('\n');
        sb.append("형식: ").append(m.getFormat()).append(" / 수집기: ").append(m.getCollector()).append(" / JDK: ").append(nz(m.getJdkVersion())).append('\n');
        sb.append("기간: ").append(fmtDur(m.getDurationSec())).append(" (시간 출처: ").append(m.getTimeSource()).append(")");
        if (m.getLogStartEpochMs() != null) sb.append(", ").append(epochToLocal(m.getLogStartEpochMs())).append(" ~ ").append(epochToLocal(m.getLogEndEpochMs()));
        sb.append('\n');
        sb.append("이벤트: ").append(k.getEventCount()).append("건 (Young ").append(k.getYoungCount()).append(", Mixed ").append(k.getMixedCount())
          .append(", Full ").append(k.getFullCount()).append(", Concurrent ").append(k.getConcurrentCount()).append(")\n");
        if (k.getMaxHeapTotalBytes() != null) sb.append("최대 힙 용량: ").append(FormatUtils.formatBytes(k.getMaxHeapTotalBytes())).append('\n');
        if (m.isTruncated()) sb.append("주의: 로그가 상한 또는 미종결로 일부만 반영됨\n");

        GcLogResult.PauseStats ps = r.getPauseStats();
        sb.append("\n== 일시정지 통계 ==\n");
        sb.append(String.format(Locale.ROOT, "횟수 %d, 합계 %.0fms, 평균 %s, p50 %s, p95 %s, p99 %s, 최대 %s%s\n",
                ps.getCount(), ps.getTotalMs(), ms(ps.getAvgMs()), ms(ps.getP50Ms()), ms(ps.getP95Ms()), ms(ps.getP99Ms()), ms(ps.getMaxMs()),
                ps.getMaxDescription() == null ? "" : " (" + ps.getMaxDescription() + ")"));
        if (r.getFullPauseStats().getCount() > 0) sb.append(String.format(Locale.ROOT, "Full GC 일시정지: %d회, 평균 %s, 최대 %s\n",
                r.getFullPauseStats().getCount(), ms(r.getFullPauseStats().getAvgMs()), ms(r.getFullPauseStats().getMaxMs())));

        sb.append("\n== 처리량·할당·승격 ==\n");
        sb.append("처리량: ").append(k.getThroughputPct() == null ? "?" : String.format(Locale.ROOT, "%.2f%%", k.getThroughputPct())).append('\n');
        if (k.getAllocationRateMbPerSec() != null) sb.append(String.format(Locale.ROOT, "할당률: %.1f MB/s\n", k.getAllocationRateMbPerSec()));
        if (k.getPromotionRateMbPerSec() != null) sb.append(String.format(Locale.ROOT, "승격률: %.3f MB/s\n", k.getPromotionRateMbPerSec()));
        if (k.getMaxOverheadPct10m() != null) sb.append(String.format(Locale.ROOT, "10분 창 최대 GC 오버헤드: %.1f%% (uptime %s)\n", k.getMaxOverheadPct10m(), fmtDur(k.getMaxOverheadWindowStartSec())));
        if (k.getMetaspaceLastBytes() != null) sb.append(Boolean.TRUE.equals(m.getPermGen()) ? "PermGen: " : "Metaspace: ").append(FormatUtils.formatBytes(nz(k.getMetaspaceFirstBytes()))).append(" → ").append(FormatUtils.formatBytes(k.getMetaspaceLastBytes())).append('\n');

        GcLogResult.Trend t = r.getTrend();
        sb.append("\n== Full GC·힙 추세 ==\n");
        sb.append("Full GC: ").append(k.getFullCount()).append("회");
        if (k.getFullGcPerHour() != null) sb.append(String.format(Locale.ROOT, " (시간당 %.2f회, 연속 최대 %d회)", k.getFullGcPerHour(), k.getMaxConsecutiveFull()));
        sb.append(explicitGcNote(k)).append(pressureNote(r.getFullGcSummary())).append('\n');
        if (t.getSlopeMbPerHour() != null) {
            sb.append(String.format(Locale.ROOT, "%s 직후 힙 회귀: 기울기 %+.2f MB/h, R²=%.2f, %d점, %s → %s\n",
                    t.getBasis(), t.getSlopeMbPerHour(), t.getR2(), t.getPointsUsed(), FormatUtils.formatBytes(t.getFirstAfterBytes().longValue()), FormatUtils.formatBytes(t.getLastAfterBytes().longValue())));
            if (t.getNote() != null) sb.append("힙 추세 판정: ").append(t.getNote()).append('\n');
        } else if (t.getNote() != null) sb.append("힙 추세: ").append(t.getNote()).append('\n');
        if (t.getExcludedWarmup() > 0) sb.append("(기동 직후 5분 이내 점 ").append(t.getExcludedWarmup()).append("개는 워밍업으로 보고 추세에서 제외)\n");
        int shown = 0;
        for (double[] p : t.getAfterPoints()) {
            if (shown++ >= 20) break;
            sb.append(String.format(Locale.ROOT, "  uptime %s: %s\n", fmtDur(p[0]), FormatUtils.formatBytes((long) p[1])));
        }
        if (k.getLastHeapAfterBytes() != null && k.getMaxHeapTotalBytes() != null) sb.append(String.format(Locale.ROOT, "마지막 GC 직후 사용량: %s (%.0f%%)\n",
                FormatUtils.formatBytes(k.getLastHeapAfterBytes()), 100.0 * k.getLastHeapAfterBytes() / k.getMaxHeapTotalBytes()));

        sb.append(fullGcClassificationSection(r));

        sb.append("\n== 이상 징후 (규칙 기반) ==\n");
        boolean any = false;
        for (GcLogResult.Finding f : r.getFindings()) {
            if ("Info".equals(f.getSeverity())) continue;
            any = true;
            sb.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append(": ").append(f.getDetail()).append('\n');
        }
        if (!any) sb.append("(없음)\n");
        if (!r.getCounts().getByCause().isEmpty()) sb.append("GC 원인 분포: ").append(r.getCounts().getByCause()).append('\n');
        if (!r.getCounts().getByFlag().isEmpty()) sb.append("플래그: ").append(r.getCounts().getByFlag()).append('\n');

        List<String> opts = jvmOptions(r, e);
        if (!opts.isEmpty()) sb.append("\n== JVM 옵션 ==\n").append(String.join(" ", opts)).append('\n');

        if (dump != null) {
            sb.append("\n== 연결된 힙 덤프 ==\n");
            sb.append("파일: ").append(dump.getFilename()).append(" (").append(e.getMatchSource()).append(" 매칭)\n");
            if (dump.getDumpCreationTime() != null) sb.append("덤프 생성 시각: ").append(dump.getDumpCreationTime()).append('\n');
            Map<String, Object> pos = summaryForDump(e.getFilename(), dump);
            @SuppressWarnings("unchecked") Map<String, Object> dp = (Map<String, Object>) pos.get("dumpPosition");
            if (dp != null && dp.get("offsetSec") != null) sb.append(String.format(Locale.ROOT, "로그 시작 후 %s 시점 (%s)\n", fmtDur((Double) dp.get("offsetSec")), Boolean.TRUE.equals(dp.get("inside")) ? "로그 범위 안" : "로그 범위 밖"));
            if (dump.getUsedHeapSize() != null && dump.getTotalHeapSize() != null) sb.append("덤프 힙: used ").append(FormatUtils.formatBytes(dump.getUsedHeapSize())).append(" / total ").append(FormatUtils.formatBytes(dump.getTotalHeapSize())).append('\n');
            if (dump.getSuspectCount() != null) sb.append("Leak Suspects: ").append(dump.getSuspectCount()).append("건\n");
        }

        sb.append("\n다음 JSON 형식으로만 답하세요:\n");
        sb.append("{\"summary\": \"핵심 요약 2~3문장\", \"rootCause\": \"근본 원인 추정\", \"recommendations\": [\"권고 1\", \"권고 2\", \"권고 3\"], ");
        sb.append("\"severity\": \"Critical|High|Medium|Low\", \"severityDesc\": \"심각도 근거\", \"gcTuningAdvice\": \"구체적 JVM 옵션 제안\"}\n");
        String out = sb.toString();
        return out.length() > 12_000 ? out.substring(0, 12_000) + "\n...(truncated)" : out;
    }

    /**
     * Full GC 줄 뒤에 붙이는 명시적 호출 설명 — LLM 이 System.gc() Full 을 메모리 압박으로 읽지 않게 한다(2026-09-15).
     * 옛 결과(필드 없음)는 빈 문자열.
     */
    static String explicitGcNote(GcLogResult.Kpi k) {
        if (k.getExplicitFullCount() <= 0 && k.getSystemGcCount() <= 0) return "";
        StringBuilder sb = new StringBuilder(" — 그중 명시적 호출(System.gc() 등) Full ").append(k.getExplicitFullCount()).append("회");
        if (k.getSystemGcIntervalSec() != null) sb.append(String.format(Locale.ROOT, ", System.gc() 약 %.0f분 간격 규칙적", k.getSystemGcIntervalSec() / 60));
        if (k.getPressureFullGcPerHour() != null) sb.append(String.format(Locale.ROOT, ", 메모리 압박 Full GC 시간당 %.2f회", k.getPressureFullGcPerHour()));
        return sb.append(" (명시적 Full 은 Old 부족 신호가 아님)").toString();
    }

    /** 압박 Full GC 한 줄 요약 — Full GC 줄 뒤에 붙인다. 블록이 없거나(옛 결과) 압박 Full 이 0 이면 빈 문자열. */
    static String pressureNote(GcLogResult.FullGcSummary s) {
        if (s == null || s.getHeapPressureCount() <= 0) return "";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, " · 메모리 압박 Full GC %d회", s.getHeapPressureCount()));
        List<String> parts = new ArrayList<>();
        if (s.getReclaimPctMedian() != null) parts.add(String.format(Locale.ROOT, "회수율 중앙값 %.0f%%", s.getReclaimPctMedian()));
        if (s.getLastAfterCapRatio() != null) parts.add(String.format(Locale.ROOT, "마지막 직후 최대 힙 대비 %.0f%%", s.getLastAfterCapRatio() * 100));
        if (!parts.isEmpty()) sb.append('(').append(String.join(", ", parts)).append(')');
        return sb.toString();
    }

    /**
     * {@code == Full GC 분류 ==} 섹션(2026-09-16) — 원인별 분류·압박 원인 분포·회수율·간격 추세·마지막 압박 Full·최저 회수 표본.
     * 블록이 없는 옛 결과는 빈 문자열(재분석 전에는 LLM 도 분류를 모른다).
     */
    static String fullGcClassificationSection(GcLogResult r) {
        GcLogResult.FullGcSummary s = r.getFullGcSummary();
        if (s == null) return "";
        Map<String, Integer> bk = s.getByKind();
        StringBuilder sb = new StringBuilder("\n== Full GC 분류 ==\n");
        sb.append(String.format(Locale.ROOT, "전체 %d회 = 메모리 압박 %d회 · Metaspace %d회 · GCLocker %d회 · 명시적 %d회 · 기타 %d회\n", s.getTotal(),
                bk.getOrDefault("HEAP_PRESSURE", 0), bk.getOrDefault("METASPACE", 0), bk.getOrDefault("GC_LOCKER", 0), bk.getOrDefault("EXPLICIT", 0), bk.getOrDefault("OTHER", 0)));
        if (s.getHeapPressureCount() <= 0) {
            sb.append(s.getTotal() > 0 ? "압박 Full GC 없음 — 힙이 차서 일어난 Full GC 는 없습니다(명시적 호출·Metaspace·GCLocker 뿐)\n" : "Full GC 없음\n");
            return sb.toString();
        }
        StringBuilder causes = new StringBuilder();
        s.getPressureByCause().forEach((c, n) -> causes.append(causes.length() > 0 ? ", " : "").append(c).append(' ').append(n));
        sb.append("압박 Full GC 원인: ").append(causes.length() > 0 ? causes : "(원인 미기록)");
        if (s.getHeapPressurePerHour() != null) sb.append(String.format(Locale.ROOT, " — 시간당 %.2f회, 연속 최대 %d회", s.getHeapPressurePerHour(), s.getMaxConsecutivePressure()));
        sb.append('\n');
        if (s.getReclaimSamples() > 0) {
            sb.append(String.format(Locale.ROOT, "압박 Full GC 회수율: 중앙값 %.0f%% · 최저 %.0f%% · 20%% 미만 %d회 (표본 %d회)\n",
                    nzd(s.getReclaimPctMedian()), nzd(s.getReclaimPctMin()), s.getLowReclaimCount(), s.getReclaimSamples()));
        } else if (s.getNote() != null) sb.append(s.getNote()).append('\n');
        if (s.getIntervalMedianFirstHalfSec() != null && s.getIntervalMedianSecondHalfSec() != null) {
            sb.append(String.format(Locale.ROOT, "압박 Full GC 간격: 앞 절반 중앙값 %s → 뒤 절반 %s (%s)\n", fmtDur(s.getIntervalMedianFirstHalfSec()), fmtDur(s.getIntervalMedianSecondHalfSec()),
                    Boolean.TRUE.equals(s.getIntervalShrinking()) ? "짧아짐" : s.getIntervalShrinking() == null ? "표본 부족" : "유지"));
        }
        if (s.getLastAfterBytes() != null) {
            sb.append(String.format(Locale.ROOT, "마지막 압박 Full GC: uptime %s, 줄 %d, %s → %s / %s", fmtDur(s.getLastAtSec()), s.getLastLine() == null ? 0 : s.getLastLine(),
                    FormatUtils.formatBytes(nz(s.getLastBeforeBytes())), FormatUtils.formatBytes(s.getLastAfterBytes()), FormatUtils.formatBytes(nz(s.getLastTotalBytes()))));
            if (s.getLastAfterRatio() != null) sb.append(String.format(Locale.ROOT, " (%.0f%%)", s.getLastAfterRatio() * 100));
            if (s.getLastAfterCapRatio() != null) sb.append(String.format(Locale.ROOT, ", 최대 힙 대비 %.0f%%", s.getLastAfterCapRatio() * 100));
            sb.append('\n');
        }
        if (!s.getWorstReclaim().isEmpty()) {
            sb.append("회수율 최저 ").append(Math.min(3, s.getWorstReclaim().size())).append("건:");
            int n = 0;
            for (GcLogResult.FullGcSample w : s.getWorstReclaim()) {
                if (n++ >= 3) break;
                sb.append(n > 1 ? ", " : " ").append(String.format(Locale.ROOT, "줄 %d %s→%s (%.0f%%)", w.getLine(),
                        FormatUtils.formatBytes(nz(w.getHeapBefore())), FormatUtils.formatBytes(nz(w.getHeapAfter())), nzd(w.getReclaimPct())));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static double nzd(Double v) { return v == null ? 0 : v; }

    /** 힙 AI 프롬프트에 끼워 넣는 {@code == GC 로그 요약 ==} 섹션(≤1500자). 미연결이면 빈 문자열. */
    public String buildGcPromptSectionForDump(String dumpFilename) {
        if (dumpFilename == null || dumpFilename.isEmpty()) return "";
        try {
            List<GcLogAnalysisEntity> logs = repository.findByMatchedDumpFilename(dumpFilename);
            GcLogAnalysisEntity e = logs.stream().filter(x -> GcLogAnalysisEntity.STATUS_SUCCESS.equals(x.getStatus())).findFirst().orElse(null);
            if (e == null) return "";
            Optional<GcLogResult> or = loadResult(e.getFilename());
            if (or.isEmpty()) return "";
            GcLogResult r = or.get();
            GcLogResult.Kpi k = r.getKpi();
            StringBuilder sb = new StringBuilder();
            sb.append("== GC 로그 요약 ==\n");
            sb.append("(이 덤프에 ").append(e.getMatchSource()).append(" 매칭된 GC 로그 ").append(e.getFilename()).append(" — 수집기 ").append(r.getMeta().getCollector())
              .append(", 기간 ").append(fmtDur(r.getMeta().getDurationSec())).append(")\n");
            sb.append(String.format(Locale.ROOT, "처리량 %s, 일시정지 p99 %s / 최대 %s, Full GC %d회", k.getThroughputPct() == null ? "?" : String.format(Locale.ROOT, "%.1f%%", k.getThroughputPct()),
                    ms(r.getPauseStats().getP99Ms()), ms(r.getPauseStats().getMaxMs()), k.getFullCount()));
            if (k.getFullGcPerHour() != null) sb.append(String.format(Locale.ROOT, " (시간당 %.2f회)", k.getFullGcPerHour()));
            sb.append(explicitGcNote(k)).append(pressureNote(r.getFullGcSummary())).append('\n');
            GcLogResult.FullGcSummary fs = r.getFullGcSummary();
            if (fs != null && fs.getHeapPressureCount() > 0 && fs.getReclaimSamples() > 0) {
                sb.append(String.format(Locale.ROOT, "압박 Full GC 회수율 중앙값 %.0f%% · 20%% 미만 %d회", nzd(fs.getReclaimPctMedian()), fs.getLowReclaimCount()));
                if (Boolean.TRUE.equals(fs.getIntervalShrinking())) sb.append(" · 간격 짧아지는 중");
                sb.append('\n');
            }
            GcLogResult.Trend t = r.getTrend();
            if (t.getSlopeMbPerHour() != null) sb.append(String.format(Locale.ROOT, "%s 직후 힙 추세: %+.2f MB/h (R²=%.2f)%s\n", t.getBasis(), t.getSlopeMbPerHour(), t.getR2(),
                    Boolean.FALSE.equals(t.getSignificant()) && t.getNote() != null ? " — " + t.getNote() : ""));
            int n = 0;
            for (GcLogResult.Finding f : r.getFindings()) {
                if ("Info".equals(f.getSeverity())) continue;
                sb.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append('\n');
                if (++n >= 3) break;
            }
            AnalysisHistoryEntity dump = historyRepository.findByFilename(dumpFilename).orElse(null);
            Map<String, Object> pos = summaryForDump(e.getFilename(), dump);
            @SuppressWarnings("unchecked") Map<String, Object> dp = (Map<String, Object>) pos.get("dumpPosition");
            if (dp != null && dp.get("offsetSec") != null) sb.append("덤프 시점: 로그 시작 후 ").append(fmtDur((Double) dp.get("offsetSec"))).append(Boolean.TRUE.equals(dp.get("inside")) ? "" : " (로그 범위 밖)").append('\n');
            String s = sb.toString();
            return s.length() > 1500 ? s.substring(0, 1500) : s;
        } catch (Exception ex) {
            logger.debug("[GcLog] prompt section failed dump={} — {}", dumpFilename, ex.getMessage());
            return "";
        }
    }

    private static List<String> jvmOptions(GcLogResult r, GcLogAnalysisEntity e) {
        if (!r.getJvmOptions().isEmpty()) return r.getJvmOptions();
        com.heapdump.analyzer.util.JvmHeapCapture.Capture cap = com.heapdump.analyzer.util.JvmHeapCapture.fromJson(e.getJvmInfo());
        if (cap != null && cap.matched() != null) return cap.matched().options();
        return List.of();
    }

    private static String nz(String s) { return s == null ? "?" : s; }
    private static long nz(Long v) { return v == null ? 0 : v; }
    private static String ms(Double v) { return v == null ? "?" : String.format(Locale.ROOT, "%.1fms", v); }
    private static String epochToLocal(Long ms) {
        return ms == null ? "?" : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString().replace('T', ' ');
    }
    static String fmtDur(Double sec) {
        if (sec == null) return "?";
        long s = Math.round(sec);
        if (s >= 3600) return String.format(Locale.ROOT, "%dh %02dm", s / 3600, (s % 3600) / 60);
        if (s >= 60) return String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60);
        return String.format(Locale.ROOT, "%.1fs", sec);
    }

    /** 실행 중 작업이 있는지(활동 가드·종료 확인용). */
    public int activeJobs() {
        int n = 0;
        for (JobProgress p : jobs.values()) if (p.future != null && !p.future.isDone()) n++;
        return n;
    }

    /** 고아 작업 정리 — 완료 후 10분 지난 진행 상태를 지운다. */
    public void pruneJobs() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        jobs.entrySet().removeIf(en -> en.getValue().future != null && en.getValue().future.isDone() && en.getValue().startedAt < cutoff);
    }
}

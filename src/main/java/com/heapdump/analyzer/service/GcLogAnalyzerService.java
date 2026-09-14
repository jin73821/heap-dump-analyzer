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
import com.heapdump.analyzer.parser.gclog.ParseLimits;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.DumpTransferLogRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.repository.GcLogResultDetailRepository;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
        e = repository.save(e);
        logger.info("[GcLog] action=register source=transfer file={} server={} remotePath={}", e.getFilename(), server.getName(), log.getRemotePath());
        matchService.tryAutoMatch(e);
        return e;
    }

    /** 업로드 — 출처 서버 없음(호스트명 수동 입력 가능). */
    public GcLogAnalysisEntity registerUploaded(String safeFilename, long size, String who, String serverName) {
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
        if (serverName != null && !serverName.isBlank()) e.setServerName(serverName.trim().length() > 100 ? serverName.trim().substring(0, 100) : serverName.trim());
        e = repository.save(e);
        logger.info("[GcLog] action=register source=upload file={} size={} server={} by={}", safeFilename, size, e.getServerName(), who);
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
        repository.findByFilename(safe).ifPresent(repository::delete);
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
        logger.info("[GcLog] action=forget-previous file={} reason={} hadResult={}", fn, reason, hadResult);
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
        e.setStatus(GcLogAnalysisEntity.STATUS_ANALYZING);
        e.setErrorMessage(null);
        if (e.getUploadedBy() == null) e.setUploadedBy(who);
        repository.save(e);
        JobProgress p = new JobProgress();
        p.totalBytes = f.length();
        jobs.put(safeFilename, p);
        p.future = executor.submit(() -> runAnalysis(safeFilename, f, p));
        logger.info("[GcLog] action=analyze-submit file={} size={} by={}", safeFilename, f.length(), who);
        return p;
    }

    private void runAnalysis(String filename, File f, JobProgress p) {
        long t0 = System.currentTimeMillis();
        p.phase = "parsing";
        GcLogAnalysisEntity e = repository.findByFilename(filename).orElse(null);
        if (e == null) return;
        try {
            ParseLimits limits = new ParseLimits(config.getGcLogMaxFileBytes(), config.getGcLogMaxLineChars(), ParseLimits.DEFAULT.maxEvents());
            Long fallback = fallbackEndEpochSec(e, f);
            final long timeoutAt = t0 + config.getGcLogAnalysisTimeoutMinutes() * 60_000L;
            GcLogResult r;
            try (InputStream in = new FileInputStream(f)) {
                r = GcLogEngine.analyze(in, limits, fallback, (bytes, lines, events) -> {
                    p.bytesRead = bytes; p.lines = lines; p.events = events;
                    if (System.currentTimeMillis() > timeoutAt) throw new GcLogEngine.GcLogException("TIMEOUT", "분석 시간 상한(" + config.getGcLogAnalysisTimeoutMinutes() + "분)을 넘었습니다.");
                    if (Thread.currentThread().isInterrupted()) throw new GcLogEngine.GcLogException("CANCELLED", "분석이 취소되었습니다.");
                });
            }
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
            e.setFileSize(f.length());
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
        e.setFindingsCount((int) r.getFindings().stream().filter(f -> !"Info".equals(f.getSeverity())).count());
        e.setMaxPauseMs(r.getPauseStats().getMaxMs());
        e.setP99PauseMs(r.getPauseStats().getP99Ms());
        e.setThroughputPct(k.getThroughputPct());
        e.setMaxHeapBytes(k.getMaxHeapTotalBytes());
        e.setSeverity(k.getSeverity());
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
            + "recommendations 는 우선순위가 높은 3개까지, gcTuningAdvice 에는 구체적 JVM 옵션을 적으세요. 반드시 마크다운 없이 순수 JSON 만 출력하세요.";

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
        return llmConfig.callLlmAnalysis(prompt, GC_SYSTEM_PROMPT);
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
        sb.append('\n');
        if (t.getSlopeMbPerHour() != null) sb.append(String.format(Locale.ROOT, "%s 직후 힙 회귀: 기울기 %+.1f MB/h, R²=%.2f, %d점, %s → %s\n",
                t.getBasis(), t.getSlopeMbPerHour(), t.getR2(), t.getPointsUsed(), FormatUtils.formatBytes(t.getFirstAfterBytes().longValue()), FormatUtils.formatBytes(t.getLastAfterBytes().longValue())));
        else if (t.getNote() != null) sb.append("힙 추세: ").append(t.getNote()).append('\n');
        if (t.getExcludedWarmup() > 0) sb.append("(기동 직후 5분 이내 점 ").append(t.getExcludedWarmup()).append("개는 워밍업으로 보고 추세에서 제외)\n");
        int shown = 0;
        for (double[] p : t.getAfterPoints()) {
            if (shown++ >= 20) break;
            sb.append(String.format(Locale.ROOT, "  uptime %s: %s\n", fmtDur(p[0]), FormatUtils.formatBytes((long) p[1])));
        }
        if (k.getLastHeapAfterBytes() != null && k.getMaxHeapTotalBytes() != null) sb.append(String.format(Locale.ROOT, "마지막 GC 직후 사용량: %s (%.0f%%)\n",
                FormatUtils.formatBytes(k.getLastHeapAfterBytes()), 100.0 * k.getLastHeapAfterBytes() / k.getMaxHeapTotalBytes()));

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
            sb.append('\n');
            GcLogResult.Trend t = r.getTrend();
            if (t.getSlopeMbPerHour() != null) sb.append(String.format(Locale.ROOT, "%s 직후 힙 추세: %+.1f MB/h (R²=%.2f)\n", t.getBasis(), t.getSlopeMbPerHour(), t.getR2()));
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

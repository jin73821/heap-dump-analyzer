package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.model.entity.ThreadDumpResultDetailEntity;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpEngine;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpException;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpFormatDetector;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpLimits;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpResultCodec;
import com.heapdump.analyzer.repository.ThreadDumpAnalysisRepository;
import com.heapdump.analyzer.repository.ThreadDumpResultDetailRepository;
import com.heapdump.analyzer.util.FilenameValidator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 스레드 덤프 등록·분석·결과 보관 (2026-11-26). {@code GcLogAnalyzerService} 의 구조를 따른다 — 자체 저장소
 * ({@code threaddump.directory}), 자체 실행기, 진행은 {@link #getStatus} 폴링, 결과 JSON 은 DB.
 *
 * <h3>분석이 실패해도 행이 ANALYZING 에 남지 않게 하는 네 겹</h3>
 * <ol>
 *   <li>{@link #runAnalysis} 는 {@code Throwable} 을 잡는다 — {@code OutOfMemoryError}·{@code StackOverflowError} 는
 *       {@code Exception} 이 아니라 Future 가 조용히 삼키고, 그러면 아무 로그도 없이 "분석 중" 이 영원히 남는다.</li>
 *   <li>대기 중(queued) 작업을 취소하면 {@code runAnalysis} 가 아예 돌지 않는다 — {@link #cancel} 이 직접 ERROR 를 쓴다.</li>
 *   <li>재기동으로 끊긴 작업은 {@link #recoverInterrupted} 가 기동 직후 ERROR(INTERRUPTED) 로 돌린다.</li>
 *   <li>그래도 남은 행은 {@link #getStatus} 가 "DB 는 분석 중인데 살아 있는 작업이 없다" 를 보고 고친다.</li>
 * </ol>
 *
 * <p>실행기는 1스레드다. 운영 힙이 작아(-Xmx512m) 대형 덤프 두 개를 동시에 분석하면 위험하다.
 */
@Service
public class ThreadDumpAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(ThreadDumpAnalyzerService.class);
    private static final int RESULT_CACHE = 2;
    private static final int MAX_FILENAME_LEN = 480;   // ai_insights.filename(500) − "__threaddump__:" 접두 여유

    private final HeapDumpConfig config;
    private final ThreadDumpAnalysisRepository repository;
    private final ThreadDumpResultDetailRepository detailRepository;
    private final LlmConfigService llmConfig;
    private final AiInsightManager aiInsight;

    /**
     * 힙 덤프 스레드 분석(2.7.1) — 힙 덤프 연결(후보·스레드별 힙 점유)에 쓴다. 선택 주입: 없으면 연결 기능만 꺼진다.
     * 생성자에 넣지 않은 것은 이 서비스를 직접 만드는 테스트를 지키려는 것이다.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    HeapThreadService heapThreads;

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    static final int COMBINE_MAX = 20;

    /** 지금 상태로는 할 수 없는 요청(분석 중인 파일을 묶거나 푸는 것) — API 는 409 로 답한다. 예상 못 한 IllegalStateException(500)과 구분한다. */
    public static class BusyException extends RuntimeException {
        public BusyException(String message) { super(message); }
    }
    /** 힙 덤프에서 내보낼 이름의 파일이 이미 있다 — 화면이 "목록에서 보기 / 새 이름으로" 를 고르게 이름을 싣는다(409). */
    public static class ExportExistsException extends RuntimeException {
        private final String filename;
        public ExportExistsException(String filename) {
            super("이미 내보낸 스레드 덤프 파일이 있습니다: " + filename);
            this.filename = filename;
        }
        public String filename() { return filename; }
    }
    /** 같은 힙 덤프를 '새 이름으로' 여러 번 내보낼 때 붙이는 번호의 상한(_threads-2 … _threads-99). */
    static final int EXPORT_NAME_MAX_SUFFIX = 99;
    /** 내보낸 직후 분석을 기다리는 시간 — 힙 덤프 스레드 텍스트는 작아 보통 1초 안에 끝난다. 앞선 큰 분석이 실행기를 잡고 있으면 '분석 중' 으로 답한다. */
    static long exportAnalyzeWaitSeconds = 15;
    /** 묶음 제안을 위해 미리 분석해 보는 파일 크기 상한 — 이보다 크면 제안에서 뺀다(업로드 직후 화면을 붙잡지 않게). */
    static final long SUGGEST_MAX_BYTES = 64L * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "threaddump-analyzer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, JobProgress> jobs = new ConcurrentHashMap<>();
    private final Map<String, ThreadDumpResult> resultCache = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ThreadDumpResult> e) { return size() > RESULT_CACHE; }
            });

    /** 진행 상태(파일별). */
    public static final class JobProgress {
        public volatile long bytesRead, totalBytes, lines;
        public volatile int snapshots, threads;
        public volatile String phase = "queued";
        public final long startedAt = System.currentTimeMillis();
        volatile Future<?> future;
    }

    /** 업로드 거부 — 컨트롤러가 {@code {success:false, code, error}} 와 HTTP 상태로 바꾼다. */
    public static class UploadRejectedException extends RuntimeException {
        private final String code;
        private final int httpStatus;
        public UploadRejectedException(String code, int httpStatus, String message) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
        }
        public String code() { return code; }
        public int httpStatus() { return httpStatus; }
    }

    /** 업로드 본문을 임시 파일에 쓰는 동작(컨트롤러의 {@code MultipartFile::transferTo}). */
    public interface UploadBody {
        void transferTo(File dest) throws IOException;
    }

    public ThreadDumpAnalyzerService(HeapDumpConfig config,
                                     ThreadDumpAnalysisRepository repository,
                                     ThreadDumpResultDetailRepository detailRepository,
                                     LlmConfigService llmConfig,
                                     AiInsightManager aiInsight) {
        this.config = config;
        this.repository = repository;
        this.detailRepository = detailRepository;
        this.llmConfig = llmConfig;
        this.aiInsight = aiInsight;
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    // ── 경로·검증 ────────────────────────────────────────────────

    public File dumpFilesDir() { return new File(config.getThreadDumpDumpFilesDirectory()); }

    public File tmpDir() { return new File(config.getThreadDumpTmpDirectory()); }

    public File fileOf(String safeFilename) { return new File(dumpFilesDir(), safeFilename); }

    /** 확장자 제한 없음(jstack.12345 / catalina.out / .gz 전부 유효) — 경로 요소·길이만 검사. */
    public String validateFilename(String filename) {
        String safe = FilenameValidator.validateSafe(filename);
        if (safe.length() > MAX_FILENAME_LEN) throw new IllegalArgumentException("파일명이 너무 깁니다(최대 " + MAX_FILENAME_LEN + "자)");
        return safe;
    }

    public static String insightKey(String filename) { return "__threaddump__:" + filename; }

    /** 업로드·분석 크기 상한(바이트) — 화면이 고르는 단계에서 먼저 걸러 준다. */
    public long maxFileBytes() { return config.getThreadDumpMaxFileBytes(); }

    private ThreadDumpLimits limits() {
        return new ThreadDumpLimits(config.getThreadDumpMaxFileBytes(), config.getThreadDumpMaxLineChars(),
                config.getThreadDumpMaxThreadsPerSnapshot(), config.getThreadDumpMaxSnapshots());
    }

    /** 파일 전체를 훑어 스레드 덤프인지 판정한다(gz 해제, 첫 양성에서 끝). */
    public ThreadDumpFormatDetector.Sniff sniff(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return ThreadDumpFormatDetector.scan(in, config.getThreadDumpMaxFileBytes(), config.getThreadDumpMaxLineChars());
        } catch (java.util.zip.ZipException e) {
            logger.debug("[ThreadDump] gzip 판별 실패 file={} — {}", f.getName(), e.getMessage());
            return new ThreadDumpFormatDetector.Sniff(ThreadDumpFormatDetector.Kind.UNKNOWN, 0);
        }
    }

    // ── 업로드 ──────────────────────────────────────────────────

    /**
     * 업로드 한 건을 받아 저장소에 넣는다 — 임시 파일에 받아 형식을 확인한 뒤에만 {@code dumpfiles/} 로 옮긴다.
     * 전송 중인 부분 파일이 목록에 보이거나, 거부된 파일의 유령 기록이 남지 않는다.
     *
     * @throws UploadRejectedException 크기 초과(413)·이름 중복(409)·스레드 덤프 아님/미지원 형식(400)
     */
    public ThreadDumpAnalysisEntity storeUpload(String originalFilename, long size, UploadBody body, String who, String serverName) throws IOException {
        String safe = validateFilename(originalFilename);
        long max = config.getThreadDumpMaxFileBytes();
        if (size > max) {
            throw new UploadRejectedException("TOO_LARGE", 413, "스레드 덤프 상한(" + (max >> 20) + " MB)을 넘는 파일입니다.");
        }
        File target = fileOf(safe);
        if (target.exists()) {
            throw new UploadRejectedException("DUPLICATE_NAME", 409, "같은 이름의 스레드 덤프가 이미 있습니다: " + safe);
        }
        File tmpDir = tmpDir();
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("임시 디렉토리를 만들 수 없습니다: " + tmpDir);
        File tmp = new File(tmpDir, UUID.randomUUID() + ".part");
        try {
            body.transferTo(tmp);
            if (tmp.length() == 0) throw new UploadRejectedException("EMPTY", 400, "빈 파일입니다.");
            ThreadDumpFormatDetector.Sniff sniff = sniff(tmp);
            if (!sniff.supported()) {
                boolean unsupported = sniff.kind() != ThreadDumpFormatDetector.Kind.UNKNOWN;
                throw new UploadRejectedException(unsupported ? "UNSUPPORTED_FORMAT" : "NOT_THREAD_DUMP", 400,
                        ThreadDumpFormatDetector.unsupportedMessage(sniff.kind()));
            }
            File dir = dumpFilesDir();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장 디렉토리를 만들 수 없습니다: " + dir);
            try {
                Files.move(tmp.toPath(), target.toPath());      // 덮어쓰지 않는다 — 동시에 같은 이름이 올라오면 뒤쪽이 진다
            } catch (FileAlreadyExistsException e) {
                throw new UploadRejectedException("DUPLICATE_NAME", 409, "같은 이름의 스레드 덤프가 이미 있습니다: " + safe);
            }
        } finally {
            if (tmp.exists() && !tmp.delete()) logger.warn("[ThreadDump] 임시 파일 삭제 실패: {}", tmp);
        }
        return registerUploaded(safe, target.length(), who, serverName);
    }

    public ThreadDumpAnalysisEntity registerUploaded(String safeFilename, long size, String who, String serverName) {
        return register(safeFilename, size, who, serverName, "upload");
    }

    private ThreadDumpAnalysisEntity register(String safeFilename, long size, String who, String serverName, String source) {
        ThreadDumpAnalysisEntity e = repository.findByFilename(safeFilename).orElseGet(ThreadDumpAnalysisEntity::new);
        // 업로드는 같은 이름 파일이 없을 때만 허용되므로 늘 새 파일이다 — 파일 없이 남은 옛 기록의 결과를 붙이지 않는다
        if (e.getId() != null) forgetAnalysis(e, source);
        e.setFilename(safeFilename);
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setFileSize(size);
        e.setCompressed(safeFilename.toLowerCase(Locale.ROOT).endsWith(".gz"));
        e.setFileDeleted(false);
        e.setUploadedBy(who);
        e.setServerName(cleanServerName(serverName));
        e = repository.save(e);
        logger.info("[ThreadDump] action=register source={} file={} size={} server={} by={}", source, safeFilename, size, e.getServerName(), who);
        return e;
    }

    // ── 힙 덤프 → 스레드 덤프 파일 내보내기 (2.7.1) ─────────────────

    /** @param status 내보낸 뒤 분석 상태 — SUCCESS · ANALYZING(기다리는 동안 안 끝남) · ERROR(이때 {@code error} 에 사유) */
    public record HeapExport(String filename, String heapFilename, int threads, long size, String status, String error) {}

    /**
     * 힙 덤프의 스레드(MAT 산출물을 바꾼 jstack 형식 텍스트)를 스레드 덤프 저장소에 파일로 저장하고 목록에 등록한다.
     * 파일명은 {@code <힙 덤프 이름>_threads.txt}. 이미 있으면 {@code newName=false} 는 {@link ExportExistsException}(409),
     * {@code newName=true} 는 {@code _threads-2.txt} 부터 비어 있는 이름을 쓴다(기존 파일·분석 결과는 건드리지 않는다).
     * 내보낸 파일은 원래 힙 덤프와 <b>연결된 상태</b>로 등록된다 — 같은 덤프에서 나온 것이 확실하므로 추정 연결이 아니다.
     * 힙 화면에서 이미 분석을 본 상태이므로 등록 직후 <b>바로 스레드 덤프로 분석</b>하고 끝나기를 잠시 기다린다
     * — 목록에 '미분석' 으로 올라가 같은 분석을 한 번 더 시작하게 하지 않는다.
     * 임시 파일(tmp/)에 다 쓴 뒤 옮겨 넣어, 목록에 반쯤 쓴 파일이 보이지 않게 한다.
     */
    public HeapExport exportFromHeap(String heapFilename, boolean newName, String who) throws IOException {
        if (heapThreads == null) throw new IllegalArgumentException("힙 덤프 스레드 내보내기를 사용할 수 없습니다.");
        HeapThreadService.ExportSource src = heapThreads.exportSource(heapFilename);
        byte[] bytes = src.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File dir = dumpFilesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장 디렉토리를 만들 수 없습니다: " + dir);
        File tmpDir = tmpDir();
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("임시 디렉토리를 만들 수 없습니다: " + tmpDir);
        File tmp = new File(tmpDir, "export-" + UUID.randomUUID() + ".part");
        String first = validateFilename(exportName(src.baseName(), 1));
        try {
            Files.write(tmp.toPath(), bytes);
            String chosen = null;
            for (int n = 1; n <= (newName ? EXPORT_NAME_MAX_SUFFIX : 1); n++) {
                String name = validateFilename(exportName(src.baseName(), n));
                try {
                    Files.move(tmp.toPath(), fileOf(name).toPath());   // REPLACE 없음 — 있으면 FileAlreadyExistsException
                    chosen = name;
                    break;
                } catch (FileAlreadyExistsException ex) {
                    logger.debug("[ThreadDump] export 이름 사용 중: {}", name);
                }
            }
            if (chosen == null) {
                if (!newName) throw new ExportExistsException(first);
                throw new IllegalArgumentException("내보낼 이름을 찾지 못했습니다(" + src.baseName() + "_threads-2~" + EXPORT_NAME_MAX_SUFFIX + ".txt 모두 사용 중).");
            }
            ThreadDumpAnalysisEntity e = register(chosen, bytes.length, who, src.serverName(), "heap-export");
            e.setLinkedHeapFilename(src.heapFilename());
            e.setLinkedAt(LocalDateTime.now());
            e.setLinkedBy(who);
            repository.save(e);
            JobProgress job = submitAnalysis(chosen, who);
            awaitQuietly(job, chosen);
            ThreadDumpAnalysisEntity after = repository.findByFilename(chosen).orElse(e);
            String status = after.getStatus();
            logger.info("[ThreadDump] action=export-from-heap heap={} file={} threads={} size={} status={} by={}",
                    src.heapFilename(), chosen, src.threads(), bytes.length, status, who);
            return new HeapExport(chosen, src.heapFilename(), src.threads(), bytes.length, status,
                    ThreadDumpAnalysisEntity.STATUS_ERROR.equals(status) ? after.getErrorMessage() : null);
        } finally {
            if (tmp.exists() && !tmp.delete()) logger.warn("[ThreadDump] 임시 파일 삭제 실패: {}", tmp);
        }
    }

    /** 분석이 끝나기를 잠시 기다린다 — 시간 안에 안 끝나면 그대로 두고(실행은 계속된다) 호출자는 '분석 중' 으로 답한다. */
    private void awaitQuietly(JobProgress job, String filename) {
        if (job == null || job.future == null) return;
        try {
            job.future.get(exportAnalyzeWaitSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            logger.info("[ThreadDump] export 후 분석이 {}초 안에 끝나지 않음 — 계속 진행 file={}", exportAnalyzeWaitSeconds, filename);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("[ThreadDump] export 후 분석 대기 중 인터럽트 file={}", filename);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException ex) {
            logger.warn("[ThreadDump] export 후 분석 작업 예외 file={} — {}", filename, ex.toString());
        }
    }

    static String exportName(String base, int n) {
        return base + "_threads" + (n <= 1 ? "" : "-" + n) + ".txt";
    }

    // ── 파일 분류 이동 (Files 페이지 '파일 분류 설정') ─────────────

    /**
     * 다른 저장소(힙·코어·GC 로그)에 있는 파일을 스레드 덤프로 편입한다 — 형식 검사 → 이동 → 등록.
     * 원 저장소 쪽 기록 정리는 호출자(컨트롤러)가 한다(서비스끼리 주입하면 순환 참조로 기동 실패).
     * 형식 검사가 이동보다 먼저라, 거부되면 파일은 원래 자리에 그대로 있다.
     *
     * @throws IllegalArgumentException 스레드 덤프 형식이 아님
     * @throws IllegalStateException    스레드 덤프 저장소에 같은 이름이 이미 있음
     */
    public ThreadDumpAnalysisEntity adoptFile(File source, String who, String serverName) throws IOException {
        String safe = validateFilename(source.getName());
        File target = fileOf(safe);
        if (target.exists()) throw new IllegalStateException("스레드 덤프 저장소에 같은 이름의 파일이 이미 있습니다: " + safe);
        ThreadDumpFormatDetector.Sniff sniff = sniff(source);
        if (!sniff.supported()) {
            throw new IllegalArgumentException(ThreadDumpFormatDetector.unsupportedMessage(sniff.kind())
                    + " 스레드 덤프로 분류할 수 없습니다.");
        }
        File dir = dumpFilesDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("저장 디렉토리를 만들 수 없습니다: " + dir);
        Files.move(source.toPath(), target.toPath());

        Optional<ThreadDumpAnalysisEntity> previous = repository.findByFilename(safe);
        ThreadDumpAnalysisEntity e = previous.orElseGet(ThreadDumpAnalysisEntity::new);
        if (previous.isPresent()) forgetAnalysis(e, "adopt");
        else { resultCache.remove(safe); detailRepository.deleteByFilename(safe); aiInsight.deleteAiInsight(insightKey(safe)); }
        e.setFilename(safe);
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setFileSize(target.length());
        e.setCompressed(safe.toLowerCase(Locale.ROOT).endsWith(".gz"));
        e.setFileDeleted(false);
        if (e.getUploadedBy() == null) e.setUploadedBy(who);
        if (e.getServerName() == null) e.setServerName(cleanServerName(serverName));
        ThreadDumpAnalysisEntity saved = repository.save(e);
        logger.info("[ThreadDump] action=adopt file={} from={} size={} by={}", safe, source.getParent(), target.length(), who);
        return saved;
    }

    /**
     * 스레드 덤프를 다른 저장소로 내보낸다(분류를 다른 유형으로 바꿀 때). 분석 결과·AI 해석은 함께 지운다.
     *
     * @throws IllegalStateException 분석 중이거나 대상 저장소에 같은 이름이 있음
     */
    public void releaseFile(String safeFilename, File targetDir, String who) throws IOException {
        String safe = validateFilename(safeFilename);
        if (isAnalyzing(safe)) throw new IllegalStateException("분석이 진행 중인 스레드 덤프는 분류를 바꿀 수 없습니다.");
        File source = fileOf(safe);
        if (!source.isFile()) throw new IllegalArgumentException("스레드 덤프 파일이 없습니다: " + safe);
        File target = new File(targetDir, safe);
        if (target.exists()) throw new IllegalStateException("대상 저장소에 같은 이름의 파일이 이미 있습니다: " + safe);
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new IOException("대상 디렉토리를 만들 수 없습니다: " + targetDir);
        Files.move(source.toPath(), target.toPath());
        forgetMovedFile(safe, who);
        logger.info("[ThreadDump] action=release file={} to={} by={}", safe, targetDir, who);
    }

    /** 파일이 이미 다른 저장소로 옮겨진 뒤 — 이쪽에 남은 기록·결과·AI 해석을 지운다. */
    public void forgetMovedFile(String safeFilename, String who) {
        detachFromGroup(safeFilename, "moved");
        JobProgress p = jobs.remove(safeFilename);
        if (p != null && p.future != null) p.future.cancel(true);
        resultCache.remove(safeFilename);
        detailRepository.deleteByFilename(safeFilename);
        aiInsight.deleteAiInsight(insightKey(safeFilename));
        repository.findByFilename(safeFilename).ifPresent(repository::delete);
        logger.info("[ThreadDump] action=forget-moved file={} by={}", safeFilename, who);
    }

    /**
     * 같은 이름의 기록을 <b>새 파일</b>로 다시 쓰기 전에 옛 분석의 흔적을 모두 지운다 — 진행 중 작업·결과 캐시·결과 JSON·AI 해석과
     * 엔티티의 요약·오류·출처. 저장은 호출자가 한다.
     */
    void forgetAnalysis(ThreadDumpAnalysisEntity e, String reason) {
        String fn = e.getFilename();
        JobProgress p = jobs.remove(fn);
        if (p != null && p.future != null) p.future.cancel(true);
        resultCache.remove(fn);
        detailRepository.deleteByFilename(fn);
        aiInsight.deleteAiInsight(insightKey(fn));
        clearSummary(e);
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setServerName(null);
        e.setUploadedBy(null);
        // 다른 파일이다 — 옛 파일에 걸려 있던 힙 덤프 연결·묶음은 이어받지 않는다
        if (e.getId() != null) detachFromGroup(fn, reason);
        e.setMemberFiles(null);
        e.setMemberCount(null);
        e.setPartOf(null);
        e.setLinkedHeapFilename(null);
        e.setLinkedAt(null);
        e.setLinkedBy(null);
        logger.info("[ThreadDump] action=forget-previous file={} reason={}", fn, reason);
    }

    private static void clearSummary(ThreadDumpAnalysisEntity e) {
        e.setErrorCode(null);
        e.setErrorMessage(null);
        e.setSnapshotCount(null);
        e.setThreadCount(null);
        e.setDeadlockCount(null);
        e.setBlockedMax(null);
        e.setStuckCount(null);
        e.setFindingCount(null);
        e.setSeverity(null);
        e.setJvmVersion(null);
        e.setJdkMajor(null);
        e.setMiddleware(null);
        e.setFirstSnapshotAt(null);
        e.setLastSnapshotAt(null);
        e.setAnalysisTimeMs(null);
        e.setAnalyzedAt(null);
    }

    // ── 목록 ────────────────────────────────────────────────────

    /**
     * 디스크와 기록을 맞춘다(쓰기) — 기록 없는 파일 등록, 사라진 파일 표시, 다른 크기로 다시 나타난 파일의 옛 결과 삭제.
     * 화면 조회마다 불리므로 {@code synchronized} 로 직렬화하고, 그래도 겹친 등록(unique 위반)은 다시 읽어 흡수한다 —
     * 여기서 예외가 나가면 Files 탭 전체가 빈다.
     *
     * @return 디스크에 실제로 있는 파일의 기록(최근 수정 순)
     */
    public synchronized List<ThreadDumpAnalysisEntity> syncWithDisk() {
        File[] files = dumpFilesDir().listFiles();
        Map<String, ThreadDumpAnalysisEntity> byName = new LinkedHashMap<>();
        for (ThreadDumpAnalysisEntity e : repository.findAllByOrderByCreatedAtDesc()) byName.put(e.getFilename(), e);
        List<ThreadDumpAnalysisEntity> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                try {
                    ThreadDumpAnalysisEntity e = byName.get(f.getName());
                    if (e == null) {
                        e = new ThreadDumpAnalysisEntity();
                        e.setFilename(f.getName());
                        e.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
                        e.setFileSize(f.length());
                        e.setCompressed(f.getName().toLowerCase(Locale.ROOT).endsWith(".gz"));
                        e = repository.save(e);
                        logger.info("[ThreadDump] action=register source=disk file={} size={} by=system", f.getName(), f.length());
                    } else if (e.isFileDeleted()) {
                        // 크기가 기록과 다르면 다른 파일이다 — 옛 결과를 붙인 채 보이면 안 된다
                        if (e.getFileSize() == null || e.getFileSize() != f.length()) {
                            forgetAnalysis(e, "reappeared");
                            e.setFileSize(f.length());
                            e.setCompressed(f.getName().toLowerCase(Locale.ROOT).endsWith(".gz"));
                        }
                        e.setFileDeleted(false);
                        e = repository.save(e);
                    }
                    byName.put(e.getFilename(), e);
                    out.add(e);
                } catch (DataIntegrityViolationException dup) {
                    logger.debug("[ThreadDump] 동시 등록 흡수 file={} — {}", f.getName(), dup.getMessage());
                    repository.findByFilename(f.getName()).ifPresent(out::add);
                } catch (RuntimeException ex) {
                    logger.warn("[ThreadDump] action=sync-failed file={} — {}", f.getName(), ex.toString());
                }
            }
        }
        for (ThreadDumpAnalysisEntity e : byName.values()) {
            if (!e.isFileDeleted() && !fileOf(e.getFilename()).isFile()) {
                try {
                    e.setFileDeleted(true);
                    repository.save(e);
                } catch (RuntimeException ex) {
                    logger.warn("[ThreadDump] action=sync-failed file={} — {}", e.getFilename(), ex.toString());
                }
            }
        }
        out.sort((a, b) -> Long.compare(fileOf(b.getFilename()).lastModified(), fileOf(a.getFilename()).lastModified()));
        return out;
    }

    /** 기록 전체(읽기 전용). 집계 화면은 이것만 쓴다 — {@link #syncWithDisk} 는 DB 를 고친다. */
    public List<ThreadDumpAnalysisEntity> history() { return repository.findAllByOrderByCreatedAtDesc(); }

    public Optional<ThreadDumpAnalysisEntity> find(String safeFilename) { return repository.findByFilename(safeFilename); }

    // ── 분석 ────────────────────────────────────────────────────

    public boolean isAnalyzing(String safeFilename) {
        JobProgress p = jobs.get(safeFilename);
        return p != null && p.future != null && !p.future.isDone();
    }

    /** 분석 제출 — 이미 진행 중이면 그 상태를 돌려준다. 파일이 없으면 IllegalArgumentException. */
    public synchronized JobProgress submitAnalysis(String safeFilename, String who) {
        if (isAnalyzing(safeFilename)) return jobs.get(safeFilename);
        File f = fileOf(safeFilename);
        if (!f.isFile()) throw new IllegalArgumentException("스레드 덤프 파일이 없습니다: " + safeFilename);
        ThreadDumpAnalysisEntity e = repository.findByFilename(safeFilename).orElseGet(() -> {
            ThreadDumpAnalysisEntity n = new ThreadDumpAnalysisEntity();
            n.setFilename(safeFilename);
            n.setCompressed(safeFilename.toLowerCase(Locale.ROOT).endsWith(".gz"));
            return n;
        });
        e.setFileSize(f.length());
        e.setFileDeleted(false);
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_ANALYZING);
        e.setErrorCode(null);
        e.setErrorMessage(null);
        if (e.getUploadedBy() == null) e.setUploadedBy(who);
        repository.save(e);
        // 이어 붙인 묶음이면 저장된 구성 그대로 다시 분석한다. 구성 파일이 사라졌으면 실행에서 FILE_MISSING 으로 끝난다(이름을 알린다)
        List<String> members = memberFilesOf(e);
        JobProgress p = new JobProgress();
        for (String m : members) p.totalBytes += fileOf(m).length();
        jobs.put(safeFilename, p);
        // 수행자는 요청 스레드에서 잡아 넘긴다 — 실행기 스레드에는 인증 정보가 없다
        p.future = executor.submit(() -> runAnalysis(safeFilename, members, p, who));
        logger.info("[ThreadDump] action=analyze-submit file={} members={} size={} by={}", safeFilename, members.size(), p.totalBytes, who);
        return p;
    }

    private void runAnalysis(String filename, List<String> members, JobProgress p, String who) {
        long t0 = System.currentTimeMillis();
        try {
            p.phase = "parsing";
            ThreadDumpAnalysisEntity e = repository.findByFilename(filename).orElse(null);
            if (e == null) return;       // 제출과 실행 사이에 삭제됐다
            final int timeoutMin = config.getThreadDumpAnalysisTimeoutMinutes();
            final long timeoutAt = t0 + timeoutMin * 60_000L;
            ThreadDumpEngine.Progress progress = (bytes, lines, snapshots, threads) -> {
                p.bytesRead = bytes; p.lines = lines; p.snapshots = snapshots; p.threads = threads;
                if (System.currentTimeMillis() > timeoutAt) throw new ThreadDumpException("TIMEOUT", "분석 시간 상한(" + timeoutMin + "분)을 넘었습니다.");
                if (Thread.currentThread().isInterrupted()) throw new ThreadDumpException("CANCELLED", "분석이 취소되었습니다.");
            };
            ThreadDumpResult r;
            if (members.size() > 1) {
                List<ThreadDumpEngine.Source> sources = new ArrayList<>();
                for (String m : members) sources.add(ThreadDumpEngine.Source.of(fileOf(m)));
                r = ThreadDumpEngine.analyze(sources, limits(), progress, false);
            } else {
                r = ThreadDumpEngine.analyze(fileOf(filename), limits(), progress);
            }
            p.phase = "saving";
            String json = ThreadDumpResultCodec.toJsonCapped(r);
            ThreadDumpResultDetailEntity d = detailRepository.findByFilename(filename).orElseGet(ThreadDumpResultDetailEntity::new);
            d.setFilename(filename);
            d.setResultJson(json);
            detailRepository.save(d);
            // 이전 분석의 AI 해석은 새 결과와 맞지 않는다
            aiInsight.deleteAiInsight(insightKey(filename));
            applySummary(e, r);
            e.setStatus(ThreadDumpAnalysisEntity.STATUS_SUCCESS);
            e.setErrorCode(null);
            e.setErrorMessage(null);
            e.setAnalysisTimeMs(System.currentTimeMillis() - t0);
            e.setAnalyzedAt(LocalDateTime.now());
            repository.save(e);
            resultCache.put(filename, r);
            p.phase = "done";
            ThreadDumpResult.Diagnostics dg = r.getDiagnostics();
            logger.info("[ThreadDump] action=analyze-done file={} snapshots={} threads={} deadlocks={} findings={} severity={} "
                            + "unparsed={} incomplete={} ruleFailures={} elapsed={}ms json={}B by={}",
                    filename, r.getSnapshots().size(), r.getKpi().getThreadsLast(), r.getKpi().getDeadlocks(), r.getFindings().size(),
                    r.getKpi().getSeverity(), dg.getUnparsedLines(), dg.getIncompleteSnapshots(), dg.getRuleFailures().size(),
                    e.getAnalysisTimeMs(), json.length(), who);
        } catch (ThreadDumpException ex) {
            Thread.interrupted();    // 취소로 세워진 인터럽트 플래그를 내린다 — 남아 있으면 아래 DB 저장이 실패한다
            fail(filename, ex.code(), ex.getMessage(), p, t0, who, null);
        } catch (IOException ex) {
            boolean cancelled = Thread.interrupted();
            if (cancelled) fail(filename, "CANCELLED", "분석이 취소되었습니다.", p, t0, who, null);
            else fail(filename, "IO_ERROR", "파일을 읽지 못했습니다 (" + ex.getMessage() + ")", p, t0, who, ex);
        } catch (OutOfMemoryError ex) {
            resultCache.clear();     // 가장 큰 덩어리부터 놓는다
            Thread.interrupted();
            fail(filename, "OUT_OF_MEMORY", "메모리가 부족해 분석하지 못했습니다. 스냅샷 수가 많다면 파일을 나눠 올리거나 "
                    + "threaddump.max-snapshots 를 낮추세요.", p, t0, who, ex);
        } catch (Throwable ex) {
            boolean cancelled = Thread.interrupted();
            if (cancelled) fail(filename, "CANCELLED", "분석이 취소되었습니다.", p, t0, who, null);
            else fail(filename, "INTERNAL", "내부 오류로 분석하지 못했습니다 (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")", p, t0, who, ex);
        } finally {
            jobs.remove(filename, p);
        }
    }

    private void applySummary(ThreadDumpAnalysisEntity e, ThreadDumpResult r) {
        clearSummary(e);
        ThreadDumpResult.Kpi k = r.getKpi();
        ThreadDumpResult.Meta m = r.getMeta();
        e.setSnapshotCount(r.getSnapshots().size());
        e.setThreadCount(k.getThreadsLast());
        e.setDeadlockCount(k.getDeadlocks());
        e.setBlockedMax(k.getBlockedMax());
        e.setStuckCount(k.getStuck());
        e.setFindingCount(k.getFindingCount());
        e.setSeverity(k.getSeverity());
        e.setJvmVersion(trunc(m.getJvmVersionLine(), 300));
        e.setJdkMajor(m.getJdkMajor());
        e.setMiddleware(null);   // 추정값은 저장하지 않는다(2.7.1) — 재분석하면 옛 값도 지워진다
        e.setFirstSnapshotAt(toLocal(m.getFirstTs()));
        e.setLastSnapshotAt(toLocal(m.getLastTs()));
    }

    /**
     * 실패 기록 — 상태·코드·메시지를 저장하고 로그를 남긴다. 저장 자체가 실패해도(DB 장애 등) 예외를 올리지 않는다:
     * 여기서 던지면 실행기 스레드가 죽고 원인 로그도 사라진다. 남은 ANALYZING 행은 {@link #getStatus}·기동 복구가 고친다.
     */
    private void fail(String filename, String code, String message, JobProgress p, long t0, String who, Throwable cause) {
        if (p != null) p.phase = "error";
        long elapsed = System.currentTimeMillis() - t0;
        // 사용자가 일으킨 결과(취소·형식 불일치)는 WARN, 조사해야 하는 것은 스택과 함께 ERROR
        if (cause != null) logger.error("[ThreadDump] action=analyze-failed file={} code={} elapsed={}ms by={} — {}", filename, code, elapsed, who, message, cause);
        else logger.warn("[ThreadDump] action=analyze-failed file={} code={} elapsed={}ms by={} — {}", filename, code, elapsed, who, message);
        try {
            ThreadDumpAnalysisEntity e = repository.findByFilename(filename).orElse(null);
            if (e == null) return;
            e.setStatus(ThreadDumpAnalysisEntity.STATUS_ERROR);
            e.setErrorCode(code);
            e.setErrorMessage(code + ": " + message);
            e.setAnalysisTimeMs(elapsed);
            e.setAnalyzedAt(LocalDateTime.now());
            repository.save(e);
        } catch (RuntimeException saveEx) {
            logger.error("[ThreadDump] action=analyze-failed-save-error file={} code={} — 실패 상태를 저장하지 못했습니다", filename, code, saveEx);
        }
    }

    /** 분석 취소. 실행 중이면 인터럽트로 멈추고, 아직 대기 중이면 여기서 직접 ERROR(CANCELLED) 를 쓴다. */
    public boolean cancel(String safeFilename, String who) {
        JobProgress p = jobs.get(safeFilename);
        if (p == null || p.future == null || p.future.isDone()) return false;
        boolean queued = "queued".equals(p.phase);
        boolean cancelled = p.future.cancel(true);
        if (cancelled && queued) {
            // 실행기가 이 작업을 집기 전이라 runAnalysis 가 돌지 않는다 — 아무도 상태를 고쳐 주지 않는다
            jobs.remove(safeFilename, p);
            fail(safeFilename, "CANCELLED", "분석이 취소되었습니다.", p, p.startedAt, who, null);
        }
        logger.info("[ThreadDump] action=cancel file={} queued={} cancelled={} by={}", safeFilename, queued, cancelled, who);
        return cancelled;
    }

    /** 기동 직후 — 이전 프로세스가 분석 도중 내려가 ANALYZING 에 남은 행을 ERROR(INTERRUPTED) 로 돌린다. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        try {
            List<ThreadDumpAnalysisEntity> stale = repository.findByStatus(ThreadDumpAnalysisEntity.STATUS_ANALYZING);
            for (ThreadDumpAnalysisEntity e : stale) {
                if (isAnalyzing(e.getFilename())) continue;
                markInterrupted(e, "startup");
            }
        } catch (RuntimeException ex) {
            logger.warn("[ThreadDump] action=recover-failed — {}", ex.toString());
        }
    }

    private void markInterrupted(ThreadDumpAnalysisEntity e, String where) {
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_ERROR);
        e.setErrorCode("INTERRUPTED");
        e.setErrorMessage("INTERRUPTED: 분석이 끝나기 전에 서버가 재시작되어 중단되었습니다. 다시 분석하세요.");
        repository.save(e);
        logger.warn("[ThreadDump] action=recover-interrupted file={} at={} by=system", e.getFilename(), where);
    }

    /** 진행 상태 맵 — 폴링 응답. */
    public Map<String, Object> getStatus(String safeFilename) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<ThreadDumpAnalysisEntity> oe = repository.findByFilename(safeFilename);
        if (oe.isPresent() && ThreadDumpAnalysisEntity.STATUS_ANALYZING.equals(oe.get().getStatus()) && !isAnalyzing(safeFilename)) {
            // 제출 도중(상태 저장 ↔ 작업 등록 사이)을 고아로 오인하지 않게 제출과 같은 락에서 다시 본다
            synchronized (this) {
                oe = repository.findByFilename(safeFilename);
                if (oe.isPresent() && ThreadDumpAnalysisEntity.STATUS_ANALYZING.equals(oe.get().getStatus()) && !isAnalyzing(safeFilename)) {
                    markInterrupted(oe.get(), "status");
                }
            }
        }
        String status = oe.map(ThreadDumpAnalysisEntity::getStatus)
                .orElse(fileOf(safeFilename).isFile() ? ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED : "MISSING");
        m.put("status", status);
        JobProgress p = jobs.get(safeFilename);
        if (p != null) {
            m.put("phase", p.phase);
            m.put("bytesRead", p.bytesRead);
            m.put("totalBytes", p.totalBytes);
            m.put("lines", p.lines);
            m.put("snapshots", p.snapshots);
            m.put("threads", p.threads);
            m.put("progressPct", p.totalBytes > 0 ? Math.min(100, Math.round(100.0 * p.bytesRead / p.totalBytes)) : 0);
            m.put("elapsedMs", System.currentTimeMillis() - p.startedAt);
        }
        oe.ifPresent(x -> {
            if (x.getErrorMessage() != null) m.put("error", x.getErrorMessage());
            if (x.getErrorCode() != null) m.put("errorCode", x.getErrorCode());
        });
        return m;
    }

    // ── 결과 조회 ────────────────────────────────────────────────

    /** 저장된 결과 JSON 그대로(화면이 fetch 로 받는다 — 역직렬화 비용이 없다). */
    public Optional<String> loadResultJson(String safeFilename) {
        return detailRepository.findByFilename(safeFilename).map(ThreadDumpResultDetailEntity::getResultJson)
                .filter(s -> s != null && !s.isBlank());
    }

    /** 결과 객체(PDF·AI 프롬프트용). 저장본이 손상됐으면 비어 있다 — 호출자는 재분석을 안내한다. */
    public Optional<ThreadDumpResult> loadResult(String safeFilename) {
        ThreadDumpResult cached = resultCache.get(safeFilename);
        if (cached != null) return Optional.of(cached);
        Optional<String> json = loadResultJson(safeFilename);
        if (json.isEmpty()) return Optional.empty();
        ThreadDumpResult r = ThreadDumpResultCodec.fromJson(json.get());
        if (r == null) {
            logger.warn("[ThreadDump] action=result-corrupt file={} — result_json 을 읽을 수 없습니다", safeFilename);
            return Optional.empty();
        }
        resultCache.put(safeFilename, r);
        return Optional.of(r);
    }

    // ── 삭제·수정 ───────────────────────────────────────────────

    public void delete(String safeFilename, boolean deleteFile, String who) {
        if (deleteFile) detachFromGroup(safeFilename, "delete");
        JobProgress p = jobs.remove(safeFilename);
        if (p != null && p.future != null) p.future.cancel(true);
        resultCache.remove(safeFilename);
        detailRepository.deleteByFilename(safeFilename);
        aiInsight.deleteAiInsight(insightKey(safeFilename));
        Optional<ThreadDumpAnalysisEntity> e = repository.findByFilename(safeFilename);
        if (deleteFile) {
            File f = fileOf(safeFilename);
            if (f.isFile() && !f.delete()) logger.warn("[ThreadDump] 파일 삭제 실패: {}", f);
            e.ifPresent(repository::delete);
        } else {
            e.ifPresent(x -> {
                clearSummary(x);
                x.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
                repository.save(x);
            });
        }
        logger.info("[ThreadDump] action=delete file={} deleteFile={} by={}", safeFilename, deleteFile, who);
    }

    public ThreadDumpAnalysisEntity updateServerName(ThreadDumpAnalysisEntity e, String serverName) {
        e.setServerName(cleanServerName(serverName));
        return repository.save(e);
    }

    // ── 이어 붙이기(2.7.1) ───────────────────────────────────────

    /** 분석할 파일 목록 — 묶음 대표면 저장된 구성(분석 순서), 아니면 자기 자신. */
    public List<String> memberFilesOf(ThreadDumpAnalysisEntity e) {
        if (e == null) return List.of();
        if (e.isCombined()) {
            List<String> m = parseMembers(e.getMemberFiles());
            if (m.size() > 1) return m;
        }
        return List.of(e.getFilename());
    }

    public static List<String> parseMembers(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (tools.jackson.databind.JsonNode n : JSON.readTree(json)) if (n.isTextual()) out.add(n.asText());
            return out;
        } catch (RuntimeException ex) {
            logger.warn("[ThreadDump] member_files 손상 — {}", ex.getMessage());
            return List.of();
        }
    }

    private static String writeMembers(List<String> names) { return JSON.writeValueAsString(names); }

    /** 이어 붙이기 미리보기 한 줄. */
    public record CombineItem(String filename, long size, int snapshots, Long firstTs, Long lastTs, String note) {}

    /**
     * 이어 붙이기 전에 순서와 문제를 보여 준다. 모든 파일에 덤프 시각이 있으면 시각순, 하나라도 없으면 고른 순서 그대로다.
     *
     * @throws IllegalArgumentException 파일 수·이름·존재 검증 실패
     */
    public Map<String, Object> combinePreview(List<String> filenames) throws IOException {
        List<String> names = validateMembers(filenames);
        List<CombineItem> items = new ArrayList<>();
        long total = 0;
        for (String n : names) {
            File f = fileOf(n);
            ThreadDumpEngine.SnapshotScan sc = ThreadDumpEngine.scanSnapshots(f, config.getThreadDumpMaxFileBytes(), config.getThreadDumpMaxLineChars());
            total += f.length();
            String note = null;
            Optional<ThreadDumpAnalysisEntity> e = repository.findByFilename(n);
            if (sc.snapshots() == 0) note = "스레드 덤프를 찾지 못함 — 분석에서 빠집니다";
            else if (e.isPresent() && e.get().getPartOf() != null) note = "'" + e.get().getPartOf() + "' 묶음에서 빠져 이 묶음으로 옮겨집니다";
            else if (e.isPresent() && e.get().isCombined()) note = "기존 묶음(" + e.get().getMemberCount() + "개)이 풀리고 이 묶음으로 바뀝니다";
            items.add(new CombineItem(n, f.length(), sc.snapshots(), sc.firstTs(), sc.lastTs(), note));
        }
        boolean allTs = items.stream().allMatch(i -> i.firstTs() != null);
        List<CombineItem> ordered = new ArrayList<>(items);
        if (allTs) ordered.sort(java.util.Comparator.comparingLong(CombineItem::firstTs));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", allTs ? "timestamp" : "given");
        m.put("files", ordered);
        m.put("representative", ordered.get(0).filename());
        m.put("totalBytes", total);
        m.put("snapshots", ordered.stream().mapToInt(CombineItem::snapshots).sum());
        m.put("overLimit", ordered.stream().mapToInt(CombineItem::snapshots).sum() > config.getThreadDumpMaxSnapshots());
        m.put("maxSnapshots", config.getThreadDumpMaxSnapshots());
        return m;
    }

    private List<String> validateMembers(List<String> filenames) {
        if (filenames == null) throw new IllegalArgumentException("이어 붙일 파일을 고르세요.");
        List<String> names = new ArrayList<>();
        for (String raw : filenames) {
            String n = validateFilename(raw);
            if (!names.contains(n)) names.add(n);
        }
        if (names.size() < 2) throw new IllegalArgumentException("이어 붙이려면 서로 다른 스레드 덤프를 2개 이상 고르세요.");
        if (names.size() > COMBINE_MAX) throw new IllegalArgumentException("한 번에 최대 " + COMBINE_MAX + "개까지 이어 붙일 수 있습니다.");
        for (String n : names) {
            if (!fileOf(n).isFile()) throw new IllegalArgumentException("스레드 덤프 파일이 없습니다: " + n);
        }
        return names;
    }

    /**
     * 고른 파일들을 하나의 분석으로 묶고 바로 분석을 시작한다. 대표 = 순서상 첫 파일(결과는 대표 행에 붙는다).
     * 다른 묶음에 속해 있던 파일은 그 묶음에서 빼 오고, 다른 묶음의 대표였다면 그 묶음을 푼다.
     *
     * @throws IllegalArgumentException 검증 실패 / BusyException 분석 중인 파일이 있음
     */
    public synchronized Map<String, Object> combine(List<String> filenames, String who) throws IOException {
        Map<String, Object> preview = combinePreview(filenames);
        @SuppressWarnings("unchecked") List<CombineItem> ordered = (List<CombineItem>) preview.get("files");
        List<String> order = ordered.stream().map(CombineItem::filename).toList();
        for (String n : order) {
            if (isAnalyzing(n)) throw new BusyException("분석이 진행 중인 파일이 있습니다: " + n + " — 끝난 뒤 다시 시도하세요.");
        }
        String repName = order.get(0);
        for (String n : order) {
            ThreadDumpAnalysisEntity e = repository.findByFilename(n).orElse(null);
            if (e == null) continue;
            if (e.isCombined() && !n.equals(repName)) ungroupInternal(e, who, "regroup");
            else if (e.getPartOf() != null && !order.contains(e.getPartOf())) detachFromGroup(n, "regroup");
        }
        for (String n : order) {
            ThreadDumpAnalysisEntity e = repository.findByFilename(n).orElseGet(() -> newRow(n));
            if (n.equals(repName)) {
                invalidateResult(e);
                e.setMemberFiles(writeMembers(order));
                e.setMemberCount(order.size());
                e.setPartOf(null);
            } else {
                if (e.isCombined()) { e.setMemberFiles(null); e.setMemberCount(null); }
                e.setPartOf(repName);
            }
            repository.save(e);
        }
        logger.info("[ThreadDump] action=combine rep={} members={} order={} by={}", repName, order, preview.get("order"), who);
        submitAnalysis(repName, who);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("representative", repName);
        out.put("members", order);
        out.put("order", preview.get("order"));
        return out;
    }

    /** 묶음을 푼다 — 구성 파일은 각자 단독 파일로 돌아가고, 대표의 묶음 결과는 지운다(그 결과는 묶음 전체를 기준으로 했다). */
    public synchronized void ungroup(String repFilename, String who) {
        ThreadDumpAnalysisEntity rep = repository.findByFilename(repFilename)
                .orElseThrow(() -> new IllegalArgumentException("스레드 덤프 기록이 없습니다: " + repFilename));
        if (!rep.isCombined()) throw new IllegalArgumentException("이어 붙인 묶음이 아닙니다: " + repFilename);
        if (isAnalyzing(repFilename)) throw new BusyException("분석이 진행 중인 묶음은 풀 수 없습니다.");
        ungroupInternal(rep, who, "ungroup");
    }

    private void ungroupInternal(ThreadDumpAnalysisEntity rep, String who, String reason) {
        for (ThreadDumpAnalysisEntity m : repository.findByPartOf(rep.getFilename())) {
            m.setPartOf(null);
            repository.save(m);
        }
        rep.setMemberFiles(null);
        rep.setMemberCount(null);
        invalidateResult(rep);
        repository.save(rep);
        logger.info("[ThreadDump] action=ungroup rep={} reason={} by={}", rep.getFilename(), reason, who);
    }

    /**
     * 파일 하나를 묶음 관계에서 뗀다(삭제·저장소 이동·같은 이름의 새 파일). 대표였다면 구성 파일들이 단독으로 돌아가고,
     * 구성 파일이었다면 대표의 구성 목록에서 빠진다 — 대표의 이미 만든 결과는 그대로 둔다(다시 분석하면 남은 구성으로 한다).
     * ⚠ 다른 파일 자체는 절대 건드리지 않는다(참조만 끊는다).
     */
    void detachFromGroup(String filename, String reason) {
        try {
            Optional<ThreadDumpAnalysisEntity> oe = repository.findByFilename(filename);
            if (oe.isEmpty()) return;
            ThreadDumpAnalysisEntity e = oe.get();
            if (e.isCombined()) {
                for (ThreadDumpAnalysisEntity m : repository.findByPartOf(filename)) {
                    m.setPartOf(null);
                    repository.save(m);
                }
            }
            if (e.getPartOf() != null) {
                repository.findByFilename(e.getPartOf()).ifPresent(rep -> {
                    List<String> left = new ArrayList<>(parseMembers(rep.getMemberFiles()));
                    left.remove(filename);
                    if (left.size() > 1) { rep.setMemberFiles(writeMembers(left)); rep.setMemberCount(left.size()); }
                    else { rep.setMemberFiles(null); rep.setMemberCount(null); }
                    repository.save(rep);
                });
                e.setPartOf(null);
            }
            logger.info("[ThreadDump] action=detach-group file={} reason={} by=system", filename, reason);
        } catch (RuntimeException ex) {
            // 묶음 정리 실패가 삭제·이동 자체를 막으면 안 된다 — 남은 참조는 화면이 "구성 파일 없음" 으로 보여 준다
            logger.warn("[ThreadDump] action=detach-group-failed file={} reason={} — {}", filename, reason, ex.toString());
        }
    }

    /** 결과만 지운다(요약·결과 JSON·AI 해석). 상태는 미분석으로. */
    private void invalidateResult(ThreadDumpAnalysisEntity e) {
        String fn = e.getFilename();
        resultCache.remove(fn);
        detailRepository.deleteByFilename(fn);
        aiInsight.deleteAiInsight(insightKey(fn));
        clearSummary(e);
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
    }

    private ThreadDumpAnalysisEntity newRow(String n) {
        ThreadDumpAnalysisEntity e = new ThreadDumpAnalysisEntity();
        e.setFilename(n);
        e.setStatus(ThreadDumpAnalysisEntity.STATUS_NOT_ANALYZED);
        e.setFileSize(fileOf(n).length());
        e.setCompressed(n.toLowerCase(Locale.ROOT).endsWith(".gz"));
        return e;
    }

    /**
     * 업로드 직후 "이어 붙이기" 제안 — 같은 JVM 실행에서 받은 것으로 보이는 파일끼리 묶는다(묶지는 않는다, 제안만).
     * 근거: 같은 VM 버전 줄 + 스레드 식별자(#n·tid)가 절반 이상 겹침. 64MB 넘는 파일·덤프가 없는 파일은 뺀다.
     */
    public List<Map<String, Object>> groupSuggestion(List<String> filenames) {
        List<String> names = new ArrayList<>();
        if (filenames != null) for (String raw : filenames) {
            String n = validateFilename(raw);
            if (!names.contains(n) && fileOf(n).isFile()) names.add(n);
        }
        record Info(String name, String vm, java.util.Set<String> ids, Long firstTs, int snapshots) {}
        List<Info> infos = new ArrayList<>();
        for (String n : names) {
            File f = fileOf(n);
            if (f.length() > SUGGEST_MAX_BYTES) continue;
            try {
                ThreadDumpResult r = ThreadDumpEngine.analyze(f, limits(), null);
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (ThreadDumpResult.Track t : r.getTracks()) if (!t.isSys()) ids.add((t.getNum() == null ? "" : t.getNum()) + "@" + t.getTid());
                infos.add(new Info(n, r.getMeta().getJvmVersionLine(), ids, r.getMeta().getFirstTs(), r.getSnapshots().size()));
            } catch (ThreadDumpException | IOException ex) {
                logger.debug("[ThreadDump] 묶음 제안에서 제외 file={} — {}", n, ex.getMessage());
            }
        }
        int[] parent = new int[infos.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        java.util.function.IntUnaryOperator root = new java.util.function.IntUnaryOperator() {
            @Override public int applyAsInt(int x) { while (parent[x] != x) x = parent[x] = parent[parent[x]]; return x; }
        };
        for (int i = 0; i < infos.size(); i++) for (int j = i + 1; j < infos.size(); j++) {
            Info a = infos.get(i), b = infos.get(j);
            if (a.vm() != null && b.vm() != null && !a.vm().equals(b.vm())) continue;
            int min = Math.min(a.ids().size(), b.ids().size());
            if (min == 0) continue;
            long common = a.ids().stream().filter(b.ids()::contains).count();
            if (common * 2 >= min) parent[root.applyAsInt(i)] = root.applyAsInt(j);
        }
        Map<Integer, List<Info>> groups = new LinkedHashMap<>();
        for (int i = 0; i < infos.size(); i++) groups.computeIfAbsent(root.applyAsInt(i), k -> new ArrayList<>()).add(infos.get(i));
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Info> g : groups.values()) {
            if (g.size() < 2) continue;
            g.sort(java.util.Comparator.comparing(i -> i.firstTs() == null ? Long.MAX_VALUE : i.firstTs()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("files", g.stream().map(Info::name).toList());
            m.put("snapshots", g.stream().mapToInt(Info::snapshots).sum());
            m.put("reason", "같은 JVM 실행의 스레드(" + g.get(0).ids().size() + "개 중 절반 이상 일치)로 보입니다");
            out.add(m);
        }
        logger.info("[ThreadDump] action=group-suggest files={} groups={} by=system", names.size(), out.size());
        return out;
    }

    // ── 힙 덤프 연계(2.7.1, 수동) ─────────────────────────────────

    public boolean heapLinkAvailable() { return heapThreads != null; }

    /**
     * 연결 후보 — 분석이 끝난 힙 덤프 전체(최근 분석 순)에 점수를 매긴다. 자동으로 연결하지 않는다(사용자가 고른다).
     * 점수: 같은 서버 1 · 덤프 시각이 스냅샷 구간 ±{@code threaddump.match.tolerance-min} 안 1 · 스레드 이름 절반 이상 일치 1.
     * 이름 일치는 점수가 있는 상위 10개만 계산한다(힙 산출물을 읽어야 해서).
     */
    public List<Map<String, Object>> heapCandidates(String safeFilename) {
        if (heapThreads == null) return List.of();
        ThreadDumpAnalysisEntity e = repository.findByFilename(safeFilename)
                .orElseThrow(() -> new IllegalArgumentException("스레드 덤프 기록이 없습니다: " + safeFilename));
        ThreadDumpResult r = loadResult(safeFilename).orElse(null);
        java.util.Set<String> names = r == null ? java.util.Set.of() : focusNames(r);
        Long first = r == null ? null : r.getMeta().getFirstTs(), last = r == null ? null : r.getMeta().getLastTs();
        long tol = config.getThreadDumpMatchToleranceMin() * 60_000L;
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.heapdump.analyzer.model.entity.AnalysisHistoryEntity h : heapThreads.analyzedHeaps()) {
            Map<String, Object> c = new LinkedHashMap<>();
            List<String> reasons = new ArrayList<>();
            int score = 0;
            c.put("filename", h.getFilename());
            c.put("dumpTime", h.getDumpCreationTime());
            c.put("serverName", h.getServerName());
            if (e.getServerName() != null && h.getServerName() != null && e.getServerName().equalsIgnoreCase(h.getServerName().trim())) {
                score++; reasons.add("같은 서버");
            }
            Long dumpMs = dumpEpochMs(h.getDumpCreationTime());
            Long gap = gapMs(dumpMs, first, last);
            c.put("gapSec", gap == null ? null : gap / 1000);
            if (gap != null && gap <= tol) { score++; reasons.add(gap == 0 ? "덤프 시각이 스냅샷 구간 안" : "덤프 시각이 스냅샷과 " + fmtGap(gap) + " 차이"); }
            c.put("score", score);
            c.put("reasons", reasons);
            c.put("linked", h.getFilename().equals(e.getLinkedHeapFilename()));
            out.add(c);
        }
        out.sort((a, b) -> {
            int s = Integer.compare((int) b.get("score"), (int) a.get("score"));
            if (s != 0) return s;
            Long ga = (Long) a.get("gapSec"), gb = (Long) b.get("gapSec");
            return Long.compare(ga == null ? Long.MAX_VALUE : ga, gb == null ? Long.MAX_VALUE : gb);
        });
        int checked = 0;
        for (Map<String, Object> c : out) {
            if (names.isEmpty() || checked >= 10 || (int) c.get("score") == 0) break;
            checked++;
            try {
                HeapThreadService.Loaded l = heapThreads.load((String) c.get("filename"));
                long common = names.stream().filter(l.byName()::containsKey).count();
                int pct = (int) Math.round(100.0 * common / names.size());
                c.put("nameMatchPct", pct);
                if (pct >= 50) {
                    c.put("score", (int) c.get("score") + 1);
                    @SuppressWarnings("unchecked") List<String> rs = (List<String>) c.get("reasons");
                    rs.add("스레드 이름 " + pct + "% 일치");
                }
            } catch (ThreadDumpException ex) {
                c.put("nameMatchError", ex.getMessage());
            }
        }
        out.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        return out;
    }

    /**
     * 힙 덤프를 연결하거나(heapFilename) 끊는다(null·빈 값). 연결은 사용자 결정이라 자동으로 덮지 않는다.
     *
     * @throws IllegalArgumentException 기록 없음·분석이 끝나지 않은 힙 덤프
     */
    public ThreadDumpAnalysisEntity linkHeap(String safeFilename, String heapFilename, String who) {
        ThreadDumpAnalysisEntity e = repository.findByFilename(safeFilename)
                .orElseThrow(() -> new IllegalArgumentException("스레드 덤프 기록이 없습니다: " + safeFilename));
        if (heapFilename == null || heapFilename.isBlank()) {
            String old = e.getLinkedHeapFilename();
            e.setLinkedHeapFilename(null);
            e.setLinkedAt(null);
            e.setLinkedBy(null);
            repository.save(e);
            logger.info("[ThreadDump] action=heap-unlink file={} heap={} by={}", safeFilename, old, who);
            return e;
        }
        if (heapThreads == null) throw new IllegalArgumentException("힙 덤프 연결을 사용할 수 없습니다.");
        String heap = heapThreads.validateFilename(heapFilename);
        com.heapdump.analyzer.model.entity.AnalysisHistoryEntity h = heapThreads.entity(heap)
                .orElseThrow(() -> new IllegalArgumentException("힙 덤프 분석 기록이 없습니다: " + heap));
        if (!"SUCCESS".equals(h.getStatus())) throw new IllegalArgumentException("분석이 끝난 힙 덤프만 연결할 수 있습니다: " + heap);
        e.setLinkedHeapFilename(heap);
        e.setLinkedAt(LocalDateTime.now());
        e.setLinkedBy(who);
        repository.save(e);
        logger.info("[ThreadDump] action=heap-link file={} heap={} by={}", safeFilename, heap, who);
        return e;
    }

    /**
     * 연결 상태와 스레드 이음 — 스레드 이름으로 힙 스레드를 찾아 힙 점유를 붙인다(양쪽 모두 이름이 유일한 스레드만).
     * 힙 산출물을 읽지 못해도 던지지 않는다({@code memoryError} 로 알린다) — 연결 자체는 살아 있어야 한다.
     */
    public Map<String, Object> heapLinkView(String safeFilename) {
        Map<String, Object> v = new LinkedHashMap<>();
        ThreadDumpAnalysisEntity e = repository.findByFilename(safeFilename).orElse(null);
        v.put("available", heapThreads != null);
        String heap = e == null ? null : e.getLinkedHeapFilename();
        v.put("linked", heap != null);
        if (heap == null || heapThreads == null) return v;
        v.put("heapFilename", heap);
        v.put("linkedBy", e.getLinkedBy());
        v.put("linkedAt", e.getLinkedAt() == null ? null : e.getLinkedAt().toString());
        Optional<com.heapdump.analyzer.model.entity.AnalysisHistoryEntity> oh = heapThreads.entity(heap);
        v.put("heapExists", oh.isPresent());
        if (oh.isEmpty()) return v;
        com.heapdump.analyzer.model.entity.AnalysisHistoryEntity h = oh.get();
        v.put("dumpTime", h.getDumpCreationTime());
        v.put("usedHeap", h.getUsedHeapSize());
        v.put("totalHeap", h.getTotalHeapSize());
        v.put("suspectCount", h.getSuspectCount());
        v.put("serverName", h.getServerName());
        ThreadDumpResult r = loadResult(safeFilename).orElse(null);
        if (r != null) {
            Long gap = gapMs(dumpEpochMs(h.getDumpCreationTime()), r.getMeta().getFirstTs(), r.getMeta().getLastTs());
            v.put("gapSec", gap == null ? null : gap / 1000);
        }
        try {
            HeapThreadService.Loaded l = heapThreads.load(heap);
            Map<String, Map<String, Object>> memory = new LinkedHashMap<>();
            java.util.Set<String> names = r == null ? java.util.Set.of() : focusNames(r);
            for (String n : names) {
                com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor.HeapThread ht = l.byName().get(n);
                if (ht == null) continue;
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("r", ht.retained());
                mm.put("l", ht.localsRetained());
                mm.put("c", ht.objectType());
                mm.put("s", com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor.stateLineOf(ht.stateValue()));
                memory.put(n, mm);
            }
            v.put("memory", memory);
            v.put("matchedThreads", memory.size());
            v.put("threadCount", names.size());
            v.put("heapThreads", l.result().getSnapshots().get(0).getThreads());
            List<Map<String, Object>> top = new ArrayList<>();
            memory.entrySet().stream()
                    .filter(en -> en.getValue().get("r") != null)
                    .sorted((a, b) -> Long.compare((Long) b.getValue().get("r"), (Long) a.getValue().get("r")))
                    .limit(10)
                    .forEach(en -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", en.getKey());
                        row.put("retained", en.getValue().get("r"));
                        row.put("retainedText", com.heapdump.analyzer.util.FormatUtils.formatBytes((Long) en.getValue().get("r")));
                        row.put("heapState", en.getValue().get("s"));
                        top.add(row);
                    });
            v.put("topThreads", top);
        } catch (ThreadDumpException ex) {
            v.put("memoryError", ex.getMessage());
            v.put("memoryErrorCode", ex.code());
        }
        return v;
    }

    /** 힙 덤프에 연결된 스레드 덤프(힙 결과 화면 칩). */
    public List<Map<String, Object>> linkedToHeap(String heapFilename) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ThreadDumpAnalysisEntity e : repository.findByLinkedHeapFilename(heapFilename)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", e.getFilename());
            m.put("status", e.getStatus());
            m.put("severity", e.getSeverity());
            m.put("snapshots", e.getSnapshotCount());
            m.put("deadlocks", e.getDeadlockCount());
            m.put("firstSnapshotAt", e.getFirstSnapshotAt() == null ? null : e.getFirstSnapshotAt().toString());
            out.add(m);
        }
        return out;
    }

    /** 힙 덤프 AI 프롬프트에 끼우는 연결된 스레드 덤프 요약(≤900자). 없으면 빈 문자열. */
    public String promptSectionForHeap(String heapFilename) {
        List<ThreadDumpAnalysisEntity> linked = repository.findByLinkedHeapFilename(heapFilename);
        if (linked.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("== 연결된 스레드 덤프 ==\n");
        int n = 0;
        for (ThreadDumpAnalysisEntity e : linked) {
            if (n++ >= 2) break;
            sb.append("파일: ").append(e.getFilename());
            if (e.getSnapshotCount() != null) sb.append(" (스냅샷 ").append(e.getSnapshotCount()).append("개");
            if (e.getFirstSnapshotAt() != null) sb.append(", ").append(e.getFirstSnapshotAt());
            if (e.getSnapshotCount() != null) sb.append(')');
            sb.append('\n');
            loadResult(e.getFilename()).ifPresent(r -> {
                // 종합 진단이 있으면 소견 목록보다 먼저 — 900자 상한에 잘려도 원인 연결 한 줄은 남는다
                if (r.getVerdict() != null && r.getVerdict().getHeadline() != null) sb.append("종합: ").append(r.getVerdict().getHeadline()).append('\n');
                for (ThreadDumpResult.Finding f : r.getFindings()) {
                    if ("Info".equals(f.getSeverity())) continue;
                    sb.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append('\n');
                }
            });
        }
        String out = sb.toString();
        return out.length() > 900 ? out.substring(0, 900) + "…\n" : out;
    }

    /** 스레드 덤프 AI 프롬프트에 붙이는 연결된 힙 덤프 맥락. 연결이 없거나 힙 산출물을 못 읽으면 null. */
    String heapLinkPromptSection(ThreadDumpAnalysisEntity e, ThreadDumpResult r) {
        if (e.getLinkedHeapFilename() == null || heapThreads == null) return null;
        Map<String, Object> v = heapLinkView(e.getFilename());
        if (!Boolean.TRUE.equals(v.get("heapExists"))) return null;
        StringBuilder sb = new StringBuilder("\n== 연결된 힙 덤프 ==\n");
        sb.append("힙 덤프: ").append(v.get("heapFilename"));
        if (v.get("dumpTime") != null) sb.append(" (생성 ").append(v.get("dumpTime")).append(')');
        sb.append('\n');
        if (v.get("gapSec") != null) sb.append("스레드 덤프 스냅샷과의 시각 차: ").append(fmtGap(((Long) v.get("gapSec")) * 1000)).append('\n');
        if (v.get("usedHeap") != null) sb.append("사용 힙: ").append(com.heapdump.analyzer.util.FormatUtils.formatBytes((Long) v.get("usedHeap")))
                .append(v.get("totalHeap") == null ? "" : " / " + com.heapdump.analyzer.util.FormatUtils.formatBytes((Long) v.get("totalHeap"))).append('\n');
        if (v.get("suspectCount") != null) sb.append("Leak Suspects: ").append(v.get("suspectCount")).append("건\n");
        if (v.get("matchedThreads") != null) sb.append("이름이 일치한 스레드: ").append(v.get("matchedThreads")).append(" / ").append(v.get("threadCount")).append('\n');
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) v.get("topThreads");
        if (top != null && !top.isEmpty()) {
            sb.append("힙 점유가 큰 스레드(힙 덤프 기준):\n");
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                Map<String, Object> t = top.get(i);
                sb.append("  \"").append(t.get("name")).append("\" ").append(t.get("retainedText")).append(" [").append(t.get("heapState")).append("]\n");
            }
        }
        return sb.toString();
    }

    /** focus 스냅샷의 Java 스레드 이름(유일한 것만). */
    static java.util.Set<String> focusNames(ThreadDumpResult r) {
        int focus = r.getMeta().getFocusSnapshot();
        Map<String, Integer> count = new java.util.HashMap<>();
        for (ThreadDumpResult.Track t : r.getTracks()) {
            if (t.isSys()) continue;
            int off = focus - t.getFirst();
            if (off < 0 || off >= t.getS().length() || t.getS().charAt(off) == ThreadDumpResult.ST_ABSENT) continue;
            count.merge(r.getNames().get(t.getN()), 1, Integer::sum);
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        count.forEach((k, c) -> { if (c == 1) out.add(k); });
        return out;
    }

    static Long dumpEpochMs(String dumpCreationTime) {
        Long sec = JvmHeapInfoService.toEpoch(dumpCreationTime);
        return sec == null ? null : sec * 1000;
    }

    /** 덤프 시각과 스냅샷 구간 사이 거리(ms). 구간 안이면 0, 어느 쪽이든 모르면 null. */
    static Long gapMs(Long dumpMs, Long first, Long last) {
        if (dumpMs == null || first == null) return null;
        long l = last == null ? first : last;
        if (dumpMs >= first && dumpMs <= l) return 0L;
        return Math.min(Math.abs(dumpMs - first), Math.abs(dumpMs - l));
    }

    static String fmtGap(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "초";
        if (s < 3600) return (s / 60) + "분";
        if (s < 86400) return (s / 3600) + "시간 " + (s % 3600 / 60) + "분";
        return (s / 86400) + "일";
    }

    // ── LLM ─────────────────────────────────────────────────────

    public boolean isLlmEnabled() { return llmConfig.isLlmEnabled(); }
    public void saveAiInsight(String key, Map<String, Object> data) { aiInsight.saveAiInsight(key, data); }
    public Map<String, Object> loadAiInsight(String key) { return aiInsight.loadAiInsight(key); }
    public boolean deleteAiInsight(String key) { return aiInsight.deleteAiInsight(key); }

    static final String TD_SYSTEM_PROMPT = "당신은 JVM 스레드 덤프 분석 전문가입니다. 주어진 스레드 덤프 분석 결과(스냅샷별 상태 분포·교착·락 경합·"
            + "여러 스냅샷에 걸쳐 같은 스택에 머문 스레드·스레드 풀·규칙 기반 이상 징후)를 해석해 근본 원인과 조치를 제시하세요. "
            + "수치와 스레드·클래스 이름은 주어진 값만 인용하고 없는 값을 만들지 마세요. "
            + "'근거'·'종합 진단'·'연관 소견'은 규칙이 덤프에서 직접 세어 이은 것이니 그대로 근거로 삼되, '의심'이라고 적힌 것은 가능성으로만 다루세요. "
            + "RUNNABLE 이라도 소켓 읽기·accept·select 에 있는 스레드는 CPU 를 쓰는 것이 아니라 상대를 기다리는 것입니다. "
            + "Object.wait() 중인 스레드는 락을 쥐고 있지 않습니다. 스냅샷이 1개뿐이면 '멈춰 있다'고 단정하지 마세요. "
            + "recommendations 는 우선순위가 높은 3개까지, codeAdvice 에는 스택에서 보이는 클래스·메서드를 짚어 고칠 지점을 적으세요. "
            + "severity 는 Critical|High|Medium|Low 네 값 중 하나만 쓰세요. 반드시 마크다운 없이 순수 JSON 만 출력하세요.";

    public Map<String, Object> analyzeWithAi(String safeFilename) {
        ThreadDumpAnalysisEntity e = repository.findByFilename(safeFilename).orElse(null);
        Optional<ThreadDumpResult> r = loadResult(safeFilename);
        if (e == null || r.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "NO_RESULT");
            err.put("error", "분석 결과가 없습니다. 스레드 덤프를 먼저 분석하세요.");
            return err;
        }
        String prompt = ThreadDumpPromptBuilder.build(r.get(), e.getFilename(), e.getServerName(), heapLinkPromptSection(e, r.get()));
        Map<String, Object> res = llmConfig.callLlmAnalysis(prompt, TD_SYSTEM_PROMPT);
        if (res != null && Boolean.TRUE.equals(res.get("success")) && res.get("data") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) res.get("data");
            res.put("data", ThreadDumpAiResponseNormalizer.normalize(data));
        }
        return res;
    }

    // ── 헬퍼 ────────────────────────────────────────────────────

    private static String cleanServerName(String serverName) {
        if (serverName == null) return null;
        String v = serverName.trim();
        if (v.isEmpty()) return null;
        return v.length() > 100 ? v.substring(0, 100) : v;
    }

    private static String trunc(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }

    private static LocalDateTime toLocal(Long epochMs) {
        return epochMs == null ? null : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }
}

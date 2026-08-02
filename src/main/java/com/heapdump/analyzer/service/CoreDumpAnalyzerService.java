package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.CoreDumpRevision;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CoreDumpAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(CoreDumpAnalyzerService.class);
    private static final String RESULT_JSON = "result.json";
    private static final String GDB_OUTPUT_TXT = "gdb_output.txt";
    private static final String REVISIONS_DIR = "revisions";
    /** 리비전 디렉토리명 = yyyyMMdd-HHmmss, 동일 초 충돌 시 -N 접미사 */
    private static final Pattern REVISION_ID = Pattern.compile("^\\d{8}-\\d{6}(-\\d+)?$");
    private static final DateTimeFormatter REVISION_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter REVISION_LABEL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HeapDumpConfig config;
    private final CoreDumpAnalysisRepository repository;
    private final ObjectMapper objectMapper;
    private final HeapDumpAnalyzerService heapFacade;
    private final LlmConfigService llmConfig;
    private final AiInsightManager aiInsight;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "coredump-analyzer");
        t.setDaemon(false);
        return t;
    });

    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();

    public CoreDumpAnalyzerService(HeapDumpConfig config,
                                   CoreDumpAnalysisRepository repository,
                                   ObjectMapper objectMapper,
                                   HeapDumpAnalyzerService heapFacade,
                                   LlmConfigService llmConfig,
                                   AiInsightManager aiInsight) {
        this.config = config;
        this.repository = repository;
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.heapFacade = heapFacade;
        this.llmConfig = llmConfig;
        this.aiInsight = aiInsight;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ── 경로 헬퍼 ─────────────────────────────────────────────────

    public File dumpFilesDir() {
        return new File(config.getCoreDumpDirectory(), "dumpfiles");
    }

    public File tmpDir() {
        return new File(config.getCoreDumpDirectory(), "tmp");
    }

    public File dataDir(String filename) {
        return new File(config.getCoreDumpDirectory(), "data" + File.separator + baseName(filename));
    }

    public File resultJsonFile(String filename) {
        return new File(dataDir(filename), RESULT_JSON);
    }

    public File revisionsDir(String filename) {
        return new File(dataDir(filename), REVISIONS_DIR);
    }

    private String baseName(String filename) {
        return Paths.get(filename).getFileName().toString();
    }

    // ── 파일명 검증 ────────────────────────────────────────────────

    public String validateCoreDumpFilename(String filename) {
        if (filename == null || filename.trim().isEmpty())
            throw new IllegalArgumentException("파일명이 필요합니다.");
        String safe = Paths.get(filename).getFileName().toString();
        if (safe.contains("\0") || safe.contains("..") || safe.contains("/") || safe.contains("\\"))
            throw new IllegalArgumentException("유효하지 않은 파일명입니다.");
        if (safe.trim().isEmpty() || safe.equals("."))
            throw new IllegalArgumentException("유효하지 않은 파일명입니다.");
        return safe;
    }

    // ── 결과 캐시 로드 ────────────────────────────────────────────

    public Optional<CoreDumpAnalysisResult> loadResult(String filename) {
        File f = resultJsonFile(filename);
        if (!f.exists()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(f, CoreDumpAnalysisResult.class));
        } catch (Exception e) {
            logger.warn("[CoreDump] result.json 로드 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── 분석 리비전 (재분석 시 기존 결과 보존) ─────────────────────

    /**
     * 리비전 ID 검증. 경로 조작 차단 — 디렉토리명 패턴에 정확히 일치해야 한다.
     */
    public String validateRevisionId(String revisionId) {
        if (revisionId == null || revisionId.trim().isEmpty())
            throw new IllegalArgumentException("리비전 ID가 필요합니다.");
        String safe = revisionId.trim();
        if (!REVISION_ID.matcher(safe).matches())
            throw new IllegalArgumentException("유효하지 않은 리비전 ID입니다: " + revisionId);
        return safe;
    }

    /**
     * 현재 result.json 을 revisions/{yyyyMMdd-HHmmss}/ 로 이관해 보존한다.
     * gdb_output.txt 도 함께 옮긴다(같은 분석의 산출물이라 짝을 맞춰야 함).
     * 이관이므로 호출 후 현재 result.json 은 사라진다 → progress 페이지의
     * "결과 있으면 analyze 로 redirect" 방어 로직에 걸리지 않아 재분석이 정상 진행된다.
     *
     * @return 생성된 리비전 ID. 보존할 결과가 없으면 null.
     */
    public synchronized String archiveCurrentResult(String filename) throws IOException {
        File current = resultJsonFile(filename);
        if (!current.exists()) return null;

        File revsDir = revisionsDir(filename);
        if (!revsDir.exists() && !revsDir.mkdirs())
            throw new IOException("리비전 디렉토리 생성 실패: " + revsDir.getAbsolutePath());

        String baseId = LocalDateTime.now().format(REVISION_ID_FMT);
        File target = new File(revsDir, baseId);
        for (int n = 2; target.exists(); n++) target = new File(revsDir, baseId + "-" + n);
        if (!target.mkdirs())
            throw new IOException("리비전 디렉토리 생성 실패: " + target.getAbsolutePath());

        Files.move(current.toPath(), new File(target, RESULT_JSON).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        File rawFile = new File(dataDir(filename), GDB_OUTPUT_TXT);
        if (rawFile.exists()) {
            try {
                Files.move(rawFile.toPath(), new File(target, GDB_OUTPUT_TXT).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // raw 출력은 부가 산출물 — 실패해도 result.json 보존은 유효
                logger.warn("[CoreDump] gdb_output.txt 아카이브 실패 (결과 보존에는 영향 없음): {} — {}",
                        rawFile.getAbsolutePath(), e.getMessage());
            }
        }
        logger.info("[CoreDump] 기존 분석 결과 보존: {} → revisions/{}", filename, target.getName());
        return target.getName();
    }

    /**
     * 보존된 리비전 목록 (최신순). 파일이 깨졌으면 조용히 건너뛴다.
     */
    public List<CoreDumpRevision> listRevisions(String filename) {
        File revsDir = revisionsDir(filename);
        File[] dirs = revsDir.listFiles(File::isDirectory);
        if (dirs == null) return Collections.emptyList();

        List<CoreDumpRevision> revisions = new ArrayList<>();
        for (File d : dirs) {
            if (!REVISION_ID.matcher(d.getName()).matches()) continue;
            File rf = new File(d, RESULT_JSON);
            if (!rf.exists()) continue;

            CoreDumpRevision rev = new CoreDumpRevision();
            rev.setId(d.getName());
            rev.setArchivedAtEpoch(d.lastModified());
            try {
                CoreDumpAnalysisResult r = objectMapper.readValue(rf, CoreDumpAnalysisResult.class);
                rev.setAnalyzedAt(r.getAnalyzedAt());
                rev.setExecutableName(r.getExecutableName());
                rev.setCrashSignal(r.getCrashSignal());
            } catch (Exception e) {
                logger.warn("[CoreDump] 리비전 result.json 로드 실패 (목록에서 제외): {} — {}",
                        rf.getAbsolutePath(), e.getMessage());
                continue;
            }
            rev.setLabel(buildRevisionLabel(rev));
            revisions.add(rev);
        }
        revisions.sort(Comparator.comparingLong(CoreDumpRevision::getArchivedAtEpoch).reversed());
        return revisions;
    }

    /** "2026-07-17 14:20 · exec 없음" 형태의 표기 라벨. */
    private String buildRevisionLabel(CoreDumpRevision rev) {
        String when = null;
        if (rev.getAnalyzedAt() != null) {
            try {
                when = LocalDateTime.parse(rev.getAnalyzedAt()).format(REVISION_LABEL_FMT);
            } catch (Exception ignored) {}
        }
        if (when == null) {
            when = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(rev.getArchivedAtEpoch()),
                    java.time.ZoneId.systemDefault()).format(REVISION_LABEL_FMT);
        }
        String exec = (rev.getExecutableName() != null && !rev.getExecutableName().isEmpty())
                ? "exec: " + rev.getExecutableName()
                : "exec 없음";
        return when + " · " + exec;
    }

    /** 특정 리비전의 결과 로드. */
    public Optional<CoreDumpAnalysisResult> loadRevisionResult(String filename, String revisionId) {
        String safeRev = validateRevisionId(revisionId);
        File rf = new File(new File(revisionsDir(filename), safeRev), RESULT_JSON);
        if (!rf.exists()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(rf, CoreDumpAnalysisResult.class));
        } catch (Exception e) {
            logger.warn("[CoreDump] 리비전 result.json 로드 실패: {} — {}", rf.getAbsolutePath(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── 활성 분석 확인 ────────────────────────────────────────────

    public boolean isAnalyzing(String filename) {
        return activeTasks.containsKey(filename);
    }

    // ── 이력 조회 ─────────────────────────────────────────────────

    public List<CoreDumpAnalysisEntity> getHistory() {
        return repository.findByFileDeletedFalseOrderByCreatedAtDesc();
    }

    public Optional<CoreDumpAnalysisEntity> getEntity(String filename) {
        return repository.findByFilename(filename);
    }

    // ── exec 파일 페어링 ───────────────────────────────────────────

    /**
     * exec 파일명 조회.
     * - properties에 키 존재 + 비어있지 않은 값 → 신규 스타일 페어링
     * - properties에 키 존재 + 빈 값("") → 명시적 해제 마커 → null 반환(레거시 폴백 차단)
     * - properties에 키 없음 → 레거시 {coreName}.exec 파일 존재 여부 폴백
     */
    public String getExecFilename(String coreFilename) {
        Map<String, String> pairings = heapFacade.loadCoreExecPairings();
        if (pairings.containsKey(coreFilename)) {
            String paired = pairings.get(coreFilename);
            if (paired == null || paired.isEmpty()) return null; // 명시적 해제
            if (new File(dumpFilesDir(), paired).exists()) return paired;
            // 코어덤프 디렉터리에 없으면 힙덤프 디렉터리(exec 타입 업로드 위치) 도 확인
            try {
                File inHeap = heapFacade.getFile(paired);
                if (inHeap != null && inHeap.exists()) return paired;
            } catch (Exception ignored) {}
            return null;
        }
        // properties에 없을 때만 레거시 폴백 사용
        File legacyExec = new File(dumpFilesDir(), coreFilename + ".exec");
        return legacyExec.exists() ? coreFilename + ".exec" : null;
    }

    public void saveExecPairing(String coreFilename, String execFilename) throws IOException {
        heapFacade.saveCoreExecPairing(coreFilename, execFilename);
        logger.info("[CoreDump] exec 페어링 저장: {} → {}", coreFilename, execFilename);
    }

    /**
     * exec 페어링 해제.
     * - 힙덤프 디렉터리에 원본이 있는 exec 복사본은 코어덤프 디렉터리에서 삭제
     *   (복사본이므로 원본은 힙덤프 디렉터리에 보존).
     * - 레거시 .exec 파일(코어덤프 디렉터리 독립 파일)은 빈 값 마킹 후 보존.
     */
    public void unpairExec(String coreFilename) throws IOException {
        // 해제 전에 현재 페어링된 exec 확인 후 복사본 삭제
        String pairedExec = getExecFilename(coreFilename);
        if (pairedExec != null) {
            File execInCoreDir = new File(dumpFilesDir(), pairedExec);
            if (execInCoreDir.exists()) {
                try {
                    File execInHeapDir = heapFacade.getFile(pairedExec);
                    if (execInHeapDir != null && execInHeapDir.exists()) {
                        deleteQuietly(execInCoreDir);
                        logger.info("[CoreDump] 페어링 해제: exec 복사본 삭제 {}", pairedExec);
                    }
                } catch (Exception ignored) {}
            }
        }
        File legacyExec = new File(dumpFilesDir(), coreFilename + ".exec");
        if (legacyExec.exists()) {
            heapFacade.saveCoreExecPairing(coreFilename, ""); // 명시적 해제 마커
        } else {
            heapFacade.removeCoreExecPairing(coreFilename);
        }
        logger.info("[CoreDump] exec 페어링 해제: {}", coreFilename);
    }

    /**
     * dumpfiles/ 에 실제 존재하는 코어 덤프 + 실행파일 목록 (인덱스 좌측 패널용).
     * dotfile / 디렉토리 제외. .exec 또는 페어링된 exec 는 fileType="exec", 그 외는 "coredump".
     * DB 이력과 파일명으로 조인해 status 채움(없으면 NOT_ANALYZED). 최신 수정순(내림차순) 정렬.
     *
     * 코어에 연결된 exec 는 목록에서 숨긴다 — 연결 정보는 코어 항목의 페어 칩
     * (pairedExecFilename)으로만 노출한다. 같은 파일이 두 항목으로 중복 표시되는 것을 막기 위함.
     * 연결이 없는 exec 만 독립 항목(NOT_ANALYZED)으로 남는다.
     */
    public List<AnalysisHistoryItem> listExistingDumpFiles() {
        File dumpDir = dumpFilesDir();
        File[] files = dumpDir.listFiles();
        if (files == null) return Collections.emptyList();

        Map<String, CoreDumpAnalysisEntity> byName = new HashMap<>();
        for (CoreDumpAnalysisEntity e : getHistory()) {
            byName.put(e.getFilename(), e);
        }

        // 페어링된 exec 파일명 — 실행파일 항목으로 분류(코어와 구분해 태그 표시)
        Map<String, String> pairings = heapFacade.loadCoreExecPairings();
        Set<String> pairedExecNames = new HashSet<>(pairings.values());
        // 역방향 인덱스: exec 파일명 → 연결된 코어 파일명 목록
        Map<String, List<String>> coresByExec = new HashMap<>();
        for (Map.Entry<String, String> p : pairings.entrySet()) {
            String exec = p.getValue();
            if (exec == null || exec.isEmpty()) continue; // 명시적 해제 마커
            coresByExec.computeIfAbsent(exec, k -> new ArrayList<>()).add(p.getKey());
        }

        List<AnalysisHistoryItem> result = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || !f.isFile()) continue;

            // .exec 확장자(레거시) 또는 페어링된 exec 파일 → 실행파일 항목
            boolean isExec = name.endsWith(".exec") || pairedExecNames.contains(name);

            AnalysisHistoryItem item = new AnalysisHistoryItem();
            item.setFileType(isExec ? "exec" : "coredump");
            item.setFilename(name);
            item.setSizeBytes(f.length());
            item.setFormattedSize(FormatUtils.formatBytes(f.length()));
            item.setLastModified(f.lastModified());

            if (!isExec) {
                // 코어 항목에만 페어링 exec 세팅(드래그 시 core+exec 동반 프리로드용)
                String execFn = getExecFilename(name);
                item.setHasExec(execFn != null);
                if (execFn != null) item.setPairedExecFilename(execFn);

                CoreDumpAnalysisEntity e = byName.get(name);
                if (e != null) {
                    item.setId(e.getId());
                    item.setStatus(e.getStatus() != null ? e.getStatus() : "NOT_ANALYZED");
                } else {
                    item.setStatus("NOT_ANALYZED");
                }
            } else {
                // exec 항목: 코어에 연결돼 있으면 숨김(코어 항목의 페어 칩으로 노출).
                // 레거시 {core}.exec 는 페어링 맵에 없을 수 있으므로 파일명 규칙으로 폴백.
                boolean linked = coresByExec.containsKey(name);
                if (!linked && name.endsWith(".exec")) {
                    String legacyCore = name.substring(0, name.length() - ".exec".length());
                    linked = new File(dumpDir, legacyCore).isFile()
                            && name.equals(getExecFilename(legacyCore));
                }
                if (linked) continue;
                item.setStatus("NOT_ANALYZED");
            }
            result.add(item);
        }
        result.sort(Comparator.comparingLong(AnalysisHistoryItem::getLastModified).reversed());
        return result;
    }

    // ── 삭제 ──────────────────────────────────────────────────────

    public void deleteDump(String filename) {
        // filename은 호출자(컨트롤러)에서 이미 validateCoreDumpFilename()을 거친 값
        // 페어링된 exec 파일 삭제 (원본명 + 레거시 .exec 모두)
        String execFn = getExecFilename(filename);
        if (execFn != null) deleteQuietly(new File(dumpFilesDir(), execFn));
        deleteQuietly(new File(dumpFilesDir(), filename + ".exec")); // 레거시 폴백
        try { heapFacade.removeCoreExecPairing(filename); } catch (IOException e) {
            logger.warn("[CoreDump] 페어링 정보 삭제 실패 (무시): {}", e.getMessage());
        }
        File dumpFile = new File(dumpFilesDir(), filename);
        deleteQuietly(dumpFile);
        // 데이터 디렉토리 삭제
        deleteDirectoryQuietly(dataDir(filename));
        // DB 플래그
        repository.findByFilename(filename).ifPresent(e -> {
            e.setFileDeleted(true);
            repository.save(e);
        });
        logger.info("[CoreDump] 삭제 완료: {}", filename);
    }

    /** 분석 이력(결과 디렉토리 + DB 행)만 삭제하고 원본 파일은 보존. */
    public void deleteHistoryOnly(String filename) {
        deleteDirectoryQuietly(dataDir(filename));
        deleteAiInsight(coreInsightKey(filename));
        // DB 이력 행을 완전히 제거 → "분석 이력" 목록에서 사라짐.
        // (기존엔 status 를 NOT_ANALYZED 로 리셋만 해 행이 미분석 상태로 잔존했음.)
        // 원본 파일은 dumpfiles/ 에 보존되므로 좌측 "서버 코어 파일" 목록에
        // 미분석 상태로 남아 재분석할 수 있다(listExistingDumpFiles 가 디스크 기반).
        repository.findByFilename(filename).ifPresent(repository::delete);
        logger.info("[CoreDump] 분석 이력 삭제(파일 보존) 완료: {}", filename);
    }

    private void deleteQuietly(File f) {
        if (!f.exists()) return;
        if (!f.delete()) logger.warn("[CoreDump] 파일 삭제 실패: {}", f.getAbsolutePath());
    }

    private void deleteDirectoryQuietly(File dir) {
        if (!dir.exists()) return;
        try {
            Files.walk(dir.toPath())
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(f -> { if (!f.delete()) logger.warn("[CoreDump] 삭제 실패: {}", f.getAbsolutePath()); });
        } catch (Exception e) {
            logger.warn("[CoreDump] 디렉토리 삭제 실패: {}", dir.getAbsolutePath(), e);
        }
    }

    // ── 분석 실행 (SSE) ───────────────────────────────────────────

    public Future<?> analyzeWithProgress(String filename, SseEmitter emitter, String uploadedBy) {
        String safe = validateCoreDumpFilename(filename);

        if (activeTasks.containsKey(safe)) {
            try {
                emitter.send(SseEmitter.event().name("progress")
                        .data(objectMapper.writeValueAsString(
                                AnalysisProgress.alreadyAnalyzing(safe))));
                emitter.complete();
            } catch (Exception ignored) {}
            return CompletableFuture.completedFuture(null);
        }

        Future<?> task = executor.submit(() -> runAnalysis(safe, emitter, uploadedBy));
        activeTasks.put(safe, task);
        return task;
    }

    private void runAnalysis(String filename, SseEmitter emitter, String uploadedBy) {
        long startTime = System.currentTimeMillis();
        File tmpAnalysisDir = new File(tmpDir(), filename);

        try {
            logger.info("[CoreDump] 분석 시작: {}", filename);

            // 1. 파일 존재 확인
            sendProgress(emitter, AnalysisProgress.step(filename, 3, "코어 덤프 파일 확인 중..."));
            File coreFile = new File(dumpFilesDir(), filename);
            if (!coreFile.exists() || !coreFile.isFile()) {
                sendProgress(emitter, AnalysisProgress.error(filename, "코어 덤프 파일을 찾을 수 없습니다: " + filename));
                emitter.complete();
                updateDbError(filename, "코어 덤프 파일을 찾을 수 없습니다");
                return;
            }

            // 2. 실행 파일 확인 (페어링 맵 우선, .exec 폴백)
            String executableName = getExecFilename(filename);
            File execFile = executableName != null ? new File(dumpFilesDir(), executableName) : null;
            logger.info("[CoreDump] 실행 파일: {}", executableName != null ? executableName : "없음 (시그널 제한 모드)");

            // 3. DB 레코드 생성/갱신
            // 삭제 후 재업로드 시 기존 엔티티의 fileDeleted=true가 유지되면
            // getHistory()의 findByFileDeletedFalseOrderByCreatedAtDesc()에서 필터링되어
            // 분석 결과가 저장됐음에도 이력 목록에 보이지 않는 문제 방지
            CoreDumpAnalysisEntity entity = repository.findByFilename(filename)
                    .orElseGet(() -> {
                        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
                        e.setFilename(filename);
                        return e;
                    });
            entity.setFileSize(coreFile.length());
            entity.setFileDeleted(false);
            entity.setUploadedBy(uploadedBy);
            entity.setStatus("ANALYZING");
            entity.setExecutableName(executableName);
            if (execFile != null && execFile.exists()) entity.setExecutableSize(execFile.length());
            repository.save(entity);

            // 4. tmp 디렉토리 준비
            sendProgress(emitter, AnalysisProgress.step(filename, 10, "GDB 분석 준비 중..."));
            tmpAnalysisDir.mkdirs();
            File coreCopy = new File(tmpAnalysisDir, filename);
            Files.copy(coreFile.toPath(), coreCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

            File execCopy = null;
            if (execFile != null && execFile.exists()) {
                // 원본 exec 파일명을 그대로 유지 — GDB 출력/분석 결과에 올바른 이름이 표시됨
                execCopy = new File(tmpAnalysisDir, execFile.getName());
                Files.copy(execFile.toPath(), execCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 5. GDB 실행
            sendProgress(emitter, AnalysisProgress.step(filename, 15, "GDB 실행 중..."));
            String rawOutput = runGdb(coreCopy, execCopy, emitter, filename);

            // 6. 출력 파싱
            sendProgress(emitter, AnalysisProgress.step(filename, 85, "GDB 출력 파싱 중..."));
            CoreDumpAnalysisResult result = parseGdbOutput(rawOutput, filename, executableName);
            result.setAnalysisTimeMs(System.currentTimeMillis() - startTime);
            result.setAnalyzedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            result.setCoreDumpTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(coreFile.lastModified()),
                    java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            // 7. result.json 저장
            sendProgress(emitter, AnalysisProgress.step(filename, 92, "분석 결과 저장 중..."));
            File dataDirFile = dataDir(filename);
            if (!dataDirFile.exists() && !dataDirFile.mkdirs()) {
                throw new IOException("결과 저장 디렉토리 생성 실패: " + dataDirFile.getAbsolutePath());
            }
            File resultFile = resultJsonFile(filename);
            objectMapper.writeValue(resultFile, result);
            logger.info("[CoreDump] result.json 저장 완료: {} ({}B)",
                    resultFile.getAbsolutePath(), resultFile.length());

            // GDB raw output 별도 저장
            File rawFile = new File(dataDirFile, "gdb_output.txt");
            try {
                Files.writeString(rawFile.toPath(), rawOutput, StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warn("[CoreDump] gdb_output.txt 저장 실패 (분석에는 영향 없음): {} — {}",
                        rawFile.getAbsolutePath(), e.getMessage());
            }

            // 8. DB 갱신
            boolean hasParseError = result.getErrorMessage() != null && !result.getErrorMessage().isEmpty();
            String dbStatus = hasParseError ? "ERROR" : "SUCCESS";
            entity.setStatus(dbStatus);
            entity.setCrashSignal(result.getCrashSignal());
            entity.setSignalDescription(result.getSignalDescription());
            entity.setCrashSummary(buildCrashSummary(result));
            entity.setAnalysisTimeMs(result.getAnalysisTimeMs());
            entity.setAnalyzedAt(LocalDateTime.now());
            if (result.getErrorMessage() != null) entity.setErrorMessage(result.getErrorMessage());
            repository.save(entity);
            logger.info("[CoreDump] DB 갱신 완료: {} → status={}", filename, dbStatus);

            // 9. 완료 — 파서 오류 시 error SSE, 정상 시 completed SSE
            String resultUrl = "/core-dump/analyze/" + filename;
            if (hasParseError) {
                logger.warn("[CoreDump] 파서 오류로 분석 결과 불완전: {} — {}", filename, result.getErrorMessage());
                sendProgress(emitter, AnalysisProgress.error(filename, result.getErrorMessage()));
            } else {
                sendProgress(emitter, AnalysisProgress.completed(filename, resultUrl));
            }
            emitter.complete();

            int frameCount  = result.getMainBacktrace() != null ? result.getMainBacktrace().size() : 0;
            int threadCount = result.getAllThreads()     != null ? result.getAllThreads().size()     : 0;
            if (hasParseError) {
                logger.warn("[CoreDump] 분석 완료(오류): {} — signal={}, frames={}, threads={}, {}ms, error={}",
                        filename,
                        result.getCrashSignal() != null ? result.getCrashSignal() : "없음",
                        frameCount, threadCount, result.getAnalysisTimeMs(), result.getErrorMessage());
            } else {
                logger.info("[CoreDump] 분석 완료: {} — signal={}, frames={}, threads={}, {}ms",
                        filename,
                        result.getCrashSignal() != null ? result.getCrashSignal() : "없음",
                        frameCount, threadCount, result.getAnalysisTimeMs());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - startTime;
            logger.warn("[CoreDump] 분석 취소됨: {} (경과: {}ms)", filename, elapsed);
            updateDbError(filename, "분석이 취소되었습니다");
            try { emitter.complete(); } catch (Exception ignored) {}

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (errMsg.contains("시간 초과")) {
                logger.error("[CoreDump] 분석 타임아웃: {} (경과: {}ms) — {}",
                        filename, elapsed, errMsg);
            } else if (e instanceof IOException) {
                logger.error("[CoreDump] I/O 오류: {} (경과: {}ms) — {}",
                        filename, elapsed, errMsg);
            } else {
                logger.error("[CoreDump] 분석 실패: {} (경과: {}ms) — {}",
                        filename, elapsed, errMsg, e);
            }
            updateDbError(filename, errMsg);
            try {
                sendProgress(emitter, AnalysisProgress.error(filename, errMsg));
                emitter.complete();
            } catch (Exception ignored) {}

        } finally {
            deleteDirectoryQuietly(tmpAnalysisDir);
            activeTasks.remove(filename);
        }
    }

    // ── GDB 실행 ──────────────────────────────────────────────────

    private String runGdb(File corePath, File execPath, SseEmitter emitter, String filename)
            throws Exception {
        List<String> cmd = buildGdbCommand(corePath, execPath);
        logger.info("[CoreDump] GDB 명령: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("LANG", "C");
        pb.environment().put("LC_ALL", "C");

        Process process = pb.start();
        StringBuilder rawOutput = new StringBuilder();

        // stdout 읽기 — daemon thread (pitfall #9)
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    rawOutput.append(line).append("\n");
                    String logLine = line;
                    sendProgress(emitter, buildLogProgress(filename, logLine));
                }
            } catch (IOException e) {
                logger.debug("[CoreDump] GDB reader 종료: {}", e.getMessage());
            }
        });
        reader.setDaemon(true);
        reader.start();

        long timeoutMs = config.getCoreDumpTimeoutMinutes() * 60L * 1000;
        boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(3000);
            throw new RuntimeException("GDB 분석 시간 초과 ("
                    + config.getCoreDumpTimeoutMinutes() + "분, file=" + corePath.getName() + ")");
        }
        reader.join(5000);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logger.warn("[CoreDump] GDB 비정상 종료: exitCode={}, file={}, outputLen={}",
                    exitCode, corePath.getName(), rawOutput.length());
        } else {
            logger.info("[CoreDump] GDB 정상 종료: file={}, outputLen={}", corePath.getName(), rawOutput.length());
        }
        if (rawOutput.length() < 10) {
            logger.error("[CoreDump] GDB 출력 없음 — GDB 설치 여부 및 파일 형식 확인 필요: {}", corePath.getName());
        }

        return rawOutput.toString();
    }

    // --batch 모드는 (gdb) 프롬프트를 출력하지 않으므로 echo 마커로 섹션 경계를 표시
    private static final String SECTION_PREFIX = "===SECTION:";

    private List<String> buildGdbCommand(File corePath, File execPath) {
        String gdb = config.getGdbCliPath();
        List<String> cmd = new ArrayList<>();
        cmd.add(gdb);
        cmd.add("--batch");
        cmd.add("--nx");

        // 코어 단독/실행파일 페어링 모두 동일한 리치 명령 세트를 실행한다.
        // (레지스터·공유 라이브러리·bt full·메모리 매핑·크래시 디스어셈블리는 코어에 이미
        //  담긴 정보라 실행 파일이 없어도 gdb 가 추출 가능 — 함수명 심볼만 exec/디버그심볼 필요.)
        // 제공 방식만 분기: exec 있으면 positional `<exec> <core>` (심볼 자동 로드),
        //                   없으면 세션 내 `core-file <core>` 로 로드.
        boolean hasExec = execPath != null && execPath.exists();

        cmd.add("-ex"); cmd.add("set pagination off");
        cmd.add("-ex"); cmd.add("set print elements 50");
        if (!hasExec) { cmd.add("-ex"); cmd.add("core-file " + corePath.getAbsolutePath()); }

        cmd.addAll(Arrays.asList(
            "-ex", "echo " + SECTION_PREFIX + "sharedlibrary===\\n",
            "-ex", "info sharedlibrary",
            "-ex", "echo " + SECTION_PREFIX + "registers===\\n",
            "-ex", "info registers",
            "-ex", "echo " + SECTION_PREFIX + "bt===\\n",
            "-ex", "bt",
            "-ex", "echo " + SECTION_PREFIX + "bt_full===\\n",
            "-ex", "bt full",
            "-ex", "echo " + SECTION_PREFIX + "threads===\\n",
            "-ex", "info threads",
            "-ex", "echo " + SECTION_PREFIX + "thread_apply===\\n",
            "-ex", "thread apply all bt full",
            // 신규: 메모리 매핑(NT_FILE) — 어떤 바이너리/라이브러리가 로드됐는지
            "-ex", "echo " + SECTION_PREFIX + "proc_mappings===\\n",
            "-ex", "info proc mappings",
            // 신규: 크래시 지점($pc) 디스어셈블리
            "-ex", "echo " + SECTION_PREFIX + "disasm===\\n",
            "-ex", "x/16i $pc"
        ));

        if (hasExec) {
            cmd.add(execPath.getAbsolutePath());
            cmd.add(corePath.getAbsolutePath());
        }
        return cmd;
    }

    private AnalysisProgress buildLogProgress(String filename, String line) {
        AnalysisProgress p = new AnalysisProgress();
        p.setFilename(filename);
        p.setStatus(AnalysisProgress.Status.RUNNING);
        p.setPercent(50);
        p.setMessage("GDB 실행 중...");
        p.setLogLine(line);
        return p;
    }

    // ── GDB 출력 파싱 ─────────────────────────────────────────────

    private static final Pattern SIGNAL_PATTERN =
            Pattern.compile("Program terminated with signal (\\S+),\\s*(.+)");
    private static final Pattern CORE_PROGRAM_PATTERN =
            Pattern.compile("Core was generated by `(.+?)'");
    private static final Pattern GDB_VERSION_PATTERN =
            Pattern.compile("GNU gdb.*?(\\d+\\.\\d+[\\d.]*)");
    private static final Pattern FRAME_PATTERN =
            Pattern.compile("#(\\d+)\\s+(0x[0-9a-fA-F]+\\s+in\\s+|)(\\S+)\\s*\\(([^)]*)\\)(.*)");
    private static final Pattern FRAME_SIMPLE_PATTERN =
            Pattern.compile("#(\\d+)\\s+(.+)");
    private static final Pattern REGISTER_PATTERN =
            Pattern.compile("^(\\w+)\\s+(0x[0-9a-fA-F]+)");
    private static final Pattern SHAREDLIB_PATTERN =
            Pattern.compile("^(0x[0-9a-fA-F]+)\\s+(0x[0-9a-fA-F]+)\\s+(Yes(?:\\s+\\(\\*\\))?|No)\\s+(\\S+)");
    // 심볼 미로드(No) 라이브러리는 From/To 주소가 비어 있어 위 패턴에 안 잡힘 → 별도 캡처
    // (예: "                    No          /sw/oracle/client/lib/libclntsh.so.19.1")
    private static final Pattern SHAREDLIB_NOSYM_PATTERN =
            Pattern.compile("^\\s+(No)\\s+(/\\S+)\\s*$");
    private static final Pattern THREAD_LINE_PATTERN =
            Pattern.compile("^\\s*(\\*?)\\s*(\\d+)\\s+(Thread\\s+\\S+(?:\\s+\\(LWP\\s+\\d+\\))?)\\s*(.*)");
    private static final Pattern THREAD_APPLY_HEADER =
            Pattern.compile("^Thread\\s+(\\d+)\\s+\\(");

    private enum Section { NONE, BT, BT_FULL, SHAREDLIB, REGISTERS, THREADS, THREAD_APPLY_BT, PROC_MAPPINGS, DISASM }

    CoreDumpAnalysisResult parseGdbOutput(String rawOutput, String filename, String executableName) {
        CoreDumpAnalysisResult result = new CoreDumpAnalysisResult();
        result.setFilename(filename);
        result.setExecutableName(executableName);
        result.setGdbRawOutput(rawOutput);
        result.setMainBacktrace(new ArrayList<>());
        result.setAllThreads(new ArrayList<>());
        result.setRegisters(new LinkedHashMap<>());
        result.setSharedLibraries(new ArrayList<>());
        result.setMemoryMappings(new ArrayList<>());
        result.setCrashDisassembly(new ArrayList<>());

        if (rawOutput == null || rawOutput.isEmpty()) {
            logger.warn("[CoreDump] GDB 출력 비어있음: {}", filename);
            result.setErrorMessage("GDB 출력이 없습니다. GDB 설치 여부 및 코어 파일 형식을 확인하세요.");
            return result;
        }

        // GDB 파일 인식 실패 조기 감지 (파싱 전)
        // "No such file or directory"는 숫자로 시작하는 줄(소스 코드 라인 번호 + 경로) 제외:
        //   정상:  "688     /home/.../file.c: No such file or directory."  → GDB 소스 표시 실패, 무시
        //   오류:  "/path/to/core: No such file or directory."              → 코어/실행 파일 자체 없음
        for (String raw : rawOutput.split("\n")) {
            String l = raw.strip();
            boolean isSourceLineMiss = l.contains("No such file or directory") && l.matches("^\\d+.*");
            if (isSourceLineMiss) continue;

            if (l.contains("is not a core dump") || l.contains("file format not recognized")
                    || l.contains("No such file or directory") || l.contains("not a core file")) {
                // 사용자 친화적 에러 메시지 생성
                String userMsg;
                String fn = filename.toLowerCase();
                if ((fn.startsWith("vmcore") || fn.equals("vmcore"))
                        && (l.contains("is not a core dump") || l.contains("file format not recognized"))) {
                    userMsg = "vmcore는 Linux 커널 크래시 덤프입니다. GDB로는 분석할 수 없으며, " +
                              "'crash' 유틸리티로 분석하세요.";
                } else if (l.contains("is not a core dump") || l.contains("file format not recognized")) {
                    userMsg = "파일 형식을 인식할 수 없습니다. 유효한 코어 덤프 파일(.core, core.PID 등)인지 확인하세요. "
                              + "일반 실행 파일이나 로그 파일은 분석할 수 없습니다.";
                } else if (l.contains("No such file or directory")) {
                    userMsg = "코어 덤프 파일 또는 실행 파일을 찾을 수 없습니다. 파일이 삭제되었거나 경로가 올바르지 않습니다.";
                } else if (l.contains("not a core file")) {
                    userMsg = "코어 덤프 파일 형식이 아닙니다. GDB가 지원하는 코어 덤프 파일(.core, core.PID)인지 확인하세요.";
                } else {
                    userMsg = "GDB가 파일을 인식하지 못했습니다: " + l;
                }
                logger.warn("[CoreDump] GDB 파일 인식 실패: filename={}, reason='{}'", filename, l);
                result.setErrorMessage(userMsg);
                return result;
            }
        }

        Section currentSection = Section.NONE;
        List<GdbStackFrame> currentBt = new ArrayList<>();
        GdbThreadInfo currentThread = null;
        Map<Integer, GdbThreadInfo> threadMap = new LinkedHashMap<>();
        List<String> currentFrameLocals = null;

        String[] lines = rawOutput.split("\n");
        for (String raw : lines) {
            String line = raw.stripTrailing();

            // GDB 버전
            if (result.getGdbVersion() == null && line.startsWith("GNU gdb")) {
                Matcher m = GDB_VERSION_PATTERN.matcher(line);
                if (m.find()) result.setGdbVersion(m.group(1));
                else result.setGdbVersion(line.trim());
            }

            // 시그널
            if (result.getCrashSignal() == null) {
                Matcher m = SIGNAL_PATTERN.matcher(line);
                if (m.find()) {
                    result.setCrashSignal(m.group(1));
                    result.setSignalDescription(m.group(2).trim());
                }
            }

            // 프로그램명
            if (result.getCoreProgramName() == null) {
                Matcher m = CORE_PROGRAM_PATTERN.matcher(line);
                if (m.find()) result.setCoreProgramName(m.group(1).trim());
            }

            // 섹션 전환 감지 — echo 마커 (--batch 모드는 (gdb) 프롬프트 미출력)
            if (line.startsWith(SECTION_PREFIX)) {
                // 이전 섹션 결과 저장
                if ((currentSection == Section.BT || currentSection == Section.BT_FULL)
                        && result.getMainBacktrace().isEmpty()) {
                    result.setMainBacktrace(new ArrayList<>(currentBt));
                }
                if (currentSection == Section.THREAD_APPLY_BT && currentThread != null) {
                    currentThread.setBacktrace(new ArrayList<>(currentBt));
                    threadMap.put(currentThread.getId(), currentThread);
                }

                currentBt = new ArrayList<>();
                currentFrameLocals = null;
                currentThread = null;

                String sectionName = line.substring(SECTION_PREFIX.length())
                        .replace("===", "").trim();
                switch (sectionName) {
                    case "sharedlibrary"  -> currentSection = Section.SHAREDLIB;
                    case "registers"      -> currentSection = Section.REGISTERS;
                    case "bt"             -> currentSection = Section.BT;
                    case "bt_full"        -> currentSection = Section.BT_FULL;
                    case "threads"        -> currentSection = Section.THREADS;
                    case "thread_apply"   -> currentSection = Section.THREAD_APPLY_BT;
                    case "proc_mappings"  -> currentSection = Section.PROC_MAPPINGS;
                    case "disasm"         -> currentSection = Section.DISASM;
                    default               -> currentSection = Section.NONE;
                }
                continue;
            }

            // 섹션별 파싱
            switch (currentSection) {
                case SHAREDLIB:
                    parseSharedLibLine(line, result.getSharedLibraries());
                    break;

                case REGISTERS:
                    parseRegisterLine(line, result.getRegisters());
                    break;

                case PROC_MAPPINGS: {
                    // 매핑 행만 수집 (헤더/전문 제외) — 예: "0x400000  0x401000  0x1000  0x0  /path/bin"
                    String t = line.strip();
                    if (t.startsWith("0x")) result.getMemoryMappings().add(t);
                    break;
                }

                case DISASM: {
                    // 명령어 라인만 수집 — 예: "=> 0x... <func+0x..>: mov ..." / "0x...: call ..."
                    String t = line.strip();
                    if (t.startsWith("0x") || t.startsWith("=>")) result.getCrashDisassembly().add(t);
                    break;
                }

                case BT:
                case BT_FULL: {
                    GdbStackFrame frame = parseFrameLine(line);
                    if (frame != null) {
                        if (currentSection == Section.BT_FULL) {
                            currentFrameLocals = new ArrayList<>();
                            frame.setLocals(currentFrameLocals);
                        }
                        currentBt.add(frame);
                    } else if (currentSection == Section.BT_FULL
                            && currentFrameLocals != null
                            && !line.isBlank()
                            && !line.startsWith(SECTION_PREFIX)) {
                        currentFrameLocals.add(line.trim());
                    }
                    break;
                }

                case THREADS:
                    parseThreadInfoLine(line, threadMap);
                    break;

                case THREAD_APPLY_BT: {
                    Matcher thm = THREAD_APPLY_HEADER.matcher(line);
                    if (thm.find()) {
                        // 이전 스레드 저장
                        if (currentThread != null) {
                            currentThread.setBacktrace(new ArrayList<>(currentBt));
                            threadMap.put(currentThread.getId(), currentThread);
                        }
                        int tid = Integer.parseInt(thm.group(1));
                        currentThread = threadMap.computeIfAbsent(tid, id -> {
                            GdbThreadInfo ti = new GdbThreadInfo();
                            ti.setId(id);
                            ti.setTargetId(line.trim());
                            return ti;
                        });
                        currentBt = new ArrayList<>();
                        currentFrameLocals = null;
                    } else {
                        GdbStackFrame frame = parseFrameLine(line);
                        if (frame != null) {
                            currentFrameLocals = new ArrayList<>();
                            frame.setLocals(currentFrameLocals);
                            currentBt.add(frame);
                        } else if (currentFrameLocals != null && !line.isBlank()) {
                            currentFrameLocals.add(line.trim());
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }

        // 루프 종료 후 마지막 섹션 저장
        if ((currentSection == Section.BT || currentSection == Section.BT_FULL)
                && result.getMainBacktrace().isEmpty()) {
            result.setMainBacktrace(new ArrayList<>(currentBt));
        }
        if (currentSection == Section.THREAD_APPLY_BT && currentThread != null) {
            currentThread.setBacktrace(new ArrayList<>(currentBt));
            threadMap.put(currentThread.getId(), currentThread);
        }

        // 스레드 목록 — 크래시 스레드 먼저
        List<GdbThreadInfo> threads = new ArrayList<>(threadMap.values());
        threads.sort(Comparator.comparingInt(t -> t.isCurrent() ? -1 : t.getId()));
        result.setAllThreads(threads);

        // 프레임 품질 분류 + 분석 신뢰도 판정 (심볼 없는/손상된 스택 대응)
        assessAnalysisQuality(result, rawOutput);

        // 파싱 결과 요약 로그
        logger.info("[CoreDump] GDB 파싱 완료: filename={}, signal={}, frames={}, resolved={}, conf={}, threads={}{}",
                filename,
                result.getCrashSignal() != null ? result.getCrashSignal() : "없음",
                result.getMainBacktrace().size(),
                result.getResolvedFrameCount(),
                result.getAnalysisConfidence(),
                result.getAllThreads().size(),
                result.getErrorMessage() != null ? ", warn='" + result.getErrorMessage() + "'" : "");

        return result;
    }

    private void parseSharedLibLine(String line, List<GdbSharedLib> libs) {
        Matcher m = SHAREDLIB_PATTERN.matcher(line);
        if (m.find()) {
            GdbSharedLib lib = new GdbSharedLib();
            lib.setFromAddr(m.group(1));
            lib.setToAddr(m.group(2));
            lib.setSymsRead(m.group(3));
            lib.setPath(m.group(4));
            libs.add(lib);
            return;
        }
        // 심볼 미로드(No) 라이브러리 — From/To 주소 없음
        Matcher mn = SHAREDLIB_NOSYM_PATTERN.matcher(line);
        if (mn.find()) {
            GdbSharedLib lib = new GdbSharedLib();
            lib.setSymsRead(mn.group(1));   // "No"
            lib.setPath(mn.group(2));
            libs.add(lib);
        }
    }

    private void parseRegisterLine(String line, Map<String, String> registers) {
        Matcher m = REGISTER_PATTERN.matcher(line.trim());
        if (m.find()) {
            registers.put(m.group(1), m.group(2));
        }
    }

    private GdbStackFrame parseFrameLine(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("#")) return null;

        GdbStackFrame frame = new GdbStackFrame();

        // 패턴 1: #N  0xADDR in function(args) at loc
        Matcher m = FRAME_PATTERN.matcher(trimmed);
        if (m.find()) {
            frame.setFrameNumber(Integer.parseInt(m.group(1)));
            String addrPart = m.group(2).trim();
            if (addrPart.endsWith(" in")) addrPart = addrPart.substring(0, addrPart.length() - 3).trim();
            frame.setAddress(addrPart.isEmpty() ? null : addrPart);
            frame.setFunction(m.group(3));
            frame.setArgs(m.group(4).trim());
            String rest = m.group(5).trim();
            // rest: " at file.c:42" or " from /lib/libxxx.so"
            if (rest.startsWith("at ")) {
                frame.setLocation(rest.substring(3).trim());
            } else if (rest.startsWith("from ")) {
                frame.setLibrary(rest.substring(5).trim());
            }
            return frame;
        }

        // 패턴 2: #N  anything
        Matcher m2 = FRAME_SIMPLE_PATTERN.matcher(trimmed);
        if (m2.find()) {
            frame.setFrameNumber(Integer.parseInt(m2.group(1)));
            frame.setFunction(m2.group(2).trim());
            return frame;
        }
        return null;
    }

    private void parseThreadInfoLine(String line, Map<Integer, GdbThreadInfo> threadMap) {
        Matcher m = THREAD_LINE_PATTERN.matcher(line);
        if (!m.find()) return;
        boolean isCurrent = !m.group(1).isEmpty();
        int id;
        try { id = Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return; }
        String targetId = m.group(3).trim();
        String rest = m.group(4).trim();

        GdbThreadInfo ti = threadMap.computeIfAbsent(id, i -> new GdbThreadInfo());
        ti.setId(id);
        ti.setCurrent(isCurrent);
        ti.setTargetId(targetId);
        ti.setCurrentFrame(rest);
        if (ti.getBacktrace() == null) ti.setBacktrace(new ArrayList<>());

        // 스레드 이름 추출 (예: "myapp" from targetId)
        Pattern nameP = Pattern.compile("\"([^\"]+)\"");
        Matcher nm = nameP.matcher(targetId);
        if (nm.find()) ti.setName(nm.group(1));
    }

    private String buildCrashSummary(CoreDumpAnalysisResult result) {
        List<GdbStackFrame> bt = result.getMainBacktrace();
        if (bt == null || bt.isEmpty()) return null;
        // RESOLVED 프레임 우선 5개 (전부 ??/노이즈인 경우 폴백으로 상위 5개)
        List<GdbStackFrame> pick = bt.stream()
                .filter(f -> "RESOLVED".equals(f.getQuality()))
                .limit(5)
                .collect(Collectors.toList());
        if (pick.isEmpty()) pick = bt.stream().limit(5).collect(Collectors.toList());
        return pick.stream()
                .map(f -> "#" + f.getFrameNumber() + " " +
                        (f.getAddress() != null ? f.getAddress() + " in " : "") +
                        (f.getFunction() != null ? f.getFunction() : "??") +
                        (f.getLocation() != null ? " at " + f.getLocation() : ""))
                .collect(Collectors.joining("\n"));
    }

    // ── 분석 신뢰도 판정 (심볼 없는/손상된 스택 대응) ────────────────

    /** 함수명이 심볼 없음(?? 또는 null)인지. */
    private static boolean isUnsymbolized(GdbStackFrame f) {
        String fn = f.getFunction();
        return fn == null || fn.isBlank() || "??".equals(fn.trim());
    }

    /**
     * 주소가 코드가 아닌 데이터(스택 스캔 노이즈)로 보이는지.
     * 0x + 16 hex 를 8바이트로 보고 6바이트 이상이 출력가능 ASCII(0x20–0x7e)이거나
     * 모든 바이트가 동일하면 garbage 로 판정.
     * 예: 0x2020202020202020, 0x454c545730353232("ELTW0522"), 0x3030303134313135("00014115")
     */
    static boolean looksLikeStackGarbage(String address) {
        if (address == null) return false;
        String hex = address.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        if (hex.length() != 16) return false;          // 64-bit 정규 주소만 평가
        int[] bytes = new int[8];
        for (int i = 0; i < 8; i++) {
            try {
                bytes[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        int printable = 0;
        boolean allSame = true;
        for (int i = 0; i < 8; i++) {
            if (bytes[i] >= 0x20 && bytes[i] <= 0x7e) printable++;
            if (bytes[i] != bytes[0]) allSame = false;
        }
        return printable >= 6 || allSame;
    }

    /** 프레임 1개의 quality 등급 산정. */
    private static String classifyFrame(GdbStackFrame f) {
        boolean resolved = !isUnsymbolized(f) || f.getLocation() != null || f.getLibrary() != null;
        if (resolved) return "RESOLVED";
        if (looksLikeStackGarbage(f.getAddress())) return "GARBAGE";
        return "UNSYMBOLIZED";
    }

    /**
     * 메인 백트레이스/스레드 백트레이스 프레임에 quality 부여 후
     * 분석 신뢰도(analysisConfidence) + 신뢰도 저하 사유(qualityWarnings) 산정.
     */
    void assessAnalysisQuality(CoreDumpAnalysisResult result, String rawOutput) {
        List<GdbStackFrame> bt = result.getMainBacktrace();
        if (bt == null) bt = Collections.emptyList();

        // 1) 프레임 분류 (메인 + 각 스레드)
        for (GdbStackFrame f : bt) f.setQuality(classifyFrame(f));
        if (result.getAllThreads() != null) {
            for (GdbThreadInfo t : result.getAllThreads()) {
                if (t.getBacktrace() != null) {
                    for (GdbStackFrame f : t.getBacktrace()) f.setQuality(classifyFrame(f));
                }
            }
        }

        // 1.5) 주소→모듈 귀속 (심볼 없는 ?? 프레임을 소유 라이브러리·벤더에 매핑)
        attributeModules(result);

        int total = bt.size();
        int resolved = 0, garbage = 0;
        GdbStackFrame firstResolved = null;
        for (GdbStackFrame f : bt) {
            if ("RESOLVED".equals(f.getQuality())) {
                resolved++;
                if (firstResolved == null) firstResolved = f;
            } else if ("GARBAGE".equals(f.getQuality())) {
                garbage++;
            }
        }
        result.setTotalFrameCount(total);
        result.setResolvedFrameCount(resolved);
        result.setFirstResolvedFrame(firstResolved);

        // 공유 라이브러리 심볼 존재 여부
        boolean libSyms = result.getSharedLibraries() != null && result.getSharedLibraries().stream()
                .anyMatch(l -> l.getSymsRead() != null && l.getSymsRead().startsWith("Yes"));
        boolean symbolsAvailable = resolved > 0 || libSyms;
        result.setSymbolsAvailable(symbolsAvailable);

        // 결함 모듈 · self-raise · 가이드 종류 판정 (귀속 결과 활용)
        computeFaultingModule(result);

        double garbageRatio = total > 0 ? (double) garbage / total : 0.0;

        // 2) 신뢰도 저하 사유 수집
        List<String> warnings = new ArrayList<>();
        String raw = rawOutput == null ? "" : rawOutput;
        // 코어 단독(실행 파일 미페어링) — 심볼 향상을 위해 어떤 바이너리를 올려야 하는지 명시
        if (result.getExecutableName() == null) {
            String prog = result.getCoreProgramName();
            String bin = (prog != null && !prog.isBlank()) ? prog.trim().split("\\s+")[0] : null;
            warnings.add("실행 파일 없이 코어 단독으로 분석됨 — 시그널·레지스터·로드된 모듈·크래시 지점은 추출되었으나 "
                    + "함수명 심볼 해석은 제한됩니다."
                    + (bin != null ? " 정확한 콜스택을 위해 프로그램 바이너리를 함께 페어링하세요: " + bin : ""));
        }
        boolean noSharedLibs = raw.contains("No shared libraries loaded at this time")
                || (result.getSharedLibraries() == null || result.getSharedLibraries().isEmpty());
        if (noSharedLibs) {
            warnings.add("공유 라이브러리 정보 없음 — 실행 파일/라이브러리 경로가 코어와 매칭되지 않습니다.");
        }
        // 정상 페어링에도 결함이 서드파티 stripped 라이브러리 내부인 경우 — exec 페어링 재촉 대신 벤더 안내
        if ("THIRDPARTY_STRIPPED".equals(result.getGuidanceKind())) {
            String vendor = result.getFaultingModuleVendor() != null
                    ? " (" + result.getFaultingModuleVendor() + ")" : "";
            StringBuilder w = new StringBuilder();
            w.append("크래시는 서드파티 라이브러리 ")
             .append(result.getFaultingModule() != null ? result.getFaultingModule() : "(미상 모듈)")
             .append(vendor)
             .append(" 내부에서 발생했습니다 — 애플리케이션 실행 파일 페어링은 정상이나 해당 라이브러리에 "
                   + "심볼이 없어 콜스택 해석이 제한됩니다. 해당 벤더의 debuginfo 확보 또는 벤더 측 이슈로 취급하세요.");
            if (result.isSelfRaisedSignal()) {
                w.append(" 최상단 raise()/abort() 는 시그널 핸들러의 자체-재raise 로, 실제 결함 지점은 결함 모듈 프레임입니다.");
            }
            warnings.add(w.toString());
        } else if (resolved == 0 || raw.contains("No symbol table info available")) {
            warnings.add("디버그 심볼 없음 — stripped 바이너리이거나 일치하는 실행 파일이 페어링되지 않았습니다.");
        }
        if (raw.contains("Cannot access memory at address")) {
            warnings.add("일부 메모리 접근 불가 — 코어가 부분 저장(truncated)되었거나 매핑이 누락되었습니다.");
        }
        if (raw.contains(".reg-xstate") && raw.contains("too small")) {
            warnings.add("레지스터 확장 상태(xstate) 일부 손상 — 레지스터 값 신뢰도가 낮을 수 있습니다.");
        }
        if (raw.contains("Can't open file (null) during file-backed mapping")) {
            warnings.add("파일 기반 매핑 노트 처리 실패 — 원본 실행 환경의 파일을 찾을 수 없습니다.");
        }
        if (garbageRatio >= 0.5 && total > 100) {
            warnings.add("스택이 손상되어 백트레이스 대부분(" + garbage + "/" + total
                    + ")이 무효 프레임입니다 — 스택 스캔 노이즈로 추정됩니다.");
        }

        // 3) 신뢰도 등급
        String confidence;
        if (resolved == 0 || (!symbolsAvailable && garbageRatio >= 0.5)) {
            confidence = "LOW";
        } else if (garbageRatio >= 0.3 || !libSyms || resolved < 3) {
            confidence = "MEDIUM";
        } else {
            confidence = "HIGH";
        }
        result.setAnalysisConfidence(confidence);
        result.setQualityWarnings(warnings);
    }

    // ── 주소→모듈 귀속 ────────────────────────────────────────────

    /** proc_mappings / sharedlibrary 로부터 파싱한 로드 구간. */
    private static final class ModRange {
        final long start, end, fileOffset;
        final String objfile;
        ModRange(long start, long end, long fileOffset, String objfile) {
            this.start = start; this.end = end; this.fileOffset = fileOffset; this.objfile = objfile;
        }
    }

    /** 경로 basename 추출. */
    private static String basenameOf(String path) {
        if (path == null) return null;
        String p = path.trim();
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** hex 주소 문자열(0x…) → unsigned long. 실패 시 null. */
    private static Long parseHexAddr(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.isEmpty()) return null;
        try { return Long.parseUnsignedLong(h, 16); } catch (NumberFormatException e) { return null; }
    }

    /**
     * 네이티브 라이브러리 경로 → 벤더 라벨. 미상이면 null.
     * util/MiddlewareDetector 의 벤더 네이밍과 라벨 정합 유지(native .so 경로 전용 소형 분류).
     */
    static String classifyVendor(String objfilePath) {
        if (objfilePath == null) return null;
        String p = objfilePath.toLowerCase();
        String base = basenameOf(p);
        if (base == null) return null;
        if (base.startsWith("libclntsh") || base.startsWith("libnnz") || base.startsWith("libclntshcore")
                || base.startsWith("libnque") || base.startsWith("libsyscomm")
                || p.contains("oracore") || p.contains("/oracle/"))
            return "Oracle Client";
        if (p.contains("/tmax/") || base.startsWith("libsvr") || base.startsWith("libcli")
                || base.startsWith("liboras") || base.startsWith("libdhcli") || base.startsWith("libtmax"))
            return "Tmax";
        if (base.startsWith("libc-") || base.equals("libc.so.6") || base.startsWith("libpthread")
                || base.startsWith("libm-") || base.startsWith("libm.so") || base.startsWith("libdl")
                || base.startsWith("librt") || base.startsWith("libnsl") || base.startsWith("libresolv")
                || base.startsWith("libnss") || base.startsWith("ld-") || base.startsWith("ld-linux"))
            return "glibc";
        if (base.startsWith("libstdc++") || base.startsWith("libgcc_s"))
            return "GCC 런타임";
        return null;
    }

    /** memoryMappings(우선) / sharedLibraries(폴백) 를 로드 구간 목록으로 파싱. */
    private List<ModRange> parseModuleRanges(CoreDumpAnalysisResult result) {
        List<ModRange> ranges = new ArrayList<>();
        List<String> maps = result.getMemoryMappings();
        if (maps != null) {
            for (String line : maps) {
                // "0xSTART 0xEND 0xSIZE 0xOFFSET /path/objfile"
                String[] tok = line.trim().split("\\s+");
                if (tok.length < 5) continue;
                if (!tok[0].startsWith("0x") || !tok[4].startsWith("/")) continue;
                if (tok[4].startsWith("/SYSV")) continue; // SysV 공유메모리 세그먼트 제외
                Long start = parseHexAddr(tok[0]);
                Long end   = parseHexAddr(tok[1]);
                Long off   = tok[3].startsWith("0x") ? parseHexAddr(tok[3]) : 0L;
                if (start == null || end == null) continue;
                if (off == null) off = 0L;
                String obj = tok[4];
                ranges.add(new ModRange(start, end, off, obj));
            }
        }
        // proc_mappings 미가용 시 sharedLibraries From/To 로 폴백(fileOffset 정보 없음 → 0)
        if (ranges.isEmpty() && result.getSharedLibraries() != null) {
            for (GdbSharedLib lib : result.getSharedLibraries()) {
                Long start = parseHexAddr(lib.getFromAddr());
                Long end   = parseHexAddr(lib.getToAddr());
                if (start == null || end == null || lib.getPath() == null) continue;
                ranges.add(new ModRange(start, end, 0L, lib.getPath()));
            }
        }
        return ranges;
    }

    private ModRange resolveRange(long addr, List<ModRange> ranges) {
        for (ModRange m : ranges) {
            if (Long.compareUnsigned(addr, m.start) >= 0 && Long.compareUnsigned(addr, m.end) < 0) return m;
        }
        return null;
    }

    /** 프레임 1개에 소유 모듈/오프셋/벤더/심볼여부 귀속. */
    private void attributeFrame(GdbStackFrame f, List<ModRange> ranges, Map<String, String> symsByBase) {
        if (f == null) return;
        Long addr = parseHexAddr(f.getAddress());
        if (addr == null || addr == 0L) return;
        ModRange m = resolveRange(addr, ranges);
        if (m != null) {
            String base = basenameOf(m.objfile);
            f.setModule(base);
            long off = addr - m.start + m.fileOffset;
            f.setModuleOffset("0x" + Long.toHexString(off));
            f.setModuleVendor(classifyVendor(m.objfile));
            String syms = symsByBase.get(base);
            if (syms != null) f.setModuleHasSymbols(syms.startsWith("Yes"));
        } else if (f.getLibrary() != null) {
            // 구간 매칭 실패 시 from 절 라이브러리 basename 폴백(오프셋 미상)
            String base = basenameOf(f.getLibrary());
            f.setModule(base);
            f.setModuleVendor(classifyVendor(f.getLibrary()));
            String syms = symsByBase.get(base);
            if (syms != null) f.setModuleHasSymbols(syms.startsWith("Yes"));
        }
    }

    /** 메인 + 모든 스레드 백트레이스 프레임에 모듈 귀속 수행. */
    private void attributeModules(CoreDumpAnalysisResult result) {
        List<ModRange> ranges = parseModuleRanges(result);
        Map<String, String> symsByBase = new HashMap<>();
        if (result.getSharedLibraries() != null) {
            for (GdbSharedLib lib : result.getSharedLibraries()) {
                if (lib.getPath() != null) {
                    symsByBase.put(basenameOf(lib.getPath()), lib.getSymsRead() == null ? "" : lib.getSymsRead());
                }
            }
        }
        if (ranges.isEmpty() && symsByBase.isEmpty()) return;
        if (result.getMainBacktrace() != null) {
            for (GdbStackFrame f : result.getMainBacktrace()) attributeFrame(f, ranges, symsByBase);
        }
        if (result.getAllThreads() != null) {
            for (GdbThreadInfo t : result.getAllThreads()) {
                if (t.getBacktrace() != null) {
                    for (GdbStackFrame f : t.getBacktrace()) attributeFrame(f, ranges, symsByBase);
                }
            }
        }
    }

    /** 시그널 자체-재raise 계열 함수명(실제 결함 지점이 아님). */
    private static boolean isSignalPlumbing(String fn) {
        if (fn == null) return false;
        String f = fn.trim();
        return f.equals("raise") || f.equals("abort") || f.equals("gsignal")
                || f.equals("pthread_kill") || f.equals("__pthread_kill_implementation")
                || f.equals("__pthread_kill_internal") || f.equals("__GI_raise") || f.equals("__GI_abort")
                || f.equals("__stack_chk_fail") || f.equals("__libc_message") || f.equals("__fortify_fail");
    }

    /**
     * 결함 모듈(최상단 시그널 프레임을 건너뛴 첫 실질 프레임의 소유 모듈) + self-raise 여부 +
     * 신뢰도 가이드 종류(guidanceKind) 판정.
     */
    private void computeFaultingModule(CoreDumpAnalysisResult result) {
        List<GdbStackFrame> bt = result.getMainBacktrace();
        if (bt == null) bt = Collections.emptyList();

        // self-raise: 최상단 함수가 raise/abort 계열
        boolean selfRaised = !bt.isEmpty() && isSignalPlumbing(bt.get(0).getFunction());
        result.setSelfRaisedSignal(selfRaised);

        // 결함 프레임: 시그널 배관/노이즈 프레임을 건너뛴 첫 프레임
        GdbStackFrame fault = null;
        for (GdbStackFrame f : bt) {
            if (isSignalPlumbing(f.getFunction())) continue;
            if ("GARBAGE".equals(f.getQuality())) continue;
            fault = f;
            break;
        }
        if (fault != null) {
            result.setFaultingModule(fault.getModule());
            result.setFaultingModuleVendor(fault.getModuleVendor());
            result.setFaultingModuleHasSymbols(fault.getModuleHasSymbols());
        }

        // 앱 바이너리 basename (페어링 exec 우선, 없으면 core 프로그램명)
        String appBase = null;
        if (result.getExecutableName() != null) {
            appBase = basenameOf(result.getExecutableName());
        } else if (result.getCoreProgramName() != null && !result.getCoreProgramName().isBlank()) {
            appBase = basenameOf(result.getCoreProgramName().trim().split("\\s+")[0]);
        }

        String gk;
        if (result.getExecutableName() == null) {
            gk = "EXEC_MISSING";
        } else {
            String fm = result.getFaultingModule();
            Boolean fhs = result.getFaultingModuleHasSymbols();
            boolean faultHasSyms = fhs != null && fhs;
            boolean faultIsApp = fm != null && appBase != null && fm.equals(appBase);
            boolean faultIsThirdParty = fm != null && !faultIsApp
                    && (result.getFaultingModuleVendor() != null || Boolean.FALSE.equals(fhs));
            if (result.getResolvedFrameCount() > 0 && faultHasSyms) {
                gk = "OK";
            } else if (faultIsThirdParty) {
                gk = "THIRDPARTY_STRIPPED";
            } else if (faultIsApp && !faultHasSyms) {
                gk = "APP_STRIPPED";
            } else if (result.getResolvedFrameCount() > 0) {
                gk = "OK";
            } else {
                gk = "APP_STRIPPED";
            }
        }
        result.setGuidanceKind(gk);
    }

    private void updateDbError(String filename, String errorMessage) {
        try {
            CoreDumpAnalysisEntity entity = repository.findByFilename(filename)
                    .orElseGet(() -> {
                        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
                        e.setFilename(filename);
                        e.setFileDeleted(false);
                        return e;
                    });
            entity.setStatus("ERROR");
            entity.setErrorMessage(errorMessage);
            repository.save(entity);
        } catch (Exception e) {
            logger.warn("[CoreDump] DB 오류 갱신 실패: {}", e.getMessage());
        }
    }

    // ── 소스 코드 뷰어 ─────────────────────────────────────────────

    public boolean existsAnalysis(String filename) {
        return repository.existsByFilename(filename);
    }

    public Map<String, Object> readSourceContext(String locationStr, int contextLines) {
        if (locationStr == null || locationStr.isBlank())
            return Map.of("error", "위치 정보가 없습니다");
        if (locationStr.contains("\0"))
            return Map.of("error", "유효하지 않은 경로입니다");

        int lastColon = locationStr.lastIndexOf(':');
        if (lastColon <= 0)
            return Map.of("error", "위치 정보 형식이 올바르지 않습니다 (파일:라인 형식 필요)");

        String rawPath = locationStr.substring(0, lastColon);
        int targetLine;
        try {
            targetLine = Integer.parseInt(locationStr.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            return Map.of("error", "라인 번호가 올바르지 않습니다");
        }
        if (targetLine < 1) return Map.of("error", "라인 번호는 1 이상이어야 합니다");

        Path path;
        try {
            path = Paths.get(rawPath).toRealPath();
        } catch (IOException e) {
            return Map.of("error", "소스 파일을 찾을 수 없습니다: " + rawPath);
        }
        if (!Files.isReadable(path))
            return Map.of("error", "소스 파일을 읽을 수 없습니다: " + rawPath);

        List<String> allLines;
        try {
            allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Map.of("error", "소스 파일 읽기 실패: " + e.getMessage());
        }

        if (targetLine > allLines.size())
            return Map.of("error", "라인 번호(" + targetLine + ")가 파일 크기(" + allLines.size() + ")를 초과합니다");

        int startLine = Math.max(1, targetLine - contextLines);
        int endLine   = Math.min(allLines.size(), targetLine + contextLines);

        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = startLine; i <= endLine; i++) {
            Map<String, Object> lineMap = new LinkedHashMap<>();
            lineMap.put("lineNum",  i);
            lineMap.put("content",  allLines.get(i - 1));
            lineMap.put("isTarget", i == targetLine);
            lines.add(lineMap);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("lines",      lines);
        res.put("startLine",  startLine);
        res.put("targetLine", targetLine);
        res.put("filePath",   rawPath);
        res.put("fileName",   path.getFileName().toString());
        return res;
    }

    // ── AI 크래시 분석 ─────────────────────────────────────────────
    //
    // heap dump 의 AI 인사이트 인프라(LlmConfigService + AiInsightManager)를
    // HeapDumpAnalyzerService facade 를 통해 재사용한다. 코어 덤프 인사이트는
    // ai_insights 테이블을 합성 키("__core__:" + filename)로 공유한다
    // (HeapAiApiController.compareKey 의 "__compare__:" 패턴과 동일 컨벤션).

    /** ai_insights 테이블 재사용을 위한 코어 덤프 합성 키. */
    public static String coreInsightKey(String filename) {
        return "__core__:" + filename;
    }

    public boolean isLlmEnabled() {
        return llmConfig.isLlmEnabled();
    }

    public String getLlmProvider() {
        return llmConfig.getLlmProvider();
    }

    public void saveAiInsight(String key, Map<String, Object> insightData) {
        aiInsight.saveAiInsight(key, insightData);
    }

    public Map<String, Object> loadAiInsight(String key) {
        return aiInsight.loadAiInsight(key);
    }

    public boolean deleteAiInsight(String key) {
        return aiInsight.deleteAiInsight(key);
    }

    /**
     * 코어 덤프 분석 결과로 LLM 프롬프트를 구성 후 1-shot 분석을 호출한다.
     * 응답 구조는 heap 의 callLlmAnalysis 와 동일({success, data:{summary,rootCause,
     * recommendations,severity,severityDesc}, model, latencyMs, errorCode, error}).
     */
    public Map<String, Object> analyzeCrashWithAi(String filename) {
        Optional<CoreDumpAnalysisResult> opt = loadResult(filename);
        if (opt.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", "NO_RESULT");
            err.put("error", "분석 결과(result.json)를 찾을 수 없습니다. GDB 분석을 먼저 완료하세요.");
            return err;
        }
        String prompt = buildCrashPrompt(opt.get());
        return llmConfig.callLlmAnalysis(prompt);
    }

    /** CoreDumpAnalysisResult → LLM 프롬프트(순수 JSON 응답 지시 포함). */
    public String buildCrashPrompt(CoreDumpAnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 리눅스 코어 덤프(GDB) 분석 전문가입니다. ")
          .append("아래 크래시 정보를 바탕으로 근본 원인과 조치 방안을 한국어로 진단하세요.\n\n");

        sb.append("== 크래시 개요 ==\n");
        sb.append("시그널: ").append(nz(r.getCrashSignal()))
          .append(r.getSignalDescription() != null ? " (" + r.getSignalDescription() + ")" : "").append('\n');
        if (r.getCoreProgramName() != null) sb.append("실행 명령: ").append(r.getCoreProgramName()).append('\n');
        if (r.getExecutableName() != null)  sb.append("실행 파일: ").append(r.getExecutableName()).append('\n');
        if (r.getGdbVersion() != null)      sb.append("GDB 버전: ").append(r.getGdbVersion()).append('\n');
        if (r.isSelfRaisedSignal()) {
            sb.append("시그널 형태: 시그널 핸들러가 raise()/abort() 로 자체-재raise (최상단 raise 프레임은 실제 결함 지점 아님)\n");
        }
        if (r.getFaultingModule() != null) {
            sb.append("결함 모듈: ").append(r.getFaultingModule());
            if (r.getFaultingModuleVendor() != null) sb.append(" (").append(r.getFaultingModuleVendor()).append(')');
            if (Boolean.FALSE.equals(r.getFaultingModuleHasSymbols())) sb.append(" — 심볼 없음(stripped)");
            sb.append('\n');
            if (r.getExecutableName() != null && Boolean.FALSE.equals(r.getFaultingModuleHasSymbols())
                    && r.getFaultingModuleVendor() != null) {
                sb.append("[중요] 애플리케이션 실행 파일은 정상 페어링됐으나 결함은 위 서드파티 라이브러리 내부입니다 — "
                        + "앱 소스 코드 라인을 단정하지 마세요.\n");
            }
        }

        // 분석 신뢰도 — 심볼 없는/손상된 스택일 때 LLM 이 단정 짓지 않도록 명시
        if (r.getAnalysisConfidence() != null) {
            sb.append("\n== 분석 신뢰도 ==\n");
            sb.append("신뢰도: ").append(r.getAnalysisConfidence())
              .append(" (식별된 심볼 프레임 ").append(r.getResolvedFrameCount())
              .append(" / 전체 ").append(r.getTotalFrameCount()).append(")\n");
            sb.append("심볼 가용: ").append(r.isSymbolsAvailable() ? "있음" : "없음").append('\n');
            if (r.getQualityWarnings() != null && !r.getQualityWarnings().isEmpty()) {
                sb.append("저하 사유:\n");
                for (String w : r.getQualityWarnings()) sb.append("  - ").append(w).append('\n');
            }
        }

        List<GdbStackFrame> bt = r.getMainBacktrace();
        if (bt != null && !bt.isEmpty()) {
            GdbStackFrame f0 = bt.get(0);
            // quality 미설정(구버전 result.json)은 정상(RESOLVED)으로 간주
            boolean f0Resolved = f0.getQuality() == null || "RESOLVED".equals(f0.getQuality());
            sb.append("\n== 크래시 지점 (Frame #0) ==\n");
            if (!f0Resolved) {
                sb.append("Frame #0 심볼 없음 — 주소만 존재(정확한 함수/라인 단정 불가).\n");
            }
            sb.append("함수: ").append(nz(f0.getFunction()));
            if (f0.getArgs() != null && !f0.getArgs().isBlank()) sb.append(" (").append(f0.getArgs()).append(')');
            sb.append('\n');
            if (f0.getLocation() != null) sb.append("위치: ").append(f0.getLocation()).append('\n');
            if (f0.getLibrary() != null)  sb.append("라이브러리: ").append(f0.getLibrary()).append('\n');
            if (f0.getAddress() != null)  sb.append("주소: ").append(f0.getAddress()).append('\n');

            // Frame #0 이 심볼 없으면 식별 가능한 최근접 심볼 별도 제공
            GdbStackFrame fr = r.getFirstResolvedFrame();
            if (!f0Resolved && fr != null) {
                sb.append("식별 가능한 최근접 심볼: #").append(fr.getFrameNumber())
                  .append(' ').append(nz(fr.getFunction()));
                if (fr.getLocation() != null)     sb.append(" at ").append(fr.getLocation());
                else if (fr.getLibrary() != null) sb.append(" from ").append(fr.getLibrary());
                sb.append('\n');
            }

            // 콜 체인 — RESOLVED 프레임만 (노이즈/무효 프레임 제외).
            // quality 미설정(구버전)은 정상으로 간주해 기존 동작 유지.
            java.util.function.Predicate<GdbStackFrame> isResolved =
                    f -> f.getQuality() == null || "RESOLVED".equals(f.getQuality());
            List<GdbStackFrame> resolvedFrames = bt.stream()
                    .filter(isResolved)
                    .limit(12)
                    .collect(Collectors.toList());
            int noiseCount = bt.size() - (int) bt.stream().filter(isResolved).count();
            if (!resolvedFrames.isEmpty()) {
                sb.append("\n== 콜 체인 (식별된 심볼 ").append(resolvedFrames.size()).append("프레임) ==\n");
                for (GdbStackFrame f : resolvedFrames) {
                    sb.append('#').append(f.getFrameNumber()).append(' ').append(nz(f.getFunction()));
                    if (f.getLocation() != null)      sb.append(" at ").append(f.getLocation());
                    else if (f.getLibrary() != null)  sb.append(" from ").append(f.getLibrary());
                    sb.append('\n');
                }
                if (noiseCount > 0) {
                    sb.append("(무효/노이즈 프레임 ").append(noiseCount).append("개는 제외됨 — 스택 손상)\n");
                }
            } else {
                sb.append("\n== 콜 체인 (심볼 없음 — 모듈 귀속) ==\n식별된 심볼 프레임 없음. ")
                  .append("아래는 각 프레임 주소를 소유 모듈에 귀속한 결과입니다:\n");
                List<GdbStackFrame> nonGarbage = bt.stream()
                        .filter(f -> !"GARBAGE".equals(f.getQuality()))
                        .limit(12)
                        .collect(Collectors.toList());
                for (GdbStackFrame f : nonGarbage) {
                    sb.append('#').append(f.getFrameNumber()).append(' ');
                    if (f.getModule() != null) {
                        sb.append(f.getModule());
                        if (f.getModuleOffset() != null) sb.append(" + ").append(f.getModuleOffset());
                        if (f.getModuleVendor() != null) sb.append(" (").append(f.getModuleVendor());
                        if (Boolean.FALSE.equals(f.getModuleHasSymbols())) sb.append(", 심볼없음");
                        if (f.getModuleVendor() != null) sb.append(')');
                    } else {
                        sb.append(nz(f.getFunction()));
                        if (f.getAddress() != null) sb.append(' ').append(f.getAddress());
                    }
                    sb.append('\n');
                }
            }
        }

        Map<String, String> regs = r.getRegisters();
        if (regs != null && !regs.isEmpty()) {
            sb.append("\n== 주요 레지스터 ==\n");
            for (String key : new String[]{"rip", "rsp", "rbp", "rax", "rbx", "rsi", "rdi", "pc", "sp", "lr"}) {
                if (regs.containsKey(key)) sb.append(key).append('=').append(regs.get(key)).append('\n');
            }
        }

        // 크래시 지점 디스어셈블리 — 심볼이 없어도 명령어로 크래시 원인 추론 가능
        List<String> disasm = r.getCrashDisassembly();
        if (disasm != null && !disasm.isEmpty()) {
            sb.append("\n== 크래시 지점 디스어셈블리 ($pc) ==\n");
            disasm.stream().limit(16).forEach(l -> sb.append(l).append('\n'));
        }

        // 로드된 모듈(메모리 매핑) — 크래시가 어느 바이너리/라이브러리에서 발생했는지 단서
        List<String> maps = r.getMemoryMappings();
        if (maps != null && !maps.isEmpty()) {
            sb.append("\n== 로드된 모듈(메모리 매핑, 상위 일부) ==\n");
            maps.stream().limit(25).forEach(l -> sb.append(l).append('\n'));
        }

        if (r.getExecutableName() == null) {
            sb.append("\n[주의] 실행 파일이 페어링되지 않은 코어 단독 분석입니다 — 함수명 심볼이 제한적입니다.\n");
        }

        List<GdbThreadInfo> threads = r.getAllThreads();
        if (threads != null && !threads.isEmpty()) {
            long total = threads.size();
            GdbThreadInfo crash = threads.stream().filter(GdbThreadInfo::isCurrent).findFirst().orElse(null);
            sb.append("\n== 스레드 정보 ==\n");
            sb.append("전체 스레드 수: ").append(total).append('\n');
            if (crash != null) {
                sb.append("크래시 스레드: #").append(crash.getId());
                if (crash.getTargetId() != null) sb.append(' ').append(crash.getTargetId());
                sb.append('\n');
            }
        }

        sb.append("\n위 데이터만 근거로 진단하세요. ");
        sb.append("심볼/디버그 정보가 없거나 신뢰도가 LOW 인 경우, 특정 코드 라인이나 함수를 단정하지 마세요. ");
        if ("THIRDPARTY_STRIPPED".equals(r.getGuidanceKind())) {
            sb.append("결함이 서드파티 stripped 라이브러리")
              .append(r.getFaultingModuleVendor() != null ? "(" + r.getFaultingModuleVendor() + ")" : "")
              .append(" 내부이므로 애플리케이션 소스 코드 라인을 단정하지 말고, 해당 벤더의 debuginfo 확보 후 재분석 "
                    + "또는 벤더 측 이슈 에스컬레이션을 recommendations 에 반드시 포함하세요. ");
        }
        sb.append("시그널 의미·레지스터·프로그램 실행 인자·식별된 심볼 범위 내에서만 추론하고, ");
        sb.append("정밀 분석에 필요한 추가 자료(코어 생성 시점의 stripped 되지 않은 동일 실행 파일 페어링, ");
        sb.append("또는 debuginfo 설치 후 재분석)를 recommendations 에 반드시 포함하세요. ");
        sb.append("식별된 심볼 프레임이 전혀 없으면 추측 대신 위 절차적 가이드를 답하세요.\n");
        sb.append("마크다운 코드블록 없이 아래 순수 JSON 한 개만 출력하세요.\n");
        sb.append("{\"summary\":\"한두 문장 핵심 요약\",")
          .append("\"rootCause\":\"근본 원인 상세 설명\",")
          .append("\"recommendations\":\"1. ...\\n2. ...\\n3. ... 형식의 구체적 조치\",")
          .append("\"severity\":\"Critical|High|Medium|Low 중 하나\",")
          .append("\"severityDesc\":\"심각도 판단 근거\"}");
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "(알 수 없음)" : s; }

    // ── SSE 전송 헬퍼 ─────────────────────────────────────────────

    private void sendProgress(SseEmitter emitter, AnalysisProgress progress) {
        if (Thread.currentThread().isInterrupted()) return;
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(objectMapper.writeValueAsString(progress)));
        } catch (Exception e) {
            logger.info("[CoreDump SSE] 클라이언트 연결 끊김, 분석 중단");
            Thread.currentThread().interrupt();
        }
    }
}

package com.heapdump.analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.*;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.repository.CoreDumpAnalysisRepository;
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

    private final HeapDumpConfig config;
    private final CoreDumpAnalysisRepository repository;
    private final ObjectMapper objectMapper;
    private final HeapDumpAnalyzerService heapFacade;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "coredump-analyzer");
        t.setDaemon(false);
        return t;
    });

    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();

    public CoreDumpAnalyzerService(HeapDumpConfig config,
                                   CoreDumpAnalysisRepository repository,
                                   ObjectMapper objectMapper,
                                   HeapDumpAnalyzerService heapFacade) {
        this.config = config;
        this.repository = repository;
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.heapFacade = heapFacade;
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

    // ── 삭제 ──────────────────────────────────────────────────────

    public void deleteDump(String filename) {
        // filename은 호출자(컨트롤러)에서 이미 validateCoreDumpFilename()을 거친 값
        // 파일 삭제
        File dumpFile = new File(dumpFilesDir(), filename);
        File execFile = new File(dumpFilesDir(), filename + ".exec");
        deleteQuietly(dumpFile);
        deleteQuietly(execFile);
        // 데이터 디렉토리 삭제
        deleteDirectoryQuietly(dataDir(filename));
        // DB 플래그
        repository.findByFilename(filename).ifPresent(e -> {
            e.setFileDeleted(true);
            repository.save(e);
        });
        logger.info("[CoreDump] 삭제 완료: {}", filename);
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

            // 2. 실행 파일 확인 (선택)
            File execFile = new File(dumpFilesDir(), filename + ".exec");
            String executableName = execFile.exists() ? filename + ".exec" : null;
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
            if (execFile.exists()) entity.setExecutableSize(execFile.length());
            repository.save(entity);

            // 4. tmp 디렉토리 준비
            sendProgress(emitter, AnalysisProgress.step(filename, 10, "GDB 분석 준비 중..."));
            tmpAnalysisDir.mkdirs();
            File coreCopy = new File(tmpAnalysisDir, filename);
            Files.copy(coreFile.toPath(), coreCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

            File execCopy = null;
            if (execFile.exists()) {
                execCopy = new File(tmpAnalysisDir, filename + ".exec");
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

        if (execPath != null && execPath.exists()) {
            cmd.addAll(Arrays.asList(
                "-ex", "set pagination off",
                "-ex", "set print elements 50",
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
                "-ex", "thread apply all bt full"
            ));
            cmd.add(execPath.getAbsolutePath());
            cmd.add(corePath.getAbsolutePath());
        } else {
            // 실행 파일 없는 경우 — 코어 파일만
            cmd.addAll(Arrays.asList(
                "-ex", "set pagination off",
                "-ex", "core-file " + corePath.getAbsolutePath(),
                "-ex", "echo " + SECTION_PREFIX + "bt===\\n",
                "-ex", "bt",
                "-ex", "echo " + SECTION_PREFIX + "threads===\\n",
                "-ex", "info threads",
                "-ex", "echo " + SECTION_PREFIX + "thread_apply===\\n",
                "-ex", "thread apply all bt"
            ));
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
    private static final Pattern THREAD_LINE_PATTERN =
            Pattern.compile("^\\s*(\\*?)\\s*(\\d+)\\s+(Thread\\s+\\S+(?:\\s+\\(LWP\\s+\\d+\\))?)\\s*(.*)");
    private static final Pattern THREAD_APPLY_HEADER =
            Pattern.compile("^Thread\\s+(\\d+)\\s+\\(");

    private enum Section { NONE, BT, BT_FULL, SHAREDLIB, REGISTERS, THREADS, THREAD_APPLY_BT }

    CoreDumpAnalysisResult parseGdbOutput(String rawOutput, String filename, String executableName) {
        CoreDumpAnalysisResult result = new CoreDumpAnalysisResult();
        result.setFilename(filename);
        result.setExecutableName(executableName);
        result.setGdbRawOutput(rawOutput);
        result.setMainBacktrace(new ArrayList<>());
        result.setAllThreads(new ArrayList<>());
        result.setRegisters(new LinkedHashMap<>());
        result.setSharedLibraries(new ArrayList<>());

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
                // vmcore(Linux 커널 크래시 덤프) 전용 안내
                String userMsg = l;
                String fn = filename.toLowerCase();
                if ((fn.startsWith("vmcore") || fn.equals("vmcore"))
                        && (l.contains("is not a core dump") || l.contains("file format not recognized"))) {
                    userMsg = "vmcore는 Linux 커널 크래시 덤프입니다. GDB로는 분석할 수 없으며, " +
                              "'crash' 유틸리티로 분석하세요. (GDB 원문: " + l + ")";
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

        // 파싱 결과 요약 로그
        logger.info("[CoreDump] GDB 파싱 완료: filename={}, signal={}, frames={}, threads={}{}",
                filename,
                result.getCrashSignal() != null ? result.getCrashSignal() : "없음",
                result.getMainBacktrace().size(),
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
        return bt.stream()
                .limit(5)
                .map(f -> "#" + f.getFrameNumber() + " " +
                        (f.getAddress() != null ? f.getAddress() + " in " : "") +
                        (f.getFunction() != null ? f.getFunction() : "??") +
                        (f.getLocation() != null ? " at " + f.getLocation() : ""))
                .collect(Collectors.joining("\n"));
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
        return heapFacade.isLlmEnabled();
    }

    public String getLlmProvider() {
        return heapFacade.getLlmProvider();
    }

    public void saveAiInsight(String key, Map<String, Object> insightData) {
        heapFacade.saveAiInsight(key, insightData);
    }

    public Map<String, Object> loadAiInsight(String key) {
        return heapFacade.loadAiInsight(key);
    }

    public boolean deleteAiInsight(String key) {
        return heapFacade.deleteAiInsight(key);
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
        return heapFacade.callLlmAnalysis(prompt);
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

        List<GdbStackFrame> bt = r.getMainBacktrace();
        if (bt != null && !bt.isEmpty()) {
            GdbStackFrame f0 = bt.get(0);
            sb.append("\n== 크래시 지점 (Frame #0) ==\n");
            sb.append("함수: ").append(nz(f0.getFunction()));
            if (f0.getArgs() != null && !f0.getArgs().isBlank()) sb.append(" (").append(f0.getArgs()).append(')');
            sb.append('\n');
            if (f0.getLocation() != null) sb.append("위치: ").append(f0.getLocation()).append('\n');
            if (f0.getLibrary() != null)  sb.append("라이브러리: ").append(f0.getLibrary()).append('\n');
            if (f0.getAddress() != null)  sb.append("주소: ").append(f0.getAddress()).append('\n');

            sb.append("\n== 콜 체인 (상위 ").append(Math.min(bt.size(), 12)).append("프레임) ==\n");
            for (int i = 0; i < bt.size() && i < 12; i++) {
                GdbStackFrame f = bt.get(i);
                sb.append('#').append(f.getFrameNumber()).append(' ').append(nz(f.getFunction()));
                if (f.getLocation() != null)      sb.append(" at ").append(f.getLocation());
                else if (f.getLibrary() != null)  sb.append(" from ").append(f.getLibrary());
                sb.append('\n');
            }
        }

        Map<String, String> regs = r.getRegisters();
        if (regs != null && !regs.isEmpty()) {
            sb.append("\n== 주요 레지스터 ==\n");
            for (String key : new String[]{"rip", "rsp", "rbp", "rax", "rbx", "rsi", "rdi", "pc", "sp", "lr"}) {
                if (regs.containsKey(key)) sb.append(key).append('=').append(regs.get(key)).append('\n');
            }
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

        sb.append("\n위 데이터만 근거로 진단하세요. 마크다운 코드블록 없이 아래 순수 JSON 한 개만 출력하세요.\n");
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

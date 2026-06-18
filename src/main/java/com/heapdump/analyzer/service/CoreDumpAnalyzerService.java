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

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "coredump-analyzer");
        t.setDaemon(false);
        return t;
    });

    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();

    public CoreDumpAnalyzerService(HeapDumpConfig config,
                                   CoreDumpAnalysisRepository repository,
                                   ObjectMapper objectMapper) {
        this.config = config;
        this.repository = repository;
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT);
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
        String safe = validateCoreDumpFilename(filename);
        // 파일 삭제
        File dumpFile = new File(dumpFilesDir(), safe);
        File execFile = new File(dumpFilesDir(), safe + ".exec");
        deleteQuietly(dumpFile);
        deleteQuietly(execFile);
        // 데이터 디렉토리 삭제
        deleteDirectoryQuietly(dataDir(safe));
        // DB 플래그
        repository.findByFilename(safe).ifPresent(e -> {
            e.setFileDeleted(true);
            repository.save(e);
        });
        logger.info("[CoreDump] 삭제 완료: {}", safe);
    }

    private void deleteQuietly(File f) {
        try { if (f.exists()) f.delete(); } catch (Exception ignored) {}
    }

    private void deleteDirectoryQuietly(File dir) {
        if (!dir.exists()) return;
        try {
            Files.walk(dir.toPath())
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (Exception ignored) {}
    }

    // ── 분석 실행 (SSE) ───────────────────────────────────────────

    public Future<?> analyzeWithProgress(String filename, SseEmitter emitter) {
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

        Future<?> task = executor.submit(() -> runAnalysis(safe, emitter));
        activeTasks.put(safe, task);
        return task;
    }

    private void runAnalysis(String filename, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        File tmpAnalysisDir = new File(tmpDir(), filename);

        try {
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

            // 3. DB 레코드 생성/갱신
            CoreDumpAnalysisEntity entity = repository.findByFilename(filename)
                    .orElseGet(() -> {
                        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
                        e.setFilename(filename);
                        e.setFileSize(coreFile.length());
                        e.setFileDeleted(false);
                        return e;
                    });
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

            // 7. result.json 저장
            sendProgress(emitter, AnalysisProgress.step(filename, 92, "분석 결과 저장 중..."));
            File dataDirFile = dataDir(filename);
            dataDirFile.mkdirs();
            objectMapper.writeValue(resultJsonFile(filename), result);

            // GDB raw output 별도 저장
            File rawFile = new File(dataDirFile, "gdb_output.txt");
            Files.writeString(rawFile.toPath(), rawOutput, StandardCharsets.UTF_8);

            // 8. DB 갱신
            entity.setStatus(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty()
                    ? "ERROR" : "SUCCESS");
            entity.setCrashSignal(result.getCrashSignal());
            entity.setSignalDescription(result.getSignalDescription());
            entity.setCrashSummary(buildCrashSummary(result));
            entity.setAnalysisTimeMs(result.getAnalysisTimeMs());
            entity.setAnalyzedAt(LocalDateTime.now());
            if (result.getErrorMessage() != null) entity.setErrorMessage(result.getErrorMessage());
            repository.save(entity);

            // 9. 완료
            String resultUrl = "/core-dump/analyze/" + filename;
            sendProgress(emitter, AnalysisProgress.completed(filename, resultUrl));
            emitter.complete();

            logger.info("[CoreDump] 분석 완료: {} ({}ms)", filename, result.getAnalysisTimeMs());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("[CoreDump] 분석 취소됨: {}", filename);
            updateDbError(filename, "분석이 취소되었습니다");
            try { emitter.complete(); } catch (Exception ignored) {}

        } catch (Exception e) {
            logger.error("[CoreDump] 분석 중 오류: {}", filename, e);
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
            throw new RuntimeException("GDB 분석 시간 초과 (" + config.getCoreDumpTimeoutMinutes() + "분)");
        }
        reader.join(5000);

        int exitCode = process.exitValue();
        logger.info("[CoreDump] GDB 종료 코드: {}", exitCode);

        return rawOutput.toString();
    }

    private List<String> buildGdbCommand(File corePath, File execPath) {
        String gdb = config.getGdbCliPath();
        List<String> cmd = new ArrayList<>();
        cmd.add(gdb);
        cmd.add("--batch");
        cmd.add("--nx");

        if (execPath != null && execPath.exists()) {
            cmd.addAll(Arrays.asList(
                "-ex", "set pagination off",
                "-ex", "set print limit 0",
                "-ex", "set print elements 50",
                "-ex", "info sharedlibrary",
                "-ex", "info registers",
                "-ex", "bt",
                "-ex", "bt full",
                "-ex", "info threads",
                "-ex", "thread apply all bt full"
            ));
            cmd.add(execPath.getAbsolutePath());
            cmd.add(corePath.getAbsolutePath());
        } else {
            // 실행 파일 없는 경우 — 코어 파일만
            cmd.addAll(Arrays.asList(
                "-ex", "set pagination off",
                "-ex", "core-file " + corePath.getAbsolutePath(),
                "-ex", "bt",
                "-ex", "info threads",
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
            Pattern.compile("^(0x[0-9a-fA-F]+)\\s+(0x[0-9a-fA-F]+)\\s+(Yes|No|Yes \\(\\*\\))\\s+(\\S+)");
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
            result.setErrorMessage("GDB 출력이 없습니다.");
            return result;
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

            // 섹션 전환 감지 (GDB 프롬프트 행)
            if (line.startsWith("(gdb) ")) {
                String cmd = line.substring(6).trim();
                // 현재 섹션 결과 저장
                if (currentSection == Section.BT || currentSection == Section.BT_FULL) {
                    if (result.getMainBacktrace().isEmpty()) {
                        result.setMainBacktrace(new ArrayList<>(currentBt));
                    }
                }
                if (currentSection == Section.THREAD_APPLY_BT && currentThread != null) {
                    currentThread.setBacktrace(new ArrayList<>(currentBt));
                    threadMap.put(currentThread.getId(), currentThread);
                }

                currentBt = new ArrayList<>();
                currentFrameLocals = null;

                if (cmd.startsWith("info sharedlibrary")) {
                    currentSection = Section.SHAREDLIB;
                } else if (cmd.startsWith("info registers")) {
                    currentSection = Section.REGISTERS;
                } else if (cmd.equals("bt") || cmd.equals("backtrace")) {
                    currentSection = Section.BT;
                } else if (cmd.equals("bt full") || cmd.equals("backtrace full")) {
                    currentSection = Section.BT_FULL;
                } else if (cmd.startsWith("info threads")) {
                    currentSection = Section.THREADS;
                } else if (cmd.startsWith("thread apply all")) {
                    currentSection = Section.THREAD_APPLY_BT;
                    currentThread = null;
                } else {
                    currentSection = Section.NONE;
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
                            && !line.startsWith("(gdb)")) {
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

        // crashSummary는 엔티티 저장 시 별도 처리
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

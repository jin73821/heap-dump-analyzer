package com.heapdump.analyzer.service;

import com.heapdump.analyzer.repository.RagLearningDocRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chroma 색인기를 화면에서 실행한다 — 기존 {@code /opt/chroma/app/run-index.sh} 를 자식 프로세스로 띄운다.
 *
 * <p><b>왜 앱이 Chroma 에 직접 쓰지 않는가</b> — 청킹(350/60/150)·e5 접두사({@code passage: }/{@code query: })·
 * 마스킹이 전부 파이썬 한 곳에 갇혀 있다. Java 로 옮기면 두 구현이 갈라지고, 접두사가 어긋나면
 * <b>에러 없이 검색 품질만 무너진다</b>(함정 41/43). 그래서 여기서는 스크립트를 그대로 실행만 한다.
 *
 * <p>구조는 {@code HeapDumpAnalyzerService.runMatCliWithProgress} 를 본떴다:
 * <ul>
 *   <li>⚠ <b>인자 배열</b>로만 실행한다 — 셸 문자열 조립 금지(앱이 root 라 주입 피해가 무제한)</li>
 *   <li>⚠ 출력 리더는 <b>전용 daemon 스레드</b>(함정 9) — 요청 스레드·분석 풀을 점유하면 안 된다</li>
 *   <li>⚠ 종료는 <b>자식부터</b> — {@code bash → python} 이라 부모만 죽이면 파이썬이 살아남는다</li>
 *   <li>⚠ 전용 단일 스레드 executor — 분석 풀을 빌리면 거부 시 Tomcat 스레드에서 돌아버린다</li>
 * </ul>
 */
@Service
public class RagIndexRunner {

    private static final Logger logger = LoggerFactory.getLogger(RagIndexRunner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 허용 소스 — <b>화이트리스트</b>. 사용자 입력을 그대로 인자로 넘기지 않는다.
     * {@code user_docs} 는 지식, {@code csv} 는 학습, {@code all} 은 전체.
     */
    public static final Set<String> ALLOWED_SOURCES = Set.of("csv", "user_docs", "csv,user_docs", "all");

    /** 진행 라인 — 색인기가 비-TTY 에서 개행으로 찍어 준다. */
    static final Pattern PROGRESS = Pattern.compile("색인\\s+(\\d+)/(\\d+)");
    /** 완료 라인 — {@code 완료: 84건 색인 → 컬렉션 총 834건} */
    static final Pattern DONE = Pattern.compile("완료:\\s*(\\d+)건 색인.*?총\\s*(\\d+)건");

    /** 상태 객체에 보관할 로그 줄 수. 전체는 파일로 남긴다. */
    static final int TAIL_MAX = 200;

    private final RagLearningDocRepository learningRepo;
    private final String scriptPath;
    private final String workDir;
    private final int timeoutMinutes;
    private final File logDir;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Process current;
    private final ConcurrentLinkedDeque<String> tail = new ConcurrentLinkedDeque<>();

    // 상태 (volatile 단순 필드 — DomRefPrecomputeStatus 와 같은 방식)
    private volatile String state = "none";   // none | queued | running | done | failed | cancelled | timeout
    private volatile String sources = "";
    private volatile boolean reset;
    private volatile int done, total;
    private volatile Integer exitCode;
    private volatile String message;
    private volatile String logFile;
    private volatile long startedAt, finishedAt;
    private volatile String startedBy;

    /** 색인은 전역 작업이라 전용 단일 스레드로 충분하다. 분석 풀과 분리(위 클래스 주석). */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-index-runner");
        t.setDaemon(true);
        return t;
    });

    public RagIndexRunner(RagLearningDocRepository learningRepo,
                          @Value("${rag.index.script:/opt/chroma/app/run-index.sh}") String scriptPath,
                          @Value("${rag.index.workdir:/opt/chroma}") String workDir,
                          @Value("${rag.index.timeout-minutes:15}") int timeoutMinutes,
                          @Value("${logging.dir:/opt/genspark/webapp_dump/logs}") String logDirPath) {
        this.learningRepo = learningRepo;
        this.scriptPath = scriptPath;
        this.workDir = workDir;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
        this.logDir = new File(logDirPath);
    }

    // ── 순수 로직 (테스트 경계) ───────────────────────────

    /** 실행 명령 조립. ⚠ 인자 배열 — 셸 문자열로 만들지 말 것. */
    static List<String> buildCommand(String script, String sources, boolean reset) {
        if (!ALLOWED_SOURCES.contains(sources)) {
            throw new IllegalArgumentException("허용되지 않은 색인 대상입니다: " + sources);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(script);
        cmd.add("--sources");
        cmd.add(sources);
        if (reset) cmd.add("--reset");
        return cmd;
    }

    /** 진행 라인 파싱 → {done, total}. 해당 없으면 null. */
    static int[] parseProgress(String line) {
        if (line == null) return null;
        Matcher m = PROGRESS.matcher(line);
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** 완료 라인 파싱 → {색인건수, 컬렉션총건수}. 해당 없으면 null. */
    static int[] parseDone(String line) {
        if (line == null) return null;
        Matcher m = DONE.matcher(line);
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    // ── 실행 ────────────────────────────────────────────

    /** 이미 실행 중이면 false. 호출부가 409 로 돌려준다. */
    public boolean isRunning() { return running.get(); }

    /**
     * 색인 시작. 실행 중이면 {@link IllegalStateException}.
     *
     * @param who 감사 로그용 사용자명 — ⚠ 요청 스레드에서 캡처해 넘긴다(워커에서 SecurityContextHolder 금지, 함정 31)
     */
    public synchronized void start(String sourcesArg, boolean resetFlag, String who) {
        if (!ALLOWED_SOURCES.contains(sourcesArg)) {
            throw new IllegalArgumentException("허용되지 않은 색인 대상입니다: " + sourcesArg);
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("색인이 이미 실행 중입니다");
        }
        // 상태는 제출 즉시 queued 로 — executor 대기 구간도 화면에 진행 중으로 보여야 한다.
        tail.clear();
        this.state = "queued";
        this.sources = sourcesArg;
        this.reset = resetFlag;
        this.done = 0;
        this.total = 0;
        this.exitCode = null;
        this.message = null;
        this.startedAt = System.currentTimeMillis();
        this.finishedAt = 0L;
        this.startedBy = who;
        this.logFile = new File(logDir, "rag-index-" + TS.format(LocalDateTime.now()) + ".log").getAbsolutePath();

        logger.info("[RagIndex] action=run sources={} reset={} by={}", sourcesArg, resetFlag, who);
        executor.submit(() -> execute(sourcesArg, resetFlag, who));
    }

    /** 실행 중인 색인을 취소한다. */
    public synchronized boolean cancel(String who) {
        Process p = current;
        if (!running.get() || p == null) return false;
        logger.info("[RagIndex] action=cancel by={}", who);
        killTree(p, "cancel");
        this.state = "cancelled";
        this.message = "사용자가 취소했습니다";
        return true;
    }

    private void execute(String sourcesArg, boolean resetFlag, String who) {
        long t0 = System.currentTimeMillis();
        Process proc = null;
        Thread reader = null;
        try {
            this.state = "running";
            List<String> cmd = buildCommand(scriptPath, sourcesArg, resetFlag);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            // ⚠ 색인기가 어느 원천을 볼지 앱이 결정한다 — 테이블이 비어 있으면 파일 원천을 쓰게 해
            //   빈 DB 로 색인해 기존 170청크를 낡게 만드는 사고를 구조적으로 막는다.
            String csvSource = learningCount() > 0 ? "db" : "file";
            pb.environment().put("RAG_CSV_SOURCE", csvSource);
            // ⚠ run-index.sh → heap_dec.sh 가 맨 `java` 를 부른다. 앱 프로세스의 PATH 가
            //   대화형 셸과 다를 수 있어 JAVA_HOME/bin 을 앞에 붙여 준다.
            String javaHome = System.getProperty("java.home", "");
            String path = pb.environment().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
            if (!javaHome.isEmpty()) path = javaHome + "/bin:" + path;
            pb.environment().put("PATH", path);

            proc = pb.start();
            current = proc;
            final Process p = proc;
            final List<String> all = Collections.synchronizedList(new ArrayList<>());

            // 출력 리더 — 전용 daemon 스레드 (함정 9)
            reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        all.add(line);
                        pushTail(line);
                        int[] pr = parseProgress(line);
                        if (pr != null) { this.done = pr[0]; this.total = pr[1]; }
                        int[] dn = parseDone(line);
                        if (dn != null) this.message = "색인 " + dn[0] + "건 → 컬렉션 총 " + dn[1] + "건";
                    }
                } catch (Exception e) {
                    logger.warn("[RagIndex] 출력 읽기 오류: {}", e.toString());
                }
            }, "rag-index-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            reader.join(5000);

            if (!finished) {
                killTree(proc, "timeout");
                this.state = "timeout";
                this.message = "색인이 " + timeoutMinutes + "분 제한을 초과해 중단됐습니다";
                logger.error("[RagIndex] action=timeout sources={} minutes={}", sourcesArg, timeoutMinutes);
            } else {
                int code = proc.exitValue();
                this.exitCode = code;
                if ("cancelled".equals(this.state)) {
                    // cancel() 이 이미 상태를 확정했다 — 덮어쓰지 않는다.
                } else if (code == 0) {
                    this.state = "done";
                    if (this.message == null) this.message = "색인 완료";
                } else {
                    this.state = "failed";
                    this.message = "색인기가 오류 코드 " + code + " 로 종료했습니다"
                            + (resetFlag ? " — --reset 도중 실패라 컬렉션이 비었을 수 있습니다. 재실행하세요." : "");
                }
            }
            writeLog(all);
            logger.info("[RagIndex] action=run-done sources={} state={} exit={} elapsedMs={} by={}",
                    sourcesArg, state, exitCode, System.currentTimeMillis() - t0, who);
        } catch (Exception e) {
            this.state = "failed";
            this.message = "색인 실행 실패: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            logger.error("[RagIndex] action=run-error sources={} — {}", sourcesArg, e.toString());
        } finally {
            // ⚠ 정리 실패가 실행 플래그 반납을 막으면 안 된다 — 이후 모든 실행이 영구히 409 가 된다.
            try {
                if (proc != null && proc.isAlive()) killTree(proc, "finally");
            } catch (Throwable t) {
                logger.error("[RagIndex] 방어 종료 중 오류(무시하고 플래그 반납): {}", t.toString());
            } finally {
                current = null;
                finishedAt = System.currentTimeMillis();
                running.set(false);
            }
        }
    }

    /** 자식부터 종료 — {@code bash → python} 이라 부모만 죽이면 파이썬이 살아남는다. */
    private void killTree(Process p, String ctx) {
        try {
            p.descendants().forEach(h -> {
                try { h.destroyForcibly(); } catch (Exception ignore) { /* 개별 실패는 무시하고 계속 */ }
            });
        } catch (Exception e) {
            logger.warn("[RagIndex] 프로세스 트리 열거 실패 ({}): {}", ctx, e.toString());
        }
        try { p.destroyForcibly(); }
        catch (Exception e) { logger.error("[RagIndex] 부모 종료 실패 ({}): {}", ctx, e.toString()); }
    }

    private long learningCount() {
        try { return learningRepo == null ? 0 : learningRepo.count(); }
        catch (Exception e) { logger.warn("[RagIndex] 학습 행 수 조회 실패 — 파일 원천으로 진행: {}", e.toString()); return 0; }
    }

    private void pushTail(String line) {
        tail.addLast(line);
        while (tail.size() > TAIL_MAX) tail.pollFirst();
    }

    private void writeLog(List<String> lines) {
        try {
            if (!logDir.exists() && !logDir.mkdirs()) return;
            Files.write(Path.of(logFile), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("[RagIndex] 로그 파일 기록 실패: {}", e.toString());
        }
    }

    // ── 상태 ────────────────────────────────────────────

    /** 화면 폴링용 스냅샷. 진행 중이면 현재까지, 끝났으면 총 소요를 서버가 계산해 준다. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("running", running.get());
        m.put("sources", sources);
        m.put("reset", reset);
        m.put("done", done);
        m.put("total", total);
        m.put("exitCode", exitCode);
        m.put("message", message);
        m.put("startedBy", startedBy);
        // epoch ms 그대로 — DomRefPrecomputeStatus 와 같은 방식(표시 포맷은 화면 몫)
        m.put("startedAt", startedAt);
        m.put("finishedAt", finishedAt);
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        m.put("elapsedMs", startedAt > 0 ? end - startedAt : 0L);
        m.put("logFile", logFile);
        m.put("tail", new ArrayList<>(tail));
        return m;
    }
}

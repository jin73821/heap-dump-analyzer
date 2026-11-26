package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpException;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpLimits;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpResultCodec;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.util.FilenameValidator;
import com.heapdump.analyzer.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 힙 덤프의 스레드 정보로 하는 스레드 분석(2.7.1) — 스레드 덤프 없이도 "힙 덤프를 만든 순간 스레드들이 무엇을 하고 있었나" 를 본다.
 *
 * <p>입력은 힙 분석이 이미 남긴 MAT 산출물이다: 결과 디렉토리의 {@code <base>.threads}(스택) + Overview/Leak Suspects ZIP 의
 * Thread Overview 페이지(이름·상태·힙 점유). 그래서 <b>이미 분석된 힙 덤프에도 재분석 없이</b> 적용되고, 결과는 DB 에 두지 않고
 * 그때그때 만든다(수백 개 스레드면 수십 ms) — 산출물 파일의 크기·수정 시각이 바뀌면 캐시를 버린다.
 *
 * <p>분석은 {@link HeapThreadExtractor} 가 스레드 덤프 텍스트로 바꾼 뒤 스레드 덤프 분석기로 한다(규칙 한 벌). 힙 덤프에 없는 것
 * (스냅샷 비교·CPU·synchronized 락 관계)은 판정하지 않고 {@code HEAP_DERIVED} 소견으로 알린다.
 *
 * <p>다른 분석 서비스를 주입하지 않는다(힙 덤프 서비스만) — {@code ThreadDumpAnalyzerService} 가 이 서비스를 쓴다.
 */
@Service
public class HeapThreadService {

    private static final Logger logger = LoggerFactory.getLogger(HeapThreadService.class);
    private static final int CACHE = 4;
    private static final int MAX_FILENAME_LEN = 480;   // ai_insights.filename(500) − "__heapthreads__:" 접두 여유

    private final HeapDumpAnalyzerService heap;
    private final AnalysisHistoryRepository historyRepository;
    private final HeapDumpConfig config;
    private final LlmConfigService llmConfig;
    private final AiInsightManager aiInsight;

    private final Map<String, Loaded> cache = Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Loaded> e) { return size() > CACHE; }
    });

    /**
     * 힙 덤프 한 건의 스레드 분석.
     *
     * @param byName 스레드 이름 → 힙 스레드(이름이 유일한 것만 — 스레드 덤프와 이름으로 이을 때 쓴다)
     */
    public record Loaded(String heapFilename, ThreadDumpResult result, String json, AnalysisHistoryEntity entity,
                         Map<String, HeapThreadExtractor.HeapThread> byName, int duplicateNames, String signature) {}

    public HeapThreadService(HeapDumpAnalyzerService heap, AnalysisHistoryRepository historyRepository, HeapDumpConfig config,
                             LlmConfigService llmConfig, AiInsightManager aiInsight) {
        this.heap = heap;
        this.historyRepository = historyRepository;
        this.config = config;
        this.llmConfig = llmConfig;
        this.aiInsight = aiInsight;
    }

    public static String insightKey(String heapFilename) { return "__heapthreads__:" + heapFilename; }

    public String validateFilename(String filename) {
        String safe = FilenameValidator.validateSafe(filename);
        if (safe.length() > MAX_FILENAME_LEN) throw new IllegalArgumentException("파일명이 너무 깁니다(최대 " + MAX_FILENAME_LEN + "자)");
        return safe;
    }

    public Optional<AnalysisHistoryEntity> entity(String heapFilename) { return heap.findHistoryEntity(heapFilename); }

    /** 분석이 끝난 힙 덤프(최근 분석 순) — 스레드 덤프 연결 후보 목록. */
    public List<AnalysisHistoryEntity> analyzedHeaps() {
        List<AnalysisHistoryEntity> out = new ArrayList<>();
        for (AnalysisHistoryEntity e : historyRepository.findAllByOrderByAnalyzedAtDesc()) {
            if ("SUCCESS".equals(e.getStatus())) out.add(e);
        }
        return out;
    }

    /**
     * 스레드 분석을 만든다(캐시). 실패는 코드가 붙은 {@link ThreadDumpException} 이다:
     * {@code HEAP_NOT_FOUND}(기록 없음) · {@code HEAP_NOT_ANALYZED}(분석 완료 전) · {@code HEAP_THREADS_UNAVAILABLE}(산출물 없음) ·
     * {@code IO_ERROR} · {@code INTERNAL}.
     */
    public Loaded load(String heapFilename) {
        String safe = validateFilename(heapFilename);
        AnalysisHistoryEntity e = heap.findHistoryEntity(safe)
                .orElseThrow(() -> new ThreadDumpException("HEAP_NOT_FOUND", "힙 덤프 분석 기록이 없습니다: " + safe));
        if (!"SUCCESS".equals(e.getStatus())) {
            throw new ThreadDumpException("HEAP_NOT_ANALYZED", "힙 덤프 분석이 끝나지 않았습니다(상태 " + e.getStatus() + "). 분석이 끝난 뒤 다시 보세요.");
        }
        File threads = threadsFile(safe);
        File zip = overviewZip(safe);
        String sig = sig(threads) + "|" + sig(zip) + "|" + e.getDumpCreationTime();
        Loaded hit = cache.get(safe);
        if (hit != null && hit.signature().equals(sig)) return hit;
        long t0 = System.currentTimeMillis();
        try {
            String overview = zip == null ? null : threadOverviewPage(zip);
            String stacks = threads == null ? null : Files.readString(threads.toPath(), StandardCharsets.UTF_8);
            HeapThreadExtractor.Extract x = HeapThreadExtractor.extract(overview, stacks, e.getDumpCreationTime());
            ThreadDumpLimits limits = new ThreadDumpLimits(config.getThreadDumpMaxFileBytes(), config.getThreadDumpMaxLineChars(),
                    config.getThreadDumpMaxThreadsPerSnapshot(), 1);
            ThreadDumpResult r = HeapThreadExtractor.analyze(x, limits, safe);
            String json = ThreadDumpResultCodec.toJsonCapped(r);
            Map<String, HeapThreadExtractor.HeapThread> byName = new HashMap<>();
            Map<String, Integer> nameCount = new HashMap<>();
            for (HeapThreadExtractor.HeapThread t : x.threads()) nameCount.merge(t.name(), 1, Integer::sum);
            int dup = 0;
            for (HeapThreadExtractor.HeapThread t : x.threads()) {
                if (nameCount.get(t.name()) == 1) byName.put(t.name(), t);
                else dup++;
            }
            Loaded l = new Loaded(safe, r, json, e, byName, dup, sig);
            cache.put(safe, l);
            logger.info("[HeapThreads] action=extract file={} threads={} overview={} stacks={} findings={} elapsed={}ms",
                    safe, x.threads().size(), overview != null, stacks != null, r.getFindings().size(), System.currentTimeMillis() - t0);
            return l;
        } catch (ThreadDumpException ex) {
            logger.warn("[HeapThreads] action=extract result=FAIL file={} code={} — {}", safe, ex.code(), ex.getMessage());
            throw ex;
        } catch (IOException ex) {
            logger.error("[HeapThreads] action=extract result=FAIL file={} code=IO_ERROR — {}", safe, ex.getMessage(), ex);
            throw new ThreadDumpException("IO_ERROR", "힙 덤프 산출물을 읽지 못했습니다 (" + ex.getMessage() + ")");
        } catch (RuntimeException ex) {
            logger.error("[HeapThreads] action=extract result=FAIL file={} code=INTERNAL — {}", safe, ex.toString(), ex);
            throw new ThreadDumpException("INTERNAL", "스레드 정보를 만들지 못했습니다 (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
        }
    }

    /** 힙 결과 화면 카드용 요약 — 실패해도 던지지 않고 {@code available=false} + 코드·사유로 돌려준다. */
    public Map<String, Object> summary(String heapFilename) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Loaded l = load(heapFilename);
            ThreadDumpResult r = l.result();
            ThreadDumpResult.Snapshot s = r.getSnapshots().get(0);
            m.put("available", true);
            m.put("severity", r.getKpi().getSeverity());
            m.put("threads", s.getThreads());
            m.put("daemon", s.getDaemon());
            m.put("states", s.getStates());
            List<Map<String, Object>> findings = new ArrayList<>();
            for (ThreadDumpResult.Finding f : r.getFindings()) {
                if ("Info".equals(f.getSeverity())) continue;
                if (findings.size() >= 5) break;
                findings.add(Map.of("code", f.getCode(), "severity", f.getSeverity(), "title", f.getTitle()));
            }
            m.put("findings", findings);
            List<Map<String, Object>> pools = new ArrayList<>();
            List<ThreadDumpResult.Pool> ps = new ArrayList<>(r.getPools());
            ps.sort(Comparator.comparingInt((ThreadDumpResult.Pool p) -> -p.getSizes().get(0)));
            for (ThreadDumpResult.Pool p : ps) {
                if (pools.size() >= 4) break;
                pools.add(Map.of("stem", p.getStem(), "kind", p.getKind(), "size", p.getSizes().get(0), "idle", p.getIdle().get(0), "active", p.getActive().get(0)));
            }
            m.put("pools", pools);
            m.put("topMemory", topMemory(r, 5));
            m.put("groups", topGroups(r, 3));
        } catch (ThreadDumpException ex) {
            m.put("available", false);
            m.put("code", ex.code());
            m.put("error", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            m.put("available", false);
            m.put("code", "BAD_REQUEST");
            m.put("error", ex.getMessage());
        }
        return m;
    }

    /** 힙 점유가 큰 스레드 — 이름·상태·점유·맨 위 프레임. */
    static List<Map<String, Object>> topMemory(ThreadDumpResult r, int max) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < r.getTracks().size(); i++) if (r.getTracks().get(i).getHr() != null) idx.add(i);
        idx.sort(Comparator.comparingLong((Integer i) -> -r.getTracks().get(i).getHr()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i : idx) {
            if (out.size() >= max) break;
            ThreadDumpResult.Track t = r.getTracks().get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", i);
            row.put("name", r.getNames().get(t.getN()));
            row.put("state", stateName(t.getS().isEmpty() ? '-' : t.getS().charAt(0)));
            row.put("retained", t.getHr());
            row.put("retainedText", FormatUtils.formatBytes(t.getHr()));
            row.put("locals", t.getHl());
            row.put("top", topFrame(r, t.getK().isEmpty() ? -1 : t.getK().get(0)));
            out.add(row);
        }
        return out;
    }

    static List<Map<String, Object>> topGroups(ThreadDumpResult r, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ThreadDumpResult.StackGroup g : r.getStackGroups()) {
            if (out.size() >= max || g.getCount() < 2) break;
            out.add(Map.of("count", g.getCount(), "state", g.getState(), "top", appFrame(r, g.getStackId())));
        }
        return out;
    }

    // ── AI ──────────────────────────────────────────────────────

    static final String SYSTEM_PROMPT = "당신은 JVM 스레드 분석 전문가입니다. 주어진 데이터는 스레드 덤프가 아니라 힙 덤프를 만든 한 시점에 남은 스레드 정보"
            + "(상태·스택·스레드별 힙 점유)입니다. 스냅샷 비교·CPU 시간·synchronized 락의 소유/대기 관계는 없으므로 '멈춰 있다', '교착이다' 라고 단정하지 말고 "
            + "가능성으로만 말하세요. 힙 덤프가 OutOfMemoryError 때 만들어졌다면 그 순간 실행 중이던 작업과 힙 점유가 큰 스레드를 우선 살피세요. "
            + "수치와 스레드·클래스 이름은 주어진 값만 인용하세요. recommendations 는 3개까지, codeAdvice 에는 스택에서 보이는 클래스·메서드를 짚으세요. "
            + "severity 는 Critical|High|Medium|Low 중 하나만 쓰세요. 반드시 마크다운 없이 순수 JSON 만 출력하세요.";

    public boolean isLlmEnabled() { return llmConfig.isLlmEnabled(); }
    public void saveAiInsight(String key, Map<String, Object> data) { aiInsight.saveAiInsight(key, data); }
    public Map<String, Object> loadAiInsight(String key) { return aiInsight.loadAiInsight(key); }
    public boolean deleteAiInsight(String key) { return aiInsight.deleteAiInsight(key); }

    public Map<String, Object> analyzeWithAi(String heapFilename) {
        Loaded l;
        try {
            l = load(heapFilename);
        } catch (ThreadDumpException ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("errorCode", ex.code());
            err.put("error", ex.getMessage());
            return err;
        }
        String prompt = ThreadDumpPromptBuilder.build(l.result(), l.heapFilename(), l.entity().getServerName(), heapContextSection(l));
        Map<String, Object> res = llmConfig.callLlmAnalysis(prompt, SYSTEM_PROMPT);
        if (res != null && Boolean.TRUE.equals(res.get("success")) && res.get("data") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) res.get("data");
            res.put("data", ThreadDumpAiResponseNormalizer.normalize(data));
        }
        return res;
    }

    /** 스레드 AI 프롬프트에 붙이는 힙 쪽 맥락 — 힙 크기·의심 건수·힙 점유 상위 스레드. */
    static String heapContextSection(Loaded l) {
        StringBuilder sb = new StringBuilder("\n== 힙 덤프 맥락 ==\n");
        AnalysisHistoryEntity e = l.entity();
        sb.append("힙 덤프: ").append(l.heapFilename());
        if (e.getDumpCreationTime() != null) sb.append(" (생성 ").append(e.getDumpCreationTime()).append(')');
        sb.append('\n');
        if (e.getUsedHeapSize() != null) {
            sb.append("사용 힙: ").append(FormatUtils.formatBytes(e.getUsedHeapSize()));
            if (e.getTotalHeapSize() != null) sb.append(" / ").append(FormatUtils.formatBytes(e.getTotalHeapSize()));
            sb.append('\n');
        }
        if (e.getSuspectCount() != null) sb.append("Leak Suspects: ").append(e.getSuspectCount()).append("건\n");
        List<Map<String, Object>> top = topMemory(l.result(), 5);
        if (!top.isEmpty()) {
            sb.append("힙 점유가 큰 스레드:\n");
            for (Map<String, Object> t : top) {
                sb.append("  \"").append(t.get("name")).append("\" ").append(t.get("retainedText")).append(" [").append(t.get("state")).append("] ")
                        .append(t.get("top")).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 힙 덤프 AI 프롬프트에 끼우는 스레드 요약(≤900자) — 상태 분포·경고 소견·힙 점유 상위. 스레드 정보가 없으면 빈 문자열.
     * 힙 AI 는 이 요약 덕분에 "OOM 순간 무엇이 돌고 있었나" 를 함께 본다.
     */
    public String promptSectionForHeapAi(String heapFilename) {
        try {
            Loaded l = load(heapFilename);
            ThreadDumpResult r = l.result();
            ThreadDumpResult.Snapshot s = r.getSnapshots().get(0);
            StringBuilder sb = new StringBuilder("== 스레드 상태 (힙 덤프 시점) ==\n");
            sb.append("스레드 ").append(s.getThreads()).append("개 ").append(s.getStates()).append('\n');
            for (ThreadDumpResult.Finding f : r.getFindings()) {
                if ("Info".equals(f.getSeverity())) continue;
                sb.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append('\n');
            }
            for (Map<String, Object> g : topGroups(r, 2)) {
                sb.append("같은 스택 ").append(g.get("count")).append("개 [").append(g.get("state")).append("] ").append(g.get("top")).append('\n');
            }
            for (Map<String, Object> t : topMemory(r, 3)) {
                sb.append("힙 점유 ").append(t.get("retainedText")).append(" \"").append(t.get("name")).append("\" ").append(t.get("top")).append('\n');
            }
            String out = sb.toString();
            return out.length() > 900 ? out.substring(0, 900) + "…\n" : out;
        } catch (ThreadDumpException | IllegalArgumentException ex) {
            logger.debug("[HeapThreads] 힙 AI 프롬프트용 스레드 요약 생략 file={} — {}", heapFilename, ex.getMessage());
            return "";
        }
    }

    // ── 파일 ────────────────────────────────────────────────────

    File threadsFile(String safe) {
        String base = heap.stripExtensionPublic(safe);
        File f = new File(heap.resultDirectoryPublic(safe), base + ".threads");
        if (f.isFile()) return f;
        File g = new File(config.getHeapDumpDirectory(), base + ".threads");
        return g.isFile() ? g : null;
    }

    /** Thread Overview 페이지가 든 ZIP — System Overview 우선, 없으면 Leak Suspects. */
    File overviewZip(String safe) {
        for (String type : new String[]{"overview", "suspects"}) {
            File z = heap.findReportZip(safe, type);
            if (z != null && z.isFile() && hasThreadOverview(z)) return z;
        }
        return null;
    }

    private static boolean hasThreadOverview(File zip) {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry en;
            while ((en = zis.getNextEntry()) != null) {
                String n = en.getName().toLowerCase(Locale.ROOT);
                if (n.contains("thread_overview") && (n.endsWith(".html") || n.endsWith(".htm"))) return true;
            }
        } catch (IOException e) {
            logger.debug("[HeapThreads] ZIP 목록 읽기 실패 {} — {}", zip.getName(), e.getMessage());
        }
        return false;
    }

    static String threadOverviewPage(File zip) throws IOException {
        String best = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry en;
            while ((en = zis.getNextEntry()) != null) {
                String n = en.getName().toLowerCase(Locale.ROOT);
                if (!n.contains("thread_overview") || !(n.endsWith(".html") || n.endsWith(".htm"))) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                String html = bos.toString(StandardCharsets.UTF_8);
                if (n.startsWith("pages/")) return html;
                if (best == null) best = html;
            }
        }
        return best;
    }

    private static String sig(File f) { return f == null ? "-" : f.getName() + ":" + f.length() + ":" + f.lastModified(); }

    // ── 표시 헬퍼 ───────────────────────────────────────────────

    static String stateName(char c) {
        return switch (c) {
            case ThreadDumpResult.ST_RUNNABLE -> "RUNNABLE";
            case ThreadDumpResult.ST_BLOCKED -> "BLOCKED";
            case ThreadDumpResult.ST_WAITING -> "WAITING";
            case ThreadDumpResult.ST_TIMED -> "TIMED_WAITING";
            default -> "UNKNOWN";
        };
    }

    static String topFrame(ThreadDumpResult r, Integer sid) {
        if (sid == null || sid < 0 || sid >= r.getStacks().size() || r.getStacks().get(sid).getF().isEmpty()) return "-";
        return r.getFrames().get(r.getStacks().get(sid).getF().get(0));
    }

    /** 스택에서 JDK·WAS 내부가 아닌 첫 프레임(없으면 맨 위). 묶음이 "어디서" 모였는지 보여 준다. */
    static String appFrame(ThreadDumpResult r, int sid) {
        if (sid < 0 || sid >= r.getStacks().size()) return "-";
        for (int fi : r.getStacks().get(sid).getF()) {
            String f = r.getFrames().get(fi);
            if (!f.matches("^(java|javax|jakarta|jdk|sun|com\\.sun|jeus\\.util|org\\.apache\\.tomcat|weblogic\\.work)\\..*")) return f;
        }
        return topFrame(r, sid);
    }
}

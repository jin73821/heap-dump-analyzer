package com.heapdump.analyzer;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpEngine;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpLimits;
import com.heapdump.analyzer.parser.threaddump.ThreadDumpResultCodec;
import com.heapdump.analyzer.service.PdfReportService;
import com.heapdump.analyzer.service.ThreadDumpPdfReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스레드 덤프 화면·리포트 템플릿 렌더 스모크 + 화면 규약(2026-11-26).
 *
 * <p>템플릿은 실제 엔진으로 렌더한다 — Thymeleaf 표현식 오류는 운영에서 chunked 응답이 중간에 끊겨 빈 화면으로 나타나므로
 * (함정 23) 배포 전에 여기서 잡는다. 인쇄 템플릿은 PDF 바이트(%PDF)까지 만들어 XHTML 정합성·폰트 로드를 함께 본다.
 */
class ThreadDumpTemplateSmokeTest {

    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static String render(String template, Map<String, Object> model) {
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        model.forEach(ctx::setVariable);
        return engine().process(template, ctx);
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = ThreadDumpTemplateSmokeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "리소스 없음: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ThreadDumpResult fixture(String name) throws IOException {
        byte[] b;
        try (InputStream in = ThreadDumpTemplateSmokeTest.class.getResourceAsStream("/threaddump/" + name)) { b = in.readAllBytes(); }
        return ThreadDumpEngine.analyze(() -> new ByteArrayInputStream(b), ThreadDumpLimits.DEFAULT, null);
    }

    private static ThreadDumpAnalysisEntity entity(String name, String status) {
        ThreadDumpAnalysisEntity e = new ThreadDumpAnalysisEntity();
        e.setFilename(name);
        e.setStatus(status);
        e.setFileSize(53949L);
        e.setServerName("was-01");
        e.setUploadedBy("admin");
        e.setCreatedAt(LocalDateTime.of(2026, 11, 26, 10, 0));
        if ("SUCCESS".equals(status)) {
            e.setSnapshotCount(3); e.setThreadCount(36); e.setDeadlockCount(1); e.setSeverity("Critical");
            e.setJdkMajor(8); e.setMiddleware("Tomcat"); e.setAnalyzedAt(LocalDateTime.of(2026, 11, 26, 10, 1)); e.setAnalysisTimeMs(42L);
        }
        if ("ERROR".equals(status)) { e.setErrorCode("NOT_THREAD_DUMP"); e.setErrorMessage("NOT_THREAD_DUMP: 스레드 덤프 형식을 인식하지 못했습니다."); }
        return e;
    }

    private static Map<String, Object> aiInsight() {
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("severity", "High");
        ai.put("summary", "교착 1건과 락 경합이 있습니다.");
        ai.put("rootCause", "락 획득 순서가 서로 반대입니다.");
        ai.put("recommendations", List.of("락 순서 통일", "tryLock 사용"));
        ai.put("codeAdvice", "DeadlockDemo.lockAB / lockBA");
        ai.put("model", "test-model");
        return ai;
    }

    // ── 목록 화면 ───────────────────────────────────────────────

    @Test
    @DisplayName("목록 화면 — 상태별 행과 분석 시작 링크(AnalyzeConfirm)·삭제·다운로드")
    void indexRenders() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        ThreadDumpAnalysisEntity gone = entity("gone.txt", "SUCCESS");
        gone.setFileDeleted(true);
        ThreadDumpAnalysisEntity gz = entity("kill3 로그.txt.gz", "NOT_ANALYZED");
        gz.setCompressed(true);
        m.put("history", List.of(entity("jstack-app.txt", "SUCCESS"), entity("bad.txt", "ERROR"), entity("run.txt", "ANALYZING"), gz, gone));
        m.put("files", List.of());
        m.put("isAdmin", true);
        m.put("maxBytes", 1073741824L);
        String html = render("thread-dump/index", m);

        assertTrue(html.contains("<meta name=\"_csrf\" content=\"test-token\">"), "CSRF 메타가 없다(함정: 큰 페이지에서 폼이 잘린다)");
        assertTrue(html.contains("id=\"gbNavThreadDump\""), "배너에 스레드 덤프 메뉴가 없다");
        assertTrue(html.contains("href=\"/thread-dump/analyze/jstack-app.txt\""));
        assertTrue(html.contains("data-kind=\"threaddump\""), "분석 시작 링크가 확인 모달 종류를 싣지 않는다");
        assertTrue(html.contains("AnalyzeConfirm.fromLink(this)"));
        assertTrue(html.contains("?start=1") || html.contains("start=1"), "실패·미분석 행의 분석 링크는 start=1 을 싣는다");
        assertTrue(html.contains("NOT_THREAD_DUMP: 스레드 덤프 형식을 인식하지 못했습니다."), "실패 사유가 목록에 보이지 않는다");
        assertTrue(html.contains("thd-sev sev-Critical"));
        assertTrue(html.contains(">GZ<"));
        assertTrue(html.contains("파일 없음"));
        assertTrue(html.contains("var THD_MAX_BYTES = 1073741824"));
        assertTrue(html.contains("kill3%20%EB%A1%9C%EA%B7%B8.txt.gz"), "한글·공백 파일명이 링크에서 인코딩되지 않았다");
        // 파일이 지워진 기록에는 다운로드·분석 버튼이 없다
        int goneRow = html.indexOf("data-filename=\"gone.txt\"");
        String goneHtml = html.substring(goneRow, html.indexOf("</tr>", goneRow));
        assertFalse(goneHtml.contains("thdDownload("), "파일 없는 기록에 다운로드 버튼이 있다");
        assertTrue(html.contains("/js/thread-dump-index.js"));
    }

    @Test
    @DisplayName("목록 화면 — 기록이 없을 때")
    void indexEmpty() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("history", List.of());
        m.put("files", List.of());
        m.put("isAdmin", false);
        m.put("maxBytes", 1L);
        String html = render("thread-dump/index", m);
        assertTrue(html.contains("스레드 덤프가 없습니다"));
        assertFalse(html.contains("id=\"thdTable\""));
    }

    // ── 결과 화면 ───────────────────────────────────────────────

    private static Map<String, Object> analyzeModel(String status, boolean fileExists, boolean withEntity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("filename", "jstack-app.txt");
        m.put("entity", withEntity ? entity("jstack-app.txt", status) : null);
        m.put("fileExists", fileExists);
        m.put("status", status);
        m.put("llmEnabled", true);
        return m;
    }

    @Test
    @DisplayName("결과 화면 — 완료·분석 중·실패·미분석·파일 없음 모두 렌더된다")
    void analyzeRendersEveryStatus() {
        String ok = render("thread-dump/analyze", analyzeModel("SUCCESS", true, true));
        assertTrue(ok.contains("var THD_STATUS = \"SUCCESS\""));
        assertTrue(ok.contains("var THD_FILENAME = \"jstack-app.txt\""));
        assertTrue(ok.contains("var THD_ERROR_CODE = null"));
        assertTrue(ok.contains("/thread-dump/analyze/jstack-app.txt/print-pdf?mode=download"), "PDF 다운로드 링크가 없다");
        assertTrue(ok.contains("id=\"thdPanel-report\""));
        assertTrue(ok.contains("/js/lib/chart.umd.min.js"));
        for (String id : new String[]{"thdBanner", "thdKpi", "thdStateChart", "thdTrendChart", "thdFindings", "thdAiBody", "thdDiag", "thdDeadlocks",
                "thdLockBody", "thdStuckBody", "thdCpuBody", "thdThreadBody", "thdGroups", "thdPoolBody", "thdRaw", "thdReportFrame"}) {
            assertTrue(ok.contains("id=\"" + id + "\""), "결과 화면에 #" + id + " 가 없다 — 스크립트가 그릴 자리가 사라졌다");
        }
        // 2.7.1 — 옛 행에 추정 미들웨어가 남아 있어도 보이지 않는다(확정할 수 없는 값은 싣지 않는다)
        assertFalse(ok.contains("미들웨어") || ok.contains(">Tomcat<"), "스레드 덤프 결과 화면에 추정 미들웨어가 보인다");
        // 스냅샷이 1개면 AI 카드가 추이 카드 자리(#thdSide)로 옮겨 간다 — 옮길 곳과 돌아올 곳이 있어야 한다
        assertTrue(ok.indexOf("id=\"thdSide\"") < ok.indexOf("id=\"thdAiHome\"") && ok.indexOf("id=\"thdAiHome\"") < ok.indexOf("id=\"thdAiCard\""));
        // 요약 탭 AI 카드는 심각도 띠만 — 전체는 'AI 분석' 탭, 상세보기 버튼이 그 탭으로 보낸다
        assertTrue(ok.contains("data-tab=\"ai\"") && ok.contains("id=\"thdPanel-ai\"") && ok.contains("id=\"thdAiFull\""), "AI 분석 탭이 없다");
        assertTrue(ok.contains("id=\"thdAiDetailBtn\" onclick=\"thdTab('ai')\""), "요약 AI 카드에 상세보기 → AI 분석 탭 버튼이 없다");
        assertTrue(ok.indexOf("data-tab=\"threads\"") < ok.indexOf("data-tab=\"ai\"") && ok.indexOf("data-tab=\"ai\"") < ok.indexOf("data-tab=\"report\""));

        String running = render("thread-dump/analyze", analyzeModel("ANALYZING", true, true));
        assertTrue(running.contains("스레드 덤프를 분석하는 중입니다"));
        assertTrue(running.contains("is-current"));

        String err = render("thread-dump/analyze", analyzeModel("ERROR", true, true));
        assertTrue(err.contains("NOT_THREAD_DUMP: 스레드 덤프 형식을 인식하지 못했습니다."));
        assertTrue(err.contains("var THD_ERROR_CODE = \"NOT_THREAD_DUMP\""));
        assertTrue(err.contains("id=\"thdRetryBtn\""));

        String fresh = render("thread-dump/analyze", analyzeModel("NOT_ANALYZED", true, false));
        assertTrue(fresh.contains("스레드 덤프 분석을 시작합니다"));

        String missing = render("thread-dump/analyze", analyzeModel("MISSING", false, false));
        assertTrue(missing.contains("파일이 없습니다"));
        assertFalse(missing.contains("id=\"thdRetryBtn\""), "파일이 없으면 다시 분석 버튼을 내지 않는다");
    }

    // ── 리포트(인쇄 템플릿) ─────────────────────────────────────

    @Test
    @DisplayName("리포트 HTML — 실제 덤프의 교착·락 경합·정체·풀·파싱 진단이 들어간다")
    void printHtmlRenders() throws IOException {
        ThreadDumpResult r = fixture("jdk8-deadlock-3snap.txt");
        Map<String, Object> model = new ThreadDumpPdfReportService(null).buildPrintModel("jstack-app.txt", entity("jstack-app.txt", "SUCCESS"), r, aiInsight());
        String html = render("thread-dump/analyze-print", model);
        assertTrue(html.contains("Thread Dump Analysis Report"));
        assertNull(r.getMeta().getMiddleware(), "스레드 덤프로는 미들웨어를 확정할 수 없어 판정하지 않는다");
        assertFalse(html.contains("미들웨어"), "리포트에 추정 미들웨어가 실린다");
        assertTrue(html.contains("was-01"));
        assertTrue(html.contains("JDK 8"));
        assertTrue(html.contains("교착 사슬 (Deadlock)"));
        assertTrue(html.contains("deadlock-A") && html.contains("deadlock-B"));
        assertTrue(html.contains("at DeadlockDemo.lockAB(DeadlockDemo.java:48)"), "교착 스레드의 스택이 리포트에 없다");
        assertTrue(html.contains("hot-lock-owner"));
        assertTrue(html.contains("http-nio-#-exec-#"));
        assertTrue(html.contains("같은 스택에 머문 스레드"));
        assertTrue(html.contains("락 순서 통일"), "AI 권장 조치가 없다");
        assertTrue(html.contains("파싱 진단"));
        assertTrue(html.contains("class=\"sev critical\""));
        assertFalse(html.contains("<script"), "인쇄 템플릿에는 스크립트가 없어야 한다(PDF 렌더러가 실행하지 못한다)");
        assertFalse(html.contains(" th:"), "처리되지 않은 Thymeleaf 속성이 남았다");
    }

    @Test
    @DisplayName("리포트 PDF — %PDF 바이트가 나온다(XHTML 정합성·Pretendard 로드)")
    void printPdfRenders() throws IOException {
        ThreadDumpResult r = fixture("jdk21-3snap.txt");
        byte[] pdf = new ThreadDumpPdfReportService(new PdfReportService(engine(), null, null))
                .renderPdf("jstack 한글.txt", entity("jstack 한글.txt", "SUCCESS"), r, aiInsight());
        assertTrue(pdf.length > 1000, "PDF 바이트가 비정상적으로 작다: " + pdf.length);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("리포트 PDF — 스레드 이름에 XML 특수문자·따옴표가 있어도, AI 해석·서버 이름이 없어도 렌더된다")
    void printPdfSurvivesHostileNames() throws IOException {
        String dump = "2026-11-26 10:00:00\nFull thread dump OpenJDK 64-Bit Server VM (25.422-b05 mixed mode):\n\n"
                + "\"<svc> & \"quoted\" 'worker' ]]> é한글\" #1 prio=5 os_prio=0 tid=0x00007f0000001000 nid=0x1 waiting for monitor entry [0x0]\n"
                + "   java.lang.Thread.State: BLOCKED (on object monitor)\n\tat a.B$$Lambda$1/0x0000000800b8c440.run(<Unknown Source>)\n"
                + "\t- waiting to lock <0x02> (a java.util.Map$Entry<K,V>)\n\t- locked <0x01> (a java.lang.Object)\n\n"
                + "\"other&amp;\" #2 prio=5 os_prio=0 tid=0x00007f0000002000 nid=0x2 waiting for monitor entry [0x0]\n"
                + "   java.lang.Thread.State: BLOCKED (on object monitor)\n\tat a.C.run(C.java:1)\n"
                + "\t- waiting to lock <0x01> (a java.lang.Object)\n\t- locked <0x02> (a java.util.Map$Entry<K,V>)\n\nJNI global references: 1\n";
        ThreadDumpResult r = ThreadDumpEngine.analyze(() -> new ByteArrayInputStream(dump.getBytes(StandardCharsets.UTF_8)), ThreadDumpLimits.DEFAULT, null);
        assertEquals(1, r.getDeadlocks().size());
        byte[] pdf = new ThreadDumpPdfReportService(new PdfReportService(engine(), null, null)).renderPdf("x.txt", null, r, null);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
        String html = render("thread-dump/analyze-print", new ThreadDumpPdfReportService(null).buildPrintModel("x.txt", null, r, null));
        assertTrue(html.contains("&lt;svc&gt; &amp;"), "스레드 이름이 이스케이프되지 않았다");
        assertTrue(html.contains("AI 분석 미실행"));
    }

    @Test
    @DisplayName("리포트 PDF — 스레드 3,000개 × 스냅샷 3개 결과도 행수 상한 안에서 렌더된다")
    void printPdfBoundedForLargeResult() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < 3; s++) {
            sb.append("2026-11-26 10:00:0").append(s).append("\nFull thread dump OpenJDK 64-Bit Server VM (25.422-b05 mixed mode):\n\n");
            for (int i = 0; i < 3000; i++) {
                sb.append("\"pool-").append(i % 90).append("-thread-").append(i).append("\" #").append(i + 1)
                        .append(" prio=5 os_prio=0 tid=0x00007f00").append(String.format("%08x", 0x1000 * (i + 1))).append(" nid=0x").append(Integer.toHexString(i + 16))
                        .append(" runnable [0x0]\n   java.lang.Thread.State: RUNNABLE\n\tat com.example.Work").append(i % 400).append(".run(Work.java:").append(i % 7).append(")\n")
                        .append("\tat java.lang.Thread.run(Thread.java:750)\n\n");
            }
            sb.append("JNI global references: 9\n\n");
        }
        byte[] in = sb.toString().getBytes(StandardCharsets.UTF_8);
        ThreadDumpResult r = ThreadDumpEngine.analyze(() -> new ByteArrayInputStream(in), ThreadDumpLimits.DEFAULT, null);
        assertEquals(3000, r.getKpi().getThreadsLast());
        Map<String, Object> model = new ThreadDumpPdfReportService(null).buildPrintModel("big.txt", null, r, null);
        @SuppressWarnings("unchecked") List<Object> stuck = (List<Object>) model.get("stuck");
        assertTrue(stuck.size() <= 30, "정체 스레드 표가 상한을 넘는다: " + stuck.size());
        assertTrue((Integer) model.get("stuckOverflow") > 0, "넘친 건수를 알리지 않는다");
        long t0 = System.currentTimeMillis();
        byte[] pdf = new ThreadDumpPdfReportService(new PdfReportService(engine(), null, null)).renderPdf("big.txt", null, r, null);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
        assertTrue(pdf.length < 2_000_000, "리포트는 요약이어야 한다: " + pdf.length);
        assertTrue(System.currentTimeMillis() - t0 < 30_000, "PDF 렌더가 너무 오래 걸린다");
        // 저장 상한으로 줄인 결과(인덱스가 빈 곳이 있는 상태)도 그대로 렌더된다
        ThreadDumpResultCodec.toJsonCapped(r, 200_000);
        assertEquals("%PDF-", new String(new ThreadDumpPdfReportService(new PdfReportService(engine(), null, null))
                .renderPdf("big.txt", null, r, null), 0, 5, StandardCharsets.US_ASCII));
    }

    // ── 2.7.1 힙 덤프 연계 · 이어 붙이기 ─────────────────────────

    private static ThreadDumpResult heapDerived() throws IOException {
        String ov, th;
        try (InputStream in = ThreadDumpTemplateSmokeTest.class.getResourceAsStream("/threaddump/heap/synthetic-overview.html")) { ov = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        try (InputStream in = ThreadDumpTemplateSmokeTest.class.getResourceAsStream("/threaddump/heap/synthetic.threads")) { th = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        var x = com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor.extract(ov, th, "2026-11-26 10:00:00");
        return com.heapdump.analyzer.parser.threaddump.HeapThreadExtractor.analyze(x, ThreadDumpLimits.DEFAULT, "app.hprof");
    }

    @Test
    @DisplayName("결과 화면(2.7.1) — 힙 덤프에서 추출 모드는 분석·삭제 버튼 없이 힙 경로를 쓴다")
    void analyzeHeapMode() {
        Map<String, Object> m = analyzeModel("SUCCESS", false, false);
        m.put("filename", "app.hprof");
        m.put("heapMode", true);
        com.heapdump.analyzer.model.entity.AnalysisHistoryEntity he = new com.heapdump.analyzer.model.entity.AnalysisHistoryEntity();
        he.setFilename("app.hprof");
        he.setServerName("was-01");
        he.setDumpCreationTime("2026-11-26 10:00:00");
        he.setSuspectCount(2);
        m.put("heapEntity", he);
        m.put("heapUsedText", "300.0 MB / 1.0 GB");
        String html = render("thread-dump/analyze", m);
        assertTrue(html.contains("var THD_MODE = \"heap\""));
        assertTrue(html.contains("힙 덤프에서 추출"));
        assertTrue(html.contains("/thread-dump/heap/app.hprof/print-pdf?mode=download"), "힙 모드 PDF 링크가 없다");
        assertTrue(html.contains("href=\"/analyze/app.hprof\""), "힙 덤프 결과로 돌아가는 링크가 없다");
        assertTrue(html.contains("300.0 MB / 1.0 GB") && html.contains("2026-11-26 10:00:00"));
        assertFalse(html.contains("id=\"thdBtnReanalyze\""), "힙 모드에 재분석 버튼이 있다");
        assertFalse(html.contains("thdOpenDelete()"), "힙 모드에 삭제 버튼이 있다");
        assertFalse(html.contains("id=\"thdHeapLinkChip\""), "힙 모드에 힙 연결 칩이 있다");
        // 스레드 덤프 파일로 내보내기 — 힙 모드에만 버튼·모달이 있다
        assertTrue(html.contains("id=\"thdBtnExport\"") && html.contains("id=\"thdExportModal\""), "힙 모드에 스레드 덤프로 내보내기가 없다");
        assertTrue(html.contains("onclick=\"thdRunExport(true)\""), "이미 있을 때 새 이름으로 내보내기 버튼이 없다");

        String dump = render("thread-dump/analyze", analyzeModel("SUCCESS", true, true));
        assertTrue(dump.contains("var THD_MODE = \"dump\""), "heapMode 가 없으면 스레드 덤프 모드다");
        assertFalse(dump.contains("id=\"thdBtnExport\"") || dump.contains("id=\"thdExportModal\""), "스레드 덤프 결과에 힙 내보내기가 있다");
        for (String id : new String[]{"thdHeapLinkChip", "thdHeapLinkModal", "thdHeapCandList", "thdMembersCard", "thdMemCard", "thdUngroupModal"}) {
            assertTrue(dump.contains("id=\"" + id + "\""), "#" + id + " 가 없다");
        }
        assertTrue(dump.contains("data-sort-key=\"mem\""), "힙 점유 열이 없다");
    }

    @Test
    @DisplayName("결과 화면(2.7.1) — 묶음 대표 배지·구성 파일의 대표 링크")
    void analyzeCombinedBadges() {
        Map<String, Object> m = analyzeModel("SUCCESS", true, true);
        ThreadDumpAnalysisEntity rep = (ThreadDumpAnalysisEntity) m.get("entity");
        rep.setMemberCount(3);
        rep.setMemberFiles("[\"jstack-app.txt\",\"b.txt\",\"c.txt\"]");
        assertTrue(render("thread-dump/analyze", m).contains("이어 붙임 3개"));
        ThreadDumpAnalysisEntity member = entity("b.txt", "NOT_ANALYZED");
        member.setPartOf("jstack-app.txt");
        m.put("entity", member);
        assertTrue(render("thread-dump/analyze", m).contains("href=\"/thread-dump/analyze/jstack-app.txt\""));
    }

    @Test
    @DisplayName("목록 화면(2.7.1) — 선택 체크박스·이어 붙이기 바·모달·묶음 배지")
    void indexCombine() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        ThreadDumpAnalysisEntity rep = entity("rep.txt", "SUCCESS");
        rep.setMemberCount(2);
        ThreadDumpAnalysisEntity mem = entity("mem.txt", "NOT_ANALYZED");
        mem.setPartOf("rep.txt");
        ThreadDumpAnalysisEntity gone = entity("gone.txt", "SUCCESS");
        gone.setFileDeleted(true);
        m.put("history", List.of(rep, mem, gone));
        m.put("files", List.of());
        m.put("isAdmin", true);
        m.put("maxBytes", 1L);
        String html = render("thread-dump/index", m);
        for (String id : new String[]{"thdCombineBar", "thdCombineModal", "thdCombineList", "thdSuggestModal", "thdSuggestList"}) {
            assertTrue(html.contains("id=\"" + id + "\""), "#" + id + " 가 없다");
        }
        assertTrue(html.contains("묶음 2개"));
        assertTrue(html.contains("묶음 구성 파일"));
        assertTrue(html.contains("class=\"thd-sel\" data-filename=\"rep.txt\""));
        int goneRow = html.indexOf("data-filename=\"gone.txt\"");
        assertFalse(html.substring(goneRow, html.indexOf("</tr>", goneRow)).contains("thd-sel"), "파일 없는 기록은 고를 수 없다");
    }

    @Test
    @DisplayName("리포트(2.7.1) — 힙 덤프 추출 결과: 제목·출처·힙 점유 표, PDF 렌더")
    void printHeapDerived() throws IOException {
        ThreadDumpResult r = heapDerived();
        Map<String, Object> model = new ThreadDumpPdfReportService(null).buildPrintModel("app.hprof", null, r, null, null);
        String html = render("thread-dump/analyze-print", model);
        assertTrue(html.contains("Thread Analysis Report (Heap Dump)"));
        assertTrue(html.contains("힙 덤프를 만든 한 시점의 스레드 정보에서 추출"));
        assertTrue(html.contains("힙 점유가 큰 스레드"));
        assertTrue(html.contains("batch &amp; &lt;report&gt; worker"), "HTML 특수문자 스레드명이 이스케이프되지 않았다");
        byte[] pdf = new ThreadDumpPdfReportService(new PdfReportService(engine(), null, null)).renderPdf("app.hprof", null, r, null, null);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("리포트(2.7.1) — 이어 붙인 결과의 구성 파일·스냅샷 파일 열, 연결된 힙 덤프 행")
    void printCombinedAndLinked() throws IOException {
        String all = resource("/threaddump/jdk21-3snap.txt");
        int cut = all.lastIndexOf('\n', all.indexOf("\nFull thread dump", 10) - 1) + 1;
        byte[] a = all.substring(0, cut).getBytes(StandardCharsets.UTF_8), b = all.substring(cut).getBytes(StandardCharsets.UTF_8);
        ThreadDumpResult r = ThreadDumpEngine.analyze(List.of(new ThreadDumpEngine.Source("first.txt", () -> new ByteArrayInputStream(a)),
                new ThreadDumpEngine.Source("second.txt", () -> new ByteArrayInputStream(b))), ThreadDumpLimits.DEFAULT, null, false);
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("linked", true);
        link.put("heapExists", true);
        link.put("heapFilename", "app.hprof");
        link.put("dumpTime", "2026-11-26 10:53:30");
        link.put("gapSec", 21L);
        link.put("matchedThreads", 2);
        link.put("threadCount", 30);
        link.put("topThreads", List.of(Map.of("name", "hot-lock-owner", "retainedText", "8.6 MB", "heapState", "RUNNABLE")));
        Map<String, Object> model = new ThreadDumpPdfReportService(null).buildPrintModel("first.txt", null, r, null, link);
        String html = render("thread-dump/analyze-print", model);
        assertTrue(html.contains("first.txt, second.txt"), "구성 파일 행이 없다");
        assertTrue(html.contains("app.hprof · 생성 2026-11-26 10:53:30 · 스냅샷과 21초 차이 · 이름 일치 스레드 2/30"), html.substring(0, 0));
        assertTrue(html.contains("힙 점유가 큰 스레드 (연결된 힙 덤프 기준)"));
        assertTrue(html.contains(">second.txt<"), "스냅샷 표에 파일 열이 없다");
        byte[] pdf = new ThreadDumpPdfReportService(new PdfReportService(engine(), null, null)).renderPdf("first.txt", null, r, null, link);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
    }

    // ── 화면 규약 ───────────────────────────────────────────────

    @Test
    @DisplayName("CSS — 글꼴 변수를 다시 선언하지 않고, 좌측 색 띠를 쓰지 않는다")
    void cssRules() throws IOException {
        String css = resource("/static/css/thread-dump.css");
        assertFalse(css.contains("--font-sans:") || css.contains("--font-mono:"), "thread-dump.css 가 글꼴 변수를 다시 선언한다");
        assertFalse(css.contains("border-left"), "좌측 색 띠 금지 — 옅은 배경 + 1px 테두리 + 배지로 표현한다");
        assertFalse(css.contains("inset 3px 0") || css.contains("inset 4px 0"), "inset 그림자로 만든 좌측 레일도 금지");
        String print = resource("/templates/thread-dump/analyze-print.html");
        assertFalse(print.contains("border-left"), "인쇄 템플릿에도 좌측 색 띠를 쓰지 않는다");
        assertFalse(print.contains("display: flex") || print.contains("display:flex") || print.contains("display: grid"),
                "OpenHTMLtoPDF 는 flex/grid 를 지원하지 않는다");
        // common.css → core-dump.css → gc-log.css → thread-dump.css 순서
        for (String tpl : new String[]{"/templates/thread-dump/index.html", "/templates/thread-dump/analyze.html"}) {
            String t = resource(tpl);
            int a = t.indexOf("/css/common.css"), b = t.indexOf("/css/core-dump.css"), c = t.indexOf("/css/gc-log.css"), d = t.indexOf("/css/thread-dump.css");
            assertTrue(a > 0 && a < b && b < c && c < d, tpl + " 의 CSS 로드 순서가 어긋났다");
        }
    }

    @Test
    @DisplayName("스크립트 — 모달은 헬퍼만 거치고, 폴링은 managedInterval, 자동 새로 고침은 없다")
    void scriptRules() throws IOException {
        for (String js : new String[]{"/static/js/thread-dump-analyze.js", "/static/js/thread-dump-index.js"}) {
            String s = resource(js);
            int hide = s.indexOf("    function hideModal(ov) {");
            assertTrue(hide > 0, js + " 에 hideModal 이 없다");
            String body = s.substring(hide, s.indexOf("\n    }\n", hide));
            assertTrue(body.contains("ov.classList.add('closing');") && body.contains("Common.afterMotion(ov, 'animationend'"), js + " 가 사라지는 연출을 기다리지 않는다");
            assertTrue(body.contains("Common.reduceMotion()) { fin(); return; }"), js + " — 동작 줄이기에서 끝 이벤트를 기다린다");
            assertEquals(1, count(s, "classList.add('open')"), js + " 에 showModal 밖의 직접 열기가 있다");
            assertEquals(0, count(s, "classList.remove('open')"), js + " 에 연출 없이 바로 닫는 경로가 있다");
            assertFalse(s.contains("location.reload("), js + " — 자동 갱신에 location.reload 금지(함정 69)");
            assertTrue(s.contains("SessionTimeout.managedInterval("), js + " 의 폴링이 managedInterval 을 거치지 않는다(함정 38)");
            assertTrue(s.contains("Common.downloadFile("), js + " 의 원본 다운로드가 공통 헬퍼를 거치지 않는다");
        }
        String an = resource("/static/js/thread-dump-analyze.js");
        assertFalse(an.contains("m.middleware"), "옛 결과의 추정 미들웨어를 화면에 쓴다");
        assertTrue(an.contains("placeAiCard(R.snapshots.length > 1)"), "스냅샷 1개일 때 AI 카드를 추이 자리로 옮기지 않는다");
        assertTrue(an.contains("var TABS = ['summary', 'locks', 'threads', 'ai', 'report'];"), "AI 분석 탭이 탭 목록에 없다(해시 이동·전환 불가)");
        assertTrue(an.contains("setAiBodies(sevHtml, h);"), "요약 탭에는 심각도 띠만, AI 탭에는 전체를 그리지 않는다");
        // 화면이 아는 실패 코드 = 서버가 내는 실패 코드
        for (String code : new String[]{"NOT_THREAD_DUMP", "UNSUPPORTED_FORMAT", "EMPTY", "TOO_LARGE", "TIMEOUT", "CANCELLED", "FILE_MISSING",
                "IO_ERROR", "OUT_OF_MEMORY", "INTERNAL", "INTERRUPTED"}) {
            assertTrue(an.contains(code + ":"), "결과 화면이 실패 코드 " + code + " 의 안내문을 갖고 있지 않다");
        }
        List<String> missing = new ArrayList<>();
        String tpl = resource("/templates/thread-dump/analyze.html");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("byId\\('(thd[A-Za-z]+)'\\)").matcher(an);
        while (m.find()) if (!tpl.contains("id=\"" + m.group(1) + "\"") && !an.contains("id=\"" + m.group(1) + "\"")) missing.add(m.group(1));
        assertTrue(missing.isEmpty(), "스크립트가 찾는 요소가 템플릿에 없다: " + missing);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}

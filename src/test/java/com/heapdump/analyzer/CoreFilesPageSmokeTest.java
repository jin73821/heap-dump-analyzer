package com.heapdump.analyzer;

import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.CoreFileRow;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreFileListBuilder;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코어 파일 화면(/core-dump) 렌더 스모크 — 2026-11-26 개편(서버 파일 + 분석 이력 한 표).
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>상단바(.cd-topbar)는 개편 대상이 아니다 — 제목·단계 표시가 그대로 렌더된다</li>
 *   <li>코어 한 개 = 표 행 + 바로 다음 상세 행(실행 파일 행은 상세 없음) — JS 가 이 쌍을 함께 옮긴다</li>
 *   <li>미분석 행의 '분석' 은 공통 분석 확인 모달(AnalyzeConfirm, kind=core)을 거친다</li>
 *   <li>템플릿의 onclick/onchange 가 부르는 전역 함수가 core-dump-index.js 에 모두 노출돼 있다</li>
 * </ul>
 * {@code -Dcorefiles.render.dump=<경로>} 를 주면 렌더 결과를 파일로 남긴다(헤드리스 화면 확인용).
 */
class CoreFilesPageSmokeTest {

    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static final String HTTPD_BT =
            "#0 0x000055d0c1a2f3e4 in ap_proxy_http_process_response at mod_proxy_http.c:1478\n"
            + "#1 0x000055d0c1a31b0c in proxy_http_handler at mod_proxy_http.c:2011\n"
            + "#2 0x000055d0c1a1e7a2 in proxy_run_scheme_handler at mod_proxy.c:3015";

    private static AnalysisHistoryItem file(String name, String type, long size, LocalDateTime modified, String exec) {
        AnalysisHistoryItem f = new AnalysisHistoryItem();
        f.setFilename(name);
        f.setFileType(type);
        f.setSizeBytes(size);
        f.setFormattedSize(size >= 1 << 30 ? (size >> 30) + " GB" : (size >> 20) + " MB");
        f.setLastModified(modified.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        f.setPairedExecFilename(exec);
        return f;
    }

    private static CoreDumpAnalysisEntity entity(String name, String status, LocalDateTime created,
                                                 String signal, String summary, String exec) {
        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
        e.setFilename(name);
        e.setStatus(status);
        e.setCreatedAt(created);
        e.setUploadedBy("admin");
        e.setCrashSignal(signal);
        e.setCrashSummary(summary);
        e.setExecutableName(exec);
        if ("SUCCESS".equals(status) || "ERROR".equals(status)) {
            e.setAnalyzedAt(created.plusMinutes(3));
            e.setAnalysisTimeMs(161_000L);
        }
        if ("ERROR".equals(status)) e.setErrorMessage("gdb 가 600초 안에 끝나지 않아 중단했습니다.");
        return e;
    }

    static List<CoreFileRow> fixtureRows() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 26, 14, 32);
        List<AnalysisHistoryItem> files = List.of(
                file("core.java.30122", "coredump", 3L << 30, t.plusMinutes(36), null),
                file("core.httpd.4821", "coredump", 1L << 30, t, "httpd"),
                file("core.sshd.3391", "coredump", 84L << 20, t.minusHours(5), null),
                file("core.tmax.1102", "coredump", 6L << 30, t.minusHours(20), null),
                file("core.httpd.4790", "coredump", 1L << 30, t.minusDays(2), "httpd"),
                file("core.java.28411", "coredump", 2L << 30, t.minusDays(2).minusHours(3), "java"),
                file("statd", "exec", 480L << 10, t.minusDays(8), null));
        List<CoreDumpAnalysisEntity> history = List.of(
                entity("core.java.30122", "ANALYZING", t.plusMinutes(36), null, null, null),
                entity("core.httpd.4821", "SUCCESS", t, "SIGSEGV", HTTPD_BT, "httpd"),
                entity("core.tmax.1102", "ERROR", t.minusHours(20), null, null, null),
                entity("core.httpd.4790", "SUCCESS", t.minusDays(2), "SIGSEGV", HTTPD_BT, "httpd"),
                entity("core.java.28411", "SUCCESS", t.minusDays(2).minusHours(3), "SIGABRT",
                        "#1 0x00007f8e2b454476 in raise\n#3 0x00007f8e2a6e1d2b in os::abort", "java"),
                entity("core.gone.1", "SUCCESS", t.minusDays(9), "SIGFPE", "#0 0x401b6e in calc_ratio at stat.c:212", null));
        return CoreFileListBuilder.build(files, history, n -> 1_153_434L, LocalDate.of(2026, 11, 26));
    }

    private static String render(List<CoreFileRow> rows) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        JakartaServletWebApplication app = JakartaServletWebApplication.buildApplication(new MockServletContext());
        IWebExchange exchange = app.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        WebContext ctx = new WebContext(exchange);
        ctx.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(new StaticApplicationContext(), null));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("coreFiles", rows);
        m.put("coreRepeatGroups", CoreFileListBuilder.repeatGroups(rows));
        m.forEach(ctx::setVariable);
        return engine.process("core-dump/index", ctx);
    }

    private static int count(String html, String needle) {
        return html.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    @DisplayName("서버 파일과 이력이 한 표로 렌더되고 상단바는 그대로다")
    void rendersMergedTable() throws IOException {
        List<CoreFileRow> rows = fixtureRows();
        String html = render(rows);
        String dump = System.getProperty("corefiles.render.dump");
        if (dump != null && !dump.isBlank()) Files.writeString(Path.of(dump), html, StandardCharsets.UTF_8);

        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertFalse(html.contains("[["), "인라인 표현식으로 해석될 '[[' 가 남아 있다");

        // 상단바 — 개편 대상 아님
        assertTrue(html.contains("<span class=\"topbar-title\">코어 덤프 분석</span>"), "상단바 제목");
        assertTrue(html.contains("<nav class=\"cd-steps\" aria-label=\"분석 단계\">"), "상단바 단계 표시");
        assertFalse(html.contains("cd-app-layout") || html.contains("cdFileList"), "옛 좌측 파일 목록이 남아 있다");

        // 행 = 7(디스크) + 1(원본 없음), 상세 = 실행 파일 행을 뺀 7
        assertEquals(8, count(html, "<tr class=\"cf-row\""), "표 행 수");
        assertEquals(7, count(html, "<tr class=\"cf-detail\""), "상세 행 수(실행 파일 행 제외)");
        // 상세 행은 자기 행 바로 다음 — 열린 tr 순서만 훑어 확인(실행 파일 행 뒤에는 상세가 없다)
        Matcher tr = Pattern.compile("<tr class=\"cf-(row|detail)\"[^>]*?\\sid=\"cf[rd]-(\\d+)\"").matcher(html);
        String lastRow = null;
        int pairs = 0;
        while (tr.find()) {
            if ("row".equals(tr.group(1))) { lastRow = tr.group(2); continue; }
            assertEquals(lastRow, tr.group(2), "상세 행이 자기 행 바로 다음에 있지 않다");
            lastRow = null;
            pairs++;
        }
        assertEquals(7, pairs);

        // 상태별 작업
        assertTrue(html.contains("href=\"/core-dump/analyze/core.httpd.4821\""), "완료 → 결과");
        assertTrue(html.contains("href=\"/core-dump/progress/core.java.30122\""), "분석 중 → 진행");
        assertTrue(Pattern.compile("href=\"/core-dump/progress/core.sshd.3391\"\\s+data-filename=\"core.sshd.3391\" data-kind=\"core\"").matcher(html).find(),
                "미분석 '분석' 은 AnalyzeConfirm(kind=core) 링크");
        assertTrue(html.contains("onclick=\"return window.AnalyzeConfirm ? AnalyzeConfirm.fromLink(this) : true;\""));
        assertTrue(html.contains("/js/analyze-confirm.js"), "analyze-confirm.js 로드");
        assertTrue(html.contains(">원본 없음</span>"), "이력만 있는 코어 표시");
        assertFalse(html.contains("data-filename=\"core.gone.1\" onclick=\"reanalyze"), "원본 없는 코어는 재분석 버튼이 없다");

        // 크래시 지점 · 같은 지점
        assertTrue(html.contains("<b class=\"cf-where-fn\">ap_proxy_http_process_response</b>"));
        assertTrue(html.contains("<span class=\"cf-bt-fn\">ap_proxy_http_process_response</span>"), "상세 백트레이스");
        assertTrue(html.contains("data-repeat-key=\"ap_proxy_http_process_response @ mod_proxy_http.c:1478\""), "같은 지점 버튼");
        assertEquals(2, count(html, "data-repeat=\"ap_proxy_http_process_response @ mod_proxy_http.c:1478\""), "묶인 행에만 data-repeat");
        assertTrue(html.contains("httpd · mod_proxy_http.c:1478"), "묶음이 같은 실행 파일이면 실행 파일 이름으로");

        // 상태 칸이 이미 말하는 것을 크래시 지점 칸에서 되풀이하지 않는다(실패 행은 사유만)
        assertFalse(html.contains("cf-where-fail") || html.contains("cf-where-run"), "굵은 색 글자 '분석 실패'·'GDB 실행 중' 이 돌아왔다");
        assertTrue(html.contains(">gdb 가 600초 안에 끝나지 않아 중단했습니다.</span>"), "실패 행은 사유를 그대로 보여 준다");

        // 좁은 본문의 두 줄 행 배치는 칸 클래스(cf-td-*)로 자리를 잡는다 — 행마다 8칸 모두 클래스가 있어야 한다
        for (String cell : List.of("chev", "st", "file", "sig", "where", "exec", "size", "up", "act")) {
            assertEquals(8, count(html, "<td class=\"cf-td-" + cell), "cf-td-" + cell + " 칸 수");
        }
        assertFalse(Pattern.compile("<tr class=\"cf-row\"[^>]*>\\s*(?:<td class=\"cf-td-[^>]*>.*?</td>\\s*)*<td>", Pattern.DOTALL)
                .matcher(html).find(), "클래스 없는 칸이 행에 있다");

        // '코어 덤프 업로드' 카드 — 업로드 버튼 두 개는 .cf-pair 안에 있고, JS 가 쓰는 id 는 그대로다
        assertTrue(html.contains("<h2 class=\"cf-h2\" id=\"cfNewTitle\">코어 덤프 업로드</h2>"), "카드 제목");
        assertTrue(Pattern.compile("<div class=\"cf-new-title\">\\s*<svg[^>]*aria-hidden=\"true\">.*?</svg>\\s*<h2 class=\"cf-h2\" id=\"cfNewTitle\">", Pattern.DOTALL)
                .matcher(html).find(), "카드 제목은 스레드 덤프 업로드 카드처럼 아이콘 + 제목 줄(.cf-new-title)");
        assertEquals(2, count(html, "<span class=\"cf-slot-pick\" aria-hidden=\"true\">파일 선택</span>"), "업로드 버튼마다 '파일 선택'(모양만)");
        assertEquals(2, count(html, "<span class=\"cf-slot-picked\">"), "고른 파일 표시 자리");
        assertTrue(Pattern.compile("<div class=\"cf-pair\">\\s*<div class=\"cf-slot\" id=\"coreDropZone\">.*?<div class=\"cf-slot\" id=\"execDropZone\">", Pattern.DOTALL)
                .matcher(html).find(), "코어·실행 파일 칸이 한 묶음(.cf-pair)");
        for (String id : List.of("coreFileName", "coreFileSize", "coreFileInput", "coreClearBtn", "execFileName", "execFileSize",
                "execFileInput", "execClearBtn", "uploadBtn", "uploadProgressFill", "uploadBtnLabel", "cfExecSuggest", "uploadStatus", "uploadErrorBox")) {
            assertEquals(1, count(html, "id=\"" + id + "\""), "업로드 카드 id " + id);
        }

        // 기간 필터 계약(CoreDumpHistoryCalendarSmokeTest)과 같은 data-uploaded 형식
        assertTrue(html.contains("data-uploaded=\"2026-11-26T14:32\""));
        assertTrue(html.contains("/css/core-dump-index.css"), "이 화면 전용 CSS");
    }

    @Test
    @DisplayName("긴 파일명은 가운데 말줄임 마크업으로, 소스 위치는 파일명:줄로 렌더된다")
    void longNamesKeepTheirTail() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 26, 3, 14);
        String name = "core.tmaxsvr_gw01.20261125-031455.18822";
        String bt = "#0 0x00007f1c2a4be3e4 in tmax::session::Registry::lookup(const std::string&) at /build/tmax/src/session/registry.cpp:188";
        List<CoreFileRow> rows = CoreFileListBuilder.build(
                List.of(file(name, "coredump", 2L << 30, t, null), file("core.httpd.1", "coredump", 1L << 20, t.minusHours(1), null)),
                List.of(entity(name, "SUCCESS", t, "SIGSEGV", bt, null)),
                n -> null, LocalDate.of(2026, 11, 26));
        String html = render(rows);

        // 앞(.cf-fname-h)만 줄고 끝(.cf-fname-t)은 남는다. 두 조각 사이에 공백이 없어야 복사한 이름이 온전하다
        assertTrue(html.contains("<span class=\"cf-fname-h\">core.tmaxsvr_gw01.20261125-</span><span class=\"cf-fname-t\">031455.18822</span>"),
                "긴 파일명 분할 마크업");
        assertTrue(html.contains("<span class=\"cf-fname-h\">core.httpd.1</span></button>"), "짧은 이름은 끝 조각이 없다");
        assertTrue(html.contains("title=\"" + name + "\""), "전체 이름 툴팁");

        // 표 칸에는 파일명:줄, 전체 경로는 툴팁과 펼친 백트레이스에
        assertTrue(html.contains("<span class=\"cf-where-loc\">registry.cpp:188</span>"), "표 칸의 짧은 위치");
        assertTrue(html.contains("<span class=\"cf-bt-loc\">/build/tmax/src/session/registry.cpp:188</span>"), "백트레이스의 전체 경로");
        assertTrue(html.contains("at /build/tmax/src/session/registry.cpp:188\""), "크래시 지점 칸 툴팁의 전체 경로");
    }

    @Test
    @DisplayName("폭 대응 규약 — 본문 폭 기준으로 열을 접고, 표에 min-width 를 주지 않으며, 상세 칸 colspan 을 맞춘다")
    void layoutContract() throws IOException {
        String css = Files.readString(Path.of("src/main/resources/static/css/core-dump-index.css"), StandardCharsets.UTF_8);
        String js = Files.readString(Path.of("src/main/resources/static/js/core-dump-index.js"), StandardCharsets.UTF_8);

        assertTrue(css.contains("container: cfpage / inline-size"), "본문(.cf-page)이 컨테이너여야 배너 접힘에 맞춰 열이 접힌다");
        assertTrue(Pattern.compile("@container cfpage \\(max-width: \\d+px\\) \\{\\s*\\.cf-table \\.cf-c-exec, \\.cf-table \\.cf-td-exec \\{ display: none; }").matcher(css).find(),
                "좁아지면 실행 파일 열부터 접는다");
        assertTrue(css.contains("grid-template-areas: \"chev st file act\" \". sig where up\""), "좁은 본문의 두 줄 행 배치");

        // 카드 모서리 = Dashboard·Files·History 의 .panel(10px)
        assertTrue(css.contains(".cf-box { background: #fff; border: 1px solid var(--cf-line); border-radius: 10px;"), "카드 모서리 10px");

        // 본문은 배너 오른쪽 영역의 가운데 — margin-left 를 배너 폭으로만 주면 넓은 화면에서 왼쪽에 붙는다
        assertTrue(css.contains("margin-left: max(var(--banner-w, 220px), calc((100% + var(--banner-w, 220px) - 1440px) / 2));"),
                "본문 가운데 정렬(.cf-page margin-left)");
        assertTrue(Pattern.compile("\\.cf-page \\{[^}]*max-width: 1440px;").matcher(css).find(), "가운데 정렬 식의 1440px 은 max-width 와 같아야 한다");

        // 표 min-width 는 컨테이너 쿼리가 없는 브라우저용 @supports 안에만 — 밖에 있으면 '작업' 열이 가로 스크롤 밖으로 밀린다
        int supports = css.indexOf("@supports not (container-type: inline-size)");
        assertTrue(supports > 0, "컨테이너 쿼리 미지원 브라우저용 안전망");
        Matcher mw = Pattern.compile("\\.cf-table\\s*\\{[^}]*min-width").matcher(css);
        while (mw.find()) assertTrue(mw.start() > supports, ".cf-table min-width 가 @supports 밖에 있다");

        // 상세 들여쓰기 — .cf-table td:first-child 보다 특이도가 높아야 한다
        assertTrue(css.contains(".cf-table .cf-detail > td {"), "상세 칸 선택자 특이도");
        assertFalse(Pattern.compile("(?m)^\\.cf-detail > td").matcher(css).find(), "특이도가 낮은 .cf-detail > td 규칙이 돌아왔다");

        // 열을 접으면 colspan 도 맞춘다(안 맞추면 '없는 열'이 남는 폭을 나눠 가진다)
        assertTrue(js.contains("function syncDetailSpan()") && js.contains("cell.colSpan = n"), "상세 칸 colspan 동기화");
        assertTrue(js.contains("new ResizeObserver(syncDetailSpan)"), "폭이 바뀔 때 다시 맞춘다");
    }

    @Test
    @DisplayName("표는 Files 디자인(.ftable·.fb), 삭제는 작업 칸 휴지통으로만(Delete 키·상세 '삭제…' 없음), 삭제·재분석 모달은 연출을 거친다")
    void filesTableAndDeleteContract() throws IOException {
        String html = render(fixtureRows());
        String css = Files.readString(Path.of("src/main/resources/static/css/core-dump-index.css"), StandardCharsets.UTF_8);
        String js = Files.readString(Path.of("src/main/resources/static/js/core-dump-index.js"), StandardCharsets.UTF_8);

        // Files 의 표 — 공용 .ftable 규칙 + 작업 칸 아이콘 버튼
        assertTrue(html.contains("<table class=\"cf-table ftable\" id=\"hiTable\">"), "공용 .ftable 규칙을 쓴다");
        assertTrue(html.contains("class=\"fb v\" href=\"/core-dump/analyze/core.httpd.4821\""), "완료 → 초록 결과 버튼");
        assertTrue(html.contains("class=\"fb analyzing\" href=\"/core-dump/progress/core.java.30122\""), "분석 중 → 진행 버튼");
        assertTrue(html.contains("class=\"fb e\" data-filename=\"core.tmax.1102\" onclick=\"reanalyze(this.dataset.filename, this)\""), "실패 → 빨강 재분석 버튼");
        assertEquals(7, count(html, "class=\"fb d\""), "실행 파일 행을 뺀 코어 행마다 휴지통");
        assertTrue(html.contains("<th class=\"cf-c-up sort-active\" data-sort-key=\"uploaded\" aria-sort=\"descending\">"), "기본 정렬 열 표시");
        assertTrue(css.contains(".cf-table th.sort-active .cf-sort-ind { opacity: 1; color: #2563EB; }"), "정렬 표시가 Files 와 같다");
        // 아이콘 버튼의 재분석은 글자를 바꾸지 않는다(바꾸면 아이콘이 지워진다)
        assertTrue(js.contains("if (icon) btn.classList.add('is-busy'); else btn.textContent = '요청 중...';"), "아이콘 버튼 재분석 상태");

        // 삭제 — 휴지통 버튼만. Delete 키·상세의 '삭제…'·단축키 안내가 돌아오면 안 된다
        assertFalse(js.contains("e.key === 'Delete'"), "Delete 키 삭제가 돌아왔다");
        assertFalse(html.contains("<kbd>Del</kbd>"), "Delete 단축키 안내가 남아 있다");
        assertFalse(html.contains(">삭제…</button>"), "펼친 상세에 '삭제…' 가 남아 있다");
        assertFalse(html.contains("cf-btn-danger-text"), "펼친 상세에 삭제 버튼이 남아 있다");

        // 모달 연출 — .open 은 showModal 한 곳에서만 붙고 떼기는 hideModal(연출 뒤)만
        assertTrue(html.contains("<div id=\"deleteDumpModal\" class=\"modal-ov cf-modal\""), "삭제 모달 연출 클래스");
        assertTrue(html.contains("<div id=\"reanalyzeModal\" class=\"modal-ov cf-modal\""), "재분석 모달 연출 클래스");
        assertEquals(1, count(js, "classList.add('open')"), "showModal 밖에서 .open 을 직접 붙인다 — 연출을 건너뛴다");
        assertEquals(0, count(js, "classList.remove('open')"), "연출 없이 바로 닫는 경로가 있다");
        assertTrue(js.contains("ov.classList.add('closing');") && js.contains("Common.afterMotion(ov, 'animationend', 260, fin);"),
                "닫는 연출이 끝나기를 기다리지 않는다 — 사라지는 모습이 잘린다");
        assertTrue(js.contains("if (ov._cfSeq === seq) ov.classList.remove('open', 'closing');"), "닫히는 사이 다시 열면 뒤늦은 떼기가 새 창을 닫는다");
        assertTrue(js.contains("Common.reduceMotion()) { fin(); return; }"), "동작 줄이기에서 끝 이벤트를 기다린다");
        int open = css.indexOf(".cf-modal.open .modal-box {"), closing = css.indexOf(".cf-modal.closing .modal-box {");
        assertTrue(open > 0 && closing > open, ".closing 규칙이 .open 뒤에 없다 — 사라지는 동안 나타나기 연출이 이긴다");
        assertTrue(css.contains(".cf-modal.closing { animation: cfOvOut .14s ease-in both; pointer-events: none; }"), "사라지는 창이 클릭을 받는다");
        assertTrue(css.contains(".cf-modal.open, .cf-modal.open .modal-box, .cf-modal.closing, .cf-modal.closing .modal-box { animation: none; }"),
                "동작 줄이기에서 연출을 끄지 않는다");

        // 문구
        assertTrue(html.contains("함수 이름과 소스 라인까지 나옵니다."), "'소스 라인' 문구");
        assertFalse(html.contains("소스 줄"), "'소스 줄' 이 남아 있다");
    }

    @Test
    @DisplayName("템플릿이 부르는 전역 함수가 core-dump-index.js 에 모두 노출돼 있다")
    void inlineHandlersAreExported() throws IOException {
        String tpl = Files.readString(Path.of("src/main/resources/templates/core-dump/index.html"), StandardCharsets.UTF_8);
        String js = Files.readString(Path.of("src/main/resources/static/js/core-dump-index.js"), StandardCharsets.UTF_8);
        Set<String> names = new HashSet<>();
        Matcher attr = Pattern.compile("on(?:click|change)=\"([^\"]*)\"").matcher(tpl);
        Pattern call = Pattern.compile("(?<![.\\w])([A-Za-z_]\\w*)\\(");
        while (attr.find()) {
            Matcher c = call.matcher(attr.group(1));
            while (c.find()) names.add(c.group(1));
        }
        names.remove("if");
        names.remove("toggleMobileBanner");   // 상단바 메뉴 버튼 — banner.html 이 제공(이 화면 JS 소관 아님)
        assertTrue(names.size() >= 10, "핸들러 추출이 비었다: " + names);
        for (String n : names) {
            assertTrue(js.contains("window." + n + " = " + n + ";"), "core-dump-index.js 가 window." + n + " 을 노출하지 않는다");
        }
    }
}

package com.heapdump.analyzer;

import com.heapdump.analyzer.model.GcLogResult;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.parser.gclog.GcLogResultCodec;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC 로그 페이지 렌더 스모크 (2026-09-14) — {@code gc-log/index} · {@code gc-log/analyze} 를 실제 엔진으로 렌더한다
 * (함정 23: 인라인 {@code [[} 파싱 실패는 헤드리스 픽스처로는 못 잡는다). 빈 모델·채운 모델 모두 끝까지 렌더돼야 한다.
 *
 * <p>함께 고정하는 JS 배선: 분석 확인 모달의 {@code gclog} kind(라벨·이동 경로·확장자 경고 제외), 스캔 패널의 kind 분기,
 * 업로드 큐의 {@code /api/gc-log/upload} 분기, 배너 메뉴, 이력 캘린더 attach 1회.
 */
class GcLogTemplateSmokeTest {

    public static final class CsrfStub {
        public String getToken()         { return "test-token"; }
        public String getHeaderName()    { return "X-CSRF-TOKEN"; }
        public String getParameterName() { return "_csrf"; }
    }

    private static String render(String template, Map<String, Object> model) {
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
        ctx.setVariable("_csrf", new CsrfStub());
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    private static String js(String name) throws IOException {
        return Files.readString(Path.of("src/main/resources/static/js/" + name), StandardCharsets.UTF_8);
    }

    private static GcLogAnalysisEntity entity(String fn, String status, String server, String matched, String source) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename(fn);
        e.setStatus(status);
        e.setServerName(server);
        e.setFileSize(56_669L);
        e.setCompressed(fn.endsWith(".gz"));
        e.setCreatedAt(LocalDateTime.of(2026, 9, 14, 10, 53));
        if ("SUCCESS".equals(status)) {
            e.setAnalyzedAt(LocalDateTime.of(2026, 9, 14, 10, 54));
            e.setCollector("G1"); e.setJdkVersion("21+35-2513"); e.setLogFormat("UNIFIED"); e.setTimeSource("absolute");
            e.setLogStart(LocalDateTime.of(2026, 9, 14, 0, 52, 33)); e.setLogEnd(LocalDateTime.of(2026, 9, 14, 10, 53, 43));
            e.setEventCount(47); e.setFullGcCount(0); e.setFindingsCount(2); e.setMaxPauseMs(261.783); e.setP99PauseMs(261.783);
            e.setThroughputPct(99.99); e.setMaxHeapBytes(314572800L); e.setSeverity("Medium");
            e.setAnalysisTimeMs(1530L);   // 1초 이상 → '1.5초' 분기(#numbers.formatDecimal)
        }
        if ("ERROR".equals(status)) e.setErrorMessage("NOT_GC_LOG: 형식 인식 실패");
        e.setMatchedDumpFilename(matched);
        e.setMatchSource(source);
        e.setMatchReason(matched != null ? "server+time" : null);
        e.setMatchCandidates(matched == null && "none".equals(source) ? "[{\"dumpFilename\":\"x.hprof\",\"score\":1.0,\"reasons\":[\"server\"]}]" : "[]");
        return e;
    }

    @Test
    @DisplayName("index: 빈 모델과 채운 모델(파일 패널·이력 12열·연결 덤프 배지 3종) 모두 끝까지 렌더된다")
    void indexRenders() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("gcLogFiles", Collections.emptyList());
        empty.put("history", Collections.emptyList());
        empty.put("isAdmin", true);
        String h0 = render("gc-log/index", empty);
        assertTrue(h0.trim().endsWith("</html>"), "빈 모델 렌더 미완");
        assertTrue(h0.contains("id=\"gcDropZone\"") && h0.contains("id=\"gcServerName\""), "업로드 존·서버명 입력");
        assertTrue(h0.contains("서버에 GC 로그 파일이 없습니다") && h0.contains("분석 이력이 없습니다"));
        assertFalse(h0.contains("[["), "인라인 표현식으로 해석될 '[[' 가 남아 있다");

        List<GcLogAnalysisEntity> items = List.of(
                entity("gc.log.0", "SUCCESS", "was01", "app_20260914.hprof", "auto"),
                entity("gc.log.1.gz", "SUCCESS", "was01", "app_20260913.hprof", "manual"),
                entity("gc-2026-09-13.log", "NOT_ANALYZED", null, null, "none"),
                entity("bad.log", "ERROR", "was02", null, "manual"),
                entity("gc.log.5", "ANALYZING", "was02", null, "none"));
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("gcLogFiles", items);
        full.put("history", items);
        full.put("isAdmin", false);
        String h = render("gc-log/index", full);
        assertTrue(h.trim().endsWith("</html>"), "채운 모델 렌더 미완");
        assertTrue(h.contains("id=\"glFileList\"") && h.contains("data-filename=\"gc.log.0\""), "파일 패널");
        assertTrue(h.contains("data-uploaded=\"2026-09-14T10:53\""), "data-uploaded 가 ISO 로 렌더되지 않았다");
        assertTrue(h.contains(">자동</span>") && h.contains(">수동</span>") && h.contains("후보 있음") && h.contains("해제됨"), "연결 덤프 배지 3종 + 해제");
        assertTrue(h.contains("href=\"/analyze/app_20260914.hprof\""), "연결 덤프 링크");
        assertTrue(h.contains("class=\"hi-sev sev-Medium\""), "심각도 배지");
        assertTrue(h.contains("uptime 기준") || h.contains("00:52 ~"), "로그 기간 셀");
        assertTrue(h.contains("data-kind=\"gclog\"") && h.contains("AnalyzeConfirm.fromLink(this)"), "미분석 '분석' 링크는 확인 모달을 거친다");
        assertTrue(h.contains("id=\"deleteGcModal\"") && h.contains("class=\"modal-ov gcl-modal\""), "삭제 모달 골격 클래스(함정 17)");
        assertTrue(h.contains("/js/analyze-confirm.js") && h.indexOf("/js/analyze-confirm.js") < h.indexOf("/js/gc-log-index.js"), "모듈 스크립트 순서");
        assertFalse(h.contains("[["), "인라인 표현식으로 해석될 '[[' 가 남아 있다");
    }

    @Test
    @DisplayName("analyze: NOT_ANALYZED/ANALYZING/ERROR/SUCCESS 네 상태 모두 렌더되고 SUCCESS 는 결과 JSON 을 싣는다")
    void analyzeRenders() {
        for (String st : new String[]{"NOT_ANALYZED", "ANALYZING", "ERROR", "MISSING"}) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", "gc.log.0");
            m.put("entity", "MISSING".equals(st) ? null : entity("gc.log.0", "ERROR".equals(st) ? "ERROR" : st, "was01", null, "none"));
            m.put("fileExists", !"MISSING".equals(st));
            m.put("status", st);
            m.put("result", null);
            m.put("resultJson", "null");
            m.put("candidates", List.of());
            m.put("instance", com.heapdump.analyzer.service.GcLogInstanceService.view(null, null, null));
            String h = render("gc-log/analyze", m);
            assertTrue(h.trim().endsWith("</html>"), st + " 렌더 미완");
            if ("ERROR".equals(st)) {
                // 실패 기록 화면: 진행 막대('분석을 시작합니다' + 0%)를 숨기고 오류 상자 안에 '다시 분석' 버튼 — 옛 오류가 시작된 것처럼 보이면 안 된다
                assertTrue(h.contains("id=\"gclProgress\" style=\"display:none\""), "ERROR: 진행 블록 숨김");
                assertTrue(h.contains("id=\"gclRetryBtn\" onclick=\"reanalyze()\""), "ERROR: 오류 상자의 다시 분석 버튼");
            } else {
                assertTrue(h.contains("id=\"gclProgress\"") && !h.contains("id=\"gclProgress\" style=\"display:none\""), st + ": 진행 블록 보임");
            }
            if ("MISSING".equals(st)) assertFalse(h.contains("id=\"gclRetryBtn\""), "파일이 없으면 다시 분석 버튼 없음");
            assertTrue(h.contains("<script type=\"application/json\" id=\"gcResult\">null</script>"), st + ": 결과 JSON null");
            assertFalse(h.replace("/*[[", "").contains("[["), st + ": '[[' 잔존");
        }

        GcLogResult r = new GcLogResult();
        r.getMeta().setFormat("UNIFIED"); r.getMeta().setCollector("G1"); r.getMeta().setDurationSec(36069.8); r.getMeta().setTimeSource("absolute");
        r.getKpi().setEventCount(47); r.getKpi().setThroughputPct(99.99); r.getKpi().setSeverity("Medium");
        GcLogResult.Finding f = new GcLogResult.Finding();
        f.setCode("HUMONGOUS_HEAVY"); f.setSeverity("Medium"); f.setTitle("Humongous <script>alert(1)</script>"); f.setDetail("d"); f.setAdvice("a");
        r.getFindings().add(f);
        r.getRawSample().getHead().add("</script><b>x</b>");   // 결과 JSON 안의 닫힘 태그 — 컨트롤러가 '</' 를 이스케이프한다
        String json = GcLogResultCodec.toJson(r).replace("</", "<\\/");
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("filename", "gc.log.0");
        ok.put("entity", entity("gc.log.0", "SUCCESS", "was01", "app_20260914.hprof", "auto"));
        ok.put("fileExists", true);
        ok.put("status", "SUCCESS");
        ok.put("result", r);
        ok.put("resultJson", json);
        ok.put("candidates", List.of());
        ok.put("instance", com.heapdump.analyzer.service.GcLogInstanceService.view(null, "app_20260914.hprof", "server1"));
        String h = render("gc-log/analyze", ok);
        assertTrue(h.trim().endsWith("</html>"), "SUCCESS 렌더 미완");
        assertTrue(h.contains("id=\"gclProgress\" style=\"display:none\""), "SUCCESS: 진행 블록 숨김");
        // 헤더 카드(2026-09-14): 메타 한 줄이 페이지 최상단에 붙고 카드가 왼쪽에 붙던 결함 — 배너 폭을 뺀 영역 가운데 정렬 래퍼 + 알약형 메타
        assertTrue(h.contains("<div class=\"gcl-page\"><div class=\"gcl-page-inner\">"),
                "가운데 정렬 래퍼 gcl-page > gcl-page-inner");
        assertFalse(h.contains("class=\"cd-page\""), "cd-page 인라인 max-width 래퍼는 배너 padding 이 없어 좌측에 붙는다");
        assertTrue(h.contains("class=\"gcl-headcard\"") && h.contains("id=\"gclMeta\""), "헤더 카드 + 메타 영역");
        assertTrue(h.contains("<span class=\"gcl-meta-k\">서버</span>") && h.contains("<span class=\"gcl-meta-k\">분석</span>"), "메타는 키·값 알약");
        assertTrue(h.contains("연결된 힙 덤프"), "매칭 칩 라벨");
        assertTrue(h.contains(">1.5초</span>"), "소요 시간 초 단위 표기");
        assertTrue(h.contains("id=\"gclResult\"") && h.contains("id=\"gclHeapChart\"") && h.contains("id=\"gclEvTable\""), "결과 섹션");
        int js = h.indexOf("<script type=\"application/json\" id=\"gcResult\">");
        int jsEnd = h.indexOf("</script>", js);
        String payload = h.substring(js, jsEnd);
        assertTrue(payload.contains("HUMONGOUS_HEAVY"), "결과 JSON 이 실렸다");
        assertFalse(payload.contains("</script>"), "JSON 안의 '</script>' 가 살아 있으면 스크립트 블록이 조기 종료된다");
        assertTrue(payload.contains("<\\/script>"), "이스케이프된 닫힘 태그");
        assertTrue(h.contains("app_20260914.hprof") && h.contains(">자동</span>"), "매칭 칩");
        assertTrue(h.contains("id=\"matchPickModal\"") && h.contains("id=\"matchPickList\""), "힙 덤프 선택 모달");
        assertTrue(h.contains("/js/lib/chart.umd.min.js") && h.contains("/js/table-grid.js"), "Chart.js·table-grid 로드");
        assertTrue(h.indexOf("/js/table-grid.js") < h.indexOf("/js/gc-log-analyze.js"), "모듈 스크립트 순서");
        // KPI 인스턴스 카드 초기값(2026-09-14) — 수동 입력 > 연결된 힙 덤프. 문자열로 나눠 실린다
        assertTrue(h.contains("name: \"server1\"") && h.contains("source: \"dump\"") && h.contains("dumpFilename: \"app_20260914.hprof\""),
                "GC_INSTANCE 초기값");
        assertTrue(h.indexOf("var GC_INSTANCE") < h.indexOf("/js/gc-log-analyze.js"), "인라인 변수가 외부 JS 보다 먼저");
    }

    @Test
    @DisplayName("JS 배선: 확인 모달 gclog kind · 스캔 패널 kind 분기 · 업로드 큐 분기 · 캘린더 attach 1회 · 폴링은 managedInterval")
    void jsWiring() throws IOException {
        String ac = js("analyze-confirm.js");
        assertTrue(ac.contains("gclog: 'GC 로그'"), "KIND_LABEL.gclog");
        assertTrue(ac.contains("if (kind === 'gclog') return '/gc-log/analyze/' + encodeURIComponent(filename) + '?start=1';"),
                "gclog 는 결과 페이지로 + ?start=1 — 없으면 실패(ERROR) 파일은 옛 오류만 보이고 분석 요청이 나가지 않는다(2026-09-14 실제 결함)");
        assertTrue(ac.contains("kind !== 'core' && kind !== 'gclog' && !hasHeapDumpExtension"), "GC 로그는 확장자 경고 제외");
        assertTrue(ac.contains("if (kind === 'core' || kind === 'gclog') { queueSeq++; els.queue.hidden = true; }"), "MAT 대기열 패널 숨김");

        String scan = js("server-scan.js");
        assertTrue(scan.contains("var isGc = f.fileType === 'gclog';") && scan.contains("kind: isCore ? 'core' : isGc ? 'gclog' : 'heap'"), "스캔 패널 kind 분기");
        assertTrue(scan.contains("badge-gclog") && scan.contains("badge-active"), "GC LOG·기록 중 배지");

        String uq = js("upload-queue.js");
        assertTrue(uq.contains("item.fileType === 'gclog'") && uq.contains("'/api/gc-log/upload'") && uq.contains("fdg.append('gcLogFile'"), "업로드 큐 gclog 분기");
        assertTrue(uq.contains("function isGcLogFilename"), "GC 로그 파일명 휴리스틱");

        String idx = js("gc-log-index.js");
        assertTrue(idx.contains("startInputId: 'hiDateStart', endInputId: 'hiDateEnd'") && idx.contains("areaId: 'hiCalArea'"), "캘린더 attach 인자");
        assertEquals(1, idx.split("Calendar\\.attach\\(", -1).length - 1, "calendar.js 는 페이지당 인스턴스 1개");
        assertTrue(idx.contains("storageKey: 'gcLogHistory'"), "코어덤프 이력과 다른 localStorage 키");

        String an = js("gc-log-analyze.js");
        assertTrue(an.contains("get('start') === '1'") && an.contains("history.replaceState(null, document.title, window.location.pathname)"),
                "?start=1 은 한 번만 쓰고 주소에서 지운다(새로고침으로 재시작 금지)");
        assertTrue(an.contains("} else if (GC_STATUS === 'ERROR' && wantStart && GC_FILE_EXISTS) {"), "실패 파일도 확인 모달을 거쳐 오면 분석을 시작한다");
        String idxHtml = Files.readString(Path.of("src/main/resources/templates/gc-log/index.html"), StandardCharsets.UTF_8);
        assertTrue(idxHtml.contains("@{/gc-log/analyze/{fn}(fn=${item.filename},start=1)}"), "모듈 미로드 폴백 href 도 start=1");
        assertTrue(an.contains("SessionTimeout.managedInterval(tick, 1000)") && an.contains("SessionTimeout.registerActivityGuard"), "폴링은 함정 38 규약");
        assertTrue(an.contains("detach: true"), "이벤트 표는 table-grid detach 모드");
        // 인스턴스 카드(2026-09-14): KPI 마지막 칸 · 수동 편집 POST · 연결 변경 응답(/match 계열)으로도 갱신
        assertTrue(an.contains("h += INSTANCE_CARD;") && an.contains("renderInstance(_instance);"), "renderKpi 가 인스턴스 카드를 붙이고 초기값을 그린다");
        assertTrue(an.contains("id=\"gclInstCard\"") && an.contains("onclick=\"startInstanceEdit()\"") && an.contains("aria-label=\"인스턴스명 편집\""), "카드 골격·편집 버튼 접근성 이름");
        assertTrue(an.contains("Common.fetchJSON(api('/instance'), { method: 'POST', body: JSON.stringify({ instance: input.value }) })"), "수동 입력 저장 경로");
        assertTrue(an.contains("if (!d || d.success !== true)"), "응답 success 확인(함정 27)");
        assertTrue(an.contains("if (v && v.instance) renderInstance(v.instance);"), "매칭 칩 갱신이 인스턴스 카드도 갱신한다");
        assertTrue(an.contains("window.startInstanceEdit = startInstanceEdit;"), "인라인 onclick 전역 노출");
        assertTrue(an.contains("if (ev.isComposing) return;"), "한글 조합 중 Enter 로 저장되지 않는다");
        String css = Files.readString(Path.of("src/main/resources/static/css/gc-log.css"), StandardCharsets.UTF_8);
        assertTrue(css.contains(".gcl-kpi-inst { position: relative; }") && css.contains(".gcl-inst-form {") && css.contains(".gcl-kpi-edit {"), "인스턴스 카드 CSS");
        assertFalse(an.contains("setInterval(tick, 1000)") && !an.contains("SessionTimeout.managedInterval"), "managedInterval 없이 setInterval 만 쓰면 안 된다");

        // 업로드 버튼 문구는 선택 상태를 따라간다(2026-09-14) — 파일을 골라도 '선택하세요' 로 남던 결함
        assertTrue(idx.contains("el.textContent = 'GC 로그 파일을 선택하세요'") && idx.contains("CHART_ICON + ' GC 로그 분석 시작'")
                && idx.contains("'업로드 중 ' + detail") && idx.contains("'분석 시작 중…'"), "CTA 4상태");
        assertTrue(idx.contains("setCta('ready')") && idx.contains("setCta('empty')") && idx.contains("setCta('starting')"), "파일 선택·해제·성공 시 문구 갱신");
        assertTrue(idxHtml.contains("<span class=\"usb-label\" id=\"uploadBtnLabel\">GC 로그 파일을 선택하세요</span>"), "라벨 span id");

        String banner = Files.readString(Path.of("src/main/resources/templates/fragments/banner.html"), StandardCharsets.UTF_8);
        assertTrue(banner.contains("href=\"/gc-log\" class=\"gb-nav-link\" id=\"gbNavGcLog\"") && banner.contains("path.startsWith('/gc-log')"), "배너 메뉴 + 활성화");
    }

    @Test
    @DisplayName("Files 페이지 GC Log 탭·분류 계약: 탭·배지·GC 전용 동작(보기/다운로드/삭제)·'GC로그' 분류 옵션과 이동 안내 · 목록에 GC 로그 병합")
    void filesPageGcLogTabContract() throws IOException {
        String f = Files.readString(Path.of("src/main/resources/templates/files.html"), StandardCharsets.UTF_8);
        int tGc = f.indexOf("data-tab=\"gclog\""), tCore = f.indexOf("data-tab=\"coredump\""), tOthers = f.indexOf("data-tab=\"others\"");
        assertTrue(tGc > 0 && tCore > 0 && tOthers > 0 && tCore < tGc && tGc < tOthers, "탭 순서 Corefile → GC Log → Others");
        assertTrue(f.contains("id=\"tabCntGc\"") && f.contains("getElementById('tabCntGc').textContent"), "탭 건수");
        assertTrue(f.contains("th:if=\"${h.fileType == 'gclog'}\" class=\"gclog-badge\">GC LOG</span>"), "GC LOG 배지");
        assertTrue(f.contains("@{/gc-log/analyze/{f}(f=${h.filename},start=1)}"), "미분석·실패 분석 버튼은 ?start=1 (모듈 미로드 폴백 포함)");
        assertTrue(f.contains("'/api/gc-log/download/' + encodeURIComponent("), "GC 로그 다운로드는 GC 저장소 엔드포인트 — 힙 /download 는 파일을 못 찾는다");
        assertTrue(f.contains("function confirmGcLogDelete(") && f.contains("'/api/gc-log/' + encodeURIComponent(") && f.contains("?deleteFile=true"),
                "GC 로그 삭제는 GC API — 힙 bulk-delete 는 dumpfiles 만 본다");
        assertTrue(f.contains("<option value=\"gclog\">GC로그</option>"), "파일 분류 설정 'GC로그' 옵션");
        assertTrue(f.contains("id=\"classifyGcIn\"") && f.contains("id=\"classifyGcOut\"") && f.contains("function onClassifyTypeChange"),
                "분류가 파일 이동이라는 안내(들어옴/나감)");
        assertEquals(2, f.split("gclog:\\s*'gclog'", -1).length - 1, "탭 클릭·저장된 탭 복원 두 곳의 모드 매핑");

        String view = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java"), StandardCharsets.UTF_8);
        assertTrue(view.contains("combined.addAll(buildGcLogHistory(isAdmin));"), "filesPage 가 GC 로그 목록을 병합");
        String api = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/controller/GcLogApiController.java"), StandardCharsets.UTF_8);
        assertTrue(api.contains("/api/gc-log/download/{filename:.+}"), "GC 로그 다운로드 엔드포인트");
    }

    @Test
    @DisplayName("대시보드 업로드: 파일명 규칙에 안 맞는 GC 로그도 앞 64KB 내용으로 분류 — JS 판별 규칙은 서버 GcLogFormatDetector 와 같다")
    void dashboardUploadSniffsGcLogContent() throws IOException {
        String uq = js("upload-queue.js");
        assertTrue(uq.contains("function sniffGcLogs(files)") && uq.contains("f.slice(0, _SNIFF_BYTES)") && uq.contains("_SNIFF_BYTES = 64 * 1024"),
                "앞 64KB 만 읽는다(수 GB 파일도 slice)");
        assertTrue(uq.contains("return sniffGcLogs(list).then(") || uq.contains("sniffGcLogs(list).then("), "큐 적재 전에 판별");
        assertTrue(uq.contains("if (_gcByContent && typeof file !== 'string' && _gcByContent.has(file)) return 'gclog';"), "내용 판별 결과 반영");
        assertTrue(uq.indexOf("if (isRecognizedHeapExt(name)) return 'heapdump';") < uq.indexOf("_gcByContent.has(file)"),
                "힙 확장자가 내용 판별보다 우선(.hprof 는 읽지 않는다)");

        // 정규식 동등성: JS 리터럴을 그대로 뽑아 Java 로 컴파일해 같은 표본에서 서버 판별기와 결론이 같은지 본다
        java.util.function.Function<String, java.util.regex.Pattern> lit = name -> {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("var " + name + " = /(.+)/;").matcher(uq);
            assertTrue(m.find(), name + " 리터럴");
            return java.util.regex.Pattern.compile(m.group(1));
        };
        java.util.regex.Pattern uLine = lit.apply("_UNIFIED_LINE"), uTag = lit.apply("_UNIFIED_GC_TAG"),
                j8 = lit.apply("_JDK8_LINE"), j8h = lit.apply("_JDK8_HEADER");
        java.util.function.Predicate<String> jsSniff = text -> {
            for (String l : text.split("\\r?\\n")) {
                if (l.isEmpty()) continue;
                if (uLine.matcher(l).lookingAt() && uTag.matcher(l).find()) return true;
                if (j8.matcher(l).find() || j8h.matcher(l).find()) return true;
            }
            return false;
        };
        String[] samples = {
                "[2026-09-14T00:52:33.485+0900][0.003s][info][gc] Using G1",
                "[0.328s][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->4M(256M) 3.1ms",
                "[1.2s][info][gc,heap     ] GC(3) Eden regions: 12->0(14)",
                "2026-09-14T00:52:33.485+0900: 0.325: [GC (Allocation Failure) [PSYoungGen: 65536K->10720K(76288K)] 65536K->10744K(251392K), 0.0101 secs]",
                "0.512: [Full GC (Metadata GC Threshold) 1024K->512K(4096K), 0.02 secs]",
                "12.001: [CMS-concurrent-mark-start]",
                "OpenJDK 64-Bit Server VM (25.392-b08) for linux-amd64 JRE (1.8.0_392-b08), built on Oct 17 2023",
                "2026-09-14 00:00:00 INFO application started",
                "[2026-09-14][info][app] started",
                "[main] [INFO] [gcService] ok",
                "GC overhead limit exceeded",
        };
        for (String smp : samples) {
            boolean server = com.heapdump.analyzer.parser.gclog.GcLogFormatDetector.sniff(List.of(smp))
                    != com.heapdump.analyzer.parser.gclog.GcLogFormat.UNKNOWN;
            assertEquals(server, jsSniff.test(smp), "JS·서버 판별 불일치: " + smp);
        }

        String idx = Files.readString(Path.of("src/main/resources/templates/index.html"), StandardCharsets.UTF_8);
        assertTrue(idx.contains("'.hprof,.bin,.dump,.gz,.log,.gclog,.txt'") && idx.contains("· GC 로그 · Max"), "대시보드 업로드 안내·accept");
    }
}

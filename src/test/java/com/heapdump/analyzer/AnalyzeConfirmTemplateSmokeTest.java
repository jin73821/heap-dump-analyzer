package com.heapdump.analyzer;

import com.heapdump.analyzer.model.entity.TargetServer;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 분석 시작 확인 모달(/js/analyze-confirm.js) · 스캔 결과 패널(/js/server-scan.js) 배선 회귀 (2026-09-13).
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>Dashboard/Files 의 Analyze 버튼은 href 로 바로 이동하지 않고 확인 모달을 연다. 단 모듈이 로드되지
 *       않았으면 href 로 떨어진다(버튼이 먹통이 되면 안 된다).</li>
 *   <li>Others 전용 경고 모달은 공통 모달의 {@code kind='others'} 로 통합됐다 — 페이지별 사본이 되살아나면 두 모달이 갈린다.</li>
 *   <li>서버 정보 페이지의 '스캔'이 토스트만 띄우지 않고 결과 패널을 그린다(종전 결함).</li>
 *   <li>모듈 {@code <script>} 는 그 전역을 쓰는 인라인 스크립트보다 먼저 온다.</li>
 * </ul>
 * JS 동작 자체는 헤드리스 픽스처로 확인했다(JS 러너 없음).
 */
class AnalyzeConfirmTemplateSmokeTest {

    private static final String ONCLICK = "return window.AnalyzeConfirm ? AnalyzeConfirm.fromLink(this) : true;";

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
        model.forEach(ctx::setVariable);
        return engine.process(template, ctx);
    }

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/resources/templates/" + name), StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static com.heapdump.analyzer.model.dto.RecentFileItem recentItem(String name, boolean compressed, String status, boolean others) {
        long mb = 1024L * 1024;
        com.heapdump.analyzer.model.HeapDumpFile f = new com.heapdump.analyzer.model.HeapDumpFile(
                name, "/h/" + name, compressed ? 45 * mb : 157 * mb, 0L, compressed, compressed ? 997 * mb : 157 * mb, compressed ? 45 * mb : 0L);
        return new com.heapdump.analyzer.model.dto.RecentFileItem(f, "heap", status, others);
    }

    private static Map<String, Object> dashboardFile(String name, boolean compressed) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("extension", "HPROF");
        f.put("compressed", compressed);
        f.put("formattedSize", "157 MB");
        f.put("formattedCompressedSize", "45 MB");
        f.put("formattedOriginalSize", "997 MB");
        f.put("formattedDate", "09-13 12:00");
        return f;
    }

    @Test
    @DisplayName("Dashboard: 미분석 파일의 Analyze 버튼이 확인 모달을 열고, Others 는 같은 모달의 others 유형이다")
    void dashboardAnalyzeButtonsOpenConfirmModal() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("files", List.of(dashboardFile("plain.hprof", false), dashboardFile("mystery", false),
                dashboardFile("packed.hprof.gz", true), dashboardFile("done.hprof", false)));
        m.put("fileCount", 4);
        // Recent Files 는 recentFiles(RecentFileItem — 힙 + GC 로그, 2026-09-14)가 그린다. 크기 표기는 실제 HeapDumpFile 포맷
        m.put("recentFiles", List.of(recentItem("plain.hprof", false, "NOT_ANALYZED", false), recentItem("mystery", false, "NOT_ANALYZED", true),
                recentItem("packed.hprof.gz", true, "NOT_ANALYZED", false), recentItem("done.hprof", false, "SUCCESS", false)));
        m.put("recentCount", 4);
        m.put("totalSize", "1 GB");
        m.put("analyzedCount", 1L);
        m.put("totalSuspects", 0L);
        m.put("analyzedFiles", Set.of("done.hprof"));
        m.put("errorFiles", Collections.emptySet());
        m.put("othersFiles", Set.of("mystery"));
        m.put("allowAllExtensions", false);
        m.put("maxUploadSizeGb", 5);
        m.put("maxUploadSizeBytes", 5L * 1024 * 1024 * 1024);
        m.put("diskUsedPercent", 42);
        m.put("hasDetections", false);
        m.put("detectionItems", Collections.emptyList());
        m.put("dailyDetections", Collections.emptyList());
        m.put("serverSeries", Collections.emptyList());
        m.put("dashboardDetectDays", 14);

        String raw = render("index", m);
        assertTrue(raw.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        String html = raw.replaceAll("\\s+", " ");   // 속성 사이 개행·들여쓰기 무시

        // 미분석 3건(plain/mystery/packed)만 Analyze 버튼 — 분석 완료 done.hprof 는 View 버튼
        assertEquals(3, count(html, ONCLICK), "미분석 파일마다 확인 모달 onclick 이 있어야 한다");
        assertTrue(html.contains("href=\"/analyze/plain.hprof\" class=\"fb p\" title=\"Analyze\" data-filename=\"plain.hprof\" data-kind=\"heap\""),
                "일반 힙 덤프 Analyze 버튼 — JS 미로드 폴백용 href 와 data-kind=heap");
        assertTrue(html.contains("data-filename=\"mystery\" data-kind=\"others\""), "Others 파일은 kind=others 로 열어야 경고 블록이 붙는다");
        assertTrue(html.contains("data-size=\"997.00 MB (GZ 45.00 MB)\""), "압축 파일은 원본·GZ 크기를 함께 보여준다");

        assertFalse(html.contains("othersAnalyzeModal"), "페이지 전용 Others 모달이 되살아났다 — 공통 모달과 갈린다");
        assertFalse(html.contains("confirmOthersAnalyze"), "제거된 전역 함수를 여전히 호출한다");
        int mod = html.indexOf("src=\"/js/analyze-confirm.js");
        assertTrue(mod > 0, "analyze-confirm.js 가 로드되지 않는다");
    }

    @Test
    @DisplayName("확인 모달: 힙 덤프 확장자가 아니면 유형과 무관하게 진행 여부를 되묻는다 (코어 제외)")
    void nonHeapExtensionAsksBeforeProceeding() throws IOException {
        // JS 러너가 없어 소스 계약만 고정한다 — 판정 표(zip/tar.gz/단독 gz/무확장자 → 경고)는 헤드리스 픽스처로 확인
        String js = Files.readString(Path.of("src/main/resources/static/js/analyze-confirm.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("var HEAP_EXT_RE = /\\.(hprof|bin|dump|dmp)(\\.gz)?$/i;"),
                "힙 덤프 확장자 목록이 업로드 큐·FilenameValidator 와 어긋났다 (단독 .gz 는 인정하지 않는다)");
        assertTrue(js.contains("var extWarn = kind !== 'core' && kind !== 'gclog' && !hasHeapDumpExtension(opts.filename);"),
                "경고 조건이 kind==='others' 로 되돌아가면 '파일 분류'로 heapdump 가 된 비-덤프 파일에서 경고가 빠진다");
        assertTrue(js.contains("덤프 파일 확장자가 아닌데 분석을 진행하시겠습니까?"), "진행 여부를 묻는 문구가 없다");
        assertTrue(js.contains("'그래도 분석 시작'"), "되묻는 경우의 확인 버튼 문구");
        assertTrue(js.contains("(extWarn ? els.cancel : els.ok).focus();"), "되묻는 경우 Enter 한 번으로 진행되면 안 된다(기본 포커스=취소)");
    }

    @Test
    @DisplayName("Files: 힙 덤프·Others 의 Analyze 버튼이 확인 모달을 연다 (Others 모달 통합)")
    void filesAnalyzeButtonsOpenConfirmModal() throws IOException {
        // files.html 은 모델이 커서 소스 계약만 본다 — 렌더는 기존 운영 경로에서 검증됨
        String src = source("files.html");
        assertEquals(3, count(src, ONCLICK), "heapdump NOT_ANALYZED · others/exec NOT_ANALYZED · gclog 미분석/실패 세 곳");
        assertTrue(src.contains("th:data-filename=\"${h.filename}\" data-kind=\"heap\""), "heapdump 버튼 kind");
        assertTrue(src.contains("th:data-filename=\"${h.filename}\" data-kind=\"others\""), "others/exec 버튼 kind");
        assertTrue(src.contains("th:data-filename=\"${h.filename}\" data-kind=\"gclog\""), "GC Log 탭 분석 버튼 kind (2026-09-14)");
        assertFalse(src.contains("othersAnalyzeModal") || src.contains("confirmOthersAnalyze"),
                "페이지 전용 Others 모달이 남아 있다");
        assertTrue(src.indexOf("src=\"/js/analyze-confirm.js") > 0, "analyze-confirm.js 가 로드되지 않는다");
    }

    @Test
    @DisplayName("서버 정보: 스캔 결과 패널이 렌더되고 모듈 스크립트가 인라인 사용처보다 앞선다")
    void serverDetailRendersScanPanel() {
        TargetServer server = new TargetServer();
        server.setId(7L);
        server.setName("WAS-01");
        server.setHost("192.168.56.10");
        server.setPort(22);
        server.setSshUser("sscuser");
        server.setDumpPath("/opt/dumps\n/var/tmp");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_csrf", new CsrfStub());
        m.put("server", server);
        m.put("histories", Collections.emptyList());
        m.put("transferLogs", Collections.emptyList());
        m.put("analysisSuccess", 0L);
        m.put("analysisFailed", 0L);
        m.put("transferSuccess", 0L);
        m.put("transferFailed", 0L);

        String html = render("server-detail", m);
        assertTrue(html.trim().endsWith("</html>"), "문서 끝까지 렌더되지 않았다 (파싱 중단 의심)");
        assertTrue(html.contains("<div id=\"scanPanel\"></div>"), "스캔 결과 패널 호스트가 없다 — 스캔이 다시 토스트만 띄운다");
        assertTrue(html.contains("data-id=\"7\" data-name=\"WAS-01\""), "스캔 버튼이 서버명을 넘기지 않는다(패널 제목·모달 출처 서버)");

        int confirm = html.indexOf("src=\"/js/analyze-confirm.js");
        int scan = html.indexOf("src=\"/js/server-scan.js");
        int use = html.indexOf("ServerScan.create(");
        assertTrue(confirm > 0 && scan > confirm && use > scan,
                "스크립트 순서: analyze-confirm.js → server-scan.js → 인라인 ServerScan.create");

        // 스캔 대상 선택 팝오버(2026-09-16) — 버튼이 대상 설정을 data-* 로 싣고 팝오버를 연다
        assertTrue(html.contains("onclick=\"openScanPicker(this)\"") && html.contains("aria-haspopup=\"dialog\""), "스캔 버튼은 대상 선택 팝오버를 연다");
        assertTrue(html.contains("data-scanheap=\"true\"") && html.contains("data-dumppath=\"/opt/dumps\n/var/tmp\"")
                && html.contains("data-scancore=\"false\"") && html.contains("data-scangclog=\"false\""), "대상 설정 data-*");
        assertTrue(html.contains("ServerScan.pickTargets(btn, {") && html.contains("_scanPanel.scan(id, name, types)"), "선택 결과로 스캔");
    }

    @Test
    @DisplayName("스캔 대상 선택: 모듈 팝오버·types 파라미터·선택 결과 오류는 서버 상태를 FAIL 로 바꾸지 않음 · 경로 줄 글자 12px")
    void scanTargetPickerModule() throws IOException {
        String js = Files.readString(Path.of("src/main/resources/static/js/server-scan.js"), StandardCharsets.UTF_8);
        assertTrue(js.contains("function pickTargets(anchor, opts) {") && js.contains("function targetsFromDataset(ds) {"), "팝오버·대상 해석 함수");
        assertTrue(js.contains("global.ServerScan = { create: create, pickTargets: pickTargets, targetsFromDataset: targetsFromDataset, closePicker: closePicker };"), "공개 API");
        assertTrue(js.contains("{ key: 'heap',  label: '힙 덤프',  flag: 'scanheap',  path: 'dumppath',     flagDefault: true }"), "scanHeap null = 켜짐(엔티티와 같다)");
        assertTrue(js.contains("'?types=' + encodeURIComponent(scanTypes.join(','))"), "선택 대상을 types 로 보낸다");
        assertTrue(js.contains("if (!noTarget) onStatus(id, 'FAIL', d.error);"), "NO_SCAN_TARGET 은 연결 실패가 아니다");
        assertTrue(js.contains("'.ss-pick{position:fixed;z-index:10000;") && js.contains("background:#fff;"), "흰 팝오버");
        assertTrue(js.contains("if (e.key === 'Escape') { e.preventDefault(); closePicker(true); }"), "Esc 로 닫고 버튼으로 포커스 복귀");
        assertTrue(js.contains("goBtn.disabled = n === 0;"), "대상이 없으면 스캔 버튼 비활성");
        assertTrue(js.contains("'.scan-src-path{flex:1 1 100%;font-size:12px;") && !js.contains("font-size:10px;color:#9CA3AF;font-family:monospace"),
                "스캔 결과 경로 줄은 12px(종전 10px)");
        // 전송 재시도(2026-09-16): 직전 시도의 진행바·오류 문구를 모두 걷어낸 뒤 새 진행바를 붙인다 — 종전엔 진행바가 시도마다 쌓였다
        int tf = js.indexOf("function transferFile(btn, callback) {");
        String tfBody = js.substring(tf, js.indexOf("var prog = document.createElement('div');", tf));
        assertTrue(tfBody.contains("parent.querySelectorAll('.transfer-progress, .transfer-err')") && tfBody.contains("parent.removeChild(el)"),
                "재시도 전에 이전 진행바·오류 문구 제거");
        assertFalse(tfBody.contains("var prevErr = parent.querySelector('.transfer-err');"), "오류 문구 하나만 지우던 종전 코드");
        assertTrue(js.contains("'.scan-size{color:#6B7280;font-size:13px;") && js.contains("'.scan-analyzed{color:#059669;font-size:12px;")
                && js.contains("'.transfer-err{font-size:12px;") && js.contains("'.transfer-label{font-size:12px;")
                && js.contains("html += '<span class=\"scan-size\">' + esc(f.formattedSize) + '</span>';"), "크기·전송 상태·오류 문구 글자 크기");

        String servers = source("servers.html");
        assertTrue(servers.contains("onclick=\"openScanPicker(this)\"") && servers.contains("function openScanPicker(btn) {"), "Target Servers 스캔 버튼 → 팝오버");
        assertTrue(servers.contains("targets: ServerScan.targetsFromDataset(row ? row.dataset : btn.dataset)"), "행의 대상 설정 data-* 사용");
        assertTrue(servers.contains("<script src=\"/js/float-tooltip.js"), "툴팁 모듈 로드(종전 누락 — 덤프 경로·실패 사유 툴팁이 안 떴다)");
        assertTrue(servers.contains("class=\"col-hide-sm col-dumppath\"") && servers.contains("th:attr=\"data-tooltip=${s.dumpPath}\"") && servers.contains("class=\"path-more\""),
                "덤프 경로 셀 툴팁 + '+N' 표시");
        assertTrue(servers.contains("'덤프 경로 ' + paths.length + '개\\n' + paths.map(function(p, i) { return (i + 1) + '. ' + p; }).join('\\n')"), "여러 경로는 번호 목록");
        assertTrue(servers.indexOf("initRows();\ndecorateDumpPathTooltips();") > 0, "로드 시 툴팁 문구 정리");
    }

    @Test
    @DisplayName("Target Servers: 인라인 스캔 구현이 제거되고 공통 모듈을 쓴다")
    void serversUsesSharedScanModule() throws IOException {
        String src = source("servers.html");
        assertTrue(src.contains("<div id=\"scanPanel\"></div>"), "스캔 패널 호스트");
        assertFalse(src.contains("function transferFile") || src.contains("function renderScanPage"),
                "인라인 스캔 구현이 남아 있다 — 모듈과 두 벌이 되면 서버 정보 페이지와 동작이 갈린다");
        int scan = src.indexOf("src=\"/js/server-scan.js");
        int use = src.indexOf("ServerScan.create(");
        assertTrue(src.indexOf("src=\"/js/analyze-confirm.js") > 0 && scan > 0 && use > scan, "모듈 스크립트 순서");
    }
}

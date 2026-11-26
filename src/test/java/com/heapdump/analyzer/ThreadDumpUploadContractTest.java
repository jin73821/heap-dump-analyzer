package com.heapdump.analyzer;

import com.heapdump.analyzer.parser.threaddump.ThreadDumpFormatDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 스레드 덤프 범주의 화면 배선 계약(2026-11-26) — 업로드 큐의 자동 분류, Files 탭·분류 모달, 분석 확인 모달, 대시보드 업로드 후 이동.
 *
 * <p>브라우저의 판별 규칙은 서버 {@link ThreadDumpFormatDetector} 와 <b>글자 그대로</b> 같아야 한다. 어긋나면 브라우저가
 * 스레드 덤프로 보낸 파일을 서버가 거부하거나(업로드 실패), 스레드 덤프인데 힙 저장소로 들어가 MAT 분석 대상이 된다.
 */
class ThreadDumpUploadContractTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String jsRegex(String js, String name) {
        Matcher m = Pattern.compile("var " + name + " = /(.*)/(i?);").matcher(js);
        assertTrue(m.find(), "upload-queue.js 에 " + name + " 가 없다");
        return m.group(1);
    }

    @Test
    @DisplayName("브라우저의 스레드 덤프 판별 정규식 = 서버 정규식")
    void sniffRegexesMatchServer() throws IOException {
        String uq = read("src/main/resources/static/js/upload-queue.js");
        assertEquals(ThreadDumpFormatDetector.NAME_RE, jsRegex(uq, "_TD_NAME_RE"), "파일명 규칙이 서버와 다르다");
        assertEquals(ThreadDumpFormatDetector.HEADER_RE, jsRegex(uq, "_TD_HEADER"), "헤더 마커가 서버와 다르다");
        assertEquals(ThreadDumpFormatDetector.THREAD_RE, jsRegex(uq, "_TD_THREAD"), "스레드 줄 마커가 서버와 다르다");
        assertEquals(ThreadDumpFormatDetector.STATE_RE, jsRegex(uq, "_TD_STATE"), "상태 줄 마커가 서버와 다르다");
        assertTrue(Pattern.compile("var _TD_NAME_RE = /.*/i;").matcher(uq).find(), "파일명 규칙은 대소문자를 가리지 않는다(서버 CASE_INSENSITIVE)");
        // 양성 조건도 같은 식이다
        assertTrue(uq.contains("if ((header && threads >= 1) || (threads >= 3 && states >= 1)) return true;"), "브라우저 양성 조건");
        String det = read("src/main/java/com/heapdump/analyzer/parser/threaddump/ThreadDumpFormatDetector.java");
        assertTrue(det.contains("return (header && threads >= 1) || (threads >= 3 && states >= 1);"), "서버 양성 조건");
    }

    @Test
    @DisplayName("자동 분류 순서 — 코어 이름 → 스레드 덤프(내용) → 힙 확장자 → GC 이름 → 스레드 덤프(이름) → GC 내용")
    void resolveOrder() throws IOException {
        String uq = read("src/main/resources/static/js/upload-queue.js");
        int fn = uq.indexOf("function resolveFileType(file) {");
        String body = uq.substring(fn, uq.indexOf("\n    }\n", fn));
        int core = body.indexOf("if (isCoreDumpFilename(name)) return 'coredump';");
        int tdContent = body.indexOf("_tdByContent.has(file)) return 'threaddump';");
        int heapExt = body.indexOf("if (isRecognizedHeapExt(name)) return 'heapdump';");
        int gcName = body.indexOf("if (isGcLogFilename(name)) return 'gclog';");
        int tdName = body.indexOf("if (isThreadDumpFilename(name)) return 'threaddump';");
        int gcContent = body.indexOf("_gcByContent.has(file)) return 'gclog';");
        assertTrue(core > 0 && core < tdContent && tdContent < heapExt && heapExt < gcName && gcName < tdName && tdName < gcContent,
                "자동 분류 순서가 바뀌었다 — GC 이름 규칙에 맞는 파일은 읽지 않고, 스레드 덤프 내용은 힙 확장자보다 먼저 본다");
        assertTrue(body.contains("if (_uploadMode === 'threaddump') return 'threaddump';"), "Files 의 Thread Dump 탭에서는 유형을 강제한다");
        // 덤프는 로그 끝에 붙는 경우가 많다 — 뒤 64KB 도 본다
        assertTrue(uq.contains("f.slice(Math.max(0, f.size - _SNIFF_BYTES))"), "파일 끝부분을 판별하지 않는다");
        // GC 로그 이름 규칙에 맞는 파일과 압축본은 스레드 덤프 판별로 읽지 않는다(기존 GC 분류 보존)
        int need = uq.indexOf("function needsThreadDumpSniff(file) {");
        String needBody = uq.substring(need, uq.indexOf("\n    }\n", need));
        assertTrue(needBody.contains("isGcLogFilename(n)") && needBody.contains("/\\.gz$/i.test(n)"), "스레드 덤프 판별 제외 대상");
    }

    @Test
    @DisplayName("업로드 큐 — 전용 엔드포인트·필드명, 확장자 검사 우회, 힙 중복 검사 생략, 배지")
    void uploadQueueBranches() throws IOException {
        String uq = read("src/main/resources/static/js/upload-queue.js");
        int at = uq.indexOf("'/api/thread-dump/upload'");
        assertTrue(at > 0, "스레드 덤프 업로드 분기가 없다 — 힙 저장소로 들어간다");
        String block = uq.substring(uq.lastIndexOf("else if (item.fileType === 'threaddump')", at), uq.indexOf("xhr.send(fdt);", at));
        assertTrue(block.contains("fdt.append('threadDumpFile', item.file, item.uploadName);"), "필드명이 컨트롤러(@RequestParam threadDumpFile)와 다르다");
        String api = read("src/main/java/com/heapdump/analyzer/controller/ThreadDumpApiController.java");
        assertTrue(api.contains("@PostMapping(\"/api/thread-dump/upload\")") && api.contains("@RequestParam(\"threadDumpFile\") MultipartFile file"));
        assertTrue(uq.contains("if (ftype === 'coredump' || ftype === 'gclog' || ftype === 'threaddump') {"), "스레드 덤프가 힙 확장자 검사에 걸린다(jstack.out 이 거부된다)");
        assertTrue(uq.contains("item.fileType === 'gclog' || item.fileType === 'threaddump') { startDuplicateChecks(idx + 1); return; }"),
                "스레드 덤프에 힙 저장소 기준 중복 검사를 돌린다");
        assertTrue(uq.contains("threaddump: { label: 'THREAD'"), "업로드 목록 배지");
        assertEquals(2, uq.split("typeBadgeHtml\\(q\\.fileType\\)", -1).length - 1, "진행 목록·준비 목록 두 곳이 같은 배지 함수를 쓴다");
        // 서버 오류 본문 {error} 를 그대로 보여 준다 — 거부 사유(스레드 덤프 아님)가 사용자에게 닿아야 한다
        assertTrue(uq.contains("if (j && (j.message || j.error)) item._error = j.message || j.error;"));
    }

    @Test
    @DisplayName("Files 화면 — 탭·건수·배지·행 동작·분류 옵션과 이동 안내·다운로드/삭제 API·순번 접두")
    void filesPage() throws IOException {
        String f = read("src/main/resources/templates/files.html");
        int tGc = f.indexOf("data-tab=\"gclog\""), tTd = f.indexOf("data-tab=\"threaddump\""), tOthers = f.indexOf("data-tab=\"others\"");
        assertTrue(tGc > 0 && tGc < tTd && tTd < tOthers, "탭 순서 GC Log → Thread Dump → Others");
        assertTrue(f.contains("id=\"tabCntTd\"") && f.contains("getElementById('tabCntTd').textContent"), "탭 건수");
        assertTrue(f.contains("else if (t === 'threaddump') td++;"), "스레드 덤프가 Heapdump 건수에 섞인다");
        assertTrue(f.contains("heap + core + gc + td + others"), "ALL 건수에 스레드 덤프가 빠졌다");
        assertTrue(f.contains("th:if=\"${h.fileType == 'threaddump'}\" class=\"threaddump-badge\">THREAD</span>"), "배지");
        assertTrue(f.contains("@{/thread-dump/analyze/{f}(f=${h.filename},start=1)}") && f.contains("data-kind=\"threaddump\""), "분석 버튼은 확인 모달 + ?start=1");
        assertTrue(f.contains("'/api/thread-dump/download/' + encodeURIComponent(filename)"), "다운로드는 스레드 덤프 저장소 엔드포인트");
        assertTrue(f.contains("function confirmThreadDumpDelete(") && f.contains("_delStoreApi = '/api/thread-dump/'"), "삭제는 스레드 덤프 API");
        assertTrue(f.contains("isTdRow(c) ? '/api/thread-dump/' : '/api/gc-log/'"), "일괄 삭제가 스레드 덤프를 힙 API 로 보낸다");
        assertTrue(f.contains(": fileType === 'threaddump' ? 'threaddump'"), "일괄 다운로드 종류");
        assertTrue(f.contains("<option value=\"threaddump\">스레드 덤프</option>"), "파일 분류 설정 옵션");
        assertTrue(f.contains("id=\"classifyTdIn\"") && f.contains("id=\"classifyTdOut\""), "분류가 파일 이동이라는 안내(들어옴/나감)");
        assertTrue(f.contains("body: JSON.stringify({ fileType: fileType, from: _classifyCurrentType })"), "분류 요청이 현재 유형(from)을 싣지 않는다");
        assertEquals(2, f.split("threaddump:\\s*'threaddump'", -1).length - 1, "탭 클릭·저장된 탭 복원 두 곳의 모드 매핑");
        assertTrue(f.contains("if (t === 'threaddump') return 'T';") && f.contains("G: [], T: [], O: []"), "순번 접두 T");
        assertTrue(f.contains("tab === 'gclog' || tab === 'threaddump') return '';"), "Thread Dump 탭은 확장자를 가리지 않는다");

        String view = read("src/main/java/com/heapdump/analyzer/controller/HeapDumpViewController.java");
        assertTrue(view.contains("combined.addAll(buildThreadDumpHistory(isAdmin));") && view.contains("item.setFileType(\"threaddump\");"), "filesPage 가 스레드 덤프 목록을 병합");
        String ctl = read("src/main/java/com/heapdump/analyzer/controller/HeapFileApiController.java");
        assertTrue(ctl.contains("Set.of(\"core\", \"exec\", \"heapdump\", \"others\", \"gclog\", \"threaddump\")"), "분류 허용 값");
    }

    @Test
    @DisplayName("분석 확인 모달·대시보드·배너")
    void confirmModalDashboardBanner() throws IOException {
        String ac = read("src/main/resources/static/js/analyze-confirm.js");
        assertTrue(ac.contains("threaddump: '스레드 덤프'"), "KIND_LABEL");
        assertTrue(ac.contains("if (kind === 'threaddump') return '/thread-dump/analyze/' + encodeURIComponent(filename) + '?start=1';"),
                "?start=1 이 없으면 실패한 파일에서 '분석'을 눌러도 옛 오류만 보인다");
        assertTrue(ac.contains(".ac-kind.threaddump{"), "유형 배지 색");

        String idx = read("src/main/resources/templates/index.html");
        assertTrue(idx.contains("var doneTd = items.filter(function(i) { return i.fileType === 'threaddump' && i.status === 'done'; });")
                && idx.contains("var turl = '/thread-dump';"), "대시보드에서 스레드 덤프만 올리면 전용 화면으로 간다");

        String banner = read("src/main/resources/templates/fragments/banner.html");
        assertTrue(banner.contains("href=\"/thread-dump\" class=\"gb-nav-link\" id=\"gbNavThreadDump\""), "메뉴 링크");
        assertTrue(banner.contains("href=\"/thread-dump\" class=\"gb-ci\" title=\"스레드 덤프\""), "접힌 배너 아이콘");
        assertTrue(banner.contains("if (path.startsWith('/thread-dump')) {"), "활성 표시");
    }
}

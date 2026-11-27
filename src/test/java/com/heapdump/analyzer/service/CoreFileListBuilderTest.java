package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.dto.AnalysisHistoryItem;
import com.heapdump.analyzer.model.dto.CoreFileRow;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코어 파일 화면 표 조립(2026-11-26 개편) — 서버 파일 + 분석 이력 합치기, '같은 지점' 묶기, 표시 문자열.
 */
class CoreFileListBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    static AnalysisHistoryItem file(String name, String type, long size, LocalDateTime modified, String pairedExec) {
        AnalysisHistoryItem f = new AnalysisHistoryItem();
        f.setFilename(name);
        f.setFileType(type);
        f.setSizeBytes(size);
        f.setFormattedSize(size + " B");
        f.setLastModified(modified.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        f.setPairedExecFilename(pairedExec);
        f.setStatus("NOT_ANALYZED");
        return f;
    }

    static CoreDumpAnalysisEntity entity(String name, String status, LocalDateTime created, String summary) {
        CoreDumpAnalysisEntity e = new CoreDumpAnalysisEntity();
        e.setFilename(name);
        e.setStatus(status);
        e.setCreatedAt(created);
        e.setCrashSummary(summary);
        if ("SUCCESS".equals(status)) {
            e.setCrashSignal("SIGSEGV");
            e.setAnalyzedAt(created.plusMinutes(3));
            e.setAnalysisTimeMs(161_000L);
        }
        return e;
    }

    private static Map<String, CoreFileRow> byName(List<CoreFileRow> rows) {
        return rows.stream().collect(Collectors.toMap(CoreFileRow::getFilename, r -> r));
    }

    @Test
    @DisplayName("서버 파일과 이력을 파일명으로 합친다 — 미분석·원본 없음·연결 안 된 실행 파일이 각각 한 행")
    void mergesFilesAndHistory() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 26, 14, 32);
        List<AnalysisHistoryItem> files = List.of(
                file("core.httpd.4821", "coredump", 1200, t.minusHours(1), "httpd"),
                file("core.sshd.3391", "coredump", 84, t.minusHours(5), null),
                file("wsm", "exec", 64, t.minusDays(3), null));
        List<CoreDumpAnalysisEntity> history = List.of(
                entity("core.httpd.4821", "SUCCESS", t, "#0 0x000055d0c1a2f3e4 in ap_proxy_http_process_response at mod_proxy_http.c:1478"),
                entity("core.gone.1", "SUCCESS", t.minusDays(2), null));

        List<CoreFileRow> rows = CoreFileListBuilder.build(files, history, n -> "httpd".equals(n) ? 1_153_434L : null, TODAY);
        assertEquals(4, rows.size());
        Map<String, CoreFileRow> m = byName(rows);

        CoreFileRow httpd = m.get("core.httpd.4821");
        assertEquals("SUCCESS", httpd.getStatus());
        assertEquals(t, httpd.getUploadedAt(), "이력이 있으면 업로드 시각은 이력의 createdAt");
        assertEquals("httpd", httpd.getExecName(), "현재 연결된 실행 파일");
        assertEquals("1.10 MB", httpd.getExecFormattedSize());
        assertEquals("ap_proxy_http_process_response", httpd.getCrashFunction());
        assertEquals("mod_proxy_http.c:1478", httpd.getCrashLocation());
        assertEquals(11, httpd.getSignalNumber());
        assertEquals("잘못된 메모리 접근", httpd.getSignalMeaning());
        assertEquals("fatal", httpd.getSignalTone());
        assertEquals("2분 41초", httpd.getFormattedAnalysisTime());
        assertEquals("2026-11-26 14:32", httpd.getUploadedLabel());

        CoreFileRow sshd = m.get("core.sshd.3391");
        assertTrue(sshd.isNotAnalyzed(), "이력이 없는 코어는 미분석");
        assertEquals(t.minusHours(5), sshd.getUploadedAt(), "이력이 없으면 파일 수정 시각");
        assertNull(sshd.getExecName());

        CoreFileRow gone = m.get("core.gone.1");
        assertTrue(gone.isFileMissing(), "이력만 있는 코어는 원본 없음");
        assertTrue(gone.getFrames().isEmpty());

        CoreFileRow wsm = m.get("wsm");
        assertTrue(wsm.isExec());
        assertEquals("EXEC", wsm.getStatus());
        assertFalse(wsm.isNotAnalyzed(), "실행 파일 행은 미분석 건수에 들어가지 않는다");

        assertEquals(List.of("core.httpd.4821", "core.sshd.3391", "core.gone.1", "wsm"),
                rows.stream().map(CoreFileRow::getFilename).collect(Collectors.toList()), "업로드 최근 순");
    }

    @Test
    @DisplayName("같은 함수·같은 소스 줄에서 끝난 코어를 묶고, 소스 위치 없는 라이브러리 프레임은 묶지 않는다")
    void groupsRepeatsBySourceLocation() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 26, 14, 0);
        String httpd = "#0 0x000055d0c1a2f3e4 in ap_proxy_http_process_response at mod_proxy_http.c:1478\n"
                + "#1 0x000055d0c1a31b0c in proxy_http_handler at mod_proxy_http.c:2011";
        String raise = "#1 0x00007f8e2b454476 in raise";
        List<CoreDumpAnalysisEntity> history = List.of(
                entity("core.httpd.4821", "SUCCESS", t, httpd),
                entity("core.httpd.4790", "SUCCESS", t.minusDays(2), httpd),
                entity("core.httpd.4655", "SUCCESS", t.minusDays(7), httpd),
                entity("core.java.1", "SUCCESS", t.minusDays(1), raise),
                entity("core.java.2", "SUCCESS", t.minusDays(3), raise));
        history.forEach(e -> e.setExecutableName(e.getFilename().startsWith("core.httpd") ? "httpd" : null));

        List<CoreFileRow> rows = CoreFileListBuilder.build(Collections.emptyList(), history, n -> null, TODAY);
        Map<String, CoreFileRow> m = byName(rows);

        CoreFileRow newest = m.get("core.httpd.4821");
        assertTrue(newest.isRepeated());
        assertEquals(3, newest.getRepeatCount());
        assertEquals(List.of("core.httpd.4790 (11-24)", "core.httpd.4655 (11-19)"), newest.getRepeatOthers());
        assertFalse(m.get("core.java.1").isRepeated(), "raise 처럼 위치 없는 프레임은 원인이 달라도 같아 보인다");

        List<CoreFileListBuilder.RepeatGroup> groups = CoreFileListBuilder.repeatGroups(rows);
        assertEquals(1, groups.size());
        assertEquals(3, groups.get(0).getCount());
        assertEquals("httpd", groups.get(0).getExecName(), "모두 같은 실행 파일이면 그 이름");
        assertEquals("mod_proxy_http.c:1478", groups.get(0).getLocation());
    }

    @Test
    @DisplayName("백트레이스 요약 한 줄을 번호·주소·함수·위치로 쪼갠다 — 형식이 다르면 원문을 함수 칸에")
    void parsesSummaryLines() {
        List<CoreFileRow.Frame> f = CoreFileListBuilder.parseFrames(
                "#0 0x000055d0c1a2f3e4 in ap_proxy_http_process_response at mod_proxy_http.c:1478\n"
                + "#3 main at main.c:41\r\n"
                + "#1 0x00007f8e2b454476 in raise\n"
                + "\n"
                + "알 수 없는 줄");
        assertEquals(4, f.size());
        assertEquals("#0", f.get(0).getNum());
        assertEquals("0x000055d0c1a2f3e4", f.get(0).getAddr());
        assertEquals("ap_proxy_http_process_response", f.get(0).getFn());
        assertEquals("mod_proxy_http.c:1478", f.get(0).getLoc());
        assertNull(f.get(1).getAddr());
        assertEquals("main", f.get(1).getFn());
        assertNull(f.get(2).getLoc());
        assertEquals("알 수 없는 줄", f.get(3).getFn());
        assertNull(f.get(3).getNum());
        assertTrue(CoreFileListBuilder.parseFrames(null).isEmpty());
    }

    @Test
    @DisplayName("소요 시간·업로드 시각 표기")
    void formatsDurationAndRelativeLabel() {
        assertNull(CoreFileListBuilder.formatDuration(null));
        assertEquals("1초 미만", CoreFileListBuilder.formatDuration(500L));
        assertEquals("58초", CoreFileListBuilder.formatDuration(58_000L));
        assertEquals("2분", CoreFileListBuilder.formatDuration(120_000L));
        assertEquals("2분 41초", CoreFileListBuilder.formatDuration(161_000L));
        assertEquals("1시간 5분", CoreFileListBuilder.formatDuration(3_900_000L));

        // 업로드 시각은 항상 연도까지(2026-11-27) — 오늘·어제·올해·지난해 모두 같은 형식
        assertEquals("2026-11-26 09:05", CoreFileListBuilder.relativeLabel(LocalDateTime.of(2026, 11, 26, 9, 5), TODAY));
        assertEquals("2026-11-25 22:47", CoreFileListBuilder.relativeLabel(LocalDateTime.of(2026, 11, 25, 22, 47), TODAY));
        assertEquals("2026-11-24 11:20", CoreFileListBuilder.relativeLabel(LocalDateTime.of(2026, 11, 24, 11, 20), TODAY));
        assertEquals("2025-11-24 11:20", CoreFileListBuilder.relativeLabel(LocalDateTime.of(2025, 11, 24, 11, 20), TODAY));
        assertNull(CoreFileListBuilder.relativeLabel(null, TODAY));
    }

    @Test
    @DisplayName("시그널 색 구분 — 메모리·명령 오류는 fatal, abort·산술은 warn, 나머지는 neutral")
    void signalTone() {
        CoreFileRow r = new CoreFileRow();
        assertEquals("neutral", r.getSignalTone());
        r.setCrashSignal("SIGBUS");
        assertEquals("fatal", r.getSignalTone());
        r.setCrashSignal("SIGABRT");
        assertEquals("warn", r.getSignalTone());
        r.setCrashSignal("SIGTERM");
        assertEquals("neutral", r.getSignalTone());
    }

    @Test
    @DisplayName("긴 파일명은 앞·끝으로 나눠 끝(pid·시각)을 남기고, 짧은 이름은 나누지 않는다")
    void nameSplitKeepsTail() {
        CoreFileRow r = new CoreFileRow();
        r.setFilename("core.httpd.4821");
        assertEquals("core.httpd.4821", r.getNameHead());
        assertNull(r.getNameTail(), "짧은 이름은 나누지 않는다");

        r.setFilename("core.tmaxsvr_gw01.20261125-031455.18822");
        assertEquals("core.tmaxsvr_gw01.20261125-", r.getNameHead());
        assertEquals("031455.18822", r.getNameTail());
        assertEquals(r.getFilename(), r.getNameHead() + r.getNameTail(), "앞 + 끝 = 원래 이름(복사·검색이 깨지지 않는다)");

        // 같은 프로그램의 코어 둘은 끝부분으로 구분된다 — 끝을 자르던 때는 화면에서 같은 글자로 보였다
        CoreFileRow o = new CoreFileRow();
        o.setFilename("core.tmaxsvr_gw01.20261124-221003.17710");
        assertFalse(r.getNameTail().equals(o.getNameTail()));

        // 자르는 자리가 보조 평면 문자 한가운데면 한 칸 앞에서 자른다
        String emoji = "core.report-archive-2026-\uD83D\uDE00" + "0314.188221";   // 끝에서 12번째가 낮은 서로게이트
        r.setFilename(emoji);
        assertFalse(Character.isLowSurrogate(r.getNameTail().charAt(0)), "서로게이트 쌍을 가르지 않는다");
        assertEquals(emoji, r.getNameHead() + r.getNameTail());

        r.setFilename(null);
        assertNull(r.getNameHead());
        assertNull(r.getNameTail());
    }

    @Test
    @DisplayName("표 칸의 소스 위치는 디렉터리를 뗀 파일명:줄 — 묶음 키는 전체 경로 그대로")
    void shortLocationDropsDirectories() {
        assertEquals("registry.cpp:188", CoreFileRow.shortLocation("/build/tmax/src/session/registry.cpp:188"));
        assertEquals("mod_proxy_http.c:1478", CoreFileRow.shortLocation("mod_proxy_http.c:1478"));
        assertEquals("main.c:3", CoreFileRow.shortLocation("C:\\src\\main.c:3"));
        assertEquals("dir/", CoreFileRow.shortLocation("dir/"), "파일명이 없으면 원문 그대로");
        assertNull(CoreFileRow.shortLocation(null));

        LocalDateTime t = LocalDateTime.of(2026, 11, 26, 10, 0);
        String bt = "#0 0x1 in lookup at /build/a/registry.cpp:188";
        List<CoreFileRow> rows = CoreFileListBuilder.build(
                List.of(file("core.a.1", "coredump", 10, t, null), file("core.a.2", "coredump", 10, t.minusHours(1), null)),
                List.of(entity("core.a.1", "SUCCESS", t, bt), entity("core.a.2", "SUCCESS", t.minusHours(1), bt)),
                n -> null, TODAY);
        assertEquals("registry.cpp:188", rows.get(0).getCrashLocationShort());
        assertEquals("lookup @ /build/a/registry.cpp:188", rows.get(0).getRepeatKey(), "같은 지점 판정은 전체 경로로");
        List<CoreFileListBuilder.RepeatGroup> groups = CoreFileListBuilder.repeatGroups(rows);
        assertEquals(1, groups.size());
        assertEquals("registry.cpp:188", groups.get(0).getLocationShort());
        assertEquals("/build/a/registry.cpp:188", groups.get(0).getLocation());
    }
}

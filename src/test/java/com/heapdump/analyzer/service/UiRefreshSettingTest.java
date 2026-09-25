package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 화면 자동 갱신 주기 허용값 — 서버 판정과 설정 화면 선택지·properties 키의 정합성 (2026-09-26). */
class UiRefreshSettingTest {

    @Test
    @DisplayName("허용값은 10·30·60 뿐이다")
    void onlyThreeValuesAllowed() {
        assertTrue(HeapDumpAnalyzerService.isUiRefreshAllowed(10));
        assertTrue(HeapDumpAnalyzerService.isUiRefreshAllowed(30));
        assertTrue(HeapDumpAnalyzerService.isUiRefreshAllowed(60));
        for (int bad : new int[]{0, -10, 5, 15, 20, 45, 61, 120, Integer.MAX_VALUE}) {
            assertFalse(HeapDumpAnalyzerService.isUiRefreshAllowed(bad), bad + " 이 허용됐다");
        }
    }

    @Test
    @DisplayName("설정 화면의 선택지가 서버 허용값과 1:1 이다")
    void settingsPageOptionsMatchServer() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/settings.html"), StandardCharsets.UTF_8);
        int from = html.indexOf("id=\"uiRefreshIntervalSelect\"");
        assertTrue(from > 0, "설정 화면에 화면 자동 갱신 주기 셀렉트가 없다");
        String sel = html.substring(from, html.indexOf("</select>", from));
        Matcher m = Pattern.compile("<option value=\"(\\d+)\"").matcher(sel);
        List<Integer> opts = new ArrayList<>();
        while (m.find()) opts.add(Integer.parseInt(m.group(1)));
        List<Integer> allowed = new ArrayList<>();
        for (int a : HeapDumpAnalyzerService.UI_REFRESH_ALLOWED) allowed.add(a);
        assertEquals(allowed, opts, "설정 화면 선택지와 서버 허용값이 다르다");
        assertTrue(html.contains("saveUiRefreshInterval(this.value)") && html.contains("/api/settings/ui-refresh-interval?seconds="),
                "저장 배선이 없다");
    }

    @Test
    @DisplayName("properties 키 ui.refresh.interval-sec 가 앱·k8s 설정 파일에 모두 있다 — sync 는 치환 전용이라 없으면 조용히 유실된다(함정 40)")
    void propertiesKeyExistsSoSyncCanWriteBack() throws Exception {
        String app = Files.readString(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        assertTrue(app.contains("\nui.refresh.interval-sec="), "application.properties 에 키가 없다");
        Path k8s = Path.of("/opt/genspark/heapApp_k8s/config/application.properties");
        if (Files.exists(k8s)) {
            assertTrue(Files.readString(k8s, StandardCharsets.UTF_8).contains("\nui.refresh.interval-sec="), "k8s ConfigMap 원본에 키가 없다");
        }
        String svc = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java"), StandardCharsets.UTF_8);
        assertTrue(svc.contains("updates.put(\"ui.refresh.interval-sec\""), "syncApplicationProperties 에 키가 없다");
        assertTrue(svc.contains("settings.put(\"uiRefreshIntervalSec\"") && svc.contains("saved.containsKey(\"uiRefreshIntervalSec\")"),
                "settings.json 저장·복원 한쪽이 빠졌다");
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * GC 로그 인스턴스명 해석 (2026-09-14) — 수동 입력 > 연결된 힙 덤프의 Instance.
 * 덤프 값은 복사하지 않고 조회 시점에 읽으므로, 연결을 해제하면 수동값이 없는 한 카드가 비어야 한다.
 */
class GcLogInstanceServiceTest {

    private static GcLogAnalysisEntity log(String manual, String dump) {
        GcLogAnalysisEntity e = new GcLogAnalysisEntity();
        e.setFilename("gc.log.0");
        e.setInstanceName(manual);
        e.setMatchedDumpFilename(dump);
        return e;
    }

    @Test
    @DisplayName("view: 수동 > 덤프 > 없음 순서, 모든 키를 빈 문자열로라도 채운다")
    void priority() {
        Map<String, Object> m = GcLogInstanceService.view("server1", "app.hprof", "server2");
        assertEquals("server1", m.get("name"));
        assertEquals("manual", m.get("source"));
        assertEquals("server2", m.get("dump"), "수동값이 있어도 덤프 값은 함께 알려 준다(카드 부가 문구)");

        Map<String, Object> d = GcLogInstanceService.view(null, "app.hprof", " server2 ");
        assertEquals("server2", d.get("name"));
        assertEquals("dump", d.get("source"));
        assertEquals("app.hprof", d.get("dumpFilename"));

        Map<String, Object> blank = GcLogInstanceService.view("   ", "app.hprof", "");
        assertEquals("", blank.get("name"));
        assertEquals("none", blank.get("source"), "공백 수동값·빈 덤프 값은 없는 것");
        assertEquals("app.hprof", blank.get("dumpFilename"), "연결은 있으나 Instance 가 없는 경우를 구분할 수 있어야 한다");

        Map<String, Object> none = GcLogInstanceService.view(null, null, "stale");
        assertEquals("", none.get("name"), "연결이 없으면 덤프 값은 무시한다");
        assertEquals("", none.get("dump"));
        assertEquals("", none.get("dumpFilename"));
        assertEquals(5, none.size());
    }

    @Test
    @DisplayName("normalize: trim · 빈 값 null · 100자 절단")
    void normalize() {
        assertNull(GcLogInstanceService.normalize(null));
        assertNull(GcLogInstanceService.normalize("  \t "));
        assertEquals("a b", GcLogInstanceService.normalize("  a b "));
        assertEquals(100, GcLogInstanceService.normalize("x".repeat(150)).length());
    }

    @Test
    @DisplayName("viewOf: 연결된 덤프만 조회하고, 연결이 없으면 힙 서비스를 부르지 않는다")
    void viewOfReadsLinkedDumpOnly() {
        HeapDumpAnalyzerService heap = Mockito.mock(HeapDumpAnalyzerService.class);
        Mockito.when(heap.getEffectiveJeusInstance("app.hprof")).thenReturn("server7");
        GcLogInstanceService svc = new GcLogInstanceService(Mockito.mock(GcLogAnalysisRepository.class), heap);

        assertEquals("server7", svc.viewOf(log(null, "app.hprof")).get("name"));
        assertEquals("manual", svc.viewOf(log("mine", "app.hprof")).get("source"));

        Mockito.clearInvocations(heap);
        assertEquals("none", svc.viewOf(log(null, null)).get("source"));
        assertEquals("none", svc.viewOf(null).get("source"));
        Mockito.verify(heap, Mockito.never()).getEffectiveJeusInstance(anyString());
    }

    @Test
    @DisplayName("updateManual: 정규화해 저장하고, 빈 값은 수동값을 지운다(덤프 값 폴백)")
    void updateManual() {
        GcLogAnalysisRepository repo = Mockito.mock(GcLogAnalysisRepository.class);
        Mockito.when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GcLogInstanceService svc = new GcLogInstanceService(repo, Mockito.mock(HeapDumpAnalyzerService.class));

        GcLogAnalysisEntity e = log(null, "app.hprof");
        assertEquals(" x ".trim(), svc.updateManual(e, " x ").getInstanceName());
        assertNull(svc.updateManual(e, "").getInstanceName());
        Mockito.verify(repo, Mockito.times(2)).save(e);
    }
}

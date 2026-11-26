package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import com.heapdump.analyzer.service.ThreadDumpAnalyzerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 파일 분류 ↔ 스레드 덤프 저장소 이동(2026-11-26).
 *
 * <p>규칙: 대상 저장소가 편입(형식 검사 → 이동 → 등록)하고 원 저장소는 기록만 정리한다. 편입이 거부되면 원 저장소는 건드리지 않는다 —
 * 순서가 뒤집히면 "분류는 실패했는데 원래 분석 결과는 사라진" 상태가 된다.
 */
class FileClassifyThreadDumpEndpointTest {

    @TempDir Path root;
    private File heapDir, coreDir, gcDir, tdDir;
    private HeapDumpAnalyzerService heap;
    private CoreDumpAnalyzerService core;
    private GcLogAnalyzerService gc;
    private ThreadDumpAnalyzerService td;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        heapDir = Files.createDirectories(root.resolve("heap")).toFile();
        coreDir = Files.createDirectories(root.resolve("core")).toFile();
        gcDir = Files.createDirectories(root.resolve("gc")).toFile();
        tdDir = Files.createDirectories(root.resolve("td")).toFile();
        heap = Mockito.mock(HeapDumpAnalyzerService.class);
        core = Mockito.mock(CoreDumpAnalyzerService.class);
        gc = Mockito.mock(GcLogAnalyzerService.class);
        td = Mockito.mock(ThreadDumpAnalyzerService.class);
        Mockito.when(heap.heapDumpFilesDirectory()).thenReturn(heapDir);
        Mockito.when(core.dumpFilesDir()).thenReturn(coreDir);
        Mockito.when(gc.fileOf(anyString())).thenAnswer(inv -> new File(gcDir, inv.getArgument(0)));
        Mockito.when(td.fileOf(anyString())).thenAnswer(inv -> new File(tdDir, inv.getArgument(0)));
        Mockito.when(heap.findHistoryEntity(anyString())).thenReturn(Optional.empty());
        Mockito.when(core.getEntity(anyString())).thenReturn(Optional.empty());
        HeapFileApiController c = new HeapFileApiController(heap, core, gc);
        c.threadDumpService = td;
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    private void touch(File dir, String name) throws Exception { Files.writeString(new File(dir, name).toPath(), "x"); }

    private ResultActions classify(String fn, String type, String from) throws Exception {
        String body = "{\"fileType\":\"" + type + "\"" + (from == null ? "" : ",\"from\":\"" + from + "\"") + "}";
        return mvc.perform(post("/api/files/" + fn + "/classify").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("힙 저장소의 미분석 파일 → 스레드 덤프: 편입 → 힙 기록만 삭제 → 라벨 제거")
    void heapToThreadDump() throws Exception {
        touch(heapDir, "catalina.out");
        Mockito.when(heap.resolveServerNameByFilename("catalina.out")).thenReturn("was-01");
        classify("catalina.out", "threaddump", "heapdump").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.moved").value(true)).andExpect(jsonPath("$.fileType").value("threaddump"));
        InOrder order = Mockito.inOrder(td, heap);
        order.verify(td).adoptFile(eq(new File(heapDir, "catalina.out")), anyString(), eq("was-01"));
        order.verify(heap).deleteHistoryRecordOnly("catalina.out");
        order.verify(heap).removeFileClassification("catalina.out");
        Mockito.verify(heap, Mockito.never()).deleteHistory(anyString(), Mockito.anyBoolean(), Mockito.anyBoolean());   // 이름 접두로 형제 파일까지 지우는 삭제는 쓰지 않는다
    }

    @Test
    @DisplayName("스레드 덤프가 아니면 400 — 원 저장소의 기록은 건드리지 않는다")
    void adoptRejectedLeavesSourceAlone() throws Exception {
        touch(heapDir, "notes.txt");
        Mockito.when(td.adoptFile(any(), anyString(), any())).thenThrow(new IllegalArgumentException("스레드 덤프 형식을 인식하지 못했습니다. 스레드 덤프로 분류할 수 없습니다."));
        classify("notes.txt", "threaddump", "others").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error")).andExpect(jsonPath("$.message").value(containsString("분류할 수 없습니다")));
        Mockito.verify(heap, Mockito.never()).deleteHistoryRecordOnly(anyString());
        Mockito.verify(heap, Mockito.never()).removeFileClassification(anyString());
    }

    @Test
    @DisplayName("GC 로그 → 스레드 덤프: 스레드 덤프가 직접 편입하고 GC 쪽은 기록만 지운다(힙 저장소를 거치지 않는다)")
    void gcLogToThreadDump() throws Exception {
        touch(gcDir, "server.log");
        classify("server.log", "threaddump", "gclog").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(true));
        InOrder order = Mockito.inOrder(td, gc);
        order.verify(td).adoptFile(eq(new File(gcDir, "server.log")), anyString(), any());
        order.verify(gc).forgetMovedFile(eq("server.log"), anyString());
        Mockito.verify(gc, Mockito.never()).releaseFile(anyString(), any(), anyString());   // release → adopt 2단이면 편입 실패 때 파일이 힙 저장소에 떨어진다
    }

    @Test
    @DisplayName("스레드 덤프 → GC 로그: 반대 방향도 같은 규칙")
    void threadDumpToGcLog() throws Exception {
        touch(tdDir, "server.log");
        classify("server.log", "gclog", "threaddump").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(true));
        InOrder order = Mockito.inOrder(gc, td);
        order.verify(gc).adoptFile(eq(new File(tdDir, "server.log")), anyString(), any());
        order.verify(td).forgetMovedFile(eq("server.log"), anyString());
    }

    @Test
    @DisplayName("스레드 덤프 → 기타·힙·코어: 힙 저장소로 내보낸 뒤 라벨을 붙인다")
    void threadDumpToOthers() throws Exception {
        touch(tdDir, "x.txt");
        classify("x.txt", "others", "threaddump").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(true));
        InOrder order = Mockito.inOrder(td, heap);
        order.verify(td).releaseFile(eq("x.txt"), eq(heapDir), anyString());
        order.verify(heap).saveFileClassification("x.txt", "others");
    }

    @Test
    @DisplayName("이미 스레드 덤프면 아무것도 옮기지 않는다 / 파일이 어디에도 없으면 404")
    void noopAndMissing() throws Exception {
        touch(tdDir, "a.txt");
        classify("a.txt", "threaddump", "threaddump").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(false));
        Mockito.verify(td, Mockito.never()).adoptFile(any(), anyString(), any());
        classify("ghost.txt", "threaddump", null).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("같은 이름이 두 저장소에 있을 때 — from 이 있으면 그 파일을, 없으면 409")
    void ambiguousSource() throws Exception {
        touch(heapDir, "same.log");
        touch(gcDir, "same.log");
        classify("same.log", "threaddump", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("여러 저장소")));
        Mockito.verify(td, Mockito.never()).adoptFile(any(), anyString(), any());

        classify("same.log", "threaddump", "gclog").andExpect(status().isOk());
        Mockito.verify(td).adoptFile(eq(new File(gcDir, "same.log")), anyString(), any());
        Mockito.verify(gc).forgetMovedFile(eq("same.log"), anyString());
        Mockito.verify(heap, Mockito.never()).deleteHistoryRecordOnly(anyString());
    }

    @Test
    @DisplayName("대상 저장소에 이미 같은 이름이 있으면 409(편입이 거부한다) — 원 저장소는 그대로")
    void targetNameTaken() throws Exception {
        touch(heapDir, "a.txt");
        Mockito.when(td.adoptFile(any(), anyString(), any())).thenThrow(new IllegalStateException("스레드 덤프 저장소에 같은 이름의 파일이 이미 있습니다: a.txt"));
        classify("a.txt", "threaddump", "heapdump").andExpect(status().isConflict());
        Mockito.verify(heap, Mockito.never()).deleteHistoryRecordOnly(anyString());
    }

    @Test
    @DisplayName("분석 결과가 있는 힙 덤프·분석 중인 파일은 옮기지 않는다")
    void blockedByAnalysis() throws Exception {
        touch(heapDir, "done.hprof");
        AnalysisHistoryEntity h = new AnalysisHistoryEntity();
        h.setStatus("SUCCESS");
        Mockito.when(heap.findHistoryEntity("done.hprof")).thenReturn(Optional.of(h));
        classify("done.hprof", "threaddump", "heapdump").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("스레드 덤프로 분류할 수 없습니다")));

        touch(gcDir, "busy.log");
        Mockito.when(gc.isAnalyzing("busy.log")).thenReturn(true);
        classify("busy.log", "threaddump", "gclog").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("진행 중")));

        touch(tdDir, "busy2.txt");
        Mockito.when(td.isAnalyzing("busy2.txt")).thenReturn(true);
        classify("busy2.txt", "gclog", "threaddump").andExpect(status().isConflict());
        Mockito.verify(td, Mockito.never()).adoptFile(any(), anyString(), any());
        Mockito.verify(gc, Mockito.never()).adoptFile(any(), anyString(), any());
    }

    @Test
    @DisplayName("원 저장소 정리가 실패해도 분류는 성공이다(파일은 이미 옮겨졌다) / 이동 I/O 실패는 500")
    void cleanupFailureAndIoError() throws Exception {
        touch(heapDir, "a.txt");
        Mockito.doThrow(new RuntimeException("db down")).when(heap).deleteHistoryRecordOnly("a.txt");
        classify("a.txt", "threaddump", "heapdump").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(true));

        touch(heapDir, "b.txt");
        Mockito.when(td.adoptFile(eq(new File(heapDir, "b.txt")), anyString(), any())).thenThrow(new IOException("Disk quota exceeded"));
        classify("b.txt", "threaddump", "heapdump").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(containsString("Disk quota exceeded")));
    }

    @Test
    @DisplayName("스레드 덤프 저장소가 없는 구성에서는 거부한다 / 모르는 유형은 400")
    void unavailableAndUnknown() throws Exception {
        MockMvc bare = MockMvcBuilders.standaloneSetup(new HeapFileApiController(heap, core, gc)).build();
        touch(heapDir, "a.txt");
        bare.perform(post("/api/files/a.txt/classify").contentType(MediaType.APPLICATION_JSON).content("{\"fileType\":\"threaddump\"}"))
                .andExpect(status().isBadRequest());
        classify("a.txt", "javacore", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("threaddump")));
    }

    @Test
    @DisplayName("출처 판정 — 화면이 보낸 유형이 실제 위치와 맞을 때만 따른다")
    void sourceStoreRules() {
        assertEquals("gclog", HeapFileApiController.sourceStore("gclog", true, false, true, false, "threaddump"));
        assertEquals("heap", HeapFileApiController.sourceStore("others", true, false, true, false, "threaddump"));
        assertEquals("heap", HeapFileApiController.sourceStore("core", true, false, false, false, "threaddump"), "확장자 없는 파일의 core 라벨은 힙 저장소에 있다");
        assertEquals("core", HeapFileApiController.sourceStore("coredump", false, true, false, false, "threaddump"));
        assertEquals("heap", HeapFileApiController.sourceStore("gclog", true, false, false, false, "threaddump"), "힌트가 틀리면 유일한 위치로");
        assertNull(HeapFileApiController.sourceStore(null, true, true, false, false, "gclog"));
        assertNull(HeapFileApiController.sourceStore("threaddump", false, false, false, true, "threaddump"), "이미 그 저장소");
        assertNull(HeapFileApiController.sourceStore(null, true, false, false, true, "threaddump"), "대상에 이미 있으면 옮길 수 없다");
    }
}

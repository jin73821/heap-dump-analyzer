package com.heapdump.analyzer.controller;

import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.CoreDumpAnalysisEntity;
import com.heapdump.analyzer.service.CoreDumpAnalyzerService;
import com.heapdump.analyzer.service.GcLogAnalyzerService;
import com.heapdump.analyzer.service.HeapDumpAnalyzerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/files/{filename}/classify} 의 GC 로그 분기 (2026-09-14, standalone MockMvc).
 *
 * <p>고정하는 계약: ① 'GC로그' 분류는 라벨 저장이 아니라 저장소 이동이다 ② 분석 결과가 있는(또는 진행 중인) 힙·코어 파일은 거부한다
 * ③ 원래 저장소의 기록은 {@code deleteHistoryRecordOnly} 로만 지운다 — {@code deleteHistory} 는 확장자를 뗀 이름으로 dumpfiles 를
 * 훑어 {@code gc.log} 에 쓰면 {@code gc.log.1} 까지 지운다 ④ 오류 본문은 files.html 이 읽는 {@code {status,message}} 모양이다.
 * 실제 파일 이동은 {@code GcLogClassifyMoveTest} 가 본다.
 */
class FileClassifyGcLogEndpointTest {

    @TempDir Path root;
    private File heapDir, coreDir, gcDir;
    private HeapDumpAnalyzerService heap;
    private CoreDumpAnalyzerService core;
    private GcLogAnalyzerService gc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        heapDir = Files.createDirectories(root.resolve("heap")).toFile();
        coreDir = Files.createDirectories(root.resolve("core")).toFile();
        gcDir = Files.createDirectories(root.resolve("gc")).toFile();
        heap = Mockito.mock(HeapDumpAnalyzerService.class);
        core = Mockito.mock(CoreDumpAnalyzerService.class);
        gc = Mockito.mock(GcLogAnalyzerService.class);
        Mockito.when(heap.heapDumpFilesDirectory()).thenReturn(heapDir);
        Mockito.when(core.dumpFilesDir()).thenReturn(coreDir);
        Mockito.when(gc.fileOf(anyString())).thenAnswer(inv -> new File(gcDir, inv.getArgument(0)));
        Mockito.when(heap.findHistoryEntity(anyString())).thenReturn(Optional.empty());
        Mockito.when(core.getEntity(anyString())).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new HeapFileApiController(heap, core, gc)).build();
    }

    private void touch(File dir, String name) throws Exception { Files.writeString(new File(dir, name).toPath(), "x"); }

    private ResultActions classify(String fn, String type) throws Exception {
        return mvc.perform(post("/api/files/" + fn + "/classify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileType\":\"" + type + "\"}"));
    }

    @Test
    @DisplayName("힙덤프 저장소의 미분석 파일 → GC로그: 이동 + 힙 기록만 삭제 + 라벨 제거 (deleteHistory 는 절대 호출하지 않는다)")
    void heapToGcLogMoves() throws Exception {
        touch(heapDir, "gc.log");
        AnalysisHistoryEntity h = new AnalysisHistoryEntity();
        h.setStatus("NOT_ANALYZED");
        Mockito.when(heap.findHistoryEntity("gc.log")).thenReturn(Optional.of(h));
        Mockito.when(heap.resolveServerNameByFilename("gc.log")).thenReturn("was01");

        classify("gc.log", "gclog").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.moved").value(true));

        Mockito.verify(gc).adoptFile(new File(heapDir, "gc.log"), "unknown", "was01");
        Mockito.verify(heap).deleteHistoryRecordOnly("gc.log");
        Mockito.verify(heap).removeFileClassification("gc.log");
        Mockito.verify(heap, Mockito.never()).deleteHistory(anyString(), Mockito.anyBoolean(), Mockito.anyBoolean());
        Mockito.verify(heap, Mockito.never()).saveFileClassification(anyString(), anyString());
    }

    @Test
    @DisplayName("코어 저장소의 파일 → GC로그: 코어 기록만 정리")
    void coreToGcLogMoves() throws Exception {
        touch(coreDir, "jvm.out");
        classify("jvm.out", "gclog").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(true));
        Mockito.verify(gc).adoptFile(eq(new File(coreDir, "jvm.out")), anyString(), any());
        Mockito.verify(core).deleteHistoryOnly("jvm.out");
        Mockito.verify(heap, Mockito.never()).deleteHistoryRecordOnly(anyString());
    }

    @Test
    @DisplayName("분석 결과가 있거나 분석 중인 파일은 GC로그로 분류할 수 없다(400) — 파일·기록 무변경")
    void analyzedFilesAreRefused() throws Exception {
        touch(heapDir, "app.hprof.log");
        AnalysisHistoryEntity h = new AnalysisHistoryEntity();
        h.setStatus("SUCCESS");
        Mockito.when(heap.findHistoryEntity("app.hprof.log")).thenReturn(Optional.of(h));
        classify("app.hprof.log", "gclog").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("분석 결과가 있는 파일")));

        touch(coreDir, "core.99");
        CoreDumpAnalysisEntity c = new CoreDumpAnalysisEntity();
        c.setStatus("ANALYZING");
        Mockito.when(core.getEntity("core.99")).thenReturn(Optional.of(c));
        classify("core.99", "gclog").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("진행 중")));

        Mockito.verify(gc, Mockito.never()).adoptFile(any(), anyString(), any());
        Mockito.verify(heap, Mockito.never()).deleteHistoryRecordOnly(anyString());
    }

    @Test
    @DisplayName("GC 로그 형식이 아니면 400, 이름 충돌이면 409 — 서비스 예외를 {status,message} 로 옮긴다")
    void serviceRefusalsMapToStatus() throws Exception {
        touch(heapDir, "app.log");
        Mockito.when(gc.adoptFile(eq(new File(heapDir, "app.log")), anyString(), any()))
                .thenThrow(new IllegalArgumentException("GC 로그 형식을 인식하지 못했습니다."));
        classify("app.log", "gclog").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("GC 로그 형식을 인식하지 못했습니다."));
        Mockito.verify(heap, Mockito.never()).deleteHistoryRecordOnly(anyString());

        touch(heapDir, "gc2.log");
        Mockito.when(gc.adoptFile(eq(new File(heapDir, "gc2.log")), anyString(), any()))
                .thenThrow(new IllegalStateException("GC 로그 저장소에 같은 이름의 파일이 이미 있습니다: gc2.log"));
        classify("gc2.log", "gclog").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("어느 저장소에도 없으면 404, 힙·코어 양쪽에 있으면 409, 이미 GC 로그면 이동 없이 200")
    void locationEdgeCases() throws Exception {
        classify("nowhere.log", "gclog").andExpect(status().isNotFound());
        touch(heapDir, "both.log");
        touch(coreDir, "both.log");
        classify("both.log", "gclog").andExpect(status().isConflict());
        touch(gcDir, "gc.log.3");
        classify("gc.log.3", "gclog").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(false));
        Mockito.verify(gc, Mockito.never()).adoptFile(any(), anyString(), any());
    }

    @Test
    @DisplayName("GC 로그 → 덤프파일: 힙덤프 저장소로 내보낸 뒤 라벨 저장 / 충돌이면 409 + 라벨 미저장")
    void gcLogToOtherTypeReleases() throws Exception {
        touch(gcDir, "gc.log.1");
        classify("gc.log.1", "heapdump").andExpect(status().isOk()).andExpect(jsonPath("$.moved").value(true));
        Mockito.verify(gc).releaseFile("gc.log.1", heapDir, "unknown");
        Mockito.verify(heap).saveFileClassification("gc.log.1", "heapdump");

        touch(gcDir, "gc.log.2");
        Mockito.doThrow(new IllegalStateException("대상 저장소에 같은 이름의 파일이 이미 있습니다: gc.log.2"))
                .when(gc).releaseFile(eq("gc.log.2"), any(), anyString());
        classify("gc.log.2", "others").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("같은 이름")));
        Mockito.verify(heap, Mockito.never()).saveFileClassification("gc.log.2", "others");
    }

    @Test
    @DisplayName("허용되지 않은 유형은 400 — 허용 목록에 gclog 가 들어 있다")
    void allowList() throws Exception {
        classify("x.log", "gc").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("gclog")));
    }
}

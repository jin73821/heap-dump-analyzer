package com.heapdump.analyzer.service;

import com.heapdump.analyzer.config.HeapDumpConfig;
import com.heapdump.analyzer.model.HeapDumpFile;
import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.util.FileTypeRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 업로드 기록 이름 정리 — .gz 로 올린 파일의 '미분석' 기록이 디스크 이름으로 남아, 목록 이름과 달라
 * 파일이 있는데도 '삭제됨' 유령 행이 되던 문제(2026-09-19).
 * 분석된 기록은 산출물이 이름에 묶여 있어 절대 건드리지 않는다 — 이 테스트의 핵심 단언이다.
 */
class HeapRecordNameMigrationTest {

    private AnalysisHistoryRepository historyRepo;
    private GcLogAnalysisRepository gcRepo;
    private HeapRecordNameMigration migration;
    private List<AnalysisHistoryEntity> rows;

    private static AnalysisHistoryEntity row(long id, String fn, String status, String uploadedBy, String server) {
        AnalysisHistoryEntity e = new AnalysisHistoryEntity();
        e.setId(id); e.setFilename(fn); e.setStatus(status); e.setUploadedBy(uploadedBy); e.setServerName(server);
        return e;
    }

    @BeforeEach
    void setUp() {
        historyRepo = Mockito.mock(AnalysisHistoryRepository.class);
        gcRepo = Mockito.mock(GcLogAnalysisRepository.class);
        rows = new ArrayList<>();
        Mockito.when(historyRepo.findAll()).thenAnswer(inv -> new ArrayList<>(rows));
        Mockito.when(gcRepo.findByMatchedDumpFilename(anyString())).thenReturn(List.of());
        migration = new HeapRecordNameMigration(historyRepo, gcRepo);
    }

    @Test
    @DisplayName("분석 기록이 없는 유령 업로드 기록은 목록 이름으로 바꾼다 — 순번·업로드자 유지")
    void renamesWhenNoCanonicalRecord() {
        AnalysisHistoryEntity ghost = row(70, "ibm-semeru-open-jre_ppc64_aix_21.0.11.0.tar.gz", "NOT_ANALYZED", "admin", null);
        rows.add(ghost);

        HeapRecordNameMigration.Result r = migration.migrate();

        assertEquals(1, r.renamed);
        assertEquals(0, r.merged);
        assertEquals("ibm-semeru-open-jre_ppc64_aix_21.0.11.0.tar", ghost.getFilename());
        assertEquals(70L, ghost.getId(), "순번(id)은 그대로");
        assertEquals("admin", ghost.getUploadedBy());
        Mockito.verify(historyRepo).save(ghost);
        Mockito.verify(historyRepo, Mockito.never()).delete(any());
    }

    @Test
    @DisplayName("목록 이름으로 이미 분석된 기록이 있으면 병합 — 빈 업로드자·출처 서버만 옮기고 유령 행을 지운다")
    void mergesIntoAnalyzedRecord() {
        AnalysisHistoryEntity ghost = row(64, "jeus_admin.hprof.gz", "NOT_ANALYZED", "alice", "daniwb1p");
        ghost.setServerId(7L);
        AnalysisHistoryEntity analyzed = row(80, "jeus_admin.hprof", "SUCCESS", null, null);
        AnalysisHistoryEntity keep = row(81, "oom.hprof", "SUCCESS", "bob", "was1");
        AnalysisHistoryEntity keepGhost = row(82, "oom.hprof.gz", "NOT_ANALYZED", "carol", "was9");
        rows.addAll(List.of(ghost, analyzed, keep, keepGhost));

        HeapRecordNameMigration.Result r = migration.migrate();

        assertEquals(2, r.merged);
        assertEquals("alice", analyzed.getUploadedBy());
        assertEquals("daniwb1p", analyzed.getServerName());
        assertEquals(7L, analyzed.getServerId());
        assertEquals("bob", keep.getUploadedBy(), "이미 있는 값은 덮지 않는다");
        assertEquals("was1", keep.getServerName());
        Mockito.verify(historyRepo).delete(ghost);
        Mockito.verify(historyRepo).delete(keepGhost);
        Mockito.verify(historyRepo).save(analyzed);
        Mockito.verify(historyRepo, Mockito.never()).save(keep);
    }

    @Test
    @DisplayName("분석된 .gz 기록(성공·실패·분석 중)은 건드리지 않는다 — 결과 디렉토리·상세·AI 인사이트가 그 이름에 묶여 있다")
    void leavesAnalyzedGzRecordsAlone() {
        rows.add(row(1, "heapApp_20260622.tar.gz", "ERROR", null, null));
        rows.add(row(2, "jeus_admin.hprof.gz", "SUCCESS", null, null));
        rows.add(row(3, "live.hprof.gz", "ANALYZING", null, null));
        rows.add(row(4, "plain.hprof", "NOT_ANALYZED", null, null));
        rows.add(row(5, "x.gz.gz", "NOT_ANALYZED", null, null));

        HeapRecordNameMigration.Result r = migration.migrate();

        assertEquals(0, r.renamed + r.merged);
        Mockito.verify(historyRepo, Mockito.never()).save(any());
        Mockito.verify(historyRepo, Mockito.never()).delete(any());
        assertEquals(List.of("heapApp_20260622.tar.gz", "jeus_admin.hprof.gz", "live.hprof.gz", "plain.hprof", "x.gz.gz"),
                rows.stream().map(AnalysisHistoryEntity::getFilename).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("유령 이름에 붙은 GC 로그 매칭도 새 이름으로 옮긴다 — 자동 매칭은 미분석 기록도 후보로 삼는다")
    void movesGcLogMatches() {
        rows.add(row(9, "was1_heap.hprof.gz", "NOT_ANALYZED", null, null));
        GcLogAnalysisEntity gc = new GcLogAnalysisEntity();
        gc.setFilename("gc.log"); gc.setMatchedDumpFilename("was1_heap.hprof.gz"); gc.setMatchSource("manual");
        Mockito.when(gcRepo.findByMatchedDumpFilename("was1_heap.hprof.gz")).thenReturn(List.of(gc));

        HeapRecordNameMigration.Result r = migration.migrate();

        assertEquals(1, r.gcMatchesMoved);
        assertEquals("was1_heap.hprof", gc.getMatchedDumpFilename());
        assertEquals("manual", gc.getMatchSource(), "수동 연결은 수동 그대로");
        Mockito.verify(gcRepo).save(gc);
    }

    @Test
    @DisplayName("멱등 — 정리할 것이 없으면 아무것도 쓰지 않는다. 실패해도 기동을 막지 않는다")
    void idempotentAndNonFatal() {
        rows.add(row(1, "a.hprof", "NOT_ANALYZED", null, null));
        migration.onReady();
        Mockito.verify(historyRepo, Mockito.never()).save(any());

        Mockito.when(historyRepo.findAll()).thenThrow(new RuntimeException("DB down"));
        migration.onReady();   // 예외가 새지 않아야 한다
    }

    @Test
    @DisplayName("업로드 기록과 파일 목록은 같은 이름을 쓴다 — 목록은 FileTypeRules.listingName, 업로드 기록도 같은 함수")
    void uploadRecordAndListingAgree(@TempDir Path tmp) throws IOException {
        HeapDumpConfig config = Mockito.mock(HeapDumpConfig.class);
        Mockito.when(config.getDumpFilesDirectory()).thenReturn(tmp.toString());
        for (String n : new String[]{"jeus_admin.hprof.gz", "ibm-semeru-open-jre_ppc64_aix_21.0.11.0.tar.gz", "plain.hprof", "front.png"}) {
            Files.writeString(tmp.resolve(n), "x");
        }
        FileManagementService fm = new FileManagementService(config, Mockito.mock(HeapAnalysisResultCache.class));
        List<String> listed = fm.listFiles(true).stream().map(HeapDumpFile::getName).sorted().collect(Collectors.toList());
        List<String> canonical = new ArrayList<>();
        for (File f : tmp.toFile().listFiles()) canonical.add(FileTypeRules.listingName(f.getName()));
        canonical.sort(null);
        assertEquals(canonical, listed, "파일 목록 이름이 listingName 과 다르다 — 업로드 기록이 다시 유령 행이 된다");

        // HeapDumpAnalyzerService 는 생성 비용이 커서(의존 20여 개) 배선만 확인한다
        String svc = Files.readString(Path.of("src/main/java/com/heapdump/analyzer/service/HeapDumpAnalyzerService.java"),
                StandardCharsets.UTF_8);
        int at = svc.indexOf("public void saveUploadRecord(");
        assertTrue(at > 0, "saveUploadRecord 가 없다");
        String body = svc.substring(at, svc.indexOf("analysisHistoryRepository.save(entity)", at));
        assertTrue(body.contains("FileTypeRules.listingName(diskFilename)"), "업로드 기록이 목록 이름으로 저장되지 않는다");
        assertFalse(body.contains("findByFilename(diskFilename)"), "디스크 이름으로 기록을 찾는다");
    }
}

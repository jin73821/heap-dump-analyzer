package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.AnalysisHistoryEntity;
import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.AnalysisHistoryRepository;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import com.heapdump.analyzer.util.FileTypeRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 업로드 기록 이름 정리 (2026-09-19, 기동 시 1회 · 멱등).
 *
 * <p>종전 업로드는 {@code analysis_history} 에 '미분석' 기록을 <b>디스크 이름</b>({@code X.hprof.gz})으로 남겼다.
 * 그런데 파일 목록·분석은 {@code .gz} 를 뗀 <b>목록 이름</b>({@code X.hprof})으로 부르므로
 * ① 분석하면 기록이 따로 하나 더 생기고 ② 업로드 기록은 목록과 이름이 달라 파일이 있는데도 '삭제됨' 유령 행으로 남았다.
 * 새 업로드는 {@code saveUploadRecord} 가 목록 이름으로 남기고, 이미 쌓인 기록은 여기서 정리한다.
 *
 * <ul>
 *   <li><b>대상은 '미분석'(NOT_ANALYZED) 기록뿐</b> — 분석 산출물(결과 디렉토리·상세 JSON·AI 인사이트·Dominator refs)이 없어
 *       이름만 바꿔도 끊어지는 것이 없다. 분석된 기록은 산출물이 그 이름에 묶여 있으므로 건드리지 않는다.</li>
 *   <li>목록 이름의 기록이 이미 있으면(= 그 이름으로 분석됨) <b>병합</b> — 비어 있는 업로드자·출처 서버를 옮기고 유령 행을 지운다.
 *       없으면 <b>이름만 바꾼다</b>(순번·업로드자 유지).</li>
 *   <li>GC 로그 자동 매칭은 미분석 기록도 후보로 삼으므로, 유령 이름에 붙은 매칭도 새 이름으로 옮긴다.</li>
 * </ul>
 * 기존 서비스와 독립된 컴포넌트 — 초기화 순서·순환 의존에 얽히지 않게 {@link ApplicationReadyEvent} 에서 돈다.
 */
@Component
public class HeapRecordNameMigration {

    private static final Logger logger = LoggerFactory.getLogger(HeapRecordNameMigration.class);

    static final String NOT_ANALYZED = "NOT_ANALYZED";

    private final AnalysisHistoryRepository historyRepository;
    private final GcLogAnalysisRepository gcLogRepository;

    public HeapRecordNameMigration(AnalysisHistoryRepository historyRepository, GcLogAnalysisRepository gcLogRepository) {
        this.historyRepository = historyRepository;
        this.gcLogRepository = gcLogRepository;
    }

    /** 이 기록을 정리해야 하는가 — 미분석이고, 이름이 목록 이름과 다르다(= .gz 로 끝난다). */
    static boolean isGhostUploadRecord(AnalysisHistoryEntity e) {
        if (e == null || e.getFilename() == null || !NOT_ANALYZED.equals(e.getStatus())) return false;
        String to = FileTypeRules.listingName(e.getFilename());
        // X.gz.gz 처럼 떼고도 .gz 가 남는 이름은 건드리지 않는다(다른 기록과 사슬로 엮일 수 있다)
        return !to.equals(e.getFilename()) && !to.toLowerCase().endsWith(".gz");
    }

    /** 결과 요약 (로그·테스트용). */
    static final class Result {
        int renamed;
        int merged;
        int gcMatchesMoved;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Result r = migrate();
            if (r.renamed + r.merged > 0) {
                logger.info("[Migrate] 업로드 기록 이름 정리 완료 — 이름 변경 {}건 · 병합 {}건 · GC 로그 매칭 이동 {}건",
                        r.renamed, r.merged, r.gcMatchesMoved);
            }
        } catch (Exception e) {
            // 정리 실패는 기동을 막지 않는다 — 유령 행이 남을 뿐이고 다음 기동에서 다시 시도한다
            logger.warn("[Migrate] 업로드 기록 이름 정리 실패 (다음 기동에서 재시도): {}", e.toString());
        }
    }

    Result migrate() {
        Result r = new Result();
        List<AnalysisHistoryEntity> all = historyRepository.findAll();
        List<AnalysisHistoryEntity> ghosts = all.stream().filter(HeapRecordNameMigration::isGhostUploadRecord)
                .collect(Collectors.toList());
        if (ghosts.isEmpty()) return r;

        // 이름 → 기록. 이름 중복 행이 있어도 죽지 않게 첫 행만 (findByFilename 은 중복이면 예외를 던진다)
        Map<String, AnalysisHistoryEntity> byName = all.stream()
                .filter(e -> e.getFilename() != null)
                .collect(Collectors.toMap(AnalysisHistoryEntity::getFilename, Function.identity(), (a, b) -> a));

        for (AnalysisHistoryEntity ghost : new ArrayList<>(ghosts)) {
            String from = ghost.getFilename();
            String to = FileTypeRules.listingName(from);
            AnalysisHistoryEntity target = byName.get(to);
            if (target != null && target != ghost) {
                boolean copied = false;
                if (isBlank(target.getUploadedBy()) && !isBlank(ghost.getUploadedBy())) { target.setUploadedBy(ghost.getUploadedBy()); copied = true; }
                if (isBlank(target.getServerName()) && !isBlank(ghost.getServerName())) {
                    target.setServerName(ghost.getServerName());
                    target.setServerId(ghost.getServerId());
                    copied = true;
                }
                if (copied) historyRepository.save(target);
                historyRepository.delete(ghost);
                byName.remove(from);
                r.merged++;
                logger.info("[Migrate] action=merge-upload-record ghost={} ghostId={} into={} targetId={} targetStatus={} by=system",
                        from, ghost.getId(), to, target.getId(), target.getStatus());
            } else {
                ghost.setFilename(to);
                historyRepository.save(ghost);
                byName.remove(from);
                byName.put(to, ghost);
                r.renamed++;
                logger.info("[Migrate] action=rename-upload-record from={} to={} id={} by=system", from, to, ghost.getId());
            }
            r.gcMatchesMoved += moveGcMatches(from, to);
        }
        return r;
    }

    private int moveGcMatches(String from, String to) {
        int n = 0;
        for (GcLogAnalysisEntity g : gcLogRepository.findByMatchedDumpFilename(from)) {
            g.setMatchedDumpFilename(to);
            gcLogRepository.save(g);
            n++;
            logger.info("[Migrate] action=move-gclog-match gclog={} from={} to={} source={} by=system",
                    g.getFilename(), from, to, g.getMatchSource());
        }
        return n;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.GcLogAnalysisEntity;
import com.heapdump.analyzer.repository.GcLogAnalysisRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GC 로그의 인스턴스명 (2026-09-14) — 결과 페이지 KPI '인스턴스' 카드.
 *
 * <p>우선순위: ① GC 로그 기록의 수동 입력값({@code gc_log_analysis.instance_name}) ② 연결된 힙 덤프의 Instance
 * ({@link HeapDumpAnalyzerService#getEffectiveJeusInstance} — analyze 화면 Instance 칩과 같은 값). 덤프 값은 복사하지 않고
 * 조회 시점에 읽는다 — 연결을 바꾸거나 해제하면 카드도 따라 바뀌어야 하기 때문이다.
 *
 * <p>⚠ 이 서비스는 <b>컨트롤러만</b> 주입한다. {@code GcLogAnalyzerService}/{@code GcLogMatchService} 에 넣으면
 * {@code GcLogAnalyzerService → HeapDumpAnalyzerService → … → RemoteDumpService → GcLogAnalyzerService} 순환으로 기동이 실패한다.
 */
@Service
public class GcLogInstanceService {

    public static final int MAX_LEN = 100;

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_DUMP = "dump";
    public static final String SOURCE_NONE = "none";

    private final GcLogAnalysisRepository repository;
    private final HeapDumpAnalyzerService heapService;

    public GcLogInstanceService(GcLogAnalysisRepository repository, HeapDumpAnalyzerService heapService) {
        this.repository = repository;
        this.heapService = heapService;
    }

    /** 입력 정규화 — trim, 빈 값은 null(=수동값 없음), 100자 절단. */
    public static String normalize(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s;
    }

    /**
     * 순수 판정 — 화면용 뷰. 항상 모든 키를 채운다(값 없음은 빈 문자열).
     * {@code name}(표시값) · {@code source}(manual|dump|none) · {@code manual} · {@code dump}(연결 덤프의 값) · {@code dumpFilename}.
     */
    public static Map<String, Object> view(String manual, String dumpFilename, String dumpInstance) {
        String m = normalize(manual);
        String d = dumpFilename == null ? null : normalize(dumpInstance);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", m != null ? m : d != null ? d : "");
        v.put("source", m != null ? SOURCE_MANUAL : d != null ? SOURCE_DUMP : SOURCE_NONE);
        v.put("manual", m == null ? "" : m);
        v.put("dump", d == null ? "" : d);
        v.put("dumpFilename", dumpFilename == null ? "" : dumpFilename);
        return v;
    }

    /** 엔티티 기준 뷰 — 연결된 덤프가 있으면 그 Instance 를 읽는다. */
    public Map<String, Object> viewOf(GcLogAnalysisEntity e) {
        if (e == null) return view(null, null, null);
        String dump = e.getMatchedDumpFilename();
        String dumpInstance = dump == null ? null : heapService.getEffectiveJeusInstance(dump);
        return view(e.getInstanceName(), dump, dumpInstance);
    }

    /** 수동값 저장 — 빈 값이면 수동값을 지워 연결된 덤프 값으로 되돌린다. */
    public GcLogAnalysisEntity updateManual(GcLogAnalysisEntity e, String value) {
        e.setInstanceName(normalize(value));
        return repository.save(e);
    }
}

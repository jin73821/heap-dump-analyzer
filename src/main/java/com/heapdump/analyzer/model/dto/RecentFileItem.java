package com.heapdump.analyzer.model.dto;

import com.heapdump.analyzer.model.HeapDumpFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 Recent Files 한 줄 (2026-09-14) — 힙 덤프 저장소 파일과 GC 로그 저장소 파일을 한 목록으로 보인다.
 *
 * <p>종전엔 힙 저장소({@code listFiles})만 나열하고 상태도 힙 분석 이력만 봤다. 그래서 GC 로그는 대시보드에서 올려도 목록에
 * 없었고, 힙 저장소에 사본이 남은 {@code gc.log} 는 GC 로그 저장소에서 분석이 끝났는데도 '미분석' + 힙 분석 버튼으로 보였다.
 *
 * <p>{@code status} 는 저장소별 분석 상태 — {@code SUCCESS}/{@code ERROR}/{@code ANALYZING}/{@code NOT_ANALYZED}.
 * 버튼 경로·삭제 API 는 {@code kind}({@code heap}|{@code gclog})가 정한다(힙 경로는 GC 로그 저장소 파일을 못 찾는다).
 */
public class RecentFileItem {

    public static final String KIND_HEAP = "heap";
    public static final String KIND_GCLOG = "gclog";

    private final HeapDumpFile file;
    private final String kind;
    private final String status;
    /** 힙 저장소의 Others(힙 덤프 확장자 아님 + 분류 없음) — 분석 확인 모달이 경고를 붙인다. */
    private final boolean others;

    public RecentFileItem(HeapDumpFile file, String kind, String status, boolean others) {
        this.file = file;
        this.kind = kind;
        this.status = status == null ? "NOT_ANALYZED" : status;
        this.others = others;
    }

    public HeapDumpFile getFile() { return file; }
    public String getName() { return file.getName(); }
    public String getKind() { return kind; }
    public String getStatus() { return status; }
    public boolean isOthers() { return others; }
    public boolean isGcLog() { return KIND_GCLOG.equals(kind); }
    public boolean isAnalyzed() { return "SUCCESS".equals(status); }
    public boolean isError() { return "ERROR".equals(status); }
    public boolean isAnalyzing() { return "ANALYZING".equals(status); }
    public boolean isNotAnalyzed() { return !isAnalyzed() && !isError() && !isAnalyzing(); }

    /** 확장자 배지 — GC 로그는 회전 접미사({@code gc.log.0} → "0")가 확장자로 잡히므로 고정 라벨. */
    public String getBadge() { return isGcLog() ? "GC" : file.getExtension(); }

    /** 분석 확인 모달 kind — {@code analyze-confirm.js} 의 heap|others|gclog. */
    public String getConfirmKind() { return isGcLog() ? "gclog" : others ? "others" : "heap"; }

    /**
     * 두 저장소 목록을 합쳐 최신순으로 돌려준다(전체 — 자르는 건 호출자).
     *
     * <p>⚠ 힙 저장소에 <b>분석 기록이 없는</b> 파일이 GC 로그 저장소의 파일과 <b>이름·크기가 같으면</b> 같은 파일의 사본으로 보고
     * 힙 쪽 줄을 뺀다 — 안 그러면 {@code gc.log} 가 '분석 완료'(GC)와 '미분석'(힙) 두 줄로 보여 제보와 같은 혼란이 남는다.
     * 크기가 다르거나 힙에서 분석(성공·실패·진행)한 파일은 서로 다른 파일이므로 둘 다 남긴다. 파일 자체는 건드리지 않는다(Files 페이지에는 남아 있다).
     */
    public static List<RecentFileItem> merge(List<RecentFileItem> heap, List<RecentFileItem> gcLogs) {
        Map<String, Long> gcSize = new HashMap<>();
        for (RecentFileItem g : gcLogs) gcSize.put(g.getName(), g.getFile().getSize());
        List<RecentFileItem> out = new ArrayList<>(heap.size() + gcLogs.size());
        for (RecentFileItem h : heap) {
            Long gs = gcSize.get(h.getName());
            if (gs != null && gs == h.getFile().getSize() && h.isNotAnalyzed()) continue;
            out.add(h);
        }
        out.addAll(gcLogs);
        out.sort(Comparator.comparingLong((RecentFileItem i) -> i.getFile().getLastModified()).reversed());
        return out;
    }
}

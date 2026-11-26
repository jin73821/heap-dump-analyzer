package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.ThreadDumpResult;
import com.heapdump.analyzer.model.entity.ThreadDumpAnalysisEntity;

import java.util.List;
import java.util.Map;

/**
 * 스레드 덤프 AI 프롬프트 — 분석 결과에서 LLM 이 판단에 쓸 것만 뽑아 12,000자 안에 담는다(소견·교착 사슬·상위 락·
 * 정체 스레드·상위 스택 그룹). 원문 덤프는 넣지 않는다 — 수천 스레드의 반복 스택은 토큰만 쓰고 판단을 흐린다.
 */
public final class ThreadDumpPromptBuilder {

    private ThreadDumpPromptBuilder() {}

    static final int MAX_CHARS = 12_000;
    private static final int FRAMES_PER_STACK = 10;

    public static String build(ThreadDumpResult r, ThreadDumpAnalysisEntity e) {
        return build(r, e.getFilename(), e.getServerName(), null);
    }

    /**
     * @param extraSection 형식 지시 앞에 붙일 맥락(연결된 힙 덤프·힙 덤프에서 추출한 경우의 힙 정보). null 이면 없음.
     *                     본문을 자를 때 함께 잘릴 수 있다 — 형식 지시만 끝까지 지킨다.
     */
    public static String build(ThreadDumpResult r, String filename, String serverName, String extraSection) {
        StringBuilder sb = new StringBuilder(8192);
        ThreadDumpResult.Meta m = r.getMeta();
        ThreadDumpResult.Kpi k = r.getKpi();
        boolean heap = "heap".equals(m.getSource());
        sb.append(heap ? "다음은 힙 덤프를 만든 한 시점에 남은 스레드 정보를 분석한 결과입니다. 해석하고 근본 원인·조치를 제시하세요.\n"
                : "다음 JVM 스레드 덤프 분석 결과를 해석하고 근본 원인·조치를 제시하세요.\n");
        sb.append("\n== 개요 ==\n");
        sb.append(heap ? "힙 덤프: " : "파일: ").append(filename).append('\n');
        if (m.getFiles() != null) sb.append("이어 붙인 구성 파일: ").append(String.join(", ", m.getFiles())).append('\n');
        if (serverName != null) sb.append("서버: ").append(serverName).append('\n');
        if (m.getJvmVersionLine() != null) sb.append("JVM: ").append(m.getJvmVersionLine()).append('\n');
        sb.append("스냅샷: ").append(r.getSnapshots().size()).append("개");
        if (m.getSpanSec() != null) sb.append(" (전체 ").append(m.getSpanSec()).append("초, 간격 출처: ").append(m.getIntervalSource()).append(")");
        if (m.getRuns() > 1) sb.append(" — JVM 실행 ").append(m.getRuns()).append("개에 걸침");
        sb.append('\n');
        if (r.getSnapshots().size() == 1) sb.append("주의: 스냅샷이 1개뿐이라 스레드가 멈춰 있는지 지나가는 중인지 알 수 없습니다.\n");
        if (m.isTruncated() || r.getDiagnostics().getIncompleteSnapshots() > 0) sb.append("주의: 덤프가 일부만 읽혔습니다(잘림·상한).\n");

        sb.append("\n== 스냅샷별 상태 ==\n");
        int shown = 0;
        for (ThreadDumpResult.Snapshot s : r.getSnapshots()) {
            if (shown++ >= 12) { sb.append("  …(").append(r.getSnapshots().size() - 12).append("개 생략)\n"); break; }
            sb.append("  #").append(s.getIdx() + 1);
            if (s.getTsText() != null) sb.append(' ').append(s.getTsText());
            sb.append(": 스레드 ").append(s.getThreads()).append(" (Java ").append(s.getJavaThreads()).append(") ").append(s.getStates());
            if (s.getDeadlocks() > 0) sb.append(" 교착 ").append(s.getDeadlocks());
            if (!s.isComplete()) sb.append(" [미완: ").append(s.getIncompleteReason()).append(']');
            sb.append('\n');
        }
        sb.append("요약: 교착 ").append(k.getDeadlocks()).append("건, 최대 BLOCKED ").append(k.getBlockedMax())
                .append(", 정체 스레드 ").append(k.getStuck()).append(", 한 락의 최대 대기자 ").append(k.getTopLockWaiters()).append('\n');

        sb.append("\n== 이상 징후 (규칙 기반) ==\n");
        boolean any = false;
        for (ThreadDumpResult.Finding f : r.getFindings()) {
            if ("Info".equals(f.getSeverity())) continue;
            any = true;
            sb.append("- [").append(f.getSeverity()).append("] ").append(f.getTitle()).append(": ").append(f.getDetail()).append('\n');
        }
        if (!any) sb.append("(없음)\n");

        if (!r.getDeadlocks().isEmpty()) {
            sb.append("\n== 교착 사슬 ==\n");
            int n = 0;
            for (ThreadDumpResult.Deadlock d : r.getDeadlocks()) {
                if (n++ >= 3) break;
                for (ThreadDumpResult.Chain c : d.getChain()) {
                    sb.append("  \"").append(c.getName()).append("\" → 락 ").append(c.getAddr()).append(" (").append(c.getCls())
                            .append(") 대기, 소유: \"").append(c.getHeldByName()).append("\"\n");
                    appendStack(sb, r, c.getT(), 6);
                }
            }
        }

        if (!r.getLocks().isEmpty()) {
            sb.append("\n== 락 경합 (대기자 많은 순) ==\n");
            int n = 0;
            for (ThreadDumpResult.LockInfo l : r.getLocks()) {
                if (n++ >= 5) break;
                sb.append("  락 ").append(l.getAddr()).append(" (").append(l.getCls()).append("): 대기 ").append(l.getWaiterCount()).append("개, 소유: ")
                        .append(l.getOwner() >= 0 ? "\"" + nameOf(r, l.getOwner()) + "\"" : "알 수 없음").append('\n');
                if (l.getOwner() >= 0) appendStack(sb, r, l.getOwner(), FRAMES_PER_STACK);
            }
        }

        if (!r.getStuck().isEmpty()) {
            sb.append("\n== 같은 스택에 머문 스레드 ==\n");
            int n = 0;
            for (ThreadDumpResult.Stuck s : r.getStuck()) {
                if (n++ >= 8) { sb.append("  …외 ").append(r.getStuck().size() - 8).append("개\n"); break; }
                sb.append("  \"").append(nameOf(r, s.getT())).append("\" [").append(s.getCategory()).append("] 스냅샷 ")
                        .append(s.getFrom() + 1).append("~").append(s.getTo() + 1);
                if (s.getDurationSec() != null) sb.append(" (약 ").append(s.getDurationSec()).append("초)");
                if (s.getCpuDeltaMs() != null) sb.append(", CPU +").append(s.getCpuDeltaMs()).append("ms");
                sb.append('\n');
                appendFrames(sb, r, s.getStackId(), 8);
            }
        }

        if (!r.getCpuTop().isEmpty()) {
            sb.append("\n== CPU 사용 상위 (스냅샷 사이) ==\n");
            int n = 0;
            for (ThreadDumpResult.CpuTop c : r.getCpuTop()) {
                if (n++ >= 5) break;
                sb.append("  \"").append(nameOf(r, c.getT())).append("\": +").append(c.getDeltaMs()).append("ms (코어의 ").append(c.getPct()).append("%)\n");
            }
        }

        if (!r.getPools().isEmpty()) {
            sb.append("\n== 스레드 풀 (focus 스냅샷) ==\n");
            int focus = m.getFocusSnapshot(), n = 0;
            for (ThreadDumpResult.Pool p : r.getPools()) {
                if (focus >= p.getSizes().size() || p.getSizes().get(focus) == 0) continue;
                if (n++ >= 10) break;
                sb.append("  ").append(p.getStem()).append(" [").append(p.getKind()).append("]: ").append(p.getSizes().get(focus))
                        .append("개 (유휴 ").append(p.getIdle().get(focus)).append(", 작업 중 ").append(p.getActive().get(focus)).append(")\n");
            }
        }

        if (!r.getStackGroups().isEmpty()) {
            sb.append("\n== 같은 스택을 가진 스레드 묶음 (많은 순) ==\n");
            int n = 0;
            for (ThreadDumpResult.StackGroup g : r.getStackGroups()) {
                if (n++ >= 5) break;
                sb.append("  ").append(g.getCount()).append("개 [").append(g.getState()).append("]\n");
                appendFrames(sb, r, g.getStackId(), FRAMES_PER_STACK);
            }
        }

        if (extraSection != null && !extraSection.isBlank()) sb.append(extraSection.startsWith("\n") ? "" : "\n").append(extraSection);
        sb.append("\n다음 JSON 형식으로만 답하세요:\n");
        sb.append("{\"summary\": \"핵심 요약 2~3문장\", \"rootCause\": \"근본 원인 추정\", \"recommendations\": [\"조치 1\", \"조치 2\", \"조치 3\"], ");
        sb.append("\"severity\": \"Critical|High|Medium|Low\", \"severityDesc\": \"심각도 근거\", \"codeAdvice\": \"스택에서 보이는 클래스·메서드 기준의 수정 지점\"}\n");
        String out = sb.toString();
        if (out.length() <= MAX_CHARS) return out;
        // 형식 지시는 끝에 있어야 한다 — 본문을 자르고 지시를 다시 붙인다
        int tail = out.lastIndexOf("\n다음 JSON 형식으로만 답하세요:");
        String instr = tail >= 0 ? out.substring(tail) : "";
        return out.substring(0, Math.max(0, MAX_CHARS - instr.length() - 20)) + "\n...(truncated)" + instr;
    }

    private static String nameOf(ThreadDumpResult r, int track) {
        if (track < 0 || track >= r.getTracks().size()) return "?";
        int n = r.getTracks().get(track).getN();
        return n >= 0 && n < r.getNames().size() ? r.getNames().get(n) : "?";
    }

    /** 스레드의 focus 스냅샷 스택(없으면 마지막으로 스택이 있던 스냅샷). */
    private static void appendStack(StringBuilder sb, ThreadDumpResult r, int track, int max) {
        if (track < 0 || track >= r.getTracks().size()) return;
        ThreadDumpResult.Track t = r.getTracks().get(track);
        List<Integer> k = t.getK();
        int off = r.getMeta().getFocusSnapshot() - t.getFirst();
        int sid = off >= 0 && off < k.size() ? k.get(off) : -1;
        for (int i = k.size() - 1; sid < 0 && i >= 0; i--) sid = k.get(i);
        appendFrames(sb, r, sid, max);
    }

    private static void appendFrames(StringBuilder sb, ThreadDumpResult r, int stackId, int max) {
        if (stackId < 0 || stackId >= r.getStacks().size()) return;
        List<Integer> f = r.getStacks().get(stackId).getF();
        for (int i = 0; i < Math.min(max, f.size()); i++) {
            int fi = f.get(i);
            if (fi >= 0 && fi < r.getFrames().size()) sb.append("      at ").append(r.getFrames().get(fi)).append('\n');
        }
        if (f.size() > max) sb.append("      … (").append(f.size() - max + r.getStacks().get(stackId).getCut()).append("개 프레임 생략)\n");
    }

    /** 테스트·디버깅용 — 프롬프트가 참조하는 키 목록. */
    static Map<String, String> responseKeys() {
        return Map.of("summary", "핵심 요약", "rootCause", "근본 원인", "recommendations", "조치", "severity", "심각도",
                "severityDesc", "심각도 근거", "codeAdvice", "수정 지점");
    }
}

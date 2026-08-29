package com.heapdump.analyzer.service;

import com.heapdump.analyzer.util.MatErrorHint;
import com.heapdump.analyzer.util.MiddlewareDetector;
import com.heapdump.analyzer.util.OomDetector;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 앱 코드 안에만 존재하는 진단 지식 → RAG 색인용 문서.
 *
 * <p><b>왜 필요한가</b> — 검색 품질 실측에서 "코어덤프 SIGSEGV 인데 심볼이 안 보인다",
 * "MAT 이 OutOfMemoryError 로 실패한다" 같은 질의가 무관한 문서를 물어왔다. 원인은
 * 검색 엔진이 아니라 <b>코퍼스 구멍</b>이었다 — 이 지식들이 DB 도 문서도 아닌
 * <b>Java 코드 상수</b>에만 있어서 색인 대상이 아니었다.
 *
 * <p><b>왜 파이썬 색인기가 아니라 여기서 만드는가</b> — 손으로 베껴 두면 코드가 바뀔 때
 * 조용히 낡는다. enum 을 직접 읽으면 항목이 늘거나 문구가 바뀔 때 재색인만으로 따라간다.
 * 화면에 뜨는 안내 문구와 RAG 답변의 용어가 일치하는 것도 같은 이유로 중요하다 —
 * 사용자가 같은 문제를 두 이름으로 보면 안 된다.
 *
 * <p>출력 스키마는 파이썬 색인기(`/opt/chroma/app/sources.py`)와 동일하다:
 * {@code {id, document, metadata}}. id 는 {@code {source_type}:{origin_id}} 라
 * upsert 가 멱등이다.
 */
@Service
public class RagKnowledgeExportService {

    /** 코어덤프 분석 품질 경고 — 화면 문구(trigger)에 원인·조치를 붙여 색인한다. */
    private static final String[][] CORE_DUMP_KNOWLEDGE = {
        {"exec-missing", "실행 파일 없이 코어 단독으로 분석됨",
         "코어에 기록된 링크맵을 읽으려면 실행 파일이 필요하다. 코어 단독이면 시그널·레지스터·"
         + "로드된 모듈·크래시 지점까지는 나오지만 함수명 해석과 콜 체인 복원이 제한된다. "
         + "sysroot 를 붙여도 소용없다 — 링크맵 접근 자체가 안 되므로 실행 파일 페어링이 전제조건이다. "
         + "조치: 크래시가 난 서버에서 동일 빌드의 실행 파일을 가져와 함께 업로드한다."},
        {"solib-missing", "공유 라이브러리 정보 없음 — 실행 파일/라이브러리 경로가 코어와 매칭되지 않습니다",
         "GDB 는 코어에 기록된 .so 절대경로를 분석 서버의 로컬 파일시스템에서 연다. 그 경로에 파일이 "
         + "없으면 함수명이 ?? 로 남는다. 이건 정직한 실패라 오히려 안전하다 — 위험한 쪽은 같은 경로에 "
         + "다른 빌드가 있는 경우다(아래 항목). 조치: sysroot 번들을 수집해 업로드한다."},
        {"wrong-build", "같은 경로에 다른 버전 라이브러리가 있으면 경고 없이 가짜 심볼이 나온다",
         "gdb 8.2 는 build-id 검증 없이 로컬 파일을 열기 때문에, 원본 서버와 다른 빌드의 .so 가 같은 "
         + "경로에 있으면 경고 0건으로 가짜 함수명·인자값·소스라인을 출력한다. Syms Read=Yes 로 보여서 "
         + "신뢰해 버리기 쉽다. 이것이 '없는 것'보다 위험한 이유다. 조치: sysroot 번들로 원본 서버의 "
         + "라이브러리를 통째로 가져와 -iex 로 sysroot 와 solib-search-path 를 함께 지정한다."},
        {"stripped", "디버그 심볼 없음 — stripped 바이너리이거나 일치하는 실행 파일이 페어링되지 않았습니다",
         "함수 경계는 알아도 소스 라인·지역변수를 알 수 없다. 서드파티 라이브러리가 stripped 면 해당 "
         + "벤더의 debuginfo 를 확보하거나 벤더 이슈로 취급한다. 앱 바이너리가 stripped 면 빌드 시 "
         + "-g 를 넣고 별도 debuginfo 를 보관하는 운영 정책이 필요하다."},
        {"truncated", "일부 메모리 접근 불가 — 코어가 부분 저장(truncated)되었거나 매핑이 누락되었습니다",
         "ulimit -c 제한이나 디스크 부족으로 코어가 잘렸을 때 난다. 스택 일부만 남아 콜 체인이 "
         + "중간에서 끊긴다. 조치: ulimit -c unlimited 와 충분한 코어 저장 공간을 확보한다."},
        {"xstate", "레지스터 확장 상태(xstate) 일부 손상 — 레지스터 값 신뢰도가 낮을 수 있습니다",
         "레지스터 기반 인자 추정이 틀릴 수 있으므로, 이 경고가 있으면 인자값을 근거로 단정하지 않는다."},
        {"signal-plumbing", "콜스택 최상단이 raise/abort 면 그건 크래시 지점이 아니다",
         "raise, abort, gsignal, pthread_kill, __GI_raise, __stack_chk_fail, __libc_message, "
         + "__fortify_fail 같은 함수는 시그널을 다시 올리는 배관이다. 진짜 원인은 그 아래 프레임에 있으므로 "
         + "배관 프레임을 걷어내고 첫 애플리케이션/라이브러리 프레임을 찾아야 한다."},
        {"vmcore", "vmcore 는 GDB 로 분석할 수 없다",
         "vmcore 는 Linux 커널 크래시 덤프다. 유저 프로세스 코어와 형식이 다르므로 crash 유틸리티와 "
         + "커널 debuginfo(vmlinux)가 필요하다."},
        {"native-vendor", "네이티브 라이브러리 벤더 분류",
         "코어의 .so 경로로 책임 소재를 가른다. Oracle Client: libclntsh/libnnz/libclntshcore/libnque/"
         + "libsyscomm/oracore. Tmax: /tmax/, libsvr/libcli/liboras/libdhcli/libtmax. "
         + "glibc: libc-, libpthread, libm, libdl, librt, libnsl, libresolv, libnss, ld-linux. "
         + "GCC 런타임: libstdc++, libgcc_s. glibc/GCC 프레임에서 죽었다면 대개 호출자의 잘못된 인자가 "
         + "원인이지 라이브러리 버그가 아니다."},
    };

    /** 색인 대상 문서 전체. 파이썬 색인기가 이 결과를 그대로 upsert 한다. */
    public List<Map<String, Object>> export() {
        List<Map<String, Object>> docs = new ArrayList<>();
        addOomKinds(docs);
        addMatHints(docs);
        addMiddlewareVendors(docs);
        addCoreDumpKnowledge(docs);
        return docs;
    }

    private void addOomKinds(List<Map<String, Object>> out) {
        for (OomDetector.OomKind k : OomDetector.OomKind.values()) {
            if (k == OomDetector.OomKind.UNKNOWN) continue;   // 라벨만 있고 지식이 없다
            String body = "JVM 메시지: " + k.jvmMessage() + "\n"
                    + "원인: " + k.cause() + "\n"
                    + "권장 조치: " + k.recommendation();
            out.add(doc("oom_kind", k.name(),
                    "OutOfMemoryError — " + k.koLabel(), body, "OOM 진단", "high"));
        }
    }

    private void addMatHints(List<Map<String, Object>> out) {
        for (MatErrorHint h : MatErrorHint.values()) {
            String body = "증상 키워드: " + String.join(", ", h.keywords()) + "\n"
                    + h.hint() + "\n" + h.detail();
            out.add(doc("mat_hint", h.name(),
                    "MAT 분석 실패 — " + h.name(), body, "MAT 운영", "high"));
        }
    }

    private void addMiddlewareVendors(List<Map<String, Object>> out) {
        for (MiddlewareDetector.Vendor v : MiddlewareDetector.Vendor.values()) {
            StringBuilder b = new StringBuilder();
            b.append("힙 덤프에서 ").append(v.displayName())
             .append(" 를 식별하는 근거입니다.\n");
            if (v.prefixes().length > 0)
                b.append("클래스 패키지 접두사: ").append(String.join(", ", v.prefixes())).append("\n");
            if (v.sysPropMarkers().length > 0)
                b.append("시스템 프로퍼티 마커: ").append(String.join(", ", v.sysPropMarkers())).append("\n");
            if (v.versionPropKeys().length > 0)
                b.append("버전 확인 키: ").append(String.join(", ", v.versionPropKeys())).append("\n");
            b.append("시스템 프로퍼티 마커는 가중치가 높아 단독으로 확정 판정에 쓰이고, "
                    + "클래스 접두사는 스레드 스택·히스토그램에서 누적 점수로 계산합니다.");
            out.add(doc("vendor", v.name(),
                    v.displayName() + " 식별 마커", b.toString(), v.category(), "info"));
        }
    }

    private void addCoreDumpKnowledge(List<Map<String, Object>> out) {
        for (String[] e : CORE_DUMP_KNOWLEDGE) {
            // ⚠ body 에 제목을 다시 넣지 말 것 — doc() 이 이미 앞에 붙인다(중복은 임베딩 희석을 키운다).
            out.add(doc("core_warn", e[0], e[1], e[2], "코어덤프 분석", "high"));
        }
    }

    /**
     * CLI 진입점 — 색인기가 이 JAR 을 직접 실행해 JSON 을 받아간다.
     *
     * <pre>java -cp heap-analyzer.jar -Dloader.main=com.heapdump.analyzer.service.RagKnowledgeExportService \
     *      org.springframework.boot.loader.launch.PropertiesLauncher</pre>
     *
     * <p>HTTP 엔드포인트({@code /api/admin/rag/knowledge-export})는 ADMIN 인증이 필요해
     * 서버 사이드 색인기가 쓰기 어렵다. {@code heap_enc.sh}/{@code heap_dec.sh} 와 같은
     * 방식으로 CLI 를 함께 둔다 — 이 클래스는 Spring 의존이 없어 그대로 인스턴스화된다.
     */
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> docs = new RagKnowledgeExportService().export();
        // stdout 은 JSON 전용이다 — 진단 메시지는 stderr 로 보낸다(색인기가 그대로 파싱한다).
        System.err.println("[RagKnowledgeExport] " + docs.size() + " docs");
        System.out.println(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(docs));
    }

    private static Map<String, Object> doc(String sourceType, String originId, String title,
                                           String body, String category, String severity) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source_type", sourceType);
        meta.put("origin_id", originId);
        meta.put("title", title);
        meta.put("category", category);
        meta.put("severity", severity);
        meta.put("synthetic", false);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", sourceType + ":" + originId);
        // 제목을 본문 첫 줄에 넣는다 — Chroma 는 단일 필드만 임베딩하므로
        // 제목이 검색에 기여하려면 본문 안에 있어야 한다.
        d.put("document", title + "\n" + body);
        d.put("metadata", meta);
        return d;
    }
}

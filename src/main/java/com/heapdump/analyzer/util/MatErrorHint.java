package com.heapdump.analyzer.util;

import java.util.Arrays;
import java.util.List;

/**
 * MAT CLI 실패 출력 → 한국어 안내.
 *
 * <p>원래 {@code HeapDumpAnalyzerService.extractMatErrorHint()} 안에 문자열
 * 리터럴로 박혀 있던 것을 꺼냈다. 화면에 뜨는 안내 문구와 RAG 답변의 용어가
 * 어긋나면 사용자가 같은 문제를 두 가지 이름으로 보게 되므로,
 * <b>진단 경로와 지식 색인 경로가 같은 상수를 보게</b> 하는 것이 목적이다.
 *
 * <p>⚠ <b>선언 순서가 곧 매칭 우선순위다.</b> 원본이 if-else 사슬이었고
 * 먼저 걸리는 쪽이 이겼다. 순서를 바꾸면 판정이 달라진다 —
 * 예컨대 GENERIC 성격의 항목을 위로 올리면 구체적인 진단을 가린다.
 */
public enum MatErrorHint {

    OUT_OF_MEMORY(
            Arrays.asList("outofmemoryerror", "java.lang.outofmemory"),
            "Java OutOfMemoryError — MAT 실행에 더 많은 힙 메모리가 필요합니다. "
                    + "MemoryAnalyzer.ini의 -Xmx 값을 늘려주세요.",
            "MAT 자식 프로세스 자체가 힙 부족으로 죽은 경우다. 분석 대상 덤프가 큰데 "
                    + "MemoryAnalyzer.ini 의 -Xmx 가 작으면 발생한다. 덤프 크기의 절반 이상을 "
                    + "권장하되, 이 서버는 MAT 동시 실행 수가 호스트 RAM 기준으로 산정되므로 "
                    + "-Xmx 를 키우면 동시 실행 가능 수가 함께 줄어든다."),

    SNAPSHOT_BROKEN(
            Arrays.asList("snapshotexception", "error opening heap dump"),
            "힙 덤프 파일이 손상되었거나 지원하지 않는 형식입니다. "
                    + "유효한 HPROF/PHD 형식인지 확인하세요.",
            "전송 중 잘렸거나(크기 확인), gzip 해제가 덜 됐거나, HPROF 가 아닌 파일일 때 난다. "
                    + "`file` 명령으로 형식을 확인하고 원본 크기와 대조한다."),

    PERMISSION_DENIED(
            Arrays.asList("permission denied", "access denied"),
            "파일 또는 디렉토리 접근 권한이 부족합니다. 파일 권한을 확인하세요.",
            "덤프 파일·결과 디렉토리·MAT 설치 경로 중 하나의 권한 문제다. "
                    + "앱 프로세스 계정으로 읽기/쓰기가 되는지 확인한다."),

    FILE_NOT_FOUND(
            Arrays.asList("no such file", "file not found", "cannot find"),
            "파일을 찾을 수 없습니다. 경로가 올바른지 확인하세요.",
            "분석 중 작업본이 삭제됐거나(동시 실행 충돌), 압축 해제본 경로가 어긋난 경우다."),

    DISK_FULL(
            Arrays.asList("disk full", "no space left"),
            "디스크 공간이 부족합니다. 불필요한 파일을 정리한 후 다시 시도하세요.",
            "MAT 은 덤프와 별도로 인덱스 파일을 만든다. 덤프 크기의 30~50% 를 추가로 쓰므로 "
                    + "덤프가 들어갈 공간만 확보해서는 부족하다.");

    private final List<String> keywords;
    private final String hint;
    private final String detail;

    MatErrorHint(List<String> keywords, String hint, String detail) {
        this.keywords = keywords;
        this.hint = hint;
        this.detail = detail;
    }

    /** 화면에 그대로 표시되는 안내 문구. */
    public String hint() { return hint; }

    /** RAG 색인용 부연 — 화면에는 쓰지 않는다. */
    public String detail() { return detail; }

    public List<String> keywords() { return keywords; }

    /**
     * 소문자로 정규화된 MAT 출력에서 첫 매칭을 찾는다. 없으면 null.
     * 호출자가 폴백(마지막 Exception 라인)을 처리한다.
     */
    public static MatErrorHint match(String lowerOutput) {
        if (lowerOutput == null || lowerOutput.isEmpty()) return null;
        for (MatErrorHint h : values()) {
            for (String k : h.keywords) {
                if (lowerOutput.contains(k)) return h;
            }
        }
        return null;
    }
}

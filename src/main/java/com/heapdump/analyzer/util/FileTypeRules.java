package com.heapdump.analyzer.util;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 저장소 파일의 유형 판정 — Files 페이지 탭(Heap Dump / Corefile / Others)의 <b>단일 출처</b>.
 * Files 페이지·대시보드(Recent Files 의 others 표시)·현황판(최근 분석 현황)이 같은 규칙을 쓴다(2026-09-19 추출 — 종전엔
 * 컨트롤러 private 메서드와 인라인 분기라 새 화면이 규칙을 베끼다 어긋날 수 있었다).
 *
 * <p>분류 라벨({@code file-classifications.properties})은 <b>힙 덤프 확장자가 없는 파일</b>에만, 그리고
 * 모든 확장자 허용(allowAllExtensions)일 때만 적용된다. 확장자가 인식되면 라벨과 무관하게 힙 덤프다.
 *
 * <p>⚠ 이름 규칙은 둘이다 — 섞지 말 것.
 * <ul>
 *   <li><b>유형</b>({@link #hasRecognizedHeapDumpExtension}): {@code .hprof/.bin/.dump/.dmp} 와 그 {@code .gz} 만 힙 덤프.
 *       단독 {@code .gz}(예: {@code .tar.gz})는 인정하지 않는다 — 분석 시작 확인 창({@code analyze-confirm.js HEAP_EXT_RE})과 같은 기준.</li>
 *   <li><b>이름</b>({@link #listingName}): 힙 덤프 저장소의 {@code X.gz} 는 목록·분석·기록 모두 {@code X} 로 부른다
 *       (분석이 {@code X.gz} 를 {@code X} 로 풀어 분석하고, 분석 후 압축해도 이름이 유지되게 하는 규약).</li>
 * </ul>
 */
public final class FileTypeRules {

    public static final String HEAPDUMP = "heapdump";
    public static final String OTHERS = "others";
    public static final String EXEC = "exec";

    private FileTypeRules() {}

    /** 힙 덤프 확장자 — 분석 시작 확인 창의 {@code HEAP_EXT_RE} 와 같은 식. 바꾸면 양쪽을 함께 바꿀 것. */
    private static final Pattern HEAP_EXT = Pattern.compile("\\.(hprof|bin|dump|dmp)(\\.gz)?$", Pattern.CASE_INSENSITIVE);

    /**
     * 분석 대상으로 인식하는 힙 덤프 확장자 — {@code .hprof/.bin/.dump/.dmp} 와 그 gzip 압축본.
     * 단독 {@code .gz} 는 아니다(2026-09-19 — 종전엔 모든 {@code .gz} 를 인정해 {@code .tar.gz} 가 힙 덤프로 집계됐다).
     */
    public static boolean hasRecognizedHeapDumpExtension(String name) {
        return name != null && HEAP_EXT.matcher(name).find();
    }

    /**
     * 힙 덤프 저장소 파일의 목록 이름 — {@code .gz} 로 끝나면 떼어낸다({@code X.hprof.gz} → {@code X.hprof}).
     * 파일 목록(listFiles)·업로드 기록이 이 이름을 쓴다. 기록을 디스크 이름({@code X.gz})으로 남기면 목록과 이름이 달라
     * 파일이 있는데도 '삭제됨' 으로 보이는 유령 행이 된다(2026-09-19 수정 전 업로드 기록).
     */
    public static String listingName(String name) {
        if (name == null || name.length() <= 3) return name;
        return name.toLowerCase().endsWith(".gz") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * 힙 덤프 저장소 파일의 유형 — {@code heapdump} 이거나, 라벨이 적용되면 그 라벨(core/exec/heapdump/others),
     * 라벨이 없으면 {@code others}.
     */
    public static String heapDirType(String name, boolean allowAllExtensions, Map<String, String> classifications) {
        if (!allowAllExtensions || hasRecognizedHeapDumpExtension(name)) return HEAPDUMP;
        String cls = classifications != null ? classifications.get(name) : null;
        return cls != null ? cls : OTHERS;
    }

    /**
     * 코어덤프 저장소 파일에 적용할 라벨 — {@code exec} 또는 {@code others}, 적용할 것이 없으면 null(현재 유형 유지).
     * core 라벨은 무시한다(코어 저장소 파일은 원래 코어다).
     */
    public static String coreDirLabel(String name, boolean allowAllExtensions, Map<String, String> classifications) {
        if (!allowAllExtensions || hasRecognizedHeapDumpExtension(name)) return null;
        String cls = classifications != null ? classifications.get(name) : null;
        return EXEC.equals(cls) || OTHERS.equals(cls) ? cls : null;
    }
}

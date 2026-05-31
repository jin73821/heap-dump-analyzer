package com.heapdump.analyzer.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스레드 스택 트레이스에서 {@code java.lang.OutOfMemoryError} 를 감지하고 종류(subtype)를 분류한다.
 *
 * <p>JVM 이 stderr 로 출력하는 OOM 에는 항상 메시지("Java heap space" 등)가 붙지만,
 * MAT 스레드 덤프는 OOM 을 스택 프레임 형태(<code>at java.lang.OutOfMemoryError.&lt;init&gt;()</code>)
 * 로만 표기하고 메시지를 포함하지 않는 경우가 대부분이다. 이때는 OOM 이 throw 된 직전 호출
 * 프레임의 시그니처로부터 종류를 <b>추정</b>한다 (예: {@code Thread.start0} → 네이티브 스레드 생성 실패,
 * {@code Bits.reserveMemory} → 다이렉트 버퍼 메모리 부족, {@code defineClass} → 메타스페이스 부족).
 *
 * <p>설계 원칙: 명시적 메시지가 있으면 그대로 신뢰하고, 없을 때만 <b>강한 시그니처</b>가 있는 경우에
 * 한해 추정한다. 시그니처가 모호하면 종류를 비워(null) 잘못된 라벨링을 피한다.
 */
public final class OomDetector {

    private OomDetector() {}

    /** OutOfMemoryError 등장 + (선택) 콜론 뒤 메시지 캡쳐. */
    private static final Pattern OOM_PATTERN =
            Pattern.compile("java\\.lang\\.OutOfMemoryError(?::\\s*([^\\r\\n]+))?");

    /** OOM 종류 — JVM 표준 메시지(영문)·한국어 라벨·원인 요약·권장 조치를 함께 보유. */
    public enum OomKind {
        HEAP_SPACE("Java heap space", "힙 공간 부족",
                "라이브 객체가 힙(-Xmx) 최대치를 초과해 더 이상 할당할 수 없는 상태입니다.",
                "Dominator Tree·Leak Suspects로 최대 점유 객체를 확인하고, 누수가 없다면 -Xmx 를 상향하세요."),
        GC_OVERHEAD("GC overhead limit exceeded", "GC 오버헤드 한계 초과",
                "GC에 과도한 시간을 쓰지만 회수되는 메모리가 거의 없어 사실상 힙이 가득 찬 상태입니다.",
                "힙 공간 부족과 동일하게 누수 객체를 점검하고, 필요 시 -Xmx 상향 또는 객체 생명주기를 단축하세요."),
        METASPACE("Metaspace", "메타스페이스 부족",
                "로드된 클래스 메타데이터가 Metaspace 한계를 초과했습니다(클래스로더 누수가 흔한 원인).",
                "클래스로더/동적 클래스 생성 누수를 확인하고, -XX:MaxMetaspaceSize 를 상향하세요."),
        COMPRESSED_CLASS_SPACE("Compressed class space", "압축 클래스 공간 부족",
                "압축 클래스 포인터 공간이 한계를 초과했습니다(과다 클래스 로딩).",
                "-XX:CompressedClassSpaceSize 상향 또는 클래스로더 누수를 점검하세요."),
        PERMGEN("PermGen space", "PermGen 공간 부족",
                "구버전 JVM의 PermGen 영역이 가득 찼습니다(클래스/인턴 문자열 누적).",
                "클래스로더 누수를 점검하고 -XX:MaxPermSize 상향 또는 JVM 업그레이드를 검토하세요."),
        DIRECT_BUFFER("Direct buffer memory", "다이렉트 버퍼 메모리 부족",
                "NIO 다이렉트 버퍼(off-heap) 할당이 한계를 초과했습니다(버퍼 미해제가 흔함).",
                "DirectByteBuffer 해제 여부를 확인하고 -XX:MaxDirectMemorySize 를 점검/상향하세요."),
        NATIVE_THREAD("unable to create new native thread", "네이티브 스레드 생성 실패",
                "OS 스레드 수 한계 또는 네이티브 메모리 부족으로 새 스레드를 만들지 못했습니다.",
                "스레드 풀 크기·누수를 점검하고, OS ulimit(nproc)·스레드 스택 크기(-Xss)를 조정하세요."),
        ARRAY_SIZE("Requested array size exceeds VM limit", "배열 크기 VM 한계 초과",
                "요청한 배열 크기가 VM이 허용하는 최대치를 초과했습니다.",
                "비정상적으로 큰 배열 할당 로직(잘못된 크기 계산)을 점검하세요."),
        OUT_OF_SWAP("Out of swap space?", "스왑 공간 부족",
                "OS의 스왑 공간까지 소진되어 네이티브 메모리를 확보하지 못했습니다.",
                "전체 메모리 사용량과 다른 프로세스를 점검하고, 힙/네이티브 메모리 사용을 줄이세요."),
        NATIVE_MEMORY("native memory allocation failed", "네이티브 메모리 할당 실패",
                "JVM 외부 네이티브 메모리(malloc/mmap) 할당에 실패했습니다.",
                "off-heap 사용량(다이렉트 버퍼·JNI·압축 등)과 OS 가용 메모리를 점검하세요."),
        KILL_PROCESS("Kill process or sacrifice child", "OS OOM Killer 작동",
                "OS의 OOM Killer가 메모리 부족으로 프로세스를 종료시켰습니다.",
                "컨테이너/호스트 메모리 한계와 힙+네이티브 총 사용량을 점검해 메모리를 확보하세요."),
        UNKNOWN(null, "종류 불명",
                "OutOfMemoryError가 감지되었으나 종류를 특정하지 못했습니다.",
                "Thread 스택과 Dominator Tree·Leak Suspects를 함께 확인해 원인을 좁혀 보세요.");

        private final String jvmMessage;
        private final String koLabel;
        private final String cause;
        private final String recommendation;
        OomKind(String jvmMessage, String koLabel, String cause, String recommendation) {
            this.jvmMessage = jvmMessage; this.koLabel = koLabel;
            this.cause = cause; this.recommendation = recommendation;
        }
        public String jvmMessage() { return jvmMessage; }
        public String koLabel() { return koLabel; }
        public String cause() { return cause; }
        public String recommendation() { return recommendation; }
    }

    /** 감지 결과. */
    public static final class Result {
        public final boolean oom;
        public final OomKind kind;
        /** 화면 표시용 종류 문자열. 명시 메시지면 원문, 추정이면 "표준메시지 (추정)", 불명이면 null. */
        public final String displayType;
        /** 종류가 스택 시그니처로부터 추정되었는지 여부. */
        public final boolean inferred;

        Result(boolean oom, OomKind kind, String displayType, boolean inferred) {
            this.oom = oom; this.kind = kind; this.displayType = displayType; this.inferred = inferred;
        }
        static final Result NONE = new Result(false, null, null, false);
    }

    /**
     * 스택 트레이스에서 OOM 을 감지하고 종류를 판정/추정한다.
     * @param stack 스레드 스택 트레이스(.threads 블록). null/빈 문자열이면 미감지.
     */
    public static Result detect(String stack) {
        if (stack == null || stack.isEmpty()) return Result.NONE;
        Matcher m = OOM_PATTERN.matcher(stack);
        if (!m.find()) return Result.NONE;

        // 1) 명시적 메시지가 있으면 그대로 신뢰 (JVM 표준 OOM 메시지).
        String explicit = m.group(1);
        if (explicit != null && !explicit.trim().isEmpty()) {
            String msg = explicit.trim();
            return new Result(true, classifyMessage(msg), msg, false);
        }

        // 2) 메시지 부재 (MAT 프레임 형태) → throw 직전 호출 프레임 시그니처로 추정.
        OomKind inferredKind = inferFromFrames(stack);
        if (inferredKind != OomKind.UNKNOWN) {
            return new Result(true, inferredKind, inferredKind.jvmMessage() + " (추정)", true);
        }

        // 3) 특이 시그니처 없음 → 실무상 절대다수가 힙 공간 부족(또는 GC 오버헤드, 동일 원인 계열)이므로
        //    "Java heap space" 로 추정하되 "(추정)" 으로 불확실성을 명시.
        return new Result(true, OomKind.HEAP_SPACE, OomKind.HEAP_SPACE.jvmMessage() + " (추정)", true);
    }

    /** 명시적 OOM 메시지 문자열을 표준 종류로 분류 (대소문자 무시 부분일치). */
    public static OomKind classifyMessage(String msg) {
        if (msg == null) return OomKind.UNKNOWN;
        String s = msg.toLowerCase();
        if (s.contains("gc overhead"))                 return OomKind.GC_OVERHEAD;
        if (s.contains("compressed class"))            return OomKind.COMPRESSED_CLASS_SPACE;
        if (s.contains("metaspace"))                   return OomKind.METASPACE;
        if (s.contains("permgen"))                     return OomKind.PERMGEN;
        if (s.contains("direct buffer"))               return OomKind.DIRECT_BUFFER;
        if (s.contains("native thread"))               return OomKind.NATIVE_THREAD;
        if (s.contains("array size"))                  return OomKind.ARRAY_SIZE;
        if (s.contains("swap space"))                  return OomKind.OUT_OF_SWAP;
        if (s.contains("kill process"))                return OomKind.KILL_PROCESS;
        if (s.contains("native memory") || s.contains("mmap")) return OomKind.NATIVE_MEMORY;
        if (s.contains("heap space"))                  return OomKind.HEAP_SPACE;
        return OomKind.UNKNOWN;
    }

    // ── 스택 프레임 시그니처 기반 추정 ──────────────────────────────
    // 각 종류별로 throw 직전 호출 경로에 거의 반드시 등장하는 프레임을 매칭한다.
    // 가장 특이도(specificity) 높은 것부터 검사.

    private static final Pattern SIG_NATIVE_THREAD = Pattern.compile(
            "java\\.lang\\.Thread\\.start0|java\\.lang\\.Thread\\.start\\(|"
          + "java\\.util\\.concurrent\\.ThreadPoolExecutor\\.addWorker");

    private static final Pattern SIG_DIRECT_BUFFER = Pattern.compile(
            "java\\.nio\\.Bits\\.reserveMemory|java\\.nio\\.DirectByteBuffer\\.<init>|"
          + "java\\.nio\\.Bits\\.reserve|sun\\.nio\\.ch\\.Util\\.getTemporaryDirectBuffer");

    private static final Pattern SIG_CLASS_SPACE = Pattern.compile(
            "java\\.lang\\.ClassLoader\\.defineClass|ClassLoader\\.defineClass[12]|"
          + "java\\.lang\\.reflect\\.Proxy|sun\\.instrument\\.|jdk\\.internal\\.reflect\\.");

    /** 강한 시그니처가 있을 때만 종류 반환, 없으면 UNKNOWN. */
    static OomKind inferFromFrames(String stack) {
        if (SIG_NATIVE_THREAD.matcher(stack).find()) return OomKind.NATIVE_THREAD;
        if (SIG_DIRECT_BUFFER.matcher(stack).find())  return OomKind.DIRECT_BUFFER;
        if (SIG_CLASS_SPACE.matcher(stack).find())    return OomKind.METASPACE;
        return OomKind.UNKNOWN;
    }

    /**
     * 스택에 OOM 을 직접 유발할 수 있는 프레임(네이티브 스레드 생성 / 다이렉트 버퍼 예약)이 있는지.
     *
     * <p>"unable to create new native thread" 는 {@code Thread.start0} (네이티브)에서 VM 이 직접
     * throw 하여 {@code OutOfMemoryError.<init>} 프레임이 스택에 없을 수 있다 → {@link #detect} 가 놓침.
     * 이런 스레드를 OQL local-참조 교차검증 대상으로 삼기 위한 사전 필터.
     */
    public static boolean hasOomProneFrames(String stack) {
        if (stack == null || stack.isEmpty()) return false;
        return SIG_NATIVE_THREAD.matcher(stack).find() || SIG_DIRECT_BUFFER.matcher(stack).find();
    }
}

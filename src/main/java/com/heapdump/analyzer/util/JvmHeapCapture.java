package com.heapdump.analyzer.util;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원격 서버 java 프로세스의 힙 설정(-Xms/-Xmx) 수집 — 순수 파싱·매칭 유틸 (2026-09-11).
 *
 * <p>힙덤프(hprof)에는 JVM 힙 옵션이 없고 MAT System Properties 에도 {@code -Xmx} 는 나오지 않는다.
 * 덤프를 SSH 로 가져오는 시점에 같은 접속으로 그 서버의 java 프로세스 정보를 읽어 두는 것이 이 유틸의 목적이다.
 *
 * <p><b>왜 {@code ps -o args} 가 아니라 {@code /proc/<pid>/cmdline} 인가</b> — ps 는 폭 절단({@code -ww} 없으면 80열),
 * 공백이 든 인자를 토큰화할 수 없고, {@code etimes} 는 procps-ng 3.3+ 전용이라 구형 서버에서 명령 전체가 실패한다.
 * pid/user/etime 만 ps 로 얻고 인자는 {@code /proc} 에서 NUL 구분 그대로 읽는다.
 *
 * <p><b>왜 base64 로 보내는가</b> — 원격 명령은 로컬 {@code bash -c "ssh … \"…\""} → 원격 sh 의 3중 인용을 지난다.
 * 스크립트의 {@code $p}·{@code $(…)} 가 로컬 bash 에서 먼저 확장되는 것을 막으려면 인용 규칙과 무관한 표현이 필요하다.
 * base64 문자집합 {@code [A-Za-z0-9+/=]} 에는 셸 특수문자가 없다.
 *
 * <p><b>보안</b> — 저장·표시하는 옵션은 allow-list({@link #STORED_OPTION}) 통과분뿐이다. {@code -D} 는 매칭 키
 * ({@link #MARKER_KEYS}) 값만 남기고 나머지({@code -Ddb.password=…}, {@code -javaagent:…=key}, {@code -XX:OnOutOfMemoryError=})는
 * 파싱 즉시 폐기한다 — raw 명령줄은 어떤 로그 레벨에도 남기지 않는다.
 *
 * <p><b>한계</b> — 이미 종료돼 재기동도 안 된 JVM, {@code hidepid=2} 마운트, 다른 계정 프로세스의 environ(EACCES),
 * {@code @argfile} 의 내용은 볼 수 없다. jcmd attach 는 euid 일치 필요·세이프포인트 유발이라 쓰지 않는다.
 */
public final class JvmHeapCapture {

    private JvmHeapCapture() {}

    public static final int SCHEMA = 2; // 2: Candidate.gcLogPath (2026-09-14)
    public static final int MAX_CANDIDATES = 20;
    public static final int MAX_OPTIONS_PER_CANDIDATE = 40;
    public static final int MAX_OPTION_LEN = 200;
    public static final int MAX_MARKER_LEN = 200;
    /** MariaDB TEXT(64KB) 안에 여유를 두고 들어가야 한다. */
    public static final int MAX_JSON_BYTES = 60 * 1024;

    // ── 원격 스크립트 ────────────────────────────────────────────

    /**
     * 원격에서 실행되는 POSIX sh 스크립트(사용자 입력 0). 파서는 {@code __MEM__}~{@code __END__} 사이만 본다.
     * 앞의 배너/MOTD 는 자동으로 무시되고 {@code __END__} 가 없으면 truncated 로 기록한다.
     */
    public static final String REMOTE_SCRIPT = String.join("\n",
            "echo __MEM__",
            "grep -E '^(MemTotal|MemAvailable):' /proc/meminfo 2>/dev/null",
            "echo \"__NOW__ $(date +%s 2>/dev/null)\"",
            "echo \"__NPROC__ $(ls /proc 2>/dev/null | grep -c '^[0-9]')\"",
            // 후보 0개일 때 이유를 가리기 위한 환경 정보(2026-09-16) — OS·접속 계정·/proc 마운트 옵션(hidepid)·ps 사용 가능 여부
            "echo \"__UNAME__ $(uname -s 2>/dev/null)\"",
            "echo \"__WHO__ $(id -un 2>/dev/null)\"",
            "echo \"__PROCMNT__ $(awk '$2 == \"/proc\" {print $4; exit}' /proc/mounts 2>/dev/null)\"",
            "ps -p $$ -o pid= >/dev/null 2>&1 && echo __PSOK__",
            "echo __PS__",
            // java 프로세스 찾기(2026-09-16 확장) — ① 실행 파일 이름(comm)이 java* ② 명령줄 첫 인자 이름이 java* ③ 명령줄에 JVM 옵션
            // (-Xms/-Xmx·-XX:·-Djava.·-Djeus.·-Dweblogic.) — 이름을 바꾼 java 바이너리·래퍼(jsvc 등)로 띄운 WAS 도 잡는다.
            // ps 한 번씩만 쓴다(운영자가 늘 치는 ps -ef 와 같은 비용). 폭 절단을 피하려고 -ww, 지원하지 않으면 일반 -o 로 폴백.
            // 여기서 넓게 모은 pid 는 /proc/<pid>/cmdline 을 읽은 뒤 파서(looksLikeJava)가 한 번 더 거른다.
            "JPIDS=$( { ps -eo pid=,comm= 2>/dev/null | awk '$2 ~ /^java/ {print $1}';"
                    + " { ps -eww -o pid=,args= 2>/dev/null || ps -eo pid=,args= 2>/dev/null; }"
                    + " | awk '{ b=$2; sub(/.*\\//, \"\", b); if (b ~ /^java/) { print $1; next }"
                    + " for (i = 3; i <= NF; i++) if ($i ~ /^-(Xm[sx][0-9]|XX:|Djava\\.|Djeus\\.|Dweblogic\\.)/) { print $1; next } }'; }"
                    + " | awk '!s[$1]++' | head -" + MAX_CANDIDATES + " )",
            "for p in $JPIDS; do",
            "  echo \"__PID__ $p\"",
            "  echo \"__PSLINE__ $(ps -o user=,etime= -p \"$p\" 2>/dev/null)\"",
            "  echo \"__EXE__ $(readlink \"/proc/$p/exe\" 2>/dev/null)\"",
            "  echo \"__CWD__ $(readlink \"/proc/$p/cwd\" 2>/dev/null)\"",
            "  printf '__CMD__ '; tr '\\0' '\\037' < \"/proc/$p/cmdline\" 2>/dev/null | head -c 8000; echo",
            "  [ -r \"/proc/$p/environ\" ] && echo __ENVOK__",
            "  tr '\\0' '\\n' < \"/proc/$p/environ\" 2>/dev/null | grep -E '^(JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)=' | head -c 4000 | sed 's/^/__ENV__ /'",
            "  grep -qE 'docker|kubepods|containerd|lxc' \"/proc/$p/cgroup\" 2>/dev/null && echo __CONTAINER__",
            "done",
            "echo __END__");

    /**
     * 실행할 스크립트 본문. remotePath 가 있으면 원격 mtime 조회 한 줄을 덧붙인다(추가 왕복 없음).
     * 경로는 단일 인용 + {@code '\''} 이스케이프 — 원격 sh 만 이 문자열을 본다(base64 로 감싸 로컬 셸은 보지 않는다).
     */
    public static String buildScript(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) return REMOTE_SCRIPT;
        String safe = remotePath.replace("'", "'\\''");
        return "echo \"__MTIME__ $(stat -c %Y '" + safe + "' 2>/dev/null)\"\n" + REMOTE_SCRIPT;
    }

    /** ssh 에 넘길 한 줄 명령 — 셸 특수문자가 전혀 없는 base64 로 스크립트를 실어 보낸다. */
    public static String buildRemoteCommand(String remotePath) {
        String b64 = Base64.getEncoder().encodeToString(buildScript(remotePath).getBytes(StandardCharsets.UTF_8));
        return "echo " + b64 + " | base64 -d | sh";
    }

    // ── 모델 ────────────────────────────────────────────────────

    /** java 프로세스 1개. options/markers 는 allow-list 통과분만 담긴다. */
    public record Candidate(int pid, String user, Long startEpoch, String mainClass, String cwd, String exe,
                            Long xmsBytes, Long xmxBytes, String xmxOrigin, String heapDumpPath, String gcLogPath,
                            List<String> options, int otherOptionCount, Map<String, String> markers,
                            boolean envReadable, boolean envUsed, boolean argfile, boolean container) {
        public Candidate {
            options = options == null ? List.of() : List.copyOf(options);
            markers = markers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
        }
    }

    /**
     * 원격 환경 정보(2026-09-16) — 후보가 0개일 때 이유(목록 제한·java 없음·미지원 OS)를 가리는 근거.
     * 옛 JSON(필드 없음)은 null.
     *
     * @param os            {@code uname -s}(예: Linux, AIX). 모르면 null
     * @param user          SSH 접속 계정({@code id -un})
     * @param procMountOpts {@code /proc} 마운트 옵션(예: {@code rw,nosuid,hidepid=2}). 모르면 null
     * @param psOk          {@code ps -p $$ -o pid=} 성공 여부 — false 면 ps 옵션 비호환
     */
    public record RemoteEnv(String os, String user, String procMountOpts, boolean psOk) {}

    /** 후보 0개의 이유 — code(PROC_UNAVAILABLE/PS_UNAVAILABLE/LIST_RESTRICTED/NO_JAVA)·짧은 라벨·안내 문구. */
    public record NoCandidateReason(String code, String label, String message) {}

    /** 수집 1회의 결과. 전송 로그 {@code jvm_info} 에 JSON 으로 저장된다. */
    public record Capture(int schema, long capturedAtEpoch, Long remoteNowEpoch, long memTotal, long memAvailable,
                          int procVisible, boolean truncated, Long dumpMtimeEpoch,
                          List<Candidate> candidates, Integer matchedIndex, String matchReason,
                          List<String> matchFlags, String note, RemoteEnv remoteEnv) {
        public Capture {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            matchFlags = matchFlags == null ? List.of() : List.copyOf(matchFlags);
        }
        /** 환경 정보 없는 생성(테스트·실패 기록·옛 호출부). */
        public Capture(int schema, long capturedAtEpoch, Long remoteNowEpoch, long memTotal, long memAvailable,
                       int procVisible, boolean truncated, Long dumpMtimeEpoch,
                       List<Candidate> candidates, Integer matchedIndex, String matchReason,
                       List<String> matchFlags, String note) {
            this(schema, capturedAtEpoch, remoteNowEpoch, memTotal, memAvailable, procVisible, truncated, dumpMtimeEpoch,
                    candidates, matchedIndex, matchReason, matchFlags, note, null);
        }
        public Capture withMatch(Match m) {
            return new Capture(schema, capturedAtEpoch, remoteNowEpoch, memTotal, memAvailable, procVisible, truncated,
                    dumpMtimeEpoch, candidates, m.index(), m.reason(), new ArrayList<>(m.flags()), note, remoteEnv);
        }
        public Capture withNote(String n) {
            return new Capture(schema, capturedAtEpoch, remoteNowEpoch, memTotal, memAvailable, procVisible, truncated,
                    dumpMtimeEpoch, candidates, matchedIndex, matchReason, matchFlags, n, remoteEnv);
        }
        public Candidate matched() {
            return matchedIndex != null && matchedIndex >= 0 && matchedIndex < candidates.size()
                    ? candidates.get(matchedIndex) : null;
        }
        /** 후보가 0인데 보이는 프로세스도 적다 — {@code hidepid=2} 등으로 목록이 제한됐을 가능성. */
        public boolean processListRestricted() {
            return candidates.isEmpty() && procVisible >= 0 && procVisible < 30;
        }
    }

    /** 매칭 결과. index 가 null 이면 미확정. */
    public record Match(Integer index, String reason, Set<String> flags) {
        public Match {
            flags = flags == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(flags));
        }
    }

    // ── 플래그 라벨(화면·PDF 공용, 한국어) ─────────────────────────

    public static final String FLAG_ESTIMATED = "estimated";
    public static final String FLAG_ERGONOMIC = "ergonomic";
    public static final String FLAG_RESTARTED = "restarted";
    public static final String FLAG_ENV = "env";
    public static final String FLAG_ENV_UNKNOWN = "env-unknown";
    public static final String FLAG_ARGFILE = "argfile";
    public static final String FLAG_CONTAINER = "container";
    public static final String FLAG_AMBIGUOUS_PID = "ambiguous-pid";

    private static final Map<String, String> FLAG_LABELS = Map.of(
            FLAG_ESTIMATED, "추정",
            FLAG_ERGONOMIC, "비율 환산",
            FLAG_RESTARTED, "재기동 후",
            FLAG_ENV, "환경변수",
            FLAG_ENV_UNKNOWN, "환경변수 미확인",
            FLAG_ARGFILE, "@argfile",
            FLAG_CONTAINER, "컨테이너",
            FLAG_AMBIGUOUS_PID, "프로세스 미확정");

    public static String flagLabel(String flag) {
        return FLAG_LABELS.getOrDefault(flag, flag);
    }

    /** 플래그 → 한 줄 설명(툴팁). */
    public static String flagHint(String flag) {
        return switch (flag) {
            case FLAG_ESTIMATED -> "-Xmx 미지정 — 물리 메모리의 1/4(JVM 기본 ergonomics)로 추정한 값";
            case FLAG_ERGONOMIC -> "-XX:MaxRAMPercentage 를 물리 메모리로 환산한 값";
            case FLAG_RESTARTED -> "덤프 생성 이후에 기동된 프로세스의 설정(재기동 전 설정과 다를 수 있음)";
            case FLAG_ENV -> "JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS 환경변수에서 읽은 값 포함";
            case FLAG_ENV_UNKNOWN -> "프로세스 환경변수를 읽을 권한이 없어 환경변수 옵션은 반영되지 않음";
            case FLAG_ARGFILE -> "@argfile 로 넘긴 옵션은 확인할 수 없음";
            case FLAG_CONTAINER -> "컨테이너 안 JVM — 호스트 메모리 기준 추정을 하지 않음";
            case FLAG_AMBIGUOUS_PID -> "여러 java 프로세스가 같은 -Xmx 라 값은 확정했지만 어느 프로세스인지는 미확정";
            default -> flag;
        };
    }

    // ── 크기 표기 ───────────────────────────────────────────────

    private static final Pattern SIZE = Pattern.compile("^\\s*(\\d+)\\s*([kKmMgGtT])?\\s*[bB]?\\s*$");

    /** JVM 크기 표기 → 바이트. {@code 4g}, {@code 8192m}, {@code 4294967296}. 잘못된 값은 IllegalArgumentException. */
    public static long parseSize(String s) {
        if (s == null) throw new IllegalArgumentException("크기 값이 비어 있습니다");
        Matcher m = SIZE.matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("JVM 크기 형식이 아닙니다 (예: 512m, 4g): " + s);
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        long mul = switch (unit) {
            case "k" -> 1L << 10;
            case "m" -> 1L << 20;
            case "g" -> 1L << 30;
            case "t" -> 1L << 40;
            default -> 1L;
        };
        if (n > Long.MAX_VALUE / mul) throw new IllegalArgumentException("크기 값이 너무 큽니다: " + s);
        return n * mul;
    }

    /** 바이트 → JVM 표기({@code 8g}/{@code 512m}/{@code 64k}), 나누어떨어지지 않으면 바이트 그대로. */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return String.valueOf(bytes);
        if (bytes % (1L << 30) == 0) return (bytes >> 30) + "g";
        if (bytes % (1L << 20) == 0) return (bytes >> 20) + "m";
        if (bytes % (1L << 10) == 0) return (bytes >> 10) + "k";
        return String.valueOf(bytes);
    }

    /** null 안전 표시용. */
    public static String formatSizeOrNull(Long bytes) {
        return bytes == null ? null : formatSize(bytes);
    }

    // ── 출력 파싱 ───────────────────────────────────────────────

    private static final Pattern ETIME = Pattern.compile("^(?:(\\d+)-)?(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})$");

    /** ps etime {@code [[dd-]hh:]mm:ss} → 초. 형식이 아니면 null. */
    public static Long parseEtimeSeconds(String s) {
        if (s == null) return null;
        Matcher m = ETIME.matcher(s.trim());
        if (!m.matches()) return null;
        long d = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long h = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long mi = Long.parseLong(m.group(3));
        long se = Long.parseLong(m.group(4));
        return ((d * 24 + h) * 60 + mi) * 60 + se;
    }

    /**
     * 원격 stdout → Capture(매칭 전). {@code __MEM__} 이전은 무시, {@code __END__} 부재는 truncated.
     * @param capturedAtEpoch 로컬 수집 시각(초) — 원격 {@code __NOW__} 가 없을 때 startEpoch 계산 기준
     */
    public static Capture parseOutput(String stdout, long capturedAtEpoch) {
        String text = stdout == null ? "" : stdout.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("__MEM__")) { start = i; break; }
        }
        if (start < 0) {
            return new Capture(SCHEMA, capturedAtEpoch, null, 0, 0, -1, true, null,
                    List.of(), null, null, List.of(), "출력에 __MEM__ 마커가 없음");
        }

        long memTotal = 0, memAvail = 0;
        Long now = null, mtime = null;
        int nproc = -1;
        boolean ended = false;
        String os = null, who = null, procMnt = null;
        boolean psOk = false, envSeen = false;
        List<RawProc> procs = new ArrayList<>();
        RawProc cur = null;

        // __MTIME__ 은 스크립트 첫 줄이라 __MEM__ 보다 앞에 온다 — 마커 앞 구간에서 이것만 따로 찾는다.
        for (int i = 0; i < start; i++) {
            String t = lines[i].trim();
            if (t.startsWith("__MTIME__")) mtime = parseLongOrNull(t.substring("__MTIME__".length()));
        }

        for (int i = start + 1; i < lines.length; i++) {
            String raw = lines[i];
            String t = raw.trim();
            if (t.equals("__END__")) { ended = true; break; }
            if (t.startsWith("__MTIME__")) { mtime = parseLongOrNull(t.substring(9)); continue; }
            if (t.startsWith("__NOW__")) { now = parseLongOrNull(t.substring(7)); continue; }
            if (t.startsWith("__NPROC__")) { Long n = parseLongOrNull(t.substring(9)); nproc = n == null ? -1 : n.intValue(); continue; }
            if (t.startsWith("__UNAME__")) { os = emptyToNull(t.substring(9).trim()); envSeen = true; continue; }
            if (t.startsWith("__WHO__")) { who = emptyToNull(t.substring(7).trim()); envSeen = true; continue; }
            if (t.startsWith("__PROCMNT__")) { procMnt = emptyToNull(t.substring(11).trim()); envSeen = true; continue; }
            if (t.equals("__PSOK__")) { psOk = true; envSeen = true; continue; }
            if (t.equals("__PS__")) continue;
            if (t.startsWith("MemTotal:")) { memTotal = parseKb(t.substring(9)); continue; }
            if (t.startsWith("MemAvailable:")) { memAvail = parseKb(t.substring(13)); continue; }
            if (t.startsWith("__PID__")) {
                Long pid = parseLongOrNull(t.substring(7));
                cur = null;
                if (pid != null && procs.size() < MAX_CANDIDATES) {
                    cur = new RawProc(pid.intValue());
                    procs.add(cur);
                }
                continue;
            }
            if (cur == null) continue;
            if (t.startsWith("__PSLINE__")) {
                String[] parts = t.substring(10).trim().split("\\s+");
                if (parts.length >= 1 && !parts[0].isEmpty()) cur.user = parts[0];
                if (parts.length >= 2) cur.etimeSec = parseEtimeSeconds(parts[1]);
            } else if (t.startsWith("__EXE__")) {
                cur.exe = emptyToNull(t.substring(7).trim());
            } else if (t.startsWith("__CWD__")) {
                cur.cwd = emptyToNull(t.substring(7).trim());
            } else if (raw.startsWith("__CMD__")) {
                // 토큰 구분자 US(\037) — 공백이 든 인자를 정확히 자른다. 앞 공백 1개는 printf 의 구분.
                String body = raw.substring("__CMD__".length());
                if (body.startsWith(" ")) body = body.substring(1);
                for (String tok : body.split("\u001f", -1)) {
                    if (!tok.isEmpty()) cur.tokens.add(tok);
                }
            } else if (t.equals("__ENVOK__")) {
                cur.envReadable = true;
            } else if (t.startsWith("__ENV__")) {
                String kv = t.substring(7).trim();
                int eq = kv.indexOf('=');
                if (eq > 0) cur.env.put(kv.substring(0, eq), kv.substring(eq + 1));
            } else if (t.equals("__CONTAINER__")) {
                cur.container = true;
            }
        }

        long baseNow = now != null ? now : capturedAtEpoch;
        List<Candidate> candidates = new ArrayList<>();
        for (RawProc p : procs) {
            if (p.tokens.isEmpty()) continue;
            if (!looksLikeJava(p)) continue;
            Long startEpoch = p.etimeSec == null ? null : baseNow - p.etimeSec;
            candidates.add(parseCommandLine(p.pid, p.user, startEpoch, p.cwd, p.exe, p.tokens, p.env,
                    p.envReadable, memTotal, p.container));
        }
        RemoteEnv env = envSeen ? new RemoteEnv(os, who, procMnt, psOk) : null;
        String note = null;
        Capture draft = new Capture(SCHEMA, capturedAtEpoch, now, memTotal, memAvail, nproc, !ended, mtime,
                candidates, null, null, List.of(), null, env);
        if (!ended) note = "출력이 중간에 끊김(타임아웃 또는 원격 오류)";
        else if (candidates.isEmpty()) {
            NoCandidateReason why = diagnoseNoCandidates(draft, null);
            note = why == null ? "java 프로세스 없음" : why.message();
        }
        return draft.withNote(note);
    }

    private static final Pattern PID_IN_DUMP_NAME = Pattern.compile("java_pid(\\d+)");
    private static final Pattern HIDEPID = Pattern.compile("(?:^|,)hidepid=(1|2|invisible|noaccess)(?:,|$)");

    /**
     * 후보가 0개인 이유(2026-09-16). 후보가 있거나 수집이 끊겼으면(truncated — 호출자가 SSH 실패로 따로 처리) null.
     * 판정 순서: ① Linux 가 아닌 OS·/proc 없음 ② ps 비호환 ③ 프로세스 목록 제한(hidepid·보이는 프로세스 &lt; 30) ④ java 없음.
     * ③ 은 root 로 접속했으면 hidepid 가 있어도 제한이 아니다.
     *
     * @param remoteName 덤프 파일명(원격) — {@code java_pid<N>} 이면 그 pid 를 안내에 넣는다. null 가능
     */
    public static NoCandidateReason diagnoseNoCandidates(Capture cap, String remoteName) {
        if (cap == null || cap.truncated() || !cap.candidates().isEmpty()) return null;
        RemoteEnv env = cap.remoteEnv();
        String os = env == null ? null : env.os();
        if ((os != null && !os.equalsIgnoreCase("Linux")) || (cap.memTotal() <= 0 && cap.procVisible() <= 0)) {
            return new NoCandidateReason("PROC_UNAVAILABLE", "미지원 OS",
                    (os != null && !os.equalsIgnoreCase("Linux") ? "원격 서버 OS 가 " + os + " 입니다." : "원격 서버에서 /proc 를 읽을 수 없습니다.")
                            + " JVM 힙 자동 수집은 Linux 의 /proc 에서만 동작합니다 — 연필(✎)로 직접 입력하세요.");
        }
        if (env != null && !env.psOk()) {
            return new NoCandidateReason("PS_UNAVAILABLE", "ps 실행 불가",
                    "원격 서버에서 ps 명령을 실행하지 못했습니다(옵션 비호환 또는 PATH 문제). 접속 계정의 PATH·ps 설치 여부를 확인하세요.");
        }
        String user = env == null ? null : env.user();
        boolean root = "root".equals(user);
        String mnt = env == null ? null : env.procMountOpts();
        Matcher hm = mnt == null ? null : HIDEPID.matcher(mnt);
        boolean hidepid = hm != null && hm.find();
        boolean fewVisible = cap.procVisible() >= 0 && cap.procVisible() < 30;
        if ((hidepid && !root) || fewVisible) {
            StringBuilder sb = new StringBuilder("프로세스 목록이 제한돼 있어 java 프로세스를 볼 수 없습니다");
            List<String> why = new ArrayList<>();
            if (hidepid) why.add("/proc 가 hidepid=" + hm.group(1) + " 로 마운트됨");
            if (user != null) why.add("접속 계정 " + user);
            if (cap.procVisible() >= 0) why.add("보이는 프로세스 " + cap.procVisible() + "개");
            if (!why.isEmpty()) sb.append(" (").append(String.join(", ", why)).append(")");
            sb.append(". 서버 설정의 SSH 계정을 WAS 실행 계정으로 바꾸거나, 연필(✎)로 직접 입력하세요.");
            return new NoCandidateReason("LIST_RESTRICTED", "목록 제한", sb.toString());
        }
        StringBuilder sb = new StringBuilder("원격 서버에 실행 중인 java 프로세스가 없습니다");
        if (cap.procVisible() >= 0) sb.append(" (보이는 프로세스 ").append(cap.procVisible()).append("개)");
        sb.append('.');
        Matcher pm = remoteName == null ? null : PID_IN_DUMP_NAME.matcher(remoteName);
        if (pm != null && pm.find()) {
            sb.append(" 덤프를 만든 JVM(pid ").append(pm.group(1)).append(")이 종료된 뒤 다시 기동되지 않았거나, 다른 서버에서 실행 중일 수 있습니다.");
        } else {
            sb.append(" 덤프를 만든 JVM 이 종료됐거나 다른 서버에서 실행 중일 수 있습니다.");
        }
        return new NoCandidateReason("NO_JAVA", "java 없음", sb.toString());
    }

    private static boolean looksLikeJava(RawProc p) {
        String first = p.tokens.get(0);
        String base = first.substring(first.lastIndexOf('/') + 1);
        if (base.startsWith("java")) return true;
        if (p.exe != null && (p.exe.endsWith("/java") || p.exe.endsWith("/javaw"))) return true;
        for (String t : p.tokens) if (t.startsWith("-Xmx") || t.startsWith("-XX:")) return true;
        return false;
    }

    private static final class RawProc {
        final int pid;
        String user, exe, cwd;
        Long etimeSec;
        boolean envReadable, container;
        final List<String> tokens = new ArrayList<>();
        final Map<String, String> env = new LinkedHashMap<>();
        RawProc(int pid) { this.pid = pid; }
    }

    // ── 명령줄 해석 ─────────────────────────────────────────────

    /** 저장·표시 대상 옵션 allow-list. 여기 없는 옵션은 개수만 센다. */
    static final Pattern STORED_OPTION = Pattern.compile(
            "^-(Xms|Xmx|Xmn|Xss)\\S*$"
            + "|^-XX:(Initial|Max|Min)HeapSize=\\S+$"
            + "|^-XX:(Initial|Max)RAMPercentage=\\S+$"
            + "|^-XX:MaxRAM=\\S+$"
            + "|^-XX:(Max)?(Metaspace|Perm|New)Size=\\S+$"
            + "|^-XX:(NewRatio|SurvivorRatio|MaxGCPauseMillis|ParallelGCThreads|ConcGCThreads|G1HeapRegionSize"
            + "|MaxDirectMemorySize|ReservedCodeCacheSize)=\\S+$"
            + "|^-XX:[+-]Use\\w+GC$"
            + "|^-XX:[+-](HeapDumpOnOutOfMemoryError|ExitOnOutOfMemoryError|UseCompressedOops|UseContainerSupport"
            + "|AlwaysPreTouch|DisableExplicitGC)$"
            + "|^-XX:HeapDumpPath=\\S+$"
            // GC 로그 위치·형식 (2026-09-14) — 값이 경로/숫자뿐이라 allow-list 철학 유지. -Xlog 는 gc 셀렉터만 남긴다(parseGcLogPath).
            + "|^-Xloggc:\\S+$"
            + "|^-Xlog:\\S+$"
            + "|^-verbose:gc$"
            + "|^-XX:[+-](PrintGC|PrintGCDetails|PrintGCDateStamps|PrintGCTimeStamps|PrintGCApplicationStoppedTime"
            + "|UseGCLogFileRotation|PrintTenuringDistribution)$"
            + "|^-XX:(NumberOfGCLogFiles|GCLogFileSize)=\\S+$");

    /** 매칭에 쓰는 {@code -D} 키. 이 키의 값만 markers 로 남긴다(200자 절단). */
    public static final List<String> MARKER_KEYS = List.of(
            "jeus.server.name", "jeus.domain.name", "jeus.home", "weblogic.Name",
            "catalina.base", "catalina.home", "user.dir");

    private static final Set<String> CLASSPATH_OPTS = Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path",
            "--add-modules", "--upgrade-module-path", "--patch-module", "--limit-modules");

    /**
     * 토큰 목록(argv) + 환경변수 → Candidate. <b>마지막 지정이 이긴다</b>(JVM 규칙).
     * 적용 순서: JAVA_TOOL_OPTIONS &lt; JDK_JAVA_OPTIONS &lt; 명령줄 &lt; _JAVA_OPTIONS.
     * 명시값이 하나라도 있으면 RAMPercentage 는 무시. 없으면 MemTotal 환산(ergonomic) → 1/4 추정(estimated).
     * 컨테이너면 추정하지 않는다(분모가 호스트 메모리가 아니라 cgroup 한도라 틀린 값이 된다).
     */
    public static Candidate parseCommandLine(int pid, String user, Long startEpoch, String cwd, String exe,
                                             List<String> argv, Map<String, String> env, boolean envReadable,
                                             long memTotal, boolean container) {
        List<String[]> sources = new ArrayList<>(); // {origin, token}
        addEnvTokens(sources, env, "JAVA_TOOL_OPTIONS");
        addEnvTokens(sources, env, "JDK_JAVA_OPTIONS");

        // 명령줄: java 실행 파일 다음부터 메인클래스 전까지가 JVM 옵션, 그 뒤는 프로그램 인자.
        String mainClass = null;
        List<String> programArgs = new ArrayList<>();
        for (int i = 1; i < argv.size(); i++) {
            String t = argv.get(i);
            if (mainClass != null) { programArgs.add(t); continue; }
            if (t.equals("-jar")) {
                if (i + 1 < argv.size()) { mainClass = argv.get(++i); }
                continue;
            }
            if (t.equals("-m") || t.equals("--module")) {
                if (i + 1 < argv.size()) { mainClass = argv.get(++i); }
                continue;
            }
            if (CLASSPATH_OPTS.contains(t)) { i++; continue; } // 값 토큰 건너뜀
            if (t.startsWith("-")) { sources.add(new String[]{"cmdline", t}); continue; }
            if (t.startsWith("@")) { sources.add(new String[]{"cmdline", t}); continue; }
            mainClass = t;
        }
        addEnvTokens(sources, env, "_JAVA_OPTIONS");

        Long xms = null, xmx = null;
        String xmxOrigin = null, heapDumpPath = null, gcLogPath = null;
        Double maxRamPct = null;
        Long maxRam = null;
        boolean argfile = false, envUsed = false;
        int other = 0;
        List<String> options = new ArrayList<>();
        Map<String, String> markers = new LinkedHashMap<>();

        for (String[] st : sources) {
            String origin = st[0], t = st[1];
            boolean fromEnv = !origin.equals("cmdline");
            if (t.startsWith("@")) { argfile = true; continue; }
            if (t.startsWith("-D")) {
                int eq = t.indexOf('=');
                String key = eq > 0 ? t.substring(2, eq) : t.substring(2);
                if (MARKER_KEYS.contains(key) && eq > 0) markers.put(key, trunc(t.substring(eq + 1), MAX_MARKER_LEN));
                continue; // 그 외 -D 는 폐기
            }
            Long v;
            if ((v = sizeAfter(t, "-Xms")) != null) { xms = v; if (fromEnv) envUsed = true; }
            else if ((v = sizeAfter(t, "-Xmx")) != null) { xmx = v; xmxOrigin = fromEnv ? "env" : "cmdline"; if (fromEnv) envUsed = true; }
            else if ((v = sizeAfter(t, "-XX:InitialHeapSize=")) != null) { xms = v; if (fromEnv) envUsed = true; }
            else if ((v = sizeAfter(t, "-XX:MaxHeapSize=")) != null) { xmx = v; xmxOrigin = fromEnv ? "env" : "cmdline"; if (fromEnv) envUsed = true; }
            else if (t.startsWith("-XX:MaxRAMPercentage=")) { maxRamPct = parseDoubleOrNull(t.substring(21)); }
            else if ((v = sizeAfter(t, "-XX:MaxRAM=")) != null) { maxRam = v; }
            else if (t.startsWith("-XX:HeapDumpPath=")) { heapDumpPath = trunc(t.substring(17), MAX_MARKER_LEN); }
            else if (t.startsWith("-Xloggc:")) { gcLogPath = trunc(t.substring(8), MAX_MARKER_LEN); }
            else if (t.startsWith("-Xlog:")) { String g = parseGcLogPath(t); if (g != null) gcLogPath = trunc(g, MAX_MARKER_LEN); }

            if (STORED_OPTION.matcher(t).matches()) {
                if (options.size() < MAX_OPTIONS_PER_CANDIDATE) options.add(trunc(t, MAX_OPTION_LEN));
            } else if (t.startsWith("-")) {
                other++;
            }
        }

        // JEUS 부트스트래퍼는 -D 대신 위치 인자(-domain X -server Y)로 넘기기도 한다 — 같은 마커 키로 합친다.
        for (int i = 0; i + 1 < programArgs.size(); i++) {
            String a = programArgs.get(i);
            if (a.equals("-server") && !markers.containsKey("jeus.server.name")) markers.put("jeus.server.name", trunc(programArgs.get(i + 1), MAX_MARKER_LEN));
            if (a.equals("-domain") && !markers.containsKey("jeus.domain.name")) markers.put("jeus.domain.name", trunc(programArgs.get(i + 1), MAX_MARKER_LEN));
        }

        if (xmx == null && !container && memTotal > 0) {
            if (maxRamPct != null || maxRam != null) {
                long base = maxRam != null ? Math.min(maxRam, memTotal) : memTotal;
                double pct = maxRamPct != null ? maxRamPct : 25.0;
                xmx = (long) (base * pct / 100.0);
                xmxOrigin = "ergonomic";
            } else {
                xmx = memTotal / 4;
                xmxOrigin = "estimated";
            }
        }
        if (xmx != null && xmxOrigin == null) xmxOrigin = "cmdline";
        return new Candidate(pid, user, startEpoch, mainClass == null ? null : trunc(mainClass, MAX_MARKER_LEN),
                cwd, exe, xms, xmx, xmxOrigin, heapDumpPath, gcLogPath, options, other, markers, envReadable, envUsed, argfile, container);
    }

    // ── GC 로그 경로 (2026-09-14) ───────────────────────────────

    /**
     * {@code -Xlog:<selectors>:<output>[:<decorators>[:<options>]]} 에서 gc 셀렉터가 있을 때 파일 경로를 뽑는다.
     * output 은 {@code file=<p>} 또는 {@code <p>}(stdout/stderr 가 아닌 것). 없으면 null.
     */
    static String parseGcLogPath(String opt) {
        if (opt == null || !opt.startsWith("-Xlog:")) return null;
        String body = opt.substring(6);
        if (body.isEmpty() || body.equals("disable") || body.startsWith("help")) return null;
        // output 구분자 ':' — 셀렉터(gc*=info,safepoint) 뒤 첫 ':'. 셀렉터 안에는 ':' 이 없다.
        int c1 = body.indexOf(':');
        String selectors = c1 < 0 ? body : body.substring(0, c1);
        if (!selectors.contains("gc") && !selectors.equals("all")) return null;
        if (c1 < 0) return null;                 // 출력 지정 없음 → stdout
        String rest = body.substring(c1 + 1);
        int c2 = rest.indexOf(':');
        String output = c2 < 0 ? rest : rest.substring(0, c2);
        if (output.startsWith("file=")) output = output.substring(5);
        if (output.isEmpty() || output.equals("stdout") || output.equals("stderr")) return null;
        if (output.startsWith("\"") && output.endsWith("\"") && output.length() >= 2) output = output.substring(1, output.length() - 1);
        return output;
    }

    /**
     * 로그 경로 정규화 — 회전 접미사({@code .0}, {@code .1.current}), JVM 치환자({@code %t}/{@code %p}/{@code %pid})를
     * 지운 뒤 비교한다. 두 경로가 같은 JVM 의 GC 로그를 가리키면 true.
     */
    public static boolean sameGcLogPath(String configured, String actual, String cwd) {
        if (configured == null || actual == null) return false;
        String a = stripRotation(actual.trim());
        String c = configured.trim();
        if (!c.startsWith("/") && cwd != null && !cwd.isBlank()) c = normDir(cwd) + "/" + c;
        String cn = stripRotation(c);
        if (cn.equals(a)) return true;
        if (cn.contains("%")) {
            String rx = java.util.regex.Pattern.quote(cn).replace("%t", "\\E.*\\Q").replace("%p", "\\E.*\\Q")
                    .replace("%pid", "\\E.*\\Q").replace("%hn", "\\E.*\\Q");
            if (a.matches(rx)) return true;
        }
        // 같은 디렉토리 + 같은 base(회전 접미사만 다름) — gc-2026-09-14_00-52-33.log 류(%t) 는 위 정규식이 잡는다
        return false;
    }

    private static final Pattern ROTATION_SUFFIX = Pattern.compile("(\\.\\d+)?(\\.current)?(\\.gz)?$");

    /** {@code gc.log.3.current} → {@code gc.log}, {@code gc.log.1.gz} → {@code gc.log}. */
    public static String stripRotation(String p) {
        if (p == null) return null;
        String s = p;
        // 접미사가 여러 겹일 수 있어 두 번 적용
        for (int i = 0; i < 2; i++) s = ROTATION_SUFFIX.matcher(s).replaceFirst("");
        return s;
    }

    /**
     * GC 로그 전송 시 매칭 — 방금 가져온 로그 경로를 {@code -Xloggc}/{@code -Xlog} 로 쓰는 JVM 을 찾는다.
     * 정확히 1개 → {@code gclog-path}, 여럿 → {@code ambiguous}, 0개인데 java 가 1개뿐 → {@code single}(약한 근거).
     */
    public static Match matchGcLog(Capture cap, String remoteLogPath) {
        List<Candidate> cs = cap == null ? List.of() : cap.candidates();
        if (cs.isEmpty()) return new Match(null, "no-candidates", Set.of());
        Integer only = null;
        for (int i = 0; i < cs.size(); i++) {
            Candidate c = cs.get(i);
            if (sameGcLogPath(c.gcLogPath(), remoteLogPath, c.cwd())) {
                if (only != null) return new Match(null, "ambiguous", Set.of(FLAG_AMBIGUOUS_PID));
                only = i;
            }
        }
        if (only != null) return new Match(only, "gclog-path", Set.of());
        if (cs.size() == 1) return new Match(0, "single", Set.of());
        return new Match(null, "ambiguous", Set.of());
    }

    private static void addEnvTokens(List<String[]> out, Map<String, String> env, String key) {
        if (env == null) return;
        String v = env.get(key);
        if (v == null || v.isBlank()) return;
        for (String t : v.trim().split("\\s+")) if (!t.isEmpty()) out.add(new String[]{key, t});
    }

    private static Long sizeAfter(String token, String prefix) {
        if (!token.startsWith(prefix)) return null;
        try { return parseSize(token.substring(prefix.length())); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** 후보 자체에서 유도되는 신뢰도 플래그(매칭 플래그와 합쳐 저장). */
    public static Set<String> candidateFlags(Candidate c) {
        Set<String> f = new LinkedHashSet<>();
        if (c == null) return f;
        if ("estimated".equals(c.xmxOrigin())) f.add(FLAG_ESTIMATED);
        if ("ergonomic".equals(c.xmxOrigin())) f.add(FLAG_ERGONOMIC);
        if (c.envUsed()) f.add(FLAG_ENV);
        if (!c.envReadable()) f.add(FLAG_ENV_UNKNOWN);
        if (c.argfile()) f.add(FLAG_ARGFILE);
        if (c.container()) f.add(FLAG_CONTAINER);
        return f;
    }

    // ── 매칭 ────────────────────────────────────────────────────

    private static final Pattern PID_IN_NAME = Pattern.compile("java_pid(\\d+)");

    /** 덤프 파일명({@code java_pid12345.hprof}, {@code .gz}·리네임 포함)에서 pid 추출. 없으면 null. */
    public static Integer pidFromFilename(String filename) {
        if (filename == null) return null;
        Matcher m = PID_IN_NAME.matcher(filename);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    /** sysProps 마커 대조 순서 — 유일성이 높은 키부터. */
    private static final List<String> SYSPROP_MATCH_ORDER = List.of(
            "jeus.server.name", "weblogic.Name", "catalina.base", "user.dir", "jeus.domain.name");

    /**
     * 후보 중 덤프의 주인 프로세스를 고른다.
     * <ol>
     *   <li>파일명 {@code java_pid} = pid (프로세스 시작이 덤프 시각보다 뒤면 pid 재사용 가능성 → 약한 근거로 강등)</li>
     *   <li>{@code -XX:HeapDumpPath} 가 덤프 경로/디렉토리, 또는 cwd 가 덤프 디렉토리 — 유일할 때</li>
     *   <li>분석 후 sysProps 마커({@code jeus.server.name} 등) 일치, {@code sun.java.command} 첫 토큰 = mainClass — 유일할 때</li>
     *   <li>약한 pid 근거 (restarted)</li>
     *   <li>후보 1개</li>
     *   <li>후보 다수지만 xmx 전부 동일 → 값은 확정, 프로세스는 미확정(ambiguous-pid)</li>
     * </ol>
     * @param sysProps 분석 후 MAT System Properties (전송 시점엔 null)
     * @param dumpEpoch 덤프 생성 시각(초). null 이면 capture.dumpMtimeEpoch 사용
     */
    public static Match match(Capture cap, String remoteFilename, String remoteDir,
                              Map<String, String> sysProps, Long dumpEpoch) {
        List<Candidate> cs = cap == null ? List.of() : cap.candidates();
        if (cs.isEmpty()) return new Match(null, "no-candidates", Set.of());
        Long dumpTs = dumpEpoch != null ? dumpEpoch : (cap.dumpMtimeEpoch());

        Integer weakPid = null;
        Integer pid = pidFromFilename(remoteFilename);
        if (pid != null) {
            for (int i = 0; i < cs.size(); i++) {
                if (cs.get(i).pid() == pid) {
                    if (startedAfter(cs.get(i), dumpTs)) weakPid = i;
                    else return finish(cs, i, "pid", dumpTs);
                }
            }
        }

        String dir = normDir(remoteDir);
        String remotePath = dir != null && remoteFilename != null ? dir + "/" + remoteFilename : null;
        Integer only = null; String reason = null;
        for (int i = 0; i < cs.size(); i++) {
            Candidate c = cs.get(i);
            String hdp = c.heapDumpPath();
            boolean hit = false; String r = null;
            if (hdp != null && dir != null) {
                String h = hdp.endsWith("/") && hdp.length() > 1 ? hdp.substring(0, hdp.length() - 1) : hdp;
                if (h.equals(remotePath) || h.equals(dir)) { hit = true; r = "heapdumppath"; }
            }
            if (!hit && dir != null && c.cwd() != null && normDir(c.cwd()).equals(dir)) { hit = true; r = "cwd"; }
            if (hit) {
                if (only != null) { only = null; reason = null; break; }
                only = i; reason = r;
            }
        }
        if (only != null) return finish(cs, only, reason, dumpTs);

        if (sysProps != null && !sysProps.isEmpty()) {
            for (String key : SYSPROP_MATCH_ORDER) {
                String v = sysProps.get(key);
                if (v == null || v.isBlank()) continue;
                Integer u = uniqueIndex(cs, c -> v.trim().equals(c.markers().get(key)));
                if (u != null) return finish(cs, u, "sysprop:" + key, dumpTs);
            }
            String cmd = sysProps.get("sun.java.command");
            if (cmd != null && !cmd.isBlank()) {
                String first = cmd.trim().split("\\s+")[0];
                Integer u = uniqueIndex(cs, c -> first.equals(c.mainClass()));
                if (u != null) return finish(cs, u, "maincommand", dumpTs);
            }
        }

        if (weakPid != null) return finish(cs, weakPid, "pid", dumpTs);
        if (cs.size() == 1) return finish(cs, 0, "single", dumpTs);

        Long common = cs.get(0).xmxBytes();
        if (common != null && cs.stream().allMatch(c -> common.equals(c.xmxBytes()))) {
            Match m = finish(cs, 0, "same-xmx", dumpTs);
            Set<String> f = new LinkedHashSet<>(m.flags());
            f.add(FLAG_AMBIGUOUS_PID);
            return new Match(0, "same-xmx", f);
        }
        return new Match(null, "ambiguous", Set.of());
    }

    private static Match finish(List<Candidate> cs, int idx, String reason, Long dumpTs) {
        Set<String> f = new LinkedHashSet<>();
        if (startedAfter(cs.get(idx), dumpTs)) f.add(FLAG_RESTARTED);
        return new Match(idx, reason, f);
    }

    private static boolean startedAfter(Candidate c, Long dumpTs) {
        return dumpTs != null && c.startEpoch() != null && c.startEpoch() > dumpTs;
    }

    private static Integer uniqueIndex(List<Candidate> cs, java.util.function.Predicate<Candidate> p) {
        Integer found = null;
        for (int i = 0; i < cs.size(); i++) {
            if (p.test(cs.get(i))) {
                if (found != null) return null;
                found = i;
            }
        }
        return found;
    }

    private static String normDir(String d) {
        if (d == null) return null;
        String s = d.trim();
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    /** remotePath 에서 디렉토리 부분. */
    public static String dirOf(String remotePath) {
        if (remotePath == null) return null;
        int i = remotePath.lastIndexOf('/');
        return i <= 0 ? (i == 0 ? "/" : null) : remotePath.substring(0, i);
    }

    // ── JSON ────────────────────────────────────────────────────

    // Jackson 3 는 FAIL_ON_NULL_FOR_PRIMITIVES 가 기본 true 라 스키마가 자란 뒤 옛 JSON(필드 누락)을 못 읽는다 — 둘 다 끈다.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** Capture → JSON. 60KB 를 넘으면 옵션·마커·후보를 단계적으로 줄여 TEXT 컬럼에 들어가게 한다. */
    public static String toJson(Capture cap) {
        if (cap == null) return null;
        String s = MAPPER.writeValueAsString(cap);
        if (utf8Len(s) <= MAX_JSON_BYTES) return s;
        Capture slim = shrink(cap, 8, true);
        s = MAPPER.writeValueAsString(slim);
        if (utf8Len(s) <= MAX_JSON_BYTES) return s;
        slim = shrink(cap, 0, false);
        s = MAPPER.writeValueAsString(slim);
        if (utf8Len(s) <= MAX_JSON_BYTES) return s;
        List<Candidate> few = slim.candidates().subList(0, Math.min(5, slim.candidates().size()));
        Capture tiny = new Capture(slim.schema(), slim.capturedAtEpoch(), slim.remoteNowEpoch(), slim.memTotal(),
                slim.memAvailable(), slim.procVisible(), slim.truncated(), slim.dumpMtimeEpoch(), few,
                slim.matchedIndex() != null && slim.matchedIndex() < few.size() ? slim.matchedIndex() : null,
                slim.matchReason(), slim.matchFlags(), "후보 목록이 커서 일부만 저장됨", slim.remoteEnv());
        return MAPPER.writeValueAsString(tiny);
    }

    private static Capture shrink(Capture cap, int keepOptions, boolean keepMarkers) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : cap.candidates()) {
            List<String> opts = c.options().subList(0, Math.min(keepOptions, c.options().size()));
            Map<String, String> mk = keepMarkers ? c.markers() : Map.of();
            out.add(new Candidate(c.pid(), c.user(), c.startEpoch(), c.mainClass(), c.cwd(), c.exe(), c.xmsBytes(),
                    c.xmxBytes(), c.xmxOrigin(), c.heapDumpPath(), c.gcLogPath(), opts, c.otherOptionCount(), mk,
                    c.envReadable(), c.envUsed(), c.argfile(), c.container()));
        }
        return new Capture(cap.schema(), cap.capturedAtEpoch(), cap.remoteNowEpoch(), cap.memTotal(), cap.memAvailable(),
                cap.procVisible(), cap.truncated(), cap.dumpMtimeEpoch(), out, cap.matchedIndex(), cap.matchReason(),
                cap.matchFlags(), cap.note(), cap.remoteEnv());
    }

    /** JSON → Capture. null/빈 값/손상은 null (호출자가 로그). */
    public static Capture fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, Capture.class);
        } catch (RuntimeException e) { // JacksonException 은 unchecked
            return null;
        }
    }

    // ── 소소한 헬퍼 ─────────────────────────────────────────────

    private static long parseKb(String s) {
        String t = s.trim();
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        Long v = parseLongOrNull(t);
        return v == null ? 0 : v * 1024;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleOrNull(String s) {
        try { return Double.parseDouble(s.trim()); } catch (RuntimeException e) { return null; }
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

}

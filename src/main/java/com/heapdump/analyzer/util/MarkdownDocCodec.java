package com.heapdump.analyzer.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 지식 문서(.md) ↔ 엔티티 왕복 코덱. 순수 static.
 *
 * <p>메타데이터는 <b>front-matter</b> 로 실어 왕복시킨다. 형식은 <b>YAML 이 아니라 그 부분집합</b>이다
 * — 스칼라 {@code key: value} 줄만 받고 중첩·리스트·멀티라인은 없다. YAML 파서를 의존성으로
 * 들이지 않기 위해서이기도 하고, {@code tags} 를 리스트로 표현하면 Chroma 메타(스칼라만 허용)에
 * 넣기 전에 다시 평탄화해야 해서 표현이 둘로 갈라지기 때문이다.
 *
 * <pre>
 * ---
 * id: oom-2026-001
 * title: Old Generation OOM (G1GC)
 * category: heap_analysis
 * tags: OOM,G1GC
 * severity: high
 * created_at: 2026-04-25
 * synthetic: false
 * ---
 *
 * # Old Generation OOM (G1GC)
 * 본문…
 * </pre>
 *
 * <p>⚠ 제목을 첫 {@code # } 헤딩에서 가져온 경우 <b>그 헤딩 줄을 본문에서 제거</b>한다.
 * 색인기 {@code sources._doc()} 가 이미 제목을 본문 앞에 붙이므로, 남겨 두면 같은 문장이
 * 두 번 들어가 임베딩이 희석된다.
 *
 * <p>⚠ {@link #render} 는 <b>항상 {@code id:} 를 써넣는다</b>. 내보낸 파일의 이름을 바꿔서
 * 되가져와도 같은 문서로 인식되어야 하고, 그러지 않으면 새 문서가 생기고 옛 청크가 고아가 된다.
 */
public final class MarkdownDocCodec {

    private MarkdownDocCodec() {}

    /** 본문 상한 1MB — 사용자가 붙여 넣는 Markdown 은 상한이 없어 방어선이 필요하다. */
    public static final int MAX_BODY_CHARS = 1_000_000;
    /** originId 최대 길이 — DB 컬럼(190)과 맞춘다. */
    public static final int MAX_ORIGIN_ID = 190;

    /** 파싱 결과. {@code warnings} 는 사용자에게 보여줄 비치명적 안내다. */
    public static final class MdDoc {
        public String originId;
        public String title;
        public String category;
        public String tags;
        public String source;
        public String severity;
        public String docDate;
        public boolean synthetic;
        public String body;
        public final List<String> warnings = new ArrayList<>();
    }

    /**
     * .md 텍스트 → 문서. 형식 오류는 {@link IllegalArgumentException} 으로 던진다
     * (호출부가 파일별 오류 목록으로 모은다).
     *
     * @param filename 확장자 포함 원본 파일명. 제목·id 폴백에 쓴다.
     */
    public static MdDoc parse(String filename, String text) {
        if (text == null) throw new IllegalArgumentException("빈 파일입니다");
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL 바이트가 포함돼 있습니다 (텍스트 파일이 아닙니다)");
        if (text.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("본문이 너무 큽니다 (" + text.length() + "자, 상한 " + MAX_BODY_CHARS + "자)");
        }

        MdDoc d = new MdDoc();
        String norm = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!norm.isEmpty() && norm.charAt(0) == '\uFEFF') norm = norm.substring(1);

        Map<String, String> fm = new LinkedHashMap<>();
        String body = norm;

        if (norm.startsWith("---\n")) {
            int end = norm.indexOf("\n---", 3);
            if (end < 0) {
                // 닫히지 않은 front-matter — 전체를 본문으로 본다(치명적이지 않다)
                d.warnings.add("front-matter 가 닫히지 않아 전체를 본문으로 처리했습니다");
            } else {
                String head = norm.substring(4, end);
                int bodyStart = norm.indexOf('\n', end + 1);
                body = bodyStart < 0 ? "" : norm.substring(bodyStart + 1);
                parseFrontMatter(head, fm, d.warnings);
            }
        }

        String base = baseName(filename);

        d.title = firstNonBlank(fm.get("title"), null);
        if (d.title == null) {
            String heading = firstHeading(body);
            if (heading != null) {
                d.title = heading;
                body = stripFirstHeading(body);   // 제목 중복 주입 방지 (위 클래스 주석)
            } else {
                d.title = base;
                d.warnings.add("제목을 찾지 못해 파일명을 제목으로 사용했습니다");
            }
        }

        d.originId = firstNonBlank(fm.get("id"), slug(base));
        if (d.originId == null || d.originId.isEmpty()) {
            // ⚠ 한글만으로 된 파일명(`메모리누수.md`)은 슬러그가 비어 버린다 — 흔한 경우라
            //    오류로 막지 않고 본문 해시로 결정적 id 를 만든다. 같은 내용이면 같은 id 라
            //    재가져오기가 중복으로 잡히고 upsert 도 멱등하다.
            d.originId = "doc-" + RagDocHash.contentHash(body).substring(0, 12);
            d.warnings.add("파일명에서 id 를 만들 수 없어 본문 해시로 생성했습니다: " + d.originId);
        }

        d.category  = trimOrEmpty(fm.get("category"));
        d.tags      = trimOrEmpty(fm.get("tags"));
        d.source    = trimOrEmpty(fm.get("source"));
        d.severity  = normalizeSeverity(fm.get("severity"), d.warnings);
        d.docDate   = trimOrEmpty(fm.get("created_at"));
        d.synthetic = "true".equalsIgnoreCase(trimOrEmpty(fm.get("synthetic")));
        d.body      = body.strip();

        if (d.body.isEmpty()) throw new IllegalArgumentException("본문이 비어 있습니다");
        if (d.title.length() > 300) d.title = d.title.substring(0, 300);
        return d;
    }

    /** 문서 → .md 텍스트. {@link #parse} 와 왕복 무손실. */
    public static String render(MdDoc d) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        put(sb, "id", d.originId);
        put(sb, "title", d.title);
        put(sb, "category", d.category);
        put(sb, "tags", d.tags);
        put(sb, "source", d.source);
        put(sb, "severity", d.severity);
        put(sb, "created_at", d.docDate);
        sb.append("synthetic: ").append(d.synthetic).append('\n');
        sb.append("---\n\n");
        sb.append(d.body == null ? "" : d.body.strip()).append('\n');
        return sb.toString();
    }

    /** 파일명 → 안전한 슬러그. 소문자 + {@code [a-z0-9._-]} 외 전부 '-' 로. */
    public static String slug(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        t = t.replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        if (t.length() > MAX_ORIGIN_ID) t = t.substring(0, MAX_ORIGIN_ID);
        return t;
    }

    /** 확장자를 뺀 파일명. 경로가 섞여 들어와도 마지막 요소만 취한다. */
    public static String baseName(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        String n = filename.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ── 내부 ────────────────────────────────────────────────

    private static void parseFrontMatter(String head, Map<String, String> out, List<String> warnings) {
        for (String line : head.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon <= 0) { warnings.add("front-matter 해석 불가 줄 무시: " + trunc(t)); continue; }
            String k = t.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String v = t.substring(colon + 1).strip();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1).replace("\\\"", "\"");
            }
            out.put(k, v);
        }
    }

    /** 첫 ATX 헤딩(`# `) 텍스트. 없으면 null. */
    private static String firstHeading(String body) {
        for (String line : body.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("# ")) return t.substring(2).strip();
            return null;   // 첫 비어있지 않은 줄이 헤딩이 아니면 제목으로 보지 않는다
        }
        return null;
    }

    private static String stripFirstHeading(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (String line : lines) {
            if (!removed && line.strip().startsWith("# ")) { removed = true; continue; }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String normalizeSeverity(String v, List<String> warnings) {
        String s = trimOrEmpty(v).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "info";
        switch (s) {
            case "critical": case "high": case "medium": case "low": case "info": return s;
            default:
                warnings.add("severity 값을 알 수 없어 info 로 두었습니다: " + trunc(s));
                return "info";
        }
    }

    private static void put(StringBuilder sb, String k, String v) {
        if (v == null || v.isEmpty()) return;
        boolean needsQuote = v.startsWith(" ") || v.endsWith(" ") || v.startsWith("#");
        sb.append(k).append(": ");
        if (needsQuote) sb.append('"').append(v.replace("\"", "\\\"")).append('"');
        else sb.append(v);
        sb.append('\n');
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.strip();
        return b;
    }

    private static String trimOrEmpty(String s) { return s == null ? "" : s.strip(); }

    private static String trunc(String s) { return s.length() > 60 ? s.substring(0, 60) + "…" : s; }
}

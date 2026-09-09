package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.RagKnowledgeDoc;
import com.heapdump.analyzer.model.entity.RagLearningDoc;
import com.heapdump.analyzer.repository.RagKnowledgeDocRepository;
import com.heapdump.analyzer.repository.RagLearningDocRepository;
import com.heapdump.analyzer.util.CsvCodec;
import com.heapdump.analyzer.util.MarkdownDocCodec;
import com.heapdump.analyzer.util.RagDocHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 지식(md) / 학습(csv) 코퍼스의 조회·가져오기·내보내기·삭제.
 *
 * <p><b>가져오기 규약</b>(leak-rules import 규약을 따르되 컨트롤러가 아니라 서비스에 둔다 —
 * 그래야 단위 테스트가 가능하다):
 * <ol>
 *   <li>전 파일 파싱 → 하나라도 실패하면 <b>아무것도 쓰지 않는다</b></li>
 *   <li>전 레코드 검증 → 오류가 하나라도 있으면 <b>0건 반영</b>(부분 적용 혼란 방지)</li>
 *   <li>파일 내 중복은 last-wins 로 접고 {@code intraDup} 으로 보고</li>
 *   <li>DB 대조는 <b>배치 조회 3회</b>(key/hash/title) — 행마다 조회하지 않는다</li>
 *   <li>{@code dryRun} 이면 여기서 멈춘다. 검사와 적용이 <b>같은 코드 경로</b>라 판정이 어긋나지 않는다</li>
 * </ol>
 *
 * <p>중복은 <b>id → 내용 → 제목</b> 우선순위로 하나를 택하되 걸린 플래그는 전부 보고한다.
 */
@Service
public class RagCorpusService {

    private static final Logger logger = LoggerFactory.getLogger(RagCorpusService.class);

    /** 학습 CSV 의 고정 헤더. 순서가 곧 내보내기 순서다 — 바꾸면 기존 파일과 호환이 깨진다. */
    public static final String[] LEARNING_HEADER = {
            "id", "category", "title", "content", "tags", "source", "severity", "created_at", "synthetic"
    };

    /**
     * 2026-04-30 내보내기 형식 — {@code synthetic} 이 없는 8컬럼. 읽기만 지원하고
     * 내보내기는 언제나 {@link #LEARNING_HEADER}(9컬럼) 정규형이다.
     */
    public static final String[] LEARNING_HEADER_LEGACY = {
            "id", "category", "title", "content", "tags", "source", "severity", "created_at"
    };

    /**
     * 밀린 행을 복구할 때 {@code tags} 조각 하나가 넘을 수 없는 길이.
     *
     * <p>이 가드가 없으면 <b>조용한 오배치</b>가 난다 — 예컨대 {@code title} 에 인용 없는 콤마가
     * 있으면 밀림이 4컬럼보다 앞에서 시작해 실제 본문이 {@code tags} 자리로 들어오는데,
     * 뒤에서 세는 규칙 특성상 severity·created_at 검증은 그대로 통과한다. 진짜 태그는
     * {@code OOM}·{@code G1GC} 같은 짧은 토큰이고 본문은 수백 자라 길이로 확실히 갈린다.
     */
    private static final int MAX_TAG_FIELD = 60;

    /**
     * 레거시 8컬럼 파일에서 가상 사례를 식별하는 표기. {@code source} 가
     * {@code (예시 — 실제 사례로 교체 필요)} 처럼 작성자가 <b>명시적으로 남긴 문구</b>다.
     *
     * <p>추정이 아니라 기록을 읽는 것이고, 오탐이 나도 방향이 안전하다(검색에서 빠질 뿐).
     * 반대로 놓치면 RAG 가 허구를 근거로 답한다 — 그래서 판정 결과를 검사 화면에 표시한다.
     */
    private static final String SYNTHETIC_MARKER = "예시";

    private static final Set<String> SEVERITIES = Set.of("critical", "high", "medium", "low", "info");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 학습 본문 상한 — TEXT 는 65,535 <b>바이트</b>라 한글이면 약 21,800자. 여유를 두고 자른다. */
    public static final int LEARNING_MAX_CONTENT = 20_000;
    public static final int MAX_ROWS = 10_000;

    private final RagLearningDocRepository learningRepo;
    private final RagKnowledgeDocRepository knowledgeRepo;

    public RagCorpusService(RagLearningDocRepository learningRepo, RagKnowledgeDocRepository knowledgeRepo) {
        this.learningRepo = learningRepo;
        this.knowledgeRepo = knowledgeRepo;
    }

    // ── 파싱 결과 ────────────────────────────────────────

    /** 파일 하나에서 파싱된 한 건. {@code payload} 는 아직 저장되지 않은 엔티티다. */
    public static final class Rec {
        public String key;            // docId(학습) / originId(지식)
        public String title;
        public String contentHash;
        public String titleNorm;
        public Object payload;
        public String status = "NEW"; // NEW / DUP_ID / DUP_CONTENT / DUP_TITLE
        public final List<String> flags = new ArrayList<>();
        public Long existingId;
        public final List<String> warnings = new ArrayList<>();
    }

    /** 파일 단위 결과. {@code errors} 가 비어 있지 않으면 전체 가져오기가 중단된다. */
    public static final class FileResult {
        public String filename;
        public boolean ok = true;
        /** 컬럼 밀림을 복구한 행 수 — 조용히 고치지 않고 검사 화면에 그대로 보인다. */
        public int shiftedRows;
        /** synthetic 컬럼이 없어 source 표기로 가상 사례라고 판정한 행 수. */
        public int syntheticInferred;
        /** CSV 가 아닌 역슬래시 이스케이프({@code \"})를 정규화해 재파싱했는가. */
        public boolean quoteFixed;
        public final List<Rec> records = new ArrayList<>();
        public final List<Map<String, Object>> errors = new ArrayList<>();
    }

    /** DB 대조용 색인. 테스트에서 손으로 만들 수 있도록 단순 맵만 담는다. */
    public static final class Existing {
        public final Map<String, Long> byKey = new HashMap<>();
        public final Map<String, Long> byHash = new HashMap<>();
        public final Map<String, Long> byTitle = new HashMap<>();
    }

    // ── 순수 로직 (테스트 경계) ───────────────────────────

    /**
     * 파일 내 중복을 last-wins 로 접는다. 같은 key 가 두 번 나오면 뒤엣것이 이긴다.
     *
     * @return 접힌 목록. 접힌 건수는 {@code before - after} 로 계산한다.
     */
    public static List<Rec> dedupeIntra(List<Rec> recs) {
        Map<String, Rec> byKey = new LinkedHashMap<>();
        for (Rec r : recs) byKey.put(r.key, r);
        return new ArrayList<>(byKey.values());
    }

    /**
     * DB 와 대조해 상태를 매긴다. <b>순수 함수</b> — 여기가 중복 정책의 단일 출처다.
     *
     * <p>우선순위 id &gt; 내용 &gt; 제목. id 중복은 UNIQUE 제약이라 회피 불가이므로 항상 먼저다.
     */
    public static void classify(List<Rec> recs, Existing ex) {
        for (Rec r : recs) {
            r.flags.clear();
            Long byKey = ex.byKey.get(r.key);
            Long byHash = ex.byHash.get(r.contentHash);
            Long byTitle = ex.byTitle.get(r.titleNorm);
            if (byKey != null) r.flags.add("DUP_ID");
            if (byHash != null) r.flags.add("DUP_CONTENT");
            if (byTitle != null) r.flags.add("DUP_TITLE");

            if (byKey != null)       { r.status = "DUP_ID";      r.existingId = byKey; }
            else if (byHash != null) { r.status = "DUP_CONTENT"; r.existingId = byHash; }
            else if (byTitle != null){ r.status = "DUP_TITLE";   r.existingId = byTitle; }
            else                     { r.status = "NEW";         r.existingId = null; }
        }
    }

    /** 학습 CSV 한 행 검증. 문제가 없으면 null, 있으면 사용자에게 보여줄 한국어 사유. */
    public static String validateLearningRow(String[] cells) {
        if (cells.length != LEARNING_HEADER.length) {
            return LEARNING_HEADER.length + "개 컬럼을 기대했지만 " + cells.length
                    + "개입니다 (tags 에 인용 없는 콤마가 있는지 확인하세요)";
        }
        if (isBlank(cells[0])) return "id 가 비어 있습니다";
        if (isBlank(cells[2])) return "title 이 비어 있습니다";
        if (isBlank(cells[3])) return "content 가 비어 있습니다";
        if (cells[3].length() > LEARNING_MAX_CONTENT) {
            return "content 가 너무 깁니다 (" + cells[3].length() + "자, 상한 " + LEARNING_MAX_CONTENT + "자)";
        }
        String sev = cells[6] == null ? "" : cells[6].trim().toLowerCase(Locale.ROOT);
        if (!sev.isEmpty() && !SEVERITIES.contains(sev)) return "severity 미지값: '" + cells[6] + "'";
        String date = cells[7] == null ? "" : cells[7].trim();
        if (!date.isEmpty() && !DATE.matcher(date).matches()) return "created_at 형식 오류: '" + date + "' (YYYY-MM-DD)";
        if (cells[0].length() > 190) return "id 가 너무 깁니다 (190자 상한)";
        return null;
    }

    // ── 밀린 컬럼 복구 ──────────────────────────────────

    /**
     * 한 행을 <b>9컬럼 정규형</b>으로 만든다. 못 만들면 {@code null}(호출자가 종전 오류를 낸다).
     *
     * <p>두 가지를 흡수한다.
     * <ol>
     *   <li><b>컬럼 밀림</b> — {@code tags} 가 인용 없이 콤마를 담아 필드가 늘어난 행.
     *       2026-04-30 내보내기가 통째로 이 상태다(204행 전부, 필드 11~16개).</li>
     *   <li><b>레거시 8컬럼</b> — {@code synthetic} 이 없던 시절 형식.</li>
     * </ol>
     *
     * <p>복구가 결정적으로 가능한 근거: 앞 4컬럼은 {@code content} 가 인용돼 있어 정확하고,
     * 뒤 {@code cols-5}컬럼은 <b>끝에서 세면</b> 정확하다. 그 사이가 전부 {@code tags} 다.
     * 컬럼 위치가 두 형식에서 같기 때문에({@code source}=5·{@code severity}=6·{@code created_at}=7)
     * 꼬리 개수만 다르게 세면 된다.
     *
     * <p>⚠ <b>추측으로 고치지 않는다.</b> 복구한 자리의 {@code severity} 가 enum 이고
     * {@code created_at} 이 날짜여야만 채택한다 — 정렬이 맞았다는 증거다. 하나라도 어긋나면
     * 진짜로 깨진 행이므로 복구를 포기하고 원래 오류를 보고한다. {@link #MAX_TAG_FIELD} 가드도
     * 같은 이유다(그 주석 참조).
     *
     * @param cols 헤더가 알려 준 컬럼 수 (9 또는 8)
     */
    public static String[] normalizeLearningRow(String[] cells, int cols) {
        if (cells == null) return null;
        String[] c;
        if (cells.length == cols) {
            c = cells;
        } else if (cells.length > cols) {
            c = recoverShifted(cells, cols);
            if (c == null) return null;
        } else {
            return null;                       // 필드가 모자라면 복구 근거가 없다
        }
        if (cols == LEARNING_HEADER.length) return c;

        String[] out = Arrays.copyOf(c, LEARNING_HEADER.length);
        out[8] = trim(c[5]).contains(SYNTHETIC_MARKER) ? "true" : "false";
        return out;
    }

    private static String[] recoverShifted(String[] cells, int cols) {
        int tail = cols - 5;                   // 8컬럼 → 3, 9컬럼 → 4
        if (tail < 3) return null;
        int from = 4, to = cells.length - tail;
        if (to <= from) return null;

        StringBuilder tags = new StringBuilder();
        for (int k = from; k < to; k++) {
            String v = cells[k] == null ? "" : cells[k];
            if (v.length() > MAX_TAG_FIELD || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) return null;
            if (tags.length() > 0) tags.append(',');
            tags.append(v);
        }

        String[] out = new String[cols];
        out[0] = cells[0]; out[1] = cells[1]; out[2] = cells[2]; out[3] = cells[3];
        out[4] = tags.toString();
        for (int k = 0; k < tail; k++) out[5 + k] = cells[to + k];

        // 정렬이 맞았다는 증거 — 둘 다 제자리에 있어야만 채택한다(빈 값도 거부).
        if (!SEVERITIES.contains(trim(out[6]).toLowerCase(Locale.ROOT))) return null;
        if (!DATE.matcher(trim(out[7])).matches()) return null;
        return out;
    }

    /**
     * CSV 가 아닌 <b>역슬래시 이스케이프</b>({@code \"})를 정규 표기({@code ""})로 바꾼다.
     *
     * <p>CSV 에서 인용 필드 안의 따옴표는 {@code ""} 로 겹쳐 쓴다. JSON 감각으로 {@code \"} 를
     * 쓰면 그 {@code "} 가 필드를 <b>거기서 끝내</b> 뒤 내용이 통째로 콤마 단위로 쪼개진다
     * (실측: {@code SELECT * FROM \"java\..*Map\"} 한 줄 때문에 본문이 5조각으로 갈라졌다).
     *
     * <p>⚠ <b>무조건 바꾸면 멀쩡한 데이터를 깨뜨린다.</b> 같은 파일에 {@code \""} 형태도 있는데,
     * 이건 역슬래시가 <b>본문</b>(셸 예제의 {@code \"})이고 뒤의 {@code ""} 는 올바른 CSV 이스케이프다.
     * 그래서 뒤 문자가 {@code "}(정상 이스케이프의 짝)·{@code ,}·개행·EOF(=필드 종결)면 손대지 않는다.
     *
     * <p>이 함수는 그 자체로 안전 판정을 하지 않는다 — 호출부가 <b>고친 쪽이 실패를 실제로 줄일 때만</b>
     * 채택한다(멀쩡한 파일은 애초에 이 경로에 오지 않는다).
     */
    static String fixBackslashQuotes(String text) {
        if (text == null || text.indexOf('\\') < 0) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                char after = (i + 2 < text.length()) ? text.charAt(i + 2) : ',';   // EOF = 종결로 본다
                if (after != '"' && after != ',' && after != '\r' && after != '\n') {
                    out.append("\"\"");
                    i++;                                   // 역슬래시와 따옴표를 함께 소비
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    /** 헤더 행이 알려 주는 컬럼 수 (없으면 현행 9). */
    private static int headerCols(List<String[]> rows) {
        if (rows.isEmpty() || !looksLikeHeader(rows.get(0))) return LEARNING_HEADER.length;
        return rows.get(0).length == LEARNING_HEADER_LEGACY.length
                ? LEARNING_HEADER_LEGACY.length : LEARNING_HEADER.length;
    }

    /** 정규형으로 만들 수 없는 행 수 — 재파싱이 개선인지 판정하는 유일한 기준이다. */
    private static int unrecoverableRows(List<String[]> rows) {
        int cols = headerCols(rows);
        int start = (!rows.isEmpty() && looksLikeHeader(rows.get(0))) ? 1 : 0;
        int bad = 0;
        for (int i = start; i < rows.size(); i++) {
            if (normalizeLearningRow(rows.get(i), cols) == null) bad++;
        }
        return bad;
    }

    // ── 파싱 ────────────────────────────────────────────

    /** 학습 CSV 파일 하나를 파싱·검증한다. 파일 단위 오류는 {@code FileResult.errors} 로 모은다. */
    public FileResult parseLearningFile(String filename, String text) {
        FileResult fr = new FileResult();
        fr.filename = filename;
        List<String[]> rows = CsvCodec.parse(text);
        if (rows.isEmpty()) { fr.ok = false; fr.errors.add(err(0, "빈 파일입니다")); return fr; }

        // ⚠ 역슬래시 이스케이프는 **오류 행이 있을 때만** 고쳐서는 안 된다. 본문이 짧으면
        //    쪼개진 조각이 전부 짧은 토큰이라 밀림 복구가 "성공" 해 버리고, 본문이 tags 로
        //    접혀 들어간 채 조용히 통과한다(실측으로 이 경로를 밟았다). \" 는 애초에 올바른
        //    CSV 에 존재할 수 없는 표기이므로, 보이면 항상 바로잡은 쪽을 먼저 본다.
        String fixed = fixBackslashQuotes(text);
        if (!fixed.equals(text)) {
            List<String[]> retry = CsvCodec.parse(fixed);
            // 악화되지 않으면 채택 — 정규화가 손댄 입력은 어차피 정상 CSV 가 아니다.
            if (!retry.isEmpty() && unrecoverableRows(retry) <= unrecoverableRows(rows)) {
                rows = retry;
                fr.quoteFixed = true;
            }
        }

        // 헤더가 형식을 말해 준다 — 9컬럼(현행) / 8컬럼(2026-04-30 내보내기).
        int cols = LEARNING_HEADER.length;
        int start = 0;
        if (looksLikeHeader(rows.get(0))) {
            start = 1;
            if (rows.get(0).length == LEARNING_HEADER_LEGACY.length) cols = LEARNING_HEADER_LEGACY.length;
        } else {
            fr.errors.add(err(0, "헤더 행이 없습니다 (" + String.join(",", LEARNING_HEADER) + ")"));
        }
        if (rows.size() - start > MAX_ROWS) {
            fr.ok = false;
            fr.errors.add(err(0, "행이 너무 많습니다 (" + (rows.size() - start) + ", 상한 " + MAX_ROWS + ")"));
            return fr;
        }

        for (int i = start; i < rows.size(); i++) {
            String[] raw = rows.get(i);
            String[] c = normalizeLearningRow(raw, cols);
            if (c == null) {
                fr.errors.add(err(i + 1, shiftFailure(raw, cols)));
                continue;
            }
            if (raw.length != cols) fr.shiftedRows++;
            if (cols != LEARNING_HEADER.length && "true".equals(c[8])) fr.syntheticInferred++;

            String problem = validateLearningRow(c);
            if (problem != null) { fr.errors.add(err(i + 1, problem)); continue; }

            RagLearningDoc e = new RagLearningDoc();
            e.setDocId(c[0].trim());
            e.setCategory(trim(c[1]));
            e.setTitle(trim(c[2]));
            e.setContent(c[3]);
            e.setTags(trim(c[4]));
            e.setSource(trim(c[5]));
            e.setSeverity(blankTo(trim(c[6]).toLowerCase(Locale.ROOT), "info"));
            e.setDocDate(trim(c[7]));
            e.setSynthetic("true".equalsIgnoreCase(trim(c[8])));
            e.setOriginalFilename(filename);
            e.setContentHash(RagDocHash.contentHash(e.getContent()));
            e.setTitleNorm(RagDocHash.titleNorm(e.getTitle()));

            Rec r = new Rec();
            r.key = e.getDocId();
            r.title = e.getTitle();
            r.contentHash = e.getContentHash();
            r.titleNorm = e.getTitleNorm();
            r.payload = e;
            fr.records.add(r);
        }
        fr.ok = fr.errors.isEmpty();
        return fr;
    }

    /** 지식 .md 파일 하나를 파싱한다. */
    public FileResult parseKnowledgeFile(String filename, String text) {
        FileResult fr = new FileResult();
        fr.filename = filename;
        try {
            MarkdownDocCodec.MdDoc d = MarkdownDocCodec.parse(filename, text);
            RagKnowledgeDoc e = new RagKnowledgeDoc();
            e.setOriginId(d.originId);
            e.setTitle(d.title);
            e.setContent(d.body);
            e.setCategory(d.category);
            e.setTags(d.tags);
            e.setSource(d.source);
            e.setSeverity(blankTo(d.severity, "info"));
            e.setDocDate(blankTo(d.docDate, null));
            e.setSynthetic(d.synthetic);
            e.setEnabled(true);
            e.setOriginalFilename(filename);
            e.setContentHash(RagDocHash.contentHash(d.body));
            e.setTitleNorm(RagDocHash.titleNorm(d.title));
            if (d.docDate != null && !d.docDate.isEmpty() && !DATE.matcher(d.docDate).matches()) {
                fr.ok = false;
                fr.errors.add(err(1, "created_at 형식 오류: '" + d.docDate + "' (YYYY-MM-DD)"));
                return fr;
            }

            Rec r = new Rec();
            r.key = e.getOriginId();
            r.title = e.getTitle();
            r.contentHash = e.getContentHash();
            r.titleNorm = e.getTitleNorm();
            r.payload = e;
            r.warnings.addAll(d.warnings);
            fr.records.add(r);
        } catch (IllegalArgumentException e) {
            fr.ok = false;
            fr.errors.add(err(1, e.getMessage()));
        }
        return fr;
    }

    // ── 대조 · 적용 ──────────────────────────────────────

    /** 학습 DB 대조 색인을 만든다(배치 3회). */
    public Existing existingLearning(Collection<Rec> recs) {
        Existing ex = new Existing();
        Set<String> keys = new LinkedHashSet<>(), hashes = new LinkedHashSet<>(), titles = new LinkedHashSet<>();
        for (Rec r : recs) { keys.add(r.key); hashes.add(r.contentHash); titles.add(r.titleNorm); }
        if (keys.isEmpty()) return ex;
        for (RagLearningDoc d : learningRepo.findByDocIdIn(keys)) ex.byKey.put(d.getDocId(), d.getId());
        for (RagLearningDoc d : learningRepo.findByContentHashIn(hashes)) ex.byHash.putIfAbsent(d.getContentHash(), d.getId());
        for (RagLearningDoc d : learningRepo.findByTitleNormIn(titles)) ex.byTitle.putIfAbsent(d.getTitleNorm(), d.getId());
        return ex;
    }

    /** 지식 DB 대조 색인. */
    public Existing existingKnowledge(Collection<Rec> recs) {
        Existing ex = new Existing();
        Set<String> keys = new LinkedHashSet<>(), hashes = new LinkedHashSet<>(), titles = new LinkedHashSet<>();
        for (Rec r : recs) { keys.add(r.key); hashes.add(r.contentHash); titles.add(r.titleNorm); }
        if (keys.isEmpty()) return ex;
        for (RagKnowledgeDoc d : knowledgeRepo.findByOriginIdIn(keys)) ex.byKey.put(d.getOriginId(), d.getId());
        for (RagKnowledgeDoc d : knowledgeRepo.findByContentHashIn(hashes)) ex.byHash.putIfAbsent(d.getContentHash(), d.getId());
        for (RagKnowledgeDoc d : knowledgeRepo.findByTitleNormIn(titles)) ex.byTitle.putIfAbsent(d.getTitleNorm(), d.getId());
        return ex;
    }

    /** 학습 적용. {@code onDuplicate} = skip | overwrite. */
    @Transactional
    public Map<String, Integer> applyLearning(List<Rec> recs, String onDuplicate, String who) {
        boolean overwrite = "overwrite".equalsIgnoreCase(onDuplicate);
        int inserted = 0, updated = 0, skipped = 0;
        int nextOrder = learningRepo.maxSortOrder();
        List<String> overwritten = new ArrayList<>();

        for (Rec r : recs) {
            RagLearningDoc in = (RagLearningDoc) r.payload;
            if ("NEW".equals(r.status)) {
                in.setSortOrder(++nextOrder);
                in.setUpdatedBy(who);
                learningRepo.save(in);
                inserted++;
            } else if (overwrite && r.existingId != null) {
                RagLearningDoc db = learningRepo.findById(r.existingId).orElse(null);
                if (db == null) { skipped++; continue; }
                // ⚠ id·created_at·sort_order 는 보존한다 — 내보내기 순서와 이력이 흔들리면 안 된다.
                db.setDocId(in.getDocId());
                db.setCategory(in.getCategory());
                db.setTitle(in.getTitle());
                db.setContent(in.getContent());
                db.setTags(in.getTags());
                db.setSource(in.getSource());
                db.setSeverity(in.getSeverity());
                db.setDocDate(in.getDocDate());
                db.setSynthetic(in.isSynthetic());
                db.setContentHash(in.getContentHash());
                db.setTitleNorm(in.getTitleNorm());
                db.setOriginalFilename(in.getOriginalFilename());
                db.setUpdatedBy(who);
                learningRepo.save(db);
                updated++;
                if (overwritten.size() < 20) overwritten.add(db.getDocId());
            } else {
                skipped++;
            }
        }
        if (!overwritten.isEmpty()) {
            logger.info("[RagCorpus] action=import-overwrite kind=learning keys={} by={}", overwritten, who);
        }
        return counts(inserted, updated, skipped);
    }

    /** 지식 적용. */
    @Transactional
    public Map<String, Integer> applyKnowledge(List<Rec> recs, String onDuplicate, String who) {
        boolean overwrite = "overwrite".equalsIgnoreCase(onDuplicate);
        int inserted = 0, updated = 0, skipped = 0;
        List<String> overwritten = new ArrayList<>();

        for (Rec r : recs) {
            RagKnowledgeDoc in = (RagKnowledgeDoc) r.payload;
            if ("NEW".equals(r.status)) {
                in.setUpdatedBy(who);
                knowledgeRepo.save(in);
                inserted++;
            } else if (overwrite && r.existingId != null) {
                RagKnowledgeDoc db = knowledgeRepo.findById(r.existingId).orElse(null);
                if (db == null) { skipped++; continue; }
                db.setOriginId(in.getOriginId());
                db.setTitle(in.getTitle());
                db.setContent(in.getContent());
                db.setCategory(in.getCategory());
                db.setTags(in.getTags());
                db.setSource(in.getSource());
                db.setSeverity(in.getSeverity());
                db.setDocDate(in.getDocDate());
                db.setSynthetic(in.isSynthetic());
                db.setContentHash(in.getContentHash());
                db.setTitleNorm(in.getTitleNorm());
                db.setOriginalFilename(in.getOriginalFilename());
                db.setUpdatedBy(who);
                knowledgeRepo.save(db);
                updated++;
                if (overwritten.size() < 20) overwritten.add(db.getOriginId());
            } else {
                skipped++;
            }
        }
        if (!overwritten.isEmpty()) {
            logger.info("[RagCorpus] action=import-overwrite kind=knowledge keys={} by={}", overwritten, who);
        }
        return counts(inserted, updated, skipped);
    }

    // ── 내보내기 ─────────────────────────────────────────

    /** 학습 전체를 CSV 로. 원본 파일과 <b>바이트 동일</b>해야 한다(헤더·순서·CRLF·무 BOM). */
    public String exportLearningCsv() {
        List<String[]> rows = new ArrayList<>();
        for (RagLearningDoc d : learningRepo.findAllByOrderBySortOrderAscDocIdAsc()) {
            rows.add(new String[]{
                    nz(d.getDocId()), nz(d.getCategory()), nz(d.getTitle()), nz(d.getContent()),
                    nz(d.getTags()), nz(d.getSource()), nz(d.getSeverity()), nz(d.getDocDate()),
                    String.valueOf(d.isSynthetic())
            });
        }
        return CsvCodec.write(LEARNING_HEADER, rows);
    }

    /** 지식 문서 하나를 .md 로. */
    public String exportKnowledgeMd(RagKnowledgeDoc d) {
        MarkdownDocCodec.MdDoc m = new MarkdownDocCodec.MdDoc();
        m.originId = d.getOriginId();
        m.title = d.getTitle();
        m.category = d.getCategory();
        m.tags = d.getTags();
        m.source = d.getSource();
        m.severity = d.getSeverity();
        m.docDate = d.getDocDate();
        m.synthetic = d.isSynthetic();
        m.body = d.getContent();
        return MarkdownDocCodec.render(m);
    }

    public static String timestamp() { return TS.format(LocalDateTime.now()); }

    // ── 조회 ────────────────────────────────────────────

    public RagLearningDocRepository learning() { return learningRepo; }
    public RagKnowledgeDocRepository knowledge() { return knowledgeRepo; }

    // ── 내부 ────────────────────────────────────────────

    private static Map<String, Integer> counts(int inserted, int updated, int skipped) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("inserted", inserted);
        m.put("updated", updated);
        m.put("skipped", skipped);
        return m;
    }

    private static boolean looksLikeHeader(String[] row) {
        return row.length > 0 && "id".equalsIgnoreCase(row[0].trim());
    }

    /**
     * 복구까지 실패한 행의 사유. 종전 컬럼 수 메시지에 <b>복구를 시도했다가 포기한 이유</b>를 덧붙인다
     * — "9개를 기대했는데 16개" 만으로는 사용자가 이미 자동 복구가 있다는 사실조차 알 수 없다.
     */
    private static String shiftFailure(String[] cells, int cols) {
        String base = cols + "개 컬럼을 기대했지만 " + cells.length + "개입니다";
        if (cells.length > cols) {
            return base + " — 밀린 컬럼 자동 복구를 시도했지만 중단했습니다"
                    + " (tags 가 아닌 칸에 인용 없는 콤마가 있거나, severity·created_at 이 제자리에 없습니다)";
        }
        return base + " (tags 에 인용 없는 콤마가 있는지 확인하세요)";
    }

    private static Map<String, Object> err(int row, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", row);
        m.put("error", message);
        return m;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankTo(String s, String d) { return (s == null || s.isEmpty()) ? d : s; }
}

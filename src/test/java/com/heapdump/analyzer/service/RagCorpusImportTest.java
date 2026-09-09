package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.RagLearningDoc;
import com.heapdump.analyzer.service.RagCorpusService.Existing;
import com.heapdump.analyzer.service.RagCorpusService.FileResult;
import com.heapdump.analyzer.service.RagCorpusService.Rec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 가져오기 판정 로직 테스트. 리포지토리를 건드리지 않는 순수 경로만 검증한다
 * (파싱·검증·중복 분류) — DB 왕복은 수동 E2E 로 확인한다.
 */
class RagCorpusImportTest {

    /** 파싱·검증은 리포지토리를 쓰지 않으므로 null 주입으로 충분하다. */
    private final RagCorpusService svc = new RagCorpusService(null, null);

    private static final String HEADER = "id,category,title,content,tags,source,severity,created_at,synthetic\r\n";

    // ── 검증 ────────────────────────────────────────────

    @Test
    @DisplayName("컬럼 수가 어긋나면 원인을 짚어 보고한다 (레거시 파일이 망가진 그 형태)")
    void raggedRowReported() {
        String msg = RagCorpusService.validateLearningRow(
                new String[]{"x", "heap", "제목", "본문", "OOM", "G1GC", "JDK11", "high", "2026-01-01", "false"});
        assertNotNull(msg);
        assertTrue(msg.contains("9개 컬럼"), msg);
        assertTrue(msg.contains("인용 없는 콤마"), "원인을 짚어주지 않으면 사용자가 고칠 수 없다");
    }

    @Test
    @DisplayName("severity·created_at 는 규격을 강제한다 (복구 스크립트와 같은 계약)")
    void enumAndDateValidated() {
        assertNotNull(RagCorpusService.validateLearningRow(
                row("i", "c", "t", "본문", "", "", "urgent", "2026-01-01", "false")));
        assertNotNull(RagCorpusService.validateLearningRow(
                row("i", "c", "t", "본문", "", "", "high", "2026/01/01", "false")));
        assertNull(RagCorpusService.validateLearningRow(
                row("i", "c", "t", "본문", "", "", "", "", "false")), "빈 severity·날짜는 허용");
    }

    @Test
    @DisplayName("category 는 자유 문자열이다 (기존 복구 스크립트도 검증하지 않는다)")
    void categoryIsFree() {
        assertNull(RagCorpusService.validateLearningRow(
                row("i", "완전히새로운분류", "t", "본문", "", "", "info", "", "false")));
    }

    @Test
    @DisplayName("필수 필드 누락은 행 오류")
    void requiredFields() {
        assertNotNull(RagCorpusService.validateLearningRow(row("", "c", "t", "본문", "", "", "", "", "false")));
        assertNotNull(RagCorpusService.validateLearningRow(row("i", "c", "", "본문", "", "", "", "", "false")));
        assertNotNull(RagCorpusService.validateLearningRow(row("i", "c", "t", "", "", "", "", "", "false")));
    }

    // ── 파일 파싱 ────────────────────────────────────────

    @Test
    @DisplayName("정상 CSV 는 헤더를 건너뛰고 행을 담는다")
    void parsesLearningCsv() {
        FileResult fr = svc.parseLearningFile("a.csv",
                HEADER + "oom-1,heap_analysis,제목,본문입니다,\"OOM,G1GC\",사내,high,2026-04-25,false\r\n");
        assertTrue(fr.ok, fr.errors.toString());
        assertEquals(1, fr.records.size());
        RagLearningDoc e = (RagLearningDoc) fr.records.get(0).payload;
        assertEquals("oom-1", e.getDocId());
        assertEquals("OOM,G1GC", e.getTags());
        assertEquals("high", e.getSeverity());
        assertFalse(e.isSynthetic());
        assertEquals("a.csv", e.getOriginalFilename());
    }

    @Test
    @DisplayName("행 오류가 하나라도 있으면 파일이 ok=false — 호출부가 전건 중단한다")
    void oneBadRowFailsFile() {
        FileResult fr = svc.parseLearningFile("a.csv",
                HEADER + "ok-1,c,t,본문,,,info,,false\r\n"
                       + "bad-1,c,t,본문,,,urgent,,false\r\n");
        assertFalse(fr.ok);
        assertEquals(1, fr.errors.size());
        assertEquals(3, fr.errors.get(0).get("row"), "행 번호는 파일 기준(헤더 포함)이어야 사용자가 찾는다");
    }

    @Test
    @DisplayName("빈 severity 는 info 로 채운다")
    void severityDefaults() {
        FileResult fr = svc.parseLearningFile("a.csv", HEADER + "i,c,t,본문,,,,,false\r\n");
        assertEquals("info", ((RagLearningDoc) fr.records.get(0).payload).getSeverity());
    }

    @Test
    @DisplayName("md 파일은 front-matter 와 본문을 나눠 담는다")
    void parsesKnowledgeMd() {
        FileResult fr = svc.parseKnowledgeFile("kb.md", "---\nid: kb-1\nseverity: high\n---\n\n# 제목\n본문\n");
        assertTrue(fr.ok, fr.errors.toString());
        assertEquals("kb-1", fr.records.get(0).key);
        assertEquals("제목", fr.records.get(0).title);
    }

    @Test
    @DisplayName("md 파싱 실패는 파일 오류로 보고된다")
    void badMdReported() {
        FileResult fr = svc.parseKnowledgeFile("kb.md", "---\nid: kb-1\n---\n\n   \n");
        assertFalse(fr.ok);
        assertFalse(fr.errors.isEmpty());
    }

    // ── 중복 판정 ────────────────────────────────────────

    @Test
    @DisplayName("우선순위 DUP_ID > DUP_CONTENT > DUP_TITLE, 걸린 플래그는 전부 보고")
    void duplicatePrecedence() {
        Existing ex = new Existing();
        ex.byKey.put("k1", 11L);
        ex.byHash.put("h1", 22L);
        ex.byTitle.put("t1", 33L);

        Rec all = rec("k1", "h1", "t1");
        Rec content = rec("other", "h1", "t1");
        Rec title = rec("other", "otherhash", "t1");
        Rec fresh = rec("new", "newhash", "newtitle");

        RagCorpusService.classify(List.of(all, content, title, fresh), ex);

        assertEquals("DUP_ID", all.status);
        assertEquals(11L, all.existingId);
        assertEquals(List.of("DUP_ID", "DUP_CONTENT", "DUP_TITLE"), all.flags,
                "하나만 고르되 사용자에게는 전부 보여야 판단이 선다");
        assertEquals("DUP_CONTENT", content.status);
        assertEquals(22L, content.existingId);
        assertEquals("DUP_TITLE", title.status);
        assertEquals(33L, title.existingId);
        assertEquals("NEW", fresh.status);
        assertNull(fresh.existingId);
    }

    @Test
    @DisplayName("파일 안 같은 키는 last-wins 로 접는다")
    void intraFileDedupe() {
        Rec a = rec("same", "h1", "t1");
        Rec b = rec("same", "h2", "t2");
        Rec c = rec("other", "h3", "t3");
        List<Rec> kept = RagCorpusService.dedupeIntra(List.of(a, b, c));
        assertEquals(2, kept.size());
        assertEquals("h2", kept.get(0).contentHash, "뒤엣것이 이긴다");
    }

    @Test
    @DisplayName("CRLF/LF 차이는 같은 내용으로 본다 — 아니면 Windows 왕복마다 신규가 된다")
    void lineEndingInsensitiveDuplicate() {
        FileResult crlf = svc.parseLearningFile("a.csv", HEADER + "i,c,t,\"1줄\r\n2줄\",,,info,,false\r\n");
        FileResult lf   = svc.parseLearningFile("a.csv", HEADER + "i,c,t,\"1줄\n2줄\",,,info,,false\n");
        assertEquals(crlf.records.get(0).contentHash, lf.records.get(0).contentHash);
    }

    private static Rec rec(String key, String hash, String titleNorm) {
        Rec r = new Rec();
        r.key = key;
        r.contentHash = hash;
        r.titleNorm = titleNorm;
        r.title = titleNorm;
        return r;
    }

    // ── 밀린 컬럼 복구 (2026-04-30 내보내기) ─────────────

    private static final String HEADER8 =
            "id,category,title,content,tags,source,severity,created_at\r\n";

    @Test
    @DisplayName("레거시 8컬럼 + tags 인용 없는 콤마 — 원본 그대로 가져와진다")
    void legacyShiftedRowsRecovered() {
        // 2026-04-30 내보내기 실제 형태: tags 가 인용 없이 콤마를 담아 필드가 8개보다 많다.
        FileResult fr = svc.parseLearningFile("rag-knowledge-20260430.csv",
                HEADER8 + "oom-2026-001,heap_analysis,Old Generation OOM,본문입니다,"
                        + "OOM,G1GC,JDK11,OldGen,https://x.example,high,2026-04-25\r\n");

        assertTrue(fr.ok, "복구 가능한 행인데 오류가 났다: " + fr.errors);
        assertEquals(1, fr.records.size());
        assertEquals(1, fr.shiftedRows, "복구 사실이 집계되지 않으면 화면에 알릴 수 없다");

        RagLearningDoc d = (RagLearningDoc) fr.records.get(0).payload;
        assertEquals("oom-2026-001", d.getDocId());
        assertEquals("본문입니다", d.getContent());
        assertEquals("OOM,G1GC,JDK11,OldGen", d.getTags(), "밀린 조각이 tags 로 되돌아와야 한다");
        assertEquals("https://x.example", d.getSource());
        assertEquals("high", d.getSeverity());
        assertEquals("2026-04-25", d.getDocDate());
    }

    @Test
    @DisplayName("synthetic 컬럼이 없으면 source 의 '예시' 표기로 가상 사례를 표시한다")
    void legacySyntheticInferredFromSourceMarker() {
        FileResult fr = svc.parseLearningFile("legacy.csv",
                HEADER8 + "ts-1,troubleshooting,사례,본문,tagA,tagB,(예시 — 실제 사례로 교체 필요),high,2026-04-30\r\n"
                        + "ok-1,heap_analysis,실제,본문,tagA,tagB,https://x.example,info,2026-04-30\r\n");

        assertTrue(fr.ok, String.valueOf(fr.errors));
        assertEquals(2, fr.records.size());
        assertEquals(1, fr.syntheticInferred);
        assertTrue(((RagLearningDoc) fr.records.get(0).payload).isSynthetic(),
                "가상 사례를 놓치면 RAG 가 허구를 근거로 답한다");
        assertFalse(((RagLearningDoc) fr.records.get(1).payload).isSynthetic());
    }

    @Test
    @DisplayName("복구는 추측하지 않는다 — severity·created_at 이 제자리에 없으면 거부")
    void recoveryRequiresProofOfAlignment() {
        // severity 자리에 enum 이 아닌 값 → 정렬이 맞다는 증거가 없다.
        FileResult bad = svc.parseLearningFile("a.csv",
                HEADER8 + "i,c,t,본문,a,b,출처,urgent,2026-04-25\r\n");
        assertFalse(bad.ok);
        assertTrue(String.valueOf(bad.errors.get(0).get("error")).contains("자동 복구"),
                "복구를 시도했다가 포기했다는 사실이 안 보이면 사용자가 원인을 좁힐 수 없다: " + bad.errors);

        // 날짜 자리가 날짜가 아니면 마찬가지
        FileResult bad2 = svc.parseLearningFile("a.csv",
                HEADER8 + "i,c,t,본문,a,b,출처,high,2026/04/25\r\n");
        assertFalse(bad2.ok);
    }

    @Test
    @DisplayName("본문이 tags 자리로 밀린 경우는 복구하지 않는다 (조용한 오배치 차단)")
    void longFieldBlocksRecovery() {
        // title 에 인용 없는 콤마가 있으면 밀림이 4컬럼보다 앞에서 시작해 본문이 tags 자리로 온다.
        // 뒤에서 세는 규칙 특성상 severity·날짜 검증은 통과하므로 길이 가드가 유일한 방어선이다.
        String longBody = "가".repeat(200);
        FileResult fr = svc.parseLearningFile("a.csv",
                HEADER8 + "i,c,제목앞,제목뒤," + longBody + ",tagA,출처,high,2026-04-25\r\n");
        assertFalse(fr.ok, "본문을 tags 로 접어 넣고 성공했다면 데이터가 조용히 망가진 것이다");
        assertEquals(0, fr.shiftedRows);
    }

    @Test
    @DisplayName("현행 9컬럼 파일은 종전 그대로 (복구 경로가 끼어들지 않는다)")
    void currentFormatUnaffected() {
        FileResult fr = svc.parseLearningFile("a.csv",
                HEADER + "oom-1,heap_analysis,제목,본문,\"OOM,G1GC\",사내,high,2026-04-25,true\r\n");
        assertTrue(fr.ok, String.valueOf(fr.errors));
        assertEquals(0, fr.shiftedRows);
        assertEquals(0, fr.syntheticInferred);
        RagLearningDoc d = (RagLearningDoc) fr.records.get(0).payload;
        assertEquals("OOM,G1GC", d.getTags());
        assertTrue(d.isSynthetic(), "9컬럼이면 파일의 synthetic 값을 그대로 쓴다");
    }

    @Test
    @DisplayName("역슬래시 이스케이프는 정규 표기로 바로잡되, 본문의 역슬래시는 건드리지 않는다")
    void backslashEscapedQuotesNormalized() {
        // JSON 감각으로 쓴 \" 는 CSV 가 아니다 — 그 따옴표가 필드를 거기서 끝내 본문이 쪼개진다.
        FileResult fr = svc.parseLearningFile("a.csv",
                HEADER8 + "mat-1,mat_tip,OQL,\"와일드카드: FROM \\\"java..*Map\\\" 정규식, 그리고 집계: count, sum\","
                        + "MAT,OQL,https://x.example,info,2026-04-30\r\n");
        assertTrue(fr.ok, String.valueOf(fr.errors));
        assertTrue(fr.quoteFixed, "정규화 사실이 보고되지 않으면 무엇을 손댔는지 알 수 없다");
        RagLearningDoc d = (RagLearningDoc) fr.records.get(0).payload;
        assertTrue(d.getContent().contains("\"java..*Map\""), "따옴표가 본문에 남아야 한다: " + d.getContent());
        assertTrue(d.getContent().contains("count, sum"), "쪼개졌던 본문이 한 덩어리로 돌아와야 한다");
        assertEquals("MAT,OQL", d.getTags());

        // 뒤가 "" 인 \"" 는 역슬래시가 본문이고 CSV 이스케이프는 정상이다 — 손대면 깨진다.
        String ok = "id,t\r\n1,\"help -g \\\"\"Data Source\\\"\"\"\r\n";
        assertEquals(ok, RagCorpusService.fixBackslashQuotes(ok), "정상 이스케이프를 건드렸다");
    }

    @Test
    @DisplayName("실제 코퍼스 파일이 손 안 대고 통째로 가져와진다")
    void realCorpusFileImportsAsIs() throws Exception {
        java.io.File f = new java.io.File("rag-data/rag-knowledge-20260430.csv");
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists(), "코퍼스 파일 없음: " + f.getAbsolutePath());

        FileResult fr = svc.parseLearningFile(f.getName(),
                java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8));

        assertTrue(fr.ok, "복구 실패 " + fr.errors.size() + "건: "
                + fr.errors.subList(0, Math.min(3, fr.errors.size())));
        assertTrue(fr.records.size() > 80, "행이 이상하게 적다: " + fr.records.size());
        assertEquals(fr.records.size(), fr.shiftedRows, "이 파일은 전 행이 밀려 있다");
        assertTrue(fr.syntheticInferred > 0, "가상 사례(예시 표기)가 하나도 안 잡혔다");
        System.out.println("실제 파일: " + fr.records.size() + "행 · 복구 " + fr.shiftedRows
                + " · 가상 " + fr.syntheticInferred);
    }

    private static String[] row(String... c) { return c; }
}

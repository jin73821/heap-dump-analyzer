package com.heapdump.analyzer.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 4180 코덱 테스트.
 *
 * <p>가장 중요한 건 {@link #golden_실제코퍼스_왕복_바이트동일}() 이다 — 학습 코퍼스를 DB 로
 * 옮긴 뒤에도 내보내기가 원본과 바이트 동일해야 전환이 안전하다고 판정할 수 있다.
 */
class CsvCodecTest {

    private static String[] row(String... cells) { return cells; }

    // ── 파싱 ────────────────────────────────────────────

    @Test
    @DisplayName("인용 필드 안의 콤마는 구분자가 아니다")
    void quotedComma() {
        List<String[]> r = CsvCodec.parse("a,\"b,c\",d\r\n");
        assertEquals(1, r.size());
        assertArrayEquals(row("a", "b,c", "d"), r.get(0));
    }

    @Test
    @DisplayName("인용 필드 안의 개행은 행을 나누지 않는다")
    void quotedNewline() {
        List<String[]> r = CsvCodec.parse("a,\"1줄\r\n2줄\",c\r\n");
        assertEquals(1, r.size());
        assertEquals("1줄\r\n2줄", r.get(0)[1]);
    }

    @Test
    @DisplayName("\"\" 는 리터럴 따옴표")
    void escapedQuote() {
        List<String[]> r = CsvCodec.parse("\"he said \"\"hi\"\"\",x\r\n");
        assertEquals("he said \"hi\"", r.get(0)[0]);
    }

    @Test
    @DisplayName("선두 BOM 제거 — Excel 왕복 시 첫 컬럼명이 오염되면 전 행이 id 없음으로 실패한다")
    void stripsBom() {
        List<String[]> r = CsvCodec.parse("﻿id,title\r\nx,y\r\n");
        assertEquals("id", r.get(0)[0]);
    }

    @Test
    @DisplayName("LF 전용 입력도 받는다")
    void lfOnly() {
        List<String[]> r = CsvCodec.parse("a,b\nc,d\n");
        assertEquals(2, r.size());
        assertArrayEquals(row("c", "d"), r.get(1));
    }

    @Test
    @DisplayName("끝 개행이 빈 행을 만들지 않는다")
    void noTrailingEmptyRow() {
        assertEquals(2, CsvCodec.parse("a\r\nb\r\n").size());
        assertEquals(2, CsvCodec.parse("a\r\nb").size());
    }

    @Test
    @DisplayName("컬럼 수가 어긋난 행을 보정하지 않고 그대로 돌려준다")
    void raggedRowSurfaced() {
        // 레거시 파일이 망가진 형태 — tags 에 인용 없는 콤마가 들어가 컬럼이 밀렸다.
        List<String[]> r = CsvCodec.parse("id,cat,tags,sev\r\nx,heap,OOM,G1GC,JDK11,high\r\n");
        assertEquals(4, r.get(0).length);
        assertEquals(6, r.get(1).length, "보정해 버리면 사용자가 원인을 영영 모른다");
    }

    // ── 쓰기 ────────────────────────────────────────────

    @Test
    @DisplayName("CRLF 로 끝나고 BOM 을 붙이지 않는다")
    void writesCrlfWithoutBom() {
        String out = CsvCodec.write(row("a", "b"), List.<String[]>of(row("1", "2")));
        assertEquals("a,b\r\n1,2\r\n", out);
        assertTrue(out.charAt(0) != '﻿');
    }

    @Test
    @DisplayName("필요할 때만 인용한다 (QUOTE_MINIMAL — 파이썬 csv 와 동일 규칙)")
    void minimalQuoting() {
        assertEquals("plain", CsvCodec.cell("plain"));
        assertEquals("\"a,b\"", CsvCodec.cell("a,b"));
        assertEquals("\"q\"\"q\"", CsvCodec.cell("q\"q"));
        assertEquals("\"l1\nl2\"", CsvCodec.cell("l1\nl2"));
        assertEquals("", CsvCodec.cell(null));
    }

    @Test
    @DisplayName("파싱 → 재작성 왕복")
    void roundTrip() {
        // ⚠ 인용이 꼭 필요한 셀만 인용된 입력이어야 왕복이 성립한다(QUOTE_MINIMAL).
        //    불필요한 인용은 재작성에서 벗겨지는 게 정상 — 파이썬 csv 도 동일하다.
        String src = "id,title,tags\r\noom-1,OOM (G1GC),\"OOM,G1GC\"\r\n";
        List<String[]> rows = CsvCodec.parse(src);
        String out = CsvCodec.write(rows.get(0), rows.subList(1, rows.size()));
        assertEquals(src, out);
    }

    // ── 골든: 실제 코퍼스 ─────────────────────────────────

    @Test
    @DisplayName("실제 학습 코퍼스 84행을 파싱 → 재작성하면 바이트가 동일하다")
    void golden_실제코퍼스_왕복_바이트동일() throws Exception {
        File f = new File("rag-data/rag-knowledge-v2.csv");
        Assumptions.assumeTrue(f.exists(), "코퍼스 파일이 없으면 건너뛴다: " + f.getAbsolutePath());

        String src = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        List<String[]> rows = CsvCodec.parse(src);

        assertEquals(85, rows.size(), "헤더 1 + 데이터 84행");
        for (String[] r : rows) assertEquals(9, r.length, "모든 행이 9컬럼이어야 한다");
        assertArrayEquals(row("id", "category", "title", "content", "tags",
                              "source", "severity", "created_at", "synthetic"), rows.get(0));

        List<String[]> data = new ArrayList<>(rows.subList(1, rows.size()));
        assertEquals(src, CsvCodec.write(rows.get(0), data),
                "내보내기가 원본과 바이트 동일해야 DB 전환을 안전하다고 판정할 수 있다");
    }
}

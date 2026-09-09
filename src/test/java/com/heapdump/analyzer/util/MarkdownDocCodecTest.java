package com.heapdump.analyzer.util;

import com.heapdump.analyzer.util.MarkdownDocCodec.MdDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownDocCodecTest {

    @Test
    @DisplayName("front-matter 를 읽고 본문과 분리한다")
    void parsesFrontMatter() {
        String md = "---\nid: oom-2026-001\ntitle: OOM (G1GC)\ncategory: heap_analysis\n"
                  + "tags: OOM,G1GC\nsource: https://x/y\nseverity: high\n"
                  + "created_at: 2026-04-25\nsynthetic: true\n---\n\n본문 첫 줄\n둘째 줄\n";
        MdDoc d = MarkdownDocCodec.parse("whatever.md", md);
        assertEquals("oom-2026-001", d.originId);
        assertEquals("OOM (G1GC)", d.title);
        assertEquals("heap_analysis", d.category);
        assertEquals("OOM,G1GC", d.tags);
        assertEquals("https://x/y", d.source);
        assertEquals("high", d.severity);
        assertEquals("2026-04-25", d.docDate);
        assertTrue(d.synthetic);
        assertEquals("본문 첫 줄\n둘째 줄", d.body);
    }

    @Test
    @DisplayName("제목을 헤딩에서 가져오면 그 헤딩은 본문에서 제거한다 (색인기가 제목을 다시 붙인다)")
    void titleFromHeadingIsStrippedFromBody() {
        MdDoc d = MarkdownDocCodec.parse("guide.md", "# 제목입니다\n\n내용입니다\n");
        assertEquals("제목입니다", d.title);
        assertFalse(d.body.contains("# 제목입니다"), "본문에 남으면 임베딩에 제목이 두 번 들어간다");
        assertEquals("내용입니다", d.body);
    }

    @Test
    @DisplayName("front-matter title 이 있으면 헤딩은 본문에 그대로 둔다")
    void frontMatterTitleKeepsHeading() {
        MdDoc d = MarkdownDocCodec.parse("x.md", "---\ntitle: 진짜 제목\n---\n\n# 다른 헤딩\n내용\n");
        assertEquals("진짜 제목", d.title);
        assertTrue(d.body.startsWith("# 다른 헤딩"));
    }

    @Test
    @DisplayName("헤딩도 없으면 파일명이 제목이 되고 경고가 남는다")
    void titleFallsBackToFilename() {
        MdDoc d = MarkdownDocCodec.parse("jeus-튜닝.md", "그냥 본문만 있습니다\n");
        assertEquals("jeus-튜닝", d.title);
        assertEquals("jeus", d.originId, "슬러그는 ASCII 로 접힌다(한글은 제거)");
        assertFalse(d.warnings.isEmpty());
    }

    @Test
    @DisplayName("한글만 있는 파일명은 오류가 아니라 본문 해시로 결정적 id 를 만든다")
    void koreanOnlyFilenameGetsHashId() {
        MdDoc a = MarkdownDocCodec.parse("메모리누수.md", "같은 본문입니다\n");
        MdDoc b = MarkdownDocCodec.parse("전혀다른이름.md", "같은 본문입니다\n");
        assertTrue(a.originId.startsWith("doc-"));
        assertEquals(a.originId, b.originId, "같은 내용이면 같은 id — 재가져오기가 중복으로 잡혀야 한다");
        assertFalse(a.warnings.isEmpty());
    }

    @Test
    @DisplayName("닫히지 않은 front-matter 는 오류가 아니라 전체 본문 + 경고")
    void unterminatedFrontMatter() {
        MdDoc d = MarkdownDocCodec.parse("a.md", "---\nid: x\n제목 없이 계속되는 본문\n");
        assertTrue(d.body.contains("---"));
        assertFalse(d.warnings.isEmpty());
    }

    @Test
    @DisplayName("CRLF 입력도 동일하게 처리한다")
    void crlfInput() {
        MdDoc d = MarkdownDocCodec.parse("a.md", "---\r\nid: k1\r\n---\r\n\r\n# 제목\r\n본문\r\n");
        assertEquals("k1", d.originId);
        assertEquals("제목", d.title);
        assertEquals("본문", d.body);
    }

    @Test
    @DisplayName("왕복 무손실 — render 는 항상 id 를 써넣어 파일명이 바뀌어도 같은 문서로 인식된다")
    void roundTrip() {
        MdDoc a = MarkdownDocCodec.parse("orig.md",
                "---\nid: kb-1\ntitle: 제목\ncategory: ops\ntags: a,b\nsource: 사내문서\n"
              + "severity: medium\ncreated_at: 2026-08-30\nsynthetic: false\n---\n\n본문\n");
        String rendered = MarkdownDocCodec.render(a);
        assertTrue(rendered.contains("id: kb-1"));

        MdDoc b = MarkdownDocCodec.parse("전혀-다른-이름.md", rendered);
        assertEquals(a.originId, b.originId);
        assertEquals(a.title, b.title);
        assertEquals(a.category, b.category);
        assertEquals(a.tags, b.tags);
        assertEquals(a.source, b.source);
        assertEquals(a.severity, b.severity);
        assertEquals(a.docDate, b.docDate);
        assertEquals(a.synthetic, b.synthetic);
        assertEquals(a.body, b.body);
    }

    @Test
    @DisplayName("severity 미지값은 오류가 아니라 info + 경고")
    void unknownSeverity() {
        MdDoc d = MarkdownDocCodec.parse("a.md", "---\nid: x\nseverity: urgent\n---\n\n본문\n");
        assertEquals("info", d.severity);
        assertFalse(d.warnings.isEmpty());
    }

    @Test
    @DisplayName("빈 본문·NUL 바이트는 거부한다")
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> MarkdownDocCodec.parse("a.md", "---\nid: x\n---\n\n   \n"));
        assertThrows(IllegalArgumentException.class,
                () -> MarkdownDocCodec.parse("a.md", "본문\0바이너리"));
    }

    @Test
    @DisplayName("슬러그는 소문자 ASCII 로 접고 길이를 제한한다")
    void slugRules() {
        assertEquals("a-b-c", MarkdownDocCodec.slug("A B/C"));
        assertEquals("oom.2026_1-x", MarkdownDocCodec.slug("--oom.2026_1-x--"));
        assertTrue(MarkdownDocCodec.slug("x".repeat(300)).length() <= MarkdownDocCodec.MAX_ORIGIN_ID);
    }
}

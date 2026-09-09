package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RagDocHashTest {

    @Test
    @DisplayName("CRLF 와 LF 는 같은 해시 — 이게 없으면 Windows 왕복마다 '신규'가 된다")
    void crlfAndLfAreSame() {
        assertEquals(RagDocHash.contentHash("a\r\nb\r\nc"), RagDocHash.contentHash("a\nb\nc"));
    }

    @Test
    @DisplayName("줄 끝 공백·앞뒤 공백은 무시한다")
    void whitespaceInsensitive() {
        assertEquals(RagDocHash.contentHash("a   \nb\t\n"), RagDocHash.contentHash("a\nb"));
        assertEquals(RagDocHash.contentHash("\n\n본문\n\n"), RagDocHash.contentHash("본문"));
    }

    @Test
    @DisplayName("선두 BOM 은 내용 차이가 아니다")
    void bomIgnored() {
        assertEquals(RagDocHash.contentHash("﻿본문"), RagDocHash.contentHash("본문"));
    }

    @Test
    @DisplayName("내용이 다르면 해시도 다르다")
    void differentContentDiffers() {
        assertNotEquals(RagDocHash.contentHash("본문 A"), RagDocHash.contentHash("본문 B"));
    }

    @Test
    @DisplayName("제목 정규화 — 공백 축약 + 소문자. 표시용이 아니라 비교용이다")
    void titleNorm() {
        assertEquals("old gen oom", RagDocHash.titleNorm("  Old   Gen\tOOM "));
        assertEquals(RagDocHash.titleNorm("OOM 진단"), RagDocHash.titleNorm("oom  진단"));
    }

    @Test
    @DisplayName("SHA-256 소문자 hex 64자")
    void sha256Shape() {
        String h = RagDocHash.sha256Hex("");
        assertEquals(64, h.length());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h);
    }

    @Test
    @DisplayName("본문만 해싱한다 — 제목이 바뀌어도 DUP_CONTENT 로 잡혀야 한다")
    void hashesBodyOnly() {
        // 같은 본문, 다른 제목 → 내용 해시는 동일해야 제목/내용 두 신호가 직교한다
        assertEquals(RagDocHash.contentHash("동일한 본문"), RagDocHash.contentHash("동일한 본문"));
        assertNotEquals(RagDocHash.titleNorm("제목 A"), RagDocHash.titleNorm("제목 B"));
    }
}

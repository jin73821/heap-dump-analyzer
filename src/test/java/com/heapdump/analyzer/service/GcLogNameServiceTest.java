package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GC 로그 분석명 {@code 호스트네임_인스턴스명_일자} 규칙 (2026-09-20).
 *
 * <p>일자는 <b>로그 시작일</b>이다 — 업로드일이 아니다(예전 로그를 오늘 올리면 이름과 내용의 날짜가 어긋난다).
 * 자정을 넘는 로그도 시작일 한 건으로 두고, 이어 붙여 여러 날에 걸칠 때만 범위로 쓴다.
 */
class GcLogNameServiceTest {

    private static final LocalDate D0 = LocalDate.of(2026, 9, 19);

    @Test
    @DisplayName("호스트_인스턴스_일자 — 인스턴스가 없으면 그 자리는 통째로 빠진다")
    void nameIsHostInstanceDate() {
        assertEquals("was1_container1_20260919", GcLogNameService.build("was1", "container1", D0, D0));
        assertEquals("was1_20260919", GcLogNameService.build("was1", null, D0, D0));
        assertEquals("was1_20260919", GcLogNameService.build("was1", "   ", D0, D0), "빈 인스턴스에 밑줄이 두 번 들어갔다");
        assertEquals("was1_container1_20260919", GcLogNameService.build("was1", "container1", D0, null));
    }

    @Test
    @DisplayName("여러 날에 걸치면 범위로 쓴다 — 해가 같으면 뒤는 월일만")
    void multiDayUsesRange() {
        assertEquals("was1_c1_20260919-0920", GcLogNameService.build("was1", "c1", D0, LocalDate.of(2026, 9, 20)));
        assertEquals("was1_c1_20261231-20270101",
                GcLogNameService.build("was1", "c1", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)));
        // 자정을 넘겨도 끝일이 시작일보다 뒤가 아니면 단일 일자
        assertEquals("was1_c1_20260919", GcLogNameService.build("was1", "c1", D0, D0));
    }

    @Test
    @DisplayName("호스트네임이 없으면 이름을 만들지 않는다 — 화면은 그때만 파일명으로 떨어진다")
    void withoutHostThereIsNoName() {
        assertNull(GcLogNameService.build(null, "c1", D0, D0));
        assertNull(GcLogNameService.build("  ", "c1", D0, D0));
    }

    @Test
    @DisplayName("같은 이름이 이미 있으면 _2, _3 을 붙인다 (파일명이 키이므로 이름은 겹쳐도 된다)")
    void duplicateGetsSuffix() {
        Set<String> taken = new HashSet<>(Set.of("was1_20260919", "was1_20260919_2"));
        assertEquals("was1_20260919_3", GcLogNameService.assignUnique("was1_20260919", taken::contains));
        assertEquals("was1_20260919", GcLogNameService.assignUnique("was1_20260919", n -> false));
    }

    @Test
    @DisplayName("경로·공백 문자는 밑줄로 정리하고 길이를 제한한다 — 이름이 경로처럼 읽히면 안 된다")
    void sanitizesPathAndWhitespace() {
        assertEquals("was_1", GcLogNameService.sanitize(" was 1 "));
        assertEquals("a_b", GcLogNameService.sanitize("a/b"));
        assertEquals("a_b", GcLogNameService.sanitize("a\\b"));
        assertEquals("a_b", GcLogNameService.sanitize("a///b"));
        assertEquals("", GcLogNameService.sanitize("///"));
        assertTrue(GcLogNameService.sanitize("x".repeat(300)).length() <= 100);
        assertTrue(GcLogNameService.build("h".repeat(200), "i".repeat(200), D0, D0).length() <= GcLogNameService.MAX_LEN);
    }
}

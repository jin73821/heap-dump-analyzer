package com.heapdump.analyzer.config;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.service.llmguard.BuiltinDetectors;
import com.heapdump.analyzer.service.llmguard.LlmGuardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 내장 규칙 시더 — 새 키만 INSERT, 판정 방식 내장 행의 표시문·설명은 코드 정의에 맞춘다 (2026-11-28).
 * 관리자가 바꾼 이름·사용·조치와 키워드형 내장 규칙의 단어 목록은 보존한다.
 */
class LlmGuardRuleSeederTest {

    private static BuiltinDetectors.Def def(String key) {
        return BuiltinDetectors.DEFAULTS.stream().filter(d -> d.key.equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("옛 표시문은 갱신, 관리자 변경·키워드 목록은 보존, 빠진 키(CARD_CVC)만 INSERT")
    void syncAndInsert() {
        Map<String, LlmGuardRule> db = new HashMap<>();
        for (BuiltinDetectors.Def d : BuiltinDetectors.DEFAULTS) {
            if (d.key.equals(BuiltinDetectors.CARD_CVC)) continue;   // 배포 전 DB 에는 없다
            db.put(d.key, d.toEntity());
        }
        LlmGuardRule rrn = db.get(BuiltinDetectors.RRN);
        rrn.setPattern("YYMMDD-[1-8]NNNNNN (생년월일 유효성 검증, 하이픈 선택)");   // v2.7.2 표시문
        rrn.setDescription("생년월일이 유효하지 않은 13자리 숫자는 제외합니다.");
        rrn.setName("주민번호(관리자가 바꾼 이름)");
        rrn.setInputAction(LlmGuardRule.ACTION_CONFIRM);
        rrn.setEnabled(false);
        LlmGuardRule kw = db.get(BuiltinDetectors.KW_CREDIT);
        kw.setPattern("주민번호\n관리자 단어");
        kw.setDescription("관리자 설명");

        LlmGuardRuleRepository repo = Mockito.mock(LlmGuardRuleRepository.class);
        Mockito.when(repo.findByBuiltinKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(db.get(inv.<String>getArgument(0))));
        Mockito.when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LlmGuardService guard = Mockito.mock(LlmGuardService.class);

        new LlmGuardRuleSeeder(repo, guard).run(null);

        ArgumentCaptor<LlmGuardRule> saved = ArgumentCaptor.forClass(LlmGuardRule.class);
        Mockito.verify(repo, Mockito.atLeastOnce()).save(saved.capture());
        List<LlmGuardRule> all = saved.getAllValues();

        assertEquals(def(BuiltinDetectors.RRN).pattern, rrn.getPattern(), "표시문 갱신");
        assertEquals(def(BuiltinDetectors.RRN).description, rrn.getDescription(), "설명 갱신");
        assertEquals("주민번호(관리자가 바꾼 이름)", rrn.getName(), "이름 보존");
        assertEquals(LlmGuardRule.ACTION_CONFIRM, rrn.getInputAction(), "조치 보존");
        assertFalse(rrn.isEnabled(), "사용 여부 보존");
        assertEquals("주민번호\n관리자 단어", kw.getPattern(), "키워드 목록은 건드리지 않는다");
        assertFalse(all.contains(kw), "키워드 내장 행은 저장하지 않는다");

        long inserted = all.stream().filter(r -> BuiltinDetectors.CARD_CVC.equals(r.getBuiltinKey())).count();
        assertEquals(1, inserted, "빠진 키만 INSERT");
        assertTrue(all.stream().noneMatch(r -> BuiltinDetectors.EMAIL.equals(r.getBuiltinKey())),
                "이미 정의와 같은 행은 저장하지 않는다");
        Mockito.verify(guard).reload();
    }
}

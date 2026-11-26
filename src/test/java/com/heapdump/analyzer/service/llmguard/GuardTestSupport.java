package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

/** LLM Guard 테스트 공용 — DB 없이 규칙 집합을 꾸민다. */
final class GuardTestSupport {

    private GuardTestSupport() {}

    /** 내장 기본 규칙만으로 동작하는 서비스(리포지토리·기록 없음, 토글 모두 켬). */
    static LlmGuardService defaults() {
        LlmGuardService s = new LlmGuardService(null, null, null);
        s.init();
        s.setSettings(true, true, true);
        return s;
    }

    /** 내장 기본 규칙 + 추가 규칙. id 는 1000 부터. */
    static LlmGuardService withRules(LlmGuardLogService log, LlmGuardRule... extra) {
        List<LlmGuardRule> all = new ArrayList<>();
        long id = 1;
        for (BuiltinDetectors.Def d : BuiltinDetectors.DEFAULTS) {
            LlmGuardRule r = d.toEntity();
            r.setId(id++);
            all.add(r);
        }
        long uid = 1000;
        for (LlmGuardRule r : extra) {
            if (r.getId() == null) r.setId(uid++);
            all.add(r);
        }
        LlmGuardRuleRepository repo = Mockito.mock(LlmGuardRuleRepository.class);
        Mockito.when(repo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(all);
        LlmGuardService s = new LlmGuardService(null, repo, log);
        s.init();
        s.setSettings(true, true, true);
        return s;
    }

    static LlmGuardRule regex(String name, String pattern, String action, boolean output) {
        LlmGuardRule r = new LlmGuardRule();
        r.setName(name);
        r.setCategory("기타");
        r.setMatchType(LlmGuardRule.TYPE_REGEX);
        r.setPattern(pattern);
        r.setInputAction(action);
        r.setCheckInput(true);
        r.setCheckOutput(output);
        r.setEnabled(true);
        r.setSortOrder(500);
        return r;
    }

    static LlmGuardRule keyword(String name, String words, String action, boolean output) {
        LlmGuardRule r = regex(name, words, action, output);
        r.setMatchType(LlmGuardRule.TYPE_KEYWORD);
        return r;
    }

    /** 내장 규칙 하나로 유효 일치(가림이 실제로 바뀌는 것)만 센다. */
    static List<String> builtinHits(String key, String text) {
        BuiltinDetectors.Def def = BuiltinDetectors.DEFAULTS.stream()
                .filter(d -> d.key.equals(key)).findFirst().orElseThrow();
        CompiledRule rule = CompiledRule.compile(def.toEntity());
        List<Hit> raw = new ArrayList<>();
        rule.scan(text, 0, text.length(), true, raw);
        List<String> out = new ArrayList<>();
        for (Hit h : raw) if (h.changes(text)) out.add(text.substring(h.replStart, h.replEnd) + "=>" + h.replacement);
        return out;
    }
}

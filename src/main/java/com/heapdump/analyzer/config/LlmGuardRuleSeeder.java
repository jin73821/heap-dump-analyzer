package com.heapdump.analyzer.config;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import com.heapdump.analyzer.repository.LlmGuardRuleRepository;
import com.heapdump.analyzer.service.llmguard.BuiltinDetectors;
import com.heapdump.analyzer.service.llmguard.LlmGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * LLM Guard 내장 규칙 시드 (2026-11-26).
 *
 * <p>{@code builtin_key} 가 <b>없는 규칙만</b> INSERT 한다 — 관리자가 바꾼 이름·사용 여부·조치·키워드 목록은
 * 재기동해도 보존된다. 새 내장 규칙이 코드에 추가되면 다음 기동에 그 하나만 들어간다. 끝에서 규칙 캐시를 다시 읽는다.
 *
 * <p>2026-11-28: 판정 방식(BUILTIN) 내장 행의 {@code pattern}(표시문)·{@code description} 이 코드 정의와 다르면 맞춘다.
 * 두 필드는 관리자 API 가 내장 규칙에 대해 편집을 막고 있어(BUILTIN_READONLY) 덮어써도 관리자 변경을 잃지 않는다.
 * 키워드형 내장 규칙의 단어 목록(pattern)은 관리자가 편집하므로 건드리지 않는다.
 */
@Component
public class LlmGuardRuleSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LlmGuardRuleSeeder.class);

    private final LlmGuardRuleRepository repo;
    private final LlmGuardService guard;

    public LlmGuardRuleSeeder(LlmGuardRuleRepository repo, LlmGuardService guard) {
        this.repo = repo;
        this.guard = guard;
    }

    @Override
    public void run(ApplicationArguments args) {
        int inserted = 0;
        int synced = 0;
        try {
            for (BuiltinDetectors.Def d : BuiltinDetectors.DEFAULTS) {
                Optional<LlmGuardRule> existing = repo.findByBuiltinKey(d.key);
                if (existing.isPresent()) {
                    if (syncDisplay(existing.get(), d)) {
                        repo.save(existing.get());
                        synced++;
                    }
                    continue;
                }
                LlmGuardRule r = d.toEntity();
                LocalDateTime now = LocalDateTime.now();
                r.setCreatedAt(now);
                r.setCreatedBy("system");
                r.setUpdatedAt(now);
                r.setUpdatedBy("system");
                repo.save(r);
                inserted++;
            }
            logger.info("[LlmGuard] action=seed inserted={} synced={} total={} by=system", inserted, synced, repo.count());
        } catch (RuntimeException e) {
            logger.warn("[LlmGuard] 내장 규칙 시드 실패 — 내장 기본값으로 동작: {}", e.toString());
        } finally {
            guard.reload();
        }
    }

    /** 판정 방식 내장 행의 표시문·설명을 정의에 맞춘다. 바뀌었으면 true. updatedAt/By 는 관리자 변경 기록이라 건드리지 않는다. */
    static boolean syncDisplay(LlmGuardRule r, BuiltinDetectors.Def d) {
        if (!LlmGuardRule.TYPE_BUILTIN.equals(d.matchType) || !LlmGuardRule.TYPE_BUILTIN.equals(r.getMatchType())) return false;
        boolean changed = false;
        if (!Objects.equals(r.getPattern(), d.pattern)) { r.setPattern(d.pattern); changed = true; }
        if (!Objects.equals(r.getDescription(), d.description)) { r.setDescription(d.description); changed = true; }
        return changed;
    }
}

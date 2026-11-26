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

/**
 * LLM Guard 내장 규칙 시드 (2026-11-26).
 *
 * <p>{@code builtin_key} 가 <b>없는 규칙만</b> INSERT 한다 — 관리자가 바꾼 이름·사용 여부·조치·키워드 목록은
 * 재기동해도 보존된다. 새 내장 규칙이 코드에 추가되면 다음 기동에 그 하나만 들어간다. 끝에서 규칙 캐시를 다시 읽는다.
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
        try {
            for (BuiltinDetectors.Def d : BuiltinDetectors.DEFAULTS) {
                if (repo.findByBuiltinKey(d.key).isPresent()) continue;
                LlmGuardRule r = d.toEntity();
                LocalDateTime now = LocalDateTime.now();
                r.setCreatedAt(now);
                r.setCreatedBy("system");
                r.setUpdatedAt(now);
                r.setUpdatedBy("system");
                repo.save(r);
                inserted++;
            }
            logger.info("[LlmGuard] action=seed inserted={} total={} by=system", inserted, repo.count());
        } catch (RuntimeException e) {
            logger.warn("[LlmGuard] 내장 규칙 시드 실패 — 내장 기본값으로 동작: {}", e.toString());
        } finally {
            guard.reload();
        }
    }
}

package com.heapdump.analyzer.service;

import com.heapdump.analyzer.model.entity.LeakFallbackRule;
import com.heapdump.analyzer.model.entity.LeakLibraryRule;
import com.heapdump.analyzer.repository.LeakFallbackRuleRepository;
import com.heapdump.analyzer.repository.LeakLibraryRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Leak 룰셋 로딩/캐싱. 시작 시 1회 + 변경 시 invalidate(). 캐시는 priority 오름차순.
 * Fallback 룰은 Pattern을 미리 컴파일해 RuntimeFallback 으로 노출.
 */
@Service
public class LeakRuleService {

    private static final Logger logger = LoggerFactory.getLogger(LeakRuleService.class);

    private final LeakLibraryRuleRepository libraryRepo;
    private final LeakFallbackRuleRepository fallbackRepo;

    private final AtomicReference<List<LeakLibraryRule>> libraryCache = new AtomicReference<>();
    private final AtomicReference<List<RuntimeFallback>> fallbackCache = new AtomicReference<>();

    public LeakRuleService(LeakLibraryRuleRepository libraryRepo,
                           LeakFallbackRuleRepository fallbackRepo) {
        this.libraryRepo = libraryRepo;
        this.fallbackRepo = fallbackRepo;
    }

    public List<LeakLibraryRule> libraryRules() {
        List<LeakLibraryRule> cached = libraryCache.get();
        if (cached == null) {
            cached = libraryRepo.findByEnabledTrueOrderByPriorityAscIdAsc();
            libraryCache.set(cached);
        }
        return cached;
    }

    public List<RuntimeFallback> fallbackRules() {
        List<RuntimeFallback> cached = fallbackCache.get();
        if (cached == null) {
            List<LeakFallbackRule> rows = fallbackRepo.findByEnabledTrueOrderByPriorityAscIdAsc();
            java.util.ArrayList<RuntimeFallback> out = new java.util.ArrayList<>(rows.size());
            for (LeakFallbackRule r : rows) {
                Pattern p;
                try {
                    p = Pattern.compile(r.getPatternRegex(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                } catch (PatternSyntaxException e) {
                    logger.warn("[LeakRule] Invalid fallback regex id={} pattern='{}'", r.getId(), r.getPatternRegex());
                    continue;
                }
                out.add(new RuntimeFallback(r, p));
            }
            cached = out;
            fallbackCache.set(cached);
        }
        return cached;
    }

    public void invalidate() {
        libraryCache.set(null);
        fallbackCache.set(null);
        logger.info("[LeakRule] cache invalidated");
    }

    public static final class RuntimeFallback {
        public final LeakFallbackRule rule;
        public final Pattern pattern;
        RuntimeFallback(LeakFallbackRule rule, Pattern pattern) {
            this.rule = rule;
            this.pattern = pattern;
        }
    }
}

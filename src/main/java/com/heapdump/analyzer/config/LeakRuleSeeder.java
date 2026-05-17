package com.heapdump.analyzer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heapdump.analyzer.model.entity.LeakFallbackRule;
import com.heapdump.analyzer.model.entity.LeakLibraryRule;
import com.heapdump.analyzer.repository.LeakFallbackRuleRepository;
import com.heapdump.analyzer.repository.LeakLibraryRuleRepository;
import com.heapdump.analyzer.service.LeakRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 두 leak rule 테이블이 비어있으면 classpath 의 JSON 시드(leak-rules/*.json)에서 INSERT.
 * 한번이라도 시드된 후에는 운영자가 DB에서 직접 편집(또는 향후 CRUD UI)으로 관리.
 */
@Component
public class LeakRuleSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LeakRuleSeeder.class);

    private final LeakLibraryRuleRepository libraryRepo;
    private final LeakFallbackRuleRepository fallbackRepo;
    private final LeakRuleService ruleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LeakRuleSeeder(LeakLibraryRuleRepository libraryRepo,
                          LeakFallbackRuleRepository fallbackRepo,
                          LeakRuleService ruleService) {
        this.libraryRepo = libraryRepo;
        this.fallbackRepo = fallbackRepo;
        this.ruleService = ruleService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean seeded = false;
        if (libraryRepo.count() == 0) {
            int n = seedLibrary();
            logger.info("[LeakRuleSeeder] seeded {} library rules", n);
            seeded = true;
        } else {
            logger.info("[LeakRuleSeeder] library rules already present ({}), skipping", libraryRepo.count());
        }
        if (fallbackRepo.count() == 0) {
            int n = seedFallback();
            logger.info("[LeakRuleSeeder] seeded {} fallback rules", n);
            seeded = true;
        } else {
            logger.info("[LeakRuleSeeder] fallback rules already present ({}), skipping", fallbackRepo.count());
        }
        if (seeded) ruleService.invalidate();
    }

    private int seedLibrary() {
        try (InputStream in = new ClassPathResource("leak-rules/library-rules.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<LeakLibraryRule> rows = new ArrayList<>();
            for (JsonNode n : root) {
                LeakLibraryRule r = new LeakLibraryRule();
                r.setPrefix(n.path("prefix").asText());
                r.setLibraryName(n.path("libraryName").asText());
                r.setCategory(n.path("category").asText());
                r.setSeverityHint(n.path("severityHint").isNull() ? null : n.path("severityHint").asText(null));
                r.setExplanationTpl(n.path("explanationTpl").asText());
                r.setAdviceTpl(n.path("adviceTpl").asText());
                r.setEnabled(n.path("enabled").asBoolean(true));
                r.setPriority(n.path("priority").asInt(1000));
                rows.add(r);
            }
            libraryRepo.saveAll(rows);
            return rows.size();
        } catch (Exception e) {
            logger.error("[LeakRuleSeeder] library seed failed", e);
            return 0;
        }
    }

    private int seedFallback() {
        try (InputStream in = new ClassPathResource("leak-rules/fallback-rules.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            List<LeakFallbackRule> rows = new ArrayList<>();
            for (JsonNode n : root) {
                LeakFallbackRule r = new LeakFallbackRule();
                r.setName(n.path("name").asText());
                r.setCategory(n.path("category").asText());
                r.setPatternRegex(n.path("patternRegex").asText());
                r.setExplanationTpl(n.path("explanationTpl").asText());
                r.setAdviceTpl(n.path("adviceTpl").asText());
                r.setSeverityHint(n.path("severityHint").isNull() ? null : n.path("severityHint").asText(null));
                r.setEnabled(n.path("enabled").asBoolean(true));
                r.setPriority(n.path("priority").asInt(5000));
                rows.add(r);
            }
            fallbackRepo.saveAll(rows);
            return rows.size();
        } catch (Exception e) {
            logger.error("[LeakRuleSeeder] fallback seed failed", e);
            return 0;
        }
    }
}

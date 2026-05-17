package com.heapdump.analyzer.config;

import com.heapdump.analyzer.service.LeakRuleService;
import com.heapdump.analyzer.util.LeakSuspectAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 스프링 컨텍스트가 초기화되면 LeakRuleService를 LeakSuspectAdvisor의 정적 참조로 주입한다.
 * MatReportParser가 정적 호출 LeakSuspectAdvisor.analyze()를 사용하므로 빈으로 직접 주입 불가.
 */
@Configuration
public class LeakSuspectAdvisorBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(LeakSuspectAdvisorBootstrap.class);

    private final LeakRuleService ruleService;

    public LeakSuspectAdvisorBootstrap(LeakRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostConstruct
    public void bind() {
        LeakSuspectAdvisor.bindRuleService(ruleService);
        logger.info("[LeakRule] LeakSuspectAdvisor wired with DB rule service (dual-path enabled)");
    }
}

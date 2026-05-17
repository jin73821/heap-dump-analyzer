package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.LeakFallbackRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeakFallbackRuleRepository extends JpaRepository<LeakFallbackRule, Long> {
    List<LeakFallbackRule> findByEnabledTrueOrderByPriorityAscIdAsc();
    List<LeakFallbackRule> findAllByOrderByPriorityAscIdAsc();
}

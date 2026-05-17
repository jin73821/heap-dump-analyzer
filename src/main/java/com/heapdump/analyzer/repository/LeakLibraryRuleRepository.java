package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.LeakLibraryRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeakLibraryRuleRepository extends JpaRepository<LeakLibraryRule, Long> {
    List<LeakLibraryRule> findByEnabledTrueOrderByPriorityAscIdAsc();
    List<LeakLibraryRule> findAllByOrderByPriorityAscIdAsc();
}

package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.LlmGuardRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmGuardRuleRepository extends JpaRepository<LlmGuardRule, Long> {

    List<LlmGuardRule> findAllByOrderBySortOrderAscIdAsc();

    Optional<LlmGuardRule> findByBuiltinKey(String builtinKey);
}

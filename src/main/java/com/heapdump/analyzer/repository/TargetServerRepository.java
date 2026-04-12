package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.TargetServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TargetServerRepository extends JpaRepository<TargetServer, Long> {
    List<TargetServer> findByEnabledTrue();
    List<TargetServer> findByAutoDetectTrueAndEnabledTrue();
    boolean existsByName(String name);
    boolean existsByHost(String host);
}

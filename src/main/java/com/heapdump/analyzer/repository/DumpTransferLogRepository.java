package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.DumpTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DumpTransferLogRepository extends JpaRepository<DumpTransferLog, Long> {
    List<DumpTransferLog> findByServerIdOrderByStartedAtDesc(Long serverId);
    boolean existsByServerIdAndFilename(Long serverId, String filename);
    boolean existsByServerIdAndFilenameAndTransferStatus(Long serverId, String filename, String transferStatus);
    List<DumpTransferLog> findByFilenameAndTransferStatusOrderByCompletedAtDesc(String filename, String transferStatus);
}

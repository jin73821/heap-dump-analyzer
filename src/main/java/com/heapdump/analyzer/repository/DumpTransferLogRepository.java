package com.heapdump.analyzer.repository;

import com.heapdump.analyzer.model.entity.DumpTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DumpTransferLogRepository
        extends JpaRepository<DumpTransferLog, Long>,
                JpaSpecificationExecutor<DumpTransferLog> {
    List<DumpTransferLog> findByServerIdOrderByStartedAtDesc(Long serverId);
    boolean existsByServerIdAndFilename(Long serverId, String filename);
    boolean existsByServerIdAndFilenameAndTransferStatus(Long serverId, String filename, String transferStatus);
    List<DumpTransferLog> findByFilenameAndTransferStatusOrderByCompletedAtDesc(String filename, String transferStatus);

    /** scan transferred 판정 — 동일 서버에서 (원격 원본명, 원격 크기) 매치되는 SUCCESS 로그가 있는지. */
    boolean existsByServerIdAndRemoteFilenameAndFileSizeAndTransferStatus(
            Long serverId, String remoteFilename, Long fileSize, String transferStatus);

    /** scan analyzed 판정용 — 위 조건의 가장 최근 SUCCESS 로그(로컬 저장명 lookup). */
    Optional<DumpTransferLog>
        findFirstByServerIdAndRemoteFilenameAndFileSizeAndTransferStatusOrderByCompletedAtDesc(
            Long serverId, String remoteFilename, Long fileSize, String transferStatus);

    /** 레거시 row 보정: remote_filename이 NULL인 경우 filename으로 1회 채움. */
    @Modifying
    @Transactional
    @Query("UPDATE DumpTransferLog d SET d.remoteFilename = d.filename WHERE d.remoteFilename IS NULL")
    int backfillRemoteFilenameFromLocal();
}

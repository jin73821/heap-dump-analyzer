package com.heapdump.analyzer.service.llmguard;

import com.heapdump.analyzer.repository.LlmGuardLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/** 탐지 이력 1년 보관 — 컷오프 365일과 정책 하한 365일. */
class LlmGuardLogPurgeTest {

    @Test
    @DisplayName("기본 보관 365일 — now-365일 이전 행을 지운다")
    void cutoff365() {
        LlmGuardLogRepository repo = Mockito.mock(LlmGuardLogRepository.class);
        Mockito.when(repo.deleteOlderThan(any())).thenReturn(3);
        LlmGuardLogService s = new LlmGuardLogService(repo);
        s.setRetentionDaysForTest(365);
        LocalDateTime before = LocalDateTime.now();
        assertEquals(3, s.purgeExpired());
        ArgumentCaptor<LocalDateTime> cap = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(repo).deleteOlderThan(cap.capture());
        long days = ChronoUnit.DAYS.between(cap.getValue(), before.plusSeconds(1));
        assertEquals(365, days);
    }

    @Test
    @DisplayName("설정이 365 미만이면 365 로 올린다(정책 하한), 그 이상은 그대로")
    void floorIs365() {
        LlmGuardLogService s = new LlmGuardLogService(Mockito.mock(LlmGuardLogRepository.class));
        s.setRetentionDaysForTest(30);
        assertEquals(365, s.effectiveRetentionDays());
        s.setRetentionDaysForTest(0);
        assertEquals(365, s.effectiveRetentionDays());
        s.setRetentionDaysForTest(730);
        assertEquals(730, s.effectiveRetentionDays());
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 3, 50);
        assertEquals(now.minusDays(730), s.cutoff(now));
    }

    @Test
    @DisplayName("정리는 스케줄 하나뿐 — cron 키와 기본값")
    void scheduled() throws Exception {
        Scheduled sc = LlmGuardLogService.class.getMethod("purgeExpired").getAnnotation(Scheduled.class);
        assertEquals("${llm.guard.log.purge-cron:0 50 3 * * *}", sc.cron());
    }

    @Test
    @DisplayName("기록 실패는 흐름을 막지 않는다")
    void writeFailureIsSwallowed() {
        LlmGuardLogRepository repo = Mockito.mock(LlmGuardLogRepository.class);
        Mockito.when(repo.saveAll(any())).thenThrow(new IllegalStateException("db down"));
        LlmGuardLogService s = new LlmGuardLogService(repo);
        assertFalse(s.write(java.util.List.of(new com.heapdump.analyzer.model.entity.LlmGuardLog())));
    }
}

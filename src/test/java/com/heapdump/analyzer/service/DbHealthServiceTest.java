package com.heapdump.analyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB 헬스 캐시 — 5초 TTL 안에서는 프로브 1회, 다운 구간의 시작 시각은 유지되고 복구되면 비운다 (2026-09-26). */
class DbHealthServiceTest {

    @Test
    @DisplayName("TTL 안 재호출은 커넥션을 다시 잡지 않고, 다운 시작 시각은 연속 다운 동안 유지된다")
    void cachesAndTracksDownSince() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection c = Mockito.mock(Connection.class);
        Mockito.when(ds.getConnection()).thenReturn(c);
        Mockito.when(c.isValid(DbHealthService.VALID_TIMEOUT_SEC)).thenReturn(true);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-26T10:00:00Z"));
        DbHealthService svc = new DbHealthService(ds, now::get);

        assertTrue(svc.snapshot().up());
        assertTrue(svc.isUp());
        Mockito.verify(ds, Mockito.times(1)).getConnection();   // TTL 안 — 캐시

        now.set(now.get().plusMillis(DbHealthService.TTL_MS + 1));
        Mockito.when(ds.getConnection()).thenThrow(new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms.", "08001"));
        DbHealthService.Snapshot down = svc.snapshot();
        assertFalse(down.up());
        assertNotNull(down.error());
        assertTrue(down.error().contains("SQLTransientConnectionException"), down.error());
        assertEquals(now.get(), down.downSince());
        Mockito.verify(ds, Mockito.times(2)).getConnection();

        Instant firstDown = down.downSince();
        now.set(now.get().plusMillis(DbHealthService.TTL_MS + 1));
        DbHealthService.Snapshot stillDown = svc.snapshot();
        assertFalse(stillDown.up());
        assertEquals(firstDown, stillDown.downSince(), "연속 다운이면 시작 시각이 바뀌면 안 된다");

        // 복구
        Mockito.reset(ds);
        Mockito.when(ds.getConnection()).thenReturn(c);
        now.set(now.get().plusMillis(DbHealthService.TTL_MS + 1));
        DbHealthService.Snapshot back = svc.snapshot();
        assertTrue(back.up());
        assertNull(back.downSince());
        assertNull(back.error());
    }

    @Test
    @DisplayName("isValid 가 false 를 돌려주면 커넥션은 잡혔어도 다운이다")
    void invalidConnectionIsDown() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection c = Mockito.mock(Connection.class);
        Mockito.when(ds.getConnection()).thenReturn(c);
        Mockito.when(c.isValid(DbHealthService.VALID_TIMEOUT_SEC)).thenReturn(false);
        DbHealthService svc = new DbHealthService(ds, () -> Instant.parse("2026-09-26T10:00:00Z"));
        DbHealthService.Snapshot s = svc.snapshot();
        assertFalse(s.up());
        assertTrue(s.error().contains("isValid"));
        Mockito.verify(c).close();
    }
}

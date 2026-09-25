package com.heapdump.analyzer.service;

import com.heapdump.analyzer.util.DbOutageDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * DB 상태 판정의 단일 출처 (2026-09-26, docs/DB_OUTAGE_DISPLAY_PLAN.md ①).
 *
 * <p>{@code /api/system/status} 가 배너 폴링마다 부르므로 커넥션을 매번 잡지 않고 {@value #TTL_MS}ms 캐시한다.
 * 프로브는 {@code getConnection()} + {@code isValid(2)} 이고, 장애 중엔 Hikari 의 {@code connection-timeout}(5초)
 * 까지 걸릴 수 있어 동기화로 한 번에 하나만 돈다(스탬피드 방지). {@code downSince} 는 연속 다운 구간의 첫 시각을 유지한다.
 *
 * <p>⚠ 세션 저장소가 DB 라 진짜 장애 때는 요청이 여기까지 오지 못하고 {@code /error} 의 503 이 먼저 나간다 —
 * 그래서 화면은 이 필드와 503 {@code DB_UNAVAILABLE} 둘 다를 본다. 이 서비스는 부분 장애(세션은 되는데 풀이 고갈)와
 * 복구 확인에 쓰인다.
 */
@Service
public class DbHealthService {

    private static final Logger logger = LoggerFactory.getLogger(DbHealthService.class);

    static final long TTL_MS = 5_000;
    static final int VALID_TIMEOUT_SEC = 2;

    /** 판정 스냅샷 — {@code error}/{@code downSince} 는 다운일 때만 값이 있다. */
    public record Snapshot(boolean up, String error, Instant checkedAt, Instant downSince) {}

    private final DataSource dataSource;
    private final Supplier<Instant> clock;
    private volatile Snapshot last;
    private final Object probeLock = new Object();

    @org.springframework.beans.factory.annotation.Autowired   // 생성자가 둘(테스트용 package-private)이라 명시해야 스프링이 고른다
    public DbHealthService(DataSource dataSource) {
        this(dataSource, Instant::now);
    }

    DbHealthService(DataSource dataSource, Supplier<Instant> clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** TTL 안이면 캐시, 아니면 프로브. */
    public Snapshot snapshot() {
        Snapshot s = last;
        Instant now = clock.get();
        if (s != null && now.toEpochMilli() - s.checkedAt().toEpochMilli() < TTL_MS) return s;
        synchronized (probeLock) {
            s = last;
            now = clock.get();
            if (s != null && now.toEpochMilli() - s.checkedAt().toEpochMilli() < TTL_MS) return s;
            Snapshot fresh = probe(now, s);
            last = fresh;
            return fresh;
        }
    }

    public boolean isUp() { return snapshot().up(); }

    private Snapshot probe(Instant now, Snapshot prev) {
        try (Connection c = dataSource.getConnection()) {
            if (c.isValid(VALID_TIMEOUT_SEC)) {
                if (prev != null && !prev.up()) logger.info("[DbHealth] 데이터베이스 연결 복구 (다운 시작 {})", prev.downSince());
                return new Snapshot(true, null, now, null);
            }
            return down(now, prev, "isValid(" + VALID_TIMEOUT_SEC + "s) 실패");
        } catch (Exception e) {
            return down(now, prev, DbOutageDetector.describe(e));
        }
    }

    private Snapshot down(Instant now, Snapshot prev, String error) {
        Instant since = prev != null && !prev.up() && prev.downSince() != null ? prev.downSince() : now;
        if (prev == null || prev.up()) logger.warn("[DbHealth] 데이터베이스 연결 불가 — {}", error);
        else logger.debug("[DbHealth] 여전히 연결 불가 (since {}) — {}", since, error);
        return new Snapshot(false, error, now, since);
    }
}

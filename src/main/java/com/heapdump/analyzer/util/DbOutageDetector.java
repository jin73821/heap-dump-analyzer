package com.heapdump.analyzer.util;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * "DB 에 연결할 수 없다" 를 예외 사슬에서 판정하는 순수 유틸 (2026-09-26, docs/DB_OUTAGE_DISPLAY_PLAN.md ②).
 *
 * <p>세션 저장소가 DB 라 장애 시 요청이 컨트롤러 전 필터에서 죽고 Boot 의 {@code /error} 로 간다 —
 * 거기서 이 판정이 참이면 503 + "데이터베이스 연결 불가" 로 통일한다(종전엔 로그인 리다이렉트·401 로 오인).
 *
 * <p>판정은 두 조건의 AND 다. ① 사슬에 <b>DB 계층</b> 예외가 있다({@link SQLException}, Spring
 * {@code DataAccessException}/{@code TransactionException}, Hibernate/JPA 예외) ② 사슬에 <b>연결성</b> 신호가 있다
 * (SQLState {@code 08xxx} 연결 오류, {@code DataAccessResourceFailureException}·{@code CannotCreateTransactionException}·
 * {@code JDBCConnectionException}·{@code SQLTransientConnectionException}(Hikari 대기 초과) 계열, 또는 그 아래의
 * {@code java.net.*} 소켓 예외). ②의 {@code java.net} 예외만으로는 참이 되지 않는다 — LLM·SSH 등 다른 원격 호출의
 * 타임아웃을 DB 장애로 오판하지 않기 위해서다. 클래스 이름으로 비교하므로 Hibernate 등에 컴파일 의존이 없다.
 */
public final class DbOutageDetector {

    private DbOutageDetector() {}

    private static final int MAX_DEPTH = 24;

    /** DB 계층 예외(있으면 ① 충족) — 클래스 계층 어느 조상이라도 이 이름이면 된다. */
    private static final String[] DB_LAYER = {
            "java.sql.SQLException",
            "org.springframework.dao.DataAccessException",
            "org.springframework.transaction.TransactionException",
            "org.hibernate.HibernateException",
            "jakarta.persistence.PersistenceException",
            "org.springframework.session.SessionRepository"   // (방어) 세션 저장소 래핑 예외가 생기면 여기 추가
    };

    /** 연결성 신호(②) — 이것 자체가 DB 계층이기도 한 것들. */
    private static final String[] CONNECTIVITY_DB = {
            "org.springframework.dao.DataAccessResourceFailureException",
            "org.springframework.dao.RecoverableDataAccessException",
            "org.springframework.jdbc.CannotGetJdbcConnectionException",
            "org.springframework.transaction.CannotCreateTransactionException",
            "org.hibernate.exception.JDBCConnectionException",
            "java.sql.SQLTransientConnectionException",
            "java.sql.SQLNonTransientConnectionException",
            "java.sql.SQLRecoverableException",
            "com.zaxxer.hikari.pool.PoolInitializationException"
    };

    /** 연결성 신호(②) — DB 계층 예외 아래에 있을 때만 인정. */
    private static final String[] CONNECTIVITY_NET = {
            "java.net.ConnectException",
            "java.net.NoRouteToHostException",
            "java.net.UnknownHostException",
            "java.net.SocketTimeoutException",
            "java.net.SocketException",
            "java.io.EOFException"
    };

    public static boolean isDbOutage(Throwable t) {
        boolean dbLayer = false, connectivity = false;
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_DEPTH && seen.put(c, Boolean.TRUE) == null; c = c.getCause(), depth++) {
            if (c instanceof SQLException) {
                dbLayer = true;
                String state = ((SQLException) c).getSQLState();
                if (state != null && state.startsWith("08")) connectivity = true;
            }
            if (hierarchyMatches(c, DB_LAYER)) dbLayer = true;
            if (hierarchyMatches(c, CONNECTIVITY_DB)) { dbLayer = true; connectivity = true; }
            if (hierarchyMatches(c, CONNECTIVITY_NET)) connectivity = true;
        }
        return dbLayer && connectivity;
    }

    /**
     * 사람이 읽을 원인 한 줄 — <b>연결성 신호를 가진 가장 깊은 예외</b>의 클래스 단순명 + 메시지(200자), 없으면 가장 깊은 원인.
     * 최심부를 그대로 쓰면 MariaDB 드라이버가 죽은 커넥션의 {@code setNetworkTimeout} 에서 던지는
     * {@code SQLSyntaxErrorException} 같은 엉뚱한 문구가 나온다(2026-09-26 실측) — 그 위의
     * {@code SQLTransientConnectionException: Connection is not available, request timed out after 5000ms} 가 진짜 원인이다.
     * 비밀값이 섞일 수 있는 URL 은 싣지 않는다.
     */
    public static String describe(Throwable t) {
        if (t == null) return "";
        Throwable deepest = t, marked = null;
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_DEPTH && seen.put(c, Boolean.TRUE) == null; c = c.getCause(), depth++) {
            deepest = c;
            boolean state08 = c instanceof SQLException && ((SQLException) c).getSQLState() != null && ((SQLException) c).getSQLState().startsWith("08");
            if (state08 || hierarchyMatches(c, CONNECTIVITY_DB) || hierarchyMatches(c, CONNECTIVITY_NET)) marked = c;
        }
        Throwable pick = marked != null ? marked : deepest;
        String msg = pick.getMessage() == null ? "" : pick.getMessage().replaceAll("\\s+", " ").trim();
        if (msg.length() > 200) msg = msg.substring(0, 200) + "…";
        return pick.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }

    private static boolean hierarchyMatches(Throwable t, String[] names) {
        for (Class<?> k = t.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String n = k.getName();
            for (String name : names) if (name.equals(n)) return true;
        }
        return false;
    }
}

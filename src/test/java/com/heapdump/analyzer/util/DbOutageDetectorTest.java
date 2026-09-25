package com.heapdump.analyzer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB 연결 불가 판정 — 실제 장애 사슬은 참, 다른 원격 호출 타임아웃·SQL 문법 오류·일반 예외는 거짓 (2026-09-26). */
class DbOutageDetectorTest {

    @Test
    @DisplayName("Hikari 풀 대기 초과·연결 거부·SQLState 08·Spring 자원 실패·트랜잭션 생성 실패는 전부 장애다")
    void realOutageChainsAreDetected() {
        assertTrue(DbOutageDetector.isDbOutage(new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms.")));
        assertTrue(DbOutageDetector.isDbOutage(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLNonTransientConnectionException("Socket fail to connect to host:192.168.56.9, port:3306. Connection refused", "08001"))));
        // 세션 저장소(JdbcIndexedSessionRepository) 경로 — 트랜잭션 생성 실패로 감싸여 온다
        assertTrue(DbOutageDetector.isDbOutage(new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("timeout", "08001", new ConnectException("Connection refused")))));
        // SQLState 08xxx 는 클래스가 무엇이든 연결 오류
        assertTrue(DbOutageDetector.isDbOutage(new RuntimeException("wrap", new SQLException("Communications link failure", "08S01"))));
        assertTrue(DbOutageDetector.isDbOutage(new DataAccessResourceFailureException("db gone")));
        // Hibernate JDBCConnectionException (클래스 이름 비교 — 컴파일 의존 없음)
        assertTrue(DbOutageDetector.isDbOutage(new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
                new SQLException("Connection refused"))));
        // java.net 예외는 DB 계층 예외 아래에 있을 때만
        assertTrue(DbOutageDetector.isDbOutage(new SQLException("Could not connect", new SocketTimeoutException("connect timed out"))));
    }

    @Test
    @DisplayName("LLM·SSH 등 다른 원격 호출의 소켓 예외, SQL 문법·제약 오류, 일반 예외, null 은 장애가 아니다")
    void nonOutagesAreRejected() {
        assertFalse(DbOutageDetector.isDbOutage(null));
        assertFalse(DbOutageDetector.isDbOutage(new IllegalStateException("boom")));
        assertFalse(DbOutageDetector.isDbOutage(new java.io.IOException("LLM 응답 없음", new SocketTimeoutException("Read timed out"))));
        assertFalse(DbOutageDetector.isDbOutage(new ConnectException("Connection refused")));   // DB 계층 없이 소켓만
        assertFalse(DbOutageDetector.isDbOutage(new SQLSyntaxErrorException("You have an error in your SQL syntax", "42000")));
        assertFalse(DbOutageDetector.isDbOutage(new DataIntegrityViolationException("Duplicate entry", new SQLException("Duplicate", "23000"))));
        assertFalse(DbOutageDetector.isDbOutage(new RuntimeException("x", new SQLException("deadlock", "40001"))));
    }

    @Test
    @DisplayName("원인 사슬이 순환해도 끝나고, 설명은 가장 깊은 원인 + 200자 이내")
    void describeAndCycles() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);   // 순환
        assertFalse(DbOutageDetector.isDbOutage(a));
        String d = DbOutageDetector.describe(new CannotGetJdbcConnectionException("outer",
                new SQLNonTransientConnectionException("Socket fail to connect\n  to host", "08001")));
        assertEquals("SQLNonTransientConnectionException: Socket fail to connect to host", d);
        assertTrue(DbOutageDetector.describe(new SQLException("x".repeat(500))).length() < 260);
        // 실측 사슬: Hikari 대기 초과 아래에 드라이버의 엉뚱한 SQLSyntaxErrorException(setNetworkTimeout) — 연결성 신호를 가진 쪽을 고른다
        String real = DbOutageDetector.describe(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms.", "08001",
                        new java.sql.SQLSyntaxErrorException("(conn=85393) Connection.setNetworkTimeout cannot be called on a closed connection"))));
        assertTrue(real.startsWith("SQLTransientConnectionException: HikariPool-1"), real);
        assertEquals("", DbOutageDetector.describe(null));
    }
}

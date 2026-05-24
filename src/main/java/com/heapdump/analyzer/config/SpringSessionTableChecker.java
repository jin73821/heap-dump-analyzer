package com.heapdump.analyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class SpringSessionTableChecker implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SpringSessionTableChecker.class);

    private static final String TABLE_SESSION    = "SPRING_SESSION";
    private static final String TABLE_ATTRIBUTES = "SPRING_SESSION_ATTRIBUTES";
    private static final String SCHEMA_RESOURCE  = "org/springframework/session/jdbc/schema-mysql.sql";

    private final DataSource dataSource;

    public SpringSessionTableChecker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String catalog = conn.getCatalog();              // 현재 DB(예: HEAPDB)로 스코프 한정
            String escape  = meta.getSearchStringEscape();   // LIKE 패턴 escape 문자 (보통 '\\')

            boolean sessionExists   = tableExists(meta, catalog, escape, TABLE_SESSION);
            boolean attributeExists = tableExists(meta, catalog, escape, TABLE_ATTRIBUTES);

            if (sessionExists && attributeExists) {
                log.info("[SpringSession] SPRING_SESSION / SPRING_SESSION_ATTRIBUTES 테이블 확인 완료 (catalog={}).", catalog);
                return;
            }

            // 한쪽이라도 누락 → 전체 스크립트 자동 실행 (continueOnError 로 기존 테이블 1050 흘려보냄)
            log.warn("[SpringSession] 테이블 누락 감지 — 자동 생성을 시도합니다. "
                    + "(SPRING_SESSION={}, SPRING_SESSION_ATTRIBUTES={}, catalog={})",
                    sessionExists, attributeExists, catalog);

            bootstrapSchema(conn);

            log.info("[SpringSession] 스키마 자동 생성 완료 (catalog={}).", catalog);
        } catch (SQLException | ScriptException e) {
            log.error("[SpringSession] 스키마 검증/부트스트랩 실패. "
                    + "수동으로 {} 을(를) 실행하거나 DB 권한(CREATE TABLE/INDEX)을 확인하세요: {}",
                    SCHEMA_RESOURCE, e.getMessage(), e);
        }
    }

    /**
     * JDBC LIKE 패턴 wildcard ('_', '%') 를 escape 하여 정확한 테이블명만 매칭한다.
     * catalog 를 명시해 현재 DB 외 다른 스키마의 동명 테이블이 false positive 로 잡히지 않도록 한다.
     */
    private boolean tableExists(DatabaseMetaData meta, String catalog, String escape, String tableName)
            throws SQLException {
        String pattern = tableName
                .replace(escape, escape + escape)   // escape 문자 자체를 먼저 이스케이프
                .replace("_", escape + "_")
                .replace("%", escape + "%");
        try (ResultSet rs = meta.getTables(catalog, null, pattern, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * Spring Session JDBC 의 schema-mysql.sql 을 classpath 에서 로드해 실행한다.
     * continueOnError=true: 부분 누락 케이스에서 기존 테이블의 CREATE TABLE 이 1050 오류로
     * 실패해도 후속 누락 테이블 CREATE 는 계속 진행한다.
     */
    private void bootstrapSchema(Connection conn) {
        Resource script = new ClassPathResource(SCHEMA_RESOURCE);
        ScriptUtils.executeSqlScript(
                conn,
                new EncodedResource(script, StandardCharsets.UTF_8),
                true,    // continueOnError
                true,    // ignoreFailedDrops
                ScriptUtils.DEFAULT_COMMENT_PREFIX,
                ScriptUtils.DEFAULT_STATEMENT_SEPARATOR,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER
        );
    }
}

package com.heapdump.analyzer.config;

import com.heapdump.analyzer.util.AesEncryptor;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * DataSource 설정 — ENC(...) 형식의 암호화된 비밀번호를 자동 복호화.
 *
 * application.properties에서:
 *   spring.datasource.password=ENC(암호화된문자열)
 * 형식으로 작성하면 AES 복호화 후 실제 DB 연결에 사용.
 */
@Configuration
public class DataSourceConfig {

    /**
     * Hikari 대기·검증 타임아웃 코드 기본값(ms) — DB 장애 시 요청이 30초(Hikari 기본)씩 멈추지 않게 한다.
     * {@code application.properties} 는 .gitignore 대상이라 새 환경에 {@code spring.datasource.hikari.*} 키가 없을 수 있어
     * 기본값을 코드에 둔다. 키가 있으면 {@code @ConfigurationProperties} 바인딩(빈 생성 직후)이 이 값을 덮어쓴다.
     */
    static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    static final long DEFAULT_VALIDATION_TIMEOUT_MS = 2_000;

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * ⚠ 직접 만든 DataSource 빈에는 Boot 의 {@code spring.datasource.hikari.*} 가 바인딩되지 않는다 — 그 바인딩은
     * Boot 자동설정의 {@code @ConfigurationProperties("spring.datasource.hikari") HikariDataSource} 빈에만 붙는다.
     * 그래서 2026-09-26 이전엔 {@code connection-timeout=5000} 을 적어도 기본 30초 그대로였다(DB 장애 실측: 요청당 30초,
     * 로그인 60초). 같은 방식으로 반환형을 {@link HikariDataSource} 로 두고 프리픽스를 붙여 바인딩되게 한다.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // password가 ENC(...) 형식이면 복호화
        String rawPassword = properties.getPassword();
        if (rawPassword != null && rawPassword.startsWith("ENC(") && rawPassword.endsWith(")")) {
            // DB 는 필수 의존이라 fail-fast 를 유지한다. 단 복호화 결과가 손상된 경우
            // 그대로 넘기면 HikariCP 인증 실패로만 보여 원인을 알 수 없으므로 여기서 차단한다.
            AesEncryptor.Decrypted d = AesEncryptor.decryptIfEncryptedChecked(rawPassword);
            if (!d.healthy()) {
                throw new IllegalStateException(
                        "DB 비밀번호(spring.datasource.password) 복호화 결과가 손상되었습니다 — "
                        + d.issue() + ". `bash heap_enc.sh \"<비밀번호>\"` 로 재생성해 "
                        + "application.properties 를 갱신한 뒤 기동하세요.");
            }
            properties.setPassword(d.value());
        }
        HikariDataSource ds = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT_MS);
        ds.setValidationTimeout(DEFAULT_VALIDATION_TIMEOUT_MS);
        return ds;
    }
}

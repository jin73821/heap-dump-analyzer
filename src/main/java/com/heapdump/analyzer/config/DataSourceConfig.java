package com.heapdump.analyzer.config;

import com.heapdump.analyzer.util.AesEncryptor;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * DataSource 설정 — ENC(...) 형식의 암호화된 비밀번호를 자동 복호화.
 *
 * application.properties에서:
 *   spring.datasource.password=ENC(암호화된문자열)
 * 형식으로 작성하면 AES 복호화 후 실제 DB 연결에 사용.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        // password가 ENC(...) 형식이면 복호화
        String rawPassword = properties.getPassword();
        if (rawPassword != null && rawPassword.startsWith("ENC(") && rawPassword.endsWith(")")) {
            String decrypted = AesEncryptor.decryptIfEncrypted(rawPassword);
            properties.setPassword(decrypted);
        }
        return properties.initializeDataSourceBuilder().build();
    }
}

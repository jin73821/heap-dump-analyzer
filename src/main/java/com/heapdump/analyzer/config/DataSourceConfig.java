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
        return properties.initializeDataSourceBuilder().build();
    }
}

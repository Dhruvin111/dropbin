package com.textbin.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Intelligent DataSource configuration supporting local Docker development,
 * standard JDBC properties, and cloud platforms like Railway (which provide
 * DATABASE_URL in postgresql:// or postgres:// format).
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${spring.datasource.url:}")
    private String configuredUrl;

    @Value("${spring.datasource.username:}")
    private String configuredUsername;

    @Value("${spring.datasource.password:}")
    private String configuredPassword;

    @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int maxPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:2}")
    private int minIdle;

    @Value("${spring.datasource.hikari.connection-timeout:20000}")
    private long connectionTimeout;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);

        // Check for DATABASE_URL environment variable (common in Railway, Heroku, Render)
        String rawDbUrl = System.getenv("DATABASE_URL");
        if (rawDbUrl == null || rawDbUrl.isBlank()) {
            rawDbUrl = System.getenv("SPRING_DATASOURCE_URL");
        }
        if (rawDbUrl == null || rawDbUrl.isBlank()) {
            rawDbUrl = configuredUrl;
        }

        // Case 1: DATABASE_URL is in postgresql:// or postgres:// URI format
        if (rawDbUrl != null && (rawDbUrl.startsWith("postgres://") || rawDbUrl.startsWith("postgresql://"))) {
            try {
                log.info("Converting cloud DATABASE_URL into JDBC format...");
                URI uri = new URI(rawDbUrl);
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getPath();
                if (path == null || path.isBlank() || path.equals("/")) {
                    path = "/railway";
                }

                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
                String query = uri.getQuery();
                if (query != null && !query.isBlank()) {
                    jdbcUrl += "?" + query;
                }

                String username = configuredUsername;
                String password = configuredPassword;
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = parts[0];
                    password = parts[1];
                } else if (userInfo != null && !userInfo.isBlank()) {
                    username = userInfo;
                }

                config.setJdbcUrl(jdbcUrl);
                config.setUsername(username);
                config.setPassword(password);

                log.info("Configured DataSource for cloud database at {}:{}", host, port);
                return new HikariDataSource(config);
            } catch (Exception e) {
                log.warn("Could not parse DATABASE_URL as URI ({}). Falling back to property defaults.", e.getMessage());
            }
        }

        // Case 2: Standard JDBC URL (local Docker or explicitly configured)
        config.setJdbcUrl(rawDbUrl != null && !rawDbUrl.isBlank() ? rawDbUrl : configuredUrl);
        config.setUsername(configuredUsername);
        config.setPassword(configuredPassword);

        log.info("Configured DataSource using standard JDBC URL: {}", config.getJdbcUrl());
        return new HikariDataSource(config);
    }
}


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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent DataSource configuration supporting local Docker development,
 * standard JDBC properties, and cloud platforms like Railway and Neon (which provide
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

        // Check for DATABASE_URL environment variables from cloud platforms (Railway, Neon, Render)
        String rawDbUrl = System.getenv("DATABASE_URL");
        if (rawDbUrl == null || rawDbUrl.isBlank()) {
            rawDbUrl = System.getenv("SPRING_DATASOURCE_URL");
        }
        if (rawDbUrl == null || rawDbUrl.isBlank()) {
            rawDbUrl = System.getenv("DATABASE_PUBLIC_URL");
        }
        if (rawDbUrl == null || rawDbUrl.isBlank()) {
            rawDbUrl = configuredUrl;
        }

        // Case 1: Cloud URL in postgres:// or postgresql:// format
        if (rawDbUrl != null && (rawDbUrl.startsWith("postgres://") || rawDbUrl.startsWith("postgresql://"))) {
            return createCloudDataSource(rawDbUrl, config);
        }

        // Case 2: Standard JDBC URL (local Docker or explicitly configured)
        config.setJdbcUrl(rawDbUrl != null && !rawDbUrl.isBlank() ? rawDbUrl : configuredUrl);
        config.setUsername(configuredUsername);
        config.setPassword(configuredPassword);

        if (config.getJdbcUrl() != null && config.getJdbcUrl().contains("localhost:5432")) {
            log.warn("===============================================================================");
            log.warn("WARNING: No cloud DATABASE_URL was found! Connecting to localhost:5432.");
            log.warn("If deploying on Railway: Add DATABASE_URL to your Web Service Variables tab");
            log.warn("by clicking '+ New Variable' -> 'Add Reference' -> Postgres -> DATABASE_URL.");
            log.warn("If deploying with Neon: Add variable DATABASE_URL=<your-neon-connection-string>.");
            log.warn("===============================================================================");
        }

        log.info("Configured DataSource using JDBC URL: {}", config.getJdbcUrl());
        return new HikariDataSource(config);
    }

    private DataSource createCloudDataSource(String rawDbUrl, HikariConfig config) {
        String cleanUrl = rawDbUrl.trim();

        // 1. First attempt: java.net.URI
        try {
            log.info("Converting cloud DATABASE_URL into JDBC format...");
            URI uri = new URI(cleanUrl);
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
        } catch (Exception ex) {
            log.warn("Standard URI parsing failed ({}), attempting regex parsing fallback...", ex.getMessage());
        }

        // 2. Second attempt: Robust regex parser (handles special characters in passwords)
        Pattern pattern = Pattern.compile("^(?:postgres|postgresql)://(?:([^:]+)(?::([^@]*))?@)?([^:/]+)(?::(\\d+))?(?:/([^?]*))?(?:\\?(.*))?$");
        Matcher matcher = pattern.matcher(cleanUrl);
        if (matcher.matches()) {
            String user = matcher.group(1);
            String pass = matcher.group(2);
            String host = matcher.group(3);
            String portStr = matcher.group(4);
            String dbName = matcher.group(5);
            String query = matcher.group(6);

            int port = (portStr != null && !portStr.isBlank()) ? Integer.parseInt(portStr) : 5432;
            String path = (dbName != null && !dbName.isBlank()) ? ("/" + dbName) : "/railway";
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
            if (query != null && !query.isBlank()) {
                jdbcUrl += "?" + query;
            }

            config.setJdbcUrl(jdbcUrl);
            config.setUsername(user != null ? user : configuredUsername);
            config.setPassword(pass != null ? pass : configuredPassword);

            log.info("Configured DataSource via regex parser for cloud database at {}:{}", host, port);
            return new HikariDataSource(config);
        }

        throw new IllegalArgumentException("Unable to parse cloud database URL: " + cleanUrl);
    }
}

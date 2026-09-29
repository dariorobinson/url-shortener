package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * AC6: tests activate exactly the {@code test} profile and the datasource is the
 * Testcontainers-backed JDBC URL, never a developer's local PostgreSQL on
 * {@code localhost:5432}.
 */
class TestProfileDatasourceIT extends IntegrationTestBase {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgreSQLContainer<?> postgresContainer;

    @Test
    void shouldActivateOnlyTheTestProfile() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
    }

    @Test
    void shouldUseTheTestcontainersJdbcUrlNotLocalhost() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        String jdbcUrl = ((HikariDataSource) dataSource).getJdbcUrl();

        assertThat(jdbcUrl).isEqualTo(postgresContainer.getJdbcUrl());
        assertThat(jdbcUrl).doesNotContain("localhost:5432");
    }
}

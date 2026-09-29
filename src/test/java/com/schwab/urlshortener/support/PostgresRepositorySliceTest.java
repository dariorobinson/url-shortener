package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@RepositoryTest
class PostgresRepositorySliceTest {

    @Test
    void shouldRunRepositorySliceAgainstTestcontainersPostgres(@Autowired DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        String serverVersion = jdbcTemplate.queryForObject("SHOW server_version", String.class);

        assertThat(serverVersion).startsWith("18.");
    }
}

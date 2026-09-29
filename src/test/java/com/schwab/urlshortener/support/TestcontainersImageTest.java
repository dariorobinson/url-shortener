package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * AC12 (D41): the Testcontainers PostgreSQL image must be the same image docker-compose.yml uses,
 * so behaviour observed against Testcontainers matches what runs under Docker Compose.
 */
class TestcontainersImageTest {

    @Test
    void shouldUseTheSamePostgresImageAsDockerCompose() throws IOException {
        File composeFile = new File("docker-compose.yml");
        assertThat(composeFile).exists();

        Map<String, Object> compose;
        try (var in = Files.newInputStream(composeFile.toPath())) {
            compose = new Yaml().load(in);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        @SuppressWarnings("unchecked")
        Map<String, Object> postgres = (Map<String, Object>) services.get("postgres");
        String composeImage = (String) postgres.get("image");

        assertThat(composeImage).isEqualTo("postgres:18.6-alpine");
        assertThat(TestcontainersConfiguration.POSTGRES_IMAGE.asCanonicalNameString()).isEqualTo(composeImage);
    }
}

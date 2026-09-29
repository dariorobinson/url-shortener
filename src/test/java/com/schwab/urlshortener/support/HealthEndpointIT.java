package com.schwab.urlshortener.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * AC1 (context-loads smoke, full stack) and AC3: the Spring context starts against a real
 * Testcontainers PostgreSQL instance, and {@code GET /actuator/health} returns exactly
 * {@code {"status":"UP"}}.
 */
class HealthEndpointIT extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldReturnUpStatusFromHealthEndpoint() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("UP");
    }
}

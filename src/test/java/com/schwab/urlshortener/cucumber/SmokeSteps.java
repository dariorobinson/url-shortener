package com.schwab.urlshortener.cucumber;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.support.SharedContainerProbe;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Step definitions for {@code smoke.feature}. Not a {@code @Component}: cucumber-spring supplies
 * its own object factory, and step classes must not be Spring-managed beans in their own right.
 */
public class SmokeSteps {

    // Injected through the same Spring context as every *IT test, via CucumberSpringConfiguration
    // extending IntegrationTestBase (AC8).
    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PostgreSQLContainer<?> postgresContainer;

    // The context's own Jackson ObjectMapper, so response parsing here uses the same
    // (de)serialization configuration as the running application rather than a bare default one.
    @Autowired
    private ObjectMapper objectMapper;

    private ResponseEntity<String> healthResponse;

    @When("I check the application's health")
    public void iCheckTheApplicationsHealth() {
        healthResponse = restTemplate.getForEntity("/actuator/health", String.class);
    }

    @Then("the health check responds with status {int}")
    public void theHealthCheckRespondsWithStatus(int expectedStatus) {
        assertThat(healthResponse.getStatusCode().value()).isEqualTo(expectedStatus);
    }

    @Then("the health status is exactly {string}")
    public void theHealthStatusIsExactly(String expectedStatus) throws Exception {
        var json = objectMapper.readTree(healthResponse.getBody());
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get("status").asText()).isEqualTo(expectedStatus);
    }

    @When("I record the identity of the test database container from a Cucumber step")
    public void iRecordTheIdentityOfTheTestDatabaseContainer() {
        SharedContainerProbe.recordOrVerify(postgresContainer.getContainerId());
    }

    @Then("it is the same test database container that the JUnit integration tests use")
    public void itIsTheSameTestDatabaseContainer() {
        // Distinct from the When step's recordOrVerify call: this reads back whatever ID is
        // currently recorded (set either by this scenario's own When step, or earlier by
        // SharedTestEnvironmentIT if that *IT ran first in this forked JVM) and checks it against
        // this step's own container bean. It fails if nothing was ever recorded (probe wiring
        // broken) or if a second, different container/context exists (AC8 violated), regardless
        // of which of the two runs first.
        assertThat(SharedContainerProbe.getRecordedContainerId())
                .as("container ID recorded by the shared probe")
                .isNotNull()
                .isEqualTo(postgresContainer.getContainerId());
    }
}

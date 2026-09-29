package com.schwab.urlshortener.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * AC8: this {@code *IT} test and the Cucumber smoke scenario ("The test database container is
 * shared with other integration tests") both inject the {@code PostgreSQLContainer} bean and
 * record its ID with {@link SharedContainerProbe}. Surefire/Failsafe default to
 * {@code forkCount=1, reuseForks=true}, so Jupiter and the Cucumber suite engine run in the same
 * forked JVM; whichever runs second verifies the ID recorded by whichever ran first. The check
 * holds regardless of execution order, because a second Spring context (from a subclass that
 * varies the context) would own a second container bean with a different ID and fail the
 * assertion.
 */
class SharedTestEnvironmentIT extends IntegrationTestBase {

    @Autowired
    private PostgreSQLContainer<?> postgresContainer;

    @Test
    void shouldShareTheSameContainerAsCucumberSteps() {
        SharedContainerProbe.recordOrVerify(postgresContainer.getContainerId());
    }
}

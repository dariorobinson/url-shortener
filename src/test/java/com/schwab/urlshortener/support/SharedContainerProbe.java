package com.schwab.urlshortener.support;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only helper that proves AC8: a single Testcontainers PostgreSQL container is shared
 * between {@code *IT} tests (run by JUnit Jupiter) and Cucumber step definitions (run by the
 * Cucumber JUnit Platform engine), because both extend {@link IntegrationTestBase} and therefore
 * resolve to the same cached Spring context within one forked Failsafe JVM.
 *
 * <p>Surefire/Failsafe default to {@code forkCount=1, reuseForks=true}, so Jupiter and the suite
 * engine run in one JVM. Whichever caller runs first records the container ID; every later caller
 * (regardless of run order) must observe the exact same ID. A second Spring context would own a
 * second container bean with a different ID, and the assertion below would fail.
 */
public final class SharedContainerProbe {

    private static final AtomicReference<String> RECORDED_CONTAINER_ID = new AtomicReference<>();

    private SharedContainerProbe() {
    }

    /**
     * Records the given container ID if none has been recorded yet in this JVM, otherwise
     * asserts that it equals the previously recorded ID.
     *
     * @throws AssertionError if a different container ID was already recorded
     */
    public static synchronized void recordOrVerify(String containerId) {
        String previous = RECORDED_CONTAINER_ID.compareAndExchange(null, containerId);
        if (previous != null && !previous.equals(containerId)) {
            throw new AssertionError(
                    "Expected a single shared Testcontainers container, but observed a second "
                            + "container ID. This means *IT tests and Cucumber steps did not share "
                            + "one Spring context (AC8 violated). Previously recorded: " + previous
                            + ", now observed: " + containerId);
        }
    }

    /**
     * Returns whichever container ID has been recorded so far in this JVM (by an {@code *IT} test
     * or a Cucumber step, whichever ran first), or {@code null} if nothing has recorded yet.
     *
     * <p>Callers use this to make an independent assertion against the shared state, rather than
     * calling {@link #recordOrVerify(String)} a second time with a value that is already known to
     * be identical within the same test/scenario.
     */
    public static synchronized String getRecordedContainerId() {
        return RECORDED_CONTAINER_ID.get();
    }
}

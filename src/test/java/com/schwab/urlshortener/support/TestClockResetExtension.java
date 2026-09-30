package com.schwab.urlshortener.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Resets the {@link TestClock} to real time before and after every test, so a test that fixes the clock and
 * fails midway cannot leak fixed time into another. Registered on {@link IntegrationTestBase}; a JUnit extension
 * is not part of Spring's context cache key.
 */
public final class TestClockResetExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        reset(context);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        reset(context);
    }

    private static void reset(ExtensionContext context) {
        SpringExtension.getApplicationContext(context).getBean(TestClock.class).reset();
    }
}

package com.schwab.urlshortener.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Test-only Clock: delegates to the production clock bean unless a test fixes the instant. Thread-safe, so a
 * Tomcat request thread sees the instant the test thread set. Reset after every test and scenario.
 */
@RequiredArgsConstructor
public final class TestClock extends Clock {

    @NonNull
    private final Clock delegate;
    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public void setInstant(Instant instant) {
        fixed.set(Objects.requireNonNull(instant));
    }

    public void advance(Duration by) {
        fixed.updateAndGet(i -> Objects.requireNonNull(i, "not fixed").plus(by));
    }

    /** Restores delegation to the production clock. */
    public void reset() {
        fixed.set(null);
    }

    public boolean isFixed() {
        return fixed.get() != null;
    }

    @Override
    public Instant instant() {
        Instant f = fixed.get();
        return f != null ? f : delegate.instant();
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("not used by the app");
    }
}

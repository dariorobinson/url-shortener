package com.schwab.urlshortener.support;

import com.schwab.urlshortener.util.shortcode.ShortCodeGenerator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link ShortCodeGenerator} that lets a test force the codes the create endpoint sees
 * (AC6 collisions, AC7 exhaustion, AC15 reserved words). Without queued codes it delegates to the
 * production generator, so tests that do not care behave exactly as in production.
 *
 * <p>Thread-safe: request threads call {@link #generate()} while the test thread queues and
 * resets. Serial execution is assumed (junit-platform.properties enables no parallel mode). If
 * parallel execution is ever enabled, every test using this seam must hold
 * {@code @ResourceLock("shortCodeGenerator")}, or the seam must become per-thread.
 */
public final class ScriptedShortCodeGenerator implements ShortCodeGenerator {

    private final ShortCodeGenerator delegate;
    private final ConcurrentLinkedQueue<String> queued = new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedShortCodeGenerator(ShortCodeGenerator delegate) {
        this.delegate = delegate;
    }

    /** The next generate() calls return these codes, in order; afterwards the real generator is used again. */
    public void willReturn(String... codes) {
        queued.addAll(List.of(codes));
    }

    /** Number of generate() calls since the last reset. */
    public int calls() {
        return calls.get();
    }

    /** Codes still queued (not yet consumed). */
    public int pending() {
        return queued.size();
    }

    public void reset() {
        queued.clear();
        calls.set(0);
    }

    @Override
    public String generate() {
        calls.incrementAndGet();
        String next = queued.poll();
        return next != null ? next : delegate.generate();
    }
}

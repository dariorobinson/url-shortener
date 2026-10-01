package com.schwab.urlshortener.util.analytics;

import java.time.Instant;

/**
 * Records one counted click (FR-5): GET only (D9), on a link resolved as ACTIVE. Implementations record atomically
 * or not at all, need no caller transaction, and may throw any RuntimeException: the caller fails open (D12, D93).
 * A future broker-backed implementation replaces this bean, not the caller.
 */
public interface ClickRecorder {

    /**
     * Records one click for the given link at the given instant.
     *
     * @param shortUrlId the primary key of the link that was resolved as ACTIVE
     * @param clickedAt  the click time, taken from the injected {@code Clock}
     * @throws RuntimeException on any failure; the caller fails open (D12, D93)
     */
    void record(long shortUrlId, Instant clickedAt);
}

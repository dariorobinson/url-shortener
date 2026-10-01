package com.schwab.urlshortener.model;

import java.time.Instant;

/**
 * A PATCH as the service sees it (D114). {@code active} is null when not requested. {@code expiryPresent} says
 * whether the expiry is to change at all; when it is, a null {@code expiresAt} clears it.
 */
public record UpdateShortUrlCommand(Boolean active, boolean expiryPresent, Instant expiresAt) {

    public static UpdateShortUrlCommand active(boolean active) {
        return new UpdateShortUrlCommand(active, false, null);
    }
}

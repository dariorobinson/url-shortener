package com.schwab.urlshortener.service.exception;

/**
 * An {@code expiresAt} that is not in the future or is beyond the configured horizon (D124, D127). It never carries
 * the submitted value: only the rule text, which is fixed server text built from configuration, so the rejected value
 * can never reach a response or a log.
 */
public class InvalidExpirationException extends RuntimeException {

    private final String rule;

    public InvalidExpirationException(String rule) {
        super("expiresAt rejected");
        this.rule = rule;
    }

    /** The rule the value broke, for the {@code errors} extension. */
    public String rule() {
        return rule;
    }
}

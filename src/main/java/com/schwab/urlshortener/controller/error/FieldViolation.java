package com.schwab.urlshortener.controller.error;

/**
 * One entry of the {@code errors} extension (D56): the JSON property that failed and the literal rule
 * text. It never carries the rejected value.
 */
public record FieldViolation(String field, String message) {
}

package com.schwab.urlshortener.config;

import com.schwab.urlshortener.util.validation.HttpUris;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;

/** The value must be an absolute http/https URL with a host, no userinfo, no query and no fragment (D28, D33). */
@Documented
@Constraint(validatedBy = HttpBaseUrl.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface HttpBaseUrl {

    String message() default "must be an absolute http or https URL with a host, and no query or fragment";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<HttpBaseUrl, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            // null/blank is reported by @NotBlank
            if (value == null || value.isBlank()) {
                return true;
            }
            URI uri = HttpUris.parseHttpUri(value);
            // An empty query "?" gives getRawQuery() == "" and an empty fragment "#" gives
            // getRawFragment() == "" (verified), so a null check rejects both. Only the base URL is
            // restricted this way; submitted URLs may carry queries and fragments (D11).
            return uri != null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        }
    }
}

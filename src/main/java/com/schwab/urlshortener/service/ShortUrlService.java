package com.schwab.urlshortener.service;

import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.domain.ShortUrl;
import com.schwab.urlshortener.domain.ShortUrlStatus;
import com.schwab.urlshortener.repository.PostgresServerErrors;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.shortcode.ShortCodeFormat;
import com.schwab.urlshortener.shortcode.ShortCodeGenerator;
import com.schwab.urlshortener.validation.AliasPolicy;
import com.schwab.urlshortener.validation.HttpUris;
import com.schwab.urlshortener.validation.UrlValidator;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates short URLs (FR-1, FR-3, FR-10) and reads them for their owner or an ADMIN (FR-12, D4, D13).
 *
 * <p><b>Never annotate {@code create}, this class or its caller with {@code @Transactional}.</b> Each
 * insert attempt runs in its own {@code REQUIRES_NEW} transaction, because PostgreSQL aborts a
 * transaction after a failed statement, so a retry must use a fresh one. The
 * {@link DataIntegrityViolationException} is caught outside the template, after it has rolled back.
 * The template is built here and deliberately not published as a bean, which would replace Boot's
 * default {@code transactionTemplate}.
 *
 * <p>Only a violation of {@code uk_short_url_short_code} means "code taken". Any other constraint
 * violation is rethrown and becomes a 500, never a retry or a 409. The database unique constraint is
 * the final guarantee under concurrency; there is no existence pre-check.
 *
 * <p>{@code get} runs in a read-only {@code TransactionTemplate} built in the constructor, never under
 * {@code @Transactional}: that annotation stays forbidden on every method of this class.
 */
@Slf4j
@Service
public class ShortUrlService {

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator generator;
    private final UrlValidator urlValidator;
    private final AliasPolicy aliasPolicy;
    private final Clock clock;
    private final int maxAttempts;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate readOnly;

    public ShortUrlService(ShortUrlRepository repository, ShortCodeGenerator generator, UrlValidator urlValidator,
            AliasPolicy aliasPolicy, Clock clock, ShortCodeProperties codeProperties,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.generator = generator;
        this.urlValidator = urlValidator;
        this.aliasPolicy = aliasPolicy;
        this.clock = clock;
        this.maxAttempts = codeProperties.maxAttempts();
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @return the created short URL
     * @throws InvalidUrlException if the URL fails {@link UrlValidator} (checked first, D63)
     * @throws InvalidAliasException if a submitted alias fails {@link AliasPolicy}
     * @throws AliasAlreadyExistsException if the alias is already a short code in any status
     * @throws ShortCodeUnavailableException if every generation attempt collided or was rejected
     * @throws DataIntegrityViolationException for any violation other than {@code uk_short_url_short_code}
     */
    public ShortUrlView create(CreateShortUrlCommand command) {
        if (!urlValidator.isValid(command.originalUrl())) {
            log.debug("Create rejected: errorCode={}", "INVALID_URL");
            throw new InvalidUrlException();
        }
        Instant now = clock.instant();
        if (command.alias() != null) {
            return createWithAlias(command, now);
        }
        return createWithGeneratedCode(command, now);
    }

    /**
     * @return the short URL, if the caller may see it
     * @throws ShortUrlNotFoundException if the code is malformed (D72) or unknown, the link is DELETED (even for
     *         ADMIN, D13), or the caller is neither its creator nor ADMIN (D4); the four cases are
     *         indistinguishable
     */
    public ShortUrlView get(String code, Caller caller) {
        return readOnly.execute(status -> ShortUrlView.from(loadVisible(code, caller)));
    }

    /** D4, D6, D13, D72. Must run inside a transaction. The order of the checks is part of the contract. */
    private ShortUrl loadVisible(String code, Caller caller) {
        if (!ShortCodeFormat.isWellFormed(code)) {
            // D72: cannot exist, so no DB call. The value is arbitrary client text and is never logged.
            log.debug("Short URL not visible: reason=MALFORMED");
            throw new ShortUrlNotFoundException();
        }
        ShortUrl url = repository.findByShortCode(code).orElse(null);   // case-sensitive (D6)
        String reason = null;
        if (url == null) {
            reason = "NOT_FOUND";
        } else if (url.getStatus() == ShortUrlStatus.DELETED) {
            reason = "DELETED";                                          // D13: before the ADMIN shortcut
        } else if (!caller.admin() && !url.getCreatedBy().equals(caller.username())) {
            reason = "NOT_OWNER";                                        // D4: exact equals, no case folding
        }
        if (reason != null) {
            log.debug("Short URL not visible: code={} reason={}", code, reason);
            throw new ShortUrlNotFoundException();
        }
        return url;
    }

    private ShortUrlView createWithAlias(CreateShortUrlCommand command, Instant now) {
        String alias = command.alias();
        if (!aliasPolicy.isValid(alias)) {
            log.debug("Create rejected: errorCode={}", "INVALID_ALIAS");
            throw new InvalidAliasException();
        }
        try {
            return created(insert(alias, command, true, now));
        } catch (DataIntegrityViolationException e) {
            if (isShortCodeConflict(e)) {
                log.info("Alias conflict: code={}", alias);
                throw new AliasAlreadyExistsException(alias, e);
            }
            throw e;
        }
    }

    private ShortUrlView createWithGeneratedCode(CreateShortUrlCommand command, Instant now) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = generator.generate();
            if (!aliasPolicy.isValid(code)) {
                // D29, D48: a reserved word counts as a collision and consumes an attempt, with no DB round trip.
                log.debug("Generated code rejected by alias policy: attempt={} maxAttempts={}", attempt, maxAttempts);
                continue;
            }
            try {
                return created(insert(code, command, false, now));
            } catch (DataIntegrityViolationException e) {
                if (!isShortCodeConflict(e)) {
                    throw e;
                }
                log.info("Generated code collision, retrying: attempt={} maxAttempts={} code={}",
                        attempt, maxAttempts, code);
            }
        }
        log.warn("Short code allocation exhausted: maxAttempts={}", maxAttempts);
        throw new ShortCodeUnavailableException(maxAttempts);
    }

    /** One attempt, in its own transaction, on a fresh entity. */
    private ShortUrl insert(String code, CreateShortUrlCommand command, boolean customAlias, Instant now) {
        return requiresNew.execute(status -> repository.saveAndFlush(
                ShortUrl.create(code, command.originalUrl(), customAlias, command.createdBy(), now)));
    }

    private ShortUrlView created(ShortUrl saved) {
        log.info("Short URL created: id={} code={} customAlias={} host={}", saved.getId(), saved.getShortCode(),
                saved.isCustomAlias(), hostOf(saved.getOriginalUrl()));
        return ShortUrlView.from(saved);
    }

    private static boolean isShortCodeConflict(DataIntegrityViolationException e) {
        return PostgresServerErrors.isUniqueViolation(e, ShortUrlRepository.SHORT_CODE_UNIQUE_CONSTRAINT);
    }

    /** The host only, never the URL: the path and query may hold tokens. */
    private static String hostOf(String url) {
        URI uri = HttpUris.parseHttpUri(url);
        return uri == null ? "unknown" : uri.getHost();
    }
}

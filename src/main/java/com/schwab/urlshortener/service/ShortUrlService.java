package com.schwab.urlshortener.service;

import com.schwab.urlshortener.config.ShortCodeProperties;
import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.entity.ShortUrlStatus;
import com.schwab.urlshortener.exception.ShortUrlAlreadyActiveException;
import com.schwab.urlshortener.exception.ShortUrlAlreadyDeactivatedException;
import com.schwab.urlshortener.model.Caller;
import com.schwab.urlshortener.model.CreateShortUrlCommand;
import com.schwab.urlshortener.model.ShortUrlStats;
import com.schwab.urlshortener.model.ShortUrlView;
import com.schwab.urlshortener.model.StatsPeriod;
import com.schwab.urlshortener.model.UpdateShortUrlCommand;
import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.PostgresServerErrors;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.service.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.service.exception.InvalidAliasException;
import com.schwab.urlshortener.service.exception.InvalidExpirationException;
import com.schwab.urlshortener.service.exception.InvalidStatsQueryException;
import com.schwab.urlshortener.service.exception.InvalidUrlException;
import com.schwab.urlshortener.service.exception.ShortCodeUnavailableException;
import com.schwab.urlshortener.service.exception.ShortUrlConcurrentModificationException;
import com.schwab.urlshortener.service.exception.ShortUrlNotFoundException;
import com.schwab.urlshortener.util.shortcode.ShortCodeFormat;
import com.schwab.urlshortener.util.shortcode.ShortCodeGenerator;
import com.schwab.urlshortener.util.validation.AliasPolicy;
import com.schwab.urlshortener.util.validation.HttpUris;
import com.schwab.urlshortener.util.validation.UrlValidator;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates short URLs (FR-1, FR-3, FR-10), reads them for their owner or an ADMIN (FR-12, D4, D13), and changes
 * their lifecycle: deactivate, reactivate, change the expiry and soft delete (FR-4, FR-6, D26, D35, D36, D46, D114).
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
 * {@code @Transactional}: that annotation stays forbidden on every method of this class. {@code update} and
 * {@code delete} run in the third template, {@code readWrite} (REQUIRED, read-write), so the load, the transition
 * and the versioned UPDATE share one transaction (D35). {@code stats} runs in a fourth template, {@code snapshotRead}
 * (read-only, REPEATABLE READ, D102), so the link read and the daily counts describe one snapshot.
 */
@Slf4j
@Service
public class ShortUrlService {

    private final ShortUrlRepository repository;
    private final ClickEventRepository clickEvents;
    private final ShortCodeGenerator generator;
    private final UrlValidator urlValidator;
    private final AliasPolicy aliasPolicy;
    private final ExpirationPolicy expirationPolicy;
    private final Clock clock;
    private final int maxAttempts;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate readWrite;
    private final TransactionTemplate snapshotRead;

    public ShortUrlService(ShortUrlRepository repository, ClickEventRepository clickEvents,
            ShortCodeGenerator generator, UrlValidator urlValidator,
            AliasPolicy aliasPolicy, ExpirationPolicy expirationPolicy, Clock clock, ShortCodeProperties codeProperties,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clickEvents = clickEvents;
        this.generator = generator;
        this.urlValidator = urlValidator;
        this.aliasPolicy = aliasPolicy;
        this.expirationPolicy = expirationPolicy;
        this.clock = clock;
        this.maxAttempts = codeProperties.maxAttempts();
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readWrite = new TransactionTemplate(transactionManager);   // REQUIRED, read-write, default isolation
        this.snapshotRead = new TransactionTemplate(transactionManager);
        this.snapshotRead.setReadOnly(true);
        this.snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);   // D102
    }

    /**
     * @return the created short URL
     * @throws InvalidUrlException if the URL fails {@link UrlValidator} (checked first, D63)
     * @throws InvalidAliasException if a submitted alias fails {@link AliasPolicy}
     * @throws InvalidExpirationException if a submitted {@code expiresAt} is not in the future or is beyond the
     *         horizon (checked after the URL and the alias, D124)
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
        if (command.alias() != null && !aliasPolicy.isValid(command.alias())) {
            log.debug("Create rejected: errorCode={}", "INVALID_ALIAS");
            throw new InvalidAliasException();
        }
        Instant expiresAt = command.expiresAt() == null ? null : validExpiry(command.expiresAt(), now, "Create");
        if (command.alias() != null) {
            return createWithAlias(command, expiresAt, now);
        }
        return createWithGeneratedCode(command, expiresAt, now);
    }

    /**
     * @return the short URL, if the caller may see it
     * @throws ShortUrlNotFoundException if the code is malformed (D72) or unknown, the link is DELETED (even for
     *         ADMIN, D13), or the caller is neither its creator nor ADMIN (D4); the four cases are
     *         indistinguishable
     */
    public ShortUrlView get(String code, Caller caller) {
        Instant now = clock.instant();
        return readOnly.execute(status -> ShortUrlView.from(loadVisible(code, caller), now));
    }

    /**
     * Click statistics for a link the caller may see (FR-5, D95 to D105). The parameters are validated first, before
     * any transaction or lookup (D104); then the link is loaded with the same visibility rules as {@link #get}
     * (a DEACTIVATED link is visible, D105) and its clicks are counted per local day in one snapshot (D102).
     *
     * @param timezone the raw {@code timezone} parameter, or null when absent (defaults to UTC, D96)
     * @param from     the raw {@code from} parameter, or null when absent (D98)
     * @param to       the raw {@code to} parameter, or null when absent (D98)
     * @throws InvalidStatsQueryException if any parameter is invalid (D99)
     * @throws ShortUrlNotFoundException  if the code is malformed, unknown, DELETED, or not the caller's (D4, D13, D72)
     */
    public ShortUrlStats stats(String code, String timezone, String from, String to, Caller caller) {
        StatsPeriod period;
        Instant now = clock.instant();                                                // D45: the clock is read once
        try {
            period = StatsPeriod.resolve(timezone, from, to, now);
        } catch (InvalidStatsQueryException e) {
            log.debug("Stats rejected: errorCode=VALIDATION_FAILED parameters={}", e.violations().stream()
                    .map(v -> v.parameter().wireName()).toList());
            throw e;
        }
        return snapshotRead.execute(status -> {
            ShortUrl url = loadVisible(code, caller);
            List<ClickEventRepository.DayCount> rows =
                    clickEvents.countClicksPerDay(url.getId(), period.dayStarts(), period.end());
            return ShortUrlStats.of(url.getShortCode(), period, url.getClickCount(), url.getLastAccessedAt(),
                    period.densify(rows), url.getExpiresAt(), url.isExpiredAt(now));
        });
    }

    /**
     * Applies a PATCH (D34, D114): deactivates or reactivates, and/or sets, changes or clears the expiry, all in one
     * transaction. The expiry is validated first, before any lookup (400 before 404, as D104). The {@code active}
     * transition runs before the expiry change, so a redundant one fails the whole request and nothing is applied
     * (D126). An expiry equal to the current one is not a change (D125); if nothing changes, nothing is written.
     *
     * @return the short URL after the change
     * @throws InvalidExpirationException if a new {@code expiresAt} is not in the future or is beyond the horizon
     *         (D127)
     * @throws ShortUrlNotFoundException if the code is malformed, unknown, DELETED, or not the caller's (D4, D13, D72)
     * @throws ShortUrlAlreadyDeactivatedException if deactivating a DEACTIVATED link
     * @throws ShortUrlAlreadyActiveException if reactivating an ACTIVE link
     * @throws ShortUrlConcurrentModificationException if another request changed the link first (D35)
     */
    public ShortUrlView update(String code, UpdateShortUrlCommand command, Caller caller) {
        Instant now = clock.instant();                                            // D45: once per request
        Instant newExpiry = command.expiryPresent() && command.expiresAt() != null
                ? validExpiry(command.expiresAt(), now, "Update") : null;
        String action = actionOf(command);
        ShortUrlView view;
        try {
            view = readWrite.execute(status -> {
                ShortUrl url = loadVisible(code, caller);
                boolean changed = false;
                if (command.active() != null) {
                    if (command.active()) {
                        url.reactivate(now);
                    } else {
                        url.deactivate(now);
                    }
                    changed = true;
                }
                if (command.expiryPresent()) {
                    changed |= url.changeExpiry(newExpiry, now);
                }
                if (changed) {
                    repository.flush();                                           // the versioned UPDATE runs here
                }
                return ShortUrlView.from(url, now);
            });
        } catch (OptimisticLockingFailureException e) {                           // D35: flush-time or commit-time
            log.info("Short URL changed concurrently: code={} action={}", code, action);
            throw new ShortUrlConcurrentModificationException(e);
        }
        if (command.active() != null) {                                           // after commit only
            log.info("Short URL {}: code={}", command.active() ? "reactivated" : "deactivated", code);
        }
        if (command.expiryPresent()) {
            log.info("Short URL expiry {}: code={}", command.expiresAt() == null ? "cleared" : "set", code);
        }
        return view;
    }

    /**
     * Soft-deletes a short URL (D1, D36, D46). The row is kept; {@code deleted_by} is the caller's username (D51).
     *
     * @throws IllegalStateException if the caller is not ADMIN (D3): the filter chain makes this unreachable over HTTP,
     *         so it signals a security misconfiguration. Checked before any lookup.
     * @throws ShortUrlNotFoundException if the code is malformed, unknown or already DELETED (D13, D72)
     * @throws ShortUrlConcurrentModificationException if another request changed the link first (D35, D87)
     */
    public void delete(String code, Caller caller) {
        if (!caller.admin()) {
            throw new IllegalStateException("delete requires ADMIN");
        }
        Instant now = clock.instant();
        try {
            readWrite.executeWithoutResult(status -> {
                ShortUrl url = loadVisible(code, caller);
                url.softDelete(caller.username(), now);                           // D51: exactly the username
                repository.flush();
            });
        } catch (OptimisticLockingFailureException e) {
            log.info("Short URL changed concurrently: code={} action={}", code, "DELETE");
            throw new ShortUrlConcurrentModificationException(e);
        }
        log.info("Short URL deleted: code={}", code);
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

    private ShortUrlView createWithAlias(CreateShortUrlCommand command, Instant expiresAt, Instant now) {
        String alias = command.alias();
        try {
            return created(insert(alias, command, true, expiresAt, now), now);
        } catch (DataIntegrityViolationException e) {
            if (isShortCodeConflict(e)) {
                log.info("Alias conflict: code={}", alias);
                throw new AliasAlreadyExistsException(alias, e);
            }
            throw e;
        }
    }

    private ShortUrlView createWithGeneratedCode(CreateShortUrlCommand command, Instant expiresAt, Instant now) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = generator.generate();
            if (!aliasPolicy.isValid(code)) {
                // D29, D48: a reserved word counts as a collision and consumes an attempt, with no DB round trip.
                log.debug("Generated code rejected by alias policy: attempt={} maxAttempts={}", attempt, maxAttempts);
                continue;
            }
            try {
                return created(insert(code, command, false, expiresAt, now), now);
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
    private ShortUrl insert(String code, CreateShortUrlCommand command, boolean customAlias, Instant expiresAt,
            Instant now) {
        return requiresNew.execute(status -> repository.saveAndFlush(
                ShortUrl.create(code, command.originalUrl(), customAlias, command.createdBy(), now, expiresAt)));
    }

    private ShortUrlView created(ShortUrl saved, Instant now) {
        log.info("Short URL created: id={} code={} customAlias={} host={} expires={}", saved.getId(),
                saved.getShortCode(), saved.isCustomAlias(), hostOf(saved.getOriginalUrl()),
                saved.getExpiresAt() != null);
        return ShortUrlView.from(saved, now);
    }

    /** D107, D124: validated against the request's instant; never logs the submitted value. */
    private Instant validExpiry(Instant expiresAt, Instant now, String operation) {
        try {
            return expirationPolicy.validate(expiresAt, now);
        } catch (InvalidExpirationException e) {
            log.debug("{} rejected: errorCode={} field={}", operation, "VALIDATION_FAILED", "expiresAt");
            throw e;
        }
    }

    private static String actionOf(UpdateShortUrlCommand command) {
        String active = command.active() == null ? null : command.active() ? "REACTIVATE" : "DEACTIVATE";
        String expiry = !command.expiryPresent() ? null : command.expiresAt() == null ? "CLEAR_EXPIRY" : "SET_EXPIRY";
        return active == null ? expiry : expiry == null ? active : active + "+" + expiry;
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

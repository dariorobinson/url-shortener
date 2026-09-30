package com.schwab.urlshortener.api;

import com.schwab.urlshortener.config.AppProperties;
import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * Builds {@code shortUrl} and {@code Location} (D33). The public link comes only from the configured
 * {@code APP_BASE_URL}, never from the request's {@code Host} or forwarded headers, so a spoofed header
 * cannot change a link. Do not use {@code ServletUriComponentsBuilder}, {@code fromCurrentRequest*},
 * {@code getServerName()}, {@code getRequestURL()} or {@code X-Forwarded-*} in this package.
 */
@Component
public class ShortUrlLinks {

    private final String publicBase;

    public ShortUrlLinks(AppProperties properties) {
        String base = properties.baseUrl();
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        // @HttpBaseUrl guarantees no query and no fragment, so the code is always appended to the path.
        this.publicBase = base.substring(0, end);
    }

    /** The absolute public link. No encoding is needed: a code is always [A-Za-z0-9]{3,32} (D6). */
    public String publicUrl(String shortCode) {
        return publicBase + "/" + shortCode;
    }

    /** The management resource, relative as written in D33 (RFC 9110 allows a relative reference). */
    public URI location(String shortCode) {
        return URI.create(ShortUrlController.BASE_PATH + "/" + shortCode);
    }
}

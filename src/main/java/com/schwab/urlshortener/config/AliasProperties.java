package com.schwab.urlshortener.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Words reserved in addition to the built-in D29 set (D48). The property can only add: the built-in
 * words stay reserved whatever is configured. Blank entries are ignored. The default is empty.
 */
@ConfigurationProperties(prefix = "shortener.alias")
public record AliasProperties(@DefaultValue List<String> additionalReservedWords) {
}

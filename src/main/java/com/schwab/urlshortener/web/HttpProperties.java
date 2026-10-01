package com.schwab.urlshortener.web;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** HTTP hardening settings (D130). The largest valid body today is about 2.3 KiB, so 16 KiB leaves ample room. */
@Validated
@ConfigurationProperties(prefix = "app.http")
public record HttpProperties(@DefaultValue("16384") @Min(1024) long maxBodyBytes) {
}

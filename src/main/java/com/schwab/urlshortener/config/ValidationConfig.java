package com.schwab.urlshortener.config;

import com.schwab.urlshortener.validation.AliasPolicy;
import com.schwab.urlshortener.validation.UrlValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AppProperties.class, AliasProperties.class, ExpirationProperties.class})
class ValidationConfig {

    @Bean
    UrlValidator urlValidator(AppProperties properties) {
        return new UrlValidator(properties.baseUrl());
    }

    @Bean
    AliasPolicy aliasPolicy(AliasProperties properties) {
        return new AliasPolicy(properties.additionalReservedWords());
    }
}

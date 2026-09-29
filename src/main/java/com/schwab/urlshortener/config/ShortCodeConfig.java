package com.schwab.urlshortener.config;

import com.schwab.urlshortener.shortcode.SecureRandomShortCodeGenerator;
import com.schwab.urlshortener.shortcode.ShortCodeGenerator;
import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortCodeProperties.class)
class ShortCodeConfig {

    @Bean
    ShortCodeGenerator shortCodeGenerator(ShortCodeProperties properties) {
        return new SecureRandomShortCodeGenerator(new SecureRandom(), properties.length());
    }
}

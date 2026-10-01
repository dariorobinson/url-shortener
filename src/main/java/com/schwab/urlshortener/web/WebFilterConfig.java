package com.schwab.urlshortener.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.security.ProblemDetailResponseWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the two servlet filters ahead of Spring Security's chain (order -100): the request ID first, so every log
 * line of the request carries it, then the body limit, so an oversized body is refused before authentication.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HttpProperties.class)
public class WebFilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(HttpProperties properties,
            ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new RequestBodyLimitFilter(properties.maxBodyBytes(),
                new ProblemDetailResponseWriter(objectMapper)));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}

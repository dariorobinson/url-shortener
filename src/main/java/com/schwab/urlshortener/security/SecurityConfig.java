package com.schwab.urlshortener.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Stateless HTTP Basic security (D3, D30, D31, D32, D37, D55, D57). Every URL rule lives in the one filter
 * chain below; there is no method security. The first matching rule wins, so the order matters. The final
 * rule is {@code anyRequest().denyAll()} (D57): anything no explicit rule matches is refused.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String REALM = "url-shortener";

    /** ADMIN inherits USER (D3). Applied to every {@code hasRole} rule in the chain. */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.USER.name())
                .build();
    }

    /**
     * The entry point, access-denied handler and writer are built here and are not beans. The
     * authentication manager wraps the single {@link DaoAuthenticationProvider} (concrete type), so no
     * {@code PasswordEncoder} or {@link UserDetailsService} bean exists.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DaoAuthenticationProvider authenticationProvider,
            ObjectMapper objectMapper) throws Exception {
        var writer = new ProblemDetailResponseWriter(objectMapper);
        var entryPoint = new ProblemDetailAuthenticationEntryPoint(writer);
        var accessDenied = new ProblemDetailAccessDeniedHandler(writer);
        http
                .authenticationManager(new ProviderManager(authenticationProvider))
                // realmName() is deliberately not called: it conflicts with a custom entry point,
                // which writes the realm itself.
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // 1. Error rendering after an already-authorized request
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 2. Public infrastructure. HEAD on health is for load-balancer probes (US-014 H7).
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // 3. Reserved prefixes, declared before the public single-segment rule so that
                        //    /actuator and /api are never treated as a short code
                        //    Every other actuator path is ADMIN-only (US-014 H6), e.g. /actuator/metrics.
                        .requestMatchers("/actuator", "/actuator/**").hasRole(Role.ADMIN.name())
                        // Delete is ADMIN-only (D3). "/**" also covers the trailing-slash and nested variants,
                        // which would otherwise fall through to the USER rule below.
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/urls/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api", "/api/**").hasRole(Role.USER.name())
                        // 4. Public redirect: GET and HEAD on any single path segment (D32)
                        .requestMatchers(HttpMethod.GET, "/*").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/*").permitAll()
                        //    and on a single segment with a trailing slash, so it reaches MVC and gets
                        //    404 RESOURCE_NOT_FOUND instead of a 401 Basic challenge (D80, US-014 H8)
                        .requestMatchers(HttpMethod.GET, "/*/").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/*/").permitAll()
                        // 5. Deny by default (D57): a request that no rule above matches is refused, never
                        //    handled. Anonymous callers get 401 through the entry point, authenticated callers
                        //    get 403 ACCESS_DENIED. Only explicitly listed paths can reach a handler, so a
                        //    case or path variant such as /API/v1/urls/{code} cannot bypass the role rules.
                        .anyRequest().denyAll());
        return http.build();
    }
}

package com.schwab.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** D3: ADMIN inherits USER, never the reverse. */
class RoleHierarchyTest {

    private final RoleHierarchy hierarchy =
            SecurityConfig.roleHierarchy();

    @Test
    void shouldLetAdminReachUserAuthority() {
        var reachable = hierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(reachable).extracting(Object::toString).contains("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void shouldNotLetUserReachAdminAuthority() {
        var reachable = hierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(reachable).extracting(Object::toString).containsExactly("ROLE_USER");
    }
}

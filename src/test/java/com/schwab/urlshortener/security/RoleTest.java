package com.schwab.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;

class RoleTest {

    @Test
    void shouldMatchTheAuthoritySpringGrantsForAnAdminRole() {
        var authorities = User.withUsername("x").password("p").roles(Role.ADMIN.name()).build().getAuthorities();

        assertThat(authorities).extracting(Object::toString).containsExactly(Role.ADMIN.authority());
        assertThat(Role.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void shouldMatchTheAuthoritySpringGrantsForAUserRole() {
        var authorities = User.withUsername("x").password("p").roles(Role.USER.name()).build().getAuthorities();

        assertThat(authorities).extracting(Object::toString).containsExactly(Role.USER.authority());
        assertThat(Role.USER.authority()).isEqualTo("ROLE_USER");
    }
}

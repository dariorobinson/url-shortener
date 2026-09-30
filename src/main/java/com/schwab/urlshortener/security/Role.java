package com.schwab.urlshortener.security;

/** Application roles (D3). ADMIN inherits USER through the role hierarchy. */
public enum Role {
    USER,
    ADMIN;

    /**
     * The granted-authority string Spring Security holds for this role ({@code ROLE_} prefix). The one shared
     * definition of the prefixed name.
     */
    public String authority() {
        return "ROLE_" + name();
    }
}

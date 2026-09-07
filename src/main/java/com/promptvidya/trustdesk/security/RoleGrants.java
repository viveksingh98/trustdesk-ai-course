package com.promptvidya.trustdesk.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Roles are for people; scopes are what the tool chain consumes. This is
 * the one place where a role becomes the exact scope set every gate in
 * the application already understands — the access-request guard, the
 * domain read tools, and the caller-scoped retriever.
 */
public final class RoleGrants {

    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String MANAGER = "MANAGER";
    public static final String AUDITOR = "AUDITOR";

    private static final String ROLE_PREFIX = "ROLE_";

    private static final Map<String, Set<String>> SCOPES_BY_ROLE = Map.of(
            EMPLOYEE, Set.of("access:request", "policies:it-hardware"),
            MANAGER, Set.of("access:request", "policies:it-hardware", "policies:finance-ops"),
            AUDITOR, Set.of("audit:read", "policies:it-hardware", "policies:finance-ops"));

    private RoleGrants() {}

    /** The scopes a role grants, or an error for a role nobody defined. */
    public static Set<String> scopesFor(String role) {
        var scopes = SCOPES_BY_ROLE.get(role);
        if (scopes == null) {
            throw new IllegalArgumentException("unknown role: " + role);
        }
        return scopes;
    }

    /** The role authority first, then its scopes in a stable order. */
    public static List<GrantedAuthority> authoritiesFor(String role) {
        var authorities = new ArrayList<GrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
        scopesFor(role).stream()
                .sorted()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return List.copyOf(authorities);
    }

    public static boolean isRole(String authority) {
        return authority != null && authority.startsWith(ROLE_PREFIX);
    }
}

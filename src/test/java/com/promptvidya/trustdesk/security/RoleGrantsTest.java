package com.promptvidya.trustdesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class RoleGrantsTest {

    @Test
    void employeesMayRequestAccessAndReadTheirOwnShelf() {
        assertThat(RoleGrants.scopesFor(RoleGrants.EMPLOYEE))
                .containsExactlyInAnyOrder("access:request", "policies:it-hardware");
    }

    @Test
    void auditorsReadEverythingButNeverRequestAccess() {
        assertThat(RoleGrants.scopesFor(RoleGrants.AUDITOR))
                .contains("audit:read", "policies:it-hardware", "policies:finance-ops")
                .doesNotContain("access:request");
    }

    @Test
    void theRoleAuthorityComesFirstAndScopesFollowInStableOrder() {
        assertThat(RoleGrants.authoritiesFor(RoleGrants.MANAGER))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(
                        "ROLE_MANAGER", "access:request", "policies:finance-ops", "policies:it-hardware");
        assertThat(RoleGrants.isRole("ROLE_MANAGER")).isTrue();
        assertThat(RoleGrants.isRole("access:request")).isFalse();
    }

    @Test
    void aRoleNobodyDefinedGrantsNothingAndFailsLoudly() {
        assertThatThrownBy(() -> RoleGrants.scopesFor("ROOT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROOT");
    }
}

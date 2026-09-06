package com.promptvidya.trustdesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.identity.ActorContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ToolAuthorizationGuardTest {

    private final ToolAuthorizationGuard guard =
            new ToolAuthorizationGuard(new AccessPolicy(Set.of("ROOT_OPERATOR")));

    @Test
    void accessRequestScopeIsRequired() {
        var actor = new ActorContext("alice", Set.of("profile:read"));
        var request = new AccessRequest("alice", "REPORT_VIEWER", "training lab");

        assertThatThrownBy(() -> guard.authorize(actor, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void blankJustificationIsRejected() {
        var actor = new ActorContext("alice", Set.of("access:request"));
        var request = new AccessRequest("alice", "REPORT_VIEWER", "   ");

        assertThatThrownBy(() -> guard.authorize(actor, request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminAlwaysRequiresHumanApproval() {
        var actor = new ActorContext("alice", Set.of("access:request"));
        var request = new AccessRequest("alice", "ADMIN", "training lab");

        assertThat(guard.authorize(actor, request)).isEqualTo("REQUIRES_HUMAN_APPROVAL");
    }

    @Test
    void policyDefinedPrivilegedEntitlementRequiresHumanApproval() {
        var actor = new ActorContext("alice", Set.of("access:request"));
        var request = new AccessRequest("alice", "ROOT_OPERATOR", "training lab");

        assertThat(guard.authorize(actor, request)).isEqualTo("REQUIRES_HUMAN_APPROVAL");
    }

    @Test
    void ordinaryEntitlementRemainsPendingApproval() {
        var actor = new ActorContext("alice", Set.of("access:request"));
        var request = new AccessRequest("alice", "REPORT_VIEWER", "training lab");

        assertThat(guard.authorize(actor, request)).isEqualTo("PENDING_APPROVAL");
    }
}

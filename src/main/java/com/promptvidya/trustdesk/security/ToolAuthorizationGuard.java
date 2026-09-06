package com.promptvidya.trustdesk.security;

import java.util.Objects;

import com.promptvidya.trustdesk.access.AccessPolicy;
import com.promptvidya.trustdesk.access.AccessRequest;
import com.promptvidya.trustdesk.identity.ActorContext;
import org.springframework.security.access.AccessDeniedException;

public final class ToolAuthorizationGuard {

    private static final String ACCESS_REQUEST_SCOPE = "access:request";
    private final AccessPolicy policy;

    public ToolAuthorizationGuard(AccessPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public String authorize(ActorContext actor, AccessRequest request) {
        if (actor == null || request == null) {
            throw denied();
        }
        if (!actor.scopes().contains(ACCESS_REQUEST_SCOPE)) {
            throw denied();
        }
        if (!actor.subject().equals(request.subject())) {
            throw denied();
        }
        if (request.entitlement() == null || request.entitlement().isBlank()) {
            throw denied();
        }
        if (request.justification() == null || request.justification().isBlank()) {
            throw denied();
        }
        return policy.requiresHumanApproval(request.entitlement())
                ? "REQUIRES_HUMAN_APPROVAL"
                : "PENDING_APPROVAL";
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Access request is not authorized");
    }
}

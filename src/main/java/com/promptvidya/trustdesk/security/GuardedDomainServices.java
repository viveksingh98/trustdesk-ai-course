package com.promptvidya.trustdesk.security;

import com.promptvidya.trustdesk.domain.AccessRequestRecord;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Authorization on the service layer itself. URL rules are the outer
 * wall; these annotations are the inner gate, evaluated on every call
 * no matter which controller, tool, or scheduled job made it.
 *
 * <p>Expressions read the authenticated principal from the security
 * context — never a parameter the caller could forge — and the
 * own-records rule lives in one line instead of in every caller.
 */
@Service
public class GuardedDomainServices {

    static final String AUDIT_READ = "audit:read";
    static final String DECIDE_ACTION = "decide_access_request";

    private final TrustDeskDomain domain;
    private final AuditTrail audit;

    public GuardedDomainServices(TrustDeskDomain domain, AuditTrail audit) {
        this.domain = Objects.requireNonNull(domain);
        this.audit = Objects.requireNonNull(audit);
    }

    /** Anyone may read their own evidence; auditors may read anyone's. */
    @PreAuthorize("hasAuthority('audit:read') or #subject == authentication.name")
    public List<AuditEvent> eventsFor(String subject) {
        return audit.eventsFor(subject);
    }

    /** Outcome-wide queries reveal other people's activity: auditors only. */
    @PreAuthorize("hasAuthority('audit:read')")
    public List<AuditEvent> eventsWithOutcome(String outcome) {
        return audit.eventsWithOutcome(outcome);
    }

    /** Pending work is visible to the people who can act on it. */
    @PreAuthorize("hasRole('MANAGER')")
    public List<AccessRequestRecord> pendingRequests() {
        return domain.pendingRequests();
    }

    /** Only managers decide — and the decider is a person, never software acting for one. */
    @PreAuthorize("hasRole('MANAGER')")
    public AccessRequestRecord decide(String requestId, boolean approved) {
        var decider = personBehind(authenticated());
        var decided = domain.decide(requestId, approved, decider);
        audit.record(decider, DECIDE_ACTION, requestId, approved ? "APPROVED" : "REJECTED");
        return decided;
    }

    private static Authentication authenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("guarded call reached without an authenticated principal");
        }
        return authentication;
    }

    /** A delegated token proves a person asked; it does not make the agent that person. */
    private static String personBehind(Authentication authentication) {
        if (DelegatedTokens.isDelegated(authentication)) {
            throw new AccessDeniedException("access decisions are made by people, not by software acting for them");
        }
        return authentication.getName();
    }
}

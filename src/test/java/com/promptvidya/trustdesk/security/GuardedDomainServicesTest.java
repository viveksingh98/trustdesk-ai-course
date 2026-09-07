package com.promptvidya.trustdesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.domain.AccessRequestRecord.RequestState;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.domain.TrustDeskDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * The inner gate, called directly — no HTTP, no URL rule in the way —
 * so what passes and what is refused is decided by the annotations alone.
 */
@SpringBootTest
class GuardedDomainServicesTest {

    @Autowired
    private GuardedDomainServices services;

    @Autowired
    private AuditTrail audit;

    @Autowired
    private TrustDeskDomain domain;

    @Test
    @WithMockUser(username = "alice", roles = RoleGrants.EMPLOYEE)
    void peopleReadTheirOwnEvidenceAndNobodyElses() {
        audit.record("alice", "read_ticket", "T-1", "ALLOWED");

        assertThat(services.eventsFor("alice")).extracting(AuditEvent::actor).containsOnly("alice");
        assertThatThrownBy(() -> services.eventsFor("bob")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "auditor", authorities = {"ROLE_AUDITOR", "audit:read"})
    void auditorsReadAnyoneAndAnyOutcome() {
        audit.record("bob", "read_ticket", "T-2", "REFUSED");

        assertThat(services.eventsFor("bob")).extracting(AuditEvent::actor).contains("bob");
        assertThat(services.eventsWithOutcome("REFUSED")).extracting(AuditEvent::actor).contains("bob");
    }

    @Test
    @WithMockUser(username = "alice", roles = RoleGrants.EMPLOYEE)
    void employeesNeitherSeePendingWorkNorDecide() {
        assertThatThrownBy(() -> services.pendingRequests()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> services.decide("R-1", true)).isInstanceOf(AccessDeniedException.class);

        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action)
                .doesNotContain(GuardedDomainServices.DECIDE_ACTION);
    }

    @Test
    @WithMockUser(username = "hardware-lead", roles = RoleGrants.MANAGER)
    void managersDecideAndTheDeciderIsThePrincipal() {
        var request = domain.requestAccess("carol", "REPORT_VIEWER", "month-end close");

        var decided = services.decide(request.id(), true);

        assertThat(decided.state()).isEqualTo(RequestState.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("hardware-lead");
        assertThat(audit.eventsFor("hardware-lead"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .contains(tuple(GuardedDomainServices.DECIDE_ACTION, request.id(), "APPROVED"));
    }

    @Test
    void noIdentityIsNotTheSameAsTheWrongIdentity() {
        assertThatThrownBy(() -> services.eventsFor("alice")).isInstanceOf(AuthenticationException.class);
    }
}

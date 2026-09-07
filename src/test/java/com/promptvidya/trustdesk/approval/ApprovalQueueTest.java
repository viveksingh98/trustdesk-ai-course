package com.promptvidya.trustdesk.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.approval.ApprovalQueue.State;
import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.DelegatedTokens;
import com.promptvidya.trustdesk.security.DevelopmentJwtKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The workflow, one promise at a time: a proposal pauses with evidence,
 * a person decides, software cannot, a stale proposal expires instead of
 * deciding, and an approval executes exactly once.
 */
@SpringBootTest
class ApprovalQueueTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T11:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final AuditTrail audit = new AuditTrail(clock);
    private final AtomicInteger sequence = new AtomicInteger();
    private final ApprovalQueue queue = new ApprovalQueue(clock, audit, () -> "A-" + sequence.incrementAndGet());
    private final ActorContext alice = new ActorContext("alice", Set.of("access:request"));
    private final DelegationChain viaAgent = new DelegationChain("alice", List.of("trustdesk-agent"));
    private final TestingAuthenticationToken aliceInPerson =
            new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE");

    @Autowired
    private DelegatedTokens tokens;

    @Autowired
    private JwtDecoder decoder;

    @Test
    void aProposalPausesWithEvidenceAndNeverRuns() {
        var action = queue.submit(alice, viaAgent, "requestAccess", " {\"entitlement\":\"REPORT_VIEWER\"} ", "month-end close");

        assertThat(action.state()).isEqualTo(State.PENDING);
        assertThat(action.chain()).isEqualTo("alice via trustdesk-agent");
        assertThat(action.expiresAt()).isEqualTo(now.get().plus(ApprovalQueue.DEFAULT_LIFETIME));
        assertThat(queue.pending()).extracting(ApprovalQueue.PendingAction::id).containsExactly("A-1");
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::outcome)
                .containsExactly(tuple(ApprovalQueue.SUBMIT_ACTION, "PENDING"));
        assertThat(audit.all()).noneMatch(event -> event.target().contains("REPORT_VIEWER"));
    }

    @Test
    void aPersonDecidesAndTheDecisionIsRecorded() {
        var action = queue.submit(alice, viaAgent, "requestAccess", "{}", "why");

        var approved = queue.decide(action.id(), true, aliceInPerson);

        assertThat(approved.state()).isEqualTo(State.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("alice");
        assertThat(queue.pending()).isEmpty();
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::target, AuditEvent::outcome)
                .contains(tuple(ApprovalQueue.DECIDE_ACTION, action.id(), "APPROVED"));
    }

    @Test
    void softwareActingForAPersonCannotDecide() {
        var action = queue.submit(alice, viaAgent, "requestAccess", "{}", "why");
        var delegated = decoder.decode(tokens.mintFor(alice, "trustdesk-agent", Set.of("access:request")));
        var agent = new JwtAuthenticationToken(delegated, List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));

        assertThatThrownBy(() -> queue.decide(action.id(), true, agent))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("people");
        assertThat(queue.get(action.id())).get().extracting(ApprovalQueue.PendingAction::state).isEqualTo(State.PENDING);
    }

    @Test
    void aStaleProposalExpiresInsteadOfBeingDecided() {
        var action = queue.submit(alice, viaAgent, "requestAccess", "{}", "why");
        now.set(now.get().plus(Duration.ofMinutes(31)));

        assertThatThrownBy(() -> queue.decide(action.id(), true, aliceInPerson)).isInstanceOf(AccessDeniedException.class);

        assertThat(queue.get(action.id())).get().extracting(ApprovalQueue.PendingAction::state).isEqualTo(State.EXPIRED);
        assertThat(queue.pending()).isEmpty();
    }

    @Test
    void anApprovalExecutesExactlyOnce() {
        var action = queue.submit(alice, viaAgent, "requestAccess", "{}", "why");
        assertThatThrownBy(() -> queue.markExecuted(action.id())).isInstanceOf(AccessDeniedException.class);
        queue.decide(action.id(), true, aliceInPerson);

        assertThat(queue.markExecuted(action.id()).state()).isEqualTo(State.EXECUTED);

        assertThatThrownBy(() -> queue.markExecuted(action.id())).isInstanceOf(AccessDeniedException.class);
        assertThat(audit.eventsWithOutcome("REFUSED")).extracting(AuditEvent::action).contains(ApprovalQueue.EXECUTE_ACTION);
        assertThat(DevelopmentJwtKeys.AUDIENCE).isNotBlank();
    }
}

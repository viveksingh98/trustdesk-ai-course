package com.promptvidya.trustdesk.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.approval.ApprovalQueue.State;
import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.identity.ActorContext;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * Bound to exactly one proposal: spelling does not matter, meaning does;
 * another person's agent cannot spend it; and it goes stale on its own.
 */
class IntentBindingTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T12:00:00Z"));
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
    private final IntentBinding binding = new IntentBinding(queue, audit, clock);
    private final ActorContext alice = new ActorContext("alice", Set.of("access:request"));
    private final DelegationChain aliceViaAgent = new DelegationChain("alice", List.of("trustdesk-agent"));
    private final TestingAuthenticationToken aliceInPerson = new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE");

    private static final String REFUND = "{\"ticketId\": \"T-1\", \"amount\": 500, \"currency\": \"EUR\"}";
    private static final String REFUND_RESPELLED = "{\"currency\":\"EUR\",\"amount\":500,\"ticketId\":\"T-1\"}";
    private static final String REFUND_INFLATED = "{\"ticketId\":\"T-1\",\"amount\":5000,\"currency\":\"EUR\"}";

    @Test
    void spellingDoesNotMatterMeaningDoes() {
        assertThat(IntentBinding.digestOf("refund", REFUND)).isEqualTo(IntentBinding.digestOf("refund", REFUND_RESPELLED));
        assertThat(IntentBinding.digestOf("refund", REFUND)).isNotEqualTo(IntentBinding.digestOf("refund", REFUND_INFLATED));
        assertThat(IntentBinding.canonical("{\"b\":{\"y\":1,\"x\":[2, 1]},\"a\":true}")).isEqualTo("{\"a\":true,\"b\":{\"x\":[2,1],\"y\":1}}");
    }

    @Test
    void theApprovedProposalExecutesAndAnInflatedOneDoesNot() {
        var action = binding.submit(alice, aliceViaAgent, "refund", REFUND, "customer overcharged");
        queue.decide(action.id(), true, aliceInPerson);

        assertThatThrownBy(() -> binding.execute(action.id(), aliceViaAgent, "refund", REFUND_INFLATED))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("different proposal");
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::outcome)
                .contains(tuple(IntentBinding.SUBSTITUTION, "REFUSED"));

        assertThat(binding.execute(action.id(), aliceViaAgent, "refund", REFUND_RESPELLED).state()).isEqualTo(State.EXECUTED);
    }

    @Test
    void anotherPersonsAgentCannotSpendTheApproval() {
        var action = binding.submit(alice, aliceViaAgent, "refund", REFUND, "customer overcharged");
        queue.decide(action.id(), true, aliceInPerson);
        var bobViaAgent = new DelegationChain("bob", List.of("trustdesk-agent"));

        assertThatThrownBy(() -> binding.execute(action.id(), bobViaAgent, "refund", REFUND))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(queue.get(action.id())).get().extracting(ApprovalQueue.PendingAction::state).isEqualTo(State.APPROVED);
    }

    @Test
    void anOldApprovalRequiresReauthorization() {
        var action = binding.submit(alice, aliceViaAgent, "refund", REFUND, "customer overcharged");
        queue.decide(action.id(), true, aliceInPerson);
        now.set(now.get().plus(Duration.ofMinutes(11)));

        assertThatThrownBy(() -> binding.execute(action.id(), aliceViaAgent, "refund", REFUND))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("propose again");
        assertThat(audit.eventsFor("alice"))
                .extracting(AuditEvent::action, AuditEvent::outcome)
                .contains(tuple(IntentBinding.STALE, "REAUTHORIZATION_REQUIRED"));

        var fresh = binding.submit(alice, aliceViaAgent, "refund", REFUND, "customer overcharged");
        queue.decide(fresh.id(), true, aliceInPerson);
        assertThat(binding.execute(fresh.id(), aliceViaAgent, "refund", REFUND).state()).isEqualTo(State.EXECUTED);
    }
}

package com.promptvidya.trustdesk.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.promptvidya.trustdesk.approval.ApprovalNotifier.Kind;
import com.promptvidya.trustdesk.approval.ApprovalNotifier.Notification;
import com.promptvidya.trustdesk.approval.ApprovalQueue.State;
import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * Notifications that are references, delivered once per reason no
 * matter how often the sweep runs, silent for decided proposals, and
 * final when a proposal expires.
 */
class ApprovalNotifierTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T13:00:00Z"));
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
    private final List<Notification> inbox = new ArrayList<>();
    private final ApprovalNotifier notifier = new ApprovalNotifier(queue, inbox::add, audit, clock);
    private final ActorContext alice = new ActorContext("alice", Set.of("access:request"));
    private final DelegationChain viaAgent = new DelegationChain("alice", List.of("trustdesk-agent"));

    private String propose() {
        return queue.submit(alice, viaAgent, "requestAccess", "{\"entitlement\":\"REPORT_VIEWER\"}", "month-end").id();
    }

    @Test
    void aNewProposalIsAnnouncedOnceAsAReference() {
        var id = propose();

        notifier.sweep(List.of(id));
        notifier.sweep(List.of(id));

        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst()).extracting(Notification::kind, Notification::actionId, Notification::tool)
                .containsExactly(Kind.NEW, id, "requestAccess");
        assertThat(inbox.getFirst().timeLeft()).isEqualTo(ApprovalQueue.DEFAULT_LIFETIME);
        assertThat(inbox.toString()).doesNotContain("REPORT_VIEWER").doesNotContain("month-end");
    }

    @Test
    void aReminderFiresOnceWhenHalfTheLifeIsGone() {
        var id = propose();
        notifier.sweep(List.of(id));

        now.set(now.get().plus(Duration.ofMinutes(14)));
        notifier.sweep(List.of(id));
        now.set(now.get().plus(Duration.ofMinutes(2)));
        notifier.sweep(List.of(id));
        notifier.sweep(List.of(id));

        assertThat(inbox).extracting(Notification::kind).containsExactly(Kind.NEW, Kind.REMINDER);
        assertThat(inbox.get(1).timeLeft()).isEqualTo(Duration.ofMinutes(14));
    }

    @Test
    void aDecidedProposalIsNeverReminded() {
        var id = propose();
        notifier.sweep(List.of(id));
        queue.decide(id, true, new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE"));

        now.set(now.get().plus(Duration.ofMinutes(20)));
        notifier.sweep(List.of(id));

        assertThat(inbox).extracting(Notification::kind).containsExactly(Kind.NEW);
    }

    @Test
    void expiryIsReportedOnceAndTheProposalIsClosed() {
        var id = propose();
        notifier.sweep(List.of(id));

        now.set(now.get().plus(Duration.ofMinutes(31)));
        notifier.sweep(List.of(id));
        notifier.sweep(List.of(id));

        assertThat(inbox).extracting(Notification::kind).containsExactly(Kind.NEW, Kind.EXPIRED);
        assertThat(queue.get(id)).get().extracting(ApprovalQueue.PendingAction::state).isEqualTo(State.EXPIRED);
        assertThat(audit.eventsFor("alice")).filteredOn(event -> event.action().equals(ApprovalNotifier.NOTIFY_ACTION)).hasSize(2);
    }
}

package com.promptvidya.trustdesk.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.approval.ApprovalNotifier.Kind;
import com.promptvidya.trustdesk.approval.ApprovalNotifier.Notification;
import com.promptvidya.trustdesk.approval.ApprovalQueue.State;
import com.promptvidya.trustdesk.approval.SafeExecutor.Limits;
import com.promptvidya.trustdesk.approval.SafeExecutor.Outcome;
import com.promptvidya.trustdesk.approval.SafeExecutor.Step;
import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

/**
 * Attacking the queue. Race two executions of one approval; replay a
 * spent one; substitute the arguments under an approval; flood the
 * queue to fatigue the person and slip a large action through; race two
 * deciders. Each move meets the property built for it.
 */
class ApprovalBypassAttackTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T14:00:00Z"));
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
    private final SafeExecutor executor = new SafeExecutor(audit);
    private final ActorContext alice = new ActorContext("alice", Set.of("access:request"));
    private final DelegationChain viaAgent = new DelegationChain("alice", List.of("trustdesk-agent"));
    private final TestingAuthenticationToken aliceInPerson = new TestingAuthenticationToken("alice", "n/a", "ROLE_EMPLOYEE");
    private final TestingAuthenticationToken leadInPerson = new TestingAuthenticationToken("hardware-lead", "n/a", "ROLE_MANAGER");

    private static final String REFUND = "{\"ticketId\":\"T-1\",\"amount\":500}";

    private Step debit(List<String> ledger) {
        return new Step() {
            @Override
            public String name() {
                return "debit";
            }

            @Override
            public void run() {
                ledger.add("debit");
            }

            @Override
            public void undo() {
                ledger.add("undo");
            }
        };
    }

    @Test
    void moveOneRacingTwoExecutionsOfOneApprovalYieldsExactlyOne() throws Exception {
        var action = binding.submit(alice, viaAgent, "refund", REFUND, "overcharged");
        queue.decide(action.id(), true, aliceInPerson);
        var start = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var refusals = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        binding.execute(action.id(), viaAgent, "refund", REFUND);
                        successes.incrementAndGet();
                    } catch (AccessDeniedException refused) {
                        refusals.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(refusals.get()).isEqualTo(7);
    }

    @Test
    void moveTwoReplayingASpentApprovalRunsNothing() {
        var ledger = new ArrayList<String>();
        var action = binding.submit(alice, viaAgent, "refund", REFUND, "overcharged");
        queue.decide(action.id(), true, aliceInPerson);
        binding.execute(action.id(), viaAgent, "refund", REFUND);
        executor.execute(action.id(), "alice", 500, new Limits(2, 1_000), List.of(debit(ledger)));

        assertThatThrownBy(() -> binding.execute(action.id(), viaAgent, "refund", REFUND)).isInstanceOf(AccessDeniedException.class);
        var replay = executor.execute(action.id(), "alice", 500, new Limits(2, 1_000), List.of(debit(ledger)));

        assertThat(replay.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(ledger).containsExactly("debit");
    }

    @Test
    void moveThreeSubstitutingTheArgumentsUnderAnApprovalIsRefused() {
        var action = binding.submit(alice, viaAgent, "refund", REFUND, "overcharged");
        queue.decide(action.id(), true, aliceInPerson);

        assertThatThrownBy(() -> binding.execute(action.id(), viaAgent, "refund", "{\"ticketId\":\"T-1\",\"amount\":50000}"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("different proposal");
        assertThat(queue.get(action.id())).get().extracting(ApprovalQueue.PendingAction::state).isEqualTo(State.APPROVED);
    }

    @Test
    void moveFourFatigueDoesNotFloodTheChannelAndLimitsStillHold() {
        var inbox = new ArrayList<Notification>();
        var notifier = new ApprovalNotifier(queue, inbox::add, audit, clock);
        var ids = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            ids.add(binding.submit(alice, viaAgent, "refund", "{\"ticketId\":\"T-1\",\"amount\":5}", "small").id());
        }
        var large = binding.submit(alice, viaAgent, "refund", "{\"ticketId\":\"T-1\",\"amount\":50000}", "small");
        ids.add(large.id());

        notifier.sweep(ids);
        notifier.sweep(ids);
        queue.decide(large.id(), true, aliceInPerson);
        binding.execute(large.id(), viaAgent, "refund", "{\"ticketId\":\"T-1\",\"amount\":50000}");

        assertThat(inbox).hasSize(101).allMatch(notification -> notification.kind() == Kind.NEW);
        assertThatThrownBy(() -> executor.execute(large.id(), "alice", 50000, new Limits(2, 1_000), List.of(debit(new ArrayList<>()))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void moveFiveRacingTwoDecidersRecordsExactlyOneDecision() throws Exception {
        var action = binding.submit(alice, viaAgent, "refund", REFUND, "overcharged");
        var start = new CountDownLatch(1);
        var decided = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (var decider : List.of(aliceInPerson, leadInPerson)) {
                pool.submit(() -> {
                    start.await();
                    try {
                        queue.decide(action.id(), decider == aliceInPerson, decider);
                        decided.incrementAndGet();
                    } catch (AccessDeniedException refused) {
                        // the loser of the race
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(decided.get()).isEqualTo(1);
        var finalState = queue.get(action.id()).orElseThrow().state().name();
        assertThat(audit.all()).filteredOn(event -> event.action().equals(ApprovalQueue.DECIDE_ACTION))
                .extracting(event -> event.outcome())
                .hasSize(2)
                .allMatch(outcome -> outcome.equals(finalState));
        assertThat(queue.get(action.id())).get()
                .extracting(ApprovalQueue.PendingAction::decidedBy).isIn("alice", "hardware-lead");
    }
}

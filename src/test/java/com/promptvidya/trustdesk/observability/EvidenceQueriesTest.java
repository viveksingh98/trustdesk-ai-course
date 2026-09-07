package com.promptvidya.trustdesk.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import com.promptvidya.trustdesk.observability.EvidenceQueries.Window;
import com.promptvidya.trustdesk.testing.FrozenClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An investigation on a frozen clock: a morning of evidence rows from
 * three actors, then the four questions a responder asks — timeline,
 * story, refusals by outcome, who is being refused too often.
 */
class EvidenceQueriesTest {

    private static final Instant START = Instant.parse("2026-09-07T08:00:00Z");

    private final FrozenClock clock = FrozenClock.at(START);
    private final AuditTrail trail = new AuditTrail(clock);
    private final EvidenceQueries queries = new EvidenceQueries(trail);

    private void record(String actor, String action, String target, String outcome, Duration later) {
        clock.advance(later);
        trail.record(actor, action, target, outcome);
    }

    private void aMorningOfEvidence() {
        record("alice", "tool_run", "ticketById", "ALLOWED", Duration.ofMinutes(1));
        record("alice", "read_ticket", "T-1", "ALLOWED", Duration.ofMinutes(1));
        record("mallory", "tool_run", "export_payroll", "DENIED_UNKNOWN_TOOL", Duration.ofMinutes(1));
        record("mallory", "tool_run", "requestAccess", "DENIED_NO_GRANT", Duration.ofMinutes(1));
        record("mallory", "tool_run", "requestAccess", "DENIED_NO_GRANT", Duration.ofMinutes(1));
        record("hardware-lead", "decide_action", "T-1", "APPROVED", Duration.ofMinutes(5));
        record("alice", "execute_action", "T-1", "COMPLETED", Duration.ofMinutes(1));
        record("bob", "tool_run", "ticketById", "REFUSED", Duration.ofHours(2));
    }

    private Window theFirstHour() {
        return new Window(START, START.plus(Duration.ofHours(1)));
    }

    @Test
    void aTimelineIsOneActorsRowsInsideTheWindowInOrder() {
        aMorningOfEvidence();

        var alice = queries.timeline("alice", theFirstHour());

        assertThat(alice).extracting(AuditEvent::action, AuditEvent::outcome)
                .containsExactly(
                        tuple("tool_run", "ALLOWED"),
                        tuple("read_ticket", "ALLOWED"),
                        tuple("execute_action", "COMPLETED"));
        assertThat(queries.timeline("bob", theFirstHour())).isEmpty();
    }

    @Test
    void aStoryFollowsOneTargetAcrossEveryActor() {
        aMorningOfEvidence();

        var ticket = queries.story("T-1");

        assertThat(ticket).extracting(AuditEvent::actor, AuditEvent::action)
                .containsExactly(
                        tuple("alice", "read_ticket"),
                        tuple("hardware-lead", "decide_action"),
                        tuple("alice", "execute_action"));
        assertThat(ticket).isSortedAccordingTo((first, second) -> first.at().compareTo(second.at()));
    }

    @Test
    void refusalsAreCountedByOutcomeSoASpikeHasAName() {
        aMorningOfEvidence();

        assertThat(queries.refusalsByOutcome(theFirstHour()))
                .containsExactlyInAnyOrderEntriesOf(Map.of("DENIED_UNKNOWN_TOOL", 1L, "DENIED_NO_GRANT", 2L));
        assertThat(queries.refusalsByOutcome(new Window(START, START.plus(Duration.ofHours(3)))))
                .containsEntry("REFUSED", 1L);
    }

    @Test
    void actorsRefusedTooOftenAreTheFirstListAResponderOpens() {
        aMorningOfEvidence();

        assertThat(queries.actorsRefusedMoreThan(2, theFirstHour())).containsExactly(Map.entry("mallory", 3L));
        assertThat(queries.actorsRefusedMoreThan(3, theFirstHour())).isEmpty();
    }

    @Test
    void windowsRunForwardAndAnswersCarryOnlyReferences() {
        aMorningOfEvidence();

        assertThatThrownBy(() -> new Window(START.plusSeconds(10), START)).isInstanceOf(IllegalArgumentException.class);
        assertThat(queries.story("T-1")).allSatisfy(event -> {
            assertThat(event.target()).hasSizeLessThanOrEqualTo(200);
            assertThat(event.outcome()).doesNotContainAnyWhitespaces();
        });
    }
}

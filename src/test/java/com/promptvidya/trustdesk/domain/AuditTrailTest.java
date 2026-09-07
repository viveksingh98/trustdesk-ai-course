package com.promptvidya.trustdesk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuditTrailTest {

    private static final Instant FIXED = Instant.parse("2026-09-07T10:00:00Z");
    private final AuditTrail trail = new AuditTrail(Clock.fixed(FIXED, ZoneOffset.UTC));

    @Test
    void everyEventNamesActorActionAndOutcome() {
        var event = trail.record("alice", "request_access", "REPORT_VIEWER", "PENDING_APPROVAL");

        assertThat(event).isEqualTo(
                new AuditEvent(FIXED, "alice", "request_access", "REPORT_VIEWER", "PENDING_APPROVAL"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> trail.record(" ", "request_access", "x", "DENIED"))
                .withMessageContaining("actor");
    }

    @Test
    void theTrailIsAppendOnlyByConstruction() {
        trail.record("alice", "read_ticket", "T-1", "ALLOWED");

        assertThatThrownBy(() -> trail.all().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(trail.size()).isEqualTo(1);
    }

    @Test
    void eventsFilterByActorWithoutLeakingOthers() {
        trail.record("alice", "read_ticket", "T-1", "ALLOWED");
        trail.record("bob", "read_ticket", "T-2", "ALLOWED");

        assertThat(trail.eventsFor("alice")).extracting(AuditEvent::target).containsExactly("T-1");
    }

    @Test
    void timeComesFromTheInjectedClock() {
        assertThat(trail.record("alice", "read_ticket", "T-1", "ALLOWED").at()).isEqualTo(FIXED);
    }
}

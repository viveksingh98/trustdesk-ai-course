package com.promptvidya.trustdesk.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Evidence as a first-class feature. Every event names an actor, an
 * action, a target, and an outcome; the trail is append-only by
 * construction — there is no method that removes or edits an event.
 *
 * <p>Events record decisions and reference targets. They never carry
 * untrusted content: no request payloads, no model words, no document
 * bodies — an audit trail that stores those becomes an injection
 * archive and a data-exposure liability in one.
 */
public final class AuditTrail {

    /** Targets are references, not content, so they stay short. */
    static final int MAXIMUM_TARGET_CHARACTERS = 200;

    /** One piece of evidence. Blank actor, action, or outcome is refused. */
    public record AuditEvent(Instant at, String actor, String action, String target, String outcome) {

        public AuditEvent {
            Objects.requireNonNull(at);
            requireText(actor, "actor");
            requireText(action, "action");
            requireText(outcome, "outcome");
            target = target == null ? "" : target;
            if (target.length() > MAXIMUM_TARGET_CHARACTERS) {
                throw new IllegalArgumentException("audit event target must be a reference, not content");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("audit event " + field + " must not be blank");
            }
        }
    }

    private final Clock clock;
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    public AuditTrail(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public AuditEvent record(String actor, String action, String target, String outcome) {
        var event = new AuditEvent(clock.instant(), actor, action, target, outcome);
        events.add(event);
        return event;
    }

    public List<AuditEvent> eventsFor(String actor) {
        return events.stream().filter(event -> event.actor().equals(actor)).toList();
    }

    public List<AuditEvent> eventsWithOutcome(String outcome) {
        return events.stream().filter(event -> event.outcome().equals(outcome)).toList();
    }

    public List<AuditEvent> all() {
        return Collections.unmodifiableList(events);
    }

    public int size() {
        return events.size();
    }
}

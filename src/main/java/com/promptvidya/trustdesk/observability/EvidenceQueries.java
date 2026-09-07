package com.promptvidya.trustdesk.observability;

import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.domain.AuditTrail.AuditEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The audit trail, asked questions. Every gate in TrustDesk has been
 * writing evidence rows since section three — actor, action, target,
 * outcome, time — and an investigation is a handful of queries over
 * them: what did this actor do in this window, what happened to this
 * target across every actor, which outcomes were refusals, and who is
 * being refused often enough to look at. Rows are references, so every
 * answer here is safe to put in front of a responder.
 */
public final class EvidenceQueries {

    /** Outcomes that mean a gate said no. */
    static final Set<String> REFUSAL_OUTCOMES = Set.of(
            "REFUSED", "DENIED_UNKNOWN_TOOL", "DENIED_NO_GRANT", "DENIED_NO_INTENT",
            "REQUIRES_HUMAN_APPROVAL", "REJECTED", "EXPIRED", "SUBSTITUTION", "STALE", "REPLAY",
            "RATE_LIMITED", "BUDGET_EXHAUSTED", "READ_ONLY", "HALTED");

    public record Window(Instant from, Instant to) {
        public Window {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("a window runs forward from a start to an end");
            }
        }

        boolean contains(Instant at) {
            return !at.isBefore(from) && !at.isAfter(to);
        }
    }

    private static final Comparator<AuditEvent> BY_TIME = Comparator.comparing(AuditEvent::at);

    private final AuditTrail trail;

    public EvidenceQueries(AuditTrail trail) {
        this.trail = Objects.requireNonNull(trail);
    }

    public static boolean isRefusal(AuditEvent event) {
        return REFUSAL_OUTCOMES.contains(event.outcome());
    }

    /** Everything one actor did inside a window, in time order. */
    public List<AuditEvent> timeline(String actor, Window window) {
        return select(event -> event.actor().equals(actor) && window.contains(event.at()));
    }

    /** Everything that happened to one target — a ticket, a request, an approval — across every actor. */
    public List<AuditEvent> story(String target) {
        return select(event -> event.target().equals(target));
    }

    /** Refusals inside a window, counted by outcome, so a spike has a name. */
    public Map<String, Long> refusalsByOutcome(Window window) {
        return trail.all().stream()
                .filter(event -> window.contains(event.at()) && isRefusal(event))
                .collect(Collectors.groupingBy(AuditEvent::outcome, TreeMap::new, Collectors.counting()));
    }

    /** Actors refused more than a threshold inside a window — the first list a responder opens. */
    public Map<String, Long> actorsRefusedMoreThan(int threshold, Window window) {
        return trail.all().stream()
                .filter(event -> window.contains(event.at()) && isRefusal(event))
                .collect(Collectors.groupingBy(AuditEvent::actor, TreeMap::new, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > threshold)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum, TreeMap::new));
    }

    private List<AuditEvent> select(Predicate<AuditEvent> filter) {
        return trail.all().stream().filter(filter).sorted(BY_TIME).toList();
    }
}

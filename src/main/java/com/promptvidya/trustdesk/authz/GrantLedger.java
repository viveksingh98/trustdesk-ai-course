package com.promptvidya.trustdesk.authz;

import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Least privilege in practice. Login and tokens say who someone is and
 * what they could in principle do; the ledger says what they hold right
 * now, per scope, with an expiry. The effective grants for a tool run
 * are the intersection: the token is the ceiling, the ledger is the
 * floor of the moment, and neither can widen the other.
 */
public final class GrantLedger {

    /** A standing grant has no expiry; a task grant dies on its own. */
    public record Grant(String subject, String scope, String grantedBy, Instant expiresAt) {
        public Grant {
            requireText(subject, "subject");
            requireText(scope, "scope");
            requireText(grantedBy, "grantedBy");
        }

        boolean activeAt(Instant now) {
            return expiresAt == null || expiresAt.isAfter(now);
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("grant " + field + " must not be blank");
            }
        }
    }

    static final Duration MAXIMUM_TASK_LIFETIME = Duration.ofHours(8);

    private final Clock clock;
    private final List<Grant> grants = new CopyOnWriteArrayList<>();

    public GrantLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** The course's standing reads: people may read their own tickets and their own shelf. */
    public static GrantLedger seeded(Clock clock) {
        var ledger = new GrantLedger(clock);
        ledger.standing("alice", "tickets:read", "onboarding");
        ledger.standing("alice", "policies:it-hardware", "onboarding");
        ledger.standing("bob", "tickets:read", "onboarding");
        return ledger;
    }

    public Grant standing(String subject, String scope, String grantedBy) {
        var grant = new Grant(subject, scope, grantedBy, null);
        grants.add(grant);
        return grant;
    }

    /** A task grant: short by default, never longer than a working day. */
    public Grant forTask(String subject, String scope, String grantedBy, Duration lifetime) {
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAXIMUM_TASK_LIFETIME) > 0) {
            throw new IllegalArgumentException("a task grant lives between one second and eight hours");
        }
        var grant = new Grant(subject, scope, grantedBy, clock.instant().plus(lifetime));
        grants.add(grant);
        return grant;
    }

    public boolean revoke(String subject, String scope) {
        return grants.removeIf(grant -> grant.subject().equals(subject) && grant.scope().equals(scope));
    }

    public Set<String> activeScopes(String subject) {
        var now = clock.instant();
        return grants.stream()
                .filter(grant -> grant.subject().equals(subject) && grant.activeAt(now))
                .map(Grant::scope)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The actor a tool run actually gets: token scopes intersected with what the ledger holds now. */
    public ActorContext narrow(ActorContext actor) {
        var effective = actor.scopes().stream()
                .filter(activeScopes(actor.subject())::contains)
                .collect(Collectors.toUnmodifiableSet());
        return new ActorContext(actor.subject(), effective);
    }
}

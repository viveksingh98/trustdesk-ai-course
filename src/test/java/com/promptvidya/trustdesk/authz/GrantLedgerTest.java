package com.promptvidya.trustdesk.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.promptvidya.trustdesk.authz.DecisionLayer.Outcome;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.identity.ActorContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Grants that expire, grants that are revoked, and a ceiling the ledger
 * can never raise — fed straight into the decision layer so the effect
 * on a real tool run is visible.
 */
class GrantLedgerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T09:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final GrantLedger ledger = GrantLedger.seeded(clock);
    private final DecisionLayer decisions = new DecisionLayer(ToolPolicy.trustDesk(), new AuditTrail(clock));
    private final ActorContext aliceToken =
            new ActorContext("alice", Set.of("tickets:read", "policies:it-hardware", "access:request"));

    @Test
    void standingGrantsHoldAndTheTokenIsTheCeiling() {
        ledger.standing("alice", "audit:read", "mistake");

        var effective = ledger.narrow(aliceToken);

        assertThat(effective.scopes()).containsExactlyInAnyOrder("tickets:read", "policies:it-hardware");
        assertThat(decisions.decide(effective, "ticketById", null).allowed()).isTrue();
        assertThat(decisions.decide(effective, "requestAccess", "laptop").outcome()).isEqualTo(Outcome.DENIED_NO_GRANT);
    }

    @Test
    void aTaskGrantOpensTheToolAndClosesItWhenItExpires() {
        ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofMinutes(15));

        assertThat(decisions.decide(ledger.narrow(aliceToken), "requestAccess", "laptop for the new hire").allowed()).isTrue();

        now.set(now.get().plus(Duration.ofMinutes(16)));
        assertThat(decisions.decide(ledger.narrow(aliceToken), "requestAccess", "laptop for the new hire").outcome())
                .isEqualTo(Outcome.DENIED_NO_GRANT);
    }

    @Test
    void revocationTakesEffectOnTheNextRun() {
        ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofHours(1));
        assertThat(ledger.activeScopes("alice")).contains("access:request");

        assertThat(ledger.revoke("alice", "access:request")).isTrue();

        assertThat(ledger.activeScopes("alice")).doesNotContain("access:request");
        assertThat(decisions.decide(ledger.narrow(aliceToken), "requestAccess", "laptop").outcome())
                .isEqualTo(Outcome.DENIED_NO_GRANT);
    }

    @Test
    void taskGrantsAreShortByConstructionAndNeverWiden() {
        assertThatThrownBy(() -> ledger.forTask("alice", "access:request", "hardware-lead", Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.forTask("alice", "access:request", "hardware-lead", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        ledger.forTask("bob", "payroll:export", "attacker", Duration.ofMinutes(5));
        var bobToken = new ActorContext("bob", Set.of("tickets:read"));

        assertThat(ledger.narrow(bobToken).scopes()).containsExactly("tickets:read");
    }
}

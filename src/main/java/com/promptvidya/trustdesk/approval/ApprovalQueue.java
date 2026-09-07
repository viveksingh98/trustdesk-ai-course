package com.promptvidya.trustdesk.approval;

import com.promptvidya.trustdesk.authz.DelegationChain;
import com.promptvidya.trustdesk.domain.AuditTrail;
import com.promptvidya.trustdesk.hardening.UntrustedText;
import com.promptvidya.trustdesk.identity.ActorContext;
import com.promptvidya.trustdesk.security.DelegatedTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * Pause, prove, proceed. A pending action records exactly what the agent
 * proposed — who, through what chain, which tool, a digest of the
 * arguments, a bounded intent, and when the proposal expires. A person,
 * never software, decides; the decision leaves evidence; and an approved
 * action can be marked executed exactly once, so a replay has nothing
 * left to spend.
 */
public final class ApprovalQueue {

    public enum State { PENDING, APPROVED, REJECTED, EXPIRED, EXECUTED }

    public record PendingAction(
            String id,
            String subject,
            String chain,
            String tool,
            String argumentsDigest,
            String intent,
            Instant requestedAt,
            Instant expiresAt,
            State state,
            String decidedBy,
            Instant decidedAt) {

        PendingAction moved(State next, String by, Instant at) {
            return new PendingAction(id, subject, chain, tool, argumentsDigest, intent, requestedAt, expiresAt, next, by, at);
        }

        public boolean staleAt(Instant now) {
            return state == State.PENDING && !expiresAt.isAfter(now);
        }
    }

    static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(30);
    static final String SUBMIT_ACTION = "approval_requested";
    static final String DECIDE_ACTION = "approval_decided";
    static final String EXECUTE_ACTION = "approval_executed";

    private final Clock clock;
    private final AuditTrail audit;
    private final Supplier<String> ids;
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    public ApprovalQueue(Clock clock, AuditTrail audit, Supplier<String> ids) {
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
        this.ids = Objects.requireNonNull(ids);
    }

    /** Pause: record the proposal, never run it. The digest is what the person will approve. */
    public PendingAction submit(ActorContext actor, DelegationChain chain, String tool, String argumentsJson, String intent) {
        var now = clock.instant();
        var digest = UntrustedText.digest(tool + "\n" + Objects.requireNonNullElse(argumentsJson, "").strip());
        var action = new PendingAction(
                ids.get(), actor.subject(), chain.describe(), tool, digest,
                UntrustedText.sanitize(intent), now, now.plus(DEFAULT_LIFETIME), State.PENDING, null, null);
        actions.put(action.id(), action);
        audit.record(actor.subject(), SUBMIT_ACTION, tool + "@" + chain.describe() + "#" + digest, State.PENDING.name());
        return action;
    }

    /** Prove: a person decides. Software acting for a person is refused, and a stale proposal expires instead of deciding. */
    public PendingAction decide(String id, boolean approve, Authentication decider) {
        if (decider == null || !decider.isAuthenticated() || DelegatedTokens.isDelegated(decider)) {
            throw new AccessDeniedException("approvals are decided by people, not by software acting for them");
        }
        var now = clock.instant();
        var moved = new AtomicBoolean(false);
        var decided = actions.computeIfPresent(id, (key, current) -> {
            if (current.staleAt(now)) {
                return current.moved(State.EXPIRED, null, now);
            }
            if (current.state() != State.PENDING) {
                return current;
            }
            moved.set(true);
            return current.moved(approve ? State.APPROVED : State.REJECTED, decider.getName(), now);
        });
        if (!moved.get()) {
            audit.record(decider.getName(), DECIDE_ACTION, id, decided == null ? "UNKNOWN" : decided.state().name());
            throw new AccessDeniedException("nothing pending to decide: " + id);
        }
        audit.record(decider.getName(), DECIDE_ACTION, id, decided.state().name());
        return decided;
    }

    /** Proceed, once: the transition itself is the proof — only the call that moved approved to executed succeeds. */
    public PendingAction markExecuted(String id) {
        var now = clock.instant();
        var moved = new AtomicBoolean(false);
        var executed = actions.computeIfPresent(id, (key, current) -> {
            if (current.state() != State.APPROVED) {
                return current;
            }
            moved.set(true);
            return current.moved(State.EXECUTED, current.decidedBy(), now);
        });
        if (!moved.get()) {
            audit.record("system", EXECUTE_ACTION, id, "REFUSED");
            throw new AccessDeniedException("not approved, or already executed: " + id);
        }
        audit.record(executed.subject(), EXECUTE_ACTION, id, State.EXECUTED.name());
        return executed;
    }

    public Optional<PendingAction> get(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    /** What a person should look at right now — stale proposals are expired on the way out. */
    public List<PendingAction> pending() {
        var now = clock.instant();
        actions.replaceAll((key, current) -> current.staleAt(now) ? current.moved(State.EXPIRED, null, now) : current);
        return actions.values().stream()
                .filter(action -> action.state() == State.PENDING)
                .sorted((left, right) -> left.requestedAt().compareTo(right.requestedAt()))
                .toList();
    }
}
